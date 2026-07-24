package com.benesquivelmusic.daw.sdk.ui;

import org.junit.jupiter.api.Test;

import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Exports;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards against over-broad deletion when story 304 removed {@code PluginUI}
 * (Plugin View Design Book §8.5.1): the three surviving types of the
 * {@code com.benesquivelmusic.daw.sdk.ui} package — {@link PanelState},
 * {@link Rectangle2D} and {@link Workspace} — must still load, and the
 * {@code daw.sdk} module must keep exporting the package.
 */
class SdkUiPackageStillCompilesTest {

    @Test
    void survivingUiTypesStillLoad() {
        assertThat(PanelState.class).isNotNull();
        assertThat(Rectangle2D.class).isNotNull();
        assertThat(Workspace.class).isNotNull();
    }

    @Test
    void sdkUiPackageIsStillExported() {
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

        assertThat(declaredExports)
                .as("removing PluginUI must not drop the export of the ui"
                        + " package; PanelState, Rectangle2D and Workspace"
                        + " remain public API")
                .contains("com.benesquivelmusic.daw.sdk.ui");
    }
}
