package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AsioBackend#setSampleRate(DeviceId, double)}
 * (story 220). Mirrors {@link AsioBackendClockSourceTest} — a stub
 * {@link AsioCapabilityShim} is injected via
 * {@link AsioBackend#setCapabilityShimFactory} so the success and
 * driver-reject paths are verifiable without an actual ASIO driver,
 * the Steinberg SDK, or a Windows host.
 */
class AsioBackendSetSampleRateTest {

    private static final DeviceId DEVICE = new DeviceId("ASIO", "Mock ASIO Device");

    @AfterEach
    void restoreFactory() {
        AsioBackend.resetCapabilityShimFactory();
    }

    @Test
    void setSampleRateInvokesShimOnceOnSuccess() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Double> seen = new AtomicReference<>();
        AsioBackend.setCapabilityShimFactory(() -> StubShim.success(calls, seen));

        new AsioBackend().setSampleRate(DEVICE, 96_000.0);

        assertThat(calls.get()).isEqualTo(1);
        assertThat(seen.get()).isEqualTo(96_000.0);
    }

    @Test
    void setSampleRateThrowsAseInvalidModeOnDriverReject() {
        AsioBackend.setCapabilityShimFactory(() -> StubShim.rejecting());
        assertThatThrownBy(() -> new AsioBackend().setSampleRate(DEVICE, 96_000.0))
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("ASE_InvalidMode")
                .hasMessageContaining("96000");
    }

    @Test
    void setSampleRateFormatsFractionalRateLiterally() {
        AsioBackend.setCapabilityShimFactory(() -> StubShim.rejecting());
        assertThatThrownBy(() -> new AsioBackend().setSampleRate(DEVICE, 44_100.5))
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("44100.5")
                .hasMessageContaining("ASE_InvalidMode");
    }

    @Test
    void setSampleRateThrowsAudioBackendExceptionWhenShimUnavailable() {
        AsioBackend.setCapabilityShimFactory(StubShim::unavailable);
        assertThatThrownBy(() -> new AsioBackend().setSampleRate(DEVICE, 48_000.0))
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("native shim");
    }

    @Test
    void setSampleRateRejectsNullDevice() {
        AsioBackend.setCapabilityShimFactory(() -> StubShim.success(new AtomicInteger(),
                new AtomicReference<>()));
        assertThatThrownBy(() -> new AsioBackend().setSampleRate(null, 48_000.0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void setSampleRateRejectsNonPositiveRate() {
        AsioBackend.setCapabilityShimFactory(() -> StubShim.success(new AtomicInteger(),
                new AtomicReference<>()));
        assertThatThrownBy(() -> new AsioBackend().setSampleRate(DEVICE, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AsioBackend().setSampleRate(DEVICE, -1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AsioBackend().setSampleRate(DEVICE, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AsioBackend().setSampleRate(DEVICE, Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Test stub: a {@link AsioCapabilityShim} subclass with a
     * configurable {@code setSampleRate} outcome. The library lookup
     * in the superclass constructor is permitted to fail silently —
     * the stub answers from its own state.
     */
    private static final class StubShim extends AsioCapabilityShim {
        private final boolean shimAvailable;
        private final boolean accept;
        private final AtomicInteger counter;
        private final AtomicReference<Double> seen;

        private StubShim(boolean shimAvailable, boolean accept,
                         AtomicInteger counter, AtomicReference<Double> seen) {
            super();
            this.shimAvailable = shimAvailable;
            this.accept = accept;
            this.counter = counter;
            this.seen = seen;
        }

        static StubShim success(AtomicInteger counter, AtomicReference<Double> seen) {
            return new StubShim(true, true, counter, seen);
        }

        static StubShim rejecting() {
            return new StubShim(true, false, new AtomicInteger(), new AtomicReference<>());
        }

        static StubShim unavailable() {
            return new StubShim(false, false, new AtomicInteger(), new AtomicReference<>());
        }

        @Override boolean isAvailable() { return shimAvailable; }
        @Override boolean setSampleRate(double rate) {
            counter.incrementAndGet();
            seen.set(rate);
            return shimAvailable && accept;
        }
        @Override Optional<BufferSizeRange> getBufferSize() { return Optional.empty(); }
        @Override boolean canSampleRate(double rate) { return false; }
        @Override Optional<Double> getSampleRate() { return Optional.empty(); }
        @Override List<RawClockSource> getClockSources() { return List.of(); }
    }
}
