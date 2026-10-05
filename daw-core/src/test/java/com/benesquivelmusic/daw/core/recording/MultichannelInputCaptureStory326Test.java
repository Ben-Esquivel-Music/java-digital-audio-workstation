package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.*;
import com.benesquivelmusic.daw.core.analysis.InputLevelMonitorRegistry;
import com.benesquivelmusic.daw.core.track.*;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.audio.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static com.benesquivelmusic.daw.core.recording.RecordedAudioTestSupport.*;

@ExtendWith(CaptureFlushThreadLeakGuard.class)
class MultichannelInputCaptureStory326Test {
    @TempDir Path directory;
    private static final com.benesquivelmusic.daw.core.audio.AudioFormat FORMAT =
            new com.benesquivelmusic.daw.core.audio.AudioFormat(48000, 2, 16, 256);

    private Track track(String name, int first, int count, int device) {
        Track track = new Track(name, TrackType.AUDIO);
        track.setInputRouting(new InputRouting(first, count));
        track.setInputDeviceIndex(device); track.setArmed(true);
        return track;
    }
    private AudioEngine engine(PatternBackend backend, int outputs) {
        AudioEngine engine = new AudioEngine(new com.benesquivelmusic.daw.core.audio.AudioFormat(48000, outputs, 16, 256));
        engine.setStreamingProvision(new StreamingProvision(backend.name(), List.of(new BackendStreamRung(backend,
                new DeviceId(backend.name(), "Interface A")))));
        return engine;
    }
    private RecordingPipeline pipeline(AudioEngine engine, List<Track> tracks) throws Exception {
        RecordingPipeline pipeline = new RecordingPipeline(engine, new Transport(), engine.getFormat(), directory, tracks);
        pipeline.prepare().toCompletableFuture().get(5, TimeUnit.SECONDS);
        pipeline.beginCapture();
        return pipeline;
    }
    private static void stop(RecordingPipeline pipeline, AudioEngine engine) throws Exception {
        pipeline.requestStop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        pipeline.completeStop(); engine.stopAudioOutput(); engine.shutdown();
    }

    @Test void inputWidthFollowsRoutingsForMonoStereoAndFourChannelProjects() {
        for (int outputs : new int[]{1, 2, 4}) {
            PatternBackend backend = new PatternBackend();
            AudioEngine engine = engine(backend, outputs);
            try {
                engine.startAudioInputOutput(List.of(track("Near",0,2,0), track("Far",6,2,0)));
                assertThat(backend.openedWidth).isEqualTo(8);
                assertThat(backend.outputWidth).isEqualTo(outputs);
                engine.startAudioInputOutput(List.of(track("Near",0,2,0)));
                assertThat(backend.openedWidth).isEqualTo(2);
                engine.startAudioInputOutput(List.of(track("Mono",0,1,0)));
                assertThat(backend.openedWidth).isEqualTo(1);
            } finally { engine.stopAudioOutput(); engine.shutdown(); }
        }
    }

    @Test void aDefaultRouteAcceptedOnTheWideFallbackStartsOnThatSameBackend() {
        PatternBackend head = new PatternBackend(); head.maximum = 2;
        PatternBackend fallback = new PatternBackend();
        AudioEngine engine = engine(head, 2);
        engine.setStreamingProvision(new StreamingProvision(head.name(), List.of(
                new BackendStreamRung(head, new DeviceId(head.name(), "Interface A")),
                new BackendStreamRung(fallback, new DeviceId(fallback.name(), "Interface A")))));
        Track far = track("Fallback inputs seven and eight", 6, 2, -1);
        try {
            engine.validateInputRouting(List.of(far));
            engine.startAudioInputOutput(List.of(far));
            assertThat(head.opens).isZero();
            assertThat(fallback.openedWidth).isEqualTo(8);
            assertThat(engine.getCaptureRoutingPlan().backend()).isSameAs(fallback);
        } finally { engine.stopAudioOutput(); engine.shutdown(); }
    }

