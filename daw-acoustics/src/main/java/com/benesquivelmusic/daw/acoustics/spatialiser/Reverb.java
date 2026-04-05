package com.benesquivelmusic.daw.acoustics.spatialiser;

import com.benesquivelmusic.daw.acoustics.common.Coefficients;
import com.benesquivelmusic.daw.acoustics.common.Definitions;
import com.benesquivelmusic.daw.acoustics.common.Matrix;
import com.benesquivelmusic.daw.acoustics.common.Vec3;
import com.benesquivelmusic.daw.acoustics.dsp.Buffer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Handles late reverberation processing using an FDN and spatialised reverb sources.
 * Ported from RoomAcoustiCpp {@code Reverb, ReverbSource}.
 */
public final class Reverb {

    private final List<Buffer> reverbSourceInputs;
    private final List<Vec3> reverbSourceShifts;
    private final AtomicReference<FDN> fdn = new AtomicReference<>();
    private final AtomicBoolean initialised = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final int numReverbSources;
    private final int numFrames;

    public Reverb(Config config) {
        this.numReverbSources = config.numReverbSources;
        this.numFrames = config.numFrames;
        reverbSourceInputs = new ArrayList<>(numReverbSources);
        for (int i = 0; i < numReverbSources; i++)
            reverbSourceInputs.add(new Buffer(numFrames));
        reverbSourceShifts = calculateSourcePositions(numReverbSources);
    }

    public void initLateReverb(Coefficients T60, double[] dimensions, FDNMatrix matrix, Config config) {
        FDN newFDN = switch (matrix) {
            case HOUSEHOLDER -> new FDN.HouseholderFDN(T60, dimensions, config);
            case RANDOM_ORTHOGONAL -> new FDN.RandomOrthogonalFDN(T60, dimensions, config);
        };
        fdn.set(newFDN);
        initialised.set(true);
        running.set(true);
    }

    public void setTargetT60(Coefficients T60) {
        FDN currentFDN = fdn.get();
        if (currentFDN != null) currentFDN.setTargetT60(T60);
    }

    public void updateReflectionFilters(List<Coefficients> absorptions) {
        FDN currentFDN = fdn.get();
        if (currentFDN != null) {
            boolean isZero = currentFDN.setTargetReflectionFilters(absorptions);
            running.set(!isZero);
        }
    }

    public void processAudio(Matrix data, Buffer outputBuffer, double lerpFactor) {
        if (!initialised.get() || !running.get()) return;

        FDN currentFDN = fdn.get();
        if (currentFDN == null) return;

        currentFDN.processAudio(data, reverbSourceInputs, lerpFactor);

        // Mix reverb source outputs into stereo output
        for (int ch = 0; ch < numReverbSources; ch++) {
            Buffer chBuf = reverbSourceInputs.get(ch);
            for (int i = 0; i < numFrames; i++) {
                // Simple panning based on source shift direction
                Vec3 shift = reverbSourceShifts.get(ch);
                double pan = 0.5 + 0.5 * shift.x;
                outputBuffer.set(i * 2, outputBuffer.get(i * 2) + chBuf.get(i) * (1.0 - pan));
                outputBuffer.set(i * 2 + 1, outputBuffer.get(i * 2 + 1) + chBuf.get(i) * pan);
            }
        }
    }

    public void reset() {
        FDN currentFDN = fdn.get();
        if (currentFDN != null) currentFDN.reset();
    }

    public List<Vec3> getReverbSourceDirections() {
        List<Vec3> directions = new ArrayList<>();
        for (Vec3 shift : reverbSourceShifts)
            directions.add(Vec3.mul(100.0, shift));
        return directions;
    }

    private List<Vec3> calculateSourcePositions(int numChannels) {
        List<Vec3> positions = new ArrayList<>();
        // Distribute sources uniformly on a sphere
        double goldenRatio = (1.0 + Math.sqrt(5.0)) / 2.0;
        for (int i = 0; i < numChannels; i++) {
            double theta = Math.acos(1.0 - 2.0 * (i + 0.5) / numChannels);
            double phi = Definitions.PI_2 * i / goldenRatio;
            positions.add(new Vec3(
                    Math.sin(theta) * Math.cos(phi),
                    Math.sin(theta) * Math.sin(phi),
                    Math.cos(theta)));
        }
        return positions;
    }
}
