package com.benesquivelmusic.daw.core.audio.health;

/**
 * The real-time side's failure categories, as {@code int} compile-time
 * constants (story 337, rulings E22 and E23).
 *
 * <p>A real-time site records with one of these constants, never with an
 * {@link AudioFailureCategory} constant: a {@code static final int} is inlined
 * by the compiler, so recording touches no class initialisation, and a site
 * that branched on an enum would compile to a synthetic {@code $SwitchMap$}
 * class whose first use allocates and takes the class-init lock on the render
 * thread. {@link AudioFailureCategory} carries the same codes, in the same
 * order, for the drain side.</p>
 */
public final class AudioFailureCodes {

    /** {@code audio.callback} — a device-callback guard (PortAudio or ASIO upcall). */
    public static final int CALLBACK = 0;

    /** {@code audio.render} — {@code processBlock} threw on the render drive. */
    public static final int RENDER = 1;

    /** {@code audio.render.halted} — the render drive's terminal honest exit. */
    public static final int RENDER_HALTED = 2;

    /** {@code audio.sink} — the backend refused a rendered block. */
    public static final int SINK = 3;

    /** {@code audio.pacing} — the backend's capacity wait failed. */
    public static final int PACING = 4;

    /** {@code audio.input} — captured input lost on a backend thread. */
    public static final int INPUT = 5;

    /** {@code audio.worker} — a task of the multi-core worker pool threw. */
    public static final int WORKER = 6;

    /** {@code audio.dsp} — part of a processor's work failed. */
    public static final int DSP = 7;

    /** {@code audio.midi} — MIDI rendering failed. */
    public static final int MIDI = 8;

    /** {@code audio.plugin} — a CLAP host failure. */
    public static final int PLUGIN = 9;

    /** {@code audio.automation} — an automation setter rejected a value. */
    public static final int AUTOMATION = 10;

    /** {@code audio.config} — the mix has a configuration the render path cannot honour. */
    public static final int CONFIG = 11;

    /** {@code audio.device} — a device-notification upcall could not be handled. */
    public static final int DEVICE = 12;

    /** The number of categories; every valid code is in {@code [0, COUNT)}. */
    public static final int COUNT = 13;

    private AudioFailureCodes() {
    }
}