    @Test void defaultRoutesWalkPastAnInputResolverFailureWhileExplicitRoutesAreRefusedByName() {
        PatternBackend head = new PatternBackend(); head.failInputResolution = true;
        PatternBackend fallback = new PatternBackend();
        AudioEngine engine = engine(head, 2);
        engine.setStreamingProvision(new StreamingProvision(head.name(), List.of(
                new BackendStreamRung(head, new DeviceId(head.name(), "Interface A")),
                new BackendStreamRung(fallback, new DeviceId(fallback.name(), "Interface A")))));
        Track far = track("Default fallback", 6, 2, -1);
        try {
            engine.validateInputRouting(List.of(far));
            engine.startAudioInputOutput(List.of(far));
            assertThat(fallback.openedWidth).isEqualTo(8);
            assertThat(head.opens).isZero();
            assertThatThrownBy(() -> engine.validateInputRouting(List.of(track("Pinned input", 0, 1, 0))))
                    .isInstanceOf(AudioBackendException.class).hasMessageContaining("Pinned input", "Interface A", "resolution failed");
        } finally { engine.stopAudioOutput(); engine.shutdown(); }
    }

    @Test void inputsSevenAndEightReachTheTakeThroughTheRealRenderPump() throws Exception {
        PatternBackend backend = new PatternBackend();
        AudioEngine engine = engine(backend,2);
        Track far = track("Inputs seven and eight",6,2,0);
        engine.startAudioInputOutput(List.of(far));
        RecordingPipeline pipeline = pipeline(engine,List.of(far));
        try {
            backend.emit(256);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean found = false;
            while (!found && System.nanoTime() < deadline) {
                pipeline.awaitFlushed();
                float[][] samples = audioOnDisk(pipeline.getSession(far));
                for (float sample : samples[0]) if (sample == decoded16(.7f)) found = true;
                if (!found) Thread.sleep(5);
            }
            assertThat(found).as("channel seven survives the backend, pump, raw ring and WAV writer").isTrue();
            engine.pauseAudioOutput(); pipeline.awaitFlushed();
            float[][] samples = audioOnDisk(pipeline.getSession(far));
            assertThat(samples.length).isEqualTo(2);
            assertThat(samples[0].length).isEqualTo(pipeline.getSession(far).getTotalSamplesRecorded());
            assertThat(pipeline.getSession(far).getFormat().sampleRate()).isEqualTo(48000);
            boolean matchedPair = false;
            for (int f = 0; f < samples[0].length; f++) if (samples[0][f] == decoded16(.7f) && samples[1][f] == decoded16(.8f)) matchedPair = true;
            assertThat(matchedPair).isTrue();
        } finally { stop(pipeline,engine); }
    }

    @Test void meteringReadsSevenAndEightAndClearsHeldLevelsWhenOneInputDisappears() throws Exception {
        PatternBackend backend=new PatternBackend(); AudioEngine engine=engine(backend,2);
        Track far=track("Far",6,2,0); InputLevelMonitorRegistry meters=new InputLevelMonitorRegistry();
        engine.setInputLevelMonitorRegistry(meters); engine.setGraph(new Transport(),null,List.of(far));
        engine.startAudioInputOutput(List.of(far));
        try {
            backend.emit(256);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while ((meters.get(far.getId())==null || meters.get(far.getId()).snapshot().peakDbfs() < -10) && System.nanoTime()<deadline) Thread.sleep(2);
            engine.pauseAudioOutput();
            assertThat(meters.get(far.getId()).snapshot().peakDbfs()).isGreaterThan(-10);
            assertThat(meters.get(far.getId()).isRoutingUnavailable()).isFalse();
            engine.processBlock(new float[7][256],new float[2][256],256);
            assertThat(meters.get(far.getId()).isRoutingUnavailable()).isTrue();
            assertThat(meters.get(far.getId()).snapshot()).isEqualTo(com.benesquivelmusic.daw.sdk.analysis.InputLevelMeter.SILENCE);
        } finally { engine.stopAudioOutput();engine.shutdown(); }
    }

    @Test void aGraphInstrumentInAMixedCapturePlanNeverGetsAPhysicalInputWarning() {
        PatternBackend backend = new PatternBackend(); AudioEngine engine = engine(backend, 2);
        Track physical = track("Physical", 6, 2, 0);
        Track instrument = track("Graph instrument", 0, 2, -1); instrument.setInputRouting(InputRouting.NONE);
        InputLevelMonitorRegistry meters = new InputLevelMonitorRegistry();
        engine.setInputLevelMonitorRegistry(meters); engine.setGraph(new Transport(), null, List.of(instrument, physical));
        engine.startAudioInputOutput(List.of(instrument, physical)); engine.pauseAudioOutput();
        try {
            meters.getOrCreate(instrument.getId()).setRoutingUnavailable(true);
            engine.processBlock(new float[8][256], new float[2][256], 256);
            assertThat(meters.get(instrument.getId()).isRoutingUnavailable()).isFalse();
            assertThat(meters.get(physical.getId()).isRoutingUnavailable()).isFalse();
        } finally { engine.stopAudioOutput(); engine.shutdown(); }
    }

