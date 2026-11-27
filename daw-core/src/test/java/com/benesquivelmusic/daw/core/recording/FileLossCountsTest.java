package com.benesquivelmusic.daw.core.recording;

import org.junit.jupiter.api.Test;

import static com.benesquivelmusic.daw.core.recording.FrameSpans.UNBOUNDED_END;
import static com.benesquivelmusic.daw.core.recording.FrameSpans.UNBOUNDED_START;
import static org.assertj.core.api.Assertions.assertThat;

/** Runs of dropped blocks and truncation judged as the take's files see them (story 338, rulings E86 and E273). */
class FileLossCountsTest {

    private static final long PUNCH_IN = 1004;
    private static final long PUNCH_OUT = 2004;
    private static final int BLOCK = 8;

    private final FileLossCounts counts = new FileLossCounts(1);

    @Test
    void withNoPunchRegionEveryRunReachesTheRegion() {
        counts.blockReceived(0, UNBOUNDED_START, UNBOUNDED_END, 0, 0);

        assertThat(counts.runReachesTheRegion(0, 8, 3 * BLOCK, 32, false)).isTrue();
        assertThat(counts.runReachesTheRegion(0, 72, 2 * BLOCK, 72, false)).as("a still transport too").isTrue();
        assertThat(counts.reachesTheRegionByItsOwnExtent(0, 8, 3 * BLOCK)).isTrue();
    }

    @Test
    void aRunReachesTheRegionWhenItsFramesToTheNextBlockDo() {
        punchBlock();
        assertThat(counts.runReachesTheRegion(0, 992, 3 * BLOCK, 1016, false)).isTrue();
    }

    @Test
    void aRunWhollyBeforeOrAfterTheRegionDoesNot() {
        punchBlock();
        assertThat(counts.runReachesTheRegion(0, 16, 3 * BLOCK, 40, false)).isFalse();
        assertThat(counts.runReachesTheRegion(0, 2016, 2 * BLOCK, 2040, false)).isFalse();
    }

    @Test
    void aRunAcrossTheLoopWrapIsJudgedOnBothSidesOfIt() {
        counts.blockReceived(0, 1004, 1012, 1000, 1036);
        assertThat(counts.runReachesTheRegion(0, 1032, 2 * BLOCK, 1010, true))
                .as("[1000, 1010) reaches into [1004, 1012)").isTrue();
    }

    @Test
    void aRunALapLongOrLongerPassedEveryFrameOfTheLoopRange() {
        counts.blockReceived(0, 1004, 1012, 1000, 1036);
        assertThat(counts.runReachesTheRegion(0, 1024, 6 * BLOCK, 1000, true))
                .as("[1024, 1036) then a whole lap (E180)").isTrue();
        assertThat(counts.runReachesTheRegion(0, 1012, 5 * BLOCK, 1016, true))
                .as("[1012, 1036) then [1000, 1016) (E180)").isTrue();
    }

    @Test
    void aRunALapLongOrLongerFromLapOnesPreRollIsJudgedOnItsFirstPartToo() {
        counts.blockReceived(0, 970, 978, 1000, 1036);
        assertThat(counts.runReachesTheRegion(0, 972, 10 * BLOCK, 1016, true))
                .as("[972, 1036) reaches into [970, 978) (E180)").isTrue();
        assertThat(counts.runReachesTheRegion(0, 980, 10 * BLOCK, 1024, true))
                .as("[980, 1036) and the loop range miss [970, 978)").isFalse();
    }

    /** Loop [1000, 1036), take start 960: pre-roll frames make up no lap (ruling E182). */
    @Test
    void aRunALapLongFromLapOnesPreRollReachesOnlyByTheFramesTheTransportPassed() {
        counts.blockReceived(0, 1020, 1030, 1000, 1036);
        assertThat(counts.runReachesTheRegion(0, 972, 5 * BLOCK, 1012, false))
                .as("[972, 1012) never reaches [1020, 1030), though the region overlaps the loop range").isFalse();
        assertThat(counts.runReachesTheRegion(0, 972, 8 * BLOCK, 1000, true))
                .as("[972, 1036) reaches into [1020, 1030)").isTrue();
        counts.blockReceived(0, 1004, 1012, 1000, 1036);
        assertThat(counts.runReachesTheRegion(0, 972, 5 * BLOCK, 1012, false))
                .as("[972, 1012) reaches into [1004, 1012)").isTrue();
    }

