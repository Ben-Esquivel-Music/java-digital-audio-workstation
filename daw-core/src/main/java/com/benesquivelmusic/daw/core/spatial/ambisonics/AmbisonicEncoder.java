package com.benesquivelmusic.daw.core.spatial.ambisonics;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.spatial.AmbisonicOrder;

import java.util.Objects;

/**
 * Encodes a mono audio source into Ambisonic B-format at the specified order.
 *
 * <p>Supports First-Order Ambisonics (FOA, 4 channels) through Third-Order
 * Ambisonics (HOA, 16 channels). The encoder computes real spherical harmonic
 * coefficients using ACN ordering and SN3D normalization (AmbiX format),
 * then applies them as gain factors to the mono input signal.</p>
 *
 * <p>The source direction is set via {@link #setDirection(double, double)}
 * using azimuth and elevation in radians. The direction can be updated
 * between processing calls to support automation.</p>
 */
public final class AmbisonicEncoder implements AudioProcessor {

    private final AmbisonicOrder order;
    private final int outputChannels;

    private double azimuthRadians;
    private double elevationRadians;
    private double[] coefficients;

    /**
     * Creates an Ambisonic encoder for the given order.
     *
     * @param order the Ambisonic order (FIRST, SECOND, or THIRD)
     */
    public AmbisonicEncoder(AmbisonicOrder order) {
        Objects.requireNonNull(order, "order must not be null");
        this.order = order;
        this.outputChannels = order.channelCount();
        this.azimuthRadians = 0.0;
        this.elevationRadians = 0.0;
        this.coefficients = SphericalHarmonics.encode(0.0, 0.0, order.order());
    }

    /**
     * Sets the source direction for encoding.
     *
     * @param azimuthRadians   the azimuth angle in radians (0 = front, π/2 = left)
     * @param elevationRadians the elevation angle in radians (0 = horizontal, π/2 = above)
     */
    public void setDirection(double azimuthRadians, double elevationRadians) {
        this.azimuthRadians = azimuthRadians;
        this.elevationRadians = elevationRadians;
        this.coefficients = SphericalHarmonics.encode(azimuthRadians, elevationRadians, order.order());
    }

    /**
     * Returns the current azimuth in radians.
     *
     * @return the azimuth
     */
    public double getAzimuthRadians() {
        return azimuthRadians;
    }

    /**
     * Returns the current elevation in radians.
     *
     * @return the elevation
     */
    public double getElevationRadians() {
        return elevationRadians;
    }

    /**
     * Returns the Ambisonic order of this encoder.
     *
     * @return the order
     */
    public AmbisonicOrder getOrder() {
        return order;
    }

    /**
     * Returns the current spherical harmonic coefficients.
     *
     * @return a copy of the coefficient array
     */
    public double[] getCoefficients() {
        return coefficients.clone();
    }

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        // Downmix input to mono
        int inputChannels = inputBuffer.length;
        int outChannels = Math.min(outputBuffer.length, outputChannels);

        for (int ch = 0; ch < outChannels; ch++) {
            float gain = (float) coefficients[ch];
            for (int i = 0; i < numFrames; i++) {
                float mono = 0;
                for (int inCh = 0; inCh < inputChannels; inCh++) {
                    mono += inputBuffer[inCh][i];
                }
                if (inputChannels > 1) {
                    mono /= inputChannels;
                }
                outputBuffer[ch][i] = mono * gain;
            }
        }

        // Zero remaining output channels
        for (int ch = outChannels; ch < outputBuffer.length; ch++) {
            java.util.Arrays.fill(outputBuffer[ch], 0, numFrames, 0.0f);
        }
    }

    @Override
    public void reset() {
        // Stateless — no internal buffers to clear
    }

    @Override
    public int getInputChannelCount() {
        return 1;
    }

    @Override
    public int getOutputChannelCount() {
        return outputChannels;
    }
}
