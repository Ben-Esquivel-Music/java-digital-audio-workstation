package com.benesquivelmusic.daw.core.audio.health;

import java.util.Arrays;
import java.util.Objects;

/**
 * An immutable snapshot of an {@link AudioHealthRecord}'s lanes (story 337,
 * rulings E18 and E81), in three groups:
 * <ul>
 *   <li><strong>live</strong> — the lanes whose render threads may still
 *       write them;</li>
 *   <li><strong>draining</strong> — retired lanes whose rings still hold
 *       descriptors the drain has not taken, oldest retired first, at most
 *       {@value #MAX_DRAINING}; their counts are already folded, so they are
 *       read for their samples only, and a lane leaves the group once the
 *       drain has emptied it;</li>
 *   <li><strong>retired</strong> — the folded counts, last failures,
 *       render-drive beats and lost descriptors of every lane retired so far,
 *       and the render-drive epoch: how many render-drive lanes were ever
 *       claimed.</li>
 * </ul>
 * <p>The record publishes a new snapshot with one volatile write whenever a
 * lane is claimed or retired, so a reader that loads the snapshot once sees a
 * consistent set whose totals never go backwards: a lane's counts are either
 * still live in {@code live} or already folded into the retired totals, never
 * both and never neither. The arrays are never mutated after construction.</p>
 *
 * @param live                    the live lanes, in claim order
 * @param draining                retired lanes with undrained descriptors,
 *                                oldest retired first
 * @param retiredCounts           per-code counts folded from retired lanes
 * @param retiredLastFailures     per-code last failures folded from retired lanes
 * @param retiredRenderDriveBeats heartbeats folded from retired render-drive lanes
 * @param retiredLost             lost-descriptor counts folded from retired
 *                                lanes, and the descriptors of evicted ones
 * @param renderDriveEpoch        render-drive lanes claimed so far
 */
record LaneSet(AudioHealthLane[] live,
               AudioHealthLane[] draining,
               long[] retiredCounts,
               Throwable[] retiredLastFailures,
               long retiredRenderDriveBeats,
               long retiredLost,
               long renderDriveEpoch) {

    /**
     * Retired lanes kept readable for the drain (story 337, ruling E81).
     * Beyond it the oldest is cleared and its undrained descriptors are
     * counted lost.
     */
    static final int MAX_DRAINING = 32;

    static final LaneSet EMPTY = new LaneSet(new AudioHealthLane[0], new AudioHealthLane[0],
            new long[AudioFailureCodes.COUNT], new Throwable[AudioFailureCodes.COUNT], 0L, 0L, 0L);

    LaneSet {
        Objects.requireNonNull(live, "live must not be null");
        Objects.requireNonNull(draining, "draining must not be null");
        Objects.requireNonNull(retiredCounts, "retiredCounts must not be null");
        Objects.requireNonNull(retiredLastFailures, "retiredLastFailures must not be null");
    }

    /**
     * This snapshot with {@code lane} appended to the live lanes, one more
     * render-drive epoch when it is a {@link LaneRole#RENDER_DRIVE} lane, and
     * every retired lane the drain has emptied dropped.
     */
    LaneSet with(AudioHealthLane lane) {
        AudioHealthLane[] grown = Arrays.copyOf(live, live.length + 1);
        grown[live.length] = lane;
        long epoch = lane.role() == LaneRole.RENDER_DRIVE ? renderDriveEpoch + 1L : renderDriveEpoch;
        return new LaneSet(grown, stillUndrained(draining), retiredCounts, retiredLastFailures,
                retiredRenderDriveBeats, retiredLost, epoch);
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
     * started, so its values are final. Its undrained descriptors stay
     * readable: the lane joins the draining group when its ring is not empty
     * (story 337, ruling E81). Retired lanes the drain has emptied are
     * dropped; beyond {@value #MAX_DRAINING} the oldest is evicted — its slots
     * cleared and its undrained descriptors counted lost.
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
        long lost = retiredLost + lane.lostRecords();
        AudioHealthLane[] owed = stillUndrained(draining);
        if (lane.undrained() > 0L) {
            owed = Arrays.copyOf(owed, owed.length + 1);
            owed[owed.length - 1] = lane;
        }
        if (owed.length > MAX_DRAINING) {
            int evicted = owed.length - MAX_DRAINING;
            for (int i = 0; i < evicted; i++) {
                lost += owed[i].undrained();
                owed[i].clearSlots();
            }
            owed = Arrays.copyOfRange(owed, evicted, owed.length);
        }
        return new LaneSet(remaining, owed, counts, lastFailures,
                retiredRenderDriveBeats + beats, lost, renderDriveEpoch);
    }

    /** The lanes of {@code lanes} whose rings still hold undrained descriptors, in order. */
    private static AudioHealthLane[] stillUndrained(AudioHealthLane[] lanes) {
        int kept = 0;
        AudioHealthLane[] owed = new AudioHealthLane[lanes.length];
        for (AudioHealthLane candidate : lanes) {
            if (candidate.undrained() > 0L) {
                owed[kept++] = candidate;
            }
        }
        return kept == lanes.length ? lanes : Arrays.copyOf(owed, kept);
    }
}
