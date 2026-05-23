package com.benesquivelmusic.daw.app.ui.views;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.ToolbarStateStore;
import com.benesquivelmusic.daw.app.ui.inspector.InspectorSelectionModel;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 281 follow-up — the Workshop split-pane divider position is
 * persisted across application sessions through
 * {@link ToolbarStateStore#saveWorkshopDividerPosition(double)} /
 * {@link ToolbarStateStore#loadWorkshopDividerPosition()}.
 *
 * <p>The wiring lives in {@code ViewNavigationController.ensureWorkshopBuilt}:
 * the persisted value is hydrated <em>before</em> the view's first layout
 * pass, and a listener on the {@code SplitPane}'s divider position writes
 * back to the store whenever the user drags the divider. This test exercises
 * both ends directly through the store + view pair without rebuilding the
 * whole controller stack — same harness shape as
 * {@code WorkshopViewLayoutTest}.</p>
 *
 * <h2>Range clamp</h2>
 *
 * <p>A stored value outside {@code [0.1, 0.9]} would collapse one pane to
 * zero width, hiding either the arrangement or the plugin pane entirely.
 * The store falls back to the default ({@code 0.6}) for any out-of-range
 * value — the {@code shouldFallBackToDefaultWhenStoredValueIsOutOfRange}
 * test pins this contract directly against {@code ToolbarStateStore}.</p>
 *
 * <h2>FX-harness pitfalls honoured</h2>
 *
 * <ul>
 *   <li>{@code @ExtendWith(JavaFxToolkitExtension.class)} initialises the
 *       JavaFX toolkit once per JVM (memory note: Windows fork-hang fix
 *       relies on the JFX26 {@code -Dglass.platform=Headless}).</li>
 *   <li>The view is attached to a sized {@link Scene} so the {@code
 *       SplitPane} skin runs and the divider actually exists; assertions
 *       on {@link WorkshopView#dividerPosition()} after
 *       {@code applyCss() + layout()} reflect the realised geometry.</li>
 *   <li>Per-test {@link Preferences} node ({@code System.nanoTime()})
 *       isolates each test's persisted state — pattern lifted from
 *       {@code ToolbarStateStoreTest}.</li>
 *   <li>Stage shown inside a try/finally with {@code stage.close()}.</li>
 *   <li>Assertions captured + rethrown on the test thread.</li>
 * </ul>
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class WorkshopDividerPersistenceTest {

    private Preferences prefs;
    private ToolbarStateStore store;

    @BeforeEach
    void setUp() {
        prefs = Preferences.userRoot()
                .node("workshopDividerPersistenceTest_" + System.nanoTime());
        store = new ToolbarStateStore(prefs);
    }

    @AfterEach
    void tearDown() throws Exception {
        prefs.removeNode();
    }

    // ── ToolbarStateStore plumbing ─────────────────────────────────────────

    @Test
    void shouldDefaultToSixtyPercentDividerPosition() {
        assertThat(store.loadWorkshopDividerPosition())
                .as("default divider position is the §4 Concept F 60/40 split")
                .isEqualTo(WorkshopView.DEFAULT_DIVIDER_POSITION);
    }

    @Test
    void shouldPersistDividerPositionAcrossInstances() {
        store.saveWorkshopDividerPosition(0.42);

        ToolbarStateStore reloaded = new ToolbarStateStore(prefs);
        assertThat(reloaded.loadWorkshopDividerPosition())
                .as("a divider position saved on instance A must reload on instance B")
                .isEqualTo(0.42);
    }

    @Test
    void shouldFallBackToDefaultWhenStoredValueIsOutOfRange() {
        // Out-of-range below the floor — would collapse the arrangement
        // pane to near-zero width.
        prefs.putDouble("toolbar.workshopDividerPosition", 0.0);
        assertThat(store.loadWorkshopDividerPosition())
                .as("a stored value below the floor [0.1] must fall back to default")
                .isEqualTo(WorkshopView.DEFAULT_DIVIDER_POSITION);

        // Out-of-range above the ceiling — would collapse the plugin pane.
        prefs.putDouble("toolbar.workshopDividerPosition", 1.0);
        assertThat(store.loadWorkshopDividerPosition())
                .as("a stored value above the ceiling [0.9] must fall back to default")
                .isEqualTo(WorkshopView.DEFAULT_DIVIDER_POSITION);
    }

    // ── WorkshopView ↔ ToolbarStateStore wiring ────────────────────────────

    @Test
    void shouldRestoreDividerPositionFromStoreOnViewBuild() throws Exception {
        // Seed the store BEFORE building the view — mirrors the production
        // sequence (ToolbarStateStore is constructed in MainController
        // before ViewNavigationController.ensureWorkshopBuilt() runs).
        store.saveWorkshopDividerPosition(0.33);

        onFxThread(() -> {
            WorkshopView view = newWorkshopView();
            // Production wiring (ViewNavigationController.ensureWorkshopBuilt)
            // hydrates the position from the store immediately after
            // construction; mirror that here so the test exercises the
            // same seam.
            view.setDividerPosition(store.loadWorkshopDividerPosition());

            StackPane root = new StackPane(view);
            root.getStyleClass().add("root-pane");
            Scene scene = new Scene(root, 1280, 800);
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            try {
                root.applyCss();
                root.layout();

                // SplitPane snaps the divider to pixel boundaries during
                // layout, so the realised position drifts off the
                // requested 0.33 by up to ±1/scene-width (~7.8e-4 at 1280
                // px). The 1e-3 tolerance is much tighter than the
                // 0.6 → 0.33 delta we are guarding against (the default
                // would land 0.27 away).
                assertThat(view.dividerPosition())
                        .as("WorkshopView must report the persisted divider "
                                + "position after hydration, not the default 0.6")
                        .isEqualTo(0.33, org.assertj.core.data.Offset.offset(1e-3));
            } finally {
                stage.close();
            }
            return null;
        });
    }

    @Test
    void shouldWriteDividerPositionBackToStoreOnDividerMove() throws Exception {
        onFxThread(() -> {
            WorkshopView view = newWorkshopView();

            // Mirror the production wiring: hydrate from store, then
            // attach the listener that writes back on changes.
            view.setDividerPosition(store.loadWorkshopDividerPosition());
            view.splitPane().getDividers().get(0).positionProperty()
                    .addListener((obs, oldPos, newPos) ->
                            store.saveWorkshopDividerPosition(newPos.doubleValue()));

            StackPane root = new StackPane(view);
            root.getStyleClass().add("root-pane");
            Scene scene = new Scene(root, 1280, 800);
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            try {
                root.applyCss();
                root.layout();

                // Programmatic divider drag — same observable property a
                // mouse-driven drag mutates.
                view.splitPane().setDividerPositions(0.7);
                root.applyCss();
                root.layout();

                assertThat(store.loadWorkshopDividerPosition())
                        .as("a divider move must write back to the store")
                        .isEqualTo(0.7, org.assertj.core.data.Offset.offset(1e-3));
            } finally {
                stage.close();
            }
            return null;
        });
    }

    // ── Harness ───────────────────────────────────────────────────────────

    private static WorkshopView newWorkshopView() {
        ResourceBundle messages = ResourceBundle.getBundle(
                "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);
        return new WorkshopView(messages, new InspectorSelectionModel());
    }

    private static <T> T onFxThread(Supplier<T> supplier) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(supplier.get());
            } catch (Throwable t) {
                err.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(15, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX thread did not complete within 15 seconds");
        }
        if (err.get() != null) {
            throw new AssertionError("FX thread action failed", err.get());
        }
        return ref.get();
    }
}
