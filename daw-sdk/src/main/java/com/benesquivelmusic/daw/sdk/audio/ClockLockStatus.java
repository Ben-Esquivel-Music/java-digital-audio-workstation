package com.benesquivelmusic.daw.sdk.audio;

import java.util.List;
import java.util.Objects;

/**
 * The current external-clock lock state of an open stream, as the backend
 * that measures it publishes it ({@link AudioBackend#clockLockStatus()}): a
 * level, read whenever a consumer looks, which also carries its
 * measurement's newest changes ({@link #transitions()}), so a consumer that
 * looks late still learns of an unlock and a relock that came and went
 * between its looks. A first kept sequence more than one past the last one a
 * consumer applied means that more changes came than the status keeps.
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
 *                      answer of the run that unlocked, but no later than
 *                      the start of its measurement when no good answer of
 *                      that measurement came before it), equal to the newest
 *                      transition's when there is one; {@code 0} for the
 *                      other locks
 * @param transitions   the measurement's newest changes of lock, oldest
 *                      first, their sequences contiguous; a first
 *                      measurement is none. Empty unless the lock is
 *                      {@link Lock#LOCKED} or {@link Lock#UNLOCKED}, and
 *                      otherwise ending on that lock. Copied; must not be
 *                      null
 */
public record ClockLockStatus(DeviceId device, int sourceId, long serial, Lock lock, long evidenceNanos,
                              List<Transition> transitions) {

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

    /**
     * One change of a measurement's lock.
     *
     * @param sequence      the change's place in its measurement, from
     *                      {@code 1}: odd for an unlock, even for a relock,
     *                      since a measurement's first change is always an
     *                      unlock
     * @param locked        whether the change is a relock
     * @param evidenceNanos the {@link System#nanoTime()} of the change's
     *                      evidence, as for
     *                      {@link ClockLockStatus#evidenceNanos()}
     */
    public record Transition(long sequence, boolean locked, long evidenceNanos) {

        public Transition {
            if (sequence < 1L) {
                throw new IllegalArgumentException("sequence " + sequence + " is below 1");
            }
            if (locked != (sequence % 2L == 0L)) {
                throw new IllegalArgumentException("sequence " + sequence + " is not "
                        + (locked ? "a relock" : "an unlock"));
            }
        }
    }

    public ClockLockStatus {
        Objects.requireNonNull(device, "device must not be null");
        Objects.requireNonNull(lock, "lock must not be null");
        if (sourceId < -1 || (sourceId < 0 && lock != Lock.CHECKING)) {
            throw new IllegalArgumentException("sourceId " + sourceId + " is not valid for " + lock);
        }
        transitions = List.copyOf(Objects.requireNonNull(transitions, "transitions must not be null"));
        for (int i = 1; i < transitions.size(); i++) {
            if (transitions.get(i).sequence() != transitions.get(i - 1).sequence() + 1L) {
                throw new IllegalArgumentException("transition sequences are not contiguous: " + transitions);
            }
        }
        if (!transitions.isEmpty()) {
            Transition newest = transitions.getLast();
            if (lock != Lock.LOCKED && lock != Lock.UNLOCKED) {
                throw new IllegalArgumentException(lock + " carries no transitions: " + transitions);
            }
            if (newest.locked() != (lock == Lock.LOCKED)) {
                throw new IllegalArgumentException("the newest transition " + newest + " is not " + lock);
            }
            if (evidenceNanos != newest.evidenceNanos()) {
                throw new IllegalArgumentException("evidenceNanos " + evidenceNanos
                        + " is not the newest transition's: " + newest);
            }
        }
    }

    /**
     * A status that carries no transitions.
     *
     * @param device        see {@link #device()}
     * @param sourceId      see {@link #sourceId()}
     * @param serial        see {@link #serial()}
     * @param lock          see {@link #lock()}
     * @param evidenceNanos see {@link #evidenceNanos()}
     */
    public ClockLockStatus(DeviceId device, int sourceId, long serial, Lock lock, long evidenceNanos) {
        this(device, sourceId, serial, lock, evidenceNanos, List.of());
    }

    /**
     * How many changes the measurement has had, those no longer kept
     * included: the newest transition's sequence, since the oldest are the
     * ones dropped.
     *
     * @return the newest sequence, or {@code 0} when there is none
     */
    public long lastSequence() {
        return transitions.isEmpty() ? 0L : transitions.getLast().sequence();
    }
}
