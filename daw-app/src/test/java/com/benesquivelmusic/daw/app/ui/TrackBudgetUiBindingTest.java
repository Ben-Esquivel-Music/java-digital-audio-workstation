package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.performance.TrackCpuBudgetEnforcer;
import com.benesquivelmusic.daw.sdk.audio.performance.DegradationPolicy;
import com.benesquivelmusic.daw.sdk.audio.performance.TrackCpuBudget;
import com.benesquivelmusic.daw.sdk.audio.performance.TrackPerformanceEvent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Headless tests for {@link TrackBudgetUiBinding} — the glue layer
 * that subscribes to {@link TrackCpuBudgetEnforcer} events and
 * surfaces "⚠" badges plus throttled toast notifications. The
 * binding is driven through a synchronous executor and an injectable
 * clock so the tests do not require a JavaFX runtime nor wall-clock
 * sleeps.
 */
class TrackBudgetUiBindingTest {

    private static TrackCpuBudget tinyBudget(DegradationPolicy policy) {
        return new TrackCpuBudget(0.01, policy);
    }

    /**
     * Simulates an enforcer continually recording over-budget CPU on a
     * track until the policy engages, asserts that the binding's
     * degraded set picks up the track and that exactly one toast
     * notification is emitted (via the {@link NotificationManager}).
     */
    @Test
    void shouldFlagDegradedTrackAndEmitSingleNotification() {
        AtomicLong now = new AtomicLong(0L);
        TrackCpuBudgetEnforcer enforcer = new TrackCpuBudgetEnforcer(
                48_000.0, 480, 1.0, now::get);
        enforcer.registerTrack("vox", tinyBudget(new DegradationPolicy.BypassExpensive()));

        List<String> notifications = new ArrayList<>();
        AtomicReference<Set<String>> latestDegraded = new AtomicReference<>(Set.of());
        TrackBudgetUiBinding binding = new TrackBudgetUiBinding(
                notifications::add,
                id -> "Lead Vocal",
                latestDegraded::set,
                Runnable::run,
                now::get);
        enforcer.performanceEvents().subscribe(binding);

        // Block budget = 480 / 48000 = 10ms = 10_000_000 ns; 50% = 5_000_000 ns.
        // Budget is 1% so 5_000_000 ns is way over.
        for (int i = 0; i < TrackCpuBudgetEnforcer.CONSECUTIVE_BLOCKS_TO_DEGRADE + 2; i++) {
            enforcer.recordTrackCpu("vox", 5_000_000L);
        }

        // Allow the SubmissionPublisher worker to deliver the event and
        // the UI callback to fire (dispatchDegradedSet runs after the
        // ConcurrentHashMap add, so we must wait for it too).
        waitForCondition(() -> latestDegraded.get().contains("vox"));

        assertThat(binding.isDegraded("vox")).isTrue();
        assertThat(latestDegraded.get()).contains("vox");
        assertThat(notifications)
                .hasSize(1)
                .first().asString().contains("Lead Vocal").contains("CPU over budget");
        enforcer.close();
    }

    /**
     * Verifies that re-firing TrackDegraded inside the 30 s throttle
     * window does not produce a second notification, but does keep
     * the track flagged as degraded.
     */
    @Test
    void shouldThrottleNotificationsToOnePerThirtySeconds() {
        AtomicLong now = new AtomicLong(0L);
        List<String> notifications = new ArrayList<>();
        TrackBudgetUiBinding binding = new TrackBudgetUiBinding(
                notifications::add,
                id -> id,
                _ -> { },
                Runnable::run,
                now::get);

        TrackCpuBudget budget = tinyBudget(new DegradationPolicy.BypassExpensive());
        TrackPerformanceEvent.TrackDegraded ev =
                new TrackPerformanceEvent.TrackDegraded("guitar", 0.5, budget, budget.onOverBudget());

        // Direct invocation simulates the publisher delivering events.
        binding.onSubscribe(new NoopSubscription());
        binding.onNext(ev);
        // Same instant — should be throttled.
        binding.onNext(ev);
        // 29 seconds later — still inside throttle window.
        now.addAndGet(29_000_000_000L);
        binding.onNext(ev);
        assertThat(notifications).hasSize(1);

        // 31 seconds after the *first* — outside throttle window now.
        now.addAndGet(2_000_000_000L);
        binding.onNext(ev);
        assertThat(notifications).hasSize(2);
    }

