package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * ABI-decoding, graceful-absence and driver-OWNERSHIP tests for
 * {@link AsioDriverShim}.
 *
 * <p>The ownership half pins an asymmetry that is easy to "simplify" back into
 * a bug: the process-global claim is taken BEFORE the load downcall and is
 * dropped only when the DRIVER refuses it. A call that throws — a budget that
 * expired, or the host's fail-fast gate refusing to submit while an abandoned
 * call is still executing — proves nothing about whether the driver
 * initialized, so the claim is kept and {@code close()} still issues
 * {@code ASIOExit}. Dropping it there is what let a timed-out
 * {@code ASIOInit} leave an initialized driver holding the device with no
 * wrapper willing to exit it.</p>
 */
class AsioDriverShimTest {

    private static final Duration TINY_BUDGET = Duration.ofMillis(200);
    private static final Duration QUIESCE_BUDGET = Duration.ofSeconds(5);

    /**
     * Released by {@link #releaseTheControlThread()} for EVERY test, following
     * {@code AsioControlThreadTest}'s idiom: a test that stalls the shared
     * control thread blocks its operation on this latch rather than on one of
     * its own, so releasing it cannot be forgotten.
     */
    private final CountDownLatch wedgeRelease = new CountDownLatch(1);

    /**
     * {@link AsioControlThread} is process-wide static state — one executor,
     * one platform thread, one abandoned-operation count shared by every ASIO
     * test in this surefire JVM. A test that returned while an operation it
     * abandoned was still executing would make unrelated ASIO tests fail fast
     * for a reason they cannot see, so the wait is asserted here: the test that
     * leaked is the test that fails.
     */
    @AfterEach
    void releaseTheControlThread() {
        wedgeRelease.countDown();
        assertThat(AsioControlThread.awaitQuiescence(QUIESCE_BUDGET))
                .as("a test must not return while an operation it abandoned is still"
                        + " executing on the process-wide control thread")
                .isTrue();
    }

    @Test
    void decodesFixedWidthDriverRecordsWithoutNativeLongsOrPointers() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment records = arena.allocate(2L * AsioDriverShim.DRIVER_RECORD_STRIDE);
            putAscii(records, 0, "Focusrite USB ASIO");
            putAscii(records, AsioDriverShim.DRIVER_NAME_BYTES,
                    "{01234567-89AB-CDEF-0123-456789ABCDEF}");
            long second = AsioDriverShim.DRIVER_RECORD_STRIDE;
            putAscii(records, second, "RME Fireface USB");
            putAscii(records, second + AsioDriverShim.DRIVER_NAME_BYTES,
                    "{FEDCBA98-7654-3210-FEDC-BA9876543210}");

