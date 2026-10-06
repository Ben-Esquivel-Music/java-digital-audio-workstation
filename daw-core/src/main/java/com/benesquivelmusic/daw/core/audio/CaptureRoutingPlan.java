package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.audio.*;
import java.util.*;

/** Immutable physical input union, resolved before opening any stream (story 326). */
public record CaptureRoutingPlan(AudioBackend backend, List<Source> sources, Map<String, Integer> trackSources, Set<String> widthValidatedTracks) {
    public record Source(DeviceId device, int requestedChannels, List<String> selectionLabels) {
        public Source {
            selectionLabels = List.copyOf(selectionLabels);
        }
        public Source(DeviceId device, int requestedChannels) {
            this(device, requestedChannels, List.of(device.name()));
        }
    }
    public record Route(String id, String name, InputRouting routing, int deviceIndex) {
        public boolean matches(Track track) {
            return id.equals(track.getId()) && routing.equals(track.getInputRouting()) && deviceIndex == track.getInputDeviceIndex();
        }
    }
    public static List<Route> snapshot(List<Track> tracks) {
        return tracks.stream().map(t -> new Route(t.getId(), t.getName(), t.getInputRouting(), t.getInputDeviceIndex())).toList();
    }
    public CaptureRoutingPlan {
        sources = List.copyOf(sources);
        trackSources = Map.copyOf(trackSources);
        widthValidatedTracks = Set.copyOf(widthValidatedTracks);
    }
    public int sourceFor(Track track) { return trackSources.getOrDefault(track.getId(), 0); }

    public static CaptureRoutingPlan resolve(BackendStreamRung rung, List<Track> tracks,
                                             List<AudioDeviceInfo> devices, boolean checkWidths) {
        return resolveSnapshots(rung, snapshot(tracks), devices, checkWidths);
    }
    public static CaptureRoutingPlan resolveSnapshots(BackendStreamRung rung, List<Route> tracks,
                                                       List<AudioDeviceInfo> devices, boolean checkWidths) {
        AudioBackend backend = rung.backend();
        if (tracks.stream().noneMatch(track -> !track.routing().isNone()))
            return new CaptureRoutingPlan(backend, List.of(), Map.of(), Set.of());
        DeviceId selectedChoice;
        try { selectedChoice = backend.selectedInputDevice(rung.device()); }
        catch (RuntimeException failure) {
            Route track = tracks.stream().filter(route -> !route.routing().isNone()).findFirst().orElseThrow();
            throw refusal(track, rung.device(), "input device resolution failed: " + failure.getMessage());
        }
        AudioDeviceInfo selectedInfo = devices.stream().filter(d -> d.qualifiedName().equals(selectedChoice.name()) || d.name().equals(selectedChoice.name()))
                .findFirst().orElse(null);
        if (selectedChoice.isDefault() && devices.size() == 1) selectedInfo = devices.getFirst();
        DeviceId selected = selectedChoice.isDefault() && selectedInfo != null
                ? new DeviceId(backend.name(), selectedInfo.qualifiedName()) : selectedChoice;
        boolean singleDevice = !backend.supportsMultipleInputDevices()
                || selectedInfo != null && selectedInfo.hostApi().equalsIgnoreCase("ASIO");
        LinkedHashMap<DeviceId, Integer> widths = new LinkedHashMap<>();
        Map<String, DeviceId> assignments = new LinkedHashMap<>();
        DeviceId asioInput = null;
        Set<String> widthValidated = new HashSet<>();
        Map<DeviceId, OptionalInt> inputCapacities = new HashMap<>();
        for (Route track : tracks) {
            InputRouting route = track.routing();
            if (route.isNone()) continue;
            long width = (long) route.firstChannel() + route.channelCount();
            if (width > Integer.MAX_VALUE) throw refusal(track, selected, "channel range overflows");
            AudioDeviceInfo info = selectedInfo;
            DeviceId input = selected;
            if (track.deviceIndex() >= 0) {
                info = devices.stream().filter(d -> d.index() == track.deviceIndex()).findFirst()
                        .orElseThrow(() -> refusal(track, selected, "input device index " + track.deviceIndex() + " is unavailable"));
                input = new DeviceId(backend.name(), info.qualifiedName());
            } else if (!selected.isDefault()) {
                info = devices.stream().filter(d -> d.qualifiedName().equals(selected.name()) || d.name().equals(selected.name()))
                        .findFirst().orElse(null);
            }
            // A qualified label and a bare label can identify the same selected endpoint.
            if (info != null && (selected.name().equals(info.name()) || selected.name().equals(info.qualifiedName()))) input = selected;
            if (singleDevice && !input.equals(selected)) {
                throw refusal(track, input, backend.name() + " only captures the active device '" + selected.name() + "'");
            }
            if (checkWidths && (info == null || !info.hasKnownInputChannelCount()) && input.equals(selected)) {
                OptionalInt capacity = inputCapacities.computeIfAbsent(input, backend::inputChannelCapacity);
                if (capacity.isPresent()) widthValidated.add(track.id());
                if (capacity.isPresent() && width > capacity.getAsInt())
                    throw refusal(track, input, route.displayName() + " exceeds " + capacity.getAsInt() + " input channels");
            }
            if (checkWidths && info != null && (!info.supportsInput() || info.hasKnownInputChannelCount() && width > info.maxInputChannels())) {
                throw refusal(track, input, route.displayName() + " exceeds " + info.maxInputChannels() + " input channels");
            }
            if (info != null && info.hostApi().equalsIgnoreCase("ASIO")) {
                if (asioInput != null && !asioInput.equals(input))
                    throw refusal(track, input, "ASIO only allows one active input device '" + asioInput.name() + "'");
                asioInput = input;
            }
            if (checkWidths && info != null && info.hasKnownInputChannelCount()) widthValidated.add(track.id());
            assignments.put(track.id(), input);
            widths.merge(input, (int) width, Math::max);
        }
        DeviceId resolvedSelected = selected;
        List<Source> sources = widths.entrySet().stream().map(e -> {
            List<String> labels = new ArrayList<>();
            labels.add(e.getKey().name());
            AudioDeviceInfo info = devices.stream().filter(candidate -> candidate.qualifiedName().equals(e.getKey().name()))
                    .findFirst().orElseGet(() -> devices.stream().filter(candidate -> candidate.name().equals(e.getKey().name()))
                            .findFirst().orElse(null));
            if (info != null) {
                labels.add(info.qualifiedName());
                if (devices.stream().filter(candidate -> candidate.name().equals(info.name())).count() == 1) {
                    labels.add(info.name());
                } else {
                    labels.removeIf(info.name()::equals);
                }
            }
            if (e.getKey().equals(resolvedSelected) && selectedChoice.isDefault()) labels.add(selectedChoice.name());
            return new Source(e.getKey(), e.getValue(), labels);
        }).toList();
        Map<String, Integer> ordinals = new LinkedHashMap<>();
        assignments.forEach((id, device) -> {
            for (int i = 0; i < sources.size(); i++) if (sources.get(i).device().equals(device)) ordinals.put(id, i);
        });
        return new CaptureRoutingPlan(backend, sources, ordinals, widthValidated);
    }
    private static AudioBackendException refusal(Route track, DeviceId device, String detail) {
        return new AudioBackendException("Track '" + track.name() + "', device '" + device.name() + "': " + detail);
    }
}
