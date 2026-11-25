package com.benesquivelmusic.daw.core.recording;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The frame arithmetic behind the take's marks: overlap with the punch region and the loop wrap. */
class FrameSpansTest {

    @Test
    void theFramesInsideTheRegionAreTheOverlapOfTwoHalfOpenSpans() {
        assertThat(FrameSpans.framesInside(0, 8, 4, 100)).isEqualTo(4);
        assertThat(FrameSpans.framesInside(96, 104, 4, 100)).isEqualTo(4);
        assertThat(FrameSpans.framesInside(0, 4, 4, 100)).as("touching at punch-in is outside").isZero();
        assertThat(FrameSpans.framesInside(100, 108, 4, 100)).as("punch-out is outside").isZero();
        assertThat(FrameSpans.framesInside(10, 20, FrameSpans.UNBOUNDED_START, FrameSpans.UNBOUNDED_END))
                .as("no punch region: everything is inside").isEqualTo(10);
        assertThat(FrameSpans.overlaps(99, 100, 4, 100)).isTrue();
        assertThat(FrameSpans.overlaps(100, 101, 4, 100)).isFalse();
    }

    @Test
    void aPointPastTheLoopEndContinuesFromTheLoopStartAndTheLoopEndItselfStays() {
        assertThat(FrameSpans.intoLoop(1040, 1000, 1036)).isEqualTo(1004);
        assertThat(FrameSpans.intoLoop(1036, 1000, 1036)).as("a stop at the loop end is a valid point").isEqualTo(1036);
        assertThat(FrameSpans.intoLoop(1020, 1000, 1036)).isEqualTo(1020);
        assertThat(FrameSpans.intoLoop(1040, 0, 0)).as("no loop: nothing moves").isEqualTo(1040);
    }

    @Test
    void aSpanThatEndsExactlyOnALaterLoopBoundaryEndsAtTheLoopEnd() {
        assertThat(FrameSpans.intoLoop(1072, 1000, 1036)).as("one lap past the loop end: the end of that lap (E176)")
                .isEqualTo(1036);
        assertThat(FrameSpans.intoLoop(1108, 1000, 1036)).as("two laps past it").isEqualTo(1036);
        assertThat(FrameSpans.intoLoop(2048, 0, 1024)).isEqualTo(1024);
    }

    @Test
    void aPointPastALaterLoopBoundaryButNotOnOneContinuesFromTheLoopStart() {
        assertThat(FrameSpans.intoLoop(1076, 1000, 1036)).isEqualTo(1004);
        assertThat(FrameSpans.intoLoop(1080, 1000, 1036)).isEqualTo(1008);
        assertThat(FrameSpans.intoLoop(1037, 1000, 1036)).isEqualTo(1001);
    }

    @Test
    void anEndPastTheLoopEndLiesOneLapLaterForEachWrapItCrossedAndAnEndOnABoundaryStaysInTheLapItCompleted() {
        assertThat(FrameSpans.wrapsBeforeTheEnd(1040, 1000, 1036)).isEqualTo(1);
        assertThat(FrameSpans.wrapsBeforeTheEnd(1036, 1000, 1036)).as("on the loop end: the lap it completed (E176)")
                .isZero();
        assertThat(FrameSpans.wrapsBeforeTheEnd(1072, 1000, 1036)).as("on the next boundary: the end of lap 2 (E176)")
                .isEqualTo(1);
        assertThat(FrameSpans.wrapsBeforeTheEnd(1076, 1000, 1036)).isEqualTo(2);
        assertThat(FrameSpans.wrapsBeforeTheEnd(1020, 1000, 1036)).as("before the loop end").isZero();
        assertThat(FrameSpans.wrapsBeforeTheEnd(1040, 0, 0)).as("no loop: nothing wraps").isZero();
    }

    @Test
    void framesAcrossTheWrapAreCountedWhereTheyLieAfterTheCut() {
        assertThat(FrameSpans.framesInsideAcrossTheWrap(1032, 8, 1000, 1036, 1000, 1004))
                .as("4 frames before the end, 4 from the loop start, all of those inside").isEqualTo(4);
        assertThat(FrameSpans.framesInsideAcrossTheWrap(1032, 8, 1000, 1036, 1034, 1036)).isEqualTo(2);
        assertThat(FrameSpans.framesInsideAcrossTheWrap(1032, 8, 0, 0, 1036, 1040))
                .as("no loop: the span stays where it is").isEqualTo(4);
    }
}
