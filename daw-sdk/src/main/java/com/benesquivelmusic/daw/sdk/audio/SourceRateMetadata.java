package com.benesquivelmusic.daw.sdk.audio;

import java.util.Objects;

/**
 * Metadata describing the <em>native</em> sample rate of an audio clip,
 * preserved independently of the session rate.
 *
 * <p>The audio engine historically assumed every source was already at
 * the session rate and relied on the importer to convert on import —
 * which loses quality when the user later changes the session rate.
 * Storing the source's native rate on the clip lets the render pipeline
 * perform just-in-time SRC via {@link SampleRateConverter} every time
 * the clip is rendered, so the same project can be played back correctly
 * at any session rate.</p>
 *
 * <p>Projects saved by older versions of the DAW do not carry this
 * metadata; the engine must treat a {@code null} value as "native rate
 * equals session rate" (legacy behavior) and optionally emit a one-shot
 * warning if clip durations suggest a rate mismatch.</p>
 *
 * @param nativeRateHz    the clip's native sample rate in Hz
 *                        (must be positive)
 * @param channels        the clip's channel count (must be positive)
 * @param framesPerChannel the clip's length at the native rate in
 *                        sample frames; must be non-negative
 *                        ({@code 0} = unknown)
 */
public record SourceRateMetadata(int nativeRateHz, int channels, long framesPerChannel) {

    /** Validates ranges. */
    public SourceRateMetadata {
        if (nativeRateHz <= 0) {
            throw new IllegalArgumentException(
                    "nativeRateHz must be positive: " + nativeRateHz);
        }
        if (channels <= 0) {
            throw new IllegalArgumentException(
                    "channels must be positive: " + channels);
        }
        if (framesPerChannel < 0) {
            throw new IllegalArgumentException(
                    "framesPerChannel must be non-negative: " + framesPerChannel);
        }
    }

    /**
     * Creates metadata for a source whose length in frames is unknown
     * or irrelevant (e.g. a streamed source).
     *
     * @param nativeRateHz the native rate in Hz
     * @param channels     the channel count
     * @return a new {@code SourceRateMetadata} with
     *         {@code framesPerChannel == 0}
     */
    public static SourceRateMetadata of(int nativeRateHz, int channels) {
        return new SourceRateMetadata(nativeRateHz, channels, 0L);
    }

    /**
     * Convenience factory that resolves the native rate from a
     * {@link SampleRate} enum.
     */
    public static SourceRateMetadata of(SampleRate rate, int channels, long framesPerChannel) {
        Objects.requireNonNull(rate, "rate must not be null");
        return new SourceRateMetadata(rate.getHz(), channels, framesPerChannel);
    }

    /**
     * Returns {@code true} if the native rate differs from the given
     * session rate — the condition under which a just-in-time SRC is
     * required.
     *
     * @param sessionRateHz the active session sample rate in Hz
     * @return whether SRC is needed
     */
    public boolean requiresConversion(int sessionRateHz) {
        if (sessionRateHz <= 0) {
            throw new IllegalArgumentException(
                    "sessionRateHz must be positive: " + sessionRateHz);
        }
        return sessionRateHz != nativeRateHz;
    }

    /**
     * Returns the native duration of the source in seconds, or
     * {@code 0.0} if {@code framesPerChannel} is unknown.
     */
    public double durationSeconds() {
        return framesPerChannel == 0L ? 0.0 : (double) framesPerChannel / nativeRateHz;
    }

    /**
     * Produces a short, user-facing badge label describing the
     * conversion the engine performs — e.g. {@code "↻ 48→44.1"} when
     * resampling a 48&nbsp;kHz clip into a 44.1&nbsp;kHz session. When
     * the two rates match, returns an empty string.
     *
     * @param sessionRateHz the active session rate
     * @return the badge text, or empty if no conversion is applied
     */
    public String badgeLabel(int sessionRateHz) {
        if (!requiresConversion(sessionRateHz)) {
            return "";
        }
        return "↻ " + formatKhz(nativeRateHz) + "→" + formatKhz(sessionRateHz);
    }

    private static String formatKhz(int hz) {
        double khz = hz / 1000.0;
        // Strip trailing .0 for whole-number kHz values (44.1 stays, 48 shows as "48").
        return (khz == Math.floor(khz))
                ? Integer.toString((int) khz)
                : String.format(java.util.Locale.ROOT, "%.1f", khz);
    }
}
