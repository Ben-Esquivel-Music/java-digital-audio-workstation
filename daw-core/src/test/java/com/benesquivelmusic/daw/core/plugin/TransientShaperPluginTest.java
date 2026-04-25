package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.dynamics.TransientShaperProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransientShaperPluginTest {

    @Test
    void shouldHavePublicNoArgConstructor() {
        assertThat(new TransientShaperPlugin()).isNotNull();
    }

    @Test
    void shouldReturnMenuLabel() {
        assertThat(new TransientShaperPlugin().getMenuLabel()).isEqualTo("Transient Shaper");
    }

    @Test
    void shouldReturnEffectCategory() {
        assertThat(new TransientShaperPlugin().getCategory()).isEqualTo(BuiltInPluginCategory.EFFECT);
    }

    @Test
    void shouldReturnDescriptorWithEffectType() {
        var d = new TransientShaperPlugin().getDescriptor();
        assertThat(d.type()).isEqualTo(PluginType.EFFECT);
        assertThat(d.name()).isEqualTo("Transient Shaper");
        assertThat(d.id()).isEqualTo(TransientShaperPlugin.PLUGIN_ID);
        assertThat(d.vendor()).isEqualTo("DAW Built-in");
    }

    @Test
    void shouldImplementDawPluginLifecycle() {
        var plugin = new TransientShaperPlugin();
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.deactivate();
        plugin.dispose();
    }

    @Test
    void shouldReturnProcessorAfterInitialize() {
        var plugin = new TransientShaperPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.getProcessor()).isInstanceOf(TransientShaperProcessor.class);
        assertThat(plugin.asAudioProcessor()).isPresent();
        assertThat(plugin.asAudioProcessor().orElseThrow()).isSameAs(plugin.getProcessor());
    }

    @Test
    void shouldClearProcessorOnDispose() {
        var plugin = new TransientShaperPlugin();
        plugin.initialize(stubContext());
        plugin.dispose();
        assertThat(plugin.asAudioProcessor()).isEmpty();
    }

    @Test
    void shouldExposeFiveParameterDescriptors() {
        var plugin = new TransientShaperPlugin();
        assertThat(plugin.getParameters()).hasSize(5);
        assertThat(plugin.getParameters().stream().map(p -> p.name()))
                .containsExactly("Attack (%)", "Sustain (%)", "Output (dB)",
                        "Input Monitor Toggle", "Channel Link");
    }

    @Test
    void shouldRouteAutomationToProcessor() {
        var plugin = new TransientShaperPlugin();
        plugin.initialize(stubContext());

        plugin.setAutomatableParameter(0,  75.0);
        plugin.setAutomatableParameter(1, -50.0);
        plugin.setAutomatableParameter(2,   3.0);
        plugin.setAutomatableParameter(3,   1.0);
        plugin.setAutomatableParameter(4,   0.25);

        TransientShaperProcessor p = plugin.getProcessor();
        assertThat(p.getAttackPercent()).isEqualTo(75.0);
        assertThat(p.getSustainPercent()).isEqualTo(-50.0);
        assertThat(p.getOutputDb()).isEqualTo(3.0);
        assertThat(p.isInputMonitor()).isTrue();
        assertThat(p.getChannelLink()).isEqualTo(0.25);
    }

    @Test
    void automationShouldClampOutOfRangeValues() {
        var plugin = new TransientShaperPlugin();
        plugin.initialize(stubContext());

        plugin.setAutomatableParameter(0, 999.0);
        plugin.setAutomatableParameter(2, 999.0);

        assertThat(plugin.getProcessor().getAttackPercent()).isEqualTo(100.0);
        assertThat(plugin.getProcessor().getOutputDb()).isEqualTo(12.0);
    }

    @Test
    void automationShouldBeNoOpBeforeInitialize() {
        var plugin = new TransientShaperPlugin();
        // Must not throw — the host may automate parameters before init.
        plugin.setAutomatableParameter(0, 50.0);
        assertThat(plugin.getProcessor()).isNull();
    }

    @Test
    void shouldHaveDistinctIdFromOtherDynamicsPlugins() {
        assertThat(TransientShaperPlugin.PLUGIN_ID)
                .isNotEqualTo(BusCompressorPlugin.PLUGIN_ID)
                .isNotEqualTo(CompressorPlugin.PLUGIN_ID)
                .isNotEqualTo(DeEsserPlugin.PLUGIN_ID);
    }

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return 48000; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) {}
        };
    }
}
