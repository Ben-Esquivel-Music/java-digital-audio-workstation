package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.persistence.backup.BackupRetentionService;
import com.benesquivelmusic.daw.sdk.event.AutoSaveListener;
import com.benesquivelmusic.daw.sdk.persistence.BackupRetentionPolicy;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Writes a project's checkpoints — the numbered copies of its document in
 * {@code checkpoints/} that protect an hours-long session against a crash —
 * on an autosave cadence and on request (story 331; Persistence Integrity
 * book §2.3, §4.2, §4.8, §5.2).
 *
 * <h2>Checkpoint file layout</h2>
 * <pre>
 *   project-dir/
 *     checkpoints/
 *       checkpoint-001-20260321T220000.daw
 *       checkpoint-002-20260321T220500.daw
 *       ...
 * </pre>
 * <p>Names follow {@link CheckpointFileName}; the index continues from the
 * highest one already in the directory, so it never restarts.</p>
 *
 * <h2>Where each step runs</h2>
 * <ul>
 *   <li><strong>The scheduler</strong> (one platform daemon thread per
 *       started project) only arms and fires ticks: a tick posts the capture
 *       to the owner executor and returns. It never blocks on, or waits for,
 *       the owner. A tick that comes while the previous tick's checkpoint is
 *       still being captured or written is dropped: at most one capture is
 *       ever pending (ruling F2).</li>
 *   <li><strong>The owner</strong> — the {@link Executor} given to
 *       {@link #setCaptureSource} (in the application, the JavaFX application
 *       thread) — reads the project's revision and clean flag and captures
 *       the project ({@link CheckpointSource}); it does no disk I/O. A revision
 *       already checkpointed is not captured at all (ruling F4).</li>
 *   <li><strong>The writer</strong> of the project's folder — one serial
 *       writer per folder, a virtual thread started on demand while jobs are
 *       queued, so it can never reject one — runs every job of that folder in
 *       order: seeding at {@link #start}, every tick's write and every
 *       {@link #writeCheckpoint} (ruling F2: one ordered writer of the
 *       directory). It renders the capture, allocates the index, writes the
 *       file through the {@link AtomicFileWriter}, keeps the index of the
 *       directory and prunes it. Two generations share a writer only when
 *       their folders are one ({@link FolderFingerprint}, story 333): a write
 *       held on one folder's stalled volume never holds another folder's
 *       seeding or writes, and two names of one folder never write it at
 *       once — unless one name's identity could not be read while the two
 *       real paths differ (a share name and a drive letter; see
 *       {@link FolderFingerprint}). A generation's seeding first reads its folder's fingerprint, on
 *       its own folder's path only, and then joins the writer already running
 *       jobs in that folder, behind them, or becomes that folder's writer.</li>
 * </ul>
 * <p>No step reads the live model off the owner, and the owner never touches
 * the disk (book §6.1). This package takes no JavaFX dependency.</p>
 *
 * <h2>One write</h2>
 * <p>{@code onBeforeCheckpoint}; the generation's {@linkplain WriteGuard write
 * guard} (the project lock is still ours); the render; the claim of the write
 * (see "Generations"); the next index (only now, so
 * a render failure uses none: rulings F8, G13 #11); the atomic write of a new
 * file; the revision it holds is recorded as checkpointed; the file joins the
 * index; {@code onAfterCheckpoint} and {@link CheckpointEvent.Written}; then
 * pruning (ruling G13 #1). Any {@link Throwable} from the capture, an owner
 * that rejects it, the guard, the render or the write is logged at
 * {@code SEVERE} and reported exactly once — {@code onCheckpointFailed} and
 * {@link CheckpointEvent.Failed} — and nothing is written; the cadence keeps
 * ticking. A prune that fails is logged at {@code WARNING} and retried after
 * the next write, and a checkpoint that refuses deletion is skipped (see
 * "Retention"); either way the checkpoint it follows stays a success. A
 * listener that throws is logged at {@code WARNING}; the other listeners are
 * still notified, and it changes no outcome. There is no fallback content:
 * with no capture source a tick writes nothing (ruling F8).</p>
 *
 * <h2>Retention</h2>
 * <p>{@link #start} seeds the index from the directory (ruling F5, book
 * §4.8): every name the grammar accepts counts, across sessions, and nothing
 * is pruned at open. After each write checkpoints are deleted until at most
 * {@link AutoSaveConfig#maxCheckpoints()} remain (ruling H4): first the
 * seeded ones, oldest first by {@link CheckpointFileName#ORDER}; only when
 * none is left, this generation's own writes, oldest written first — every
 * seeded file predates every file this generation wrote, which needs no
 * clock, zone or daylight-saving rule to know. The checkpoint just written,
 * and the one holding the last checkpointed revision, are never deleted. A
 * name the grammar rejects is never counted or deleted. Seeding also deletes
 * the stray temps a hard kill can leave — exactly the
 * {@link AtomicFileWriter}'s temps of checkpoint names (ruling G6) — while
 * the write guard passes.</p>
 * <p>A checkpoint that refuses deletion — held open by an antivirus scanner,
 * the search indexer or a sync client, or denied by its ACL — is skipped and
 * the prune goes on in the same order (ruling J1). It stays on disk and in
 * the index but no longer counts toward the cap, so {@code checkpoints/}
 * holds the cap plus each checkpoint that refuses. Each later prune retries
 * at most one of them, the one tried least recently, at its place in the
 * order; never in the prune that refused it, and without waiting. A prune
 * that starts at or below the cap, or stops before the retry's place,
 * retries none. Anything the delete of one file throws is a refusal; a file
 * already gone counts as deleted. A generation logs its first refusal at
 * {@code WARNING}, and every later refusal and a refused file's eventual
 * deletion at {@code FINE}; no listener hears of a refusal.</p>
 *
 * <h2>Autosave disabled</h2>
 * <p>{@link AutoSaveConfig#enabled()} turns off the automatic checkpoints —
 * the scheduled ticks and the checkpoint after a save — not the generation
 * (ruling H3). A start still seeds the directory; no tick is armed and no
 * re-arm is reported; a {@link CheckpointOrigin#AFTER_SAVE} requested while
 * it is disabled is refused at the call (ruling J2), and one or a scheduled
 * job that reaches the writer after it was disabled writes nothing; neither
 * tells a listener. A user's checkpoint and a failed save's safety copy are
 * written as always.</p>
 *
 * <h2>Generations</h2>
 * <p>Each {@link #start} begins a generation and {@link #stop} ends it, under
 * the lock a job takes to claim its work in the folder (story 333). A claim is
 * the only way a job writes or deletes there: a write claims after its render
 * and holds the claim through its index, its atomic write and its prune; a
 * seeding with an ordering fact to compact or stray temps to delete claims
 * around that compaction and those deletes. No job of an ended generation
 * claims after {@code stop()};
 * a capture not yet taken is dropped, and the futures of jobs still queued
 * complete at once, off the caller's thread. {@code stop()} itself never
 * waits: it returns a stage that completes once the generation's claimed work
 * has ended, and {@link ProjectManager} releases the project's lock only
 * then, so nothing of this session writes or deletes in a folder after its
 * lock is released. A write already inside the atomic write when its
 * generation ends finishes into its own project's directory and is reported
 * to no listener (ruling F9, G5); it prunes as usual, until five seconds
 * after {@code stop()} ({@link #STOP_WAIT}), from when a claim still held
 * decides no further delete. A
 * checkpoint {@link ProjectManager} requests for a save is bound, by
 * identity, to the generation that project's open started
 * ({@link Generation}): once that generation has ended it writes nothing
 * and tells no listener, whatever generation runs then — another project's,
 * or the same folder's opened again — so a save never writes into another
 * generation's checkpoints.</p>
 */
public final class CheckpointManager {

    /**
     * Checked on the writer just before each checkpoint is written, before
     * the ordering fact is compacted and before seeding deletes a stray temp:
     * that this session may still write the project folder. Each generation
     * has its own ({@link #startGeneration(Path, WriteGuard)}, story 333):
     * {@link ProjectManager} gives each the lock check of the lease its own
     * open or create took ({@link ProjectLockManager#verifyHeld()}, which also
     * refuses when that lease holds no lock), so a job of a project still
     * running after a switch checks that project's lease, never the next
     * project's. A guard that throws fails the checkpoint visibly.
     */
    @FunctionalInterface
    public interface WriteGuard {

        /** @throws IOException if this session must not write the project folder */
        void check() throws IOException;
    }

    /**
     * One generation of a project's checkpoints — what one {@link #start}
     * began — as an opaque handle (story 331, ruling F9): a checkpoint
     * requested against it through
     * {@link #writeCheckpoint(Generation, String, long, CheckpointOrigin)}
     * while it runs is queued as one of its jobs, which writes into its
     * project's {@code checkpoints/} unless the generation's end drops it
     * first; its render or its write fails; for any but a
     * {@link CheckpointOrigin#USER} or a {@link CheckpointOrigin#CLOSING}
     * checkpoint, the revision it holds is
     * already checkpointed — it then names that checkpoint and writes nothing
     * (ruling G9 (2)); or, for an {@link CheckpointOrigin#AFTER_SAVE},
     * autosave is disabled (ruling H3). One requested once it has ended is
     * refused, whatever generation runs then. The listeners hear of such a
     * checkpoint as written or failed only if it was written or failed
     * before its generation ended — not when {@link #stop()} drops it, nor
     * when it is written or fails while {@code stop()} ends its generation,
     * nor when an {@code AFTER_SAVE} is refused because autosave is disabled;
     * a skipped one only re-arms the countdown. Two generations are never
     * the same one, even on the same folder. {@link ProjectManager} keeps the
     * one each writable open started.
     */
    static final class Generation {

        private final Session session;

        private Generation(Session session) {
            this.session = session;
        }

        @Override
        public String toString() {
            return session.toString();
        }
    }

    /** Deletes a pruned checkpoint or a stray temp; a test fails it to pin a prune failure. */
    @FunctionalInterface
    interface FileDeleter {

        /** @throws IOException if {@code file} cannot be deleted ({@link NoSuchFileException} when it is gone) */
        void delete(Path file) throws IOException;
    }

    private static final Logger LOG = Logger.getLogger(CheckpointManager.class.getName());

    private static final String CHECKPOINT_DIR_NAME = "checkpoints";

    /**
     * How long after {@link #stop()} a claim of the ended generation may
     * still decide a delete (ruling G5 (3)): past it, a claim still held
     * deletes nothing more. {@code stop()} never waits for it.
     */
    static final Duration STOP_WAIT = Duration.ofSeconds(5);

    /** A guard that always passes: {@link #start(Path)}'s, for callers that hold no lock. */
    private static final WriteGuard PASSES = () -> { };

    /** Runs {@link #stop()}'s bound once it passed, and completes stopped jobs' futures: never the caller. */
    private static final Executor STOP_THREADS = task -> Thread.ofVirtual().name("daw-checkpoint-stop").start(task);

    /** The stamp of a checkpoint id, in ASCII digits under every default locale. */
    private static final DateTimeFormatter ID_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT).withZone(ZoneId.systemDefault());

    private volatile AutoSaveConfig config;
    private final AtomicFileWriter fileWriter;
    private final InstantSource clock;
    private final FileDeleter deleter;
    private final Duration stopWait;
    private final Executor writerExecutor;

    private final List<AutoSaveListener> listeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<CheckpointEvent>> eventListeners = new CopyOnWriteArrayList<>();
    @FunctionalInterface interface DirectoryReader { DirectoryStream<Path> open(Path directory) throws IOException; }
    private DirectoryReader directoryReader = Files::newDirectoryStream;
    void setDirectoryReader(DirectoryReader reader) { directoryReader = Objects.requireNonNull(reader); }
    private volatile Function<Path, FolderFingerprint> folderFingerprints = FolderFingerprint::of;

    /** Test seam: how a generation's seeding reads its folder's fingerprint (story 333). */
    void setFolderFingerprints(Function<Path, FolderFingerprint> fingerprints) {
        folderFingerprints = Objects.requireNonNull(fingerprints, "fingerprints must not be null");
    }
    private final AtomicReference<java.util.function.LongSupplier> journalSequenceSource = new AtomicReference<>();

    /** The revision and optional journal boundary of one immutable model capture. */
    public record Capture(long revision, OptionalLong journalSequence) {
        public Capture { Objects.requireNonNull(journalSequence, "journalSequence must not be null"); }
    }

    /** Called on the model owner immediately before its immutable capture. */
    public Capture captureStarted(long revision) {
        java.util.function.LongSupplier source = journalSequenceSource.get();
        return new Capture(revision, source == null ? OptionalLong.empty() : OptionalLong.of(source.getAsLong()));
    }

    /** Installs the current journal's publication fence; removing an old registration leaves a newer one intact. */
    public Runnable observeJournalSequence(java.util.function.LongSupplier source) {
        journalSequenceSource.set(Objects.requireNonNull(source));
        return () -> journalSequenceSource.compareAndSet(source, null);
    }
    private volatile BackupRetentionPolicy retentionPolicy;
    private volatile CaptureSource captureSource;

    /**
     * Serializes {@link #start}, {@link #stop} and {@link #reconfigure}; never
     * taken by a writer, and never held across disk I/O or a wait.
     */
    private final ReentrantLock lifecycle = new ReentrantLock();

    /**
     * Guards each generation's end ({@link Session#ended}), its count of jobs
     * the writers hold and its claims, and the folder writers: a job claims
     * its work under it, and {@link #stop()} ends the generation under it.
     * Never held across disk I/O.
     */
    private final ReentrantLock stateLock = new ReentrantLock();
    private final Condition writerReleased = stateLock.newCondition();

    /**
     * Guarded by {@link #stateLock}: the jobs of every generation the writers
     * hold, queued or running — the sum of each generation's
     * {@link Session#heldJobs}, which the wait at exit asks (ruling M1).
     */
    private int heldJobsOfEveryGeneration;

    /**
     * Guarded by {@link #stateLock}: the {@link #whenNoJobIsHeld()} stages
     * still waiting, completed — outside the lock — when
     * {@link #heldJobsOfEveryGeneration} drops to zero (story 333).
     */
    private final List<CompletableFuture<Void>> noJobWaiters = new ArrayList<>();

    /**
     * Guarded by {@link #stateLock}: the folder writers a generation's seeding
     * may join — each with its folder's fingerprint — in the order they
     * registered. A writer leaves once no generation attached to it holds a
     * job and none of them runs any more (story 333).
     */
    private final List<FolderWriter> writers = new ArrayList<>();

    private final AtomicLong generations = new AtomicLong();
    private final AtomicLong checkpointIds = new AtomicLong();

    /** The current generation, or the last one once stopped; {@code null} before the first start. */
    private volatile Session session;

    /**
     * Creates a new checkpoint manager with the given auto-save configuration.
     *
     * @param config the auto-save configuration
     */
    public CheckpointManager(AutoSaveConfig config) {
        this(config, new AtomicFileWriter());
    }

    /**
     * Creates a checkpoint manager whose checkpoint files are written through
     * {@code fileWriter} — used by tests that fail or observe the writes
     * (story 331).
     *
     * @param config     the auto-save configuration
     * @param fileWriter the atomic-replace writer of every checkpoint file
     */
    public CheckpointManager(AutoSaveConfig config, AtomicFileWriter fileWriter) {
        this(config, fileWriter, InstantSource.system(), Files::delete, STOP_WAIT);
    }

    /**
     * The test seam: the clock of names and due times, the deleter of pruned
     * checkpoints and stray temps, and the bound after {@link #stop()} past
     * which a claim still held deletes nothing more.
     */
    CheckpointManager(AutoSaveConfig config, AtomicFileWriter fileWriter, InstantSource clock, FileDeleter deleter,
                      Duration stopWait) {
        this(config, fileWriter, clock, deleter, stopWait,
                task -> Thread.ofVirtual().name("daw-checkpoint-writer").start(task));
    }

    /** A controlled writer executor pins publication ordering without relying on thread scheduling in tests. */
    CheckpointManager(AutoSaveConfig config, AtomicFileWriter fileWriter, InstantSource clock, FileDeleter deleter,
                      Duration stopWait, Executor writerExecutor) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.fileWriter = Objects.requireNonNull(fileWriter, "fileWriter must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.deleter = Objects.requireNonNull(deleter, "deleter must not be null");
        this.stopWait = Objects.requireNonNull(stopWait, "stopWait must not be null");
        this.writerExecutor = Objects.requireNonNull(writerExecutor, "writerExecutor must not be null");
    }

    /**
     * Starts the checkpoints of the project in {@code projectDirectory}: a new
     * generation, whose first writer job seeds the index from
     * {@code checkpoints/} (see the class note, "Retention"). The first tick
     * is armed a full interval after seeding finishes. This call does no disk
     * I/O. A generation still running — another project, or this one — is
     * {@linkplain #stop() stopped} first, which never waits. With autosave
     * disabled the generation still begins and seeds, so user checkpoints and
     * safety copies can be written, but no tick is armed (ruling H3).
     *
     * <p>Its write guard always passes: this form is for tests and for
     * callers that hold no project lock. {@link ProjectManager} starts each
     * generation with its own lease's lock check
     * ({@link #startGeneration(Path, WriteGuard)}).</p>
     *
     * @param projectDirectory the directory where checkpoints will be stored
     * @return completes when seeding finished (exceptionally when the
     *         directory could not be read, or the generation was stopped
     *         first)
     */
    public CompletableFuture<Void> start(Path projectDirectory) {
        return start(projectDirectory, PASSES);
    }

    /** {@link #start(Path)} with {@code guard} as the generation's write guard (test seam). */
    CompletableFuture<Void> start(Path projectDirectory, WriteGuard guard) {
        return startGeneration(projectDirectory, guard).session.seeded;
    }

    /**
     * {@link #start}, returning the generation it began, so that checkpoints
     * can be requested against that generation alone
     * ({@link #writeCheckpoint(Generation, String, long, CheckpointOrigin)}),
     * and checking {@code guard} before each checkpoint write, each
     * compaction of the ordering fact and each stray-temp delete of that
     * generation — a prune's deletes run inside a write that passed it and do
     * not check it again (story 333: {@link ProjectManager} passes the lock
     * check of the lease the open or the create took). This call does no disk I/O: the
     * generation's seeding, its first job, reads the folder's
     * {@linkplain FolderFingerprint fingerprint} on a writer of its own and
     * then joins the writer of that folder (see the class note).
     *
     * @param projectDirectory the directory where checkpoints will be stored
     * @param guard            what each checkpoint write, compaction and stray-temp delete of the generation
     *                         checks first (a prune's deletes run inside a write that passed it)
     * @return the generation this call began
     */
    Generation startGeneration(Path projectDirectory, WriteGuard guard) {
        Objects.requireNonNull(projectDirectory, "projectDirectory must not be null");
        Objects.requireNonNull(guard, "guard must not be null");
        lifecycle.lock();
        try {
            Session previous = session;
            if (previous != null && !previous.ended) {
                stop();
            }
            Session started = new Session(generations.incrementAndGet(), projectDirectory, newScheduler(), guard);
            FolderWriter provisional = new FolderWriter();
            stateLock.lock();
            try {
                // A caller that observes this generation cannot queue a write ahead of its seeding;
                // a fast writer cannot report its facts before listeners can observe it running.
                started.writer = provisional;
                started.attached = true;
                provisional.attachedGenerations++;
                session = started;
                enqueueLocked(new SeedJob(started));
            } finally {
                stateLock.unlock();
            }
            ensureDrain(provisional);
            return new Generation(started);
        } finally {
            lifecycle.unlock();
        }
    }

    /**
     * Ends the current generation (rulings F9, G5; story 333): the next tick
     * is cancelled, a capture not yet taken is dropped, no job of the
     * generation claims a write or a delete after this call, and the futures
     * of its queued jobs complete exceptionally with an {@link IOException}
     * at once, on a thread of their own — never the caller's — even while a
     * write ahead of them is held on a stalled volume. It returns at once: it
     * never waits — for a writer, a claim or the owner executor — so the
     * JavaFX application thread never waits in it, and it never interrupts a
     * write.
     *
     * <p>The returned stage completes when the generation's claimed work —
     * a write's tail (its index, its atomic write and its prune) or its
     * seeding's writes and deletes — has ended: at once when none is under
     * way, otherwise on the writer that ends the last claim. It never
     * completes exceptionally. {@link ProjectManager} releases the project's
     * lock only once it has completed, so nothing of this session writes or
     * deletes in the folder after its lock is released. A claimed write that
     * finishes after this call prunes as usual and notifies no listener; from
     * five seconds after this call ({@link #STOP_WAIT}) a claim still held
     * decides no further delete, which a WARNING then says (ruling G5 (3)).
     * Attach dependants with an async method: a dependant added before the
     * stage completes may run on a checkpoint writer.</p>
     *
     * <p>Idempotent: with no generation running it returns the last ended
     * generation's stage, or a completed one before any start.</p>
     *
     * @return completes once the ended generation's claimed work has ended
     */
    public CompletableFuture<Void> stop() {
        lifecycle.lock();
        try {
            Session ending = session;
            if (ending == null) {
                return CompletableFuture.completedFuture(null);
            }
            if (ending.ended) {
                return ending.claimsEnded.copy();
            }
            boolean claimed;
            List<WriterJob> unclaimed;
            stateLock.lock();
            try {
                ending.ended = true;
                claimed = ending.claims > 0;
                unclaimed = queuedJobsOf(ending);
                detachIfDoneLocked(ending);
            } finally {
                stateLock.unlock();
            }
            ending.scheduler.shutdown();
            completeUnclaimed(ending, unclaimed);
            if (claimed) {
                LOG.info(() -> "The checkpoints of " + ending.projectDirectory + " end once the work the checkpoint"
                        + " writer claimed there has ended");
                boundTheClaims(ending);
            } else {
                ending.claimsEnded.complete(null);
            }
            return ending.claimsEnded.copy();
        } finally {
            lifecycle.unlock();
        }
    }

    /**
     * Replaces the auto-save configuration; the cap applies from the next
     * write. With a generation running (ruling H3): a configuration that
     * enables autosave re-arms the next tick a full interval of the new
     * configuration from now ({@link CheckpointEvent.RearmReason#RECONFIGURED});
     * one that disables it cancels the next tick and drops a capture not yet
     * handed to the writer, and keeps the generation, so user checkpoints and
     * safety copies are still written. It never {@linkplain #stop() stops}
     * the checkpoints, so it never waits for the writer.
     *
     * @param newConfig the new auto-save configuration
     */
    public void reconfigure(AutoSaveConfig newConfig) {
        Objects.requireNonNull(newConfig, "newConfig must not be null");
        lifecycle.lock();
        try {
            this.config = newConfig;
            Session running = session;
            if (running == null || running.ended) {
                return;
            }
            if (newConfig.enabled()) {
                rearm(running, CheckpointEvent.RearmReason.RECONFIGURED);
            } else {
                disarm(running);
            }
        } finally {
            lifecycle.unlock();
        }
    }

    /**
     * Returns whether the automatic checkpoints are running: a project's
     * checkpoints are started and autosave is enabled (ruling H3). With
     * autosave disabled a started project still takes user checkpoints and
     * safety copies.
     *
     * @return {@code true} if running
     */
    public boolean isRunning() {
        Session current = session;
        return current != null && !current.ended && config.enabled();
    }

    /**
     * Returns the identity of the generation running now — started and not
     * yet stopped, whether autosave is enabled or not — as its
     * {@link CheckpointEvent#generation() facts} carry it (story 331): a
     * consumer applies a fact only while this names the generation that
     * reported it.
     *
     * @return the running generation's identity, or empty when none runs
     */
    public OptionalLong runningGeneration() {
        Session current = session;
        return current == null || current.ended ? OptionalLong.empty() : OptionalLong.of(current.generation);
    }

    /**
     * Installs what scheduled checkpoints capture: {@code source}, called on
     * {@code owner} — the executor of the thread that owns the model (in the
     * application, the JavaFX application thread through its dispatcher's
     * un-keyed post). The owner runs only the capture; rendering and the disk
     * write happen on the checkpoint writer. Until a source is installed a
     * tick writes nothing.
     *
     * @param owner  runs each capture; it may queue the capture, but must not run it on the caller's stack while
     *               holding locks the model's thread needs
     * @param source the project to capture
     */
    public void setCaptureSource(Executor owner, CheckpointSource source) {
        this.captureSource = new CaptureSource(
                Objects.requireNonNull(owner, "owner must not be null"),
                Objects.requireNonNull(source, "source must not be null"));
    }

    /**
     * Writes a checkpoint holding {@code xml} — the document a manual save
     * just wrote to {@code project.daw}, the user's Create Checkpoint, a
     * failed save's safety copy, or the exit's closing checkpoint — through
     * the same ordered writer as the ticks (rulings F2, F3), as a job of the
     * generation running at the call. Unless {@code origin} is
     * {@link CheckpointOrigin#USER} or {@link CheckpointOrigin#CLOSING},
     * nothing is written when {@code revision}
     * is already checkpointed: the future then names the file that holds it.
     * A user checkpoint (ruling G8) and a closing checkpoint (story 333,
     * ruling R3) are always written. Any call invalidates
     * the first tick's match with the seeded checkpoint (ruling G3), and the
     * finished job re-arms the cadence a full interval later while autosave
     * is enabled.
     *
     * <p>The returned future always completes: with the file that now holds
     * {@code revision} — the new checkpoint, or the existing one — or
     * exceptionally with the original cause of the failure (the same
     * instance; {@link CompletableFuture#join()} wraps it in a
     * {@link java.util.concurrent.CompletionException}), which the listeners
     * also hear with {@code origin}. It completes exceptionally with an
     * {@link IOException}, and no listener hears of it, when no project's
     * checkpoints are started, when they are stopped before this one is
     * written, and for {@link CheckpointOrigin#AFTER_SAVE} when autosave is
     * disabled at the call (ruling J2: it is refused there, queues nothing
     * and re-arms nothing) or as it reaches the writer (ruling H3). Callers
     * must not complete it themselves.</p>
     *
     * @param xml      the complete checkpoint document
     * @param revision the edit revision {@code xml} was captured at
     * @param origin   why it is written; never {@link CheckpointOrigin#SCHEDULED}
     * @return the checkpoint's future, completed on the writer thread — or on a thread of {@link #stop()}'s when
     *         it is stopped first, or on the caller when no checkpoints are started or it is an
     *         {@link CheckpointOrigin#AFTER_SAVE} while autosave is disabled
     * @throws IllegalArgumentException if {@code origin} is {@link CheckpointOrigin#SCHEDULED}
     */
    public CompletableFuture<Path> writeCheckpoint(String xml, long revision, CheckpointOrigin origin) {
        return writeCheckpoint(xml, new Capture(revision, OptionalLong.empty()), origin);
    }

    /** Writes bytes with the journal boundary captured with those bytes, never with their later submission. */
    public CompletableFuture<Path> writeCheckpoint(String xml, Capture capture, CheckpointOrigin origin) {
        Objects.requireNonNull(capture, "capture must not be null");
        Objects.requireNonNull(xml, "xml must not be null");
        requireARequestOrigin(origin);
        Session current = session;
        if (current == null || current.ended) {
            CompletableFuture<Path> notRunning = new CompletableFuture<>();
            notRunning.completeExceptionally(new IOException(
                    "Checkpoints are not running: no project directory is open for checkpoints"));
            return notRunning;
        }
        return request(current, () -> xml, capture, origin);
    }

    /**
     * {@link #writeCheckpoint(String, long, CheckpointOrigin)}, bound to
     * {@code generation} instead of the generation current at the call
     * (story 331, ruling F9): {@link ProjectManager} requests a save's
     * checkpoint and a failed save's safety copy through it, against the
     * generation its project's open started. While that generation runs, the
     * request is written into its folder, as above. Once it has ended — the
     * project closed or replaced, or its folder opened again — the
     * request writes nothing, no listener hears of it, and its future
     * completes exceptionally with an {@link IOException} at once, on the
     * caller: a job's generation is checked where it is queued, under the
     * lock that ends a generation, so it never reaches another generation. A
     * request queued before the end is one of that generation's jobs
     * ({@link #stop()}).
     *
     * @param generation the generation the checkpoint belongs to
     * @param xml        the complete checkpoint document
     * @param revision   the edit revision {@code xml} was captured at
     * @param origin     why it is written; never {@link CheckpointOrigin#SCHEDULED}
     * @return the checkpoint's future, completed as above
     * @throws IllegalArgumentException if {@code origin} is {@link CheckpointOrigin#SCHEDULED}
     */
    CompletableFuture<Path> writeCheckpoint(Generation generation, String xml, long revision,
                                            CheckpointOrigin origin) {
        return writeCheckpoint(generation, xml, new Capture(revision, OptionalLong.empty()), origin);
    }

    CompletableFuture<Path> writeCheckpoint(Generation generation, String xml, Capture capture,
                                            CheckpointOrigin origin) {
        Objects.requireNonNull(xml, "xml must not be null");
        return writeCheckpoint(generation, () -> xml, capture, origin);
    }

    /**
     * {@link #writeCheckpoint(Generation, String, Capture, CheckpointOrigin)}
     * for a capture not yet rendered (story 333): {@code content} renders the
     * document on the writer of the generation's folder, never on the caller,
     * so the model's owner only captures — the exit's closing checkpoint
     * ({@link CheckpointOrigin#CLOSING}) is requested through it.
     *
     * @param generation the generation the checkpoint belongs to
     * @param content    renders the complete checkpoint document, once, on the writer
     * @param capture    the revision and journal boundary of the capture {@code content} renders
     * @param origin     why it is written; never {@link CheckpointOrigin#SCHEDULED}
     * @return the checkpoint's future, completed as above
     * @throws IllegalArgumentException if {@code origin} is {@link CheckpointOrigin#SCHEDULED}
     */
    CompletableFuture<Path> writeCheckpoint(Generation generation, CheckpointSource.Renderer content,
                                            Capture capture, CheckpointOrigin origin) {
        Objects.requireNonNull(generation, "generation must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(capture, "capture must not be null");
        requireARequestOrigin(origin);
        return request(generation.session, content, capture, origin);
    }

    private static void requireARequestOrigin(CheckpointOrigin origin) {
        Objects.requireNonNull(origin, "origin must not be null");
        if (origin == CheckpointOrigin.SCHEDULED) {
            throw new IllegalArgumentException("a scheduled checkpoint is the cadence's own; pass the origin of"
                    + " the request");
        }
    }

    /**
     * Queues a requested checkpoint as a job of {@code bound}; the job is
     * dropped, its future completed exceptionally, when {@code bound} has
     * ended ({@link #submit}).
     */
    private CompletableFuture<Path> request(Session bound, CheckpointSource.Renderer content, Capture capture,
                                            CheckpointOrigin origin) {
        CompletableFuture<Path> result = new CompletableFuture<>();
        bound.firstTick.set(SeedMatch.NONE);
        if (isAutomatic(origin) && !config.enabled()) {
            // Known at the call: it writes nothing and orders nothing, so it waits behind no job (ruling J2).
            result.completeExceptionally(autosaveDisabled(bound, origin));
            return result;
        }
        submit(new WriteJob(bound, origin, capture, content, result));
        return result;
    }

    /**
     * Adds a listener of the SDK checkpoint events: told just before each
     * checkpoint is written, and once of its success or failure. Listeners
     * are called on the checkpoint writer of the checkpoint's folder and must
     * not block; two folders' writers may call them at once (story 333).
     *
     * @param listener the listener to add
     */
    public void addListener(AutoSaveListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        listeners.add(listener);
    }

    /**
     * Removes a listener.
     *
     * @param listener the listener to remove
     */
    public void removeListener(AutoSaveListener listener) {
        listeners.remove(listener);
    }

    /**
     * Adds a listener of the {@link CheckpointEvent}s: what each generation's
     * seeding found, every checkpoint written or failed, with its
     * {@link CheckpointOrigin}, and every re-arm of the cadence with its next
     * due time (story 331, rulings G13 #2, #9, K1). It is called once per
     * fact, on the checkpoint writer of the fact's folder (seeding and
     * outcomes) or the scheduler thread (re-arms) — the facts of an ended
     * generation's folder and of the running one's may arrive at once, on two
     * writers (story 333) — and must not block; a UI listener marshals
     * onto its own thread. A re-arm is not reported once the scheduler sees
     * the checkpoints stopped or autosave disabled, but its delivery can
     * still trail a {@link #reconfigure} or {@link #stop()} made on another
     * thread: a consumer re-checks {@link #isRunning()} on its own thread when
     * it handles a {@link CheckpointEvent.Rearmed}, and shows no countdown
     * when it is {@code false}. Every fact names the generation that reported
     * it, which a consumer compares with {@link #runningGeneration()} on its
     * own thread: a fact of a generation that has ended describes a project
     * that is no longer open.
     *
     * @param listener the listener to add
     * @return a removal token; running it removes the listener
     */
    public Runnable addEventListener(Consumer<CheckpointEvent> listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        eventListeners.add(listener);
        return () -> removeEventListener(listener);
    }

    /**
     * Removes an event listener; one never added is ignored.
     *
     * @param listener the listener to remove
     */
    public void removeEventListener(Consumer<CheckpointEvent> listener) {
        eventListeners.remove(listener);
    }

    /**
     * Returns the highest checkpoint index used in the current (or last)
     * project's directory: seeded from the files there, then advanced by
     * each checkpoint written.
     *
     * @return the highest index, {@code 0} when there is none
     */
    public long getCheckpointCount() {
        Session current = session;
        return current == null ? 0L : current.view.highestIndex();
    }

    /**
     * Returns the checkpoint files of the current (or last) project's
     * directory the index holds — seeded and written, oldest first by
     * {@link CheckpointFileName#ORDER}: the stamps in their names, which a
     * clock or zone change can set apart from the order they were written in.
     *
     * @return an unmodifiable list
     */
    public List<Path> getCheckpointFiles() {
        Session current = session;
        return current == null ? List.of() : current.view.files();
    }

    /**
     * Returns the auto-save configuration.
     *
     * @return the config
     */
    public AutoSaveConfig getConfig() {
        return config;
    }

    /**
     * Sets a {@link BackupRetentionPolicy} to apply on every autosave in
     * addition to the simple {@code maxCheckpoints} cap. When non-{@code null}
     * the grandfather-father-son rotation from
     * {@link BackupRetentionService} is run on the checkpoint directory after
     * each successful checkpoint, so older snapshots are kept as
     * hourly / daily / weekly milestones.
     *
     * @param policy the policy to apply, or {@code null} to disable
     *               retention-based pruning (legacy {@code maxCheckpoints}
     *               behaviour only)
     */
    public void setRetentionPolicy(BackupRetentionPolicy policy) {
        this.retentionPolicy = policy;
    }

    /** Returns the active retention policy, or {@code null} if not set. */
    public BackupRetentionPolicy getRetentionPolicy() {
        return retentionPolicy;
    }

    // ── The cadence (scheduler thread) ──────────────────────────────────────

    private static ScheduledThreadPoolExecutor newScheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "daw-checkpoint-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    /**
     * Re-arms {@code armed}'s next tick from any thread; the arming runs on
     * its scheduler, in order. While autosave is disabled nothing is armed
     * or reported (ruling H3).
     */
    private void rearm(Session armed, CheckpointEvent.RearmReason reason) {
        if (!config.enabled()) {
            return;
        }
        try {
            armed.scheduler.execute(() -> {
                if (!armed.ended && config.enabled()) {
                    armAndReport(armed, reason);
                }
            });
        } catch (RejectedExecutionException stopped) {
            // The generation ended: there is no next tick to arm.
        }
    }

    /**
     * Cancels {@code armed}'s next tick, on its scheduler after every arming
     * posted before; autosave was just disabled, so nothing arms another
     * (ruling H3).
     */
    private static void disarm(Session armed) {
        try {
            armed.scheduler.execute(() -> cancelNextTick(armed));
        } catch (RejectedExecutionException stopped) {
            // The generation ended: its next tick was cancelled with it.
        }
    }

    /** Cancels the armed next tick, if any; scheduler thread only. */
    private static void cancelNextTick(Session armed) {
        ScheduledFuture<?> next = armed.nextTick;
        if (next != null) {
            next.cancel(false);
            armed.nextTick = null;
        }
    }

    /**
     * Arms the next tick and reports it; scheduler thread only. A
     * {@link #stop()} or a {@link #reconfigure} that disables autosave can
     * land while it arms, after the caller's check: then the tick just armed
     * is cancelled and nothing is reported, for that tick would never come.
     */
    private void armAndReport(Session armed, CheckpointEvent.RearmReason reason) {
        CheckpointEvent.Rearmed rearmed = armNextTick(armed, reason);
        if (armed.ended || !config.enabled()) {
            cancelNextTick(armed);
            return;
        }
        fire(armed, rearmed);
    }

    /** Arms the next tick and returns the re-arm to report; scheduler thread only. */
    private CheckpointEvent.Rearmed armNextTick(Session armed, CheckpointEvent.RearmReason reason) {
        Duration interval = config.autoSaveInterval();
        return new CheckpointEvent.Rearmed(armed.generation, scheduleNextTick(armed, interval), interval, reason);
    }

    /**
     * Schedules the next tick {@code interval} from now, replacing the one
     * armed before, and returns when it is due; scheduler thread only.
     */
    private Instant scheduleNextTick(Session armed, Duration interval) {
        ScheduledFuture<?> previous = armed.nextTick;
        if (previous != null) {
            previous.cancel(false);
        }
        Instant due = clock.instant().plus(interval);
        armed.nextTick = armed.scheduler.schedule(() -> tick(armed), nanosOf(interval), TimeUnit.NANOSECONDS);
        return due;
    }

    private static long nanosOf(Duration interval) {
        try {
            return interval.toNanos();
        } catch (ArithmeticException beyondALong) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * A scheduled tick: posts the capture to the owner and returns; it never
     * waits for the owner. It also arms the next tick a full interval later,
     * unreported: the job it starts re-arms from its own end, which replaces
     * that tick, so it fires only while the job is still held — a stalled
     * owner or writer — and is then dropped and reported
     * ({@link CheckpointEvent.RearmReason#TICK_DROPPED}). A tick that comes
     * after autosave was disabled does nothing and arms nothing (ruling H3).
     */
    private void tick(Session ticking) {
        try {
            if (ticking.ended || !config.enabled()) {
                return;
            }
            CaptureSource source = captureSource;
            if (source == null) {
                // Nothing to capture: no file, no listener (ruling F8); keep the cadence.
                scheduleNextTick(ticking, config.autoSaveInterval());
                return;
            }
            if (!ticking.tickInFlight.compareAndSet(false, true)) {
                armAndReport(ticking, CheckpointEvent.RearmReason.TICK_DROPPED);
                return;
            }
            scheduleNextTick(ticking, config.autoSaveInterval());
            try {
                source.owner().execute(() -> captureOnOwner(ticking, source.source()));
            } catch (Throwable rejected) {
                submit(new CaptureFailure(ticking, rejected));
            }
        } catch (RejectedExecutionException stopped) {
            if (!ticking.ended) {
                LOG.log(Level.SEVERE, stopped, () -> "A checkpoint tick of " + ticking.projectDirectory
                        + " could not arm the next tick");
            }
            // Otherwise stop() shut the scheduler down after this tick's check: there is no next tick to arm.
        } catch (Throwable unexpected) {
            LOG.log(Level.SEVERE, unexpected, () -> "A checkpoint tick of " + ticking.projectDirectory
                    + " failed before it could capture the project");
        }
    }

    /**
     * The owner's half of a tick: the revision check and the capture, then
     * the hand-off to the writer. A capture whose generation ended, or that
     * comes after autosave was disabled, is dropped untaken.
     */
    private void captureOnOwner(Session ticking, CheckpointSource source) {
        boolean handedToWriter = false;
        try {
            if (ticking.ended || !config.enabled()) {
                return;
            }
            if (!source.isAvailable()) {
                rearm(ticking, CheckpointEvent.RearmReason.SKIPPED);
                return;
            }
            long revision = source.revision();
            Checkpointed done = ticking.checkpointed;
            if (done != null && done.revision() == revision) {
                rearm(ticking, CheckpointEvent.RearmReason.SKIPPED);
                return;
            }
            SeedMatch seed = ticking.firstTick.getAndSet(SeedMatch.NONE);
            if (seed instanceof SeedMatch.Matched(Path newest) && source.isClean()) {
                submit(new SeedSkipJob(ticking, revision, newest));
                handedToWriter = true;
                return;
            }
            Capture capture = captureStarted(revision);
            CheckpointSource.Renderer renderer = Objects.requireNonNull(source.capture(),
                    "the capture returned no renderer");
            submit(new WriteJob(ticking, CheckpointOrigin.SCHEDULED, capture, renderer, new CompletableFuture<>()));
            handedToWriter = true;
        } catch (Throwable failure) {
            submit(new CaptureFailure(ticking, failure));
            handedToWriter = true;
        } finally {
            if (!handedToWriter) {
                ticking.tickInFlight.set(false);
            }
        }
    }

    // ── The writer ──────────────────────────────────────────────────────────

    /** Queues {@code job} on its generation's folder writer, or drops it when its generation has ended. */
    private void submit(WriterJob job) {
        Session owner = job.session();
        boolean accepted;
        FolderWriter writer = null;
        stateLock.lock();
        try {
            accepted = !owner.ended;
            if (accepted) {
                enqueueLocked(job);
                writer = owner.writer;
            }
        } finally {
            stateLock.unlock();
        }
        if (accepted) {
            ensureDrain(writer);
        } else {
            drop(job, stoppedBefore(owner));
        }
    }

    /**
     * Registers a held job in submission order, on the writer its generation
     * is attached to now. Caller holds {@link #stateLock}.
     */
    private void enqueueLocked(WriterJob job) {
        job.session().heldJobs++;
        heldJobsOfEveryGeneration++;
        job.session().writer.queue.add(job);
    }

    /** Starts {@code writer}'s virtual thread unless one is draining its queue. */
    private void ensureDrain(FolderWriter writer) {
        if (!writer.draining.compareAndSet(false, true)) {
            return;
        }
        try {
            writerExecutor.execute(() -> drain(writer));
        } catch (Throwable notStarted) {
            writer.draining.set(false);
            LOG.log(Level.SEVERE, notStarted, () -> "The checkpoint writer could not start; its queued jobs fail");
            WriterJob job;
            while ((job = writer.queue.poll()) != null) {
                drop(job, notStarted);
                released(job.session());
            }
        }
    }

    private void drain(FolderWriter writer) {
        while (true) {
            WriterJob job;
            while ((job = writer.queue.poll()) != null) {
                runJob(job);
            }
            writer.draining.set(false);
            if (writer.queue.isEmpty() || !writer.draining.compareAndSet(false, true)) {
                return;
            }
        }
    }

    private void runJob(WriterJob job) {
        try {
            if (job.session().ended) {
                drop(job, stoppedBefore(job.session()));
                return;
            }
            switch (job) {
                case SeedJob seed -> seed(seed);
                case WriteJob write -> write(write);
                case SeedSkipJob skip -> skipIfTheSeedStillMatches(skip);
                case CaptureFailure failure -> reportCaptureFailure(failure);
            }
        } catch (Throwable unexpected) {
            LOG.log(Level.SEVERE, unexpected, () -> "A checkpoint job of " + job.session().projectDirectory
                    + " failed unexpectedly");
            drop(job, unexpected);
        } finally {
            released(job.session());
        }
    }

    /** Completes a job that will not run (or ran no further), without telling any listener. */
    private static void drop(WriterJob job, Throwable cause) {
        switch (job) {
            case SeedJob seed -> {
                seed.session().firstTick.compareAndSet(SeedMatch.UNSEEDED, SeedMatch.NONE);
                seed.session().seeded.completeExceptionally(cause);
            }
            case WriteJob write -> {
                write.result().completeExceptionally(cause);
                if (write.origin() == CheckpointOrigin.SCHEDULED) {
                    write.session().tickInFlight.set(false);
                }
            }
            case SeedSkipJob skip -> skip.session().tickInFlight.set(false);
            case CaptureFailure failure -> {
                LOG.log(Level.FINE, failure.cause(), () -> "A capture failure of the stopped checkpoints of "
                        + failure.session().projectDirectory + " is reported to no listener");
                failure.session().tickInFlight.set(false);
            }
        }
    }

    private void released(Session owner) {
        List<CompletableFuture<Void>> idle = List.of();
        stateLock.lock();
        try {
            owner.heldJobs--;
            heldJobsOfEveryGeneration--;
            detachIfDoneLocked(owner);
            writerReleased.signalAll();
            if (owner.heldJobs == 0 && !owner.idleWaiters.isEmpty()) {
                idle = new ArrayList<>(owner.idleWaiters);
                owner.idleWaiters.clear();
            }
            if (heldJobsOfEveryGeneration == 0 && !noJobWaiters.isEmpty()) {
                List<CompletableFuture<Void>> everyWriterIdle = new ArrayList<>(idle);
                everyWriterIdle.addAll(noJobWaiters);
                noJobWaiters.clear();
                idle = everyWriterIdle;
            }
        } finally {
            stateLock.unlock();
        }
        for (CompletableFuture<Void> waiter : idle) {
            waiter.complete(null);
        }
    }

    /**
     * Detaches {@code owner} from its folder writer once it has ended and its
     * writer holds none of its jobs, and unregisters that writer once no
     * generation is attached to it any more — it then holds no job at all,
     * for every job it holds belongs to a generation attached to it, so no
     * job is stranded and a later generation of that folder starts a writer
     * of its own. Caller holds {@link #stateLock}.
     */
    private void detachIfDoneLocked(Session owner) {
        if (!owner.ended || owner.heldJobs > 0 || !owner.attached) {
            return;
        }
        owner.attached = false;
        FolderWriter writer = owner.writer;
        writer.attachedGenerations--;
        if (writer.attachedGenerations == 0) {
            writers.remove(writer);
        }
    }

    /**
     * The jobs of {@code ended} still queued; under {@link #stateLock}, just
     * after it ended. None of them can claim: a job a writer takes now is
     * dropped, and a claim checks the end under the same lock.
     */
    private static List<WriterJob> queuedJobsOf(Session ended) {
        List<WriterJob> queued = new ArrayList<>();
        for (WriterJob job : ended.writer.queue) {
            if (job.session() == ended) {
                queued.add(job);
            }
        }
        return queued;
    }

    /**
     * Completes the futures of {@code ended}'s queued jobs (ruling G5 (2)),
     * on a virtual thread of its own: their dependants never run on the
     * caller of {@link #stop()} — the JavaFX application thread in the
     * application — and never wait behind a write held on a stalled volume.
     * No listener hears of them. The writer still takes each job off the
     * queue, drops it — completing it again changes nothing — and releases
     * it, so the count of held jobs and the in-flight flag stay the writer's.
     */
    private static void completeUnclaimed(Session ended, List<WriterJob> unclaimed) {
        if (unclaimed.isEmpty()) {
            return;
        }
        try {
            STOP_THREADS.execute(() -> {
                for (WriterJob job : unclaimed) {
                    switch (job) {
                        case SeedJob seed -> {
                            seed.session().firstTick.compareAndSet(SeedMatch.UNSEEDED, SeedMatch.NONE);
                            seed.session().seeded.completeExceptionally(stoppedBefore(ended));
                        }
                        case WriteJob write -> write.result().completeExceptionally(stoppedBefore(ended));
                        case SeedSkipJob _, CaptureFailure _ -> {
                            // Nothing waits on them.
                        }
                    }
                }
            });
        } catch (Throwable notStarted) {
            LOG.log(Level.WARNING, notStarted, () -> "The queued checkpoint jobs of " + ended.projectDirectory
                    + " complete only when the writer reaches them");
        }
    }

    private static IOException stoppedBefore(Session stopped) {
        IOException notWritten = new IOException("The checkpoints of " + stopped.projectDirectory
                + " were stopped before this checkpoint was written");
        LOG.log(Level.FINE, () -> notWritten.getMessage());
        return notWritten;
    }

    /**
     * Arms {@link #stop()}'s bound for {@code ending}, whose claimed work was
     * still under way when it ended: {@link #stopWait} later, on a thread of
     * its own, a claim still held decides no further delete.
     */
    private void boundTheClaims(Session ending) {
        try {
            CompletableFuture.delayedExecutor(nanosOf(stopWait), TimeUnit.NANOSECONDS, STOP_THREADS)
                    .execute(() -> claimsOutlivedTheBound(ending));
        } catch (Throwable notArmed) {
            LOG.log(Level.WARNING, notArmed, () -> "The bound of the claimed checkpoint work in "
                    + ending.projectDirectory + " could not be armed; it prunes nothing more");
            claimsOutlivedTheBound(ending);
        }
    }

    /**
     * {@link #stop()}'s bound passed: if {@code ending} still holds a claim,
     * no further delete of it is decided — under the lock each delete asks
     * first (ruling G5 (3)) — and a WARNING says so. The claim itself is never
     * cut short: the lock stays held until it ends.
     */
    private void claimsOutlivedTheBound(Session ending) {
        boolean stillClaimed;
        Path writing;
        stateLock.lock();
        try {
            stillClaimed = ending.claims > 0;
            if (stillClaimed) {
                ending.mayPrune = false;
            }
            writing = ending.publishing;
        } finally {
            stateLock.unlock();
        }
        if (stillClaimed) {
            LOG.warning(() -> "The checkpoint writer did not finish with " + ending.projectDirectory
                    + " within " + stopWait.toMillis() + " ms"
                    + (writing == null ? "" : "; " + writing + " may still be published there")
                    + "; it notifies no listener and prunes nothing more");
        }
    }

    /**
     * Claims work of {@code owner} in its folder — a write's tail or its
     * seeding's writes and deletes (story 333) — unless it has ended: the
     * only way a job may write or delete there. Under {@link #stateLock},
     * where {@link #stop()} ends it.
     *
     * @return whether the claim was taken; {@code false} once the generation ended
     */
    private boolean claim(Session owner) {
        stateLock.lock();
        try {
            if (owner.ended) {
                return false;
            }
            owner.claims++;
            return true;
        } finally {
            stateLock.unlock();
        }
    }

    /** Ends a claim of {@code owner}; the last claim of an ended generation completes {@link #stop()}'s stage. */
    private void releaseClaim(Session owner) {
        boolean lastOfAnEndedGeneration;
        stateLock.lock();
        try {
            owner.claims--;
            lastOfAnEndedGeneration = owner.ended && owner.claims == 0;
        } finally {
            stateLock.unlock();
        }
        if (lastOfAnEndedGeneration) {
            owner.claimsEnded.complete(null);
        }
    }

    /**
     * Whether a job of {@code pruning} may delete a checkpoint now: always
     * within its claim, until {@link #stop()}'s bound passed with the claim
     * still held (ruling G5 (3)). Asked under {@link #stateLock} before each
     * delete, where the bound decides it, so no delete is decided after the
     * bound; one decided just before it is still issued within the claim, and
     * the project's lock is released only after the claim has ended.
     */
    private boolean mayStillPrune(Session pruning) {
        stateLock.lock();
        try {
            return pruning.mayPrune;
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * The test seam of an ordering a test must pin on the writer: waits, at
     * most {@code bound}, until the writer holds no job of the current
     * generation — every job handed to it has returned, its re-arm already
     * decided — and returns whether it did. It queues nothing and arms
     * nothing.
     */
    boolean awaitWriterIdle(Duration bound) throws InterruptedException {
        Session current = session;
        if (current == null) {
            return true;
        }
        long deadline = System.nanoTime() + bound.toNanos();
        stateLock.lock();
        try {
            while (current.heldJobs > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    return false;
                }
                writerReleased.awaitNanos(remaining);
            }
            return true;
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Whether the writers hold a job of any generation, of any folder, queued
     * or running (story 331, ruling M1): what
     * {@link ProjectManager#awaitWritesBeforeExit()} asks as the application
     * exits.
     *
     * @return {@code true} while they hold one
     */
    boolean holdsAJob() {
        stateLock.lock();
        try {
            return heldJobsOfEveryGeneration > 0;
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Waits until the writers hold no job of any generation, of any folder —
     * every job of any origin handed to them, queued or running, has
     * returned — or until
     * {@code deadline}, a {@link System#nanoTime()} value (story 331, ruling
     * M1: the second half of the wait at exit). It queues, cancels,
     * completes and interrupts nothing; a job handed over while it waits is
     * waited for too, within the same deadline.
     *
     * @param deadline when to stop waiting, as {@link System#nanoTime()} reads it
     * @return whether the writers held no job before the deadline
     * @throws InterruptedException if the waiting thread is interrupted
     */
    boolean awaitNoJobUntil(long deadline) throws InterruptedException {
        stateLock.lock();
        try {
            while (heldJobsOfEveryGeneration > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    return false;
                }
                writerReleased.awaitNanos(remaining);
            }
            return true;
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Completes once the writers hold no job of any generation, of any
     * folder, queued or running — {@link #awaitNoJobUntil(long)}'s condition,
     * without a thread waiting for it (story 333: ruling M1's second half for
     * the exit protocol). It is completed at once when they hold none, and
     * otherwise on the writer that returns the last job; attach dependants
     * with an async method. A job handed over before it completes is waited
     * for too. It never completes exceptionally, and it cancels nothing.
     *
     * @return completes when no checkpoint job of any generation is held
     */
    public CompletableFuture<Void> whenNoJobIsHeld() {
        stateLock.lock();
        try {
            if (heldJobsOfEveryGeneration == 0) {
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<Void> idle = new CompletableFuture<>();
            noJobWaiters.add(idle);
            return idle;
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Completes once {@code generation} holds no job, queued or running —
     * its seeding, its writes and its skips (story 333: the exit's closing
     * checkpoint is captured only once the open generation is idle). It is
     * completed at once when the generation holds none, and otherwise on the
     * writer that returns its last job; attach dependants with an async
     * method. It never completes exceptionally, and it cancels nothing.
     *
     * @param generation the generation to wait for
     * @return completes when {@code generation} holds no job
     */
    CompletableFuture<Void> whenGenerationIdle(Generation generation) {
        Session waited = Objects.requireNonNull(generation, "generation must not be null").session;
        stateLock.lock();
        try {
            if (waited.heldJobs == 0) {
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<Void> idle = new CompletableFuture<>();
            waited.idleWaiters.add(idle);
            return idle;
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Whether {@code revision} is the revision {@code generation} last
     * checkpointed — written, or matched by its first tick against the
     * seeded checkpoint (ruling G3) — as its writer recorded it (story 333:
     * the exit's closing-checkpoint trigger (ii)). No disk access.
     *
     * @param generation the generation asked
     * @param revision   an edit revision
     * @return whether that revision is the generation's last checkpointed one
     */
    boolean isCheckpointed(Generation generation, long revision) {
        Checkpointed done = Objects.requireNonNull(generation, "generation must not be null").session.checkpointed;
        return done != null && done.revision() == revision;
    }

    // ── Writer jobs ─────────────────────────────────────────────────────────

    /**
     * Seeds the index from the directory, records the first tick's match
     * (ruling G3), deletes stray temps, and reports what it found
     * ({@link CheckpointEvent.Seeded}, ruling K1: the newest seeded checkpoint,
     * or none when the folder held none or could not be read) before the
     * seeded future completes and before any write of the generation.
     *
     * <p>Its first step, on the writer the generation started with, reads
     * the folder's {@linkplain FolderFingerprint fingerprint} — on that
     * folder's own path only — and joins the writer of that folder when one
     * still runs jobs there (story 333): the seeding is then queued there,
     * behind those jobs and ahead of every other job of its generation, and
     * runs there. Its compaction of the ordering fact and its deletes of
     * stray temps are one claim, taken only while the generation runs and
     * ended before it reports.</p>
     */
    private void seed(SeedJob job) {
        Session seeding = job.session();
        if (!seeding.fingerprinted) {
            seeding.fingerprinted = true;
            if (joinedTheWriterOfItsFolder(job, fingerprintOf(seeding.projectDirectory))) {
                return;
            }
        }
        boolean claimed = false;
        try {
            List<Path> strayTemps = new ArrayList<>();
            try {
                seeding.certified.addAll(CheckpointOrder.read(seeding.checkpointDirectory));
            } catch (IOException invalidFact) {
                LOG.log(Level.WARNING, "Checkpoint ordering fact is unavailable; using legacy stamp order", invalidFact);
            }
            if (!Files.notExists(seeding.checkpointDirectory)) {
                if (!Files.readAttributes(seeding.checkpointDirectory, java.nio.file.attribute.BasicFileAttributes.class).isDirectory()) {
                    throw new IOException("Checkpoints path is not a directory: " + seeding.checkpointDirectory);
                }
                try (DirectoryStream<Path> entries = directoryReader.open(seeding.checkpointDirectory)) {
                    for (Path entry : entries) {
                        String name = entry.getFileName().toString();
                        Optional<CheckpointFileName> checkpoint = CheckpointFileName.parse(name);
                        if (checkpoint.isPresent()) {
                            seeding.index.put(checkpoint.get(), entry);
                            seeding.highestIndex = Math.max(seeding.highestIndex, checkpoint.get().index());
                        } else if (isStrayCheckpointTemp(entry, name)) {
                            strayTemps.add(entry);
                        }
                    }
                }
            }
            seeding.certificationEligible = true;
            Set<String> present = new HashSet<>();
            seeding.index.keySet().forEach(name -> present.add(name.fileName()));
            seeding.certified.stream().filter(name -> !present.contains(name))
                    .forEach(seeding.deletedCertifications::add);
            // The seeding's writes and deletes are one claim, taken only while the generation runs and only when
            // there is one to make: an ordering fact naming a checkpoint no longer there, or a stray temp.
            claimed = (!seeding.deletedCertifications.isEmpty() || !strayTemps.isEmpty()) && claim(seeding);
            if (claimed) {
                compactCertifications(seeding);
            }
            seeding.publishView();
            seeding.firstTick.compareAndSet(SeedMatch.UNSEEDED, seedMatchOf(seeding));
            if (claimed) {
                deleteStrayTemps(seeding, strayTemps);
                claimed = false;
                releaseClaim(seeding);
            }
            Optional<Path> newest = seeding.index.isEmpty()
                    ? Optional.empty()
                    : Optional.of(seeding.index.lastEntry().getValue());
            fire(seeding, new CheckpointEvent.Seeded(seeding.generation, newest));
            seeding.seeded.complete(null);
        } catch (Throwable unreadable) {
            if (claimed) {
                claimed = false;
                releaseClaim(seeding);
            }
            LOG.log(Level.WARNING, unreadable, () -> "Could not read the checkpoints of " + seeding.projectDirectory
                    + "; checkpoints are written from index " + (seeding.highestIndex + 1));
            seeding.firstTick.compareAndSet(SeedMatch.UNSEEDED, SeedMatch.NONE);
            fire(seeding, new CheckpointEvent.Seeded(seeding.generation, Optional.empty()));
            seeding.seeded.completeExceptionally(unreadable);
        } finally {
            if (claimed) {
                releaseClaim(seeding);
            }
            seeding.seededNames = Set.copyOf(seeding.index.keySet());
            rearm(seeding, CheckpointEvent.RearmReason.STARTED);
        }
    }

    /**
     * The seeding's first step (story 333): with {@code fingerprint} read on
     * its folder's own path, either joins the registered writer of the same
     * folder — under {@link #stateLock}, the seeding queued there first, then
     * every other job of its generation in order, so its jobs stay behind its
     * seeding and the folder's earlier jobs, and the hold this run has on the
     * seeding is released by its caller as usual — and returns {@code true};
     * or registers the generation's own writer as that folder's and returns
     * {@code false}, and the seeding goes on here. A generation that has
     * already ended joins nothing: its jobs claim nothing.
     */
    private boolean joinedTheWriterOfItsFolder(SeedJob job, FolderFingerprint fingerprint) {
        Session seeding = job.session();
        FolderWriter existing = null;
        stateLock.lock();
        try {
            FolderWriter own = seeding.writer;
            if (seeding.ended) {
                return false;
            }
            for (FolderWriter writer : writers) {
                if (writer != own && writer.fingerprint.sameFolder(fingerprint)) {
                    existing = writer;
                    break;
                }
            }
            if (existing == null) {
                own.fingerprint = fingerprint;
                writers.add(own);
                return false;
            }
            own.attachedGenerations--;
            existing.attachedGenerations++;
            seeding.writer = existing;
            enqueueLocked(job);
            WriterJob moved;
            while ((moved = own.queue.poll()) != null) {
                existing.queue.add(moved);
            }
        } finally {
            stateLock.unlock();
        }
        LOG.fine(() -> "The checkpoints of " + seeding.projectDirectory + " are written behind the jobs still held in"
                + " that folder");
        ensureDrain(existing);
        return true;
    }

    /**
     * The fingerprint of {@code projectDirectory}, read on its own path; one
     * that cannot be read at all — the read threw — is its absolute,
     * normalised path with no identity, logged at {@code FINE}, so the
     * seeding still joins or registers a writer and arms its ticks.
     */
    private FolderFingerprint fingerprintOf(Path projectDirectory) {
        try {
            return folderFingerprints.apply(projectDirectory);
        } catch (Throwable unreadable) {
            LOG.log(Level.FINE, unreadable, () -> "The fingerprint of " + projectDirectory + " could not be read;"
                    + " its checkpoints are told apart by its path alone");
            return new FolderFingerprint(Optional.empty(), pathAlone(projectDirectory));
        }
    }

    /**
     * {@code projectDirectory}'s absolute, normalised path — or, when even that
     * cannot be read (an {@link Error} from {@link Path#toAbsolutePath()}, the
     * very read that failed), its normalised path as given.
     */
    private static String pathAlone(Path projectDirectory) {
        try {
            return projectDirectory.toAbsolutePath().normalize().toString();
        } catch (Throwable unreadable) {
            return projectDirectory.normalize().toString();
        }
    }

    /** Exactly the {@link AtomicFileWriter}'s temp of a checkpoint name, as a regular file (ruling G6). */
    private static boolean isStrayCheckpointTemp(Path entry, String name) {
        return AtomicFileWriter.publishedNameOf(name).flatMap(CheckpointFileName::parse).isPresent()
                && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS);
    }

    /** Whether {@code project.daw} is byte-identical to the newest seeded checkpoint. */
    private static SeedMatch seedMatchOf(Session seeded) {
        if (seeded.index.isEmpty()) {
            return SeedMatch.NONE;
        }
        Path newest = seeded.index.lastEntry().getValue();
        Path head = seeded.projectDirectory.resolve(ProjectManager.PROJECT_FILE_NAME);
        return isIdentical(head, newest) ? new SeedMatch.Matched(newest) : SeedMatch.NONE;
    }

    private static boolean isIdentical(Path head, Path checkpoint) {
        try {
            return Files.isRegularFile(head) && Files.mismatch(head, checkpoint) == -1L;
        } catch (IOException | RuntimeException unreadable) {
            LOG.log(Level.FINE, unreadable, () -> head + " could not be compared with " + checkpoint);
            return false;
        }
    }

    private void deleteStrayTemps(Session seeded, List<Path> strayTemps) {
        if (strayTemps.isEmpty()) {
            return;
        }
        try {
            seeded.guard.check();
        } catch (Throwable notOurs) {
            LOG.log(Level.INFO, notOurs, () -> "Left " + strayTemps.size() + " stray checkpoint temp file(s) in "
                    + seeded.checkpointDirectory + ": this session may not write the project folder");
            return;
        }
        int deleted = 0;
        for (Path strayTemp : strayTemps) {
            if (seeded.ended) {
                break;
            }
            try {
                deleter.delete(strayTemp);
                deleted++;
            } catch (NoSuchFileException gone) {
                // Already gone.
            } catch (IOException | RuntimeException notDeleted) {
                LOG.log(Level.FINE, notDeleted, () -> "Could not delete the stray checkpoint temp " + strayTemp);
            }
        }
        int count = deleted;
        LOG.info(() -> "Deleted " + count + " stray checkpoint temp file(s) in " + seeded.checkpointDirectory);
    }

    /**
     * One checkpoint write, in the class note's order ("One write"). The
     * write claims its folder after its render (story 333) and holds the
     * claim through its index, its atomic write and its prune; the claim ends
     * before its future completes, so whoever observes the outcome also
     * observes the claim ended. A disabled automatic origin and an already
     * checkpointed revision return before claiming.
     */
    private void write(WriteJob job) {
        Session target = job.session();
        boolean claimed = false;
        try {
            if (isAutomatic(job.origin()) && !config.enabled()) {
                job.result().completeExceptionally(autosaveDisabled(target, job.origin()));
                return;
            }
            Checkpointed done = target.checkpointed;
            if (!alwaysWritten(job.origin()) && done != null && done.revision() == job.revision()) {
                job.result().complete(done.file());
                rearm(target, CheckpointEvent.RearmReason.SKIPPED);
                return;
            }
            String id = nextCheckpointId();
            notifyBefore(target, id);
            Published published;
            try {
                target.guard.check();
                String content = Objects.requireNonNull(job.content().render(),
                        "the checkpoint's renderer returned no content");
                claimed = claim(target);
                if (!claimed) {
                    throw new GenerationEnded(target);
                }
                published = publish(target, content);
            } catch (GenerationEnded stopped) {
                LOG.log(Level.INFO, () -> "Checkpoint " + id + " was not written: " + stopped.getMessage());
                job.result().completeExceptionally(stopped);
                return;
            } catch (Throwable failure) {
                LOG.log(Level.SEVERE, failure, () -> "Checkpoint " + id + " (" + job.origin() + ") of "
                        + target.projectDirectory + " failed; nothing was written");
                notifyFailed(target, id, job.origin(), failure);
                if (claimed) {
                    claimed = false;
                    releaseClaim(target);
                }
                job.result().completeExceptionally(failure);
                rearm(target, CheckpointEvent.RearmReason.FAILED);
                return;
            }
            recordWritten(target, id, job, published);
            claimed = false;
            releaseClaim(target);
            job.result().complete(published.file());
            rearm(target, CheckpointEvent.RearmReason.WRITTEN);
        } finally {
            if (claimed) {
                releaseClaim(target);
            }
            if (job.origin() == CheckpointOrigin.SCHEDULED) {
                target.tickInFlight.set(false);
            }
        }
    }

    /**
     * Everything after a checkpoint is on disk, within its claim: the
     * revision it holds, the index, the listeners, the prune. Nothing here
     * turns the written checkpoint into a failure or a drop: an index that
     * cannot take it is logged, and the write still reports its one outcome.
     */
    private void recordWritten(Session target, String id, WriteJob job, Published published) {
        Path file = published.file();
        Checkpointed previous = target.checkpointed;
        if (previous == null || previous.capture().journalSequence().isEmpty()
                || (job.capture().journalSequence().isPresent()
                    && job.capture().journalSequence().getAsLong() >= previous.capture().journalSequence().getAsLong())) {
            target.checkpointed = new Checkpointed(job.capture(), file);
        }
        try {
            target.index.put(published.name(), file);
            target.publishView();
        } catch (Throwable unindexed) {
            LOG.log(Level.WARNING, unindexed, () -> "Checkpoint " + id + " was written to " + file
                    + " but could not join the index of " + target.projectDirectory
                    + "; the next start counts it");
        }
        notifyWritten(target, id, job.origin(), file, job.capture());
        prune(target, file);
    }

    /** The automatic checkpoints, which autosave being disabled turns off (ruling H3). */
    private static boolean isAutomatic(CheckpointOrigin origin) {
        return switch (origin) {
            case SCHEDULED, AFTER_SAVE -> true;
            case USER, SAFETY_COPY, CLOSING -> false;
        };
    }

    /**
     * The checkpoints written even when their revision is already
     * checkpointed: the user's (ruling G8) and the exit's closing checkpoint
     * (story 333, ruling R3).
     */
    private static boolean alwaysWritten(CheckpointOrigin origin) {
        return switch (origin) {
            case USER, CLOSING -> true;
            case SCHEDULED, AFTER_SAVE, SAFETY_COPY -> false;
        };
    }

    private static IOException autosaveDisabled(Session target, CheckpointOrigin origin) {
        IOException notWritten = new IOException("Autosave is disabled: no " + origin + " checkpoint of "
                + target.projectDirectory + " is written");
        LOG.log(Level.FINE, () -> notWritten.getMessage());
        return notWritten;
    }

    /**
     * Within the write's claim — taken under {@link #stateLock}, the fence
     * {@link #stop()} ends a generation under — allocates the next index and
     * writes the new checkpoint through the atomic writer. The name it
     * returns is the one it allocated, never the published file's name read
     * back.
     */
    private Published publish(Session target, String content) throws IOException {
        long index;
        Published published;
        stateLock.lock();
        try {
            index = Math.addExact(target.highestIndex, 1L);
            CheckpointFileName name = CheckpointFileName.of(index, clock.instant(), ZoneId.systemDefault());
            published = new Published(name, target.checkpointDirectory.resolve(name.fileName()));
            target.publishing = published.file();
        } finally {
            stateLock.unlock();
        }
        try {
            Files.createDirectories(target.checkpointDirectory);
            Set<String> covered = new HashSet<>(target.certified);
            covered.removeAll(target.deletedCertifications);
            if (target.certificationEligible) covered.add(published.name().fileName());
            else covered.remove(published.name().fileName());
            recordCertifications(target, covered);
            fileWriter.writeString(published.file(), content);
        } catch (IOException | RuntimeException | Error failure) {
            if (Files.notExists(published.file(), LinkOption.NOFOLLOW_LINKS)) {
                target.deletedCertifications.add(published.name().fileName());
            }
            throw failure;
        } finally {
            target.publishing = null;
        }
        target.highestIndex = index;
        return published;
    }

    private void recordCertifications(Session target, Set<String> covered) throws IOException {
        if (!covered.equals(target.certified)) {
            CheckpointOrder.record(target.checkpointDirectory, covered, fileWriter);
            List<Map.Entry<CheckpointFileName, Path>> existing = new ArrayList<>(target.index.entrySet());
            target.index.clear();
            target.certified.clear();
            target.certified.addAll(covered);
            existing.forEach(entry -> target.index.put(entry.getKey(), entry.getValue()));
        }
        target.deletedCertifications.clear();
    }

    private void compactCertifications(Session target) {
        if (target.deletedCertifications.isEmpty()) return;
        Set<String> covered = new HashSet<>(target.certified);
        covered.removeAll(target.deletedCertifications);
        if (covered.equals(target.certified)) {
            target.deletedCertifications.clear();
            return;
        }
        try {
            target.guard.check();
            recordCertifications(target, covered);
        } catch (IOException failure) {
            LOG.log(Level.WARNING, "Could not compact checkpoint ordering metadata; the next write retries", failure);
        }
    }

    /**
     * Deletes checkpoints until the cap holds (rulings F5, H4, J1): the
     * seeded ones first, oldest first by {@link CheckpointFileName#ORDER};
     * then this generation's own writes, oldest written first.
     * {@code written} — the checkpoint just written — and the file holding
     * the greatest captured journal boundary are never deleted. An older
     * capture completing late may therefore retain one extra checkpoint
     * until a newer capture supersedes both protected files. A file whose delete
     * throws — any {@link Throwable}, an {@link Error} too — is skipped and
     * marked refused, and the pass goes on: a refused file stays in the index
     * and on disk, and the cap counts the index less its refused files. Each
     * pass retries at most one file refused in an earlier pass — the one
     * tried least recently, at its place in the order — and never one it
     * refused itself; a pass that starts at or below the cap, or stops before
     * the retry's place, retries none, and none waits. A file already gone
     * ({@link NoSuchFileException}) counts as deleted. A failure outside the
     * delete of one file ends the pass with a warning, retried after the next
     * write; either way the checkpoint just written stays a success (ruling
     * G13 #1). The prune runs within the write's claim (story 333): a write
     * whose generation {@link #stop()} ended while it was claimed prunes as
     * usual, and the project's lock is released only after the claim ended;
     * once {@code stop()}'s bound has passed with the claim still held, no
     * delete of the job is decided any more (ruling G5 (3)), and one decided
     * just before is still issued within the claim.
     */
    private void prune(Session pruning, Path written) {
        try {
            int cap = config.maxCheckpoints();
            Set<Path> spared = new HashSet<>();
            spared.add(written);
            Checkpointed done = pruning.checkpointed;
            if (done != null) {
                spared.add(done.file());
            }
            pruning.refusedDeletes.removeIf(refused -> !pruning.index.containsKey(refused));
            Optional<CheckpointFileName> retry = pruning.refusedDeletes.isEmpty()
                    ? Optional.empty()
                    : Optional.of(pruning.refusedDeletes.getFirst());
            Set<CheckpointFileName> attempted = new HashSet<>();
            for (Map.Entry<CheckpointFileName, Path> candidate : pruneOrder(pruning)) {
                if (pruning.index.size() - pruning.refusedDeletes.size() <= cap || !mayStillPrune(pruning)) {
                    break;
                }
                CheckpointFileName name = candidate.getKey();
                boolean retried = pruning.refusedDeletes.contains(name);
                // A file refused in an earlier pass is tried only as this pass's one retry.
                if (spared.contains(candidate.getValue()) || (retried && !retry.equals(Optional.of(name)))) {
                    continue;
                }
                attempted.add(name);
                if (deleteOrRefuse(pruning, candidate.getValue(), name, cap, retried)) {
                    pruning.index.remove(name);
                    pruning.refusedDeletes.remove(name);
                    pruning.deletedCertifications.add(name.fileName());
                }
            }
            applyRetentionPolicy(pruning, spared, attempted);
            if (mayStillPrune(pruning)) compactCertifications(pruning);
        } catch (Throwable failure) {
            LOG.log(Level.WARNING, failure, () -> "Pruning the checkpoints of " + pruning.projectDirectory
                    + " failed; it is retried after the next checkpoint");
        } finally {
            pruning.publishView();
        }
    }

    /**
     * Deletes one checkpoint the prune chose and returns whether it is gone
     * ({@link NoSuchFileException} counts as gone). A delete that throws
     * anything else — any {@link Throwable}, an {@link Error} too (ruling J1:
     * a delete that throws skips that file) — marks it refused, now the most
     * recently tried, and logs it: the generation's first refusal at
     * {@code WARNING}, every later one at {@code FINE}.
     */
    private boolean deleteOrRefuse(Session pruning, Path file, CheckpointFileName name, int cap, boolean retried) {
        try {
            deleter.delete(file);
        } catch (NoSuchFileException gone) {
            // Already gone: nothing left to count.
        } catch (Throwable refused) {
            pruning.refusedDeletes.addLast(name);
            if (pruning.refusalReported) {
                LOG.log(Level.FINE, refused, () -> "Could not delete the checkpoint " + file
                        + (retried ? " again: " : ": ") + refused + "; it is retried after later checkpoints");
            } else {
                pruning.refusalReported = true;
                LOG.log(Level.WARNING, refused, () -> "Could not delete the checkpoint " + file + ": " + refused
                        + ". checkpoints/ holds more than " + cap + " checkpoints until it can be deleted; it is"
                        + " retried after later checkpoints, and further refusals this session are logged at FINE");
            }
            return false;
        }
        if (retried) {
            LOG.fine(() -> "The checkpoint " + file + ", which refused deletion before, is gone");
        }
        return true;
    }

    /**
     * The index in the order the prune deletes it (ruling H4): the seeded
     * files by {@link CheckpointFileName#ORDER} — the stamp order that is
     * right for folders whose indices restarted at every launch before story
     * 331 — then this generation's own writes by ascending index, which is
     * the order they were written in. The display order is not this one.
     */
    private static List<Map.Entry<CheckpointFileName, Path>> pruneOrder(Session pruning) {
        List<Map.Entry<CheckpointFileName, Path>> order = new ArrayList<>();
        List<Map.Entry<CheckpointFileName, Path>> written = new ArrayList<>();
        for (Map.Entry<CheckpointFileName, Path> entry : pruning.index.entrySet()) {
            Map.Entry<CheckpointFileName, Path> candidate = Map.entry(entry.getKey(), entry.getValue());
            if (pruning.seededNames.contains(entry.getKey())) {
                order.add(candidate);
            } else {
                written.add(candidate);
            }
        }
        written.sort(Comparator.comparingLong(candidate -> candidate.getKey().index()));
        order.addAll(written);
        return order;
    }

    /**
     * Applies the optional {@link BackupRetentionPolicy} grandfather-father-son
     * rotation to the checkpoint directory, then drops what it deleted from
     * the index. Best effort, as before story 331 (story 335 delivers the
     * policy to this directory).
     */
    private void applyRetentionPolicy(Session pruning, Set<Path> spared, Set<CheckpointFileName> attempted) {
        BackupRetentionPolicy policy = this.retentionPolicy;
        if (policy == null || pruning.ended) {
            return;
        }
        BackupRetentionService.Plan plan;
        try {
            List<BackupRetentionService.Snapshot> snapshots = new ArrayList<>();
            for (Path file : pruning.index.values()) {
                if (Files.isRegularFile(file)) snapshots.add(new BackupRetentionService.Snapshot(file,
                        Files.getLastModifiedTime(file).toInstant(), Files.size(file)));
            }
            plan = BackupRetentionService.plan(snapshots, policy, clock.instant());
        } catch (IOException ignored) {
            // best-effort cleanup
            return;
        }
        for (BackupRetentionService.Snapshot snapshot : plan.discarded()) {
            if (!mayStillPrune(pruning)) break;
            CheckpointFileName name = CheckpointFileName.parse(snapshot.path().getFileName().toString()).orElseThrow();
            if (spared.contains(snapshot.path()) || attempted.contains(name)) continue;
            if (deleteOrRefuse(pruning, snapshot.path(), name, config.maxCheckpoints(), pruning.refusedDeletes.contains(name))) {
                pruning.index.remove(name);
                pruning.refusedDeletes.remove(name);
                pruning.deletedCertifications.add(name.fileName());
            }
        }
    }

    /**
     * The first tick of a clean open, which seeding matched: skips when
     * {@code project.daw} is still byte-identical to the seeded checkpoint
     * (ruling G3), recording the revision as checkpointed by that file. If
     * the head changed since seeding, this tick writes nothing either; the
     * next one checkpoints.
     */
    private void skipIfTheSeedStillMatches(SeedSkipJob job) {
        Session ticking = job.session();
        try {
            Checkpointed done = ticking.checkpointed;
            if (done == null || done.revision() != job.revision()) {
                Path head = ticking.projectDirectory.resolve(ProjectManager.PROJECT_FILE_NAME);
                if (isIdentical(head, job.newest())) {
                    ticking.checkpointed = new Checkpointed(new Capture(job.revision(), OptionalLong.empty()), job.newest());
                } else {
                    LOG.fine(() -> head + " changed since the checkpoints of " + ticking.projectDirectory
                            + " were seeded; the next tick checkpoints it");
                }
            }
            rearm(ticking, CheckpointEvent.RearmReason.SKIPPED);
        } finally {
            ticking.tickInFlight.set(false);
        }
    }

    private void reportCaptureFailure(CaptureFailure job) {
        Session ticking = job.session();
        try {
            if (!config.enabled()) {
                LOG.log(Level.FINE, job.cause(), () -> "A capture failure of " + ticking.projectDirectory
                        + " is reported to no listener: autosave is disabled");
                return;
            }
            String id = nextCheckpointId();
            LOG.log(Level.SEVERE, job.cause(), () -> "Checkpoint " + id + " (" + CheckpointOrigin.SCHEDULED
                    + ") of " + ticking.projectDirectory + " could not capture the project; nothing was written");
            notifyFailed(ticking, id, CheckpointOrigin.SCHEDULED, job.cause());
            rearm(ticking, CheckpointEvent.RearmReason.FAILED);
        } finally {
            ticking.tickInFlight.set(false);
        }
    }

    private String nextCheckpointId() {
        return "chk-" + checkpointIds.incrementAndGet() + "-" + ID_STAMP.format(clock.instant());
    }

    // ── Listeners (fenced: an ended generation tells no one) ───────────────

    private void notifyBefore(Session notifying, String checkpointId) {
        for (AutoSaveListener listener : listeners) {
            if (notifying.ended) {
                return;
            }
            isolated(listener, "onBeforeCheckpoint", () -> listener.onBeforeCheckpoint(checkpointId));
        }
    }

    private void notifyWritten(Session notifying, String checkpointId, CheckpointOrigin origin, Path file,
                               Capture capture) {
        for (AutoSaveListener listener : listeners) {
            if (notifying.ended) {
                return;
            }
            isolated(listener, "onAfterCheckpoint", () -> listener.onAfterCheckpoint(checkpointId));
        }
        fire(notifying, new CheckpointEvent.Written(notifying.generation, checkpointId, origin, file,
                capture.revision(), capture.journalSequence()));
    }

    private void notifyFailed(Session notifying, String checkpointId, CheckpointOrigin origin, Throwable cause) {
        for (AutoSaveListener listener : listeners) {
            if (notifying.ended) {
                return;
            }
            isolated(listener, "onCheckpointFailed", () -> listener.onCheckpointFailed(checkpointId, cause));
        }
        fire(notifying, new CheckpointEvent.Failed(notifying.generation, checkpointId, origin, cause));
    }

    private void fire(Session notifying, CheckpointEvent event) {
        for (Consumer<CheckpointEvent> listener : eventListeners) {
            if (notifying.ended) {
                return;
            }
            isolated(listener, "an event listener", () -> listener.accept(event));
        }
    }

    private static void isolated(Object listener, String call, Runnable notification) {
        try {
            notification.run();
        } catch (Throwable thrown) {
            LOG.log(Level.WARNING, thrown, () -> "Checkpoint listener " + listener + " threw from " + call
                    + "; the other listeners are still notified and the outcome stands");
        }
    }

    // ── State ───────────────────────────────────────────────────────────────

    /** The owner executor and the source it captures. */
    private record CaptureSource(Executor owner, CheckpointSource source) {
    }

    /** The last revision this generation checkpointed, and the file that holds it. */
    private record Checkpointed(Capture capture, Path file) {
        long revision() { return capture.revision(); }
    }

    /** A checkpoint just written: the name its index and stamp were allocated as, and its file. */
    private record Published(CheckpointFileName name, Path file) {
    }

    /** What the getters read: the index's files, oldest first, and the highest index used. */
    private record IndexView(List<Path> files, long highestIndex) {

        static final IndexView EMPTY = new IndexView(List.of(), 0L);
    }

    /** What seeding found for the generation's first tick (ruling G3). */
    private sealed interface SeedMatch {

        /** Seeding has not finished. */
        SeedMatch UNSEEDED = new Unseeded();

        /** No match, or the match was used or invalidated. */
        SeedMatch NONE = new None();

        record Unseeded() implements SeedMatch {
        }

        record None() implements SeedMatch {
        }

        /** {@code project.daw} was byte-identical to {@code newest}, the newest seeded checkpoint. */
        record Matched(Path newest) implements SeedMatch {
        }
    }

    /** One generation: a started project's checkpoints. */
    private static final class Session {

        final long generation;
        final Path projectDirectory;
        final Path checkpointDirectory;
        final ScheduledThreadPoolExecutor scheduler;
        final CompletableFuture<Void> seeded = new CompletableFuture<>();

        /** Set by a tick from posting its capture until its job finished; at most one capture is pending. */
        final AtomicBoolean tickInFlight = new AtomicBoolean();

        final AtomicReference<SeedMatch> firstTick = new AtomicReference<>(SeedMatch.UNSEEDED);

        /** Written on the writer thread, read on the owner's. */
        volatile Checkpointed checkpointed;

        /** Written on the writer thread, read by the getters. */
        volatile IndexView view = IndexView.EMPTY;

        /** Writer-confined: the checkpoint files of the directory by {@link CheckpointFileName#ORDER}. */
        final Set<String> certified = new HashSet<>();
        final Set<String> deletedCertifications = new HashSet<>();
        boolean certificationEligible;
        final TreeMap<CheckpointFileName, Path> index = new TreeMap<>(CheckpointOrder.comparator(certified));

        /** Writer-confined: the highest index seeded or written. */
        long highestIndex;

        /** Writer-confined: every name in the index when seeding finished; the prune deletes these first. */
        Set<CheckpointFileName> seededNames = Set.of();

        /**
         * Writer-confined: the checkpoints whose delete was refused, least
         * recently tried first; they stay in the index and the cap does not
         * count them (ruling J1).
         */
        final LinkedHashSet<CheckpointFileName> refusedDeletes = new LinkedHashSet<>();

        /** Writer-confined: whether this generation logged a refused delete at {@code WARNING}. */
        boolean refusalReported;

        /** Written under {@link #stateLock}; once {@code true}, never {@code false} again. */
        volatile boolean ended;

        /** Guarded by {@link #stateLock}: the jobs of this generation the writers hold (queued or running). */
        int heldJobs;

        /**
         * Guarded by {@link #stateLock}: whether this generation's jobs may
         * still delete checkpoints. Cleared, never set again, when
         * {@link #stop()}'s bound passes with a claim still held (ruling G5 (3)).
         */
        boolean mayPrune = true;

        /**
         * What each checkpoint write, each compaction of the ordering fact and each stray-temp delete of this
         * generation checks first: its own lease's lock check (story 333). A prune's deletes run inside a write
         * that passed it and do not check it again.
         */
        final WriteGuard guard;

        /**
         * Guarded by {@link #stateLock}: the writer of this generation's
         * folder — the one it started with, until its seeding joins the
         * writer already running jobs in that folder (story 333).
         */
        FolderWriter writer;

        /** Guarded by {@link #stateLock}: whether this generation still counts among its writer's. */
        boolean attached;

        /** Writer-confined: whether the seeding has read the folder's fingerprint and found its writer. */
        boolean fingerprinted;

        /** Guarded by {@link #stateLock}: the claims of this generation's jobs not yet ended (story 333). */
        int claims;

        /** Completed once the generation ended and its last claim ended: {@link #stop()}'s stage. */
        final CompletableFuture<Void> claimsEnded = new CompletableFuture<>();

        /**
         * Guarded by {@link #stateLock}: the {@link #whenGenerationIdle}
         * stages still waiting, completed — outside the lock — when
         * {@link #heldJobs} drops to zero (story 333).
         */
        final List<CompletableFuture<Void>> idleWaiters = new ArrayList<>();

        /** The checkpoint being published, for {@link #stop()}'s warning. */
        volatile Path publishing;

        /** Scheduler-confined: the armed next tick. */
        ScheduledFuture<?> nextTick;

        Session(long generation, Path projectDirectory, ScheduledThreadPoolExecutor scheduler, WriteGuard guard) {
            this.generation = generation;
            this.projectDirectory = projectDirectory;
            this.checkpointDirectory = projectDirectory.resolve(CHECKPOINT_DIR_NAME);
            this.scheduler = scheduler;
            this.guard = guard;
        }

        /** Publishes the index for the getters; writer thread only. */
        void publishView() {
            view = new IndexView(List.copyOf(index.values()), highestIndex);
        }

        @Override
        public String toString() {
            return "checkpoints #" + generation + " of " + projectDirectory;
        }
    }

    /**
     * The one serial writer of a project folder (story 333): its jobs, run in
     * submission order on one virtual thread at a time, started on demand.
     * Generations whose folders are one share it.
     */
    private static final class FolderWriter {

        final ConcurrentLinkedQueue<WriterJob> queue = new ConcurrentLinkedQueue<>();
        final AtomicBoolean draining = new AtomicBoolean();

        /** Guarded by {@link #stateLock}: the folder's fingerprint once registered; {@code null} before. */
        FolderFingerprint fingerprint;

        /** Guarded by {@link #stateLock}: the generations attached to this writer. */
        int attachedGenerations;
    }

    /** A job of a folder writer, run in submission order on its thread. */
    private sealed interface WriterJob permits SeedJob, WriteJob, SeedSkipJob, CaptureFailure {

        /** The generation the job belongs to. */
        Session session();
    }

    /** Seeds the generation's index from its directory. */
    private record SeedJob(Session session) implements WriterJob {
    }

    /** Renders and writes one checkpoint of {@code revision}. */
    private record WriteJob(Session session, CheckpointOrigin origin, Capture capture,
                            CheckpointSource.Renderer content, CompletableFuture<Path> result) implements WriterJob {
        long revision() { return capture.revision(); }
    }

    /** A first tick that seeding matched: re-checks the head and records the revision as checkpointed. */
    private record SeedSkipJob(Session session, long revision, Path newest) implements WriterJob {
    }

    /** A tick whose capture failed or whose owner refused it: reported once on the writer. */
    private record CaptureFailure(Session session, Throwable cause) implements WriterJob {
    }

    /** The fence: a job's generation ended before it claimed its write. */
    private static final class GenerationEnded extends IOException {

        GenerationEnded(Session stopped) {
            super("The checkpoints of " + stopped.projectDirectory
                    + " were stopped before this checkpoint was written");
        }
    }
}
