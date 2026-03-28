package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.core.analysis.LoudnessMeter;
import com.benesquivelmusic.daw.sdk.visualization.LoudnessTarget;

/**
 * Normalizes audio to a target integrated loudness (LUFS).
 *
 * <p>Uses the {@link LoudnessMeter} to measure the integrated loudness of the
 * input audio, then applies a linear gain adjustment to reach the target LUFS.
 * The normalization is applied in-place to the audio buffer.</p>
 *
 * <p>If the measured loudness is below the absolute gate threshold (−70 LUFS),
 * no normalization is applied (the audio is effectively silence).</p>
 */
public final class LoudnessNormalizer {

    private static final double LUFS_FLOOR = -70.0;
    private static final int BLOCK_SIZE = 4096;

    private LoudnessNormalizer() {
        // utility class
    }

    /**
     * Normalizes the audio to the specified target integrated loudness.
     *
     * @param audioData  the audio data as {@code [channel][sample]} in [-1.0, 1.0];
     *                   modified in-place
     * @param sampleRate the sample rate in Hz
     * @param targetLufs the target integrated loudness in LUFS (e.g., -14.0)
     * @return the gain applied in dB, or 0.0 if no normalization was applied
     * @throws IllegalArgumentException if audioData is empty or sampleRate is not positive
     */
    public static double normalize(float[][] audioData, int sampleRate, double targetLufs) {
        if (audioData == null || audioData.length == 0) {
            throw new IllegalArgumentException("audioData must have at least one channel");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }

        double measuredLufs = measureIntegratedLoudness(audioData, sampleRate);

        if (measuredLufs <= LUFS_FLOOR) {
            return 0.0;
        }

        double gainDb = targetLufs - measuredLufs;
        double gainLinear = Math.pow(10.0, gainDb / 20.0);

        applyGain(audioData, gainLinear);

        return gainDb;
    }

    /**
     * Normalizes the audio to the integrated loudness target defined by the
     * specified {@link LoudnessTarget}.
     *
     * @param audioData  the audio data as {@code [channel][sample]} in [-1.0, 1.0];
     *                   modified in-place
     * @param sampleRate the sample rate in Hz
     * @param target     the loudness target preset
     * @return the gain applied in dB, or 0.0 if no normalization was applied
     */
    public static double normalize(float[][] audioData, int sampleRate, LoudnessTarget target) {
        if (target == null) {
            throw new IllegalArgumentException("target must not be null");
        }
        return normalize(audioData, sampleRate, target.targetIntegratedLufs());
    }

    /**
     * Measures the integrated loudness of the audio in LUFS.
     *
     * @param audioData  the audio data as {@code [channel][sample]}
     * @param sampleRate the sample rate in Hz
     * @return the measured integrated loudness in LUFS
     */
    public static double measureIntegratedLoudness(float[][] audioData, int sampleRate) {
        if (audioData == null || audioData.length == 0) {
            throw new IllegalArgumentException("audioData must have at least one channel");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }

        int channels = audioData.length;
        int numSamples = audioData[0].length;

        float[] left = audioData[0];
        float[] right = (channels >= 2) ? audioData[1] : audioData[0];

        LoudnessMeter meter = new LoudnessMeter(sampleRate, BLOCK_SIZE);

        int offset = 0;
        while (offset < numSamples) {
            int framesToProcess = Math.min(BLOCK_SIZE, numSamples - offset);

            float[] leftBlock = new float[framesToProcess];
            float[] rightBlock = new float[framesToProcess];
            System.arraycopy(left, offset, leftBlock, 0, framesToProcess);
            System.arraycopy(right, offset, rightBlock, 0, framesToProcess);

            meter.process(leftBlock, rightBlock, framesToProcess);
            offset += framesToProcess;
        }

        return meter.getLatestData().integratedLufs();
    }

    private static void applyGain(float[][] audioData, double gainLinear) {
        for (int ch = 0; ch < audioData.length; ch++) {
            for (int i = 0; i < audioData[ch].length; i++) {
                audioData[ch][i] = (float) Math.max(-1.0, Math.min(1.0,
                        audioData[ch][i] * gainLinear));
            }
        }
    }
}
