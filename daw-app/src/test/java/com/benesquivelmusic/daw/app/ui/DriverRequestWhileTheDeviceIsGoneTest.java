package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.sdk.audio.AudioBackendException;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceEvent;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.FormatChangeReason;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.await;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A driver's format-change request and a device that is gone (story 338,
 * rulings E36, E111 and E113): a reset whose reopen fails ends as a device
 * loss with its own notice and Reconnect, and names the drive that last ran;
 * a request that arrives while the device is lost with no reopen of it under
 * way, or that reaches the worker after the loss, does nothing, so Reconnect
 * still works; a request the reopened device sends while Reconnect reopens it
 * is handled once the loss has ended, or dropped if that Reconnect fails; and
 * a reopen that is scheduled or running refuses the stall verdict.
 */
@Tag("slow")
class DriverRequestWhileTheDeviceIsGoneTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 24, 128);
    private static final String E113_TAIL = " asked to be reset and could not be reopened — audio stopped. Check that"
            + " it is connected and powered, then choose Reconnect.";

    private final AudioEngine engine = new AudioEngine(FORMAT);
    private final RecordingNotifications notices = new RecordingNotifications();
    private final AtomicInteger reconfigured = new AtomicInteger();
    private final DefaultAudioEngineController controller =
            new DefaultAudioEngineController(engine, reconfigured::incrementAndGet, notices);
    private final SpyStreamingBackend spy = new SpyStreamingBackend("SpyReset");
    private final AtomicLong clock = new AtomicLong();
    private DeviceId device = new DeviceId("SpyReset", "Interface A");

    @AfterEach
    void tearDown() {
        CountDownLatch gate = spy.openGate;
        if (gate != null) {
            gate.countDown();
        }
        spy.openGate = null;
        spy.blockAwait = false;
        spy.failOpensWith(null);
        engine.stopAudioOutput();
        engine.stop();
        controller.shutdown();
    }

    private List<EngineStateChange> open() {
        controller.setEngineHealthClock(clock::get);
        engine.setStreamingProvision(new StreamingProvision("SpyReset", List.of(new BackendStreamRung(spy, device))));
        List<EngineStateChange> published = EngineHealthMonitorTest.subscribe(controller);
        engine.startAudioOutput();
        controller.tickEngineHealthMonitor();
        return published;
    }

    private void resetRequest() {
        spy.publishDeviceEvent(new AudioDeviceEvent.FormatChangeRequested(device, Optional.empty(),
                new FormatChangeReason.DriverReset()));
    }

    @Test
    void aResetWhoseReopenFailsIsADeviceLossWithItsOwnNoticeAndReconnect() throws Exception {
        List<EngineStateChange> published = open();
        spy.failOpensWith(new AudioBackendException("the device is gone"));

        resetRequest();
        awaitTheLastNotice("Interface A" + E113_TAIL);

        assertThat(controller.engineState()).as("the failed reopen ended as a loss").isEqualTo(EngineState.DEVICE_LOST);
        assertThat(engine.isStreamOpen()).isFalse();
        await(() -> published.stream().anyMatch(change -> change.state() == EngineState.DEVICE_LOST),
                "the loss is published");
        assertThat(published).filteredOn(change -> change.state() == EngineState.DEVICE_LOST)
                .extracting(EngineStateChange::cause)
                .containsExactly(EngineStateChange.LossCause.REOPEN_AFTER_RESET_FAILED);
        assertThat(notices.notices()).last()
                .extracting(RecordingNotifications.Notice::message, RecordingNotifications.Notice::actionLabel)
                .containsExactly("Interface A" + E113_TAIL, Optional.of("Reconnect"));
        assertThat(notices.messages()).as("ruling E113: never \"reconfigured\", \"disconnected\" or a stall")
                .noneMatch(message -> message.contains("Audio engine reconfigured")
                        || message.contains("disconnected") || message.contains("stopped responding"));
        assertThat(reconfigured.get()).as("the post-reconfigure callback still ran").isEqualTo(1);
        assertThat(controller.xrunJournal().entries()).as("not a stall: no \"Device stopped responding\" entry")
                .noneMatch(entry -> entry.detail() == XrunJournal.Detail.DEVICE_STOPPED_RESPONDING);

        spy.failOpensWith(null);
        controller.reconnectLostDevice(false).join(TimeUnit.SECONDS.toMillis(10));
        assertThat(controller.engineState()).as("Reconnect reopens it").isEqualTo(EngineState.STOPPED);
        assertThat(notices.messages()).last().isEqualTo("Interface A is responding again. Press Play to continue.");
    }

    @Test
    void aResetWhoseReopenFailsOnTheDefaultDeviceSaysTheAudioDevice() throws Exception {
        device = DeviceId.defaultFor("SpyReset");
        open();
        spy.failOpensWith(new AudioBackendException("the device is gone"));

        resetRequest();
        awaitTheLastNotice("The audio device" + E113_TAIL);

        assertThat(controller.engineState()).as("the failed reopen ended as a loss").isEqualTo(EngineState.DEVICE_LOST);
        assertThat(notices.messages()).as("ruling E111").last().isEqualTo("The audio device" + E113_TAIL);
    }

    /**
     * The loss's notice is shown after DEVICE_LOST is published, so a test
     * that reads it awaits the notice itself, never the state.
     */
    private void awaitTheLastNotice(String expected) {
        await(() -> !notices.messages().isEmpty() && notices.messages().getLast().equals(expected),
                "the notice: " + expected);
    }

    /**
     * After a reset whose reopen fails, the loss names the render drive that
     * last ran — the failed reopen started none — so the health drain clears
     * the fault that drive latched and the Engine cell reads only the loss
     * (ruling E69; the drain's rule is pinned by
     * {@code AudioHealthDrainTest.theLossClearsADeviceSideFactLatchedOnTheLostDrive}).
     */
    @Test
    void aResetWhoseReopenFailsNamesTheDriveThatLastRanAsTheLostOne() throws Exception {
        open();
        long beats = engine.audioHealth().renderDriveBeats();
        await(() -> engine.audioHealth().renderDriveBeats() >= beats + 2, "the render drive beats");
        long lastRan = engine.audioHealth().renderDriveEpoch();
        spy.failOpensWith(new AudioBackendException("the device is gone"));

        resetRequest();
        awaitTheLastNotice("Interface A" + E113_TAIL);

        assertThat(engine.audioHealth().renderDriveEpoch()).as("the failed reopen started no drive")
                .isEqualTo(lastRan);
        assertThat(controller.lostDriveEpoch()).as("the drive the loss names").isEqualTo(lastRan);
    }

    /**
     * A reset request the reopened device sends while Reconnect's open is
     * held is not dropped: the worker waits for the monitor and handles it
     * once the loss has ended, exactly once, and the stream is restored.
     */
    @Test
    void aRequestWhileReconnectsOpenIsHeldIsHandledOnceTheLossHasEnded() throws Exception {
        open();
        spy.simulateDeviceRemoved(device);
        await(() -> controller.engineState() == EngineState.DEVICE_LOST && notices.size() == 1, "the removal");
        spy.openGate = new CountDownLatch(1);

        Thread reconnect = controller.reconnectLostDevice(false);
        await(() -> spy.openEntries.get() == 1, "Reconnect's open is held");
        resetRequest();
        await(DriverRequestWhileTheDeviceIsGoneTest::theFormatChangeWorkerWaitsForTheMonitor,
                "the request was kept: its reopen waits for the monitor");
        spy.openGate.countDown();
        reconnect.join(TimeUnit.SECONDS.toMillis(10));

        await(() -> reconfigured.get() == 1 && controller.engineState() != EngineState.RECONFIGURING
                && !theFormatChangeWorkerWaitsForTheMonitor(), "the request was handled");
        assertThat(reconfigured.get()).as("once").isEqualTo(1);
        assertThat(controller.engineState()).isNotEqualTo(EngineState.DEVICE_LOST);
        assertThat(engine.isStreamOpen()).as("the stream is restored").isTrue();
    }

    /**
     * A reset request the reopened device sends after the open and before
     * Reconnect publishes the end of the loss — from inside the reopen's own
     * stream-open seam — is handled once the loss has ended.
     */
    @Test
    void aRequestBetweenReconnectsOpenAndItsEndOfTheLossIsHandledAfterIt() throws Exception {
        open();
        spy.simulateDeviceRemoved(device);
        await(() -> controller.engineState() == EngineState.DEVICE_LOST && notices.size() == 1, "the removal");
        List<String> stateWhenSent = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean sent = new AtomicBoolean();
        controller.setStreamOpenedCallback(() -> {
            if (!sent.compareAndSet(false, true)) {
                return;
            }
            stateWhenSent.add("lost=" + controller.isDeviceLost());
            resetRequest();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!theFormatChangeWorkerWaitsForTheMonitor() && System.nanoTime() - deadline < 0) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
            }
        });

        controller.reconnectLostDevice(false).join(TimeUnit.SECONDS.toMillis(10));

        assertThat(stateWhenSent).as("premise: sent while the device was still lost").containsExactly("lost=true");
        await(() -> reconfigured.get() == 1 && controller.engineState() != EngineState.RECONFIGURING
                && !theFormatChangeWorkerWaitsForTheMonitor(), "the request was handled");
        assertThat(reconfigured.get()).as("once").isEqualTo(1);
        assertThat(engine.isStreamOpen()).as("the stream is restored").isTrue();
    }

    /** A request kept while Reconnect's open was held is dropped under the monitor when that Reconnect fails. */
    @Test
    void aRequestWhileAReconnectThatFailsWasUnderWayIsDroppedAndTheDeviceStaysLost() throws Exception {
        open();
        spy.simulateDeviceRemoved(device);
        await(() -> controller.engineState() == EngineState.DEVICE_LOST && notices.size() == 1, "the removal");
        spy.failOpensWith(new AudioBackendException("the driver reports no device"));
        spy.openGate = new CountDownLatch(1);

        Thread reconnect = controller.reconnectLostDevice(false);
        await(() -> spy.openEntries.get() == 1, "Reconnect's open is held");
        resetRequest();
        await(DriverRequestWhileTheDeviceIsGoneTest::theFormatChangeWorkerWaitsForTheMonitor,
                "the request was kept: its reopen waits for the monitor");
        spy.openGate.countDown();
        reconnect.join(TimeUnit.SECONDS.toMillis(10));
        await(() -> !theFormatChangeWorkerWaitsForTheMonitor(), "the worker had the monitor");
        await(() -> !notices.messages().isEmpty() && notices.messages().getLast().contains("could not be reopened"),
                "Reconnect's failure");

        assertThat(controller.engineState()).isEqualTo(EngineState.DEVICE_LOST);
        assertThat(reconfigured.get()).as("dropped: nothing reconfigured").isZero();
        assertThat(notices.messages()).noneMatch(message -> message.contains("Reconfiguring"));
    }

    @Test
    void aResetRequestAfterAStallVerdictLeavesTheLossAndReconnectThenReopens() throws Exception {
        open();
        tickAfterBeats();
        spy.blockAwait = true;
        await(() -> spy.blockedAwaitEntries.get() >= 1, "the render pump is wedged");
        for (int i = 0; i < 40 && controller.engineState() != EngineState.DEVICE_LOST; i++) {
            tick();
        }
        assertThat(controller.engineState()).isEqualTo(EngineState.DEVICE_LOST);
        spy.blockAwait = false;

        resetRequest();
        Thread.sleep(1_000);

        assertThat(controller.engineState()).as("the request is dropped while the device is lost")
                .isEqualTo(EngineState.DEVICE_LOST);
        controller.reconnectLostDevice(false).join(TimeUnit.SECONDS.toMillis(10));
        assertThat(controller.engineState()).isEqualTo(EngineState.STOPPED);
        assertThat(notices.messages()).last().isEqualTo("Interface A is responding again. Press Play to continue.");
    }

    @Test
    void aRequestThatReachesTheWorkerAfterALossIsDroppedThere() throws Exception {
        open();

        resetRequest();
        spy.simulateDeviceRemoved(device);
        await(() -> controller.engineState() == EngineState.DEVICE_LOST, "the removal");
        Thread.sleep(1_000);

        assertThat(controller.engineState()).as("the worker's reopen did not end the loss")
                .isEqualTo(EngineState.DEVICE_LOST);
        assertThat(notices.messages()).noneMatch(message -> message.contains("reconfigured"));
    }

    @Test
    void aRequestThatArrivesWhileTheDeviceIsLostIsNotRunAfterTheDeviceReturns() throws Exception {
        List<EngineStateChange> published = open();
        spy.simulateDeviceRemoved(device);
        await(() -> controller.engineState() == EngineState.DEVICE_LOST, "the removal");

        resetRequest();
        spy.publishDeviceEvent(new AudioDeviceEvent.DeviceArrived(device));
        await(() -> controller.engineState() == EngineState.STOPPED, "the device's return");
        Thread.sleep(1_000);

        assertThat(published).extracting(EngineStateChange::state).as("no reopen after the return")
                .endsWith(EngineState.DEVICE_LOST, EngineState.STOPPED);
        assertThat(notices.messages()).noneMatch(message -> message.contains("reconfigured"));
        assertThat(engine.isStreamPaused()).as("the returned stream stays parked").isTrue();
    }

    @Test
    void aReopenTakenOffTheQueueButNotDoneRefusesTheStallVerdict() throws Exception {
        open();
        tickAfterBeats();
        spy.blockAwait = true;
        await(() -> spy.blockedAwaitEntries.get() >= 1, "the render pump is wedged");
        synchronized (controller) {
            resetRequest();
            await(() -> theFormatChangeWorkerWaitsForTheMonitor(), "the reopen task runs and waits for the monitor");
            for (int i = 0; i < 40; i++) {
                tick();
            }
            assertThat(controller.engineState()).as("a reopen still running refuses every verdict")
                    .isNotEqualTo(EngineState.DEVICE_LOST);
            spy.blockAwait = false;
        }
    }

    private static boolean theFormatChangeWorkerWaitsForTheMonitor() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.getName().equals("daw-format-change-worker")
                        && thread.getState() == Thread.State.BLOCKED);
    }

    private void tickAfterBeats() {
        long beats = engine.audioHealth().renderDriveBeats();
        await(() -> engine.audioHealth().renderDriveBeats() >= beats + 2, "the render drive beats");
        tick();
    }

    private void tick() {
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(DefaultAudioEngineController.ENGINE_HEALTH_TICK_MILLIS));
        controller.tickEngineHealthMonitor();
    }
}
