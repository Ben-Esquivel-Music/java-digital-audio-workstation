package com.benesquivelmusic.daw.core.persistence.journal;

import com.benesquivelmusic.daw.core.persistence.AtomicFileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Durable session baseline and capture boundaries, kept with the journal they describe. */
public final class JournalState {
    public static final String BASE_FILE = "session-base.daw";
    private JournalState() { }

    public static String hash(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
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
        for (Path segment : RecoveryScanner.listSegments(context.journalDirectory())) {
            long decoded = JournalSegment.readAll(segment).stream().mapToLong(JournalRecord::serializedSize).sum();
            if (decoded != Files.size(segment)) return true;
        }
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

    /** An immutable byte-for-byte copy also supports heads with no matching checkpoint. */
    static void saveBase(ProjectContext context) throws IOException {
        if (Files.exists(context.projectFile())) {
            new AtomicFileWriter().writeString(context.journalDirectory().resolve(BASE_FILE), Files.readString(context.projectFile()));
        }
    }

    public record ReplayBase(Path file, long sequence) { }

    /** Select only a checkpoint whose durable marker proves its bytes and capture point. */
    public static ReplayBase replayBase(ProjectContext context) throws IOException {
        List<JournalRecord> records = records(context);
        ReplayBase newestCapture = null;
        for (JournalRecord marker : records.reversed()) {
            if (!marker.isCheckpointMarker()) continue;
            String[] fields = new String(marker.payload(), StandardCharsets.UTF_8).split("\n", -1);
            if (fields.length != 3 || com.benesquivelmusic.daw.core.persistence.CheckpointFileName.parse(fields[1]).isEmpty()) continue;
            Path candidate = context.checkpointDirectory().resolve(fields[1]);
            long boundary = boundary(marker);
            if (boundary >= -1L && boundary < marker.sequence()
                    && (newestCapture == null || boundary > newestCapture.sequence())
                    && matchesHash(candidate, fields[2])) {
                newestCapture = new ReplayBase(candidate, boundary);
            }
        }
        if (newestCapture != null) return newestCapture;
        for (JournalRecord start : records) {
            if (!JournalRecord.SESSION_START_TYPE.equals(start.type())) continue;
            Path base = context.journalDirectory().resolve(BASE_FILE);
            if (!Files.isRegularFile(base) || !hash(base).equals(new String(start.payload(), StandardCharsets.UTF_8))) {
                throw new IOException("The session's replay base is missing or changed: " + base);
            }
            return new ReplayBase(base, -1L);
        }
        // Older journals have no provenance. Prefer the head to an unrelated, stale checkpoint.
        if (Files.isRegularFile(context.projectFile())) return new ReplayBase(context.projectFile(), -1L);
        Path legacy = new RecoveryScanner().findNewestCheckpoint(context.checkpointDirectory())
                .map(RecoveryScanner.Checkpoint::path).orElseThrow(() -> new IOException("No replay base exists"));
        return new ReplayBase(legacy, -1L);
    }

    private static boolean matchesHash(Path candidate, String expected) {
        try {
            return Files.isRegularFile(candidate) && hash(candidate).equals(expected);
        } catch (IOException unreadable) {
            return false;
        }
    }
}
