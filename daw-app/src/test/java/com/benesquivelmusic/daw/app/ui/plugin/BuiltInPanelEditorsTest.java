package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.core.plugin.ConvolutionReverbPlugin;
import com.benesquivelmusic.daw.core.plugin.ExciterPlugin;
import com.benesquivelmusic.daw.core.plugin.MidSideWrapperPlugin;
import com.benesquivelmusic.daw.core.plugin.MultibandCompressorPlugin;
import com.benesquivelmusic.daw.core.plugin.TransientShaperPlugin;
import com.benesquivelmusic.daw.core.plugin.TruePeakLimiterPlugin;
import com.benesquivelmusic.daw.core.plugin.TunerPlugin;
import com.benesquivelmusic.daw.core.plugin.VirtualKeyboardPlugin;
import com.benesquivelmusic.daw.core.plugin.builtin.midi.ArpeggiatorPlugin;
import com.benesquivelmusic.daw.sdk.editor.ChromePolicy;
import com.benesquivelmusic.daw.sdk.editor.EditorContext;
import com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory;
import com.benesquivelmusic.daw.sdk.editor.PluginParameterStore;
import com.benesquivelmusic.daw.sdk.editor.Theme;
import com.benesquivelmusic.daw.sdk.midi.SoundFontRendererException;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;
import javafx.scene.layout.Region;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.function.Supplier;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * Story 302 — the Panel built-ins (§8.3 items 3, 4, 6, 7). Each returns a
 * {@link PluginEditorFactory.Panel} whose {@code createPanel(EditorContext)}
 * yields a non-null {@link Region} (the custom layout ported into
 * {@code daw-core}'s {@code plugin.editor} package from the deleted bespoke
 * views), and the two §8.3-item-6 utilities ({@code VirtualKeyboardPlugin},
 * {@code TunerPlugin}) report {@link ChromePolicy#MINIMAL}.
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class BuiltInPanelEditorsTest {

    private record PanelCase(String name, Supplier<DawPlugin> constructor, ChromePolicy expectedChrome) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static List<PanelCase> panelBuiltIns() {
        return List.of(
                new PanelCase("TruePeakLimiterPlugin", TruePeakLimiterPlugin::new, ChromePolicy.STANDARD),
                new PanelCase("TransientShaperPlugin", TransientShaperPlugin::new, ChromePolicy.STANDARD),
                new PanelCase("MultibandCompressorPlugin", MultibandCompressorPlugin::new, ChromePolicy.STANDARD),
                new PanelCase("ConvolutionReverbPlugin", ConvolutionReverbPlugin::new, ChromePolicy.STANDARD),
                new PanelCase("ExciterPlugin", ExciterPlugin::new, ChromePolicy.STANDARD),
                new PanelCase("MidSideWrapperPlugin", MidSideWrapperPlugin::new, ChromePolicy.STANDARD),
                new PanelCase("VirtualKeyboardPlugin", VirtualKeyboardPlugin::new, ChromePolicy.MINIMAL),
                new PanelCase("TunerPlugin", TunerPlugin::new, ChromePolicy.MINIMAL),
                new PanelCase("ArpeggiatorPlugin", ArpeggiatorPlugin::new, ChromePolicy.STANDARD));
    }

    @ParameterizedTest
    @MethodSource("panelBuiltIns")
    void eachPanelBuiltInCreatesANonNullRegionAndReportsItsChrome(PanelCase panelCase) {
        DawPlugin plugin = panelCase.constructor().get();
        try {
            try {
                plugin.initialize(stubContext());
            } catch (SoundFontRendererException e) {
                // Environment-availability skip (the NativeCodecAvailability
                // pattern): CI runners without an audio device cannot open the
                // Java Sound synthesizer that VirtualKeyboardPlugin's real
                // renderer needs. Skip only this case; the panel contract is
                // still verified wherever a synthesizer exists.
                abort("No Java Sound synthesizer available in this environment: " + e.getMessage());
            }
            PluginEditorFactory factory = plugin.editorFactory();

            assertThat(factory)
                    .as("%s must expose a Panel editor factory",
                            plugin.getClass().getSimpleName())
                    .isInstanceOf(PluginEditorFactory.Panel.class);
            assertThat(factory.hints().chrome())
                    .as("%s's chrome policy", plugin.getClass().getSimpleName())
                    .isEqualTo(panelCase.expectedChrome());

            PluginEditorFactory.Panel panel = (PluginEditorFactory.Panel) factory;
            Region region = runOnFxThread(() -> panel.createPanel(contextFor(plugin)));
            assertThat(region)
                    .as("%s's createPanel must yield a non-null Region",
                            plugin.getClass().getSimpleName())
                    .isNotNull();
        } finally {
            plugin.dispose();
        }
    }

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return 48_000.0; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) { }
        };
    }

    /**
     * A minimal host-side {@link EditorContext}: a real
     * {@link PluginParameterStore} over the plugin's declared parameters (the
     * §2.6 bridge the panels mirror gestures into) plus neutral theme and
     * no-op host requests.
     */
    private static EditorContext contextFor(DawPlugin plugin) {
        PluginParameterStore store = new PluginParameterStore(plugin.getParameters());
        ObservableValue<Theme> theme = new SimpleObjectProperty<>(Theme.neutral());
        return new EditorContext() {
            @Override public double sampleRate() { return 48_000.0; }
            @Override public Theme theme() { return theme.getValue(); }
            @Override public ObservableValue<Theme> themeProperty() { return theme; }
            @Override public PluginParameterStore parameterStore() { return store; }
            @Override public void requestResize(int width, int height) { }
            @Override public void requestRender() { }
            @Override public void requestAnimationTimer(double framesPerSecond) { }
            @Override public void postFault(Throwable error, String hint) { }
        };
    }
}
