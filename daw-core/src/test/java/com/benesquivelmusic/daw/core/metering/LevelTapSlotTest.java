package com.benesquivelmusic.daw.core.metering;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class LevelTapSlotTest {

    private static final float EPSILON = 1e-6f;

    @Test
    void neverPublishedSlotReadsFalse() {
        LevelTapSlot slot = new LevelTapSlot(MeterTapPoint.MASTER_OUT);
        assertThat(slot.hasPublished()).isFalse();
        assertThat(slot.readInto(new MeterFrame())).isFalse();
        assertThat(slot.point()).isEqualTo(MeterTapPoint.MASTER_OUT);
    }

    @Test
    void perSampleAccumulationPublishesPeakAndRms() {
        LevelTapSlot slot = new LevelTapSlot(MeterTapPoint.MASTER_OUT);
        slot.beginBlock(5L, 17L, 2);
        float[] left = {0.5f, -0.5f, 0.5f, -0.5f};
        float[] right = {0.25f, 0.0f, -0.25f, 0.0f};
        for (int f = 0; f < 4; f++) {
            slot.accumulate(0, left[f]);
            slot.accumulate(1, right[f]);
        }
        slot.publish(4);

        MeterFrame frame = new MeterFrame();
        assertThat(slot.readInto(frame)).isTrue();
        assertThat(frame.epoch()).isEqualTo(5L);
        assertThat(frame.blockIndex()).isEqualTo(17L);
        assertThat(frame.channelCount()).isEqualTo(2);
        assertThat(frame.peak(0)).isCloseTo(0.5f, within(EPSILON));
        assertThat(frame.rms(0)).isCloseTo(0.5f, within(EPSILON));
        assertThat(frame.peak(1)).isCloseTo(0.25f, within(EPSILON));
        assertThat(frame.rms(1)).isCloseTo((float) Math.sqrt(0.125 / 4), within(EPSILON));
        assertThat(frame.clipped()).isFalse();
    }

    @Test
    void blockHelpersMatchThePerSampleForm() {
        float[] buffer = new float[64];
        for (int f = 0; f < buffer.length; f++) {
            buffer[f] = (float) Math.sin(2 * Math.PI * f / 16.0);
        }
        LevelTapSlot perSample = new LevelTapSlot(MeterTapPoint.MASTER_OUT);
        perSample.beginBlock(1L, 1L, 1);
        for (float x : buffer) {
            perSample.accumulate(0, x * 0.5f);
        }
        perSample.publish(buffer.length);

        LevelTapSlot scaled = new LevelTapSlot(MeterTapPoint.MASTER_OUT);
        scaled.beginBlock(1L, 1L, 1);
        scaled.accumulateScaled(0, buffer, buffer.length, 0.5f);
        scaled.publish(buffer.length);

        float[] half = new float[buffer.length];
        for (int f = 0; f < buffer.length; f++) {
            half[f] = buffer[f] * 0.5f;
        }
        LevelTapSlot block = new LevelTapSlot(MeterTapPoint.MASTER_OUT);
        block.beginBlock(1L, 1L, 1);
        block.accumulate(0, half, half.length);
        block.publish(buffer.length);

        MeterFrame a = new MeterFrame();
        MeterFrame b = new MeterFrame();
        MeterFrame c = new MeterFrame();
        assertThat(perSample.readInto(a)).isTrue();
        assertThat(scaled.readInto(b)).isTrue();
        assertThat(block.readInto(c)).isTrue();
        assertThat(a.peak(0)).isCloseTo(0.5f, within(EPSILON));
        assertThat(b.peak(0)).isCloseTo(a.peak(0), within(EPSILON));
        assertThat(c.peak(0)).isCloseTo(a.peak(0), within(EPSILON));
        assertThat(b.rms(0)).isCloseTo(a.rms(0), within(EPSILON));
        assertThat(c.rms(0)).isCloseTo(a.rms(0), within(EPSILON));
        assertThat(a.rms(0)).isCloseTo((float) (0.5 / Math.sqrt(2)), within(1e-4f));
    }

    @Test
    void doubleAccumulatorLaneIsNarrowedOnPublish() {
        double[] lane = new double[8];
        for (int f = 0; f < lane.length; f++) {
            lane[f] = (f & 1) == 0 ? 0.75 : -0.75;
        }
        LevelTapSlot slot = new LevelTapSlot(MeterTapPoint.MASTER_CHAIN);
        slot.beginBlock(1L, 2L, 1);
        slot.accumulate(0, lane, lane.length);
        slot.publish(lane.length);
        MeterFrame frame = new MeterFrame();
        assertThat(slot.readInto(frame)).isTrue();
        assertThat(frame.peak(0)).isCloseTo(0.75f, within(EPSILON));
        assertThat(frame.rms(0)).isCloseTo(0.75f, within(EPSILON));
    }

    @Test
    void clipFlagSetsAtFullScaleAndNotBelow() {
        LevelTapSlot slot = new LevelTapSlot(MeterTapPoint.MASTER_OUT);
        slot.beginBlock(1L, 1L, 1);
        slot.accumulate(0, 0.999f);
        slot.publish(1);
        MeterFrame frame = new MeterFrame();
        assertThat(slot.readInto(frame)).isTrue();
        assertThat(frame.clipped()).isFalse();

        slot.beginBlock(1L, 2L, 1);
        slot.accumulate(0, -1.0f);
        slot.publish(1);
        assertThat(slot.readInto(frame)).isTrue();
        assertThat(frame.clipped()).isTrue();
        assertThat(frame.peak(0)).isEqualTo(1.0f);
    }

    @Test
    void lanesOutsideTheBlockLaneCountAreIgnored() {
        LevelTapSlot slot = new LevelTapSlot(MeterTapPoint.MASTER_OUT);
        slot.beginBlock(1L, 1L, 1);
        slot.accumulate(1, 0.9f);
        slot.accumulate(-1, 0.9f);
        slot.accumulate(MeterFrame.MAX_CHANNELS + 3, 0.9f);
        slot.accumulate(0, 0.3f);
        slot.publish(1);
        MeterFrame frame = new MeterFrame();
        assertThat(slot.readInto(frame)).isTrue();
        assertThat(frame.channelCount()).isEqualTo(1);
        assertThat(frame.peak(0)).isCloseTo(0.3f, within(EPSILON));
        assertThat(frame.peak(1)).isZero();
        assertThat(frame.maxPeak()).isCloseTo(0.3f, within(EPSILON));
    }

    @Test
    void laneCountClampsToMaxChannels() {
        LevelTapSlot slot = new LevelTapSlot(MeterTapPoint.MASTER_OUT);
        slot.beginBlock(1L, 1L, 20);
        slot.publish(1);
        MeterFrame frame = new MeterFrame();
        assertThat(slot.readInto(frame)).isTrue();
        assertThat(frame.channelCount()).isEqualTo(MeterFrame.MAX_CHANNELS);

        slot.publishSilence(1L, 2L, -4);
        assertThat(slot.readInto(frame)).isTrue();
        assertThat(frame.channelCount()).isZero();
    }

    @Test
    void publishSilenceClearsALoudSlotAndStampsTheBlock() {
        LevelTapSlot slot = new LevelTapSlot(MeterTapPoint.MASTER_OUT);
        slot.beginBlock(1L, 1L, 2);
        slot.accumulate(0, 1.0f);
        slot.accumulate(1, 0.8f);
        slot.publish(1);

        slot.publishSilence(2L, 9L, 2);

        MeterFrame frame = new MeterFrame();
        assertThat(slot.readInto(frame)).isTrue();
        assertThat(frame.epoch()).isEqualTo(2L);
        assertThat(frame.blockIndex()).isEqualTo(9L);
        assertThat(frame.channelCount()).isEqualTo(2);
        assertThat(frame.isSilent()).isTrue();
        assertThat(frame.clipped()).isFalse();
    }

    @Test
    void publishIsLatestWins() {
        LevelTapSlot slot = new LevelTapSlot(MeterTapPoint.MASTER_OUT);
        for (int block = 1; block <= 3; block++) {
            slot.beginBlock(1L, block, 1);
            slot.accumulate(0, 0.1f * block);
            slot.publish(1);
        }
        MeterFrame frame = new MeterFrame();
        assertThat(slot.readInto(frame)).isTrue();
        assertThat(frame.blockIndex()).isEqualTo(3L);
        assertThat(frame.peak(0)).isCloseTo(0.3f, within(EPSILON));
        assertThat(slot.sequence()).isEqualTo(6L);
    }

    @Test
    void lanesAboveANarrowerBlockAreZeroedNotStale() {
        LevelTapSlot slot = new LevelTapSlot(MeterTapPoint.MASTER_OUT);
        slot.beginBlock(1L, 1L, 2);
        slot.accumulate(1, 0.7f);
        slot.publish(1);
        slot.beginBlock(1L, 2L, 1);
        slot.accumulate(0, 0.2f);
        slot.publish(1);
        MeterFrame frame = new MeterFrame();
        assertThat(slot.readInto(frame)).isTrue();
        assertThat(frame.channelCount()).isEqualTo(1);
        assertThat(frame.peak(1)).isZero();
        assertThat(frame.rms(1)).isZero();
    }

    @Test
    void ringsDefaultToEmptyAndAreSwappedNotMutated() {
        LevelTapSlot slot = new LevelTapSlot(MeterTapPoint.MASTER_OUT);
        SampleBlockRing[] before = slot.rings();
        assertThat(before).isEmpty();
        SampleBlockRing ring = new SampleBlockRing(2, 16);
        slot.setRings(new SampleBlockRing[] {ring});
        assertThat(slot.rings()).containsExactly(ring);
        assertThat(before).isEmpty();
        slot.setRings(null);
        assertThat(slot.rings()).isEmpty();
    }

    /**
     * Story acceptance "latest-wins publication": a writer publishing a
     * million frames whose every field is derived from the block number can
     * never be observed torn — each successful read is internally consistent.
     */
    @Test
    void concurrentReaderNeverObservesATornFrame() throws Exception {
        final int blocks = 1_000_000;
        LevelTapSlot slot = new LevelTapSlot(MeterTapPoint.MASTER_OUT);
        AtomicBoolean writerDone = new AtomicBoolean();
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();
        Thread writer = Thread.ofPlatform().name("tap-slot-writer").unstarted(() -> {
            try {
                for (int i = 1; i <= blocks; i++) {
                    float value = valueFor(i);
                    slot.beginBlock(i, i, 2);
                    slot.accumulate(0, value);
                    slot.accumulate(1, -value);
                    slot.publish(1);
                }
            } catch (Throwable t) {
                writerFailure.set(t);
            } finally {
                writerDone.set(true);
            }
        });

        AtomicLong successfulReads = new AtomicLong();
        AtomicLong rejectedReads = new AtomicLong();
        AtomicLong tornReads = new AtomicLong();
        AtomicReference<String> firstTear = new AtomicReference<>();
        Thread reader = Thread.ofPlatform().name("tap-slot-reader").unstarted(() -> {
            MeterFrame frame = new MeterFrame();
            boolean lastPass = false;
            while (true) {
                if (writerDone.get()) {
                    lastPass = true;
                }
                if (slot.readInto(frame)) {
                    successfulReads.incrementAndGet();
                    if (!isConsistent(frame)) {
                        tornReads.incrementAndGet();
                        firstTear.compareAndSet(null, frame.toString());
                    }
                } else {
                    rejectedReads.incrementAndGet();
                }
                if (lastPass) {
                    return;
                }
            }
        });

        writer.start();
        reader.start();
        writer.join(10_000L);
        assertThat(writer.isAlive()).as("writer must finish within the time bound").isFalse();
        reader.join(10_000L);
        assertThat(reader.isAlive()).as("reader must finish within the time bound").isFalse();

        assertThat(writerFailure.get()).isNull();
        assertThat(successfulReads.get())
                .as("the stress must actually read frames (rejected: %d)", rejectedReads.get())
                .isPositive();
        assertThat(tornReads.get()).as("first torn frame: %s", firstTear.get()).isZero();

        MeterFrame last = new MeterFrame();
        assertThat(slot.readInto(last)).isTrue();
        assertThat(last.blockIndex()).isEqualTo(blocks);
        assertThat(last.epoch()).isEqualTo(blocks);
        assertThat(isConsistent(last)).isTrue();
    }

    private static float valueFor(long block) {
        return (block & 0xFFFF) / 65536f;
    }

    private static boolean isConsistent(MeterFrame frame) {
        if (frame.epoch() != frame.blockIndex() || frame.channelCount() != 2) {
            return false;
        }
        float expected = valueFor(frame.epoch());
        return Math.abs(frame.peak(0) - expected) <= EPSILON
                && Math.abs(frame.peak(1) - expected) <= EPSILON
                && Math.abs(frame.rms(0) - expected) <= EPSILON
                && Math.abs(frame.rms(1) - expected) <= EPSILON
                && !frame.clipped();
    }
}
