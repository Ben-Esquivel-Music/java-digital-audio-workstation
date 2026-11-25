package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.status.EngineHealthStatus;
import com.benesquivelmusic.daw.core.audio.performance.PerformanceEventLossListener;
import com.benesquivelmusic.daw.sdk.audio.performance.DegradationPolicy;
import com.benesquivelmusic.daw.sdk.audio.performance.TrackCpuBudget;
import com.benesquivelmusic.daw.sdk.audio.performance.TrackPerformanceEvent;

import org.junit.jupiter.api.Test;

import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The CPU budget enforcer's degradations in the xrun log (story 338,
 * rulings E42, E174, E178, E181, E184, E197, E199, E200 and E203; amendment A5):
 * for degradations whose policy is not {@code DoNothing}, an entry at a
 * track's first marking, at its first marking after any 30 seconds with no
 * CPU budget event for it and at its first marking after each Clear of the
 * log, saying only that the track went over its CPU budget, with the
 * rolling average as a whole percent; nothing for {@code DoNothing} or a
 * restore; and such an entry never raises the count. A degradation the
 * enforcer's publisher dropped for the log's listener is counted; one the
 * listener received and did not log is not. The listener hands the count over once it
 * has delivered every event queued for it ahead of the latest counted drop,
 * which it reckons from the count of events the enforcer offered before that
 * drop: at that delivery, at the window's next read of the log when that
 * drop was counted only after it, or at its completion when the replaced
 * enforcer is closed once the block in flight at its replacement has ended
 * (ruling E203), whichever comes first, and once. The log writes
 * one entry for all it was handed at its next read. The window's read is the
 * listener's {@code handOverAtTheRead} and then the journal's drain. The
 * subscriber is attached beside the enforcer's UI binding in
 * {@code MainController.installTrackCpuBudgetEnforcer}, before the enforcer is
 * handed to the engine, and the window's feed reads the current one and each
 * one whose replaced enforcer is not closed yet, pinned in
 * {@code FailureSpineWiringSourceTest}; {@code EngineHealthFeedTest} runs the
 * feed's read and {@code CpuBudgetEnforcerRetirerTest} the replacement.
 */
class ShedLogSubscriberTest {

    private static final TrackCpuBudget BUDGET = TrackCpuBudget.UNLIMITED;
    private static final long SECOND = 1_000_000_000L;

    /** The events offered through {@link #offer}, counted after each offer as the enforcer counts them. */
    private long offered;
    /**
     * The enforcer's drop handler: tells a loss listener which event it lost
     * and how many events were offered before it, and does not retry.
     */
    private final BiPredicate<Flow.Subscriber<? super TrackPerformanceEvent>, TrackPerformanceEvent>
            asTheEnforcerDrops = (subscriber, dropped) -> {
                if (subscriber instanceof PerformanceEventLossListener listener) {
                    listener.eventDropped(dropped, offered);
                }
                return false;
            };
    private final XrunJournal journal = new XrunJournal();
    /** The consumer tasks of a held publisher: its subscribers receive nothing until the test runs them. */
    private final Queue<Runnable> consumers = new ConcurrentLinkedQueue<>();

    @Test
    void eachDegradationThatNamesAPolicyIsOneEntrySayingOnlyThatTheTrackWentOverItsBudget() {
        try (SubmissionPublisher<TrackPerformanceEvent> enforcer = new SubmissionPublisher<>()) {
            ShedLogSubscriber subscriber = new ShedLogSubscriber(journal);
            enforcer.subscribe(subscriber);

            enforcer.submit(degraded("t-1", 0.874, new DegradationPolicy.BypassExpensive()));
            enforcer.submit(degraded("t-2", 0.906, new DegradationPolicy.ReduceOversampling(2)));
            enforcer.submit(degraded("t-3", 1.04, new DegradationPolicy.SubstituteSimpleKernel("fir-short")));
            enforcer.submit(degraded("t-4", 0.99, new DegradationPolicy.DoNothing()));
            enforcer.submit(new TrackPerformanceEvent.TrackRestored("t-1", 0.4));
            enforcer.submit(degraded("t-5", 0.5, new DegradationPolicy.ReduceOversampling(4)));
            await(() -> journal.entries().size() >= 4, "the entries were written");
        }

        assertThat(journal.entries()).extracting(XrunJournal.Entry::detail, XrunJournal.Entry::arguments)
                .containsExactly(
                        tuple(XrunJournal.Detail.OVER_CPU_BUDGET, List.of("t-1", 87L)),
                        tuple(XrunJournal.Detail.OVER_CPU_BUDGET, List.of("t-2", 91L)),
                        tuple(XrunJournal.Detail.OVER_CPU_BUDGET, List.of("t-3", 104L)),
                        tuple(XrunJournal.Detail.OVER_CPU_BUDGET, List.of("t-5", 50L)));
        assertThat(journal.counts().gaps()).as("going over the budget is not a dropout (ruling E21)").isZero();
    }

    /**
     * A drop comes while the listener's buffer is full, so a buffer's worth
     * of deliveries is still to come; the journal keeps the newest hundred.
     * The count is handed over after the last of those deliveries, so the
     * read after them shows it as the newest entry.
     */
    @Test
    void aDroppedDegradationIsTheNewestEntryOfTheNextDrainAfterTheBurstThatFollows() {
        ShedLogSubscriber subscriber = new ShedLogSubscriber(journal);
        subscriber.eventDropped(degraded("t-lost", 0.9, new DegradationPolicy.BypassExpensive()),
                Flow.defaultBufferSize());
        for (int i = 0; i < Flow.defaultBufferSize(); i++) {
            subscriber.onNext(degraded("t-" + i, 0.9, new DegradationPolicy.BypassExpensive()));
        }

        List<XrunJournal.Entry> drained = journal.drain();

        assertThat(drained).hasSize(XrunJournal.MAX_ENTRIES);
        assertNotLogged(drained.getLast(), 1L);
        assertThat(journal.counts().gaps()).isZero();
    }

