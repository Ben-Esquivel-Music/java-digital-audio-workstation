package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 329 — the production wiring of the open project's audio, which no
 * headless test reaches ({@code MainController} is never FXML-loaded in
 * tests): the controller's {@code Deps.repaintArrangement} is the arrangement
 * repaint {@code AudioImportController} also receives, and that repaint
 * really repaints the canvas; the canvas asks the owner what each clip's body
 * shows; the window's disposal (story 333's DISPOSE) disposes the owner; and a snapshot restore
 * installs through the lifecycle controller. A cut default — a no-op
 * repaint, an unwired canvas, a teardown without {@code dispose()} — fails
 * here, while the behavioural tests ({@code ProjectOpenDoesNotDecodeOnTheFxThreadTest})
 * pin that the drain runs {@code Deps.repaintArrangement}.
 */
class OpenProjectAudioWiringTest {

    private static final String DEPS = "new ProjectLifecycleController.Deps(";

    @Test
    void mainControllerWiresTheRepaintTheCanvasStateTheTeardownAndTheSnapshotDoor() throws IOException {
        Path file = SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java");
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(raw.length()).as("non-vacuity: the MainController source was read").isGreaterThan(50_000);
        String code = SourceScanSupport.stripStringLiterals(SourceScanSupport.stripComments(raw))
                .replaceAll("\\s+", " ");

        assertThat(code.indexOf(DEPS)).as("MainController builds the lifecycle Deps").isNotNegative()
                .as("exactly once").isEqualTo(code.lastIndexOf(DEPS));
        List<String> arguments = arguments(code, code.indexOf(DEPS) + DEPS.length() - 1);
        assertThat(arguments).as("the ten Deps components").hasSize(10);
        assertThat(arguments.getLast()).as("repaintArrangement is the arrangement repaint")
                .isEqualTo("this::repaintArrangementCanvas");
        assertThat(methodBody(code, "private void createAudioImportController()"))
                .as("the same repaint the audio import receives")
                .contains("this::repaintArrangementCanvas");
        assertThat(methodBody(code, "private void repaintArrangementCanvas()"))
                .as("and it repaints the canvas")
                .contains("arrangementCanvas.setTracks(projectVM.getTracks());");

        assertThat(methodBody(code, "private void createArrangementCanvas()"))
                .as("the canvas asks the open project's audio what each clip's body shows")
                .contains("arrangementCanvas.setClipAudioStates(projectLifecycleController.openProjectAudio()::stateOf);");
        // Story 333 — the teardown is the window's disposal (DISPOSE), run by the exit protocol, its first step.
        assertThat(methodBody(code, "java.util.concurrent.CompletionStage<Void> disposeWindowLifetime()"))
                .as("the window's disposal retires the open project's audio first")
                .contains("runWindowTeardown(() -> { if (projectLifecycleController != null) {"
                        + " projectLifecycleController.dispose(); } }");
        assertThat(methodBody(code, "private void applySnapshotRestoredProject("))
                .as("a snapshot restore installs, rehydrates and announces through the lifecycle controller")
                .contains("projectLifecycleController.installRestoredSnapshot(restored, label, missingAssets, unreadItems);");
    }

    /** The top-level arguments of the call whose opening parenthesis is at {@code open}, trimmed. */
    private static List<String> arguments(String code, int open) {
        assertThat(code.charAt(open)).as("an argument list opens at %s", open).isEqualTo('(');
        List<String> arguments = new ArrayList<>();
        int depth = 0;
        int start = open + 1;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '{') {
                depth++;
            } else if (c == ')' || c == '}') {
                if (--depth == 0) {
                    arguments.add(code.substring(start, i).trim());
                    return arguments;
                }
            } else if (c == ',' && depth == 1) {
                arguments.add(code.substring(start, i).trim());
                start = i + 1;
            }
        }
        throw new AssertionError("unbalanced parentheses after " + open);
    }

    /** The brace-balanced body of the method whose declaration starts with {@code signature}. */
    private static String methodBody(String code, String signature) {
        int declaration = code.indexOf(signature);
        assertThat(declaration).as("MainController declares %s", signature).isNotNegative();
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
