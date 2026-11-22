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
    void framesAcrossTheWrapAreCountedWhereTheyLieAfterTheCut() {
        assertThat(FrameSpans.framesInsideAcrossTheWrap(1032, 8, 1000, 1036, 1000, 1004))
                .as("4 frames before the end, 4 from the loop start, all of those inside").isEqualTo(4);
        assertThat(FrameSpans.framesInsideAcrossTheWrap(1032, 8, 1000, 1036, 1034, 1036)).isEqualTo(2);
        assertThat(FrameSpans.framesInsideAcrossTheWrap(1032, 8, 0, 0, 1036, 1040))
                .as("no loop: the span stays where it is").isEqualTo(4);
    }
}