    @ParameterizedTest
    @EnumSource(InputTermination.class)
    void siblingInputTerminationSilencesOnlyThatSourcesMeters(InputTermination termination) {
        PatternBackend backend=new PatternBackend();AudioEngine engine=engine(backend,2);
        Track primary=track("Primary",0,1,0),siblingLeft=track("Sibling left",0,1,1),siblingRight=track("Sibling right",1,1,1);
        List<Track> tracks=List.of(primary,siblingLeft,siblingRight);
        InputLevelMonitorRegistry meters=new InputLevelMonitorRegistry();
        engine.setInputLevelMonitorRegistry(meters);engine.setGraph(new Transport(),null,tracks);
        try {
            engine.startAudioInputOutput(tracks);engine.pauseAudioOutput();
            engine.processBlock(new float[][]{filled(.1f,256)},new float[2][256],256);
            backend.sibling.emit(256);
            var primaryLevel=meters.get(primary.getId()).snapshot();
            for(Track track:List.of(siblingLeft,siblingRight)) {
                assertThat(meters.get(track.getId()).isRoutingUnavailable()).isFalse();
                assertThat(meters.get(track.getId()).snapshot().peakDbfs()).isGreaterThan(-10);
            }
            termination.signal(backend.sibling.subscribers.getFirst());
            backend.sibling.emit(256);
            for(Track track:List.of(siblingLeft,siblingRight)) {
                assertThat(meters.get(track.getId()).isRoutingUnavailable()).isTrue();
                assertThat(meters.get(track.getId()).snapshot()).isEqualTo(com.benesquivelmusic.daw.sdk.analysis.InputLevelMeter.SILENCE);
                assertThat(meters.get(track.getId()).routingDescription()).contains(track.getName(),"Interface B");
            }
            assertThat(meters.get(primary.getId()).isRoutingUnavailable()).isFalse();
            assertThat(meters.get(primary.getId()).snapshot()).isEqualTo(primaryLevel);
            InputLevelMonitorRegistry rebound=new InputLevelMonitorRegistry();
            engine.setInputLevelMonitorRegistry(rebound);engine.setTracks(tracks);
            engine.processBlock(new float[][]{filled(.1f,256)},new float[2][256],256);
            for(Track track:List.of(siblingLeft,siblingRight)) {
                assertThat(rebound.get(track.getId()).isRoutingUnavailable()).isTrue();
                assertThat(rebound.get(track.getId()).snapshot()).isEqualTo(com.benesquivelmusic.daw.sdk.analysis.InputLevelMeter.SILENCE);
            }
            assertThat(rebound.get(primary.getId()).isRoutingUnavailable()).isFalse();
            assertThat(rebound.get(primary.getId()).snapshot().peakDbfs()).isGreaterThan(-30);
            siblingLeft.setInputRouting(InputRouting.NONE);
            engine.processBlock(new float[][]{filled(.1f,256)},new float[2][256],256);
            assertThat(rebound.get(siblingLeft.getId()).isRoutingUnavailable()).isFalse();
            assertThat(rebound.get(siblingLeft.getId()).snapshot()).isEqualTo(com.benesquivelmusic.daw.sdk.analysis.InputLevelMeter.SILENCE);
            assertThat(rebound.get(siblingRight.getId()).isRoutingUnavailable()).isTrue();
            siblingLeft.setInputRouting(new InputRouting(0,1));
            engine.processBlock(new float[][]{filled(.1f,256)},new float[2][256],256);
            assertThat(rebound.get(siblingLeft.getId()).isRoutingUnavailable()).isTrue();
            assertThat(rebound.get(siblingLeft.getId()).snapshot()).isEqualTo(com.benesquivelmusic.daw.sdk.analysis.InputLevelMeter.SILENCE);
            engine.stopAudioOutput();engine.startAudioOutput();engine.pauseAudioOutput();
            engine.processBlock(new float[][]{filled(.1f,256),filled(.2f,256)},new float[2][256],256);
            for(Track track:tracks) {
                assertThat(rebound.get(track.getId()).isRoutingUnavailable()).isFalse();
                assertThat(rebound.get(track.getId()).snapshot().peakDbfs()).isGreaterThan(-30);
            }
        } finally {engine.stopAudioOutput();engine.shutdown();}
    }

