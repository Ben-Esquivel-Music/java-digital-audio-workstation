package com.benesquivelmusic.daw.app.ui.vm.command;

import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.track.Track;

/**
 * The intent seam a {@link TrackCommand} flows into — the up-going half of
 * "state flows down, intent flows up" (Control Synchronization Design Book §2.2,
 * §3.4) for track and channel controls (story 291). A command never writes
 * track or channel state directly; it asks the handler to run the corresponding
 * mutation path.
 *
 * <p>The production implementation ({@link CoreTrackIntentHandler}) wraps the
 * existing mutation path and runs the §5.1 cascade for each intent: VALIDATE →
 * MUTATE (which fires the core change signal, so the {@code TrackVM} /
 * {@code ChannelVM} republish their properties) → ANNOUNCE (a typed
 * {@code TrackEvent} / {@code MixerEvent} on the bus). Crucially, the mute and
 * solo intents mutate <em>both</em> the {@code Track} (the authority for the
 * arrangement lane and persistence) and its paired {@code MixerChannel} (what
 * the audio engine reads), keeping the two historically-unsynchronised flags in
 * lock-step — the §1.3 fix. Tests substitute a recording fake to assert that a
 * control gesture issues the right command without touching the engine.</p>
 */
public interface TrackIntentHandler {

    /**
     * Sets the track's muted flag and mirrors it onto the paired mixer channel
     * (§1.3, §5.2).
     *
     * @param track the track whose mute to set
     * @param muted the requested mute state
     */
    void toggleMute(Track track, boolean muted);

    /**
     * Sets the track's solo flag and mirrors it onto the paired mixer channel
     * (§1.3, §5.2).
     *
     * @param track  the track whose solo to set
     * @param soloed the requested solo state
     */
    void toggleSolo(Track track, boolean soloed);

    /**
     * Sets the track's armed (record-ready) flag. Arm is track-only — there is
     * no mixer-channel armed state to mirror (§5.2).
     *
     * @param track the track whose arm to set
     * @param armed the requested armed state
     */
    void toggleArm(Track track, boolean armed);

    /**
     * Sets the channel's linear volume (§5.2). Channel/{@code ChannelVM} only;
     * the track carries its own volume but the audio engine reads the channel's.
     *
     * @param channel the channel whose volume to set
     * @param volume  the requested linear volume in [0,1]
     */
    void setVolume(MixerChannel channel, double volume);

    /**
     * Sets the channel's pan position (§5.2). Channel/{@code ChannelVM} only.
     *
     * @param channel the channel whose pan to set
     * @param pan     the requested pan in [−1,1]
     */
    void setPan(MixerChannel channel, double pan);
}
