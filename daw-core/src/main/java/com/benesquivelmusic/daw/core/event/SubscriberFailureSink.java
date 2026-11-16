package com.benesquivelmusic.daw.core.event;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Receives every failure of a {@link DefaultEventBus} consumer — a handler, a
 * {@link java.util.concurrent.Flow.Subscriber} or a publication observer that
 * threw (story 337, rulings E37 and E38).
 *
 * <p>The bus requires one at build time and has no default: a sink is
 * injected, never defaulted (failure-surfacing book §2.7). The application's
 * composition root passes a funnel-backed sink, which decides when a consumer
 * that keeps failing is surfaced; tests and headless tools pass
 * {@link #LOG_ONLY} explicitly. The bus isolates every consumer: a failure is
 * reported here and delivery to every other consumer — and later events to
 * the same one — continues.</p>
 *
 * <p>Called on the thread that ran the consumer: a subscription's worker, the
 * UI executor's thread, a virtual thread, or the publishing thread for a
 * publication observer. A sink that throws is contained by the bus.</p>
 */
@FunctionalInterface
public interface SubscriberFailureSink {

    /**
     * The explicit log-only sink for tests and headless tools: one
     * {@link Level#WARNING} record per failure, with the stack, on
     * {@link DefaultEventBus}'s logger.
     */
    SubscriberFailureSink LOG_ONLY = SubscriberFailureSink::logOnly;

    /**
     * Reports one consumer failure.
     *
     * @param subscriber which consumer failed, and on which delivery path
     * @param failure    what it threw
     */
    void subscriberFailed(SubscriberDescription subscriber, Throwable failure);

    private static void logOnly(SubscriberDescription subscriber, Throwable failure) {
        Logger.getLogger(DefaultEventBus.class.getName())
                .log(Level.WARNING, "Subscriber " + subscriber + " threw", failure);
    }
}
