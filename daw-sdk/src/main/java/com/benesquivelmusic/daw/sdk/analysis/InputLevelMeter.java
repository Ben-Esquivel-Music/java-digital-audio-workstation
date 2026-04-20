package com.benesquivelmusic.daw.sdk.analysis;

/**
 * Immutable snapshot of input-signal metering for an armed track.
 *
 * <p>Exposes per-block peak, RMS, and a latching inter-sample-peak clip flag
 * as observed by the recording pipeline <em>ahead of any processing</em>. This
 * is the data the mixer's input-meter column and the arrangement-view track
 * header clip indicator read from (see user story 137).</p>
 *
 * <p>The clip flag is "sticky": once {@code clippedSinceReset} flips to
 * {@code true}, it remains {@code true} in successive snapshots until the
 * backing monitor's {@code reset()} method is called (typically wired to a
 * click on the mixer's clip LED).</p>
 *
 * <p>Note: The user story (137) originally specified package
 * {@code com.benesquivelmusic.daw.sdk.audio.analysis}, but this codebase's
 * existing convention places analysis records directly under
 * {@code com.benesquivelmusic.daw.sdk.analysis} (see
 * {@link DynamicRangeMetrics}, {@link SignalQualityMetrics}, etc.). Codebase
 * consistency wins.</p>
 *
 * @param peakDbfs            sample-peak level for the most recent block,
 *                            in dBFS (never higher than {@code 0.0} for a
 *                            non-clipping sample peak; may exceed {@code 0.0}
 *                            if the sample peak itself exceeded full scale)
 * @param rmsDbfs             windowed RMS level for the most recent block,
 *                            in dBFS
 * @param clippedSinceReset   {@code true} if an inter-sample peak at or
 *                            above {@code 0 dBFS} has been observed since
 *                            the last reset (sticky / latching)
 * @param lastClipFrameIndex  absolute frame index of the most recent clip
 *                            event, or {@code -1} if none has occurred
 *                            since the last reset
 */
public record InputLevelMeter(
        double peakDbfs,
        double rmsDbfs,
        boolean clippedSinceReset,
        long lastClipFrameIndex) {

    /** dBFS value reported when the signal is below the noise floor. */
    public static final double DB_FLOOR = -120.0;

    /** Snapshot for a fresh / silent / just-reset monitor. */
    public static final InputLevelMeter SILENCE =
            new InputLevelMeter(DB_FLOOR, DB_FLOOR, false, -1L);
}
