package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.saturation.ExciterProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExciterPluginTest {

    @Test
    void shouldHavePublicNoArgConstructor() {
        ExciterPlugin plugin = new ExciterPlugin();
        assertThat(plugin).isNotNull();
    }

    @Test
    void shouldReturnMenuLabel() {
        assertThat(new ExciterPlugin().getMenuLabel()).isEqualTo("Harmonic Exciter");
    }

    @Test
    void shouldReturnMenuIcon() {
        assertThat(new ExciterPlugin().getMenuIcon()).isEqualTo("exciter");
    }

    @Test
    void shouldReturnEffectCategory() {
        assertThat(new ExciterPlugin().getCategory()).isEqualTo(BuiltInPluginCategory.EFFECT);
    }

    @Test
    void shouldReturnDescriptorWithEffectType() {
        var descriptor = new ExciterPlugin().getDescriptor();
        assertThat(descriptor.type()).isEqualTo(PluginType.EFFECT);
        assertThat(descriptor.name()).isEqualTo("Harmonic Exciter");
        assertThat(descriptor.id()).isNotBlank();
        assertThat(descriptor.vendor()).isEqualTo("DAW Built-in");
    }

    @Test
    void shouldImplementDawPluginLifecycle() {
        var plugin = new ExciterPlugin();
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.deactivate();
        plugin.dispose();
    }

    @Test
    void shouldReturnProcessorAfterInitialize() {
        var plugin = new ExciterPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.getProcessor()).isNotNull().isInstanceOf(ExciterProcessor.class);
        assertThat(plugin.asAudioProcessor()).isPresent();
    }

    @Test
    void asAudioProcessorShouldReturnSameInstanceAsGetProcessor() {
        var plugin = new ExciterPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.asAudioProcessor().orElseThrow()).isSameAs(plugin.getProcessor());
    }

    @Test
    void shouldReturnEmptyProcessorBeforeInitialize() {
        var plugin = new ExciterPlugin();
        assertThat(plugin.asAudioProcessor()).isEmpty();
    }

    @Test
    void shouldClearProcessorOnDispose() {
        var plugin = new ExciterPlugin();
        plugin.initialize(stubContext());
        plugin.dispose();
        assertThat(plugin.asAudioProcessor()).isEmpty();
    }

    @Test
    void shouldExposeFiveParameterDescriptors() {
        var plugin = new ExciterPlugin();
        assertThat(plugin.getParameters()).isNotNull().hasSize(5);
        assertThat(plugin.getParameters().stream().map(p -> p.name()))
                .contains("Frequency (Hz)", "Drive (%)", "Mix (%)", "Output (dB)", "Mode");
    }

    @Test
    void frequencyParameterShouldExposeIssueRangeOf1To16Khz() {
        var plugin = new ExciterPlugin();
        var freqParam = plugin.getParameters().stream()
                .filter(p -> p.id() == 0).findFirst().orElseThrow();
        assertThat(freqParam.minValue()).isEqualTo(1_000.0);
        assertThat(freqParam.maxValue()).isEqualTo(16_000.0);
    }

    @Test
    void shouldBeDiscoveredAsBuiltInDawPlugin() {
        // Sealed interface permits list and discoverAll() should expose this plugin.
        boolean found = BuiltInDawPlugin.discoverAll().stream()
                .anyMatch(p -> p instanceof ExciterPlugin);
        assertThat(found).isTrue();
    }

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return 44100; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) {}
        };
    }
}
