package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sampled transport frames a clock mark is dated from (story 338,
 * amendment A11, rulings E62, E177, E183 and E221): an unlock or a resync at
 * the newest sample at or before the evidence, a relock at the oldest sample
 * at or after it; the take's start-frame for evidence from before the take's
 * reach sample, for unknown evidence until that sample, and for evidence
 * after it that is older than every sample kept; the current frame for
 * unknown evidence after the reach, or with no take started. The history
 * keeps about four seconds and the newest sample before them. The reach
 * sample of a take anchored at a punch-in is its first sample at or past
 * that frame; of any other take, its first sample, whatever frame it reads.
 */
class TakeFrameHistoryTest {

    private static final long SECOND = 1_000_000_000L;
    private static final long MILLI = 1_000_000L;
    private static final long CURRENT = 999_999L;
    private static final boolean PUNCH_IN = true;
    private static final boolean NOT_A_PUNCH_IN = false;

    private final TakeFrameHistory history = new TakeFrameHistory();

    @Test
    void theNewestSampleAtOrBeforeTheEvidenceDatesTheEvent() {
        history.sample(10 * SECOND, 1_000);
        history.sample(11 * SECOND, 2_000);
        history.sample(12 * SECOND, 3_000);

        assertThat(history.frameAt(11 * SECOND, CURRENT)).as("a sample taken at the evidence").isEqualTo(2_000);
        assertThat(history.frameAt(11 * SECOND + SECOND / 2, CURRENT)).as("between two samples: the earlier")
                .isEqualTo(2_000);
        assertThat(history.frameAt(20 * SECOND, CURRENT)).as("after the newest sample: the newest").isEqualTo(3_000);
    }

    @Test
    void evidenceBeforeTheTakeIsItsStartFrameAndUnknownEvidenceAfterTheReachIsTheCurrentFrame() {
        history.takeStartsAt(900, NOT_A_PUNCH_IN);
        history.sample(10 * SECOND, 1_000);
        history.sample(11 * SECOND, 2_000);

        assertThat(history.frameAt(0L, CURRENT)).as("evidence unknown, after the reach").isEqualTo(CURRENT);
        assertThat(history.frameAt(9 * SECOND, CURRENT)).as("before the take's first sample: its start-frame")
                .isEqualTo(900);
        assertThat(new TakeFrameHistory().frameAt(10 * SECOND, CURRENT)).as("no take, no sample").isEqualTo(CURRENT);
    }

    @Test
    void theNewestSampleAtTheSpansEdgeIsKeptAndOlderEvidenceIsTheStartFrame() {
        history.takeStartsAt(1_000, NOT_A_PUNCH_IN);
        history.sample(10 * SECOND, 1_000);
        history.sample(11 * SECOND, 1_500);
        history.sample(12 * SECOND, 2_000);
        assertThat(history.frameAt(11 * SECOND, CURRENT)).as("negative control: inside the span").isEqualTo(1_500);

        history.sample(11 * SECOND + TakeFrameHistory.SPAN_NANOS + 1, 3_000);

        assertThat(history.frameAt(11 * SECOND, CURRENT)).as("older than the span, the newest sample before it")
                .isEqualTo(1_500);
        assertThat(history.frameAt(10 * SECOND, CURRENT))
                .as("at the reach sample, which has given way: older than every sample kept, the start-frame")
                .isEqualTo(1_000);
        assertThat(history.frameAt(12 * SECOND, CURRENT)).isEqualTo(2_000);
    }

    @Test
    void theNewestSampleAtOrBeforeTheSpansEdgeSurvivesEveryEviction() {
        history.sample(10 * SECOND, 1_000);
        history.sample(11 * SECOND, 2_000);
        history.sample(12 * SECOND, 3_000);

        history.sample(20 * SECOND, 4_000);

        assertThat(history.frameAt(13 * SECOND, CURRENT)).as("evidence older than the span: the sample before it")
                .isEqualTo(3_000);
        assertThat(history.frameAt(11 * SECOND, CURRENT)).as("older still: no take, so the frame now")
                .isEqualTo(CURRENT);
    }

    @Test
    void evidenceAfterTheReachOlderThanEveryKeptSampleIsTheStartFrame() {
        history.takeStartsAt(900, NOT_A_PUNCH_IN);
        history.sample(10 * SECOND, 1_000);
        history.sample(11 * SECOND, 1_100);
        history.sample(12 * SECOND, 1_200);
        history.sample(20 * SECOND, 2_000);

        assertThat(history.frameAt(11 * SECOND, CURRENT)).as("after the reach, older than the 12 s sample kept")
                .isEqualTo(900);
        assertThat(history.frameAt(13 * SECOND, CURRENT)).as("negative control: the 12 s sample is kept")
                .isEqualTo(1_200);
    }

