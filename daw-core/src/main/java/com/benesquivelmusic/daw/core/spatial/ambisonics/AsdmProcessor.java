package com.benesquivelmusic.daw.core.spatial.ambisonics;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

/**
 * Ambisonic Spatial Decomposition Method (ASDM) processor that separates
 * a First-Order Ambisonic (FOA) signal into salient (directional) and
 * diffuse (ambient) streams.
 *
 * <p>This technique enhances the spatial resolution of FOA signals, making
 * them perceptually comparable to higher-order encodings. The algorithm
 * estimates the active intensity vector from the B-format signal, computes
 * a diffuseness parameter, and uses it to separate the signal into two
 * complementary streams.</p>
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Compute the instantaneous active intensity vector:
 *       {@code I = [W·X, W·Y, W·Z]}</li>
 *   <li>Compute the energy density:
 *       {@code E = W² + X² + Y² + Z²}</li>
 *   <li>Compute the diffuseness parameter:
 *       {@code ψ = 1 − |I| / (E · 0.5)} (clamped to [0, 1])</li>
 *   <li>Separate: {@code salient = (1−ψ) · signal}, {@code diffuse = ψ · signal}</li>
 * </ol>
 *
 * <p>The output has 8 channels: channels 0–3 are the salient B-format stream,
 * channels 4–7 are the diffuse B-format stream.</p>
 */
public final class AsdmProcessor implements AudioProcessor {

    private static final int FOA_CHANNELS = 4;

    private double smoothingCoefficient;
    private double smoothedDiffuseness;

    /**
     * Creates an ASDM processor with the default smoothing coefficient.
     * A higher smoothing coefficient provides more stable separation at
     * the expense of reduced temporal resolution.
     */
    public AsdmProcessor() {
        this(0.95);
    }

    /**
     * Creates an ASDM processor with the specified smoothing coefficient.
     *
     * @param smoothingCoefficient temporal smoothing factor in [0, 1)
     *                             where 0 = no smoothing, higher = more smoothing
     */
    public AsdmProcessor(double smoothingCoefficient) {
        if (smoothingCoefficient < 0.0 || smoothingCoefficient >= 1.0) {
            throw new IllegalArgumentException(
                    "smoothingCoefficient must be in [0, 1): " + smoothingCoefficient);
        }
        this.smoothingCoefficient = smoothingCoefficient;
        this.smoothedDiffuseness = 0.5;
    }

    /**
     * Returns the current smoothing coefficient.
     *
     * @return the smoothing coefficient
     */
    public double getSmoothingCoefficient() {
        return smoothingCoefficient;
    }

    /**
     * Sets the smoothing coefficient.
     *
     * @param smoothingCoefficient value in [0, 1)
     */
    public void setSmoothingCoefficient(double smoothingCoefficient) {
        if (smoothingCoefficient < 0.0 || smoothingCoefficient >= 1.0) {
            throw new IllegalArgumentException(
                    "smoothingCoefficient must be in [0, 1): " + smoothingCoefficient);
        }
        this.smoothingCoefficient = smoothingCoefficient;
    }

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        float[] w = inputBuffer[0]; // W (omnidirectional)
        float[] y = inputBuffer[1]; // Y
        float[] z = inputBuffer[2]; // Z
        float[] x = inputBuffer[3]; // X

        for (int i = 0; i < numFrames; i++) {
            double wVal = w[i];
            double xVal = x[i];
            double yVal = y[i];
            double zVal = z[i];

            // Active intensity vector components
            double ix = wVal * xVal;
            double iy = wVal * yVal;
            double iz = wVal * zVal;
            double intensityMagnitude = Math.sqrt(ix * ix + iy * iy + iz * iz);

            // Energy density
            double energy = wVal * wVal + xVal * xVal + yVal * yVal + zVal * zVal;

            // Diffuseness estimate (0 = fully directional, 1 = fully diffuse)
            double diffuseness;
            if (energy > 1e-15) {
                diffuseness = 1.0 - (2.0 * intensityMagnitude / energy);
                diffuseness = Math.max(0.0, Math.min(1.0, diffuseness));
            } else {
                diffuseness = 1.0; // silence is considered diffuse
            }

            // Temporal smoothing
            smoothedDiffuseness = smoothingCoefficient * smoothedDiffuseness
                    + (1.0 - smoothingCoefficient) * diffuseness;

            float salientGain = (float) (1.0 - smoothedDiffuseness);
            float diffuseGain = (float) smoothedDiffuseness;

            // Salient stream (channels 0–3)
            outputBuffer[0][i] = w[i] * salientGain;
            outputBuffer[1][i] = y[i] * salientGain;
            outputBuffer[2][i] = z[i] * salientGain;
            outputBuffer[3][i] = x[i] * salientGain;

            // Diffuse stream (channels 4–7)
            outputBuffer[4][i] = w[i] * diffuseGain;
            outputBuffer[5][i] = y[i] * diffuseGain;
            outputBuffer[6][i] = z[i] * diffuseGain;
            outputBuffer[7][i] = x[i] * diffuseGain;
        }
    }

    @Override
    public void reset() {
        smoothedDiffuseness = 0.5;
    }

    @Override
    public int getInputChannelCount() {
        return FOA_CHANNELS;
    }

    @Override
    public int getOutputChannelCount() {
        return FOA_CHANNELS * 2; // salient (4) + diffuse (4)
    }
}
