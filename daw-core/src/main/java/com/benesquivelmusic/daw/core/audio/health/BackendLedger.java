package com.benesquivelmusic.daw.core.audio.health;

import com.benesquivelmusic.daw.sdk.audio.BackendThreadCounters;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Supplier;

/**
 * An immutable snapshot of the backend counters an {@link AudioHealthRecord}
 * has seen (story 337, ruling E4): everything folded from streams already
 * closed, plus — while a stream is open — the live backend's counter source
 * and the baseline it reported when it was attached.
 *
 * <p>A backend's counters only ever grow for the life of the backend
 * instance, and the same instance may be reopened, so the record counts only
 * what grew since attach: {@code live − baseline}, clamped at zero. That is
 * what keeps a reopen of the same backend from counting its history twice.
 * The record swaps the whole ledger with one volatile write.</p>
 *
 * @param folded   the counts of every stream already closed
 * @param live     the open stream's backend counter source, when one is open
 * @param baseline what {@code live} reported when it was attached
 */
record BackendLedger(BackendThreadCounters folded,
                     Optional<Supplier<BackendThreadCounters>> live,
                     BackendThreadCounters baseline) {

    static final BackendLedger NONE = new BackendLedger(
            BackendThreadCounters.NONE, Optional.empty(), BackendThreadCounters.NONE);

    BackendLedger {
        Objects.requireNonNull(folded, "folded must not be null");
        Objects.requireNonNull(live, "live must not be null");
        Objects.requireNonNull(baseline, "baseline must not be null");
    }

    /** The live source's growth since attach, or {@code NONE} without a live source. */
    BackendThreadCounters liveDelta() {
        if (live.isEmpty()) {
            return BackendThreadCounters.NONE;
        }
        BackendThreadCounters now = Objects.requireNonNull(live.get().get(),
                "a backend's threadCounters() must not return null");
        OptionalLong periods = now.deviceCallbackPeriods().isPresent()
                ? OptionalLong.of(Math.max(0L, now.deviceCallbackPeriods().getAsLong()
                        - baseline.deviceCallbackPeriods().orElse(0L)))
                : OptionalLong.empty();
        return new BackendThreadCounters(
                Math.max(0L, now.callbackFaults() - baseline.callbackFaults()),
                Math.max(0L, now.inputBlocksLost() - baseline.inputBlocksLost()),
                Math.max(0L, now.inputHalts() - baseline.inputHalts()),
                Math.max(0L, now.deviceNotificationFaults() - baseline.deviceNotificationFaults()),
                periods,
                now.lastCallbackFault(),
                now.lastInputFault(),
                now.lastDeviceNotificationFault());
    }

    /** Folded plus the live delta; last faults prefer the live source. */
    BackendThreadCounters view() {
        return liveDelta().plus(folded);
    }

    /** This ledger with the live source's delta folded in and no live source. */
    BackendLedger detached() {
        return new BackendLedger(view(), Optional.empty(), BackendThreadCounters.NONE);
    }

    /**
     * This ledger with any previous live source folded in and {@code source}
     * attached at the baseline it reports now.
     */
    BackendLedger attached(Supplier<BackendThreadCounters> source) {
        BackendThreadCounters baselineNow = Objects.requireNonNull(source.get(),
                "a backend's threadCounters() must not return null");
        return new BackendLedger(view(), Optional.of(source), baselineNow);
    }
}