    @Test
    void aRunShorterThanALapIsStillJudgedToTheNextBlock() {
        counts.blockReceived(0, 1004, 1012, 1000, 1036);
        assertThat(counts.runReachesTheRegion(0, 1020, BLOCK, 1028, false)).as("[1020, 1028) misses [1004, 1012)")
                .isFalse();
    }

    @Test
    void aRunFromTheEndOfABlockAcrossTheWrapIsJudgedFromWhereTheTransportContinued() {
        counts.blockReceived(0, 1000, 1004, 1000, 1036);
        assertThat(counts.runReachesTheRegion(0, 1040, BLOCK, 1012, true))
                .as("[1004, 1012), after the wrap, misses [1000, 1004)").isFalse();
    }

    @Test
    void aRunNoBlockFollowsReachesTheRegionByItsOwnExtent() {
        punchBlock();
        assertThat(counts.reachesTheRegionByItsOwnExtent(0, 1000, 2 * BLOCK)).as("[1000, 1016)").isTrue();
        assertThat(counts.reachesTheRegionByItsOwnExtent(0, 2016, 2 * BLOCK)).as("after punch-out").isFalse();
    }

    @Test
    void aPreRollRunNoBlockFollowsReachesNothingAndOneReachingPunchInReachesIt() {
        counts.blockReceived(0, 2000, 3000, 0, 0);
        assertThat(counts.reachesTheRegionByItsOwnExtent(0, 72, 3 * 256)).as("ruling E104: [72, 840)").isFalse();
        assertThat(counts.reachesTheRegionByItsOwnExtent(0, 1900, 256)).as("ruling E104: [1900, 2156)").isTrue();
    }

    @Test
    void aLoopTakeAddsNothingByItselfAndAnExtentAcrossTheWrapReachesOnTheFarSide() {
        counts.blockReceived(0, PUNCH_IN, PUNCH_OUT, 0, 4000);
        assertThat(counts.reachesTheRegionByItsOwnExtent(0, 2016, 2 * BLOCK)).as("ruling E104").isFalse();
        counts.blockReceived(0, 1004, 1012, 1000, 3016);
        assertThat(counts.reachesTheRegionByItsOwnExtent(0, 3008, 2 * BLOCK))
                .as("[3008, 3016) then [1000, 1008) reaches into [1004, 1012)").isTrue();
    }

    /** Rulings E104, E213 and E256: blocks behind the final sweep, a run no block follows. */
    @Test
    void blocksBehindTheFinalSweepAreJudgedByTheirOwnExtent() {
        FileLossCounts nothingReceived = new FileLossCounts(1);
        assertThat(nothingReceived.reachesTheRegionByItsOwnExtent(0, 4000, 2 * BLOCK))
                .as("a take that received no block knows no region").isTrue();
        counts.blockReceived(0, UNBOUNDED_START, UNBOUNDED_END, 0, 0);
        assertThat(counts.reachesTheRegionByItsOwnExtent(0, 8, 3 * BLOCK)).isTrue();
        punchBlock();
        assertThat(counts.reachesTheRegionByItsOwnExtent(0, 2016, 2 * BLOCK)).isFalse();
        assertThat(counts.reachesTheRegionByItsOwnExtent(0, 988, 2 * BLOCK)).as("ends at punch-in").isFalse();
        assertThat(counts.reachesTheRegionByItsOwnExtent(0, 1000, 2 * BLOCK)).isTrue();
        counts.blockReceived(0, 100, 170, 0, 0);
        assertThat(counts.reachesTheRegionByItsOwnExtent(0, 112, 8 * BLOCK)).as("[112, 176)").isTrue();
        assertThat(counts.reachesTheRegionByItsOwnExtent(0, 176, 2 * BLOCK)).as("[176, 192)").isFalse();
    }

