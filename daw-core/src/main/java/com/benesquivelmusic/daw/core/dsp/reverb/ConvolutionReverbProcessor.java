package com.benesquivelmusic.daw.core.dsp.reverb;

import com.benesquivelmusic.daw.core.analysis.FftUtils;
import com.benesquivelmusic.daw.core.mixer.InsertEffect;
import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Convolution reverb processor using uniformly-partitioned FFT-based convolution.
 *
 * <p>Convolves the input signal against a (possibly long) impulse response
 * — captured rooms, plates, springs, hardware reverbs — using uniformly
 * partitioned overlap-save in the frequency domain. This makes processing
 * cost roughly {@code O(N log B)} per sample, where {@code B} is the
 * partition (block) size, instead of {@code O(N)} for direct convolution
 * — viable for impulse responses up to 10 seconds at 48 kHz.</p>
 *
 * <h2>Algorithm</h2>
 * <ul>
 *   <li>The IR is split into {@link #PARTITION_SIZE}-sample partitions; each
 *       is FFT-transformed once when the IR is loaded ({@link Kernel}).</li>
 *   <li>An input ring of {@code K} most-recent input-block FFTs (the
 *       <em>frequency-domain delay line</em>) is maintained on the audio
 *       thread.</li>
 *   <li>For each output block, every IR partition FFT is multiplied with
 *       the corresponding delayed input FFT and accumulated; the inverse
 *       FFT yields a {@code 2B}-sample buffer whose second half is the
 *       output (overlap-save).</li>
 * </ul>
 *
 * <h2>Parameters</h2>
 * <ul>
 *   <li>IR selection ({@code 0..} = bundled IR index)</li>
 *   <li>Stretch factor (0.5×–2.0× IR length)</li>
 *   <li>Predelay (0–200 ms)</li>
 *   <li>Low-cut and high-cut filters on the wet signal</li>
 *   <li>Trim start / trim end (fractions of IR length)</li>
 *   <li>Stereo width and dry/wet mix</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * <p>{@link #process} is real-time safe: it allocates nothing and only
 * reads the {@link Kernel} and partition buffers atomically. IR
 * preparation (file decode, FFT-of-partitions, normalization) is
 * non-realtime and is dispatched onto a virtual thread by
 * {@link #setImpulseResponseAsync}.</p>
 */
@InsertEffect(type = "CONVOLUTION_REVERB", displayName = "Convolution Reverb")
public final class ConvolutionReverbProcessor implements AudioProcessor {

    /** Partition / block size — power of two; FFT length is twice this. */
    public static final int PARTITION_SIZE = 256;
    /** FFT length — twice the partition size for overlap-save. */
    public static final int FFT_SIZE = 2 * PARTITION_SIZE;
    /** Maximum supported IR length in seconds at any sample rate. */
    public static final double MAX_IR_LENGTH_SECONDS = 10.0;

    private final int channels;
    private final double sampleRate;

    // ── Parameters (all guarded by per-set range checks) ────────────────
    private double irSelection;     // index into ImpulseResponseLibrary.ENTRIES
    private double stretch = 1.0;   // 0.5..2.0
    private double predelayMs = 0.0;
    private double lowCutHz = 20.0;
    private double highCutHz = 20000.0;
    private double mix = 0.3;
    private double stereoWidth = 1.0;
    private double trimStart = 0.0;
    private double trimEnd = 1.0;

    // ── Kernel (atomically swapped from worker thread) ──────────────────
    private final AtomicReference<Kernel> kernel = new AtomicReference<>(Kernel.EMPTY);

    // ── RT scratch buffers (pre-allocated; never resized at runtime) ────
    private final float[][] inputRing;       // [ch][PARTITION_SIZE], collects incoming samples
    private final int[] inputRingPos;        // current write position per channel
    private final float[][] outputCarry;     // [ch][PARTITION_SIZE], overlap from previous block
    private final double[][][] fdl;          // [ch][partition][2*FFT_SIZE] — frequency-domain delay line (re,im interleaved)
    private final int[] fdlIndex;            // ring index per channel
    private final double[] fftReal;          // [FFT_SIZE]
    private final double[] fftImag;          // [FFT_SIZE]
    private final double[] accumReal;        // [FFT_SIZE]
    private final double[] accumImag;        // [FFT_SIZE]
    private final float[][] predelayBuffer;  // [ch][maxPredelaySamples]
    private final int[] predelayPos;
    private final int maxPredelaySamples;

    // ── Wet-signal filters (one-pole HP/LP per channel) ─────────────────
    private final double[] hpStateX1;
    private final double[] hpStateY1;
    private final double[] lpStateY1;

    /**
     * Creates a convolution reverb processor and synchronously loads the
     * default bundled impulse response (small room).
     *
     * @param channels   number of audio channels (1 or 2)
     * @param sampleRate sample rate in Hz
     */
    public ConvolutionReverbProcessor(int channels, double sampleRate) {
        if (channels <= 0 || channels > 2) {
            throw new IllegalArgumentException("channels must be 1 or 2: " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        this.channels = channels;
        this.sampleRate = sampleRate;

        this.maxPredelaySamples = (int) Math.ceil(0.2 * sampleRate) + 1;
        this.predelayBuffer = new float[channels][maxPredelaySamples];
        this.predelayPos = new int[channels];

        // Allocate enough partitions for the worst-case IR (10 s)
        int maxPartitions = (int) Math.ceil(MAX_IR_LENGTH_SECONDS * sampleRate / PARTITION_SIZE) + 1;
        this.inputRing = new float[channels][PARTITION_SIZE];
        this.inputRingPos = new int[channels];
        this.outputCarry = new float[channels][PARTITION_SIZE];
        this.fdl = new double[channels][maxPartitions][2 * FFT_SIZE];
        this.fdlIndex = new int[channels];
        this.fftReal = new double[FFT_SIZE];
        this.fftImag = new double[FFT_SIZE];
        this.accumReal = new double[FFT_SIZE];
        this.accumImag = new double[FFT_SIZE];

        this.hpStateX1 = new double[channels];
        this.hpStateY1 = new double[channels];
        this.lpStateY1 = new double[channels];
        this.prevBlocks = new float[channels][PARTITION_SIZE];

        // Load default IR synchronously so the processor is immediately useful
        loadBundled(0);
    }

    // ── IR loading ─────────────────────────────────────────────────────

    /**
     * Loads the bundled IR at the given index. Called synchronously — only
     * use from constructor or worker threads.
     */
    private void loadBundled(int index) {
        index = Math.max(0, Math.min(ImpulseResponseLibrary.ENTRIES.size() - 1, index));
        ImpulseResponseLibrary.Entry e = ImpulseResponseLibrary.ENTRIES.get(index);
        float[][] ir = ImpulseResponseLibrary.load(e.id(), sampleRate);
        // Keep a pristine copy so trim/stretch can always re-prepare from source.
        this.pristineIr = deepCopy(ir);
        applyImpulseResponse(ir, e.id());
        this.irSelection = index;
    }

    /** Pristine (post-load, pre-trim/stretch) IR retained so reloadCurrentIr() can re-prepare. */
    private float[][] pristineIr;
    /** Path of the last user-loaded IR file, if any — used to reload after trim/stretch. */
    private Path pristinePath;

    private static float[][] deepCopy(float[][] ir) {
        if (ir == null) return null;
        float[][] out = new float[ir.length][];
        for (int i = 0; i < ir.length; i++) {
            out[i] = ir[i] == null ? null : ir[i].clone();
        }
        return out;
    }

    /**
     * Single-thread executor used for non-realtime IR preparation. Tasks
     * are serialized so that the latest swap always wins. The worker
     * thread is a virtual thread (Project Loom, JEP 444) and the executor
     * is shut down with the JVM.
     */
    private static final java.util.concurrent.ExecutorService IR_PREP_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    r -> Thread.ofVirtual().name("ConvolutionReverb-IR-Prep").unstarted(r));

    /**
     * Waits for any pending IR-preparation work submitted via async
     * setters to finish. Intended for tests; not for real-time use.
     */
    public void awaitIrPreparation() {
        try {
            IR_PREP_EXECUTOR.submit(() -> {}).get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException ignored) {
            // task is a no-op; nothing to surface
        }
    }

    /**
     * Replaces the impulse response. May allocate; must not be called from
     * the audio thread. Use {@link #setImpulseResponseAsync(float[][])} for
     * audio-thread-safe asynchronous loading.
     *
     * @param ir an IR with one or two channels; null clears the IR
     */
    public void setImpulseResponse(float[][] ir) {
        validateIrShape(ir);
        this.pristineIr = deepCopy(ir);
        this.pristinePath = null;
        applyImpulseResponse(ir, null);
    }

    /**
     * Asynchronously prepares and installs an impulse response on a virtual
     * thread. The audio thread continues to use the previous IR until the
     * returned future completes; the swap is atomic via
     * {@link AtomicReference}.
     *
     * @param ir the new IR
     * @return a future that completes when the IR is installed
     */
    public CompletableFuture<Void> setImpulseResponseAsync(float[][] ir) {
        validateIrShape(ir);
        return CompletableFuture.runAsync(() -> {
            this.pristineIr = deepCopy(ir);
            this.pristinePath = null;
            applyImpulseResponse(ir, null);
        }, IR_PREP_EXECUTOR);
    }

    /**
     * Asynchronously loads an IR from a file on a virtual thread.
     *
     * @param path the path to a WAV impulse response
     * @return a future that completes when the IR is installed
     */
    public CompletableFuture<Void> loadImpulseResponseFromFileAsync(Path path) {
        Objects.requireNonNull(path, "path");
        return CompletableFuture.runAsync(() -> {
            try {
                float[][] ir = ImpulseResponseLibrary.loadFromFile(path, sampleRate);
                this.pristineIr = deepCopy(ir);
                this.pristinePath = path;
                applyImpulseResponse(ir, path.toString());
            } catch (Exception ex) {
                throw new RuntimeException("Failed to load IR: " + path, ex);
            }
        }, IR_PREP_EXECUTOR);
    }

    /**
     * Validates that the IR has a usable shape. {@code null} or empty is
     * treated as "clear the IR" by {@link #applyImpulseResponse}, but a
     * non-empty IR with mismatched / null channel rows is rejected.
     */
    private static void validateIrShape(float[][] ir) {
        if (ir == null || ir.length == 0) return;
        if (ir[0] == null) {
            throw new IllegalArgumentException("IR channel 0 is null");
        }
        int n = ir[0].length;
        for (int c = 1; c < ir.length; c++) {
            if (ir[c] == null) {
                throw new IllegalArgumentException("IR channel " + c + " is null");
            }
            if (ir[c].length != n) {
                throw new IllegalArgumentException(
                        "IR channels have mismatched length: ch0=" + n + " ch" + c + "=" + ir[c].length);
            }
        }
    }

    private void applyImpulseResponse(float[][] ir, String sourceId) {
        if (ir == null || ir.length == 0 || ir[0] == null || ir[0].length == 0) {
            kernel.set(Kernel.EMPTY);
            return;
        }
        // Defensive validation: every channel must be non-null and the same length.
        int n = ir[0].length;
        for (int c = 1; c < ir.length; c++) {
            if (ir[c] == null || ir[c].length != n) {
                throw new IllegalArgumentException(
                        "IR channel arrays must be non-null and same length (ch0=" + n
                        + ", ch" + c + "=" + (ir[c] == null ? "null" : ir[c].length) + ")");
            }
        }
        // Apply trim and stretch off the audio thread (allocations OK here)
        float[][] processed = applyTrimAndStretch(ir);
        Kernel k = Kernel.build(processed, channels, sampleRate);
        k = new Kernel(k.partitions, k.numPartitions, k.lengthSamples, sourceId);
        kernel.set(k);
    }

    private float[][] applyTrimAndStretch(float[][] ir) {
        int chs = ir.length;
        int n = ir[0].length;
        int start = (int) Math.max(0, Math.min(n - 1, Math.round(trimStart * n)));
        int end = (int) Math.max(start + 1, Math.min(n, Math.round(trimEnd * n)));
        int trimmedLen = end - start;
        // Stretch by linear resampling
        int outLen = (int) Math.max(1, Math.round(trimmedLen * stretch));
        // Cap at MAX_IR_LENGTH_SECONDS so the FDL never overflows
        int cap = (int) (MAX_IR_LENGTH_SECONDS * sampleRate);
        if (outLen > cap) {
            outLen = cap;
        }
        float[][] out = new float[chs][outLen];
        for (int c = 0; c < chs; c++) {
            for (int i = 0; i < outLen; i++) {
                double srcPos = start + (i / (double) outLen) * trimmedLen;
                int idx = (int) srcPos;
                double frac = srcPos - idx;
                float a = ir[c][Math.min(idx, end - 1)];
                float b = ir[c][Math.min(idx + 1, end - 1)];
                out[c][i] = (float) (a + (b - a) * frac);
            }
        }
        return out;
    }

    /** Returns a copy of the currently loaded (post-trim/stretch) IR for UI rendering. */
    public float[][] getImpulseResponseSnapshot() {
        Kernel k = kernel.get();
        if (k == null || k.numPartitions == 0) {
            return new float[channels][0];
        }
        // Reconstruct time-domain IR from frequency partitions
        float[][] out = new float[channels][k.lengthSamples];
        double[] re = new double[FFT_SIZE];
        double[] im = new double[FFT_SIZE];
        for (int ch = 0; ch < channels; ch++) {
            for (int p = 0; p < k.numPartitions; p++) {
                System.arraycopy(k.partitions[Math.min(ch, k.partitions.length - 1)][p], 0, re, 0, FFT_SIZE);
                Arrays.fill(im, 0.0);
                // partitions store re[0..FFT_SIZE-1] then im[0..FFT_SIZE-1]
                System.arraycopy(k.partitions[Math.min(ch, k.partitions.length - 1)][p], FFT_SIZE, im, 0, FFT_SIZE);
                FftUtils.ifft(re, im);
                int dstStart = p * PARTITION_SIZE;
                for (int i = 0; i < PARTITION_SIZE && dstStart + i < k.lengthSamples; i++) {
                    out[ch][dstStart + i] = (float) re[i];
                }
            }
        }
        return out;
    }

    /** Returns the source identifier of the currently loaded IR (id or path), or null if none. */
    public String getImpulseResponseSourceId() {
        Kernel k = kernel.get();
        return k == null ? null : k.sourceId;
    }

    /** Returns the loaded IR length in sample frames (after trim/stretch), or 0 if no IR. */
    public int getImpulseResponseLength() {
        return kernel.get().lengthSamples;
    }

    // ── Audio processing ───────────────────────────────────────────────

    @RealTimeSafe
    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        Kernel k = kernel.get();
        int activeCh = Math.min(channels, Math.min(inputBuffer.length, outputBuffer.length));
        int predelay = Math.min(maxPredelaySamples - 1,
                (int) Math.round(predelayMs * 0.001 * sampleRate));

        // HP / LP coefficients (one-pole), recomputed from current params
        double hpA = computeOnePoleCoeff(lowCutHz);
        double lpA = computeOnePoleCoeff(highCutHz);
        double widthScale = stereoWidth;
        double dryGain = 1.0 - mix;
        double wetGain = mix;

        for (int frame = 0; frame < numFrames; frame++) {
            for (int ch = 0; ch < activeCh; ch++) {
                float dry = inputBuffer[ch][frame];

                // Predelay: write current dry into ring, then read at
                // (writePos - delay) so a delay of 0 yields the dry sample.
                predelayBuffer[ch][predelayPos[ch]] = dry;
                int rdPos = (predelayPos[ch] - predelay + maxPredelaySamples) % maxPredelaySamples;
                float delayedIn = predelayBuffer[ch][rdPos];
                predelayPos[ch] = (predelayPos[ch] + 1) % maxPredelaySamples;

                // Push into input ring at the current write position; read the
                // wet sample at the same index from the previously-computed
                // output block — this introduces exactly PARTITION_SIZE samples
                // of latency (reported via getLatencySamples).
                int pos = inputRingPos[ch];
                inputRing[ch][pos] = delayedIn;
                float wet = outputCarry[ch][pos];
                inputRingPos[ch] = pos + 1;
                if (inputRingPos[ch] >= PARTITION_SIZE) {
                    runConvolutionBlock(ch, k);
                    inputRingPos[ch] = 0;
                }

                // Apply HP/LP (one-pole each) on wet signal
                double hpY = wet - hpStateX1[ch] + hpA * hpStateY1[ch];
                hpStateX1[ch] = wet;
                hpStateY1[ch] = hpY;
                double lpY = lpStateY1[ch] + (1.0 - lpA) * (hpY - lpStateY1[ch]);
                lpStateY1[ch] = lpY;
                wet = (float) lpY;

                // Mix dry + wet
                outputBuffer[ch][frame] = (float) (dry * dryGain + wet * wetGain);
            }
        }

        // Stereo-width adjustment via mid/side
        if (activeCh == 2 && widthScale != 1.0) {
            for (int frame = 0; frame < numFrames; frame++) {
                float l = outputBuffer[0][frame];
                float r = outputBuffer[1][frame];
                double mid = 0.5 * (l + r);
                double side = 0.5 * (l - r) * widthScale;
                outputBuffer[0][frame] = (float) (mid + side);
                outputBuffer[1][frame] = (float) (mid - side);
            }
        }
    }

    /**
     * Runs a single FFT-partition convolution block for one channel: forward
     * FFTs the latest {@code PARTITION_SIZE} input samples, accumulates
     * pairwise products with each IR partition's spectrum, IFFTs, and
     * deposits the second half into {@link #outputCarry}.
     */
    @RealTimeSafe
    private void runConvolutionBlock(int ch, Kernel k) {
        if (k.numPartitions == 0) {
            Arrays.fill(outputCarry[ch], 0f);
            return;
        }
        // Forward FFT of [previous PARTITION_SIZE samples (saved) | new PARTITION_SIZE]
        // Overlap-save: first half = last block's input, second half = current block.
        // We reuse the FDL slot by storing the full FFT_SIZE FFT.
        int partChIndex = Math.min(ch, k.partitions.length - 1);
        // Build input frame: first half is from previous block via FDL[partChIndex+? trick];
        // we keep a per-channel "last block" buffer by stashing it into fftReal pre-roll.
        // Simpler: keep the last block in inputRingPrev[ch].
        // We use an ad-hoc cached block: inputRing already holds the new block;
        // the previous block must be remembered. We use outputCarry's reuse: since
        // outputCarry was just consumed, we can re-purpose a separate stash.
        // For clarity store previous half in fdl[ch][lastIndex]'s "raw" slot? — too complex.
        // Instead: keep a per-channel scratch `prevBlock` field.
        float[] prev = prevBlock(ch);
        for (int i = 0; i < PARTITION_SIZE; i++) {
            fftReal[i] = prev[i];
        }
        for (int i = 0; i < PARTITION_SIZE; i++) {
            fftReal[PARTITION_SIZE + i] = inputRing[ch][i];
        }
        Arrays.fill(fftImag, 0.0);
        FftUtils.fft(fftReal, fftImag);

        // Save current input FFT into FDL ring
        int writeIdx = fdlIndex[ch];
        double[] fdlSlot = fdl[ch][writeIdx];
        System.arraycopy(fftReal, 0, fdlSlot, 0, FFT_SIZE);
        System.arraycopy(fftImag, 0, fdlSlot, FFT_SIZE, FFT_SIZE);
        // shift "prev" for next round: the current input becomes prev
        System.arraycopy(inputRing[ch], 0, prev, 0, PARTITION_SIZE);

        // Accumulate ∑ FDL[k] * H[k] across all partitions
        Arrays.fill(accumReal, 0.0);
        Arrays.fill(accumImag, 0.0);
        for (int p = 0; p < k.numPartitions; p++) {
            int fdlPos = (writeIdx - p + fdl[ch].length) % fdl[ch].length;
            double[] x = fdl[ch][fdlPos];
            double[] h = k.partitions[partChIndex][p];
            for (int b = 0; b < FFT_SIZE; b++) {
                double xr = x[b];
                double xi = x[FFT_SIZE + b];
                double hr = h[b];
                double hi = h[FFT_SIZE + b];
                accumReal[b] += xr * hr - xi * hi;
                accumImag[b] += xr * hi + xi * hr;
            }
        }

        // IFFT (in-place on accum buffers)
        FftUtils.ifft(accumReal, accumImag);

        // Second half is the valid output (overlap-save discards the first half)
        for (int i = 0; i < PARTITION_SIZE; i++) {
            outputCarry[ch][i] = (float) accumReal[PARTITION_SIZE + i];
        }

        fdlIndex[ch] = (writeIdx + 1) % fdl[ch].length;
    }

    /** Per-channel "previous input block" buffers used by overlap-save. */
    private final float[][] prevBlocks;

    private float[] prevBlock(int ch) {
        return prevBlocks[ch];
    }

    /** Maps a cutoff frequency to a one-pole filter coefficient. */
    private double computeOnePoleCoeff(double cutoffHz) {
        double f = Math.max(0.0, Math.min(sampleRate * 0.49, cutoffHz));
        double rc = 1.0 / (2.0 * Math.PI * Math.max(1.0, f));
        double dt = 1.0 / sampleRate;
        return rc / (rc + dt);
    }

    @Override
    public void reset() {
        for (int ch = 0; ch < channels; ch++) {
            Arrays.fill(inputRing[ch], 0f);
            Arrays.fill(outputCarry[ch], 0f);
            Arrays.fill(predelayBuffer[ch], 0f);
            for (double[] slot : fdl[ch]) {
                Arrays.fill(slot, 0.0);
            }
            inputRingPos[ch] = 0;
            predelayPos[ch] = 0;
            fdlIndex[ch] = 0;
            hpStateX1[ch] = 0;
            hpStateY1[ch] = 0;
            lpStateY1[ch] = 0;
        }
        if (prevBlocks != null) {
            for (float[] p : prevBlocks) Arrays.fill(p, 0f);
        }
    }

    @Override
    public int getInputChannelCount() { return channels; }

    @Override
    public int getOutputChannelCount() { return channels; }

    /**
     * Reports the partition latency to the host for plugin delay
     * compensation (PDC). The processor introduces exactly
     * {@link #PARTITION_SIZE} samples of latency because the convolution
     * output is delayed by one full block.
     */
    @Override
    public int getLatencySamples() {
        return PARTITION_SIZE;
    }

    // ── Annotated parameters ───────────────────────────────────────────

    @ProcessorParam(id = 0, name = "IR", min = 0.0, max = 7.0, defaultValue = 0.0)
    public double getIrSelection() { return irSelection; }

    /**
     * Selects a bundled IR by integer index (truncated).
     *
     * <p>Heavy work — IR decode/synthesis and FFT-partitioning — is
     * dispatched to {@link #IR_PREP_EXECUTOR} so the setter remains safe
     * to call from any thread (including via reflective automation).
     * The previous IR continues to play until the new kernel is ready.</p>
     */
    public void setIrSelection(double value) {
        // Clamp into valid range — the annotation's [0, 7] should match,
        // but be tolerant of slightly out-of-range automation values.
        int idx = (int) Math.round(value);
        idx = Math.max(0, Math.min(ImpulseResponseLibrary.ENTRIES.size() - 1, idx));
        if (idx == (int) Math.round(this.irSelection) && pristineIr != null) {
            // Already loaded — nothing to do.
            this.irSelection = idx;
            return;
        }
        this.irSelection = idx;
        final int finalIdx = idx;
        IR_PREP_EXECUTOR.execute(() -> loadBundled(finalIdx));
    }

    @ProcessorParam(id = 1, name = "Stretch", min = 0.5, max = 2.0, defaultValue = 1.0)
    public double getStretch() { return stretch; }

    public void setStretch(double v) {
        // Clamp into valid range so automation feeding endpoints never throws.
        v = Math.max(0.5, Math.min(2.0, v));
        if (v == this.stretch) return;
        this.stretch = v;
        // Re-prepare the current IR off the audio thread.
        IR_PREP_EXECUTOR.execute(this::reloadCurrentIr);
    }

    @ProcessorParam(id = 2, name = "Predelay", min = 0.0, max = 200.0, defaultValue = 0.0, unit = "ms")
    public double getPredelayMs() { return predelayMs; }

    public void setPredelayMs(double v) {
        if (v < 0.0 || v > 200.0) {
            throw new IllegalArgumentException("predelayMs must be in [0, 200]: " + v);
        }
        this.predelayMs = v;
    }

    @ProcessorParam(id = 3, name = "Low Cut", min = 20.0, max = 1000.0, defaultValue = 20.0, unit = "Hz")
    public double getLowCutHz() { return lowCutHz; }

    public void setLowCutHz(double v) {
        if (v < 20.0 || v > 1000.0) {
            throw new IllegalArgumentException("lowCutHz must be in [20, 1000]: " + v);
        }
        this.lowCutHz = v;
    }

    @ProcessorParam(id = 4, name = "High Cut", min = 1000.0, max = 20000.0, defaultValue = 20000.0, unit = "Hz")
    public double getHighCutHz() { return highCutHz; }

    public void setHighCutHz(double v) {
        if (v < 1000.0 || v > 20000.0) {
            throw new IllegalArgumentException("highCutHz must be in [1000, 20000]: " + v);
        }
        this.highCutHz = v;
    }

    @ProcessorParam(id = 5, name = "Mix", min = 0.0, max = 1.0, defaultValue = 0.3)
    public double getMix() { return mix; }

    public void setMix(double v) {
        if (v < 0.0 || v > 1.0) {
            throw new IllegalArgumentException("mix must be in [0, 1]: " + v);
        }
        this.mix = v;
    }

    @ProcessorParam(id = 6, name = "Width", min = 0.0, max = 2.0, defaultValue = 1.0)
    public double getStereoWidth() { return stereoWidth; }

    public void setStereoWidth(double v) {
        if (v < 0.0 || v > 2.0) {
            throw new IllegalArgumentException("stereoWidth must be in [0, 2]: " + v);
        }
        this.stereoWidth = v;
    }

    @ProcessorParam(id = 7, name = "Trim Start", min = 0.0, max = 1.0, defaultValue = 0.0)
    public double getTrimStart() { return trimStart; }

    public void setTrimStart(double v) {
        // Clamp to annotation range and ensure ordering with trimEnd.
        v = Math.max(0.0, Math.min(1.0, v));
        if (v >= trimEnd) {
            v = Math.max(0.0, trimEnd - 1e-6);
        }
        if (v == this.trimStart) return;
        this.trimStart = v;
        IR_PREP_EXECUTOR.execute(this::reloadCurrentIr);
    }

    @ProcessorParam(id = 8, name = "Trim End", min = 0.0, max = 1.0, defaultValue = 1.0)
    public double getTrimEnd() { return trimEnd; }

    public void setTrimEnd(double v) {
        // Clamp to annotation range and ensure ordering with trimStart.
        v = Math.max(0.0, Math.min(1.0, v));
        if (v <= trimStart) {
            v = Math.min(1.0, trimStart + 1e-6);
        }
        if (v == this.trimEnd) return;
        this.trimEnd = v;
        IR_PREP_EXECUTOR.execute(this::reloadCurrentIr);
    }

    private void reloadCurrentIr() {
        // Re-prepare from the pristine cached IR if available — this works for
        // bundled, programmatically-set, and file-loaded IRs alike.
        if (pristineIr != null) {
            applyImpulseResponse(pristineIr,
                    pristinePath != null ? pristinePath.toString() : currentBundledId());
            return;
        }
        // Fallback: empty kernel + a fresh bundled load.
        Kernel k = kernel.get();
        if (k == null || k == Kernel.EMPTY) {
            int idx = (int) Math.round(irSelection);
            idx = Math.max(0, Math.min(ImpulseResponseLibrary.ENTRIES.size() - 1, idx));
            ImpulseResponseLibrary.Entry e = ImpulseResponseLibrary.ENTRIES.get(idx);
            float[][] ir = ImpulseResponseLibrary.load(e.id(), sampleRate);
            this.pristineIr = deepCopy(ir);
            applyImpulseResponse(ir, e.id());
            return;
        }
        // For bundled IRs we can recover by id; for path-backed IRs we re-read.
        if (k.sourceId != null) {
            ImpulseResponseLibrary.Entry e = ImpulseResponseLibrary.findById(k.sourceId);
            if (e != null) {
                float[][] ir = ImpulseResponseLibrary.load(e.id(), sampleRate);
                this.pristineIr = deepCopy(ir);
                applyImpulseResponse(ir, e.id());
            }
        }
    }

    private String currentBundledId() {
        int idx = (int) Math.round(irSelection);
        if (idx >= 0 && idx < ImpulseResponseLibrary.ENTRIES.size()) {
            return ImpulseResponseLibrary.ENTRIES.get(idx).id();
        }
        return null;
    }

    // ── Kernel ─────────────────────────────────────────────────────────

    /**
     * Immutable frequency-domain partitioned IR ready for real-time
     * convolution. Atomically swapped by the worker thread once preparation
     * is complete.
     *
     * @param partitions    [channel][partitionIndex][2*FFT_SIZE] real+imag
     * @param numPartitions number of partitions covering the IR
     * @param lengthSamples original IR length in samples after trim/stretch
     * @param sourceId      bundled-id or file-path (for persistence/debug)
     */
    record Kernel(double[][][] partitions, int numPartitions, int lengthSamples, String sourceId) {

        static final Kernel EMPTY =
                new Kernel(new double[1][0][0], 0, 0, null);

        static Kernel build(float[][] ir, int channels, double sampleRate) {
            int irChannels = ir.length;
            int n = ir[0].length;
            int numPartitions = (int) Math.ceil(n / (double) PARTITION_SIZE);
            // The number of channels in partitions is min(2, irChannels) so a stereo
            // host using a mono IR sees the IR mirrored across both channels.
            int kchs = Math.max(1, Math.min(channels, irChannels));
            double[][][] parts = new double[kchs][numPartitions][2 * FFT_SIZE];
            double[] re = new double[FFT_SIZE];
            double[] im = new double[FFT_SIZE];
            for (int ch = 0; ch < kchs; ch++) {
                float[] src = ir[Math.min(ch, irChannels - 1)];
                for (int p = 0; p < numPartitions; p++) {
                    Arrays.fill(re, 0.0);
                    Arrays.fill(im, 0.0);
                    int srcStart = p * PARTITION_SIZE;
                    int copyLen = Math.min(PARTITION_SIZE, n - srcStart);
                    for (int i = 0; i < copyLen; i++) {
                        re[i] = src[srcStart + i];
                    }
                    FftUtils.fft(re, im);
                    System.arraycopy(re, 0, parts[ch][p], 0, FFT_SIZE);
                    System.arraycopy(im, 0, parts[ch][p], FFT_SIZE, FFT_SIZE);
                }
            }
            return new Kernel(parts, numPartitions, n, null);
        }
    }
}
