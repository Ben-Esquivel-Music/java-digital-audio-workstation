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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 295 — binding contract between {@link ProjectOperationProgress} (the
 * single §5.5 status model) and the {@link SessionStatusStrip}'s bound cells,
 * marshalled through the story-289 {@link FxDispatcher}.
 *
 * <p>Verifies the two §2.1 / §6 "coalesce per frame" guarantees the strip relies
 * on: (a) an off-FX-thread mutation is reflected on the bound cell only after a
 * {@link FxDispatcher#pulse() pulse}, and (b) N rapid same-key mutations within
 * one frame collapse to a single observable update (latest-wins).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ProjectOperationProgressBindingTest {

    /**
     * A mutation published to the model off-pulse becomes visible on the bound
     * cell text only after {@link FxDispatcher#pulse()} drains the keyed work.
     */
    @Test
    void offThreadMutationReflectedOnBoundCellAfterPulse() throws Exception {
        // The setters are thread-safe (FxDispatcher.onFx may be called from any
        // thread), so publish from the test thread before hopping onto FX.
        FxDispatcher dispatcher = new FxDispatcher();
        ProjectOperationProgress progress = new ProjectOperationProgress(dispatcher);

        AtomicReference<SessionStatusStrip> stripRef = new AtomicReference<>();
        runOnFx(() -> stripRef.set(realizedStrip(progress)));
        SessionStatusStrip strip = stripRef.get();

        // Publish off the FX thread (the disk scan / save path's real situation).
        progress.setJournalEventsQueued(4);
        progress.setWorkspaceName("Studio");

        runOnFx(() -> {
            // Before the pulse the keyed work is still queued; draining it on the
            // FX thread is what flips the bound properties (and hence the cells).
            dispatcher.pulse();

            assertThat(strip.journalCell().getText())
                    .as("journal cell binds \"Journal: <n> events queued\" after pulse")
                    .contains("4");
            assertThat(strip.workspaceCell().getText())
                    .as("workspace cell binds the workspace name after pulse")
                    .contains("Studio");
        });
    }

    /**
     * Three rapid same-key mutations with no intervening pulse collapse to a
     * single observable update on the next pulse, and the latest value wins.
     */
    @Test
    void rapidSameKeyMutationsCoalesceToSingleUpdate() throws Exception {
        FxDispatcher dispatcher = new FxDispatcher();
        ProjectOperationProgress progress = new ProjectOperationProgress(dispatcher);

        // Everything inside one FX block so ordering is deterministic:
        // add listener -> 3 same-key sets (no pulse between) -> single pulse -> assert.
        runOnFx(() -> {
            AtomicInteger fireCount = new AtomicInteger(0);
            progress.journalEventsQueuedProperty()
                    .addListener(observable -> fireCount.incrementAndGet());

            progress.setJournalEventsQueued(1);
            progress.setJournalEventsQueued(2);
            progress.setJournalEventsQueued(3);

            // Not yet drained: the keyed work coalesces to the latest runnable, and
            // the property has not changed, so the listener has not fired.
            assertThat(fireCount.get())
                    .as("no observable update before the pulse drains the keyed work")
                    .isZero();

            dispatcher.pulse();

            assertThat(fireCount.get())
                    .as("three rapid same-key mutations coalesce to exactly one update")
                    .isEqualTo(1);
            assertThat(progress.getJournalEventsQueued())
                    .as("latest-wins coalescing keeps the last published value")
                    .isEqualTo(3);
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Builds a {@link SessionStatusStrip} over {@code progress} and realizes its
     * skin headlessly (Group + Scene + applyCss) so the cell accessors return
     * live, bound cells. Must run on the FX thread.
     */
    private static SessionStatusStrip realizedStrip(ProjectOperationProgress progress) {
        SessionStatusStrip strip = new SessionStatusStrip(progress, MotionManager.getDefault());
        Group root = new Group(strip);
        new Scene(root, 1400, 80);
        strip.applyCss(); // realizes the skin (creates + binds the cells)
        strip.layout();
        return strip;
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
