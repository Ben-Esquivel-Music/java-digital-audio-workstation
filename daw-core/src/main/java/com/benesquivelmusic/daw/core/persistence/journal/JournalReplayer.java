package com.benesquivelmusic.daw.core.persistence.journal;

import com.benesquivelmusic.daw.core.concurrent.DawScope;
import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.project.DawProject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/**
 * Story 298 — the structured, rollback-safe crash-recovery replay
 * (Design Book §4.6.1 / §5.3).
 *
 * <p>{@link #recover(ProjectContext)} fans three independent inputs out as
 * sibling subtasks of a shutdown-on-failure {@link DawScope} — verify segment
 * checksums, load the newest checkpoint into a <em>fresh</em>
 * {@link DawProject}, and read the journal records — then joins them. The
 * {@code DawScope} (a non-preview reimplementation of JEP 505 on virtual
 * threads) guarantees that the first failing subtask makes
 * {@link DawScope#joinAll()} throw and interrupts the survivors, so the merge
 * step runs <strong>only when all three inputs are valid</strong>. Because the
 * merge happens on a fresh project assembled after the join, a corrupt segment
 * or checkpoint rolls the whole recovery back via
 * {@link JournalRecoveryException} without ever touching a live project.</p>
 *
 * <h2>No {@code ThreadLocal}</h2>
 * <p>The per-recovery {@link ProjectContext} is threaded into each fork by
 * <em>closure capture</em> — the explicit record argument the story mandates
 * in place of a thread-bound slot.</p>
 *
 * <h2>Apply fidelity</h2>
 * <p>{@link #applyRecords} is a clean, extensible dispatch on the record
 * {@code type} tag. Be honest about its reach: most current {@link
 * com.benesquivelmusic.daw.sdk.event.DawEvent DawEvent}s carry only an entity
 * id and a timestamp (no before/after payload — see {@code DawEvent}'s
 * "notification, not a state-bearing delta" contract), so faithful
 * per-mutation state reconstruction is bounded by event richness and is, for
 * now, intentionally minimal (the records are counted and dispatched but most
 * leaf types carry no reconstructable delta). The structural guarantee this
 * story delivers — <em>atomic, structured, rollback-safe replay onto a
 * checkpoint with no half-applied state</em> — does not depend on per-type
 * fidelity, and the dispatch is trivially extended as events gain payloads.</p>
 */
public final class JournalReplayer {

    private final ProjectDeserializer deserializer;
    private final RecoveryScanner scanner;

    /** Creates a replayer with a default deserializer and scanner. */
    public JournalReplayer() {
        this(new ProjectDeserializer(), new RecoveryScanner());
    }

    /**
     * Creates a replayer with explicit collaborators (test seam).
     *
     * @param deserializer the project deserializer
     * @param scanner      the recovery scanner used to build the summary
     */
    public JournalReplayer(ProjectDeserializer deserializer, RecoveryScanner scanner) {
        this.deserializer = Objects.requireNonNull(deserializer, "deserializer must not be null");
        this.scanner = Objects.requireNonNull(scanner, "scanner must not be null");
    }

    /**
     * Recovers a project by structured replay: the newest checkpoint in
     * {@code ctx.checkpointDirectory()} is loaded and every well-formed record
     * across {@code ctx.journalDirectory()}'s segments is replayed onto it.
     *
     * @param ctx the project context
     * @return the recovery result
     * @throws JournalRecoveryException if there is no checkpoint to recover
     *                                  from, or if any recovery subtask fails
     *                                  (the recovery is then rolled back)
     */
    public RecoveryResult recover(ProjectContext ctx) {
        Objects.requireNonNull(ctx, "ctx must not be null");
        Path checkpointPath = newestCheckpointPath(ctx)
                .orElseThrow(() -> new JournalRecoveryException(
                        "no checkpoint to recover from in " + ctx.checkpointDirectory(), null));
        List<Path> segments = listSegments(ctx);
        return recover(ctx, checkpointPath, segments);
    }

