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
 *   <li><strong>The writer</strong> — one virtual thread, started on demand
 *       while jobs are queued, so it can never reject one — runs every job in
 *       order: seeding at {@link #start}, every tick's write and every
 *       {@link #writeCheckpoint} (ruling F2: one ordered writer of the
 *       directory). It renders the capture, allocates the index, writes the
 *       file through the {@link AtomicFileWriter}, keeps the index of the
 *       directory and prunes it.</li>
 * </ul>
 * <p>No step reads the live model off the owner, and the owner never touches
 * the disk (book §6.1). This package takes no JavaFX dependency.</p>
 *
 * <h2>One write</h2>
 * <p>{@code onBeforeCheckpoint}; the {@linkplain #setWriteGuard write guard}
 * (the project lock is still ours); the render; the next index (only now, so
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
 * the lock a job takes to claim its write: no job of an ended generation
 * starts a write after {@code stop()}; a capture not yet taken is dropped,
 * and the futures of jobs still queued complete at once, off the caller's
 * thread. A write already inside the atomic write finishes into its own
 * project's directory and is reported to no listener (ruling F9, G5); it
 * prunes while {@code stop()} still waits for it, and decides no delete once
 * {@code stop()} has returned (one decided just before may still be issued). A
 * checkpoint {@link ProjectManager} requests for a save is bound, by
 * identity, to the generation that project's open started
 * ({@link Generation}): once that generation has ended it writes nothing
 * and tells no listener, whatever generation runs then — another project's,
 * or the same folder's opened again — so a save never writes into another
 * generation's checkpoints.</p>
 */
public final class CheckpointManager {

    /**
     * Checked on the writer just before each checkpoint is written, and
     * before seeding deletes a stray temp: that this session may still write
     * the project folder. {@link ProjectManager} installs its lock check
     * ({@link ProjectLockManager#verifyHeld()}, which also refuses when this
     * session holds no lock). A guard that throws fails the checkpoint
     * visibly.
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
     * {@link CheckpointOrigin#USER} checkpoint, the revision it holds is
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

    /** How long {@link #stop()} waits for the writer to finish an ended generation's jobs (ruling G5). */
    static final Duration STOP_WAIT = Duration.ofSeconds(5);

    /** The stamp of a checkpoint id, in ASCII digits under every default locale. */
    private static final DateTimeFormatter ID_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT).withZone(ZoneId.systemDefault());

    /** Bound while a manager's writer runs a job: a {@link #stop()} from a listener there must not wait on itself. */
    private static final ScopedValue<CheckpointManager> WRITING = ScopedValue.newInstance();

    private volatile AutoSaveConfig config;
    private final AtomicFileWriter fileWriter;
    private final InstantSource clock;
    private final FileDeleter deleter;
    private final Duration stopWait;

    private final List<AutoSaveListener> listeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<CheckpointEvent>> eventListeners = new CopyOnWriteArrayList<>();
    private volatile BackupRetentionPolicy retentionPolicy;
    private volatile CaptureSource captureSource;
    private volatile WriteGuard writeGuard = () -> { };

    /** Serializes {@link #start}, {@link #stop} and {@link #reconfigure}; never taken by the writer. */
    private final Object lifecycle = new Object();

    /**
     * Guards each generation's end ({@link Session#ended}) and its count of
     * jobs the writer holds: a job claims its write under it, and
     * {@link #stop()} ends the generation under it.
     */
    private final ReentrantLock stateLock = new ReentrantLock();
    private final Condition writerReleased = stateLock.newCondition();

    /**
     * Guarded by {@link #stateLock}: the jobs of every generation the writer
     * holds, queued or running — the sum of each generation's
     * {@link Session#heldJobs}, which the wait at exit asks (ruling M1).
     */
    private int heldJobsOfEveryGeneration;

    private final AtomicLong generations = new AtomicLong();
    private final AtomicLong checkpointIds = new AtomicLong();

    /** The current generation, or the last one once stopped; {@code null} before the first start. */
    private volatile Session session;

    private final ConcurrentLinkedQueue<WriterJob> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean draining = new AtomicBoolean();

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
     * checkpoints and stray temps, and {@link #stop()}'s bound.
     */
    CheckpointManager(AutoSaveConfig config, AtomicFileWriter fileWriter, InstantSource clock, FileDeleter deleter,
                      Duration stopWait) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.fileWriter = Objects.requireNonNull(fileWriter, "fileWriter must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.deleter = Objects.requireNonNull(deleter, "deleter must not be null");
        this.stopWait = Objects.requireNonNull(stopWait, "stopWait must not be null");
    }

    /**
     * Starts the checkpoints of the project in {@code projectDirectory}: a new
     * generation, whose first writer job seeds the index from
     * {@code checkpoints/} (see the class note, "Retention"). The first tick
     * is armed a full interval after seeding finishes. This call does no disk
     * I/O. A generation still running — another project, or this one — is
     * {@linkplain #stop() stopped} first, which may wait for its writer.
     * With autosave disabled the generation still begins and seeds, so user
     * checkpoints and safety copies can be written, but no tick is armed
     * (ruling H3).
     *
     * @param projectDirectory the directory where checkpoints will be stored
     * @return completes when seeding finished (exceptionally when the
     *         directory could not be read, or the generation was stopped
     *         first)
     */
    public CompletableFuture<Void> start(Path projectDirectory) {
        return startGeneration(projectDirectory).session.seeded;
    }

    /**
     * {@link #start}, returning the generation it began, so that checkpoints
     * can be requested against that generation alone
     * ({@link #writeCheckpoint(Generation, String, long, CheckpointOrigin)}).
     *
     * @param projectDirectory the directory where checkpoints will be stored
     * @return the generation this call began
     */
    Generation startGeneration(Path projectDirectory) {
        Objects.requireNonNull(projectDirectory, "projectDirectory must not be null");
        synchronized (lifecycle) {
            Session previous = session;
            if (previous != null && !previous.ended) {
                stop();
            }
            Session started = new Session(generations.incrementAndGet(), projectDirectory, newScheduler());
            // Queued before the generation is published: no request reaches the writer ahead of its seeding.
            submit(new SeedJob(started));
            session = started;
            return new Generation(started);
        }
    }

    /**
     * Stops the checkpoints of the current project, ending its generation
     * (rulings F9, G5): the next tick is cancelled, a capture not yet taken
     * is dropped, and no job of this generation starts a write after this
     * call — jobs still queued write nothing, and their futures complete
     * exceptionally with an {@link IOException} at once, on a thread of their
     * own, even while a write ahead of them is held on a stalled volume. It
     * then waits, at most five seconds in all, until the writer holds no job
     * of this generation, so the project lock is released after this
     * folder's writer finished. It never interrupts a write and never waits
     * on the owner executor. A job that finishes its write while this call
     * waits prunes as usual and notifies no listener; one still inside its
     * atomic write when the bound runs out finishes into its own directory
     * and notifies no listener (ruling G5 (3)): no delete of this generation
     * is decided after this call returns; one decided just before it, like a
     * write already under way, may still be issued.
     */
    public void stop() {
        synchronized (lifecycle) {
            Session ending = session;
            if (ending == null || ending.ended) {
                return;
            }
            int held;
            List<WriterJob> unclaimed;
            stateLock.lock();
            try {
                ending.ended = true;
                held = ending.heldJobs;
                unclaimed = queuedJobsOf(ending);
            } finally {
                stateLock.unlock();
            }
            ending.scheduler.shutdown();
            completeUnclaimed(ending, unclaimed);
            if (held > 0) {
                awaitWriterRelease(ending);
            }
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
        synchronized (lifecycle) {
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
     * Installs the check made on the writer just before each checkpoint is
     * written (see {@link WriteGuard}). The default passes.
     *
     * @param guard the write guard
     */
    public void setWriteGuard(WriteGuard guard) {
        this.writeGuard = Objects.requireNonNull(guard, "guard must not be null");
    }

    /**
     * Writes a checkpoint holding {@code xml} — the document a manual save
     * just wrote to {@code project.daw}, the user's Create Checkpoint, or a
     * failed save's safety copy — through the same ordered writer as the
     * ticks (rulings F2, F3), as a job of the generation running at the
     * call. Unless {@code origin} is
     * {@link CheckpointOrigin#USER}, nothing is written when {@code revision}
     * is already checkpointed: the future then names the file that holds it.
     * A user checkpoint is always written (ruling G8). Any call invalidates
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
        Objects.requireNonNull(xml, "xml must not be null");
        requireARequestOrigin(origin);
        Session current = session;
        if (current == null || current.ended) {
            CompletableFuture<Path> notRunning = new CompletableFuture<>();
            notRunning.completeExceptionally(new IOException(
                    "Checkpoints are not running: no project directory is open for checkpoints"));
            return notRunning;
        }
        return request(current, xml, revision, origin);
    }

    /**
     * {@link #writeCheckpoint(String, long, CheckpointOrigin)}, bound to
     * {@code generation} instead of the generation current at the call
     * (story 331, ruling F9): {@link ProjectManager} requests a save's
     * checkpoint and a failed save's safety copy through it, against the
     * generation its project's open started. While that generation runs, the
     * request is written into its folder, as above. Once it has ended — the
     * project closed, abandoned or replaced, or its folder opened again — the
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
        Objects.requireNonNull(generation, "generation must not be null");
        Objects.requireNonNull(xml, "xml must not be null");
        requireARequestOrigin(origin);
        return request(generation.session, xml, revision, origin);
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
    private CompletableFuture<Path> request(Session bound, String xml, long revision, CheckpointOrigin origin) {
        CompletableFuture<Path> result = new CompletableFuture<>();
        bound.firstTick.set(SeedMatch.NONE);
        if (isAutomatic(origin) && !config.enabled()) {
            // Known at the call: it writes nothing and orders nothing, so it waits behind no job (ruling J2).
            result.completeExceptionally(autosaveDisabled(bound, origin));
            return result;
        }
        submit(new WriteJob(bound, origin, revision, () -> xml, result));
        return result;
    }

    /**
     * Adds a listener of the SDK checkpoint events: told just before each
     * checkpoint is written, and once of its success or failure. Listeners
     * are called on the checkpoint writer thread and must not block.
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
     * fact, on the checkpoint writer thread (seeding and outcomes) or the
     * scheduler thread (re-arms), and must not block; a UI listener marshals
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
            CheckpointSource.Renderer renderer = Objects.requireNonNull(source.capture(),
                    "the capture returned no renderer");
            submit(new WriteJob(ticking, CheckpointOrigin.SCHEDULED, revision, renderer, new CompletableFuture<>()));
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

    /** Queues {@code job} for the writer, or drops it when its generation has ended. */
    private void submit(WriterJob job) {
        Session owner = job.session();
        boolean accepted;
        stateLock.lock();
        try {
            accepted = !owner.ended;
            if (accepted) {
                owner.heldJobs++;
                heldJobsOfEveryGeneration++;
                queue.add(job);
            }
        } finally {
            stateLock.unlock();
        }
        if (accepted) {
            ensureDrain();
        } else {
            drop(job, stoppedBefore(owner));
        }
    }

    /** Starts the writer's virtual thread unless one is draining the queue. */
    private void ensureDrain() {
        if (!draining.compareAndSet(false, true)) {
            return;
        }
        try {
            Thread.ofVirtual().name("daw-checkpoint-writer")
                    .start(() -> ScopedValue.where(WRITING, this).run(this::drain));
        } catch (Throwable notStarted) {
            draining.set(false);
            LOG.log(Level.SEVERE, notStarted, () -> "The checkpoint writer could not start; its queued jobs fail");
            WriterJob job;
            while ((job = queue.poll()) != null) {
                drop(job, notStarted);
                released(job.session());
            }
        }
    }

    private void drain() {
        while (true) {
            WriterJob job;
            while ((job = queue.poll()) != null) {
                runJob(job);
            }
            draining.set(false);
            if (queue.isEmpty() || !draining.compareAndSet(false, true)) {
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
                case SeedJob seed -> seed(seed.session());
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
        stateLock.lock();
        try {
            owner.heldJobs--;
            heldJobsOfEveryGeneration--;
            writerReleased.signalAll();
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * The jobs of {@code ended} still queued; under {@link #stateLock}, just
     * after it ended. None of them can start a write: a job the writer takes
     * now is dropped, and a claim checks the end under the same lock.
     */
    private List<WriterJob> queuedJobsOf(Session ended) {
        List<WriterJob> queued = new ArrayList<>();
        for (WriterJob job : queue) {
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
            Thread.ofVirtual().name("daw-checkpoint-stop").start(() -> {
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

    private void awaitWriterRelease(Session ending) {
        if (WRITING.isBound() && WRITING.get() == this) {
            LOG.warning(() -> "Checkpoints of " + ending.projectDirectory + " were stopped from the checkpoint"
                    + " writer's own thread; its remaining jobs are dropped without waiting");
            forbidPruning(ending);
            return;
        }
        LOG.info(() -> "Waiting up to " + stopWait.toMillis() + " ms for the checkpoint writer to finish with "
                + ending.projectDirectory);
        long deadline = System.nanoTime() + stopWait.toNanos();
        boolean interrupted = false;
        stateLock.lock();
        try {
            while (ending.heldJobs > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    // Decided here, under the lock each prune asks before each delete (ruling G5 (3)).
                    ending.mayPrune = false;
                    Path writing = ending.publishing;
                    LOG.warning(() -> "The checkpoint writer did not finish with " + ending.projectDirectory
                            + " within " + stopWait.toMillis() + " ms"
                            + (writing == null ? "" : "; " + writing + " may still be published there")
                            + "; it notifies no listener and prunes nothing more");
                    return;
                }
                try {
                    writerReleased.awaitNanos(remaining);
                } catch (InterruptedException interruptedWhileWaiting) {
                    interrupted = true;
                }
            }
        } finally {
            stateLock.unlock();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** {@link #stop()} returns while {@code ending}'s jobs are still held: from here on they delete nothing. */
    private void forbidPruning(Session ending) {
        stateLock.lock();
        try {
            ending.mayPrune = false;
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Whether a job of {@code pruning} may delete a checkpoint now: until
     * {@link #stop()} returns with the generation's jobs still held (ruling
     * G5 (3)). Asked under {@link #stateLock} before each delete, where
     * {@code stop()} decides it, so no delete is decided after {@code stop()}
     * returns; one decided just before it, like a write already under way,
     * may still be issued.
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
     * Whether the writer holds a job of any generation, queued or running
     * (story 331, ruling M1): what {@link ProjectManager#awaitWritesBeforeExit()}
     * asks as the application exits.
     *
     * @return {@code true} while it holds one
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
     * Waits until the writer holds no job of any generation — every job of
     * any origin handed to it, queued or running, has returned — or until
     * {@code deadline}, a {@link System#nanoTime()} value (story 331, ruling
     * M1: the second half of the wait at exit). It queues, cancels,
     * completes and interrupts nothing; a job handed over while it waits is
     * waited for too, within the same deadline.
     *
     * @param deadline when to stop waiting, as {@link System#nanoTime()} reads it
     * @return whether the writer held no job before the deadline
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

    // ── Writer jobs ─────────────────────────────────────────────────────────

    /**
     * Seeds the index from the directory, records the first tick's match
     * (ruling G3), deletes stray temps, and reports what it found
     * ({@link CheckpointEvent.Seeded}, ruling K1: the newest seeded checkpoint,
     * or none when the folder held none or could not be read) before the
     * seeded future completes and before any write of the generation.
     */
    private void seed(Session seeding) {
        try {
            List<Path> strayTemps = new ArrayList<>();
            if (Files.isDirectory(seeding.checkpointDirectory)) {
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(seeding.checkpointDirectory)) {
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
            seeding.publishView();
            seeding.firstTick.compareAndSet(SeedMatch.UNSEEDED, seedMatchOf(seeding));
            deleteStrayTemps(seeding, strayTemps);
            Optional<Path> newest = seeding.index.isEmpty()
                    ? Optional.empty()
                    : Optional.of(seeding.index.lastEntry().getValue());
            fire(seeding, new CheckpointEvent.Seeded(seeding.generation, newest));
            seeding.seeded.complete(null);
        } catch (Throwable unreadable) {
            LOG.log(Level.WARNING, unreadable, () -> "Could not read the checkpoints of " + seeding.projectDirectory
                    + "; checkpoints are written from index " + (seeding.highestIndex + 1));
            seeding.firstTick.compareAndSet(SeedMatch.UNSEEDED, SeedMatch.NONE);
            fire(seeding, new CheckpointEvent.Seeded(seeding.generation, Optional.empty()));
            seeding.seeded.completeExceptionally(unreadable);
        } finally {
            seeding.seededNames = Set.copyOf(seeding.index.keySet());
            rearm(seeding, CheckpointEvent.RearmReason.STARTED);
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
            writeGuard.check();
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

    /** One checkpoint write, in the class note's order ("One write"). */
    private void write(WriteJob job) {
        Session target = job.session();
        try {
            if (isAutomatic(job.origin()) && !config.enabled()) {
                job.result().completeExceptionally(autosaveDisabled(target, job.origin()));
                return;
            }
            Checkpointed done = target.checkpointed;
            if (job.origin() != CheckpointOrigin.USER && done != null && done.revision() == job.revision()) {
                job.result().complete(done.file());
                rearm(target, CheckpointEvent.RearmReason.SKIPPED);
                return;
            }
            String id = nextCheckpointId();
            notifyBefore(target, id);
            Published published;
            try {
                writeGuard.check();
                String content = Objects.requireNonNull(job.content().render(),
                        "the checkpoint's renderer returned no content");
                published = publish(target, content);
            } catch (GenerationEnded stopped) {
                LOG.log(Level.INFO, () -> "Checkpoint " + id + " was not written: " + stopped.getMessage());
                job.result().completeExceptionally(stopped);
                return;
            } catch (Throwable failure) {
                LOG.log(Level.SEVERE, failure, () -> "Checkpoint " + id + " (" + job.origin() + ") of "
                        + target.projectDirectory + " failed; nothing was written");
                notifyFailed(target, id, job.origin(), failure);
                job.result().completeExceptionally(failure);
                rearm(target, CheckpointEvent.RearmReason.FAILED);
                return;
            }
            recordWritten(target, id, job, published);
        } finally {
            if (job.origin() == CheckpointOrigin.SCHEDULED) {
                target.tickInFlight.set(false);
            }
        }
    }

    /**
     * Everything after a checkpoint is on disk: the revision it holds, the
     * index, the listeners, the prune, the future, the re-arm. Nothing here
     * turns the written checkpoint into a failure or a drop: an index that
     * cannot take it is logged, and the write still reports its one outcome.
     */
    private void recordWritten(Session target, String id, WriteJob job, Published published) {
        Path file = published.file();
        target.checkpointed = new Checkpointed(job.revision(), file);
        try {
            target.index.put(published.name(), file);
            target.publishView();
        } catch (Throwable unindexed) {
            LOG.log(Level.WARNING, unindexed, () -> "Checkpoint " + id + " was written to " + file
                    + " but could not join the index of " + target.projectDirectory
                    + "; the next start counts it");
        }
        notifyWritten(target, id, job.origin(), file, job.revision());
        prune(target, file);
        job.result().complete(file);
        rearm(target, CheckpointEvent.RearmReason.WRITTEN);
    }

    /** The automatic checkpoints, which autosave being disabled turns off (ruling H3). */
    private static boolean isAutomatic(CheckpointOrigin origin) {
        return switch (origin) {
            case SCHEDULED, AFTER_SAVE -> true;
            case USER, SAFETY_COPY -> false;
        };
    }

    private static IOException autosaveDisabled(Session target, CheckpointOrigin origin) {
        IOException notWritten = new IOException("Autosave is disabled: no " + origin + " checkpoint of "
                + target.projectDirectory + " is written");
        LOG.log(Level.FINE, () -> notWritten.getMessage());
        return notWritten;
    }

    /**
     * Claims the write under {@link #stateLock} — the fence {@link #stop()}
     * ends a generation under — allocates the next index and writes the new
     * checkpoint through the atomic writer. The name it returns is the one
     * it allocated, never the published file's name read back.
     */
    private Published publish(Session target, String content) throws IOException {
        long index;
        Published published;
        stateLock.lock();
        try {
            if (target.ended) {
                throw new GenerationEnded(target);
            }
            index = Math.addExact(target.highestIndex, 1L);
            CheckpointFileName name = CheckpointFileName.of(index, clock.instant(), ZoneId.systemDefault());
            published = new Published(name, target.checkpointDirectory.resolve(name.fileName()));
            target.publishing = published.file();
        } finally {
            stateLock.unlock();
        }
        try {
            Files.createDirectories(target.checkpointDirectory);
            fileWriter.writeString(published.file(), content);
        } finally {
            target.publishing = null;
        }
        target.highestIndex = index;
        return published;
    }

    /**
     * Deletes checkpoints until the cap holds (rulings F5, H4, J1): the
     * seeded ones first, oldest first by {@link CheckpointFileName#ORDER};
     * then this generation's own writes, oldest written first.
     * {@code written} — the checkpoint just written — and the file holding
     * the last checkpointed revision are never deleted. A file whose delete
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
     * G13 #1). A job of a generation {@link #stop()} ended prunes while
     * {@code stop()} still waits for it; once {@code stop()} has returned
     * with it still held — its bound ran out, or it was called on the writer
     * — no delete of the job is decided any more (ruling G5 (3)); one
     * decided just before that moment, like a write already under way, may
     * still be issued.
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
                if (deleteOrRefuse(pruning, candidate.getValue(), name, cap, retried)) {
                    pruning.index.remove(name);
                    pruning.refusedDeletes.remove(name);
                }
            }
            applyRetentionPolicy(pruning);
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
    private void applyRetentionPolicy(Session pruning) {
        BackupRetentionPolicy policy = this.retentionPolicy;
        if (policy == null || pruning.ended) {
            return;
        }
        BackupRetentionService.Plan plan;
        try {
            plan = new BackupRetentionService(policy).prune(pruning.checkpointDirectory);
        } catch (IOException ignored) {
            // best-effort cleanup
            return;
        }
        Set<Path> kept = new HashSet<>();
        for (BackupRetentionService.Snapshot snapshot : plan.kept()) {
            kept.add(snapshot.path());
        }
        pruning.index.values().removeIf(path -> !kept.contains(path));
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
                    ticking.checkpointed = new Checkpointed(job.revision(), job.newest());
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
                               long revision) {
        for (AutoSaveListener listener : listeners) {
            if (notifying.ended) {
                return;
            }
            isolated(listener, "onAfterCheckpoint", () -> listener.onAfterCheckpoint(checkpointId));
        }
        fire(notifying, new CheckpointEvent.Written(notifying.generation, checkpointId, origin, file, revision));
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
    private record Checkpointed(long revision, Path file) {
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
        final TreeMap<CheckpointFileName, Path> index = new TreeMap<>(CheckpointFileName.ORDER);

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

        /** Guarded by {@link #stateLock}: the jobs of this generation the writer holds (queued or running). */
        int heldJobs;

        /**
         * Guarded by {@link #stateLock}: whether this generation's jobs may
         * still delete checkpoints. Cleared, never set again, when
         * {@link #stop()} returns while they are still held (ruling G5 (3)).
         */
        boolean mayPrune = true;

        /** The checkpoint being published, for {@link #stop()}'s warning. */
        volatile Path publishing;

        /** Scheduler-confined: the armed next tick. */
        ScheduledFuture<?> nextTick;

        Session(long generation, Path projectDirectory, ScheduledThreadPoolExecutor scheduler) {
            this.generation = generation;
            this.projectDirectory = projectDirectory;
            this.checkpointDirectory = projectDirectory.resolve(CHECKPOINT_DIR_NAME);
            this.scheduler = scheduler;
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

    /** A job of the writer, run in submission order on its thread. */
    private sealed interface WriterJob permits SeedJob, WriteJob, SeedSkipJob, CaptureFailure {

        /** The generation the job belongs to. */
        Session session();
    }

    /** Seeds the generation's index from its directory. */
    private record SeedJob(Session session) implements WriterJob {
    }

    /** Renders and writes one checkpoint of {@code revision}. */
    private record WriteJob(Session session, CheckpointOrigin origin, long revision,
                            CheckpointSource.Renderer content, CompletableFuture<Path> result) implements WriterJob {
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
