package com.benesquivelmusic.daw.core.spatial.panner;

import com.benesquivelmusic.daw.sdk.spatial.DistanceAttenuationModel;
import com.benesquivelmusic.daw.sdk.spatial.PanAutomationCurve;
import com.benesquivelmusic.daw.sdk.spatial.PositioningMode;
import com.benesquivelmusic.daw.sdk.spatial.SpatialPanner;
import com.benesquivelmusic.daw.sdk.spatial.SpatialPannerData;
import com.benesquivelmusic.daw.sdk.spatial.SpatialPosition;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Panning table synthesis implementation that emulates VBAP behavior
 * while handling irregular and degenerate speaker layouts.
 *
 * <p>Pre-computes a gain lookup table at configurable angular resolution
 * (azimuth × elevation) using the existing {@link VbapPanner}. At runtime,
 * source positions are resolved via bilinear interpolation of the
 * pre-computed table, providing smooth panning for any source direction
 * without requiring real-time triangulation.</p>
 *
 * <p>This approach handles irregular layouts gracefully — missing speakers,
 * non-symmetric arrangements, and height-only arrays — because the table
 * is populated by querying VBAP at every grid point, and degenerate entries
 * are repaired with nearest-neighbor interpolation from valid neighbors.</p>
 *
 * <p>References:</p>
 * <ul>
 *   <li>"Emulating Vector Base Amplitude Panning Using Panning Table
 *       Synthesis" (AES, 2023)</li>
 *   <li>"Multichannel Compensated Amplitude Panning" (AES, 2019)</li>
 *   <li>"Immersive Audio Reproduction and Adaptability for Irregular
 *       Loudspeaker Layouts" (AES, 2024)</li>
 * </ul>
 */
public final class PanningTableSynthesizer implements SpatialPanner {

    /** Default angular resolution in degrees. */
    public static final double DEFAULT_RESOLUTION = 1.0;

    private static final SpatialPosition DEFAULT_POSITION =
            new SpatialPosition(0, 0, 1.0);

    private final int outputChannelCount;
    private final double azimuthResolution;
    private final double elevationResolution;
    private final int azimuthSteps;
    private final int elevationSteps;

    private double[][][] gainTable; // [azimuthIndex][elevationIndex][speaker]

    private SpatialPosition position;
    private double spread;
    private DistanceAttenuationModel attenuationModel;
    private PositioningMode positioningMode;
    private PanAutomationCurve automationCurve;
    private List<SpatialPosition> speakerPositions;

    /**
     * Creates a panning table synthesizer with the given speaker layout
     * and the default angular resolution of 1°.
     *
     * @param speakerPositions the speaker positions (must have at least 2)
     */
    public PanningTableSynthesizer(List<SpatialPosition> speakerPositions) {
        this(speakerPositions, DEFAULT_RESOLUTION, DEFAULT_RESOLUTION);
    }

    /**
     * Creates a panning table synthesizer with the given speaker layout
     * and angular resolution.
     *
     * @param speakerPositions    the speaker positions (must have at least 2)
     * @param azimuthResolution   the azimuth grid resolution in degrees (must be in (0, 360])
     * @param elevationResolution the elevation grid resolution in degrees (must be in (0, 180])
     */
    public PanningTableSynthesizer(List<SpatialPosition> speakerPositions,
                                   double azimuthResolution,
                                   double elevationResolution) {
        Objects.requireNonNull(speakerPositions, "speakerPositions must not be null");
        if (speakerPositions.size() < 2) {
            throw new IllegalArgumentException("at least 2 speakers are required");
        }
        if (azimuthResolution <= 0 || azimuthResolution > 360) {
            throw new IllegalArgumentException(
                    "azimuthResolution must be in (0, 360]: " + azimuthResolution);
        }
        if (elevationResolution <= 0 || elevationResolution > 180) {
            throw new IllegalArgumentException(
                    "elevationResolution must be in (0, 180]: " + elevationResolution);
        }

        this.speakerPositions = List.copyOf(speakerPositions);
        this.outputChannelCount = speakerPositions.size();
        this.azimuthResolution = azimuthResolution;
        this.elevationResolution = elevationResolution;
        this.azimuthSteps = (int) Math.ceil(360.0 / azimuthResolution);
        this.elevationSteps = (int) Math.ceil(180.0 / elevationResolution) + 1;
        this.position = DEFAULT_POSITION;
        this.spread = 0.0;
        this.attenuationModel = new InverseSquareAttenuation(1.0, 100.0);
        this.positioningMode = PositioningMode.FREE_FORM;

        buildTable();
    }

