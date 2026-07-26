package com.benesquivelmusic.daw.app.ui.settings;

/**
 * When a changed setting takes effect (Settings View Design Book §6.4,
 * story 305).
 *
 * <p>Exactly three apply classes, in the book's §6.4 order. This
 * replaces the audio tab's blanket "may require a restart" hint with
 * per-setting truth: most audio changes are an engine reconfigure, not
 * an application restart, and only the backend choice genuinely needs a
 * restart. The enum shape is pinned by
 * {@code DescriptorEnumsAreCanonicalTest}.</p>
 */
public enum ApplyClass {

    /** Takes effect immediately — no engine re-arm, no restart. */
    LIVE,

    /**
     * Takes effect on the next audio-engine stream re-arm (an engine
     * stop/start cycle), <em>not</em> an application restart.
     */
    ENGINE_RECONFIGURE,

    /**
     * Takes effect only after the application restarts. Exactly one
     * descriptor ({@code audio.backend}) carries this class today.
     */
    RESTART_REQUIRED
}
