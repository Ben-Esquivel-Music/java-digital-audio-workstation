package com.benesquivelmusic.daw.sdk.export;

/**
 * Callback interface for monitoring the progress of an audio export operation.
 *
 * <p>Implementations receive periodic updates as the export pipeline progresses
 * through its stages (sample rate conversion, loudness normalization, dithering,
 * format encoding). The progress value increases monotonically from 0.0 to 1.0.</p>
 */
@FunctionalInterface
public interface ExportProgressListener {

    /**
     * Called to report export progress.
     *
     * @param progress a value in [0.0, 1.0] where 0.0 means not started
     *                 and 1.0 means complete
     * @param stage    a human-readable description of the current pipeline stage
     *                 (e.g., "Sample rate conversion", "Encoding WAV")
     */
    void onProgress(double progress, String stage);

    /**
     * A no-op listener that ignores all progress updates.
     */
    ExportProgressListener NONE = (progress, stage) -> { };
}
