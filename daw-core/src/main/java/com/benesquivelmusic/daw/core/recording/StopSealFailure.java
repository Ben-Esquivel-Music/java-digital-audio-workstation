package com.benesquivelmusic.daw.core.recording;

import java.util.Objects;

/**
 * The seal a stop requested failed for at least one lane (Recording
 * Reliability book §4.3, §5.2 — the FINALIZING row's "write failure →
 * ABORTED with partial take intact + error"; story 323 review): the value
 * {@link CaptureFlushService#stopSealFailure()} and
 * {@link RecordingPipeline#stopSealFailure()} report. When a lane's seal
 * throws in the seal a stop requests — its rename or one of its forces fails,
 * an empty segment cannot be discarded, a listener of its session throws, an
 * {@link Error} included — the flush thread logs it, leaves any segment it
 * could not seal as its {@code .part} for recovery, goes on to the remaining
 * lanes, and writes a final manifest that reads {@code seal-status=aborted}
 * with {@code sealed-by=write-failure} (if that write fails, the manifest on
 * disk is the last one written). That seal is no early seal, so
 * {@link CaptureFlushService#earlySeal()} never completes for it; this is how
 * its failure reaches the caller. A take the flush thread sealed early never
 * has one, whatever a lane threw in that early seal: {@link EarlySeal}
 * reports it.
 *
 * @param failure            the first throwable a lane's seal threw — an
 *                           {@link java.io.UncheckedIOException} from a failed
 *                           rename, force or discard, any other
 *                           {@link RuntimeException}, or an {@link Error}
 * @param everySegmentSealed whether every segment of the take was sealed
 *                           anyway: {@code false} when a segment's seal failed,
 *                           or an empty segment could not be discarded, and its
 *                           {@code .part} file was left for recovery;
 *                           {@code true} when every segment of every lane was
 *                           sealed even so — what threw kept no segment from
 *                           its seal (a listener of a lane's session, say)
 */
public record StopSealFailure(Throwable failure, boolean everySegmentSealed) {

    public StopSealFailure {
        Objects.requireNonNull(failure, "failure must not be null");
    }
}
