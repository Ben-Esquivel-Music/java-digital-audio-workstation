package com.benesquivelmusic.daw.core.export.aaf;

import java.util.Objects;
import java.util.UUID;

/**
 * A single source clip placement on the AAF timeline.
 *
 * <p>This is the cross-format data-model record used by both the
 * AAF&nbsp;1.2 and OMF&nbsp;2.0 writers. All time fields are in
 * <em>samples</em> at the project sample rate so that round-trip
 * comparisons are exact: beats are converted to samples once, before the
 * clip enters this record.</p>
 *
 * @param sourceMobId           unique source-mob identifier (an AAF
 *                              {@code SourceMob} maps to a real-world
 *                              piece of media)
 * @param sourceFile            absolute or relative path to the source
 *                              audio file (the {@code SourceMob}'s
 *                              {@code FileDescriptor.URLString})
 * @param sourceName            display name for the source (e.g. clip
 *                              file name)
 * @param trackIndex            zero-based timeline track index
 * @param trackName             display name of the track
 * @param startSample           clip start position on the composition
 *                              timeline, in samples
 * @param lengthSamples         clip length on the timeline, in samples
 * @param sourceOffsetSamples   offset into the source media at which
 *                              the clip starts playing
 * @param gainDb                per-clip gain in dB (0.0 == unity)
 * @param fadeInSamples         length of the fade-in in samples
 *                              (0 == no fade-in)
 * @param fadeInCurve           shape of the fade-in
 * @param fadeOutSamples        length of the fade-out in samples
 *                              (0 == no fade-out)
 * @param fadeOutCurve          shape of the fade-out
 */
public record AafSourceClip(UUID sourceMobId,
                            String sourceFile,
                            String sourceName,
                            int trackIndex,
                            String trackName,
                            long startSample,
                            long lengthSamples,
                            long sourceOffsetSamples,
                            double gainDb,
                            long fadeInSamples,
                            AafFadeCurve fadeInCurve,
                            long fadeOutSamples,
                            AafFadeCurve fadeOutCurve) {

    public AafSourceClip {
        Objects.requireNonNull(sourceMobId, "sourceMobId must not be null");
        Objects.requireNonNull(sourceName, "sourceName must not be null");
        Objects.requireNonNull(trackName, "trackName must not be null");
        Objects.requireNonNull(fadeInCurve, "fadeInCurve must not be null");
        Objects.requireNonNull(fadeOutCurve, "fadeOutCurve must not be null");
        if (trackIndex < 0)         throw new IllegalArgumentException("trackIndex must be >= 0: " + trackIndex);
        if (startSample < 0)        throw new IllegalArgumentException("startSample must be >= 0: " + startSample);
        if (lengthSamples <= 0)     throw new IllegalArgumentException("lengthSamples must be > 0: " + lengthSamples);
        if (sourceOffsetSamples < 0) throw new IllegalArgumentException("sourceOffsetSamples must be >= 0: " + sourceOffsetSamples);
        if (fadeInSamples < 0)      throw new IllegalArgumentException("fadeInSamples must be >= 0: " + fadeInSamples);
        if (fadeOutSamples < 0)     throw new IllegalArgumentException("fadeOutSamples must be >= 0: " + fadeOutSamples);
        if (fadeInSamples + fadeOutSamples > lengthSamples) {
            throw new IllegalArgumentException(
                    "fade lengths exceed clip length (" + fadeInSamples + " + " + fadeOutSamples
                            + " > " + lengthSamples + ")");
        }
    }
}
