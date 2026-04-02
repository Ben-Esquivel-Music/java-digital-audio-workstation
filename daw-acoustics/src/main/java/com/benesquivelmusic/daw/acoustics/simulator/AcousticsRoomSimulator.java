package com.benesquivelmusic.daw.acoustics.simulator;

import com.benesquivelmusic.daw.acoustics.common.Absorption;
import com.benesquivelmusic.daw.acoustics.common.Coefficients;
import com.benesquivelmusic.daw.acoustics.common.Definitions;
import com.benesquivelmusic.daw.acoustics.common.Vec3;
import com.benesquivelmusic.daw.acoustics.common.Vec4;
import com.benesquivelmusic.daw.acoustics.dsp.Buffer;
import com.benesquivelmusic.daw.acoustics.spatialiser.Config;
import com.benesquivelmusic.daw.acoustics.spatialiser.Context;
import com.benesquivelmusic.daw.acoustics.spatialiser.FDNMatrix;
import com.benesquivelmusic.daw.acoustics.spatialiser.Room;
import com.benesquivelmusic.daw.sdk.spatial.ImpulseResponse;
import com.benesquivelmusic.daw.sdk.spatial.RoomSimulationConfig;
import com.benesquivelmusic.daw.sdk.spatial.RoomSimulator;
import com.benesquivelmusic.daw.sdk.telemetry.ListenerOrientation;
import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.SoundSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Full-featured room acoustic simulator using the daw-acoustics engine
 * (a pure-Java port of RoomAcoustiCpp).
 *
 * <p>This implementation replaces the native RoomAcoustiC++ FFM bridge with a
 * pure-Java engine that combines:</p>
 * <ul>
 *   <li>Image-source method for early reflections</li>
 *   <li>Feedback Delay Network (Householder variant) for late reverberation</li>
 *   <li>Per-surface frequency-dependent absorption</li>
 *   <li>Air absorption modeling</li>
 * </ul>
 *
 * <p>The engine uses the full {@link Context} and {@link Room} infrastructure
 * ported from RoomAcoustiCpp, providing physically-based room acoustics
 * simulation without any native library dependency.</p>
 */
public final class AcousticsRoomSimulator implements RoomSimulator {

    /** Speed of sound in air at 20°C in meters per second. */
    static final double SPEED_OF_SOUND_MPS = Definitions.SPEED_OF_SOUND;

    /** Number of frequency bands for absorption modeling. */
    private static final int NUM_FREQUENCY_BANDS = 5;

    /** Default block size for audio processing. */
    private static final int DEFAULT_BLOCK_SIZE = 512;

    /** Number of FDN reverb sources. */
    private static final int NUM_REVERB_SOURCES = 12;

    private RoomSimulationConfig config;
    private ListenerOrientation listenerOrientation;
    private final List<SoundSource> sources = new ArrayList<>();

    // Acoustics engine state
    private Context context;
    private Config acousticsConfig;
    private final Map<String, Long> sourceIdMap = new LinkedHashMap<>();

    // Room geometry wall IDs (floor, ceiling, left, right, front, back — 2 triangles each)
    private final long[] wallIds = new long[12];
    private int wallCount;

    // Convolution / IR state
    private float[] currentIr;
    private boolean needsIrUpdate = true;

    /**
     * Creates an acoustics room simulator. Call {@link #configure(RoomSimulationConfig)}
     * before processing audio.
     */
    public AcousticsRoomSimulator() {
        // Default state — not yet configured
    }

    @Override
    public void configure(RoomSimulationConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        this.config = config;
        this.listenerOrientation = config.listener();
        this.sources.clear();
        this.sources.addAll(config.sources());
        this.sourceIdMap.clear();

        initializeAcousticsEngine();
        this.needsIrUpdate = true;
    }

    @Override
    public RoomSimulationConfig getConfiguration() {
        return config;
    }

    @Override
    public void setListenerOrientation(ListenerOrientation orientation) {
        Objects.requireNonNull(orientation, "orientation must not be null");
        this.listenerOrientation = orientation;
        if (context != null) {
            Position3D pos = orientation.position();
            context.updateListener(
                    new Vec3(pos.x(), pos.y(), pos.z()),
                    orientationToVec4(orientation));
        }
        this.needsIrUpdate = true;
    }

