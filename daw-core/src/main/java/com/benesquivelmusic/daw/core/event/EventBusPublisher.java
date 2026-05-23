package com.benesquivelmusic.daw.core.event;

import com.benesquivelmusic.daw.sdk.event.DawEvent;
import com.benesquivelmusic.daw.sdk.event.EventBus;

import java.util.Objects;

/**
 * Story 283 — process-wide publisher seam for the typed
 * {@link EventBus} so that {@code UndoableAction} classes (and any
 * other model-mutating callers) can publish {@link DawEvent}s without
 * each one taking a constructor dependency on the bus.
 *
 * <p>The application wires a single {@link EventBus} instance at
 * startup via {@link #setDefault(EventBus)}; tests that don't care
 * about events simply leave the bus unset and every {@link #publish}
 * call becomes a no-op. Producers always call
 * {@link #publish(DawEvent)} — they never thread-marshal; the
 * per-subscription {@code DispatchMode} on each
 * {@link EventBus#on(Class, com.benesquivelmusic.daw.sdk.event.DispatchMode,
 * java.util.function.Consumer)} call is the consumer's choice.</p>
 *
 * <p>This class is the <em>only</em> publisher facade in the engine —
 * action classes call {@link #publish} directly, not a typed wrapper
 * per event family. The bus reference is read once at publish time
 * (volatile) so a hot-swap via {@link #setDefault(EventBus)} from a
 * test never tears.</p>
 */
public final class EventBusPublisher {

    private static volatile EventBus bus;

    private EventBusPublisher() {
        // Utility class — no instances.
    }

    /**
     * Installs the application-wide {@link EventBus}. Idempotent.
     * Passing {@code null} clears the default so subsequent
     * {@link #publish} calls become no-ops (useful for test
     * teardown and on app shutdown).
     *
     * @param newDefault the bus to use, or {@code null} to clear
     */
    public static void setDefault(EventBus newDefault) {
        bus = newDefault;
    }

    /**
     * Returns the current default bus, or {@code null} when none has
     * been installed. Subscribers should capture the returned instance
     * in a {@code final} field at construction time (per the "Capture
     * swappable singleton reference once" memory) — never re-read on
     * every event.
     *
     * @return the current default bus, or {@code null}
     */
    public static EventBus getDefault() {
        return bus;
    }

    /**
     * Publishes the given event to the current default bus if one is
     * installed; otherwise the call is a no-op so production tests of
     * publishers that don't need event delivery (e.g. action unit
     * tests that just verify model state) stay clean.
     *
     * <p>The bus reference is read once into a local so a concurrent
     * {@link #setDefault(EventBus)} call cannot cause a publish to a
     * partially-installed bus.</p>
     *
     * @param event the event to publish; must not be {@code null}
     * @throws NullPointerException if {@code event} is {@code null}
     */
    public static void publish(DawEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        EventBus current = bus;
        if (current != null) {
            current.publish(event);
        }
    }
}
