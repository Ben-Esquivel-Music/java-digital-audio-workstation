package com.benesquivelmusic.daw.sdk.audio;

import java.util.Objects;

/**
 * Event published by {@link AudioBackend#clockLockEvents()} whenever the
 * external-clock lock state changes.
 *
 * <p>Multi-channel interfaces expose the lock state of their external
 * clock input (word-clock, S/PDIF, ADAT, AES) so the host can show a
 * lock indicator and pause recording before sample slips reach disk.
 * In ASIO the state is queried via
 * {@code ASIOFuture(kAsioGetExternalClockLocked)} polled at 1 Hz from a
 * non-audio thread; the driver may also push an unlock signal as
 * {@code kAsioResyncRequest}. CoreAudio surfaces the same information
 * through {@code kAudioDevicePropertyClockSourceLocked}.</p>
 *
 * <p>{@code locked} is {@code true} when the device reports the
 * external clock as locked (or when running on the internal clock,
 * which is always considered locked). {@code sourceId} echoes the
 * {@link ClockSource#id()} of the source whose state is being
 * reported, so subscribers can ignore events for inactive sources.</p>
 *
 * @param device   the device whose clock-lock state changed; must not
 *                 be null
 * @param sourceId driver-defined id of the affected clock source
 *                 ({@code >= 0})
 * @param locked   {@code true} when the source is locked,
 *                 {@code false} when the driver reports unlock
 */
public record ClockLockEvent(DeviceId device, int sourceId, boolean locked) {

    public ClockLockEvent {
        Objects.requireNonNull(device, "device must not be null");
        if (sourceId < 0) {
            throw new IllegalArgumentException("sourceId must not be negative: " + sourceId);
        }
    }
}
