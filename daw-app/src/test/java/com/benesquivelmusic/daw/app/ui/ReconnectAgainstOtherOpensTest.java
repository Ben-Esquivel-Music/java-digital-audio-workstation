package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.StreamOpenRefusedException;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.sdk.audio.AudioBackendException;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceEvent;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Reconnect against what else happens to the stream (story 338, rulings E36,
 * E57, E111 and E112): it never parks or closes a stream a Play opened while
 * it waited, nor one a Play that waited behind its open was accepted on — the
 * open, the check of the device and the park or close are one section of the
 * engine's lifecycle lock, for the person's Reconnect and for the device's
 * own return alike; a stream another caller opened is left to that open's
 * announcement; the lost stream itself is closed again before it is
 * reopened; one runs at a time however often the person presses, also when
 * its thread could not start; it says why it failed in one of four fixed
 * reasons, and a device with no name to show is "the audio device".
 */
class ReconnectAgainstOtherOpensTest {

    private static final String CHECK = ". Check that it is connected and powered, then choose Reconnect again,"
            + " or choose another device in Audio Settings.";
    private static final List<String> REASONS = List.of("the previous audio stream has not shut down yet",
            "it opened with an audio format that cannot be used", "it is not available",
            "the audio driver refused to open it");
    private static final DeviceId INTERFACE_A = new DeviceId("SpyLost", "Interface A");
    private static final String LOSS_NOTICE = "Audio device disconnected — playback stopped";

    private final AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
    private final RecordingNotifications notices = new RecordingNotifications();
    private final DefaultAudioEngineController controller = new DefaultAudioEngineController(engine, null, notices);
    private final SpyStreamingBackend spy = new SpyStreamingBackend("SpyLost");
    private final SpyStreamingBackend other = new SpyStreamingBackend("SpyOther");
    private final Logger controllerLog = Logger.getLogger(DefaultAudioEngineController.class.getName());
    private final Level controllerLogLevel = controllerLog.getLevel();
    private HeldLogLine heldLogLine;

    @AfterEach
    void tearDown() {
        if (heldLogLine != null) {
            heldLogLine.release.countDown();
            controllerLog.removeHandler(heldLogLine);
            controllerLog.setLevel(controllerLogLevel);
        }
        for (SpyStreamingBackend backend : List.of(spy, other)) {
            CountDownLatch gate = backend.openGate;
            if (gate != null) {
                gate.countDown();
            }
            backend.openGate = null;
            backend.failOpensWith(null);
            backend.blockAwait = false;
        }
        engine.stopAudioOutput();
        engine.stop();
        controller.shutdown();
    }

    /**
     * Holds the thread that logs a line starting with {@code prefix} inside
     * that log call until {@link #release} counts down: a step of the
     * controller's own program order a test can stop at, with whatever the
     * thread holds there still held.
     */
    private static final class HeldLogLine extends Handler {
        private final String prefix;
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        HeldLogLine(String prefix) {
            this.prefix = prefix;
            setLevel(Level.ALL);
        }

