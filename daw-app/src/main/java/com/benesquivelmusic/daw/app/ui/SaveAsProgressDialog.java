package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.dialogs.DawgDialog;
import com.benesquivelmusic.daw.app.ui.dialogs.DialogDismissibility;

import javafx.scene.control.Button;
import javafx.scene.control.DialogEvent;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * The modal progress of a Save As (story 333; the studio engineer's rulings
 * R5 and S6, Failure Surfacing book §2.4, §5.2): titled "Save Project As",
 * a determinate bar and one status line — {@code Checking what to copy into
 * “<new name>”…} while the plan runs, the copy's {@code Copying audio to
 * “<new name>”… <x> of <y>}, {@code Writing project.daw…}, then
 * {@code Closing “<project name>”…} and {@code Opening “<new name>”…} — and a
 * Cancel; once the original folder's closing steps have taken ten seconds,
 * a note in the warning colour and {@code Continue}, never the default
 * button. Application-modal and owned by the main window, so the capture is
 * the project's final state; it is shown, never waited on.
 *
 * <p>Its body is a plain pane inside the {@link DawgDialog} (javafx skill §3,
 * "Custom controls: the Control + Skin pattern": a one-off dialog body is
 * not a Control), styled by role classes over the theme's tokens — no
 * inline style. The buttons belong to the content; a hidden cancel button
 * ({@link DialogDismissibility#installHiddenCancel}) keeps Escape and the
 * window's close box live, and both reach one close-request guard: they
 * cancel while Cancel is enabled and do nothing otherwise — from
 * {@code project.daw}'s write on, when the copy can no longer be cancelled,
 * the dialog cannot be dismissed (Failure Surfacing book §5.2, a temporary
 * veto). The flow closes it itself ({@link #closeByFlow()}), which runs no
 * callback. No animation: the bar is determinate and the step line static
 * (javafx skill §10).</p>
 */
final class SaveAsProgressDialog extends DawgDialog<Void> {

    private final Label status = new Label();
    private final ProgressBar bar = new ProgressBar(0);
    private final Label note = new Label();
    private final Button continueButton = new Button(ProjectCloseText.text("saveAs.continue"));
    private final Button cancelButton = new Button(ProjectCloseText.text("saveAs.cancel"));
    private boolean closingByFlow;
    private boolean cancelEnabled = true;
    private BooleanSupplier onCancel;
    private Runnable onContinue;
    private Window guardedWindow;

    /**
     * Creates the dialog, owned by {@code owner} when there is one.
     *
     * @param owner the main window
     */
    SaveAsProgressDialog(Optional<Window> owner) {
        Objects.requireNonNull(owner, "owner must not be null");
        setTitle(ProjectCloseText.text("saveAs.title"));
        setHeaderText(ProjectCloseText.text("saveAs.title"));
        initModality(Modality.APPLICATION_MODAL);
        owner.ifPresent(this::initOwner);

        status.getStyleClass().add("save-as-progress-status");
        bar.getStyleClass().add("save-as-progress-bar");
        bar.setMaxWidth(Double.MAX_VALUE);
        note.getStyleClass().add("save-as-progress-note");
        note.setWrapText(true);
        showNote(false);
        continueButton.getStyleClass().addAll("dawg-button", "size-default", "secondary");
        continueButton.setDefaultButton(false);
        continueButton.setOnAction(_ -> continuePressed());
        cancelButton.getStyleClass().addAll("dawg-button", "size-default", "secondary");
        cancelButton.setOnAction(_ -> cancelPressed());
        HBox actions = new HBox(continueButton, cancelButton);
        actions.getStyleClass().add("save-as-progress-actions");
        HBox.setHgrow(continueButton, Priority.NEVER);
        VBox body = new VBox(status, bar, note, actions);
        body.getStyleClass().add("save-as-progress-body");
        getDialogPane().setContent(body);

        DialogDismissibility.installHiddenCancel(this);
        setResultConverter(_ -> null);
        setOnCloseRequest(this::closeRequested);
        setOnShown(_ -> {
            guardTheWindowsCloseBox();
            cancelButton.requestFocus();
        });
    }

    /**
     * The window's close box reaches the dialog's own stage first, which
     * hides itself on a close request nobody consumed — whatever the dialog's
     * close request answered. The dialog's stage therefore consumes every
     * close request and treats it as a Cancel (the FirstRunWizardHost
     * idiom). The filter lives on the dialog's own stage, so it ends with it.
     */
    private void guardTheWindowsCloseBox() {
        Window window = getDialogPane().getScene() == null ? null : getDialogPane().getScene().getWindow();
        if (window == null || window == guardedWindow) {
            return;
        }
        guardedWindow = window;
        window.addEventFilter(WindowEvent.WINDOW_CLOSE_REQUEST, event -> {
            if (closingByFlow) {
                return;
            }
            event.consume();
            cancelPressed();
        });
    }

    /**
     * What Cancel, Escape and the close box run while Cancel is enabled: it
     * answers whether the cancel was taken — the dialog then closes — or
     * came too late, once the copy could no longer be cancelled — the dialog
     * stays and Cancel is disabled. FX thread.
     *
     * @param action the cancel, answering whether it was taken
     */
    void onCancel(BooleanSupplier action) {
        this.onCancel = Objects.requireNonNull(action, "action must not be null");
    }

    /** What {@code Continue} runs; once. FX thread. */
    void onContinue(Runnable action) {
        this.onContinue = Objects.requireNonNull(action, "action must not be null");
    }

    /**
     * The plan runs, before the first byte is copied: its status line, the
     * bar at 0 — determinate, no animation — and Cancel as it was (enabled).
     * FX thread.
     */
    void showPlanning(String statusLine) {
        status.setText(Objects.requireNonNull(statusLine, "statusLine must not be null"));
        bar.setProgress(0.0);
    }

    /** The copy's progress: its status line and how far it is, from 0 to 1. FX thread. */
    void showCopying(String statusLine, double fraction) {
        status.setText(Objects.requireNonNull(statusLine, "statusLine must not be null"));
        bar.setProgress(Math.max(0.0, Math.min(1.0, fraction)));
    }

    /**
     * {@code project.daw} is being written: from now on the copy cannot be
     * cancelled — Cancel is disabled, and Escape and the close box do
     * nothing. FX thread.
     */
    void showWritingHead(String statusLine) {
        showPastTheCopy(statusLine);
    }

    /**
     * A step after the copy was published — the original folder being
     * closed, the copy being opened: its status line, the bar full, and, as
     * from {@code project.daw}'s write on, no Cancel — Escape and the close
     * box do nothing. FX thread.
     */
    void showPastTheCopy(String statusLine) {
        status.setText(Objects.requireNonNull(statusLine, "statusLine must not be null"));
        bar.setProgress(1.0);
        cancelEnabled = false;
        cancelButton.setDisable(true);
    }

    /** The original folder is still being closed after ten seconds: the note and {@code Continue}. FX thread. */
    void showOldStillClosing(String noteText) {
        note.setText(Objects.requireNonNull(noteText, "noteText must not be null"));
        showNote(true);
    }

    /** Closes the dialog for the flow: runs no callback, and is never vetoed. FX thread. */
    void closeByFlow() {
        closingByFlow = true;
        onCancel = null;
        onContinue = null;
        if (isShowing()) {
            close();
        }
    }

    /** The status line shown. */
    String statusText() {
        return status.getText();
    }

    /** The note shown, empty while none is. */
    String noteText() {
        return note.isVisible() ? note.getText() : "";
    }

    /** The bar's progress, from 0 to 1. */
    double progress() {
        return bar.getProgress();
    }

    /** The content's Cancel button. */
    Button cancelButton() {
        return cancelButton;
    }

    /** The content's Continue button. */
    Button continueButton() {
        return continueButton;
    }

    private void showNote(boolean shown) {
        note.setVisible(shown);
        note.setManaged(shown);
        continueButton.setVisible(shown);
        continueButton.setManaged(shown);
    }

    /**
     * A Cancel while Cancel is enabled: the flow is asked; taken, the dialog
     * closes; too late, it stays with Cancel disabled.
     *
     * @return whether the dialog closes
     */
    private boolean cancelPressed() {
        if (!cancelEnabled || closingByFlow) {
            return false;
        }
        BooleanSupplier cancel = onCancel;
        if (cancel != null && !cancel.getAsBoolean()) {
            cancelEnabled = false;
            cancelButton.setDisable(true);
            return false;
        }
        closingByFlow = true;
        onCancel = null;
        onContinue = null;
        if (isShowing()) {
            close();
        }
        return true;
    }

    private void continuePressed() {
        Runnable proceed = onContinue;
        if (proceed == null) {
            return;
        }
        onContinue = null;
        continueButton.setDisable(true);
        proceed.run();
    }

    /**
     * Escape, the window's close box, the hidden cancel button and a
     * programmatic close all reach the dialog's close request: the flow's own
     * close goes on; otherwise it is a Cancel while Cancel is enabled — the
     * close goes on — and is vetoed while it is not.
     */
    private void closeRequested(DialogEvent event) {
        if (closingByFlow) {
            return;
        }
        if (!cancelPressed()) {
            event.consume();
        }
    }
}
