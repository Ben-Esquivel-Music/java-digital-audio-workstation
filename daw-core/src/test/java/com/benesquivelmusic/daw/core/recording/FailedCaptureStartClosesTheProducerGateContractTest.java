package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.awaitWithinTheGuard;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.BLOCK_FRAMES;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.MONO_16;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.SAMPLE_RATE;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.outcomeWithinTheGuard;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.rampBlock;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A start that fails closes the producer gate of the take it rolls back
 * (Recording Reliability book §5.2): removing the recording callback does
 * not stop a callback the audio thread has already loaded, so it is the
 * gate that keeps such a callback from publishing into the ring of a take
 * that is being discarded. Every rollback closes it — a {@code prepare()}
 * that failed after it allocated the ring, a {@code beginCapture()} that
 * failed — and the rollback of {@code beginCapture()} closes it first,
 * before it removes the callback and before it stops the transport.
 */
class FailedCaptureStartClosesTheProducerGateContractTest {

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
        // Headroom that never warns, whatever the machine's disk holds.
        created.setDiskHeadroomWatch(new DiskHeadroomWatch(takeDir, () -> 10 * GIB, GIB, 64 * MIB,
                Duration.ZERO, System::nanoTime, warnings::add));
        created.setWarningSink(warnings::add);
        return created;
    }

    @Test
    void aCallbackLoadedBeforeAFailedStartWasRolledBackPublishesNothing() throws Exception {
        AudioEngine engine = new AudioEngine(MONO_16);
        Transport transport = new Transport();
        transport.setTempo(120.0);
        Track track = RampCaptureTestSupport.armedMonoTrack("Vocal");
        pipeline = newPipeline(engine, transport, track);
        awaitWithinTheGuard(pipeline.prepare(), "the take's readiness");
        CaptureRing ring = pipeline.getCaptureRing();
        CaptureFlushService service = pipeline.getCaptureFlushService();
        assertThat(ring.isProducerClosed()).as("fixture: the prepared take's gate is open").isFalse();
        // The callback the audio thread loaded while beginCapture() had it
        // installed: the take's own ring and flush service, as beginCapture() wires them.
        CaptureCallback loaded = new CaptureCallback(ring, service, new Track[0], transport, engine,
                SAMPLE_RATE, 120.0);
        engine.shutdown(); // the engine start inside beginCapture() throws

        Throwable thrown = outcomeWithinTheGuard("begin capture", pipeline::beginCapture);

        assertThat(thrown).as("fixture: beginCapture() failed and rolled back")
                .isInstanceOf(IllegalStateException.class);
        assertThat(engine.getRecordingCallback()).as("fixture: the rollback removed the callback").isNull();
        assertThat(ring.isProducerClosed()).as("the rollback closed the gate of the take it discards").isTrue();

        loaded.onAudioCaptured(rampBlock(0), BLOCK_FRAMES);

        assertThat(ring.publishedBlocks()).as("the callback that outlived the rollback published nothing").isZero();
        assertThat(loaded.fillBlock(rampBlock(0), BLOCK_FRAMES))
                .as("a callback that enters the closed gate claims and fills nothing").isFalse();
        loaded.publishAndLeave(false);
        awaitWithinTheGuard(service.termination(), "the capture-flush thread's termination");
    }

    @Test
    void theRollbackOfAFailedBeginCaptureClosesTheGateBeforeItTouchesTheCallbackOrTheTransport() throws Exception {
        AudioEngine engine = new AudioEngine(MONO_16);
        Transport transport = new Transport();
        Track track = RampCaptureTestSupport.armedMonoTrack("Vocal");
        pipeline = newPipeline(engine, transport, track);
        awaitWithinTheGuard(pipeline.prepare(), "the take's readiness");
        CaptureRing ring = pipeline.getCaptureRing();

        // The transport tells its listeners from inside record() and stop(),
        // on the calling thread. The first throws when the transport starts
        // recording — beginCapture()'s last step fails with the transport
        // left recording — and the rollback's own transport stop is then
        // where the gate is looked at, and where the callback the audio
        // thread still holds runs once more.
        AtomicBoolean failTheRecord = new AtomicBoolean(true);
        List<Boolean> closedWhenTheRollbackStoppedTheTransport = new CopyOnWriteArrayList<>();
        List<Boolean> callbackStillInstalledThen = new CopyOnWriteArrayList<>();
        AudioEngine.RecordingCallback[] loaded = new AudioEngine.RecordingCallback[1];
        Runnable removeListener = transport.addChangeListener(kind -> {
            if (kind != Transport.ChangeKind.STATE) {
                return;
            }
            if (transport.getState() == TransportState.RECORDING && failTheRecord.compareAndSet(true, false)) {
                loaded[0] = engine.getRecordingCallback();
                throw new IllegalStateException("injected: the transport could not start recording");
            }
            if (transport.getState() == TransportState.STOPPED && loaded[0] != null) {
                closedWhenTheRollbackStoppedTheTransport.add(ring.isProducerClosed());
                callbackStillInstalledThen.add(engine.getRecordingCallback() != null);
                loaded[0].onAudioCaptured(rampBlock(0), BLOCK_FRAMES);
            }
        });
        Throwable thrown;
        try {
            thrown = outcomeWithinTheGuard("begin capture", pipeline::beginCapture);
        } finally {
            removeListener.run();
        }

        assertThat(thrown).as("fixture: beginCapture() failed at the transport")
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("injected");
        assertThat(loaded[0]).as("fixture: the callback was installed when the step failed")
                .isInstanceOf(CaptureCallback.class);
        assertThat(closedWhenTheRollbackStoppedTheTransport)
                .as("the rollback stopped the transport once, with the gate already closed").containsExactly(true);
        assertThat(callbackStillInstalledThen).as("and with the callback already removed").containsExactly(false);
        assertThat(ring.publishedBlocks()).as("the callback that ran inside the rollback published nothing").isZero();
        assertThat(pipeline.isActive()).isFalse();
        awaitWithinTheGuard(pipeline.termination(), "the capture-flush thread's termination");
    }

    @Test
    void aPrepareThatFailsAfterItAllocatedTheRingClosesThatRingsGate() throws Exception {
        AudioEngine engine = new AudioEngine(MONO_16);
        Transport transport = new Transport();
        Track track = RampCaptureTestSupport.armedMonoTrack("Vocal");
        pipeline = newPipeline(engine, transport, track);
        // The ring is allocated before the captures are built; the factory fails after it.
        pipeline.setSessionFactory((armed, directory) -> {
            throw new IllegalStateException("injected factory failure");
        });

        Throwable thrown = outcomeWithinTheGuard("prepare", pipeline::prepare);

        assertThat(thrown).as("fixture: prepare() failed and rolled back")
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("injected factory failure");
        CaptureRing ring = pipeline.getCaptureRing();
        assertThat(ring).as("fixture: the failed prepare had allocated the take's ring, which stays reachable")
                .isNotNull();
        assertThat(ring.isProducerClosed()).as("the rollback closed the gate of the ring it leaves behind").isTrue();
        assertThat(ring.enterProducer()).as("a callback that enters it claims nothing").isFalse();
        ring.exitProducer();
        assertThat(pipeline.isPreparing()).isFalse();
    }
}
