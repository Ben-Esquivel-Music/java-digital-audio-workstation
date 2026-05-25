package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import javafx.application.Platform;
import javafx.scene.Scene;
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

    /** Isolated Preferences node created per test; removed during teardown. */
    private Preferences isolatedPrefs;

    @BeforeEach
    void enableAnimationsForTest() {
        // Force animations ON regardless of the developer's saved Reduce
        // Motion preference — the race only exists when the fade Timeline
        // runs. Uses an isolated Preferences node so we don't pollute the
        // user's real settings.
        isolatedPrefs = Preferences.userRoot().node(
                "test/" + RightPaneToggleRaceTest.class.getName() + "/" + System.nanoTime());
        MotionManager testInstance = new MotionManager(isolatedPrefs);
        testInstance.setReduceMotion(false);
        MotionManager.setDefaultForTest(testInstance);
    }

    @AfterEach
    void restoreDefault() {
        MotionManager.setDefaultForTest(null);
        // Best-effort: remove the isolated Preferences node so we don't
        // accumulate per-run nodes under the user root over time.
        if (isolatedPrefs != null) {
            try {
                isolatedPrefs.removeNode();
            } catch (java.util.prefs.BackingStoreException ignored) {
                // Best-effort cleanup; leave node behind if backing store rejects.
            }
            isolatedPrefs = null;
        }
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
        AtomicReference<UndoHistoryPanel> fadedPanelRef = new AtomicReference<>();
        AtomicReference<BrowserPanelController> browserCtlRef = new AtomicReference<>();
        AtomicReference<HistoryPanelController> historyCtlRef = new AtomicReference<>();

        runAndRethrow(() -> {
            BorderPane root = new BorderPane();
            // Mount root into a Scene so the JavaFX animation timer
            // pulses drive the Timeline forward in headless mode.
            new Scene(root, 800, 600);
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
            fadedPanelRef.set((UndoHistoryPanel) root.getRight());

            browserRef.set(browserPanel);
            rootRef.set(root);
            browserCtlRef.set(browserCtl);
            historyCtlRef.set(historyCtl);
        });

        // Wait for the fade-in to complete (opacity reaches 1.0) so that
        // when the hide is triggered the fade-out starts from a non-zero
        // opacity. This guarantees awaitOpacityZero later actually implies
        // the fade-out Timeline ran to completion (not a no-op check on an
        // already-zero value).
        awaitOpacityOne(fadedPanelRef.get());

        runAndRethrow(() -> {
            // Now toggle to browser: history.hide() starts a fade-out
            // whose setOnFinished lambda will, pre-fix, wipe the right slot.
            historyCtlRef.get().toggleHistoryPanel();
            browserCtlRef.get().toggleBrowserPanel();
            // Browser must be installed immediately.
            assertThat(rootRef.get().getRight()).isSameAs(browserRef.get());
        });

        // Wait for the history panel's fade-out opacity to reach 0.0,
        // confirming the Timeline actually finished (not just that time elapsed).
        awaitOpacityZero(fadedPanelRef.get());

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
        AtomicReference<BrowserPanel> fadedBrowserRef = new AtomicReference<>();
        AtomicReference<HistoryPanelController> historyCtlRef = new AtomicReference<>();

        runAndRethrow(() -> {
            BorderPane root = new BorderPane();
            // Mount root into a Scene so the JavaFX animation timer
            // pulses drive the Timeline forward in headless mode.
            new Scene(root, 800, 600);
            Button browserButton = new Button("Library");
            Button historyButton = new Button("History");
            BrowserPanel browserPanel = new BrowserPanel();
            BrowserPanelController browserCtl =
                    new BrowserPanelController(browserPanel, browserButton, root);
            browserCtl.initialize();
            HistoryPanelController historyCtl =
                    new HistoryPanelController(root, historyButton, new StubHost(browserCtl));
            historyCtl.build();

            // Open browser (synchronous install + fade-in).
            browserCtl.toggleBrowserPanel();
            assertThat(root.getRight()).isSameAs(browserPanel);

            fadedBrowserRef.set(browserPanel);
            rootRef.set(root);
            historyCtlRef.set(historyCtl);
        });

        // Wait for the browser fade-in to complete (opacity reaches 1.0)
        // so the subsequent fade-out starts from a non-zero opacity,
        // ensuring awaitOpacityZero actually confirms Timeline completion.
        awaitOpacityOne(fadedBrowserRef.get());

        runAndRethrow(() -> {
            // Toggle to history: internally calls host.hideBrowserPanel()
            // (a fade-out) and synchronously installs the history panel.
            historyCtlRef.get().toggleHistoryPanel();
            assertThat(rootRef.get().getRight()).isInstanceOf(UndoHistoryPanel.class);
        });

        // Wait for the browser panel's fade-out opacity to reach 0.0,
        // confirming the Timeline actually finished.
        awaitOpacityZero(fadedBrowserRef.get());

        runAndRethrow(() -> {
            // Post-fix guard: browser's stale fade lambda no-ops because
            // it no longer owns the right slot.
            assertThat(rootRef.get().getRight()).isInstanceOf(UndoHistoryPanel.class);
        });
    }

    /**
     * Polls the FX thread until the given node's opacity reaches 1.0
     * (confirming the fade-in Timeline actually completed) or a deadline
     * is exceeded. Called before triggering a hide so the subsequent
     * fade-out starts from a non-zero opacity — guaranteeing that
     * {@code awaitOpacityZero} later implies actual Timeline completion.
     */
    private static void awaitOpacityOne(javafx.scene.Node node) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FADE_WAIT_MS);
        while (System.nanoTime() < deadline) {
            CountDownLatch poll = new CountDownLatch(1);
            AtomicReference<Double> opacityRef = new AtomicReference<>();
            Platform.runLater(() -> {
                opacityRef.set(node.getOpacity());
                poll.countDown();
            });
            if (!poll.await(2, TimeUnit.SECONDS)) {
                break;
            }
            Double opacity = opacityRef.get();
            if (opacity != null && opacity >= 1.0) {
                return;
            }
            Thread.sleep(20);
        }
        // Final check — assert so failure message is informative.
        CountDownLatch finalLatch = new CountDownLatch(1);
        AtomicReference<Double> finalOpacity = new AtomicReference<>();
        Platform.runLater(() -> {
            finalOpacity.set(node.getOpacity());
            finalLatch.countDown();
        });
        finalLatch.await(2, TimeUnit.SECONDS);
        assertThat(finalOpacity.get())
                .as("Panel opacity should reach 1.0 (fade-in Timeline must have completed)")
                .isNotNull()
                .isEqualTo(1.0);
    }

    /**
     * Polls the FX thread until the given node's opacity reaches 0.0
     * (confirming the fade-out Timeline actually completed) or a deadline
     * is exceeded. This replaces a fixed {@code Thread.sleep} and
     * guarantees the {@code setOnFinished} path has been exercised.
     */
    private static void awaitOpacityZero(javafx.scene.Node node) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FADE_WAIT_MS);
        while (System.nanoTime() < deadline) {
            CountDownLatch poll = new CountDownLatch(1);
            AtomicReference<Double> opacityRef = new AtomicReference<>();
            Platform.runLater(() -> {
                opacityRef.set(node.getOpacity());
                poll.countDown();
            });
            if (!poll.await(2, TimeUnit.SECONDS)) {
                break;
            }
            Double opacity = opacityRef.get();
            if (opacity != null && opacity <= 0.0) {
                return;
            }
            Thread.sleep(20);
        }
        // Final check — assert so failure message is informative.
        CountDownLatch finalLatch = new CountDownLatch(1);
        AtomicReference<Double> finalOpacity = new AtomicReference<>();
        Platform.runLater(() -> {
            finalOpacity.set(node.getOpacity());
            finalLatch.countDown();
        });
        finalLatch.await(2, TimeUnit.SECONDS);
        assertThat(finalOpacity.get())
                .as("Fading panel opacity should reach 0.0 (Timeline must have completed)")
                .isNotNull()
                .isEqualTo(0.0);
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
