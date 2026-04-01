package com.benesquivelmusic.daw.acoustics.spatialiser;

import com.benesquivelmusic.daw.acoustics.common.*;
import com.benesquivelmusic.daw.acoustics.dsp.Buffer;

/**
 * Public API facade for the RoomAcoustiCpp spatialiser.
 * Thread-safe singleton interface matching the C++ free-function API.
 * Ported from RoomAcoustiCpp {@code Interface.h}.
 */
public final class Interface {

    private static volatile Context context;

    private Interface() {}

    /** Initialise the spatialiser with the given configuration. */
    public static boolean init(Config config) {
        if (context != null) exit();
        context = new Context(config);
        return true;
    }

    /** Exit and clean up the spatialiser. */
    public static void exit() {
        if (context != null) {
            context.exit();
            context = null;
        }
    }

    /** Update the spatialisation mode. */
    public static void updateSpatialisationMode(SpatialisationMode mode) {
        requireContext().updateSpatialisationMode(mode);
    }

    /** Set headphone EQ filters. */
    public static void setHeadphoneEQ(Buffer leftIR, Buffer rightIR) {
        requireContext().setHeadphoneEQ(leftIR, rightIR);
    }

    /** Update IEM config. */
    public static void updateIEMConfig(IEMData data) {
        requireContext().updateIEMConfig(data);
    }

    /** Update reverb formula. */
    public static void updateReverbTime(ReverbFormula model) {
        requireContext().updateReverbTime(model);
    }

    /** Override reverb time. */
    public static void updateReverbTime(Coefficients T60) {
        requireContext().updateReverbTime(T60);
    }

    /** Update diffraction model. */
    public static void updateDiffractionModel(DiffractionModel model) {
        requireContext().updateDiffractionModel(model);
    }

    /** Initialise late reverb. */
    public static boolean initLateReverb(double volume, double[] dimensions, FDNMatrix matrix) {
        return requireContext().initLateReverb(volume, dimensions, matrix);
    }

    /** Reset FDN buffers. */
    public static void resetFDN() { requireContext().resetFDN(); }

    /** Update listener. */
    public static void updateListener(Vec3 position, Vec4 orientation) {
        requireContext().updateListener(position, orientation);
    }

    /** Init a new source. Returns source ID. */
    public static long initSource() { return requireContext().initSource(); }

    /** Update source position and orientation. */
    public static void updateSource(long id, Vec3 position, Vec4 orientation) {
        requireContext().updateSource(id, position, orientation);
    }

    /** Update source directivity. */
    public static void updateSourceDirectivity(long id, SourceDirectivity directivity) {
        requireContext().updateSourceDirectivity(id, directivity);
    }

    /** Remove source. */
    public static void removeSource(long id) { requireContext().removeSource(id); }

    /** Init a new wall. Returns wall ID. */
    public static long initWall(Vec3[] vertices, Absorption absorption) {
        return requireContext().initWall(vertices, absorption);
    }

    /** Update wall position. */
    public static void updateWall(long id, Vec3[] vData) { requireContext().updateWall(id, vData); }

    /** Update wall absorption. */
    public static void updateWallAbsorption(long id, Absorption absorption) {
        requireContext().updateWallAbsorption(id, absorption);
    }

    /** Remove wall. */
    public static void removeWall(long id) { requireContext().removeWall(id); }

    /** Update planes and edges. */
    public static void updatePlanesAndEdges() { requireContext().updatePlanesAndEdges(); }

    /** Submit audio to a source. */
    public static void submitAudio(long id, Buffer data) { requireContext().submitAudio(id, data); }

    /** Get the processed stereo output. */
    public static void getOutput(Buffer outputBuffer) { requireContext().getOutput(outputBuffer); }

    /** Set impulse response mode. */
    public static void updateImpulseResponseMode(boolean mode) {
        requireContext().updateImpulseResponseMode(mode);
    }

    private static Context requireContext() {
        Context ctx = context;
        if (ctx == null) throw new IllegalStateException("Spatialiser not initialised. Call init() first.");
        return ctx;
    }
}
