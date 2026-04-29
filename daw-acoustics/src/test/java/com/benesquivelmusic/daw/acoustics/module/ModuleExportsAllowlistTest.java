package com.benesquivelmusic.daw.acoustics.module;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Exports;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compile-check test: the exported packages of {@code daw.acoustics} must
 * exactly match {@code META-INF/api-packages.allowlist}.
 *
 * <p>See {@code docs/ARCHITECTURE.md} > "Module export tiers".
 */
class ModuleExportsAllowlistTest {

    private static final String ALLOWLIST_RESOURCE = "/META-INF/api-packages.allowlist";
    private static final String MODULE_NAME = "daw.acoustics";

    @Test
    void exportsMatchAllowlist() throws IOException {
        ModuleDescriptor descriptor = getClass().getModule().getDescriptor();
        assertThat(descriptor)
                .as("%s must run as a named module so its exports can be"
                        + " inspected; check the maven-surefire-plugin"
                        + " configuration if this fails", MODULE_NAME)
                .isNotNull();
        assertThat(descriptor.name()).isEqualTo(MODULE_NAME);

        Set<String> declared = descriptor.exports().stream()
                .filter(e -> !e.isQualified())
                .map(Exports::source)
                .collect(Collectors.toCollection(TreeSet::new));

        Set<String> allowed = readAllowlist();

        assertThat(declared)
                .as("Exports declared in module-info.java must equal the"
                        + " allowlist in %s. Update both files together.",
                        ALLOWLIST_RESOURCE)
                .isEqualTo(allowed);
    }

    private Set<String> readAllowlist() throws IOException {
        Set<String> packages = new TreeSet<>();
        try (InputStream in = getClass().getResourceAsStream(ALLOWLIST_RESOURCE)) {
            assertThat(in)
                    .as("allowlist resource %s must exist on the test classpath",
                            ALLOWLIST_RESOURCE)
                    .isNotNull();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    packages.add(trimmed);
                }
            }
        }
        return packages;
    }
}
