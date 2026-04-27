package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.transport.TransportState;

import javafx.scene.control.Button;

import java.util.Objects;

/**
 * Drives the pulsing-glow effect on the play button while playing and
 * the blink/glow effect on the record button while recording.
 *
 * <p>Extracted from {@link AnimationController} so the per-frame style
 * computation can be reasoned about and unit tested without spinning up
 * the full {@code AnimationTimer}. The pure-math helpers
 * ({@link #playGlowStyle}, {@link #recordGlowStyle},
 * {@link #recordBlinkOpacity}) are package-private and deterministic
 * given a phase value.</p>
 *
 * <p>Issue: "Decompose Remaining God-Class Controllers into Focused
 * Services."</p>
 */
final class TransportGlowAnimator {

    private final Button playButton;
    private final Button recordButton;

    TransportGlowAnimator(Button playButton, Button recordButton) {
        this.playButton = Objects.requireNonNull(playButton, "playButton must not be null");
        this.recordButton = Objects.requireNonNull(recordButton, "recordButton must not be null");
    }

    /**
     * Applies the appropriate glow/blink style to the play and record
     * buttons for the given transport state and animation phase.
     *
     * @param state       the current transport state
     * @param phaseSeconds the accumulated animation phase, in seconds
     */
    void apply(TransportState state, double phaseSeconds) {
        switch (state) {
            case PLAYING -> {
                playButton.setStyle(playGlowStyle(phaseSeconds));
                recordButton.setOpacity(1.0);
                recordButton.setStyle("");
            }
            case RECORDING -> {
                recordButton.setOpacity(recordBlinkOpacity(phaseSeconds));
                recordButton.setStyle(recordGlowStyle(phaseSeconds));
                playButton.setStyle("");
            }
            default -> {
                playButton.setStyle("");
                recordButton.setOpacity(1.0);
                recordButton.setStyle("");
            }
        }
    }

    /** Returns the play-button drop-shadow style for the given phase. */
    static String playGlowStyle(double phaseSeconds) {
        double pulse = 0.5 + 0.5 * Math.sin(phaseSeconds * Math.PI * 1.4);
        double radius = 8 + pulse * 14;
        double spread = 0.05 + pulse * 0.25;
        return String.format(
                "-fx-effect: dropshadow(gaussian, #00e676, %.1f, %.2f, 0, 0);",
                radius, spread);
    }

    /** Returns the record-button drop-shadow style for the given phase. */
    static String recordGlowStyle(double phaseSeconds) {
        double blink = 0.5 + 0.5 * Math.sin(phaseSeconds * Math.PI * 4.0);
        double glowRadius = 8 + blink * 16;
        double glowSpread = 0.1 + blink * 0.3;
        return String.format(
                "-fx-effect: dropshadow(gaussian, #ff1744, %.1f, %.2f, 0, 0);",
                glowRadius, glowSpread);
    }

    /** Returns the record-button blink opacity for the given phase. */
    static double recordBlinkOpacity(double phaseSeconds) {
        double blink = 0.5 + 0.5 * Math.sin(phaseSeconds * Math.PI * 4.0);
        return 0.4 + blink * 0.6;
    }
}
