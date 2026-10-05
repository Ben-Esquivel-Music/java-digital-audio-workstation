package com.benesquivelmusic.daw.app.ui.recording;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Persisted session default input selection. Explicit per-track selections are
 * resolved by the capture routing union (story 326); selecting a session default
 * remains an asynchronous engine configuration change.
 * The legacy comparison helpers are retained for session-selection diagnostics.
 */
public interface SessionInputSelection {

    /** How a blank (backend-default) session device is named in user-facing text. */
    String DEFAULT_DEVICE_DISPLAY = "<default>";

    /**
     * Returns the persisted session input device name — the qualified name a
     * device offers via {@link AudioDeviceInfo#qualifiedName()} — or an empty
     * string when the backend default is in use.
     *
     * @return the session device name; never {@code null}
     */
    String currentDeviceName();

    /**
     * Makes {@code device} the session input: obtains recording-stop consent,
     * then persists the choice and applies it to the running engine on a worker.
     * Declined consent leaves the accepted session input intact. Selecting the device whose
     * {@link AudioDeviceInfo#qualifiedName() qualified name} already equals
     * {@link #currentDeviceName()} is a <strong>no-op</strong> — nothing is
     * persisted and the engine is not reconfigured; a different pending choice
     * is superseded. The per-track dialogs
     * preselect the session device, so confirming that preselection (the
     * common "Add Audio Track → OK" gesture) must not stop the transport and
     * reopen the stream. The no-op rule is that exact compare, not
     * {@link #isSessionDevice} (fix round 2): the predicate's bare-name
     * tolerance would also swallow a pick that differs from a legacy bare
     * session name only by host API — a silent ignore (design book §5.6) —
     * so such a pick persists the qualified name and applies. Implementations
     * report an apply failure through their notification seam rather than
     * throwing.
     *
     * @param device the device the user chose; must not be {@code null}
     */
    void select(AudioDeviceInfo device);

    /**
     * Whether {@code device} is the session input device (compared with
     * {@link AudioDeviceInfo#isSelectionFor}). A blank session name — the
     * backend default — matches no enumerated device: the default is not a
     * choice a track can agree with by index.
     *
     * @param device an enumerated device; must not be {@code null}
     * @return {@code true} when the persisted selection names {@code device}
     */
    default boolean isSessionDevice(AudioDeviceInfo device) {
        Objects.requireNonNull(device, "device must not be null");
        return AudioDeviceInfo.isSelectionFor(currentDeviceName(), device.name(), device.hostApi());
    }

    /**
     * Returns the {@link AudioDeviceInfo#index() index} of the session device
     * within {@code devices} — the value the input-port dialog preselects — or
     * {@link Track#NO_INPUT_DEVICE} when the session device is not in the list.
     *
     * @param devices the enumerated devices; must not be {@code null}
     * @return the matching device's index, or {@link Track#NO_INPUT_DEVICE}
     */
    default int selectedIndexIn(List<AudioDeviceInfo> devices) {
        Objects.requireNonNull(devices, "devices must not be null");
        for (AudioDeviceInfo device : devices) {
            if (isSessionDevice(device)) {
                return device.index();
            }
        }
        return Track.NO_INPUT_DEVICE;
    }

    /**
     * Diagnostic comparison of armed explicit selections with the persisted session default.
     * Different valid inputs can participate in the same capture union. An empty device
     * list provides no comparison; actual capture validation belongs to CaptureRoutingPlan.
     *
     * @return differing or unresolved selections in project order
     */
    default List<Track> mismatches(List<Track> tracks, List<AudioDeviceInfo> devices) {
        Objects.requireNonNull(tracks, "tracks must not be null");
        Objects.requireNonNull(devices, "devices must not be null");
        List<Track> conflicting = new ArrayList<>();
        if (devices.isEmpty()) {
            return conflicting; // no backend / enumeration failed: nothing to compare against
        }
        for (Track track : tracks) {
            if (!track.isArmed() || track.getInputDeviceIndex() == Track.NO_INPUT_DEVICE) {
                continue;
            }
            Optional<AudioDeviceInfo> chosen = resolve(track, devices);
            if (chosen.isEmpty() || !isSessionDevice(chosen.get())) {
                conflicting.add(track);
            }
        }
        return conflicting;
    }

    /** Builds a grouped diagnostic description of selections that differ from the session default. */
    default Optional<String> mismatchWarning(List<Track> tracks, List<AudioDeviceInfo> devices) {
        List<Track> conflicting = mismatches(tracks, devices);
        if (conflicting.isEmpty()) {
            return Optional.empty();
        }
        // Key: the chosen device's qualified name, or empty for the one
        // "no longer available" group. Insertion order = first-seen project order.
        Map<Optional<String>, List<String>> trackNamesByDevice = new LinkedHashMap<>();
        for (Track track : conflicting) {
            Optional<String> chosen = resolve(track, devices).map(AudioDeviceInfo::qualifiedName);
            trackNamesByDevice.computeIfAbsent(chosen, _ -> new ArrayList<>()).add(track.getName());
        }
        StringBuilder text = new StringBuilder("Session input is '")
                .append(displayName(currentDeviceName()))
                .append("'; ");
        boolean first = true;
        for (Map.Entry<Optional<String>, List<String>> group : trackNamesByDevice.entrySet()) {
            if (!first) {
                text.append("; ");
            }
            first = false;
            text.append("track(s) ").append(String.join(", ", group.getValue()));
            group.getKey().ifPresentOrElse(
                    device -> text.append(" chose '").append(device).append('\''),
                    () -> text.append(" chose an input device that is no longer available"));
        }

        return Optional.of(text.toString());
    }

    /**
     * Resolves a track's explicit per-track input index to the enumerated
     * device with that index.
     *
     * @param track   the track; must not be {@code null}
     * @param devices the enumerated devices; must not be {@code null}
     * @return the device the track chose, or empty when the track chose none
     *         or its index matches no enumerated device
     */
    static Optional<AudioDeviceInfo> resolve(Track track, List<AudioDeviceInfo> devices) {
        Objects.requireNonNull(track, "track must not be null");
        Objects.requireNonNull(devices, "devices must not be null");
        int index = track.getInputDeviceIndex();
        if (index == Track.NO_INPUT_DEVICE) {
            return Optional.empty();
        }
        for (AudioDeviceInfo device : devices) {
            if (device.index() == index) {
                return Optional.of(device);
            }
        }
        return Optional.empty();
    }

    /**
     * The user-facing form of a session device name: the name itself, or
     * {@link #DEFAULT_DEVICE_DISPLAY} when blank (the backend default).
     *
     * @param deviceName a persisted device name; may be {@code null}
     * @return the display text; never {@code null}
     */
    static String displayName(String deviceName) {
        return deviceName == null || deviceName.isBlank() ? DEFAULT_DEVICE_DISPLAY : deviceName;
    }
}
