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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Reconnect after a device loss (story 338, amendment A13,
 * rulings E6, E7, E56, E57 and E67), over a real engine and a real injected
 * {@link NotificationManager}: it reopens only the lost device and parks the
 * stream, leaves the words to the record coordinator when a take was rescued,
 * says "is responding again" when none was, names the cause of a failure in
 * the person's terms and keeps the state DEVICE_LOST, and does nothing once
 * the loss is over.
 */
@Tag("slow")
class ReconnectLostDeviceTest {

    private static final String CHECK = ". Check that it is connected and powered, then choose Reconnect again,"
            + " or choose another device in Audio Settings.";

    private final AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
    private final RecordingNotifications notices = new RecordingNotifications();
    private final DefaultAudioEngineController controller = new DefaultAudioEngineController(engine, null, notices);
    private final SpyStreamingBackend lost = new SpyStreamingBackend("SpyLost");
    private final DeviceId lostDevice = new DeviceId("SpyLost", "Interface A");

    @AfterEach
    void tearDown() {
        lost.blockAwait = false;
        engine.stopAudioOutput();
        engine.stop();
        controller.shutdown();
    }

    /** Opens the stream on {@code rungs}, the first of which is the lost device's, and unplugs it. */
    private List<EngineStateChange> openThenLose(BackendStreamRung... rungs) {
        engine.setStreamingProvision(new StreamingProvision("SpyLost", List.of(rungs)));
        List<EngineStateChange> published = EngineHealthMonitorTest.subscribe(controller);
        engine.startAudioOutput();
        assertThat(engine.openStreamDevice()).as("premise: the stream opened on the lost device").contains(lostDevice);
        lost.simulateDeviceRemoved(lostDevice);
        await(() -> controller.engineState() == EngineState.DEVICE_LOST && notices.size() == 1, "the loss");
        return published;
    }

    private void reconnect(boolean takeRescued) throws InterruptedException {
        Thread reconnect = controller.reconnectLostDevice(takeRescued);
        assertThat(reconnect.getName()).isEqualTo("daw-audio-device-reconnect");
        reconnect.join(TimeUnit.SECONDS.toMillis(10));
        assertThat(reconnect.isAlive()).as("the reconnect finished").isFalse();
    }

    @Test
    void theDeviceBackWithARescuedTakeReopensParksAndLeavesTheWordsToTheRecordCoordinator() throws Exception {
        List<EngineStateChange> published = openThenLose(new BackendStreamRung(lost, lostDevice));

        reconnect(true);

        assertThat(controller.engineState()).isEqualTo(EngineState.STOPPED);
        assertThat(engine.isStreamOpen()).as("reopened").isTrue();
        assertThat(engine.isStreamPaused()).as("and parked").isTrue();
        await(() -> published.size() == 2, "the end of the loss is published");
        assertThat(published.get(1)).isEqualTo(new EngineStateChange(EngineState.STOPPED, Optional.of(lostDevice)));
        assertThat(notices.messages()).as("nothing beyond the loss's own notice")
                .containsExactly("Audio device disconnected — playback stopped");
    }

    @Test
    void theDeviceBackWithNoTakeIsRespondingAgain() throws Exception {
        openThenLose(new BackendStreamRung(lost, lostDevice));

        reconnect(false);

        assertThat(controller.engineState()).isEqualTo(EngineState.STOPPED);
        assertThat(notices.notices()).last()
                .extracting(RecordingNotifications.Notice::message, RecordingNotifications.Notice::actionLabel)
                .containsExactly("Interface A is responding again. Press Play to continue.", Optional.empty());
    }

    @Test
    void aDeviceStillAbsentCouldNotBeReopenedAndTheStateStaysDeviceLost() throws Exception {
        openThenLose(new BackendStreamRung(lost, lostDevice));
        lost.failOpensWith(new AudioBackendException("the driver reports no device"));

        reconnect(false);

        assertThat(controller.engineState()).isEqualTo(EngineState.DEVICE_LOST);
        assertThat(engine.isStreamOpen()).isFalse();
        RecordingNotifications.Notice failure = notices.notices().getLast();
        assertThat(failure.message()).startsWith("Interface A could not be reopened: ").endsWith(CHECK)
                .doesNotContain("Exception").doesNotContain("reconnected");
        assertThat(failure.actionLabel()).as("Reconnect is offered again").contains("Reconnect");
    }

