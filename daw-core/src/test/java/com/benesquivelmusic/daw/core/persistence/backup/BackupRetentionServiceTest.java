package com.benesquivelmusic.daw.core.persistence.backup;

import com.benesquivelmusic.daw.core.persistence.backup.BackupRetentionService.Bucket;
import com.benesquivelmusic.daw.core.persistence.backup.BackupRetentionService.Plan;
import com.benesquivelmusic.daw.core.persistence.backup.BackupRetentionService.Snapshot;
import com.benesquivelmusic.daw.sdk.persistence.BackupRetentionPolicy;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retention plan (story 335, ruling R1.5): the caller's list order is
 * recency, recent ones are kept whatever their age, the age cap and the
 * milestone slots read the timestamps, a future timestamp takes no slot,
 * weeks are calendar weeks, and what the caller must keep is never
 * discarded but counts toward the byte cap where it stands.
 */
class BackupRetentionServiceTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    @Test
    void recentBucketKeepsTopN() {
        List<Snapshot> snaps = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            // one snapshot per minute, newest at i=0
            snaps.add(snapshot("snap-" + i, NOW.minus(Duration.ofMinutes(i)), 100L));
        }
        BackupRetentionPolicy onlyRecent = new BackupRetentionPolicy(
                5, 0, 0, 0, Duration.ZERO, 0L);
        Plan plan = BackupRetentionService.plan(snaps, Set.of(), onlyRecent, NOW, UTC);
        assertThat(plan.count(Bucket.RECENT)).isEqualTo(5);
        assertThat(plan.kept()).hasSize(5);
        // newest 5 retained
        assertThat(plan.kept().get(0).path().getFileName().toString()).isEqualTo("snap-0");
        assertThat(plan.kept().get(4).path().getFileName().toString()).isEqualTo("snap-4");
    }

    @Test
    void disablingABucketStopsThatClassFromBeingKept() {
        List<Snapshot> snaps = new ArrayList<>();
        for (int h = 0; h < 72; h++) {
            snaps.add(snapshot("snap-" + h, NOW.minus(Duration.ofHours(h)), 100L));
        }

        BackupRetentionPolicy noHourly = new BackupRetentionPolicy(
                0, 0, 5, 0, Duration.ZERO, 0L);
        Plan plan = BackupRetentionService.plan(snaps, Set.of(), noHourly, NOW, UTC);
        assertThat(plan.count(Bucket.RECENT)).isZero();
        assertThat(plan.count(Bucket.HOURLY)).isZero();
        assertThat(plan.count(Bucket.WEEKLY)).isZero();
        // 3 days of snapshots → at most 3 day slots within keepDaily=5
        assertThat(plan.count(Bucket.DAILY)).isBetween(2L, 5L);
    }

    @Test
    void multiBucketSnapshotOccupiesMostRecentBucket() {
        // A single snapshot fits all of recent, hourly, daily and weekly; it is RECENT only.
        List<Snapshot> snaps = List.of(snapshot("snap-0", NOW.minus(Duration.ofMinutes(1)), 10L));
        Plan plan = BackupRetentionService.plan(snaps, Set.of(), BackupRetentionPolicy.DEFAULT, NOW, UTC);
        assertThat(plan.count(Bucket.RECENT)).isEqualTo(1);
        assertThat(plan.count(Bucket.HOURLY)).isZero();
        assertThat(plan.count(Bucket.DAILY)).isZero();
        assertThat(plan.count(Bucket.WEEKLY)).isZero();
    }

    @Test
    void maxAgeDropsAncientMilestones() {
        List<Snapshot> snaps = List.of(
                snapshot("recent", NOW.minus(Duration.ofHours(1)), 10L),
                snapshot("ancient", NOW.minus(Duration.ofDays(400)), 10L));
        BackupRetentionPolicy generous = new BackupRetentionPolicy(
                0, 100, 100, 100, Duration.ofDays(365), 0L);
        Plan plan = BackupRetentionService.plan(snaps, Set.of(), generous, NOW, UTC);
        assertThat(names(plan.kept())).containsExactly("recent");
    }

    @Test
    void recentIsKeptWhateverItsAge() {
        // A clock that ran a year ahead wrote the older ones; the newest N by write order still survive.
        List<Snapshot> snaps = List.of(
                snapshot("newest", NOW.minus(Duration.ofDays(400)), 10L),
                snapshot("second", NOW.minus(Duration.ofDays(401)), 10L),
                snapshot("third", NOW.minus(Duration.ofDays(402)), 10L));
        BackupRetentionPolicy policy = new BackupRetentionPolicy(
                2, 100, 100, 100, Duration.ofDays(30), 0L);
        Plan plan = BackupRetentionService.plan(snaps, Set.of(), policy, NOW, UTC);
        assertThat(names(plan.kept())).containsExactly("newest", "second");
        assertThat(plan.count(Bucket.RECENT)).isEqualTo(2);
        assertThat(names(plan.discarded())).containsExactly("third");
    }

    @Test
    void inputOrderNotTimestampRanks() {
        // The caller's write order says "first" is the newest, though its stamp is the oldest.
        List<Snapshot> snaps = List.of(
                snapshot("first", NOW.minus(Duration.ofHours(3)), 10L),
                snapshot("second", NOW.minus(Duration.ofHours(1)), 10L),
                snapshot("third", NOW.minus(Duration.ofMinutes(5)), 10L));
        BackupRetentionPolicy onlyRecent = new BackupRetentionPolicy(1, 0, 0, 0, Duration.ZERO, 0L);
        Plan plan = BackupRetentionService.plan(snaps, Set.of(), onlyRecent, NOW, UTC);
        assertThat(names(plan.kept())).containsExactly("first");
        assertThat(plan.decisions()).extracting(decision -> decision.snapshot().path().toString())
                .as("decisions stay in input order").containsExactly("first", "second", "third");
    }

    @Test
    void inputOrderDecidesWhichSnapshotTakesAMilestoneSlot() {
        // Two snapshots of one hour slot: the one first in write order takes it, whatever the stamps say.
        List<Snapshot> snaps = List.of(
                snapshot("written-last", NOW.minus(Duration.ofMinutes(150)), 10L),
                snapshot("written-first", NOW.minus(Duration.ofMinutes(130)), 10L));
        BackupRetentionPolicy hourly = new BackupRetentionPolicy(0, 5, 0, 0, Duration.ZERO, 0L);
        Plan plan = BackupRetentionService.plan(snaps, Set.of(), hourly, NOW, UTC);
        assertThat(names(plan.kept())).containsExactly("written-last");
        assertThat(plan.count(Bucket.HOURLY)).isEqualTo(1);
    }

    @Test
    void alwaysKeptSurvivesBytesAndAge() {
        List<Snapshot> snaps = List.of(
                snapshot("fresh", NOW.minus(Duration.ofMinutes(5)), 10L),
                snapshot("ancient-and-huge", NOW.minus(Duration.ofDays(400)), 5_000L));
        BackupRetentionPolicy strict = new BackupRetentionPolicy(0, 0, 0, 0, Duration.ofDays(1), 100L);
        Plan plan = BackupRetentionService.plan(snaps, Set.of(Path.of("ancient-and-huge")), strict, NOW, UTC);
        assertThat(names(plan.kept())).containsExactly("ancient-and-huge");
        assertThat(plan.count(Bucket.SPARED)).isEqualTo(1);
    }

    @Test
    void recentAlwaysKeptSurvivesBytesCap() {
        // The just-written checkpoint is normally RECENT; bigger than the cap, it stays and fills the budget.
        List<Snapshot> snaps = List.of(
                snapshot("just-written", NOW, 500L),
                snapshot("older", NOW.minus(Duration.ofMinutes(5)), 10L));
        BackupRetentionPolicy capped = new BackupRetentionPolicy(10, 0, 0, 0, Duration.ZERO, 100L);
        Plan plan = BackupRetentionService.plan(snaps, Set.of(Path.of("just-written")), capped, NOW, UTC);
        assertThat(names(plan.kept())).containsExactly("just-written");
        assertThat(plan.decisions().getFirst().bucket()).isEqualTo(Bucket.RECENT);
        assertThat(names(plan.discarded())).containsExactly("older");
    }

    @Test
    void bytesCapCountsSparedInWriteOrderAndNeverDropsThem() {
        // 100 + spared 300 passes 350: everything after the spared one goes, the one before it stays.
        List<Snapshot> snaps = List.of(
                snapshot("s0", NOW.minus(Duration.ofMinutes(1)), 100L),
                snapshot("s1-spared", NOW.minus(Duration.ofMinutes(2)), 300L),
                snapshot("s2", NOW.minus(Duration.ofMinutes(3)), 100L),
                snapshot("s3", NOW.minus(Duration.ofMinutes(4)), 100L));
        BackupRetentionPolicy capped = new BackupRetentionPolicy(10, 0, 0, 0, Duration.ZERO, 350L);
        Plan plan = BackupRetentionService.plan(snaps, Set.of(Path.of("s1-spared")), capped, NOW, UTC);
        assertThat(names(plan.kept())).containsExactly("s0", "s1-spared");
        assertThat(names(plan.discarded())).containsExactly("s2", "s3");
    }

    @Test
    void futureStampGetsNoMilestoneSlot() {
        List<Snapshot> snaps = List.of(snapshot("future", NOW.plus(Duration.ofMinutes(10)), 10L));
        BackupRetentionPolicy hourly = new BackupRetentionPolicy(0, 5, 5, 5, Duration.ZERO, 0L);

        Plan unprotected = BackupRetentionService.plan(snaps, Set.of(), hourly, NOW, UTC);
        Plan spared = BackupRetentionService.plan(snaps, Set.of(Path.of("future")), hourly, NOW, UTC);

        assertThat(unprotected.kept()).as("a future stamp takes no hourly, daily or weekly slot").isEmpty();
        assertThat(spared.decisions().getFirst().bucket()).isEqualTo(Bucket.SPARED);
    }

    @Test
    void weeklySlotsAreContiguousAcrossA52WeekYear() {
        // 2026-01-05 is a Monday; 2025 has 52 ISO weeks. Week keys 1, 2 and 3 must be contiguous calendar weeks.
        Instant now = at("2026-01-05T12:00:00");
        List<Snapshot> snaps = List.of(
                snapshot("2026-01-01", at("2026-01-01T12:00:00"), 10L),
                snapshot("2025-12-24", at("2025-12-24T12:00:00"), 10L),
                snapshot("2025-12-17", at("2025-12-17T12:00:00"), 10L));
        BackupRetentionPolicy weekly = new BackupRetentionPolicy(0, 0, 0, 3, Duration.ZERO, 0L);
        Plan plan = BackupRetentionService.plan(snaps, Set.of(), weekly, now, UTC);
        assertThat(names(plan.kept())).containsExactly("2026-01-01", "2025-12-24");
        assertThat(plan.count(Bucket.WEEKLY)).isEqualTo(2);
    }

    @Test
    void maxBytesEnforcesCumulativeCap() {
        List<Snapshot> snaps = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            snaps.add(snapshot("snap-" + i, NOW.minus(Duration.ofMinutes(i)), 100L));
        }
        // Allow 250 bytes total → newest 2 snapshots (100+100=200) kept, 3rd (300) over budget.
        BackupRetentionPolicy capped = new BackupRetentionPolicy(
                10, 0, 0, 0, Duration.ZERO, 250L);
        Plan plan = BackupRetentionService.plan(snaps, Set.of(), capped, NOW, UTC);
        assertThat(plan.kept()).hasSize(2);
        assertThat(plan.keptBytes()).isEqualTo(200L);
    }

    /**
     * Simulates one checkpoint every 15 minutes for a full year (~35,000
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

        List<Snapshot> snaps = new ArrayList<>((int) count);
        for (long i = 0; i < count; i++) {
            Instant ts = end.minus(cadence.multipliedBy(i));
            snaps.add(snapshot("snap-" + i + ".daw", ts, 1024L));
        }

        BackupRetentionPolicy d = BackupRetentionPolicy.DEFAULT;
        Plan plan = BackupRetentionService.plan(snaps, Set.of(), d, end, UTC);

        // Each bucket count must not exceed its policy cap. RECENT "steals" from HOURLY: the most recent N
        // snapshots occupy the recent bucket, leaving fewer hour slots available to HOURLY.
        assertThat(plan.count(Bucket.RECENT)).isEqualTo(d.keepRecent());
        assertThat(plan.count(Bucket.HOURLY))
                .isPositive()
                .isLessThanOrEqualTo(d.keepHourly());
        assertThat(plan.count(Bucket.DAILY))
                .isPositive()
                .isLessThanOrEqualTo(d.keepDaily());
        // weekly capped by either policy or by the 30-day age limit (4-5 weeks)
        assertThat(plan.count(Bucket.WEEKLY))
                .isLessThanOrEqualTo(d.keepWeekly())
                .isPositive();
        assertThat(plan.count(Bucket.SPARED)).isZero();

        // No snapshot older than maxAge should be kept.
        for (Snapshot s : plan.kept()) {
            Duration age = Duration.between(s.timestamp(), end);
            assertThat(age).isLessThanOrEqualTo(d.maxAge());
        }
        // Cumulative size respects the cap.
        assertThat(plan.keptBytes()).isLessThanOrEqualTo(d.maxBytes());

        // Sum of buckets equals the kept count (sanity).
        long bucketSum = plan.count(Bucket.RECENT)
                + plan.count(Bucket.HOURLY)
                + plan.count(Bucket.DAILY)
                + plan.count(Bucket.WEEKLY);
        assertThat(bucketSum).isEqualTo(plan.kept().size());
    }

    @Test
    void hourlyBucketLeavesOneSlotUnavailableWhenRecentConsumesCurrentHour() {
        // With keepRecent=1 and one snapshot per hour, RECENT consumes hour
        // slot 0, leaving 23 of the 24 HOURLY slots fillable.
        List<Snapshot> snaps = new ArrayList<>();
        for (int h = 0; h < 48; h++) {
            snaps.add(snapshot("snap-" + h, NOW.minus(Duration.ofMinutes(30L + 60L * h)), 100L));
        }
        BackupRetentionPolicy p = new BackupRetentionPolicy(
                1, 24, 0, 0, Duration.ZERO, 0L);
        Plan plan = BackupRetentionService.plan(snaps, Set.of(), p, NOW, UTC);
        assertThat(plan.count(Bucket.RECENT)).isEqualTo(1);
        // Hour slot 0 is consumed by RECENT, leaving 23 of 24 hourly slots fillable.
        assertThat(plan.count(Bucket.HOURLY)).isEqualTo(23);
    }

    @Test
    void disablingAllBucketsKeepsNothing() {
        List<Snapshot> snaps = List.of(snapshot("snap", NOW.minus(Duration.ofMinutes(5)), 10L));
        BackupRetentionPolicy nothing = new BackupRetentionPolicy(
                0, 0, 0, 0, Duration.ZERO, 0L);
        Plan plan = BackupRetentionService.plan(snaps, Set.of(), nothing, NOW, UTC);
        assertThat(plan.kept()).isEmpty();
    }

    private static Snapshot snapshot(String name, Instant timestamp, long sizeBytes) {
        return new Snapshot(Path.of(name), timestamp, sizeBytes);
    }

    private static Instant at(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(UTC).toInstant();
    }

    private static List<String> names(List<Snapshot> snapshots) {
        return snapshots.stream().map(snapshot -> snapshot.path().toString()).toList();
    }
}
