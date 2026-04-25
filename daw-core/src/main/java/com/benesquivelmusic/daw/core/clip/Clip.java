package com.benesquivelmusic.daw.core.clip;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.midi.MidiClip;

/**
 * A clip placed on a track's timeline.
 *
 * <p>This interface unifies {@link AudioClip} and {@link MidiClip}
 * for the cross-cutting concerns that apply to any clip regardless of
 * media type — most importantly the <em>time-lock</em> flag exposed by
 * {@link #isLocked()} / {@link #setLocked(boolean)}.</p>
 *
 * <p>Conceptually this is a sealed interface (only {@link AudioClip} and
 * {@link MidiClip} are permitted); it is declared as a plain
 * {@code public interface} only because {@code daw-core} currently lacks
 * a {@code module-info.java}, and Java's sealed-class rules forbid
 * cross-package permits in the unnamed module.</p>
 *
 * <p>A time-locked clip refuses every operation that would change its
 * timeline position: move, nudge, cross-track drag, slip, and ripple.
 * Lock is strictly about <em>timeline position</em> — locked clips still
 * play, split, trim, and render normally. See the user story
 * "Clip-Level Time Lock Preventing Accidental Movement".</p>
 */
public interface Clip {

    /**
     * Returns {@code true} when this clip is time-locked and refuses
     * position-changing operations.
     *
     * @return whether the clip is locked
     */
    boolean isLocked();

    /**
     * Sets the time-lock flag.
     *
     * <p>Setting this directly bypasses the undo system; prefer
     * {@code SetClipLockedAction} for user-driven changes.</p>
     *
     * @param locked the new lock state
     */
    void setLocked(boolean locked);

    /**
     * Returns a short human-readable label identifying this clip in
     * status-bar messages (e.g. "kick.wav", "Verse MIDI").
     *
     * @return the clip's display name
     */
    String getDisplayName();
}
