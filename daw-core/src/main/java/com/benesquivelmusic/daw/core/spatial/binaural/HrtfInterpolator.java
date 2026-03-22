package com.benesquivelmusic.daw.core.spatial.binaural;

import com.benesquivelmusic.daw.sdk.spatial.HrtfData;
import com.benesquivelmusic.daw.sdk.spatial.SphericalCoordinate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Interpolates HRTF impulse responses for arbitrary source directions
 * not present in the measured dataset.
 *
 * <p>Uses inverse-distance weighted (IDW) interpolation on the unit sphere
 * with the K nearest measured directions. This provides smooth transitions
 * between measured positions while preserving spectral characteristics.</p>
 */
public final class HrtfInterpolator {

    /** Result of HRTF interpolation for a given direction. */
    public record InterpolatedHrtf(float[] leftIr, float[] rightIr,
                                   float leftDelay, float rightDelay) {}

    private static final int DEFAULT_NEIGHBOR_COUNT = 3;
    private static final double EXACT_MATCH_THRESHOLD = 0.1; // degrees

    private final HrtfData hrtfData;
    private final int neighborCount;

    /**
     * Creates an interpolator for the given HRTF dataset.
     *
     * @param hrtfData       the HRTF dataset containing measured impulse responses
     * @param neighborCount  number of nearest neighbors to use for interpolation
     */
    public HrtfInterpolator(HrtfData hrtfData, int neighborCount) {
        if (hrtfData == null) {
            throw new IllegalArgumentException("hrtfData must not be null");
        }
        if (neighborCount < 1) {
            throw new IllegalArgumentException("neighborCount must be at least 1: " + neighborCount);
        }
        this.hrtfData = hrtfData;
        this.neighborCount = Math.min(neighborCount, hrtfData.measurementCount());
    }

    /**
     * Creates an interpolator with the default neighbor count (3).
     *
     * @param hrtfData the HRTF dataset
     */
    public HrtfInterpolator(HrtfData hrtfData) {
        this(hrtfData, DEFAULT_NEIGHBOR_COUNT);
    }

    /**
     * Interpolates HRTF impulse responses for the given source direction.
     *
     * <p>If the target direction exactly matches a measured position (within
     * {@value EXACT_MATCH_THRESHOLD}°), the measured data is returned
     * directly without interpolation.</p>
     *
     * @param target the desired source direction
     * @return the interpolated left/right impulse responses and delays
     */
    public InterpolatedHrtf interpolate(SphericalCoordinate target) {
        List<SphericalCoordinate> positions = hrtfData.sourcePositions();
        int irLen = hrtfData.irLength();

        // Compute angular distances and find K nearest neighbors
        record Neighbor(int index, double distance) {}
        List<Neighbor> neighbors = new ArrayList<>(positions.size());
        for (int i = 0; i < positions.size(); i++) {
            double dist = target.angularDistanceTo(positions.get(i));
            neighbors.add(new Neighbor(i, dist));
        }
        neighbors.sort(Comparator.comparingDouble(Neighbor::distance));

        // Check for exact match
        if (neighbors.getFirst().distance() < EXACT_MATCH_THRESHOLD) {
            int idx = neighbors.getFirst().index();
            return new InterpolatedHrtf(
                    hrtfData.impulseResponses()[idx][0].clone(),
                    hrtfData.impulseResponses()[idx][1].clone(),
                    hrtfData.delays()[idx][0],
                    hrtfData.delays()[idx][1]);
        }

        // Inverse-distance weighted interpolation
        List<Neighbor> nearest = neighbors.subList(0, neighborCount);
        double[] weights = new double[neighborCount];
        double weightSum = 0;
        for (int i = 0; i < neighborCount; i++) {
            weights[i] = 1.0 / Math.max(nearest.get(i).distance(), 1e-10);
            weightSum += weights[i];
        }
        for (int i = 0; i < neighborCount; i++) {
            weights[i] /= weightSum;
        }

        float[] leftIr = new float[irLen];
        float[] rightIr = new float[irLen];
        float leftDelay = 0;
        float rightDelay = 0;

        for (int i = 0; i < neighborCount; i++) {
            int idx = nearest.get(i).index();
            double w = weights[i];

            float[] srcLeft = hrtfData.impulseResponses()[idx][0];
            float[] srcRight = hrtfData.impulseResponses()[idx][1];

            for (int s = 0; s < irLen; s++) {
                leftIr[s] += (float) (srcLeft[s] * w);
                rightIr[s] += (float) (srcRight[s] * w);
            }

            leftDelay += (float) (hrtfData.delays()[idx][0] * w);
            rightDelay += (float) (hrtfData.delays()[idx][1] * w);
        }

        return new InterpolatedHrtf(leftIr, rightIr, leftDelay, rightDelay);
    }
}
