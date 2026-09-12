package com.benesquivelmusic.daw.app.ui.metering;

/**
 * A {@link MeterFeed} subscription token — the UI-side counterpart of the
 * engine's epoch-scoped {@code TapSubscription} (Audio Engine Wiring Design
 * Book §3.5, §6.2).
 *
 * <p>Unlike the engine token, a feed subscription expresses a <em>lasting
 * intent</em> ("show this tap point on this surface"): when a project rebind
 * moves the bus to a new epoch and disposes the engine token, the feed
 * delivers one silent frame and re-attaches under the new epoch on the next
 * pulse, so frames resume once the new binding renders. Only
 * {@link #dispose()} (the owning surface's teardown — dock hide, strip
 * rebuild, editor close) or {@link MeterFeed#dispose()} ends it.</p>
 *
 * <p>FX thread only.</p>
 */
public sealed interface MeterSubscription permits MeterFeed.Entry {

    /** The coalescing identity this subscription was registered under. */
    MeterKey key();

    /**
     * The binding epoch of the current engine token — advances after a rebind
     * once the feed has re-attached (test / diagnostic seam).
     */
    long epoch();

    /** {@code true} once {@link #dispose()} ran, the key was replaced, or the feed was disposed. */
    boolean isDisposed();

    /** Removes this subscription from the feed and disposes its engine token. Idempotent. */
    void dispose();
}
