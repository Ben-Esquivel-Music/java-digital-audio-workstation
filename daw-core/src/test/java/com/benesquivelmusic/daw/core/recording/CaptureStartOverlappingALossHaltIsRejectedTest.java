package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.awaitWithinTheGuard;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.BLOCK_FRAMES;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.BYTES_PER_FRAME_MONO_16;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.HANG_GUARD;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.MONO_16;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The device-loss fence of a capture start (Recording Reliability book §2.4,
 * §5.5, §6.1; story 327, PR #982 review r4297840180): a
 * {@link RecordingPipeline#beginCapture()} that overlaps the engine's
 * {@link AudioEngine#haltRecordingCapture()} — any halt after the take's
 * baseline of the engine's capture-halt generation was read — is rejected:
 * the ordinary all-or-nothing rollback, gates closed, callbacks removed,
 * rethrown with the message the user reads, so no stream reopened after the
 * loss can feed callbacks the start installed after the halt read the slots.
 *
 * <p>Each halt runs on the acting thread at a fixed point of the start:
 * after the take was prepared and before {@code beginCapture()} (the stream
 * closed and reopened in between, as the loss and a fast return leave it, or
 * left closed); inside the preparation itself (from the session factory,
 * which {@code prepare(long)} runs on the caller thread, after its caller
 * read the baseline); before the preparation, with the baseline read before the halt
 * and handed to {@link RecordingPipeline#prepare(long)}; from inside the
 * capture activation, which runs after both callbacks are installed and
 * before the final check; and with the whole start run from inside the
 * halt's window between reading the callback slots and storing {@code null}
 * (a probe callback's {@code onCaptureHalted()}). A real {@link AudioEngine}
 * renders on a {@link MockAudioBackend}; a running pump is left running
 * until the backend has sunk three more blocks — a premise that reads
 * nothing of the take — and the take's published-block count must not move.
 * The control starts the same take with no halt and sees the running pump
 * publish, so a zero count is not the fixture's. A halt before the baseline
 * was read rejects nothing.</p>
 */
@ExtendWith(CaptureFlushThreadLeakGuard.class)
class CaptureStartOverlappingALossHaltIsRejectedTest {

    private static final int BYTES_PER_SUNK_BLOCK = BLOCK_FRAMES * MONO_16.channels() * BYTES_PER_FRAME_MONO_16;

    @TempDir
    Path takeDir;

    private final MockAudioBackend backend = new MockAudioBackend();
    private final Transport transport = new Transport();
    private final Track track = RampCaptureTestSupport.armedMonoTrack("Vocal");
    /** Every transport state the transport entered from the moment the test began listening. */
    private final List<TransportState> transportStatesEntered = new CopyOnWriteArrayList<>();
    private AudioEngine engine;
    private RecordingPipeline pipeline;

    @AfterEach
    void endTheTakeAndTheStream() throws InterruptedException {
        try {
            PipelineLifecycleTestSupport.endTheTakeAndAssertItsFlushThreadEnded(pipeline);
        } finally {
            if (engine != null) {
                engine.stopAudioOutput();
                engine.shutdown();
            }
        }
    }

    private AudioEngine engineOnTheMockBackend() {
        AudioEngine created = new AudioEngine(MONO_16);
        created.setStreamingProvision(new StreamingProvision(backend.name(),
                List.of(new BackendStreamRung(backend, DeviceId.defaultFor(backend.name())))));
        return created;
    }

    private RecordingPipeline newPipeline() {
        return new RecordingPipeline(engine, transport, MONO_16, takeDir, List.of(track));
    }

    private RecordingPipeline preparedPipeline() {
        RecordingPipeline prepared = newPipeline();
        awaitWithinTheGuard(prepared.prepare(engine.getCaptureHaltGeneration()), "the take's readiness");
        return prepared;
    }

    /** What {@code DefaultAudioEngineController.onDeviceRemoved} does to the engine, in its order. */
    private void loseTheDevice() {
        engine.haltRecordingCapture();
        engine.stopAudioOutput();
        engine.stop();
        assertThat(engine.isStreamOpen()).as("premise: the loss closed the stream").isFalse();
    }

    /** Records every transport state change from now on into {@link #transportStatesEntered}. */
    private void listenToTheTransport() {
        transport.addChangeListener(kind -> {
            if (kind == Transport.ChangeKind.STATE) {
                transportStatesEntered.add(transport.getState());
            }
        });
    }

    /** Leaves the running pump until the backend has sunk {@code blocks} more blocks, then parks it. */
    private void renderAtLeast(int blocks) {
        int target = backend.recordedOutput().length + blocks * BYTES_PER_SUNK_BLOCK;
        await(() -> backend.recordedOutput().length >= target,
                "premise: the running pump rendered at least " + blocks + " more blocks");
        engine.pauseAudioOutput();
    }

    private void assertTheStartWasRolledBack(RecordingPipeline rejected) {
        assertThat(engine.getRecordingCallback()).as("the recording callback slot is empty").isNull();
        assertThat(engine.getAdditionalRecordingCallbacks()).as("the additional callbacks slot is empty").isEmpty();
        assertThat(rejected.isActive()).as("the pipeline is idle again").isFalse();
        assertThat(rejected.isPreparing()).as("the rejected start is not a pending one").isFalse();
        assertThat(rejected.getCaptureRing().isProducerClosed()).as("the take's producer gate is closed").isTrue();
        assertThat(track.isRecording()).as("the recording flag is cleared").isFalse();
        awaitWithinTheGuard(rejected.termination(), "the rejected take's capture-flush thread's termination");
    }

    @Test
    void aHaltBetweenPreparationAndBeginCaptureRejectsTheStartAndTheReopenedPumpPublishesNothing() {
        engine = engineOnTheMockBackend();
        engine.startAudioOutput();
        pipeline = preparedPipeline();
        loseTheDevice();
        // The device returns — or something opens a stream — before the
        // loss reaches the record coordinator, which then begins capture.
        engine.startAudioOutput();
        listenToTheTransport();

        assertThatThrownBy(pipeline::beginCapture)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(RecordingPipeline.DEVICE_LOST_DURING_START_MESSAGE);

        assertTheStartWasRolledBack(pipeline);
        assertThat(transportStatesEntered).as("the rejected start never put the transport in recording")
                .doesNotContain(TransportState.RECORDING);
        assertThat(transport.getState()).as("the transport is stopped").isEqualTo(TransportState.STOPPED);
        renderAtLeast(3);
        assertThat(pipeline.getPublishedBlocks()).as("no block of the reopened stream reached the take").isZero();
    }

    /**
     * The loss leaves the engine stopped and no stream reopens: the start
     * is refused by the first check, before it would start the engine —
     * which the rollback would leave running — or touch the transport.
     */
    @Test
    void aHaltWithNoStreamReopenedRejectsTheStartWithoutStartingTheEngine() {
        engine = engineOnTheMockBackend();
        engine.startAudioOutput();
        pipeline = preparedPipeline();
        loseTheDevice();
        assertThat(engine.isRunning()).as("premise: the loss stopped the engine").isFalse();
        listenToTheTransport();

        assertThatThrownBy(pipeline::beginCapture)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(RecordingPipeline.DEVICE_LOST_DURING_START_MESSAGE);

        assertTheStartWasRolledBack(pipeline);
        assertThat(engine.isRunning()).as("the rejected start did not start the engine").isFalse();
        assertThat(transportStatesEntered).as("the rejected start never touched the transport's state").isEmpty();
    }

    @Test
    void aHaltRunFromTheCaptureActivationRejectsTheStartWithItsGatesClosed() {
        engine = engineOnTheMockBackend();
        engine.startAudioOutput();
        pipeline = preparedPipeline();
        AtomicReference<AudioEngine.RecordingCallback> installedAtTheHalt = new AtomicReference<>();
        AtomicReference<TransportState> transportAtTheHalt = new AtomicReference<>();

        assertThatThrownBy(() -> pipeline.beginCapture(() -> {
            installedAtTheHalt.set(engine.getRecordingCallback());
            transportAtTheHalt.set(transport.getState());
            engine.haltRecordingCapture();
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(RecordingPipeline.DEVICE_LOST_DURING_START_MESSAGE);

        assertThat(installedAtTheHalt.get()).as("premise: the halt ran with the take's own callback installed")
                .isInstanceOf(CaptureCallback.class);
        assertThat(transportAtTheHalt.get()).as("premise: the halt ran after the record transition")
                .isEqualTo(TransportState.RECORDING);
        assertTheStartWasRolledBack(pipeline);
        assertThat(transport.getState()).as("the rollback stopped the transport").isEqualTo(TransportState.STOPPED);
        // The stream opened before the start is still running.
        renderAtLeast(3);
        assertThat(pipeline.getPublishedBlocks()).as("the rejected start published nothing").isZero();
    }

    /**
     * The whole start runs inside the halt's window between reading the
     * callback slots and storing {@code null}: a probe callback installed
     * before the halt begins capture from its {@code onCaptureHalted()},
     * which the halt calls after it has read both slots. The start must see
     * the halt — the halt advanced the generation before it read the slots
     * — or the halt's {@code null} stores would remove the callbacks the
     * start installed, their start gate open.
     */
    @Test
    void aStartRunInsideTheHaltsWindowBetweenReadingAndEmptyingTheSlotsIsRejected() {
        engine = engineOnTheMockBackend();
        pipeline = preparedPipeline();
        AtomicBoolean probeHalted = new AtomicBoolean();
        AtomicReference<Throwable> startOutcome = new AtomicReference<>();
        engine.setRecordingCallback(new AudioEngine.RecordingCallback() {
            @Override
            public void onAudioCaptured(float[][] inputBuffer, int numFrames) {
            }

            @Override
            public void onCaptureHalted() {
                probeHalted.set(true);
                try {
                    pipeline.beginCapture();
                } catch (RuntimeException | Error thrown) {
                    startOutcome.set(thrown);
                }
            }
        });

        engine.haltRecordingCapture();

        assertThat(probeHalted).as("premise: the halt read the probe and called it").isTrue();
        assertThat(startOutcome.get()).as("the start run inside the halt's window was rejected")
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(RecordingPipeline.DEVICE_LOST_DURING_START_MESSAGE);
        assertTheStartWasRolledBack(pipeline);
    }

    /**
     * The halt runs inside {@code prepare(long)} itself — from the session
     * factory, which the preparation calls on the caller thread after the
     * baseline was read — while the stream opened before the take keeps
     * running: the take's start is rejected and the pump publishes nothing.
     */
    @Test
    void aHaltDuringThePreparationRejectsTheStart() {
        engine = engineOnTheMockBackend();
        engine.startAudioOutput();
        pipeline = newPipeline();
        AtomicInteger haltsInThePreparation = new AtomicInteger();
        pipeline.setSessionFactory((armed, directory) -> {
            engine.haltRecordingCapture();
            haltsInThePreparation.incrementAndGet();
            return new RecordingSession(MONO_16, directory);
        });
        long baselineBefore = engine.getCaptureHaltGeneration();
        awaitWithinTheGuard(pipeline.prepare(engine.getCaptureHaltGeneration()), "the take's readiness");
        assertThat(haltsInThePreparation).as("premise: the halt ran inside the preparation, once").hasValue(1);
        assertThat(engine.getCaptureHaltGeneration()).as("premise: the halt advanced the generation")
                .isEqualTo(baselineBefore + 1);

        assertThatThrownBy(pipeline::beginCapture)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(RecordingPipeline.DEVICE_LOST_DURING_START_MESSAGE);

        assertTheStartWasRolledBack(pipeline);
        renderAtLeast(3);
        assertThat(pipeline.getPublishedBlocks()).as("the running pump published nothing into the take").isZero();
    }

    /**
     * The record coordinator's order: the baseline is read before the take's
     * stream is opened, and handed to {@link RecordingPipeline#prepare(long)};
     * a halt between that read and the preparation rejects the start, though
     * it ran before the take was prepared.
     */
    @Test
    void aBaselineReadBeforeAHaltThatPrecededThePreparationRejectsTheStart() {
        engine = engineOnTheMockBackend();
        engine.startAudioOutput();
        long baseline = engine.getCaptureHaltGeneration();
        loseTheDevice();
        engine.startAudioOutput();
        pipeline = newPipeline();
        awaitWithinTheGuard(pipeline.prepare(baseline), "the take's readiness");

        assertThatThrownBy(pipeline::beginCapture)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(RecordingPipeline.DEVICE_LOST_DURING_START_MESSAGE);

        assertTheStartWasRolledBack(pipeline);
        renderAtLeast(3);
        assertThat(pipeline.getPublishedBlocks()).as("no block of the reopened stream reached the take").isZero();
    }

    @Test
    void aBaselineTheEngineHasNotReachedIsRefusedTouchingNothing() {
        engine = engineOnTheMockBackend();
        pipeline = newPipeline();

        assertThatThrownBy(() -> pipeline.prepare(engine.getCaptureHaltGeneration() + 1))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(pipeline.isPreparing()).isFalse();
        assertThat(pipeline.getCaptureFlushService()).as("no flush service was created").isNull();
    }

    @Test
    void withoutAHaltTheSameStartSucceedsAndTheRunningPumpPublishes() {
        engine = engineOnTheMockBackend();
        engine.startAudioOutput();
        pipeline = preparedPipeline();

        pipeline.beginCapture(() -> { });

        assertThat(pipeline.isActive()).isTrue();
        assertThat(engine.getRecordingCallback()).isInstanceOf(CaptureCallback.class);
        await(() -> pipeline.getPublishedBlocks() >= 3, "the running pump published at least three blocks");
    }

    @Test
    void aHaltBeforeTheTakeIsPreparedDoesNotRejectItsStart() {
        engine = engineOnTheMockBackend();
        engine.startAudioOutput();
        loseTheDevice();
        engine.startAudioOutput();
        pipeline = preparedPipeline();

        pipeline.beginCapture();

        assertThat(pipeline.isActive()).as("the halt came before the take's baseline was read").isTrue();
        assertThat(engine.getRecordingCallback()).isInstanceOf(CaptureCallback.class);
        await(() -> pipeline.getPublishedBlocks() >= 1, "the reopened pump publishes into the new take");
    }

    /** Pins that {@link PipelineLifecycleTestSupport#startRecording} reads its baseline from the pipeline's own engine. */
    @Test
    void theLifecycleHelperStartsATakeOnAnEngineWhoseGenerationHasAdvanced() {
        engine = engineOnTheMockBackend();
        engine.haltRecordingCapture();
        engine.haltRecordingCapture();
        assertThat(engine.getCaptureHaltGeneration()).as("premise: the halts advanced the generation").isPositive();
        pipeline = newPipeline();

        PipelineLifecycleTestSupport.startRecording(pipeline);

        assertThat(pipeline.isActive()).as("the take started on the engine's current generation").isTrue();
    }

    /**
     * Two input sources, so the start installs a sibling input's callback as
     * well; the halt runs from the capture activation, after both callbacks
     * are installed: the rejected start leaves the additional-callbacks slot
     * empty and the sibling ring's producer gate closed, and a block the
     * sibling input then delivers reaches no ring. (The halt and the
     * rollback both empty that slot and close that gate; this pins the state
     * the two leave, not which of them stored it.)
     */
    @Test
    void aHaltFromTheCaptureActivationWithASiblingInputLeavesNoSiblingCallbackAndItsGateClosed() throws Exception {
        MultichannelInputCaptureStory326Test.PatternBackend patterns = new MultichannelInputCaptureStory326Test.PatternBackend();
        engine = new AudioEngine(new com.benesquivelmusic.daw.core.audio.AudioFormat(48_000, 2, 16, 256));
        engine.setStreamingProvision(new StreamingProvision(patterns.name(),
                List.of(new BackendStreamRung(patterns, new DeviceId(patterns.name(), "Interface A")))));
        List<Track> tracks = List.of(patternTrack("A", 0), patternTrack("B", 1));
        engine.startAudioInputOutput(tracks);
        engine.pauseAudioOutput();
        pipeline = new RecordingPipeline(engine, new Transport(), engine.getFormat(), takeDir, tracks);
        awaitWithinTheGuard(pipeline.prepare(engine.getCaptureHaltGeneration()), "the take's readiness");
        AtomicReference<AudioEngine.RecordingCallback[]> siblingsAtTheHalt = new AtomicReference<>();

        assertThatThrownBy(() -> pipeline.beginCapture(() -> {
            siblingsAtTheHalt.set(engine.getAdditionalRecordingCallbacks());
            engine.haltRecordingCapture();
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(RecordingPipeline.DEVICE_LOST_DURING_START_MESSAGE);

        assertThat(siblingsAtTheHalt.get()).as("premise: the halt ran with the sibling input's callback installed")
                .singleElement().isInstanceOf(CaptureCallback.class);
        assertThat(engine.getRecordingCallback()).isNull();
        assertThat(engine.getAdditionalRecordingCallbacks()).as("no sibling-input callback is installed").isEmpty();
        assertThat(ringOf(siblingsAtTheHalt.get()[0]).isProducerClosed())
                .as("the sibling ring's producer gate is closed").isTrue();
        assertThat(pipeline.isActive()).isFalse();
        assertThat(patterns.sibling.subscribers).as("premise: the sibling input is subscribed").isNotEmpty();
        patterns.sibling.emit(256);
        assertThat(pipeline.getPublishedBlocks()).as("the sibling's block reached no ring").isZero();
    }

    /** The ring a {@link CaptureCallback} publishes into; read reflectively (same module), failing on a rename. */
    private static CaptureRing ringOf(AudioEngine.RecordingCallback callback) throws ReflectiveOperationException {
        var field = CaptureCallback.class.getDeclaredField("ring");
        field.setAccessible(true);
        return (CaptureRing) field.get(callback);
    }

    private static Track patternTrack(String name, int device) {
        Track created = new Track(name, TrackType.AUDIO);
        created.setInputRouting(new InputRouting(0, 1));
        created.setInputDevice(MultichannelInputCaptureStory326Test.patternInput(device));
        created.setArmed(true);
        return created;
    }

    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + HANG_GUARD.toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime() - deadline < 0).as("%s, within %s", what, HANG_GUARD).isTrue();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }
}
