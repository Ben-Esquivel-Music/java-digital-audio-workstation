package com.benesquivelmusic.daw.core.audio.health;

import java.util.Arrays;
import java.util.Objects;

/**
 * An immutable snapshot of an {@link AudioHealthRecord}'s lanes: the live
 * ones, plus the folded totals of every lane retired so far (story 337,
 * ruling E18). The record publishes a new snapshot with one volatile write
 * whenever a lane is claimed or retired, so a reader that loads the snapshot
 * once sees a consistent set whose totals never go backwards: a lane's counts
 * are either still live in {@code live} or already folded into the retired
 * totals, never both and never neither. The arrays are never mutated after
 * construction.
 *
 * @param live                  the live lanes, in claim order
 * @param retiredCounts         per-code counts folded from retired lanes
 * @param retiredLastFailures   per-code last failures folded from retired lanes
 * @param retiredRenderDriveBeats heartbeats folded from retired render-drive lanes
 * @param retiredLost           lost-descriptor counts folded from retired lanes
 */
record LaneSet(AudioHealthLane[] live,
               long[] retiredCounts,
               Throwable[] retiredLastFailures,
               long retiredRenderDriveBeats,
               long retiredLost) {

    static final LaneSet EMPTY = new LaneSet(new AudioHealthLane[0],
            new long[AudioFailureCodes.COUNT], new Throwable[AudioFailureCodes.COUNT], 0L, 0L);

    LaneSet {
        Objects.requireNonNull(live, "live must not be null");
        Objects.requireNonNull(retiredCounts, "retiredCounts must not be null");
        Objects.requireNonNull(retiredLastFailures, "retiredLastFailures must not be null");
    }

    /** This snapshot with {@code lane} appended to the live lanes. */
    LaneSet with(AudioHealthLane lane) {
        AudioHealthLane[] grown = Arrays.copyOf(live, live.length + 1);
        grown[live.length] = lane;
        return new LaneSet(grown, retiredCounts, retiredLastFailures,
                retiredRenderDriveBeats, retiredLost);
    }

    /** Whether {@code lane} is live in this snapshot. */
    boolean contains(AudioHealthLane lane) {
        for (AudioHealthLane candidate : live) {
            if (candidate == lane) {
                return true;
            }
        }
        return false;
    }

    /**
     * This snapshot with {@code lane} removed from the live lanes and its
     * final counts, heartbeats, lost descriptors and last failures folded into
     * the retired totals. The lane's owner thread must have exited or never
     * started, so its values are final.
     */
    LaneSet retire(AudioHealthLane lane) {
        AudioHealthLane[] remaining = new AudioHealthLane[live.length - 1];
        int next = 0;
        for (AudioHealthLane candidate : live) {
            if (candidate != lane) {
                remaining[next++] = candidate;
            }
        }
        long[] counts = retiredCounts.clone();
        Throwable[] lastFailures = retiredLastFailures.clone();
        for (int code = 0; code < AudioFailureCodes.COUNT; code++) {
            counts[code] += lane.countOf(code);
            Throwable last = lane.lastFailureOf(code);
            if (last != null) {
                lastFailures[code] = last;
            }
        }
        long beats = lane.role() == LaneRole.RENDER_DRIVE ? lane.beats() : 0L;
        return new LaneSet(remaining, counts, lastFailures,
                retiredRenderDriveBeats + beats, retiredLost + lane.lostRecords());
    }
}
