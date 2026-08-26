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
    void selectByNameReturnsMatchingSdkBackendInstance() {
        AudioBackendSelector selector = new AudioBackendSelector();
        // Mock is always registered and always available; using it keeps
        // this assertion deterministic across Windows / macOS / Linux CI.
        AudioBackend backend = selector.selectByName(MockAudioBackend.NAME);
        assertInstanceOf(MockAudioBackend.class, backend);
        assertEquals(MockAudioBackend.NAME, backend.name());
        backend.close();
    }

    @Test
    void selectByNameReturnsNullForUnknownName() {
        AudioBackendSelector selector = new AudioBackendSelector();
        assertEquals(null, selector.selectByName("NotARealBackend"));
        assertEquals(null, selector.selectByName(""));
        assertEquals(null, selector.selectByName(null));
    }

    @Test
    void availableBackendsExcludesAvailableButNonStreamingBackends() {
        // Story 316: an available backend whose streaming path is not
        // implemented must not be offered — it would open a silent stream.
        List<String> order = new AudioBackendSelector().preferenceOrderForCurrentOs();
        Map<String, Supplier<AudioBackend>> factories = new LinkedHashMap<>();
        for (String name : order) {
            factories.put(name, () -> new AvailableNonStreamingBackend(name));
        }
        // The Java Sound rung streams (Mock stands in: supportsStreaming true).
        factories.put("Java Sound", MockAudioBackend::new);

        AudioBackendSelector selector = new AudioBackendSelector(factories);
        assertEquals(List.of("Java Sound"), selector.availableBackends());
        assertEquals(List.of("Java Sound"), selector.availableBackendNames());
        assertEquals("Java Sound", selector.defaultBackendName());
    }

    @Test
    void defaultBackendNameReturnsTheFirstStreamingRung() {
        List<String> order = new AudioBackendSelector().preferenceOrderForCurrentOs();
        String head = order.get(0);
        Map<String, Supplier<AudioBackend>> factories = new LinkedHashMap<>();
        factories.put(head, MockAudioBackend::new); // available AND streaming
        factories.put("Java Sound", MockAudioBackend::new);

        AudioBackendSelector selector = new AudioBackendSelector(factories);
        assertEquals(head, selector.defaultBackendName());
        assertTrue(selector.availableBackends().contains(head),
                selector.availableBackends().toString());
    }

    /**
     * Available-but-non-streaming {@link AudioBackend} test double. Being a
     * class outside the historical permitted set, it is also compile-level
     * proof that story 316 unsealed the interface. It deliberately does NOT
     * override {@code supportsStreaming()}, so it exercises the inherited
     * {@code false} default the selector's streaming gate keys on.
     */
    private static final class AvailableNonStreamingBackend implements AudioBackend {
        private final String name;

        private AvailableNonStreamingBackend(String name) {
            this.name = name;
        }

        @Override public String name() { return name; }
        @Override public boolean isAvailable() { return true; }
        @Override public List<AudioDeviceInfo> listDevices() { return List.of(); }
        @Override public void open(DeviceId device, AudioFormat format, int bufferFrames) { }
        @Override public java.util.concurrent.Flow.Publisher<AudioBlock> inputBlocks() {
            return subscriber -> { };
        }
        @Override public void sink(AudioBlock block) { }
        @Override public boolean isOpen() { return false; }
        @Override public void close() { }
    }

    @Test
    void availableBackendNamesRoundTripsThroughSelectByName() {
        // Story 130: the combo values produced by availableBackendNames()
        // (consumed by AudioSettingsDialog) must be the same set the
        // controller can instantiate. Round-trip every entry.
        AudioBackendSelector selector = new AudioBackendSelector();
        for (String name : selector.availableBackendNames()) {
            AudioBackend backend = selector.selectByName(name);
            assertTrue(backend != null,
                    "selectByName must produce an instance for " + name);
            assertEquals(name, backend.name(),
                    "round-trip name mismatch for " + name);
            backend.close();
        }
    }
}