        @Override
        public void publish(LogRecord logged) {
            if (logged.getMessage() == null || !logged.getMessage().startsWith(prefix)) {
                return;
            }
            reached.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private HeldLogLine holdTheControllerAt(String prefix) {
        heldLogLine = new HeldLogLine(prefix);
        controllerLog.setLevel(Level.ALL);
        controllerLog.addHandler(heldLogLine);
        return heldLogLine;
    }

    /** What a Play from the transport saw: whether it was accepted, as {@code TransportController} judges it. */
    private record Play(Thread thread, AtomicBoolean accepted, AtomicReference<Throwable> failure) {
        static Play start(AudioEngine engine) {
            AtomicBoolean accepted = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread thread = Thread.ofVirtual().start(() -> {
                try {
                    engine.startAudioOutput();
                    accepted.set(engine.isStreamOpen() && !engine.isStreamPaused());
                } catch (Throwable refused) {
                    failure.set(refused);
                }
            });
            return new Play(thread, accepted, failure);
        }
    }

    /**
     * The lost device's reopen is started by {@code reopen} and held inside
     * {@code gated}'s open; a Play then waits for the engine's lifecycle lock
     * behind it; the open is released.
     */
    private Play aPlayWaitingBehindTheReopensOpen(SpyStreamingBackend gated, Runnable reopen) throws Exception {
        gated.openGate = new CountDownLatch(1);
        reopen.run();
        await(() -> gated.openEntries.get() == 1, "the reopen's open is held");
        Play play = Play.start(engine);
        await(() -> play.thread().getState() == Thread.State.WAITING, "Play waits for the engine's lifecycle lock");
        gated.openGate.countDown();
        play.thread().join(TimeUnit.SECONDS.toMillis(10));
        return play;
    }

    private void assertThePlayWasNeverAcceptedOverAStreamThatWasThenParkedOrClosed(Play play) {
        assertThat(play.failure().get()).as("the Play was not refused").isNull();
        assertThat(play.accepted().get()).as("the Play was accepted").isTrue();
        assertThat(engine.isStreamOpen()).as("its stream is open: never closed under it").isTrue();
        assertThat(engine.isStreamPaused()).as("and playing: never parked under it").isFalse();
    }

    private void openThenLose(DeviceId device) {
        engine.setStreamingProvision(new StreamingProvision("SpyLost", List.of(new BackendStreamRung(spy, device))));
        engine.startAudioOutput();
        spy.simulateDeviceRemoved(device);
        await(() -> controller.engineState() == EngineState.DEVICE_LOST && notices.size() == 1, "the loss");
    }

    /** Opens on Interface A with Interface B as the ladder's next rung, unplugs A, and makes A refuse to open. */
    private void openThenLoseWithAnotherDeviceBehind() {
        engine.setStreamingProvision(new StreamingProvision("SpyLost", List.of(new BackendStreamRung(spy, INTERFACE_A),
                new BackendStreamRung(other, new DeviceId("SpyOther", "Interface B")))));
        engine.startAudioOutput();
        spy.simulateDeviceRemoved(INTERFACE_A);
        await(() -> controller.engineState() == EngineState.DEVICE_LOST && notices.size() == 1, "the loss");
        spy.failOpensWith(new AudioBackendException("the driver reports no device"));
    }

    @Test
    void aPlayThatWaitedBehindReconnectsOpenFindsTheStreamParkedAndPlaysIt() throws Exception {
        openThenLose(INTERFACE_A);
        AtomicReference<Thread> reconnect = new AtomicReference<>();

        Play play = aPlayWaitingBehindTheReopensOpen(spy, () -> reconnect.set(controller.reconnectLostDevice(false)));
        reconnect.get().join(TimeUnit.SECONDS.toMillis(10));

        assertThePlayWasNeverAcceptedOverAStreamThatWasThenParkedOrClosed(play);
        assertThat(engine.openStreamDevice()).contains(INTERFACE_A);
    }

    @Test
    void aPlayThatWaitedBehindAReconnectThatLandedOnAnotherDeviceIsNeverLeftOverAClosedStream() throws Exception {
        openThenLoseWithAnotherDeviceBehind();
        AtomicReference<Thread> reconnect = new AtomicReference<>();

        Play play = aPlayWaitingBehindTheReopensOpen(other, () -> reconnect.set(controller.reconnectLostDevice(false)));
        reconnect.get().join(TimeUnit.SECONDS.toMillis(10));

        assertThat(notices.messages()).as("premise: Reconnect's ladder landed on the other device")
                .contains("Interface A could not be reopened: it is not available" + CHECK);
        assertThePlayWasNeverAcceptedOverAStreamThatWasThenParkedOrClosed(play);
    }

    @Test
    void aPlayThatWaitedBehindTheDevicesOwnReturnFindsTheStreamParkedAndPlaysIt() throws Exception {
        openThenLose(INTERFACE_A);

        Play play = aPlayWaitingBehindTheReopensOpen(spy,
                () -> spy.publishDeviceEvent(new AudioDeviceEvent.DeviceArrived(INTERFACE_A)));
        await(() -> controller.engineState() == EngineState.STOPPED, "the return ended the loss");

        assertThePlayWasNeverAcceptedOverAStreamThatWasThenParkedOrClosed(play);
    }

    @Test
    void aPlayThatWaitedBehindAReturnThatLandedOnAnotherDeviceIsNeverParkedUnderIt() throws Exception {
        openThenLoseWithAnotherDeviceBehind();

        Play play = aPlayWaitingBehindTheReopensOpen(other,
                () -> spy.publishDeviceEvent(new AudioDeviceEvent.DeviceArrived(INTERFACE_A)));
        await(() -> controller.engineState() == EngineState.STOPPED, "the return ended the loss");

        assertThePlayWasNeverAcceptedOverAStreamThatWasThenParkedOrClosed(play);
    }

    /**
     * A Play's open completed while its announcement waits for the
     * controller's monitor, which Reconnect holds: the stream is open but not
     * Reconnect's, so Reconnect publishes nothing and says nothing, and that
     * announcement ends the loss.
     */
    @Test
    void aStreamAnotherCallerOpenedWhoseAnnouncementIsHeldIsLeftToThatAnnouncement() throws Exception {
        openThenLose(INTERFACE_A);
        int opensBefore = spy.opens().size();
        List<EngineStateChange> published = EngineHealthMonitorTest.subscribe(controller);
        spy.openGate = new CountDownLatch(1);
        Play play = Play.start(engine);
        await(() -> spy.openEntries.get() == 1, "Play's open is in flight");
        HeldLogLine reconnectEntered = holdTheControllerAt("Reconnect requested for the lost device");
        Thread reconnect = controller.reconnectLostDevice(false);
        assertThat(reconnectEntered.reached.await(10, TimeUnit.SECONDS)).as("Reconnect holds the monitor").isTrue();

        spy.openGate.countDown();
        await(() -> play.thread().getState() == Thread.State.BLOCKED, "Play's announcement waits for the monitor");
        assertThat(engine.isStreamOpen()).as("premise: Play's open completed").isTrue();
        reconnectEntered.release.countDown();
        reconnect.join(TimeUnit.SECONDS.toMillis(10));
        play.thread().join(TimeUnit.SECONDS.toMillis(10));
        await(() -> controller.engineState() == EngineState.STOPPED, "Play's own announcement ends the loss");

        assertThePlayWasNeverAcceptedOverAStreamThatWasThenParkedOrClosed(play);
        assertThat(spy.opens()).as("Reconnect opened nothing of its own").hasSize(opensBefore + 1);
        assertThat(notices.messages()).as("Reconnect said nothing about a stream it did not open")
                .containsExactly(LOSS_NOTICE);
        await(() -> !published.isEmpty(), "the end of the loss is published");
        assertThat(published).extracting(EngineStateChange::state).as("one STOPPED, from the announcement")
                .containsExactly(EngineState.STOPPED);
    }

    /**
     * A stall verdict whose stream would not close — the render pump is
     * wedged — then Reconnect: the previous stream has not shut down, and the
     * state stays DEVICE_LOST; once the pump lets go, Reconnect reopens.
     */
    @Test
    void aReconnectWhileTheStalledStreamHasNotShutDownSaysSoAndALaterOneReopens() throws Exception {
        AtomicLong clock = new AtomicLong();
        controller.setEngineHealthClock(clock::get);
        engine.setStreamingProvision(new StreamingProvision("SpyLost",
                List.of(new BackendStreamRung(spy, INTERFACE_A))));
        engine.startAudioOutput();
        controller.tickEngineHealthMonitor();
        long beats = engine.audioHealth().renderDriveBeats();
        await(() -> engine.audioHealth().renderDriveBeats() >= beats + 2, "the render drive beats");
        tick(clock);
        spy.blockAwait = true;
        await(() -> spy.blockedAwaitEntries.get() >= 1, "the render pump is wedged");
        for (int i = 0; i < 40 && controller.engineState() != EngineState.DEVICE_LOST; i++) {
            tick(clock);
        }
        await(() -> notices.size() == 1, "the stall's notice");

        controller.reconnectLostDevice(false).join(TimeUnit.SECONDS.toMillis(10));

        assertThat(controller.engineState()).isEqualTo(EngineState.DEVICE_LOST);
        assertThat(notices.notices()).hasSize(2).last()
                .extracting(RecordingNotifications.Notice::message, RecordingNotifications.Notice::actionLabel)
                .containsExactly("Interface A could not be reopened: the previous audio stream has not shut down yet"
                        + CHECK, Optional.of("Reconnect"));

        spy.blockAwait = false;
        controller.reconnectLostDevice(false).join(TimeUnit.SECONDS.toMillis(10));

        assertThat(controller.engineState()).isEqualTo(EngineState.STOPPED);
        assertThat(engine.isStreamOpen()).as("reopened").isTrue();
        assertThat(engine.isStreamPaused()).as("and parked").isTrue();
        assertThat(notices.messages()).as("said once, for the reopen that happened").filteredOn(message ->
                message.contains("is responding again")).containsExactly(
                        "Interface A is responding again. Press Play to continue.");
    }

    /** A Reconnect whose thread could not start leaves no Reconnect in flight: the next one runs. */
    @Test
    void aReconnectWhoseThreadCannotStartLeavesTheNextOneFreeToRun() throws Exception {
        openThenLose(INTERFACE_A);
        controller.setReconnectThreadFactory(body -> new Thread(body) {
            @Override
            public synchronized void start() {
                throw new IllegalStateException("no thread can be started");
            }
        });

        assertThatCode(() -> controller.reconnectLostDevice(false)).as("the failure is logged, not thrown")
                .doesNotThrowAnyException();

        controller.setReconnectThreadFactory(Thread.ofVirtual().name("daw-audio-device-reconnect").factory());
        Thread later = controller.reconnectLostDevice(false);
        later.join(TimeUnit.SECONDS.toMillis(10));
        assertThat(later.isAlive()).isFalse();
        assertThat(controller.engineState()).as("the later Reconnect ran and reopened").isEqualTo(EngineState.STOPPED);
    }

    /**
     * Ruling E139: a Reconnect whose thread cannot start, here for an
     * exception, raises one warning with the Reconnect action and the ruled
     * sentence; the state stays DEVICE_LOST and the next click reconnects.
     */
    @Test
    void aReconnectThatCouldNotStartForAnExceptionSaysSoOnceAndOffersReconnect() throws Exception {
        aReconnectThatCouldNotStartSaysSoOnceAndOffersReconnect(new IllegalStateException("no thread can be started"));
    }

    /** Ruling E139: the same notice for a {@link VirtualMachineError}, which is not rethrown. */
    @Test
    void aReconnectThatCouldNotStartForAnOutOfMemoryErrorSaysSoOnceAndOffersReconnect() throws Exception {
        aReconnectThatCouldNotStartSaysSoOnceAndOffersReconnect(new OutOfMemoryError("unable to create a thread"));
    }

    /** Ruling E139: a thread factory that throws an exception is a Reconnect that could not start. */
    @Test
    void aReconnectWhoseThreadCannotBeMadeForAnExceptionSaysSoOnceAndOffersReconnect() throws Exception {
        aReconnectThatCouldNotStartSaysSoOnceAndOffersReconnect(_ -> {
            throw new IllegalStateException("no thread can be made");
        });
    }

    /** Ruling E139: the same for a {@link VirtualMachineError} while the thread is made, which is not rethrown. */
    @Test
    void aReconnectWhoseThreadCannotBeMadeForAnOutOfMemoryErrorSaysSoOnceAndOffersReconnect() throws Exception {
        aReconnectThatCouldNotStartSaysSoOnceAndOffersReconnect(_ -> {
            throw new OutOfMemoryError("unable to create a thread");
        });
    }

    /** Ruling E139: a thread factory that makes no thread is a Reconnect that could not start. */
    @Test
    void aReconnectWhoseThreadFactoryMakesNoThreadSaysSoOnceAndOffersReconnect() throws Exception {
        aReconnectThatCouldNotStartSaysSoOnceAndOffersReconnect(_ -> null);
    }

    private void aReconnectThatCouldNotStartSaysSoOnceAndOffersReconnect(Throwable cause) throws Exception {
        aReconnectThatCouldNotStartSaysSoOnceAndOffersReconnect(body -> new Thread(body) {
            @Override
            public synchronized void start() {
                if (cause instanceof Error error) {
                    throw error;
                }
                throw (RuntimeException) cause;
            }
        });
    }

    private void aReconnectThatCouldNotStartSaysSoOnceAndOffersReconnect(ThreadFactory failing) throws Exception {
        openThenLose(INTERFACE_A);
        int noticesAtTheLoss = notices.size();
        controller.setReconnectThreadFactory(failing);

        assertThatCode(() -> controller.reconnectLostDevice(false)).as("nothing is rethrown")
                .doesNotThrowAnyException();
        List<RecordingNotifications.Notice> raised =
                notices.notices().subList(noticesAtTheLoss, notices.size());
        EngineState afterTheClick = controller.engineState();
        controller.setReconnectThreadFactory(Thread.ofVirtual().name("daw-audio-device-reconnect").factory());
        Thread later = controller.reconnectLostDevice(false);
        later.join(TimeUnit.SECONDS.toMillis(10));

        assertThat(raised).as("one notice, the ruled sentence, with the Reconnect action (E139)").singleElement()
                .extracting(RecordingNotifications.Notice::message, RecordingNotifications.Notice::actionLabel)
                .containsExactly("Reconnect could not start. Choose Reconnect again, or choose another device in"
                        + " Audio Settings.", Optional.of("Reconnect"));
        assertThat(afterTheClick).as("the device stays lost").isEqualTo(EngineState.DEVICE_LOST);
        assertThat(later.isAlive()).isFalse();
        assertThat(controller.engineState()).as("the slot was released: the next click reconnected")
                .isEqualTo(EngineState.STOPPED);
    }

    private void tick(AtomicLong clock) {
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(DefaultAudioEngineController.ENGINE_HEALTH_TICK_MILLIS));
        controller.tickEngineHealthMonitor();
    }

