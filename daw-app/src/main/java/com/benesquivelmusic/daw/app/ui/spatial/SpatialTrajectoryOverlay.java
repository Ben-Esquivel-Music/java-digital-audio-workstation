package com.benesquivelmusic.daw.app.ui.spatial;

import com.benesquivelmusic.daw.core.automation.AutomationData;
import com.benesquivelmusic.daw.core.automation.AutomationLane;
import com.benesquivelmusic.daw.core.automation.ObjectParameterTarget;
import com.benesquivelmusic.daw.sdk.spatial.ObjectParameter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Headless sampler for the 3D spatial panner trajectory overlay
 * (story 172). Given an object instance and its X / Y / Z automation lanes,
 * this class produces a sequence of normalised 3D positions covering a
 * configurable window before and after the playhead — exactly what the
 * trajectory-overlay renderer paints over the panner.
 *
 * <p>Computation is intentionally pure (no JavaFX, no scene graph) so that
 * it is unit-testable in a headless environment and reusable from
 * non-JavaFX render targets such as Atmos preview.</p>
 *
 * <p>Each {@link Point3D} is a snapshot of {@code (x, y, z)} sampled at a
 * specific beat position; values fall back to {@link ObjectParameter}'s
 * defaults when the corresponding lane has no breakpoints.</p>
 */
public final class SpatialTrajectoryOverlay {

    /** Default look-ahead window in bars (story 172: configurable, default 4). */
    public static final double DEFAULT_LOOK_AHEAD_BARS = 4.0;
    /** Default look-back window in bars for the dimmer "past" trail. */
    public static final double DEFAULT_LOOK_BEHIND_BARS = 1.0;
    /** Default number of trajectory samples (resolution). */
    public static final int DEFAULT_RESOLUTION = 64;

    private final AutomationData automationData;
    private final String objectInstanceId;
    private final double beatsPerBar;

    private double lookAheadBars = DEFAULT_LOOK_AHEAD_BARS;
    private double lookBehindBars = DEFAULT_LOOK_BEHIND_BARS;
    private int resolution = DEFAULT_RESOLUTION;

    /**
     * Creates a sampler bound to the given automation data and object id.
     *
     * @param automationData     the automation container holding the lanes
     * @param objectInstanceId   the panner instance id (must match the
     *                           {@link ObjectParameterTarget#objectInstanceId()}
     *                           used when creating lanes)
     * @param beatsPerBar        the time-signature numerator (e.g. 4 for 4/4)
     */
    public SpatialTrajectoryOverlay(AutomationData automationData,
                                    String objectInstanceId,
                                    double beatsPerBar) {
        this.automationData = Objects.requireNonNull(automationData,
                "automationData must not be null");
        this.objectInstanceId = Objects.requireNonNull(objectInstanceId,
                "objectInstanceId must not be null");
        if (beatsPerBar <= 0.0) {
            throw new IllegalArgumentException(
                    "beatsPerBar must be > 0: " + beatsPerBar);
        }
        this.beatsPerBar = beatsPerBar;
    }

    /** Sets the future-trajectory window in bars (must be {@code > 0}). */
    public void setLookAheadBars(double bars) {
        if (bars <= 0.0) {
            throw new IllegalArgumentException("bars must be > 0: " + bars);
        }
        this.lookAheadBars = bars;
    }

    /** Sets the past-trajectory window in bars (must be {@code >= 0}). */
    public void setLookBehindBars(double bars) {
        if (bars < 0.0) {
            throw new IllegalArgumentException("bars must be >= 0: " + bars);
        }
        this.lookBehindBars = bars;
    }

    /** Sets the number of samples in each trajectory slice. */
    public void setResolution(int resolution) {
        if (resolution < 2) {
            throw new IllegalArgumentException("resolution must be >= 2: " + resolution);
        }
        this.resolution = resolution;
    }

    /** Returns the configured look-ahead window in bars. */
    public double getLookAheadBars() {
        return lookAheadBars;
    }

    /** Returns the configured look-behind window in bars. */
    public double getLookBehindBars() {
        return lookBehindBars;
    }

    /** Returns the configured sample resolution. */
    public int getResolution() {
        return resolution;
    }

    /**
     * Samples the future trajectory starting at the given playhead beat.
     *
     * @param playheadBeats current transport position in beats
     * @return an ordered list of points covering the look-ahead window
     */
    public List<Point3D> sampleFuture(double playheadBeats) {
        return sample(playheadBeats, playheadBeats + lookAheadBars * beatsPerBar);
    }

    /**
     * Samples the past trajectory ending at the given playhead beat.
     *
     * @param playheadBeats current transport position in beats
     * @return an ordered list of points covering the look-behind window
     */
    public List<Point3D> samplePast(double playheadBeats) {
        double start = Math.max(0.0, playheadBeats - lookBehindBars * beatsPerBar);
        return sample(start, playheadBeats);
    }

    private List<Point3D> sample(double startBeats, double endBeats) {
        AutomationLane xLane = laneFor(ObjectParameter.X);
        AutomationLane yLane = laneFor(ObjectParameter.Y);
        AutomationLane zLane = laneFor(ObjectParameter.Z);

        List<Point3D> out = new ArrayList<>(resolution);
        if (endBeats <= startBeats) {
            return out;
        }
        double step = (endBeats - startBeats) / (resolution - 1);
        for (int i = 0; i < resolution; i++) {
            double t = startBeats + i * step;
            double x = xLane != null ? xLane.getValueAtTime(t) : ObjectParameter.X.getDefaultValue();
            double y = yLane != null ? yLane.getValueAtTime(t) : ObjectParameter.Y.getDefaultValue();
            double z = zLane != null ? zLane.getValueAtTime(t) : ObjectParameter.Z.getDefaultValue();
            out.add(new Point3D(t, x, y, z));
        }
        return out;
    }

    private AutomationLane laneFor(ObjectParameter parameter) {
        return automationData.getObjectLane(
                new ObjectParameterTarget(objectInstanceId, parameter));
    }

    /**
     * One trajectory point: the time at which it was sampled (in beats) and
     * the corresponding {@code (x, y, z)} in normalised [-1, 1] space.
     */
    public record Point3D(double timeInBeats, double x, double y, double z) {
    }
}