    @Test
    void aRelockIsDatedAtTheOldestSampleAtOrAfterItsEvidence() {
        TakeFrameHistory beforeItsReach = new TakeFrameHistory();
        beforeItsReach.takeStartsAt(800, PUNCH_IN);
        beforeItsReach.sample(9 * SECOND, 700);
        history.takeStartsAt(900, NOT_A_PUNCH_IN);
        history.sample(10 * SECOND, 1_000);
        history.sample(11 * SECOND, 1_100);
        history.sample(12 * SECOND, 1_200);

        assertThat(beforeItsReach.relockFrameAt(9 * SECOND, CURRENT)).as("in a pre-roll: the start-frame")
                .hasValue(800);
        assertThat(beforeItsReach.relockFrameAt(0L, CURRENT)).as("unknown, before the reach").hasValue(800);
        assertThat(history.relockFrameAt(9 * SECOND, CURRENT)).as("before the reach sample: the start-frame")
                .hasValue(900);
        assertThat(history.relockFrameAt(0L, CURRENT)).as("unknown, after the reach: the frame now")
                .hasValue(CURRENT);
        assertThat(history.relockFrameAt(10 * SECOND + SECOND / 2, CURRENT)).as("between two samples: the later")
                .hasValue(1_100);
        assertThat(history.relockFrameAt(11 * SECOND, CURRENT)).as("at a sample: that sample").hasValue(1_100);
        assertThat(history.relockFrameAt(12 * SECOND + 1, CURRENT)).as("after the newest sample: none yet")
                .isEqualTo(OptionalLong.empty());
    }

    @Test
    void aPunchTakesPreRollEvidenceIsDatedAtTheStartFrameAndTheReachOnAsSampled() {
        history.takeStartsAt(1_000, PUNCH_IN);
        history.sample(10 * SECOND, 900);
        history.sample(11 * SECOND, 950);

        assertThat(history.frameAt(0L, CURRENT)).as("unknown evidence during the pre-roll").isEqualTo(1_000);
        assertThat(history.frameAt(11 * SECOND, CURRENT)).as("pre-roll evidence before the reach").isEqualTo(1_000);

        history.sample(12 * SECOND, 1_000);
        history.sample(13 * SECOND, 1_050);

        assertThat(history.frameAt(11 * SECOND, CURRENT)).as("evidence at the 950 sample, declared after 1050")
                .isEqualTo(1_000);
        assertThat(history.frameAt(11 * SECOND + SECOND / 2, CURRENT)).as("between the 950 and 1000 samples")
                .isEqualTo(1_000);
        assertThat(history.frameAt(12 * SECOND, CURRENT)).as("at the reach sample").isEqualTo(1_000);
        assertThat(history.frameAt(13 * SECOND, CURRENT)).as("at the 1050 sample").isEqualTo(1_050);
        assertThat(history.frameAt(0L, CURRENT)).as("unknown evidence after the reach: the frame now")
                .isEqualTo(CURRENT);
    }

    @Test
    void aRollingTakesEvidenceBeforeItsFirstSampleIsItsStartFrame() {
        history.takeStartsAt(900, NOT_A_PUNCH_IN);
        history.sample(10 * SECOND, 1_200);

        assertThat(history.frameAt(10 * SECOND - 1, CURRENT)).isEqualTo(900);
        assertThat(history.frameAt(10 * SECOND, CURRENT)).as("at the first sample: its frame").isEqualTo(1_200);
    }

    /**
     * Ruling E183: loop [32, 48) beats below a playhead parked at beat 80,
     * Record with a count-in; the transport wraps into the loop before the
     * take's first sample, and no sample ever reads the start-frame again.
     */
    @Test
    void aTakeNotAnchoredAtAPunchInReachesAtItsFirstSampleWhateverFrameItReads() {
        history.takeStartsAt(80_000, NOT_A_PUNCH_IN);
        history.sample(10 * SECOND, 33_000);
        history.sample(11 * SECOND, 40_000);

        assertThat(history.frameAt(11 * SECOND + 1, 41_000)).as("evidence after the second sample: its frame")
                .isEqualTo(40_000);
        assertThat(history.frameAt(9 * SECOND, 41_000)).as("evidence before the first sample: the start-frame")
                .isEqualTo(80_000);
    }