    @Test
    void aPlayWhoseOpenWasInFlightKeepsItsRunningStreamAndItsOwnAnnouncementEndsTheLoss() throws Exception {
        openThenLose(new DeviceId("SpyLost", "Interface A"));
        int opensBefore = spy.opens().size();
        spy.openGate = new CountDownLatch(1);
        AtomicReference<Throwable> playFailure = new AtomicReference<>();
        Thread play = Thread.ofVirtual().start(() -> {
            try {
                engine.startAudioOutput();
            } catch (Throwable failure) {
                playFailure.set(failure);
            }
        });
        await(() -> spy.openEntries.get() == 1, "Play's open is in flight");

        Thread reconnect = controller.reconnectLostDevice(false);
        await(() -> reconnect.getState() == Thread.State.WAITING, "Reconnect waits for the engine's lifecycle lock");
        spy.openGate.countDown();
        play.join(TimeUnit.SECONDS.toMillis(10));
        reconnect.join(TimeUnit.SECONDS.toMillis(10));
        await(() -> controller.engineState() == EngineState.STOPPED, "Play's own announcement ends the loss");

        assertThat(playFailure.get()).isNull();
        assertThat(spy.opens()).as("Reconnect opened nothing of its own").hasSize(opensBefore + 1);
        assertThat(engine.isStreamOpen()).as("the person's stream is open").isTrue();
        assertThat(engine.isStreamPaused()).as("and still playing: Reconnect never parked it").isFalse();
        assertThat(notices.messages()).as("Reconnect said nothing about a stream it did not open")
                .containsExactly("Audio device disconnected — playback stopped");
    }

