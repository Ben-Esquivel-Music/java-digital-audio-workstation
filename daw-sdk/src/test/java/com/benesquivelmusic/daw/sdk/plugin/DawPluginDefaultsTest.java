package com.benesquivelmusic.daw.sdk.plugin;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DawPluginDefaultsTest {

    @Test
    void shouldDeriveAutomatableParametersFromGenericParameters() {
        DawPlugin plugin = new ParametricTestPlugin(List.of(
                new PluginParameter(0, "Gain", 0.0, 1.0, 0.75),
                new PluginParameter(1, "Cutoff", 20.0, 20000.0, 1000.0)));

        List<AutomatableParameter> derived = plugin.getAutomatableParameters();

        assertThat(derived).hasSize(2);
        assertThat(derived.get(0).id()).isEqualTo(0);
        assertThat(derived.get(0).displayName()).isEqualTo("Gain");
        assertThat(derived.get(0).minValue()).isEqualTo(0.0);
        assertThat(derived.get(0).maxValue()).isEqualTo(1.0);
        assertThat(derived.get(0).defaultValue()).isEqualTo(0.75);
        assertThat(derived.get(0).unit()).isEmpty();
        assertThat(derived.get(1).displayName()).isEqualTo("Cutoff");
    }

    @Test
    void shouldReturnEmptyListWhenNoParameters() {
        DawPlugin plugin = new ParametricTestPlugin(List.of());

        assertThat(plugin.getAutomatableParameters()).isEmpty();
    }

    @Test
    void shouldHaveNoOpDefaultSetter() {
        DawPlugin plugin = new ParametricTestPlugin(List.of(
                new PluginParameter(0, "Gain", 0.0, 1.0, 0.5)));

        // Default implementation must not throw.
        plugin.setAutomatableParameter(0, 0.25);
    }

    /** Minimal plugin exposing a configurable parameter list for the tests. */
    private static final class ParametricTestPlugin implements DawPlugin {

        private final List<PluginParameter> parameters;

        ParametricTestPlugin(List<PluginParameter> parameters) {
            this.parameters = parameters;
        }

        @Override
        public PluginDescriptor getDescriptor() {
            return new PluginDescriptor(
                    "test.plugin", "Test", "1.0", "Tests", PluginType.EFFECT);
        }

        @Override public void initialize(PluginContext context) {}
        @Override public void activate() {}
        @Override public void deactivate() {}
        @Override public void dispose() {}

        @Override
        public Optional<AudioProcessor> asAudioProcessor() {
            return Optional.empty();
        }

        @Override
        public List<PluginParameter> getParameters() {
            return parameters;
        }
    }
}
