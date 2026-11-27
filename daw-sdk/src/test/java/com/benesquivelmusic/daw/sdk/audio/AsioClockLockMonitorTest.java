package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;
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
    /** The position polls sent before {@link #lockedThroughAnOpenPanelWithResyncs()} opened the panel. */
    private int positionsBeforeThePanel;

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

    /** Ruling E62: after a good answer of its measurement, two bad answers unlock once, dated by the first. */
    @Test
    void twoBadAnswersOnAnExternalSourceUnlockOnceDatedByTheFirst() {
        monitor.start(DEVICE);
        tick(ok(100));
        tick(ok(200));
        events.clear();
        long firstBad = tick(ok(200));
        assertThat(events).as("one bad answer never unlocks").isEmpty();

        tick(ok(200));
        tick(ok(200));
        tick(ok(200));

        assertThat(events).containsExactly(new ClockLockEvent(DEVICE, 1, false, firstBad));
        assertThat(monitor.status().orElseThrow().transitions()).containsExactly(transition(1, false, firstBad));
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
        tick(ok(150));
        events.clear();
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
        long firstStart = clock.get();
        monitor.start(DEVICE);
        tick(ok(100));
        tick(notAdvancing());
        tick(notAdvancing());
        assertThat(monitor.isLocked()).isFalse();

        monitor.stop();
        long secondStart = clock.get();
        monitor.start(DEVICE);
        assertThat(monitor.isLocked()).as("the new stream carries no unlock").isTrue();
        assertThat(monitor.isMeasured()).as("and is not yet measured").isFalse();
        tick(ok(500));
        tick(ok(500));
        tick(ok(500));

        assertThat(events).as("two bad answers of the new stream are a new unlock")
                .containsExactly(new ClockLockEvent(DEVICE, 1, false, firstStart),
                        new ClockLockEvent(DEVICE, 1, false, secondStart));
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
        assertThat(unlocked).as("two bad answers, dated by the first, and kept as the measurement's first change")
                .isEqualTo(new ClockLockStatus(DEVICE, 1, sourceRead.serial(), ClockLockStatus.Lock.UNLOCKED,
                        firstBad, List.of(transition(1, false, firstBad))));
        assertThat(relocked).as("the relock keeps the serial: same serial, was UNLOCKED, is LOCKED")
                .isEqualTo(new ClockLockStatus(DEVICE, 1, sourceRead.serial(), ClockLockStatus.Lock.LOCKED, third,
                        List.of(transition(1, false, firstBad), transition(2, true, third))));
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

    /** Every unlock and relock of one measurement is kept in its status, in order, each with its own evidence. */
    @Test
    void everyUnlockAndRelockOfAMeasurementIsKeptInItsStatusInOrder() {
        monitor.start(DEVICE);
        tick(ok(100));
        tick(ok(200));
        long t3 = tick(ok(200));
        tick(noClock());
        tick(ok(300));
        tick(ok(400));
        long t7 = tick(ok(500));
        long t8 = tick(ok(500));
        tick(noClock());
        ClockLockStatus status = monitor.status().orElseThrow();

        assertThat(status.transitions()).as("the first measurement is no change; each unlock at its first bad answer")
                .containsExactly(transition(1, false, t3), transition(2, true, t7), transition(3, false, t8));
        assertThat(status.evidenceNanos()).isEqualTo(t8);
    }

    /**
     * A measurement whose first decision is an unlock has that unlock as its first change, dated at the stream's
     * start (ruling E269).
     */
    @Test
    void aFirstMeasurementThatIsAnUnlockIsTransitionOne() {
        long started = clock.get();
        monitor.start(DEVICE);
        tick(ok(100));
        long firstBad = tick(notAdvancing());
        tick(notAdvancing());

        assertThat(firstBad).as("premise: the first bad answer came after the start").isGreaterThan(started);
        assertThat(monitor.status().orElseThrow().lock()).isEqualTo(ClockLockStatus.Lock.UNLOCKED);
        assertThat(monitor.status().orElseThrow().transitions()).containsExactly(transition(1, false, started));
        assertThat(events).containsExactly(new ClockLockEvent(DEVICE, 1, false, started));
    }

    /** A source change and a new start each begin a measurement with no changes, counted again from 1. */
    @Test
    void aNewMeasurementStartsItsTransitionsAfresh() {
        monitor.start(DEVICE);
        tick(ok(100));
        tick(notAdvancing());
        tick(notAdvancing());
        ClockLockStatus unlocked = monitor.status().orElseThrow();

        probe.sources = List.of(INTERNAL_IDLE, WORD_CLOCK_IDLE, new ClockSource(2, "ADAT", true, new ClockKind.Adat()));
        acceptedSwitch();
        long newSourceRead = tick(ok(200));
        ClockLockStatus newSource = monitor.status().orElseThrow();
        tick(notAdvancing());
        tick(notAdvancing());
        ClockLockStatus newSourceUnlocked = monitor.status().orElseThrow();
        monitor.stop();
        long restartedAt = clock.get();
        monitor.start(DEVICE);
        ClockLockStatus restarted = monitor.status().orElseThrow();
        tick(ok(300));
        tick(notAdvancing());
        tick(notAdvancing());

        assertThat(unlocked.transitions()).as("premise: an unlock").hasSize(1);
        assertThat(newSource.transitions()).as("a source change empties them").isEmpty();
        assertThat(newSourceUnlocked.transitions()).as("the new source's first unlock is its first change")
                .containsExactly(transition(1, false, newSourceRead));
        assertThat(restarted.transitions()).as("a start empties them").isEmpty();
        assertThat(monitor.status().orElseThrow().transitions()).containsExactly(transition(1, false, restartedAt));
    }

    /**
     * Ruling E269: a newly chosen source's measurement starts at the poll that
     * took it up, so an unlock that is its first decision is dated there, not
     * at the stream's start and not at its own first bad answer.
     */
    @Test
    void anUnlockThatDecidesANewlyChosenSourceIsDatedAtThePollThatTookItUp() {
        commonStart();
        probe.sources = List.of(INTERNAL_IDLE, WORD_CLOCK_IDLE, new ClockSource(2, "ADAT", true, new ClockKind.Adat()));
        acceptedSwitch();
        long firstRead = tick(ok(300));
        long firstBad = tick(noClock());
        tick(noClock());

        assertThat(firstBad).as("premise: the first bad answer came after the poll that took the source up")
                .isGreaterThan(firstRead);
        assertThat(monitor.status().orElseThrow().transitions()).containsExactly(transition(1, false, firstRead));
        assertThat(events).containsExactly(new ClockLockEvent(DEVICE, 2, false, firstRead));
    }

    /**
     * Ruling E269: a stream opened on the internal clock, the source with id
     * 0, whose source is then switched to an external one, starts the
     * external source's measurement at the poll that takes it up, so an
     * unlock that is its first decision is dated there, not at the stream's
     * start.
     */
    @Test
    void anUnlockThatDecidesAnExternalSourceChosenAfterTheInternalClockIsDatedAtThePollThatTookItUp() {
        probe.sources = List.of(INTERNAL_CURRENT, WORD_CLOCK_IDLE);
        long started = clock.get();
        monitor.start(DEVICE);
        tickWithNothingSent();
        ClockLockStatus onTheInternalClock = monitor.status().orElseThrow();

        probe.sources = List.of(INTERNAL_IDLE, WORD_CLOCK_CURRENT);
        acceptedSwitch();
        long firstRead = tick(ok(300));
        long firstBad = tick(noClock());
        tick(noClock());

        assertThat(onTheInternalClock.sourceId()).as("premise: the stream opened on the internal clock").isZero();
        assertThat(onTheInternalClock.lock()).isEqualTo(ClockLockStatus.Lock.NOT_MEASURABLE);
        assertThat(firstRead).as("premise: the external source was taken up after the stream's start")
                .isGreaterThan(started);
        assertThat(firstBad).as("premise: the first bad answer came after the poll that took the source up")
                .isGreaterThan(firstRead);
        assertThat(monitor.status().orElseThrow().transitions()).containsExactly(transition(1, false, firstRead));
        assertThat(events).containsExactly(new ClockLockEvent(DEVICE, 1, false, firstRead));
    }

    /** More changes than a status keeps: the oldest go, and the newest sequence still counts every change. */
    @Test
    void theStatusKeepsTheNewestTransitionsAndItsSequenceCountsTheDropped() {
        monitor.start(DEVICE);
        tick(ok(100));
        tick(ok(200));
        long position = 200;
        int changes = AsioClockLockMonitor.TRANSITIONS_KEPT + 3;
        for (int change = 1; change <= changes; change++) {
            if (change % 2 == 1) {
                tick(ok(position));
                tick(noClock());
            } else {
                for (int good = 0; good < AsioClockLockMonitor.GOOD_ANSWERS_TO_RELOCK; good++) {
                    position += 100;
                    tick(ok(position));
                }
            }
        }
        ClockLockStatus status = monitor.status().orElseThrow();

        assertThat(status.lastSequence()).isEqualTo(changes);
        assertThat(status.transitions()).hasSize(AsioClockLockMonitor.TRANSITIONS_KEPT);
        assertThat(status.transitions().getFirst().sequence()).as("the oldest are the ones dropped").isEqualTo(4L);
        assertThat(status.lock()).isEqualTo(ClockLockStatus.Lock.UNLOCKED);
    }

    /** A consumer woken by an unlock or a relock already finds that change in the status it reads. */
    @Test
    void atEachEventTheStatusAlreadyHoldsItsTransition() {
        List<ClockLockEvent> announced = new CopyOnWriteArrayList<>();
        List<Optional<ClockLockStatus.Transition>> newestAtEachEvent = new CopyOnWriteArrayList<>();
        SubmissionPublisher<ClockLockEvent> publisher = new SubmissionPublisher<>(Runnable::run, 16);
        AsioClockLockMonitor watched = new AsioClockLockMonitor(probe, clock::get, publisher,
                Duration.ofSeconds(1), body -> new Thread(() -> { }));
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(ClockLockEvent event) {
                announced.add(event);
                List<ClockLockStatus.Transition> kept = watched.status().orElseThrow().transitions();
                newestAtEachEvent.add(kept.isEmpty() ? Optional.empty() : Optional.of(kept.getLast()));
            }
            @Override public void onError(Throwable t) { }
            @Override public void onComplete() { }
        });
        watched.start(DEVICE);
        for (Supplier<AsioCapabilityShim.SamplePosition> answer : List.of(ok(100), ok(200), ok(200), noClock())) {
            probe.answers.addLast(answer);
            clock.addAndGet(1_000L);
            watched.pollOnce();
        }
        assertThat(newestAtEachEvent).as("the first measurement is no change; the unlock is in the status it announces")
                .containsExactly(Optional.empty(), Optional.of(transition(1, false, announced.get(1).evidenceNanos())));
        for (Supplier<AsioCapabilityShim.SamplePosition> answer : List.of(ok(300), ok(400), ok(500))) {
            probe.answers.addLast(answer);
            clock.addAndGet(1_000L);
            watched.pollOnce();
        }

        assertThat(newestAtEachEvent).as("so is the relock").hasSize(3)
                .endsWith(Optional.of(transition(2, true, announced.get(2).evidenceNanos())));
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
        tick(ok(150));
        events.clear();
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
        probe.closePanel();
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
        probe.closePanel();
        tick(ok(200));
        ClockLockStatus after = monitor.status().orElseThrow();

        assertThat(after.sourceId()).as("the source the panel chose").isEqualTo(2);
        assertThat(after.serial()).as("a new measurement").isNotEqualTo(before);
    }

    /** Ruling E227: a session of the driver's panel that no tick saw open still has the source read again. */
    @Test
    void aPanelSessionBetweenTwoPollsMakesTheSourceBeReadAgain() {
        monitor.start(DEVICE);
        tick(ok(100));
        long before = monitor.status().orElseThrow().serial();

        probe.sources = List.of(INTERNAL_IDLE, WORD_CLOCK_IDLE, new ClockSource(2, "ADAT", true, new ClockKind.Adat()));
        probe.closePanel();
        tick(ok(200));
        ClockLockStatus after = monitor.status().orElseThrow();

        assertThat(after.sourceId()).as("the source the panel chose").isEqualTo(2);
        assertThat(after.serial()).as("a new measurement").isNotEqualTo(before);
    }

    /**
     * Ruling E248: while the driver's control panel is open, its resyncs
     * are the person's change, never a bad answer: the status, its serial
     * and its transitions stay as they were, and nothing is sent.
     */
    @Test
    void resyncsWhileTheDriversControlPanelIsOpenNeverDeclareTheClockLost() {
        ClockLockStatus locked = lockedThroughAnOpenPanelWithResyncs();

        assertThat(locked.lock()).as("premise: measured locked").isEqualTo(ClockLockStatus.Lock.LOCKED);
        assertThat(monitor.status()).as("the status is untouched while the panel is open").contains(locked);
        assertThat(locked.transitions()).isEmpty();
        assertThat(events).isEmpty();
        assertThat(probe.positionCalls).as("nothing is sent while the panel is open").isEqualTo(positionsBeforeThePanel);
    }

    /**
     * Ruling E249: the intervals of the panel's close and the next belong to
     * the person's change; resyncs in the third and fourth unlock once, dated
     * at the third's.
     */
    @Test
    void theIntervalOfThePanelsCloseAndTheNextAreExemptAndTheThirdIsJudged() {
        ClockLockStatus locked = lockedThroughAnOpenPanelWithResyncs();
        probe.closePanel();

        resync();
        tick(ok(300));
        ClockLockStatus.Lock afterTheFirst = lockNow();
        resync();
        tick(ok(400));
        ClockLockStatus.Lock afterTheSecond = lockNow();
        long third = resync();
        tick(ok(500));
        ClockLockStatus.Lock afterTheThird = lockNow();
        resync();
        tick(ok(600));
        ClockLockStatus after = monitor.status().orElseThrow();

        assertThat(List.of(afterTheFirst, afterTheSecond, afterTheThird)).as("two exempt, then one bad answer")
                .containsExactly(ClockLockStatus.Lock.LOCKED, ClockLockStatus.Lock.LOCKED, ClockLockStatus.Lock.LOCKED);
        assertThat(after.lock()).isEqualTo(ClockLockStatus.Lock.UNLOCKED);
        assertThat(after.serial()).as("the same measurement").isEqualTo(locked.serial());
        assertThat(after.transitions()).containsExactly(transition(1, false, third));
        assertThat(events).containsExactly(new ClockLockEvent(DEVICE, 1, false, third));
    }

    /** Ruling E249: a session that opened and closed between two ticks gets the same two intervals. */
    @Test
    void aPanelSessionNoPollSawOpenExemptsItsCloseIntervalAndTheNext() {
        commonStart();
        int sourcesBefore = probe.sourceCalls;
        probe.closePanel();

        resync();
        tick(ok(300));
        int sourcesAfterTheFirst = probe.sourceCalls;
        ClockLockStatus.Lock afterTheFirst = lockNow();
        resync();
        tick(ok(400));
        ClockLockStatus.Lock afterTheSecond = lockNow();
        long third = resync();
        tick(ok(500));
        resync();
        tick(ok(600));
        ClockLockStatus after = monitor.status().orElseThrow();

        assertThat(sourcesAfterTheFirst).as("the poll that counts the session reads the source again")
                .isEqualTo(sourcesBefore + 1);
        assertThat(List.of(afterTheFirst, afterTheSecond)).as("both exempt")
                .containsExactly(ClockLockStatus.Lock.LOCKED, ClockLockStatus.Lock.LOCKED);
        assertThat(after.lock()).isEqualTo(ClockLockStatus.Lock.UNLOCKED);
        assertThat(after.transitions()).containsExactly(transition(1, false, third));
    }

    /**
     * Ruling E249: the level is read before the closed-session count, so a
     * panel that closes between the two reads leaves a tick that read it open
     * (sends nothing, judges no resync), and the next tick reads the source
     * again. Read the other way round, that tick would send a poll to the
     * driver; the reads after its evidence would still leave it unjudged.
     */
    @Test
    void aPollThatReadsThePanelClosedAlsoSeesItsSession() {
        commonStart();
        resync();
        tick(ok(300));
        probe.panelOpen = true;
        probe.afterTheNextSessionCountRead = probe::closePanel;

        resync();
        int positionsBefore = probe.positionCalls;
        tickWithNothingSent();
        int positionsAfterThePanelClosed = probe.positionCalls;
        ClockLockStatus.Lock afterThePanelClosed = lockNow();
        int sourcesBefore = probe.sourceCalls;
        tick(ok(400));

        assertThat(positionsAfterThePanelClosed).as("the tick that read the panel open sent no poll")
                .isEqualTo(positionsBefore);
        assertThat(afterThePanelClosed).as("the tick that read the panel open judged no resync")
                .isEqualTo(ClockLockStatus.Lock.LOCKED);
        assertThat(probe.sourceCalls).as("the next tick sees the session").isEqualTo(sourcesBefore + 1);
        assertThat(events).as("no unlock").isEmpty();
    }

    /**
     * Ruling E250: in a measurement that had found the clock good, a clock
     * lost while the panel is open is declared from the first bad poll after
     * the close, dated there, never before the close.
     */
    @Test
    void aClockLostWhileThePanelIsOpenIsDeclaredFromTheFirstBadPollAfterItCloses() {
        commonStart();
        probe.panelOpen = true;
        for (int i = 0; i < 3; i++) {
            tickWithNothingSent();
        }
        long closedAt = clock.get();
        probe.closePanel();
        long first = tick(noClock());
        ClockLockStatus.Lock afterTheFirst = lockNow();
        tick(noClock());

        assertThat(afterTheFirst).as("one bad answer").isEqualTo(ClockLockStatus.Lock.LOCKED);
        assertThat(monitor.status().orElseThrow().transitions()).containsExactly(transition(1, false, first));
        assertThat(events).containsExactly(new ClockLockEvent(DEVICE, 1, false, first));
        assertThat(first).as("never dated before the close").isGreaterThan(closedAt);
    }

    /**
     * Ruling E251: an unlock declared before the panel opened stands through
     * the session, which starts nothing afresh; after the close a baseline
     * and three good polls relock it.
     */
    @Test
    void anUnlockDeclaredBeforeThePanelOpenedStandsAcrossItAndRelocksAfterThreeGoodPollsAfterTheClose() {
        commonStart();
        long firstBad = tick(ok(200));
        tick(noClock());
        ClockLockStatus unlocked = monitor.status().orElseThrow();
        int eventsBefore = events.size();
        probe.panelOpen = true;
        resync();
        tickWithNothingSent();
        tickWithNothingSent();
        resync();
        tickWithNothingSent();
        List<ClockLockEvent> whileOpen = List.copyOf(events.subList(eventsBefore, events.size()));
        probe.closePanel();
        tick(ok(300));
        tick(ok(400));
        tick(ok(500));
        ClockLockStatus.Lock afterTheThird = lockNow();
        long relock = tick(ok(600));
        ClockLockStatus after = monitor.status().orElseThrow();

        assertThat(unlocked.transitions()).as("premise: unlocked").containsExactly(transition(1, false, firstBad));
        assertThat(whileOpen).isEmpty();
        assertThat(afterTheThird).as("a baseline and two good polls").isEqualTo(ClockLockStatus.Lock.UNLOCKED);
        assertThat(after.lock()).isEqualTo(ClockLockStatus.Lock.LOCKED);
        assertThat(after.serial()).as("the same measurement").isEqualTo(unlocked.serial());
        assertThat(after.transitions()).containsExactly(transition(1, false, firstBad), transition(2, true, relock));
        assertThat(events.subList(eventsBefore, events.size()))
                .containsExactly(new ClockLockEvent(DEVICE, 1, true, relock));
    }

    /**
     * Ruling E249: the runs are broken at a session's close, so a bad
     * interval before a session no poll saw open does not add up with the
     * first bad poll after it.
     */
    @Test
    void aBadIntervalBeforeAPanelSessionNoPollSawOpenIsNotCountedWithItsClose() {
        commonStart();
        long beforeTheSession = tick(notAdvancing());
        probe.closePanel();
        long first = tick(noClock());
        ClockLockStatus.Lock afterTheFirst = lockNow();
        tick(noClock());

        assertThat(afterTheFirst).as("one bad answer since the close").isEqualTo(ClockLockStatus.Lock.LOCKED);
        assertThat(monitor.status().orElseThrow().transitions())
                .as("dated at the first bad poll after the close, not at " + beforeTheSession)
                .containsExactly(transition(1, false, first));
    }

    /**
     * Ruling E260: the panel closes between a tick's read of the level and
     * its read of the count. That tick read the panel open and is a switch
     * interval in its own right; the first two ticks that read it closed are
     * the two, and the third is judged.
     */
    @Test
    void aPanelThatClosesBetweenAPollsTwoReadsStillExemptsTheTwoPollsAfterIt() {
        commonStart();
        int sourcesBefore = probe.sourceCalls;
        probe.panelOpen = true;
        probe.afterTheNextPanelLevelRead = probe::closePanel;

        resync();
        tickWithNothingSent();
        ClockLockStatus.Lock afterTheClose = lockNow();
        int sourcesAfterTheClose = probe.sourceCalls;
        resync();
        tick(ok(300));
        ClockLockStatus.Lock afterTheFirst = lockNow();
        int sourcesAfterTheFirst = probe.sourceCalls;
        resync();
        tick(ok(400));
        ClockLockStatus.Lock afterTheSecond = lockNow();
        long third = resync();
        tick(ok(500));
        ClockLockStatus.Lock afterTheThird = lockNow();
        resync();
        tick(ok(600));
        ClockLockStatus after = monitor.status().orElseThrow();

        assertThat(List.of(afterTheClose, afterTheFirst, afterTheSecond, afterTheThird))
                .as("the tick that read the panel open, two exempt, then one bad answer")
                .containsExactly(ClockLockStatus.Lock.LOCKED, ClockLockStatus.Lock.LOCKED,
                        ClockLockStatus.Lock.LOCKED, ClockLockStatus.Lock.LOCKED);
        assertThat(sourcesAfterTheClose).as("nothing is sent by the tick that read the panel open")
                .isEqualTo(sourcesBefore);
        assertThat(sourcesAfterTheFirst).as("the first tick that reads it closed reads the source again")
                .isEqualTo(sourcesBefore + 1);
        assertThat(after.lock()).isEqualTo(ClockLockStatus.Lock.UNLOCKED);
        assertThat(after.transitions()).containsExactly(transition(1, false, third));
        assertThat(events).containsExactly(new ClockLockEvent(DEVICE, 1, false, third));
    }

    /**
     * Rulings E248 and E260: a panel session that opens after a tick's first
     * reads, while that tick's poll waits behind it, and closes before the poll
     * returns, is judged by no tick; the next tick that reads the panel closed
     * counts it, reads the source again and gives the close its two exempt
     * intervals.
     */
    @Test
    void aPanelSessionThatOpensAndClosesWhileATicksPollWaitsIsNotJudged() {
        commonStart();
        resync();
        tick(ok(300));
        tick(() -> {
            probe.panelOpen = true;
            resync();
            probe.closePanel();
            return new AsioCapabilityShim.SamplePosition(0, 400L, 0L);
        });
        ClockLockStatus.Lock afterTheSession = lockNow();
        List<ClockLockStatus.Transition> transitionsAfterTheSession = monitor.status().orElseThrow().transitions();
        List<ClockLockEvent> eventsAfterTheSession = List.copyOf(events);
        int sourcesBefore = probe.sourceCalls;
        resync();
        tick(ok(500));
        int sourcesAfterTheFirst = probe.sourceCalls;
        ClockLockStatus.Lock afterTheFirst = lockNow();
        resync();
        tick(ok(600));
        ClockLockStatus.Lock afterTheSecond = lockNow();
        long third = resync();
        tick(ok(700));
        resync();
        tick(ok(800));

        assertThat(afterTheSession).as("the tick the session overlapped did not judge its resync")
                .isEqualTo(ClockLockStatus.Lock.LOCKED);
        assertThat(transitionsAfterTheSession).isEmpty();
        assertThat(eventsAfterTheSession).isEmpty();
        assertThat(sourcesAfterTheFirst).as("the next tick that reads the panel closed reads the source again")
                .isEqualTo(sourcesBefore + 1);
        assertThat(List.of(afterTheFirst, afterTheSecond)).as("both exempt")
                .containsExactly(ClockLockStatus.Lock.LOCKED, ClockLockStatus.Lock.LOCKED);
        assertThat(lockNow()).isEqualTo(ClockLockStatus.Lock.UNLOCKED);
        assertThat(monitor.status().orElseThrow().transitions()).containsExactly(transition(1, false, third));
        assertThat(events).containsExactly(new ClockLockEvent(DEVICE, 1, false, third));
    }

    /**
     * Ruling E248: a panel session that opens after a tick's first reads and
     * switches the driver to its internal clock before that tick's poll
     * returns. The old source's answer, its third good one, is judged by no
     * tick, so nothing relocks for it, and the next tick reads the new source.
     */
    @Test
    void aPanelSessionThatSwitchesTheSourceWhileATicksPollWaitsRelocksNothingForTheOldSource() {
        commonStart();
        tick(noClock());
        tick(noClock());
        tick(ok(400));
        tick(ok(500));
        List<ClockLockEvent> beforeTheSession = List.copyOf(events);
        tick(() -> {
            probe.panelOpen = true;
            probe.sources = List.of(INTERNAL_CURRENT, WORD_CLOCK_IDLE);
            probe.closePanel();
            return new AsioCapabilityShim.SamplePosition(0, 600L, 0L);
        });
        ClockLockStatus.Lock afterTheSession = lockNow();
        tickWithNothingSent();
        ClockLockStatus afterTheReRead = monitor.status().orElseThrow();

        assertThat(beforeTheSession).as("premise: unlocked, with two good answers since")
                .extracting(ClockLockEvent::locked).containsExactly(false);
        assertThat(afterTheSession).isEqualTo(ClockLockStatus.Lock.UNLOCKED);
        assertThat(events).as("no relock for the old source").isEqualTo(beforeTheSession);
        assertThat(afterTheReRead.sourceId()).as("the source the panel chose").isZero();
        assertThat(afterTheReRead.lock()).isEqualTo(ClockLockStatus.Lock.NOT_MEASURABLE);
    }

    /**
     * Ruling E248: a panel that opens while a tick's poll waits and is still
     * open when that tick judges leaves the tick unjudged, its resync
     * included; the tick after the close reads the source again.
     */
    @Test
    void aPanelThatOpensWhileATicksPollWaitsAndIsStillOpenWhenItJudgesIsNotJudged() {
        commonStart();
        resync();
        tick(ok(300));
        tick(() -> {
            probe.panelOpen = true;
            resync();
            return new AsioCapabilityShim.SamplePosition(0, 400L, 0L);
        });
        ClockLockStatus whileOpen = monitor.status().orElseThrow();
        List<ClockLockEvent> eventsWhileOpen = List.copyOf(events);
        int sourcesBefore = probe.sourceCalls;
        probe.closePanel();
        tick(ok(500));

        assertThat(whileOpen.lock()).as("the resync of the tick that read the panel open after its poll is not judged")
                .isEqualTo(ClockLockStatus.Lock.LOCKED);
        assertThat(whileOpen.transitions()).isEmpty();
        assertThat(eventsWhileOpen).isEmpty();
        assertThat(probe.sourceCalls).as("the tick after the close reads the source again")
                .isEqualTo(sourcesBefore + 1);
    }

    /**
     * Rulings E249 and E260: a panel that opens while a tick's poll waits and
     * closes between that tick's two reads after its evidence leaves the tick
     * unjudged, its resync included, because the level is read first and
     * reads the panel still open. Read the other way round, the count would
     * be read before the close and the level after it, and the resync would
     * be judged.
     */
    @Test
    void aPanelThatClosesBetweenTheReadsAfterATicksPollLeavesThatTickUnjudged() {
        commonStart();
        resync();
        tick(ok(300));
        AtomicBoolean closed = new AtomicBoolean();
        Runnable closeAtTheFirstOfTheTwoReads = () -> {
            if (closed.compareAndSet(false, true)) {
                probe.closePanel();
            }
        };
        tick(() -> {
            probe.panelOpen = true;
            resync();
            probe.afterTheNextPanelLevelRead = closeAtTheFirstOfTheTwoReads;
            probe.afterTheNextSessionCountRead = closeAtTheFirstOfTheTwoReads;
            return new AsioCapabilityShim.SamplePosition(0, 400L, 0L);
        });

        assertThat(closed).as("premise: the panel closed during that tick's reads after its poll").isTrue();
        assertThat(lockNow()).as("the tick that read the panel open after its poll judged no resync")
                .isEqualTo(ClockLockStatus.Lock.LOCKED);
        assertThat(monitor.status().orElseThrow().transitions()).isEmpty();
        assertThat(events).isEmpty();
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
        tick(ok(150));
        events.clear();
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
        tick(ok(150));
        events.clear();
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
     * Ruling E279: after a good answer, a bad interval and a good one, two
     * resyncs at t and t + 0.6 s in one interval, then a bad interval. The
     * unlock is dated at the first request of its first bad interval, never at
     * the newest, at an earlier run's request or at the declaration.
     */
    @Test
    void anUnlockFromResyncsIsDatedAtTheFirstRequestOfItsFirstBadInterval() {
        long t = 10_000_000_000L;
        monitor.start(DEVICE);
        tick(ok(100));
        tick(ok(150));
        events.clear();
        probe.arrive(t - 2_000_000_000L);
        tick(ok(200));
        tick(ok(300));
        probe.arrive(t);
        probe.arrive(t + 600_000_000L);
        tick(ok(400));
        long declared = tick(notAdvancing());

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.locked()).isFalse();
            assertThat(event.evidenceNanos()).isEqualTo(t).isNotEqualTo(declared);
        });
    }

    /**
     * Ruling E279: a bad interval whose first request's arrival the backend no
     * longer keeps is dated at the interval's start, before any of its
     * requests arrived.
     */
    @Test
    void aBadIntervalWhoseFirstArrivalIsNoLongerKeptIsDatedAtItsStart() {
        monitor.start(DEVICE);
        tick(ok(100));
        long intervalStart = tick(ok(150));
        events.clear();
        probe.forgotten.add(probe.arrive(intervalStart + 500L));
        probe.arrive(intervalStart + 600L);
        tick(ok(200));
        tick(notAdvancing());

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.locked()).isFalse();
            assertThat(event.evidenceNanos()).as("the interval's start, before its first request's own arrival")
                    .isEqualTo(intervalStart).isLessThan(intervalStart + 500L);
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
        tick(ok(150));
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
     * Ruling E271: the stream that answers a driver's reset request starts with its first two polls as switch
     * intervals, so the resync of its second poll is the reset's and only those of its third and fourth unlock.
     */
    @Test
    void aStreamThatAnswersADriverResetJudgesNoResyncInItsFirstTwoPolls() {
        long started = reopenedForADriverReset();

        assertOnlyTheResyncsOfTheThirdAndFourthPollsUnlock(started);
    }

    /** Ruling E271: the reset's window exempts resyncs only; a bad poll answer in it is judged as on any stream. */
    @Test
    void aBadPollAnswerInTheFirstPollsOfAStreamThatAnswersAResetIsStillJudged() {
        long started = reopenedForADriverReset();
        tick(ok(1_000));
        tick(noClock());
        ClockLockStatus.Lock afterTheSecond = lockNow();
        tick(noClock());

        assertThat(afterTheSecond).as("one bad answer never unlocks").isEqualTo(ClockLockStatus.Lock.CHECKING);
        assertThat(lockNow()).as("the window's second poll counted its bad answer, so the third poll's unlocks")
                .isEqualTo(ClockLockStatus.Lock.UNLOCKED);
        assertThat(monitor.status().orElseThrow().transitions()).containsExactly(transition(1, false, started));
        assertThat(events).containsExactly(new ClockLockEvent(DEVICE, 1, false, started));
    }

    /** Ruling E271: a stream started with no reset request since its backend's previous start gets no window. */
    @Test
    void aStreamWithNoResetSinceItsBackendsLastOpenIsJudgedFromItsSecondPoll() {
        reopenedForADriverReset();
        tick(ok(1_000));
        monitor.stop();
        events.clear();
        long started = clock.get();
        monitor.start(DEVICE);
        tick(ok(2_000));
        resync();
        tick(ok(2_100));
        ClockLockStatus.Lock afterTheSecond = lockNow();
        resync();
        tick(ok(2_200));

        assertThat(afterTheSecond).as("one bad interval never unlocks").isEqualTo(ClockLockStatus.Lock.CHECKING);
        assertThat(lockNow()).as("the resyncs of its second and third polls unlock")
                .isEqualTo(ClockLockStatus.Lock.UNLOCKED);
        assertThat(monitor.status().orElseThrow().transitions()).containsExactly(transition(1, false, started));
        assertThat(events).containsExactly(new ClockLockEvent(DEVICE, 1, false, started));
    }

    /**
     * Ruling E271: whatever the order of the panel's close, the driver's reset request and the closed stream's last
     * poll, the stream that answers the request gets its window, also when the closed stream took the session up.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(ResetAfterAPanelChange.class)
    void aStreamThatAnswersAResetAfterAPanelChangeGetsItsWindowWhateverTheOrder(ResetAfterAPanelChange order) {
        commonStart();
        probe.panelOpen = true;
        int sourcesBefore = probe.sourceCalls;
        for (PanelOrResetStep step : order.steps) {
            switch (step) {
                case PANEL_CLOSES -> probe.closePanel();
                case DRIVER_ASKS_TO_BE_RESET -> monitor.pause();
                case CLOSED_STREAMS_LAST_POLL -> tickWithNothingSent();
            }
        }
        int sourcesReadByTheLastPoll = probe.sourceCalls - sourcesBefore;
        monitor.stop();
        long started = clock.get();
        monitor.start(DEVICE);

        assertThat(sourcesReadByTheLastPoll)
                .as("premise: only a poll after the close and before the request takes the session up")
                .isEqualTo(order == ResetAfterAPanelChange.CLOSE_POLL_REQUEST ? 1 : 0);
        assertOnlyTheResyncsOfTheThirdAndFourthPollsUnlock(started);
    }

    /**
     * Ruling E271: a panel whose call returned at once, its session taken up and its window spent long before the
     * driver asks to be reset, still leaves the stream that answers the request its own window.
     */
    @Test
    void aStreamThatAnswersAResetGetsItsWindowAfterAPanelWhoseCallReturnedAtOnce() {
        commonStart();
        int sourcesBefore = probe.sourceCalls;
        probe.closePanel();
        tick(ok(300));
        int sourcesAfterTheSession = probe.sourceCalls;
        tick(ok(400));
        tick(ok(500));
        tick(ok(600));
        List<ClockLockEvent> beforeTheRequest = List.copyOf(events);
        monitor.pause();
        monitor.stop();
        long started = clock.get();
        monitor.start(DEVICE);

        assertThat(sourcesAfterTheSession).as("premise: the open stream took the session up")
                .isEqualTo(sourcesBefore + 1);
        assertThat(beforeTheRequest).as("premise: the session's window ran out with nothing published").isEmpty();
        assertOnlyTheResyncsOfTheThirdAndFourthPollsUnlock(started);
    }

    /** Rulings E249 and E271: a panel session closed inside the reset's window gets its own two intervals. */
    @Test
    void aPanelSessionClosedInsideTheResetWindowGetsItsOwnTwoIntervals() {
        long started = reopenedForADriverReset();
        tick(ok(1_000));
        probe.closePanel();
        List<ClockLockStatus.Lock> afterEach = new ArrayList<>();
        for (long position = 1_100L; position <= 1_400L; position += 100L) {
            resync();
            tick(ok(position));
            afterEach.add(lockNow());
        }

        assertThat(afterEach).as("the session's two intervals run from the poll that takes it up")
                .containsExactly(ClockLockStatus.Lock.CHECKING, ClockLockStatus.Lock.CHECKING,
                        ClockLockStatus.Lock.CHECKING, ClockLockStatus.Lock.UNLOCKED);
        assertThat(monitor.status().orElseThrow().transitions()).containsExactly(transition(1, false, started));
    }

    /** Rulings E138 and E271: a switch answered inside the reset's window gets its own two intervals. */
    @Test
    void aSwitchAnsweredInsideTheResetWindowGetsItsOwnTwoIntervals() {
        long started = reopenedForADriverReset();
        tick(ok(1_000));
        monitor.switchSent();
        monitor.switchAnswered(false);
        List<ClockLockStatus.Lock> afterEach = new ArrayList<>();
        for (long position = 1_100L; position <= 1_400L; position += 100L) {
            resync();
            tick(ok(position));
            afterEach.add(lockNow());
        }

        assertThat(afterEach).as("the switch's two intervals run from the poll that judges its answer")
                .containsExactly(ClockLockStatus.Lock.CHECKING, ClockLockStatus.Lock.CHECKING,
                        ClockLockStatus.Lock.CHECKING, ClockLockStatus.Lock.UNLOCKED);
        assertThat(monitor.status().orElseThrow().transitions()).containsExactly(transition(1, false, started));
    }

    /** Ruling E271: a reset requested while no stream runs gives the next stream the window. */
    @Test
    void aResetRequestedWhileNoStreamRunsGivesTheNextStreamTheWindow() {
        commonStart();
        monitor.stop();
        monitor.pause();
        long started = clock.get();
        monitor.start(DEVICE);

        assertOnlyTheResyncsOfTheThirdAndFourthPollsUnlock(started);
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

    /** The stream measured locked by its first good answer, with no transitions; its events are cleared. */
    private void commonStart() {
        monitor.start(DEVICE);
        tick(ok(100));
        tick(ok(200));
        events.clear();
    }

    /** The common start, then the driver's request to be reset and the stream the engine reopens for it; its start. */
    private long reopenedForADriverReset() {
        commonStart();
        monitor.pause();
        monitor.stop();
        long started = clock.get();
        monitor.start(DEVICE);
        return started;
    }

    /**
     * A baseline poll, then a resync in each of the next three: checking through the third poll, and one unlock at
     * the fourth, dated at the stream's start (E269).
     */
    private void assertOnlyTheResyncsOfTheThirdAndFourthPollsUnlock(long started) {
        List<ClockLockStatus.Lock> afterEach = new ArrayList<>();
        tick(ok(1_000));
        afterEach.add(lockNow());
        for (long position = 1_100L; position <= 1_300L; position += 100L) {
            resync();
            tick(ok(position));
            afterEach.add(lockNow());
        }
        assertThat(afterEach).as("the first poll judges nothing and the second's resync is the reset's")
                .containsExactly(ClockLockStatus.Lock.CHECKING, ClockLockStatus.Lock.CHECKING,
                        ClockLockStatus.Lock.CHECKING, ClockLockStatus.Lock.UNLOCKED);
        assertThat(monitor.status().orElseThrow().transitions()).containsExactly(transition(1, false, started));
        assertThat(events).containsExactly(new ClockLockEvent(DEVICE, 1, false, started));
    }

    /** The common start, then four intervals with the driver's panel open, each holding a resync. */
    private ClockLockStatus lockedThroughAnOpenPanelWithResyncs() {
        commonStart();
        ClockLockStatus locked = monitor.status().orElseThrow();
        positionsBeforeThePanel = probe.positionCalls;
        probe.panelOpen = true;
        for (int i = 0; i < 4; i++) {
            resync();
            tickWithNothingSent();
        }
        return locked;
    }

    /** Queues the next answer, advances the clock, polls once; returns the poll's time. */
    private long tick(Supplier<AsioCapabilityShim.SamplePosition> answer) {
        probe.answers.addLast(answer);
        long now = clock.addAndGet(1_000L);
        monitor.pollOnce();
        return now;
    }

    /** A tick that sends nothing: no answer is queued, as with the driver's panel open. */
    private long tickWithNothingSent() {
        long now = clock.addAndGet(1_000L);
        monitor.pollOnce();
        return now;
    }

    /** A resync request arrives halfway through the next interval; returns its arrival. */
    private long resync() {
        long arrived = clock.get() + 500L;
        probe.arrive(arrived);
        return arrived;
    }

    private ClockLockStatus.Lock lockNow() {
        return monitor.status().orElseThrow().lock();
    }

    private static ClockLockStatus.Transition transition(long sequence, boolean locked, long evidenceNanos) {
        return new ClockLockStatus.Transition(sequence, locked, evidenceNanos);
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

    /** One step of {@link #aStreamThatAnswersAResetAfterAPanelChangeGetsItsWindowWhateverTheOrder}. */
    enum PanelOrResetStep {
        /** The driver's control panel closes: its session is counted, then the panel reads closed. */
        PANEL_CLOSES,
        /** The driver asks to be reset. */
        DRIVER_ASKS_TO_BE_RESET,
        /** A poll of the stream the engine closes for the request, with no answer queued for it. */
        CLOSED_STREAMS_LAST_POLL
    }

    /** The six orders of the panel's close, the driver's reset request and the closed stream's last poll. */
    enum ResetAfterAPanelChange {
        CLOSE_REQUEST_POLL(PanelOrResetStep.PANEL_CLOSES, PanelOrResetStep.DRIVER_ASKS_TO_BE_RESET,
                PanelOrResetStep.CLOSED_STREAMS_LAST_POLL),
        CLOSE_POLL_REQUEST(PanelOrResetStep.PANEL_CLOSES, PanelOrResetStep.CLOSED_STREAMS_LAST_POLL,
                PanelOrResetStep.DRIVER_ASKS_TO_BE_RESET),
        REQUEST_CLOSE_POLL(PanelOrResetStep.DRIVER_ASKS_TO_BE_RESET, PanelOrResetStep.PANEL_CLOSES,
                PanelOrResetStep.CLOSED_STREAMS_LAST_POLL),
        REQUEST_POLL_CLOSE(PanelOrResetStep.DRIVER_ASKS_TO_BE_RESET, PanelOrResetStep.CLOSED_STREAMS_LAST_POLL,
                PanelOrResetStep.PANEL_CLOSES),
        POLL_CLOSE_REQUEST(PanelOrResetStep.CLOSED_STREAMS_LAST_POLL, PanelOrResetStep.PANEL_CLOSES,
                PanelOrResetStep.DRIVER_ASKS_TO_BE_RESET),
        POLL_REQUEST_CLOSE(PanelOrResetStep.CLOSED_STREAMS_LAST_POLL, PanelOrResetStep.DRIVER_ASKS_TO_BE_RESET,
                PanelOrResetStep.PANEL_CLOSES);

        final List<PanelOrResetStep> steps;

        ResetAfterAPanelChange(PanelOrResetStep... steps) {
            this.steps = List.of(steps);
        }
    }

    /** A probe whose answers the test queues; device periods advance with every poll. */
    private static final class FakeProbe implements AsioClockLockMonitor.Probe {
        volatile List<ClockSource> sources = List.of();
        volatile boolean sourcesFail;
        volatile boolean positionAvailable = true;
        volatile boolean periodsAdvance = true;
        volatile boolean panelOpen;
        volatile long closedSessions;
        /** Runs once inside the monitor's next read of the panel's level, after that read took its value. */
        volatile Runnable afterTheNextPanelLevelRead;
        /** Runs once inside the monitor's next read of the closed-session count, after that read took its value. */
        volatile Runnable afterTheNextSessionCountRead;
        final Deque<Supplier<AsioCapabilityShim.SamplePosition>> answers = new ArrayDeque<>();
        final AtomicLong resyncs = new AtomicLong();
        /** The fallback arrival of a request {@link #arrive} did not date: the newest one scripted. */
        volatile long lastResyncNanos;
        /** Each request's arrival, by its number, as {@link #arrive} scripted it. */
        final Map<Long, Long> arrivals = new ConcurrentHashMap<>();
        /** The requests whose arrival the backend no longer keeps. */
        final Set<Long> forgotten = ConcurrentHashMap.newKeySet();
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

        @Override public OptionalLong driverResyncArrivalNanos(long request) {
            if (forgotten.contains(request)) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(arrivals.getOrDefault(request, lastResyncNanos));
        }

        /** One resync request that arrived at {@code arrivedNanos}; returns its number. */
        long arrive(long arrivedNanos) {
            long request = resyncs.get();
            arrivals.put(request, arrivedNanos);
            lastResyncNanos = arrivedNanos;
            resyncs.incrementAndGet();
            return request;
        }

        @Override public boolean controlPanelOpen() {
            boolean now = panelOpen;
            Runnable once = afterTheNextPanelLevelRead;
            if (once != null) {
                afterTheNextPanelLevelRead = null;
                once.run();
            }
            return now;
        }

        @Override public long closedControlPanelSessions() {
            long now = closedSessions;
            Runnable once = afterTheNextSessionCountRead;
            if (once != null) {
                afterTheNextSessionCountRead = null;
                once.run();
            }
            return now;
        }

        /** The panel closes as AsioControlThread closes it: the session is counted before the panel reads closed. */
        void closePanel() {
            closedSessions = closedSessions + 1;
            panelOpen = false;
        }
    }
}
