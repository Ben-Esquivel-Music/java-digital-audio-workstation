package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.LevelMeterDisplay;
import com.benesquivelmusic.daw.app.ui.display.SpectrumDisplay;
import com.benesquivelmusic.daw.core.transport.TransportState;

import javafx.animation.AnimationTimer;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

import java.util.Objects;

/**
 * Coordinates all frame-by-frame and transition-based animations for the
 * main DAW window. Heavy lifting is delegated to focused collaborators:
 *
 * <ul>
 *   <li>{@link IdleVisualizationAnimator} — synthesized spectrum and
 *       level-meter data while the engine is idle.</li>
 *   <li>{@link TransportGlowAnimator} — pulsing play-button glow and
 *       blinking record-button glow.</li>
 *   <li>{@link TimeTickerAnimator} — elapsed-time display ticker.</li>
 *   <li>{@link ButtonPressAnimator} — scale-bounce press animations on
 *       toolbar buttons.</li>
 * </ul>
 *
 * <p>This controller is responsible only for owning the single
 * {@link AnimationTimer} that drives those collaborators each frame, plus
 * exposing the playhead-update callback hook used by
 * {@link MainController}. Issue: "Decompose Remaining God-Class
 * Controllers into Focused Services."</p>
 */
final class AnimationController {

    /**
     * Callback interface implemented by the host controller to provide
     * dynamic state that remains in the top-level controller.
     */
    interface Host {
        TransportState transportState();
    }

    private final IdleVisualizationAnimator idleVisualizationAnimator;
    private final TransportGlowAnimator transportGlowAnimator;
    private final TimeTickerAnimator timeTickerAnimator;
    private final ButtonPressAnimator buttonPressAnimator;
    private final Host host;

    private AnimationTimer mainAnimTimer;
    private double glowAnimPhase;
    private Runnable playheadUpdateCallback;

    AnimationController(SpectrumDisplay spectrumDisplay,
                        LevelMeterDisplay levelMeterDisplay,
                        Label timeDisplay,
                        Button playButton,
                        Button recordButton,
                        Button[] animatedButtons,
                        Host host) {
        this.idleVisualizationAnimator = new IdleVisualizationAnimator(
                spectrumDisplay, levelMeterDisplay);
        this.transportGlowAnimator = new TransportGlowAnimator(playButton, recordButton);
        this.timeTickerAnimator = new TimeTickerAnimator(timeDisplay);
        this.buttonPressAnimator = new ButtonPressAnimator(animatedButtons);
        this.host = Objects.requireNonNull(host, "host must not be null");
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Creates and starts the single {@link AnimationTimer} that drives
     * all continuous frame-by-frame animations: idle visualization demo,
     * transport glow, and the time-display ticker.
     */
    void start() {
        mainAnimTimer = new AnimationTimer() {
            private long lastNanos = 0;

            @Override
            public void handle(long now) {
                if (lastNanos == 0) {
                    lastNanos = now;
                    return;
                }
                double delta = (now - lastNanos) / 1_000_000_000.0;
                lastNanos = now;

                glowAnimPhase += delta;

                TransportState state = host.transportState();
                timeTickerAnimator.tick(now);
                transportGlowAnimator.apply(state, glowAnimPhase);

                if (playheadUpdateCallback != null) {
                    playheadUpdateCallback.run();
                }

                idleVisualizationAnimator.tick(delta);
            }
        };
        mainAnimTimer.start();
    }

    /** Stops the animation timer. */
    void stop() {
        if (mainAnimTimer != null) {
            mainAnimTimer.stop();
        }
    }

    /**
     * Sets a callback invoked each animation frame to update the playhead
     * position in the arrangement view from the transport's current beat.
     *
     * @param callback the callback to invoke, or {@code null} to clear
     */
    void setPlayheadUpdateCallback(Runnable callback) {
        this.playheadUpdateCallback = callback;
    }

    // ── Time ticker ──────────────────────────────────────────────────────────

    /** Starts the time ticker from zero (or resumes from a paused position). */
    void startTimeTicker() {
        timeTickerAnimator.start();
    }

    /** Pauses the time ticker, preserving elapsed time for clean resume. */
    void pauseTimeTicker() {
        timeTickerAnimator.pause();
    }

    /** Stops and resets the time ticker. */
    void stopTimeTicker() {
        timeTickerAnimator.stop();
    }

    /**
     * Formats elapsed nanoseconds into a {@code HH:MM:SS.t} display string.
     *
     * <p>Retained as a static helper for backward compatibility with
     * existing tests; delegates to {@link TimeTickerAnimator#formatTime}.</p>
     *
     * @param elapsedNanos elapsed time in nanoseconds
     * @return formatted time string
     */
    static String formatTime(long elapsedNanos) {
        return TimeTickerAnimator.formatTime(elapsedNanos);
    }

    // ── Button press animations ──────────────────────────────────────────────

    /**
     * Adds a scale-bounce press/release animation to every animated button so
     * clicks feel tactile and immediate.
     */
    void applyButtonPressAnimations() {
        buttonPressAnimator.install();
    }
}
