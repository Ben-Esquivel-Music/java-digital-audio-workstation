package com.benesquivelmusic.daw.core.persistence.journal;

import com.benesquivelmusic.daw.core.persistence.CheckpointFileName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Story 298 — scans a project's on-disk {@code checkpoints/} and
 * {@code journal/} directories to build a {@link RecoverySummary} the
 * daw-app dialog can render.
 *
 * <p>The scanner is read-only: it parses the newest checkpoint's index and
 * timestamp from its file name (the {@link CheckpointFileName} grammar the
 * {@code CheckpointManager} writes; newest by {@link CheckpointFileName#ORDER},
 * the stamp in the name and then the index),
 * reads every {@code segment-*.bin} via
 * {@link JournalSegment#readAll(Path)} (which stops cleanly at a torn tail),
 * sorts the records by sequence, and derives the last-event facts, count,
 * span, distinct types, and total journal byte size.</p>
 */
public final class RecoveryScanner {

    private static final Pattern SEGMENT_NAME =
            Pattern.compile("segment-(\\d+)\\.bin");

    private final ZoneId zone;

    /** Creates a scanner that interprets checkpoint stamps in the system zone. */
    public RecoveryScanner() {
        this(ZoneId.systemDefault());
    }

    /**
     * Creates a scanner that interprets checkpoint stamps in {@code zone}.
     *
     * @param zone the zone used to convert a checkpoint's local-time stamp to
     *             an instant
     */
    public RecoveryScanner(ZoneId zone) {
        this.zone = Objects.requireNonNull(zone, "zone must not be null");
    }

    /**
     * Scans {@code ctx} for a recoverable session.
     *
     * @param ctx the project context
     * @return a summary if there is a checkpoint and/or any journal records;
     *         {@link Optional#empty()} if neither a checkpoint nor a non-empty
     *         journal exists (daw-app then shows "no recovery needed")
     * @throws IOException if a directory exists but cannot be read
     */
    public Optional<RecoverySummary> scan(ProjectContext ctx) throws IOException {
        Objects.requireNonNull(ctx, "ctx must not be null");

        Optional<Checkpoint> checkpoint = findNewestCheckpoint(ctx.checkpointDirectory());
        List<Path> segments = listSegments(ctx.journalDirectory());
        List<JournalRecord> records = readAllSorted(segments);

        if (checkpoint.isEmpty() && records.isEmpty()) {
            return Optional.empty();
        }

        long journalBytes = totalBytes(segments);
        List<String> segmentNames = segments.stream()
                .map(p -> p.getFileName().toString())
                .toList();

        String lastType = "";
        Instant lastTime = null;
        Duration span = Duration.ZERO;
        Set<String> types = new LinkedHashSet<>();
        if (!records.isEmpty()) {
            for (JournalRecord r : records) {
                types.add(r.type());
            }
            JournalRecord last = records.getLast();
            lastType = last.type();
            lastTime = last.timestamp();
            Instant first = records.getFirst().timestamp();
            Duration d = Duration.between(first, lastTime);
            span = d.isNegative() ? Duration.ZERO : d;
        }

        long cpIndex = checkpoint.map(Checkpoint::index).orElse(-1L);
        Instant cpTime = checkpoint.map(Checkpoint::time).orElse(null);
        String cpName = checkpoint.map(Checkpoint::fileName).orElse("");

        return Optional.of(new RecoverySummary(
                cpIndex,
                cpTime,
                cpName,
                lastType,
                lastTime,
                records.size(),
                span,
                segmentNames,
                journalBytes,
                new ArrayList<>(types)));
    }

    // ---- checkpoint discovery ---------------------------------------------

    /**
     * Finds the newest checkpoint in {@code checkpointDir} by
     * {@link CheckpointFileName#ORDER}: the stamp in the name, then the index
     * (story 331, ruling F5), so an earlier session's higher index never
     * outranks a later checkpoint.
     *
     * @param checkpointDir the {@code checkpoints/} directory
     * @return the newest checkpoint, or empty if the directory is missing or
     *         contains no parseable checkpoint files
     */
    Optional<Checkpoint> findNewestCheckpoint(Path checkpointDir) throws IOException {
        if (!Files.isDirectory(checkpointDir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(checkpointDir)) {
            return files
                    .flatMap(file -> CheckpointFileName.parse(file.getFileName().toString())
                            .map(name -> new NamedFile(name, file))
                            .stream())
                    .max(Comparator.comparing(NamedFile::name, CheckpointFileName.ORDER))
                    .map(newest -> new Checkpoint(newest.name().index(), newest.name().instantIn(zone),
                            newest.name().fileName(), newest.file()));
        }
    }

    /** A file whose name the checkpoint grammar accepts, with that name. */
    private record NamedFile(CheckpointFileName name, Path file) {
    }

    // ---- journal segment discovery ----------------------------------------

    /**
     * Lists the {@code segment-*.bin} files in {@code journalDir}, ordered by
     * numeric index.
     *
     * @param journalDir the {@code journal/} directory
     * @return the segment paths, or an empty list if the directory is missing
     */
    static List<Path> listSegments(Path journalDir) throws IOException {
        if (!Files.isDirectory(journalDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(journalDir)) {
            return files
                    .filter(p -> SEGMENT_NAME.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(RecoveryScanner::segmentIndex))
                    .toList();
        }
    }

    private static int segmentIndex(Path segment) {
        Matcher m = SEGMENT_NAME.matcher(segment.getFileName().toString());
        return m.matches() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }

    /**
     * Reads every record from {@code segments} and returns them sorted by
     * global sequence (so out-of-order overflow drains are reconciled).
     */
    static List<JournalRecord> readAllSorted(List<Path> segments) throws IOException {
        List<JournalRecord> all = new ArrayList<>();
        for (Path segment : segments) {
            all.addAll(JournalSegment.readAll(segment));
        }
        all.sort(Comparator.comparingLong(JournalRecord::sequence));
        return all;
    }

    private static long totalBytes(List<Path> segments) throws IOException {
        long total = 0L;
        for (Path segment : segments) {
            if (Files.exists(segment)) {
                total += Files.size(segment);
            }
        }
        return total;
    }

    /** A discovered checkpoint file with its parsed index and time. */
    record Checkpoint(long index, Instant time, String fileName, Path path) {
        Checkpoint {
            Objects.requireNonNull(time, "time must not be null");
            Objects.requireNonNull(fileName, "fileName must not be null");
            Objects.requireNonNull(path, "path must not be null");
        }
    }
}
