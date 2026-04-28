package com.benesquivelmusic.daw.core.export.aaf;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure-Java writer for AAF&nbsp;1.2 interchange files capturing the
 * minimal subset of the AAF data model required for editorial → post
 * audio handoff: composition, source-mob references, source clips with
 * position / length / source-offset, fade-in / fade-out (with curve
 * type), and per-clip gain.
 *
 * <p>See the {@code package-info.java} for the complete on-disk layout.
 * Files written by this class can be re-read by {@link AafReader},
 * which is used by tests as the bundled AAF verifier.</p>
 *
 * <p>This class is stateless and thread-safe: each {@link #write} call
 * is independent.</p>
 */
public final class AafWriter {

    /** Magic header bytes that identify an AAF&nbsp;1.2 file. */
    public static final byte[] AAF_MAGIC = {'A', 'A', 'F', '1', '2', 0, 0, 0};

    /** Trailer bytes at end of file that confirm a complete write. */
    public static final byte[] AAF_TRAILER = {'A', 'E', 'N', 'D'};

    /** AAF version major emitted by this writer. */
    public static final short VERSION_MAJOR = 1;
    /** AAF version minor emitted by this writer. */
    public static final short VERSION_MINOR = 2;

    /**
     * Optional source-media payloads keyed by source-mob id. When a
     * composition is written with {@link #write(AafComposition, Map, Path)}
     * each entry whose key matches a clip's {@code sourceMobId} is
     * appended into the file as embedded media; clips whose source-mob
     * id has no payload are written by reference only.
     */
    public AafWriter() {
    }

    /**
     * Writes the composition to {@code outputPath} with no embedded
     * media (reference-only export).
     */
    public void write(AafComposition composition, Path outputPath) throws IOException {
        write(composition, Map.of(), outputPath);
    }

    /**
     * Writes the composition to {@code outputPath}, optionally
     * embedding raw PCM media for any source-mobs whose id appears in
     * {@code embeddedMedia}.
     *
     * @param composition the timeline data model
     * @param embeddedMedia map from source-mob id to PCM payload (may
     *                      be empty, never {@code null})
     * @param outputPath  destination file (will be created or
     *                    overwritten)
     * @throws IOException if the file cannot be written
     */
    public void write(AafComposition composition,
                      Map<UUID, EmbeddedMedia> embeddedMedia,
                      Path outputPath) throws IOException {
        Objects.requireNonNull(composition, "composition must not be null");
        Objects.requireNonNull(embeddedMedia, "embeddedMedia must not be null");
        Objects.requireNonNull(outputPath, "outputPath must not be null");
        writeInternal(AAF_MAGIC, AAF_TRAILER, VERSION_MAJOR, VERSION_MINOR,
                composition, embeddedMedia, outputPath);
    }

    /**
     * Internal entry point shared with the OMF writer: the byte layout
     * is identical, only the magic / trailer / version differ. Public
     * for use by the OMF&nbsp;2.0 fallback writer in a sibling package;
     * application code should prefer {@link #write}.
     */
    public static void writeInternal(byte[] magic,
                              byte[] trailer,
                              short versionMajor,
                              short versionMinor,
                              AafComposition composition,
                              Map<UUID, EmbeddedMedia> embeddedMedia,
                              Path outputPath) throws IOException {
        byte[] manifest = buildManifestJson(composition, embeddedMedia.keySet())
                .getBytes(StandardCharsets.UTF_8);

        try (OutputStream out = Files.newOutputStream(outputPath)) {
            out.write(magic);
            writeShortBE(out, versionMajor);
            writeShortBE(out, versionMinor);
            writeIntBE(out, manifest.length);
            out.write(manifest);

            // Only embed sources actually referenced by a clip.
            List<UUID> referencedIds = new ArrayList<>();
            for (AafSourceClip c : composition.clips()) {
                if (embeddedMedia.containsKey(c.sourceMobId())
                        && !referencedIds.contains(c.sourceMobId())) {
                    referencedIds.add(c.sourceMobId());
                }
            }
            writeIntBE(out, referencedIds.size());
            for (UUID id : referencedIds) {
                EmbeddedMedia media = embeddedMedia.get(id);
                writeUuid(out, id);
                byte[] nameBytes = media.name().getBytes(StandardCharsets.UTF_8);
                writeIntBE(out, nameBytes.length);
                out.write(nameBytes);
                writeIntBE(out, media.sampleRate());
                writeShortBE(out, (short) media.channels());
                writeShortBE(out, (short) media.bitsPerSample());
                writeLongBE(out, media.frameCount());
                writeIntBE(out, media.pcmData().length);
                out.write(media.pcmData());
            }

            out.write(trailer);
        }
    }

    // ── Manifest JSON ─────────────────────────────────────────────────────

    /**
     * Builds the UTF-8 JSON manifest. Hand-written to avoid pulling in
     * a JSON library; the schema is fixed and consumed only by
     * {@link AafReader}.
     */
    static String buildManifestJson(AafComposition composition,
                                    java.util.Set<UUID> embeddedIds) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\n");
        appendString(sb, "compositionName", composition.compositionName(), true);
        appendInt(sb, "sampleRate", composition.sampleRate(), true);
        appendString(sb, "frameRate", composition.frameRate().name(), true);
        appendDouble(sb, "frameRateFps", composition.frameRate().fps(), true);
        appendString(sb, "startTimecode", composition.startTimecode().toString(), true);
        appendInt(sb, "startHours", composition.startTimecode().hours(), true);
        appendInt(sb, "startMinutes", composition.startTimecode().minutes(), true);
        appendInt(sb, "startSeconds", composition.startTimecode().seconds(), true);
        appendInt(sb, "startFrames", composition.startTimecode().frames(), true);
        appendLong(sb, "totalLengthSamples", composition.totalLengthSamples(), true);
        sb.append("  \"clips\": [");
        boolean first = true;
        for (AafSourceClip c : composition.clips()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\n    {\n");
            appendString(sb, "      ", "sourceMobId", c.sourceMobId().toString(), true);
            appendString(sb, "      ", "sourceFile", nullSafe(c.sourceFile()), true);
            appendString(sb, "      ", "sourceName", c.sourceName(), true);
            appendBool(sb,   "      ", "embedded", embeddedIds.contains(c.sourceMobId()), true);
            appendInt(sb,    "      ", "trackIndex", c.trackIndex(), true);
            appendString(sb, "      ", "trackName", c.trackName(), true);
            appendLong(sb,   "      ", "startSample", c.startSample(), true);
            appendLong(sb,   "      ", "lengthSamples", c.lengthSamples(), true);
            appendLong(sb,   "      ", "sourceOffsetSamples", c.sourceOffsetSamples(), true);
            appendDouble(sb, "      ", "gainDb", c.gainDb(), true);
            appendLong(sb,   "      ", "fadeInSamples", c.fadeInSamples(), true);
            appendString(sb, "      ", "fadeInCurve", c.fadeInCurve().name(), true);
            appendLong(sb,   "      ", "fadeOutSamples", c.fadeOutSamples(), true);
            appendString(sb, "      ", "fadeOutCurve", c.fadeOutCurve().name(), false);
            sb.append("    }");
        }
        if (!composition.clips().isEmpty()) sb.append('\n');
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    // ── Tiny JSON helpers ─────────────────────────────────────────────────

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static void appendString(StringBuilder sb, String key, String value, boolean comma) {
        appendString(sb, "  ", key, value, comma);
    }
    private static void appendInt(StringBuilder sb, String key, int value, boolean comma) {
        appendInt(sb, "  ", key, value, comma);
    }
    private static void appendLong(StringBuilder sb, String key, long value, boolean comma) {
        appendLong(sb, "  ", key, value, comma);
    }
    private static void appendDouble(StringBuilder sb, String key, double value, boolean comma) {
        appendDouble(sb, "  ", key, value, comma);
    }
    private static void appendString(StringBuilder sb, String indent, String key, String value, boolean comma) {
        sb.append(indent).append('"').append(key).append("\": \"")
                .append(escape(value)).append('"').append(comma ? "," : "").append('\n');
    }
    private static void appendInt(StringBuilder sb, String indent, String key, int value, boolean comma) {
        sb.append(indent).append('"').append(key).append("\": ")
                .append(value).append(comma ? "," : "").append('\n');
    }
    private static void appendLong(StringBuilder sb, String indent, String key, long value, boolean comma) {
        sb.append(indent).append('"').append(key).append("\": ")
                .append(value).append(comma ? "," : "").append('\n');
    }
    private static void appendDouble(StringBuilder sb, String indent, String key, double value, boolean comma) {
        sb.append(indent).append('"').append(key).append("\": ")
                .append(String.format(Locale.ROOT, "%.10f", value))
                .append(comma ? "," : "").append('\n');
    }
    private static void appendBool(StringBuilder sb, String indent, String key, boolean value, boolean comma) {
        sb.append(indent).append('"').append(key).append("\": ")
                .append(value).append(comma ? "," : "").append('\n');
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"'  -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.toString();
    }

    // ── Binary helpers ────────────────────────────────────────────────────

    private static void writeShortBE(OutputStream out, short v) throws IOException {
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }
    private static void writeIntBE(OutputStream out, int v) throws IOException {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }
    private static void writeLongBE(OutputStream out, long v) throws IOException {
        for (int s = 56; s >= 0; s -= 8) out.write((int) ((v >>> s) & 0xFF));
    }
    private static void writeUuid(OutputStream out, UUID id) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
        buf.putLong(id.getMostSignificantBits());
        buf.putLong(id.getLeastSignificantBits());
        out.write(buf.array());
    }

    /**
     * A blob of embedded media attached to an AAF source-mob.
     *
     * @param name           display / file name of the source
     * @param sampleRate     PCM sample rate (Hz)
     * @param channels       channel count
     * @param bitsPerSample  bit depth (e.g. 16, 24, 32)
     * @param frameCount     number of frames (samples per channel)
     * @param pcmData        raw little-endian signed PCM, interleaved
     */
    public record EmbeddedMedia(String name,
                                int sampleRate,
                                int channels,
                                int bitsPerSample,
                                long frameCount,
                                byte[] pcmData) {
        public EmbeddedMedia {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(pcmData, "pcmData must not be null");
            if (sampleRate <= 0)    throw new IllegalArgumentException("sampleRate must be positive");
            if (channels <= 0)      throw new IllegalArgumentException("channels must be positive");
            if (bitsPerSample <= 0) throw new IllegalArgumentException("bitsPerSample must be positive");
            if (frameCount < 0)     throw new IllegalArgumentException("frameCount must be >= 0");
        }
    }

    /**
     * Convenience map builder used by callers that want type-safe
     * construction of the embedded-media map.
     */
    public static Map<UUID, EmbeddedMedia> mediaMap() {
        return new LinkedHashMap<>();
    }
}
