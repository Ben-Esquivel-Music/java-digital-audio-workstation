package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.status.EngineHealthStatus;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.performance.PerformanceEventLossListener;
import com.benesquivelmusic.daw.core.audio.performance.TrackCpuBudgetEnforcer;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.audio.performance.DegradationPolicy;
import com.benesquivelmusic.daw.sdk.audio.performance.TrackCpuBudget;
import com.benesquivelmusic.daw.sdk.audio.performance.TrackPerformanceEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.await;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Story 338, ruling E203: a replaced CPU budget enforcer is detached from the
 * engine at once and closed only once the render thread can no longer be
 * using it — at once when no block is in flight, otherwise at the first
 * window pulse after that block has ended — and its xrun log listener stays
 * subscribed until that close, so a marking the old enforcer makes in its
 * last block gets a Budget entry under that listener's runs, or is counted in
 * its "never reached the log" entry; the window's read hands over a retiring
 * listener's due count as it does the current one's. The window's disposal
 * closes every waiting enforcer; with no window pulse the retirer closes at
 * once. One test renders through a real {@link AudioEngine}, holding its
 * block inside the enforcer's measurement; the others drive the engine's two
 * counts, blocks begun and blocks ended, by hand. The window's wiring is
 * pinned in {@code FailureSpineWiringSourceTest}.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class CpuBudgetEnforcerRetirerTest {

    private static final double SAMPLE_RATE = 48_000.0;
    private static final int BLOCK = 1024;
    /** Over budget at a thousandth of the block, which the insert's hundredth always is. */
    private static final TrackCpuBudget OVER_AT_A_THOUSANDTH =
            new TrackCpuBudget(0.001, new DegradationPolicy.BypassExpensive());
    private static final String NOT_LOGGED = "1 CPU budget marking never reached the log: it fell behind";

    private final XrunJournal journal = new XrunJournal();
    private final AtomicLong begun = new AtomicLong();
    private final AtomicLong ended = new AtomicLong();
    private final AtomicInteger detaches = new AtomicInteger();

    @Test
    void aMarkingInTheBlockInFlightAtTheReplacementIsLoggedByTheOldListenerAndTheNewEnforcersMarkingAgain()
            throws Exception {
        AudioEngine engine = new AudioEngine(new AudioFormat(SAMPLE_RATE, 2, 16, BLOCK));
        Track track = bindAPlayingTrackOverItsBudget(engine);
        HeldClock clock = new HeldClock();
        TrackCpuBudgetEnforcer replaced = new TrackCpuBudgetEnforcer(SAMPLE_RATE, BLOCK, Double.MIN_VALUE, clock);
        ShedLogSubscriber replacedListener = new ShedLogSubscriber(journal);
        replaced.performanceEvents().subscribe(replacedListener);
        engine.setCpuBudgetEnforcer(replaced);
        CpuBudgetEnforcerRetirer retirer = CpuBudgetEnforcerRetirer.forEngine(engine, true);
        TrackCpuBudgetEnforcer next = new TrackCpuBudgetEnforcer(SAMPLE_RATE, BLOCK, 1.0, System::nanoTime);
        Thread markingBlock = Thread.ofPlatform().name("render").unstarted(() -> render(engine));
        engine.start();
        try {
            clock.holdTheNextMeasurement();
            markingBlock.start();
            clock.awaitHeld();
            boolean markedBeforeItsMeasurementEnded = replaced.isDegraded(track.getId());
            List<Long> countsInFlight = List.of(engine.blocksBegun(), engine.meteringTapBus().blockIndex());

            retirer.retire(replaced, replacedListener);
            TrackCpuBudgetEnforcer installedAfterTheRetire = engine.getCpuBudgetEnforcer();
            retirer.pulse();
            boolean closedWhileTheBlockRan = isClosed(replaced);
            clock.release();
            markingBlock.join(TimeUnit.SECONDS.toMillis(20));
            boolean closedOnceTheBlockEnded = isClosed(replaced);
            retirer.pulse();
            boolean closedAtTheNextPulse = isClosed(replaced);
            await(() -> budgetEntriesOf(track).size() == 1, "the old listener logged the last block's marking");

            next.performanceEvents().subscribe(new ShedLogSubscriber(journal));
            engine.setCpuBudgetEnforcer(next);
            for (int block = 0; block < TrackCpuBudgetEnforcer.CONSECUTIVE_BLOCKS_TO_DEGRADE; block++) {
                render(engine);
            }
            await(() -> budgetEntriesOf(track).size() == 2, "the new listener logged the new enforcer's marking");

            assertThat(markedBeforeItsMeasurementEnded)
                    .as("premise: the track is not marked before the held block's measurement ends").isFalse();
            assertThat(countsInFlight).as("premise: the marking block has begun and not ended")
                    .isEqualTo(List.of(1L, 0L));
            assertThat(markingBlock.isAlive()).as("premise: the marking block ended").isFalse();
            assertThat(installedAfterTheRetire).as("detached at once").isNull();
            assertThat(closedWhileTheBlockRan).as("not closed while the block in flight could use it").isFalse();
            assertThat(closedOnceTheBlockEnded).as("closed by a pulse, not by the block's end").isFalse();
            assertThat(closedAtTheNextPulse).as("closed at the first pulse after the block ended").isTrue();
            assertThat(budgetEntriesOf(track)).as("one Budget entry from each enforcer's listener, no suppression")
                    .hasSize(2);
            assertThat(journal.counts().gaps()).as("the xrun count is unchanged").isZero();
        } finally {
            clock.release();
            if (markingBlock.isAlive()) {
                markingBlock.join(TimeUnit.SECONDS.toMillis(20));
            }
            engine.shutdown();
            replaced.close();
            next.close();
        }
    }

    /** A pulse closes a waiting enforcer whose listener's hand-over throws all the same. */
    @Test
    void aWaitingEnforcerIsClosedEvenWhenItsListenersHandOverThrows() {
        CpuBudgetEnforcerRetirer retirer = retirerOverTheCounts(true);
        TrackCpuBudgetEnforcer replaced = enforcer();
        ShedLogSubscriber failing = new ShedLogSubscriber(journal, () -> {
            throw new IllegalStateException("the journal's clock failed");
        });
        begun.set(1L);
        retirer.retire(replaced, failing);
        boolean closedAtTheRetire = isClosed(replaced);
        ended.set(1L);

        Throwable thrown = catchThrowable(retirer::pulse);

        assertThat(closedAtTheRetire).as("premise: it waits for the block in flight").isFalse();
        assertThat(thrown).as("premise: the hand-over threw").isInstanceOf(IllegalStateException.class);
        assertThat(isClosed(replaced)).as("closed all the same").isTrue();
    }

    /** After the window's disposal no pulse runs, so a replacement closes the enforcer at once. */
    @Test
    void aReplacementAfterTheWindowsDisposalClosesAtOnce() {
        CpuBudgetEnforcerRetirer retirer = retirerOverTheCounts(true);
        retirer.closeAll();
        TrackCpuBudgetEnforcer replaced = enforcer();
        begun.set(1L);

        retirer.retire(replaced, new ShedLogSubscriber(journal));

        assertThat(detaches).as("premise: detached").hasValue(1);
        assertThat(isClosed(replaced)).as("closed at once, with a block in flight").isTrue();
    }

    @Test
    void aMarkingInTheBlockInFlightThatTheOldListenersFullBufferCannotHoldIsCounted() {
        CpuBudgetEnforcerRetirer retirer = retirerOverTheCounts(true);
        TrackCpuBudgetEnforcer replaced = enforcer();
        ShedLogSubscriber listener = new ShedLogSubscriber(journal);
        HeldDelivery held = new HeldDelivery(listener);
        replaced.performanceEvents().subscribe(held);
        try {
            for (int filler = 0; held.dropped.isEmpty(); filler++) {
                assertThat(filler).as("the listener's buffer fills").isLessThan(2_000);
                mark(replaced, "filler-" + filler);
            }

            begun.incrementAndGet();
            retirer.retire(replaced, listener);
            mark(replaced, "T");
            ended.incrementAndGet();
            held.release();
            retirer.pulse();
            held.awaitCompletion();
            listener.handOverAtTheRead();
            List<XrunJournal.Entry> read = journal.drain();

            assertThat(held.dropped).as("the marking in the block in flight was offered and dropped for the listener")
                    .contains("T");
            assertThat(read.getLast().detail()).isEqualTo(XrunJournal.Detail.CPU_BUDGET_ENTRIES_NOT_LOGGED);
            assertThat(read.getLast().arguments()).as("every dropped marking is counted, the last block's included")
                    .containsExactly((long) held.dropped.size());
            assertThat(journal.counts().gaps()).as("the xrun count is unchanged").isZero();
        } finally {
            held.release();
            replaced.close();
        }
    }

    /** The order the engine's counts are read in: a block that begins during the detach read the old enforcer. */
    @Test
    void aBlockThatBeginsWhileTheEnforcerIsDetachedIsWaitedFor() {
        CpuBudgetEnforcerRetirer retirer = new CpuBudgetEnforcerRetirer(() -> {
            begun.incrementAndGet();
            detaches.incrementAndGet();
        }, begun::get, ended::get, true);
        TrackCpuBudgetEnforcer replaced = enforcer();

        retirer.retire(replaced, null);
        boolean closedAtTheRetire = isClosed(replaced);
        retirer.pulse();
        boolean closedAtAPulseWhileThatBlockRuns = isClosed(replaced);
        ended.incrementAndGet();
        retirer.pulse();

        assertThat(detaches).as("premise: detached once").hasValue(1);
        assertThat(closedAtTheRetire).as("that block may still use it").isFalse();
        assertThat(closedAtAPulseWhileThatBlockRuns).isFalse();
        assertThat(isClosed(replaced)).as("closed once that block ended").isTrue();
    }

    @Test
    void anEnforcerReplacedWithNoBlockInFlightIsClosedAtOnce() {
        CpuBudgetEnforcerRetirer retirer = retirerOverTheCounts(true);
        TrackCpuBudgetEnforcer replaced = enforcer();
        begun.set(3L);
        ended.set(3L);

        retirer.retire(replaced, new ShedLogSubscriber(journal));

        assertThat(detaches).hasValue(1);
        assertThat(isClosed(replaced)).isTrue();
    }

    @Test
    void aSecondReplacementBeforeTheFirstClosedClosesEachOnItsOwnBlockInOrder() {
        CpuBudgetEnforcerRetirer retirer = retirerOverTheCounts(true);
        TrackCpuBudgetEnforcer first = enforcer();
        TrackCpuBudgetEnforcer second = enforcer();
        begun.set(1L);
        retirer.retire(first, null);
        ended.set(1L);
        begun.set(2L);
        retirer.retire(second, null);
        boolean[] closedAtTheSecondRetire = {isClosed(first), isClosed(second)};

        retirer.pulse();
        boolean[] closedAtTheFirstPulse = {isClosed(first), isClosed(second)};
        ended.set(2L);
        retirer.pulse();

        assertThat(closedAtTheSecondRetire).as("each waits for its block in flight").containsExactly(false, false);
        assertThat(closedAtTheFirstPulse).as("the first's block ended; the second's has not")
                .containsExactly(true, false);
        assertThat(isClosed(second)).as("the second, once its block ended").isTrue();
    }

    @Test
    void theWindowsDisposalClosesEveryWaitingEnforcer() {
        CpuBudgetEnforcerRetirer retirer = retirerOverTheCounts(true);
        TrackCpuBudgetEnforcer first = enforcer();
        TrackCpuBudgetEnforcer second = enforcer();
        begun.set(1L);
        retirer.retire(first, null);
        retirer.retire(second, null);
        boolean[] waiting = {isClosed(first), isClosed(second)};

        retirer.closeAll();
        retirer.pulse();

        assertThat(waiting).as("premise: both wait for the block in flight").containsExactly(false, false);
        assertThat(isClosed(first)).isTrue();
        assertThat(isClosed(second)).isTrue();
    }

    @Test
    void withNoWindowPulseTheReplacedEnforcerIsClosedAtOnce() {
        CpuBudgetEnforcerRetirer retirer = retirerOverTheCounts(false);
        TrackCpuBudgetEnforcer replaced = enforcer();
        begun.set(1L);

        retirer.retire(replaced, null);

        assertThat(detaches).hasValue(1);
        assertThat(isClosed(replaced)).isTrue();
    }

    @Test
    void theWindowsReadHandsOverTheDueCountOfAListenerWhoseEnforcerIsStillRetiring() throws Exception {
        CpuBudgetEnforcerRetirer retirer = retirerOverTheCounts(true);
        TrackCpuBudgetEnforcer replaced = enforcer();
        ShedLogSubscriber listener = new ShedLogSubscriber(journal);
        begun.set(1L);
        runOnFx(() -> retirer.retire(replaced, listener));
        listener.eventDropped(marking("t-1"), 0L);
        EngineHealthStatus model = onFx(EngineHealthStatus::new);
        EngineHealthFeed feed = new EngineHealthFeed(model, journal, new EngineHealthFeed.Sources(() -> null,
                OptionalDouble::empty, _ -> Optional.empty(), () -> null, retirer::handOverAtTheRead));

        List<String> read = onFx(() -> {
            feed.pulse();
            return List.copyOf(model.xrunLog());
        });

        assertThat(isClosed(replaced)).as("premise: still waiting for its block in flight").isFalse();
        assertThat(read).singleElement().asString().endsWith(NOT_LOGGED);
    }

    private CpuBudgetEnforcerRetirer retirerOverTheCounts(boolean pulsed) {
        return new CpuBudgetEnforcerRetirer(detaches::incrementAndGet, begun::get, ended::get, pulsed);
    }

    private static TrackCpuBudgetEnforcer enforcer() {
        return new TrackCpuBudgetEnforcer(SAMPLE_RATE, BLOCK, 1.0, () -> 0L);
    }

    /** Whether the enforcer's publisher, a {@link SubmissionPublisher}, is closed. */
    private static boolean isClosed(TrackCpuBudgetEnforcer enforcer) {
        return ((SubmissionPublisher<TrackPerformanceEvent>) enforcer.performanceEvents()).isClosed();
    }

    /** Runs {@code trackId} over its budget until the enforcer marks it, as the render thread's blocks do. */
    private static void mark(TrackCpuBudgetEnforcer enforcer, String trackId) {
        enforcer.registerTrack(trackId, OVER_AT_A_THOUSANDTH);
        for (int block = 0; block < TrackCpuBudgetEnforcer.CONSECUTIVE_BLOCKS_TO_DEGRADE; block++) {
            enforcer.recordTrackCpu(trackId, enforcer.getBlockBudgetNanos());
        }
        assertThat(enforcer.isDegraded(trackId)).as("premise: %s marked", trackId).isTrue();
    }

    private static TrackPerformanceEvent.TrackDegraded marking(String trackId) {
        return new TrackPerformanceEvent.TrackDegraded(trackId, 0.9, OVER_AT_A_THOUSANDTH,
                new DegradationPolicy.BypassExpensive());
    }

    private List<XrunJournal.Entry> budgetEntriesOf(Track track) {
        return journal.entries().stream()
                .filter(entry -> entry.detail() == XrunJournal.Detail.OVER_CPU_BUDGET)
                .filter(entry -> entry.arguments().getFirst().equals(track.getId()))
                .toList();
    }

    private static Track bindAPlayingTrackOverItsBudget(AudioEngine engine) {
        Transport transport = new Transport();
        Track track = new Track("Lead Vox", TrackType.AUDIO);
        Mixer mixer = new Mixer();
        MixerChannel channel = new MixerChannel(track.getName());
        channel.setCpuBudget(OVER_AT_A_THOUSANDTH);
        channel.addInsert(new InsertSlot("A hundredth of the block", new SpinsForAHundredthOfTheBlock()));
        mixer.addChannel(channel);
        transport.play();
        engine.setGraph(transport, mixer, List.of(track));
        return track;
    }

    private static void render(AudioEngine engine) {
        engine.processBlock(new float[2][BLOCK], new float[2][BLOCK], BLOCK);
    }

    /**
     * The enforcer's clock, which it reads in each measurement before it
     * decides a marking: armed, it holds the measuring block there, with no
     * timeout of its own, until the test releases it, in the test's
     * {@code finally} at the latest.
     */
    private static final class HeldClock implements LongSupplier {
        private final AtomicBoolean armed = new AtomicBoolean();
        private final CountDownLatch held = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        void holdTheNextMeasurement() {
            armed.set(true);
        }

        void awaitHeld() throws InterruptedException {
            assertThat(held.await(20, TimeUnit.SECONDS)).as("the block reached its measurement").isTrue();
        }

        void release() {
            released.countDown();
        }

        @Override
        public long getAsLong() {
            if (armed.compareAndSet(true, false)) {
                held.countDown();
                try {
                    released.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return 0L;
        }
    }

    /**
     * The log's listener behind a delivery the test holds: the first event
     * waits in delivery, with no timeout of its own, until {@link #release()},
     * which the test calls in its {@code finally} at the latest, so the
     * listener's buffer fills; every drop is passed on, and the dropped
     * markings' tracks kept.
     */
    private static final class HeldDelivery
            implements Flow.Subscriber<TrackPerformanceEvent>, PerformanceEventLossListener {
        private final ShedLogSubscriber listener;
        private final CountDownLatch released = new CountDownLatch(1);
        private final CountDownLatch completed = new CountDownLatch(1);
        final List<String> dropped = new CopyOnWriteArrayList<>();

        HeldDelivery(ShedLogSubscriber listener) {
            this.listener = listener;
        }

        void release() {
            released.countDown();
        }

        void awaitCompletion() {
            try {
                assertThat(completed.await(20, TimeUnit.SECONDS)).as("the closed enforcer completed").isTrue();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            listener.onSubscribe(subscription);
        }

        @Override
        public void onNext(TrackPerformanceEvent event) {
            try {
                released.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            listener.onNext(event);
        }

        @Override
        public void onError(Throwable throwable) {
            listener.onError(throwable);
            completed.countDown();
        }

        @Override
        public void onComplete() {
            listener.onComplete();
            completed.countDown();
        }

        @Override
        public void eventDropped(TrackPerformanceEvent event, long offeredBefore) {
            listener.eventDropped(event, offeredBefore);
            if (event instanceof TrackPerformanceEvent.TrackDegraded degraded) {
                dropped.add(degraded.trackId());
            }
        }
    }

    /** Passes the signal through and spins for a hundredth of the block, so the track is always over its budget. */
    private static final class SpinsForAHundredthOfTheBlock implements AudioProcessor {
        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            long cost = (long) Math.ceil(0.01 * numFrames / SAMPLE_RATE * 1_000_000_000.0);
            long start = System.nanoTime();
            for (int channel = 0; channel < Math.min(inputBuffer.length, outputBuffer.length); channel++) {
                System.arraycopy(inputBuffer[channel], 0, outputBuffer[channel], 0, numFrames);
            }
            while (System.nanoTime() - start < cost) {
                Thread.onSpinWait();
            }
        }

        @Override
        public void reset() {
        }

        @Override
        public int getInputChannelCount() {
            return 2;
        }

        @Override
        public int getOutputChannelCount() {
            return 2;
        }
    }
}