    @ParameterizedTest
    @EnumSource(InputTermination.class)
    void oldSiblingTerminationCannotSilenceStoppedOrReplacementMeters(InputTermination termination) {
        PatternBackend backend=new PatternBackend();AudioEngine engine=engine(backend,2);
        Track primary=track("Primary",0,1,0),sibling=track("Sibling",0,1,1);
        List<Track> tracks=List.of(primary,sibling);
        InputLevelMonitorRegistry meters=new InputLevelMonitorRegistry();
        engine.setInputLevelMonitorRegistry(meters);engine.setGraph(new Transport(),null,tracks);
        try {
            engine.startAudioInputOutput(tracks);engine.pauseAudioOutput();
            backend.sibling.emit(256);
            Flow.Subscriber<? super AudioBlock> old=backend.sibling.subscribers.getFirst();
            var stoppedLevel=meters.get(sibling.getId()).snapshot();
            engine.stopAudioOutput();
            termination.signal(old);
            assertThat(meters.get(sibling.getId()).isRoutingUnavailable()).isFalse();
            assertThat(meters.get(sibling.getId()).snapshot()).isEqualTo(stoppedLevel);
            engine.startAudioInputOutput(tracks);engine.pauseAudioOutput();
            engine.processBlock(new float[][]{filled(.1f,256)},new float[2][256],256);
            backend.sibling.emit(256);
            var replacementLevel=meters.get(sibling.getId()).snapshot();
            termination.signal(old);
            assertThat(meters.get(sibling.getId()).isRoutingUnavailable()).isFalse();
            assertThat(meters.get(sibling.getId()).snapshot()).isEqualTo(replacementLevel);
            assertThat(meters.get(primary.getId()).isRoutingUnavailable()).isFalse();
        } finally {engine.stopAudioOutput();engine.shutdown();}
    }

    private enum InputTermination {
        FAILURE, COMPLETION;

        void signal(Flow.Subscriber<? super AudioBlock> subscriber) {
            switch(this) {
                case FAILURE -> subscriber.onError(new AudioBackendException("input publisher failed"));
                case COMPLETION -> subscriber.onComplete();
            }
        }
    }

    @Test void secondInputOpenFailureRollsBackBothOwnedStreams() {
        PatternBackend backend=new PatternBackend();backend.failSiblingOpen=true; AudioEngine engine=engine(backend,2);
        try {
            assertThatThrownBy(()->engine.startAudioInputOutput(List.of(track("A",0,1,0),track("B",0,1,1)))).hasMessageContaining("second input refused");
            assertThat(backend.open).isFalse();assertThat(backend.sibling.open).isFalse();
            assertThat(backend.subscribers).isEmpty();assertThat(backend.sibling.subscribers).isEmpty();
        } finally {engine.stopAudioOutput();engine.shutdown();}
    }

    @Test void lateOldInputSubscriberCannotWriteIntoAReplacementTake() throws Exception {
        PatternBackend backend=new PatternBackend();AudioEngine engine=engine(backend,2);
        Track a=track("A",0,1,0),b=track("B",0,1,1);
        engine.startAudioInputOutput(List.of(a,b));engine.pauseAudioOutput();
        Flow.Subscriber<? super AudioBlock> old=backend.sibling.subscribers.getFirst();
        engine.stopAudioOutput();engine.startAudioInputOutput(List.of(a,b));engine.pauseAudioOutput();
        RecordingPipeline pipeline=pipeline(engine,List.of(a,b));
        try {
            old.onNext(new AudioBlock(48000,1,256,filled(.4f,256)));
            backend.sibling.emit(256);pipeline.awaitFlushed();
            assertThat(audioOnDisk(pipeline.getSession(b))[0]).containsOnly(decoded16(.9f));
            assertThat(pipeline.getSession(b).getTotalSamplesRecorded()).isEqualTo(256);
        } finally {stop(pipeline,engine);}
    }

