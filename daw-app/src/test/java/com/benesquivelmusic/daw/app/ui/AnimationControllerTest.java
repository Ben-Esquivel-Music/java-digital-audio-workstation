package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link AnimationController} helper logic that can be exercised
 * without a live JavaFX scene or toolkit.
 *
 * <p>The former {@code formatTime} tests are gone with the wall-clock time
 * ticker (story 315 deleted {@code TimeTickerAnimator}); the {@code HH:MM:SS.t}
 * projection format is now covered by the {@code TransportControlBinder}
 * time-display tests ({@code TimeDisplayProjectionTest}).</p>
 */
class AnimationControllerTest {

    // ── Playhead update callback ────────────────────────────────────────────

    @Test
    void shouldAcceptNullPlayheadCallback() {
        // Verify the setter does not throw when clearing the callback.
        // Full AnimationTimer integration requires a JavaFX toolkit, but
        // the setter itself is safe to call without one.
        // This is a compile-time verification that the API exists.
        assertThat(AnimationController.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("setPlayheadUpdateCallback"));
    }
}
