package com.benesquivelmusic.daw.acoustics.dsp;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lock-free FIR filter with variable-length impulse response.
 * Ported from RoomAcoustiCpp {@code FIRFilter}.
 */
public final class FIRFilter {

    private final int maxFilterLength;
    private AtomicReference<Buffer> targetIR;
    private final Buffer currentIR;
    private final Buffer inputLine;

    private int irLength;
    private int oldIrLength;
    private int count;

    private final AtomicBoolean clearInputLine = new AtomicBoolean(false);
    private final AtomicBoolean irsEqual = new AtomicBoolean(false);
    private final AtomicBoolean initialised = new AtomicBoolean(false);

    public FIRFilter(Buffer ir, int maxSize) {
        maxFilterLength = (maxSize % 8 == 0) ? maxSize : maxSize + (8 - maxSize % 8);
        inputLine = new Buffer(2 * maxFilterLength);
        currentIR = new Buffer(maxFilterLength);

        if (!setTargetIR(ir)) return;

        irLength = targetIR.get().length();
        oldIrLength = irLength;

        double[] irRaw = ir.rawData();
        double[] curRaw = currentIR.rawData();
        System.arraycopy(irRaw, 0, curRaw, 0, Math.min(irRaw.length, curRaw.length));

        irsEqual.set(true);
        initialised.set(true);
    }

    public double getOutput(double input, double lerpFactor) {
        if (!initialised.get()) return 0.0;

        if (clearInputLine.compareAndSet(true, false)) inputLine.reset();

        if (!irsEqual.get()) interpolateIR(lerpFactor);

        double[] line = inputLine.rawData();
        double[] ir = currentIR.rawData();

        // Using a double buffer size to avoid checks in process loop
        line[count] = input;
        line[count + maxFilterLength] = input;

        double output = 0.0;
        int index = count;
        for (int i = 0; i < irLength; i += 8) {
            output += ir[i] * line[index++];
            output += ir[i + 1] * line[index++];
            output += ir[i + 2] * line[index++];
            output += ir[i + 3] * line[index++];
            output += ir[i + 4] * line[index++];
            output += ir[i + 5] * line[index++];
            output += ir[i + 6] * line[index++];
            output += ir[i + 7] * line[index++];
        }

        if (--count < 0) count = maxFilterLength - 1;

        return output;
    }

    public boolean setTargetIR(Buffer ir) {
        if (ir == null || ir.length() == 0) return false;
        int len = ir.length();
        int padded = (len % 8 == 0) ? len : len + (8 - len % 8);
        Buffer paddedIR = new Buffer(padded);
        for (int i = 0; i < len; i++) paddedIR.set(i, ir.get(i));
        targetIR = new AtomicReference<>(paddedIR);
        irsEqual.set(false);
        return true;
    }

    public void reset() { clearInputLine.set(true); }

    private void interpolateIR(double lerpFactor) {
        irsEqual.set(true);
        Buffer tgt = targetIR.get();
        int newLen = tgt.length();

        Interpolation.lerp(currentIR, tgt, oldIrLength, lerpFactor);

        if (!Interpolation.equals(currentIR, tgt, newLen)) {
            irsEqual.set(false);
        }

        oldIrLength = irLength;
        irLength = newLen;
    }
}
