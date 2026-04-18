package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.sdk.plugin.AutomatableParameter;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that built-in effect plugins expose their parameters via the
 * {@link DawPlugin#getAutomatableParameters()} contract, which the host uses
 * to populate the automation-lane parameter selector when a plugin is
 * inserted on a channel.
 */
class BuiltInPluginAutomatableParametersTest {

    @Test
    void compressorExposesSixAutomatableParameters() {
        DawPlugin plugin = new CompressorPlugin();

        List<AutomatableParameter> automatable = plugin.getAutomatableParameters();

        assertThat(automatable).hasSize(6);
        assertThat(automatable)
                .extracting(AutomatableParameter::displayName)
                .contains("Threshold (dB)", "Ratio", "Attack (ms)",
                          "Release (ms)", "Knee (dB)", "Makeup Gain (dB)");
        assertThat(automatable)
                .extracting(AutomatableParameter::id)
                .containsExactly(0, 1, 2, 3, 4, 5);
    }

    @Test
    void pluginWithNoParametersShouldExposeEmptyAutomatableList() {
        DawPlugin plugin = new SpectrumAnalyzerPlugin();

        assertThat(plugin.getAutomatableParameters()).isEmpty();
    }
}