    @Test
    void requestsWhileOneReconnectIsUnderWayAreDroppedAndALaterOneRuns() throws Exception {
        openThenLose(new DeviceId("SpyLost", "Interface A"));
        spy.failOpensWith(new AudioBackendException("the driver reports no device"));
        spy.openGate = new CountDownLatch(1);

        Thread first = controller.reconnectLostDevice(false);
        await(() -> spy.openEntries.get() == 1, "the first Reconnect's open is held");
        Thread second = controller.reconnectLostDevice(false);
        Thread third = controller.reconnectLostDevice(false);
        Thread fourth = controller.reconnectLostDevice(false);
        spy.openGate.countDown();
        for (Thread attempt : List.of(first, second, third, fourth)) {
            attempt.join(TimeUnit.SECONDS.toMillis(10));
        }

        assertThat(spy.openEntries.get()).as("one attempt").isEqualTo(1);
        assertThat(notices.messages()).as("one failure notice").filteredOn(message -> message.contains("could not be"
                + " reopened")).hasSize(1);
        assertThat(List.of(second, third, fourth)).as("each later request got the running one").containsOnly(first);

        Thread later = controller.reconnectLostDevice(false);
        later.join(TimeUnit.SECONDS.toMillis(10));
        assertThat(later).isNotSameAs(first);
        assertThat(spy.openEntries.get()).as("a request after it finished runs").isEqualTo(2);
    }

