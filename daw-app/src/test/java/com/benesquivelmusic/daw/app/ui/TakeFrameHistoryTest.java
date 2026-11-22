package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sampled transport frames a clock mark is dated from (story 338,
 * amendment A11, ruling E62): the newest sample at or before the evidence;
 * the current frame when the evidence is unknown or older than the kept
 * history of about four seconds.
 */
class TakeFrameHistoryTest {

    private static final long SECOND = 1_000_000_000L;
    private static final long CURRENT = 999_999L;

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
    void anUnknownEvidenceOrOneOlderThanTheHistoryIsTheCurrentFrame() {
        history.sample(10 * SECOND, 1_000);
        history.sample(11 * SECOND, 2_000);

        assertThat(history.frameAt(0L, CURRENT)).as("evidence unknown").isEqualTo(CURRENT);
        assertThat(history.frameAt(9 * SECOND, CURRENT)).as("older than every sample").isEqualTo(CURRENT);
        assertThat(new TakeFrameHistory().frameAt(10 * SECOND, CURRENT)).as("no sample yet").isEqualTo(CURRENT);
    }

    @Test
    void samplesOlderThanTheSpanAreDropped() {
        history.sample(10 * SECOND, 1_000);
        history.sample(12 * SECOND, 2_000);
        assertThat(history.frameAt(10 * SECOND, CURRENT)).as("negative control: inside the span").isEqualTo(1_000);

        history.sample(10 * SECOND + TakeFrameHistory.SPAN_NANOS + 1, 3_000);

        assertThat(history.frameAt(10 * SECOND, CURRENT)).as("dropped: older than the span").isEqualTo(CURRENT);
        assertThat(history.frameAt(12 * SECOND, CURRENT)).isEqualTo(2_000);
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
    void theOrderOfReadingsSurvivesTheNanoClockWrapping() {
        history.sample(Long.MAX_VALUE - SECOND, 1_000);
        history.sample(Long.MAX_VALUE - SECOND + 2 * SECOND, 2_000);

        assertThat(history.frameAt(Long.MAX_VALUE, CURRENT)).isEqualTo(1_000);
        assertThat(history.frameAt(Long.MAX_VALUE - SECOND + 2 * SECOND, CURRENT)).isEqualTo(2_000);
    }
}
