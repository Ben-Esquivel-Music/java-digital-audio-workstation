package com.benesquivelmusic.daw.app.ui.theme;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Reads and writes {@link Theme} JSON files using a hand-rolled parser
 * (no external dependency), matching the lightweight JSON-handling style
 * used elsewhere in {@code daw-app} (see
 * {@code CommandPaletteRecentsStore} and {@code WorkspaceJson}).
 *
 * <p>The on-disk schema is documented in the bundled theme files under
 * {@code src/main/resources/themes/}.</p>
 */
public final class ThemeJson {

    private ThemeJson() {
        // utility class
    }

    /** Loads a theme from a UTF-8 input stream. */
    public static Theme load(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in must not be null");
        String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        return parse(text);
    }

    /** Loads a theme from a path. */
    public static Theme load(Path path) throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        return parse(Files.readString(path, StandardCharsets.UTF_8));
    }

    /** Parses the JSON text into a {@link Theme}. */
    public static Theme parse(String json) {
        Objects.requireNonNull(json, "json must not be null");
        Parser p = new Parser(json);
        Object root = p.parseValue();
        p.skipWhitespace();
        if (!p.atEnd()) {
            throw new IllegalArgumentException(
                    "Unexpected trailing content at offset " + p.pos);
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Theme JSON root must be an object");
        }
        return fromMap(map);
    }

    @SuppressWarnings("unchecked")
    private static Theme fromMap(Map<?, ?> map) {
        String id = requireString(map, "id");
        String name = requireString(map, "name");
        String description = optionalString(map, "description", "");
        boolean dark = optionalBoolean(map, "dark", true);

        Object colorsObj = map.get("colors");
        if (!(colorsObj instanceof Map<?, ?> colorsMap)) {
            throw new IllegalArgumentException("Theme '" + id + "' has no 'colors' object");
        }
        Map<String, Theme.Color> colors = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : colorsMap.entrySet()) {
            String colorName = (String) e.getKey();
            if (!(e.getValue() instanceof Map<?, ?> colorEntry)) {
                throw new IllegalArgumentException(
                        "Theme '" + id + "' color '" + colorName + "' must be an object");
            }
            String value = requireString(colorEntry, "value");
            String role = optionalString(colorEntry, "role", "foreground");
            colors.put(colorName, new Theme.Color(value, role));
        }

        Object pairsObj = map.get("pairs");
        if (!(pairsObj instanceof List<?> pairsList)) {
            throw new IllegalArgumentException("Theme '" + id + "' has no 'pairs' array");
        }
        List<Theme.Pair> pairs = new ArrayList<>(pairsList.size());
        for (Object pairObj : pairsList) {
            if (!(pairObj instanceof Map<?, ?> pairMap)) {
                throw new IllegalArgumentException(
                        "Theme '" + id + "' pairs entry must be an object");
            }
            pairs.add(new Theme.Pair(
                    requireString(pairMap, "foreground"),
                    requireString(pairMap, "background")));
        }

        return new Theme(id, name, description, dark, colors, pairs);
    }

    private static String requireString(Map<?, ?> map, String key) {
        Object v = map.get(key);
        if (!(v instanceof String s)) {
            throw new IllegalArgumentException("Missing or non-string field: " + key);
        }
        return s;
    }

    private static String optionalString(Map<?, ?> map, String key, String defaultValue) {
        Object v = map.get(key);
        return (v instanceof String s) ? s : defaultValue;
    }

    private static boolean optionalBoolean(Map<?, ?> map, String key, boolean defaultValue) {
        Object v = map.get(key);
        return (v instanceof Boolean b) ? b : defaultValue;
    }

    /** Writes a theme as pretty-printed JSON. Used by the duplicate-and-edit flow. */
    public static void write(Theme theme, Path path) throws IOException {
        Objects.requireNonNull(theme, "theme must not be null");
        Objects.requireNonNull(path, "path must not be null");
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write(toJson(theme));
        }
    }

    /** Serializes a theme to a pretty-printed JSON string. */
    public static String toJson(Theme theme) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\n");
        sb.append("  \"id\": ").append(quote(theme.id())).append(",\n");
        sb.append("  \"name\": ").append(quote(theme.name())).append(",\n");
        sb.append("  \"description\": ").append(quote(theme.description())).append(",\n");
        sb.append("  \"dark\": ").append(theme.dark()).append(",\n");
        sb.append("  \"colors\": {\n");
        int i = 0;
        int n = theme.colors().size();
        for (Map.Entry<String, Theme.Color> e : theme.colors().entrySet()) {
            sb.append("    ").append(quote(e.getKey())).append(": { ")
                    .append("\"value\": ").append(quote(e.getValue().value()))
                    .append(", \"role\": ").append(quote(e.getValue().role()))
                    .append(" }");
            if (++i < n) sb.append(",");
            sb.append("\n");
        }
        sb.append("  },\n");
        sb.append("  \"pairs\": [\n");
        for (int p = 0; p < theme.pairs().size(); p++) {
            Theme.Pair pair = theme.pairs().get(p);
            sb.append("    { \"foreground\": ").append(quote(pair.foreground()))
                    .append(", \"background\": ").append(quote(pair.background()))
                    .append(" }");
            if (p + 1 < theme.pairs().size()) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    // ── tiny JSON parser (objects, arrays, strings, numbers, booleans, null) ──

    private static final class Parser {
        private final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        void skipWhitespace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        Object parseValue() {
            skipWhitespace();
            if (atEnd()) {
                throw new IllegalArgumentException("Unexpected end of input");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                default -> parseNumber();
            };
        }

        Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> out = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return out;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                out.put(key, value);
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    return out;
                }
                throw new IllegalArgumentException(
                        "Expected ',' or '}' at offset " + pos + ", got '" + c + "'");
            }
        }

        List<Object> parseArray() {
            expect('[');
            List<Object> out = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return out;
            }
            while (true) {
                out.add(parseValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    return out;
                }
                throw new IllegalArgumentException(
                        "Expected ',' or ']' at offset " + pos + ", got '" + c + "'");
            }
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= s.length()) {
                        throw new IllegalArgumentException("Unterminated escape");
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"', '\\', '/' -> sb.append(e);
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (pos + 4 > s.length()) {
                                throw new IllegalArgumentException("Bad unicode escape");
                            }
                            int code = Integer.parseInt(s.substring(pos, pos + 4), 16);
                            sb.append((char) code);
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException(
                                "Bad escape: \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        Boolean parseBoolean() {
            if (s.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Expected boolean at offset " + pos);
        }

        Object parseNull() {
            if (s.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new IllegalArgumentException("Expected null at offset " + pos);
        }

        Number parseNumber() {
            int start = pos;
            if (peek() == '-') pos++;
            while (pos < s.length() && "0123456789.eE+-".indexOf(s.charAt(pos)) >= 0) {
                pos++;
            }
            String token = s.substring(start, pos);
            try {
                if (token.contains(".") || token.contains("e") || token.contains("E")) {
                    return Double.parseDouble(token);
                }
                return Long.parseLong(token);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Bad number: " + token, e);
            }
        }

        char peek() {
            if (atEnd()) {
                throw new IllegalArgumentException("Unexpected end of input");
            }
            return s.charAt(pos);
        }

        void expect(char c) {
            skipWhitespace();
            if (atEnd() || s.charAt(pos) != c) {
                throw new IllegalArgumentException(
                        "Expected '" + c + "' at offset " + pos);
            }
            pos++;
        }
    }

    /** Re-throws an IO error as unchecked for use in lambda contexts. */
    static UncheckedIOException unchecked(IOException e) {
        return new UncheckedIOException(e);
    }
}