    /**
     * Ruling E184: the window reads the log while the listener delivers its
     * backlog, and the log keeps only its newest lines. A read between the
     * first delivery after the drop and the end of the backlog finds no count
     * yet, and the count is among the newest lines once the backlog is in.
     */
    @Test
    void aReadOfTheLogDuringTheBacklogLeavesTheCountAmongTheNewestLines() {
        ShedLogSubscriber subscriber = new ShedLogSubscriber(journal);
        List<XrunJournal.Entry> log = new ArrayList<>();
        subscriber.eventDropped(degraded("t-lost", 0.9, new DegradationPolicy.BypassExpensive()),
                Flow.defaultBufferSize());
        subscriber.onNext(degraded("t-0", 0.9, new DegradationPolicy.BypassExpensive()));
        List<XrunJournal.Entry> firstRead = windowRead(subscriber);
        log.addAll(firstRead);
        for (int i = 1; i < Flow.defaultBufferSize(); i++) {
            subscriber.onNext(degraded("t-" + i, 0.9, new DegradationPolicy.BypassExpensive()));
            if (i % 40 == 0) {
                log.addAll(windowRead(subscriber));
            }
        }
        log.addAll(windowRead(subscriber));

        assertThat(firstRead).as("the read during the backlog").extracting(XrunJournal.Entry::detail)
                .containsExactly(XrunJournal.Detail.OVER_CPU_BUDGET);
        assertThat(log.subList(log.size() - EngineHealthStatus.MAX_LOG_LINES, log.size()))
                .filteredOn(entry -> entry.detail() == XrunJournal.Detail.CPU_BUDGET_ENTRIES_NOT_LOGGED)
                .singleElement().satisfies(note -> assertNotLogged(note, 1L));
        assertThat(log.getLast().detail()).as("the newest line")
                .isEqualTo(XrunJournal.Detail.CPU_BUDGET_ENTRIES_NOT_LOGGED);
    }

    /**
     * Ruling E184's check: 300 degradations offered to the held listener,
     * its deliveries run one at a time and the log read after each. The
     * count is handed over at the delivery that ends the backlog the buffer
     * held at the drops, and nothing of that backlog is read after it.
     */
    @Test
    void theCountIsHandedOverAtTheDeliveryThatEndsTheBacklogQueuedAtTheLatestDrop() {
        ReadAfterEachDelivery window = new ReadAfterEachDelivery(new ShedLogSubscriber(journal));
        int backlog = Flow.defaultBufferSize();
        try (SubmissionPublisher<TrackPerformanceEvent> enforcer = heldPublisher()) {
            enforcer.subscribe(window);
            Offered offered = offer(enforcer, 300, 1);
            runAll();
            List<List<XrunJournal.Entry>> reads = List.copyOf(window.reads);

            enforcer.close();
            runAll();

            assertThat(offered.dropped()).as("premise: N = 300 offered less the %d the buffer held", backlog)
                    .isEqualTo(44L);
            assertThat(reads).as("premise: one read per delivery").hasSize(backlog);
            assertThat(reads.subList(0, backlog - 1)).as("no count before the delivery that ends the backlog")
                    .allSatisfy(read -> assertThat(read).extracting(XrunJournal.Entry::detail)
                            .containsExactly(XrunJournal.Detail.OVER_CPU_BUDGET));
            assertThat(reads.getLast()).as("the read after delivery %d: the count is its newest entry", backlog)
                    .extracting(XrunJournal.Entry::detail, XrunJournal.Entry::arguments).containsExactly(
                            tuple(XrunJournal.Detail.OVER_CPU_BUDGET, List.of("t-" + (backlog - 1), 90L)),
                            tuple(XrunJournal.Detail.CPU_BUDGET_ENTRIES_NOT_LOGGED, List.of(44L)));
            assertNotLogged(reads.getLast().getLast(), 44L);
            assertThat(journal.drain()).as("nothing of the backlog after it, nothing more at the completion")
                    .isEmpty();
            assertThat(journal.counts().gaps()).as("the xrun count is unchanged").isZero();
        }
    }

    /**
     * Ruling E184: the consumer takes the first event of the backlog and
     * counts it before the publisher's drop handler runs. The count still
     * goes over at the delivery that ends the backlog queued at the drop,
     * with nothing offered after it.
     */
    @Test
    void aDeliveryRacingTheDropHandlerLeavesTheCountToFollowTheBacklogAtOnce() {
        ShedLogSubscriber subscriber = new ShedLogSubscriber(journal);
        int backlog = Flow.defaultBufferSize();
        subscriber.onNext(degraded("t-0", 0.9, new DegradationPolicy.BypassExpensive()));
        subscriber.eventDropped(degraded("t-lost", 0.9, new DegradationPolicy.BypassExpensive()), backlog);
        for (int i = 1; i < backlog; i++) {
            subscriber.onNext(degraded("t-" + i, 0.9, new DegradationPolicy.BypassExpensive()));
        }

        List<XrunJournal.Entry> read = journal.drain();

        assertNotLogged(read.getLast(), 1L);
        assertThat(read.get(read.size() - 2).arguments()).as("the last event of the backlog, just ahead of the count")
                .containsExactly("t-" + (backlog - 1), 90L);
    }

