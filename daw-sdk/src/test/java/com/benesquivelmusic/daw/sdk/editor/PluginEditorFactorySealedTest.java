package com.benesquivelmusic.daw.sdk.editor;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guardrails for {@link PluginEditorFactory}: it must stay sealed over
 * exactly the four permitted modes so the host can pattern-match exhaustively
 * (Plugin View Design Book §2.1, §4.1).
 */
class PluginEditorFactorySealedTest {

    @Test
    void factoryIsSealedOverExactlyFourModes() {
        assertThat(PluginEditorFactory.class.isSealed()).isTrue();

        List<String> permitted =
                Arrays.stream(PluginEditorFactory.class.getPermittedSubclasses())
                        .map(Class::getSimpleName)
                        .toList();

        assertThat(permitted)
                .containsExactlyInAnyOrder("Declarative", "Panel", "Canvas", "Faulted");
    }

    @Test
    void panelAndCanvasAreInterfacesDeclarativeAndFaultedAreRecords() {
        assertThat(PluginEditorFactory.Panel.class.isInterface()).isTrue();
        assertThat(PluginEditorFactory.Canvas.class.isInterface()).isTrue();
        assertThat(PluginEditorFactory.Declarative.class.isRecord()).isTrue();
        assertThat(PluginEditorFactory.Faulted.class.isRecord()).isTrue();
    }

    @Test
    void declarativeRoundTripsParametersAndHints() {
        List<PluginParameter> params = List.of(
                new PluginParameter(0, "Gain", 0.0, 1.0, 0.5),
                new PluginParameter(1, "Mix", 0.0, 1.0, 1.0));
        EditorHints hints = EditorHints.standard();

        PluginEditorFactory.Declarative declarative =
                new PluginEditorFactory.Declarative(params, hints);

        assertThat(declarative.parameters()).isEqualTo(params);
        assertThat(declarative.hints()).isEqualTo(hints);
    }
}
