package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.journal.Backpressure;
import com.benesquivelmusic.daw.core.persistence.journal.JournalListener;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wraps the listener of each journal a {@code ProjectJournalCoordinator}
 * opens (its listener-wrap seam; story 433, story 334 ruling R-T1) and holds
 * one report of the journal writer on demand. Every call is delegated. Once
 * {@link #arm() armed}, the first queue-count report on the journal writer's
 * own thread ({@value #WRITER_THREAD}) — made after its batch's fsync and
 * before the segment rotation a checkpoint marker in the batch asked for —
 * counts down {@code entered} and waits for {@link #release()}, so the
 * write stays in flight; the wait is bounded by {@link SaveFlowFixture#GUARD},
 * so a lost release never wedges the writer. Then it delegates. A report on
 * any other thread (a checkpoint's marker accepted on the checkpoint writer,
 * a force flush, a close's drain) is never held.
 *
 * <p>Blocking a {@link JournalListener} breaks its "non-blocking" contract on
 * purpose, to hold the writer: test code only.</p>
 */
final class HeldJournalReport implements UnaryOperator<JournalListener> {

    /** The name of the journal writer's own thread ({@code JournalWriter.start}). */
    static final String WRITER_THREAD = "daw-journal-writer";

    private final AtomicBoolean armed = new AtomicBoolean();
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    /** Holds the next queue-count report on the journal writer's own thread. */
    void arm() {
        armed.set(true);
    }

    /** Lets the held report, and so the journal writer, go on. */
    void release() {
        release.countDown();
    }

    /** Waits, bounded by {@link SaveFlowFixture#GUARD}, until the armed report is held. */
    void awaitEntered() throws InterruptedException {
        assertThat(entered.await(SaveFlowFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .as("the journal writer's report was held within %s", SaveFlowFixture.GUARD).isTrue();
    }

    @Override
    public JournalListener apply(JournalListener reported) {
        Objects.requireNonNull(reported, "reported must not be null");
        return new JournalListener() {
            @Override
            public void onQueuedCountChanged(int queuedSinceCheckpoint) {
                holdIfArmed();
                reported.onQueuedCountChanged(queuedSinceCheckpoint);
            }

            @Override
            public void onBackpressure(Backpressure level) {
                reported.onBackpressure(level);
            }
        };
    }

    private void holdIfArmed() {
        if (!WRITER_THREAD.equals(Thread.currentThread().getName()) || !armed.compareAndSet(true, false)) {
            return;
        }
        entered.countDown();
        try {
            release.await(SaveFlowFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
