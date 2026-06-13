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

    /** Stops playback and returns the playhead to the anchor (§5.2 "Stop"). */
    void stop();

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
