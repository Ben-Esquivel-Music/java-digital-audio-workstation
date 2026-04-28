package com.benesquivelmusic.daw.sdk.export;

import java.util.Objects;

/**
 * Describes the format and filename of the mastered stereo render in a
 * deliverable bundle.
 *
 * <p>Used by {@link DeliverableBundle} to specify how the master mix should
 * be encoded (WAV/FLAC/etc.) and what filename it should receive inside the
 * output zip.</p>
 *
 * @param audioConfig the audio export configuration (format, sample rate,
 *                    bit depth, dithering, metadata) for the master
 * @param baseName    the base filename (without extension) for the master
 *                    file inside the zip; the file extension is taken from
 *                    {@link AudioExportConfig#format()}
 */
public record MasterFormat(AudioExportConfig audioConfig, String baseName) {

    public MasterFormat {
        Objects.requireNonNull(audioConfig, "audioConfig must not be null");
        Objects.requireNonNull(baseName, "baseName must not be null");
        if (baseName.isBlank()) {
            throw new IllegalArgumentException("baseName must not be blank");
        }
    }
}
