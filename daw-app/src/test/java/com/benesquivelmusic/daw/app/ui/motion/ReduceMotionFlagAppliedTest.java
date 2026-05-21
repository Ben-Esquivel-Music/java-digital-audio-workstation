package com.benesquivelmusic.daw.app.ui.motion;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.inspector.InspectorDrawer;

import javafx.scene.Scene;
import javafx.scene.layout.Pane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Optional;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 279 — with global Reduce Motion enabled, an {@link InspectorDrawer}
 * that has <em>not</em> had its per-control flag touched still collapses
 * <strong>instantly</strong>: the 220 ms open/close transition is cut to
 * {@code 0 ms} by the global gate, so the control width reaches its final
 * value within a single layout pulse.
 *
 * <p>This proves the two-mechanism gate: the drawer keeps its default
 * {@code localAnimated = true}, and only the global {@code MotionManager}
 * Reduce Motion flag suppresses the transition. (Contrast
 * {@code InspectorDrawerCollapseTest}, which suppresses via the
 * per-control {@code setAnimated(false)} call.)</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class ReduceMotionFlagAppliedTest {

    /** An OS hint reporting "undetected" — isolates the test from the real OS. */
    private static final OsMotionHint NO_HINT = Optional::empty;

    @Test
    void reduceMotionCollapsesTheDrawerWithinOnePulse() throws BackingStoreException {
        Preferences node = Preferences.userRoot()
                .node("reduceMotionAppliedTest_" + System.nanoTime());
        try {
            MotionManager motion = new MotionManager(node, NO_HINT);
            motion.setReduceMotion(true);
            MotionManager.setDefaultForTest(motion);

            double[] widths = new double[2];
            runOnFxThread(() -> {
                // The drawer reads MotionManager.getDefault() at
                // construction, so the test instance must be installed
                // first (done above).
                InspectorDrawer drawer = new InspectorDrawer();
                // Deliberately DO NOT call setAnimated(...) — the
                // per-control flag stays true; only the global Reduce
                // Motion flag is in play here.
                assertThat(drawer.isAnimated())
                        .as("global Reduce Motion must gate isAnimated() to false")
                        .isFalse();
                drawer.setExpanded(true);
                Pane root = new Pane(drawer);
                new Scene(root, 600, 400);
                root.applyCss();
                root.layout();
                widths[0] = drawer.getWidth();
                drawer.setExpanded(false);
                root.applyCss();
                root.layout();
                widths[1] = drawer.getWidth();
                return null;
            });

            // Expanded width is 240 px; collapsed is 24 px. With the
            // transition cut to 0 ms the collapse completes in the same
            // pulse — no Timeline interpolation leaves it mid-travel.
            assertThat(widths[0])
                    .as("expanded width")
                    .isCloseTo(240.0, within(1.0));
            assertThat(widths[1])
                    .as("collapsed width reached within one pulse under Reduce Motion")
                    .isCloseTo(24.0, within(1.0));
        } finally {
            MotionManager.setDefaultForTest(null);
            node.removeNode();
        }
    }
}
