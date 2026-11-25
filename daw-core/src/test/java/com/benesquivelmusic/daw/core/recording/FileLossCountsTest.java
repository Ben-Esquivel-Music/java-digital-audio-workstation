package com.benesquivelmusic.daw.core.recording;

import org.junit.jupiter.api.Test;

import static com.benesquivelmusic.daw.core.recording.FrameSpans.UNBOUNDED_END;
import static com.benesquivelmusic.daw.core.recording.FrameSpans.UNBOUNDED_START;
import static org.assertj.core.api.Assertions.assertThat;

/** Ring-overflow and truncation losses counted as the take's files see them (story 338, ruling E86). */
class FileLossCountsTest {

    private static final long PUNCH_IN = 1004;
    private static final long PUNCH_OUT = 2004;
    private static final int BLOCK = 8;

    private final FileLossCounts counts = new FileLossCounts(1);

    @Test
    void withNoPunchRegionEveryGapCountsAtOnce() {
        counts.blockReceived(0, 0, UNBOUNDED_START, UNBOUNDED_END, 0, 0);
        counts.gapNoticed(0, 8, 3, BLOCK);

        assertThat(counts.overflowBlocks()).isEqualTo(3);
    }

    @Test
    void aGapCountsWholeWhenItsExtentToTheNextBlockReachesIntoTheRegion() {
        punchBlock(976);
        counts.gapNoticed(0, 992, 3, BLOCK);
        assertThat(counts.overflowBlocks()).as("judged when the next block closes it").isZero();
        punchBlock(1016);

        assertThat(counts.overflowBlocks()).isEqualTo(3);
    }

    @Test
    void aGapWhollyBeforeOrAfterTheRegionCountsNothing() {
        punchBlock(0);
        counts.gapNoticed(0, 16, 3, BLOCK);
        punchBlock(40);
        punchBlock(2008);
        counts.gapNoticed(0, 2016, 2, BLOCK);
        punchBlock(2040);

        assertThat(counts.overflowBlocks()).isZero();
    }

    @Test
    void aGapAcrossTheLoopWrapIsJudgedOnBothSidesOfIt() {
        counts.blockReceived(0, 1024, 1004, 1012, 1000, 1036);
        counts.gapNoticed(0, 1032, 2, BLOCK);
        counts.blockReceived(0, 1010, 1004, 1012, 1000, 1036);

        assertThat(counts.overflowBlocks()).as("[1000, 1010) reaches into [1004, 1012)").isEqualTo(2);
    }

    @Test
    void aGapALapLongOrLongerPassedEveryFrameOfTheLoopRange() {
        FileLossCounts endsOnTheWrap = new FileLossCounts(1);
        endsOnTheWrap.blockReceived(0, 1016, 1004, 1012, 1000, 1036);
        endsOnTheWrap.gapNoticed(0, 1024, 6, BLOCK);
        endsOnTheWrap.blockReceived(0, 1000, 1004, 1012, 1000, 1036);
        assertThat(endsOnTheWrap.overflowBlocks()).as("[1024, 1036) then a whole lap (E180)").isEqualTo(6);

        FileLossCounts nextBlockAfterItsStart = new FileLossCounts(1);
        nextBlockAfterItsStart.blockReceived(0, 1004, 1004, 1012, 1000, 1036);
        nextBlockAfterItsStart.gapNoticed(0, 1012, 5, BLOCK);
        nextBlockAfterItsStart.blockReceived(0, 1016, 1004, 1012, 1000, 1036);
        assertThat(nextBlockAfterItsStart.overflowBlocks()).as("[1012, 1036) then [1000, 1016) (E180)").isEqualTo(5);
    }

    @Test
    void aGapALapLongOrLongerFromLapOnesPreRollIsJudgedOnItsFirstPartToo() {
        FileLossCounts reachesTheRegion = new FileLossCounts(1);
        reachesTheRegion.blockReceived(0, 964, 970, 978, 1000, 1036);
        reachesTheRegion.gapNoticed(0, 972, 10, BLOCK);
        reachesTheRegion.blockReceived(0, 1016, 970, 978, 1000, 1036);
        assertThat(reachesTheRegion.overflowBlocks()).as("[972, 1036) reaches into [970, 978) (E180)").isEqualTo(10);

        FileLossCounts afterTheRegion = new FileLossCounts(1);
        afterTheRegion.blockReceived(0, 972, 970, 978, 1000, 1036);
        afterTheRegion.gapNoticed(0, 980, 10, BLOCK);
        afterTheRegion.blockReceived(0, 1024, 970, 978, 1000, 1036);
        assertThat(afterTheRegion.overflowBlocks()).as("[980, 1036) and the loop range miss [970, 978)").isZero();
    }

