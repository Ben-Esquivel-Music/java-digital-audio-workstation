package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;
import com.benesquivelmusic.daw.app.ui.status.SessionStatusStrip;
import com.benesquivelmusic.daw.app.ui.status.StatusStripCell;

import javafx.scene.Group;
import javafx.scene.Scene;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 295 — the Disk cell's §6.2 capture-room thresholds drive its severity
 * style class: amber ({@link StatusStripCell#SEVERITY_WARN_CLASS}) under 30
 * record-minutes, red ({@link StatusStripCell#SEVERITY_CRITICAL_CLASS}) under 5.
 *
 * <p>The cell flips those classes on its own {@code getStyleClass()} when its
 * severity changes (headless-safe — independent of a realized skin), and the
 * skin binds the disk cell's severity to {@code estimatedRecordMinutes}.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class DiskCellThresholdTest {

    /**
     * Feeds three capture-room estimates through the model and asserts the disk
     * cell's severity style classes flip across the §6.2 amber/red thresholds.
     */
    @Test
    void diskCellSeverityClassFlipsAcrossThresholds() throws Exception {
        FxDispatcher dispatcher = new FxDispatcher();
        ProjectOperationProgress progress = new ProjectOperationProgress(dispatcher);

        AtomicReference<SessionStatusStrip> stripRef = new AtomicReference<>();
        runOnFx(() -> stripRef.set(realizedStrip(progress)));
        SessionStatusStrip strip = stripRef.get();

        runOnFx(() -> {
            // 29 record-minutes → amber (WARN), not red.
            progress.setDisk(100L * 1024 * 1024 * 1024, 29.0);
            dispatcher.pulse();
            assertThat(strip.diskCell().getStyleClass())
                    .as("29 record-minutes (< 30) flips the disk cell to the amber WARN class")
                    .contains(StatusStripCell.SEVERITY_WARN_CLASS)
                    .doesNotContain(StatusStripCell.SEVERITY_CRITICAL_CLASS);

            // 4 record-minutes → red (CRITICAL), not amber.
            progress.setDisk(5L * 1024 * 1024 * 1024, 4.0);
            dispatcher.pulse();
            assertThat(strip.diskCell().getStyleClass())
                    .as("4 record-minutes (< 5) flips the disk cell to the red CRITICAL class")
                    .contains(StatusStripCell.SEVERITY_CRITICAL_CLASS)
                    .doesNotContain(StatusStripCell.SEVERITY_WARN_CLASS);

            // 120 record-minutes → healthy (NORMAL): neither severity class present.
            progress.setDisk(500L * 1024 * 1024 * 1024, 120.0);
            dispatcher.pulse();
            assertThat(strip.diskCell().getStyleClass())
                    .as("120 record-minutes (>= 30) carries neither severity class")
                    .doesNotContain(
                            StatusStripCell.SEVERITY_WARN_CLASS,
                            StatusStripCell.SEVERITY_CRITICAL_CLASS);
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Builds a {@link SessionStatusStrip} over {@code progress} and realizes its
     * skin headlessly so the disk cell is created and its severity bound. Must
     * run on the FX thread.
     */
    private static SessionStatusStrip realizedStrip(ProjectOperationProgress progress) {
        SessionStatusStrip strip = new SessionStatusStrip(progress, MotionManager.getDefault());
        Group root = new Group(strip);
        new Scene(root, 1400, 80);
        strip.applyCss();
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