    @Override
    public ListenerOrientation getListenerOrientation() {
        return listenerOrientation;
    }

    @Override
    public void addSource(SoundSource source) {
        Objects.requireNonNull(source, "source must not be null");
        sources.add(source);
        if (context != null) {
            long id = context.initSource();
            sourceIdMap.put(source.name(), id);
            Position3D pos = source.position();
            context.updateSource(id, new Vec3(pos.x(), pos.y(), pos.z()), new Vec4());
        }
        this.needsIrUpdate = true;
    }

    @Override
    public boolean removeSource(String sourceName) {
        boolean removed = sources.removeIf(s -> s.name().equals(sourceName));
        if (removed) {
            Long id = sourceIdMap.remove(sourceName);
            if (id != null && context != null) {
                context.removeSource(id);
            }
            this.needsIrUpdate = true;
        }
        return removed;
    }

    @Override
    public boolean updateSourcePosition(String sourceName, Position3D position) {
        Objects.requireNonNull(position, "position must not be null");
        for (int i = 0; i < sources.size(); i++) {
            SoundSource existing = sources.get(i);
            if (existing.name().equals(sourceName)) {
                sources.set(i, new SoundSource(existing.name(), position, existing.powerDb()));
                Long id = sourceIdMap.get(sourceName);
                if (id != null && context != null) {
                    context.updateSource(id, new Vec3(position.x(), position.y(), position.z()), new Vec4());
                }
                this.needsIrUpdate = true;
                return true;
            }
        }
        return false;
    }

    @Override
    public ImpulseResponse generateImpulseResponse() {
        ensureConfigured();

        RoomDimensions dims = config.dimensions();
        int sampleRate = config.sampleRate();

        // Compute RT60 using the acoustics engine's room model
        double avgAbsorption = config.averageAbsorption();
        double rt60 = estimateRt60(dims, avgAbsorption);

        // IR length = RT60 * sampleRate (capped at a reasonable maximum)
        int irLength = Math.min((int) (rt60 * sampleRate), sampleRate * 4);
        irLength = Math.max(irLength, sampleRate / 10); // minimum 100ms

        float[] ir = new float[irLength];

        // 1. Early reflections via image-source method
        if (!sources.isEmpty() && listenerOrientation != null) {
            addEarlyReflections(ir, sampleRate, dims);
        }

        // 2. Late reverberation via the acoustics engine FDN
        addFdnReverbTail(ir, sampleRate, rt60);

        // Normalize the IR
        normalizeIr(ir);

        this.currentIr = ir;
        this.needsIrUpdate = false;

        return new ImpulseResponse(new float[][]{ir}, sampleRate);
    }

    @Override
    public boolean isNativeAccelerated() {
        return false;
    }

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        if (config == null) {
            // Pass through if not configured
            for (int ch = 0; ch < Math.min(inputBuffer.length, outputBuffer.length); ch++) {
                System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
            }
            return;
        }

        // Regenerate IR if needed
        if (needsIrUpdate || currentIr == null) {
            generateImpulseResponse();
        }

