package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.CaptureInputSnapshot;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.audio.InputStallKind;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.recording.MultichannelInputCaptureStory326Test.PatternBackend;
import com.benesquivelmusic.daw.core.recording.MultichannelInputCaptureStory326Test.RenderGate;
import com.benesquivelmusic.daw.core.recording.TakeManifest.ClockEvent;
import com.benesquivelmusic.daw.core.recording.TakeManifest.ClockMark;
import com.benesquivelmusic.daw.core.recording.TakeManifest.InputSilence;
import com.benesquivelmusic.daw.core.recording.TakeManifest.InputStopped;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SealedBy;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.audio.AudioBlock;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.audio.BackendDropoutCounters;
import com.benesquivelmusic.daw.sdk.audio.BackendThreadCounters;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.InputBlockLossListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;

import static com.benesquivelmusic.daw.core.recording.RecordedAudioTestSupport.audioOnDisk;
import static com.benesquivelmusic.daw.core.recording.RecordedAudioTestSupport.decoded16;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A capture input that stops delivering, or never delivers, during a take
 * (story 338, rulings E13 and E15): the engine's input snapshot and stall
 * declaration, the render's input-starved stamp, and what the take's
 * {@code take.manifest}, warnings and counts record. Driven through the real
 * render pump, one paced block at a time ({@link RenderGate}): the stream's
 * own input delivers before a block is rendered, an input opened beside the
 * stream after it.
 */
@ExtendWith(CaptureFlushThreadLeakGuard.class)
@Tag("slow")
class InputStallCaptureTest {

    private static final int BLOCK = 256;
    private static final com.benesquivelmusic.daw.core.audio.AudioFormat FORMAT =
            new com.benesquivelmusic.daw.core.audio.AudioFormat(48000, 2, 16, BLOCK);

    @TempDir
    Path directory;

    private final PatternBackend backend = new PatternBackend();
    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private final Transport transport = new Transport();
    private AudioEngine engine;
    private RecordingPipeline pipeline;

    @BeforeEach
    void setUp() {
        backend.renderGate = new RenderGate();
        engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(new StreamingProvision(backend.name(), List.of(new BackendStreamRung(backend,
                new DeviceId(backend.name(), "Interface A")))));
    }

    @AfterEach
    void tearDown() {
        try {
            if (pipeline != null) {
                PipelineLifecycleTestSupport.endTheTake(pipeline);
            }
        } finally {
            try {
                engine.stopAudioOutput();
            } finally {
                engine.shutdown();
            }
        }
    }

    @Test
    void theStreamsInputDeclaredStalledMidTakeFlagsOnlyItsTrackAndTheTakeKeepsRecording() throws Exception {
        Track primary = track("Primary", 0, 1, 0);
        Track sibling = track("Sibling", 0, 1, 1);
        startTake(List.of(primary, sibling), null);
        block(true, true);
        block(true, true);
        block(false, true);
        block(false, true);

        CaptureInputSnapshot streamInput = input(true);
        CaptureInputSnapshot siblingInput = input(false);
        assertThat(streamInput.deliveredBlocks()).as("two blocks arrived on the stream's input").isEqualTo(2);
        assertThat(streamInput.sourceName()).contains("Interface A");
        assertThat(siblingInput.deliveredBlocks()).isEqualTo(4);
        assertThat(siblingInput.sourceName()).contains("Interface B");
        assertThat(input(false).handle()).as("a handle is the same object for the life of its input")
                .isSameAs(siblingInput.handle());

        assertThat(engine.declareInputStalled(streamInput.handle(), InputStallKind.STOPPED)).isTrue();
        assertThat(engine.declareInputStalled(streamInput.handle(), InputStallKind.STOPPED))
                .as("a declaration acts once").isFalse();
        assertThat(engine.captureInputs()).extracting(CaptureInputSnapshot::streamInput).containsExactly(false);
        block(false, true);
        block(false, true);

        assertThat(pipeline.isActive()).as("the take is still recording").isTrue();
        assertThat(pipeline.getSession(sibling).getTotalSamplesRecorded()).as("the other input's track keeps growing")
                .isEqualTo(6 * BLOCK);
        assertThat(audioOnDisk(pipeline.getSession(sibling))[0]).containsOnly(decoded16(.9f));
        assertThat(pipeline.getSession(primary).getTotalSamplesRecorded()).isEqualTo(6 * BLOCK);
        BackendThreadCounters health = engine.audioHealth().backendCounters();
        assertThat(health.inputHalts()).as("an input that delivered and stopped is an input halt (E11)").isEqualTo(1);
        assertThat(health.lastInputFault()).get().extracting(Throwable::getMessage)
                .isEqualTo("input from “" + streamInput.sourceName() + "” stopped delivering audio");

        TakeManifest manifest = stopAndRead();
        long start = manifest.startFrame();
        assertThat(manifest.routingFlags()).extracting(TakeManifest.RoutingFlag::trackId)
                .containsExactly(primary.getId());
        assertThat(manifest.inputStops()).as("the frame after the last block the input delivered (E13)")
                .containsExactly(new InputStopped(primary.getId(), start + 2 * BLOCK, start + 2 * BLOCK));
        assertThat(manifest.inputSilences()).as("the starved blocks before the stop are the stop's (E49)").isEmpty();
        assertThat(pipeline.getStarvedInputBlocks()).isZero();
        assertThat(warnings).containsExactly("Track 'Primary': input from '" + streamInput.sourceName()
                + "' stopped delivering audio at 0:00.0 into the take; the track records silence from there.");
    }

