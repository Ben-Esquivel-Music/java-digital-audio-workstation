package com.benesquivelmusic.daw.app.ui.recording;

import com.benesquivelmusic.daw.app.ui.AudioEngineController;
import com.benesquivelmusic.daw.app.ui.NotificationLevel;
import com.benesquivelmusic.daw.app.ui.SettingsModel;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.BufferSizeRange;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 322 — the ONE session-level input selection (Audio Engine Wiring
 * Design Book §5.6 "Per-track input device"): {@code select} persists the
 * device and applies it to the engine with the chosen device name;
 * {@code mismatches} lists only the ARMED tracks whose explicit per-track
 * choice disagrees with the session device; the warning names the tracks
 * and both devices.
 */
class SessionInputSelectionTest {

    private static final AudioDeviceInfo MIC = AudioDeviceInfo.unprobed(0, "Mic In", "ASIO");
    private static final AudioDeviceInfo USB = AudioDeviceInfo.unprobed(1, "USB In", "WASAPI");
    private static final AudioDeviceInfo LINE = AudioDeviceInfo.unprobed(2, "Line In", "WASAPI");
    private static final List<AudioDeviceInfo> DEVICES = List.of(MIC, USB, LINE);
    /** The same endpoint as {@link #USB} enumerated under another host API (a new index). */
    private static final AudioDeviceInfo USB_MME = AudioDeviceInfo.unprobed(3, "USB In", "MME");
    private static final List<AudioDeviceInfo> SAME_NAME_UNDER_TWO_HOST_APIS = List.of(MIC, USB, LINE, USB_MME);

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

    @Test
    void selectPersistsTheDeviceAndAppliesTheConfigurationWithItsName() throws Exception {
        SettingsModel settings = newSettings();
        settings.setAudioBackend("ASIO");
        settings.setAudioOutputDevice("Main Out [ASIO]");
        RecordingController controller = new RecordingController();
        List<String> notifications = new CopyOnWriteArrayList<>();
        SettingsBackedSessionInputSelection selection = new SettingsBackedSessionInputSelection(
                settings, controller, (_, message, _, _) -> notifications.add(message), () -> { });

        selection.selectAndApply(USB).orElseThrow().join();

        assertThat(settings.getAudioInputDevice()).isEqualTo("USB In [WASAPI]");
        assertThat(selection.currentDeviceName()).isEqualTo("USB In [WASAPI]");
        assertThat(selection.isSessionDevice(USB)).isTrue();
        assertThat(selection.selectedIndexIn(DEVICES)).isEqualTo(USB.index());
        AudioEngineController.Request request = controller.lastRequest.get();
        assertThat(request).isNotNull();
        assertThat(request.inputDeviceName()).isEqualTo("USB In [WASAPI]");
        assertThat(request.backendName()).isEqualTo("ASIO");
        assertThat(request.outputDeviceName()).isEqualTo("Main Out [ASIO]");
        assertThat(request.bufferFrames()).isEqualTo(settings.getBufferSize());
        assertThat(controller.applyThread.get()).isNotSameAs(Thread.currentThread());
        assertThat(notifications).isEmpty();
    }

    @Test
    void selectUsesTheProvisionedBackendWhenNoneIsPersistedAndReportsAnApplyFailure() throws Exception {
        SettingsModel settings = newSettings();
        settings.setAudioBackend("");
        RecordingController controller = new RecordingController();
        controller.failure = new IllegalStateException("device is busy");
        List<String> notifications = new CopyOnWriteArrayList<>();
        AtomicReference<NotificationLevel> level = new AtomicReference<>();
        SettingsBackedSessionInputSelection selection = new SettingsBackedSessionInputSelection(
                settings, controller, (l, message, _, _) -> {
                    level.set(l);
                    notifications.add(message);
                }, () -> { });

        selection.selectAndApply(MIC).orElseThrow().join();

        assertThat(controller.lastRequest.get().backendName()).isEqualTo(RecordingController.PROVISIONED);
        assertThat(settings.getAudioInputDevice()).as("the choice is persisted even when the apply fails")
                .isEqualTo("Mic In [ASIO]");
        assertThat(level.get()).isEqualTo(NotificationLevel.ERROR);
        assertThat(notifications).singleElement().asString()
                .contains("Mic In [ASIO]").contains("device is busy");
    }

