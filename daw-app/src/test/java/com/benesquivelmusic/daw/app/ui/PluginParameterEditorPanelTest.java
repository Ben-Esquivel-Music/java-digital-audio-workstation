package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.plugin.parameter.ABComparison;
import com.benesquivelmusic.daw.core.plugin.parameter.ParameterPreset;
import com.benesquivelmusic.daw.core.plugin.parameter.PluginParameterState;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(JavaFxToolkitExtension.class)
class PluginParameterEditorPanelTest {

    private PluginParameterEditorPanel createOnFxThread(List<PluginParameter> params) throws Exception {
        AtomicReference<PluginParameterEditorPanel> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(new PluginParameterEditorPanel(params));
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
        return ref.get();
    }

    private void runOnFxThread(Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
    }

    @Test
    void shouldRejectNullParameters() {
        assertThatThrownBy(() -> new PluginParameterEditorPanel(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldCreatePanelWithParameters() throws Exception {

        List<PluginParameter> params = List.of(
                new PluginParameter(0, "Gain", -24.0, 24.0, 0.0),
                new PluginParameter(1, "Mix", 0.0, 1.0, 0.5)
        );

        PluginParameterEditorPanel panel = createOnFxThread(params);

        assertThat(panel).isNotNull();
        assertThat(panel.getState()).isNotNull();
        assertThat(panel.getState().getParameters()).hasSize(2);
    }

    @Test
    void shouldHaveStyleClass() throws Exception {

        PluginParameterEditorPanel panel = createOnFxThread(List.of(
                new PluginParameter(0, "Test", 0.0, 1.0, 0.5)
        ));

        assertThat(panel.getStyleClass()).contains("plugin-parameter-editor");
    }

    @Test
    void shouldProvideAbComparison() throws Exception {

        PluginParameterEditorPanel panel = createOnFxThread(List.of(
                new PluginParameter(0, "Gain", -24.0, 24.0, 0.0)
        ));

        ABComparison ab = panel.getAbComparison();
        assertThat(ab).isNotNull();
        assertThat(ab.getActiveSlot()).isEqualTo(ABComparison.Slot.A);
    }

    @Test
    void shouldSetPresets() throws Exception {

        PluginParameterEditorPanel panel = createOnFxThread(List.of(
                new PluginParameter(0, "Gain", -24.0, 24.0, 0.0)
        ));

        List<ParameterPreset> presets = List.of(
                ParameterPreset.factory("Preset 1", Map.of(0, 6.0)),
                ParameterPreset.user("Preset 2", Map.of(0, -3.0))
        );

        runOnFxThread(() -> panel.setPresets(presets));

        assertThat(panel.getPresetComboBox().getItems()).hasSize(2);
        assertThat(panel.getPresetComboBox().getItems()).containsExactly("Preset 1", "Preset 2");
    }

    @Test
    void shouldProvideAbToggleButton() throws Exception {

        PluginParameterEditorPanel panel = createOnFxThread(List.of(
                new PluginParameter(0, "Gain", -24.0, 24.0, 0.0)
        ));

        assertThat(panel.getAbToggleButton()).isNotNull();
        assertThat(panel.getAbToggleButton().getText()).isEqualTo("A");
    }

    @Test
    void shouldHandleEmptyParameterList() throws Exception {

        PluginParameterEditorPanel panel = createOnFxThread(List.of());

        assertThat(panel).isNotNull();
        assertThat(panel.getState().getParameters()).isEmpty();
    }
}
