package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.core.plugin.BuiltInDawPlugin;
import com.benesquivelmusic.daw.core.plugin.BusCompressorPlugin;
import com.benesquivelmusic.daw.core.plugin.DeEsserPlugin;
import com.benesquivelmusic.daw.core.plugin.DitherPlugin;
import com.benesquivelmusic.daw.core.plugin.NoiseGatePlugin;
import com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.function.Supplier;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 302 — the batch-1/2 declarative built-ins (§8.3 items 1–2).
 * {@code DitherPlugin}, {@code NoiseGatePlugin}, {@code DeEsserPlugin} and
 * {@code BusCompressorPlugin} ride the SDK's default {@code editorFactory()}
 * (§4.2 — "a plugin that overrides only {@code getParameters()} gets a
 * host-generated parameter-grid editor for free"): each yields a
 * {@link PluginEditorFactory.Declarative} whose parameters equal
 * {@code getParameters()}, and activating each through the host pipeline
 * produces the §6.2 host parameter grid — their bespoke daw-app view classes
 * are deleted.
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class BuiltInDeclarativeEditorsTest {

    private static final List<Supplier<BuiltInDawPlugin>> DECLARATIVE_BUILT_INS = List.of(
            DitherPlugin::new,
            NoiseGatePlugin::new,
            DeEsserPlugin::new,
            BusCompressorPlugin::new);

    @Test
    void eachDeclarativeBuiltInReportsItsOwnParameterList() {
        for (Supplier<BuiltInDawPlugin> constructor : DECLARATIVE_BUILT_INS) {
            BuiltInDawPlugin plugin = constructor.get();
            PluginEditorFactory factory = plugin.editorFactory();

            assertThat(factory)
                    .as("%s must expose a Declarative editor factory",
                            plugin.getClass().getSimpleName())
                    .isInstanceOf(PluginEditorFactory.Declarative.class);
            assertThat(((PluginEditorFactory.Declarative) factory).parameters())
                    .as("%s's Declarative parameters must equal getParameters()",
                            plugin.getClass().getSimpleName())
                    .isEqualTo(plugin.getParameters());
            assertThat(plugin.getParameters())
                    .as("a declarative built-in without parameters would render an "
                            + "empty grid — the §1.4-era views carried real controls")
                    .isNotEmpty();
        }
    }

    @Test
    void activatingEachDeclarativeBuiltInYieldsTheHostParameterGrid() {
        for (Supplier<BuiltInDawPlugin> constructor : DECLARATIVE_BUILT_INS) {
            BuiltInDawPlugin plugin = constructor.get();
            plugin.initialize(stubContext());
            plugin.activate();
            PluginEditorSession session = runOnFxThread(() ->
                    PluginEditorSession.open(plugin, null,
                            new PluginEditorSession.Deps(() -> 48_000.0, () -> null, null, null)));
            try {
                assertThat(session.frame().getBody())
                        .as("%s's editor body must be the §6.2 host parameter grid",
                                plugin.getClass().getSimpleName())
                        .isInstanceOf(ParameterGridPanel.class);
            } finally {
                runOnFxThread(() -> {
                    session.dispose();
                    return null;
                });
                plugin.deactivate();
                plugin.dispose();
            }
        }
    }

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return 48_000.0; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) { }
        };
    }
}
