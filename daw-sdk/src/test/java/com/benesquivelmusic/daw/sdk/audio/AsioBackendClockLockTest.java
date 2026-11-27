package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The clock-lock poll as {@link AsioBackend} wires it: started by a
 * successful open, stopped first thing in {@code close()} without ever
 * interrupting a poll inside {@link AsioControlThread}, and publishing on the
 * backend's own never-completed publisher. Stub driver, streaming and
 * capability shims stand in for the native library.
 */
class AsioBackendClockLockTest {

    private static final DeviceId DEVICE = new DeviceId(AsioBackend.NAME, "Clocked Driver");
    private static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 32);
    private static final int FRAMES = 64;
    private static final long AWAIT_SECONDS = 10;

    private Arena arena;
    private AsioBackend backend;
    private final AtomicInteger positionCalls = new AtomicInteger();
    private volatile Supplier<AsioCapabilityShim.SamplePosition> positionAnswer;
    private final List<ClockLockEvent> events = new CopyOnWriteArrayList<>();
    private volatile int currentSourceId = 1;
    private volatile boolean resetDuringCreateBuffers;
    private volatile int setClockSourceAnswer;
    private volatile RuntimeException setClockSourceFailure;
    private volatile boolean switchPendingDuringTheCall;
    /** Each stream's poller is its own thread: its polls, counted apart from a closed stream's late poll. */
    private final ThreadLocal<AtomicInteger> pollsOfThisPoller = ThreadLocal.withInitial(AtomicInteger::new);
    /** The polls of the stream whose poller polled first most recently; replaced by a test before each reopen. */
    private volatile AtomicInteger pollsOfTheNewestStream = new AtomicInteger();
    private final AtomicInteger sourceReads = new AtomicInteger();

    @BeforeEach
    void setUp() {
        arena = Arena.ofShared();
        AsioBackend.setDriverShimFactory(StubDriverShim::new);
        AsioBackend.setStreamingShimFactory(StubStreamingShim::new);
        AsioBackend.setCapabilityShimFactory(ClockedCapabilityShim::new);
        AsioBackend.setClockPollInterval(Duration.ofMillis(20));
        backend = new AsioBackend();
        backend.clockLockEvents().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(ClockLockEvent event) { events.add(event); }
            @Override public void onError(Throwable t) { }
            @Override public void onComplete() { }
        });
    }

    @AfterEach
    void tearDown() {
        backend.close();
        AsioBackend.resetDriverShimFactory();
        AsioBackend.resetStreamingShimFactory();
        AsioBackend.resetCapabilityShimFactory();
        AsioBackend.resetClockPollInterval();
        arena.close();
    }

    @Test
    void anExternalSourceWhosePositionFreezesUnlocksOnTheBackendsPublisher() throws Exception {
        positionAnswer = frozenPositionWhileTheDeviceCallsBack();

        backend.open(DEVICE, FORMAT, FRAMES);

        assertThat(await(() -> !events.isEmpty())).as("an unlock arrives").isTrue();
        ClockLockEvent unlock = events.getFirst();
        assertThat(unlock.device()).isEqualTo(new DeviceId(AsioBackend.NAME, DEVICE.name()));
        assertThat(unlock.sourceId()).isEqualTo(1);
        assertThat(unlock.locked()).isFalse();
        assertThat(unlock.evidenceNanos()).isNotZero();
    }

    /** Each open starts the lock fresh: an unlock of the stream before is not carried, so a new one is published. */
    @Test
    void aReopenOfTheSameDevicePublishesItsOwnUnlock() throws Exception {
        positionAnswer = frozenPositionWhileTheDeviceCallsBack();
        backend.open(DEVICE, FORMAT, FRAMES);
        assertThat(await(() -> events.size() == 1)).as("the first stream's unlock").isTrue();
        backend.close();

        backend.open(DEVICE, FORMAT, FRAMES);

        assertThat(await(() -> events.size() == 2)).as("the reopened stream's own unlock").isTrue();
        assertThat(events).extracting(ClockLockEvent::locked).containsExactly(false, false);
    }

    /**
     * {@code close()} stops the poll, and the publisher outlives the stream:
     * a subscriber that arrives after the close is subscribed, not completed.
     */
    @Test
    void closeStopsThePollAndLeavesThePublisherOpen() throws Exception {
        positionAnswer = frozenPositionWhileTheDeviceCallsBack();
        backend.open(DEVICE, FORMAT, FRAMES);
        assertThat(await(() -> positionCalls.get() >= 2)).isTrue();

        backend.close();
        int callsAtClose = positionCalls.get();
        Thread.sleep(300);

        assertThat(positionCalls.get())
                .as("at most the poll already inside its downcall finishes")
                .isLessThanOrEqualTo(callsAtClose + 1);
        AtomicBoolean subscribed = new AtomicBoolean();
        AtomicBoolean completed = new AtomicBoolean();
        backend.clockLockEvents().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { subscribed.set(true); }
            @Override public void onNext(ClockLockEvent event) { }
            @Override public void onError(Throwable t) { completed.set(true); }
            @Override public void onComplete() { completed.set(true); }
        });
        assertThat(await(subscribed::get)).isTrue();
        Thread.sleep(100);
        assertThat(completed.get()).as("the clock publisher is never completed").isFalse();
    }

    /**
     * The no-interrupt rule. A poll is held inside {@link AsioControlThread}
     * when {@code close()} runs: the stop must leave that call RUNNING, not
     * ABANDONED, so the control thread stays quiesced and the backend's own
     * teardown downcalls are not refused. An interrupt would abandon the call
     * and poison every later ASIO call until it returned.
     */
    @Test
    void closeNeverInterruptsAPollInsideTheControlThread() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        positionAnswer = () -> {
            try {
                return AsioControlThread.call(() -> {
                    entered.countDown();
                    release.await(AWAIT_SECONDS * 3, TimeUnit.SECONDS);
                    return new AsioCapabilityShim.SamplePosition(
                            AsioClockLockMonitor.ASE_SP_NOT_ADVANCING, 0L, 0L);
                });
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new AudioBackendException("no answer", failure);
            }
        };
        Thread closer = null;
        try {
            backend.open(DEVICE, FORMAT, FRAMES);
            assertThat(entered.await(AWAIT_SECONDS, TimeUnit.SECONDS))
                    .as("the poll is inside the control thread")
                    .isTrue();
            AsioClockLockMonitor monitor = backend.clockLockMonitor();

            closer = Thread.ofPlatform().name("test-asio-close").start(backend::close);
            assertThat(await(() -> !monitor.isRunning())).as("close() stopped the monitor").isTrue();

            long watchUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
            while (System.nanoTime() < watchUntil) {
                assertThat(AsioControlThread.isQuiesced())
                        .as("the held poll stays RUNNING, never abandoned by an interrupt")
                        .isTrue();
                Thread.sleep(5);
            }
        } finally {
            release.countDown();
        }
        closer.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS * 3));
        assertThat(closer.isAlive()).as("close() completes once the poll returns").isFalse();
        Thread.sleep(100);
        assertThat(AsioControlThread.isQuiesced()).isTrue();
        assertThat(backend.isReleasePending()).isFalse();
        assertThat(events).as("a poll that returns after close publishes nothing").isEmpty();
    }

    /**
     * A clock-source selection the driver accepted makes the poll read the
     * source again; on the internal clock it stops polling.
     */
    @Test
    void anAcceptedClockSourceSelectionIsReadAgainByThePoll() throws Exception {
        positionAnswer = frozenPositionWhileTheDeviceCallsBack();
        backend.open(DEVICE, FORMAT, FRAMES);
        assertThat(await(() -> positionCalls.get() >= 2)).isTrue();

        backend.selectClockSource(DEVICE, 0);
        Thread.sleep(100);
        int callsAfterSelection = positionCalls.get();
        Thread.sleep(300);

        assertThat(positionCalls.get())
                .as("an internal source is never polled")
                .isEqualTo(callsAfterSelection);
    }

    /**
     * Rulings E137 and E138: the clock-lock poll learns of the person's
     * switch before the driver call, and every exit ends it — a refusal and
     * a driver call that throws included — so no switch stays pending.
     */
    @Test
    void aRefusedSelectionAndAThrowingOneBothEndThePendingSwitch() throws Exception {
        positionAnswer = frozenPositionWhileTheDeviceCallsBack();
        backend.open(DEVICE, FORMAT, FRAMES);
        AsioClockLockMonitor monitor = backend.clockLockMonitor();

        setClockSourceAnswer = -997;
        assertThatThrownBy(() -> backend.selectClockSource(DEVICE, 0)).isInstanceOf(AudioBackendException.class);
        boolean pendingDuringTheRefusedCall = switchPendingDuringTheCall;
        boolean pendingAfterTheRefusal = monitor.isSwitchPending();
        setClockSourceAnswer = 0;
        setClockSourceFailure = new IllegalStateException("the driver call threw");
        switchPendingDuringTheCall = false;
        assertThatThrownBy(() -> backend.selectClockSource(DEVICE, 0)).isInstanceOf(AudioBackendException.class);
        boolean pendingDuringTheThrowingCall = switchPendingDuringTheCall;

        assertThat(pendingDuringTheRefusedCall).as("told before the driver call").isTrue();
        assertThat(pendingAfterTheRefusal).as("a refusal ends the pending switch").isFalse();
        assertThat(pendingDuringTheThrowingCall).isTrue();
        assertThat(monitor.isSwitchPending()).as("a driver call that threw ends it too").isFalse();
        assertThat(currentSourceId).as("premise: the driver took neither").isEqualTo(1);
    }

    @Test
    void aDriverResetPausesThePoll() throws Exception {
        positionAnswer = frozenPositionWhileTheDeviceCallsBack();
        backend.open(DEVICE, FORMAT, FRAMES);
        assertThat(await(() -> positionCalls.get() >= 2)).isTrue();

        backend.stopStreamingForDriverReset();
        Thread.sleep(100);
        int callsAfterReset = positionCalls.get();
        Thread.sleep(300);

        assertThat(positionCalls.get()).isEqualTo(callsAfterReset);
    }

    @Test
    void anOpenThatLatchedADriverResetStartsNoPoll() throws Exception {
        positionAnswer = frozenPositionWhileTheDeviceCallsBack();
        resetDuringCreateBuffers = true;

        backend.open(DEVICE, FORMAT, FRAMES);
        Thread.sleep(300);

        assertThat(backend.clockLockMonitor().isRunning()).isFalse();
        assertThat(positionCalls.get()).isZero();
    }

    /**
     * Ruling E271, as the backend wires it: the stream the engine reopens after the driver asked to be reset judges
     * no resync in its first two polls, and the plain reopen after it is judged from its second poll again.
     */
    @Test
    void aStreamReopenedForADriverResetJudgesNoResyncInItsFirstTwoPollsAndAPlainReopenDoes() throws Exception {
        positionAnswer = movingPositionWithAResyncInEachStreamsSecondAndThirdPolls();
        backend.open(DEVICE, FORMAT, FRAMES);
        assertThat(await(() -> pollsOfTheNewestStream.get() >= 4)).as("the first stream's resyncs are behind it")
                .isTrue();

        backend.stopStreamingForDriverReset();
        backend.close();
        pollsOfTheNewestStream = new AtomicInteger();
        backend.open(DEVICE, FORMAT, FRAMES);
        assertThat(await(() -> pollsOfTheNewestStream.get() >= 5)).as("the reopened stream's fourth poll is applied")
                .isTrue();
        ClockLockStatus reopened = backend.clockLockStatus().orElseThrow();
        backend.close();
        pollsOfTheNewestStream = new AtomicInteger();
        backend.open(DEVICE, FORMAT, FRAMES);

        assertThat(reopened.lock()).as("measured locked at its fourth poll").isEqualTo(ClockLockStatus.Lock.LOCKED);
        assertThat(reopened.transitions()).as("the resyncs of its second and third polls unlocked nothing").isEmpty();
        assertThat(await(() -> backend.clockLockStatus()
                .map(status -> !status.transitions().isEmpty() && !status.transitions().getFirst().locked())
                .orElse(false)))
                .as("a plain reopen is judged from its second poll").isTrue();
    }

    /**
     * Ruling E271: the stream that answers the driver's reset request gets its window also when the stream before
     * it already took up the session of the driver's control panel that caused the request.
     */
    @Test
    void aStreamReopenedForADriverResetGetsItsWindowAfterTheStreamBeforeItTookThePanelSessionUp() throws Throwable {
        positionAnswer = movingPositionWithAResyncInEachStreamsSecondAndThirdPolls();
        backend.open(DEVICE, FORMAT, FRAMES);
        assertThat(await(() -> pollsOfTheNewestStream.get() >= 4)).as("the first stream's resyncs are behind it")
                .isTrue();
        int readsBefore = sourceReads.get();
        AsioControlThread.callUnbounded(() -> null);
        assertThat(await(() -> sourceReads.get() > readsBefore)).as("the open stream's poll took the session up")
                .isTrue();

        backend.stopStreamingForDriverReset();
        backend.close();
        pollsOfTheNewestStream = new AtomicInteger();
        backend.open(DEVICE, FORMAT, FRAMES);
        assertThat(await(() -> pollsOfTheNewestStream.get() >= 5)).as("the reopened stream's fourth poll is applied")
                .isTrue();
        ClockLockStatus reopened = backend.clockLockStatus().orElseThrow();

        assertThat(reopened.lock()).as("measured locked at its fourth poll").isEqualTo(ClockLockStatus.Lock.LOCKED);
        assertThat(reopened.transitions()).as("the resyncs of its second and third polls unlocked nothing").isEmpty();
    }

    /**
     * Rulings E278 and E271: a reset requested while the driver's panel owns the control thread is answered by the
     * stream opened once the panel has closed; its first two polls are switch intervals.
     */
    @Test
    void aResetWhileThePanelIsOpenIsAnsweredByTheStreamOpenedAfterTheClose() throws Exception {
        positionAnswer = movingPositionWithAResyncInEachStreamsSecondAndThirdPolls();
        backend.open(DEVICE, FORMAT, FRAMES);
        assertThat(await(() -> pollsOfTheNewestStream.get() >= 4)).as("the first stream's resyncs are behind it")
                .isTrue();
        CountDownLatch panelShown = new CountDownLatch(1);
        CountDownLatch panelClosed = new CountDownLatch(1);
        Thread panel = Thread.ofPlatform().name("test-asio-control-panel").start(() -> {
            try {
                AsioControlThread.callUnbounded(() -> {
                    panelShown.countDown();
                    panelClosed.await();
                    return null;
                });
            } catch (Throwable ignored) {
                // The panel's result does not matter here.
            }
        });
        try {
            assertThat(panelShown.await(AWAIT_SECONDS, TimeUnit.SECONDS)).as("the panel owns the control thread")
                    .isTrue();
            backend.stopStreamingForDriverReset();
        } finally {
            panelClosed.countDown();
            panel.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
        }
        assertThat(panel.isAlive()).as("the panel has closed").isFalse();

        backend.close();
        pollsOfTheNewestStream = new AtomicInteger();
        backend.open(DEVICE, FORMAT, FRAMES);
        assertThat(await(() -> pollsOfTheNewestStream.get() >= 5)).as("the reopened stream's fourth poll is applied")
                .isTrue();
        ClockLockStatus reopened = backend.clockLockStatus().orElseThrow();

        assertThat(reopened.lock()).as("measured locked at its fourth poll").isEqualTo(ClockLockStatus.Lock.LOCKED);
        assertThat(reopened.transitions()).as("the resyncs of its second and third polls unlocked nothing").isEmpty();
    }

    /**
     * Ruling E279, as the backend wires it: two resyncs in the third poll and one in the fourth unlock, dated at the
     * arrival the backend kept for the first request of the first bad interval.
     */
    @Test
    void anUnlockFromResyncsIsDatedAtTheFirstRequestOfItsFirstBadInterval() throws Exception {
        positionAnswer = twoResyncsInTheThirdPollAndOneInTheFourth();
        backend.open(DEVICE, FORMAT, FRAMES);
        assertThat(await(() -> backend.clockLockStatus().map(status -> !status.transitions().isEmpty())
                .orElse(false))).as("the resyncs unlock").isTrue();

        ClockLockStatus.Transition unlock = backend.clockLockStatus().orElseThrow().transitions().getFirst();
        List<DriverResyncArrivals.Run> runs = backend.takeDriverResyncArrivals().runs();

        assertThat(runs).as("premise: each request kept, at its own arrival").hasSize(3)
                .allSatisfy(run -> assertThat(run.requests()).isEqualTo(1L));
        assertThat(runs.get(0).arrivedNanos()).as("premise: the first two arrived apart")
                .isLessThan(runs.get(1).arrivedNanos());
        assertThat(unlock.locked()).isFalse();
        assertThat(unlock.evidenceNanos()).as("the first request of the first bad interval")
                .isEqualTo(runs.get(0).arrivedNanos());
    }

    /**
     * With no driver loaded (and on any host without the native library) the
     * production binding has no sample position to give: no answer, never a
     * bad one.
     */
    @Test
    void theProductionBindingGivesNoAnswerWithoutALoadedDriver() {
        try (AsioCapabilityShim shim = new AsioCapabilityShim()) {
            assertThat(shim.isSamplePositionAvailable()).isFalse();
            assertThatThrownBy(shim::samplePosition)
                    .isInstanceOf(AudioBackendException.class)
                    .hasMessageContaining("asioshim_getSamplePosition");
        }
    }

    /**
     * The backend's status is the poll's own level: the open names the
     * driver it resolved, the status follows the unlock the event announces,
     * and the close empties it.
     */
    @Test
    void theBackendsStatusIsThePollsLevelFromOpenToClose() throws Exception {
        positionAnswer = frozenPositionWhileTheDeviceCallsBack();
        assertThat(backend.clockLockStatus()).as("no stream, no status").isEmpty();

        backend.open(DEVICE, FORMAT, FRAMES);
        assertThat(await(() -> !events.isEmpty())).as("an unlock arrives").isTrue();
        ClockLockStatus whileOpen = backend.clockLockStatus().orElseThrow();
        backend.close();

        assertThat(whileOpen.device()).isEqualTo(new DeviceId(AsioBackend.NAME, DEVICE.name()));
        assertThat(whileOpen.sourceId()).isEqualTo(1);
        assertThat(whileOpen.lock()).isEqualTo(ClockLockStatus.Lock.UNLOCKED);
        assertThat(whileOpen.evidenceNanos()).isEqualTo(events.getFirst().evidenceNanos());
        assertThat(backend.clockLockStatus()).as("the close empties it").isEmpty();
    }

    /**
     * The read the FX thread makes every frame takes no lock: neither
     * {@code AsioBackend.clockLockStatus()} nor the poll's {@code status()} is
     * {@code synchronized}, and the backend's body only reads the poll's
     * field.
     */
    @Test
    void theStatusReadTakesNoLock() throws Exception {
        assertThat(Modifier.isSynchronized(AsioBackend.class.getMethod("clockLockStatus").getModifiers()))
                .as("AsioBackend.clockLockStatus() is not synchronized").isFalse();
        assertThat(Modifier.isSynchronized(AsioClockLockMonitor.class.getDeclaredMethod("status").getModifiers()))
                .as("AsioClockLockMonitor.status() is not synchronized").isFalse();
        String code = Files.readString(sdkSource("AsioBackend.java")).replaceAll("\\s+", " ");
        int declaration = code.indexOf("public Optional<ClockLockStatus> clockLockStatus() {");
        assertThat(declaration).as("non-vacuity: the method was found").isNotNegative();
        assertThat(code.substring(declaration, code.indexOf('}', declaration) + 1))
                .as("one read of the poll's field: no DRIVER_LIFECYCLE_LOCK, no control thread")
                .isEqualTo("public Optional<ClockLockStatus> clockLockStatus() { return clockLockMonitor.status(); }");
        code = Files.readString(sdkSource("AsioClockLockMonitor.java")).replaceAll("\\s+", " ");
        declaration = code.indexOf("Optional<ClockLockStatus> status() {");
        assertThat(declaration).as("non-vacuity: the method was found").isNotNegative();
        assertThat(code.substring(declaration, code.indexOf('}', declaration) + 1))
                .as("one volatile read").isEqualTo("Optional<ClockLockStatus> status() { return Optional.ofNullable(status); }");
    }

    /**
     * While the driver's control panel owns the control thread, the poll
     * sends nothing to the driver; it resumes when the panel closes.
     */
    @Test
    void thePollSendsNothingWhileTheDriversControlPanelIsOpen() throws Exception {
        AsioBackend.setCapabilityShimFactory(ShimClosedOffTheControlThread::new);
        AtomicInteger moving = new AtomicInteger();
        AtomicInteger callsWhileOpen = new AtomicInteger();
        positionAnswer = () -> {
            positionCalls.incrementAndGet();
            if (AsioControlThread.isControlPanelOpen()) {
                callsWhileOpen.incrementAndGet();
            }
            return new AsioCapabilityShim.SamplePosition(0, 4_096L * moving.incrementAndGet(), 0L);
        };
        backend.open(DEVICE, FORMAT, FRAMES);
        assertThat(await(() -> positionCalls.get() >= 2)).as("the poll runs").isTrue();
        CountDownLatch panelShown = new CountDownLatch(1);
        CountDownLatch panelClosed = new CountDownLatch(1);
        Thread panel = Thread.ofPlatform().name("test-asio-control-panel").start(() -> {
            try {
                AsioControlThread.callUnbounded(() -> {
                    panelShown.countDown();
                    panelClosed.await(AWAIT_SECONDS * 3, TimeUnit.SECONDS);
                    return null;
                });
            } catch (Throwable ignored) {
                // The panel's result does not matter here.
            }
        });
        try {
            assertThat(panelShown.await(AWAIT_SECONDS, TimeUnit.SECONDS)).as("the panel owns the control thread")
                    .isTrue();
            Thread.sleep(400);
        } finally {
            panelClosed.countDown();
            panel.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
        }
        int callsAfterThePanel = positionCalls.get();

        assertThat(callsWhileOpen.get())
                .as("in 400 ms of 20 ms intervals, at most the one poll already past its check when the panel opened")
                .isLessThanOrEqualTo(1);
        assertThat(await(() -> positionCalls.get() > callsAfterThePanel)).as("the poll resumes").isTrue();
    }

    /**
     * Ruling E227: a session of the driver's control panel that opens and
     * closes between two polls, so that no poll sees it open, still makes the
     * poll read the source again: the backend's probe counts closed sessions.
     */
    @Test
    void aPanelSessionShorterThanAPollMakesThePollReadTheSourceAgain() throws Throwable {
        positionAnswer = frozenPositionWhileTheDeviceCallsBack();
        backend.open(DEVICE, FORMAT, FRAMES);
        assertThat(await(() -> positionCalls.get() >= 2)).as("the poll runs").isTrue();
        assertThat(backend.clockLockStatus().orElseThrow().sourceId()).as("premise: the word clock").isEqualTo(1);

        currentSourceId = 0;
        AsioControlThread.callUnbounded(() -> null);

        assertThat(await(() -> backend.clockLockStatus().map(ClockLockStatus::sourceId).orElse(-1) == 0))
                .as("the source the panel chose is read").isTrue();
    }

    /**
     * Ruling E249: the closed-session count is raised before the panel's flag
     * falls, so a poll that reads the panel closed also sees the session that
     * closed it. The order of two stores cannot be observed without a seam,
     * so the source is read.
     */
    @Test
    void aPanelSessionIsCountedBeforeThePanelReadsClosed() throws Exception {
        String code = Files.readString(sdkSource("AsioControlThread.java")).replaceAll("\\s+", " ");

        assertThat(code).contains(
                "} finally { CLOSED_CONTROL_PANEL_SESSIONS.incrementAndGet(); controlPanelOpen = false; }");
    }

    /** OK with a moving position while the device calls back; a resync during each stream's second and third polls. */
    private Supplier<AsioCapabilityShim.SamplePosition> movingPositionWithAResyncInEachStreamsSecondAndThirdPolls() {
        return () -> {
            positionCalls.incrementAndGet();
            AtomicInteger polls = pollsOfThisPoller.get();
            int poll = polls.incrementAndGet();
            if (poll == 1) {
                pollsOfTheNewestStream = polls;
            }
            AsioBufferSwitchShim bridge = backend.activeBufferSwitchShim();
            if (bridge != null) {
                bridge.bufferSwitchUpcall(0, 1);
            }
            if (poll == 2 || poll == 3) {
                backend.noteDriverResync();
            }
            return new AsioCapabilityShim.SamplePosition(0, 4_096L * poll, 0L);
        };
    }

    /**
     * OK with a moving position while the device calls back; two resyncs, apart, in the third poll, and one in the
     * fourth.
     */
    private Supplier<AsioCapabilityShim.SamplePosition> twoResyncsInTheThirdPollAndOneInTheFourth() {
        return () -> {
            positionCalls.incrementAndGet();
            int poll = pollsOfThisPoller.get().incrementAndGet();
            AsioBufferSwitchShim bridge = backend.activeBufferSwitchShim();
            if (bridge != null) {
                bridge.bufferSwitchUpcall(0, 1);
            }
            if (poll == 3) {
                backend.noteDriverResync();
                long afterTheFirst = System.nanoTime();
                while (System.nanoTime() == afterTheFirst) {
                    Thread.onSpinWait();
                }
                backend.noteDriverResync();
            }
            if (poll == 4) {
                backend.noteDriverResync();
            }
            return new AsioCapabilityShim.SamplePosition(0, 4_096L * poll, 0L);
        };
    }

    /** OK answers with one frozen position, while the device's callbacks keep coming. */
    private Supplier<AsioCapabilityShim.SamplePosition> frozenPositionWhileTheDeviceCallsBack() {
        return () -> {
            positionCalls.incrementAndGet();
            AsioBufferSwitchShim bridge = backend.activeBufferSwitchShim();
            if (bridge != null) {
                bridge.bufferSwitchUpcall(0, 1);
            }
            return new AsioCapabilityShim.SamplePosition(0, 4_096L, 0L);
        };
    }

    /** A main source of this package, from the module or from the repository root. */
    private static Path sdkSource(String file) {
        Path inModule = Path.of("src/main/java/com/benesquivelmusic/daw/sdk/audio").resolve(file);
        return Files.isRegularFile(inModule) ? inModule : Path.of("daw-sdk").resolve(inModule);
    }

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(5);
        }
        return condition.getAsBoolean();
    }

    /**
     * A {@link ClockedCapabilityShim} whose close does not wait for the
     * control thread, so a poll that ignored an open panel would not stall in
     * the close and hide itself. Its empty arena is left to the test JVM.
     */
    private final class ShimClosedOffTheControlThread extends ClockedCapabilityShim {
        @Override public void close() { }
    }

    /** Reports an external current source among two, and answers the position queries. */
    private class ClockedCapabilityShim extends AsioCapabilityShim {
        @Override boolean isAvailable() { return true; }
        @Override Optional<BufferSizeRange> getBufferSize() { return Optional.empty(); }
        @Override Optional<int[]> getChannelCount() { return Optional.empty(); }
        @Override boolean isClockSourceAvailable() { return true; }
        @Override List<RawClockSource> getClockSources() {
            sourceReads.incrementAndGet();
            int current = currentSourceId;
            return List.of(new RawClockSource(0, "Internal", -1, 0, current == 0),
                    new RawClockSource(1, "Word Clock", -1, 1, current == 1));
        }
        @Override int setClockSource(int reference) {
            switchPendingDuringTheCall = backend.clockLockMonitor().isSwitchPending();
            RuntimeException failure = setClockSourceFailure;
            if (failure != null) {
                throw failure;
            }
            if (setClockSourceAnswer != 0) {
                return setClockSourceAnswer;
            }
            currentSourceId = reference;
            return 0;
        }
        @Override boolean isSamplePositionAvailable() { return true; }
        @Override SamplePosition samplePosition() { return positionAnswer.get(); }
        @Override public void close() { super.close(); }
    }

    private static final class StubDriverShim extends AsioDriverShim {
        @Override boolean isEnumerationAvailable() { return true; }
        @Override boolean isLifecycleAvailable() { return true; }
        @Override List<DriverDescriptor> listDrivers() {
            return List.of(new DriverDescriptor(DEVICE.name(),
                    "{00000000-0000-0000-0000-000000000000}"));
        }
        @Override boolean loadDriver(String driverName) { return true; }
        @Override public void close() { super.close(); }
    }

    /** Streaming shim backed by test-owned memory instead of a real driver. */
    private final class StubStreamingShim extends AsioStreamingShim {
        private final List<BufferInfo> bufferInfos;

        StubStreamingShim() {
            List<BufferInfo> infos = new ArrayList<>();
            for (int direction = 0; direction < 2; direction++) {
                for (int channel = 0; channel < FORMAT.channels(); channel++) {
                    MemorySegment half0 = arena.allocate(ValueLayout.JAVA_FLOAT, FRAMES);
                    MemorySegment half1 = arena.allocate(ValueLayout.JAVA_FLOAT, FRAMES);
                    infos.add(new BufferInfo(channel, direction == 0, 19,
                            half0.address(), half1.address()));
                }
            }
            bufferInfos = List.copyOf(infos);
        }

        @Override boolean isStreamingAvailable() { return true; }
        @Override boolean createBuffers(int[] in, int[] out, int frames) {
            if (resetDuringCreateBuffers) {
                // Real drivers may send kAsioResetRequest from inside ASIOCreateBuffers.
                backend.stopStreamingForDriverReset();
            }
            return true;
        }
        @Override List<BufferInfo> getBufferInfos() { return bufferInfos; }
        @Override boolean start() { return true; }
        @Override boolean stop() { return true; }
        @Override boolean disposeBuffers() { return true; }
        @Override boolean installBufferSwitchCallback(MemorySegment stub) { return true; }
        @Override boolean uninstallBufferSwitchCallback() { return true; }
        @Override public void close() { super.close(); }
    }
}
