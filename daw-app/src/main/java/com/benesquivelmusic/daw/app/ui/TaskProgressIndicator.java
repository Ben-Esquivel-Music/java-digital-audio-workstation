package com.benesquivelmusic.daw.app.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * A modeless progress indicator for long-running, cancellable tasks.
 *
 * <p>Used by {@link TrackFreezeController} to surface offline render
 * progress when freezing one or more tracks. The window is intentionally
 * <em>modeless</em> so the user can keep working in other parts of the
 * application while a freeze is in flight.</p>
 *
 * <p>Provides a title label, a per-step detail label, an indeterminate
 * or fractional progress bar, and a Cancel button. Cancellation
 * delegates to a caller-supplied {@link Runnable} so the owning
 * controller can decide how to roll the task back (for example by
 * calling {@code undo()} on the partially-executed action).</p>
 *
 * <p>This class is intentionally headless-tolerant: all FX-API calls
 * happen on the JavaFX application thread, and the progress methods
 * route through {@link Platform#runLater(Runnable)} so they can be
 * invoked from a worker thread.</p>
 */
public final class TaskProgressIndicator {

    private final Stage stage;
    private final Label titleLabel;
    private final Label detailLabel;
    private final ProgressBar progressBar;
    private final Button cancelButton;
    private volatile Runnable onCancel;
    private volatile boolean cancelled;

    /**
     * Creates a new progress indicator.
     *
     * @param owner the owning window for visual stacking; may be
     *              {@code null} for a free-floating indicator
     * @param title the initial window title and headline label
     */
    public TaskProgressIndicator(Window owner, String title) {
        this.titleLabel = new Label(title);
        this.titleLabel.getStyleClass().add("panel-header");
        this.detailLabel = new Label("");
        this.detailLabel.setWrapText(true);
        this.progressBar = new ProgressBar(0.0);
        this.progressBar.setPrefWidth(360);
        this.cancelButton = new Button("Cancel");
        this.cancelButton.setOnAction(_ -> requestCancel());

        VBox root = new VBox(8, titleLabel, detailLabel, progressBar, cancelButton);
        root.setAlignment(Pos.CENTER_LEFT);
        root.setPadding(new Insets(14));
        root.getStyleClass().add("daw-panel");

        this.stage = new Stage();
        this.stage.initStyle(StageStyle.UTILITY);
        this.stage.initModality(Modality.NONE);
        if (owner != null) {
            this.stage.initOwner(owner);
        }
        this.stage.setTitle(title);
        this.stage.setResizable(false);
        this.stage.setScene(new Scene(root));
    }

    /**
     * Registers the callback invoked when the user clicks Cancel or
     * when {@link #requestCancel()} is called programmatically.
     */
    public void setOnCancel(Runnable callback) {
        this.onCancel = callback;
    }

    /**
     * Updates the progress bar and detail label. Safe to call from any
     * thread — the update is hopped to the JavaFX application thread.
     *
     * @param progress fraction in {@code [0.0, 1.0]}, or any negative
     *                 value to show an indeterminate spinner
     * @param detail   short status message (e.g. {@code "Freezing 'Drums' (2/5)"})
     */
    public void update(double progress, String detail) {
        Runnable r = () -> {
            progressBar.setProgress(progress);
            if (detail != null) {
                detailLabel.setText(detail);
            }
        };
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
    }

    /** Updates the headline title shown above the progress bar. */
    public void setTitle(String title) {
        Runnable r = () -> {
            titleLabel.setText(title);
            stage.setTitle(title);
        };
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
    }

    /** Shows the progress window if it is not already visible. */
    public void show() {
        if (Platform.isFxApplicationThread()) {
            if (!stage.isShowing()) stage.show();
        } else {
            Platform.runLater(() -> { if (!stage.isShowing()) stage.show(); });
        }
    }

    /** Closes the progress window. Safe to call from any thread. */
    public void close() {
        if (Platform.isFxApplicationThread()) {
            stage.close();
        } else {
            Platform.runLater(stage::close);
        }
    }

    /**
     * Marks the task as cancelled and runs the registered cancel
     * callback (if any). Subsequent calls are no-ops; callers that
     * poll {@link #isCancelled()} should observe the {@code true}
     * value before tearing down their work loop.
     */
    public void requestCancel() {
        if (cancelled) return;
        cancelled = true;
        cancelButton.setDisable(true);
        cancelButton.setText("Cancelling…");
        Runnable cb = onCancel;
        if (cb != null) cb.run();
    }

    /** Returns {@code true} once {@link #requestCancel()} has been invoked. */
    public boolean isCancelled() {
        return cancelled;
    }

    /** Package-private accessor used by tests to verify state without an FX scene. */
    Stage stageForTest() {
        return stage;
    }
}
