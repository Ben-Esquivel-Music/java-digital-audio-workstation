package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.project.DawProject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 335 (studio ruling R4.1) — the default tempo's steady-state role is
 * the new-project template: every new empty project takes it as its beat-0
 * BPM, with no undo entry and the project clean. Close Project (whose
 * replacement New shares) runs through the application's close protocol
 * ({@link ExitFixture}) over a lifecycle controller handed a new-project
 * tempo, as {@code MainController} hands it the persisted default; the
 * startup Untitled project and the remaining sites are pinned by source
 * ({@code initialize()} builds a live audio engine and is never run).
 */
@ExtendWith(JavaFxToolkitExtension.class)
class NewProjectTakesDefaultTempoTest {

    @TempDir
    Path workspace;

    @Test
    void closeProjectInstallsAnUntitledProjectAtTheNewProjectTempoCleanWithAnEmptyHistory() throws Exception {
        try (ExitFixture fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT)) {
            runOnFx(() -> fixture.lifecycle().setNewProjectTempo(() -> 97.0));
            DawProject closed = fixture.save.project.get();

            fixture.closeProject().toCompletableFuture().get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS);
            fixture.await(() -> fixture.welcomes.get() == 1, "the Welcome window after the run ended");

            DawProject untitled = fixture.save.project.get();
            assertThat(untitled).isNotSameAs(closed);
            assertThat(untitled.getName()).isEqualTo("Untitled Project");
            assertThat(untitled.getTransport().getTempo()).isEqualTo(97.0);
            assertThat(untitled.getTransport().getTempoMap().getTempoChangeCount()).isEqualTo(1);
            assertThat(untitled.isDirty()).as("a template is no edit").isFalse();
            assertThat(onFx(() -> fixture.save.undoManager.get().undoSize())).isZero();
            assertThat(onFx(() -> fixture.save.undoManager.get().redoSize())).isZero();
        }
    }

    @Test
    void theStartupUntitledProjectAndEveryNewEmptyProjectTakeThePersistedDefault() throws IOException {
        Path ui = SourceScanSupport.locateDawAppModule().resolve("src/main/java/com/benesquivelmusic/daw/app/ui");
        String main = flatCode(ui.resolve("MainController.java"));
        assertThat(methodBody(main, "private void initialize()"))
                .contains("project.getTransport().setTempo(startupSettings.getDefaultTempo());");
        assertThat(methodBody(main, "private void createProjectLifecycleController()"))
                .contains("projectLifecycleController.setNewProjectTempo(settingsModel::getDefaultTempo);");

        String lifecycle = flatCode(ui.resolve("ProjectLifecycleController.java"));
        assertThat(methodBody(lifecycle, "private DawProject newUntitledProject()"))
                .contains("untitled.getTransport().setTempo(newProjectTempo.getAsDouble());");
        assertThat(lifecycle.split("new DawProject\\(\"Untitled Project\"", -1))
                .as("every new empty project is built by newUntitledProject()").hasSize(2);
        assertThat(lifecycle.split("newUntitledProject\\(\\)", -1))
                .as("New and Close Project, the import placeholder, the migration roll-back, and the declaration")
                .hasSize(5);
    }

    private static String flatCode(Path file) throws IOException {
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(raw.length()).as("non-vacuity: %s was read", file.getFileName()).isGreaterThan(10_000);
        return SourceScanSupport.stripComments(raw).replaceAll("\\s+", " ");
    }

    private static String methodBody(String code, String signature) {
        int declaration = code.indexOf(signature);
        assertThat(declaration).as("the source declares %s", signature).isNotNegative();
        int open = code.indexOf('{', declaration);
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return code.substring(open, i + 1);
            }
        }
        throw new AssertionError("unbalanced braces after " + signature);
    }
}
