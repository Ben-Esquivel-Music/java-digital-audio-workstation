package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.recording.RecordState;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.recording.TakeManifest;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioBackendException;
import com.benesquivelmusic.daw.sdk.audio.BackendThreadCounters;
import com.benesquivelmusic.daw.sdk.audio.FormatChangeReason;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.await;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.awaitOnFx;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rescued take's lead follows how the device was lost, at every site that
 * words it (story 338, rulings E5, E111 and E113), over the production
 * detection wiring on the default provision, whose device has no name to
 * show: a stall during a take whose MIDI stop fails leads with the stall, and
 * a driver reset whose reopen fails seals the take as a device loss and leads
 * with the reset; a reset whose reopen finds the driver's control panel open
 * is no loss: the take goes on across the wait (ruling E278).
 */
@ExtendWith(JavaFxToolkitExtension.class)
class DeviceLossLeadsDuringATakeTest {

    private static final long TICK_NANOS = TimeUnit.MILLISECONDS.toNanos(
            DefaultAudioEngineController.ENGINE_HEALTH_TICK_MILLIS);

    @TempDir
    Path projectDirectory;

    private DeviceLossRecordingFixture fixture;

    @BeforeEach
    void wireTheProductionDetectionPath() throws Exception {
        fixture = DeviceLossRecordingFixture.withEngineBridge(projectDirectory);
    }

