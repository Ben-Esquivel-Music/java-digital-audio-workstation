package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpectrumAnalyzerPluginTest {

    private SpectrumAnalyzerPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new SpectrumAnalyzerPlugin();
    }

    // ── Construction ───────────────────────────────────────────────────

    @Test
    void shouldHavePublicNoArgConstructor() {
        SpectrumAnalyzerPlugin fresh = new SpectrumAnalyzerPlugin();
        assertThat(fresh).isNotNull();
    }

    // ── Descriptor Metadata ────────────────────────────────────────────

    @Test
    void shouldReturnMenuLabel() {
        assertThat(plugin.getMenuLabel()).isEqualTo("Spectrum Analyzer");
    }

    @Test
    void shouldReturnMenuIcon() {
        assertThat(plugin.getMenuIcon()).isEqualTo("spectrum");
    }

    @Test
    void shouldReturnAnalyzerCategory() {
        assertThat(plugin.getCategory()).isEqualTo(BuiltInPluginCategory.ANALYZER);
    }

    @Test
    void shouldReturnDescriptorWithAnalyzerType() {
        var descriptor = plugin.getDescriptor();
        assertThat(descriptor.type()).isEqualTo(PluginType.ANALYZER);
        assertThat(descriptor.name()).isEqualTo("Spectrum Analyzer");
        assertThat(descriptor.id()).isEqualTo("com.benesquivelmusic.daw.spectrum-analyzer");
        assertThat(descriptor.vendor()).isEqualTo("DAW Built-in");
    }

    @Test
    void pluginIdConstantShouldMatchDescriptorId() {
        assertThat(SpectrumAnalyzerPlugin.PLUGIN_ID).isEqualTo(plugin.getDescriptor().id());
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    @Test
    void initializeShouldRejectNullContext() {
        assertThatThrownBy(() -> plugin.initialize(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void initializeShouldCreateAnalyzer() {
        assertThat(plugin.getAnalyzer()).isNull();
        plugin.initialize(stubContext());
        assertThat(plugin.getAnalyzer()).isNotNull();
    }

    @Test
    void initializeShouldConfigureAnalyzerWithDefaultFftSize() {
        plugin.initialize(stubContext());
        assertThat(plugin.getAnalyzer().getFftSize()).isEqualTo(SpectrumAnalyzerPlugin.DEFAULT_FFT_SIZE);
    }

    @Test
    void initializeShouldConfigureAnalyzerWithSampleRate() {
        plugin.initialize(stubContext());
        assertThat(plugin.getAnalyzer().getSampleRate()).isEqualTo(44100.0);
    }

    @Test
    void initializeShouldEnablePeakHold() {
        plugin.initialize(stubContext());
        assertThat(plugin.getAnalyzer().isPeakHoldEnabled()).isTrue();
    }

    @Test
    void activateShouldMarkActive() {
        plugin.initialize(stubContext());
        plugin.activate();
        assertThat(plugin.isActive()).isTrue();
    }

    @Test
    void deactivateShouldResetAnalyzer() {
        plugin.initialize(stubContext());
        plugin.activate();

        // Feed some data so the analyzer has state
        float[] samples = new float[SpectrumAnalyzerPlugin.DEFAULT_FFT_SIZE];
        samples[0] = 1.0f;
        plugin.getAnalyzer().process(samples);
        assertThat(plugin.getAnalyzer().hasData()).isTrue();

        plugin.deactivate();
        assertThat(plugin.isActive()).isFalse();
        assertThat(plugin.getAnalyzer().hasData()).isFalse();
    }

    @Test
    void deactivateBeforeInitializeShouldNotThrow() {
        plugin.deactivate();
    }

    @Test
    void disposeShouldReleaseAnalyzer() {
        plugin.initialize(stubContext());
        assertThat(plugin.getAnalyzer()).isNotNull();

        plugin.dispose();
        assertThat(plugin.getAnalyzer()).isNull();
    }

    @Test
    void disposeShouldMarkInactive() {
        plugin.initialize(stubContext());
        plugin.activate();
        assertThat(plugin.isActive()).isTrue();

        plugin.dispose();
        assertThat(plugin.isActive()).isFalse();
    }

    @Test
    void disposeBeforeInitializeShouldNotThrow() {
        plugin.dispose();
    }

    @Test
    void shouldImplementFullLifecycle() {
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.deactivate();
        plugin.dispose();
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return 44100; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) {}
        };
    }
}
