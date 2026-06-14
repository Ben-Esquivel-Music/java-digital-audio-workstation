package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import com.benesquivelmusic.daw.core.undo.UndoHistoryListener;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.layout.BorderPane;
import javafx.util.Duration;

import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Manages the undo history panel: build, rebuild, and animated toggle on
 * the right side of the root pane.
 *
 * <p>Extracted from {@code MainController} to separate panel management
 * from the main coordinator. The notification-history surface is no
 * longer a standalone panel here — story 273 folded it into the
 * inspector drawer's Notifications section, so this controller only owns
 * the undo-history panel now.</p>
 */
final class HistoryPanelController {

    private static final Logger LOG = Logger.getLogger(HistoryPanelController.class.getName());

    private final BorderPane rootPane;
    private final javafx.scene.control.Button historyButton;
    /**
     * Story 293 — direct collaborators replacing the retired {@code Host}
     * callback-up interface (CONTROL_SYNCHRONIZATION_DESIGN_BOOK §9). The undo
     * manager arrives via a live {@link Supplier} (this controller is init-only
     * and is not reconstructed on project load, so the supplier reads the
     * swappable field fresh each time — e.g. after a snapshot restore swaps in a
     * new {@code UndoManager}).
     */
    private final Supplier<UndoManager> undoManager;
    private final Runnable syncMenuState;
    private final Runnable refreshArrangementCanvas;
    private final BooleanSupplier browserPanelVisible;
    private final Runnable hideBrowserPanel;
    private final BiConsumer<String, DawIcon> statusBar;
    /**
     * The FX-thread marshalling seam (story 289), injected on the production
     * path and threaded into every {@link UndoHistoryPanel} this controller
     * builds. May be {@code null} in a pure-unit context (the compatibility
     * constructor defaults it to {@link FxDispatcher#getDefault()});
     * {@link #postFx} tolerates the null.
     */
    private final FxDispatcher fxDispatcher;

    private UndoHistoryPanel undoHistoryPanel;
    private boolean historyPanelVisible;
    /**
     * The history listener registered by {@link #rebuild()} and the
     * {@link UndoManager} it is currently attached to. Tracked as a pair so
     * each rebuild can detach from the previously-listened manager before
     * re-registering — keeping {@code rebuild()} idempotent and preventing
     * duplicate menu/canvas refreshes were it ever invoked without a fresh
     * {@code UndoManager} having been swapped in first.
     */
    private UndoHistoryListener historyListener;
    private UndoManager listenedUndoManager;

    HistoryPanelController(BorderPane rootPane,
                           javafx.scene.control.Button historyButton,
                           Supplier<UndoManager> undoManager,
                           Runnable syncMenuState,
                           Runnable refreshArrangementCanvas,
                           BooleanSupplier browserPanelVisible,
                           Runnable hideBrowserPanel,
                           BiConsumer<String, DawIcon> statusBar) {
        this(rootPane, historyButton, undoManager, syncMenuState,
                refreshArrangementCanvas, browserPanelVisible, hideBrowserPanel,
                statusBar, FxDispatcher.getDefault());
    }

    HistoryPanelController(BorderPane rootPane,
                           javafx.scene.control.Button historyButton,
                           Supplier<UndoManager> undoManager,
                           Runnable syncMenuState,
                           Runnable refreshArrangementCanvas,
                           BooleanSupplier browserPanelVisible,
                           Runnable hideBrowserPanel,
                           BiConsumer<String, DawIcon> statusBar,
                           FxDispatcher fxDispatcher) {
        this.rootPane = Objects.requireNonNull(rootPane, "rootPane must not be null");
        this.historyButton = Objects.requireNonNull(historyButton, "historyButton must not be null");
        this.undoManager = Objects.requireNonNull(undoManager, "undoManager must not be null");
        this.syncMenuState = Objects.requireNonNull(syncMenuState, "syncMenuState must not be null");
        this.refreshArrangementCanvas = Objects.requireNonNull(
                refreshArrangementCanvas, "refreshArrangementCanvas must not be null");
        this.browserPanelVisible = Objects.requireNonNull(
                browserPanelVisible, "browserPanelVisible must not be null");
        this.hideBrowserPanel = Objects.requireNonNull(
                hideBrowserPanel, "hideBrowserPanel must not be null");
        this.statusBar = Objects.requireNonNull(statusBar, "statusBar must not be null");
        // May be null in a pure-unit context; postFx() / UndoHistoryPanel fall
        // back to the static seam, preserving today's behaviour byte-for-byte.
        this.fxDispatcher = fxDispatcher;
    }

