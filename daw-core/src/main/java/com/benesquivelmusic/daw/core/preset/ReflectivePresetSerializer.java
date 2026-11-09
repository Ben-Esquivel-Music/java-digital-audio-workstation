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
import java.util.SequencedMap;

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
 * the real-time audio path. The cache is a {@link ClassValue}, whose entry
 * each class holds itself, so it never keeps a class reachable: a JAR
 * plugin's class — and the class loader of its insert, one per insert and
 * project open — can be collected once the insert is disposed (story 334).
 * Unknown keys on restore are skipped (forward
 * compatibility), and out-of-range values are clamped to the declared
 * {@code [min, max]} range to prevent invalid state from malformed preset
 * files; {@link #restore} does both silently, while {@link #restoreEach}
 * reports what it did with every value ({@link ParameterRestore}).</p>
 */
public final class ReflectivePresetSerializer {

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.publicLookup();
    private static final ClassValue<List<PresetParam>> CACHE = new ClassValue<>() {
        @Override
        protected List<PresetParam> computeValue(Class<?> type) {
            return discover(type);
        }
    };

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
        return snapshot(processor, Map.of());
    }

    /** Snapshots name-keyed state with previously captured, unapplied editor values. */
    public static Map<String, Double> snapshot(AudioProcessor processor, Map<Integer, Double> pendingValues) {
        if (processor == null) {
            throw new NullPointerException("processor must not be null");
        }
        List<PresetParam> params = reflect(processor.getClass());
        Map<String, Double> values = new LinkedHashMap<>(params.size() * 2);
        for (PresetParam p : params) {
            try {
                Double pending = pendingValues.get(p.id);
                double v = pending != null ? pending : (double) p.getter.invoke(processor);
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
     * {@code null}, {@code NaN} and infinite values are ignored, and so is a
     * value the processor's setter refuses with an
     * {@link IllegalArgumentException}. The outcomes are those of
     * {@link #restoreEach}, which reports each.</p>
     *
     * @return the number of parameters applied, as saved or clamped
     * @throws NullPointerException  if either argument is {@code null}
     * @throws IllegalStateException if a setter or getter fails other than by
     *                               refusing its value
     */
    public static int restore(AudioProcessor processor, Map<String, Double> values) {
        if (processor == null) {
            throw new NullPointerException("processor must not be null");
        }
        if (values == null) {
            throw new NullPointerException("values must not be null");
        }
        return (int) restoreEach(processor, new LinkedHashMap<>(values)).stream()
                .filter(outcome -> outcome instanceof ParameterRestore.Applied
                        || outcome instanceof ParameterRestore.Clamped)
                .count();
    }

    /**
     * Restores every named parameter from {@code values} onto
     * {@code processor}, in the map's order, and says what happened to each
     * entry (story 334: the project reader reports every value it could not
     * apply as saved).
     *
     * <ul>
     *   <li>{@link ParameterRestore.Unknown} — the processor declares no
     *       parameter of that name (every name, for a processor without
     *       {@link ProcessorParam} accessors);</li>
     *   <li>{@link ParameterRestore.NotFinite} — the value is {@code null},
     *       NaN or infinite, and is not applied;</li>
     *   <li>{@link ParameterRestore.Clamped} — the value is outside the
     *       declared {@code [min, max]} range, and the nearest bound is
     *       applied;</li>
     *   <li>{@link ParameterRestore.Rejected} — the setter refused the value
     *       it was given — the saved value, or the nearest bound of one
     *       outside the declared range — with an
     *       {@link IllegalArgumentException}; the value then in force is read
     *       through the getter;</li>
     *   <li>{@link ParameterRestore.Applied} — the value was applied as
     *       saved.</li>
     * </ul>
     *
     * <p>Uses the per-class cache of {@link #isSupported}, {@link #snapshot}
     * and {@link #restore}: no reflection after a class's first use.</p>
     *
     * @param processor the processor to restore
     * @param values    the saved values by parameter name, in the order to apply them
     * @return one outcome per entry of {@code values}, in its order
     * @throws NullPointerException  if either argument is {@code null}
     * @throws IllegalStateException if a setter or getter fails other than by
     *                               refusing its value
     */
    public static List<ParameterRestore> restoreEach(AudioProcessor processor, SequencedMap<String, Double> values) {
        if (processor == null) {
            throw new NullPointerException("processor must not be null");
        }
        if (values == null) {
            throw new NullPointerException("values must not be null");
        }
        List<PresetParam> params = reflect(processor.getClass());
        Map<String, PresetParam> byName = new LinkedHashMap<>(params.size() * 2);
        for (PresetParam p : params) {
            byName.put(p.name, p);
        }
        List<ParameterRestore> outcomes = new ArrayList<>(values.size());
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            outcomes.add(restoreOne(processor, byName.get(entry.getKey()), entry.getKey(), entry.getValue()));
        }
        return List.copyOf(outcomes);
    }

    /** Applies one saved value to the parameter {@code p} declares ({@code null}: none of that name). */
    private static ParameterRestore restoreOne(AudioProcessor processor, PresetParam p, String name, Double raw) {
        if (p == null) {
            return new ParameterRestore.Unknown(name, raw == null ? Double.NaN : raw);
        }
        if (raw == null || !Double.isFinite(raw)) {
            return new ParameterRestore.NotFinite(name, raw == null ? Double.NaN : raw);
        }
        double applied = Math.max(p.min, Math.min(p.max, raw));
        try {
            p.setter.invoke(processor, applied);
        } catch (IllegalArgumentException refused) {
            return new ParameterRestore.Rejected(name, raw, valueOf(processor, p));
        } catch (Throwable t) {
            throw new IllegalStateException(
                    "Failed to invoke setter for preset parameter '" + p.name
                            + "' on " + processor.getClass().getSimpleName(), t);
        }
        return applied == raw
                ? new ParameterRestore.Applied(name, applied)
                : new ParameterRestore.Clamped(name, raw, applied, p.min, p.max);
    }

    /** The parameter's value, read through its cached getter. */
    private static double valueOf(AudioProcessor processor, PresetParam p) {
        try {
            return (double) p.getter.invoke(processor);
        } catch (Throwable t) {
            throw new IllegalStateException(
                    "Failed to invoke getter for preset parameter '" + p.name
                            + "' on " + processor.getClass().getSimpleName(), t);
        }
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
        return CACHE.get(processorClass);
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
            params.add(new PresetParam(ann.id(), ann.name(), ann.min(), ann.max(),
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
        final int id;
        final String name;
        final double min;
        final double max;
        final MethodHandle getter;
        final MethodHandle setter;

        PresetParam(int id, String name, double min, double max,
                    MethodHandle getter, MethodHandle setter) {
            this.id = id;
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
