package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transport's lap the recording callbacks stamp on a take's slots on the
 * render thread (story 338, rulings E186 and E198): the stream's own blocks
 * and the output-clocked blocks of an input opened beside it carry it, counted
 * once per wrap by whichever of the take's callbacks sees the wrap first,
 * before the ring is asked for a slot, from the start gate's beat while the
 * gate is the take's origin; a block an input beside the stream delivers on
 * its own thread carries none. The test is the ring's consumer; the flush
 * service is never started.
 */
class CaptureCallbackTransportLapTest {

    private static final int BLOCK = 8;
    private static final double SAMPLE_RATE = 48_000.0;
    private static final double TEMPO = 120.0;
    private static final AudioFormat MONO = new AudioFormat(SAMPLE_RATE, 1, 16, BLOCK);
    private static final double LOOP_START_BEAT = 0.0;
    private static final double LOOP_END_BEAT = 4.0;
    private static final long GIB = 1L << 30;

    @TempDir
    Path takeDir;

    private final Transport transport = new Transport();
    private final AudioEngine engine = new AudioEngine(MONO);
    private final CaptureCallback.StartGate gate = new CaptureCallback.StartGate();
    private final float[][] delivered = new float[1][BLOCK];
    private final float[][] clocked = new float[0][];

    @Test
    void consecutiveStreamBlocksCarryTheLapTheyWereRenderedIn() {
        CaptureRing ring = ring(16);
        CaptureCallback stream = streamCallback(ring);
        openTheGateAt(2.0);

        List<Long> laps = new ArrayList<>();
        for (double beat : new double[] {2.0, 3.0, 0.0, 1.0, 2.0, 3.0, 0.0}) {
            transport.setPositionInBeats(beat);
            stream.onAudioCaptured(delivered, BLOCK);
            laps.add(drainOne(ring));
        }

        assertThat(laps).as("lap 1 until the first step back, then one more lap at each").containsExactly(
                0L, 0L, 1L, 1L, 1L, 1L, 2L);
    }

    @ParameterizedTest(name = "the input beside the stream first: {0}")
    @ValueSource(booleans = {true, false})
    void theClockedBlockOfTheWrapPeriodAndTheStreamsBlockCountTheWrapOnce(boolean besideFirst) {
        CaptureRing streamRing = ring(16);
        CaptureRing besideRing = ring(16);
        CaptureCallback stream = streamCallback(streamRing);
        CaptureCallback beside = besideCallback(besideRing);
        openTheGateAt(3.0);

        List<Long> streamLaps = new ArrayList<>();
        List<Long> besideLaps = new ArrayList<>();
        for (double beat : new double[] {3.0, 0.0, 1.0}) {
            transport.setPositionInBeats(beat);
            if (besideFirst) {
                beside.onAudioCaptured(clocked, BLOCK);
                stream.onAudioCaptured(delivered, BLOCK);
            } else {
                stream.onAudioCaptured(delivered, BLOCK);
                beside.onAudioCaptured(clocked, BLOCK);
            }
            streamLaps.add(drainOne(streamRing));
            besideLaps.add(drainOne(besideRing));
        }

        assertThat(besideLaps).as("the clocked blocks carry the transport's lap").containsExactly(0L, 1L, 1L);
        assertThat(streamLaps).as("the second callback of the wrap period sees the same beat: one wrap")
                .containsExactly(0L, 1L, 1L);
    }

    @Test
    void aWrapWhileTheRingIsFullIsStillCounted() {
        CaptureRing ring = ring(CaptureRing.MIN_SLOTS);
        CaptureCallback stream = streamCallback(ring);
        double keptBeat = 3.5;
        double lastKeptBeat = 0.0;
        openTheGateAt(0.0);
        for (int block = 0; block < CaptureRing.MIN_SLOTS; block++) {
            lastKeptBeat = block * keptBeat / CaptureRing.MIN_SLOTS;
            transport.setPositionInBeats(lastKeptBeat);
            stream.onAudioCaptured(delivered, BLOCK);
        }
        assertThat(ring.publishedBlocks()).as("premise: the ring is full").isEqualTo(CaptureRing.MIN_SLOTS);

        for (double beat : new double[] {0.0, 1.0, 2.0, 3.0}) {
            transport.setPositionInBeats(beat);
            stream.onAudioCaptured(delivered, BLOCK);
        }
        assertThat(ring.overflowCount()).as("premise: every block of the lap that followed was dropped, the wrap"
                + " at its first included").isEqualTo(4);
        for (int block = 0; block < CaptureRing.MIN_SLOTS; block++) {
            assertThat(drainOne(ring)).isZero();
        }
        assertThat(keptBeat).as("premise: the next block kept is at a beat no lower than the last one kept, so only"
                + " the dropped wrap can put it in lap 2").isGreaterThanOrEqualTo(lastKeptBeat);
        transport.setPositionInBeats(keptBeat);
        stream.onAudioCaptured(delivered, BLOCK);

        assertThat(drainOne(ring)).as("the next block kept, at a beat no lower than the last one kept, is in lap 2")
                .isEqualTo(1L);
    }