    // ---- Table Access ----

    /**
     * Returns the number of azimuth grid steps in the pre-computed table.
     *
     * @return the azimuth step count
     */
    public int getAzimuthSteps() {
        return azimuthSteps;
    }

    /**
     * Returns the number of elevation grid steps in the pre-computed table.
     *
     * @return the elevation step count
     */
    public int getElevationSteps() {
        return elevationSteps;
    }

    /**
     * Returns the azimuth angular resolution in degrees.
     *
     * @return the azimuth resolution
     */
    public double getAzimuthResolution() {
        return azimuthResolution;
    }

    /**
     * Returns the elevation angular resolution in degrees.
     *
     * @return the elevation resolution
     */
    public double getElevationResolution() {
        return elevationResolution;
    }

    // ---- SpatialPanner: Position ----

    @Override
    public void setPosition(SpatialPosition position) {
        Objects.requireNonNull(position, "position must not be null");
        this.position = resolvePosition(position);
    }

    @Override
    public SpatialPosition getPosition() {
        return position;
    }

    // ---- SpatialPanner: Spread ----

    @Override
    public void setSpread(double spread) {
        if (spread < 0.0 || spread > 1.0) {
            throw new IllegalArgumentException("spread must be in [0, 1]: " + spread);
        }
        this.spread = spread;
    }

    @Override
    public double getSpread() {
        return spread;
    }

    // ---- SpatialPanner: Distance Attenuation ----

    @Override
    public void setDistanceAttenuationModel(DistanceAttenuationModel model) {
        Objects.requireNonNull(model, "model must not be null");
        this.attenuationModel = model;
    }

    @Override
    public DistanceAttenuationModel getDistanceAttenuationModel() {
        return attenuationModel;
    }

    // ---- SpatialPanner: Positioning Mode ----

    @Override
    public void setPositioningMode(PositioningMode mode) {
        Objects.requireNonNull(mode, "mode must not be null");
        this.positioningMode = mode;
        if (mode == PositioningMode.SNAP_TO_SPEAKER) {
            this.position = resolvePosition(this.position);
        }
    }

    @Override
    public PositioningMode getPositioningMode() {
        return positioningMode;
    }

    // ---- SpatialPanner: Automation ----

    @Override
    public void setAutomationCurve(PanAutomationCurve curve) {
        this.automationCurve = curve;
    }

    @Override
    public PanAutomationCurve getAutomationCurve() {
        return automationCurve;
    }

    // ---- SpatialPanner: Speakers ----

    @Override
    public void setSpeakerPositions(List<SpatialPosition> speakers) {
        Objects.requireNonNull(speakers, "speakers must not be null");
        if (speakers.size() < 2) {
            throw new IllegalArgumentException("at least 2 speakers are required");
        }
        this.speakerPositions = List.copyOf(speakers);
        buildTable();
    }

    @Override
    public List<SpatialPosition> getSpeakerPositions() {
        return speakerPositions;
    }

    // ---- SpatialPanner: Gains ----

