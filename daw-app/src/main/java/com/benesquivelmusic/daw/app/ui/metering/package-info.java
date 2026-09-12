/**
 * The FX-pulse drain of the engine's metering tap bus (story 318; Audio
 * Engine Wiring Design Book &sect;4.3, &sect;5.3, &sect;6.2&ndash;6.3).
 *
 * <p>{@code daw-core}'s {@link com.benesquivelmusic.daw.core.metering.MeteringTapBus}
 * publishes one preallocated, latest-wins
 * {@link com.benesquivelmusic.daw.core.metering.LevelTapSlot} per tap point
 * from the render thread. This package is the consumer side: a single
 * {@link com.benesquivelmusic.daw.app.ui.metering.MeterFeed} registers as an
 * {@link com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher#addPulseParticipant(Runnable)
 * FxDispatcher pulse participant} and, once per frame, reads the slot of
 * every <em>visible</em> subscription into a reusable
 * {@link com.benesquivelmusic.daw.core.metering.MeterFrame} and hands it to
 * the surface's {@link com.benesquivelmusic.daw.app.ui.metering.MeterSink}.
 * A hidden meter costs zero (no read, no sink call); a meter whose frames stop
 * receives one silent frame ("honest idle") and then nothing until they
 * resume; a project rebind delivers one silent frame and re-attaches under
 * the new epoch.</p>
 *
 * <p>Coalescing identity is
 * {@link com.benesquivelmusic.daw.app.ui.metering.MeterKey} — one key per tap
 * point per surface (the one-key-per-fact rule). Everything here runs on the
 * JavaFX Application Thread; the only cross-thread edge is the seqlock read
 * inside the engine token, which is the tap bus's contract, not this
 * package's.</p>
 */
package com.benesquivelmusic.daw.app.ui.metering;
