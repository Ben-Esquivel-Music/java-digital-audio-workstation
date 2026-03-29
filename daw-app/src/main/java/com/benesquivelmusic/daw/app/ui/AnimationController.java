package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.LevelMeterDisplay;
import com.benesquivelmusic.daw.app.ui.display.SpectrumDisplay;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.sdk.visualization.LevelData;
import com.benesquivelmusic.daw.sdk.visualization.SpectrumData;

import javafx.animation.AnimationTimer;
import javafx.animation.Interpolator;
import javafx.animation.ScaleTransition;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.util.Duration;

import java.util.Objects;

/**
 * Encapsulates all frame-by-frame and transition-based animations for the
 * main DAW window: the idle visualization demo (synthetic spectrum and
 * level-meter data), the transport-state glow/blink on the play and record
 * buttons, the elapsed-time display ticker, and the button-press scale
 * animations.
 *
 * <p>Extracted from {@link MainController} to isolate animation bookkeeping
 * into a dedicated, independently testable class. All dependencies are
 * received via constructor injection.</p>
 */
final class AnimationController {

    /**
     * Callback interface implemented by the host controller to provide
     * dynamic state that remains in the top-level controller.
     */
    interface Host {
        TransportState transportState();
    }

    private static final int IDLE_FFT_SIZE = 1024;

    private final SpectrumDisplay spectrumDisplay;
    private final LevelMeterDisplay levelMeterDisplay;
    private final Label timeDisplay;
    private final Button playButton;
    private final Button recordButton;
    private final Button[] animatedButtons;
    private final Host host;

    private AnimationTimer mainAnimTimer;
    private double idleAnimPhase;
    private double glowAnimPhase;
    private final float[] idleSpectrumBins = new float[IDLE_FFT_SIZE / 2];

    private long timeTickerStartNanos;
    private boolean timeTickerRunning;
    private long timeTickerPausedElapsedNanos;

