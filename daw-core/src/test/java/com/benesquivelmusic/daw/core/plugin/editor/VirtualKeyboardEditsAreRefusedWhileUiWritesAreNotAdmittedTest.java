package com.benesquivelmusic.daw.core.plugin.editor;

import com.benesquivelmusic.daw.core.midi.KeyboardPreset;
import com.benesquivelmusic.daw.core.midi.KeyboardProcessor;
import com.benesquivelmusic.daw.core.midi.VelocityCurve;
import com.benesquivelmusic.daw.core.plugin.VirtualKeyboardPlugin;
import com.benesquivelmusic.daw.sdk.editor.EditorContext;
import com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory;
import com.benesquivelmusic.daw.sdk.editor.PluginParameterStore;
import com.benesquivelmusic.daw.sdk.editor.Theme;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;
import javafx.scene.AccessibleAction;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.benesquivelmusic.daw.core.plugin.editor.HeadlessFxToolkit.nodes;
import static com.benesquivelmusic.daw.core.plugin.editor.HeadlessFxToolkit.onFx;
import static com.benesquivelmusic.daw.core.plugin.editor.HeadlessFxToolkit.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The virtual keyboard's editor under the host's UI-write admission (PR #989
 * review 5711460812; the studio engineer's ruling 9 R9-5), built as the host
 * builds it, with an editor context whose store carries a gate, on the
 * headless JavaFX toolkit ({@link HeadlessFxToolkit}). While the gate says no,
 * an assistive technology's action that starts a capture or sound, changes a
 * setting or deletes the recorded clip — Record on, Play, Clear, transpose,
 * velocity, curve and preset — changes nothing in the processor, and its
 * control shows the processor again; Record off and Stop, which end work the
 * processor is already running, pass as Stop does. Once the gate says yes,
 * the refused acts edit.
 */
class VirtualKeyboardEditsAreRefusedWhileUiWritesAreNotAdmittedTest {

    private final AtomicBoolean admitted = new AtomicBoolean(true);
    private VirtualKeyboardPlugin plugin;
    private KeyboardProcessor processor;
    private Region panel;
    private Stage stage;

    @BeforeEach
    void openTheEditor() throws Exception {
        HeadlessFxToolkit.start();
        plugin = new VirtualKeyboardPlugin();
        plugin.initialize(stubContext());
        processor = plugin.getProcessor();
        PluginEditorFactory.Panel editor = (PluginEditorFactory.Panel) plugin.editorFactory();
        EditorContext context = gatedContext();
        // Shown, so the controls have their skins: a combo box's skin fires its action on a value change.
        stage = onFx(() -> {
            panel = editor.createPanel(context);
            Stage window = new Stage();
            window.setScene(new Scene(panel, 760, 320));
            window.show();
            panel.applyCss();
            panel.layout();
            return window;
        });
    }

    @AfterEach
    void closeTheEditor() throws Exception {
        if (plugin != null) {
            runOnFx(() -> {
                if (stage != null) {
                    stage.hide();
                }
                if (processor != null) {
                    processor.stopRecording();
                    processor.stopPlayback();
                }
                plugin.dispose();
            });
        }
    }

    @Test
    void whileTheGateSaysNoTheActsThatStartOrChangeOrDeleteAreRefusedAndShowTheProcessor() throws Exception {
        recordANote();
        KeyboardPreset before = onFx(processor::getPreset);
        admitted.set(false);

        runOnFx(() -> record().executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(processor::isRecording)).as("Record on is refused: no capture starts").isFalse();
        assertThat(onFx(() -> record().isSelected())).as("and the toggle shows it").isFalse();

        runOnFx(() -> button("▶ Play").executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(processor::isPlaying)).as("Play is refused: no sound starts").isFalse();

        runOnFx(() -> button("🗑 Clear").executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(() -> processor.getClip().isEmpty())).as("Clear is refused: the take is kept").isFalse();

        runOnFx(() -> transpose().executeAccessibleAction(AccessibleAction.INCREMENT));
        assertThat(onFx(() -> transpose().getValue())).as("transpose is refused and shown again")
                .isEqualTo(before.transpose());

        runOnFx(() -> velocity().executeAccessibleAction(AccessibleAction.SET_VALUE, 40.0));
        assertThat(onFx(() -> velocity().getValue())).as("velocity is refused and shown again")
                .isEqualTo((double) before.defaultVelocity());

        runOnFx(() -> curve().getSelectionModel().select(VelocityCurve.HARD));
        assertThat(onFx(() -> curve().getValue())).as("the curve is refused and shown again")
                .isEqualTo(before.velocityCurve());

        runOnFx(() -> preset().getSelectionModel().select(KeyboardPreset.organ()));
        assertThat(onFx(() -> preset().getValue())).as("the preset is refused and shown again").isEqualTo(before);

        assertThat(onFx(processor::getPreset)).as("no setting changed").isEqualTo(before);

        admitted.set(true);
        runOnFx(() -> transpose().executeAccessibleAction(AccessibleAction.INCREMENT));
        assertThat(onFx(() -> processor.getPreset().transpose())).as("admitted: transpose edits")
                .isEqualTo(before.transpose() + 1);
        runOnFx(() -> button("🗑 Clear").executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(() -> processor.getClip().isEmpty())).as("admitted: Clear deletes the take").isTrue();
        runOnFx(() -> record().executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(processor::isRecording)).as("admitted: Record on captures").isTrue();
    }

    @Test
    void whileTheGateSaysNoRecordOffAndStopEndWhatIsRunning() throws Exception {
        recordANote();

        runOnFx(() -> record().executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(processor::isRecording)).as("fixture: recording, the gate saying yes").isTrue();
        admitted.set(false);
        runOnFx(() -> record().executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(processor::isRecording)).as("Record off ends the capture").isFalse();
        assertThat(onFx(() -> record().isSelected())).isFalse();

        admitted.set(true);
        runOnFx(() -> record().executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(processor::isRecording)).as("fixture: recording again").isTrue();
        admitted.set(false);
        runOnFx(() -> button("⏹ Stop").executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(processor::isRecording)).as("Stop ends the capture").isFalse();
        assertThat(onFx(() -> record().isSelected())).as("and the toggle shows it").isFalse();

        admitted.set(true);
        runOnFx(() -> button("▶ Play").executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(processor::isPlaying)).as("fixture: playing the take, the gate saying yes").isTrue();
        admitted.set(false);
        runOnFx(() -> button("⏹ Stop").executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(processor::isPlaying)).as("Stop ends the playback").isFalse();
    }

    // ── rig ──────────────────────────────────────────────────────────────────

    /** Records one held note while the gate says yes, so the clip has a take to play, keep or clear. */
    private void recordANote() throws Exception {
        runOnFx(() -> record().executeAccessibleAction(AccessibleAction.FIRE));
        runOnFx(() -> processor.noteOn(60, 100));
        runOnFx(() -> record().executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(() -> !processor.isRecording() && !processor.getClip().isEmpty()))
                .as("fixture: a take was recorded").isTrue();
    }

    /** An editor context as the host's: a store with a gate reading {@link #admitted}. */
    private EditorContext gatedContext() {
        PluginParameterStore store = new PluginParameterStore(plugin.getParameters());
        store.addUiWriteGate(admitted::get, _ -> { });
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

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return 48_000.0; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) { }
        };
    }

    private ToggleButton record() {
        return nodes(panel).filter(ToggleButton.class::isInstance).map(ToggleButton.class::cast)
                .filter(button -> "⏺ Record".equals(button.getText())).findFirst().orElseThrow();
    }

    private Button button(String text) {
        return nodes(panel).filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> text.equals(button.getText())).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Spinner<Integer> transpose() {
        return nodes(panel).filter(Spinner.class::isInstance).map(node -> (Spinner<Integer>) node)
                .findFirst().orElseThrow();
    }

    private Slider velocity() {
        return nodes(panel).filter(Slider.class::isInstance).map(Slider.class::cast).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private ComboBox<VelocityCurve> curve() {
        return combos().stream().filter(combo -> combo.getItems().getFirst() instanceof VelocityCurve)
                .map(combo -> (ComboBox<VelocityCurve>) combo).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private ComboBox<KeyboardPreset> preset() {
        return combos().stream().filter(combo -> combo.getItems().getFirst() instanceof KeyboardPreset)
                .map(combo -> (ComboBox<KeyboardPreset>) combo).findFirst().orElseThrow();
    }

    private List<ComboBox<?>> combos() {
        return nodes(panel).filter(ComboBox.class::isInstance).<ComboBox<?>>map(node -> (ComboBox<?>) node).toList();
    }
}
