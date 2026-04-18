package com.benesquivelmusic.daw.core.track;

/**
 * Controls how a track's automation lanes interact with playback and
 * recording.
 *
 * <p>The four active modes ({@link #READ}, {@link #WRITE}, {@link #LATCH},
 * {@link #TOUCH}) all read existing automation during playback; they differ
 * in whether (and how) user interaction with a fader, knob or plugin control
 * writes new automation breakpoints at the current transport position.
 * {@link #OFF} disables both reading and writing.</p>
 */
public enum AutomationMode {

    /**
     * Automation lanes are read during playback and their values are applied
     * to the corresponding mixer channel parameters each audio block.
     *
     * <p>User interaction with controls is <em>not</em> captured as new
     * automation breakpoints.</p>
     */
    READ,

    /**
     * Automation lanes are read during playback <em>and</em> every block
     * while playback is running, the current fader/knob value is written as
     * a new breakpoint at the transport position — overwriting any existing
     * automation.
     *
     * <p>Use {@code WRITE} to replace the entire automation pass, for example
     * to re-record a volume envelope from scratch.</p>
     */
    WRITE,

    /**
     * Automation lanes are read during playback. Writing starts the first
     * time the user touches a control during the pass and continues until
     * playback stops.
     *
     * <p>Use {@code LATCH} to overdub automation from a single trigger point
     * to the end of the region — useful when punching in a correction that
     * should hold until the end.</p>
     */
    LATCH,

    /**
     * Automation lanes are read during playback. Writing occurs only while
     * the user is actively holding / adjusting a control; releasing the
     * control snaps the parameter back to the existing automation curve.
     *
     * <p>Use {@code TOUCH} to overdub short corrections without affecting
     * the rest of the envelope.</p>
     */
    TOUCH,

    /**
     * Automation lanes are ignored during playback. The mixer uses the
     * static fader/knob values set by the user. No writing occurs.
     */
    OFF;

    /**
     * Returns {@code true} if this mode reads existing automation during
     * playback (all modes except {@link #OFF}).
     *
     * @return {@code true} if automation values should be applied during playback
     */
    public boolean readsAutomation() {
        return this != OFF;
    }

    /**
     * Returns {@code true} if this mode writes new automation breakpoints
     * from user interaction ({@link #WRITE}, {@link #LATCH}, {@link #TOUCH}).
     *
     * @return {@code true} if user interaction should be recorded as automation
     */
    public boolean writesAutomation() {
        return this == WRITE || this == LATCH || this == TOUCH;
    }
}
