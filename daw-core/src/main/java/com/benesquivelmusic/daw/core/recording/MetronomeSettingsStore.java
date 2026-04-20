package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.sdk.transport.ClickOutput;

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
 * Persists the user's global metronome defaults — most importantly the
 * {@link ClickOutput} routing — to {@code ~/.daw/metronome-settings.json}.
 *
 * <p>Every professional DAW remembers the engineer's preferred click routing
 * across sessions: the drummer's headphone channel is rarely different from
 * day to day. New projects should open pre-configured so the engineer does
 * not have to re-pick the hardware channel before every tracking session.
 * Story 136 adds this store; per-project overrides continue to flow through
 * {@link com.benesquivelmusic.daw.core.persistence.ProjectSerializer}.</p>
 *
 * <p>The on-disk format is a small flat JSON document, matching the style of
 * {@link com.benesquivelmusic.daw.sdk.audio.AudioSettingsStore}:</p>
 * <pre>{@code
 * {
 *   "enabled": true,
 *   "volume": 1.0,
 *   "clickSound": "WOODBLOCK",
 *   "subdivision": "QUARTER",
 *   "clickOutput.hardwareChannelIndex": 2,
 *   "clickOutput.gain": 0.8,
 *   "clickOutput.mainMixEnabled": false,
 *   "clickOutput.sideOutputEnabled": true
 * }
 * }</pre>
 *
 * <p>Unknown or corrupt files are treated as "no saved settings" — callers
 * should fall back to their code-level defaults.</p>
 */
public final class MetronomeSettingsStore {

    /** Default relative path under {@code user.home}. */
    public static final String DEFAULT_RELATIVE_PATH = ".daw/metronome-settings.json";

    /**
     * Immutable snapshot of the persisted metronome defaults.
     *
     * @param enabled      whether the metronome starts enabled
     * @param volume       click volume in {@code [0.0, 1.0]}
     * @param clickSound   preferred {@link ClickSound}
     * @param subdivision  preferred {@link Subdivision}
     * @param clickOutput  routing for the side output; non-null
     */
    public record Settings(boolean enabled,
                           double volume,
                           ClickSound clickSound,
                           Subdivision subdivision,
                           ClickOutput clickOutput) {

        public Settings {
            Objects.requireNonNull(clickSound, "clickSound must not be null");
            Objects.requireNonNull(subdivision, "subdivision must not be null");
            Objects.requireNonNull(clickOutput, "clickOutput must not be null");
            if (volume < 0.0 || volume > 1.0) {
                throw new IllegalArgumentException(
                        "volume must be between 0.0 and 1.0: " + volume);
            }
        }

        /** Factory defaults matching {@link Metronome}'s constructor. */
        public static Settings defaults() {
            return new Settings(true, 1.0, ClickSound.WOODBLOCK, Subdivision.QUARTER,
                    ClickOutput.MAIN_MIX_ONLY);
        }
    }

    private final Path file;

    /** Creates a store rooted at {@code ~/.daw/metronome-settings.json}. */
    public MetronomeSettingsStore() {
        this(Paths.get(System.getProperty("user.home", "."), DEFAULT_RELATIVE_PATH));
    }

    /**
     * Creates a store backed by an explicit file path — useful for tests that
     * point at a {@code @TempDir}.
     *
     * @param file on-disk location; must not be null
     */
    public MetronomeSettingsStore(Path file) {
        this.file = Objects.requireNonNull(file, "file must not be null");
    }

    /** Returns the file the store reads and writes. */
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
            boolean enabled = Boolean.parseBoolean(kv.getOrDefault("enabled", "true"));
            double volume = clamp01(Double.parseDouble(kv.getOrDefault("volume", "1.0")));
            ClickSound clickSound = parseEnum(kv.get("clickSound"),
                    ClickSound.class, ClickSound.WOODBLOCK);
            Subdivision subdivision = parseEnum(kv.get("subdivision"),
                    Subdivision.class, Subdivision.QUARTER);

            int channel = Math.max(0,
                    Integer.parseInt(kv.getOrDefault("clickOutput.hardwareChannelIndex", "0")));
            double gain = clamp01(
                    Double.parseDouble(kv.getOrDefault("clickOutput.gain", "1.0")));
            boolean mainMix = Boolean.parseBoolean(
                    kv.getOrDefault("clickOutput.mainMixEnabled", "true"));
            boolean sideOut = Boolean.parseBoolean(
                    kv.getOrDefault("clickOutput.sideOutputEnabled", "false"));

            return Optional.of(new Settings(
                    enabled, volume, clickSound, subdivision,
                    new ClickOutput(channel, gain, mainMix, sideOut)));
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
        ClickOutput co = s.clickOutput();
        return "{\n"
                + "  \"enabled\": " + s.enabled() + ",\n"
                + "  \"volume\": " + s.volume() + ",\n"
                + "  \"clickSound\": \"" + s.clickSound().name() + "\",\n"
                + "  \"subdivision\": \"" + s.subdivision().name() + "\",\n"
                + "  \"clickOutput.hardwareChannelIndex\": " + co.hardwareChannelIndex() + ",\n"
                + "  \"clickOutput.gain\": " + co.gain() + ",\n"
                + "  \"clickOutput.mainMixEnabled\": " + co.mainMixEnabled() + ",\n"
                + "  \"clickOutput.sideOutputEnabled\": " + co.sideOutputEnabled() + "\n"
                + "}\n";
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static <E extends Enum<E>> E parseEnum(String raw, Class<E> type, E fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /**
     * Small tolerant flat-JSON parser matching the shape
     * {@link #toJson(Settings)} writes. Keys are strings, values are strings,
     * numbers, or booleans at a single level.
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
            i++;
            while (i < end && Character.isWhitespace(s.charAt(i))) i++;
            if (i >= end || s.charAt(i) != ':') return null;
            i++;
            while (i < end && Character.isWhitespace(s.charAt(i))) i++;
            if (i >= end) return null;
            String value;
            if (s.charAt(i) == '"') {
                i++;
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
                i++;
                value = sb.toString();
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
