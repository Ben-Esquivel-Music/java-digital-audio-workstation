package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.controls.MixerChannelStrip;
import com.benesquivelmusic.daw.app.ui.vm.command.SetChannelPanCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.SetChannelVolumeCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleArmCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleMuteCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleSoloCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.TrackCommand;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.track.Track;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.css.PseudoClass;
import javafx.scene.control.ButtonBase;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Binds a track's controls — its arrangement-lane mute/solo/arm buttons, its
 * mixer strip, and its fader/pan — to a {@link TrackVM}/{@link ChannelVM} and
 * routes their gestures to {@link TrackCommand}s (story 291; the §6 control
 * wiring and the §4.4 single-writer binding discipline of the Control
 * Synchronization Design Book).
 *
 * <p>Each control's <em>visible state is a pure function of the VM</em>: the
 * binder drives the existing {@code :active} pseudo-class (UI Design Book §2.1)
 * from VM properties, never the reverse. A user gesture does not write the
 * control's own value; it raises an intent through the
 * {@link Consumer}&lt;{@link TrackCommand}&gt; sink, so the control updates only
 * as a subscriber once the VM republishes — no {@code suppressEvents} guard is
 * needed (§4.4). The same command sink is shared by the lane buttons, the mixer
 * strip, and the Track menu (§2.8).</p>
 *
 * <h2>The §1.3 "one flag, both surfaces" join</h2>
 *
 * <p>{@link #bindMute(ButtonBase)} and {@link #bindChannelStrip(MixerChannelStrip)}
 * subscribe the <em>same</em> {@link TrackVM#mutedProperty()} flag: the lane
 * button's {@code :active} pseudo-class and the strip's {@code :muted} pseudo
 * (via its bound {@code mutedProperty}) both follow it. One toggle, both
 * surfaces — the unification this story delivers.</p>
 *
 * <h2>Nullable channel</h2>
 *
 * <p>{@code channel}/{@code channelVm} may be {@code null} for a track with no
 * paired mixer channel; the flag bindings ({@link #bindMute}/{@link #bindSolo}/
 * {@link #bindArm}/{@link #bindChannelStrip}) work regardless, but
 * {@link #bindFader(DoubleProperty)} / {@link #bindPan(DoubleProperty)} require a
 * non-null channel VM.</p>
 *
 * <h2>Unit scope</h2>
 *
 * <p>The fader/pan bindings operate on a plain {@link DoubleProperty} (a
 * Slider/Knob value) in <em>linear</em> units ([0,1] for volume, [−1,1] for pan)
 * — the {@code MixerChannelStrip} dB-fader↔linear conversion is a live detail
 * deferred to story 293's controller rewiring and is deliberately not done here.</p>
 *
 * <p>{@link #dispose()} removes every listener and binding so no leak survives
 * the control's lifetime ({@code javafx-application-design} §3/§4). Idempotent.</p>
 */
public final class TrackControlBinder {

    /** The {@code :active} pseudo-class shared with the lane controllers (UI Design Book §2.1). */
    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");

    private final Track track;
    private final TrackVM trackVm;
    private final MixerChannel channel;
    private final ChannelVM channelVm;
    private final Consumer<TrackCommand> commandSink;
    private final List<Runnable> disposers = new ArrayList<>();

    /**
     * Creates a binder over a track's VMs that emits commands into
     * {@code commandSink}.
     *
     * @param track       the track the controls act on; must not be {@code null}
     * @param trackVm     the track view-model the controls observe; must not be {@code null}
     * @param channel     the paired mixer channel, or {@code null} if the track has none
     * @param channelVm   the channel view-model, or {@code null} if the track has none
     * @param commandSink where control gestures are dispatched; must not be {@code null}
     * @throws NullPointerException if {@code track}, {@code trackVm}, or
     *                              {@code commandSink} is {@code null}
     */
    public TrackControlBinder(Track track, TrackVM trackVm,
                              MixerChannel channel, ChannelVM channelVm,
                              Consumer<TrackCommand> commandSink) {
        this.track = Objects.requireNonNull(track, "track must not be null");
        this.trackVm = Objects.requireNonNull(trackVm, "trackVm must not be null");
        this.channel = channel;       // nullable — a track may have no paired channel
        this.channelVm = channelVm;   // nullable — paired with channel
        this.commandSink = Objects.requireNonNull(commandSink, "commandSink must not be null");
    }

    /**
     * Binds a lane mute button: its {@code :active} pseudo-class follows
     * {@code trackVm.muted}; a click raises {@link ToggleMuteCommand} with the
     * negated current state.
     *
     * @param muteControl the mute button; must not be {@code null}
     */
    public void bindMute(ButtonBase muteControl) {
        Objects.requireNonNull(muteControl, "muteControl must not be null");
        updateActive(muteControl, trackVm.isMuted());
        ChangeListener<Boolean> listener =
                (obs, was, now) -> updateActive(muteControl, Boolean.TRUE.equals(now));
        trackVm.mutedProperty().addListener(listener);
        disposers.add(() -> trackVm.mutedProperty().removeListener(listener));
        onAction(muteControl, () -> new ToggleMuteCommand(track, !trackVm.isMuted()));
    }

    /**
     * Binds a lane solo button: its {@code :active} pseudo-class follows
     * {@code trackVm.soloed}; a click raises {@link ToggleSoloCommand}.
     *
     * @param soloControl the solo button; must not be {@code null}
     */
    public void bindSolo(ButtonBase soloControl) {
        Objects.requireNonNull(soloControl, "soloControl must not be null");
        updateActive(soloControl, trackVm.isSoloed());
        ChangeListener<Boolean> listener =
                (obs, was, now) -> updateActive(soloControl, Boolean.TRUE.equals(now));
        trackVm.soloedProperty().addListener(listener);
        disposers.add(() -> trackVm.soloedProperty().removeListener(listener));
        onAction(soloControl, () -> new ToggleSoloCommand(track, !trackVm.isSoloed()));
    }

    /**
     * Binds a lane arm button: its {@code :active} pseudo-class follows
     * {@code trackVm.armed}; a click raises {@link ToggleArmCommand}.
     *
     * @param armControl the arm button; must not be {@code null}
     */
    public void bindArm(ButtonBase armControl) {
        Objects.requireNonNull(armControl, "armControl must not be null");
        updateActive(armControl, trackVm.isArmed());
        ChangeListener<Boolean> listener =
                (obs, was, now) -> updateActive(armControl, Boolean.TRUE.equals(now));
        trackVm.armedProperty().addListener(listener);
        disposers.add(() -> trackVm.armedProperty().removeListener(listener));
        onAction(armControl, () -> new ToggleArmCommand(track, !trackVm.isArmed()));
    }

    /**
     * Mirrors the <em>same</em> {@link TrackVM} flags the lane buttons use onto a
     * {@link MixerChannelStrip} — the §1.3 "one flag, both surfaces" join. The
     * strip reflects the VM flag but never writes it back: its own mute/solo
     * gesture must be wired through {@link #bindMute}/{@link #bindSolo} on its
     * buttons (or the controller, story 293).
     *
     * <p><strong>Why a listener + setter, not {@code bind()}:</strong> the strip's
     * {@code muted}/{@code soloed}/{@code armed} are <em>two-way</em> control
     * properties — its skin keeps each in lock-step with a toggle button
     * ({@code muted}→{@code muteBtn.setSelected}, and {@code muteBtn.selected}→
     * {@code control.setMuted}). {@code bind()}-ing such a property would make the
     * skin's button-driven {@code setMuted(...)} throw "A bound value cannot be
     * set." the moment the flag (or the user) flips it. Instead a
     * {@link ChangeListener} pushes the VM value through the strip's
     * {@code setMuted(...)}; the skin's resulting echo back into {@code setMuted}
     * carries the identical value, so the property's no-op short-circuit
     * terminates the cascade with no feedback loop (the {@code javafx-application-
     * design} §3/§13/§15 "break the property-change cycle" rule). The strip's
     * property stays writable for its own button — exactly the two-way control it
     * was designed to be.
     *
     * @param strip the mixer channel strip; must not be {@code null}
     */
    public void bindChannelStrip(MixerChannelStrip strip) {
        Objects.requireNonNull(strip, "strip must not be null");
        mirrorFlag(trackVm.mutedProperty(), strip::setMuted);
        mirrorFlag(trackVm.soloedProperty(), strip::setSoloed);
        mirrorFlag(trackVm.armedProperty(), strip::setArmed);
    }

    /**
     * Seeds {@code sink} with {@code source}'s current value and keeps it in sync
     * on every change, registering a disposer that removes the listener. Used to
     * one-way mirror a VM flag onto a two-way control property via its setter (see
     * {@link #bindChannelStrip}), never {@code bind()}.
     */
    private void mirrorFlag(ReadOnlyBooleanProperty source, Consumer<Boolean> sink) {
        sink.accept(source.get());
        ChangeListener<Boolean> listener =
                (obs, was, now) -> sink.accept(Boolean.TRUE.equals(now));
        source.addListener(listener);
        disposers.add(() -> source.removeListener(listener));
    }

    /**
     * Binds a linear-volume control (a Slider/Knob value in [0,1]) to the
     * channel VM: the control is set from {@code channelVm.volume} and refreshed
     * whenever it republishes, and on commit raises {@link SetChannelVolumeCommand}.
     * A VM-driven refresh does not re-raise a command, and a value the handler
     * rejects snaps the control back to the VM's accepted value (see
     * {@link #onCommit}). Requires a non-null channel VM.
     *
     * @param linearVolumeControl the fader's value property in [0,1]; must not be {@code null}
     * @throws IllegalStateException if this binder has no paired channel VM
     */
    public void bindFader(DoubleProperty linearVolumeControl) {
        Objects.requireNonNull(linearVolumeControl, "linearVolumeControl must not be null");
        requireChannel();
        linearVolumeControl.set(channelVm.getVolume());
        ChangeListener<Number> listener =
                (obs, was, now) -> linearVolumeControl.set(now.doubleValue());
        channelVm.volumeProperty().addListener(listener);
        disposers.add(() -> channelVm.volumeProperty().removeListener(listener));
        onCommit(linearVolumeControl, channelVm::getVolume,
                () -> new SetChannelVolumeCommand(channel, linearVolumeControl.get()));
    }

    /**
     * Binds a pan control (a Knob value in [−1,1]) to the channel VM: the control
     * is set from {@code channelVm.pan} and refreshed whenever it republishes,
     * and on commit raises {@link SetChannelPanCommand}. A VM-driven refresh does
     * not re-raise a command, and a value the handler rejects snaps the control
     * back to the VM's accepted value (see {@link #onCommit}). Requires a non-null
     * channel VM.
     *
     * @param panControl the pan knob's value property in [−1,1]; must not be {@code null}
     * @throws IllegalStateException if this binder has no paired channel VM
     */
    public void bindPan(DoubleProperty panControl) {
        Objects.requireNonNull(panControl, "panControl must not be null");
        requireChannel();
        panControl.set(channelVm.getPan());
        ChangeListener<Number> listener =
                (obs, was, now) -> panControl.set(now.doubleValue());
        channelVm.panProperty().addListener(listener);
        disposers.add(() -> channelVm.panProperty().removeListener(listener));
        onCommit(panControl, channelVm::getPan,
                () -> new SetChannelPanCommand(channel, panControl.get()));
    }

    private void requireChannel() {
        if (channel == null || channelVm == null) {
            throw new IllegalStateException(
                    "this binder has no paired mixer channel; fader/pan cannot be bound");
        }
    }

    /**
     * Wires a button click to raise a freshly-built command (the command is
     * built per click via {@code factory} so it captures the VM's current state
     * at click time, not at bind time).
     */
    private void onAction(ButtonBase button, java.util.function.Supplier<TrackCommand> factory) {
        button.setOnAction(_ -> commandSink.accept(factory.get()));
        disposers.add(() -> button.setOnAction(null));
    }

    /**
     * Wires a control's value-commit (here, a value change of the bound linear
     * property) to raise a command built from the property's current value.
     *
     * <p>A {@code DoubleProperty} (a plain Slider/Knob value) has no Enter/
     * focus-loss "commit" event of its own; its committed value is its current
     * value, so a change of the property — driven by user drag — is the commit
     * signal. Two changes are <em>not</em> user gestures and must not raise a
     * command:</p>
     * <ul>
     *   <li>A <strong>VM-driven refresh</strong> sets the control to exactly the
     *       VM's current value; the guard {@code now == vmValue} drops that echo
     *       so an external change (automation, undo, another surface) does not
     *       round-trip a redundant command. This is a stateless value check, not
     *       a {@code suppressEvents} flag (§4.4).</li>
     *   <li>The <strong>snap-back</strong> below sets the control back to the
     *       VM's accepted value, which the same guard then drops.</li>
     * </ul>
     *
     * <p>If the handler refuses the value — its VALIDATE/MUTATE throws
     * {@link IllegalArgumentException} for an out-of-range fader/pan — no MUTATE
     * fired, so the VM still holds the accepted value: the control snaps back to
     * it rather than leaving an invalid value or letting the exception escape onto
     * the FX thread (mirrors {@code TransportControlBinder.bindTempoField}).</p>
     */
    private void onCommit(DoubleProperty control,
                          java.util.function.DoubleSupplier vmValue,
                          java.util.function.Supplier<TrackCommand> factory) {
        ChangeListener<Number> commit = (obs, was, now) -> {
            if (now.doubleValue() == vmValue.getAsDouble()) {
                return; // echo of a VM-driven refresh / snap-back, not a user gesture
            }
            try {
                commandSink.accept(factory.get());
            } catch (IllegalArgumentException rejected) {
                control.set(vmValue.getAsDouble());
            }
        };
        control.addListener(commit);
        disposers.add(() -> control.removeListener(commit));
    }

    private static void updateActive(ButtonBase button, boolean active) {
        button.pseudoClassStateChanged(ACTIVE, active);
    }

    /** Removes every listener and binding installed by this binder. Idempotent. */
    public void dispose() {
        for (Runnable d : disposers) {
            d.run();
        }
        disposers.clear();
    }
}
