package com.benesquivelmusic.daw.app.ui;

import javafx.scene.control.Label;

import java.util.Objects;

/**
 * Tracks elapsed transport time and refreshes a JavaFX {@link Label} each
 * animation frame.
 *
 * <p>Extracted from {@link AnimationController} so the start / pause /
 * stop / format flow can be unit tested without a JavaFX animation
 * timer. {@link #formatTime(long)} remains a pure static helper.</p>
 *
 * <p>Issue: "Decompose Remaining God-Class Controllers into Focused
 * Services."</p>
 */
final class TimeTickerAnimator {

    private final Label timeDisplay;

    private long startNanos;
    private long pausedElapsedNanos;
    private boolean running;

    TimeTickerAnimator(Label timeDisplay) {
        this.timeDisplay = Objects.requireNonNull(timeDisplay, "timeDisplay must not be null");
    }

    /** Starts (or resumes) the ticker. */
    void start() {
        startNanos = System.nanoTime();
        running = true;
    }

    /** Pauses the ticker, preserving elapsed time for clean resume. */
    void pause() {
        if (running) {
            pausedElapsedNanos += System.nanoTime() - startNanos;
            running = false;
        }
    }

    /** Stops and resets the ticker. */
    void stop() {
        running = false;
        pausedElapsedNanos = 0;
    }

    /** Returns whether the ticker is currently running. */
    boolean isRunning() {
        return running;
    }

    /**
     * Updates the display label from the running elapsed time using the
     * given absolute "now" nanosecond reading.
     */
    void tick(long nowNanos) {
        if (running) {
            long elapsed = pausedElapsedNanos + (nowNanos - startNanos);
            timeDisplay.setText(formatTime(elapsed));
        }
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
}
