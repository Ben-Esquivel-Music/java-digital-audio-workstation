package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpectrumAnalyzerPluginTest {

    @Test
    void shouldHavePublicNoArgConstructor() {
        SpectrumAnalyzerPlugin plugin = new SpectrumAnalyzerPlugin();
        assertThat(plugin).isNotNull();
    }

    @Test
    void shouldReturnMenuLabel() {
        assertThat(new SpectrumAnalyzerPlugin().getMenuLabel()).isEqualTo("Spectrum Analyzer");
    }

    @Test
    void shouldReturnMenuIcon() {
        assertThat(new SpectrumAnalyzerPlugin().getMenuIcon()).isEqualTo("spectrum");
    }

    @Test
    void shouldReturnAnalyzerCategory() {
        assertThat(new SpectrumAnalyzerPlugin().getCategory()).isEqualTo(BuiltInPluginCategory.ANALYZER);
    }

    @Test
    void shouldReturnDescriptorWithAnalyzerType() {
        var descriptor = new SpectrumAnalyzerPlugin().getDescriptor();
        assertThat(descriptor.type()).isEqualTo(PluginType.ANALYZER);
        assertThat(descriptor.name()).isEqualTo("Spectrum Analyzer");
        assertThat(descriptor.id()).isEqualTo("com.benesquivelmusic.daw.spectrum-analyzer");
        assertThat(descriptor.vendor()).isEqualTo("DAW Built-in");
    }

    @Test
    void shouldImplementDawPluginLifecycle() {
        var plugin = new SpectrumAnalyzerPlugin();
        plugin.initialize(null);
        plugin.activate();
        plugin.deactivate();
        plugin.dispose();
    }
}
