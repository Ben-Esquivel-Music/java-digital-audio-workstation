package com.benesquivelmusic.daw.app.ui.display;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WaveParticleAnimatorTest {

    @Test
    void shouldStartWithNoParticles() {
        WaveParticleAnimator animator = new WaveParticleAnimator();

        assertThat(animator.getParticleCount()).isZero();
        assertThat(animator.getParticles()).isEmpty();
    }

    @Test
    void shouldSpawnParticleAfterInterval() {
        WaveParticleAnimator animator = new WaveParticleAnimator(0.1, 1.0, 2.0);

        // Advance past the first spawn interval
        animator.update(0.15);

        assertThat(animator.getParticleCount()).isEqualTo(1);
    }

    @Test
    void shouldSpawnMultipleParticlesOverTime() {
        WaveParticleAnimator animator = new WaveParticleAnimator(0.1, 1.0, 2.0);

        // Advance enough to spawn 3 particles
        animator.update(0.35);

        assertThat(animator.getParticleCount()).isEqualTo(3);
    }

    @Test
    void shouldAdvanceParticleProgress() {
        WaveParticleAnimator animator = new WaveParticleAnimator(0.05, 1.0, 5.0);

        animator.update(0.1); // spawn particles
        animator.update(0.3); // advance existing, may also spawn new

        // At least some particles should have progressed beyond their start
        assertThat(animator.getParticles())
                .anyMatch(p -> p.progress() > 0);
    }

    @Test
    void shouldRemoveDeadParticles() {
        WaveParticleAnimator animator = new WaveParticleAnimator(0.1, 2.0, 0.5);

        animator.update(0.15); // spawn 1
        assertThat(animator.getParticleCount()).isEqualTo(1);

        // Advance past maxAge (0.5s) — the particle should die at progress=1.0 or maxAge
        animator.update(0.6);

        // Old particle died, but new ones may have spawned (6 intervals passed)
        for (WaveParticleAnimator.Particle particle : animator.getParticles()) {
            assertThat(particle.isDead()).isFalse();
        }
    }

    @Test
    void shouldResetAllParticles() {
        WaveParticleAnimator animator = new WaveParticleAnimator(0.1, 1.0, 2.0);

        animator.update(0.5);
        assertThat(animator.getParticleCount()).isGreaterThan(0);

        animator.reset();
        assertThat(animator.getParticleCount()).isZero();
    }

    @Test
    void particleShouldComputeOpacity() {
        WaveParticleAnimator.Particle particle = new WaveParticleAnimator.Particle(0.5, 1.0, 0.5, 2.0);

        assertThat(particle.opacity()).isBetween(0.0, 1.0);
    }

    @Test
    void particleShouldFadeInAtBirth() {
        // Very young particle
        WaveParticleAnimator.Particle particle = new WaveParticleAnimator.Particle(0.0, 1.0, 0.01, 2.0);

        // Should be partially faded in
        assertThat(particle.opacity()).isLessThan(1.0);
        assertThat(particle.opacity()).isGreaterThan(0.0);
    }

    @Test
    void particleShouldFadeOutNearDeath() {
        // Particle near end of life
        WaveParticleAnimator.Particle particle = new WaveParticleAnimator.Particle(0.8, 1.0, 1.9, 2.0);

        assertThat(particle.opacity()).isLessThan(0.5);
    }

    @Test
    void particleShouldBeDeadWhenProgressReaches1() {
        WaveParticleAnimator.Particle particle = new WaveParticleAnimator.Particle(1.0, 1.0, 0.5, 2.0);

        assertThat(particle.isDead()).isTrue();
    }

    @Test
    void particleShouldBeDeadWhenAgeExceedsMaxAge() {
        WaveParticleAnimator.Particle particle = new WaveParticleAnimator.Particle(0.5, 1.0, 2.0, 2.0);

        assertThat(particle.isDead()).isTrue();
    }

    @Test
    void particleAdvanceShouldIncrementProgressAndAge() {
        WaveParticleAnimator.Particle particle = new WaveParticleAnimator.Particle(0.0, 1.0, 0.0, 3.0);
        WaveParticleAnimator.Particle advanced = particle.advance(0.5);

        assertThat(advanced.progress()).isGreaterThan(0.0);
        assertThat(advanced.age()).isEqualTo(0.5);
    }

    @Test
    void shouldRejectNonPositiveSpawnInterval() {
        assertThatThrownBy(() -> new WaveParticleAnimator(0, 1.0, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNonPositiveParticleSpeed() {
        assertThatThrownBy(() -> new WaveParticleAnimator(0.1, 0, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNonPositiveMaxAge() {
        assertThatThrownBy(() -> new WaveParticleAnimator(0.1, 1.0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void particleShouldRejectNonPositiveSpeed() {
        assertThatThrownBy(() -> new WaveParticleAnimator.Particle(0, 0, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void particleShouldRejectNonPositiveMaxAge() {
        assertThatThrownBy(() -> new WaveParticleAnimator.Particle(0, 1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldReturnDefensiveCopyOfParticles() {
        WaveParticleAnimator animator = new WaveParticleAnimator(0.1, 1.0, 2.0);
        animator.update(0.15);

        List<WaveParticleAnimator.Particle> particles = animator.getParticles();

        // List.copyOf returns an unmodifiable list
        assertThatThrownBy(() -> particles.clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void defaultConstructorShouldCreateValidAnimator() {
        WaveParticleAnimator animator = new WaveParticleAnimator();

        animator.update(1.0);
        assertThat(animator.getParticleCount()).isGreaterThan(0);
    }
}
