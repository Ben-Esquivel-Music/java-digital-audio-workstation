package com.benesquivelmusic.daw.core.persistence.journal;

import com.benesquivelmusic.daw.core.concurrent.DawScope;
import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.persistence.UnreadProjectItem;
import com.benesquivelmusic.daw.core.project.DawProject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;

/**
 * Story 298 — the structured, rollback-safe crash-recovery replay
 * (Design Book §4.6.1 / §5.3).
 *
 * <p>{@link #recover(RecoveryPlan)} replays the plan the recovery dialog
 * described: it loads the plan's recovery point into a <em>fresh</em>
 * {@link DawProject} and applies exactly the plan's journaled changes (the
 * mutation records after the point's capture boundary). Two independent
 * inputs fan out as sibling subtasks of a shutdown-on-failure
 * {@link DawScope} — the strict checksum verification of every segment, and
 * the load of the recovery point after its SHA-256 is verified again — then
 * join. The {@code DawScope} (a non-preview reimplementation of JEP 505 on
 * virtual threads) guarantees that the first failing subtask makes
 * {@link DawScope#joinAll()} throw and interrupts the survivors, so the merge
 * step runs <strong>only when both inputs are valid</strong>. Because the
 * merge happens on a fresh project assembled after the join, a corrupt
 * segment, or a recovery point that changed after the plan described it,
 * rolls the whole recovery back via {@link JournalRecoveryException} without
 * ever touching a live project.</p>
 *
 * <h2>No {@code ThreadLocal}</h2>
 * <p>The per-recovery {@link ProjectContext} is threaded into each fork by
 * <em>closure capture</em> — the explicit record argument the story mandates
 * in place of a thread-bound slot.</p>
 *
 * <h2>Apply fidelity</h2>
 * <p>{@link #applyRecords} dispatches each record through one per-type rule,
 * {@link #rebuilds(String)}, which the {@link RecoverySummary}'s rebuilt count
 * also uses. Be honest about its reach: most current {@link
 * com.benesquivelmusic.daw.sdk.event.DawEvent DawEvent}s carry only an entity
 * id and a timestamp (no before/after payload — see {@code DawEvent}'s
 * "notification, not a state-bearing delta" contract), and the journal records
 * them with no payload, so no type is rebuilt today: the records are counted
 * and dispatched, and the recovered project is the recovery point. The
 * structural guarantee this story delivers — <em>atomic, structured,
 * rollback-safe replay onto a verified recovery point with no half-applied
 * state</em> — does not depend on per-type fidelity, and a type is rebuilt as
 * soon as its delta joins {@link #DELTAS}.</p>
 */
public final class JournalReplayer {

