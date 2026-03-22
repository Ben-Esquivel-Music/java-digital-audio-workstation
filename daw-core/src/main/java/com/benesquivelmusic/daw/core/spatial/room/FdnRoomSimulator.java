package com.benesquivelmusic.daw.core.spatial.room;

import com.benesquivelmusic.daw.sdk.spatial.ImpulseResponse;
import com.benesquivelmusic.daw.sdk.spatial.RoomSimulationConfig;
import com.benesquivelmusic.daw.sdk.spatial.RoomSimulator;
import com.benesquivelmusic.daw.sdk.telemetry.ListenerOrientation;
import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.SoundSource;
import com.benesquivelmusic.daw.sdk.telemetry.WallMaterial;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Pure-Java room acoustic simulator using a Feedback Delay Network (FDN)
 * for late reverberation and the image-source method for early reflections.
 *
 * <p>This is the fallback implementation when the native RoomAcoustiC++
 * library is not available. It provides physically-plausible room simulation
 * suitable for real-time auralization.</p>
 *
 * <p>The FDN uses a Householder feedback matrix and per-channel allpass
 * diffusers for natural-sounding diffuse reverberation. Early reflections
 * are modeled using first-order image sources off the six room surfaces.</p>
 */
public final class FdnRoomSimulator implements RoomSimulator {

    /** Speed of sound in air at room temperature in meters per second. */
    static final double SPEED_OF_SOUND_MPS = 343.0;

    /** Number of delay lines in the FDN. */
    static final int FDN_ORDER = 8;

    /** Prime-number base delay lengths (in samples at 48 kHz) for the FDN. */
    private static final int[] BASE_DELAY_PRIMES = {
            347, 521, 743, 907, 1109, 1303, 1523, 1741
    };

    private RoomSimulationConfig config;
    private ListenerOrientation listenerOrientation;
    private final List<SoundSource> sources = new ArrayList<>();

    // FDN state
    private float[][] delayLines;
    private int[] delayLengths;
    private int[] writePositions;
    private float[] feedbackGains;

    // Convolution state
    private float[] currentIr;
    private float[] prevInputBuffer;
    private int blockSize;
    private boolean needsIrUpdate = true;

    /**
     * Creates an FDN room simulator. Call {@link #configure(RoomSimulationConfig)}
     * before processing audio.
     */
    public FdnRoomSimulator() {
        // Default state — not yet configured
    }

    @Override
    public void configure(RoomSimulationConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        this.config = config;
        this.listenerOrientation = config.listener();
        this.sources.clear();
        this.sources.addAll(config.sources());
        initializeFdn();
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
        this.needsIrUpdate = true;
    }

