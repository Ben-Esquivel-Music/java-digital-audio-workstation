package com.benesquivelmusic.daw.sdk.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Supplier;

/**
 * A fold-on-detach ledger of {@link BackendThreadCounters}: the counts of
 * every counter source already detached, plus the sources still live, each
 * counted from the baseline it was attached at (story 337, ruling E90).
 *
 * <p>A counter source lives for one stream — an ASIO buffer-switch shim, a
 * PortAudio upcall invoker, an open backend, an input device opened beside the
 * stream — so its counts would vanish when it is torn down. The ledger folds a
 * source's counts in when it is detached, so a report built from
 * {@link #view()} only ever grows and a quick reconfigure after a burst loses
 * nothing. A source the same owner attaches again replaces the owner's previous
 * one, which is folded first. Several owners may be live at once, each with its
 * own baseline (story 337, ruling E86).</p>
 *
 * <p>The ledger is an immutable value: every operation returns a new ledger,
 * and its holder swaps the whole value with one volatile write, so a reader
 * never sees a source counted twice or not at all. Owners are compared by
 * identity. Building a view calls each live source once and allocates, so it
 * runs on the caller's thread, never on a thread whose faults it counts.</p>
 *
 * @param folded the counts of every source already detached
 * @param live   the sources still attached, oldest first
 */
public record BackendCounterLedger(BackendThreadCounters folded, List<LiveSource> live) {

    /** No source attached and nothing folded. */
    public static final BackendCounterLedger EMPTY =
            new BackendCounterLedger(BackendThreadCounters.NONE, List.of());

    public BackendCounterLedger {
        Objects.requireNonNull(folded, "folded must not be null");
        Objects.requireNonNull(live, "live must not be null");
        live = List.copyOf(live);
    }

    /**
     * This ledger with {@code owner}'s previous live source, if any, folded in
     * and {@code source} attached at {@code baseline}.
     *
     * @param owner    the source's owner, compared by identity; never {@code null}
     * @param source   the counter source; never {@code null}
     * @param baseline what {@code source} had counted when it was attached; its
     *                 counts are not counted again
     * @return the new ledger; never {@code null}
     */
    public BackendCounterLedger attached(Object owner, Supplier<BackendThreadCounters> source,
                                         BackendThreadCounters baseline) {
        BackendCounterLedger without = detached(owner);
        List<LiveSource> sources = new ArrayList<>(without.live);
        sources.add(new LiveSource(owner, source, baseline));
        return new BackendCounterLedger(without.folded, sources);
    }

    /**
     * This ledger with {@code owner}'s live source folded in and dropped.
     * Idempotent: with no live source for {@code owner} it returns this
     * ledger, so a second detach folds nothing.
     *
     * @param owner the owner whose source is detached; never {@code null}
     * @return the new ledger, or this one when {@code owner} has no live source
     */
    public BackendCounterLedger detached(Object owner) {
        Objects.requireNonNull(owner, "owner must not be null");
        List<LiveSource> remaining = new ArrayList<>(live.size());
        BackendThreadCounters foldedNow = folded;
        boolean found = false;
        for (LiveSource source : live) {
            if (source.owner() == owner) {
                foldedNow = source.delta().plus(foldedNow);
                found = true;
            } else {
                remaining.add(source);
            }
        }
        return found ? new BackendCounterLedger(foldedNow, remaining) : this;
    }

    /**
     * Everything counted: the folded counts plus every live source's growth
     * since its attach. Counts and heartbeats add; each last fault is the
     * newest-attached live source's that has one, then the next newest's,
     * then the folded one ({@link BackendThreadCounters#plus}).
     *
     * @return the report; never {@code null}
     */
    public BackendThreadCounters view() {
        BackendThreadCounters total = folded;
        for (int index = 0; index < live.size(); index++) {
            total = live.get(index).delta().plus(total);
        }
        return total;
    }

    /**
     * One owner's live growth since its attach.
     *
     * @param owner the owner to look up, compared by identity; never {@code null}
     * @return its source's delta, or empty when {@code owner} has no live source
     */
    public Optional<BackendThreadCounters> liveDelta(Object owner) {
        Objects.requireNonNull(owner, "owner must not be null");
        for (LiveSource source : live) {
            if (source.owner() == owner) {
                return Optional.of(source.delta());
            }
        }
        return Optional.empty();
    }

    /**
     * One attached counter source.
     *
     * @param owner    the source's owner, compared by identity
     * @param source   the counter source
     * @param baseline what the source had counted when it was attached
     */
    public record LiveSource(Object owner, Supplier<BackendThreadCounters> source,
                             BackendThreadCounters baseline) {

        public LiveSource {
            Objects.requireNonNull(owner, "owner must not be null");
            Objects.requireNonNull(source, "source must not be null");
            Objects.requireNonNull(baseline, "baseline must not be null");
        }

        /**
         * What the source counted since its attach: {@code now − baseline} per
         * count and for the heartbeat, each clamped at zero, so a source that
         * reports less than its baseline never produces a negative count. The
         * last faults are the source's own. The source is called once.
         *
         * @return the growth; never {@code null}
         * @throws NullPointerException if the source reports {@code null}
         */
        public BackendThreadCounters delta() {
            BackendThreadCounters now = Objects.requireNonNull(source.get(),
                    "a backend counter source must not report null");
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
    }
}
