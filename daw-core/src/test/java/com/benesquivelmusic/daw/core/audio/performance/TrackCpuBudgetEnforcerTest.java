package com.benesquivelmusic.daw.core.audio.performance;

import com.benesquivelmusic.daw.sdk.audio.performance.DegradationPolicy;
import com.benesquivelmusic.daw.sdk.audio.performance.TrackCpuBudget;
import com.benesquivelmusic.daw.sdk.audio.performance.TrackPerformanceEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link TrackCpuBudgetEnforcer}. The clock is injected
 * so restoration hysteresis is simulated deterministically without
 * sleeping the test thread.
 */
class TrackCpuBudgetEnforcerTest {

    private static final double SAMPLE_RATE = 48_000.0;
    private static final int BUFFER_SIZE = 480; // 10 ms block
    /** Block budget = 480 / 48000 s = 10 ms = 10_000_000 ns. */
    private static final long BLOCK_BUDGET_NS =
            (long) ((BUFFER_SIZE / SAMPLE_RATE) * 1_000_000_000.0);

    @Test
    void constructorComputesBlockBudgetFromBufferSizeAndSampleRate() {
        try (TrackCpuBudgetEnforcer e = new TrackCpuBudgetEnforcer(SAMPLE_RATE, BUFFER_SIZE)) {
            assertThat(e.getBlockBudgetNanos()).isEqualTo(BLOCK_BUDGET_NS);
            assertThat(e.getMasterMaxFractionOfBlock()).isEqualTo(1.0);
        }
    }

