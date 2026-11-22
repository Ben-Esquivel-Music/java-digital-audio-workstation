package com.benesquivelmusic.daw.app.ui;

/**
 * High-level audio-engine lifecycle state surfaced to the UI.
 *
 * <p>Distinct from {@link com.benesquivelmusic.daw.core.transport.TransportState}
 * (which is about play/record/stop) and from the audio stream's open/closed
 * flag — this captures the engine's overall availability so the transport
 * status can show the loss while the audio device is gone, and so tests can
 * deterministically assert which lifecycle state the controller is in after
 * a disconnect.</p>
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
     * crash, JACK server shutdown), stopped responding while its stream ran
     * (story 338: the engine's heartbeat watchdog saw the device's heartbeat
     * stop for its window), or asked to be reset and the stream that was
     * open could not be reopened (story 338, ruling E113: often an unplug
     * the driver reports as a reset); {@link EngineStateChange#cause()}
     * tells the three apart. The render thread is halted, an
     * in-flight take is rescued on disk by the record coordinator (story
     * 327 — this state reaches it through {@code engineStateEvents()}, as
     * an {@link EngineStateChange} carrying the lost device), and the
     * controller is waiting for a matching {@code DeviceArrived} event, or
     * for the person's Reconnect, so it can reopen the stream, park it and
     * only then transition to {@link #STOPPED}. Any other stream opening ends
     * this state too (story 327): a loss is over once a stream is open again.
     *
     * <p>Publisher contract (story 327): a publisher of this state closes
     * the stream first ({@code AudioEngine.stopAudioOutput()}, then
     * {@code stop()}), because the record coordinator's stop tail
     * ({@code TransportController.finishDeviceLossStop}) treats any stream
     * it finds open on the loss turn as a post-loss reopen and leaves it
     * alone.</p>
     *
     * <p>While the engine is in this state, the rest of the application
     * remains fully responsive — only audio I/O is suspended.</p>
     */
    DEVICE_LOST,

    /**
     * The engine is being reconfigured: a Settings apply or Restart Audio
     * Engine (story 338: published once the record guard let the apply go
     * ahead, before the stream is stopped), or the driver asked the host to
     * drop the current stream and reopen with a renegotiated format (story
     * 218 — typically the user just changed buffer size, sample rate, or
     * clock source from the vendor's native control panel). The stream is
     * stopped and closed, the format and provision are applied, and a
     * stream that was open is reopened.
     *
     * <p>This is a transient state. A Settings apply or Restart Audio Engine
     * ends in {@link #RUNNING} when a stream that was open is open again,
     * and in {@link #STOPPED} otherwise: when none was open, when the stream
     * could not be started again, or when the apply was refused or failed,
     * each of the last two with a notification. A reopen the driver asked
     * for ends the same way, except that a stream that was open and could
     * not be reopened ends in {@link #DEVICE_LOST} with the cause
     * {@link EngineStateChange.LossCause#REOPEN_AFTER_RESET_FAILED}: a
     * closed stream is never watched again, so only a loss gives the person
     * Reconnect and seals a take honestly (ruling E113).</p>
     */
    RECONFIGURING
}
