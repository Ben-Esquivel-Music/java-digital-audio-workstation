package com.benesquivelmusic.daw.core.audio.health;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rate bound of a failure logged off a render thread (story 337, ruling
 * E77): the first failure is always written, then at most one record per
 * 30-second window, each later record stating how many failures occurred
 * since the previous one. The clock is injected.
 */
class OffLaneFailureLogTest {

    private static final Logger LOG = Logger.getLogger(OffLaneFailureLogTest.class.getName());

    private final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    private final Handler capture = new Handler() {
        @Override public void publish(LogRecord entry) { logged.add(entry); }
        @Override public void flush() { }
        @Override public void close() { }
    };
    private final AtomicLong nowNanos = new AtomicLong(1_000L);
    private boolean parentHandlers;

    @BeforeEach
    void captureTheLog() {
        parentHandlers = LOG.getUseParentHandlers();
        LOG.setUseParentHandlers(false);
        LOG.addHandler(capture);
    }

    @AfterEach
    void releaseTheLog() {
        LOG.removeHandler(capture);
        LOG.setUseParentHandlers(parentHandlers);
    }

    @Test
    void theFirstFailureIsAlwaysWritten() {
        OffLaneFailureLog log = new OffLaneFailureLog(LOG, Level.WARNING, nowNanos::get);
        IllegalStateException failure = new IllegalStateException("segment failed");

        log.failed(() -> "Reverb: late-reverb segment 3 failed and was left out of this block", failure);

        assertThat(logged).singleElement().satisfies(entry -> {
            assertThat(entry.getLevel()).isEqualTo(Level.WARNING);
            assertThat(entry.getMessage())
                    .isEqualTo("Reverb: late-reverb segment 3 failed and was left out of this block");
            assertThat(entry.getThrown()).isSameAs(failure);
        });
    }

    @Test
    void laterFailuresInsideTheWindowAreCountedNotWritten() {
        OffLaneFailureLog log = new OffLaneFailureLog(LOG, Level.WARNING, nowNanos::get);

        log.failed(() -> "block failed", null);
        for (int block = 0; block < 5; block++) {
            nowNanos.addAndGet(OffLaneFailureLog.WINDOW_NANOS / 10);
            log.failed(() -> "block failed", null);
        }
        nowNanos.addAndGet(OffLaneFailureLog.WINDOW_NANOS / 2 - 1L);
        log.failed(() -> "block failed", null);

        assertThat(logged).as("every failure up to the window's last nanosecond is only counted")
                .hasSize(1);
    }

    @Test
    void theFirstRecordAfterTheWindowStatesHowManyFailedSinceThePrevious() {
        OffLaneFailureLog log = new OffLaneFailureLog(LOG, Level.WARNING, nowNanos::get);
        IllegalArgumentException latest = new IllegalArgumentException("latest");

        log.failed(() -> "block failed", null);
        for (int block = 0; block < 4; block++) {
            nowNanos.addAndGet(1_000_000L);
            log.failed(() -> "block failed", null);
        }
        nowNanos.addAndGet(OffLaneFailureLog.WINDOW_NANOS);
        log.failed(() -> "block failed", latest);
        nowNanos.addAndGet(OffLaneFailureLog.WINDOW_NANOS);
        log.failed(() -> "block failed", null);

        assertThat(logged).extracting(LogRecord::getMessage).containsExactly(
                "block failed",
                "block failed (5 failures since the previous record)",
                "block failed (1 failure since the previous record)");
        assertThat(logged.get(1).getThrown()).as("a later record carries its own failure").isSameAs(latest);
    }
}
