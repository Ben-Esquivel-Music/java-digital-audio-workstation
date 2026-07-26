package com.benesquivelmusic.daw.app.ui.settings;

/**
 * The reach of a setting — how far a changed value travels (Settings
 * View Design Book §3.2, story 305).
 *
 * <p>Exactly four scopes, in the book's §3.2 order. This is the
 * canonical vocabulary for the whole settings surface (one name per
 * thing); later stages surface the scope on screen but never invent a
 * synonym. The enum shape is pinned by
 * {@code DescriptorEnumsAreCanonicalTest}.</p>
 */
public enum Scope {

    /** Applies to the whole application, across every project. */
    APPLICATION,

    /**
     * Stored as a default for future projects. Some project-default
     * settings (default tempo, auto-save interval) are additionally
     * live-applied to the open project by {@code LiveSettingsApplier} —
     * the §3.2 dual-reach note. The tag records where the value is
     * stored; the secondary live application is behaviour, not scope.
     */
    PROJECT_DEFAULTS,

    /**
     * Applies to the currently open project only. No descriptor carries
     * this scope today — the constant exists per §3.2 canon so later
     * stages need no vocabulary change.
     */
    THIS_PROJECT,

    /** Tied to the configured audio device / driver stack. */
    AUDIO_DEVICE
}