    /**
     * Record pressed while the transport rolls: the start gate's beat is
     * stored just before the loop end, and the transport wraps before the
     * first gated block. The take's rings start at the gate's beat, so the
     * flush thread counts that wrap, and the render thread counts it too.
     */
    @Test
    void aWrapBetweenTheGatesBeatAndTheFirstGatedBlockIsCountedOnThatBlock() {
        CaptureRing ring = ring(16);
        CaptureCallback stream = streamCallback(ring);
        openTheGateAt(3.9);
        transport.record();
        transport.advancePosition(0.2);
        assertThat(transport.getPositionInBeats()).as("premise: the transport wrapped after the gate's beat")
                .isLessThan(1.0);

        stream.onAudioCaptured(delivered, BLOCK);

        assertThat(ring.peek().beatPosition()).as("premise: the block starts from the gate's beat").isEqualTo(3.9);
        assertThat(drainOne(ring)).as("the wrap after the gate's beat: lap 2").isEqualTo(1L);
    }

    /**
     * Ruling E198: an input beside the stream delivers a block on its own
     * thread first, then is clocked in the take's first render period, so its
     * clocked block is the take's first counted one. The count still starts
     * from the start gate's beat, where the rings start, so the wrap between
     * the gate's opening and that period is in both blocks of the period.
     */
    @Test
    void aWrapBeforeTheFirstCountedBlockIsCountedWhenThatBlockIsTheClockedBlockOfAnInputThatDeliveredFirst() {
        CaptureRing streamRing = ring(16);
        CaptureRing besideRing = ring(16);
        CaptureCallback stream = streamCallback(streamRing);
        CaptureCallback beside = besideCallback(besideRing);
        openTheGateAt(3.9);
        beside.onAudioCaptured(delivered, BLOCK);
        transport.record();
        transport.advancePosition(0.2);
        assertThat(transport.getPositionInBeats()).as("premise: the transport wrapped after the gate's beat")
                .isLessThan(1.0);

        beside.onAudioCaptured(clocked, BLOCK);
        stream.onAudioCaptured(delivered, BLOCK);

        assertThat(drainOne(besideRing)).as("premise: the block it delivered on its own thread carries no lap")
                .isEqualTo(CaptureRing.NO_TRANSPORT_LAP);
        assertThat(drainOne(besideRing)).as("its clocked block, the take's first counted one: lap 2").isEqualTo(1L);
        assertThat(drainOne(streamRing)).as("the stream's block of the same period: the same wrap, once")
                .isEqualTo(1L);
    }

    @Test
    void aFirstGatedBlockAtTheGatesBeatIsInLapOneAndTheNextWrapIsCountedOnce() {
        CaptureRing ring = ring(16);
        CaptureCallback stream = streamCallback(ring);
        openTheGateAt(3.9);
        transport.record();

        stream.onAudioCaptured(delivered, BLOCK);
        transport.advancePosition(0.2);
        stream.onAudioCaptured(delivered, BLOCK);

        assertThat(ring.peek().beatPosition()).as("premise: the block starts from the gate's beat").isEqualTo(3.9);
        assertThat(drainOne(ring)).as("at the gate's beat: no wrap").isZero();
        assertThat(drainOne(ring)).isEqualTo(1L);
    }

    @Test
    void anInputBesideTheStreamFirstClockedAfterTheStreamCountedAWrapDoesNotCountItAgain() {
        CaptureRing streamRing = ring(16);
        CaptureRing besideRing = ring(16);
        CaptureCallback stream = streamCallback(streamRing);
        CaptureCallback beside = besideCallback(besideRing);
        openTheGateAt(3.0);
        transport.record();
        stream.onAudioCaptured(delivered, BLOCK);
        transport.advancePosition(1.5);

        stream.onAudioCaptured(delivered, BLOCK);
        beside.onAudioCaptured(clocked, BLOCK);

        assertThat(drainOne(streamRing)).isZero();
        assertThat(drainOne(streamRing)).isEqualTo(1L);
        assertThat(besideRing.peek().beatPosition()).as("premise: its first block starts from the gate's beat")
                .isEqualTo(3.0);
        assertThat(drainOne(besideRing)).as("the wrap the stream counted, once").isEqualTo(1L);
    }

