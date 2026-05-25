package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.layout.BorderPane;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the right-pane toggle race: a panel's in-flight
 * hide-fade {@link javafx.animation.Timeline#setOnFinished} lambda must
 * not wipe the right slot if a sibling panel has taken it over.
 *
 * <p>Repro (pre-fix): open the Undo History panel; press Ctrl+B to
 * switch to Browser. The browser appears, then ~250 ms later the
 * History panel's stale fade-out lambda fires {@code rootPane.setRight(null)}
 * and the browser disappears.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class RightPaneToggleRaceTest {

    /** Generous margin above the 250 ms fade duration. */
    private static final long FADE_WAIT_MS = 600;

    @BeforeEach
    void enableAnimationsForTest() {
        // Force animations ON regardless of the developer's saved Reduce
        // Motion preference — the race only exists when the fade Timeline
        // runs. Uses an isolated Preferences node so we don't pollute the
        // user's real settings.
        Preferences isolated = Preferences.userRoot().node(
                "test/" + RightPaneToggleRaceTest.class.getName() + "/" + System.nanoTime());
        MotionManager testInstance = new MotionManager(isolated);
        testInstance.setReduceMotion(false);
        MotionManager.setDefaultForTest(testInstance);
    }

    @AfterEach
    void restoreDefault() {
        MotionManager.setDefaultForTest(null);
    }

    /**
     * Hide history → show browser → wait past the fade. After the fade
     * the stale {@code setOnFinished} lambda would (pre-fix) clear the
     * right slot. The guard ensures the browser remains installed.
     */
    @Test
    void historyHideFadeMustNotWipeNewlyInstalledBrowser() throws Exception {
        AtomicReference<BrowserPanel> browserRef = new AtomicReference<>();
        AtomicReference<BorderPane> rootRef = new AtomicReference<>();

        runAndRethrow(() -> {
            BorderPane root = new BorderPane();
            Button browserButton = new Button("Library");
            Button historyButton = new Button("History");
            BrowserPanel browserPanel = new BrowserPanel();
            BrowserPanelController browserCtl =
                    new BrowserPanelController(browserPanel, browserButton, root);
            browserCtl.initialize();
            HistoryPanelController historyCtl =
                    new HistoryPanelController(root, historyButton, new StubHost(browserCtl));
            historyCtl.build();

            // Open history (synchronous install + fade-in).
            historyCtl.toggleHistoryPanel();
            assertThat(root.getRight()).isInstanceOf(UndoHistoryPanel.class);

            // Now toggle to browser: history.hide() starts a fade-out
            // whose setOnFinished lambda will, pre-fix, wipe the right slot.
            historyCtl.toggleHistoryPanel();
            browserCtl.toggleBrowserPanel();
            // Browser must be installed immediately.
            assertThat(root.getRight()).isSameAs(browserPanel);

            browserRef.set(browserPanel);
            rootRef.set(root);
        });

        // Wait past the 250 ms hide-fade so the stale setOnFinished fires.
        // Background sleep — FX thread continues servicing Timeline pulses.
        Thread.sleep(FADE_WAIT_MS);

        runAndRethrow(() -> {
            // Pre-fix: rootRef.getRight() == null (browser wiped by stale lambda).
            // Post-fix: rootRef.getRight() == browserPanel (guard skipped clear).
            assertThat(rootRef.get().getRight()).isSameAs(browserRef.get());
        });
    }

    /**
     * Symmetric direction: browser hide → history show → wait. Browser's
     * stale fade lambda must not wipe the freshly-installed history panel.
     */
    @Test
    void browserHideFadeMustNotWipeNewlyInstalledHistory() throws Exception {
        AtomicReference<BorderPane> rootRef = new AtomicReference<>();

        runAndRethrow(() -> {
            BorderPane root = new BorderPane();
            Button browserButton = new Button("Library");
            Button historyButton = new Button("History");
            BrowserPanel browserPanel = new BrowserPanel();
            BrowserPanelController browserCtl =
                    new BrowserPanelController(browserPanel, browserButton, root);
            browserCtl.initialize();
            HistoryPanelController historyCtl =
                    new HistoryPanelController(root, historyButton, new StubHost(browserCtl));
            historyCtl.build();

            // Open browser, then directly call history.toggle() which
            // internally calls host.hideBrowserPanel() (a fade-out) and
            // synchronously installs the history panel.
            browserCtl.toggleBrowserPanel();
            assertThat(root.getRight()).isSameAs(browserPanel);

            historyCtl.toggleHistoryPanel();
            assertThat(root.getRight()).isInstanceOf(UndoHistoryPanel.class);

            rootRef.set(root);
        });

        Thread.sleep(FADE_WAIT_MS);

        runAndRethrow(() -> {
            // Post-fix guard: browser's stale fade lambda no-ops because
            // it no longer owns the right slot.
            assertThat(rootRef.get().getRight()).isInstanceOf(UndoHistoryPanel.class);
        });
    }

    /**
     * Runs the action on the FX thread, capturing and rethrowing any
     * Throwable from inside the runnable. Assertions inside a raw
     * {@code Platform.runLater} are silently swallowed on the FX thread —
     * a well-known headless-test pitfall in this codebase.
     */
    private static void runAndRethrow(FxAction action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX action did not complete within 5s");
        }
        Throwable t = failure.get();
        if (t instanceof Error e) throw e;
        if (t instanceof RuntimeException re) throw re;
        if (t != null) throw new RuntimeException(t);
    }

    @FunctionalInterface
    private interface FxAction {
        void run() throws Exception;
    }

    /** Minimal Host implementing only the methods the toggle path exercises. */
    private static final class StubHost implements HistoryPanelController.Host {
        private final BrowserPanelController browserCtl;
        private final UndoManager undoManager = new UndoManager();

        StubHost(BrowserPanelController browserCtl) {
            this.browserCtl = browserCtl;
        }

        @Override public UndoManager undoManager() { return undoManager; }
        @Override public void updateUndoRedoState() { /* no-op */ }
        @Override public void refreshArrangementCanvas() { /* no-op */ }
        @Override public boolean isBrowserPanelVisible() { return browserCtl.isPanelVisible(); }
        @Override public void hideBrowserPanel() { browserCtl.toggleBrowserPanel(); }
        @Override public void updateStatusBar(String text,
                com.benesquivelmusic.daw.app.ui.icons.DawIcon icon) { /* no-op */ }
    }
}
