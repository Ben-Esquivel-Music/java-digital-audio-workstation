package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.archive.ProjectArchiver;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationReport;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.snapshot.SnapshotBrowserService;
import com.benesquivelmusic.daw.core.snapshot.SnapshotEntry;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 323 review (PR #978, Copilot review 5365941737, the fix round's F1):
 * every Stop of an audio take leaves the take FINALIZING (Recording
 * Reliability book §5.2 — FINALIZING returns to IDLE only once the seal has
 * completed) until the FX turn its capture thread's termination posts has
 * run — the only turn that publishes the take, into the project it was
 * recorded in; a start that was cancelled, or that failed once its take
 * directory existed, counts the same way until the FX turn after the
 * removal of its files has run, whether or not everything could be
 * removed. So no in-app door replaces the open
 * project meanwhile:
 * {@link ProjectLifecycleController} refuses each of them with a WARNING toast
 * ({@link ProjectLifecycleController#PROJECT_CHANGE_WHILE_WRITING_MESSAGE}),
 * before the unsaved-changes prompt and before anything is abandoned, and
 * behaves as before once the take has been written. A load refused after
 * work off the FX thread has already changed something on disk says what
 * that work did ({@link ProjectLifecycleController#lateLoadRefusalMessage}).
 * Quitting the application is not one of those doors: nothing guards it
 * until story 333.
 *
 * <p>The take-being-written check is the controller's seam
 * ({@code setTakeBeingWrittenCheck}); what feeds it in the app is
 * {@code MainController}, which is never FXML-loaded in tests (it starts the
 * audio engine, the autosave scheduler and scene listeners). Its wiring is
 * therefore pinned against its source, as {@code MainControllerShrinkTest}
 * and {@code MainControllerInitializationOrderTest} do, and the behaviour it
 * wires is tested in-process: here for the guard, and in
 * {@code TransportControllerTest} for {@code TransportController.retire()} and
 * {@code isTakeBeingWritten()}.</p>
 *
 * <p>Every FX action runs through {@link Platform#runLater} and is bounded
 * at 5 s. An unsaved-changes prompt shown by a test is recorded and hidden
 * on the FX thread, which ends its {@code showAndWait}, so no test waits on
 * a dialog.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ProjectChangeWhileTakeIsWrittenTest {

    private static final String UNSAVED_CHANGES_TITLE = "Unsaved Changes";

    /**
     * How long a recovery scan is given to report, as in
     * {@code MigrationRollBackAndRecoveryGuardTest}, whose non-vacuity leg
     * shows a started one does within it.
     */
    private static final Duration SCAN_WINDOW = Duration.ofSeconds(3);

    @TempDir
    Path workspace;

    private final AtomicBoolean takeBeingWritten = new AtomicBoolean(true);
    private final AtomicReference<DawProject> project =
            new AtomicReference<>(new DawProject("Song A", AudioFormat.CD_QUALITY));
    private final AtomicInteger rebuilds = new AtomicInteger();
    private final AtomicInteger unsavedChangesPrompts = new AtomicInteger();
    private final NotificationHistoryService shown = new NotificationHistoryService();
    private final ListChangeListener<Window> dismissUnsavedChangesPrompts = change -> {
        while (change.next()) {
            for (Window window : change.getAddedSubList()) {
                if (window instanceof Stage stage && UNSAVED_CHANGES_TITLE.equals(stage.getTitle())) {
                    unsavedChangesPrompts.incrementAndGet();
                    Platform.runLater(window::hide);
                }
            }
        }
    };

    private ProjectManager projectManager;
    private Path songA;
    private ProjectLifecycleController lifecycle;

    @BeforeEach
    void openSongA() throws Exception {
        projectManager = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT));
        songA = projectManager.createProject("Song A", workspace).projectPath();
        project.get().setMetadata(project.get().getMetadata().withPath(songA));
        runOnFx(() -> {
            Window.getWindows().addListener(dismissUnsavedChangesPrompts);
            NotificationBar notificationBar = new NotificationBar();
            notificationBar.setAnimated(false);
            notificationBar.setHistoryService(shown);
            AtomicReference<UndoManager> undoManager = new AtomicReference<>(new UndoManager());
            lifecycle = new ProjectLifecycleController(projectManager, new SessionInterchangeController(),
                    notificationBar, new ProjectOperationProgress(new FxDispatcher()), new BorderPane(),
                    new VBox(),
                    new ProjectLifecycleController.Deps(project::get, project::set, undoManager::get,
                            undoManager::set, () -> { }, () -> { }, rebuilds::incrementAndGet,
                            () -> null, _ -> { }),
                    new ProjectArchiver());
            lifecycle.setTakeBeingWrittenCheck(takeBeingWritten::get);
        });
    }

    @AfterEach
    void closeTheOpenProject() throws Exception {
        // The project is abandoned even if the FX turn overruns its bound.
        try {
            runOnFx(() -> Window.getWindows().removeListener(dismissUnsavedChangesPrompts));
        } finally {
            projectManager.abandonProject();
        }
    }

    @Test
    void theProjectCloseGateRefusesFirstWithAWarningAndAsksAboutUnsavedChangesOnceTheTakeIsWritten()
            throws Exception {
        project.get().markDirty();

        assertThat(onFx(lifecycle::confirmProjectMayClose))
                .as("a door that would replace the open project is refused while a take is being written")
                .isFalse();
        assertThat(unsavedChangesPrompts)
                .as("refused before the unsaved-changes prompt, which a take being written does not trip")
                .hasValue(0);
        assertRefusedOnce();

        takeBeingWritten.set(false);
        assertThat(onFx(lifecycle::confirmProjectMayClose))
                .as("fixture: the prompt, dismissed, cancels the change").isFalse();
        assertThat(unsavedChangesPrompts).as("once the take is written, a dirty project prompts again").hasValue(1);
        project.get().markClean();
        assertThat(onFx(lifecycle::confirmProjectMayClose))
                .as("and a clean project proceeds").isTrue();
        assertThat(unsavedChangesPrompts).hasValue(1);
        assertRefusedOnce();
    }

    /**
     * The unsaved-changes prompt is only that prompt: the refusal of a take
     * being written belongs to the gate that asks it
     * ({@code confirmProjectMayClose}), so a caller that wants the prompt
     * alone does not inherit a refusal its name does not announce.
     */
    @Test
    void theUnsavedChangesPromptAloneRefusesNothingWhileATakeIsBeingWritten() throws Exception {
        assertThat(onFx(lifecycle::confirmDiscardUnsavedChanges))
                .as("a clean project has nothing to ask about, take or no take").isTrue();

        project.get().markDirty();
        assertThat(onFx(lifecycle::confirmDiscardUnsavedChanges))
                .as("fixture: the prompt, dismissed, cancels the change").isFalse();
        assertThat(unsavedChangesPrompts).as("a dirty project is asked about").hasValue(1);
        assertThat(shown.getEntries()).as("and nothing is refused").isEmpty();
    }

    @Test
    void newProjectIsRefusedBeforeItsLocationPromptAndOpensItOnceTheTakeIsWritten() throws Exception {
        AtomicInteger locationPrompts = new AtomicInteger();
        runOnFx(() -> lifecycle.setNewProjectLocationPrompt(() -> {
            locationPrompts.incrementAndGet();
            return null; // the user cancels the location prompt
        }));
        DawProject songAModel = project.get();

        runOnFx(lifecycle::onNewProject);

        assertThat(locationPrompts).as("refused before the location prompt").hasValue(0);
        assertSongAIsStillOpen(songAModel);
        assertRefusedOnce();

        takeBeingWritten.set(false);
        runOnFx(lifecycle::onNewProject);
        assertThat(locationPrompts).as("once the take is written, New asks where to create the project").hasValue(1);
    }

    @Test
    void openImportAndRestoreFromArchiveAreRefusedBeforeTheirChoosers() throws Exception {
        DawProject songAModel = project.get();

        // With no refusal each would go on to a file or directory chooser;
        // refused, they return before it.
        runOnFx(lifecycle::onOpenProject);
        runOnFx(lifecycle::onImportSession);
        runOnFx(lifecycle::onRestoreFromArchive);

        assertSongAIsStillOpen(songAModel);
        assertThat(shown.getEntries()).as("each door is refused with the warning").hasSize(3)
                .allSatisfy(entry -> {
                    assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING);
                    assertThat(entry.message())
                            .isEqualTo(ProjectLifecycleController.PROJECT_CHANGE_WHILE_WRITING_MESSAGE);
                });
    }

    @Test
    void aLoadIsRefusedBeforeTheOpenProjectIsAbandonedAndSucceedsOnceTheTakeIsWritten() throws Exception {
        Path songB = anotherProjectOnDisk("Song B");
        DawProject songAModel = project.get();

        assertThat(onFx(() -> lifecycle.loadProjectFromPath(songB)))
                .as("the recovery and archive-restore loads are refused too").isFalse();
        assertSongAIsStillOpen(songAModel);
        assertRefusedOnce();

        takeBeingWritten.set(false);
        assertThat(onFx(() -> lifecycle.loadProjectFromPath(songB))).isTrue();
        assertThat(projectManager.getCurrentProject().projectPath()).isEqualTo(songB);
        assertThat(rebuilds).hasValue(1);
    }

    @Test
    void aMigrationRollBackIsRefusedBeforeTheBackupIsCopiedOverTheProjectFile() throws Exception {
        Path projectFile = songA.resolve("project.daw");
        String before = Files.readString(projectFile, StandardCharsets.UTF_8);
        Path backup = Files.writeString(songA.resolve("project.daw.v0.19700101-000000.bak"), "a backup");
        MigrationReport report = new MigrationReport(0, 1, List.of(), Instant.now());
        DawProject songAModel = project.get();

        runOnFx(() -> lifecycle.rollbackMigration(songA, report, backup));

        assertThat(Files.readString(projectFile, StandardCharsets.UTF_8))
                .as("project.daw is untouched: disk and memory still agree").isEqualTo(before);
        assertSongAIsStillOpen(songAModel);
        assertRefusedOnce();
    }

    /**
     * A refused recovery shows one WARNING in the FX turn that asked and
     * leaves Song A open — also once {@link #SCAN_WINDOW} has passed with no
     * second notification and no rebuild. Song C has no journal, so a scan
     * started anyway would post a load: one that {@code loadProjectFromPath}
     * refuses with the same WARNING, or one that opens Song C. Whether such a
     * load lands within the window is a matter of timing;
     * {@code MigrationRollBackAndRecoveryGuardTest.aRefusedRecoveryStartsNoScanThatReportsOnALaterTurn}
     * pins the before-the-scan property and shows that a started scan
     * reports within the window.
     */
    @Test
    void aRefusedRecoveryWarnsOnceAndLeavesTheOpenProjectOpen() throws Exception {
        Path songC = anotherProjectOnDisk("Song C");
        DawProject songAModel = project.get();

        runOnFx(() -> lifecycle.beginRecovery(songC));

        assertRefusedOnce();
        assertSongAIsStillOpen(songAModel);

        boolean reportedLater = awaitWithinTheScanWindow(
                () -> shown.getEntries().size() > 1 || rebuilds.get() > 0);
        assertThat(reportedLater).as("nothing follows the refusal: no second notification, no rebuild").isFalse();
        assertRefusedOnce();
        assertSongAIsStillOpen(songAModel);
    }

    /**
     * The archive restore's load comes after the archive has been extracted
     * off the FX thread, so its refusal names the folder the archive went to
     * and says it can be opened once the take has been written.
     */
    @Test
    void anArchiveRestoreRefusedAfterItsExtractionNamesTheFolderTheArchiveWentTo() throws Exception {
        Path destination = anotherProjectOnDisk("Song B"); // stands for the extracted archive
        DawProject songAModel = project.get();

        runOnFx(() -> lifecycle.openRestoredArchive(destination, "Song B", 0));

        assertRefusedOnceWith(ProjectLifecycleController.lateLoadRefusalMessage(
                "The archive was restored to " + destination));
        assertThat(shown.getEntries().getFirst().message()).as("the folder is named")
                .contains(destination.toString());
        assertSongAIsStillOpen(songAModel);

        takeBeingWritten.set(false);
        runOnFx(() -> lifecycle.openRestoredArchive(destination, "Song B", 0));
        assertThat(projectManager.getCurrentProject().projectPath())
                .as("once the take is written, the restored project opens").isEqualTo(destination);
        assertThat(shown.getEntries().getLast().message()).isEqualTo("Restored archive: Song B");
    }

    /** A replay has already written the recovered changes into project.daw; its refusal says so. */
    @Test
    void aReplayRefusedAfterItWroteTheProjectFileSaysTheChangesAreThere() throws Exception {
        Path songB = anotherProjectOnDisk("Song B");
        Path projectFile = songB.resolve("project.daw");
        DawProject songAModel = project.get();

        runOnFx(() -> lifecycle.openRecoveredProject(songB, projectFile, 3));

        assertRefusedOnceWith(ProjectLifecycleController.lateLoadRefusalMessage(
                "3 recovered changes were written to " + projectFile));
        assertSongAIsStillOpen(songAModel);

        takeBeingWritten.set(false);
        runOnFx(() -> lifecycle.openRecoveredProject(songB, projectFile, 3));
        assertThat(projectManager.getCurrentProject().projectPath()).isEqualTo(songB);
        assertThat(shown.getEntries().getLast().message()).isEqualTo("Recovered 3 changes from the journal");
    }

    /**
     * A replay that failed falls back to the last clean save, and its ERROR
     * says what that load did — so it is shown after the load, never before
     * it. Refused, the one WARNING carries the failure and claims no open.
     */
    @Test
    void aFailedReplaySaysItOpenedTheLastCleanSaveOnlyOnceTheLoadHasOpenedIt() throws Exception {
        Path songB = anotherProjectOnDisk("Song B");
        DawProject songAModel = project.get();

        runOnFx(() -> lifecycle.openLastCleanSaveAfterFailedReplay(songB, "injected replay failure"));

        assertRefusedOnceWith(ProjectLifecycleController.lateLoadRefusalMessage(
                "Recovery failed (injected replay failure)"));
        assertSongAIsStillOpen(songAModel);

        takeBeingWritten.set(false);
        runOnFx(() -> lifecycle.openLastCleanSaveAfterFailedReplay(songB, "injected replay failure"));
        assertThat(projectManager.getCurrentProject().projectPath()).isEqualTo(songB);
        assertThat(shown.getEntries().getLast())
                .as("the failure is the last word, after the load it reports on")
                .satisfies(entry -> {
                    assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
                    assertThat(entry.message())
                            .isEqualTo("Recovery failed (injected replay failure); opened the last clean save instead");
                });
    }

    @Test
    void aSnapshotRestoreIsRefusedAndRestoresOnceTheTakeIsWritten() throws Exception {
        AtomicInteger restores = new AtomicInteger();
        // MainController wires the snapshot restore's gate exactly this way
        // (SnapshotsController.Deps in MainController.initialize; pinned by
        // the source scan below).
        SnapshotsController snapshots = new SnapshotsController(new SnapshotBrowserService(),
                new CheckpointManager(AutoSaveConfig.DEFAULT), projectManager,
                new SnapshotsController.Deps(
                        () -> null,
                        project::get,
                        () -> lifecycle == null || lifecycle.confirmProjectMayClose(),
                        (restored, label) -> restores.incrementAndGet()));
        SnapshotEntry entry = snapshots.createCheckpointWithLabel("Before the take");

        runOnFx(() -> snapshots.restoreEntry(entry));

        assertThat(restores).as("no restored project replaces the open one").hasValue(0);
        assertRefusedOnce();

        takeBeingWritten.set(false);
        runOnFx(() -> snapshots.restoreEntry(entry));
        assertThat(restores).as("once the take is written, the restore goes ahead").hasValue(1);
    }

    /**
     * {@code MainController}'s wiring points, read from its source with
     * comments and string literals stripped and whitespace collapsed: each
     * check handed to the lifecycle controller — whether a take is being
     * written, and (story 323 review, "Refuse while recording") whether a
     * recording is in flight, which {@code ProjectChangeWhileRecordingTest}
     * drives — reads the transport controller that is current when it is
     * asked (a method reading the field, not a captured controller) and
     * answers exactly that controller's answer — each body is compared whole,
     * so an inverted or widened answer fails; every new transport controller
     * retires the one it replaces, captured before the field is overwritten;
     * and the snapshot restore, whose only way to the guard is the
     * {@code SnapshotsController.Deps} built here, is handed the
     * project-close gate.
     */
    @Test
    void mainControllerFeedsTheCurrentControllersAnswerAndRetiresTheControllerItReplaces() throws IOException {
        Path file = SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java");
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(raw.length()).as("non-vacuity: the MainController source was read").isGreaterThan(50_000);
        String code = SourceScanSupport.stripStringLiterals(SourceScanSupport.stripComments(raw))
                .replaceAll("\\s+", " ");

        String create = methodBody(code, "private void createTransportController()");
        int captured = create.indexOf("TransportController previous = transportController;");
        int replaced = create.indexOf("transportController = new TransportController(");
        int retired = create.indexOf("previous.retire();");
        assertThat(captured).as("the controller being replaced is captured").isNotNegative();
        assertThat(replaced).as("before the field is overwritten").isGreaterThan(captured);
        assertThat(retired).as("and retired once its successor exists").isGreaterThan(replaced);

        assertThat(methodBody(code, "private boolean isTakeBeingWritten()"))
                .as("the check reads the transport controller field on every call and answers its answer as it is")
                .isEqualTo("{ return transportController != null && transportController.isTakeBeingWritten(); }");
        assertThat(methodBody(code, "private boolean isRecordingInFlight()"))
                .as("so does the recording check")
                .isEqualTo("{ return transportController != null && transportController.isRecordingInFlight(); }");
        assertThat(methodBody(code, "private void createProjectLifecycleController()"))
                .as("the lifecycle controller asks those methods, not a captured controller")
                .contains("projectLifecycleController.setTakeBeingWrittenCheck(this::isTakeBeingWritten);")
                .contains("projectLifecycleController.setRecordingInFlightCheck(this::isRecordingInFlight);");

        String snapshotDeps = "new SnapshotsController.Deps(";
        assertThat(code.indexOf(snapshotDeps)).as("MainController builds SnapshotsController.Deps").isNotNegative()
                .as("exactly once").isEqualTo(code.lastIndexOf(snapshotDeps));
        List<String> arguments = arguments(code, code.indexOf(snapshotDeps) + snapshotDeps.length() - 1);
        assertThat(arguments).as("ownerStage, currentProject, the gate, applyRestoredProject").hasSize(4);
        assertThat(arguments.get(2))
                .as("a snapshot restore passes the project-close gate, which refuses while a take is being written")
                .isEqualTo("() -> projectLifecycleController == null"
                        + " || projectLifecycleController.confirmProjectMayClose()");
    }

    /** The top-level arguments of the call whose opening parenthesis is at {@code open}, trimmed. */
    private static List<String> arguments(String code, int open) {
        assertThat(code.charAt(open)).as("an argument list opens at %s", open).isEqualTo('(');
        List<String> arguments = new ArrayList<>();
        int depth = 0;
        int start = open + 1;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '{') {
                depth++;
            } else if (c == ')' || c == '}') {
                if (--depth == 0) {
                    arguments.add(code.substring(start, i).trim());
                    return arguments;
                }
            } else if (c == ',' && depth == 1) {
                arguments.add(code.substring(start, i).trim());
                start = i + 1;
            }
        }
        throw new AssertionError("unbalanced parentheses after " + open);
    }

    /** The brace-balanced body of the method whose declaration starts with {@code signature}. */
    private static String methodBody(String code, String signature) {
        int declaration = code.indexOf(signature);
        assertThat(declaration).as("MainController declares %s", signature).isNotNegative();
        int open = code.indexOf('{', declaration);
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return code.substring(open, i + 1);
            }
        }
        throw new AssertionError("unbalanced braces after " + signature);
    }

    /**
     * Polls {@code condition} (after an FX turn each time) until it holds or
     * {@link #SCAN_WINDOW} ends; a copy of
     * {@code MigrationRollBackAndRecoveryGuardTest}'s helper.
     */
    private static boolean awaitWithinTheScanWindow(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + SCAN_WINDOW.toNanos();
        while (System.nanoTime() < deadline) {
            runOnFx(() -> { });
            if (condition.getAsBoolean()) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
        runOnFx(() -> { });
        return condition.getAsBoolean();
    }

    private void assertRefusedOnce() {
        assertRefusedOnceWith(ProjectLifecycleController.PROJECT_CHANGE_WHILE_WRITING_MESSAGE);
    }

    private void assertRefusedOnceWith(String warning) {
        assertThat(shown.getEntries()).as("one WARNING toast, no modal dialog").singleElement()
                .satisfies(entry -> {
                    assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING);
                    assertThat(entry.message()).isEqualTo(warning);
                });
    }

    private void assertSongAIsStillOpen(DawProject songAModel) {
        assertThat(project.get()).as("the open project model is not replaced").isSameAs(songAModel);
        assertThat(projectManager.getCurrentProject()).as("the open project is not abandoned").isNotNull();
        assertThat(projectManager.getCurrentProject().projectPath()).isEqualTo(songA);
        assertThat(rebuilds).as("no UI rebuild for another project").hasValue(0);
    }

    /** A saved project on disk, created and closed by a manager of its own. */
    private Path anotherProjectOnDisk(String name) throws IOException {
        ProjectManager other = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT));
        Path directory = other.createProject(name, workspace).projectPath();
        other.closeProject();
        return directory;
    }

    private static <T> T onFx(Supplier<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        runOnFx(() -> result.set(action.get()));
        return result.get();
    }

    /** Runs {@code action} on the FX thread and rethrows what it threw; bounded at 5 s. */
    private static void runOnFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                done.countDown();
            }
        });
        assertThat(done.await(5, TimeUnit.SECONDS)).as("the FX action returned within 5 s").isTrue();
        if (thrown.get() instanceof Error error) {
            throw error;
        }
        if (thrown.get() != null) {
            throw new AssertionError("the FX action threw", thrown.get());
        }
    }
}
