package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.dynamics.BusCompressorProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BusCompressorPluginTest {

    @Test
    void shouldHavePublicNoArgConstructor() {
        assertThat(new BusCompressorPlugin()).isNotNull();
    }

    @Test
    void shouldReturnMenuLabel() {
        assertThat(new BusCompressorPlugin().getMenuLabel()).isEqualTo("Bus Compressor");
    }

    @Test
    void shouldReturnEffectCategory() {
        assertThat(new BusCompressorPlugin().getCategory()).isEqualTo(BuiltInPluginCategory.EFFECT);
    }

    @Test
    void shouldReturnDescriptorWithEffectType() {
        var d = new BusCompressorPlugin().getDescriptor();
        assertThat(d.type()).isEqualTo(PluginType.EFFECT);
        assertThat(d.name()).isEqualTo("Bus Compressor");
        assertThat(d.id()).isEqualTo(BusCompressorPlugin.PLUGIN_ID);
        assertThat(d.vendor()).isEqualTo("DAW Built-in");
    }

    @Test
    void shouldImplementDawPluginLifecycle() {
        var plugin = new BusCompressorPlugin();
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.deactivate();
        plugin.dispose();
    }

    @Test
    void shouldReturnProcessorAfterInitialize() {
        var plugin = new BusCompressorPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.getProcessor()).isInstanceOf(BusCompressorProcessor.class);
        assertThat(plugin.asAudioProcessor()).isPresent();
        assertThat(plugin.asAudioProcessor().orElseThrow()).isSameAs(plugin.getProcessor());
    }

    @Test
    void shouldClearProcessorOnDispose() {
        var plugin = new BusCompressorPlugin();
        plugin.initialize(stubContext());
        plugin.dispose();
        assertThat(plugin.asAudioProcessor()).isEmpty();
    }

    @Test
    void shouldExposeEightParameterDescriptors() {
        var plugin = new BusCompressorPlugin();
        assertThat(plugin.getParameters()).hasSize(8);
        assertThat(plugin.getParameters().stream().map(p -> p.name()))
                .containsExactly("Threshold (dB)", "Ratio", "Attack (ms)",
                        "Release (s)", "Makeup Gain (dB)", "Mix",
                        "Auto Release Toggle", "Drive Toggle");
    }

    @Test
    void shouldRouteAutomationValuesToProcessor() {
        var plugin = new BusCompressorPlugin();
        plugin.initialize(stubContext());
        var processor = plugin.getProcessor();

        plugin.setAutomatableParameter(0, -22.0);
        // Ratio / attack / release snap to the processor's SSL steps, so
        // automation is exercised with step-exact values here.
        plugin.setAutomatableParameter(1, 10.0);
        plugin.setAutomatableParameter(2, 3.0);
        plugin.setAutomatableParameter(3, 0.3);
        plugin.setAutomatableParameter(4, 6.0);
        plugin.setAutomatableParameter(5, 0.5);
        plugin.setAutomatableParameter(6, 1.0);
        plugin.setAutomatableParameter(7, 1.0);

        assertThat(processor.getThresholdDb()).isEqualTo(-22.0);
        assertThat(processor.getRatio()).isEqualTo(10.0);
        assertThat(processor.getAttackMs()).isEqualTo(3.0);
        assertThat(processor.getReleaseS()).isEqualTo(0.3);
        assertThat(processor.getMakeupGainDb()).isEqualTo(6.0);
        assertThat(processor.getMix()).isEqualTo(0.5);
        assertThat(processor.isReleaseAuto()).isTrue();
        assertThat(processor.isDrive()).isTrue();
    }

    @Test
    void automationWritesBeforeInitializeAreIgnored() {
        var plugin = new BusCompressorPlugin();
        plugin.setAutomatableParameter(0, -22.0);
        assertThat(plugin.getProcessor()).isNull();
    }

    @Test
    void shouldHaveDistinctIdFromCompressorPlugin() {
        assertThat(BusCompressorPlugin.PLUGIN_ID).isNotEqualTo(CompressorPlugin.PLUGIN_ID);
    }

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return 44100; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) {}
        };
    }
}
