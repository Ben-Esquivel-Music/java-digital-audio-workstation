package com.benesquivelmusic.daw.app.diagnostics;

import com.benesquivelmusic.daw.core.audio.health.AudioFailureCategory;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A fingerprint's sustained buckets on the drain's monotonic clock: three consecutive one-second buckets escalate
 * (story 337, ruling E44), wherever the clock's origin lies. {@link System#nanoTime()} may read negative, so a run
 * starts from its own bucket, never from an earlier run's or from zero.
 */
class FailureTrackTest {

    private static final long SECOND = AudioHealthDrain.BUCKET_NANOS;
    private static final Instant WALL = Instant.parse("2026-11-22T12:00:00Z");

    private final FailureTrack track = new FailureTrack(AudioFailureCategory.CALLBACK);

    @Test
    void threeConsecutiveNegativeBucketsEscalateAtTheThird() {
        assertThat(occurredIn(-4)).isFalse();
        assertThat(occurredIn(-3)).isFalse();
        assertThat(occurredIn(-2)).as("the third consecutive bucket").isTrue();
    }

    @Test
    void aRunThatCrossesZeroEscalatesAtItsThirdBucket() {
        assertThat(occurredIn(-1)).isFalse();
        assertThat(occurredIn(0)).isFalse();
        assertThat(occurredIn(1)).as("the third consecutive bucket").isTrue();
    }

    @Test
    void aGapAfterANegativeBucketStartsANewRun() {
        assertThat(occurredIn(-5)).isFalse();
        assertThat(occurredIn(1)).isFalse();
        assertThat(track.runBegan).as("bucket 1 does not follow bucket -5").isTrue();
        assertThat(occurredIn(2)).as("only two consecutive buckets").isFalse();
    }

    @Test
    void aRestartOnANegativeClockStartsAFreshRunThatEscalates() {
        occurredIn(-10);
        occurredIn(-9);
        track.restartTheBuckets();

        assertThat(occurredIn(-8)).isFalse();
        assertThat(track.runBegan).isTrue();
        assertThat(occurredIn(-7)).isFalse();
        assertThat(occurredIn(-6)).as("the third bucket of the fresh run").isTrue();
    }

    @Test
    void theSameBucketCountedTwiceLeavesTheRunsLengthAlone() {
        occurredIn(5);
        assertThat(occurredIn(5)).isFalse();
        boolean secondOccurrenceBeganARun = track.runBegan;
        int afterTheFirstBucket = track.consecutiveBuckets;
        occurredIn(6);
        occurredIn(6);

        assertThat(secondOccurrenceBeganARun).as("the same bucket continues its run").isFalse();
        assertThat(afterTheFirstBucket).as("one bucket, however many occurrences").isEqualTo(1);
        assertThat(track.consecutiveBuckets).as("two buckets").isEqualTo(2);
        assertThat(occurredIn(7)).as("the third consecutive bucket").isTrue();
    }

    @Test
    void aPositiveClockEscalatesAtTheThirdConsecutiveBucketOnly() {
        assertThat(occurredIn(100)).isFalse();
        assertThat(occurredIn(101)).isFalse();
        assertThat(occurredIn(102)).isTrue();

        FailureTrack gapped = new FailureTrack(AudioFailureCategory.CALLBACK);
        assertThat(gapped.occurred(1, 100 * SECOND, WALL)).isFalse();
        assertThat(gapped.occurred(1, 102 * SECOND, WALL)).isFalse();
        assertThat(gapped.occurred(1, 103 * SECOND, WALL)).as("bucket 101 was skipped").isFalse();
    }

    private boolean occurredIn(long bucket) {
        return track.occurred(1, bucket * SECOND, WALL);
    }
}
