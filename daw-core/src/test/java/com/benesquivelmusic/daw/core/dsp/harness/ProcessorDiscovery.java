package com.benesquivelmusic.daw.core.dsp.harness;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Classpath scanner that discovers every concrete, public
 * {@link AudioProcessor} implementation in a given package.
 *
 * <p>Scans both exploded directory layouts (e.g. {@code target/test-classes},
 * {@code target/classes}) and JAR files. Kept implementation
 * dependency-free so it works as an in-project test utility without pulling
 * in Reflections or ClassGraph.</p>
 */
public final class ProcessorDiscovery {

    private ProcessorDiscovery() {}

    /**
     * Returns all concrete, public {@link AudioProcessor} implementations
     * declared directly in {@code packageName} (non-recursive) sorted by
     * simple name for stable test ordering.
     */
    public static List<Class<? extends AudioProcessor>> findAudioProcessors(String packageName) {
        // Module-aware enumeration: under JPMS daw.core is a named module and
        // ClassLoader.getResources() can no longer walk its package
        // directories. ModuleClassScanner lists class names via the module's
        // ModuleReader (with a class-path fallback). It enumerates
        // recursively, so the original NON-recursive contract is preserved
        // here by keeping only classes whose package equals packageName.
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Map<String, Class<? extends AudioProcessor>> out = new HashMap<>();
        for (String fqcn : com.benesquivelmusic.daw.core.testsupport.ModuleClassScanner
                .classNamesUnder(packageName)) {
            int lastDot = fqcn.lastIndexOf('.');
            String pkg = lastDot < 0 ? "" : fqcn.substring(0, lastDot);
            if (!pkg.equals(packageName)) {
                continue; // non-recursive: skip sub-packages
            }
            addIfProcessor(fqcn, cl, out);
        }
        List<Class<? extends AudioProcessor>> sorted = new ArrayList<>(out.values());
        sorted.sort(Comparator.comparing(Class::getSimpleName));
        return Collections.unmodifiableList(sorted);
    }

    /**
     * Returns the subset of {@link #findAudioProcessors(String)} whose classes
     * declare a public {@code (int, double)} constructor — the standard
     * {@code (channels, sampleRate)} convention used by the built-in
     * processor registry.
     */
    public static List<Class<? extends AudioProcessor>> findProcessorsWithStandardConstructor(
            String packageName) {
        List<Class<? extends AudioProcessor>> all = findAudioProcessors(packageName);
        List<Class<? extends AudioProcessor>> matching = new ArrayList<>(all.size());
        for (Class<? extends AudioProcessor> cls : all) {
            if (findStandardConstructor(cls) != null) {
                matching.add(cls);
            }
        }
        return Collections.unmodifiableList(matching);
    }

    /**
     * Returns the public {@code (int, double)} constructor of {@code cls}, or
     * {@code null} if the class does not declare one.
     */
    public static Constructor<?> findStandardConstructor(Class<?> cls) {
        for (Constructor<?> ctor : cls.getConstructors()) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length == 2
                    && params[0] == int.class
                    && params[1] == double.class) {
                return ctor;
            }
        }
        return null;
    }

    // ─── internals ──────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static void addIfProcessor(String fqcn, ClassLoader cl,
                                       Map<String, Class<? extends AudioProcessor>> out) {
        Class<?> cls;
        try {
            cls = Class.forName(fqcn, false, cl);
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            return;
        }
        int mods = cls.getModifiers();
        if (!Modifier.isPublic(mods)
                || Modifier.isAbstract(mods)
                || Modifier.isInterface(mods)
                || cls.isEnum()
                || cls.isAnnotation()) {
            return;
        }
        if (!AudioProcessor.class.isAssignableFrom(cls)) {
            return;
        }
        out.put(fqcn, (Class<? extends AudioProcessor>) cls);
    }
}
