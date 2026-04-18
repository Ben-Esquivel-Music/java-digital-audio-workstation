package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.WaveshaperProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WaveshaperPluginTest {

    @Test
    void shouldHavePublicNoArgConstructor() {
        WaveshaperPlugin plugin = new WaveshaperPlugin();
        assertThat(plugin).isNotNull();
    }

    @Test
    void shouldReturnMenuLabel() {
        assertThat(new WaveshaperPlugin().getMenuLabel()).isEqualTo("Waveshaper / Saturation");
    }

    @Test
    void shouldReturnMenuIcon() {
        assertThat(new WaveshaperPlugin().getMenuIcon()).isEqualTo("waveshaper");
    }

    @Test
    void shouldReturnEffectCategory() {
        assertThat(new WaveshaperPlugin().getCategory()).isEqualTo(BuiltInPluginCategory.EFFECT);
    }

    @Test
    void shouldReturnDescriptorWithEffectType() {
        var descriptor = new WaveshaperPlugin().getDescriptor();
        assertThat(descriptor.type()).isEqualTo(PluginType.EFFECT);
        assertThat(descriptor.name()).isEqualTo("Waveshaper / Saturation");
        assertThat(descriptor.id()).isNotBlank();
        assertThat(descriptor.vendor()).isEqualTo("DAW Built-in");
    }

    @Test
    void shouldImplementDawPluginLifecycle() {
        var plugin = new WaveshaperPlugin();
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.deactivate();
        plugin.dispose();
    }

    @Test
    void shouldReturnProcessorAfterInitialize() {
        var plugin = new WaveshaperPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.getProcessor()).isNotNull();
        assertThat(plugin.asAudioProcessor()).isPresent();
    }

    @Test
    void shouldReturnProcessorOfCorrectType() {
        var plugin = new WaveshaperPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.getProcessor()).isInstanceOf(WaveshaperProcessor.class);
    }

    @Test
    void asAudioProcessorShouldReturnSameInstanceAsGetProcessor() {
        var plugin = new WaveshaperPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.asAudioProcessor().orElseThrow()).isSameAs(plugin.getProcessor());
    }

    @Test
    void shouldReturnEmptyProcessorBeforeInitialize() {
        var plugin = new WaveshaperPlugin();
        assertThat(plugin.asAudioProcessor()).isEmpty();
    }

    @Test
    void shouldClearProcessorOnDispose() {
        var plugin = new WaveshaperPlugin();
        plugin.initialize(stubContext());
        plugin.dispose();
        assertThat(plugin.asAudioProcessor()).isEmpty();
    }

    @Test
    void shouldHaveReasonableDefaultProcessorParameters() {
        var plugin = new WaveshaperPlugin();
        plugin.initialize(stubContext());
        WaveshaperProcessor p = plugin.getProcessor();
        assertThat(p.getTransferFunction()).isEqualTo(WaveshaperProcessor.TransferFunction.SOFT_CLIP);
        assertThat(p.getOversampleFactor()).isEqualTo(WaveshaperProcessor.OversampleFactor.TWO_X);
        assertThat(p.getDriveDb()).isEqualTo(0.0);
        assertThat(p.getMix()).isEqualTo(1.0);
        assertThat(p.getOutputGainDb()).isEqualTo(0.0);
    }

    @Test
    void shouldExposeParameterDescriptors() {
        var plugin = new WaveshaperPlugin();
        assertThat(plugin.getParameters()).isNotNull().hasSize(5);
        assertThat(plugin.getParameters().stream().map(p -> p.name()))
                .contains("Drive (dB)", "Mix", "Output Gain (dB)",
                          "Oversampling", "Transfer Function");
    }

    @Test
    void driveParameterShouldExposeFullResearchBackedRange() {
        var plugin = new WaveshaperPlugin();
        var driveParam = plugin.getParameters().stream()
                .filter(p -> p.id() == 0)
                .findFirst()
                .orElseThrow();
        assertThat(driveParam.minValue()).isEqualTo(WaveshaperProcessor.MIN_DRIVE_DB);
        assertThat(driveParam.maxValue()).isEqualTo(WaveshaperProcessor.MAX_DRIVE_DB);
    }

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return 44100; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) {}
        };
    }
}
