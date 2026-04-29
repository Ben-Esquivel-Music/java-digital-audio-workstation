package com.benesquivelmusic.daw.app.ui;

/**
 * High-level audio-engine lifecycle state surfaced to the UI.
 *
 * <p>Distinct from {@link com.benesquivelmusic.daw.core.transport.TransportState}
 * (which is about play/record/stop) and from the audio stream's open/closed
 * flag — this captures the engine's overall availability so the transport bar
 * can show a "Reconnecting…" indicator while the audio device is gone, and so
 * tests can deterministically assert which lifecycle state the controller is
 * in after a disconnect.</p>
 */
public enum EngineState {

    /** Audio stream open and rendering normally. */
    RUNNING,

    /**
     * Audio stream is intentionally closed (no device disconnect involved).
     * The user can re-arm transport at any time.
     */
    STOPPED,

    /**
     * The active audio device disappeared mid-session (USB unplug, driver
     * crash, JACK server shutdown). The render thread is halted, the
     * in-flight recording take has been persisted, and the controller is
     * waiting for a matching {@code DeviceArrived} event so it can
     * automatically reopen the stream and transition to {@link #STOPPED}.
     *
     * <p>While the engine is in this state, the rest of the application
     * remains fully responsive — only audio I/O is suspended.</p>
     */
    DEVICE_LOST
}
