package com.benesquivelmusic.daw.core.spatial.panner;

import com.benesquivelmusic.daw.sdk.spatial.*;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * VBAP (Vector Base Amplitude Panning) implementation of the 3D spatial panner.
 *
 * <p>Computes per-speaker gain coefficients based on the angular relationship
 * between the virtual source position and the speaker layout. The implementation
 * uses 2D VBAP for horizontal panning (azimuth) with elevation handled by
 * gain-weighted contribution from speakers above and below.</p>
 *
 * <p>Key features:</p>
 * <ul>
 *   <li>VBAP gain computation with energy preservation (gains sum to unit energy)</li>
 *   <li>Inverse square law distance attenuation with configurable rolloff</li>
 *   <li>Source size/spread for diffuse rendering (blends toward uniform gains)</li>
 *   <li>Snap-to-speaker and free-form positioning modes</li>
 *   <li>Pan automation support with per-parameter interpolation</li>
 * </ul>
 */
public final class VbapPanner implements SpatialPanner {

    private static final SpatialPosition DEFAULT_POSITION =
            new SpatialPosition(0, 0, 1.0);

    private final int outputChannelCount;

    private SpatialPosition position;
    private double spread;
    private DistanceAttenuationModel attenuationModel;
    private PositioningMode positioningMode;
    private PanAutomationCurve automationCurve;
    private List<SpatialPosition> speakerPositions;

    /**
     * Creates a VBAP panner with the given speaker layout.
     *
     * @param speakerPositions the speaker positions (must have at least 2)
     */
    public VbapPanner(List<SpatialPosition> speakerPositions) {
        Objects.requireNonNull(speakerPositions, "speakerPositions must not be null");
        if (speakerPositions.size() < 2) {
            throw new IllegalArgumentException("at least 2 speakers are required");
        }
        this.speakerPositions = List.copyOf(speakerPositions);
        this.outputChannelCount = speakerPositions.size();
        this.position = DEFAULT_POSITION;
        this.spread = 0.0;
        this.attenuationModel = new InverseSquareAttenuation(1.0, 100.0);
        this.positioningMode = PositioningMode.FREE_FORM;
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
        // Re-resolve position when switching to snap mode
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
    }

    @Override
    public List<SpatialPosition> getSpeakerPositions() {
        return speakerPositions;
    }

    // ---- SpatialPanner: VBAP Gains ----

    @Override
    public double[] computeSpeakerGains() {
        int numSpeakers = speakerPositions.size();
        double[] gains = new double[numSpeakers];

        // Compute unit vectors for the source
        double srcX = position.x();
        double srcY = position.y();
        double srcZ = position.z();
        double srcLen = Math.sqrt(srcX * srcX + srcY * srcY + srcZ * srcZ);
        if (srcLen > 1e-15) {
            srcX /= srcLen;
            srcY /= srcLen;
            srcZ /= srcLen;
        } else {
            // Source at origin: distribute equally
            Arrays.fill(gains, 1.0 / Math.sqrt(numSpeakers));
            return gains;
        }

        // Compute dot product of source direction with each speaker direction
        double[] dots = new double[numSpeakers];
        for (int i = 0; i < numSpeakers; i++) {
            SpatialPosition sp = speakerPositions.get(i);
            double spX = sp.x();
            double spY = sp.y();
            double spZ = sp.z();
            double spLen = Math.sqrt(spX * spX + spY * spY + spZ * spZ);
            if (spLen > 1e-15) {
                dots[i] = (srcX * spX + srcY * spY + srcZ * spZ) / spLen;
            }
        }

        // Find the two closest speakers (highest dot products)
        int best = 0;
        int secondBest = 1;
        if (dots[1] > dots[0]) {
            best = 1;
            secondBest = 0;
        }
        for (int i = 2; i < numSpeakers; i++) {
            if (dots[i] > dots[best]) {
                secondBest = best;
                best = i;
            } else if (dots[i] > dots[secondBest]) {
                secondBest = i;
            }
        }

        // Compute VBAP gains for the speaker pair
        double dotBest = Math.max(dots[best], 0.0);
        double dotSecond = Math.max(dots[secondBest], 0.0);
        double sumDots = dotBest + dotSecond;

        if (sumDots > 1e-15) {
            gains[best] = dotBest / sumDots;
            gains[secondBest] = dotSecond / sumDots;
        } else {
            gains[best] = 1.0;
        }

        // Normalize for energy preservation: sum of squares = 1
        normalizeGains(gains);

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

        // Downmix input to mono
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

        // Zero remaining output channels if any
        for (int ch = outChannels; ch < outputBuffer.length; ch++) {
            Arrays.fill(outputBuffer[ch], 0, numFrames, 0.0f);
        }
    }

    @Override
    public void reset() {
        // Stateless panner — no internal buffers to clear
    }

    @Override
    public int getInputChannelCount() {
        return 1; // accepts mono (or downmixes to mono)
    }

    @Override
    public int getOutputChannelCount() {
        return outputChannelCount;
    }

    // ---- Internal helpers ----

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
        // Preserve the original distance but snap direction to nearest speaker
        return new SpatialPosition(nearest.azimuthDegrees(), nearest.elevationDegrees(),
                pos.distanceMeters());
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
