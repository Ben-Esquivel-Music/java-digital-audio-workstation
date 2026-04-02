package com.benesquivelmusic.daw.acoustics.dsp;

import com.benesquivelmusic.daw.acoustics.common.*;
import com.benesquivelmusic.daw.acoustics.common.Definitions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Graphic equaliser. Ported from RoomAcoustiCpp {@code GraphicEQ}.
 *
 * @see <a href="https://webaudio.github.io/Audio-EQ-Cookbook/Audio-EQ-Cookbook.txt">Audio EQ Cookbook</a>
 */
public final class GraphicEQ {

    private final int numFilters;
    private Coefficients previousInput;

    private final PeakLowShelf lowShelf;
    private final List<PeakingFilter> peakingFilters;
    private final PeakHighShelf highShelf;

    private Matrix filterResponseMatrix;

    private final AtomicReference<Double> targetGain;
    private double currentGain;

    private final AtomicBoolean initialised = new AtomicBoolean(false);
    private final AtomicBoolean gainsEqual = new AtomicBoolean(false);

    public GraphicEQ(Coefficients fc, double Q, int sampleRate) {
        this(new Coefficients(fc.length(), 0.0), fc, Q, sampleRate);
    }

    public GraphicEQ(Coefficients gain, Coefficients fc, double Q, int sampleRate) {
        numFilters = gain.length() + 2;
        previousInput = new Coefficients(gain);

        Coefficients f = createFrequencyVector(fc);

        initMatrix(f, Q, sampleRate);

        var result = calculateGains(gain);
        double[] filterGains = result.filterGains;

        // Increasing the low shelf frequency by SQRT_2 creates a smoother response at low frequencies
        lowShelf = new PeakLowShelf(f.get(0) * Definitions.SQRT_2, filterGains[0], Q, sampleRate);
        peakingFilters = new ArrayList<>();
        for (int i = 1; i < numFilters - 1; i++)
            peakingFilters.add(new PeakingFilter(f.get(i), filterGains[i], Q, sampleRate));
        // Increasing the high shelf frequency by SQRT_2 creates a smoother response at high frequencies
        highShelf = new PeakHighShelf(Math.min(f.get(numFilters - 2) * Definitions.SQRT_2, 20000.0), filterGains[numFilters - 1], Q, sampleRate);

        targetGain = new AtomicReference<>(result.dcGain);
        currentGain = result.dcGain;

        gainsEqual.set(true);
        initialised.set(true);
    }

    public boolean setTargetGains(Coefficients gains) {
        if (Interpolation.equals(gains, previousInput)) {
            if (gainsEqual.get() && gains.allLessOrEqual(0.0))
                return true;
            return false;
        }
        previousInput = new Coefficients(gains);

        var result = calculateGains(gains);
        double[] filterGains = result.filterGains;

        targetGain.set(result.dcGain);
        gainsEqual.set(false);
        lowShelf.setTargetGain(filterGains[0]);
        for (int i = 0; i < peakingFilters.size(); i++)
            peakingFilters.get(i).setTargetGain(filterGains[i + 1]);
        highShelf.setTargetGain(filterGains[numFilters - 1]);
        return gains.allLessOrEqual(0.0);
    }

    public double getOutput(double input, double lerpFactor) {
        if (!initialised.get()) return 0.0;

        if (!gainsEqual.get()) interpolateGain(lerpFactor);

        if (numFilters == 3) // Only one peaking filter: single band EQ is just a gain
            return input * currentGain;

        double out = lowShelf.getOutput(input, lerpFactor);
        for (PeakingFilter f : peakingFilters)
            out = f.getOutput(out, lerpFactor);
        out = highShelf.getOutput(out, lerpFactor);
        out *= currentGain;
        return out;
    }

    public void processAudio(Buffer inBuffer, Buffer outBuffer, double lerpFactor) {
        if (!initialised.get()) {
            outBuffer.reset();
            return;
        }
        if (currentGain == 0.0 && gainsEqual.get()) {
            outBuffer.reset();
            return;
        }
        for (int i = 0; i < inBuffer.length(); i++)
            outBuffer.set(i, getOutput(inBuffer.get(i), lerpFactor));
    }

    public void clearBuffers() {
        lowShelf.clearBuffers();
        for (PeakingFilter f : peakingFilters) f.clearBuffers();
        highShelf.clearBuffers();
    }

