package com.benesquivelmusic.daw.core.audio.health;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit contract of one render thread's {@link AudioHealthLane} (story 337,
 * rulings E19, E21 and E22): exact single-writer counters, a FIFO descriptor
 * ring that drops the newest when full and counts the loss, the drain's
 * copy-null-advance order, and an out-of-range category ignored rather than
 * thrown on the real-time path.
 */
class AudioHealthLaneTest {

    private final AudioHealthRecord record = new AudioHealthRecord();
    private final AudioHealthLane lane = record.claimLane(LaneRole.RENDER_DRIVE);

    @Test
    void descriptorsDrainInTheOrderTheyWereRecorded() {
        IllegalStateException first = new IllegalStateException("first");
        IllegalArgumentException second = new IllegalArgumentException("second");
        lane.record(AudioFailureCodes.RENDER, first, "subject-a", 1);
        lane.beat();
        lane.record(AudioFailureCodes.SINK, second, null, 2);
        lane.record(AudioFailureCodes.CONFIG, null, "subject-c", 3);

        List<AudioFailureSample> samples = drain();

        assertThat(samples).extracting(AudioFailureSample::category).containsExactly(
                AudioFailureCategory.RENDER, AudioFailureCategory.SINK, AudioFailureCategory.CONFIG);
        assertThat(samples.get(0).failure()).containsSame(first);
        assertThat(samples.get(0).subject()).contains("subject-a");
        assertThat(samples.get(0).stamp()).as("stamped with the heartbeat at record time").isZero();
        assertThat(samples.get(1).failure()).containsSame(second);
        assertThat(samples.get(1).subject()).isEmpty();
        assertThat(samples.get(1).stamp()).isEqualTo(1L);
        assertThat(samples.get(2).failure()).as("a record with no throwable").isEmpty();
        assertThat(samples).extracting(AudioFailureSample::detail).containsExactly(1, 2, 3);
        assertThat(samples).extracting(AudioFailureSample::lane).containsOnly(LaneRole.RENDER_DRIVE);
        assertThat(drain()).as("a drained ring is empty").isEmpty();
    }

    @Test
    void aFullRingDropsTheNewestAndCountsTheLossWhileTheCountsStayExact() {
        for (int i = 0; i < AudioHealthLane.SLOTS + 4; i++) {
            lane.record(AudioFailureCodes.WORKER, new IllegalStateException("task " + i), null, i);
        }

        List<AudioFailureSample> samples = drain();

        assertThat(lane.count(AudioFailureCategory.WORKER)).isEqualTo(AudioHealthLane.SLOTS + 4);
        assertThat(lane.lostRecords()).isEqualTo(4L);
        assertThat(samples).extracting(AudioFailureSample::detail)
                .as("the first occurrences survive; the newest were dropped")
                .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15);
        assertThat(record.lostSamples()).isEqualTo(4L);
    }

    @Test
    void aDrainedSlotIsEmptiedBeforeItIsReleasedAndThenReusable() throws Exception {
        lane.record(AudioFailureCodes.DSP, new IllegalStateException("segment"), "processor", 7);

        assertThat(drain()).hasSize(1);

        RecordSlot slot = slots()[0];
        assertThat(slot.failure).as("the throwable reference is released").isNull();
        assertThat(slot.subject).as("the subject reference is released").isNull();

        for (int i = 0; i < AudioHealthLane.SLOTS; i++) {
            lane.record(AudioFailureCodes.DSP, null, null, i);
        }
        assertThat(drain()).as("every slot is reusable after a drain").hasSize(AudioHealthLane.SLOTS);
        assertThat(lane.lostRecords()).isZero();
    }

    @Test
    void anOutOfRangeCategoryIsIgnoredRatherThanThrown() {
        lane.record(-1, new IllegalStateException("negative"), null, 0);
        lane.record(AudioFailureCodes.COUNT, new IllegalStateException("past the end"), null, 0);

        assertThat(drain()).isEmpty();
        assertThat(record.totals()).containsOnly(0L);
    }

    @Test
    void theLastFailureOfACategoryIsKeptAcrossARecordWithoutOne() {
        IllegalStateException failure = new IllegalStateException("kept");
        lane.record(AudioFailureCodes.PLUGIN, failure, null, 0);
        lane.record(AudioFailureCodes.PLUGIN, null, null, 0);

        assertThat(lane.lastFailureOf(AudioFailureCodes.PLUGIN)).isSameAs(failure);
        assertThat(lane.count(AudioFailureCategory.PLUGIN)).isEqualTo(2);
    }

    @Test
    void theHeartbeatCountsEveryBeat() {
        for (int i = 0; i < 5; i++) {
            lane.beat();
        }

        assertThat(lane.beats()).isEqualTo(5);
        assertThat(record.renderDriveBeats()).isEqualTo(5);
    }

    @Test
    void theCategoryEnumMirrorsTheRealTimeCodesInOrder() {
        AudioFailureCategory[] categories = AudioFailureCategory.values();

        assertThat(categories).hasSize(AudioFailureCodes.COUNT);
        for (int code = 0; code < categories.length; code++) {
            assertThat(categories[code].code()).isEqualTo(code);
            assertThat(AudioFailureCategory.ofCode(code)).isSameAs(categories[code]);
            assertThat(categories[code].slug()).startsWith("audio.");
        }
        assertThat(AudioFailureCategory.RENDER_HALTED.slug()).isEqualTo("audio.render.halted");
        assertThat(Arrays.stream(categories).map(AudioFailureCategory::slug)).containsExactly(
                "audio.callback", "audio.render", "audio.render.halted", "audio.sink", "audio.pacing",
                "audio.input", "audio.worker", "audio.dsp", "audio.midi", "audio.plugin",
                "audio.automation", "audio.config", "audio.device");
        assertThat(AudioFailureCategory.CALLBACK.code()).isEqualTo(AudioFailureCodes.CALLBACK);
        assertThat(AudioFailureCategory.DEVICE.code()).isEqualTo(AudioFailureCodes.DEVICE);
    }

    private List<AudioFailureSample> drain() {
        List<AudioFailureSample> samples = new ArrayList<>();
        record.drainSamples(samples::add);
        return samples;
    }

    private RecordSlot[] slots() throws ReflectiveOperationException {
        Field field = AudioHealthLane.class.getDeclaredField("slots");
        field.setAccessible(true);
        return (RecordSlot[]) field.get(lane);
    }
}