    @Test void eachSiblingLoopLapRotatesItsOwnTrackLane() throws Exception {
        PatternBackend backend=new PatternBackend();AudioEngine engine=engine(backend,2);
        Track a=track("A",0,1,0),b=track("B",0,1,1);
        Transport transport=new Transport();transport.setLoopWindow(true,0,512.0/48000*120/60);
        engine.setGraph(transport,null,List.of(a,b));engine.startAudioInputOutput(List.of(a,b));engine.pauseAudioOutput();
        RecordingPipeline pipeline=new RecordingPipeline(engine,transport,FORMAT,directory,List.of(a,b));
        pipeline.setLoopRecord(true);pipeline.prepare().toCompletableFuture().get(5,TimeUnit.SECONDS);pipeline.beginCapture();
        try {
            for(int block=0;block<7;block++) {
                engine.getRecordingCallback().onAudioCaptured(new float[][]{filled(.1f,256)},256);
                backend.sibling.emit(256);pipeline.awaitFlushed();
                transport.advancePosition(256.0/48000*120/60);
            }
            assertThat(TakeManifest.read(directory.resolve(TakeManifest.FILE_NAME)).segmentsFor(b.getId()).stream().map(TakeManifest.SegmentEntry::lane).distinct().toList()).hasSizeGreaterThanOrEqualTo(3);
        } finally {stop(pipeline,engine);}
    }

    @Test void everyDeviceHasItsOwnRingSignalAndLatency() throws Exception {
        PatternBackend backend = new PatternBackend();
        AudioEngine engine = engine(backend,2);
        Track a = track("A",0,1,0), b = track("B",0,1,1);
        engine.startAudioInputOutput(List.of(a,b));
        RecordingPipeline pipeline = pipeline(engine,List.of(a,b));
        try {
            assertThat(backend.sibling).isNotNull();
            backend.emit(256); backend.sibling.emit(256);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            boolean found=false;
            while(!found && System.nanoTime()<deadline) {
                pipeline.awaitFlushed();
                for(float sample:audioOnDisk(pipeline.getSession(a))[0]) if(sample==decoded16(.1f)) found=true;
                if(!found)Thread.sleep(2);
            }
            assertThat(found).isTrue();engine.pauseAudioOutput();pipeline.awaitFlushed();
            assertThat(audioOnDisk(pipeline.getSession(a))[0]).contains(decoded16(.1f)).doesNotContain(decoded16(.9f));
            assertThat(audioOnDisk(pipeline.getSession(b))[0]).containsOnly(decoded16(.9f));
            assertThat(pipeline.getSession(b).getCompensationFrames()).isEqualTo(12);
            assertThat(pipeline.getSession(a).getCompensationFrames()).isZero();
            assertThat(pipeline.getCaptureFlushService().lastManifest().orElseThrow().tracks())
                    .allSatisfy(entry -> assertThat(entry.channels()).isEqualTo(1));
        } finally { stop(pipeline,engine); }
    }

    @Test void primaryCalibrationOverridesItsDriverButNeverTheSiblingDeviceLatency() throws Exception {
        PatternBackend backend = new PatternBackend(); backend.primaryLatency = new RoundTripLatency(64, 128, 16);
        AudioEngine engine = engine(backend, 2);
        Track a = track("Calibrated primary", 0, 1, 0), b = track("Measured sibling", 0, 1, 1);
        engine.startAudioInputOutput(List.of(a, b)); engine.pauseAudioOutput();
        for (int calibration : new int[]{360, 0}) {
            RecordingPipeline pipeline = new RecordingPipeline(engine, new Transport(), FORMAT,
                    directory.resolve("calibration-" + calibration), List.of(a, b));
            pipeline.setReportedLatency(new RoundTripLatency(calibration, 0, 0));
            pipeline.prepare().toCompletableFuture().get(5, TimeUnit.SECONDS); pipeline.beginCapture();
            try {
                assertThat(pipeline.getSession(a).getCompensationFrames()).isEqualTo(calibration);
                assertThat(pipeline.getSession(b).getCompensationFrames()).isEqualTo(12);
            } finally { pipeline.requestStop().toCompletableFuture().get(5, TimeUnit.SECONDS); pipeline.completeStop(); }
        }
        engine.stopAudioOutput(); engine.shutdown();
    }

