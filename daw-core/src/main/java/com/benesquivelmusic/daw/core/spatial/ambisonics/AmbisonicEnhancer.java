package com.benesquivelmusic.daw.core.spatial.ambisonics;

import com.benesquivelmusic.daw.core.analysis.FftUtils;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.Arrays;

/**
 * Enhances first-order Ambisonic (FOA) signals to achieve apparent higher-order
 * spatial resolution using time-frequency masking.
 *
 * <p>FOA recordings (4 channels: W, Y, Z, X in ACN/SN3D order) have limited
 * spatial resolution. This processor applies STFT-domain directional analysis
 * and time-frequency masking to sharpen spatial images, complementing the
 * existing {@link AsdmProcessor} (Ambisonic Spatial Decomposition Method).</p>
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>STFT analysis of all 4 FOA channels using a Hann window with
 *       overlap-add reconstruction.</li>
 *   <li>Per time-frequency bin: compute the active intensity vector
 *       {@code I = [W·X, W·Y, W·Z]} and the energy density
 *       {@code E = W² + X² + Y² + Z²}.</li>
 *   <li>Estimate the diffuseness parameter:
 *       {@code ψ = 1 − 2·|I| / E} (clamped to [0, 1]).</li>
 *   <li>Apply temporal smoothing to the diffuseness estimate.</li>
 *   <li>Generate a directional gain mask:
 *       {@code G = 1 + strength · (1 − ψ − threshold)} (clamped to [0, 2])
 *       where bins with high directionality are boosted and diffuse bins
 *       are attenuated.</li>
 *   <li>Apply the mask to all 4 FOA channels in the STFT domain.</li>
 *   <li>Overlap-add ISTFT synthesis to produce enhanced FOA output.</li>
 * </ol>
 *
 * <h2>References</h2>
 * <ul>
 *   <li>"Enhancement of Ambisonics Signals using time-frequency masking" (AES, 2020)</li>
 *   <li>"Four-Directional Ambisonic Spatial Decomposition Method With Reduced
 *       Temporal Artifacts" (AES, 2022)</li>
 * </ul>
 *
 * @see AsdmProcessor
 * @see FftUtils
 * @see SphericalHarmonics
 */
public final class AmbisonicEnhancer implements AudioProcessor {

    private static final int FOA_CHANNELS = 4;

    private final int fftSize;
    private final int hopSize;
    private final double[] window;

    // Per-channel STFT working buffers (real/imag)
    private final double[][] stftReal;
    private final double[][] stftImag;

    // Overlap-add output accumulator and input buffer per channel
    private final double[][] overlapBuffer;
    private final double[][] inputAccumulator;
    private int inputWritePos;

    // Smoothed diffuseness per frequency bin
    private final double[] smoothedDiffuseness;

    // Parameters
    private double enhancementStrength;
    private double directDiffuseThreshold;
    private double temporalSmoothing;

    /**
     * Creates an Ambisonic enhancer with default parameters.
     *
     * <p>Defaults: FFT size = 1024, enhancement strength = 1.0,
     * direct/diffuse threshold = 0.5, temporal smoothing = 0.8.</p>
     */
    public AmbisonicEnhancer() {
        this(1024, 1.0, 0.5, 0.8);
    }

    /**
     * Creates an Ambisonic enhancer with the specified parameters.
     *
     * @param fftSize                the FFT size (must be a power of two, ≥ 64)
     * @param enhancementStrength    the enhancement strength in [0, 1] where 0 = no
     *                               enhancement (passthrough) and 1 = maximum enhancement
     * @param directDiffuseThreshold the threshold in [0, 1] above which a bin is considered
     *                               directional; lower values enhance more aggressively
     * @param temporalSmoothing      temporal smoothing factor in [0, 1) for the diffuseness
     *                               estimate; higher values yield smoother but slower response
     */
    public AmbisonicEnhancer(int fftSize, double enhancementStrength,
                             double directDiffuseThreshold, double temporalSmoothing) {
        if (fftSize < 64 || (fftSize & (fftSize - 1)) != 0) {
            throw new IllegalArgumentException(
                    "fftSize must be a power of two >= 64: " + fftSize);
        }
        validateEnhancementStrength(enhancementStrength);
        validateDirectDiffuseThreshold(directDiffuseThreshold);
        validateTemporalSmoothing(temporalSmoothing);

        this.fftSize = fftSize;
        this.hopSize = fftSize / 2;
        this.window = FftUtils.createHannWindow(fftSize);

        this.enhancementStrength = enhancementStrength;
        this.directDiffuseThreshold = directDiffuseThreshold;
        this.temporalSmoothing = temporalSmoothing;

        this.stftReal = new double[FOA_CHANNELS][fftSize];
        this.stftImag = new double[FOA_CHANNELS][fftSize];
        this.overlapBuffer = new double[FOA_CHANNELS][fftSize];
        this.inputAccumulator = new double[FOA_CHANNELS][fftSize];
        this.inputWritePos = 0;
        this.smoothedDiffuseness = new double[fftSize / 2 + 1];
        Arrays.fill(this.smoothedDiffuseness, 0.5);
    }

