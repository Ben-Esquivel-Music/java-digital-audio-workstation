package com.benesquivelmusic.daw.sdk.persistence;

import java.time.Duration;
import java.util.Objects;

/**
 * Retention policy for autosave / checkpoint snapshots.
 *
 * <p>Implements the classic "grandfather-father-son" rotation: keep a small
 * number of <em>recent</em> snapshots plus widely-spaced <em>hourly</em>,
 * <em>daily</em> and <em>weekly</em> milestones so that a week-old version is
 * always recoverable without retaining every individual autosave on disk.</p>
 *
 * <p>Each snapshot is ranked into the most specific bucket it qualifies for:
 * the {@link #keepRecent() N} most recent snapshots fill the {@code recent}
 * bucket first; the remaining snapshots are then grouped by hour / day / ISO
 * week and at most one representative (the most recent within the slot) is
 * kept per slot. Snapshots that fall in no bucket — or that exceed
 * {@link #maxAge()} or push the cumulative byte total over
 * {@link #maxBytes()} — are discarded.</p>
 *
 * <p>Setting any of {@link #keepRecent()}, {@link #keepHourly()},
 * {@link #keepDaily()} or {@link #keepWeekly()} to {@code 0} disables that
 * class of retention entirely. {@link #maxAge()} of {@link Duration#ZERO} or
 * less and {@link #maxBytes()} of {@code 0} disable those caps.</p>
 *
 * @param keepRecent the number of most-recent snapshots to keep verbatim
 *                   (must be {@code >= 0})
 * @param keepHourly the maximum number of hourly milestones to keep
 *                   (one per hour bucket, most-recent in each), {@code >= 0}
 * @param keepDaily  the maximum number of daily milestones to keep
 *                   (one per calendar day), {@code >= 0}
 * @param keepWeekly the maximum number of weekly milestones to keep
 *                   (one per ISO week), {@code >= 0}
 * @param maxAge     hard cap on snapshot age; snapshots older than this are
 *                   dropped regardless of bucket. {@link Duration#ZERO} or
 *                   negative disables the cap.
 * @param maxBytes   hard cap on total cumulative size of retained snapshots
 *                   in bytes; 0 disables the cap.
 */
public record BackupRetentionPolicy(
        int keepRecent,
        int keepHourly,
        int keepDaily,
        int keepWeekly,
        Duration maxAge,
        long maxBytes
) {

    /**
     * Default policy: 10 recent + 24 hourly + 14 daily + 8 weekly milestones,
     * with a 30-day hard age cap and a 2 GiB total-size cap per project.
     */
    public static final BackupRetentionPolicy DEFAULT = new BackupRetentionPolicy(
            10,
            24,
            14,
            8,
            Duration.ofDays(30),
            2L * 1024L * 1024L * 1024L
    );

    public BackupRetentionPolicy {
        Objects.requireNonNull(maxAge, "maxAge must not be null");
        if (keepRecent < 0) {
            throw new IllegalArgumentException("keepRecent must be >= 0: " + keepRecent);
        }
        if (keepHourly < 0) {
            throw new IllegalArgumentException("keepHourly must be >= 0: " + keepHourly);
        }
        if (keepDaily < 0) {
            throw new IllegalArgumentException("keepDaily must be >= 0: " + keepDaily);
        }
        if (keepWeekly < 0) {
            throw new IllegalArgumentException("keepWeekly must be >= 0: " + keepWeekly);
        }
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must be >= 0: " + maxBytes);
        }
    }

    /** Returns whether {@link #maxAge()} is enforced (positive). */
    public boolean enforcesMaxAge() {
        return maxAge != null && !maxAge.isZero() && !maxAge.isNegative();
    }

    /** Returns whether {@link #maxBytes()} is enforced (positive). */
    public boolean enforcesMaxBytes() {
        return maxBytes > 0L;
    }

    /** Returns a copy with {@link #keepRecent()} replaced. */
    public BackupRetentionPolicy withKeepRecent(int keepRecent) {
        return new BackupRetentionPolicy(keepRecent, keepHourly, keepDaily, keepWeekly, maxAge, maxBytes);
    }

    /** Returns a copy with {@link #keepHourly()} replaced. */
    public BackupRetentionPolicy withKeepHourly(int keepHourly) {
        return new BackupRetentionPolicy(keepRecent, keepHourly, keepDaily, keepWeekly, maxAge, maxBytes);
    }

    /** Returns a copy with {@link #keepDaily()} replaced. */
    public BackupRetentionPolicy withKeepDaily(int keepDaily) {
        return new BackupRetentionPolicy(keepRecent, keepHourly, keepDaily, keepWeekly, maxAge, maxBytes);
    }

    /** Returns a copy with {@link #keepWeekly()} replaced. */
    public BackupRetentionPolicy withKeepWeekly(int keepWeekly) {
        return new BackupRetentionPolicy(keepRecent, keepHourly, keepDaily, keepWeekly, maxAge, maxBytes);
    }

    /** Returns a copy with {@link #maxAge()} replaced. */
    public BackupRetentionPolicy withMaxAge(Duration maxAge) {
        return new BackupRetentionPolicy(keepRecent, keepHourly, keepDaily, keepWeekly, maxAge, maxBytes);
    }

    /** Returns a copy with {@link #maxBytes()} replaced. */
    public BackupRetentionPolicy withMaxBytes(long maxBytes) {
        return new BackupRetentionPolicy(keepRecent, keepHourly, keepDaily, keepWeekly, maxAge, maxBytes);
    }
}
