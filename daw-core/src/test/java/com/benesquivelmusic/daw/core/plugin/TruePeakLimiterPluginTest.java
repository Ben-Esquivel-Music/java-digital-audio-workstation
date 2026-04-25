package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.dynamics.TruePeakLimiterProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TruePeakLimiterPluginTest {

    @Test
    void shouldHavePublicNoArgConstructor() {
        assertThat(new TruePeakLimiterPlugin()).isNotNull();
    }

    @Test
    void shouldReturnMenuLabel() {
        assertThat(new TruePeakLimiterPlugin().getMenuLabel()).isEqualTo("True-Peak Limiter");
    }

    @Test
    void shouldReturnMasteringCategory() {
        assertThat(new TruePeakLimiterPlugin().getCategory())
                .isEqualTo(BuiltInPluginCategory.MASTERING);
    }

    @Test
    void shouldReturnDescriptorWithEffectType() {
        var d = new TruePeakLimiterPlugin().getDescriptor();
        assertThat(d.type()).isEqualTo(PluginType.EFFECT);
        assertThat(d.name()).isEqualTo("True-Peak Limiter");
        assertThat(d.id()).isEqualTo(TruePeakLimiterPlugin.PLUGIN_ID);
        assertThat(d.vendor()).isEqualTo("DAW Built-in");
    }

    @Test
    void shouldImplementDawPluginLifecycle() {
        var plugin = new TruePeakLimiterPlugin();
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.deactivate();
        plugin.dispose();
    }

    @Test
    void shouldReturnProcessorAfterInitialize() {
        var plugin = new TruePeakLimiterPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.getProcessor()).isInstanceOf(TruePeakLimiterProcessor.class);
        assertThat(plugin.asAudioProcessor()).isPresent();
        assertThat(plugin.asAudioProcessor().orElseThrow()).isSameAs(plugin.getProcessor());
    }

    @Test
    void shouldClearProcessorOnDispose() {
        var plugin = new TruePeakLimiterPlugin();
        plugin.initialize(stubContext());
        plugin.dispose();
        assertThat(plugin.asAudioProcessor()).isEmpty();
    }

    @Test
    void shouldExposeFiveParameterDescriptors() {
        var plugin = new TruePeakLimiterPlugin();
        assertThat(plugin.getParameters()).hasSize(5);
        assertThat(plugin.getParameters().stream().map(p -> p.name()))
                .containsExactly("Ceiling (dBTP)", "Release (ms)",
                        "Lookahead (ms)", "ISR", "Channel Link (%)");
    }

    @Test
    void shouldHaveDistinctIdFromOtherDynamicsPlugins() {
        assertThat(TruePeakLimiterPlugin.PLUGIN_ID)
                .isNotEqualTo(CompressorPlugin.PLUGIN_ID)
                .isNotEqualTo(BusCompressorPlugin.PLUGIN_ID)
                .isNotEqualTo(MultibandCompressorPlugin.PLUGIN_ID)
                .isNotEqualTo(DeEsserPlugin.PLUGIN_ID);
    }

    @Test
    void shouldAppearInBuiltInDawPluginPermittedSet() {
        var classes = java.util.Arrays.stream(BuiltInDawPlugin.class.getPermittedSubclasses())
                .toList();
        assertThat(classes).contains(TruePeakLimiterPlugin.class);
    }

    @Test
    void shouldRouteAutomationValuesToProcessor() {
        var plugin = new TruePeakLimiterPlugin();
        plugin.initialize(stubContext());
        plugin.setAutomatableParameter(0, -2.0); // ceiling
        plugin.setAutomatableParameter(1, 100.0); // release
        plugin.setAutomatableParameter(2, 7.0);   // lookahead
        plugin.setAutomatableParameter(3, 8.0);   // ISR
        plugin.setAutomatableParameter(4, 50.0);  // channel link

        var p = plugin.getProcessor();
        assertThat(p.getCeilingDb()).isEqualTo(-2.0);
        assertThat(p.getReleaseMs()).isEqualTo(100.0);
        assertThat(p.getLookaheadMs()).isEqualTo(7.0);
        assertThat(p.getIsr()).isEqualTo(8);
        assertThat(p.getChannelLinkPercent()).isEqualTo(50.0);
    }

    @Test
    void setAutomatableParameterShouldBeNoOpBeforeInitialize() {
        new TruePeakLimiterPlugin().setAutomatableParameter(0, -1.0);
    }

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return 48_000; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) {}
        };
    }
}
