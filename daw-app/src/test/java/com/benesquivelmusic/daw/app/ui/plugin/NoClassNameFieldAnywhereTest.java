package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.SourceScanSupport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 304 — guards the §8.5.2 "manifest required to install" contract at the
 * source level: the §1.4 "type a class name" friction is fully gone from the
 * plugin install flow (Plugin View Design Book §1.4, §8.5.2). No plugin-UI
 * source may reference the retired {@code classNameField}, and the two
 * install-flow sources ({@code PluginInstallPanel.java},
 * {@code PluginManagerDialog.java}) may not construct or mention a text field
 * at all — a manifest-less JAR is rejected with copy, never offered a typing
 * affordance.
 *
 * <p>This is a sibling of {@code RunLaterConsolidationTest} and
 * {@code StatusStripNoLabelSetTextTest} — the conformance-sentinel pattern: a
 * green gate that scans the source tree and prevents drift, so a future edit
 * cannot quietly reintroduce a class-name input.</p>
 *
 * <h3>Scope &amp; preprocessing</h3>
 *
 * <p>The scan covers {@code PluginManagerDialog.java} (which lives in
 * {@code …app.ui}) plus every {@code .java} under {@code …app.ui.plugin} in
 * {@code src/main} — this test itself lives under {@code src/test}, so the walk
 * is self-excluding. To stay faithful to a SOURCE-level scan it strips
 * {@code //} and {@code /* *\/} comments before matching — via the shared
 * {@link SourceScanSupport} harness — so a historical mention in Javadoc
 * would not false-match. The {@code TextField} assertion is deliberately scoped
 * to exactly the two install-flow files rather than the whole package:
 * {@code PluginBrowser.java} legitimately owns a {@code TextField} — its §6.7
 * search box — which is browser filtering, not class-name entry.</p>
 */
final class NoClassNameFieldAnywhereTest {

    /**
     * The install-flow sources that must be free of any text-field token: the
     * §6.8 install panel and the manager dialog that hosts the install flow.
     * {@code PluginBrowser.java} is excluded on purpose — its search box is a
     * legitimate {@code TextField} (§6.7), unrelated to class-name entry.
     */
    private static final Set<String> INSTALL_FLOW_FILES =
            Set.of("PluginInstallPanel.java", "PluginManagerDialog.java");

    /** Files the non-empty-scan guard requires the walk to have visited. */
    private static final List<String> REQUIRED_SCANNED_FILES = List.of(
            "PluginManagerDialog.java",
            "PluginInstallPanel.java",
            "PluginBrowser.java",
            "PluginJarScanner.java");

    @Test
    void installFlowSourcesCarryNoClassNameFieldOrTextField() throws IOException {
        Path uiDir = SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/ui");
        Path pluginDir = uiDir.resolve("plugin");
        Path managerDialog = uiDir.resolve("PluginManagerDialog.java");
        assertThat(Files.isDirectory(pluginDir))
                .as("the plugin UI sources must live under %s", pluginDir)
                .isTrue();
        assertThat(Files.isRegularFile(managerDialog))
                .as("the manager dialog source must exist: %s", managerDialog)
                .isTrue();

        List<Path> inScope = new ArrayList<>();
        inScope.add(managerDialog);
        Files.walkFileTree(pluginDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().endsWith(".java")) {
                    inScope.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        List<String> classNameFieldOffenders = new ArrayList<>();
        List<String> textFieldOffenders = new ArrayList<>();
        List<String> scanned = new ArrayList<>();

        for (Path file : inScope) {
            String name = file.getFileName().toString();
            scanned.add(name);

            // Strip comments so a historical Javadoc mention does not
            // false-match; string literals are kept — neither token legitimately
            // appears inside a string in any in-scope file.
            String code = SourceScanSupport.stripComments(
                    Files.readString(file, StandardCharsets.UTF_8));
            if (code.contains("classNameField")) {
                classNameFieldOffenders.add(name
                        + "  — references the retired classNameField (story 304 "
                        + "removed the class-name install fallback)");
            }
            if (INSTALL_FLOW_FILES.contains(name) && code.contains("TextField")) {
                textFieldOffenders.add(name
                        + "  — the install flow may not contain a TextField; a "
                        + "manifest-less JAR is rejected with copy, never offered "
                        + "a typing affordance (story 304 §8.5.2)");
            }
        }

        // Guard against a silently-empty scan — a broken path would make this
        // audit vacuously pass.
        assertThat(scanned)
                .as("the scan must visit the install-flow and browser sources")
                .containsAll(REQUIRED_SCANNED_FILES)
                .hasSizeGreaterThanOrEqualTo(REQUIRED_SCANNED_FILES.size() + 1);

        classNameFieldOffenders.sort(String::compareTo);
        assertThat(classNameFieldOffenders)
                .as("Story 304 (§1.4, §8.5.2) — no plugin-UI source may reference "
                        + "the retired classNameField. Offending files:%n  %s",
                        String.join("\n  ", classNameFieldOffenders))
                .isEmpty();

        textFieldOffenders.sort(String::compareTo);
        assertThat(textFieldOffenders)
                .as("Story 304 (§1.4, §8.5.2) — the install-flow sources must be "
                        + "TextField-free. Offending files:%n  %s",
                        String.join("\n  ", textFieldOffenders))
                .isEmpty();
    }
}