    /**
     * Ruling E184: the drop comes while the consumer has taken the oldest
     * event from the buffer and not yet counted it, so the backlog queued at
     * the drop is that event and a full buffer behind it. Its last event is
     * logged ahead of the count.
     */
    @Test
    void anEventInFlightAtTheDropIsLoggedAheadOfTheCount() {
        ShedLogSubscriber subscriber = new ShedLogSubscriber(journal);
        int backlog = Flow.defaultBufferSize();
        subscriber.eventDropped(degraded("t-lost", 0.9, new DegradationPolicy.BypassExpensive()), backlog + 1L);
        for (int i = 0; i < backlog; i++) {
            subscriber.onNext(degraded("t-" + i, 0.9, new DegradationPolicy.BypassExpensive()));
        }
        List<XrunJournal.Entry> beforeTheLastOfTheBacklog = journal.drain();

        subscriber.onNext(degraded("t-" + backlog, 0.9, new DegradationPolicy.BypassExpensive()));

        assertThat(beforeTheLastOfTheBacklog).extracting(XrunJournal.Entry::detail)
                .as("one event of the backlog is still to come").doesNotContain(
                        XrunJournal.Detail.CPU_BUDGET_ENTRIES_NOT_LOGGED);
        assertThat(journal.drain()).extracting(XrunJournal.Entry::detail, XrunJournal.Entry::arguments)
                .containsExactly(tuple(XrunJournal.Detail.OVER_CPU_BUDGET, List.of("t-" + backlog, 90L)),
                        tuple(XrunJournal.Detail.CPU_BUDGET_ENTRIES_NOT_LOGGED, List.of(1L)));
    }

    /** Ruling E184: two runs of losses handed over before one read of the log are one entry carrying their sum. */
    @Test
    void twoRunsHandedOverBeforeOneReadAreOneEntryCarryingTheirSum() {
        try (SubmissionPublisher<TrackPerformanceEvent> enforcer = heldPublisher()) {
            enforcer.subscribe(new ShedLogSubscriber(journal));
            Offered first = offer(enforcer, 300, 1);
            runAll();
            Offered second = offer(enforcer, 300, 1);
            runAll();

            List<XrunJournal.Entry> drained = journal.drain();

            assertThat(first.dropped()).as("premise: the first run").isPositive();
            assertThat(second.dropped()).as("premise: the second run, after the first was delivered").isPositive();
            assertThat(drained).filteredOn(entry -> entry.detail() == XrunJournal.Detail.CPU_BUDGET_ENTRIES_NOT_LOGGED)
                    .singleElement().satisfies(note -> assertNotLogged(note, first.dropped() + second.dropped()));
            assertThat(drained.getLast().detail()).isEqualTo(XrunJournal.Detail.CPU_BUDGET_ENTRIES_NOT_LOGGED);
            assertThat(journal.counts().gaps()).isZero();
        }
    }

    @Test
    void aDroppedRestoreOrDoNothingCountsNothing() {
        ShedLogSubscriber subscriber = new ShedLogSubscriber(journal);
        subscriber.eventDropped(new TrackPerformanceEvent.TrackRestored("t-1", 0.2), 0L);
        subscriber.eventDropped(degraded("t-2", 0.9, new DegradationPolicy.DoNothing()), 1L);
        subscriber.onNext(degraded("t-3", 0.9, new DegradationPolicy.BypassExpensive()));

        assertThat(journal.drain()).extracting(XrunJournal.Entry::detail)
                .containsExactly(XrunJournal.Detail.OVER_CPU_BUDGET);
    }

    @Test
    void theCompletionReportsWhatWasLostSinceTheLastDelivery() {
        ShedLogSubscriber subscriber = new ShedLogSubscriber(journal);
        subscriber.onNext(degraded("t-1", 0.9, new DegradationPolicy.BypassExpensive()));
        subscriber.eventDropped(degraded("t-2", 0.9, new DegradationPolicy.BypassExpensive()), 1L);
        subscriber.eventDropped(degraded("t-3", 0.9, new DegradationPolicy.SubstituteSimpleKernel("fir-short")), 2L);
        List<XrunJournal.Entry> beforeTheCompletion = journal.drain();

        subscriber.onComplete();

        assertThat(beforeTheCompletion).as("nothing delivered after the losses yet").extracting(XrunJournal.Entry::detail)
                .containsExactly(XrunJournal.Detail.OVER_CPU_BUDGET);
        assertThat(journal.drain()).singleElement().satisfies(note -> assertNotLogged(note, 2L));
    }

    /**
     * Ruling E197: the audio thread is held between the publisher's decision
     * to drop a degradation and the listener counting it, while the listener
     * delivers its whole buffer. The window's next read hands the count over,
     * with no event and no completion after it, and the next event adds no
     * second entry for that drop.
     */
    @Test
    void aDropCountedAfterTheWholeBufferWasDeliveredIsHandedOverAtTheWindowsNextReadAndNotAgainAtTheNextEvent() {
        ShedLogSubscriber subscriber = deliverTheWholeBufferBeforeTheDropIsCounted();

        List<XrunJournal.Entry> read = windowRead(subscriber);
        subscriber.onNext(new TrackPerformanceEvent.TrackRestored("t-0", 0.1));

        assertThat(read).singleElement().satisfies(note -> assertNotLogged(note, 1L));
        assertThat(windowRead(subscriber)).as("the next event adds no second entry for that drop").isEmpty();
        assertThat(journal.counts().gaps()).as("the xrun count is unchanged").isZero();
    }

