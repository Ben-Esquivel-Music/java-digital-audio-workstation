package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.CountInSchedule;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.awaitWithinTheGuard;
import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.stopRecording;
import static com.benesquivelmusic.daw.core.recording.RecordedAudioTestSupport.audioOnDisk;
import static com.benesquivelmusic.daw.core.recording.RecordedAudioTestSupport.decoded16;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A take that counts in (story 328, Recording Reliability book §5.6):
 * {@link RecordingPipeline#beginCapture(Runnable, CountInSchedule)} installs
 * the schedule instead of recording at once; the render thread's record
 * start at the count-in's last block boundary puts the transport in
 * recording and opens the capture start gate, so the first captured frame is
 * the record position's and nothing of the count-in is in the take; the
 * caller's activation is never run by the pipeline; a halt, a stop or a
 * rolling transport cancel or refuse the count-in and the record start never
 * runs.
 */
@ExtendWith(CaptureFlushThreadLeakGuard.class)
class RecordingPipelineCountInTest {

    private static final int BLOCK = 512;
    private static final double RATE = 44_100.0;
    private static final double RECORD_POSITION = 2.0;
    private static final float COUNT_IN_LEVEL = 0.25f;
    private static final float BAR_ONE_LEVEL = 0.5f;

    @TempDir
    Path tempDir;

    private AudioEngine audioEngine;
    private Transport transport;
    private AudioFormat format;
    private Track track;
    private RecordingPipeline pipeline;
    private final AtomicInteger activations = new AtomicInteger();

    @BeforeEach
    void setUp() {
        format = new AudioFormat(RATE, 2, 16, BLOCK);
        audioEngine = new AudioEngine(format);
        transport = new Transport();
        transport.setTempo(120);
        transport.setPositionInBeats(RECORD_POSITION);
        track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        pipeline = new RecordingPipeline(audioEngine, transport, format, tempDir, List.of(track));
    }

    private CountInSchedule twoBars() {
        return CountInSchedule.forMode(CountInMode.TWO_BARS, 4, 120.0, RATE, BLOCK, 0).orElseThrow();
    }

    private void beginWithCountIn(CountInSchedule schedule) {
        awaitWithinTheGuard(pipeline.prepare(audioEngine.getCaptureHaltGeneration()), "the take's readiness");
        pipeline.beginCapture(activations::incrementAndGet, schedule);
    }

    private void renderBlock(float level) {
        float[][] input = new float[2][BLOCK];
        for (float[] channel : input) {
            Arrays.fill(channel, level);
        }
        audioEngine.processBlock(input, new float[2][BLOCK], BLOCK);
    }

    /** What the engine's render pipeline does after a recorded block when it drives the transport. */
    private void advanceTransportByOneBlock() {
        double samplesPerBeat = RATE * 60.0 / transport.getTempo();
        transport.advancePosition(BLOCK / samplesPerBeat);
    }

    @Test
    void captureBeginsAtTheRecordPositionWhenTheCountInEndsAndHoldsNothingOfTheCountIn() {
        CountInSchedule schedule = twoBars();
        int countInBlocks = (int) (schedule.totalFrames() / BLOCK);
        assertThat(schedule.totalFrames() % BLOCK).isZero();
        beginWithCountIn(schedule);

        assertThat(pipeline.isActive()).isTrue();
        assertThat(audioEngine.isRunning()).isTrue();
        assertThat(audioEngine.getRecordingCallback()).isNotNull();
        assertThat(transport.getState()).as("the transport holds through the count-in").isEqualTo(TransportState.STOPPED);
        assertThat(activations).as("the activation is the caller's to run after the fire").hasValue(0);
        assertThat(pipeline.countInSchedule()).contains(schedule);
        assertThat(audioEngine.pendingCountIn()).contains(schedule);
        assertThat(schedule.hasRecordStart()).isTrue();

        for (int block = 0; block < countInBlocks - 1; block++) {
            renderBlock(COUNT_IN_LEVEL);
            assertThat(transport.getState()).as("block %d", block).isEqualTo(TransportState.STOPPED);
        }
        assertThat(pipeline.getCaptureRing().publishedBlocks()).as("nothing is captured while counting in").isZero();
        renderBlock(COUNT_IN_LEVEL);
        assertThat(transport.getState()).as("the last count-in block's end is the record start")
                .isEqualTo(TransportState.RECORDING);
        assertThat(schedule.phase()).isEqualTo(CountInSchedule.Phase.FIRED);
        assertThat(pipeline.getCaptureRing().publishedBlocks()).as("the last count-in block is not captured").isZero();
        assertThat(transport.getPositionInBeats()).isEqualTo(RECORD_POSITION);
        assertThat(activations).hasValue(0);

        for (int block = 0; block < 3; block++) {
            renderBlock(BAR_ONE_LEVEL);
            advanceTransportByOneBlock();
        }
        assertThat(pipeline.getCaptureRing().publishedBlocks()).isEqualTo(3L);

        List<AudioClip> clips = stopRecording(pipeline);
        assertThat(clips).hasSize(1);
        AudioClip clip = clips.getFirst();
        assertThat(clip.getStartBeat()).as("the take starts at the record position").isEqualTo(RECORD_POSITION);
        float[][] audio = audioOnDisk(clip);
        assertThat(audio[0]).hasSize(3 * BLOCK);
        assertThat(audio[0]).containsOnly(decoded16(BAR_ONE_LEVEL));
        assertThat(audio[0]).doesNotContain(decoded16(COUNT_IN_LEVEL));
    }

    @Test
    void aHaltDuringTheCountInCancelsItSoTheRecordStartNeverRunsOnTheHaltedTake() {
        CountInSchedule schedule = twoBars();
        int countInBlocks = (int) (schedule.totalFrames() / BLOCK);
        beginWithCountIn(schedule);
        for (int block = 0; block < 10; block++) {
            renderBlock(COUNT_IN_LEVEL);
        }

        audioEngine.haltRecordingCapture();

        assertThat(schedule.phase()).isEqualTo(CountInSchedule.Phase.CANCELLED);
        assertThat(audioEngine.pendingCountIn()).isEmpty();
        assertThat(pipeline.getCaptureRing().isProducerClosed()).isTrue();
        for (int block = 10; block < countInBlocks + 5; block++) {
            renderBlock(COUNT_IN_LEVEL);
        }
        assertThat(transport.getState()).isEqualTo(TransportState.STOPPED);
        assertThat(schedule.isFired()).isFalse();
        assertThat(activations).hasValue(0);
        assertThat(pipeline.getCaptureRing().publishedBlocks()).isZero();

        // The loss's own handling ends the take through the ordinary path.
        awaitWithinTheGuard(pipeline.requestStop(TakeManifest.SealedBy.DEVICE_LOSS), "the rescued take's termination");
        assertThat(pipeline.completeStop()).isEmpty();
        assertThat(transport.getState()).isEqualTo(TransportState.STOPPED);
    }

    @Test
    void aStopDuringTheCountInCancelsItAndTheTakeNeverRecorded() {
        CountInSchedule schedule = twoBars();
        int countInBlocks = (int) (schedule.totalFrames() / BLOCK);
        beginWithCountIn(schedule);
        for (int block = 0; block < 10; block++) {
            renderBlock(COUNT_IN_LEVEL);
        }

        stopRecording(pipeline);

        assertThat(schedule.phase()).isEqualTo(CountInSchedule.Phase.CANCELLED);
        assertThat(audioEngine.pendingCountIn()).isEmpty();
        assertThat(audioEngine.getRecordingCallback()).isNull();
        for (int block = 10; block < countInBlocks + 5; block++) {
            renderBlock(COUNT_IN_LEVEL);
        }
        assertThat(transport.getState()).isEqualTo(TransportState.STOPPED);
        assertThat(schedule.isFired()).isFalse();
        assertThat(pipeline.getCaptureRing().publishedBlocks()).isZero();
        assertThat(pipeline.isActive()).isFalse();
    }

    @Test
    void aRollingTransportRefusesTheCountInAndTheStartIsRolledBack() {
        transport.play();
        CountInSchedule schedule = twoBars();
        awaitWithinTheGuard(pipeline.prepare(audioEngine.getCaptureHaltGeneration()), "the take's readiness");

        assertThatThrownBy(() -> pipeline.beginCapture(activations::incrementAndGet, schedule))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("rolling");

        assertThat(pipeline.isActive()).isFalse();
        assertThat(pipeline.isPreparing()).isFalse();
        assertThat(audioEngine.getRecordingCallback()).isNull();
        assertThat(schedule.phase()).isEqualTo(CountInSchedule.Phase.CANCELLED);
        assertThat(audioEngine.pendingCountIn()).isEmpty();
        assertThat(track.isRecording()).isFalse();
        assertThat(activations).hasValue(0);
    }

    @Test
    void aSpentScheduleOrOneWithARecordStartAlreadySetIsRefusedBeforeAnythingIsTouched() {
        awaitWithinTheGuard(pipeline.prepare(audioEngine.getCaptureHaltGeneration()), "the take's readiness");
        CountInSchedule cancelled = twoBars();
        cancelled.cancel();
        assertThatThrownBy(() -> pipeline.beginCapture(() -> { }, cancelled))
                .isInstanceOf(IllegalArgumentException.class);
        CountInSchedule preset = twoBars();
        preset.setRecordStart(() -> { });
        assertThatThrownBy(() -> pipeline.beginCapture(() -> { }, preset))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(pipeline.isPreparing()).as("the take is still being prepared").isTrue();
        assertThat(audioEngine.getRecordingCallback()).isNull();
        awaitWithinTheGuard(pipeline.cancelStart(), "the cancelled start's termination");
    }
}
