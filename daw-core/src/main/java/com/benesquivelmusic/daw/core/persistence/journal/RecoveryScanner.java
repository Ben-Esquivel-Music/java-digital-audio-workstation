package com.benesquivelmusic.daw.core.persistence.journal;

import com.benesquivelmusic.daw.core.persistence.CheckpointFileName;
import com.benesquivelmusic.daw.core.persistence.CheckpointOrder;
import com.benesquivelmusic.daw.core.persistence.RecoveryTemps;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
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
 * Reads a project's recovery evidence once and builds the {@link RecoveryPlan}
 * that the recovery dialog describes and Recover everything executes.
 *
 * <p>The scanner is read-only. It lists {@code journal/segment-*.bin} in index
 * order and reads each segment's bytes once: the records up to a torn tail,
 * the unreadable bytes after them (the measure
 * {@link JournalState#needsRecovery(ProjectContext)} uses), and whether the
 * segment fails the strict read replay's checksum step refuses. The recovery
 * point comes from the same selection replay uses (the highest hash-verified
 * checkpoint capture, then the session base its {@code $session-start}
 * certifies, then for an older version's journal {@code project.daw}, then the
 * newest checkpoint), and the changes are exactly the mutation records after
 * that point's capture boundary. Checkpoints are ordered by their
 * {@link CheckpointFileName} grammar; explicit {@link CheckpointOrder}
 * provenance orders certified checkpoints by index, after the legacy
 * stamp-ordered files.</p>
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
     * Builds the plan for a writable, locked open: what Recover everything
     * would do, or nothing when no decision is owed.
     *
     * <p>A decision is owed when journaled persistence is on and the journal
     * needs recovery, or when {@code temps} holds a complete save newer than
     * {@code project.daw}. Without an owed journal decision the plan promotes
     * that interrupted save; otherwise it replays the journal, keeping any such
     * save aside. An opted-out journal is listed but never read.</p>
     *
     * @param ctx            the project
     * @param temps          the project's atomic-writer temps, as the open
     *                       inspected them
     * @param journalEnabled whether journaled persistence is on
     * @return the plan, or empty exactly when nothing is owed: no journal
     *         decision ({@code journalEnabled} and
     *         {@link JournalState#needsRecovery}) and no newer head temp
     * @throws IOException if the evidence cannot be read
     */
    public Optional<RecoveryPlan> plan(ProjectContext ctx, RecoveryTemps temps, boolean journalEnabled)
            throws IOException {
        Objects.requireNonNull(ctx, "ctx must not be null");
        Objects.requireNonNull(temps, "temps must not be null");
        Evidence journal = journalEnabled ? readJournal(ctx) : listJournal(ctx);
        boolean owed = journalEnabled && JournalState.needsRecovery(journal.records(), journal.unreadableBytes());
        Optional<RecoveryPlan.NewerHead> newerHead = newerHead(temps);
        if (!owed && newerHead.isEmpty()) {
            return Optional.empty();
        }
        if (!owed) {
            return Optional.of(new RecoveryPlan(ctx, new RecoveryPlan.PromoteNewerHead(newerHead.get()), readHead(ctx),
                    journalEnabled, journal.present(), journal.segments(), journal.bytes(), journal.unreadableBytes(),
                    List.of()));
        }
        return Optional.of(replayJournal(ctx, journal, newerHead));
    }

    /**
     * Builds the journal replay plan for {@code ctx} regardless of whether a
     * decision is owed, with no temps: what {@link JournalReplayer#recover(ProjectContext)}
     * replays.
     *
     * @param ctx the project
     * @return the replay plan; its base is {@link RecoveryPlan.Unavailable} when
     *         replay cannot start
     * @throws IOException if the evidence cannot be read
     */
    public RecoveryPlan replayPlan(ProjectContext ctx) throws IOException {
        Objects.requireNonNull(ctx, "ctx must not be null");
        return replayJournal(ctx, readJournal(ctx), Optional.empty());
    }

    private RecoveryPlan replayJournal(ProjectContext ctx, Evidence journal, Optional<RecoveryPlan.NewerHead> newerHead)
            throws IOException {
        Optional<RecoveryPlan.Head> head = readHead(ctx);
        RecoveryPlan.Base base = JournalState.resolveBase(ctx, journal.records(), head, this);
        long boundary = base instanceof RecoveryPlan.Available available ? available.boundary() : RecoveryPlan.NO_BOUNDARY;
        if (base instanceof RecoveryPlan.Available && journal.damagedSegment().isPresent()) {
            base = new RecoveryPlan.Unavailable(new RecoverySummary.SegmentDamaged(journal.damagedSegment().get()));
        }
        List<JournalRecord> mutations = journal.records().stream()
                .filter(record -> !record.isControl() && record.sequence() > boundary)
                .toList();
        Set<String> sessionTypes = new LinkedHashSet<>();
        for (JournalRecord record : journal.records()) {
            if (record.isControl()) sessionTypes.add(record.type());
        }
        return new RecoveryPlan(ctx, new RecoveryPlan.ReplayJournal(base, mutations, newerHead), head, true,
                journal.present(), journal.segments(), journal.bytes(), journal.unreadableBytes(),
                List.copyOf(sessionTypes));
    }

    /** {@code project.daw} as it is now, following a link: its time and digest, or empty when there is none. */
    private static Optional<RecoveryPlan.Head> readHead(ProjectContext ctx) throws IOException {
        Path head = ctx.projectFile();
        if (!Files.isRegularFile(head)) return Optional.empty();
        return Optional.of(new RecoveryPlan.Head(Files.getLastModifiedTime(head).toInstant(), JournalState.hash(head)));
    }

    private static Optional<RecoveryPlan.NewerHead> newerHead(RecoveryTemps temps) throws IOException {
        Path temp = temps.newerHead();
        if (temp == null) return Optional.empty();
        return Optional.of(new RecoveryPlan.NewerHead(temp, Files.getLastModifiedTime(temp).toInstant(), Files.size(temp)));
    }

    // ---- journal evidence --------------------------------------------------

    /**
     * The journal as read once: the segments, their records sorted by sequence,
     * the sizes, the unreadable tail bytes, and the first segment the strict read
     * refuses.
     */
    private record Evidence(List<Path> segments, boolean present, List<JournalRecord> records, long bytes,
                            long unreadableBytes, Optional<String> damagedSegment) {
        Evidence {
            segments = List.copyOf(Objects.requireNonNull(segments, "segments must not be null"));
            records = List.copyOf(Objects.requireNonNull(records, "records must not be null"));
            Objects.requireNonNull(damagedSegment, "damagedSegment must not be null");
        }
    }

    private static Evidence readJournal(ProjectContext ctx) throws IOException {
        List<Path> segments = listSegments(ctx.journalDirectory());
        List<JournalRecord> records = new ArrayList<>();
        long bytes = 0L;
        long unreadable = 0L;
        String damaged = null;
        for (Path segment : segments) {
            byte[] content = Files.readAllBytes(segment);
            List<JournalRecord> decoded = JournalSegment.decode(content);
            records.addAll(decoded);
            bytes += content.length;
            unreadable += content.length - decoded.stream().mapToLong(JournalRecord::serializedSize).sum();
            if (damaged == null && failsStrictRead(segment, content)) {
                damaged = segment.getFileName().toString();
            }
        }
        records.sort(Comparator.comparingLong(JournalRecord::sequence));
        return new Evidence(segments, present(ctx, segments), records, bytes, unreadable, Optional.ofNullable(damaged));
    }

    /** An opted-out journal: listed and sized, never read. */
    private static Evidence listJournal(ProjectContext ctx) throws IOException {
        List<Path> segments = listSegments(ctx.journalDirectory());
        long bytes = 0L;
        for (Path segment : segments) {
            bytes += Files.size(segment);
        }
        return new Evidence(segments, present(ctx, segments), List.of(), bytes, 0L, Optional.empty());
    }

    private static boolean present(ProjectContext ctx, List<Path> segments) {
        return !segments.isEmpty()
                || Files.exists(ctx.journalDirectory().resolve(JournalState.BASE_FILE), LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Exactly the condition replay's checksum step fails a segment on: a
     * complete frame that fails to decode before the end, or bytes from which no
     * record decodes at all.
     */
    private static boolean failsStrictRead(Path segment, byte[] content) {
        try {
            return content.length > 0 && JournalSegment.decodeStrict(content, segment).isEmpty();
        } catch (IOException corrupt) {
            return true;
        }
    }

    // ---- checkpoint discovery ---------------------------------------------

    /**
     * Finds the newest checkpoint file in {@code checkpointDirectory}, by the
     * same order the legacy recovery point uses.
     *
     * @param checkpointDirectory the {@code checkpoints/} directory
     * @return the newest checkpoint file, or empty if the directory is missing
     *         or contains no parseable checkpoint files
     * @throws IOException if the directory exists but cannot be listed
     */
    public Optional<Path> newestCheckpoint(Path checkpointDirectory) throws IOException {
        Objects.requireNonNull(checkpointDirectory, "checkpointDirectory must not be null");
        return newestNamed(checkpointDirectory).map(NamedFile::file);
    }

    /**
     * The newest checkpoint as an older version's recovery point: its index,
     * file-name stamp (in this scanner's zone) and digest as read now.
     */
    Optional<RecoveryPlan.NewestCheckpoint> newestCheckpointBase(Path checkpointDirectory) throws IOException {
        Optional<NamedFile> newest = newestNamed(checkpointDirectory);
        if (newest.isEmpty()) return Optional.empty();
        NamedFile file = newest.get();
        return Optional.of(new RecoveryPlan.NewestCheckpoint(file.file(), file.name().index(),
                JournalState.hash(file.file()), file.name().instantIn(zone)));
    }

    /**
     * Finds the newest checkpoint using explicit monotonic-index provenance.
     * Files predating that fact retain the legacy stamp order, including files
     * whose old per-launch indices happened never to repeat.
     */
    private static Optional<NamedFile> newestNamed(Path checkpointDir) throws IOException {
        if (!Files.isDirectory(checkpointDir)) {
            return Optional.empty();
        }
        Set<String> covered;
        try { covered = CheckpointOrder.read(checkpointDir); }
        catch (IOException invalidFact) {
            java.util.logging.Logger.getLogger(RecoveryScanner.class.getName()).log(java.util.logging.Level.WARNING,
                    "Checkpoint ordering fact unavailable; using legacy stamp order", invalidFact);
            covered = Set.of();
        }
        Comparator<CheckpointFileName> order = CheckpointOrder.comparator(covered);
        try (Stream<Path> files = Files.list(checkpointDir)) {
            return files
                    .flatMap(file -> CheckpointFileName.parse(file.getFileName().toString())
                            .map(name -> new NamedFile(name, file))
                            .stream())
                    .max(Comparator.comparing(NamedFile::name, order));
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
}