    @Test
    void constructorRejectsInvalidArguments() {
        assertThatThrownBy(() -> new TrackCpuBudgetEnforcer(0.0, BUFFER_SIZE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrackCpuBudgetEnforcer(SAMPLE_RATE, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrackCpuBudgetEnforcer(SAMPLE_RATE, BUFFER_SIZE, 0.0, System::nanoTime))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrackCpuBudgetEnforcer(SAMPLE_RATE, BUFFER_SIZE, 1.5, System::nanoTime))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordTrackCpuRejectsNegativeElapsed() {
        try (TrackCpuBudgetEnforcer e = new TrackCpuBudgetEnforcer(SAMPLE_RATE, BUFFER_SIZE)) {
            e.registerTrack("t", new TrackCpuBudget(0.5, new DegradationPolicy.DoNothing()));
            assertThatThrownBy(() -> e.recordTrackCpu("t", -1L))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void unregisteredTrackIsIgnoredSilently() {
        try (TrackCpuBudgetEnforcer e = new TrackCpuBudgetEnforcer(SAMPLE_RATE, BUFFER_SIZE)) {
            e.recordTrackCpu("nope", BLOCK_BUDGET_NS); // must not throw
            assertThat(e.rollingAverageFor("nope")).isEmpty();
            assertThat(e.isDegraded("nope")).isFalse();
        }
    }

    @Test
    void doNothingPolicyPreservesCurrentBehaviorAndDoesNotPublish() throws Exception {
        AtomicLong clock = new AtomicLong(0L);
        try (TrackCpuBudgetEnforcer e = new TrackCpuBudgetEnforcer(
                SAMPLE_RATE, BUFFER_SIZE, 1.0, clock::get)) {
            EventCollector sub = new EventCollector();
            e.performanceEvents().subscribe(sub);
            e.registerTrack("t", new TrackCpuBudget(0.1, new DegradationPolicy.DoNothing()));
            // 20 blocks well over budget — no events because policy is DoNothing.
            for (int i = 0; i < 20; i++) {
                e.recordTrackCpu("t", BLOCK_BUDGET_NS); // 100% of block
            }
            Thread.sleep(50); // give publisher a chance to deliver
            assertThat(sub.events).isEmpty();
        }
    }

    @Test
    void degradationTriggersAfterFiveConsecutiveOverBudgetBlocks() throws Exception {
        AtomicLong clock = new AtomicLong(0L);
        try (TrackCpuBudgetEnforcer e = new TrackCpuBudgetEnforcer(
                SAMPLE_RATE, BUFFER_SIZE, 1.0, clock::get)) {
            EventCollector sub = new EventCollector();
            e.performanceEvents().subscribe(sub);

            TrackCpuBudget budget = new TrackCpuBudget(
                    0.25, new DegradationPolicy.BypassExpensive());
            e.registerTrack("synth", budget);

            // Four over-budget blocks: not yet degraded.
            for (int i = 0; i < TrackCpuBudgetEnforcer.CONSECUTIVE_BLOCKS_TO_DEGRADE - 1; i++) {
                e.recordTrackCpu("synth", (long) (BLOCK_BUDGET_NS * 0.8));
                clock.addAndGet(BLOCK_BUDGET_NS);
            }
            assertThat(e.isDegraded("synth")).isFalse();

            // Fifth over-budget block — should degrade and publish.
            e.recordTrackCpu("synth", (long) (BLOCK_BUDGET_NS * 0.8));
            sub.awaitEvents(1);

            assertThat(e.isDegraded("synth")).isTrue();
            assertThat(sub.events).hasSize(1);
            assertThat(sub.events.get(0)).isInstanceOf(TrackPerformanceEvent.TrackDegraded.class);
            TrackPerformanceEvent.TrackDegraded d =
                    (TrackPerformanceEvent.TrackDegraded) sub.events.get(0);
            assertThat(d.trackId()).isEqualTo("synth");
            assertThat(d.appliedPolicy()).isInstanceOf(DegradationPolicy.BypassExpensive.class);
            assertThat(d.budget()).isSameAs(budget);
            assertThat(d.measuredFraction()).isGreaterThan(0.25);
        }
    }

    @Test
    void restoreFiresOnceCpuStaysBelowBudgetForOneSecond() throws Exception {
        AtomicLong clock = new AtomicLong(0L);
        try (TrackCpuBudgetEnforcer e = new TrackCpuBudgetEnforcer(
                SAMPLE_RATE, BUFFER_SIZE, 1.0, clock::get)) {
            EventCollector sub = new EventCollector();
            e.performanceEvents().subscribe(sub);

            e.registerTrack("reverb", new TrackCpuBudget(
                    0.25, new DegradationPolicy.ReduceOversampling(1)));

            // Trip degradation.
            for (int i = 0; i < TrackCpuBudgetEnforcer.CONSECUTIVE_BLOCKS_TO_DEGRADE; i++) {
                e.recordTrackCpu("reverb", (long) (BLOCK_BUDGET_NS * 0.9));
                clock.addAndGet(BLOCK_BUDGET_NS);
            }
            sub.awaitEvents(1);
            assertThat(e.isDegraded("reverb")).isTrue();

            // Under-budget blocks for ~500 ms — not yet enough to
            // both wash the rolling average below budget AND clear
            // the 1-second hysteresis.
            for (int i = 0; i < 50; i++) { // 50 * 10 ms = 500 ms
                e.recordTrackCpu("reverb", (long) (BLOCK_BUDGET_NS * 0.05));
                clock.addAndGet(BLOCK_BUDGET_NS);
            }
            Thread.sleep(20);
            assertThat(sub.events).hasSize(1); // still only the degrade event
            assertThat(e.isDegraded("reverb")).isTrue();

            // Feed plenty more (rolling window must fully wash + 1s real time).
            for (int i = 0; i < 150; i++) {
                e.recordTrackCpu("reverb", (long) (BLOCK_BUDGET_NS * 0.05));
                clock.addAndGet(BLOCK_BUDGET_NS);
            }
            sub.awaitEvents(2);

            assertThat(e.isDegraded("reverb")).isFalse();
            assertThat(sub.events).hasSize(2);
            assertThat(sub.events.get(1)).isInstanceOf(TrackPerformanceEvent.TrackRestored.class);
            TrackPerformanceEvent.TrackRestored r =
                    (TrackPerformanceEvent.TrackRestored) sub.events.get(1);
            assertThat(r.trackId()).isEqualTo("reverb");
        }
    }

    @Test
    void briefUnderBudgetDoesNotRestore() throws Exception {
        AtomicLong clock = new AtomicLong(0L);
        try (TrackCpuBudgetEnforcer e = new TrackCpuBudgetEnforcer(
                SAMPLE_RATE, BUFFER_SIZE, 1.0, clock::get)) {
            EventCollector sub = new EventCollector();
            e.performanceEvents().subscribe(sub);

            e.registerTrack("x", new TrackCpuBudget(
                    0.25, new DegradationPolicy.BypassExpensive()));

            for (int i = 0; i < TrackCpuBudgetEnforcer.CONSECUTIVE_BLOCKS_TO_DEGRADE; i++) {
                e.recordTrackCpu("x", (long) (BLOCK_BUDGET_NS * 0.9));
                clock.addAndGet(BLOCK_BUDGET_NS);
            }
            sub.awaitEvents(1);

            // Under-budget only briefly, then over again — must NOT restore.
            e.recordTrackCpu("x", (long) (BLOCK_BUDGET_NS * 0.05));
            clock.addAndGet(BLOCK_BUDGET_NS);
            e.recordTrackCpu("x", (long) (BLOCK_BUDGET_NS * 0.9));
            clock.addAndGet(BLOCK_BUDGET_NS);

            Thread.sleep(20);
            assertThat(sub.events).hasSize(1);
            assertThat(e.isDegraded("x")).isTrue();
        }
    }

    @Test
    void masterBudgetCascadeShedsHighestCpuTracksFirst() throws Exception {
        AtomicLong clock = new AtomicLong(0L);
        try (TrackCpuBudgetEnforcer e = new TrackCpuBudgetEnforcer(
                SAMPLE_RATE, BUFFER_SIZE, 0.9, clock::get)) {
            EventCollector sub = new EventCollector();
            e.performanceEvents().subscribe(sub);

            e.registerTrack("low",  new TrackCpuBudget(0.9, new DegradationPolicy.BypassExpensive()));
            e.registerTrack("mid",  new TrackCpuBudget(0.9, new DegradationPolicy.BypassExpensive()));
            e.registerTrack("high", new TrackCpuBudget(0.9, new DegradationPolicy.BypassExpensive()));

            // Each under its own budget, but total > master (0.9).
            // low=0.1, mid=0.35, high=0.6 -> total 1.05
            e.recordTrackCpu("low",  (long) (BLOCK_BUDGET_NS * 0.10));
            e.recordTrackCpu("mid",  (long) (BLOCK_BUDGET_NS * 0.35));
            e.recordTrackCpu("high", (long) (BLOCK_BUDGET_NS * 0.60));

            List<String> shed = e.evaluateMasterBudget();
            // Shedding "high" alone drops total to 0.45, below 0.9 -> stop.
            assertThat(shed).containsExactly("high");
            assertThat(e.isDegraded("high")).isTrue();
            assertThat(e.isDegraded("mid")).isFalse();
            assertThat(e.isDegraded("low")).isFalse();

            sub.awaitEvents(1);
            assertThat(sub.events).hasSize(1);
            TrackPerformanceEvent.TrackDegraded d =
                    (TrackPerformanceEvent.TrackDegraded) sub.events.get(0);
            assertThat(d.trackId()).isEqualTo("high");
        }
    }

    @Test
    void masterBudgetCascadeShedsMultipleTracksInDescendingCpuOrder() {
        try (TrackCpuBudgetEnforcer e = new TrackCpuBudgetEnforcer(
                SAMPLE_RATE, BUFFER_SIZE, 0.5, System::nanoTime)) {
            e.registerTrack("a", new TrackCpuBudget(0.9, new DegradationPolicy.DoNothing()));
            e.registerTrack("b", new TrackCpuBudget(0.9, new DegradationPolicy.DoNothing()));
            e.registerTrack("c", new TrackCpuBudget(0.9, new DegradationPolicy.DoNothing()));
            e.registerTrack("d", new TrackCpuBudget(0.9, new DegradationPolicy.DoNothing()));

            e.recordTrackCpu("a", (long) (BLOCK_BUDGET_NS * 0.40));
            e.recordTrackCpu("b", (long) (BLOCK_BUDGET_NS * 0.30));
            e.recordTrackCpu("c", (long) (BLOCK_BUDGET_NS * 0.20));
            e.recordTrackCpu("d", (long) (BLOCK_BUDGET_NS * 0.10));

            // total = 1.00, master = 0.5 -> shed a (total 0.60) then b (total 0.30).
            List<String> shed = e.evaluateMasterBudget();
            assertThat(shed).containsExactly("a", "b");
        }
    }

    @Test
    void masterBudgetCascadeSkipsAlreadyDegradedTracks() throws Exception {
        AtomicLong clock = new AtomicLong(0L);
        try (TrackCpuBudgetEnforcer e = new TrackCpuBudgetEnforcer(
                SAMPLE_RATE, BUFFER_SIZE, 0.5, clock::get)) {
            EventCollector sub = new EventCollector();
            e.performanceEvents().subscribe(sub);

            e.registerTrack("high", new TrackCpuBudget(0.1, new DegradationPolicy.BypassExpensive()));
            e.registerTrack("mid",  new TrackCpuBudget(0.9, new DegradationPolicy.BypassExpensive()));
            e.registerTrack("low",  new TrackCpuBudget(0.9, new DegradationPolicy.BypassExpensive()));

            // Trip per-track degradation for "high" first.
            for (int i = 0; i < TrackCpuBudgetEnforcer.CONSECUTIVE_BLOCKS_TO_DEGRADE; i++) {
                e.recordTrackCpu("high", (long) (BLOCK_BUDGET_NS * 0.5));
                e.recordTrackCpu("mid",  (long) (BLOCK_BUDGET_NS * 0.3));
                e.recordTrackCpu("low",  (long) (BLOCK_BUDGET_NS * 0.2));
                clock.addAndGet(BLOCK_BUDGET_NS);
            }
            sub.awaitEvents(1);
            assertThat(e.isDegraded("high")).isTrue();

            // Now evaluate master: total = 1.0, master = 0.5.
            // "high" is already degraded and must be skipped.
            // Cascade sheds "mid" (total drops to 0.7) then
            // still > 0.5 so sheds "low" (total drops to 0.5).
            List<String> shed = e.evaluateMasterBudget();
            assertThat(shed).doesNotContain("high");
            // mid and low should be shed (they were not yet degraded)
            assertThat(shed).contains("mid", "low");
        }
    }

    @Test
    void masterBudgetNotExceededReturnsEmptyList() {
        try (TrackCpuBudgetEnforcer e = new TrackCpuBudgetEnforcer(
                SAMPLE_RATE, BUFFER_SIZE, 0.9, System::nanoTime)) {
            e.registerTrack("x", new TrackCpuBudget(0.5, new DegradationPolicy.BypassExpensive()));
            e.recordTrackCpu("x", (long) (BLOCK_BUDGET_NS * 0.1));
            assertThat(e.evaluateMasterBudget()).isEmpty();
        }
    }

    @Test
    void registerTrackWithNullBudgetUsesUnlimitedDefault() {
        try (TrackCpuBudgetEnforcer e = new TrackCpuBudgetEnforcer(SAMPLE_RATE, BUFFER_SIZE)) {
            e.registerTrack("legacy", null); // simulates old project load
            for (int i = 0; i < 20; i++) {
                e.recordTrackCpu("legacy", BLOCK_BUDGET_NS);
            }
            assertThat(e.isDegraded("legacy")).isFalse();
        }
    }

    @Test
    void unregisterTrackRemovesState() {
        try (TrackCpuBudgetEnforcer e = new TrackCpuBudgetEnforcer(SAMPLE_RATE, BUFFER_SIZE)) {
            e.registerTrack("t", new TrackCpuBudget(0.5, new DegradationPolicy.BypassExpensive()));
            e.recordTrackCpu("t", (long) (BLOCK_BUDGET_NS * 0.2));
            assertThat(e.rollingAverageFor("t")).isPresent();
            e.unregisterTrack("t");
            assertThat(e.rollingAverageFor("t")).isEmpty();
        }
    }

    @Test
    void resetClearsRollingAveragesAndDegradedState() {
        try (TrackCpuBudgetEnforcer e = new TrackCpuBudgetEnforcer(SAMPLE_RATE, BUFFER_SIZE)) {
            e.registerTrack("t", new TrackCpuBudget(0.1, new DegradationPolicy.BypassExpensive()));
            for (int i = 0; i < 10; i++) {
                e.recordTrackCpu("t", BLOCK_BUDGET_NS);
            }
            assertThat(e.isDegraded("t")).isTrue();
            e.reset();
            assertThat(e.isDegraded("t")).isFalse();
            assertThat(e.rollingAverageFor("t")).isEmpty();
        }
    }

    /** Subscriber that collects {@link TrackPerformanceEvent}s for assertions. */
    private static final class EventCollector implements Flow.Subscriber<TrackPerformanceEvent> {
        final List<TrackPerformanceEvent> events = new CopyOnWriteArrayList<>();
        private volatile CountDownLatch latch = new CountDownLatch(0);
        private volatile int awaiting;

        void awaitEvents(int n) throws InterruptedException {
            synchronized (this) {
                awaiting = n;
                int remaining = Math.max(0, n - events.size());
                latch = new CountDownLatch(remaining);
            }
            assertThat(latch.await(5, TimeUnit.SECONDS))
                    .as("expected %d events, saw %d: %s", n, events.size(), events)
                    .isTrue();
        }

        @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
        @Override public void onNext(TrackPerformanceEvent item) {
            events.add(item);
            synchronized (this) {
                if (events.size() <= awaiting && latch.getCount() > 0) {
                    latch.countDown();
                }
            }
        }
        @Override public void onError(Throwable t) { }
        @Override public void onComplete() { }
    }
}
