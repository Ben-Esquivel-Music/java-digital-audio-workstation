package com.benesquivelmusic.daw.sdk.plugin;

/**
 * Immutable snapshot of a plugin's instantaneous meter reading, intended for
 * UI consumption (e.g., driving a needle-style VU meter in a plugin view).
 *
 * <p>Plugins that expose metering output (dynamics processors, level meters,
 * etc.) can create a {@code PluginMeterSnapshot} on the audio thread and make
 * it available to the UI thread. Because the record is immutable and all
 * fields are scalar, it is well suited for cross-thread handoff; however,
 * callers must still use safe publication when sharing instances between
 * threads (for example, via a {@code volatile} field, an
 * {@code java.util.concurrent.atomic.AtomicReference}, or another established
 * thread-safe handoff mechanism).</p>
 *
 * @param gainReductionDb the current gain reduction in dB; typically a
 *                        non-positive value (&le; 0), where {@code 0.0} means
 *                        no gain reduction. Not validated — callers that
 *                        publish snapshots are responsible for supplying
 *                        meaningful values.
 * @param inputLevelDb    the input level of the detection-source signal in
 *                        dBFS, or {@link Double#NEGATIVE_INFINITY} when not
 *                        available
 * @param outputLevelDb   the output level after processing in dBFS, or
 *                        {@link Double#NEGATIVE_INFINITY} when not available
 */
public record PluginMeterSnapshot(
        double gainReductionDb,
        double inputLevelDb,
        double outputLevelDb
) {

    /** Snapshot value representing "no activity" — zero reduction, silent I/O. */
    public static final PluginMeterSnapshot SILENT =
            new PluginMeterSnapshot(0.0, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);

    /**
     * Convenience factory that constructs a snapshot carrying only a
     * gain-reduction reading.
     *
     * @param gainReductionDb the current gain reduction in dB (typically &le; 0)
     * @return a snapshot with input/output levels set to
     *         {@link Double#NEGATIVE_INFINITY}
     */
    public static PluginMeterSnapshot ofGainReduction(double gainReductionDb) {
        return new PluginMeterSnapshot(
                gainReductionDb,
                Double.NEGATIVE_INFINITY,
                Double.NEGATIVE_INFINITY);
    }
}
