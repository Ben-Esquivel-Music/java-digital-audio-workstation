package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceEvent;
import com.benesquivelmusic.daw.sdk.audio.BackendThreadCounters;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.FormatChangeReason;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;
import com.benesquivelmusic.daw.sdk.audio.SampleRate;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The engine controller's health monitor over a real {@link AudioEngine}
 * (story 338, rulings E4 and E6): a stream whose watched heartbeat stops
 * is declared lost through the same steps as the host's removal, with the
 * stall's notice and its Reconnect action, sent to a real injected
 * {@link NotificationManager}; nothing is declared while the drive is not
 * alive, while a driver panel is open or within the start-up allowance
 * after it closes (ruling E228), or while a configuration change is in
 * progress, nor on real threads streaming healthily across Stop, pause,
 * resume and a Settings apply. Ticks run on the test thread through the
 * controller's tick seam with an injected clock, except where the monitor's
 * own thread is the subject.
 */
@Tag("slow")
class EngineHealthMonitorTest {

    private static final long TICK_NANOS = TimeUnit.MILLISECONDS.toNanos(DefaultAudioEngineController.ENGINE_HEALTH_TICK_MILLIS);
    private static final String STALL_NOTICE = "Interface A stopped responding — audio stopped. Check that it is"
            + " connected and powered, then choose Reconnect.";

    // Held strongly: the log manager keeps a logger only weakly, so a handler added to one nobody holds may be lost.
    private final Logger controllerLog = Logger.getLogger(DefaultAudioEngineController.class.getName());

    /** What the loss's two observation points saw: the stream's close, then its notice. */
    private record AtTheClose(AudioEngine.RecordingCallback installed, int halts, boolean haltedOnThisThread,
                              EngineState state, boolean streamOpen, boolean underTheControllersMonitor) {
    }

    private record AtTheNotice(EngineState state, boolean streamOpen, boolean engineRunning) {
    }

    @Test
    void aRenderDriveWhoseBeatsStopIsDeclaredLostThroughTheRemovalsStepsWithTheStallsNotice() throws Exception {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        RecordingNotifications notices = new RecordingNotifications();
        DefaultAudioEngineController controller = new DefaultAudioEngineController(engine, null, notices);
        AtomicLong clock = new AtomicLong();
        controller.setEngineHealthClock(clock::get);
        SpyStreamingBackend spy = new SpyStreamingBackend("SpyStall");
        DeviceId active = new DeviceId("SpyStall", "Interface A");
        engine.setStreamingProvision(new StreamingProvision("SpyStall", List.of(new BackendStreamRung(spy, active))));
        AtomicInteger halts = new AtomicInteger();
        AtomicReference<Thread> haltedOn = new AtomicReference<>();
        engine.setRecordingCallback(new AudioEngine.RecordingCallback() {
            @Override public void onAudioCaptured(float[][] inputBuffer, int numFrames) { /* the pump's */ }
            @Override public void onCaptureHalted() {
                halts.incrementAndGet();
                haltedOn.set(Thread.currentThread());
            }
        });
        AtomicReference<AtTheClose> atTheClose = new AtomicReference<>();
        AtomicLong epochAtTheClose = new AtomicLong(-2);
        spy.armNextInputCancelHook(() -> {
            atTheClose.compareAndSet(null, new AtTheClose(engine.getRecordingCallback(), halts.get(),
                    haltedOn.get() == Thread.currentThread(), controller.engineState(), engine.isStreamOpen(),
                    Thread.holdsLock(controller)));
            epochAtTheClose.set(engine.audioHealth().renderDriveEpoch());
        });
        notices.context(() -> new AtTheNotice(controller.engineState(), engine.isStreamOpen(), engine.isRunning()));
        List<EngineStateChange> published = subscribe(controller);
        try {
            engine.startAudioOutput();
            controller.tickEngineHealthMonitor();
            assertThat(spy.awaitSunkBlocks(8, 5, TimeUnit.SECONDS)).as("premise: the drive beats").isTrue();
            tick(controller, clock);
            spy.blockAwait = true;
            await(() -> spy.blockedAwaitEntries.get() >= 1, "the render pump is wedged in awaitSinkCapacity");

            tickUntil(controller, clock, () -> controller.engineState() == EngineState.DEVICE_LOST, 30);

            assertThat(controller.engineState()).isEqualTo(EngineState.DEVICE_LOST);
            assertThat(atTheClose.get())
                    .as("capture was halted on the monitor's thread, under the controller's monitor, before the"
                            + " stream's close, and the loss not yet declared")
                    .isEqualTo(new AtTheClose(null, 1, true, EngineState.STOPPED, true, true));
            assertThat(notices.notices())
                    .extracting(RecordingNotifications.Notice::message, RecordingNotifications.Notice::actionLabel,
                            RecordingNotifications.Notice::context)
                    .as("one notice, the stall's, with Reconnect, sent after the stream and the engine stopped and"
                            + " DEVICE_LOST was published")
                    .containsExactly(tuple(STALL_NOTICE, Optional.of("Reconnect"),
                            new AtTheNotice(EngineState.DEVICE_LOST, false, false)));
            assertThat(engine.isStreamOpen()).isFalse();
            assertThat(engine.isRenderDriveAlive()).isFalse();
            assertThat(controller.lostDriveEpoch()).as("the lost drive's epoch, read before its close")
                    .isEqualTo(epochAtTheClose.get());
            await(() -> !published.isEmpty(), "the loss is published");
            assertThat(published).containsExactly(
                    new EngineStateChange(EngineState.DEVICE_LOST, Optional.of(active),
                            EngineStateChange.LossCause.STALLED));

            for (int i = 0; i < 20; i++) {
                tick(controller, clock);
            }
            assertThat(notices.size()).as("a declared loss is not declared again").isEqualTo(1);
            assertThat(controller.engineState()).isEqualTo(EngineState.DEVICE_LOST);
        } finally {
            spy.blockAwait = false;
            engine.stopAudioOutput();
            engine.stop();
            controller.shutdown();
        }
    }