        // Apply IR via convolution
        int irLen = Math.min(currentIr.length, numFrames);
        for (int ch = 0; ch < Math.min(inputBuffer.length, outputBuffer.length); ch++) {
            convolveBlock(inputBuffer[ch], outputBuffer[ch], numFrames, currentIr, irLen);
        }
    }

    @Override
    public void reset() {
        if (context != null) {
            context.resetFDN();
        }
        currentIr = null;
        needsIrUpdate = true;
    }

    @Override
    public int getInputChannelCount() {
        return 1;
    }

    @Override
    public int getOutputChannelCount() {
        return 1;
    }

    // ----------------------------------------------------------------
    // Acoustics engine initialization
    // ----------------------------------------------------------------

    private void initializeAcousticsEngine() {
        // Clean up previous context
        if (context != null) {
            context.exit();
        }

        int sampleRate = config.sampleRate();

        // Create acoustics configuration
        Coefficients frequencyBands = new Coefficients(
                new double[]{250.0, 500.0, 1000.0, 2000.0, 4000.0});
        acousticsConfig = new Config(sampleRate, DEFAULT_BLOCK_SIZE,
                NUM_REVERB_SOURCES, 2.0, 0.98, frequencyBands);

        // Create context
        context = new Context(acousticsConfig);

        // Build room geometry (rectangular room as 12 triangles — 2 per face)
        buildRoomGeometry();

        // Initialize late reverb
        RoomDimensions dims = config.dimensions();
        double volume = dims.volume();
        double[] dimensions = {dims.width(), dims.length(), dims.height()};
        context.initLateReverb(volume, dimensions, FDNMatrix.HOUSEHOLDER);

        // Set listener position
        if (listenerOrientation != null) {
            Position3D lp = listenerOrientation.position();
            context.updateListener(
                    new Vec3(lp.x(), lp.y(), lp.z()),
                    orientationToVec4(listenerOrientation));
        }

        // Register sources
        sourceIdMap.clear();
        for (SoundSource source : sources) {
            long id = context.initSource();
            sourceIdMap.put(source.name(), id);
            Position3D sp = source.position();
            context.updateSource(id, new Vec3(sp.x(), sp.y(), sp.z()), new Vec4());
        }

        // Update planes and edges for diffraction/image-edge model
        context.updatePlanesAndEdges();
    }

    private void buildRoomGeometry() {
        RoomDimensions dims = config.dimensions();
        double w = dims.width();
        double l = dims.length();
        double h = dims.height();

        wallCount = 0;

        // 8 corners of the room
        Vec3 c000 = new Vec3(0, 0, 0);
        Vec3 cW00 = new Vec3(w, 0, 0);
        Vec3 c0L0 = new Vec3(0, l, 0);
        Vec3 cWL0 = new Vec3(w, l, 0);
        Vec3 c00H = new Vec3(0, 0, h);
        Vec3 cW0H = new Vec3(w, 0, h);
        Vec3 c0LH = new Vec3(0, l, h);
        Vec3 cWLH = new Vec3(w, l, h);

        // Floor (z=0) — 2 triangles
        addWall(new Vec3[]{c000, cW00, cWL0}, surfaceAbsorption("floor"));
        addWall(new Vec3[]{c000, cWL0, c0L0}, surfaceAbsorption("floor"));

        // Ceiling (z=h) — 2 triangles
        addWall(new Vec3[]{c00H, c0LH, cWLH}, surfaceAbsorption("ceiling"));
        addWall(new Vec3[]{c00H, cWLH, cW0H}, surfaceAbsorption("ceiling"));

        // Left wall (x=0) — 2 triangles
        addWall(new Vec3[]{c000, c0L0, c0LH}, surfaceAbsorption("leftWall"));
        addWall(new Vec3[]{c000, c0LH, c00H}, surfaceAbsorption("leftWall"));

        // Right wall (x=w) — 2 triangles
        addWall(new Vec3[]{cW00, cW0H, cWLH}, surfaceAbsorption("rightWall"));
        addWall(new Vec3[]{cW00, cWLH, cWL0}, surfaceAbsorption("rightWall"));

        // Front wall (y=0) — 2 triangles
        addWall(new Vec3[]{c000, c00H, cW0H}, surfaceAbsorption("frontWall"));
        addWall(new Vec3[]{c000, cW0H, cW00}, surfaceAbsorption("frontWall"));

        // Back wall (y=l) — 2 triangles
        addWall(new Vec3[]{c0L0, cWL0, cWLH}, surfaceAbsorption("backWall"));
        addWall(new Vec3[]{c0L0, cWLH, c0LH}, surfaceAbsorption("backWall"));
    }

    private void addWall(Vec3[] vertices, Absorption absorption) {
        long id = context.initWall(vertices, absorption);
        if (wallCount < wallIds.length) {
            wallIds[wallCount++] = id;
        }
    }

    /**
     * Creates a frequency-dependent absorption from the per-surface wall material.
     * Maps the single mid-frequency absorption coefficient to a multi-band
     * absorption profile with physically plausible frequency variation.
     */
    private Absorption surfaceAbsorption(String surfaceName) {
        double alpha = config.materialForSurface(surfaceName).absorptionCoefficient();

        // Create frequency-dependent absorption: lower absorption at low frequencies,
        // higher at high frequencies (typical for most materials)
        double[] bands = new double[NUM_FREQUENCY_BANDS];
        bands[0] = alpha * 0.7;  // 250 Hz — less absorptive at low freq
        bands[1] = alpha * 0.85; // 500 Hz
        bands[2] = alpha;        // 1000 Hz — nominal value
        bands[3] = alpha * 1.1;  // 2000 Hz — more absorptive at high freq
        bands[4] = alpha * 1.15; // 4000 Hz

        // Clamp to [0, 1]
        for (int i = 0; i < bands.length; i++) {
            bands[i] = Math.max(0.0, Math.min(1.0, bands[i]));
        }

        return new Absorption(bands);
    }

    // ----------------------------------------------------------------
    // Early reflections (image-source method)
    // ----------------------------------------------------------------

    private void addEarlyReflections(float[] ir, int sampleRate, RoomDimensions dims) {
        SoundSource source = sources.getFirst();
        Position3D sp = source.position();
        Position3D lp = listenerOrientation.position();

        // Direct sound
        double directDist = sp.distanceTo(lp);
        int directSample = distanceToSample(directDist, sampleRate);
        if (directSample >= 0 && directSample < ir.length) {
            ir[directSample] += (float) (1.0 / Math.max(directDist, 0.01));
        }

        // First-order image sources (6 surfaces)
        Position3D[] images = {
                new Position3D(-sp.x(), sp.y(), sp.z()),                       // left wall (x=0)
                new Position3D(2 * dims.width() - sp.x(), sp.y(), sp.z()),     // right wall (x=width)
                new Position3D(sp.x(), -sp.y(), sp.z()),                       // front wall (y=0)
                new Position3D(sp.x(), 2 * dims.length() - sp.y(), sp.z()),    // back wall (y=length)
                new Position3D(sp.x(), sp.y(), -sp.z()),                       // floor (z=0)
                new Position3D(sp.x(), sp.y(), 2 * dims.height() - sp.z())     // ceiling (z=height)
        };

        for (int i = 0; i < images.length; i++) {
            String surface = RoomSimulationConfig.SURFACE_NAMES.get(i);
            double absorption = config.materialForSurface(surface).absorptionCoefficient();
            double reflectionGain = 1.0 - absorption;

            double dist = images[i].distanceTo(lp);
            int sample = distanceToSample(dist, sampleRate);
            if (sample >= 0 && sample < ir.length) {
                // Apply air absorption attenuation for longer paths
                double airAtten = Math.exp(-0.005 * dist);
                ir[sample] += (float) (reflectionGain * airAtten / Math.max(dist, 0.01));
            }
        }

        // Second-order image sources (wall pairs) for richer early reflections
        addSecondOrderReflections(ir, sampleRate, dims, sp, lp);
    }

    /**
     * Adds second-order image sources (reflections off two walls) for more
     * realistic early reflection density.
     */
    private void addSecondOrderReflections(float[] ir, int sampleRate,
                                            RoomDimensions dims,
                                            Position3D sp, Position3D lp) {
        double w = dims.width();
        double l = dims.length();
        double h = dims.height();

        // Second-order pairs: reflect across two opposite walls
        // x-axis reflections
        Position3D[] secondOrder = {
                new Position3D(-sp.x(), -sp.y(), sp.z()),
                new Position3D(-sp.x(), 2 * l - sp.y(), sp.z()),
                new Position3D(2 * w - sp.x(), -sp.y(), sp.z()),
                new Position3D(2 * w - sp.x(), 2 * l - sp.y(), sp.z()),
                new Position3D(-sp.x(), sp.y(), -sp.z()),
                new Position3D(-sp.x(), sp.y(), 2 * h - sp.z()),
                new Position3D(2 * w - sp.x(), sp.y(), -sp.z()),
                new Position3D(2 * w - sp.x(), sp.y(), 2 * h - sp.z()),
                new Position3D(sp.x(), -sp.y(), -sp.z()),
                new Position3D(sp.x(), -sp.y(), 2 * h - sp.z()),
                new Position3D(sp.x(), 2 * l - sp.y(), -sp.z()),
                new Position3D(sp.x(), 2 * l - sp.y(), 2 * h - sp.z()),
        };

        double avgAbsorption = config.averageAbsorption();
        double reflectionGain = Math.pow(1.0 - avgAbsorption, 2.0);

        for (Position3D image : secondOrder) {
            double dist = image.distanceTo(lp);
            int sample = distanceToSample(dist, sampleRate);
            if (sample >= 0 && sample < ir.length) {
                double airAtten = Math.exp(-0.005 * dist);
                ir[sample] += (float) (reflectionGain * airAtten / Math.max(dist, 0.01));
            }
        }
    }

    // ----------------------------------------------------------------
    // FDN late reverberation synthesis (using acoustics engine)
    // ----------------------------------------------------------------

    private void addFdnReverbTail(float[] ir, int sampleRate, double rt60) {
        // Use the acoustics engine's FDN for late reverberation
        // Synthesize reverb tail starting after early reflections
        int earlyReflEnd = (int) (0.05 * sampleRate); // 50ms early reflection window
        int startSample = Math.min(earlyReflEnd, ir.length);

        if (context == null) return;

        // Reset FDN before synthesis
        context.resetFDN();

        // Process an impulse through the acoustics engine context
        int blockSize = acousticsConfig.numFrames;
        int totalBlocks = (ir.length - startSample + blockSize - 1) / blockSize;

        // Create impulse input
        Buffer inputBuffer = new Buffer(blockSize);
        Buffer outputBuffer = new Buffer(blockSize * 2); // stereo output

        for (int block = 0; block < totalBlocks; block++) {
            inputBuffer.reset();

            // Inject impulse in the first block
            if (block == 0) {
                inputBuffer.set(0, 1.0);
            }

            // Submit audio to first source (if available)
            if (!sourceIdMap.isEmpty()) {
                Long firstId = sourceIdMap.values().iterator().next();
                context.submitAudio(firstId, inputBuffer);
            }

            // Get processed output
            outputBuffer.reset();
            context.getOutput(outputBuffer);

            // Copy stereo output to mono IR (averaging L/R)
            int irOffset = startSample + block * blockSize;
            for (int i = 0; i < blockSize && (irOffset + i) < ir.length; i++) {
                double left = outputBuffer.get(i * 2);
                double right = outputBuffer.get(i * 2 + 1);
                ir[irOffset + i] += (float) ((left + right) * 0.5);
            }
        }
    }

    // ----------------------------------------------------------------
    // Convolution
    // ----------------------------------------------------------------

    private void convolveBlock(float[] input, float[] output, int numFrames,
                               float[] ir, int irLen) {
        Arrays.fill(output, 0, numFrames, 0.0f);
        for (int n = 0; n < numFrames; n++) {
            for (int k = 0; k < irLen && (n - k) >= 0; k++) {
                output[n] += input[n - k] * ir[k];
            }
        }
    }

    // ----------------------------------------------------------------
    // Utility methods
    // ----------------------------------------------------------------

    /**
     * Estimates RT60 using the Sabine equation.
     *
     * @param dims          the room dimensions
     * @param avgAbsorption the average absorption coefficient
     * @return the estimated RT60 in seconds
     */
    public static double estimateRt60(RoomDimensions dims, double avgAbsorption) {
        double volume = dims.volume();
        double surfaceArea = dims.surfaceArea();
        double totalAbsorption = surfaceArea * avgAbsorption;
        if (totalAbsorption <= 0) {
            return Double.MAX_VALUE;
        }
        return 0.161 * volume / totalAbsorption;
    }

    private static int distanceToSample(double distanceMeters, int sampleRate) {
        return (int) ((distanceMeters / SPEED_OF_SOUND_MPS) * sampleRate);
    }

    private static void normalizeIr(float[] ir) {
        float maxAbs = 0.0f;
        for (float sample : ir) {
            float abs = Math.abs(sample);
            if (abs > maxAbs) {
                maxAbs = abs;
            }
        }
        if (maxAbs > 0.0f) {
            float scale = 1.0f / maxAbs;
            for (int i = 0; i < ir.length; i++) {
                ir[i] *= scale;
            }
        }
    }

    private static Vec4 orientationToVec4(ListenerOrientation orientation) {
        double yawRad = Definitions.deg2Rad(orientation.yawDegrees());
        double pitchRad = Definitions.deg2Rad(orientation.pitchDegrees());
        return new Vec4(Math.cos(yawRad) * Math.cos(pitchRad),
                Math.sin(yawRad) * Math.cos(pitchRad),
                Math.sin(pitchRad), 0.0);
    }

    private void ensureConfigured() {
        if (config == null) {
            throw new IllegalStateException("Simulator has not been configured; call configure() first");
        }
    }
}
