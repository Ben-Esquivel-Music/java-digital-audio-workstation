package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.plugin.parameter.ABComparison;
import com.benesquivelmusic.daw.core.plugin.parameter.ParameterPreset;
import com.benesquivelmusic.daw.core.plugin.parameter.PluginParameterState;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.*;
import java.util.function.BiConsumer;

/**
 * A generic plugin parameter editor panel that generates controls from
 * {@link PluginParameter} metadata.
 *
 * <p>Displays labeled sliders for continuous parameters and toggle buttons
 * for boolean parameters (where min=0, max=1). Each control shows the
 * parameter name, current value, and supports double-click to reset to
 * the parameter's default value.</p>
 *
 * <p>Includes a preset dropdown for loading/saving parameter presets and
 * an A/B comparison toggle button.</p>
 *
 * <p>Parameter changes are reported via the {@code onParameterChanged}
 * callback, allowing real-time wiring to the effects chain.</p>
 */
public final class PluginParameterEditorPanel extends VBox {

    private static final double SLIDER_WIDTH = 200;
    private static final double CONTROL_SPACING = 12;
    private static final String STYLE_CLASS = "plugin-parameter-editor";

    private final PluginParameterState state;
    private final ABComparison abComparison;
    private final Map<Integer, Slider> sliderMap = new LinkedHashMap<>();
    private final Map<Integer, ToggleButton> toggleMap = new LinkedHashMap<>();
    private final Map<Integer, Label> valueLabelMap = new LinkedHashMap<>();
    private final ComboBox<String> presetComboBox;
    private final ToggleButton abToggleButton;
    private final List<ParameterPreset> presets = new ArrayList<>();
    private BiConsumer<Integer, Double> onParameterChanged;

    /**
     * Creates a new plugin parameter editor panel.
     *
     * @param parameters the parameter descriptors to generate controls for
     * @throws NullPointerException if {@code parameters} is {@code null}
     */
    public PluginParameterEditorPanel(List<PluginParameter> parameters) {
        Objects.requireNonNull(parameters, "parameters must not be null");
        getStyleClass().add(STYLE_CLASS);
        setSpacing(8);
        setPadding(new Insets(12));

        this.state = new PluginParameterState(parameters);
        this.abComparison = new ABComparison(state);

        // --- Toolbar: Preset selector + A/B toggle ---
        presetComboBox = new ComboBox<>();
        presetComboBox.setPromptText("Presets…");
        presetComboBox.setPrefWidth(180);
        presetComboBox.setOnAction(e -> loadSelectedPreset());

        abToggleButton = new ToggleButton("A");
        abToggleButton.setTooltip(new Tooltip("Toggle A/B comparison"));
        abToggleButton.setOnAction(e -> handleAbToggle());

        Button copyAbButton = new Button("A→B");
        copyAbButton.setTooltip(new Tooltip("Copy current settings to inactive slot"));
        copyAbButton.setOnAction(e -> abComparison.copyActiveToInactive());

        HBox toolbar = new HBox(8, new Label("Preset:"), presetComboBox,
                new Separator(), abToggleButton, copyAbButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(0, 0, 8, 0));
        getChildren().add(toolbar);

        // --- Parameter controls ---
        FlowPane controlsPane = new FlowPane();
        controlsPane.setHgap(CONTROL_SPACING);
        controlsPane.setVgap(CONTROL_SPACING);

        for (PluginParameter param : parameters) {
            VBox control = createParameterControl(param);
            controlsPane.getChildren().add(control);
        }
        getChildren().add(controlsPane);
    }

    /**
     * Sets the callback invoked when a parameter value changes.
     *
     * <p>The callback receives the parameter id and the new value.</p>
     *
     * @param callback the change callback
     */
    public void setOnParameterChanged(BiConsumer<Integer, Double> callback) {
        this.onParameterChanged = callback;
    }

    /**
     * Returns the parameter state managed by this panel.
     *
     * @return the parameter state
     */
    public PluginParameterState getState() {
        return state;
    }

    /**
     * Returns the A/B comparison manager.
     *
     * @return the A/B comparison manager
     */
    public ABComparison getAbComparison() {
        return abComparison;
    }

    /**
     * Sets the available presets in the preset dropdown.
     *
     * @param presets the list of presets to populate
     */
    public void setPresets(List<ParameterPreset> presets) {
        Objects.requireNonNull(presets, "presets must not be null");
        this.presets.clear();
        this.presets.addAll(presets);
        presetComboBox.getItems().clear();
        for (ParameterPreset preset : presets) {
            presetComboBox.getItems().add(preset.name());
        }
    }

    /**
     * Returns the preset combo box for external access (e.g., adding save actions).
     *
     * @return the preset combo box
     */
    public ComboBox<String> getPresetComboBox() {
        return presetComboBox;
    }

