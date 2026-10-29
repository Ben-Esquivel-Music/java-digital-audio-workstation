package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, ruling R12 #13 — "workspace" is the dock layout's word: the
 * numbered workspace slots ({@code DawAction.WORKSPACE_1} to {@code _9}),
 * Save Current Workspace As… and the Mission Control layouts switch the dock
 * layout only, and never close the open project. The close protocol is
 * reached from the window's close box and the File menu's two doors alone
 * (design §1.3): only {@code MainController} and the coordinator itself name
 * {@code ExitCoordinator}; the workspace and layout sources never name it;
 * the slot shortcuts route to the workspace manager's slot switch; and the
 * close-then-continue primitive for a future project workspace switch
 * (story 333's last stage) has no layout door. A source scan, not a
 * reflection consumer (dawg-annotations-reflection §4, "Adding a new
 * reflection consumer").
 */
class CloseThenContinueIsNotALayoutPresetTest {

    private static final Path APP_MAIN = SourceScanSupport.locateDawAppModule().resolve("src/main/java");
    private static final Path UI = APP_MAIN.resolve("com/benesquivelmusic/daw/app/ui");

    @Test
    void onlyTheCompositionRootAndTheCoordinatorNameTheCloseProtocol() throws IOException {
        TreeSet<String> naming = new TreeSet<>();
        try (Stream<Path> sources = Files.walk(APP_MAIN)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String code = code(source);
                if (code.contains("ExitCoordinator") || code.contains("closeOpenProjectThenContinue")
                        || code.contains("CloseThenContinue")) {
                    naming.add(source.getFileName().toString());
                }
            }
        }
        assertThat(naming).as("non-vacuity and the rule: the coordinator and the composition root, no other")
                .containsExactly("ExitCoordinator.java", "MainController.java");
    }

    @Test
    void theWorkspaceSlotsAndTheLayoutsSwitchTheDockLayoutOnly() throws IOException {
        List<Path> layoutSources;
        try (Stream<Path> sources = Files.walk(UI.resolve("layout"))) {
            layoutSources = sources.filter(path -> path.toString().endsWith(".java")).toList();
        }
        assertThat(layoutSources).as("non-vacuity: the layout sources were found").isNotEmpty();
        for (Path source : Stream.concat(layoutSources.stream(),
                Stream.of(UI.resolve("WorkspaceManager.java"), UI.resolve("WorkspacesMenu.java"))).toList()) {
            assertThat(code(source)).as("%s never closes the open project", source.getFileName())
                    .doesNotContain("ExitCoordinator").doesNotContain("closeProject")
                    .doesNotContain("CloseDoor").doesNotContain("installUntitledAfterClose");
        }
        String keyboard = flat(code(UI.resolve("KeyboardShortcutController.java")));
        assertThat(keyboard).as("the slot shortcuts reach the workspace slot switch")
                .contains("actionHandlers.put(slotActions.get(i), () -> host.onSwitchToWorkspaceSlot(slot));")
                .contains("actionHandlers.put(DawAction.WORKSPACE_SAVE_AS, host::onSaveWorkspaceAs);");
        String main = flat(code(UI.resolve("MainController.java")));
        assertThat(main).as("which is the workspace manager's, and nothing of the close")
                .contains("@Override public void onSwitchToWorkspaceSlot(int slotIndex) {"
                        + " if (workspaceManager != null) workspaceManager.switchToSlot(slotIndex); }");
    }

    private static String code(Path file) throws IOException {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(source).as("non-vacuity: %s was read", file.getFileName()).isNotBlank();
        return SourceScanSupport.stripStringLiterals(SourceScanSupport.stripComments(source));
    }

    private static String flat(String code) {
        return code.replaceAll("\\s+", " ");
    }
}
