package com.benesquivelmusic.daw.app.ui;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The relink flow's workers for its tests (story 329): each job waits until
 * {@link #release()} — so a test decides when a check or a search runs —
 * and the test can wait, bounded, for every job to end before it pulses the
 * dispatcher. An {@linkplain #ignoringInterrupts() interrupt-ignoring}
 * instance runs its jobs to the end even when the flow interrupts them, so a
 * stale result really is posted and only the FX-side fence can drop it.
 */
final class HeldRelinkWorkers implements ThreadFactory {

    private final boolean ignoreInterrupts;
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<Thread> threads = new CopyOnWriteArrayList<>();

    private HeldRelinkWorkers(boolean ignoreInterrupts) {
        this.ignoreInterrupts = ignoreInterrupts;
    }

    /** Workers whose interrupted jobs run at once and see the interrupt. */
    static HeldRelinkWorkers held() {
        return new HeldRelinkWorkers(false);
    }

    /** Workers whose jobs run only on {@link #release()}, to the end, whatever interrupts them. */
    static HeldRelinkWorkers ignoringInterrupts() {
        return new HeldRelinkWorkers(true);
    }

    @Override
    public Thread newThread(Runnable job) {
        Thread thread = Thread.ofVirtual().name("held-relink-" + threads.size()).unstarted(() -> {
            awaitRelease();
            job.run();
        });
        threads.add(thread);
        return thread;
    }

    private void awaitRelease() {
        while (true) {
            try {
                release.await();
                return;
            } catch (InterruptedException interrupted) {
                if (!ignoreInterrupts) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** The jobs started so far. */
    int started() {
        return threads.size();
    }

    /** Lets every held job run, and every later one at once. */
    void release() {
        release.countDown();
    }

    /** Waits, bounded, until every job started so far has ended. */
    void awaitEveryJobEnded() throws InterruptedException {
        for (Thread thread : threads) {
            assertThat(thread.join(OpenProjectFixture.GUARD))
                    .as("relink worker %s ended within %s", thread.getName(), OpenProjectFixture.GUARD).isTrue();
        }
    }
}