    /**
     * Returns the A/B toggle button.
     *
     * @return the A/B toggle button
     */
    public ToggleButton getAbToggleButton() {
        return abToggleButton;
    }

    /**
     * Refreshes all controls to reflect the current parameter state values.
     */
    public void refreshControls() {
        for (PluginParameter param : state.getParameters()) {
            double value = state.getValue(param.id());
            Slider slider = sliderMap.get(param.id());
            if (slider != null) {
                slider.setValue(value);
            }
            ToggleButton toggle = toggleMap.get(param.id());
            if (toggle != null) {
                toggle.setSelected(value >= 0.5);
            }
            Label valueLabel = valueLabelMap.get(param.id());
            if (valueLabel != null) {
                valueLabel.setText(formatValue(value, param));
            }
        }
    }

    private VBox createParameterControl(PluginParameter param) {
        Label nameLabel = new Label(param.name());
        nameLabel.getStyleClass().add("parameter-name");

        Label valueLabel = new Label(formatValue(param.defaultValue(), param));
        valueLabel.getStyleClass().add("parameter-value");
        valueLabelMap.put(param.id(), valueLabel);

        VBox controlBox = new VBox(4);
        controlBox.setAlignment(Pos.CENTER);
        controlBox.setPadding(new Insets(4));
        controlBox.getStyleClass().add("parameter-control");

        if (isBooleanParameter(param)) {
            ToggleButton toggle = new ToggleButton(param.defaultValue() >= 0.5 ? "ON" : "OFF");
            toggle.setSelected(param.defaultValue() >= 0.5);
            toggle.setTooltip(new Tooltip("Double-click to reset to default"));
            toggle.setOnAction(e -> {
                double newValue = toggle.isSelected() ? 1.0 : 0.0;
                state.setValue(param.id(), newValue);
                toggle.setText(toggle.isSelected() ? "ON" : "OFF");
                valueLabel.setText(formatValue(newValue, param));
                fireParameterChanged(param.id(), newValue);
            });
            toggle.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2) {
                    state.resetToDefault(param.id());
                    double defaultVal = param.defaultValue();
                    toggle.setSelected(defaultVal >= 0.5);
                    toggle.setText(defaultVal >= 0.5 ? "ON" : "OFF");
                    valueLabel.setText(formatValue(defaultVal, param));
                    fireParameterChanged(param.id(), defaultVal);
                }
            });
            toggleMap.put(param.id(), toggle);
            controlBox.getChildren().addAll(nameLabel, toggle, valueLabel);
        } else {
            Slider slider = new Slider(param.minValue(), param.maxValue(), param.defaultValue());
            slider.setPrefWidth(SLIDER_WIDTH);
            slider.setShowTickLabels(true);
            slider.setShowTickMarks(true);
            slider.setTooltip(new Tooltip("Double-click to reset to default"));

            slider.valueProperty().addListener((obs, oldVal, newVal) -> {
                double newValue = newVal.doubleValue();
                state.setValue(param.id(), newValue);
                valueLabel.setText(formatValue(newValue, param));
                fireParameterChanged(param.id(), newValue);
            });
            slider.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2) {
                    state.resetToDefault(param.id());
                    double defaultVal = param.defaultValue();
                    slider.setValue(defaultVal);
                    valueLabel.setText(formatValue(defaultVal, param));
                    fireParameterChanged(param.id(), defaultVal);
                }
            });
            sliderMap.put(param.id(), slider);
            controlBox.getChildren().addAll(nameLabel, slider, valueLabel);
        }

        return controlBox;
    }

    private void loadSelectedPreset() {
        int index = presetComboBox.getSelectionModel().getSelectedIndex();
        if (index >= 0 && index < presets.size()) {
            ParameterPreset preset = presets.get(index);
            state.loadValues(preset.values());
            refreshControls();
        }
    }

    private void handleAbToggle() {
        abComparison.toggle();
        ABComparison.Slot active = abComparison.getActiveSlot();
        abToggleButton.setText(active == ABComparison.Slot.A ? "A" : "B");
        refreshControls();
    }

    private void fireParameterChanged(int parameterId, double value) {
        if (onParameterChanged != null) {
            onParameterChanged.accept(parameterId, value);
        }
    }

    private static boolean isBooleanParameter(PluginParameter param) {
        return param.minValue() == 0.0 && param.maxValue() == 1.0
                && (param.defaultValue() == 0.0 || param.defaultValue() == 1.0)
                && param.name().toLowerCase().contains("toggle");
    }

    private static String formatValue(double value, PluginParameter param) {
        if (isBooleanParameter(param)) {
            return value >= 0.5 ? "ON" : "OFF";
        }
        if (Math.abs(value) >= 1000) {
            return String.format("%.1f", value);
        }
        return String.format("%.2f", value);
    }
}
