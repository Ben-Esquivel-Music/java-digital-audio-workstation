package com.benesquivelmusic.daw.core.mastering;

import com.benesquivelmusic.daw.sdk.mastering.CrossfadeCurve;

/**
 * Generates crossfade gain curves for transitions between album tracks.
 *
 * <p>Supports three curve types:</p>
 * <ul>
 *   <li>{@link CrossfadeCurve#LINEAR} — straight-line gain ramps</li>
 *   <li>{@link CrossfadeCurve#EQUAL_POWER} — maintains constant perceived loudness
 *       using sine/cosine curves</li>
 *   <li>{@link CrossfadeCurve#S_CURVE} — smooth ease-in/ease-out using a
 *       raised-cosine (Hann) envelope</li>
 * </ul>
 */
public final class CrossfadeGenerator {

    private CrossfadeGenerator() {
        // utility class
    }

    /**
     * Generates a pair of gain curves for a crossfade transition.
     *
     * <p>The returned array contains two float arrays of the given length:
     * {@code [0]} is the fade-out curve (starts at 1.0, ends at 0.0) and
     * {@code [1]} is the fade-in curve (starts at 0.0, ends at 1.0).</p>
     *
     * @param curve      the crossfade curve type
     * @param numSamples the number of samples in the crossfade region
     * @return a {@code float[2][numSamples]} array of gain curves
     * @throws IllegalArgumentException if numSamples is not positive
     */
    public static float[][] generate(CrossfadeCurve curve, int numSamples) {
        if (numSamples <= 0) {
            throw new IllegalArgumentException("numSamples must be positive: " + numSamples);
        }

        float[] fadeOut = new float[numSamples];
        float[] fadeIn = new float[numSamples];

        for (int i = 0; i < numSamples; i++) {
            double t = (numSamples == 1) ? 1.0 : (double) i / (numSamples - 1);
            switch (curve) {
                case LINEAR -> {
                    fadeOut[i] = (float) (1.0 - t);
                    fadeIn[i] = (float) t;
                }
                case EQUAL_POWER -> {
                    fadeOut[i] = (float) Math.cos(t * Math.PI * 0.5);
                    fadeIn[i] = (float) Math.sin(t * Math.PI * 0.5);
                }
                case S_CURVE -> {
                    double s = 0.5 * (1.0 - Math.cos(t * Math.PI));
                    fadeOut[i] = (float) (1.0 - s);
                    fadeIn[i] = (float) s;
                }
            }
        }

        return new float[][] { fadeOut, fadeIn };
    }

    /**
     * Generates a crossfade with the specified duration and sample rate.
     *
     * @param curve           the crossfade curve type
     * @param durationSeconds the crossfade duration in seconds
     * @param sampleRate      the audio sample rate in Hz
     * @return a {@code float[2][numSamples]} array of gain curves
     */
    public static float[][] generate(CrossfadeCurve curve, double durationSeconds,
                                     double sampleRate) {
        int numSamples = Math.max(1, (int) (durationSeconds * sampleRate));
        return generate(curve, numSamples);
    }
}
