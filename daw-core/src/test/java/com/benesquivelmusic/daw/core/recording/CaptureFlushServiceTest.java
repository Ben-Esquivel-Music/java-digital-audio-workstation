package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CaptureFlushService} driven directly through its ring (story 323;
 * context D9): the cases the engine cannot produce on demand — a source
 * that delivers fewer channels than the routed width, an instrument source
 * that goes absent after it has delivered, a take whose very first
 * manifest write is refused, and the termination signal of a thread that
 * never ran; and the rollbacks that must not act while a held thread may
 * still write (story 323 review).
 */
class CaptureFlushServiceTest {

    private static final int SLOT_FRAMES = 8;
    private static final AudioFormat STEREO_16 = new AudioFormat(48_000.0, 2, 16, SLOT_FRAMES);
    private static final long GIB = 1L << 30;
    private static final long SECOND = 1_000_000_000L;
    private static final Duration GUARD = Duration.ofSeconds(10);
    /** How long the held pass waits for a hold that (wrongly) returned without it; it runs out when the hold is right. */
    private static final Duration HOLD_PROBE = Duration.ofMillis(500);

    @TempDir
    Path takeDir;

    private final AtomicLong clock = new AtomicLong();
    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private CaptureFlushService service;

    @AfterEach
    void stopTheFlushThread() {
        if (service != null) {
            service.close();
        }
    }

    /** One armed graph-instrument track (routing NONE) recording ring source 1. */
    private TrackCapture instrumentCapture(Track track) {
        return instrumentCapture(track, (t, dir) -> new RecordingSession(STEREO_16, dir));
    }

    private TrackCapture instrumentCapture(Track track, TrackCapture.SessionFactory sessions) {
        return new TrackCapture(track, InputRouting.NONE, 1, STEREO_16.channels(), SLOT_FRAMES, 0L, 0.0,
                STEREO_16.sampleRate(), 120.0, takeDir.resolve(track.getId()), sessions);
    }

    private CaptureFlushService newService(CaptureRing ring, TrackCapture capture) {
        CaptureFlushService.TakeConfig config = new CaptureFlushService.TakeConfig(takeDir, STEREO_16, 120.0,
                0.0, 0L, null, false, Duration.ofSeconds(5), Instant.parse("2026-09-29T10:00:00Z"));
        DiskHeadroomWatch watch = new DiskHeadroomWatch(takeDir, () -> 10 * GIB, GIB, 64L << 20,
                Duration.ZERO, clock::get, warnings::add);
        return new CaptureFlushService(ring, config, List.of(capture), watch, warnings::add, clock::get);
    }

    private static float[][] block(int channels, float value) {
        float[][] block = new float[channels][SLOT_FRAMES];
        for (float[] row : block) {
            Arrays.fill(row, value);
        }
        return block;
    }

    /**
     * Publishes one block; a {@code null} instrument marks source 1 absent,
     * as the callback does when the engine hands it no recording buffer.
     */
    private void publish(CaptureRing ring, int blockIndex, float[][] instrument, int instrumentChannels) {
        CaptureRing.Slot slot = ring.claim();
        assertThat(slot).as("fixture: the ring has room").isNotNull();
        slot.setNumFrames(SLOT_FRAMES);
        slot.setStartFrame((long) blockIndex * SLOT_FRAMES);
        slot.setBeatPosition(blockIndex * 0.001);
        slot.copySource(0, block(2, 0f), 2, SLOT_FRAMES);
        if (instrument == null) {
            slot.clearSource(1);
        } else {
            slot.copySource(1, instrument, instrumentChannels, SLOT_FRAMES);
        }
        ring.publish();
        service.signal();
    }

    @Test
    void anInstrumentSourceNarrowerThanTheRoutedWidthLeavesNoStaleSamples() {
        Track synth = new Track("Synth", TrackType.AUDIO);
        TrackCapture capture = instrumentCapture(synth);
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 2, 2, 8);
        service = newService(ring, capture);
        service.start();

        publish(ring, 0, block(2, 0.5f), 2);  // both instrument channels present
        publish(ring, 1, block(2, 0.25f), 1); // this block delivers ONE channel only
        service.awaitFlushed(GUARD);

