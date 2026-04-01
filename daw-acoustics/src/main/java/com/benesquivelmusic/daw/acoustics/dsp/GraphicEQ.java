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
        numFilters = fc.length();
        previousInput = new Coefficients(gain);

        lowShelf = new PeakLowShelf(fc.get(0), Q, sampleRate);
        peakingFilters = new ArrayList<>();
        for (int i = 1; i < numFilters - 1; i++)
            peakingFilters.add(new PeakingFilter(fc.get(i), Q, sampleRate));
        highShelf = new PeakHighShelf(fc.get(numFilters - 1), Q, sampleRate);

        initMatrix(fc, Q, sampleRate);

        var result = calculateGains(gain);
        Rowvec filterGains = result.filterGains();

        lowShelf.setTargetGain(filterGains.get(0));
        for (int i = 0; i < peakingFilters.size(); i++)
            peakingFilters.get(i).setTargetGain(filterGains.get(i + 1));
        highShelf.setTargetGain(filterGains.get(numFilters - 1));

        targetGain = new AtomicReference<>(result.dcGain());
        currentGain = result.dcGain();

        gainsEqual.set(true);
        initialised.set(true);
    }

    public boolean setTargetGains(Coefficients gains) {
        if (Interpolation.equals(gains, previousInput)) return false;
        previousInput = new Coefficients(gains);

        var result = calculateGains(gains);
        Rowvec filterGains = result.filterGains();

        lowShelf.setTargetGain(filterGains.get(0));
        for (int i = 0; i < peakingFilters.size(); i++)
            peakingFilters.get(i).setTargetGain(filterGains.get(i + 1));
        highShelf.setTargetGain(filterGains.get(numFilters - 1));

        targetGain.set(result.dcGain());
        gainsEqual.set(false);
        return false;
    }

    public double getOutput(double input, double lerpFactor) {
        if (!initialised.get()) return input;
        if (!gainsEqual.get()) interpolateGain(lerpFactor);

        double output = input * currentGain;
        output = lowShelf.getOutput(output, lerpFactor);
        for (PeakingFilter f : peakingFilters)
            output = f.getOutput(output, lerpFactor);
        output = highShelf.getOutput(output, lerpFactor);
        return output;
    }

    public void processAudio(Buffer inBuffer, Buffer outBuffer, double lerpFactor) {
        for (int i = 0; i < inBuffer.length(); i++)
            outBuffer.set(i, outBuffer.get(i) + getOutput(inBuffer.get(i), lerpFactor));
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
        Coefficients fVec = createFrequencyVector(fc);
        int n = fVec.length();

        Matrix respMatrix = new Matrix(n, numFilters);

        // Low shelf response
        PeakLowShelf tempLow = new PeakLowShelf(fc.get(0), 2.0, Q, (int) fs);
        Coefficients lowResp = tempLow.getFrequencyResponse(fVec);
        for (int i = 0; i < n; i++) respMatrix.set(i, 0, lowResp.get(i));

        // Peaking filter responses
        for (int j = 1; j < numFilters - 1; j++) {
            PeakingFilter tempPeak = new PeakingFilter(fc.get(j), 2.0, Q, (int) fs);
            Coefficients peakResp = tempPeak.getFrequencyResponse(fVec);
            for (int i = 0; i < n; i++) respMatrix.set(i, j, peakResp.get(i));
        }

        // High shelf response
        PeakHighShelf tempHigh = new PeakHighShelf(fc.get(numFilters - 1), 2.0, Q, (int) fs);
        Coefficients highResp = tempHigh.getFrequencyResponse(fVec);
        for (int i = 0; i < n; i++) respMatrix.set(i, numFilters - 1, highResp.get(i));

        respMatrix.log10();

        // Invert via normal equations: (A^T A)^-1 A^T
        Matrix at = respMatrix.transpose();
        Matrix ata = Matrix.multiply(at, respMatrix);
        ata.inverse();
        filterResponseMatrix = Matrix.multiply(ata, at);
    }

    private record GainResult(Rowvec filterGains, double dcGain) {}

    private GainResult calculateGains(Coefficients gains) {
        Coefficients fVec = createFrequencyVector(new Coefficients(numFilters));

        // Convert gains to log-space target vector
        Vec target = new Vec(fVec.length());
        Coefficients gainLogPow = new Coefficients(gains);
        gainLogPow.mulLocal(0.05); // gains/20
        gainLogPow.pow10Local();

        for (int i = 0; i < fVec.length(); i++) {
            // Interpolate to match each frequency
            int band = Math.min(i, gainLogPow.length() - 1);
            target.set(i, Definitions.log10(gainLogPow.get(band)));
        }

        // Multiply filterResponseMatrix by target to get filter dB gains
        Matrix result = Matrix.multiply(filterResponseMatrix, target);

        Rowvec filterGains = new Rowvec(numFilters);
        for (int i = 0; i < numFilters; i++)
            filterGains.set(i, Definitions.pow10(result.get(i, 0)));

        // DC gain = product of all filter gains at DC / target DC gain
        double dcGain = gainLogPow.get(0);
        return new GainResult(filterGains, dcGain);
    }

    private Coefficients createFrequencyVector(Coefficients fc) {
        // One point per band
        Coefficients fVec = new Coefficients(numFilters);
        for (int i = 0; i < numFilters; i++) {
            fVec.set(i, previousInput.length() > i ? 20.0 * Math.pow(10.0, i * 3.0 / numFilters) : 1000.0);
        }
        return fVec;
    }
}
