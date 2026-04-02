package com.benesquivelmusic.daw.acoustics.spatialiser;

import com.benesquivelmusic.daw.acoustics.common.*;
import com.benesquivelmusic.daw.acoustics.dsp.Buffer;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Global context for the spatialiser. Main processing class.
 * Ported from RoomAcoustiCpp {@code Context}.
 */
public final class Context {

    private final Config config;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final Room room;
    private final Reverb reverb;
    private final SourceManager sources;
    private final ImageEdge imageEdgeModel;
    private final HeadphoneEQ headphoneEQ;

    private Vec3 listenerPosition = new Vec3();
    private boolean applyHeadphoneEQ;

    private final Matrix reverbInput;
    private Thread iemThread;

    public Context(Config config) {
        this.config = config;
        this.room = new Room(config.frequencyBands.length());
        this.reverb = new Reverb(config);
        this.sources = new SourceManager(config);
        this.imageEdgeModel = new ImageEdge(room, config);
        this.headphoneEQ = new HeadphoneEQ(2048);
        this.reverbInput = new Matrix(config.numReverbSources, config.numFrames);
        this.isRunning.set(true);
    }

    public synchronized void exit() {
        isRunning.set(false);
        if (iemThread != null) {
            iemThread.interrupt();
            try { iemThread.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            iemThread = null;
        }
    }

    public boolean isRunning() { return isRunning.get(); }

    public void setHeadphoneEQ(Buffer leftIR, Buffer rightIR) {
        headphoneEQ.setFilters(leftIR, rightIR);
        applyHeadphoneEQ = true;
    }

    public void updateSpatialisationMode(SpatialisationMode mode) {
        config.setSpatialisationMode(mode);
        sources.updateSpatialisationMode(mode);
    }

    public void updateIEMConfig(IEMData data) {
        imageEdgeModel.updateIEMConfig(data, config);
    }

    public void updateReverbTime(ReverbFormula model) {
        room.updateReverbTimeFormula(model);
        reverb.setTargetT60(room.getReverbTime());
    }

    public void updateReverbTime(Coefficients T60) {
        room.updateReverbTime(T60);
        reverb.setTargetT60(room.getReverbTime());
    }

    public void updateDiffractionModel(DiffractionModel model) {
        config.setDiffractionModel(model);
        imageEdgeModel.updateDiffractionModel(model);
    }

    public Room getRoom() { return room; }
    public ImageEdge getImageEdgeModel() { return imageEdgeModel; }

    public boolean initLateReverb(double volume, double[] dimensions, FDNMatrix matrix) {
        if (dimensions == null || dimensions.length == 0) return false;
        Coefficients T60 = room.getReverbTime(volume);
        reverb.initLateReverb(T60, dimensions, matrix, config);
        return true;
    }

    public void resetFDN() { reverb.reset(); }

    public void updateListener(Vec3 position, Vec4 orientation) {
        listenerPosition = new Vec3(position);
        imageEdgeModel.setListenerPosition(position);
    }

    public long initSource() { return sources.initSource(); }

    public void updateSource(long id, Vec3 position, Vec4 orientation) {
        sources.updateSource(id, position, orientation);
    }

    public void updateSourceDirectivity(long id, SourceDirectivity directivity) {
        sources.updateSourceDirectivity(id, directivity);
    }

    public void removeSource(long id) { sources.removeSource(id); }

    public long initWall(Vec3[] vertices, Absorption absorption) {
        if (absorption.length() != config.frequencyBands.length()) return -1;
        long id = room.initWall(vertices, absorption);
        room.initEdges(id);
        return id;
    }

    public void updateWall(long id, Vec3[] vData) { room.updateWall(id, vData); }

    public void updateWallAbsorption(long id, Absorption absorption) {
        if (absorption.length() != config.frequencyBands.length()) return;
        room.updateWallAbsorption(id, absorption);
        reverb.setTargetT60(room.getReverbTime());
    }

    public void removeWall(long id) { room.removeWall(id); }

    public void updatePlanesAndEdges() {
        room.updatePlanes();
        room.updateEdges();
    }

    public void submitAudio(long id, Buffer data) { sources.setInputBuffer(id, data); }

    /**
     * Process all sources and write stereo output.
     * If outputBuffer length != 2 * numFrames, it will be resized.
     */
    public void getOutput(Buffer outputBuffer) {
        if (outputBuffer.length() != 2 * config.numFrames)
            outputBuffer.resize(2 * config.numFrames);

        outputBuffer.reset();
        reverbInput.reset();
        double lerpFactor = config.getLerpFactor();

        // Process sources: direct, reflections, etc.
        // In the full C++ implementation, this calls into 3DTI for binaural processing.
        // Here we provide the framework for plugging in an alternative binaural renderer.
        Map<Long, Source> srcMap = sources.getSources();
        for (Map.Entry<Long, Source> entry : srcMap.entrySet()) {
            Source src = entry.getValue();
            Buffer input = src.getInputBuffer();
            // Simple stereo mix (without binaural — that would require an external HRTF library)
            for (int i = 0; i < config.numFrames; i++) {
                double sample = input.get(i);
                outputBuffer.set(i * 2, outputBuffer.get(i * 2) + sample);
                outputBuffer.set(i * 2 + 1, outputBuffer.get(i * 2 + 1) + sample);
            }
        }

        // Process late reverberation
        reverb.processAudio(reverbInput, outputBuffer, lerpFactor);
        reverbInput.reset();

        // Apply headphone EQ if set
        if (applyHeadphoneEQ)
            headphoneEQ.processAudio(outputBuffer, outputBuffer, lerpFactor);
    }

    public void updateImpulseResponseMode(boolean mode) {
        config.setImpulseResponseMode(mode);
        sources.updateImpulseResponseMode(mode);
    }

    /** Start the background IEM thread. */
    public synchronized void startIEMThread(long intervalMs) {
        if (iemThread != null && iemThread.isAlive()) return;
        iemThread = Thread.ofVirtual().start(() -> {
            while (isRunning.get()) {
                try {
                    Map<Long, Vec3> positions = sources.getSourcePositions();
                    imageEdgeModel.runIEM(positions);
                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }
}
