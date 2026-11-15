package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.core.audio.health.AudioFailureCategory;
import com.benesquivelmusic.daw.core.audio.health.AudioFailureSample;
import com.benesquivelmusic.daw.core.audio.health.AudioHealthRecord;
import com.benesquivelmusic.daw.core.audio.health.LaneRole;
import com.benesquivelmusic.daw.core.audio.health.RenderThread;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VelvetNoiseReverbProcessorTest {

    // ── Late-segment failures (story 337, ruling E29) ─────────────────────

    /**
     * Stop's teardown interrupts the render drive; a late-segment wait that
     * sees the interrupt restores the flag and records nothing — it is not a
     * DSP fault. The default parameters give eight late segments, so the
     * parallel path runs.
     */
    @Test
    void anInterruptedRenderThreadKeepsItsInterruptAndRecordsNothing() throws Exception {
        VelvetNoiseReverbProcessor reverb = new VelvetNoiseReverbProcessor(1, 44100.0);
        AudioHealthRecord record = new AudioHealthRecord();
        AtomicBoolean stillInterrupted = new AtomicBoolean();

        onRenderThread(record, () -> {
            Thread.currentThread().interrupt();
            reverb.process(impulse(512), new float[1][512], 512);
            stillInterrupted.set(Thread.currentThread().isInterrupted());
        });

        assertThat(stillInterrupted).as("the interrupt flag is restored for the stop that set it").isTrue();
        assertThat(record.totals()).containsOnly(0L);
    }

    /**
     * A late segment that fails midway is zeroed — its half-written buffer
     * would sum into an audible click — and recorded as {@code audio.dsp}
     * with the segment index, on a render thread only. The fault lands at
     * frame {@value #FAULT_FRAME} of a non-silent block, so the frames before
     * it really were written before the throw and only the catch's zeroing
     * can leave the segment's buffer silent.
     */
    @Test
    void aFailedLateSegmentIsZeroedAndRecordedOnARenderThread() throws Exception {
        VelvetNoiseReverbProcessor reverb = new VelvetNoiseReverbProcessor(1, 44100.0);
        reverb.process(impulse(512), new float[1][512], 512);
        breakLateSegmentMidway(reverb, 2);
        AudioHealthRecord record = new AudioHealthRecord();
        float[][] output = new float[1][512];

        onRenderThread(record, () -> reverb.process(constant(512, 0.5f), output, 512));

        float[] failedSegment = ((float[][]) privateField(reverb, "segmentWorkBuffers"))[2];
        for (int frame = 0; frame < 512; frame++) {
            assertThat(failedSegment[frame])
                    .as("frame %d of the failed segment, written before the fault, is zeroed", frame)
                    .isEqualTo(0.0f);
        }
        assertThat(record.total(AudioFailureCategory.DSP)).isEqualTo(1);
        List<AudioFailureSample> samples = new ArrayList<>();
        record.drainSamples(samples::add);
        assertThat(samples).singleElement().satisfies(sample -> {
            assertThat(sample.detail()).as("the failed segment").isEqualTo(2);
            assertThat(sample.subject()).contains(reverb);
            assertThat(sample.failure()).get().isInstanceOf(ArrayIndexOutOfBoundsException.class);
        });
        for (float value : output[0]) {
            assertThat(Float.isFinite(value)).isTrue();
        }

        reverb.process(impulse(512), new float[1][512], 512);
        assertThat(record.total(AudioFailureCategory.DSP))
                .as("off a render thread the same failure is survived and not recorded")
                .isEqualTo(1);
    }

    private static float[][] impulse(int frames) {
        float[][] input = new float[1][frames];
        input[0][0] = 1.0f;
        return input;
    }

    private static float[][] constant(int frames, float value) {
        float[][] input = new float[1][frames];
        java.util.Arrays.fill(input[0], value);
        return input;
    }

    /** The frame of the next block at which {@link #breakLateSegmentMidway} makes the segment throw. */
    private static final int FAULT_FRAME = 64;

    /**
     * Makes one late segment fail midway through the next block: the write
     * position moves {@value #FAULT_FRAME} frames short of the ring's end and
     * pulse 0 of the segment gets a delay of
     * {@code 2 * ringLength - FAULT_FRAME}. Frames {@code [0, FAULT_FRAME)}
     * then read valid ring positions and are written; at frame
     * {@value #FAULT_FRAME} the write position wraps to 0, the read position
     * is {@code FAULT_FRAME - ringLength}, negative, and the segment task
     * throws — a real fault inside it, after it wrote part of its buffer.
     */
    private static void breakLateSegmentMidway(VelvetNoiseReverbProcessor reverb, int segment)
            throws ReflectiveOperationException {
        int ringLength = (int) privateField(reverb, "ringLength");
        ((int[]) privateField(reverb, "writePos"))[0] = ringLength - FAULT_FRAME;
        ((int[][][]) privateField(reverb, "lateDelays"))[0][segment][0] = 2 * ringLength - FAULT_FRAME;
    }

    private static Object privateField(VelvetNoiseReverbProcessor reverb, String name)
            throws ReflectiveOperationException {
        java.lang.reflect.Field field = VelvetNoiseReverbProcessor.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(reverb);
    }

    private static void onRenderThread(AudioHealthRecord record, Runnable body) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        RenderThread thread = new RenderThread(record.claimLane(LaneRole.RENDER_DRIVE), () -> {
            try {
                body.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "velvet-render-thread");
        thread.start();
        thread.join(5_000L);
        assertThat(thread.isAlive()).as("the render thread finished within the guard budget").isFalse();
        assertThat(failure.get()).as("nothing escaped the render thread").isNull();
    }

    @Test
    void shouldCreateWithDefaults() {
        VelvetNoiseReverbProcessor reverb = new VelvetNoiseReverbProcessor(2, 44100.0);
        assertThat(reverb.getInputChannelCount()).isEqualTo(2);
        assertThat(reverb.getOutputChannelCount()).isEqualTo(2);
        assertThat(reverb.getDecayTime()).isEqualTo(0.5);
        assertThat(reverb.getDensity()).isEqualTo(0.5);
        assertThat(reverb.getEarlyLateMix()).isEqualTo(0.5);
        assertThat(reverb.getDamping()).isEqualTo(0.5);
        assertThat(reverb.getMix()).isEqualTo(0.3);
    }

    @Test
    void shouldPassDrySignalWithZeroMix() {
        VelvetNoiseReverbProcessor reverb = new VelvetNoiseReverbProcessor(1, 44100.0);
        reverb.setMix(0.0);

        float[][] input = new float[1][512];
        float[][] output = new float[1][512];
        for (int i = 0; i < 512; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }
        reverb.process(input, output, 512);

        for (int i = 0; i < 512; i++) {
            assertThat(output[0][i]).isCloseTo(input[0][i],
                    org.assertj.core.data.Offset.offset(1e-5f));
        }
    }

    @Test
    void shouldProduceReverbTailAfterImpulse() {
        VelvetNoiseReverbProcessor reverb = new VelvetNoiseReverbProcessor(1, 44100.0);
        reverb.setMix(1.0);
        reverb.setDecayTime(0.8);

        // Process an impulse
        float[][] input = new float[1][8192];
        float[][] output = new float[1][8192];
        input[0][0] = 1.0f;
        reverb.process(input, output, 8192);

        // The tail should have energy after the initial impulse
        double tailRms = rms(output[0], 2048, 8192);
        assertThat(tailRms).isGreaterThan(0.0);
    }

    @Test
    void shouldDecayOverTime() {
        VelvetNoiseReverbProcessor reverb = new VelvetNoiseReverbProcessor(1, 44100.0);
        reverb.setMix(1.0);
        reverb.setDecayTime(0.5);
        reverb.setDamping(0.0);

        // Process an impulse
        int len = 44100; // 1 second
        float[][] input = new float[1][len];
        float[][] output = new float[1][len];
        input[0][0] = 1.0f;
        reverb.process(input, output, len);

        // Early portion should be louder than late portion
        double earlyRms = rms(output[0], 2048, 8192);
        double lateRms = rms(output[0], len - 8192, len);
        assertThat(earlyRms).isGreaterThan(lateRms);
    }

    @Test
    void shouldModifySignalComparedToDry() {
        VelvetNoiseReverbProcessor reverb = new VelvetNoiseReverbProcessor(1, 44100.0);
        reverb.setMix(0.5);

        float[][] input = new float[1][4096];
        float[][] output = new float[1][4096];
        for (int i = 0; i < 4096; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }
        reverb.process(input, output, 4096);

        boolean differs = false;
        for (int i = 2048; i < 4096; i++) {
            if (Math.abs(output[0][i] - input[0][i]) > 0.001f) {
                differs = true;
                break;
            }
        }
        assertThat(differs).isTrue();
    }

    @Test
    void shouldResetState() {
        VelvetNoiseReverbProcessor reverb = new VelvetNoiseReverbProcessor(1, 44100.0);
        reverb.setMix(1.0);

        float[][] input = new float[1][4096];
        float[][] output = new float[1][4096];
        input[0][0] = 1.0f;
        reverb.process(input, output, 4096);

        reverb.reset();

        // After reset, processing silence should produce silence
        float[][] silence = new float[1][4096];
        float[][] resetOutput = new float[1][4096];
        reverb.process(silence, resetOutput, 4096);

        for (int i = 0; i < 4096; i++) {
            assertThat(resetOutput[0][i]).isCloseTo(0.0f,
                    org.assertj.core.data.Offset.offset(1e-6f));
        }
    }

    @Test
    void shouldRejectInvalidConstructorParameters() {
        assertThatThrownBy(() -> new VelvetNoiseReverbProcessor(0, 44100.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VelvetNoiseReverbProcessor(2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidDecayTime() {
        VelvetNoiseReverbProcessor reverb = new VelvetNoiseReverbProcessor(1, 44100.0);
        assertThatThrownBy(() -> reverb.setDecayTime(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reverb.setDecayTime(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidDensity() {
        VelvetNoiseReverbProcessor reverb = new VelvetNoiseReverbProcessor(1, 44100.0);
        assertThatThrownBy(() -> reverb.setDensity(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reverb.setDensity(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidEarlyLateMix() {
        VelvetNoiseReverbProcessor reverb = new VelvetNoiseReverbProcessor(1, 44100.0);
        assertThatThrownBy(() -> reverb.setEarlyLateMix(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reverb.setEarlyLateMix(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidDamping() {
        VelvetNoiseReverbProcessor reverb = new VelvetNoiseReverbProcessor(1, 44100.0);
        assertThatThrownBy(() -> reverb.setDamping(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reverb.setDamping(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidMix() {
        VelvetNoiseReverbProcessor reverb = new VelvetNoiseReverbProcessor(1, 44100.0);
        assertThatThrownBy(() -> reverb.setMix(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reverb.setMix(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldSupportParameterChanges() {
        VelvetNoiseReverbProcessor reverb = new VelvetNoiseReverbProcessor(1, 44100.0);
        reverb.setDecayTime(0.8);
        reverb.setDensity(0.7);
        reverb.setEarlyLateMix(0.3);
        reverb.setDamping(0.2);
        reverb.setMix(0.6);

        assertThat(reverb.getDecayTime()).isEqualTo(0.8);
        assertThat(reverb.getDensity()).isEqualTo(0.7);
        assertThat(reverb.getEarlyLateMix()).isEqualTo(0.3);
        assertThat(reverb.getDamping()).isEqualTo(0.2);
        assertThat(reverb.getMix()).isEqualTo(0.6);
    }

    @Test
    void shouldProcessStereo() {
        VelvetNoiseReverbProcessor reverb = new VelvetNoiseReverbProcessor(2, 44100.0);
        reverb.setMix(1.0);
        reverb.setDamping(0.0);

        float[][] input = new float[2][4096];
        float[][] output = new float[2][4096];
        input[0][0] = 1.0f;
        input[1][0] = 1.0f;
        reverb.process(input, output, 4096);

        // Both channels should have reverb tail
        assertThat(rms(output[0], 2048, 4096)).isGreaterThan(0.0);
        assertThat(rms(output[1], 2048, 4096)).isGreaterThan(0.0);
    }

    @Test
    void shouldRespondToDensityChange() {
        // Different density settings should produce different impulse responses
        VelvetNoiseReverbProcessor reverb1 = new VelvetNoiseReverbProcessor(1, 44100.0);
        reverb1.setMix(1.0);
        reverb1.setDensity(0.1);
        reverb1.setDamping(0.0);

        VelvetNoiseReverbProcessor reverb2 = new VelvetNoiseReverbProcessor(1, 44100.0);
        reverb2.setMix(1.0);
        reverb2.setDensity(0.9);
        reverb2.setDamping(0.0);

        float[][] input = new float[1][8192];
        input[0][0] = 1.0f;
        float[][] output1 = new float[1][8192];
        float[][] output2 = new float[1][8192];

        reverb1.process(input, output1, 8192);
        reverb2.process(input, output2, 8192);

        // Outputs should differ due to different densities
        boolean differs = false;
        for (int i = 1024; i < 8192; i++) {
            if (Math.abs(output1[0][i] - output2[0][i]) > 1e-6f) {
                differs = true;
                break;
            }
        }
        assertThat(differs).isTrue();
    }

    @Test
    void shouldProduceStereoDecorrelation() {
        // Stereo channels should have different impulse responses for decorrelation
        VelvetNoiseReverbProcessor reverb = new VelvetNoiseReverbProcessor(2, 44100.0);
        reverb.setMix(1.0);
        reverb.setDamping(0.0);

        float[][] input = new float[2][8192];
        float[][] output = new float[2][8192];
        input[0][0] = 1.0f;
        input[1][0] = 1.0f;
        reverb.process(input, output, 8192);

        // Left and right channels should differ due to decorrelated sequences
        boolean differs = false;
        for (int i = 1024; i < 8192; i++) {
            if (Math.abs(output[0][i] - output[1][i]) > 1e-6f) {
                differs = true;
                break;
            }
        }
        assertThat(differs).isTrue();
    }

    @Test
    void shouldProduceLateReverbViaParallelSegments() {
        // With high decay time the late reverb uses parallel segment processing
        // via virtual threads; verify the late tail has energy
        VelvetNoiseReverbProcessor reverb = new VelvetNoiseReverbProcessor(1, 44100.0);
        reverb.setMix(1.0);
        reverb.setDecayTime(0.9);
        reverb.setEarlyLateMix(1.0); // only late reverb
        reverb.setDamping(0.0);

        int len = 44100; // 1 second
        float[][] input = new float[1][len];
        float[][] output = new float[1][len];
        input[0][0] = 1.0f;
        reverb.process(input, output, len);

        // Late reverb tail should have energy in the mid-to-late region
        double midRms = rms(output[0], len / 4, len / 2);
        assertThat(midRms).isGreaterThan(0.0);
    }

    @Test
    void shouldProduceConsistentResultsAcrossBufferSizes() {
        // Process the same signal with different buffer sizes and verify
        // the results are identical, confirming segment processing is correct
        VelvetNoiseReverbProcessor reverb1 = new VelvetNoiseReverbProcessor(1, 44100.0);
        reverb1.setMix(1.0);
        reverb1.setDecayTime(0.8);
        reverb1.setDamping(0.0);

        VelvetNoiseReverbProcessor reverb2 = new VelvetNoiseReverbProcessor(1, 44100.0);
        reverb2.setMix(1.0);
        reverb2.setDecayTime(0.8);
        reverb2.setDamping(0.0);

        // Process in one large block
        float[][] input = new float[1][4096];
        input[0][0] = 1.0f;
        float[][] output1 = new float[1][4096];
        reverb1.process(input, output1, 4096);

        // Process in small blocks
        float[][] output2 = new float[1][4096];
        int blockSize = 256;
        for (int offset = 0; offset < 4096; offset += blockSize) {
            float[][] blockIn = new float[1][blockSize];
            float[][] blockOut = new float[1][blockSize];
            System.arraycopy(input[0], offset, blockIn[0], 0, blockSize);
            reverb2.process(blockIn, blockOut, blockSize);
            System.arraycopy(blockOut[0], 0, output2[0], offset, blockSize);
        }

        // Results should be identical
        for (int i = 0; i < 4096; i++) {
            assertThat(output1[0][i]).isCloseTo(output2[0][i],
                    org.assertj.core.data.Offset.offset(1e-5f));
        }
    }

    private static double rms(float[] buffer, int start, int end) {
        double sum = 0;
        for (int i = start; i < end; i++) {
            sum += (double) buffer[i] * buffer[i];
        }
        return Math.sqrt(sum / (end - start));
    }
}
