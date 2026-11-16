package com.benesquivelmusic.daw.core.audio.health;

/**
 * What kind of render thread owns an {@link AudioHealthLane} (story 337,
 * rulings E5 and E18). Off the real-time path only.
 */
public enum LaneRole {

    /**
     * The engine's render drive — the one thread that calls
     * {@code AudioEngine.processBlock} and hands the block to the backend. Its
     * lane carries the render-drive heartbeat.
     */
    RENDER_DRIVE,

    /** A worker thread of the multi-core render pool. */
    WORKER
}