    /** Ruling E197: the read hands the late count over, and the completion adds no second entry for it. */
    @Test
    void aDropCountedAfterTheWholeBufferWasDeliveredIsHandedOverAtTheWindowsNextReadAndNotAgainAtTheCompletion() {
        ShedLogSubscriber subscriber = deliverTheWholeBufferBeforeTheDropIsCounted();

        List<XrunJournal.Entry> read = windowRead(subscriber);
        subscriber.onComplete();

        assertThat(read).singleElement().satisfies(note -> assertNotLogged(note, 1L));
        assertThat(windowRead(subscriber)).as("the completion adds no second entry for that drop").isEmpty();
        assertThat(journal.counts().gaps()).as("the xrun count is unchanged").isZero();
    }

    /**
     * Ruling E184 under E197: a read that comes while the backlog queued at
     * the drop is still being delivered hands nothing over; the delivery that
     * ends the backlog does, after its own entry.
     */
    @Test
    void aReadWhileTheBacklogIsStillBeingDeliveredHandsNothingOver() {
        ShedLogSubscriber subscriber = new ShedLogSubscriber(journal);
        int backlog = Flow.defaultBufferSize();
        subscriber.eventDropped(degraded("t-lost", 0.9, new DegradationPolicy.BypassExpensive()), backlog);
        for (int i = 0; i < backlog - 1; i++) {
            subscriber.onNext(degraded("t-" + i, 0.9, new DegradationPolicy.BypassExpensive()));
        }

        List<XrunJournal.Entry> duringTheBacklog = windowRead(subscriber);
        subscriber.onNext(degraded("t-" + (backlog - 1), 0.9, new DegradationPolicy.BypassExpensive()));

        assertThat(duringTheBacklog).as("one delivery of the backlog is still to come").isNotEmpty()
                .extracting(XrunJournal.Entry::detail).doesNotContain(XrunJournal.Detail.CPU_BUDGET_ENTRIES_NOT_LOGGED);
        assertThat(windowRead(subscriber)).extracting(XrunJournal.Entry::detail, XrunJournal.Entry::arguments)
                .containsExactly(tuple(XrunJournal.Detail.OVER_CPU_BUDGET, List.of("t-" + (backlog - 1), 90L)),
                        tuple(XrunJournal.Detail.CPU_BUDGET_ENTRIES_NOT_LOGGED, List.of(1L)));
    }

    /**
     * A read cannot hand the count over before the backlog's last entry is in
     * the journal: the delivery that ends the backlog counts itself only after
     * its entry. The read is run inside that delivery's append, between the
     * two, which the listener's monitor otherwise keeps it out of.
     */
    @Test
    void aReadInsideTheLastDeliveryOfTheBacklogBeforeItsEntryIsWrittenHandsNothingOver() {
        AtomicReference<Runnable> insideTheAppend = new AtomicReference<>();
        XrunJournal reentered = new XrunJournal(InstantSource.system(), () -> {
            Runnable read = insideTheAppend.getAndSet(null);
            if (read != null) {
                read.run();
            }
            return System.nanoTime();
        });
        ShedLogSubscriber subscriber = new ShedLogSubscriber(reentered);
        int backlog = Flow.defaultBufferSize();
        subscriber.eventDropped(degraded("t-lost", 0.9, new DegradationPolicy.BypassExpensive()), backlog);
        for (int i = 0; i < backlog - 1; i++) {
            subscriber.onNext(degraded("t-" + i, 0.9, new DegradationPolicy.BypassExpensive()));
        }
        List<List<XrunJournal.Entry>> reads = new ArrayList<>();
        insideTheAppend.set(() -> {
            subscriber.handOverAtTheRead();
            reads.add(reentered.drain());
        });

        subscriber.onNext(degraded("t-" + (backlog - 1), 0.9, new DegradationPolicy.BypassExpensive()));
        subscriber.handOverAtTheRead();
        reads.add(reentered.drain());

        assertThat(reads).as("premise: the read ran inside the append").hasSize(2);
        assertThat(reads.getFirst()).extracting(XrunJournal.Entry::detail)
                .as("the read before the backlog's last entry is in the journal")
                .doesNotContain(XrunJournal.Detail.CPU_BUDGET_ENTRIES_NOT_LOGGED);
        assertThat(reads.getLast()).extracting(XrunJournal.Entry::detail, XrunJournal.Entry::arguments)
                .as("the backlog's last entry, then the count")
                .containsExactly(tuple(XrunJournal.Detail.OVER_CPU_BUDGET, List.of("t-" + (backlog - 1), 90L)),
                        tuple(XrunJournal.Detail.CPU_BUDGET_ENTRIES_NOT_LOGGED, List.of(1L)));
    }