    @Test
    void anInputOpenedBesideTheStreamDeclaredStalledRecordsItsStopAndWhereClockedSilenceResumed()
            throws Exception {
        Track primary = track("Primary", 0, 1, 0);
        Track sibling = track("Sibling", 0, 1, 1);
        startTake(List.of(primary, sibling), null);
        block(true, true);
        block(true, true);
        block(true, false);
        block(true, false);

        CaptureInputSnapshot siblingInput = input(false);
        assertThat(siblingInput.deliveredBlocks()).isEqualTo(2);
        assertThat(engine.declareInputStalled(siblingInput.handle(), InputStallKind.STOPPED)).isTrue();
        block(true, false);
        block(true, false);

        long primaryFrames = pipeline.getSession(primary).getTotalSamplesRecorded();
        long siblingFrames = pipeline.getSession(sibling).getTotalSamplesRecorded();
        assertThat(primaryFrames).isEqualTo(6 * BLOCK);
        assertThat(audioOnDisk(pipeline.getSession(primary))[0]).containsOnly(decoded16(.1f));
        float[] siblingAudio = audioOnDisk(pipeline.getSession(sibling))[0];
        assertThat(Arrays.copyOfRange(siblingAudio, 0, 2 * BLOCK)).containsOnly(decoded16(.9f));
        assertThat(Arrays.copyOfRange(siblingAudio, 2 * BLOCK, siblingAudio.length)).containsOnly(0f);
        assertThat(engine.audioHealth().backendCounters().inputHalts()).isEqualTo(1);

        TakeManifest manifest = stopAndRead();
        long start = manifest.startFrame();
        assertThat(manifest.inputStops()).as("stop: start + the frames it delivered; resume: the hand-off (E55)")
                .containsExactly(new InputStopped(sibling.getId(), start + 2 * BLOCK, start + 4 * BLOCK));
        InputStopped stop = manifest.inputStops().getFirst();
        assertThat(primaryFrames - siblingFrames)
                .as("the sibling's file is short by exactly the window it missed: no padding (E55)")
                .isEqualTo(stop.resumeFrame() - stop.stopFrame())
                .isEqualTo(2 * BLOCK);
        assertThat(manifest.routingFlags()).extracting(TakeManifest.RoutingFlag::trackId)
                .containsExactly(sibling.getId());
        assertThat(manifest.inputSilences()).isEmpty();
        assertThat(warnings).singleElement().asString()
                .startsWith("Track 'Sibling': input from '").contains("Interface B")
                .endsWith("' stopped delivering audio at 0:00.0 into the take; the track records silence from there.");
    }

    @Test
    void anInputThatNeverDeliversDuringATakeIsFlaggedFromTheTakesStartWithoutAnInputHalt() throws Exception {
        Track primary = track("Primary", 0, 1, 0);
        Track sibling = track("Sibling", 0, 1, 1);
        startTake(List.of(primary, sibling), null);
        long haltsBefore = engine.audioHealth().backendCounters().inputHalts();
        block(false, true);
        block(false, true);
        block(false, true);

        CaptureInputSnapshot streamInput = input(true);
        assertThat(streamInput.deliveredBlocks()).isZero();
        assertThat(engine.declareInputStalled(streamInput.handle(), InputStallKind.NEVER_DELIVERED)).isTrue();
        block(false, true);
        block(false, true);

        assertThat(engine.audioHealth().backendCounters().inputHalts())
                .as("an input that never started is no input halt (E52)").isEqualTo(haltsBefore);
        TakeManifest manifest = stopAndRead();
        long start = manifest.startFrame();
        assertThat(manifest.inputStops()).as("flagged from the take's first frame (E15)")
                .containsExactly(new InputStopped(primary.getId(), start, start));
        assertThat(manifest.inputSilences()).isEmpty();
        assertThat(manifest.routingFlags()).extracting(TakeManifest.RoutingFlag::trackId)
                .containsExactly(primary.getId());
        assertThat(warnings).containsExactly("Track 'Primary': input from '" + streamInput.sourceName()
                + "' delivered no audio; the track records silence.");
        assertThat(pipeline.getSession(sibling).getTotalSamplesRecorded()).isEqualTo(5 * BLOCK);
    }

