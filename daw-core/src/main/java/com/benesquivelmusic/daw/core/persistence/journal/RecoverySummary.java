package com.benesquivelmusic.daw.core.persistence.journal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Story 298 — the human-readable summary of a recoverable session, rendered
 * by the daw-app crash-recovery dialog so the user can decide whether to
 * replay the journal on top of the newest checkpoint.
 *
 * @param checkpointIndex      the numeric index of the newest checkpoint
 *                             ({@code checkpoint-NNN-...}), or {@code -1} if
 *                             there is no checkpoint
 * @param checkpointTime       the timestamp parsed from the checkpoint file
 *                             name, or {@code null} if unknown / absent
 * @param checkpointFileName   the newest checkpoint's file name, or
 *                             {@code ""} if absent
 * @param lastEventType        the type tag of the most recent journaled
 *                             record, or {@code ""} if the journal is empty
 * @param lastEventTime        the timestamp of the most recent journaled
 *                             record, or {@code null} if the journal is empty
 * @param replayableEventCount the number of well-formed journal records that
 *                             can be replayed
 * @param replayableSpan       wall-clock span from the first to the last
 *                             journaled record ({@link Duration#ZERO} if fewer
 *                             than two records)
 * @param segmentFileNames     the journal segment file names contributing to
 *                             this summary, in read order
 * @param journalBytes         total size in bytes of the journal segments
 * @param eventTypes           the distinct event type tags present in the
 *                             journal, in first-seen order
 */
public record RecoverySummary(
        int checkpointIndex,
        Instant checkpointTime,
        String checkpointFileName,
        String lastEventType,
        Instant lastEventTime,
        long replayableEventCount,
        Duration replayableSpan,
        List<String> segmentFileNames,
        long journalBytes,
        List<String> eventTypes) {

    /**
     * Canonical constructor. Non-optional string/collection fields are
     * required and the lists are defensively copied to unmodifiable lists;
     * {@code checkpointTime} and {@code lastEventTime} may be {@code null}
     * (documented absence), and {@code replayableSpan} is required.
     */
    public RecoverySummary {
        Objects.requireNonNull(checkpointFileName, "checkpointFileName must not be null");
        Objects.requireNonNull(lastEventType, "lastEventType must not be null");
        Objects.requireNonNull(replayableSpan, "replayableSpan must not be null");
        segmentFileNames = segmentFileNames == null ? List.of() : List.copyOf(segmentFileNames);
        eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
    }

    /** {@return {@code true} if a checkpoint was found} */
    public boolean hasCheckpoint() {
        return checkpointIndex >= 0;
    }

    /** {@return {@code true} if there are any replayable journal records} */
    public boolean hasJournal() {
        return replayableEventCount > 0L;
    }
}
