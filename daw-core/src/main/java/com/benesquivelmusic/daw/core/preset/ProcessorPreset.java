package com.benesquivelmusic.daw.core.preset;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A standalone preset file capturing a named configuration of a single
 * {@link com.benesquivelmusic.daw.sdk.audio.AudioProcessor}.
 *
 * <p>{@code processorClassName} uniquely identifies the processor this preset
 * applies to; {@code displayName} is the user-visible label (e.g.,
 * "Vocal Compressor"); {@code parameterValues} holds the
 * {@link com.benesquivelmusic.daw.sdk.annotation.ProcessorParam} name→value
 * snapshot produced by {@link ReflectivePresetSerializer#snapshot}.</p>
 *
 * @param processorClassName fully qualified class name of the target processor
 * @param displayName        user-visible preset name
 * @param parameterValues    parameter snapshot keyed by {@code @ProcessorParam} name
 */
public record ProcessorPreset(
        String processorClassName,
        String displayName,
        Map<String, Double> parameterValues) {

    public ProcessorPreset {
        Objects.requireNonNull(processorClassName, "processorClassName must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(parameterValues, "parameterValues must not be null");
        if (processorClassName.isBlank()) {
            throw new IllegalArgumentException("processorClassName must not be blank");
        }
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        // Defensive copy with deterministic ordering so equals/hashCode and
        // serialized output are stable.
        parameterValues = Map.copyOf(new LinkedHashMap<>(parameterValues));
    }

    // ── JSON serialization ──────────────────────────────────────────────────

    /**
     * Serializes this preset to a JSON document of the form:
     * <pre>{@code
     * {
     *   "processorClassName": "...",
     *   "displayName": "...",
     *   "parameterValues": { "Threshold": -20.0, ... }
     * }
     * }</pre>
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\n  \"processorClassName\": ");
        appendJsonString(sb, processorClassName);
        sb.append(",\n  \"displayName\": ");
        appendJsonString(sb, displayName);
        sb.append(",\n  \"parameterValues\": ");
        sb.append(ReflectivePresetSerializer.toJson(parameterValues));
        sb.append("\n}\n");
        return sb.toString();
    }

    /**
     * Parses a preset JSON document produced by {@link #toJson()}.
     *
     * @throws IllegalArgumentException if the document is malformed or missing
     *                                  required fields
     */
    public static ProcessorPreset fromJson(String json) {
        Objects.requireNonNull(json, "json must not be null");
        Parser p = new Parser(json);
        p.skipWs();
        p.expect('{');
        String className = null;
        String displayName = null;
        Map<String, Double> params = null;
        boolean first = true;
        while (true) {
            p.skipWs();
            if (p.peek() == '}') {
                p.pos++;
                break;
            }
            if (!first) {
                p.expect(',');
                p.skipWs();
            }
            first = false;
            String key = p.parseString();
            p.skipWs();
            p.expect(':');
            p.skipWs();
            switch (key) {
                case "processorClassName" -> className = p.parseString();
                case "displayName" -> displayName = p.parseString();
                case "parameterValues" -> params = p.parseFlatObject();
                default -> p.skipValue();
            }
        }
        if (className == null) {
            throw new IllegalArgumentException("Missing processorClassName");
        }
        if (displayName == null) {
            throw new IllegalArgumentException("Missing displayName");
        }
        if (params == null) {
            params = new LinkedHashMap<>();
        }
        return new ProcessorPreset(className, displayName, params);
    }

    private static void appendJsonString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
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
        sb.append('"');
    }

    /** Focused JSON parser supporting the preset document shape. */
    private static final class Parser {
        final String src;
        int pos;

        Parser(String src) {
            this.src = src;
            this.pos = 0;
        }

        void skipWs() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        char peek() {
            if (pos >= src.length()) {
                throw new IllegalArgumentException("Unexpected end of input at " + pos);
            }
            return src.charAt(pos);
        }

        void expect(char c) {
            if (pos >= src.length() || src.charAt(pos) != c) {
                throw new IllegalArgumentException(
                        "Expected '" + c + "' at position " + pos);
            }
            pos++;
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= src.length()) {
                    throw new IllegalArgumentException("Unterminated string");
                }
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= src.length()) {
                        throw new IllegalArgumentException("Unterminated escape");
                    }
                    char e = src.charAt(pos++);
                    switch (e) {
                        case '"', '\\', '/' -> sb.append(e);
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (pos + 4 > src.length()) {
                                throw new IllegalArgumentException("Bad \\u escape");
                            }
                            sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("Unknown escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Map<String, Double> parseFlatObject() {
            expect('{');
            Map<String, Double> out = new LinkedHashMap<>();
            skipWs();
            if (pos < src.length() && src.charAt(pos) == '}') {
                pos++;
                return out;
            }
            while (true) {
                skipWs();
                String k = parseString();
                skipWs();
                expect(':');
                skipWs();
                Double v = parseNumber();
                if (v != null) {
                    out.put(k, v);
                }
                skipWs();
                if (pos >= src.length()) {
                    throw new IllegalArgumentException("Unterminated object");
                }
                char c = src.charAt(pos++);
                if (c == ',') {
                    continue;
                }
                if (c == '}') {
                    return out;
                }
                throw new IllegalArgumentException("Expected ',' or '}' at " + (pos - 1));
            }
        }

        Double parseNumber() {
            if (src.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            int start = pos;
            if (pos < src.length() && (src.charAt(pos) == '-' || src.charAt(pos) == '+')) {
                pos++;
            }
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E'
                        || c == '+' || c == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            if (start == pos) {
                throw new IllegalArgumentException("Expected number at " + pos);
            }
            return Double.parseDouble(src.substring(start, pos));
        }

        /** Skips over any JSON value without interpreting it. */
        void skipValue() {
            skipWs();
            if (pos >= src.length()) {
                throw new IllegalArgumentException("Unexpected end of input");
            }
            char c = src.charAt(pos);
            if (c == '"') {
                parseString();
            } else if (c == '{' || c == '[') {
                char close = (c == '{') ? '}' : ']';
                pos++;
                int depth = 1;
                while (pos < src.length() && depth > 0) {
                    char x = src.charAt(pos++);
                    if (x == '"') {
                        pos--;
                        parseString();
                    } else if (x == '{' || x == '[') {
                        depth++;
                    } else if (x == '}' || x == ']') {
                        depth--;
                    }
                }
                if (depth != 0) {
                    throw new IllegalArgumentException("Unbalanced brackets");
                }
            } else if (src.startsWith("true", pos)) {
                pos += 4;
            } else if (src.startsWith("false", pos)) {
                pos += 5;
            } else if (src.startsWith("null", pos)) {
                pos += 4;
            } else {
                // number
                parseNumber();
            }
        }
    }

    /**
     * Utility: returns a preset's keys in sorted order (used by equality tests
     * to avoid map-order surprises).
     */
    public List<String> sortedParameterNames() {
        List<String> names = new ArrayList<>(parameterValues.keySet());
        names.sort(String::compareTo);
        return List.copyOf(names);
    }
}
