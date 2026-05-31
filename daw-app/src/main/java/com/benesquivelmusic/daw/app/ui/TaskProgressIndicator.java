package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;

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
     * The FX-thread marshalling seam (story 289), injected on the production
     * path by the owning controller. May be {@code null} in a pure-unit context
     * (the compatibility constructor defaults it to
     * {@link FxDispatcher#getDefault()}); {@link #postFx} tolerates the null.
     */
    private final FxDispatcher fxDispatcher;

    /**
     * Creates a new progress indicator.
     *
     * @param owner the owning window for visual stacking; may be
     *              {@code null} for a free-floating indicator
     * @param title the initial window title and headline label
     */
    public TaskProgressIndicator(Window owner, String title) {
        this(owner, title, FxDispatcher.getDefault());
    }

    /**
     * Creates a new progress indicator with an explicit FX-thread marshalling
     * seam (story 289).
     *
     * @param owner        the owning window for visual stacking; may be
     *                     {@code null} for a free-floating indicator
     * @param title        the initial window title and headline label
     * @param fxDispatcher the FX-thread marshalling seam, or {@code null} to use
     *                     the {@link FxDispatcher#getDefault() app-scoped default}
     */
    public TaskProgressIndicator(Window owner, String title, FxDispatcher fxDispatcher) {
        // May be null in a pure-unit context; postFx() falls back to the
        // static seam, preserving today's behaviour byte-for-byte.
        this.fxDispatcher = fxDispatcher;
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
            postFx(r);
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
            postFx(r);
        }
    }

    /** Shows the progress window if it is not already visible. */
    public void show() {
        if (Platform.isFxApplicationThread()) {
            if (!stage.isShowing()) stage.show();
        } else {
            postFx(() -> { if (!stage.isShowing()) stage.show(); });
        }
    }

    /** Closes the progress window. Safe to call from any thread. */
    public void close() {
        if (Platform.isFxApplicationThread()) {
            stage.close();
        } else {
            postFx(stage::close);
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
        Runnable uiUpdate = () -> {
            cancelButton.setDisable(true);
            cancelButton.setText("Cancelling…");
        };
        if (Platform.isFxApplicationThread()) {
            uiUpdate.run();
        } else {
            postFx(uiUpdate);
        }
        Runnable cb = onCancel;
        if (cb != null) cb.run();
    }

    /**
     * Hides the Cancel button entirely. Used for single-track freezes
     * where cooperative cancellation is not possible (the render is
     * atomic).
     */
    public void hideCancelButton() {
        Runnable r = () -> {
            cancelButton.setVisible(false);
            cancelButton.setManaged(false);
        };
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            postFx(r);
        }
    }

    /** Returns {@code true} once {@link #requestCancel()} has been invoked. */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Posts {@code work} to the FX thread through the injected
     * {@link FxDispatcher} when present, else the static app-scoped seam — the
     * null branch reproduces today's behaviour exactly (story 289). Callers
     * already guard with {@code Platform.isFxApplicationThread()}; this is only
     * the off-thread hop.
     */
    private void postFx(Runnable work) {
        FxDispatcher.runOnFx(fxDispatcher, work);
    }

    /** Package-private accessor used by tests to verify state without an FX scene. */
    Stage stageForTest() {
        return stage;
    }
}
