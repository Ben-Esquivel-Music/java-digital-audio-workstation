package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.sdk.transport.PreRollPostRoll;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.sdk.event.EventBus;
import com.benesquivelmusic.daw.sdk.event.TransportEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.LockSupport;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, {@code TransportController.stopForClose()} (design §3.8; the
 * studio engineer's B2 and rulings R1, R2): the close's Stop leaves no
 * post-roll running — one its Stop enters, or one already running, ends on
 * its own FX turn, which publishes {@code TransportEvent.Stopped} — so the
 * close's capture is of a stopped transport and no record follows it; over a
 * transport already stopped it does nothing, never the double-stop rewind.
 * A take that Record started inside a running post-roll's tail gets its
 * ordinary Stop (PR #989 review 5704604621): an audio, a MIDI-only and a
 * mixed take are no longer recording when it returns, and an audio take is
 * being written until its publication; a take still being prepared inside
 * the tail is cancelled by the tail's end, whose cancel text stays. A take's
 * Stop for a close, inside a tail or with none, is its one Stop: the playhead
 * stays at the take's anchor, never rewound to the start by a second Stop;
 * and a running tail with nothing recording is ended by its end alone,
 * with no further Stop request entering a new tail. It answers whether the
 * transport is stopped, out of post-roll, with nothing recording.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class StopForCloseTest {

    /** A post-roll that outlasts an audio take's preparation: eight bars at {@link #SLOW_TEMPO}, 96 s. */
    private static final PreRollPostRoll LONG_POST_ROLL = new PreRollPostRoll(0, PreRollPostRoll.MAX_BARS, true);
    private static final double SLOW_TEMPO = 20.0;
    /** A post-roll that a test can outwait: one bar at {@link #FAST_TEMPO}, 1 s. */
    private static final PreRollPostRoll SHORT_POST_ROLL = new PreRollPostRoll(0, 1, true);
    private static final double FAST_TEMPO = 240.0;
    /** Past the end of a {@link #SHORT_POST_ROLL} tail that was left running: its timer would have fired by then. */
    private static final Duration PAST_A_SHORT_POST_ROLL = Duration.ofSeconds(2);
    /** Where a take's test puts the playhead before Play or Record: a take's Stop rewinds no further back. */
    private static final double PLAYHEAD_BEFORE_THE_TAKE = 8.0;
    /** The status text of the double-stop's rewind ({@code TransportController.stop()}). */
    private static final String RETURNED_TO_START = "Returned to start";

    @TempDir
    Path folder;

    private DawProject project;
    private RecordingInFlightFixture recording;
    private Transport transport;
    private EventBus previous;
    private DefaultEventBus bus;
    private final CaptureThreadHold hold = new CaptureThreadHold();
    private final List<TransportEvent> published = new CopyOnWriteArrayList<>();

    @BeforeEach
    void aSavedProjectWithPostRoll() throws Exception {
        project = new DawProject("Song", AudioFormat.CD_QUALITY);
        project.setMetadata(project.getMetadata().withPath(folder));
        transport = project.getTransport();
        previous = EventBusPublisher.getDefault();
        bus = new DefaultEventBus();
        bus.onPublication(TransportEvent.class, published::add);
        EventBusPublisher.setDefault(bus);
        runOnFx(() -> transport.setPreRollPostRoll(new PreRollPostRoll(0, 2, true)));
    }

    @AfterEach
    void close() throws Exception {
        try {
            hold.release();
            if (recording != null) {
                recording.close();
            }
        } finally {
            EventBusPublisher.setDefault(previous);
            bus.close();
        }
    }

    @Test
    void aRunningPostRollEndsNowWithItsStoppedPublishedOnThisTurn() throws Exception {
        recording = RecordingInFlightFixture.audio(project);
        runOnFx(() -> {
            transport.play();
            recording.controller().stop();
        });
        assertThat(onFx(transport::isInPostRoll)).as("fixture: the user's Stop entered the post-roll").isTrue();
        published.clear();

        boolean stopped = onFx(() -> {
            boolean answer = recording.controller().stopForClose();
            assertThat(stoppedAnnounced()).as("one Stopped, published on the close's own turn").isEqualTo(1);
            return answer;
        });

        assertThat(stopped).isTrue();
        assertThat(onFx(transport::getState)).isEqualTo(TransportState.STOPPED);
        assertThat(onFx(transport::isInPostRoll)).isFalse();
    }

    /**
     * With nothing recording, a running post-roll is ended by its end alone,
     * never by another Stop request, which would enter a new tail first: the
     * close's turn changes the transport's state once, to STOPPED out of
     * post-roll. The changes are recorded on the acting thread, as the
     * transport fires them.
     */
    @Test
    void aRunningPostRollIsEndedWithoutAnotherStopRequest() throws Exception {
        recording = RecordingInFlightFixture.audio(project);
        runOnFx(() -> {
            transport.play();
            recording.controller().stop();
        });
        assertThat(onFx(transport::isInPostRoll)).as("fixture: the user's Stop entered the post-roll").isTrue();
        List<String> stateChanges = new ArrayList<>();

        boolean stopped = onFx(() -> {
            Runnable stopListening = transport.addChangeListener(change -> {
                if (change == Transport.ChangeKind.STATE) {
                    stateChanges.add(transport.getState() + (transport.isInPostRoll() ? " post-roll" : ""));
                }
            });
            try {
                return recording.controller().stopForClose();
            } finally {
                stopListening.run();
            }
        });

        assertThat(stopped).isTrue();
        assertThat(stateChanges).as("one state change on the close's turn: the tail's end, no new tail entered")
                .containsExactly("STOPPED");
    }

    @Test
    void aPlayingTransportStopsAndThePostRollItsStopEntersEndsOnThisTurn() throws Exception {
        recording = RecordingInFlightFixture.audio(project);
        runOnFx(transport::play);

        boolean stopped = onFx(() -> {
            boolean answer = recording.controller().stopForClose();
            assertThat(stoppedAnnounced()).as("one Stopped, published on the close's own turn").isEqualTo(1);
            return answer;
        });

        assertThat(stopped).isTrue();
        assertThat(onFx(transport::getState)).isEqualTo(TransportState.STOPPED);
        assertThat(onFx(transport::isInPostRoll)).as("no post-roll is left running for a close").isFalse();
    }

    @Test
    void aStoppedTransportIsLeftAsItIsNeverTheDoubleStopRewind() throws Exception {
        recording = RecordingInFlightFixture.audio(project);
        runOnFx(() -> transport.setPositionInBeats(8.0));
        assertThat(onFx(transport::isReturnToStartOnStop)).as("fixture: a Stop would rewind").isTrue();

        boolean stopped = onFx(() -> recording.controller().stopForClose());

        assertThat(stopped).isTrue();
        assertThat(onFx(transport::getPositionInBeats)).isEqualTo(8.0);
        assertThat(published).as("nothing transitioned: nothing announced").isEmpty();
    }

    /**
     * An audio take Record started inside a running post-roll's tail (its
     * flag still set, as {@code Transport.record()} leaves it) gets its
     * ordinary Stop: right after the close's Stop, on the acting thread, it
     * no longer records and is being written — its seal requested, its clip
     * not yet published — the transport is stopped and out of post-roll, and
     * one Stopped was published; then the take is published.
     */
    @Test
    void anAudioTakeRecordStartedInsideARunningPostRollGetsItsStopAndIsWrittenUntilItsPublication()
            throws Exception {
        recording = RecordingInFlightFixture.audio(project);
        recordInsideARunningPostRoll(LONG_POST_ROLL, SLOW_TEMPO);
        Track vox = recording.armed();

        boolean stopped = onFx(() -> {
            boolean answer = recording.controller().stopForClose();
            assertTheTakeGotItsStopAndTheTransportStopped();
            assertTheTakesStopWasNotFollowedByASecondStop();
            assertThat(recording.controller().isTakeBeingWritten()).as("its seal requested: the take is being written")
                    .isTrue();
            assertThat(vox.getClips()).as("not published before its capture thread has sealed it").isEmpty();
            return answer;
        });

        assertThat(stopped).as("stopped, out of post-roll, nothing recording").isTrue();
        recording.awaitNothingBeingWritten();
        assertThat(onFx(() -> List.copyOf(vox.getClips()))).as("the take was published").hasSize(1);
        assertThat(stoppedAnnounced()).as("no further Stopped after the close's turn").isEqualTo(1);
    }

    /**
     * A MIDI-only take Record started inside a running post-roll's tail: its
     * Stop enters a new post-roll, which the close's Stop ends on the same
     * turn — the held note published, the transport stopped and out of
     * post-roll, one Stopped — and no tail timer is left to fire later.
     */
    @Test
    void aMidiOnlyTakeRecordStartedInsideARunningPostRollGetsItsStopAndNoNewTailIsLeftRunning() throws Exception {
        recording = RecordingInFlightFixture.midiOnly(project);
        recordInsideARunningPostRoll(SHORT_POST_ROLL, FAST_TEMPO);
        recording.holdANote();
        Track keys = recording.armed();

        boolean stopped = onFx(() -> {
            boolean answer = recording.controller().stopForClose();
            assertTheTakeGotItsStopAndTheTransportStopped();
            assertThat(keys.getMidiClip().size()).as("the take's held note was published").isEqualTo(1);
            return answer;
        });

        assertThat(stopped).as("stopped, out of post-roll, nothing recording").isTrue();
        LockSupport.parkNanos(PAST_A_SHORT_POST_ROLL.toNanos());
        assertThat(onFx(transport::getState)).as("no tail left running to stop it again").isEqualTo(TransportState.STOPPED);
        assertThat(onFx(transport::isInPostRoll)).isFalse();
        assertThat(stoppedAnnounced()).as("no second Stopped from a tail left running").isEqualTo(1);
    }

    /**
     * A mixed take Record started inside a running post-roll's tail: as the
     * audio take — no longer recording, being written, the transport stopped
     * and out of post-roll, one Stopped — and its held note published.
     */
    @Test
    void aMixedTakeRecordStartedInsideARunningPostRollGetsItsStopAndIsWrittenUntilItsPublication() throws Exception {
        recording = RecordingInFlightFixture.mixed(project);
        recordInsideARunningPostRoll(LONG_POST_ROLL, SLOW_TEMPO);
        recording.holdANote();
        Track vox = recording.armed();
        Track keys = onFx(() -> project.getTracks().stream()
                .filter(track -> track.getType() == TrackType.MIDI).findFirst().orElseThrow());

        boolean stopped = onFx(() -> {
            boolean answer = recording.controller().stopForClose();
            assertTheTakeGotItsStopAndTheTransportStopped();
            assertTheTakesStopWasNotFollowedByASecondStop();
            assertThat(recording.controller().isTakeBeingWritten()).as("its seal requested: the take is being written")
                    .isTrue();
            assertThat(keys.getMidiClip().size()).as("the take's held note was published").isEqualTo(1);
            return answer;
        });

        assertThat(stopped).as("stopped, out of post-roll, nothing recording").isTrue();
        recording.awaitNothingBeingWritten();
        assertThat(onFx(() -> List.copyOf(vox.getClips()))).as("the audio take was published").hasSize(1);
        assertThat(stoppedAnnounced()).as("no further Stopped after the close's turn").isEqualTo(1);
    }

    /**
     * An audio take recording with no post-roll at all, the playhead put at
     * {@link #PLAYHEAD_BEFORE_THE_TAKE} before Record: the close's Stop is the
     * take's one Stop — the playhead back at the take's anchor, not at the
     * start, and no "Returned to start" — and the take is then written until
     * its publication.
     */
    @Test
    void anAudioTakeStoppedForACloseOutsideAnyTailIsNotRewoundByASecondStop() throws Exception {
        recording = RecordingInFlightFixture.audio(project);
        runOnFx(() -> {
            transport.setPreRollPostRoll(PreRollPostRoll.DISABLED);
            transport.setPositionInBeats(PLAYHEAD_BEFORE_THE_TAKE);
        });
        assertThat(onFx(transport::isReturnToStartOnStop)).as("fixture: a second Stop would rewind to the start")
                .isTrue();
        recording.start();
        recording.assertStillRecording();
        published.clear();
        Track vox = recording.armed();

        boolean stopped = onFx(() -> {
            boolean answer = recording.controller().stopForClose();
            assertTheTakeGotItsStopAndTheTransportStopped();
            assertTheTakesStopWasNotFollowedByASecondStop();
            assertThat(recording.controller().isTakeBeingWritten()).as("its seal requested: the take is being written")
                    .isTrue();
            return answer;
        });

        assertThat(stopped).as("stopped, out of post-roll, nothing recording").isTrue();
        recording.awaitNothingBeingWritten();
        assertThat(onFx(() -> List.copyOf(vox.getClips()))).as("the take was published").hasSize(1);
    }

    /**
     * An audio take still being prepared inside a running post-roll's tail
     * (its capture thread held while it creates the take's files): the end
     * of the tail cancels it, as it always does, and the cancel's text stays
     * in the status bar — a Stop over the playing tail would enter a new
     * post-roll and leave "Stopped" instead.
     */
    @Test
    void aTakeBeingPreparedInsideARunningPostRollIsCancelledByTheTailsEndAndItsCancelTextStays() throws Exception {
        recording = RecordingInFlightFixture.audio(project);
        runOnFx(() -> hold.installOn(recording.controller()));
        hold.arm();
        runOnFx(() -> {
            enterARunningPostRoll(LONG_POST_ROLL, SLOW_TEMPO);
            recording.startOnFx();
        });
        hold.awaitHolding(Duration.ofSeconds(10));
        published.clear();

        try {
            boolean stopped = onFx(() -> {
                boolean answer = recording.controller().stopForClose();
                assertThat(recording.controller().isPreparingTake()).as("the take's start was cancelled").isFalse();
                assertThat(transport.getState()).isEqualTo(TransportState.STOPPED);
                assertThat(transport.isInPostRoll()).as("the tail ended on this turn").isFalse();
                assertThat(stoppedAnnounced()).as("one Stopped, the tail's end's").isEqualTo(1);
                assertThat(recording.statusText()).as("the cancel's text stays in the status bar")
                        .isEqualTo(TransportController.RECORDING_CANCELLED_MESSAGE);
                return answer;
            });

            assertThat(stopped).as("stopped, out of post-roll, nothing recording").isTrue();
        } finally {
            hold.release();
        }
    }

    /**
     * Post-roll on at {@code tempo}, Play, the user's Stop — its tail runs —
     * and Record inside the tail, on one FX turn; then waits, bounded, until
     * the take records, and asserts that the transport is RECORDING with the
     * post-roll flag still set.
     */
    private void recordInsideARunningPostRoll(PreRollPostRoll postRoll, double tempo) throws Exception {
        runOnFx(() -> {
            enterARunningPostRoll(postRoll, tempo);
            recording.startOnFx();
        });
        recording.assertStillRecording();
        assertThat(onFx(transport::isInPostRoll)).as("fixture: Record inside the tail leaves the post-roll flag set")
                .isTrue();
        published.clear();
    }

    /**
     * Post-roll on at {@code tempo}, the playhead at
     * {@link #PLAYHEAD_BEFORE_THE_TAKE}, Play and the user's Stop: the tail
     * runs. FX thread.
     */
    private void enterARunningPostRoll(PreRollPostRoll postRoll, double tempo) {
        transport.setPreRollPostRoll(postRoll);
        transport.setTempo(tempo);
        transport.setPositionInBeats(PLAYHEAD_BEFORE_THE_TAKE);
        assertThat(transport.isReturnToStartOnStop()).as("fixture: a second Stop would rewind to the start").isTrue();
        transport.play();
        recording.controller().stop();
        assertThat(transport.isInPostRoll()).as("fixture: the user's Stop entered the post-roll").isTrue();
        assertThat(transport.getState()).as("fixture: the tail plays").isEqualTo(TransportState.PLAYING);
    }

    /** Right after the close's Stop: the take no longer records, the transport is stopped and out of post-roll. FX thread. */
    private void assertTheTakeGotItsStopAndTheTransportStopped() {
        assertThat(recording.controller().isRecordingInFlight()).as("the take got its Stop: nothing records").isFalse();
        assertThat(transport.getState()).isEqualTo(TransportState.STOPPED);
        assertThat(transport.isInPostRoll()).as("no post-roll left running").isFalse();
        assertThat(stoppedAnnounced()).as("exactly one Stopped, on the close's own turn").isEqualTo(1);
    }

    /**
     * Right after the close's Stop of a take: the take's Stop left the
     * playhead at the take's anchor, at or past
     * {@link #PLAYHEAD_BEFORE_THE_TAKE}, and no second Stop followed it — the
     * double-stop's rewind to the start, and its status text, never ran. FX
     * thread.
     */
    private void assertTheTakesStopWasNotFollowedByASecondStop() {
        assertThat(transport.getPositionInBeats()).as("at the take's anchor, never rewound to the start")
                .isGreaterThanOrEqualTo(PLAYHEAD_BEFORE_THE_TAKE);
        assertThat(recording.statusText()).as("no double-stop").isNotEqualTo(RETURNED_TO_START);
    }

    /** How many {@code TransportEvent.Stopped} were published since the last clear. */
    private long stoppedAnnounced() {
        return published.stream().filter(TransportEvent.Stopped.class::isInstance).count();
    }
}
