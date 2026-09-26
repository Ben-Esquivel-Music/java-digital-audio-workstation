package com.benesquivelmusic.daw.core.metering;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.dsp.GainStagingProcessor;
import com.benesquivelmusic.daw.core.mastering.MasteringChain;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.sdk.mastering.MasteringStageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class MasteringStageTapTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 24, 64);
    private final MeteringTapBus bus = new MeteringTapBus();
    private final Mixer mixer = new Mixer();

    @BeforeEach
    void bind() {
        bus.rebind(mixer, FORMAT, 1L);
    }

    @AfterEach
    void close() {
        bus.close();
    }

    @Test
    void stageIdentifiersHaveValueSemanticsAndBoundedIndices() {
        assertThat(new MeterTapPoint.MasteringStage(3))
                .isEqualTo(new MeterTapPoint.MasteringStage(3))
                .hasSameHashCodeAs(new MeterTapPoint.MasteringStage(3))
                .isNotEqualTo(new MeterTapPoint.MasteringStage(4));
        assertThatIllegalArgumentException().isThrownBy(() -> new MeterTapPoint.MasteringStage(-1));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new MeterTapPoint.MasteringStage(MeterTapPoint.MAX_MASTERING_STAGES));
        assertThat(bus.snapshot().masteringStage(-1)).isNull();
        assertThat(bus.snapshot().masteringStage(Integer.MAX_VALUE)).isNull();
    }

    @Test
    void stagesCostNothingWithoutDemandAndDisappearAfterTheLastDetach() {
        var initial = bus.snapshot();
        var first = bus.attachLevel(new MeterTapPoint.MasteringStage(3));
        var second = bus.attachLevel(new MeterTapPoint.MasteringStage(3));
        var observed = bus.snapshot();
        assertThat(initial.isEmpty()).isTrue();
        assertThat(initial.masteringStage(3)).isNull();
        assertThat(observed.isEmpty()).isFalse();
        assertThat(observed.masteringStage(3)).isNotNull();
        assertThat(observed.masteringStage(2)).isNull();

        first.dispose();
        assertThat(bus.snapshot().masteringStage(3)).isSameAs(observed.masteringStage(3));
        second.dispose();

        assertThat(bus.snapshot().masteringStage(3)).isNull();
        assertThat(bus.snapshot().isEmpty()).isTrue();
        assertThat(observed.masteringStage(3)).as("an in-flight snapshot remains immutable").isNotNull();
    }

    @Test
    void newEpochDisposesSubscribersAndCannotReadThePreviousStages() {
        var point = new MeterTapPoint.MasteringStage(MeterTapPoint.MAX_MASTERING_STAGES - 1);
        var old = bus.attachLevel(point);
        var oldSnapshot = bus.snapshot();
        var oldSlot = oldSnapshot.masteringStage(point.stageIndex());
        oldSlot.beginBlock(1L, 0L, 1);
        oldSlot.setMasteringLevels(-2.0, 4.0);
        oldSlot.publish(1);
        var frame = new MeterFrame();
        assertThat(old.readInto(frame)).isTrue();

        bus.rebind(mixer, FORMAT, 2L);
        var current = bus.attachLevel(point);

        assertThat(old.isDisposed()).isTrue();
        assertThat(old.readInto(frame)).isFalse();
        assertThat(bus.snapshot().masteringStage(point.stageIndex())).isNotSameAs(oldSlot);
        oldSlot.publishSilence(1L, 1L, 1);
        assertThat(current.readInto(frame)).isFalse();
    }

    @Test
    void snapshotAbortPublishesIdleMasteringReadings() {
        var subscription = bus.attachLevel(new MeterTapPoint.MasteringStage(0));
        var taps = bus.snapshot();
        taps.beginPublication();
        var stage = taps.masteringStage(0);
        stage.beginBlock(taps.epoch(), taps.blockIndex(), 1);
        stage.accumulate(0, 0.5f);
        stage.setMasteringLevels(-2.0, 4.0);
        stage.publish(1);
        var frame = new MeterFrame();
        assertThat(subscription.readInto(frame)).isFalse();

        taps.abortBlock(1, 1);
        bus.blockCompleted(taps);

        assertThat(subscription.readInto(frame)).isTrue();
        assertThat(frame.isSilent()).isTrue();
        assertThat(frame.inputPeakDb()).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(frame.gainReductionDb()).isNaN();
        assertThat(frame.epoch()).isEqualTo(taps.epoch());
    }

    @Test
    void analysisAttachmentPreservesStageScalarsAndLeavesOldRingGenerationUnchanged() {
        var point = new MeterTapPoint.MasteringStage(0);
        var subscription = bus.attachLevel(point);
        var initial = bus.snapshot().masteringStage(0);
        initial.beginBlock(1L, 0L, 1);
        initial.setMasteringLevels(-2.0, 4.0);
        initial.publish(1);
        var analysis = bus.attachAnalysis(point, 2, (samples, channels, frames, rate) -> { });

        assertThat(bus.snapshot().hasAnalysisRings()).isTrue();
        assertThat(initial.rings()).isEmpty();
        assertThat(bus.snapshot().masteringStage(0).rings()).hasSize(1);
        var frame = new MeterFrame();
        assertThat(subscription.readInto(frame)).isTrue();
        assertThat(frame.inputPeakDb()).isEqualTo(-2.0);
        assertThat(frame.gainReductionDb()).isEqualTo(4.0);

        analysis.dispose();

        assertThat(bus.snapshot().hasAnalysisRings()).isFalse();
        assertThat(subscription.readInto(frame)).isTrue();
        assertThat(frame.gainReductionDb()).isEqualTo(4.0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"stopped", "stage bypass", "chain bypass", "solo exclusion"})
    void idleStagePublishesOneSilentAnalysisBlockAlongsideItsLevels(String idleMode) {
        var chain = new MasteringChain();
        chain.addStage(MasteringStageType.GAIN_STAGING, "Gain", new GainStagingProcessor(2, 0));
        chain.addStage(MasteringStageType.EQ_TONAL, "Other", new GainStagingProcessor(2, 0));
        chain.allocateIntermediateBuffers(2, 4);
        var ring = new SampleBlockRing(4, 4);
        var slot = new LevelTapSlot(new MeterTapPoint.MasteringStage(0), new SampleBlockRing[] {ring});
        var taps = new TapSnapshot(bus, mixer, 1L, FORMAT,
                new MixerChannel[0], new LevelTapSlot[0], new MixerChannel[0], new LevelTapSlot[0],
                null, null, new LevelTapSlot[] {slot}, new InsertSlot[0], new MixerChannel[0],
                new InsertTapPair[0]);
        float[][] input = {{0.25f, 0.5f, -0.5f, -0.25f}, {0.25f, 0.5f, -0.5f, -0.25f}};
        var output = new float[2][4];
        var analysis = new float[2][4];
        taps.beginPublication();
        chain.process(input, output, 4, taps);
        bus.blockCompleted(taps);
        assertThat(ring.readInto(analysis)).isEqualTo(4);
        assertThat(analysis[0]).containsExactly(input[0]);

        switch (idleMode) {
            case "stage bypass" -> chain.getStages().getFirst().setBypassed(true);
            case "chain bypass" -> chain.setChainBypassed(true);
            case "solo exclusion" -> chain.getStages().getLast().setSolo(true);
            default -> { }
        }
        taps.beginPublication();
        chain.process(input, output, 3, taps, !idleMode.equals("stopped"));
        assertThat(ring.readInto(analysis)).as("no tentative audio escapes before block completion").isEqualTo(-1);
        bus.blockCompleted(taps);

        assertThat(ring.readInto(analysis)).isEqualTo(3);
        assertThat(ring.lastChannelCount()).isEqualTo(2);
        assertThat(java.util.Arrays.copyOf(analysis[0], 3)).containsOnly(0f);
        assertThat(java.util.Arrays.copyOf(analysis[1], 3)).containsOnly(0f);
        assertThat(ring.readInto(analysis)).as("one publication per block").isEqualTo(-1);
        var frame = new MeterFrame();
        assertThat(slot.readInto(frame)).isTrue();
        assertThat(frame.isSilent()).isTrue();
        assertThat(frame.blockIndex()).isEqualTo(1L);
        assertThat(output[0]).as("monitoring audio remains audible").containsExactly(input[0]);
    }
}
