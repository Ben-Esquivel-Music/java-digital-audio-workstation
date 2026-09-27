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
     * Sets the channel's linear volume — what the audio engine reads — and
     * mirrors it onto the paired {@code Track} when the channel has one
     * (story 322: "the UI writes the model the engine reads; any mirrored
     * model is updated in the same dual-write, in one place" — Audio Engine
     * Wiring Design Book §2.10, §5.6). A standalone channel (return bus,
     * master) is channel-only.
     *
     * @param channel the channel whose volume to set
     * @param volume  the requested linear volume in [0,1]
     */
    void setVolume(MixerChannel channel, double volume);

    /**
     * Sets the channel's pan position and mirrors it onto the paired
     * {@code Track} when the channel has one (story 322, §2.10, §5.6).
     *
     * @param channel the channel whose pan to set
     * @param pan     the requested pan in [−1,1]
     */
    void setPan(MixerChannel channel, double pan);

    /**
     * Sets a channel's muted flag directly — for strips whose channel has no
     * track (return bus, master, VCA member without a track; story 322 §5.6).
     * When the channel <em>does</em> have a track the handler mirrors the flag
     * onto it, exactly as {@link #toggleMute(Track, boolean)} does in the other
     * direction, so both intents converge on lock-step state.
     *
     * @param channel the channel whose mute to set
     * @param muted   the requested mute state
     */
    void toggleChannelMute(MixerChannel channel, boolean muted);

    /**
     * Sets a channel's solo flag directly (the channel-targeted sibling of
     * {@link #toggleSolo(Track, boolean)}; story 322 §5.6). Mirrors onto a
     * paired track when one exists.
     *
     * @param channel the channel whose solo to set
     * @param soloed  the requested solo state
     */
    void toggleChannelSolo(MixerChannel channel, boolean soloed);

    /**
     * Renames the track (story 322 — Audio Engine Wiring Design Book §2.10
     * "nothing dead"). The mixer strip's inline name editor raises this intent
     * through the sink, with {@code MixerView} recording the undo entry around
     * the command; the arrangement lane's inline rename keeps its own
     * {@code UndoableAction} and writes the {@code Track} directly — both
     * surfaces' name labels follow {@code TrackVM.name} either way. Whitespace
     * is stripped; a blank result is rejected with
     * {@link IllegalArgumentException} (the raising control snaps back to the
     * VM's name); an unchanged name is a VALIDATE no-op that announces
     * nothing. The name is track-only: a {@code MixerChannel}'s name is fixed at
     * creation.
     *
     * @param track the track to rename
     * @param name  the requested name
     * @throws IllegalArgumentException if {@code name} is blank after stripping
     */
    void renameTrack(Track track, String name);
}
