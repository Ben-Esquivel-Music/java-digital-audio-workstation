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
 * The ONE session-level audio input device — story 322, Audio Engine Wiring
 * Design Book §5.6 ("Per-track input device → session-level input selection +
 * mismatch warning").
 *
 * <p>Recording opens the engine's provisioned input device (story 316:
 * {@code TransportController} calls {@code startAudioInputOutput()} with no
 * per-track index), which is the device {@code SettingsModel.getAudioInputDevice()}
 * names. The per-track choice a user makes in the input-port dialog
 * ({@code Track.setInputDeviceIndex}) is kept as persisted intent for story
 * 326's multi-device capture, but it does not route audio today. This type
 * makes that honest: every per-track dialog also {@linkplain #select(AudioDeviceInfo)
 * selects} the session device, and whenever armed tracks disagree with it the
 * surfaces show a single {@code WARNING} built by {@link #mismatchWarning} —
 * never a silent ignore.</p>
 *
 * <p>The pure comparison logic ({@link #mismatches}, {@link #mismatchWarning},
 * {@link #selectedIndexIn}) lives here as default methods so the production
 * implementation ({@link SettingsBackedSessionInputSelection}) and any test
 * stub share exactly one definition of "disagrees with the session device".
 * Device identity is compared with {@link AudioDeviceInfo#isSelectionFor}, the
 * repo's single rule for matching a persisted selection to an enumerated
 * device (it tolerates a bare name saved before qualified names existed).</p>
 *
 * <p>Threading: {@link #select(AudioDeviceInfo)} is called on the FX thread and
 * must never block it — the engine reconfiguration runs on a worker; the read
 * methods are trivially thread-safe.</p>
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
     * Makes {@code device} the session input: persists the choice and applies
     * it to the running engine. Selecting the device whose
     * {@link AudioDeviceInfo#qualifiedName() qualified name} already equals
     * {@link #currentDeviceName()} is a <strong>no-op</strong> — nothing is
     * persisted and the engine is not reconfigured: the per-track dialogs
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
     * Returns the armed tracks whose explicit per-track input choice
     * ({@code inputDeviceIndex != NO_INPUT_DEVICE}) resolves to an enumerated
     * device that is not the session device — the tracks recording will
     * silently serve from the wrong device unless the user is told.
     *
     * <p>Unarmed tracks are irrelevant to the next take and skipped. A track
     * whose index resolves to no enumerated device cannot be named and is
     * skipped as well (its choice is dangling, not a disagreement).</p>
     *
     * @param tracks  the project's tracks; must not be {@code null}
     * @param devices the enumerated devices; must not be {@code null}
     * @return the conflicting armed tracks, in project order; never {@code null}
     */
    default List<Track> mismatches(List<Track> tracks, List<AudioDeviceInfo> devices) {
        Objects.requireNonNull(tracks, "tracks must not be null");
        Objects.requireNonNull(devices, "devices must not be null");
        List<Track> conflicting = new ArrayList<>();
        for (Track track : tracks) {
            if (!track.isArmed()) {
                continue;
            }
            Optional<AudioDeviceInfo> chosen = resolve(track, devices);
            if (chosen.isPresent() && !isSessionDevice(chosen.get())) {
                conflicting.add(track);
            }
        }
        return conflicting;
    }

    /**
     * Builds the single {@code WARNING} text for the current mismatches, e.g.
     * {@code Recording uses the session input 'Mic In [ASIO]'; track(s) Vox,
     * Guitar chose 'USB In [WASAPI]' — multi-device capture is story 326}, or
     * {@link Optional#empty()} when every armed track agrees with the session
     * device. Tracks that chose different devices are grouped per device and
     * the groups joined with {@code "; "}, so every conflicting device is named.
     *
     * @param tracks  the project's tracks; must not be {@code null}
     * @param devices the enumerated devices; must not be {@code null}
     * @return the warning text, or empty when there is nothing to warn about
     */
    default Optional<String> mismatchWarning(List<Track> tracks, List<AudioDeviceInfo> devices) {
        List<Track> conflicting = mismatches(tracks, devices);
        if (conflicting.isEmpty()) {
            return Optional.empty();
        }
        Map<String, List<String>> trackNamesByDevice = new LinkedHashMap<>();
        for (Track track : conflicting) {
            String chosen = resolve(track, devices).map(AudioDeviceInfo::qualifiedName).orElseThrow();
            trackNamesByDevice.computeIfAbsent(chosen, _ -> new ArrayList<>()).add(track.getName());
        }
        StringBuilder text = new StringBuilder("Recording uses the session input '")
                .append(displayName(currentDeviceName()))
                .append("'; ");
        boolean first = true;
        for (Map.Entry<String, List<String>> group : trackNamesByDevice.entrySet()) {
            if (!first) {
                text.append("; ");
            }
            first = false;
            text.append("track(s) ")
                    .append(String.join(", ", group.getValue()))
                    .append(" chose '")
                    .append(group.getKey())
                    .append('\'');
        }
        text.append(" — multi-device capture is story 326");
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
