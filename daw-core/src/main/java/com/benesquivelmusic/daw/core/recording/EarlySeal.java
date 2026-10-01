package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.recording.TakeManifest.SealedBy;

import java.util.Objects;

/**
 * A take the {@code capture-flush} thread sealed on its own, not for a stop
 * (Recording Reliability book §4.3, §5.2; story 323 review): the value
 * {@link CaptureFlushService#earlySeal()} and
 * {@link RecordingPipeline#earlySeal()} complete with. The flush thread seals
 * a take early when the disk-headroom watch reports
 * {@link DiskHeadroomWatch.State#EXHAUSTED} before a block is written
 * ({@link DiskExhausted}), and when applying a block or forcing a segment on
 * cadence throws, or a throwable escapes its drain loop ({@link WriteFailed}).
 * The final sweep — the pass that drains the ring once a stop has been
 * requested, before that stop's seal runs — runs no cadence tick (an append
 * it makes still runs the writer's own cadence check); there
 * the headroom floor, a block whose application throws, or a throwable that
 * escapes the drain loop seals the take early too. The seal a stop requests
 * is never an early seal.
 */
public sealed interface EarlySeal permits EarlySeal.DiskExhausted, EarlySeal.WriteFailed {

    /**
     * Returns what sealed the take — {@link SealedBy#DISK_EXHAUSTION} or
     * {@link SealedBy#WRITE_FAILURE} — the value
     * {@link CaptureFlushService#sealReason()} reports. The manifest's
     * {@code sealed-by} reads {@code write-failure} instead when a lane's
     * seal threw.
     */
    SealedBy reason();

    /**
     * Returns whether every segment of the take was sealed. {@code false}
     * when the seal of a segment failed — at a rotation, or when its lane
     * was sealed — and that segment was left as its {@code .part} file for
     * recovery.
     */
    boolean everySegmentSealed();

    /**
     * The disk-headroom watch reported {@link DiskHeadroomWatch.State#EXHAUSTED}
     * before a block was written; that block and every later one were not.
     *
     * @param floorBytes         the watch's floor: a probed free-space figure below
     *                           it is EXHAUSTED ({@link DiskHeadroomWatch#DEFAULT_FLOOR_BYTES},
     *                           64 MiB, for the watch {@code RecordingPipeline} builds itself)
     * @param freeSpaceUnknown   whether the watch reported EXHAUSTED because its
     *                           free-space probe had failed
     *                           {@link DiskHeadroomWatch#FAILURES_BEFORE_EXHAUSTED}
     *                           or more times in a row, whatever its last probed figure
     * @param everySegmentSealed see {@link EarlySeal#everySegmentSealed()}
     */
    record DiskExhausted(long floorBytes, boolean freeSpaceUnknown, boolean everySegmentSealed)
            implements EarlySeal {

        public DiskExhausted {
            if (floorBytes < 0) {
                throw new IllegalArgumentException("floorBytes must not be negative: " + floorBytes);
            }
        }

        @Override
        public SealedBy reason() {
            return SealedBy.DISK_EXHAUSTION;
        }
    }

    /**
     * Applying a block or forcing a segment on cadence threw — an
     * {@link java.io.UncheckedIOException} from a write, or any other
     * throwable — or a throwable escaped the drain loop itself.
     *
     * @param failure            the throwable that ended the take
     * @param everySegmentSealed see {@link EarlySeal#everySegmentSealed()}
     */
    record WriteFailed(Throwable failure, boolean everySegmentSealed) implements EarlySeal {

        public WriteFailed {
            Objects.requireNonNull(failure, "failure must not be null");
        }

        @Override
        public SealedBy reason() {
            return SealedBy.WRITE_FAILURE;
        }
    }
}
