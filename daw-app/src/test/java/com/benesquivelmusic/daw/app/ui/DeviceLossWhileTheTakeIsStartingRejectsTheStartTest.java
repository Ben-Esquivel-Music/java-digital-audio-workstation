package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.recording.RecordState;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.recording.RecordingPipeline;
import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.await;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.awaitOnFx;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * A device loss that lands while the take is starting (story 327, PR #982
 * review r4297840180; Recording Reliability book §2.4, §5.5): Record opened
 * the take's stream, the engine controller's loss path halted capture
 * ({@code AudioEngine.haltRecordingCapture()}) and closed the stream on the
 * device-event thread, a stream was reopened, and only then did the take's
 * readiness turn run {@code RecordingPipeline.beginCapture()} — before the
 * loss's own FX turn. The start is rejected by the pipeline's device-loss
 * fence and abandoned as a failed start: one ERROR that says the device was
 * lost while the take was starting, no callback left installed, nothing of
 * the reopened stream in the take, its files discarded; the queued loss then
 * finds no take in flight and changes nothing, and the machine settles to
 * IDLE with the reopened stream left to whatever opened it.
 *
 * <p>The halt lands at one of three points of the start, each fixed on
 * every run:</p>
 * <ul>
 *   <li>after the take was prepared — its capture thread held before its
 *       readiness ({@link CaptureThreadHold}); the test thread runs the
 *       device-event thread's steps. This pins the fence of
 *       {@code beginCapture()} itself; it passes with any baseline read
 *       before the take is prepared.</li>
 *   <li>after the FX turn that follows the stream's open handed the
 *       take-directory allocation to the storage executor, and before the
 *       take's pipeline is built — the allocation held
 *       ({@code SteppedTakeFixture.ManualStorage}); the test thread runs the
 *       steps. This pins that the baseline is read before the take is
 *       prepared: a baseline read when the pipeline is prepared includes the
 *       halt and lets the start through. It passes with a baseline read
 *       anywhere from the Record press up to that hand-off.</li>
 *   <li>inside the open of the take's stream — from the engine's
 *       stream-open listener, on the record start's input-check thread,
 *       before {@code startAudioInputOutput} returns there. This pins that
 *       the baseline is read before the open returns: a baseline read on
 *       the input-check thread after the open, or on the FX turn that
 *       follows it, includes the halt and lets the start through; the read
 *       at the Record press, in the take's {@code PendingStart}, rejects
 *       it.</li>
 * </ul>
 *
 * <p>The loss is reported to the coordinator directly, as in
 * {@code DeviceLossRescueStepIndependenceTest}: the engine-bridge wiring
 * cannot order the readiness turn before the loss's turn.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class DeviceLossWhileTheTakeIsStartingRejectsTheStartTest {

    private static final Duration HOLD_WAIT = Duration.ofSeconds(10);
    private static final String DEVICE_LOST_WHILE_STARTING =
            "Recording aborted — no take was started: the audio device was lost while the take was starting";
    /** The name of the virtual thread on which the record start opens the take's stream. */
    private static final String INPUT_CHECK_THREAD = "daw-record-input-check";

    @TempDir
    Path projectDirectory;

    private final CaptureThreadHold hold = new CaptureThreadHold();
    private DeviceLossRecordingFixture fixture;

    @BeforeEach
    void openTheProject() throws Exception {
        fixture = DeviceLossRecordingFixture.withoutEngineBridge(projectDirectory);
        runOnFx(() -> hold.installOn(fixture.controller()));
    }

    @AfterEach
    void stopEverything() throws Exception {
        hold.release();
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void aLossBeforeCaptureBeginsAbortsTheStartAndTheQueuedLossChangesNothing() throws Exception {
        DeviceId device = fixture.activeDevice();
        int before = fixture.shownCount();
        hold.arm();
        runOnFx(fixture.controller()::toggleRecord);
        hold.awaitHolding(HOLD_WAIT);
        RecordingPipeline pipeline = hold.pipeline();
        Path takeDirectory = pipeline.getTakeDirectory();
        assertThat(onFx(() -> fixture.coordinator().getState())).as("premise: the take is being prepared")
                .isEqualTo(RecordState.PREPARING);
        assertThat(pipeline.isPreparing()).as("premise: capture has not begun").isTrue();

        loseTheDeviceAndReopenAStream();
        hold.release();
        awaitOnFx(() -> !fixture.controller().isPreparingTake(), "the readiness turn ran");

        assertTheStartWasAbandonedAndTheQueuedLossChangesNothing(device, before, pipeline, takeDirectory);
    }

    /**
     * PR #982 review, fix round 4: the halt lands after Record opened the
     * take's stream and before the take is prepared — the take-directory
     * allocation is held, so no pipeline exists yet. A baseline read when
     * the pipeline is prepared would already include the halt and let the
     * start through, its callbacks then fed by the reopened stream; the
     * baseline read at the Record press rejects it. The halt comes after the
     * stream's open and its FX turn, so a baseline read there passes this
     * test too; {@link #aLossWhileTheTakesStreamIsBeingOpenedAbortsTheStart}
     * is the one that fails it.
     */
    @Test
    void aLossAfterTheStreamOpenedAndBeforeTheTakeIsPreparedAbortsTheStart() throws Exception {
        DeviceId device = fixture.activeDevice();
        SteppedTakeFixture.ManualStorage storage = new SteppedTakeFixture.ManualStorage();
        runOnFx(() -> fixture.coordinator().setStorageExecutorForTest(storage));
        int before = fixture.shownCount();
        runOnFx(fixture.controller()::toggleRecord);
        // Handed over on the FX turn that follows the stream's open.
        storage.awaitQueued(1);
        assertThat(onFx(() -> fixture.coordinator().getState())).as("premise: the take is being prepared")
                .isEqualTo(RecordState.PREPARING);
        assertThat(fixture.engine().isStreamOpen()).as("premise: Record opened the take's stream").isTrue();

        loseTheDeviceAndReopenAStream();
        storage.release();
        awaitOnFx(() -> !fixture.controller().isPreparingTake(), "the readiness turn ran");
        RecordingPipeline pipeline = hold.pipeline();

        assertTheStartWasAbandonedAndTheQueuedLossChangesNothing(device, before, pipeline,
                pipeline.getTakeDirectory());
    }

    /**
     * PR #982 review, fix round 5: the halt lands inside the open of the
     * take's stream. The record start's input-check thread opens it with
     * {@code AudioEngine.startAudioInputOutput}; the engine notifies its
     * stream-open listener on the thread that completed the open, after it
     * has released its lifecycle lock and before that call returns. The
     * listener ({@link LossInsideTheOpen}) runs the device-event thread's
     * loss path there — halt, close — and a reopen, once: so the halt comes
     * after the Record press and before the input-check thread records the
     * stream's generation and posts the FX turn that hands the take over to
     * the storage executor. A baseline read anywhere after the open returned
     * — on that thread, or on that FX turn — would include the halt and let
     * the start through, its callbacks then fed by the reopened stream; the
     * baseline the take's {@code PendingStart} read at the Record press
     * rejects it.
     *
     * <p>The listener and not the engine controller's own loss path: this
     * fixture has no engine controller, whose stream-open binding would
     * otherwise occupy the listener slot, and the loss is reported to the
     * coordinator directly, as in the sibling tests. The listener runs
     * outside the engine's lifecycle lock, so the loss's close and the
     * reopen take it as the device-event thread would, and it waits for
     * nothing on the FX thread. It records where it ran and what it saw,
     * and keeps any failure for the test thread: the engine logs and
     * swallows a listener's runtime exception.</p>
     */
    @Test
    void aLossWhileTheTakesStreamIsBeingOpenedAbortsTheStart() throws Exception {
        DeviceId device = fixture.activeDevice();
        long generationAtThePress = fixture.engine().getCaptureHaltGeneration();
        LossInsideTheOpen loss = new LossInsideTheOpen(fixture.engine());
        fixture.engine().setStreamOpenListener(loss);
        int before = fixture.shownCount();
        runOnFx(fixture.controller()::toggleRecord);
        awaitOnFx(() -> !fixture.controller().isPreparingTake(), "the readiness turn ran");
        fixture.engine().setStreamOpenListener(null);

        assertThat(loss.failure).as("fixture: the loss inside the open ran to its end").isNull();
        assertThat(loss.thread).as("premise: the loss ran on the record start's input-check thread")
                .isEqualTo(INPUT_CHECK_THREAD);
        assertThat(loss.engineFrames).as("premise: the loss ran inside the open of the take's stream, "
                + "before it returned").contains("startAudioInputOutput");
        assertThat(loss.generationBeforeTheHalt).as("premise: no halt between the Record press and the open")
                .isEqualTo(generationAtThePress);
        assertThat(loss.generationAfterTheHalt).as("premise: the loss's halt advanced the generation")
                .isEqualTo(generationAtThePress + 1);
        assertThat(loss.streamClosedByTheLoss).as("premise: the loss closed the take's stream").isTrue();
        RecordingPipeline pipeline = hold.pipeline();

        assertTheStartWasAbandonedAndTheQueuedLossChangesNothing(device, before, pipeline,
                pipeline.getTakeDirectory());
    }

    /**
     * The device-event thread's loss path in its order — halt, then close —
     * and then a stream opens before the loss reaches the FX thread.
     */
    private void loseTheDeviceAndReopenAStream() {
        fixture.engine().haltRecordingCapture();
        fixture.closeTheStreamAsTheLossDoes();
        fixture.engine().startAudioOutput();
    }

    private void assertTheStartWasAbandonedAndTheQueuedLossChangesNothing(DeviceId device, int before,
                                                                          RecordingPipeline pipeline,
                                                                          Path takeDirectory) throws Exception {
        assertThat(onFx(() -> fixture.coordinator().getState())).as("the start was abandoned, not announced")
                .isIn(RecordState.ABORTED, RecordState.IDLE);
        assertThat(pipeline.isActive()).isFalse();
        assertThat(fixture.engine().getRecordingCallback()).as("the rejected start removed its callback").isNull();
        assertThat(fixture.engine().getAdditionalRecordingCallbacks()).as("no additional callback is installed")
                .isEmpty();
        assertThat(fixture.entriesSince(before))
                .extracting(NotificationEntry::level, NotificationEntry::message)
                .containsExactly(tuple(NotificationLevel.ERROR, DEVICE_LOST_WHILE_STARTING));

        // The loss's own FX turn, queued behind the readiness turn.
        int beforeTheLossTurn = fixture.shownCount();
        runOnFx(() -> fixture.coordinator().onDeviceLost(device));
        awaitOnFx(() -> fixture.coordinator().getState() == RecordState.IDLE
                && !fixture.controller().isTakeBeingWritten(), "the abandoned start settled to IDLE");
        assertThat(fixture.entriesSince(beforeTheLossTurn)).as("the queued loss found no take in flight").isEmpty();

        int bytesPerSunkBlock = fixture.project().getFormat().bufferSize()
                * fixture.project().getFormat().channels() * Short.BYTES;
        int target = fixture.backend().recordedOutput().length + 3 * bytesPerSunkBlock;
        await(() -> fixture.backend().recordedOutput().length >= target,
                "premise: the reopened pump rendered at least three more blocks");
        fixture.engine().pauseAudioOutput();
        assertThat(pipeline.getPublishedBlocks()).as("nothing of the reopened stream reached the take").isZero();
        assertThat(takeDirectory).as("the abandoned take's files and directory were removed").doesNotExist();
        assertThat(fixture.engine().isStreamOpen()).as("the reopened stream is left to whatever opened it").isTrue();
        assertThat(onFx(() -> fixture.guitar().isArmed())).as("the armed set is kept").isTrue();
    }

    /**
     * The device-event thread's loss path — halt, close — and a reopen, run
     * from the engine's stream-open listener the first time it is called:
     * on the thread that completed the open, inside the open call, after the
     * engine released its lifecycle lock. The reopen notifies the listener
     * again, and that call does nothing. Records what it saw for the test
     * thread, and any failure, which the engine would log and swallow.
     */
    private static final class LossInsideTheOpen implements AudioEngine.StreamOpenListener {

        private final AudioEngine engine;
        private final AtomicBoolean ran = new AtomicBoolean();
        volatile String thread;
        volatile List<String> engineFrames = List.of();
        volatile long generationBeforeTheHalt = -1;
        volatile long generationAfterTheHalt = -1;
        volatile boolean streamClosedByTheLoss;
        volatile Throwable failure;

        LossInsideTheOpen(AudioEngine engine) {
            this.engine = engine;
        }

        @Override
        public void streamOpened(AudioBackend backend, DeviceId device) {
            if (!ran.compareAndSet(false, true)) {
                return;
            }
            try {
                thread = Thread.currentThread().getName();
                engineFrames = StackWalker.getInstance().walk(frames -> frames
                        .filter(frame -> frame.getClassName().equals(AudioEngine.class.getName()))
                        .map(StackWalker.StackFrame::getMethodName)
                        .toList());
                generationBeforeTheHalt = engine.getCaptureHaltGeneration();
                engine.haltRecordingCapture();
                generationAfterTheHalt = engine.getCaptureHaltGeneration();
                engine.stopAudioOutput();
                engine.stop();
                streamClosedByTheLoss = !engine.isStreamOpen();
                engine.startAudioOutput();
            } catch (Throwable thrown) {
                failure = thrown;
            }
        }
    }
}
