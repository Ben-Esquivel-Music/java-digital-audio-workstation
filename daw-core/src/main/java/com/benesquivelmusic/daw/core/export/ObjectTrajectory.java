package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.sdk.spatial.ObjectMetadata;

import java.util.List;
import java.util.Objects;

/**
 * A time-stamped sequence of {@link ObjectMetadata} samples describing the
 * trajectory of a single audio object over the duration of a render.
 *
 * <p>Used by the ADM BWF exporter (story 026 + story 172) to emit one
 * {@code audioBlockFormat} per breakpoint with {@code rtime} and
 * {@code duration} attributes — the time-stamped position data consumed by
 * downstream Atmos / ADM-BWF renderers.</p>
 *
 * <p>Each frame is represented by an {@link Frame} of:
 * <ul>
 *   <li>{@code rtimeSeconds} — start time of the block, relative to the
 *       beginning of the render, in seconds (must be {@code >= 0}).</li>
 *   <li>{@code durationSeconds} — duration of the block in seconds (must
 *       be {@code > 0}).</li>
 *   <li>{@code metadata} — the {@link ObjectMetadata} at the block start.</li>
 * </ul>
 *
 * <p>An empty trajectory means "use the object's static metadata" — the
 * exporter then emits a single block format as before, preserving backward
 * compatibility for callers that have not adopted automation-driven
 * trajectories.</p>
 *
 * @param frames the time-ordered list of frames; may be empty
 */
public record ObjectTrajectory(List<Frame> frames) {

    public ObjectTrajectory {
        Objects.requireNonNull(frames, "frames must not be null");
        frames = List.copyOf(frames);
    }

    /** Returns an empty trajectory (signals "use static metadata"). */
    public static ObjectTrajectory empty() {
        return new ObjectTrajectory(List.of());
    }

    /** Returns {@code true} if this trajectory has no frames. */
    public boolean isEmpty() {
        return frames.isEmpty();
    }

    /**
     * One time-stamped sample of object metadata.
     *
     * @param rtimeSeconds    start time in seconds, relative to render start
     * @param durationSeconds duration of this block in seconds
     * @param metadata        the per-object metadata at {@code rtimeSeconds}
     */
    public record Frame(double rtimeSeconds, double durationSeconds, ObjectMetadata metadata) {
        public Frame {
            Objects.requireNonNull(metadata, "metadata must not be null");
            if (rtimeSeconds < 0.0) {
                throw new IllegalArgumentException(
                        "rtimeSeconds must be >= 0: " + rtimeSeconds);
            }
            if (durationSeconds <= 0.0) {
                throw new IllegalArgumentException(
                        "durationSeconds must be > 0: " + durationSeconds);
            }
        }
    }
}
