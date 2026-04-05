package com.benesquivelmusic.daw.core.spatial.ambisonics;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

/**
 * Converts A-format (raw tetrahedral microphone) recordings to B-format
 * (Ambisonic First-Order).
 *
 * <p>A-format is the raw output from a tetrahedral Ambisonic microphone such
 * as the Sennheiser AMBEO, Zoom H3-VR, or Rode NT-SF1. The four capsules
 * are arranged at the vertices of a regular tetrahedron:</p>
 * <ul>
 *   <li>FLU — Front-Left-Up</li>
 *   <li>FRD — Front-Right-Down</li>
 *   <li>BLD — Back-Left-Down</li>
 *   <li>BRU — Back-Right-Up</li>
 * </ul>
 *
 * <p>The conversion to B-format (W, Y, Z, X) uses the standard matrix:</p>
 * <pre>
 *   W = 0.5 * (FLU + FRD + BLD + BRU)
 *   X = 0.5 * (FLU + FRD - BLD - BRU)
 *   Y = 0.5 * (FLU - FRD + BLD - BRU)
 *   Z = 0.5 * (FLU - FRD - BLD + BRU)
 * </pre>
 *
 * <p>The output is in ACN/SN3D (AmbiX) order: W (ch 0), Y (ch 1), Z (ch 2), X (ch 3).</p>
 */
public final class AFormatConverter implements AudioProcessor {

    /** A-format channel index: Front-Left-Up. */
    public static final int FLU = 0;
    /** A-format channel index: Front-Right-Down. */
    public static final int FRD = 1;
    /** A-format channel index: Back-Left-Down. */
    public static final int BLD = 2;
    /** A-format channel index: Back-Right-Up. */
    public static final int BRU = 3;

    private static final float SCALE = 0.5f;

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        float[] flu = inputBuffer[FLU];
        float[] frd = inputBuffer[FRD];
        float[] bld = inputBuffer[BLD];
        float[] bru = inputBuffer[BRU];

        // ACN order: W=0, Y=1, Z=2, X=3
        float[] w = outputBuffer[0];
        float[] y = outputBuffer[1];
        float[] z = outputBuffer[2];
        float[] x = outputBuffer[3];

        for (int i = 0; i < numFrames; i++) {
            float f = flu[i];
            float r = frd[i];
            float b = bld[i];
            float u = bru[i];

            w[i] = SCALE * (f + r + b + u);
            y[i] = SCALE * (f - r + b - u);
            z[i] = SCALE * (f - r - b + u);
            x[i] = SCALE * (f + r - b - u);
        }
    }

    @Override
    public void reset() {
        // Stateless — no internal buffers
    }

    @Override
    public int getInputChannelCount() {
        return 4; // A-format: FLU, FRD, BLD, BRU
    }

    @Override
    public int getOutputChannelCount() {
        return 4; // B-format FOA: W, Y, Z, X
    }
}