    @Test
    void aLadderThatLandsOnAnotherDeviceIsClosedAgainAndTheLostDeviceIsStillWatched() throws Exception {
        SpyStreamingBackend other = new SpyStreamingBackend("SpyOther");
        DeviceId otherDevice = new DeviceId("SpyOther", "Interface B");
        openThenLose(new BackendStreamRung(lost, lostDevice), new BackendStreamRung(other, otherDevice));
        lost.failOpensWith(new AudioBackendException("the driver reports no device"));

        reconnect(false);

        assertThat(other.opens()).as("premise: the ladder landed on the other device").hasSize(1);
        assertThat(other.isOpen()).as("that stream is not kept").isFalse();
        assertThat(engine.isStreamOpen()).isFalse();
        assertThat(controller.engineState()).isEqualTo(EngineState.DEVICE_LOST);
        assertThat(controller.getWatchedDevice()).as("the watch is back on the lost device").contains(lostDevice);
        assertThat(notices.notices()).last()
                .extracting(RecordingNotifications.Notice::message, RecordingNotifications.Notice::actionLabel)
                .containsExactly("Interface A could not be reopened: it is not available" + CHECK,
                        Optional.of("Reconnect"));

        lost.failOpensWith(null);
        lost.publishDeviceEvent(new AudioDeviceEvent.DeviceArrived(lostDevice));
        await(() -> controller.engineState() == EngineState.STOPPED, "the lost device's own return is still noticed");
        assertThat(engine.openStreamDevice()).contains(lostDevice);
    }

    @Test
    void aPreviousStreamThatHasNotShutDownIsSaidInThePersonsTerms() throws Exception {
        engine.setStreamingProvision(new StreamingProvision("SpyLost",
                List.of(new BackendStreamRung(lost, lostDevice))));
        engine.startAudioOutput();
        lost.blockAwait = true;
        await(() -> lost.blockedAwaitEntries.get() >= 1, "the render pump is wedged");
        lost.simulateDeviceRemoved(lostDevice);
        await(() -> controller.engineState() == EngineState.DEVICE_LOST && notices.size() == 1, "the loss");

        reconnect(false);

        assertThat(controller.engineState()).isEqualTo(EngineState.DEVICE_LOST);
        assertThat(notices.notices()).last()
                .extracting(RecordingNotifications.Notice::message, RecordingNotifications.Notice::actionLabel)
                .containsExactly("Interface A could not be reopened: the previous audio stream has not shut down yet"
                        + CHECK, Optional.of("Reconnect"));
    }

    @Test
    void aSecondReconnectAfterTheLossEndedDoesNothing() throws Exception {
        openThenLose(new BackendStreamRung(lost, lostDevice));
        reconnect(false);
        int opens = lost.opens().size();
        int noticesBefore = notices.size();

        reconnect(false);

        assertThat(lost.opens()).as("nothing reopened").hasSize(opens);
        assertThat(notices.size()).as("nothing said").isEqualTo(noticesBefore);
        assertThat(controller.engineState()).isEqualTo(EngineState.STOPPED);
    }

    @Test
    void anotherDeviceArrivingSaysPlaybackStaysStopped() throws Exception {
        openThenLose(new BackendStreamRung(lost, lostDevice));

        lost.publishDeviceEvent(new AudioDeviceEvent.DeviceArrived(new DeviceId("SpyLost", "Interface C")));
        await(() -> notices.size() == 2, "the remediation");

        assertThat(notices.notices()).last()
                .extracting(RecordingNotifications.Notice::message, RecordingNotifications.Notice::actionLabel)
                .containsExactly("Interface A is still disconnected — Interface C arrived, but it is not"
                        + " the lost device; playback stays stopped", Optional.empty());
        assertThat(controller.engineState()).isEqualTo(EngineState.DEVICE_LOST);
    }

    /** Ruling E126: after a stall another device's arrival never says "disconnected". */
    @Test
    void anotherDeviceArrivingAfterAStallSaysTheLostOneStoppedRespondingAndHasNotBeenReopened() throws Exception {
        engine.setStreamingProvision(new StreamingProvision("SpyLost",
                List.of(new BackendStreamRung(lost, lostDevice))));
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
        controller.setEngineHealthClock(clock::get);
        engine.startAudioOutput();
        controller.tickEngineHealthMonitor();
        long beats = engine.audioHealth().renderDriveBeats();
        await(() -> engine.audioHealth().renderDriveBeats() >= beats + 2, "the render drive beats");
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
        controller.tickEngineHealthMonitor();
        lost.blockAwait = true;
        await(() -> lost.blockedAwaitEntries.get() >= 1, "the render pump is wedged");
        for (int i = 0; i < 40 && controller.engineState() != EngineState.DEVICE_LOST; i++) {
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
            controller.tickEngineHealthMonitor();
        }
        await(() -> notices.size() == 1, "the stall's notice");

        lost.publishDeviceEvent(new AudioDeviceEvent.DeviceArrived(new DeviceId("SpyLost", "Interface C")));
        await(() -> notices.size() == 2, "the remediation");

        assertThat(notices.messages()).last().isEqualTo("Interface A stopped responding and has not been reopened —"
                + " Interface C arrived, but it is not the lost device; playback stays stopped");
    }

