package com.benesquivelmusic.daw.sdk.editor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

/**
 * Finds, parses and validates a plugin's {@code META-INF/daw-plugin.json}
 * manifest (Plugin View Design Book §4.5). Never throws for a malformed or
 * missing manifest — every problem is reported as a {@link Result.Invalid} with
 * human-readable reasons, so a bad JAR drop degrades gracefully rather than
 * escaping an exception into the install flow (§1.4, §6.8).
 *
 * <p>The reader deliberately embeds a tiny JSON parser for a <em>flat object of
 * string values</em> rather than depend on a JSON library: {@code daw-sdk} is the
 * project's dependency-free API floor.</p>
 */
public final class PluginManifestReader {

    /** The JAR entry path the manifest is expected at. */
    public static final String MANIFEST_ENTRY = "META-INF/daw-plugin.json";

    static final String KEY_PLUGIN_CLASS = "pluginClass";
    static final String KEY_ID = "id";
    static final String KEY_NAME = "name";
    static final String KEY_VERSION = "version";
    static final String KEY_VENDOR = "vendor";
    static final String KEY_TYPE = "type";
    static final String KEY_CATEGORY = "category";
    static final String KEY_ICON_HINT = "iconHint";

    /**
     * The outcome of a read/parse attempt: either a valid {@link PluginManifest}
     * or a non-empty list of validation errors.
     */
    public sealed interface Result permits Result.Valid, Result.Invalid {

        /** A successful parse. */
        record Valid(PluginManifest value) implements Result {
            public Valid {
                Objects.requireNonNull(value, "value must not be null");
            }
        }

        /** A failed parse: one or more human-readable reasons. */
        record Invalid(List<String> errors) implements Result {
            public Invalid {
                Objects.requireNonNull(errors, "errors must not be null");
                errors = List.copyOf(errors);
                if (errors.isEmpty()) {
                    throw new IllegalArgumentException("errors must not be empty");
                }
            }
        }

        /** @return {@code true} if this is a {@link Valid} result. */
        default boolean isValid() {
            return this instanceof Valid;
        }

        /** @return the manifest if valid, otherwise empty. */
        default Optional<PluginManifest> manifest() {
            return this instanceof Valid v ? Optional.of(v.value()) : Optional.empty();
        }
    }

