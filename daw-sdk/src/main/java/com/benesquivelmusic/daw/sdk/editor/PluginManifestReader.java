package com.benesquivelmusic.daw.sdk.editor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

/**
 * Finds, parses and validates a plugin's {@code META-INF/daw-plugin.json}
 * manifest (Plugin View Design Book §4.5). Never throws for a malformed or
 * missing manifest — every problem is reported as a {@link Result.Invalid} (or
 * {@link BundleResult.Invalid}) with human-readable reasons, so a bad JAR drop
 * degrades gracefully rather than escaping an exception into the install flow
 * (§1.4, §6.8).
 *
 * <p>A manifest comes in two forms:</p>
 * <ul>
 *   <li>a single flat object — one plugin per JAR (the story-300 form), read
 *       with {@link #readFromJar(Path)} / {@link #parse(String)};</li>
 *   <li>a top-level array of those same flat objects — a <em>bundle</em>
 *       declaring several plugins in one JAR, so the §6.8 install flow can offer
 *       "Install all" and register N entries from a single drop (story 303).
 *       Read with {@link #readAllFromJar(Path)} / {@link #parseAll(String)}.</li>
 * </ul>
 *
 * <p>The bundle methods accept either form (a single object is treated as a
 * one-plugin bundle), so a story-300 manifest keeps working unchanged. The
 * legacy single-object methods reject an array gracefully, pointing the caller
 * at the bundle API.</p>
 *
 * <p>The reader deliberately embeds a tiny JSON parser for a <em>flat object of
 * string values</em> (and a top-level array of such objects) rather than depend
 * on a JSON library: {@code daw-sdk} is the project's dependency-free API floor.</p>
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
     * The outcome of a single-manifest read/parse attempt: either a valid
     * {@link PluginManifest} or a non-empty list of validation errors.
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
     * The outcome of a bundle read/parse attempt: either a non-empty list of
     * valid {@link PluginManifest}s in declared order, or a non-empty list of
     * validation errors (§6.8, story 303). A single-object manifest yields a
     * one-element {@link Valid}.
     */
    public sealed interface BundleResult permits BundleResult.Valid, BundleResult.Invalid {

        /** A successful parse of one or more manifests, in declared order. */
        record Valid(List<PluginManifest> values) implements BundleResult {
            public Valid {
                Objects.requireNonNull(values, "values must not be null");
                values = List.copyOf(values);
                if (values.isEmpty()) {
                    throw new IllegalArgumentException("values must not be empty");
                }
            }
        }

        /** A failed parse: one or more human-readable reasons. */
        record Invalid(List<String> errors) implements BundleResult {
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

        /** @return the manifests if valid, otherwise an empty list. */
        default List<PluginManifest> manifests() {
            return this instanceof Valid v ? v.values() : List.of();
        }
    }

    /**
     * Reads and validates the single manifest inside the given JAR file. A JAR
     * whose manifest is a bundle (top-level array) is reported as
     * {@link Result.Invalid}; use {@link #readAllFromJar(Path)} for those.
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
     * Parses and validates a single-manifest document read from the given stream
     * (UTF-8). Does not close the stream.
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
     * Parses and validates a single-manifest document. A top-level array (a
     * bundle) is reported as {@link Result.Invalid} pointing at the bundle API;
     * use {@link #parseAll(String)} for those.
     *
     * @param json the manifest document (never {@code null})
     * @return a validation result; never throws for malformed content
     */
    public Result parse(String json) {
        Objects.requireNonNull(json, "json must not be null");
        ParsedDocument document;
        try {
            document = JsonReader.parse(json);
        } catch (RuntimeException e) {
            return new Result.Invalid(List.of("malformed JSON: " + e.getMessage()));
        }

        if (document.bundle()) {
            return new Result.Invalid(List.of(
                    "manifest is a bundle (top-level array); use readAllFromJar/parseAll"));
        }

        List<String> errors = new ArrayList<>();
        PluginManifest manifest = validate(document.elements().get(0), errors);
        if (!errors.isEmpty()) {
            return new Result.Invalid(errors);
        }
        return new Result.Valid(manifest);
    }

    /**
     * Reads and validates every manifest declared in the given JAR file, whether
     * the manifest is a single object (a one-plugin bundle) or a top-level array
     * (§6.8, story 303).
     *
     * @param jarFile the plugin JAR (never {@code null})
     * @return a {@link BundleResult.Valid} or {@link BundleResult.Invalid}; never
     *         throws for a missing/malformed manifest or an unreadable JAR
     */
    public BundleResult readAllFromJar(Path jarFile) {
        Objects.requireNonNull(jarFile, "jarFile must not be null");
        try (JarFile jar = new JarFile(jarFile.toFile())) {
            ZipEntry entry = jar.getEntry(MANIFEST_ENTRY);
            if (entry == null) {
                return new BundleResult.Invalid(
                        List.of("no " + MANIFEST_ENTRY + " entry in " + jarFile));
            }
            try (InputStream in = jar.getInputStream(entry)) {
                return parseAll(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
            return new BundleResult.Invalid(
                    List.of("could not read " + jarFile + ": " + e.getMessage()));
        }
    }

    /**
     * Parses and validates a bundle document read from the given stream (UTF-8).
     * Does not close the stream.
     *
     * @param jsonStream the manifest content (never {@code null})
     * @return a bundle result; never throws for malformed content
     */
    public BundleResult parseAll(InputStream jsonStream) {
        Objects.requireNonNull(jsonStream, "jsonStream must not be null");
        try {
            return parseAll(new String(jsonStream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new BundleResult.Invalid(
                    List.of("could not read manifest stream: " + e.getMessage()));
        }
    }

    /**
     * Parses and validates a bundle document. A single flat object is accepted as
     * a one-plugin bundle (full backward compatibility with story 300); a
     * top-level array declares several plugins in declared order. Every element is
     * validated with the same field rules as {@link #parse(String)}; an element's
     * errors are prefixed with its index (for example
     * {@code "plugins[1]: missing required field: vendor"}). Any element error, an
     * empty array, or a duplicate {@code pluginClass} across elements yields
     * {@link BundleResult.Invalid} carrying all accumulated errors — no partial
     * results leak.
     *
     * @param json the manifest document (never {@code null})
     * @return a bundle result; never throws for malformed content
     */
    public BundleResult parseAll(String json) {
        Objects.requireNonNull(json, "json must not be null");
        ParsedDocument document;
        try {
            document = JsonReader.parse(json);
        } catch (RuntimeException e) {
            return new BundleResult.Invalid(List.of("malformed JSON: " + e.getMessage()));
        }

        List<Map<String, String>> elements = document.elements();

        if (!document.bundle()) {
            // A single flat object is a one-plugin bundle; keep its errors
            // unprefixed so they read exactly like the single-object API.
            List<String> errors = new ArrayList<>();
            PluginManifest manifest = validate(elements.get(0), errors);
            if (!errors.isEmpty()) {
                return new BundleResult.Invalid(errors);
            }
            return new BundleResult.Valid(List.of(manifest));
        }

        if (elements.isEmpty()) {
            return new BundleResult.Invalid(List.of("manifest declares no plugins"));
        }

        List<String> errors = new ArrayList<>();
        List<PluginManifest> manifests = new ArrayList<>();
        for (int index = 0; index < elements.size(); index++) {
            List<String> elementErrors = new ArrayList<>();
            PluginManifest manifest = validate(elements.get(index), elementErrors);
            if (elementErrors.isEmpty()) {
                manifests.add(manifest);
            } else {
                for (String error : elementErrors) {
                    errors.add("plugins[" + index + "]: " + error);
                }
            }
        }

        // A repeated pluginClass would register two plugins under one class name
        // (§6.8): reject the whole bundle rather than install a conflict.
        collectDuplicatePluginClasses(elements, errors);

        if (!errors.isEmpty()) {
            return new BundleResult.Invalid(errors);
        }
        return new BundleResult.Valid(manifests);
    }

    /**
     * Validates one flat field map against the manifest field rules, adding any
     * problems to {@code errors}.
     *
     * @return the manifest when the fields are valid, otherwise {@code null} (with
     *         reasons appended to {@code errors})
     */
    private static PluginManifest validate(Map<String, String> fields, List<String> errors) {
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
            return null;
        }

        try {
            PluginDescriptor descriptor =
                    new PluginDescriptor(id, name, version, vendor, type, category, iconHint);
            return new PluginManifest(pluginClass, descriptor);
        } catch (RuntimeException e) {
            errors.add("invalid manifest fields: " + e.getMessage());
            return null;
        }
    }

    private static void collectDuplicatePluginClasses(
            List<Map<String, String>> elements, List<String> errors) {
        Set<String> seen = new HashSet<>();
        Set<String> reported = new HashSet<>();
        for (Map<String, String> element : elements) {
            String pluginClass = element.get(KEY_PLUGIN_CLASS);
            if (pluginClass == null || pluginClass.isBlank()) {
                continue;
            }
            if (!seen.add(pluginClass) && reported.add(pluginClass)) {
                errors.add("duplicate pluginClass across plugins: " + pluginClass);
            }
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
     * A parsed manifest document: one or more flat field maps. {@code bundle} is
     * {@code true} when the source was a top-level array, {@code false} when it
     * was a single object (in which case {@code elements} always has one entry).
     */
    private record ParsedDocument(boolean bundle, List<Map<String, String>> elements) {}

    /**
     * A deliberately tiny JSON reader for a <em>flat object of string values</em>,
     * or a <em>top-level array</em> of such objects — all a {@code daw-plugin.json}
     * manifest needs. Rejects anything else (nested objects/arrays inside an
     * element, or number/boolean/null values) by throwing, which the caller
     * converts into an {@code Invalid} result.
     */
    private static final class JsonReader {
        private final String s;
        private int i;

        private JsonReader(String s) {
            this.s = s;
        }

        static ParsedDocument parse(String json) {
            JsonReader p = new JsonReader(json);
            p.skipWs();
            ParsedDocument document = p.peek() == '['
                    ? new ParsedDocument(true, p.parseObjectArray())
                    : new ParsedDocument(false, List.of(p.parseObject()));
            p.skipWs();
            if (p.i != p.s.length()) {
                throw new IllegalArgumentException("trailing content at index " + p.i);
            }
            return document;
        }

        private List<Map<String, String>> parseObjectArray() {
            List<Map<String, String>> out = new ArrayList<>();
            skipWs();
            expect('[');
            skipWs();
            if (peek() == ']') {
                i++;
                return out;
            }
            while (true) {
                skipWs();
                out.add(parseObject());
                skipWs();
                char c = next();
                if (c == ']') {
                    return out;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("expected ',' or ']' at index " + (i - 1));
                }
            }
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
