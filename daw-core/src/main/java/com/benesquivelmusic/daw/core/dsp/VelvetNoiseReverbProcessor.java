package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.core.mixer.InsertEffect;

import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

/**
 * Reverb processor based on velvet-noise sequences.
 *
 * <p>Velvet noise is a sparse random signal restricted to {−1, 0, +1}, enabling
 * convolution via simple additions and subtractions instead of multiplications.
 * This yields reverb with the perceptual quality of convolution reverb at a
 * fraction of the computational cost — critical for real-time use with virtual
 * threads.</p>
 *
 * <p>The impulse response is split into two regions:</p>
 * <ul>
 *   <li><b>Early reflections</b> (first ~80 ms) — sparse pulses with individual
 *       per-pulse decay gains, processed sequentially</li>
 *   <li><b>Late reverb tail</b> — divided into segments that are processed in
 *       parallel using {@link Executors#newVirtualThreadPerTaskExecutor()}.
 *       Within each segment, convolution uses only additions and subtractions
 *       (no per-pulse multiplications); a single per-segment gain encodes the
 *       exponential decay envelope.</li>
 * </ul>
 *
 * <p>Based on AES research: "Efficient Velvet-Noise Convolution in Multicore
 * Processors" (2024). Complements the Schroeder–Moorer {@link ReverbProcessor}
 * and the physically modeled {@link SpringReverbProcessor} as a third reverb
 * algorithm.</p>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
@InsertEffect(type = "VELVET_NOISE_REVERB", displayName = "Velvet Noise Reverb")
public final class VelvetNoiseReverbProcessor implements AudioProcessor {

    private static final double MIN_DECAY_SECONDS = 0.1;
    private static final double MAX_DECAY_SECONDS = 3.0;
    private static final double MIN_DENSITY_PPS = 500.0;
    private static final double MAX_DENSITY_PPS = 4000.0;
    private static final double EARLY_REFLECTION_MS = 80.0;
    private static final int NUM_LATE_SEGMENTS = 8;
    private static final int PARALLEL_SEGMENT_THRESHOLD = 4;
    private static final int INITIAL_WORK_BUFFER_SIZE = 4096;
    private static final long BASE_SEED = 42L;

    private final int channels;
    private final double sampleRate;

    // Parameters (all normalized to [0, 1])
    private double decayTime;
    private double density;
    private double earlyLateMix;
    private double damping;
    private double mix;

    // Circular input buffer per channel
    private float[][] ringBuffer;
    private int[] writePos;
    private int ringLength;

    // Early reflection pulse data per channel
    private int[][] earlyDelays;       // [ch][pulse] — delay in samples
    private int[][] earlyPolarities;   // [ch][pulse] — +1 or -1
    private float[][] earlyGains;      // [ch][pulse] — decay gain (includes normalization)
    private int earlyPulseCount;

    // Late reverb segment data per channel
    private int[][][] lateDelays;      // [ch][seg][pulse] — delay in samples
    private int[][][] latePolarities;  // [ch][seg][pulse] — +1 or -1
    private float[] lateSegmentGains;  // [seg] — constant gain per segment (decay + norm)
    private int[] latePulseCounts;     // [seg] — pulse count per segment
    private int lateSegmentCount;

    // Pre-allocated work buffers for segment processing
    private float[][] segmentWorkBuffers;
    private int workBufferSize;

    // One-pole lowpass damping state per channel
    private final float[] dampState;

    /**
     * Creates a velvet-noise reverb processor with default settings.
     *
     * @param channels   number of audio channels
     * @param sampleRate the sample rate in Hz
     */
    public VelvetNoiseReverbProcessor(int channels, double sampleRate) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.decayTime = 0.5;
        this.density = 0.5;
        this.earlyLateMix = 0.5;
        this.damping = 0.5;
        this.mix = 0.3;

        this.writePos = new int[channels];
        this.dampState = new float[channels];

        this.workBufferSize = INITIAL_WORK_BUFFER_SIZE;
        this.segmentWorkBuffers = new float[NUM_LATE_SEGMENTS][workBufferSize];

        generateVelvetNoiseSequence();
    }

    @RealTimeSafe
    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        int activeCh = Math.min(channels, inputBuffer.length);
        double dampCoeff = damping;
        double earlyWeight = 1.0 - earlyLateMix;
        double lateWeight = earlyLateMix;

        ensureWorkBufferSize(numFrames);

        for (int ch = 0; ch < activeCh; ch++) {
            int baseWritePos = writePos[ch];

            // Write all input samples to the circular buffer
            for (int frame = 0; frame < numFrames; frame++) {
                ringBuffer[ch][(baseWritePos + frame) % ringLength] = inputBuffer[ch][frame];
            }

            // Process late reverb segments (parallel for large segment counts)
            processLateSegments(ch, baseWritePos, numFrames);

            // Per-frame: early reflections + late reverb sum + damping + mix
            for (int frame = 0; frame < numFrames; frame++) {
                int currentPos = (baseWritePos + frame) % ringLength;

                // Early reflections (sparse convolution with per-pulse gains)
                float earlyWet = 0.0f;
                for (int p = 0; p < earlyPulseCount; p++) {
                    int readPos = (currentPos - earlyDelays[ch][p] + ringLength) % ringLength;
                    float sample = ringBuffer[ch][readPos];
                    if (earlyPolarities[ch][p] == 1) {
                        earlyWet += sample * earlyGains[ch][p];
                    } else {
                        earlyWet -= sample * earlyGains[ch][p];
                    }
                }

                // Sum pre-computed late reverb segment contributions
                float lateWet = 0.0f;
                for (int seg = 0; seg < lateSegmentCount; seg++) {
                    lateWet += segmentWorkBuffers[seg][frame] * lateSegmentGains[seg];
                }

                // Combine early and late
                float wet = (float) (earlyWet * earlyWeight + lateWet * lateWeight);

                // Apply damping (one-pole lowpass)
                dampState[ch] = (float) (wet * (1.0 - dampCoeff) + dampState[ch] * dampCoeff);
                wet = dampState[ch];

                // Mix dry and wet
                outputBuffer[ch][frame] = (float) (inputBuffer[ch][frame] * (1.0 - mix)
                        + wet * mix);
            }

            writePos[ch] = (baseWritePos + numFrames) % ringLength;
        }
    }

    // --- Parameter accessors ---

    @ProcessorParam(id = 0, name = "Decay Time", min = 0.0, max = 1.0, defaultValue = 0.5)
    public double getDecayTime() { return decayTime; }

    public void setDecayTime(double decayTime) {
        if (decayTime < 0 || decayTime > 1.0) {
            throw new IllegalArgumentException("decayTime must be in [0, 1]: " + decayTime);
        }
        this.decayTime = decayTime;
        generateVelvetNoiseSequence();
    }

    @ProcessorParam(id = 1, name = "Density", min = 0.0, max = 1.0, defaultValue = 0.5)
    public double getDensity() { return density; }

    public void setDensity(double density) {
        if (density < 0 || density > 1.0) {
            throw new IllegalArgumentException("density must be in [0, 1]: " + density);
        }
        this.density = density;
        generateVelvetNoiseSequence();
    }

    @ProcessorParam(id = 2, name = "Early/Late Mix", min = 0.0, max = 1.0, defaultValue = 0.5)
    public double getEarlyLateMix() { return earlyLateMix; }

    public void setEarlyLateMix(double earlyLateMix) {
        if (earlyLateMix < 0 || earlyLateMix > 1.0) {
            throw new IllegalArgumentException(
                    "earlyLateMix must be in [0, 1]: " + earlyLateMix);
        }
        this.earlyLateMix = earlyLateMix;
    }

    @ProcessorParam(id = 3, name = "Damping", min = 0.0, max = 1.0, defaultValue = 0.5)
    public double getDamping() { return damping; }

    public void setDamping(double damping) {
        if (damping < 0 || damping > 1.0) {
            throw new IllegalArgumentException("damping must be in [0, 1]: " + damping);
        }
        this.damping = damping;
    }

    @ProcessorParam(id = 4, name = "Mix", min = 0.0, max = 1.0, defaultValue = 0.3)
    public double getMix() { return mix; }

    public void setMix(double mix) {
        if (mix < 0 || mix > 1.0) {
            throw new IllegalArgumentException("mix must be in [0, 1]: " + mix);
        }
        this.mix = mix;
    }

    @Override
    public void reset() {
        for (int ch = 0; ch < channels; ch++) {
            Arrays.fill(ringBuffer[ch], 0.0f);
            writePos[ch] = 0;
            dampState[ch] = 0.0f;
        }
    }

    @Override
    public int getInputChannelCount() { return channels; }

    @Override
    public int getOutputChannelCount() { return channels; }

    // --- Private methods ---

    /**
     * Ensures the pre-allocated work buffers are large enough for the given frame count.
     */
    private void ensureWorkBufferSize(int numFrames) {
        if (numFrames > workBufferSize) {
            workBufferSize = numFrames;
            int bufferCount = Math.max(lateSegmentCount, NUM_LATE_SEGMENTS);
            segmentWorkBuffers = new float[bufferCount][workBufferSize];
        }
    }

    /**
     * Processes all late-reverb segments using sparse velvet-noise convolution.
     * When enough segments exist, processing is parallelized across virtual threads
     * using {@link Executors#newVirtualThreadPerTaskExecutor()}.
     */
    private void processLateSegments(int ch, int baseWritePos, int numFrames) {
        for (int seg = 0; seg < lateSegmentCount; seg++) {
            Arrays.fill(segmentWorkBuffers[seg], 0, numFrames, 0.0f);
        }

        if (lateSegmentCount >= PARALLEL_SEGMENT_THRESHOLD) {
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<?>[] futures = new Future<?>[lateSegmentCount];
                for (int seg = 0; seg < lateSegmentCount; seg++) {
                    int segIdx = seg;
                    futures[seg] = executor.submit(() ->
                            computeSegment(ch, segIdx, baseWritePos, numFrames));
                }
                for (int seg = 0; seg < lateSegmentCount; seg++) {
                    try {
                        futures[seg].get();
                    } catch (Exception e) {
                        // Segment work buffer remains zeroed on failure
                    }
                }
            }
        } else {
            for (int seg = 0; seg < lateSegmentCount; seg++) {
                computeSegment(ch, seg, baseWritePos, numFrames);
            }
        }
    }

    /**
     * Computes a single late-reverb segment using sparse velvet-noise convolution.
     * Within a segment, all operations are additions and subtractions — no
     * per-pulse multiplications are required.
     */
    private void computeSegment(int ch, int seg, int baseWritePos, int numFrames) {
        int pulseCount = latePulseCounts[seg];
        float[] output = segmentWorkBuffers[seg];
        int[] delays = lateDelays[ch][seg];
        int[] polarities = latePolarities[ch][seg];
        float[] ring = ringBuffer[ch];

        for (int frame = 0; frame < numFrames; frame++) {
            int currentPos = (baseWritePos + frame) % ringLength;
            float sum = 0.0f;
            for (int p = 0; p < pulseCount; p++) {
                int readPos = (currentPos - delays[p] + ringLength) % ringLength;
                float sample = ring[readPos];
                // Pure addition or subtraction — no multiplication per pulse
                if (polarities[p] == 1) {
                    sum += sample;
                } else {
                    sum -= sample;
                }
            }
            output[frame] = sum;
        }
    }

    /**
     * Generates the velvet-noise impulse response as sparse pulse sequences.
     *
     * <p>The response is split into early reflections (first ~80 ms) with
     * individual per-pulse decay gains, and late reverb segments with constant
     * per-segment gains for efficient parallel processing. Pulse values are
     * restricted to {−1, 0, +1} with positions placed randomly within grid
     * intervals determined by the pulse density. Different channels receive
     * different random sequences for stereo decorrelation.</p>
     */
    private void generateVelvetNoiseSequence() {
        double reverbSeconds = MIN_DECAY_SECONDS
                + decayTime * (MAX_DECAY_SECONDS - MIN_DECAY_SECONDS);
        double pulsesPerSecond = MIN_DENSITY_PPS
                + density * (MAX_DENSITY_PPS - MIN_DENSITY_PPS);

        int reverbSamples = Math.max(1, (int) (reverbSeconds * sampleRate));
        ringLength = reverbSamples + 1;
        ringBuffer = new float[channels][ringLength];
        writePos = new int[channels];

        int earlyBoundary = Math.min(
                (int) (EARLY_REFLECTION_MS * 0.001 * sampleRate), reverbSamples);

        // --- Early reflections ---
        double earlyDurationSec = earlyBoundary / sampleRate;
        earlyPulseCount = Math.max(1, (int) (pulsesPerSecond * earlyDurationSec * 0.3));
        earlyDelays = new int[channels][earlyPulseCount];
        earlyPolarities = new int[channels][earlyPulseCount];
        earlyGains = new float[channels][earlyPulseCount];

        double earlyNorm = 1.0 / Math.sqrt(Math.max(1, earlyPulseCount));

        // Decay rate: -60dB over reverb length (ln(0.001) ≈ -6.908)
        double decayRate = -6.908 / reverbSamples;

        for (int ch = 0; ch < channels; ch++) {
            Random rng = new Random(BASE_SEED + ch * 31L);
            double gridSize = (double) earlyBoundary / earlyPulseCount;
            for (int p = 0; p < earlyPulseCount; p++) {
                int gridStart = (int) (p * gridSize);
                int gridEnd = (int) ((p + 1) * gridSize);
                gridEnd = Math.min(gridEnd, earlyBoundary);
                int pos = gridStart + rng.nextInt(Math.max(1, gridEnd - gridStart));
                pos = Math.max(1, Math.min(pos, earlyBoundary - 1));
                earlyDelays[ch][p] = pos;
                earlyPolarities[ch][p] = rng.nextBoolean() ? 1 : -1;

                float envelope = (float) Math.exp(decayRate * pos);
                earlyGains[ch][p] = (float) (envelope * earlyNorm);
            }
        }

        // --- Late reverb segments ---
        int lateStart = earlyBoundary;
        int lateDuration = reverbSamples - lateStart;
        if (lateDuration <= 0) {
            lateSegmentCount = 0;
            lateDelays = new int[channels][0][];
            latePolarities = new int[channels][0][];
            lateSegmentGains = new float[0];
            latePulseCounts = new int[0];
            return;
        }

        lateSegmentCount = Math.min(NUM_LATE_SEGMENTS,
                Math.max(1, lateDuration / Math.max(1, (int) (sampleRate * 0.05))));
        int samplesPerSeg = lateDuration / lateSegmentCount;

        lateDelays = new int[channels][lateSegmentCount][];
        latePolarities = new int[channels][lateSegmentCount][];
        lateSegmentGains = new float[lateSegmentCount];
        latePulseCounts = new int[lateSegmentCount];

        for (int seg = 0; seg < lateSegmentCount; seg++) {
            int segStart = lateStart + seg * samplesPerSeg;
            int segEnd = (seg == lateSegmentCount - 1)
                    ? reverbSamples : segStart + samplesPerSeg;
            double segDurationSec = (segEnd - segStart) / sampleRate;
            int segPulses = Math.max(1, (int) (pulsesPerSecond * segDurationSec));
            latePulseCounts[seg] = segPulses;

            // Segment gain: exponential decay at midpoint, normalized by pulse count
            double segMidSample = (segStart + segEnd) / 2.0;
            float envelope = (float) Math.exp(decayRate * segMidSample);
            double segNorm = 1.0 / Math.sqrt(Math.max(1, segPulses));
            lateSegmentGains[seg] = (float) (envelope * segNorm);

            for (int ch = 0; ch < channels; ch++) {
                Random rng = new Random(BASE_SEED + ch * 31L + seg * 97L);
                lateDelays[ch][seg] = new int[segPulses];
                latePolarities[ch][seg] = new int[segPulses];

                double grid = (double) (segEnd - segStart) / segPulses;
                for (int p = 0; p < segPulses; p++) {
                    int gridStart = segStart + (int) (p * grid);
                    int gridEnd = segStart + (int) ((p + 1) * grid);
                    gridEnd = Math.min(gridEnd, segEnd);
                    int pos = gridStart + rng.nextInt(Math.max(1, gridEnd - gridStart));
                    pos = Math.max(segStart, Math.min(pos, segEnd - 1));
                    lateDelays[ch][seg][p] = pos;
                    latePolarities[ch][seg][p] = rng.nextBoolean() ? 1 : -1;
                }
            }
        }

        // Ensure work buffers match segment count
        if (segmentWorkBuffers.length < lateSegmentCount) {
            segmentWorkBuffers = new float[lateSegmentCount][workBufferSize];
        }
    }
}
