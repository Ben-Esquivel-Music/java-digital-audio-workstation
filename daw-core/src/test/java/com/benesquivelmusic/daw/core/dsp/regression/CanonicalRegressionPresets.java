package com.benesquivelmusic.daw.core.dsp.regression;

import com.benesquivelmusic.daw.core.dsp.CompressorProcessor;

/**
 * Static initializer that registers the canonical
 * {@link DspRegressionPreset#DEFAULT}, {@link DspRegressionPreset#AGGRESSIVE},
 * and {@link DspRegressionPreset#SUBTLE} presets for every regression-tested
 * processor.
 *
 * <p>This class is referenced from a {@code static} block in the regression
 * coverage test so that registration is guaranteed to have run by the time
 * any case executes — no test ordering hazard.</p>
 */
final class CanonicalRegressionPresets {

    private CanonicalRegressionPresets() {}

    /**
     * Registers all canonical presets. Idempotent — safe to invoke from
     * multiple {@code static} initializers.
     */
    static void registerAll() {
        registerCompressorPresets();
    }

    private static void registerCompressorPresets() {
        DspRegressionPreset.register(CompressorProcessor.class,
                DspRegressionPreset.DEFAULT,
                p -> {
                    // Shipping defaults — the constructor already applies them,
                    // but we set them explicitly so the preset is robust to
                    // future default-value changes.
                    p.setThresholdDb(-20.0);
                    p.setRatio(4.0);
                    p.setAttackMs(10.0);
                    p.setReleaseMs(100.0);
                    p.setKneeDb(6.0);
                    p.setMakeupGainDb(0.0);
                    p.setDetectionMode(CompressorProcessor.DetectionMode.PEAK);
                    return p;
                });

        DspRegressionPreset.register(CompressorProcessor.class,
                DspRegressionPreset.AGGRESSIVE,
                p -> {
                    // "Slamming" preset — high ratio, low threshold, fast attack.
                    p.setThresholdDb(-30.0);
                    p.setRatio(10.0);
                    p.setAttackMs(0.5);
                    p.setReleaseMs(50.0);
                    p.setKneeDb(0.0);
                    p.setMakeupGainDb(6.0);
                    p.setDetectionMode(CompressorProcessor.DetectionMode.PEAK);
                    return p;
                });

        DspRegressionPreset.register(CompressorProcessor.class,
                DspRegressionPreset.SUBTLE,
                p -> {
                    // "Glue" preset — gentle ratio, high threshold, slow attack.
                    p.setThresholdDb(-12.0);
                    p.setRatio(1.5);
                    p.setAttackMs(30.0);
                    p.setReleaseMs(250.0);
                    p.setKneeDb(12.0);
                    p.setMakeupGainDb(1.0);
                    p.setDetectionMode(CompressorProcessor.DetectionMode.RMS);
                    return p;
                });
    }
}
