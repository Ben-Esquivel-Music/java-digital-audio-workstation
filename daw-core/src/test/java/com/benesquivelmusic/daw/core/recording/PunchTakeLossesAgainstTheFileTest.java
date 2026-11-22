package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.recording.TakeManifest.InputStopped;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SealedBy;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.awaitTermination;
import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.awaitWithinTheGuard;
import static org.assertj.core.api.Assertions.assertThat;

/** Punch takes checked against what the take's file holds (story 338, rulings E77, E104 and E118). */
class PunchTakeLossesAgainstTheFileTest {

    private static final int BLOCK = 8;
    private static final AudioFormat MONO = new AudioFormat(48_000.0, 1, 16, BLOCK);
    private static final long GIB = 1L << 30;
    private static final Duration GUARD = Duration.ofSeconds(10);

    private enum Kind { DELIVERED, STARVED, ENDED }

    @TempDir
    Path takeDir;

    private final AtomicLong clock = new AtomicLong();
    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private CaptureFlushService service;
    private CaptureRing ring;
    private TrackCapture capture;
    private long punchIn;
    private long punchOut;

    @AfterEach
    void stopTheFlushThread() {
        if (service != null) {
            service.requestStop(SealedBy.STOP);
            awaitTermination(service);
        }
    }

    @Test
    void aPreRollOverflowSealedBeforePunchInCountsNoDropoutsForAnEmptyFile() throws IOException {
        punchIn = 2000;
        punchOut = 3000;
        start(CaptureRing.MIN_SLOTS);
        feed(0, Kind.DELIVERED);
        service.setDrainPaused(true);
        for (int block = 0; block < CaptureRing.MIN_SLOTS; block++) {
            publish(8 + (long) block * BLOCK, Kind.DELIVERED);
        }
        for (int dropped = 0; dropped < 3; dropped++) {
            assertThat(ring.claim()).isNull();
        }
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);

        TakeManifest manifest = stopAndRead();
        long fileFrames = manifest.segmentsFor(capture.trackId()).stream()
                .mapToLong(TakeManifest.SegmentEntry::frames).sum();
        assertThat(fileFrames).as("premise: sealed in the pre-roll, the file holds no frame").isZero();
        assertThat(service.fileOverflowBlocks()).as("ruling E104: the gap's extent lies wholly in the pre-roll")
                .isZero();
        assertThat(service.overflowCount()).as("the ring counts every drop").isEqualTo(3);
        assertThat(manifest.gaps()).as("the gap= line is written as today").hasSize(1);
    }

    @Test
    void aGapNoBlockFollowsWhoseBlocksReachPunchInCountsWhole() throws IOException {
        punchIn = 2000;
        punchOut = 3000;
        start(CaptureRing.MIN_SLOTS);
        feed(1908, Kind.DELIVERED);
        service.setDrainPaused(true);
        for (int block = 0; block < CaptureRing.MIN_SLOTS; block++) {
            publish(1916 + (long) block * BLOCK, Kind.DELIVERED);
        }
        for (int dropped = 0; dropped < 3; dropped++) {
            assertThat(ring.claim()).isNull();
        }
        service.setDrainPaused(false);
        service.awaitFlushed(GUARD);

        stopAndRead();
        assertThat(service.fileOverflowBlocks()).as("ruling E104: three blocks of 8 from 1980 reach punch-in at 2000")
                .isEqualTo(3);
    }

    @Test
    void anInputEndedBeforePunchInAndDeclaredAfterPunchOutIsFlaggedFromPunchInAgainstTheFile() throws IOException {
        punchIn = 1012;
        punchOut = 1044;
        start(16);
        feed(984, Kind.DELIVERED);
        feed(992, Kind.DELIVERED);
        feed(1000, Kind.STARVED);
        feed(1008, Kind.STARVED);
        feed(1016, Kind.STARVED);
        feed(1024, Kind.STARVED);
        feed(1032, Kind.STARVED);
        feed(1040, Kind.STARVED);
        feed(1048, Kind.ENDED);

        TakeManifest manifest = stopAndRead();
        long fileFrames = manifest.segmentsFor(capture.trackId()).stream()
                .mapToLong(TakeManifest.SegmentEntry::frames).sum();
        assertThat(fileFrames).as("the file holds punch-in to punch-out").isEqualTo(punchOut - punchIn);
        assertThat(manifest.inputStops()).as("flagged from the first frame the file holds")
                .containsExactly(new InputStopped(capture.trackId(), punchIn, punchIn));
        assertThat(manifest.inputSilences()).as("the stop covers the run (E49)").isEmpty();
        assertThat(service.starvedInputBlocks()).as("the take figures: starved blocks").isZero();
        assertThat(service.starvedInputFrames()).isZero();
        assertThat(service.fileOverflowBlocks()).isZero();
        assertThat(warnings).containsExactly(
                "Track 'Take': input from 'Interface' delivered no audio; the track records silence.");
    }

    private void start(int slots) {
        CaptureFlushService.TakeConfig config = new CaptureFlushService.TakeConfig(takeDir, MONO, 120.0,
                0.0, 0L, null, false, Duration.ofSeconds(5), Instant.parse("2026-11-16T10:00:00Z"));
        DiskHeadroomWatch watch = new DiskHeadroomWatch(takeDir, () -> 10 * GIB, GIB, 64L << 20,
                Duration.ZERO, clock::get, warnings::add);
        ring = new CaptureRing(BLOCK, 1, 1, slots);
        Track track = new Track("Take", TrackType.AUDIO);
        capture = new TrackCapture(track, new InputRouting(0, 1), -1, 1, BLOCK, 0,
                MONO.sampleRate(), 120.0, takeDir.resolve(track.getId()),
                (t, dir) -> new RecordingSession(MONO, dir));
        capture.setInputSource(0, "Interface");
        service = new CaptureFlushService(ring, config, List.of(capture), watch, warnings::add, clock::get);
        service.start();
        awaitWithinTheGuard(service.readiness(), "the take's readiness");
    }

    private void feed(long start, Kind kind) {
        publish(start, kind);
        service.awaitFlushed(GUARD);
    }

    private void publish(long start, Kind kind) {
        CaptureRing.Slot slot = ring.claim();
        assertThat(slot).as("fixture: the ring has room").isNotNull();
        slot.setNumFrames(BLOCK);
        slot.setTruncatedFrames(0);
        slot.setStartFrame(start);
        slot.setBeatPosition(start / 24_000.0);
        slot.setPunchEnabled(true);
        slot.setPunchStartFrames(punchIn);
        slot.setPunchEndFrames(punchOut);
        slot.setLoopEnabled(false);
        slot.setLoopStartFrame(0);
        slot.setLoopEndFrame(0);
        float[][] rows = new float[1][BLOCK];
        switch (kind) {
            case DELIVERED -> {
                Arrays.fill(rows[0], .5f);
                slot.copySource(0, rows, 1, BLOCK);
            }
            case STARVED -> {
                slot.copySource(0, rows, 1, BLOCK);
                slot.setInputStarved(true);
            }
            case ENDED -> {
                slot.copySource(0, new float[0][], 0, BLOCK);
                slot.setOutputClockFrame(CaptureRing.NO_OUTPUT_CLOCK_FRAME);
            }
        }
        ring.publish();
        service.signal();
    }

    private TakeManifest stopAndRead() throws IOException {
        service.requestStop(SealedBy.STOP);
        awaitTermination(service);
        return TakeManifest.read(TakeManifest.manifestPath(takeDir));
    }
}
