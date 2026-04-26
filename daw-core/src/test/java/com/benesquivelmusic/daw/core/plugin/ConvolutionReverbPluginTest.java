package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.reverb.ConvolutionReverbProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConvolutionReverbPluginTest {

    private static PluginContext ctx() {
        return new PluginContext() {
            @Override public int getAudioChannels() { return 2; }
            @Override public double getSampleRate() { return 48000.0; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) {}
        };
    }

    @Test
    void descriptorMetadataIsCorrect() {
        var plugin = new ConvolutionReverbPlugin();
        var d = plugin.getDescriptor();
        assertThat(d.id()).isEqualTo(ConvolutionReverbPlugin.PLUGIN_ID);
        assertThat(d.name()).isEqualTo("Convolution Reverb");
        assertThat(d.type()).isEqualTo(PluginType.EFFECT);
    }

    @Test
    void initializeCreatesBackingProcessor() {
        var plugin = new ConvolutionReverbPlugin();
        plugin.initialize(ctx());
        assertThat(plugin.getProcessor())
                .isInstanceOf(ConvolutionReverbProcessor.class);
        assertThat(plugin.asAudioProcessor()).isPresent();
    }

    @Test
    void disposeClearsProcessor() {
        var plugin = new ConvolutionReverbPlugin();
        plugin.initialize(ctx());
        plugin.activate();
        plugin.deactivate();
        plugin.dispose();
        assertThat(plugin.getProcessor()).isNull();
    }

    @Test
    void parameterDescriptorsCoverFullPluginSurface() {
        var plugin = new ConvolutionReverbPlugin();
        List<?> params = plugin.getParameters();
        assertThat(params).hasSize(9); // matches @ProcessorParam ids 0..8
    }

    @Test
    void pluginIsBuiltInAndAnnotated() {
        BuiltInPlugin meta = ConvolutionReverbPlugin.class.getAnnotation(BuiltInPlugin.class);
        assertThat(meta).isNotNull();
        assertThat(meta.label()).isEqualTo("Convolution Reverb");
        assertThat(meta.category()).isEqualTo(BuiltInPluginCategory.EFFECT);

        // Permitted by the sealed interface
        assertThat(BuiltInDawPlugin.class.getPermittedSubclasses())
                .contains(ConvolutionReverbPlugin.class);
    }
}
