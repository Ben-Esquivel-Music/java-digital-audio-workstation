package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the FFM-backed
 * {@link AsioBackend#clockSources(DeviceId)} and
 * {@link AsioBackend#selectClockSource(DeviceId, int)} (story 216).
 *
 * <p>Tests inject a stub {@link AsioCapabilityShim} via
 * {@link AsioBackend#setCapabilityShimFactory} so the success / error
 * paths can be verified without requiring an actual ASIO driver / the
 * Steinberg SDK / a Windows host.</p>
 */
class AsioBackendClockSourceTest {

    private static final DeviceId DEVICE = new DeviceId("ASIO", "Mock ASIO Device");

    @AfterEach
    void restoreFactory() {
        AsioBackend.resetCapabilityShimFactory();
    }

    @Test
    void clockSourcesParsesThreeSyntheticEntriesAndClassifiesKinds() {
        // Three driver-shaped entries: Internal (current), Word Clock, S/PDIF.
        List<AsioCapabilityShim.RawClockSource> raw = List.of(
                new AsioCapabilityShim.RawClockSource(0, "Internal", -1, 0, true),
                new AsioCapabilityShim.RawClockSource(1, "Word Clock", -1, 1, false),
                new AsioCapabilityShim.RawClockSource(2, "S/PDIF Coax", -1, 2, false));
        AsioBackend.setCapabilityShimFactory(() -> StubShim.withClockSources(raw, 0));

        AsioBackend backend = new AsioBackend();
        List<ClockSource> sources = backend.clockSources(DEVICE);

        assertThat(sources).hasSize(3);
        assertThat(sources.get(0).id()).isEqualTo(0);
        assertThat(sources.get(0).name()).isEqualTo("Internal");
        assertThat(sources.get(0).current()).isTrue();
        assertThat(sources.get(0).kind()).isInstanceOf(ClockKind.Internal.class);

        assertThat(sources.get(1).name()).isEqualTo("Word Clock");
        assertThat(sources.get(1).current()).isFalse();
        assertThat(sources.get(1).kind()).isInstanceOf(ClockKind.WordClock.class);

        assertThat(sources.get(2).kind()).isInstanceOf(ClockKind.Spdif.class);
        // Confirms exactly one entry is reported as current — the UI uses
        // this to default the Clock Source combo selection.
        assertThat(sources.stream().filter(ClockSource::current).count()).isEqualTo(1L);
    }

    @Test
    void clockSourcesClassifiesAdatAesAndUnknownIntoExternalBucket() {
        List<AsioCapabilityShim.RawClockSource> raw = List.of(
                new AsioCapabilityShim.RawClockSource(0, "ADAT 1-8", -1, 0, true),
                new AsioCapabilityShim.RawClockSource(1, "AES In", -1, 1, false),
                new AsioCapabilityShim.RawClockSource(2, "MADI", -1, 2, false));
        AsioBackend.setCapabilityShimFactory(() -> StubShim.withClockSources(raw, 0));

        List<ClockSource> sources = new AsioBackend().clockSources(DEVICE);

        assertThat(sources).hasSize(3);
        assertThat(sources.get(0).kind()).isInstanceOf(ClockKind.Adat.class);
        assertThat(sources.get(1).kind()).isInstanceOf(ClockKind.Aes.class);
        // MADI is not in the Internal/WordClock/SPDIF/ADAT/AES buckets
        // so it lands in the External fallback.
        assertThat(sources.get(2).kind()).isInstanceOf(ClockKind.External.class);
    }

    @Test
    void clockSourcesHandlesNonAsciiNamesByReplacingTheBytes() {
        // The ASIO SDK contract is ASCII; a non-conformant driver may
        // emit a non-ASCII byte. The shim replaces such bytes with '?'
        // before the row reaches the backend, so the resulting
        // ClockSource still satisfies its non-blank / printable
        // invariant. We simulate the post-decode result here.
        List<AsioCapabilityShim.RawClockSource> raw = List.of(
                new AsioCapabilityShim.RawClockSource(0, "Internal??", -1, 0, true));
        AsioBackend.setCapabilityShimFactory(() -> StubShim.withClockSources(raw, 0));

        List<ClockSource> sources = new AsioBackend().clockSources(DEVICE);
        assertThat(sources).hasSize(1);
        assertThat(sources.getFirst().name()).isEqualTo("Internal??");
        assertThat(sources.getFirst().kind()).isInstanceOf(ClockKind.Internal.class);
    }

    @Test
    void clockSourcesReturnsEmptyListWhenShimIsAbsent() {
        AsioBackend.setCapabilityShimFactory(StubShim::unavailable);
        List<ClockSource> sources = new AsioBackend().clockSources(DEVICE);
        assertThat(sources).isEmpty();
    }

    @Test
    void clockSourcesReturnsEmptyListWhenClockSourceSymbolMissing() {
        // Shim is otherwise available but the clock-source symbols are
        // missing (older shim build). The backend must degrade to the
        // empty-list contract documented on AudioBackend.
        AsioBackend.setCapabilityShimFactory(
                () -> StubShim.withClockSources(List.of(), 0, /*clockSymbols*/ false));
        assertThat(new AsioBackend().clockSources(DEVICE)).isEmpty();
    }

    @Test
    void selectClockSourceInvokesDowncallOnceOnSuccess() {
        AtomicInteger calls = new AtomicInteger();
        AsioBackend.setCapabilityShimFactory(() -> {
            StubShim s = StubShim.withClockSources(List.of(), 0);
            s.setSelectionCounter(calls);
            return s;
        });
        new AsioBackend().selectClockSource(DEVICE, 1);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void selectClockSourceTranslatesAseInvalidModeIntoAudioBackendException() {
        AsioBackend.setCapabilityShimFactory(() -> StubShim.withClockSources(List.of(), -997));
        assertThatThrownBy(() -> new AsioBackend().selectClockSource(DEVICE, 2))
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("ASE_InvalidMode")
                .hasMessageContaining("driver rejects clock change while streaming");
    }

    @Test
    void selectClockSourceTranslatesAseNotPresentIntoAudioBackendException() {
        AsioBackend.setCapabilityShimFactory(() -> StubShim.withClockSources(List.of(), -1000));
        assertThatThrownBy(() -> new AsioBackend().selectClockSource(DEVICE, 99))
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("ASE_NotPresent")
                .hasMessageContaining("unknown clock source id");
    }

    @Test
    void selectClockSourceTranslatesUnknownErrorCodeWithRawNumber() {
        AsioBackend.setCapabilityShimFactory(() -> StubShim.withClockSources(List.of(), -42));
        assertThatThrownBy(() -> new AsioBackend().selectClockSource(DEVICE, 0))
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("ASIOError -42");
    }

    @Test
    void selectClockSourceThrowsWhenShimMissing() {
        AsioBackend.setCapabilityShimFactory(StubShim::unavailable);
        assertThatThrownBy(() -> new AsioBackend().selectClockSource(DEVICE, 0))
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("native shim");
    }

    @Test
    void selectClockSourceRejectsNegativeId() {
        AsioBackend.setCapabilityShimFactory(() -> StubShim.withClockSources(List.of(), 0));
        assertThatThrownBy(() -> new AsioBackend().selectClockSource(DEVICE, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void productionShimReportsClockSourceUnavailableWhenLibraryMissing() {
        // Sanity check on the host shim: the asioshim library is not
        // installed in CI, so the clock-source symbols cannot resolve.
        try (AsioCapabilityShim shim = new AsioCapabilityShim()) {
            assertThat(shim.isClockSourceAvailable()).isFalse();
            assertThat(shim.getClockSources()).isEmpty();
            // setClockSource returns ASE_NotPresent (-1000) sentinel.
            assertThat(shim.setClockSource(0)).isEqualTo(-1000);
        }
    }

    @Test
    void classifyClockKindCoversTheStandardNamePalette() {
        assertThat(AsioBackend.classifyClockKind("Internal"))
                .isInstanceOf(ClockKind.Internal.class);
        assertThat(AsioBackend.classifyClockKind("INT"))
                .isInstanceOf(ClockKind.Internal.class);
        assertThat(AsioBackend.classifyClockKind("Word Clock"))
                .isInstanceOf(ClockKind.WordClock.class);
        assertThat(AsioBackend.classifyClockKind("WordClock In"))
                .isInstanceOf(ClockKind.WordClock.class);
        assertThat(AsioBackend.classifyClockKind("S/PDIF"))
                .isInstanceOf(ClockKind.Spdif.class);
        assertThat(AsioBackend.classifyClockKind("ADAT 1-8"))
                .isInstanceOf(ClockKind.Adat.class);
        assertThat(AsioBackend.classifyClockKind("AES/EBU"))
                .isInstanceOf(ClockKind.Aes.class);
        assertThat(AsioBackend.classifyClockKind("Dante"))
                .isInstanceOf(ClockKind.External.class);
        assertThat(AsioBackend.classifyClockKind(null))
                .isInstanceOf(ClockKind.External.class);
    }

    /**
     * Test stub: a {@link AsioCapabilityShim} subclass exposing
     * caller-supplied clock-source rows and a configurable
     * {@code ASIOSetClockSource} return code. The library lookup in
     * the superclass constructor is permitted to fail silently — the
     * stub answers the four base queries from defaults and overrides
     * the clock-source surface entirely.
     */
    private static final class StubShim extends AsioCapabilityShim {
        private final boolean shimAvailable;
        private final boolean clockSymbols;
        private final List<RawClockSource> rows;
        private final int selectError;
        private AtomicInteger counter;

        private StubShim(boolean shimAvailable,
                         boolean clockSymbols,
                         List<RawClockSource> rows,
                         int selectError) {
            super();
            this.shimAvailable = shimAvailable;
            this.clockSymbols = clockSymbols;
            this.rows = List.copyOf(rows);
            this.selectError = selectError;
        }

        static StubShim withClockSources(List<RawClockSource> rows, int selectError) {
            return new StubShim(true, true, rows, selectError);
        }

        static StubShim withClockSources(List<RawClockSource> rows, int selectError,
                                         boolean clockSymbols) {
            return new StubShim(true, clockSymbols, rows, selectError);
        }

        static StubShim unavailable() {
            return new StubShim(false, false, List.of(), 0);
        }

        void setSelectionCounter(AtomicInteger counter) {
            this.counter = counter;
        }

        @Override boolean isAvailable() { return shimAvailable; }
        @Override boolean isClockSourceAvailable() { return shimAvailable && clockSymbols; }
        @Override List<RawClockSource> getClockSources() {
            return clockSymbols ? rows : List.of();
        }
        @Override int setClockSource(int reference) {
            if (counter != null) counter.incrementAndGet();
            if (!isClockSourceAvailable()) return -1000;
            return selectError;
        }

        // The rest of the surface is irrelevant for these tests but the
        // base class methods may still get called via inheritance —
        // return safe defaults.
        @Override Optional<BufferSizeRange> getBufferSize() { return Optional.empty(); }
        @Override boolean canSampleRate(double rate) { return false; }
        @Override Optional<Double> getSampleRate() { return Optional.empty(); }
        @Override boolean setSampleRate(double rate) { return false; }
    }
}
