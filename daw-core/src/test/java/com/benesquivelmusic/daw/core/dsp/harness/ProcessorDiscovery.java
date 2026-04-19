package com.benesquivelmusic.daw.core.dsp.harness;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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
        String resourcePath = packageName.replace('.', '/');
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Map<String, Class<? extends AudioProcessor>> out = new HashMap<>();
        try {
            Enumeration<URL> roots = cl.getResources(resourcePath);
            while (roots.hasMoreElements()) {
                URL url = roots.nextElement();
                switch (url.getProtocol()) {
                    case "file" -> scanDirectory(Paths.get(url.toURI()), packageName, cl, out);
                    case "jar" -> scanJar(url, packageName, cl, out);
                    default -> { /* ignore other protocols */ }
                }
            }
        } catch (IOException | URISyntaxException e) {
            throw new UncheckedIOException(
                    "Failed to scan package " + packageName,
                    e instanceof IOException io ? io : new IOException(e));
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

    private static void scanDirectory(Path dir, String pkg, ClassLoader cl,
                                      Map<String, Class<? extends AudioProcessor>> out)
            throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(p -> p.getFileName().toString().endsWith(".class"))
                    .forEach(p -> addIfProcessor(pkg, stripClass(p.getFileName().toString()), cl, out));
        }
    }

    private static void scanJar(URL url, String pkg, ClassLoader cl,
                                Map<String, Class<? extends AudioProcessor>> out)
            throws IOException, URISyntaxException {
        String spec = url.toString();
        int bang = spec.indexOf("!/");
        if (bang < 0) return;
        URI jarUri = new URI(spec.substring(0, bang));
        String inside = spec.substring(bang + 1);
        try (FileSystem fs = FileSystems.newFileSystem(jarUri, Map.of())) {
            Path dir = fs.getPath(inside);
            if (!Files.isDirectory(dir)) return;
            try (Stream<Path> entries = Files.list(dir)) {
                entries.filter(p -> p.getFileName().toString().endsWith(".class"))
                        .forEach(p -> addIfProcessor(pkg, stripClass(p.getFileName().toString()), cl, out));
            }
        }
    }

    private static String stripClass(String filename) {
        return filename.substring(0, filename.length() - ".class".length());
    }

    @SuppressWarnings("unchecked")
    private static void addIfProcessor(String pkg, String simple, ClassLoader cl,
                                       Map<String, Class<? extends AudioProcessor>> out) {
        if (simple.indexOf('$') >= 0) {
            return; // skip inner/anonymous classes
        }
        String fqcn = pkg + "." + simple;
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
