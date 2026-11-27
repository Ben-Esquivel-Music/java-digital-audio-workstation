package com.benesquivelmusic.daw.core.audio.health;

/**
 * The drain side's names for the real-time failure categories (story 337,
 * ruling E23): one constant per {@link AudioFailureCodes} code, declared in
 * code order, each with its user-facing category slug.
 *
 * <p>Never referenced from a real-time method — a real-time site records with
 * the {@code int} constants of {@link AudioFailureCodes} (story 337, ruling
 * E22). {@code audio.watchdog} and {@code audio.xrun} are log slugs of the
 * heartbeat watchdog and the xrun feed, not categories of this record.</p>
 */
public enum AudioFailureCategory {

    /** A device-callback guard caught a fault. */
    CALLBACK(AudioFailureCodes.CALLBACK, "audio.callback"),
    /** {@code processBlock} threw on the render drive. */
    RENDER(AudioFailureCodes.RENDER, "audio.render"),
    /** The render drive's terminal honest exit. */
    RENDER_HALTED(AudioFailureCodes.RENDER_HALTED, "audio.render.halted"),
    /** The backend refused a rendered block. */
    SINK(AudioFailureCodes.SINK, "audio.sink"),
    /** The backend's capacity wait failed. */
    PACING(AudioFailureCodes.PACING, "audio.pacing"),
    /** Captured input lost on a backend thread. */
    INPUT(AudioFailureCodes.INPUT, "audio.input"),
    /** A worker-pool task threw. */
    WORKER(AudioFailureCodes.WORKER, "audio.worker"),
    /** Part of a processor's work failed. */
    DSP(AudioFailureCodes.DSP, "audio.dsp"),
    /** MIDI rendering failed. */
    MIDI(AudioFailureCodes.MIDI, "audio.midi"),
    /** A CLAP host failure. */
    PLUGIN(AudioFailureCodes.PLUGIN, "audio.plugin"),
    /** An automation setter rejected a value. */
    AUTOMATION(AudioFailureCodes.AUTOMATION, "audio.automation"),
    /** The mix has a configuration the render path cannot honour. */
    CONFIG(AudioFailureCodes.CONFIG, "audio.config"),
    /** A device-notification upcall could not be handled. */
    DEVICE(AudioFailureCodes.DEVICE, "audio.device");

    private static final AudioFailureCategory[] BY_CODE = values();

    private final int code;
    private final String slug;

    AudioFailureCategory(int code, String slug) {
        this.code = code;
        this.slug = slug;
    }

    /**
     * Returns the {@link AudioFailureCodes} constant this category names.
     *
     * @return the code, in {@code [0, AudioFailureCodes.COUNT)}
     */
    public int code() {
        return code;
    }

    /**
     * Returns the user-facing category slug, for example
     * {@code "audio.callback"} — the funnel's fingerprint category.
     *
     * @return the slug; never {@code null}
     */
    public String slug() {
        return slug;
    }

    /**
     * Returns the category for an {@link AudioFailureCodes} code.
     *
     * @param code the code
     * @return the category; never {@code null}
     * @throws IllegalArgumentException if {@code code} is out of range
     */
    public static AudioFailureCategory ofCode(int code) {
        if (code < 0 || code >= BY_CODE.length) {
            throw new IllegalArgumentException("no audio failure category for code " + code);
        }
        return BY_CODE[code];
    }
}
