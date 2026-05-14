package com.benesquivelmusic.daw.app.ui.icons;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Build-time inventory check for the vendored Lucide icon subset.
 *
 * <p>Per UI Design Book §3.6, the project commits to a curated subset
 * of Lucide icons listed in {@code icons.allowed.txt}. This test fails
 * if any {@code .svg} sits in the {@code lucide/} directory without an
 * entry in the whitelist, or vice versa.</p>
 *
 * <p>Rationale: prevents drift toward "every developer adds one more
 * icon". Adding a new icon is a deliberate two-step act — drop in the
 * SVG <em>and</em> add the name to {@code icons.allowed.txt}.</p>
 */
class LucideIconInventoryTest {

    private static final String LUCIDE_DIR =
            "com/benesquivelmusic/daw/app/ui/icons/lucide";

    @Test
    void vendoredIconsMustMatchAllowlist() throws IOException, URISyntaxException {
        Set<String> bundled = listBundledIcons();
        Set<String> allowed = readAllowlist();

        Set<String> extras = new TreeSet<>(bundled);
        extras.removeAll(allowed);
        Set<String> missing = new TreeSet<>(allowed);
        missing.removeAll(bundled);

        assertThat(extras)
                .as("Lucide SVG files present without a matching entry in icons.allowed.txt — " +
                        "either remove the file or add the name to the whitelist (UI Design Book §3.6)")
                .isEmpty();
        assertThat(missing)
                .as("Names listed in icons.allowed.txt with no matching SVG file — " +
                        "either vendor the file under " + LUCIDE_DIR + "/ or remove the name")
                .isEmpty();
    }

    @Test
    void licenseAndAttributionMustBeBundled() {
        assertThat(getClass().getResource("/" + LUCIDE_DIR + "/LICENSE"))
                .as("Lucide LICENSE (ISC) must ship alongside the icons")
                .isNotNull();
        assertThat(getClass().getResource("/" + LUCIDE_DIR + "/ATTRIBUTION"))
                .as("Lucide ATTRIBUTION notes must ship alongside the icons")
                .isNotNull();
    }

    // ── Helpers ──

    private static Set<String> listBundledIcons() throws IOException, URISyntaxException {
        URL dir = LucideIconInventoryTest.class.getClassLoader().getResource(LUCIDE_DIR);
        assertThat(dir).as("Lucide icon directory must be on the classpath").isNotNull();

        Set<String> names = new TreeSet<>();
        Path root = Paths.get(dir.toURI());
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, "*.svg")) {
            List<Path> sorted = new ArrayList<>();
            stream.forEach(sorted::add);
            sorted.sort(Comparator.comparing(p -> p.getFileName().toString()));
            for (Path p : sorted) {
                String f = p.getFileName().toString();
                names.add(f.substring(0, f.length() - ".svg".length()));
            }
        }
        return names;
    }

    private static Set<String> readAllowlist() throws IOException {
        Set<String> result = new LinkedHashSet<>();
        try (InputStream in = LucideIconInventoryTest.class
                .getResourceAsStream("/" + LUCIDE_DIR + "/icons.allowed.txt")) {
            assertThat(in).as("icons.allowed.txt must be on the classpath").isNotNull();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                    result.add(trimmed);
                }
            }
        }
        return result;
    }
}
