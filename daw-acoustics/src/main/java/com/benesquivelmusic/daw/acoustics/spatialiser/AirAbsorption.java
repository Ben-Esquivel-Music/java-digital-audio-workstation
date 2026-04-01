package com.benesquivelmusic.daw.acoustics.spatialiser;

import com.benesquivelmusic.daw.acoustics.common.Definitions;
import com.benesquivelmusic.daw.acoustics.dsp.Buffer;
import com.benesquivelmusic.daw.acoustics.dsp.IIRFilter1;
import com.benesquivelmusic.daw.acoustics.dsp.Interpolation;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Air absorption filter.
 * Ported from RoomAcoustiCpp {@code AirAbsorption}.
 */
public final class AirAbsorption extends IIRFilter1 {

    private final double constant;
    private final AtomicReference<Double> targetDistance;
    private double currentDistance;

    public AirAbsorption(double distance, int sampleRate) {
        super(sampleRate);
        this.constant = (double) sampleRate / (Definitions.SPEED_OF_SOUND * 7782.0);
        this.targetDistance = new AtomicReference<>(distance);
        this.currentDistance = distance;
        a0 = 1.0; b1 = 0.0;
        updateCoefficients(distance);
        parametersEqual.set(true);
        initialised.set(true);
    }

    public void setTargetDistance(double distance) {
        targetDistance.set(distance);
        parametersEqual.set(false);
    }

    public void processAudio(Buffer inBuffer, Buffer outBuffer, double lerpFactor) {
        for (int i = 0; i < inBuffer.length(); i++)
            outBuffer.set(i, getOutput(inBuffer.get(i), lerpFactor));
    }

    @Override
    protected void interpolateParameters(double lerpFactor) {
        parametersEqual.set(true);
        double tgt = targetDistance.get();
        currentDistance = Interpolation.lerp(currentDistance, tgt, lerpFactor);
        if (Interpolation.equals(currentDistance, tgt)) currentDistance = tgt;
        else parametersEqual.set(false);
        updateCoefficients(currentDistance);
    }

    private void updateCoefficients(double distance) {
        b0 = Math.exp(-distance * constant);
        a1 = b0 - 1;
    }
}