    /**
     * Asserts that a subsequent TrackRestored event clears the
     * degraded set so the mixer view's "⚠" badge is removed.
     */
    @Test
    void shouldClearDegradedSetOnTrackRestored() {
        AtomicReference<Set<String>> latest = new AtomicReference<>(new HashSet<>());
        TrackBudgetUiBinding binding = new TrackBudgetUiBinding(
                _ -> { },
                id -> id,
                latest::set,
                Runnable::run,
                () -> 0L);
        binding.onSubscribe(new NoopSubscription());

        TrackCpuBudget budget = tinyBudget(new DegradationPolicy.DoNothing());
        binding.onNext(new TrackPerformanceEvent.TrackDegraded(
                "snare", 0.6, budget, new DegradationPolicy.BypassExpensive()));
        assertThat(binding.isDegraded("snare")).isTrue();
        assertThat(latest.get()).contains("snare");

        binding.onNext(new TrackPerformanceEvent.TrackRestored("snare", 0.001));
        assertThat(binding.isDegraded("snare")).isFalse();
        assertThat(latest.get()).doesNotContain("snare");
    }

    /**
     * End-to-end: the master-budget cascade publishes
     * {@code TrackDegraded} for the highest-CPU tracks first so the
     * binding flags exactly those tracks.
     */
    @Test
    void shouldFlagTracksShedByMasterBudgetCascade() {
        AtomicLong now = new AtomicLong(0L);
        // Master budget = 80% of block; per-track budgets unlimited.
        TrackCpuBudgetEnforcer enforcer = new TrackCpuBudgetEnforcer(
                48_000.0, 480, 0.8, now::get);
        enforcer.registerTrack("low",
                new TrackCpuBudget(1.0, new DegradationPolicy.BypassExpensive()));
        enforcer.registerTrack("hot",
                new TrackCpuBudget(1.0, new DegradationPolicy.BypassExpensive()));

        AtomicReference<Set<String>> latest = new AtomicReference<>(Set.of());
        TrackBudgetUiBinding binding = new TrackBudgetUiBinding(
                _ -> { },
                id -> id,
                latest::set,
                Runnable::run,
                now::get);
        enforcer.performanceEvents().subscribe(binding);

        // Block budget = 10ms. Push "hot" at 90% (9 ms) and "low" at 10%.
        // Sum (1.0) > master (0.8) — cascade should shed the highest.
        enforcer.recordTrackCpu("low", 1_000_000L);
        enforcer.recordTrackCpu("hot", 9_000_000L);
        List<String> shed = enforcer.evaluateMasterBudget();
        assertThat(shed).startsWith("hot");
        waitForBindingToObserve(binding, "hot");
        assertThat(binding.isDegraded("hot")).isTrue();
        assertThat(binding.isDegraded("low")).isFalse();
        enforcer.close();
    }

    /** Polls (with a small bounded budget) until the binding observes the given id. */
    private static void waitForBindingToObserve(TrackBudgetUiBinding binding, String trackId) {
        waitForCondition(() -> binding.isDegraded(trackId));
    }

    /** Polls (with a bounded 2 s budget) until the given condition becomes true. */
    private static void waitForCondition(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + 2_000_000_000L; // 2s
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Minimal {@link java.util.concurrent.Flow.Subscription} for direct {@code onNext} drives. */
    private static final class NoopSubscription implements java.util.concurrent.Flow.Subscription {
        @Override public void request(long n) { }
        @Override public void cancel() { }
    }
}