    /** Loop [1000, 1036), take start 960: pre-roll frames make up no lap (ruling E182). */
    @Test
    void aGapALapLongFromLapOnesPreRollCountsOnlyTheFramesTheTransportPassed() {
        FileLossCounts endsBeforeTheRegion = new FileLossCounts(1);
        endsBeforeTheRegion.blockReceived(0, 964, 1020, 1030, 1000, 1036);
        endsBeforeTheRegion.gapNoticed(0, 972, 5, BLOCK);
        endsBeforeTheRegion.blockReceived(0, 1012, 1020, 1030, 1000, 1036);
        assertThat(endsBeforeTheRegion.overflowBlocks())
                .as("[972, 1012) never reaches [1020, 1030), though the region overlaps the loop range").isZero();

        FileLossCounts reachesTheRegion = new FileLossCounts(1);
        reachesTheRegion.blockReceived(0, 964, 1004, 1012, 1000, 1036);
        reachesTheRegion.gapNoticed(0, 972, 5, BLOCK);
        reachesTheRegion.blockReceived(0, 1012, 1004, 1012, 1000, 1036);
        assertThat(reachesTheRegion.overflowBlocks()).as("[972, 1012) reaches into [1004, 1012)").isEqualTo(5);

        FileLossCounts endsOnTheLoopEnd = new FileLossCounts(1);
        endsOnTheLoopEnd.blockReceived(0, 964, 1020, 1030, 1000, 1036);
        endsOnTheLoopEnd.gapNoticed(0, 972, 8, BLOCK);
        endsOnTheLoopEnd.blockReceived(0, 1000, 1020, 1030, 1000, 1036);
        assertThat(endsOnTheLoopEnd.overflowBlocks()).as("[972, 1036) reaches into [1020, 1030)").isEqualTo(8);
    }

    @Test
    void aGapShorterThanALapIsStillJudgedToTheNextBlock() {
        counts.blockReceived(0, 1012, 1004, 1012, 1000, 1036);
        counts.gapNoticed(0, 1020, 1, BLOCK);
        counts.blockReceived(0, 1028, 1004, 1012, 1000, 1036);

        assertThat(counts.overflowBlocks()).as("[1020, 1028) misses [1004, 1012)").isZero();
    }

    @Test
    void aGapFromTheEndOfABlockAcrossTheWrapIsJudgedFromWhereTheTransportContinued() {
        counts.blockReceived(0, 1032, 1000, 1004, 1000, 1036);
        counts.gapNoticed(0, 1040, 1, BLOCK);
        counts.blockReceived(0, 1012, 1000, 1004, 1000, 1036);

        assertThat(counts.overflowBlocks()).as("[1004, 1012), after the wrap, misses [1000, 1004)").isZero();
    }

    @Test
    void aGapNoBlockFollowsCountsWhenItsOwnExtentReachesIntoTheRegion() {
        punchBlock(992);
        counts.gapNoticed(0, 1000, 2, BLOCK);
        counts.takeSealed();
        assertThat(counts.overflowBlocks()).as("[1000, 1016) reaches into the region").isEqualTo(2);

        FileLossCounts afterPunchOut = new FileLossCounts(1);
        afterPunchOut.blockReceived(0, 2008, PUNCH_IN, PUNCH_OUT, 0, 0);
        afterPunchOut.gapNoticed(0, 2016, 2, BLOCK);
        afterPunchOut.takeSealed();
        assertThat(afterPunchOut.overflowBlocks()).isZero();
    }

    @Test
    void aPreRollGapNoBlockFollowsCountsNothingAndOneReachingPunchInCountsWhole() {
        FileLossCounts preRoll = new FileLossCounts(1);
        preRoll.blockReceived(0, 0, 2000, 3000, 0, 0);
        preRoll.gapNoticed(0, 72, 3, 256);
        preRoll.takeSealed();
        assertThat(preRoll.overflowBlocks()).as("ruling E104: [72, 840) lies before punch-in [2000, 3000)")
                .isZero();

        FileLossCounts intoTheRegion = new FileLossCounts(1);
        intoTheRegion.blockReceived(0, 1644, 2000, 3000, 0, 0);
        intoTheRegion.gapNoticed(0, 1900, 1, 256);
        intoTheRegion.takeSealed();
        assertThat(intoTheRegion.overflowBlocks()).as("ruling E104: [1900, 2156) reaches into [2000, 3000)")
                .isEqualTo(1);
    }

    @Test
    void aLoopTakeAddsNothingByItselfAndAnExtentAcrossTheWrapCountsOnTheFarSide() {
        FileLossCounts looped = new FileLossCounts(1);
        looped.blockReceived(0, 2008, PUNCH_IN, PUNCH_OUT, 0, 4000);
        looped.gapNoticed(0, 2016, 2, BLOCK);
        looped.takeSealed();
        assertThat(looped.overflowBlocks()).as("ruling E104: that the take looped adds nothing").isZero();

        FileLossCounts acrossTheWrap = new FileLossCounts(1);
        acrossTheWrap.blockReceived(0, 3000, 1004, 1012, 1000, 3016);
        acrossTheWrap.gapNoticed(0, 3008, 2, BLOCK);
        acrossTheWrap.takeSealed();
        assertThat(acrossTheWrap.overflowBlocks()).as("[3008, 3016) then [1000, 1008) reaches into [1004, 1012)")
                .isEqualTo(2);
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

    private void punchBlock(long start) {
        counts.blockReceived(0, start, PUNCH_IN, PUNCH_OUT, 0, 0);
    }
}
