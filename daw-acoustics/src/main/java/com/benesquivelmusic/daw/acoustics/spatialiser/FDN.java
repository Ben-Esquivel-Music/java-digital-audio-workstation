package com.benesquivelmusic.daw.acoustics.spatialiser;

import com.benesquivelmusic.daw.acoustics.common.Coefficients;
import com.benesquivelmusic.daw.acoustics.common.Definitions;
import com.benesquivelmusic.daw.acoustics.common.Matrix;
import com.benesquivelmusic.daw.acoustics.common.Rowvec;
import com.benesquivelmusic.daw.acoustics.dsp.Buffer;
import com.benesquivelmusic.daw.acoustics.dsp.GraphicEQ;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Feedback Delay Network for late reverberation.
 * Ported from RoomAcoustiCpp {@code FDN, FDNChannel, HouseHolderFDN, RandomOrthogonalFDN}.
 */
public class FDN {

    /** A single delay line channel within the FDN. */
    public static final class FDNChannel {
        private final double mT;
        private final Buffer buffer;
        private int idx;
        private final GraphicEQ absorptionFilter;
        private final GraphicEQ reflectionFilter;
        private final AtomicBoolean clearBuffers = new AtomicBoolean(false);

        public FDNChannel(int delayLength, Coefficients T60, Config config) {
            this.mT = (double) delayLength / config.fs;
            this.buffer = new Buffer(delayLength);
            this.absorptionFilter = new GraphicEQ(calculateFilterGains(T60), config.frequencyBands, config.Q, config.fs);
            this.reflectionFilter = new GraphicEQ(config.frequencyBands, config.Q, config.fs);
        }

        public void setTargetT60(Coefficients T60) {
            absorptionFilter.setTargetGains(calculateFilterGains(T60));
        }

        public boolean setTargetReflectionFilter(Coefficients gains) {
            return reflectionFilter.setTargetGains(gains);
        }

        public void reset() {
            clearBuffers.set(true);
            absorptionFilter.clearBuffers();
            reflectionFilter.clearBuffers();
        }

        public void processOutput(Buffer data, Buffer outBuffer, double lerpFactor) {
            reflectionFilter.processAudio(data, outBuffer, lerpFactor);
        }

        public double getOutput(double input, double lerpFactor) {
            if (clearBuffers.compareAndSet(true, false)) buffer.reset();

            int len = buffer.length();
            double output = buffer.get(idx);
            output = absorptionFilter.getOutput(output, lerpFactor);
            buffer.set(idx, input);
            idx = (idx + 1) % len;
            return output;
        }

        private Coefficients calculateFilterGains(Coefficients T60) {
            // 20 * log10(H(f)) = -60 * t / t60(f) => H(f) = 10^(-3 * t / t60(f))
            Coefficients gains = new Coefficients(T60.length());
            for (int i = 0; i < T60.length(); i++)
                gains.set(i, Definitions.pow10(-3.0 * mT / T60.get(i)));
            return gains;
        }
    }

    protected Rowvec x;
    protected Rowvec y;
    private final Matrix feedbackMatrix;
    private final List<FDNChannel> channels;
    private final AtomicBoolean clearBuffers = new AtomicBoolean(false);

    public FDN(Coefficients T60, double[] dimensions, Config config) {
        this(T60, dimensions, config, initIdentityMatrix(config.numReverbSources));
    }

    protected FDN(Coefficients T60, double[] dimensions, Config config, Matrix matrix) {
        this.feedbackMatrix = matrix;
        int numChannels = config.numReverbSources;
        channels = new ArrayList<>(numChannels);

        int[] delays = calculateTimeDelay(dimensions, numChannels, config.fs);
        for (int i = 0; i < numChannels; i++)
            channels.add(new FDNChannel(delays[i], T60, config));

        x = new Rowvec(numChannels);
        y = new Rowvec(numChannels);
    }

    public void setTargetT60(Coefficients T60) {
        for (FDNChannel ch : channels) ch.setTargetT60(T60);
    }

    public boolean setTargetReflectionFilters(List<Coefficients> gains) {
        boolean isZero = true;
        for (int i = 0; i < channels.size(); i++)
            isZero = channels.get(i).setTargetReflectionFilter(gains.get(i)) && isZero;
        return isZero;
    }

