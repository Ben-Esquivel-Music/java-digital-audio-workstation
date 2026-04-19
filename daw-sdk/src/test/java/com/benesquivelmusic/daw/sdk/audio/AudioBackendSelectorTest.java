package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioBackendSelectorTest {

    @Test
    void windowsPreferenceOrder() {
        List<String> order = AudioBackendSelector.preferenceOrder("Windows 11");
        assertEquals(List.of("ASIO", "WASAPI", "Java Sound"), order);
    }

    @Test
    void macOsPreferenceOrder() {
        List<String> order = AudioBackendSelector.preferenceOrder("Mac OS X");
        assertEquals(List.of("CoreAudio", "Java Sound"), order);
    }

    @Test
    void linuxPreferenceOrder() {
        List<String> order = AudioBackendSelector.preferenceOrder("Linux");
        assertEquals(List.of("JACK", "Java Sound"), order);
    }

    @Test
    void unknownOsFallsBackToJavaSound() {
        List<String> order = AudioBackendSelector.preferenceOrder("Haiku");
        assertEquals(List.of("Java Sound"), order);
    }

    @Test
    void javaSoundIsAlwaysInAvailableList() {
        AudioBackendSelector selector = new AudioBackendSelector();
        List<String> available = selector.availableBackends();
        assertTrue(available.contains("Java Sound"), available.toString());
    }

    @Test
    void openWithFallbackFallsBackWhenPreferredBackendIsUnavailable() {
        // Remap "Java Sound" to a MockAudioBackend so this test stays headless
        // and does not try to talk to a real audio device on CI.
        Map<String, Supplier<AudioBackend>> factories = new LinkedHashMap<>();
        factories.put("ASIO", AsioBackend::new); // unavailable on Linux CI
        factories.put("Java Sound", MockAudioBackend::new);

        AudioBackendSelector selector = new AudioBackendSelector(factories);
        AudioBackend opened = selector.openWithFallback(
                "ASIO", DeviceId.defaultFor("ASIO"), AudioFormat.CD_QUALITY, 128);

        assertInstanceOf(MockAudioBackend.class, opened);
        assertTrue(opened.isOpen());
        opened.close();
    }

    @Test
    void openWithFallbackUsesPreferredWhenAvailable() {
        Map<String, Supplier<AudioBackend>> factories = new LinkedHashMap<>();
        factories.put("Mock", MockAudioBackend::new);
        factories.put("Java Sound", MockAudioBackend::new);

        AudioBackendSelector selector = new AudioBackendSelector(factories);
        AudioBackend opened = selector.openWithFallback(
                "Mock", DeviceId.defaultFor("Mock"), AudioFormat.CD_QUALITY, 128);

        assertEquals("Mock", opened.name());
        assertTrue(opened.isOpen());
        opened.close();
    }
}
