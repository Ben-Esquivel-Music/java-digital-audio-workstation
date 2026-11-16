package com.benesquivelmusic.daw.core.event;

/**
 * The route by which {@link DefaultEventBus} handed an event to the consumer
 * that failed (story 337, ruling E38). All three report to the bus's one
 * {@link SubscriberFailureSink}.
 */
public enum DeliveryPath {

    /**
     * A handler registered with {@code on(...)}, run by its subscription's
     * worker on the caller thread, the UI executor or a virtual thread.
     */
    HANDLER,

    /**
     * A {@link java.util.concurrent.Flow.Subscriber} attached through
     * {@code subscribe(...)}; its {@code onNext} threw.
     */
    FLOW_SUBSCRIBER,

    /**
     * A publication observer registered with {@code onPublication(...)}, run
     * inside {@code publish} on the publishing thread.
     */
    PUBLICATION_OBSERVER
}
