package com.benesquivelmusic.daw.app.ui.vm.command;

/**
 * The intent seam a {@link TransportCommand} flows into — the up-going half of
 * "state flows down, intent flows up" (Control Synchronization Design Book §2.2,
 * §3.4). A command never writes transport state directly; it asks the handler to
 * run the corresponding mutation path.
 *
 * <p>The production implementation ({@link CoreTransportIntentHandler}) wraps the
 * existing transport mutation path and runs the §5.1 cascade for each intent:
 * VALIDATE → MUTATE (which fires the core change signal, so {@code TransportVM}
 * republishes its properties) → ANNOUNCE (a typed {@code TransportEvent} on the
 * bus). Tests substitute a recording fake to assert that a control gesture issues
 * the right command without touching the engine.</p>
 */
public interface TransportIntentHandler {

    /** Begins playback from the current position (§5.2 "Start / Play"). */
    void start();

    /**
     * Pauses playback at the current position (§5.2; story 315 — the pause half
     * of Play-toggles-pause). Only meaningful while PLAYING or RECORDING; the
     * production handler's VALIDATE phase absorbs a stale intent.
     */
    void pause();

    /**
     * Toggles between playing and paused — the Play gesture, resolved
     * <em>here</em> rather than at the gesture layer (story 315 review;
     * §2.8 "one path").
     *
     * <p>The handler owns the transport, so it is the only participant that can
     * read the authoritative state; a binder deciding from the async
     * {@code TransportVM} mirror can be a frame stale and raise a Start that
     * VALIDATE then drops. Implementations start when not PLAYING and pause
     * when PLAYING; a RECORDING transport maps to start, which VALIDATE rejects
     * (Stop is the only way out of record).</p>
     */
    void togglePlayPause();

    /**
     * Starts playback with the configured pre-roll applied (Story 134; §5.2).
     * Always transitions the transport to PLAYING — with a pre-roll configured
     * it re-anchors and rewinds first; with none it is equivalent to
     * {@link #start()}.
     */
    void playWithPreRoll();

    /** Stops playback and returns the playhead to the anchor (§5.2 "Stop"). */
    void stop();

    /**
     * Skips the playhead back to the beginning (§5.2). An absolute seek to
     * beat zero: queued while the RT clock owns the transport, immediate
     * otherwise (story 315).
     */
    void skipBack();

    /**
     * Skips the playhead forward by four bars (§5.2). A RELATIVE seek — the
     * jump composes against {@code Transport.getSeekTargetInBeats()}, never the
     * committed position (story 315 review): while the RT clock owns the
     * transport a seek sits queued until the next block boundary, and only the
     * pending target lets successive skips accumulate instead of collapsing
     * onto the same base.
     */
    void skipForward();

    /** Toggles the record-arm / recording state (§5.2 "Record"). */
    void toggleRecord();

    /**
     * Sets the initial tempo in BPM (§5.2 "Set tempo").
     *
     * @param bpm the requested tempo in beats per minute
     */
    void setTempo(double bpm);

    /** Toggles loop playback on or off (§5.2 "Toggle loop"). */
    void toggleLoop();
}
