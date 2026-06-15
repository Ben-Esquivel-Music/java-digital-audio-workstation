package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;
import com.benesquivelmusic.daw.app.ui.status.SessionStatusStrip;

import javafx.scene.Group;
import javafx.scene.Scene;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 295 — the §4.4 priority-overflow contract of the
 * {@link SessionStatusStrip} skin. As the strip narrows, collapsible cells are
 * hidden into the {@code ⋯} overflow popover in the fixed order Workspace →
 * Schema → Lock → Disk → Journal; Session, Saved and Checkpoint never collapse.
 *
 * <p>Thresholds (a collapsible cell is hidden when strip width &lt; threshold):
 * Workspace &lt; 1200, Schema &lt; 1140, Lock &lt; 1080, Disk &lt; 1020,
 * Journal &lt; 960. At &ge; 1200 all cells are visible across two rows and the
 * overflow button is hidden.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class StatusStripOverflowPriorityTest {

    /** At &ge; 1200 px every cell is inline (two rows) and nothing overflows. */
    @Test
    void atFullWidthNoCellOverflows() throws Exception {
        runOnFx(() -> {
            SessionStatusStrip strip = realizedStrip();
            resizeTo(strip, 1300.0);

            assertThat(strip.isOverflowButtonVisible())
                    .as("overflow button is hidden when no cell is collapsed (width 1300 >= 1200)")
                    .isFalse();
            assertThat(strip.overflowCells())
                    .as("no cell is collapsed at width 1300")
                    .isEmpty();

            // Every fact is present: never-collapse cells and all collapsibles.
            assertThat(strip.sessionCell().isManaged()).as("session cell present").isTrue();
            assertThat(strip.savedCell().isManaged()).as("saved cell present").isTrue();
            assertThat(strip.checkpointCountdown().isManaged()).as("checkpoint present").isTrue();
            assertThat(strip.journalCell().isManaged()).as("journal cell present").isTrue();
            assertThat(strip.diskCell().isManaged()).as("disk cell present").isTrue();
            assertThat(strip.lockCell().isManaged()).as("lock cell present").isTrue();
            assertThat(strip.schemaCell().isManaged()).as("schema cell present").isTrue();
            assertThat(strip.workspaceCell().isManaged()).as("workspace cell present").isTrue();
        });
    }

    /**
     * Between 1140 and 1200 only the lowest-priority cell (Workspace) collapses;
     * the overflow button appears and the never-collapse cells stay inline.
     */
    @Test
    void betweenSchemaAndWorkspaceOnlyWorkspaceCollapses() throws Exception {
        runOnFx(() -> {
            SessionStatusStrip strip = realizedStrip();
            resizeTo(strip, 1160.0);

            assertThat(strip.overflowCells())
                    .as("at width 1160 only Workspace is collapsed (first in the priority order)")
                    .containsExactly(strip.workspaceCell());
            assertThat(strip.isOverflowButtonVisible())
                    .as("overflow button is shown once any cell is collapsed")
                    .isTrue();

            // The never-collapse cells remain managed/inline.
            assertThat(strip.sessionCell().isManaged()).as("session never collapses").isTrue();
            assertThat(strip.savedCell().isManaged()).as("saved never collapses").isTrue();
            assertThat(strip.checkpointCountdown().isManaged()).as("checkpoint never collapses").isTrue();
        });
    }

    /**
     * Below every threshold (and below the 900 px single-row mark) all five
     * collapsible cells are hidden, in collapse order; the never-collapse cells
     * stay inline.
     */
    @Test
    void belowAllThresholdsEveryCollapsibleCellOverflowsInOrder() throws Exception {
        runOnFx(() -> {
            SessionStatusStrip strip = realizedStrip();
            resizeTo(strip, 850.0);

            assertThat(strip.overflowCells())
                    .as("at width 850 all five collapsibles overflow in collapse order")
                    .containsExactly(
                            strip.workspaceCell(),
                            strip.schemaCell(),
                            strip.lockCell(),
                            strip.diskCell(),
                            strip.journalCell());
            assertThat(strip.isOverflowButtonVisible())
                    .as("overflow button is shown when cells are collapsed")
                    .isTrue();

            // Session, Saved and Checkpoint still inline.
            assertThat(strip.sessionCell().isManaged()).as("session never collapses").isTrue();
            assertThat(strip.savedCell().isManaged()).as("saved never collapses").isTrue();
            assertThat(strip.checkpointCountdown().isManaged()).as("checkpoint never collapses").isTrue();
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Builds a {@link SessionStatusStrip} (over a fresh, unstarted dispatcher's
     * model) and realizes its skin headlessly. The strip is wrapped in a
     * {@link Group} so it does not get resized by a parent layout pass — the
     * test drives width explicitly via {@link #resizeTo}. Must run on the FX
     * thread.
     */
    private static SessionStatusStrip realizedStrip() {
        ProjectOperationProgress progress = new ProjectOperationProgress(new FxDispatcher());
        SessionStatusStrip strip = new SessionStatusStrip(progress, MotionManager.getDefault());
        Group root = new Group(strip); // Group does not resize its child, so resize(...) sticks
        new Scene(root, 1400, 80);
        strip.applyCss(); // realizes the skin (creates cells + width listener + initial reconcile)
        strip.layout();
        return strip;
    }

    /**
     * Sets the strip width (which fires the skin's width listener and runs the
     * reconcile) then re-applies CSS and lays out. The skin is already realized.
     */
    private static void resizeTo(SessionStatusStrip strip, double width) {
        strip.resize(width, 60.0);
        strip.applyCss();
        strip.layout();
    }

    private static void runOnFx(Runnable action) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        javafx.application.Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timed out waiting for JavaFX action to complete");
        }
        if (error.get() != null) {
            throw new RuntimeException(error.get());
        }
    }
}
