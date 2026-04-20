package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.sdk.transport.ClickOutput;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.layout.GridPane;

import java.util.Objects;

/**
 * Modal dialog that edits the metronome's {@link ClickOutput} routing — the
 * side-output hardware channel, its gain, whether the click is also summed
 * into the main control-room bus, and whether the direct hardware side output
 * is enabled at all.
 *
 * <p>Introduced for story 136 ("Click-Track Side Output to Dedicated Hardware
 * Channel"). The dialog exposes:</p>
 * <ul>
 *   <li><b>Hardware Channel</b> — 0-based physical output index used when
 *       {@link ClickOutput#sideOutputEnabled()} is {@code true}.</li>
 *   <li><b>Side-Output Gain</b> — linear gain applied to the click on the
 *       side output, {@code [0.0, 1.0]}.</li>
 *   <li><b>Send to Main Mix</b> — mirrors {@link ClickOutput#mainMixEnabled()}.
 *       When disabled, no click prints into the control-room mix and is
 *       therefore never picked up by overhead or room microphones.</li>
 *   <li><b>Enable Side Output</b> — mirrors {@link ClickOutput#sideOutputEnabled()}.</li>
 * </ul>
 *
 * <p>On Apply the dialog resolves to a new {@link ClickOutput} value; on
 * Cancel (or close) it resolves to {@code null}. Callers are expected to
 * apply the result to their {@link com.benesquivelmusic.daw.core.recording.Metronome}
 * and persist it (per-project via
 * {@link com.benesquivelmusic.daw.core.persistence.ProjectSerializer} and
 * globally via
 * {@link com.benesquivelmusic.daw.core.recording.MetronomeSettingsStore}).</p>
 */
public final class MetronomeSettingsDialog extends Dialog<ClickOutput> {

    /** Upper bound for the hardware channel spinner. Covers typical interfaces
     *  (ASIO, Core Audio, WASAPI) without imposing a hard limit on higher-end
     *  systems; the spinner editor accepts arbitrary integer input. */
    private static final int MAX_CHANNEL_SPINNER = 63;

    private final Spinner<Integer> channelSpinner;
    private final Slider gainSlider;
    private final Label gainValueLabel;
    private final CheckBox mainMixCheck;
    private final CheckBox sideOutputCheck;

    /**
     * Creates a new dialog pre-populated from {@code current}.
     *
     * @param current the current click routing to show; must not be null
     * @throws NullPointerException if {@code current} is null
     */
    public MetronomeSettingsDialog(ClickOutput current) {
        Objects.requireNonNull(current, "current must not be null");

        setTitle("Metronome Settings");
        setHeaderText("Click-Track Routing");
        setGraphic(IconNode.of(DawIcon.METRONOME, 24));

        channelSpinner = new Spinner<>(0, MAX_CHANNEL_SPINNER, current.hardwareChannelIndex());
        channelSpinner.setEditable(true);
        channelSpinner.setPrefWidth(90);

        gainSlider = new Slider(0.0, 1.0, current.gain());
        gainSlider.setShowTickLabels(true);
        gainSlider.setShowTickMarks(true);
        gainSlider.setMajorTickUnit(0.25);
        gainSlider.setPrefWidth(220);
        gainValueLabel = new Label(formatGain(current.gain()));
        gainSlider.valueProperty().addListener(
                (_, _, v) -> gainValueLabel.setText(formatGain(v.doubleValue())));

        mainMixCheck = new CheckBox("Also send click to Main Mix");
        mainMixCheck.setSelected(current.mainMixEnabled());

        sideOutputCheck = new CheckBox("Enable direct hardware Side Output");
        sideOutputCheck.setSelected(current.sideOutputEnabled());

        getDialogPane().setContent(buildContent());
        getDialogPane().getButtonTypes().addAll(ButtonType.APPLY, ButtonType.CANCEL);
        getDialogPane().setPrefWidth(460);

        DarkThemeHelper.applyTo(this);

        setResultConverter(button -> {
            if (button == ButtonType.APPLY) {
                int channel = Math.max(0, channelSpinner.getValue() == null
                        ? 0 : channelSpinner.getValue());
                double gain = clamp01(gainSlider.getValue());
                return new ClickOutput(
                        channel, gain, mainMixCheck.isSelected(), sideOutputCheck.isSelected());
            }
            return null;
        });
    }

    private Node buildContent() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));

        int row = 0;
        Label header = new Label("Click Routing");
        header.setStyle("-fx-font-weight: bold;");
        grid.add(header, 0, row, 3, 1);
        row++;
        grid.add(new Separator(), 0, row, 3, 1);
        row++;

        grid.add(new Label("Hardware Channel:"), 0, row);
        grid.add(channelSpinner, 1, row);
        grid.add(new Label("(0-based output index)"), 2, row);
        row++;

        grid.add(new Label("Side-Output Gain:"), 0, row);
        grid.add(gainSlider, 1, row);
        grid.add(gainValueLabel, 2, row);
        row++;

        grid.add(new Separator(), 0, row, 3, 1);
        row++;
        grid.add(mainMixCheck, 0, row, 3, 1);
        row++;
        grid.add(sideOutputCheck, 0, row, 3, 1);
        row++;

        Label hint = new Label(
                "Disable \"Send to Main Mix\" while enabling the Side Output to send "
                        + "the click only to the drummer's headphone channel; the click "
                        + "is then never printed into the control-room mix and cannot be "
                        + "picked up by overhead or room microphones.");
        hint.setWrapText(true);
        hint.setStyle("-fx-font-size: 10px; -fx-text-fill: #aaaaaa;");
        grid.add(hint, 0, row, 3, 1);

        return grid;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static String formatGain(double v) {
        return String.format("%.0f%%", clamp01(v) * 100.0);
    }
}
