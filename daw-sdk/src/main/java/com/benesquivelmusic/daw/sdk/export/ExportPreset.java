package com.benesquivelmusic.daw.sdk.export;

import java.util.Objects;

/**
 * Named presets for common audio export targets.
 *
 * <p>Each preset encapsulates a display name and an {@link AudioExportConfig}
 * tailored to a specific distribution channel (CD, streaming, hi-res archive,
 * vinyl pre-master).</p>
 *
 * @param name   a human-readable preset name (e.g., "CD Quality")
 * @param config the export configuration for this preset
 */
public record ExportPreset(String name, AudioExportConfig config) {

    public ExportPreset {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(config, "config must not be null");
    }

    /** CD quality: 44.1 kHz, 16-bit WAV with TPDF dithering. */
    public static final ExportPreset CD = new ExportPreset(
            "CD Quality",
            new AudioExportConfig(AudioExportFormat.WAV, 44_100, 16, DitherType.TPDF)
    );

    /** Streaming: 44.1 kHz, 16-bit FLAC with TPDF dithering. */
    public static final ExportPreset STREAMING = new ExportPreset(
            "Streaming",
            new AudioExportConfig(AudioExportFormat.FLAC, 44_100, 16, DitherType.TPDF)
    );

    /** Hi-Res archive: 96 kHz, 24-bit FLAC, no dithering needed. */
    public static final ExportPreset HI_RES = new ExportPreset(
            "Hi-Res",
            new AudioExportConfig(AudioExportFormat.FLAC, 96_000, 24, DitherType.NONE)
    );

    /** Vinyl pre-master: 96 kHz, 24-bit WAV, no dithering needed. */
    public static final ExportPreset VINYL = new ExportPreset(
            "Vinyl Pre-Master",
            new AudioExportConfig(AudioExportFormat.WAV, 96_000, 24, DitherType.NONE)
    );

    /** Spotify: 44.1 kHz, 16-bit WAV with TPDF dithering (target −14 LUFS). */
    public static final ExportPreset SPOTIFY = new ExportPreset(
            "Spotify",
            new AudioExportConfig(AudioExportFormat.WAV, 44_100, 16, DitherType.TPDF)
    );

    /** Apple Music: 44.1 kHz, 16-bit WAV with noise-shaped dithering (target −16 LUFS). */
    public static final ExportPreset APPLE_MUSIC = new ExportPreset(
            "Apple Music",
            new AudioExportConfig(AudioExportFormat.WAV, 44_100, 16, DitherType.NOISE_SHAPED)
    );
}