    /**
     * Posts {@code work} to the FX thread through the injected
     * {@link FxDispatcher} when present, else the static app-scoped seam — the
     * null branch reproduces today's behaviour exactly (story 289).
     */
    private void postFx(Runnable work) {
        FxDispatcher.runOnFx(fxDispatcher, work);
    }

    void build() {
        undoHistoryPanel = new UndoHistoryPanel(undoManager.get(), fxDispatcher);
        historyButton.setOnAction(_ -> toggleHistoryPanel());
        LOG.fine("Built undo history panel");
    }

    void rebuild() {
        if (undoHistoryPanel != null) {
            undoHistoryPanel.dispose();
        }
        // Read the swappable manager once so the listener and the panel below
        // are guaranteed to share the same instance.
        UndoManager current = undoManager.get();
        // Detach the prior listener from the manager it was registered on
        // before registering on the current one. Production callers swap in a
        // fresh UndoManager just before each rebuild(), so the old listener
        // would otherwise linger on the discarded manager; and if rebuild()
        // were ever called without a swap, the same manager would accumulate
        // duplicate listeners and fire the refresh callbacks once per
        // registration on every edit.
        if (historyListener != null && listenedUndoManager != null) {
            listenedUndoManager.removeHistoryListener(historyListener);
        }
        historyListener = _ -> {
            if (javafx.application.Platform.isFxApplicationThread()) {
                syncMenuState.run();
                refreshArrangementCanvas.run();
            } else {
                postFx(() -> {
                    syncMenuState.run();
                    refreshArrangementCanvas.run();
                });
            }
        };
        current.addHistoryListener(historyListener);
        listenedUndoManager = current;
        undoHistoryPanel = new UndoHistoryPanel(current, fxDispatcher);
        if (historyPanelVisible) {
            rootPane.setRight(undoHistoryPanel);
        }
    }

    void toggleHistoryPanel() {
        historyPanelVisible = !historyPanelVisible;
        // Panel show/hide is transitional motion (UI Design Book §3.5) —
        // gated by global Reduce Motion (story 279). With Reduce Motion on
        // the panel is shown/removed at once with no opacity fade.
        boolean animate = MotionManager.getDefault().isAnimationAllowed();
        if (historyPanelVisible) {
            if (browserPanelVisible.getAsBoolean()) {
                hideBrowserPanel.run();
            }
            rootPane.setRight(undoHistoryPanel);
            if (animate) {
                undoHistoryPanel.setOpacity(0.0);
                new Timeline(new KeyFrame(Duration.millis(250),
                        new KeyValue(undoHistoryPanel.opacityProperty(), 1.0))).play();
            } else {
                undoHistoryPanel.setOpacity(1.0);
            }
            statusBar.accept("Undo History panel opened", DawIcon.HISTORY);
        } else {
            if (animate) {
                final var fadingPanel = undoHistoryPanel;
                Timeline timeline = new Timeline(new KeyFrame(Duration.millis(250),
                        new KeyValue(fadingPanel.opacityProperty(), 0.0)));
                // Guard: only clear the right slot if WE still own it AND
                // the panel is still meant to be hidden. A sibling right-pane
                // (e.g. BrowserPanel) may have taken it over while our fade
                // was in flight; clearing unconditionally would wipe it.
                // Likewise, if the user closed and quickly reopened the
                // History panel before this fade completes, historyPanelVisible
                // will be true again and we must not clear the re-opened panel.
                // We compare against the captured fadingPanel (not the mutable
                // field) so that rebuild() during the fade doesn't bypass the
                // guard.
                timeline.setOnFinished(_ -> {
                    if (!historyPanelVisible && rootPane.getRight() == fadingPanel) {
                        rootPane.setRight(null);
                    }
                });
                timeline.play();
            } else {
                rootPane.setRight(null);
            }
            statusBar.accept("Undo History panel closed", DawIcon.HISTORY);
        }
        updateHistoryButtonActiveState();
    }

    boolean isHistoryPanelVisible() {
        return historyPanelVisible;
    }

    void setHistoryPanelVisible(boolean visible) {
        this.historyPanelVisible = visible;
        updateHistoryButtonActiveState();
    }

    private void updateHistoryButtonActiveState() {
        List<String> styles = historyButton.getStyleClass();
        if (historyPanelVisible) {
            if (!styles.contains("toolbar-button-active")) {
                styles.add("toolbar-button-active");
            }
        } else {
            styles.remove("toolbar-button-active");
        }
    }
}
