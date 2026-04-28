package com.benesquivelmusic.daw.core.persistence.backup;

import com.benesquivelmusic.daw.sdk.persistence.BackupRetentionPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Applies a {@link BackupRetentionPolicy} to a directory of snapshot files.
 *
 * <p>On every new autosave the host calls {@link #prune(Path)} (or the static
 * {@link #plan(List, BackupRetentionPolicy, Instant)} for previewing) and the
 * service computes which snapshots to keep using a "grandfather-father-son"
 * bucket scheme:</p>
 *
 * <ol>
 *   <li><b>recent</b> — the {@code keepRecent} most-recent snapshots are
 *       kept verbatim;</li>
 *   <li><b>hourly</b> — for the remaining snapshots, the most-recent within
 *       each hour bucket (relative to <i>now</i>) is kept up to
 *       {@code keepHourly} buckets;</li>
 *   <li><b>daily</b> — same, one per calendar day, up to {@code keepDaily};</li>
 *   <li><b>weekly</b> — one per ISO week, up to {@code keepWeekly}.</li>
 * </ol>
 *
 * <p>A snapshot that qualifies for multiple buckets is counted in the most
 * specific (most-recent) bucket only. Snapshots that match no bucket — or
 * that exceed {@link BackupRetentionPolicy#maxAge()} or push the cumulative
 * total above {@link BackupRetentionPolicy#maxBytes()} — are deleted.</p>
 *
 * <p>This service is also used to compute the live "what would be kept"
 * preview for the backup-settings UI without modifying any files.</p>
 */
public final class BackupRetentionService {

    /** Categorisation of a single snapshot under a {@link BackupRetentionPolicy}. */
    public enum Bucket {
        /** Among the N most-recent snapshots. */
        RECENT,
        /** Hourly milestone (one per hour, most-recent in slot). */
        HOURLY,
        /** Daily milestone (one per calendar day, most-recent in slot). */
        DAILY,
        /** Weekly milestone (one per ISO week, most-recent in slot). */
        WEEKLY,
        /** Discarded — no bucket assigned. */
        DISCARDED
    }

    /**
     * A single snapshot file with its modification timestamp and size.
     *
     * @param path      the snapshot file path
     * @param timestamp the snapshot's logical timestamp (typically last-modified)
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

    /** The aggregated outcome of applying a policy. */
    public record Plan(List<Decision> decisions) {
        public Plan {
            Objects.requireNonNull(decisions, "decisions must not be null");
            decisions = List.copyOf(decisions);
        }

        /** Returns the snapshots that would be kept (non-discarded). */
        public List<Snapshot> kept() {
            return decisions.stream().filter(Decision::kept).map(Decision::snapshot).toList();
        }

        /** Returns the snapshots that would be discarded. */
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

    private final BackupRetentionPolicy policy;
    private final ZoneId zone;

    /** Creates a service that uses the JVM default time zone for bucketing. */
    public BackupRetentionService(BackupRetentionPolicy policy) {
        this(policy, ZoneId.systemDefault());
    }

    /** Creates a service with an explicit time zone (useful for tests). */
    public BackupRetentionService(BackupRetentionPolicy policy, ZoneId zone) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.zone = Objects.requireNonNull(zone, "zone must not be null");
    }

    /** Returns the active policy. */
    public BackupRetentionPolicy policy() {
        return policy;
    }

    /**
     * Scans {@code snapshotDirectory} for files, applies the policy, and
     * attempts to delete any snapshots that are not retained.
     *
     * <p>Deletion is best-effort: failures to delete individual discarded
     * snapshots are ignored and do not prevent the plan from being returned.
     * An {@code IOException} is only thrown if the directory listing or the
     * snapshot metadata reads fail.</p>
     *
     * @param snapshotDirectory directory containing snapshot files (non-recursive)
     * @return the plan that was applied
     * @throws IOException if directory listing or snapshot metadata reads fail
     */
    public Plan prune(Path snapshotDirectory) throws IOException {
        Objects.requireNonNull(snapshotDirectory, "snapshotDirectory must not be null");
        if (!Files.isDirectory(snapshotDirectory)) {
            return new Plan(List.of());
        }
        List<Snapshot> snapshots = new ArrayList<>();
        try (Stream<Path> stream = Files.list(snapshotDirectory)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(p)) continue;
                snapshots.add(new Snapshot(p,
                        Files.getLastModifiedTime(p).toInstant(),
                        Files.size(p)));
            }
        }
        Plan plan = plan(snapshots, policy, Instant.now(), zone);
        for (Snapshot s : plan.discarded()) {
            try {
                Files.deleteIfExists(s.path());
            } catch (IOException ignored) {
                // best-effort cleanup, consistent with CheckpointManager
            }
        }
        return plan;
    }

    /**
     * Pure (no-IO) policy evaluation, suitable for the live UI preview.
     *
     * <p>Uses the JVM default time zone.</p>
     */
    public static Plan plan(List<Snapshot> snapshots, BackupRetentionPolicy policy, Instant now) {
        return plan(snapshots, policy, now, ZoneId.systemDefault());
    }

    /**
     * Pure (no-IO) policy evaluation with an explicit zone.
     *
     * @param snapshots input snapshots in any order
     * @param policy    the retention policy
     * @param now       the reference "now" for age and recency comparisons
     * @param zone      the zone used to compute hour/day/week bucket keys
     * @return a plan describing which snapshots would be kept and discarded
     */
    public static Plan plan(List<Snapshot> snapshots,
                            BackupRetentionPolicy policy,
                            Instant now,
                            ZoneId zone) {
        Objects.requireNonNull(snapshots, "snapshots must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(zone, "zone must not be null");

        // Sort newest-first so the "most recent in slot" is encountered first.
        List<Snapshot> sorted = new ArrayList<>(snapshots);
        sorted.sort(Comparator.comparing(Snapshot::timestamp).reversed());

        // Age cap pre-filter: snapshots older than maxAge are immediately discarded
        // and never compete for a bucket slot. This keeps the bucket counts honest.
        List<Snapshot> ageEligible = new ArrayList<>(sorted.size());
        List<Snapshot> tooOld = new ArrayList<>();
        for (Snapshot s : sorted) {
            if (policy.enforcesMaxAge()) {
                Duration age = Duration.between(s.timestamp(), now);
                if (!age.isNegative() && age.compareTo(policy.maxAge()) > 0) {
                    tooOld.add(s);
                    continue;
                }
            }
            ageEligible.add(s);
        }

        Map<Snapshot, Bucket> assigned = new LinkedHashMap<>();

        // 1) Recent — top N regardless of bucket key.
        int recentCount = Math.min(policy.keepRecent(), ageEligible.size());
        for (int i = 0; i < recentCount; i++) {
            assigned.put(ageEligible.get(i), Bucket.RECENT);
        }

        // For hourly/daily/weekly we walk the remaining snapshots newest-first
        // and pick the first one in each unique slot key, capped by the bucket
        // count. Slot keys are computed against `now` so "the last 24 hours,
        // 14 days, 8 weeks" semantics from the issue are preserved.
        List<Snapshot> tail = ageEligible.subList(recentCount, ageEligible.size());

        Map<Long, Snapshot> hourlySlots = new LinkedHashMap<>();
        Map<Long, Snapshot> dailySlots = new LinkedHashMap<>();
        Map<Long, Snapshot> weeklySlots = new LinkedHashMap<>();
        for (Snapshot s : tail) {
            if (assigned.containsKey(s)) continue;
            long hourKey = hoursBetween(s.timestamp(), now);
            long dayKey = daysBetween(s.timestamp(), now, zone);
            long weekKey = weeksBetween(s.timestamp(), now, zone);
            if (policy.keepHourly() > 0
                    && hourKey >= 0 && hourKey < policy.keepHourly()
                    && !hourlySlots.containsKey(hourKey)) {
                hourlySlots.put(hourKey, s);
                assigned.put(s, Bucket.HOURLY);
                continue;
            }
            if (policy.keepDaily() > 0
                    && dayKey >= 0 && dayKey < policy.keepDaily()
                    && !dailySlots.containsKey(dayKey)) {
                dailySlots.put(dayKey, s);
                assigned.put(s, Bucket.DAILY);
                continue;
            }
            if (policy.keepWeekly() > 0
                    && weekKey >= 0 && weekKey < policy.keepWeekly()
                    && !weeklySlots.containsKey(weekKey)) {
                weeklySlots.put(weekKey, s);
                assigned.put(s, Bucket.WEEKLY);
            }
        }

        // 2) Apply the cumulative-bytes cap. We keep newest-first; once the
        // running total would exceed maxBytes, the rest of the kept snapshots
        // are downgraded to DISCARDED.
        List<Snapshot> ordered = new ArrayList<>(assigned.keySet());
        ordered.sort(Comparator.comparing(Snapshot::timestamp).reversed());
        long running = 0L;
        boolean over = false;
        Map<Snapshot, Bucket> finalAssigned = new LinkedHashMap<>(assigned);
        for (Snapshot s : ordered) {
            if (over) {
                finalAssigned.remove(s);
                continue;
            }
            long next = running + s.sizeBytes();
            if (policy.enforcesMaxBytes() && next > policy.maxBytes()) {
                finalAssigned.remove(s);
                over = true;
            } else {
                running = next;
            }
        }

        // Build the decision list in newest-first order across the original
        // input so callers see a stable, predictable ordering.
        List<Decision> decisions = new ArrayList<>(sorted.size());
        for (Snapshot s : sorted) {
            Bucket b = finalAssigned.getOrDefault(s, Bucket.DISCARDED);
            decisions.add(new Decision(s, b));
        }
        // tooOld snapshots were excluded above — make sure they appear too.
        for (Snapshot s : tooOld) {
            // Already in `sorted`, so they are in `decisions` with DISCARDED.
            // No-op; this loop documents intent.
        }
        return new Plan(decisions);
    }

    private static long hoursBetween(Instant earlier, Instant later) {
        return Duration.between(earlier, later).toHours();
    }

    private static long daysBetween(Instant earlier, Instant later, ZoneId zone) {
        LocalDate a = earlier.atZone(zone).toLocalDate();
        LocalDate b = later.atZone(zone).toLocalDate();
        return ChronoUnit.DAYS.between(a, b);
    }

    private static long weeksBetween(Instant earlier, Instant later, ZoneId zone) {
        LocalDate a = earlier.atZone(zone).toLocalDate();
        LocalDate b = later.atZone(zone).toLocalDate();
        long aWeek = a.getLong(IsoFields.WEEK_BASED_YEAR) * 53L + a.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        long bWeek = b.getLong(IsoFields.WEEK_BASED_YEAR) * 53L + b.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        return bWeek - aWeek;
    }
}
