package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.analysis.LoudnessMeter;
import com.benesquivelmusic.daw.core.dsp.CompressorProcessor;
import com.benesquivelmusic.daw.core.mastering.MasteringChain;
import com.benesquivelmusic.daw.core.metering.MeterFrame;
import com.benesquivelmusic.daw.core.metering.MeterTapPoint;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.audio.MixPrecision;
import com.benesquivelmusic.daw.sdk.mastering.MasteringStageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class LiveMasterBusTest {
    private static final int BLOCK = 480;
    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 24, BLOCK);

    @ParameterizedTest
    @EnumSource(MixPrecision.class)
    void rackInsertIsAudibleAndRemovalRestoresDryEngineOutput(MixPrecision precision) {
        var project = constantProject();
        project.getMixer().setMixPrecision(precision);
        var engine = start(project);
        try {
            float dry = render(engine)[0][0];
            var master = project.getMixer().getMasterChannel();
            var slot = new InsertSlot("Rack gain", new AffineProcessor(0.5f, 0));
            master.addInsert(slot);
            assertThat(render(engine)[0][0]).isCloseTo(dry * 0.5f, within(1e-7f));
            master.removeInsert(slot);
            assertThat(render(engine)[0][0]).isCloseTo(dry, within(1e-7f));
        } finally {
            engine.shutdown();
        }
    }

    @ParameterizedTest
    @EnumSource(MixPrecision.class)
    void insertsThenMasteringThenMonitorGainAndMuteWithAudibleAb(MixPrecision precision) {
        var project = constantProject();
        project.getMixer().setMixPrecision(precision);
        var engine = start(project);
        try {
            float dry = render(engine)[0][0];
            var master = project.getMixer().getMasterChannel();
            master.addInsert(new InsertSlot("Offset", new AffineProcessor(1, 0.1f)));
            engine.getMasteringChain().addStage(MasteringStageType.GAIN_STAGING,
                    "Gain", new AffineProcessor(2, 0));
            master.setVolume(0.25);
            var chainTap = engine.meteringTapBus().attachLevel(MeterTapPoint.MASTER_CHAIN);
            var outTap = engine.meteringTapBus().attachLevel(MeterTapPoint.MASTER_OUT);
            var chainFrame = new MeterFrame();
            var outFrame = new MeterFrame();
            float mastered = (dry + 0.1f) * 2;
            assertThat(render(engine)[0][0]).isCloseTo(mastered * 0.25f, within(1e-7f));
            assertThat(chainTap.readInto(chainFrame)).isTrue();
            assertThat(outTap.readInto(outFrame)).isTrue();
            assertThat(chainFrame.peak(0)).isCloseTo(mastered, within(1e-7f));
            assertThat(outFrame.peak(0)).isCloseTo(mastered * 0.25f, within(1e-7f));

            engine.getMasteringChain().setChainBypassed(true);
            assertThat(render(engine)[0][0]).isCloseTo((dry + 0.1f) * 0.25f, within(1e-7f));
            engine.getMasteringChain().setChainBypassed(false);
            master.setMuted(true);
            assertThat(render(engine)[0]).containsOnly(0f);
            assertThat(chainTap.readInto(chainFrame)).isTrue();
            assertThat(chainFrame.peak(0)).isCloseTo(mastered, within(1e-7f));
            assertThat(outTap.readInto(outFrame)).isTrue();
            assertThat(outFrame.isSilent()).isTrue();
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void engineDynamicsPublishRealGainReductionAndStoppedStageIsIdle() {
        var project = constantProject();
        var engine = start(project);
        try {
            var compressor = new CompressorProcessor(48_000, 2);
            compressor.setThresholdDb(-40);
            compressor.setRatio(8);
            compressor.setAttackMs(0.1);
            engine.getMasteringChain().addStage(MasteringStageType.COMPRESSION, "Dynamics", compressor);
            var subscription = engine.meteringTapBus().attachLevel(new MeterTapPoint.MasteringStage(0));
            for (int i = 0; i < 20; i++) render(engine);
            var frame = new MeterFrame();
            assertThat(subscription.readInto(frame)).isTrue();
            assertThat(frame.gainReductionDb()).isLessThan(-1);
            assertThat(frame.gainReductionDb()).isEqualTo(compressor.getGainReductionDb());
            project.getTransport().stop();
            render(engine);
            assertThat(subscription.readInto(frame)).isTrue();
            assertThat(frame.isSilent()).isTrue();
            assertThat(frame.gainReductionDb()).isNaN();
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void livePreFaderLoudnessMatchesOfflineProgrammeDespiteMovingMonitorGain() throws Exception {
        int programmeFrames = 48_000 * 4;
        var liveProject = sineProject(programmeFrames);
        var engine = start(liveProject);
        try {
            installProgrammeChain(liveProject, engine.getMasteringChain());
            var liveMeter = new LoudnessMeter(48_000, BLOCK);
            var delivered = new Semaphore(0);
            var analysis = engine.meteringTapBus().attachAnalysis(MeterTapPoint.MASTER_CHAIN, 8,
                    (samples, channels, frames, rate) -> {
                        liveMeter.process(samples[0], samples[1], frames);
                        delivered.release();
                    });
            var out = engine.meteringTapBus().attachLevel(MeterTapPoint.MASTER_OUT);
            var chain = engine.meteringTapBus().attachLevel(MeterTapPoint.MASTER_CHAIN);
            var outFrame = new MeterFrame();
            var chainFrame = new MeterFrame();
            for (int block = 0; block < programmeFrames / BLOCK; block++) {
                double monitorGain = block % 3 == 0 ? 0.0 : block % 3 == 1 ? 0.1 : 0.7;
                liveProject.getMixer().getMasterChannel().setVolume(monitorGain);
                render(engine);
                assertThat(delivered.tryAcquire(5, TimeUnit.SECONDS)).isTrue();
                assertThat(out.readInto(outFrame)).isTrue();
                assertThat(chain.readInto(chainFrame)).isTrue();
                assertThat(outFrame.maxPeak()).isCloseTo((float) (chainFrame.maxPeak() * monitorGain), within(1e-6f));
            }
            assertThat(analysis.droppedBlocks()).isZero();

            var offlineProject = sineProject(programmeFrames);
            var offlineChain = new MasteringChain();
            installProgrammeChain(offlineProject, offlineChain);
            offlineProject.getMixer().prepareForPlayback(2, BLOCK);
            offlineProject.getTransport().play();
            var offlineOutput = new float[2][programmeFrames];
            new RenderPipeline(FORMAT, 4, BLOCK).renderOffline(offlineProject.getTransport(),
                    offlineProject.getMixer(), offlineProject.getTracks(), null, offlineChain,
                    offlineOutput, programmeFrames, BLOCK);
            var offlineMeter = new LoudnessMeter(48_000, BLOCK);
            offlineMeter.process(offlineOutput[0], offlineOutput[1], programmeFrames);
            assertThat(liveMeter.getLatestData().integratedLufs()).isFinite()
                    .isCloseTo(offlineMeter.getLatestData().integratedLufs(), within(0.01));
            assertThat(liveMeter.getLatestData().momentaryLufs())
                    .isCloseTo(offlineMeter.getLatestData().momentaryLufs(), within(0.01));
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void secondMasterInsertSeamHasBeenRemoved() throws Exception {
        assertThat(Arrays.stream(AudioEngine.class.getMethods()).map(method -> method.getName()))
                .doesNotContain("getMaster" + "Chain");
        Path sourceRoot = Path.of("src/main/java");
        try (var files = Files.walk(sourceRoot)) {
            var sources = files.filter(path -> path.toString().endsWith(".java")).toList();
            assertThat(sources).isNotEmpty();
            for (Path source : sources) {
                assertThat(Files.readString(source)).as("source %s", source)
                        .doesNotContain("getMaster" + "Chain(");
            }
        }
    }

    private static void installProgrammeChain(DawProject project, MasteringChain chain) {
        project.getMixer().getMasterChannel().addInsert(new InsertSlot("Master gain", new AffineProcessor(0.8f, 0)));
        chain.addStage(MasteringStageType.GAIN_STAGING, "Mastering gain", new AffineProcessor(0.5f, 0));
    }

    private static DawProject constantProject() {
        var project = new DawProject("Master", FORMAT);
        var track = project.createAudioTrack("Programme");
        var data = new float[2][48_000];
        for (float[] lane : data) Arrays.fill(lane, 0.25f);
        var clip = new AudioClip("Constant", 0, 8, null);
        clip.setAudioData(data);
        track.addClip(clip);
        return project;
    }

    private static DawProject sineProject(int frames) {
        var project = new DawProject("Programme", FORMAT);
        var track = project.createAudioTrack("Tone");
        var data = new float[2][frames];
        for (int frame = 0; frame < frames; frame++) {
            float sample = (float) (0.5 * Math.sin(2 * Math.PI * 997 * frame / 48_000));
            data[0][frame] = sample;
            data[1][frame] = sample;
        }
        var clip = new AudioClip("Tone", 0, frames / 24_000.0, null);
        clip.setAudioData(data);
        track.addClip(clip);
        return project;
    }

    private static AudioEngine start(DawProject project) {
        var engine = new AudioEngine(FORMAT);
        new EngineBinder(engine).bind(project);
        project.getTransport().play();
        engine.start();
        return engine;
    }

    private static float[][] render(AudioEngine engine) {
        var output = new float[2][BLOCK];
        engine.processBlock(null, output, BLOCK, new float[BLOCK * 2]);
        return output;
    }

    private record AffineProcessor(float gain, float offset) implements AudioProcessor {
        @Override public void process(float[][] input, float[][] output, int frames) {
            for (int channel = 0; channel < Math.min(input.length, output.length); channel++) {
                for (int frame = 0; frame < frames; frame++) output[channel][frame] = input[channel][frame] * gain + offset;
            }
        }
        @Override public void reset() { }
        @Override public int getInputChannelCount() { return 2; }
        @Override public int getOutputChannelCount() { return 2; }
    }
}
