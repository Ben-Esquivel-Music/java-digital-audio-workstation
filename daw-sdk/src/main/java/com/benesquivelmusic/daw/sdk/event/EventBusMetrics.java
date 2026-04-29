package com.benesquivelmusic.daw.sdk.event;

import java.util.Map;

/**
 * Read-only instrumentation view of an {@link EventBus} for debug
 * panels, telemetry pipelines, and tests.
 *
 * <p>The bus implementation maintains per-event-type throughput
 * counters and per-subscription handler execution time averages.
 * Slow subscribers — those whose mean handler execution time exceeds
 * {@value #SLOW_SUBSCRIBER_THRESHOLD_NANOS} nanoseconds (1&nbsp;ms) —
 * are flagged in {@link #slowSubscribers()} so they can be surfaced
 * in a debug overlay or logged at startup.</p>
 *
 * <p><strong>Note:</strong> the "slow subscriber" metric measures only
 * handler execution time (the duration of the user callback), not
 * end-to-end latency including queueing or executor scheduling
 * delays.</p>
 *
 * <p>All snapshots returned by methods on this interface are
 * immutable point-in-time copies; the bus itself continues to
 * accumulate counters in the background.</p>
 */
public interface EventBusMetrics {

    /** Mean handler execution time threshold (in nanoseconds) above which a subscriber is considered slow. */
    long SLOW_SUBSCRIBER_THRESHOLD_NANOS = 1_000_000L;

    /**
     * Returns a snapshot of the number of events published per event
     * type, keyed by the event class name (e.g. {@code TransportEvent.Started}).
     */
    Map<String, Long> publishedByType();

    /**
     * Returns a snapshot of the number of events that were dropped due
     * to {@link OverflowStrategy#DROP_OLDEST}, keyed by the event class
     * name.
     */
    Map<String, Long> droppedByType();

    /**
     * Returns identifiers of subscriptions whose mean handler execution
     * time has exceeded {@link #SLOW_SUBSCRIBER_THRESHOLD_NANOS}. Each
     * entry is human-readable text suitable for display in a debug view.
     */
    Iterable<String> slowSubscribers();
}
