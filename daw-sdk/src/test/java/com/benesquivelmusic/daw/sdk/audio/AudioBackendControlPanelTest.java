package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@link AudioBackend#openControlPanel()} contract from
 * the "Launch Native Audio Driver Control Panel from Audio Settings"
 * story:
 *
 * <ul>
 *   <li>backends without a vendor panel return {@link Optional#empty()};</li>
 *   <li>backends with a panel return a non-null runnable but only when
 *       the host platform supports the underlying driver;</li>
 *   <li>{@link MockAudioBackend} records every invocation so the
 *       {@code AudioSettingsDialog} test can assert it re-queried
 *       device capabilities afterwards.</li>
 * </ul>
 */
class AudioBackendControlPanelTest {

    @Test
    void jackBackendHasNoNativeControlPanel() {
        // qjackctl is third-party and out of scope; JackBackend never
        // exposes a vendor panel.
        assertTrue(new JackBackend().openControlPanel().isEmpty());
    }

    @Test
    void javaxSoundBackendHasNoNativeControlPanel() {
        // The JDK mixer has no vendor UI.
        assertTrue(new JavaxSoundBackend().openControlPanel().isEmpty());
    }

    @Test
    void asioBackendIsAlwaysEmptyUntilNativeBridgeWired() {
        // The native ASIOControlPanel() bridge is not yet wired, so the
        // backend must always return empty regardless of platform. When
        // the FFM downcall is implemented this test should be updated.
        AsioBackend asio = new AsioBackend();
        assertTrue(asio.openControlPanel().isEmpty());
    }

    @Test
    void wasapiBackendExposesPanelOnlyOnWindows() {
        WasapiBackend wasapi = new WasapiBackend();
        Optional<Runnable> panel = wasapi.openControlPanel();
        if (wasapi.isAvailable()) {
            assertTrue(panel.isPresent());
            assertNotNull(panel.get());
        } else {
            assertTrue(panel.isEmpty());
        }
    }

    @Test
    void coreAudioBackendExposesPanelOnlyOnMac() {
        CoreAudioBackend coreAudio = new CoreAudioBackend();
        Optional<Runnable> panel = coreAudio.openControlPanel();
        if (coreAudio.isAvailable()) {
            assertTrue(panel.isPresent());
            assertNotNull(panel.get());
        } else {
            assertTrue(panel.isEmpty());
        }
    }

    @Test
    void mockBackendRunnableIncrementsInvocationCount() {
        MockAudioBackend mock = new MockAudioBackend();
        Runnable r = mock.openControlPanel().orElseThrow();
        r.run();
        r.run();
        r.run();
        assertEquals(3, mock.controlPanelInvocationCount());
    }
}
