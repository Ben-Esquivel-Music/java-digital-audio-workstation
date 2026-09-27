package com.benesquivelmusic.daw.app.ui.recording;

import com.benesquivelmusic.daw.app.ui.AudioEngineController;
import com.benesquivelmusic.daw.app.ui.SettingsModel;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.BufferSizeRange;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 322 contract (probe-derived, permanent; fix-round 2 probe "a") — the
 * exact session-input gate (R2-1) under non-default inputs: one legacy bare
 * name enumerated under THREE host APIs at non-monotonic indices, the third
 * one picked. Pins what story 326 (multi-device capture) inherits: the first
 * confirm upgrades the persisted name to the qualified form with exactly one
 * apply; only the very same device is then a no-op; a sibling host API is a
 * real selection again; and after the upgrade the arm-time mismatch check is
 * exact, so an armed track holding a sibling-host-API index is reported.
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
    void aBareNameSharedByThreeHostApisUpgradesToTheThirdPickAppliesOnceAndIsThenANoOp() throws Exception {
        SettingsModel settings = newSettings();
        settings.setAudioBackend("PortAudio");
        settings.setAudioInputDevice("Scarlett 18i20");
        Controller controller = new Controller();
        List<String> notifications = new CopyOnWriteArrayList<>();
        SettingsBackedSessionInputSelection selection = new SettingsBackedSessionInputSelection(
                settings, controller, (_, message, _, _) -> notifications.add(message), () -> { });

        assertThat(selection.isSessionDevice(MME)).as("bare tolerance: MME").isTrue();
        assertThat(selection.isSessionDevice(DIRECT_SOUND)).as("bare tolerance: DirectSound").isTrue();
        assertThat(selection.isSessionDevice(WASAPI)).as("bare tolerance: WASAPI").isTrue();
        assertThat(selection.isSessionDevice(REALTEK)).isFalse();
        assertThat(selection.selectedIndexIn(DEVICES)).isIn(MME.index(), DIRECT_SOUND.index(), WASAPI.index());

        selection.selectAndApply(WASAPI).orElseThrow().join();

        assertThat(settings.getAudioInputDevice()).isEqualTo("Scarlett 18i20 [WASAPI]");
        assertThat(controller.applies.get()).as("exactly one apply").isEqualTo(1);
        assertThat(controller.lastRequest.get().inputDeviceName()).isEqualTo("Scarlett 18i20 [WASAPI]");
        assertThat(controller.lastRequest.get().backendName()).isEqualTo("PortAudio");
        assertThat(selection.isSessionDevice(WASAPI)).isTrue();
        assertThat(selection.isSessionDevice(MME)).as("exact from now on").isFalse();
        assertThat(selection.isSessionDevice(DIRECT_SOUND)).isFalse();
        assertThat(selection.selectedIndexIn(DEVICES)).isEqualTo(WASAPI.index());

        assertThat(selection.selectAndApply(WASAPI)).as("second pick of the same device").isEmpty();
        assertThat(controller.applies.get()).isEqualTo(1);
        assertThat(settings.getAudioInputDevice()).isEqualTo("Scarlett 18i20 [WASAPI]");
        assertThat(notifications).isEmpty();

        selection.selectAndApply(DIRECT_SOUND).orElseThrow().join();
        assertThat(settings.getAudioInputDevice()).isEqualTo("Scarlett 18i20 [Windows DirectSound]");
        assertThat(controller.applies.get()).isEqualTo(2);
        assertThat(controller.lastRequest.get().inputDeviceName()).isEqualTo("Scarlett 18i20 [Windows DirectSound]");
        assertThat(selection.selectedIndexIn(DEVICES)).isEqualTo(DIRECT_SOUND.index());
        assertThat(notifications).isEmpty();
    }

    @Test
    void afterTheUpgradeTheMismatchCheckIsExactForArmedTracksHoldingASiblingHostApiIndex() throws Exception {
        SettingsModel settings = newSettings();
        settings.setAudioInputDevice("Scarlett 18i20");
        Controller controller = new Controller();
        SettingsBackedSessionInputSelection selection = new SettingsBackedSessionInputSelection(
                settings, controller, (_, _, _, _) -> { }, () -> { });
        Track viaMme = armed("Vox", MME.index());
        Track viaWasapi = armed("Gtr", WASAPI.index());
        assertThat(selection.mismatches(List.of(viaMme, viaWasapi), DEVICES)).as("bare tolerance before").isEmpty();

        selection.selectAndApply(WASAPI).orElseThrow().join();

        assertThat(selection.mismatches(List.of(viaMme, viaWasapi), DEVICES))
                .as("the sibling-host-API index now disagrees with the exact session name")
                .containsExactly(viaMme);
    }

    private static Track armed(String name, int inputDeviceIndex) {
        Track track = new Track(name, TrackType.AUDIO);
        track.setArmed(true);
        track.setInputDeviceIndex(inputDeviceIndex);
        return track;
    }

    private static final class Controller implements AudioEngineController {
        final AtomicReference<Request> lastRequest = new AtomicReference<>();
        final AtomicInteger applies = new AtomicInteger();

        @Override public String getActiveBackendName() { return BACKEND_NONE; }
        @Override public String getProvisionedBackendName() { return "Provisioned"; }
        @Override public List<String> getAvailableBackendNames() { return List.of("PortAudio"); }
        @Override public List<AudioDeviceInfo> listDevices() { return DEVICES; }
        @Override public List<AudioDeviceInfo> listDevices(String backendName) { return DEVICES; }

        @Override
        public BufferSizeRange bufferSizeRange(String backendName, String outputDeviceName) {
            return BufferSizeRange.singleton(256);
        }

        @Override
        public Set<Integer> supportedSampleRates(String backendName, String outputDeviceName) {
            return Set.of(48_000);
        }

        @Override public double getCpuLoadPercent() { return 0; }

        @Override
        public void applyConfiguration(Request request) {
            lastRequest.set(request);
            applies.incrementAndGet();
        }

        @Override public void playTestTone(String outputDeviceName) { }
    }
}
