package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.audio.BackendDropoutCounters;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.benesquivelmusic.daw.core.audio.AudioEngine.CapturedInputLoss.UNREAD;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine's ledger of captured input lost before the capture rings ({@code AudioEngine.CapturedInputLoss},
 * ruling E214): a fold seals a source's counts; no read moves them after that, and no read in flight reads that
 * source's report again.
 */
class CapturedInputLossTest {

    private static final long GUARD_SECONDS = 10L;

    @Test
    void aReadInFlightWhenAnotherSourceIsDetachedNeverReadsThatSourcesReportAgain() throws Exception {
        AudioEngine.CapturedInputLoss loss = new AudioEngine.CapturedInputLoss();
        AudioEngineOutputTest.SynchronousTestBackend first =
                new AudioEngineOutputTest.SynchronousTestBackend("First Input");
        AudioEngineOutputTest.SynchronousTestBackend second =
                new AudioEngineOutputTest.SynchronousTestBackend("Second Input");
        AtomicInteger secondReads = new AtomicInteger();
        AtomicLong secondDrops = new AtomicLong(5L);
        CountDownLatch inTheFirstReport = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong total = new AtomicLong(-1L);
        Thread reader = Thread.ofPlatform().name("captured-input-loss-reader")
                .unstarted(() -> total.set(loss.total(false)));
        first.dropoutReport = () -> {
            if (Thread.currentThread() == reader && inTheFirstReport.getCount() > 0L) {
                inTheFirstReport.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return inputDrops(3L);
        };
        second.dropoutReport = () -> {
            secondReads.incrementAndGet();
            return inputDrops(secondDrops.get());
        };
        loss.attachStream(first);
        loss.attachInput(second);
        int readsAfterTheDetach;
        reader.start();
        try {
            assertThat(inTheFirstReport.await(GUARD_SECONDS, TimeUnit.SECONDS))
                    .as("premise: the reader is inside the first source's report").isTrue();
            secondDrops.set(7L);
            loss.detach(second);
            readsAfterTheDetach = secondReads.get();
        } finally {
            release.countDown();
        }
        reader.join(TimeUnit.SECONDS.toMillis(GUARD_SECONDS));

        assertThat(reader.isAlive()).as("the reader finished").isFalse();
        assertThat(readsAfterTheDetach).as("premise: the attach and the detach each read the second source's report")
                .isEqualTo(2);
        assertThat(secondReads).as("the read in flight never read the sealed source's report again")
                .hasValue(readsAfterTheDetach);
        assertThat(total).as("the second source's two blocks, counted once by its fold").hasValue(2L);
    }

    @Test
    void noReadMovesTheCountsAFoldSealed() {
        AudioEngine.CapturedInputLoss.Counts open = new AudioEngine.CapturedInputLoss.Counts(2L, 5L, false, false);
        AudioEngine.CapturedInputLoss.Counts sealed = new AudioEngine.CapturedInputLoss.Counts(2L, 5L, false, true);
        AudioEngine.CapturedInputLoss.Counts sealedBeforeAGoodRead =
                new AudioEngine.CapturedInputLoss.Counts(UNREAD, UNREAD, false, true);
        AudioEngine.CapturedInputLoss.Counts sealedSkippingAGap =
                new AudioEngine.CapturedInputLoss.Counts(2L, 5L, true, true);

        assertThat(open.after(9L, false)).as("premise: a read moves counts no fold sealed")
                .isEqualTo(new AudioEngine.CapturedInputLoss.Counts(2L, 9L, false, false));
        assertThat(sealed.after(9L, false)).as("a higher good read").isSameAs(sealed);
        assertThat(sealed.after(UNREAD, true)).as("a failed read as a take starts").isSameAs(sealed);
        assertThat(sealedBeforeAGoodRead.after(4L, false)).as("the first good read").isSameAs(sealedBeforeAGoodRead);
        assertThat(sealedSkippingAGap.after(9L, false)).as("the good read after a failed take-start read")
                .isSameAs(sealedSkippingAGap);
    }

    private static BackendDropoutCounters inputDrops(long dropped) {
        return new BackendDropoutCounters(0L, 0L, 0L, 0L, dropped, false);
    }
}