    /**
     * Returns the FFT size used for STFT analysis.
     *
     * @return the FFT size
     */
    public int getFftSize() {
        return fftSize;
    }

    /**
     * Returns the current enhancement strength.
     *
     * @return the enhancement strength in [0, 1]
     */
    public double getEnhancementStrength() {
        return enhancementStrength;
    }

    /**
     * Sets the enhancement strength.
     *
     * @param enhancementStrength value in [0, 1]
     */
    public void setEnhancementStrength(double enhancementStrength) {
        validateEnhancementStrength(enhancementStrength);
        this.enhancementStrength = enhancementStrength;
    }

    /**
     * Returns the current direct/diffuse threshold.
     *
     * @return the threshold in [0, 1]
     */
    public double getDirectDiffuseThreshold() {
        return directDiffuseThreshold;
    }

    /**
     * Sets the direct/diffuse threshold.
     *
     * @param directDiffuseThreshold value in [0, 1]
     */
    public void setDirectDiffuseThreshold(double directDiffuseThreshold) {
        validateDirectDiffuseThreshold(directDiffuseThreshold);
        this.directDiffuseThreshold = directDiffuseThreshold;
    }

    /**
     * Returns the current temporal smoothing coefficient.
     *
     * @return the smoothing coefficient in [0, 1)
     */
    public double getTemporalSmoothing() {
        return temporalSmoothing;
    }

    /**
     * Sets the temporal smoothing coefficient.
     *
     * @param temporalSmoothing value in [0, 1)
     */
    public void setTemporalSmoothing(double temporalSmoothing) {
        validateTemporalSmoothing(temporalSmoothing);
        this.temporalSmoothing = temporalSmoothing;
    }

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        // Zero the output buffer
        for (int ch = 0; ch < FOA_CHANNELS; ch++) {
            Arrays.fill(outputBuffer[ch], 0, numFrames, 0.0f);
        }

