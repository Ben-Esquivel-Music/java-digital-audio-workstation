package com.benesquivelmusic.daw.app.ui.marshal;

/**
 * Which unit of work of one {@link FxDispatcher#pulse()} failed (story 337,
 * rulings E32 and E33): the pulse guards each of them on its own, so a
 * failure is reported with the kind of item that threw.
 */
public enum PulseItemKind {

    /** A pulse participant ({@link FxDispatcher#addPulseParticipant(Runnable)}). */
    PARTICIPANT,

    /** A keyed, per-frame coalesced runnable ({@link FxDispatcher#onFx(Object, Runnable)}). */
    KEYED_WORK,

    /** The FX-thread consumer of a continuous channel, as the pulse drained it. */
    CHANNEL_DRAIN
}
