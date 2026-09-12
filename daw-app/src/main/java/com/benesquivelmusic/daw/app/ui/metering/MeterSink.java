package com.benesquivelmusic.daw.app.ui.metering;

import com.benesquivelmusic.daw.core.metering.MeterFrame;

/**
 * The FX-thread consumer of one level-lane subscription. Called by the
 * {@link MeterFeed} pulse participant, on the JavaFX Application Thread, at
 * most once per rendered block and once more with a silent frame when
 * frames stop arriving ("honest idle").
 *
 * <p>The frame is the feed's reusable per-subscription {@link MeterFrame}:
 * it is valid only for the duration of the call and is overwritten by the
 * next read. A sink that needs to keep values copies the primitives (or
 * builds a {@link MeterFrame#toLevelData() LevelData}) — it never retains the
 * frame itself.</p>
 */
@FunctionalInterface
public interface MeterSink {

    /**
     * Receives the latest coherent frame of the subscribed tap point.
     *
     * @param frame the feed's reusable frame; valid during this call only
     */
    void accept(MeterFrame frame);
}