    AnimationController(SpectrumDisplay spectrumDisplay,
                        LevelMeterDisplay levelMeterDisplay,
                        Label timeDisplay,
                        Button playButton,
                        Button recordButton,
                        Button[] animatedButtons,
                        Host host) {
        this.spectrumDisplay = Objects.requireNonNull(spectrumDisplay, "spectrumDisplay must not be null");
        this.levelMeterDisplay = Objects.requireNonNull(levelMeterDisplay, "levelMeterDisplay must not be null");
        this.timeDisplay = Objects.requireNonNull(timeDisplay, "timeDisplay must not be null");
        this.playButton = Objects.requireNonNull(playButton, "playButton must not be null");
        this.recordButton = Objects.requireNonNull(recordButton, "recordButton must not be null");
        this.animatedButtons = Objects.requireNonNull(animatedButtons, "animatedButtons must not be null");
        this.host = Objects.requireNonNull(host, "host must not be null");
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Creates and starts the single {@link AnimationTimer} that drives all
     * continuous frame-by-frame animations: idle visualization demo, transport
     * glow, and the time-display ticker.
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

                // Advance animation phases
                idleAnimPhase += delta;
                glowAnimPhase += delta;

                TransportState state = host.transportState();

                // Time ticker: update time display while playing or recording
                tickTimeDisplay(now);

                // Transport glow on play and record buttons
                applyTransportGlow(state);

                // Idle visualization (always runs to keep displays alive)
                tickIdleVisualization(delta);
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

    // ── Time ticker ──────────────────────────────────────────────────────────

    /** Starts the time ticker from zero (or resumes from a paused position). */
    void startTimeTicker() {
        timeTickerStartNanos = System.nanoTime();
        timeTickerRunning = true;
    }

    /** Pauses the time ticker, preserving elapsed time for clean resume. */
    void pauseTimeTicker() {
        if (timeTickerRunning) {
            timeTickerPausedElapsedNanos += System.nanoTime() - timeTickerStartNanos;
            timeTickerRunning = false;
        }
    }

    /** Stops and resets the time ticker. */
    void stopTimeTicker() {
        timeTickerRunning = false;
        timeTickerPausedElapsedNanos = 0;
    }

    /**
     * Called each frame by the animation timer to update the time display
     * while the transport is playing or recording.
     */
    private void tickTimeDisplay(long nowNanos) {
        if (timeTickerRunning) {
            long elapsedNanos = timeTickerPausedElapsedNanos + (nowNanos - timeTickerStartNanos);
            refreshTimeDisplay(elapsedNanos);
        }
    }

    /** Updates the time display label from the given elapsed nanosecond count. */
    private void refreshTimeDisplay(long elapsedNanos) {
        timeDisplay.setText(formatTime(elapsedNanos));
    }

    /**
     * Formats elapsed nanoseconds into a {@code HH:MM:SS.t} display string.
     *
     * @param elapsedNanos elapsed time in nanoseconds
     * @return formatted time string
     */
    static String formatTime(long elapsedNanos) {
        long elapsedMs = elapsedNanos / 1_000_000L;
        long tenths = (elapsedMs % 1000) / 100;
        long totalSeconds = elapsedMs / 1000;
        long minutes = totalSeconds / 60;
        long hours = minutes / 60;
        return String.format("%02d:%02d:%02d.%d",
                hours, minutes % 60, totalSeconds % 60, tenths);
    }

    // ── Transport glow ───────────────────────────────────────────────────────

    /**
     * Applies a pulsing glow to the play button while playing and a blink
     * to the record button while recording.
     */
    private void applyTransportGlow(TransportState state) {
        if (state == TransportState.PLAYING) {
            double pulse = 0.5 + 0.5 * Math.sin(glowAnimPhase * Math.PI * 1.4);
            double radius = 8 + pulse * 14;
            double spread = 0.05 + pulse * 0.25;
            playButton.setStyle(String.format(
                    "-fx-effect: dropshadow(gaussian, #00e676, %.1f, %.2f, 0, 0);",
                    radius, spread));
            recordButton.setOpacity(1.0);
            recordButton.setStyle("");
        } else if (state == TransportState.RECORDING) {
            // Blink record button: full opacity <-> dim, at ~2 Hz
            double blink = 0.5 + 0.5 * Math.sin(glowAnimPhase * Math.PI * 4.0);
            double opacity = 0.4 + blink * 0.6;
            recordButton.setOpacity(opacity);
            double glowRadius = 8 + blink * 16;
            double glowSpread = 0.1 + blink * 0.3;
            recordButton.setStyle(String.format(
                    "-fx-effect: dropshadow(gaussian, #ff1744, %.1f, %.2f, 0, 0);",
                    glowRadius, glowSpread));
            playButton.setStyle("");
        } else {
            playButton.setStyle("");
            recordButton.setOpacity(1.0);
            recordButton.setStyle("");
        }
    }

    // ── Idle visualization ───────────────────────────────────────────────────

    /**
     * Generates synthetic spectrum and level data for the idle demo animation so
     * the visualization displays stay visually alive when no audio is being processed.
     */
    private void tickIdleVisualization(double deltaSeconds) {
        // ── Spectrum: pink-noise shape with gentle wobble ──────────────────
        int binCount = idleSpectrumBins.length;
        for (int i = 1; i < binCount; i++) {
            // Logarithmic position: 0.0 (low) → 1.0 (high)
            double t = Math.log((double) i / binCount + 1.0) / Math.log(2.0);
            // Pink-noise baseline: gentle downward slope
            double base = -28.0 - t * 30.0;
            // Slow wobble across the frequency range
            double wobble = 7.0 * Math.sin(idleAnimPhase * 0.9 + t * 5.5);
            // Low-mid bump that breathes
            double bump = 5.0 * Math.exp(-Math.pow((t - 0.25), 2) / 0.01)
                    * (0.5 + 0.5 * Math.sin(idleAnimPhase * 0.6));
            idleSpectrumBins[i] = (float) Math.max(-90.0, base + wobble + bump);
        }
        idleSpectrumBins[0] = idleSpectrumBins[1];
        spectrumDisplay.updateSpectrum(new SpectrumData(idleSpectrumBins, IDLE_FFT_SIZE, 44100.0));

        // ── Level meter: gentle breathing RMS with occasional peaks ──────
        double rmsLinear = 0.18 + 0.12 * Math.abs(Math.sin(idleAnimPhase * 0.75));
        double peakBoost = 1.0 + 0.25 * Math.abs(Math.sin(idleAnimPhase * 1.8));
        double peakLinear = Math.min(rmsLinear * peakBoost * 1.3, 0.85);
        double dbRms = 20.0 * Math.log10(Math.max(rmsLinear, 1e-9));
        double dbPeak = 20.0 * Math.log10(Math.max(peakLinear, 1e-9));
        levelMeterDisplay.update(
                new LevelData(peakLinear, rmsLinear, dbPeak, dbRms, false),
                (long) (deltaSeconds * 1_000_000_000L));
    }

    // ── Button press animations ──────────────────────────────────────────────

    /**
     * Adds a scale-bounce press/release animation to every animated button so
     * clicks feel tactile and immediate.
     */
    void applyButtonPressAnimations() {
        for (Button btn : animatedButtons) {
            applyPressAnimation(btn);
        }
    }

    /**
     * Attaches a subtle scale-down-then-spring-back animation to a single button.
     */
    private void applyPressAnimation(Button btn) {
        ScaleTransition pressDown = new ScaleTransition(Duration.millis(70), btn);
        pressDown.setToX(0.90);
        pressDown.setToY(0.90);
        pressDown.setInterpolator(Interpolator.EASE_IN);

        ScaleTransition springBack = new ScaleTransition(Duration.millis(130), btn);
        springBack.setToX(1.0);
        springBack.setToY(1.0);
        springBack.setInterpolator(Interpolator.EASE_OUT);

        btn.setOnMousePressed(_ -> {
            springBack.stop();
            pressDown.playFromStart();
        });
        btn.setOnMouseReleased(_ -> {
            pressDown.stop();
            springBack.playFromStart();
        });
    }
}
