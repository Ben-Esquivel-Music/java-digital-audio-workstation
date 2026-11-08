package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.ProjectLifecycleController.CloseReadiness;
import com.benesquivelmusic.daw.app.ui.ProjectLifecycleController.PendingSwitch;
import com.benesquivelmusic.daw.app.ui.ProjectLifecycleController.UnsavedChangesAnswer;
import com.benesquivelmusic.daw.app.ui.ProjectLifecycleController.UnsavedChangesPrompt;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectLock;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.archive.ProjectArchiver;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, ruling R2 — the unsaved-changes prompt of every close: its
 * header names the project, or says it is open read-only; its content says
 * what Discard leaves — the last save's time ({@code HH:mm} today,
 * {@code yyyy-MM-dd HH:mm} on another day, ruling K1/L1), a folder left as it
 * was created, a project never saved, a read-only folder Save As leaves as it
 * is — after the lines that come before it, in the engineer's order (ENG-N4):
 * the standing head failure, then the take just recorded, which is shown only
 * when the close included a take that put a clip in the project. The buttons
 * are {@code Save and close} (with an ellipsis when a chooser follows),
 * {@code Discard and close} and {@code Cancel}; shown, Save is the default
 * button (Enter), Discard stands apart on the left and is never the default
 * nor the cancel button, Cancel is the cancel button (Escape and the close
 * box), and the main window owns the prompt. It is the lifecycle
 * controller's one unsaved-changes Alert, whose door prompt is unchanged.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ProjectClosePromptsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);
    private static final Instant SAVED_TODAY = Instant.parse("2026-10-28T14:32:10Z");
    private static final Instant SAVED_BEFORE = Instant.parse("2026-10-22T18:10:00Z");

    private ProjectLifecycleController lifecycle;
    private Stage mainWindow;
    private ProjectClosePrompts prompts;
    /** An {@link #answer}: Enter, pressed and released on the prompt's focus owner. */
    private static final String ENTER = "<Enter>";
    /** An {@link #answer}: Escape, pressed on the prompt's focus owner. */
    private static final String ESCAPE = "<Escape>";

    /**
     * The answer the next shown prompt gets: a button's text, {@link #ENTER},
     * {@link #ESCAPE}, or {@code null} to close it with its close box.
     */
    private volatile String answer;
    private final AtomicReference<DialogPane> shown = new AtomicReference<>();
    private final AtomicReference<Window> shownOwner = new AtomicReference<>();
    /** The prompt's focus owner when it was answered. */
    private final AtomicReference<Node> focusOwner = new AtomicReference<>();
    /** Whether the last answer by key or close box closed the prompt by itself — never the test's hang guard. */
    private final AtomicBoolean closedByItself = new AtomicBoolean();

    private final ListChangeListener<Window> answering = change -> {
        while (change.next()) {
            for (Window window : change.getAddedSubList()) {
                if (window instanceof Stage stage && ("Unsaved Changes".equals(stage.getTitle())
                        || "Project Taken Over".equals(stage.getTitle()) || "Save Project As".equals(stage.getTitle()))
                        && stage.getScene().getRoot().lookup(".dialog-pane") instanceof DialogPane pane) {
                    shown.set(pane);
                    shownOwner.set(stage.getOwner());
                    Platform.runLater(() -> answer(stage, pane));
                }
            }
        }
    };

    @BeforeEach
    void build() throws Exception {
        runOnFx(() -> {
            DawProject project = new DawProject("Song A", AudioFormat.STUDIO_QUALITY);
            AtomicReference<DawProject> live = new AtomicReference<>(project);
            AtomicReference<UndoManager> undo = new AtomicReference<>(new UndoManager());
            lifecycle = new ProjectLifecycleController(new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT)),
                    new SessionInterchangeController(), new NotificationBar(),
                    new ProjectOperationProgress(new FxDispatcher()), new BorderPane(), new VBox(),
                    new ProjectLifecycleController.Deps(live::get, live::set, undo::get, undo::set, () -> { },
                            () -> { }, () -> { }, () -> null, _ -> { }, () -> { }),
                    new ProjectArchiver(), new FxDispatcher());
            mainWindow = new Stage();
            mainWindow.setScene(new Scene(new StackPane(), 400, 300));
            mainWindow.show();
            prompts = new ProjectClosePrompts(lifecycle, () -> Optional.of(mainWindow), ZoneOffset.UTC);
            Window.getWindows().addListener(answering);
        });
    }

    @AfterEach
    void close() throws Exception {
        runOnFx(() -> {
            Window.getWindows().removeListener(answering);
            mainWindow.hide();
        });
    }

    @Test
    void theContentSaysWhatDiscardLeaves() {
        assertThat(prompts.unsavedChanges(readiness(true, false, Optional.of(SAVED_TODAY), Optional.empty()), false)
                .content()).as("saved today").isEqualTo("If you discard them, “Song A” keeps its last save from 14:32.");
        assertThat(prompts.unsavedChanges(readiness(true, false, Optional.of(SAVED_BEFORE), Optional.empty()), false)
                .content()).as("saved on another day")
                .isEqualTo("If you discard them, “Song A” keeps its last save from 2026-10-22 18:10.");
        assertThat(prompts.unsavedChanges(readiness(true, false, Optional.empty(), Optional.empty()), false)
                .content()).as("a folder whose head no save wrote")
                .isEqualTo("If you discard them, “Song A” is left empty, as it was created.");
        assertThat(prompts.unsavedChanges(readiness(false, false, Optional.empty(), Optional.empty()), false)
                .content()).as("no folder")
                .isEqualTo("This project has never been saved, so its changes are gone if you discard them.");
        assertThat(prompts.unsavedChanges(readiness(true, true, Optional.of(SAVED_TODAY), Optional.empty()), false)
                .content()).as("read-only")
                .isEqualTo("Another session holds this project's lock, so its folder can't be written. Save and"
                        + " close… saves your changes to a new project folder and leaves the original as it is.");
    }

    @Test
    void theHeaderNamesTheProjectOrSaysItIsReadOnly() {
        assertThat(prompts.unsavedChanges(readiness(true, false, Optional.of(SAVED_TODAY), Optional.empty()), false)
                .header()).isEqualTo("Save the changes to “Song A” before closing?");
        assertThat(prompts.unsavedChanges(readiness(true, true, Optional.of(SAVED_TODAY), Optional.empty()), false)
                .header()).isEqualTo("“Song A” is open read-only. Save your changes as a new project before closing?");
    }

    @Test
    void theLinesBeforeTheContentAreTheLastSavesFailureThenTheTake() {
        UnsavedChangesPrompt both = prompts.unsavedChanges(readiness(true, false, Optional.of(SAVED_TODAY),
                Optional.of(new IOException("Disk full"))), true);
        UnsavedChangesPrompt failureOnly = prompts.unsavedChanges(readiness(true, false, Optional.of(SAVED_TODAY),
                Optional.of(new IOException())), false);

        assertThat(both.content().split("\n")).containsExactly(
                "The last save failed (Disk full).",
                "Your changes include the take you just recorded.",
                "If you discard them, “Song A” keeps its last save from 14:32.");
        assertThat(failureOnly.content().split("\n")).as("the cause as the ERROR names it, never \"null\"")
                .containsExactly("The last save failed (IOException).",
                        "If you discard them, “Song A” keeps its last save from 14:32.");
    }

    @Test
    void theLabelsAreRuledAndAChooserFollowsSaveWithNoFolderOrReadOnly() {
        UnsavedChangesPrompt saved = prompts.unsavedChanges(readiness(true, false, Optional.of(SAVED_TODAY),
                Optional.empty()), false);
        assertThat(List.of(saved.saveLabel(), saved.discardLabel(), saved.cancelLabel().orElseThrow()))
                .containsExactly("Save and close", "Discard and close", "Cancel");
        assertThat(saved.discardApart()).isTrue();
        assertThat(saved.owner()).contains(mainWindow);
        assertThat(prompts.unsavedChanges(readiness(false, false, Optional.empty(), Optional.empty()), false)
                .saveLabel()).as("no folder: the location chooser follows").isEqualTo("Save and close…");
        assertThat(prompts.unsavedChanges(readiness(true, true, Optional.empty(), Optional.empty()), false)
                .saveLabel()).as("read-only: the Save As chooser follows").isEqualTo("Save and close…");
    }

    @Test
    void shownSaveIsTheDefaultDiscardStandsApartCancelIsEscapeAndTheMainWindowOwnsIt() throws Exception {
        UnsavedChangesPrompt prompt = prompts.unsavedChanges(readiness(true, false, Optional.of(SAVED_TODAY),
                Optional.empty()), false);

        answer = "Save and close";
        assertThat(onFx(() -> prompts.askBeforeClosing(prompt))).isEqualTo(UnsavedChangesAnswer.SAVE);
        DialogPane pane = shown.get();
        assertThat(pane.getHeaderText()).isEqualTo("Save the changes to “Song A” before closing?");
        assertThat(pane.getContentText()).isEqualTo("If you discard them, “Song A” keeps its last save from 14:32.");
        assertThat(pane.getButtonTypes()).extracting(ButtonType::getText)
                .containsExactly("Save and close", "Discard and close", "Cancel");
        assertThat(pane.getButtonTypes()).extracting(ButtonType::getButtonData)
                .containsExactly(ButtonBar.ButtonData.OK_DONE, ButtonBar.ButtonData.LEFT,
                        ButtonBar.ButtonData.CANCEL_CLOSE);
        Button save = (Button) pane.lookupButton(pane.getButtonTypes().get(0));
        Button discard = (Button) pane.lookupButton(pane.getButtonTypes().get(1));
        Button cancel = (Button) pane.lookupButton(pane.getButtonTypes().get(2));
        assertThat(save.isDefaultButton()).as("Enter saves").isTrue();
        assertThat(discard.isDefaultButton()).as("Discard is never the default").isFalse();
        assertThat(discard.isCancelButton()).as("nor the cancel button").isFalse();
        assertThat(cancel.isCancelButton()).as("Escape cancels").isTrue();
        assertThat(shownOwner.get()).as("owned by the main window").isSameAs(mainWindow);

        answer = "Discard and close";
        assertThat(onFx(() -> prompts.askBeforeClosing(prompt))).isEqualTo(UnsavedChangesAnswer.DISCARD);
        answer = "Cancel";
        assertThat(onFx(() -> prompts.askBeforeClosing(prompt))).isEqualTo(UnsavedChangesAnswer.CANCEL);
        answer = null;
        assertThat(onFx(() -> prompts.askBeforeClosing(prompt))).as("the close box cancels")
                .isEqualTo(UnsavedChangesAnswer.CANCEL);
        assertThat(closedByItself).as("stage-4 review ENG-S7: the close box closed it, not the hang guard").isTrue();
    }

    /**
     * The studio engineer's stage-4 S6: on Windows Enter fires the focused
     * button, not the default one, so the safe answer must have the focus
     * when the prompt opens — Enter on it saves.
     */
    @Test
    void enterOnThePromptAsItOpensSavesBecauseSaveHasTheFocus() throws Exception {
        UnsavedChangesPrompt prompt = prompts.unsavedChanges(readiness(true, false, Optional.of(SAVED_TODAY),
                Optional.empty()), false);

        answer = ENTER;
        assertThat(onFx(() -> prompts.askBeforeClosing(prompt))).as("Enter saves").isEqualTo(UnsavedChangesAnswer.SAVE);

        DialogPane pane = shown.get();
        assertThat(focusOwner.get()).as("the focus is on Save as the prompt opens")
                .isSameAs(pane.lookupButton(pane.getButtonTypes().getFirst()));
        assertThat(closedByItself).isTrue();
    }

    /**
     * The studio engineer's stage-4 N2: a read-only folder's last "failure"
     * is the refusal its content already says, so no "The last save failed"
     * line repeats it.
     */
    @Test
    void theReadOnlyPromptDoesNotRepeatTheReadOnlyRefusalAsAFailure() {
        UnsavedChangesPrompt readOnly = prompts.unsavedChanges(readiness(true, true, Optional.of(SAVED_TODAY),
                Optional.of(new IOException("Project was opened read-only; use Save As to a new location"))), true);

        assertThat(readOnly.content().split("\n")).containsExactly(
                "Your changes include the take you just recorded.",
                "Another session holds this project's lock, so its folder can't be written. Save and close… saves"
                        + " your changes to a new project folder and leaves the original as it is.");
    }

    /**
     * The studio engineer's stage-4 S5 (pinned by source: a headless stage
     * cannot be minimised): a prompt owned by a minimised main window would
     * be hidden with it, so the close prompts restore their owner and bring it
     * to the front before they show.
     */
    @Test
    void theClosePromptsRestoreAMinimisedOwnerBeforeTheyShow() throws Exception {
        String code = FxHalfOfASaveMakesNoFileSystemCallTest.code(java.nio.file.Files.readString(
                SourceScanSupport.locateDawAppModule().resolve(
                        "src/main/java/com/benesquivelmusic/daw/app/ui/ProjectClosePrompts.java"),
                java.nio.charset.StandardCharsets.UTF_8));
        String ask = FxHalfOfASaveMakesNoFileSystemCallTest.body("ProjectClosePrompts.java", code,
                "askBeforeClosing").code().replaceAll("\\s+", " ");
        String takenOver = FxHalfOfASaveMakesNoFileSystemCallTest.body("ProjectClosePrompts.java", code,
                "takenOver").code().replaceAll("\\s+", " ");
        String bringToFront = FxHalfOfASaveMakesNoFileSystemCallTest.body("ProjectClosePrompts.java", code,
                "bringToFront").code().replaceAll("\\s+", " ");

        assertThat(ask.indexOf("prompt.owner().ifPresent(ProjectClosePrompts::bringToFront);"))
                .as("R2's prompt restores its owner first").isGreaterThanOrEqualTo(0)
                .isLessThan(ask.indexOf("lifecycle.askAboutUnsavedChanges(prompt)"));
        assertThat(takenOver.indexOf("prompt.owner().ifPresent(ProjectClosePrompts::bringToFront);"))
                .as("so does R7's").isGreaterThanOrEqualTo(0).isLessThan(takenOver.indexOf("alert.showAndWait()"));
        assertThat(bringToFront).contains("if (stage.isIconified()) { stage.setIconified(false); }")
                .contains("stage.toFront();");
    }

    /**
     * The studio engineer's stage-7 N4, beside stage-4 S5's pin: the closing
     * surface's note that asks for a decision — R8's ten-second note and
     * Quit anyway, a seal failure's — restores a minimised owner and brings
     * it to the front, as the prompts do; an empty note (DISPOSE's) restores
     * nothing. Pinned by source, a headless stage may not stay minimised.
     */
    @Test
    void theClosingSurfacesDecisionRestoresAMinimisedOwner() throws Exception {
        String code = FxHalfOfASaveMakesNoFileSystemCallTest.code(java.nio.file.Files.readString(
                SourceScanSupport.locateDawAppModule().resolve(
                        "src/main/java/com/benesquivelmusic/daw/app/ui/ClosingProgressDialog.java"),
                java.nio.charset.StandardCharsets.UTF_8));
        String note = FxHalfOfASaveMakesNoFileSystemCallTest.body("ClosingProgressDialog.java", code, "note")
                .code().replaceAll("\\s+", " ");

        // The studio engineer's app-fix-2 review N2: only a decision newly asked restores the owner.
        assertThat(note).contains("boolean newDecision = !noteText.isEmpty() && !noteText.equals(noteText());",
                "if (newDecision && isShowing() && getOwner() instanceof Stage owner) {"
                + " if (owner.isIconified()) { owner.setIconified(false); } owner.toFront(); }");
        assertThat(note.indexOf("boolean newDecision")).as("judged before the note is shown").isNotNegative()
                .isLessThan(note.indexOf("showNote("));
        assertThat(note.indexOf("showNote(")).as("after the note is shown").isNotNegative()
                .isLessThan(note.indexOf("owner.toFront();"));
    }

    @Test
    void theDoorsPromptIsAsItWas() throws Exception {
        answer = "Discard";
        assertThat(onFx(() -> lifecycle.askAboutUnsavedChanges(UnsavedChangesPrompt.DOOR)))
                .isEqualTo(UnsavedChangesAnswer.DISCARD);
        DialogPane pane = shown.get();

        assertThat(pane.getHeaderText()).isEqualTo("You have unsaved changes.");
        assertThat(pane.getContentText()).isEqualTo("Do you want to save before continuing?");
        assertThat(pane.getButtonTypes()).hasSize(3);
        assertThat(pane.getButtonTypes().subList(0, 2)).extracting(ButtonType::getText)
                .containsExactly("Save", "Discard");
        assertThat(pane.getButtonTypes().subList(0, 2)).extracting(ButtonType::getButtonData)
                .containsOnly(ButtonBar.ButtonData.OTHER);
        assertThat(pane.getButtonTypes().get(2)).as("the platform's own Cancel").isSameAs(ButtonType.CANCEL);
        assertThat(shownOwner.get()).as("the doors' prompt names no owner").isNull();
    }

    @Test
    void theTakeoverDialogOfASaveNamesTheHolderOffersSaveAsAsTheDefaultAndNotNowOnEscape() throws Exception {
        ProjectLock alice = new ProjectLock("lock-1", "alice", "Studio-PC", 42L, Instant.EPOCH, Instant.EPOCH);
        ProjectClosePrompts.TakenOverPrompt prompt = prompts.takenOverOnSave("Song A", alice);

        answer = "Save As…";
        assertThat(onFx(() -> prompts.takenOver(prompt))).isEqualTo(ProjectClosePrompts.TakenOverAnswer.SAVE_AS);
        DialogPane pane = shown.get();
        assertThat(pane.getHeaderText()).isEqualTo("“Song A” was taken over by alice on Studio-PC (process 42).");
        assertThat(pane.getContentText()).isEqualTo("Nothing was written to its folder, and your changes are still"
                + " open here. Save them as a new project to keep them; the original folder is left to the other"
                + " session.");
        assertThat(pane.getButtonTypes()).extracting(ButtonType::getText).containsExactly("Save As…", "Not now");
        Button saveAs = (Button) pane.lookupButton(pane.getButtonTypes().get(0));
        Button notNow = (Button) pane.lookupButton(pane.getButtonTypes().get(1));
        assertThat(saveAs.isDefaultButton()).as("R7: Save As… is the default").isTrue();
        assertThat(notNow.isCancelButton()).as("Not now is Escape").isTrue();
        assertThat(shownOwner.get()).isSameAs(mainWindow);

        answer = "Not now";
        assertThat(onFx(() -> prompts.takenOver(prompt))).isEqualTo(ProjectClosePrompts.TakenOverAnswer.CANCEL);
        answer = null;
        assertThat(onFx(() -> prompts.takenOver(prompt))).as("the close box is Not now")
                .isEqualTo(ProjectClosePrompts.TakenOverAnswer.CANCEL);
    }

    @Test
    void theTakeoverDialogOfACloseOffersToLeaveWithoutSavingAndSaysSoWhenTheLockFileIsGone() throws Exception {
        answer = "Quit without saving";
        assertThat(onFx(() -> prompts.takenOver(prompts.takenOverAtClose("Song A", null, false, true))))
                .isEqualTo(ProjectClosePrompts.TakenOverAnswer.WITHOUT_SAVING);
        DialogPane pane = shown.get();
        assertThat(pane.getHeaderText()).isEqualTo("The lock of “Song A” was removed, so another session may have"
                + " taken the project over.");
        assertThat(pane.getContentText()).isEqualTo("Nothing was written to its folder, and your changes are still"
                + " open here. Save them as a new project before closing, or close without them.");
        assertThat(pane.getButtonTypes()).extracting(ButtonType::getText)
                .containsExactly("Save As…", "Quit without saving", "Cancel");
        assertThat(pane.getButtonTypes()).extracting(ButtonType::getButtonData).containsExactly(
                ButtonBar.ButtonData.OK_DONE, ButtonBar.ButtonData.OTHER, ButtonBar.ButtonData.CANCEL_CLOSE);
        assertThat(((Button) pane.lookupButton(pane.getButtonTypes().get(1))).isDefaultButton()).isFalse();

        answer = "Close without saving";
        assertThat(onFx(() -> prompts.takenOver(prompts.takenOverAtClose("Song A", null, false, false))))
                .isEqualTo(ProjectClosePrompts.TakenOverAnswer.WITHOUT_SAVING);
        answer = "Cancel";
        assertThat(onFx(() -> prompts.takenOver(prompts.takenOverAtClose("Song A", null, false, false))))
                .isEqualTo(ProjectClosePrompts.TakenOverAnswer.CANCEL);
    }

    /** The studio engineer's stage-6 S1: a loss known only from the lease's status names no holder, claims no removal. */
    @Test
    void theTakeoverDialogOfALossKnownOnlyFromTheLeaseSaysTheLockIsNoLongerHeld() throws Exception {
        answer = "Cancel";
        assertThat(onFx(() -> prompts.takenOver(prompts.takenOverAtClose("Song A", null, true, true))))
                .isEqualTo(ProjectClosePrompts.TakenOverAnswer.CANCEL);

        assertThat(shown.get().getHeaderText()).isEqualTo("The lock of “Song A” is no longer held by this session,"
                + " so another session may have taken the project over.");
        assertThat(shown.get().getContentText()).isEqualTo("Nothing was written to its folder, and your changes are"
                + " still open here. Save them as a new project before closing, or close without them.");
    }

    @Test
    void aRefusedSaveAsTargetIsAModalWarningOwnedByTheMainWindowWithOneOk() throws Exception {
        answer = "OK";
        runOnFx(() -> prompts.refuseSaveAsTarget("Save As can't save into the project's own folder. Choose a"
                + " location outside “Song A”."));

        DialogPane pane = shown.get();
        assertThat(pane.getButtonTypes()).extracting(ButtonType::getText).containsExactly("OK");
        assertThat(((Button) pane.lookupButton(pane.getButtonTypes().getFirst())).isDefaultButton()).isTrue();
        assertThat(pane.lookup(".dawg-dialog-message")).isInstanceOfSatisfying(javafx.scene.control.Label.class,
                label -> assertThat(label.getText()).isEqualTo("Save As can't save into the project's own folder."
                        + " Choose a location outside “Song A”."));
        assertThat(shownOwner.get()).isSameAs(mainWindow);
        answer = null;
        runOnFx(() -> prompts.refuseSaveAsTarget("closed by its close box"));
        assertThat(closedByItself).as("the close glyph closes it").isTrue();
        answer = ESCAPE;
        runOnFx(() -> prompts.refuseSaveAsTarget("closed by Escape"));
        assertThat(closedByItself).as("stage-5 review N11: Escape closes the one-button refusal").isTrue();
        answer = ENTER;
        runOnFx(() -> prompts.refuseSaveAsTarget("closed by Enter"));
        assertThat(closedByItself).as("and Enter presses its OK").isTrue();
    }

    private CloseReadiness readiness(boolean hasFolder, boolean readOnly, Optional<Instant> savedAt,
                                     Optional<Throwable> failure) {
        return new CloseReadiness("Song A", true, hasFolder, readOnly, savedAt, failure, false, Optional.empty(),
                new PendingSwitch.None(), TODAY);
    }

    /**
     * Presses the button the test chose, presses Enter or Escape on the focus
     * owner, or closes the prompt with its close box. FX thread. The focus is
     * set once the prompt's buttons were laid out: while it has none yet, the
     * answer waits a few FX turns, bounded.
     */
    private void answer(Stage stage, DialogPane pane) {
        answer(stage, pane, 0);
    }

    private void answer(Stage stage, DialogPane pane, int turns) {
        Node focused = stage.getScene().getFocusOwner();
        if (focused == null && turns < 50) {
            Platform.runLater(() -> answer(stage, pane, turns + 1));
            return;
        }
        focusOwner.set(focused);
        closedByItself.set(false);
        String text = answer;
        if (ENTER.equals(text) || ESCAPE.equals(text)) {
            KeyCode key = ENTER.equals(text) ? KeyCode.ENTER : KeyCode.ESCAPE;
            Node target = focused != null ? focused : stage.getScene().getRoot();
            target.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", key, false, false, false, false));
            target.fireEvent(new KeyEvent(KeyEvent.KEY_RELEASED, "", "", key, false, false, false, false));
            closedByItself.set(!stage.isShowing());
            hideIfStillShowing(stage);
            return;
        }
        if (text != null) {
            for (ButtonType type : pane.getButtonTypes()) {
                if (type.getText().equals(text) && pane.lookupButton(type) instanceof Button button) {
                    button.fire();
                    return;
                }
            }
        }
        // The window's close box: a close request, which the Alert answers with its cancel button.
        stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
        closedByItself.set(!stage.isShowing());
        hideIfStillShowing(stage);
    }

    /** A hang guard only: a prompt that refused to close must not leave its nested loop running. */
    private static void hideIfStillShowing(Stage stage) {
        if (stage.isShowing()) {
            stage.hide();
        }
    }
}
