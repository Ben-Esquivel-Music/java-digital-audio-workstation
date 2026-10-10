package com.benesquivelmusic.daw.app.ui.recording;

import com.benesquivelmusic.daw.app.ui.SettingsModel;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 322 contract (probe-derived, permanent; fix-round 2 probe "a") — the
 * session-input comparison under non-default inputs: one legacy bare name
 * enumerated under THREE host APIs at non-monotonic indices. Pins what story
 * 326 (multi-device capture) inherits: a persisted legacy bare name matches
 * every same-named endpoint; once the Audio Settings session selector has
 * persisted a qualified name the comparison is exact — only that endpoint is
 * preselected by the input dialogs.
 */
class Story322SessionInputUpgradeContractTest {

    private static final AudioDeviceInfo REALTEK = AudioDeviceInfo.unprobed(0, "Realtek Mic", "MME");
    private static final AudioDeviceInfo MME = AudioDeviceInfo.unprobed(7, "Scarlett 18i20", "MME");
    private static final AudioDeviceInfo DIRECT_SOUND =
            AudioDeviceInfo.unprobed(2, "Scarlett 18i20", "Windows DirectSound");
    private static final AudioDeviceInfo WASAPI = AudioDeviceInfo.unprobed(11, "Scarlett 18i20", "WASAPI");
    private static final List<AudioDeviceInfo> DEVICES = List.of(REALTEK, MME, DIRECT_SOUND, WASAPI);

    private Preferences prefs;

    @AfterEach
    void dropPreferences() throws BackingStoreException {
        if (prefs != null) {
            prefs.removeNode();
        }
    }

    private SettingsModel newSettings() {
        prefs = Preferences.userRoot().node("story322FixProbe2_" + System.nanoTime());
        return new SettingsModel(prefs);
    }

    @Test
    void aBareNameSharedByThreeHostApisMatchesAllUntilAQualifiedNameIsPersisted() {
        SettingsModel settings = newSettings();
        settings.setAudioBackend("PortAudio");
        settings.setAudioInputDevice("Scarlett 18i20");
        SessionInputSelection selection = new SettingsBackedSessionInputSelection(settings);

        assertThat(selection.isSessionDevice(MME)).as("bare tolerance: MME").isTrue();
        assertThat(selection.isSessionDevice(DIRECT_SOUND)).as("bare tolerance: DirectSound").isTrue();
        assertThat(selection.isSessionDevice(WASAPI)).as("bare tolerance: WASAPI").isTrue();
        assertThat(selection.isSessionDevice(REALTEK)).isFalse();
        assertThat(selection.selectedIndexIn(DEVICES)).isIn(MME.index(), DIRECT_SOUND.index(), WASAPI.index());

        settings.setAudioInputDevice(WASAPI.qualifiedName());   // the session selector commits the third endpoint

        assertThat(selection.isSessionDevice(WASAPI)).isTrue();
        assertThat(selection.isSessionDevice(MME)).as("exact from now on").isFalse();
        assertThat(selection.isSessionDevice(DIRECT_SOUND)).isFalse();
        assertThat(selection.selectedIndexIn(DEVICES)).isEqualTo(WASAPI.index());

        settings.setAudioInputDevice(DIRECT_SOUND.qualifiedName());
        assertThat(selection.selectedIndexIn(DEVICES)).isEqualTo(DIRECT_SOUND.index());
    }
}
