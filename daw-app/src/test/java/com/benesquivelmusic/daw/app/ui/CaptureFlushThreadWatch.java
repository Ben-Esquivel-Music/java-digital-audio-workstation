package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.recording.CaptureFlushService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Remembers the {@code capture-flush} threads alive when a test began and,
 * at teardown, joins (bounded) every one that is new since then and reports
 * those that survive. Mirrors daw-core's test-side leak guard without
 * depending on it.
 */
final class CaptureFlushThreadWatch {

    /** How long teardown waits for each new capture-flush thread to end. */
    static final Duration JOIN_BUDGET = Duration.ofSeconds(30);

    private final Set<Thread> aliveAtStart;

    private CaptureFlushThreadWatch(Set<Thread> aliveAtStart) {
        this.aliveAtStart = aliveAtStart;
    }

    /** Snapshots the capture-flush threads alive now. */
    static CaptureFlushThreadWatch snapshot() {
        return new CaptureFlushThreadWatch(liveCaptureFlushThreads());
    }

    /**
     * Joins, bounded by {@link #JOIN_BUDGET} each, every capture-flush thread
     * not alive at the snapshot.
     *
     * @return an error naming the survivors, or {@code null} if none survived
     */
    AssertionError joinNewThreads() throws InterruptedException {
        List<Thread> survivors = new ArrayList<>();
        for (Thread thread : liveCaptureFlushThreads()) {
            if (aliveAtStart.contains(thread)) {
                continue;
            }
            thread.join(JOIN_BUDGET.toMillis());
            if (thread.isAlive()) {
                survivors.add(thread);
            }
        }
        return survivors.isEmpty() ? null : new AssertionError("capture-flush thread(s) still alive "
                + JOIN_BUDGET.toSeconds() + " s after teardown ended the test's takes: " + survivors);
    }

    private static Set<Thread> liveCaptureFlushThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(thread -> thread.getName().equals(CaptureFlushService.THREAD_NAME))
                .collect(Collectors.toSet());
    }
}