    /**
     * Ruling E197: deliveries, drops and the window's reads on two threads at
     * once; every drop is in exactly one entry, so the entries' N values add
     * up to the drops.
     */
    @Test
    void whenDeliveriesAndReadsRaceEachDropIsCountedOnce() throws Exception {
        ShedLogSubscriber subscriber = new ShedLogSubscriber(journal);
        int events = 20_000;
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger readsDone = new AtomicInteger();
        List<XrunJournal.Entry> read = new ArrayList<>();
        Thread window = Thread.ofPlatform().name("window reads").start(() -> {
            while (done.getCount() > 0) {
                read.addAll(windowRead(subscriber));
                readsDone.incrementAndGet();
            }
        });
        long drops = 0L;
        try {
            for (int i = 0; i < events; i++) {
                if (i % 1_000 == 0) {
                    awaitAnotherRead(readsDone);
                }
                TrackPerformanceEvent.TrackDegraded event = degraded("t-" + i, 0.9,
                        new DegradationPolicy.BypassExpensive());
                if (i % 3 == 0) {
                    subscriber.eventDropped(event, i);
                    drops++;
                } else {
                    subscriber.onNext(event);
                }
            }
        } finally {
            done.countDown();
        }
        window.join();
        subscriber.onComplete();
        read.addAll(windowRead(subscriber));

        List<XrunJournal.Entry> notLogged = read.stream()
                .filter(entry -> entry.detail() == XrunJournal.Detail.CPU_BUDGET_ENTRIES_NOT_LOGGED).toList();
        assertThat(notLogged).as("premise: counts were handed over in several entries").hasSizeGreaterThan(1);
        assertThat(notLogged.stream().mapToLong(entry -> (Long) entry.arguments().getFirst()).sum())
                .as("the N values add up to the drops").isEqualTo(drops);
        assertThat(journal.counts().gaps()).as("the xrun count is unchanged").isZero();
    }

    /**
     * Ruling E174's check: the listener held while more degradations than
     * its buffer holds are offered, restores among them, then released, the
     * window reading the log after each delivery as it does while the
     * listener catches up.
     */
    @Test
    void aListenerThatFellBehindLogsEachDeliveredDegradationAndCountsTheDroppedOnesInOneEntry() {
        ReadAfterEachDelivery window = new ReadAfterEachDelivery(new ShedLogSubscriber(journal));
        Offered offered;
        try (SubmissionPublisher<TrackPerformanceEvent> enforcer = heldPublisher()) {
            enforcer.subscribe(window);
            offered = offer(enforcer, 400, 1, 10);
            runAll();
        }

        List<XrunJournal.Entry> read = window.reads.stream().flatMap(List::stream).toList();

        assertThat(offered.held() + offered.dropped()).as("premise: more degradations than the buffer holds")
                .isGreaterThan(Flow.defaultBufferSize());
        assertThat(offered.dropped()).as("premise: degradations were dropped").isPositive();
        assertThat(read).filteredOn(entry -> entry.detail() == XrunJournal.Detail.OVER_CPU_BUDGET)
                .as("one entry per delivered degradation").hasSize((int) offered.held());
        assertThat(read).filteredOn(entry -> entry.detail() == XrunJournal.Detail.CPU_BUDGET_ENTRIES_NOT_LOGGED)
                .singleElement().satisfies(note -> assertNotLogged(note, offered.dropped()));
        assertThat(read.getLast().detail()).isEqualTo(XrunJournal.Detail.CPU_BUDGET_ENTRIES_NOT_LOGGED);
        assertThat(journal.counts().gaps()).as("the xrun count is unchanged").isZero();
    }

    /** Ruling E178: closing the replaced enforcer delivers what the listener had queued, then the completion. */
    @Test
    void replacingTheEnforcerLogsEveryDegradationItsListenerHadQueued() {
        try (SubmissionPublisher<TrackPerformanceEvent> enforcer = heldPublisher()) {
            enforcer.subscribe(new ShedLogSubscriber(journal));
            Offered offered = offer(enforcer, 50, 1, 5);

            enforcer.close();
            runAll();

            assertThat(offered.held()).as("premise: K degradations, fewer than the buffer holds").isEqualTo(40);
            assertThat(journal.drain()).extracting(XrunJournal.Entry::detail).as("K entries, nothing lost")
                    .hasSize(40).containsOnly(XrunJournal.Detail.OVER_CPU_BUDGET);
            assertThat(journal.counts().gaps()).isZero();
        }
    }

    @Test
    void replacingTheEnforcerAfterMoreThanTheBufferHoldsCountsWhatWasNotDelivered() {
        try (SubmissionPublisher<TrackPerformanceEvent> enforcer = heldPublisher()) {
            enforcer.subscribe(new ShedLogSubscriber(journal));
            Offered offered = offer(enforcer, 300, 1);

            enforcer.close();
            runAll();

            List<XrunJournal.Entry> drained = journal.drain();
            assertThat(offered.dropped()).as("published minus delivered")
                    .isEqualTo(300L - Flow.defaultBufferSize());
            assertNotLogged(drained.getLast(), offered.dropped());
            assertThat(journal.counts().gaps()).isZero();
        }
    }

    /** Ruling E178: what is published after the replaced enforcer is closed is neither logged nor counted. */
    @Test
    void aDegradationPublishedAfterTheReplacedEnforcerIsClosedIsNeitherLoggedNorCounted() {
        try (SubmissionPublisher<TrackPerformanceEvent> enforcer = heldPublisher()) {
            enforcer.subscribe(new ShedLogSubscriber(journal));
            Offered offered = offer(enforcer, 300, 1);

            enforcer.close();
            assertThatThrownBy(() -> enforcer.offer(
                    degraded("t-late", 0.9, new DegradationPolicy.BypassExpensive()), asTheEnforcerDrops))
                    .isInstanceOf(IllegalStateException.class);
            runAll();

            List<XrunJournal.Entry> drained = journal.drain();
            assertThat(drained).extracting(XrunJournal.Entry::arguments)
                    .noneMatch(arguments -> arguments.contains("t-late"));
            assertNotLogged(drained.getLast(), offered.dropped());
            assertThat(journal.counts().gaps()).isZero();
        }
    }

