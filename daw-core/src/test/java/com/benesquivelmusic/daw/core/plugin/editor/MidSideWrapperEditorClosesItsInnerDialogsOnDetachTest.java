package com.benesquivelmusic.daw.core.plugin.editor;

import com.benesquivelmusic.daw.core.plugin.MidSideWrapperPlugin;
import com.benesquivelmusic.daw.core.plugin.MidSideWrapperPlugin.ChainOwner;
import com.benesquivelmusic.daw.sdk.editor.EditorContext;
import com.benesquivelmusic.daw.sdk.editor.PluginParameterStore;
import com.benesquivelmusic.daw.sdk.editor.Theme;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;
import javafx.scene.AccessibleAction;
import javafx.scene.Scene;
import javafx.scene.control.ListCell;
import javafx.scene.control.Slider;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.benesquivelmusic.daw.core.plugin.editor.HeadlessFxToolkit.nodes;
import static com.benesquivelmusic.daw.core.plugin.editor.HeadlessFxToolkit.onFx;
import static com.benesquivelmusic.daw.core.plugin.editor.HeadlessFxToolkit.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Mid/Side wrapper's editor closes the inner parameter dialogs its panes
 * opened when the host detaches it (PR #989 review 5711460812), on the
 * headless JavaFX toolkit ({@link HeadlessFxToolkit}). The editor is built
 * as the host builds it, over an initialized wrapper with one inner plug-in,
 * and its context's store carries no gate, as a disposed session leaves it,
 * so the store admits every write. A dialog opened by the inner chain's
 * double-click handler is hidden by {@code detach()}, and an assistive
 * technology's {@code SET_VALUE} on its slider, a control taken before the
 * detach, then leaves the inner plug-in unwritten.
 */
class MidSideWrapperEditorClosesItsInnerDialogsOnDetachTest {

    private static final int GAIN_ID = 1;
    private static final String DIALOG_TITLE = "Probe — Parameters (MID chain)";

    @Test
    void detachHidesTheOpenParameterDialogAndItsSliderWritesNothingAfterIt() throws Exception {
        HeadlessFxToolkit.start();
        MidSideWrapperPlugin wrapper = new MidSideWrapperPlugin();
        wrapper.initialize(stubContext());
        ProbePlugin probe = new ProbePlugin();
        wrapper.addPlugin(ChainOwner.MID, probe);
        MidSideWrapperEditor editor = (MidSideWrapperEditor) wrapper.editorFactory();
        Stage stage = onFx(() -> show(editor.createPanel(contextWithoutAGate())));
        try {
            runOnFx(() -> doubleClick(cellOf(stage, probe)));
            Stage dialog = onFx(() -> showingDialog().orElseThrow());
            Slider gain = onFx(() -> sliderOf(dialog));
            runOnFx(() -> gain.executeAccessibleAction(AccessibleAction.SET_VALUE, 6.0));
            assertThat(probe.writes).as("fixture: attached, the dialog writes the plug-in").containsExactly(6.0);

            runOnFx(editor::detach);

            assertThat(onFx(dialog::isShowing)).as("detached: the dialog is hidden").isFalse();
            runOnFx(() -> gain.executeAccessibleAction(AccessibleAction.SET_VALUE, 12.0));
            assertThat(probe.writes).as("and its slider writes the plug-in nothing").containsExactly(6.0);
            assertThat(onFx(gain::getValue)).as("it shows what it showed").isEqualTo(6.0);
        } finally {
            runOnFx(() -> {
                showingDialog().ifPresent(Stage::hide);
                stage.hide();
            });
            wrapper.dispose();
        }
    }

    // ── rig ──────────────────────────────────────────────────────────────────

    /** Shows the editor's panel in a stage of its own, so its inner chains' lists have cells. FX thread. */
    private static Stage show(Region panel) {
        Stage stage = new Stage();
        stage.setScene(new Scene(panel, 640, 420));
        stage.show();
        panel.applyCss();
        panel.layout();
        return stage;
    }

    /** The list cell that shows {@code item}. FX thread. */
    @SuppressWarnings("unchecked")
    private static ListCell<DawPlugin> cellOf(Stage stage, DawPlugin item) {
        return nodes(stage.getScene().getRoot()).filter(ListCell.class::isInstance)
                .map(node -> (ListCell<DawPlugin>) node).filter(cell -> cell.getItem() == item)
                .findFirst().orElseThrow();
    }

    /** A primary-button double click, as the cell's mouse-clicked handler receives it. FX thread. */
    private static void doubleClick(ListCell<DawPlugin> cell) {
        cell.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, MouseButton.PRIMARY, 2,
                false, false, false, false, true, false, false, true, false, false, null));
    }

    /** The probe's parameter dialog, while it shows. FX thread. */
    private static Optional<Stage> showingDialog() {
        return Window.getWindows().stream().filter(Stage.class::isInstance).map(Stage.class::cast)
                .filter(window -> window.isShowing() && DIALOG_TITLE.equals(window.getTitle())).findFirst();
    }

    private static Slider sliderOf(Stage dialog) {
        dialog.getScene().getRoot().applyCss();
        return nodes(dialog.getScene().getRoot()).filter(Slider.class::isInstance)
                .map(Slider.class::cast).findFirst().orElseThrow();
    }

    /** An editor context whose store carries no gate, as a disposed session leaves it: it admits every write. */
    private static EditorContext contextWithoutAGate() {
        PluginParameterStore store = new PluginParameterStore(List.of());
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

    /** An inner-chain plug-in with one continuous gain; it records every value written to it. */
    private static final class ProbePlugin implements DawPlugin {
        final List<Double> writes = new CopyOnWriteArrayList<>();

        @Override public PluginDescriptor getDescriptor() {
            return new PluginDescriptor("com.test.inner.probe", "Probe", "1.0.0", "Test Vendor", PluginType.EFFECT);
        }
        @Override public void initialize(PluginContext context) { }
        @Override public void activate() { }
        @Override public void deactivate() { }
        @Override public void dispose() { }
        @Override public List<PluginParameter> getParameters() {
            return List.of(new PluginParameter(GAIN_ID, "Gain", -24.0, 24.0, 0.0));
        }
        @Override public void setAutomatableParameter(int parameterId, double value) {
            if (parameterId == GAIN_ID) {
                writes.add(value);
            }
        }
    }
}
