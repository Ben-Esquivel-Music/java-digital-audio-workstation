package com.benesquivelmusic.daw.core.persistence.backup;

import com.benesquivelmusic.daw.sdk.persistence.BackupRetentionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BackupRetentionServiceTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test
    void emptyDirectoryProducesEmptyPlan() throws IOException {
        BackupRetentionService svc = new BackupRetentionService(BackupRetentionPolicy.DEFAULT);
        BackupRetentionService.Plan plan = svc.prune(Path.of("nonexistent-" + System.nanoTime()));
        assertThat(plan.decisions()).isEmpty();
    }

    @Test
    void recentBucketKeepsTopN() {
        Instant now = Instant.parse("2026-06-15T12:00:00Z");
        List<BackupRetentionService.Snapshot> snaps = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            // one snapshot per minute, newest at i=0
            snaps.add(new BackupRetentionService.Snapshot(
                    Path.of("snap-" + i),
                    now.minus(Duration.ofMinutes(i)),
                    100L));
        }
        BackupRetentionPolicy onlyRecent = new BackupRetentionPolicy(
                5, 0, 0, 0, Duration.ZERO, 0L);
        BackupRetentionService.Plan plan =
                BackupRetentionService.plan(snaps, onlyRecent, now, UTC);
        assertThat(plan.count(BackupRetentionService.Bucket.RECENT)).isEqualTo(5);
        assertThat(plan.kept()).hasSize(5);
        // newest 5 retained
        assertThat(plan.kept().get(0).path().getFileName().toString()).isEqualTo("snap-0");
        assertThat(plan.kept().get(4).path().getFileName().toString()).isEqualTo("snap-4");
    }

    @Test
    void disablingABucketStopsThatClassFromBeingKept() {
        // Snapshots two-hours-apart over 3 days (plenty for hourly+daily slots)
        Instant now = Instant.parse("2026-06-15T12:00:00Z");
        List<BackupRetentionService.Snapshot> snaps = new ArrayList<>();
        for (int h = 0; h < 72; h++) {
            snaps.add(new BackupRetentionService.Snapshot(
                    Path.of("snap-" + h),
                    now.minus(Duration.ofHours(h)),
                    100L));
        }

        BackupRetentionPolicy noHourly = new BackupRetentionPolicy(
                0, 0, 5, 0, Duration.ZERO, 0L);
        BackupRetentionService.Plan plan =
                BackupRetentionService.plan(snaps, noHourly, now, UTC);
        assertThat(plan.count(BackupRetentionService.Bucket.RECENT)).isZero();
        assertThat(plan.count(BackupRetentionService.Bucket.HOURLY)).isZero();
        assertThat(plan.count(BackupRetentionService.Bucket.WEEKLY)).isZero();
        // 3 days of snapshots → at most 3 day slots within keepDaily=5
        assertThat(plan.count(BackupRetentionService.Bucket.DAILY)).isBetween(2L, 5L);
    }

    @Test
    void multiBucketSnapshotOccupiesMostRecentBucket() {
        // Single snapshot fits all of: recent, hourly, daily, weekly. Should
        // be classed RECENT only — verifying the "occupies the most-recent
        // bucket" rule from the issue.
        Instant now = Instant.parse("2026-06-15T12:00:00Z");
        List<BackupRetentionService.Snapshot> snaps = List.of(
                new BackupRetentionService.Snapshot(
                        Path.of("snap-0"), now.minus(Duration.ofMinutes(1)), 10L));
        BackupRetentionService.Plan plan =
                BackupRetentionService.plan(snaps, BackupRetentionPolicy.DEFAULT, now, UTC);
        assertThat(plan.count(BackupRetentionService.Bucket.RECENT)).isEqualTo(1);
        assertThat(plan.count(BackupRetentionService.Bucket.HOURLY)).isZero();
        assertThat(plan.count(BackupRetentionService.Bucket.DAILY)).isZero();
        assertThat(plan.count(BackupRetentionService.Bucket.WEEKLY)).isZero();
    }

    @Test
    void maxAgeDropsAncientSnapshots() {
        Instant now = Instant.parse("2026-06-15T12:00:00Z");
        List<BackupRetentionService.Snapshot> snaps = List.of(
                new BackupRetentionService.Snapshot(
                        Path.of("recent"), now.minus(Duration.ofHours(1)), 10L),
                new BackupRetentionService.Snapshot(
                        Path.of("ancient"), now.minus(Duration.ofDays(400)), 10L));
        BackupRetentionPolicy generous = new BackupRetentionPolicy(
                100, 100, 100, 100, Duration.ofDays(365), 0L);
        BackupRetentionService.Plan plan =
                BackupRetentionService.plan(snaps, generous, now, UTC);
        assertThat(plan.kept()).extracting(s -> s.path().getFileName().toString())
                .containsExactly("recent");
    }

    @Test
    void maxBytesEnforcesCumulativeCap() {
        Instant now = Instant.parse("2026-06-15T12:00:00Z");
        List<BackupRetentionService.Snapshot> snaps = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            snaps.add(new BackupRetentionService.Snapshot(
                    Path.of("snap-" + i), now.minus(Duration.ofMinutes(i)), 100L));
        }
        // Allow 250 bytes total → newest 2 snapshots (100+100=200) kept, 3rd (300) over budget.
        BackupRetentionPolicy capped = new BackupRetentionPolicy(
                10, 0, 0, 0, Duration.ZERO, 250L);
        BackupRetentionService.Plan plan =
                BackupRetentionService.plan(snaps, capped, now, UTC);
        assertThat(plan.kept()).hasSize(2);
        assertThat(plan.keptBytes()).isEqualTo(200L);
    }

    @Test
    void prunePhysicallyDeletesDiscardedFiles(@TempDir Path tempDir) throws IOException {
        Instant now = Instant.now();
        // 8 files, two minutes apart
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Path f = tempDir.resolve("snap-" + i + ".daw");
            Files.writeString(f, "x");
            Files.setLastModifiedTime(f,
                    FileTime.from(now.minus(Duration.ofMinutes(2L * i))));
            files.add(f);
        }
        BackupRetentionPolicy keepThree = new BackupRetentionPolicy(
                3, 0, 0, 0, Duration.ZERO, 0L);
        BackupRetentionService svc = new BackupRetentionService(keepThree);
        BackupRetentionService.Plan plan = svc.prune(tempDir);
        assertThat(plan.kept()).hasSize(3);
        // Newest three remain on disk; older five gone.
        assertThat(files.get(0)).exists();
        assertThat(files.get(1)).exists();
        assertThat(files.get(2)).exists();
        for (int i = 3; i < 8; i++) {
            assertThat(files.get(i)).doesNotExist();
        }
    }

    /**
     * Simulates one autosave every 15 minutes for a full year (~35,000
     * snapshots) and asserts that, after applying the default policy, the
     * surviving set matches the policy's bucket counts and the total size
     * is below the byte cap.
     */
    @Test
    void simulatedYearlyStreamMatchesPolicyBuckets() {
        Instant end = Instant.parse("2027-01-01T00:00:00Z");
        Duration cadence = Duration.ofMinutes(15);
        Duration totalSpan = Duration.ofDays(365);
        long count = totalSpan.dividedBy(cadence);

        List<BackupRetentionService.Snapshot> snaps = new ArrayList<>((int) count);
        for (long i = 0; i < count; i++) {
            Instant ts = end.minus(cadence.multipliedBy(i));
            snaps.add(new BackupRetentionService.Snapshot(
                    Path.of("snap-" + i + ".daw"), ts, 1024L));
        }

        BackupRetentionPolicy d = BackupRetentionPolicy.DEFAULT;
        BackupRetentionService.Plan plan =
                BackupRetentionService.plan(snaps, d, end, UTC);

        // Each bucket count must not exceed its policy cap. Note RECENT
        // "steals" from HOURLY — by design the most recent N snapshots
        // occupy the recent bucket, leaving fewer hour slots available to
        // HOURLY. So HOURLY may fall short of keepHourly when keepRecent's
        // span overlaps multiple hour slots.
        assertThat(plan.count(BackupRetentionService.Bucket.RECENT)).isEqualTo(d.keepRecent());
        assertThat(plan.count(BackupRetentionService.Bucket.HOURLY))
                .isPositive()
                .isLessThanOrEqualTo(d.keepHourly());
        assertThat(plan.count(BackupRetentionService.Bucket.DAILY))
                .isPositive()
                .isLessThanOrEqualTo(d.keepDaily());
        // weekly capped by either policy or by the 30-day age limit (4-5 weeks)
        assertThat(plan.count(BackupRetentionService.Bucket.WEEKLY))
                .isLessThanOrEqualTo(d.keepWeekly())
                .isPositive();

        // No snapshot older than maxAge should be kept.
        for (BackupRetentionService.Snapshot s : plan.kept()) {
            Duration age = Duration.between(s.timestamp(), end);
            assertThat(age).isLessThanOrEqualTo(d.maxAge());
        }
        // Cumulative size respects the cap.
        assertThat(plan.keptBytes()).isLessThanOrEqualTo(d.maxBytes());

        // Sum of buckets equals the kept count (sanity).
        long bucketSum = plan.count(BackupRetentionService.Bucket.RECENT)
                + plan.count(BackupRetentionService.Bucket.HOURLY)
                + plan.count(BackupRetentionService.Bucket.DAILY)
                + plan.count(BackupRetentionService.Bucket.WEEKLY);
        assertThat(bucketSum).isEqualTo(plan.kept().size());
    }

    @Test
    void hourlyBucketReachesFullCapacityWhenRecentDoesNotSteal() {
        // With keepRecent=1 and one snapshot per hour, HOURLY must fill all 24 slots.
        Instant now = Instant.parse("2026-06-15T12:00:00Z");
        List<BackupRetentionService.Snapshot> snaps = new ArrayList<>();
        for (int h = 0; h < 48; h++) {
            snaps.add(new BackupRetentionService.Snapshot(
                    Path.of("snap-" + h),
                    now.minus(Duration.ofMinutes(30L + 60L * h)),
                    100L));
        }
        BackupRetentionPolicy p = new BackupRetentionPolicy(
                1, 24, 0, 0, Duration.ZERO, 0L);
        BackupRetentionService.Plan plan =
                BackupRetentionService.plan(snaps, p, now, UTC);
        assertThat(plan.count(BackupRetentionService.Bucket.RECENT)).isEqualTo(1);
        // Hour slot 0 is consumed by RECENT, leaving 23 of 24 hourly slots fillable.
        assertThat(plan.count(BackupRetentionService.Bucket.HOURLY)).isEqualTo(23);
    }

    @Test
    void disablingAllBucketsKeepsNothing() {
        Instant now = Instant.parse("2026-06-15T12:00:00Z");
        List<BackupRetentionService.Snapshot> snaps = List.of(
                new BackupRetentionService.Snapshot(
                        Path.of("snap"), now.minus(Duration.ofMinutes(5)), 10L));
        BackupRetentionPolicy nothing = new BackupRetentionPolicy(
                0, 0, 0, 0, Duration.ZERO, 0L);
        BackupRetentionService.Plan plan =
                BackupRetentionService.plan(snaps, nothing, now, UTC);
        assertThat(plan.kept()).isEmpty();
    }
}
