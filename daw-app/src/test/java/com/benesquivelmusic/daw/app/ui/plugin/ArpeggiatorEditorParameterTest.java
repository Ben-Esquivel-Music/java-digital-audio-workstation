package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.core.plugin.builtin.midi.ArpeggiatorPlugin;
import com.benesquivelmusic.daw.sdk.editor.EditorContext;
import com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory;
import com.benesquivelmusic.daw.sdk.editor.PluginParameterStore;
import com.benesquivelmusic.daw.sdk.editor.Theme;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.stream.Stream;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
class ArpeggiatorEditorParameterTest {
    @Test
    void gesturesAndRecallReachMidiParametersWhileEchoAndReopeningPreserveStoreOwnership() {
        runOnFxThread(() -> {
            var plugin = new ArpeggiatorPlugin();
            var store = new PluginParameterStore(plugin.getParameters());
            var context = context(store);
            var editor = (PluginEditorFactory.Panel) plugin.editorFactory();
            try {
                var controls = Controls.from(editor.createPanel(context));
                double[] gesture = {6, 5, 4, 125, 60, 1};
                controls.setValues(gesture);
                assertThat(plugin.getRate()).isEqualTo(ArpeggiatorPlugin.Rate.SIXTEENTH);
                drain(store, plugin);
                assertValues(new PluginParameterStore(plugin.getParameters()), gesture);

                double[] recalled = {1, 1, 2, 25, 15, 0};
                for (int id = 0; id < recalled.length; id++) {
                    store.writeFromUiById(id, recalled[id]);
                    editor.parameterChanged(id, recalled[id]);
                }
                controls.assertValues(recalled);
                assertValues(new PluginParameterStore(plugin.getParameters()), gesture);
                drain(store, plugin);
                assertValues(new PluginParameterStore(plugin.getParameters()), recalled);

                // An editor echo alone cannot enqueue a processor write.
                editor.parameterChanged(3, 50);
                assertThat(store.drainToAudio(_ -> { throw new AssertionError("Echo wrote to the processor"); })).isZero();
                assertThat(plugin.getGate()).isEqualTo(25);
                assertValues(store, recalled);

                editor.detach();
                editor = (PluginEditorFactory.Panel) plugin.editorFactory();
                Controls.from(editor.createPanel(context)).assertValues(recalled);
                assertValues(store, recalled);
                assertThat(store.drainToAudio(_ -> { throw new AssertionError("Reopening wrote to the processor"); })).isZero();
            } finally {
                editor.detach();
                plugin.dispose();
            }
            return null;
        });
    }

    private static void drain(PluginParameterStore store, ArpeggiatorPlugin plugin) {
        assertThat(store.drainToAudio(index -> plugin.setAutomatableParameter(
                store.parameterIdAt(index), store.value(index)))).isEqualTo(6);
    }

    private static void assertValues(PluginParameterStore store, double[] expected) {
        for (int id = 0; id < expected.length; id++) {
            assertThat(store.valueById(id)).as("parameter %d", id).isEqualTo(expected[id]);
        }
    }

    private record Controls(ComboBox<?> rate, ComboBox<?> pattern, Slider octave, Slider gate,
                            Slider swing, ToggleButton latch) {
        static Controls from(Node root) {
            var choices = nodes(root).filter(ComboBox.class::isInstance).map(node -> (ComboBox<?>) node).toList();
            var sliders = nodes(root).filter(Slider.class::isInstance).map(Slider.class::cast).toList();
            var latch = nodes(root).filter(ToggleButton.class::isInstance).map(ToggleButton.class::cast)
                    .findFirst().orElseThrow();
            return new Controls(choices.get(0), choices.get(1), sliders.get(0), sliders.get(1), sliders.get(2), latch);
        }

        void setValues(double[] values) {
            rate.getSelectionModel().select((int) values[0]);
            pattern.getSelectionModel().select((int) values[1]);
            octave.setValue(values[2]);
            gate.setValue(values[3]);
            swing.setValue(values[4]);
            latch.setSelected(values[5] >= 0.5);
        }

        void assertValues(double[] values) {
            assertThat(rate.getSelectionModel().getSelectedIndex()).isEqualTo((int) values[0]);
            assertThat(pattern.getSelectionModel().getSelectedIndex()).isEqualTo((int) values[1]);
            assertThat(octave.getValue()).isEqualTo(values[2]);
            assertThat(gate.getValue()).isEqualTo(values[3]);
            assertThat(swing.getValue()).isEqualTo(values[4]);
            assertThat(latch.isSelected()).isEqualTo(values[5] >= 0.5);
        }
    }

    private static Stream<Node> nodes(Node node) {
        return node instanceof Parent parent
                ? Stream.concat(Stream.of(node), parent.getChildrenUnmodifiable().stream()
                        .flatMap(ArpeggiatorEditorParameterTest::nodes))
                : Stream.of(node);
    }

    private static EditorContext context(PluginParameterStore store) {
        ObservableValue<Theme> theme = new SimpleObjectProperty<>(Theme.neutral());
        return new EditorContext() {
            @Override public double sampleRate() { return 48_000; }
            @Override public Theme theme() { return theme.getValue(); }
            @Override public ObservableValue<Theme> themeProperty() { return theme; }
            @Override public PluginParameterStore parameterStore() { return store; }
            @Override public void requestResize(int width, int height) { }
            @Override public void requestRender() { }
            @Override public void requestAnimationTimer(double framesPerSecond) { }
            @Override public void postFault(Throwable error, String hint) { throw new AssertionError(hint, error); }
        };
    }
}
