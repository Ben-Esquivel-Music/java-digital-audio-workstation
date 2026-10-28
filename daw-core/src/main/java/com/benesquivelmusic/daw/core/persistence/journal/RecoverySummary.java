package com.benesquivelmusic.daw.core.persistence.journal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * What a recovery decision is about, as the daw-app recovery dialog and its
 * Inspect events view render it: an immutable projection of the one
 * {@link RecoveryPlan} the open built ({@link RecoveryPlan#summary()}), so the
 * dialog describes exactly what Recover everything then does with that same
 * plan.
 *
 * <p>The summary holds facts only. It exposes no {@code Path}, no
 * {@link JournalRecord} and no I/O, and it carries no English: reasons are
 * types ({@link CannotReplay}) and daw-app words them. Restore checkpoint only
 * always opens {@code project.daw}, the last clean save, so that file's time
 * ({@link #lastCleanSave()}) is reported apart from the point Recover
 * everything starts from ({@link #recovery()}).</p>
 *
 * @param recovery               what Recover everything does with this plan
 * @param lastCleanSave          the last-modified time of {@code project.daw},
 *                               the file Restore checkpoint only opens; empty
 *                               when the project folder holds no
 *                               {@code project.daw}
 * @param journalEnabled         whether journaled persistence is on for this
 *                               open; when it is off the journal is kept and
 *                               not read, and neither choice deletes it
 * @param journalPresent         whether the project holds a journal (a segment
 *                               file or the session base), whatever was read
 *                               of it
 * @param segmentFileNames       the journal segment file names, in read order
 * @param journalBytes           the total size in bytes of those segments
 * @param unreadableJournalBytes the bytes at the end of the segments no record
 *                               could be read from (a torn tail); 0 when the
 *                               journal was not read
 * @param sessionRecordTypes     the distinct session (control) record types the
 *                               journal holds, in first-seen order (for example
 *                               {@code $session-start}, {@code $checkpoint},
 *                               {@code $session-seal}); never counted as
 *                               changes. Empty when the journal is not part of
 *                               the recovery ({@link PromoteNewerHead}) or was
 *                               not read
 */
public record RecoverySummary(
        Recovery recovery,
        Optional<Instant> lastCleanSave,
        boolean journalEnabled,
        boolean journalPresent,
        List<String> segmentFileNames,
        long journalBytes,
        long unreadableJournalBytes,
        List<String> sessionRecordTypes) {

    /**
     * @throws NullPointerException     if an object component is {@code null}
     * @throws IllegalArgumentException if a byte count is negative
     */
    public RecoverySummary {
        Objects.requireNonNull(recovery, "recovery must not be null");
        Objects.requireNonNull(lastCleanSave, "lastCleanSave must not be null");
        segmentFileNames = List.copyOf(Objects.requireNonNull(segmentFileNames, "segmentFileNames must not be null"));
        sessionRecordTypes = List.copyOf(Objects.requireNonNull(sessionRecordTypes, "sessionRecordTypes must not be null"));
        if (journalBytes < 0L || unreadableJournalBytes < 0L) {
            throw new IllegalArgumentException("byte counts must be >= 0: " + journalBytes + ", " + unreadableJournalBytes);
        }
    }

    /** What Recover everything does with the plan this summary describes. */
    public sealed interface Recovery permits Replay, PromoteNewerHead, ReplayFails {
    }

    /**
     * Recover everything loads the recovery point, applies the journaled
     * changes after it that replay rebuilds ({@link Changes#rebuilt()}; today
     * none), and writes the result as {@code project.daw}, backing up
     * {@code project.daw} first when there is one.
     *
     * @param baseKind                which kind of file the recovery point is
     * @param baseFileName            the recovery point's project-relative file
     *                                name: {@code checkpoints/<file>},
     *                                {@code journal/session-base.daw} or
     *                                {@code project.daw}
     * @param checkpointIndex         the checkpoint's index when the recovery
     *                                point is a checkpoint, else empty
     * @param baseTime                when the recovery point was made: the
     *                                checkpoint marker's time, the
     *                                {@code $session-start} time, the saved
     *                                head's last-modified time, or the newest
     *                                checkpoint's file-name stamp
     * @param headRelation            how {@code project.daw} relates to the
     *                                recovery point
     * @param changes                 the journaled changes after the recovery
     *                                point, exactly the ones replay applies
     * @param retainedInterruptedSave a complete save of {@code project.daw} that
     *                                did not finish, newer than it, which either
     *                                choice keeps as a snapshot; empty when there
     *                                is none
     */
    public record Replay(BaseKind baseKind, String baseFileName, OptionalLong checkpointIndex, Instant baseTime,
                         HeadRelation headRelation, Changes changes,
                         Optional<InterruptedSave> retainedInterruptedSave) implements Recovery {

        /**
         * @throws NullPointerException     if an object component is {@code null}
         * @throws IllegalArgumentException if {@code checkpointIndex} is present for a base that
         *                                  is not a checkpoint, or empty for one that is
         */
        public Replay {
            Objects.requireNonNull(baseKind, "baseKind must not be null");
            Objects.requireNonNull(baseFileName, "baseFileName must not be null");
            Objects.requireNonNull(checkpointIndex, "checkpointIndex must not be null");
            boolean checkpoint = baseKind == BaseKind.CHECKPOINT_CAPTURE || baseKind == BaseKind.NEWEST_CHECKPOINT;
            if (checkpointIndex.isPresent() != checkpoint) {
                throw new IllegalArgumentException("a checkpoint recovery point, and only one, has an index: "
                        + baseKind + ", " + checkpointIndex);
            }
            Objects.requireNonNull(baseTime, "baseTime must not be null");
            Objects.requireNonNull(headRelation, "headRelation must not be null");
            Objects.requireNonNull(changes, "changes must not be null");
            Objects.requireNonNull(retainedInterruptedSave, "retainedInterruptedSave must not be null");
        }
    }

    /**
     * No journal decision is owed, and a complete save of {@code project.daw}
     * that did not finish is newer than it: Recover everything makes that
     * interrupted save the project, keeping a backup of the last clean save.
     * No journaled change is part of this recovery.
     *
     * @param writtenAt    the interrupted save's last-modified time
     * @param tempFileName the interrupted save's file name (an atomic-writer
     *                     temp beside {@code project.daw})
     */
    public record PromoteNewerHead(Instant writtenAt, String tempFileName) implements Recovery {

        /** @throws NullPointerException if a component is {@code null} */
        public PromoteNewerHead {
            Objects.requireNonNull(writtenAt, "writtenAt must not be null");
            Objects.requireNonNull(tempFileName, "tempFileName must not be null");
        }
    }

    /**
     * Recover everything cannot replay this journal: choosing it keeps the
     * journal, writes nothing, and, when {@code project.daw} exists, opens it
     * read-only.
     *
     * @param reason                  why the replay cannot start
     * @param changes                 the journaled changes that cannot be
     *                                replayed
     * @param retainedInterruptedSave a newer, complete interrupted save that
     *                                Restore checkpoint only keeps as a
     *                                snapshot; empty when there is none
     */
    public record ReplayFails(CannotReplay reason, Changes changes,
                              Optional<InterruptedSave> retainedInterruptedSave) implements Recovery {

        /** @throws NullPointerException if a component is {@code null} */
        public ReplayFails {
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(changes, "changes must not be null");
            Objects.requireNonNull(retainedInterruptedSave, "retainedInterruptedSave must not be null");
        }
    }

    /** Which kind of file a replay's recovery point is. */
    public enum BaseKind {
        /** A checkpoint whose journal marker certifies its bytes and the changes it captured. */
        CHECKPOINT_CAPTURE,
        /** The copy of {@code project.daw} the session kept when it opened ({@code journal/session-base.daw}). */
        SESSION_START,
        /** {@code project.daw} itself: a journal written by an older version, with no provenance. */
        SAVED_HEAD,
        /** The newest checkpoint: an older version's journal, and no {@code project.daw}. */
        NEWEST_CHECKPOINT
    }

    /** How {@code project.daw} relates to a replay's recovery point. */
    public enum HeadRelation {
        /** {@code project.daw} holds the recovery point's bytes (equal SHA-256 digests). */
        IDENTICAL,
        /** The bytes differ and {@code project.daw} was last modified strictly before the recovery point was made. */
        HEAD_OLDER,
        /**
         * The bytes differ and {@code project.daw} was not last modified before the recovery
         * point: Recover everything replaces a save at least as new as its starting point. The
         * conservative default when the times cannot order them.
         */
        HEAD_NEWER,
        /** There is no {@code project.daw}. */
        MISSING
    }

    /** Why Recover everything cannot replay a journal. */
    public sealed interface CannotReplay permits BaseMissing, BaseChanged, SegmentDamaged, NoBase {
    }

    /** The copy of the project the session kept when it opened ({@code journal/session-base.daw}) is missing. */
    public record BaseMissing() implements CannotReplay {
    }

    /**
     * The copy of the project the session kept when it opened no longer holds the bytes its
     * {@code $session-start} record certifies, or cannot be read to check them.
     */
    public record BaseChanged() implements CannotReplay {
    }

    /**
     * A journal segment holds a complete record that fails its checksum before its end (or holds
     * bytes and no readable record), which replay refuses rather than drop what follows.
     *
     * @param segmentFileName the damaged segment's file name
     */
    public record SegmentDamaged(String segmentFileName) implements CannotReplay {

        /** @throws NullPointerException if {@code segmentFileName} is {@code null} */
        public SegmentDamaged {
            Objects.requireNonNull(segmentFileName, "segmentFileName must not be null");
        }
    }

    /** There is no saved project, session copy or checkpoint to start a replay from. */
    public record NoBase() implements CannotReplay {
    }

    /**
     * The journaled changes after a recovery point: mutation records only, never a session
     * (control) record, and never one at or before the point's capture boundary.
     *
     * @param count    how many changes there are
     * @param rebuilt  how many of them replay rebuilds into the recovered project, by the
     *                 per-type rule replay itself applies; the rest are not in the recovered
     *                 project, and once a choice is applied with journaled persistence on, the
     *                 journal that recorded them is deleted
     * @param span     the time from the first change to the last ({@link Duration#ZERO} for fewer
     *                 than two)
     * @param lastType the last change's type tag, empty when there is none
     * @param lastTime the last change's time, empty when there is none
     * @param types    the distinct change type tags, in first-seen order
     */
    public record Changes(long count, long rebuilt, Duration span, Optional<String> lastType,
                          Optional<Instant> lastTime, List<String> types) {

        /**
         * @throws NullPointerException     if an object component is {@code null}
         * @throws IllegalArgumentException if a count is negative, {@code rebuilt} exceeds
         *                                  {@code count}, {@code span} is negative, or the last
         *                                  change's type and time are not both present exactly
         *                                  when there is a change
         */
        public Changes {
            if (count < 0L || rebuilt < 0L || rebuilt > count) {
                throw new IllegalArgumentException("need 0 <= rebuilt <= count: " + rebuilt + ", " + count);
            }
            Objects.requireNonNull(span, "span must not be null");
            if (span.isNegative()) throw new IllegalArgumentException("span must not be negative: " + span);
            Objects.requireNonNull(lastType, "lastType must not be null");
            Objects.requireNonNull(lastTime, "lastTime must not be null");
            boolean anyChange = count > 0L;
            if (lastType.isPresent() != anyChange || lastTime.isPresent() != anyChange) {
                throw new IllegalArgumentException("the last change's type and time are present exactly when there is a"
                        + " change: " + count + ", " + lastType + ", " + lastTime);
            }
            types = List.copyOf(Objects.requireNonNull(types, "types must not be null"));
        }
    }

    /**
     * A complete save of {@code project.daw} that did not finish, newer than it. It is kept as an
     * "Interrupted save" snapshot before anything is deleted: by either choice beside a journal
     * that replays, and only by Restore checkpoint only when the replay cannot start (Recover
     * everything then leaves it in place).
     *
     * @param writtenAt    its last-modified time
     * @param tempFileName its file name (an atomic-writer temp beside {@code project.daw})
     */
    public record InterruptedSave(Instant writtenAt, String tempFileName) {

        /** @throws NullPointerException if a component is {@code null} */
        public InterruptedSave {
            Objects.requireNonNull(writtenAt, "writtenAt must not be null");
            Objects.requireNonNull(tempFileName, "tempFileName must not be null");
        }
    }
}
