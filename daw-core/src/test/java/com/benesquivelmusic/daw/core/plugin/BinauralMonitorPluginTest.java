package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.spatial.binaural.BinauralMonitoringProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BinauralMonitorPluginTest {

    @Test
    void shouldHavePublicNoArgConstructor() {
        assertThat(new BinauralMonitorPlugin()).isNotNull();
    }

    @Test
    void shouldReturnMenuMetadata() {
        var plugin = new BinauralMonitorPlugin();
        assertThat(plugin.getMenuLabel()).isEqualTo("Binaural Monitor");
        assertThat(plugin.getMenuIcon()).isEqualTo("binaural-monitor");
        assertThat(plugin.getCategory()).isEqualTo(BuiltInPluginCategory.EFFECT);
    }

    @Test
    void shouldReturnEffectDescriptor() {
        var d = new BinauralMonitorPlugin().getDescriptor();
        assertThat(d.type()).isEqualTo(PluginType.EFFECT);
        assertThat(d.name()).isEqualTo("Binaural Monitor");
        assertThat(d.id()).isEqualTo(BinauralMonitorPlugin.PLUGIN_ID);
        assertThat(d.vendor()).isEqualTo("DAW Built-in");
    }

    @Test
    void shouldReturnProcessorAfterInitialize() {
        var plugin = new BinauralMonitorPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.asAudioProcessor()).isPresent();
        assertThat(plugin.getProcessor()).isInstanceOf(BinauralMonitoringProcessor.class);
        assertThat(plugin.asAudioProcessor().orElseThrow()).isSameAs(plugin.getProcessor());
    }

    @Test
    void shouldReturnEmptyBeforeInitialize() {
        assertThat(new BinauralMonitorPlugin().asAudioProcessor()).isEmpty();
    }

    @Test
    void shouldClearProcessorOnDispose() {
        var plugin = new BinauralMonitorPlugin();
        plugin.initialize(stubContext());
        plugin.dispose();
        assertThat(plugin.asAudioProcessor()).isEmpty();
    }

    @Test
    void shouldCompleteLifecycleWithoutErrors() {
        var plugin = new BinauralMonitorPlugin();
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.deactivate();
        plugin.dispose();
    }

    @Test
    void shouldExposeParameterDescriptors() {
        var params = new BinauralMonitorPlugin().getParameters();
        assertThat(params).hasSize(1);
        assertThat(params.get(0).name()).isEqualTo("Wet Level");
    }

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return 44100; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) {}
        };
    }
}
