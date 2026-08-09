package com.benesquivelmusic.daw.app.ui.settings;

import com.benesquivelmusic.daw.app.ui.dialogs.DawgDialog;
import com.benesquivelmusic.daw.app.ui.dialogs.DialogDismissibility;

import javafx.beans.value.ChangeListener;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Modal host lifecycle for {@link FirstRunWizard}.
 *
 * <p>The content owns the visible footer, while one hidden cancel-data
 * button keeps every JavaFX dismissal route live. Finish applies inside the
 * wizard's retryable error boundary; the host is dismissed only after the
 * callback, persisted first-run flag, outcome, and teardown succeed.</p>
 */
public final class FirstRunWizardHost {

    private final FirstRunWizard wizard;
    private final DawgDialog<Void> dialog = new DawgDialog<>();
    private final Consumer<String> restartNoticeSink;
    private final Button hiddenCancel;
    private final EventHandler<ActionEvent> hiddenCancelGuard;
    private final EventHandler<WindowEvent> windowCloseGuard;
    private final ChangeListener<Window> windowListener;
    private final ChangeListener<Scene> sceneListener;
    private Optional<String> restartNotice = Optional.empty();
    private Scene observedScene;
    private Window guardedWindow;

    /**
     * Creates a host around an already-constructed wizard.
     *
     * @param wizard the wizard content
     * @param applyEdits applies the collected values and completes with the
     *                   canonical restart notice only after the apply succeeds
     * @param restartNoticeSink user-visible notice sink invoked after a
     *                          successful Finish has dismissed the host
     */
    public FirstRunWizardHost(
            FirstRunWizard wizard,
            Function<Map<String, Object>, CompletionStage<Optional<String>>> applyEdits,
            Consumer<String> restartNoticeSink) {
        this.wizard = Objects.requireNonNull(wizard, "wizard must not be null");
        Objects.requireNonNull(applyEdits, "applyEdits must not be null");
        this.restartNoticeSink = Objects.requireNonNull(
                restartNoticeSink, "restartNoticeSink must not be null");

        dialog.setTitle(wizard.title());
        dialog.setHeaderText(wizard.title());
        dialog.getDialogPane().setContent(wizard);
        hiddenCancel = DialogDismissibility.installHiddenCancel(dialog);
        hiddenCancelGuard = event -> {
            if (wizard.isFinishPending()) {
                event.consume();
            }
        };
        hiddenCancel.addEventFilter(ActionEvent.ACTION, hiddenCancelGuard);
        dialog.setOnCloseRequest(event -> {
            if (wizard.isFinishPending()) {
                event.consume();
            }
        });
        windowCloseGuard = event -> {
            if (wizard.isFinishPending()) {
                event.consume();
            }
        };
        windowListener = (_, _, window) -> switchGuardedWindow(window);
        sceneListener = (_, _, scene) -> observeScene(scene);
        dialog.getDialogPane().sceneProperty().addListener(sceneListener);
        observeScene(dialog.getDialogPane().getScene());
        dialog.setResultConverter(_ -> null);

        wizard.setOnFinishedAsync(edits -> Objects.requireNonNull(
                applyEdits.apply(edits), "applyEdits completion must not be null")
                .thenAccept(notice -> restartNotice = Objects.requireNonNull(
                        notice, "applyEdits result must not be null")));
        wizard.setOnOutcomeRecorded(this::dismiss);
    }

    /** Assigns the owner before the host is shown. */
    public void initOwner(Window owner) {
        dialog.initOwner(Objects.requireNonNull(owner, "owner must not be null"));
    }

    /**
     * Shows the modal host and completes abnormal dismissal as Skip.
     *
     * @return the host's empty {@code Void} result
     */
    public Optional<Void> showAndWait() {
        try {
            Optional<Void> result = dialog.showAndWait();
            wizard.skipIfNoOutcome();
            if (wizard.wasFinished()) {
                restartNotice.ifPresent(restartNoticeSink);
            }
            return result;
        } finally {
            removeDismissGuards();
            wizard.close();
        }
    }

    DawgDialog<Void> dialogForTest() {
        return dialog;
    }

    private void observeScene(Scene scene) {
        if (scene == observedScene) {
            return;
        }
        if (observedScene != null) {
            observedScene.windowProperty().removeListener(windowListener);
        }
        observedScene = scene;
        if (scene == null) {
            switchGuardedWindow(null);
            return;
        }
        scene.windowProperty().addListener(windowListener);
        switchGuardedWindow(scene.getWindow());
    }

    private void switchGuardedWindow(Window window) {
        if (window == guardedWindow) {
            return;
        }
        if (guardedWindow != null) {
            guardedWindow.removeEventFilter(
                    WindowEvent.WINDOW_CLOSE_REQUEST, windowCloseGuard);
        }
        guardedWindow = window;
        if (window != null) {
            window.addEventFilter(WindowEvent.WINDOW_CLOSE_REQUEST, windowCloseGuard);
        }
    }

    private void removeDismissGuards() {
        hiddenCancel.removeEventFilter(ActionEvent.ACTION, hiddenCancelGuard);
        dialog.setOnCloseRequest(null);
        dialog.getDialogPane().sceneProperty().removeListener(sceneListener);
        observeScene(null);
    }

    private void dismiss() {
        if (dialog.isShowing()) {
            dialog.close();
        }
    }
}