    @Test
    void frozenDevicePeriodsAreAStallWhileTheRenderDriveKeepsCycling() throws Exception {
        AudioEngine engine = new AudioEngine(new AudioFormat(48_000.0, 2, 24, 256));
        RecordingNotifications notices = new RecordingNotifications();
        DefaultAudioEngineController controller = new DefaultAudioEngineController(engine, null, notices);
        AtomicLong clock = new AtomicLong();
        controller.setEngineHealthClock(clock::get);
        MockAudioBackend mock = new MockAudioBackend();
        DeviceId active = new DeviceId(mock.name(), "Interface A");
        engine.setStreamingProvision(new StreamingProvision(mock.name(),
                List.of(new BackendStreamRung(mock, active))));
        try {
            engine.startAudioOutput();
            mock.setThreadCounters(devicePeriods(1));
            controller.tickEngineHealthMonitor();
            mock.setThreadCounters(devicePeriods(2));
            tick(controller, clock);
            long beatsAtTheFreeze = engine.audioHealth().renderDriveBeats();

            for (int i = 0; i < 30 && controller.engineState() != EngineState.DEVICE_LOST; i++) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
                tick(controller, clock);
            }

            assertThat(engine.audioHealth().renderDriveBeats()).as("premise: the render drive kept beating")
                    .isGreaterThan(beatsAtTheFreeze);
            assertThat(controller.engineState()).isEqualTo(EngineState.DEVICE_LOST);
            assertThat(notices.notices())
                    .extracting(RecordingNotifications.Notice::message, RecordingNotifications.Notice::actionLabel)
                    .containsExactly(tuple(STALL_NOTICE, Optional.of("Reconnect")));
            assertThat(engine.isStreamOpen()).isFalse();
        } finally {
            engine.stopAudioOutput();
            engine.stop();
            controller.shutdown();
        }
    }

    @Test
    void theHostsRemovalSaysPlaybackStoppedAndCarriesTheRemovedCause() throws Exception {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        RecordingNotifications notices = new RecordingNotifications();
        DefaultAudioEngineController controller = new DefaultAudioEngineController(engine, null, notices);
        SpyStreamingBackend spy = new SpyStreamingBackend("SpyRemoved");
        DeviceId active = new DeviceId("SpyRemoved", "Interface A");
        engine.setStreamingProvision(new StreamingProvision("SpyRemoved", List.of(new BackendStreamRung(spy, active))));
        List<EngineStateChange> published = subscribe(controller);
        try {
            engine.startAudioOutput();
            long epoch = engine.audioHealth().renderDriveEpoch();

            spy.simulateDeviceRemoved(active);
            await(() -> notices.size() == 1, "the removal's notice");

            assertThat(notices.notices())
                    .extracting(RecordingNotifications.Notice::message, RecordingNotifications.Notice::actionLabel)
                    .containsExactly(tuple("Audio device disconnected — playback stopped", Optional.empty()));
            await(() -> !published.isEmpty(), "the loss is published");
            assertThat(published).containsExactly(new EngineStateChange(EngineState.DEVICE_LOST,
                    Optional.of(active), EngineStateChange.LossCause.REMOVED));
            assertThat(controller.lostDriveEpoch()).isEqualTo(epoch);
        } finally {
            engine.stopAudioOutput();
            engine.stop();
            controller.shutdown();
        }
    }

    @Test
    void anOpenDriverPanelRefusesTheVerdictAndAStallThatOutlastsItIsDeclaredAfterwards() throws Exception {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        RecordingNotifications notices = new RecordingNotifications();
        DefaultAudioEngineController controller = new DefaultAudioEngineController(engine, null, notices);
        AtomicLong clock = new AtomicLong();
        controller.setEngineHealthClock(clock::get);
        SpyStreamingBackend spy = new SpyStreamingBackend("SpyPanel");
        DeviceId active = new DeviceId("SpyPanel", "Interface A");
        engine.setStreamingProvision(new StreamingProvision("SpyPanel", List.of(new BackendStreamRung(spy, active))));
        CountDownLatch panelOpen = new CountDownLatch(1);
        CountDownLatch closePanel = new CountDownLatch(1);
        spy.setControlPanelAction(() -> {
            panelOpen.countDown();
            try {
                closePanel.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        Thread panel = null;
        try {
            engine.startAudioOutput();
            Runnable panelAction = controller.openControlPanel().orElseThrow();
            controller.tickEngineHealthMonitor();
            assertThat(spy.awaitSunkBlocks(8, 5, TimeUnit.SECONDS)).isTrue();
            tick(controller, clock);
            panel = Thread.ofVirtual().name("test-driver-panel").start(panelAction);
            assertThat(panelOpen.await(5, TimeUnit.SECONDS)).as("the driver dialog is up").isTrue();
            spy.blockAwait = true;
            await(() -> spy.blockedAwaitEntries.get() >= 1, "the render pump is wedged");

            for (int i = 0; i < 50; i++) {
                tick(controller, clock);
            }
            assertThat(controller.engineState()).as("never DEVICE_LOST while the user is in the driver dialog")
                    .isNotEqualTo(EngineState.DEVICE_LOST);
            assertThat(notices.size()).isZero();

            closePanel.countDown();
            panel.join(TimeUnit.SECONDS.toMillis(10));
            assertThat(panel.isAlive()).as("the dialog closed").isFalse();
            for (int i = 0; i < 45; i++) {
                tick(controller, clock);
            }
            assertThat(controller.engineState()).as("the start-up allowance from the panel's close (ruling E228)")
                    .isNotEqualTo(EngineState.DEVICE_LOST);
            tickUntil(controller, clock, () -> controller.engineState() == EngineState.DEVICE_LOST, 15);
            assertThat(controller.engineState()).as("the stall is declared once that allowance ran out")
                    .isEqualTo(EngineState.DEVICE_LOST);
        } finally {
            closePanel.countDown();
            if (panel != null) {
                panel.join(TimeUnit.SECONDS.toMillis(10));
            }
            spy.blockAwait = false;
            engine.stopAudioOutput();
            engine.stop();
            controller.shutdown();
        }
    }

    /**
     * Ruling E228: a verdict due in the very tick during which the driver
     * panel closes, after that tick read the allowance requests, is refused:
     * the allowance the close asked for is not taken up yet. The stall is
     * declared only once that allowance ran out.
     */
    @Test
    void aVerdictDueAsTheDriverPanelClosesWaitsForTheAllowanceFromItsClose() throws Exception {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        RecordingNotifications notices = new RecordingNotifications();
        DefaultAudioEngineController controller = new DefaultAudioEngineController(engine, null, notices);
        AtomicLong clock = new AtomicLong();
        controller.setEngineHealthClock(clock::get);
        SpyStreamingBackend spy = new SpyStreamingBackend("SpyPanelClose");
        DeviceId active = new DeviceId("SpyPanelClose", "Interface A");
        engine.setStreamingProvision(new StreamingProvision("SpyPanelClose",
                List.of(new BackendStreamRung(spy, active))));
        CountDownLatch panelOpen = new CountDownLatch(1);
        CountDownLatch closePanel = new CountDownLatch(1);
        spy.setControlPanelAction(() -> {
            panelOpen.countDown();
            try {
                closePanel.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        List<String> refusals = new CopyOnWriteArrayList<>();
        Handler refusalLog = new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                if (logRecord.getMessage() != null && logRecord.getMessage().contains("refused on re-check")) {
                    refusals.add(logRecord.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        refusalLog.setLevel(Level.ALL);
        Level levelBefore = controllerLog.getLevel();
        controllerLog.setLevel(Level.FINE);
        controllerLog.addHandler(refusalLog);
        Thread panel = null;
        try {
            engine.startAudioOutput();
            Runnable panelAction = controller.openControlPanel().orElseThrow();
            controller.tickEngineHealthMonitor();
            assertThat(spy.awaitSunkBlocks(8, 5, TimeUnit.SECONDS)).isTrue();
            tick(controller, clock);
            panel = Thread.ofVirtual().name("test-driver-panel").start(panelAction);
            assertThat(panelOpen.await(5, TimeUnit.SECONDS)).as("the driver dialog is up").isTrue();
            spy.blockAwait = true;
            await(() -> spy.blockedAwaitEntries.get() >= 1, "the render pump is wedged");
            tickUntil(controller, clock, () -> !refusals.isEmpty(), 100);
            for (int i = 0; i < 19; i++) {
                tick(controller, clock);
            }
            int refusalsWhileOpen = refusals.size();

            Thread ticking = Thread.currentThread();
            Thread dialog = panel;
            AtomicBoolean closeAtTheNextRead = new AtomicBoolean(true);
            controller.setEngineHealthClock(() -> {
                if (Thread.currentThread() == ticking && closeAtTheNextRead.getAndSet(false)) {
                    closePanel.countDown();
                    try {
                        dialog.join(TimeUnit.SECONDS.toMillis(10));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                return clock.get();
            });
            tick(controller, clock);
            boolean closedInTheTick = !dialog.isAlive();
            int refusalsAtTheClose = refusals.size();
            EngineState atTheClose = controller.engineState();
            for (int i = 0; i < 45; i++) {
                tick(controller, clock);
            }
            EngineState withinTheAllowance = controller.engineState();
            tickUntil(controller, clock, () -> controller.engineState() == EngineState.DEVICE_LOST, 15);

            assertThat(refusalsWhileOpen).as("premise: one verdict refused while the panel is open").isEqualTo(1);
            assertThat(closedInTheTick).as("premise: the panel closed inside the tick").isTrue();
            assertThat(atTheClose).as("the verdict due in that tick is refused").isNotEqualTo(EngineState.DEVICE_LOST);
            assertThat(refusalsAtTheClose).as("premise: a verdict was due in that tick").isEqualTo(2);
            assertThat(withinTheAllowance).as("the start-up allowance from the close")
                    .isNotEqualTo(EngineState.DEVICE_LOST);
            assertThat(controller.engineState()).as("declared once the allowance ran out")
                    .isEqualTo(EngineState.DEVICE_LOST);
        } finally {
            closePanel.countDown();
            if (panel != null) {
                panel.join(TimeUnit.SECONDS.toMillis(10));
            }
            controllerLog.removeHandler(refusalLog);
            controllerLog.setLevel(levelBefore);
            spy.blockAwait = false;
            engine.stopAudioOutput();
            engine.stop();
            controller.shutdown();
        }
    }

    @Test
    void aConfigurationChangeInProgressRefusesTheVerdictUntilItEnds() throws Exception {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        RecordingNotifications notices = new RecordingNotifications();
        DefaultAudioEngineController controller = new DefaultAudioEngineController(engine, null, notices);
        AtomicLong clock = new AtomicLong();
        controller.setEngineHealthClock(clock::get);
        SpyStreamingBackend spy = new SpyStreamingBackend("SpyConfig");
        DeviceId active = new DeviceId("SpyConfig", "Interface A");
        engine.setStreamingProvision(new StreamingProvision("SpyConfig", List.of(new BackendStreamRung(spy, active))));
        try {
            engine.startAudioOutput();
            controller.tickEngineHealthMonitor();
            assertThat(spy.awaitSunkBlocks(8, 5, TimeUnit.SECONDS)).isTrue();
            tick(controller, clock);
            spy.blockAwait = true;
            await(() -> spy.blockedAwaitEntries.get() >= 1, "the render pump is wedged");

            try (AudioEngineController.ConfigurationLease lease = controller.beginConfigurationChange()) {
                for (int i = 0; i < 50; i++) {
                    tick(controller, clock);
                }
                assertThat(controller.engineState()).as("a Settings apply under way explains the stillness")
                        .isNotEqualTo(EngineState.DEVICE_LOST);
            }

            tickUntil(controller, clock, () -> controller.engineState() == EngineState.DEVICE_LOST, 30);
            assertThat(controller.engineState()).isEqualTo(EngineState.DEVICE_LOST);
            assertThat(notices.messages()).containsExactly(STALL_NOTICE);
        } finally {
            spy.blockAwait = false;
            engine.stopAudioOutput();
            engine.stop();
            controller.shutdown();
        }
    }

    /**
     * A driver format change refuses a verdict only while its reopen is
     * scheduled: once the reopen ran, a stall of the reopened stream is
     * declared.
     */
    @Test
    void aDriverFormatChangeThatWasReopenedNoLongerHoldsBackAVerdict() throws Exception {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        RecordingNotifications notices = new RecordingNotifications();
        DefaultAudioEngineController controller = new DefaultAudioEngineController(engine, null, notices);
        AtomicLong clock = new AtomicLong();
        controller.setEngineHealthClock(clock::get);
        SpyStreamingBackend spy = new SpyStreamingBackend("SpyFormat");
        DeviceId active = new DeviceId("SpyFormat", "Interface A");
        engine.setStreamingProvision(new StreamingProvision("SpyFormat", List.of(new BackendStreamRung(spy, active))));
        List<EngineStateChange> published = subscribe(controller);
        try {
            engine.startAudioOutput();
            spy.publishDeviceEvent(new AudioDeviceEvent.FormatChangeRequested(active,
                    Optional.of(new com.benesquivelmusic.daw.sdk.audio.AudioFormat(44_100.0, 2, 16)),
                    new FormatChangeReason.BufferSizeChange()));
            await(() -> published.stream().anyMatch(change -> change.state() == EngineState.RUNNING),
                    "the driver's format change reopened the stream");
            assertThat(spy.opens()).as("premise: reopened").hasSize(2);

            controller.tickEngineHealthMonitor();
            assertThat(spy.awaitSunkBlocks(8, 5, TimeUnit.SECONDS)).isTrue();
            tick(controller, clock);
            spy.blockAwait = true;
            await(() -> spy.blockedAwaitEntries.get() >= 1, "the render pump is wedged");
            tickUntil(controller, clock, () -> controller.engineState() == EngineState.DEVICE_LOST, 30);

            assertThat(controller.engineState()).isEqualTo(EngineState.DEVICE_LOST);
            assertThat(notices.messages()).contains(STALL_NOTICE);
        } finally {
            spy.blockAwait = false;
            engine.stopAudioOutput();
            engine.stop();
            controller.shutdown();
        }
    }

    @Test
    void aStreamClosedMoreSlowlyThanTheWindowIsNeverDeclaredLost() throws Exception {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        RecordingNotifications notices = new RecordingNotifications();
        DefaultAudioEngineController controller = new DefaultAudioEngineController(engine, null, notices);
        AtomicLong clock = new AtomicLong();
        controller.setEngineHealthClock(clock::get);
        SpyStreamingBackend spy = new SpyStreamingBackend("SpySlowClose");
        DeviceId active = new DeviceId("SpySlowClose", "Interface A");
        engine.setStreamingProvision(new StreamingProvision("SpySlowClose",
                List.of(new BackendStreamRung(spy, active))));
        CountDownLatch closing = new CountDownLatch(1);
        try {
            engine.startAudioOutput();
            controller.tickEngineHealthMonitor();
            assertThat(spy.awaitSunkBlocks(8, 5, TimeUnit.SECONDS)).isTrue();
            tick(controller, clock);
            spy.armNextCloseHook(() -> {
                closing.countDown();
                LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(3));
            });
            Thread stopper = Thread.ofVirtual().name("test-slow-stop").start(engine::stopAudioOutput);
            assertThat(closing.await(5, TimeUnit.SECONDS)).as("the backend's close is under way").isTrue();

            int ticks = 0;
            while (stopper.isAlive()) {
                tick(controller, clock);
                ticks++;
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            }

            assertThat(controller.engineState()).as("a stream being closed is not a lost device")
                    .isNotEqualTo(EngineState.DEVICE_LOST);
            assertThat(notices.size()).isZero();
            assertThat(ticks * TICK_NANOS).as("premise: the close outlasted the window in monitor time")
                    .isGreaterThan(TimeUnit.SECONDS.toNanos(4));
        } finally {
            engine.stopAudioOutput();
            engine.stop();
            controller.shutdown();
        }
    }

    @Test
    void healthyStreamingAt2048And4096FramesOnRealThreadsNeverTripsAcrossStopPauseResumeAndApply()
            throws Exception {
        for (int frames : new int[]{2048, 4096}) {
            MockAudioBackend mock = new MockAudioBackend();
            AudioBackendSelector selector = new AudioBackendSelector(Map.of(MockAudioBackend.NAME, () -> mock));
            AudioEngine engine = new AudioEngine(new AudioFormat(44_100.0, 2, 16, frames));
            RecordingNotifications notices = new RecordingNotifications();
            DefaultAudioEngineController controller = new DefaultAudioEngineController(engine, null, notices,
                    selector);
            List<EngineStateChange> published = subscribe(controller);
            AudioEngineController.Request request = new AudioEngineController.Request(MockAudioBackend.NAME, "", "",
                    SampleRate.HZ_44100, frames, 16);
            try {
                controller.applyConfiguration(request);
                engine.startAudioOutput();
                tickInRealTime(controller, 4_500);

                controller.applyConfiguration(request);
                assertThat(engine.isStreamOpen()).as("premise: the apply reopened the stream").isTrue();
                tickInRealTime(controller, 2_500);

                engine.pauseAudioOutput();
                tickInRealTime(controller, 2_500);
                engine.startAudioOutput();
                tickInRealTime(controller, 2_500);

                engine.stopAudioOutput();
                tickInRealTime(controller, 2_500);

                assertThat(controller.engineState()).as("%d frames", frames).isNotEqualTo(EngineState.DEVICE_LOST);
                assertThat(notices.messages()).as("%d frames", frames)
                        .noneMatch(message -> message.contains("stopped responding"));
                assertThat(published).extracting(EngineStateChange::state).as("%d frames", frames)
                        .doesNotContain(EngineState.DEVICE_LOST);
            } finally {
                engine.stopAudioOutput();
                engine.stop();
                controller.shutdown();
            }
        }
    }

    @Test
    void theStartedMonitorDeclaresAStallOnItsOwnThread() throws Exception {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        RecordingNotifications notices = new RecordingNotifications();
        DefaultAudioEngineController controller = new DefaultAudioEngineController(engine, null, notices);
        SpyStreamingBackend spy = new SpyStreamingBackend("SpyMonitored");
        DeviceId active = new DeviceId("SpyMonitored", "Interface A");
        engine.setStreamingProvision(new StreamingProvision("SpyMonitored",
                List.of(new BackendStreamRung(spy, active))));
        try {
            engine.startAudioOutput();
            controller.startEngineHealthMonitor();
            assertThat(spy.awaitSunkBlocks(8, 5, TimeUnit.SECONDS)).isTrue();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(300));
            spy.blockAwait = true;

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (notices.size() == 0 && System.nanoTime() - deadline < 0) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
            }

            assertThat(notices.notices())
                    .extracting(RecordingNotifications.Notice::message, RecordingNotifications.Notice::thread)
                    .containsExactly(tuple(STALL_NOTICE, "daw-engine-health-monitor"));
            assertThat(controller.engineState()).isEqualTo(EngineState.DEVICE_LOST);
        } finally {
            spy.blockAwait = false;
            engine.stopAudioOutput();
            engine.stop();
            controller.shutdown();
        }
    }

    @Test
    void theMonitorStartsOnlyWhenAskedOnceAndNeverAfterItWasStopped() throws Exception {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        DefaultAudioEngineController controller = new DefaultAudioEngineController(engine, null,
                new RecordingNotifications());
        Set<Thread> before = monitorThreads();
        try {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(300));
            assertThat(newMonitorThreads(before)).as("constructing the controller starts no monitor").isEmpty();

            controller.startEngineHealthMonitor();
            controller.startEngineHealthMonitor();
            await(() -> !newMonitorThreads(before).isEmpty(), "the monitor's first tick");
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(300));
            assertThat(newMonitorThreads(before)).as("a second start is a no-op").hasSize(1);

            controller.stopEngineHealthMonitor();
            await(() -> newMonitorThreads(before).stream().noneMatch(Thread::isAlive), "the monitor ends");
            Set<Thread> afterStop = monitorThreads();
            controller.startEngineHealthMonitor();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(300));
            assertThat(newMonitorThreads(afterStop)).as("no start after the stop").isEmpty();
        } finally {
            controller.shutdown();
        }
    }

    @Test
    void aMonitorStoppedOrShutDownBeforeItsFirstStartNeverStarts() {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        DefaultAudioEngineController stopped = new DefaultAudioEngineController(engine, null,
                new RecordingNotifications());
        DefaultAudioEngineController shutDown = new DefaultAudioEngineController(engine, null,
                new RecordingNotifications());
        Set<Thread> before = monitorThreads();
        try {
            stopped.stopEngineHealthMonitor();
            shutDown.shutdown();

            stopped.startEngineHealthMonitor();
            shutDown.startEngineHealthMonitor();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(300));

            assertThat(newMonitorThreads(before)).isEmpty();
        } finally {
            stopped.shutdown();
        }
    }

    @Test
    void aTickThatThrowsIsLoggedAndTheScheduleSurvivesIt() {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        DefaultAudioEngineController controller = new DefaultAudioEngineController(engine, null,
                new RecordingNotifications());
        AtomicInteger reads = new AtomicInteger();
        controller.setEngineHealthClock(() -> {
            if (reads.incrementAndGet() <= 3) {
                throw new IllegalStateException("a broken clock");
            }
            return System.nanoTime();
        });
        try {
            assertThatCode(controller::tickEngineHealthMonitor).as("the tick seam swallows and logs")
                    .doesNotThrowAnyException();

            controller.startEngineHealthMonitor();
            await(() -> reads.get() > 6, "the monitor ticks on past the failures");
        } finally {
            controller.shutdown();
        }
    }

    private static Set<Thread> monitorThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().equals("daw-engine-health-monitor"))
                .collect(Collectors.toSet());
    }

    private static Set<Thread> newMonitorThreads(Set<Thread> before) {
        Set<Thread> now = monitorThreads();
        now.removeAll(before);
        return now;
    }

    private static BackendThreadCounters devicePeriods(long periods) {
        return new BackendThreadCounters(0, 0, 0, 0, OptionalLong.of(periods), Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    private static void tick(DefaultAudioEngineController controller, AtomicLong clock) {
        clock.addAndGet(TICK_NANOS);
        controller.tickEngineHealthMonitor();
    }

    private static void tickUntil(DefaultAudioEngineController controller, AtomicLong clock,
                                  java.util.function.BooleanSupplier done, int maxTicks) {
        for (int i = 0; i < maxTicks && !done.getAsBoolean(); i++) {
            tick(controller, clock);
        }
    }

    /** Ticks about every 100 ms on the system clock for {@code millis}. */
    private static void tickInRealTime(DefaultAudioEngineController controller, long millis) {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() - end < 0) {
            LockSupport.parkNanos(TICK_NANOS);
            controller.tickEngineHealthMonitor();
        }
    }

    static List<EngineStateChange> subscribe(AudioEngineController controller) {
        List<EngineStateChange> published = java.util.Collections.synchronizedList(new ArrayList<>());
        controller.engineStateEvents().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(EngineStateChange change) { published.add(change); }
            @Override public void onError(Throwable t) { /* ignore */ }
            @Override public void onComplete() { /* ignore */ }
        });
        return published;
    }
}