        int framesProcessed = 0;
        while (framesProcessed < numFrames) {
            int framesToCopy = Math.min(numFrames - framesProcessed, fftSize - inputWritePos);
            for (int ch = 0; ch < FOA_CHANNELS; ch++) {
                for (int i = 0; i < framesToCopy; i++) {
                    inputAccumulator[ch][inputWritePos + i] = inputBuffer[ch][framesProcessed + i];
                }
            }
            inputWritePos += framesToCopy;

            if (inputWritePos >= fftSize) {
                processStftFrame();

                // Overlap-add: write the first hopSize samples to the output
                for (int ch = 0; ch < FOA_CHANNELS; ch++) {
                    for (int i = 0; i < hopSize; i++) {
                        int outIdx = framesProcessed + framesToCopy - (fftSize - i);
                        if (outIdx >= 0 && outIdx < numFrames) {
                            outputBuffer[ch][outIdx] += (float) overlapBuffer[ch][i];
                        }
                    }
                    // Save the second half for the next overlap
                    for (int i = 0; i < hopSize; i++) {
                        overlapBuffer[ch][i] = overlapBuffer[ch][i + hopSize];
                        overlapBuffer[ch][i + hopSize] = 0.0;
                    }
                }

                // Shift input accumulator: keep the second half
                for (int ch = 0; ch < FOA_CHANNELS; ch++) {
                    System.arraycopy(inputAccumulator[ch], hopSize, inputAccumulator[ch], 0, hopSize);
                    Arrays.fill(inputAccumulator[ch], hopSize, fftSize, 0.0);
                }
                inputWritePos = hopSize;
            }

            framesProcessed += framesToCopy;
        }
    }

    /**
     * Processes one STFT frame: window, FFT, apply directional mask, IFFT,
     * window, and accumulate into the overlap buffer.
     */
    private void processStftFrame() {
        int numBins = fftSize / 2 + 1;

        // Window and forward FFT for each channel
        for (int ch = 0; ch < FOA_CHANNELS; ch++) {
            for (int i = 0; i < fftSize; i++) {
                stftReal[ch][i] = inputAccumulator[ch][i] * window[i];
                stftImag[ch][i] = 0.0;
            }
            FftUtils.fft(stftReal[ch], stftImag[ch]);
        }

        // Compute directional mask per frequency bin
        double[] mask = new double[numBins];
        for (int bin = 0; bin < numBins; bin++) {
            // W = channel 0, Y = channel 1, Z = channel 2, X = channel 3 (ACN order)
            double wR = stftReal[0][bin];
            double wI = stftImag[0][bin];
            double yR = stftReal[1][bin];
            double yI = stftImag[1][bin];
            double zR = stftReal[2][bin];
            double zI = stftImag[2][bin];
            double xR = stftReal[3][bin];
            double xI = stftImag[3][bin];

            // Active intensity vector: Re(W* · X), Re(W* · Y), Re(W* · Z)
            // where W* is the complex conjugate of W
            double ix = wR * xR + wI * xI; // Re(conj(W) * X)
            double iy = wR * yR + wI * yI; // Re(conj(W) * Y)
            double iz = wR * zR + wI * zI; // Re(conj(W) * Z)
            double intensityMag = Math.sqrt(ix * ix + iy * iy + iz * iz);

            // Energy density: |W|² + |X|² + |Y|² + |Z|²
            double energy = (wR * wR + wI * wI) + (xR * xR + xI * xI)
                    + (yR * yR + yI * yI) + (zR * zR + zI * zI);

            // Diffuseness estimate
            double diffuseness;
            if (energy > 1e-30) {
                diffuseness = 1.0 - (2.0 * intensityMag / energy);
                diffuseness = Math.max(0.0, Math.min(1.0, diffuseness));
            } else {
                diffuseness = 1.0;
            }

            // Temporal smoothing
            smoothedDiffuseness[bin] = temporalSmoothing * smoothedDiffuseness[bin]
                    + (1.0 - temporalSmoothing) * diffuseness;

            // Directional mask: boost directional bins, attenuate diffuse bins
            // directionality = 1 - smoothedDiffuseness (high when strongly directional)
            double directionality = 1.0 - smoothedDiffuseness[bin];
            double gain = 1.0 + enhancementStrength * (directionality - directDiffuseThreshold);
            mask[bin] = Math.max(0.0, Math.min(2.0, gain));
        }

        // Apply mask to all channels and inverse FFT
        for (int ch = 0; ch < FOA_CHANNELS; ch++) {
            // Apply mask to positive frequencies
            for (int bin = 0; bin < numBins; bin++) {
                stftReal[ch][bin] *= mask[bin];
                stftImag[ch][bin] *= mask[bin];
            }
            // Mirror to negative frequencies (conjugate symmetry for real signal)
            for (int bin = 1; bin < fftSize / 2; bin++) {
                stftReal[ch][fftSize - bin] = stftReal[ch][bin];
                stftImag[ch][fftSize - bin] = -stftImag[ch][bin];
            }

            FftUtils.ifft(stftReal[ch], stftImag[ch]);

            // Synthesis window and overlap-add
            for (int i = 0; i < fftSize; i++) {
                overlapBuffer[ch][i] += stftReal[ch][i] * window[i];
            }
        }
    }

    @Override
    public void reset() {
        for (int ch = 0; ch < FOA_CHANNELS; ch++) {
            Arrays.fill(overlapBuffer[ch], 0.0);
            Arrays.fill(inputAccumulator[ch], 0.0);
        }
        inputWritePos = 0;
        Arrays.fill(smoothedDiffuseness, 0.5);
    }

    @Override
    public int getInputChannelCount() {
        return FOA_CHANNELS;
    }

    @Override
    public int getOutputChannelCount() {
        return FOA_CHANNELS;
    }

    private static void validateEnhancementStrength(double value) {
        if (value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    "enhancementStrength must be in [0, 1]: " + value);
        }
    }

    private static void validateDirectDiffuseThreshold(double value) {
        if (value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    "directDiffuseThreshold must be in [0, 1]: " + value);
        }
    }

    private static void validateTemporalSmoothing(double value) {
        if (value < 0.0 || value >= 1.0) {
            throw new IllegalArgumentException(
                    "temporalSmoothing must be in [0, 1): " + value);
        }
    }
}
