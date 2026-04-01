package com.benesquivelmusic.daw.acoustics.spatialiser;

import com.benesquivelmusic.daw.acoustics.dsp.Buffer;
import com.benesquivelmusic.daw.acoustics.dsp.FIRFilter;

/**
 * Stereo FIR headphone EQ filter.
 * Ported from RoomAcoustiCpp {@code HeadphoneEQ}.
 */
public final class HeadphoneEQ {

    private final FIRFilter leftFilter;
    private final FIRFilter rightFilter;

    public HeadphoneEQ(int maxFilterLength) {
        Buffer identity = new Buffer(1);
        identity.set(0, 1.0);
        leftFilter = new FIRFilter(identity, maxFilterLength);
        rightFilter = new FIRFilter(identity, maxFilterLength);
    }

    public void setFilters(Buffer leftIR, Buffer rightIR) {
        leftFilter.setTargetIR(leftIR);
        rightFilter.setTargetIR(rightIR);
    }

    public void processAudio(Buffer inputBuffer, Buffer outputBuffer, double lerpFactor) {
        for (int i = 0; i < inputBuffer.length(); i += 2) {
            outputBuffer.set(i, leftFilter.getOutput(inputBuffer.get(i), lerpFactor));
            outputBuffer.set(i + 1, rightFilter.getOutput(inputBuffer.get(i + 1), lerpFactor));
        }
    }

    public void reset() { leftFilter.reset(); rightFilter.reset(); }
}
