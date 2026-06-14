package com.benesquivelmusic.daw.app.ui.vm.command;

import com.benesquivelmusic.daw.app.ui.EditTool;
import com.benesquivelmusic.daw.core.track.Track;

/**
 * The intent seam a {@link SelectionCommand} flows into — the up-going half of
 * "state flows down, intent flows up" (Control Synchronization Design Book §2.2,
 * §3.4, §5.5). A command never writes the selection directly; it asks the handler
 * to run the corresponding mutation.
 *
 * <p>The production implementation ({@link CoreSelectionIntentHandler}) runs the
 * §5.1 cascade per intent: VALIDATE (an idempotent gate) → MUTATE (write the
 * {@code SelectionVM}, the UI-authoritative selection model) → ANNOUNCE (publish
 * {@code UiEvent.SelectionChanged} on the bus, except for a tool change, which is
 * bound directly per §2.7). Tests substitute a recording fake to assert that a
 * gesture issues the right command.</p>
 */
public interface SelectionIntentHandler {

    /**
     * Selects a track as the focused track (§5.5 "Select clip(s)/track(s)").
     *
     * @param track the track to select
     */
    void selectTrack(Track track);

    /**
     * Selects a clip as the focused clip (§5.5).
     *
     * @param clip the clip to select
     */
    void selectClip(Object clip);

    /**
     * Selects a device (insert / plugin) as the focused device (§5.5).
     *
     * @param device the device to select
     */
    void selectDevice(Object device);

    /**
     * Changes the active edit tool (§5.5 "Change edit tool").
     *
     * @param tool the edit tool to activate
     */
    void setTool(EditTool tool);

    /** Clears the track / clip / device selection (the edit tool is unchanged). */
    void clear();
}
