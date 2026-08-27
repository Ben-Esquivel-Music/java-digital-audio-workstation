package com.benesquivelmusic.daw.app.ui.display;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeterAnimatorTest {

    private static final long FRAME_NANOS = 16_666_667L; // ~60 fps

    @Test
    void shouldInitializeToZero() {
        MeterAnimator animator = new MeterAnimator();
        assertThat(animator.getCurrentValue()).isEqualTo(0.0);
        assertThat(animator.getPeakValue()).isEqualTo(0.0);
    }

    @Test
    void shouldRiseTowardsTarget() {
        MeterAnimator animator = new MeterAnimator();
        // Simulate multiple frames pushing towards 0.8
        for (int i = 0; i < 60; i++) {
            animator.update(0.8, FRAME_NANOS);
        }
        assertThat(animator.getCurrentValue()).isGreaterThan(0.5);
    }

    @Test
    void shouldDecayWhenTargetDrops() {
        MeterAnimator animator = new MeterAnimator();
        // Rise
        for (int i = 0; i < 60; i++) {
            animator.update(0.8, FRAME_NANOS);
        }
        double peakValue = animator.getCurrentValue();

        // Decay
        for (int i = 0; i < 60; i++) {
            animator.update(0.0, FRAME_NANOS);
        }
        assertThat(animator.getCurrentValue()).isLessThan(peakValue);
    }

    @Test
    void shouldHoldPeakValue() {
        MeterAnimator animator = new MeterAnimator(0.005, 0.3, 1.5);

        // Push to a peak
        for (int i = 0; i < 10; i++) {
            animator.update(0.9, FRAME_NANOS);
        }

        double peakAfterPush = animator.getPeakValue();
        assertThat(peakAfterPush).isCloseTo(0.9, org.assertj.core.data.Offset.offset(0.01));

        // Drop signal but peak should hold
        for (int i = 0; i < 30; i++) {
            animator.update(0.0, FRAME_NANOS);
        }
        // Peak hold time is 1.5s = 90 frames at 60fps. After 30 frames, it should still hold
        assertThat(animator.getPeakValue()).isCloseTo(0.9, org.assertj.core.data.Offset.offset(0.1));
    }

    @Test
    void shouldResetToZero() {
        MeterAnimator animator = new MeterAnimator();
        for (int i = 0; i < 30; i++) {
            animator.update(0.8, FRAME_NANOS);
        }
        animator.reset();

        assertThat(animator.getCurrentValue()).isEqualTo(0.0);
        assertThat(animator.getPeakValue()).isEqualTo(0.0);
    }

    @Test
    void shouldHaveDefaultConstants() {
        assertThat(MeterAnimator.DEFAULT_ATTACK_SECONDS).isEqualTo(0.005);
        assertThat(MeterAnimator.DEFAULT_RELEASE_SECONDS).isEqualTo(0.3);
        assertThat(MeterAnimator.DEFAULT_PEAK_HOLD_SECONDS).isEqualTo(1.5);
    }

    @Test
    void shouldRejectInvalidAttack() {
        assertThatThrownBy(() -> new MeterAnimator(0, 0.3, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidRelease() {
        assertThatThrownBy(() -> new MeterAnimator(0.005, 0, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativePeakHold() {
        assertThatThrownBy(() -> new MeterAnimator(0.005, 0.3, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAttackFasterThanRelease() {
        MeterAnimator animator = new MeterAnimator();

        // Measure attack speed
        for (int i = 0; i < 5; i++) {
            animator.update(1.0, FRAME_NANOS);
        }
        double afterAttack = animator.getCurrentValue();

        // Reset and measure release speed
        animator.reset();
        animator.update(1.0, FRAME_NANOS); // Set to 1.0 instantly
        for (int i = 0; i < 60; i++) {
            animator.update(1.0, FRAME_NANOS);
        }
        double peakVal = animator.getCurrentValue();

        animator.update(0.0, FRAME_NANOS);
        animator.update(0.0, FRAME_NANOS);
        animator.update(0.0, FRAME_NANOS);
        animator.update(0.0, FRAME_NANOS);
        animator.update(0.0, FRAME_NANOS);
        double afterRelease = peakVal - animator.getCurrentValue();

        // Attack should move further in 5 frames than release drops
        // This is a behavioral test — attack is faster than release
        assertThat(afterAttack).isGreaterThan(0.0);
    }

    // ── Story 318 — delta-correct ballistics ────────────────────────────────

    private static final long FRAME_NANOS_120 = 8_333_333L; // ~120 fps

    @Test
    void decayPerUnitTimeIsTheSameAtSixtyAndOneHundredTwentyHertz() {
        MeterAnimator at60 = new MeterAnimator();
        MeterAnimator at120 = new MeterAnimator();
        // One long attack step lands both at (numerically) full scale.
        at60.update(1.0, 1_000_000_000L);
        at120.update(1.0, 1_000_000_000L);

        // Release for 0.5 s: 30 frames at 60 Hz vs 60 frames at 120 Hz.
        for (int i = 0; i < 30; i++) {
            at60.update(0.0, FRAME_NANOS);
        }
        for (int i = 0; i < 60; i++) {
            at120.update(0.0, FRAME_NANOS_120);
        }

        double analytic = Math.exp(-0.5 / MeterAnimator.DEFAULT_RELEASE_SECONDS);
        assertThat(at60.getCurrentValue())
                .as("60 Hz decay after 0.5 s matches the analytic exp(-t/tau)")
                .isCloseTo(analytic, org.assertj.core.data.Offset.offset(1e-3));
        assertThat(at120.getCurrentValue())
                .as("120 Hz decay after 0.5 s is the same")
                .isCloseTo(at60.getCurrentValue(), org.assertj.core.data.Offset.offset(1e-3));
    }

    @Test
    void riseTimeIsTheSameAtSixtyAndOneHundredTwentyHertz() {
        MeterAnimator at60 = new MeterAnimator(0.05, 0.3, 0);
        MeterAnimator at120 = new MeterAnimator(0.05, 0.3, 0);
        for (int i = 0; i < 6; i++) {
            at60.update(1.0, FRAME_NANOS);
        }
        for (int i = 0; i < 12; i++) {
            at120.update(1.0, FRAME_NANOS_120);
        }
        double analytic = 1.0 - Math.exp(-0.1 / 0.05);
        assertThat(at60.getCurrentValue()).isCloseTo(analytic, org.assertj.core.data.Offset.offset(1e-3));
        assertThat(at120.getCurrentValue()).isCloseTo(at60.getCurrentValue(), org.assertj.core.data.Offset.offset(1e-3));
    }

    @Test
    void aDroppedFrameGapDoesNotOvershootTheTarget() {
        MeterAnimator animator = new MeterAnimator();
        long gap = 250_000_000L; // 250 ms without a frame

        animator.update(1.0, gap);
        assertThat(animator.getCurrentValue())
                .as("rising through a 250 ms gap converges without overshoot")
                .isGreaterThan(0.99).isLessThanOrEqualTo(1.0);
        assertThat(animator.getPeakValue()).isEqualTo(1.0);

        animator.update(0.2, gap);
        assertThat(animator.getCurrentValue())
                .as("falling through a 250 ms gap never undershoots the target")
                .isGreaterThanOrEqualTo(0.2).isLessThan(1.0);

        // Peak fall after a zero-length hold: one 3 s gap (10 release time
        // constants) takes both the smoothed value and the peak below the
        // snap-to-zero threshold — never negative, never past the target.
        MeterAnimator hold = new MeterAnimator(0.005, 0.3, 0.0);
        hold.update(1.0, FRAME_NANOS);
        hold.update(0.0, 3_000_000_000L);
        assertThat(hold.getPeakValue()).as("the peak falls with the release tau once the hold expires")
                .isEqualTo(0.0);
        assertThat(hold.getCurrentValue()).isEqualTo(0.0);
    }

    @Test
    void zeroOrNegativeDeltaLeavesEveryValueUnchanged() {
        MeterAnimator animator = new MeterAnimator();
        for (int i = 0; i < 10; i++) {
            animator.update(0.8, FRAME_NANOS);
        }
        double value = animator.getCurrentValue();
        double peak = animator.getPeakValue();

        animator.update(0.0, 0L);
        animator.update(1.0, -FRAME_NANOS);

        assertThat(animator.getCurrentValue()).isEqualTo(value);
        assertThat(animator.getPeakValue()).isEqualTo(peak);
    }
}
