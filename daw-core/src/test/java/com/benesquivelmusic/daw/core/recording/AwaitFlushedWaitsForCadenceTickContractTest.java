package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.transport.PunchRegion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.BLOCK_FRAMES;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.HANG_GUARD;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.MONO_16;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.advanceOneBlock;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.outcomeWithinTheGuard;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.rampBlock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 323 review probe (Copilot 5365941737, MEDIUM — force cadence): the
 * cadence tick that follows a block is part of that block's application, so
 * {@code awaitFlushed} — "every block published before this call has been
 * applied — routed, written, counted, its manifest rewrite done and the
 * cadence tick after it run" — does not pass a block whose tick is still
 * forcing. The block here is gated (past a transport punch-out), so the
 * force can only come from the tick, never from an append; the tick's
 * {@code force(false)} is held on a latch through the channel opener seam.
 *
 * <p>Every wait is bounded: the hold and the final stop by
 * {@link RampCaptureTestSupport#HANG_GUARD}, the fences by
 * {@link CaptureFlushService#DEFAULT_AWAIT_TIMEOUT}, and the fence that
 * must not pass by {@link #FENCE_PROBE} — a wait that always runs out while
 * the code is right.</p>
 */
class AwaitFlushedWaitsForCadenceTickContractTest {

    private static final long SECOND = 1_000_000_000L;
    private static final long BLOCK_BYTES = (long) BLOCK_FRAMES * RampCaptureTestSupport.BYTES_PER_FRAME_MONO_16;
    private static final long GIB = 1L << 30;
    /** The fence that must not pass while the tick is held. */
    private static final Duration FENCE_PROBE = Duration.ofMillis(200);

    @TempDir
    Path takeDir;

    private RecordingPipeline pipeline;
    private final List<String> warnings = new CopyOnWriteArrayList<>();
    /** Armed by the test: the next delegated {@code force} holds the forcing thread until {@link #release}. */
    private final AtomicBoolean holdNextForce = new AtomicBoolean();
    private final CountDownLatch held = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    /** A held thread is never left behind: released, then given the guard to finish before the directory goes. */
    @AfterEach
    void releaseAndJoinTheFlushThread() throws InterruptedException {
        release.countDown();
        CaptureFlushService service = pipeline == null ? null : pipeline.getCaptureFlushService();
        if (service != null) {
            service.thread().join(HANG_GUARD.toMillis());
        }
    }

    /** Runs on the flush thread: signals that it is held, then waits for the release (bounded). */
    private void holdHere() {
        held.countDown();
        try {
            release.await(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void feed(AudioEngine engine, Transport transport, long firstFrame) {
        engine.processBlock(rampBlock(firstFrame), new float[1][BLOCK_FRAMES], BLOCK_FRAMES);
        advanceOneBlock(transport);
    }

    @Test
    void theFenceDoesNotPassAGatedBlockWhoseCadenceTickIsStillForcing() throws Exception {
        AudioEngine engine = new AudioEngine(MONO_16);
        Transport transport = new Transport();
        transport.setPunchRegion(new PunchRegion(0L, 2L * BLOCK_FRAMES, true));
        Track track = RampCaptureTestSupport.armedMonoTrack("Vocal");
        AtomicLong clock = new AtomicLong(0);
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        journal.beforeForce(() -> {
            if (holdNextForce.compareAndSet(true, false)) {
                holdHere();
            }
        });
        pipeline = new RecordingPipeline(engine, transport, MONO_16, takeDir, List.of(track));
        pipeline.setNanoClock(clock::get);
        pipeline.setWarningSink(warnings::add);
        // A fixed probe: the real one would warn (or seal) on a nearly full disk.
        pipeline.setDiskHeadroomWatch(new DiskHeadroomWatch(takeDir, () -> 10 * GIB, GIB, 64L << 20,
                Duration.ZERO, clock::get, warnings::add));
        pipeline.setChannelOpener(journal.opener(SegmentWriter.CREATE_NEW_CHANNEL));
        pipeline.start();
        CaptureFlushService service = pipeline.getCaptureFlushService();
        assertThat(pipeline.getForceCadence()).as("fixture: the default cadence").isEqualTo(Duration.ofSeconds(5));

        clock.set(1 * SECOND);
        feed(engine, transport, 0);            // inside the punch region: recorded
        feed(engine, transport, BLOCK_FRAMES); // ends at the punch-out: recorded
        pipeline.awaitFlushed();
        SegmentWriter writer = pipeline.getSession(track).getCurrentWriter();
        assertThat(writer.bytesSinceForce()).as("fixture: two recorded blocks, not forced yet")
                .isEqualTo(2 * BLOCK_BYTES);
        assertThat(journal.forces(false)).isZero();

        // The loop is held between passes while the clock crosses the cadence
        // and one gated block is published: the pass that resumes applies it
        // (no append) and its tick is the first thing to meet the due bytes.
        service.setDrainPaused(true);
        clock.set(5 * SECOND);
        holdNextForce.set(true);
        feed(engine, transport, 2L * BLOCK_FRAMES);
        long published = pipeline.getCaptureRing().publishedBlocks();
        service.setDrainPaused(false);

        assertThat(held.await(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .as("fixture: the flush thread is inside the tick's force(false) of the punched-out bytes").isTrue();
        assertThat(journal.forces(false)).as("fixture: the held force has not reached the channel").isZero();
        assertThat(service.appliedBlocks())
                .as("the gated block whose cadence tick is still forcing is not counted as applied")
                .isLessThan(published);
        assertThatThrownBy(() -> pipeline.awaitFlushed(FENCE_PROBE))
                .as("the fence does not pass a block whose cadence tick is still running")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timed out");

        release.countDown();
        pipeline.awaitFlushed();
        assertThat(journal.forces(false)).isEqualTo(1);
        assertThat(writer.bytesSinceForce()).as("the punched-out bytes are forced").isZero();
        assertThat(writer.lastForceNanos()).isEqualTo(5 * SECOND);
        assertThat(outcomeWithinTheGuard("stop", pipeline::stop)).as("the stop completes").isNull();
        assertThat(journal.forces(false)).as("the seal adds no cadence force").isEqualTo(1);
    }
}
