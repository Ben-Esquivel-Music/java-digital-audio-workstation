package com.benesquivelmusic.daw.app.ui.metering;

import com.benesquivelmusic.daw.core.metering.MeterFrame;

/**
 * The FX-thread consumer of one {@code INSERT_IO} subscription — the insert's
 * input and output frames of the same rendered block, delivered together so
 * an editor footer's IN / OUT pair moves as one. Same contract as
 * {@link MeterSink}: FX thread, at most once per block plus one silent
 * delivery on idle, frames valid during the call only.
 */
@FunctionalInterface
public interface InsertIoSink {

    /**
     * Receives the insert's input and output frames.
     *
     * @param input  what the processor received; valid during this call only
     * @param output what the processor returned; valid during this call only
     */
    void accept(MeterFrame input, MeterFrame output);
}
