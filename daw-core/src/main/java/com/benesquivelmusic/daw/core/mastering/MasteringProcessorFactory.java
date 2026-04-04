package com.benesquivelmusic.daw.core.mastering;

import com.benesquivelmusic.daw.core.dsp.BiquadFilter;
import com.benesquivelmusic.daw.core.dsp.CompressorProcessor;
import com.benesquivelmusic.daw.core.dsp.DitherProcessor;
import com.benesquivelmusic.daw.core.dsp.GainStagingProcessor;
import com.benesquivelmusic.daw.core.dsp.LimiterProcessor;
import com.benesquivelmusic.daw.core.dsp.ParametricEqProcessor;
import com.benesquivelmusic.daw.core.dsp.StereoImagerProcessor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.mastering.MasteringStageConfig;
import com.benesquivelmusic.daw.sdk.mastering.MasteringStageType;

import java.util.Map;

/**
 * Factory that creates real DSP processor instances for mastering chain stages.
 *
 * <p>Maps each {@link MasteringStageType} to the corresponding processor from
 * {@code daw-core/dsp}, applying preset parameters:
 * <ul>
 *   <li>{@code GAIN_STAGING} → {@link GainStagingProcessor}</li>
 *   <li>{@code EQ_CORRECTIVE} → {@link ParametricEqProcessor} (high-pass filter)</li>
 *   <li>{@code COMPRESSION} → {@link CompressorProcessor}</li>
 *   <li>{@code EQ_TONAL} → {@link ParametricEqProcessor} (shelf filters)</li>
 *   <li>{@code STEREO_IMAGING} → {@link StereoImagerProcessor}</li>
 *   <li>{@code LIMITING} → {@link LimiterProcessor}</li>
 *   <li>{@code DITHERING} → {@link DitherProcessor}</li>
 * </ul>
 */
public final class MasteringProcessorFactory {

    private MasteringProcessorFactory() {
        // utility class
    }

    /**
     * Creates a real DSP processor for the given mastering stage configuration.
     *
     * @param config     the stage configuration with type and parameters
     * @param channels   the number of audio channels (typically 2 for stereo mastering)
     * @param sampleRate the sample rate in Hz
     * @return a configured audio processor
     * @throws IllegalArgumentException if STEREO_IMAGING is requested with channels ≠ 2
     */
    public static AudioProcessor createProcessor(MasteringStageConfig config,
                                                  int channels, double sampleRate) {
        return switch (config.stageType()) {
            case GAIN_STAGING   -> createGainStaging(config.parameters(), channels);
            case EQ_CORRECTIVE  -> createCorrectiveEq(config.parameters(), channels, sampleRate);
            case COMPRESSION    -> createCompressor(config.parameters(), channels, sampleRate);
            case EQ_TONAL       -> createTonalEq(config.parameters(), channels, sampleRate);
            case STEREO_IMAGING -> createStereoImager(config.parameters(), channels, sampleRate);
            case LIMITING       -> createLimiter(config.parameters(), channels, sampleRate);
            case DITHERING      -> createDitherer(config.parameters(), channels);
        };
    }

    private static AudioProcessor createGainStaging(Map<String, Double> params, int channels) {
        double gainDb = params.getOrDefault("gainDb", 0.0);
        return new GainStagingProcessor(channels, gainDb);
    }

    private static AudioProcessor createCorrectiveEq(Map<String, Double> params,
                                                      int channels, double sampleRate) {
        ParametricEqProcessor eq = new ParametricEqProcessor(channels, sampleRate);
        double highPassHz = params.getOrDefault("highPassHz", 30.0);
        double highPassQ = params.getOrDefault("highPassQ", 0.707);
        eq.addBand(ParametricEqProcessor.BandConfig.of(
                BiquadFilter.FilterType.HIGH_PASS, highPassHz, highPassQ, 0.0));
        return eq;
    }

    private static AudioProcessor createCompressor(Map<String, Double> params,
                                                    int channels, double sampleRate) {
        CompressorProcessor comp = new CompressorProcessor(channels, sampleRate);
        if (params.containsKey("thresholdDb")) comp.setThresholdDb(params.get("thresholdDb"));
        if (params.containsKey("ratio"))       comp.setRatio(params.get("ratio"));
        if (params.containsKey("attackMs"))    comp.setAttackMs(params.get("attackMs"));
        if (params.containsKey("releaseMs"))   comp.setReleaseMs(params.get("releaseMs"));
        if (params.containsKey("kneeDb"))      comp.setKneeDb(params.get("kneeDb"));
        if (params.containsKey("makeupGainDb")) comp.setMakeupGainDb(params.get("makeupGainDb"));
        return comp;
    }

    private static AudioProcessor createTonalEq(Map<String, Double> params,
                                                 int channels, double sampleRate) {
        ParametricEqProcessor eq = new ParametricEqProcessor(channels, sampleRate);
        if (params.containsKey("lowShelfHz")) {
            double freq = params.get("lowShelfHz");
            double gain = params.getOrDefault("lowShelfGainDb", 0.0);
            eq.addBand(ParametricEqProcessor.BandConfig.of(
                    BiquadFilter.FilterType.LOW_SHELF, freq, 0.707, gain));
        }
        if (params.containsKey("highShelfHz")) {
            double freq = params.get("highShelfHz");
            double gain = params.getOrDefault("highShelfGainDb", 0.0);
            eq.addBand(ParametricEqProcessor.BandConfig.of(
                    BiquadFilter.FilterType.HIGH_SHELF, freq, 0.707, gain));
        }
        return eq;
    }

    private static AudioProcessor createStereoImager(Map<String, Double> params,
                                                      int channels, double sampleRate) {
        if (channels != 2) {
            throw new IllegalArgumentException(
                    "STEREO_IMAGING requires exactly 2 channels, got " + channels);
        }
        StereoImagerProcessor imager = new StereoImagerProcessor(sampleRate);
        if (params.containsKey("width")) {
            imager.setWidth(params.get("width"));
        }
        return imager;
    }

    private static AudioProcessor createLimiter(Map<String, Double> params,
                                                 int channels, double sampleRate) {
        LimiterProcessor limiter = new LimiterProcessor(channels, sampleRate);
        if (params.containsKey("ceilingDb"))  limiter.setCeilingDb(params.get("ceilingDb"));
        if (params.containsKey("releaseMs"))  limiter.setReleaseMs(params.get("releaseMs"));
        if (params.containsKey("attackMs"))   limiter.setAttackMs(params.get("attackMs"));
        return limiter;
    }

    private static AudioProcessor createDitherer(Map<String, Double> params, int channels) {
        int bitDepth = params.containsKey("bitDepth")
                ? params.get("bitDepth").intValue()
                : 16;
        return new DitherProcessor(channels, bitDepth);
    }
}
