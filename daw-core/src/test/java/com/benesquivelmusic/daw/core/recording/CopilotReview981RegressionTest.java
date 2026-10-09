package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.analysis.InputLevelMonitorRegistry;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.sdk.audio.AudioBackendException;
import com.benesquivelmusic.daw.sdk.audio.AudioBlock;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.RoundTripLatency;
import com.benesquivelmusic.daw.sdk.transport.PunchRegion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static com.benesquivelmusic.daw.core.recording.RecordedAudioTestSupport.audioOnDisk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

@ExtendWith(CaptureFlushThreadLeakGuard.class)
class CopilotReview981RegressionTest {
    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 16, 256);
    @TempDir Path directory;

    private enum ZeroInput { PRIMARY_ENUMERATED, PRIMARY_OPENED, SIBLING_ENUMERATED, SIBLING_OPENED }

    private static Track track(String name, int device) {
        Track track = new Track(name, TrackType.AUDIO);
        track.setArmed(true);
        track.setInputDevice(MultichannelInputCaptureStory326Test.patternInput(device));
        track.setInputRouting(new InputRouting(0, 1));
        return track;
    }

    private static AudioEngine engine(MultichannelInputCaptureStory326Test.PatternBackend backend) {
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(new StreamingProvision(backend.name(), List.of(
                new BackendStreamRung(backend, new DeviceId(backend.name(), "Interface A")))));
        return engine;
    }

    private RecordingPipeline ready(AudioEngine engine, Transport transport, List<Track> tracks, String name) throws Exception {
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, FORMAT, directory.resolve(name), tracks);
        pipeline.prepare().toCompletableFuture().get(5, TimeUnit.SECONDS);
        return pipeline;
    }

    private static void stop(RecordingPipeline pipeline, AudioEngine engine) throws Exception {
        pipeline.requestStop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        pipeline.completeStop();
        engine.stopAudioOutput();
        engine.shutdown();
    }

    @ParameterizedTest @EnumSource(ZeroInput.class)
    void previouslyValidatedZeroChannelInputsRecordClockedSilence(ZeroInput unavailable) throws Exception {
        var backend = new MultichannelInputCaptureStory326Test.PatternBackend();
        AudioEngine engine = engine(backend);
        Track primary = track("Primary", 0), sibling = track("Sibling", 1);
        Track instrument = track("Graph instrument", 0);
        instrument.setInputRouting(InputRouting.NONE);
        List<Track> tracks = List.of(primary, sibling, instrument);
        engine.validateInputRouting(tracks);
        boolean primaryUnavailable = unavailable.name().startsWith("PRIMARY");
        switch (unavailable) {
            case PRIMARY_ENUMERATED -> { backend.maximum = 0; backend.siblingMaximum = 8; }
            case PRIMARY_OPENED -> backend.openedWidthOverride = 0;
            case SIBLING_ENUMERATED -> { backend.siblingMaximum = 0; backend.siblingRate = 0; }
            case SIBLING_OPENED -> backend.siblingOpenedWidth = 0;
        }
        Transport transport = new Transport();
        engine.setGraph(transport, null, tracks);
        var meters = new InputLevelMonitorRegistry();
        engine.setInputLevelMonitorRegistry(meters);
        var warnings = new CopyOnWriteArrayList<String>();
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, FORMAT, directory, tracks);
        try {
            engine.startAudioInputOutput(tracks);
            engine.pauseAudioOutput();
            pipeline.setWarningSink(warnings::add);
            pipeline.prepare().toCompletableFuture().get(5, TimeUnit.SECONDS);
            pipeline.beginCapture();
            for (int block = 0; block < 2; block++) {
                float[][] input = primaryUnavailable ? new float[0][] : signal(.5f);
                engine.processBlock(input, new float[2][256], 256);
                if (primaryUnavailable) backend.sibling.emit(256);
            }
            pipeline.awaitFlushed();
            Track lost = primaryUnavailable ? primary : sibling;
            assertThat(pipeline.getSession(lost).getTotalSamplesRecorded()).isEqualTo(512);
            assertThat(audioOnDisk(pipeline.getSession(lost))[0]).hasSize(512).containsOnly(0f);
            assertThat(meters.get(lost.getId()).isRoutingUnavailable()).isTrue();
            assertThat(meters.get(instrument.getId()).isRoutingUnavailable()).isFalse();
            assertThat(warnings).singleElement().asString().contains(lost.getName(), "silence");
        } finally { stop(pipeline, engine); }
        assertThat(TakeManifest.read(directory.resolve(TakeManifest.FILE_NAME)).routingFlags()).singleElement()
                .satisfies(flag -> assertThat(flag.availableChannels()).isZero());
    }

    @Test
    void aProvenSiblingIsOpenedWhenASameNamedPlaybackEntryListsNoCaptureChannels() {
        // Java Sound on Windows lists a playback-only and a capture-only mixer under one name. The
        // playback entry's zero capture channels must not mark the proven capture source unavailable.
        var backend = spy(new MultichannelInputCaptureStory326Test.PatternBackend());
        doReturn(List.of(
                new AudioDeviceInfo(0, "Interface A", "Pattern", 8, 2, 48_000, List.of(), 0, 0),
                new AudioDeviceInfo(1, "Interface B", "Pattern", 0, 2, 48_000, List.of(), 0, 0),
                new AudioDeviceInfo(2, "Interface B", "Pattern", 8, 0, 48_000, List.of(), 0, 0)))
                .when(backend).listDevices();
        AudioEngine engine = engine(backend);
        List<Track> tracks = List.of(track("Primary", 0), track("Sibling", 1));
        try {
            engine.validateInputRouting(tracks);
            engine.startAudioInputOutput(tracks);

            assertThat(backend.sibling).as("the sibling source was created").isNotNull();
            assertThat(backend.sibling.input).isEqualTo("Interface B [Pattern]");
            assertThat(backend.sibling.openedInputChannels())
                    .as("opened and capturing, not skipped as known-unavailable").isEqualTo(1);
        } finally { engine.stopAudioOutput(); engine.shutdown(); }
    }

    @Test
    void aTooWideDefaultRouteOnASameNamedTwinIsRefusedBeforeTheOpen() {
        // The plan's own enumeration lists one 8-channel "Interface A", so its width check passes. The
        // engine's second enumeration then lists the Windows twin pair: a playback entry with zero capture
        // channels and a 2-channel capture entry under the same name. Read as the plan reads the label, the
        // head is the capture entry, so the 4-wide route is refused before any device opens.
        var backend = spy(new MultichannelInputCaptureStory326Test.PatternBackend());
        backend.maximum = 2;
        doReturn(List.of(new AudioDeviceInfo(0, "Interface A", "Pattern", 8, 2, 48_000, List.of(), 0, 0)),
                List.of(new AudioDeviceInfo(0, "Interface A", "Pattern", 0, 2, 48_000, List.of(), 0, 0),
                        new AudioDeviceInfo(1, "Interface A", "Pattern", 2, 0, 48_000, List.of(), 0, 0)))
                .when(backend).listDevices();
        AudioEngine engine = engine(backend);
        Track wide = new Track("Wide", TrackType.AUDIO);
        wide.setArmed(true);
        wide.setInputRouting(new InputRouting(0, 4));
        try {
            assertThatThrownBy(() -> engine.startAudioInputOutput(List.of(wide)))
                    .isInstanceOf(AudioBackendException.class)
                    .hasMessageContaining("exceeds 2 input channels")
                    .hasMessageNotContaining("opened");
            assertThat(backend.opens).as("refused by the pre-open resolve, not after opening").isZero();
        } finally { engine.stopAudioOutput(); engine.shutdown(); }
    }

    @Test
    void neverValidatedZeroChannelRouteIsRefusedAndReleased() {
        var backend = new MultichannelInputCaptureStory326Test.PatternBackend();
        backend.openedWidthOverride = 0;
        AudioEngine engine = engine(backend);
        try {
            assertThatThrownBy(() -> engine.startAudioInputOutput(List.of(track("Unproven", 0))))
                    .isInstanceOf(AudioBackendException.class).hasMessageContaining("no capture channels");
            assertThat(backend.open).isFalse();
            backend.maximum = 0;
            assertThatThrownBy(() -> engine.startAudioInputOutput(List.of(track("Unknown zero", 0))))
                    .isInstanceOf(AudioBackendException.class);
            assertThat(backend.open).isFalse();
        } finally { engine.stopAudioOutput(); engine.shutdown(); }
    }

    @Test
    void calibrationFollowsDeviceIdentityAcrossArmedOrderingIncludingZero() throws Exception {
        for (int frames : new int[]{360, 0}) {
            for (boolean includeCalibrated : new boolean[]{false, true}) {
                var backend = new MultichannelInputCaptureStory326Test.PatternBackend();
                backend.primaryLatency = new RoundTripLatency(64, 128, 16);
                AudioEngine engine = engine(backend);
                Track uncalibrated = track("B first", 1), calibrated = track("A second", 0);
                List<Track> tracks = includeCalibrated ? List.of(uncalibrated, calibrated) : List.of(uncalibrated);
                engine.startAudioInputOutput(tracks); engine.pauseAudioOutput();
                backend.failEnumeration = true;
                RecordingPipeline pipeline = new RecordingPipeline(engine, new Transport(), FORMAT,
                        directory.resolve(frames + "-" + includeCalibrated), tracks);
                pipeline.setReportedLatency(new DeviceId(backend.name(), "Interface A"), new RoundTripLatency(frames, 0, 0));
                pipeline.prepare().toCompletableFuture().get(5, TimeUnit.SECONDS);
                pipeline.beginCapture();
                try {
                    assertThat(pipeline.getSession(uncalibrated).getCompensationFrames()).isEqualTo(12);
                    if (includeCalibrated) assertThat(pipeline.getSession(calibrated).getCompensationFrames()).isEqualTo(frames);
                } finally { stop(pipeline, engine); }
            }
        }
    }

    @Test
    void captureWaitsForRecordAndActivationAndSharesTheFinalOrigin() throws Exception {
        var backend = new MultichannelInputCaptureStory326Test.PatternBackend();
        AudioEngine engine = engine(backend);
        Track primary = track("A", 0), sibling = track("B", 1);
        engine.startAudioInputOutput(List.of(primary, sibling)); engine.pauseAudioOutput();
        Transport transport = new Transport();
        RecordingPipeline pipeline = ready(engine, transport, List.of(primary, sibling), "gate");
        var origins = new CopyOnWriteArrayList<Long>();
        pipeline.getCaptureFlushService().setBlockObserver((sequence, frame, count) -> origins.add(frame));
        transport.addChangeListener(kind -> {
            if (kind == Transport.ChangeKind.STATE && transport.getState() == TransportState.RECORDING) {
                engine.getRecordingCallback().onAudioCaptured(signal(.25f), 256);
                backend.sibling.emit(256);
                transport.advancePosition(1);
            }
        });
        try {
            pipeline.beginCapture(() -> {
                engine.getRecordingCallback().onAudioCaptured(signal(.25f), 256);
                backend.sibling.emit(256);
                assertThat(pipeline.getCaptureRing().publishedBlocks()).isZero();
                assertThat(pipeline.getCaptureFlushService().appliedBlocks()).isZero();
            });
            engine.getRecordingCallback().onAudioCaptured(signal(.5f), 256);
            backend.sibling.emit(256);
            pipeline.awaitFlushed();
            assertThat(origins).containsExactly(24_000L, 24_000L);
            assertThat(audioOnDisk(pipeline.getSession(primary))[0]).hasSize(256).containsOnly(.5f);
            assertThat(pipeline.getSession(sibling).getTotalSamplesRecorded()).isEqualTo(256);
        } finally { stop(pipeline, engine); }
    }

    @Test
    void explicitSeekAfterActivationOverridesTheInitialOrigin() throws Exception {
        AudioEngine engine = new AudioEngine(FORMAT);
        Transport transport = new Transport();
        RecordingPipeline pipeline = ready(engine, transport, List.of(track("Seek", 0)), "seek");
        var origins = new CopyOnWriteArrayList<Long>();
        pipeline.getCaptureFlushService().setBlockObserver((sequence, frame, count) -> origins.add(frame));
        try {
            pipeline.beginCapture();
            transport.setPositionInBeats(2);
            engine.getRecordingCallback().onAudioCaptured(signal(.5f), 256);
            pipeline.awaitFlushed();
            assertThat(origins).containsExactly(48_000L);
        } finally { stop(pipeline, engine); }
    }

    @Test
    void activeSiblingReanchorsForForwardBackwardAndRepeatedSeeks() throws Exception {
        try (SiblingCapture capture = prepareSiblingCapture(new Transport(), "sibling-seeks")) {
            capture.pipeline().beginCapture();
            capture.emit(256);
            capture.transport().setPositionInBeats(2);
            capture.emit(128);
            capture.transport().setPositionInBeats(1);
            capture.emit(64);
            capture.transport().setPositionInBeats(1);
            capture.emit(96);
            capture.transport().advancePosition(1);
            capture.emit(256);

            assertThat(capture.frames()).containsExactly(0L, 48_000L, 24_000L, 24_000L, 24_096L);
        }
    }

    @Test
    void queuedSiblingSeekWaitsForPrimaryCommitAndKeepsItsTargetAfterPrimaryAdvances() throws Exception {
        try (SiblingCapture capture = prepareSiblingCapture(new Transport(), "queued-sibling-seek")) {
            capture.pipeline().beginCapture();
            capture.transport().setRealTimeClockActive(true);
            capture.emit(128);
            capture.transport().setPositionInBeats(2);
            capture.emit(128);
            capture.engine().getRecordingCallback().onAudioCaptured(signal(.5f), 256);
            capture.pipeline().awaitFlushed();
            assertThat(capture.transport().getPositionInBeats()).isZero();

            capture.transport().advancePosition(0.25);
            capture.transport().advancePosition(1);
            capture.emit(256);
            capture.emit(128);
            capture.engine().getRecordingCallback().onAudioCaptured(signal(.5f), 256);
            capture.pipeline().awaitFlushed();

            assertThat(capture.frames()).containsExactly(0L, 128L, 0L, 48_000L, 48_256L, 72_000L);
        }
    }

    @Test
    void queuedSiblingSeeksUseTheLastTargetAndRepeatedRequestsResetTheCursor() throws Exception {
        try (SiblingCapture capture = prepareSiblingCapture(new Transport(), "repeated-queued-seeks")) {
            capture.pipeline().beginCapture();
            capture.transport().setRealTimeClockActive(true);
            capture.emit(256);
            capture.transport().setPositionInBeats(2);
            capture.transport().setPositionInBeats(5);
            capture.emit(256);
            capture.transport().advancePosition(0.25);
            capture.emit(256);
            capture.transport().setPositionInBeats(5);
            capture.emit(256);
            capture.transport().advancePosition(0.25);
            capture.emit(256);

            assertThat(capture.frames()).containsExactly(0L, 256L, 120_000L, 120_256L, 120_000L);
        }
    }

    @Test
    void seekPendingAtActivationIsStillAppliedAfterTheFirstSiblingBlock() throws Exception {
        Transport transport = new Transport();
        transport.setPositionInBeats(1);
        try (SiblingCapture capture = prepareSiblingCapture(transport, "activation-pending-seek")) {
            capture.pipeline().beginCapture(() -> {
                transport.setRealTimeClockActive(true);
                transport.setPositionInBeats(3);
            });
            capture.emit(256);
            transport.advancePosition(0.25);
            transport.advancePosition(2);
            capture.emit(256);

            assertThat(capture.frames()).containsExactly(24_000L, 72_000L);
        }
    }

    @Test
    void seekBeforeTheFirstSiblingBlockUsesTheTargetEvenAfterPrimaryAdvances() throws Exception {
        try (SiblingCapture capture = prepareSiblingCapture(new Transport(), "seek-before-sibling")) {
            capture.pipeline().beginCapture();
            capture.transport().setPositionInBeats(2);
            capture.transport().advancePosition(1);
            capture.emit(256);
            capture.emit(128);

            assertThat(capture.frames()).containsExactly(48_000L, 48_256L);
        }
    }

    @Test
    void activationSeekCommittedBeforeTheFirstBlockSupersedesTheFrozenOrigin() throws Exception {
        Transport transport = new Transport();
        transport.setPositionInBeats(1);
        try (SiblingCapture capture = prepareSiblingCapture(transport, "activation-seek-committed")) {
            capture.pipeline().beginCapture(() -> {
                transport.setRealTimeClockActive(true);
                transport.setPositionInBeats(3);
            });
            transport.advancePosition(0.25);
            capture.engine().getRecordingCallback().onAudioCaptured(signal(.5f), 256);
            capture.pipeline().awaitFlushed();
            capture.emit(256);
            capture.emit(256);

            assertThat(capture.frames()).containsExactly(72_000L, 72_000L, 72_256L);
        }
    }

    @Test
    void seekOnAnOverflowedSiblingBlockStillCountsItsDeliveredFrames() throws Exception {
        try (SiblingCapture capture = prepareSiblingCapture(new Transport(), "overflowed-seek")) {
            capture.pipeline().beginCapture();
            CaptureFlushService flush = capture.pipeline().getCaptureFlushService();
            int capacity = capture.pipeline().getCaptureRing().capacity();
            flush.setDrainPaused(true);
            try {
                for (int block = 0; block < capacity; block++) {
                    capture.backend().sibling.emit(256);
                }
                capture.transport().setPositionInBeats(2);
                capture.backend().sibling.emit(128);
            } finally {
                flush.setDrainPaused(false);
            }
            capture.pipeline().awaitFlushed();
            capture.emit(64);

            assertThat(capture.frames()).hasSize(capacity + 1).last().isEqualTo(48_128L);
        }
    }

    @Test
    void siblingSeekReanchorsBeforeLoopWrapping() throws Exception {
        Transport transport = new Transport();
        transport.setPositionInBeats(1);
        transport.setLoopWindow(true, 1, 1 + 1024.0 / 24_000);
        try (SiblingCapture capture = prepareSiblingCapture(transport, "looped-sibling-seek")) {
            capture.pipeline().beginCapture();
            capture.emit(256);
            capture.emit(256);
            transport.setPositionInBeats(1);
            capture.emit(256);
            capture.emit(768);
            capture.emit(256);

            assertThat(capture.frames()).containsExactly(24_000L, 24_256L, 24_000L, 24_256L, 24_000L);
        }
    }

    @Test
    void siblingSeeksEnterLeaveAndReenterThePunchRegion() throws Exception {
        Transport transport = new Transport();
        transport.setPunchRegion(new PunchRegion(48_000, 48_256, true));
        try (SiblingCapture capture = prepareSiblingCapture(transport, "punched-sibling-seek")) {
            capture.pipeline().beginCapture();
            capture.emit(256);
            transport.setPositionInBeats(2);
            capture.emit(256);
            assertThat(capture.pipeline().getSession(capture.sibling()).getTotalSamplesRecorded()).isEqualTo(256);
            transport.setPositionInBeats(0);
            capture.emit(256);
            assertThat(capture.pipeline().getSession(capture.sibling()).getTotalSamplesRecorded()).isEqualTo(256);
            transport.setPositionInBeats(2);
            capture.emit(256);
            assertThat(capture.pipeline().getSession(capture.sibling()).getTotalSamplesRecorded()).isEqualTo(512);
        }
    }

    private SiblingCapture prepareSiblingCapture(Transport transport, String name) throws Exception {
        var backend = new MultichannelInputCaptureStory326Test.PatternBackend();
        AudioEngine engine = engine(backend);
        Track primary = track("Primary", 0), sibling = track("Sibling", 1);
        List<Track> tracks = List.of(primary, sibling);
        engine.setGraph(transport, null, tracks);
        engine.startAudioInputOutput(tracks);
        engine.pauseAudioOutput();
        RecordingPipeline pipeline = ready(engine, transport, tracks, name);
        List<Long> frames = new CopyOnWriteArrayList<>();
        pipeline.getCaptureFlushService().setBlockObserver((sequence, frame, count) -> frames.add(frame));
        return new SiblingCapture(backend, engine, transport, pipeline, sibling, frames);
    }

    private record SiblingCapture(MultichannelInputCaptureStory326Test.PatternBackend backend,
                                  AudioEngine engine, Transport transport, RecordingPipeline pipeline,
                                  Track sibling, List<Long> frames) implements AutoCloseable {
        void emit(int numFrames) {
            backend.sibling.emit(numFrames);
            pipeline.awaitFlushed();
        }

        @Override
        public void close() throws Exception {
            stop(pipeline, engine);
        }
    }

    @Test
    void failedRecordTransitionPublishesNothingAndDiscardsBothSources() throws Exception {
        var backend = new MultichannelInputCaptureStory326Test.PatternBackend();
        AudioEngine engine = engine(backend);
        Track primary = track("A", 0), sibling = track("B", 1);
        engine.startAudioInputOutput(List.of(primary, sibling)); engine.pauseAudioOutput();
        Transport transport = new Transport();
        RecordingPipeline pipeline = ready(engine, transport, List.of(primary, sibling), "failed");
        transport.addChangeListener(kind -> {
            if (kind == Transport.ChangeKind.STATE && transport.getState() == TransportState.RECORDING) {
                engine.getRecordingCallback().onAudioCaptured(signal(.25f), 256);
                backend.sibling.emit(256);
                throw new IllegalStateException("record listener refused");
            }
        });
        try {
            assertThatThrownBy(pipeline::beginCapture).hasMessageContaining("record listener refused");
            pipeline.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(pipeline.getCaptureRing().publishedBlocks()).isZero();
            assertThat(pipeline.getCaptureFlushService().appliedBlocks()).isZero();
            assertThat(engine.getRecordingCallback()).isNull();
            try (var files = Files.walk(directory.resolve("failed"))) {
                assertThat(files.filter(Files::isRegularFile).toList()).isEmpty();
            }
        } finally { engine.stopAudioOutput(); engine.shutdown(); }
    }

    private enum Termination {
        FAILURE, COMPLETION;

        void signal(Flow.Subscriber<? super AudioBlock> subscriber) {
            switch (this) {
                case FAILURE -> subscriber.onError(new AudioBackendException("Interface B publisher failed"));
                case COMPLETION -> subscriber.onComplete();
            }
        }
    }

    /**
     * Interface B's live block is held inside its track callback while the terminal signal arrives and an
     * output period runs. That period belongs to the block: the output must not clock silence beside it (two
     * producers in one CaptureCallback), and once the block has left, every later period is clocked. A terminal
     * signal and a block from the subscriber of a replaced stream change nothing. The publisher modelled here
     * breaks Flow's serial-signal rule (a terminal signal while its onNext still runs); SubmissionPublisher-backed
     * siblings cannot reach this path, so it is defensive, and this is the test the earlier two-write handoff
     * fails.
     */
    @ParameterizedTest @EnumSource(Termination.class)
    void terminationDuringAnInFlightSiblingBlockHandsTheSourceToTheOutputClockWhenTheBlockLeaves(Termination termination)
            throws Exception {
        DyingSibling take = startDyingSibling("in-flight-" + termination);
        try (take) {
            take.period();
            take.period();
            ProducerFence fence = take.fence();
            var delivery = new FutureTask<Void>(() -> { take.backend().sibling.emit(256); return null; });
            Thread publisher = Thread.ofPlatform().name("Interface B publisher").unstarted(delivery);
            fence.holdOn = publisher;
            publisher.start();
            Throwable primary = null;
            try {
                assertThat(fence.held.await(5, TimeUnit.SECONDS)).as("Interface B's third block entered its callback").isTrue();
                termination.signal(take.subscriber());
                termination.signal(take.stale());
                take.stale().onNext(block(take.backend().sibling.openedWidth));
                take.engine().processBlock(signal(.5f), new float[2][256], 256);
                assertThat(fence.overlaps.get()).as("the output clocked silence beside the in-flight block").isZero();
            } catch (Throwable failure) {
                primary = failure;
                throw failure;
            } finally {
                fence.release.countDown();
                joinReleased(publisher, primary);
            }
            delivery.get(5, TimeUnit.SECONDS);
            take.period(); // Interface B's publisher is dead: only the output delivers from here on.
            take.period();
            take.assertFullLengthWithSilenceAfter(3, 5);
        }
        assertOneRoutingFlagPerSibling("in-flight-" + termination, take.siblings());
    }

    /**
     * Interface B's error signal is held in the warning its onError logs, which runs after the handoff; an
     * output period and a block delivered during that hold fall after the handoff, so the period is clocked
     * silent and the block refused. This guards against splitting the handoff around the warning: refusing
     * blocks first and clocking the output only later loses that period. The earlier two-write handoff's own
     * window had no observable hook here (markUnavailable ran before it, the warning after it), so the guard
     * for that window is structural: the single CAS in the handoff.
     */
    @Test
    void anOutputPeriodDuringASiblingsErrorSignalIsClockedSilentAndNeverSkipped() throws Exception {
        Logger engineLog = Logger.getLogger(AudioEngine.class.getName());
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var terminating = new AtomicReference<Thread>();
        Handler holdTheErrorSignal = new Handler() {
            @Override public void publish(LogRecord record) {
                if (Thread.currentThread() != terminating.get() || !"Input stream failed".equals(record.getMessage())) return;
                held.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        engineLog.addHandler(holdTheErrorSignal);
        DyingSibling take;
        try {
            take = startDyingSibling("error-signal");
        } catch (Exception | Error failure) {
            engineLog.removeHandler(holdTheErrorSignal);
            throw failure;
        }
        try (take) {
            take.period();
            take.period();
            var failure = new FutureTask<Void>(() -> { Termination.FAILURE.signal(take.subscriber()); return null; });
            Thread publisher = Thread.ofPlatform().name("Interface B publisher").unstarted(failure);
            terminating.set(publisher);
            publisher.start();
            Throwable primary = null;
            try {
                assertThat(held.await(5, TimeUnit.SECONDS)).as("Interface B's error signal reached its warning").isTrue();
                take.backend().sibling.emit(256); // a block delivered concurrently with the error signal: refused
                take.engine().processBlock(signal(.5f), new float[2][256], 256);
            } catch (Throwable thrown) {
                primary = thrown;
                throw thrown;
            } finally {
                release.countDown();
                joinReleased(publisher, primary);
            }
            failure.get(5, TimeUnit.SECONDS);
            take.period();
            take.assertFullLengthWithSilenceAfter(2, 4);
        } finally {
            engineLog.removeHandler(holdTheErrorSignal);
        }
        assertOneRoutingFlagPerSibling("error-signal", take.siblings());
    }

    /**
     * Stopping the stream closes Interface B's source for good: a subscription that reaches its subscriber
     * only after the stop is cancelled at once and never asked for blocks.
     */
    @Test
    void aSubscriptionReachingAStoppedStreamsSiblingIsCancelledAndNeverRequested() throws Exception {
        var backend = new MultichannelInputCaptureStory326Test.PatternBackend();
        AudioEngine engine = engine(backend);
        List<Track> tracks = List.of(track("Primary", 0), track("Sibling", 1));
        engine.setGraph(new Transport(), null, tracks);
        try {
            engine.startAudioInputOutput(tracks);
            engine.pauseAudioOutput();
            Flow.Subscriber<? super AudioBlock> stopped = backend.sibling.subscribers.getFirst();
            engine.stopAudioOutput();
            var requested = new AtomicLong();
            var cancelled = new AtomicBoolean();
            stopped.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) { requested.addAndGet(n); }
                @Override public void cancel() { cancelled.set(true); }
            });
            assertThat(cancelled).as("the stopped stream's subscriber cancels the late subscription").isTrue();
            assertThat(requested.get()).as("and requests no blocks through it").isZero();
        } finally { engine.stopAudioOutput(); engine.shutdown(); }
    }

    /** Primary on Interface A, two tracks on Interface B, driven one output period at a time. */
    private DyingSibling startDyingSibling(String name) throws Exception {
        var backend = new MultichannelInputCaptureStory326Test.PatternBackend();
        AudioEngine engine = engine(backend);
        Track primary = track("Primary", 0), left = track("Sibling left", 1), right = track("Sibling right", 1);
        right.setInputRouting(new InputRouting(1, 1));
        List<Track> tracks = List.of(primary, left, right);
        Transport transport = new Transport();
        engine.setGraph(transport, null, tracks);
        var meters = new InputLevelMonitorRegistry();
        engine.setInputLevelMonitorRegistry(meters);
        engine.startAudioInputOutput(tracks);
        engine.pauseAudioOutput();
        Flow.Subscriber<? super AudioBlock> stale = backend.sibling.subscribers.getFirst();
        engine.stopAudioOutput();
        engine.startAudioInputOutput(tracks);
        engine.pauseAudioOutput();
        var warnings = new CopyOnWriteArrayList<String>();
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, FORMAT, directory.resolve(name), tracks);
        pipeline.setWarningSink(warnings::add);
        try {
            pipeline.prepare().toCompletableFuture().get(5, TimeUnit.SECONDS);
            pipeline.beginCapture();
        } catch (Exception | Error failure) {
            stop(pipeline, engine);
            throw failure;
        }
        return new DyingSibling(backend, engine, pipeline, meters, primary, List.of(left, right), warnings,
                backend.sibling.subscribers.getFirst(), stale);
    }

    private record DyingSibling(MultichannelInputCaptureStory326Test.PatternBackend backend, AudioEngine engine,
                                RecordingPipeline pipeline, InputLevelMonitorRegistry meters, Track primary,
                                List<Track> siblings, List<String> warnings,
                                Flow.Subscriber<? super AudioBlock> subscriber,
                                Flow.Subscriber<? super AudioBlock> stale) implements AutoCloseable {
        /** One output period: the output callback, then Interface B's block for it. */
        void period() {
            engine.processBlock(signal(.5f), new float[2][256], 256);
            backend.sibling.emit(256);
        }

        /** Wraps Interface B's installed track callback; the take's own CaptureCallback still records. */
        ProducerFence fence() throws ReflectiveOperationException {
            Field installed = AudioEngine.class.getDeclaredField("additionalRecordingCallbacks");
            installed.setAccessible(true);
            var callbacks = (AudioEngine.RecordingCallback[]) installed.get(engine);
            assertThat(callbacks).hasSize(1);
            var fence = new ProducerFence(callbacks[0]);
            engine.setAdditionalRecordingCallbacks(new AudioEngine.RecordingCallback[]{fence});
            return fence;
        }

        void assertFullLengthWithSilenceAfter(int liveBlocks, int periods) {
            pipeline.awaitFlushed();
            assertThat(pipeline.getSession(primary).getTotalSamplesRecorded()).isEqualTo(periods * 256L);
            for (Track sibling : siblings) {
                assertThat(pipeline.getSession(sibling).getTotalSamplesRecorded())
                        .as("'%s' owns every output period exactly once", sibling.getName())
                        .isEqualTo(periods * 256L);
                float[] recorded = audioOnDisk(pipeline.getSession(sibling))[0];
                assertThat(recorded).hasSize(periods * 256);
                assertThat(Arrays.copyOfRange(recorded, 0, liveBlocks * 256)).containsOnly(RecordedAudioTestSupport.decoded16(.9f));
                assertThat(Arrays.copyOfRange(recorded, liveBlocks * 256, recorded.length)).containsOnly(0f);
                assertThat(meters.get(sibling.getId()).isRoutingUnavailable()).isTrue();
                assertThat(warnings).filteredOn(warning -> warning.contains("'" + sibling.getName() + "'"))
                        .singleElement().asString().contains("Interface B", "silence");
            }
            assertThat(meters.get(primary.getId()).isRoutingUnavailable()).isFalse();
            assertThat(warnings).hasSize(siblings.size());
        }

        @Override
        public void close() throws Exception {
            stop(pipeline, engine);
        }
    }

    /**
     * Joins a publisher thread whose latch the caller has just released; failing to join is added to the
     * test's own failure when there is one, so it never masks it.
     */
    private static void joinReleased(Thread publisher, Throwable primary) {
        AssertionError unjoined = null;
        try {
            publisher.join(5_000);
            if (publisher.isAlive()) unjoined = new AssertionError("'" + publisher.getName() + "' still runs 5 s after its release");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            unjoined = new AssertionError("interrupted while joining '" + publisher.getName() + "'", interrupted);
        }
        if (unjoined == null) return;
        if (primary != null) primary.addSuppressed(unjoined);
        else throw unjoined;
    }

    private void assertOneRoutingFlagPerSibling(String name, List<Track> siblings) throws Exception {
        List<TakeManifest.RoutingFlag> flags = TakeManifest.read(directory.resolve(name).resolve(TakeManifest.FILE_NAME)).routingFlags();
        assertThat(flags).extracting(TakeManifest.RoutingFlag::trackId)
                .containsExactlyInAnyOrderElementsOf(siblings.stream().map(Track::getId).toList());
        assertThat(flags).allSatisfy(flag -> {
            assertThat(flag.device()).contains("Interface B");
            assertThat(flag.availableChannels()).isZero();
        });
    }

    /**
     * One source's track callback, entered by at most one producer at a time: a second entry is counted and
     * dropped instead of reaching the take's single-producer ring. {@code holdOn}'s next entry is held.
     */
    private static final class ProducerFence implements AudioEngine.RecordingCallback {
        private final AudioEngine.RecordingCallback delegate;
        private final AtomicInteger inside = new AtomicInteger();
        final AtomicInteger overlaps = new AtomicInteger();
        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile Thread holdOn;

        ProducerFence(AudioEngine.RecordingCallback delegate) { this.delegate = delegate; }

        @Override public void onAudioCaptured(float[][] inputBuffer, int numFrames) {
            if (inside.getAndIncrement() != 0) {
                overlaps.incrementAndGet();
                inside.decrementAndGet();
                return;
            }
            try {
                if (Thread.currentThread() == holdOn) {
                    holdOn = null;
                    held.countDown();
                    try { release.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                delegate.onAudioCaptured(inputBuffer, numFrames);
            } finally {
                inside.decrementAndGet();
            }
        }
    }

    private static AudioBlock block(int width) {
        float[] samples = new float[width * 256];
        Arrays.fill(samples, .4f);
        return new AudioBlock(48_000, width, 256, samples);
    }

    private static float[][] signal(float value) {
        float[][] signal = new float[1][256];
        Arrays.fill(signal[0], value);
        return signal;
    }
}
