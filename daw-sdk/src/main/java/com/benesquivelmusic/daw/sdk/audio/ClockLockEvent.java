package com.benesquivelmusic.daw.sdk.audio;

import java.util.Objects;

/**
 * Event published by {@link AudioBackend#clockLockEvents()} whenever the
 * external-clock lock state changes, and when a stream's lock is first
 * measured.
 *
 * <p>Multi-channel interfaces expose the lock state of their external
 * clock input (word-clock, S/PDIF, ADAT, AES) so the host can show a
 * lock indicator and mark a recording where its clock was suspect. ASIO
 * has no lock query in its public API, so {@link AsioBackend} derives the
 * state from a once-a-second {@code ASIOGetSamplePosition} poll plus the
 * driver's {@code kAsioResyncRequest} messages, while the current source is
 * external; see {@link AudioBackend#clockLockEvents()} for the rule. It is
 * the only backend that publishes these events; every other backend publishes
 * none and reports no {@link AudioBackend#clockLockStatus()}. The same state
 * is available as a level from {@link AudioBackend#clockLockStatus()}, which
 * a consumer that must not miss a change reads instead.</p>
 *
 * <p>{@code locked} is {@code true} when the device reports the
 * external clock as locked. From {@link AsioBackend} a {@code true} event is
 * either the relock after an unlock of the same stream, or the first good
 * answer of a stream or of a newly selected source, which ends its "not yet
 * measured" state: a subscriber tells them apart by whether it saw an unlock
 * of that stream. An internal clock publishes nothing. {@code sourceId}
 * echoes the {@link ClockSource#id()} of the source whose state is being
 * reported, so subscribers can ignore events for inactive sources.</p>
 *
 * <p>{@code evidenceNanos} dates the evidence rather than the declaration:
 * for an unlock, the {@link System#nanoTime()} of the first bad answer of the
 * run that unlocked; for a relock or a first measurement, that of the answer
 * that declared it. A subscriber that marks a recording uses it to place the
 * mark where the clock became suspect, which can be seconds before the event
 * arrived.</p>
 *
 * @param device        the device whose clock-lock state changed; must not
 *                      be null
 * @param sourceId      driver-defined id of the affected clock source
 *                      ({@code >= 0})
 * @param locked        {@code true} when the source is locked,
 *                      {@code false} when the driver reports unlock
 * @param evidenceNanos the {@link System#nanoTime()} of the evidence behind
 *                      the change; {@code 0} when unknown
 */
public record ClockLockEvent(DeviceId device, int sourceId, boolean locked, long evidenceNanos) {

    public ClockLockEvent {
        Objects.requireNonNull(device, "device must not be null");
        if (sourceId < 0) {
            throw new IllegalArgumentException("sourceId must not be negative: " + sourceId);
        }
    }

    /**
     * An event whose evidence time is unknown ({@code evidenceNanos} = 0).
     *
     * @param device   the device whose clock-lock state changed; must not be null
     * @param sourceId driver-defined id of the affected clock source ({@code >= 0})
     * @param locked   {@code true} when the source is locked
     */
    public ClockLockEvent(DeviceId device, int sourceId, boolean locked) {
        this(device, sourceId, locked, 0L);
    }
}
