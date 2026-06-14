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
 * story 291).
 *
 * <p>For every intent the handler runs, in order:</p>
 * <ol>
 *   <li><strong>VALIDATE</strong> — a no-op gate when <em>every</em> surface the
 *       intent owns already matches the request, so an idempotent toggle neither
 *       mutates nor announces. For mute/solo this means both the {@code Track}
 *       <em>and</em> its paired {@code MixerChannel}: if they have drifted apart,
 *       the gate lets the intent through to re-sync them (see the §1.3 note).</li>
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
 * <h2>The §1.3 fix — mute/solo in lock-step</h2>
 *
 * <p>Historically the codebase carried two unsynchronised mute/solo flags:
 * {@code Track.muted/solo} (the arrangement lane and persistence) and
 * {@code MixerChannel.muted/solo} (what the audio engine reads). This handler
 * unifies them: {@link #toggleMute}/{@link #toggleSolo} mutate the {@code Track}
 * (the authority for {@code TrackVM}/persistence) <em>and</em> the paired
 * {@code MixerChannel} found via
 * {@link DawProject#getMixerChannelForTrack(Track)} (the engine + the truthful
 * {@code MixerEvent}), then announce a {@code TrackEvent} and/or a
 * {@code MixerEvent}, so the two surfaces and the engine never diverge. The gate
 * is evaluated <em>per surface</em>: each handler mutates and announces only the
 * surface(s) whose state actually differs from the request, so a pair that
 * started out of sync (a legacy direct {@code setMuted}, or a load path that
 * restored only one flag) is healed back into lock-step on the next toggle —
 * and a genuine no-op (both already at the requested state) still announces
 * nothing. Arm is track-only (no mixer-channel armed flag), so
 * {@link #toggleArm} announces only {@code TrackEvent.Armed}. Volume and pan are
 * channel-only.</p>
 *
 * <h2>Deferred</h2>
 *
 * <p>The PROJECT phase ({@code ProjectVM.dirty}) is story 292's concern and is
 * skipped here (§5.1 permits skipping a phase). Undo capture is left to story
 * 293's rewiring of the live {@code TrackStripController}/{@code MixerView}: the
 * existing undoable {@code ToggleMuteAction}/{@code SetVolumeAction} remain for
 * that wiring. This handler is the neutral, test-proven seam the commands target
 * — it does not rewire any live controller.</p>
 */
public final class CoreTrackIntentHandler implements TrackIntentHandler {

    private final DawProject project;

    /**
     * Creates a handler bound to {@code project} (used to resolve a track's
     * paired mixer channel for the §1.3 mute/solo mirror).
     *
     * @param project the authoritative project; must not be {@code null}
     * @throws NullPointerException if {@code project} is {@code null}
     */
    public CoreTrackIntentHandler(DawProject project) {
        this.project = Objects.requireNonNull(project, "project must not be null");
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
        if (track.isArmed() == armed) {
            return; // VALIDATE: idempotent
        }
        // Arm is track-only — no MixerChannel armed state to mirror.
        track.setArmed(armed);
        EventBusPublisher.publish(
                new TrackEvent.Armed(UUID.fromString(track.getId()), armed, Instant.now()));
    }

    @Override
    public void setVolume(MixerChannel channel, double volume) {
        Objects.requireNonNull(channel, "channel must not be null");
        if (channel.getVolume() == volume) {
            return; // VALIDATE: idempotent
        }
        // MUTATE may throw IllegalArgumentException for an out-of-[0,1] value;
        // let it propagate — the binder catches it exactly as the tempo field does.
        channel.setVolume(volume);
        EventBusPublisher.publish(new MixerEvent.GainChanged(channel.getId(), Instant.now()));
    }

    @Override
    public void setPan(MixerChannel channel, double pan) {
        Objects.requireNonNull(channel, "channel must not be null");
        if (channel.getPan() == pan) {
            return; // VALIDATE: idempotent
        }
        channel.setPan(pan); // may throw IllegalArgumentException for out-of-[−1,1]
        EventBusPublisher.publish(new MixerEvent.PanChanged(channel.getId(), Instant.now()));
    }
}
