package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the FFM-backed
 * {@link AsioBackend#bufferSizeRange(DeviceId)} and
 * {@link AsioBackend#supportedSampleRates(DeviceId)} (story 130).
 *
 * <p>Tests inject a stub {@link AsioCapabilityShim} via
 * {@link AsioBackend#setCapabilityShimFactory} so the success path
 * can be verified without requiring an actual ASIO driver / Steinberg
 * SDK / Windows host.</p>
 */
class AsioBackendCapabilityTest {

    private static final DeviceId DEVICE = new DeviceId("ASIO", "Mock ASIO Device");

    @AfterEach
    void restoreFactory() {
        AsioBackend.resetCapabilityShimFactory();
    }

    @Test
    void bufferSizeRangeReturnsDriverReportedFourTuple() {
        // Story 130: the FFM bridge returns the driver's exact four-tuple,
        // which the dialog expands into the dropdown {96, 192, 288, 384}.
        BufferSizeRange driverReported = new BufferSizeRange(96, 384, 192, 96);
        AsioBackend.setCapabilityShimFactory(() -> new StubShim(true,
                Optional.of(driverReported), rate -> false));

        AsioBackend backend = new AsioBackend();
        BufferSizeRange range = backend.bufferSizeRange(DEVICE);

        assertThat(range).isEqualTo(driverReported);
        assertThat(range.expandedSizes()).containsExactly(96, 192, 288, 384);
    }

    @Test
    void supportedSampleRatesReturnsOnlyDriverAcceptedRates() {
        // Driver accepts only 48 kHz and 96 kHz from the canonical menu.
        Set<Integer> accepted = Set.of(48_000, 96_000);
        AsioBackend.setCapabilityShimFactory(() -> new StubShim(true,
                Optional.of(BufferSizeRange.DEFAULT_RANGE),
                rate -> accepted.contains((int) rate)));

        AsioBackend backend = new AsioBackend();
        Set<Integer> rates = backend.supportedSampleRates(DEVICE);

        assertThat(rates).containsExactlyInAnyOrderElementsOf(accepted);
    }

    @Test
    void bufferSizeRangeFallsBackToDefaultWhenShimIsAbsent() {
        AsioBackend.setCapabilityShimFactory(StubShim::unavailable);

        AsioBackend backend = new AsioBackend();
        BufferSizeRange range = backend.bufferSizeRange(DEVICE);

        assertThat(range).isEqualTo(BufferSizeRange.DEFAULT_RANGE);
    }

    @Test
    void supportedSampleRatesFallsBackToCanonicalSetWhenShimIsAbsent() {
        AsioBackend.setCapabilityShimFactory(StubShim::unavailable);

        AsioBackend backend = new AsioBackend();
        Set<Integer> rates = backend.supportedSampleRates(DEVICE);

        assertThat(rates).containsExactlyInAnyOrder(
                44_100, 48_000, 88_200, 96_000, 176_400, 192_000);
    }

    @Test
    void supportedSampleRatesFallsBackToDriverCurrentRateWhenAllCanonicalRatesRejected() {
        // The shim is available but the driver rejects every canonical rate
        // (e.g. a hardware-locked device). Instead of returning the full
        // canonical set (which would reintroduce ASE_InvalidMode), we query
        // the driver's current rate and return that as a singleton.
        AsioBackend.setCapabilityShimFactory(() -> new StubShim(true,
                Optional.of(BufferSizeRange.DEFAULT_RANGE), rate -> false, 48_000.0));

        AsioBackend backend = new AsioBackend();
        Set<Integer> rates = backend.supportedSampleRates(DEVICE);

        assertThat(rates).containsExactly(48_000);
    }

    @Test
    void supportedSampleRatesReturnsEmptyWhenAllRejectedAndNoCurrentRate() {
        // When the shim is available, all canonical rates are rejected, and
        // getSampleRate also fails, the result is an empty set — safer than
        // marking unsupported rates as supported.
        AsioBackend.setCapabilityShimFactory(() -> new StubShim(true,
                Optional.of(BufferSizeRange.DEFAULT_RANGE), rate -> false));

        AsioBackend backend = new AsioBackend();
        Set<Integer> rates = backend.supportedSampleRates(DEVICE);

        assertThat(rates).isEmpty();
    }

    @Test
    void shimAbsenceIsLoggedExactlyOncePerProcess() {
        AsioBackend.setCapabilityShimFactory(StubShim::unavailable);

        AtomicInteger fallbackLogs = new AtomicInteger();
        Handler counter = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.INFO.intValue()
                        && record.getMessage() != null
                        && record.getMessage().contains("ASIO capability shim")) {
                    fallbackLogs.incrementAndGet();
                }
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        Logger backendLog = Logger.getLogger(AsioBackend.class.getName());
        backendLog.addHandler(counter);
        boolean wasUseParent = backendLog.getUseParentHandlers();
        Level wasLevel = backendLog.getLevel();
        backendLog.setUseParentHandlers(false);
        backendLog.setLevel(Level.ALL);
        try {
            AsioBackend backend = new AsioBackend();
            // Each call falls back to the default; the absence message
            // must only fire on the first call this process.
            backend.bufferSizeRange(DEVICE);
            backend.supportedSampleRates(DEVICE);
            backend.bufferSizeRange(DEVICE);

            assertThat(fallbackLogs.get()).isEqualTo(1);
        } finally {
            backendLog.removeHandler(counter);
            backendLog.setUseParentHandlers(wasUseParent);
            backendLog.setLevel(wasLevel);
        }
    }

    @Test
    void productionShimIsUnavailableWhenAsioshimLibraryMissing() {
        // Sanity-check: in this CI sandbox the asioshim library is not
        // installed, so the production shim must construct successfully
        // and report unavailable rather than throwing.
        try (AsioCapabilityShim shim = new AsioCapabilityShim()) {
            assertThat(shim.isAvailable()).isFalse();
            assertThat(shim.getBufferSize()).isEmpty();
            assertThat(shim.canSampleRate(48_000)).isFalse();
            assertThat(shim.getSampleRate()).isEmpty();
            assertThat(shim.setSampleRate(48_000)).isFalse();
        }
    }

    @Test
    void dialogDropdownFromGranularityProducesExpectedLadder() {
        // Verifies that BufferSizeRange.expandedSizes() — the method the
        // Audio Settings dialog uses — produces the {96, 192, 288, 384}
        // ladder for the driver-reported four-tuple in the issue.
        BufferSizeRange r = new BufferSizeRange(96, 384, 192, 96);
        List<Integer> menu = r.expandedSizes();
        assertThat(menu).containsExactly(96, 192, 288, 384);
        assertThat(r.accepts(192)).isTrue();
        assertThat(r.accepts(200)).isFalse();
    }

    /**
     * Test stub: a {@link AsioCapabilityShim} subclass that reports a
     * pre-set availability flag and answers the four queries from
     * caller-supplied data. The superclass constructor's library
     * lookup is permitted to fail silently — tests use the stub's own
     * answers regardless.
     */
    private static final class StubShim extends AsioCapabilityShim {
        private final boolean available;
        private final Optional<BufferSizeRange> range;
        private final java.util.function.DoublePredicate canRate;
        private final Optional<Double> currentRate;

        StubShim(boolean available,
                 Optional<BufferSizeRange> range,
                 java.util.function.DoublePredicate canRate) {
            this(available, range, canRate, null);
        }

        StubShim(boolean available,
                 Optional<BufferSizeRange> range,
                 java.util.function.DoublePredicate canRate,
                 Double currentRate) {
            super();
            this.available = available;
            this.range = range;
            this.canRate = canRate;
            this.currentRate = Optional.ofNullable(currentRate);
        }

        static StubShim unavailable() {
            return new StubShim(false, Optional.empty(), rate -> false);
        }

        @Override
        boolean isAvailable() {
            return available;
        }

        @Override
        Optional<BufferSizeRange> getBufferSize() {
            return available ? range : Optional.empty();
        }

        @Override
        boolean canSampleRate(double rate) {
            return available && canRate.test(rate);
        }

        @Override
        Optional<Double> getSampleRate() {
            return available ? currentRate : Optional.empty();
        }
    }
}