        assertThat(ring.peek()).isNull();
        float[][] captured = capture.session().getCapturedAudio();
        assertThat(captured).hasNumberOfRows(2);
        assertThat(captured[0]).hasSize(2 * SLOT_FRAMES);
        assertThat(Arrays.copyOfRange(captured[0], 0, SLOT_FRAMES)).containsOnly(0.5f);
        assertThat(Arrays.copyOfRange(captured[0], SLOT_FRAMES, 2 * SLOT_FRAMES)).containsOnly(0.25f);
        assertThat(Arrays.copyOfRange(captured[1], 0, SLOT_FRAMES))
                .as("fixture: the first block really wrote the second row").containsOnly(0.5f);
        assertThat(Arrays.copyOfRange(captured[1], SLOT_FRAMES, 2 * SLOT_FRAMES))
                .as("the row the second block did not deliver is silent, not the first block's samples")
                .containsOnly(0f);
        assertThat(warnings).isEmpty();
    }

    @Test
    void bytesOfAnInstrumentWhoseSourceWentAbsentAreForcedOnCadence() {
        // A graph instrument the callback found a recording buffer for, and
        // then did not: the blocks after that route nothing to its session,
        // so no append of its own will ever run the cadence check again.
        Track synth = new Track("Synth", TrackType.AUDIO);
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        TrackCapture capture = instrumentCapture(synth, (t, dir) -> {
            RecordingSession session = new RecordingSession(STEREO_16, dir,
                    RecordingSession.DEFAULT_MAX_SEGMENT_DURATION, RecordingSession.DEFAULT_MAX_SEGMENT_BYTES,
                    SegmentWriter.DEFAULT_FORCE_CADENCE, clock::get);
            session.setChannelOpener(journal.opener(SegmentWriter.CREATE_NEW_CHANNEL));
            return session;
        });
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 2, 2, 8);
        service = newService(ring, capture);
        service.start();
        List<Long> forcesSeen = new CopyOnWriteArrayList<>();
        service.setBlockObserver((sequence, startFrame, numFrames) -> forcesSeen.add(journal.forces(false)));

        clock.set(SECOND);
        publish(ring, 0, block(2, 0.5f), 2);  // the instrument delivered: recorded
        service.awaitFlushed(GUARD);
        SegmentWriter writer = capture.session().getCurrentWriter();
        assertThat(writer.frameCount()).as("fixture: the first block was recorded").isEqualTo(SLOT_FRAMES);
        assertThat(writer.bytesSinceForce()).as("fixture: and is not forced yet").isPositive();
        assertThat(journal.forces(false)).isZero();

        // Held between passes while the clock crosses the 5 s cadence and two
        // blocks without the instrument are published; one pass applies both.
        service.setDrainPaused(true);
        clock.set(5 * SECOND);
        publish(ring, 1, null, 0);
        publish(ring, 2, null, 0);
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);

        assertThat(writer.frameCount()).as("the absent source appended nothing").isEqualTo(SLOT_FRAMES);
        assertThat(writer.bytesSinceForce()).as("the instrument's bytes are forced").isZero();
        assertThat(writer.forceCount()).isEqualTo(1);
        service.close(); // returns once the thread has terminated, which publishes the observer's list
        assertThat(forcesSeen).as("force(false) calls on the channel as each block was finished")
                .containsExactly(0L, 1L, 1L);
        assertThat(journal.forces(false)).as("the seal adds no cadence force").isEqualTo(1);
        assertThat(warnings).isEmpty();
    }

    @Test
    void holdingTheDrainReturnsOnlyOnceThePassInFlightHasEnded() throws InterruptedException {
        // The cadence tests move the clock while the loop is held; that is
        // only sound if no pass that started before the hold is still
        // running — its end-of-pass tick would read the new time.
        Track synth = new Track("Synth", TrackType.AUDIO);
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 2, 2, 8);
        service = newService(ring, instrumentCapture(synth));
        service.start();
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch inPass = new CountDownLatch(1);
        CountDownLatch holdReturned = new CountDownLatch(1);
        service.setBlockObserver((sequence, startFrame, numFrames) -> {
            inPass.countDown();
            try {
                // A hold that returned while this pass is held here would
                // release this wait at once; one that waits for the pass
                // lets it run out.
                holdReturned.await(HOLD_PROBE.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            order.add("block finished");
        });
        publish(ring, 0, block(2, 0.5f), 2);
        assertThat(inPass.await(GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .as("fixture: the flush thread is inside a pass").isTrue();

        service.setDrainPaused(true); // bounded by DEFAULT_AWAIT_TIMEOUT
        order.add("hold returned");
        holdReturned.countDown();

        assertThat(order).containsExactly("block finished", "hold returned");
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);
        assertThat(service.appliedBlocks()).isEqualTo(1);
    }

    @Test
    void aRefusedInitialManifestWriteFailsTheStartAtOnceAndLeavesNothing() throws IOException {
        Track synth = new Track("Synth", TrackType.AUDIO);
        TrackCapture capture = instrumentCapture(synth);
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 2, 2, 8);
        service = newService(ring, capture);

        // One refused attempt: a retry would get through, so a start that
        // fails proves the caller thread does not sit in a retry loop.
        service.failNextManifestWrites(1);

        assertThatThrownBy(service::start).isInstanceOf(UncheckedIOException.class)
                .hasRootCauseMessage("injected manifest write failure (test seam) under " + takeDir);
        assertThat(service.isRunning()).isFalse();
        assertThat(service.thread().isAlive()).as("the flush thread was never started").isFalse();
        assertThat(service.manifestWrites()).isZero();
        try (Stream<Path> entries = Files.list(takeDir)) {
            assertThat(entries).as("the failed start left the take directory empty").isEmpty();
        }
    }

    @Test
    void aServiceWhoseStartFailedHasTerminatedAndItsStopNeverWaits() {
        Track synth = new Track("Synth", TrackType.AUDIO);
        service = newService(new CaptureRing(SLOT_FRAMES, 2, 2, 8), instrumentCapture(synth));
        service.failNextManifestWrites(1);
        assertThatThrownBy(service::start).isInstanceOf(UncheckedIOException.class);

        assertThat(service.isTerminated()).as("a thread that never ran will never touch the take").isTrue();
        assertThat(service.termination().toCompletableFuture().isDone()).isTrue();
        service.setStopJoinTimeout(Duration.ofMillis(1));
        assertThat(service.stopAndSeal(TakeManifest.SealedBy.STOP)).containsOnlyKeys(synth.getId());
    }

    @Test
    void aServiceStoppedBeforeItWasStartedHasTerminatedAndRefusesAStart() throws IOException {
        Track synth = new Track("Synth", TrackType.AUDIO);
        service = newService(new CaptureRing(SLOT_FRAMES, 2, 2, 8), instrumentCapture(synth));
        assertThat(service.isTerminated()).as("fixture: an unstarted service may still start").isFalse();

        service.stopAndSeal(TakeManifest.SealedBy.STOP);

        assertThat(service.isTerminated()).isTrue();
        assertThat(service.termination().toCompletableFuture().isDone()).isTrue();
        assertThatThrownBy(service::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stopped before it was started");
        try (Stream<Path> entries = Files.list(takeDir)) {
            assertThat(entries).as("nothing was ever created").isEmpty();
        }
    }

    /** Holds the flush thread in the block observer until {@code release} (bounded). */
    private CountDownLatch holdTheFlushThreadInItsNextBlock(CountDownLatch release) {
        CountDownLatch held = new CountDownLatch(1);
        service.setBlockObserver((sequence, startFrame, numFrames) -> {
            held.countDown();
            try {
                release.await(GUARD.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        return held;
    }

    private Map<Path, Long> takeFiles() throws IOException {
        Map<Path, Long> sizes = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(takeDir)) {
            for (Path path : paths.sorted().toList()) {
                sizes.put(path, Files.isRegularFile(path) ? Files.size(path) : -1L);
            }
        }
        return sizes;
    }

    @Test
    void aStartRollbackWhoseJoinRunsOutDeletesNothingTheThreadMayStillWrite() throws Exception {
        Track synth = new Track("Synth", TrackType.AUDIO);
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 2, 2, 8);
        service = newService(ring, instrumentCapture(synth));
        service.start();
        service.setStopJoinTimeout(Duration.ofMillis(200));
        CountDownLatch release = new CountDownLatch(1);
        try {
            CountDownLatch held = holdTheFlushThreadInItsNextBlock(release);
            publish(ring, 0, block(2, 0.5f), 2);
            assertThat(held.await(GUARD.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            Path part = takeDir.resolve(synth.getId()).resolve("segment-000.wav.part");
            assertThat(part).as("fixture: the lane is streaming").exists();
            Map<Path, Long> before = takeFiles();

            assertThatThrownBy(service::abortStart)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("left in place for recovery");

            assertThat(takeFiles()).as("nothing the thread may still write was deleted").isEqualTo(before);
            assertThat(warnings).anySatisfy(warning -> assertThat(warning).contains("left in place for recovery"));
            assertThat(service.isTerminated()).isFalse();

            release.countDown();
            service.termination().toCompletableFuture().get(GUARD.toMillis(), TimeUnit.MILLISECONDS);
            service.abortStart(); // the thread is gone: now the rollback may delete

            assertThat(part).doesNotExist();
            assertThat(TakeManifest.manifestPath(takeDir)).doesNotExist();
        } finally {
            release.countDown();
        }
    }

    @Test
    void aCrashSimulationWhoseJoinRunsOutAbandonsNothing() throws Exception {
        Track synth = new Track("Synth", TrackType.AUDIO);
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 2, 2, 8);
        TrackCapture capture = instrumentCapture(synth);
        service = newService(ring, capture);
        service.start();
        service.setStopJoinTimeout(Duration.ofMillis(200));
        CountDownLatch release = new CountDownLatch(1);
        try {
            CountDownLatch held = holdTheFlushThreadInItsNextBlock(release);
            publish(ring, 0, block(2, 0.5f), 2);
            assertThat(held.await(GUARD.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

            assertThatThrownBy(service::simulateHardTermination)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("nothing was abandoned");
            assertThat(capture.session().isActive()).as("the session the thread holds was not abandoned").isTrue();
            assertThat(capture.session().getCurrentWriter().isStreaming())
                    .as("the writer the thread holds is still streaming").isTrue();

            release.countDown();
            service.termination().toCompletableFuture().get(GUARD.toMillis(), TimeUnit.MILLISECONDS);
            service.simulateHardTermination(); // the thread is gone: now the crash may be simulated
        } finally {
            release.countDown();
        }
    }
}
