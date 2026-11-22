package com.benesquivelmusic.daw.sdk.audio;

import java.util.Objects;

/**
 * The current external-clock lock state of an open stream, as the backend
 * that measures it publishes it ({@link AudioBackend#clockLockStatus()}): a
 * level, read whenever a consumer looks, never an event that can be missed.
 *
 * <p>The {@code serial} names one measurement: it changes whenever the lock
 * state starts afresh — at every stream start and at every change of the
 * current clock source inside a stream — and a value never repeats within the
 * process. A consumer compares serials for equality only: an
 * {@link Lock#UNLOCKED} followed by a {@link Lock#LOCKED} of the same serial
 * is a relock; a {@link Lock#LOCKED} under a new serial is a first
 * measurement, never a relock.</p>
 *
 * @param device        the device the measurement runs on, as the driver
 *                      side resolved it (for ASIO, the driver's name, even
 *                      when the stream was asked for the default device);
 *                      must not be null
 * @param sourceId      the {@link ClockSource#id()} of the current source,
 *                      or {@code -1} while the source has not been read; at
 *                      least {@code 0} for every lock but
 *                      {@link Lock#CHECKING}
 * @param serial        the measurement's serial
 * @param lock          the lock state; must not be null
 * @param evidenceNanos the {@link System#nanoTime()} of the evidence behind
 *                      the last change to {@link Lock#LOCKED} or
 *                      {@link Lock#UNLOCKED} (for an unlock, the first bad
 *                      answer of the run that unlocked); {@code 0} for the
 *                      other locks
 */
public record ClockLockStatus(DeviceId device, int sourceId, long serial, Lock lock, long evidenceNanos) {

    /** What is known about the lock of the current source. */
    public enum Lock {
        /**
         * The source is known and nothing will measure its lock: an internal
         * source, a device with a single source, or a driver that gives no
         * sample position.
         */
        NOT_MEASURABLE,
        /** The source has not been read yet, or it is measured and no answer has decided its lock yet. */
        CHECKING,
        /** Locked: a good answer of this measurement, or a relock after an unlock of it. */
        LOCKED,
        /** Not locked: two consecutive bad answers of this measurement. */
        UNLOCKED
    }

    public ClockLockStatus {
        Objects.requireNonNull(device, "device must not be null");
        Objects.requireNonNull(lock, "lock must not be null");
        if (sourceId < -1 || (sourceId < 0 && lock != Lock.CHECKING)) {
            throw new IllegalArgumentException("sourceId " + sourceId + " is not valid for " + lock);
        }
    }
}
