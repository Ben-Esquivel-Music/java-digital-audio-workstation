package com.benesquivelmusic.daw.sdk.audio;

import com.benesquivelmusic.daw.sdk.event.DawEvent;

import java.util.Objects;

/**
 * Published when the engine's backend-open ladder falls past a rung —
 * the requested backend (or an intermediate fallback) failed to open and a
 * different backend ended up carrying the stream (story 316).
 *
 * <p>The story's honesty contract: requested &ne; active must always be a
 * <em>visible</em> fact, never a silent substitution. The engine publishes
 * one {@code BackendFallbackEvent} per <strong>failed</strong> ladder hop on
 * the {@code EventBus} (from the non-real-time caller of the open path), so
 * a UI surface can render each hop as a notification — "ASIO failed to open;
 * audio is running through Java Sound" — the moment it happens.</p>
 *
 * <p>Semantics of the components:</p>
 * <ul>
 *   <li>{@code requestedBackend} / {@code requestedDevice} — what the user's
 *       configuration asked for (the ladder's first rung).</li>
 *   <li>{@code activeBackend} / {@code activeDevice} — the rung that
 *       ultimately opened, or the literal {@code "none"} when every rung
 *       failed and no stream is open at all.</li>
 *   <li>{@code cause} — the failed rung's exception message, naming why
 *       that hop was taken.</li>
 * </ul>
 *
 * <p>Like {@link XrunEvent}, this event is not tied to a wall-clock instant
 * captured at construction; {@link #timestamp()} inherits {@link
 * DawEvent}'s {@code Instant.EPOCH} default and consumers treat the event
 * as "now" on arrival.</p>
 *
 * @param requestedBackend name of the backend the configuration requested;
 *                         must not be null
 * @param requestedDevice  name of the device the configuration requested
 *                         (the first rung's device); must not be null
 * @param activeBackend    name of the backend that ultimately opened, or
 *                         {@code "none"} when every rung failed; must not
 *                         be null
 * @param activeDevice     name of the device that ultimately opened, or
 *                         {@code "none"} when every rung failed; must not
 *                         be null
 * @param cause            the failed rung's exception message; must not be
 *                         null
 */
public record BackendFallbackEvent(
        String requestedBackend,
        String requestedDevice,
        String activeBackend,
        String activeDevice,
        String cause) implements DawEvent {

    public BackendFallbackEvent {
        Objects.requireNonNull(requestedBackend, "requestedBackend must not be null");
        Objects.requireNonNull(requestedDevice, "requestedDevice must not be null");
        Objects.requireNonNull(activeBackend, "activeBackend must not be null");
        Objects.requireNonNull(activeDevice, "activeDevice must not be null");
        Objects.requireNonNull(cause, "cause must not be null");
    }
}
