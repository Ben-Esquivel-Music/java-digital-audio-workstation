package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.sdk.audio.performance.DegradationPolicy;
import com.benesquivelmusic.daw.sdk.audio.performance.TrackCpuBudget;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.GridPane;

import java.util.Objects;

/**
 * Modal dialog that lets the user configure the per-track CPU budget
 * (story 129 UI) on a single {@link MixerChannel}.
 *
 * <p>The dialog exposes two controls:</p>
 *
 * <ul>
 *   <li>A {@code maxFractionOfBlock} slider in the legal open
 *       interval {@code (0.0, 1.0]} (default {@code 0.5}).</li>
 *   <li>A {@link DegradationPolicy} combo that mirrors the four
 *       built-in policies ({@code DoNothing}, {@code BypassExpensive},
 *       {@code ReduceOversampling}, {@code SubstituteSimpleKernel}).
 *       The default is {@code DoNothing} so existing projects load
 *       with no behaviour change.</li>
 * </ul>
 *
 * <p>On {@link ButtonType#APPLY}, the new {@link TrackCpuBudget} is
 * pushed to the channel via {@link MixerChannel#setCpuBudget(TrackCpuBudget)}
 * and an optional {@code onApplied} hook is invoked so the caller can
 * re-register the channel with the live
 * {@link com.benesquivelmusic.daw.core.audio.performance.TrackCpuBudgetEnforcer
 * TrackCpuBudgetEnforcer}.</p>
 */
public final class ChannelCpuBudgetDialog extends Dialog<Void> {

    /** Default {@code maxFractionOfBlock} suggested by the issue. */
    public static final double DEFAULT_MAX_FRACTION = 0.5;

    private final MixerChannel channel;
    private final Runnable onApplied;
    private final Slider fractionSlider;
    private final ComboBox<String> policyCombo;

    /**
     * Creates a dialog bound to {@code channel}. The current channel
     * budget (if any) seeds the slider/combo; missing budgets fall
     * back to {@link #DEFAULT_MAX_FRACTION} and {@code DoNothing}.
     *
     * @param channel    the channel to configure (must not be {@code null})
     * @param onApplied  callback invoked after a successful Apply,
     *                   typically used to re-register the channel
     *                   with the active enforcer; may be {@code null}
     */
    public ChannelCpuBudgetDialog(MixerChannel channel, Runnable onApplied) {
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.onApplied = onApplied;

        setTitle("Per-Track CPU Budget");
        setHeaderText("CPU Budget — " + channel.getName());

        TrackCpuBudget current = channel.getCpuBudget();
        double initialFraction = current != null ? current.maxFractionOfBlock() : DEFAULT_MAX_FRACTION;
        DegradationPolicy initialPolicy = current != null
                ? current.onOverBudget()
                : new DegradationPolicy.DoNothing();

        fractionSlider = new Slider(0.01, 1.0, initialFraction);
        fractionSlider.setShowTickMarks(true);
        fractionSlider.setShowTickLabels(true);
        fractionSlider.setMajorTickUnit(0.25);
        fractionSlider.setMinorTickCount(4);
        fractionSlider.setBlockIncrement(0.05);

        Label fractionValueLabel = new Label(formatPercent(initialFraction));
        fractionSlider.valueProperty().addListener(
                (_, _, v) -> fractionValueLabel.setText(formatPercent(v.doubleValue())));

        policyCombo = new ComboBox<>();
        policyCombo.getItems().addAll(
                "DoNothing",
                "BypassExpensive",
                "ReduceOversampling",
                "SubstituteSimpleKernel");
        policyCombo.setValue(SettingsModel.policyName(initialPolicy));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        grid.add(new Label("Max CPU fraction per block:"), 0, 0);
        grid.add(fractionSlider, 1, 0);
        grid.add(fractionValueLabel, 2, 0);
        grid.add(new Label("Degradation policy:"), 0, 1);
        grid.add(policyCombo, 1, 1, 2, 1);

        getDialogPane().setContent(grid);
        getDialogPane().getButtonTypes().setAll(ButtonType.APPLY, ButtonType.CANCEL);
        getDialogPane().setPrefWidth(460);

        setResultConverter(button -> {
            if (button == ButtonType.APPLY) {
                applyResult();
            }
            return null;
        });
    }

    private void applyResult() {
        double fraction = fractionSlider.getValue();
        if (Double.isNaN(fraction) || fraction <= 0.0) {
            fraction = 0.01;
        } else if (fraction > 1.0) {
            fraction = 1.0;
        }
        DegradationPolicy policy = SettingsModel.parsePolicy(policyCombo.getValue());
        channel.setCpuBudget(new TrackCpuBudget(fraction, policy));
        if (onApplied != null) {
            onApplied.run();
        }
    }

    private static String formatPercent(double fraction) {
        return String.format("%d%%", Math.round(fraction * 100.0));
    }
}
