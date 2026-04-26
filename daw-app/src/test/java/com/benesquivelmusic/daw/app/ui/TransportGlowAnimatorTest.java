package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TransportGlowAnimator} pure-math helpers. These
 * assertions were not possible before extraction because the styling
 * logic was tangled inside an {@code AnimationTimer.handle} callback
 * that required a live JavaFX toolkit.
 */
class TransportGlowAnimatorTest {

    @Test
    void playGlowStyleIsADropShadowWithGreenColor() {
        String style = TransportGlowAnimator.playGlowStyle(0.0);
        assertThat(style).startsWith("-fx-effect: dropshadow(gaussian, #00e676,");
        assertThat(style).endsWith(");");
    }

    @Test
    void recordGlowStyleIsADropShadowWithRedColor() {
        String style = TransportGlowAnimator.recordGlowStyle(0.0);
        assertThat(style).startsWith("-fx-effect: dropshadow(gaussian, #ff1744,");
        assertThat(style).endsWith(");");
    }

    @Test
    void recordBlinkOpacityStaysWithinDocumentedRange() {
        // The blink envelope is 0.4 + sin(...) * 0.6 → range [0.4, 1.0].
        for (int i = 0; i < 1000; i++) {
            double phase = i * 0.01;
            double opacity = TransportGlowAnimator.recordBlinkOpacity(phase);
            assertThat(opacity).isBetween(0.4 - 1e-9, 1.0 + 1e-9);
        }
    }

    @Test
    void recordBlinkOpacityIsDeterministicForAFixedPhase() {
        double a = TransportGlowAnimator.recordBlinkOpacity(2.71828);
        double b = TransportGlowAnimator.recordBlinkOpacity(2.71828);
        assertThat(a).isEqualTo(b);
    }
}
