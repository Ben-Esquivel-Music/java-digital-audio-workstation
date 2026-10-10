package com.benesquivelmusic.daw.app.ui.recording;

import com.benesquivelmusic.daw.app.ui.SettingsModel;
import com.benesquivelmusic.daw.core.audio.AudioDeviceManager;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 322 — the ONE session-level input selection (Audio Engine Wiring
 * Design Book §5.6 "Per-track input device"), a read-only view of the
 * persisted preference the Audio Settings session selector writes: it reads
 * that preference live and is only the input dialogs' preselection fallback.
 * A per-track identity resolves by name, and only on the backend it names
 * (story 326).
 */
class SessionInputSelectionTest {

    /** The backend that lists {@link #DEVICES} — not a host API: one backend can list several. */
    private static final String BACKEND = "PortAudio";

    private static final AudioDeviceInfo MIC = AudioDeviceInfo.unprobed(0, "Mic In", "ASIO");
    private static final AudioDeviceInfo USB = AudioDeviceInfo.unprobed(1, "USB In", "WASAPI");
    private static final AudioDeviceInfo LINE = AudioDeviceInfo.unprobed(2, "Line In", "WASAPI");
    private static final List<AudioDeviceInfo> DEVICES = List.of(MIC, USB, LINE);
    private static final AudioDeviceManager.Enumeration ENUMERATION = listedBy(BACKEND, DEVICES);

    private Preferences prefs;

    @AfterEach
    void dropPreferences() throws BackingStoreException {
        if (prefs != null) {
            prefs.removeNode();
        }
    }

    private SettingsModel newSettings() {
        prefs = Preferences.userRoot().node("sessionInputSelectionTest_" + System.nanoTime());
        return new SettingsModel(prefs);
    }

    private static AudioDeviceManager.Enumeration listedBy(String backend, List<AudioDeviceInfo> devices) {
        return new AudioDeviceManager.Enumeration(Optional.of(backend), devices);
    }

    @Test
    void theSessionDeviceIsWhateverTheSettingsSelectorLastPersisted() {
        SettingsModel settings = newSettings();
        SessionInputSelection selection = new SettingsBackedSessionInputSelection(settings);
        assertThat(selection.currentDeviceName()).isEmpty();
        assertThat(selection.selectedIndexIn(DEVICES)).isEqualTo(Track.NO_INPUT_DEVICE);

        settings.setAudioInputDevice(USB.qualifiedName());   // what the Audio Settings selector commits

        assertThat(selection.currentDeviceName()).isEqualTo("USB In [WASAPI]");
        assertThat(selection.isSessionDevice(USB)).isTrue();
        assertThat(selection.selectedIndexIn(DEVICES)).isEqualTo(USB.index());
    }

    @Test
    void blankSessionDeviceIsTheDefaultAndPreselectsNoRow() {
        SettingsModel settings = newSettings();
        settings.setAudioInputDevice("");
        SessionInputSelection selection = new SettingsBackedSessionInputSelection(settings);

        assertThat(selection.selectedIndexIn(DEVICES)).isEqualTo(Track.NO_INPUT_DEVICE);
        assertThat(selection.isSessionDevice(MIC)).isFalse();
    }

    @Test
    void restoringDefaultsClearsTheSessionDeviceBackToTheBackendDefault() {
        SettingsModel settings = newSettings();
        settings.setAudioInputDevice(USB.qualifiedName());
        SessionInputSelection selection = new SettingsBackedSessionInputSelection(settings);
        assertThat(selection.selectedIndexIn(DEVICES)).isEqualTo(USB.index());

        settings.resetToDefaults();   // the other writer of the session input

        assertThat(selection.currentDeviceName()).isEmpty();
        assertThat(selection.selectedIndexIn(DEVICES)).isEqualTo(Track.NO_INPUT_DEVICE);
    }

    @Test
    void legacyBareNameStillNamesTheSessionDevice() {
        SettingsModel settings = newSettings();
        settings.setAudioInputDevice("Mic In");   // saved before qualified names existed
        SessionInputSelection selection = new SettingsBackedSessionInputSelection(settings);

        assertThat(selection.isSessionDevice(MIC)).isTrue();
        assertThat(selection.selectedIndexIn(DEVICES)).isEqualTo(MIC.index());
    }

