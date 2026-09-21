package com.benesquivelmusic.daw.core.dsp;

/** A processor whose costly parameter state is prepared outside the render thread. */
public interface PreparedParameterProcessor {

    /** Called before publishing the processor into the live graph. */
    void enableRealtimeParameterPreparation();

    /** Publishes completed state at a block boundary without allocating or waiting. */
    void applyPreparedParameters();

    /** Waits for the latest state before a non-real-time offline render begins. */
    void awaitParameterPreparation();

    /** Cancels background preparation after the processor has left the live graph. */
    void closeParameterPreparation();
}