    @Override
    public double[] computeSpeakerGains() {
        int numSpeakers = speakerPositions.size();
        double azimuth = normalizeAzimuth(position.azimuthDegrees());
        double elevation = position.elevationDegrees();

        double[] gains = interpolateGains(azimuth, elevation);

        // Apply spread: blend toward uniform distribution
        if (spread > 0.0) {
            double uniformGain = 1.0 / Math.sqrt(numSpeakers);
            for (int i = 0; i < numSpeakers; i++) {
                gains[i] = gains[i] * (1.0 - spread) + uniformGain * spread;
            }
            normalizeGains(gains);
        }

        return gains;
    }

    // ---- SpatialPanner: Visualization ----

    @Override
    public SpatialPannerData getPannerData() {
        double distGain = attenuationModel.computeGain(position.distanceMeters());
        double hfRolloff = attenuationModel.computeHighFrequencyRolloff(position.distanceMeters());
        double reverbSend = attenuationModel.computeReverbSend(position.distanceMeters());

        return new SpatialPannerData(
                position,
                speakerPositions,
                computeSpeakerGains(),
                spread,
                distGain,
                hfRolloff,
                reverbSend,
                positioningMode
        );
    }

    // ---- AudioProcessor ----

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        double[] gains = computeSpeakerGains();
        double distGain = attenuationModel.computeGain(position.distanceMeters());

        int inputChannels = inputBuffer.length;
        int outChannels = Math.min(outputBuffer.length, speakerPositions.size());

        for (int ch = 0; ch < outChannels; ch++) {
            float gainF = (float) (gains[ch] * distGain);
            for (int i = 0; i < numFrames; i++) {
                float mono = 0;
                for (int inCh = 0; inCh < inputChannels; inCh++) {
                    mono += inputBuffer[inCh][i];
                }
                if (inputChannels > 0) {
                    mono /= inputChannels;
                }
                outputBuffer[ch][i] = mono * gainF;
            }
        }

