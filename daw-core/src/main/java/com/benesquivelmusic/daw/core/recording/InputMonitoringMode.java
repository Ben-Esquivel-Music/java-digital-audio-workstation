package com.benesquivelmusic.daw.core.recording;

/**
 * Controls when input monitoring is active on an armed track.
 *
 * <p>Input monitoring routes the audio input signal through the track's
 * mixer channel so the performer can hear themselves in real time.</p>
 */
public enum InputMonitoringMode {

    /**
     * Input monitoring is disabled. The input signal is not routed
     * through the mixer channel during recording.
     */
    OFF,

    /**
     * Input monitoring is automatically enabled while the track is
     * armed and the transport is in recording mode, and disabled
     * during playback.
     */
    AUTO,

    /**
     * Input monitoring is always enabled while the track is armed,
     * regardless of the transport state.
     */
    ALWAYS
}