    @Test
    void aLegacyBareSessionNameUpgradesItselfOnTheFirstConfirmAndOnlyTheSameDeviceIsThenANoOp()
            throws Exception {
        // Story 322 fix round 2 (R2-1): the no-op gate is the EXACT qualified
        // name, not isSessionDevice's bare-name tolerance. A legacy bare
        // "USB In" is therefore persisted as "USB In [WASAPI]" and applied
        // ONCE on the first confirm (what every confirm did before the
        // round-1 gate), and only the second confirm of the very same device
        // is the no-op that must not stop the pump and reopen the stream.
        SettingsModel settings = newSettings();
        settings.setAudioInputDevice("USB In");   // a legacy bare name that still names USB
        RecordingController controller = new RecordingController();
        List<String> notifications = new CopyOnWriteArrayList<>();
        SettingsBackedSessionInputSelection selection = new SettingsBackedSessionInputSelection(
                settings, controller, (_, message, _, _) -> notifications.add(message), () -> { });
        assertThat(selection.isSessionDevice(USB)).as("the bare tolerance still preselects USB").isTrue();

        selection.selectAndApply(USB).orElseThrow().join();

        assertThat(settings.getAudioInputDevice()).as("the bare name upgraded itself").isEqualTo("USB In [WASAPI]");
        assertThat(controller.applies.get()).as("exactly one apply").isEqualTo(1);
        assertThat(controller.lastRequest.get().inputDeviceName()).isEqualTo("USB In [WASAPI]");

        assertThat(selection.selectAndApply(USB)).as("no worker for the very same device").isEmpty();

        assertThat(settings.getAudioInputDevice()).as("settings unchanged").isEqualTo("USB In [WASAPI]");
        assertThat(controller.applies.get()).as("no second apply").isEqualTo(1);
        assertThat(notifications).isEmpty();
    }

    @Test
    void aSameNamedDeviceUnderAnotherHostApiIsPersistedAndAppliedOverALegacyBareSessionName()
            throws Exception {
        // Story 322 fix round 2 (R2-1): PortAudio enumerates "USB In" under
        // MME and under WASAPI, and a persisted bare "USB In" names BOTH by
        // isSessionDevice's tolerance. Picking the [MME] endpoint must persist
        // "USB In [MME]" and apply it — under the round-1 tolerant gate it did
        // neither while the caller still wrote the per-track index, the
        // status bar and the dirty flag: the silent ignore §5.6 forbids.
        SettingsModel settings = newSettings();
        settings.setAudioInputDevice("USB In");
        RecordingController controller = new RecordingController();
        SettingsBackedSessionInputSelection selection = new SettingsBackedSessionInputSelection(
                settings, controller, (_, _, _, _) -> { }, () -> { });
        assertThat(selection.isSessionDevice(USB)).isTrue();
        assertThat(selection.isSessionDevice(USB_MME)).as("the bare name names both endpoints").isTrue();

        selection.selectAndApply(USB_MME).orElseThrow().join();

        assertThat(settings.getAudioInputDevice()).isEqualTo("USB In [MME]");
        assertThat(controller.applies.get()).as("one apply, targeting the pick").isEqualTo(1);
        assertThat(controller.lastRequest.get().inputDeviceName()).isEqualTo("USB In [MME]");
        assertThat(selection.isSessionDevice(USB_MME)).isTrue();
        assertThat(selection.isSessionDevice(USB)).as("the session name is exact from now on").isFalse();
        assertThat(selection.selectedIndexIn(SAME_NAME_UNDER_TWO_HOST_APIS)).isEqualTo(USB_MME.index());
    }

    @Test
    void selectingTheSessionInputByItsQualifiedNameIsANoOpWhileADifferentDeviceStillApplies()
            throws Exception {
        SettingsModel settings = newSettings();
        settings.setAudioInputDevice(USB.qualifiedName());
        RecordingController controller = new RecordingController();
        SettingsBackedSessionInputSelection selection = new SettingsBackedSessionInputSelection(
                settings, controller, (_, _, _, _) -> { }, () -> { });

        assertThat(selection.selectAndApply(USB)).isEmpty();
        assertThat(controller.lastRequest.get()).isNull();

        selection.selectAndApply(MIC).orElseThrow().join();

        assertThat(controller.lastRequest.get().inputDeviceName()).isEqualTo("Mic In [ASIO]");
        assertThat(settings.getAudioInputDevice()).isEqualTo("Mic In [ASIO]");
    }

