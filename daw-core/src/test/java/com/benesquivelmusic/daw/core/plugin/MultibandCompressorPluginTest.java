package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.MultibandCompressorProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MultibandCompressorPluginTest {

    @Test
    void shouldHavePublicNoArgConstructor() {
        assertThat(new MultibandCompressorPlugin()).isNotNull();
    }

    @Test
    void shouldReturnMenuLabel() {
        assertThat(new MultibandCompressorPlugin().getMenuLabel())
                .isEqualTo("Multiband Compressor");
    }

    @Test
    void shouldReturnEffectCategory() {
        assertThat(new MultibandCompressorPlugin().getCategory())
                .isEqualTo(BuiltInPluginCategory.EFFECT);
    }

    @Test
    void shouldReturnDescriptorWithEffectType() {
        var d = new MultibandCompressorPlugin().getDescriptor();
        assertThat(d.type()).isEqualTo(PluginType.EFFECT);
        assertThat(d.name()).isEqualTo("Multiband Compressor");
        assertThat(d.id()).isEqualTo(MultibandCompressorPlugin.PLUGIN_ID);
        assertThat(d.vendor()).isEqualTo("DAW Built-in");
    }

    @Test
    void shouldImplementDawPluginLifecycle() {
        var plugin = new MultibandCompressorPlugin();
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.deactivate();
        plugin.dispose();
    }

    @Test
    void shouldReturnProcessorAfterInitialize() {
        var plugin = new MultibandCompressorPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.getProcessor()).isInstanceOf(MultibandCompressorProcessor.class);
        assertThat(plugin.asAudioProcessor()).isPresent();
        assertThat(plugin.asAudioProcessor().orElseThrow()).isSameAs(plugin.getProcessor());
    }

    @Test
    void shouldDefaultToFourBands() {
        var plugin = new MultibandCompressorPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.getBandCount()).isEqualTo(4);
        assertThat(plugin.getProcessor().getBandCount()).isEqualTo(4);
    }

    @Test
    void shouldChangeBandCountAndRebuildProcessor() {
        var plugin = new MultibandCompressorPlugin();
        plugin.initialize(stubContext());

        plugin.setBandCount(3);
        assertThat(plugin.getBandCount()).isEqualTo(3);
        assertThat(plugin.getProcessor().getBandCount()).isEqualTo(3);

        plugin.setBandCount(5);
        assertThat(plugin.getBandCount()).isEqualTo(5);
        assertThat(plugin.getProcessor().getBandCount()).isEqualTo(5);
        assertThat(plugin.getProcessor().getCrossoverFrequencies()).hasSize(4);
    }

    @Test
    void shouldRejectInvalidBandCount() {
        var plugin = new MultibandCompressorPlugin();
        plugin.initialize(stubContext());
        assertThatThrownBy(() -> plugin.setBandCount(2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plugin.setBandCount(6))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldExposeAllBandParameters() {
        var plugin = new MultibandCompressorPlugin();
        var params = plugin.getParameters();
        // 1 band-count + 1 linear-phase + 4 crossovers + 5 * 8 per-band = 46
        assertThat(params).hasSize(46);
        assertThat(params.get(0).name()).isEqualTo("Band Count");
        assertThat(params.get(1).name()).isEqualTo("Linear Phase");
        assertThat(params.get(2).name()).startsWith("Crossover 1");
        assertThat(params.stream().map(p -> p.name()))
                .anyMatch(n -> n.startsWith("Band 5"));
    }

    @Test
    void shouldSupportLinearPhaseFlag() {
        var plugin = new MultibandCompressorPlugin();
        assertThat(plugin.isLinearPhase()).isFalse();
        plugin.setLinearPhase(true);
        assertThat(plugin.isLinearPhase()).isTrue();
    }

    @Test
    void shouldClearProcessorOnDispose() {
        var plugin = new MultibandCompressorPlugin();
        plugin.initialize(stubContext());
        plugin.dispose();
        assertThat(plugin.asAudioProcessor()).isEmpty();
        assertThat(plugin.getProcessor()).isNull();
    }

    @Test
    void shouldHaveDistinctIdFromCompressorPlugins() {
        assertThat(MultibandCompressorPlugin.PLUGIN_ID)
                .isNotEqualTo(CompressorPlugin.PLUGIN_ID)
                .isNotEqualTo(BusCompressorPlugin.PLUGIN_ID);
    }

    @Test
    void shouldBeDiscoveredAsBuiltInPlugin() {
        assertThat(BuiltInDawPlugin.discoverAll())
                .anyMatch(p -> p instanceof MultibandCompressorPlugin);
        assertThat(BuiltInDawPlugin.menuEntries())
                .anyMatch(e -> e.pluginClass().equals(MultibandCompressorPlugin.class));
    }

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return 44100; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) {}
        };
    }
}
