package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.plugin.PluginJarScanner.JarInspection;
import com.benesquivelmusic.daw.sdk.editor.PluginManifestReader;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 303 review follow-up — the folder-install skipped-JARs dialog must
 * partition by skip reason: a JAR the scanner could not read (or whose manifest
 * failed to parse) must never be lumped into the "no manifest" section.
 */
class FolderInstallSkippedSummaryTest {

    private static final PluginManifestReader.BundleResult.Invalid INVALID =
            new PluginManifestReader.BundleResult.Invalid(List.of("some problem"));

    private static JarInspection inspection(String name, boolean jarReadable,
                                            boolean manifestPresent) {
        return new JarInspection(Path.of(name), jarReadable, manifestPresent, INVALID, 0, 0, 0);
    }

    @Test
    void summarySeparatesUnreadableInvalidAndManifestLessJars() {
        String summary = PluginBrowser.skippedSummary(List.of(
                inspection("corrupt.jar", false, false),
                inspection("bad-manifest.jar", true, true),
                inspection("plain.jar", true, false)));

        assertThat(summary).isNotNull();
        String[] sections = summary.split("\n\n");
        assertThat(sections).hasSize(3);
        assertThat(sections[0])
                .as("an unreadable JAR is reported as unreadable, not manifest-less")
                .contains("could not be read").contains("corrupt.jar");
        assertThat(sections[1])
                .as("a present-but-invalid manifest is reported as invalid")
                .contains("invalid manifest").contains("bad-manifest.jar");
        assertThat(sections[2])
                .as("only a readable, manifest-less JAR lands in the no-manifest section")
                .contains("no manifest").contains("plain.jar")
                .doesNotContain("corrupt.jar").doesNotContain("bad-manifest.jar");
    }

    @Test
    void summaryOmitsEmptySectionsAndIsNullWhenNothingSkipped() {
        assertThat(PluginBrowser.skippedSummary(List.of()))
                .as("no dialog when nothing was skipped").isNull();
        assertThat(PluginBrowser.skippedSummary(List.of(inspection("plain.jar", true, false))))
                .as("only the applicable section is emitted")
                .contains("no manifest")
                .doesNotContain("could not be read").doesNotContain("invalid manifest");
    }
}
