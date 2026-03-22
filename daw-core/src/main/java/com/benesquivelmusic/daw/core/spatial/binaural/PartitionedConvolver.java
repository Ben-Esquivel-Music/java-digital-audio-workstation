package com.benesquivelmusic.daw.core.spatial.binaural;

import java.util.Arrays;

/**
 * Partitioned convolution engine using the overlap-save method with FFT.
 *
 * <p>Partitions a potentially long impulse response into block-sized segments,
 * transforms each to the frequency domain, and maintains a frequency-domain
 * delay line (FDL) for efficient real-time convolution. This gives
 * O(B log B) complexity per block instead of O(B × L) for direct convolution,
 * where B is the block size and L is the IR length.</p>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
public final class PartitionedConvolver {

    private final int blockSize;
    private final int fftSize;
    private final int numPartitions;

    // Pre-computed IR partitions in frequency domain
    private final double[][] irPartitionsReal;
    private final double[][] irPartitionsImag;

    // Frequency-domain delay line (circular buffer of input FFTs)
    private final double[][] fdlReal;
    private final double[][] fdlImag;
    private int fdlIndex;

    // Previous input block for overlap
    private final float[] prevInput;

    // Pre-allocated workspace buffers (avoid allocation in processBlock)
    private final double[] workInputReal;
    private final double[] workInputImag;
    private final double[] workSumReal;
    private final double[] workSumImag;

    /**
     * Creates a partitioned convolver for the given impulse response.
     *
     * @param impulseResponse the impulse response to convolve with
     * @param blockSize       the processing block size (must be a power of 2)
     */
    public PartitionedConvolver(float[] impulseResponse, int blockSize) {
        if (impulseResponse == null || impulseResponse.length == 0) {
            throw new IllegalArgumentException("impulseResponse must not be null or empty");
        }
        if (blockSize <= 0 || (blockSize & (blockSize - 1)) != 0) {
            throw new IllegalArgumentException("blockSize must be a positive power of 2: " + blockSize);
        }

        this.blockSize = blockSize;
        this.fftSize = blockSize * 2;
        this.numPartitions = (impulseResponse.length + blockSize - 1) / blockSize;

        // Partition and FFT the impulse response
        irPartitionsReal = new double[numPartitions][fftSize];
        irPartitionsImag = new double[numPartitions][fftSize];
        for (int p = 0; p < numPartitions; p++) {
            int offset = p * blockSize;
            int len = Math.min(blockSize, impulseResponse.length - offset);
            for (int i = 0; i < len; i++) {
                irPartitionsReal[p][i] = impulseResponse[offset + i];
            }
            fft(irPartitionsReal[p], irPartitionsImag[p], false);
        }

        // Initialize frequency-domain delay line
        fdlReal = new double[numPartitions][fftSize];
        fdlImag = new double[numPartitions][fftSize];
        fdlIndex = 0;
        prevInput = new float[blockSize];

        // Pre-allocate workspace buffers
        workInputReal = new double[fftSize];
        workInputImag = new double[fftSize];
        workSumReal = new double[fftSize];
        workSumImag = new double[fftSize];
    }

    /**
     * Processes one block of input samples through the convolver.
     *
     * <p>Exactly {@code blockSize} samples are read from input and written
     * to output.</p>
     *
     * @param input        the input buffer
     * @param output       the output buffer
     * @param inputOffset  start offset in the input buffer
     * @param outputOffset start offset in the output buffer
     */
    public void processBlock(float[] input, float[] output, int inputOffset, int outputOffset) {
        // Form overlap-save input: [prevInput | currentInput]
        Arrays.fill(workInputImag, 0.0);
        for (int i = 0; i < blockSize; i++) {
            workInputReal[i] = prevInput[i];
            workInputReal[i + blockSize] = input[inputOffset + i];
        }

        // Save current input as previous for next block
        System.arraycopy(input, inputOffset, prevInput, 0, blockSize);

        // FFT the input
        fft(workInputReal, workInputImag, false);

        // Store in FDL
        System.arraycopy(workInputReal, 0, fdlReal[fdlIndex], 0, fftSize);
        System.arraycopy(workInputImag, 0, fdlImag[fdlIndex], 0, fftSize);

        // Accumulate: multiply each FDL entry with corresponding IR partition
        Arrays.fill(workSumReal, 0.0);
        Arrays.fill(workSumImag, 0.0);
        for (int p = 0; p < numPartitions; p++) {
            int fdlIdx = ((fdlIndex - p) % numPartitions + numPartitions) % numPartitions;
            double[] xr = fdlReal[fdlIdx];
            double[] xi = fdlImag[fdlIdx];
            double[] hr = irPartitionsReal[p];
            double[] hi = irPartitionsImag[p];

            for (int i = 0; i < fftSize; i++) {
                workSumReal[i] += xr[i] * hr[i] - xi[i] * hi[i];
                workSumImag[i] += xr[i] * hi[i] + xi[i] * hr[i];
            }
        }

        // IFFT
        fft(workSumReal, workSumImag, true);

        // Output last blockSize samples (overlap-save)
        for (int i = 0; i < blockSize; i++) {
            output[outputOffset + i] = (float) workSumReal[i + blockSize];
        }

        // Advance FDL index
        fdlIndex = (fdlIndex + 1) % numPartitions;
    }

    /** Returns the block size this convolver operates on. */
    public int getBlockSize() {
        return blockSize;
    }

    /** Resets all internal state (delay line, previous input). */
    public void reset() {
        for (int p = 0; p < numPartitions; p++) {
            Arrays.fill(fdlReal[p], 0.0);
            Arrays.fill(fdlImag[p], 0.0);
        }
        Arrays.fill(prevInput, 0.0f);
        fdlIndex = 0;
    }

    // ---- Radix-2 Cooley–Tukey FFT ------------------------------------------

    static void fft(double[] real, double[] imag, boolean inverse) {
        int n = real.length;
        if (n <= 1) return;

        // Bit-reversal permutation
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            while ((j & bit) != 0) {
                j ^= bit;
                bit >>= 1;
            }
            j ^= bit;
            if (i < j) {
                double tmp = real[i]; real[i] = real[j]; real[j] = tmp;
                tmp = imag[i]; imag[i] = imag[j]; imag[j] = tmp;
            }
        }

        // Butterfly stages
        for (int len = 2; len <= n; len <<= 1) {
            double angle = (inverse ? 1 : -1) * 2.0 * Math.PI / len;
            double wReal = Math.cos(angle);
            double wImag = Math.sin(angle);

            for (int i = 0; i < n; i += len) {
                double curR = 1.0, curI = 0.0;
                int half = len >> 1;
                for (int j = 0; j < half; j++) {
                    int a = i + j;
                    int b = i + j + half;
                    double ur = real[a], ui = imag[a];
                    double vr = real[b] * curR - imag[b] * curI;
                    double vi = real[b] * curI + imag[b] * curR;
                    real[a] = ur + vr;
                    imag[a] = ui + vi;
                    real[b] = ur - vr;
                    imag[b] = ui - vi;
                    double newCurR = curR * wReal - curI * wImag;
                    curI = curR * wImag + curI * wReal;
                    curR = newCurR;
                }
            }
        }

        if (inverse) {
            for (int i = 0; i < n; i++) {
                real[i] /= n;
                imag[i] /= n;
            }
        }
    }
}
