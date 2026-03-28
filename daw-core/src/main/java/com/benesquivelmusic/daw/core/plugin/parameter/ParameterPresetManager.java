package com.benesquivelmusic.daw.core.plugin.parameter;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Manages loading and saving of {@link ParameterPreset} instances as JSON files.
 *
 * <p>Each preset is serialized as a simple JSON object with the following structure:</p>
 * <pre>{@code
 * {
 *   "name": "Warm Vocal EQ",
 *   "factory": false,
 *   "values": {
 *     "0": 0.75,
 *     "1": 0.5,
 *     "2": 0.3
 *   }
 * }
 * }</pre>
 *
 * <p>JSON parsing and writing are implemented with minimal hand-written logic
 * to avoid external library dependencies.</p>
 */
public final class ParameterPresetManager {

    private final Path presetsDirectory;

    /**
     * Creates a preset manager storing presets in the specified directory.
     *
     * @param presetsDirectory the directory for preset files
     * @throws NullPointerException if {@code presetsDirectory} is {@code null}
     */
    public ParameterPresetManager(Path presetsDirectory) {
        this.presetsDirectory = Objects.requireNonNull(presetsDirectory, "presetsDirectory must not be null");
    }

    /**
     * Returns the presets directory.
     *
     * @return the presets directory path
     */
    public Path getPresetsDirectory() {
        return presetsDirectory;
    }

    /**
     * Saves a preset to a JSON file.
     *
     * <p>The file name is derived from the preset name with non-alphanumeric
     * characters replaced by underscores.</p>
     *
     * @param preset the preset to save
     * @return the path to the saved file
     * @throws IOException if the file cannot be written
     */
    public Path savePreset(ParameterPreset preset) throws IOException {
        Objects.requireNonNull(preset, "preset must not be null");
        Files.createDirectories(presetsDirectory);
        String fileName = sanitizeFileName(preset.name()) + ".json";
        Path filePath = presetsDirectory.resolve(fileName);
        try (Writer writer = Files.newBufferedWriter(filePath)) {
            writer.write(toJson(preset));
        }
        return filePath;
    }

    /**
     * Loads a preset from a JSON file.
     *
     * @param filePath the path to the JSON file
     * @return the loaded preset
     * @throws IOException if the file cannot be read or is malformed
     */
    public ParameterPreset loadPreset(Path filePath) throws IOException {
        Objects.requireNonNull(filePath, "filePath must not be null");
        String json;
        try (Reader reader = Files.newBufferedReader(filePath)) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, read);
            }
            json = sb.toString();
        }
        return fromJson(json);
    }

    /**
     * Loads all preset files (*.json) from the presets directory.
     *
     * @return the list of loaded presets
     * @throws IOException if the directory cannot be read
     */
    public List<ParameterPreset> loadAllPresets() throws IOException {
        List<ParameterPreset> presets = new ArrayList<>();
        if (!Files.isDirectory(presetsDirectory)) {
            return presets;
        }
        try (java.util.stream.Stream<Path> paths = Files.list(presetsDirectory)) {
            List<Path> jsonFiles = paths
                    .filter(p -> p.toString().endsWith(".json"))
                    .sorted()
                    .toList();
            for (Path path : jsonFiles) {
                presets.add(loadPreset(path));
            }
        }
        return presets;
    }

    /**
     * Deletes a preset file.
     *
     * @param presetName the name of the preset to delete
     * @return {@code true} if the file was deleted
     * @throws IOException if an I/O error occurs
     */
    public boolean deletePreset(String presetName) throws IOException {
        Objects.requireNonNull(presetName, "presetName must not be null");
        String fileName = sanitizeFileName(presetName) + ".json";
        Path filePath = presetsDirectory.resolve(fileName);
        return Files.deleteIfExists(filePath);
    }

    // ── JSON serialization (minimal, no external dependencies) ────────────

    static String toJson(ParameterPreset preset) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"name\": ").append(jsonString(preset.name())).append(",\n");
        sb.append("  \"factory\": ").append(preset.factory()).append(",\n");
        sb.append("  \"values\": {");
        Map<Integer, Double> values = preset.values();
        int i = 0;
        for (Map.Entry<Integer, Double> entry : values.entrySet()) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("\n    ").append(jsonString(String.valueOf(entry.getKey())))
              .append(": ").append(entry.getValue());
            i++;
        }
        if (!values.isEmpty()) {
            sb.append("\n  ");
        }
        sb.append("}\n");
        sb.append("}");
        return sb.toString();
    }

    static ParameterPreset fromJson(String json) throws IOException {
        try {
            String name = extractStringField(json, "name");
            boolean factory = extractBooleanField(json, "factory");
            Map<Integer, Double> values = extractValuesObject(json);
            return new ParameterPreset(name, values, factory);
        } catch (Exception e) {
            throw new IOException("Malformed preset JSON: " + e.getMessage(), e);
        }
    }

    private static String extractStringField(String json, String field) {
        String pattern = "\"" + field + "\"";
        int fieldIndex = json.indexOf(pattern);
        if (fieldIndex == -1) {
            throw new IllegalArgumentException("Missing field: " + field);
        }
        int colonIndex = json.indexOf(':', fieldIndex + pattern.length());
        int quoteStart = json.indexOf('"', colonIndex + 1);
        int quoteEnd = json.indexOf('"', quoteStart + 1);
        return json.substring(quoteStart + 1, quoteEnd);
    }

    private static boolean extractBooleanField(String json, String field) {
        String pattern = "\"" + field + "\"";
        int fieldIndex = json.indexOf(pattern);
        if (fieldIndex == -1) {
            return false;
        }
        int colonIndex = json.indexOf(':', fieldIndex + pattern.length());
        String remainder = json.substring(colonIndex + 1).strip();
        return remainder.startsWith("true");
    }

    private static Map<Integer, Double> extractValuesObject(String json) {
        int valuesStart = json.indexOf("\"values\"");
        if (valuesStart == -1) {
            throw new IllegalArgumentException("Missing 'values' field");
        }
        int braceStart = json.indexOf('{', valuesStart);
        int braceEnd = findMatchingBrace(json, braceStart);
        String valuesJson = json.substring(braceStart + 1, braceEnd).strip();

        Map<Integer, Double> values = new LinkedHashMap<>();
        if (valuesJson.isEmpty()) {
            return values;
        }

        String[] entries = valuesJson.split(",");
        for (String entry : entries) {
            String trimmed = entry.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            int colonIndex = trimmed.indexOf(':');
            String key = trimmed.substring(0, colonIndex).strip();
            // Remove quotes from key
            if (key.startsWith("\"") && key.endsWith("\"")) {
                key = key.substring(1, key.length() - 1);
            }
            String value = trimmed.substring(colonIndex + 1).strip();
            values.put(Integer.parseInt(key), Double.parseDouble(value));
        }
        return values;
    }

    private static int findMatchingBrace(String json, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new IllegalArgumentException("Unmatched brace at index " + openIndex);
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
