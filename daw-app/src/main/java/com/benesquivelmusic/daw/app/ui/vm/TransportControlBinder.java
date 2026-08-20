package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.vm.command.SetTempoCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.StopTransportCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleLoopCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.TogglePlayPauseCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleRecordCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.TransportCommand;
import com.benesquivelmusic.daw.core.transport.TransportState;

import javafx.beans.binding.Bindings;
import javafx.beans.value.ChangeListener;
import javafx.css.PseudoClass;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Binds the transport-bar controls to a {@link TransportVM} and routes their
 * gestures to {@link TransportCommand}s — the §6.1 transport-bar wiring and the
 * §4.4 single-writer binding discipline (Control Synchronization Design Book).
 *
 * <p>Each control's <em>visible state is a pure function of the VM</em>: the
 * binder drives the existing {@code :active} pseudo-class (UI Design Book §2.1)
 * and the tempo text from VM properties, never the reverse. A user gesture does
 * not write the control's own value; it raises an intent through the
 * {@link Consumer}&lt;{@link TransportCommand}&gt; sink, so the control updates
 * only as a subscriber once the VM republishes — no {@code suppressEvents} guard
 * is needed (§4.4). The same command sink is shared by the buttons, the spacebar,
 * and the Transport menu (§2.8).</p>
 *
 * <p>{@link #dispose()} removes every listener and binding so no leak survives
 * the control's lifetime ({@code javafx-application-design} §3/§4).</p>
 */
public final class TransportControlBinder {

    /** The {@code :active} pseudo-class shared with {@code TransportController} (UI Design Book §2.1). */
    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");

    private final TransportVM vm;
    private final Consumer<TransportCommand> commandSink;
    private final List<Runnable> disposers = new ArrayList<>();

    /**
     * Creates a binder over {@code vm} that emits commands into {@code commandSink}.
     *
     * @param vm          the view-model the controls observe; must not be {@code null}
     * @param commandSink where control gestures are dispatched; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public TransportControlBinder(TransportVM vm, Consumer<TransportCommand> commandSink) {
        this.vm = Objects.requireNonNull(vm, "vm must not be null");
        this.commandSink = Objects.requireNonNull(commandSink, "commandSink must not be null");
    }

    /**
     * Binds a Play control: its {@code :active} pseudo-class follows
     * {@code state == PLAYING}; a click raises {@link TogglePlayPauseCommand} —
     * Play-toggles-pause (UI Design Book §5.1, story 315).
     *
     * <p>The binder deliberately does <em>not</em> resolve the toggle itself.
     * The VM state is an async mirror: every write is marshalled through
     * {@code FxDispatcher.onFx}, an unconditional {@code Platform.runLater}, so
     * it lags the authoritative transport by at least one FX turn. Choosing
     * Start-or-Pause from that mirror raised a Start against an already-PLAYING
     * transport inside the lag window, where VALIDATE silently dropped it and
     * the click did nothing. Raising the toggle moves the decision to the
     * handler, which reads {@code Transport.getState()} — the same authority the
     * keyboard and Performance-Stage paths use (§2.8 "one path").</p>
     *
     * <p>Only the raised intent changed: the visible {@code :active} binding
     * stays VM-driven (§4.4 — state flows down from the VM).</p>
     *
     * @param play the play button; must not be {@code null}
     */
    public void bindPlay(ButtonBase play) {
        Objects.requireNonNull(play, "play must not be null");
        bindActiveState(play, TransportState.PLAYING);
        onAction(play, new TogglePlayPauseCommand());
    }

    /**
     * Binds a Stop control: a click raises {@link StopTransportCommand}. Stop has
     * no persistent active state of its own.
     *
     * @param stop the stop button; must not be {@code null}
     */
    public void bindStop(ButtonBase stop) {
        Objects.requireNonNull(stop, "stop must not be null");
        onAction(stop, new StopTransportCommand());
    }

    /**
     * Binds a Record control: its {@code :active} pseudo-class follows
     * {@code state == RECORDING}; a click raises {@link ToggleRecordCommand}.
     *
     * @param record the record button; must not be {@code null}
     */
    public void bindRecord(ButtonBase record) {
        Objects.requireNonNull(record, "record must not be null");
        bindActiveState(record, TransportState.RECORDING);
        onAction(record, new ToggleRecordCommand());
    }

    /**
     * Binds a Loop control: its {@code :active} pseudo-class follows
     * {@code loopRegion.enabled}; a click raises {@link ToggleLoopCommand}.
     *
     * @param loop the loop toggle/button; must not be {@code null}
     */
    public void bindLoop(ButtonBase loop) {
        Objects.requireNonNull(loop, "loop must not be null");
        updateActive(loop, vm.getLoopRegion() != null && vm.getLoopRegion().enabled());
        ChangeListener<LoopRegion> listener =
                (obs, was, now) -> updateActive(loop, now != null && now.enabled());
        vm.loopRegionProperty().addListener(listener);
        disposers.add(() -> vm.loopRegionProperty().removeListener(listener));
        onAction(loop, new ToggleLoopCommand());
    }

    /**
     * Binds a read-only tempo display: its {@code text} is bound to the VM tempo
     * formatted as {@code "%.1f BPM"} (matching the legacy display). One-way; the
     * label never writes back.
     *
     * @param tempoLabel the tempo label; must not be {@code null}
     */
    public void bindTempoLabel(Label tempoLabel) {
        Objects.requireNonNull(tempoLabel, "tempoLabel must not be null");
        tempoLabel.textProperty().bind(Bindings.createStringBinding(
                () -> String.format(Locale.ROOT, "%.1f BPM", vm.getTempo()),
                vm.tempoProperty()));
        disposers.add(() -> tempoLabel.textProperty().unbind());
    }

    /**
     * Binds the transport time display: its {@code text} is the beats→time
     * projection of {@link TransportVM#playheadProperty()} through
     * {@link TransportVM#tempoProperty()} — one clock, projected (Audio Engine
     * Wiring Design Book §2.3, story 315; this replaces the deleted wall-clock
     * {@code TimeTickerAnimator}). Formatted {@code HH:MM:SS.t} with truncated
     * tenths, exactly like the legacy ticker. One-way; the label never writes
     * back, and no other writer may {@code setText} a bound label.
     *
     * @param timeDisplay the time display label; must not be {@code null}
     */
    public void bindTimeDisplay(Label timeDisplay) {
        Objects.requireNonNull(timeDisplay, "timeDisplay must not be null");
        timeDisplay.textProperty().bind(Bindings.createStringBinding(
                () -> formatBeatsAsTime(vm.getPlayhead(), vm.getTempo()),
                vm.playheadProperty(), vm.tempoProperty()));
        disposers.add(() -> timeDisplay.textProperty().unbind());
    }

    /**
     * The tolerance folded into the tenths truncation below. The projected
     * seconds are a floating-point product of a playhead the RT path builds by
     * repeatedly accumulating {@code deltaBeats}, so a value that mathematically
     * lands exactly on a tenth almost never does in binary — truncating such a
     * value renders a whole tenth low. One nanosecond is far below the display's
     * 100 ms resolution, so it can never promote a genuinely lower reading.
     */
    private static final double TENTHS_EPSILON = 1e-9;

    /**
     * Projects a beat position through the tempo into {@code HH:MM:SS.t}
     * (tenths truncated, matching the retired {@code TimeTickerAnimator}
     * format so the display is bit-identical for the same elapsed time).
     *
     * <p>Truncation is toward zero <em>after</em> {@link #TENTHS_EPSILON} is
     * added, so an accumulated position that sits a few ulps below a tenth
     * boundary reads as that tenth rather than the one below it (story 315
     * review).</p>
     */
    private static String formatBeatsAsTime(double beats, double tempoBpm) {
        double seconds = tempoBpm > 0.0 ? beats * 60.0 / tempoBpm : 0.0;
        long tenthsTotal = (long) Math.floor(seconds * 10.0 + TENTHS_EPSILON);
        long tenths = tenthsTotal % 10;
        long totalSeconds = tenthsTotal / 10;
        long minutes = totalSeconds / 60;
        long hours = minutes / 60;
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%d",
                hours, minutes % 60, totalSeconds % 60, tenths);
    }

    /**
     * Binds an editable tempo field: it displays the VM tempo and, on commit
     * (Enter / focus-loss), raises {@link SetTempoCommand} with the typed value —
     * never a per-keystroke write-back (§4.4). The field is refreshed from the VM
     * whenever the committed tempo changes, so an accepted edit lands once the VM
     * republishes; a <em>rejected</em> edit — unparseable text <em>or</em> a value
     * the handler's VALIDATE phase refuses (out of range / NaN, which throws an
     * {@link IllegalArgumentException}) — snaps the field back to the committed
     * tempo rather than leaving invalid text or letting the exception escape onto
     * the FX thread.
     *
     * @param tempoField the tempo text field; must not be {@code null}
     */
    public void bindTempoField(TextField tempoField) {
        Objects.requireNonNull(tempoField, "tempoField must not be null");
        tempoField.setText(formatTempo(vm.getTempo()));

        ChangeListener<Number> tempoListener =
                (obs, was, now) -> tempoField.setText(formatTempo(now.doubleValue()));
        vm.tempoProperty().addListener(tempoListener);
        disposers.add(() -> vm.tempoProperty().removeListener(tempoListener));

        Runnable commit = () -> {
            try {
                commandSink.accept(new SetTempoCommand(Double.parseDouble(tempoField.getText().trim())));
            } catch (IllegalArgumentException rejected) {
                // Snap back on any rejection. NumberFormatException (unparseable
                // text) IS an IllegalArgumentException, and so is the handler's
                // out-of-range / NaN rejection (CoreTransportIntentHandler.setTempo):
                // catching only the former left an out-of-range entry showing invalid
                // text while the IAE escaped uncaught onto the FX thread. The VM tempo
                // is unchanged on a rejected edit (no MUTATE fired), so it is the
                // correct value to restore.
                tempoField.setText(formatTempo(vm.getTempo()));
            }
        };
        tempoField.setOnAction(_ -> commit.run());
        ChangeListener<Boolean> focusListener = (obs, was, focused) -> {
            if (Boolean.FALSE.equals(focused)) {
                commit.run();
            }
        };
        tempoField.focusedProperty().addListener(focusListener);
        disposers.add(() -> {
            tempoField.setOnAction(null);
            tempoField.focusedProperty().removeListener(focusListener);
        });
    }

    private void bindActiveState(ButtonBase button, TransportState activeWhen) {
        updateActive(button, vm.getState() == activeWhen);
        ChangeListener<TransportState> listener =
                (obs, was, now) -> updateActive(button, now == activeWhen);
        vm.stateProperty().addListener(listener);
        disposers.add(() -> vm.stateProperty().removeListener(listener));
    }

    private void onAction(ButtonBase button, TransportCommand command) {
        button.setOnAction(_ -> commandSink.accept(command));
        disposers.add(() -> button.setOnAction(null));
    }

    private static void updateActive(ButtonBase button, boolean active) {
        button.pseudoClassStateChanged(ACTIVE, active);
    }

    private static String formatTempo(double bpm) {
        return String.format(Locale.ROOT, "%.1f", bpm);
    }

    /** Removes every listener and binding installed by this binder. Idempotent. */
    public void dispose() {
        for (Runnable d : disposers) {
            d.run();
        }
        disposers.clear();
    }
}
