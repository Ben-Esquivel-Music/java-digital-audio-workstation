package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.core.automation.AutomationData;
import com.benesquivelmusic.daw.core.automation.AutomationLane;
import com.benesquivelmusic.daw.core.automation.AutomationPoint;
import com.benesquivelmusic.daw.core.automation.ObjectParameterTarget;
import com.benesquivelmusic.daw.sdk.spatial.ObjectMetadata;
import com.benesquivelmusic.daw.sdk.spatial.ObjectParameter;

import java.util.Objects;
import java.util.TreeSet;

/**
 * Builds an {@link ObjectTrajectory} from the {@link AutomationData} of a
 * spatial track by sampling its {@link ObjectParameterTarget} lanes at every
 * breakpoint time (the union of all X / Y / Z / SIZE / DIVERGENCE / GAIN
 * lane points).
 *
 * <p>Story 172: the ADM-BWF export consumes the X / Y / Z lanes (and
 * optionally SIZE / GAIN) of an object track as time-stamped position data;
 * this helper performs the lane → trajectory translation in a unit-testable,
 * UI-independent way.</p>
 */
public final class ObjectTrajectoryBuilder {

    private ObjectTrajectoryBuilder() {
        // utility class
    }

    /**
     * Builds a trajectory for the given object instance from automation
     * lanes. If no lane on this object has any breakpoints, the returned
     * trajectory is empty (signals "use static metadata").
     *
     * @param automationData    the automation container holding the lanes
     * @param objectInstanceId  the panner instance id (matches
     *                          {@link ObjectParameterTarget#objectInstanceId()})
     * @param staticMetadata    fall-back metadata used for any parameter
     *                          that has no automation lane on this object
     * @param tempoBpm          the tempo in beats-per-minute, used to convert
     *                          breakpoint times from beats to seconds
     * @param totalDurationSeconds  the total render duration in seconds —
     *                              the last frame's {@code duration} extends
     *                              up to this value
     * @return the constructed trajectory; empty if no breakpoints exist
     */
    public static ObjectTrajectory build(AutomationData automationData,
                                         String objectInstanceId,
                                         ObjectMetadata staticMetadata,
                                         double tempoBpm,
                                         double totalDurationSeconds) {
        Objects.requireNonNull(automationData, "automationData must not be null");
        Objects.requireNonNull(objectInstanceId, "objectInstanceId must not be null");
        Objects.requireNonNull(staticMetadata, "staticMetadata must not be null");
        if (tempoBpm <= 0.0) {
            throw new IllegalArgumentException("tempoBpm must be > 0: " + tempoBpm);
        }
        if (totalDurationSeconds <= 0.0) {
            throw new IllegalArgumentException(
                    "totalDurationSeconds must be > 0: " + totalDurationSeconds);
        }

        // Collect lanes for this object
        AutomationLane xLane = automationData.getObjectLane(
                new ObjectParameterTarget(objectInstanceId, ObjectParameter.X));
        AutomationLane yLane = automationData.getObjectLane(
                new ObjectParameterTarget(objectInstanceId, ObjectParameter.Y));
        AutomationLane zLane = automationData.getObjectLane(
                new ObjectParameterTarget(objectInstanceId, ObjectParameter.Z));
        AutomationLane sizeLane = automationData.getObjectLane(
                new ObjectParameterTarget(objectInstanceId, ObjectParameter.SIZE));
        AutomationLane gainLane = automationData.getObjectLane(
                new ObjectParameterTarget(objectInstanceId, ObjectParameter.GAIN));

        // Union of all breakpoint times across all lanes (sorted, deduped)
        TreeSet<Double> beatTimes = new TreeSet<>();
        addPointTimes(beatTimes, xLane);
        addPointTimes(beatTimes, yLane);
        addPointTimes(beatTimes, zLane);
        addPointTimes(beatTimes, sizeLane);
        addPointTimes(beatTimes, gainLane);

        if (beatTimes.isEmpty()) {
            return ObjectTrajectory.empty();
        }

        double secondsPerBeat = 60.0 / tempoBpm;
        java.util.List<ObjectTrajectory.Frame> frames = new java.util.ArrayList<>();
        Double[] times = beatTimes.toArray(Double[]::new);
        for (int i = 0; i < times.length; i++) {
            double tBeats = times[i];
            double rtime = tBeats * secondsPerBeat;
            if (rtime >= totalDurationSeconds) {
                break;
            }
            double nextRtime = (i + 1 < times.length)
                    ? times[i + 1] * secondsPerBeat
                    : totalDurationSeconds;
            if (nextRtime > totalDurationSeconds) {
                nextRtime = totalDurationSeconds;
            }
            double duration = nextRtime - rtime;
            if (duration <= 0.0) {
                continue;
            }
            ObjectMetadata sample = sampleAt(tBeats, xLane, yLane, zLane,
                    sizeLane, gainLane, staticMetadata);
            frames.add(new ObjectTrajectory.Frame(rtime, duration, sample));
        }
        return new ObjectTrajectory(frames);
    }

    private static void addPointTimes(TreeSet<Double> times, AutomationLane lane) {
        if (lane == null) {
            return;
        }
        for (AutomationPoint p : lane.getPoints()) {
            times.add(p.getTimeInBeats());
        }
    }

    private static ObjectMetadata sampleAt(double tBeats,
                                           AutomationLane xLane,
                                           AutomationLane yLane,
                                           AutomationLane zLane,
                                           AutomationLane sizeLane,
                                           AutomationLane gainLane,
                                           ObjectMetadata fallback) {
        double x = (xLane != null && xLane.getPointCount() > 0)
                ? xLane.getValueAtTime(tBeats) : fallback.x();
        double y = (yLane != null && yLane.getPointCount() > 0)
                ? yLane.getValueAtTime(tBeats) : fallback.y();
        double z = (zLane != null && zLane.getPointCount() > 0)
                ? zLane.getValueAtTime(tBeats) : fallback.z();
        double size = (sizeLane != null && sizeLane.getPointCount() > 0)
                ? sizeLane.getValueAtTime(tBeats) : fallback.size();
        double gain = (gainLane != null && gainLane.getPointCount() > 0)
                ? gainLane.getValueAtTime(tBeats) : fallback.gain();
        return new ObjectMetadata(clamp(x, -1.0, 1.0), clamp(y, -1.0, 1.0),
                clamp(z, -1.0, 1.0), clamp(size, 0.0, 1.0), clamp(gain, 0.0, 1.0));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
