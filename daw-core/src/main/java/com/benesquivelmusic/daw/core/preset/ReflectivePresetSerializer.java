package com.benesquivelmusic.daw.core.preset;

import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflective preset serializer that snapshots and restores the complete state
 * of any {@link AudioProcessor} whose parameters are declared via
 * {@link ProcessorParam}-annotated getters.
 *
 * <p>Unlike {@link com.benesquivelmusic.daw.core.mixer.ReflectiveParameterRegistry},
 * which keys parameters by numeric id for live UI editing, the preset
 * serializer keys parameters by the annotation's {@code name} attribute so
 * that presets survive id renumbering and are human-readable in JSON/XML.</p>
 *
 * <p>Reflected method handles are cached per processor class via
 * {@link MethodHandle}s — reflection happens at most once per class, never on
 * the real-time audio path. Unknown keys on restore are silently skipped
 * (forward compatibility), and out-of-range values are clamped to the declared
 * {@code [min, max]} range to prevent invalid state from malformed preset
 * files.</p>
 */
public final class ReflectivePresetSerializer {

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.publicLookup();
    private static final Map<Class<?>, List<PresetParam>> CACHE = new ConcurrentHashMap<>();

    private ReflectivePresetSerializer() {
        // utility class
    }

    /**
     * Returns {@code true} when the given processor declares any
     * {@link ProcessorParam}-annotated methods.
     */
    public static boolean isSupported(AudioProcessor processor) {
        if (processor == null) {
            return false;
        }
        return !reflect(processor.getClass()).isEmpty();
    }

    /**
     * Captures the current value of every {@link ProcessorParam}-annotated
     * parameter on {@code processor} and returns an ordered, name-keyed map.
     *
     * @throws NullPointerException if {@code processor} is {@code null}
     */
    public static Map<String, Double> snapshot(AudioProcessor processor) {
        if (processor == null) {
            throw new NullPointerException("processor must not be null");
        }
        List<PresetParam> params = reflect(processor.getClass());
        Map<String, Double> values = new LinkedHashMap<>(params.size() * 2);
        for (PresetParam p : params) {
            try {
                double v = (double) p.getter.invoke(processor);
                values.put(p.name, v);
            } catch (Throwable t) {
                throw new IllegalStateException(
                        "Failed to invoke getter for preset parameter '" + p.name
                                + "' on " + processor.getClass().getSimpleName(), t);
            }
        }
        return values;
    }

