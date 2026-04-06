package com.benesquivelmusic.daw.core.track;

/**
 * Controls how a track's automation lanes interact with playback.
 *
 * <p>When set to {@link #READ}, the audio engine applies automation lane
 * values to mixer parameters each processing block. When set to {@link #OFF},
 * automation data is preserved but not applied during playback.</p>
 */
public enum AutomationMode {

    /**
     * Automation lanes are read during playback and their values are applied
     * to the corresponding mixer channel parameters each audio block.
     */
    READ,

    /**
     * Automation lanes are ignored during playback. The mixer uses the
     * static fader/knob values set by the user.
     */
    OFF
}
