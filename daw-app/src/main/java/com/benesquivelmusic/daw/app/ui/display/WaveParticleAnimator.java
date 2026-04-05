package com.benesquivelmusic.daw.app.ui.display;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages animated particles that travel along sound wave paths.
 *
 * <p>Each particle has a position (0.0 to 1.0 along the path), a speed,
 * and an age-based fade. Particles are spawned periodically and die when
 * they reach the end of their path or exceed their maximum lifetime.</p>
 *
 * <p>This creates a fun, creative "traveling energy" effect that visually
 * communicates how sound propagates from sources to microphones.</p>
 */
public final class WaveParticleAnimator {

    /** A single animated particle traveling along a path. */
    public record Particle(double progress, double speed, double age, double maxAge) {

        /**
         * Creates a new particle.
         *
         * @param progress the position along the path in [0.0, 1.0]
         * @param speed    how fast the particle moves per second (fraction of path)
         * @param age      the current age of the particle in seconds
         * @param maxAge   the maximum lifetime in seconds
         */
        public Particle {
            if (speed <= 0) {
                throw new IllegalArgumentException("speed must be positive: " + speed);
            }
            if (maxAge <= 0) {
                throw new IllegalArgumentException("maxAge must be positive: " + maxAge);
            }
        }

        /** Returns the opacity based on the particle's age (fades out near end of life). */
        public double opacity() {
            double remaining = 1.0 - (age / maxAge);
            // Smooth fade-out in the last 30% of life
            if (remaining < 0.3) {
                return remaining / 0.3;
            }
            // Smooth fade-in in the first 10% of life
            double lived = age / maxAge;
            if (lived < 0.1) {
                return lived / 0.1;
            }
            return 1.0;
        }

        /** Returns true if the particle has expired. */
        public boolean isDead() {
            return age >= maxAge || progress >= 1.0;
        }

        /** Returns a new particle advanced by the given delta time. */
        public Particle advance(double deltaSeconds) {
            return new Particle(
                    Math.min(progress + speed * deltaSeconds, 1.0),
                    speed,
                    age + deltaSeconds,
                    maxAge
            );
        }
    }

    private final List<Particle> particles = new ArrayList<>();
    private final double spawnInterval;
    private final double particleSpeed;
    private final double particleMaxAge;
    private double timeSinceLastSpawn;

    /**
     * Creates a wave particle animator.
     *
     * @param spawnInterval  seconds between spawning new particles
     * @param particleSpeed  path-fraction traveled per second
     * @param particleMaxAge maximum lifetime of each particle in seconds
     */
    public WaveParticleAnimator(double spawnInterval, double particleSpeed, double particleMaxAge) {
        if (spawnInterval <= 0) {
            throw new IllegalArgumentException("spawnInterval must be positive: " + spawnInterval);
        }
        if (particleSpeed <= 0) {
            throw new IllegalArgumentException("particleSpeed must be positive: " + particleSpeed);
        }
        if (particleMaxAge <= 0) {
            throw new IllegalArgumentException("particleMaxAge must be positive: " + particleMaxAge);
        }
        this.spawnInterval = spawnInterval;
        this.particleSpeed = particleSpeed;
        this.particleMaxAge = particleMaxAge;
        this.timeSinceLastSpawn = 0;
    }

    /** Creates a wave particle animator with default settings (fun, lively defaults). */
    public WaveParticleAnimator() {
        this(0.15, 1.2, 1.5);
    }

    /**
     * Updates all particles and spawns new ones as needed.
     *
     * @param deltaSeconds time elapsed since the last update
     */
    public void update(double deltaSeconds) {
        // Advance existing particles
        particles.replaceAll(p -> p.advance(deltaSeconds));

        // Remove dead particles
        particles.removeIf(Particle::isDead);

        // Spawn new particles at the interval
        timeSinceLastSpawn += deltaSeconds;
        while (timeSinceLastSpawn >= spawnInterval) {
            particles.add(new Particle(0.0, particleSpeed, 0.0, particleMaxAge));
            timeSinceLastSpawn -= spawnInterval;
        }
    }

    /**
     * Returns an unmodifiable snapshot of the current particles.
     *
     * @return the live particles
     */
    public List<Particle> getParticles() {
        return List.copyOf(particles);
    }

    /** Returns the number of live particles. */
    public int getParticleCount() {
        return particles.size();
    }

    /** Resets the animator, removing all particles. */
    public void reset() {
        particles.clear();
        timeSinceLastSpawn = 0;
    }
}
