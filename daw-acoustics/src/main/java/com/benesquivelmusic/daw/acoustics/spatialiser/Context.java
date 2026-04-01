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
        this.headphoneEQ = new HeadphoneEQ(config.numFrames);
        this.reverbInput = new Matrix(config.numReverbSources, config.numFrames);
        this.isRunning.set(true);
    }

    public void exit() {
        isRunning.set(false);
        if (iemThread != null) {
            try { iemThread.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
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
    }

    public void updateReverbTime(Coefficients T60) {
        room.updateReverbTime(T60);
    }

    public void updateDiffractionModel(DiffractionModel model) {
        config.setDiffractionModel(model);
        imageEdgeModel.updateDiffractionModel(model);
    }

    public Room getRoom() { return room; }
    public ImageEdge getImageEdgeModel() { return imageEdgeModel; }

    public boolean initLateReverb(double volume, double[] dimensions, FDNMatrix matrix) {
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
        return room.initWall(vertices, absorption);
    }

    public void updateWall(long id, Vec3[] vData) { room.updateWall(id, vData); }

    public void updateWallAbsorption(long id, Absorption absorption) {
        room.updateWallAbsorption(id, absorption);
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
        reverb.processAudio(reverbInput, outputBuffer, config.getLerpFactor());

        // Apply headphone EQ if set
        if (applyHeadphoneEQ) {
            Buffer eqOutput = new Buffer(outputBuffer.length());
            headphoneEQ.processAudio(outputBuffer, eqOutput, config.getLerpFactor());
            for (int i = 0; i < outputBuffer.length(); i++)
                outputBuffer.set(i, eqOutput.get(i));
        }
    }

    public void updateImpulseResponseMode(boolean mode) {
        config.setImpulseResponseMode(mode);
        sources.updateImpulseResponseMode(mode);
    }

    /** Start the background IEM thread. */
    public void startIEMThread(long intervalMs) {
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
