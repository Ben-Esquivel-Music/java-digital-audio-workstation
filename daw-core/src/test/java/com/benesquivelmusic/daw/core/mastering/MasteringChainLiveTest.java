package com.benesquivelmusic.daw.core.mastering;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.dsp.CompressorProcessor;
import com.benesquivelmusic.daw.core.dsp.GainStagingProcessor;
import com.benesquivelmusic.daw.core.metering.MeterFrame;
import com.benesquivelmusic.daw.core.metering.MeterTapPoint;
import com.benesquivelmusic.daw.core.metering.MeteringTapBus;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.mastering.MasteringStageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

class MasteringChainLiveTest {

    @Test
    void stageLimitMatchesTheBoundedMeterLaneCapacityAndRejectsEditsAtomically() {
        var chain = new MasteringChain();
        var stage = stage("Gain", new AffineProcessor(1.0f, 0.0f));
        chain.replaceStages(java.util.Collections.nCopies(MeterTapPoint.MAX_MASTERING_STAGES, stage));
        var before = chain.getStages();

        assertThatIllegalArgumentException().isThrownBy(() ->
                chain.addStage(MasteringStageType.GAIN_STAGING, "Overflow", stage.getProcessor()));
        assertThat(chain.getStages()).containsExactlyElementsOf(before);
        assertThatIllegalArgumentException().isThrownBy(() ->
                chain.insertStage(0, MasteringStageType.GAIN_STAGING, "Overflow", stage.getProcessor()));
        assertThat(chain.getStages()).containsExactlyElementsOf(before);
        assertThatIllegalArgumentException().isThrownBy(() -> chain.replaceStages(
                java.util.Collections.nCopies(MeterTapPoint.MAX_MASTERING_STAGES + 1, stage)));
        assertThat(chain.getStages()).containsExactlyElementsOf(before);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stereoMasteringPreservesAdditionalDeviceLanes(boolean inPlace) {
        var chain = new MasteringChain();
        chain.addStage(MasteringStageType.GAIN_STAGING, "Half", new AffineProcessor(0.5f, 0.0f));
        chain.allocateIntermediateBuffers(2, 2);
        var input = new float[][] {{0.8f, -0.8f}, {0.6f, -0.6f}, {0.4f, -0.4f}, {0.2f, -0.2f}};
        var output = inPlace ? input : new float[4][2];

        chain.process(input, output, 2);

        assertThat(output[0]).containsExactly(0.4f, -0.4f);
        assertThat(output[1]).containsExactly(0.3f, -0.3f);
        assertThat(output[2]).containsExactly(0.4f, -0.4f);
        assertThat(output[3]).containsExactly(0.2f, -0.2f);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void monoOutputDuplicatesInputForStereoProcessorsAndReceivesTheLeftResult(boolean inPlace) {
        var chain = new MasteringChain();
        chain.addStage(MasteringStageType.STEREO_IMAGING, "Fold", new StereoFoldProcessor());
        chain.allocateIntermediateBuffers(2, 2);
        var input = new float[][] {{0.8f, -0.8f}};
        var output = inPlace ? input : new float[1][2];

        chain.process(input, output, 2);

        assertThat(output[0]).containsExactly(0.4f, -0.4f);
    }

    @Test
    void parameterChangesWaitForTheNextRenderBlock() {
        var chain = new MasteringChain();
        var gain = new GainStagingProcessor(2, 0.0);
        chain.addStage(MasteringStageType.GAIN_STAGING, "Gain", gain);
        var updates = new AtomicInteger();
        assertThat(chain.enqueueParameterUpdate(() -> {
            gain.setGainDb(-6.0);
            updates.incrementAndGet();
        })).isTrue();
        assertThat(gain.getGainDb()).isZero();
        var input = new float[][] {{1.0f}, {1.0f}};
        var output = new float[2][1];

        chain.process(input, output, 1);

        assertThat(output[0][0]).isCloseTo((float) Math.pow(10.0, -6.0 / 20.0), within(1e-6f));
        assertThat(updates.get()).isEqualTo(1);
        chain.process(input, output, 1);
        assertThat(updates.get()).isEqualTo(1);
    }

    @Test
    void saturatedParameterQueueRejectsExcessWorkAndAcceptsUpdatesAfterDrain() {
        var chain = new MasteringChain();
        var updates = new AtomicInteger();
        int accepted = 0;
        while (accepted < 1_024 && chain.enqueueParameterUpdate(updates::incrementAndGet)) {
            accepted++;
        }
        assertThat(accepted).isBetween(1, 1_023);
        assertThat(updates.get()).isZero();
        var input = new float[][] {{0.25f}, {0.25f}};
        var output = new float[2][1];

        chain.process(input, output, 1);

        assertThat(updates.get()).isEqualTo(accepted);
        assertThat(chain.enqueueParameterUpdate(updates::incrementAndGet)).isTrue();
        chain.process(input, output, 1);
        assertThat(updates.get()).isEqualTo(accepted + 1);
    }

    @Test
    @Timeout(15)
    void replacingStagesDuringRenderKeepsTheCapturedStagesAndScratchTogether() throws Exception {
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var chain = new MasteringChain();
        chain.addStage(MasteringStageType.GAIN_STAGING, "Blocked gain", new BlockingGain(entered, resume));
        chain.addStage(MasteringStageType.EQ_CORRECTIVE, "Double", new AffineProcessor(2.0f, 0.0f));
        chain.addStage(MasteringStageType.EQ_TONAL, "Add", new AffineProcessor(1.0f, 1.0f));
        chain.allocateIntermediateBuffers(2, 1);
        var input = new float[][] {{1.0f}, {1.0f}};
        var output = new float[2][1];
        var failure = new AtomicReference<Throwable>();
        Thread render = Thread.ofPlatform().name("mastering-snapshot-render").unstarted(() -> {
            try {
                chain.process(input, output, 1);
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        render.start();
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            chain.replaceStages(List.of(stage("Replacement", new AffineProcessor(7.0f, 0.0f))));
        } finally {
            resume.countDown();
            render.join(5_000);
        }

        assertThat(render.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        assertThat(output[0][0]).isEqualTo(5.0f);
        assertThat(output[1][0]).isEqualTo(5.0f);
        chain.process(input, output, 1);
        assertThat(output[0][0]).isEqualTo(7.0f);
    }

    @Test
    @Timeout(15)
    void concurrentPresetReplacementAndMovesNeverExposePartialChains() throws Exception {
        var chain = new MasteringChain();
        var add = stage("Add", new AffineProcessor(1.0f, 1.0f));
        var multiply = stage("Multiply", new AffineProcessor(2.0f, 0.0f));
        var replacement = stage("Five", new AffineProcessor(5.0f, 0.0f));
        var ordered = List.of(add, multiply);
        chain.replaceStages(ordered);
        chain.allocateIntermediateBuffers(2, 8);
        var start = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        Thread editing = Thread.ofPlatform().name("mastering-stage-editor").unstarted(() -> {
            try {
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("render did not start");
                }
                for (int iteration = 0; iteration < 2_000; iteration++) {
                    chain.replaceStages(ordered);
                    chain.moveStage(0, 1);
                    chain.replaceStages(List.of(replacement));
                }
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        var input = new float[2][8];
        Arrays.fill(input[0], 1.0f);
        Arrays.fill(input[1], 1.0f);
        var output = new float[2][8];
        editing.start();
        start.countDown();
        try {
            for (int iteration = 0; iteration < 5_000; iteration++) {
                chain.process(input, output, 8);
                float value = output[0][0];
                assertThat(value).isIn(3.0f, 4.0f, 5.0f);
                for (float[] channel : output) {
                    for (float sample : channel) assertThat(sample).isEqualTo(value);
                }
            }
        } finally {
            editing.join(5_000);
        }
        assertThat(editing.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
    }

    @Test
    void stageLaneReportsActualCompressionAndReturnsToIdleForBypass() {
        var compressor = new CompressorProcessor(2, 48_000.0);
        compressor.setThresholdDb(-20.0);
        compressor.setRatio(4.0);
        compressor.setKneeDb(0.0);
        compressor.setAttackMs(0.0);
        var chain = new MasteringChain();
        chain.addStage(MasteringStageType.COMPRESSION, "Compression", compressor);
        int frames = 4_096;
        var input = new float[2][frames];
        Arrays.fill(input[0], 0.8f);
        Arrays.fill(input[1], 0.8f);
        var output = new float[2][frames];
        var bus = new MeteringTapBus();
        try {
            bus.rebind(new Mixer(), new AudioFormat(48_000.0, 2, 24, frames), 1L);
            var subscription = bus.attachLevel(new MeterTapPoint.MasteringStage(0));
            var taps = bus.snapshot();
            chain.process(input, output, frames, taps);
            bus.blockCompleted(taps);
            var frame = new MeterFrame();

            assertThat(subscription.readInto(frame)).isTrue();
            assertThat(frame.gainReductionDb()).isLessThan(-1.0);
            assertThat(frame.gainReductionDb()).isEqualTo(compressor.getGainReductionDb());
            assertThat(frame.inputPeakDb()).isCloseTo(MeterFrame.toDb(0.8), within(1e-5));
            double sumSquares = 0.0;
            float peak = 0.0f;
            for (float sample : output[0]) {
                peak = Math.max(peak, Math.abs(sample));
                sumSquares += (double) sample * sample;
            }
            assertThat(frame.peak(0)).isEqualTo(peak);
            assertThat(frame.rms(0)).isCloseTo((float) Math.sqrt(sumSquares / frames), within(1e-6f));

            chain.getStages().getFirst().setBypassed(true);
            chain.process(input, output, frames, taps);
            bus.blockCompleted(taps);

            assertThat(subscription.readInto(frame)).isTrue();
            assertThat(frame.isSilent()).isTrue();
            assertThat(frame.gainReductionDb()).isNaN();
            assertThat(output[0]).containsOnly(0.8f);

            chain.getStages().getFirst().setBypassed(false);
            chain.process(input, output, frames, taps);
            bus.blockCompleted(taps);
            assertThat(subscription.readInto(frame)).isTrue();
            assertThat(frame.gainReductionDb()).isLessThan(-1.0);

            chain.setChainBypassed(true);
            chain.process(input, output, frames, taps);
            bus.blockCompleted(taps);

            assertThat(subscription.readInto(frame)).isTrue();
            assertThat(frame.isSilent()).isTrue();
            assertThat(frame.gainReductionDb()).isNaN();
            assertThat(output[0]).containsOnly(0.8f);
        } finally {
            bus.close();
        }
    }

    @Test
    void latestControlValueSurvivesClosingBeforeRenderAndRetiredSlotsAreReclaimed() {
        var chain = new MasteringChain();
        var value = new AtomicInteger();
        var control = chain.createParameterControl();
        for (int i = 1; i <= 2_000; i++) {
            int next = i;
            control.submit(() -> value.set(next));
        }
        control.close();
        assertThat(value.get()).isZero();
        var input = new float[2][1];
        chain.process(input, input, 1);
        assertThat(value.get()).isEqualTo(2_000);
        for (int i = 0; i < 2_000; i++) {
            chain.createParameterControl().close();
        }
        try (var replacement = chain.createParameterControl()) {
            replacement.submit(() -> value.set(3_000));
            chain.process(input, input, 1);
            assertThat(value.get()).isEqualTo(3_000);
        }
    }

    @Test
    @Timeout(15)
    void bypassingTheLastStageDuringRenderDoesNotLeaveTheOutputUnwritten() throws Exception {
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var chain = new MasteringChain();
        chain.addStage(MasteringStageType.GAIN_STAGING, "Add", new BlockingGain(entered, resume));
        chain.addStage(MasteringStageType.EQ_TONAL, "Double", new AffineProcessor(2, 0));
        chain.allocateIntermediateBuffers(2, 1);
        var input = new float[][] {{1}, {1}};
        var output = new float[][] {{-99}, {-99}};
        var failure = new AtomicReference<Throwable>();
        Thread render = Thread.ofPlatform().start(() -> {
            try {
                chain.process(input, output, 1);
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            chain.getStages().getLast().setBypassed(true);
        } finally {
            resume.countDown();
            render.join(5_000);
        }
        assertThat(render.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        assertThat(output[0]).containsExactly(4);
        chain.process(input, output, 1);
        assertThat(output[0]).containsExactly(2);
    }

    private static MasteringChain.Stage stage(String name, AudioProcessor processor) {
        return new MasteringChain.Stage(MasteringStageType.GAIN_STAGING, name, processor);
    }

    private record AffineProcessor(float gain, float offset) implements AudioProcessor {
        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            for (int channel = 0; channel < 2; channel++) {
                for (int frame = 0; frame < numFrames; frame++) {
                    outputBuffer[channel][frame] = inputBuffer[channel][frame] * gain + offset;
                }
            }
        }

        @Override public void reset() { }
        @Override public int getInputChannelCount() { return 2; }
        @Override public int getOutputChannelCount() { return 2; }
    }

    private record BlockingGain(CountDownLatch entered, CountDownLatch resume) implements AudioProcessor {
        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            entered.countDown();
            try {
                if (!resume.await(5, TimeUnit.SECONDS)) throw new AssertionError("editor did not resume render");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError(error);
            }
            for (int channel = 0; channel < 2; channel++) {
                for (int frame = 0; frame < numFrames; frame++) {
                    outputBuffer[channel][frame] = inputBuffer[channel][frame] + 1.0f;
                }
            }
        }

        @Override public void reset() { }
        @Override public int getInputChannelCount() { return 2; }
        @Override public int getOutputChannelCount() { return 2; }
    }

    private static final class StereoFoldProcessor implements AudioProcessor {
        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            assertThat(inputBuffer.length).isEqualTo(2);
            assertThat(outputBuffer.length).isEqualTo(2);
            for (int frame = 0; frame < numFrames; frame++) {
                float left = inputBuffer[0][frame];
                float right = inputBuffer[1][frame];
                outputBuffer[0][frame] = (left + right) * 0.25f;
                outputBuffer[1][frame] = (left - right) * 0.25f;
            }
        }

        @Override public void reset() { }
        @Override public int getInputChannelCount() { return 2; }
        @Override public int getOutputChannelCount() { return 2; }
    }
}