        for (int ch = outChannels; ch < outputBuffer.length; ch++) {
            Arrays.fill(outputBuffer[ch], 0, numFrames, 0.0f);
        }
    }

    @Override
    public void reset() {
        // Stateless — the pre-computed table does not change between resets
    }

    @Override
    public int getInputChannelCount() {
        return 1;
    }

    @Override
    public int getOutputChannelCount() {
        return outputChannelCount;
    }

    // ---- Table Construction ----

    private void buildTable() {
        int numSpeakers = speakerPositions.size();
        gainTable = new double[azimuthSteps][elevationSteps][numSpeakers];

        VbapPanner vbap = new VbapPanner(speakerPositions);

        for (int ai = 0; ai < azimuthSteps; ai++) {
            double azimuth = ai * azimuthResolution;
            for (int ei = 0; ei < elevationSteps; ei++) {
                double elevation = -90.0 + ei * elevationResolution;
                elevation = Math.min(elevation, 90.0);

                vbap.setPosition(new SpatialPosition(azimuth, elevation, 1.0));
                double[] gains = vbap.computeSpeakerGains();
                gainTable[ai][ei] = gains;
            }
        }

        repairDegenerateEntries();
    }

    /**
     * Repairs degenerate grid entries (all-zero gains) by copying
     * gains from the nearest valid neighbor. This handles cases where
     * VBAP triangulation fails for certain directions.
     */
    private void repairDegenerateEntries() {
        int numSpeakers = speakerPositions.size();

        for (int ai = 0; ai < azimuthSteps; ai++) {
            for (int ei = 0; ei < elevationSteps; ei++) {
                if (isDegenerate(gainTable[ai][ei])) {
                    double[] nearest = findNearestValidGains(ai, ei);
                    if (nearest != null) {
                        System.arraycopy(nearest, 0, gainTable[ai][ei], 0, numSpeakers);
                    } else {
                        // Fallback: uniform distribution
                        double uniform = 1.0 / Math.sqrt(numSpeakers);
                        Arrays.fill(gainTable[ai][ei], uniform);
                    }
                }
            }
        }
    }

    private boolean isDegenerate(double[] gains) {
        double sumSq = 0;
        for (double g : gains) {
            sumSq += g * g;
        }
        return sumSq < 1e-15;
    }

    private double[] findNearestValidGains(int azIdx, int elIdx) {
        // Search in expanding rings around the degenerate cell
        int maxRadius = Math.max(azimuthSteps, elevationSteps);
        for (int radius = 1; radius <= maxRadius; radius++) {
            for (int da = -radius; da <= radius; da++) {
                for (int de = -radius; de <= radius; de++) {
                    if (Math.abs(da) != radius && Math.abs(de) != radius) {
                        continue; // only check the ring boundary
                    }
                    int ai = (azIdx + da) % azimuthSteps;
                    if (ai < 0) ai += azimuthSteps;
                    int ei = elIdx + de;
                    if (ei < 0 || ei >= elevationSteps) continue;

                    if (!isDegenerate(gainTable[ai][ei])) {
                        return gainTable[ai][ei];
                    }
                }
            }
        }
        return null;
    }

    // ---- Bilinear Interpolation ----

    private double[] interpolateGains(double azimuth, double elevation) {
        int numSpeakers = speakerPositions.size();

        // Compute fractional indices
        double azFrac = azimuth / azimuthResolution;
        double elFrac = (elevation + 90.0) / elevationResolution;

        int azLow = (int) Math.floor(azFrac);
        int elLow = (int) Math.floor(elFrac);

        double azT = azFrac - azLow;
        double elT = elFrac - elLow;

        // Wrap/clamp indices
        int az0 = azLow % azimuthSteps;
        if (az0 < 0) az0 += azimuthSteps;
        int az1 = (az0 + 1) % azimuthSteps;

        int el0 = Math.max(0, Math.min(elLow, elevationSteps - 1));
        int el1 = Math.min(el0 + 1, elevationSteps - 1);

        // Bilinear interpolation
        double[] gains = new double[numSpeakers];
        double w00 = (1.0 - azT) * (1.0 - elT);
        double w10 = azT * (1.0 - elT);
        double w01 = (1.0 - azT) * elT;
        double w11 = azT * elT;

        for (int s = 0; s < numSpeakers; s++) {
            gains[s] = w00 * gainTable[az0][el0][s]
                     + w10 * gainTable[az1][el0][s]
                     + w01 * gainTable[az0][el1][s]
                     + w11 * gainTable[az1][el1][s];
        }

        normalizeGains(gains);
        return gains;
    }

    // ---- Helpers ----

    private SpatialPosition resolvePosition(SpatialPosition pos) {
        if (positioningMode == PositioningMode.SNAP_TO_SPEAKER && !speakerPositions.isEmpty()) {
            return findNearestSpeaker(pos);
        }
        return pos;
    }

    private SpatialPosition findNearestSpeaker(SpatialPosition pos) {
        SpatialPosition nearest = speakerPositions.getFirst();
        double minAngle = pos.angularDistanceTo(nearest);
        for (int i = 1; i < speakerPositions.size(); i++) {
            double angle = pos.angularDistanceTo(speakerPositions.get(i));
            if (angle < minAngle) {
                minAngle = angle;
                nearest = speakerPositions.get(i);
            }
        }
        return new SpatialPosition(nearest.azimuthDegrees(), nearest.elevationDegrees(),
                pos.distanceMeters());
    }

    private static double normalizeAzimuth(double azimuth) {
        double az = azimuth % 360.0;
        if (az < 0) az += 360.0;
        return az;
    }

    private static void normalizeGains(double[] gains) {
        double sumSq = 0;
        for (double g : gains) {
            sumSq += g * g;
        }
        if (sumSq > 1e-15) {
            double scale = 1.0 / Math.sqrt(sumSq);
            for (int i = 0; i < gains.length; i++) {
                gains[i] *= scale;
            }
        }
    }
}
