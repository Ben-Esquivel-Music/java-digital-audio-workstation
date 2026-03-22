package com.benesquivelmusic.daw.core.spatial.ambisonics;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.spatial.AmbisonicOrder;
import com.benesquivelmusic.daw.sdk.spatial.DecoderType;
import com.benesquivelmusic.daw.sdk.spatial.SpatialPosition;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Decodes Ambisonic B-format signals to arbitrary speaker layouts.
 *
 * <p>Supports basic (sampling), max-rE, and in-phase decoder types for both
 * 2D and 3D speaker configurations. The decoder computes a matrix of gains
 * from the Ambisonic channels to each speaker, based on the spherical harmonic
 * coefficients of the speaker directions.</p>
 *
 * <p>The decoding matrix is pre-computed when the speaker layout or decoder
 * type changes, making the per-sample processing efficient.</p>
 */
public final class AmbisonicDecoder implements AudioProcessor {

    private final AmbisonicOrder order;
    private final int inputChannels;
    private List<SpatialPosition> speakerPositions;
    private DecoderType decoderType;
    private double[][] decoderMatrix; // [speaker][ambiChannel]

    /**
     * Creates an Ambisonic decoder for the given order and speaker layout.
     *
     * @param order            the Ambisonic order
     * @param speakerPositions the speaker positions
     * @param decoderType      the decoder weighting type
     */
    public AmbisonicDecoder(AmbisonicOrder order, List<SpatialPosition> speakerPositions,
                            DecoderType decoderType) {
        Objects.requireNonNull(order, "order must not be null");
        Objects.requireNonNull(speakerPositions, "speakerPositions must not be null");
        Objects.requireNonNull(decoderType, "decoderType must not be null");
        if (speakerPositions.isEmpty()) {
            throw new IllegalArgumentException("speakerPositions must not be empty");
        }
        this.order = order;
        this.inputChannels = order.channelCount();
        this.speakerPositions = List.copyOf(speakerPositions);
        this.decoderType = decoderType;
        this.decoderMatrix = computeDecoderMatrix();
    }

    /**
     * Returns the current decoder type.
     *
     * @return the decoder type
     */
    public DecoderType getDecoderType() {
        return decoderType;
    }

    /**
     * Sets the decoder type and recomputes the decoding matrix.
     *
     * @param decoderType the new decoder type
     */
    public void setDecoderType(DecoderType decoderType) {
        Objects.requireNonNull(decoderType, "decoderType must not be null");
        this.decoderType = decoderType;
        this.decoderMatrix = computeDecoderMatrix();
    }

    /**
     * Returns the speaker positions used by this decoder.
     *
     * @return the speaker positions
     */
    public List<SpatialPosition> getSpeakerPositions() {
        return speakerPositions;
    }

    /**
     * Sets the speaker positions and recomputes the decoding matrix.
     *
     * @param speakerPositions the new speaker positions
     */
    public void setSpeakerPositions(List<SpatialPosition> speakerPositions) {
        Objects.requireNonNull(speakerPositions, "speakerPositions must not be null");
        if (speakerPositions.isEmpty()) {
            throw new IllegalArgumentException("speakerPositions must not be empty");
        }
        this.speakerPositions = List.copyOf(speakerPositions);
        this.decoderMatrix = computeDecoderMatrix();
    }

    /**
     * Returns the pre-computed decoder matrix.
     * Each row corresponds to a speaker, each column to an Ambisonic channel.
     *
     * @return a copy of the decoder matrix {@code [speaker][ambiChannel]}
     */
    public double[][] getDecoderMatrix() {
        double[][] copy = new double[decoderMatrix.length][];
        for (int i = 0; i < decoderMatrix.length; i++) {
            copy[i] = decoderMatrix[i].clone();
        }
        return copy;
    }

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        int numSpeakers = Math.min(outputBuffer.length, decoderMatrix.length);
        int numAmbiChannels = Math.min(inputBuffer.length, inputChannels);

        for (int spk = 0; spk < numSpeakers; spk++) {
            double[] row = decoderMatrix[spk];
            for (int i = 0; i < numFrames; i++) {
                double sample = 0.0;
                for (int ch = 0; ch < numAmbiChannels; ch++) {
                    sample += row[ch] * inputBuffer[ch][i];
                }
                outputBuffer[spk][i] = (float) sample;
            }
        }

        // Zero remaining output channels
        for (int spk = numSpeakers; spk < outputBuffer.length; spk++) {
            Arrays.fill(outputBuffer[spk], 0, numFrames, 0.0f);
        }
    }

    @Override
    public void reset() {
        // Stateless — no internal buffers to clear
    }

    @Override
    public int getInputChannelCount() {
        return inputChannels;
    }

    @Override
    public int getOutputChannelCount() {
        return speakerPositions.size();
    }

    // ---- Internal ----

    private double[][] computeDecoderMatrix() {
        int numSpeakers = speakerPositions.size();
        double[][] matrix = new double[numSpeakers][inputChannels];

        double[] weights = computeOrderWeights();

        for (int spk = 0; spk < numSpeakers; spk++) {
            SpatialPosition pos = speakerPositions.get(spk);
            double azRad = Math.toRadians(pos.azimuthDegrees());
            double elRad = Math.toRadians(pos.elevationDegrees());
            double[] shCoeffs = SphericalHarmonics.encode(azRad, elRad, order.order());

            for (int ch = 0; ch < inputChannels; ch++) {
                int[] degreeOrder = SphericalHarmonics.acnToDegreeOrder(ch);
                int degree = degreeOrder[0];
                matrix[spk][ch] = shCoeffs[ch] * weights[degree] / numSpeakers;
            }
        }

        return matrix;
    }

    private double[] computeOrderWeights() {
        int maxOrder = order.order();
        double[] weights = new double[maxOrder + 1];

        switch (decoderType) {
            case BASIC -> {
                Arrays.fill(weights, 1.0);
            }
            case MAX_RE -> {
                // Max-rE weights maximize energy concentration at the look direction
                weights[0] = 1.0;
                if (maxOrder >= 1) weights[1] = maxReWeight(1, maxOrder);
                if (maxOrder >= 2) weights[2] = maxReWeight(2, maxOrder);
                if (maxOrder >= 3) weights[3] = maxReWeight(3, maxOrder);
            }
            case IN_PHASE -> {
                // In-phase weights ensure all gains are non-negative
                weights[0] = 1.0;
                for (int l = 1; l <= maxOrder; l++) {
                    weights[l] = inPhaseWeight(l, maxOrder);
                }
            }
        }

        return weights;
    }

    /**
     * Max-rE weight for degree l at Ambisonic order N.
     * Approximation: cos(l * π / (2N + 2))
     */
    private static double maxReWeight(int l, int maxOrder) {
        return Math.cos(l * Math.PI / (2.0 * maxOrder + 2.0));
    }

    /**
     * In-phase weight for degree l at Ambisonic order N.
     * Formula: N! * (N+1)! / ((N+l+1)! * (N-l)!) * (2l+1)
     * Simplified: product of (N-k+1)/(N+k+1) for k=1..l
     */
    private static double inPhaseWeight(int l, int maxOrder) {
        double weight = 1.0;
        for (int k = 1; k <= l; k++) {
            weight *= (double) (maxOrder - k + 1) / (maxOrder + k + 1);
        }
        return weight;
    }
}