    /**
     * Recovers using an explicit checkpoint and segment set (test overload).
     *
     * @param ctx            the project context (threaded into each fork)
     * @param checkpointPath the checkpoint file to load
     * @param segmentPaths   the journal segment files to verify + replay
     * @return the recovery result
     * @throws JournalRecoveryException if any subtask fails (rolled back)
     */
    public RecoveryResult recover(ProjectContext ctx, Path checkpointPath, List<Path> segmentPaths) {
        Objects.requireNonNull(ctx, "ctx must not be null");
        Objects.requireNonNull(checkpointPath, "checkpointPath must not be null");
        Objects.requireNonNull(segmentPaths, "segmentPaths must not be null");
        List<Path> segments = List.copyOf(segmentPaths);

        try (DawScope scope = DawScope.openShutdownOnFailure("project-recover")) {
            // Each lambda captures `ctx` explicitly — the no-ThreadLocal seam.
            DawScope.Subtask<Void> checksum = scope.fork("checksum",
                    () -> { verifyChecksums(ctx, segments); return null; });
            DawScope.Subtask<DawProject> base = scope.fork("load-checkpoint",
                    () -> loadCheckpoint(ctx, checkpointPath));
            DawScope.Subtask<List<JournalRecord>> recs = scope.fork("replay-segment",
                    () -> readRecords(ctx, segments));

            scope.joinAll();   // throws ExecutionException if ANY fork failed

            // All three inputs are valid — merge onto the FRESH project only now.
            DawProject recovered = base.resultNow();
            List<JournalRecord> records = recs.resultNow();
            int applied = applyRecords(recovered, records);

            RecoverySummary summary = buildSummary(ctx, checkpointPath, segments, records);
            // Touch the checksum result so its successful completion is observed.
            checksum.resultNow();
            return new RecoveryResult(recovered, summary, applied);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new JournalRecoveryException("recovery rolled back", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JournalRecoveryException("recovery interrupted", e);
        }
    }

    // ---- subtasks (each receives the explicit ProjectContext) -------------

    /**
     * Re-reads each segment and validates every frame's CRC. A deliberately
     * corrupt segment whose tail fails CRC after some valid records is fine
     * (that is normal truncation); but a segment that is entirely unreadable,
     * or that fails to fully decode where bytes remain, fails the recovery so
     * the structured scope rolls back. The {@code ctx} argument is the
     * explicitly-threaded context (no {@link ThreadLocal}).
     */
    private void verifyChecksums(ProjectContext ctx, List<Path> segments) throws IOException {
        Objects.requireNonNull(ctx, "ctx must not be null");
        for (Path segment : segments) {
            if (!Files.exists(segment)) {
                continue;
            }
            long size = Files.size(segment);
            // readAllStrict throws on a complete-but-corrupt frame in the middle
            // of the segment (which readAll would silently truncate at, dropping
            // every record after it). A benign torn tail still decodes cleanly.
            List<JournalRecord> recovered = JournalSegment.readAllStrict(segment);
            // A non-empty segment that yields zero decodable records is
            // corrupt at the head — treat as a recovery failure.
            if (size > 0L && recovered.isEmpty()) {
                throw new IOException("journal segment failed checksum verification: " + segment);
            }
        }
    }

    /**
     * Loads {@code checkpointPath} into a fresh {@link DawProject}. Throws if
     * the checkpoint is missing or cannot be deserialised — which fails the
     * recovery and rolls it back. The {@code ctx} argument is the
     * explicitly-threaded context (no {@link ThreadLocal}).
     */
    private DawProject loadCheckpoint(ProjectContext ctx, Path checkpointPath) throws IOException {
        Objects.requireNonNull(ctx, "ctx must not be null");
        if (!Files.exists(checkpointPath)) {
            throw new IOException("checkpoint file not found: " + checkpointPath);
        }
        String xml = Files.readString(checkpointPath);
        return deserializer.deserialize(xml);
    }

    /**
     * Reads every well-formed record across {@code segments}, sorted by
     * sequence. The {@code ctx} argument is the explicitly-threaded context
     * (no {@link ThreadLocal}).
     */
    private List<JournalRecord> readRecords(ProjectContext ctx, List<Path> segments)
            throws IOException {
        Objects.requireNonNull(ctx, "ctx must not be null");
        return RecoveryScanner.readAllSorted(segments);
    }

    // ---- deterministic merge ----------------------------------------------

    /**
     * Best-effort, extensible apply of {@code records} (already sorted by
     * sequence) onto {@code project}. See the class Javadoc's "Apply fidelity"
     * note: most current event records carry only identity + timestamp, so the
     * dispatch counts and routes records but reconstructs no per-mutation state
     * for the payload-free leaf types. Checkpoint markers are skipped.
     *
     * @return the number of records applied (i.e. dispatched, excluding markers)
     */
    int applyRecords(DawProject project, List<JournalRecord> records) {
        Objects.requireNonNull(project, "project must not be null");
        int applied = 0;
        for (JournalRecord record : records) {
            if (record.isCheckpointMarker()) {
                continue;
            }
            applyOne(project, record);
            applied++;
        }
        return applied;
    }

    /**
     * Applies a single record. Dispatches on the family prefix of the type
     * tag; payload-free families are accepted as no-op-state advances (the
     * record's existence is the recovery signal). Extend the branches as
     * events gain reconstructable payloads.
     */
    private void applyOne(DawProject project, JournalRecord record) {
        String type = record.type();
        // Family-level dispatch — ready to be specialised per leaf type as
        // payloads arrive. Today these are intentionally non-mutating because
        // the source events carry no before/after delta.
        if (type.startsWith("MixerEvent.")) {
            return;
        } else if (type.startsWith("TrackEvent.")) {
            return;
        } else if (type.startsWith("ClipEvent.")) {
            return;
        } else if (type.startsWith("AutomationEvent.")) {
            return;
        } else if (type.startsWith("PluginEvent.")) {
            return;
        } else if (type.startsWith("TransportEvent.")) {
            return;
        } else if (type.startsWith("ProjectEvent.")) {
            return;
        }
        // Unknown / future type tags are tolerated (forward compatibility).
    }

    // ---- summary + discovery helpers --------------------------------------

    private RecoverySummary buildSummary(ProjectContext ctx, Path checkpointPath,
                                         List<Path> segments, List<JournalRecord> records) {
        try {
            return scanner.scan(ctx).orElseGet(() -> minimalSummary(checkpointPath, segments, records));
        } catch (IOException e) {
            return minimalSummary(checkpointPath, segments, records);
        }
    }

    private static RecoverySummary minimalSummary(Path checkpointPath, List<Path> segments,
                                                  List<JournalRecord> records) {
        List<String> segmentNames = new ArrayList<>();
        for (Path s : segments) {
            segmentNames.add(s.getFileName().toString());
        }
        String lastType = records.isEmpty() ? "" : records.getLast().type();
        var lastTime = records.isEmpty() ? null : records.getLast().timestamp();
        return new RecoverySummary(
                -1, null, checkpointPath.getFileName().toString(),
                lastType, lastTime, records.size(),
                java.time.Duration.ZERO, segmentNames, 0L, List.of());
    }

    private Optional<Path> newestCheckpointPath(ProjectContext ctx) {
        try {
            return scanner.findNewestCheckpoint(ctx.checkpointDirectory())
                    .map(RecoveryScanner.Checkpoint::path);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static List<Path> listSegments(ProjectContext ctx) {
        try {
            return RecoveryScanner.listSegments(ctx.journalDirectory());
        } catch (IOException e) {
            return List.of();
        }
    }
}
