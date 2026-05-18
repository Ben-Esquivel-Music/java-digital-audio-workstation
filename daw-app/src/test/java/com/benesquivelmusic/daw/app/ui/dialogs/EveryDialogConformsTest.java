package com.benesquivelmusic.daw.app.ui.dialogs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.lang.reflect.Modifier;
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

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Story 276 conformance gate. Classpath/module-scans the UI packages
 * for every class whose simple name ends in {@code Dialog} and asserts
 * each either {@code extends DawgDialog} or carries
 * {@link LegacyDialog}. The annotation makes the not-yet-migrated state
 * visible and prevents drift — a new dialog cannot be added without
 * consciously adopting the §5.9 skeleton or recording a TODO.
 *
 * <p>The scanner mirrors {@code ProcessorDiscovery}/{@code
 * ModuleClassScanner} (consumer #7): module layer first (named module),
 * class-loader walk as the fallback (plain class path); {@code
 * Class.forName(name, false, cl)} so static initializers do not run
 * during discovery; both exploded {@code target/classes} dirs and JAR
 * {@code FileSystem}s handled. No toolkit needed.</p>
 */
class EveryDialogConformsTest {

    private static final String[] SCAN_PACKAGES = {
            "com.benesquivelmusic.daw.app.ui"
    };

    @Test
    void everyDialogExtendsDawgDialogOrIsAnnotatedLegacy() {
        List<String> offenders = new ArrayList<>();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();

        for (String pkg : SCAN_PACKAGES) {
            for (String fqcn : classNamesUnder(pkg)) {
                Class<?> c;
                try {
                    c = Class.forName(fqcn, false, cl);
                } catch (ClassNotFoundException | NoClassDefFoundError e) {
                    continue;
                }
                if (!c.getSimpleName().endsWith("Dialog")) {
                    continue;
                }
                // Skip the base skeleton itself, the @LegacyDialog
                // annotation type (its name also ends in "Dialog"), and
                // any interface.
                if (c == DawgDialog.class
                        || c.isAnnotation()
                        || c.isInterface()
                        || Modifier.isAbstract(c.getModifiers())) {
                    continue;
                }
                boolean conforms = DawgDialog.class.isAssignableFrom(c);
                if (!conforms && c.isAnnotationPresent(LegacyDialog.class)) {
                    LegacyDialog ann = c.getAnnotation(LegacyDialog.class);
                    conforms = ann.value() != null && !ann.value().isBlank();
                }
                if (!conforms) {
                    offenders.add(c.getName());
                }
            }
        }

        if (!offenders.isEmpty()) {
            offenders.sort(Comparator.naturalOrder());
            fail("Story 276 — every *Dialog class must either "
                    + "`extends DawgDialog` (adopt the §5.9 chrome "
                    + "skeleton) or carry @LegacyDialog(\"<TODO>\"). "
                    + "Non-conforming:\n  "
                    + String.join("\n  ", offenders)
                    + "\nAdd `extends DawgDialog<R>` or annotate with "
                    + "@LegacyDialog and a migration TODO.");
        }
    }

    @Test
    void scannerActuallyFoundDialogs() {
        // Guard against a silently-empty scan (a broken scanner would
        // make the conformance test vacuously pass).
        long count = classNamesUnder(SCAN_PACKAGES[0]).stream()
                .filter(n -> n.endsWith("Dialog"))
                .count();
        if (count < 5) {
            fail("scanner found only " + count + " *Dialog classes — "
                    + "expected the full UI dialog set; the classpath/"
                    + "module scan is likely broken");
        }
    }

    // ── scanner (mirrors ProcessorDiscovery / ModuleClassScanner) ────────────

    private static List<String> classNamesUnder(String packagePrefix) {
        Module module = EveryDialogConformsTest.class.getModule();
        Set<String> names = new LinkedHashSet<>();
        if (module.isNamed() && module.getLayer() != null) {
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
        for (var resolved : layer.configuration().modules()) {
            String name = resolved.name();
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
                        "Failed to read module " + name + " for " + packagePrefix, e);
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
