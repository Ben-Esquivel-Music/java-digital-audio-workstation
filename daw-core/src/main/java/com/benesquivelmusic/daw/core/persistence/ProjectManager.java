package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.persistence.migration.MigrationReport;
import com.benesquivelmusic.daw.core.project.DawProject;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the lifecycle of DAW projects on disk.
 *
 * <p>Provides create, open, save, and close operations. Each project is
 * stored in its own directory. The project manager coordinates with a
 * {@link CheckpointManager} to automatically save project state during
 * long-running recording sessions.</p>
 *
 * <p>Full project state — tracks, clips, mixer settings, transport, and
 * metadata — is serialized to XML via {@link ProjectSerializer} and
 * restored via {@link ProjectDeserializer}. Every write of the project file
 * — the full state, the metadata-only file and the sibling backups — goes
 * through the {@link AtomicFileWriter} (story 331): a failed write leaves
 * {@code project.daw} as it was. A {@code project.daw} that is a symbolic
 * link to a {@code .daw} file stays a link, and that file is written; one
 * that leads anywhere else is refused, and nothing is written (ruling
 * M3).</p>
 *
 * <h2>The save</h2>
 * <p>{@link #save(DawProject)} is the one way the project's state reaches
 * {@code project.daw} (story 331; Persistence Integrity book §4.2, §5.2,
 * rejection §9.8). The caller — the thread that owns the model, in the
 * application the JavaFX application thread — only captures: it stamps the
 * metadata, reads the edit revision and walks the model into a
 * {@link ProjectDocument}. Everything else runs on the <em>save writer</em>,
 * one virtual thread started while saves are queued, so it can never refuse
 * one, in this order (rulings F1, F3, F10, G9): the generation fence (a
 * capture of a project closed or replaced since writes nothing); the lock
 * check of the lease its open took ({@link ProjectLockManager#verifyOurs()});
 * the render, once; the head-write claim, the fence's last check (story
 * 333); the backup the open owes, a durable copy that never overwrites a
 * backup; the atomic head write; the project marked clean if no edit came since the
 * capture; then, if the project is still the open one, the checkpoint of the
 * same bytes. One save writes at a time, and one capture waits behind it: a
 * newer capture of the same open project takes the waiting one's place, and
 * that one's future completes with the newer one's outcome (ruling F2), so
 * captures are written in the order they were taken and never older over
 * newer. A capture of a project closed or replaced since never gives its
 * place to the next project's: it meets the fence.</p>
 *
 * <p>A save's checkpoint and a failed save's safety copy are bound, by
 * identity, to the checkpoint generation its project's open started
 * (ruling F9): once that project is closed or replaced — the same folder
 * opened again included — neither is written, and no checkpoint listener
 * hears of it. The head write's last check is a claim, taken under the same
 * lock that fences the project's saves: a head write that claimed before the
 * fence still finishes into its own project's folder and writes no
 * checkpoint, and one that did not writes nothing.</p>
 *
 * <h2>The lock hand-off</h2>
 * <p>Closing or replacing a project never waits (story 333; Persistence
 * Integrity book §5.4, rejection §9.14): its saves are fenced, its
 * checkpoints stop, and its lease is <em>retired</em> — released, its lock
 * file deleted if it is still ours, on a {@code daw-lease-retire} thread of
 * its own, only once everything this session still owes that folder has
 * ended: the saves queued for it before the close, a head write past its
 * claim, and the checkpoint writer's claimed work there
 * ({@link CheckpointManager#stop()}). Until then the retiring lease keeps its
 * heartbeat, so nothing of this session writes or deletes in a folder after
 * its lock is gone, and another session never meets a folder that is still
 * being written without its lock. {@link #whenRetiredLeasesReleased()} says
 * when the releases have run. Reopening a folder whose lease is still
 * retiring takes this session's own lock over silently
 * ({@link ProjectLockManager#handOverTo}); the old release then deletes
 * nothing, and the obligation moves with the lock: the lease that took it
 * over is itself released — closed, replaced, or never adopted by a
 * failed open — only once the old lease's owed work has ended too. A copy
 * of the folder that carries the retiring lock file is not that folder: its
 * open meets the lock-conflict prompt.</p>
 *
 * <h2>Save As</h2>
 * <p>A Save As (story 333, ruling R5) is four steps, so the application can
 * keep the open project untouched until the copy is whole and locked: the
 * capture on the model's owner ({@link #captureForSaveAs}), the plan and the
 * copy on a worker ({@link #planSaveAs}, {@link #publishCopy}: every file
 * through the {@link AtomicFileWriter}, {@code project.daw} last, a staged
 * copy published by one rename), and — once the application took the copy's
 * lock and closed the open project — {@link #adoptPublishedCopy}, which makes
 * the copy the open project without reloading it, so the session and its
 * undo history go on. The copy's saves and checkpoints write a reference
 * into the folders it was copied from (its former roots) project-relative
 * only when the copy holds its file ({@link FormerRoots}); any other stays
 * absolute and still names the original's file.</p>
 *
 * <p>The save writer waits for the checkpoint it requests at most five
 * seconds (ruling J3): past it the save completes without
 * naming one, the checkpoint is still written, and the checkpoint listeners
 * hear of it when it is. A head write or backup that fails after the render,
 * with the lock still ours and the project still open, leaves the rendered
 * bytes in a safety checkpoint (ruling G9).</p>
 *
 * <p>The save writer and the checkpoint writers — one per project folder
 * (story 333) — are daemon virtual threads: the JVM does not wait for them,
 * and an exit can end them mid-write. The application waits for all of them,
 * at most thirty seconds, from its one JVM shutdown hook
 * ({@link #awaitWritesBeforeExit()}, ruling M1).</p>
 *
 * <h2>Project Directory Layout</h2>
 * <pre>
 *   MyProject/
 *     project.daw            — full project state (XML)
 *     audio/                 — recorded audio (story 323; see {@link #audioDirectory(Path)})
 *       takes/
 *         2026-08-03T14-22-05_take-0007/   — one directory per record gesture
 *           take.manifest                  — ordered segment lists, format, seal status
 *           &lt;trackId&gt;/segment-000.wav      — sealed segments; segment-NNN.wav.part while streaming
 *     assets/                — audio an archive restore or a consolidation put there ({@link #ASSETS_DIR_NAME})
 *     archives/              — packaged archives the disk-usage view counts ({@link #ARCHIVES_DIR_NAME})
 *     checkpoints/           — auto-save checkpoints
 * </pre>
 */
public final class ProjectManager {

    private static final Logger LOG = Logger.getLogger(ProjectManager.class.getName());

    /** The project's head file; the {@link CheckpointManager} compares the newest checkpoint with it. */
    static final String PROJECT_FILE_NAME = "project.daw";

    /**
     * How long the save writer waits for each checkpoint it requests — the
     * one after a save and a failed save's safety copy — before it completes
     * the save without it (ruling J3: the value of
     * {@link CheckpointManager#STOP_WAIT}, ruling G5).
     */
    static final Duration CHECKPOINT_WAIT = Duration.ofSeconds(5);

    /**
     * How long {@link #awaitWritesBeforeExit()} waits in all, counted from
     * its start, for the saves and checkpoints still being written as the
     * application exits (ruling M1). Public for the application-stop
     * fallback, whose waits share this bound (story 333, ruling R4: "bounded
     * at 30 s, the same bound as the M1 hook").
     */
    public static final Duration EXIT_WRITE_WAIT = Duration.ofSeconds(30);

    /**
     * Name of the per-project directory that holds recorded audio — the only
     * home for captured takes (Recording Reliability book §2.6).
     */
    public static final String AUDIO_DIR_NAME = "audio";

    /**
     * Name of the per-project directory that holds the audio an archive
     * restore or a consolidation put there ({@code assets/<sha>_<name>};
     * {@code ProjectArchiver}): like {@code audio/}, a home of the project's
     * own audio, which Save As copies whole (story 333).
     */
    public static final String ASSETS_DIR_NAME = "assets";

    /**
     * Name of the header an archive carries, which an archive restore leaves
     * at the top of the restored folder ({@code ArchiveHeader}): the
     * archive's own description, stale in any copy of the folder, which Save
     * As therefore leaves out (story 333).
     */
    public static final String ARCHIVE_HEADER_FILE_NAME = "archive.properties";

    /**
     * Name of the per-project directory the disk-usage view counts as the
     * project's packaged archives ({@code ProjectDiskUsage}): copies of the
     * original at past moments, each self-contained, which Save As leaves
     * out — they are the original's history, not the copy's (story 333, the
     * studio engineer's ruling 3 Q2).
     */
    public static final String ARCHIVES_DIR_NAME = "archives";

    /**
     * Returns {@code <projectDir>/audio} — where recorded takes live
     * ({@code audio/takes/<stamp>_take-NNNN/…}, allocated by
     * {@code com.benesquivelmusic.daw.core.recording.TakeDirectories}).
     * Created by {@link #createProject(String, Path)}.
     *
     * @param projectDir the project directory
     * @return the audio directory path (not necessarily existing yet)
     */
    public static Path audioDirectory(Path projectDir) {
        return Objects.requireNonNull(projectDir, "projectDir must not be null")
                .resolve(AUDIO_DIR_NAME);
    }

    /**
     * Where every retired lease is released (story 333): never the caller —
     * the JavaFX application thread in the application — nor the save writer
     * or a checkpoint writer, whose work the release waits for; except when
     * no thread can be started (see {@link #startRetirement}).
     */
    private static final Executor RETIRE_EXECUTOR = ProjectManager::startRetirement;

    /**
     * Starts a release on a {@code daw-lease-retire} virtual thread — or, when
     * no thread can be started, runs it on the caller, with a {@code WARNING}:
     * a release that never runs would keep its lock, its heartbeat refreshing
     * it, for the rest of the session.
     */
    private static void startRetirement(Runnable release) {
        try {
            Thread.ofVirtual().name("daw-lease-retire").start(release);
        } catch (Throwable notStarted) {
            LOG.log(Level.WARNING, notStarted, () -> "No thread could be started for a project lock's release; it"
                    + " runs on the calling thread");
            release.run();
        }
    }

    /**
     * One retired lease that held a lock: the folder it held, and its release
     * — completed, never exceptionally, once the work owed in that folder,
     * this lease's and what it inherited, has ended and the release has run.
     */
    private record Retirement(ProjectLockManager lease, Path folder, CompletableFuture<Void> released) {
        Retirement {
            Objects.requireNonNull(lease, "lease must not be null");
            Objects.requireNonNull(folder, "folder must not be null");
            Objects.requireNonNull(released, "released must not be null");
        }
    }

    /**
     * Every lease this manager retired that held a lock, by that lock's id;
     * never removed (lock ids are random UUIDs): how a reopen of a folder
     * recognises this session's own lock, still on disk while its release
     * waits for the work owed in that folder (story 333).
     */
    private final Map<String, Retirement> retiredLeases = new ConcurrentHashMap<>();

    /**
     * The work a lease inherited with the lock it took over from a retiring
     * lease of this session (story 333): that lease's release, which waits
     * for everything still owed in the folder. Keyed by identity; an entry
     * leaves when its lease is retired, and the lease's own release then
     * waits for it too — so the obligation moves with the lock, and a lock
     * handed over is never released while the work of an earlier lease of
     * the folder may still write there.
     */
    private final Map<ProjectLockManager, CompletableFuture<Void>> inheritedWork =
            Collections.synchronizedMap(new IdentityHashMap<>());

    /** The releases of retired leases not yet run (story 333); each leaves once it has run. */
    private final Set<CompletableFuture<Void>> retirements = ConcurrentHashMap.newKeySet();

    /**
     * Fences the open project's saves ({@link #saveTarget}) and guards the
     * head-write claim ({@link SaveTarget#headWriteEnded}): a head write
     * claims its target under it exactly while it is still the open one, and
     * every close and replacement swaps the target under it. Never held
     * across disk I/O or a wait (Project Manager book §8).
     */
    private final ReentrantLock fenceLock = new ReentrantLock();

    /** Written by the create, open and save paths, which may run on workers; read by any thread. */
    private final Map<String, ProjectMetadata> recentProjects = Collections.synchronizedMap(new LinkedHashMap<>());
    private final CheckpointManager checkpointManager;
    private final RecentProjectsStore recentProjectsStore;
    private final ProjectSerializer serializer = new ProjectSerializer();
    private final ProjectDeserializer deserializer;
    private volatile ProjectLockManager lockManager;
    private final AtomicFileWriter fileWriter;
    /** Plans and makes Save As copies, every file through {@link #fileWriter} (story 333). */
    private final ProjectFolderCopy folderCopy;
    private final Duration checkpointWait;

    private volatile ProjectMetadata currentProject;
    private volatile DawProject currentDawProject;
    private volatile LockConflictHandler lockConflictHandler;
    private volatile boolean readOnly;
    @FunctionalInterface
    public interface RecoveryGate {
        void beforeParse(Path directory, boolean readOnly, ProjectLockManager lock) throws IOException;
    }
    private volatile RecoveryGate recoveryGate = (_, _, _) -> { };

    /** Installed by the application open pipeline; invoked after lock negotiation, before parsing or checkpoints. */
    public void setRecoveryGate(RecoveryGate gate) { recoveryGate = Objects.requireNonNull(gate); }

    /**
     * The save generation of the open project — what its backup owes, where
     * it is written, the lease that guards it and the checkpoint generation
     * its checkpoints belong to — or {@code null} when none is open, and
     * while a create or an open replaces it. Written only under
     * {@link #fenceLock} ({@link #fence}): replaced by every create and open,
     * cleared by every close; a capture carries the one it was taken under,
     * and the save writer writes it only while it is still this one.
     */
    private volatile SaveTarget saveTarget;

    /**
     * Migration report from the most recent {@link #openProject(Path)} call.
     * When {@link MigrationReport#wasMigrated()} is true, the next save
     * will first back up the original on-disk file (see
     * {@link #save(DawProject)}) so the user can roll back to the
     * pre-migration version.
     */
    private volatile MigrationReport lastMigrationReport = MigrationReport.noOp(
            com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry.CURRENT_VERSION);

    /**
     * Missing-assets report built when the currently open project was loaded
     * (Persistence Integrity book §4.3, §5.3 step 5 ACCOUNT) — see
     * {@link #getLastMissingAssets()}. {@link MissingAssetsReport#EMPTY} when
     * no deserialized project is open.
     */
    private volatile MissingAssetsReport lastMissingAssets = MissingAssetsReport.EMPTY;

    /**
     * Saved items the load of the currently open project could not honour as
     * written (Persistence Integrity book §2.4) — see
     * {@link #getLastUnreadItems()}. Empty when no deserialized project is
     * open.
     */
    private volatile List<UnreadProjectItem> lastUnreadItems = List.of();

    /**
     * The save writer's queue, oldest first, guarded by itself: captures,
     * {@link #runAfterQueuedSaves} tasks and {@link #whenSavesIdle()} waits
     * not yet taken. A capture queued while the last entry is a capture of
     * the same save generation replaces it (ruling F2).
     */
    private final ArrayDeque<WriterEntry> saveQueue = new ArrayDeque<>();

    /** Guarded by {@link #saveQueue}: a save writer thread is draining the queue. */
    private boolean saveWriterRunning;

    /**
     * Creates a project manager with the given checkpoint manager.
     *
     * @param checkpointManager the checkpoint manager to use for auto-saves
     */
    public ProjectManager(CheckpointManager checkpointManager) {
        this(checkpointManager, null);
    }

    /**
     * Creates a project manager with the given checkpoint manager and
     * recent-projects store for cross-session persistence.
     *
     * @param checkpointManager   the checkpoint manager to use for auto-saves
     * @param recentProjectsStore the store for persisting recent project paths (may be {@code null})
     */
    public ProjectManager(CheckpointManager checkpointManager, RecentProjectsStore recentProjectsStore) {
        this(checkpointManager, recentProjectsStore, new ProjectLockManager());
    }

    /**
     * Creates a project manager with explicit lock manager injection — primarily
     * for tests that simulate two sessions against the same project directory.
     *
     * @param checkpointManager   the checkpoint manager to use for auto-saves
     * @param recentProjectsStore the store for persisting recent project paths (may be {@code null})
     * @param lockManager         the project lock manager (must not be {@code null})
     */
    public ProjectManager(CheckpointManager checkpointManager,
                          RecentProjectsStore recentProjectsStore,
                          ProjectLockManager lockManager) {
        this(checkpointManager, recentProjectsStore, lockManager, new ProjectDeserializer());
    }

    /**
     * Creates a project manager with explicit deserializer injection — used
     * by tests that need to drive an alternate
     * {@link com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry}
     * through the manager.
     */
    public ProjectManager(CheckpointManager checkpointManager,
                          RecentProjectsStore recentProjectsStore,
                          ProjectLockManager lockManager,
                          ProjectDeserializer deserializer) {
        this(checkpointManager, recentProjectsStore, lockManager, deserializer, new AtomicFileWriter());
    }

    /**
     * Creates a project manager with explicit file-writer injection — used
     * by tests that fail or observe the project file's writes (story 331):
     * the head, the metadata file and the sibling backups all go through
     * {@code fileWriter}.
     *
     * @param checkpointManager   the checkpoint manager to use for auto-saves
     * @param recentProjectsStore the store for persisting recent project paths (may be {@code null})
     * @param lockManager         the project lock manager (must not be {@code null})
     * @param deserializer        the project deserializer (must not be {@code null})
     * @param fileWriter          the atomic-replace writer of every project-file write (must not be {@code null})
     */
    public ProjectManager(CheckpointManager checkpointManager,
                          RecentProjectsStore recentProjectsStore,
                          ProjectLockManager lockManager,
                          ProjectDeserializer deserializer,
                          AtomicFileWriter fileWriter) {
        this(checkpointManager, recentProjectsStore, lockManager, deserializer, fileWriter, CHECKPOINT_WAIT);
    }

    /**
     * The test seam of the save writer's bounded checkpoint wait (ruling J3):
     * as above, with how long it waits for each checkpoint it requests.
     */
    ProjectManager(CheckpointManager checkpointManager,
                   RecentProjectsStore recentProjectsStore,
                   ProjectLockManager lockManager,
                   ProjectDeserializer deserializer,
                   AtomicFileWriter fileWriter,
                   Duration checkpointWait) {
        this.checkpointManager = Objects.requireNonNull(checkpointManager,
                "checkpointManager must not be null");
        this.recentProjectsStore = recentProjectsStore;
        this.lockManager = Objects.requireNonNull(lockManager, "lockManager must not be null");
        this.deserializer = Objects.requireNonNull(deserializer, "deserializer must not be null");
        this.fileWriter = Objects.requireNonNull(fileWriter, "fileWriter must not be null");
        this.folderCopy = new ProjectFolderCopy(fileWriter);
        this.checkpointWait = Objects.requireNonNull(checkpointWait, "checkpointWait must not be null");
        // A checkpoint is written only while this session holds the project
        // lock and it is still ours (story 331): each checkpoint generation
        // checks the lease its own open or create took (story 333), so a
        // stolen lock fails the checkpoint visibly, a session that holds none
        // writes nothing, and a project still being written after a switch
        // never borrows the next project's lease.
    }

    /**
     * Sets the handler invoked when an open() call discovers another session
     * already holds the project lock. If {@code null} (the default), conflicts
     * resolve to {@link LockConflictResolution#CANCEL}.
     */
    public void setLockConflictHandler(LockConflictHandler handler) {
        this.lockConflictHandler = handler;
    }

    /**
     * Returns the project lock manager backing this {@code ProjectManager}:
     * the lease of the open project. A close retires that lease — it is
     * released once the work owed in its folder has ended (story 333; see the
     * class note, "The lock hand-off") — and gives this manager a fresh one
     * ({@link ProjectLockManager#fork()}); an open replaces it with the
     * lease its preparation took. A retired lease is never reused.
     */
    public ProjectLockManager getLockManager() {
        return lockManager;
    }

    /** Returns whether the current project was opened read-only because of a lock conflict. */
    public boolean isReadOnly() {
        return readOnly;
    }

    /**
     * Creates a new project in the specified directory.
     *
     * <p>The project folder is created under {@code parentDirectory} from the
     * sanitized name; if that directory already exists it is <em>not</em>
     * overwritten — a numeric suffix is appended ({@code "Untitled Project 2"},
     * {@code "Untitled Project 3"}, …). Two new projects created with the same
     * default name into the same workspace therefore land in distinct
     * directories instead of the second silently clobbering the first.</p>
     *
     * <p>Call it off the JavaFX application thread: it does disk I/O — the
     * folder, the metadata file (written through the {@link AtomicFileWriter};
     * creating a project is not the save path), the Recent Projects entry and
     * the lock. A project still open is closed first, as
     * {@link #closeProject()} closes it (story 333): its saves are fenced, its
     * checkpoints end, and its lease is retired — its lock released only once
     * the work owed in its folder has ended (see the class note, "The lock
     * hand-off"); that close never waits. A save of that project never writes
     * a checkpoint or a safety copy into this project's checkpoints, or any
     * but its own project's: both are bound to the checkpoint generation its
     * open started, which this call ends. The new folder's lock is taken by a
     * lease of its own.</p>
     *
     * <p>That generation ends first, before any step here that can fail
     * (story 331): if this call throws, no project's checkpoints run until a
     * project is created or opened — a save's checkpoint and safety copy are
     * refused, and so is {@link CheckpointManager#writeCheckpoint(String,
     * long, CheckpointOrigin)} — so nothing of the new project is ever written
     * into the old project's {@code checkpoints/}.</p>
     *
     * @param name             the project name
     * @param parentDirectory  the parent directory where the project folder will be created
     * @return metadata for the new project
     * @throws IOException if the project directory cannot be created
     */
    public ProjectMetadata createProject(String name, Path parentDirectory) throws IOException {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(parentDirectory, "parentDirectory must not be null");
        // The project is being replaced: its checkpoints end before anything here can fail, and its lease is
        // retired — released once the work owed in its folder has ended.
        if (currentProject != null) {
            closeProject();
        } else {
            checkpointManager.stop();
        }
        ProjectLockManager lease = lockManager;

        Path projectDir = createUniqueProjectDirectory(parentDirectory, sanitizeDirectoryName(name));
        Files.createDirectories(audioDirectory(projectDir));

        ProjectMetadata metadata = ProjectMetadata.createNew(name).withPath(projectDir);
        writeProjectFile(metadata);

        currentProject = metadata;
        currentDawProject = null;
        readOnly = false;
        lastMissingAssets = MissingAssetsReport.EMPTY;
        lastUnreadItems = List.of();
        recentProjects.put(projectDir.toString(), metadata);
        recordRecentProject(projectDir);
        // createUniqueProjectDirectory() atomically created a brand-new folder, so
        // the directory cannot already be locked — unconditionally take it.
        lease.forceAcquire(projectDir);
        lease.startHeartbeat();
        fence(SaveTarget.writable(projectDir, PendingBackup.NONE, lease, FormerRoots.NONE,
                checkpointManager.startGeneration(projectDir, lease::verifyHeld)));
        return metadata;
    }

    /**
     * Atomically creates and returns a fresh child directory under {@code parent}:
     * {@code base}, else {@code "base 2"}, {@code "base 3"}, … . Each candidate is
     * claimed with {@link Files#createDirectory(Path, java.nio.file.attribute.FileAttribute[])}
     * — which fails if the directory already exists — rather than an
     * {@link Files#exists} check followed by a separate {@code createDirectories},
     * closing the time-of-check/time-of-use gap: two processes (or threads)
     * creating a same-named project into the same workspace can never both resolve
     * the same name and have the loser silently merge into the winner's directory.
     * Creating a new project therefore never overwrites — nor co-occupies — an
     * existing project directory, an irreversible action the Project Manager design
     * book reserves for an explicit confirmation, never a silent default.
     */
    private static Path createUniqueProjectDirectory(Path parent, String base) throws IOException {
        Files.createDirectories(parent);
        int suffix = 1;
        Path candidate = parent.resolve(base);
        while (true) {
            try {
                return Files.createDirectory(candidate);
            } catch (FileAlreadyExistsException e) {
                suffix++;
                candidate = parent.resolve(base + " " + suffix);
            }
        }
    }

    /**
     * Opens an existing project from the specified directory.
     *
     * <p>If the project file contains full XML project state, it is deserialized
     * into a {@link DawProject} accessible via {@link #getCurrentDawProject()}.
     * Legacy project files that contain only metadata are also supported.</p>
     *
     * <p>The deserialized project's clips carry their source references but
     * no sample data; the caller rehydrates them ({@link ClipRehydrator}).
     * What the load found missing is classified here, without further disk
     * I/O, into {@link #getLastMissingAssets()} — next to
     * {@link #getLastMigrationReport()} — so the caller can announce the
     * open's outcome before any decode finishes. A legacy metadata-only file
     * has no clips: its report is {@link MissingAssetsReport#EMPTY}. The saved
     * items the load could not honour as written are kept in
     * {@link #getLastUnreadItems()}.</p>
     *
     * <p>The first save of a project this call migrated backs the original
     * file up as {@code project.daw.v<fromVersion>.<stamp>.bak}; the first
     * save of one whose next save would drop content its file saved
     * ({@link ProjectDeserializer#losesSavedContent()}) backs it up as
     * {@code project.daw.degraded.<stamp>.bak} — one backup per load, the
     * migration one when both apply, its path logged at INFO.</p>
     *
     * <p>A writable open starts the project's checkpoints. A read-only open
     * starts none: a session that does not hold the lock never seeds, writes
     * or prunes a project's checkpoints (story 331). Those of the project
     * open before this call end first, before any step here that can fail —
     * the lock, the read, the deserialization, the recent-projects write — so
     * if this call throws, no project's checkpoints run until a project is
     * created or opened, and nothing of another project is ever written into
     * the old project's {@code checkpoints/}. The project open before this
     * call stays open, and saveable, until the new one has parsed, been
     * recorded in Recent Projects and its lease verified (story 332); only
     * then are its saves fenced — what is already being written, a head
     * write past its claim or a checkpoint the checkpoint writer had already
     * claimed (ruling G5), finishes into that project's own folder — and its
     * lease retired: its lock is released, its file deleted if it is still
     * ours, only once the saves queued for it before this call, that head
     * write and that claimed checkpoint work have ended (story 333; see the
     * class note, "The lock hand-off"). This call never waits for them. A
     * save of that project never writes a checkpoint or a safety copy into
     * this project's checkpoints — nor into its own folder's when this call
     * opens that folder again — for both are bound, by identity, to the
     * checkpoint generation its open started, which this call ends.</p>
     *
     * @param projectDirectory the project directory
     * @return the project metadata
     * @throws IOException if the project cannot be read
     */
    public ProjectMetadata openProject(Path projectDirectory) throws IOException {
        return openProject(projectDirectory, null);
    }

    public ProjectMetadata openProject(Path projectDirectory, PreparedOpen supplied) throws IOException {
        Objects.requireNonNull(projectDirectory, "projectDirectory must not be null");
        // The project is being replaced: its checkpoints end before anything here can fail.
        CompletableFuture<Void> outgoingClaims = stopCheckpointsOf(saveTarget);

        Path projectFile = projectDirectory.resolve(PROJECT_FILE_NAME);
        if (!Files.exists(projectFile)) {
            if (supplied != null) supplied.close();
            throw new IOException("Project file not found: " + projectFile);
        }

        try (PreparedOpen prepared = supplied != null ? supplied : prepareOpen(projectDirectory)) {
            if (!prepared.directory().equals(projectDirectory)) {
                throw new IOException("Prepared open belongs to another project");
            }
            // Each step that can still fail comes first: until adopt() returned, the outgoing project is untouched.
            PreparedProject loaded = readProject(projectDirectory, projectFile);
            recordRecentProject(projectDirectory);
            ProjectLockManager accepted = prepared.adopt();
            // The fence comes before any release: the outgoing lease goes only once the work owed in its folder ended.
            CompletableFuture<Void> savesQueued = whenSavesIdle();
            CompletableFuture<Void> headIdle = fence(null);
            ProjectLockManager outgoing = lockManager;
            lockManager = accepted;
            if (outgoing != accepted) {
                retire(outgoing, CompletableFuture.allOf(savesQueued, headIdle, outgoingClaims));
            }
            currentProject = loaded.metadata();
            currentDawProject = loaded.project();
            lastMigrationReport = loaded.migration();
            lastMissingAssets = loaded.missingAssets();
            lastUnreadItems = loaded.unreadItems();
            readOnly = prepared.readOnly();
            recentProjects.put(projectDirectory.toString(), currentProject);
            fence(readOnly ? SaveTarget.readOnly(projectDirectory, loaded.backup(), accepted)
                    : SaveTarget.writable(projectDirectory, loaded.backup(), accepted, FormerRoots.NONE,
                            checkpointManager.startGeneration(projectDirectory, accepted::verifyHeld)));
            return currentProject;
        }
    }

    private PreparedProject readProject(Path projectDirectory, Path projectFile) throws IOException {
        String content = Files.readString(projectFile);
        if (content.strip().startsWith("<?xml") || content.strip().startsWith("<daw-project")) {
            // Project-relative clip references (story 323) resolve against
            // the directory the project file lives in.
            DawProject dawProject = deserializer.deserialize(content, projectDirectory);
            ProjectMetadata metadata = dawProject.getMetadata().withPath(projectDirectory);
            dawProject.setMetadata(metadata);
            dawProject.markClean();
            MigrationReport migration = deserializer.getLastMigrationReport();
            return new PreparedProject(metadata, dawProject, migration,
                    PendingBackup.at(migration, deserializer.losesSavedContent()),
                    MissingAssetsReport.atOpen(dawProject, deserializer.getMissingFiles(), projectDirectory),
                    deserializer.getUnreadItems());
        } else {
            ProjectMetadata metadata = parseProjectFile(content, projectDirectory);
            return new PreparedProject(metadata, null, MigrationReport.noOp(
                    com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry.CURRENT_VERSION),
                    PendingBackup.NONE, MissingAssetsReport.EMPTY, List.of());
        }
    }

    private record PreparedProject(ProjectMetadata metadata, DawProject project, MigrationReport migration,
                                   PendingBackup backup, MissingAssetsReport missingAssets,
                                   List<UnreadProjectItem> unreadItems) { }

    /**
     * A target lease; closing an unapplied open never touches the outgoing
     * project. Its lock check and its release read and delete the lock file:
     * they run under a {@link ReentrantLock} of its own, because the open's
     * worker may be a virtual thread (Project Manager book §8) — the lock
     * file's I/O itself still runs under the lease's monitor
     * ({@link ProjectLockManager}; an accepted tension, stage-2 review R2-N4).
     */
    public static final class PreparedOpen implements AutoCloseable {

        /** How an unapplied lease is released: at once, or once the work it inherited has ended (story 333). */
        @FunctionalInterface
        private interface Release {
            void release(ProjectLockManager lease) throws IOException;
        }

        private final Path directory;
        private final ProjectLockManager lock;
        private final boolean readOnly;
        private final Release release;
        private final ReentrantLock state = new ReentrantLock();
        private boolean consumed;

        private PreparedOpen(Path directory, ProjectLockManager lock, boolean readOnly, Release release) {
            this.directory = directory;
            this.lock = lock;
            this.readOnly = readOnly;
            this.release = release;
        }

        public Path directory() { return directory; }
        public ProjectLockManager lock() { return lock; }
        public boolean readOnly() { return readOnly; }

        /**
         * Makes this lease the open project's (story 333): verifies it is
         * still held unless read-only, and retires nothing — the open retires
         * the outgoing lease once the work owed in its folder has ended (see
         * the class note, "The lock hand-off"). A failure leaves the outgoing
         * project as it was.
         */
        private ProjectLockManager adopt() throws IOException {
            state.lock();
            try {
                if (consumed) throw new IOException("The prepared project lease has already been closed or transferred");
                if (!readOnly) lock.verifyHeld();
                consumed = true;
                return lock;
            } finally {
                state.unlock();
            }
        }

        /**
         * Makes this writable lease the open project's whatever its lock
         * check finds (story 333, the studio engineer's ruling 3 Q1): a Save
         * As copy whose lock was lost after the old project was closed is
         * still the open project — the same fact as a lock stolen one moment
         * after an adoption. Its lock is checked once ({@link ProjectLockManager#verifyHeld()},
         * which marks a lost lock {@link LockStatus#STOLEN}), and the lease is
         * consumed either way.
         *
         * @return the check's failure — a {@link LockStolenException} (the
         *         same instance) when another session holds the lock or its
         *         file is gone, another {@link IOException} when it could not
         *         be read — or empty when the lock is still ours
         * @throws IOException if the lease was already closed or adopted
         */
        private Optional<IOException> adoptReportingItsLockCheck() throws IOException {
            state.lock();
            try {
                if (consumed) throw new IOException("The prepared project lease has already been closed or transferred");
                Optional<IOException> check;
                try {
                    lock.verifyHeld();
                    check = Optional.empty();
                } catch (IOException failed) {
                    check = Optional.of(failed);
                }
                consumed = true;
                return check;
            } finally {
                state.unlock();
            }
        }

        /**
         * Releases the lease unless an open adopted it: at once — unless it
         * took this session's own lock over from a lease still being retired,
         * whose work may still write in the folder; then only once that work
         * has ended (story 333).
         */
        @Override
        public void close() throws IOException {
            state.lock();
            try {
                if (!consumed) {
                    consumed = true;
                    release.release(lock);
                }
            } finally {
                state.unlock();
            }
        }
    }

    public PreparedOpen prepareOpen(Path directory) throws IOException {
        PreparedOpen prepared = prepareLockedAccess(directory);
        try {
            recoveryGate.beforeParse(directory, prepared.readOnly(), prepared.lock());
            return prepared;
        } catch (IOException | RuntimeException | Error failure) {
            try { prepared.close(); } catch (IOException release) { failure.addSuppressed(release); }
            throw failure;
        }
    }

    /** Negotiates an independent target lease without scanning or applying recovery. */
    public PreparedOpen prepareLockedAccess(Path directory) throws IOException {
        return acquireTarget(directory, lockManager.fork());
    }

    /** Recovery failed after its lock decision: the last saved head may be inspected without changing evidence. */
    public PreparedOpen prepareReadOnlyOpen(Path directory) {
        ProjectLockManager target = lockManager.fork();
        target.openReadOnly(directory);
        return new PreparedOpen(directory, target, true, ProjectLockManager::close);
    }

    /**
     * Takes {@code target}'s lock of {@code directory}: a free folder's at
     * once; this session's own lock, still on disk because the lease that
     * holds it is being retired (story 333), by a silent hand-over — no
     * conflict prompt, and the retiring lease's release then deletes
     * nothing; another session's only as the conflict handler decides. A
     * lock file gone by the time it was read is a release that ran in
     * between, and the acquisition is tried once more.
     *
     * <p>The hand-over happens only when the retiring lease held this very
     * folder — the same path, or another name of it — never for a copy of the
     * folder that carries the retiring lease's lock file. The lease that
     * takes the lock over inherits the retiring lease's release
     * ({@link #inheritedWork}): it is itself released only once the work
     * owed in the folder has ended, whether it is adopted and closed later or
     * never adopted at all ({@link #releaseUnapplied}). A retiring lease
     * released between the read of its lock and the hand-over, or whose lock
     * another session took over meanwhile, hands nothing over, and the open
     * acquires the folder's lock as any other's — another session's lock
     * created or taken over meanwhile meets the conflict prompt and is not
     * overwritten by the hand-over; a lock file gone by the time that
     * acquisition read it is tried once more, as above.</p>
     */
    private PreparedOpen acquireTarget(Path directory, ProjectLockManager target) throws IOException {
        boolean readOnly = false;
        try {
            ProjectLockManager.AcquisitionResult result = target.tryAcquire(directory);
            if (!result.wasAcquired() && result.existingLock() == null) {
                result = target.tryAcquire(directory);
            }
            Retirement found = result.wasAcquired() || result.existingLock() == null ? null
                    : retiredLeases.get(result.existingLock().lockId());
            Retirement retiring = found != null && isTheFolderOf(found, directory) ? found : null;
            if (found != null && retiring == null) {
                LOG.fine(() -> directory + " carries the lock of this session's retiring lease of " + found.folder()
                        + ": a copy of that folder, not the folder itself");
            }
            boolean handedOver = false;
            if (retiring != null) {
                LOG.fine(() -> "This open takes over this session's own retiring lock of " + directory);
                // The obligation moves with the lock, before the lock does: a failure below still releases it late.
                inheritedWork.put(target, retiring.released());
                handedOver = retiring.lease().handOverTo(target, directory).isPresent();
                if (!handedOver) {
                    // Released or taken over since the read: the file may hold another session's lock now, which the
                    // hand-over never overwrites.
                    LOG.fine(() -> "The retiring lease of " + directory + " no longer held its lock when it could be"
                            + " handed over; the open acquires the folder's lock as any other's");
                    result = target.tryAcquire(directory);
                    if (!result.wasAcquired() && result.existingLock() == null) {
                        // The lock file vanished between its check and its read (the app fix pass 1 review, N1).
                        result = target.tryAcquire(directory);
                    }
                }
            }
            if (!handedOver && !result.wasAcquired()) {
                LockConflictResolution choice = lockConflictHandler != null && result.existingLock() != null
                        ? lockConflictHandler.resolve(result.existingLock(), result.stale()) : LockConflictResolution.CANCEL;
                switch (choice) {
                    case CANCEL -> throw new ProjectLockedException("Project is already open by " + describe(result.existingLock()),
                            result.existingLock(), result.stale());
                    case OPEN_READ_ONLY -> { target.openReadOnly(directory); readOnly = true; }
                    case TAKE_OVER -> {
                        if (found != null) {
                            // The lock is this session's retiring lease's, met under a name the file system did not
                            // match: the taken-over lock still waits for that lease's owed work before its release.
                            inheritedWork.put(target, found.released());
                        }
                        target.forceAcquire(directory);
                    }
                }
            }
            if (!readOnly) target.startHeartbeat();
            return new PreparedOpen(directory, target, readOnly, this::releaseUnapplied);
        } catch (IOException | RuntimeException | Error failure) {
            try { releaseUnapplied(target); } catch (IOException release) { failure.addSuppressed(release); }
            throw failure;
        }
    }

    /**
     * Whether {@code retiring} held {@code directory} itself: the same
     * absolute path, or — asked of the file system on the open's own thread
     * ({@link Files#isSameFile}) — another name of the same folder, such as a
     * junction, a symbolic link or a share name. Never {@link FolderFingerprint}:
     * its false "same" for a twin of the folder (a copy that kept its
     * creation time and name, on the same volume) would take the twin's lock
     * over silently. A folder whose identity cannot be read is not the folder.
     */
    private static boolean isTheFolderOf(Retirement retiring, Path directory) {
        Path held = retiring.folder().toAbsolutePath().normalize();
        Path opened = directory.toAbsolutePath().normalize();
        if (held.equals(opened)) {
            return true;
        }
        try {
            return Files.isSameFile(held, opened);
        } catch (IOException | RuntimeException unreadable) {
            LOG.log(Level.FINE, unreadable, () -> "Could not tell whether " + opened + " is " + held);
            return false;
        }
    }

    private static String describe(ProjectLock lock) {
        if (lock == null) {
            return "another session";
        }
        return lock.user() + "@" + lock.hostname() + " (pid " + lock.pid() + ")";
    }

    /**
     * Saves {@code project} to the open project's {@code project.daw} — the
     * one canonical save (story 331; see the class note, "The save"). The
     * project becomes the one this manager holds
     * ({@link #getCurrentDawProject()}).
     *
     * <p>Call it on the thread that owns the model: this call stamps the
     * metadata's modification time, reads the edit revision and captures the
     * project, and does nothing else — no disk access and no lock-file read.
     * The render, the lock check, the backup, the head write and the
     * checkpoint run on the save writer, never on the caller. The project is
     * marked clean afterwards only if no edit came since the capture (ruling
     * F1); a failed save never marks it clean.</p>
     *
     * <p>The returned future always completes, never exceptionally:
     * {@link SaveOutcome.Saved} when {@code project.daw} holds the capture —
     * a checkpoint that could not be written does not fail the save (ruling
     * G13 #5) — or {@link SaveOutcome.Failed} with the cause as thrown: a
     * read-only project (an {@link IOException} naming Save As), a capture
     * that threw, a save writer that could not start, a capture of a project
     * closed or replaced before its write ran, a lost lock
     * ({@link LockStolenException}), a failed render, backup or head write.
     * It completes on the save writer — or at once, on the caller, for a
     * read-only project or a capture that threw. When the save writer could
     * not start, the saves it strands complete on the thread that tried to
     * start it: at once on this call's thread when this call tried, and
     * otherwise on another caller's — the thread of the {@code save},
     * {@link #runAfterQueuedSaves} or {@link #whenSavesIdle()} call that
     * tried. A capture that a newer capture of the same open project
     * replaced while it waited completes with the newer one's outcome; a
     * capture of a project closed or replaced since is never replaced by the
     * next project's.</p>
     *
     * <p>The generation fence: a capture of a project closed or replaced
     * before its head write claimed its target writes nothing. One whose
     * head write had already claimed it — the last check, atomic with the
     * fence — still finishes that write into its own project's folder, under
     * that project's lock, which the close releases only once the write has
     * ended (story 333's lock hand-off), and completes
     * {@link SaveOutcome.Saved} naming no checkpoint, or one written into its
     * own folder before the switch returned. A lock found taken over while
     * its project is being closed by this session is that close, not a
     * takeover: the save fails as closed before its write.
     * A save's checkpoint and a failed save's safety copy are bound, by
     * identity, to the checkpoint generation its project's open started:
     * neither is ever written into another generation's checkpoints —
     * another project's, or its own folder's opened again — nor heard of by
     * their listeners.</p>
     *
     * <p>A dependant that a non-async method of the future adds, such as
     * {@code whenComplete}, runs on the thread that completes the future
     * (the save writer or, when the writer could not start, the thread that
     * tried to start the writer) if the dependant is added before the future
     * completes — or, as {@link CompletableFuture} allows, on another thread
     * using the future meanwhile — and otherwise on the thread that adds it,
     * as it always does when the future completes at once (see above): either
     * way it must not block, and must not wait on another save's future.</p>
     *
     * @param project the project to save
     * @return the save's outcome
     * @throws NullPointerException  if {@code project} is {@code null}
     * @throws IllegalStateException if no project is currently open
     */
    public CompletableFuture<SaveOutcome> save(DawProject project) {
        Objects.requireNonNull(project, "project must not be null");
        ProjectMetadata open = currentProject;
        SaveTarget target = saveTarget;
        if (open == null || target == null) {
            throw new IllegalStateException("No project is currently open");
        }
        if (readOnly) {
            return CompletableFuture.completedFuture(new SaveOutcome.Failed(
                    new IOException("Project was opened read-only; use Save As to a new location"),
                    Optional.empty()));
        }
        SaveJob job;
        try {
            ProjectMetadata touched = open.touch();
            currentProject = touched;
            project.setMetadata(touched);
            currentDawProject = project;
            long revision = project.getEditRevision();
            CheckpointManager.Capture capture = checkpointManager.captureStarted(revision);
            job = new SaveJob(target, project, touched, capture, serializer.capture(project, target.formerRoots()));
        } catch (Throwable notCaptured) {
            LOG.log(Level.WARNING, notCaptured, () -> "The project in " + target.projectDirectory()
                    + " could not be captured for its save; nothing was written");
            return CompletableFuture.completedFuture(new SaveOutcome.Failed(notCaptured, Optional.empty()));
        }
        CompletableFuture<SaveOutcome> outcome = new CompletableFuture<>();
        job.waiters.add(outcome);
        submit(job);
        return outcome;
    }

    /**
     * Runs {@code task} on the save writer once every save queued before this
     * call has finished — its head write and the checkpoint after it, or that
     * checkpoint's bounded wait (ruling J3) — and before any save queued
     * after it (story 331). A named snapshot copies {@code project.daw}
     * through it, so the copy never races a head write.
     *
     * <p>The future completes on the save writer with what {@code task}
     * returns, or exceptionally with what it throws (the same instance). The
     * task runs whether or not a project is open; it must not block on a
     * save's future, nor on a stage of this manager's that waits for the save
     * writer ({@link #whenSavesIdle()}, {@link #whenWritersIdle()}, a
     * close's release).</p>
     *
     * @param task the task
     * @param <T>  what it returns
     * @return the task's result
     */
    public <T> CompletableFuture<T> runAfterQueuedSaves(Callable<T> task) {
        QueuedTask<T> queued = new QueuedTask<>(Objects.requireNonNull(task, "task must not be null"),
                new CompletableFuture<>());
        submit(queued);
        return queued.result();
    }

    /**
     * Completes on the save writer once every save queued before this call
     * has finished — the write in progress, the capture waiting behind it,
     * and that capture's checkpoint or the checkpoint's bounded wait
     * (rulings G1, J3) — so a door can re-judge the project as it is after
     * the save. A save queued after this call is not waited for. It never
     * completes exceptionally. When the save writer could not start, the
     * saves queued before it complete {@link SaveOutcome.Failed} and it
     * completes on the thread that tried to start the writer: at once on
     * this call's thread when this call tried, and otherwise on another
     * caller's — the thread of the {@link #save(DawProject)},
     * {@link #runAfterQueuedSaves} or {@code whenSavesIdle} call that tried.
     *
     * @return completes when the saves queued before the call are done
     */
    public CompletableFuture<Void> whenSavesIdle() {
        IdleWait wait = new IdleWait(new CompletableFuture<>());
        submit(wait);
        return wait.idle();
    }

    /**
     * Completes once both writers are idle (story 333; ruling M1's wait,
     * without a thread waiting for it): first every save queued before this
     * call ({@link #whenSavesIdle()}), then — from then on — until the
     * checkpoint writers hold no job of any generation, of any folder
     * ({@link CheckpointManager#whenNoJobIsHeld()}), a checkpoint whose
     * generation already ended included. It never blocks the caller and
     * never completes exceptionally; it cancels nothing. It completes on the
     * save writer or a checkpoint writer: attach dependants with an async
     * method.
     *
     * @return completes when the saves queued before the call and every checkpoint job are done
     */
    public CompletableFuture<Void> whenWritersIdle() {
        return whenSavesIdle().handle((_, _) -> null).thenCompose(_ -> checkpointManager.whenNoJobIsHeld());
    }

    /**
     * Completes once the checkpoint generation of the open writable project
     * holds no job, queued or running (story 333: the exit captures its
     * closing checkpoint only once that generation is idle) — at once when
     * no writable project is open, or its generation holds none. It never
     * completes exceptionally, and it completes on a checkpoint writer or on
     * the caller: attach dependants with an async method.
     *
     * @return completes when the open generation holds no job
     */
    public CompletableFuture<Void> whenOpenGenerationIdle() {
        SaveTarget target = saveTarget;
        Optional<CheckpointManager.Generation> generation = target == null ? Optional.empty() : target.checkpoints();
        return generation.map(checkpointManager::whenGenerationIdle)
                .orElseGet(() -> CompletableFuture.completedFuture(null));
    }

    /**
     * Reads the open project's lock file, off the caller, on a
     * {@code daw-lock-check} virtual thread (story 333: the exit's check that
     * nothing it is about to write — a closing checkpoint, a seal — goes into
     * a folder another session took over). It completes with the
     * {@link LockStolenException} when another session holds the lock or its
     * file is gone, and empty when the lock is still ours, when nothing is
     * held (no project open, or a read-only open), or when the file cannot be
     * read — logged at {@code WARNING}. It never completes exceptionally.
     *
     * @return the steal, if the check found one
     */
    public CompletableFuture<Optional<LockStolenException>> whenLockChecked() {
        ProjectLockManager lease = lockManager;
        CompletableFuture<Optional<LockStolenException>> checked = new CompletableFuture<>();
        Runnable check = () -> {
            try {
                lease.verifyOurs();
                checked.complete(Optional.empty());
            } catch (LockStolenException stolen) {
                checked.complete(Optional.of(stolen));
            } catch (IOException | RuntimeException unreadable) {
                LOG.log(Level.WARNING, unreadable, () -> "The project lock could not be checked; it is taken as"
                        + " still ours");
                checked.complete(Optional.empty());
            }
        };
        try {
            Thread.ofVirtual().name("daw-lock-check").start(check);
        } catch (Throwable notStarted) {
            LOG.log(Level.WARNING, notStarted, () -> "The project lock check could not start; it is taken as"
                    + " still ours");
            checked.complete(Optional.empty());
        }
        return checked;
    }

    /**
     * Requests the exit's closing checkpoint of {@code project} (story 333,
     * ruling R3; {@link CheckpointOrigin#CLOSING}) in the checkpoint
     * generation of the open writable project. Call it on the thread that
     * owns the model: this call reads the edit revision, takes the journal's
     * capture boundary and captures the project — no disk access; the
     * render and the write run on the writer of the project's folder. It is
     * written even when its revision is already checkpointed and when
     * autosave is disabled, and its generation's write guard refuses it under
     * a stolen lock.
     *
     * <p>The future completes with the checkpoint written, or exceptionally:
     * with an {@link IOException} at once when no writable project is open —
     * none, a read-only open, after {@link #closeProject()} — or when its
     * generation has ended; with what a capture that threw threw; with what
     * the guard, the render or the write failed with.</p>
     *
     * @param project the live project to capture
     * @return the closing checkpoint
     */
    public CompletableFuture<Path> writeClosingCheckpoint(DawProject project) {
        Objects.requireNonNull(project, "project must not be null");
        SaveTarget target = saveTarget;
        Optional<CheckpointManager.Generation> generation = target == null ? Optional.empty() : target.checkpoints();
        if (generation.isEmpty()) {
            return CompletableFuture.failedFuture(new IOException(
                    "No writable project is open: no closing checkpoint is written"));
        }
        CheckpointManager.Capture capture;
        ProjectDocument document;
        try {
            capture = checkpointManager.captureStarted(project.getEditRevision());
            document = serializer.capture(project, target.formerRoots());
        } catch (Throwable notCaptured) {
            LOG.log(Level.WARNING, notCaptured, () -> "The project in " + target.projectDirectory()
                    + " could not be captured for its closing checkpoint; nothing was written");
            return CompletableFuture.failedFuture(notCaptured);
        }
        return checkpointManager.writeCheckpoint(generation.get(), document::toXml, capture,
                CheckpointOrigin.CLOSING);
    }

    /**
     * Whether the checkpoint generation of the open writable project has
     * {@code project}'s edit revision checkpointed — written, or matched at
     * its first tick (story 333: the exit's closing-checkpoint trigger (ii)).
     * Call it on the thread that owns the model. {@code false} when no
     * writable project is open. No disk access.
     *
     * @param project the live project
     * @return whether its revision is checkpointed
     */
    public boolean isCheckpointed(DawProject project) {
        Objects.requireNonNull(project, "project must not be null");
        SaveTarget target = saveTarget;
        Optional<CheckpointManager.Generation> generation = target == null ? Optional.empty() : target.checkpoints();
        return generation.isPresent() && checkpointManager.isCheckpointed(generation.get(), project.getEditRevision());
    }

    /**
     * Captures {@code project} as the open project's own checkpoints capture
     * it (story 333, ENG-S10): a document relativised against its folder and
     * — when it is a published Save As copy — written with its former roots,
     * so a reference into the folder the copy was taken from whose file the
     * copy holds names the copy's file, as the copy's saves write it. The
     * application's periodic checkpoint and its Create Checkpoint capture
     * through this, never through {@link ProjectSerializer#capture(DawProject)}
     * alone, so a checkpoint of a copy never depends on the original folder.
     * Call it on the thread that owns the model; no disk access. It captures
     * with no former roots when no project is open, or when {@code project}
     * is not yet re-rooted at the open target's folder.
     *
     * @param project the live project
     * @return the captured document, confined to one thread at a time
     * @throws IOException if no XML document can be created
     */
    public ProjectDocument captureOpenProject(DawProject project) throws IOException {
        Objects.requireNonNull(project, "project must not be null");
        return serializer.capture(project, formerRootsFor(project));
    }

    /** The open target's former roots when {@code project} is rooted at the target's folder; otherwise none. */
    private FormerRoots formerRootsFor(DawProject project) {
        SaveTarget target = saveTarget;
        ProjectMetadata live = project.getMetadata();
        Path folder = live == null ? null : live.projectPath();
        return target != null && folder != null && target.projectDirectory().equals(folder)
                ? target.formerRoots() : FormerRoots.NONE;
    }

    // ── Save As (story 333, rulings R5, R6) ────────────────────────────────

    /**
     * Captures {@code project} for a Save As named {@code newName} (story
     * 333, ruling R5; Persistence Integrity book §2.3, §6.1). Call it on the
     * thread that owns the model: it reads the edit revision and walks the
     * model into a document — no disk access. The document carries
     * {@code newName} and the capture's time in its metadata. The copy's
     * {@code project.daw} is rendered from it against the copy's folder
     * ({@link #publishCopy}): a clip reference under the folder the project
     * was opened from — or under one of its former roots — is written
     * project-relative there only when the copy holds its file, and stays
     * absolute, still naming the original's file, when it does not. The live
     * project is not changed: not renamed, not re-pointed, its metadata and
     * dirty state as they were.
     *
     * @param project the project to copy, as it is now
     * @param newName the copy's name: its folder's name, already sanitised
     *                ({@link #sanitizeDirectoryName})
     * @return the capture, to be handed to one worker
     * @throws IOException              if no project document can be created
     * @throws IllegalArgumentException if {@code newName} is blank
     */
    public SaveAsCapture captureForSaveAs(DawProject project, String newName) throws IOException {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(newName, "newName must not be null");
        if (newName.isBlank()) {
            throw new IllegalArgumentException("newName must not be blank");
        }
        ProjectMetadata live = project.getMetadata();
        Optional<Path> sourceFolder = Optional.ofNullable(live == null ? null : live.projectPath());
        FormerRoots formerRoots = formerRootsFor(project);
        Instant now = Instant.now();
        ProjectMetadata metadata = new ProjectMetadata(newName, live == null ? now : live.createdAt(), now, null);
        long revision = project.getEditRevision();
        ProjectDocument document = serializer.capture(project, formerRoots)
                .withProjectName(newName)
                .withLastModified(now);
        return new SaveAsCapture(newName, revision, sourceFolder, formerRoots, metadata, document);
    }

    /**
     * Plans the Save As of {@code capture} into the folder {@code target}
     * (story 333, rulings R5, R6), on a worker — it reads the source folder,
     * renders the document once to measure it, and asks the target's volume:
     * {@link SaveAsPlan.Ready} with every file the copy takes; or a refusal —
     * {@link SaveAsPlan.TargetInsideProject} for the open project's folder,
     * a folder inside it, or another name of either;
     * {@link SaveAsPlan.TargetTaken} for an existing target that is not an
     * empty folder; {@link SaveAsPlan.NotEnoughSpace} when the volume's usable
     * space is less than what the copy needs there. What is copied and what
     * is left out: {@code audio/} and {@code assets/} whole but for a take
     * still streaming and temp or {@code .part} files, every other file under
     * the folder the document references, {@code snapshots/} — never
     * {@code checkpoints/}, {@code journal/}, {@code sessions/}, the lock or
     * the head's backups. A source folder that cannot be read — its volume
     * gone, say — or an entry of it that cannot is logged at
     * {@code WARNING} and leaves what it holds out of the copy, its referenced
     * files counted as missing: the copy then holds {@code project.daw}
     * alone, which keeps the session's work (story 333, the studio engineer's
     * ruling B1 on stage 3). Never on the JavaFX application thread.
     *
     * <p>{@code cancelled} is asked before each folder and file the plan
     * reads and before the render; once it answers {@code true} the plan
     * stops, having written nothing, and throws
     * {@link java.util.concurrent.CancellationException}.</p>
     *
     * @param capture   what {@link #captureForSaveAs} captured
     * @param target    the copy's folder: a name not taken, or an empty folder, named {@code capture.name()}
     * @param cancelled whether the user cancelled
     * @return the plan
     * @throws IOException                                   if the target's volume cannot be asked
     * @throws java.util.concurrent.CancellationException    if {@code cancelled} answered {@code true}
     * @throws IllegalArgumentException                      if {@code target}'s name is not the capture's name
     */
    public SaveAsPlan planSaveAs(SaveAsCapture capture, Path target, BooleanSupplier cancelled) throws IOException {
        return folderCopy.plan(capture, target, cancelled);
    }

    /**
     * Makes the copy {@code plan} describes, on the calling worker, blocking
     * until it is published (story 333, ruling R5): each file is copied
     * through the {@link AtomicFileWriter} — forced, its size checked against
     * its source's — then {@code project.daw} is written last, and a staged
     * copy is published by one rename, so the target never holds half a
     * project. It opens nothing: no lock, journal, checkpoints or session
     * (see {@link #adoptPublishedCopy}), and records nothing in Recent
     * Projects ({@link #rememberRecentProject}). The open project and its
     * folder are only read.
     *
     * <p>{@code cancelled} is asked before each file, before
     * {@link SaveAsProgress#writingHead()} and once more right after it; from
     * then on the copy can no longer be cancelled and finishes. A cancel or a failure
     * deletes exactly what this copy created and nothing else. Never throws,
     * never on the JavaFX application thread.</p>
     *
     * @param plan      a {@link SaveAsPlan.Ready} plan
     * @param progress  told of each file and of the head write, on this worker
     * @param cancelled whether the user cancelled
     * @return {@link SaveAsOutcome.Published}, {@link SaveAsOutcome.Cancelled} or {@link SaveAsOutcome.Failed}
     */
    public SaveAsOutcome publishCopy(SaveAsPlan.Ready plan, SaveAsProgress progress, BooleanSupplier cancelled) {
        return folderCopy.copy(plan, progress, cancelled);
    }

    /**
     * Makes the published copy the open project (story 333, ruling R5), on a
     * worker: {@code lease} — the copy's lock, taken by
     * {@link #prepareLockedAccess} after the publish — becomes this manager's
     * lease, {@code project} the open project, and the copy's folder its save
     * target, with its checkpoints started and the folders it was copied
     * from as its former roots (its saves write a reference into them
     * project-relative when the copy holds its file). Nothing is saved — the
     * copy's {@code project.daw} is the head — and nothing in the model is
     * changed: the caller renames, re-points
     * ({@link ProjectAssetReferences#rebase}, with the files the copy holds)
     * and marks the live project clean on its owner thread. The copy is
     * recorded in Recent Projects; a failure to record it is logged and the
     * adoption stands. Its missing-assets report is
     * {@link MissingAssetsReport#EMPTY} until the next open: an adoption
     * loads nothing (the copy's missing files are
     * {@link SaveAsOutcome.Published#missingFiles()}).
     *
     * <p>No project may be open: close the one the copy was taken from first
     * ({@link #closeProject()}). <strong>Check the lease before that close</strong>,
     * on the worker, right after {@link #prepareLockedAccess}: a read-only
     * lease ({@link PreparedOpen#readOnly()} — another session took the copy
     * between its publish and its lock) or one that no longer holds the lock
     * ({@code lease.lock().verifyHeld()}) is the Save As's lock failure, and
     * the open project must then stay open and unchanged (story 333,
     * ENG-S5). A lock lost after that check — between the old project's
     * close and this call — is no refusal (story 333, the studio engineer's
     * ruling 3 Q1): the copy is still installed, as on success, under a lease
     * whose lock another session holds, exactly as if the lock had been
     * stolen a moment after the adoption — its saves fail with
     * {@link LockStolenException}, its checkpoints are refused by their
     * guard, its heartbeat and release write and delete nothing of the other
     * session's lock. The result reports what the lock check found. This
     * method's own refusals are contract breaches the check before the close
     * prevents — a project still open, a lease of another folder or a
     * read-only one, which are fixed when the lease is prepared: they install
     * nothing and leave {@code lease} to the caller, who closes it.</p>
     *
     * @param published the published copy
     * @param lease     the copy's lock, prepared for {@code published.folder()}
     * @param project   the live project the copy was taken from
     * @return the open project's metadata — the copy's — and what the copy's
     *         lock check found
     * @throws IOException           if {@code lease} is of another folder, read-only, or already consumed
     * @throws IllegalStateException if a project is open
     */
    public AdoptedCopy adoptPublishedCopy(SaveAsOutcome.Published published, PreparedOpen lease,
                                          DawProject project) throws IOException {
        Objects.requireNonNull(published, "published must not be null");
        Objects.requireNonNull(lease, "lease must not be null");
        Objects.requireNonNull(project, "project must not be null");
        if (currentProject != null) {
            throw new IllegalStateException("A project is still open: close it before the published copy is"
                    + " adopted");
        }
        Path folder = published.folder();
        if (!lease.directory().equals(folder)) {
            throw new IOException("The prepared lease belongs to " + lease.directory() + ", not to the published"
                    + " copy in " + folder);
        }
        if (lease.readOnly()) {
            throw new IOException("The published copy in " + folder + " could not be locked: another session"
                    + " holds it");
        }
        Optional<IOException> lockCheck = lease.adoptReportingItsLockCheck();
        ProjectLockManager accepted = lease.lock();
        ProjectLockManager outgoing = lockManager;
        lockManager = accepted;
        if (outgoing != accepted) {
            retire(outgoing, CompletableFuture.completedFuture(null));
        }
        ProjectMetadata metadata = published.metadata();
        currentProject = metadata;
        currentDawProject = project;
        readOnly = false;
        lastMigrationReport = MigrationReport.noOp(
                com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry.CURRENT_VERSION);
        lastMissingAssets = MissingAssetsReport.EMPTY;
        lastUnreadItems = List.of();
        recentProjects.put(folder.toString(), metadata);
        fence(SaveTarget.writable(folder, PendingBackup.NONE, accepted, published.formerRoots(),
                checkpointManager.startGeneration(folder, accepted::verifyHeld)));
        try {
            rememberRecentProject(folder);
        } catch (RuntimeException notRecorded) {
            LOG.log(Level.WARNING, notRecorded, () -> "The copy in " + folder + " is open but could not be"
                    + " recorded in Recent Projects");
        }
        lockCheck.ifPresent(failure -> LOG.log(Level.WARNING, failure, () -> "The copy in " + folder + " is open,"
                + " but its lock " + (failure instanceof LockStolenException ? "was lost" : "could not be checked")
                + " before it was adopted"));
        return new AdoptedCopy(metadata, lockCheck);
    }

    /**
     * A Save As copy made the open project ({@link #adoptPublishedCopy};
     * story 333, the studio engineer's ruling 3 Q1): its metadata, and what
     * its lock check found when it was adopted.
     *
     * @param metadata         the open project's metadata — the copy's
     * @param lockCheckFailure empty when the copy's lock was still ours; a
     *                         {@link LockStolenException} — the same instance
     *                         the check threw, its holder in
     *                         {@link LockStolenException#currentHolder()} —
     *                         when another session took it or its file is
     *                         gone; another {@link IOException} when the lock
     *                         file could not be read
     */
    public record AdoptedCopy(ProjectMetadata metadata, Optional<IOException> lockCheckFailure) {
        public AdoptedCopy {
            Objects.requireNonNull(metadata, "metadata must not be null");
            Objects.requireNonNull(lockCheckFailure, "lockCheckFailure must not be null");
        }
    }

    /**
     * Records {@code projectDirectory} in Recent Projects (story 333: a Save
     * As copy published at exit is not opened, only remembered). It persists
     * the list — a {@link java.util.prefs.Preferences} write — so call it on
     * a worker, never on the JavaFX application thread. Does nothing without
     * a {@link RecentProjectsStore}.
     *
     * @param projectDirectory the project folder
     */
    public void rememberRecentProject(Path projectDirectory) {
        recordRecentProject(Objects.requireNonNull(projectDirectory, "projectDirectory must not be null"));
    }

    /**
     * Waits, as the application exits, for the saves and every checkpoint job
     * already handed to the writers (story 331, ruling M1): the save writer
     * and the checkpoint writers are daemon virtual threads, which the JVM
     * would otherwise end mid-write.
     * The application calls it from its one JVM shutdown hook, off the JavaFX
     * application thread; it does nothing else there — it saves nothing,
     * stops no checkpoints, releases no lock and posts nothing.
     *
     * <p>Under one deadline, thirty seconds from its start: first
     * {@link #whenSavesIdle()} — the save being written, the capture waiting
     * behind it, a named-snapshot copy queued among them, and each save's
     * checkpoint or safety copy, as long as the save writer waits for it
     * (ruling J3); then, with the time left, every job the checkpoint writers
     * hold, of any origin, any generation and any folder, until they hold
     * none. A write
     * asked for after its half began may be waited for too, within the same
     * deadline. It completes, cancels and interrupts nothing.</p>
     *
     * <p>It logs at {@code INFO} that it waits only if either writer held a
     * write when it started; with none, it returns at once. When the deadline
     * runs out it logs at {@code WARNING} and returns {@code false}: the JVM
     * then ends the writers, and {@code project.daw} holds the last save that
     * finished, whole (the atomic replace). Both lines are best effort. The
     * JDK's {@code LogManager} resets every logging handler from a shutdown
     * hook of its own, which runs alongside this one from the start of the
     * exit. With the JDK's default {@code LogManager}, the {@code WARNING},
     * logged at the deadline, therefore reaches no handler, and the
     * {@code INFO} usually does not either (story 331, ruling N1). A log sink
     * that defers that reset until this method returns receives both (story
     * 336).</p>
     *
     * @return whether every write it waited for finished before the deadline
     */
    public boolean awaitWritesBeforeExit() {
        return awaitWritesBeforeExit(EXIT_WRITE_WAIT);
    }

    /** The test seam of {@link #awaitWritesBeforeExit()}: as it, with the deadline {@code bound} from its start. */
    boolean awaitWritesBeforeExit(Duration bound) {
        long deadline = System.nanoTime() + bound.toNanos();
        boolean savesPending;
        synchronized (saveQueue) {
            savesPending = saveWriterRunning;
        }
        if (!savesPending && !checkpointManager.holdsAJob()) {
            return true;
        }
        LOG.info(() -> "Waiting up to " + boundText(bound) + " for the saves and checkpoints still being written"
                + " before the application exits");
        boolean finished;
        try {
            finished = (!savesPending || savesIdleBefore(deadline)) && checkpointManager.awaitNoJobUntil(deadline);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOG.warning(() -> "The wait for the saves and checkpoints still being written was interrupted; the"
                    + " application exits without them, and project.daw holds the last save that finished");
            return false;
        }
        if (!finished) {
            LOG.warning(() -> "Saves or checkpoints were still being written " + boundText(bound) + " after the"
                    + " application began to exit; the application exits without them, and project.daw holds the"
                    + " last save that finished");
        }
        return finished;
    }

    /** Whether every save queued so far finished before {@code deadline} ({@link System#nanoTime()}). */
    private boolean savesIdleBefore(long deadline) throws InterruptedException {
        try {
            whenSavesIdle().get(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            return true;
        } catch (TimeoutException stillWriting) {
            return false;
        } catch (ExecutionException neverExceptional) {
            // whenSavesIdle() never completes exceptionally; were it to, the saves would be done.
            return true;
        }
    }

    /**
     * Closes the open project without saving (story 333, ruling R9: the
     * exit's FLUSH is the save; RELEASE never saves): its saves are fenced —
     * a capture queued for it writes nothing, a head write that already
     * claimed its target finishes into its folder — its checkpoints stop,
     * never waiting, and its lease is retired: its lock file is deleted, if it
     * is still ours, only once the saves queued for it before this call, a
     * head write already past its claim, and the checkpoint writer's claimed
     * work in its folder have ended (story 333's lock hand-off; see the class
     * note, "The lock hand-off"). No save of it writes a checkpoint or a
     * safety copy into another generation's checkpoints, the same folder
     * opened again included.
     *
     * <p>It returns at once, on any thread — the JavaFX application thread,
     * the save writer (a {@link #runAfterQueuedSaves} task) or a worker — and
     * the returned stage completes, never exceptionally, when that lock
     * release has run, on a {@code daw-lease-retire} thread — except when no
     * thread can be started ({@link #startRetirement}): attach dependants
     * with an async method. A lost lock is never swallowed here:
     * nothing is saved, so there is nothing to skip. With no project open it
     * returns {@link #whenRetiredLeasesReleased()}.</p>
     *
     * @return completes when the closed project's lock release has run
     */
    public CompletableFuture<Void> closeProject() {
        return closeProject(CompletableFuture.completedFuture(null));
    }

    /**
     * {@link #closeProject()}, whose lock release also waits for
     * {@code alsoOwed}: work the caller still has under way in the project's
     * folder, such as the end of its journal — a seal or a drain still
     * writing into {@code journal/} (story 333, ruling OQ-1 #3) — so the lock
     * is never released while that work may still write there. If it never
     * completes, the lock stays until the application ends, as after a crash;
     * an exceptional completion counts as ended.
     *
     * @param alsoOwed the caller's work still writing into the folder
     * @return completes when the closed project's lock release has run
     */
    public CompletableFuture<Void> closeProject(CompletionStage<?> alsoOwed) {
        Objects.requireNonNull(alsoOwed, "alsoOwed must not be null");
        // Adapted before anything changes: a stage that does not interoperate can never leave a half-closed project.
        CompletableFuture<Void> callersWork = new CompletableFuture<>();
        alsoOwed.whenComplete((_, _) -> callersWork.complete(null));
        if (currentProject == null) {
            return whenRetiredLeasesReleased();
        }
        // A snapshot of the saves queued before the close — a named-snapshot copy among them — then the fence.
        SaveTarget closing = saveTarget;
        CompletableFuture<Void> savesQueued = whenSavesIdle();
        CompletableFuture<Void> headIdle = fence(null);
        CompletableFuture<Void> checkpointClaims = stopCheckpointsOf(closing);
        ProjectLockManager outgoing = lockManager;
        // A retired lease instance is never reused.
        lockManager = outgoing.fork();
        CompletableFuture<Void> released = retire(outgoing, CompletableFuture.allOf(savesQueued, headIdle,
                checkpointClaims, callersWork));
        currentProject = null;
        currentDawProject = null;
        readOnly = false;
        lastMigrationReport = MigrationReport.noOp(
                com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry.CURRENT_VERSION);
        lastMissingAssets = MissingAssetsReport.EMPTY;
        lastUnreadItems = List.of();
        return released;
    }

    /**
     * Completes once every lease this manager retired so far has run its
     * release (story 333): each lock file deleted if it was still ours, each
     * heartbeat stopped. A lease is released only once the work owed in its
     * folder has ended — however long that takes (see the class note, "The
     * lock hand-off"). It never completes exceptionally; it completes on a
     * {@code daw-lease-retire} thread (or the thread a release ran on when none
     * could be started, {@link #startRetirement}), or at once when every
     * release has run.
     *
     * @return completes when the releases retired before this call have run
     */
    public CompletableFuture<Void> whenRetiredLeasesReleased() {
        return CompletableFuture.allOf(retirements.toArray(CompletableFuture[]::new));
    }

    /**
     * Ends the running checkpoint generation ({@link CheckpointManager#stop()},
     * which never waits) and returns the checkpoint work still owed in the
     * folder of {@code closing}: its generation's claimed work — none for a
     * target without one (a read-only open, or nothing open), whose lease
     * then never waits for another folder's claimed write.
     */
    private CompletableFuture<Void> stopCheckpointsOf(SaveTarget closing) {
        CompletableFuture<Void> claims = checkpointManager.stop();
        return closing != null && closing.checkpoints().isPresent() ? claims
                : CompletableFuture.completedFuture(null);
    }

    /**
     * Retires {@code lease}: releases it — its lock file deleted if it is
     * still ours, its heartbeat stopped, its scheduler shut down — once
     * {@code owedWork} has completed, normally or not, so nothing this
     * session still owes the folder writes or deletes there after its lock is
     * gone (story 333). The release runs on a {@code daw-lease-retire}
     * virtual thread — unless none can be started ({@link #startRetirement})
     * — never on the caller — the JavaFX application thread in the
     * application — and never on the save writer or a checkpoint writer that
     * ends the owed work. The lease keeps its heartbeat until then, and a
     * lease that holds a lock is remembered by its lock's id and its folder,
     * so a reopen of that folder takes the lock over ({@link #acquireTarget}).
     * A lease that took its lock over from a retiring lease inherited that
     * lease's release ({@link #inheritedWork}): its own release waits for it
     * as well. A release that fails is logged; nothing is thrown.
     *
     * @return completes, never exceptionally, when the release has run
     */
    private CompletableFuture<Void> retire(ProjectLockManager lease, CompletableFuture<Void> owedWork) {
        CompletableFuture<Void> inherited = inheritedWork.remove(lease);
        CompletableFuture<Void> owed = inherited == null ? owedWork : CompletableFuture.allOf(owedWork, inherited);
        // The release stage exists before the lease is findable by its lock: a reopen that finds it inherits it.
        CompletableFuture<Void> released = new CompletableFuture<>();
        Optional<ProjectLock> held = lease.currentLock();
        Optional<Path> folder = lease.boundDirectory();
        if (held.isPresent() && folder.isPresent()) {
            retiredLeases.put(held.get().lockId(), new Retirement(lease, folder.get(), released));
        }
        if (!owed.isDone()) {
            LOG.fine(() -> nameOf(lease) + " is released once the work owed in its folder has ended");
        }
        retirements.add(released);
        released.whenComplete((_, _) -> retirements.remove(released));
        owed.handle((_, _) -> null)
                .thenRunAsync(() -> release(lease), RETIRE_EXECUTOR)
                .whenComplete((_, notReleased) -> {
                    if (notReleased != null) {
                        LOG.log(Level.WARNING, notReleased, () -> nameOf(lease) + " could not be released");
                    }
                    released.complete(null);
                });
        return released;
    }

    /**
     * Releases a lease the open it was prepared for never adopted (a failed
     * open, a cancelled recovery, a lock taken read-only after all): at once,
     * on the caller, as {@link ProjectLockManager#close()} does — unless it
     * took this session's own lock over from a retiring lease, whose work may
     * still write in the folder; then it is retired, and released only once
     * that work has ended (story 333).
     */
    private void releaseUnapplied(ProjectLockManager lease) throws IOException {
        if (inheritedWork.containsKey(lease)) {
            retire(lease, CompletableFuture.completedFuture(null));
        } else {
            lease.close();
        }
    }

    private static void release(ProjectLockManager lease) {
        String name = nameOf(lease);
        try {
            lease.close();
        } catch (Throwable failure) {
            LOG.log(Level.WARNING, failure, () -> name + " could not be released");
        }
    }

    /** How the log names a lease: by the id of the lock it holds. */
    private static String nameOf(ProjectLockManager lease) {
        return lease.currentLock().map(lock -> "The project lock " + lock.lockId()).orElse("A project lease");
    }

    /**
     * Returns the migration report produced when the currently open
     * project was loaded. Always non-null; if no project is open, or no
     * migrations were applied, {@link MigrationReport#wasMigrated()}
     * returns {@code false}.
     *
     * <p>The UI layer consults this report to decide whether to show
     * the {@code MigrationReportDialog} after a load.</p>
     */
    public MigrationReport getLastMigrationReport() {
        return lastMigrationReport;
    }

    /**
     * Returns the missing-assets report built when the currently open
     * project was loaded by {@link #openProject(Path)}: the clip sources the
     * load found missing or unresolvable, one row per asset, plus the missing
     * reference-track files for the log. Always non-null;
     * {@link MissingAssetsReport#EMPTY} when no deserialized project is open
     * — after {@link #createProject}, {@link #closeProject()}, a legacy
     * metadata-only open, and {@link #adoptPublishedCopy}, which loads
     * nothing.
     *
     * <p>The UI layer consults it to qualify the open announcement and to
     * present the missing-assets report; the clip rehydrator skips the
     * sources it names. Rows the rehydration adds later (a file that does
     * not decode, …) are the caller's to track: this report is the open's.</p>
     *
     * @return the open's missing-assets report
     */
    public MissingAssetsReport getLastMissingAssets() {
        return lastMissingAssets;
    }

    /**
     * Returns the saved items the load of the currently open project could
     * not honour as written ({@link ProjectDeserializer#getUnreadItems()} of
     * the {@link #openProject(Path)} that loaded it): skipped MIDI notes, CC
     * lanes and CC events, adjusted arpeggiator settings, MIDI effects kept
     * as placeholders, and removed mixer references to tracks the file does
     * not contain. Always non-null; empty when the open honoured everything
     * and when no deserialized project is open — after {@link #createProject},
     * {@link #closeProject()}, a legacy metadata-only open, and
     * {@link #adoptPublishedCopy}, which loads nothing.
     *
     * <p>The UI layer consults it, next to {@link #getLastMissingAssets()},
     * to qualify the open announcement.</p>
     *
     * @return an unmodifiable list of the open's unread items
     */
    public List<UnreadProjectItem> getLastUnreadItems() {
        return lastUnreadItems;
    }

    /**
     * Returns the metadata for the currently open project, or {@code null}.
     * Safe to read from any thread.
     *
     * @return the current project metadata
     */
    public ProjectMetadata getCurrentProject() {
        return currentProject;
    }

    /**
     * Returns the currently loaded {@link DawProject}, or {@code null} if
     * only metadata is available (e.g., a legacy project file was opened).
     *
     * @return the current DAW project, or {@code null}
     */
    public DawProject getCurrentDawProject() {
        return currentDawProject;
    }

    /**
     * Returns whether the current project has unsaved changes.
     *
     * @return {@code true} if there are unsaved changes
     */
    public boolean hasUnsavedChanges() {
        DawProject open = currentDawProject;
        if (open != null) {
            return open.isDirty();
        }
        return false;
    }

    /**
     * Returns a map of recent project paths to their metadata: a copy, taken
     * under the map's lock, so iterating it never races a worker's write.
     *
     * @return an unmodifiable snapshot of the recent projects, in the order they were recorded
     */
    public Map<String, ProjectMetadata> getRecentProjects() {
        synchronized (recentProjects) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(recentProjects));
        }
    }

    /**
     * Returns the checkpoint manager.
     *
     * @return the checkpoint manager
     */
    public CheckpointManager getCheckpointManager() {
        return checkpointManager;
    }

    /**
     * Returns the recent-projects store, or {@code null} if none was configured.
     *
     * @return the recent-projects store
     */
    public RecentProjectsStore getRecentProjectsStore() {
        return recentProjectsStore;
    }

    /**
     * Returns the list of recently opened project paths (most recent first),
     * or an empty list if no {@link RecentProjectsStore} was configured.
     *
     * @return list of recent project paths
     */
    public List<Path> getRecentProjectPaths() {
        if (recentProjectsStore == null) {
            return List.of();
        }
        return recentProjectsStore.getRecentProjectPaths();
    }

    private void recordRecentProject(Path projectDir) {
        if (recentProjectsStore != null) {
            recentProjectsStore.addRecentProject(projectDir);
        }
    }

    // ── The fence and the head-write claim (story 333) ──────────────────────

    /**
     * Makes {@code next} the open project's save target — {@code null} when a
     * close or a replacement fences the open one — under {@link #fenceLock},
     * atomically with every head-write claim: a head write of the outgoing
     * target either claimed before this call, and the returned stage
     * completes when it has ended, or writes nothing. Never waits.
     *
     * @return completes when the outgoing target's claimed head write has ended; at once when none is claimed
     */
    private CompletableFuture<Void> fence(SaveTarget next) {
        CompletableFuture<Void> claimed;
        fenceLock.lock();
        try {
            SaveTarget outgoing = saveTarget;
            saveTarget = next;
            claimed = outgoing == null || outgoing == next ? null : outgoing.headWriteEnded;
        } finally {
            fenceLock.unlock();
        }
        return claimed == null ? CompletableFuture.completedFuture(null) : claimed.copy();
    }

    /**
     * The head write's last check: claims {@code target} for the backup and
     * the head write if it is still the open project's, under
     * {@link #fenceLock}, so no fence passes between the check and the
     * claim.
     *
     * @return whether the claim was taken; {@code false} once the project was closed or replaced
     */
    private boolean claimHeadWrite(SaveTarget target) {
        fenceLock.lock();
        try {
            if (target != saveTarget) {
                return false;
            }
            target.headWriteEnded = new CompletableFuture<>();
            return true;
        } finally {
            fenceLock.unlock();
        }
    }

    /** Ends {@code target}'s head-write claim, if one is held, and completes what a fence returned for it. */
    private void endHeadWrite(SaveTarget target) {
        CompletableFuture<Void> ended;
        fenceLock.lock();
        try {
            ended = target.headWriteEnded;
            target.headWriteEnded = null;
        } finally {
            fenceLock.unlock();
        }
        if (ended != null) {
            ended.complete(null);
        }
    }

    // ── The save writer ─────────────────────────────────────────────────────

    /**
     * Queues {@code entry} for the save writer, coalescing a capture with the
     * one waiting last when both were taken under the same save generation
     * (ruling F2). A capture of a project closed or replaced since keeps its
     * place, and meets the fence.
     */
    private void submit(WriterEntry entry) {
        boolean startWriter;
        synchronized (saveQueue) {
            if (entry instanceof SaveJob newer && saveQueue.peekLast() instanceof SaveJob waiting
                    && waiting.target() == newer.target()) {
                saveQueue.pollLast();
                newer.waiters.addAll(waiting.waiters);
            }
            saveQueue.addLast(entry);
            startWriter = !saveWriterRunning;
            saveWriterRunning = true;
        }
        if (startWriter) {
            startSaveWriter();
        }
    }

    /**
     * Starts the save writer's virtual thread; if it cannot start, every
     * queued entry ends at once: a save fails, a task completes
     * exceptionally, and an idle wait completes — nothing queued is left to
     * wait for.
     */
    private void startSaveWriter() {
        try {
            Thread.ofVirtual().name("daw-save-writer").start(this::drainSaves);
        } catch (Throwable notStarted) {
            LOG.log(Level.SEVERE, notStarted, () -> "The save writer could not start; its queued saves fail");
            List<WriterEntry> stranded;
            synchronized (saveQueue) {
                stranded = new ArrayList<>(saveQueue);
                saveQueue.clear();
                saveWriterRunning = false;
            }
            for (WriterEntry entry : stranded) {
                switch (entry) {
                    case SaveJob job -> complete(job, new SaveOutcome.Failed(notStarted, Optional.empty()));
                    case QueuedTask<?> task -> task.result().completeExceptionally(notStarted);
                    case IdleWait wait -> wait.idle().complete(null);
                }
            }
        }
    }

    private void drainSaves() {
        while (true) {
            WriterEntry entry;
            synchronized (saveQueue) {
                entry = saveQueue.pollFirst();
                if (entry == null) {
                    saveWriterRunning = false;
                    return;
                }
            }
            try {
                switch (entry) {
                    case SaveJob job -> runSave(job);
                    case QueuedTask<?> task -> task.run();
                    case IdleWait wait -> wait.idle().complete(null);
                }
            } catch (Throwable escaped) {
                // Never here: each entry catches everything. The writer keeps draining, so no later entry is stranded.
                LOG.log(Level.SEVERE, escaped, () -> "A save writer entry failed unexpectedly");
            }
        }
    }

    /** Runs one save and completes every future waiting on it — the capture's own and those it replaced. */
    private void runSave(SaveJob job) {
        SaveOutcome outcome;
        try {
            outcome = write(job);
        } catch (Throwable unexpected) {
            LOG.log(Level.SEVERE, unexpected, () -> "The save of " + job.target().projectDirectory()
                    + " failed unexpectedly");
            outcome = new SaveOutcome.Failed(unexpected, Optional.empty());
        }
        complete(job, outcome);
    }

    private static void complete(SaveJob job, SaveOutcome outcome) {
        for (CompletableFuture<SaveOutcome> waiter : job.waiters) {
            waiter.complete(outcome);
        }
    }

    /**
     * One save on the writer, in the class note's order ("The save"). The
     * lock check reads the lease of the save's own target, never the lease of
     * a project opened since; the last check is the head-write claim, taken
     * atomically with the fence, and the claim is held through the backup
     * and the head write, so a close releases that project's lock only after
     * them (story 333).
     */
    private SaveOutcome write(SaveJob job) {
        SaveTarget target = job.target();
        if (target != saveTarget) {
            return closedBeforeItsWrite(target);
        }
        try {
            target.lease().verifyOurs();
        } catch (Throwable notOurs) {
            if (notOurs instanceof LockStolenException && target != saveTarget) {
                // A lock found taken while this session closed or replaced the project is reported as that close.
                return closedBeforeItsWrite(target);
            }
            LOG.log(Level.WARNING, notOurs, () -> "The save of " + target.projectDirectory()
                    + " was refused by the project lock; nothing was written");
            return new SaveOutcome.Failed(notOurs, Optional.empty());
        }
        String xml;
        try {
            xml = job.document().toXml();
        } catch (Throwable unrendered) {
            LOG.log(Level.WARNING, unrendered, () -> "The save of " + target.projectDirectory()
                    + " could not render the project; nothing was written");
            return new SaveOutcome.Failed(unrendered, Optional.empty());
        }
        if (!claimHeadWrite(target)) {
            return closedBeforeItsWrite(target);
        }
        Path projectFile = target.projectDirectory().resolve(PROJECT_FILE_NAME);
        Throwable headFailure = null;
        try {
            backUpIfOwed(target, projectFile);
            replaceProjectFile(projectFile, xml);
        } catch (Throwable failure) {
            headFailure = failure;
        } finally {
            // The claim covers the folder's head and its backups only; a safety copy is the checkpoint writer's.
            endHeadWrite(target);
        }
        if (headFailure != null) {
            Throwable failed = headFailure;
            LOG.log(Level.WARNING, failed, () -> "Saving " + projectFile + " failed; it is as it was");
            return failedAfterTheRender(job, xml, failed);
        }
        try {
            job.project().markCleanIfUnchanged(job.revision());
        } catch (Throwable listenerFailure) {
            // A change listener threw on the DIRTY it fired; the project is clean and project.daw holds the capture.
            LOG.log(Level.WARNING, listenerFailure, () -> "A change listener threw when the save of " + projectFile
                    + " marked the project clean; the save stands");
        }
        if (target != saveTarget) {
            LOG.fine(() -> "The project in " + target.projectDirectory() + " was closed or replaced while "
                    + projectFile + " was written; it holds the capture, and no checkpoint of it is written");
            return new SaveOutcome.Saved(job.metadata(), job.revision(), Optional.empty());
        }
        return new SaveOutcome.Saved(job.metadata(), job.revision(), checkpointOfTheSave(job, xml));
    }

    private static SaveOutcome closedBeforeItsWrite(SaveTarget target) {
        IOException closed = new IOException("The project in " + target.projectDirectory()
                + " was closed or replaced before this save was written; nothing was written");
        LOG.log(Level.FINE, () -> closed.getMessage());
        return new SaveOutcome.Failed(closed, Optional.empty());
    }

    /**
     * The backup the open owes (ruling F10): copied durably, never over an
     * existing backup, before the head is replaced, then cleared — by a
     * compare-and-set, so it clears only what it took. A copy that fails
     * throws, and the backup stays owed; so does a link the link guard
     * ({@link AtomicFileWriter#followLinkToDawFile}) refuses, one that does
     * not lead to an existing regular {@code .daw} file (ruling M3), before
     * anything is copied. The read-only refusal comes later, in the head
     * replace: a read-only {@code project.daw}, or a read-only {@code .daw}
     * file a link leads to, is backed up, and no longer owed, before the
     * head replace refuses it (story 383).
     */
    private void backUpIfOwed(SaveTarget target, Path projectFile) throws IOException {
        // If the project was opened from a legacy schema and migrated in
        // memory, or its open could not honour everything the file saved,
        // the user's choice to save is the commit point — preserve the
        // original file as a sibling backup before overwriting it. The
        // backup is taken at most once per load and never overwrites an
        // existing backup so repeated saves remain idempotent. A
        // project.daw that is gone and is not a link owes no backup, so the
        // first write settles it either way and no later save backs up this
        // load's own output; a link the link guard refuses — one that does
        // not lead to an existing regular .daw file, a dangling one
        // included — leaves the backup owed (ruling M3), but a read-only
        // .daw file behind a link passes the guard and is backed up before
        // the head replace refuses it (story 383).
        PendingBackup owed = target.pendingBackup().get();
        switch (owed) {
            case PendingBackup.None _ -> { }
            case PendingBackup.Migration migration -> backUpBeforeFirstWrite(projectFile,
                    "v" + migration.fromVersion(), migration.losesSavedContent()
                            ? "Wrote the migration backup, which also keeps content this save drops because"
                                    + " the open could not read it as written (the open's WARNING lines name"
                                    + " each item)"
                            : "Wrote the migration backup");
            case PendingBackup.Degraded _ -> backUpBeforeFirstWrite(projectFile,
                    PendingBackup.DEGRADED_TAG, "Wrote a backup of the project file before this save, which"
                            + " drops content the open could not read as written (the open's WARNING lines"
                            + " name each item)");
        }
        target.pendingBackup().compareAndSet(owed, PendingBackup.NONE);
    }

    /**
     * A backup copy or a head write failed after the render: the rendered
     * bytes go to a safety checkpoint (ruling G9) unless the lock was lost or
     * the project was closed since — in the checkpoint generation its open
     * started, never another (ruling F9). A failed safety copy never replaces
     * the head's cause; its error is added to it as suppressed, and so is a
     * {@link TimeoutException} when the copy is still being written past the
     * bound (ruling J3).
     */
    private SaveOutcome failedAfterTheRender(SaveJob job, String xml, Throwable headCause) {
        SaveTarget target = job.target();
        Optional<CheckpointManager.Generation> checkpoints = target.checkpoints();
        // A read-only open's target has no generation; its saves are refused at the call and never get here.
        if (headCause instanceof LockStolenException || target != saveTarget || checkpoints.isEmpty()) {
            return new SaveOutcome.Failed(headCause, Optional.empty());
        }
        CompletableFuture<Path> copy = checkpointManager.writeCheckpoint(checkpoints.get(), xml, job.capture(),
                CheckpointOrigin.SAFETY_COPY);
        try {
            Path safety = copy.get(checkpointWait.toNanos(), TimeUnit.NANOSECONDS);
            LOG.warning(() -> "The document the failed save of " + target.projectDirectory() + " rendered is in "
                    + safety);
            return new SaveOutcome.Failed(headCause, Optional.of(safety));
        } catch (ExecutionException notWritten) {
            Throwable copyFailure = notWritten.getCause() == null ? notWritten : notWritten.getCause();
            LOG.log(Level.WARNING, copyFailure, () -> "No safety copy of the failed save of "
                    + target.projectDirectory() + " could be written");
            addSuppressed(headCause, copyFailure);
        } catch (TimeoutException stillWriting) {
            TimeoutException pending = new TimeoutException("the safety copy was still being written after "
                    + boundText(checkpointWait));
            LOG.warning(() -> "The safety copy of the failed save of " + target.projectDirectory()
                    + " was still being written after " + boundText(checkpointWait)
                    + "; the save completes without it, and the copy is still written");
            addSuppressed(headCause, pending);
        } catch (CancellationException cancelled) {
            addSuppressed(headCause, cancelled);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOG.log(Level.WARNING, interrupted, () -> "The save writer was interrupted while it waited for the"
                    + " safety copy of " + target.projectDirectory());
            addSuppressed(headCause, interrupted);
        }
        return new SaveOutcome.Failed(headCause, Optional.empty());
    }

    /**
     * The checkpoint of a written head, from the same bytes (ruling F3), in
     * the checkpoint generation the project's open started — refused once
     * that generation has ended, never sent to another (ruling F9) — and
     * waited for at most the bound (ruling J3). None — it failed, autosave
     * is disabled, the generation ended, it is still being written — never
     * fails the save: the checkpoint listeners hear of the checkpoint only if
     * it was written or failed before its generation ended (ruling G13 #5) —
     * not of one the end of its generation dropped, nor of one written or
     * failed while {@link CheckpointManager#stop()} ended it, nor of one
     * refused because autosave is disabled.
     */
    private Optional<Path> checkpointOfTheSave(SaveJob job, String xml) {
        Path projectDirectory = job.target().projectDirectory();
        Optional<CheckpointManager.Generation> checkpoints = job.target().checkpoints();
        if (checkpoints.isEmpty()) {
            // A read-only open's target: its saves are refused at the call and never get here.
            return Optional.empty();
        }
        CompletableFuture<Path> checkpoint = checkpointManager.writeCheckpoint(checkpoints.get(), xml,
                job.capture(), CheckpointOrigin.AFTER_SAVE);
        try {
            return Optional.of(checkpoint.get(checkpointWait.toNanos(), TimeUnit.NANOSECONDS));
        } catch (ExecutionException notWritten) {
            LOG.log(Level.FINE, notWritten.getCause(), () -> "The checkpoint of the save of " + projectDirectory
                    + " was not written");
        } catch (TimeoutException stillWriting) {
            LOG.warning(() -> "The checkpoint of the save of " + projectDirectory + " was still being written after "
                    + boundText(checkpointWait) + "; the save completes without it, and it is still written");
        } catch (CancellationException cancelled) {
            LOG.log(Level.FINE, cancelled, () -> "The checkpoint of the save of " + projectDirectory
                    + " was cancelled");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOG.log(Level.WARNING, interrupted, () -> "The save writer was interrupted while it waited for the"
                    + " checkpoint of " + projectDirectory);
        }
        return Optional.empty();
    }

    private static void addSuppressed(Throwable headCause, Throwable suppressed) {
        if (suppressed != headCause) {
            headCause.addSuppressed(suppressed);
        }
    }

    /** {@code 5 s} for whole seconds, else milliseconds. */
    private static String boundText(Duration bound) {
        return bound.toMillis() % 1_000L == 0L ? bound.toSeconds() + " s" : bound.toMillis() + " ms";
    }

    // ── Project-file writes ─────────────────────────────────────────────────

    /**
     * Backs {@code projectFile} up under {@code tag}, if there is a file to
     * back up, and logs {@code written} followed by {@code ": <backup path>"}
     * at INFO. The line counts nothing: every loss that arms a backup was
     * logged at WARNING during the open, so it says why the backup exists and
     * points to those lines. A {@code projectFile} that is a symbolic link the
     * head replace's link guard ({@link AtomicFileWriter#followLinkToDawFile})
     * refuses — one that does not lead to an existing regular {@code .daw}
     * file — is refused here first, with the same
     * {@link java.nio.file.FileSystemException} (ruling M3), before anything
     * is read through it: nothing is copied, and the backup stays owed. A
     * read-only {@code .daw} file, behind a link or not, passes the guard and
     * is backed up here; the head replace refuses it only afterwards (story
     * 383).
     */
    private void backUpBeforeFirstWrite(Path projectFile, String tag, String written) throws IOException {
        Path dawFile = AtomicFileWriter.followLinkToDawFile(projectFile);
        if (Files.exists(dawFile)) {
            Path backup = writeSiblingBackup(projectFile, tag);
            LOG.info(() -> written + ": " + backup);
        }
    }

    /**
     * Copies {@code projectFile} to the sibling
     * {@code project.daw.<tag>.<yyyyMMdd-HHmmss UTC>.bak}, or
     * {@code ….<stamp>-<n>.bak} when that name is taken (two saves within a
     * second), never overwriting an existing backup: the copy is durable and
     * publishes only under a name nothing holds
     * ({@link AtomicFileWriter.Overwrite#NEVER}), so a name another writer
     * takes between the choice and the publish moves this one on to the
     * next suffix (story 331).
     *
     * @param projectFile the project file to back up
     * @param tag         {@code v<fromVersion>} for a migrated open — the name
     *                    the Migration History, the hub's health scan and the
     *                    roll-back look for — or {@code degraded}, which none
     *                    of them matches
     * @return the backup written
     */
    private Path writeSiblingBackup(Path projectFile, String tag) throws IOException {
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(java.time.ZoneOffset.UTC)
                .format(Instant.now());
        for (int n = 0; ; n++) {
            String suffix = n == 0 ? "" : "-" + n;
            Path backup = projectFile.resolveSibling(
                    projectFile.getFileName() + "." + tag + "." + stamp + suffix + ".bak");
            try {
                return fileWriter.copy(projectFile, backup, AtomicFileWriter.Overwrite.NEVER);
            } catch (FileAlreadyExistsException taken) {
                // Never clobber an existing backup (e.g. two opens within a
                // second); a refusal with the name still free is not that.
                if (!Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                    throw taken;
                }
            }
        }
    }

    private void writeProjectFile(ProjectMetadata metadata) throws IOException {
        if (metadata.projectPath() == null) {
            return;
        }
        Path projectFile = metadata.projectPath().resolve(PROJECT_FILE_NAME);
        String content = String.format("""
                # DAW Project File
                name=%s
                created_at=%s
                last_modified=%s
                """,
                metadata.name(),
                metadata.createdAt().toString(),
                metadata.lastModified().toString());
        replaceProjectFile(projectFile, content);
    }

    /**
     * Atomically replaces {@code projectFile} with {@code content}, refusing
     * a file the user made read-only (ruling G10). A {@code project.daw} that
     * is a symbolic link keeps the link: the {@code .daw} file it leads to is
     * replaced, and a link that leads anywhere else is refused with a
     * {@link java.nio.file.FileSystemException} naming it and nothing written
     * (ruling M3) — for a save, an ordinary head failure.
     */
    private void replaceProjectFile(Path projectFile, String content) throws IOException {
        fileWriter.writeString(projectFile, content, AtomicFileWriter.ReplacePermission.TARGET,
                AtomicFileWriter.ReplaceOption.FOLLOW_LINK_TO_DAW_FILE);
    }

    private ProjectMetadata parseProjectFile(String content, Path projectDir) {
        String name = "Untitled";
        Instant createdAt = Instant.now();
        Instant lastModified = Instant.now();

        for (String line : content.split("\n")) {
            if (line.startsWith("name=")) {
                name = line.substring("name=".length()).strip();
            } else if (line.startsWith("created_at=")) {
                try {
                    createdAt = Instant.parse(line.substring("created_at=".length()).strip());
                } catch (DateTimeParseException ignored) {
                    // keep default
                }
            } else if (line.startsWith("last_modified=")) {
                try {
                    lastModified = Instant.parse(line.substring("last_modified=".length()).strip());
                } catch (DateTimeParseException ignored) {
                    // keep default
                }
            }
        }
        return new ProjectMetadata(name, createdAt, lastModified, projectDir);
    }

    /**
     * The folder name a project name gives (story 333: New, a first save and
     * Save As name their folder through it): every character but ASCII
     * letters, digits, {@code .}, {@code _}, {@code -} and space becomes
     * {@code _}, then the result is stripped. Pure; no I/O.
     *
     * @param name the project name the user typed
     * @return the folder name
     * @throws IllegalArgumentException if the result is empty, {@code .} or {@code ..}
     */
    public static String sanitizeDirectoryName(String name) {
        Objects.requireNonNull(name, "name must not be null");
        String sanitized = name.replaceAll("[^a-zA-Z0-9._\\- ]", "_").strip();
        if (sanitized.isEmpty() || sanitized.equals(".") || sanitized.equals("..")) {
            throw new IllegalArgumentException("Project name results in an invalid directory name: " + name);
        }
        return sanitized;
    }

    // ── State ───────────────────────────────────────────────────────────────

    /**
     * One open project's save generation: where its saves are written, the
     * backup its first write owes, the lease its open or create took, and the
     * checkpoint generation its open started, which its saves' checkpoints
     * and safety copies are bound to (ruling F9). Compared by identity: a
     * capture taken under one writes only while it is still
     * {@link #saveTarget}.
     */
    private static final class SaveTarget {

        private final Path projectDirectory;

        /**
         * The lease its open or create took: a save of this target checks
         * this lease's lock, never the lease of a project opened since
         * (story 333).
         */
        private final ProjectLockManager lease;

        /** The checkpoint generation a writable open started; {@code null} for a read-only open, which starts none. */
        private final CheckpointManager.Generation checkpoints;

        /**
         * The folders this session saved the project from as a copy, oldest
         * first, and the files this folder holds of them (story 333, Save As;
         * ENG-S10): a capture writes a reference under one of them
         * project-relative only when this folder holds its file
         * ({@link ProjectSerializer#capture(DawProject, FormerRoots)}).
         * {@link FormerRoots#NONE} unless this target is a published Save As copy.
         */
        private final FormerRoots formerRoots;

        /**
         * Guarded by {@link ProjectManager#fenceLock}: completed when the head
         * write that claimed this target ends; {@code null} while none is
         * claimed (story 333's lock hand-off).
         */
        private CompletableFuture<Void> headWriteEnded;

        /**
         * The sibling backup of {@code project.daw} the first write owes,
         * decided when the project was opened: the pre-migration original of
         * a migrated open, the original of an open that could not honour
         * everything its file saved ({@link ProjectDeserializer#losesSavedContent()}),
         * or none. Cleared by that first write, whether or not there was a
         * file to copy, so the backup is taken at most once per load and never
         * holds this load's own output.
         */
        private final AtomicReference<PendingBackup> pendingBackup;

        private SaveTarget(Path projectDirectory, PendingBackup owed, ProjectLockManager lease,
                           FormerRoots formerRoots, CheckpointManager.Generation checkpoints) {
            this.projectDirectory = Objects.requireNonNull(projectDirectory, "projectDirectory must not be null");
            this.pendingBackup = new AtomicReference<>(Objects.requireNonNull(owed, "owed must not be null"));
            this.lease = Objects.requireNonNull(lease, "lease must not be null");
            this.formerRoots = Objects.requireNonNull(formerRoots, "formerRoots must not be null");
            this.checkpoints = checkpoints;
        }

        /**
         * A writable open's target, guarded by {@code lease}; its saves' checkpoints belong to
         * {@code checkpoints}, and its captures write references under its {@code formerRoots} whose files it
         * holds project-relative.
         */
        static SaveTarget writable(Path projectDirectory, PendingBackup owed, ProjectLockManager lease,
                                   FormerRoots formerRoots, CheckpointManager.Generation checkpoints) {
            return new SaveTarget(projectDirectory, owed, lease, formerRoots,
                    Objects.requireNonNull(checkpoints, "checkpoints must not be null"));
        }

        /** The target of a read-only open, whose {@code lease} holds no lock: it has no checkpoint generation. */
        static SaveTarget readOnly(Path projectDirectory, PendingBackup owed, ProjectLockManager lease) {
            return new SaveTarget(projectDirectory, owed, lease, FormerRoots.NONE, null);
        }

        /** The folders this session saved the project from as a copy, and what this folder holds of them. */
        FormerRoots formerRoots() {
            return formerRoots;
        }

        Path projectDirectory() {
            return projectDirectory;
        }

        ProjectLockManager lease() {
            return lease;
        }

        /** The checkpoint generation this project's open started; empty for a read-only open. */
        Optional<CheckpointManager.Generation> checkpoints() {
            return Optional.ofNullable(checkpoints);
        }

        AtomicReference<PendingBackup> pendingBackup() {
            return pendingBackup;
        }
    }

    /** An entry of the save writer's queue. */
    private sealed interface WriterEntry permits SaveJob, QueuedTask, IdleWait {
    }

    /**
     * One capture to write. {@link #waiters} holds its own future and those
     * of the captures it replaced; guarded by {@link #saveQueue} while it is
     * queued, then the writer's.
     */
    private static final class SaveJob implements WriterEntry {

        private final SaveTarget target;
        private final DawProject project;
        private final ProjectMetadata metadata;
        private final CheckpointManager.Capture capture;
        private final ProjectDocument document;
        final List<CompletableFuture<SaveOutcome>> waiters = new ArrayList<>();

        SaveJob(SaveTarget target, DawProject project, ProjectMetadata metadata, CheckpointManager.Capture capture,
                ProjectDocument document) {
            this.target = target;
            this.project = project;
            this.metadata = metadata;
            this.capture = capture;
            this.document = document;
        }

        SaveTarget target() {
            return target;
        }

        DawProject project() {
            return project;
        }

        ProjectMetadata metadata() {
            return metadata;
        }

        long revision() {
            return capture.revision();
        }

        CheckpointManager.Capture capture() { return capture; }

        ProjectDocument document() {
            return document;
        }
    }

    /** A {@link #runAfterQueuedSaves} task and its future. */
    private record QueuedTask<T>(Callable<T> task, CompletableFuture<T> result) implements WriterEntry {

        void run() {
            try {
                result.complete(task.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        }
    }

    /**
     * A {@link #whenSavesIdle()} wait: completed when the writer reaches it,
     * or at once when the writer cannot start — never exceptionally.
     */
    private record IdleWait(CompletableFuture<Void> idle) implements WriterEntry {
    }

    /**
     * The sibling backup the first write of an opened project owes
     * ({@link SaveTarget#pendingBackup()}), decided by {@link #at} when the
     * project is opened.
     */
    private sealed interface PendingBackup {

        /** No backup is owed. */
        PendingBackup NONE = new None();

        /**
         * The tag of a degraded open's backup,
         * {@code project.daw.degraded.<stamp>.bak}. It does not start with
         * {@code v} and a digit, so the Migration History, the hub's health
         * scan and the migration roll-back never take it for a pre-migration
         * backup.
         */
        String DEGRADED_TAG = "degraded";

        /**
         * The backup an open owes: the pre-migration original when the schema
         * was migrated — the one backup, which then also holds what the open
         * could not honour, under the name the roll-back finds — else, when
         * the next save would drop saved content
         * ({@link ProjectDeserializer#losesSavedContent()}), the original
         * under {@link #DEGRADED_TAG}; else none.
         *
         * @param migration          the open's migration report
         * @param losesSavedContent  whether the next save would drop saved content
         * @return the backup owed
         */
        static PendingBackup at(MigrationReport migration, boolean losesSavedContent) {
            if (migration.wasMigrated()) {
                return new Migration(migration.fromVersion(), losesSavedContent);
            }
            return losesSavedContent ? new Degraded() : NONE;
        }

        /** Nothing is owed. */
        record None() implements PendingBackup {
        }

        /**
         * The pre-migration original, {@code project.daw.v<fromVersion>.<stamp>.bak}.
         *
         * @param fromVersion       the schema version the file was saved with
         * @param losesSavedContent whether it also holds content the next save drops
         */
        record Migration(int fromVersion, boolean losesSavedContent) implements PendingBackup {
        }

        /**
         * The original of an open whose next save would drop saved content,
         * {@code project.daw.degraded.<stamp>.bak}.
         */
        record Degraded() implements PendingBackup {
        }
    }
}