    /**
     * Reads and validates the manifest inside the given JAR file.
     *
     * @param jarFile the plugin JAR (never {@code null})
     * @return a {@link Result.Valid} or {@link Result.Invalid}; never throws for a
     *         missing/malformed manifest or an unreadable JAR
     */
    public Result readFromJar(Path jarFile) {
        Objects.requireNonNull(jarFile, "jarFile must not be null");
        try (JarFile jar = new JarFile(jarFile.toFile())) {
            ZipEntry entry = jar.getEntry(MANIFEST_ENTRY);
            if (entry == null) {
                return new Result.Invalid(List.of("no " + MANIFEST_ENTRY + " entry in " + jarFile));
            }
            try (InputStream in = jar.getInputStream(entry)) {
                return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
            return new Result.Invalid(List.of("could not read " + jarFile + ": " + e.getMessage()));
        }
    }

    /**
     * Parses and validates manifest JSON read from the given stream (UTF-8). Does
     * not close the stream.
     *
     * @param jsonStream the manifest content (never {@code null})
     * @return a validation result; never throws for malformed content
     */
    public Result parse(InputStream jsonStream) {
        Objects.requireNonNull(jsonStream, "jsonStream must not be null");
        try {
            return parse(new String(jsonStream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new Result.Invalid(List.of("could not read manifest stream: " + e.getMessage()));
        }
    }

    /**
     * Parses and validates the given manifest JSON text.
     *
     * @param json the manifest document (never {@code null})
     * @return a validation result; never throws for malformed content
     */
    public Result parse(String json) {
        Objects.requireNonNull(json, "json must not be null");
        Map<String, String> fields;
        try {
            fields = JsonObject.parse(json);
        } catch (RuntimeException e) {
            return new Result.Invalid(List.of("malformed JSON: " + e.getMessage()));
        }

        List<String> errors = new ArrayList<>();
        String pluginClass = required(fields, KEY_PLUGIN_CLASS, errors);
        String id = required(fields, KEY_ID, errors);
        String name = required(fields, KEY_NAME, errors);
        String version = required(fields, KEY_VERSION, errors);
        String vendor = required(fields, KEY_VENDOR, errors);
        String typeName = required(fields, KEY_TYPE, errors);

        PluginType type = null;
        if (typeName != null) {
            try {
                type = PluginType.valueOf(typeName);
            } catch (IllegalArgumentException e) {
                errors.add("unknown type: " + typeName);
            }
        }

        PluginCategory category = PluginCategory.UTILITY;
        String categoryName = fields.get(KEY_CATEGORY);
        if (categoryName != null && !categoryName.isBlank()) {
            try {
                category = PluginCategory.valueOf(categoryName);
            } catch (IllegalArgumentException e) {
                errors.add("unknown category: " + categoryName);
            }
        }

        String iconHint = fields.getOrDefault(KEY_ICON_HINT, "");

        if (!errors.isEmpty()) {
            return new Result.Invalid(errors);
        }

        try {
            PluginDescriptor descriptor =
                    new PluginDescriptor(id, name, version, vendor, type, category, iconHint);
            return new Result.Valid(new PluginManifest(pluginClass, descriptor));
        } catch (RuntimeException e) {
            return new Result.Invalid(List.of("invalid manifest fields: " + e.getMessage()));
        }
    }

    private static String required(Map<String, String> fields, String key, List<String> errors) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) {
            errors.add("missing required field: " + key);
            return null;
        }
        return value;
    }

    /**
     * A deliberately tiny JSON reader for a <em>flat object of string values</em>
     * — all a {@code daw-plugin.json} manifest needs. Rejects anything that is not
     * a flat {@code {"key":"value", ...}} object (nested objects/arrays, or
     * number/boolean/null values) by throwing, which the caller converts into a
     * {@link Result.Invalid}.
     */
    private static final class JsonObject {
        private final String s;
        private int i;

        private JsonObject(String s) {
            this.s = s;
        }

        static Map<String, String> parse(String json) {
            JsonObject p = new JsonObject(json);
            Map<String, String> out = p.parseObject();
            p.skipWs();
            if (p.i != p.s.length()) {
                throw new IllegalArgumentException("trailing content at index " + p.i);
            }
            return out;
        }

        private Map<String, String> parseObject() {
            Map<String, String> out = new LinkedHashMap<>();
            skipWs();
            expect('{');
            skipWs();
            if (peek() == '}') {
                i++;
                return out;
            }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                skipWs();
                String value = parseString();
                out.put(key, value);
                skipWs();
                char c = next();
                if (c == '}') {
                    return out;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("expected ',' or '}' at index " + (i - 1));
                }
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) {
                    throw new IllegalArgumentException("unterminated string");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (i + 4 > s.length()) {
                                throw new IllegalArgumentException("bad \\u escape");
                            }
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> throw new IllegalArgumentException("bad escape \\" + e);
                    }
                } else {
                    if (c < 0x20) {
                        throw new IllegalArgumentException(
                                "unescaped control character in string at index " + (i - 1));
                    }
                    sb.append(c);
                }
            }
        }

        private void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    i++;
                } else {
                    break;
                }
            }
        }

        private char peek() {
            if (i >= s.length()) {
                throw new IllegalArgumentException("unexpected end of input");
            }
            return s.charAt(i);
        }

        private char next() {
            if (i >= s.length()) {
                throw new IllegalArgumentException("unexpected end of input");
            }
            return s.charAt(i++);
        }

        private void expect(char c) {
            char actual = next();
            if (actual != c) {
                throw new IllegalArgumentException(
                        "expected '" + c + "' at index " + (i - 1) + " but got '" + actual + "'");
            }
        }
    }
}
