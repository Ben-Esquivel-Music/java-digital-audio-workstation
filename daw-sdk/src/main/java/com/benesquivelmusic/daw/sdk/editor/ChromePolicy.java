package com.benesquivelmusic.daw.sdk.editor;

/**
 * How much host chrome (breadcrumb header, preset/meter footer, A/B bar,
 * fault banner) surrounds a plugin editor surface.
 *
 * <p>A plugin declares its preferred chrome through {@link EditorHints#chrome()};
 * the host owns and renders the chrome in every mode (Plugin View Design Book
 * §2.1, §2.2). This enum only selects <em>how much</em> of that host chrome is
 * shown, never lets the plugin draw the chrome itself.</p>
 *
 * @see EditorHints
 */
public enum ChromePolicy {

    /**
     * Full host chrome: breadcrumb header, preset + I/O meter footer, and the
     * A/B compare bar are all shown around the plugin surface (§5.A, §6.1–§6.5).
     * The default for declarative and panel editors.
     */
    STANDARD,

    /**
     * Collapse to a title bar only — for tiny utilities (three knobs or fewer)
     * that do not warrant a full editor frame (§5.C compact strip).
     */
    MINIMAL,

    /**
     * The chrome reveals on hover and retracts after a short period of pointer
     * inactivity, so a full-surface tool (spectrum analyzer, oscilloscope,
     * match-EQ) owns every pixel the rest of the time (§5.D immersive canvas).
     */
    IMMERSIVE
}
