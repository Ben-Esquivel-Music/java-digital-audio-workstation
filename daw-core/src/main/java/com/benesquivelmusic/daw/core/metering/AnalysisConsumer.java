package com.benesquivelmusic.daw.core.metering;

/**
 * A consumer on the analysis lane (book &sect;3.4): receives whole sample
 * blocks, in order, on the single {@code daw-metering-analysis} thread.
 *
 * <p>{@link #onBlock} is called <strong>only</strong> on the analysis thread,
 * never on the render thread and never on the FX thread. The sample arrays
 * are the lane's scratch buffers: valid for the duration of the call, reused
 * for the next block, so a consumer that needs history must copy. A consumer
 * that throws a {@link RuntimeException} is logged (rate-limited) and skipped
 * for that block; the lane keeps draining.</p>
 *
 * <p>Consumers of this substrate (spectrum, loudness, correlation, tuner)
 * land in story 319.</p>
 */
@FunctionalInterface
public interface AnalysisConsumer {

    /**
     * Delivers one rendered block.
     *
     * @param samples      planar samples, {@code samples[channel][frame]}; only
     *                     the first {@code channelCount} lanes and the first
     *                     {@code numFrames} frames are meaningful
     * @param channelCount lanes carried by this block ({@code 1..MeterFrame.MAX_CHANNELS})
     * @param numFrames    frames carried by this block
     * @param sampleRate   the engine sample rate in Hz at delivery time
     *                     ({@code 0.0} when the bus is unbound)
     */
    void onBlock(float[][] samples, int channelCount, int numFrames, double sampleRate);

    /**
     * Reports that the ring dropped blocks because this consumer fell behind.
     * Called on the analysis thread after the surviving blocks are drained,
     * with the cumulative count. Default: ignore.
     *
     * @param droppedBlocks total blocks dropped since attach
     */
    default void onOverrun(long droppedBlocks) {
    }
}
