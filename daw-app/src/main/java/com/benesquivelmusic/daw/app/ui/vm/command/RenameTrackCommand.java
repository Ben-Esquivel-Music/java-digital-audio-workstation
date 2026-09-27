package com.benesquivelmusic.daw.app.ui.vm.command;

import com.benesquivelmusic.daw.core.track.Track;

import java.util.Objects;

/**
 * Intent to rename a track (story 322 — Audio Engine Wiring Design Book §2.10
 * "nothing dead"). The deferred story-271 {@code MixerChannelStrip} migration
 * made the strip's double-click inline name editor reachable in production; a
 * rename typed there must reach the model through the one intent path rather
 * than die in the strip's own {@code channelName} property and revert on the
 * next VM republish. The name is a {@code Track} fact only — a
 * {@code MixerChannel}'s name is fixed at creation — so the handler mutates the
 * track and announces {@code TrackEvent.Renamed}.
 *
 * <p>The command owns the ONE rule that turns the requested text into the
 * name the model would hold ({@link #normalize}, {@link #normalizedName()})
 * and can say whether executing it would change anything
 * ({@link #changesNothing()}); the handler's VALIDATE and the surface that
 * records the rename in its undo history both read that rule instead of
 * repeating it — the canonical value comes from the authority (story 322 fix
 * round 2, design book §5.6 "one path").</p>
 *
 * @param track the track to rename; must not be {@code null}
 * @param name  the requested name; must not be {@code null} (the handler strips
 *              whitespace and rejects a blank result with
 *              {@link IllegalArgumentException} so the raising control snaps
 *              back to the VM's name)
 */
public record RenameTrackCommand(Track track, String name) implements TrackCommand {

    /** @throws NullPointerException if {@code track} or {@code name} is {@code null} */
    public RenameTrackCommand {
        Objects.requireNonNull(track, "track must not be null");
        Objects.requireNonNull(name, "name must not be null");
    }

    /**
     * The strip rule behind every rename: surrounding whitespace removed.
     * Static because the handler receives the requested text through
     * {@link TrackIntentHandler#renameTrack(Track, String)}, not the command.
     *
     * @param name a requested name; must not be {@code null}
     * @return the normalised name; empty when the request was blank
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public static String normalize(String name) {
        return Objects.requireNonNull(name, "name must not be null").strip();
    }

    /**
     * Returns the name the handler would write for this command.
     *
     * @return {@link #normalize(String) normalize}{@code (name())}
     */
    public String normalizedName() {
        return normalize(name);
    }

    /**
     * Whether executing this command leaves the track's name as it is: its
     * {@link #normalizedName()} already equals {@code track().getName()}, so
     * the handler's VALIDATE no-ops. A blank request is NOT "nothing" — the
     * handler refuses it — and answers {@code false} unless the track's name
     * is itself empty. The mixer keeps such a command out of its undo
     * history: recording a no-op action would push a visible do-nothing
     * entry and clear the redo stack.
     *
     * @return {@code true} when the normalised name equals the track's current name
     */
    public boolean changesNothing() {
        return normalizedName().equals(track.getName());
    }

    @Override
    public void execute(TrackIntentHandler handler) {
        handler.renameTrack(track, name);
    }
}
