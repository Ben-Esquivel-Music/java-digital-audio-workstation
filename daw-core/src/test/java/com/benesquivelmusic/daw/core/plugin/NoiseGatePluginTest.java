package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.dynamics.NoiseGateProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NoiseGatePluginTest {

    @Test
    void shouldHavePublicNoArgConstructor() {
        assertThat(new NoiseGatePlugin()).isNotNull();
    }

    @Test
    void shouldReturnMenuLabel() {
        assertThat(new NoiseGatePlugin().getMenuLabel()).isEqualTo("Noise Gate");
    }

    @Test
    void shouldReturnEffectCategory() {
        assertThat(new NoiseGatePlugin().getCategory()).isEqualTo(BuiltInPluginCategory.EFFECT);
    }

    @Test
    void shouldReturnDescriptorWithEffectType() {
        var d = new NoiseGatePlugin().getDescriptor();
        assertThat(d.type()).isEqualTo(PluginType.EFFECT);
        assertThat(d.name()).isEqualTo("Noise Gate");
        assertThat(d.id()).isEqualTo(NoiseGatePlugin.PLUGIN_ID);
        assertThat(d.vendor()).isEqualTo("DAW Built-in");
    }

    @Test
    void shouldImplementDawPluginLifecycle() {
        var plugin = new NoiseGatePlugin();
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.deactivate();
        plugin.dispose();
    }

    @Test
    void shouldReturnProcessorAfterInitialize() {
        var plugin = new NoiseGatePlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.getProcessor()).isInstanceOf(NoiseGateProcessor.class);
        assertThat(plugin.asAudioProcessor()).isPresent();
        assertThat(plugin.asAudioProcessor().orElseThrow()).isSameAs(plugin.getProcessor());
    }

    @Test
    void shouldClearProcessorOnDispose() {
        var plugin = new NoiseGatePlugin();
        plugin.initialize(stubContext());
        plugin.dispose();
        assertThat(plugin.asAudioProcessor()).isEmpty();
    }

    @Test
    void shouldExposeTenParameterDescriptors() {
        var plugin = new NoiseGatePlugin();
        assertThat(plugin.getParameters()).hasSize(10);
        assertThat(plugin.getParameters().stream().map(p -> p.name()))
                .containsExactly(
                        "Threshold (dB)", "Hysteresis (dB)", "Attack (ms)",
                        "Hold (ms)", "Release (ms)", "Range (dB)",
                        "Lookahead (ms)", "Sidechain Enabled Toggle",
                        "Sidechain Filter Freq (Hz)", "Sidechain Filter Q");
    }

    @Test
    void shouldRouteAutomationToProcessor() {
        var plugin = new NoiseGatePlugin();
        plugin.initialize(stubContext());

        plugin.setAutomatableParameter(0, -30.0);
        plugin.setAutomatableParameter(1,   6.0);
        plugin.setAutomatableParameter(2,   2.5);
        plugin.setAutomatableParameter(3, 100.0);
        plugin.setAutomatableParameter(4, 200.0);
        plugin.setAutomatableParameter(5, -60.0);
        plugin.setAutomatableParameter(6,   3.0);
        plugin.setAutomatableParameter(7,   1.0);
        plugin.setAutomatableParameter(8, 120.0);
        plugin.setAutomatableParameter(9,   1.4);

        NoiseGateProcessor p = plugin.getProcessor();
        assertThat(p.getThresholdDb()).isEqualTo(-30.0);
        assertThat(p.getHysteresisDb()).isEqualTo(6.0);
        assertThat(p.getAttackMs()).isEqualTo(2.5);
        assertThat(p.getHoldMs()).isEqualTo(100.0);
        assertThat(p.getReleaseMs()).isEqualTo(200.0);
        assertThat(p.getRangeDb()).isEqualTo(-60.0);
        assertThat(p.getLookaheadMs()).isEqualTo(3.0);
        assertThat(p.isSidechainEnabled()).isTrue();
        assertThat(p.getSidechainFilterFreqHz()).isEqualTo(120.0);
        assertThat(p.getSidechainFilterQ()).isEqualTo(1.4);
    }

    @Test
    void automationShouldClampOutOfRangeValues() {
        var plugin = new NoiseGatePlugin();
        plugin.initialize(stubContext());

        plugin.setAutomatableParameter(0, 999.0);   // threshold
        plugin.setAutomatableParameter(6, 999.0);   // lookahead

        assertThat(plugin.getProcessor().getThresholdDb()).isEqualTo(0.0);
        assertThat(plugin.getProcessor().getLookaheadMs()).isEqualTo(10.0);
    }

    @Test
    void automationShouldBeNoOpBeforeInitialize() {
        var plugin = new NoiseGatePlugin();
        plugin.setAutomatableParameter(0, -30.0); // must not throw
        assertThat(plugin.getProcessor()).isNull();
    }

    @Test
    void shouldHaveDistinctIdFromOtherDynamicsPlugins() {
        assertThat(NoiseGatePlugin.PLUGIN_ID)
                .isNotEqualTo(BusCompressorPlugin.PLUGIN_ID)
                .isNotEqualTo(CompressorPlugin.PLUGIN_ID)
                .isNotEqualTo(DeEsserPlugin.PLUGIN_ID)
                .isNotEqualTo(TruePeakLimiterPlugin.PLUGIN_ID)
                .isNotEqualTo(TransientShaperPlugin.PLUGIN_ID);
    }

    @Test
    void shouldAppearInBuiltInDawPluginPermittedSet() {
        var classes = java.util.Arrays.stream(BuiltInDawPlugin.class.getPermittedSubclasses())
                .toList();
        assertThat(classes).contains(NoiseGatePlugin.class);
    }

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return 48000; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) {}
        };
    }
}
