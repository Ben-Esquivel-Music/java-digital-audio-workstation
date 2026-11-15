package com.benesquivelmusic.daw.sdk.audio;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The failure counters a backend keeps on the threads it owns — a device
 * callback, an input drain, a capture loop, a device-notification upcall —
 * pulled by the engine through {@link AudioBackend#threadCounters()} (story
 * 337, ruling E4).
 *
 * <p>Each count lives inside the backend with the thread that writes it: a
 * count with one writer thread is a {@code volatile long} bumped with
 * {@code ++}, next to a {@code volatile Throwable} holding the last fault; a
 * count that several native threads share — device-notification failures —
 * is an {@code AtomicLong} incremented on the failure path only (story 337,
 * ruling E31). A plain store allocates nothing, so the writer can be a
 * real-time driver thread. A backend folds
 * the counts of each per-stream helper (an upcall invoker, an ASIO shim)
 * into its own totals when the helper is torn down, so every count only ever
 * grows for the life of the backend instance and a quick reconfigure after a
 * burst loses nothing (failure-surfacing book §2.1).</p>
 *
 * <p>This is a value, not a sink (book §2.7): the backend reports counts, it
 * never swallows a failure on the strength of a default. Building one
 * allocates, so a backend builds it on the CALLER's thread — never on the
 * thread whose faults it counts.</p>
 *
 * @param callbackFaults            faults the device-callback guard caught
 *                                  ({@code audio.callback}); {@code >= 0}
 * @param inputBlocksLost           captured input blocks lost on a backend
 *                                  input thread ({@code audio.input}); {@code >= 0}
 * @param inputHalts                backend input threads that ended on a fault
 *                                  they could not continue past (a broken line,
 *                                  a {@link VirtualMachineError}); {@code >= 0}
 * @param deviceNotificationFaults  device-notification upcalls whose handling
 *                                  failed ({@code audio.device}); {@code >= 0}
 * @param deviceCallbackPeriods     the device-callback heartbeat: periods the
 *                                  driver's callback was entered, monotonic
 *                                  (story 337, ruling E5); empty when the
 *                                  backend has no device callback thread of
 *                                  its own, so a watchdog uses the engine's
 *                                  render-drive heartbeat instead
 * @param lastCallbackFault         the newest fault behind {@code callbackFaults}
 * @param lastInputFault            the newest fault behind the two input counts
 * @param lastDeviceNotificationFault the newest fault behind
 *                                  {@code deviceNotificationFaults}
 */
public record BackendThreadCounters(long callbackFaults,
                                    long inputBlocksLost,
                                    long inputHalts,
                                    long deviceNotificationFaults,
                                    OptionalLong deviceCallbackPeriods,
                                    Optional<Throwable> lastCallbackFault,
                                    Optional<Throwable> lastInputFault,
                                    Optional<Throwable> lastDeviceNotificationFault) {

    /**
     * "No own threads, zero counts" — the truthful report of a backend that
     * runs no thread of its own, and the default of
     * {@link AudioBackend#threadCounters()}.
     */
    public static final BackendThreadCounters NONE = new BackendThreadCounters(
            0L, 0L, 0L, 0L, OptionalLong.empty(),
            Optional.empty(), Optional.empty(), Optional.empty());

    public BackendThreadCounters {
        Objects.requireNonNull(deviceCallbackPeriods, "deviceCallbackPeriods must not be null");
        Objects.requireNonNull(lastCallbackFault, "lastCallbackFault must not be null");
        Objects.requireNonNull(lastInputFault, "lastInputFault must not be null");
        Objects.requireNonNull(lastDeviceNotificationFault,
                "lastDeviceNotificationFault must not be null");
        requireNonNegative("callbackFaults", callbackFaults);
        requireNonNegative("inputBlocksLost", inputBlocksLost);
        requireNonNegative("inputHalts", inputHalts);
        requireNonNegative("deviceNotificationFaults", deviceNotificationFaults);
        if (deviceCallbackPeriods.isPresent()) {
            requireNonNegative("deviceCallbackPeriods", deviceCallbackPeriods.getAsLong());
        }
    }

    /**
     * Sums two reports — the shape an adapter uses to add its own drain's
     * counts to the backend it wraps.
     *
     * <p>Counts add. The device heartbeat is present when either side has
     * one (the sum of the present values). Each last fault is this report's
     * when present, otherwise {@code other}'s.</p>
     *
     * @param other the report to add; never {@code null}
     * @return the sum; never {@code null}
     */
    public BackendThreadCounters plus(BackendThreadCounters other) {
        Objects.requireNonNull(other, "other must not be null");
        OptionalLong periods;
        if (deviceCallbackPeriods.isPresent() && other.deviceCallbackPeriods.isPresent()) {
            periods = OptionalLong.of(deviceCallbackPeriods.getAsLong()
                    + other.deviceCallbackPeriods.getAsLong());
        } else if (deviceCallbackPeriods.isPresent()) {
            periods = deviceCallbackPeriods;
        } else {
            periods = other.deviceCallbackPeriods;
        }
        return new BackendThreadCounters(
                callbackFaults + other.callbackFaults,
                inputBlocksLost + other.inputBlocksLost,
                inputHalts + other.inputHalts,
                deviceNotificationFaults + other.deviceNotificationFaults,
                periods,
                lastCallbackFault.or(() -> other.lastCallbackFault),
                lastInputFault.or(() -> other.lastInputFault),
                lastDeviceNotificationFault.or(() -> other.lastDeviceNotificationFault));
    }

    private static void requireNonNegative(String name, long value) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must be >= 0: " + value);
        }
    }
}
