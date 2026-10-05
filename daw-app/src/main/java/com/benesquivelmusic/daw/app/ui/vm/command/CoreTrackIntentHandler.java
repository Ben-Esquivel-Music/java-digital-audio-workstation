package com.benesquivelmusic.daw.app.ui.vm.command;

import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.event.MixerEvent;
import com.benesquivelmusic.daw.sdk.event.TrackEvent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The production {@link TrackIntentHandler}: it wraps the {@link Track} and
 * {@link MixerChannel} mutation paths and runs the universal cascade for each
 * track/channel intent (Control Synchronization Design Book §5.1, §5.2;
 * story 291; story 322 — Audio Engine Wiring Design Book §2.10, §5.6).
 *
 * <p>For every intent the handler runs, in order:</p>
 * <ol>
 *   <li><strong>VALIDATE</strong> — a no-op gate when <em>every</em> surface the
 *       intent owns already matches the request, so an idempotent toggle neither
 *       mutates nor announces. For every intent that has two surfaces this means
 *       both the {@code Track} <em>and</em> its paired {@code MixerChannel}: if
 *       they have drifted apart, the gate lets the intent through to re-sync them
 *       (see the §1.3 note).</li>
 *   <li><strong>MUTATE</strong> — call the core setter(s). That fires the
 *       toolkit-neutral change signal, so the {@code TrackVM} / {@code ChannelVM}
 *       re-read and republish their properties (the REPUBLISH phase happens
 *       automatically via the signal + dispatcher; this handler touches no
 *       {@code Property}).</li>
 *   <li><strong>ANNOUNCE</strong> — publish the existing typed
 *       {@link TrackEvent} / {@link MixerEvent} on the bus via
 *       {@link EventBusPublisher} (live since story 283) so unrelated surfaces
 *       react.</li>
 * </ol>
 *
 * <h2>The §1.3 fix — every mirrored fact in lock-step</h2>
 *
 * <p>Historically the codebase carried two unsynchronised copies of mute/solo
 * <em>and</em> of volume/pan: {@code Track.*} (the arrangement lane and
 * persistence) and {@code MixerChannel.*} (what the audio engine reads). This
 * handler unifies them. The track-targeted intents ({@link #toggleMute},
 * {@link #toggleSolo}) mutate the {@code Track} <em>and</em> the paired channel
 * found via {@link DawProject#getMixerChannelForTrack(Track)}; the
 * channel-targeted intents ({@link #setVolume}, {@link #setPan},
 * {@link #toggleChannelMute}, {@link #toggleChannelSolo}) mutate the channel
 * <em>and</em> the paired track found via the story-322 reverse lookup
 * {@link DawProject#getTrackForChannel(MixerChannel)} — a standalone channel
 * (return bus, master) has no track and is channel-only. The gate is evaluated
 * <em>per surface</em>: each intent mutates and announces only the surface(s)
 * whose state actually differs from the request, so a pair that started out of
 * sync (a legacy direct setter, or a load path that restored only one side) is
 * healed back into lock-step on the next intent — and a genuine no-op (both
 * already at the requested state) still announces nothing. Arm is track-only
 * (no mixer-channel armed flag), so {@link #toggleArm} announces only
 * {@code TrackEvent.Armed}.</p>
 *
 * <p>Volume/pan announce one {@link MixerEvent.GainChanged} /
 * {@link MixerEvent.PanChanged} per intent that changed the <em>channel</em>
 * (the bus fact is "this channel's gain/pan moved" — the engine's value). A
 * Track-only heal, where the channel already matched the request and only the
 * arrangement mirror was brought back into step, announces nothing: the
 * audible state did not change, so a bus event would be a phantom.</p>
 *
 * <h2>Deferred</h2>
 *
 * <p>The PROJECT phase ({@code ProjectVM.dirty}) is story 292's concern and is
 * skipped here (§5.1 permits skipping a phase). Undo capture is not the
 * handler's concern either, and today it happens only for renames: no surface
 * records an undoable action for a mute / solo / volume / pan gesture; a
 * mixer-strip rename is made undoable by {@code MixerView}, which wraps the
 * {@link #renameTrack} intent in its own {@code UndoableAction} before raising
 * it through the sink; the arrangement lane's inline rename keeps its own
 * {@code UndoableAction} that writes the {@code Track} directly.</p>
 */
public final class CoreTrackIntentHandler implements TrackIntentHandler {

    private final DawProject project;

    /**
     * Creates a handler bound to {@code project} (used to resolve a track's
     * paired mixer channel — and a channel's paired track — for the dual-writes).
     *
     * @param project the authoritative project; must not be {@code null}
     * @throws NullPointerException if {@code project} is {@code null}
     */
    public CoreTrackIntentHandler(DawProject project) {
        this.project = Objects.requireNonNull(project, "project must not be null");
    }

    private java.util.function.BiConsumer<Track, Runnable> armValidator = (track, accept) -> accept.run();
    private java.util.function.Consumer<Track> cancelArm = track -> { };
    public void setArmCancellation(java.util.function.Consumer<Track> cancellation) { cancelArm = Objects.requireNonNull(cancellation); }
    public void setArmValidator(java.util.function.BiConsumer<Track, Runnable> validator) {
        armValidator = Objects.requireNonNull(validator);
    }

    @Override
    public void toggleMute(Track track, boolean muted) {
        Objects.requireNonNull(track, "track must not be null");
        MixerChannel channel = project.getMixerChannelForTrack(track);
        // VALIDATE per surface: proceed when EITHER the Track (lane + persistence
        // authority) or its paired MixerChannel (engine) disagrees with the
        // request, so a divergent pair is re-synced into §1.3 lock-step rather
        // than left split by a gate keyed on only one of them.
        boolean trackChanged = track.isMuted() != muted;
        boolean channelChanged = channel != null && channel.isMuted() != muted;
        if (!trackChanged && !channelChanged) {
            return; // both surfaces already at the requested state — true no-op
        }
        // MUTATE + ANNOUNCE only the surface(s) that actually changed.
        Instant now = Instant.now();
        if (trackChanged) {
            track.setMuted(muted);
            EventBusPublisher.publish(new TrackEvent.Muted(UUID.fromString(track.getId()), muted, now));
        }
        if (channelChanged) {
            channel.setMuted(muted);
            EventBusPublisher.publish(new MixerEvent.MuteChanged(channel.getId(), muted, now));
        }
    }

    @Override
    public void toggleSolo(Track track, boolean soloed) {
        Objects.requireNonNull(track, "track must not be null");
        MixerChannel channel = project.getMixerChannelForTrack(track);
        boolean trackChanged = track.isSolo() != soloed;
        boolean channelChanged = channel != null && channel.isSolo() != soloed;
        if (!trackChanged && !channelChanged) {
            return; // both surfaces already at the requested state — true no-op
        }
        Instant now = Instant.now();
        if (trackChanged) {
            track.setSolo(soloed);
            EventBusPublisher.publish(new TrackEvent.Soloed(UUID.fromString(track.getId()), soloed, now));
        }
        if (channelChanged) {
            channel.setSolo(soloed);
            EventBusPublisher.publish(new MixerEvent.SoloChanged(channel.getId(), soloed, now));
        }
    }

    @Override
    public void toggleArm(Track track, boolean armed) {
        Objects.requireNonNull(track, "track must not be null");
        if (!armed) cancelArm.accept(track);
        if (track.isArmed() == armed) {
            return; // VALIDATE: idempotent
        }
        if (armed) armValidator.accept(track, () -> applyArm(track, true));
        else applyArm(track, false);
    }

    private void applyArm(Track track, boolean armed) {
        if (track.isArmed() == armed) return;
        track.setArmed(armed);
        EventBusPublisher.publish(new TrackEvent.Armed(UUID.fromString(track.getId()), armed, Instant.now()));
    }

    @Override
    public void setVolume(MixerChannel channel, double volume) {
        Objects.requireNonNull(channel, "channel must not be null");
        Track track = project.getTrackForChannel(channel).orElse(null);
        // VALIDATE per surface (story 322): the engine's channel AND the
        // arrangement's Track, when there is one.
        boolean channelChanged = channel.getVolume() != volume;
        boolean trackChanged = track != null && track.getVolume() != volume;
        if (!channelChanged && !trackChanged) {
            return; // true no-op
        }
        // MUTATE the channel first: it may throw IllegalArgumentException for an
        // out-of-[0,1] value (the binder catches it exactly as the tempo field
        // does), and then the Track must not have been touched either.
        if (channelChanged) {
            channel.setVolume(volume);
        }
        if (trackChanged) {
            track.setVolume(volume);
        }
        // ANNOUNCE only the engine fact: a Track-only heal moved no gain.
        if (channelChanged) {
            EventBusPublisher.publish(new MixerEvent.GainChanged(channel.getId(), Instant.now()));
        }
    }

    @Override
    public void setPan(MixerChannel channel, double pan) {
        Objects.requireNonNull(channel, "channel must not be null");
        Track track = project.getTrackForChannel(channel).orElse(null);
        boolean channelChanged = channel.getPan() != pan;
        boolean trackChanged = track != null && track.getPan() != pan;
        if (!channelChanged && !trackChanged) {
            return; // true no-op
        }
        if (channelChanged) {
            channel.setPan(pan); // may throw IllegalArgumentException for out-of-[−1,1]
        }
        if (trackChanged) {
            track.setPan(pan);
        }
        // ANNOUNCE only the engine fact: a Track-only heal moved no pan.
        if (channelChanged) {
            EventBusPublisher.publish(new MixerEvent.PanChanged(channel.getId(), Instant.now()));
        }
    }

    @Override
    public void toggleChannelMute(MixerChannel channel, boolean muted) {
        Objects.requireNonNull(channel, "channel must not be null");
        Track track = project.getTrackForChannel(channel).orElse(null);
        boolean channelChanged = channel.isMuted() != muted;
        boolean trackChanged = track != null && track.isMuted() != muted;
        if (!channelChanged && !trackChanged) {
            return; // VALIDATE: idempotent on every surface
        }
        Instant now = Instant.now();
        if (channelChanged) {
            channel.setMuted(muted);
            EventBusPublisher.publish(new MixerEvent.MuteChanged(channel.getId(), muted, now));
        }
        if (trackChanged) {
            track.setMuted(muted);
            EventBusPublisher.publish(new TrackEvent.Muted(UUID.fromString(track.getId()), muted, now));
        }
    }

    @Override
    public void toggleChannelSolo(MixerChannel channel, boolean soloed) {
        Objects.requireNonNull(channel, "channel must not be null");
        Track track = project.getTrackForChannel(channel).orElse(null);
        boolean channelChanged = channel.isSolo() != soloed;
        boolean trackChanged = track != null && track.isSolo() != soloed;
        if (!channelChanged && !trackChanged) {
            return; // VALIDATE: idempotent on every surface
        }
        Instant now = Instant.now();
        if (channelChanged) {
            channel.setSolo(soloed);
            EventBusPublisher.publish(new MixerEvent.SoloChanged(channel.getId(), soloed, now));
        }
        if (trackChanged) {
            track.setSolo(soloed);
            EventBusPublisher.publish(new TrackEvent.Soloed(UUID.fromString(track.getId()), soloed, now));
        }
    }

    @Override
    public void renameTrack(Track track, String name) {
        Objects.requireNonNull(track, "track must not be null");
        Objects.requireNonNull(name, "name must not be null");
        // The command owns the strip rule (RenameTrackCommand.normalize) so
        // VALIDATE and a surface asking "would this change anything?"
        // (RenameTrackCommand.changesNothing) can never disagree.
        String requested = RenameTrackCommand.normalize(name);
        if (requested.isEmpty()) {
            // VALIDATE: a blank name is refused, exactly as an out-of-range
            // fader value is — the raising control snaps back to the VM's name.
            throw new IllegalArgumentException("track name must not be blank");
        }
        if (requested.equals(track.getName())) {
            return; // VALIDATE: idempotent
        }
        // MUTATE: track-only — a MixerChannel's name is fixed at creation.
        track.setName(requested);
        // ANNOUNCE
        EventBusPublisher.publish(
                new TrackEvent.Renamed(UUID.fromString(track.getId()), Instant.now()));
    }
}