    /**
     * The lost device's own return whose reopen the engine refuses (ruling
     * E129): one of E112's reasons and Reconnect; the engine's own words go
     * to the log only.
     */
    @Test
    void aReturnWhoseReopenIsRefusedSaysWhyInThePersonsTermsAndOffersReconnect() throws Exception {
        openThenLose(new BackendStreamRung(lost, lostDevice));
        lost.failOpensWith(new StreamOpenRefusedException(StreamOpenRefusedException.Reason.PREVIOUS_STREAM_NOT_RELEASED,
                "the render pump has not exited; close() deferred"));

        lost.publishDeviceEvent(new AudioDeviceEvent.DeviceArrived(lostDevice));
        await(() -> notices.size() == 2, "the failed return's notice");

        assertThat(notices.notices()).last()
                .extracting(RecordingNotifications.Notice::message, RecordingNotifications.Notice::actionLabel)
                .containsExactly("Interface A was reconnected but could not be reopened: the previous audio stream has"
                        + " not shut down yet. Choose Reconnect, or choose another device in Audio Settings.",
                        Optional.of("Reconnect"));
        assertThat(controller.engineState()).isEqualTo(EngineState.DEVICE_LOST);
    }

    /** As above with a refusal the engine does not type, and a device with no name to show (rulings E111, E112). */
    @Test
    void aReturnWhoseReopenTheDriverRefusesIsTheDriversRefusalWhateverItsMessageSays() throws Exception {
        DeviceId placeholder = DeviceId.defaultFor("SpyLost");
        engine.setStreamingProvision(new StreamingProvision("SpyLost", List.of(new BackendStreamRung(lost,
                placeholder))));
        engine.startAudioOutput();
        lost.simulateDeviceRemoved(placeholder);
        await(() -> controller.engineState() == EngineState.DEVICE_LOST && notices.size() == 1, "the loss");
        lost.failOpensWith(new AudioBackendException("ladder: close() of the render pump"));

        lost.publishDeviceEvent(new AudioDeviceEvent.DeviceArrived(placeholder));
        await(() -> notices.size() == 2, "the failed return's notice");

        assertThat(notices.notices()).last()
                .extracting(RecordingNotifications.Notice::message, RecordingNotifications.Notice::actionLabel)
                .containsExactly("The audio device was reconnected but could not be reopened: the audio driver"
                        + " refused to open it. Choose Reconnect, or choose another device in Audio Settings.",
                        Optional.of("Reconnect"));
    }

    @Test
    void aFailureTheEngineDoesNotNameIsTheDriversRefusalAndItsMessageNeverReachesTheNotice() {
        assertThat(DefaultAudioEngineController.reconnectFailureReason(new AudioBackendException("no device.")))
                .as("ruling E112").isEqualTo("the audio driver refused to open it");
        assertThat(DefaultAudioEngineController.reconnectFailureReason(new IllegalStateException()))
                .isEqualTo("the audio driver refused to open it");
        assertThat(DefaultAudioEngineController.reconnectFailureReason(new IllegalStateException("  ")))
                .isEqualTo("the audio driver refused to open it");
    }

    @Test
    void theStallNoticesActionReadsTheTakeOnTheClickAndReconnects() throws Exception {
        engine.setStreamingProvision(new StreamingProvision("SpyLost",
                List.of(new BackendStreamRung(lost, lostDevice))));
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
        controller.setEngineHealthClock(clock::get);
        java.util.concurrent.atomic.AtomicInteger queries = new java.util.concurrent.atomic.AtomicInteger();
        controller.setTakeRescuedQuery(() -> {
            queries.incrementAndGet();
            return false;
        });
        engine.startAudioOutput();
        controller.tickEngineHealthMonitor();
        assertThat(lost.awaitSunkBlocks(8, 5, TimeUnit.SECONDS)).isTrue();
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
        controller.tickEngineHealthMonitor();
        lost.blockAwait = true;
        await(() -> lost.blockedAwaitEntries.get() >= 1, "the render pump is wedged");
        for (int i = 0; i < 30 && controller.engineState() != EngineState.DEVICE_LOST; i++) {
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
            controller.tickEngineHealthMonitor();
        }
        assertThat(notices.notices()).extracting(RecordingNotifications.Notice::actionLabel)
                .containsExactly(Optional.of("Reconnect"));
        lost.blockAwait = false;

        notices.notices().getFirst().action().orElseThrow().run();
        await(() -> controller.engineState() == EngineState.STOPPED, "the action reconnected");

        assertThat(queries.get()).as("the take was read when the action ran").isEqualTo(1);
        assertThat(engine.isStreamOpen()).isTrue();
        await(() -> notices.size() == 2, "the reconnect's notice");
        assertThat(notices.messages()).last().isEqualTo("Interface A is responding again. Press Play to continue.");
    }
}
