package com.benesquivelmusic.daw.app.ui.recording;

import com.benesquivelmusic.daw.core.audio.AudioDeviceManager;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only view of the persisted session default input — the device the
 * Audio Settings session selector and the first-run wizard choose (and
 * {@code SettingsModel.resetToDefaults} clears back to the backend default).
 * Explicit per-track selections are stable {@link DeviceId} identities
 * resolved by the capture routing union (story 326) and never
 * rewrite the session default: a per-track pick must not start the
 * asynchronous engine reconfiguration that changing the session default is,
 * because that would race the arm guard's validation of the very track being
 * changed. The new-track and per-track input dialogs only read this selection, as
 * the preselection fallback for a track with no input of its own.
 */
public interface SessionInputSelection {

    /**
     * Returns the persisted session input device name — the qualified name a
     * device offers via {@link AudioDeviceInfo#qualifiedName()} — or an empty
     * string when the backend default is in use.
     *
     * @return the session device name; never {@code null}
     */
    String currentDeviceName();

    /**
     * Whether {@code device} is the session input device. The persisted
     * session value is a device NAME, never an enumeration index: it is
     * compared with {@link AudioDeviceInfo#isSelectionFor}, so a qualified
     * name matches exactly that endpoint while a legacy bare name saved before
     * qualified names existed still matches a same-named endpoint. A blank
     * session name — the backend default — matches no enumerated device: the
     * default is not a concrete row the input-port dialog can preselect.
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
     * Resolves a track's explicit input to the enumerated device it names.
     * A stable identity resolves only on the backend it names — an identity
     * stamped by another backend never matches a same-labelled device of the
     * listing backend (capture would refuse it, CaptureRoutingPlan) — and then
     * by its qualified endpoint name, never by position, so a reordered list
     * still yields the same device; a name that matches no device, or more
     * than one, resolves to nothing rather than to whatever now occupies the
     * old position. Only a track without an identity falls back to its legacy
     * index hint, and only because this is the input-port dialog's
     * preselection ({@code TrackStripController.preselectedInputIndex}): the
     * hinted row is offered only as a suggestion to confirm, and the confirmed
     * pick stores an identity (clearing the hint). What is captured is decided
     * by {@code CaptureRoutingPlan}, which never resolves a hint: a hint-only
     * track records from the session default input, as earlier versions did.
     *
     * @param track       the track; must not be {@code null}
     * @param enumeration the listing backend and its devices; must not be {@code null}
     * @return the device the track chose, or empty when the track chose none
     *         or its choice matches no unique device of the listing backend
     */
    static Optional<AudioDeviceInfo> resolve(Track track, AudioDeviceManager.Enumeration enumeration) {
        Objects.requireNonNull(track, "track must not be null");
        Objects.requireNonNull(enumeration, "enumeration must not be null");
        List<AudioDeviceInfo> devices = enumeration.devices();
        Optional<DeviceId> identity = track.getInputDevice();
        if (identity.isPresent()) {
            if (!enumeration.backendName().equals(Optional.of(identity.get().backend()))) {
                return Optional.empty();
            }
            List<AudioDeviceInfo> matches = devices.stream()
                    .filter(device -> device.qualifiedName().equals(identity.get().name()))
                    .toList();
            return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
        }
        int index = track.getLegacyInputDeviceIndexHint();
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
}
