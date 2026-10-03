package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.recording.DiskHeadroomWatch.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DiskHeadroomWatch} — the flush thread's cached free-space check
 * (story 323; book §4.3).
 */
class DiskHeadroomWatchTest {

    private static final long GIB = 1L << 30;
    private static final long MIB = 1L << 20;
    private static final long SECOND = 1_000_000_000L;

    @TempDir
    Path tempDir;

    private final AtomicLong free = new AtomicLong(2 * GIB);
    private final AtomicInteger probeCalls = new AtomicInteger();
    private final AtomicBoolean probeFails = new AtomicBoolean();
    private final List<String> warnings = new ArrayList<>();

    private DiskHeadroomWatch watch(Duration refresh) {
        return new DiskHeadroomWatch(tempDir, () -> {
            probeCalls.incrementAndGet();
            if (probeFails.get()) {
                throw new UncheckedIOException(new IOException("file store unreadable"));
            }
            return free.get();
        }, 1 * GIB, 64 * MIB, refresh, () -> 0L, warnings::add);
    }

    @Test
    void okToLowWarnsOncePerCrossingNotPerTick() {
        DiskHeadroomWatch watch = watch(Duration.ZERO);
        assertThat(watch.check(0)).isEqualTo(State.OK);
        assertThat(warnings).isEmpty();

        free.set(512 * MIB);
        assertThat(watch.check(1)).isEqualTo(State.LOW);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst()).contains("512 MiB", "low-water 1024 MiB", tempDir.toString());