            assertThat(AsioDriverShim.decodeDrivers(records, 2)).containsExactly(
                    new AsioDriverShim.DriverDescriptor(
                            "Focusrite USB ASIO",
                            "{01234567-89AB-CDEF-0123-456789ABCDEF}"),
                    new AsioDriverShim.DriverDescriptor(
                            "RME Fireface USB",
                            "{FEDCBA98-7654-3210-FEDC-BA9876543210}"));
            assertThat(AsioDriverShim.DRIVER_RECORD_STRIDE).isEqualTo(168);
        }
    }

    @Test
    void decoderSkipsBlankRowsAndSanitizesNonAsciiBytes() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment records = arena.allocate(2L * AsioDriverShim.DRIVER_RECORD_STRIDE);
            records.set(ValueLayout.JAVA_BYTE, AsioDriverShim.DRIVER_RECORD_STRIDE,
                    (byte) 0xC3);
            putAscii(records, AsioDriverShim.DRIVER_RECORD_STRIDE + 1, " Driver");

            assertThat(AsioDriverShim.decodeDrivers(records, 2))
                    .containsExactly(new AsioDriverShim.DriverDescriptor("? Driver", ""));
        }
    }

    @Test
    void decodesNormalizedDriverInfoRecord() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = arena.allocate(AsioDriverShim.DRIVER_INFO_STRIDE);
            info.set(ValueLayout.JAVA_INT, 0, 2);
            info.set(ValueLayout.JAVA_INT, 4, 0x01020304);
            putAscii(info, AsioDriverShim.DRIVER_INFO_NAME_OFFSET, "USB ASIO");
            putAscii(info, AsioDriverShim.DRIVER_INFO_ERROR_OFFSET, "ready");

            assertThat(AsioDriverShim.decodeDriverInfo(info)).isEqualTo(
                    new AsioDriverShim.DriverInfo(2, 0x01020304, "USB ASIO", "ready"));
            assertThat(AsioDriverShim.DRIVER_INFO_STRIDE).isEqualTo(264);
        }
    }

    @Test
    void productionWrapperGracefullyHandlesMissingLibraryOrDriver() {
        AsioDriverShim shim = new AsioDriverShim();
        // Every result is safe regardless of whether this workstation happens
        // to have the optional DLL installed.
        List<AsioDriverShim.DriverDescriptor> drivers = shim.listDrivers();
        assertThat(drivers).isNotNull();
        assertThat(shim.getDriverName()).isEmpty();
        assertThat(shim.getDriverInfo()).isEmpty();
        shim.unloadDriver();
        shim.close();
        shim.close();
        assertThat(AsioDriverShim.isDriverLoaded()).isFalse();
    }

    @Test
    void aLoadTheDriverItselfRefusesLeavesNoOwnershipAndNothingToExit() {
        LifecycleStub ownerLifecycle = new LifecycleStub();
        LifecycleStub nextLifecycle = new LifecycleStub();
        try (AsioDriverShim owner = lifecycleShim(ownerLifecycle);
             AsioDriverShim next = lifecycleShim(nextLifecycle)) {
            assertThat(owner.loadDriver("First ASIO Driver")).isTrue();

            // status != SHIM_OK is the one answer that PROVES non-ownership:
            // the driver ran the load and said no.
            ownerLifecycle.loadStatus = 0;
            assertThat(owner.loadDriver("Missing ASIO Driver")).isFalse();

            assertThat(AsioDriverShim.isDriverLoaded()).isFalse();
            assertThat(next.loadDriver("Next ASIO Driver")).isTrue();
        }
        assertThat(ownerLifecycle.unloadAttempts.get())
                .as("a driver that refused the load holds nothing, so close() must not"
                        + " spend an ASIOExit on it")
                .isZero();
        assertThat(AsioDriverShim.isDriverLoaded()).isFalse();
    }

    @Test
    void aLoadWhoseCallThrowsKeepsTheClaimSoCloseStillIssuesTheUnload() {
        LifecycleStub ownerLifecycle = new LifecycleStub();
        LifecycleStub nextLifecycle = new LifecycleStub();
        try (AsioDriverShim owner = lifecycleShim(ownerLifecycle);
             AsioDriverShim next = lifecycleShim(nextLifecycle)) {
            ownerLifecycle.throwOnLoad = true;
            assertThat(owner.loadDriver("Slow USB ASIO Driver")).isFalse();

            assertThat(ownerLifecycle.loadAttempts.get())
                    .as("the driver WAS asked; only the answer is missing")
                    .isEqualTo(1);
            assertThat(AsioDriverShim.isDriverLoaded())
                    .as("a throw says the host stopped waiting, not that the driver"
                            + " failed to initialize — so the claim stays and this"
                            + " wrapper still presents as the owner")
                    .isTrue();
            assertThat(next.loadDriver("Next ASIO Driver"))
                    .as("the kept claim is what stops a second wrapper loading a driver"
                            + " over one that may already be initialized")
                    .isFalse();
            assertThat(nextLifecycle.loadAttempts.get()).isZero();
        }
        assertThat(ownerLifecycle.unloadAttempts.get())
                .as("the whole point of keeping the claim: ASIOExit is still issued for"
                        + " a driver that may have initialized after the call gave up."
                        + " Dropping the claim made unloadDriver() early-return and the"
                        + " device stayed held for the life of the process")
                .isEqualTo(1);
        assertThat(AsioDriverShim.isDriverLoaded()).isFalse();
    }

    @Test
    void aLoadRefusedByTheHostsFailFastGateAlsoKeepsTheClaim() throws Exception {
        LifecycleStub ownerLifecycle = new LifecycleStub();
        LifecycleStub nextLifecycle = new LifecycleStub();
        try (AsioDriverShim owner = lifecycleShim(ownerLifecycle);
             AsioDriverShim next = lifecycleShim(nextLifecycle)) {
            // The production stand-in for a timed-out ASIOInit that this
            // harness can reach without burning DEFAULT_BUDGET of wall clock:
            // an operation the host gave up on is still inside the driver, so
            // AsioControlThread refuses every bounded call on arrival — which
            // is how loadDriver throws without the driver ever being asked.
            abandonARunningOperation();
            assertThat(AsioControlThread.isQuiesced()).isFalse();

            assertThat(owner.loadDriver("Slow USB ASIO Driver")).isFalse();
            assertThat(ownerLifecycle.loadAttempts.get())
                    .as("the host refused to submit the downcall, so the driver never"
                            + " saw this load — but an EARLIER wedged call may have left"
                            + " it initialized, which is why the outcome is unknown")
                    .isZero();
            assertThat(AsioDriverShim.isDriverLoaded())
                    .as("unknown outcome must resolve to presumed-owned")
                    .isTrue();

            // Let the wedged call return, so the assertions below are about the
            // ownership claim rather than about the gate still being shut.
            wedgeRelease.countDown();
            assertThat(AsioControlThread.awaitQuiescence(QUIESCE_BUDGET)).isTrue();

            assertThat(next.loadDriver("Next ASIO Driver"))
                    .as("the claim outlives the gate: a wrapper that may hold the driver"
                            + " keeps the next one out even once calls flow again")
                    .isFalse();
        }
        assertThat(ownerLifecycle.unloadAttempts.get())
                .as("and close() still issues ASIOExit for the driver it may hold")
                .isEqualTo(1);
        assertThat(AsioDriverShim.isDriverLoaded()).isFalse();
    }

    /**
     * Occupies the shared control thread with an operation that blocks until
     * {@link #wedgeRelease} and lets its caller's budget expire while it is
     * provably EXECUTING — the state in which {@link AsioControlThread} refuses
     * further bounded calls. The started latch asserts that state rather than
     * assuming it; an operation still QUEUED would be withdrawn instead, which
     * is a different case entirely.
     */
    private void abandonARunningOperation() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        Throwable thrown = catchThrowable(() -> AsioControlThread.call(() -> {
            started.countDown();
            wedgeRelease.await();
            return 1;
        }, TINY_BUDGET));

        assertThat(started.await(QUIESCE_BUDGET.toMillis(), TimeUnit.MILLISECONDS))
                .as("the operation must have STARTED, or this is the queued case")
                .isTrue();
        assertThat(thrown).isInstanceOf(AudioBackendException.class);
    }

    private static void putAscii(MemorySegment target, long offset, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        for (int index = 0; index < bytes.length; index++) {
            target.set(ValueLayout.JAVA_BYTE, offset + index, bytes[index]);
        }
        target.set(ValueLayout.JAVA_BYTE, offset + bytes.length, (byte) 0);
    }

    private static AsioDriverShim lifecycleShim(LifecycleStub lifecycle) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodHandle loadDriver = lookup.findVirtual(
                    LifecycleStub.class,
                    "loadDriver",
                    MethodType.methodType(int.class, MemorySegment.class))
                    .bindTo(lifecycle);
            MethodHandle unloadDriver = lookup.findVirtual(
                    LifecycleStub.class,
                    "unloadDriver",
                    MethodType.methodType(void.class))
                    .bindTo(lifecycle);
            return new AsioDriverShim(loadDriver, unloadDriver);
        } catch (NoSuchMethodException | IllegalAccessException failure) {
            throw new AssertionError("Could not create ASIO lifecycle test handles", failure);
        }
    }

    private static final class LifecycleStub {
        /** Counts downcalls that actually REACHED this stub. */
        private final AtomicInteger loadAttempts = new AtomicInteger();
        /** Whether {@code ASIOExit} was issued at all is the assertion. */
        private final AtomicInteger unloadAttempts = new AtomicInteger();
        private volatile int loadStatus = 1;
        private volatile boolean throwOnLoad;

        @SuppressWarnings("unused")
        private int loadDriver(MemorySegment ignored) {
            loadAttempts.incrementAndGet();
            if (throwOnLoad) {
                throw new IllegalStateException("simulated native load failure");
            }
            return loadStatus;
        }

        @SuppressWarnings("unused")
        private void unloadDriver() {
            unloadAttempts.incrementAndGet();
        }
    }
}