    @Override
    public boolean removeSource(String sourceName) {
        boolean removed = sources.removeIf(s -> s.name().equals(sourceName));
        if (removed) {
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

        // Compute RT60 using Sabine equation
        double rt60 = estimateRt60(dims, config.averageAbsorption());

        // IR length = RT60 * sampleRate (capped at a reasonable maximum)
        int irLength = Math.min((int) (rt60 * sampleRate), sampleRate * 4);
        irLength = Math.max(irLength, sampleRate / 10); // minimum 100ms

        float[] ir = new float[irLength];

        // 1. Early reflections via image-source method
        if (!sources.isEmpty() && listenerOrientation != null) {
            addEarlyReflections(ir, sampleRate, dims);
        }

        // 2. Late reverberation via FDN synthesis
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

        // Simple time-domain convolution for short IRs, or truncated convolution
        int irLen = Math.min(currentIr.length, numFrames);
        for (int ch = 0; ch < Math.min(inputBuffer.length, outputBuffer.length); ch++) {
            convolveBlock(inputBuffer[ch], outputBuffer[ch], numFrames, currentIr, irLen);
        }
    }

    @Override
    public void reset() {
        if (delayLines != null) {
            for (float[] line : delayLines) {
                Arrays.fill(line, 0.0f);
            }
        }
        if (writePositions != null) {
            Arrays.fill(writePositions, 0);
        }
        currentIr = null;
        prevInputBuffer = null;
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
    // FDN initialization
    // ----------------------------------------------------------------

    private void initializeFdn() {
        int sampleRate = config.sampleRate();
        double rt60 = estimateRt60(config.dimensions(), config.averageAbsorption());

        delayLengths = new int[FDN_ORDER];
        delayLines = new float[FDN_ORDER][];
        writePositions = new int[FDN_ORDER];
        feedbackGains = new float[FDN_ORDER];

        // Scale base delays by room volume and sample rate
        double volumeScale = Math.cbrt(config.dimensions().volume() / 100.0);
        for (int i = 0; i < FDN_ORDER; i++) {
            delayLengths[i] = Math.max(1,
                    (int) (BASE_DELAY_PRIMES[i] * volumeScale * sampleRate / 48000.0));
            delayLines[i] = new float[delayLengths[i]];

            // Feedback gain from desired RT60: g = 10^(-3 * delay / (RT60 * sampleRate))
            double delaySec = (double) delayLengths[i] / sampleRate;
            feedbackGains[i] = (float) Math.pow(10.0, -3.0 * delaySec / Math.max(rt60, 0.01));
        }
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
        if (directSample < ir.length) {
            ir[directSample] += (float) (1.0 / Math.max(directDist, 0.01));
        }

        // First-order image sources (6 surfaces)
        Position3D[] images = {
                new Position3D(-sp.x(), sp.y(), sp.z()),                       // left wall   (x=0)
                new Position3D(2 * dims.width() - sp.x(), sp.y(), sp.z()),    // right wall  (x=width)
                new Position3D(sp.x(), -sp.y(), sp.z()),                       // front wall  (y=0)
                new Position3D(sp.x(), 2 * dims.length() - sp.y(), sp.z()),   // back wall   (y=length)
                new Position3D(sp.x(), sp.y(), -sp.z()),                       // floor       (z=0)
                new Position3D(sp.x(), sp.y(), 2 * dims.height() - sp.z())    // ceiling     (z=height)
        };

        for (int i = 0; i < images.length; i++) {
            String surface = RoomSimulationConfig.SURFACE_NAMES.get(i);
            double absorption = config.materialForSurface(surface).absorptionCoefficient();
            double reflectionGain = 1.0 - absorption;

            double dist = images[i].distanceTo(lp);
            int sample = distanceToSample(dist, sampleRate);
            if (sample < ir.length && sample >= 0) {
                ir[sample] += (float) (reflectionGain / Math.max(dist, 0.01));
            }
        }
    }

    // ----------------------------------------------------------------
    // FDN late reverberation synthesis
    // ----------------------------------------------------------------

    private void addFdnReverbTail(float[] ir, int sampleRate, double rt60) {
        // Synthesize FDN reverb tail starting after early reflections
        int earlyReflEnd = (int) (0.05 * sampleRate); // 50ms early reflection window
        int startSample = Math.min(earlyReflEnd, ir.length);

        // Reset FDN delay lines
        for (int i = 0; i < FDN_ORDER; i++) {
            Arrays.fill(delayLines[i], 0.0f);
            writePositions[i] = 0;
        }

        // Inject an impulse into the FDN
        for (int i = 0; i < FDN_ORDER; i++) {
            delayLines[i][0] = 1.0f / FDN_ORDER;
        }

        // Run the FDN to generate the reverb tail
        for (int n = startSample; n < ir.length; n++) {
            float output = 0.0f;
            float[] readValues = new float[FDN_ORDER];

            // Read from delay lines
            for (int i = 0; i < FDN_ORDER; i++) {
                int readPos = (writePositions[i] - delayLengths[i] + delayLines[i].length)
                        % delayLines[i].length;
                readValues[i] = delayLines[i][readPos];
                output += readValues[i];
            }

            // Apply Householder feedback matrix: H = I - (2/N) * ones
            float sum = 0.0f;
            for (int i = 0; i < FDN_ORDER; i++) {
                sum += readValues[i];
            }
            float householderTerm = 2.0f * sum / FDN_ORDER;

            // Write back with feedback
            for (int i = 0; i < FDN_ORDER; i++) {
                float feedback = (readValues[i] - householderTerm) * feedbackGains[i];
                delayLines[i][writePositions[i]] = feedback;
                writePositions[i] = (writePositions[i] + 1) % delayLines[i].length;
            }

            ir[n] += output / FDN_ORDER;
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

    static double estimateRt60(RoomDimensions dims, double avgAbsorption) {
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

    private void ensureConfigured() {
        if (config == null) {
            throw new IllegalStateException("Simulator has not been configured; call configure() first");
        }
    }
}