    /**
     * Ruling E127: on the default device of a backend that names no default
     * input, the plan keeps the placeholder as the input's name; the warning
     * and the fault's reason say "the audio input".
     */
    @Test
    void anInputNamedByThePlaceholderThatStopsIsTheAudioInputInTheWarningAndTheFault() throws Exception {
        onTheDefaultDevice();
        Track primary = track("Primary", 0, 1, -1);
        startTake(List.of(primary), null);
        block(true, false);
        block(true, false);
        block(false, false);
        CaptureInputSnapshot streamInput = input(true);
        assertThat(streamInput.sourceName()).as("premise: the plan kept the placeholder")
                .isEqualTo(DeviceId.defaultFor(backend.name()).name());

        assertThat(engine.declareInputStalled(streamInput.handle(), InputStallKind.STOPPED)).isTrue();
        block(false, false);

        assertThat(engine.audioHealth().backendCounters().lastInputFault()).get().extracting(Throwable::getMessage)
                .isEqualTo("the audio input stopped delivering audio");
        stopAndRead();
        assertThat(warnings).containsExactly("Track 'Primary': the audio input stopped delivering audio at 0:00.0"
                + " into the take; the track records silence from there.");
    }

    /** Ruling E127: the placeholder-named input that never delivered. */
    @Test
    void anInputNamedByThePlaceholderThatNeverDeliversIsTheAudioInputInTheWarning() throws Exception {
        onTheDefaultDevice();
        Track primary = track("Primary", 0, 1, -1);
        startTake(List.of(primary), null);
        block(false, false);
        block(false, false);

        assertThat(engine.declareInputStalled(input(true).handle(), InputStallKind.NEVER_DELIVERED)).isTrue();
        block(false, false);

        stopAndRead();
        assertThat(warnings).containsExactly("Track 'Primary': the audio input delivered no audio; the track records"
                + " silence.");
    }

    private void onTheDefaultDevice() {
        engine.setStreamingProvision(new StreamingProvision(backend.name(),
                List.of(new BackendStreamRung(backend, DeviceId.defaultFor(backend.name())))));
    }

    @Test
    void aTransientStarvedRunIsOneCountedSilenceEntryWithNoFlagAndNoWarning() throws Exception {
        Track primary = track("Primary", 0, 1, 0);
        Track sibling = track("Sibling", 0, 1, 1);
        startTake(List.of(primary, sibling), null);
        block(true, true);
        block(false, true);
        block(false, true);
        block(true, true);

        assertThat(pipeline.getStarvedInputBlocks()).as("the run the delivered block ended is counted (E34)")
                .isEqualTo(2);
        assertThat(pipeline.getStarvedInputFrames()).isEqualTo(2 * BLOCK);
        TakeManifest manifest = stopAndRead();
        long start = manifest.startFrame();
        assertThat(manifest.inputSilences())
                .containsExactly(new InputSilence(primary.getId(), start + BLOCK, 2 * BLOCK));
        assertThat(manifest.routingFlags()).isEmpty();
        assertThat(manifest.inputStops()).isEmpty();
        assertThat(manifest.gaps()).as("silence that was written is no gap (E46)").isEmpty();
        assertThat(warnings).isEmpty();
    }

    /**
     * The callback stamps each block with the loop window it was captured in,
     * so a starved run across the transport's wrap is cut at the loop end
     * (story 338, ruling E75): a loop of 1000 frames, four blocks of 256.
     */
    @Test
    void aStarvedRunAcrossTheTransportsLoopWrapEndsEachLineByTheLoopEnd() throws Exception {
        long loopEndFrame = 1000;
        transport.setLoopWindow(true, 0.0, loopEndFrame / 24_000.0);
        Track primary = track("Primary", 0, 1, 0);
        startTake(List.of(primary), null);
        block(true, false);
        block(false, false);
        block(false, false);
        block(false, false);
        block(false, false);
        block(true, false);

        assertThat(pipeline.getStarvedInputBlocks()).as("one per block, the one across the wrap once").isEqualTo(4);
        TakeManifest manifest = stopAndRead();
        assertThat(manifest.startFrame()).as("premise: the take starts at the loop start").isZero();
        assertThat(manifest.inputSilences()).as("cut at the loop end, continued from the loop start")
                .containsExactly(new InputSilence(primary.getId(), BLOCK, loopEndFrame - BLOCK),
                        new InputSilence(primary.getId(), 0, 4 * BLOCK - (loopEndFrame - BLOCK)));
    }

