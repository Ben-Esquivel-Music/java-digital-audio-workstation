package com.benesquivelmusic.daw.core.export.aaf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Reader / verifier for files produced by {@link AafWriter} (and by the
 * OMF&nbsp;2.0 fallback writer, which uses the same envelope with
 * different magic).
 *
 * <p>This class is the bundled "AAF verifier" referenced by the issue:
 * tests round-trip a composition through the writer, parse the result
 * back into an {@link AafFile}, and assert that every position, length,
 * source-offset, fade and gain matches within the allowed precision
 * (1 sample for sample-quantised fields).</p>
 */
public final class AafReader {

    /**
     * Reads an AAF&nbsp;1.2 file written by {@link AafWriter}.
     */
    public AafFile read(Path file) throws IOException {
        return read(file, AafWriter.AAF_MAGIC, AafWriter.AAF_TRAILER);
    }

    /**
     * Reads a file with explicit magic / trailer (used by tests for
     * OMF). Package-private so callers go through the public AAF or OMF
     * APIs in normal use.
     */
    AafFile read(Path file, byte[] expectedMagic, byte[] expectedTrailer) throws IOException {
        long fileSize = Files.size(file);
        try (InputStream in = Files.newInputStream(file)) {
            byte[] magic = in.readNBytes(8);
            if (!java.util.Arrays.equals(magic, expectedMagic)) {
                throw new IOException("not an AAF/OMF file: bad magic in " + file);
            }
            int versionMajor = readShortBE(in);
            int versionMinor = readShortBE(in);
            int manifestLen  = readIntBE(in);
            if (manifestLen < 0 || manifestLen > fileSize) {
                throw new IOException("invalid manifest length: " + manifestLen);
            }
            byte[] manifestBytes = in.readNBytes(manifestLen);
            String manifestJson = new String(manifestBytes, StandardCharsets.UTF_8);

            int mediaCount = readIntBE(in);
            if (mediaCount < 0 || mediaCount > 1_000_000) {
                throw new IOException("implausible media count: " + mediaCount);
            }
            Map<UUID, AafWriter.EmbeddedMedia> embedded = new LinkedHashMap<>();
            for (int i = 0; i < mediaCount; i++) {
                byte[] uuidBytes = in.readNBytes(16);
                ByteBuffer buf = ByteBuffer.wrap(uuidBytes).order(ByteOrder.BIG_ENDIAN);
                UUID id = new UUID(buf.getLong(), buf.getLong());
                int nameLen = readIntBE(in);
                String name = new String(in.readNBytes(nameLen), StandardCharsets.UTF_8);
                int sampleRate = readIntBE(in);
                int channels = readShortBE(in);
                int bitsPerSample = readShortBE(in);
                long frameCount = readLongBE(in);
                int pcmLen = readIntBE(in);
                byte[] pcm = in.readNBytes(pcmLen);
                embedded.put(id, new AafWriter.EmbeddedMedia(
                        name, sampleRate, channels, bitsPerSample, frameCount, pcm));
            }
            byte[] trailer = in.readNBytes(4);
            if (!java.util.Arrays.equals(trailer, expectedTrailer)) {
                throw new IOException("invalid trailer: " + new String(trailer));
            }

            AafComposition comp = parseManifest(manifestJson);
            return new AafFile(versionMajor, versionMinor, comp, Map.copyOf(embedded));
        }
    }

    // ── Tiny JSON parser tailored for the manifest schema ─────────────────

