package com.benesquivelmusic.daw.sdk.persistence;

import java.time.Duration;
import java.util.Objects;

/**
 * Retention policy for a project's checkpoints — the numbered copies of its
 * document in its {@code checkpoints/} folder.
 *
 * <p>Implements the classic "grandfather-father-son" rotation: keep a small
 * number of <em>recent</em> checkpoints plus widely-spaced <em>hourly</em>,
 * <em>daily</em> and <em>weekly</em> milestones so that a week-old version is
 * always recoverable without retaining every individual checkpoint on
 * disk.</p>
 *
 * <p>Recency is the order the checkpoints were written in, which the
 * checkpoint writer knows; it is never a file time and never the time in a
 * checkpoint's name alone, which a clock set back or forward or a time-zone
 * change sets apart from that order. The time in a name only keys a
 * checkpoint's milestone slot and its age.</p>
 *
 * <ul>
 *   <li>The {@link #keepRecent() N} newest checkpoints are kept
 *       <em>whatever their age</em>.</li>
 *   <li>Every other checkpoint takes at most one milestone slot — its hour,
 *       counted back from now; else its calendar day; else its calendar
 *       week, Monday to Sunday — and each slot keeps one checkpoint, the
 *       newest to reach it. A checkpoint whose time lies in the future
 *       takes no milestone slot: it is kept only as a recent one, or when
 *       the writer must keep it.</li>
 *   <li>{@link #maxAge()} caps the milestones only: a milestone candidate
 *       older than it is discarded, never a recent checkpoint.</li>
 *   <li>{@link #maxBytes()} adds the sizes of the kept checkpoints, newest
 *       first in write order; once the total passes the cap, the older ones
 *       are discarded.</li>
 *   <li>The checkpoint writer may keep checkpoints the policy would discard
 *       — the one just written and the one holding the last checkpointed
 *       revision; they count toward {@link #maxBytes()} and are never
 *       discarded.</li>
 * </ul>
 *
 * <p>Setting any of {@link #keepRecent()}, {@link #keepHourly()},
 * {@link #keepDaily()} or {@link #keepWeekly()} to {@code 0} disables that
 * class of retention entirely. {@link #maxAge()} of {@link Duration#ZERO} or
 * less and {@link #maxBytes()} of {@code 0} disable those caps.</p>
 *
 * @param keepRecent the number of newest checkpoints, by write order, to keep
 *                   whatever their age (must be {@code >= 0})
 * @param keepHourly the maximum number of hourly milestones to keep
 *                   (one per hour counted back from now), {@code >= 0}
 * @param keepDaily  the maximum number of daily milestones to keep
 *                   (one per calendar day), {@code >= 0}
 * @param keepWeekly the maximum number of weekly milestones to keep
 *                   (one per calendar week, Monday to Sunday), {@code >= 0}
 * @param maxAge     cap on the age of milestones; a milestone candidate older
 *                   than this is discarded, the recent ones never are.
 *                   {@link Duration#ZERO} or negative disables the cap.
 * @param maxBytes   cap on the total size of the kept checkpoints in bytes,
 *                   newest first in write order; 0 disables the cap.
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
     * with a 30-day age cap on the milestones and a 2 GiB total-size cap per
     * project.
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
