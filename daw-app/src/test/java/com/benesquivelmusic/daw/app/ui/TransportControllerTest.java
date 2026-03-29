package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link TransportController} helper logic that can be exercised
 * without a live JavaFX scene or toolkit.
 *
 * <p>The {@code formatTime} tests have been moved to {@link AnimationControllerTest}
 * after the time-ticker logic was extracted into {@link AnimationController}.</p>
 */
class TransportControllerTest {

    @Test
    void shouldCreateTransportControllerClass() {
        // Verify the TransportController class is loadable and its Host interface is accessible
        Class<?> hostClass = TransportController.Host.class;
        org.assertj.core.api.Assertions.assertThat(hostClass).isNotNull();
        org.assertj.core.api.Assertions.assertThat(hostClass.isInterface()).isTrue();
    }
}
