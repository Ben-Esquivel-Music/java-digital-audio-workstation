package com.benesquivelmusic.daw.core.metering;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
}
