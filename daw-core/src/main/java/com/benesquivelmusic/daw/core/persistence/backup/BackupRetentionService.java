package com.benesquivelmusic.daw.core.persistence.backup;

import com.benesquivelmusic.daw.sdk.persistence.BackupRetentionPolicy;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The plan of a {@link BackupRetentionPolicy} — the "grandfather-father-son"
 * rotation — over a project's checkpoints: which to keep and which to
 * discard (story 335, ruling R1.5). Pure and JavaFX-free: it reads no file
 * and deletes nothing. {@code CheckpointManager} plans its index with it
 * after every checkpoint write, inside the write's claim, and deletes what
 * the plan discards.
 *
 * <p>The caller ranks. The input is newest first in the order the
 * checkpoints were written in, which the caller knows and a timestamp does
 * not: a clock set back or forward, a time-zone change or a copy that
 * rewrites file times never makes an old checkpoint look new. A snapshot's
 * timestamp only keys its milestone slot and its age. In list order:</p>
 *
 * <ol>
 *   <li><b>recent</b> — the first {@code keepRecent} snapshots, whatever
 *       their age: the age cap never applies to them;</li>
 *   <li><b>hourly</b>, <b>daily</b>, <b>weekly</b> — every other snapshot
 *       no older than {@link BackupRetentionPolicy#maxAge()} takes the first
 *       free slot it fits, trying its hour (counted back from <i>now</i>),
 *       then its calendar day, then its calendar week (Monday to Sunday),
 *       days and weeks read in the given zone; at most {@code keepHourly},
 *       {@code keepDaily} and {@code keepWeekly} slots, and the first
 *       snapshot in list order to reach a slot takes it. A timestamp after
 *       <i>now</i> takes no slot;</li>
 *   <li><b>spared</b> — every snapshot the caller must keep
 *       ({@code alwaysKept}) is kept, whatever its age or slot;</li>
 *   <li><b>bytes</b> — walking the kept snapshots in list order, their
 *       sizes add up; once the total passes
 *       {@link BackupRetentionPolicy#maxBytes()}, every later snapshot is
 *       discarded unless the caller must keep it. One the caller must keep
 *       is counted where it stands and is never discarded.</li>
 * </ol>
 *
 * <p>Every other snapshot is discarded.</p>
 */
public final class BackupRetentionService {

    /** Categorisation of a single snapshot under a {@link BackupRetentionPolicy}. */
    public enum Bucket {
        /** Among the {@code keepRecent} newest snapshots, by the caller's write order. */
        RECENT,
        /** Hourly milestone (one per hour counted back from now, the newest in its slot). */
        HOURLY,
        /** Daily milestone (one per calendar day, the newest in its slot). */
        DAILY,
        /** Weekly milestone (one per calendar week, Monday to Sunday, the newest in its slot). */
        WEEKLY,
        /** Kept because the caller must keep it, though no other bucket holds it. */
        SPARED,
        /** Discarded — no bucket assigned. */
        DISCARDED
    }

    /**
     * A single snapshot with its timestamp and size.
     *
     * @param path      the snapshot file path
     * @param timestamp the snapshot's logical timestamp, which keys its
     *                  milestone slot and its age and never ranks it
     * @param sizeBytes the snapshot's size in bytes (must be {@code >= 0})
     */
    public record Snapshot(Path path, Instant timestamp, long sizeBytes) {
        public Snapshot {
            Objects.requireNonNull(path, "path must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
            if (sizeBytes < 0) {
                throw new IllegalArgumentException("sizeBytes must be >= 0: " + sizeBytes);
            }
        }
    }

    /** The result of categorising one snapshot. */
    public record Decision(Snapshot snapshot, Bucket bucket) {
        public Decision {
            Objects.requireNonNull(snapshot, "snapshot must not be null");
            Objects.requireNonNull(bucket, "bucket must not be null");
        }
        /** {@code true} when this snapshot is retained (not {@link Bucket#DISCARDED}). */
        public boolean kept() { return bucket != Bucket.DISCARDED; }
    }

    /** The aggregated outcome of applying a policy; its decisions are in input order. */
    public record Plan(List<Decision> decisions) {
        public Plan {
            Objects.requireNonNull(decisions, "decisions must not be null");
            decisions = List.copyOf(decisions);
        }

        /** Returns the snapshots that would be kept (non-discarded), in input order. */
        public List<Snapshot> kept() {
            return decisions.stream().filter(Decision::kept).map(Decision::snapshot).toList();
        }

        /** Returns the snapshots that would be discarded, in input order. */
        public List<Snapshot> discarded() {
            return decisions.stream().filter(d -> !d.kept()).map(Decision::snapshot).toList();
        }

        /** Returns the count of kept snapshots in the given bucket. */
        public long count(Bucket bucket) {
            return decisions.stream().filter(d -> d.bucket() == bucket).count();
        }

        /** Total bytes across kept snapshots. */
        public long keptBytes() {
            return decisions.stream().filter(Decision::kept).mapToLong(d -> d.snapshot().sizeBytes()).sum();
        }
    }

    private BackupRetentionService() {
    }

    /**
     * Pure (no-IO) policy evaluation (see the class note).
     *
     * @param newestFirst the snapshots, newest first in the caller's write
     *                    order; their timestamps only key milestone slots and age
     * @param alwaysKept  the paths the caller must keep: kept whatever their
     *                    age, slot or size, and counted toward the byte cap
     * @param policy      the retention policy
     * @param now         the reference "now" for ages and hour slots
     * @param zone        the zone of calendar days and weeks
     * @return a plan describing which snapshots would be kept and discarded
     */
    public static Plan plan(List<Snapshot> newestFirst, Set<Path> alwaysKept,
                            BackupRetentionPolicy policy, Instant now, ZoneId zone) {
        Objects.requireNonNull(newestFirst, "newestFirst must not be null");
        Objects.requireNonNull(alwaysKept, "alwaysKept must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(zone, "zone must not be null");

        int count = newestFirst.size();
        Bucket[] buckets = new Bucket[count];
        Arrays.fill(buckets, Bucket.DISCARDED);

        int recent = Math.min(policy.keepRecent(), count);
        for (int i = 0; i < recent; i++) {
            buckets[i] = Bucket.RECENT;
        }

        Milestones milestones = new Milestones(policy, now, zone);
        for (int i = recent; i < count; i++) {
            buckets[i] = milestones.slotOf(newestFirst.get(i).timestamp());
        }

        long running = 0L;
        boolean over = false;
        for (int i = 0; i < count; i++) {
            Snapshot snapshot = newestFirst.get(i);
            boolean mustKeep = alwaysKept.contains(snapshot.path());
            if (mustKeep && buckets[i] == Bucket.DISCARDED) {
                buckets[i] = Bucket.SPARED;
            }
            if (buckets[i] == Bucket.DISCARDED) {
                continue;
            }
            if (mustKeep) {
                running += snapshot.sizeBytes();
                over |= policy.enforcesMaxBytes() && running > policy.maxBytes();
            } else if (over || (policy.enforcesMaxBytes() && running + snapshot.sizeBytes() > policy.maxBytes())) {
                buckets[i] = Bucket.DISCARDED;
                over = true;
            } else {
                running += snapshot.sizeBytes();
            }
        }

        List<Decision> decisions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            decisions.add(new Decision(newestFirst.get(i), buckets[i]));
        }
        return new Plan(decisions);
    }

    /** The hourly, daily and weekly slots of one plan, each taken by the first snapshot to reach it. */
    private static final class Milestones {

        private final BackupRetentionPolicy policy;
        private final Instant now;
        private final ZoneId zone;
        private final LocalDate today;
        private final Set<Long> hours = new HashSet<>();
        private final Set<Long> days = new HashSet<>();
        private final Set<Long> weeks = new HashSet<>();

        Milestones(BackupRetentionPolicy policy, Instant now, ZoneId zone) {
            this.policy = policy;
            this.now = now;
            this.zone = zone;
            this.today = now.atZone(zone).toLocalDate();
        }

        /**
         * The first free slot {@code timestamp} fits, or {@link Bucket#DISCARDED}: a timestamp after now, or
         * one older than the age cap, takes none.
         */
        Bucket slotOf(Instant timestamp) {
            Duration age = Duration.between(timestamp, now);
            if (age.isNegative() || (policy.enforcesMaxAge() && age.compareTo(policy.maxAge()) > 0)) {
                return Bucket.DISCARDED;
            }
            if (takes(hours, age.toHours(), policy.keepHourly())) {
                return Bucket.HOURLY;
            }
            LocalDate day = timestamp.atZone(zone).toLocalDate();
            if (takes(days, ChronoUnit.DAYS.between(day, today), policy.keepDaily())) {
                return Bucket.DAILY;
            }
            if (takes(weeks, ChronoUnit.WEEKS.between(monday(day), monday(today)), policy.keepWeekly())) {
                return Bucket.WEEKLY;
            }
            return Bucket.DISCARDED;
        }

        /** Takes slot {@code key} of {@code slots} when it lies in {@code [0, limit)} and is still free. */
        private static boolean takes(Set<Long> slots, long key, int limit) {
            return key >= 0 && key < limit && slots.add(key);
        }

        /** The Monday that starts the calendar week of {@code day}. */
        private static LocalDate monday(LocalDate day) {
            return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        }
    }
}