    @Test void asioRejectsTheSecondDeviceAtArmWithTrackAndDeviceNames() {
        PatternBackend backend = new PatternBackend(); backend.multiple = false;
        AudioEngine engine = engine(backend,2);
        try {
            assertThatThrownBy(() -> engine.validateInputRouting(List.of(track("Second microphone",0,1,1))))
                    .isInstanceOf(AudioBackendException.class).hasMessageContaining("Second microphone")
                    .hasMessageContaining("Interface B").hasMessageContaining("active device");
            assertThat(backend.opens).isZero();
        } finally { engine.shutdown(); }
    }

    @Test void knownDefaultCapabilityAndOverflowedRangesAreRefusedAtArm() {
        PatternBackend backend = new PatternBackend(); backend.maximum = 2;
        AudioEngine engine = engine(backend,2);
        try {
            assertThatThrownBy(() -> engine.validateInputRouting(List.of(track("Default input",6,2,-1))))
                    .hasMessageContaining("Default input").hasMessageContaining("2 input channels");
            assertThatThrownBy(() -> engine.validateInputRouting(List.of(track("Overflow",Integer.MAX_VALUE,2,0))))
                    .hasMessageContaining("Overflow").hasMessageContaining("overflows");
        } finally { engine.shutdown(); }
    }

    @Test void aDeviceShrinkingBeforeStartIsSilencedFlaggedAndWarnedBeforeReadiness() throws Exception {
        PatternBackend backend = new PatternBackend(); AudioEngine engine = engine(backend,2);
        Track far = track("Shrinking stereo",6,2,0);
        engine.validateInputRouting(List.of(far));
        backend.maximum = 7;
        engine.startAudioInputOutput(List.of(far)); engine.pauseAudioOutput();
        InputLevelMonitorRegistry meters = new InputLevelMonitorRegistry(); engine.setInputLevelMonitorRegistry(meters);
        List<String> warnings = new CopyOnWriteArrayList<>();
        Transport transport = new Transport(); engine.setGraph(transport,null,List.of(far));
        RecordingPipeline pipeline = new RecordingPipeline(engine,transport,FORMAT,directory,List.of(far));
        pipeline.setWarningSink(warnings::add);
        pipeline.prepare().toCompletableFuture().get(5,TimeUnit.SECONDS);
        assertThat(warnings).singleElement().asString().contains("Shrinking stereo", "Interface A", "silence");
        assertThat(TakeManifest.read(directory.resolve(TakeManifest.FILE_NAME)).routingFlags()).singleElement()
                .satisfies(flag -> assertThat(flag.availableChannels()).isEqualTo(7));
        pipeline.beginCapture();
        try {
            float[][] raw = new float[7][256]; for (float[] row : raw) Arrays.fill(row,.8f);
            engine.processBlock(raw,new float[2][256],256);
            pipeline.awaitFlushed();
            for (float[] row : audioOnDisk(pipeline.getSession(far)))
                for (float sample : row) assertThat(Float.floatToRawIntBits(sample)).isZero();
            assertThat(meters.get(far.getId()).isRoutingUnavailable()).isTrue();
            assertThat(warnings).hasSize(1);
        } finally { stop(pipeline,engine); }
        assertThat(TakeManifest.read(directory.resolve(TakeManifest.FILE_NAME)).routingFlags()).hasSize(1);
    }

    @Test void missingOneChannelZerosTheEntireReusedStereoScratch() throws Exception {
        AudioEngine engine = new AudioEngine(FORMAT);
        Track far = track("Stereo",6,2,0);
        RecordingPipeline pipeline = pipeline(engine,List.of(far));
        try {
            float[][] full = new float[8][256]; for (float[] row : full) Arrays.fill(row,.5f);
            engine.getRecordingCallback().onAudioCaptured(full,256);
            float[][] shortBlock = Arrays.copyOf(full,7);
            engine.getRecordingCallback().onAudioCaptured(shortBlock,256);
            pipeline.awaitFlushed();
            float[][] samples = audioOnDisk(pipeline.getSession(far));
            for (float[] row : samples) {
                assertThat(row[0]).isEqualTo(decoded16(.5f));
                for (int f=256; f<512; f++) assertThat(Float.floatToRawIntBits(row[f])).isZero();
            }
            assertThat(TakeManifest.read(directory.resolve(TakeManifest.FILE_NAME)).routingFlags()).hasSize(1);
        } finally { stop(pipeline,engine); }
    }

