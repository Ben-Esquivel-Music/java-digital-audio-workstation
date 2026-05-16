package com.benesquivelmusic.daw.core.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * JPMS-aware class enumerator for the test-only discovery scanners.
 *
 * <p>The legacy scanners walked
 * {@code Thread.currentThread().getContextClassLoader().getResources(pkgPath)}
 * and traversed the returned {@code file:} / {@code jar:} URLs. That stops
 * working once {@code daw.core} is a <em>named module</em>: a named module's
 * package directories are encapsulated and are not enumerable through the
 * class loader. The module-system-correct replacement is to read the module
 * via its {@link ModuleReference} / {@link ModuleReader}, which lists every
 * resource (including {@code .class} files) the module contains regardless of
 * encapsulation.</p>
 *
 * <p>Resolution strategy, in order:</p>
 * <ol>
 *   <li>If the current class is in a <strong>named module</strong>, list the
 *       binary names of all classes under the requested package prefix from
 *       every module reference in the module's {@link ModuleLayer} whose name
 *       starts with {@code daw.} (covers {@code daw.core} plus the patched
 *       test module, which Surefire merges into {@code daw.core}).</li>
 *   <li>Otherwise fall back to the original class-loader resource walk so the
 *       scanners still work when run on the plain class path.</li>
 * </ol>
 */
public final class ModuleClassScanner {

    private ModuleClassScanner() {
    }

    /**
     * Returns the fully-qualified binary names of every {@code .class} under
     * {@code packagePrefix} (recursively), excluding {@code module-info} and
     * inner/anonymous classes ({@code $}).
     */
    public static List<String> classNamesUnder(String packagePrefix) {
        Module module = ModuleClassScanner.class.getModule();
        Set<String> names = new LinkedHashSet<>();
        if (module.isNamed()) {
            collectFromModuleLayer(module, packagePrefix, names);
        }
        if (names.isEmpty()) {
            collectFromClassLoader(packagePrefix, names);
        }
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(Comparator.naturalOrder());
        return List.copyOf(sorted);
    }

    private static void collectFromModuleLayer(Module module, String packagePrefix,
                                               Set<String> out) {
        String prefixPath = packagePrefix.replace('.', '/') + "/";
        ModuleLayer layer = module.getLayer();
        if (layer == null) {
            return;
        }
        for (var resolved : layer.configuration().modules()) {
            String name = resolved.name();
            // daw.core (production classes) and any sibling daw.* modules.
            // Surefire patches the test classes into the daw.core module, so
            // both production and test .class files are enumerated here.
            if (!name.startsWith("daw.")) {
                continue;
            }
            ModuleReference ref = resolved.reference();
            try (ModuleReader reader = ref.open()) {
                reader.list()
                        .filter(r -> r.startsWith(prefixPath) && r.endsWith(".class"))
                        .filter(r -> !r.endsWith("module-info.class"))
                        .map(r -> r.substring(0, r.length() - ".class".length())
                                .replace('/', '.'))
                        .filter(cn -> cn.indexOf('$') < 0)
                        .forEach(out::add);
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Failed to read module " + name + " for package " + packagePrefix, e);
            }
        }
    }

    private static void collectFromClassLoader(String packagePrefix, Set<String> out) {
        String resourcePath = packagePrefix.replace('.', '/');
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try {
            Enumeration<URL> roots = cl.getResources(resourcePath);
            while (roots.hasMoreElements()) {
                collectFromUrl(roots.nextElement(), resourcePath, packagePrefix, out);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to scan class path for " + packagePrefix, e);
        }
    }

    private static void collectFromUrl(URL url, String resourcePath,
                                       String packagePrefix, Set<String> out) {
        try {
            URI uri = url.toURI();
            FileSystem closeable = null;
            Path root;
            if ("jar".equals(uri.getScheme())) {
                closeable = FileSystems.newFileSystem(uri, Map.of());
                root = closeable.getPath(resourcePath);
            } else {
                root = Path.of(uri);
            }
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(p -> p.toString().endsWith(".class"))
                        .forEach(p -> {
                            StringBuilder rel = new StringBuilder();
                            Path r = root.relativize(p);
                            for (int i = 0; i < r.getNameCount(); i++) {
                                if (i > 0) {
                                    rel.append('.');
                                }
                                rel.append(r.getName(i));
                            }
                            String cn = packagePrefix + "."
                                    + rel.substring(0, rel.length() - ".class".length());
                            if (cn.indexOf('$') < 0 && !cn.endsWith("module-info")) {
                                out.add(cn);
                            }
                        });
            } finally {
                if (closeable != null) {
                    closeable.close();
                }
            }
        } catch (URISyntaxException | IOException e) {
            throw new UncheckedIOException(new IOException("Failed to scan " + url, e));
        }
    }
}
