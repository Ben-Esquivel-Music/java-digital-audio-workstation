package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class NoSyntheticAnalyzerFeedScanTest {
    @Test
    void syntheticFeedAndDuplicateWindowsHaveNoProductionReferences() throws Exception {
        Path repo = SourceScanSupport.locateDawAppModule().getParent();
        int scanned = 0;
        for (String module : List.of("daw-app", "daw-core")) {
            try (var paths = Files.walk(repo.resolve(module + "/src/main/java"))) {
                for (Path path : paths.filter(file -> file.toString().endsWith(".java")).toList()) {
                    scanned++;
                    String source = executableSource(Files.readString(path));
                    assertThat(source).as(path.toString()).doesNotContain(
                            "IdleVisualizationAnimator", "LoudnessDisplayWindow", "CorrelationDisplayWindow");
                    if (path.getFileName().toString().equals("SoundWaveTelemetryEditor.java")) {
                        assertThat(source).doesNotContain("Math.sin", "PHASE_RATE", "drawRibbon");
                        assertThat(source).contains("plugin.getWaveform()");
                    }
                    if (path.getFileName().toString().equals("AnimationController.java")) {
                        assertThat(source).doesNotContain("SpectrumDisplay", "SpectrumData", "updateSpectrum");
                    }
                }
            }
        }
        assertThat(scanned).isGreaterThan(500);
    }

    @Test
    void productionRootPersistsVisibilityAfterBothSingleAndGroupedToggles() throws Exception {
        String source = executableSource(Files.readString(SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java")));
        assertThat(source).contains("visualizationPreferences.saveLayout(newLayout)",
                "visualizationPreferences.saveLayout(dockManager.layout())", "prefs.restore(dockManager)");
    }

    @Test
    void harmlessCommentsAndLiteralsAreExcludedFromBothSentinels() {
        String source = executableSource("""
                // IdleVisualizationAnimator visualizationPreferences.saveLayout(newLayout)
                /* LoudnessDisplayWindow prefs.restore(dockManager) */
                String retired = "CorrelationDisplayWindow";
                String example = "visualizationPreferences.saveLayout(dockManager.layout())";
                plugin.getWaveform();
                """);
        assertThat(source).doesNotContain("IdleVisualizationAnimator", "LoudnessDisplayWindow",
                "CorrelationDisplayWindow", "visualizationPreferences.saveLayout", "prefs.restore");
        assertThat(source).contains("plugin.getWaveform()");
    }

    @Test
    void executableReferencesSurvivePreprocessing() {
        String source = executableSource("""
                new IdleVisualizationAnimator();
                visualizationPreferences.saveLayout(newLayout);
                visualizationPreferences.saveLayout(dockManager.layout());
                prefs.restore(dockManager);
                """);
        assertThat(source).contains("IdleVisualizationAnimator", "visualizationPreferences.saveLayout(newLayout)",
                "visualizationPreferences.saveLayout(dockManager.layout())", "prefs.restore(dockManager)");
    }

    private static String executableSource(String source) {
        return SourceScanSupport.stripStringLiterals(SourceScanSupport.stripComments(source));
    }
}
