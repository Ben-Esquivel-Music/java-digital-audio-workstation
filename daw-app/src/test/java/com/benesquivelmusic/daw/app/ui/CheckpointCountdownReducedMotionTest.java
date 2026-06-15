package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.status.CheckpointCountdownCanvas;

import javafx.scene.Group;
import javafx.scene.Scene;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 295 — the §6 checkpoint countdown cell ({@link CheckpointCountdownCanvas})
 * under Reduce Motion (story 279) and its pure, toolkit-free text/geometry
 * transforms.
 *
 * <p>The cell's caption and progress-bar fraction are computed by pure static
 * methods that need no FX thread, so those are asserted directly. The
 * {@code AnimationTimer} lifecycle is scene-gated: it runs <em>iff</em> the cell
 * is attached to a {@link Scene} and Reduce Motion is off
 * ({@code javafx-application-design} §6 "a control owns its own timer"; §11 "all
 * draws on the FX thread"). Attaching the canvas to a {@code Scene} is what arms
 * the timer; Reduce Motion gates it back off so a reduce-motion cell never spins
 * a frame loop and instead repaints static text only on change.</p>
 *
 * <p>Assertions that run inside a {@code Platform.runLater} body are swallowed by
 * JavaFX, so every FX-thread assertion goes through {@link #runOnFx(Runnable)},
 * which captures and rethrows the failure on the test thread.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class CheckpointCountdownReducedMotionTest {

    // ── (a) Pure transforms — no FX thread required ───────────────────────────

    @Test
    void formatRemainingProducesMinuteSecondAndHourForms() {
        assertThat(CheckpointCountdownCanvas.formatRemaining(Duration.ofSeconds(37)))
                .as("37s formats as M:SS")
                .isEqualTo("0:37");
        assertThat(CheckpointCountdownCanvas.formatRemaining(Duration.ofSeconds(125)))
                .as("125s = 2m05s formats as M:SS")
                .isEqualTo("2:05");
        assertThat(CheckpointCountdownCanvas.formatRemaining(Duration.ZERO))
                .as("zero clamps to 0:00")
                .isEqualTo("0:00");
        assertThat(CheckpointCountdownCanvas.formatRemaining(Duration.ofSeconds(-5)))
                .as("a negative duration clamps to 0:00")
                .isEqualTo("0:00");
        assertThat(CheckpointCountdownCanvas.formatRemaining(Duration.ofSeconds(3725)))
                .as("3725s = 1h02m05s switches to the H:MM:SS form")
                .isEqualTo("1:02:05");
    }

    @Test
    void countdownTextDescribesScheduledAndUnscheduledStates() {
        assertThat(CheckpointCountdownCanvas.countdownText(null))
                .as("a null remaining means no checkpoint is scheduled")
                .isEqualTo("No checkpoint scheduled");
        assertThat(CheckpointCountdownCanvas.countdownText(Duration.ofSeconds(37)))
                .as("a scheduled checkpoint reads 'Next checkpoint in M:SS'")
                .isEqualTo("Next checkpoint in 0:37");
    }

    @Test
    void barFractionIsRemainingFractionClampedToUnitInterval() {
        Instant now = Instant.now();
        Instant next = now.plusSeconds(30);
        assertThat(CheckpointCountdownCanvas.barFraction(now, next, Duration.ofSeconds(60)))
                .as("30s remaining of a 60s interval is roughly half the bar")
                .isCloseTo(0.5, within(0.05));
        assertThat(CheckpointCountdownCanvas.barFraction(now, now, Duration.ofSeconds(60)))
                .as("no time remaining means an empty bar")
                .isCloseTo(0.0, within(0.05));
    }

    @Test
    void shouldRedrawFiresOnFirstTickThenThrottlesToOneHzWithoutOverflow() {
        // Positive frame timestamps, as AnimationTimer's System.nanoTime()-based
        // `now` is on Windows. The first tick after a (re)start carries the
        // Long.MIN_VALUE sentinel; it MUST redraw. A bare
        // `now - Long.MIN_VALUE >= ONE_SECOND_NANOS` overflows to a negative
        // delta here and would never fire — this is the regression guard.
        long firstNow = 5_000_000_000L;
        assertThat(CheckpointCountdownCanvas.shouldRedraw(Long.MIN_VALUE, firstNow))
                .as("the first tick after a (re)start always redraws despite the "
                        + "Long.MIN_VALUE sentinel (now - Long.MIN_VALUE overflow regression)")
                .isTrue();

        // Once a real timestamp is recorded, the loop throttles to 1 Hz.
        assertThat(CheckpointCountdownCanvas.shouldRedraw(firstNow, firstNow + 500_000_000L))
                .as("a sub-second tick (0.5 s after the last redraw) is throttled")
                .isFalse();
        assertThat(CheckpointCountdownCanvas.shouldRedraw(firstNow, firstNow + 1_000_000_000L))
                .as("a tick exactly 1 s after the last redraw fires")
                .isTrue();
        assertThat(CheckpointCountdownCanvas.shouldRedraw(firstNow, firstNow + 2_500_000_000L))
                .as("a tick well past 1 s fires")
                .isTrue();
    }

    // ── (b) Reduce Motion ON → no timer (static text) ─────────────────────────

    @Test
    void reduceMotionOnLeavesTimerStoppedEvenWhenAttachedToScene() throws Exception {
        boolean[] running = new boolean[1];
        int[] starts = new int[1];
        runOnFx(() -> {
            CheckpointCountdownCanvas canvas = new CheckpointCountdownCanvas();
            canvas.setReduceMotion(true);
            // Attaching to a Scene normally arms the scene-gated timer; Reduce
            // Motion must gate it back off.
            new Scene(new Group(canvas));
            running[0] = canvas.isCountdownTimerRunning();
            starts[0] = canvas.timerStartCount();
        });
        assertThat(running[0])
                .as("with Reduce Motion on, no AnimationTimer runs even when the "
                        + "cell is attached to a Scene (story 279 — static text only)")
                .isFalse();
        assertThat(starts[0])
                .as("the timer was never started under Reduce Motion")
                .isZero();
    }

    // ── (c) Reduce Motion OFF → timer started exactly once ────────────────────

    @Test
    void reduceMotionOffStartsTheTimerOnceWhenAttachedToScene() throws Exception {
        boolean[] running = new boolean[1];
        int[] starts = new int[1];
        runOnFx(() -> {
            CheckpointCountdownCanvas canvas = new CheckpointCountdownCanvas();
            canvas.setReduceMotion(false);
            // Attaching to a Scene arms the scene-gated 1 Hz redraw timer.
            new Scene(new Group(canvas));
            running[0] = canvas.isCountdownTimerRunning();
            starts[0] = canvas.timerStartCount();
        });
        assertThat(running[0])
                .as("with Reduce Motion off and the cell attached to a Scene, the "
                        + "1 Hz countdown timer runs")
                .isTrue();
        assertThat(starts[0])
                .as("the scene-gated timer started exactly once")
                .isEqualTo(1);
    }

    // ── FX-thread helper (captures + rethrows swallowed assertion failures) ────

    private static void runOnFx(Runnable action) throws Exception {
        java.util.concurrent.atomic.AtomicReference<Throwable> error =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        javafx.application.Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new RuntimeException("FX action timed out");
        }
        if (error.get() != null) {
            throw new RuntimeException(error.get());
        }
    }
}
