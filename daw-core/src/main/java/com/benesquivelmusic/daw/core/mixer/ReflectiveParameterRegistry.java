package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Reflective discovery for DSP processor parameters declared via
 * {@link ProcessorParam}.
 *
 * <p>Given any object, this registry finds all {@code @ProcessorParam}-annotated
 * getter methods on its class, resolves matching setters by JavaBeans naming
 * convention ({@code getXxx} → {@code setXxx(double)}), and exposes them as:</p>
 * <ul>
 *   <li>a list of {@link PluginParameter} descriptors (id, name with optional
 *       unit suffix, min, max, default),</li>
 *   <li>a {@link BiConsumer} that applies parameter value changes,</li>
 *   <li>a {@link Map} of current parameter values keyed by id.</li>
 * </ul>
 *
 * <p>Reflected method handles are cached per processor class so that reflection
 * happens at most once per class, never on the real-time audio path.</p>
 */
public final class ReflectiveParameterRegistry {

    /** Per-class cache of reflected parameters. */
    private static final Map<Class<?>, List<ReflectedParam>> CACHE = new ConcurrentHashMap<>();

    private ReflectiveParameterRegistry() {
        // utility class
    }

    /**
     * Returns {@code true} if the given processor class declares any
     * {@link ProcessorParam}-annotated methods.
     */
    public static boolean hasAnnotatedParameters(Class<?> processorClass) {
        return !reflect(processorClass).isEmpty();
    }

    /**
     * Returns the parameter descriptors for the given processor class, sorted
     * by parameter id ascending.
     */
    public static List<PluginParameter> getParameterDescriptors(Class<?> processorClass) {
        List<ReflectedParam> params = reflect(processorClass);
        List<PluginParameter> descriptors = new ArrayList<>(params.size());
        for (ReflectedParam p : params) {
            descriptors.add(p.toPluginParameter());
        }
        return List.copyOf(descriptors);
    }

    /**
     * Returns a handler that applies parameter value changes to {@code processor}
     * by invoking the discovered setter for the given id. Unknown ids are
     * silently ignored.
     */
    public static BiConsumer<Integer, Double> createParameterHandler(Object processor) {
        if (processor == null) {
            throw new NullPointerException("processor must not be null");
        }
        Map<Integer, ReflectedParam> byId = byId(reflect(processor.getClass()));
        return (id, value) -> {
            ReflectedParam p = byId.get(id);
            if (p == null) {
                return;
            }
            try {
                p.setter.invoke(processor, value);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "Failed to invoke setter " + p.setter.getName()
                                + " on " + processor.getClass().getSimpleName(), e);
            }
        };
    }

    /**
     * Reads the current value of every annotated parameter from {@code processor}
     * and returns a map keyed by parameter id, preserving ascending-id order.
     */
    public static Map<Integer, Double> getParameterValues(Object processor) {
        if (processor == null) {
            throw new NullPointerException("processor must not be null");
        }
        List<ReflectedParam> params = reflect(processor.getClass());
        Map<Integer, Double> values = new LinkedHashMap<>();
        for (ReflectedParam p : params) {
            try {
                Object v = p.getter.invoke(processor);
                if (v instanceof Number n) {
                    values.put(p.id, n.doubleValue());
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "Failed to invoke getter " + p.getter.getName()
                                + " on " + processor.getClass().getSimpleName(), e);
            }
        }
        return values;
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private static Map<Integer, ReflectedParam> byId(List<ReflectedParam> params) {
        Map<Integer, ReflectedParam> byId = new LinkedHashMap<>(params.size() * 2);
        for (ReflectedParam p : params) {
            byId.put(p.id, p);
        }
        return byId;
    }

    private static List<ReflectedParam> reflect(Class<?> processorClass) {
        return CACHE.computeIfAbsent(processorClass, ReflectiveParameterRegistry::discover);
    }

    private static List<ReflectedParam> discover(Class<?> processorClass) {
        List<ReflectedParam> params = new ArrayList<>();
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
            String name = m.getName();
            if (!name.startsWith("get")) {
                throw new IllegalStateException(
                        "@ProcessorParam getter must follow getXxx convention: "
                                + processorClass.getSimpleName() + "#" + name);
            }
            String setterName = "set" + name.substring(3);
            Method setter;
            try {
                setter = processorClass.getMethod(setterName, double.class);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(
                        "No matching setter " + setterName + "(double) for @ProcessorParam getter "
                                + processorClass.getSimpleName() + "#" + name, e);
            }
            params.add(new ReflectedParam(ann.id(), ann.name(), ann.unit(),
                    ann.min(), ann.max(), ann.defaultValue(), m, setter));
        }
        params.sort(Comparator.comparingInt(p -> p.id));
        // Validate id uniqueness
        for (int i = 1; i < params.size(); i++) {
            if (params.get(i).id == params.get(i - 1).id) {
                throw new IllegalStateException(
                        "Duplicate @ProcessorParam id " + params.get(i).id
                                + " on " + processorClass.getSimpleName());
            }
        }
        return List.copyOf(params);
    }

    /** Immutable descriptor bundling annotation metadata with reflected method handles. */
    private static final class ReflectedParam {
        final int id;
        final String name;
        final String unit;
        final double min;
        final double max;
        final double defaultValue;
        final Method getter;
        final Method setter;

        ReflectedParam(int id, String name, String unit, double min, double max,
                       double defaultValue, Method getter, Method setter) {
            this.id = id;
            this.name = name;
            this.unit = unit;
            this.min = min;
            this.max = max;
            this.defaultValue = defaultValue;
            this.getter = getter;
            this.setter = setter;
        }

        PluginParameter toPluginParameter() {
            String displayName = (unit == null || unit.isEmpty())
                    ? name
                    : name + " (" + unit + ")";
            return new PluginParameter(id, displayName, min, max, defaultValue);
        }
    }
}
