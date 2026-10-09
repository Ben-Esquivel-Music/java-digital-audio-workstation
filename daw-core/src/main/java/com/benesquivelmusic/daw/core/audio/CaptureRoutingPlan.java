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
    /**
     * Frozen per-track routing. {@code device} is the stable input identity and the
     * only track-level device claim; an empty {@code device} records from the session
     * default input. A pre-identity project's legacy device position
     * ({@link Track#getLegacyInputDeviceIndexHint()}) is deliberately not carried: a
     * position cannot identify a device, earlier versions never captured by it, and it
     * is offered only as a suggestion to confirm in the per-track input dialog — so a
     * hint-only track routes exactly like a track with no device of its own.
     */
    public record Route(String id, String name, InputRouting routing, Optional<DeviceId> device) {
        public Route {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(routing, "routing must not be null");
            Objects.requireNonNull(device, "device must not be null");
        }
        /** Whether the track names its own input identity; otherwise it records from the session default input. */
        public boolean hasExplicitDevice() { return device.isPresent(); }
        public boolean matches(Track track) {
            return id.equals(track.getId()) && routing.equals(track.getInputRouting())
                    && device.equals(track.getInputDevice());
        }
    }
    public static List<Route> snapshot(List<Track> tracks) {
        return tracks.stream().map(t -> new Route(t.getId(), t.getName(), t.getInputRouting(),
                t.getInputDevice())).toList();
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
        AudioDeviceInfo selectedInfo = selectedChoice.isDefault() ? defaultInputDevice(devices).orElse(null)
                : inputsLabelled(devices, selectedChoice.name()).stream().findFirst().orElse(null);
        // The alias becomes the concrete device BEFORE any grouping, so a default route and an explicit
        // route to that same device form one source instead of a phantom two-device union.
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
            // A track with no device of its own records from the selection, already resolved above.
            AudioDeviceInfo info = selectedInfo;
            DeviceId input = selected;
            if (track.device().isPresent()) {
                info = byIdentity(backend, track, track.device().get(), devices);
                input = new DeviceId(backend.name(), info.qualifiedName());
            }
            // A qualified label and a bare label can identify the same selected endpoint — but a bare
            // label is shared by every endpoint with that name, so it merges only the endpoint the
            // selection itself resolved to, never a same-named sibling the track identifies.
            if (info != null && (selected.name().equals(info.qualifiedName())
                    || info.equals(selectedInfo) && selected.name().equals(info.name()))) input = selected;
            if (singleDevice && !input.equals(selected)) {
                throw refusal(track, input, backend.name() + " only captures the active "
                        + (selected.isDefault() ? "default input" : "device '" + selected.name() + "'"));
            }
            if (info != null && info.hostApi().equalsIgnoreCase("ASIO")) {
                if (asioInput != null && !asioInput.equals(input))
                    throw refusal(track, input, "ASIO only allows one active input device '" + asioInput.name() + "'");
                asioInput = input;
            }
            // Every device the union may open is probed — the selection and an explicitly routed sibling
            // alike — so an over-wide route is refused at arm time, not after opening at Record. The single-
            // device and one-active-ASIO-driver rules above already refused any input this backend cannot
            // open alongside the selection; clock-domain agreement is checked after the union is built.
            if (checkWidths && (info == null || !info.hasKnownInputChannelCount())) {
                OptionalInt capacity;
                try { capacity = inputCapacities.computeIfAbsent(input, backend::inputChannelCapacity); }
                catch (RuntimeException failure) {
                    AudioBackendException refused = refusal(track, input, "input capability query failed: " + failure.getMessage());
                    refused.initCause(failure);
                    throw refused;
                }
                if (capacity.isPresent()) widthValidated.add(track.id());
                if (capacity.isPresent() && width > capacity.getAsInt())
                    throw refusal(track, input, route.displayName() + " exceeds " + capacity.getAsInt() + " input channels");
            }
            if (checkWidths && info != null && (!info.supportsInput() || info.hasKnownInputChannelCount() && width > info.maxInputChannels())) {
                throw refusal(track, input, route.displayName() + " exceeds " + info.maxInputChannels() + " input channels");
            }
            if (checkWidths && info != null && info.hasKnownInputChannelCount()) widthValidated.add(track.id());
            assignments.put(track.id(), input);
            widths.merge(input, (int) width, Math::max);
        }
        DeviceId resolvedSelected = selected;
        List<Source> sources = widths.entrySet().stream().map(e -> {
            List<String> labels = new ArrayList<>();
            labels.add(e.getKey().name());
            List<AudioDeviceInfo> qualified = inputMatches(devices, candidate -> candidate.qualifiedName().equals(e.getKey().name()));
            AudioDeviceInfo info = (qualified.isEmpty() ? inputMatches(devices, candidate -> candidate.name().equals(e.getKey().name()))
                    : qualified).stream().findFirst().orElse(null);
            if (info != null) {
                labels.add(info.qualifiedName());
                // The bare name is an alias only when, as an input label, it names this device alone.
                if (inputMatches(devices, candidate -> candidate.name().equals(info.name())).equals(List.of(info))) {
                    labels.add(info.name());
                } else {
                    labels.removeIf(info.name()::equals);
                }
            }
            if (e.getKey().equals(resolvedSelected) && selectedChoice.isDefault()) labels.add(selectedChoice.name());
            return new Source(e.getKey(), e.getValue(), labels);
        }).toList();
        requireSharedClockDomain(backend, tracks, assignments, sources);
        Map<String, Integer> ordinals = new LinkedHashMap<>();
        assignments.forEach((id, device) -> {
            for (int i = 0; i < sources.size(); i++) if (sources.get(i).device().equals(device)) ordinals.put(id, i);
        });
        return new CaptureRoutingPlan(backend, sources, ordinals, widthValidated);
    }
    /**
     * Resolves a frozen identity against the enumerated devices: same backend and exactly one device
     * with that qualified name, after the capture preference of {@link #inputMatches}. The enumeration
     * index is never consulted, so reordered devices still resolve to the same endpoint and a vanished
     * one is refused instead of replaced by its index-mate.
     */
    private static AudioDeviceInfo byIdentity(AudioBackend backend, Route track, DeviceId identity, List<AudioDeviceInfo> devices) {
        if (!identity.backend().equals(backend.name()))
            throw refusal(track, identity, "the device belongs to backend '" + identity.backend()
                    + "', but capture records from backend '" + backend.name() + "'");
        List<AudioDeviceInfo> matches = inputMatches(devices, d -> d.qualifiedName().equals(identity.name()));
        if (matches.isEmpty()) throw refusal(track, identity, "input device is unavailable");
        if (matches.size() > 1) throw refusal(track, identity, "input device identity is ambiguous (" + matches.size() + " devices share it)");
        return matches.getFirst();
    }
    /**
     * The enumerated devices an input label names: those whose qualified or bare name equals it, after
     * the capture preference of {@link #inputMatches}, in one pass. It is the lookup the plan resolves
     * the session input selection with, and input paths outside this class read a label through it, so
     * they apply the same name/qualified-name test and the same capture preference as that resolution.
     * It does not reproduce the plan's other lookups: {@link #byIdentity} matches qualified names only,
     * and the Source label lookup gives a qualified-name match priority over a bare-name match.
     */
    static List<AudioDeviceInfo> inputsLabelled(List<AudioDeviceInfo> devices, String label) {
        return inputMatches(devices, d -> d.qualifiedName().equals(label) || d.name().equals(label));
    }
    /**
     * The devices {@code named} selects, narrowed to those that capture when it selects several
     * ({@link AudioDeviceInfo#preferDirection}): Java Sound lists a playback mixer and a capture mixer
     * under one name and one qualified label, and as an input that label means the mixer that captures.
     * Two capturing devices stay ambiguous, and a single device that cannot capture is kept, so its
     * width check refuses it; neither is ever replaced by another device.
     */
    private static List<AudioDeviceInfo> inputMatches(List<AudioDeviceInfo> devices, java.util.function.Predicate<AudioDeviceInfo> named) {
        return AudioDeviceInfo.preferDirection(devices.stream().filter(named).toList(), AudioDeviceInfo::supportsInput);
    }
    /**
     * Sibling sources count their own delivered frames, so the union is only sound when every
     * device runs from the primary input's hardware clock; otherwise their files drift apart
     * progressively during a take. Refuses the first track routed to an unsynchronized device.
     */
    private static void requireSharedClockDomain(AudioBackend backend, List<Route> tracks,
                                                 Map<String, DeviceId> assignments, List<Source> sources) {
        if (sources.size() < 2) return;
        DeviceId primary = sources.getFirst().device();
        for (Source source : sources.subList(1, sources.size())) {
            if (backend.sharesClockDomain(primary, source.device())) continue;
            Route track = tracks.stream().filter(route -> source.device().equals(assignments.get(route.id())))
                    .findFirst().orElseThrow();
            throw refusal(track, source.device(), "input is not clock-synchronized with " + describe(primary)
                    + "; independent device clocks drift apart during a take");
        }
    }
    /**
     * The concrete device behind the backend's default-input alias, when the enumeration alone
     * proves it: the only device listed, or else the only device able to capture. Empty when
     * several devices could be the default; the alias then stays unresolved.
     */
    private static Optional<AudioDeviceInfo> defaultInputDevice(List<AudioDeviceInfo> devices) {
        if (devices.size() == 1) return Optional.of(devices.getFirst());
        List<AudioDeviceInfo> capturing = devices.stream().filter(AudioDeviceInfo::supportsInput).toList();
        return capturing.size() == 1 ? Optional.of(capturing.getFirst()) : Optional.empty();
    }
    /** Names a device for an error; an unresolved default alias is never presented as a device. */
    static String describe(DeviceId device) {
        return device.isDefault() ? "the default input of '" + device.backend() + "', which does not resolve to a specific device"
                : "device '" + device.name() + "'";
    }
    private static AudioBackendException refusal(Route track, DeviceId device, String detail) {
        return new AudioBackendException("Track '" + track.name() + "', " + describe(device) + ": " + detail);
    }
}
