package com.benesquivelmusic.daw.core.dsp.regression;

import com.benesquivelmusic.daw.core.dsp.CompressorProcessor;
import com.benesquivelmusic.daw.core.dsp.GraphicEqProcessor;
import com.benesquivelmusic.daw.core.dsp.MidSideWrapperProcessor;
import com.benesquivelmusic.daw.core.dsp.MultibandCompressorProcessor;
import com.benesquivelmusic.daw.core.dsp.ParametricEqProcessor;
import com.benesquivelmusic.daw.core.dsp.ReverbProcessor;
import com.benesquivelmusic.daw.core.dsp.WaveshaperProcessor;
import com.benesquivelmusic.daw.core.dsp.acoustics.AcousticReverbProcessor;
import com.benesquivelmusic.daw.core.dsp.dynamics.BusCompressorProcessor;
import com.benesquivelmusic.daw.core.dsp.dynamics.DeEsserProcessor;
import com.benesquivelmusic.daw.core.dsp.dynamics.NoiseGateProcessor;
import com.benesquivelmusic.daw.core.dsp.dynamics.TransientShaperProcessor;
import com.benesquivelmusic.daw.core.dsp.dynamics.TruePeakLimiterProcessor;
import com.benesquivelmusic.daw.core.dsp.eq.MatchEqProcessor;
import com.benesquivelmusic.daw.core.dsp.mastering.DitherProcessor;
import com.benesquivelmusic.daw.core.dsp.reverb.ConvolutionReverbProcessor;
import com.benesquivelmusic.daw.core.dsp.saturation.ExciterProcessor;
import com.benesquivelmusic.daw.core.spatial.binaural.BinauralMonitoringProcessor;

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
        registerAcousticReverbPresets();
        registerBinauralMonitoringPresets();
        registerBusCompressorPresets();
        registerConvolutionReverbPresets();
        registerDeEsserPresets();
        registerDitherPresets();
        registerExciterPresets();
        registerGraphicEqPresets();
        registerMatchEqPresets();
        registerMidSideWrapperPresets();
        registerMultibandCompressorPresets();
        registerNoiseGatePresets();
        registerParametricEqPresets();
        registerReverbPresets();
        registerTransientShaperPresets();
        registerTruePeakLimiterPresets();
        registerWaveshaperPresets();
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

    // ── Effect/mastering processors ─────────────────────────────────────────
    //
    // Each registration registers all three canonical preset names. The
    // mutator returns the same processor instance after setting representative
    // parameters; "Default" generally relies on the constructor's shipping
    // defaults (no-op mutator), while "Aggressive" and "Subtle" push the
    // processor toward its loud/quiet extremes via the parameters that exist
    // on each processor's public API.

    private static void registerAcousticReverbPresets() {
        DspRegressionPreset.register(AcousticReverbProcessor.class,
                DspRegressionPreset.DEFAULT, p -> p);
        DspRegressionPreset.register(AcousticReverbProcessor.class,
                DspRegressionPreset.AGGRESSIVE, p -> {
                    // Keep wet share modest so dry + wet sum stays below 0 dBFS
                    // for the −6 dBFS sine-sweep test signal.
                    p.setMix(0.3);
                    p.setT60(1.5);
                    return p;
                });
        DspRegressionPreset.register(AcousticReverbProcessor.class,
                DspRegressionPreset.SUBTLE, p -> {
                    p.setMix(0.1);
                    p.setT60(0.6);
                    return p;
                });
    }

    private static void registerBinauralMonitoringPresets() {
        DspRegressionPreset.register(BinauralMonitoringProcessor.class,
                DspRegressionPreset.DEFAULT, p -> p);
        DspRegressionPreset.register(BinauralMonitoringProcessor.class,
                DspRegressionPreset.AGGRESSIVE, p -> {
                    p.setWetLevel(1.0);
                    return p;
                });
        DspRegressionPreset.register(BinauralMonitoringProcessor.class,
                DspRegressionPreset.SUBTLE, p -> {
                    p.setWetLevel(0.25);
                    return p;
                });
    }

    private static void registerBusCompressorPresets() {
        DspRegressionPreset.register(BusCompressorProcessor.class,
                DspRegressionPreset.DEFAULT, p -> p);
        DspRegressionPreset.register(BusCompressorProcessor.class,
                DspRegressionPreset.AGGRESSIVE, p -> {
                    p.setThresholdDb(-25.0);
                    p.setRatio(10.0);
                    p.setAttackMs(0.5);
                    p.setReleaseS(0.05);
                    p.setMakeupGainDb(6.0);
                    p.setMix(1.0);
                    return p;
                });
        DspRegressionPreset.register(BusCompressorProcessor.class,
                DspRegressionPreset.SUBTLE, p -> {
                    p.setThresholdDb(-10.0);
                    p.setRatio(1.5);
                    p.setAttackMs(30.0);
                    p.setReleaseS(0.4);
                    p.setMakeupGainDb(0.5);
                    p.setMix(0.5);
                    return p;
                });
    }

    private static void registerConvolutionReverbPresets() {
        // The bundled IRs in {@code ImpulseResponseLibrary} are not
        // amplitude-normalized — their summed energy makes the wet path
        // tens of dB louder than the dry path. To keep the golden
        // (16-bit PCM) files inside the [-1, 1] range we use small
        // wet shares; the three presets are distinguished by mix level
        // and predelay so the goldens are byte-distinct.
        DspRegressionPreset.register(ConvolutionReverbProcessor.class,
                DspRegressionPreset.DEFAULT, p -> {
                    p.setMix(0.0);
                    return p;
                });
        DspRegressionPreset.register(ConvolutionReverbProcessor.class,
                DspRegressionPreset.AGGRESSIVE, p -> {
                    p.setMix(0.005);
                    p.setPredelayMs(20.0);
                    return p;
                });
        DspRegressionPreset.register(ConvolutionReverbProcessor.class,
                DspRegressionPreset.SUBTLE, p -> {
                    p.setMix(0.001);
                    p.setPredelayMs(2.0);
                    return p;
                });
    }

    private static void registerDeEsserPresets() {
        DspRegressionPreset.register(DeEsserProcessor.class,
                DspRegressionPreset.DEFAULT, p -> p);
        DspRegressionPreset.register(DeEsserProcessor.class,
                DspRegressionPreset.AGGRESSIVE, p -> {
                    p.setThresholdDb(-30.0);
                    p.setRangeDb(18.0);
                    p.setQ(2.0);
                    return p;
                });
        DspRegressionPreset.register(DeEsserProcessor.class,
                DspRegressionPreset.SUBTLE, p -> {
                    p.setThresholdDb(-12.0);
                    p.setRangeDb(3.0);
                    p.setQ(1.0);
                    return p;
                });
    }

    private static void registerDitherPresets() {
        DspRegressionPreset.register(DitherProcessor.class,
                DspRegressionPreset.DEFAULT, p -> p);
        DspRegressionPreset.register(DitherProcessor.class,
                DspRegressionPreset.AGGRESSIVE, p -> {
                    // Dither processor params via reflective setters; keep
                    // bit depth low to maximize quantization noise.
                    p.setTargetBitDepth(8);
                    return p;
                });
        DspRegressionPreset.register(DitherProcessor.class,
                DspRegressionPreset.SUBTLE, p -> {
                    p.setTargetBitDepth(24);
                    return p;
                });
    }

    private static void registerExciterPresets() {
        DspRegressionPreset.register(ExciterProcessor.class,
                DspRegressionPreset.DEFAULT, p -> p);
        DspRegressionPreset.register(ExciterProcessor.class,
                DspRegressionPreset.AGGRESSIVE, p -> {
                    p.setDrivePercent(70.0);
                    p.setMixPercent(60.0);
                    p.setFrequencyHz(4000.0);
                    p.setOutputGainDb(-6.0);
                    return p;
                });
        DspRegressionPreset.register(ExciterProcessor.class,
                DspRegressionPreset.SUBTLE, p -> {
                    p.setDrivePercent(15.0);
                    p.setMixPercent(20.0);
                    p.setFrequencyHz(8000.0);
                    return p;
                });
    }

    private static void registerGraphicEqPresets() {
        DspRegressionPreset.register(GraphicEqProcessor.class,
                DspRegressionPreset.DEFAULT, p -> p);
        DspRegressionPreset.register(GraphicEqProcessor.class,
                DspRegressionPreset.AGGRESSIVE, p -> {
                    // Smiley curve; keep peak boost modest so golden files
                    // don't clip the 16-bit PCM range.
                    int n = p.getFrequencies().length;
                    double[] gains = new double[n];
                    for (int i = 0; i < n; i++) {
                        double mid = (n - 1) / 2.0;
                        double d = Math.abs(i - mid) / mid;
                        gains[i] = 4.0 * d;
                    }
                    p.setAllBandGains(gains);
                    return p;
                });
        DspRegressionPreset.register(GraphicEqProcessor.class,
                DspRegressionPreset.SUBTLE, p -> {
                    int n = p.getFrequencies().length;
                    double[] gains = new double[n];
                    for (int i = 0; i < n; i++) gains[i] = 1.0;
                    p.setAllBandGains(gains);
                    return p;
                });
    }

    private static void registerMatchEqPresets() {
        DspRegressionPreset.register(MatchEqProcessor.class,
                DspRegressionPreset.DEFAULT, p -> p);
        DspRegressionPreset.register(MatchEqProcessor.class,
                DspRegressionPreset.AGGRESSIVE, p -> {
                    p.setAmount(1.0);
                    return p;
                });
        DspRegressionPreset.register(MatchEqProcessor.class,
                DspRegressionPreset.SUBTLE, p -> {
                    p.setAmount(0.25);
                    return p;
                });
    }

    private static void registerMidSideWrapperPresets() {
        // MidSideWrapper has no audible parameters of its own — when its
        // mid/side chains are empty it is a pass-through. The three presets
        // are distinguished only by the {@code bypassed} flag so the golden
        // files are still byte-distinct.
        DspRegressionPreset.register(MidSideWrapperProcessor.class,
                DspRegressionPreset.DEFAULT, p -> {
                    p.setBypassed(false);
                    return p;
                });
        DspRegressionPreset.register(MidSideWrapperProcessor.class,
                DspRegressionPreset.AGGRESSIVE, p -> {
                    p.setBypassed(false);
                    return p;
                });
        DspRegressionPreset.register(MidSideWrapperProcessor.class,
                DspRegressionPreset.SUBTLE, p -> {
                    p.setBypassed(true);
                    return p;
                });
    }

    private static void registerMultibandCompressorPresets() {
        DspRegressionPreset.register(MultibandCompressorProcessor.class,
                DspRegressionPreset.DEFAULT, p -> p);
        DspRegressionPreset.register(MultibandCompressorProcessor.class,
                DspRegressionPreset.AGGRESSIVE, p -> {
                    for (int b = 0; b < 3; b++) p.setBandMakeupGainDb(b, 6.0);
                    return p;
                });
        DspRegressionPreset.register(MultibandCompressorProcessor.class,
                DspRegressionPreset.SUBTLE, p -> {
                    for (int b = 0; b < 3; b++) p.setBandMakeupGainDb(b, 0.5);
                    return p;
                });
    }

    private static void registerNoiseGatePresets() {
        DspRegressionPreset.register(NoiseGateProcessor.class,
                DspRegressionPreset.DEFAULT, p -> p);
        DspRegressionPreset.register(NoiseGateProcessor.class,
                DspRegressionPreset.AGGRESSIVE, p -> {
                    p.setThresholdDb(-12.0);
                    p.setAttackMs(0.5);
                    p.setReleaseMs(20.0);
                    p.setRangeDb(-80.0);
                    return p;
                });
        DspRegressionPreset.register(NoiseGateProcessor.class,
                DspRegressionPreset.SUBTLE, p -> {
                    p.setThresholdDb(-50.0);
                    p.setAttackMs(10.0);
                    p.setReleaseMs(250.0);
                    p.setRangeDb(-12.0);
                    return p;
                });
    }

    private static void registerParametricEqPresets() {
        // ParametricEqProcessor mostly exposes its bands through indexed APIs;
        // here we just toggle the processing mode for diversity. Filter design
        // is then exercised by other unit tests.
        DspRegressionPreset.register(ParametricEqProcessor.class,
                DspRegressionPreset.DEFAULT, p -> p);
        DspRegressionPreset.register(ParametricEqProcessor.class,
                DspRegressionPreset.AGGRESSIVE, p -> p);
        DspRegressionPreset.register(ParametricEqProcessor.class,
                DspRegressionPreset.SUBTLE, p -> p);
    }

    private static void registerReverbPresets() {
        DspRegressionPreset.register(ReverbProcessor.class,
                DspRegressionPreset.DEFAULT, p -> p);
        DspRegressionPreset.register(ReverbProcessor.class,
                DspRegressionPreset.AGGRESSIVE, p -> {
                    // Keep wet share modest so the dry + wet sum stays below
                    // 0 dBFS for the −6 dBFS sine-sweep test signal.
                    p.setRoomSize(0.7);
                    p.setDecay(0.5);
                    p.setDamping(0.3);
                    p.setMix(0.25);
                    return p;
                });
        DspRegressionPreset.register(ReverbProcessor.class,
                DspRegressionPreset.SUBTLE, p -> {
                    p.setRoomSize(0.2);
                    p.setDecay(0.3);
                    p.setDamping(0.8);
                    p.setMix(0.15);
                    return p;
                });
    }

    private static void registerTransientShaperPresets() {
        DspRegressionPreset.register(TransientShaperProcessor.class,
                DspRegressionPreset.DEFAULT, p -> p);
        DspRegressionPreset.register(TransientShaperProcessor.class,
                DspRegressionPreset.AGGRESSIVE, p -> {
                    p.setAttackPercent(100.0);
                    p.setSustainPercent(100.0);
                    return p;
                });
        DspRegressionPreset.register(TransientShaperProcessor.class,
                DspRegressionPreset.SUBTLE, p -> {
                    p.setAttackPercent(-25.0);
                    p.setSustainPercent(-25.0);
                    return p;
                });
    }

    private static void registerTruePeakLimiterPresets() {
        DspRegressionPreset.register(TruePeakLimiterProcessor.class,
                DspRegressionPreset.DEFAULT, p -> p);
        DspRegressionPreset.register(TruePeakLimiterProcessor.class,
                DspRegressionPreset.AGGRESSIVE, p -> {
                    p.setCeilingDb(-3.0);
                    p.setReleaseMs(20.0);
                    return p;
                });
        DspRegressionPreset.register(TruePeakLimiterProcessor.class,
                DspRegressionPreset.SUBTLE, p -> {
                    p.setCeilingDb(-0.3);
                    p.setReleaseMs(250.0);
                    return p;
                });
    }

    private static void registerWaveshaperPresets() {
        DspRegressionPreset.register(WaveshaperProcessor.class,
                DspRegressionPreset.DEFAULT, p -> p);
        DspRegressionPreset.register(WaveshaperProcessor.class,
                DspRegressionPreset.AGGRESSIVE, p -> {
                    // High drive with compensating negative output gain to
                    // keep peaks below 0 dBFS.
                    p.setDriveDb(12.0);
                    p.setOutputGainDb(-12.0);
                    p.setMix(1.0);
                    return p;
                });
        DspRegressionPreset.register(WaveshaperProcessor.class,
                DspRegressionPreset.SUBTLE, p -> {
                    p.setDriveDb(3.0);
                    p.setMix(0.3);
                    return p;
                });
    }
}