    /**
     * Restores every named parameter from {@code values} onto {@code processor}.
     *
     * <p>Unknown keys (e.g., parameters that existed in an older version of the
     * processor) are silently skipped. Values outside the declared
     * {@code [min, max]} range are clamped to that range so that malformed or
     * hand-edited preset files cannot put a processor into an invalid state.
     * {@code null} and {@code NaN} values are ignored.</p>
     *
     * @return the number of parameters successfully applied
     * @throws NullPointerException if either argument is {@code null}
     */
    public static int restore(AudioProcessor processor, Map<String, Double> values) {
        if (processor == null) {
            throw new NullPointerException("processor must not be null");
        }
        if (values == null) {
            throw new NullPointerException("values must not be null");
        }
        List<PresetParam> params = reflect(processor.getClass());
        if (params.isEmpty()) {
            return 0;
        }
        Map<String, PresetParam> byName = new LinkedHashMap<>(params.size() * 2);
        for (PresetParam p : params) {
            byName.put(p.name, p);
        }
        int applied = 0;
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            PresetParam p = byName.get(entry.getKey());
            if (p == null) {
                continue; // forward compatibility — unknown key
            }
            Double raw = entry.getValue();
            if (raw == null || Double.isNaN(raw)) {
                continue;
            }
            double clamped = Math.max(p.min, Math.min(p.max, raw));
            try {
                p.setter.invoke(processor, clamped);
                applied++;
            } catch (Throwable t) {
                throw new IllegalStateException(
                        "Failed to invoke setter for preset parameter '" + p.name
                                + "' on " + processor.getClass().getSimpleName(), t);
            }
        }
        return applied;
    }

    // ── JSON (minimal, schema-restricted) ───────────────────────────────────

    /**
     * Serializes a parameter snapshot to a portable JSON object such as
     * {@code {"Ratio":4.0,"Threshold":-20.0}}. Keys are sorted alphabetically
     * for deterministic, diff-friendly output.
     */
    public static String toJson(Map<String, Double> values) {
        if (values == null) {
            throw new NullPointerException("values must not be null");
        }
        List<Map.Entry<String, Double>> entries = new ArrayList<>(values.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        StringBuilder sb = new StringBuilder(entries.size() * 24);
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Double> e : entries) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            appendJsonString(sb, e.getKey());
            sb.append(':');
            appendJsonNumber(sb, e.getValue());
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * Parses the JSON produced by {@link #toJson(Map)} back into a map.
     *
     * @throws IllegalArgumentException if {@code json} is not a valid flat
     *                                  string→number object
     */
    public static Map<String, Double> fromJson(String json) {
        if (json == null) {
            throw new NullPointerException("json must not be null");
        }
        return new JsonParser(json).parseFlatObject();
    }

    // ── XML ─────────────────────────────────────────────────────────────────

    /**
     * Writes the snapshot as XML text of the form
     * {@code <parameters><parameter name="..." value="..."/>...</parameters>}.
     */
    public static String toXml(Map<String, Double> values) {
        if (values == null) {
            throw new NullPointerException("values must not be null");
        }
        List<Map.Entry<String, Double>> entries = new ArrayList<>(values.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        StringBuilder sb = new StringBuilder(entries.size() * 48);
        sb.append("<parameters>");
        for (Map.Entry<String, Double> e : entries) {
            sb.append("<parameter name=\"");
            appendXmlAttr(sb, e.getKey());
            sb.append("\" value=\"");
            sb.append(e.getValue());
            sb.append("\"/>");
        }
        sb.append("</parameters>");
        return sb.toString();
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private static List<PresetParam> reflect(Class<?> processorClass) {
        return CACHE.computeIfAbsent(processorClass, ReflectivePresetSerializer::discover);
    }

    private static List<PresetParam> discover(Class<?> processorClass) {
        List<PresetParam> params = new ArrayList<>();
        for (Method m : processorClass.getMethods()) {
            ProcessorParam ann = m.getAnnotation(ProcessorParam.class);
            if (ann == null) {
                continue;
            }
            if (m.getParameterCount() != 0) {
                throw new IllegalStateException(
                        "@ProcessorParam getter must have zero parameters: "
                                + processorClass.getSimpleName() + "#" + m.getName());
            }
            Class<?> ret = m.getReturnType();
            if (ret != double.class && ret != Double.class) {
                throw new IllegalStateException(
                        "@ProcessorParam getter must return double: "
                                + processorClass.getSimpleName() + "#" + m.getName());
            }
            String getterName = m.getName();
            if (!getterName.startsWith("get")) {
                throw new IllegalStateException(
                        "@ProcessorParam getter must follow getXxx convention: "
                                + processorClass.getSimpleName() + "#" + getterName);
            }
            String setterName = "set" + getterName.substring(3);
            Method setter;
            try {
                setter = processorClass.getMethod(setterName, double.class);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(
                        "No matching setter " + setterName + "(double) for @ProcessorParam getter "
                                + processorClass.getSimpleName() + "#" + getterName, e);
            }
            MethodHandle getterHandle;
            MethodHandle setterHandle;
            try {
                getterHandle = LOOKUP.unreflect(m);
                setterHandle = LOOKUP.unreflect(setter);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(
                        "Cannot access accessor methods for @ProcessorParam on "
                                + processorClass.getSimpleName(), e);
            }
            params.add(new PresetParam(ann.name(), ann.min(), ann.max(),
                    getterHandle, setterHandle));
        }
        params.sort(Comparator.comparing(p -> p.name));
        // Validate name uniqueness
        for (int i = 1; i < params.size(); i++) {
            if (params.get(i).name.equals(params.get(i - 1).name)) {
                throw new IllegalStateException(
                        "Duplicate @ProcessorParam name '" + params.get(i).name
                                + "' on " + processorClass.getSimpleName());
            }
        }
        return List.copyOf(params);
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
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
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

    private static void appendJsonNumber(StringBuilder sb, double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            sb.append("null");
        } else {
            sb.append(v);
        }
    }

    private static void appendXmlAttr(StringBuilder sb, String s) {
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> sb.append(c);
            }
        }
    }

    /**
     * Cached, immutable descriptor bundling annotation metadata with
     * pre-bound {@link MethodHandle}s to the getter and setter.
     */
    private static final class PresetParam {
        final String name;
        final double min;
        final double max;
        final MethodHandle getter;
        final MethodHandle setter;

        PresetParam(String name, double min, double max,
                    MethodHandle getter, MethodHandle setter) {
            this.name = name;
            this.min = min;
            this.max = max;
            this.getter = getter;
            this.setter = setter;
        }
    }

    /** Minimal JSON parser for flat {@code {"k":number,...}} objects. */
    private static final class JsonParser {
        private final String src;
        private int pos;

        JsonParser(String src) {
            this.src = src;
            this.pos = 0;
        }

        Map<String, Double> parseFlatObject() {
            skipWs();
            expect('{');
            Map<String, Double> out = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') {
                pos++;
                skipWs();
                if (pos != src.length()) {
                    throw err("Trailing content after closing brace");
                }
                return out;
            }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                skipWs();
                Double value = parseNumberOrNull();
                if (value != null) {
                    out.put(key, value);
                }
                skipWs();
                char c = read();
                if (c == ',') {
                    continue;
                }
                if (c == '}') {
                    break;
                }
                throw err("Expected ',' or '}' but got '" + c + "'");
            }
            skipWs();
            if (pos != src.length()) {
                throw err("Trailing content after closing brace");
            }
            return out;
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= src.length()) {
                    throw err("Unterminated string");
                }
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= src.length()) {
                        throw err("Unterminated escape");
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
                                throw err("Bad \\u escape");
                            }
                            sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw err("Unknown escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private Double parseNumberOrNull() {
            if (src.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            int start = pos;
            if (peek() == '-' || peek() == '+') {
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
                throw err("Expected number");
            }
            try {
                return Double.parseDouble(src.substring(start, pos));
            } catch (NumberFormatException nfe) {
                throw err("Invalid number: " + src.substring(start, pos));
            }
        }

        private void skipWs() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private char peek() {
            if (pos >= src.length()) {
                throw err("Unexpected end of input");
            }
            return src.charAt(pos);
        }

        private char read() {
            if (pos >= src.length()) {
                throw err("Unexpected end of input");
            }
            return src.charAt(pos++);
        }

        private void expect(char c) {
            if (pos >= src.length() || src.charAt(pos) != c) {
                throw err("Expected '" + c + "'");
            }
            pos++;
        }

        private IllegalArgumentException err(String msg) {
            return new IllegalArgumentException(msg + " at position " + pos + " in: " + src);
        }
    }
}
