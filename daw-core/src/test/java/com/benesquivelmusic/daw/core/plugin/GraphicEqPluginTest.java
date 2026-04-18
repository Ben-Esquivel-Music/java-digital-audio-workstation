package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.GraphicEqProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GraphicEqPluginTest {

    @Test
    void shouldHavePublicNoArgConstructor() {
        GraphicEqPlugin plugin = new GraphicEqPlugin();
        assertThat(plugin).isNotNull();
    }

    @Test
    void shouldReturnMenuLabel() {
        assertThat(new GraphicEqPlugin().getMenuLabel()).isEqualTo("Graphic EQ");
    }

    @Test
    void shouldReturnMenuIcon() {
        assertThat(new GraphicEqPlugin().getMenuIcon()).isEqualTo("eq");
    }

    @Test
    void shouldReturnEffectCategory() {
        assertThat(new GraphicEqPlugin().getCategory()).isEqualTo(BuiltInPluginCategory.EFFECT);
    }

    @Test
    void shouldReturnDescriptorWithEffectType() {
        var descriptor = new GraphicEqPlugin().getDescriptor();
        assertThat(descriptor.type()).isEqualTo(PluginType.EFFECT);
        assertThat(descriptor.name()).isEqualTo("Graphic EQ");
        assertThat(descriptor.id()).isNotBlank();
        assertThat(descriptor.vendor()).isEqualTo("DAW Built-in");
    }

    @Test
    void shouldImplementDawPluginLifecycle() {
        var plugin = new GraphicEqPlugin();
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.deactivate();
        plugin.dispose();
    }

    @Test
    void shouldReturnProcessorAfterInitialize() {
        var plugin = new GraphicEqPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.getProcessor()).isNotNull();
        assertThat(plugin.asAudioProcessor()).isPresent();
    }

    @Test
    void shouldReturnProcessorOfCorrectType() {
        var plugin = new GraphicEqPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.getProcessor()).isInstanceOf(GraphicEqProcessor.class);
    }

    @Test
    void asAudioProcessorShouldReturnSameInstanceAsGetProcessor() {
        var plugin = new GraphicEqPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.asAudioProcessor().orElseThrow()).isSameAs(plugin.getProcessor());
    }

    @Test
    void shouldReturnEmptyProcessorBeforeInitialize() {
        var plugin = new GraphicEqPlugin();
        assertThat(plugin.asAudioProcessor()).isEmpty();
    }

    @Test
    void shouldClearProcessorOnDispose() {
        var plugin = new GraphicEqPlugin();
        plugin.initialize(stubContext());
        plugin.dispose();
        assertThat(plugin.asAudioProcessor()).isEmpty();
    }

    @Test
    void shouldExposeTenParametersForOctaveModeBeforeInitialize() {
        var plugin = new GraphicEqPlugin();
        // Before initialize, defaults to 10-band octave layout
        assertThat(plugin.getParameters())
                .isNotNull()
                .hasSize(10)
                .isUnmodifiable();
    }

    @Test
    void shouldExposeOneParameterPerBandAfterInitialize() {
        var plugin = new GraphicEqPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.getParameters()).hasSize(plugin.getProcessor().getBandCount());
    }

    @Test
    void shouldExposeThirtyOneParametersInThirdOctaveMode() {
        var plugin = new GraphicEqPlugin();
        plugin.initialize(stubContext());
        plugin.getProcessor().setBandType(GraphicEqProcessor.BandType.THIRD_OCTAVE);
        assertThat(plugin.getParameters()).hasSize(31);
    }

    @Test
    void eachParameterShouldHavePlusMinusTwelveDbRangeAndZeroDefault() {
        var plugin = new GraphicEqPlugin();
        for (PluginParameter p : plugin.getParameters()) {
            assertThat(p.minValue()).isEqualTo(-12.0);
            assertThat(p.maxValue()).isEqualTo(12.0);
            assertThat(p.defaultValue()).isEqualTo(0.0);
            assertThat(p.name()).isNotBlank();
        }
    }

    @Test
    void parameterIdsShouldMatchBandIndices() {
        var plugin = new GraphicEqPlugin();
        var params = plugin.getParameters();
        for (int i = 0; i < params.size(); i++) {
            assertThat(params.get(i).id()).isEqualTo(i);
        }
    }

    @Test
    void parameterNamesShouldIncludeCenterFrequencies() {
        var plugin = new GraphicEqPlugin();
        var params = plugin.getParameters();
        // First octave band: 31.5 Hz, last (10th): 16 kHz
        assertThat(params.get(0).name()).contains("31.5 Hz").startsWith("Band 1");
        assertThat(params.get(params.size() - 1).name()).contains("16 kHz");
    }

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return 44100; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) {}
        };
    }
}
