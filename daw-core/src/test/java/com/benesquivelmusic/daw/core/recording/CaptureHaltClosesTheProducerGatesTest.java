package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.awaitWithinTheGuard;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.BLOCK_FRAMES;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.MONO_16;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.SAMPLE_RATE;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.rampBlock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The engine's capture halt (Recording Reliability book §5.5, §6.1; story
 * 327, PR #982 review): {@link AudioEngine#haltRecordingCapture()} tells
 * every installed recording callback — the take's and each sibling input's
 * — that its capture has ended, and the pipeline's callback answers by
 * closing its ring's producer gate ({@link CaptureCallback#onCaptureHalted()}),
 * before both callback slots are emptied. It is the loss path's fence
 * against a stream reopened before the pipeline's own stop has run on the
 * FX thread: a halted callback that is run once more publishes nothing, a
 * second halt finds nothing to halt, and a halt while nothing records
 * touches nothing.
 */
class CaptureHaltClosesTheProducerGatesTest {

    private static final long GIB = 1L << 30;
    private static final long MIB = 1L << 20;

    @TempDir
    Path takeDir;

    private RecordingPipeline pipeline;
    private final List<String> warnings = new CopyOnWriteArrayList<>();

    @AfterEach
    void endTheTake() throws InterruptedException {
        PipelineLifecycleTestSupport.endTheTakeAndAssertItsFlushThreadEnded(pipeline);
    }

    private RecordingPipeline newPipeline(AudioEngine engine, Transport transport, Track track) {
        RecordingPipeline created = new RecordingPipeline(engine, transport, MONO_16, takeDir, List.of(track));
        created.setDiskHeadroomWatch(new DiskHeadroomWatch(takeDir, () -> 10 * GIB, GIB, 64 * MIB,
                Duration.ZERO, System::nanoTime, warnings::add));
        created.setWarningSink(warnings::add);
        return created;
    }

    @Test
    void theHaltClosesTheGateOfEveryInstalledCallbackThenEmptiesBothSlots() throws Exception {
        AudioEngine engine = new AudioEngine(MONO_16);
        Transport transport = new Transport();
        transport.setTempo(120.0);
        Track track = RampCaptureTestSupport.armedMonoTrack("Vocal");
        pipeline = newPipeline(engine, transport, track);
        awaitWithinTheGuard(pipeline.prepare(), "the take's readiness");
        CaptureRing ring = pipeline.getCaptureRing();
        CaptureFlushService service = pipeline.getCaptureFlushService();
        // The take's callback and a sibling input's, as beginCapture() wires
        // them: each carries its own ring.
        CaptureCallback take = new CaptureCallback(ring, service, new Track[0], transport, engine,
                SAMPLE_RATE, SAMPLE_RATE, 120.0);
        CaptureRing siblingRing = new CaptureRing(BLOCK_FRAMES, 1, 1, CaptureRing.MIN_SLOTS);
        CaptureCallback sibling = new CaptureCallback(siblingRing, service, new Track[0], transport, engine,
                SAMPLE_RATE, SAMPLE_RATE, 120.0);
        AtomicInteger haltsSeenByAThirdCallback = new AtomicInteger();
        AudioEngine.RecordingCallback counting = new AudioEngine.RecordingCallback() {
            @Override
            public void onAudioCaptured(float[][] inputBuffer, int numFrames) {
                // Never run here.
            }

            @Override
            public void onCaptureHalted() {
                haltsSeenByAThirdCallback.incrementAndGet();
            }
        };
        engine.setRecordingCallback(take);
        engine.setAdditionalRecordingCallbacks(new AudioEngine.RecordingCallback[] {sibling, counting});
        assertThat(ring.isProducerClosed()).as("premise: the take's gate is open").isFalse();
        assertThat(siblingRing.isProducerClosed()).as("premise: the sibling's gate is open").isFalse();
        assertThat(engine.getRecordingCallback()).as("premise: the take's callback is installed").isSameAs(take);

        engine.haltRecordingCapture();

        assertThat(ring.isProducerClosed()).as("the take's callback closed its ring's gate").isTrue();
        assertThat(siblingRing.isProducerClosed()).as("the sibling input's callback closed its ring's gate").isTrue();
        assertThat(haltsSeenByAThirdCallback).as("every additional callback was told once").hasValue(1);
        assertThat(engine.getRecordingCallback()).as("the recording callback slot is empty").isNull();

        // The callback a running pump had already loaded runs once more and
        // publishes nothing: it enters a closed gate.
        take.onAudioCaptured(rampBlock(0), BLOCK_FRAMES);
        assertThat(ring.publishedBlocks()).as("a halted callback publishes nothing").isZero();

        // A second halt finds both slots empty: nothing is told again. The
        // pipeline's own stop then repeats the gate close harmlessly.
        engine.haltRecordingCapture();
        assertThat(haltsSeenByAThirdCallback).as("the additional callbacks slot was emptied by the first halt")
                .hasValue(1);
        assertThatCode(ring::closeProducer).as("closing a closed gate again is harmless").doesNotThrowAnyException();
        assertThat(ring.isProducerClosed()).isTrue();
    }

    @Test
    void aHaltWhileNothingRecordsTouchesNothing() {
        AudioEngine engine = new AudioEngine(MONO_16);
        assertThat(engine.getRecordingCallback()).as("premise: no callback is installed").isNull();

        assertThatCode(engine::haltRecordingCapture).doesNotThrowAnyException();

        assertThat(engine.getRecordingCallback()).isNull();
    }
}
