package com.benesquivelmusic.daw.core.persistence.journal;

import com.benesquivelmusic.daw.core.persistence.AtomicFileWriter;
import com.benesquivelmusic.daw.core.persistence.CheckpointFileName;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** Durable session baseline and capture boundaries, kept with the journal they describe. */
public final class JournalState {
    public static final String BASE_FILE = "session-base.daw";
    private JournalState() { }

    public static String hash(Path path) throws IOException {
        return sha256(Files.readAllBytes(path));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public static byte[] capture(long sequence, Path checkpoint) throws IOException {
        return (sequence + "\n" + checkpoint.getFileName() + "\n" + hash(checkpoint)).getBytes(StandardCharsets.UTF_8);
    }

    public static long boundary(JournalRecord marker) {
        if (marker.payload().length == 0) return marker.sequence(); // legacy boundary
        try {
            return Long.parseLong(new String(marker.payload(), StandardCharsets.UTF_8).split("\n", -1)[0]);
        } catch (RuntimeException invalid) {
            return -1L;
        }
    }

    public static List<JournalRecord> records(ProjectContext context) throws IOException {
        return RecoveryScanner.readAllSorted(RecoveryScanner.listSegments(context.journalDirectory()));
    }

    public static boolean needsRecovery(ProjectContext context) throws IOException {
        List<JournalRecord> records = records(context);
        long unreadable = 0L;
        for (Path segment : RecoveryScanner.listSegments(context.journalDirectory())) {
            long decoded = JournalSegment.readAll(segment).stream().mapToLong(JournalRecord::serializedSize).sum();
            unreadable += Files.size(segment) - decoded;
        }
        return needsRecovery(records, unreadable);
    }

    /**
     * {@link #needsRecovery(ProjectContext)} over a journal already read: its records sorted by
     * sequence, and the bytes at the segments' ends no record could be read from.
     */
    static boolean needsRecovery(List<JournalRecord> records, long unreadableBytes) {
        if (unreadableBytes != 0L) return true;
        if (records.isEmpty()) return false;
        long boundary = records.stream().filter(JournalRecord::isCheckpointMarker)
                .mapToLong(JournalState::boundary).max().orElse(-1L);
        return !records.getLast().isSessionSeal() || records.stream()
                .anyMatch(record -> !record.isControl() && record.sequence() > boundary);
    }

    public static void clear(ProjectContext context) throws IOException {
        for (Path path : RecoveryScanner.listSegments(context.journalDirectory())) Files.deleteIfExists(path);
        Files.deleteIfExists(context.journalDirectory().resolve(BASE_FILE));
    }

    /**
     * Writes the session's replay base: an app-owned, byte-for-byte copy of the head, so replay
     * also supports heads with no matching checkpoint. The head is read once, as UTF-8 (replay
     * decodes bases that way), and its in-memory bytes are what the {@code $session-start}
     * certifies. A leftover base is never evidence here, because the caller has already refused
     * every non-empty prior segment: it is deleted (never recursively: a non-empty directory
     * refuses, an empty one is removed like a file, and a link goes as a link), and a fresh file
     * with the directory's default permissions replaces it, so no odd bits are carried over. The
     * base is then read back once and compared, so a session whose base replay could not read
     * never starts.
     *
     * @return the SHA-256 of the base written, or empty when there is no head (nothing is written)
     * @throws IOException if the head cannot be read, the leftover base cannot be deleted, the base
     *                     cannot be written, or it does not read back as written
     */
    static Optional<String> saveBase(ProjectContext context) throws IOException {
        String head;
        try {
            head = Files.readString(context.projectFile());
        } catch (NoSuchFileException noHead) {
            return Optional.empty();
        }
        String written = sha256(head.getBytes(StandardCharsets.UTF_8));
        Path base = context.journalDirectory().resolve(BASE_FILE);
        Files.deleteIfExists(base);
        new AtomicFileWriter().writeString(base, head, AtomicFileWriter.ReplacePermission.DIRECTORY);
        String readBack;
        try {
            readBack = hash(base);
        } catch (IOException unreadable) {
            throw new IOException("The journal's session base cannot be read back (" + reason(unreadable)
                    + "), so a crash in this session could not be recovered: " + base, unreadable);
        }
        if (!readBack.equals(written)) {
            throw new IOException("The journal's session base does not read back as written, "
                    + "so a crash in this session could not be recovered: " + base);
        }
        return Optional.of(written);
    }

    /**
     * Why {@code failure} happened, for a message that already names the file: a
     * {@link FileSystemException}'s reason, any other failure's message, or its type when there is
     * none; never the path. The rule of daw-app's RecordCoordinator.ioReason.
     */
    public static String reason(Throwable failure) {
        String reason = failure instanceof FileSystemException fileSystemFailure
                ? fileSystemFailure.getReason()
                : failure.getMessage();
        if (reason != null && !reason.isBlank()) return reason;
        String simpleName = failure.getClass().getSimpleName();
        return simpleName.isEmpty() ? failure.getClass().getName() : simpleName;
    }

    /**
     * The journal's recovery point, selected in this order: the checkpoint with the highest capture
     * boundary whose durable marker proves its bytes (equal boundaries keep the latest marker);
     * else the session base its {@code $session-start} certifies; else, for an older version's
     * journal with no provenance, {@code project.daw} (preferred to an unrelated, stale
     * checkpoint); else the newest checkpoint. Never throws for a base that is missing or
     * changed, or for no base at all: it returns {@link RecoveryPlan.Unavailable} with the
     * reason, and replay refuses that base.
     *
     * @param context the project
     * @param records the journal's records, sorted by sequence
     * @param head    {@code project.daw} as the plan read it, or empty when there is none
     * @param scanner the scanner that finds the newest checkpoint in its zone
     * @throws IOException if the checkpoints directory cannot be listed, or the newest checkpoint
     *                     an older version's journal falls back to cannot be read
     */
    static RecoveryPlan.Base resolveBase(ProjectContext context, List<JournalRecord> records,
                                         Optional<RecoveryPlan.Head> head, RecoveryScanner scanner)
            throws IOException {
        RecoveryPlan.CheckpointCapture newestCapture = null;
        for (JournalRecord marker : records.reversed()) {
            if (!marker.isCheckpointMarker()) continue;
            String[] fields = new String(marker.payload(), StandardCharsets.UTF_8).split("\n", -1);
            if (fields.length != 3) continue;
            Optional<CheckpointFileName> name = CheckpointFileName.parse(fields[1]);
            if (name.isEmpty()) continue;
            Path candidate = context.checkpointDirectory().resolve(fields[1]);
            long boundary = boundary(marker);
            if (boundary >= -1L && boundary < marker.sequence()
                    && (newestCapture == null || boundary > newestCapture.boundary())
                    && matchesHash(candidate, fields[2])) {
                newestCapture = new RecoveryPlan.CheckpointCapture(candidate, name.get().index(), boundary, fields[2],
                        marker.timestamp());
            }
        }
        if (newestCapture != null) return newestCapture;
        for (JournalRecord start : records) {
            if (!JournalRecord.SESSION_START_TYPE.equals(start.type())) continue;
            Path base = context.journalDirectory().resolve(BASE_FILE);
            String certified = new String(start.payload(), StandardCharsets.UTF_8);
            if (!Files.isRegularFile(base)) return new RecoveryPlan.Unavailable(new RecoverySummary.BaseMissing());
            if (!matchesHash(base, certified)) return new RecoveryPlan.Unavailable(new RecoverySummary.BaseChanged());
            return new RecoveryPlan.SessionStart(base, certified, start.timestamp());
        }
        // Older journals have no provenance. Prefer the head to an unrelated, stale checkpoint.
        if (head.isPresent()) {
            return new RecoveryPlan.SavedHead(context.projectFile(), head.get().sha256(), head.get().savedAt());
        }
        Optional<RecoveryPlan.NewestCheckpoint> legacy = scanner.newestCheckpointBase(context.checkpointDirectory());
        if (legacy.isPresent()) return legacy.get();
        return new RecoveryPlan.Unavailable(new RecoverySummary.NoBase());
    }

    private static boolean matchesHash(Path candidate, String expected) {
        try {
            return Files.isRegularFile(candidate) && hash(candidate).equals(expected);
        } catch (IOException unreadable) {
            return false;
        }
    }
}
