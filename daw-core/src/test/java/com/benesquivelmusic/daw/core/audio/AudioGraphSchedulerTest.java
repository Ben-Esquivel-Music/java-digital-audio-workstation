package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.Send;
import com.benesquivelmusic.daw.core.mixer.SendMode;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Correctness tests for {@link AudioGraphScheduler}. Validates that
 * enabling the parallel pre-pass produces bit-exact output compared with
 * the single-threaded path, that the scheduler falls back to single-threaded
 * for small blocks, and that shared return buses do not deadlock or corrupt
 * audio when every channel sends to the same bus.
 */
class AudioGraphSchedulerTest {

    private static final int CHANNELS = 2;

    /** Deterministic gain-and-bias insert — representative of typical DSP work. */
    private static final class GainBiasInsert implements AudioProcessor {
        private final float gain;
        private final float bias;
        GainBiasInsert(float gain, float bias) { this.gain = gain; this.bias = bias; }
        @Override
        public void process(float[][] in, float[][] out, int n) {
            int ch = Math.min(in.length, out.length);
            for (int c = 0; c < ch; c++) {
                for (int f = 0; f < n; f++) {
                    out[c][f] = in[c][f] * gain + bias;
                }
            }
        }
        @Override public void reset() {}
        @Override public int getInputChannelCount() { return CHANNELS; }
        @Override public int getOutputChannelCount() { return CHANNELS; }
    }

    /** Builds and configures a 16-track / 4-bus mixer with a rich mix of inserts and sends. */
    private static Mixer buildBusyMixer(long seed) {
        Random rng = new Random(seed);
        Mixer mixer = new Mixer();
        // 3 extra return buses on top of the default one → 4 total.
        MixerChannel[] buses = {
                mixer.getReturnBuses().get(0),
                mixer.addReturnBus("Delay"),
                mixer.addReturnBus("Chorus"),
                mixer.addReturnBus("Plate")
        };
        for (MixerChannel bus : buses) {
            bus.setVolume(0.5 + rng.nextDouble() * 0.4);
            // Non-trivial insert on the bus itself.
            bus.addInsert(new InsertSlot("bus-ins",
                    new GainBiasInsert(0.85f, 0.0f)));
        }

        for (int t = 0; t < 16; t++) {
            MixerChannel mc = new MixerChannel("T" + t);
            mc.setVolume(0.2 + rng.nextDouble() * 0.8);
            mc.setPan(-1.0 + rng.nextDouble() * 2.0);
            // 1–4 inserts per channel.
            int inserts = 1 + rng.nextInt(4);
            for (int i = 0; i < inserts; i++) {
                mc.addInsert(new InsertSlot("ins" + i,
                        new GainBiasInsert(0.5f + rng.nextFloat() * 0.8f,
                                -0.01f + rng.nextFloat() * 0.02f)));
            }
            // Each track sends to every bus — this is the "bus is shared by
            // every track" stress case from the issue.
            for (MixerChannel bus : buses) {
                Send send = new Send(bus, rng.nextDouble() * 0.6,
                        rng.nextBoolean() ? SendMode.PRE_FADER : SendMode.POST_FADER);
                mc.addSend(send);
            }
            mixer.addChannel(mc);
        }
        mixer.prepareForPlayback(CHANNELS, 1024);
        return mixer;
    }

    /** Fills {@code trackBuffers} with a reproducible waveform for each track. */
    private static void fillTrackBuffers(float[][][] trackBuffers, int numFrames, long seed) {
        Random rng = new Random(seed);
        for (int t = 0; t < trackBuffers.length; t++) {
            for (int c = 0; c < trackBuffers[t].length; c++) {
                for (int f = 0; f < numFrames; f++) {
                    trackBuffers[t][c][f] = (rng.nextFloat() * 2.0f - 1.0f) * 0.5f;
                }
            }
        }
    }

    private static float[][] zeroed(int channels, int frames) {
        return new float[channels][frames];
    }

    private static float[][][] zeroed3D(int a, int b, int c) {
        return new float[a][b][c];
    }

