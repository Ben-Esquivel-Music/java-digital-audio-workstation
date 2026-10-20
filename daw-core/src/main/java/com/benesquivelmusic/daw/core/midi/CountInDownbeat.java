package com.benesquivelmusic.daw.core.midi;

/**
 * Bar 1's downbeat as the render thread reached it at the end of a count-in
 * (story 328, Recording Reliability book §5.6): whether the count-in has
 * fired, and the monotonic clock ({@code System.nanoTime()}) it fired at.
 * A {@link MidiRecorder} given one measures bar 1 from the real fire
 * instead of the window its caller predicted when the count-in began;
 * see {@link MidiRecorder#setCountInDownbeat(CountInDownbeat)}.
 *
 * <p>Both reads are volatile loads on the caller's thread, and the stamp is
 * written before the fire is published: a {@code true} {@link #isFired()}
 * makes {@link #fireNanos()} meaningful.</p>
 */
public interface CountInDownbeat {

    /** Whether the render thread reached the count-in's end and ran the record start. */
    boolean isFired();

    /** The monotonic clock at the fire; meaningful only once {@link #isFired()} reads {@code true}. */
    long fireNanos();
}
