package com.benesquivelmusic.daw.app.ui.inspector;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §5.6 — selection routes through the inspector, so the legacy
 * {@code ClipEditDialog.fxml} / {@code TempoEditDialog.fxml} modals
 * must not be reintroduced. This test asserts there is no FXML by
 * those names in the resources tree and no Java source still spawning
 * a {@link javafx.stage.Stage} for them.
 */
class ClipDialogRemovedTest {

    private static final Path REPO_ROOT = resolveRepoRoot();

    private static Path resolveRepoRoot() {
        // Tests run with cwd = daw-app (Maven default). Walk up until
        // we find the multi-module pom.xml at the repository root.
        Path cwd = Paths.get("").toAbsolutePath();
        Path p = cwd;
        for (int i = 0; i < 6 && p != null; i++) {
            if (Files.exists(p.resolve("daw-app")) && Files.exists(p.resolve("pom.xml"))) {
                return p;
            }
            p = p.getParent();
        }
        return cwd;
    }

    @Test
    void clipEditDialogFxmlIsNotPresentInResources() throws IOException {
        Path resources = REPO_ROOT.resolve("daw-app/src/main/resources");
        if (!Files.isDirectory(resources)) {
            return; // tolerate alternate layouts
        }
        try (Stream<Path> s = Files.walk(resources)) {
            List<Path> hits = s.filter(p -> {
                String name = p.getFileName() == null ? "" : p.getFileName().toString();
                return name.equals("ClipEditDialog.fxml")
                        || name.equals("TempoEditDialog.fxml");
            }).toList();
            assertThat(hits)
                    .as("§5.6 removes ad-hoc Clip/Tempo edit dialogs in favour of the Inspector")
                    .isEmpty();
        }
    }

    @Test
    void noStageSpawningCodeReferencesClipOrTempoEditDialogFxml() throws IOException {
        Path src = REPO_ROOT.resolve("daw-app/src/main/java");
        if (!Files.isDirectory(src)) {
            return;
        }
        try (Stream<Path> s = Files.walk(src)) {
            List<Path> offenders = s.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                try {
                    String content = Files.readString(p);
                    return content.contains("ClipEditDialog.fxml")
                            || content.contains("TempoEditDialog.fxml");
                } catch (IOException ex) {
                    return false;
                }
            }).toList();
            assertThat(offenders)
                    .as("no Java source may spawn a Stage for the removed dialogs")
                    .isEmpty();
        }
    }
}
