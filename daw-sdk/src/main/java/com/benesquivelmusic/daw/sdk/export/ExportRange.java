package com.benesquivelmusic.daw.sdk.export;

/**
 * Defines a time range for audio export in seconds.
 *
 * <p>Use {@link #FULL} to export the entire project.
 * Otherwise, specify a start and end time in seconds to export
 * only a portion of the audio.</p>
 *
 * @param startSeconds the start time in seconds (inclusive), must be non-negative
 * @param endSeconds   the end time in seconds (exclusive), must be greater than startSeconds;
 *                     use {@link Double#MAX_VALUE} for "until end of project"
 */
public record ExportRange(double startSeconds, double endSeconds) {

    /** Sentinel value representing the full project duration. */
    public static final ExportRange FULL = new ExportRange(0.0, Double.MAX_VALUE);

    public ExportRange {
        if (startSeconds < 0) {
            throw new IllegalArgumentException("startSeconds must be non-negative: " + startSeconds);
        }
        if (endSeconds <= startSeconds) {
            throw new IllegalArgumentException(
                    "endSeconds must be greater than startSeconds: " + startSeconds + " >= " + endSeconds);
        }
    }

    /**
     * Returns {@code true} if this range represents the full project.
     *
     * @return whether this is a full-project export
     */
    public boolean isFullProject() {
        return startSeconds == 0.0 && endSeconds == Double.MAX_VALUE;
    }

    /**
     * Extracts the portion of audio data within this range.
     *
     * @param audioData  the full audio data as {@code [channel][sample]}
     * @param sampleRate the sample rate in Hz
     * @return the trimmed audio data, or the original data if this is a full-project range
     */
    public float[][] extractRange(float[][] audioData, int sampleRate) {
        if (isFullProject() || audioData.length == 0) {
            return audioData;
        }

        int totalSamples = audioData[0].length;
        int startSample = Math.min((int) (startSeconds * sampleRate), totalSamples);
        int endSample = Math.min((int) (endSeconds * sampleRate), totalSamples);

        if (startSample >= endSample) {
            return new float[audioData.length][0];
        }

        int length = endSample - startSample;
        float[][] result = new float[audioData.length][length];
        for (int ch = 0; ch < audioData.length; ch++) {
            System.arraycopy(audioData[ch], startSample, result[ch], 0, length);
        }
        return result;
    }
}
