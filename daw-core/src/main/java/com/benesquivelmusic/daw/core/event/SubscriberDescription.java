package com.benesquivelmusic.daw.core.event;

import java.util.Objects;

/**
 * Which bus consumer failed, as {@link DefaultEventBus} describes it to its
 * {@link SubscriberFailureSink} (story 337, rulings E36–E38).
 *
 * <p>{@code eventTypeName} names the kind of update the consumer failed to
 * handle, for the user's copy; {@code subscriberId} identifies the consumer —
 * its subscription and its class — for the log record only. Neither is part of
 * a failure fingerprint: the funnel fingerprints the throwable (story 337,
 * ruling E36), so one consumer failing on many event types is still one
 * offender.</p>
 *
 * @param eventTypeName the simple name of the event type being delivered
 * @param subscriberId  the consumer's description: its subscription id and,
 *                      where the bus knows it, the consumer's class name
 * @param path          the route the event took to the consumer
 */
public record SubscriberDescription(String eventTypeName, String subscriberId, DeliveryPath path) {

    public SubscriberDescription {
        Objects.requireNonNull(eventTypeName, "eventTypeName must not be null");
        Objects.requireNonNull(subscriberId, "subscriberId must not be null");
        Objects.requireNonNull(path, "path must not be null");
    }

    /**
     * Returns {@code subscriberId (PATH)} — the offender text a log record
     * carries.
     */
    @Override
    public String toString() {
        return subscriberId + " (" + path + ")";
    }
}
