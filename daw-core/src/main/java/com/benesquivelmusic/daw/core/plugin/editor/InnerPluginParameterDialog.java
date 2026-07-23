package com.benesquivelmusic.daw.core.plugin.editor;

import com.benesquivelmusic.daw.core.plugin.MidSideWrapperPlugin.ChainOwner;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Modest parameter editor opened when the user double-clicks an inner-chain
 * plugin in {@link InnerChainPane}: a {@link Stage} hosting one labelled
 * control per {@link PluginParameter} — an ON/OFF {@link ToggleButton} for
 * boolean parameters (per the conservative heuristic the host parameter
 * surfaces share), a {@link Slider} for everything else (range and initial
 * value from the descriptor) — each change routed to
 * {@link DawPlugin#setAutomatableParameter(int, double)}.
 *
 * <p>Story 302 §8.3 replacement for the retired {@code daw-app} flow that
 * showed a {@code PluginParameterEditorPanel} in a stage — that class is
 * daw-app-only, so this package-private stand-in ports the essential
 * behaviour (labelled controls writing through to the inner plugin) without
 * the preset dropdown / A/B comparison extras.</p>
 */
final class InnerPluginParameterDialog {

    private InnerPluginParameterDialog() {
        // static utility — not instantiable
    }

    /**
     * Opens a non-modal stage titled with the plugin name and owning chain,
     * containing one labelled control per parameter.
     *
     * @param plugin the inner-chain plugin whose parameters are edited
     * @param owner  the chain the plugin lives in (title context only)
     */
    static void open(DawPlugin plugin, ChainOwner owner) {
        Objects.requireNonNull(plugin, "plugin must not be null");
        Objects.requireNonNull(owner, "owner must not be null");

        List<PluginParameter> params = plugin.getParameters();
        VBox content = new VBox(8);
        content.setPadding(new Insets(12));
        for (PluginParameter param : params) {
            content.getChildren().add(parameterRow(plugin, param));
        }

        ScrollPane scroller = new ScrollPane(content);
        scroller.setFitToWidth(true);

        Stage stage = new Stage();
        stage.setTitle(plugin.getDescriptor().name() + " — Parameters ("
                + owner + " chain)");
        stage.setScene(new Scene(scroller, 420, 320));
        stage.show();
    }

    /**
     * One row per parameter. Boolean parameters keep the ON/OFF toggle
     * affordance the host parameter surfaces use rather than collapsing into
     * a 0..1 slider with a numeric readout; everything else gets the
     * labelled slider ported from the retired panel.
     */
    private static VBox parameterRow(DawPlugin plugin, PluginParameter param) {
        Label name = new Label(param.name());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        if (isBooleanParameter(param)) {
            boolean initiallyOn = param.defaultValue() >= 0.5;
            ToggleButton toggle = new ToggleButton(initiallyOn ? "ON" : "OFF");
            toggle.setSelected(initiallyOn);
            toggle.setOnAction(_ -> {
                boolean on = toggle.isSelected();
                toggle.setText(on ? "ON" : "OFF");
                plugin.setAutomatableParameter(param.id(), on ? 1.0 : 0.0);
            });
            HBox row = new HBox(8, name, spacer, toggle);
            row.setAlignment(Pos.CENTER_LEFT);
            return new VBox(2, row);
        }

        Label value = new Label(formatValue(param.defaultValue()));
        HBox labelRow = new HBox(8, name, spacer, value);

        Slider slider = new Slider(param.minValue(), param.maxValue(), param.defaultValue());
        slider.setShowTickLabels(true);
        slider.setShowTickMarks(true);
        slider.valueProperty().addListener((_, _, newVal) -> {
            double v = newVal.doubleValue();
            plugin.setAutomatableParameter(param.id(), v);
            value.setText(formatValue(v));
        });

        return new VBox(2, labelRow, slider);
    }

    /**
     * The deliberately conservative boolean heuristic shared verbatim with
     * the host parameter surfaces ({@code ParameterGridPanel} /
     * {@code PluginParameterEditorPanel} — daw-app classes unreachable from
     * this module, hence the local copy): unit range, integral default, AND
     * the name mentions "toggle". Without the name guard a continuous 0..1
     * parameter defaulting to 0 or 1 (a "Mix", a "Level") would silently
     * collapse into an ON/OFF toggle — the SDK has no boolean metadata yet
     * to decide otherwise.
     */
    private static boolean isBooleanParameter(PluginParameter param) {
        return param.minValue() == 0.0 && param.maxValue() == 1.0
                && (param.defaultValue() == 0.0 || param.defaultValue() == 1.0)
                && param.name().toLowerCase(Locale.ROOT).contains("toggle");
    }

    /** Same readout format as the retired panel: one decimal ≥ 1000, else two. */
    private static String formatValue(double value) {
        if (Math.abs(value) >= 1000) {
            return String.format("%.1f", value);
        }
        return String.format("%.2f", value);
    }
}
