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
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.RoundTripLatency;
import com.benesquivelmusic.daw.sdk.transport.PunchRegion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static com.benesquivelmusic.daw.core.recording.RecordedAudioTestSupport.audioOnDisk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    private static float[][] signal(float value) {
        float[][] signal = new float[1][256];
        Arrays.fill(signal[0], value);
        return signal;
    }
}
