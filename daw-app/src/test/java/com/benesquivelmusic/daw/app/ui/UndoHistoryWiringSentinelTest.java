package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Source-scan sentinel for the undo-history wiring that has no headless
 * production test: {@code MainController} is FXML-loaded and never
 * constructed in tests (the {@code EngineBinderConformanceTest} idiom), and
 * the arrangement canvas's dirty hook is only reached through an Alt-click
 * gesture on a take lane.
 *
 * <p>{@code onUndo} / {@code onRedo} no longer mark the project dirty
 * themselves: the per-generation {@code UndoHistoryDirtyBinder} does, for
 * every surface (Control Synchronization Design Book §5.1, §7 "one dirty
 * bit"). This test fails if that binder, its dispose, the one menu-sync /
 * repaint history listener or its removal is deleted from
 * {@code rebuildViewModels()}, if {@code initialize()} or
 * {@code HistoryPanelController} registers a history listener again
 * (javafx-application-design §15: one listener owner, every registration
 * paired with its removal), or if the arrangement canvas's
 * {@code markProjectDirty} captures a project instead of reading the live
 * one, or is no longer wired. The factory body must hold no bound
 * {@code ::markDirty} reference at all; the timeline ruler's edit callback,
 * the dirty mark of a loop or punch gesture (PR #984 review), is otherwise
 * pinned by behaviour, headlessly, in
 * {@code ArrangementCanvasFactoryRulerRateTest}. It also fails if
 * {@code handleProjectRebuild()} stops re-attaching the one
 * {@code TrackFreezeController} to the rebuilt mixer and track strip, or
 * stops refreshing that mixer once the controller is attached, or if that
 * controller stops reading the live project and undo manager or marks the
 * project dirty itself. Every scan first proves it found a non-trivial body,
 * so none can pass vacuously.</p>
 */
final class UndoHistoryWiringSentinelTest {

    private static final String UI_SOURCES = "src/main/java/com/benesquivelmusic/daw/app/ui/";

    /** Shortest body a located method may have — a guard against a scan of a stub or a mis-match. */
    private static final int NON_TRIVIAL_BODY = 200;

    /** {@code HistoryPanelController.rebuild()} is short by design: dispose the panel, build the next. */
    private static final int MINIMAL_PANEL_REBUILD = 60;

    @Test
    void rebuildViewModelsAttachesTheDirtyBinderToThisGenerationsManagerAndProject() throws IOException {
        String body = rebuildViewModelsBody();

        int disposePreviousGeneration = body.indexOf("vmDisposers.clear();");
        assertThat(disposePreviousGeneration)
                .as("rebuildViewModels() disposes the previous generation first")
                .isNotNegative();

        Matcher binder = Pattern.compile(
                "UndoHistoryDirtyBinder (\\w+) = new UndoHistoryDirtyBinder\\( ?undoManager ?, ?project ?\\);")
                .matcher(body);
        assertThat(binder.find())
                .as("rebuildViewModels() constructs the UndoHistoryDirtyBinder over this "
                        + "generation's undoManager and project (onUndo / onRedo rely on it)")
                .isTrue();
        assertThat(binder.start())
                .as("the binder is built for the new generation, after the old one is disposed")
                .isGreaterThan(disposePreviousGeneration);
        String binderName = binder.group(1);
        assertThat(body.indexOf("vmDisposers.add(" + binderName + "::dispose);", binder.end()))
                .as("the binder's dispose is registered with the generation's disposers")
                .isNotNegative();
        assertThat(occurrences(body, "new UndoHistoryDirtyBinder("))
                .as("exactly one dirty binder per generation")
                .isEqualTo(1);
    }

    @Test
    void theMenuSyncAndRepaintListenerHasOneOwnerAndAMatchingRemoval() throws IOException {
        String body = rebuildViewModelsBody();

        Matcher add = Pattern.compile("(\\w+)\\.addHistoryListener\\((\\w+)\\);").matcher(body);
        List<String> registrations = new ArrayList<>();
        while (add.find()) {
            registrations.add(add.group());
        }
        assertThat(registrations)
                .as("rebuildViewModels() registers exactly one history listener of its own")
                .hasSize(1);
        assertThat(occurrences(body, "addHistoryListener"))
                .as("no second registration in any form: an inline lambda, a method reference "
                        + "(::addHistoryListener) or a spaced call all name the token once more")
                .isEqualTo(1);
        add.reset();
        assertThat(add.find()).isTrue();
        String manager = add.group(1);
        String listener = add.group(2);

        assertThat(body)
                .as("the listener is registered on this generation's manager, captured for its removal")
                .contains("UndoManager " + manager + " = undoManager;");
        Matcher listenerBody = Pattern.compile(
                "UndoHistoryListener " + Pattern.quote(listener) + " = \\(_, _\\) -> \\{(.*?)\\};")
                .matcher(body);
        assertThat(listenerBody.find())
                .as("the registered listener is declared in rebuildViewModels()")
                .isTrue();
        assertThat(listenerBody.group(1))
                .as("it is the menu-sync + arrangement-repaint reaction")
                .contains("syncMenuStateIfPresent();")
                .contains("repaintArrangementCanvas();");
        assertThat(body.indexOf("vmDisposers.add(() -> " + manager + ".removeHistoryListener(" + listener + "));"))
                .as("every registration has its removal, on the same manager")
                .isNotNegative();
        assertThat(body.indexOf("new UndoHistoryDirtyBinder("))
                .as("registered after the dirty binder, so a menu sync reads the bit it just set")
                .isLessThan(add.start());
    }

    @Test
    void initializeRegistersNoHistoryListener() throws IOException {
        String body = methodBody(mainControllerCode(), "private void initialize()");

        assertThat(body)
                .as("guard: the scan found initialize(), where the first generation's manager is made")
                .contains("undoManager = new UndoManager();");
        assertThat(body)
                .as("initialize() registers no history listener: rebuildViewModels() owns them "
                        + "for every generation, the first included")
                .doesNotContain("addHistoryListener(");
    }

    @Test
    void theHistoryPanelControllerOnlyRebuildsItsPanel() throws IOException {
        String code = sourceCode("HistoryPanelController.java");
        String rebuild = methodBody(code, "void rebuild()", MINIMAL_PANEL_REBUILD);

        assertThat(rebuild)
                .as("guard: the scan found rebuild(), which builds the panel over the current manager")
                .contains("new UndoHistoryPanel(");
        assertThat(code)
                .as("the panel controller registers no history listener of its own "
                        + "(the panel itself does, and its dispose() removes it)")
                .doesNotContain("addHistoryListener(")
                .doesNotContain("syncMenuState")
                .doesNotContain("refreshArrangementCanvas");
    }

    @Test
    void theArrangementCanvasMarksTheLiveProjectDirtyAtCallTime() throws IOException {
        String body = methodBody(sourceCode("ArrangementCanvasFactory.java"), "static Result create(");

        int deps = body.indexOf("new ClipInteractionController.Deps(");
        assertThat(deps)
                .as("guard: the scan found the ClipInteractionController.Deps the factory builds")
                .isNotNegative();
        assertThat(body.substring(deps))
                .as("the last Deps component, markProjectDirty, reads host.project() when it "
                        + "runs, so it marks the project of the current generation")
                .contains(", () -> host.project().markDirty()));");
        assertThat(body)
                .as("never a bound reference (host.project()::markDirty), which would capture "
                        + "the project the canvas and the ruler were created with")
                .doesNotContain("::markDirty");

        String controller = sourceCode("ClipInteractionController.java");
        int record = controller.indexOf("record Deps(");
        assertThat(record).as("guard: ClipInteractionController declares its Deps record").isNotNegative();
        assertThat(controller.indexOf("Runnable markProjectDirty) {", record))
                .as("the factory's last Deps argument is the markProjectDirty component")
                .isNotNegative();
    }

    @Test
    void theRebuiltMixerAndTrackStripGetTheOneFreezeController() throws IOException {
        String body = methodBody(mainControllerCode(), "private void handleProjectRebuild()");

        int mixer = body.indexOf("MixerView newMixerView = new MixerView(");
        assertThat(mixer)
                .as("guard: the scan found handleProjectRebuild(), which builds the next MixerView")
                .isNotNegative();
        int freezeSetter = body.indexOf("newMixerView.setTrackFreezeController(trackFreezeController);", mixer);
        assertThat(freezeSetter)
                .as("the rebuilt mixer keeps its freeze context-menu entries")
                .isNotNegative();
        int cpuBudget = body.indexOf("installTrackCpuBudgetEnforcer();", freezeSetter);
        assertThat(cpuBudget)
                .as("guard: the CPU-budget handler and degraded predicate are installed after the freeze controller")
                .isNotNegative();
        assertThat(body.indexOf("newMixerView.refresh();", cpuBudget))
                .as("the rebuilt mixer is refreshed after the freeze controller and the CPU-budget "
                        + "wiring are set: the constructor's refresh() ran before them and no setter "
                        + "refreshes, so without it no strip shows \"Freeze track\" after an Open")
                .isNotNegative();
        int strip = body.indexOf("createTrackStripController();");
        assertThat(strip)
                .as("guard: handleProjectRebuild() rebuilds the track strip controller")
                .isNotNegative();
        assertThat(body.indexOf("trackStripController.setTrackFreezeController(trackFreezeController);", strip))
                .as("the rebuilt track strip keeps its freeze context-menu entries")
                .isNotNegative();
    }

    @Test
    void theFreezeControllerReadsTheLiveGenerationAndLeavesTheDirtyBitToTheBinder() throws IOException {
        String body = methodBody(mainControllerCode(), "private void createTrackFreezeController()");

        assertThat(body)
                .as("the one init-time controller reads the project and undo manager live, "
                        + "so it follows every Open, New, import, recovery and snapshot restore")
                .contains("new TrackFreezeController(() -> project, () -> undoManager,");
        assertThat(body)
                .as("its refresh callbacks mark nothing dirty: every freeze runs through the "
                        + "generation's UndoManager.execute, so the generation's binder marks it")
                .doesNotContain("markDirty");
    }

    private static String rebuildViewModelsBody() throws IOException {
        String body = methodBody(mainControllerCode(), "private void rebuildViewModels()");
        assertThat(body)
                .as("guard: the scan found the rebuildViewModels() that builds the VM generation")
                .contains("new HistoryVM(undoManager");
        return body;
    }

    private static String mainControllerCode() throws IOException {
        return sourceCode("MainController.java");
    }

    /** The comment- and literal-free source of a daw-app ui file, whitespace collapsed to single spaces. */
    private static String sourceCode(String fileName) throws IOException {
        Path file = SourceScanSupport.locateDawAppModule().resolve(UI_SOURCES + fileName);
        assertThat(Files.isRegularFile(file)).as("%s exists at %s", fileName, file).isTrue();
        String code = SourceScanSupport.stripStringLiterals(
                SourceScanSupport.stripComments(Files.readString(file, StandardCharsets.UTF_8)));
        return code.replaceAll("\\s+", " ")
                .replaceAll("\\( ", "(")
                .replaceAll(" \\)", ")");
    }

    /**
     * The brace-balanced body of the first declaration starting with
     * {@code signature}, asserted non-trivial (more than
     * {@value #NON_TRIVIAL_BODY} characters once whitespace is collapsed).
     */
    private static String methodBody(String code, String signature) {
        return methodBody(code, signature, NON_TRIVIAL_BODY);
    }

    private static String methodBody(String code, String signature, int minimumLength) {
        int start = code.indexOf(signature);
        assertThat(start).as("the source declares `%s`", signature).isNotNegative();
        int open = code.indexOf('{', start);
        assertThat(open).as("`%s` has a body", signature).isNotNegative();
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    String body = code.substring(open, i + 1);
                    assertThat(body.length())
                            .as("guard: the body of `%s` is non-trivial", signature)
                            .isGreaterThan(minimumLength);
                    return body;
                }
            }
        }
        throw new AssertionError("Unbalanced braces after " + signature);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }
}
