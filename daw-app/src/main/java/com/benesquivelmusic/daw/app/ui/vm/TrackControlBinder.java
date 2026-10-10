package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.controls.InsertSlotModel;
import com.benesquivelmusic.daw.app.ui.controls.MixerChannelStrip;
import com.benesquivelmusic.daw.app.ui.controls.TrackStrip;
import com.benesquivelmusic.daw.app.ui.vm.command.RenameTrackCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.SetChannelPanCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.SetChannelVolumeCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleArmCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleMuteCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleSoloCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.TrackCommand;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.track.Track;

import javafx.beans.InvalidationListener;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.css.PseudoClass;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Slider;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Binds a track's controls — its arrangement-lane mute/solo/arm buttons and
 * volume/pan sliders, its {@link MixerChannelStrip}, and its Performance Stage
 * {@link TrackStrip} tile — to a
 * {@link TrackVM}/{@link ChannelVM} and routes their gestures to
 * {@link TrackCommand}s (story 291; story 322 — the §6 control wiring and the
 * §4.4 single-writer binding discipline of the Control Synchronization Design
 * Book, Audio Engine Wiring Design Book §5.6).
 *
 * <p>Each control's <em>visible state is a pure function of the VM</em>: the
 * binder drives the existing {@code :active} pseudo-class (UI Design Book §2.1)
 * from VM properties, never the reverse. A user gesture does not write the
 * control's own value; it raises an intent through the
 * {@link Consumer}&lt;{@link TrackCommand}&gt; sink, so the control updates only
 * as a subscriber once the VM republishes — no {@code suppressEvents} guard is
 * needed (§4.4). The same command sink is shared by the lane controls, the
 * mixer strip, and the Track menu (§2.8).</p>
 *
 * <h2>The §1.3 "one flag, both surfaces" join</h2>
 *
 * <p>{@link #bindMute(ButtonBase)} and {@link #bindStrip(MixerChannelStrip)}
 * subscribe the <em>same</em> {@link TrackVM#mutedProperty()} flag: the lane
 * button's {@code :active} pseudo-class and the strip's {@code :muted} pseudo
 * (via its mirrored {@code mutedProperty}) both follow it. One toggle, both
 * surfaces — the unification story 291 designed and story 322 puts in
 * production.</p>
 *
 * <h2>Nullable channel</h2>
 *
 * <p>{@code channel}/{@code channelVm} may be {@code null} for a track with no
 * paired mixer channel; the flag bindings ({@link #bindMute}/{@link #bindSolo}/
 * {@link #bindArm}/{@link #bindChannelStrip}) work regardless, but the fader/pan
 * bindings and {@link #bindStrip} require a non-null channel VM.</p>
 *
 * <h2>Unit scope</h2>
 *
 * <p>The {@link DoubleProperty} / {@link Slider} fader/pan bindings are in
 * <em>linear</em> units ([0,1] for volume, [−1,1] for pan). Only
 * {@link #bindStrip} converts: the strip's {@code faderDbProperty()} is in dB
 * ({@code db = 20·log10(lin)}, floored at {@link MixerChannelStrip#FADER_MIN_DB}
 * for {@code lin ≤ 0}; {@code lin = 10^(db/20)} clamped to [0,1]).</p>
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
     * {@code trackVm.muted} (seeded at bind time — the style is a function of
     * the model, never of a click); a click raises {@link ToggleMuteCommand}
     * with the negated current state.
     *
     * @param muteControl the mute button; must not be {@code null}
     */
    public void bindMute(ButtonBase muteControl) {
        Objects.requireNonNull(muteControl, "muteControl must not be null");
        updateActive(muteControl, trackVm.isMuted());
        ChangeListener<Boolean> listener =
                (_, _, now) -> updateActive(muteControl, Boolean.TRUE.equals(now));
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
                (_, _, now) -> updateActive(soloControl, Boolean.TRUE.equals(now));
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
                (_, _, now) -> updateActive(armControl, Boolean.TRUE.equals(now));
        trackVm.armedProperty().addListener(listener);
        disposers.add(() -> trackVm.armedProperty().removeListener(listener));
        onAction(armControl, () -> new ToggleArmCommand(track, !trackVm.isArmed()));
    }

    /**
     * Mirrors the <em>same</em> {@link TrackVM} flags the lane buttons use onto a
     * {@link MixerChannelStrip} — the §1.3 "one flag, both surfaces" join. This
     * is the one-way half only: the strip reflects the VM flag but its own M/S/R
     * gesture raises nothing. {@link #bindStrip(MixerChannelStrip)} is the full
     * two-way binding (mirror <em>plus</em> intent) a production strip needs.
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
     * Binds a whole {@link MixerChannelStrip} to this track's VMs (story 322,
     * the §5.6 "Mixer strip vol/pan/mute/solo" row): every strip fact is a
     * subscriber of a VM property and every strip gesture is an intent through
     * the sink.
     *
     * <ul>
     *   <li>{@code channelId} / {@code channelName} ← {@link ChannelVM#channelId()}
     *       / {@link TrackVM#nameProperty()}.</li>
     *   <li>M/S/R: mirrored from the {@code TrackVM} flags exactly as
     *       {@link #bindChannelStrip}, <em>plus</em> a listener on each two-way
     *       strip flag that raises the matching {@code ToggleXCommand} when the
     *       strip's new value differs from the VM's — the stateless echo guard
     *       (§4.4): a VM-driven mirror arrives with {@code now == vm} and raises
     *       nothing; a user click arrives with {@code now != vm} and raises the
     *       intent, whose republish then mirrors the identical value back
     *       (no-op).</li>
     *   <li>{@code faderDbProperty} ↔ {@link ChannelVM#volumeProperty()} via
     *       linear↔dB with the same echo guard on both sides, compared in the
     *       strip's own dB domain so a float round-trip can never raise a phantom
     *       command; a rejected, clamped or no-op value snaps the fader back:
     *       the strip is a subscriber, never a store (see {@link #bindFaderDb}).</li>
     *   <li>{@code panProperty} ↔ {@link ChannelVM#panProperty()} (linear,
     *       {@link #bindPan(DoubleProperty)}).</li>
     *   <li>{@code insertsProperty} ← {@link ChannelVM#insertsProperty()}
     *       ({@code setAll} on every change).</li>
     *   <li>Meter: the strip's integrated {@code LevelMeter} peak follows
     *       {@link ChannelVM#meterLevelProperty()} through
     *       {@link MixerChannelStrip#meterPeakDbProperty()} (an invalidation
     *       listener reading the primitive — no per-tick boxing), and when the VM
     *       {@linkplain ChannelVM#hasMeterFeed() has a feed} the strip is bound as
     *       the visibility-owning surface via {@link ChannelVM#bindMeter}.</li>
     * </ul>
     *
     * @param strip the mixer channel strip; must not be {@code null}
     * @throws IllegalStateException if this binder has no paired channel VM
     */
    public void bindStrip(MixerChannelStrip strip) {
        Objects.requireNonNull(strip, "strip must not be null");
        requireChannel();

        strip.setChannelId(channelVm.channelId());

        strip.setChannelName(trackVm.getName());
        ChangeListener<String> nameListener = (_, _, now) -> strip.setChannelName(now);
        trackVm.nameProperty().addListener(nameListener);
        disposers.add(() -> trackVm.nameProperty().removeListener(nameListener));
        // The skin's double-click inline editor commits into channelName: a
        // value the VM does not already hold is a rename gesture and raises
        // RenameTrackCommand (the same stateless echo guard as the flags). If
        // the model did not adopt the text verbatim — refused as blank, or
        // stripped, or merely recorded by a non-executing sink — the strip
        // snaps back to the VM's name: its name is a subscriber, never a store.
        ChangeListener<String> stripNameToCommand = (_, _, now) -> {
            if (now == null || now.equals(trackVm.getName())) {
                return; // echo of the VM-driven mirror / snap-back, not a gesture
            }
            try {
                commandSink.accept(new RenameTrackCommand(track, now));
            } catch (IllegalArgumentException rejected) {
                // blank: VALIDATE refused it — fall through to the snap-back
            }
            if (!now.equals(trackVm.getName())) {
                strip.setChannelName(trackVm.getName());
            }
        };
        strip.channelNameProperty().addListener(stripNameToCommand);
        disposers.add(() -> strip.channelNameProperty().removeListener(stripNameToCommand));

        bindChannelStrip(strip);
        raiseOnFlagChange(strip.mutedProperty(), trackVm::isMuted,
                now -> new ToggleMuteCommand(track, now));
        raiseOnFlagChange(strip.soloedProperty(), trackVm::isSoloed,
                now -> new ToggleSoloCommand(track, now));
        raiseOnArmChange(strip.armedProperty(), trackVm::isArmed,
                now -> new ToggleArmCommand(track, now));

        bindFaderDb(strip);
        bindPan(strip.panProperty());

        strip.insertsProperty().setAll(channelVm.insertsProperty());
        ListChangeListener<InsertSlotModel> insertsListener =
                _ -> strip.insertsProperty().setAll(channelVm.insertsProperty());
        channelVm.insertsProperty().addListener(insertsListener);
        disposers.add(() -> channelVm.insertsProperty().removeListener(insertsListener));

        strip.setMeterPeakDb(channelVm.getMeterLevel());
        InvalidationListener meterRelay = _ -> strip.setMeterPeakDb(channelVm.getMeterLevel());
        channelVm.meterLevelProperty().addListener(meterRelay);
        disposers.add(() -> channelVm.meterLevelProperty().removeListener(meterRelay));
        if (channelVm.hasMeterFeed()) {
            disposers.add(channelVm.bindMeter(strip));
        }
    }

    /**
     * Binds a {@link TrackStrip} tile — the Performance Stage's oversized M/S/R
     * control (story 280) — to this track's VM (story 322: the §5.6 "one intent
     * path, both surfaces" contract extended to the stage, Audio Engine Wiring
     * Design Book §2.10). The tile's name follows {@link TrackVM#nameProperty()};
     * its two-way {@code muted}/{@code soloed}/{@code armed} properties mirror
     * the <em>same</em> {@code TrackVM} flags the lane buttons and the mixer
     * strip use (listener + setter, never {@code bind()} — see
     * {@link #bindChannelStrip}), and a flip the VM does not already hold raises
     * the matching {@code ToggleXCommand} through the sink (the stateless echo
     * guard of {@link #bindStrip}). The tile has no fader, so no paired channel
     * is required.
     *
     * @param tile the stage tile; must not be {@code null}
     */
    public void bindTile(TrackStrip tile) {
        Objects.requireNonNull(tile, "tile must not be null");
        tile.setTrackName(trackVm.getName());
        ChangeListener<String> nameListener = (_, _, now) -> tile.setTrackName(now);
        trackVm.nameProperty().addListener(nameListener);
        disposers.add(() -> trackVm.nameProperty().removeListener(nameListener));

        mirrorFlag(trackVm.mutedProperty(), tile::setMuted);
        mirrorFlag(trackVm.soloedProperty(), tile::setSoloed);
        mirrorFlag(trackVm.armedProperty(), tile::setArmed);
        raiseOnFlagChange(tile.mutedProperty(), trackVm::isMuted,
                now -> new ToggleMuteCommand(track, now));
        raiseOnFlagChange(tile.soloedProperty(), trackVm::isSoloed,
                now -> new ToggleSoloCommand(track, now));
        raiseOnArmChange(tile.armedProperty(), trackVm::isArmed,
                now -> new ToggleArmCommand(track, now));
    }

    /**
     * The strip fader half of {@link #bindStrip}: VM linear volume → strip dB,
     * and strip dB → {@link SetChannelVolumeCommand} with the linear value,
     * guarded in the dB domain ({@code now == linearToDb(vm)} is an echo).
     *
     * <p>After every gesture — accepted, rejected or a no-op — the fader is
     * re-asserted to {@code linearToDb(vm)} (PR #977 review). The strip's
     * travel reaches {@link MixerChannelStrip#FADER_MAX_DB +12 dB} while the
     * model is [0,1], so a drag from unity to +6 dB dispatches a clamped
     * {@code 1.0}, which {@code CoreTrackIntentHandler.setVolume} sees as a
     * true no-op: no MUTATE, no republish, and without the re-assert the strip
     * would sit at +6 dB over a rendered gain of 1.0. On the FX thread the VM
     * republish is synchronous, so when the model did change the re-assert is
     * a same-value no-op that fires nothing; when it did not, the re-assert
     * snaps the fader back and the echo guard swallows that change event —
     * no ping-pong ({@code javafx-application-design} §13).</p>
     */
    private void bindFaderDb(MixerChannelStrip strip) {
        strip.setFaderDb(linearToDb(channelVm.getVolume()));
        ChangeListener<Number> vmToFader =
                (_, _, now) -> strip.setFaderDb(linearToDb(now.doubleValue()));
        channelVm.volumeProperty().addListener(vmToFader);
        disposers.add(() -> channelVm.volumeProperty().removeListener(vmToFader));

        ChangeListener<Number> faderToCommand = (_, _, now) -> {
            double db = now.doubleValue();
            if (db == linearToDb(channelVm.getVolume())) {
                return; // echo of the VM-driven write / snap-back, not a gesture
            }
            try {
                commandSink.accept(new SetChannelVolumeCommand(channel, dbToLinear(db)));
            } catch (IllegalArgumentException rejected) {
                // VALIDATE refused it — fall through to the snap-back
            }
            strip.setFaderDb(linearToDb(channelVm.getVolume()));
        };
        strip.faderDbProperty().addListener(faderToCommand);
        disposers.add(() -> strip.faderDbProperty().removeListener(faderToCommand));
    }

    /**
     * Linear [0,1] → dB for the strip fader: {@code 20·log10(lin)}, floored at
     * {@link MixerChannelStrip#FADER_MIN_DB} (also for {@code lin ≤ 0}, where the
     * logarithm is undefined / −∞). The floor matches the fader's own range so a
     * value the control would clamp never differs from what the binder wrote —
     * otherwise the clamp would read as a user gesture.
     */
    static double linearToDb(double linear) {
        if (linear <= 0.0) {
            return MixerChannelStrip.FADER_MIN_DB;
        }
        return Math.max(MixerChannelStrip.FADER_MIN_DB, 20.0 * Math.log10(linear));
    }

    /** dB → linear for the model: {@code 10^(db/20)} clamped to [0,1] (the channel's range). */
    static double dbToLinear(double db) {
        return Math.clamp(Math.pow(10.0, db / 20.0), 0.0, 1.0);
    }

    /**
     * Raises {@code factory.apply(now)} when a two-way strip flag changes to a
     * value the VM does not already hold — the stateless echo guard of
     * {@link #bindStrip}.
     */
    private void raiseOnArmChange(javafx.beans.property.BooleanProperty property,
                                  java.util.function.BooleanSupplier current,
                                  java.util.function.Function<Boolean, TrackCommand> command) {
        ChangeListener<Boolean> listener = (_, _, now) -> {
            if (now == current.getAsBoolean()) return;
            commandSink.accept(command.apply(now));
            property.set(current.getAsBoolean());
        };
        property.addListener(listener);
        disposers.add(() -> property.removeListener(listener));
    }

    private void raiseOnFlagChange(ReadOnlyBooleanProperty stripFlag, BooleanSupplier vmValue,
                                   Function<Boolean, TrackCommand> factory) {
        ChangeListener<Boolean> listener = (_, _, now) -> {
            boolean requested = Boolean.TRUE.equals(now);
            if (requested == vmValue.getAsBoolean()) {
                return; // VM-driven mirror, not a gesture
            }
            commandSink.accept(factory.apply(requested));
        };
        stripFlag.addListener(listener);
        disposers.add(() -> stripFlag.removeListener(listener));
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
                (_, _, now) -> sink.accept(Boolean.TRUE.equals(now));
        source.addListener(listener);
        disposers.add(() -> source.removeListener(listener));
    }

    /**
     * Binds a linear-volume control (a Slider/Knob value in [0,1]) to the
     * channel VM: the control is set from {@code channelVm.volume} and refreshed
     * whenever it republishes, and on commit raises {@link SetChannelVolumeCommand}.
     * A VM-driven refresh does not re-raise a command, and a value the handler
     * rejects snaps the control back to the VM's accepted value (see
     * {@link #onCommit}). Requires a non-null channel VM. Prefer
     * {@link #bindFader(Slider)} for a real {@link Slider}, which also gates the
     * VM→control echo while the user drags.
     *
     * @param linearVolumeControl the fader's value property in [0,1]; must not be {@code null}
     * @throws IllegalStateException if this binder has no paired channel VM
     */
    public void bindFader(DoubleProperty linearVolumeControl) {
        Objects.requireNonNull(linearVolumeControl, "linearVolumeControl must not be null");
        requireChannel();
        linearVolumeControl.set(channelVm.getVolume());
        ChangeListener<Number> listener =
                (_, _, now) -> linearVolumeControl.set(now.doubleValue());
        channelVm.volumeProperty().addListener(listener);
        disposers.add(() -> channelVm.volumeProperty().removeListener(listener));
        onCommit(linearVolumeControl, channelVm::getVolume,
                () -> new SetChannelVolumeCommand(channel, linearVolumeControl.get()));
    }

    /**
     * Binds a linear-volume {@link Slider} ([0,1]) to the channel VM with the
     * {@link SliderBinding} discipline (story 322): commit per value change (a
     * DAW fader is audible while dragging), the VM→slider echo suppressed while
     * {@link Slider#isValueChanging()} and re-applied once on release, snap-back
     * on a rejected value. Raises {@link SetChannelVolumeCommand}.
     *
     * @param slider the fader; must not be {@code null}
     * @throws IllegalStateException if this binder has no paired channel VM
     */
    public void bindFader(Slider slider) {
        Objects.requireNonNull(slider, "slider must not be null");
        requireChannel();
        disposers.add(SliderBinding.bind(slider, channelVm.volumeProperty(),
                value -> new SetChannelVolumeCommand(channel, value), commandSink));
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
                (_, _, now) -> panControl.set(now.doubleValue());
        channelVm.panProperty().addListener(listener);
        disposers.add(() -> channelVm.panProperty().removeListener(listener));
        onCommit(panControl, channelVm::getPan,
                () -> new SetChannelPanCommand(channel, panControl.get()));
    }

    /**
     * Binds a pan {@link Slider} ([−1,1]) to the channel VM with the
     * {@link SliderBinding} discipline (see {@link #bindFader(Slider)}). Raises
     * {@link SetChannelPanCommand}.
     *
     * @param slider the pan slider; must not be {@code null}
     * @throws IllegalStateException if this binder has no paired channel VM
     */
    public void bindPan(Slider slider) {
        Objects.requireNonNull(slider, "slider must not be null");
        requireChannel();
        disposers.add(SliderBinding.bind(slider, channelVm.panProperty(),
                value -> new SetChannelPanCommand(channel, value), commandSink));
    }

    private void requireChannel() {
        if (channel == null || channelVm == null) {
            throw new IllegalStateException(
                    "this binder has no paired mixer channel; fader/pan/strip cannot be bound");
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
        ChangeListener<Number> commit = (_, _, now) -> {
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
