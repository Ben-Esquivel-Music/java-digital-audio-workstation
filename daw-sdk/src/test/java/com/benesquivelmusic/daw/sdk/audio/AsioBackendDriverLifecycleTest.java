package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Driver enumeration and lifecycle acceptance tests for story 310. */
class AsioBackendDriverLifecycleTest {

    private static final DeviceId DRIVER_A = new DeviceId("ASIO", "Driver A");
    private static final DeviceId DRIVER_B = new DeviceId("ASIO", "Driver B");

    private static final Duration TINY_BUDGET = Duration.ofMillis(200);
    private static final long GUARD_BUDGET_MILLIS = 5_000L;

    private final List<AsioBackend> openedBackends = new ArrayList<>();

    /**
     * Released by {@link #cleanUp()} for EVERY test. A test that has to stall
     * the process-wide control thread blocks its operation on this latch rather
     * than on one of its own, so releasing it cannot be forgotten — the idiom
     * {@code AsioControlThreadTest} established.
     */
    private final CountDownLatch wedgeRelease = new CountDownLatch(1);

    @AfterEach
    void cleanUp() {
        // Released FIRST, and unconditionally. AsioControlThread is process-wide
        // static state — one executor, one platform thread, one abandoned-call
        // count shared by every ASIO test in this surefire JVM — so a test that
        // returned while an operation it abandoned was still executing would
        // make unrelated tests fail fast, or silently defer their driver
        // releases, for a reason they cannot see. The tracked backends are
        // closed only after quiescence returns, which keeps their closes on the
        // synchronous release path.
        wedgeRelease.countDown();
        boolean quiesced = AsioControlThread.awaitQuiescence(
                Duration.ofMillis(GUARD_BUDGET_MILLIS));
        openedBackends.forEach(AsioBackend::close);
        AsioBackend.resetDriverShimFactory();
        AsioBackend.resetCapabilityShimFactory();
        assertThat(quiesced)
                .as("a test must not return while an operation it abandoned is still"
                        + " executing on the process-wide control thread")
                .isTrue();
    }

