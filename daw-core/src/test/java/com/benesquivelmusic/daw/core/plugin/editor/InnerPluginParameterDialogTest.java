package com.benesquivelmusic.daw.core.plugin.editor;

import com.benesquivelmusic.daw.core.plugin.MidSideWrapperPlugin.ChainOwner;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;
import javafx.scene.AccessibleAction;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static com.benesquivelmusic.daw.core.plugin.editor.HeadlessFxToolkit.nodes;
import static com.benesquivelmusic.daw.core.plugin.editor.HeadlessFxToolkit.onFx;
import static com.benesquivelmusic.daw.core.plugin.editor.HeadlessFxToolkit.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The inner-chain parameter dialog under the host's UI-write admission (PR
 * #989 review 5711460812), on the headless JavaFX toolkit
 * ({@link HeadlessFxToolkit}): while the admission says no, an assistive
 * technology's {@code FIRE} on a toggle and {@code SET_VALUE} on a slider
 * leave the plug-in's {@code setAutomatableParameter} uncalled, and each
 * control shows what it showed before — the plug-in offers no read of its
 * parameters; while it says yes, the same acts write the plug-in.
 */
class InnerPluginParameterDialogTest {

    private static final int BYPASS_ID = 1;
    private static final int GAIN_ID = 2;
    private static final String TITLE = "Probe — Parameters (MID chain)";

    @Test
    void whileEditsAreNotAdmittedTheControlsWriteNothingAndShowWhatTheyShowed() throws Exception {
        ProbePlugin probe = new ProbePlugin();
        Stage dialog = open(probe, () -> false);
        try {
            ToggleButton toggle = onFx(() -> toggleOf(dialog));
            Slider slider = onFx(() -> sliderOf(dialog));

            runOnFx(() -> toggle.executeAccessibleAction(AccessibleAction.FIRE));
            runOnFx(() -> slider.executeAccessibleAction(AccessibleAction.SET_VALUE, 12.0));

            assertThat(probe.writes).as("refused: the plug-in is not written").isEmpty();
            assertThat(onFx(toggle::isSelected)).as("the toggle shows OFF again").isFalse();
            assertThat(onFx(toggle::getText)).isEqualTo("OFF");
            assertThat(onFx(slider::getValue)).as("the slider shows its value again").isZero();
        } finally {
            runOnFx(dialog::hide);
        }
    }

    @Test
    void whileEditsAreAdmittedTheControlsWriteThePlugin() throws Exception {
        ProbePlugin probe = new ProbePlugin();
        Stage dialog = open(probe, () -> true);
        try {
            ToggleButton toggle = onFx(() -> toggleOf(dialog));
            Slider slider = onFx(() -> sliderOf(dialog));

            runOnFx(() -> toggle.executeAccessibleAction(AccessibleAction.FIRE));
            runOnFx(() -> slider.executeAccessibleAction(AccessibleAction.SET_VALUE, 12.0));

            assertThat(probe.writes).containsExactly(Map.entry(BYPASS_ID, 1.0), Map.entry(GAIN_ID, 12.0));
            assertThat(onFx(toggle::isSelected)).isTrue();
            assertThat(onFx(toggle::getText)).isEqualTo("ON");
            assertThat(onFx(slider::getValue)).isEqualTo(12.0);
        } finally {
            runOnFx(dialog::hide);
        }
    }

    private static Stage open(DawPlugin plugin, BooleanSupplier editsAdmitted) throws Exception {
        HeadlessFxToolkit.start();
        return onFx(() -> {
            InnerPluginParameterDialog.open(plugin, ChainOwner.MID, editsAdmitted);
            return Window.getWindows().stream().filter(Stage.class::isInstance).map(Stage.class::cast)
                    .filter(stage -> stage.isShowing() && TITLE.equals(stage.getTitle())).findFirst().orElseThrow();
        });
    }

    private static ToggleButton toggleOf(Stage dialog) {
        return nodes(dialog.getScene().getRoot()).filter(ToggleButton.class::isInstance)
                .map(ToggleButton.class::cast).findFirst().orElseThrow();
    }

    private static Slider sliderOf(Stage dialog) {
        dialog.getScene().getRoot().applyCss();
        return nodes(dialog.getScene().getRoot()).filter(Slider.class::isInstance)
                .map(Slider.class::cast).findFirst().orElseThrow();
    }

    /** An inner-chain plug-in with a boolean toggle and a continuous gain; it records every parameter write. */
    private static final class ProbePlugin implements DawPlugin {
        final List<Map.Entry<Integer, Double>> writes = new CopyOnWriteArrayList<>();

        @Override public PluginDescriptor getDescriptor() {
            return new PluginDescriptor("com.test.inner.probe", "Probe", "1.0.0", "Test Vendor", PluginType.EFFECT);
        }
        @Override public void initialize(PluginContext context) { }
        @Override public void activate() { }
        @Override public void deactivate() { }
        @Override public void dispose() { }
        @Override public List<PluginParameter> getParameters() {
            return List.of(
                    new PluginParameter(BYPASS_ID, "Bypass Toggle", 0.0, 1.0, 0.0),
                    new PluginParameter(GAIN_ID, "Gain", -24.0, 24.0, 0.0));
        }
        @Override public void setAutomatableParameter(int parameterId, double value) {
            writes.add(Map.entry(parameterId, value));
        }
    }
}
