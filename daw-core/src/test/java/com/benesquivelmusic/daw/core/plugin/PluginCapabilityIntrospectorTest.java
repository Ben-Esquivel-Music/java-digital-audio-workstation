package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.CompressorProcessor;
import com.benesquivelmusic.daw.core.dsp.DelayProcessor;
import com.benesquivelmusic.daw.core.dsp.GainReductionProvider;
import com.benesquivelmusic.daw.core.dsp.LimiterProcessor;
import com.benesquivelmusic.daw.core.dsp.ReverbProcessor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link PluginCapabilityIntrospector} and the
 * {@link PluginCapabilities} it produces.
 */
class PluginCapabilityIntrospectorTest {

    private static final double SAMPLE_RATE = 48_000.0;

    @BeforeEach
    void clearCache() {
        PluginCapabilityIntrospector.clearCache();
    }

    @Test
    void compressorReportsSidechainAndGainReduction() {
        var compressor = new CompressorProcessor(2, SAMPLE_RATE);

        PluginCapabilities caps = PluginCapabilityIntrospector.capabilitiesOf(compressor);

        assertThat(caps.processesAudio()).isTrue();
        assertThat(caps.providesSidechainInput()).isTrue();
        assertThat(caps.reportsGainReduction()).isTrue();
        assertThat(caps.realTimeSafeProcess()).isTrue();
        assertThat(caps.genericallyConstructible()).isTrue();
        assertThat(caps.parameterCount()).isPositive();
    }

    @Test
    void reverbReportsNoSidechainAndNoGainReduction() {
        var reverb = new ReverbProcessor(2, SAMPLE_RATE);

        PluginCapabilities caps = PluginCapabilityIntrospector.capabilitiesOf(reverb);

        assertThat(caps.processesAudio()).isTrue();
        assertThat(caps.providesSidechainInput()).isFalse();
        assertThat(caps.reportsGainReduction()).isFalse();
        assertThat(caps.reportsLatency()).isFalse();
        assertThat(caps.genericallyConstructible()).isTrue();
    }

    @Test
    void limiterReportsLatencyAndGainReduction() {
        var limiter = new LimiterProcessor(2, SAMPLE_RATE);

        PluginCapabilities caps = PluginCapabilityIntrospector.capabilitiesOf(limiter);

        assertThat(caps.reportsLatency()).isTrue();
        assertThat(caps.reportsGainReduction()).isTrue();
        assertThat(caps.providesSidechainInput()).isFalse();
    }

    @Test
    void capabilitiesAreCachedPerClass() {
        var compressor1 = new CompressorProcessor(2, SAMPLE_RATE);
        var compressor2 = new CompressorProcessor(1, SAMPLE_RATE);

        PluginCapabilities first = PluginCapabilityIntrospector.capabilitiesOf(compressor1);
        PluginCapabilities second = PluginCapabilityIntrospector.capabilitiesOf(compressor2);

        // Same class -> same cached instance (reference equality, not just equals)
        assertThat(second).isSameAs(first);
    }

    @Test
    void nullProcessorReturnsNoneCapabilities() {
        PluginCapabilities caps = PluginCapabilityIntrospector.capabilitiesOf((AudioProcessor) null);

        assertThat(caps).isSameAs(PluginCapabilities.NONE);
        assertThat(caps.processesAudio()).isFalse();
        assertThat(caps.customCapabilities()).isEmpty();
    }

    @Test
    void delayProcessorHasAudioCapability() {
        // Sanity-check another non-dynamics processor to confirm flags are class-specific.
        var delay = new DelayProcessor(2, SAMPLE_RATE);
        PluginCapabilities caps = PluginCapabilityIntrospector.capabilitiesOf(delay);

        assertThat(caps.processesAudio()).isTrue();
        assertThat(caps.reportsGainReduction()).isFalse();
        assertThat(caps.providesSidechainInput()).isFalse();
    }

    @Test
    void customCapabilitiesAnnotationIsDiscovered() {
        PluginCapabilities caps =
                PluginCapabilityIntrospector.capabilitiesOf(TaggedProcessor.class);

        assertThat(caps.customCapabilities()).containsExactlyInAnyOrder("oversampled", "linearPhase");
        assertThat(caps.hasCustomCapability("oversampled")).isTrue();
        assertThat(caps.hasCustomCapability("unknown")).isFalse();
    }

    @Test
    void noneCapabilitiesIsImmutable() {
        assertThat(PluginCapabilities.NONE.customCapabilities()).isEmpty();
        // Record's defensive copy in the canonical constructor must return an unmodifiable set.
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> PluginCapabilities.NONE.customCapabilities().add("x"));
    }

    /** Fixture class used to verify {@link ProcessorCapability} annotation discovery. */
    @ProcessorCapability("oversampled")
    @ProcessorCapability("linearPhase")
    private static final class TaggedProcessor implements AudioProcessor, GainReductionProvider {
        @Override public void process(float[][] in, float[][] out, int n) { }
        @Override public void reset() { }
        @Override public int getInputChannelCount() { return 2; }
        @Override public int getOutputChannelCount() { return 2; }
        @Override public double getGainReductionDb() { return 0.0; }
    }
}