    /**
     * Ruling E199: a track gets one Budget entry when it is first marked, and
     * none while it keeps being marked, restores between the markings
     * included. With a second track cycling the same way, one entry each.
     */
    @Test
    void aTrackMarkedRestoredAndMarkedAgainEverySecondForTwoMinutesHasOneEntry() {
        AtomicLong now = new AtomicLong();
        XrunJournal log = new XrunJournal(InstantSource.system(), now::get);
        ShedLogSubscriber subscriber = new ShedLogSubscriber(log, now::get);

        subscriber.onNext(marking("T"));
        subscriber.onNext(marking("U"));
        for (int second = 1; second <= 120; second++) {
            now.set(second * SECOND - SECOND / 2);
            subscriber.onNext(new TrackPerformanceEvent.TrackRestored("T", 0.1));
            subscriber.onNext(new TrackPerformanceEvent.TrackRestored("U", 0.1));
            now.set(second * SECOND);
            subscriber.onNext(marking("T"));
            subscriber.onNext(marking("U"));
        }

        assertThat(log.drain()).extracting(XrunJournal.Entry::detail, XrunJournal.Entry::arguments).containsExactly(
                tuple(XrunJournal.Detail.OVER_CPU_BUDGET, List.of("T", 90L)),
                tuple(XrunJournal.Detail.OVER_CPU_BUDGET, List.of("U", 90L)));
        assertThat(log.counts().gaps()).as("the xrun count is unchanged").isZero();
    }

    /**
     * Rulings E199 and E200: T and U, marked 1 s before their restores, so
     * inside their runs, are marked again: T 29.9 s after its restore, which
     * writes nothing, U 30 s after its, which writes one entry.
     */
    @Test
    void aMarkingWithinThirtySecondsOfTheTracksLastEventWritesNothingAndOneThirtySecondsAfterWritesOne() {
        AtomicLong now = new AtomicLong();
        XrunJournal log = new XrunJournal(InstantSource.system(), now::get);
        ShedLogSubscriber subscriber = new ShedLogSubscriber(log, now::get);
        subscriber.onNext(marking("T"));
        subscriber.onNext(marking("U"));
        now.set(SECOND);
        subscriber.onNext(new TrackPerformanceEvent.TrackRestored("T", 0.1));
        subscriber.onNext(new TrackPerformanceEvent.TrackRestored("U", 0.1));

        now.set(SECOND + 29 * SECOND + SECOND * 9 / 10);
        subscriber.onNext(marking("T"));
        now.set(SECOND + 30 * SECOND);
        subscriber.onNext(marking("U"));

        assertThat(log.drain()).extracting(XrunJournal.Entry::arguments).as("T once, U at its first marking and"
                        + " again 30 s after its restore")
                .containsExactly(List.of("T", 90L), List.of("U", 90L), List.of("U", 90L));
    }

    /** Ruling E199: after the log's Clear, the next marking of a track that keeps being marked writes one entry. */
    @Test
    void aClearWhileATrackKeepsBeingMarkedLetsItsNextMarkingWriteOneEntry() {
        AtomicLong now = new AtomicLong();
        XrunJournal log = new XrunJournal(InstantSource.system(), now::get);
        ShedLogSubscriber subscriber = new ShedLogSubscriber(log, now::get);
        subscriber.onNext(marking("T"));
        now.set(SECOND);
        subscriber.onNext(new TrackPerformanceEvent.TrackRestored("T", 0.1));

        now.set(SECOND * 3 / 2);
        log.clear();
        for (int second = 2; second <= 5; second++) {
            now.set(second * SECOND);
            subscriber.onNext(marking("T"));
            now.set(second * SECOND + SECOND / 2);
            subscriber.onNext(new TrackPerformanceEvent.TrackRestored("T", 0.1));
        }

        assertThat(log.drain()).extracting(XrunJournal.Entry::arguments).as("one entry after the Clear")
                .containsExactly(List.of("T", 90L));
    }

    /**
     * Ruling E200: a restore inside the track's run keeps the Clears of the
     * run's entry, so a Clear between the marking and that restore lets the
     * next marking write one entry.
     */
    @Test
    void aRestoreInsideTheRunKeepsTheClearsOfItsEntrySoAClearBeforeItLetsTheNextMarkingWriteOne() {
        AtomicLong now = new AtomicLong();
        XrunJournal log = new XrunJournal(InstantSource.system(), now::get);
        ShedLogSubscriber subscriber = new ShedLogSubscriber(log, now::get);
        subscriber.onNext(marking("T"));
        now.set(SECOND / 2);
        log.clear();
        now.set(SECOND);
        subscriber.onNext(new TrackPerformanceEvent.TrackRestored("T", 0.1));

        now.set(2 * SECOND);
        subscriber.onNext(marking("T"));

        assertThat(log.drain()).extracting(XrunJournal.Entry::arguments, XrunJournal.Entry::arrivedNanos)
                .as("one entry after the Clear, at 2 s").containsExactly(tuple(List.of("T", 90L), 2 * SECOND));
    }

