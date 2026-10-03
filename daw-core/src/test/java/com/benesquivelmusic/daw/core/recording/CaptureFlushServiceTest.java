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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.awaitTermination;
import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.awaitWithinTheGuard;
import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.failureWithinTheGuard;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.outcomeWithinTheGuard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CaptureFlushService} driven directly through its ring (story 323;
 * context D9): the cases the engine cannot produce on demand — a source
 * that delivers fewer channels than the routed width, an instrument source
 * that goes absent after it has delivered, a take whose very first
 * manifest write is refused, and the signals of a thread that never ran;
 * and the requests that end a take while the flush thread is held in a pass,
 * which return at once and leave the files to that thread (story 323
 * review, PR #978).
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

    /** A thread is never left behind: asked to stop, then given the guard to finish before the directory goes. */
    @AfterEach
    void stopTheFlushThread() {
        if (service != null) {
            service.requestStop(TakeManifest.SealedBy.STOP);
            awaitTermination(service);
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

    /** Starts the service and waits (bounded) for the take to be ready: its files exist and the thread drains. */
    private void startAndAwaitReadiness() {
        service.start();
        awaitWithinTheGuard(service.readiness(), "the take's readiness");
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
        startAndAwaitReadiness();

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
        startAndAwaitReadiness();
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
        service.requestStop(TakeManifest.SealedBy.STOP);
        awaitTermination(service); // the thread has terminated, which publishes the observer's list
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
        startAndAwaitReadiness();
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
    void aRefusedInitialManifestWriteFailsTheReadinessAtOnceAndLeavesNothing() throws IOException {
        Track synth = new Track("Synth", TrackType.AUDIO);
        TrackCapture capture = instrumentCapture(synth);
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 2, 2, 8);
        service = newService(ring, capture);

        // One refused attempt: a retry would get through, so a readiness
        // that fails proves the initialisation makes a single attempt.
        service.failNextManifestWrites(1);
        service.start();

        Throwable notReady = failureWithinTheGuard(service.readiness(), "the take's readiness");
        assertThat(notReady).isInstanceOf(UncheckedIOException.class)
                .hasMessage("cannot start capture under " + takeDir)
                .hasRootCauseMessage("injected manifest write failure (test seam) under " + takeDir);
        assertThat(service.isTerminated()).as("terminated before the readiness failed").isTrue();
        assertThat(service.termination().toCompletableFuture().isDone()).isTrue();
        assertThat(service.isRunning()).as("the thread never drained").isFalse();
        assertThat(service.manifestWrites()).isZero();
        try (Stream<Path> entries = Files.list(takeDir)) {
            assertThat(entries).as("the failed start left the take directory empty").isEmpty();
        }
        assertThat(service.earlySeal().toCompletableFuture().isDone()).as("a take never ready never seals early")
                .isFalse();
    }

    @Test
    void aServiceWhoseInitialisationFailedHasTerminatedAndAStopRequestChangesNothing() {
        Track synth = new Track("Synth", TrackType.AUDIO);
        service = newService(new CaptureRing(SLOT_FRAMES, 2, 2, 8), instrumentCapture(synth));
        service.failNextManifestWrites(1);
        service.start();
        assertThat(failureWithinTheGuard(service.readiness(), "the take's readiness"))
                .isInstanceOf(UncheckedIOException.class);

        assertThat(service.isTerminated()).as("a thread that rolled back will never touch the take").isTrue();
        service.requestStop(TakeManifest.SealedBy.STOP);

        assertThat(service.isSealed()).as("nothing to seal").isFalse();
        assertThat(service.sealedSegmentPaths()).containsOnlyKeys(synth.getId());
        assertThat(service.sealedSegmentPaths().get(synth.getId())).isEmpty();
    }

    @Test
    void aServiceStoppedBeforeItWasStartedHasTerminatedFailsItsReadinessAndRefusesAStart() throws IOException {
        Track synth = new Track("Synth", TrackType.AUDIO);
        service = newService(new CaptureRing(SLOT_FRAMES, 2, 2, 8), instrumentCapture(synth));
        assertThat(service.isTerminated()).as("fixture: an unstarted service may still start").isFalse();

        service.requestStop(TakeManifest.SealedBy.STOP);

        assertThat(service.isTerminated()).isTrue();
        assertThat(service.termination().toCompletableFuture().isDone()).isTrue();
        assertThat(failureWithinTheGuard(service.readiness(), "the take's readiness"))
                .as("no dependent of the readiness waits forever").isInstanceOf(CancellationException.class);
        assertThatThrownBy(service::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stopped before it was started");
        try (Stream<Path> entries = Files.list(takeDir)) {
            assertThat(entries).as("nothing was ever created").isEmpty();
        }
    }

    @Test
    void aServiceAbortedBeforeItWasStartedHasTerminatedAndFailsItsReadiness() {
        Track synth = new Track("Synth", TrackType.AUDIO);
        service = newService(new CaptureRing(SLOT_FRAMES, 2, 2, 8), instrumentCapture(synth));

        service.requestAbort();

        assertThat(service.isTerminated()).isTrue();
        assertThat(failureWithinTheGuard(service.readiness(), "the take's readiness"))
                .isInstanceOf(CancellationException.class);
        assertThatThrownBy(service::start).isInstanceOf(IllegalStateException.class);
    }

    /**
     * Holds the flush thread in the block observer until {@code release} —
     * for at most twice the guard, so a request that waited for the hold
     * would still be waiting when {@code outcomeWithinTheGuard} gives up.
     */
    private CountDownLatch holdTheFlushThreadInItsNextBlock(CountDownLatch release) {
        CountDownLatch held = new CountDownLatch(1);
        service.setBlockObserver((sequence, startFrame, numFrames) -> {
            held.countDown();
            try {
                release.await(2 * GUARD.toMillis(), TimeUnit.MILLISECONDS);
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
    void anAbortWhileTheThreadIsHeldInAPassReturnsAtOnceAndTheThreadDiscardsTheTakeOnceThePassEnds()
            throws Exception {
        Track synth = new Track("Synth", TrackType.AUDIO);
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 2, 2, 8);
        service = newService(ring, instrumentCapture(synth));
        startAndAwaitReadiness();
        CountDownLatch release = new CountDownLatch(1);
        try {
            CountDownLatch held = holdTheFlushThreadInItsNextBlock(release);
            publish(ring, 0, block(2, 0.5f), 2);
            assertThat(held.await(GUARD.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            Path part = takeDir.resolve(synth.getId()).resolve("segment-000.wav.part");
            assertThat(part).as("fixture: the lane is streaming").exists();
            Map<Path, Long> before = takeFiles();

            // The guard is far below the hold's own bound: an abort that waited
            // for the held thread would still be waiting when the guard ran out.
            assertThat(outcomeWithinTheGuard("abort", service::requestAbort)).as("the abort returns").isNull();

            assertThat(takeFiles()).as("nothing the held thread may still write was deleted").isEqualTo(before);
            assertThat(service.isTerminated()).isFalse();

            release.countDown();
            awaitTermination(service);

            assertThat(part).as("the flush thread discarded the take's files itself").doesNotExist();
            assertThat(takeDir.resolve(synth.getId())).as("and the track directory it left empty").doesNotExist();
            assertThat(TakeManifest.manifestPath(takeDir)).doesNotExist();
            assertThat(service.isSealed()).as("an abort seals nothing").isFalse();
            assertThat(service.earlySeal().toCompletableFuture().isDone()).isFalse();
            assertThat(service.stopSealFailure()).isEmpty();
        } finally {
            release.countDown();
        }
    }

    @Test
    void anAbandonRequestedWhileTheThreadIsHeldAbandonsNothingUntilThePassEnds() throws Exception {
        Track synth = new Track("Synth", TrackType.AUDIO);
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 2, 2, 8);
        TrackCapture capture = instrumentCapture(synth);
        service = newService(ring, capture);
        startAndAwaitReadiness();
        CountDownLatch release = new CountDownLatch(1);
        try {
            CountDownLatch held = holdTheFlushThreadInItsNextBlock(release);
            publish(ring, 0, block(2, 0.5f), 2);
            assertThat(held.await(GUARD.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

            assertThat(outcomeWithinTheGuard("abandon", service::stopAndAbandon)).as("the request returns").isNull();
            assertThat(capture.session().isActive()).as("the session the thread holds was not abandoned").isTrue();
            assertThat(capture.session().getCurrentWriter().isStreaming())
                    .as("the writer the thread holds is still streaming").isTrue();

            release.countDown();
            awaitTermination(service);

            assertThat(capture.session().isActive()).as("the flush thread abandoned its writer").isFalse();
            assertThat(takeDir.resolve(synth.getId()).resolve("segment-000.wav.part"))
                    .as("an abandoned segment stays a .part").exists();
            assertThat(service.isSealed()).isFalse();
        } finally {
            release.countDown();
        }
    }
}
