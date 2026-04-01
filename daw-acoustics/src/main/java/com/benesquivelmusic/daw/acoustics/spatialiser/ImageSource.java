package com.benesquivelmusic.daw.acoustics.spatialiser;

import com.benesquivelmusic.daw.acoustics.common.Absorption;
import com.benesquivelmusic.daw.acoustics.common.Access;
import com.benesquivelmusic.daw.acoustics.common.Vec3;
import com.benesquivelmusic.daw.acoustics.dsp.Buffer;
import com.benesquivelmusic.daw.acoustics.dsp.GraphicEQ;
import com.benesquivelmusic.daw.acoustics.dsp.Parameter;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An image source used for early reflection / diffraction audio processing.
 * Ported from RoomAcoustiCpp {@code ImageSource}.
 */
public final class ImageSource extends Access {

    private final AirAbsorption airAbsorption;
    private final GraphicEQ absorptionFilter;
    private final Parameter gainParameter;

    private final AtomicReference<Vec3> targetPosition;
    private Vec3 currentPosition;

    private final AtomicReference<Absorption> targetAbsorption;
    private Absorption currentAbsorption;

    private final AtomicBoolean clearBuffers = new AtomicBoolean(false);
    private final AtomicBoolean hasChanged = new AtomicBoolean(true);

    private int fdnChannel = -1;
    private boolean feedsFDN;

    public ImageSource(Config config) {
        airAbsorption = new AirAbsorption(1.0, config.fs);
        absorptionFilter = new GraphicEQ(config.frequencyBands, config.Q, config.fs);
        gainParameter = new Parameter(0.0);
        targetPosition = new AtomicReference<>(new Vec3());
        currentPosition = new Vec3();
        targetAbsorption = new AtomicReference<>(new Absorption(config.frequencyBands.length()));
        currentAbsorption = new Absorption(config.frequencyBands.length());
    }

    /** Update the target parameters from image source data. */
    public void updateFromData(ImageSourceData data, double distance) {
        targetPosition.set(new Vec3(data.getPosition()));
        if (data.getAbsorption() != null)
            targetAbsorption.set(new Absorption(data.getAbsorption()));
        if (distance > 0) {
            gainParameter.setTarget(1.0 / distance);
            airAbsorption.setTargetDistance(distance);
        }
        fdnChannel = data.getFdnChannel();
        feedsFDN = data.feedsFDN();
        hasChanged.set(true);
    }

    /** Process a single audio frame for this image source. */
    public void processAudio(Buffer inputBuffer, Buffer outputBuffer, double lerpFactor) {
        if (!getAccess()) return;
        try {
            if (clearBuffers.compareAndSet(true, false)) {
                airAbsorption.clearBuffers();
                absorptionFilter.clearBuffers();
            }

            double gain = gainParameter.use(lerpFactor);
            int numFrames = inputBuffer.length();
            Buffer scratch = new Buffer(numFrames);

            // Apply gain and absorption filter
            for (int i = 0; i < numFrames; i++)
                scratch.set(i, inputBuffer.get(i) * gain);

            absorptionFilter.processAudio(scratch, outputBuffer, lerpFactor);
        } finally {
            freeAccess();
        }
    }

    public void init() {
        allowAccess();
        hasChanged.set(true);
    }

    public void remove() {
        preventAccess();
        clearBuffers.set(true);
    }

    public Vec3 getPosition() { return new Vec3(currentPosition); }
    public boolean hasChanged() { return hasChanged.compareAndSet(true, false); }
    public int getFdnChannel() { return fdnChannel; }
    public boolean feedsFDN() { return feedsFDN; }
}