    @Test
    void aPreviousStreamThatHasNotLetGoIsSaidSo() {
        assertThat(DefaultAudioEngineController.reconnectFailureReason(new StreamOpenRefusedException(
                StreamOpenRefusedException.Reason.PREVIOUS_STREAM_NOT_RELEASED, "the handle is held; close()")))
                .isEqualTo("the previous audio stream has not shut down yet");
    }

    @Test
    void aFormatTheEngineCannotUseIsSaidSo() {
        assertThat(DefaultAudioEngineController.reconnectFailureReason(new StreamOpenRefusedException(
                StreamOpenRefusedException.Reason.UNUSABLE_FORMAT, "negotiated 4 channels")))
                .isEqualTo("it opened with an audio format that cannot be used");
    }

    @Test
    void aDeviceThatIsNotThereIsNotAvailable() {
        assertThat(DefaultAudioEngineController.reconnectFailureReason(new StreamOpenRefusedException(
                StreamOpenRefusedException.Reason.NOT_AVAILABLE, "so the ladder skipped it")))
                .isEqualTo("it is not available");
    }

    @Test
    void anythingElseIsTheDriversRefusalWhateverItsMessageSays() {
        assertThat(DefaultAudioEngineController.reconnectFailureReason(new AudioBackendException("Cannot open a"
                + " new audio stream: the previous stream's handle is still held by ASIO, which returned from"
                + " close() with the release DEFERRED", new IllegalStateException("ladder"))))
                .as("prose is never matched").isEqualTo("the audio driver refused to open it");
        assertThat(DefaultAudioEngineController.reconnectFailureReason(new IllegalStateException("wrapped",
                new StreamOpenRefusedException(StreamOpenRefusedException.Reason.NOT_AVAILABLE, "none"))))
                .as("a wrapped refusal keeps its reason").isEqualTo("it is not available");
        assertThat(DefaultAudioEngineController.reconnectFailureReason(null))
                .isEqualTo("the audio driver refused to open it");
    }

