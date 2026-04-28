package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the per-backend overrides of {@link AudioBackend#bufferSizeRange(DeviceId)}
 * and {@link AudioBackend#supportedSampleRates(DeviceId)} introduced for
 * story 213. These verify the documented per-backend conventions:
 * WASAPI shared vs exclusive, JACK singleton, and the configurable mock.
 */
class AudioBackendCapabilityQueriesTest {

    private static final DeviceId DEFAULT_DEVICE = DeviceId.defaultFor("test");

    @Test
    void wasapiSharedModeReportsSingletonRangeAndRate() {
        // WASAPI shared mode is fixed at the OS mixer's period and rate;
        // the dialog dropdown collapses to a single entry per story 213.
        WasapiBackend shared = new WasapiBackend(false);
        BufferSizeRange range = shared.bufferSizeRange(DEFAULT_DEVICE);
        assertThat(range.expandedSizes()).hasSize(1);
        assertThat(shared.supportedSampleRates(DEFAULT_DEVICE)).hasSize(1);
    }

    @Test
    void wasapiExclusiveModeReportsGranularRangeAndCanonicalRates() {
        // Exclusive mode lets the application pick from a granular ladder
        // of buffer sizes and the full canonical rate list.
        WasapiBackend exclusive = new WasapiBackend(true);
        BufferSizeRange range = exclusive.bufferSizeRange(DEFAULT_DEVICE);
        assertThat(range.expandedSizes().size()).isGreaterThan(1);
        assertThat(exclusive.supportedSampleRates(DEFAULT_DEVICE))
                .contains(44_100, 48_000, 96_000, 192_000);
    }

    @Test
    void jackReportsServerWideSingletonsForBothBufferAndRate() {
        // JACK clients cannot pick their own buffer size or sample rate
        // — the server picks one server-wide value for every client.
        JackBackend jack = new JackBackend();
        assertThat(jack.bufferSizeRange(DEFAULT_DEVICE).granularity()).isZero();
        assertThat(jack.bufferSizeRange(DEFAULT_DEVICE).expandedSizes()).hasSize(1);
        assertThat(jack.supportedSampleRates(DEFAULT_DEVICE)).hasSize(1);
    }

    @Test
    void mockBackendShouldReturnConfiguredRangeAndRates() {
        // The mock's setters are how tests drive the dialog through
        // arbitrary driver-reported capability shapes.
        MockAudioBackend mock = new MockAudioBackend();
        BufferSizeRange custom = new BufferSizeRange(96, 384, 192, 96);
        mock.setBufferSizeRange(custom);
        mock.setSupportedSampleRates(Set.of(44_100, 48_000));

        assertThat(mock.bufferSizeRange(DEFAULT_DEVICE)).isEqualTo(custom);
        assertThat(mock.bufferSizeRange(DEFAULT_DEVICE).expandedSizes())
                .containsExactly(96, 192, 288, 384);
        assertThat(mock.supportedSampleRates(DEFAULT_DEVICE))
                .containsExactlyInAnyOrder(44_100, 48_000);
    }

    @Test
    void defaultBackendBehaviourPreservesHistoricalMenu() {
        // Any backend that does not override the methods inherits the
        // canonical defaults so persisted settings keep working.
        JavaxSoundBackend java = new JavaxSoundBackend();
        assertThat(java.bufferSizeRange(DEFAULT_DEVICE))
                .isEqualTo(BufferSizeRange.DEFAULT_RANGE);
        assertThat(java.supportedSampleRates(DEFAULT_DEVICE))
                .contains(44_100, 48_000, 88_200, 96_000, 176_400, 192_000);
    }
}
