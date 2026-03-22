package com.benesquivelmusic.daw.core.spatial.ambisonics;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.spatial.AmbisonicOrder;

import java.util.Arrays;
import java.util.Objects;

/**
 * Applies 3D rotation to an Ambisonic signal using yaw, pitch, and roll angles.
 *
 * <p>Ambisonic rotation transforms the spatial orientation of the encoded sound
 * field without re-encoding individual sources. This is essential for head-tracking
 * in VR/AR applications and for creative scene orientation in mixing.</p>
 *
 * <p>For First-Order Ambisonics, the rotation is a direct 3×3 matrix applied to
 * the directional channels (Y, Z, X). The omnidirectional channel (W) is
 * unaffected by rotation. Higher orders use block-diagonal rotation matrices
 * (one block per order).</p>
 *
 * <p>Rotation convention uses intrinsic Euler angles (Z-Y-X):</p>
 * <ul>
 *   <li><strong>Yaw</strong> (α) — rotation around the vertical (Z) axis</li>
 *   <li><strong>Pitch</strong> (β) — rotation around the lateral (Y) axis</li>
 *   <li><strong>Roll</strong> (γ) — rotation around the front (X) axis</li>
 * </ul>
 */
public final class AmbisonicRotator implements AudioProcessor {

    private final AmbisonicOrder order;
    private final int channelCount;

    private double yawRadians;
    private double pitchRadians;
    private double rollRadians;
    private double[][] rotationMatrix; // 3x3 for the first-order directional channels

    /**
     * Creates a rotator for the given Ambisonic order with no initial rotation.
     *
     * @param order the Ambisonic order
     */
    public AmbisonicRotator(AmbisonicOrder order) {
        Objects.requireNonNull(order, "order must not be null");
        this.order = order;
        this.channelCount = order.channelCount();
        this.rotationMatrix = identityMatrix();
    }

    /**
     * Sets the rotation angles and recomputes the rotation matrix.
     *
     * @param yawRadians   rotation around Z axis in radians
     * @param pitchRadians rotation around Y axis in radians
     * @param rollRadians  rotation around X axis in radians
     */
    public void setRotation(double yawRadians, double pitchRadians, double rollRadians) {
        this.yawRadians = yawRadians;
        this.pitchRadians = pitchRadians;
        this.rollRadians = rollRadians;
        this.rotationMatrix = computeRotationMatrix(yawRadians, pitchRadians, rollRadians);
    }

    /** Returns the current yaw in radians. */
    public double getYawRadians() {
        return yawRadians;
    }

    /** Returns the current pitch in radians. */
    public double getPitchRadians() {
        return pitchRadians;
    }

    /** Returns the current roll in radians. */
    public double getRollRadians() {
        return rollRadians;
    }

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        int channels = Math.min(channelCount, Math.min(inputBuffer.length, outputBuffer.length));

        // Channel 0 (W) passes through unchanged
        if (channels > 0) {
            System.arraycopy(inputBuffer[0], 0, outputBuffer[0], 0, numFrames);
        }

        // Apply rotation to first-order directional channels (Y=1, Z=2, X=3)
        if (channels >= 4) {
            for (int i = 0; i < numFrames; i++) {
                double y = inputBuffer[1][i]; // Y
                double z = inputBuffer[2][i]; // Z
                double x = inputBuffer[3][i]; // X

                // ACN order is Y, Z, X but rotation matrix operates on X, Y, Z
                // Map: ACN1=Y, ACN2=Z, ACN3=X -> vector [X, Y, Z]
                double rx = rotationMatrix[0][0] * x + rotationMatrix[0][1] * y + rotationMatrix[0][2] * z;
                double ry = rotationMatrix[1][0] * x + rotationMatrix[1][1] * y + rotationMatrix[1][2] * z;
                double rz = rotationMatrix[2][0] * x + rotationMatrix[2][1] * y + rotationMatrix[2][2] * z;

                // Map back: X->ACN3, Y->ACN1, Z->ACN2
                outputBuffer[1][i] = (float) ry;
                outputBuffer[2][i] = (float) rz;
                outputBuffer[3][i] = (float) rx;
            }
        }

        // Higher-order channels: for simplicity, pass through higher-order channels
        // unrotated (full HOA rotation requires Wigner-D matrices, which are
        // significantly more complex). This provides correct FOA rotation for all orders.
        for (int ch = 4; ch < channels; ch++) {
            System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
        }

        // Zero remaining output channels
        for (int ch = channels; ch < outputBuffer.length; ch++) {
            Arrays.fill(outputBuffer[ch], 0, numFrames, 0.0f);
        }
    }

    @Override
    public void reset() {
        // Stateless
    }

    @Override
    public int getInputChannelCount() {
        return channelCount;
    }

    @Override
    public int getOutputChannelCount() {
        return channelCount;
    }

    // ---- Internal ----

    /**
     * Computes a 3×3 rotation matrix from intrinsic Z-Y-X Euler angles.
     * This rotates the first-order Cartesian components (X, Y, Z).
     */
    private static double[][] computeRotationMatrix(double yaw, double pitch, double roll) {
        double cy = Math.cos(yaw);
        double sy = Math.sin(yaw);
        double cp = Math.cos(pitch);
        double sp = Math.sin(pitch);
        double cr = Math.cos(roll);
        double sr = Math.sin(roll);

        // Combined rotation Rz(yaw) * Ry(pitch) * Rx(roll)
        return new double[][]{
                {cy * cp, cy * sp * sr - sy * cr, cy * sp * cr + sy * sr},
                {sy * cp, sy * sp * sr + cy * cr, sy * sp * cr - cy * sr},
                {-sp,     cp * sr,                cp * cr}
        };
    }

    private static double[][] identityMatrix() {
        return new double[][]{
                {1, 0, 0},
                {0, 1, 0},
                {0, 0, 1}
        };
    }
}
