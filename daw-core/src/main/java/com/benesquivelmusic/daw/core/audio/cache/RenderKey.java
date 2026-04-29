package com.benesquivelmusic.daw.core.audio.cache;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Cache key identifying a single rendered (frozen) track.
 *
 * <p>The key is the tuple <em>(trackDspHash, sessionSampleRate,
 * bitDepth)</em>. {@code trackDspHash} is a lowercase hex SHA-256
 * digest of every input that affects the rendered audio (insert chain
 * identities, parameter values, automation curves, source-clip
 * content hashes, send configuration, and the project tempo /
 * time-signature at the track's range — see {@link
 * TrackDspHasher}). Any change to those inputs produces a different
 * hash and therefore a cache miss; this is the cache's only
 * invalidation mechanism.</p>
 *
 * <p>The {@link #toFileName()} method maps a key to the
 * {@code <renderKey>.pcm} filename used by {@link RenderedTrackCache}.</p>
 *
 * @param trackDspHash       lowercase 64-character hex SHA-256 digest
 *                           of the track's DSP-relevant state
 * @param sessionSampleRate  the session sample rate in Hz; positive
 * @param bitDepth           the rendered bit depth (e.g. 16, 24, 32);
 *                           positive
 */
public record RenderKey(String trackDspHash, int sessionSampleRate, int bitDepth) {

    /** Length of a hex-encoded SHA-256 digest. */
    public static final int HASH_LENGTH = 64;

    private static final Pattern HEX_64 = Pattern.compile("[0-9a-f]{64}");

    public RenderKey {
        Objects.requireNonNull(trackDspHash, "trackDspHash must not be null");
        if (!HEX_64.matcher(trackDspHash).matches()) {
            throw new IllegalArgumentException(
                    "trackDspHash must be a 64-character lowercase hex SHA-256 digest: "
                            + trackDspHash);
        }
        if (sessionSampleRate <= 0) {
            throw new IllegalArgumentException(
                    "sessionSampleRate must be positive: " + sessionSampleRate);
        }
        if (bitDepth <= 0) {
            throw new IllegalArgumentException(
                    "bitDepth must be positive: " + bitDepth);
        }
    }

    /** First two characters of the digest — the on-disk shard directory name. */
    public String hashPrefix() {
        return trackDspHash.substring(0, 2);
    }

    /**
     * Returns the canonical file name for this key (without any
     * directory portion). The filename embeds the hash and rendering
     * parameters so two keys that differ only in sample rate or bit
     * depth do not collide on disk.
     *
     * @return a deterministic filename ending in {@code .pcm}
     */
    public String toFileName() {
        return trackDspHash + "_" + sessionSampleRate + "_" + bitDepth + ".pcm";
    }
}
