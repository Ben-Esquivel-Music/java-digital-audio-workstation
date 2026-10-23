package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.persistence.migration.MigrationReport;
import com.benesquivelmusic.daw.core.project.DawProject;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
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
 * restored via {@link ProjectDeserializer}.</p>
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

    private static final String PROJECT_FILE_NAME = "project.daw";

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

    private ProjectMetadata currentProject;
    private DawProject currentDawProject;
    private LockConflictHandler lockConflictHandler;
    private boolean readOnly;

    /**
     * Migration report from the most recent {@link #openProject(Path)} call.
     * When {@link MigrationReport#wasMigrated()} is true, the next save
     * will first back up the original on-disk file (see
     * {@link #saveProject()} / {@link #saveDawProject(DawProject)}) so the
     * user can roll back to the pre-migration version.
     */
    private MigrationReport lastMigrationReport = MigrationReport.noOp(
            com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry.CURRENT_VERSION);

    /**
     * The sibling backup of {@code project.daw} the first write of the
     * currently open project owes, decided when it was opened: the
     * pre-migration original of a migrated open, the original of an open
     * that could not honour everything its file saved
     * ({@link ProjectDeserializer#losesSavedContent()}), or none. Cleared by
     * that first write, whether or not there was a file to copy, so the
     * backup is taken at most once per load and never holds this load's own
     * output.
     */
    private PendingBackup pendingBackup = PendingBackup.NONE;

    /**
     * Missing-assets report built when the currently open project was loaded
     * (Persistence Integrity book §4.3, §5.3 step 5 ACCOUNT) — see
     * {@link #getLastMissingAssets()}. {@link MissingAssetsReport#EMPTY} when
     * no deserialized project is open.
     */
    private MissingAssetsReport lastMissingAssets = MissingAssetsReport.EMPTY;

    /**
     * Saved items the load of the currently open project could not honour as
     * written (Persistence Integrity book §2.4) — see
     * {@link #getLastUnreadItems()}. Empty when no deserialized project is
     * open.
     */
    private List<UnreadProjectItem> lastUnreadItems = List.of();

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
        this.checkpointManager = Objects.requireNonNull(checkpointManager,
                "checkpointManager must not be null");
        this.recentProjectsStore = recentProjectsStore;
        this.lockManager = Objects.requireNonNull(lockManager, "lockManager must not be null");
        this.deserializer = Objects.requireNonNull(deserializer, "deserializer must not be null");
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
     * @param name             the project name
     * @param parentDirectory  the parent directory where the project folder will be created
     * @return metadata for the new project
     * @throws IOException if the project directory cannot be created
     */
    public ProjectMetadata createProject(String name, Path parentDirectory) throws IOException {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(parentDirectory, "parentDirectory must not be null");

        Path projectDir = createUniqueProjectDirectory(parentDirectory, sanitizeDirectoryName(name));
        Files.createDirectories(audioDirectory(projectDir));

        ProjectMetadata metadata = ProjectMetadata.createNew(name).withPath(projectDir);
        writeProjectFile(metadata);

        currentProject = metadata;
        currentDawProject = null;
        readOnly = false;
        pendingBackup = PendingBackup.NONE;
        lastMissingAssets = MissingAssetsReport.EMPTY;
        lastUnreadItems = List.of();
        recentProjects.put(projectDir.toString(), metadata);
        recordRecentProject(projectDir);
        // createUniqueProjectDirectory() atomically created a brand-new folder, so
        // the directory cannot already be locked — unconditionally take it.
        lockManager.forceAcquire(projectDir);
        lockManager.startHeartbeat();
        checkpointManager.start(projectDir);
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
     * @param projectDirectory the project directory
     * @return the project metadata
     * @throws IOException if the project cannot be read
     */
    public ProjectMetadata openProject(Path projectDirectory) throws IOException {
        Objects.requireNonNull(projectDirectory, "projectDirectory must not be null");

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

        if (content.strip().startsWith("<?xml") || content.strip().startsWith("<daw-project")) {
            // Project-relative clip references (story 323) resolve against
            // the directory the project file lives in.
            DawProject dawProject = deserializer.deserialize(content, projectDirectory);
            ProjectMetadata metadata = dawProject.getMetadata().withPath(projectDirectory);
            dawProject.setMetadata(metadata);
            dawProject.markClean();
            currentDawProject = dawProject;
            currentProject = metadata;
            lastMigrationReport = deserializer.getLastMigrationReport();
            lastMissingAssets = MissingAssetsReport.atOpen(
                    dawProject, deserializer.getMissingFiles(), projectDirectory);
            lastUnreadItems = deserializer.getUnreadItems();
            pendingBackup = PendingBackup.at(lastMigrationReport, deserializer.losesSavedContent());
        } else {
            ProjectMetadata metadata = parseProjectFile(content, projectDirectory);
            currentProject = metadata;
            currentDawProject = null;
            lastMigrationReport = MigrationReport.noOp(
                    com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry.CURRENT_VERSION);
            pendingBackup = PendingBackup.NONE;
            // The deserializer did not run: its missing-files and unread-items
            // lists are the previous open's, and this file has nothing to report.
            lastMissingAssets = MissingAssetsReport.EMPTY;
            lastUnreadItems = List.of();
        }

        readOnly = openedReadOnly;
        recentProjects.put(projectDirectory.toString(), currentProject);
        recordRecentProject(projectDirectory);
        checkpointManager.start(projectDirectory);
        return currentProject;
    }

    private static String describe(ProjectLock lock) {
        if (lock == null) {
            return "another session";
        }
        return lock.user() + "@" + lock.hostname() + " (pid " + lock.pid() + ")";
    }

    /**
     * Saves the current project state. If a {@link DawProject} has been set
     * via {@link #saveDawProject(DawProject)}, the full project state is
     * serialized to XML. Otherwise, only metadata is written.
     *
     * @throws IOException    if the project cannot be saved
     * @throws IllegalStateException if no project is currently open
     */
    public void saveProject() throws IOException {
        if (currentProject == null) {
            throw new IllegalStateException("No project is currently open");
        }
        if (readOnly) {
            throw new IOException("Project was opened read-only; use Save As to a new location");
        }
        lockManager.verifyOurs();
        currentProject = currentProject.touch();
        if (currentDawProject != null) {
            currentDawProject.setMetadata(currentProject);
            writeDawProjectFile(currentDawProject, currentProject.projectPath());
            currentDawProject.markClean();
        } else {
            writeProjectFile(currentProject);
        }
        checkpointManager.performCheckpoint();
    }

    /**
     * Saves the full state of the given {@link DawProject} to disk. The project
     * is associated with this manager and its metadata is updated.
     *
     * @param dawProject the project to save
     * @throws IOException if the project cannot be saved
     * @throws IllegalStateException if no project is currently open
     */
    public void saveDawProject(DawProject dawProject) throws IOException {
        Objects.requireNonNull(dawProject, "dawProject must not be null");
        if (currentProject == null) {
            throw new IllegalStateException("No project is currently open");
        }
        if (readOnly) {
            throw new IOException("Project was opened read-only; use Save As to a new location");
        }
        lockManager.verifyOurs();
        currentDawProject = dawProject;
        currentProject = currentProject.touch();
        dawProject.setMetadata(currentProject);
        writeDawProjectFile(dawProject, currentProject.projectPath());
        dawProject.markClean();
        checkpointManager.performCheckpoint();
    }

    /**
     * Closes the current project after saving.
     *
     * @throws IOException if the final save fails
     */
    public void closeProject() throws IOException {
        if (currentProject == null) {
            return;
        }
        if (!readOnly) {
            try {
                saveProject();
            } catch (LockStolenException e) {
                // Lock was stolen — preserve user data by skipping the final save;
                // the UI is expected to have already prompted Save As.
            }
        }
        checkpointManager.stop();
        try {
            lockManager.release();
        } catch (IOException ignored) {
            // best-effort cleanup
        }
        currentProject = null;
        currentDawProject = null;
        readOnly = false;
        pendingBackup = PendingBackup.NONE;
        lastMigrationReport = MigrationReport.noOp(
                com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry.CURRENT_VERSION);
        lastMissingAssets = MissingAssetsReport.EMPTY;
        lastUnreadItems = List.of();
    }

    /**
     * Abandons the current project without saving. Use this when the user
     * has explicitly chosen to discard unsaved changes.
     */
    public void abandonProject() {
        if (currentProject == null) {
            return;
        }
        checkpointManager.stop();
        try {
            lockManager.release();
        } catch (IOException ignored) {
            // best-effort cleanup
        }
        currentProject = null;
        currentDawProject = null;
        readOnly = false;
        pendingBackup = PendingBackup.NONE;
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
        if (currentDawProject != null) {
            return currentDawProject.isDirty();
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

    private void writeDawProjectFile(DawProject dawProject, Path projectDir) throws IOException {
        if (projectDir == null) {
            return;
        }
        Path projectFile = projectDir.resolve(PROJECT_FILE_NAME);
        // If the project was opened from a legacy schema and migrated in
        // memory, or its open could not honour everything the file saved,
        // the user's choice to save is the commit point — preserve the
        // original file as a sibling backup before overwriting it. The
        // backup is taken at most once per load and never overwrites an
        // existing backup so repeated saves remain idempotent. A copy that
        // fails fails the save with project.daw untouched, and the next save
        // tries again; a file that is gone owes no backup, so the first write
        // settles it either way and no later save backs up this load's own
        // output.
        switch (pendingBackup) {
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
        pendingBackup = PendingBackup.NONE;
        String xml = serializer.serialize(dawProject);
        Files.writeString(projectFile, xml);
    }

    /**
     * Backs {@code projectFile} up under {@code tag}, if there is a file to
     * back up, and logs {@code written} followed by {@code ": <backup path>"}
     * at INFO. The line counts nothing: every loss that arms a backup was
     * logged at WARNING during the open, so it says why the backup exists and
     * points to those lines.
     */
    private static void backUpBeforeFirstWrite(Path projectFile, String tag, String written) throws IOException {
        if (Files.exists(projectFile)) {
            Path backup = writeSiblingBackup(projectFile, tag);
            LOG.info(() -> written + ": " + backup);
        }
    }

    /**
     * Copies {@code projectFile} to the sibling
     * {@code project.daw.<tag>.<yyyyMMdd-HHmmss UTC>.bak}, or
     * {@code ….<stamp>-<n>.bak} when that name is taken (two saves within a
     * second), never overwriting an existing backup.
     *
     * @param projectFile the project file to back up
     * @param tag         {@code v<fromVersion>} for a migrated open — the name
     *                    the Migration History, the hub's health scan and the
     *                    roll-back look for — or {@code degraded}, which none
     *                    of them matches
     * @return the backup written
     */
    private static Path writeSiblingBackup(Path projectFile, String tag) throws IOException {
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(java.time.ZoneOffset.UTC)
                .format(Instant.now());
        Path backup = projectFile.resolveSibling(
                projectFile.getFileName() + "." + tag + "." + stamp + ".bak");
        // Never clobber an existing backup (e.g. two opens within a second).
        int n = 0;
        while (Files.exists(backup)) {
            n++;
            backup = projectFile.resolveSibling(
                    projectFile.getFileName() + "." + tag + "." + stamp + "-" + n + ".bak");
        }
        Files.copy(projectFile, backup, StandardCopyOption.COPY_ATTRIBUTES);
        return backup;
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
        Files.writeString(projectFile, content);
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

    /**
     * The sibling backup the first write of an opened project owes
     * ({@link #pendingBackup}), decided by {@link #at} when the project is
     * opened.
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
