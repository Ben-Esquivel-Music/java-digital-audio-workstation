package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333 — how the application wires File ▸ Save As… (ruling R5; Control
 * Synchronization book §2.8: the menu, the palette and a save failure's
 * action are one intent), pinned in the sources because
 * {@code MainController} is never FXML-loaded in tests: both of its hosts
 * route Save As to the lifecycle controller, the keyboard controller maps
 * {@code DawAction.SAVE_AS} for the palette, the menu item is built from the
 * bundle's label, and {@code MainController} builds the flow — over the
 * session journal ending and the folder closer — and installs it. A source
 * scan, not a reflection consumer (dawg-annotations-reflection §4, "Adding a
 * new reflection consumer").
 *
 * <p>It also pins the studio engineer's E3-S5: the application captures the
 * open project only through the project manager
 * ({@code ProjectManager.captureOpenProject}, which writes a Save As copy's
 * former roots) — the periodic checkpoint and Create Checkpoint — and
 * references {@code ProjectSerializer} only to serialise a recovered or an
 * archived project, which is no capture of the open one.</p>
 */
class SaveAsWiringSourceTest {

    private static final Path UI = SourceScanSupport.locateDawAppModule()
            .resolve("src/main/java/com/benesquivelmusic/daw/app/ui");

    @Test
    void bothHostsRouteSaveAsToTheLifecycleControllerAndThePaletteMapsIt() throws IOException {
        String main = flat(code("MainController.java"));

        assertThat(occurrences(main,
                "@Override public void onSaveProjectAs() { projectLifecycleController.onSaveProjectAs(); }"))
                .as("the menu's MenuActions and the keyboard's Host: one intent").isEqualTo(2);
        assertThat(flat(code("KeyboardShortcutController.java")))
                .contains("actionHandlers.put(DawAction.SAVE_AS, host::onSaveProjectAs);");
        assertThat(Files.readString(UI.resolve("MenuConstructionService.java"), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " "))
                .contains("menuItem(msg(\"menu.file.saveAs\"), DawIcon.DOWNLOAD, DawAction.SAVE_AS,"
                        + " host::onSaveProjectAs);")
                .contains("saveItem, saveAsItem,");
    }

    @Test
    void mainControllerBuildsTheFlowOverTheJournalEndingAndTheCloserAndInstallsIt() throws IOException {
        String main = flat(code("MainController.java"));

        assertThat(body(main, "private void createProjectLifecycleController()")).contains("createSaveAsFlow();");
        assertThat(body(main, "private void createSaveAsFlow()"))
                .contains("new SessionJournalEnding(projectJournalCoordinator, sessionManager, () -> activeSession,"
                        + " () -> activeSessionProjectDir, () -> sessionOpening, this::currentProjectFolder,")
                .contains("projectFolderCloser = new ProjectFolderCloser(projectManager, projectJournalCoordinator,"
                        + " journalEnding, () -> project, dispatcher());")
                .contains("projectLifecycleController.saveAsLifecycle(), projectFolderCloser,"
                        + " projectLifecycleController.closePrompts(), SaveAsTargetPrompt.saveDialog(owner),")
                .contains("projectLifecycleController.setSaveAsFlow(saveAsFlow);");
        assertThat(body(main, "private void projectFolderChanged()"))
                .as("the folder change forgets another folder's session before it opens this one's, and opens no"
                        + " journal (ruling 2 OQ-1, constraint 1)")
                .contains("clearSessionIfNotTheOpenFolder(); refreshSessionManager(true);")
                .doesNotContain("openFor").doesNotContain("journal");
        assertThat(body(main, "private void refreshSessionManager(boolean openSession)"))
                .as("the session being opened is recorded before its worker starts (FEAS-S11)")
                .contains("sessionOpening = new SessionJournalEnding.SessionOpening(projectDir, sessionGeneration,"
                        + " opening);")
                .contains("finally { opening.complete(java.util.Optional.ofNullable(opened)); }");
    }

    @Test
    void theApplicationCapturesTheOpenProjectOnlyThroughTheProjectManager() throws IOException {
        Map<String, Integer> serializers = new TreeMap<>();
        Map<String, Integer> captures = new TreeMap<>();
        try (Stream<Path> sources = Files.walk(SourceScanSupport.locateDawAppModule().resolve("src/main/java"))) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String code = flat(SourceScanSupport.stripStringLiterals(SourceScanSupport.stripComments(
                        Files.readString(source, StandardCharsets.UTF_8))));
                String name = source.getFileName().toString();
                int made = occurrences(code, "new ProjectSerializer()")
                        + occurrences(code, "new com.benesquivelmusic.daw.core.persistence.ProjectSerializer()");
                if (made > 0) {
                    serializers.merge(name, made, Integer::sum);
                }
                int captured = occurrences(code, "serializer.capture(");
                if (captured > 0) {
                    captures.merge(name, captured, Integer::sum);
                }
            }
        }

        assertThat(serializers).as("E3-S5: a serializer only for a recovered or an archived project").isEqualTo(Map.of(
                "OpenRecoveryGate.java", 1,
                "ProjectLifecycleController.java", 2));
        assertThat(captures).as("no capture of the open project but the project manager's").isEmpty();
        assertThat(flat(code("MainController.java")))
                .contains("return projectManager.captureOpenProject(project.get())::toXml;");
        assertThat(flat(code("SnapshotsController.java")))
                .contains("content = projectManager.captureOpenProject(project).toXml();");
    }

    private static String code(String file) throws IOException {
        return SourceScanSupport.stripStringLiterals(SourceScanSupport.stripComments(
                Files.readString(UI.resolve(file), StandardCharsets.UTF_8)));
    }

    private static String flat(String code) {
        return code.replaceAll("\\s+", " ");
    }

    private static int occurrences(String code, String fragment) {
        int count = 0;
        for (int at = code.indexOf(fragment); at >= 0; at = code.indexOf(fragment, at + 1)) {
            count++;
        }
        return count;
    }

    private static String body(String code, String signature) {
        int declaration = code.indexOf(signature);
        assertThat(declaration).as("declares %s", signature).isNotNegative();
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
