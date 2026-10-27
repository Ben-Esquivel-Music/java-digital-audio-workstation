package com.benesquivelmusic.daw.core.persistence.journal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * The one reading of a project's recovery evidence that both the recovery
 * dialog and the recovery action use, built once per gated open by
 * {@link RecoveryScanner#plan} on the open's worker thread: the dialog shows
 * {@link #summary()}, and Recover everything executes this same instance
 * ({@link JournalReplayer#recover(RecoveryPlan)}, or the promotion of
 * {@link PromoteNewerHead#temp()}), so the two can never describe different
 * evidence.
 *
 * <p>Everything here was read while the plan was built. The actions read the
 * disk again: the replay re-reads every segment strictly and re-verifies
 * {@link Available#sha256()}, and the promotion re-checks the temp's size and
 * time ({@link NewerHead#readUnchanged()}); those fail instead of acting on
 * evidence that changed after it was described. Keeping an interrupted save as
 * a snapshot copies the temp as it is then; only promotion refuses a temp that
 * changed.</p>
 *
 * @param context                the project the plan reads
 * @param recovery               what Recover everything does
 * @param head                   {@code project.daw} as read when the plan was
 *                               built; empty when the folder holds none
 * @param journalEnabled         whether journaled persistence is on for this
 *                               open
 * @param journalPresent         whether a journal segment or the session base
 *                               exists
 * @param segments               the journal segment files, in read order
 * @param journalBytes           their total size in bytes
 * @param unreadableJournalBytes the bytes at their ends no record could be read
 *                               from; 0 when the journal was not read
 * @param sessionRecordTypes     the distinct session (control) record types the
 *                               journal holds, in first-seen order; empty for a
 *                               {@link PromoteNewerHead} plan (the journal is
 *                               not part of that recovery) and when the journal
 *                               was not read
 */
public record RecoveryPlan(
        ProjectContext context,
        Recovery recovery,
        Optional<Head> head,
        boolean journalEnabled,
        boolean journalPresent,
        List<Path> segments,
        long journalBytes,
        long unreadableJournalBytes,
        List<String> sessionRecordTypes) {

    /** The capture boundary of a base that replays every journaled change. */
    static final long NO_BOUNDARY = -1L;

    /**
     * @throws NullPointerException     if an object component is {@code null}
     * @throws IllegalArgumentException if a byte count is negative
     */
    public RecoveryPlan {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(recovery, "recovery must not be null");
        Objects.requireNonNull(head, "head must not be null");
        segments = List.copyOf(Objects.requireNonNull(segments, "segments must not be null"));
        sessionRecordTypes = List.copyOf(Objects.requireNonNull(sessionRecordTypes, "sessionRecordTypes must not be null"));
        if (journalBytes < 0L || unreadableJournalBytes < 0L) {
            throw new IllegalArgumentException("byte counts must be >= 0: " + journalBytes + ", " + unreadableJournalBytes);
        }
    }

    /** {@return the interrupted save this plan keeps or promotes, if there is one} */
    public Optional<NewerHead> interruptedSave() {
        return switch (recovery) {
            case PromoteNewerHead promote -> Optional.of(promote.temp());
            case ReplayJournal journal -> journal.retainedNewerHead();
        };
    }

    /**
     * {@return the immutable description of this plan the recovery dialog renders} A pure
     * projection: no I/O.
     */
    public RecoverySummary summary() {
        RecoverySummary.Recovery described = switch (recovery) {
            case PromoteNewerHead promote ->
                    new RecoverySummary.PromoteNewerHead(promote.temp().writtenAt(), promote.temp().fileName());
            case ReplayJournal journal -> describe(journal);
        };
        return new RecoverySummary(described, head.map(Head::savedAt), journalEnabled, journalPresent,
                segments.stream().map(segment -> segment.getFileName().toString()).toList(),
                journalBytes, unreadableJournalBytes, sessionRecordTypes);
    }

    private RecoverySummary.Recovery describe(ReplayJournal journal) {
        RecoverySummary.Changes changes = changes(journal.mutations());
        Optional<RecoverySummary.InterruptedSave> retained = journal.retainedNewerHead().map(NewerHead::described);
        return switch (journal.base()) {
            case Unavailable unavailable -> new RecoverySummary.ReplayFails(unavailable.reason(), changes, retained);
            case Available base -> new RecoverySummary.Replay(base.kind(), base.displayName(), base.checkpointIndex(),
                    base.time(), relation(base), changes, retained);
        };
    }

    /**
     * {@code project.daw} against a base: IDENTICAL on equal SHA-256 digests (a saved-head base is
     * {@code project.daw} itself), HEAD_OLDER only when the head was last modified strictly before
     * the base was made, HEAD_NEWER otherwise, MISSING without a head.
     */
    private RecoverySummary.HeadRelation relation(Available base) {
        if (head.isEmpty()) return RecoverySummary.HeadRelation.MISSING;
        Head saved = head.get();
        if (saved.sha256().equals(base.sha256())) return RecoverySummary.HeadRelation.IDENTICAL;
        if (saved.savedAt().isBefore(base.time())) return RecoverySummary.HeadRelation.HEAD_OLDER;
        return RecoverySummary.HeadRelation.HEAD_NEWER;
    }

    private static RecoverySummary.Changes changes(List<JournalRecord> mutations) {
        if (mutations.isEmpty()) {
            return new RecoverySummary.Changes(0L, 0L, Duration.ZERO, Optional.empty(), Optional.empty(), List.of());
        }
        Set<String> types = new LinkedHashSet<>();
        long rebuilt = 0L;
        for (JournalRecord mutation : mutations) {
            types.add(mutation.type());
            if (JournalReplayer.rebuilds(mutation.type())) rebuilt++;
        }
        JournalRecord last = mutations.getLast();
        Duration span = Duration.between(mutations.getFirst().timestamp(), last.timestamp());
        return new RecoverySummary.Changes(mutations.size(), rebuilt, span.isNegative() ? Duration.ZERO : span,
                Optional.of(last.type()), Optional.of(last.timestamp()), List.copyOf(types));
    }

    /** What Recover everything does with the evidence. */
    public sealed interface Recovery permits PromoteNewerHead, ReplayJournal {
    }

    /**
     * No journal decision is owed and a complete interrupted save is newer than
     * {@code project.daw}: Recover everything writes its bytes as the project.
     *
     * @param temp the interrupted save
     */
    public record PromoteNewerHead(NewerHead temp) implements Recovery {

        /** @throws NullPointerException if {@code temp} is {@code null} */
        public PromoteNewerHead {
            Objects.requireNonNull(temp, "temp must not be null");
        }
    }

    /**
     * A journal decision is owed: Recover everything loads {@code base} and applies exactly
     * {@code mutations}.
     *
     * @param base              the recovery point, or why there is none
     * @param mutations         the journaled changes after the base's capture boundary: no
     *                          session (control) record, sorted by sequence
     * @param retainedNewerHead a complete interrupted save newer than {@code project.daw}, which
     *                          Restore checkpoint only, and Recover everything when its replay
     *                          can start, keep as a snapshot before anything is deleted
     */
    public record ReplayJournal(Base base, List<JournalRecord> mutations, Optional<NewerHead> retainedNewerHead)
            implements Recovery {

        /**
         * @throws NullPointerException     if a component is {@code null}
         * @throws IllegalArgumentException if a mutation is a session (control) record
         */
        public ReplayJournal {
            Objects.requireNonNull(base, "base must not be null");
            mutations = List.copyOf(Objects.requireNonNull(mutations, "mutations must not be null"));
            if (mutations.stream().anyMatch(JournalRecord::isControl)) {
                throw new IllegalArgumentException("a session record is never a change to replay");
            }
            Objects.requireNonNull(retainedNewerHead, "retainedNewerHead must not be null");
        }
    }

    /** A journal replay's recovery point, or why replay cannot start. */
    public sealed interface Base permits Available, Unavailable {
    }

    /** A recovery point replay can load: a file, the digest it must still have, and its time. */
    public sealed interface Available extends Base permits CheckpointCapture, SessionStart, SavedHead, NewestCheckpoint {

        /** {@return the file replay loads} */
        Path file();

        /**
         * {@return the SHA-256 the file must still have when replay loads it} The digest its
         * journal record certifies, or for a base with no such record the one read while the plan
         * was built.
         */
        String sha256();

        /** {@return when this recovery point was made} */
        Instant time();

        /** {@return the journal sequence this base already holds; only later changes replay} */
        long boundary();

        /** {@return the kind of file this is, as the summary names it} */
        RecoverySummary.BaseKind kind();

        /** {@return the checkpoint index when this base is a checkpoint} */
        default OptionalLong checkpointIndex() {
            return OptionalLong.empty();
        }

        /** {@return the project-relative file name the summary shows} */
        default String displayName() {
            return ProjectContext.CHECKPOINT_DIR_NAME + "/" + file().getFileName();
        }
    }

    /**
     * A checkpoint whose {@code $checkpoint} marker certifies its bytes and the journal sequence
     * it captured: the highest such boundary whose file still matches.
     *
     * @param file       the checkpoint file
     * @param index      its index
     * @param boundary   the last journal sequence it holds
     * @param sha256     the digest the marker records
     * @param capturedAt the marker's time
     */
    public record CheckpointCapture(Path file, long index, long boundary, String sha256, Instant capturedAt)
            implements Available {

        /** @throws NullPointerException if an object component is {@code null} */
        public CheckpointCapture {
            Objects.requireNonNull(file, "file must not be null");
            Objects.requireNonNull(sha256, "sha256 must not be null");
            Objects.requireNonNull(capturedAt, "capturedAt must not be null");
        }

        @Override public Instant time() { return capturedAt; }
        @Override public RecoverySummary.BaseKind kind() { return RecoverySummary.BaseKind.CHECKPOINT_CAPTURE; }
        @Override public OptionalLong checkpointIndex() { return OptionalLong.of(index); }
    }

    /**
     * The copy of {@code project.daw} the session kept when it opened, verified by its
     * {@code $session-start} record.
     *
     * @param file     {@code journal/session-base.daw}
     * @param sha256   the digest the {@code $session-start} record carries
     * @param openedAt the {@code $session-start} record's time
     */
    public record SessionStart(Path file, String sha256, Instant openedAt) implements Available {

        /** @throws NullPointerException if a component is {@code null} */
        public SessionStart {
            Objects.requireNonNull(file, "file must not be null");
            Objects.requireNonNull(sha256, "sha256 must not be null");
            Objects.requireNonNull(openedAt, "openedAt must not be null");
        }

        @Override public Instant time() { return openedAt; }
        @Override public long boundary() { return NO_BOUNDARY; }
        @Override public RecoverySummary.BaseKind kind() { return RecoverySummary.BaseKind.SESSION_START; }
        @Override public String displayName() { return ProjectContext.JOURNAL_DIR_NAME + "/" + JournalState.BASE_FILE; }
    }

    /**
     * {@code project.daw} itself, for a journal an older version wrote with no provenance.
     *
     * @param file    {@code project.daw}
     * @param sha256  its digest when the plan was built
     * @param savedAt its last-modified time
     */
    public record SavedHead(Path file, String sha256, Instant savedAt) implements Available {

        /** @throws NullPointerException if a component is {@code null} */
        public SavedHead {
            Objects.requireNonNull(file, "file must not be null");
            Objects.requireNonNull(sha256, "sha256 must not be null");
            Objects.requireNonNull(savedAt, "savedAt must not be null");
        }

        @Override public Instant time() { return savedAt; }
        @Override public long boundary() { return NO_BOUNDARY; }
        @Override public RecoverySummary.BaseKind kind() { return RecoverySummary.BaseKind.SAVED_HEAD; }
        @Override public String displayName() { return ProjectContext.PROJECT_FILE_NAME; }
    }

    /**
     * The newest checkpoint, for an older version's journal when there is no {@code project.daw}.
     *
     * @param file      the checkpoint file
     * @param index     its index
     * @param sha256    its digest when the plan was built
     * @param stampedAt the time its file name records
     */
    public record NewestCheckpoint(Path file, long index, String sha256, Instant stampedAt) implements Available {

        /** @throws NullPointerException if an object component is {@code null} */
        public NewestCheckpoint {
            Objects.requireNonNull(file, "file must not be null");
            Objects.requireNonNull(sha256, "sha256 must not be null");
            Objects.requireNonNull(stampedAt, "stampedAt must not be null");
        }

        @Override public Instant time() { return stampedAt; }
        @Override public long boundary() { return NO_BOUNDARY; }
        @Override public RecoverySummary.BaseKind kind() { return RecoverySummary.BaseKind.NEWEST_CHECKPOINT; }
        @Override public OptionalLong checkpointIndex() { return OptionalLong.of(index); }
    }

    /**
     * Replay cannot start from this plan: there is no recovery point replay
     * could load, or a journal segment is damaged
     * ({@link RecoverySummary.SegmentDamaged}), which stands in for a recovery
     * point that verified. Replay throws {@link JournalRecoveryException} for
     * this base and writes nothing.
     *
     * @param reason why, as a type daw-app words
     */
    public record Unavailable(RecoverySummary.CannotReplay reason) implements Base {

        /** @throws NullPointerException if {@code reason} is {@code null} */
        public Unavailable {
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    /**
     * {@code project.daw} as read when the plan was built.
     *
     * @param savedAt its last-modified time
     * @param sha256  its SHA-256 digest
     */
    public record Head(Instant savedAt, String sha256) {

        /** @throws NullPointerException if a component is {@code null} */
        public Head {
            Objects.requireNonNull(savedAt, "savedAt must not be null");
            Objects.requireNonNull(sha256, "sha256 must not be null");
        }
    }

    /**
     * A complete atomic-writer temp of {@code project.daw}, newer than it: a save that did not
     * finish. Its time and size are kept so the action can tell whether it is still the file the
     * dialog described.
     *
     * @param temp      the temp file
     * @param writtenAt its last-modified time
     * @param size      its size in bytes
     */
    public record NewerHead(Path temp, Instant writtenAt, long size) {

        /**
         * @throws NullPointerException     if an object component is {@code null}
         * @throws IllegalArgumentException if {@code size} is negative
         */
        public NewerHead {
            Objects.requireNonNull(temp, "temp must not be null");
            Objects.requireNonNull(writtenAt, "writtenAt must not be null");
            if (size < 0L) throw new IllegalArgumentException("size must be >= 0: " + size);
        }

        /** {@return the temp's file name} */
        public String fileName() {
            return temp.getFileName().toString();
        }

        /**
         * Reads the interrupted save's document for promotion, refusing a file that is no longer
         * the one the plan described.
         *
         * @return the document, decoded as UTF-8
         * @throws IOException if the temp cannot be read or decoded, or its size or last-modified
         *                     time differs from the plan's
         */
        public String readUnchanged() throws IOException {
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(temp);
            } catch (NoSuchFileException gone) {
                throw new IOException("The interrupted save was removed after it was described: " + temp, gone);
            }
            if (bytes.length != size || !Files.getLastModifiedTime(temp).toInstant().equals(writtenAt)) {
                throw new IOException("The interrupted save changed after it was described: " + temp);
            }
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        }

        RecoverySummary.InterruptedSave described() {
            return new RecoverySummary.InterruptedSave(writtenAt, fileName());
        }
    }
}
