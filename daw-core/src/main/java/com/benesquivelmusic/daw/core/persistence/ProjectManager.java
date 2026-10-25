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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
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
 * capture of a project closed, abandoned or replaced since writes nothing);
 * the lock check ({@link ProjectLockManager#verifyOurs()}); the render, once;
 * the backup the open owes, a durable copy that never overwrites a backup;
 * the atomic head write; the project marked clean if no edit came since the
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
 * (ruling F9): once that project is closed, abandoned or replaced — the same
 * folder opened again included — neither is written, and no checkpoint
 * listener hears of it. A head write already past its last check when that
 * happens still finishes into its own project's folder (a known limit until
 * story 333's lock hand-off); it writes no checkpoint.</p>
 *
 * <p>The save writer waits for the checkpoint it requests at most five
 * seconds (ruling J3): past it the save completes without
 * naming one, the checkpoint is still written, and the checkpoint listeners
 * hear of it when it is. A head write or backup that fails after the render,
 * with the lock still ours and the project still open, leaves the rendered
 * bytes in a safety checkpoint (ruling G9).</p>
 *
 * <p>The save writer and the checkpoint writer are daemon virtual threads:
 * the JVM does not wait for them, and an exit can end them mid-write. The
 * application waits for both, at most thirty seconds, from its one JVM
 * shutdown hook ({@link #awaitWritesBeforeExit()}, ruling M1).</p>
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
     * application exits (ruling M1).
     */
    static final Duration EXIT_WRITE_WAIT = Duration.ofSeconds(30);

    /** Bound while this manager's save writer runs an entry: {@link #closeProject()} there would wait on itself. */
    private static final ScopedValue<ProjectManager> SAVE_WRITING = ScopedValue.newInstance();

    /**
     * Name of the per-project directory that holds recorded audio — the only
     * home for captured takes (Recording Reliability book §2.6).
     */
    public static final String AUDIO_DIR_NAME = "audio";

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

    private final Map<String, ProjectMetadata> recentProjects = new LinkedHashMap<>();
    private final CheckpointManager checkpointManager;
    private final RecentProjectsStore recentProjectsStore;
    private final ProjectSerializer serializer = new ProjectSerializer();
    private final ProjectDeserializer deserializer;
    private final ProjectLockManager lockManager;
    private final AtomicFileWriter fileWriter;
    private final Duration checkpointWait;

    private volatile ProjectMetadata currentProject;
    private volatile DawProject currentDawProject;
    private volatile LockConflictHandler lockConflictHandler;
    private volatile boolean readOnly;

    /**
     * The save generation of the open project — what its backup owes, where
     * it is written and the checkpoint generation its checkpoints belong to
     * — or {@code null} when none is open, and while a create or an open
     * replaces it. Replaced by every create and open, cleared by close and
     * abandon; a capture carries the one it was taken under, and the save
     * writer writes it only while it is still this one.
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
        this.checkpointWait = Objects.requireNonNull(checkpointWait, "checkpointWait must not be null");
        // A checkpoint is written only while this session holds the project
        // lock and it is still ours (story 331): a stolen lock fails the
        // checkpoint visibly, and a session that holds none writes nothing.
        checkpointManager.setWriteGuard(lockManager::verifyHeld);
    }

    /**
     * Sets the handler invoked when an open() call discovers another session
     * already holds the project lock. If {@code null} (the default), conflicts
     * resolve to {@link LockConflictResolution#CANCEL}.
     */
    public void setLockConflictHandler(LockConflictHandler handler) {
        this.lockConflictHandler = handler;
    }

    /** Returns the project lock manager backing this {@code ProjectManager}. */
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
     * <p>The metadata file is written here, on the caller, through the
     * {@link AtomicFileWriter}: creating a project is not the save path.
     * Saves of the project open before this call write nothing once it
     * returns (a new save generation), except what is already being written
     * — a head write past its last check (a known limit until story 333's
     * lock hand-off; a door waits for {@link #whenSavesIdle()} first), or a
     * checkpoint still inside its write when the checkpoints' bounded stop
     * gives up on it (ruling G5) — which finishes into that project's own
     * folder. A save of that project never writes a checkpoint or a safety
     * copy into this project's checkpoints, or any but its own project's:
     * both are bound to the checkpoint generation its open started, which
     * this call ends.</p>
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
        // The project is being replaced: its checkpoints end before anything here can fail.
        checkpointManager.stop();

        Path projectDir = createUniqueProjectDirectory(parentDirectory, sanitizeDirectoryName(name));
        Files.createDirectories(audioDirectory(projectDir));

        ProjectMetadata metadata = ProjectMetadata.createNew(name).withPath(projectDir);
        writeProjectFile(metadata);

        // The open project's saves are fenced from here; the new target follows its checkpoint generation.
        saveTarget = null;
        currentProject = metadata;
        currentDawProject = null;
        readOnly = false;
        lastMissingAssets = MissingAssetsReport.EMPTY;
        lastUnreadItems = List.of();
        recentProjects.put(projectDir.toString(), metadata);
        recordRecentProject(projectDir);
        // createUniqueProjectDirectory() atomically created a brand-new folder, so
        // the directory cannot already be locked — unconditionally take it.
        lockManager.forceAcquire(projectDir);
        lockManager.startHeartbeat();
        saveTarget = SaveTarget.writable(projectDir, PendingBackup.NONE,
                checkpointManager.startGeneration(projectDir));
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
     * the old project's {@code checkpoints/}. Saves of the project open before this call
     * write nothing once it returns (a new save generation), except what is
     * already being written — a head write past its last check (a known
     * limit until story 333's lock hand-off; a door waits for
     * {@link #whenSavesIdle()} first), or a checkpoint still inside its write
     * when the checkpoints' bounded stop gives up on it (ruling G5) — which
     * finishes into that project's own folder. A save of that project never
     * writes a checkpoint or a safety copy into this project's checkpoints —
     * nor into its own folder's when this call opens that folder again — for
     * both are bound, by identity, to the checkpoint generation its open
     * started, which this call ends.</p>
     *
     * @param projectDirectory the project directory
     * @return the project metadata
     * @throws IOException if the project cannot be read
     */
    public ProjectMetadata openProject(Path projectDirectory) throws IOException {
        Objects.requireNonNull(projectDirectory, "projectDirectory must not be null");
        // The project is being replaced: its checkpoints end before anything here can fail.
        checkpointManager.stop();

        Path projectFile = projectDirectory.resolve(PROJECT_FILE_NAME);
        if (!Files.exists(projectFile)) {
            throw new IOException("Project file not found: " + projectFile);
        }

        // Resolve any pre-existing lock before reading the project file so the
        // user can cancel the open without side effects.
        ProjectLockManager.AcquisitionResult result = lockManager.tryAcquire(projectDirectory);
        boolean openedReadOnly = false;
        if (!result.wasAcquired()) {
            LockConflictHandler handler = lockConflictHandler;
            LockConflictResolution decision = (handler != null && result.existingLock() != null)
                    ? handler.resolve(result.existingLock(), result.stale())
                    : LockConflictResolution.CANCEL;
            switch (decision) {
                case CANCEL -> throw new ProjectLockedException(
                        "Project is already open by " + describe(result.existingLock()),
                        result.existingLock(), result.stale());
                case OPEN_READ_ONLY -> {
                    lockManager.openReadOnly(projectDirectory);
                    openedReadOnly = true;
                }
                case TAKE_OVER -> lockManager.forceAcquire(projectDirectory);
            }
        }
        if (!openedReadOnly && lockManager.status() == LockStatus.HELD) {
            lockManager.startHeartbeat();
        }

        String content = Files.readString(projectFile);

        PendingBackup owed;
        if (content.strip().startsWith("<?xml") || content.strip().startsWith("<daw-project")) {
            // Project-relative clip references (story 323) resolve against
            // the directory the project file lives in.
            DawProject dawProject = deserializer.deserialize(content, projectDirectory);
            ProjectMetadata metadata = dawProject.getMetadata().withPath(projectDirectory);
            dawProject.setMetadata(metadata);
            dawProject.markClean();
            MigrationReport migration = deserializer.getLastMigrationReport();
            owed = PendingBackup.at(migration, deserializer.losesSavedContent());
            // The open project's saves are fenced from here; the new target follows its checkpoint generation.
            saveTarget = null;
            currentDawProject = dawProject;
            currentProject = metadata;
            lastMigrationReport = migration;
            lastMissingAssets = MissingAssetsReport.atOpen(
                    dawProject, deserializer.getMissingFiles(), projectDirectory);
            lastUnreadItems = deserializer.getUnreadItems();
        } else {
            ProjectMetadata metadata = parseProjectFile(content, projectDirectory);
            owed = PendingBackup.NONE;
            saveTarget = null;
            currentProject = metadata;
            currentDawProject = null;
            lastMigrationReport = MigrationReport.noOp(
                    com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry.CURRENT_VERSION);
            // The deserializer did not run: its missing-files and unread-items
            // lists are the previous open's, and this file has nothing to report.
            lastMissingAssets = MissingAssetsReport.EMPTY;
            lastUnreadItems = List.of();
        }

        readOnly = openedReadOnly;
        recentProjects.put(projectDirectory.toString(), currentProject);
        recordRecentProject(projectDirectory);
        if (openedReadOnly) {
            saveTarget = SaveTarget.readOnly(projectDirectory, owed);
        } else {
            saveTarget = SaveTarget.writable(projectDirectory, owed,
                    checkpointManager.startGeneration(projectDirectory));
        }
        return currentProject;
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
     * <p>The generation fence: a capture of a project closed, abandoned or
     * replaced before its head write starts writes nothing. One whose head
     * write is already past its last check then still finishes that write
     * into its own project's folder (a known limit until story 333's lock
     * hand-off) and completes {@link SaveOutcome.Saved} naming no checkpoint,
     * or one written into its own folder before the switch returned.
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
            job = new SaveJob(target, project, touched, revision, serializer.capture(project));
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
     * save's future, nor call {@link #closeProject()}.</p>
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
     * Waits, as the application exits, for the saves and every checkpoint job
     * already handed to the writers (story 331, ruling M1): the save writer
     * and the checkpoint writer are daemon virtual threads, which the JVM
     * would otherwise end mid-write.
     * The application calls it from its one JVM shutdown hook, off the JavaFX
     * application thread; it does nothing else there — it saves nothing,
     * stops no checkpoints, releases no lock and posts nothing.
     *
     * <p>Under one deadline, thirty seconds from its start: first
     * {@link #whenSavesIdle()} — the save being written, the capture waiting
     * behind it, a named-snapshot copy queued among them, and each save's
     * checkpoint or safety copy, as long as the save writer waits for it
     * (ruling J3); then, with the time left, every job the checkpoint writer
     * holds, of any origin and any generation, until it holds none. A write
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
     * Closes the current project after saving: its {@link DawProject} through
     * {@link #save(DawProject)}, waited for, or — when none is held — its
     * metadata file, written on the caller. A read-only project is not
     * saved. A lost lock ({@link LockStolenException}) skips the final save;
     * any other failure is thrown, and the project stays open. Must not be
     * called from a {@link #runAfterQueuedSaves} task.
     *
     * @throws IOException if the final save fails
     */
    public void closeProject() throws IOException {
        if (currentProject == null) {
            return;
        }
        if (SAVE_WRITING.isBound() && SAVE_WRITING.get() == this) {
            throw new IllegalStateException("closeProject() was called from the save writer; it would wait on itself");
        }
        if (!readOnly) {
            try {
                saveForClose();
            } catch (LockStolenException e) {
                // Lock was stolen — preserve user data by skipping the final save;
                // the UI is expected to have already prompted Save As.
            }
        }
        saveTarget = null;
        checkpointManager.stop();
        try {
            lockManager.release();
        } catch (IOException ignored) {
            // best-effort cleanup
        }
        currentProject = null;
        currentDawProject = null;
        readOnly = false;
        lastMigrationReport = MigrationReport.noOp(
                com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry.CURRENT_VERSION);
        lastMissingAssets = MissingAssetsReport.EMPTY;
        lastUnreadItems = List.of();
    }

    /** The final save of {@link #closeProject()}; throws what it failed with, as an {@link IOException}. */
    private void saveForClose() throws IOException {
        DawProject open = currentDawProject;
        if (open == null) {
            lockManager.verifyOurs();
            ProjectMetadata touched = currentProject.touch();
            currentProject = touched;
            writeProjectFile(touched);
            return;
        }
        if (save(open).join() instanceof SaveOutcome.Failed(Throwable cause, _)) {
            throw cause instanceof IOException io ? io : new IOException("The final save failed: " + cause, cause);
        }
    }

    /**
     * Abandons the current project without saving. Use this when the user
     * has explicitly chosen to discard unsaved changes. Saves of it write
     * nothing once this call returns (the generation fence), except what is
     * already being written — a head write past its last check, which is not
     * waited for (a known limit until story 333's lock hand-off; the caller
     * waits for {@link #whenSavesIdle()} first, ruling G1), or a checkpoint
     * still inside its write when the checkpoints' bounded stop gives up on
     * it (ruling G5) — which finishes into the project's folder. No save of
     * it writes a checkpoint or a safety copy into another generation's
     * checkpoints, the same folder opened again included: both are bound to
     * the checkpoint generation its open started, which this call ends.
     */
    public void abandonProject() {
        if (currentProject == null) {
            return;
        }
        saveTarget = null;
        checkpointManager.stop();
        try {
            lockManager.release();
        } catch (IOException ignored) {
            // best-effort cleanup
        }
        currentProject = null;
        currentDawProject = null;
        readOnly = false;
        lastMigrationReport = MigrationReport.noOp(
                com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry.CURRENT_VERSION);
        lastMissingAssets = MissingAssetsReport.EMPTY;
        lastUnreadItems = List.of();
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
     * — after {@link #createProject}, {@link #closeProject()},
     * {@link #abandonProject()}, and a legacy metadata-only open.
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
     * {@link #closeProject()}, {@link #abandonProject()}, and a legacy
     * metadata-only open.
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
     * Returns a map of recent project paths to their metadata.
     *
     * @return unmodifiable map of recent projects
     */
    public Map<String, ProjectMetadata> getRecentProjects() {
        return Collections.unmodifiableMap(recentProjects);
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
            Thread.ofVirtual().name("daw-save-writer")
                    .start(() -> ScopedValue.where(SAVE_WRITING, this).run(this::drainSaves));
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

    /** One save on the writer, in the class note's order ("The save"). */
    private SaveOutcome write(SaveJob job) {
        SaveTarget target = job.target();
        if (target != saveTarget) {
            return closedBeforeItsWrite(target);
        }
        try {
            lockManager.verifyOurs();
        } catch (Throwable notOurs) {
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
        if (target != saveTarget) {
            return closedBeforeItsWrite(target);
        }
        Path projectFile = target.projectDirectory().resolve(PROJECT_FILE_NAME);
        try {
            backUpIfOwed(target, projectFile);
            replaceProjectFile(projectFile, xml);
        } catch (Throwable headFailure) {
            LOG.log(Level.WARNING, headFailure, () -> "Saving " + projectFile + " failed; it is as it was");
            return failedAfterTheRender(job, xml, headFailure);
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
        CompletableFuture<Path> copy = checkpointManager.writeCheckpoint(checkpoints.get(), xml, job.revision(),
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
                job.revision(), CheckpointOrigin.AFTER_SAVE);
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

    static String sanitizeDirectoryName(String name) {
        String sanitized = name.replaceAll("[^a-zA-Z0-9._\\- ]", "_").strip();
        if (sanitized.isEmpty() || sanitized.equals(".") || sanitized.equals("..")) {
            throw new IllegalArgumentException("Project name results in an invalid directory name: " + name);
        }
        return sanitized;
    }

    // ── State ───────────────────────────────────────────────────────────────

    /**
     * One open project's save generation: where its saves are written, the
     * backup its first write owes, and the checkpoint generation its open
     * started, which its saves' checkpoints and safety copies are bound to
     * (ruling F9). Compared by identity: a capture taken under one writes
     * only while it is still {@link #saveTarget}.
     */
    private static final class SaveTarget {

        private final Path projectDirectory;

        /** The checkpoint generation a writable open started; {@code null} for a read-only open, which starts none. */
        private final CheckpointManager.Generation checkpoints;

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

        private SaveTarget(Path projectDirectory, PendingBackup owed, CheckpointManager.Generation checkpoints) {
            this.projectDirectory = Objects.requireNonNull(projectDirectory, "projectDirectory must not be null");
            this.pendingBackup = new AtomicReference<>(Objects.requireNonNull(owed, "owed must not be null"));
            this.checkpoints = checkpoints;
        }

        /** The target of a writable open, whose saves' checkpoints belong to {@code checkpoints}. */
        static SaveTarget writable(Path projectDirectory, PendingBackup owed,
                                   CheckpointManager.Generation checkpoints) {
            return new SaveTarget(projectDirectory, owed,
                    Objects.requireNonNull(checkpoints, "checkpoints must not be null"));
        }

        /** The target of a read-only open: it has no checkpoint generation. */
        static SaveTarget readOnly(Path projectDirectory, PendingBackup owed) {
            return new SaveTarget(projectDirectory, owed, null);
        }

        Path projectDirectory() {
            return projectDirectory;
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
        private final long revision;
        private final ProjectDocument document;
        final List<CompletableFuture<SaveOutcome>> waiters = new ArrayList<>();

        SaveJob(SaveTarget target, DawProject project, ProjectMetadata metadata, long revision,
                ProjectDocument document) {
            this.target = target;
            this.project = project;
            this.metadata = metadata;
            this.revision = revision;
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
            return revision;
        }

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
