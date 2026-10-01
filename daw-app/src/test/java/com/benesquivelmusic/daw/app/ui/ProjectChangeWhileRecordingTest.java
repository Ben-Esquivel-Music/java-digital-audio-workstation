package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;
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
import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioBlock;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.CaptureRequirement;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;

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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Story 323 review (PR #978, Copilot review 5372368457 — the user's
 * decision "Refuse while recording"): before it, opening another project in
 * the middle of a take was not refused; the take went on recording into the
 * replaced project, and nothing published it. Now every in-app door that
 * replaces the open project refuses while a recording is in flight, at the
 * point where it refuses while a take is being written
 * ({@code ProjectChangeWhileTakeIsWrittenTest}): before any dialog, prompt,
 * scan, file copy or abandon, with one WARNING toast
 * ({@link ProjectLifecycleController#PROJECT_CHANGE_WHILE_RECORDING_MESSAGE})
 * and no modal dialog. A load refused after work done off the FX thread
 * says what that work did
 * ({@link ProjectLifecycleController#lateLoadRefusalWhileRecordingMessage}).
 * The recording goes on untouched, and once the user has stopped it — the
 * take published, nothing being written — the same door proceeds. Quitting
 * the application is not one of those doors (story 333).
 *
 * <p>The recording is real ({@link RecordingInFlightFixture}): a
 * {@link TransportController} over Song A streams the armed track's take
 * into Song A's {@code audio/takes} through a real engine on a mock backend
 * (or, in one test, records a MIDI-only take from a stub input), and the
 * lifecycle controller asks that controller's {@code isRecordingInFlight()}
 * and {@code isTakeBeingWritten()}, as {@code MainController} asks its
 * current one ({@code ProjectChangeWhileTakeIsWrittenTest} pins that wiring
 * against {@code MainController}'s source). The tests of the messages
 * themselves, and of which refusal is shown were both checks ever to answer
 * {@code true}, give the lifecycle controller checks of their own and record
 * nothing.</p>
 *
 * <p>Every FX action runs through {@link Platform#runLater} and is bounded:
 * at 5 s, or, for the fixture's Record and Stop, at the longer bound
 * {@link RecordingInFlightFixture} gives them. The recovery test polls for
 * {@link #SCAN_WINDOW}, one FX turn per poll, and the test thread's other
 * waits — for the recording's blocks, for the record start's input check —
 * are bounded, at 10 s at most. An unsaved-changes prompt shown by a test is
 * counted and hidden on the FX thread, which ends its {@code showAndWait} as
 * a cancel, so no test waits on a dialog.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ProjectChangeWhileRecordingTest {

    private static final String UNSAVED_CHANGES_TITLE = "Unsaved Changes";

    /**
     * How long a recovery scan is given to report, as in
     * {@code MigrationRollBackAndRecoveryGuardTest}, whose non-vacuity leg
     * shows a started one does within it.
     */
    private static final Duration SCAN_WINDOW = Duration.ofSeconds(3);

    @TempDir
    Path workspace;

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
    private RecordingInFlightFixture recording;

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
        });
    }

    @AfterEach
    void stopTheRecordingAndCloseTheOpenProject() throws Exception {
        try {
            if (recording != null) {
                recording.close();
            }
        } finally {
            runOnFx(() -> Window.getWindows().removeListener(dismissUnsavedChangesPrompts));
            projectManager.abandonProject();
        }
    }

    // ── the doors, while an audio take is recording ──────────────────────────

    @Test
    void theProjectCloseGateRefusesWhileRecordingAndAsksAboutUnsavedChangesOnceTheRecordingIsStopped()
            throws Exception {
        record(RecordingInFlightFixture.audio(project.get()));
        project.get().markDirty();

        assertThat(onFx(lifecycle::confirmProjectMayClose))
                .as("a door that would replace the open project is refused while recording")
                .isFalse();
        assertThat(unsavedChangesPrompts).as("refused before the unsaved-changes prompt").hasValue(0);
        assertRefusedOnce();
        recording.assertStillRecording();

        recording.stop();
        assertThat(onFx(lifecycle::confirmProjectMayClose))
                .as("fixture: the prompt, dismissed, cancels the change").isFalse();
        assertThat(unsavedChangesPrompts).as("once the recording is stopped, a dirty project prompts").hasValue(1);
        project.get().markClean();
        assertThat(onFx(lifecycle::confirmProjectMayClose)).as("and a clean project proceeds").isTrue();
        assertThat(unsavedChangesPrompts).hasValue(1);
        assertRefusedOnce();
    }

    /** The unsaved-changes prompt is only that prompt: the recording refusal belongs to the gate. */
    @Test
    void theUnsavedChangesPromptAloneRefusesNothingWhileRecording() throws Exception {
        record(RecordingInFlightFixture.audio(project.get()));

        assertThat(onFx(lifecycle::confirmDiscardUnsavedChanges))
                .as("a clean project has nothing to ask about, recording or not").isTrue();
        project.get().markDirty();
        assertThat(onFx(lifecycle::confirmDiscardUnsavedChanges))
                .as("fixture: the prompt, dismissed, cancels the change").isFalse();
        assertThat(unsavedChangesPrompts).as("a dirty project is asked about").hasValue(1);
        assertThat(shown.getEntries()).as("and nothing is refused").isEmpty();
        recording.assertStillRecording();
    }

    @Test
    void newProjectIsRefusedWhileRecordingBeforeItsLocationPromptAndAsksOnceTheRecordingIsStopped()
            throws Exception {
        record(RecordingInFlightFixture.audio(project.get()));
        AtomicInteger locationPrompts = countLocationPrompts();
        DawProject songAModel = project.get();

        runOnFx(lifecycle::onNewProject);

        assertThat(locationPrompts).as("refused before the location prompt").hasValue(0);
        assertSongAIsStillOpen(songAModel);
        assertRefusedOnce();
        recording.assertStillRecording();

        recording.stop();
        runOnFx(lifecycle::onNewProject);
        assertThat(locationPrompts).as("once the recording is stopped, New asks where to create the project")
                .hasValue(1);
    }

    @Test
    void openImportAndRestoreFromArchiveAreRefusedWhileRecordingBeforeTheirChoosers() throws Exception {
        record(RecordingInFlightFixture.audio(project.get()));
        DawProject songAModel = project.get();

        // With no refusal each would go on to a file or directory chooser,
        // which throws here (the root pane is in no scene); refused, they
        // return before it.
        runOnFx(lifecycle::onOpenProject);
        runOnFx(lifecycle::onImportSession);
        runOnFx(lifecycle::onRestoreFromArchive);

        assertSongAIsStillOpen(songAModel);
        assertThat(shown.getEntries()).as("each door is refused with the warning").hasSize(3)
                .allSatisfy(entry -> {
                    assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING);
                    assertThat(entry.message())
                            .isEqualTo(ProjectLifecycleController.PROJECT_CHANGE_WHILE_RECORDING_MESSAGE);
                });
        recording.assertStillRecording();
    }

    @Test
    void aLoadIsRefusedWhileRecordingBeforeTheOpenProjectIsAbandonedAndSucceedsOnceTheRecordingIsStopped()
            throws Exception {
        record(RecordingInFlightFixture.audio(project.get()));
        Path songB = anotherProjectOnDisk("Song B");
        DawProject songAModel = project.get();

        assertThat(onFx(() -> lifecycle.loadProjectFromPath(songB)))
                .as("the loads of the Hub, Welcome and recovery flows are refused too").isFalse();
        assertSongAIsStillOpen(songAModel);
        assertRefusedOnce();
        recording.assertStillRecording();

        recording.stop();
        assertThat(onFx(() -> lifecycle.loadProjectFromPath(songB))).isTrue();
        assertThat(projectManager.getCurrentProject().projectPath()).isEqualTo(songB);
        assertThat(rebuilds).hasValue(1);
    }

    @Test
    void aMigrationRollBackIsRefusedWhileRecordingBeforeTheBackupIsCopiedOverTheProjectFile() throws Exception {
        record(RecordingInFlightFixture.audio(project.get()));
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
        recording.assertStillRecording();
    }

    /**
     * A refused recovery shows one WARNING in the FX turn that asked and
     * leaves Song A open and the recording going on — also once
     * {@link #SCAN_WINDOW} has passed with no second notification and no
     * rebuild. Song C has no journal, so a scan started anyway would post a
     * load: one that {@code loadProjectFromPath} refuses with the same
     * WARNING, or one that opens Song C. Whether such a load lands within the
     * window is a matter of timing;
     * {@code MigrationRollBackAndRecoveryGuardTest.aRecoveryRefusedWhileRecordingStartsNoScanThatReportsOnALaterTurn}
     * pins the before-the-scan property and shows that a started scan
     * reports within the window.
     */
    @Test
    void aRecoveryRefusedWhileRecordingWarnsOnceAndLeavesTheOpenProjectOpen() throws Exception {
        record(RecordingInFlightFixture.audio(project.get()));
        Path songC = anotherProjectOnDisk("Song C");
        DawProject songAModel = project.get();

        runOnFx(() -> lifecycle.beginRecovery(songC));

        assertRefusedOnce();
        assertSongAIsStillOpen(songAModel);
        recording.assertStillRecording();

        boolean reportedLater = awaitWithinTheScanWindow(
                () -> shown.getEntries().size() > 1 || rebuilds.get() > 0);
        assertThat(reportedLater).as("nothing follows the refusal: no second notification, no rebuild").isFalse();
        assertRefusedOnce();
        assertSongAIsStillOpen(songAModel);
        recording.assertStillRecording();
    }

    @Test
    void aSnapshotRestoreIsRefusedWhileRecordingAndRestoresOnceTheRecordingIsStopped() throws Exception {
        AtomicInteger restores = new AtomicInteger();
        // MainController wires the snapshot restore's gate exactly this way
        // (pinned by ProjectChangeWhileTakeIsWrittenTest's source scan).
        SnapshotsController snapshots = new SnapshotsController(new SnapshotBrowserService(),
                new CheckpointManager(AutoSaveConfig.DEFAULT), projectManager,
                new SnapshotsController.Deps(
                        () -> null,
                        project::get,
                        () -> lifecycle == null || lifecycle.confirmProjectMayClose(),
                        (restored, label) -> restores.incrementAndGet()));
        SnapshotEntry entry = snapshots.createCheckpointWithLabel("Before the take");
        record(RecordingInFlightFixture.audio(project.get()));

        runOnFx(() -> snapshots.restoreEntry(entry));

        assertThat(restores).as("no restored project replaces the open one").hasValue(0);
        assertRefusedOnce();
        recording.assertStillRecording();

        recording.stop();
        runOnFx(() -> snapshots.restoreEntry(entry));
        assertThat(restores).as("once the recording is stopped, the restore goes ahead").hasValue(1);
    }

    // ── the loads that follow work off the FX thread ─────────────────────────

    /** The archive is already extracted, so the refusal names the folder it went to. */
    @Test
    void anArchiveRestoreRefusedWhileRecordingNamesTheFolderTheArchiveWentTo() throws Exception {
        record(RecordingInFlightFixture.audio(project.get()));
        Path destination = anotherProjectOnDisk("Song B"); // stands for the extracted archive
        DawProject songAModel = project.get();

        runOnFx(() -> lifecycle.openRestoredArchive(destination, "Song B", 0));

        assertRefusedOnceWith(ProjectLifecycleController.lateLoadRefusalWhileRecordingMessage(
                "The archive was restored to " + destination));
        assertThat(shown.getEntries().getFirst().message()).as("the folder is named")
                .contains(destination.toString());
        assertSongAIsStillOpen(songAModel);
        recording.assertStillRecording();

        recording.stop();
        runOnFx(() -> lifecycle.openRestoredArchive(destination, "Song B", 0));
        assertThat(projectManager.getCurrentProject().projectPath())
                .as("once the recording is stopped, the restored project opens").isEqualTo(destination);
        assertThat(shown.getEntries().getLast().message()).isEqualTo("Restored archive: Song B");
    }

    /** A replay has already written the recovered changes into project.daw; its refusal says so. */
    @Test
    void aReplayRefusedWhileRecordingSaysTheChangesAreInTheProjectFile() throws Exception {
        record(RecordingInFlightFixture.audio(project.get()));
        Path songB = anotherProjectOnDisk("Song B");
        Path projectFile = songB.resolve("project.daw");
        DawProject songAModel = project.get();

        runOnFx(() -> lifecycle.openRecoveredProject(songB, projectFile, 3));

        assertRefusedOnceWith(ProjectLifecycleController.lateLoadRefusalWhileRecordingMessage(
                "3 recovered changes were written to " + projectFile));
        assertSongAIsStillOpen(songAModel);
        recording.assertStillRecording();

        recording.stop();
        runOnFx(() -> lifecycle.openRecoveredProject(songB, projectFile, 3));
        assertThat(projectManager.getCurrentProject().projectPath()).isEqualTo(songB);
        assertThat(shown.getEntries().getLast().message()).isEqualTo("Recovered 3 changes from the journal");
    }

    /** Refused, a failed replay's one WARNING carries the failure and claims no open. */
    @Test
    void aFailedReplayRefusedWhileRecordingCarriesTheFailureAndClaimsNoOpen() throws Exception {
        record(RecordingInFlightFixture.audio(project.get()));
        Path songB = anotherProjectOnDisk("Song B");
        DawProject songAModel = project.get();

        runOnFx(() -> lifecycle.openLastCleanSaveAfterFailedReplay(songB, "injected replay failure"));

        assertRefusedOnceWith(ProjectLifecycleController.lateLoadRefusalWhileRecordingMessage(
                "Recovery failed (injected replay failure)"));
        assertThat(shown.getEntries().getFirst().message()).doesNotContain("opened the last clean save");
        assertSongAIsStillOpen(songAModel);
        recording.assertStillRecording();

        recording.stop();
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

    // ── a MIDI-only take, and a record start's input check ───────────────────

    /** A MIDI-only take is a recording in flight too: its live MIDI recorder counts. */
    @Test
    void aMidiOnlyRecordingRefusesNewProjectAndNewAsksOnceTheRecordingIsStopped() throws Exception {
        record(RecordingInFlightFixture.midiOnly(project.get()));
        AtomicInteger locationPrompts = countLocationPrompts();
        DawProject songAModel = project.get();

        runOnFx(lifecycle::onNewProject);

        assertThat(locationPrompts).as("refused before the location prompt").hasValue(0);
        assertSongAIsStillOpen(songAModel);
        assertRefusedOnce();
        recording.assertStillRecording();

        recording.stop();
        runOnFx(lifecycle::onNewProject);
        assertThat(locationPrompts).as("once the MIDI take is stopped, New asks where to create the project")
                .hasValue(1);
    }

    /**
     * Record starts the take before its session-input check enumerates the
     * devices off the FX thread, so while that check still waits for its
     * device list the recording is already in flight, and a door is refused.
     */
    @Test
    void aDoorAskedWhileTheRecordStartsInputCheckStillWaitsForItsDeviceListIsRefused() throws Exception {
        HeldDeviceListBackend backend = new HeldDeviceListBackend();
        try {
            record(RecordingInFlightFixture.audio(project.get(), backend));
            assertThat(backend.asked.await(5, TimeUnit.SECONDS))
                    .as("fixture: a worker asked for the device list").isTrue();
            Thread inputCheck = onFx(() -> recording.controller().pendingSessionInputCheck()).orElseThrow();
            assertThat(backend.heldCaller).as("fixture: the worker is the record start's input check")
                    .isSameAs(inputCheck);
            assertThat(inputCheck.isAlive()).as("fixture: the input check still waits").isTrue();

            assertThat(onFx(lifecycle::confirmProjectMayClose)).isFalse();

            assertRefusedOnce();
            recording.assertStillRecording();
            backend.release.countDown();
            inputCheck.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(inputCheck.isAlive()).as("fixture: the released input check finished").isFalse();
        } finally {
            backend.release.countDown();
        }
    }

    // ── the messages, at the lifecycle controller ────────────────────────────

    /**
     * The refusal's own words, asked through the lifecycle controller with
     * the recording check answering {@code true} and no transport: it tells
     * the user to stop the recording, and says nothing of a take being
     * written to disk.
     */
    @Test
    void theRecordingRefusalTellsTheUserToStopTheRecording() throws Exception {
        runOnFx(() -> lifecycle.setRecordingInFlightCheck(() -> true));

        assertThat(onFx(lifecycle::confirmProjectMayClose)).isFalse();

        assertRefusedOnce();
        assertThat(ProjectLifecycleController.PROJECT_CHANGE_WHILE_RECORDING_MESSAGE)
                .isEqualTo("The open project can't be replaced while recording — stop the recording first")
                .doesNotContain("disk", "writ");
    }

    /**
     * A late load's refusal while recording, asked the same way: what the
     * work did, that the project was not opened and why, and what to do — and
     * nothing of a take being written to disk.
     */
    @Test
    void aLateLoadRefusedWhileRecordingSaysWhatTheWorkDidAndToStopTheRecordingThenOpenIt() throws Exception {
        Path destination = anotherProjectOnDisk("Song B");
        String alreadyDone = "The archive was restored to " + destination;
        runOnFx(() -> lifecycle.setRecordingInFlightCheck(() -> true));

        runOnFx(() -> lifecycle.openRestoredArchive(destination, "Song B", 0));

        String ownWords = "; the project was not opened, because the open project can't be replaced"
                + " while recording — stop the recording, then open it";
        assertThat(ProjectLifecycleController.lateLoadRefusalWhileRecordingMessage(alreadyDone))
                .isEqualTo(alreadyDone + ownWords);
        assertRefusedOnceWith(alreadyDone + ownWords);
        assertThat(ownWords).doesNotContain("disk", "writ");
        assertThat(projectManager.getCurrentProject().projectPath()).isEqualTo(songA);
    }

    /**
     * A take being written and a recording in flight do not hold together —
     * Record refuses while a take is being written — but were both checks to
     * answer {@code true}, the take being written is named, at a door and at
     * a late load alike.
     */
    @Test
    void whenBothChecksAnswerTrueTheTakeBeingWrittenIsNamed() throws Exception {
        Path destination = anotherProjectOnDisk("Song B");
        runOnFx(() -> {
            lifecycle.setTakeBeingWrittenCheck(() -> true);
            lifecycle.setRecordingInFlightCheck(() -> true);
        });

        assertThat(onFx(lifecycle::confirmProjectMayClose)).isFalse();
        runOnFx(() -> lifecycle.openRestoredArchive(destination, "Song B", 0));

        assertThat(shown.getEntries()).extracting(NotificationEntry::level, NotificationEntry::message)
                .containsExactly(
                        tuple(NotificationLevel.WARNING, ProjectLifecycleController.PROJECT_CHANGE_WHILE_WRITING_MESSAGE),
                        tuple(NotificationLevel.WARNING, ProjectLifecycleController.lateLoadRefusalMessage(
                                "The archive was restored to " + destination)));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Starts {@code fixture}'s recording in Song A, with the lifecycle
     * controller asking its transport controller both checks, as
     * {@code MainController} asks the current one.
     */
    private void record(RecordingInFlightFixture fixture) throws Exception {
        recording = fixture;
        TransportController controller = fixture.controller();
        runOnFx(() -> {
            lifecycle.setTakeBeingWrittenCheck(controller::isTakeBeingWritten);
            lifecycle.setRecordingInFlightCheck(controller::isRecordingInFlight);
        });
        fixture.start();
    }

    /** Stubs New's location prompt as a cancel, counting each time it is asked. */
    private AtomicInteger countLocationPrompts() throws Exception {
        AtomicInteger locationPrompts = new AtomicInteger();
        runOnFx(() -> lifecycle.setNewProjectLocationPrompt(() -> {
            locationPrompts.incrementAndGet();
            return null; // the user cancels the location prompt
        }));
        return locationPrompts;
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
        assertRefusedOnceWith(ProjectLifecycleController.PROJECT_CHANGE_WHILE_RECORDING_MESSAGE);
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

    /**
     * An {@link AudioBackend} wrapping a {@link MockAudioBackend}, through
     * which it opens, streams and closes; its device list, when a thread
     * other than the FX thread asks for it, is handed out only once
     * {@link #release} has been counted down (bounded at 10 s) — the hold of
     * a driver walk that has not answered yet.
     */
    private static final class HeldDeviceListBackend implements AudioBackend {
        private final MockAudioBackend delegate = new MockAudioBackend();
        final CountDownLatch asked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile Thread heldCaller;

        @Override
        public List<AudioDeviceInfo> listDevices() {
            if (!Platform.isFxApplicationThread()) {
                heldCaller = Thread.currentThread();
                asked.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return delegate.listDevices();
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            return true;
        }

        @Override
        public void open(DeviceId device, com.benesquivelmusic.daw.sdk.audio.AudioFormat format, int bufferFrames) {
            delegate.open(device, format, bufferFrames);
        }

        @Override
        public void open(DeviceId device, com.benesquivelmusic.daw.sdk.audio.AudioFormat format, int bufferFrames,
                         CaptureRequirement capture) {
            delegate.open(device, format, bufferFrames);
        }

        @Override
        public int openedInputChannels() {
            return delegate.openedInputChannels();
        }

        @Override
        public Flow.Publisher<AudioBlock> inputBlocks() {
            return delegate.inputBlocks();
        }

        @Override
        public void sink(AudioBlock block) {
            delegate.sink(block);
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
