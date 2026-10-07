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
        track.setInputDevice(patternInput(device)); track.setArmed(true);
        return track;
    }
    /** Stable identity of a PatternBackend device by its enumeration position (0 = Interface A, 1 = Interface B). */
    static Optional<DeviceId> patternInput(int device) {
        return device < 0 ? Optional.empty()
                : Optional.of(new DeviceId("Pattern ASIO", (device == 0 ? "Interface A" : "Interface B") + " [Pattern]"));
    }
    private AudioEngine engine(PatternBackend backend, int outputs) {
        AudioEngine engine = new AudioEngine(new com.benesquivelmusic.daw.core.audio.AudioFormat(48000, outputs, 16, 256));
        engine.setStreamingProvision(new StreamingProvision(backend.name(), List.of(new BackendStreamRung(backend,
                new DeviceId(backend.name(), "Interface A")))));
        return engine;
    }
    private RecordingPipeline pipeline(AudioEngine engine, List<Track> tracks) throws Exception {
        return pipeline(engine, directory, tracks);
    }
    private static RecordingPipeline pipeline(AudioEngine engine, Path takeDirectory, List<Track> tracks) throws Exception {
        RecordingPipeline pipeline = new RecordingPipeline(engine, new Transport(), engine.getFormat(), takeDirectory, tracks);
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
            // With no stream open the explicit route targets the head, the rung record start opens
            // first; it is refused there by name rather than walked onto the fallback.
            engine.stopAudioOutput();
            assertThatThrownBy(() -> engine.validateInputRouting(List.of(track("Pinned input", 0, 1, 0))))
                    .isInstanceOf(AudioBackendException.class).hasMessageContainingAll("Pinned input", "Interface A", "resolution failed");
        } finally { engine.stopAudioOutput(); engine.shutdown(); }
    }

    /** Head refuses to open, so the session stream runs on the fallback rung "Pattern WASAPI". */
    private static AudioEngine streamingOnFallback(PatternBackend head, PatternBackend fallback) {
        head.failOpen = true; fallback.name = "Pattern WASAPI";
        AudioEngine engine = new AudioEngine(new com.benesquivelmusic.daw.core.audio.AudioFormat(48000, 2, 16, 256));
        engine.setStreamingProvision(new StreamingProvision(head.name(), List.of(
                new BackendStreamRung(head, new DeviceId(head.name(), "Interface A")),
                new BackendStreamRung(fallback, new DeviceId(fallback.name(), "Interface A")))));
        engine.startAudioOutput();
        assertThat(engine.openStreamBackendName()).contains(fallback.name());
        return engine;
    }

    @Test void aDevicePickedWhileTheStreamRunsOnAFallbackRungArmsAndRecordsFromThatRung() throws Exception {
        PatternBackend head = new PatternBackend(), fallback = new PatternBackend();
        AudioEngine engine = streamingOnFallback(head, fallback);
        // The per-track picker lists, and stamps the identity with, the backend getBackend() names.
        Track picked = track("Picked on fallback", 0, 1, -1);
        picked.setInputDevice(Optional.of(new DeviceId(engine.getBackend().name(), fallback.listDevices().get(1).qualifiedName())));
        RecordingPipeline pipeline = null;
        try {
            assertThatCode(() -> engine.validateInputRouting(List.of(picked))).doesNotThrowAnyException();
            engine.startAudioInputOutput(List.of(picked));
            assertThat(engine.getCaptureRoutingPlan().backend()).isSameAs(fallback);
            assertThat(fallback.input).isEqualTo("Interface B [Pattern]");
            assertThat(engine.openStreamBackendName()).contains(fallback.name());
            pipeline = pipeline(engine, List.of(picked));
            fallback.emit(256);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean found = false;
            while (!found && System.nanoTime() < deadline) {
                pipeline.awaitFlushed();
                for (float sample : audioOnDisk(pipeline.getSession(picked))[0]) if (sample == decoded16(.9f)) found = true;
                if (!found) Thread.sleep(5);
            }
            assertThat(found).as("the take carries the picked fallback device's signal").isTrue();
        } finally {
            if (pipeline != null) stop(pipeline, engine);
            else { engine.stopAudioOutput(); engine.shutdown(); }
        }
    }

    @Test void aDeviceAnotherBackendListedIsRefusedNamingTheBackendCaptureUses() {
        PatternBackend head = new PatternBackend(), fallback = new PatternBackend();
        AudioEngine engine = streamingOnFallback(head, fallback);
        Track foreign = track("Foreign", 0, 1, -1);
        foreign.setInputDevice(Optional.of(new DeviceId("Other backend", fallback.listDevices().get(1).qualifiedName())));
        try {
            assertThatThrownBy(() -> engine.validateInputRouting(List.of(foreign)))
                    .isInstanceOf(AudioBackendException.class)
                    .hasMessageContainingAll("Foreign", "Other backend", "capture records from backend '" + fallback.name() + "'")
                    .hasMessageNotContaining(head.name());
        } finally { engine.stopAudioOutput(); engine.shutdown(); }
    }

    @Test void anIdentityOwnedByALowerRungNeverDemotesAWorkingHeadOntoTheFallback() {
        PatternBackend head = new PatternBackend();
        PatternBackend fallback = new PatternBackend(); fallback.name = "Pattern WASAPI";
        AudioEngine engine = engine(head, 2);
        engine.setStreamingProvision(new StreamingProvision(head.name(), List.of(
                new BackendStreamRung(head, new DeviceId(head.name(), "Interface A")),
                new BackendStreamRung(fallback, new DeviceId(fallback.name(), "Interface A")))));
        // Picked back when the stream ran on the fallback; Settings has since restored the head.
        Track stale = track("Picked under fallback", 0, 1, -1);
        stale.setInputDevice(Optional.of(new DeviceId(fallback.name(), "Interface B [Pattern]")));
        try {
            engine.startAudioOutput();
            assertThat(engine.openStreamBackendName()).contains(head.name());
            assertThatThrownBy(() -> engine.validateInputRouting(List.of(stale)))
                    .isInstanceOf(AudioBackendException.class)
                    .hasMessageContainingAll("Picked under fallback", "belongs to backend '" + fallback.name() + "'",
                            "capture records from backend '" + head.name() + "'");
            assertThatThrownBy(() -> engine.startAudioInputOutput(List.of(stale)))
                    .isInstanceOf(AudioBackendException.class)
                    .hasMessageContainingAll("Picked under fallback", fallback.name(), head.name());
            assertThat(fallback.opens).as("the fallback rung is never opened in place of the working head").isZero();
        } finally { engine.stopAudioOutput(); engine.shutdown(); }
    }

    @Test void aHintOnlyTrackRecordsTheSessionDefaultAndRecordsItsIdentityOnceSet() throws Exception {
        PatternBackend backend = new PatternBackend();
        AudioEngine engine = engine(backend, 2);
        // Saved by an earlier version: position 1 is Interface B in this list, but earlier
        // versions never captured by a position, so the track records the session input
        // (Interface A) exactly as before.
        Track migrated = track("Old project vocal", 0, 1, -1);
        migrated.setLegacyInputDeviceIndexHint(1);
        RecordingPipeline pipeline = null;
        try {
            assertThatCode(() -> engine.validateInputRouting(List.of(migrated))).doesNotThrowAnyException();
            engine.startAudioInputOutput(List.of(migrated));
            assertThat(backend.input).isEqualTo("Interface A");
            pipeline = pipeline(engine, List.of(migrated));
            backend.emit(256);
            float[] sessionTake = awaitSample(pipeline, migrated, decoded16(.1f));
            assertThat(sessionTake).as("the take carries the session default's signal")
                    .contains(decoded16(.1f)).doesNotContain(decoded16(.9f));
            pipeline.requestStop().toCompletableFuture().get(5, TimeUnit.SECONDS);
            pipeline.completeStop();
            pipeline = null;
            engine.stopAudioOutput();

            migrated.setInputDevice(patternInput(1));
            assertThat(migrated.getLegacyInputDeviceIndexHint()).isEqualTo(Track.NO_INPUT_DEVICE);
            assertThatCode(() -> engine.validateInputRouting(List.of(migrated))).doesNotThrowAnyException();
            engine.startAudioInputOutput(List.of(migrated));
            assertThat(backend.input).isEqualTo("Interface B [Pattern]");
            pipeline = pipeline(engine, java.nio.file.Files.createDirectories(directory.resolve("confirmed")),
                    List.of(migrated));
            backend.emit(256);
            assertThat(awaitSample(pipeline, migrated, decoded16(.9f)))
                    .as("the take carries the confirmed identity's signal").contains(decoded16(.9f));
        } finally {
            if (pipeline != null) stop(pipeline, engine);
            else { engine.stopAudioOutput(); engine.shutdown(); }
        }
    }

    /** First channel of {@code track}'s take once {@code expected} reaches disk, or after a 5 s bound. */
    private static float[] awaitSample(RecordingPipeline pipeline, Track track, float expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            pipeline.awaitFlushed();
            float[] samples = audioOnDisk(pipeline.getSession(track))[0];
            for (float sample : samples) if (sample == expected) return samples;
            if (System.nanoTime() >= deadline) return samples;
            Thread.sleep(5);
        }
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
            engine.refreshInputMeterRouting();
            assertThat(rebound.get(siblingLeft.getId()).isRoutingUnavailable()).isFalse();
            assertThat(rebound.get(siblingLeft.getId()).routingDescription()).isEmpty();
            assertThat(rebound.get(siblingLeft.getId()).snapshot()).isEqualTo(com.benesquivelmusic.daw.sdk.analysis.InputLevelMeter.SILENCE);
            engine.processBlock(new float[][]{filled(.1f,256)},new float[2][256],256);
            assertThat(rebound.get(siblingLeft.getId()).isRoutingUnavailable()).isFalse();
            assertThat(rebound.get(siblingLeft.getId()).snapshot()).isEqualTo(com.benesquivelmusic.daw.sdk.analysis.InputLevelMeter.SILENCE);
            assertThat(rebound.get(siblingRight.getId()).isRoutingUnavailable()).isTrue();
            siblingLeft.setInputRouting(new InputRouting(0,1));
            engine.refreshInputMeterRouting();
            assertThat(rebound.get(siblingLeft.getId()).isRoutingUnavailable()).isTrue();
            assertThat(rebound.get(siblingLeft.getId()).routingDescription()).contains("Input 1", "Interface B");
            siblingLeft.setInputRouting(new InputRouting(1, 1));
            engine.refreshInputMeterRouting();
            assertThat(rebound.get(siblingLeft.getId()).isRoutingUnavailable()).isTrue();
            assertThat(rebound.get(siblingLeft.getId()).routingDescription()).contains("Input 2", "Interface B");
            assertThat(rebound.get(siblingLeft.getId()).snapshot()).isEqualTo(com.benesquivelmusic.daw.sdk.analysis.InputLevelMeter.SILENCE);
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

    @ParameterizedTest
    @EnumSource(InputTermination.class)
    void terminatedPrimaryInputKeepsClockedSilenceUnavailableAndFlagged(InputTermination termination)
            throws Exception {
        PatternBackend backend = new PatternBackend();
        backend.renderGate = new RenderGate();
        AudioEngine engine = engine(backend, 2);
        Track primary = track("Primary stereo", 6, 2, 0), sibling = track("Sibling", 0, 1, 1);
        List<Track> tracks = List.of(primary, sibling);
        Transport transport = new Transport();
        var mixer = new com.benesquivelmusic.daw.core.mixer.Mixer();
        mixer.getMasterChannel().getEffectsChain().addProcessor(new AudioProcessor() {
            @Override public void process(float[][] input, float[][] output, int frames) {
                for (float[] row : output) Arrays.fill(row, 0, frames, .5f);
            }
            @Override public void reset() { }
            @Override public int getInputChannelCount() { return 2; }
            @Override public int getOutputChannelCount() { return 2; }
        });
        engine.setGraph(transport, mixer, tracks);
        InputLevelMonitorRegistry meters = new InputLevelMonitorRegistry();
        engine.setInputLevelMonitorRegistry(meters);
        List<String> warnings = new CopyOnWriteArrayList<>();
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, FORMAT, directory, tracks);
        pipeline.setWarningSink(warnings::add);
        try {
            engine.startAudioInputOutput(tracks);
            backend.renderGate.awaitRendered();
            pipeline.prepare().toCompletableFuture().get(5, TimeUnit.SECONDS);
            pipeline.beginCapture();
            backend.emit(256);
            backend.renderGate.advance();
            backend.sibling.emit(256);
            pipeline.awaitFlushed();
            assertThat(meters.get(primary.getId()).snapshot().peakDbfs()).isGreaterThan(-10);
            assertThat(meters.get(primary.getId()).isRoutingUnavailable()).isFalse();

            // A gap in a still-open publisher remains known-width silence.
            backend.renderGate.advance();
            backend.sibling.emit(256);
            pipeline.awaitFlushed();
            assertThat(meters.get(primary.getId()).isRoutingUnavailable()).isFalse();
            assertThat(warnings).isEmpty();

            Flow.Subscriber<? super AudioBlock> subscriber = backend.subscribers.getFirst();
            backend.emit(256); // Queued audio must not restore availability after termination.
            termination.signal(subscriber);
            subscriber.onNext(new AudioBlock(48000, 8, 256, filled(.4f, 8 * 256)));
            for (int block = 0; block < 3; block++) {
                backend.renderGate.advance();
                backend.sibling.emit(256);
                pipeline.awaitFlushed();
                assertThat(meters.get(primary.getId()).isRoutingUnavailable()).isTrue();
                assertThat(meters.get(primary.getId()).snapshot())
                        .isEqualTo(com.benesquivelmusic.daw.sdk.analysis.InputLevelMeter.SILENCE);
                assertThat(meters.get(sibling.getId()).isRoutingUnavailable()).isFalse();
                assertThat(meters.get(sibling.getId()).snapshot().peakDbfs()).isGreaterThan(-10);
                assertThat(backend.renderGate.lastOutput).containsOnly(.5f);
            }
            float[][] recorded = audioOnDisk(pipeline.getSession(primary));
            assertThat(recorded.length).isEqualTo(2);
            assertThat(pipeline.getSession(primary).getTotalSamplesRecorded()).isEqualTo(5 * 256);
            assertThat(Arrays.copyOfRange(recorded[0], 0, 256)).containsOnly(decoded16(.7f));
            assertThat(Arrays.copyOfRange(recorded[1], 0, 256)).containsOnly(decoded16(.8f));
            for (float[] row : recorded) {
                assertThat(row).hasSize(5 * 256);
                assertThat(Arrays.copyOfRange(row, 256, row.length)).containsOnly(0f);
            }
            assertThat(pipeline.getSession(sibling).getTotalSamplesRecorded()).isEqualTo(5 * 256);
            assertThat(audioOnDisk(pipeline.getSession(sibling))[0]).hasSize(5 * 256)
                    .containsOnly(decoded16(.9f));
            assertThat(warnings).singleElement().asString().contains("Primary stereo", "Interface A", "silence");
        } finally {
            try {
                PipelineLifecycleTestSupport.endTheTake(pipeline);
            } finally {
                try { engine.stopAudioOutput(); }
                finally { engine.shutdown(); }
            }
        }
        assertThat(TakeManifest.read(directory.resolve(TakeManifest.FILE_NAME)).routingFlags()).singleElement()
                .satisfies(flag -> {
                    assertThat(flag.trackId()).isEqualTo(primary.getId());
                    assertThat(flag.device()).contains("Interface A");
                    assertThat(flag.firstChannel()).isEqualTo(6);
                    assertThat(flag.channelCount()).isEqualTo(2);
                    assertThat(flag.availableChannels()).isZero();
                });
    }

    @ParameterizedTest
    @EnumSource(InputTermination.class)
    void oldPrimaryTerminationCannotSilenceStoppedOrReplacementMeters(InputTermination termination)
            throws Exception {
        PatternBackend backend = new PatternBackend();
        backend.renderGate = new RenderGate();
        AudioEngine engine = engine(backend, 2);
        Track primary = track("Primary", 0, 1, 0);
        List<Track> tracks = List.of(primary);
        InputLevelMonitorRegistry meters = new InputLevelMonitorRegistry();
        engine.setInputLevelMonitorRegistry(meters);
        engine.setGraph(new Transport(), null, tracks);
        try {
            engine.startAudioInputOutput(tracks);
            backend.renderGate.awaitRendered();
            backend.emit(256);
            backend.renderGate.advance();
            Flow.Subscriber<? super AudioBlock> old = backend.subscribers.getFirst();
            var stoppedLevel = meters.get(primary.getId()).snapshot();
            engine.stopAudioOutput();
            termination.signal(old);
            assertThat(meters.get(primary.getId()).isRoutingUnavailable()).isFalse();
            assertThat(meters.get(primary.getId()).snapshot()).isEqualTo(stoppedLevel);

            engine.startAudioInputOutput(tracks);
            backend.renderGate.awaitRendered();
            backend.emit(256);
            backend.renderGate.advance();
            var replacementLevel = meters.get(primary.getId()).snapshot();
            assertThat(replacementLevel.peakDbfs()).isGreaterThan(-30);
            termination.signal(old);
            old.onNext(new AudioBlock(48000, 1, 256, filled(.4f, 256)));
            backend.emit(256);
            backend.renderGate.advance();
            assertThat(meters.get(primary.getId()).isRoutingUnavailable()).isFalse();
            assertThat(meters.get(primary.getId()).snapshot().rmsDbfs()).isEqualTo(replacementLevel.rmsDbfs());
            assertThat(meters.get(primary.getId()).snapshot().peakDbfs()).isGreaterThan(-30);
        } finally { engine.stopAudioOutput(); engine.shutdown(); }
    }

    @ParameterizedTest
    @EnumSource(InputTermination.class)
    void terminatedSiblingInputKeepsClockedSilenceUnavailableAndFlagged(InputTermination termination)
            throws Exception {
        PatternBackend backend = new PatternBackend();
        backend.renderGate = new RenderGate();
        AudioEngine engine = engine(backend, 2);
        Track primary = track("Primary", 0, 1, 0);
        Track siblingLeft = track("Sibling left", 0, 1, 1), siblingRight = track("Sibling right", 1, 1, 1);
        List<Track> siblings = List.of(siblingLeft, siblingRight), tracks = List.of(primary, siblingLeft, siblingRight);
        Transport transport = new Transport();
        engine.setGraph(transport, null, tracks);
        InputLevelMonitorRegistry meters = new InputLevelMonitorRegistry();
        engine.setInputLevelMonitorRegistry(meters);
        List<String> warnings = new CopyOnWriteArrayList<>();
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, FORMAT, directory, tracks);
        pipeline.setWarningSink(warnings::add);
        try {
            engine.startAudioInputOutput(tracks);
            backend.renderGate.awaitRendered();
            pipeline.prepare().toCompletableFuture().get(5, TimeUnit.SECONDS);
            pipeline.beginCapture();
            for (int block = 0; block < 2; block++) {
                backend.emit(256);
                backend.renderGate.advance();
                backend.sibling.emit(256);
                pipeline.awaitFlushed();
            }
            assertThat(warnings).isEmpty();

            Flow.Subscriber<? super AudioBlock> subscriber = backend.sibling.subscribers.getFirst();
            termination.signal(subscriber);
            // Audio a dead publisher still delivers must not reach the take beside the clocked silence.
            int width = backend.sibling.openedWidth;
            subscriber.onNext(new AudioBlock(48000, width, 256, filled(.4f, width * 256)));
            for (int block = 0; block < 3; block++) {
                backend.emit(256);
                backend.renderGate.advance();
                backend.sibling.emit(256);
                pipeline.awaitFlushed();
                for (Track sibling : siblings) {
                    assertThat(meters.get(sibling.getId()).isRoutingUnavailable()).isTrue();
                    assertThat(meters.get(sibling.getId()).snapshot())
                            .isEqualTo(com.benesquivelmusic.daw.sdk.analysis.InputLevelMeter.SILENCE);
                }
                assertThat(meters.get(primary.getId()).isRoutingUnavailable()).isFalse();
                assertThat(meters.get(primary.getId()).snapshot().peakDbfs()).isGreaterThan(-30);
            }
            assertThat(pipeline.getSession(primary).getTotalSamplesRecorded()).isEqualTo(5 * 256);
            assertThat(audioOnDisk(pipeline.getSession(primary))[0]).hasSize(5 * 256).containsOnly(decoded16(.1f));
            for (Track sibling : siblings) {
                assertThat(pipeline.getSession(sibling).getTotalSamplesRecorded())
                        .as("'%s' stays time-aligned and full-length", sibling.getName())
                        .isEqualTo(pipeline.getSession(primary).getTotalSamplesRecorded());
                float[] recorded = audioOnDisk(pipeline.getSession(sibling))[0];
                assertThat(recorded).hasSize(5 * 256);
                assertThat(Arrays.copyOfRange(recorded, 0, 2 * 256)).containsOnly(decoded16(.9f));
                assertThat(Arrays.copyOfRange(recorded, 2 * 256, recorded.length)).containsOnly(0f);
                assertThat(warnings).filteredOn(warning -> warning.contains("'" + sibling.getName() + "'"))
                        .singleElement().asString().contains("Interface B", "silence");
            }
            assertThat(warnings).hasSize(2).noneMatch(warning -> warning.contains("'Primary'"));
        } finally {
            try {
                PipelineLifecycleTestSupport.endTheTake(pipeline);
            } finally {
                try { engine.stopAudioOutput(); }
                finally { engine.shutdown(); }
            }
        }
        List<TakeManifest.RoutingFlag> flags = TakeManifest.read(directory.resolve(TakeManifest.FILE_NAME)).routingFlags();
        assertThat(flags).extracting(TakeManifest.RoutingFlag::trackId)
                .containsExactlyInAnyOrder(siblingLeft.getId(), siblingRight.getId());
        for (TakeManifest.RoutingFlag flag : flags) {
            Track sibling = flag.trackId().equals(siblingLeft.getId()) ? siblingLeft : siblingRight;
            assertThat(flag.device()).contains("Interface B");
            assertThat(flag.firstChannel()).isEqualTo(sibling.getInputRouting().firstChannel());
            assertThat(flag.channelCount()).isEqualTo(1);
            assertThat(flag.availableChannels()).isZero();
        }
    }

    @ParameterizedTest
    @EnumSource(InputTermination.class)
    void oldSiblingTerminationNeverClocksTheReplacementStreamsSiblingAsSilence(InputTermination termination)
            throws Exception {
        PatternBackend backend = new PatternBackend();
        AudioEngine engine = engine(backend, 2);
        Track primary = track("Primary", 0, 1, 0), sibling = track("Sibling", 0, 1, 1);
        List<Track> tracks = List.of(primary, sibling);
        Transport transport = new Transport();
        engine.setGraph(transport, null, tracks);
        engine.startAudioInputOutput(tracks); engine.pauseAudioOutput();
        Flow.Subscriber<? super AudioBlock> old = backend.sibling.subscribers.getFirst();
        CaptureRoutingPlan oldPlan = engine.getCaptureRoutingPlan();
        engine.stopAudioOutput();
        // The signal() calls are guard checks (deactivate() already cleared the old subscriber); the late
        // appends replay a terminal signal that passed its guard before the reset and are what discriminate.
        termination.signal(old);
        appendClockedSilenceLate(engine, oldPlan, 1); // between streams: the reset sentinel holds no plan
        engine.startAudioInputOutput(tracks); engine.pauseAudioOutput();
        termination.signal(old);
        appendClockedSilenceLate(engine, oldPlan, 1); // after replacement: the new stream's set holds its plan
        List<String> warnings = new CopyOnWriteArrayList<>();
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, FORMAT, directory, tracks);
        pipeline.setWarningSink(warnings::add);
        try {
            pipeline.prepare().toCompletableFuture().get(5, TimeUnit.SECONDS);
            pipeline.beginCapture();
            for (int block = 0; block < 3; block++) {
                engine.processBlock(new float[][]{filled(.1f, 256)}, new float[2][256], 256);
                backend.sibling.emit(256);
                pipeline.awaitFlushed();
            }
            // Every zero-width block the audio thread clocked would add 256 silent frames.
            assertThat(pipeline.getSession(sibling).getTotalSamplesRecorded()).isEqualTo(3 * 256);
            assertThat(audioOnDisk(pipeline.getSession(sibling))[0]).hasSize(3 * 256).containsOnly(decoded16(.9f));
            assertThat(warnings).isEmpty();
        } finally {
            try {
                PipelineLifecycleTestSupport.endTheTake(pipeline);
            } finally {
                try { engine.stopAudioOutput(); }
                finally { engine.shutdown(); }
            }
        }
        assertThat(TakeManifest.read(directory.resolve(TakeManifest.FILE_NAME)).routingFlags()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(InputTermination.class)
    void siblingTerminatingWhileTheStreamStartsKeepsClockedSilenceUnavailableAndFlagged(InputTermination termination)
            throws Exception {
        PatternBackend backend = new PatternBackend();
        backend.renderGate = new RenderGate();
        backend.siblingTerminatesOnSubscribe = termination;
        AudioEngine engine = engine(backend, 2);
        Track primary = track("Primary", 0, 1, 0);
        Track siblingLeft = track("Sibling left", 0, 1, 1), siblingRight = track("Sibling right", 1, 1, 1);
        List<Track> siblings = List.of(siblingLeft, siblingRight), tracks = List.of(primary, siblingLeft, siblingRight);
        Transport transport = new Transport();
        engine.setGraph(transport, null, tracks);
        InputLevelMonitorRegistry meters = new InputLevelMonitorRegistry();
        engine.setInputLevelMonitorRegistry(meters);
        List<String> warnings = new CopyOnWriteArrayList<>();
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, FORMAT, directory, tracks);
        pipeline.setWarningSink(warnings::add);
        try {
            // Interface B's publisher terminates inside subscribe(), before startAudioInputOutput returns.
            engine.startAudioInputOutput(tracks);
            backend.renderGate.awaitRendered();
            pipeline.prepare().toCompletableFuture().get(5, TimeUnit.SECONDS);
            pipeline.beginCapture();
            for (int block = 0; block < 3; block++) {
                backend.emit(256);
                backend.renderGate.advance();
                backend.sibling.emit(256);
                pipeline.awaitFlushed();
            }
            for (Track sibling : siblings) {
                assertThat(meters.get(sibling.getId()).isRoutingUnavailable()).isTrue();
                assertThat(meters.get(sibling.getId()).snapshot())
                        .isEqualTo(com.benesquivelmusic.daw.sdk.analysis.InputLevelMeter.SILENCE);
            }
            assertThat(meters.get(primary.getId()).isRoutingUnavailable()).isFalse();
            assertThat(pipeline.getSession(primary).getTotalSamplesRecorded()).isEqualTo(3 * 256);
            assertThat(audioOnDisk(pipeline.getSession(primary))[0]).hasSize(3 * 256).containsOnly(decoded16(.1f));
            for (Track sibling : siblings) {
                assertThat(pipeline.getSession(sibling).getTotalSamplesRecorded())
                        .as("'%s' is clocked from the first block", sibling.getName()).isEqualTo(3 * 256);
                assertThat(audioOnDisk(pipeline.getSession(sibling))[0]).hasSize(3 * 256).containsOnly(0f);
                assertThat(warnings).filteredOn(warning -> warning.contains("'" + sibling.getName() + "'"))
                        .singleElement().asString().contains("Interface B", "silence");
            }
            assertThat(warnings).hasSize(2).noneMatch(warning -> warning.contains("'Primary'"));
        } finally {
            try {
                PipelineLifecycleTestSupport.endTheTake(pipeline);
            } finally {
                try { engine.stopAudioOutput(); }
                finally { engine.shutdown(); }
            }
        }
        List<TakeManifest.RoutingFlag> flags = TakeManifest.read(directory.resolve(TakeManifest.FILE_NAME)).routingFlags();
        assertThat(flags).extracting(TakeManifest.RoutingFlag::trackId)
                .containsExactlyInAnyOrder(siblingLeft.getId(), siblingRight.getId());
        assertThat(flags).allSatisfy(flag -> {
            assertThat(flag.device()).contains("Interface B");
            assertThat(flag.availableChannels()).isZero();
        });
    }

    /** The engine's own append, entered with a replaced plan: the publish a racing terminal signal makes. */
    private static void appendClockedSilenceLate(AudioEngine engine, CaptureRoutingPlan plan, int source) throws Exception {
        var append = AudioEngine.class.getDeclaredMethod("clockInputSourceSilently", CaptureRoutingPlan.class, int.class);
        append.setAccessible(true);
        append.invoke(engine, plan, source);
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

    @Test void devicesWithoutASharedClockAreRefusedAtArmAndAtRecordStartWithTrackAndDeviceNames() {
        PatternBackend backend = new PatternBackend(); backend.sharedClock = false;
        AudioEngine engine = engine(backend,2);
        List<Track> tracks = List.of(track("Kick",0,1,0), track("Room microphone",0,1,1));
        try {
            assertThatThrownBy(() -> engine.validateInputRouting(tracks))
                    .isInstanceOf(AudioBackendException.class).hasMessageContaining("Room microphone")
                    .hasMessageContaining("Interface B").hasMessageContaining("not clock-synchronized");
            assertThatThrownBy(() -> engine.startAudioInputOutput(tracks))
                    .isInstanceOf(AudioBackendException.class).hasMessageContaining("Room microphone")
                    .hasMessageContaining("Interface B").hasMessageContaining("not clock-synchronized");
            assertThat(backend.opens).isZero(); assertThat(backend.sibling).isNull();
        } finally { engine.stopAudioOutput(); engine.shutdown(); }
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
                    .hasMessageContainingAll("Interface B","44100","48000");
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
                .isInstanceOf(java.io.IOException.class).hasMessageContainingAll("broken.manifest:1", "routing-unavailable");
        assertThatThrownBy(() -> TakeManifest.parse("routing-unavailable=track|VVNC|6|0|7", "broken.manifest"))
                .isInstanceOf(java.io.IOException.class).hasMessageContainingAll("broken.manifest:1", "routing-unavailable");
    }

    private static float[] filled(float value,int count) { float[] result=new float[count]; Arrays.fill(result,value); return result; }

    private static final class RenderGate {
        private final Semaphore permits = new Semaphore(0);
        private final Semaphore rendered = new Semaphore(0);
        private volatile float[] lastOutput;

        void awaitRendered() throws InterruptedException {
            assertThat(rendered.tryAcquire(5, TimeUnit.SECONDS)).as("the next paced block rendered").isTrue();
        }

        void advance() throws InterruptedException {
            permits.release();
            awaitRendered();
        }

        void pace() {
            try { permits.acquire(); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        }

        void rendered(AudioBlock block) {
            lastOutput = block.samples().clone();
            rendered.release();
        }
    }

    static final class PatternBackend implements AudioBackend {
        int maximum=8, siblingMaximum=-1, openedWidthOverride=-1, siblingOpenedWidth=-1, openedWidth, outputWidth, opens;
        boolean multiple=true, sharedClock=true, open, failClose, failSiblingOpen, failOpen, failInputResolution, failEnumeration;
        InputTermination terminatesOnSubscribe, siblingTerminatesOnSubscribe;
        String input="Interface A";
        double openedRate=48000,siblingRate=48000;
        RoundTripLatency primaryLatency = RoundTripLatency.UNKNOWN;
        PatternBackend sibling;
        RenderGate renderGate;
        final List<Flow.Subscriber<? super AudioBlock>> subscribers=new CopyOnWriteArrayList<>();
        String name="Pattern ASIO";
        List<String> deviceNames=List.of("Interface A","Interface B");
        @Override public String name(){return name;}
        @Override public boolean isAvailable(){return true;}
        @Override public boolean isOpen(){return open;}
        @Override public boolean supportsStreaming(){return true;}
        @Override public boolean supportsMultipleInputDevices(){return multiple;}
        // The fixture models a word-clocked rig unless a test clears sharedClock.
        @Override public boolean sharesClockDomain(DeviceId first,DeviceId second){return sharedClock || first.equals(second);}
        @Override public DeviceId selectedInputDevice(DeviceId output) {
            if (failInputResolution) throw new AudioBackendException("head has no input device");
            return output;
        }
        @Override public List<AudioDeviceInfo> listDevices(){if(failEnumeration)throw new AssertionError("Provider enumeration must stay off take preparation");return List.of(info(0,deviceNames.get(0)),info(1,deviceNames.get(1)));}
        AudioDeviceInfo info(int index,String name){return new AudioDeviceInfo(index,name,"Pattern",index == 1 && siblingMaximum >= 0 ? siblingMaximum : maximum,2,48000,List.of(SampleRate.HZ_48000),0,0);}
        @Override public void open(DeviceId device,com.benesquivelmusic.daw.sdk.audio.AudioFormat format,int frames){
            open(device,format,frames,CaptureRequirement.REQUIRED,device,format.channels());
        }
        @Override public void open(DeviceId output,com.benesquivelmusic.daw.sdk.audio.AudioFormat format,int frames,
                                   CaptureRequirement capture,DeviceId input,int width){
            if(open)throw new IllegalStateException("already open"); open=true;opens++;
            if(failOpen)throw new AudioBackendException("second input refused");
            openedWidth=openedWidthOverride >= 0 ? openedWidthOverride : Math.min(maximum,width);outputWidth=format.channels();this.input=input.name();
        }
        @Override public AudioBackend createInputBackend(){sibling=new PatternBackend();sibling.name=name;sibling.maximum=siblingMaximum >= 0 ? siblingMaximum : maximum;sibling.openedWidthOverride=siblingOpenedWidth;sibling.failOpen=failSiblingOpen;sibling.openedRate=siblingRate;sibling.terminatesOnSubscribe=siblingTerminatesOnSubscribe;return sibling;}
        @Override public void openInput(DeviceId input,com.benesquivelmusic.daw.sdk.audio.AudioFormat format,int frames,int width){
            open(input,format,frames,CaptureRequirement.REQUIRED,input,width);
        }
        @Override public int openedInputChannels(){return open?openedWidth:0;}
        @Override public double openedInputSampleRate(){return openedRate;}
        @Override public RoundTripLatency reportedLatency(){return input.contains("B")?new RoundTripLatency(12,0,0):primaryLatency;}
        @Override public Flow.Publisher<AudioBlock> inputBlocks(){return subscriber->{subscribers.add(subscriber);subscriber.onSubscribe(new Flow.Subscription(){
            public void request(long n){} public void cancel(){subscribers.remove(subscriber);}
        });if(terminatesOnSubscribe!=null)terminatesOnSubscribe.signal(subscriber);};}
        @Override public void sink(AudioBlock block){if(renderGate != null)renderGate.rendered(block);}
        @Override public void awaitSinkCapacity(long timeoutNanos){
            if(renderGate == null)AudioBackend.super.awaitSinkCapacity(timeoutNanos);else renderGate.pace();
        }
        @Override public void close(){if(failClose)throw new AudioBackendException("held sibling");open=false;}
        void emit(int frames){float[] samples=new float[frames*openedWidth];for(int f=0;f<frames;f++)for(int c=0;c<openedWidth;c++)
            samples[f*openedWidth+c]=input.contains("B")?.9f:(c+1)/10f;
            AudioBlock block=new AudioBlock(48000,openedWidth,frames,samples);subscribers.forEach(subscriber->subscriber.onNext(block));}
    }
}
