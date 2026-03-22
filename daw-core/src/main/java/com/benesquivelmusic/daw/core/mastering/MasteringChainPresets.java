package com.benesquivelmusic.daw.core.mastering;

import com.benesquivelmusic.daw.sdk.mastering.MasteringChainPreset;
import com.benesquivelmusic.daw.sdk.mastering.MasteringStageConfig;
import com.benesquivelmusic.daw.sdk.mastering.MasteringStageType;

import java.util.List;
import java.util.Map;

/**
 * Factory providing genre-specific default mastering chain presets.
 *
 * <p>Each preset defines the standard mastering chain order (gain staging →
 * corrective EQ → compression → tonal EQ → stereo imaging → limiting →
 * dithering) with genre-appropriate starting parameters.</p>
 *
 * <p>These presets serve as starting points for mastering engineers —
 * not as final settings. Parameters should be adjusted to suit each mix.</p>
 */
public final class MasteringChainPresets {

    private MasteringChainPresets() {
        // utility class
    }

    /**
     * Returns a mastering preset tuned for Pop and EDM.
     *
     * <p>Characteristics: moderate compression, wide stereo imaging,
     * bright top-end, aggressive limiting for loudness.</p>
     */
    public static MasteringChainPreset popEdm() {
        return new MasteringChainPreset("Pop/EDM Master", "Pop/EDM", List.of(
                MasteringStageConfig.of(MasteringStageType.GAIN_STAGING, "Input Gain",
                        Map.of("gainDb", 0.0)),
                MasteringStageConfig.of(MasteringStageType.EQ_CORRECTIVE, "Corrective EQ",
                        Map.of("highPassHz", 30.0, "highPassQ", 0.707)),
                MasteringStageConfig.of(MasteringStageType.COMPRESSION, "Glue Compression",
                        Map.of("thresholdDb", -16.0, "ratio", 3.0,
                                "attackMs", 15.0, "releaseMs", 150.0, "kneeDb", 6.0)),
                MasteringStageConfig.of(MasteringStageType.EQ_TONAL, "Tonal EQ",
                        Map.of("lowShelfHz", 80.0, "lowShelfGainDb", 1.5,
                                "highShelfHz", 12000.0, "highShelfGainDb", 2.0)),
                MasteringStageConfig.of(MasteringStageType.STEREO_IMAGING, "Stereo Width",
                        Map.of("width", 1.3)),
                MasteringStageConfig.of(MasteringStageType.LIMITING, "Loudness Limiter",
                        Map.of("ceilingDb", -0.3, "releaseMs", 50.0)),
                MasteringStageConfig.of(MasteringStageType.DITHERING, "Dither to 16-bit",
                        Map.of("bitDepth", 16.0))
        ));
    }

    /**
     * Returns a mastering preset tuned for Rock.
     *
     * <p>Characteristics: heavier compression, punchy low-mids,
     * controlled high-end, moderate stereo width.</p>
     */
    public static MasteringChainPreset rock() {
        return new MasteringChainPreset("Rock Master", "Rock", List.of(
                MasteringStageConfig.of(MasteringStageType.GAIN_STAGING, "Input Gain",
                        Map.of("gainDb", 0.0)),
                MasteringStageConfig.of(MasteringStageType.EQ_CORRECTIVE, "Corrective EQ",
                        Map.of("highPassHz", 25.0, "highPassQ", 0.707)),
                MasteringStageConfig.of(MasteringStageType.COMPRESSION, "Bus Compression",
                        Map.of("thresholdDb", -18.0, "ratio", 4.0,
                                "attackMs", 10.0, "releaseMs", 100.0, "kneeDb", 4.0)),
                MasteringStageConfig.of(MasteringStageType.EQ_TONAL, "Tonal EQ",
                        Map.of("lowShelfHz", 100.0, "lowShelfGainDb", 2.0,
                                "highShelfHz", 10000.0, "highShelfGainDb", 1.0)),
                MasteringStageConfig.of(MasteringStageType.STEREO_IMAGING, "Stereo Width",
                        Map.of("width", 1.1)),
                MasteringStageConfig.of(MasteringStageType.LIMITING, "Limiter",
                        Map.of("ceilingDb", -0.5, "releaseMs", 60.0)),
                MasteringStageConfig.of(MasteringStageType.DITHERING, "Dither to 16-bit",
                        Map.of("bitDepth", 16.0))
        ));
    }