    @Test
    void listDevicesReturnsOneEntryPerEnumeratedDriver() {
        StubDriverShim shim = StubDriverShim.available();
        AsioBackend.setDriverShimFactory(() -> shim);

        assertThat(new AsioBackend().listDevices())
                .extracting(AudioDeviceInfo::index, AudioDeviceInfo::name,
                        AudioDeviceInfo::hostApi)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(0, "Driver A", "ASIO"),
                        org.assertj.core.groups.Tuple.tuple(1, "Driver B", "ASIO"));
        assertThat(shim.closeCalls.get()).isEqualTo(1);
    }

    @Test
    void enumeratedDriversAreSelectableOutputsWithoutFabricatingAChannelCount() {
        // Story 316 review (R4): every enumerated driver used to be built
        // with maxInputChannels == maxOutputChannels == 0, and 0 means "this
        // direction is not offered" — so AudioDeviceInfo::supportsOutput was
        // false for all of them and the Settings device menus (which filter
        // on exactly that) offered nothing but the blank default. A specific
        // ASIO driver could not be selected or persisted at all.
        AsioBackend.setDriverShimFactory(StubDriverShim::available);

        List<AudioDeviceInfo> devices = new AsioBackend().listDevices();

        assertThat(devices)
                .as("both installed drivers are offered as OUTPUT devices")
                .filteredOn(AudioDeviceInfo::supportsOutput)
                .extracting(AudioDeviceInfo::name)
                .containsExactly("Driver A", "Driver B");
        assertThat(devices)
                .filteredOn(AudioDeviceInfo::supportsInput)
                .extracting(AudioDeviceInfo::name)
                .containsExactly("Driver A", "Driver B");
        // Honesty invariant: enumeration never loads a driver, so
        // ASIOGetChannels has never been called and no count is knowable.
        assertThat(devices)
                .allSatisfy(device -> {
                    assertThat(device.hasKnownOutputChannelCount())
                            .as("an unloaded driver's output count is not knowable")
                            .isFalse();
                    assertThat(device.hasKnownInputChannelCount()).isFalse();
                    assertThat(device.maxOutputChannels())
                            .as("no fabricated positive count is ever reported")
                            .isNotPositive()
                            .isEqualTo(AudioDeviceInfo.CHANNEL_COUNT_UNKNOWN);
                    assertThat(device.maxInputChannels())
                            .isNotPositive()
                            .isEqualTo(AudioDeviceInfo.CHANNEL_COUNT_UNKNOWN);
                });
    }

    @Test
    void listDevicesReturnsEmptyWhenEnumerationSymbolIsAbsent() {
        StubDriverShim shim = StubDriverShim.unavailable();
        AsioBackend.setDriverShimFactory(() -> shim);

        assertThat(new AsioBackend().listDevices()).isEmpty();
        assertThat(shim.listCalls.get()).isZero();
    }

    @Test
    void openLoadsDriverBeforeMarkingBackendOpenAndSurfacesDriverInfo() {
        AtomicReference<AsioBackend> backendRef = new AtomicReference<>();
        StubDriverShim shim = StubDriverShim.available();
        shim.openState = () -> backendRef.get().isOpen();
        AsioBackend.setDriverShimFactory(() -> shim);
        AsioBackend backend = track(new AsioBackend());
        backendRef.set(backend);

        backend.open(DRIVER_A, AudioFormat.CD_QUALITY, 128);

        assertThat(shim.openDuringLoad).isFalse();
        assertThat(shim.events).containsExactly("load:Driver A");
        assertThat(backend.isOpen()).isTrue();
        assertThat(backend.activeDriverInfo()).contains(
                new AsioDriverInfo("Driver A", 2, 17, "ready"));
    }

    @Test
    void activeDriverInfoIsAUsablePublicSdkValue() throws Exception {
        assertThat(Modifier.isPublic(AsioDriverInfo.class.getModifiers())).isTrue();
        assertThat(AsioDriverInfo.class.isRecord()).isTrue();
        assertThat(Modifier.isPublic(AsioBackend.class
                .getMethod("activeDriverInfo").getModifiers())).isTrue();
        assertThat(new AsioBackend().activeDriverInfo()).isEmpty();
    }

    @Test
    void defaultDeviceResolvesToFirstEnumeratedDriver() {
        StubDriverShim shim = StubDriverShim.available();
        AsioBackend.setDriverShimFactory(() -> shim);
        AsioBackend backend = track(new AsioBackend());

        backend.open(DeviceId.defaultFor("ASIO"), AudioFormat.CD_QUALITY, 128);

        assertThat(shim.events).containsExactly("load:Driver A");
    }

    @Test
    void defaultDeviceFailsClearlyWhenNoDriversAreInstalled() {
        StubDriverShim shim = StubDriverShim.available();
        shim.drivers = List.of();
        AsioBackend.setDriverShimFactory(() -> shim);
        AsioBackend backend = new AsioBackend();

        assertThatThrownBy(() -> backend.open(
                DeviceId.defaultFor("ASIO"), AudioFormat.CD_QUALITY, 128))
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("No installed ASIO driver");
        assertThat(backend.isOpen()).isFalse();
        assertThat(shim.closeCalls.get()).isEqualTo(1);
    }

    @Test
    void failedLoadRollsBackAndLeavesBackendClosed() {
        StubDriverShim shim = StubDriverShim.available();
        shim.loadSucceeds = false;
        AsioBackend.setDriverShimFactory(() -> shim);
        AsioBackend backend = new AsioBackend();

        assertThatThrownBy(() -> backend.open(DRIVER_A, AudioFormat.CD_QUALITY, 128))
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("Could not load and initialize");
        assertThat(backend.isOpen()).isFalse();
        assertThat(shim.closeCalls.get()).isEqualTo(1);
    }

    @Test
    void validationOccursBeforeCreatingOrLoadingNativeShim() {
        AtomicInteger factoryCalls = new AtomicInteger();
        AsioBackend.setDriverShimFactory(() -> {
            factoryCalls.incrementAndGet();
            return StubDriverShim.available();
        });
        AsioBackend backend = new AsioBackend();

        assertThatThrownBy(() -> backend.open(DRIVER_A, AudioFormat.CD_QUALITY, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(factoryCalls.get()).isZero();
    }

    @Test
    void secondOpenIsRejectedWithoutReplacingCurrentDriver() {
        StubDriverShim shim = StubDriverShim.available();
        AsioBackend.setDriverShimFactory(() -> shim);
        AsioBackend backend = track(new AsioBackend());
        backend.open(DRIVER_A, AudioFormat.CD_QUALITY, 128);

        assertThatThrownBy(() -> backend.open(DRIVER_B, AudioFormat.CD_QUALITY, 128))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already has an open stream");
        assertThat(shim.events).containsExactly("load:Driver A");
    }

    @Test
    void closeThenOpenDifferentDriverUnloadsBeforeReloadAndCloseIsIdempotent() {
        List<String> lifecycle = new ArrayList<>();
        List<StubDriverShim> shims = new ArrayList<>();
        AsioBackend.setDriverShimFactory(() -> {
            StubDriverShim shim = StubDriverShim.available();
            shim.events = lifecycle;
            shims.add(shim);
            return shim;
        });
        AsioBackend backend = track(new AsioBackend());

        backend.open(DRIVER_A, AudioFormat.CD_QUALITY, 128);
        backend.close();
        backend.open(DRIVER_B, AudioFormat.CD_QUALITY, 256);
        backend.close();
        backend.close();

        assertThat(lifecycle).containsExactly(
                "load:Driver A", "unload", "load:Driver B", "unload");
        assertThat(shims).hasSize(2);
        assertThat(backend.isOpen()).isFalse();
    }

    @Test
    void closeThenReopenCreatesFreshDeviceEventPublisher() throws Exception {
        AsioBackend.setDriverShimFactory(StubDriverShim::available);
        AsioBackend backend = track(new AsioBackend());
        backend.open(DRIVER_A, AudioFormat.CD_QUALITY, 128);
        backend.close();
        backend.open(DRIVER_B, AudioFormat.CD_QUALITY, 256);

        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<AudioDeviceEvent> event = new AtomicReference<>();
        backend.deviceEvents().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(AudioDeviceEvent item) {
                event.set(item);
                received.countDown();
            }

            @Override public void onError(Throwable throwable) { }
            @Override public void onComplete() { }
        });
        backend.publishFormatChangeRequested(
                DRIVER_B, Optional.empty(), new FormatChangeReason.DriverReset());

        assertThat(received.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(event.get())
                .isEqualTo(new AudioDeviceEvent.FormatChangeRequested(
                        DRIVER_B, Optional.empty(), new FormatChangeReason.DriverReset()));
    }

    @Test
    void separateBackendCannotStealProcessWideDriver() {
        AsioBackend first = track(new AsioBackend());
        AsioBackend second = track(new AsioBackend());
        AsioBackend.setDriverShimFactory(StubDriverShim::available);
        first.open(DRIVER_A, AudioFormat.CD_QUALITY, 128);

        assertThatThrownBy(() -> second.open(DRIVER_B, AudioFormat.CD_QUALITY, 128))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("process-wide driver");
        assertThat(first.isOpen()).isTrue();
        assertThat(second.isOpen()).isFalse();
    }

    /**
     * Story 316 re-review — a driver release the backend had to DEFER is
     * visible to the caller instead of being silent.
     *
     * <p>{@code releaseDriverShim} already deferred the shim close while the
     * control thread was executing a call the host had abandoned, and that is
     * correct: the shim can only ever be closed once, so spending that single
     * chance on downcalls that fail fast would leak {@code ASIOExit} and the
     * arena for the life of the process. What was missing is that
     * {@link AsioBackend#close()} then returned NORMALLY, so a caller walking a
     * fallback ladder read an ordinary released rung and opened another host
     * over a device this driver may still hold — the exact
     * two-backends-on-one-device outcome the walk is supposed to refuse.</p>
     *
     * <p>The condition must also clear on COMPLETION: a caller that abandoned
     * a device on account of it may retry, so the count has to clear itself
     * once the queued close has actually run — and only then, never merely
     * because time passed (see the expired-wait and cannot-be-re-queued tests
     * below).</p>
     */
    @Test
    void aDeferredDriverReleaseIsReportedUntilTheQueuedCloseHasRun() throws Exception {
        StubDriverShim shim = StubDriverShim.available();
        AsioBackend.setDriverShimFactory(() -> shim);
        QueuedReleaseExecutor deferredReleases = new QueuedReleaseExecutor();
        AsioBackend backend = new AsioBackend(deferredReleases);
        backend.open(DRIVER_A, AudioFormat.CD_QUALITY, 128);

        assertThat(backend.isReleasePending())
                .as("nothing is owed while the backend is simply open")
                .isFalse();

        abandonARunningControlThreadOperation();

        withBackendSevereLogsMuted(backend::close);

        assertThat(backend.isReleasePending())
                .as("close() returned normally, but the driver was NOT given back —"
                        + " a caller that reads this as a release opens its next rung"
                        + " over a device this driver may still hold")
                .isTrue();
        assertThat(shim.closeCalls.get())
                .as("the close really was deferred rather than merely reported")
                .isZero();
        assertThat(deferredReleases.pending())
                .as("exactly one release is owed, and it is queued rather than lost")
                .isEqualTo(1);

        wedgeRelease.countDown();
        assertThat(AsioControlThread.awaitQuiescence(
                Duration.ofMillis(GUARD_BUDGET_MILLIS)))
                .as("the driver returning is what lets the queued release proceed")
                .isTrue();
        deferredReleases.runQueued();

        assertThat(shim.closeCalls.get())
                .as("the deferred task is what finally issues ASIOExit")
                .isEqualTo(1);
        assertThat(backend.isReleasePending())
                .as("the condition clears once the queued close has actually run,"
                        + " so a caller that gave up on this device may retry it"
                        + " rather than treat it as lost for the life of the process")
                .isFalse();
    }

    /**
     * Story 316 review — a deferred release whose wait for the driver expires
     * stays PENDING: the budget bounds one wait, not the release.
     *
     * <p>The deferred task used to close the shim once its budget expired and
     * then hand the count back in its {@code finally}, so
     * {@link AsioBackend#isReleasePending()} cleared while the driver was still
     * inside the abandoned call — and the engine could retry the device, or
     * walk on to PortAudio, beside a driver that still held or was still
     * acquiring it. The close itself unloaded nothing either: every downcall
     * fails fast until the control thread quiesces. An expired wait now
     * re-queues itself with the count untouched and the shim unclosed, and only
     * a wait that saw the driver return issues {@code ASIOExit}.</p>
     */
    @Test
    void aDriverReleaseWhoseBudgetExpiresStaysPendingUntilTheDriverReturns() throws Exception {
        StubDriverShim shim = StubDriverShim.available();
        AsioBackend.setDriverShimFactory(() -> shim);
        QueuedReleaseExecutor deferredReleases = new QueuedReleaseExecutor();
        AsioBackend backend = new AsioBackend(deferredReleases, Duration.ofMillis(50));
        backend.open(DRIVER_A, AudioFormat.CD_QUALITY, 128);
        abandonARunningControlThreadOperation();
        withBackendSevereLogsMuted(backend::close);

        // The wedge is still held, so the 50 ms wait expires with the driver
        // still inside the abandoned call.
        withBackendSevereLogsMuted(deferredReleases::runQueued);

        assertThat(backend.isReleasePending())
                .as("a release the driver has not let the host reach stays pending —"
                        + " clearing it here is what let the engine open another host"
                        + " over a device this driver may still hold")
                .isTrue();
        assertThat(shim.closeCalls.get())
                .as("the shim's single close attempt is not spent on downcalls that"
                        + " fail fast")
                .isZero();
        assertThat(deferredReleases.pending())
                .as("the wait re-queued itself rather than giving up")
                .isEqualTo(1);

        wedgeRelease.countDown();
        assertThat(AsioControlThread.awaitQuiescence(
                Duration.ofMillis(GUARD_BUDGET_MILLIS)))
                .isTrue();
        deferredReleases.runQueued();

        assertThat(shim.closeCalls.get())
                .as("the driver returning is what lets the queued release issue ASIOExit")
                .isEqualTo(1);
        assertThat(backend.isReleasePending())
                .as("and only then does the release stop being owed")
                .isFalse();
    }

    /**
     * Story 316 review — a wait the executor refuses to take back leaves the
     * release pending for the life of the backend rather than clearing it.
     *
     * <p>Closing the shim at that point would spend
     * {@code AsioDriverShim.close()}'s single, latching attempt on downcalls
     * that fail fast, and the count is the only thing keeping a second host
     * off the device — so neither the close nor the decrement happens.</p>
     */
    @Test
    void aDriverReleaseThatCannotBeReQueuedStaysPendingForTheLifeOfTheBackend()
            throws Exception {
        StubDriverShim shim = StubDriverShim.available();
        AsioBackend.setDriverShimFactory(() -> shim);
        QueuedReleaseExecutor deferredReleases = QueuedReleaseExecutor.rejectingAfter(1);
        AsioBackend backend = new AsioBackend(deferredReleases, Duration.ofMillis(50));
        backend.open(DRIVER_A, AudioFormat.CD_QUALITY, 128);
        abandonARunningControlThreadOperation();
        withBackendSevereLogsMuted(backend::close);
        assertThat(deferredReleases.pending())
                .as("the first submission is the one the executor accepts")
                .isEqualTo(1);

        withBackendSevereLogsMuted(deferredReleases::runQueued);

        assertThat(backend.isReleasePending())
                .as("the release is still owed: nothing has reached the driver")
                .isTrue();
        assertThat(shim.closeCalls.get()).isZero();
        assertThat(deferredReleases.pending())
                .as("the re-queue was rejected, so nothing is left to run")
                .isZero();

        wedgeRelease.countDown();
        assertThat(AsioControlThread.awaitQuiescence(
                Duration.ofMillis(GUARD_BUDGET_MILLIS)))
                .isTrue();
        deferredReleases.runQueued();

        assertThat(backend.isReleasePending())
                .as("with no wait left to run, nothing can ever clear it — and nothing"
                        + " should, because the shim was never closed")
                .isTrue();
        assertThat(shim.closeCalls.get()).isZero();
    }

    /**
     * Story 316 review — a release the executor rejects OUTRIGHT stays pending
     * too. It used to close the shim inline and hand the count straight back,
     * which is the same defect through a different door: the close cannot
     * reach the driver while the control thread is non-quiescent, yet the
     * count cleared.
     */
    @Test
    void aDriverReleaseTheExecutorRejectsOutrightStaysPendingRatherThanClosingInline()
            throws Exception {
        StubDriverShim shim = StubDriverShim.available();
        AsioBackend.setDriverShimFactory(() -> shim);
        QueuedReleaseExecutor deferredReleases = QueuedReleaseExecutor.rejectingAfter(0);
        AsioBackend backend = new AsioBackend(deferredReleases);
        backend.open(DRIVER_A, AudioFormat.CD_QUALITY, 128);
        abandonARunningControlThreadOperation();

        withBackendSevereLogsMuted(backend::close);

        assertThat(backend.isReleasePending())
                .as("a rejected submission is not a release")
                .isTrue();
        assertThat(shim.closeCalls.get())
                .as("no inline close: it could not reach the driver, and it would spend"
                        + " the shim's only attempt")
                .isZero();
        assertThat(deferredReleases.pending()).isZero();
    }

    /**
     * Story 316 review — an interrupted teardown thread does not re-queue its
     * wait. {@code AsioControlThread.awaitQuiescence} returns {@code false} at
     * once on an interrupted thread for as long as the driver is still inside
     * the abandoned call (it still answers {@code true} once the control
     * thread has quiesced), so a re-queue would spin; the release stays
     * pending instead — shim unclosed, count untouched — for the life of the
     * backend, and the interrupt status is left for the thread's owner.
     */
    @Test
    void aDriverReleaseInterruptedWhileWaitingStaysPendingWithoutReQueueing()
            throws Exception {
        StubDriverShim shim = StubDriverShim.available();
        AsioBackend.setDriverShimFactory(() -> shim);
        QueuedReleaseExecutor deferredReleases = new QueuedReleaseExecutor();
        AsioBackend backend = new AsioBackend(
                deferredReleases, Duration.ofMillis(GUARD_BUDGET_MILLIS));
        backend.open(DRIVER_A, AudioFormat.CD_QUALITY, 128);
        abandonARunningControlThreadOperation();
        withBackendSevereLogsMuted(backend::close);

        boolean interruptLeftSet;
        Thread.currentThread().interrupt();
        try {
            withBackendSevereLogsMuted(deferredReleases::runQueued);
        } finally {
            interruptLeftSet = Thread.interrupted();
        }

        assertThat(interruptLeftSet)
                .as("the wait ends on the interrupt and leaves its status set")
                .isTrue();
        assertThat(backend.isReleasePending())
                .as("the release is still owed: nothing has reached the driver")
                .isTrue();
        assertThat(shim.closeCalls.get()).isZero();
        assertThat(deferredReleases.pending())
                .as("re-queueing on an interrupted thread would spin, so it does not")
                .isZero();
    }

    /**
     * Story 316 re-review — the {@link AudioBackend#isReleasePending()} default
     * is {@code false}, and every backend that releases synchronously inside
     * {@code close()} is CORRECT to inherit it rather than missing an override.
     *
     * <p>Pinned as an enumeration because the method's javadoc names these
     * implementors, and a named list that nobody checks goes stale silently.
     * {@code JavaxSoundBackend} is in it for a different reason than the rest:
     * its {@code close()} THROWS when a line could not be released and retains
     * the handle, and callers already read a close that threw as a non-release,
     * so the fact is reported through the exception instead of through this
     * flag. ({@code daw-core}'s {@code CallbackBackendAdapter} inherits it too;
     * this module cannot see it.)</p>
     */
    @Test
    void backendsThatReleaseInsideCloseInheritTheFalseDefault() {
        assertThat(new JavaxSoundBackend().isReleasePending()).isFalse();
        assertThat(new MockAudioBackend().isReleasePending()).isFalse();
        assertThat(new WasapiBackend().isReleasePending()).isFalse();
        assertThat(new CoreAudioBackend().isReleasePending()).isFalse();
        assertThat(new JackBackend().isReleasePending()).isFalse();
    }

    /**
     * Occupies the process-wide control thread with an operation that blocks
     * until {@link #wedgeRelease} and lets its caller's budget expire while it
     * is provably EXECUTING, which is what leaves
     * {@link AsioControlThread#isQuiesced()} false — the state in which
     * {@code AsioBackend.releaseDriverShim} must defer. The started latch
     * asserts the operation reached the driver rather than assuming it: an
     * operation still QUEUED when its budget expires is WITHDRAWN instead, and
     * leaves the thread quiesced.
     */
    private void abandonARunningControlThreadOperation() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        Throwable thrown = catchThrowable(() -> AsioControlThread.call(() -> {
            started.countDown();
            wedgeRelease.await();
            return 1;
        }, TINY_BUDGET));

        assertThat(started.await(GUARD_BUDGET_MILLIS, TimeUnit.MILLISECONDS))
                .as("the operation must have STARTED, or this is the queued case and"
                        + " the control thread stays quiesced")
                .isTrue();
        assertThat(thrown).isInstanceOf(AudioBackendException.class);
        assertThat(AsioControlThread.isQuiesced())
                .as("an operation the host gave up on is still inside the driver")
                .isFalse();
    }

    private AsioBackend track(AsioBackend backend) {
        openedBackends.add(backend);
        return backend;
    }

    /**
     * Runs {@code action} with the backend's logger detached from its parent
     * handlers. A deferral, an expired wait and a rejected (re-)queue all log
     * at SEVERE by design; muting the logger for the one call that provokes
     * each keeps an EXPECTED diagnostic from reading as a build failure in the
     * console.
     */
    private static void withBackendSevereLogsMuted(Runnable action) {
        Logger backendLog = Logger.getLogger(AsioBackend.class.getName());
        boolean useParentHandlers = backendLog.getUseParentHandlers();
        backendLog.setUseParentHandlers(false);
        try {
            action.run();
        } finally {
            backendLog.setUseParentHandlers(useParentHandlers);
        }
    }

    /**
     * Stand-in for the shared {@code asio-reset-teardown} executor that runs a
     * queued release only when the test says so. Deferral is otherwise
     * unobservable: the production executor would race the assertions, and a
     * test that waited for it could not distinguish "reported pending" from
     * "already finished". {@link #rejectingAfter(int)} models the executor
     * having shut down partway through: every submission past the limit is
     * refused with {@link RejectedExecutionException}.
     */
    private static final class QueuedReleaseExecutor implements Executor {

        private final List<Runnable> queued = new ArrayList<>();
        private final int acceptLimit;
        private int accepted;

        QueuedReleaseExecutor() {
            this(Integer.MAX_VALUE);
        }

        private QueuedReleaseExecutor(int acceptLimit) {
            this.acceptLimit = acceptLimit;
        }

        static QueuedReleaseExecutor rejectingAfter(int acceptedSubmissions) {
            return new QueuedReleaseExecutor(acceptedSubmissions);
        }

        @Override
        public void execute(Runnable command) {
            if (accepted >= acceptLimit) {
                throw new RejectedExecutionException(
                        "shut down after " + acceptLimit + " submission(s)");
            }
            accepted++;
            queued.add(command);
        }

        int pending() {
            return queued.size();
        }

        void runQueued() {
            List<Runnable> tasks = List.copyOf(queued);
            queued.clear();
            tasks.forEach(Runnable::run);
        }
    }

    private static final class StubDriverShim extends AsioDriverShim {
        private List<DriverDescriptor> drivers = List.of(
                new DriverDescriptor("Driver A", "{AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA}"),
                new DriverDescriptor("Driver B", "{BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB}"));
        private List<String> events = new ArrayList<>();
        private final AtomicInteger listCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private java.util.function.BooleanSupplier openState = () -> false;
        private boolean enumerationAvailable = true;
        private boolean lifecycleAvailable = true;
        private boolean loadSucceeds = true;
        private boolean closed;
        private boolean openDuringLoad;
        private String activeName;

        static StubDriverShim available() {
            return new StubDriverShim();
        }

        static StubDriverShim unavailable() {
            StubDriverShim shim = new StubDriverShim();
            shim.enumerationAvailable = false;
            shim.lifecycleAvailable = false;
            return shim;
        }

        @Override
        boolean isEnumerationAvailable() {
            return enumerationAvailable && !closed;
        }

        @Override
        boolean isLifecycleAvailable() {
            return lifecycleAvailable && !closed;
        }

        @Override
        List<DriverDescriptor> listDrivers() {
            if (!isEnumerationAvailable()) {
                return List.of();
            }
            listCalls.incrementAndGet();
            return drivers;
        }

        @Override
        boolean loadDriver(String driverName) {
            openDuringLoad = openState.getAsBoolean();
            events.add("load:" + driverName);
            if (loadSucceeds) {
                activeName = driverName;
            }
            return loadSucceeds;
        }

        @Override
        Optional<DriverInfo> getDriverInfo() {
            return activeName == null
                    ? Optional.empty()
                    : Optional.of(new DriverInfo(2, 17, activeName, "ready"));
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            closeCalls.incrementAndGet();
            if (activeName != null) {
                events.add("unload");
                activeName = null;
            }
        }
    }
}
