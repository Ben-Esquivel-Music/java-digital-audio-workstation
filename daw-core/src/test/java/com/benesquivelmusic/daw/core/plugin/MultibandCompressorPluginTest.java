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
    }

    @Test
    void asAudioProcessorShouldReturnStableInstanceAcrossRebuilds() {
        var plugin = new MultibandCompressorPlugin();
        plugin.initialize(stubContext());

        var firstWrapper = plugin.asAudioProcessor().orElseThrow();
        var firstInner = plugin.getProcessor();

        plugin.setBandCount(5);

        // The wrapper exposed to the mixer chain stays the same instance even
        // though the underlying processor was swapped, so InsertSlot/EffectsChain
        // continue routing audio through the live processor.
        assertThat(plugin.asAudioProcessor()).isPresent();
        assertThat(plugin.asAudioProcessor().orElseThrow()).isSameAs(firstWrapper);
        assertThat(plugin.getProcessor()).isNotSameAs(firstInner);
        assertThat(plugin.getProcessor().getBandCount()).isEqualTo(5);
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
    void shouldRejectBandCountChangeBeforeInitialize() {
        var plugin = new MultibandCompressorPlugin();
        assertThatThrownBy(() -> plugin.setBandCount(3))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldExposeAllBandParameters() {
        var plugin = new MultibandCompressorPlugin();
        var params = plugin.getParameters();
        // 1 band-count + 1 linear-phase + 4 crossovers + 5 * 8 per-band = 46
        assertThat(params).hasSize(46);
        assertThat(params.get(0).name()).isEqualTo("Band Count");
        assertThat(params.get(1).name()).isEqualTo("Linear Phase Toggle");
        assertThat(params.get(2).name()).startsWith("Crossover 1");
        assertThat(params.stream().map(p -> p.name()))
                .anyMatch(n -> n.startsWith("Band 5"))
                .anyMatch(n -> n.equals("Band 1 Bypass Toggle"))
                .anyMatch(n -> n.equals("Band 1 Mute Toggle"))
                .anyMatch(n -> n.equals("Band 1 Solo Toggle"));
    }

    @Test
    void crossoverParameterDefaultsShouldMatchDefaultBandLayout() {
        var plugin = new MultibandCompressorPlugin();
        plugin.initialize(stubContext());
        double[] processorCrossovers = plugin.getProcessor().getCrossoverFrequencies();
        var params = plugin.getParameters();
        // The first DEFAULT_BAND_COUNT - 1 crossover defaults must match the
        // processor's actual crossover layout in its initial (DEFAULT_BAND_COUNT) state.
        for (int i = 0; i < processorCrossovers.length; i++) {
            assertThat(params.get(2 + i).defaultValue())
                    .as("Crossover %d default", i + 1)
                    .isEqualTo(processorCrossovers[i]);
        }
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
    void automatableParametersShouldExcludeBandCount() {
        var plugin = new MultibandCompressorPlugin();
        var automatable = plugin.getAutomatableParameters();
        // 46 - 1 (Band Count is excluded because rebuilding the processor
        // is not real-time safe).
        assertThat(automatable).hasSize(45);
        assertThat(automatable).noneMatch(p -> p.id() == 0);
        assertThat(automatable.get(0).displayName()).isEqualTo("Linear Phase Toggle");
    }

    @Test
    void setAutomatableParameterShouldRouteToProcessorState() {
        var plugin = new MultibandCompressorPlugin();
        plugin.initialize(stubContext());

        // Linear-phase toggle (id 1)
        plugin.setAutomatableParameter(1, 1.0);
        assertThat(plugin.isLinearPhase()).isTrue();
        plugin.setAutomatableParameter(1, 0.0);
        assertThat(plugin.isLinearPhase()).isFalse();

        // Per-band threshold (id 6 = Band 1 Threshold (dB))
        plugin.setAutomatableParameter(6, -33.0);
        assertThat(plugin.getProcessor().getBandCompressor(0).getThresholdDb())
                .isEqualTo(-33.0);

        // Per-band makeup gain (id 10 = Band 1 Makeup Gain (dB))
        plugin.setAutomatableParameter(10, 6.0);
        assertThat(plugin.getProcessor().getBandMakeupGainDb(0)).isEqualTo(6.0);

        // Solo toggle (id 13 = Band 1 Solo Toggle)
        plugin.setAutomatableParameter(13, 1.0);
        assertThat(plugin.getProcessor().isBandSoloed(0)).isTrue();
        plugin.setAutomatableParameter(13, 0.0);
        assertThat(plugin.getProcessor().isBandSoloed(0)).isFalse();

        // Out-of-range band index for the current 4-band layout: must not throw
        plugin.setAutomatableParameter(6 + 8 * 4, -10.0);

        // Band Count (id 0) must not rebuild the processor via automation
        var beforeProcessor = plugin.getProcessor();
        plugin.setAutomatableParameter(0, 5.0);
        assertThat(plugin.getProcessor()).isSameAs(beforeProcessor);
        assertThat(plugin.getBandCount()).isEqualTo(4);
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
