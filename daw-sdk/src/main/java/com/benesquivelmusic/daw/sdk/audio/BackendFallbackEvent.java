package com.benesquivelmusic.daw.sdk.audio;

import com.benesquivelmusic.daw.sdk.event.DawEvent;

import java.util.Objects;

/**
 * Published when a backend the user asked for did not end up carrying the
 * stream — either it failed to open and the ladder fell past it, or the
 * application layer's availability / streaming gate refused it before the
 * ladder was even built (story 316).
 *
 * <p>The story's honesty contract: requested &ne; active must always be a
 * <em>visible</em> fact, never a silent substitution. The engine publishes
 * one {@code BackendFallbackEvent} per <strong>skipped</strong> backend on
 * the {@code EventBus} (from the non-real-time caller of the open path), so
 * a UI surface can render each one as a notification — "ASIO failed to open;
 * audio is running through Java Sound" — the moment it happens. There are
 * two sources, published in that chronological order:</p>
 * <ul>
 *   <li>a <strong>gate rejection</strong> — the app layer found the
 *       requested backend unavailable on this host, or available with no
 *       streaming path, or unknown to this build, so it never became a
 *       ladder rung and no open was ever attempted. The rejection rides
 *       into the engine as a {@code StreamingProvision} pending failed hop
 *       and is published from here like any other (story 316 review —
 *       without it, a fallback head that opened first try published
 *       nothing at all);</li>
 *   <li>a <strong>failed ladder hop</strong> — a rung the engine really
 *       tried: refused streaming support, an unrenderable negotiated
 *       format, or an {@code open()} the device rejected.</li>
 * </ul>
 *
 * <p>Semantics of the components:</p>
 * <ul>
 *   <li>{@code requestedBackend} / {@code requestedDevice} — what the user's
 *       configuration asked for, carried by the {@code StreamingProvision}
 *       itself. Deliberately NOT read off the ladder's first rung (story
 *       316 review): a gate-rejected request is absent from the ladder, so
 *       the first rung is already a fallback and pairing the user's
 *       requested backend name with that fallback's device would name an
 *       endpoint the user never chose.</li>
 *   <li>{@code activeBackend} / {@code activeDevice} — the rung whose
 *       stream actually STARTED, or the literal {@code "none"} when no
 *       stream is running: either every rung failed to open, or a rung
 *       opened but its render pump failed to start (story 316 review — an
 *       opened-but-never-started rung is not an active stream, so it is
 *       never named here).</li>
 *   <li>{@code cause} — why this backend was skipped: the failed rung's
 *       exception message, or the gate's rejection reason.</li>
 * </ul>
 *
 * <p>Like {@link XrunEvent}, this event is not tied to a wall-clock instant
 * captured at construction; {@link #timestamp()} inherits {@link
 * DawEvent}'s {@code Instant.EPOCH} default and consumers treat the event
 * as "now" on arrival.</p>
 *
 * @param requestedBackend name of the backend the configuration requested;
 *                         must not be null
 * @param requestedDevice  name of the device the configuration requested,
 *                         as carried by the provision — never the first
 *                         rung's, which belongs to a fallback whenever the
 *                         request was gate-rejected; must not be null
 * @param activeBackend    name of the backend whose stream actually started,
 *                         or {@code "none"} when none did — every rung
 *                         failed to open, or one opened and its render pump
 *                         failed to start; must not be null
 * @param activeDevice     name of the device whose stream actually started,
 *                         or {@code "none"} under the same two conditions;
 *                         must not be null
 * @param cause            why the backend was skipped — the failed rung's
 *                         exception message, or the app layer's gate
 *                         rejection reason; must not be null
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