    @Test void siblingBurstUsesItsOwnFrameCursorAndReportsOversizedBlocks() throws Exception {
        PatternBackend backend = new PatternBackend(); AudioEngine engine = engine(backend,2);
        Track a=track("A",0,1,0), b=track("B",0,1,1);
        engine.startAudioInputOutput(List.of(a,b)); engine.pauseAudioOutput();
        RecordingPipeline pipeline=pipeline(engine,List.of(a,b));
        List<Long> frames = new CopyOnWriteArrayList<>();
        pipeline.getCaptureFlushService().setBlockObserver((sequence,start,count)->frames.add(start));
        try {
            backend.sibling.emit(256); backend.sibling.emit(512);
            pipeline.awaitFlushed();
            assertThat(frames).containsExactly(0L,256L);
            assertThat(TakeManifest.read(directory.resolve(TakeManifest.FILE_NAME)).truncatedFrames()).isEqualTo(256);
            assertThat(pipeline.getTruncatedFrames()).isEqualTo(256);
            assertThat(pipeline.getSession(b).getTotalSamplesRecorded()).isEqualTo(512);
        } finally { stop(pipeline,engine); }
    }
    @Test void onlyTheOverflowingInputDeviceReceivesGapEntriesAndAllCountersAgree() throws Exception {
        PatternBackend backend=new PatternBackend();AudioEngine engine=engine(backend,2);
        Track a=track("A",0,1,0),b=track("B",0,1,1);
        engine.startAudioInputOutput(List.of(a,b));engine.pauseAudioOutput();
        RecordingPipeline pipeline=new RecordingPipeline(engine,new Transport(),FORMAT,directory,List.of(a,b));pipeline.setRingSlots(8);
        pipeline.prepare().toCompletableFuture().get(5,TimeUnit.SECONDS);pipeline.beginCapture();
        CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1);
        pipeline.getCaptureFlushService().setBlockObserver((sequence,start,frames)->{held.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        try {
            engine.getRecordingCallback().onAudioCaptured(new float[][]{filled(.1f,256)},256);
            assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
            for(int block=0;block<12;block++)backend.sibling.emit(256);
            release.countDown();pipeline.awaitFlushed();
            TakeManifest manifest=TakeManifest.read(directory.resolve(TakeManifest.FILE_NAME));
            assertThat(manifest.overflowBlocks()).isEqualTo(4);
            assertThat(manifest.gaps()).isNotEmpty().allSatisfy(gap->assertThat(gap.trackId()).isEqualTo(b.getId()));
            assertThat(pipeline.getOverflowCount()).isEqualTo(4);
            assertThat(pipeline.getCaptureFlushService().overflowCount()).isEqualTo(4);
        } finally {release.countDown();stop(pipeline,engine);}
    }

    @Test void siblingRateMismatchIsRefusedAndBothStreamsAreReleasedBeforeATakeExists() {
        PatternBackend backend=new PatternBackend();backend.siblingRate=44100;AudioEngine engine=engine(backend,2);
        try {
            assertThatThrownBy(()->engine.startAudioInputOutput(List.of(track("A",0,1,0),track("B",0,1,1))))
                    .hasMessageContaining("Interface B","44100","48000");
            assertThat(backend.open).isFalse();assertThat(backend.sibling.open).isFalse();
        } finally {engine.stopAudioOutput();engine.shutdown();}
    }

    @Test void retainedSiblingCannotSkipPrimaryCloseOrBeReopenedOver() {
        PatternBackend backend=new PatternBackend(); AudioEngine engine=engine(backend,2);
        engine.startAudioInputOutput(List.of(track("A",0,1,0),track("B",0,1,1)));
        backend.sibling.failClose=true;
        assertThatThrownBy(engine::stopAudioOutput).hasMessageContaining("held sibling");
        assertThat(backend.open).isFalse();
        int opens=backend.opens;
        assertThatThrownBy(engine::startAudioOutput).hasMessageContaining("held sibling");
        assertThat(backend.opens).isEqualTo(opens);
        backend.sibling.failClose=false; engine.stopAudioOutput(); engine.shutdown();
    }
    @Test void routingFlagRoundTripsArbitraryDeviceLabels() throws Exception {
        TakeManifest.Builder builder = TakeManifest.builder().take("take").startedAt(java.time.Instant.now())
                .sampleRate(48000).bitDepth(16).streamChannels(2).startBeat(0).startFrame(0)
                .forceCadenceMillis(100).ringSlots(8).ringFrames(256);
        builder.addRoutingFlag(new TakeManifest.RoutingFlag("track","USB | ASIO\nPort",6,2,7));
        TakeManifest manifest=builder.build(); manifest.write(directory);
        assertThat(TakeManifest.read(directory.resolve(TakeManifest.FILE_NAME))).isEqualTo(manifest);
        assertThat(manifest.toBuilder().build().routingFlags()).isEqualTo(manifest.routingFlags());
    }
    @Test void malformedRoutingFlagsAreReportedAsManifestIoErrorsWithALine() {
        assertThatThrownBy(() -> TakeManifest.parse("routing-unavailable=track|%%%|6|2|7", "broken.manifest"))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("broken.manifest:1", "routing-unavailable");
        assertThatThrownBy(() -> TakeManifest.parse("routing-unavailable=track|VVNC|6|0|7", "broken.manifest"))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("broken.manifest:1", "routing-unavailable");
    }

    private static float[] filled(float value,int count) { float[] result=new float[count]; Arrays.fill(result,value); return result; }

    static final class PatternBackend implements AudioBackend {
        int maximum=8, openedWidth, outputWidth, opens;
        boolean multiple=true, open, failClose, failSiblingOpen, failOpen, failInputResolution;
        String input="Interface A";
        double openedRate=48000,siblingRate=48000;
        RoundTripLatency primaryLatency = RoundTripLatency.UNKNOWN;
        PatternBackend sibling;
        final List<Flow.Subscriber<? super AudioBlock>> subscribers=new CopyOnWriteArrayList<>();
        @Override public String name(){return "Pattern ASIO";}
        @Override public boolean isAvailable(){return true;}
        @Override public boolean isOpen(){return open;}
        @Override public boolean supportsStreaming(){return true;}
        @Override public boolean supportsMultipleInputDevices(){return multiple;}
        @Override public DeviceId selectedInputDevice(DeviceId output) {
            if (failInputResolution) throw new AudioBackendException("head has no input device");
            return output;
        }
        @Override public List<AudioDeviceInfo> listDevices(){return List.of(info(0,"Interface A"),info(1,"Interface B"));}
        AudioDeviceInfo info(int index,String name){return new AudioDeviceInfo(index,name,"Pattern",maximum,2,48000,List.of(SampleRate.HZ_48000),0,0);}
        @Override public void open(DeviceId device,com.benesquivelmusic.daw.sdk.audio.AudioFormat format,int frames){
            open(device,format,frames,CaptureRequirement.REQUIRED,device,format.channels());
        }
        @Override public void open(DeviceId output,com.benesquivelmusic.daw.sdk.audio.AudioFormat format,int frames,
                                   CaptureRequirement capture,DeviceId input,int width){
            if(open)throw new IllegalStateException("already open"); open=true;opens++;
            if(failOpen)throw new AudioBackendException("second input refused");
            openedWidth=Math.min(maximum,width);outputWidth=format.channels();this.input=input.name();
        }
        @Override public AudioBackend createInputBackend(){sibling=new PatternBackend();sibling.maximum=maximum;sibling.failOpen=failSiblingOpen;sibling.openedRate=siblingRate;return sibling;}
        @Override public void openInput(DeviceId input,com.benesquivelmusic.daw.sdk.audio.AudioFormat format,int frames,int width){
            open(input,format,frames,CaptureRequirement.REQUIRED,input,width);
        }
        @Override public int openedInputChannels(){return open?openedWidth:0;}
        @Override public double openedInputSampleRate(){return openedRate;}
        @Override public RoundTripLatency reportedLatency(){return input.contains("B")?new RoundTripLatency(12,0,0):primaryLatency;}
        @Override public Flow.Publisher<AudioBlock> inputBlocks(){return subscriber->{subscribers.add(subscriber);subscriber.onSubscribe(new Flow.Subscription(){
            public void request(long n){} public void cancel(){subscribers.remove(subscriber);}
        });};}
        @Override public void sink(AudioBlock block){}
        @Override public void close(){if(failClose)throw new AudioBackendException("held sibling");open=false;}
        void emit(int frames){float[] samples=new float[frames*openedWidth];for(int f=0;f<frames;f++)for(int c=0;c<openedWidth;c++)
            samples[f*openedWidth+c]=input.contains("B")?.9f:(c+1)/10f;
            AudioBlock block=new AudioBlock(48000,openedWidth,frames,samples);subscribers.forEach(subscriber->subscriber.onNext(block));}
    }
}
