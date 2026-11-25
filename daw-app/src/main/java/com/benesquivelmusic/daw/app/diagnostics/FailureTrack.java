package com.benesquivelmusic.daw.app.diagnostics;

import com.benesquivelmusic.daw.core.audio.health.AudioFailureCategory;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/** One streaming category's — or the halt's — state across the {@link AudioHealthDrain}'s frames. FX thread. */
final class FailureTrack {

    /**
     * The stream's heartbeat as one frame's reading has it (story 338, amendment A14): the render drive's beats,
     * and the device periods of the reading's backend report.
     *
     * @param beats         the render-drive beats
     * @param devicePeriods the device-callback periods, or empty
     */
    record Heartbeat(long beats, OptionalLong devicePeriods) {

        /**
         * Whether the stream showed life since {@code earlier}: its render beats advanced, or its device periods
         * did where both readings have them. Either one, because the reading's periods still carry a closed
         * stream's count while a stream without periods of its own is open, so they alone cannot say whether
         * that stream stands still.
         */
        boolean advancedSince(Heartbeat earlier) {
            if (beats > earlier.beats) {
                return true;
            }
            return devicePeriods.isPresent() && earlier.devicePeriods.isPresent()
                    && devicePeriods.getAsLong() > earlier.devicePeriods.getAsLong();
        }
    }

    final AudioFailureCategory category;
    final String site;
    final Fingerprint fingerprint;
    /** Distinct anchors sampled since the fingerprint's last notice, at most {@link AudioHealthDrain#MAX_ANCHORS}. */
    final Set<String> anchors = new LinkedHashSet<>();
    Optional<Instant> firstSeen = Optional.empty();
    Instant lastSeen = Instant.EPOCH;
    /** Every occurrence the drain reported for the fingerprint. */
    long total;
    long noticeNanos;
    boolean bucketed;
    long lastBucket;
    int consecutiveBuckets;
    /** Whether the latest {@link #occurred} began a new run of consecutive buckets. */
    boolean runBegan;
    /** The watched heartbeat when the current run began, for a device-side category; empty before a run. */
    Optional<Heartbeat> runHeartbeat = Optional.empty();

    FailureTrack(AudioFailureCategory category) {
        this.category = category;
        this.site = AudioHealthDrain.siteOf(category);
        this.fingerprint = Fingerprint.ofSite(category.slug(), site);
    }

    /**
     * Counts {@code weight} occurrences in the one-second bucket of {@code now}.
     *
     * @param weight the occurrences
     * @param now    the drain's monotonic clock
     * @param wall   the wall clock
     * @return whether the fingerprint has now occurred in {@link AudioHealthDrain#SUSTAINED_BUCKETS} consecutive
     *         buckets
     */
    boolean occurred(long weight, long now, Instant wall) {
        total += weight;
        if (firstSeen.isEmpty()) {
            firstSeen = Optional.of(wall);
        }
        lastSeen = wall;
        long bucket = Math.floorDiv(now, AudioHealthDrain.BUCKET_NANOS);
        runBegan = !bucketed || bucket > lastBucket + 1;
        if (runBegan) {
            consecutiveBuckets = 1;
        } else if (bucket == lastBucket + 1) {
            consecutiveBuckets++;
        }
        bucketed = true;
        // System.nanoTime may read negative: a run starts from its own bucket, never from the field's zero.
        lastBucket = runBegan ? bucket : Math.max(lastBucket, bucket);
        return consecutiveBuckets >= AudioHealthDrain.SUSTAINED_BUCKETS;
    }

    void addAnchor(String anchor) {
        if (anchors.size() < AudioHealthDrain.MAX_ANCHORS) {
            anchors.add(anchor);
        }
    }

    void noticeShown(long now) {
        noticeNanos = now;
        anchors.clear();
    }

    void restartTheBuckets() {
        bucketed = false;
        consecutiveBuckets = 0;
        runHeartbeat = Optional.empty();
    }
}
