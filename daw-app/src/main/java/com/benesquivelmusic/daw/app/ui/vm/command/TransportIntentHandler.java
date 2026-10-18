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

    /**
     * Toggles loop-record on or off (story 328, book §5.6): whether a take
     * recorded over an enabled loop stacks each lap as a take lane. The
     * production controller refuses the toggle while a take is being
     * prepared, counting in or recording, and says so on the status line.
     */
    void toggleLoopRecord();

    /**
     * Sets the punch-in point at the playhead (story 328, book §5.6). The
     * point just set wins; if that leaves less than the minimum punch length
     * before the existing punch-out, the punch-out moves to keep it. With no
     * region installed, an ENABLED region of the minimum length is created.
     */
    void setPunchIn();

    /**
     * Sets the punch-out point at the playhead (story 328, book §5.6). The
     * point just set wins; if that leaves less than the minimum punch length
     * after the existing punch-in, the punch-in moves to keep it (never a
     * silent swap). With no region installed, an ENABLED region of the
     * minimum length ending here is created.
     */
    void setPunchOut();

    /**
     * Flips the enabled flag of the existing punch region (story 328, book
     * §5.6). With no region installed nothing changes — the production
     * controller reports that on the status line.
     */
    void togglePunch();
}