    private static AafComposition parseManifest(String json) throws IOException {
        Map<String, Object> top = parseObject(json);
        String compName = (String) top.get("compositionName");
        int sampleRate  = ((Number) top.get("sampleRate")).intValue();
        AafFrameRate fr = AafFrameRate.valueOf((String) top.get("frameRate"));
        int sh = ((Number) top.get("startHours")).intValue();
        int sm = ((Number) top.get("startMinutes")).intValue();
        int ss = ((Number) top.get("startSeconds")).intValue();
        int sf = ((Number) top.get("startFrames")).intValue();
        long total = ((Number) top.get("totalLengthSamples")).longValue();
        AafTimecode start = new AafTimecode(sh, sm, ss, sf, fr);

        List<Object> rawClips = (List<Object>) top.get("clips");
        List<AafSourceClip> clips = new ArrayList<>();
        if (rawClips != null) {
            for (Object o : rawClips) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) o;
                clips.add(new AafSourceClip(
                        UUID.fromString((String) m.get("sourceMobId")),
                        emptyToNull((String) m.get("sourceFile")),
                        (String) m.get("sourceName"),
                        ((Number) m.get("trackIndex")).intValue(),
                        (String) m.get("trackName"),
                        ((Number) m.get("startSample")).longValue(),
                        ((Number) m.get("lengthSamples")).longValue(),
                        ((Number) m.get("sourceOffsetSamples")).longValue(),
                        ((Number) m.get("gainDb")).doubleValue(),
                        ((Number) m.get("fadeInSamples")).longValue(),
                        AafFadeCurve.valueOf((String) m.get("fadeInCurve")),
                        ((Number) m.get("fadeOutSamples")).longValue(),
                        AafFadeCurve.valueOf((String) m.get("fadeOutCurve"))));
            }
        }
        return new AafComposition(compName, sampleRate, fr, start, total, clips);
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    /**
     * Hand-rolled minimal JSON parser; supports only the schema produced
     * by {@link AafWriter#buildManifestJson}: objects, arrays, strings,
     * numbers (int/long/double), and booleans. No null support is
     * needed because the writer never emits {@code null}.
     */
    private static Map<String, Object> parseObject(String json) throws IOException {
        Parser p = new Parser(json);
        p.skipWs();
        Object root = p.parseValue();
        if (!(root instanceof Map<?, ?>)) {
            throw new IOException("expected JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) root;
        return typed;
    }

    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        Object parseValue() throws IOException {
            skipWs();
            if (pos >= src.length()) throw new IOException("unexpected end of input");
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> parseObj();
                case '[' -> parseArr();
                case '"' -> parseStr();
                case 't', 'f' -> parseBool();
                default -> parseNum();
            };
        }

        Map<String, Object> parseObj() throws IOException {
            expect('{');
            Map<String, Object> out = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') { pos++; return out; }
            while (true) {
                skipWs();
                String key = parseStr();
                skipWs();
                expect(':');
                Object val = parseValue();
                out.put(key, val);
                skipWs();
                char c = src.charAt(pos++);
                if (c == ',') continue;
                if (c == '}') return out;
                throw new IOException("expected , or } at " + pos);
            }
        }

        List<Object> parseArr() throws IOException {
            expect('[');
            List<Object> out = new ArrayList<>();
            skipWs();
            if (peek() == ']') { pos++; return out; }
            while (true) {
                out.add(parseValue());
                skipWs();
                char c = src.charAt(pos++);
                if (c == ',') continue;
                if (c == ']') return out;
                throw new IOException("expected , or ] at " + pos);
            }
        }

        String parseStr() throws IOException {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= src.length()) throw new IOException("bad escape");
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"'  -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/'  -> sb.append('/');
                        case 'n'  -> sb.append('\n');
                        case 'r'  -> sb.append('\r');
                        case 't'  -> sb.append('\t');
                        case 'b'  -> sb.append('\b');
                        case 'f'  -> sb.append('\f');
                        case 'u'  -> {
                            if (pos + 4 > src.length()) throw new IOException("bad unicode escape");
                            sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new IOException("bad escape: \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IOException("unterminated string");
        }

        Boolean parseBool() throws IOException {
            if (src.startsWith("true", pos))  { pos += 4; return Boolean.TRUE; }
            if (src.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw new IOException("invalid literal at " + pos);
        }

        Number parseNum() {
            int start = pos;
            if (src.charAt(pos) == '-') pos++;
            boolean isReal = false;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == '.' || c == 'e' || c == 'E') isReal = true;
                if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E'
                        || c == '+' || c == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            String tok = src.substring(start, pos);
            if (isReal) return Double.parseDouble(tok);
            try {
                return Long.parseLong(tok);
            } catch (NumberFormatException nfe) {
                return Double.parseDouble(tok);
            }
        }

        void skipWs() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
        }

        char peek() {
            return pos < src.length() ? src.charAt(pos) : '\0';
        }

        void expect(char c) throws IOException {
            skipWs();
            if (pos >= src.length() || src.charAt(pos) != c) {
                throw new IOException("expected '" + c + "' at " + pos);
            }
            pos++;
        }
    }

    // ── Binary helpers ────────────────────────────────────────────────────

    private static int readShortBE(InputStream in) throws IOException {
        int hi = in.read(), lo = in.read();
        if ((hi | lo) < 0) throw new IOException("truncated short");
        return ((hi & 0xFF) << 8) | (lo & 0xFF);
    }
    private static int readIntBE(InputStream in) throws IOException {
        int b1 = in.read(), b2 = in.read(), b3 = in.read(), b4 = in.read();
        if ((b1 | b2 | b3 | b4) < 0) throw new IOException("truncated int");
        return (b1 << 24) | ((b2 & 0xFF) << 16) | ((b3 & 0xFF) << 8) | (b4 & 0xFF);
    }
    private static long readLongBE(InputStream in) throws IOException {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            int b = in.read();
            if (b < 0) throw new IOException("truncated long");
            v = (v << 8) | (b & 0xFF);
        }
        return v;
    }

    /**
     * Snapshot of a parsed AAF / OMF file used by tests.
     *
     * @param versionMajor the major version of the on-disk format
     * @param versionMinor the minor version of the on-disk format
     * @param composition  the parsed composition
     * @param embeddedMedia map of source-mob id to embedded payload
     *                      (empty if the file was reference-only)
     */
    public record AafFile(int versionMajor,
                          int versionMinor,
                          AafComposition composition,
                          Map<UUID, AafWriter.EmbeddedMedia> embeddedMedia) {
        public AafFile {
            Objects.requireNonNull(composition, "composition must not be null");
            Objects.requireNonNull(embeddedMedia, "embeddedMedia must not be null");
        }
    }
}
