package com.benesquivelmusic.daw.core.audio.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Builder that produces the SHA-256 hex digest used as
 * {@link RenderKey#trackDspHash()}.
 *
 * <p>The hasher is intentionally <em>field-by-field</em>: callers
 * append every piece of DSP-relevant state in a deterministic order
 * (insert chain identities, parameter values, automation curves,
 * source-clip content hashes, send configuration, tempo and
 * time-signature at the track's range). The resulting digest is
 * stable across JVMs and machines for the same input sequence, which
 * is what allows the on-disk cache to recognize a previously rendered
 * track on project reopen.</p>
 *
 * <p>This class is not thread-safe; create one instance per render
 * key computation.</p>
 *
 * <pre>{@code
 * RenderKey key = new RenderKey(
 *     new TrackDspHasher()
 *         .addInsert("plug.eq", 3)
 *         .addParameter("gain", 0.5)
 *         .addClipContent("clip-1", "abc123…")
 *         .addTempo(120.0)
 *         .addTimeSignature(4, 4)
 *         .addRange(0, 44_100)
 *         .digestHex(),
 *     48_000,
 *     32);
 * }</pre>
 */
public final class TrackDspHasher {

    /**
     * Single-byte tag prefixed to each section so that, for example,
     * an insert {@code "x"} cannot collide with a parameter named
     * {@code "x"}. Tags are stable; do not renumber them — that would
     * invalidate every existing on-disk cache entry.
     */
    private enum Tag {
        INSERT(1),
        PARAMETER(2),
        AUTOMATION(3),
        CLIP_CONTENT(4),
        SEND(5),
        TEMPO(6),
        TIME_SIGNATURE(7),
        RANGE(8),
        BYPASS(9);

        final byte value;
        Tag(int v) { this.value = (byte) v; }
    }

    private final MessageDigest sha256;

    public TrackDspHasher() {
        try {
            this.sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS — every JVM ships it.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Appends one insert plugin's identity (id + version). */
    public TrackDspHasher addInsert(String pluginId, int pluginVersion) {
        Objects.requireNonNull(pluginId, "pluginId must not be null");
        tag(Tag.INSERT);
        utf8(pluginId);
        intLE(pluginVersion);
        return this;
    }

    /** Appends one (name, value) parameter sample. */
    public TrackDspHasher addParameter(String name, double value) {
        Objects.requireNonNull(name, "name must not be null");
        tag(Tag.PARAMETER);
        utf8(name);
        longLE(Double.doubleToRawLongBits(value));
        return this;
    }

    /**
     * Appends one automation lane curve. Callers should pass the
     * lane name plus the curve as parallel arrays of {@code (time,
     * value)} samples already sampled at a stable resolution; the
     * arrays are hashed verbatim so callers must always sample with
     * the same density to keep the hash stable.
     */
    public TrackDspHasher addAutomation(String laneName, double[] times, double[] values) {
        Objects.requireNonNull(laneName, "laneName must not be null");
        Objects.requireNonNull(times, "times must not be null");
        Objects.requireNonNull(values, "values must not be null");
        if (times.length != values.length) {
            throw new IllegalArgumentException(
                    "times and values must be the same length: "
                            + times.length + " vs " + values.length);
        }
        tag(Tag.AUTOMATION);
        utf8(laneName);
        intLE(times.length);
        for (int i = 0; i < times.length; i++) {
            longLE(Double.doubleToRawLongBits(times[i]));
            longLE(Double.doubleToRawLongBits(values[i]));
        }
        return this;
    }

    /**
     * Appends a source-clip content fingerprint. {@code contentHash}
     * is a hex string identifying the clip's audio data — typically
     * its own SHA-256 — so editing the underlying samples changes the
     * track digest even if no DSP parameter changed.
     */
    public TrackDspHasher addClipContent(String clipId, String contentHash) {
        Objects.requireNonNull(clipId, "clipId must not be null");
        Objects.requireNonNull(contentHash, "contentHash must not be null");
        tag(Tag.CLIP_CONTENT);
        utf8(clipId);
        utf8(contentHash);
        return this;
    }

    /** Appends one send: {@code targetBusId}, gain, pre/post-fader flag. */
    public TrackDspHasher addSend(String targetBusId, double level, boolean preFader) {
        Objects.requireNonNull(targetBusId, "targetBusId must not be null");
        tag(Tag.SEND);
        utf8(targetBusId);
        longLE(Double.doubleToRawLongBits(level));
        sha256.update((byte) (preFader ? 1 : 0));
        return this;
    }

    /** Appends the project tempo (BPM) at the track's range. */
    public TrackDspHasher addTempo(double bpm) {
        tag(Tag.TEMPO);
        longLE(Double.doubleToRawLongBits(bpm));
        return this;
    }

    /** Appends the project time signature at the track's range. */
    public TrackDspHasher addTimeSignature(int numerator, int denominator) {
        tag(Tag.TIME_SIGNATURE);
        intLE(numerator);
        intLE(denominator);
        return this;
    }

    /** Appends the rendered sample range {@code [startFrame, endFrame)}. */
    public TrackDspHasher addRange(long startFrame, long endFrame) {
        tag(Tag.RANGE);
        longLE(startFrame);
        longLE(endFrame);
        return this;
    }

    /** Appends a chain-bypass flag (changes when the user toggles all-off). */
    public TrackDspHasher addBypass(boolean chainBypassed) {
        tag(Tag.BYPASS);
        sha256.update((byte) (chainBypassed ? 1 : 0));
        return this;
    }

    /** Returns the lowercase hex SHA-256 digest of the appended state. */
    public String digestHex() {
        return HexFormat.of().formatHex(sha256.digest());
    }

    private void tag(Tag t) {
        sha256.update(t.value);
    }

    private void utf8(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        intLE(bytes.length);
        sha256.update(bytes);
    }

    private void intLE(int v) {
        sha256.update((byte) v);
        sha256.update((byte) (v >>> 8));
        sha256.update((byte) (v >>> 16));
        sha256.update((byte) (v >>> 24));
    }

    private void longLE(long v) {
        sha256.update((byte) v);
        sha256.update((byte) (v >>> 8));
        sha256.update((byte) (v >>> 16));
        sha256.update((byte) (v >>> 24));
        sha256.update((byte) (v >>> 32));
        sha256.update((byte) (v >>> 40));
        sha256.update((byte) (v >>> 48));
        sha256.update((byte) (v >>> 56));
    }
}
