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
 * never a silent ignore. An armed track whose persisted index resolves to
 * none of the enumerated devices (the interface was unplugged) is one such
 * disagreement: over a non-empty enumeration it is reported as an unavailable
 * device, not dropped (PR #977 review) — an empty enumeration compares
 * nothing, see {@link #mismatches}. A stale index may instead name an
 * unrelated device that now carries it (a backend change); that is treated as
 * a choice of that device (reported only when that device is not the session
 * device), since only the bare index is persisted.</p>
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
     * Returns the armed tracks whose explicit per-track input choice
     * ({@code inputDeviceIndex != NO_INPUT_DEVICE}) is not the session device
     * — the tracks recording will silently serve from the wrong device unless
     * the user is told. A choice disagrees in two ways: it resolves to an
     * enumerated device other than the session device, or it resolves to
     * <em>no</em> enumerated device at all. The index is persisted
     * ({@code ProjectSerializer} writes {@code input-device}), so a project
     * reopened after an interface was unplugged may hold indices that resolve
     * to nothing — and after a backend change a stale index may instead name
     * an unrelated device, which is then treated as a choice of that device
     * (reported only when that device is not the session device; only the
     * bare int is persisted); treating an unresolved index as "not a
     * disagreement" was the silent ignore §5.6 forbids (PR #977 review).
     *
     * <p>Unarmed tracks are irrelevant to the next take and skipped. When
     * {@code devices} is empty nothing is reported: both production callers
     * pass an empty list when there is no backend or the enumeration failed,
     * and that means "nothing to compare", not "every device is unavailable" —
     * flagging every armed track on an enumeration failure would be a false
     * warning.</p>
     *
     * @param tracks  the project's tracks; must not be {@code null}
     * @param devices the enumerated devices, or empty when they could not be
     *                enumerated; must not be {@code null}
     * @return the conflicting armed tracks, in project order; never {@code null}
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

    /**
     * Builds the single {@code WARNING} text for the current mismatches, e.g.
     * {@code Recording uses the session input 'Mic In [ASIO]'; track(s) Vox,
     * Guitar chose 'USB In [WASAPI]' — multi-device capture is story 326}, or
     * {@link Optional#empty()} when every armed track agrees with the session
     * device — or when {@code devices} is empty, which compares nothing (see
     * {@link #mismatches}). Tracks that chose different devices are grouped per device and
     * the groups joined with {@code "; "}, so every conflicting device is named.
     * Tracks whose persisted index resolves to no enumerated device share ONE
     * "unavailable" group — {@code track(s) Drums chose an input device that is
     * no longer available} — because a device index means nothing to a user;
     * that group takes its place among the others in first-seen project order.
     *
     * @param tracks  the project's tracks; must not be {@code null}
     * @param devices the enumerated devices, or empty when they could not be
     *                enumerated; must not be {@code null}
     * @return the warning text, or empty when there is nothing to warn about
     */
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
        StringBuilder text = new StringBuilder("Recording uses the session input '")
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
