package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AsioClockLockMonitor}: the rule that turns
 * sample-position answers and resync requests into clock-lock events, driven
 * tick by tick on the test thread through an injected probe, clock and
 * synchronous publisher. The poller thread the monitor starts is an idle
 * thread here, so nothing polls behind the test's back.
 */
class AsioClockLockMonitorTest {

    private static final DeviceId DEVICE = new DeviceId("ASIO", "Clocked Interface");
    private static final ClockSource INTERNAL_IDLE =
            new ClockSource(0, "Internal", false, new ClockKind.Internal());
    private static final ClockSource WORD_CLOCK_CURRENT =
            new ClockSource(1, "Word Clock", true, new ClockKind.WordClock());
    private static final ClockSource INTERNAL_CURRENT =
            new ClockSource(0, "Internal", true, new ClockKind.Internal());
    private static final ClockSource WORD_CLOCK_IDLE =
            new ClockSource(1, "Word Clock", false, new ClockKind.WordClock());

    private FakeProbe probe;
    private AtomicLong clock;
    private List<ClockLockEvent> events;
    private AsioClockLockMonitor monitor;

    @BeforeEach
    void setUp() {
        probe = new FakeProbe();
        probe.sources = List.of(INTERNAL_IDLE, WORD_CLOCK_CURRENT);
        clock = new AtomicLong(1_000L);
        events = new CopyOnWriteArrayList<>();
        SubmissionPublisher<ClockLockEvent> publisher = new SubmissionPublisher<>(Runnable::run, 16);
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(ClockLockEvent event) { events.add(event); }
            @Override public void onError(Throwable t) { }
            @Override public void onComplete() { }
        });
        monitor = new AsioClockLockMonitor(probe, clock::get, publisher,
                Duration.ofSeconds(1), body -> new Thread(() -> { }));
    }

    @Test
    void twoBadAnswersOnAnExternalSourceUnlockOnceDatedByTheFirst() {
        monitor.start(DEVICE);
        tick(ok(100));
        long firstBad = tick(ok(100));
        assertThat(events).as("one bad answer never unlocks").isEmpty();

        tick(ok(100));
        tick(ok(100));
        tick(ok(100));

        assertThat(events).containsExactly(new ClockLockEvent(DEVICE, 1, false, firstBad));
        assertThat(monitor.isLocked()).isFalse();
    }

    @Test
    void threeGoodAnswersRelockDatedByTheThird() {
        monitor.start(DEVICE);
        tick(ok(100));
        tick(notAdvancing());
        tick(noClock());
        events.clear();

        tick(ok(200));
        tick(ok(300));
        assertThat(events).as("two good answers do not relock").isEmpty();
        long third = tick(ok(400));

        assertThat(events).containsExactly(new ClockLockEvent(DEVICE, 1, true, third));
        assertThat(monitor.isLocked()).isTrue();
    }

    @Test
    void anInternalSourceIsNeverPolledAndReadsLocked() {
        probe.sources = List.of(INTERNAL_CURRENT, WORD_CLOCK_IDLE);
        monitor.start(DEVICE);

        for (int i = 0; i < 5; i++) {
            monitor.pollOnce();
        }

        assertThat(probe.positionCalls).isZero();
        assertThat(events).isEmpty();
        assertThat(monitor.isLocked()).isTrue();
    }

    @Test
    void aDeviceThatReportsASingleSourceIsNeverPolled() {
        probe.sources = List.of(WORD_CLOCK_CURRENT);
        monitor.start(DEVICE);

        for (int i = 0; i < 5; i++) {
            monitor.pollOnce();
        }

        assertThat(probe.positionCalls).isZero();
    }

    @Test
    void aNativeBuildWithoutTheSamplePositionQueryPollsNothing() {
        probe.positionAvailable = false;
        monitor.start(DEVICE);

        for (int i = 0; i < 5; i++) {
            monitor.pollOnce();
        }

        assertThat(probe.positionCalls).isZero();
        assertThat(events).isEmpty();
    }

    /**
     * A poll that timed out or was refused (the driver's control panel owns
     * the control thread, say) is no answer: never a bad one, and it breaks
     * the run, so the bad answers on either side of it do not add up.
     */
    @Test
    void aPollWithNoAnswerIsNeverBadAndBreaksTheRun() {
        monitor.start(DEVICE);
        tick(ok(100));
        tick(notAdvancing());
        tick(() -> {
            throw new AudioBackendException("ASIO driver operation did not complete");
        });
        tick(notAdvancing());
        assertThat(events).as("bad, no answer, bad is not two consecutive bad answers").isEmpty();

        long secondRunStart = clock.get();
        tick(notAdvancing());

        assertThat(events).singleElement()
                .satisfies(event -> assertThat(event.locked()).isFalse());
        assertThat(events.getFirst().evidenceNanos()).isLessThan(secondRunStart + 1);
    }

    @Test
    void anUnexpectedErrorCodeIsNoAnswer() {
        monitor.start(DEVICE);
        tick(ok(100));
        tick(notAdvancing());
        tick(() -> new AsioCapabilityShim.SamplePosition(-999, 0L, 0L));
        tick(notAdvancing());

        assertThat(events).isEmpty();
    }

    /** Frozen device periods are the watchdog's stall, never a clock unlock. */
    @Test
    void badAnswersWhileTheDeviceStoppedCallingBackAreNotCounted() {
        monitor.start(DEVICE);
        tick(ok(100));
        probe.periodsAdvance = false;

        for (int i = 0; i < 5; i++) {
            tick(notAdvancing());
            tick(ok(100));
        }

        assertThat(events).isEmpty();
    }

    @Test
    void oneResyncAloneNeverUnlocks() {
        monitor.start(DEVICE);
        tick(ok(100));
        probe.resyncs.incrementAndGet();
        tick(ok(200));
        long firstGood = tick(ok(300));
        tick(ok(400));

        assertThat(events).as("no unlock; the first good answer outside the resync's interval measures the lock")
                .containsExactly(new ClockLockEvent(DEVICE, 1, true, firstGood));
    }

    @Test
    void theFirstGoodAnswerOfAStreamPublishesExactlyOneLockedEvent() {
        monitor.start(DEVICE);
        assertThat(monitor.isMeasured()).as("a fresh stream is not yet measured").isFalse();
        tick(ok(100));
        assertThat(events).as("the first answer is only a baseline").isEmpty();

        long firstGood = tick(ok(200));
        tick(ok(300));
        tick(ok(400));
        tick(ok(500));

        assertThat(events).containsExactly(new ClockLockEvent(DEVICE, 1, true, firstGood));
        assertThat(monitor.isMeasured()).isTrue();
        assertThat(monitor.isLocked()).isTrue();
    }

    @Test
    void oneBadAnswerOfAStreamNotYetMeasuredPublishesNothing() {
        monitor.start(DEVICE);
        tick(ok(100));
        tick(ok(100));

        assertThat(events).as("one bad answer leaves the lock not measured").isEmpty();
        assertThat(monitor.isMeasured()).isFalse();
        assertThat(monitor.isLocked()).isTrue();
    }

    @Test
    void anInternalSourcePublishesNothingAndIsNeverMeasured() {
        probe.sources = List.of(INTERNAL_CURRENT, WORD_CLOCK_IDLE);
        monitor.start(DEVICE);
        for (int i = 0; i < 5; i++) {
            probe.resyncs.incrementAndGet();
            monitor.pollOnce();
        }

        assertThat(events).isEmpty();
        assertThat(monitor.isMeasured()).isFalse();
    }

    /** A source selected inside a stream starts unmeasured again: its first good answer is published. */
    @Test
    void aSourceChangeInsideAStreamReturnsToNotYetMeasured() {
        monitor.start(DEVICE);
        tick(ok(100));
        long firstGood = tick(ok(200));
        assertThat(monitor.isMeasured()).isTrue();

        probe.sources = List.of(INTERNAL_IDLE, WORD_CLOCK_IDLE,
                new ClockSource(2, "ADAT", true, new ClockKind.Adat()));
        acceptedSwitch();
        tick(ok(300));
        assertThat(monitor.isMeasured()).as("the new source is not yet measured").isFalse();
        long newSourceGood = tick(ok(400));

        assertThat(events).containsExactly(new ClockLockEvent(DEVICE, 1, true, firstGood),
                new ClockLockEvent(DEVICE, 2, true, newSourceGood));
    }

    @Test
    void aResyncPlusOneBadAnswerUnlockDatedByTheResync() {
        monitor.start(DEVICE);
        tick(ok(100));
        probe.lastResyncNanos = 777L;
        probe.resyncs.incrementAndGet();
        tick(ok(200));
        tick(notAdvancing());

        assertThat(events).containsExactly(new ClockLockEvent(DEVICE, 1, false, 777L));
    }

    @Test
    void resyncsInConsecutiveIntervalsUnlockEvenWhileThePositionAdvances() {
        monitor.start(DEVICE);
        tick(ok(100));
        probe.lastResyncNanos = 5_000L;
        probe.resyncs.incrementAndGet();
        tick(ok(200));
        probe.resyncs.incrementAndGet();
        tick(ok(300));

        assertThat(events).singleElement()
                .satisfies(event -> {
                    assertThat(event.locked()).isFalse();
                    assertThat(event.evidenceNanos()).isEqualTo(5_000L);
                });
    }

    @Test
    void aResyncOnAnInternalSourceTouchesNoLockState() {
        probe.sources = List.of(INTERNAL_CURRENT, WORD_CLOCK_IDLE);
        monitor.start(DEVICE);
        monitor.pollOnce();
        probe.resyncs.addAndGet(5);
        monitor.pollOnce();

        assertThat(events).isEmpty();
        assertThat(monitor.isLocked()).isTrue();
    }

    /** Every stream starts fresh: an unlock before a close is not carried into the next start of the same device. */
    @Test
    void anUnlockBeforeACloseIsNotCarriedIntoTheNextStartOfTheSameDevice() {
        monitor.start(DEVICE);
        tick(ok(100));
        long firstUnlockEvidence = tick(notAdvancing());
        tick(notAdvancing());
        assertThat(monitor.isLocked()).isFalse();

        monitor.stop();
        monitor.start(DEVICE);
        assertThat(monitor.isLocked()).as("the new stream carries no unlock").isTrue();
        assertThat(monitor.isMeasured()).as("and is not yet measured").isFalse();
        tick(ok(500));
        long secondUnlockEvidence = tick(ok(500));
        tick(ok(500));

        assertThat(events).as("two bad answers of the new stream are a new unlock")
                .containsExactly(new ClockLockEvent(DEVICE, 1, false, firstUnlockEvidence),
                        new ClockLockEvent(DEVICE, 1, false, secondUnlockEvidence));
    }

    @Test
    void anotherDeviceStartsLockedWithoutAnEvent() {
        monitor.start(DEVICE);
        tick(ok(100));
        tick(notAdvancing());
        tick(notAdvancing());
        events.clear();

        monitor.stop();
        monitor.start(new DeviceId("ASIO", "Another Interface"));

        assertThat(monitor.isLocked()).isTrue();
        assertThat(events).isEmpty();
    }

    /**
     * A source the driver accepted is read again on the next tick; switching
     * to the internal clock resets the state silently and stops the polls.
     */
    @Test
    void anInvalidatedSourceIsReadAgainAndAnInternalOneStopsThePolls() {
        monitor.start(DEVICE);
        tick(ok(100));
        tick(notAdvancing());
        tick(notAdvancing());
        events.clear();
        int callsBefore = probe.positionCalls;

        probe.sources = List.of(INTERNAL_CURRENT, WORD_CLOCK_IDLE);
        acceptedSwitch();
        monitor.pollOnce();
        monitor.pollOnce();

        assertThat(probe.positionCalls).isEqualTo(callsBefore);
        assertThat(monitor.isLocked()).isTrue();
        assertThat(events).isEmpty();
    }

    @Test
    void nothingIsPolledWhilePausedForADriverReset() {
        monitor.start(DEVICE);
        tick(ok(100));
        int callsBefore = probe.positionCalls;

        monitor.pause();
        for (int i = 0; i < 3; i++) {
            monitor.pollOnce();
        }

        assertThat(probe.positionCalls).isEqualTo(callsBefore);
    }

    /**
     * A stop that lands while a poll is inside its downcall wins: the late
     * answer is applied and published under the monitor's lock only for the
     * start it belongs to.
     */
    @Test
    void anAnswerThatReturnsAfterStopPublishesNothing() {
        monitor.start(DEVICE);
        tick(ok(100));
        tick(notAdvancing());

        tick(() -> {
            monitor.stop();
            return new AsioCapabilityShim.SamplePosition(AsioClockLockMonitor.ASE_SP_NOT_ADVANCING, 0L, 0L);
        });

        assertThat(events).isEmpty();
        assertThat(monitor.isRunning()).isFalse();
    }

    @Test
    void aStoppedMonitorPollsNothing() {
        monitor.start(DEVICE);
        tick(ok(100));
        int callsBefore = probe.positionCalls;

        monitor.stop();
        monitor.pollOnce();

        assertThat(probe.positionCalls).isEqualTo(callsBefore);
    }

    /**
     * A source read that names no current source (the control panel held the
     * control thread) is retried every ten poll intervals, never each tick,
     * and once it succeeds the stream is polled.
     */
    @Test
    void anUnreadableSourceIsReadAgainEveryTenIntervalsAndThenPolled() {
        probe.sources = List.of();
        monitor.start(DEVICE);
        monitor.pollOnce();
        assertThat(probe.sourceCalls).isEqualTo(1);

        for (int i = 0; i < AsioClockLockMonitor.SOURCE_RETRY_INTERVALS - 1; i++) {
            monitor.pollOnce();
        }
        assertThat(probe.sourceCalls).as("no read between retries").isEqualTo(1);
        monitor.pollOnce();
        assertThat(probe.sourceCalls).as("the tenth interval reads again").isEqualTo(2);
        assertThat(probe.positionCalls).isZero();

        probe.sources = List.of(INTERNAL_IDLE, WORD_CLOCK_CURRENT);
        for (int i = 0; i < AsioClockLockMonitor.SOURCE_RETRY_INTERVALS; i++) {
            monitor.pollOnce();
        }
        assertThat(probe.sourceCalls).isEqualTo(3);
        tick(ok(100));
        tick(notAdvancing());
        tick(notAdvancing());

        assertThat(events).singleElement()
                .satisfies(event -> assertThat(event.locked()).isFalse());
    }

    /**
     * An unreadable source is no answer: never an unlock, and the lock state
     * and the measured flag it finds are kept, so a relock after the read
     * succeeds is still published, and no first measurement is.
     */
    @Test
    void anUnreadableSourceIsNeverAnUnlockAndKeepsTheLockState() {
        monitor.start(DEVICE);
        tick(ok(100));
        tick(notAdvancing());
        tick(notAdvancing());
        events.clear();
        int callsBefore = probe.positionCalls;

        probe.sources = List.of();
        acceptedSwitch();
        for (int i = 0; i < AsioClockLockMonitor.SOURCE_RETRY_INTERVALS; i++) {
            monitor.pollOnce();
        }
        assertThat(probe.positionCalls).as("an unknown source is not polled").isEqualTo(callsBefore);
        assertThat(events).isEmpty();
        assertThat(monitor.isLocked()).as("the unlocked state is kept").isFalse();
        assertThat(monitor.isMeasured()).as("and so is the measured flag").isTrue();

        probe.sources = List.of(INTERNAL_IDLE, WORD_CLOCK_CURRENT);
        for (int i = 0; i < AsioClockLockMonitor.SOURCE_RETRY_INTERVALS; i++) {
            monitor.pollOnce();
        }
        tick(ok(500));
        tick(ok(600));
        tick(ok(700));
        long third = tick(ok(800));

        assertThat(events).containsExactly(new ClockLockEvent(DEVICE, 1, true, third));
    }

    /**
     * A source whose read fails after a selection is judged by nothing: not
     * polled, and the driver's resync requests meanwhile unlock nothing.
     */
    @Test
    void aSourceThatCannotBeReadIsNeverAnUnlockEvenWithResyncs() {
        monitor.start(DEVICE);
        tick(ok(100));
        int callsBefore = probe.positionCalls;
        probe.sourcesFail = true;
        acceptedSwitch();

        for (int i = 0; i < 3 * AsioClockLockMonitor.SOURCE_RETRY_INTERVALS; i++) {
            probe.resyncs.addAndGet(2);
            monitor.pollOnce();
        }

        assertThat(probe.sourceCalls).as("the first read, then one per ten intervals").isEqualTo(4);
        assertThat(probe.positionCalls).isEqualTo(callsBefore);
        assertThat(events).isEmpty();
        assertThat(monitor.isLocked()).isTrue();
    }

    /**
     * The level behind the events, at each change: a start, the source read,
     * the first measurement, an unlock, a relock, a source change (a new
     * serial), a failed source read (nothing changes) and the stop (empty).
     */
    @Test
    void theStatusFollowsEveryChangeOfTheLockAndStartsAfreshWithANewSerial() {
        assertThat(monitor.status()).as("nothing before the first start").isEmpty();
        monitor.start(DEVICE);
        ClockLockStatus started = monitor.status().orElseThrow();
        tick(ok(100));
        ClockLockStatus sourceRead = monitor.status().orElseThrow();
        long firstGood = tick(ok(200));
        ClockLockStatus measured = monitor.status().orElseThrow();
        long firstBad = tick(ok(200));
        tick(noClock());
        ClockLockStatus unlocked = monitor.status().orElseThrow();
        tick(ok(300));
        tick(ok(400));
        long third = tick(ok(500));
        ClockLockStatus relocked = monitor.status().orElseThrow();

        probe.sources = List.of();
        acceptedSwitch();
        monitor.pollOnce();
        ClockLockStatus afterAFailedRead = monitor.status().orElseThrow();
        probe.sources = List.of(INTERNAL_IDLE, WORD_CLOCK_IDLE, new ClockSource(2, "ADAT", true, new ClockKind.Adat()));
        for (int i = 0; i < AsioClockLockMonitor.SOURCE_RETRY_INTERVALS; i++) {
            monitor.pollOnce();
        }
        ClockLockStatus newSource = monitor.status().orElseThrow();
        monitor.stop();

        assertThat(started).as("a start: the source not read yet")
                .isEqualTo(new ClockLockStatus(DEVICE, -1, started.serial(), ClockLockStatus.Lock.CHECKING, 0L));
        assertThat(sourceRead).as("the source read: polled, not yet measured")
                .isEqualTo(new ClockLockStatus(DEVICE, 1, sourceRead.serial(), ClockLockStatus.Lock.CHECKING, 0L));
        assertThat(measured).as("the first good answer")
                .isEqualTo(new ClockLockStatus(DEVICE, 1, sourceRead.serial(), ClockLockStatus.Lock.LOCKED, firstGood));
        assertThat(unlocked).as("two bad answers, dated by the first")
                .isEqualTo(new ClockLockStatus(DEVICE, 1, sourceRead.serial(), ClockLockStatus.Lock.UNLOCKED,
                        firstBad));
        assertThat(relocked).as("the relock keeps the serial: same serial, was UNLOCKED, is LOCKED")
                .isEqualTo(new ClockLockStatus(DEVICE, 1, sourceRead.serial(), ClockLockStatus.Lock.LOCKED, third));
        assertThat(afterAFailedRead).as("a failed source read changes nothing (ruling E80)").isEqualTo(relocked);
        assertThat(newSource).as("a source change starts afresh under a new serial")
                .isEqualTo(new ClockLockStatus(DEVICE, 2, newSource.serial(), ClockLockStatus.Lock.CHECKING, 0L));
        assertThat(List.of(started.serial(), sourceRead.serial(), newSource.serial()))
                .as("every fresh start has its own serial").doesNotHaveDuplicates();
        assertThat(monitor.status()).as("the stop empties it").isEmpty();
    }

    /** A consumer woken by an event reads the state the event announces. */
    @Test
    void theStatusIsWrittenBeforeTheEventIsOffered() {
        List<ClockLockStatus.Lock> seenAtEachEvent = new CopyOnWriteArrayList<>();
        SubmissionPublisher<ClockLockEvent> publisher = new SubmissionPublisher<>(Runnable::run, 16);
        AsioClockLockMonitor watched = new AsioClockLockMonitor(probe, clock::get, publisher,
                Duration.ofSeconds(1), body -> new Thread(() -> { }));
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(ClockLockEvent event) {
                seenAtEachEvent.add(watched.status().orElseThrow().lock());
            }
            @Override public void onError(Throwable t) { }
            @Override public void onComplete() { }
        });
        watched.start(DEVICE);
        for (Supplier<AsioCapabilityShim.SamplePosition> answer
                : List.of(ok(100), ok(200), ok(200), ok(200), ok(300), ok(400), ok(500))) {
            probe.answers.addLast(answer);
            clock.addAndGet(1_000L);
            watched.pollOnce();
        }

        assertThat(seenAtEachEvent).as("first measurement, unlock, relock: each read the state it announced")
                .containsExactly(ClockLockStatus.Lock.LOCKED, ClockLockStatus.Lock.UNLOCKED,
                        ClockLockStatus.Lock.LOCKED);
    }

    /** Ruling E100: a source nothing will poll is known and not measurable, never "checking". */
    @Test
    void aSourceNothingPollsIsNotMeasurable() {
        probe.sources = List.of(INTERNAL_CURRENT, WORD_CLOCK_IDLE);
        monitor.start(DEVICE);
        monitor.pollOnce();
        ClockLockStatus internal = monitor.status().orElseThrow();
        monitor.stop();

        probe.sources = List.of(WORD_CLOCK_CURRENT);
        monitor.start(DEVICE);
        monitor.pollOnce();
        ClockLockStatus singleSource = monitor.status().orElseThrow();
        monitor.stop();

        probe.sources = List.of(INTERNAL_IDLE, WORD_CLOCK_CURRENT);
        probe.positionAvailable = false;
        monitor.start(DEVICE);
        for (int i = 0; i < 3; i++) {
            probe.resyncs.incrementAndGet();
            monitor.pollOnce();
        }
        ClockLockStatus noPosition = monitor.status().orElseThrow();

        assertThat(internal.lock()).isEqualTo(ClockLockStatus.Lock.NOT_MEASURABLE);
        assertThat(internal.sourceId()).isZero();
        assertThat(singleSource.lock()).as("a device with a single source (ruling E60)")
                .isEqualTo(ClockLockStatus.Lock.NOT_MEASURABLE);
        assertThat(noPosition.lock()).as("no sample position; resyncs are never a bad answer there")
                .isEqualTo(ClockLockStatus.Lock.NOT_MEASURABLE);
        assertThat(events).isEmpty();
    }

    /** Ruling E106: several resyncs inside one poll interval are one bad answer, never an unlock. */
    @Test
    void severalResyncsInsideOneIntervalAreOneBadAnswer() {
        monitor.start(DEVICE);
        tick(ok(100));
        probe.resyncs.addAndGet(3);
        tick(ok(200));
        long good = tick(ok(300));

        assertThat(events).as("no unlock from messages that all arrived inside one interval")
                .containsExactly(new ClockLockEvent(DEVICE, 1, true, good));
    }

    /** Ruling E106: a resync and a bad poll answer in one interval are one bad answer. */
    @Test
    void aResyncAndABadPollAnswerInOneIntervalAreOneBadAnswer() {
        monitor.start(DEVICE);
        tick(ok(100));
        probe.resyncs.incrementAndGet();
        tick(notAdvancing());
        long good = tick(ok(200));

        assertThat(events).containsExactly(new ClockLockEvent(DEVICE, 1, true, good));
    }

    /** Ruling E106: a resync in an interval whose poll answered good makes that interval bad. */
    @Test
    void aResyncInAnIntervalWhosePollAnsweredGoodIsABadAnswer() {
        monitor.start(DEVICE);
        tick(ok(100));
        tick(ok(200));
        probe.lastResyncNanos = 4_242L;
        probe.resyncs.incrementAndGet();
        tick(ok(300));
        tick(notAdvancing());

        assertThat(events).extracting(ClockLockEvent::locked).containsExactly(true, false);
        assertThat(events.getLast().evidenceNanos()).as("dated by the resync").isEqualTo(4_242L);
    }

    /** Ruling E106: that resync breaks the good run, so the relock needs three good intervals after it. */
    @Test
    void aResyncInAnIntervalWhosePollAnsweredGoodBreaksTheGoodRun() {
        monitor.start(DEVICE);
        tick(ok(100));
        tick(notAdvancing());
        tick(notAdvancing());
        tick(ok(200));
        tick(ok(300));
        probe.resyncs.incrementAndGet();
        tick(ok(400));
        tick(ok(500));
        tick(ok(600));
        assertThat(events).as("two good intervals after the resync do not relock").hasSize(1);
        long third = tick(ok(700));

        assertThat(events).containsExactly(new ClockLockEvent(DEVICE, 1, false, events.getFirst().evidenceNanos()),
                new ClockLockEvent(DEVICE, 1, true, third));
    }

    /** Ruling E106: a resync in an interval whose poll went unanswered is one bad answer, not a reset. */
    @Test
    void aResyncInAnIntervalWhosePollWentUnansweredIsOneBadAnswer() {
        monitor.start(DEVICE);
        tick(ok(100));
        long firstBad = tick(notAdvancing());
        probe.lastResyncNanos = 9_999L;
        probe.resyncs.incrementAndGet();
        tick(() -> {
            throw new AudioBackendException("ASIO driver operation did not complete");
        });

        assertThat(events).as("bad, then a resync with no poll answer: two consecutive bad intervals")
                .containsExactly(new ClockLockEvent(DEVICE, 1, false, firstBad));
    }

    /**
     * While the driver's control panel owns the control thread nothing is
     * sent to the driver: no source read, no position poll. Once it closes,
     * the poll resumes.
     */
    @Test
    void nothingIsSentToTheDriverWhileItsControlPanelIsOpen() {
        monitor.start(DEVICE);
        tick(ok(100));
        int positionsBefore = probe.positionCalls;
        int sourcesBefore = probe.sourceCalls;

        probe.panelOpen = true;
        acceptedSwitch();
        for (int i = 0; i < 3; i++) {
            monitor.pollOnce();
        }
        int positionsWhileOpen = probe.positionCalls;
        int sourcesWhileOpen = probe.sourceCalls;
        probe.panelOpen = false;
        tick(ok(200));

        assertThat(positionsWhileOpen).as("no position poll while the panel is open").isEqualTo(positionsBefore);
        assertThat(sourcesWhileOpen).as("no source read while the panel is open").isEqualTo(sourcesBefore);
        assertThat(probe.sourceCalls).as("the source is read once the panel closed").isEqualTo(sourcesBefore + 1);
        assertThat(probe.positionCalls).isEqualTo(positionsBefore + 1);
    }

    /** A source switched in the driver's own panel is read once the panel closes, and is measured afresh. */
    @Test
    void aSourceSwitchedInTheDriversPanelIsReadOnceThePanelCloses() {
        monitor.start(DEVICE);
        tick(ok(100));
        long before = monitor.status().orElseThrow().serial();

        probe.panelOpen = true;
        monitor.pollOnce();
        probe.sources = List.of(INTERNAL_IDLE, WORD_CLOCK_IDLE, new ClockSource(2, "ADAT", true, new ClockKind.Adat()));
        probe.panelOpen = false;
        tick(ok(200));
        ClockLockStatus after = monitor.status().orElseThrow();

        assertThat(after.sourceId()).as("the source the panel chose").isEqualTo(2);
        assertThat(after.serial()).as("a new measurement").isNotEqualTo(before);
    }

    /**
     * Ruling E120: resyncs while the device's callbacks stand still are the
     * watchdog's case. A resync in each of two consecutive frozen intervals
     * unlocks nothing.
     */
    @Test
    void resyncsWhileThePeriodsStandStillAreNotJudgedForLock() {
        monitor.start(DEVICE);
        tick(ok(100));
        probe.periodsAdvance = false;
        probe.resyncs.incrementAndGet();
        tick(ok(100));
        probe.resyncs.incrementAndGet();
        tick(ok(100));

        assertThat(events).as("never \"External clock lost\" for frozen periods").isEmpty();
        assertThat(monitor.status().orElseThrow().lock()).isNotEqualTo(ClockLockStatus.Lock.UNLOCKED);
        assertThat(monitor.isLocked()).isTrue();
    }

    /** Ruling E120: a frozen interval with a resync breaks the bad run. */
    @Test
    void aFrozenIntervalWithAResyncBreaksTheBadRun() {
        monitor.start(DEVICE);
        tick(ok(100));
        tick(notAdvancing());
        probe.periodsAdvance = false;
        probe.resyncs.incrementAndGet();
        tick(ok(100));
        probe.periodsAdvance = true;
        tick(notAdvancing());

        assertThat(events).as("bad, frozen, bad: not two bad intervals with nothing between them").isEmpty();
    }

    /** Ruling E120: a frozen interval is neither good nor bad, so a moving position in it measures nothing. */
    @Test
    void aMovingPositionWhileThePeriodsStandStillIsNotAGoodAnswer() {
        monitor.start(DEVICE);
        tick(ok(100));
        probe.periodsAdvance = false;
        tick(ok(200));
        tick(ok(300));

        assertThat(events).isEmpty();
        assertThat(monitor.isMeasured()).as("no answer of this stream has decided the lock").isFalse();
    }

    /**
     * Ruling E121: bad (a resync whose poll went unanswered), an answered
     * interval that could only set a new baseline, bad: not two bad
     * intervals with nothing between them.
     */
    @Test
    void aResyncUnansweredThenABaselineOnlyIntervalThenABadAnswerDoesNotUnlock() {
        monitor.start(DEVICE);
        tick(ok(100));
        probe.resyncs.incrementAndGet();
        tick(() -> {
            throw new AudioBackendException("ASIO driver operation did not complete");
        });
        tick(ok(200));
        tick(ok(200));

        assertThat(events).isEmpty();
    }

    /** Ruling E121: bad, baseline-only, bad, bad unlocks once, dated at the first of the last two. */
    @Test
    void afterABaselineOnlyIntervalTwoBadIntervalsUnlockDatedByTheFirstOfThem() {
        monitor.start(DEVICE);
        tick(ok(100));
        probe.lastResyncNanos = 1_500L;
        probe.resyncs.incrementAndGet();
        tick(() -> {
            throw new AudioBackendException("ASIO driver operation did not complete");
        });
        tick(ok(200));
        long firstOfTheLastTwo = tick(notAdvancing());
        tick(notAdvancing());

        assertThat(events).containsExactly(new ClockLockEvent(DEVICE, 1, false, firstOfTheLastTwo));
    }

    /** Ruling E121: a resync while the periods advanced is bad even in an interval that only re-baselined. */
    @Test
    void aResyncInABaselineOnlyIntervalIsStillABadAnswer() {
        monitor.start(DEVICE);
        tick(ok(100));
        tick(() -> {
            throw new AudioBackendException("ASIO driver operation did not complete");
        });
        probe.lastResyncNanos = 6_000L;
        probe.resyncs.incrementAndGet();
        tick(ok(200));
        tick(notAdvancing());

        assertThat(events).containsExactly(new ClockLockEvent(DEVICE, 1, false, 6_000L));
    }

    /**
     * Ruling E122: a resync that arrived before the person's switch and was
     * not judged yet, and one in the first interval after it, belong to the
     * switch: with one bad interval after them, nothing unlocks.
     */
    @Test
    void resyncsJustBeforeAndRightAfterASourceSwitchAreNotABadAnswerForTheNewSource() {
        monitor.start(DEVICE);
        tick(ok(100));
        tick(ok(200));
        probe.resyncs.incrementAndGet();
        probe.sources = List.of(INTERNAL_IDLE, WORD_CLOCK_IDLE, new ClockSource(2, "ADAT", true, new ClockKind.Adat()));
        acceptedSwitch();
        tick(ok(300));
        probe.resyncs.incrementAndGet();
        tick(ok(400));
        tick(notAdvancing());

        assertThat(events).as("the first stream's lock only; no unlock of the new source")
                .extracting(ClockLockEvent::locked).containsOnly(true);
        assertThat(monitor.status().orElseThrow().sourceId()).as("premise: the new source").isEqualTo(2);
        assertThat(monitor.status().orElseThrow().lock()).isNotEqualTo(ClockLockStatus.Lock.UNLOCKED);
    }

    /**
     * Ruling E137, amending E122's Verify line: resyncs judged at the third
     * and fourth polls after the driver's answer unlock once.
     */
    @Test
    void resyncsJudgedAtTheThirdAndFourthPollsAfterTheDriversAnswerUnlockOnce() {
        monitor.start(DEVICE);
        tick(ok(100));
        tick(ok(200));
        probe.sources = List.of(INTERNAL_IDLE, WORD_CLOCK_IDLE, new ClockSource(2, "ADAT", true, new ClockKind.Adat()));
        acceptedSwitch();
        tick(ok(300));
        tick(ok(400));
        probe.lastResyncNanos = 8_000L;
        probe.resyncs.incrementAndGet();
        tick(ok(500));
        probe.resyncs.incrementAndGet();
        tick(ok(600));

        assertThat(events).filteredOn(event -> !event.locked())
                .containsExactly(new ClockLockEvent(DEVICE, 2, false, 8_000L));
    }

    /**
     * Ruling E123, a recorded limit: two resyncs at t and t + 0.6 s in one
     * interval, then a bad interval. The unlock is dated at the newest resync
     * the backend kept, never at the declaration.
     */
    @Test
    void anUnlockWhoseFirstIntervalHeldTwoResyncsIsDatedAtTheNewest() {
        long t = 10_000_000_000L;
        monitor.start(DEVICE);
        tick(ok(100));
        probe.lastResyncNanos = t;
        probe.resyncs.incrementAndGet();
        probe.lastResyncNanos = t + 600_000_000L;
        probe.resyncs.incrementAndGet();
        tick(ok(200));
        long declared = tick(notAdvancing());

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.locked()).isFalse();
            assertThat(event.evidenceNanos()).isEqualTo(t + 600_000_000L).isNotEqualTo(declared);
        });
    }

    /**
     * Ruling E137, the bound, with the switch answered just after a poll:
     * resyncs judged at the first and second polls after the driver's answer
     * give no unlock; at the third and fourth, one unlock at the fourth.
     */
    @Test
    void aSwitchAnsweredJustAfterAPollExemptsTheResyncsOfTheNextTwoPollsOnly() {
        monitor.start(DEVICE);
        tick(ok(100));
        tick(ok(200));

        acceptedSwitch();
        probe.resyncs.incrementAndGet();
        tick(ok(300));
        probe.resyncs.incrementAndGet();
        tick(ok(400));
        List<ClockLockEvent> afterTheSecond = List.copyOf(events);
        probe.lastResyncNanos = 7_000L;
        probe.resyncs.incrementAndGet();
        tick(ok(500));
        List<ClockLockEvent> afterTheThird = List.copyOf(events);
        probe.resyncs.incrementAndGet();
        tick(ok(600));

        assertThat(afterTheSecond).as("E137: never an unlock from resyncs at the first or second poll after the answer")
                .extracting(ClockLockEvent::locked).containsOnly(true);
        assertThat(afterTheThird).as("one bad answer publishes nothing").isEqualTo(afterTheSecond);
        assertThat(events).filteredOn(event -> !event.locked()).as("E137: the exemption ends at the second poll")
                .containsExactly(new ClockLockEvent(DEVICE, 1, false, 7_000L));
        assertThat(probe.driverResyncs()).as("each resync still counted by the backend").isEqualTo(4);
    }

    /**
     * Ruling E137, the bound, with the switch answered just before a poll
     * judges, while the poll's own driver call runs: the same sequence gives
     * the same result. That poll's source reading is stale and is not used.
     */
    @Test
    void aSwitchAnsweredWhileAPollRunsExemptsTheResyncsOfThatPollAndTheNextOnly() {
        monitor.start(DEVICE);
        tick(ok(100));
        tick(ok(200));

        probe.resyncs.incrementAndGet();
        tick(() -> {
            acceptedSwitch();
            return new AsioCapabilityShim.SamplePosition(0, 300, 0L);
        });
        probe.resyncs.incrementAndGet();
        tick(ok(400));
        List<ClockLockEvent> afterTheSecond = List.copyOf(events);
        probe.lastResyncNanos = 7_000L;
        probe.resyncs.incrementAndGet();
        tick(ok(500));
        List<ClockLockEvent> afterTheThird = List.copyOf(events);
        probe.resyncs.incrementAndGet();
        tick(ok(600));

        assertThat(afterTheSecond).as("E137: no unlock from the first or second poll after the answer")
                .extracting(ClockLockEvent::locked).containsOnly(true);
        assertThat(afterTheThird).as("one bad answer publishes nothing").isEqualTo(afterTheSecond);
        assertThat(events).filteredOn(event -> !event.locked()).as("E137: one unlock, at the fourth poll")
                .containsExactly(new ClockLockEvent(DEVICE, 1, false, 7_000L));
    }

    /**
     * Ruling E138: resyncs judged by two polls between the switch being sent
     * and the driver's answer are the switch's: no event, and the backend
     * counts both.
     */
    @Test
    void resyncsJudgedWhileTheDriverStillRunsTheSwitchAreNotABadAnswer() {
        monitor.start(DEVICE);
        tick(ok(100));
        tick(ok(200));
        List<ClockLockEvent> beforeTheSend = List.copyOf(events);

        monitor.switchSent();
        probe.resyncs.incrementAndGet();
        tick(ok(300));
        probe.resyncs.incrementAndGet();
        tick(ok(400));
        boolean pendingThroughout = monitor.isSwitchPending();
        monitor.switchAnswered(true);

        assertThat(pendingThroughout).as("premise: the driver had not answered").isTrue();
        assertThat(events).as("E138: no ClockLockEvent from resyncs between the send and the answer")
                .isEqualTo(beforeTheSend);
        assertThat(monitor.isLocked()).isTrue();
        assertThat(probe.driverResyncs()).as("the count rises by 2").isEqualTo(2);
    }

    /** Ruling E138: a switch whose driver call outlasts three polls, each with a resync, unlocks nothing. */
    @Test
    void aSwitchPendingForThreePollsWithAResyncInEachUnlocksNothing() {
        monitor.start(DEVICE);
        tick(ok(100));
        tick(ok(200));

        monitor.switchSent();
        for (int poll = 0; poll < 3; poll++) {
            probe.resyncs.incrementAndGet();
            tick(ok(300 + 100L * poll));
        }

        assertThat(events).as("E138: the exemption holds for as long as the driver has not answered")
                .extracting(ClockLockEvent::locked).containsOnly(true);
        assertThat(monitor.isLocked()).isTrue();
    }

    /**
     * Ruling E138: a refused switch resets nothing, writes no status, and
     * gets the same window as an accepted one: resyncs at the first and
     * second polls after the refusal give no unlock; at the third and
     * fourth, one.
     */
    @Test
    void aRefusedSwitchKeepsTheOldSourcesStateAndGetsTheSameWindow() {
        monitor.start(DEVICE);
        tick(ok(100));
        tick(ok(200));
        ClockLockStatus beforeTheSwitch = monitor.status().orElseThrow();
        int sourceReadsBefore = probe.sourceCalls;

        monitor.switchSent();
        monitor.switchAnswered(false);
        ClockLockStatus afterTheRefusal = monitor.status().orElseThrow();
        probe.resyncs.incrementAndGet();
        tick(ok(300));
        probe.resyncs.incrementAndGet();
        tick(ok(400));
        List<ClockLockEvent> afterTheSecond = List.copyOf(events);
        probe.lastResyncNanos = 7_000L;
        probe.resyncs.incrementAndGet();
        tick(ok(500));
        probe.resyncs.incrementAndGet();
        tick(ok(600));

        assertThat(afterTheRefusal).as("E138: no status write at a refusal").isSameAs(beforeTheSwitch);
        assertThat(probe.sourceCalls).as("the old source is kept, not read again").isEqualTo(sourceReadsBefore);
        assertThat(afterTheSecond).as("E138: no unlock from resyncs at the first and second polls after a refusal")
                .extracting(ClockLockEvent::locked).containsOnly(true);
        assertThat(events).filteredOn(event -> !event.locked()).as("one unlock from the third and fourth")
                .containsExactly(new ClockLockEvent(DEVICE, 1, false, 7_000L));
    }

    /**
     * Ruling E138: a refused switch while the old source shows UNLOCKED: it
     * stays UNLOCKED under the same serial, with no event, so no relock
     * notice and no relock mark.
     */
    @Test
    void aRefusedSwitchWhileUnlockedStaysUnlockedWithNoEvent() {
        monitor.start(DEVICE);
        tick(ok(100));
        tick(notAdvancing());
        tick(notAdvancing());
        ClockLockStatus unlocked = monitor.status().orElseThrow();
        List<ClockLockEvent> beforeTheSwitch = List.copyOf(events);

        monitor.switchSent();
        monitor.switchAnswered(false);
        tick(ok(200));
        ClockLockStatus after = monitor.status().orElseThrow();

        assertThat(unlocked.lock()).as("premise: unlocked").isEqualTo(ClockLockStatus.Lock.UNLOCKED);
        assertThat(after.lock()).as("E138: the refused switch reset nothing").isEqualTo(ClockLockStatus.Lock.UNLOCKED);
        assertThat(after.serial()).as("the same measurement").isEqualTo(unlocked.serial());
        assertThat(events).as("no relock notice or mark").isEqualTo(beforeTheSwitch);
    }

    /** Rulings E107, E124 and E138: an answer with no switch sent grants nothing; E106 judges the resyncs. */
    @Test
    void anAnswerWithNoSwitchSentGrantsNoWindow() {
        monitor.start(DEVICE);
        tick(ok(100));
        tick(ok(200));

        monitor.switchAnswered(false);
        probe.lastResyncNanos = 7_000L;
        probe.resyncs.incrementAndGet();
        tick(ok(300));
        probe.resyncs.incrementAndGet();
        tick(ok(400));

        assertThat(events).filteredOn(event -> !event.locked()).as("E106: two bad intervals unlock")
                .containsExactly(new ClockLockEvent(DEVICE, 1, false, 7_000L));
    }

    /** A stream that starts while a switch is pending begins with no window and no pending switch. */
    @Test
    void aStartWhileASwitchIsPendingBeginsWithNoWindow() {
        monitor.start(DEVICE);
        tick(ok(100));
        monitor.switchSent();

        monitor.start(DEVICE);
        boolean pendingAfterTheStart = monitor.isSwitchPending();
        monitor.switchAnswered(true);
        tick(ok(100));
        probe.lastResyncNanos = 7_000L;
        probe.resyncs.incrementAndGet();
        tick(ok(200));
        probe.resyncs.incrementAndGet();
        tick(ok(300));

        assertThat(pendingAfterTheStart).isFalse();
        assertThat(events).filteredOn(event -> !event.locked()).as("the new stream's resyncs are judged (E106)")
                .containsExactly(new ClockLockEvent(DEVICE, 1, false, 7_000L));
    }

    /**
     * Ruling E140: after an unlock, good positions in three intervals whose
     * periods stood still give no relock; three good intervals with
     * advancing periods then give one.
     */
    @Test
    void afterAnUnlockFrozenGoodIntervalsNeverRelockAndAdvancingOnesDo() {
        monitor.start(DEVICE);
        tick(ok(100));
        tick(notAdvancing());
        tick(notAdvancing());
        probe.periodsAdvance = false;
        tick(ok(200));
        tick(ok(300));
        tick(ok(400));
        List<ClockLockEvent> afterTheFrozen = List.copyOf(events);
        probe.periodsAdvance = true;
        tick(ok(500));
        tick(ok(600));
        long third = tick(ok(700));

        assertThat(afterTheFrozen).as("E140: never a relock from intervals whose periods stood still")
                .extracting(ClockLockEvent::locked).containsExactly(false);
        assertThat(events).as("three advancing good intervals relock")
                .containsExactly(afterTheFrozen.getFirst(), new ClockLockEvent(DEVICE, 1, true, third));
    }

    /** Ruling E140: good, good, frozen-but-good, good after an unlock is no relock. */
    @Test
    void aFrozenGoodIntervalBreaksTheGoodRun() {
        monitor.start(DEVICE);
        tick(ok(100));
        tick(notAdvancing());
        tick(notAdvancing());
        tick(ok(200));
        tick(ok(300));
        probe.periodsAdvance = false;
        tick(ok(400));
        probe.periodsAdvance = true;
        tick(ok(500));

        assertThat(events).as("E140: good, good, frozen, good: no relock")
                .extracting(ClockLockEvent::locked).containsExactly(false);
    }

    /** The person's switch, sent and accepted. */
    private void acceptedSwitch() {
        monitor.switchSent();
        monitor.switchAnswered(true);
    }

    /** Queues the next answer, advances the clock, polls once; returns the poll's time. */
    private long tick(Supplier<AsioCapabilityShim.SamplePosition> answer) {
        probe.answers.addLast(answer);
        long now = clock.addAndGet(1_000L);
        monitor.pollOnce();
        return now;
    }

    private static Supplier<AsioCapabilityShim.SamplePosition> ok(long position) {
        return () -> new AsioCapabilityShim.SamplePosition(0, position, 0L);
    }

    private static Supplier<AsioCapabilityShim.SamplePosition> notAdvancing() {
        return () -> new AsioCapabilityShim.SamplePosition(
                AsioClockLockMonitor.ASE_SP_NOT_ADVANCING, 0L, 0L);
    }

    private static Supplier<AsioCapabilityShim.SamplePosition> noClock() {
        return () -> new AsioCapabilityShim.SamplePosition(
                AsioClockLockMonitor.ASE_NO_CLOCK, 0L, 0L);
    }

    /** A probe whose answers the test queues; device periods advance with every poll. */
    private static final class FakeProbe implements AsioClockLockMonitor.Probe {
        volatile List<ClockSource> sources = List.of();
        volatile boolean sourcesFail;
        volatile boolean positionAvailable = true;
        volatile boolean periodsAdvance = true;
        volatile boolean panelOpen;
        final Deque<Supplier<AsioCapabilityShim.SamplePosition>> answers = new ArrayDeque<>();
        final AtomicLong resyncs = new AtomicLong();
        volatile long lastResyncNanos;
        int positionCalls;
        int sourceCalls;
        private long periods;

        @Override public List<ClockSource> clockSources(DeviceId device) {
            sourceCalls++;
            if (sourcesFail) {
                throw new AudioBackendException("ASIO driver operation did not complete");
            }
            return sources;
        }

        @Override public boolean samplePositionAvailable() {
            return positionAvailable;
        }

        @Override public AsioCapabilityShim.SamplePosition samplePosition() {
            positionCalls++;
            Supplier<AsioCapabilityShim.SamplePosition> next = answers.pollFirst();
            if (next == null) {
                throw new AudioBackendException("no answer queued");
            }
            return next.get();
        }

        @Override public long deviceCallbackPeriods() {
            if (periodsAdvance) {
                periods += 48;
            }
            return periods;
        }

        @Override public long driverResyncs() {
            return resyncs.get();
        }

        @Override public long lastDriverResyncNanos() {
            return lastResyncNanos;
        }

        @Override public boolean controlPanelOpen() {
            return panelOpen;
        }
    }
}