    /**
     * Ruling E183: Record pressed while the transport rolls in a loop
     * [1000, 1036), the preparation spanning a wrap, so every sample lies
     * below the start-frame.
     */
    @Test
    void aRollingTakeWhoseSamplesAllLieBelowItsStartFrameDatesEvidenceAtTheSampleBeforeIt() {
        history.takeStartsAt(1_030, NOT_A_PUNCH_IN);
        history.sample(10 * SECOND, 1_004);
        history.sample(11 * SECOND, 1_012);
        history.sample(12 * SECOND, 1_020);

        assertThat(history.frameAt(10 * SECOND + SECOND / 2, CURRENT))
                .as("between the first and second samples: the first sample's frame").isEqualTo(1_004);
        assertThat(history.frameAt(10 * SECOND - 1, CURRENT)).as("before the first sample: the start-frame")
                .isEqualTo(1_030);
    }

    @Test
    void afterTheReachALoopTakesSampleBelowTheStartFrameDatesTheEvidence() {
        history.takeStartsAt(1_000, PUNCH_IN);
        history.sample(10 * SECOND, 1_000);
        history.sample(11 * SECOND, 1_030);
        history.sample(12 * SECOND, 995);

        assertThat(history.frameAt(12 * SECOND + SECOND / 2, CURRENT)).as("a later lap, below the start-frame")
                .isEqualTo(995);
    }

    @Test
    void theReachIsKeptAfterItsSampleHasGivenWay() {
        history.takeStartsAt(1_000, PUNCH_IN);
        history.sample(10 * SECOND, 1_000);
        for (int i = 1; i <= 2_000; i++) {
            history.sample(11 * SECOND + i, 2_000 + i);
        }

        assertThat(history.frameAt(9 * SECOND, CURRENT)).as("evidence before the reach, which has given way")
                .isEqualTo(1_000);
        assertThat(history.frameAt(10 * SECOND + SECOND / 2, CURRENT))
                .as("after the reach, in history that has given way: the start-frame").isEqualTo(1_000);
        assertThat(history.frameAt(11 * SECOND + 1_500, CURRENT)).as("negative control: inside the kept history")
                .isEqualTo(3_500);
    }

    @Test
    void aFullHistoryGivesWayOldestFirstAndClearForgetsEverySample() {
        for (int i = 1; i <= 2_000; i++) {
            history.sample(10 * SECOND + i, i);
        }
        assertThat(history.frameAt(10 * SECOND + 2_000, CURRENT)).isEqualTo(2_000);
        assertThat(history.frameAt(10 * SECOND + 1_500, CURRENT)).isEqualTo(1_500);
        assertThat(history.frameAt(10 * SECOND + 500, CURRENT)).as("given way to newer samples").isEqualTo(CURRENT);

        history.clear();

        assertThat(history.frameAt(10 * SECOND + 2_000, CURRENT)).isEqualTo(CURRENT);
    }

    @Test
    void clearForgetsTheTakesStartAndItsReach() {
        history.takeStartsAt(1_000, NOT_A_PUNCH_IN);
        history.sample(10 * SECOND, 1_000);

        history.clear();
        history.sample(20 * SECOND, 5_000);

        assertThat(history.frameAt(9 * SECOND, CURRENT)).as("no take started since the clear").isEqualTo(CURRENT);

        history.clear();
        history.takeStartsAt(6_000, PUNCH_IN);
        history.sample(30 * SECOND, 5_500);

        assertThat(history.frameAt(0L, CURRENT)).as("the next take's own start, not yet reached").isEqualTo(6_000);
        assertThat(history.frameAt(31 * SECOND, CURRENT)).as("before the next take's reach").isEqualTo(6_000);
    }

    @Test
    void theOrderOfReadingsSurvivesTheNanoClockWrapping() {
        history.sample(Long.MAX_VALUE - SECOND, 1_000);
        history.sample(Long.MAX_VALUE - SECOND + 2 * SECOND, 2_000);

        assertThat(history.frameAt(Long.MAX_VALUE, CURRENT)).isEqualTo(1_000);
        assertThat(history.frameAt(Long.MAX_VALUE - SECOND + 2 * SECOND, CURRENT)).isEqualTo(2_000);
    }

    @Test
    void theReachSurvivesTheNanoClockWrapping() {
        history.takeStartsAt(1_000, PUNCH_IN);
        history.sample(Long.MAX_VALUE - SECOND, 900);
        history.sample(Long.MAX_VALUE - SECOND + 2 * SECOND, 1_000);
        history.sample(Long.MAX_VALUE - SECOND + 3 * SECOND, 1_100);

        assertThat(history.frameAt(Long.MAX_VALUE, CURRENT)).as("before the reach, across the wrap").isEqualTo(1_000);
        assertThat(history.frameAt(Long.MAX_VALUE - SECOND + 3 * SECOND, CURRENT)).isEqualTo(1_100);
    }

