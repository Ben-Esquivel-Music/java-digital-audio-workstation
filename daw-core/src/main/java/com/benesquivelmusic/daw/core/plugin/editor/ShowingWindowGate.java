package com.benesquivelmusic.daw.core.plugin.editor;

import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.stage.Window;

import java.util.Objects;

/**
 * The package's single source of the hidden-stage animation-leak discipline:
 * an editor may only run self-scheduled work while its node sits in a
 * {@link Scene} whose {@link Window} is <em>showing</em>. Scene presence alone
 * is not enough — a hidden {@link javafx.stage.Stage} (the dock layer hides
 * floating stages rather than closing them) keeps its Scene attached, so a
 * scene-gated loop would keep spinning invisibly.
 *
 * <p>Two entry points, one per self-scheduling style:</p>
 * <ul>
 *   <li>{@link #isShowing(Node)} — the per-frame predicate for canvas editors
 *       that re-request a render at the end of each frame;</li>
 *   <li>{@link #install(Node, Runnable, Runnable)} — the transition tracker
 *       for panel editors that start/stop an
 *       {@link javafx.animation.AnimationTimer} (or attach/release a listener)
 *       across their visible lifetime. A Panel has no dispose hook, so the
 *       gate — like the scene listeners it replaces — stays installed for the
 *       node's lifetime; {@code onHidden} is the only teardown signal.</li>
 * </ul>
 */
final class ShowingWindowGate {

    private final Runnable onShown;
    private final Runnable onHidden;

    /** The window whose {@code showingProperty} is currently tracked. */
    private Window trackedWindow;
    /** Last state delivered, so callbacks fire only on real transitions. */
    private boolean shown;

    private final ChangeListener<Boolean> showingListener =
            (obs, was, showing) -> apply(showing);
    private final ChangeListener<Window> windowListener =
            (obs, was, window) -> trackWindow(window);
    private final ChangeListener<Scene> sceneListener =
            (obs, oldScene, newScene) -> trackScene(oldScene, newScene);

    private ShowingWindowGate(Runnable onShown, Runnable onHidden) {
        this.onShown = onShown;
        this.onHidden = onHidden;
    }

    /**
     * @param node any node of the editor (the backing canvas, typically)
     * @return {@code true} iff the node is in a scene whose window is showing
     */
    static boolean isShowing(Node node) {
        Scene scene = node.getScene();
        Window window = scene == null ? null : scene.getWindow();
        return window != null && window.isShowing();
    }

    /**
     * Runs {@code onShown} whenever {@code node} transitions into a showing
     * window (including immediately, if it is in one at install time) and
     * {@code onHidden} whenever it transitions out — via scene removal, the
     * scene leaving its window, or the window being hidden. Callbacks fire
     * only on transitions, and {@code onHidden} never fires before the first
     * {@code onShown}.
     *
     * @param node     the editor node whose display state gates the work
     * @param onShown  work to (re)start when the node becomes visible
     * @param onHidden work to stop when the node stops being visible
     */
    static void install(Node node, Runnable onShown, Runnable onHidden) {
        Objects.requireNonNull(node, "node must not be null");
        Objects.requireNonNull(onShown, "onShown must not be null");
        Objects.requireNonNull(onHidden, "onHidden must not be null");
        ShowingWindowGate gate = new ShowingWindowGate(onShown, onHidden);
        node.sceneProperty().addListener(gate.sceneListener);
        gate.trackScene(null, node.getScene());
    }

    private void trackScene(Scene oldScene, Scene newScene) {
        if (oldScene != null) {
            oldScene.windowProperty().removeListener(windowListener);
        }
        if (newScene != null) {
            newScene.windowProperty().addListener(windowListener);
            trackWindow(newScene.getWindow());
        } else {
            trackWindow(null);
        }
    }

    private void trackWindow(Window window) {
        if (trackedWindow == window) {
            apply(window != null && window.isShowing());
            return;
        }
        if (trackedWindow != null) {
            trackedWindow.showingProperty().removeListener(showingListener);
        }
        trackedWindow = window;
        if (window != null) {
            window.showingProperty().addListener(showingListener);
        }
        apply(window != null && window.isShowing());
    }

    private void apply(boolean showing) {
        if (showing == shown) {
            return;
        }
        shown = showing;
        if (showing) {
            onShown.run();
        } else {
            onHidden.run();
        }
    }
}