    /**
     * Ruling E200: T, marked at 0 s, is restored at 40 s, after 30 seconds
     * with no CPU budget event for it, and the log was cleared at 5 s. Its
     * marking at 41 s writes one entry; its restores and markings every
     * second after it, to 120 s, write none.
     */
    @Test
    void aRestoreAfterThirtySecondsWithNoEventLetsTheNextMarkingWriteOneEntryAfterAClear() {
        AtomicLong now = new AtomicLong();
        XrunJournal log = new XrunJournal(InstantSource.system(), now::get);
        ShedLogSubscriber subscriber = new ShedLogSubscriber(log, now::get);
        subscriber.onNext(marking("T"));
        now.set(5 * SECOND);
        List<XrunJournal.Entry> beforeTheClear = log.drain();
        log.clear();

        restoreAtFortyMarkAtFortyOneThenCycleEverySecondToTwoMinutes(subscriber, now);

        assertThat(beforeTheClear).extracting(XrunJournal.Entry::arguments, XrunJournal.Entry::arrivedNanos)
                .as("one entry before the Clear, at 0 s").containsExactly(tuple(List.of("T", 90L), 0L));
        assertThat(log.drain()).extracting(XrunJournal.Entry::arguments, XrunJournal.Entry::arrivedNanos)
                .as("exactly one entry after the Clear, at 41 s").containsExactly(tuple(List.of("T", 90L), 41 * SECOND));
    }

    /**
     * Ruling E200: as {@link #aRestoreAfterThirtySecondsWithNoEventLetsTheNextMarkingWriteOneEntryAfterAClear},
     * with no Clear: one entry at 0 s and one at 41 s.
     */
    @Test
    void aRestoreAfterThirtySecondsWithNoEventLetsTheNextMarkingWriteOneEntry() {
        AtomicLong now = new AtomicLong();
        XrunJournal log = new XrunJournal(InstantSource.system(), now::get);
        ShedLogSubscriber subscriber = new ShedLogSubscriber(log, now::get);
        subscriber.onNext(marking("T"));

        restoreAtFortyMarkAtFortyOneThenCycleEverySecondToTwoMinutes(subscriber, now);

        assertThat(log.drain()).extracting(XrunJournal.Entry::arguments, XrunJournal.Entry::arrivedNanos)
                .as("exactly two entries, at 0 s and 41 s")
                .containsExactly(tuple(List.of("T", 90L), 0L), tuple(List.of("T", 90L), 41 * SECOND));
    }

    /**
     * Ruling E199: the listener keeps a track only while that track's last
     * event is less than 30 seconds old, a removed track included.
     */
    @Test
    void theListenerHoldsNoStateForATrackThirtySecondsAfterItsLastEvent() {
        AtomicLong now = new AtomicLong();
        XrunJournal log = new XrunJournal(InstantSource.system(), now::get);
        ShedLogSubscriber subscriber = new ShedLogSubscriber(log, now::get);
        subscriber.onNext(marking("T"));
        now.set(10 * SECOND);
        subscriber.onNext(new TrackPerformanceEvent.TrackRestored("removed", 0.1));

        now.set(30 * SECOND - SECOND / 10);
        int beforeThirtySeconds = heldAfterARead(subscriber);
        now.set(30 * SECOND);
        int atThirtySeconds = heldAfterARead(subscriber);
        now.set(40 * SECOND);
        int atTheRemovedTracksThirtySeconds = heldAfterARead(subscriber);
        now.set(100 * SECOND);
        subscriber.onNext(marking("V"));

        assertThat(beforeThirtySeconds).as("both tracks' last events are less than 30 s old").isEqualTo(2);
        assertThat(atThirtySeconds).as("T's last event is 30 s old").isEqualTo(1);
        assertThat(atTheRemovedTracksThirtySeconds).as("the removed track's too").isZero();
        assertThat(subscriber.tracksHeld()).as("a delivery keeps only its own track").isEqualTo(1);
    }

    /**
     * Ruling E199: a marking the listener does not log on purpose was
     * delivered, so it adds nothing to the count of markings that never
     * reached the log; only the dropped one is counted.
     */
    @Test
    void aMarkingTheListenerDoesNotLogAddsNothingToTheCount() {
        AtomicLong now = new AtomicLong();
        XrunJournal log = new XrunJournal(InstantSource.system(), now::get);
        ShedLogSubscriber subscriber = new ShedLogSubscriber(log, now::get);
        long offered = 0L;
        subscriber.onNext(marking("T"));
        offered++;
        for (int second = 1; second <= 10; second++) {
            now.set(second * SECOND);
            subscriber.onNext(new TrackPerformanceEvent.TrackRestored("T", 0.1));
            subscriber.onNext(marking("T"));
            offered += 2;
        }

        subscriber.eventDropped(marking("T"), offered);
        subscriber.handOverAtTheRead();

        assertThat(log.drain()).extracting(XrunJournal.Entry::detail, XrunJournal.Entry::arguments).containsExactly(
                tuple(XrunJournal.Detail.OVER_CPU_BUDGET, List.of("T", 90L)),
                tuple(XrunJournal.Detail.CPU_BUDGET_ENTRIES_NOT_LOGGED, List.of(1L)));
        assertThat(log.counts().gaps()).as("the xrun count is unchanged").isZero();
    }

    private static int heldAfterARead(ShedLogSubscriber subscriber) {
        subscriber.handOverAtTheRead();
        return subscriber.tracksHeld();
    }

    /** T restored at 40 s and marked at 41 s, then restored and marked again every second to 120 s. */
    private static void restoreAtFortyMarkAtFortyOneThenCycleEverySecondToTwoMinutes(ShedLogSubscriber subscriber,
                                                                                    AtomicLong now) {
        now.set(40 * SECOND);
        subscriber.onNext(new TrackPerformanceEvent.TrackRestored("T", 0.1));
        now.set(41 * SECOND);
        subscriber.onNext(marking("T"));
        for (int second = 42; second <= 120; second++) {
            now.set(second * SECOND - SECOND / 2);
            subscriber.onNext(new TrackPerformanceEvent.TrackRestored("T", 0.1));
            now.set(second * SECOND);
            subscriber.onNext(marking("T"));
        }
    }

