package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.recording.TakeManifest.GapEntry;
import com.benesquivelmusic.daw.core.recording.TakeManifest.InputSilence;
import com.benesquivelmusic.daw.core.recording.TakeManifest.InputStopped;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SealedBy;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.awaitTermination;
import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.awaitWithinTheGuard;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a take's {@code input-silence}, {@code input-stopped} and loss counts
 * say about its file when the take punches or loops (story 338, rulings
 * E75–E77, E79, E86–E90, E172, E176, E179, E186, E188 and E191). Driven
 * through the flush service's rings with blocks whose headers the test
 * writes: where each block starts, whether the input delivered it, rendered
 * it starved, or ended, the punch region and loop window the callback
 * stamped, and the transport's lap the render thread stamped (see
 * {@link #publish}).
 */
@Tag("slow")
class TakeMarksInPunchAndLoopTakesTest {

    private static final int BLOCK = 8;
    private static final AudioFormat MONO = new AudioFormat(48_000.0, 1, 16, BLOCK);
    private static final long GIB = 1L << 30;
    private static final Duration GUARD = Duration.ofSeconds(10);
    private static final long PUNCH_IN = 1004;
    private static final long PUNCH_OUT = 2004;
    private static final long LOOP_START = 1000;
    private static final long LOOP_END = 1036;
    /** Ring slots for a take fed long runs of blocks. */
    private static final int RUN_SLOTS = 1024;

    private enum Kind { DELIVERED, STARVED, ENDED }

    @TempDir
    Path takeDir;

    private final AtomicLong clock = new AtomicLong();
    private final AtomicLong freeBytes = new AtomicLong(10 * GIB);
    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private final ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
    private CaptureFlushService service;
    private CaptureRing ring;
    private CaptureRing siblingRing;
    private TrackCapture capture;
    private TrackCapture siblingCapture;
    private long punchIn = -1;
    private long punchOut = -1;
    private long loopStart;
    private long loopEnd;
    private long outputClockFrame = CaptureRing.NO_OUTPUT_CLOCK_FRAME;
    private String inputName = "Interface";
    /** The render thread's lap count: the transport frame it last saw, and its steps back while looping. */
    private long lastTransportFrame = -1;
    private long transportLaps;

    @AfterEach
    void stopTheFlushThread() {
        if (service != null) {
            service.requestStop(SealedBy.STOP);
            awaitTermination(service);
        }
    }

    @Test
    void aStarvedRunAcrossTheLoopWrapIsTwoLinesThatEndAtTheLoopEndInALoopRecordTake() throws IOException {
        loop();
        start(true, 16);
        feed(1016, Kind.DELIVERED);
        feed(1024, Kind.STARVED);
        feed(1032, Kind.STARVED);
        feed(1004, Kind.STARVED);
        feed(1012, Kind.DELIVERED);

        assertThat(service.starvedInputBlocks()).as("one per block, whatever the lap (E75)").isEqualTo(3);
        assertThat(service.starvedInputFrames()).isEqualTo(3 * BLOCK);
        TakeManifest manifest = stopAndRead();
        assertThat(manifest.inputSilences()).as("cut at the loop end, continued from the loop start (E75)")
                .containsExactly(silence(1024, 12), silence(LOOP_START, 12));
        assertEveryLineEndsByTheLoopEnd(manifest);
    }

    @Test
    void aStarvedRunAcrossTheWrapIsSplitWithLoopRecordOffToo() throws IOException {
        loop();
        start(false, 16);
        feed(1016, Kind.DELIVERED);
        feed(1024, Kind.STARVED);
        feed(1032, Kind.STARVED);
        feed(1004, Kind.STARVED);
        feed(1012, Kind.DELIVERED);

        TakeManifest manifest = stopAndRead();
        assertThat(manifest.inputSilences()).as("a loop take with loop-record off wraps as well (E75)")
                .containsExactly(silence(1024, 12), silence(LOOP_START, 12));
    }

    @Test
    void theTailOfAStopAcrossTheWrapIsSplitAndCounted() throws IOException {
        loop();
        start(false, 16);
        feed(1016, Kind.DELIVERED);
        feed(1024, Kind.STARVED);
        feed(1032, Kind.STARVED);
        feed(1004, Kind.STARVED);

        TakeManifest manifest = stopAndRead();
        assertThat(manifest.inputSilences()).containsExactly(silence(1024, 12), silence(LOOP_START, 12));
        assertThat(service.starvedInputBlocks()).as("an ordinary Stop's tail is counted (E79)").isEqualTo(3);
        assertEveryLineEndsByTheLoopEnd(manifest);
    }

    @Test
    void aLastDeliveredBlockAcrossTheWrapStopsTheInputInsideTheLoopRange() throws IOException {
        loop();
        start(false, 16);
        feed(1024, Kind.DELIVERED);
        feed(1032, Kind.DELIVERED);
        feed(1004, Kind.ENDED);

        TakeManifest manifest = stopAndRead();
        assertThat(manifest.inputStops()).as("the stop moved past the wrap to loop start + overshoot (E89)")
                .containsExactly(new InputStopped(capture.trackId(), 1004, 1004));
    }

    @Test
    void aLastDeliveredBlockTwoLapsLongThatEndsOnTheWrapStopsTheInputAtTheLoopEnd() throws IOException {
        loop(1000, 1004);
        start(false, 16);
        feed(1000, Kind.DELIVERED);
        feed(1000, Kind.ENDED);

        TakeManifest manifest = stopAndRead();
        assertThat(manifest.inputStops()).as("the end of the last lap the block filled (E176)")
                .containsExactly(new InputStopped(capture.trackId(), 1004, 1004));
    }

    @Test
    void aLastDeliveredBlockThatEndsInsideALaterLapStopsTheInputWhereItEnds() throws IOException {
        loop(1000, 1006);
        start(false, 16);
        feed(1000, Kind.DELIVERED);
        feed(1002, Kind.ENDED);

        TakeManifest manifest = stopAndRead();
        assertThat(manifest.inputStops()).containsExactly(new InputStopped(capture.trackId(), 1002, 1002));
    }

    @Test
    void anInputThatNeverDeliveredInALoopTakeStartedBeforeTheLoopStopsAtTheTakesStartFrame() throws IOException {
        loop();
        start(false, 16, false, 960);
        feed(960, Kind.ENDED);

        TakeManifest manifest = stopAndRead();
        assertThat(manifest.inputStops()).as("lap 1's pre-roll is in the file; its first frame is the stop (E115)")
                .containsExactly(new InputStopped(capture.trackId(), 960, 960));
        assertThat(warnings).contains(
                "Track 'Take': input from 'Interface' delivered no audio; the track records silence.");
    }

    @Test
    void anInputBesideTheStreamWhoseStopWindowCrossesTheWrapResumesBeforeItsStop() throws IOException {
        loop();
        start(false, 16, true);
        feedSibling(1008, Kind.DELIVERED);
        feedSibling(1016, Kind.DELIVERED);
        outputClockFrame = 1008;
        feedSibling(1008, Kind.ENDED);

        TakeManifest manifest = stopAndRead();
        InputStopped stop = new InputStopped(siblingCapture.trackId(), 1024, 1008);
        assertThat(manifest.inputStops()).as("resume < stop: the wrap lies between them (E89)").containsExactly(stop);
        assertThat(List.of(stop.stopFrame(), stop.resumeFrame()))
                .allSatisfy(frame -> assertThat(frame).isBetween(LOOP_START, LOOP_END));
        assertThat(TakeManifest.parse(manifest.toText(), "round trip").inputStops())
                .as("read back unchanged, not rejected or reordered").containsExactly(stop);
    }

    @Test
    void aStarvedRunWhollyBeforePunchInIsNeitherWrittenNorCounted() throws IOException {
        punch();
        start(false, 16);
        feed(0, Kind.DELIVERED);
        feed(8, Kind.STARVED);
        feed(16, Kind.STARVED);
        feed(24, Kind.DELIVERED);

        assertThat(service.starvedInputBlocks()).as("frames the take never wrote (E76)").isZero();
        assertThat(stopAndRead().inputSilences()).isEmpty();
    }

    @Test
    void aStarvedRunAcrossPunchInIsMarkedFromPunchInAndItsFirstBlockCountsOnce() throws IOException {
        punch();
        start(false, 16);
        feed(992, Kind.DELIVERED);
        feed(1000, Kind.STARVED);
        feed(1008, Kind.STARVED);
        feed(1016, Kind.DELIVERED);

        assertThat(service.starvedInputBlocks()).as("the block across punch-in is one block (E87)").isEqualTo(2);
        assertThat(service.starvedInputFrames()).as("frames from punch-in only").isEqualTo(4 + BLOCK);
        assertThat(stopAndRead().inputSilences()).containsExactly(silence(PUNCH_IN, 4 + BLOCK));
    }

    @Test
    void aTailAcrossPunchOutAtAStopEndsAtPunchOut() throws IOException {
        punch();
        start(false, 16);
        feed(1984, Kind.DELIVERED);
        feed(1992, Kind.STARVED);
        feed(2000, Kind.STARVED);
        feed(2008, Kind.STARVED);

        TakeManifest manifest = stopAndRead();
        assertThat(manifest.inputSilences()).as("no frame past punch-out (E76)")
                .containsExactly(silence(1992, BLOCK + 4));
        assertThat(service.starvedInputBlocks()).as("counted at an ordinary Stop (E79)").isEqualTo(2);
    }

    @Test
    void anInputThatStallsAndIsDeclaredAfterPunchOutLeavesACompleteFileUnmarked() throws IOException {
        punch();
        start(false, 16);
        feed(1992, Kind.DELIVERED);
        feed(2000, Kind.DELIVERED);
        feed(2008, Kind.STARVED);
        feed(2016, Kind.STARVED);
        feed(2024, Kind.ENDED);
        feed(2032, Kind.ENDED);

        TakeManifest manifest = stopAndRead();
        assertThat(manifest.inputSilences()).as("no tail past punch-out (E77)").isEmpty();
        assertThat(manifest.inputStops()).as("no flag: the file is complete").isEmpty();
        assertThat(manifest.routingFlags()).isEmpty();
        assertThat(service.starvedInputBlocks()).isZero();
        assertThat(warnings).isEmpty();
    }

    @Test
    void anInputThatEndsBeforePunchInIsFlaggedFromPunchInAsHavingDeliveredNoAudio() throws IOException {
        punch();
        start(false, 16);
        feed(976, Kind.DELIVERED);
        feed(984, Kind.DELIVERED);
        feed(992, Kind.ENDED);
        feed(1000, Kind.ENDED);
        feed(1008, Kind.ENDED);

        TakeManifest manifest = stopAndRead();
        assertThat(manifest.inputStops()).as("the first frame the file holds (E77)")
                .containsExactly(new InputStopped(capture.trackId(), PUNCH_IN, PUNCH_IN));
        assertThat(warnings).containsExactly(
                "Track 'Take': input from 'Interface' delivered no audio; the track records silence.");
    }

    @Test
    void anInputWhoseLastDeliveryIsInsideTheRegionButIsDeclaredAfterPunchOutIsFlaggedAtItsDeliveredEnd()
            throws IOException {
        punch();
        start(false, 16);
        feed(1984, Kind.DELIVERED);
        feed(1992, Kind.STARVED);
        feed(2000, Kind.STARVED);
        feed(2008, Kind.STARVED);
        feed(2016, Kind.ENDED);

        TakeManifest manifest = stopAndRead();
        assertThat(manifest.inputStops()).as("flagged at once, outside the region (E77)")
                .containsExactly(new InputStopped(capture.trackId(), 1992, 1992));
        assertThat(manifest.inputSilences()).as("the stop covers the run (E49)").isEmpty();
        assertThat(warnings).containsExactly("Track 'Take': input from 'Interface' stopped delivering audio at"
                + " 0:00.0 into the take; the track records silence from there.");
    }

    @Test
    void anInputThatEndsBeforePunchInAndIsDeclaredAfterPunchOutIsFlaggedFromPunchIn() throws IOException {
        punch(1004, 1020);
        start(false, 16);
        feed(992, Kind.DELIVERED);
        feed(1000, Kind.STARVED);
        feed(1008, Kind.STARVED);
        feed(1016, Kind.STARVED);
        feed(1024, Kind.ENDED);

        TakeManifest manifest = stopAndRead();
        assertThat(manifest.inputStops()).as("the file holds silence from punch-in: flagged there (E77, E15)")
                .containsExactly(new InputStopped(capture.trackId(), 1004, 1004));
        assertThat(manifest.inputSilences()).as("the stop covers the run (E49)").isEmpty();
        assertThat(warnings).containsExactly(
                "Track 'Take': input from 'Interface' delivered no audio; the track records silence.");
    }

    @Test
    void anInputThatEndsInATakeThatStartedAfterPunchOutLeavesNothingToFlag() throws IOException {
        punch(1004, 1020);
        start(false, 16);
        feed(1024, Kind.STARVED);
        feed(1032, Kind.ENDED);

        TakeManifest manifest = stopAndRead();
        assertThat(manifest.inputStops()).as("the file holds no frame").isEmpty();
        assertThat(warnings).isEmpty();
    }

    @Test
    void aLoopPunchTakeThatDeliveredInLapOneIsFlaggedAtTheReEntryAsHavingStopped() throws IOException {
        loopAndPunch(1004, 1020);
        start(false, 16);
        feed(1000, Kind.DELIVERED);
        feed(1008, Kind.DELIVERED);
        feed(1016, Kind.DELIVERED);
        feed(1024, Kind.DELIVERED);
        feed(1032, Kind.ENDED);
        assertThat(warnings).as("stopped after punch-out in lap 1: no flag yet").isEmpty();
        feed(1004, Kind.ENDED);

        TakeManifest manifest = stopAndRead();
        assertThat(manifest.inputStops()).as("re-entry in lap 2: stop at punch-in (E88)")
                .containsExactly(new InputStopped(capture.trackId(), 1004, 1004));
        assertThat(warnings).containsExactly("Track 'Take': input from 'Interface' stopped delivering audio at"
                + " 0:00.0 into the take; the track records silence from there.");
    }

    @Test
    void aLoopPunchTakeThatNeverDeliveredInsideTheRegionSaysItDeliveredNoAudio() throws IOException {
        loopAndPunch(1012, 1020);
        start(false, 16);
        feed(1024, Kind.DELIVERED);
        feed(1032, Kind.ENDED);
        feed(1004, Kind.ENDED);
        feed(1012, Kind.ENDED);

        TakeManifest manifest = stopAndRead();
        assertThat(manifest.inputStops()).containsExactly(new InputStopped(capture.trackId(), 1012, 1012));
        assertThat(warnings).as("no delivered block reached the file in any lap (E88)").containsExactly(
                "Track 'Take': input from 'Interface' delivered no audio; the track records silence.");
    }

    @Test
    void aReEntryAfterTheWrapIsFlaggedAtTheLoopStartWhenThePunchInLiesBeforeIt() throws IOException {
        loopAndPunch(990, 1020);
        start(false, 16);
        feed(985, Kind.DELIVERED);
        feed(993, Kind.DELIVERED);
        feed(1001, Kind.DELIVERED);
        feed(1009, Kind.DELIVERED);
        feed(1017, Kind.DELIVERED);
        feed(1025, Kind.ENDED);
        feed(1033, Kind.ENDED);
        assertThat(warnings).as("stopped after punch-out in lap 1: no flag yet").isEmpty();
        feed(1005, Kind.ENDED);

        TakeManifest manifest = stopAndRead();
        assertThat(manifest.inputStops()).as("lap 2 never plays the punch-in frame; its silence starts at the loop"
                + " start (E179)").containsExactly(new InputStopped(capture.trackId(), LOOP_START, LOOP_START));
    }

    @Test
    void anInputBesideTheStreamThatStoppedInLapOneAndIsClockedInLapTwoResumesAtTheClockFrame() throws IOException {
        siblingStopsInLapOneAndIsClockedInLapTwoAt(1008);

        TakeManifest manifest = stopAndRead();
        assertThat(manifest.inputStops()).as("resume < stop: the wrap lies between them (E179, E89)")
                .containsExactly(new InputStopped(siblingCapture.trackId(), 1015, 1008));
    }

    @Test
    void aLaterLapClockFrameBeforeThatLapsPunchRegionResumesAtTheRegionStart() throws IOException {
        siblingStopsInLapOneAndIsClockedInLapTwoAt(1000);

        TakeManifest manifest = stopAndRead();
        assertThat(manifest.inputStops()).as("raised to lap 2's first frame of the region, not to the stop (E179)")
                .containsExactly(new InputStopped(siblingCapture.trackId(), 1015, 1004));
    }

    @Test
    void aLaterLapClockFrameLaterInTheLoopThanTheStopResumesAboveTheStopThoughTheLoopWrapped() throws IOException {
        siblingStopsInLapOneAndIsClockedInLapTwoAt(1030);

        TakeManifest manifest = stopAndRead();
        assertThat(manifest.inputStops()).as("resume > stop although the wrap lies between them (E191)")
                .containsExactly(new InputStopped(siblingCapture.trackId(), 1015, 1030));
    }

    @Test
    void anInputBesideTheStreamClockedBeforePunchInInTheSameLapResumesAtPunchIn() throws IOException {
        loopAndPunch(1004, 1020);
        start(false, 16, true);
        feed(984, Kind.DELIVERED);
        feedSibling(984, Kind.DELIVERED);
        feed(992, Kind.DELIVERED);
        outputClockFrame = 992;
        feedSibling(992, Kind.ENDED);
        feed(1000, Kind.DELIVERED);
        outputClockFrame = 1000;
        feedSibling(1000, Kind.ENDED);

        TakeManifest manifest = stopAndRead();
        assertThat(manifest.inputStops()).as("the block across punch-in, in the stop's lap: raised to the stop (E179)")
                .containsExactly(new InputStopped(siblingCapture.trackId(), 1004, 1004));
    }

    @Test
    void anInputBesideTheStreamThatStopsAndIsClockedInTheSameLaterLapResumesAtItsStop() throws IOException {
        loopAndPunch(1004, 1020);
        start(false, 16, true, 1012);
        for (long frame = 1012; frame <= 1028; frame += BLOCK) {
            feed(frame, Kind.DELIVERED);
            feedSibling(frame, Kind.DELIVERED);
        }
        feed(LOOP_START, Kind.DELIVERED);
        feedSibling(LOOP_START, Kind.DELIVERED);
        outputClockFrame = LOOP_START;
        feedSibling(1008, Kind.ENDED);

        TakeManifest manifest = stopAndRead();
        assertThat(manifest.inputStops()).as("both in lap 2: no wrap between them (E179)")
                .containsExactly(new InputStopped(siblingCapture.trackId(), 1008, 1008));
    }

    @Test
    void anInputBesideTheStreamThatStopsAndIsClockedInTheSameLapAfterTwoWrapsResumesAtItsStop() throws IOException {
        loopAndPunch(1004, 1020);
        start(false, 16, true, 1012);
        for (long frame : new long[] {1012, 1020, 1028, LOOP_START, 1008, 1016, 1024, 1032, 1004}) {
            feed(frame, Kind.DELIVERED);
            feedSibling(frame, Kind.DELIVERED);
        }
        outputClockFrame = 1004;
        feedSibling(1012, Kind.ENDED);

        TakeManifest manifest = stopAndRead();
        assertThat(manifest.inputStops()).as("the stop and the clock frame both in lap 3: raised to the stop (E179,"
                + " E186)").containsExactly(new InputStopped(siblingCapture.trackId(), 1012, 1012));
    }

    /**
     * The last block the input beside the stream delivers runs from 1032 past
     * the loop end, so its end, 1004, lies in lap 2; the stream wraps to the
     * loop start and its clocked block lies in lap 2 too.
     */
    @Test
    void aLastDeliveryBesideTheStreamThatRunsPastTheLoopEndStopsInTheLapItsEndLiesIn() throws IOException {
        loopAndPunch(990, 1020);
        start(false, 16, true, 1012);
        feed(1012, Kind.DELIVERED);
        feedSibling(1016, Kind.DELIVERED);
        feed(1020, Kind.DELIVERED);
        feedSibling(1024, Kind.DELIVERED);
        feed(1028, Kind.DELIVERED);
        feedSibling(1032, Kind.DELIVERED);
        feed(LOOP_START, Kind.DELIVERED);
        outputClockFrame = LOOP_START;
        feedSibling(1004, Kind.ENDED);

        TakeManifest manifest = stopAndRead();
        assertThat(manifest.inputStops()).as("the stop and the clock frame both in lap 2: raised to the stop (E186,"
                + " E179)").containsExactly(new InputStopped(siblingCapture.trackId(), 1004, 1004));
    }

    @Test
    void aLastDeliveryBesideTheStreamThatEndsOnTheLoopEndStopsInTheLapItCompleted() throws IOException {
        loopAndPunch(PUNCH_IN, PUNCH_OUT);
        start(false, 16, true, 1012);
        for (long frame = 1012; frame <= 1028; frame += BLOCK) {
            feed(frame, Kind.DELIVERED);
            feedSibling(frame, Kind.DELIVERED);
        }
        feed(LOOP_START, Kind.DELIVERED);
        outputClockFrame = LOOP_START;
        feedSibling(LOOP_START, Kind.ENDED);

        TakeManifest manifest = stopAndRead();
        assertThat(manifest.inputStops()).as("the end of lap 1, the resume at lap 2's region start (E176, E179)")
                .containsExactly(new InputStopped(siblingCapture.trackId(), LOOP_END, 1004));
    }

    /**
     * The render thread publishes the clocked block of an input beside the
     * stream before the stream's own block of the same render period, so the
     * flush thread can apply it first.
     */
    @Test
    void aClockedBlockOfTheWrapPeriodAppliedBeforeTheStreamsOwnBlockReadsTheTransportsLap() throws IOException {
        loopAndPunch(1004, 1020);
        start(false, 16, true);
        feed(996, Kind.DELIVERED);
        feedSibling(999, Kind.DELIVERED);
        feed(1004, Kind.DELIVERED);
        feedSibling(1007, Kind.DELIVERED);
        feed(1012, Kind.DELIVERED);
        feed(1020, Kind.DELIVERED);
        feed(1028, Kind.DELIVERED);
        outputClockFrame = LOOP_START;
        feedSibling(1015, Kind.ENDED);
        feed(LOOP_START, Kind.DELIVERED);

        TakeManifest manifest = stopAndRead();
        assertThat(manifest.inputStops()).as("the stop in lap 1, the resume at lap 2's region start (E186, E179)")
                .containsExactly(new InputStopped(siblingCapture.trackId(), 1015, 1004));
    }

    /** One drain pass applies the stream's blocks through the wrap before the lap-1 block beside it. */
    @Test
    void aLapOneDeliveryAppliedAfterTheStreamsWrapInTheSamePassKeepsItsOwnLap() throws IOException {
        loopAndPunch(1004, 1020);
        start(false, 16, true);
        feed(996, Kind.DELIVERED);
        feedSibling(999, Kind.DELIVERED);
        feed(1004, Kind.DELIVERED);
        service.setDrainPaused(true);
        for (long frame : new long[] {1012, 1020, 1028, LOOP_START, 1008}) {
            publish(ring, frame, Kind.DELIVERED, 0);
        }
        publish(siblingRing, 1007, Kind.DELIVERED, 0);
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);
        outputClockFrame = 1008;
        feedSibling(1015, Kind.ENDED);

        TakeManifest manifest = stopAndRead();
        assertThat(manifest.inputStops()).as("the delivered end is lap 1's, the clock frame lap 2's (E186, E179)")
                .containsExactly(new InputStopped(siblingCapture.trackId(), 1015, 1008));
    }

    /** The same pass order, with the flag of a stop in lap 1's pre-roll applied after the stream's wrap. */
    @Test
    void aLapOneFlagAppliedAfterTheStreamsWrapInTheSamePassStopsAtThePunchIn() throws IOException {
        loopAndPunch(990, 1020);
        start(false, 16, true, 980);
        feed(980, Kind.DELIVERED);
        feedSibling(980, Kind.DELIVERED);
        service.setDrainPaused(true);
        for (long frame = 988; frame <= 1028; frame += BLOCK) {
            publish(ring, frame, Kind.DELIVERED, 0);
        }
        outputClockFrame = 1028;
        publish(siblingRing, 988, Kind.ENDED, 0);
        publish(ring, LOOP_START, Kind.DELIVERED, 0);
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);

        TakeManifest manifest = stopAndRead();
        assertThat(manifest.inputStops()).as("flagged in lap 1: the punch-in, where the file's silence begins (E186)")
                .containsExactly(new InputStopped(siblingCapture.trackId(), 990, 1028));
    }

    @Test
    void anInputBesideTheStreamThatEndedInThePreRollIsStoppedAtThePunchInWhileItsOwnFramesAreInLapOne()
            throws IOException {
        loopAndPunch(990, 1020);
        start(false, 16, true, 964);
        feed(964, Kind.DELIVERED);
        feedSibling(964, Kind.DELIVERED);
        feed(972, Kind.DELIVERED);
        feedSibling(972, Kind.DELIVERED);
        for (long frame = 980; frame <= 1028; frame += BLOCK) {
            feed(frame, Kind.DELIVERED);
        }
        feed(LOOP_START, Kind.DELIVERED);
        feed(1008, Kind.DELIVERED);
        outputClockFrame = 1008;
        feedSibling(980, Kind.ENDED);
        assertThat(warnings).as("its own frames have not reached the region: no flag yet").isEmpty();
        feed(1016, Kind.DELIVERED);
        outputClockFrame = 1016;
        feedSibling(988, Kind.ENDED);

        TakeManifest manifest = stopAndRead();
        assertThat(manifest.inputStops()).as("the stop at lap 1's punch-in; the resume at the clock frame, the later"
                + " of it and lap 2's region start (E186)")
                .containsExactly(new InputStopped(siblingCapture.trackId(), 990, 1016));
        assertThat(warnings).containsExactly(
                "Track 'Beside': input from 'Other Interface' delivered no audio; the track records silence.");
    }

    @Test
    void aStopInTheFirstLapOfALoopRecordTakeNamesLapOne() throws IOException {
        loop();
        start(true, 16);
        feed(1000, Kind.DELIVERED);
        feed(1008, Kind.DELIVERED);
        feed(1016, Kind.ENDED);

        stopAndRead();
        assertThat(warnings).containsExactly("Track 'Take': input from 'Interface' stopped delivering audio at"
                + " 0:00.0 into lap 1 of the take; the track records silence from there.");
    }

    @Test
    void aStopInALoopRecordTakeOfAnInputWithNoNameToShowNamesTheLapToo() throws IOException {
        inputName = " ";
        loop();
        start(true, 16);
        feed(1000, Kind.DELIVERED);
        feed(1008, Kind.ENDED);

        stopAndRead();
        assertThat(warnings).containsExactly("Track 'Take': the audio input stopped delivering audio at 0:00.0"
                + " into lap 1 of the take; the track records silence from there.");
    }

    @Test
    void anInputThatNeverDeliveredInALoopRecordTakeNamesNoLap() throws IOException {
        loop();
        start(true, 16);
        feed(1000, Kind.ENDED);

        stopAndRead();
        assertThat(warnings).as("E88's sentence, with no lap (E172)").containsExactly(
                "Track 'Take': input from 'Interface' delivered no audio; the track records silence.");
    }

    @Test
    void aStopTwoSecondsIntoLapThreeIsWrittenAsThatLapsPosition() throws IOException {
        long loopEnd = 96_000 + BLOCK;
        loop(0, loopEnd);
        start(true, RUN_SLOTS, false, loopEnd - 2 * BLOCK);
        feedRun(loopEnd - 2 * BLOCK, 2, Kind.DELIVERED);
        feedRun(0, (int) (loopEnd / BLOCK), Kind.DELIVERED);
        feedRun(0, 96_000 / BLOCK, Kind.DELIVERED);
        feed(96_000, Kind.ENDED);

        stopAndRead();
        assertThat(warnings).as("the frame's position in lap 3's own file (E172)").containsExactly(
                "Track 'Take': input from 'Interface' stopped delivering audio at 0:02.0 into lap 3 of the take;"
                        + " the track records silence from there.");
    }

    @Test
    void aStopDeclaredAfterTheNextWrapNamesTheLapOfTheLastDeliveredBlock() throws IOException {
        loop(0, 9_600);
        start(true, RUN_SLOTS);
        feedRun(0, 1_200, Kind.DELIVERED);
        feedRun(0, 600, Kind.DELIVERED);
        feedRun(4_800, 600, Kind.STARVED);
        feedRun(0, 100, Kind.STARVED);
        feed(800, Kind.ENDED);

        stopAndRead();
        assertThat(warnings).as("lap 2 and its position, not the lap the stop was declared in (E172)")
                .containsExactly("Track 'Take': input from 'Interface' stopped delivering audio at 0:00.1 into lap 2"
                        + " of the take; the track records silence from there.");
    }

    @Test
    void aLastDeliveredBlockThatEndsAtTheLoopEndNamesTheLapItCompleted() throws IOException {
        loop(0, 4_800);
        start(true, RUN_SLOTS);
        feedRun(0, 600, Kind.DELIVERED);
        feedRun(0, 600, Kind.DELIVERED);
        feed(0, Kind.ENDED);

        TakeManifest manifest = stopAndRead();
        assertThat(manifest.inputStops()).containsExactly(new InputStopped(capture.trackId(), 4_800, 4_800));
        assertThat(warnings).as("lap 2's full length, into lap 2 (E172, E176)").containsExactly(
                "Track 'Take': input from 'Interface' stopped delivering audio at 0:00.1 into lap 2 of the take;"
                        + " the track records silence from there.");
    }

    @Test
    void aLastDeliveredBlockAcrossTheLoopEndIsNamedInItsLapWhileTheStopLiesWhereTheNextLapBegins()
            throws IOException {
        loop(0, 4_804);
        start(true, RUN_SLOTS);
        feedRun(0, 601, Kind.DELIVERED);
        feed(4, Kind.ENDED);

        TakeManifest manifest = stopAndRead();
        assertThat(manifest.inputStops()).as("the frame after the block, in lap 2 (E188)")
                .containsExactly(new InputStopped(capture.trackId(), 4, 4));
        assertThat(warnings).as("the lap whose file holds the block: the same instant (E188)").containsExactly(
                "Track 'Take': input from 'Interface' stopped delivering audio at 0:00.1 into lap 1 of the take;"
                        + " the track records silence from there.");
    }

    @Test
    void aLoopPunchReEntryInALoopRecordTakeNamesTheLapTheInputLastDeliveredIn() throws IOException {
        loopAndPunch(1004, 1020);
        start(true, 16);
        feed(1000, Kind.DELIVERED);
        feed(1008, Kind.DELIVERED);
        feed(1016, Kind.DELIVERED);
        feed(1024, Kind.DELIVERED);
        feed(1032, Kind.ENDED);
        feed(1004, Kind.ENDED);

        TakeManifest manifest = stopAndRead();
        assertThat(manifest.inputStops()).containsExactly(new InputStopped(capture.trackId(), 1004, 1004));
        assertThat(warnings).as("declared in lap 2; the last delivered frame is lap 1's (E172)").containsExactly(
                "Track 'Take': input from 'Interface' stopped delivering audio at 0:00.0 into lap 1 of the take;"
                        + " the track records silence from there.");
    }

    @Test
    void aStopAfterAStarvedRunCountsItsTail() throws IOException {
        start(false, 16);
        feed(0, Kind.DELIVERED);
        feed(8, Kind.STARVED);
        feed(16, Kind.STARVED);

        TakeManifest manifest = stopAndRead();
        assertThat(manifest.sealedBy()).contains(SealedBy.STOP);
        assertThat(manifest.inputSilences()).containsExactly(silence(8, 2 * BLOCK));
        assertThat(service.starvedInputBlocks()).as("a closed run at an ordinary seal (E79)").isEqualTo(2);
        assertThat(service.starvedInputFrames()).isEqualTo(2 * BLOCK);
    }

    @Test
    void aDiskExhaustionSealCountsItsTailUpToTheLastFrameWritten() throws IOException {
        start(false, 16);
        feed(0, Kind.DELIVERED);
        feed(8, Kind.STARVED);
        feed(16, Kind.STARVED);
        freeBytes.set(0);
        feed(24, Kind.STARVED);

        TakeManifest manifest = stopAndRead();
        assertThat(manifest.sealedBy()).contains(SealedBy.DISK_EXHAUSTION);
        long written = manifest.segmentsFor(capture.trackId()).stream().mapToLong(TakeManifest.SegmentEntry::frames)
                .sum();
        assertThat(manifest.inputSilences()).as("the tail, up to the last block the file received (E90)")
                .containsExactly(silence(8, 2 * BLOCK));
        assertThat(written).as("the file holds every frame the line marks").isEqualTo(3 * BLOCK);
        assertThat(service.starvedInputBlocks()).as("counted: no device loss (E90)").isEqualTo(2);
    }

    @Test
    void aWriteFailureSealsWithATailThatStopsBeforeTheBlockThatFailed() throws IOException {
        start(false, 16);
        feed(0, Kind.DELIVERED);
        feed(8, Kind.STARVED);
        journal.failNextWrites(1);
        feed(16, Kind.STARVED);

        TakeManifest manifest = stopAndRead();
        assertThat(manifest.sealedBy()).contains(SealedBy.WRITE_FAILURE);
        assertThat(manifest.inputSilences()).as("the block whose write failed is not in the file (E90)")
                .containsExactly(silence(8, BLOCK));
        assertThat(service.starvedInputBlocks()).isEqualTo(1);
    }

    @Test
    void overflowGapsCountOnlyWhenTheirExtentReachesIntoThePunchRegion() throws IOException {
        punch();
        start(false, CaptureRing.MIN_SLOTS);
        overflowGap(0, 100);
        assertThat(service.fileOverflowBlocks()).as("a gap wholly in the pre-roll").isZero();
        overflowGap(936, 1016);
        assertThat(service.fileOverflowBlocks()).as("starts before punch-in and runs into the region: whole (E86)")
                .isEqualTo(3);
        overflowGap(2008, 2100);
        assertThat(service.fileOverflowBlocks()).as("a gap wholly after punch-out").isEqualTo(3);
        overflowGap(1900, -1);
        assertThat(service.overflowCount()).as("the ring counts every drop").isEqualTo(12);

        TakeManifest manifest = stopAndRead();
        assertThat(service.fileOverflowBlocks()).as("a gap no block follows, from before punch-out: the seal counts it")
                .isEqualTo(6);
        assertThat(manifest.gaps()).as("gap= lines are written as today")
                .containsExactly(new GapEntry(GapEntry.ALL_TRACKS, 64, 3), new GapEntry(GapEntry.ALL_TRACKS, 1000, 3),
                        new GapEntry(GapEntry.ALL_TRACKS, 2072, 3), new GapEntry(GapEntry.ALL_TRACKS, 1964, 3));
    }

    @Test
    void truncatedFramesCountOnlyInsideThePunchRegion() throws IOException {
        punch();
        start(false, 16);
        feedTruncated(0, 4);
        feed(8, Kind.DELIVERED);
        assertThat(service.fileTruncationEpisodes()).as("an episode wholly outside the region").isZero();
        feedTruncated(1994, 4);
        feedTruncated(2002, 4);
        feed(2010, Kind.DELIVERED);

        assertThat(service.fileTruncatedFrames()).as("only the episode's frames before punch-out (E86)")
                .isEqualTo(2);
        assertThat(service.fileTruncationEpisodes()).isEqualTo(1);
        assertThat(service.truncationEpisodes()).as("the manifest's episodes are all of them").isEqualTo(2);
        TakeManifest manifest = stopAndRead();
        assertThat(manifest.truncatedFrames()).as("truncated-frames is written as today").isEqualTo(12);
    }

    private void punch() {
        punch(PUNCH_IN, PUNCH_OUT);
    }

    private void punch(long in, long out) {
        punchIn = in;
        punchOut = out;
    }

    private void loop() {
        loop(LOOP_START, LOOP_END);
    }

    private void loop(long start, long end) {
        loopStart = start;
        loopEnd = end;
    }

    /**
     * Punch [1004, 1020) in the loop [1000, 1036): the input beside the stream
     * delivers up to 1015 in lap 1 and stalls while the stream plays on through
     * the wrap. Its first output-clocked block is stamped from its own frame
     * cursor, still at 1015, and clocked at {@code clockFrame} in lap 2.
     */
    private void siblingStopsInLapOneAndIsClockedInLapTwoAt(long clockFrame) {
        loopAndPunch(1004, 1020);
        start(false, 16, true);
        feed(996, Kind.DELIVERED);
        feedSibling(999, Kind.DELIVERED);
        feed(1004, Kind.DELIVERED);
        feedSibling(1007, Kind.DELIVERED);
        feed(1012, Kind.DELIVERED);
        feed(1020, Kind.DELIVERED);
        feed(1028, Kind.DELIVERED);
        for (long frame = LOOP_START; frame <= clockFrame; frame += BLOCK) {
            feed(frame, Kind.DELIVERED);
        }
        outputClockFrame = clockFrame;
        feedSibling(1015, Kind.ENDED);
    }

    private void loopAndPunch(long in, long out) {
        loop();
        punchIn = in;
        punchOut = out;
    }

    private void start(boolean loopRecord, int slots) {
        start(loopRecord, slots, false);
    }

    private void start(boolean loopRecord, int slots, boolean withSibling) {
        start(loopRecord, slots, withSibling, 0L);
    }

    private void start(boolean loopRecord, int slots, boolean withSibling, long recordingStartFrame) {
        CaptureFlushService.TakeConfig config = new CaptureFlushService.TakeConfig(takeDir, MONO, 120.0,
                recordingStartFrame / 24_000.0, recordingStartFrame, null, loopRecord, Duration.ofSeconds(5),
                Instant.parse("2026-11-16T10:00:00Z"));
        DiskHeadroomWatch watch = new DiskHeadroomWatch(takeDir, freeBytes::get, GIB, 64L << 20,
                Duration.ZERO, clock::get, warnings::add);
        ring = new CaptureRing(BLOCK, 1, 1, slots);
        capture = capture(new Track("Take", TrackType.AUDIO), 0, inputName);
        List<TrackCapture> captures = new ArrayList<>(List.of(capture));
        if (withSibling) {
            siblingRing = new CaptureRing(BLOCK, 1, 1, slots);
            siblingCapture = capture(new Track("Beside", TrackType.AUDIO), 1, "Other Interface");
            captures.add(siblingCapture);
        }
        service = new CaptureFlushService(ring, config, captures, watch, warnings::add, clock::get);
        if (withSibling) {
            service.setInputRings(new CaptureRing[]{ring, siblingRing});
        }
        service.start();
        awaitWithinTheGuard(service.readiness(), "the take's readiness");
    }

    private TrackCapture capture(Track track, int source, String device) {
        TrackCapture made = new TrackCapture(track, new InputRouting(0, 1), -1, 1, BLOCK, 0,
                MONO.sampleRate(), 120.0, takeDir.resolve(track.getId()), (t, dir) -> {
                    RecordingSession session = new RecordingSession(MONO, dir);
                    session.setChannelOpener(journal.opener(SegmentWriter.CREATE_NEW_CHANNEL));
                    return session;
                });
        made.setInputSource(source, device);
        return made;
    }

    private void feed(long start, Kind kind) {
        publish(ring, start, kind, 0);
        service.awaitFlushed(GUARD);
    }

    private void feedSibling(long start, Kind kind) {
        publish(siblingRing, start, kind, 0);
        service.awaitFlushed(GUARD);
    }

    /** {@code blocks} consecutive blocks of one kind from {@code start}, a ring's worth at a time. */
    private void feedRun(long start, int blocks, Kind kind) {
        int batch = ring.capacity();
        for (int first = 0; first < blocks; first += batch) {
            for (int block = first; block < Math.min(blocks, first + batch); block++) {
                publish(ring, start + (long) block * BLOCK, kind, 0);
            }
            service.awaitFlushed(GUARD);
        }
    }

    private void feedTruncated(long start, int truncated) {
        publish(ring, start, Kind.DELIVERED, truncated);
        ring.noteTruncatedFrames(truncated);
        service.awaitFlushed(GUARD);
    }

    /**
     * A full ring of blocks from {@code start} while the drain is held, then
     * three dropped, so the gap starts after the last of them; the next block
     * at {@code next}, or none when it is negative.
     */
    private void overflowGap(long start, long next) {
        service.setDrainPaused(true);
        for (int block = 0; block < CaptureRing.MIN_SLOTS; block++) {
            publish(ring, start + (long) block * BLOCK, Kind.DELIVERED, 0);
        }
        for (int dropped = 0; dropped < 3; dropped++) {
            assertThat(ring.claim(CaptureRing.DELIVERED)).as("fixture: the ring is full").isNull();
        }
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);
        if (next >= 0) {
            feed(next, Kind.DELIVERED);
        }
    }

    /**
     * Writes one block's header and samples and publishes it. The stream's
     * own blocks, and an output-clocked block of the input beside it, carry
     * the transport's lap the way the render thread stamps it: counted once
     * per step back of the transport's frame while the loop is on, in the
     * order the blocks are published. The input's own delivered blocks carry
     * none.
     */
    private void publish(CaptureRing target, long start, Kind kind, int truncated) {
        boolean clocked = target == siblingRing && kind == Kind.ENDED
                && outputClockFrame != CaptureRing.NO_OUTPUT_CLOCK_FRAME;
        boolean stamped = target == ring || clocked;
        if (stamped) {
            long transportFrame = clocked ? outputClockFrame : start;
            if (loopEnd > loopStart && lastTransportFrame >= 0 && transportFrame < lastTransportFrame) {
                transportLaps++;
            }
            lastTransportFrame = transportFrame;
        }
        CaptureRing.Slot slot = target.claim(CaptureRing.DELIVERED);
        assertThat(slot).as("fixture: the ring has room").isNotNull();
        slot.setNumFrames(BLOCK);
        slot.setTruncatedFrames(truncated);
        slot.setStartFrame(start);
        slot.setBeatPosition(start / 24_000.0);
        slot.setPunchEnabled(punchIn >= 0);
        slot.setPunchStartFrames(Math.max(0, punchIn));
        slot.setPunchEndFrames(Math.max(0, punchOut));
        slot.setLoopEnabled(loopEnd > loopStart);
        slot.setLoopStartFrame(loopStart);
        slot.setLoopEndFrame(loopEnd);
        float[][] rows = new float[1][BLOCK];
        switch (kind) {
            case DELIVERED -> {
                Arrays.fill(rows[0], .5f);
                slot.copySource(0, rows, 1, BLOCK);
            }
            case STARVED -> {
                slot.copySource(0, rows, 1, BLOCK);
                slot.setInputStarved(true);
            }
            case ENDED -> {
                slot.copySource(0, new float[0][], 0, BLOCK);
                slot.setOutputClockFrame(outputClockFrame);
            }
        }
        if (stamped) {
            slot.setTransportLap(transportLaps);
        }
        target.publish();
        service.signal();
    }

    private InputSilence silence(long start, long frames) {
        return new InputSilence(capture.trackId(), start, frames);
    }

    private static void assertEveryLineEndsByTheLoopEnd(TakeManifest manifest) {
        assertThat(manifest.inputSilences())
                .allSatisfy(line -> assertThat(line.startFrame() + line.frames()).isLessThanOrEqualTo(LOOP_END));
    }

    private TakeManifest stopAndRead() throws IOException {
        service.requestStop(SealedBy.STOP);
        awaitTermination(service);
        return read();
    }

    private TakeManifest read() throws IOException {
        return TakeManifest.read(TakeManifest.manifestPath(takeDir));
    }
}
