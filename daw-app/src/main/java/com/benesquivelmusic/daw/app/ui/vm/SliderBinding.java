package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.vm.command.TrackCommand;

import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.value.ChangeListener;
import javafx.scene.control.Slider;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.DoubleFunction;

/**
 * The one {@link Slider} binding discipline both {@link TrackControlBinder}
 * and {@link ChannelControlBinder} use for a continuous channel fact (volume
 * or pan) — story 322, Control Synchronization Design Book §4.4 (single-writer
 * binding, no {@code suppressEvents} flag) and the "commit-on-release needs the
 * concrete control" lesson.
 *
 * <ul>
 *   <li><strong>Commit per value change.</strong> A DAW fader is audible while
 *       dragging, so every tick of the slider raises the command (the model
 *       write is per tick, never deferred to release).</li>
 *   <li><strong>Stateless echo guard.</strong> A slider value equal to the VM's
 *       current value is an echo of a VM-driven refresh (or of the snap-back
 *       below), not a gesture, and raises nothing.</li>
 *   <li><strong>Echo suppressed while dragging.</strong> While
 *       {@link Slider#isValueChanging()} the VM→slider write is <em>not</em>
 *       applied: a per-tick commit republishes the VM per tick, and pushing that
 *       straight back into the slider fights the pointer (and an automation or
 *       partner-link write landing mid-drag would yank the thumb). On release
 *       ({@code valueChanging → false}) the VM value is re-applied once, so the
 *       slider always settles on the model's accepted value — the stale snap-back
 *       that silently overwrote a recalled scene is gone.</li>
 *   <li><strong>Snap-back on rejection.</strong> If the handler throws
 *       {@link IllegalArgumentException} (out-of-range), the slider is set back to
 *       the VM's accepted value, which the echo guard then drops.</li>
 * </ul>
 */
final class SliderBinding {

    private SliderBinding() {
    }

    /**
     * Installs the binding and returns its disposer.
     *
     * @param slider     the control; must not be {@code null}
     * @param vmValue    the VM fact the slider mirrors; must not be {@code null}
     * @param command    builds the command for a committed slider value
     * @param commandSink where the command is dispatched
     * @return a runnable that removes every listener installed here
     */
    static Runnable bind(Slider slider,
                         ReadOnlyDoubleProperty vmValue,
                         DoubleFunction<TrackCommand> command,
                         Consumer<TrackCommand> commandSink) {
        Objects.requireNonNull(slider, "slider must not be null");
        Objects.requireNonNull(vmValue, "vmValue must not be null");
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(commandSink, "commandSink must not be null");

        slider.setValue(vmValue.get());

        ChangeListener<Number> vmToSlider = (_, _, now) -> {
            if (!slider.isValueChanging()) {
                slider.setValue(now.doubleValue());
            }
        };
        vmValue.addListener(vmToSlider);

        ChangeListener<Boolean> onRelease = (_, _, changing) -> {
            if (!Boolean.TRUE.equals(changing)) {
                slider.setValue(vmValue.get()); // re-apply once on release
            }
        };
        slider.valueChangingProperty().addListener(onRelease);

        ChangeListener<Number> commit = (_, _, now) -> {
            double value = now.doubleValue();
            if (value == vmValue.get()) {
                return; // echo of a VM-driven refresh / snap-back, not a gesture
            }
            try {
                commandSink.accept(command.apply(value));
            } catch (IllegalArgumentException rejected) {
                slider.setValue(vmValue.get());
            }
        };
        slider.valueProperty().addListener(commit);

        return () -> {
            vmValue.removeListener(vmToSlider);
            slider.valueChangingProperty().removeListener(onRelease);
            slider.valueProperty().removeListener(commit);
        };
    }
}
