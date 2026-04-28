package com.benesquivelmusic.daw.core.export.aaf;

import java.util.List;
import java.util.Objects;

/**
 * Pre-composed timeline ready to be serialised by {@link AafWriter} or
 * {@code OmfWriter}. The composition has been flattened to samples at a
 * single {@link #sampleRate()} and labelled with a single
 * {@link #frameRate()}; downstream tools see a consistent timeline
 * regardless of how the project was authored.
 *
 * @param compositionName the AAF {@code CompositionMob} name
 * @param sampleRate      the audio sample rate (Hz)
 * @param frameRate       the timeline frame rate
 * @param startTimecode   user-configured start of timeline (e.g.
 *                        {@code 01:00:00:00} per film convention)
 * @param totalLengthSamples the total length of the composition timeline
 * @param clips           every source-clip placement on the composition
 *                        (sorted by track then start position)
 */
public record AafComposition(String compositionName,
                             int sampleRate,
                             AafFrameRate frameRate,
                             AafTimecode startTimecode,
                             long totalLengthSamples,
                             List<AafSourceClip> clips) {

    public AafComposition {
        Objects.requireNonNull(compositionName, "compositionName must not be null");
        Objects.requireNonNull(frameRate, "frameRate must not be null");
        Objects.requireNonNull(startTimecode, "startTimecode must not be null");
        Objects.requireNonNull(clips, "clips must not be null");
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (totalLengthSamples < 0) {
            throw new IllegalArgumentException(
                    "totalLengthSamples must be >= 0: " + totalLengthSamples);
        }
        if (startTimecode.frameRate() != frameRate) {
            throw new IllegalArgumentException(
                    "startTimecode frame rate (" + startTimecode.frameRate()
                            + ") does not match composition frame rate (" + frameRate + ")");
        }
        clips = List.copyOf(clips);
    }
}