    /**
     * The per-type deltas replay applies: a record type listed here changes the recovered
     * project. Empty: no journaled event family carries a reconstructable delta yet.
     */
    private static final Map<String, BiConsumer<DawProject, JournalRecord>> DELTAS = Map.of();

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
     * @param scanner      the recovery scanner that builds the plan
     *                     {@link #recover(ProjectContext)} replays
     */
    public JournalReplayer(ProjectDeserializer deserializer, RecoveryScanner scanner) {
        this.deserializer = Objects.requireNonNull(deserializer, "deserializer must not be null");
        this.scanner = Objects.requireNonNull(scanner, "scanner must not be null");
    }

    /**
     * Whether replaying a record of {@code type} changes the recovered project: the one rule
     * {@link #applyRecords} applies and the {@link RecoverySummary.Changes#rebuilt()} count uses.
     *
     * @param type a mutation record's type tag
     * @return {@code true} if replay rebuilds the change into the recovered project
     */
    static boolean rebuilds(String type) {
        return DELTAS.containsKey(type);
    }

    /**
     * Recovers a project from the journal as it is now: the plan
     * {@link RecoveryScanner#replayPlan(ProjectContext)} builds, replayed by
     * {@link #recover(RecoveryPlan)}.
     *
     * @param ctx the project context
     * @return the recovery result
     * @throws JournalRecoveryException if the evidence cannot be read, there is
     *                                  no recovery point to recover from, or any
     *                                  recovery subtask fails (the recovery is
     *                                  then rolled back)
     */
    public RecoveryResult recover(ProjectContext ctx) {
        Objects.requireNonNull(ctx, "ctx must not be null");
        RecoveryPlan plan;
        try {
            plan = scanner.replayPlan(ctx);
        } catch (IOException failure) {
            throw new JournalRecoveryException("Cannot establish the session replay base", failure);
        }
        return recover(plan);
    }

    /**
     * Replays a journal plan: loads its recovery point, after verifying that the
     * file still has the SHA-256 the plan recorded, into a fresh project and
     * applies exactly its journaled changes, while every segment passes the
     * strict checksum read again.
     *
     * @param plan the plan the recovery dialog described
     * @return the recovery result
     * @throws IllegalArgumentException if the plan promotes an interrupted save
     *                                  instead of replaying a journal
     * @throws JournalRecoveryException if the plan has no recovery point or
     *                                  found a damaged segment (that one is
     *                                  named in the message), the recovery
     *                                  point changed after the plan was built,
     *                                  or any recovery subtask fails (the
     *                                  recovery is then rolled back)
     */
    public RecoveryResult recover(RecoveryPlan plan) {
        Objects.requireNonNull(plan, "plan must not be null");
        if (!(plan.recovery() instanceof RecoveryPlan.ReplayJournal journal)) {
            throw new IllegalArgumentException("Only a journal plan replays; an interrupted save is promoted as written");
        }
        RecoveryPlan.Available base = switch (journal.base()) {
            case RecoveryPlan.Unavailable unavailable -> throw cannotStart(unavailable, plan.context());
            case RecoveryPlan.Available available -> available;
        };
        return replay(plan.context(), base.file(), Optional.of(base.sha256()), plan.segments(), journal.mutations());
    }

    /**
     * Recovers using an explicit checkpoint and segment set, replaying every
     * mutation record in them (test overload).
     *
     * @param ctx            the project context (threaded into each fork)
     * @param checkpointPath the checkpoint file to load
     * @param segmentPaths   the journal segment files to verify + replay
     * @return the recovery result
     * @throws JournalRecoveryException if any subtask fails (rolled back)
     */
    RecoveryResult recover(ProjectContext ctx, Path checkpointPath, List<Path> segmentPaths) {
        Objects.requireNonNull(ctx, "ctx must not be null");
        Objects.requireNonNull(checkpointPath, "checkpointPath must not be null");
        List<Path> segments = List.copyOf(Objects.requireNonNull(segmentPaths, "segmentPaths must not be null"));
        List<JournalRecord> mutations;
        try {
            mutations = RecoveryScanner.readAllSorted(segments).stream().filter(record -> !record.isControl()).toList();
        } catch (IOException failure) {
            throw new JournalRecoveryException("recovery rolled back", failure);
        }
        return replay(ctx, checkpointPath, Optional.empty(), segments, mutations);
    }

    /**
     * The failure of a replay that cannot start. A damaged segment is the reason itself: the
     * recovery point is intact, so the message names the segment, not the base.
     */
    private static JournalRecoveryException cannotStart(RecoveryPlan.Unavailable unavailable, ProjectContext ctx) {
        String why = describe(unavailable, ctx);
        if (unavailable.reason() instanceof RecoverySummary.SegmentDamaged) {
            return new JournalRecoveryException(why, new IOException(why));
        }
        return new JournalRecoveryException("Cannot establish the session replay base", new IOException(why));
    }

    private static String describe(RecoveryPlan.Unavailable base, ProjectContext ctx) {
        Path sessionBase = ctx.journalDirectory().resolve(JournalState.BASE_FILE);
        return switch (base.reason()) {
            case RecoverySummary.BaseMissing _ -> "The session's replay base is missing: " + sessionBase;
            case RecoverySummary.BaseChanged _ -> "The session's replay base has changed: " + sessionBase;
            case RecoverySummary.SegmentDamaged damaged -> "journal segment failed checksum verification: "
                    + ctx.journalDirectory().resolve(damaged.segmentFileName());
            case RecoverySummary.NoBase _ -> "No replay base exists";
        };
    }

    private RecoveryResult replay(ProjectContext ctx, Path baseFile, Optional<String> sha256, List<Path> segments,
                                  List<JournalRecord> mutations) {
        try (DawScope scope = DawScope.openShutdownOnFailure("project-recover")) {
            // Each lambda captures `ctx` explicitly — the no-ThreadLocal seam.
            DawScope.Subtask<Void> checksum = scope.fork("checksum",
                    () -> { verifyChecksums(ctx, segments); return null; });
            DawScope.Subtask<LoadedCheckpoint> base = scope.fork("load-checkpoint",
                    () -> loadCheckpoint(ctx, baseFile, sha256));

            scope.joinAll();   // throws ExecutionException if ANY fork failed

            // Both inputs are valid — merge onto the FRESH project only now.
            LoadedCheckpoint loaded = base.resultNow();
            DawProject recovered = loaded.project();
            int applied = applyRecords(recovered, mutations);

            // Touch the checksum result so its successful completion is observed.
            checksum.resultNow();
            return new RecoveryResult(recovered, applied, loaded.unreadItems());
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
     * the structured scope rolls back. {@link RecoveryScanner} describes a
     * plan with a segment that fails here as one whose replay cannot start.
     * The {@code ctx} argument is the explicitly-threaded context (no
     * {@link ThreadLocal}).
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
     * the file is missing, no longer has the SHA-256 the plan recorded for it
     * ({@code sha256}; the bytes checked are the bytes deserialised), or cannot
     * be deserialised — which fails the recovery and rolls it back. The
     * {@code ctx} argument is the explicitly-threaded context (no
     * {@link ThreadLocal}); its {@link ProjectContext#projectDirectory()} is
     * the base the file's project-relative clip references resolve against
     * (story 323) — a checkpoint lives in {@code <project>/checkpoints/} but its
     * references are relative to {@code <project>/}. The same directory is
     * stamped on the recovered project's metadata (mirroring
     * {@code ProjectManager.openProject}): the references the deserializer
     * resolved are absolute in memory, and a serializer can only write them
     * project-relative again when the project knows its directory. The saved
     * items the read could not honour as written are taken with it, read right
     * after the load in this subtask.
     */
    private LoadedCheckpoint loadCheckpoint(ProjectContext ctx, Path checkpointPath, Optional<String> sha256)
            throws IOException {
        Objects.requireNonNull(ctx, "ctx must not be null");
        if (!Files.exists(checkpointPath)) {
            throw new IOException("checkpoint file not found: " + checkpointPath);
        }
        byte[] bytes = Files.readAllBytes(checkpointPath);
        if (sha256.isPresent() && !JournalState.sha256(bytes).equals(sha256.get())) {
            throw new IOException("The recovery point changed after the recovery was planned: " + checkpointPath);
        }
        String xml = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        DawProject project = deserializer.deserialize(xml, ctx.projectDirectory());
        List<UnreadProjectItem> unreadItems = deserializer.getUnreadItems();
        project.setMetadata(project.getMetadata().withPath(ctx.projectDirectory()));
        return new LoadedCheckpoint(project, unreadItems);
    }

    /** A loaded checkpoint and the saved items its read could not honour as written. */
    private record LoadedCheckpoint(DawProject project, List<UnreadProjectItem> unreadItems) {
    }

    // ---- deterministic merge ----------------------------------------------

    /**
     * Best-effort, extensible apply of {@code records} (already sorted by
     * sequence) onto {@code project}. See the class Javadoc's "Apply fidelity"
     * note: a record changes the project only when {@link #rebuilds(String)}
     * says its type does; every other record is counted and dispatched as a
     * no-op state advance. Session (control) records are skipped.
     *
     * @return the number of records applied (i.e. dispatched, excluding session records)
     */
    int applyRecords(DawProject project, List<JournalRecord> records) {
        Objects.requireNonNull(project, "project must not be null");
        int applied = 0;
        for (JournalRecord record : records) {
            if (record.isControl()) {
                continue;
            }
            applyOne(project, record);
            applied++;
        }
        return applied;
    }

    /**
     * Applies a single record through the one per-type rule: a type whose
     * delta is known changes the project; any other type, known family or
     * unknown / future tag, is accepted as a no-op state advance (the record's
     * existence is the recovery signal; forward compatibility).
     */
    private void applyOne(DawProject project, JournalRecord record) {
        if (rebuilds(record.type())) {
            DELTAS.get(record.type()).accept(project, record);
        }
    }
}