        assertThat(watch.check(2)).isEqualTo(State.LOW);
        assertThat(watch.check(3)).isEqualTo(State.LOW);
        assertThat(warnings).as("staying LOW is silent").hasSize(1);
        assertThat(watch.state()).isEqualTo(State.LOW);
        assertThat(watch.cachedFreeBytes()).isEqualTo(512 * MIB);
    }

    @Test
    void lowToOkToLowWarnsAgain() {
        DiskHeadroomWatch watch = watch(Duration.ZERO);
        free.set(100 * MIB);
        assertThat(watch.check(0)).isEqualTo(State.LOW);
        free.set(5 * GIB);
        assertThat(watch.check(1)).isEqualTo(State.OK);
        assertThat(warnings).hasSize(1);
        free.set(900 * MIB);
        assertThat(watch.check(2)).isEqualTo(State.LOW);

        assertThat(warnings).hasSize(2);
    }

    @Test
    void floorCrossingReportsExhausted() {
        DiskHeadroomWatch watch = watch(Duration.ZERO);
        assertThat(watch.check(0)).isEqualTo(State.OK);
        free.set(64 * MIB);
        assertThat(watch.check(1)).as("exactly the floor is still LOW").isEqualTo(State.LOW);
        free.set(64 * MIB - 1);
        assertThat(watch.check(2)).isEqualTo(State.EXHAUSTED);
        assertThat(warnings).hasSize(2);
        assertThat(warnings.get(1)).contains("exhausted", "floor 64 MiB");

        assertThat(watch.check(3)).isEqualTo(State.EXHAUSTED);
        assertThat(warnings).hasSize(2);
        free.set(10 * GIB);
        assertThat(watch.check(4)).isEqualTo(State.OK);
    }

    /** Collects what {@link DiskHeadroomWatch}'s own logger publishes while a test runs. */
    private static final class CapturingHandler extends Handler {
        final List<LogRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    @Test
    void aSinkThatThrowsOnACrossingIsLoggedAndTheStateIsStillReturned() {
        // A sink is foreign code (story 339 injects one that reaches the UI);
        // if it throws, the warning is lost but the watch's answer is not.
        List<String> offered = new ArrayList<>();
        IllegalStateException sinkFault = new IllegalStateException("injected sink failure");
        DiskHeadroomWatch watch = new DiskHeadroomWatch(tempDir, free::get, GIB, 64 * MIB,
                Duration.ZERO, () -> 0L, message -> {
                    offered.add(message);
                    throw sinkFault;
                });
        Logger watchLogger = Logger.getLogger(DiskHeadroomWatch.class.getName());
        CapturingHandler handler = new CapturingHandler();
        watchLogger.addHandler(handler);
        try {
            assertThat(watch.check(0)).isEqualTo(State.OK);
            assertThat(offered).as("fixture: no crossing yet, the sink was not called").isEmpty();

            free.set(512 * MIB);
            assertThat(watch.check(1)).as("the LOW crossing, with a sink that throws").isEqualTo(State.LOW);
            assertThat(watch.state()).isEqualTo(State.LOW);
            assertThat(watch.check(2)).as("staying LOW does not call the sink again").isEqualTo(State.LOW);
            assertThat(offered).singleElement()
                    .satisfies(message -> assertThat(message).contains("Disk headroom low", "512 MiB"));

            free.set(1 * MIB);
            assertThat(watch.check(3)).as("the EXHAUSTED crossing, with a sink that throws")
                    .isEqualTo(State.EXHAUSTED);
            assertThat(offered).hasSize(2);
            assertThat(offered.getLast()).contains("Disk exhausted", "floor 64 MiB");
        } finally {
            watchLogger.removeHandler(handler);
        }

        assertThat(handler.records)
                .as("each lost warning is in the log, with the message the sink was given and what it threw")
                .filteredOn(record -> record.getMessage().contains("(warning sink threw)"))
                .hasSize(2)
                .allSatisfy(record -> {
                    assertThat(record.getLevel()).isEqualTo(Level.WARNING);
                    assertThat(record.getThrown()).isSameAs(sinkFault);
                })
                .extracting(LogRecord::getMessage)
                .satisfiesExactly(
                        low -> assertThat(low).contains("Disk headroom low", "512 MiB"),
                        exhausted -> assertThat(exhausted).contains("Disk exhausted", "floor 64 MiB"));
    }

    @Test
    void refreshIntervalIsHonoured() {
        DiskHeadroomWatch watch = watch(Duration.ofSeconds(2));
        assertThat(watch.check(0)).isEqualTo(State.OK);
        assertThat(probeCalls.get()).as("the first check always probes").isEqualTo(1);

        free.set(10 * MIB);
        assertThat(watch.check(1 * SECOND)).as("cached figure inside the interval").isEqualTo(State.OK);
        assertThat(watch.check(2 * SECOND - 1)).isEqualTo(State.OK);
        assertThat(probeCalls.get()).isEqualTo(1);

        assertThat(watch.check(2 * SECOND)).as("interval elapsed: probe and see the drop").isEqualTo(State.EXHAUSTED);
        assertThat(probeCalls.get()).isEqualTo(2);
        assertThat(watch.check(3 * SECOND)).isEqualTo(State.EXHAUSTED);
        assertThat(probeCalls.get()).isEqualTo(2);
        assertThat(watch.check(4 * SECOND)).isEqualTo(State.EXHAUSTED);
        assertThat(probeCalls.get()).isEqualTo(3);
        assertThat(watch.refresh()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void probeFailureKeepsTheLastFigureOnceAndEscalatesOnRepeat() {
        DiskHeadroomWatch watch = watch(Duration.ZERO);
        assertThat(watch.check(0)).isEqualTo(State.OK);

        probeFails.set(true);
        assertThat(watch.check(1)).as("one failure keeps the previous figure").isEqualTo(State.OK);
        assertThat(watch.consecutiveProbeFailures()).isEqualTo(1);
        assertThat(watch.cachedFreeBytes()).isEqualTo(2 * GIB);
        assertThat(warnings).isEmpty();

        assertThat(watch.check(2)).as("a repeat failure escalates").isEqualTo(State.EXHAUSTED);
        assertThat(watch.consecutiveProbeFailures()).isEqualTo(2);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst()).contains("2 consecutive");

        probeFails.set(false);
        assertThat(watch.check(3)).isEqualTo(State.OK);
        assertThat(watch.consecutiveProbeFailures()).isZero();
    }

    @Test
    void probeFailureBeforeAnyFigureIsOkOnceThenExhausted() {
        probeFails.set(true);
        DiskHeadroomWatch watch = watch(Duration.ZERO);
        assertThat(watch.check(0)).isEqualTo(State.OK);
        assertThat(watch.cachedFreeBytes()).isEqualTo(-1);
        assertThat(watch.check(1)).isEqualTo(State.EXHAUSTED);
    }

    @Test
    void productionProbeReadsTheRealFileStoreAndMapsIoFailuresToUnchecked() {
        assertThat(DiskHeadroomWatch.usableSpaceProbe(tempDir).getAsLong()).isPositive();

        DiskHeadroomWatch watch = DiskHeadroomWatch.forDirectory(tempDir, warnings::add);
        watch.check(0);
        assertThat(watch.cachedFreeBytes()).isPositive();
        assertThat(watch.consecutiveProbeFailures()).isZero();
        assertThat(watch.lowWaterBytes()).isEqualTo(DiskHeadroomWatch.DEFAULT_LOW_WATER_BYTES);
        assertThat(watch.floorBytes()).isEqualTo(DiskHeadroomWatch.DEFAULT_FLOOR_BYTES);
        assertThat(watch.root()).isEqualTo(tempDir);

        Path missing = tempDir.resolve("does-not-exist").resolve("deeper");
        assertThatThrownBy(() -> DiskHeadroomWatch.usableSpaceProbe(missing).getAsLong())
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void checkWithoutArgumentUsesTheInjectedClock() {
        AtomicLong clock = new AtomicLong();
        DiskHeadroomWatch watch = new DiskHeadroomWatch(tempDir, () -> {
            probeCalls.incrementAndGet();
            return free.get();
        }, GIB, 64 * MIB, Duration.ofSeconds(2), clock::get, warnings::add);
        watch.check();
        clock.set(SECOND);
        watch.check();
        assertThat(probeCalls.get()).isEqualTo(1);
        clock.set(2 * SECOND);
        watch.check();
        assertThat(probeCalls.get()).isEqualTo(2);
    }

    @Test
    void rejectsInconsistentThresholds() {
        assertThatThrownBy(() -> new DiskHeadroomWatch(tempDir, () -> 0L, 10, 20, Duration.ZERO, () -> 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DiskHeadroomWatch(tempDir, () -> 0L, 10, -1, Duration.ZERO, () -> 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DiskHeadroomWatch(tempDir, () -> 0L, 10, 5, Duration.ofSeconds(-1), () -> 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