    /** The person's Stop of a take whose input delivered every block marks nothing (story 338, ruling E79). */
    @Test
    void aHealthyTakeStoppedByThePersonWritesNoSilenceAndCountsNoStarvedBlock() throws Exception {
        Track primary = track("Primary", 0, 1, 0);
        startTake(List.of(primary), null);
        for (int block = 0; block < 4; block++) {
            block(true, false);
        }

        TakeManifest manifest = stopAndRead();

        assertThat(manifest.sealedBy()).contains(SealedBy.STOP);
        assertThat(manifest.inputSilences()).as("the stop sequence starves no block").isEmpty();
        assertThat(pipeline.getStarvedInputBlocks()).isZero();
        assertThat(pipeline.getSession(primary).getTotalSamplesRecorded()).isEqualTo(4 * BLOCK);
        assertThat(warnings).isEmpty();
    }

    @Test
    void aStallRescuedSealMarksTheTrailingSilenceOnTheStreamInputsTracksOnlyAndCountsNone() throws Exception {
        Track primary = track("Primary", 0, 1, 0);
        Track sibling = track("Sibling", 0, 1, 1);
        startTake(List.of(primary, sibling), null);
        block(true, true);
        block(true, true);
        block(false, true);
        block(false, true);
        block(false, true);

        engine.haltRecordingCapture();
        pipeline.requestStop(SealedBy.DEVICE_LOSS).toCompletableFuture().get(5, TimeUnit.SECONDS);
        pipeline.completeStop();
        TakeManifest manifest = TakeManifest.read(directory.resolve(TakeManifest.FILE_NAME));

        long start = manifest.startFrame();
        assertThat(manifest.sealedBy()).contains(SealedBy.DEVICE_LOSS);
        assertThat(manifest.inputSilences()).as("the tail, on the stream input's track only (E3, E48)")
                .containsExactly(new InputSilence(primary.getId(), start + 2 * BLOCK, 3 * BLOCK));
        assertThat(pipeline.getStarvedInputBlocks()).as("the tail is part of the loss, not a dropout (E48)").isZero();
        assertThat(manifest.routingFlags()).isEmpty();
        assertThat(warnings).isEmpty();
    }

    @Test
    void aSiblingBlockDeliveredInsideAStarvedRenderIsNeverMarkedStarved() throws Exception {
        Track primary = track("Primary", 0, 1, 0);
        Track sibling = track("Sibling", 0, 1, 1);
        boolean[] siblingDeliversInsideTheRender = {false};
        Mixer mixer = new Mixer();
        mixer.getMasterChannel().getEffectsChain().addProcessor(new AudioProcessor() {
            @Override public void process(float[][] input, float[][] output, int frames) {
                if (siblingDeliversInsideTheRender[0]) {
                    backend.sibling.emit(BLOCK);
                }
            }
            @Override public void reset() { }
            @Override public int getInputChannelCount() { return 2; }
            @Override public int getOutputChannelCount() { return 2; }
        });
        startTake(List.of(primary, sibling), mixer);
        block(true, true);
        siblingDeliversInsideTheRender[0] = true;
        block(false, false);
        block(false, false);
        siblingDeliversInsideTheRender[0] = false;
        block(true, true);

        assertThat(pipeline.getSession(sibling).getTotalSamplesRecorded()).isEqualTo(4 * BLOCK);
        TakeManifest manifest = stopAndRead();
        assertThat(manifest.inputSilences()).as("only the stream's own input is stamped")
                .extracting(InputSilence::trackId).containsExactly(primary.getId());
    }

    @Test
    void aBlockTheTestRendersAfterThePumpStoppedIsNeverStamped() throws Exception {
        Track primary = track("Primary", 0, 1, 0);
        List<Track> tracks = List.of(primary);
        engine.setGraph(transport, null, tracks);
        pipeline = new RecordingPipeline(engine, transport, FORMAT, directory, tracks);
        pipeline.setWarningSink(warnings::add);
        engine.startAudioInputOutput(tracks);
        backend.renderGate.awaitRendered();
        engine.pauseAudioOutput();
        pipeline.prepare(engine.getCaptureHaltGeneration()).toCompletableFuture().get(5, TimeUnit.SECONDS);
        pipeline.beginCapture();
        for (int block = 0; block < 3; block++) {
            float[] row = new float[BLOCK];
            Arrays.fill(row, .1f);
            engine.processBlock(new float[][]{row}, new float[2][BLOCK], BLOCK);
            pipeline.awaitFlushed();
        }

        TakeManifest manifest = stopAndRead();
        assertThat(manifest.inputSilences())
                .as("the pump's last block was starved, but its stamp ended with that block").isEmpty();
    }

