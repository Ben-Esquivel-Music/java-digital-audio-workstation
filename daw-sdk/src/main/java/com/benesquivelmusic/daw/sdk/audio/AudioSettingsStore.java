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

    /**
     * Immutable snapshot of the persisted settings.
     *
     * <p>The optional {@link #clockSourceByDeviceKey()} map persists
     * the user's chosen hardware clock source per device, keyed by
     * {@code "<backend>|<device-name>"}. It is serialized as a single
     * comma-separated string field {@code "clockSourcesByDevice"} so
     * the flat-JSON parser does not need to grow a nested-object code
     * path. An empty map is the historical default and serializes as
     * an empty string.</p>
     */
    public record Settings(
            String backend,
            String inputDevice,
            String outputDevice,
            double sampleRate,
            int bufferFrames,
            Map<String, Integer> clockSourceByDeviceKey) {

        public Settings {
            Objects.requireNonNull(backend, "backend must not be null");
            Objects.requireNonNull(inputDevice, "inputDevice must not be null");
            Objects.requireNonNull(outputDevice, "outputDevice must not be null");
            Objects.requireNonNull(clockSourceByDeviceKey,
                    "clockSourceByDeviceKey must not be null");
            if (backend.isBlank()) {
                throw new IllegalArgumentException("backend must not be blank");
            }
            if (!(sampleRate > 0)) {
                throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
            }
            if (bufferFrames <= 0) {
                throw new IllegalArgumentException("bufferFrames must be positive: " + bufferFrames);
            }
            // Defensive copy so callers cannot mutate the persisted map.
            clockSourceByDeviceKey = Map.copyOf(clockSourceByDeviceKey);
        }

        /**
         * Backwards-compatible constructor for callers that do not
         * persist per-device clock-source selections. Equivalent to
         * passing an empty map for {@code clockSourceByDeviceKey}.
         */
        public Settings(String backend,
                        String inputDevice,
                        String outputDevice,
                        double sampleRate,
                        int bufferFrames) {
            this(backend, inputDevice, outputDevice, sampleRate, bufferFrames, Map.of());
        }

        /**
         * Returns the device-key encoding used by
         * {@link #clockSourceByDeviceKey()} for a given {@link DeviceId}.
         * Defined here so callers always agree on the encoding.
         *
         * @param device the device id; must not be null
         * @return a stable string key in the form
         *         {@code "<backend>|<device-name>"}
         */
        public static String deviceKey(DeviceId device) {
            Objects.requireNonNull(device, "device must not be null");
            return device.backend() + "|" + device.name();
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
                    Integer.parseInt(bf),
                    parseClockSources(kv.get("clockSourcesByDevice"))));
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
                + "  \"bufferFrames\": " + s.bufferFrames() + ",\n"
                + "  \"clockSourcesByDevice\": \""
                + escape(encodeClockSources(s.clockSourceByDeviceKey())) + "\"\n"
                + "}\n";
    }

    /**
     * Encodes a per-device clock-source map as a single comma-separated
     * string of {@code key=id} pairs, with {@code key} URL-encoded so
     * embedded {@code ,} or {@code =} characters round-trip cleanly.
     */
    static String encodeClockSources(Map<String, Integer> map) {
        if (map.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        // Sort for deterministic output so persisted files diff cleanly.
        for (Map.Entry<String, Integer> e :
                new java.util.TreeMap<>(map).entrySet()) {
            if (!first) sb.append(',');
            sb.append(java.net.URLEncoder.encode(
                            e.getKey(), java.nio.charset.StandardCharsets.UTF_8))
                    .append('=')
                    .append(e.getValue().intValue());
            first = false;
        }
        return sb.toString();
    }

    /**
     * Decodes the {@link #encodeClockSources(Map)} format. Returns an
     * empty map for {@code null} or the empty string. Individual
     * malformed entries (bad percent-encoding, non-integer id) are
     * silently skipped — only that pair is lost; any successfully
     * parsed entries are preserved. This ensures corrupt clock-source
     * data is non-fatal: the rest of the settings file still loads.
     */
    static Map<String, Integer> parseClockSources(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String pair : encoded.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0 || eq == pair.length() - 1) {
                continue;
            }
            try {
                String key = java.net.URLDecoder.decode(
                        pair.substring(0, eq),
                        java.nio.charset.StandardCharsets.UTF_8);
                out.put(key, Integer.parseInt(pair.substring(eq + 1)));
            } catch (IllegalArgumentException ignored) {
                // Skip malformed entry; preserve any previously parsed entries.
            }
        }
        return out;
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