    private void interpolateGain(double lerpFactor) {
        gainsEqual.set(true);
        double tgt = targetGain.get();
        currentGain = Interpolation.lerp(currentGain, tgt, lerpFactor);
        if (Interpolation.equals(currentGain, tgt)) currentGain = tgt;
        else gainsEqual.set(false);
    }

    private void initMatrix(Coefficients fc, double Q, double fs) {
        double pdb = 6.0;
        double p = Math.pow(10.0, pdb / 20.0);

        filterResponseMatrix = new Matrix(numFilters, numFilters);

        // Low shelf response
        PeakLowShelf tempLowShelf = new PeakLowShelf(fc.get(0) * Definitions.SQRT_2, p, Q, (int) fs);
        Coefficients out = tempLowShelf.getFrequencyResponse(fc);
        for (int i = 0; i < numFilters; i++)
            filterResponseMatrix.set(0, i, out.get(i));

        // Peaking filter responses
        for (int j = 1; j < numFilters - 1; j++) {
            PeakingFilter tempPeakingFilter = new PeakingFilter(fc.get(j), p, Q, (int) fs);
            out = tempPeakingFilter.getFrequencyResponse(fc);
            for (int i = 0; i < out.length(); i++)
                filterResponseMatrix.set(j, i, out.get(i));
        }

        // High shelf response
        PeakHighShelf tempHighShelf = new PeakHighShelf(
                Math.min(fc.get(numFilters - 2) * Definitions.SQRT_2, 20000.0), p, Q, (int) fs);
        out = tempHighShelf.getFrequencyResponse(fc);
        for (int i = 0; i < out.length(); i++)
            filterResponseMatrix.set(numFilters - 1, i, out.get(i));

        filterResponseMatrix.inverse();
        filterResponseMatrix.mulLocal(pdb);
    }

    private record GainResult(double[] filterGains, double dcGain) {}

    private GainResult calculateGains(Coefficients gains) {
        double[] inputGains = new double[numFilters];
        java.util.Arrays.fill(inputGains, 1.0);

        if (gains.allLessOrEqual(0.0))
            return new GainResult(inputGains, 0.0);

        if (gains.length() == 1) {
            inputGains[0] = gains.get(0);
            inputGains[1] = gains.get(0);
            inputGains[2] = gains.get(0);
        } else {
            inputGains[0] = (gains.get(0) + gains.get(1)) / 2.0; // Low shelf gain
            for (int i = 1; i < numFilters - 1; i++)
                inputGains[i] = gains.get(i - 1); // Peaking filter gains
            inputGains[numFilters - 1] = (gains.get(numFilters - 4) + gains.get(numFilters - 3)) / 2.0; // High shelf gain
        }

        // Clamp to EPS, take log10
        for (int i = 0; i < numFilters; i++)
            inputGains[i] = Math.max(inputGains[i], Definitions.EPS);
        for (int i = 0; i < numFilters; i++)
            inputGains[i] = Definitions.log10(inputGains[i]);

        // Remove average filter gain
        double meandBGain = 0;
        for (double g : inputGains) meandBGain += g;
        meandBGain /= numFilters;
        for (int i = 0; i < numFilters; i++)
            inputGains[i] -= meandBGain;

        // Multiply row vector by filterResponseMatrix: inputGains = inputGains * filterResponseMatrix
        double[] result = new double[numFilters];
        for (int i = 0; i < numFilters; i++) {
            double sum = 0;
            for (int j = 0; j < numFilters; j++)
                sum += inputGains[j] * filterResponseMatrix.get(j, i);
            result[i] = sum;
        }

        // Convert back to linear
        for (int i = 0; i < numFilters; i++)
            result[i] = Definitions.pow10(result[i]);

        return new GainResult(result, Definitions.pow10(meandBGain));
    }

    private Coefficients createFrequencyVector(Coefficients fc) {
        Coefficients f = new Coefficients(numFilters);
        f.set(0, Math.max(fc.get(0) / 2.0, 20.0));
        for (int i = 1; i < numFilters - 1; i++)
            f.set(i, fc.get(i - 1));
        f.set(numFilters - 1, Math.min(fc.get(fc.length() - 1) * 2.0, 20000.0));
        return f;
    }
}
