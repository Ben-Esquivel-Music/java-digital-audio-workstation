package com.benesquivelmusic.daw.core.audio;

/**
 * The tag that marks an {@link AudioClip} as comp output (Recording
 * Reliability book §3.5, "Takes: one model"): an owner identifies one
 * compile pass of a comp — for a take comping, one
 * {@code TakeComping.compile()}. A clip carries the pass that produced it
 * ({@link AudioClip#compOutputOwner()}); a clip with none is a user clip.
 *
 * <p>The owner does nothing itself. The comping that made the pass finds
 * its output on its own track by matching any of its passes
 * ({@code TakeComping.isCompOutput}, {@code TakeComping.compOutputOn}), and
 * a recompile removes all of it — whatever edits have split, glued,
 * trimmed or moved those clips since — and adds the fresh compile, tagged
 * with the new pass, so the main lane is always exactly what the comping
 * state says it is. Clip edits keep the tag where the result is still comp
 * output (a split's halves, a remnant of a paste-over or a crop, a glue of
 * halves of the same pass) and drop it where the result is a copy the user
 * made (a duplicate, a paste).</p>
 *
 * <p>A recompile makes every clip of an earlier pass superseded output,
 * which never re-enters its own track ({@code Track.addClip}): an undo that
 * puts back a clip a recompile replaced cannot leave the comp playing twice
 * (PR #984 review). A clip moved to another track keeps its owner, which is
 * inert there — only the owning comping's own track scans for or refuses
 * its output — so it behaves as a user clip on that track. A move clears
 * the owner only when the destination's comping holds the clip as
 * superseded output ({@link CrossTrackMoveAction}).</p>
 *
 * <p>A marker: it declares no operation, and identity is the only thing a
 * clip compares it by ({@link AudioClip#isCompOutputOf}), so it tells one
 * pass from another, not one comp from another.</p>
 */
public interface CompOutputOwner {
}
