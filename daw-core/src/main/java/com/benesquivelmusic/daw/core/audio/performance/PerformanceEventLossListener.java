package com.benesquivelmusic.daw.core.audio.performance;

import com.benesquivelmusic.daw.sdk.audio.performance.TrackPerformanceEvent;

/**
 * A subscriber of {@link TrackCpuBudgetEnforcer#performanceEvents()} that is
 * told of each event it will never receive: the enforcer offers its events
 * and never waits, so an event that this subscriber's full buffer cannot hold
 * is dropped for it. It is also told how many events the enforcer offered
 * before that one, a count the enforcer keeps on the offering thread, so it
 * does not depend on what the subscriber has delivered by then. For a
 * subscriber subscribed before the enforcer's first offer, each of those
 * events was either queued for it or reported to it as dropped.
 *
 * <p>Called on the thread that offered the event, the render thread, while
 * the enforcer's publisher holds its lock: an implementation only reads the
 * event and reads and stores its own fields. It takes no lock, allocates
 * nothing, never blocks and calls out to nothing.</p>
 */
public interface PerformanceEventLossListener {

    /**
     * The enforcer's publisher dropped {@code event} for this subscriber.
     *
     * @param event         the event this subscriber will not receive
     * @param offeredBefore how many events the enforcer offered before {@code event}
     */
    void eventDropped(TrackPerformanceEvent event, long offeredBefore);
}
