package com.benesquivelmusic.daw.acoustics.spatialiser;

import com.benesquivelmusic.daw.acoustics.common.*;
import com.benesquivelmusic.daw.acoustics.dsp.Buffer;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents a sound source in the spatialiser.
 * Ported from RoomAcoustiCpp {@code Source}.
 */
public final class Source extends Access {

    /** Direct sound audio data. */
    public record AudioData(Absorption directivity, LateReverbModel reverbSend) {}

    /** Source position, orientation and directivity. */
    public record Data(long id, Vec3 position, Vec4 orientation, Vec3 forward, SourceDirectivity directivity, boolean hasChanged) {}

    private Vec3 currentPosition = new Vec3();
    private Vec4 currentOrientation = new Vec4();
    private final AtomicReference<SourceDirectivity> directivity = new AtomicReference<>(SourceDirectivity.OMNI);
    private final AtomicBoolean hasChanged = new AtomicBoolean(true);
    private final Buffer inputBuffer;
    private boolean inputBufferUpdated;
    private final AtomicBoolean clearInputBuffer = new AtomicBoolean(false);

    private final Object dataMutex = new Object();

    public Source(Config config) {
        inputBuffer = new Buffer(config.numFrames);
    }

    public void init() {
        allowAccess();
        hasChanged.set(true);
    }

    public void remove() {
        preventAccess();
        clearInputBuffer.set(true);
    }

    public void resetInputBuffer() {
        if (clearInputBuffer.compareAndSet(true, false) || !inputBufferUpdated) {
            inputBuffer.reset();
            inputBufferUpdated = true;
        }
    }

    public void setInputBuffer(Buffer data) {
        inputBufferUpdated = true;
        double[] src = data.rawData();
        double[] dst = inputBuffer.rawData();
        int copyLen = Math.min(src.length, dst.length);
        System.arraycopy(src, 0, dst, 0, copyLen);
        if (copyLen < dst.length) {
            java.util.Arrays.fill(dst, copyLen, dst.length, 0.0);
        }
    }

    public void updateDirectivity(SourceDirectivity dir) {
        directivity.set(dir);
        hasChanged.set(true);
    }

    public void updateSpatialisationMode(SpatialisationMode mode) {
        // Stored for future use when binaural renderer is plugged in
    }

    public void updateImpulseResponseMode(boolean mode) {
        // Stored for future use when binaural renderer is plugged in
    }

    public void update(Vec3 position, Vec4 orientation, double distance) {
        synchronized (dataMutex) {
            currentPosition = new Vec3(position);
            currentOrientation = new Vec4(orientation);
        }
        hasChanged.set(true);
    }

    public Data getData(long id) {
        synchronized (dataMutex) {
            boolean changed = hasChanged.compareAndSet(true, false);
            Vec3 forward = currentOrientation.forward();
            return new Data(id, new Vec3(currentPosition), new Vec4(currentOrientation),
                    forward, directivity.get(), changed);
        }
    }

    public Vec3 getPosition() { synchronized (dataMutex) { return new Vec3(currentPosition); } }
    public Vec4 getOrientation() { synchronized (dataMutex) { return new Vec4(currentOrientation); } }
    public SourceDirectivity getDirectivity() { return directivity.get(); }
    public boolean hasChanged() { return hasChanged.compareAndSet(true, false); }
    public Buffer getInputBuffer() { return inputBuffer; }
}
