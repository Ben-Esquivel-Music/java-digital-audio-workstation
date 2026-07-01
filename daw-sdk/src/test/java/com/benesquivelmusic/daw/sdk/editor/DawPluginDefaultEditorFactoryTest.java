package com.benesquivelmusic.daw.sdk.editor;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the backwards-compatible default of
 * {@link DawPlugin#editorFactory()}: a plugin that only overrides
 * {@code getParameters()} gets a host-generated declarative editor carrying
 * exactly those parameters (Plugin View Design Book §4.2).
 */
class DawPluginDefaultEditorFactoryTest {

    @Test
    void defaultEditorFactoryCarriesOverriddenParameters() {
        List<PluginParameter> params = List.of(
                new PluginParameter(0, "Gain", 0.0, 1.0, 0.5),
                new PluginParameter(1, "Mix", 0.0, 1.0, 1.0));
        DawPlugin plugin = new ParametricPlugin(params);

        PluginEditorFactory factory = plugin.editorFactory();

        assertThat(factory).isInstanceOf(PluginEditorFactory.Declarative.class);
        assertThat(((PluginEditorFactory.Declarative) factory).parameters())
                .isEqualTo(params);
    }

    @Test
    void defaultEditorFactoryForBarePluginIsEmptyDeclarative() {
        DawPlugin plugin = new MinimalPlugin();

        PluginEditorFactory factory = plugin.editorFactory();

        assertThat(factory).isInstanceOf(PluginEditorFactory.Declarative.class);
        assertThat(((PluginEditorFactory.Declarative) factory).parameters()).isEmpty();
    }

    /** Lifecycle + descriptor only; inherits the default (empty) {@code getParameters()}. */
    private static class MinimalPlugin implements DawPlugin {

        @Override
        public PluginDescriptor getDescriptor() {
            return new PluginDescriptor(
                    "test.plugin", "Test", "1.0", "Tests", PluginType.EFFECT);
        }

        @Override public void initialize(PluginContext context) {}
        @Override public void activate() {}
        @Override public void deactivate() {}
        @Override public void dispose() {}
    }

    /** Overrides only {@code getParameters()} — the discriminating case for the default factory. */
    private static final class ParametricPlugin extends MinimalPlugin {

        private final List<PluginParameter> parameters;

        ParametricPlugin(List<PluginParameter> parameters) {
            this.parameters = parameters;
        }

        @Override
        public List<PluginParameter> getParameters() {
            return parameters;
        }
    }
}