    @Test
    void parallelAndSequentialProduceBitIdenticalOutput() {
        final int numFrames = 256;
        final int trackCount = 16;

        // Reference (single-threaded) render.
        Mixer refMixer = buildBusyMixer(42L);
        float[][][] refTrackBufs = new float[trackCount][CHANNELS][numFrames];
        fillTrackBuffers(refTrackBufs, numFrames, 7L);
        float[][] refOut = zeroed(CHANNELS, numFrames);
        float[][][] refReturns = zeroed3D(Mixer.MAX_RETURN_BUSES, CHANNELS, numFrames);
        refMixer.mixDown(refTrackBufs, refOut, refReturns, numFrames);

        // Parallel render with a 4-worker pool and identical inputs.
        try (AudioWorkerPool pool = new AudioWorkerPool(4)) {
            Mixer parMixer = buildBusyMixer(42L);
            parMixer.setGraphScheduler(new AudioGraphScheduler(pool, trackCount));
            float[][][] parTrackBufs = new float[trackCount][CHANNELS][numFrames];
            fillTrackBuffers(parTrackBufs, numFrames, 7L);
            float[][] parOut = zeroed(CHANNELS, numFrames);
            float[][][] parReturns = zeroed3D(Mixer.MAX_RETURN_BUSES, CHANNELS, numFrames);
            parMixer.mixDown(parTrackBufs, parOut, parReturns, numFrames);

            for (int c = 0; c < CHANNELS; c++) {
                for (int f = 0; f < numFrames; f++) {
                    assertThat(parOut[c][f])
                            .as("main out ch=%d frame=%d", c, f)
                            .isEqualTo(refOut[c][f]);
                }
            }
            // Each return bus must also match bit-exactly — this verifies
            // that shared buses do not corrupt audio despite every track
            // sending into them concurrently.
            for (int r = 0; r < 4; r++) {
                for (int c = 0; c < CHANNELS; c++) {
                    for (int f = 0; f < numFrames; f++) {
                        assertThat(parReturns[r][c][f])
                                .as("return bus %d ch=%d frame=%d", r, c, f)
                                .isEqualTo(refReturns[r][c][f]);
                    }
                }
            }

            // The scheduler must actually have dispatched tasks (at least 2).
            assertThat(parMixer.getGraphScheduler().getLastDispatchedTaskCount())
                    .isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    void schedulerStaysBitExactAcrossRepeatedBlocks() {
        // Re-using the same scheduler across many blocks must not drift; the
        // task array and pool are re-used, which makes sure allocation-free
        // hot-path behavior is correct.
        final int numFrames = 128;
        final int trackCount = 16;
        final int blocks = 32;

        Mixer refMixer = buildBusyMixer(5L);
        try (AudioWorkerPool pool = new AudioWorkerPool(3)) {
            Mixer parMixer = buildBusyMixer(5L);
            parMixer.setGraphScheduler(new AudioGraphScheduler(pool, trackCount));

            for (int b = 0; b < blocks; b++) {
                float[][][] refT = new float[trackCount][CHANNELS][numFrames];
                float[][][] parT = new float[trackCount][CHANNELS][numFrames];
                fillTrackBuffers(refT, numFrames, 100L + b);
                fillTrackBuffers(parT, numFrames, 100L + b);
                float[][] refOut = zeroed(CHANNELS, numFrames);
                float[][] parOut = zeroed(CHANNELS, numFrames);
                float[][][] refRet = zeroed3D(Mixer.MAX_RETURN_BUSES, CHANNELS, numFrames);
                float[][][] parRet = zeroed3D(Mixer.MAX_RETURN_BUSES, CHANNELS, numFrames);

                refMixer.mixDown(refT, refOut, refRet, numFrames);
                parMixer.mixDown(parT, parOut, parRet, numFrames);

                for (int c = 0; c < CHANNELS; c++) {
                    for (int f = 0; f < numFrames; f++) {
                        assertThat(parOut[c][f])
                                .as("block=%d ch=%d frame=%d", b, c, f)
                                .isEqualTo(refOut[c][f]);
                    }
                }
            }
        }
    }

    @Test
    void schedulerFallsBackToInlineForSmallBlocks() {
        // Default threshold is 64 frames — anything smaller must not dispatch
        // to workers (so sessions with very small block sizes are not penalized
        // by coordination overhead).
        try (AudioWorkerPool pool = new AudioWorkerPool(4)) {
            AudioGraphScheduler scheduler = new AudioGraphScheduler(pool, 16);

            Mixer mixer = buildBusyMixer(1L);
            mixer.setGraphScheduler(scheduler);

            float[][][] trackBufs = new float[16][CHANNELS][32];
            fillTrackBuffers(trackBufs, 32, 9L);
            float[][] out = zeroed(CHANNELS, 32);
            float[][][] ret = zeroed3D(Mixer.MAX_RETURN_BUSES, CHANNELS, 32);

            mixer.mixDown(trackBufs, out, ret, 32);

            assertThat(scheduler.getLastDispatchedTaskCount()).isZero();
        }
    }

    @Test
    void singleWorkerPoolDisablesDispatch() {
        try (AudioWorkerPool pool = new AudioWorkerPool(1)) {
            AudioGraphScheduler scheduler = new AudioGraphScheduler(pool, 16);
            Mixer mixer = buildBusyMixer(2L);
            mixer.setGraphScheduler(scheduler);

            float[][][] trackBufs = new float[16][CHANNELS][256];
            fillTrackBuffers(trackBufs, 256, 3L);
            float[][] out = zeroed(CHANNELS, 256);
            float[][][] ret = zeroed3D(Mixer.MAX_RETURN_BUSES, CHANNELS, 256);

            mixer.mixDown(trackBufs, out, ret, 256);

            assertThat(scheduler.getLastDispatchedTaskCount()).isZero();
        }
    }

    @Test
    void sharedBusAcrossAllTracksDoesNotDeadlock() {
        // Regression guard for the "bus shared by every track" stress case.
        // If the scheduler were to serialize on a bus lock, a 16-track render
        // with every track routed to the same bus would deadlock or stall.
        // Wrapped in assertTimeoutPreemptively so a real deadlock fails the
        // test instead of hanging CI indefinitely.
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (AudioWorkerPool pool = new AudioWorkerPool(8)) {
                Mixer mixer = buildBusyMixer(99L);
                mixer.setGraphScheduler(new AudioGraphScheduler(pool, 16));

                for (int i = 0; i < 100; i++) {
                    float[][][] trackBufs = new float[16][CHANNELS][128];
                    fillTrackBuffers(trackBufs, 128, 31L + i);
                    float[][] out = zeroed(CHANNELS, 128);
                    float[][][] ret = zeroed3D(Mixer.MAX_RETURN_BUSES, CHANNELS, 128);
                    mixer.mixDown(trackBufs, out, ret, 128);
                }
            }
        });
    }

    @Test
    void constructorsValidate() {
        try (AudioWorkerPool pool = new AudioWorkerPool(2)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> new AudioGraphScheduler(null, 4))
                    .isInstanceOf(NullPointerException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> new AudioGraphScheduler(pool, 0))
                    .isInstanceOf(IllegalArgumentException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> new AudioGraphScheduler(pool, 4, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
