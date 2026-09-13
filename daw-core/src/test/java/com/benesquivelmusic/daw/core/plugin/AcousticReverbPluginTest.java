package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.acoustics.AcousticReverbProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AcousticReverbPluginTest {

    @Test
    void shouldHavePublicNoArgConstructor() {
        assertThat(new AcousticReverbPlugin()).isNotNull();
    }

    @Test
    void shouldReturnMenuMetadata() {
        var plugin = new AcousticReverbPlugin();
        assertThat(plugin.getMenuLabel()).isEqualTo("Acoustic Reverb");
        assertThat(plugin.getMenuIcon()).isEqualTo("acoustic-reverb");
        assertThat(plugin.getCategory()).isEqualTo(BuiltInPluginCategory.EFFECT);
    }

    @Test
    void shouldReturnEffectDescriptor() {
        var d = new AcousticReverbPlugin().getDescriptor();
        assertThat(d.type()).isEqualTo(PluginType.EFFECT);
        assertThat(d.name()).isEqualTo("Acoustic Reverb");
        assertThat(d.id()).isEqualTo(AcousticReverbPlugin.PLUGIN_ID);
        assertThat(d.vendor()).isEqualTo("DAW Built-in");
    }

    @Test
    void shouldReturnProcessorAfterInitialize() {
        var plugin = new AcousticReverbPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.asAudioProcessor()).isPresent();
        assertThat(plugin.getProcessor()).isInstanceOf(AcousticReverbProcessor.class);
        assertThat(plugin.asAudioProcessor().orElseThrow().getInputChannelCount())
                .isEqualTo(plugin.getProcessor().getInputChannelCount());
        plugin.dispose();
    }

    @Test
    void shouldReturnEmptyBeforeInitialize() {
        assertThat(new AcousticReverbPlugin().asAudioProcessor()).isEmpty();
    }

    @Test
    void shouldClearProcessorOnDispose() {
        var plugin = new AcousticReverbPlugin();
        plugin.initialize(stubContext());
        plugin.dispose();
        assertThat(plugin.asAudioProcessor()).isEmpty();
    }

    @Test
    void shouldCompleteLifecycleWithoutErrors() {
        var plugin = new AcousticReverbPlugin();
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.deactivate();
        plugin.dispose();
    }

    @Test
    void shouldExposeParameterDescriptors() {
        var params = new AcousticReverbPlugin().getParameters();
        assertThat(params).hasSize(3);
        assertThat(params.stream().map(p -> p.name()))
                .contains("Preset", "T60 (s)", "Mix");
    }

    @Test
    void roomChangesPrepareOffThreadAndPublishThroughTheSameSignalPath() throws Exception {
        var plugin = new AcousticReverbPlugin();
        plugin.initialize(stubContext());
        try {
            var signalPath = plugin.asAudioProcessor().orElseThrow();
            var previous = plugin.getProcessor();
            plugin.setAutomatableParameter(2, 0.75);
            assertThat(previous.getMix()).isEqualTo(0.75);
            plugin.setAutomatableParameter(0, 3);
            plugin.setAutomatableParameter(1, 2.0);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            float[][] buffer = new float[2][512];
            while (plugin.getProcessor() == previous && System.nanoTime() < deadline) {
                signalPath.process(buffer, buffer, 512);
                Thread.sleep(5);
            }
            assertThat(plugin.getProcessor()).isNotSameAs(previous);
            assertThat(plugin.asAudioProcessor().orElseThrow()).isSameAs(signalPath);
            assertThat(plugin.getProcessor().getMix()).isEqualTo(0.75);
        } finally {
            plugin.dispose();
        }
    }

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return 44100; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) {}
        };
    }
}
