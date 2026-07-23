package com.benesquivelmusic.daw.core.plugin.editor;

import com.benesquivelmusic.daw.core.plugin.MidSideWrapperPlugin.ChainOwner;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.List;
import java.util.Objects;

/**
 * Modest parameter editor opened when the user double-clicks an inner-chain
 * plugin in {@link InnerChainPane}: a {@link Stage} hosting one labelled
 * {@link Slider} per {@link PluginParameter} (range and initial value from
 * the descriptor), each change routed to
 * {@link DawPlugin#setAutomatableParameter(int, double)}.
 *
 * <p>Story 302 §8.3 replacement for the retired {@code daw-app} flow that
 * showed a {@code PluginParameterEditorPanel} in a stage — that class is
 * daw-app-only, so this package-private stand-in ports the essential
 * behaviour (labelled sliders writing through to the inner plugin) without
 * the preset dropdown / A/B comparison extras.</p>
 */
final class InnerPluginParameterDialog {

    private InnerPluginParameterDialog() {
        // static utility — not instantiable
    }

    /**
     * Opens a non-modal stage titled with the plugin name and owning chain,
     * containing one labelled slider per parameter.
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

    private static VBox parameterRow(DawPlugin plugin, PluginParameter param) {
        Label name = new Label(param.name());
        Label value = new Label(formatValue(param.defaultValue()));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
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

    /** Same readout format as the retired panel: one decimal ≥ 1000, else two. */
    private static String formatValue(double value) {
        if (Math.abs(value) >= 1000) {
            return String.format("%.1f", value);
        }
        return String.format("%.2f", value);
    }
}