    public void processAudio(Matrix data, List<Buffer> outputBuffers, double lerpFactor) {
        if (clearBuffers.compareAndSet(true, false)) {
            for (int i = 0; i < y.cols(); i++) { x.set(i, 0); y.set(i, 0); }
        }

        int numFrames = data.cols();
        int numChannels = channels.size();

        Buffer[] channelOutputs = new Buffer[numChannels];
        for (int i = 0; i < numChannels; i++) channelOutputs[i] = new Buffer(numFrames);

        for (int n = 0; n < numFrames; n++) {
            processMatrix();

            for (int i = 0; i < numChannels; i++) {
                double input = x.get(i) + data.get(i, n);
                double output = channels.get(i).getOutput(input, lerpFactor);
                y.set(i, output);
                channelOutputs[i].set(n, output);
            }
        }

        for (int i = 0; i < numChannels; i++)
            channels.get(i).processOutput(channelOutputs[i], outputBuffers.get(i), lerpFactor);
    }

    public void reset() {
        clearBuffers.set(true);
        for (FDNChannel ch : channels) ch.reset();
    }

    protected void processMatrix() { processSquare(); }

    protected void processSquare() {
        int n = y.cols();
        for (int i = 0; i < n; i++) {
            double sum = 0;
            for (int j = 0; j < n; j++) sum += feedbackMatrix.get(i, j) * y.get(j);
            x.set(i, sum);
        }
    }

    private static Matrix initIdentityMatrix(int n) {
        Matrix m = new Matrix(n, n);
        for (int i = 0; i < n; i++) m.set(i, i, 1.0);
        return m;
    }

    private int[] calculateTimeDelay(double[] dimensions, int numChannels, int fs) {
        // Distribute delay lines based on room dimensions
        int[] delays = new int[numChannels];
        double maxDim = 0;
        for (double d : dimensions) maxDim = Math.max(maxDim, d);
        if (maxDim == 0) maxDim = 5.0;

        for (int i = 0; i < numChannels; i++) {
            double dimFactor = dimensions.length > 0 ? dimensions[i % dimensions.length] : maxDim;
            double baseDelay = dimFactor / Definitions.SPEED_OF_SOUND * fs;
            delays[i] = Math.max(1, (int) (baseDelay * (1.0 + 0.1 * i)));
        }
        makeSetMutuallyPrime(delays);
        return delays;
    }

    private static void makeSetMutuallyPrime(int[] numbers) {
        for (int i = 1; i < numbers.length; i++) {
            while (!isEntryMutuallyPrime(numbers, i)) numbers[i]++;
        }
    }

    private static boolean isEntryMutuallyPrime(int[] numbers, int idx) {
        for (int i = 0; i < numbers.length; i++) {
            if (i == idx) continue;
            if (gcd(numbers[i], numbers[idx]) != 1) return false;
        }
        return true;
    }

    private static int gcd(int a, int b) { return b == 0 ? a : gcd(b, a % b); }

    // Householder subclass
    public static final class HouseholderFDN extends FDN {
        private final double householderFactor;

        public HouseholderFDN(Coefficients T60, double[] dimensions, Config config) {
            super(T60, dimensions, config, new Matrix(0, 0));
            this.householderFactor = 2.0 / (double) config.numReverbSources;
        }

        @Override
        protected void processMatrix() {
            double sum = y.sum();
            double entry = householderFactor * sum;
            for (int i = 0; i < y.cols(); i++)
                x.set(i, entry - y.get(i));
        }
    }

    // Random orthogonal subclass
    public static final class RandomOrthogonalFDN extends FDN {
        public RandomOrthogonalFDN(Coefficients T60, double[] dimensions, Config config) {
            super(T60, dimensions, config, initRandomOrthogonalMatrix(config.numReverbSources));
        }

        private static Matrix initRandomOrthogonalMatrix(int n) {
            // Gram-Schmidt orthogonalization of random matrix
            Matrix m = new Matrix(n, n);
            m.randomUniformDistribution(-1.0, 1.0);

            // QR decomposition (modified Gram-Schmidt)
            double[][] q = new double[n][n];
            for (int j = 0; j < n; j++) {
                double[] v = m.getColumn(j);
                for (int i = 0; i < j; i++) {
                    double dot = 0;
                    for (int k = 0; k < n; k++) dot += q[k][i] * v[k];
                    for (int k = 0; k < n; k++) v[k] -= dot * q[k][i];
                }
                double norm = 0;
                for (double vk : v) norm += vk * vk;
                norm = Math.sqrt(norm);
                for (int k = 0; k < n; k++) q[k][j] = v[k] / norm;
            }
            return new Matrix(q);
        }
    }
}
