package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The close's own surfaces take real input while the window is inert (story
 * 333, design §1.5; the studio engineer's ruling R-3.3; PR #989 review
 * 5711460812). The fence covers every window shown while it is up, so each
 * window the close shows carries the close-surface mark: the unsaved-changes
 * prompt's Cancel answers a click and its Save and close Enter; the takeover
 * dialog's Save As… answers Enter and its Cancel Escape; the Closing dialog's
 * Don't quit and Quit anyway answer a click, and Escape keeps the project
 * open; the Save As refusal's OK answers Enter and the Save As progress
 * dialog's Cancel a click. In the FX turn of each answer the fence is up, the
 * surface is not fenced, and the same click fired at the main window's Play
 * button is consumed before its root sees it, so no case passes with the
 * fence down. The input is real — mouse and key events fired at the control,
 * which its window's filter sees, but for the Closing dialog's Escape, which
 * is fired at the dialog's window because a keyboard Escape does not reach it
 * yet (future story 414); {@code button.fire()} would pass any fence. Each
 * run is the exit fixture's real close on a real main window, in a folder of
 * its own, closed — its fence lowered — after it.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class CloseSurfacesTakeInputWhileTheWindowIsInertTest {

    /**
     * What the FX turn that answered a close surface saw, recorded there and
     * asserted after: a failed assertion inside the close's own turn would
     * break the protocol instead of the test.
     */
    private record Answer(String surface, boolean fenceRaised, boolean surfaceFenced, int mainWindowPresses,
                          boolean closedByItself) { }

    @FunctionalInterface
    private interface Run {
        void on(ExitFixture fixture) throws Exception;
    }

    private static final String UNSAVED_PROMPT = "the unsaved-changes prompt";
    private static final String TAKEOVER = "the takeover dialog";
    private static final String CLOSING = "the Closing dialog";
    private static final String REFUSAL = "the Save As refusal";
    private static final String SAVE_AS_PROGRESS = "the Save As progress dialog";

    @TempDir
    Path workspace;

    private final List<Answer> answers = new CopyOnWriteArrayList<>();
    private final List<Throwable> answerFailures = new CopyOnWriteArrayList<>();
    private final AtomicInteger mainWindowPresses = new AtomicInteger();

    @Test
    void theUnsavedPromptsCancelAnswersAClick() throws Exception {
        run("cancel-by-click", fixture -> {
            fixture.edit("Bass");
            fixture.save.answersByInput = byInput(fixture, ExitFixture.UNSAVED_TITLE, UNSAVED_PROMPT,
                    prompt -> WindowInputFenceTest.click(buttonOf(prompt, "Cancel")));

            fixture.closeBox();
            fixture.await(() -> fixture.steps.size() == 2, "GUARD's end");

            assertTheSurfaceTookTheInput(UNSAVED_PROMPT, true);
            assertThat(fixture.stepNames()).containsExactly("INTERCEPT DONE", "GUARD CANCELLED");
        });
    }

    @Test
    void theUnsavedPromptsSaveAndCloseAnswersEnter() throws Exception {
        run("save-and-close-by-enter", fixture -> {
            fixture.edit("Bass");
            fixture.save.answersByInput = byInput(fixture, ExitFixture.UNSAVED_TITLE, UNSAVED_PROMPT,
                    CloseSurfacesTakeInputWhileTheWindowIsInertTest::enter);

            fixture.closeBox();
            fixture.await(() -> !answers.isEmpty(), "the prompt's answer");
            assertTheSurfaceTookTheInput(UNSAVED_PROMPT, true);
            fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

            assertThat(Files.readString(fixture.songA.resolve("project.daw"), StandardCharsets.UTF_8))
                    .as("Enter on the focused Save and close saved the edit").contains("Bass");
        });
    }

    /**
     * The takeover dialog of an exit whose save met a stolen lock (ruling R7,
     * the StolenLockAtExitTest rig): Enter on Save As…, its default button
     * with the focus, reaches the Save As target chooser — cancelled here, so
     * the close ends — and, in a second run, Escape is its Cancel.
     */
    @Test
    void theTakeoverDialogsSaveAsAnswersEnterAndItsCancelAnswersEscape() throws Exception {
        run("takeover-save-as-by-enter", fixture -> {
            aTakeSavedThenEditedUnderAStolenLock(fixture);
            fixture.save.promptAnswer = "Save and close";
            fixture.save.answersByInput = byInput(fixture, SaveFlowFixture.TAKEOVER_TITLE, TAKEOVER,
                    CloseSurfacesTakeInputWhileTheWindowIsInertTest::enter);

            fixture.closeBox();
            fixture.await(() -> fixture.steps.size() == 3, "FLUSH's end");

            assertTheSurfaceTookTheInput(TAKEOVER, true);
            assertThat(fixture.save.saveAsTargets.calls()).as("Enter on Save As… reached its target chooser")
                    .hasSize(1);
            assertThat(fixture.stepNames()).containsExactly("INTERCEPT DONE", "GUARD DONE", "FLUSH CANCELLED");
        });
        run("takeover-cancel-by-escape", fixture -> {
            aTakeSavedThenEditedUnderAStolenLock(fixture);
            fixture.save.promptAnswer = "Save and close";
            fixture.save.answersByInput = byInput(fixture, SaveFlowFixture.TAKEOVER_TITLE, TAKEOVER,
                    dialog -> WindowInputFenceTest.type(focusedOrRoot(dialog), KeyCode.ESCAPE));

            fixture.closeBox();
            fixture.await(() -> fixture.steps.size() == 3, "FLUSH's end");

            assertTheSurfaceTookTheInput(TAKEOVER, true);
            assertThat(fixture.save.saveAsTargets.calls()).as("Escape is the Cancel: no Save As").isEmpty();
            assertThat(fixture.stepNames()).containsExactly("INTERCEPT DONE", "GUARD DONE", "FLUSH CANCELLED");
            assertThat(onFx(() -> fixture.stage().isShowing())).as("the project stays open").isTrue();
        });
    }

    /**
     * The Closing dialog over a held save (the HungSaveEscapeTest rig), past
     * its ten seconds so Quit anyway is offered: a click on Don't quit keeps
     * the project open; so does Escape pressed at its window while Don't
     * quit shows; and a click on Quit anyway quits.
     */
    @Test
    void theClosingDialogsDontQuitAndQuitAnywayAnswerClicksAndEscapeKeeps() throws Exception {
        run("dont-quit-by-click", fixture -> {
            SaveFlowFixture.Publishes.Hold head = aClosingDialogOverAHeldSave(fixture);
            ClosingProgressDialog surface = fixture.surface();

            runOnFx(() -> answerNow(fixture, CLOSING, windowOf(surface),
                    () -> WindowInputFenceTest.click(surface.keepButton())));

            assertTheSurfaceTookTheInput(CLOSING, true);
            assertThat(fixture.stepNames()).containsExactly("INTERCEPT DONE", "GUARD CANCELLED");
            assertThat(fixture.terminalExits).hasValue(0);
            head.release();
            fixture.save.settle();
        });
        run("escape-keeps", fixture -> {
            SaveFlowFixture.Publishes.Hold head = aClosingDialogOverAHeldSave(fixture);
            ClosingProgressDialog surface = fixture.surface();
            assertThat(onFx(() -> surface.keepButton().isVisible())).as("fixture: Don't quit shows").isTrue();

            // Escape at the dialog's window, as ClosingProgressDialogTest presses it: the window's filters see it, the
            // fence's among them, and its stage's own Escape handler answers it.
            runOnFx(() -> answerNow(fixture, CLOSING, windowOf(surface), () -> {
                Node pane = surface.getDialogPane();
                windowOf(surface).fireEvent(new KeyEvent(pane, pane, KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE,
                        false, false, false, false));
            }));

            assertTheSurfaceTookTheInput(CLOSING, true);
            assertThat(fixture.stepNames()).as("Escape is Don't quit")
                    .containsExactly("INTERCEPT DONE", "GUARD CANCELLED");
            assertThat(fixture.terminalExits).hasValue(0);
            head.release();
            fixture.save.settle();
        });
        run("quit-anyway-by-click", fixture -> {
            SaveFlowFixture.Publishes.Hold head = aClosingDialogOverAHeldSave(fixture);
            ClosingProgressDialog surface = fixture.surface();
            assertThat(onFx(() -> surface.offerButton().isVisible())).as("fixture: Quit anyway is offered").isTrue();

            runOnFx(() -> answerNow(fixture, CLOSING, windowOf(surface),
                    () -> WindowInputFenceTest.click(surface.offerButton())));

            // Quit anyway leaves the surface up through DISPOSE ("Stopping audio…"), so it does not close itself.
            assertTheSurfaceTookTheInput(CLOSING, false);
            fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
            assertThat(fixture.stepNames()).containsExactly("INTERCEPT DONE", "GUARD SKIPPED", "FLUSH SKIPPED",
                    "CLOSING_CHECKPOINT SKIPPED", "SEAL SKIPPED", "RELEASE SKIPPED", "DISPOSE DONE", "HIDE DONE");
            assertThat(fixture.terminalExits).hasValue(1);
            head.release();
            fixture.save.settle();
        });
    }

    /**
     * The takeover's Save As (ruling R7) asked for a target inside the
     * project's folder first: Enter on the refusal's OK, and the chooser
     * asks again; then, with the copy held at its take, a click on the
     * progress dialog's Cancel ends the Save As and the close.
     */
    @Test
    void theSaveAsProgressCancelAnswersAClickAndTheRefusalsOkAnswersEnter() throws Exception {
        run("save-as-refusal-and-cancel", fixture -> {
            aTakeSavedThenEditedUnderAStolenLock(fixture);
            Path target = fixture.songA.resolveSibling("Song A taken over");
            fixture.save.promptAnswer = "Save and close";
            fixture.save.takeoverAnswer = "Save As…";
            fixture.save.saveAsTargets.choose(fixture.songA.resolve("Inner")).choose(target);
            SaveFlowFixture.Publishes.Hold take = fixture.save.publishes.holdNext(
                    path -> path.getFileName().toString().equals("take.wav")
                            && path.toString().contains(".saving-as.tmp"));
            fixture.save.answersByInput = byInput(fixture, SaveFlowFixture.SAVE_AS_TITLE, REFUSAL,
                    CloseSurfacesTakeInputWhileTheWindowIsInertTest::enter);

            fixture.closeBox();
            fixture.await(() -> fixture.save.saveAsTargets.calls().size() == 2 || fixture.steps.size() == 3,
                    "the chooser asked again, or FLUSH ended");
            assertThat(fixture.save.saveAsTargets.calls())
                    .as("the close's own Save As asked again after its refused target, its close running").hasSize(2);
            take.awaitHeld();
            fixture.save.pulse();
            SaveAsProgressDialog progress = onFx(() -> fixture.save.saveAsFlow().progressDialog());
            assertThat(onFx(progress::isShowing)).as("fixture: the copy's progress shows").isTrue();
            assertThat(onFx(() -> progress.cancelButton().isDisabled())).as("fixture: Cancel can cancel").isFalse();

            runOnFx(() -> answerNow(fixture, SAVE_AS_PROGRESS, windowOf(progress),
                    () -> WindowInputFenceTest.click(progress.cancelButton())));
            take.release();
            fixture.await(() -> fixture.steps.size() == 3, "FLUSH's end");

            assertTheSurfaceTookTheInput(REFUSAL, true);
            assertTheSurfaceTookTheInput(SAVE_AS_PROGRESS, true);
            assertThat(fixture.save.saveAsTargets.calls()).as("the chooser asked again after the refusal").hasSize(2);
            assertThat(fixture.stepNames()).containsExactly("INTERCEPT DONE", "GUARD DONE", "FLUSH CANCELLED");
            assertThat(target).as("the cancelled copy left nothing").doesNotExist();
        });
    }

    // ── the runs ─────────────────────────────────────────────────────────────

    /**
     * Runs {@code body} over an exit fixture in a folder of its own, whose
     * main window's root counts the presses that reach it — one live click
     * first, so the count is not vacuous — and closes the fixture after.
     */
    private void run(String name, Run body) throws Exception {
        answers.clear();
        answerFailures.clear();
        // try-with-resources: a failure of the close is suppressed by the run's own, never in its place.
        try (ExitFixture fixture = new ExitFixture(Files.createDirectories(workspace.resolve(name)),
                AutoSaveConfig.DEFAULT)) {
            runOnFx(() -> fixture.stage().getScene().getRoot().addEventFilter(MouseEvent.MOUSE_PRESSED,
                    _ -> mainWindowPresses.incrementAndGet()));
            int before = mainWindowPresses.get();
            runOnFx(() -> WindowInputFenceTest.click(play(fixture)));
            assertThat(mainWindowPresses.get() - before).as("fixture: a click at the live main window reaches its root")
                    .isEqualTo(1);
            body.on(fixture);
            assertThat(answerFailures).as("every answer's own turn ran").isEmpty();
        }
    }

    /** Song A with a take saved, then edited, then its lock taken by another session (the StolenLockAtExitTest rig). */
    private static void aTakeSavedThenEditedUnderAStolenLock(ExitFixture fixture) throws Exception {
        Path take = fixture.songA.resolve("audio").resolve("take.wav");
        Files.write(take, new byte[1024]);
        runOnFx(() -> fixture.save.project.get().getTracks().getFirst()
                .addClip(new AudioClip("take", 0, 1, take.toString())));
        fixture.save.save();
        fixture.save.settle();
        fixture.edit("Bass");
        new ProjectLockManager(InstantSource.system(), "alice", "Studio-PC", 42L).forceAcquire(fixture.songA);
    }

    /**
     * An exit waiting on a held save, its Closing dialog shown and past its
     * ten seconds (the HungSaveEscapeTest rig); returns the hold.
     */
    private static SaveFlowFixture.Publishes.Hold aClosingDialogOverAHeldSave(ExitFixture fixture) throws Exception {
        fixture.edit("Bass");
        SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.save.save();
        head.awaitHeld();
        fixture.closeBox();
        fixture.await(() -> !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(), "the wait clock");
        fixture.fire(ExitCoordinator.SURFACE_DELAY);
        fixture.fire(ExitCoordinator.NOTE_DELAY);
        assertThat(onFx(() -> fixture.surface().isOpen())).as("fixture: the Closing dialog shows").isTrue();
        return head;
    }

    // ── the answers ──────────────────────────────────────────────────────────

    /**
     * The fixture's seam: the window titled {@code title} is answered by
     * {@code input}, not by the fixture — for the Save As title only the
     * refusal, which shows a message: the progress dialog shares the title.
     */
    private Predicate<Stage> byInput(ExitFixture fixture, String title, String surface, Consumer<Stage> input) {
        return stage -> {
            if (!title.equals(stage.getTitle())) {
                return false;
            }
            if (SaveFlowFixture.SAVE_AS_TITLE.equals(title)
                    && stage.getScene().getRoot().lookup(".dawg-dialog-message") == null) {
                return false;
            }
            answerByInput(fixture, surface, stage, input, 0);
            return true;
        };
    }

    /**
     * Answers the prompt {@code dialog}, waiting in its nested loop: once its
     * scene has a focus owner — a few FX turns, bounded, the
     * {@code ExitFixture.answerStopTake} idiom — as {@link #answerNow} does;
     * then hides it if the input did not close it, a hang guard only. FX
     * thread.
     */
    private void answerByInput(ExitFixture fixture, String surface, Stage dialog, Consumer<Stage> input, int turns) {
        if (dialog.getScene().getFocusOwner() == null && turns < 50) {
            Platform.runLater(() -> answerByInput(fixture, surface, dialog, input, turns + 1));
            return;
        }
        answerNow(fixture, surface, dialog, () -> input.accept(dialog));
        if (dialog.isShowing()) {
            dialog.hide();
        }
    }

    /**
     * Records what this FX turn sees — whether the fence is up, whether
     * {@code window} is fenced, and whether a click at the main window's
     * Play button reaches its root — then answers by {@code input}, and
     * records whether that closed {@code window}. FX thread.
     */
    private void answerNow(ExitFixture fixture, String surface, Stage window, Runnable input) {
        try {
            boolean raised = fixture.fence().isRaised();
            boolean fenced = fixture.fence().fencedWindows().contains(window);
            int before = mainWindowPresses.get();
            WindowInputFenceTest.click(play(fixture));
            int presses = mainWindowPresses.get() - before;
            input.run();
            answers.add(new Answer(surface, raised, fenced, presses, !window.isShowing()));
        } catch (RuntimeException | AssertionError failed) {
            answerFailures.add(failed);
        }
    }

    private void assertTheSurfaceTookTheInput(String surface, boolean closesItself) {
        assertThat(answers).as("%s was answered by input once", surface)
                .filteredOn(answer -> answer.surface().equals(surface)).singleElement().satisfies(answer -> {
                    assertThat(answer.fenceRaised()).as("the window was inert while %s asked", surface).isTrue();
                    assertThat(answer.surfaceFenced()).as("%s is not fenced", surface).isFalse();
                    assertThat(answer.mainWindowPresses()).as("the same click at the main window was consumed")
                            .isZero();
                    assertThat(answer.closedByItself()).as("%s took the input and closed", surface)
                            .isEqualTo(closesItself);
                });
    }

    /** Enter pressed and released at {@code dialog}'s focus owner. FX thread. */
    private static void enter(Stage dialog) {
        WindowInputFenceTest.type(focusedOrRoot(dialog), KeyCode.ENTER);
    }

    private static Node focusedOrRoot(Stage dialog) {
        Node focused = dialog.getScene().getFocusOwner();
        return focused != null ? focused : dialog.getScene().getRoot();
    }

    /** The button of {@code dialog}'s pane labelled {@code text}. FX thread. */
    private static Button buttonOf(Stage dialog, String text) {
        DialogPane pane = (DialogPane) dialog.getScene().getRoot().lookup(".dialog-pane");
        for (ButtonType type : pane.getButtonTypes()) {
            if (type.getText().equals(text) && pane.lookupButton(type) instanceof Button button) {
                return button;
            }
        }
        throw new AssertionError("no " + text + " button on " + dialog.getTitle());
    }

    /** The main window's Play button: the control the click at the fenced window is fired at. FX thread. */
    private static Button play(ExitFixture fixture) {
        return (Button) fixture.stage().getScene().getRoot().lookup(".button");
    }

    private static Stage windowOf(javafx.scene.control.Dialog<?> dialog) {
        return (Stage) dialog.getDialogPane().getScene().getWindow();
    }
}
