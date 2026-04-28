package com.benesquivelmusic.daw.core.dsp.regression;

import com.benesquivelmusic.daw.core.dsp.harness.ProcessorDiscovery;
import com.benesquivelmusic.daw.core.plugin.BuiltInDawPlugin;
import com.benesquivelmusic.daw.core.plugin.BuiltInPlugin;
import com.benesquivelmusic.daw.core.plugin.BuiltInPluginCategory;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Annotation-driven coverage check for the DSP regression framework.
 *
 * <p>Every concrete built-in plugin annotated with
 * {@code @BuiltInPlugin(category = EFFECT or MASTERING)} should have at
 * least one {@link DspRegression}-annotated test method that exercises
 * its underlying processor. This class scans the test classpath for
 * {@code @DspRegression} cases and reports any effect-category plugin
 * missing coverage.</p>
 *
 * <p>Two checks are exposed:
 * <ul>
 *   <li>{@link #reportRegressionCoverage()} — always runs; <em>prints</em>
 *       the coverage gap to stdout but does not fail. This makes the
 *       build observable without forcing a 45-file commit on day one.</li>
 *   <li>{@link #strictCoverage()} — gated on
 *       {@code -Ddsp.regression.strict=true} (set by the
 *       {@code long-tests} Maven profile in CI). Fails the build if any
 *       effect/mastering plugin lacks coverage or any covered processor
 *       is missing one of the canonical presets.</li>
 * </ul>
 *
 * <p>Coverage is keyed by <em>processor class</em> rather than plugin
 * class: a regression test for the processor automatically covers all
 * plugins that wrap it. The mapping plugin → processor is read from
 * each plugin's class via reflection.</p>
 *
 * <p>This is the test-time twin of the compile-time check planned for
 * the dawg-annotations harness (story 114).</p>
 */
class DspRegressionCoverageTest {

    static {
        CanonicalRegressionPresets.registerAll();
    }

    /** Categories that require DSP regression coverage. */
    private static final Set<BuiltInPluginCategory> REQUIRED_CATEGORIES =
            Set.of(BuiltInPluginCategory.EFFECT, BuiltInPluginCategory.MASTERING);

    /** Always-on diagnostic — prints coverage gap, never fails. */
    @Test
    void reportRegressionCoverage() {
        CoverageReport report = computeCoverage();
        System.out.println(report.format());
        // Sanity: every covered processor's presets must round-trip without
        // throwing — this is a guarantee the strict check will rely on.
        for (Class<? extends AudioProcessor> p : report.coveredProcessors) {
            assertThat(DspRegressionPreset.registeredPresets(p))
                    .as("registered presets for %s", p.getSimpleName())
                    .isNotEmpty();
        }
    }

    /** Strict CI check — gated on the {@code long-tests} profile. */
    @Test
    @EnabledIfSystemProperty(named = "dsp.regression.strict", matches = "true")
    void strictCoverage() {
        CoverageReport report = computeCoverage();
        assertThat(report.uncoveredPlugins)
                .as("Built-in effect/mastering plugins missing @DspRegression "
                  + "coverage. Add a <Processor>RegressionTest under "
                  + "daw-core/src/test/java/.../dsp/regression/ and register "
                  + "Default/Aggressive/Subtle presets in CanonicalRegressionPresets.")
                .isEmpty();
        assertThat(report.processorsMissingPresets)
                .as("Processors with @DspRegression tests must register all three "
                  + "canonical presets (Default/Aggressive/Subtle).")
                .isEmpty();
    }

    // ── Coverage discovery ──────────────────────────────────────────────────

    private static CoverageReport computeCoverage() {
        Set<Class<? extends AudioProcessor>> covered = discoverCoveredProcessors();

        Map<String, String> uncovered = new TreeMap<>();
        for (Class<? extends BuiltInDawPlugin> plugin : effectCategoryPlugins()) {
            Class<? extends AudioProcessor> processor = resolveProcessorClass(plugin);
            if (processor == null) {
                uncovered.put(plugin.getSimpleName(), "(no AudioProcessor field; skipped)");
            } else if (!covered.contains(processor)) {
                uncovered.put(plugin.getSimpleName(), processor.getSimpleName());
            }
        }

        Map<String, Set<String>> missingPresets = new TreeMap<>();
        for (Class<? extends AudioProcessor> p : covered) {
            Set<String> registered = DspRegressionPreset.registeredPresets(p);
            Set<String> need = new LinkedHashSet<>();
            for (String preset : DspRegressionPreset.CANONICAL) {
                if (!registered.contains(preset)) need.add(preset);
            }
            if (!need.isEmpty()) missingPresets.put(p.getSimpleName(), need);
        }
        return new CoverageReport(covered, uncovered, missingPresets);
    }

    /** Iterate sealed-permitted built-in plugin classes that are EFFECT/MASTERING. */
    @SuppressWarnings("unchecked")
    private static List<Class<? extends BuiltInDawPlugin>> effectCategoryPlugins() {
        List<Class<? extends BuiltInDawPlugin>> out = new ArrayList<>();
        Class<?>[] permitted = BuiltInDawPlugin.class.getPermittedSubclasses();
        if (permitted == null) return out;
        for (Class<?> c : permitted) {
            if (c.isInterface()) continue; // skip MidiEffectPlugin etc. — category markers
            BuiltInPlugin meta = c.getAnnotation(BuiltInPlugin.class);
            if (meta == null) continue;
            if (!REQUIRED_CATEGORIES.contains(meta.category())) continue;
            out.add((Class<? extends BuiltInDawPlugin>) c);
        }
        out.sort(Comparator.comparing(Class::getSimpleName));
        return out;
    }

    /** Walk every test class under com.benesquivelmusic and pick out @DspRegression carriers. */
    private static Set<Class<? extends AudioProcessor>> discoverCoveredProcessors() {
        Set<Class<? extends AudioProcessor>> out = new HashSet<>();
        for (Class<?> testClass : findTestClassesUnder("com.benesquivelmusic")) {
            if (!hasRegressionAnnotation(testClass)) continue;
            DspRegressionTarget target = testClass.getAnnotation(DspRegressionTarget.class);
            Class<? extends AudioProcessor> processor =
                    (target != null) ? target.processor() : inferProcessorFromName(testClass);
            if (processor != null) out.add(processor);
        }
        return out;
    }

    private static boolean hasRegressionAnnotation(Class<?> testClass) {
        if (testClass.getAnnotationsByType(DspRegression.class).length > 0) return true;
        for (Method m : testClass.getDeclaredMethods()) {
            if (m.getAnnotationsByType(DspRegression.class).length > 0) return true;
        }
        return false;
    }

    /** {@code FooProcessorRegressionTest} → {@code FooProcessor} in dsp.* */
    private static Class<? extends AudioProcessor> inferProcessorFromName(Class<?> testClass) {
        String name = testClass.getSimpleName();
        if (!name.endsWith("RegressionTest")) return null;
        String stem = name.substring(0, name.length() - "RegressionTest".length());
        for (String pkg : DSP_SUBPACKAGES) {
            for (Class<? extends AudioProcessor> p : ProcessorDiscovery.findAudioProcessors(pkg)) {
                if (p.getSimpleName().equals(stem)) return p;
            }
        }
        return null;
    }

    private static final List<String> DSP_SUBPACKAGES = List.of(
            "com.benesquivelmusic.daw.core.dsp",
            "com.benesquivelmusic.daw.core.dsp.dynamics",
            "com.benesquivelmusic.daw.core.dsp.eq",
            "com.benesquivelmusic.daw.core.dsp.reverb",
            "com.benesquivelmusic.daw.core.dsp.saturation",
            "com.benesquivelmusic.daw.core.dsp.mastering",
            "com.benesquivelmusic.daw.core.dsp.acoustics");

    /** Resolve a built-in plugin class to the {@link AudioProcessor} class it wraps. */
    @SuppressWarnings("unchecked")
    private static Class<? extends AudioProcessor> resolveProcessorClass(
            Class<? extends BuiltInDawPlugin> pluginClass) {
        // Look at declared fields for the most reliable signal.
        for (Field f : pluginClass.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (AudioProcessor.class.isAssignableFrom(f.getType())) {
                return (Class<? extends AudioProcessor>) f.getType();
            }
        }
        return null;
    }

    // ── Test-classpath scanner ──────────────────────────────────────────────

    private static Set<Class<?>> findTestClassesUnder(String basePackage) {
        Set<Class<?>> result = new TreeSet<>(Comparator.comparing(Class::getName));
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        String resourcePath = basePackage.replace('.', '/');
        try {
            Enumeration<URL> roots = cl.getResources(resourcePath);
            while (roots.hasMoreElements()) {
                URL url = roots.nextElement();
                switch (url.getProtocol()) {
                    case "file" -> scanFileTree(Paths.get(url.toURI()), basePackage, cl, result);
                    case "jar"  -> scanJarTree(url, basePackage, cl, result);
                    default     -> { /* ignore */ }
                }
            }
        } catch (IOException | URISyntaxException e) {
            throw new UncheckedIOException(
                    "Failed to scan test classpath under " + basePackage,
                    e instanceof IOException io ? io : new IOException(e));
        }
        return result;
    }

    private static void scanFileTree(Path dir, String pkg, ClassLoader cl, Set<Class<?>> out)
            throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(p -> p.getFileName().toString().endsWith(".class")).forEach(p -> {
                String rel = dir.relativize(p).toString().replace('\\', '/');
                String fqcn = pkg + "." + rel.substring(0, rel.length() - ".class".length())
                                              .replace('/', '.');
                tryAdd(fqcn, cl, out);
            });
        }
    }

    private static void scanJarTree(URL url, String pkg, ClassLoader cl, Set<Class<?>> out)
            throws IOException, URISyntaxException {
        String spec = url.toString();
        int bang = spec.indexOf("!/");
        if (bang < 0) return;
        java.net.URI jarUri = new java.net.URI(spec.substring(0, bang));
        String inside = spec.substring(bang + 1);
        try (FileSystem fs = FileSystems.newFileSystem(jarUri, Map.of())) {
            Path dir = fs.getPath(inside);
            if (!Files.isDirectory(dir)) return;
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(p -> p.getFileName().toString().endsWith(".class")).forEach(p -> {
                    String rel = dir.relativize(p).toString().replace('\\', '/');
                    String fqcn = pkg + "." + rel.substring(0, rel.length() - ".class".length())
                                                  .replace('/', '.');
                    tryAdd(fqcn, cl, out);
                });
            }
        }
    }

    private static void tryAdd(String fqcn, ClassLoader cl, Set<Class<?>> out) {
        if (fqcn.indexOf('$') >= 0) return;
        try {
            Class<?> cls = Class.forName(fqcn, false, cl);
            int mods = cls.getModifiers();
            if (Modifier.isAbstract(mods) || cls.isInterface()
                    || cls.isEnum() || cls.isAnnotation()) return;
            out.add(cls);
        } catch (Throwable ignored) {
            // best-effort scan: skip classes that fail to load (e.g. test fixtures
            // with missing optional dependencies).
        }
    }

    // ── Reporting type ──────────────────────────────────────────────────────

    private record CoverageReport(
            Set<Class<? extends AudioProcessor>> coveredProcessors,
            Map<String, String> uncoveredPlugins,
            Map<String, Set<String>> processorsMissingPresets) {

        String format() {
            StringBuilder sb = new StringBuilder();
            sb.append("DSP regression coverage report\n");
            sb.append("  covered processors (").append(coveredProcessors.size()).append("): ");
            coveredProcessors.stream()
                    .map(Class::getSimpleName)
                    .sorted()
                    .forEach(s -> sb.append(s).append(' '));
            sb.append('\n');
            sb.append("  effect plugins missing coverage (")
                    .append(uncoveredPlugins.size()).append("):\n");
            uncoveredPlugins.forEach((plugin, processor) ->
                    sb.append("    - ").append(plugin)
                      .append(" → ").append(processor).append('\n'));
            sb.append("  covered processors missing canonical presets (")
                    .append(processorsMissingPresets.size()).append("):\n");
            processorsMissingPresets.forEach((proc, missing) ->
                    sb.append("    - ").append(proc).append(": ").append(missing).append('\n'));
            return sb.toString();
        }
    }
}
