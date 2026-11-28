package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.awaitTermination;
import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.awaitWithinTheGuard;
import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.failureWithinTheGuard;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.outcomeWithinTheGuard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
@Tag("slow")
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
        return new TrackCapture(track, InputRouting.NONE, 1, STEREO_16.channels(), SLOT_FRAMES, 0L,
                STEREO_16.sampleRate(), 120.0, takeDir.resolve(track.getId()), sessions);
    }

    private CaptureFlushService newService(CaptureRing ring, TrackCapture capture) {
        return newService(ring, List.of(capture), null);
    }

    private CaptureFlushService newService(CaptureRing ring, List<TrackCapture> captures) {
        return newService(ring, captures, null);
    }

    private CaptureFlushService newService(CaptureRing ring, List<TrackCapture> captures, PunchRange legacy) {
        CaptureFlushService.TakeConfig config = new CaptureFlushService.TakeConfig(takeDir, STEREO_16, 120.0,
                0.0, 0L, legacy, false, Duration.ofSeconds(5), Instant.parse("2026-09-29T10:00:00Z"));
        DiskHeadroomWatch watch = new DiskHeadroomWatch(takeDir, () -> 10 * GIB, GIB, 64L << 20,
                Duration.ZERO, clock::get, warnings::add);
        return new CaptureFlushService(ring, config, List.copyOf(captures), watch, warnings::add, clock::get);
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

    @Test
    void eachInputRetainsItsOwnPunchEntryAndReentryFadeState() {
        Track first = new Track("First", TrackType.AUDIO);
        Track second = new Track("Second", TrackType.AUDIO);
        TrackCapture firstCapture = physicalPunchCapture(first, 0);
        TrackCapture secondCapture = physicalPunchCapture(second, 1);
        CaptureRing firstRing = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        CaptureRing secondRing = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        CaptureFlushService.TakeConfig config = new CaptureFlushService.TakeConfig(takeDir, STEREO_16, 120,
                0, 0, null, false, Duration.ofSeconds(5), Instant.parse("2026-09-29T10:00:00Z"));
        DiskHeadroomWatch watch = new DiskHeadroomWatch(takeDir, () -> 10 * GIB, GIB, 64L << 20,
                Duration.ZERO, clock::get, warnings::add);
        service = new CaptureFlushService(firstRing, config, List.of(firstCapture, secondCapture), watch,
                warnings::add, clock::get);
        service.setInputRings(new CaptureRing[]{firstRing, secondRing});
        service.setDrainPaused(true);
        startAndAwaitReadiness();
        publishPunch(firstRing, 120);
        publishPunch(secondRing, 120);
        flushPunchPass();
        assertThat(RecordedAudioTestSupport.audioOnDisk(firstCapture.session())[0][0]).isZero();
        assertThat(RecordedAudioTestSupport.audioOnDisk(secondCapture.session())[0][0]).isZero();

        service.setDrainPaused(true);
        publishPunch(firstRing, 80);
        publishPunch(secondRing, 128);
        flushPunchPass();
        assertThat(firstCapture.session().getTotalSamplesRecorded()).isEqualTo(8);
        assertThat(RecordedAudioTestSupport.audioOnDisk(secondCapture.session())[0][8]).isEqualTo(.5f);

        service.setDrainPaused(true);
        publishPunch(firstRing, 128);
        publishPunch(secondRing, 80);
        flushPunchPass();
        assertThat(RecordedAudioTestSupport.audioOnDisk(firstCapture.session())[0][8]).isZero();
        service.setDrainPaused(true);
        publishPunch(firstRing, 136);
        publishPunch(secondRing, 120);
        flushPunchPass();
        assertThat(RecordedAudioTestSupport.audioOnDisk(firstCapture.session())[0][16]).isEqualTo(.5f);
        assertThat(RecordedAudioTestSupport.audioOnDisk(secondCapture.session())[0][16]).isZero();
    }

    private TrackCapture physicalPunchCapture(Track track, int source) {
        TrackCapture capture = new TrackCapture(track, new InputRouting(0, 1), -1, 1, SLOT_FRAMES, 0,
                STEREO_16.sampleRate(), 120, takeDir.resolve(track.getId()),
                (t, dir) -> new RecordingSession(new AudioFormat(48_000, 1, 16, SLOT_FRAMES), dir));
        capture.setInputSource(source, "Input " + source);
        return capture;
    }

    private void publishPunch(CaptureRing ring, long frame) {
        publishPunch(ring, frame, 200);
    }

    private void publishPunch(CaptureRing ring, long frame, long punchOut) {
        CaptureRing.Slot slot = ring.claim(CaptureRing.DELIVERED);
        assertThat(slot).isNotNull();
        slot.setNumFrames(SLOT_FRAMES);
        slot.setStartFrame(frame);
        slot.setBeatPosition(frame / 24_000.0);
        slot.setPunchEnabled(true);
        slot.setPunchStartFrames(100);
        slot.setPunchEndFrames(punchOut);
        slot.copySource(0, block(1, .5f), 1, SLOT_FRAMES);
        ring.publish();
        service.signal();
    }

    /** One mono block of a physical input at {@code frame}, with no punch region. */
    private void publishInput(CaptureRing ring, long frame) {
        CaptureRing.Slot slot = ring.claim(CaptureRing.DELIVERED);
        assertThat(slot).as("fixture: the ring has room").isNotNull();
        slot.setNumFrames(SLOT_FRAMES);
        slot.setStartFrame(frame);
        slot.setBeatPosition(frame / 24_000.0);
        slot.copySource(0, block(1, .5f), 1, SLOT_FRAMES);
        ring.publish();
        service.signal();
    }

    private static final long NO = CaptureRing.NO_OUTPUT_CLOCK_FRAME;

    /** A block's punch region and loop window; {@link #NONE} for neither. */
    private record Region(boolean punch, long in, long out, long loopStart, long loopEnd) {
        static final Region NONE = new Region(false, 0, 0, 0, 0);

        static Region of(long in, long out) {
            return new Region(true, in, out, 0, 0);
        }

        static Region looped(long in, long out, long loopStart, long loopEnd) {
            return new Region(true, in, out, loopStart, loopEnd);
        }

        void stamp(CaptureRing.Slot slot) {
            slot.setPunchEnabled(punch);
            slot.setPunchStartFrames(in);
            slot.setPunchEndFrames(out);
            slot.setLoopEnabled(loopEnd > loopStart);
            slot.setLoopStartFrame(loopStart);
            slot.setLoopEndFrame(loopEnd);
        }
    }

    /** One block at {@code frame} with {@code channels} channels of .5f, 0 for the silence after a declared stop. */
    private void publishBlock(CaptureRing ring, long frame, int channels, Region region, long outputClockFrame) {
        CaptureRing.Slot slot = ring.claim(channels > 0 ? CaptureRing.DELIVERED : 0);
        assertThat(slot).as("fixture: the ring has room").isNotNull();
        slot.setNumFrames(SLOT_FRAMES);
        slot.setStartFrame(frame);
        slot.setBeatPosition(frame / 24_000.0);
        region.stamp(slot);
        slot.copySource(0, block(channels, .5f), channels, SLOT_FRAMES);
        slot.setOutputClockFrame(outputClockFrame);
        ring.publish();
        service.signal();
    }

    private void publishSilence(CaptureRing ring, long frame, long outputClockFrame) {
        publishBlock(ring, frame, 0, Region.NONE, outputClockFrame);
    }

    /** One input-starved block: the render's zeros for an input still live (ruling E281). */
    private void publishStarved(CaptureRing ring, long frame, Region region) {
        CaptureRing.Slot slot = ring.claim(CaptureRing.STARVED);
        assertThat(slot).as("fixture: the ring has room").isNotNull();
        slot.setNumFrames(SLOT_FRAMES);
        slot.setStartFrame(frame);
        slot.setBeatPosition(frame / 24_000.0);
        region.stamp(slot);
        slot.copySource(0, block(1, 0f), 1, SLOT_FRAMES);
        slot.setInputStarved(true);
        ring.publish();
        service.signal();
    }

    /** A recorded instrument's block on source 1 with the stream's input stopped: nothing on source 0. */
    private void publishInstrumentOnly(CaptureRing ring, long frame, Region region) {
        CaptureRing.Slot slot = ring.claim(CaptureRing.INSTRUMENT);
        assertThat(slot).as("fixture: the ring has room").isNotNull();
        slot.setNumFrames(SLOT_FRAMES);
        slot.setStartFrame(frame);
        slot.setBeatPosition(frame / 24_000.0);
        region.stamp(slot);
        slot.copySource(0, new float[0][], 0, SLOT_FRAMES);
        slot.copySource(1, block(2, .5f), 2, SLOT_FRAMES);
        ring.publish();
        service.signal();
    }

    /**
     * Holds the drain, fills {@code ring} from {@code firstFrame} with blocks of {@code fill}, 0 for the silence
     * after a declared stop, then drops {@code drops}.
     */
    private void fillWhileHeldThenDrop(CaptureRing ring, long firstFrame, int fill, Region region, int... drops) {
        service.setDrainPaused(true);
        for (int block = 0; block < ring.capacity(); block++) {
            long frame = firstFrame + (long) block * SLOT_FRAMES;
            if (fill == CaptureRing.STARVED) {
                publishStarved(ring, frame, region);
            } else {
                publishBlock(ring, frame, fill == 0 ? 0 : 1, region, NO);
            }
        }
        for (int kind : drops) {
            assertThat(ring.claim(kind)).as("fixture: the full ring drops a block").isNull();
        }
    }

    /** "Vocal": a mono physical input on ring source 0, in a one-ring take with room on the disk. */
    private TrackCapture vocalOn(CaptureRing ring, ObservedFileChannel.Journal journal) {
        TrackCapture vocal = journalledPhysicalCapture(new Track("Vocal", TrackType.AUDIO), 0, journal);
        service = newOneInputService(ring, vocal, new AtomicLong(10 * GIB));
        return vocal;
    }

    /** A mono physical-input track recording ring source {@code source}, its writers opened through {@code journal}. */
    private TrackCapture journalledPhysicalCapture(Track track, int source, ObservedFileChannel.Journal journal) {
        AudioFormat mono = new AudioFormat(48_000, 1, 16, SLOT_FRAMES);
        TrackCapture capture = new TrackCapture(track, new InputRouting(0, 1), -1, 1, SLOT_FRAMES, 0,
                STEREO_16.sampleRate(), 120, takeDir.resolve(track.getId()), journalledSessions(mono, journal));
        capture.setInputSource(source, "Input " + source);
        return capture;
    }

    /** Sessions on the test's clock, which never moves unless a test moves it: no cadence force is ever due. */
    private TrackCapture.SessionFactory journalledSessions(AudioFormat format, ObservedFileChannel.Journal journal) {
        return (t, dir) -> {
            RecordingSession session = new RecordingSession(format, dir,
                    RecordingSession.DEFAULT_MAX_SEGMENT_DURATION, RecordingSession.DEFAULT_MAX_SEGMENT_BYTES,
                    SegmentWriter.DEFAULT_FORCE_CADENCE, clock::get);
            session.setChannelOpener(journal.opener(SegmentWriter.CREATE_NEW_CHANNEL));
            return session;
        };
    }

    private CaptureFlushService newTwoInputService(CaptureRing first, CaptureRing second, List<TrackCapture> captures,
                                                   LongSupplier freeBytes) {
        return newInputService(new CaptureRing[]{first, second}, captures, freeBytes);
    }

    /** A service draining {@code rings} through {@link CaptureFlushService#setInputRings}, as production does. */
    private CaptureFlushService newInputService(CaptureRing[] rings, List<TrackCapture> captures, LongSupplier freeBytes) {
        CaptureFlushService.TakeConfig config = new CaptureFlushService.TakeConfig(takeDir, STEREO_16, 120,
                0, 0, null, false, Duration.ofSeconds(5), Instant.parse("2026-09-29T10:00:00Z"));
        DiskHeadroomWatch watch = new DiskHeadroomWatch(takeDir, freeBytes, GIB, 64L << 20,
                Duration.ZERO, clock::get, warnings::add);
        CaptureFlushService inputs = new CaptureFlushService(rings[0], config, captures, watch, warnings::add,
                clock::get);
        inputs.setInputRings(rings);
        return inputs;
    }

    private CaptureFlushService newOneInputService(CaptureRing ring, TrackCapture capture, AtomicLong freeBytes) {
        return newInputService(new CaptureRing[]{ring}, List.of(capture), freeBytes::get);
    }

    /**
     * Once {@code armed} is set, the next force of a writer opened through
     * {@code journal} holds the flush thread until {@code release}. With the
     * test's clock still, no cadence force is due, so that force is a seal's
     * {@code force(true)}: a Stop's, made once the final sweep is done, or an
     * early seal's. The hold has no timeout of its own: a stalled host fails
     * the test as a stall.
     */
    private static CountDownLatch holdTheSealsForceOnceArmed(ObservedFileChannel.Journal journal, AtomicBoolean armed,
                                                             CountDownLatch release) {
        CountDownLatch held = new CountDownLatch(1);
        journal.beforeForce(() -> {
            if (armed.compareAndSet(true, false)) {
                held.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        return held;
    }

    /**
     * Once {@code armed} is set, the next close of a channel opened through
     * {@code journal} holds the flush thread until {@code release}. In a take
     * whose lane wrote no frame, the seal discards the empty segment, closing
     * its channel with no force, once the final sweep is done. The hold has
     * no timeout of its own: a stalled host fails the test as a stall.
     */
    private static CountDownLatch holdTheEmptyLanesDiscardOnceArmed(ObservedFileChannel.Journal journal,
                                                                    AtomicBoolean armed, CountDownLatch release) {
        CountDownLatch held = new CountDownLatch(1);
        journal.beforeClose(() -> {
            if (armed.compareAndSet(true, false)) {
                held.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        return held;
    }

    /**
     * Once {@code armed} is set, the next write through {@code journal} holds the flush thread until
     * {@code release}. The hold has no timeout of its own: a stalled host fails the test as a stall.
     */
    private static CountDownLatch holdTheNextWriteOnceArmed(ObservedFileChannel.Journal journal, AtomicBoolean armed,
                                                            CountDownLatch release) {
        CountDownLatch held = new CountDownLatch(1);
        journal.beforeWrite(() -> {
            if (armed.compareAndSet(true, false)) {
                held.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        return held;
    }

    private void stopAndHoldTheSeal(CountDownLatch held, AtomicBoolean armed) throws InterruptedException {
        stopAndHoldTheSeal(held, armed, TakeManifest.SealedBy.STOP);
    }

    /**
     * Stops the take for {@code reason} and returns once the flush thread is held sealing by {@code held}'s hold, its
     * final sweep done.
     */
    private void stopAndHoldTheSeal(CountDownLatch held, AtomicBoolean armed, TakeManifest.SealedBy reason)
            throws InterruptedException {
        armed.set(true);
        service.requestStop(reason);
        assertThat(held.await(GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .as("fixture: the flush thread swept and is held sealing").isTrue();
    }

    /** Holds the drain, fills {@code ring} with blocks from {@code firstFrame} on, and lets it refuse {@code drops}. */
    private void fillWhileHeldAndDrop(CaptureRing ring, long firstFrame, int drops) {
        service.setDrainPaused(true);
        for (int block = 0; block < ring.capacity(); block++) {
            publishInput(ring, firstFrame + (long) block * SLOT_FRAMES);
        }
        for (int drop = 0; drop < drops; drop++) {
            assertThat(ring.claim(CaptureRing.DELIVERED)).as("fixture: the full ring drops a block").isNull();
        }
    }

    /**
     * Once installed, fills {@code ring} during the pass that applies its first block, publishing past the tail
     * that pass read and then dropping one block, and exhausts the disk, so the next pass's first block meets the
     * headroom floor.
     */
    private void fillDuringTheFirstBlockThenExhaustTheDisk(CaptureRing ring, AtomicLong freeBytes) {
        AtomicBoolean filled = new AtomicBoolean();
        service.setBlockObserver((sequence, startFrame, numFrames) -> {
            if (filled.compareAndSet(false, true)) {
                publishThenDropBehindTheSweep(ring, ring.capacity() - 1, 1);
                freeBytes.set(0L);
            }
        });
    }

    /**
     * What callbacks the flush thread is not keeping up with do to a ring: {@code blocks} published, then
     * {@code drops} refused.
     */
    private static void publishThenDropBehindTheSweep(CaptureRing ring, int blocks, int drops) {
        for (int block = 0; block < blocks; block++) {
            CaptureRing.Slot published = ring.claim(CaptureRing.DELIVERED);
            assertThat(published).as("fixture: the ring has room for block %d", block).isNotNull();
            published.copySource(0, block(1, .5f), 1, SLOT_FRAMES);
            ring.publish();
        }
        for (int drop = 0; drop < drops; drop++) {
            assertThat(ring.claim(CaptureRing.DELIVERED)).as("fixture: the ring is full").isNull();
        }
    }

    private TakeManifest finalManifest() throws IOException {
        return TakeManifest.read(TakeManifest.manifestPath(takeDir));
    }

    /**
     * Stands in for a callback that read the ring full before the final
     * sweep drained it and stored its drop after the sweep's overflow
     * accounting: by the seal's look every block published behind the sweep
     * has been read, and one drop is still unaccounted (ruling E242).
     */
    @Test
    void aDropBehindTheFinalSweepWithNoBlockLeftBehindIsOneGapAtTheEndOfTheLastAppliedBlock() throws Exception {
        Track synth = new Track("Synth", TrackType.AUDIO);
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 2, 2, 8);
        service = newService(ring, instrumentCapture(synth, journalledSessions(STEREO_16, journal)));
        AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch held = holdTheSealsForceOnceArmed(journal, armed, release);
        startAndAwaitReadiness();
        publish(ring, 0, block(2, 0.5f), 2);
        publish(ring, 1, block(2, 0.5f), 2);
        publish(ring, 2, block(2, 0.5f), 2);
        service.awaitFlushed(GUARD);

        try {
            stopAndHoldTheSeal(held, armed);
            publishThenDropBehindTheSweep(ring, ring.capacity(), 1);
            for (int block = 0; block < ring.capacity(); block++) {
                assertThat(ring.peek()).isNotNull();
                ring.release();
            }
        } finally {
            release.countDown();
        }
        awaitTermination(service);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.gaps()).as("one line, at the end of the last block applied")
                .containsExactly(new TakeManifest.GapEntry(TakeManifest.GapEntry.ALL_TRACKS, 3L * SLOT_FRAMES, 1));
        assertThat(manifest.overflowBlocks()).isEqualTo(1);
        assertThat(service.fileOverflowBlocks()).isEqualTo(1);
        assertThat(warnings).as("the ring-overflow warning, and no left-behind warning").containsExactly(
                "Capture ring overflow: 1 block(s) dropped at frame " + 3L * SLOT_FRAMES
                        + " (1 dropped in total); the take manifest records the gap");
    }

    /** Ruling E256: in a punch take each line behind the final sweep counts whole when it reaches into the region. */
    @Test
    void inAPunchTakeEachLineBehindTheFinalSweepCountsOnlyWhenItReachesIntoTheRegion() throws Exception {
        Track track = new Track("Vocal", TrackType.AUDIO);
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        service = newService(ring, journalledPhysicalCapture(track, 0, journal));
        AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch held = holdTheSealsForceOnceArmed(journal, armed, release);
        startAndAwaitReadiness();
        publishPunch(ring, 96, 170);
        publishPunch(ring, 104, 170);
        service.awaitFlushed(GUARD);

        try {
            stopAndHoldTheSeal(held, armed);
            publishThenDropBehindTheSweep(ring, ring.capacity(), 2);
        } finally {
            release.countDown();
        }
        awaitTermination(service);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.gaps()).containsExactly(
                new TakeManifest.GapEntry(TakeManifest.GapEntry.ALL_TRACKS, 112, 8),
                new TakeManifest.GapEntry(TakeManifest.GapEntry.ALL_TRACKS, 176, 2));
        assertThat(manifest.overflowBlocks()).isEqualTo(2);
        assertThat(service.fileOverflowBlocks())
                .as("[112, 176) reaches into the punch region [100, 170); [176, 192) lies after it").isEqualTo(8);
    }

    /**
     * Each ring's late drops are what it dropped beyond what its own part of the final sweep accounted: the second
     * ring's run has its line from the drain, once the block after it is applied, and the first ring's from the
     * seal, which no applied block follows (ruling E275).
     */
    @Test
    void eachRingsLateDropsAreCountedAgainstWhatItsOwnSweepCounted() throws Exception {
        Track first = new Track("First", TrackType.AUDIO);
        Track second = new Track("Second", TrackType.AUDIO);
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing firstRing = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        CaptureRing secondRing = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        service = newTwoInputService(firstRing, secondRing, List.of(journalledPhysicalCapture(first, 0, journal),
                journalledPhysicalCapture(second, 1, journal)), () -> 10 * GIB);
        AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch held = holdTheSealsForceOnceArmed(journal, armed, release);
        service.setDrainPaused(true);
        startAndAwaitReadiness();
        publishInput(firstRing, 0);
        for (int block = 0; block < secondRing.capacity(); block++) {
            publishInput(secondRing, (long) block * SLOT_FRAMES);
        }
        assertThat(secondRing.claim(CaptureRing.DELIVERED))
                .as("fixture: the second ring drops one block while it is full").isNull();
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);
        publishInput(secondRing, 9L * SLOT_FRAMES);
        service.awaitFlushed(GUARD);
        assertThat(service.fileOverflowBlocks()).as("fixture: the drop the drain accounted").isEqualTo(1);

        try {
            stopAndHoldTheSeal(held, armed);
            publishThenDropBehindTheSweep(firstRing, firstRing.capacity(), 2);
        } finally {
            release.countDown();
        }
        awaitTermination(service);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.gaps()).as("the second ring's line from the drain, then the first ring's two")
                .containsExactly(
                        new TakeManifest.GapEntry(second.getId(), 64, 1),
                        new TakeManifest.GapEntry(first.getId(), 8, 8),
                        new TakeManifest.GapEntry(first.getId(), 72, 2));
        assertThat(manifest.overflowBlocks()).as("every drop of both rings").isEqualTo(3);
        assertThat(service.fileOverflowBlocks()).isEqualTo(1 + 8 + 2);
    }

    /**
     * After an early seal the seal's look records nothing: no line, warning
     * or count for the blocks left behind or the late drop, and no
     * {@code overflow-blocks} change (ruling E263). The early seal is an
     * exhausted disk whose final manifest write is refused, so the Stop's
     * seal takes the look; the callbacks behind the final sweep publish to
     * the first ring while the sweep discards the second ring's block.
     */
    @Test
    void afterAnEarlySealTheSealsLookCountsNothingMore() throws Exception {
        Track first = new Track("First", TrackType.AUDIO);
        Track second = new Track("Second", TrackType.AUDIO);
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing firstRing = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        CaptureRing secondRing = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        AtomicLong freeBytes = new AtomicLong(10 * GIB);
        service = newTwoInputService(firstRing, secondRing, List.of(journalledPhysicalCapture(first, 0, journal),
                journalledPhysicalCapture(second, 1, journal)), freeBytes::get);
        service.setDrainPaused(true);
        startAndAwaitReadiness();
        publishInput(firstRing, 0);
        publishInput(secondRing, 0);
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);

        service.setDrainPaused(true);
        service.failNextManifestWrites(CaptureFlushService.MANIFEST_WRITE_ATTEMPTS);
        freeBytes.set(0L);
        publishInput(firstRing, 8);
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);
        assertThat(service.earlySeal().toCompletableFuture().isDone()).as("fixture: the take was sealed early")
                .isTrue();
        assertThat(service.finalManifestFailure()).as("fixture: the early seal's final write was refused")
                .isPresent();
        long fileOverflowBefore = service.fileOverflowBlocks();

        service.setDrainPaused(true);
        publishInput(secondRing, 8);
        AtomicInteger publishedBehindTheSweep = new AtomicInteger();
        AtomicInteger droppedBehindTheSweep = new AtomicInteger();
        service.setBlockObserver((sequence, startFrame, numFrames) -> {
            if (publishedBehindTheSweep.get() > 0) {
                return;
            }
            for (int block = 0; block < firstRing.capacity(); block++) {
                if (firstRing.claim(CaptureRing.DELIVERED) != null) {
                    firstRing.publish();
                    publishedBehindTheSweep.incrementAndGet();
                }
            }
            if (firstRing.claim(CaptureRing.DELIVERED) == null) {
                droppedBehindTheSweep.incrementAndGet();
            }
        });
        service.requestStop(TakeManifest.SealedBy.STOP);
        awaitTermination(service);

        assertThat(publishedBehindTheSweep.get()).as("fixture: the first ring filled behind the sweep")
                .isEqualTo(firstRing.capacity());
        assertThat(droppedBehindTheSweep.get()).as("fixture: and dropped a block").isEqualTo(1);
        TakeManifest manifest = finalManifest();
        assertThat(manifest.sealedBy()).as("fixture: the Stop's seal wrote the final manifest")
                .contains(TakeManifest.SealedBy.DISK_EXHAUSTION);
        assertThat(manifest.gaps()).as("after an early seal the seal's look records nothing").isEmpty();
        assertThat(manifest.overflowBlocks()).isZero();
        assertThat(service.fileOverflowBlocks()).as("the look counted nothing").isEqualTo(fileOverflowBefore);
        assertThat(warnings).noneMatch(warning -> warning.contains("published after the final sweep"));
        assertThat(warnings).noneMatch(warning -> warning.contains("Capture ring overflow"));
    }

    /** The take records no ring loss: no {@code gap=} line, no {@code overflow-blocks}, no count, no warning. */
    private void assertNoRingLossIsRecorded() throws IOException {
        TakeManifest manifest = finalManifest();
        assertThat(manifest.gaps()).isEmpty();
        assertThat(manifest.overflowBlocks()).isZero();
        assertThat(service.fileOverflowBlocks()).isZero();
        assertThat(warnings).noneMatch(warning -> warning.contains("Capture ring overflow"));
    }

    /** Ruling E263: what a ring drops while an early seal is being sealed gets no line, no warning and no count. */
    @Test
    void aDropWhileAnEarlySealIsSealingIsNoLossOfTheTake() throws Exception {
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        AtomicLong freeBytes = new AtomicLong(10 * GIB);
        service = newOneInputService(ring, journalledPhysicalCapture(new Track("Vocal", TrackType.AUDIO), 0, journal),
                freeBytes);
        AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch held = holdTheSealsForceOnceArmed(journal, armed, release);
        startAndAwaitReadiness();
        publishInput(ring, 0);
        service.awaitFlushed(GUARD);

        try {
            armed.set(true);
            freeBytes.set(0L);
            publishInput(ring, 8);
            assertThat(held.await(GUARD.toMillis(), TimeUnit.MILLISECONDS))
                    .as("fixture: the early seal is held sealing").isTrue();
            publishThenDropBehindTheSweep(ring, ring.capacity() - 1, 2);
        } finally {
            release.countDown();
        }
        service.awaitFlushed(GUARD);

        assertThat(awaitWithinTheGuard(service.earlySeal(), "the early seal"))
                .isInstanceOf(EarlySeal.DiskExhausted.class);
        assertNoRingLossIsRecorded();
    }

    /** Ruling E263: the same in a punch take, where the line would lie inside the region and never be counted. */
    @Test
    void aDropWhileAnEarlySealIsSealingIsNoLossOfAPunchTake() throws Exception {
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        AtomicLong freeBytes = new AtomicLong(10 * GIB);
        service = newOneInputService(ring, journalledPhysicalCapture(new Track("Vocal", TrackType.AUDIO), 0, journal),
                freeBytes);
        AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch held = holdTheSealsForceOnceArmed(journal, armed, release);
        startAndAwaitReadiness();
        publishPunch(ring, 96);
        service.awaitFlushed(GUARD);

        try {
            armed.set(true);
            freeBytes.set(0L);
            publishPunch(ring, 104);
            assertThat(held.await(GUARD.toMillis(), TimeUnit.MILLISECONDS))
                    .as("fixture: the early seal is held sealing").isTrue();
            publishThenDropBehindTheSweep(ring, ring.capacity() - 1, 2);
        } finally {
            release.countDown();
        }
        service.awaitFlushed(GUARD);

        assertThat(awaitWithinTheGuard(service.earlySeal(), "the early seal"))
                .isInstanceOf(EarlySeal.DiskExhausted.class);
        assertNoRingLossIsRecorded();
    }

    /**
     * Ruling E263: drops the drain had not noticed when the headroom floor sealed the take get nothing, though the
     * ring made them before the block that sealed it: they lie past every block the take keeps.
     */
    @Test
    void aDropTheDrainHadNotNoticedWhenTheHeadroomFloorSealsTheTakeGetsNothing() throws Exception {
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        AtomicLong freeBytes = new AtomicLong(10 * GIB);
        service = newOneInputService(ring, journalledPhysicalCapture(new Track("Vocal", TrackType.AUDIO), 0, journal),
                freeBytes);
        startAndAwaitReadiness();
        publishInput(ring, 0);
        service.awaitFlushed(GUARD);
        fillWhileHeldAndDrop(ring, SLOT_FRAMES, 2);
        freeBytes.set(0L);
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);

        assertThat(awaitWithinTheGuard(service.earlySeal(), "the early seal"))
                .isInstanceOf(EarlySeal.DiskExhausted.class);
        assertNoRingLossIsRecorded();
        assertThat(service.overflowCount()).as("the ring counts every drop").isEqualTo(2);
    }

    /**
     * Ruling E263: the same in a punch take, with the drops inside the punch region: they still lie past every block
     * the take keeps.
     */
    @Test
    void aDropTheDrainHadNotNoticedWhenTheHeadroomFloorSealsAPunchTakeGetsNothing() throws Exception {
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        AtomicLong freeBytes = new AtomicLong(10 * GIB);
        service = newOneInputService(ring, journalledPhysicalCapture(new Track("Vocal", TrackType.AUDIO), 0, journal),
                freeBytes);
        startAndAwaitReadiness();
        publishPunch(ring, 96);
        publishPunch(ring, 104);
        service.awaitFlushed(GUARD);
        service.setDrainPaused(true);
        for (int block = 0; block < ring.capacity(); block++) {
            publishPunch(ring, 112 + (long) block * SLOT_FRAMES);
        }
        assertThat(ring.claim(CaptureRing.DELIVERED)).as("fixture: the full ring drops a block").isNull();
        assertThat(ring.claim(CaptureRing.DELIVERED)).as("fixture: the full ring drops a block").isNull();
        freeBytes.set(0L);
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);

        assertThat(awaitWithinTheGuard(service.earlySeal(), "the early seal"))
                .isInstanceOf(EarlySeal.DiskExhausted.class);
        assertNoRingLossIsRecorded();
    }

    /**
     * Ruling E263: blocks a ring drops while the write that then fails is held get nothing: they lie after the block
     * whose write failed, which the file never receives.
     */
    @Test
    void aDropWhileTheWriteThatFailsIsHeldIsNoLossOfTheTake() throws Exception {
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        AtomicLong freeBytes = new AtomicLong(10 * GIB);
        service = newOneInputService(ring, journalledPhysicalCapture(new Track("Vocal", TrackType.AUDIO), 0, journal),
                freeBytes);
        AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch held = holdTheNextWriteOnceArmed(journal, armed, release);
        startAndAwaitReadiness();
        publishInput(ring, 0);
        service.awaitFlushed(GUARD);

        try {
            journal.failNextWrites(1);
            armed.set(true);
            publishInput(ring, 8);
            assertThat(held.await(GUARD.toMillis(), TimeUnit.MILLISECONDS))
                    .as("fixture: the block's write is held").isTrue();
            publishThenDropBehindTheSweep(ring, ring.capacity() - 1, 2);
        } finally {
            release.countDown();
        }
        service.awaitFlushed(GUARD);

        assertThat(awaitWithinTheGuard(service.earlySeal(), "the early seal"))
                .isInstanceOf(EarlySeal.WriteFailed.class);
        assertNoRingLossIsRecorded();
    }

    /**
     * Ruling E263: drops the drain had not noticed when a block's write failed get nothing, though the ring made them
     * before that block.
     */
    @Test
    void aDropTheDrainHadNotNoticedWhenAWriteFailureSealsTheTakeGetsNothing() throws Exception {
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        AtomicLong freeBytes = new AtomicLong(10 * GIB);
        service = newOneInputService(ring, journalledPhysicalCapture(new Track("Vocal", TrackType.AUDIO), 0, journal),
                freeBytes);
        startAndAwaitReadiness();
        publishInput(ring, 0);
        service.awaitFlushed(GUARD);
        fillWhileHeldAndDrop(ring, SLOT_FRAMES, 2);
        journal.failNextWrites(1);
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);

        assertThat(awaitWithinTheGuard(service.earlySeal(), "the early seal"))
                .isInstanceOf(EarlySeal.WriteFailed.class);
        assertNoRingLossIsRecorded();
    }

    /**
     * Ruling E263: after the drain's own loop fails nothing looks at the ring again; the drops it had not noticed get
     * nothing, though the block in hand was kept.
     */
    @Test
    void aDropTheDrainHadNotNoticedWhenItsLoopFailsGetsNothing() throws Exception {
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        AtomicLong freeBytes = new AtomicLong(10 * GIB);
        service = newOneInputService(ring, journalledPhysicalCapture(new Track("Vocal", TrackType.AUDIO), 0, journal),
                freeBytes);
        startAndAwaitReadiness();
        publishInput(ring, 0);
        service.awaitFlushed(GUARD);
        fillWhileHeldAndDrop(ring, SLOT_FRAMES, 2);
        IllegalStateException fault = new IllegalStateException("injected observer fault");
        service.setBlockObserver((sequence, startFrame, numFrames) -> {
            throw fault;
        });
        service.setDrainPaused(false);
        awaitTermination(service);

        assertThat(service.appliedBlocks()).as("fixture: the block in hand was kept before the loop failed")
                .isEqualTo(2);
        assertThat(awaitWithinTheGuard(service.earlySeal(), "the early seal"))
                .isInstanceOfSatisfying(EarlySeal.WriteFailed.class, seal -> assertThat(seal.failure()).isSameAs(fault));
        assertNoRingLossIsRecorded();
    }

    /**
     * Ruling E263: a drop another ring made after the drain's last look at it gets nothing when the take is sealed
     * early.
     */
    @Test
    void aDropOnAnotherRingSinceItsLastLookIsNoLossOfATakeSealedEarly() throws Exception {
        Track a = new Track("First", TrackType.AUDIO);
        Track b = new Track("Second", TrackType.AUDIO);
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing first = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        CaptureRing second = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        AtomicLong freeBytes = new AtomicLong(10 * GIB);
        service = newTwoInputService(first, second, List.of(journalledPhysicalCapture(a, 0, journal),
                journalledPhysicalCapture(b, 1, journal)), freeBytes::get);
        service.setDrainPaused(true);
        startAndAwaitReadiness();
        publishInput(first, 0);
        publishInput(second, 0);
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);

        service.setDrainPaused(true);
        for (int block = 1; block <= second.capacity(); block++) {
            publishInput(second, (long) block * SLOT_FRAMES);
        }
        assertThat(second.claim(CaptureRing.DELIVERED)).as("fixture: the second ring drops a block").isNull();
        publishInput(first, 8);
        freeBytes.set(0L);
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);

        assertThat(awaitWithinTheGuard(service.earlySeal(), "the early seal"))
                .isInstanceOf(EarlySeal.DiskExhausted.class);
        assertNoRingLossIsRecorded();
    }

    /**
     * Ruling E264: blocks a ring receives or drops behind a device loss's final sweep are part of that loss: no line,
     * no warning, no count.
     */
    @Test
    void aDeviceLossSealRecordsAndCountsNothingTheRingGetsBehindItsFinalSweep() throws Exception {
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        AtomicLong freeBytes = new AtomicLong(10 * GIB);
        service = newOneInputService(ring, journalledPhysicalCapture(new Track("Vocal", TrackType.AUDIO), 0, journal),
                freeBytes);
        AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch held = holdTheSealsForceOnceArmed(journal, armed, release);
        startAndAwaitReadiness();
        publishInput(ring, 0);
        publishInput(ring, 8);
        service.awaitFlushed(GUARD);

        try {
            stopAndHoldTheSeal(held, armed, TakeManifest.SealedBy.DEVICE_LOSS);
            publishThenDropBehindTheSweep(ring, ring.capacity(), 1);
        } finally {
            release.countDown();
        }
        awaitTermination(service);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.sealedBy()).contains(TakeManifest.SealedBy.DEVICE_LOSS);
        assertThat(manifest.gaps()).isEmpty();
        assertThat(manifest.overflowBlocks()).isZero();
        assertThat(service.fileOverflowBlocks()).isZero();
        assertThat(warnings).noneMatch(warning -> warning.contains("published after the final sweep"))
                .noneMatch(warning -> warning.contains("Capture ring overflow"));
    }

    /**
     * Ruling E264: a drop behind a device loss's final sweep gets no line and no count, yet the seal's look still
     * accounts it, so once the thread has ended {@link CaptureFlushService#awaitFlushed} finds every drop noticed.
     */
    @Test
    void aDropBehindTheFinalSweepOfADeviceLossSealIsStillAccountedForAwaitFlushed() throws Exception {
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        AtomicLong freeBytes = new AtomicLong(10 * GIB);
        service = newOneInputService(ring, journalledPhysicalCapture(new Track("Vocal", TrackType.AUDIO), 0, journal),
                freeBytes);
        AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch held = holdTheSealsForceOnceArmed(journal, armed, release);
        startAndAwaitReadiness();
        publishInput(ring, 0);
        publishInput(ring, 8);
        service.awaitFlushed(GUARD);

        try {
            stopAndHoldTheSeal(held, armed, TakeManifest.SealedBy.DEVICE_LOSS);
            publishThenDropBehindTheSweep(ring, ring.capacity(), 1);
            for (int block = 0; block < ring.capacity(); block++) {
                assertThat(ring.peek()).isNotNull();
                ring.release();
            }
        } finally {
            release.countDown();
        }
        awaitTermination(service);

        assertThat(ring.overflowCount()).as("premise: the ring dropped a block behind the final sweep").isEqualTo(1);
        assertThat(finalManifest().gaps()).as("premise: the device loss's look recorded nothing").isEmpty();
        assertThatCode(() -> service.awaitFlushed(GUARD)).doesNotThrowAnyException();
    }

    /**
     * Rulings E213 and E264: at a Stop's seal a block left behind the final sweep keeps its line, its warning and its
     * count.
     */
    @Test
    void aStopsSealRecordsAndCountsABlockBehindItsFinalSweep() throws Exception {
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        AtomicLong freeBytes = new AtomicLong(10 * GIB);
        service = newOneInputService(ring, journalledPhysicalCapture(new Track("Vocal", TrackType.AUDIO), 0, journal),
                freeBytes);
        AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch held = holdTheSealsForceOnceArmed(journal, armed, release);
        startAndAwaitReadiness();
        publishInput(ring, 0);
        publishInput(ring, 8);
        service.awaitFlushed(GUARD);

        try {
            stopAndHoldTheSeal(held, armed, TakeManifest.SealedBy.STOP);
            publishThenDropBehindTheSweep(ring, 1, 0);
        } finally {
            release.countDown();
        }
        awaitTermination(service);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.sealedBy()).contains(TakeManifest.SealedBy.STOP);
        assertThat(manifest.gaps())
                .containsExactly(new TakeManifest.GapEntry(TakeManifest.GapEntry.ALL_TRACKS, 16, 1));
        assertThat(service.fileOverflowBlocks()).isEqualTo(1);
        assertThat(warnings).contains("1 capture block(s) were published after the final sweep at frame 16 and are"
                + " not part of the take; the manifest records the gap");
    }

    /** Ruling E256: a late overflow wholly in the pre-roll of a punch take keeps its line and counts nothing. */
    @Test
    void aLateOverflowWhollyInThePreRollOfAPunchTakeCountsNothing() throws Exception {
        Track track = new Track("Vocal", TrackType.AUDIO);
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        service = newService(ring, journalledPhysicalCapture(track, 0, journal));
        AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch held = holdTheEmptyLanesDiscardOnceArmed(journal, armed, release);
        startAndAwaitReadiness();
        publishPunch(ring, 0);
        publishPunch(ring, 8);
        service.awaitFlushed(GUARD);

        try {
            stopAndHoldTheSeal(held, armed);
            publishThenDropBehindTheSweep(ring, ring.capacity(), 2);
        } finally {
            release.countDown();
        }
        awaitTermination(service);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.gaps()).containsExactly(
                new TakeManifest.GapEntry(TakeManifest.GapEntry.ALL_TRACKS, 16, 8),
                new TakeManifest.GapEntry(TakeManifest.GapEntry.ALL_TRACKS, 80, 2));
        assertThat(manifest.overflowBlocks()).isEqualTo(2);
        assertThat(service.fileOverflowBlocks()).as("[16, 80) and [80, 96) lie before punch-in at 100").isZero();
    }

    /**
     * Ruling E256: a late overflow that reaches into the punch region counts its blocks; the blocks left behind,
     * wholly in the pre-roll, do not.
     */
    @Test
    void aLateOverflowReachingIntoThePunchRegionCountsItsBlocks() throws Exception {
        Track track = new Track("Vocal", TrackType.AUDIO);
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        service = newService(ring, journalledPhysicalCapture(track, 0, journal));
        AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch held = holdTheEmptyLanesDiscardOnceArmed(journal, armed, release);
        startAndAwaitReadiness();
        publishPunch(ring, 0);
        publishPunch(ring, 8);
        service.awaitFlushed(GUARD);

        try {
            stopAndHoldTheSeal(held, armed);
            publishThenDropBehindTheSweep(ring, ring.capacity(), 3);
        } finally {
            release.countDown();
        }
        awaitTermination(service);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.gaps()).containsExactly(
                new TakeManifest.GapEntry(TakeManifest.GapEntry.ALL_TRACKS, 16, 8),
                new TakeManifest.GapEntry(TakeManifest.GapEntry.ALL_TRACKS, 80, 3));
        assertThat(manifest.overflowBlocks()).isEqualTo(3);
        assertThat(service.fileOverflowBlocks()).as("[80, 104) reaches into [100, 200); [16, 80) does not")
                .isEqualTo(3);
    }

    /**
     * Ruling E274: a drop the drain saw at its look before the headroom floor sealed the take gets nothing when no
     * block the take kept follows it. The ring fills during the pass that applies the take's first block, past the
     * tail that pass read, and the floor discards the next pass's first block, so the drop lies past the file's end.
     */
    @Test
    void aDropTheDrainSawPastTheBlockTheHeadroomFloorDiscardsGetsNothing() throws Exception {
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        AtomicLong freeBytes = new AtomicLong(10 * GIB);
        TrackCapture capture = journalledPhysicalCapture(new Track("Vocal", TrackType.AUDIO), 0, journal);
        service = newOneInputService(ring, capture, freeBytes);
        fillDuringTheFirstBlockThenExhaustTheDisk(ring, freeBytes);
        startAndAwaitReadiness();
        publishInput(ring, 0);
        assertThat(awaitWithinTheGuard(service.earlySeal(), "the early seal"))
                .as("fixture: the next pass's first block met the headroom floor")
                .isInstanceOf(EarlySeal.DiskExhausted.class);
        service.awaitFlushed(GUARD);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.segmentsFor(capture.trackId()).stream().mapToLong(TakeManifest.SegmentEntry::frames).sum())
                .as("fixture: the file holds the first block only").isEqualTo(SLOT_FRAMES);
        assertNoRingLossIsRecorded();
    }

    /** Ruling E274: the same in a punch take, with the drop inside the punch region. */
    @Test
    void aDropTheDrainSawPastTheBlockTheHeadroomFloorDiscardsInAPunchTakeGetsNothing() throws Exception {
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        AtomicLong freeBytes = new AtomicLong(10 * GIB);
        TrackCapture capture = journalledPhysicalCapture(new Track("Vocal", TrackType.AUDIO), 0, journal);
        service = newOneInputService(ring, capture, freeBytes);
        fillDuringTheFirstBlockThenExhaustTheDisk(ring, freeBytes);
        startAndAwaitReadiness();
        publishPunch(ring, 96);
        assertThat(awaitWithinTheGuard(service.earlySeal(), "the early seal"))
                .as("fixture: the next pass's first block met the headroom floor")
                .isInstanceOf(EarlySeal.DiskExhausted.class);
        service.awaitFlushed(GUARD);

        assertNoRingLossIsRecorded();
    }

    /**
     * Rulings E263 and E274: {@code overflow-blocks} holds exactly the drops that have a line. The second ring's run
     * is lined once the block after it is applied, before the headroom floor seals the take. The first ring, whose
     * next block the floor then refuses, had dropped two blocks no kept block follows; they get nothing, though the
     * drain saw them at its look.
     */
    @Test
    void overflowBlocksOfATakeSealedEarlyHoldsOnlyTheDropsThatHaveALine() throws Exception {
        Track first = new Track("First", TrackType.AUDIO);
        Track second = new Track("Second", TrackType.AUDIO);
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing firstRing = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        CaptureRing secondRing = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        AtomicLong freeBytes = new AtomicLong(10 * GIB);
        service = newTwoInputService(firstRing, secondRing, List.of(journalledPhysicalCapture(first, 0, journal),
                journalledPhysicalCapture(second, 1, journal)), freeBytes::get);
        service.setDrainPaused(true);
        startAndAwaitReadiness();
        publishInput(firstRing, 0);
        publishInput(secondRing, 0);
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);

        fillWhileHeldAndDrop(secondRing, SLOT_FRAMES, 1);
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);
        publishInput(secondRing, 80);
        service.awaitFlushed(GUARD);
        fillWhileHeldAndDrop(firstRing, SLOT_FRAMES, 2);
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);
        freeBytes.set(0L);
        publishInput(firstRing, 88);
        assertThat(awaitWithinTheGuard(service.earlySeal(), "the early seal"))
                .as("fixture: the first ring's next block met the headroom floor")
                .isInstanceOf(EarlySeal.DiskExhausted.class);
        service.awaitFlushed(GUARD);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.gaps()).as("the second ring's line, lined once the block after its run was applied")
                .containsExactly(new TakeManifest.GapEntry(second.getId(), 72, 1));
        assertThat(manifest.overflowBlocks()).as("only the drop that has a line").isEqualTo(1);
        assertThat(service.overflowCount()).as("fixture: the rings dropped three blocks").isEqualTo(3);
        assertThat(service.fileOverflowBlocks()).isEqualTo(1);
        assertThat(warnings).filteredOn(warning -> warning.contains("Capture ring overflow")).containsExactly(
                "Capture ring overflow: 1 block(s) dropped at frame 72 (1 dropped in total); the take manifest records"
                        + " the gap");
    }

    private void flushPunchPass() {
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);
    }

    /**
     * Publishes one block; a {@code null} instrument marks source 1 absent,
     * as the callback does when the engine hands it no recording buffer.
     */
    private void publish(CaptureRing ring, int blockIndex, float[][] instrument, int instrumentChannels) {
        CaptureRing.Slot slot = ring.claim(CaptureRing.DELIVERED);
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
        // Both values are exact at 16 bits, so the streaming segment decodes to them.
        float[][] captured = RecordedAudioTestSupport.audioOnDisk(capture.session());
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
    void awaitFlushedReturnsOnlyOnceTheBlockObserverHasReturned() throws InterruptedException {
        // The fence ends at the look that ends the block's pass, after its
        // release, so what the observer writes is readable once awaitFlushed
        // returns (story 336, studio-engineer ruling R992-22).
        Track synth = new Track("Synth", TrackType.AUDIO);
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 2, 2, 8);
        service = newService(ring, instrumentCapture(synth));
        startAndAwaitReadiness();
        CountDownLatch inObserver = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Long> observed = new CopyOnWriteArrayList<>();
        service.setBlockObserver((sequence, startFrame, numFrames) -> {
            inObserver.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            observed.add(sequence);
        });
        try {
            publish(ring, 0, block(2, 0.5f), 2);
            assertThat(inObserver.await(GUARD.toMillis(), TimeUnit.MILLISECONDS))
                    .as("fixture: the flush thread is in the observer").isTrue();
            assertThatThrownBy(() -> service.awaitFlushed(HOLD_PROBE))
                    .as("the fence waits for the observer")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("awaitFlushed timed out");
            assertThat(service.appliedBlocks()).as("the block was counted before its observer ran").isEqualTo(1);
            assertThat(ring.releasedBlocks()).as("its slot is released only once the observer has returned")
                    .isZero();
        } finally {
            release.countDown();
        }

        service.awaitFlushed(GUARD);
        assertThat(observed).as("read at the fence, not at the thread's termination").containsExactly(0L);
        assertThat(ring.releasedBlocks()).isEqualTo(1);
    }

    @Test
    void aBlockWhoseObserverThrowsIsStillCountedAndReleased() {
        Track synth = new Track("Synth", TrackType.AUDIO);
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 2, 2, 8);
        service = newService(ring, instrumentCapture(synth));
        startAndAwaitReadiness();
        IllegalStateException fault = new IllegalStateException("injected observer fault");
        service.setBlockObserver((sequence, startFrame, numFrames) -> {
            throw fault;
        });
        publish(ring, 0, block(2, 0.5f), 2);
        awaitTermination(service);

        assertThat(service.lastFailure()).containsSame(fault);
        assertThat(service.appliedBlocks()).isEqualTo(1);
        assertThat(ring.releasedBlocks()).as("the slot is released whatever the observer does").isEqualTo(1);
        assertThatCode(() -> service.awaitFlushed(GUARD)).as("the fence holds on a terminated service")
                .doesNotThrowAnyException();
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
     * Holds the flush thread in the block observer until {@code release},
     * which every caller counts down in a {@code finally}. The hold has no
     * timeout of its own, so a request that waited for the hold would still
     * be waiting when {@code outcomeWithinTheGuard} gives up, and a stalled
     * host fails the test as a stall.
     */
    private CountDownLatch holdTheFlushThreadInItsNextBlock(CountDownLatch release) {
        CountDownLatch held = new CountDownLatch(1);
        service.setBlockObserver((sequence, startFrame, numFrames) -> {
            held.countDown();
            try {
                release.await();
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

            // The hold has no bound of its own: an abort that waited for the
            // held thread would still be waiting when the guard ran out.
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

    /**
     * Ruling E280: from the moment the capture thread begins an early seal, before its signal completes, the take
     * ignores a clock event handed to it and says so ({@link CaptureFlushService#ignoresClockEvents()}); an event
     * handed in before the seal is written.
     */
    @Test
    void aClockEventIsIgnoredFromTheMomentAnEarlySealBeginsAndTheFlagSaysSo() throws Exception {
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        AtomicLong freeBytes = new AtomicLong(10 * GIB);
        service = newOneInputService(ring, journalledPhysicalCapture(new Track("Vocal", TrackType.AUDIO), 0, journal),
                freeBytes);
        AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch held = holdTheSealsForceOnceArmed(journal, armed, release);
        startAndAwaitReadiness();
        publishInput(ring, 0);
        service.awaitFlushed(GUARD);
        boolean ignoredBeforeTheSeal = service.ignoresClockEvents();
        service.noteClockEvent(TakeManifest.ClockEvent.UNLOCKED, 0L);
        boolean ignoredDuringTheSeal;
        boolean signalledDuringTheSeal;
        try {
            armed.set(true);
            freeBytes.set(0L);
            publishInput(ring, 8);
            assertThat(held.await(GUARD.toMillis(), TimeUnit.MILLISECONDS))
                    .as("fixture: the early seal is held sealing").isTrue();
            ignoredDuringTheSeal = service.ignoresClockEvents();
            signalledDuringTheSeal = service.earlySeal().toCompletableFuture().isDone();
            service.noteClockEvent(TakeManifest.ClockEvent.LOCKED, 8L);
        } finally {
            release.countDown();
        }

        assertThat(awaitWithinTheGuard(service.earlySeal(), "the early seal"))
                .isInstanceOf(EarlySeal.DiskExhausted.class);
        assertThat(ignoredBeforeTheSeal).as("premise: the take records unsealed").isFalse();
        assertThat(signalledDuringTheSeal).as("premise: read before the early-seal signal").isFalse();
        assertThat(ignoredDuringTheSeal).as("from the moment the seal began").isTrue();
        assertThat(finalManifest().clockMarks()).extracting(TakeManifest.ClockMark::event)
                .as("the event handed in before the seal is written, the one during it is not")
                .containsExactly(TakeManifest.ClockEvent.UNLOCKED);
    }

    private static TakeManifest.GapEntry gap(long frame, long blocks) {
        return new TakeManifest.GapEntry(TakeManifest.GapEntry.ALL_TRACKS, frame, blocks);
    }

    private static TakeManifest.InputStopped stop(TrackCapture capture, long stopFrame, long resumeFrame) {
        return new TakeManifest.InputStopped(capture.trackId(), stopFrame, resumeFrame);
    }

    private static String overflow(long blocks, long frame, long total) {
        return "Capture ring overflow: " + blocks + " block(s) dropped at frame " + frame + " (" + total
                + " dropped in total); the take manifest records the gap";
    }

    private List<String> overflowWarnings() {
        return warnings.stream().filter(warning -> warning.startsWith("Capture ring overflow")).toList();
    }

    /** B: the take's dropouts the ring and the input's starved runs give, as the take's tally adds them. */
    private long dropouts() {
        return service.fileOverflowBlocks() + service.starvedInputBlocks();
    }

    /** "First" on ring source 0 and "Second" on source 1, journalled, with room on the disk. */
    private List<TrackCapture> twoInputs(CaptureRing firstRing, CaptureRing secondRing,
                                         ObservedFileChannel.Journal journal) {
        List<TrackCapture> captures = List.of(
                journalledPhysicalCapture(new Track("First", TrackType.AUDIO), 0, journal),
                journalledPhysicalCapture(new Track("Second", TrackType.AUDIO), 1, journal));
        service = newTwoInputService(firstRing, secondRing, captures, () -> 10 * GIB);
        return captures;
    }

    /**
     * Blocks 0 and 8; as the pass applies 8, past the tail it read, the ring is filled from 16 to 64 and drops 72
     * and 80; the next pass applies 16 to 64, and then 88 is published and applied.
     */
    private void dropTwoPastThePassesTail(CaptureRing ring, Region region) {
        AtomicBoolean filled = new AtomicBoolean();
        service.setBlockObserver((sequence, startFrame, numFrames) -> {
            if (sequence == 1 && filled.compareAndSet(false, true)) {
                for (long frame = 16; frame <= 64; frame += SLOT_FRAMES) {
                    publishBlock(ring, frame, 1, region, NO);
                }
                assertThat(ring.claim(CaptureRing.DELIVERED)).as("fixture: the full ring drops 72").isNull();
                assertThat(ring.claim(CaptureRing.DELIVERED)).as("fixture: the full ring drops 80").isNull();
            }
        });
        publishBlock(ring, 0, 1, region, NO);
        publishBlock(ring, 8, 1, region, NO);
        service.awaitFlushed(GUARD);
        service.awaitFlushed(GUARD);
        publishBlock(ring, 88, 1, region, NO);
        service.awaitFlushed(GUARD);
    }

    /**
     * Block 0, then the ring filled from 8 to 64 and two drops; as the next pass applies 16 it publishes 88 and the
     * ring drops 96, and as it applies 24 it publishes 104. Returns the manifest writes as that pass applied 64.
     */
    private AtomicLong twoRunsInOnePass(CaptureRing ring) {
        publishInput(ring, 0);
        service.awaitFlushed(GUARD);
        fillWhileHeldAndDrop(ring, SLOT_FRAMES, 2);
        AtomicLong writesAt64 = new AtomicLong(-1L);
        service.setBlockObserver((sequence, startFrame, numFrames) -> {
            if (sequence == 2) {
                publishInput(ring, 88);
                assertThat(ring.claim(CaptureRing.DELIVERED)).as("fixture: the full ring drops 96").isNull();
            } else if (sequence == 3) {
                publishInput(ring, 104);
            } else if (sequence == 8) {
                writesAt64.set(service.manifestWrites());
            }
        });
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);
        service.awaitFlushed(GUARD);
        return writesAt64;
    }

    /** Ruling E273: a run is lined at the end of the block the ring delivered right before it. */
    @Test
    void aRunIsLinedAtTheEndOfTheBlockBeforeIt() {
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        vocalOn(ring, new ObservedFileChannel.Journal());
        startAndAwaitReadiness();

        dropTwoPastThePassesTail(ring, Region.NONE);

        TakeManifest manifest = service.lastManifest().orElseThrow();
        assertThat(manifest.gaps()).containsExactly(gap(72, 2));
        assertThat(manifest.overflowBlocks()).isEqualTo(2);
        assertThat(service.fileOverflowBlocks()).isEqualTo(2);
        assertThat(overflowWarnings()).containsExactly(overflow(2, 72, 2));
    }

    /** Ruling E273: two runs a pass lines get a line each and one warning, for the first, with the take's total. */
    @Test
    void twoRunsInOnePassGetTwoLines() {
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        vocalOn(ring, new ObservedFileChannel.Journal());
        startAndAwaitReadiness();

        twoRunsInOnePass(ring);

        TakeManifest manifest = service.lastManifest().orElseThrow();
        assertThat(manifest.gaps()).containsExactly(gap(72, 2), gap(96, 1));
        assertThat(manifest.overflowBlocks()).isEqualTo(3);
        assertThat(dropouts()).isEqualTo(3);
        assertThat(overflowWarnings()).containsExactly(overflow(2, 72, 3));
    }

    /** Ruling E273: a run inside the punch region counts, wherever the pass that saw it ended. */
    @Test
    void aDropInsideThePunchRegionAlwaysCounts() {
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        vocalOn(ring, new ObservedFileChannel.Journal());
        startAndAwaitReadiness();

        dropTwoPastThePassesTail(ring, Region.of(40, 200));

        assertThat(service.lastManifest().orElseThrow().gaps()).containsExactly(gap(72, 2));
        assertThat(dropouts()).as("[72, 88) lies in [40, 200)").isEqualTo(2);
    }

    /** Ruling E273: a run outside the punch region keeps its line and counts nothing. */
    @Test
    void aDropOutsideItNeverCounts() {
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        vocalOn(ring, new ObservedFileChannel.Journal());
        startAndAwaitReadiness();

        dropTwoPastThePassesTail(ring, Region.of(0, 40));

        TakeManifest manifest = service.lastManifest().orElseThrow();
        assertThat(manifest.gaps()).containsExactly(gap(72, 2));
        assertThat(manifest.overflowBlocks()).isEqualTo(2);
        assertThat(dropouts()).as("[72, 88) lies after [0, 40)").isZero();
    }

    /** Ruling E273: a run no block follows yet has no line, and the fence does not wait for one. */
    @Test
    void aRunNoBlockFollowsYetIsLinedWhenTheNextBlockIsApplied() {
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        vocalOn(ring, new ObservedFileChannel.Journal());
        startAndAwaitReadiness();
        publishInput(ring, 0);
        service.awaitFlushed(GUARD);
        fillWhileHeldAndDrop(ring, SLOT_FRAMES, 2);
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);

        TakeManifest before = service.lastManifest().orElseThrow();
        assertThat(before.gaps()).as("no block follows the run yet").isEmpty();
        assertThat(before.overflowBlocks()).isZero();
        assertThat(dropouts()).isZero();
        assertThat(overflowWarnings()).isEmpty();

        publishInput(ring, 88);
        service.awaitFlushed(GUARD);

        assertThat(service.lastManifest().orElseThrow().gaps()).containsExactly(gap(72, 2));
        assertThat(dropouts()).isEqualTo(2);
        assertThat(overflowWarnings()).containsExactly(overflow(2, 72, 2));
    }

    /** Ruling E273: the runs a pass lines cost one manifest rewrite, at the look that ends the pass. */
    @Test
    void oneManifestRewritePerPassThatLinesRuns() {
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        vocalOn(ring, new ObservedFileChannel.Journal());
        startAndAwaitReadiness();

        AtomicLong writesAt64 = twoRunsInOnePass(ring);

        assertThat(writesAt64.get()).as("fixture: the pass applied 64").isNotNegative();
        assertThat(service.manifestWrites() - writesAt64.get()).as("the pass that lined both runs").isEqualTo(1);
    }

    /** Ruling E274: the run before the block the headroom floor discards counts nothing. */
    @Test
    void theDropsBeforeTheBlockTheHeadroomFloorDiscardsCountNothing() throws Exception {
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        AtomicLong freeBytes = new AtomicLong(10 * GIB);
        service = newOneInputService(ring, journalledPhysicalCapture(new Track("Vocal", TrackType.AUDIO), 0, journal),
                freeBytes);
        startAndAwaitReadiness();
        publishInput(ring, 0);
        service.awaitFlushed(GUARD);
        fillWhileHeldAndDrop(ring, SLOT_FRAMES, 2);
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);
        freeBytes.set(0L);
        publishInput(ring, 88);
        service.awaitFlushed(GUARD);

        assertThat(awaitWithinTheGuard(service.earlySeal(), "the early seal"))
                .isInstanceOf(EarlySeal.DiskExhausted.class);
        assertNoRingLossIsRecorded();
        assertThat(service.overflowCount()).isEqualTo(2);
    }

    /** Ruling E274: a run before a block kept before an observer failure keeps its line, count and warning. */
    @Test
    void aRunBeforeABlockKeptBeforeAnObserverFailureCounts() throws Exception {
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        vocalOn(ring, new ObservedFileChannel.Journal());
        startAndAwaitReadiness();
        publishInput(ring, 0);
        service.awaitFlushed(GUARD);
        fillWhileHeldAndDrop(ring, SLOT_FRAMES, 1);
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);
        IllegalStateException fault = new IllegalStateException("injected observer fault");
        service.setBlockObserver((sequence, startFrame, numFrames) -> {
            if (sequence == 9) {
                throw fault;
            }
        });
        publishInput(ring, 80);
        awaitTermination(service);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.gaps()).containsExactly(gap(72, 1));
        assertThat(manifest.overflowBlocks()).isEqualTo(1);
        assertThat(dropouts()).isEqualTo(1);
        assertThat(warnings).contains(overflow(1, 72, 1));
        assertThat(awaitWithinTheGuard(service.earlySeal(), "the early seal")).isInstanceOfSatisfying(
                EarlySeal.WriteFailed.class, seal -> assertThat(seal.failure()).isSameAs(fault));
    }

    /** Ruling E274: the run before the block whose write failed counts nothing. */
    @Test
    void aRunBeforeTheBlockWhoseWriteFailedCountsNothing() throws Exception {
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        vocalOn(ring, journal);
        startAndAwaitReadiness();
        publishInput(ring, 0);
        service.awaitFlushed(GUARD);
        fillWhileHeldAndDrop(ring, SLOT_FRAMES, 1);
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);
        journal.failNextWrites(1);
        publishInput(ring, 80);
        service.awaitFlushed(GUARD);

        assertThat(awaitWithinTheGuard(service.earlySeal(), "the early seal"))
                .isInstanceOf(EarlySeal.WriteFailed.class);
        assertNoRingLossIsRecorded();
    }

    /**
     * Ruling E275: at the Stop the drops the final sweep's look found join the late drops' line, after the block
     * left behind, each line counting by its own extent.
     */
    @Test
    void atTheStopTheDropsTheFinalSweepsLookFoundJoinTheLateDropsLine() throws Exception {
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        vocalOn(ring, journal);
        AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch held = holdTheSealsForceOnceArmed(journal, armed, release);
        startAndAwaitReadiness();
        publishBlock(ring, 0, 1, Region.of(0, 80), NO);
        service.awaitFlushed(GUARD);
        fillWhileHeldThenDrop(ring, SLOT_FRAMES, CaptureRing.DELIVERED, Region.of(0, 80),
                CaptureRing.DELIVERED, CaptureRing.DELIVERED);

        try {
            stopAndHoldTheSeal(held, armed);
            publishBlock(ring, 88, 1, Region.of(0, 80), NO);
        } finally {
            release.countDown();
        }
        awaitTermination(service);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.gaps()).containsExactly(gap(72, 1), gap(80, 2));
        assertThat(manifest.overflowBlocks()).isEqualTo(2);
        assertThat(dropouts()).as("[72, 80) lies in [0, 80); [80, 96) does not").isEqualTo(1);
        assertThat(warnings).contains("1 capture block(s) were published after the final sweep at frame 72 and are"
                + " not part of the take; the manifest records the gap");
        assertThat(overflowWarnings()).containsExactly(overflow(2, 80, 2));
    }

    /**
     * Ruling E275: at a device loss the drops the final sweep's look finds are lined at the end of the last block
     * applied; what the ring drops after that look is the loss's.
     */
    @Test
    void atADeviceLossTheDropsTheFinalSweepFindsAreLinedAtTheLastBlock() throws Exception {
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        vocalOn(ring, journal);
        AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch held = holdTheSealsForceOnceArmed(journal, armed, release);
        startAndAwaitReadiness();
        publishInput(ring, 0);
        service.awaitFlushed(GUARD);
        fillWhileHeldAndDrop(ring, SLOT_FRAMES, 2);

        try {
            stopAndHoldTheSeal(held, armed, TakeManifest.SealedBy.DEVICE_LOSS);
            publishThenDropBehindTheSweep(ring, ring.capacity(), 1);
        } finally {
            release.countDown();
        }
        awaitTermination(service);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.gaps()).containsExactly(gap(72, 2));
        assertThat(manifest.overflowBlocks()).isEqualTo(2);
        assertThat(dropouts()).isEqualTo(2);
        assertThat(service.overflowCount()).isEqualTo(3);
        assertThat(overflowWarnings()).containsExactly(overflow(2, 72, 2));
        assertThat(warnings).noneMatch(warning -> warning.contains("published after the final sweep"));
        assertThat(manifest.sealedBy()).contains(TakeManifest.SealedBy.DEVICE_LOSS);
    }

    /** Ruling E277: a block of silence left behind after the take holds the input's stop counts nothing. */
    @Test
    void atTheStopASilentBlockLeftBehindAfterTheInputStoppedLineCountsNothing() throws Exception {
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing firstRing = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        CaptureRing secondRing = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        TrackCapture second = twoInputs(firstRing, secondRing, journal).get(1);
        AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch held = holdTheSealsForceOnceArmed(journal, armed, release);
        startAndAwaitReadiness();
        publishInput(firstRing, 0);
        publishInput(secondRing, 0);
        publishSilence(secondRing, 8, 8);
        service.awaitFlushed(GUARD);

        try {
            stopAndHoldTheSeal(held, armed);
            publishSilence(secondRing, 16, 16);
        } finally {
            release.countDown();
        }
        awaitTermination(service);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.gaps()).isEmpty();
        assertThat(manifest.overflowBlocks()).isZero();
        assertThat(dropouts()).isZero();
        assertThat(warnings).noneMatch(warning -> warning.contains("published after the final sweep"));
        assertThat(manifest.inputStops()).containsExactly(stop(second, 8, 8));
    }

    /**
     * Rulings E277 and E284: the input's first silence, left behind at the Stop, gives its input-stopped line at
     * the seal, read from its header, in the words of a stop the file holds no audio after.
     */
    @Test
    void atTheStopTheInputsFirstSilenceLeftBehindGivesItsInputStoppedLineAtTheSeal() throws Exception {
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing firstRing = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        CaptureRing secondRing = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        TrackCapture second = twoInputs(firstRing, secondRing, journal).get(1);
        AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch held = holdTheSealsForceOnceArmed(journal, armed, release);
        startAndAwaitReadiness();
        publishInput(firstRing, 0);
        publishInput(secondRing, 0);
        publishInput(secondRing, 8);
        service.awaitFlushed(GUARD);

        try {
            stopAndHoldTheSeal(held, armed);
            publishSilence(secondRing, 16, 24);
        } finally {
            release.countDown();
        }
        awaitTermination(service);

        TakeManifest manifest = finalManifest();
        assertThat(dropouts()).isZero();
        assertThat(manifest.gaps()).isEmpty();
        assertThat(manifest.inputStops()).containsExactly(stop(second, 16, 24));
        assertThat(warnings).contains("Track 'Second': input from 'Input 1' stopped delivering audio at 0:00.0 into"
                + " the take; the track holds no audio from there.");
    }

    /** Ruling E277: a block left behind that carries a recorded instrument's audio counts with its line. */
    @Test
    void atTheStopABlockLeftBehindWithARecordedInstrumentsAudioCountsWithItsLine() throws Exception {
        Track synth = new Track("Synth", TrackType.AUDIO);
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 2, 2, 8);
        service = newService(ring, instrumentCapture(synth, journalledSessions(STEREO_16, journal)));
        AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch held = holdTheSealsForceOnceArmed(journal, armed, release);
        startAndAwaitReadiness();
        publish(ring, 0, block(2, 0.5f), 2);
        publish(ring, 1, block(2, 0.5f), 2);
        service.awaitFlushed(GUARD);

        try {
            stopAndHoldTheSeal(held, armed);
            publishInstrumentOnly(ring, 16, Region.NONE);
        } finally {
            release.countDown();
        }
        awaitTermination(service);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.gaps()).containsExactly(gap(16, 1));
        assertThat(dropouts()).isEqualTo(1);
        assertThat(manifest.inputStops()).isEmpty();
        assertThat(warnings).containsExactly("1 capture block(s) were published after the final sweep at frame 16 and"
                + " are not part of the take; the manifest records the gap");
    }

    /** Ruling E277: silence a full ring drops after the take holds the input's stop gets nothing. */
    @Test
    void duringTheTakeSilenceARingDropsAfterTheInputStoppedLineCountsNothing() {
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        TrackCapture vocal = vocalOn(ring, new ObservedFileChannel.Journal());
        startAndAwaitReadiness();
        publishInput(ring, 0);
        publishSilence(ring, 8, NO);
        service.awaitFlushed(GUARD);
        service.setDrainPaused(true);
        for (long frame = 16; frame <= 72; frame += SLOT_FRAMES) {
            publishSilence(ring, frame, NO);
        }
        assertThat(ring.claim(0)).as("fixture: the full ring drops silence").isNull();
        assertThat(ring.claim(0)).as("fixture: the full ring drops silence").isNull();
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);
        publishSilence(ring, 96, NO);
        service.awaitFlushed(GUARD);

        TakeManifest manifest = service.lastManifest().orElseThrow();
        assertThat(manifest.gaps()).isEmpty();
        assertThat(manifest.overflowBlocks()).isZero();
        assertThat(dropouts()).isZero();
        assertThat(overflowWarnings()).isEmpty();
        assertThat(manifest.inputStops()).containsExactly(stop(vocal, 8, 8));
        assertThat(service.overflowCount()).isEqualTo(2);
    }

    /** Rulings E273 and E277: a delivered block dropped before the input's first silence is lined before it. */
    @Test
    void aDeliveredBlockDroppedBeforeTheFirstSilenceIsLinedAtTheBlockBeforeIt() {
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        TrackCapture vocal = vocalOn(ring, new ObservedFileChannel.Journal());
        startAndAwaitReadiness();
        publishInput(ring, 0);
        service.awaitFlushed(GUARD);
        fillWhileHeldAndDrop(ring, SLOT_FRAMES, 1);
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);
        publishSilence(ring, 80, NO);
        service.awaitFlushed(GUARD);

        TakeManifest manifest = service.lastManifest().orElseThrow();
        assertThat(manifest.gaps()).containsExactly(gap(72, 1));
        assertThat(dropouts()).isEqualTo(1);
        assertThat(manifest.overflowBlocks()).isEqualTo(1);
        assertThat(manifest.inputStops()).containsExactly(stop(vocal, 72, 72));
        assertThat(overflowWarnings()).containsExactly(overflow(1, 72, 1));
    }

    /** Ruling E277: the input's first silence dropped gives one stop line, at the applied silence, and no gap. */
    @Test
    void theInputsFirstSilenceDroppedGivesOneStopLineAndNoGap() {
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        TrackCapture vocal = vocalOn(ring, new ObservedFileChannel.Journal());
        startAndAwaitReadiness();
        publishInput(ring, 0);
        service.awaitFlushed(GUARD);
        fillWhileHeldThenDrop(ring, SLOT_FRAMES, CaptureRing.DELIVERED, Region.NONE, 0, 0);
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);
        publishSilence(ring, 88, NO);
        service.awaitFlushed(GUARD);

        TakeManifest manifest = service.lastManifest().orElseThrow();
        assertThat(manifest.gaps()).isEmpty();
        assertThat(dropouts()).isZero();
        assertThat(manifest.overflowBlocks()).isZero();
        assertThat(overflowWarnings()).isEmpty();
        assertThat(manifest.inputStops()).containsExactly(stop(vocal, 72, 72));
    }

    /** Ruling E277: a run in which the input stopped is lined for its delivered block only. */
    @Test
    void aRunInWhichTheInputStoppedIsLinedForItsDeliveredBlock() {
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        TrackCapture vocal = vocalOn(ring, new ObservedFileChannel.Journal());
        startAndAwaitReadiness();
        publishInput(ring, 0);
        service.awaitFlushed(GUARD);
        fillWhileHeldThenDrop(ring, SLOT_FRAMES, CaptureRing.DELIVERED, Region.NONE, CaptureRing.DELIVERED, 0, 0);
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);
        publishSilence(ring, 96, NO);
        service.awaitFlushed(GUARD);

        TakeManifest manifest = service.lastManifest().orElseThrow();
        assertThat(manifest.gaps()).containsExactly(gap(72, 1));
        assertThat(dropouts()).isEqualTo(1);
        assertThat(manifest.overflowBlocks()).isEqualTo(1);
        assertThat(manifest.inputStops()).containsExactly(stop(vocal, 72, 72));
    }

    /** Ruling E277: such a run is judged by the own extent of the blocks it counts, not to the next block. */
    @Test
    void aRunInWhichTheInputStoppedIsJudgedByItsOwnExtent() {
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        vocalOn(ring, new ObservedFileChannel.Journal());
        startAndAwaitReadiness();
        publishBlock(ring, 0, 1, Region.of(80, 200), NO);
        service.awaitFlushed(GUARD);
        fillWhileHeldThenDrop(ring, SLOT_FRAMES, CaptureRing.DELIVERED, Region.of(80, 200),
                CaptureRing.DELIVERED, 0, 0);
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);
        publishBlock(ring, 96, 0, Region.of(80, 200), NO);
        service.awaitFlushed(GUARD);

        TakeManifest manifest = service.lastManifest().orElseThrow();
        assertThat(manifest.gaps()).containsExactly(gap(72, 1));
        assertThat(manifest.overflowBlocks()).isEqualTo(1);
        assertThat(dropouts()).as("[72, 80) lies before [80, 200)").isZero();
    }

    /** Rulings E277 and E284: at a device loss, silence dropped before the final sweep's look gives its stop line. */
    @Test
    void atADeviceLossSilenceDroppedBeforeTheFinalSweepsLookGivesTheStopLineAtTheSeal() throws Exception {
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        TrackCapture vocal = vocalOn(ring, new ObservedFileChannel.Journal());
        startAndAwaitReadiness();
        publishInput(ring, 0);
        service.awaitFlushed(GUARD);
        fillWhileHeldThenDrop(ring, SLOT_FRAMES, CaptureRing.DELIVERED, Region.NONE, 0, 0);
        service.requestStop(TakeManifest.SealedBy.DEVICE_LOSS);
        awaitTermination(service);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.inputStops()).containsExactly(stop(vocal, 72, 72));
        assertThat(manifest.gaps()).isEmpty();
        assertThat(manifest.overflowBlocks()).isZero();
        assertThat(dropouts()).isZero();
        assertThat(warnings).contains("Track 'Vocal': input from 'Input 0' stopped delivering audio at 0:00.0 into"
                + " the take; the track holds no audio from there.");
        assertThat(manifest.sealedBy()).contains(TakeManifest.SealedBy.DEVICE_LOSS);
    }

    /**
     * Rulings E49, E277 and E284: silence dropped after the last block at the Stop gives the stop line, which covers
     * the trailing starved run.
     */
    @Test
    void atTheStopSilenceDroppedAfterTheLastBlockGivesTheStopLineAndCoversTheStarvedRun() throws Exception {
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        TrackCapture vocal = vocalOn(ring, new ObservedFileChannel.Journal());
        startAndAwaitReadiness();
        publishInput(ring, 0);
        service.awaitFlushed(GUARD);
        service.setDrainPaused(true);
        for (long frame = SLOT_FRAMES; frame <= 56; frame += SLOT_FRAMES) {
            publishInput(ring, frame);
        }
        publishStarved(ring, 64, Region.NONE);
        assertThat(ring.claim(0)).as("fixture: the full ring drops silence").isNull();
        assertThat(ring.claim(0)).as("fixture: the full ring drops silence").isNull();
        service.requestStop(TakeManifest.SealedBy.STOP);
        awaitTermination(service);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.inputStops()).containsExactly(stop(vocal, 64, 64));
        assertThat(manifest.inputSilences()).isEmpty();
        assertThat(service.starvedInputBlocks()).isZero();
        assertThat(manifest.gaps()).isEmpty();
        assertThat(dropouts()).isZero();
    }

    /**
     * Rulings E277, E282 and E302: silence a full ring dropped before the input delivered again, as after a driver
     * reset reopened the stream following a declared stall, is marked by the block with which it delivers again:
     * the input-stopped line at the delivered end, and one gap= line for the dropped silence, without which the
     * audio after it would sit early in the file unrecorded; the seal counts only the silence dropped since the
     * last kept block.
     */
    @Test
    void silenceDroppedBeforeTheInputDeliveredAgainIsNotTheSilenceTheSealCounts() throws Exception {
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        TrackCapture vocal = vocalOn(ring, new ObservedFileChannel.Journal());
        startAndAwaitReadiness();
        publishInput(ring, 0);
        service.awaitFlushed(GUARD);
        fillWhileHeldThenDrop(ring, SLOT_FRAMES, CaptureRing.DELIVERED, Region.NONE, 0, 0);
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);
        publishInput(ring, 88);
        service.awaitFlushed(GUARD);
        fillWhileHeldThenDrop(ring, 96, CaptureRing.DELIVERED, Region.NONE,
                CaptureRing.DELIVERED, CaptureRing.DELIVERED);
        service.requestStop(TakeManifest.SealedBy.STOP);
        awaitTermination(service);

        TakeManifest manifest = finalManifest();
        assertThat(service.overflowCount()).as("premise: two silence drops, then two delivered ones").isEqualTo(4);
        assertThat(manifest.inputStops()).containsExactly(stop(vocal, 72, 72));
        assertThat(manifest.gaps()).containsExactly(gap(72, 2), gap(160, 2));
        assertThat(manifest.overflowBlocks()).isEqualTo(4);
        assertThat(dropouts()).isEqualTo(4);
        assertThat(warnings).containsExactly(
                "Track 'Vocal': input from 'Input 0' stopped delivering audio at 0:00.0 into the take.",
                overflow(2, 72, 2), overflow(2, 160, 4));
    }

    /**
     * Rulings E49, E302 and E301: a starved block, the input live again, after a starved block and two silence
     * blocks the ring dropped lines the whole run at the end of the block before it and marks the stop first, so
     * the kept starved block gets no input-silence entry.
     */
    @Test
    void aStarvedBlockAfterDroppedStarvedAndSilenceBlocksLinesTheWholeRunAndMarksTheStop() throws Exception {
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        TrackCapture vocal = vocalOn(ring, new ObservedFileChannel.Journal());
        startAndAwaitReadiness();
        publishInput(ring, 0);
        service.awaitFlushed(GUARD);
        fillWhileHeldThenDrop(ring, SLOT_FRAMES, CaptureRing.DELIVERED, Region.NONE, CaptureRing.STARVED, 0, 0);
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);
        publishStarved(ring, 96, Region.NONE);
        service.awaitFlushed(GUARD);
        assertThat(service.lastManifest().orElseThrow().gaps()).as("lined with the block").containsExactly(gap(72, 3));
        service.requestStop(TakeManifest.SealedBy.STOP);
        awaitTermination(service);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.inputStops()).containsExactly(stop(vocal, 72, 72));
        assertThat(manifest.inputSilences()).isEmpty();
        assertThat(manifest.gaps()).containsExactly(gap(72, 3));
        assertThat(manifest.overflowBlocks()).isEqualTo(3);
        assertThat(service.fileOverflowBlocks()).isEqualTo(3);
        assertThat(service.starvedInputBlocks()).isZero();
        assertThat(dropouts()).isEqualTo(3);
        assertThat(warnings).containsExactly(
                "Track 'Vocal': input from 'Input 0' stopped delivering audio at 0:00.0 into the take.",
                overflow(3, 72, 3));
    }

    /**
     * Ruling E302 at the seal: silence dropped and then a delivered block dropped, with nothing kept after them
     * before the Stop or the loss, place the silence first: [72, 80), which reaches the region [72, 80) and flags
     * the track at punch-in; the delivered block is lined for itself and judged over the whole run.
     */
    @ParameterizedTest(name = "sealed by {0}")
    @ValueSource(strings = {"STOP", "DEVICE_LOSS"})
    void theSealPlacesDroppedSilenceBeforeTheDeliveredBlockDroppedAfterIt(TakeManifest.SealedBy seal)
            throws Exception {
        TrackCapture vocal = blocksUnderARegionThenTheSeal(seal, 0, SLOT_FRAMES, CaptureRing.DELIVERED,
                Region.of(72, 80), 0, CaptureRing.DELIVERED);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.inputStops()).as("silence at [72, 80); after the dropped audio it would start at 80")
                .containsExactly(stop(vocal, 72, 72));
        assertThat(manifest.gaps()).containsExactly(gap(72, 1));
        assertThat(service.fileOverflowBlocks()).as("the whole run, [72, 88), reaches [72, 80)").isEqualTo(1);
        assertThat(warnings).containsExactly(
                "Track 'Vocal': input from 'Input 0' delivered no audio; the track holds no audio.",
                overflow(1, 72, 1));
    }

    /**
     * Rulings E277 and E281: at the Stop, the input's silence dropped with a recorded instrument's audio is lined
     * for that audio and gives the input's stop line.
     */
    @Test
    void atTheStopTheInputsSilenceDroppedWithARecordedInstrumentsAudioIsLinedAndGivesTheStopLine() throws Exception {
        Track synth = new Track("Synth", TrackType.AUDIO);
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 2, 2, 8);
        TrackCapture mic = journalledPhysicalCapture(new Track("Mic", TrackType.AUDIO), 0, journal);
        service = newService(ring, List.of(instrumentCapture(synth, journalledSessions(STEREO_16, journal)), mic));
        startAndAwaitReadiness();
        publish(ring, 0, block(2, .5f), 2);
        service.awaitFlushed(GUARD);
        service.setDrainPaused(true);
        for (int index = 1; index <= 8; index++) {
            publish(ring, index, block(2, .5f), 2);
        }
        assertThat(ring.claim(CaptureRing.INSTRUMENT)).as("fixture: the full ring drops a block").isNull();
        assertThat(ring.claim(CaptureRing.INSTRUMENT)).as("fixture: the full ring drops a block").isNull();
        service.requestStop(TakeManifest.SealedBy.STOP);
        awaitTermination(service);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.gaps()).containsExactly(gap(72, 2));
        assertThat(manifest.overflowBlocks()).isEqualTo(2);
        assertThat(dropouts()).isEqualTo(2);
        assertThat(manifest.inputStops()).containsExactly(stop(mic, 72, 72));
    }

    /** Block 0, the ring filled from 8 to 64 with one input-starved drop, then a starved block at 80 kept. */
    private TrackCapture aStarvedDropThenAKeptStarvedBlock(CaptureRing ring, ObservedFileChannel.Journal journal) {
        TrackCapture vocal = vocalOn(ring, journal);
        startAndAwaitReadiness();
        publishInput(ring, 0);
        service.awaitFlushed(GUARD);
        fillWhileHeldThenDrop(ring, SLOT_FRAMES, CaptureRing.DELIVERED, Region.NONE, CaptureRing.STARVED);
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);
        publishStarved(ring, 80, Region.NONE);
        service.awaitFlushed(GUARD);
        return vocal;
    }

    /** Ruling E281: a dropped starved block whose starved run ends with a delivery counts, once that delivery comes. */
    @Test
    void aStarvedDropWhoseRunEndsWithADeliveryCountsWithTheDeliveryAndNotBefore() {
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        aStarvedDropThenAKeptStarvedBlock(ring, new ObservedFileChannel.Journal());

        TakeManifest before = service.lastManifest().orElseThrow();
        assertThat(before.gaps()).as("the run waits for its starved run to end").isEmpty();
        assertThat(before.overflowBlocks()).isZero();
        assertThat(service.fileOverflowBlocks()).isZero();
        assertThat(overflowWarnings()).isEmpty();

        publishInput(ring, 88);
        service.awaitFlushed(GUARD);

        TakeManifest manifest = service.lastManifest().orElseThrow();
        assertThat(manifest.gaps()).containsExactly(gap(72, 1));
        assertThat(manifest.overflowBlocks()).isEqualTo(1);
        assertThat(service.fileOverflowBlocks()).isEqualTo(1);
        assertThat(service.starvedInputBlocks()).isEqualTo(1);
        assertThat(overflowWarnings()).containsExactly(overflow(1, 72, 1));
    }

    /** Ruling E281: a dropped starved block whose starved run goes on into the declared stop gets nothing. */
    @Test
    void aStarvedDropWhoseRunContinuesIntoTheDeclaredStopGetsNothing() throws Exception {
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        TrackCapture vocal = aStarvedDropThenAKeptStarvedBlock(ring, new ObservedFileChannel.Journal());
        publishSilence(ring, 88, NO);
        service.awaitFlushed(GUARD);
        service.requestStop(TakeManifest.SealedBy.STOP);
        awaitTermination(service);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.gaps()).isEmpty();
        assertThat(manifest.overflowBlocks()).isZero();
        assertThat(dropouts()).isZero();
        assertThat(overflowWarnings()).isEmpty();
        assertThat(manifest.inputStops()).containsExactly(stop(vocal, 72, 72));
        assertThat(warnings).contains("Track 'Vocal': input from 'Input 0' stopped delivering audio at 0:00.0 into"
                + " the take; the track records silence from there.");
    }

    /** Ruling E281: at the Stop, with no declared stop after it, a dropped starved block counts. */
    @Test
    void atTheStopAStarvedDropWithItsStarvedRunOpenCounts() throws Exception {
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        aStarvedDropThenAKeptStarvedBlock(ring, new ObservedFileChannel.Journal());
        service.requestStop(TakeManifest.SealedBy.STOP);
        awaitTermination(service);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.gaps()).containsExactly(gap(72, 1));
        assertThat(manifest.overflowBlocks()).isEqualTo(1);
        assertThat(dropouts()).as("the dropped block and the kept starved block").isEqualTo(2);
    }

    /** A delivered then a starved drop, a starved block kept at 88, then the declared stop at 96 and the Stop. */
    private void aDeliveredAndAStarvedDropThenTheStop(CaptureRing ring, Region starvedRegion) throws Exception {
        vocalOn(ring, new ObservedFileChannel.Journal());
        startAndAwaitReadiness();
        publishInput(ring, 0);
        service.awaitFlushed(GUARD);
        fillWhileHeldThenDrop(ring, SLOT_FRAMES, CaptureRing.DELIVERED, Region.NONE,
                CaptureRing.DELIVERED, CaptureRing.STARVED);
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);
        publishStarved(ring, 88, starvedRegion);
        service.awaitFlushed(GUARD);
        publishSilence(ring, 96, NO);
        service.awaitFlushed(GUARD);
        service.requestStop(TakeManifest.SealedBy.STOP);
        awaitTermination(service);
    }

    /** Ruling E281: such a run counts its delivered block, not its starved one, which the declared stop covers. */
    @Test
    void aRunOfADeliveredThenAStarvedDropBeforeAKeptStarvedBlockAndAStopCountsItsDeliveredBlock() throws Exception {
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        aDeliveredAndAStarvedDropThenTheStop(ring, Region.NONE);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.gaps()).containsExactly(gap(72, 1));
        assertThat(manifest.overflowBlocks()).isEqualTo(1);
        assertThat(dropouts()).isEqualTo(1);
    }

    /** Ruling E281: that delivered block is judged by its own extent, [72, 80), not to the next block. */
    @Test
    void aRunOfADeliveredThenAStarvedDropBeforeAKeptStarvedBlockAndAStopIsJudgedByItsOwnExtent() throws Exception {
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        aDeliveredAndAStarvedDropThenTheStop(ring, Region.of(80, 200));

        TakeManifest manifest = finalManifest();
        assertThat(manifest.gaps()).containsExactly(gap(72, 1));
        assertThat(manifest.overflowBlocks()).isEqualTo(1);
        assertThat(service.fileOverflowBlocks()).as("[72, 80) lies before [80, 200)").isZero();
    }

    /** Rulings E90 and E281: at a device loss a dropped starved block whose starved run is open gets nothing. */
    @Test
    void atADeviceLossAStarvedDropWithItsStarvedRunOpenGetsNothing() throws Exception {
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        TrackCapture vocal = aStarvedDropThenAKeptStarvedBlock(ring, new ObservedFileChannel.Journal());
        service.requestStop(TakeManifest.SealedBy.DEVICE_LOSS);
        awaitTermination(service);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.gaps()).isEmpty();
        assertThat(manifest.overflowBlocks()).isZero();
        assertThat(dropouts()).isZero();
        assertThat(manifest.inputSilences()).as("written, not counted")
                .containsExactly(new TakeManifest.InputSilence(vocal.trackId(), 80, 8));
        assertThat(manifest.sealedBy()).contains(TakeManifest.SealedBy.DEVICE_LOSS);
    }

    /** Ruling E281: a delivered block the ring drops ends the starved run before it, so the waiting run counts. */
    @Test
    void aDeliveredBlockTheRingDropsEndsTheStarvedRunBeforeIt() {
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        aStarvedDropThenAKeptStarvedBlock(ring, new ObservedFileChannel.Journal());
        fillWhileHeldThenDrop(ring, 88, CaptureRing.STARVED, Region.NONE, CaptureRing.DELIVERED);
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);
        publishStarved(ring, 160, Region.NONE);
        service.awaitFlushed(GUARD);

        TakeManifest manifest = service.lastManifest().orElseThrow();
        assertThat(manifest.gaps()).containsExactly(gap(72, 1), gap(152, 1));
        assertThat(manifest.overflowBlocks()).isEqualTo(2);
    }

    /**
     * Ruling E281, at a device loss: a run waiting on a starved run that a dropped delivery ended counts, and the
     * delivery the final sweep's look found is lined at the end of the last block applied.
     */
    @Test
    void atADeviceLossAWaitingRunWhoseStarvedRunADroppedDeliveryEndedCounts() throws Exception {
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        aStarvedDropThenAKeptStarvedBlock(ring, new ObservedFileChannel.Journal());
        fillWhileHeldThenDrop(ring, 88, CaptureRing.STARVED, Region.NONE, CaptureRing.DELIVERED);
        service.requestStop(TakeManifest.SealedBy.DEVICE_LOSS);
        awaitTermination(service);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.gaps()).containsExactly(gap(72, 1), gap(152, 1));
        assertThat(manifest.overflowBlocks()).isEqualTo(2);
        assertThat(service.fileOverflowBlocks()).isEqualTo(2);
        assertThat(manifest.sealedBy()).contains(TakeManifest.SealedBy.DEVICE_LOSS);
    }

    /**
     * Ruling E281, at a device loss: a starved drop the delivery the ring dropped after it ended is lined with
     * that delivery, both found by the final sweep's look.
     */
    @Test
    void atADeviceLossAStarvedDropADroppedDeliveryEndedIsLinedWithIt() throws Exception {
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        vocalOn(ring, new ObservedFileChannel.Journal());
        startAndAwaitReadiness();
        publishInput(ring, 0);
        service.awaitFlushed(GUARD);
        fillWhileHeldThenDrop(ring, SLOT_FRAMES, CaptureRing.DELIVERED, Region.NONE,
                CaptureRing.STARVED, CaptureRing.DELIVERED);
        service.requestStop(TakeManifest.SealedBy.DEVICE_LOSS);
        awaitTermination(service);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.gaps()).containsExactly(gap(72, 2));
        assertThat(manifest.overflowBlocks()).isEqualTo(2);
        assertThat(manifest.sealedBy()).contains(TakeManifest.SealedBy.DEVICE_LOSS);
    }

    /**
     * Ruling E294, a Known limit: input-starved blocks the take received, then a starved and a delivered block the
     * ring dropped, then the input's declared stop. The received starved blocks end only at a delivered block the
     * take received, so they are the stop's, which stands at the end of the last delivered block received; the
     * dropped starved block ends at the dropped delivery and counts with it, on one gap= line.
     */
    @Test
    void receivedStarvedBlocksBeforeADroppedDeliveryAndTheDeclaredStopAreTheStopsAndOnlyTheDropsCount()
            throws Exception {
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        TrackCapture vocal = vocalOn(ring, new ObservedFileChannel.Journal());
        startAndAwaitReadiness();
        publishInput(ring, 0);
        service.awaitFlushed(GUARD);
        fillWhileHeldThenDrop(ring, SLOT_FRAMES, CaptureRing.STARVED, Region.NONE,
                CaptureRing.STARVED, CaptureRing.DELIVERED);
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);
        publishSilence(ring, 88, NO);
        service.awaitFlushed(GUARD);
        service.requestStop(TakeManifest.SealedBy.STOP);
        awaitTermination(service);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.inputStops()).containsExactly(stop(vocal, 8, 8));
        assertThat(manifest.inputSilences()).as("the received starved blocks are reported under the stop").isEmpty();
        assertThat(service.starvedInputBlocks()).isZero();
        assertThat(manifest.gaps()).containsExactly(gap(72, 2));
        assertThat(dropouts()).as("the dropped starved block and the dropped delivery").isEqualTo(2);
    }

    /**
     * A delivered block at {@code firstBlock}, the ring filled from {@code fillStart} with blocks of {@code fill}
     * and {@code drops} dropped, all under {@code region}, then the Stop: the seal's stop line for dropped silence.
     */
    private TrackCapture blocksUnderARegionThenTheStop(long firstBlock, long fillStart, int fill, Region region,
                                                       int... drops) {
        return blocksUnderARegionThenTheSeal(TakeManifest.SealedBy.STOP, firstBlock, fillStart, fill, region, drops);
    }

    /** {@link #blocksUnderARegionThenTheStop}'s blocks, then the take sealed for {@code seal}. */
    private TrackCapture blocksUnderARegionThenTheSeal(TakeManifest.SealedBy seal, long firstBlock, long fillStart,
                                                       int fill, Region region, int... drops) {
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        TrackCapture vocal = vocalOn(ring, new ObservedFileChannel.Journal());
        blocksUnderARegion(ring, firstBlock, fillStart, fill, region, drops);
        service.requestStop(seal);
        awaitTermination(service);
        return vocal;
    }

    /** Starts the take, then publishes {@link #blocksUnderARegionThenTheStop}'s blocks on {@code ring}. */
    private void blocksUnderARegion(CaptureRing ring, long firstBlock, long fillStart, int fill, Region region,
                                    int... drops) {
        startAndAwaitReadiness();
        publishBlock(ring, firstBlock, 1, region, NO);
        service.awaitFlushed(GUARD);
        fillWhileHeldThenDrop(ring, fillStart, fill, region, drops);
    }

    /** "Vocal" on {@code ring}, as {@link #vocalOn} gives it, in a take that records each lap in a lane of its own. */
    private TrackCapture vocalOnALoopRecordTake(CaptureRing ring) {
        TrackCapture vocal = journalledPhysicalCapture(new Track("Vocal", TrackType.AUDIO), 0,
                new ObservedFileChannel.Journal());
        CaptureFlushService.TakeConfig config = new CaptureFlushService.TakeConfig(takeDir, STEREO_16, 120,
                0, 0, null, true, Duration.ofSeconds(5), Instant.parse("2026-09-29T10:00:00Z"));
        DiskHeadroomWatch watch = new DiskHeadroomWatch(takeDir, () -> 10 * GIB, GIB, 64L << 20,
                Duration.ZERO, clock::get, warnings::add);
        service = new CaptureFlushService(ring, config, List.of(vocal), watch, warnings::add, clock::get);
        service.setInputRings(new CaptureRing[]{ring});
        return vocal;
    }

    /** Ruling E282: a delivered end inside the punch region is the seal's stop for the silence it dropped. */
    @Test
    void inAPunchTakeTheSealsStopForDroppedSilenceIsTheDeliveredEndInsideTheRegion() throws Exception {
        TrackCapture vocal = blocksUnderARegionThenTheStop(0, 56, CaptureRing.DELIVERED, Region.of(80, 200), 0, 0);

        assertThat(finalManifest().inputStops()).containsExactly(stop(vocal, 120, 120));
        assertThat(warnings).contains("Track 'Vocal': input from 'Input 0' stopped delivering audio at 0:00.0 into"
                + " the take; the track holds no audio from there.");
    }

    /** Ruling E282: an input that delivered to punch-out gets no line from the seal. */
    @Test
    void inAPunchTakeTheSealsStopForDroppedSilenceADeliveredEndPastPunchOutGetsNoLine() throws Exception {
        blocksUnderARegionThenTheStop(0, 176, CaptureRing.DELIVERED, Region.of(80, 200), 0, 0);

        assertThat(finalManifest().inputStops()).isEmpty();
        assertThat(warnings).noneMatch(warning -> warning.contains("input from"));
    }

    /** Ruling E282: an end before punch-in is marked at the region's first frame the silence reaches. */
    @Test
    void inAPunchTakeTheSealsStopForDroppedSilenceAnEndBeforePunchInIsMarkedAtTheRegionsFirstFrame()
            throws Exception {
        TrackCapture vocal = blocksUnderARegionThenTheStop(0, SLOT_FRAMES, CaptureRing.STARVED, Region.of(80, 200),
                0, 0, 0);

        assertThat(finalManifest().inputStops()).containsExactly(stop(vocal, 80, 80));
        assertThat(warnings).contains("Track 'Vocal': input from 'Input 0' delivered no audio; the track holds no"
                + " audio.");
    }

    /** Ruling E282: silence that ends before the region gets no line. */
    @Test
    void inAPunchTakeTheSealsStopForDroppedSilenceThatEndsBeforeTheRegionGetsNoLine() throws Exception {
        blocksUnderARegionThenTheStop(0, SLOT_FRAMES, CaptureRing.STARVED, Region.of(80, 200), 0);

        assertThat(finalManifest().inputStops()).isEmpty();
    }

    /** Rulings E179 and E282: the stop is the region's first frame in the lap the silence first reaches it in. */
    @Test
    void inALoopAndPunchTakeTheStopIsTheRegionsFirstFrameInTheLapTheSilenceReachesItIn() throws Exception {
        TrackCapture vocal = blocksUnderARegionThenTheStop(40, 48, CaptureRing.DELIVERED,
                Region.looped(60, 100, 70, 136), 0, 0, 0, 0);

        assertThat(finalManifest().inputStops()).containsExactly(stop(vocal, 70, 70));
    }

    /**
     * Rulings E283, E296 and E297: in a loop take whose loop starts after punch-in, silence of an input that ended
     * before punch-in, none of which reached the take, starting at punch-out before the loop end, flags the track
     * whose file holds frames at this lap's punch-in, before the re-entry at the next lap's loop start is
     * considered; the starved run before it is the stop's (ruling E49).
     */
    @Test
    void inALoopTakeSilenceFromPunchOutBeforeTheLoopEndFlagsTheTrackAtThisLapsRegionStart() throws Exception {
        TrackCapture vocal = blocksUnderARegionThenTheStop(0, SLOT_FRAMES, CaptureRing.STARVED,
                Region.looped(56, 72, 60, 80), 0, 0, 0);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.segmentsFor(vocal.trackId()).stream().mapToLong(TakeManifest.SegmentEntry::frames).sum())
                .as("premise: the file holds the starved blocks inside [56, 72)").isEqualTo(16);
        assertThat(manifest.inputStops()).containsExactly(stop(vocal, 56, 56));
        assertThat(manifest.inputSilences()).isEmpty();
        assertThat(service.starvedInputBlocks()).isZero();
        assertThat(warnings).containsExactly(
                "Track 'Vocal': input from 'Input 0' delivered no audio; the track holds no audio.");
    }

    /**
     * Rulings E88 and E297: silence that starts after a wrap, where the transport played it before the region, is
     * not silence from punch-out in this lap: the re-entry rule judges it, and silence that never reaches the
     * region again gives no stop, so the starved run before it is written and counted.
     */
    @Test
    void inALoopTakeSilenceStartingAfterAWrapBeforeTheRegionIsJudgedByTheReentryRule() throws Exception {
        TrackCapture vocal = blocksUnderARegionThenTheStop(SLOT_FRAMES, 2 * SLOT_FRAMES, CaptureRing.STARVED,
                Region.looped(56, 72, 32, 80), 0, 0, 0);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.segmentsFor(vocal.trackId()).stream().mapToLong(TakeManifest.SegmentEntry::frames).sum())
                .as("premise: the file holds the starved blocks inside [56, 72)").isEqualTo(16);
        assertThat(manifest.inputStops()).as("[80, 104) is played as [32, 56), before the region").isEmpty();
        assertThat(manifest.inputSilences()).containsExactly(new TakeManifest.InputSilence(vocal.trackId(), 56, 16));
        assertThat(service.starvedInputBlocks()).isEqualTo(2);
    }

    /**
     * Rulings E283 and E300: with no loop recording, silence whose first block lies past the loop end and is played
     * at punch-out in the next lap flags the track, whose one file holds frames, at that lap's region start, the
     * later of punch-in and the loop start; the starved run before it is the stop's (ruling E49), and the starved
     * block the ring dropped inside that lap's region changes nothing: it wrote no frame.
     */
    @Test
    void inALoopTakeSilenceFirstPlayedFromPunchOutInTheNextLapFlagsTheTrackAtThatLapsRegionStart() throws Exception {
        TrackCapture vocal = blocksUnderARegionThenTheStop(0, 32, CaptureRing.STARVED, Region.looped(56, 72, 64, 96),
                CaptureRing.STARVED, 0);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.segmentsFor(vocal.trackId()).stream().mapToLong(TakeManifest.SegmentEntry::frames).sum())
                .as("premise: the file holds the starved blocks inside [56, 72)").isEqualTo(16);
        assertThat(manifest.inputStops()).as("raw 104 is played at 72 in lap 2, whose region starts at 64")
                .containsExactly(stop(vocal, 64, 64));
        assertThat(manifest.inputSilences()).isEmpty();
        assertThat(service.starvedInputBlocks()).isZero();
        assertThat(manifest.gaps()).isEmpty();
        assertThat(warnings).containsExactly(
                "Track 'Vocal': input from 'Input 0' delivered no audio; the track holds no audio.");
    }

    /**
     * Rulings E300 and E301: under loop recording the lap the silence's first block is played in has no file, since
     * none of its blocks reached the take: no stop, so the kept starved run is written and counted as input-silence
     * in lap 1's file, and the starved block the ring dropped, which no stop covers, is lined at 96 and counted:
     * played as [64, 72), it lies in lap 2's region.
     */
    @Test
    void underLoopRecordingSilenceFirstPlayedInALapNoBlockOfWhichReachedTheTakeGivesNoStop() throws Exception {
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        TrackCapture vocal = vocalOnALoopRecordTake(ring);
        blocksUnderARegion(ring, 0, 32, CaptureRing.STARVED, Region.looped(56, 72, 64, 96), CaptureRing.STARVED, 0);
        service.requestStop(TakeManifest.SealedBy.STOP);
        awaitTermination(service);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.segmentsFor(vocal.trackId()).stream().mapToLong(TakeManifest.SegmentEntry::frames).sum())
                .as("premise: lap 1's file holds the starved blocks inside [56, 72)").isEqualTo(16);
        assertThat(manifest.inputStops()).isEmpty();
        assertThat(manifest.inputSilences()).containsExactly(new TakeManifest.InputSilence(vocal.trackId(), 56, 16));
        assertThat(manifest.gaps()).containsExactly(gap(96, 1));
        assertThat(manifest.overflowBlocks()).isEqualTo(1);
        assertThat(service.fileOverflowBlocks()).isEqualTo(1);
        assertThat(service.starvedInputBlocks()).isEqualTo(2);
        assertThat(dropouts()).isEqualTo(3);
        assertThat(warnings).containsExactly(overflow(1, 96, 1));
    }

    /**
     * Rulings E296 and E301: an input that ended before punch-in, whose dropped silence starts at punch-out, flags
     * only a track whose file holds frames, and the kept starved blocks all lie before the region, so this track's
     * holds none: no stop covers the starved blocks the ring dropped, which are lined at the end of the last block
     * applied and counted by their own extent, [72, 96), as a starved run with no declared stop after it.
     */
    @Test
    void atTheStopStarvedDropsBeforeADeclaredStopThatFlagsNoTrackAreLinedAndCounted() throws Exception {
        TrackCapture vocal = blocksUnderARegionThenTheStop(0, SLOT_FRAMES, CaptureRing.STARVED, Region.of(80, 96),
                CaptureRing.STARVED, CaptureRing.STARVED, CaptureRing.STARVED, 0);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.segmentsFor(vocal.trackId()).stream().mapToLong(TakeManifest.SegmentEntry::frames).sum())
                .as("premise: [8, 72) lies before [80, 96)").isZero();
        assertThat(manifest.inputStops()).as("no stop at punch-in for a file that holds nothing").isEmpty();
        assertThat(manifest.inputSilences()).isEmpty();
        assertThat(manifest.gaps()).containsExactly(gap(72, 3));
        assertThat(manifest.overflowBlocks()).isEqualTo(3);
        assertThat(service.fileOverflowBlocks()).as("[72, 96) reaches [80, 96)").isEqualTo(3);
        assertThat(service.starvedInputBlocks()).isZero();
        assertThat(dropouts()).isEqualTo(3);
        assertThat(warnings).containsExactly(overflow(3, 72, 3));
    }

    /**
     * Ruling E301, applied: starved blocks the ring dropped before a declared stop that flags no track, its file
     * holding nothing once the region has passed, wait past that stop and count at the Stop by the frames to the
     * stop's block, [72, 96), which reach [80, 96).
     */
    @Test
    void aStarvedDropBeforeAKeptDeclaredStopThatFlagsNoTrackCountsAtTheStop() throws Exception {
        aStarvedRunWithDropsThenSilenceAt(Region.of(80, 96), 96);
        assertThat(service.lastManifest().orElseThrow().gaps()).as("the run waits").isEmpty();
        service.requestStop(TakeManifest.SealedBy.STOP);
        awaitTermination(service);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.inputStops()).isEmpty();
        assertThat(manifest.inputSilences()).isEmpty();
        assertThat(manifest.gaps()).containsExactly(gap(72, 3));
        assertThat(manifest.overflowBlocks()).isEqualTo(3);
        assertThat(service.fileOverflowBlocks()).isEqualTo(3);
        assertThat(service.starvedInputBlocks()).isZero();
        assertThat(dropouts()).isEqualTo(3);
        assertThat(warnings).containsExactly(overflow(3, 72, 3));
    }

    /**
     * Ruling E301, applied: such starved drops are the declared stop's once a later block of its silence, at 120,
     * flags the track: no line and no count.
     */
    @Test
    void aStarvedDropBeforeADeclaredStopIsTheStopsOnceALaterBlockOfItsSilenceFlagsTheTrack() throws Exception {
        TrackCapture vocal = aStarvedRunWithDropsThenSilenceAt(Region.of(120, 200), 96, 120);
        service.requestStop(TakeManifest.SealedBy.STOP);
        awaitTermination(service);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.inputStops()).containsExactly(stop(vocal, 120, 120));
        assertThat(manifest.inputSilences()).isEmpty();
        assertThat(manifest.gaps()).isEmpty();
        assertThat(manifest.overflowBlocks()).isZero();
        assertThat(service.fileOverflowBlocks()).isZero();
        assertThat(service.starvedInputBlocks()).isZero();
        assertThat(warnings).containsExactly(
                "Track 'Vocal': input from 'Input 0' delivered no audio; the track records silence.");
    }

    /**
     * Block 0 delivered, the ring filled from 8 with starved blocks and three more starved dropped, all under
     * {@code region}, then each of {@code silences} kept as a block of the declared stop's silence.
     */
    private TrackCapture aStarvedRunWithDropsThenSilenceAt(Region region, long... silences) {
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        TrackCapture vocal = vocalOn(ring, new ObservedFileChannel.Journal());
        blocksUnderARegion(ring, 0, SLOT_FRAMES, CaptureRing.STARVED, region, CaptureRing.STARVED,
                CaptureRing.STARVED, CaptureRing.STARVED);
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);
        for (long frame : silences) {
            publishBlock(ring, frame, 0, region, NO);
            service.awaitFlushed(GUARD);
        }
        return vocal;
    }

    /**
     * Rulings E281 and E301, the review's case: loop [0, 64), punch [16, 48). The ring is filled with lap 1's
     * delivered [24, 64), lap 2's delivered [0, 8) and starved [8, 24), and drops lap 2's starved block at 24; lap
     * 2's starved [32, 64) and lap 3's first block of the declared stop's silence, which flags no track (its
     * delivered end 8 lies before punch-in and the region is not passed), are kept; then the Stop. The dropped
     * block counts as an applied starved block in its place would: its line at 24, the end of the block before it,
     * reaching [16, 48) by the frames to 32, beside the kept starved blocks' [16, 24) and [32, 48).
     */
    @Test
    void inALoopTakeAStarvedDropBeforeADeclaredStopThatFlagsNoTrackCountsAsAnAppliedOneWould() throws Exception {
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        TrackCapture vocal = vocalOn(ring, new ObservedFileChannel.Journal());
        Region region = Region.looped(16, 48, 0, 64);
        startAndAwaitReadiness();
        service.setDrainPaused(true);
        for (long frame = 24; frame < 64; frame += SLOT_FRAMES) {
            publishBlock(ring, frame, 1, region, NO);
        }
        publishBlock(ring, 0, 1, region, NO);
        publishStarved(ring, 8, region);
        publishStarved(ring, 16, region);
        assertThat(ring.claim(CaptureRing.STARVED)).as("fixture: the full ring drops lap 2's block at 24").isNull();
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);
        for (long frame = 32; frame < 64; frame += SLOT_FRAMES) {
            publishStarved(ring, frame, region);
        }
        publishBlock(ring, 0, 0, region, NO);
        service.awaitFlushed(GUARD);
        service.requestStop(TakeManifest.SealedBy.STOP);
        awaitTermination(service);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.inputStops()).isEmpty();
        assertThat(manifest.inputSilences()).containsExactly(new TakeManifest.InputSilence(vocal.trackId(), 16, 8),
                new TakeManifest.InputSilence(vocal.trackId(), 32, 16));
        assertThat(manifest.gaps()).containsExactly(gap(24, 1));
        assertThat(manifest.overflowBlocks()).isEqualTo(1);
        assertThat(service.fileOverflowBlocks()).isEqualTo(1);
        assertThat(service.starvedInputBlocks()).isEqualTo(3);
        assertThat(dropouts()).as("the 4 starved blocks inside the region, as if all were applied").isEqualTo(4);
        assertThat(warnings).containsExactly(overflow(1, 24, 1));
    }

    /**
     * Ruling E303: silence the take kept before the region, which flagged no track, does not stop the seal marking
     * the silence the ring dropped after it, [72, 88), at the region's first frame it reaches, at the Stop or at a
     * device loss.
     */
    @ParameterizedTest(name = "sealed by {0}")
    @ValueSource(strings = {"STOP", "DEVICE_LOSS"})
    void theSealMarksDroppedSilenceAfterKeptSilenceThatFlaggedNoTrack(TakeManifest.SealedBy seal) throws Exception {
        TrackCapture vocal = blocksUnderARegionThenTheSeal(seal, 0, SLOT_FRAMES, 0, Region.of(80, 200), 0, 0);

        assertThat(finalManifest().inputStops()).as("[8, 72) was kept and flagged nothing")
                .containsExactly(stop(vocal, 80, 80));
        assertThat(warnings).containsExactly(
                "Track 'Vocal': input from 'Input 0' delivered no audio; the track holds no audio.");
    }

    /** "Vocal": delivered [0, 32), to punch-out under {@code region}, then kept silence, which flags nothing. */
    private TrackCapture deliveredToPunchOutThenKeptSilence(CaptureRing ring, ObservedFileChannel.Journal journal,
                                                          Region region) {
        TrackCapture vocal = vocalOn(ring, journal);
        startAndAwaitReadiness();
        for (long frame = 0; frame < 32; frame += SLOT_FRAMES) {
            publishBlock(ring, frame, 1, region, NO);
        }
        publishBlock(ring, 32, 0, region, NO);
        service.awaitFlushed(GUARD);
        return vocal;
    }

    /**
     * Ruling E303: in a loop take, the input delivered to punch-out and its kept silence after it flagged nothing;
     * its silence the ring dropped, raw [104, 152), wraps into lap 2's region, so the seal marks the re-entry.
     */
    @Test
    void atTheStopSilenceDroppedInTheNextLapAfterKeptSilenceThatFlaggedNothingGetsTheReentryStop() throws Exception {
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        Region region = Region.looped(16, 32, 0, 128);
        TrackCapture vocal = deliveredToPunchOutThenKeptSilence(ring, new ObservedFileChannel.Journal(), region);
        service.setDrainPaused(true);
        for (long frame = 40; frame <= 96; frame += SLOT_FRAMES) {
            publishBlock(ring, frame, 0, region, NO);
        }
        for (int drop = 0; drop < 6; drop++) {
            assertThat(ring.claim(0)).as("fixture: the full ring drops silence").isNull();
        }
        service.requestStop(TakeManifest.SealedBy.STOP);
        awaitTermination(service);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.inputStops()).containsExactly(stop(vocal, 16, 16));
        assertThat(manifest.gaps()).isEmpty();
        assertThat(manifest.overflowBlocks()).isZero();
        assertThat(dropouts()).isZero();
        assertThat(warnings).containsExactly("Track 'Vocal': input from 'Input 0' stopped delivering audio at 0:00.0"
                + " into the take; the track holds no audio from there.");
    }

    /** Ruling E303: so is a held silence block in lap 2's region, read by its own header. */
    @Test
    void aHeldSilenceBlockAfterKeptSilenceThatFlaggedNothingIsReadByItsHeader() throws Exception {
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        Region region = Region.looped(16, 32, 0, 128);
        TrackCapture vocal = deliveredToPunchOutThenKeptSilence(ring, journal, region);
        AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch held = holdTheSealsForceOnceArmed(journal, armed, release);

        try {
            stopAndHoldTheSeal(held, armed);
            for (long frame = 0; frame <= 16; frame += SLOT_FRAMES) {
                publishBlock(ring, frame, 0, region, NO);
            }
        } finally {
            release.countDown();
        }
        awaitTermination(service);

        assertThat(finalManifest().inputStops()).containsExactly(stop(vocal, 16, 16));
        assertThat(warnings).containsExactly("Track 'Vocal': input from 'Input 0' stopped delivering audio at 0:00.0"
                + " into the take; the track holds no audio from there.");
    }

    /**
     * Rulings E49, E277 and E281: a starved drop followed by the input's first silence, left behind at the Stop,
     * is the declared stop's: the seal writes the stop at the delivered end, and the drop gets no line and no count.
     */
    @Test
    void aStarvedDropBeforeTheInputsFirstSilenceLeftBehindAtTheStopIsTheStops() throws Exception {
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        TrackCapture vocal = vocalOn(ring, journal);
        AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch held = holdTheSealsForceOnceArmed(journal, armed, release);
        startAndAwaitReadiness();
        publishInput(ring, 0);
        service.awaitFlushed(GUARD);
        fillWhileHeldThenDrop(ring, SLOT_FRAMES, CaptureRing.DELIVERED, Region.NONE, CaptureRing.STARVED);

        try {
            stopAndHoldTheSeal(held, armed);
            publishSilence(ring, 80, NO);
        } finally {
            release.countDown();
        }
        awaitTermination(service);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.inputStops()).containsExactly(stop(vocal, 72, 72));
        assertThat(manifest.gaps()).isEmpty();
        assertThat(dropouts()).isZero();
    }

    /**
     * Ruling E287 at the seal: drops no kept block follows, a silence-only block before an instrument's audio, are
     * judged over the whole run, [72, 88), which reaches into [80, 200); in the other order by the audio block's own
     * extent, [72, 80).
     */
    @ParameterizedTest(name = "silence first: {0}, sealed by {1}")
    @CsvSource({"true, STOP", "false, STOP", "true, DEVICE_LOSS", "false, DEVICE_LOSS"})
    void dropsNoKeptBlockFollowsWhoseSilenceCameBeforeAnInstrumentsAudioAreJudgedOverTheWholeRun(
            boolean silenceFirst, TakeManifest.SealedBy seal) throws Exception {
        Track synth = new Track("Synth", TrackType.AUDIO);
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 2, 2, 8);
        service = newService(ring, List.of(instrumentCapture(synth, journalledSessions(STEREO_16, journal)),
                journalledPhysicalCapture(new Track("Mic", TrackType.AUDIO), 0, journal)));
        startAndAwaitReadiness();
        publish(ring, 0, block(2, .5f), 2);
        service.awaitFlushed(GUARD);
        service.setDrainPaused(true);
        for (long frame = SLOT_FRAMES; frame <= 64; frame += SLOT_FRAMES) {
            publishInstrumentOnly(ring, frame, Region.of(80, 200));
        }
        for (int kind : silenceFirst ? new int[] {0, CaptureRing.INSTRUMENT} : new int[] {CaptureRing.INSTRUMENT, 0}) {
            assertThat(ring.claim(kind)).as("fixture: the full ring drops a block").isNull();
        }
        service.requestStop(seal);
        awaitTermination(service);

        assertThat(finalManifest().gaps()).containsExactly(gap(72, 1));
        assertThat(service.fileOverflowBlocks()).isEqualTo(silenceFirst ? 1 : 0);
    }

    /** Ruling E282: with the legacy beat range the stop is the delivered end only when the silence starts in it. */
    @ParameterizedTest(name = "filled from {0}: stop {1}")
    @CsvSource({"56, 120", "176, -1"})
    void withTheLegacyRangeTheStopIsTheDeliveredEndOnlyWhenTheSilenceStartsInsideIt(long fillStart, long stopFrame)
            throws Exception {
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        TrackCapture vocal = journalledPhysicalCapture(new Track("Vocal", TrackType.AUDIO), 0,
                new ObservedFileChannel.Journal());
        service = newService(ring, List.of(vocal), new PunchRange(80 / 24_000.0, 200 / 24_000.0));
        startAndAwaitReadiness();
        fillWhileHeldThenDrop(ring, fillStart, CaptureRing.DELIVERED, Region.NONE, 0, 0);
        service.requestStop(TakeManifest.SealedBy.STOP);
        awaitTermination(service);

        if (stopFrame < 0) {
            assertThat(finalManifest().inputStops()).isEmpty();
        } else {
            assertThat(finalManifest().inputStops()).containsExactly(stop(vocal, stopFrame, stopFrame));
        }
    }

    /** Ruling E282: with the legacy beat range, the dropped silence's block starts are followed across the wrap. */
    @Test
    void withTheLegacyRangeTheSilencesBlockStartsAreFollowedAcrossTheLoopWrap() throws Exception {
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        TrackCapture vocal = journalledPhysicalCapture(new Track("Vocal", TrackType.AUDIO), 0,
                new ObservedFileChannel.Journal());
        service = newService(ring, List.of(vocal), new PunchRange(76 / 24_000.0, 100 / 24_000.0));
        startAndAwaitReadiness();
        fillWhileHeldThenDrop(ring, 64, CaptureRing.DELIVERED, new Region(false, 0, 0, 64, 128), 0, 0, 0);
        service.requestStop(TakeManifest.SealedBy.STOP);
        awaitTermination(service);

        assertThat(finalManifest().inputStops()).as("raw 144, the third block, is played at 80, inside the range")
                .containsExactly(stop(vocal, 128, 128));
    }

    /** Ruling E283: a later silence block left behind after dropped silence is not read; the stop is the seal's. */
    @Test
    void aLaterSilenceBlockLeftBehindAfterDroppedSilenceGivesTheDroppedSilencesStop() throws Exception {
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing firstRing = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        CaptureRing secondRing = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        TrackCapture second = twoInputs(firstRing, secondRing, journal).get(1);
        AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch held = holdTheSealsForceOnceArmed(journal, armed, release);
        startAndAwaitReadiness();
        publishInput(firstRing, 0);
        fillWhileHeldThenDrop(secondRing, 0, CaptureRing.DELIVERED, Region.NONE, 0, 0);

        try {
            stopAndHoldTheSeal(held, armed);
            publishSilence(secondRing, 80, 200);
        } finally {
            release.countDown();
        }
        awaitTermination(service);

        assertThat(finalManifest().inputStops()).containsExactly(stop(second, 64, 64));
    }

    /** Ruling E283: a held silence block that is the first to reach the region is read by its own header. */
    @Test
    void aHeldSilenceBlockAtTheRegionAfterDroppedSilenceBeforeItIsReadByItsHeader() throws Exception {
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing firstRing = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        CaptureRing secondRing = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        TrackCapture second = twoInputs(firstRing, secondRing, journal).get(1);
        AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch held = holdTheSealsForceOnceArmed(journal, armed, release);
        startAndAwaitReadiness();
        publishInput(firstRing, 0);
        fillWhileHeldThenDrop(secondRing, 0, CaptureRing.DELIVERED, Region.of(80, 200), 0, 0);

        try {
            stopAndHoldTheSeal(held, armed);
            publishBlock(secondRing, 80, 0, Region.of(80, 200), 96);
        } finally {
            release.countDown();
        }
        awaitTermination(service);

        assertThat(finalManifest().inputStops()).containsExactly(stop(second, 80, 96));
    }

    /** Ruling E283: a held silence block's lap counts the wraps among the held blocks before it. */
    @Test
    void aHeldSilenceBlocksLapCountsTheWrapsAmongTheBlocksBeforeItTheTakeDidNotReceive() throws Exception {
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        TrackCapture vocal = vocalOn(ring, journal);
        AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch held = holdTheEmptyLanesDiscardOnceArmed(journal, armed, release);
        Region region = Region.looped(40, 56, 44, 64);
        startAndAwaitReadiness();
        publishBlock(ring, 0, 1, region, NO);
        publishBlock(ring, 8, 1, region, NO);
        service.awaitFlushed(GUARD);

        try {
            stopAndHoldTheSeal(held, armed);
            publishBlock(ring, 48, 1, region, NO);
            publishBlock(ring, 56, 1, region, NO);
            publishBlock(ring, 44, 1, region, NO);
            publishBlock(ring, 52, 0, region, NO);
        } finally {
            release.countDown();
        }
        awaitTermination(service);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.inputStops()).containsExactly(stop(vocal, 44, 44));
        assertThat(manifest.gaps()).containsExactly(gap(16, 3));
        assertThat(warnings).as("no delivered frame reached the file, so the warning can name no lap (ruling E293)")
                .contains("Track 'Vocal': input from 'Input 0' delivered no audio; the track holds no audio.");
    }

    /**
     * Ruling E298: an input none of whose blocks reached the take holds no frame on any track; the stop the seal
     * writes for its dropped silence stands at the take's start frame, in the words of a track that holds no
     * audio. The blocks before the drops stand in for blocks the final sweep never received.
     */
    @Test
    void anInputNoneOfWhoseBlocksReachedTheTakeGetsTheSealsStopAtTheTakesStartFrame() throws Exception {
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        TrackCapture vocal = journalledPhysicalCapture(new Track("Vocal", TrackType.AUDIO), 0, journal);
        CaptureFlushService.TakeConfig config = new CaptureFlushService.TakeConfig(takeDir, STEREO_16, 120,
                40 / 24_000.0, 40, null, false, Duration.ofSeconds(5), Instant.parse("2026-09-29T10:00:00Z"));
        DiskHeadroomWatch watch = new DiskHeadroomWatch(takeDir, () -> 10 * GIB, GIB, 64L << 20,
                Duration.ZERO, clock::get, warnings::add);
        service = new CaptureFlushService(ring, config, List.of(vocal), watch, warnings::add, clock::get);
        service.setInputRings(new CaptureRing[]{ring});
        AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch held = holdTheEmptyLanesDiscardOnceArmed(journal, armed, release);
        startAndAwaitReadiness();

        try {
            stopAndHoldTheSeal(held, armed);
            for (int block = 0; block < ring.capacity(); block++) {
                assertThat(ring.claim(0)).as("fixture: the ring has room for block %d", block).isNotNull();
                ring.publish();
            }
            assertThat(ring.claim(0)).as("fixture: the full ring drops silence").isNull();
            assertThat(ring.claim(0)).as("fixture: the full ring drops silence").isNull();
            for (int block = 0; block < ring.capacity(); block++) {
                assertThat(ring.peek()).isNotNull();
                ring.release();
            }
        } finally {
            release.countDown();
        }
        awaitTermination(service);

        assertThat(finalManifest().inputStops()).containsExactly(stop(vocal, 40, 40));
        assertThat(warnings).containsExactly(
                "Track 'Vocal': input from 'Input 0' delivered no audio; the track holds no audio.");
    }

    /**
     * Ruling E286: an early seal in a device-loss seal's final sweep, on either ring, leaves every ring's trailing
     * drops without a line, a count, a warning or a seal-written stop.
     */
    @ParameterizedTest(name = "the trailing drops on the first ring: {0}")
    @ValueSource(booleans = {true, false})
    void anEarlySealInADeviceLossSealsFinalSweepLinesNoRingsTrailingDrops(boolean trailingOnTheFirst)
            throws Exception {
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing firstRing = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        CaptureRing secondRing = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        AtomicLong freeBytes = new AtomicLong(10 * GIB);
        service = newTwoInputService(firstRing, secondRing, List.of(
                journalledPhysicalCapture(new Track("First", TrackType.AUDIO), 0, journal),
                journalledPhysicalCapture(new Track("Second", TrackType.AUDIO), 1, journal)), freeBytes::get);
        CaptureRing dropping = trailingOnTheFirst ? firstRing : secondRing;
        CaptureRing other = trailingOnTheFirst ? secondRing : firstRing;
        startAndAwaitReadiness();
        publishInput(firstRing, 0);
        publishInput(secondRing, 0);
        service.awaitFlushed(GUARD);
        fillWhileHeldThenDrop(dropping, SLOT_FRAMES, CaptureRing.DELIVERED, Region.NONE,
                CaptureRing.DELIVERED, CaptureRing.DELIVERED, 0);
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);
        service.setDrainPaused(true);
        publishInput(other, SLOT_FRAMES);
        freeBytes.set(0L);
        service.requestStop(TakeManifest.SealedBy.DEVICE_LOSS);
        awaitTermination(service);

        TakeManifest manifest = finalManifest();
        assertThat(manifest.gaps()).isEmpty();
        assertThat(manifest.overflowBlocks()).isZero();
        assertThat(dropouts()).isZero();
        assertThat(overflowWarnings()).isEmpty();
        assertThat(manifest.inputStops()).isEmpty();
        assertThat(manifest.sealedBy()).contains(TakeManifest.SealedBy.DISK_EXHAUSTION);
    }

    /**
     * Ruling E287: a run whose silence-only block came before an instrument's audio is judged over the whole run,
     * [72, 88), which reaches into [80, 200); in the other order by the audio block's own extent, [72, 80).
     */
    @ParameterizedTest(name = "silence first: {0}")
    @ValueSource(booleans = {true, false})
    void aRunWhoseSilenceCameBeforeAnInstrumentsAudioIsJudgedOverTheWholeRun(boolean silenceFirst) {
        Track synth = new Track("Synth", TrackType.AUDIO);
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 2, 2, 8);
        service = newService(ring, List.of(instrumentCapture(synth, journalledSessions(STEREO_16, journal)),
                journalledPhysicalCapture(new Track("Mic", TrackType.AUDIO), 0, journal)));
        startAndAwaitReadiness();
        publish(ring, 0, block(2, .5f), 2);
        service.awaitFlushed(GUARD);
        service.setDrainPaused(true);
        for (long frame = SLOT_FRAMES; frame <= 64; frame += SLOT_FRAMES) {
            publishInstrumentOnly(ring, frame, Region.NONE);
        }
        for (int kind : silenceFirst ? new int[] {0, CaptureRing.INSTRUMENT} : new int[] {CaptureRing.INSTRUMENT, 0}) {
            assertThat(ring.claim(kind)).as("fixture: the full ring drops a block").isNull();
        }
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);
        publishInstrumentOnly(ring, 88, Region.of(80, 200));
        service.awaitFlushed(GUARD);

        assertThat(service.lastManifest().orElseThrow().gaps()).containsExactly(gap(72, 1));
        assertThat(service.fileOverflowBlocks()).isEqualTo(silenceFirst ? 1 : 0);
    }

    /**
     * Block 0, then the ring filled from 8 with one drop; as the next pass applies 16 and 24 it publishes 80 and 88,
     * and as the pass after keeps 80, which lines the run, {@code onTheBlockAt80} runs, so 88 meets what it set.
     * Returns once the early seal has been announced, which the warnings record as {@code ANNOUNCED}.
     */
    private void aRunThenALaterBlockOfItsPassFails(CaptureRing ring, Runnable onTheBlockAt80) {
        publishInput(ring, 0);
        service.awaitFlushed(GUARD);
        fillWhileHeldAndDrop(ring, SLOT_FRAMES, 1);
        CompletionStage<Void> announced = service.earlySeal().thenAccept(_ -> warnings.add("ANNOUNCED"));
        service.setBlockObserver((sequence, startFrame, numFrames) -> {
            if (sequence == 2) {
                publishInput(ring, 80);
            } else if (sequence == 3) {
                publishInput(ring, 88);
            } else if (sequence == 9) {
                onTheBlockAt80.run();
            }
        });
        service.setDrainPaused(false);
        awaitWithinTheGuard(announced, "the early seal's announcement");
    }

    private int indexOfTheFirstWarningStartingWith(String start) {
        for (int index = 0; index < warnings.size(); index++) {
            if (warnings.get(index).startsWith(start)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Ruling E274: a run lined in a pass whose later block's write fails keeps its warning, given before the early
     * seal's and before the early seal is announced.
     */
    @Test
    void aRunsWarningComesBeforeTheEarlySealWhenALaterBlockOfItsPassFailsItsWrite() throws Exception {
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        vocalOn(ring, journal);
        startAndAwaitReadiness();

        aRunThenALaterBlockOfItsPassFails(ring, () -> journal.failNextWrites(1));

        int overflowAt = warnings.indexOf(overflow(1, 72, 1));
        int earlySealAt = indexOfTheFirstWarningStartingWith("Recording stopped early — ");
        assertThat(overflowAt).as("the run's warning").isNotNegative();
        assertThat(overflowAt).as("before the early seal's warning").isLessThan(earlySealAt);
        assertThat(earlySealAt).as("which comes before the early seal is announced")
                .isLessThan(warnings.indexOf("ANNOUNCED"));
        assertThat(finalManifest().gaps()).containsExactly(gap(72, 1));
        assertThat(dropouts()).isEqualTo(1);
    }

    /** Ruling E274: the same when the headroom floor discards the later block. */
    @Test
    void aRunsWarningComesBeforeTheEarlySealWhenTheHeadroomFloorDiscardsALaterBlockOfItsPass() throws Exception {
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 1, 1, 8);
        AtomicLong freeBytes = new AtomicLong(10 * GIB);
        service = newOneInputService(ring, journalledPhysicalCapture(new Track("Vocal", TrackType.AUDIO), 0,
                new ObservedFileChannel.Journal()), freeBytes);
        startAndAwaitReadiness();

        aRunThenALaterBlockOfItsPassFails(ring, () -> freeBytes.set(0L));

        int overflowAt = warnings.indexOf(overflow(1, 72, 1));
        assertThat(overflowAt).as("the run's warning").isNotNegative();
        assertThat(overflowAt).as("before the early seal is announced").isLessThan(warnings.indexOf("ANNOUNCED"));
        assertThat(awaitWithinTheGuard(service.earlySeal(), "the early seal"))
                .isInstanceOf(EarlySeal.DiskExhausted.class);
        assertThat(finalManifest().gaps()).containsExactly(gap(72, 1));
        assertThat(dropouts()).isEqualTo(1);
    }
}
