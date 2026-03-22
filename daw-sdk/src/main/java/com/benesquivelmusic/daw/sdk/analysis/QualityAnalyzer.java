package com.benesquivelmusic.daw.sdk.analysis;

/**
 * Interface for audio quality assessment.
 *
 * <p>Implementations analyze audio buffers and produce a {@link QualityReport}
 * containing objective quality metrics derived from AES research on perceptual
 * audio quality. Metrics include signal quality (SNR, THD, crest factor),
 * spectral quality (flatness, centroid, bandwidth), stereo quality (correlation,
 * width consistency, mono compatibility), and dynamic range (PLR, DR score).</p>
 *
 * <p>The analyzer is designed for offline (non-real-time) analysis of complete
 * audio buffers, typically used for pre-export quality validation in the
 * mastering workflow.</p>
 */
public interface QualityAnalyzer {

    /**
     * Analyzes stereo audio and produces a complete quality report.
     *
     * @param left        left channel samples in [-1.0, 1.0]
     * @param right       right channel samples in [-1.0, 1.0]
     * @param numFrames   number of sample frames to analyze
     * @param sampleRate  audio sample rate in Hz
     * @param thresholds  pass/fail thresholds for the report
     * @return a quality report with all metrics and pass/fail evaluation
     */
    QualityReport analyze(float[] left, float[] right, int numFrames,
                          double sampleRate, QualityThresholds thresholds);

    /**
     * Analyzes stereo audio using default professional thresholds.
     *
     * @param left        left channel samples in [-1.0, 1.0]
     * @param right       right channel samples in [-1.0, 1.0]
     * @param numFrames   number of sample frames to analyze
     * @param sampleRate  audio sample rate in Hz
     * @return a quality report with all metrics and pass/fail evaluation
     */
    default QualityReport analyze(float[] left, float[] right, int numFrames,
                                  double sampleRate) {
        return analyze(left, right, numFrames, sampleRate, QualityThresholds.DEFAULT);
    }

    /**
     * Computes signal quality metrics (SNR, THD, crest factor) for a mono signal.
     *
     * @param samples    audio samples in [-1.0, 1.0]
     * @param numFrames  number of sample frames
     * @return signal quality metrics
     */
    SignalQualityMetrics analyzeSignalQuality(float[] samples, int numFrames);

    /**
     * Computes spectral quality metrics for a mono signal.
     *
     * @param samples     audio samples in [-1.0, 1.0]
     * @param numFrames   number of sample frames
     * @param sampleRate  audio sample rate in Hz
     * @return spectral quality metrics
     */
    SpectralQualityMetrics analyzeSpectralQuality(float[] samples, int numFrames,
                                                  double sampleRate);

    /**
     * Computes stereo quality metrics for a stereo signal.
     *
     * @param left       left channel samples
     * @param right      right channel samples
     * @param numFrames  number of sample frames
     * @return stereo quality metrics
     */
    StereoQualityMetrics analyzeStereoQuality(float[] left, float[] right, int numFrames);

    /**
     * Computes dynamic range metrics for a mono signal.
     *
     * @param samples    audio samples in [-1.0, 1.0]
     * @param numFrames  number of sample frames
     * @return dynamic range metrics
     */
    DynamicRangeMetrics analyzeDynamicRange(float[] samples, int numFrames);
}
