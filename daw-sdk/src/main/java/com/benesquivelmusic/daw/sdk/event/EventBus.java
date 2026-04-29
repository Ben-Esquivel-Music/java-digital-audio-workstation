package com.benesquivelmusic.daw.sdk.event;

import java.util.concurrent.Flow;
import java.util.function.Consumer;

/**
 * Central, typed publish/subscribe channel for {@link DawEvent}s.
 *
 * <p>The bus replaces the per-emitter ad-hoc listener lists used in
 * earlier iterations of the engine. Emitters call {@link #publish}
 * exclusively; consumers either subscribe to a typed
 * {@link Flow.Publisher} via {@link #subscribe(Class)} or attach a
 * convenience callback via {@link #on(Class, Consumer)}.</p>
 *
 * <p>The implementation is required to provide:</p>
 * <ul>
 *   <li><strong>Bounded buffering</strong> per subscriber (configurable;
 *       default 256) so a slow consumer cannot stall the publisher.</li>
 *   <li><strong>Per-type overflow strategy</strong> via
 *       {@link OverflowStrategy} — {@link OverflowStrategy#DROP_OLDEST}
 *       for high-rate events, {@link OverflowStrategy#BLOCK} for
 *       critical events that must not be lost.</li>
 *   <li><strong>Per-subscription dispatch mode</strong> via
 *       {@link DispatchMode} — caller thread, UI thread, or virtual
 *       thread.</li>
 *   <li><strong>Instrumentation</strong> via {@link #metrics()} —
 *       throughput counters by event type and detection of slow
 *       subscribers.</li>
 * </ul>
 *
 * <h2>Idiomatic subscriber pattern</h2>
 * <pre>{@code
 * bus.on(TransportEvent.Started.class, DispatchMode.ON_UI_THREAD,
 *        ev -> updatePlayButton(ev));
 * }</pre>
 *
 * <p>Implementations of this interface live in
 * {@code com.benesquivelmusic.daw.core.event}; this SDK type is the
 * stable contract that plugins, the UI layer, and tests depend on.</p>
 */
public interface EventBus {

    /** Default per-subscription bounded-buffer capacity. */
    int DEFAULT_BUFFER_CAPACITY = 256;

    /**
     * Publishes the given event to every subscriber whose declared
     * type is assignable from the event's runtime type. Behaviour when
     * a subscriber's buffer is full is determined by the
     * {@link OverflowStrategy} configured for the event's type (see
     * implementation-specific configuration).
     *
     * @param event the event to publish; must not be {@code null}
     */
    void publish(DawEvent event);

    /**
     * Returns a {@link Flow.Publisher} that emits every event whose
     * runtime type is assignable to {@code type}. The publisher is
     * filtered: subscribers receive only events of the requested type
     * (or its subtypes).
     *
     * <p>Each subscription gets its own bounded buffer; subscribers
     * receive events on whichever thread the underlying
     * {@code SubmissionPublisher} delivers on. Use
     * {@link #on(Class, DispatchMode, Consumer)} for explicit
     * dispatch-mode control.</p>
     *
     * @param type the event type to filter by; not {@code null}
     * @param <E>  the event type
     * @return a per-call publisher emitting events of the requested type
     */
    <E extends DawEvent> Flow.Publisher<E> subscribe(Class<E> type);

    /**
     * Convenience subscription that invokes {@code handler} on every
     * matching event using {@link DispatchMode#ON_CALLER_THREAD}.
     *
     * @param type    the event type to filter by
     * @param handler the callback to invoke for each matching event
     * @param <E>     the event type
     * @return a handle that, when closed, cancels the subscription
     */
    default <E extends DawEvent> Subscription on(Class<E> type, Consumer<? super E> handler) {
        return on(type, DispatchMode.ON_CALLER_THREAD, handler);
    }

    /**
     * Convenience subscription that invokes {@code handler} on every
     * matching event using the given {@link DispatchMode}.
     *
     * @param type    the event type to filter by
     * @param mode    on which thread the handler should run
     * @param handler the callback to invoke for each matching event
     * @param <E>     the event type
     * @return a handle that, when closed, cancels the subscription
     */
    <E extends DawEvent> Subscription on(Class<E> type,
                                         DispatchMode mode,
                                         Consumer<? super E> handler);

    /**
     * Returns the read-only instrumentation view of this bus.
     */
    EventBusMetrics metrics();

    /**
     * Closes the bus, cancelling every active subscription and
     * releasing any backing executors. After {@code close()}, calls to
     * {@link #publish} are silently dropped.
     */
    void close();

    /**
     * Cancellable handle returned by the {@code on(...)} convenience
     * subscriptions. Closing the handle is idempotent.
     */
    interface Subscription extends AutoCloseable {
        @Override void close();
    }
}
