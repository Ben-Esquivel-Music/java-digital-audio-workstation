package com.benesquivelmusic.daw.core.metering;

import com.benesquivelmusic.daw.sdk.visualization.LevelData;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class MeterFrameTest {

    @Test
    void freshFrameIsEmptyAndSilent() {
        MeterFrame frame = new MeterFrame();
        assertThat(frame.channelCount()).isZero();
        assertThat(frame.epoch()).isZero();
        assertThat(frame.blockIndex()).isZero();
        assertThat(frame.clipped()).isFalse();
        assertThat(frame.isSilent()).isTrue();
        assertThat(frame.maxPeak()).isZero();
        assertThat(frame.maxRms()).isZero();
    }

    @Test
    void markSilentStampsTheFrameAndZeroesEveryLane() {
        MeterFrame frame = publishedFrame(1.0f, 0.5f, true);
        assertThat(frame.isSilent()).isFalse();

        frame.markSilent(7L, 99L, 2);

        assertThat(frame.epoch()).isEqualTo(7L);
        assertThat(frame.blockIndex()).isEqualTo(99L);
        assertThat(frame.channelCount()).isEqualTo(2);
        assertThat(frame.clipped()).isFalse();
        assertThat(frame.isSilent()).isTrue();
        for (int ch = 0; ch < MeterFrame.MAX_CHANNELS; ch++) {
            assertThat(frame.peak(ch)).isZero();
            assertThat(frame.rms(ch)).isZero();
        }
    }

    @Test
    void markSilentRejectsAnOutOfRangeLaneCount() {
        MeterFrame frame = new MeterFrame();
        assertThatIllegalArgumentException().isThrownBy(() -> frame.markSilent(1L, 1L, -1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> frame.markSilent(1L, 1L, MeterFrame.MAX_CHANNELS + 1));
    }

    @Test
    void laneAccessorsRejectOutOfRangeLanes() {
        MeterFrame frame = new MeterFrame();
        assertThatThrownBy(() -> frame.peak(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> frame.rms(MeterFrame.MAX_CHANNELS))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void clearResetsEverything() {
        MeterFrame frame = publishedFrame(1.0f, 0.5f, true);
        frame.clear();
        assertThat(frame.channelCount()).isZero();
        assertThat(frame.epoch()).isZero();
        assertThat(frame.blockIndex()).isZero();
        assertThat(frame.clipped()).isFalse();
        assertThat(frame.isSilent()).isTrue();
    }

    @Test
    void maxPeakAndMaxRmsSpanOnlyTheMeaningfulLanes() {
        LevelTapSlot slot = new LevelTapSlot(MeterTapPoint.MASTER_OUT);
        slot.beginBlock(1L, 1L, 3);
        slot.accumulate(0, 0.2f);
        slot.accumulate(1, -0.9f);
        slot.accumulate(2, 0.4f);
        slot.publish(1);
        MeterFrame frame = new MeterFrame();
        assertThat(slot.readInto(frame)).isTrue();
        assertThat(frame.channelCount()).isEqualTo(3);
        assertThat(frame.maxPeak()).isCloseTo(0.9f, within(1e-6f));
        assertThat(frame.maxRms()).isCloseTo(0.9f, within(1e-6f));
    }

    @Test
    void toLevelDataConvertsTheLoudestLaneToDecibels() {
        MeterFrame frame = publishedFrame(0.5f, 0.25f, false);
        LevelData data = frame.toLevelData();
        assertThat(data.peakLinear()).isCloseTo(0.5, within(1e-6));
        assertThat(data.rmsLinear()).isCloseTo(0.25, within(1e-6));
        assertThat(data.peakDb()).isCloseTo(-6.0206, within(1e-3));
        assertThat(data.rmsDb()).isCloseTo(-12.0412, within(1e-3));
        assertThat(data.clipping()).isFalse();
    }

    @Test
    void toLevelDataOfSilenceIsMinusInfinity() {
        LevelData data = new MeterFrame().toLevelData();
        assertThat(data.peakDb()).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(data.rmsDb()).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(data.peakLinear()).isZero();
        assertThat(data.clipping()).isFalse();
    }

    @Test
    void toLevelDataCarriesTheClipFlag() {
        MeterFrame frame = publishedFrame(1.0f, 1.0f, true);
        LevelData data = frame.toLevelData();
        assertThat(data.clipping()).isTrue();
        assertThat(data.peakDb()).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void toDbMatchesTwentyLogTen() {
        assertThat(MeterFrame.toDb(0.0)).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(MeterFrame.toDb(-1.0)).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(MeterFrame.toDb(1.0)).isCloseTo(0.0, within(1e-12));
        assertThat(MeterFrame.toDb(0.1)).isCloseTo(-20.0, within(1e-9));
    }

    @Test
    void failedReadLeavesTheFrameUntouched() {
        MeterFrame frame = publishedFrame(1.0f, 0.5f, true);
        LevelTapSlot neverPublished = new LevelTapSlot(MeterTapPoint.MASTER_CHAIN);

        assertThat(neverPublished.readInto(frame)).isFalse();

        assertThat(frame.peak(0)).isCloseTo(1.0f, within(1e-6f));
        assertThat(frame.rms(0)).isCloseTo(0.5f, within(1e-6f));
        assertThat(frame.clipped()).isTrue();
        assertThat(frame.epoch()).isEqualTo(3L);
        assertThat(frame.blockIndex()).isEqualTo(42L);
    }

    /**
     * A frame whose lane 0 reports the given peak / RMS: two samples
     * {@code +peak} and {@code -peak} would give RMS == peak, so the RMS is
     * shaped by padding with zeros ({@code rms = peak * sqrt(k / n)}).
     */
    private static MeterFrame publishedFrame(float peak, float rms, boolean clipped) {
        LevelTapSlot slot = new LevelTapSlot(MeterTapPoint.MASTER_OUT);
        slot.beginBlock(3L, 42L, 1);
        // n samples with k of them at |peak|: rms = peak * sqrt(k/n).
        int n = 16;
        int k = Math.round(n * (rms * rms) / (peak * peak));
        for (int i = 0; i < k; i++) {
            slot.accumulate(0, (i & 1) == 0 ? peak : -peak);
        }
        slot.publish(n);
        MeterFrame frame = new MeterFrame();
        if (!slot.readInto(frame)) {
            throw new AssertionError("fixture slot must be readable");
        }
        if (frame.clipped() != clipped) {
            throw new AssertionError("fixture clip flag mismatch: wanted " + clipped);
        }
        return frame;
    }
}
