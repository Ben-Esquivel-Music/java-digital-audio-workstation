package com.benesquivelmusic.daw.sdk.export;

import java.util.Objects;

/**
 * Specifies a single stem in a deliverable bundle: which project track to
 * render, the filename to use inside the zip, and the audio export
 * configuration (format, sample rate, bit depth) for that stem.
 *
 * <p>A bundle typically carries one {@code StemSpec} per logical stem
 * (drums, bass, keys, vocals, FX). All stems may share the same
 * {@link AudioExportConfig} (e.g., 24-bit/96 kHz WAV) or each may have its
 * own format if the deliverable spec calls for it.</p>
 *
 * @param trackIndex  the index of the track to render (into the project's
 *                    track list)
 * @param stemName    the human-readable stem name used as the base
 *                    filename (without extension) inside the zip
 * @param audioConfig the audio export configuration for this stem
 */
public record StemSpec(int trackIndex, String stemName, AudioExportConfig audioConfig) {

    public StemSpec {
        Objects.requireNonNull(stemName, "stemName must not be null");
        Objects.requireNonNull(audioConfig, "audioConfig must not be null");
        if (trackIndex < 0) {
            throw new IllegalArgumentException(
                    "trackIndex must not be negative: " + trackIndex);
        }
        if (stemName.isBlank()) {
            throw new IllegalArgumentException("stemName must not be blank");
        }
    }
}