    private static TrackPerformanceEvent.TrackDegraded marking(String trackId) {
        return degraded(trackId, 0.9, new DegradationPolicy.BypassExpensive());
    }

    private static TrackPerformanceEvent.TrackDegraded degraded(String trackId, double fraction,
                                                               DegradationPolicy policy) {
        return new TrackPerformanceEvent.TrackDegraded(trackId, fraction, BUDGET, policy);
    }

    private static void assertNotLogged(XrunJournal.Entry entry, long lost) {
        assertThat(entry.detail()).isEqualTo(XrunJournal.Detail.CPU_BUDGET_ENTRIES_NOT_LOGGED);
        assertThat(entry.arguments()).containsExactly(lost);
        assertThat(entry.count()).as("the number is the entry's own, not a repeat").isEqualTo(1L);
        assertThat(entry.kind()).isEqualTo(XrunJournal.Kind.SHED);
    }

    /**
     * The publisher drops a degradation with the listener's buffer full; the
     * listener delivers every event that buffer held, and the window reads
     * them, before the drop is counted.
     */
    private ShedLogSubscriber deliverTheWholeBufferBeforeTheDropIsCounted() {
        ShedLogSubscriber subscriber = new ShedLogSubscriber(journal);
        int backlog = Flow.defaultBufferSize();
        for (int i = 0; i < backlog; i++) {
            subscriber.onNext(degraded("t-" + i, 0.9, new DegradationPolicy.BypassExpensive()));
        }
        assertThat(windowRead(subscriber)).as("the read of the backlog, before the late count")
                .extracting(XrunJournal.Entry::detail)
                .isNotEmpty().doesNotContain(XrunJournal.Detail.CPU_BUDGET_ENTRIES_NOT_LOGGED);

        subscriber.eventDropped(degraded("t-lost", 0.9, new DegradationPolicy.BypassExpensive()), backlog);
        return subscriber;
    }

    /** Lets the window's reading thread finish at least one more read, so the race spans many reads. */
    private static void awaitAnotherRead(AtomicInteger readsDone) {
        int seen = readsDone.get();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (readsDone.get() == seen) {
            if (System.nanoTime() - deadline > 0) {
                throw new AssertionError("the window stopped reading");
            }
            Thread.onSpinWait();
        }
    }

    /** The window's read of the log: the listener's hook, then the journal's drain. */
    private List<XrunJournal.Entry> windowRead(ShedLogSubscriber subscriber) {
        subscriber.handOverAtTheRead();
        return journal.drain();
    }

    /** A publisher with the enforcer's buffer size whose subscribers receive nothing until {@link #runAll()}. */
    private SubmissionPublisher<TrackPerformanceEvent> heldPublisher() {
        return new SubmissionPublisher<>(consumers::add, Flow.defaultBufferSize());
    }

    private void runAll() {
        for (Runnable consumer = consumers.poll(); consumer != null; consumer = consumers.poll()) {
            consumer.run();
        }
    }

    /** Degradations offered while the listener was held: those its buffer kept, and those dropped for it. */
    private record Offered(long held, long dropped) {
    }

    /** Offers {@code events} events as the enforcer does; every {@code every}th is a degradation, the rest restores. */
    private Offered offer(SubmissionPublisher<TrackPerformanceEvent> enforcer, int events, int every) {
        return offer(enforcer, events, every, Integer.MAX_VALUE);
    }

    /**
     * As {@link #offer(SubmissionPublisher, int, int)}, except that every
     * {@code restoreEvery}th event is a restore whatever {@code every} says.
     */
    private Offered offer(SubmissionPublisher<TrackPerformanceEvent> enforcer, int events, int every,
                          int restoreEvery) {
        long held = 0L;
        long dropped = 0L;
        for (int i = 0; i < events; i++) {
            boolean degradation = i % every == 0 && (i + 1) % restoreEvery != 0;
            TrackPerformanceEvent event = degradation
                    ? degraded("t-" + i, 0.9, new DegradationPolicy.BypassExpensive())
                    : new TrackPerformanceEvent.TrackRestored("t-" + i, 0.1);
            boolean lost = enforcer.offer(event, asTheEnforcerDrops) < 0;
            offered++;
            if (degradation && lost) {
                dropped++;
            } else if (degradation) {
                held++;
            }
        }
        return new Offered(held, dropped);
    }

    /**
     * The log's listener with the window reading the journal after each
     * delivery, as the window's pulse does while the listener catches up:
     * each read is what the status log is handed at that pulse.
     */
    private final class ReadAfterEachDelivery
            implements Flow.Subscriber<TrackPerformanceEvent>, PerformanceEventLossListener {
        private final ShedLogSubscriber listener;
        private final List<List<XrunJournal.Entry>> reads = new ArrayList<>();

        ReadAfterEachDelivery(ShedLogSubscriber listener) {
            this.listener = listener;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            listener.onSubscribe(subscription);
        }

        @Override
        public void onNext(TrackPerformanceEvent event) {
            listener.onNext(event);
            reads.add(windowRead(listener));
        }

        @Override
        public void onError(Throwable throwable) {
            listener.onError(throwable);
        }

        @Override
        public void onComplete() {
            listener.onComplete();
        }

        @Override
        public void eventDropped(TrackPerformanceEvent event, long offeredBefore) {
            listener.eventDropped(event, offeredBefore);
        }
    }
}
