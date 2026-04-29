package com.benesquivelmusic.daw.sdk.module;

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
 * Compile-check test that asserts the exported packages of {@code daw.sdk}
 * exactly match the committed allowlist in
 * {@code META-INF/api-packages.allowlist}.
 *
 * <p>Adding or removing a {@code module-info.java} export without updating the
 * allowlist (or vice versa) makes this test fail, forcing a deliberate
 * acknowledgement of the API-surface change.
 *
 * <p>Tier model: public API / SPI / internal — see
 * {@code docs/ARCHITECTURE.md} > "Module export tiers".
 */
class ModuleExportsAllowlistTest {

    private static final String ALLOWLIST_RESOURCE = "/META-INF/api-packages.allowlist";

    @Test
    void exportsMatchAllowlist() throws IOException {
        ModuleDescriptor descriptor = getClass().getModule().getDescriptor();
        // When tests run on the classpath instead of the module path the
        // module is unnamed and has no descriptor. Surefire 3.5+ runs modular
        // projects on the module path, so the descriptor must be present here.
        assertThat(descriptor)
                .as("daw.sdk must run as a named module so its exports can be"
                        + " inspected; check the maven-surefire-plugin"
                        + " configuration if this fails")
                .isNotNull();
        assertThat(descriptor.name()).isEqualTo("daw.sdk");

        Set<String> declaredExports = descriptor.exports().stream()
                .filter(e -> !e.isQualified()) // qualified `exports ... to X` is not part of public API
                .map(Exports::source)
                .collect(Collectors.toCollection(TreeSet::new));

        Set<String> allowedExports = readAllowlist();

        assertThat(declaredExports)
                .as("Exports declared in module-info.java must equal the"
                        + " allowlist in %s. Update both files together.",
                        ALLOWLIST_RESOURCE)
                .isEqualTo(allowedExports);
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