    @Test
    void aTakeWithNoStartGateStartsInLapOne() {
        CaptureRing ring = ring(16);
        CaptureCallback stream = new CaptureCallback(ring, unstartedFlushService(ring), new Track[0], transport,
                engine, SAMPLE_RATE, SAMPLE_RATE, TEMPO);
        transport.setLoopWindow(true, LOOP_START_BEAT, LOOP_END_BEAT);
        transport.setPositionInBeats(3.9);
        transport.record();
        transport.advancePosition(0.2);

        stream.onAudioCaptured(delivered, BLOCK);

        assertThat(drainOne(ring)).as("no origin before the first block: no wrap before it").isZero();
    }

    @Test
    void aStartGateWhoseOriginASeekSupersededCountsNoWrapBeforeTheFirstBlock() {
        CaptureRing ring = ring(16);
        CaptureCallback stream = streamCallback(ring);
        openTheGateAt(3.9);
        transport.setPositionInBeats(0.1);
        transport.record();

        stream.onAudioCaptured(delivered, BLOCK);

        assertThat(ring.peek().beatPosition()).as("premise: the seek, not the gate's beat, is the origin")
                .isEqualTo(0.1);
        assertThat(drainOne(ring)).as("the take starts at the seek's target: no wrap before it").isZero();
    }

    @Test
    void aBlockTheInputBesideTheStreamDeliversCarriesNoLapAndCountsNone() {
        CaptureRing streamRing = ring(16);
        CaptureRing besideRing = ring(16);
        CaptureCallback stream = streamCallback(streamRing);
        CaptureCallback beside = besideCallback(besideRing);
        openTheGateAt(3.0);
        transport.setPositionInBeats(3.0);
        stream.onAudioCaptured(delivered, BLOCK);

        transport.setPositionInBeats(0.0);
        beside.onAudioCaptured(delivered, BLOCK);

        assertThat(drainOne(besideRing)).as("its own publisher's thread does not know the transport's lap")
                .isEqualTo(CaptureRing.NO_TRANSPORT_LAP);
        stream.onAudioCaptured(delivered, BLOCK);
        assertThat(drainOne(streamRing)).isZero();
        assertThat(drainOne(streamRing)).as("the wrap is the stream's to count, once").isEqualTo(1L);
    }

    private void openTheGateAt(double beat) {
        transport.setLoopWindow(true, LOOP_START_BEAT, LOOP_END_BEAT);
        transport.setPositionInBeats(beat);
        gate.beat = beat;
        gate.positionSeek = transport.getPositionSeek();
        gate.seekPending = false;
        gate.active = true;
    }

    private CaptureRing ring(int slots) {
        return new CaptureRing(BLOCK, 1, 1, slots);
    }

    private CaptureCallback streamCallback(CaptureRing ring) {
        return new CaptureCallback(ring, unstartedFlushService(ring), new Track[0], transport, engine,
                SAMPLE_RATE, SAMPLE_RATE, TEMPO, gate);
    }

    private CaptureCallback besideCallback(CaptureRing ring) {
        CaptureCallback callback = new CaptureCallback(ring, unstartedFlushService(ring), new Track[0], transport,
                engine, SAMPLE_RATE, SAMPLE_RATE, TEMPO, gate);
        callback.useIndependentFrameCursor();
        return callback;
    }

    /** The lap of the ring's oldest published block, which is then released. */
    private static long drainOne(CaptureRing ring) {
        CaptureRing.Slot slot = ring.peek();
        assertThat(slot).as("fixture: a block was published").isNotNull();
        long lap = slot.transportLap();
        ring.release();
        return lap;
    }

    private CaptureFlushService unstartedFlushService(CaptureRing ring) {
        CaptureFlushService.TakeConfig config = new CaptureFlushService.TakeConfig(takeDir, MONO, TEMPO, 0.0, 0L,
                null, false, Duration.ofSeconds(5), Instant.parse("2026-11-22T10:00:00Z"));
        DiskHeadroomWatch watch = new DiskHeadroomWatch(takeDir, () -> 10 * GIB, GIB, 64L << 20, Duration.ZERO,
                System::nanoTime, warning -> { });
        Track track = new Track("Take", TrackType.AUDIO);
        TrackCapture capture = new TrackCapture(track, new InputRouting(0, 1), -1, 1, BLOCK, 0, SAMPLE_RATE, TEMPO,
                takeDir.resolve(track.getId()), (t, dir) -> new RecordingSession(MONO, dir));
        return new CaptureFlushService(ring, config, List.of(capture), watch, warning -> { }, System::nanoTime);
    }
}
