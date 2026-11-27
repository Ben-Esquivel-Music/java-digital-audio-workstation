package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.dialogs.DawgDialog;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.sdk.audio.performance.DegradationPolicy;
import com.benesquivelmusic.daw.sdk.audio.performance.TrackCpuBudget;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.util.Objects;
import java.util.function.BooleanSupplier;

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
 *       with no behaviour change. A note under it says what going over
 *       the budget does under the chosen policy: the engine applies none
 *       yet (story 338, ruling E194).</li>
 * </ul>
 *
 * <p>On {@link ButtonType#APPLY}, the new {@link TrackCpuBudget} is
 * pushed to the channel via {@link MixerChannel#setCpuBudget(TrackCpuBudget)}
 * and an optional {@code onApplied} hook is invoked so the caller can
 * re-register the channel with the live
 * {@link com.benesquivelmusic.daw.core.audio.performance.TrackCpuBudgetEnforcer
 * TrackCpuBudgetEnforcer}.</p>
 *
 * <p>An Apply while the window's edit gate says no (a close waits on disk
 * work, or a project is being installed) is refused: the channel's budget is
 * not written and {@code onApplied} does not run (PR #989 review
 * 5711460812).</p>
 */
public final class ChannelCpuBudgetDialog extends DawgDialog<Void> {

    /** Default {@code maxFractionOfBlock} suggested by the issue. */
    public static final double DEFAULT_MAX_FRACTION = 0.5;

    /** Story 338, ruling E194: the note under the policy for the three policies the engine does not apply yet. */
    private static final String POLICY_NOT_APPLIED_NOTE = "This policy is not applied: when the track goes over"
            + " this budget, or the master CPU budget in Audio Settings marks it, its processing is not reduced;"
            + " it gets a ⚠ on its mixer strip, a notification and a Budget entry in the Xrun Log.";
    /** Story 338, ruling E194: the note under the policy for {@code DoNothing}, for which nothing is published. */
    private static final String DO_NOTHING_NOTE = "With DoNothing, going over budget changes and shows nothing:"
            + " the track's processing is not reduced, and there is no ⚠ on its mixer strip, no notification and"
            + " no Xrun Log entry.";

    private final MixerChannel channel;
    private final Runnable onApplied;
    private final BooleanSupplier projectActionsAllowed;
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
     * @param projectActionsAllowed the window's edit gate: an Apply while it
     *                   says no writes nothing (PR #989 review 5711460812);
     *                   must not be {@code null}
     */
    public ChannelCpuBudgetDialog(MixerChannel channel, Runnable onApplied,
                                  BooleanSupplier projectActionsAllowed) {
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.onApplied = onApplied;
        this.projectActionsAllowed = Objects.requireNonNull(
                projectActionsAllowed, "projectActionsAllowed must not be null");

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
        // Story 266 / §3.2 — percentage readout uses the mono numeric class.
        fractionValueLabel.getStyleClass().add("numeric-value");
        fractionSlider.valueProperty().addListener(
                (_, _, v) -> fractionValueLabel.setText(formatPercent(v.doubleValue())));

        policyCombo = new ComboBox<>();
        policyCombo.getItems().addAll(
                "DoNothing",
                "BypassExpensive",
                "ReduceOversampling",
                "SubstituteSimpleKernel");
        policyCombo.setValue(SettingsModel.policyName(initialPolicy));

        Label policyNote = new Label(noteFor(policyCombo.getValue()));
        policyNote.setWrapText(true);
        policyCombo.valueProperty().addListener((_, _, name) -> policyNote.setText(noteFor(name)));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.add(new Label("Max CPU fraction per block:"), 0, 0);
        grid.add(fractionSlider, 1, 0);
        grid.add(fractionValueLabel, 2, 0);
        grid.add(new Label("Degradation policy:"), 0, 1);
        grid.add(policyCombo, 1, 1, 2, 1);

        // In a VBox, which fills its width, the note wraps at the dialog's width.
        VBox content = new VBox(8, grid, policyNote);
        content.setPadding(new Insets(12));
        getDialogPane().setContent(content);
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
        if (!projectActionsAllowed.getAsBoolean()) {
            return;
        }
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

    /** What going over the budget does under the policy named {@code name}: the engine applies none yet. */
    private static String noteFor(String name) {
        return "DoNothing".equals(name) ? DO_NOTHING_NOTE : POLICY_NOT_APPLIED_NOTE;
    }

    private static String formatPercent(double fraction) {
        return String.format("%d%%", Math.round(fraction * 100.0));
    }
}
