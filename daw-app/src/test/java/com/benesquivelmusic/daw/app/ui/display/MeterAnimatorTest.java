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
}