    @Test
    void anExplicitIdentityResolvesByNameAfterTheEnumerationIsReordered() {
        Track vox = armed("Vox", USB.index());
        List<AudioDeviceInfo> reordered = List.of(
                AudioDeviceInfo.unprobed(0, "USB In", "WASAPI"),
                AudioDeviceInfo.unprobed(1, "Line In", "WASAPI"),
                AudioDeviceInfo.unprobed(2, "Mic In", "ASIO"));

        assertThat(SessionInputSelection.resolve(vox, listedBy(BACKEND, reordered)))
                .map(AudioDeviceInfo::qualifiedName)
                .hasValue(USB.qualifiedName());
        assertThat(SessionInputSelection.resolve(vox, listedBy(BACKEND, List.of(MIC, LINE))))
                .as("a vanished identity resolves to nothing, never to the device now at its old index")
                .isEmpty();
    }

    @Test
    void anIdentityFromAnotherBackendNeverMatchesASameLabelledDevice() {
        // The track picked "USB In [WASAPI]" while another backend was active;
        // the listing backend offers an endpoint with the very same qualified
        // label. Capture refuses that identity (CaptureRoutingPlan: wrong
        // backend), so the preselection must not claim it resolves to the
        // same-labelled row.
        Track foreign = new Track("Foreign", TrackType.AUDIO);
        foreign.setArmed(true);
        foreign.setInputDevice(Optional.of(new DeviceId("Other Backend", USB.qualifiedName())));

        assertThat(SessionInputSelection.resolve(foreign, ENUMERATION)).isEmpty();
        assertThat(SessionInputSelection.resolve(foreign, listedBy("Other Backend", DEVICES)))
                .as("on its own backend the identity resolves").hasValue(USB);
    }

    @Test
    void aNameSharedByAPlaybackAndACaptureMixerPreselectsAndResolvesTheCaptureMixer() {
        // Java Sound on Windows: a playback-only and a capture-only mixer share one name, playback first.
        String spdif = "Digital Audio (S/PDIF)";
        AudioDeviceInfo playback = new AudioDeviceInfo(0, spdif, "Java Sound", 0, 2, 48_000, List.of(), 0, 0);
        AudioDeviceInfo capture = new AudioDeviceInfo(1, spdif, "Java Sound", 2, 0, 48_000, List.of(), 0, 0);
        List<AudioDeviceInfo> devices = List.of(playback, capture);
        SettingsModel settings = newSettings();
        SessionInputSelection selection = new SettingsBackedSessionInputSelection(settings);

        for (String persisted : List.of(spdif, capture.qualifiedName())) {
            settings.setAudioInputDevice(persisted);
            assertThat(selection.selectedIndexIn(devices))
                    .as("'%s' preselects the row the input dialog offers, the capture mixer", persisted)
                    .isEqualTo(capture.index());
        }

        Track guitar = new Track("Guitar", TrackType.AUDIO);
        guitar.setInputDevice(Optional.of(new DeviceId("Java Sound", capture.qualifiedName())));
        assertThat(SessionInputSelection.resolve(guitar, listedBy("Java Sound", devices)))
                .as("the identity resolves to the capture mixer, not to nothing").hasValue(capture);
        AudioDeviceInfo secondCapture = new AudioDeviceInfo(2, spdif, "Java Sound", 2, 0, 48_000, List.of(), 0, 0);
        assertThat(SessionInputSelection.resolve(guitar, listedBy("Java Sound", List.of(playback, capture, secondCapture))))
                .as("two capture mixers of that name stay unresolved").isEmpty();
    }

    @Test
    void aLegacyIndexHintResolvesOnlyWhileTheTrackHasNoIdentity() {
        Track legacy = new Track("Legacy", TrackType.AUDIO);
        legacy.setLegacyInputDeviceIndexHint(USB.index());

        assertThat(SessionInputSelection.resolve(legacy, ENUMERATION)).hasValue(USB);
        legacy.setInputDevice(identityAt(LINE.index()));
        assertThat(SessionInputSelection.resolve(legacy, ENUMERATION)).hasValue(LINE);
    }

    private static Track armed(String name, int enumeratedIndex) {
        Track track = new Track(name, TrackType.AUDIO);
        track.setArmed(true);
        track.setInputDevice(identityAt(enumeratedIndex));
        return track;
    }

    /**
     * The stable identity of the {@link #DEVICES} entry enumerated at {@code index};
     * an index no entry has stands for a device that has since been unplugged (its
     * identity names nothing in the list); {@link Track#NO_INPUT_DEVICE} is no choice.
     */
    private static Optional<DeviceId> identityAt(int index) {
        if (index == Track.NO_INPUT_DEVICE) {
            return Optional.empty();
        }
        String name = DEVICES.stream().filter(device -> device.index() == index).findFirst()
                .map(AudioDeviceInfo::qualifiedName).orElse("Unplugged " + index + " [ASIO]");
        return Optional.of(new DeviceId(BACKEND, name));
    }
}