    @Test
    void clockEventsReachTheManifestFromTheLoopAndAtTheSealAndNotAfterIt() throws Exception {
        Track primary = track("Primary", 0, 1, 0);
        startTake(List.of(primary), null);
        block(true, false);

        pipeline.noteClockEvent(ClockEvent.UNLOCKED, 1_000);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!readManifest().clockMarks().contains(new ClockMark(ClockEvent.UNLOCKED, 1_000))) {
            assertThat(System.nanoTime() - deadline).as("the drain loop writes a clock event").isNegative();
            Thread.sleep(5);
        }
        pipeline.getCaptureFlushService().setDrainPaused(true);
        pipeline.noteClockEvent(ClockEvent.LOCKED, 2_000);
        TakeManifest sealed = stopAndRead();
        pipeline.noteClockEvent(ClockEvent.RESYNC, 3_000);

        assertThat(sealed.clockMarks()).as("the event the paused loop never drained is written at the seal")
                .containsExactly(new ClockMark(ClockEvent.UNLOCKED, 1_000), new ClockMark(ClockEvent.LOCKED, 2_000));
        assertThat(readManifest()).as("an event after the seal is ignored").isEqualTo(sealed);
    }

    @Test
    void capturedInputBlocksLostCountsQueueAndBackendDropsAndKeepsThemAcrossAClose() throws Exception {
        Track primary = track("Primary", 0, 1, 0);
        List<Track> tracks = List.of(primary);
        engine.setGraph(transport, null, tracks);
        backend.dropouts = inputDrops(2);
        engine.startAudioInputOutput(tracks);
        backend.renderGate.awaitRendered();
        for (int block = 0; block < 35; block++) {
            backend.emit(BLOCK);
        }
        assertThat(engine.capturedInputBlocksLost()).as("the pump's queue holds 32 blocks").isEqualTo(3);

        backend.dropouts = inputDrops(7);
        assertThat(engine.capturedInputBlocksLost()).as("the backend's drops since the open").isEqualTo(8);
        engine.stopAudioOutput();
        assertThat(engine.capturedInputBlocksLost()).as("kept when the stream closes").isEqualTo(8);
        backend.dropouts = inputDrops(9);
        assertThat(engine.capturedInputBlocksLost()).as("a closed stream counts nothing more").isEqualTo(8);

        engine.startAudioInputOutput(tracks);
        backend.renderGate.awaitRendered();
        backend.dropouts = inputDrops(10);
        assertThat(engine.capturedInputBlocksLost()).as("a reopened backend counts from its new open").isEqualTo(9);
    }

    /**
     * The blocks the stream's own input thread lost count in the take's figure
     * from the stream's open; its input halts and callback faults never do, and
     * a closed stream counts nothing more until it opens again (story 338,
     * rulings E206, E207 and E211).
     */
    @Test
    void theBlocksTheStreamsInputThreadLostCountFromItsOpenAndItsHaltsAndCallbackFaultsDoNot() throws Exception {
        List<Track> tracks = List.of(track("Primary", 0, 1, 0));
        backend.counters = threadCounters(0, 2, 0);
        startStream(tracks);
        long base = engine.capturedInputBlocksLost();

        backend.counters = threadCounters(0, 5, 1);
        assertThat(engine.capturedInputBlocksLost() - base).as("three blocks lost since the open").isEqualTo(3);
        backend.counters = threadCounters(4, 5, 1);
        assertThat(engine.capturedInputBlocksLost() - base).as("callback faults are not lost input").isEqualTo(3);
        engine.stopAudioOutput();
        assertThat(engine.capturedInputBlocksLost() - base).as("kept when the stream closes").isEqualTo(3);
        backend.counters = threadCounters(0, 9, 1);
        assertThat(engine.capturedInputBlocksLost() - base).as("a closed stream counts nothing more").isEqualTo(3);

        startStream(tracks);
        backend.counters = threadCounters(0, 10, 1);
        assertThat(engine.capturedInputBlocksLost() - base).as("a reopened stream counts from its new open")
                .isEqualTo(4);
    }

    /**
     * An input opened beside the stream counts its lost blocks and its failed
     * device callbacks, never its input halts, and keeps them when it closes
     * (story 337, ruling E86; story 338, rulings E207 and E211).
     */
    @Test
    void theBlocksAnInputBesideTheStreamLostCountWithItsFailedCallbacksAndItsHaltsDoNot() throws Exception {
        backend.siblingCounters = threadCounters(1, 1, 0);
        startStream(List.of(track("Primary", 0, 1, 0), track("Sibling", 0, 1, 1)));
        long base = engine.capturedInputBlocksLost();

        backend.sibling.counters = threadCounters(3, 2, 1);
        assertThat(engine.capturedInputBlocksLost() - base).as("two failed callbacks and one lost block")
                .isEqualTo(3);
        engine.stopAudioOutput();
        assertThat(engine.capturedInputBlocksLost() - base).as("kept when the input closes").isEqualTo(3);
    }

    /**
     * An input opened beside the stream whose dropout report fails at the read
     * a take baselines on brings none of the blocks it lost before the take
     * into it, and counts on from its next good read (story 338, ruling E261).
     */
    @Test
    void aReportBesideTheStreamThatFailsAsTheTakeStartsImportsNothingLostBeforeIt() throws Exception {
        startStream(List.of(track("Primary", 0, 1, 0), track("Sibling", 0, 1, 1)));
        backend.sibling.dropouts = null;

        long base = engine.capturedInputBlocksLostAsATakeStarts();

        backend.sibling.dropouts = inputDrops(3);
        assertThat(engine.capturedInputBlocksLost() - base).as("the blocks lost before the take are not its own")
                .isZero();
        backend.sibling.dropouts = inputDrops(4);
        assertThat(engine.capturedInputBlocksLost() - base).isEqualTo(1);
    }

    /** A stream report that fails keeps the count it had read: no throw, no fall (story 338, ruling E214). */
    @Test
    void aStreamReportThatThrowsKeepsTheCountItHadRead() throws Exception {
        startStream(List.of(track("Primary", 0, 1, 0)));
        long base = engine.capturedInputBlocksLost();
        backend.counters = threadCounters(0, 4, 0);
        assertThat(engine.capturedInputBlocksLost() - base).isEqualTo(4);
        try {
            backend.reportFailure = new AssertionError("the report broke");

            long afterTheFailure = engine.capturedInputBlocksLost();

            assertThat(afterTheFailure - base).isEqualTo(4);
        } finally {
            backend.reportFailure = null;
        }
    }

    /**
     * A {@link VirtualMachineError} from the stream's report reaches the one
     * read that met it; a later read answers the count from before it (story
     * 337, rulings E78 and E95).
     */
    @Test
    void aVirtualMachineErrorFromTheStreamsReportReachesOnlyTheReadThatMetIt() throws Exception {
        startStream(List.of(track("Primary", 0, 1, 0)));
        backend.counters = threadCounters(0, 4, 0);
        long before = engine.capturedInputBlocksLost();
        InternalError fatal = new InternalError("the report met a fatal error");
        try {
            backend.reportFailure = fatal;

            assertThatThrownBy(engine::capturedInputBlocksLost).isSameAs(fatal);
            assertThat(engine.capturedInputBlocksLost()).isEqualTo(before);
        } finally {
            backend.reportFailure = null;
        }
    }

    /**
     * A block a publisher drops for the render pump's subscriber counts once,
     * under the gate its queue drops count under: never once its input ended
     * or its pump stopped (story 338, ruling E208).
     */
    @Test
    void aBlockDroppedForTheStreamsSubscriberCountsOnlyWhileItsPumpRunsAndItsInputLives() throws Exception {
        List<Track> tracks = List.of(track("Primary", 0, 1, 0));
        startStream(tracks);
        long base = engine.capturedInputBlocksLost();
        Flow.Subscriber<? super AudioBlock> subscriber = backend.subscribers.get(0);
        InputBlockLossListener pump = (InputBlockLossListener) subscriber;
        AudioBlock dropped = AudioBlock.silence(48000, 2, BLOCK);

        pump.inputBlockDropped(dropped);
        assertThat(engine.capturedInputBlocksLost() - base).isEqualTo(1);
        subscriber.onComplete();
        pump.inputBlockDropped(dropped);
        assertThat(engine.capturedInputBlocksLost() - base).as("its input ended").isEqualTo(1);

        engine.stopAudioOutput();
        startStream(tracks);
        InputBlockLossListener restarted = (InputBlockLossListener) backend.subscribers.get(0);
        engine.stopAudioOutput();
        restarted.inputBlockDropped(dropped);
        assertThat(engine.capturedInputBlocksLost() - base).as("its pump stopped").isEqualTo(1);
    }

    /**
     * A block a publisher drops for the subscriber of an input beside the
     * stream counts once while that subscriber is live, and never after its
     * input closed (story 338, ruling E208).
     */
    @Test
    void aBlockDroppedForTheSubscriberBesideTheStreamCountsOnlyWhileItIsLive() throws Exception {
        startStream(List.of(track("Primary", 0, 1, 0), track("Sibling", 0, 1, 1)));
        long base = engine.capturedInputBlocksLost();
        InputBlockLossListener beside = (InputBlockLossListener) backend.sibling.subscribers.get(0);
        AudioBlock dropped = AudioBlock.silence(48000, 1, BLOCK);

        beside.inputBlockDropped(dropped);
        assertThat(engine.capturedInputBlocksLost() - base).isEqualTo(1);
        engine.stopAudioOutput();
        beside.inputBlockDropped(dropped);
        assertThat(engine.capturedInputBlocksLost() - base).as("its input closed").isEqualTo(1);
    }

    /**
     * Through the real wiring: the stream's publisher holds one block per
     * subscriber and never delivers. A block only another subscriber dropped
     * moves the figure by 0, one the engine's subscriber dropped too moves it
     * by exactly 1, and nothing counts once the pump stopped (story 338,
     * ruling E208).
     */
    @Test
    void aBlockOnlyAnotherSubscriberDroppedNeverCountsAndOneTheStreamsSubscriberDroppedCountsOnce() throws Exception {
        backend.publisher = new SubmissionPublisher<>(task -> { }, 1);
        backend.publisher.subscribe(new Flow.Subscriber<AudioBlock>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { }
            @Override public void onNext(AudioBlock item) { }
            @Override public void onError(Throwable throwable) { }
            @Override public void onComplete() { }
        });
        backend.publisher.offer(AudioBlock.silence(48000, 2, BLOCK), InputBlockLossListener::reportDrop);
        startStream(List.of(track("Primary", 0, 1, 0)));
        long base = engine.capturedInputBlocksLost();

        backend.emit(BLOCK);
        assertThat(engine.capturedInputBlocksLost() - base).as("only the other subscriber dropped it").isZero();
        backend.emit(BLOCK);
        assertThat(engine.capturedInputBlocksLost() - base).as("the engine's subscriber dropped it too")
                .isEqualTo(1);
        engine.stopAudioOutput();
        backend.emit(BLOCK);
        assertThat(engine.capturedInputBlocksLost() - base).as("a stopped pump counts nothing").isEqualTo(1);
    }

    /**
     * On the stream's own device, a failed device callback that lost its
     * period's input counts one block in the take's dropouts, also when the
     * input queue's backlog filled its place so nothing starved; it is one
     * callback fault and no lost block in the health record (story 338,
     * ruling E211).
     */
    @Test
    void aFailedCallbackThatLostItsInputWhileTheQueueHeldABacklogCountsOneDropout() throws Exception {
        startTake(List.of(track("Primary", 0, 1, 0)), null);
        long base = engine.capturedInputBlocksLost();
        block(true, false);
        backend.emit(BLOCK);
        backend.emit(BLOCK);
        backend.renderGate.advance();
        failOneCallbackLosingItsInput(backend);

        backend.renderGate.advance();
        pipeline.awaitFlushed();

        assertThat(pipeline.getStarvedInputBlocks()).as("the backlog filled the place").isZero();
        assertThat(pipeline.getFileOverflowBlocks()).isZero();
        assertThat(engine.capturedInputBlocksLost() - base).as("Take dropouts = 0 + 0 + 1").isEqualTo(1);
        BackendThreadCounters health = engine.audioHealth().backendCounters();
        assertThat(health.callbackFaults()).isEqualTo(1);
        assertThat(health.inputBlocksLost()).isZero();
    }

    /**
     * The same failed callback with an empty queue: its place is a starved
     * block, and its loss counts one dropout before the capture rings
     * (counting it once in all is future story 463's).
     */
    @Test
    void aFailedCallbackThatLostItsInputWithAnEmptyQueueCountsOneDropout() throws Exception {
        startTake(List.of(track("Primary", 0, 1, 0)), null);
        long base = engine.capturedInputBlocksLost();
        block(true, false);
        failOneCallbackLosingItsInput(backend);

        backend.renderGate.advance();
        block(true, false);

        long lost = engine.capturedInputBlocksLost() - base;
        assertThat(lost).isEqualTo(1);
    }

    /**
     * An input opened beside the stream counts a failed device callback once,
     * as lost input through its health record, never also as a failed
     * callback of the stream's own device (story 337, rulings E49 and E86;
     * story 338, ruling E211).
     */
    @Test
    void aFailedCallbackOnAnInputBesideTheStreamCountsOnce() throws Exception {
        startTake(List.of(track("Primary", 0, 1, 0), track("Sibling", 0, 1, 1)), null);
        long base = engine.capturedInputBlocksLost();

        failOneCallbackLosingItsInput(backend.sibling);

        assertThat(engine.capturedInputBlocksLost() - base).isEqualTo(1);
        BackendThreadCounters health = engine.audioHealth().backendCounters();
        assertThat(health.inputBlocksLost()).as("an input's failed callback is lost input").isEqualTo(1);
        assertThat(health.callbackFaults()).isZero();
    }

    private static void failOneCallbackLosingItsInput(PatternBackend device) {
        device.failedCallbackInputBlocks = 1;
        device.counters = new BackendThreadCounters(1, 0, 0, 0, OptionalLong.empty(),
                Optional.of(new IllegalStateException("the device callback failed")), Optional.empty(),
                Optional.empty());
    }

    private static BackendThreadCounters threadCounters(long callbackFaults, long inputBlocksLost, long inputHalts) {
        return new BackendThreadCounters(callbackFaults, inputBlocksLost, inputHalts, 0, OptionalLong.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** Opens the stream for {@code tracks}, with no take, and waits for its first rendered block. */
    private void startStream(List<Track> tracks) throws Exception {
        engine.setGraph(transport, new Mixer(), tracks);
        engine.startAudioInputOutput(tracks);
        backend.renderGate.awaitRendered();
    }

    @Test
    void aHandleThatIsNotACaptureInputOfThisEngineIsRefused() throws Exception {
        Track primary = track("Primary", 0, 1, 0);
        startTake(List.of(primary), null);
        Object handle = input(true).handle();
        AudioEngine other = new AudioEngine(FORMAT);
        try {
            assertThatThrownBy(() -> other.declareInputStalled(handle, InputStallKind.STOPPED))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> engine.declareInputStalled(new Object(), InputStallKind.STOPPED))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            other.shutdown();
        }
        assertThat(engine.audioHealth().backendCounters().inputHalts()).isZero();
    }

    @Test
    void theStopsPositionIsMinutesSecondsAndTenthsOfTheTracksOwnFile() {
        assertThat(CaptureFlushService.takePosition(0, 48_000)).isEqualTo("0:00.0");
        assertThat(CaptureFlushService.takePosition(48_000L * 75 + 24_000, 48_000)).isEqualTo("1:15.5");
        assertThat(CaptureFlushService.takePosition(48_000L * 9 + 4_800, 48_000)).isEqualTo("0:09.1");
        assertThat(CaptureFlushService.takePosition(48_000L * 60 - 1, 48_000)).as("rounds up into the next minute")
                .isEqualTo("1:00.0");
        assertThat(CaptureFlushService.takePosition(44_100L * 3_725, 44_100)).isEqualTo("62:05.0");
    }

    private static BackendDropoutCounters inputDrops(long dropped) {
        return new BackendDropoutCounters(0, 0, 0, 0, dropped, false);
    }

    private Track track(String name, int first, int count, int device) {
        Track track = new Track(name, TrackType.AUDIO);
        track.setInputRouting(new InputRouting(first, count));
        track.setInputDevice(MultichannelInputCaptureStory326Test.patternInput(device));
        track.setArmed(true);
        return track;
    }

    /** Starts the stream and a take; the transport advances only through a mixer's render, so there is always one. */
    private void startTake(List<Track> tracks, Mixer mixer) throws Exception {
        engine.setGraph(transport, mixer != null ? mixer : new Mixer(), tracks);
        pipeline = new RecordingPipeline(engine, transport, FORMAT, directory, tracks);
        pipeline.setWarningSink(warnings::add);
        engine.startAudioInputOutput(tracks);
        backend.renderGate.awaitRendered();
        pipeline.prepare(engine.getCaptureHaltGeneration()).toCompletableFuture().get(5, TimeUnit.SECONDS);
        pipeline.beginCapture();
    }

    /**
     * One paced render block: the stream's input delivers its block first
     * when asked, the input beside the stream after the render when asked.
     */
    private void block(boolean streamInputDelivers, boolean siblingDelivers) throws Exception {
        if (streamInputDelivers) {
            backend.emit(BLOCK);
        }
        backend.renderGate.advance();
        if (siblingDelivers) {
            backend.sibling.emit(BLOCK);
        }
        pipeline.awaitFlushed();
    }

    private CaptureInputSnapshot input(boolean streamInput) {
        return engine.captureInputs().stream().filter(input -> input.streamInput() == streamInput)
                .findFirst().orElseThrow();
    }

    private TakeManifest stopAndRead() throws Exception {
        pipeline.requestStop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        pipeline.completeStop();
        return readManifest();
    }

    private TakeManifest readManifest() throws IOException {
        return TakeManifest.read(directory.resolve(TakeManifest.FILE_NAME));
    }
}
