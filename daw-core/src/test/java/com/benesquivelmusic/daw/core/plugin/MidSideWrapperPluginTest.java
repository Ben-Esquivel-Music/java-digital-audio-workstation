package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.MidSideWrapperProcessor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MidSideWrapperPluginTest {

    @Test
    void shouldHavePublicNoArgConstructor() {
        assertThat(new MidSideWrapperPlugin()).isNotNull();
    }

    @Test
    void shouldReturnMenuLabelAndCategory() {
        var p = new MidSideWrapperPlugin();
        assertThat(p.getMenuLabel()).isEqualTo("Mid/Side Wrapper");
        assertThat(p.getCategory()).isEqualTo(BuiltInPluginCategory.EFFECT);
    }

    @Test
    void shouldReturnDescriptorWithEffectType() {
        var d = new MidSideWrapperPlugin().getDescriptor();
        assertThat(d.type()).isEqualTo(PluginType.EFFECT);
        assertThat(d.name()).isEqualTo("Mid/Side Wrapper");
        assertThat(d.id()).isEqualTo(MidSideWrapperPlugin.PLUGIN_ID);
        assertThat(d.vendor()).isEqualTo("DAW Built-in");
    }

    @Test
    void shouldExposeProcessorAfterInitialize() {
        var plugin = new MidSideWrapperPlugin();
        assertThat(plugin.asAudioProcessor()).isEmpty();
        plugin.initialize(stubContext());
        assertThat(plugin.asAudioProcessor()).isPresent();
        assertThat(plugin.getProcessor()).isInstanceOf(MidSideWrapperProcessor.class);
        plugin.activate();
        plugin.deactivate();
        plugin.dispose();
        assertThat(plugin.getProcessor()).isNull();
    }

    @Test
    void chainsStartEmpty() {
        var plugin = new MidSideWrapperPlugin();
        assertThat(plugin.getMidChain()).isEmpty();
        assertThat(plugin.getSideChain()).isEmpty();
        assertThat(plugin.getChain(MidSideWrapperPlugin.ChainOwner.MID)).isEmpty();
        assertThat(plugin.getChain(MidSideWrapperPlugin.ChainOwner.SIDE)).isEmpty();
    }

    @Test
    void addPlugin_routesProcessorIntoUnderlyingChain() {
        var plugin = new MidSideWrapperPlugin();
        plugin.initialize(stubContext());

        var inner = new RecordingMonoPlugin();
        plugin.addPlugin(MidSideWrapperPlugin.ChainOwner.MID, inner);

        assertThat(plugin.getMidChain()).containsExactly(inner);
        assertThat(plugin.getProcessor().getMidChain()).hasSize(1);
        assertThat(inner.initCalls).isEqualTo(1);
    }

    @Test
    void addPluginAfterActivate_alsoActivatesInnerPlugin() {
        var plugin = new MidSideWrapperPlugin();
        plugin.initialize(stubContext());
        plugin.activate();

        var inner = new RecordingMonoPlugin();
        plugin.addPlugin(MidSideWrapperPlugin.ChainOwner.SIDE, inner);

        assertThat(inner.activateCalls).isEqualTo(1);
        assertThat(plugin.getSideChain()).containsExactly(inner);
    }

    @Test
    void removePlugin_unwiresAndDisposesInnerPlugin() {
        var plugin = new MidSideWrapperPlugin();
        plugin.initialize(stubContext());

        var inner = new RecordingMonoPlugin();
        plugin.addPlugin(MidSideWrapperPlugin.ChainOwner.SIDE, inner);
        assertThat(plugin.getSideChain()).hasSize(1);

        boolean removed = plugin.removePlugin(MidSideWrapperPlugin.ChainOwner.SIDE, inner);

        assertThat(removed).isTrue();
        assertThat(plugin.getSideChain()).isEmpty();
        assertThat(plugin.getProcessor().getSideChain()).isEmpty();
        assertThat(inner.disposeCalls).isEqualTo(1);
    }

    @Test
    void presets_returnInitializableWrappers() {
        // Each preset must be re-initializable through the standard host path.
        for (var supplier : new java.util.function.Supplier[] {
                MidSideWrapperPlugin::stereoWidenerPreset,
                MidSideWrapperPlugin::monoBassPreset,
                MidSideWrapperPlugin::centerFocusPreset
        }) {
            var p = (MidSideWrapperPlugin) supplier.get();
            p.initialize(stubContext());
            p.activate();
            assertThat(p.asAudioProcessor()).isPresent();
            p.deactivate();
            p.dispose();
        }
    }

    @Test
    void stereoWidenerPreset_putsGainOnSideChainOnly() {
        var p = MidSideWrapperPlugin.stereoWidenerPreset();
        assertThat(p.getMidChain()).isEmpty();
        assertThat(p.getSideChain()).hasSize(1);
    }

    @Test
    void monoBassPreset_putsHighPassOnSideChain() {
        var p = MidSideWrapperPlugin.monoBassPreset();
        assertThat(p.getMidChain()).isEmpty();
        assertThat(p.getSideChain()).hasSize(1);
    }

    @Test
    void centerFocusPreset_putsCompressorOnMidChain() {
        var p = MidSideWrapperPlugin.centerFocusPreset();
        assertThat(p.getMidChain()).hasSize(1);
        assertThat(p.getSideChain()).isEmpty();
    }

    @Test
    void chainOwner_isStable() {
        // The ChainOwner enum is exposed as the discriminator the undo system
        // uses to identify which inner chain an operation targets. Lock down
        // its values so future renames are visible to the undo subsystem.
        assertThat(MidSideWrapperPlugin.ChainOwner.values())
                .containsExactly(
                        MidSideWrapperPlugin.ChainOwner.MID,
                        MidSideWrapperPlugin.ChainOwner.SIDE);
    }

    @Test
    void getChain_rejectsNullOwner() {
        assertThatThrownBy(() -> new MidSideWrapperPlugin().getChain(null))
                .isInstanceOf(NullPointerException.class);
    }

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return 44100; }
            @Override public int getBufferSize()    { return 512; }
            @Override public void log(String m)     { /* no-op */ }
        };
    }

    /** Lightweight DawPlugin used to verify lifecycle delegation. */
    private static final class RecordingMonoPlugin implements DawPlugin {
        private static final PluginDescriptor DESC = new PluginDescriptor(
                "test.recording", "Recording", "1.0.0", "test", PluginType.EFFECT);
        int initCalls, activateCalls, deactivateCalls, disposeCalls;
        AudioProcessor processor;
        @Override public PluginDescriptor getDescriptor() { return DESC; }
        @Override public void initialize(PluginContext ctx) {
            initCalls++;
            processor = new AudioProcessor() {
                @Override public void process(float[][] in, float[][] out, int n) {
                    System.arraycopy(in[0], 0, out[0], 0, n);
                }
                @Override public void reset() {}
                @Override public int getInputChannelCount()  { return 1; }
                @Override public int getOutputChannelCount() { return 1; }
            };
        }
        @Override public void activate()   { activateCalls++; }
        @Override public void deactivate() { deactivateCalls++; }
        @Override public void dispose()    { disposeCalls++; processor = null; }
        @Override public Optional<AudioProcessor> asAudioProcessor() {
            return Optional.ofNullable(processor);
        }
    }
}