    /** Ruling E285: the next block at the line: either own extent may hold the run. */
    @Test
    void aRunWhoseNextBlockStartsAtItsLineReachesTheRegionByEitherOwnExtent() {
        counts.blockReceived(0, 56, 64, 0, 0);
        assertThat(counts.runReachesTheRegion(0, 72, 2 * BLOCK, 72, false)).as("back from 72: [56, 72)").isTrue();
    }

    /** Ruling E285: the next block at the line, and the region only in the run's frames forward from its line. */
    @Test
    void aRunWhoseNextBlockStartsAtItsLineReachesTheRegionByItsExtentForwardFromTheLine() {
        counts.blockReceived(0, 72, 80, 0, 0);
        assertThat(counts.runReachesTheRegion(0, 72, 2 * BLOCK, 72, false))
                .as("forward from 72: [72, 88); back from 72, [56, 72), misses [72, 80)").isTrue();
    }

    /** Ruling E285: a step back with no loop is bounded by the run's two own extents, not by nothing. */
    @Test
    void aBackwardStepWithNoLoopIsJudgedByTheRunsOwnExtentsOnly() {
        counts.blockReceived(0, 300, 400, 0, 0);
        assertThat(counts.runReachesTheRegion(0, 200, 2 * BLOCK, 64, false)).isFalse();
        counts.blockReceived(0, 50, 60, 0, 0);
        assertThat(counts.runReachesTheRegion(0, 200, 2 * BLOCK, 64, false)).as("back from 64: [48, 64)").isTrue();
    }

    /**
     * Ruling E285: in a loop take, a step back the take counted no wrap for is judged by the run's two own extents,
     * not across a wrap from its line to the loop end.
     */
    @Test
    void aBackwardStepWithNoWrapInALoopTakeIsJudgedByTheRunsOwnExtentsOnly() {
        counts.blockReceived(0, 300, 320, 0, 400);
        assertThat(counts.runReachesTheRegion(0, 200, 2 * BLOCK, 64, false))
                .as("[200, 216) and [48, 64) miss [300, 320); [200, 400) would not").isFalse();
        counts.blockReceived(0, 50, 60, 0, 400);
        assertThat(counts.runReachesTheRegion(0, 200, 2 * BLOCK, 64, false)).as("back from 64: [48, 64)").isTrue();
    }

    @Test
    void countedRunsAddUpAndTheWindowIsTheLastBlocks() {
        assertThat(counts.windowOf(0)).as("no block received").isNull();
        counts.blockReceived(0, PUNCH_IN, PUNCH_OUT, 0, 4000);
        assertThat(counts.windowOf(0)).isEqualTo(new FileLossCounts.Window(PUNCH_IN, PUNCH_OUT, 0, 4000));
        assertThat(counts.windowOf(0).hasPunchRegion()).isTrue();
        counts.countOverflowBlocks(2);
        counts.countOverflowBlocks(3);
        assertThat(counts.overflowBlocks()).isEqualTo(5);
    }

    @Test
    void truncatedFramesCountOnlyInsideTheRegionAndAnEpisodeOnceOneOfItsFramesDoes() {
        counts.truncated(0, true, 8, 4, PUNCH_IN, PUNCH_OUT, 0, 0);
        assertThat(counts.truncationEpisodes()).as("an episode wholly outside").isZero();

        counts.truncated(0, true, 1998, 4, PUNCH_IN, PUNCH_OUT, 0, 0);
        counts.truncated(0, false, 2002, 4, PUNCH_IN, PUNCH_OUT, 0, 0);

        assertThat(counts.truncatedFrames()).as("only the episode's frames before punch-out").isEqualTo(6);
        assertThat(counts.truncationEpisodes()).isEqualTo(1);
    }

    private void punchBlock() {
        counts.blockReceived(0, PUNCH_IN, PUNCH_OUT, 0, 0);
    }
}
