package com.benesquivelmusic.daw.sdk.audio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Persists per-user audio backend &amp; device selection to
 * {@code ~/.daw/audio-settings.json}.
 *
 * <p>The on-disk format is intentionally a small flat JSON document so the
 * SDK does not need to pull in a JSON library:</p>
 * <pre>{@code
 * {
 *   "backend": "ASIO",
 *   "inputDevice": "Focusrite Scarlett 4i4",
 *   "outputDevice": "Focusrite Scarlett 4i4",
 *   "sampleRate": 48000.0,
 *   "bufferFrames": 128
 * }
 * }</pre>
 *
 * <p>Unknown or corrupt files are treated as "no saved settings" — the
 * caller should fall back to {@link AudioBackendSelector#defaultBackendName()}.
 * Corrupt files are not deleted; the user can inspect and fix them.</p>
 */
public final class AudioSettingsStore {

    /** Default relative path under {@code user.home}. */
    public static final String DEFAULT_RELATIVE_PATH = ".daw/audio-settings.json";

    /** Immutable snapshot of the persisted settings. */
    public record Settings(
            String backend,
            String inputDevice,
            String outputDevice,
            double sampleRate,
            int bufferFrames) {

        public Settings {
            Objects.requireNonNull(backend, "backend must not be null");
            Objects.requireNonNull(inputDevice, "inputDevice must not be null");
            Objects.requireNonNull(outputDevice, "outputDevice must not be null");
            if (backend.isBlank()) {
                throw new IllegalArgumentException("backend must not be blank");
            }
            if (!(sampleRate > 0)) {
                throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
            }
            if (bufferFrames <= 0) {
                throw new IllegalArgumentException("bufferFrames must be positive: " + bufferFrames);
            }
        }
    }

    private final Path file;

    /** Creates a store rooted at {@code ~/.daw/audio-settings.json}. */
    public AudioSettingsStore() {
        this(Paths.get(System.getProperty("user.home", "."), DEFAULT_RELATIVE_PATH));
    }

    /**
     * Creates a store backed by an explicit file path — useful for tests that
     * point at a {@code @TempDir}.
     *
     * @param file on-disk location; must not be null
     */
    public AudioSettingsStore(Path file) {
        this.file = Objects.requireNonNull(file, "file must not be null");
    }

    /**
     * Returns the file the store reads and writes.
     *
     * @return persistence file path
     */
    public Path file() {
        return file;
    }

    /**
     * Loads settings from disk, returning {@link Optional#empty()} when the
     * file does not exist or cannot be parsed.
     *
     * @return loaded settings, or empty if nothing is persisted
     */
    public Optional<Settings> load() {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, String> kv = parseFlatJson(json);
            if (kv == null) {
                return Optional.empty();
            }
            String backend = kv.get("backend");
            String inputDevice = kv.get("inputDevice");
            String outputDevice = kv.get("outputDevice");
            String sr = kv.get("sampleRate");
            String bf = kv.get("bufferFrames");
            if (backend == null || inputDevice == null || outputDevice == null
                    || sr == null || bf == null) {
                return Optional.empty();
            }
            return Optional.of(new Settings(
                    backend,
                    inputDevice,
                    outputDevice,
                    Double.parseDouble(sr),
                    Integer.parseInt(bf)));
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Writes the given settings to disk, creating parent directories as needed.
     *
     * @param settings settings to persist; must not be null
     * @throws IOException if the file cannot be written
     */
    public void save(Settings settings) throws IOException {
        Objects.requireNonNull(settings, "settings must not be null");
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, toJson(settings), StandardCharsets.UTF_8);
    }

    static String toJson(Settings s) {
        return "{\n"
                + "  \"backend\": \"" + escape(s.backend()) + "\",\n"
                + "  \"inputDevice\": \"" + escape(s.inputDevice()) + "\",\n"
                + "  \"outputDevice\": \"" + escape(s.outputDevice()) + "\",\n"
                + "  \"sampleRate\": " + s.sampleRate() + ",\n"
                + "  \"bufferFrames\": " + s.bufferFrames() + "\n"
                + "}\n";
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Parses a flat JSON object with string/number values into a
     * {@code Map<String,String>}. Returns {@code null} if the input is not
     * a JSON object. Kept intentionally small — only understands the
     * single-level shape this store writes.
     */
    static Map<String, String> parseFlatJson(String json) {
        if (json == null) return null;
        String s = json.trim();
        if (s.isEmpty() || s.charAt(0) != '{' || s.charAt(s.length() - 1) != '}') {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        int i = 1;
        int end = s.length() - 1;
        while (i < end) {
            while (i < end && Character.isWhitespace(s.charAt(i))) i++;
            if (i >= end) break;
            if (s.charAt(i) == ',') { i++; continue; }
            if (s.charAt(i) != '"') return null;
            int keyStart = ++i;
            while (i < end && s.charAt(i) != '"') i++;
            if (i >= end) return null;
            String key = s.substring(keyStart, i);
            i++; // closing quote
            while (i < end && Character.isWhitespace(s.charAt(i))) i++;
            if (i >= end || s.charAt(i) != ':') return null;
            i++;
            while (i < end && Character.isWhitespace(s.charAt(i))) i++;
            if (i >= end) return null;
            String value;
            if (s.charAt(i) == '"') {
                int valStart = ++i;
                StringBuilder sb = new StringBuilder();
                while (i < end && s.charAt(i) != '"') {
                    if (s.charAt(i) == '\\' && i + 1 < end) {
                        char esc = s.charAt(++i);
                        switch (esc) {
                            case '"' -> sb.append('"');
                            case '\\' -> sb.append('\\');
                            case 'n' -> sb.append('\n');
                            case 'r' -> sb.append('\r');
                            case 't' -> sb.append('\t');
                            default -> sb.append(esc);
                        }
                    } else {
                        sb.append(s.charAt(i));
                    }
                    i++;
                }
                if (i >= end) return null;
                i++; // closing quote
                value = sb.toString();
                // suppress unused warning for valStart
                Objects.equals(valStart, valStart);
            } else {
                int valStart = i;
                while (i < end && s.charAt(i) != ',' && !Character.isWhitespace(s.charAt(i))) i++;
                value = s.substring(valStart, i).trim();
            }
            out.put(key, value);
            while (i < end && Character.isWhitespace(s.charAt(i))) i++;
            if (i < end && s.charAt(i) == ',') i++;
        }
        return out;
    }
}
