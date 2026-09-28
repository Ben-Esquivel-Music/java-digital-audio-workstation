package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.vm.command.SetChannelPanCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.SetChannelVolumeCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleChannelMuteCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleChannelSoloCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.TrackCommand;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.css.PseudoClass;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Slider;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Binds the controls of a <em>standalone</em> mixer channel — a return bus,
 * the master, or any channel with no paired {@code Track} — to its
 * {@link ChannelVM} and routes their gestures to channel-targeted
 * {@link TrackCommand}s (story 322, Audio Engine Wiring Design Book §5.6:
 * return/master strips bind through the same intent path as track strips).
 *
 * <p>The sibling of {@link TrackControlBinder}: identical discipline (each
 * control's visible state is a pure function of the VM; a gesture raises an
 * intent through the shared {@link Consumer}&lt;{@link TrackCommand}&gt; sink and
 * the control updates only as a subscriber — Control Synchronization Design
 * Book §4.4), but over {@link ChannelVM#mutedProperty()} /
 * {@link ChannelVM#soloedProperty()} instead of a {@code TrackVM}, and raising
 * {@link ToggleChannelMuteCommand} / {@link ToggleChannelSoloCommand}. Fader
 * and pan share the {@link SliderBinding} helper with the track binder.</p>
 *
 * <p>{@link #dispose()} removes every listener installed here. Idempotent.</p>
 */
public final class ChannelControlBinder {

    /** The {@code :active} pseudo-class shared with the lane controllers (UI Design Book §2.1). */
    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");

    private final MixerChannel channel;
    private final ChannelVM channelVm;
    private final Consumer<TrackCommand> commandSink;
    private final List<Runnable> disposers = new ArrayList<>();

    /**
     * Creates a binder over a standalone channel's VM that emits commands into
     * {@code commandSink}.
     *
     * @param channel     the channel the controls act on; must not be {@code null}
     * @param channelVm   the channel view-model the controls observe; must not be {@code null}
     * @param commandSink where control gestures are dispatched; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public ChannelControlBinder(MixerChannel channel, ChannelVM channelVm,
                                Consumer<TrackCommand> commandSink) {
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.channelVm = Objects.requireNonNull(channelVm, "channelVm must not be null");
        this.commandSink = Objects.requireNonNull(commandSink, "commandSink must not be null");
    }

    /**
     * Binds a linear-volume slider ([0,1]) to {@code channelVm.volume} with the
     * {@link SliderBinding} discipline (commit per tick, echo suppressed while
     * dragging, re-applied on release), raising {@link SetChannelVolumeCommand}.
     *
     * @param slider the fader; must not be {@code null}
     */
    public void bindFader(Slider slider) {
        disposers.add(SliderBinding.bind(slider, channelVm.volumeProperty(),
                value -> new SetChannelVolumeCommand(channel, value), commandSink));
    }

    /**
     * Binds a pan slider ([−1,1]) to {@code channelVm.pan} with the
     * {@link SliderBinding} discipline, raising {@link SetChannelPanCommand}.
     *
     * @param slider the pan slider; must not be {@code null}
     */
    public void bindPan(Slider slider) {
        disposers.add(SliderBinding.bind(slider, channelVm.panProperty(),
                value -> new SetChannelPanCommand(channel, value), commandSink));
    }

    /**
     * Binds a mute button: its {@code :active} pseudo-class follows
     * {@code channelVm.muted} (seeded at bind time — the style is a function of
     * the model, never of a click); a click raises {@link ToggleChannelMuteCommand}
     * with the negated current state.
     *
     * @param muteControl the mute button; must not be {@code null}
     */
    public void bindMute(ButtonBase muteControl) {
        bindFlag(muteControl, channelVm.mutedProperty(), channelVm::isMuted,
                () -> new ToggleChannelMuteCommand(channel, !channelVm.isMuted()));
    }

    /**
     * Binds a solo button: its {@code :active} pseudo-class follows
     * {@code channelVm.soloed}; a click raises {@link ToggleChannelSoloCommand}.
     *
     * @param soloControl the solo button; must not be {@code null}
     */
    public void bindSolo(ButtonBase soloControl) {
        bindFlag(soloControl, channelVm.soloedProperty(), channelVm::isSoloed,
                () -> new ToggleChannelSoloCommand(channel, !channelVm.isSoloed()));
    }

    private void bindFlag(ButtonBase control, ReadOnlyBooleanProperty flag,
                          BooleanSupplier current, Supplier<TrackCommand> factory) {
        Objects.requireNonNull(control, "control must not be null");
        control.pseudoClassStateChanged(ACTIVE, current.getAsBoolean());
        ChangeListener<Boolean> listener =
                (_, _, now) -> control.pseudoClassStateChanged(ACTIVE, Boolean.TRUE.equals(now));
        flag.addListener(listener);
        disposers.add(() -> flag.removeListener(listener));
        // Built per click so it captures the VM's state at click time.
        control.setOnAction(_ -> commandSink.accept(factory.get()));
        disposers.add(() -> control.setOnAction(null));
    }

    /** Removes every listener and handler installed by this binder. Idempotent. */
    public void dispose() {
        for (Runnable d : disposers) {
            d.run();
        }
        disposers.clear();
    }
}