    @AfterEach
    void stopEverything() throws Exception {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void aStallDuringATakeWhoseMidiStopFailsLeadsWithTheStall() throws Exception {
        RecordingInFlightFixture.StubMidiInput failingToClose = new RecordingInFlightFixture.StubMidiInput() {
            @Override
            public void close() {
                super.close();
                throw new IllegalStateException("the MIDI provider failed to close");
            }
        };
        runOnFx(() -> {
            Track keys = new Track("Keys", TrackType.MIDI);
            keys.setArmed(true);
            keys.setMidiInputDeviceName(RecordingInFlightFixture.STUB_MIDI_INPUT);
            fixture.project().addTrack(keys);
            fixture.controller().setMidiInputDeviceResolverForTest(
                    name -> RecordingInFlightFixture.STUB_MIDI_INPUT.equals(name) ? failingToClose : null);
        });
        DefaultAudioEngineController controller = fixture.engineController().orElseThrow();
        AtomicLong clock = new AtomicLong();
        controller.setEngineHealthClock(clock::get);
        fixture.recordUntilABlockIsOnDisk();
        int before = fixture.shownCount();

        fixture.backend().setThreadCounters(devicePeriods(1));
        controller.tickEngineHealthMonitor();
        fixture.backend().setThreadCounters(devicePeriods(2));
        for (int i = 0; i < 40 && controller.engineState() != EngineState.DEVICE_LOST; i++) {
            clock.addAndGet(TICK_NANOS);
            controller.tickEngineHealthMonitor();
        }
        assertThat(controller.engineState()).isEqualTo(EngineState.DEVICE_LOST);
        await(() -> fixture.entriesSince(before).stream().anyMatch(entry -> entry.message().contains(
                "MIDI recording stopped with errors")
                && entry.message().contains("during recording")), "the take's report with the MIDI failure");

        assertThat(fixture.entriesSince(before)).extracting(NotificationEntry::message)
                .filteredOn(message -> message.contains("during recording"))
                .as("ruling E5 at the MIDI-stop-failure site; ruling E111 without a name")
                .isNotEmpty()
                .allMatch(message -> message.startsWith("The audio device stopped responding during recording"));
    }

    @Test
    void aResetWhoseReopenFailsDuringATakeSealsItAsADeviceLossAndLeadsWithTheReset() throws Exception {
        DefaultAudioEngineController controller = fixture.engineController().orElseThrow();
        Path takeDirectory = fixture.recordUntilABlockIsOnDisk();
        int before = fixture.shownCount();
        fixture.backend().failOpensWith(new AudioBackendException("the device is gone"));

        fixture.backend().simulateFormatChangeRequested(fixture.activeDevice(), Optional.empty(),
                new FormatChangeReason.DriverReset());
        // The loss's notice follows the DEVICE_LOST it ends with, on the controller's thread: awaited itself.
        String resetNotice = "The audio device asked to be reset and could not be reopened — audio stopped. Check"
                + " that it is connected and powered, then choose Reconnect.";
        await(() -> fixture.engineNotices().contains(resetNotice), "the failed reopen's notice");
        assertThat(controller.engineState()).as("the failed reopen ended as a loss").isEqualTo(EngineState.DEVICE_LOST);
        fixture.awaitRescued();
        fixture.awaitPublished();

        TakeManifest manifest = TakeManifest.read(TakeManifest.manifestPath(takeDirectory));
        assertThat(manifest.sealedBy()).contains(TakeManifest.SealedBy.DEVICE_LOSS);
        assertThat(onFx(() -> fixture.coordinator().lostCause()))
                .isEqualTo(EngineStateChange.LossCause.REOPEN_AFTER_RESET_FAILED);
        List<NotificationEntry> entries = fixture.entriesSince(before);
        assertThat(entries).filteredOn(entry -> entry.actionLabel().equals(Optional.of("Review")))
                .extracting(NotificationEntry::message)
                .as("ruling E113's lead; ruling E111 without a name")
                .singleElement().asString()
                .startsWith("The audio device could not be reopened after a driver reset during recording. The take"
                        + " was saved up to the dropout: ");
        assertThat(fixture.engineNotices()).last().isEqualTo("The audio device asked to be reset and could not be"
                + " reopened — audio stopped. Check that it is connected and powered, then choose Reconnect.");
        assertThat(fixture.engineNoticeActions()).last().isEqualTo(Optional.of("Reconnect"));
        assertThat(fixture.engineNotices()).noneMatch(message -> message.contains("Audio engine reconfigured"));
        assertThat(onFx(() -> fixture.coordinator().getState())).as("never a take in record state over a closed"
                + " stream").isEqualTo(RecordState.DEVICE_LOST);
        fixture.backend().failOpensWith(null);
    }

    @Test
    void aTakeRecordingAtTheRequestGoesOnAcrossTheWait() throws Exception {
        CountDownLatch shown = new CountDownLatch(1);
        CountDownLatch close = new CountDownLatch(1);
        AudioBackend heldPanel = withAControlPanelThatStaysOpen(fixture.backend(), shown, close);
        runOnFx(() -> fixture.engine().setStreamingProvision(new StreamingProvision(fixture.backend().name(),
                List.of(new BackendStreamRung(heldPanel, fixture.activeDevice())))));
        DefaultAudioEngineController controller = fixture.engineController().orElseThrow();
        List<EngineStateChange> published = EngineHealthMonitorTest.subscribe(controller);
        Path takeDirectory = fixture.recordUntilABlockIsOnDisk();
        int before = fixture.shownCount();
        long halts = fixture.engine().getCaptureHaltGeneration();
        FutureTask<Void> panel = new FutureTask<>(controller.openControlPanel().orElseThrow(), null);
        Thread.ofPlatform().name("test-driver-control-panel").start(panel);
        try {
            await(() -> shown.getCount() == 0, "the driver's control panel is open");
            fixture.backend().simulateFormatChangeRequested(fixture.activeDevice(), Optional.empty(),
                    new FormatChangeReason.DriverReset());
            await(() -> controller.formatChangeAwaitingTheControlPanel().isPresent(),
                    "the reopen waits for the panel");
            awaitOnFx(() -> fixture.engineHealth().engineStateProperty().get().state() == EngineState.RECONFIGURING,
                    "the Engine cell's model reads reconfiguring");
            assertThat(onFx(() -> fixture.coordinator().getState())).isEqualTo(RecordState.RECORDING);
            assertThat(fixture.engine().getCaptureHaltGeneration()).as("no capture halt").isEqualTo(halts);
            assertThat(fixture.engineNotices()).endsWith("Reconfiguring audio engine…")
                    .noneMatch(message -> message.contains("could not be reopened"));
        } finally {
            close.countDown();
        }
        panel.get(10, TimeUnit.SECONDS);
        await(() -> controller.engineState() == EngineState.RUNNING, "the reopen after the panel's close");
        assertThat(fixture.engineNotices()).endsWith("Reconfiguring audio engine…", "Audio engine reconfigured");
        assertThat(onFx(() -> fixture.coordinator().getState())).isEqualTo(RecordState.RECORDING);

        runOnFx(fixture.controller()::toggleRecord);
        fixture.awaitPublished();

        assertThat(TakeManifest.read(TakeManifest.manifestPath(takeDirectory)).sealedBy())
                .contains(TakeManifest.SealedBy.STOP);
        synchronized (published) {
            assertThat(published).extracting(EngineStateChange::state).doesNotContain(EngineState.DEVICE_LOST);
        }
        assertThat(fixture.entriesSince(before)).extracting(NotificationEntry::message)
                .noneMatch(message -> message.contains("could not be reopened")
                        || message.contains("saved up to the dropout"));
    }

    /**
     * {@code device}, whose control panel, once shown, stays open until
     * {@code close} is counted down: a {@link Proxy}, as
     * {@link CaptureBlockBudget} makes one, that forwards every other call.
     */
    private static AudioBackend withAControlPanelThatStaysOpen(AudioBackend device, CountDownLatch shown,
                                                               CountDownLatch close) {
        Runnable panel = () -> {
            shown.countDown();
            boolean interrupted = false;
            boolean closed = false;
            while (!closed) {
                try {
                    close.await();
                    closed = true;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        InvocationHandler calls = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> "ControlPanelThatStaysOpen[" + device.name() + "]";
                };
            }
            if (method.getName().equals("openControlPanel")) {
                return Optional.of(panel);
            }
            try {
                return method.invoke(device, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        };
        return (AudioBackend) Proxy.newProxyInstance(AudioBackend.class.getClassLoader(),
                new Class<?>[] {AudioBackend.class}, calls);
    }

    private static BackendThreadCounters devicePeriods(long periods) {
        return new BackendThreadCounters(0, 0, 0, 0, OptionalLong.of(periods), Optional.empty(),
                Optional.empty(), Optional.empty());
    }
}
