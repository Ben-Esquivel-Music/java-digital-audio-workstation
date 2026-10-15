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
     * crash, JACK server shutdown). The render thread is halted, an
     * in-flight take is rescued on disk by the record coordinator (story
     * 327 — this state reaches it through {@code engineStateEvents()}, as
     * an {@link EngineStateChange} carrying the lost device), and the
     * controller is waiting for a matching {@code DeviceArrived} event so
     * it can automatically reopen the stream, park it and only then
     * transition to {@link #STOPPED}. Any other stream opening ends this
     * state too (story 327): a loss is over once a stream is open again.
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
     * The driver asked the host to drop the current stream and reopen
     * with a renegotiated format (story 218 — typically the user just
     * changed buffer size, sample rate, or clock source from the vendor's
     * native control panel). Transport is paused, the render queue is
     * drained, the stream is closed, the backend's capabilities are
     * re-queried, and the stream is being reopened with the proposed
     * format. The transport bar shows "Reconfiguring audio engine…"
     * during this window.
     *
     * <p>This is a transient state: the controller transitions back to
     * {@link #STOPPED} (the user re-arms transport manually) once the
     * reopen succeeds, or back to {@link #STOPPED} with a notification
     * if the reopen fails.</p>
     */
    RECONFIGURING
}