    /** Ruling E291: given-up samples are kept, each for its own owed date; Clear forgets them. */
    @Test
    void aSampleGivenUpIsKeptWhileItIsTheNewestAtOrBeforeAnOwedDate() {
        long firstDate = 10 * SECOND + 50 * MILLI;
        long secondDate = 11 * SECOND + 230 * MILLI;
        history.keepSamplesFor(() -> new long[] {secondDate, firstDate});
        history.takeStartsAt(900, NOT_A_PUNCH_IN);
        for (int i = 0; i <= 60; i++) {
            history.sample(10 * SECOND + i * 100 * MILLI, 1_000 + i);
        }

        assertThat(history.frameAt(12 * SECOND, CURRENT)).as("premise: inside the span").isEqualTo(1_020);
        assertThat(history.frameAt(firstDate, CURRENT)).as("kept for the first date").isEqualTo(1_000);
        assertThat(history.frameAt(secondDate, CURRENT)).as("its own for the second").isEqualTo(1_012);
        assertThat(history.frameAt(11 * SECOND + 550 * MILLI, CURRENT))
                .as("evidence whose newest sample gave way unkept: the start-frame").isEqualTo(900);

        history.clear();
        history.sample(17 * SECOND, 5_000);

        assertThat(history.frameAt(firstDate, CURRENT)).as("Clear forgets the kept samples").isEqualTo(CURRENT);
    }

    /** Ruling E291: past the history's capacity, too. */
    @Test
    void pastItsCapacityTheHistoryStillKeepsTheSampleAtOrBeforeAnOwedDate() {
        long date = 10 * SECOND + 5 * MILLI + 500;
        history.keepSamplesFor(() -> new long[] {date});
        history.takeStartsAt(900, NOT_A_PUNCH_IN);
        for (int i = 0; i < 2_000; i++) {
            history.sample(10 * SECOND + i * MILLI, 1_000 + i);
        }

        assertThat(history.frameAt(10 * SECOND + 1_500 * MILLI, CURRENT)).as("premise: in the ring").isEqualTo(2_500);
        assertThat(history.frameAt(date, CURRENT)).as("given way at the capacity, kept for the date")
                .isEqualTo(1_005);
        assertThat(history.frameAt(10 * SECOND + 6 * MILLI + 500, CURRENT)).as("not owed").isEqualTo(900);
    }

    /** Ruling E291: a kept sample goes once no date it is the newest at or before is owed. */
    @Test
    void aKeptSampleGoesOnceNoDateItIsTheNewestBeforeIsOwed() {
        long date = 10 * SECOND + 50 * MILLI;
        AtomicReference<long[]> owed = new AtomicReference<>(new long[] {date});
        history.keepSamplesFor(owed::get);
        history.takeStartsAt(900, NOT_A_PUNCH_IN);
        for (int i = 0; i <= 50; i++) {
            history.sample(10 * SECOND + i * 100 * MILLI, 1_000 + i);
        }
        assertThat(history.frameAt(date, CURRENT)).as("premise: kept while owed").isEqualTo(1_000);

        owed.set(new long[0]);
        history.sample(15 * SECOND + 100 * MILLI, 1_051);

        assertThat(history.frameAt(date, CURRENT)).as("handed to the take: it gives way").isEqualTo(900);
    }

    /** Ruling E291: a kept sample behind one still owed goes too, once no date it is the newest before is owed. */
    @Test
    void aKeptSampleBehindOneStillOwedGoesOnceNoDateItIsTheNewestBeforeIsOwed() {
        long stillOwed = 10 * SECOND + 50 * MILLI;
        long handedOver = 10 * SECOND + 250 * MILLI;
        AtomicReference<long[]> owed = new AtomicReference<>(new long[] {stillOwed, handedOver});
        history.keepSamplesFor(owed::get);
        history.takeStartsAt(900, NOT_A_PUNCH_IN);
        for (int i = 0; i <= 50; i++) {
            history.sample(10 * SECOND + i * 100 * MILLI, 1_000 + i);
        }
        assertThat(history.frameAt(handedOver, CURRENT)).as("premise: kept while owed").isEqualTo(1_002);

        owed.set(new long[] {stillOwed});
        history.sample(15 * SECOND + 100 * MILLI, 1_051);

        assertThat(history.frameAt(handedOver, CURRENT)).as("handed to the take: it gives way").isEqualTo(900);
        assertThat(history.frameAt(stillOwed, CURRENT)).as("the older one is still owed").isEqualTo(1_000);
    }
}