    /**
     * Returns a mastering preset tuned for Jazz and Classical.
     *
     * <p>Characteristics: gentle compression, natural dynamics,
     * subtle stereo imaging, transparent limiting.</p>
     */
    public static MasteringChainPreset jazzClassical() {
        return new MasteringChainPreset("Jazz/Classical Master", "Jazz/Classical", List.of(
                MasteringStageConfig.of(MasteringStageType.GAIN_STAGING, "Input Gain",
                        Map.of("gainDb", 0.0)),
                MasteringStageConfig.of(MasteringStageType.EQ_CORRECTIVE, "Corrective EQ",
                        Map.of("highPassHz", 20.0, "highPassQ", 0.5)),
                MasteringStageConfig.of(MasteringStageType.COMPRESSION, "Light Compression",
                        Map.of("thresholdDb", -12.0, "ratio", 2.0,
                                "attackMs", 25.0, "releaseMs", 200.0, "kneeDb", 10.0)),
                MasteringStageConfig.of(MasteringStageType.EQ_TONAL, "Tonal EQ",
                        Map.of("lowShelfHz", 60.0, "lowShelfGainDb", 0.5,
                                "highShelfHz", 15000.0, "highShelfGainDb", 0.5)),
                MasteringStageConfig.of(MasteringStageType.STEREO_IMAGING, "Stereo Width",
                        Map.of("width", 1.0)),
                MasteringStageConfig.of(MasteringStageType.LIMITING, "Transparent Limiter",
                        Map.of("ceilingDb", -0.3, "releaseMs", 100.0)),
                MasteringStageConfig.of(MasteringStageType.DITHERING, "Dither to 16-bit",
                        Map.of("bitDepth", 16.0))
        ));
    }

    /**
     * Returns a mastering preset tuned for Hip-Hop and R&amp;B.
     *
     * <p>Characteristics: deep bass emphasis, warm mids,
     * wide stereo image, tight limiting.</p>
     */
    public static MasteringChainPreset hipHopRnB() {
        return new MasteringChainPreset("Hip-Hop/R&B Master", "Hip-Hop/R&B", List.of(
                MasteringStageConfig.of(MasteringStageType.GAIN_STAGING, "Input Gain",
                        Map.of("gainDb", 0.0)),
                MasteringStageConfig.of(MasteringStageType.EQ_CORRECTIVE, "Corrective EQ",
                        Map.of("highPassHz", 25.0, "highPassQ", 0.707)),
                MasteringStageConfig.of(MasteringStageType.COMPRESSION, "Punch Compression",
                        Map.of("thresholdDb", -15.0, "ratio", 3.5,
                                "attackMs", 12.0, "releaseMs", 120.0, "kneeDb", 6.0)),
                MasteringStageConfig.of(MasteringStageType.EQ_TONAL, "Tonal EQ",
                        Map.of("lowShelfHz", 60.0, "lowShelfGainDb", 3.0,
                                "highShelfHz", 10000.0, "highShelfGainDb", 1.5)),
                MasteringStageConfig.of(MasteringStageType.STEREO_IMAGING, "Stereo Width",
                        Map.of("width", 1.2)),
                MasteringStageConfig.of(MasteringStageType.LIMITING, "Loudness Limiter",
                        Map.of("ceilingDb", -0.3, "releaseMs", 40.0)),
                MasteringStageConfig.of(MasteringStageType.DITHERING, "Dither to 16-bit",
                        Map.of("bitDepth", 16.0))
        ));
    }

    /**
     * Returns all built-in genre presets.
     *
     * @return an unmodifiable list of all default presets
     */
    public static List<MasteringChainPreset> allDefaults() {
        return List.of(popEdm(), rock(), jazzClassical(), hipHopRnB());
    }
}