    @Test
    void theReasonANoticeGivesIsAlwaysOneOfTheFourAndNeverTheEnginesWords() throws Exception {
        openThenLose(new DeviceId("SpyLost", "Interface A"));
        spy.failOpensWith(new AudioBackendException("Cannot open: handle held by close() in the ladder"));

        controller.reconnectLostDevice(false).join(TimeUnit.SECONDS.toMillis(10));

        String notice = notices.messages().getLast();
        assertThat(notice).startsWith("Interface A could not be reopened: ").endsWith(CHECK);
        String reason = notice.substring("Interface A could not be reopened: ".length(),
                notice.length() - CHECK.length());
        assertThat(reason).as("ruling E112").isIn(REASONS).doesNotContain("close()", "handle", "ladder");
        assertThat(Character.isLowerCase(reason.charAt(0))).as("never a capital").isTrue();
    }

    @Test
    void theDefaultDevicesReconnectSentencesSayTheAudioDevice() throws Exception {
        openThenLose(DeviceId.defaultFor("SpyLost"));
        spy.failOpensWith(new AudioBackendException("the driver reports no device"));
        controller.reconnectLostDevice(false).join(TimeUnit.SECONDS.toMillis(10));
        assertThat(notices.messages().getLast()).as("ruling E111, the failure")
                .isEqualTo("The audio device could not be reopened: the audio driver refused to open it" + CHECK);

        spy.failOpensWith(null);
        controller.reconnectLostDevice(false).join(TimeUnit.SECONDS.toMillis(10));
        assertThat(notices.messages().getLast()).as("ruling E111, the success")
                .isEqualTo("The audio device is responding again. Press Play to continue.");
    }
}
