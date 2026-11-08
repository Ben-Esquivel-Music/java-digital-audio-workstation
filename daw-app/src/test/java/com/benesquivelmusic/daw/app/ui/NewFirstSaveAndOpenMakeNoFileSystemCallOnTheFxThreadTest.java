package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.status.ProjectJournalCoordinator;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;
import com.benesquivelmusic.daw.core.session.SessionManager;
import com.benesquivelmusic.daw.core.session.SessionManifestStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, Goals — Tests, "Off-FX" (design §4.1, §4.5): File ▸ New, the
 * first Ctrl+S of a project never saved, and File ▸ Open make no file-system
 * call on the JavaFX application thread. The application's wiring as the
 * tests can build it — the recovery gate on, the write-ahead journal on, the
 * folder-changed hook {@code MainController} runs — over a
 * {@link ThreadRecordingFileSystem} on the test's folder: every file-system
 * call any of it makes is recorded with its thread, and none may be the FX
 * thread's.
 *
 * <p>Non-vacuity, per flow: the flow's named disk work is recorded on a worker
 * — New and the first save: the new folder's {@code createDirectory}, its
 * {@code .project.lock} and its {@code project.daw} publishes; Open: the
 * {@code project.daw} read and the {@code .project.lock} publish — and every
 * file and folder that appeared under the test's folder is one the recording
 * file system created, so nothing escaped onto the default file system
 * unrecorded (COV-S-11). New also opens the new folder's session manifest and
 * registers its checkpoints with the snapshot browser once the folder exists
 * (COV-S-3). Kills: {@code createProject} back on the FX thread; a disk read
 * in the location prompt; {@code MigrationSuppression} read on the FX thread
 * (by {@code DoorsMakeNoFileSystemCallOnTheFxThreadTest}, which also scans what
 * this flow does not reach: the native chooser's own default).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class NewFirstSaveAndOpenMakeNoFileSystemCallOnTheFxThreadTest {

    @TempDir
    Path temp;

    private ThreadRecordingFileSystem files;
    private SaveFlowFixture fixture;
    private DefaultEventBus bus;
    private ProjectJournalCoordinator journal;

    @BeforeEach
    void openSongAWithTheGateAndTheJournalOn() throws Exception {
        files = ThreadRecordingFileSystem.over(temp);
        bus = new DefaultEventBus();
        fixture = new SaveFlowFixture(files.root(), AutoSaveConfig.DEFAULT);
        journal = new ProjectJournalCoordinator(fixture.progress, onFx(NotificationBar::new), fixture.dispatcher,
                () -> true, () -> bus, fixture.checkpoints, new SessionManager(), InstantSource.system());
        runOnFx(() -> fixture.lifecycle().enableRecoveryGate(() -> true, () -> {
            Path journalDirectory = journal.currentProjectDirectory();
            journal.detachFromBus();
            return () -> journal.closeCurrentFor(journalDirectory, null);
        }, journal::openForRequired));
        journal.openFor(fixture.songA);
        fixture.awaitCheckpointsSeeded(fixture.checkpoints.runningGeneration().orElseThrow() - 1);
    }

    @AfterEach
    void close() throws Exception {
        try {
            journal.drainWithoutSeal(null);
            fixture.close();
        } finally {
            bus.close();
        }
    }

    @Test
    void newCreatesItsFolderAndOpensItsSessionOffTheFxThread() throws Exception {
        Path parent = Files.createDirectories(files.root().resolve("new"));
        installMainControllersFolderChangedStep();
        Set<Path> before = everythingUnder(temp);
        files.clear();

        runOnFx(() -> {
            fixture.lifecycle().setNewProjectLocationPrompt(() -> parent);
            fixture.lifecycle().onNewProject();
        });
        fixture.awaitOnFx(() -> !fixture.lifecycle().isProjectInstallationPending()
                && fixture.projectManager.getCurrentProject() != null
                && parent.equals(fixture.projectManager.getCurrentProject().projectPath().getParent()),
                "the new project installed");
        Path created = fixture.projectManager.getCurrentProject().projectPath();
        Path sessions = created.resolve(SessionManifestStore.SESSIONS_DIR_NAME);
        // Listed through the real path: the test's own look must not be recorded on the FX thread.
        Path realSessions = ThreadRecordingFileSystem.real(sessions);
        fixture.awaitOnFx(() -> manifestsIn(realSessions) > 0 && journal.isOpen(), "its session manifest and journal");
        fixture.settle();

        assertThat(files.callsOn(fixture.fxThread())).as("New makes no file-system call on the FX thread").isEmpty();
        Path real = ThreadRecordingFileSystem.real(created);
        assertThat(files.callsOf("createDirectory", real)).as("the new folder, created on the create's worker")
                .singleElement().satisfies(call -> assertThat(call.thread().getName())
                        .isEqualTo(ProjectLifecycleController.PROJECT_CREATE_WORKER));
        assertThat(files.callsOf("move", real.resolve(ProjectLockManager.LOCK_FILE_NAME)))
                .as("its lock, published off the FX thread").isNotEmpty();
        assertThat(files.callsOf("move", real.resolve("project.daw")))
                .as("its project.daw: the create's metadata head, then the initial save").hasSize(2);
        assertThat(onFx(() -> fixture.snapshots().service().getAutosaveDirectories()))
                .as("its checkpoints are in the snapshot browser").contains(created.resolve("checkpoints"));
        assertNothingEscaped(before);
    }

    @Test
    void aFirstSaveCreatesItsFolderOffTheFxThread() throws Exception {
        journal.drainWithoutSeal(null);
        fixture.projectManager.closeProject().get(SaveFlowFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS);
        Path parent = Files.createDirectories(files.root().resolve("first save"));
        runOnFx(() -> fixture.project.get().markDirty());
        Set<Path> before = everythingUnder(temp);
        files.clear();

        runOnFx(() -> {
            fixture.lifecycle().setNewProjectLocationPrompt(() -> parent);
            fixture.lifecycle().onSaveProject();
        });
        fixture.awaitOnFx(() -> fixture.projectManager.getCurrentProject() != null
                && !onFxDirty(), "the first save");
        fixture.settle();
        Path created = fixture.projectManager.getCurrentProject().projectPath();

        assertThat(files.callsOn(fixture.fxThread())).as("a first save makes no file-system call on the FX thread")
                .isEmpty();
        Path real = ThreadRecordingFileSystem.real(created);
        assertThat(files.callsOf("createDirectory", real)).singleElement().satisfies(call ->
                assertThat(call.thread().getName()).isEqualTo(ProjectLifecycleController.PROJECT_CREATE_WORKER));
        assertThat(files.callsOf("move", real.resolve(ProjectLockManager.LOCK_FILE_NAME))).isNotEmpty();
        assertThat(files.callsOf("move", real.resolve("project.daw")))
                .as("the create's metadata head, then the save's, on the save writer")
                .hasSize(2).last().satisfies(call -> assertThat(call.thread().getName()).isEqualTo("daw-save-writer"));
        assertNothingEscaped(before);
    }

    @Test
    void anOpenReadsAndLocksTheProjectOffTheFxThread() throws Exception {
        Path songB = fixture.anotherProjectOnDisk("Song B");
        Set<Path> before = everythingUnder(temp);
        files.clear();

        runOnFx(() -> {
            fixture.lifecycle().setOpenProjectPrompt(() -> songB);
            fixture.lifecycle().onOpenProject();
        });
        fixture.awaitOnFx(() -> fixture.currentProjectIs(songB) && !fixture.lifecycle().isProjectInstallationPending()
                && journal.isOpen(), "Song B open, its journal too");
        fixture.settle();

        assertThat(files.callsOn(fixture.fxThread())).as("an open makes no file-system call on the FX thread")
                .isEmpty();
        Path real = ThreadRecordingFileSystem.real(songB);
        assertThat(files.calls()).as("its project.daw read on a worker").anySatisfy(call -> {
            assertThat(call.path()).isEqualTo(real.resolve("project.daw").toAbsolutePath().normalize());
            assertThat(call.operation()).isIn("newByteChannel(read)", "newInputStream");
            assertThat(call.thread()).isNotSameAs(fixture.fxThread());
        });
        assertThat(writesTo(real.resolve(ProjectLockManager.LOCK_FILE_NAME)))
                .as("its lock, written off the FX thread").isNotEmpty()
                .allSatisfy(call -> assertThat(call.thread()).isNotSameAs(fixture.fxThread()));
        assertNothingEscaped(before);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** The calls that wrote or created {@code realPath}: a publish onto it, or a write of it. */
    private List<ThreadRecordingFileSystem.Call> writesTo(Path realPath) {
        Path target = realPath.toAbsolutePath().normalize();
        return files.calls().stream().filter(call -> call.path().equals(target)
                && (call.operation().equals("move") || call.operation().endsWith("(write)")
                || call.operation().equals("newOutputStream"))).toList();
    }

    private boolean onFxDirty() {
        return fixture.project.get().isDirty();
    }

    /**
     * The application's folder-changed hook: {@code MainController}'s own
     * step, over a controller built without FXML whose non-final fields are
     * set (never a final one, JEP 500).
     */
    private void installMainControllersFolderChangedStep() throws Exception {
        MainController main = onFx(() -> {
            MainController controller = new MainController(new SessionManager());
            setField(controller, "projectManager", fixture.projectManager);
            setField(controller, "snapshotsController", fixture.snapshots());
            setField(controller, "sessionManagerDock", new SessionManagerDock());
            setField(controller, "project", fixture.project.get());
            return controller;
        });
        Method folderChanged = MainController.class.getDeclaredMethod("projectFolderChanged");
        folderChanged.setAccessible(true);
        runOnFx(() -> fixture.lifecycle().setProjectFolderChangedHook(() -> {
            try {
                folderChanged.invoke(main);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }));
    }

    private static void setField(MainController target, String name, Object value) {
        try {
            Field field = MainController.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    /** Every file and folder that appeared under the test's folder was made through the recording file system. */
    private void assertNothingEscaped(Set<Path> before) throws Exception {
        Set<Path> appeared = new HashSet<>(everythingUnder(temp));
        appeared.removeAll(before);
        assertThat(appeared).as("non-vacuity: the flow made files").isNotEmpty();
        assertThat(appeared).as("every new file and folder was created through the recording file system")
                .isSubsetOf(files.created());
    }

    private static Set<Path> everythingUnder(Path folder) throws Exception {
        try (Stream<Path> entries = Files.walk(folder)) {
            return entries.filter(entry -> !entry.equals(folder))
                    .map(entry -> entry.toAbsolutePath().normalize())
                    .collect(Collectors.toSet());
        }
    }

    private static long manifestsIn(Path sessions) {
        try (Stream<Path> found = Files.list(sessions)) {
            return found.filter(file -> file.getFileName().toString().endsWith(".session.xml")).count();
        } catch (Exception notYet) {
            return 0;
        }
    }
}