    @Test
    void mismatchesListsOnlyArmedTracksWhoseChoiceDisagreesWithTheSessionDevice() {
        SettingsModel settings = newSettings();
        settings.setAudioInputDevice(MIC.qualifiedName());
        SessionInputSelection selection = new SettingsBackedSessionInputSelection(
                settings, new RecordingController(), (_, _, _, _) -> { }, () -> { });

        Track agrees = armed("Agrees", MIC.index());
        Track conflicts = armed("Conflicts", USB.index());
        Track unarmedConflict = new Track("Unarmed", TrackType.AUDIO);
        unarmedConflict.setInputDeviceIndex(USB.index());
        Track noChoice = armed("No choice", Track.NO_INPUT_DEVICE);
        Track dangling = armed("Dangling", 42);

        assertThat(selection.mismatches(
                List.of(agrees, conflicts, unarmedConflict, noChoice, dangling), DEVICES))
                .containsExactly(conflicts);
        assertThat(selection.mismatchWarning(List.of(agrees, noChoice), DEVICES)).isEmpty();
    }

    @Test
    void mismatchWarningNamesTheTracksAndBothDevices() {
        SettingsModel settings = newSettings();
        settings.setAudioInputDevice(MIC.qualifiedName());
        SessionInputSelection selection = new SettingsBackedSessionInputSelection(
                settings, new RecordingController(), (_, _, _, _) -> { }, () -> { });

        Optional<String> warning = selection.mismatchWarning(
                List.of(armed("Vox", USB.index()), armed("Guitar", USB.index()), armed("Keys", LINE.index())),
                DEVICES);

        assertThat(warning).isPresent();
        assertThat(warning.get()).isEqualTo(
                "Recording uses the session input 'Mic In [ASIO]'; "
                        + "track(s) Vox, Guitar chose 'USB In [WASAPI]'; "
                        + "track(s) Keys chose 'Line In [WASAPI]' "
                        + "— multi-device capture is story 326");
    }

    @Test
    void blankSessionDeviceIsTheDefaultAndEveryExplicitChoiceDisagreesWithIt() {
        SettingsModel settings = newSettings();
        settings.setAudioInputDevice("");
        SessionInputSelection selection = new SettingsBackedSessionInputSelection(
                settings, new RecordingController(), (_, _, _, _) -> { }, () -> { });

        assertThat(selection.selectedIndexIn(DEVICES)).isEqualTo(Track.NO_INPUT_DEVICE);
        assertThat(selection.mismatchWarning(List.of(armed("Vox", MIC.index())), DEVICES))
                .hasValueSatisfying(text -> assertThat(text)
                        .startsWith("Recording uses the session input '<default>'; track(s) Vox chose 'Mic In [ASIO]'"));
    }

    @Test
    void legacyBareNameStillNamesTheSessionDevice() {
        SettingsModel settings = newSettings();
        settings.setAudioInputDevice("Mic In");   // saved before qualified names existed
        SessionInputSelection selection = new SettingsBackedSessionInputSelection(
                settings, new RecordingController(), (_, _, _, _) -> { }, () -> { });

        assertThat(selection.isSessionDevice(MIC)).isTrue();
        assertThat(selection.selectedIndexIn(DEVICES)).isEqualTo(MIC.index());
        assertThat(selection.mismatches(List.of(armed("Vox", MIC.index())), DEVICES)).isEmpty();
    }

    private static Track armed(String name, int inputDeviceIndex) {
        Track track = new Track(name, TrackType.AUDIO);
        track.setArmed(true);
        track.setInputDeviceIndex(inputDeviceIndex);
        return track;
    }

    /** Records the last {@link Request}; optionally fails the apply. */
    private static final class RecordingController implements AudioEngineController {
        static final String PROVISIONED = "Provisioned Backend";
        final AtomicReference<Request> lastRequest = new AtomicReference<>();
        final AtomicReference<Thread> applyThread = new AtomicReference<>();
        final AtomicInteger applies = new AtomicInteger();
        volatile RuntimeException failure;

        @Override public String getActiveBackendName() { return BACKEND_NONE; }
        @Override public String getProvisionedBackendName() { return PROVISIONED; }
        @Override public List<String> getAvailableBackendNames() { return List.of(PROVISIONED); }
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
            applyThread.set(Thread.currentThread());
            lastRequest.set(request);
            applies.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
        }

        @Override public void playTestTone(String outputDeviceName) { }
    }
}
