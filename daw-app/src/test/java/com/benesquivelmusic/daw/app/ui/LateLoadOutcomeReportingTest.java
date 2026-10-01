package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.persistence.archive.ProjectArchiver;
import com.benesquivelmusic.daw.core.persistence.journal.JournalRecord;
import com.benesquivelmusic.daw.core.persistence.journal.ProjectContext;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 323 review probe (verification round 3): the loads that follow work
 * off the FX thread — a journal discard, a journal replay, a replay whose
 * project file could not be written, a replay that failed — are refused, if
 * a take has started being written meanwhile, with ONE WARNING that says what
 * that work already did
 * ({@code ProjectLifecycleController.lateLoadRefusalMessage}); and the FX
 * halves say what their load did when it is not refused.
 *
 * <p>{@code ProjectChangeWhileTakeIsWrittenTest} drives the FX
 * halves ({@code openRecoveredProject}, {@code openLastCleanSaveAfterFailedReplay},
 * {@code openRestoredArchive}) directly, with a load that succeeds; no test
 * ran a recovery of a project with a journal, so nothing drove the calls from
 * the replay to those halves, or the discard and the failed-write refusals
 * that stay inline. Here the flow runs for real: {@code beginRecovery} scans
 * a project with a real checkpoint and journal (frames written with
 * {@link JournalRecord#toBuffer()}, which is what a segment holds), the real
 * {@code RecoveryDialog} opens, and the test answers it. A take starts being
 * written while the dialog is open — after the recovery's own up-front
 * refusal has let it through — so the refusal that follows is the late one.
 * A replay's project-file write is made to fail by a directory standing where
 * its temporary file goes ({@code Files.writeString} on a directory throws an
 * IOException: AccessDeniedException on Windows, "Is a directory" on Linux);
 * a replay is made to fail by a checkpoint that is not a project.</p>
 *
 * <p>The same four late refusals are made while a recording is in flight
 * (story 323 review, the user's decision "Refuse while recording"): a real
 * recording ({@link RecordingInFlightFixture}) starts while the dialog is
 * open, instead of a take starting to be written, and the one WARNING is
 * the recording form
 * ({@code ProjectLifecycleController.lateLoadRefusalWhileRecordingMessage});
 * the recording goes on untouched.</p>
 *
 * <p>Same fixture as {@code ProjectChangeWhileTakeIsWrittenTest}.
 * Every FX action runs through {@link Platform#runLater} and is bounded at
 * 5 s, or, for the fixture's Stop, at the longer bound
 * {@link RecordingInFlightFixture} gives it; the flow's outcome is polled
 * for on the test thread, bounded by
 * {@link #OUTCOME_BUDGET} (the scan, the replay and the discard are small
 * file operations). The dialog is answered on a later FX turn than the one
 * that showed it, so no test waits on a dialog; a dialog still open when a
 * test ends is hidden, and the Welcome screen a cancelled recovery re-shows is
 * handed to a presenter that shows nothing.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class LateLoadOutcomeReportingTest {

    private static final String RECOVERY_DIALOG_TITLE = "Recover unsaved work";
    private static final String RECOVER_EVERYTHING = "Recover everything";
    private static final String RESTORE_CHECKPOINT_ONLY = "Restore checkpoint only";
    private static final int JOURNALED_CHANGES = 3;
    /** How long the flow's outcome is polled for, on the test thread. */
    private static final Duration OUTCOME_BUDGET = Duration.ofSeconds(10);

    @TempDir
    Path workspace;

    private final AtomicBoolean takeBeingWritten = new AtomicBoolean(false);
    private final AtomicReference<DawProject> project =
            new AtomicReference<>(new DawProject("Song A", AudioFormat.CD_QUALITY));
    private final AtomicInteger rebuilds = new AtomicInteger();
    private final AtomicInteger recoveryDialogs = new AtomicInteger();
    private final AtomicReference<String> recoveryAnswer = new AtomicReference<>();
    private final AtomicReference<Throwable> answerFailure = new AtomicReference<>();
    private final AtomicReference<Throwable> recordingStartFailure = new AtomicReference<>();
    private final NotificationHistoryService shown = new NotificationHistoryService();
    /**
     * What starts while the user reads the recovery dialog: a take starts
     * being written; in the recording tests, a recording starts instead
     * ({@link #aRecordingStartsWhileTheDialogIsOpen()}).
     */
    private final AtomicReference<Runnable> whileTheDialogIsOpen =
            new AtomicReference<>(() -> takeBeingWritten.set(true));
    private final ListChangeListener<Window> answerRecoveryDialogs = change -> {
        while (change.next()) {
            for (Window window : change.getAddedSubList()) {
                if (window instanceof Stage stage && RECOVERY_DIALOG_TITLE.equals(stage.getTitle())) {
                    recoveryDialogs.incrementAndGet();
                    whileTheDialogIsOpen.get().run();
                    Platform.runLater(() -> answer(stage, recoveryAnswer.get()));
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
            Window.getWindows().addListener(answerRecoveryDialogs);
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
            lifecycle.setWelcomePresenter(_ -> { });
        });
    }

    @AfterEach
    void closeTheOpenProject() throws Exception {
        try {
            if (recording != null) {
                recording.close();
            }
        } finally {
            runOnFx(() -> {
                Window.getWindows().removeListener(answerRecoveryDialogs);
                for (Window window : List.copyOf(Window.getWindows())) {
                    if (window instanceof Stage stage && RECOVERY_DIALOG_TITLE.equals(stage.getTitle())) {
                        stage.hide();
                    }
                }
            });
            projectManager.abandonProject();
        }
    }

    // ── the recovery flow, refused late ──────────────────────────────────────

    @Test
    void aDiscardRefusedAfterTheJournalIsGoneSaysTheJournalWasDiscarded() throws Exception {
        Path songB = aProjectWithACheckpointAndAJournal("Song B", aCheckpointOf("Song B"));
        DawProject songAModel = project.get();
        recoveryAnswer.set(RESTORE_CHECKPOINT_ONLY);

        runOnFx(() -> lifecycle.beginRecovery(songB));

        assertOneWarning(ProjectLifecycleController.lateLoadRefusalMessage(
                "The journal of " + songB + " was discarded"));
        assertThat(onFx(shown::getEntries).getFirst().message())
                .as("the shared suffix in its own words, not only as lateLoadRefusalMessage builds it")
                .contains("the project was not opened", "open it once it has");
        assertThat(ProjectContext.forProject(songB).journalDirectory())
                .as("fixture: the discard deleted the journal before the load was refused").doesNotExist();
        assertSongAIsStillOpen(songAModel);
    }

    @Test
    void aReplayRefusedAfterItWroteTheProjectFileSaysTheRecoveredChangesAreThere() throws Exception {
        Path songB = aProjectWithACheckpointAndAJournal("Song B", aCheckpointOf("Song B"));
        Path projectFile = ProjectContext.forProject(songB).projectFile();
        DawProject songAModel = project.get();
        recoveryAnswer.set(RECOVER_EVERYTHING);

        runOnFx(() -> lifecycle.beginRecovery(songB));

        assertOneWarning(ProjectLifecycleController.lateLoadRefusalMessage(
                JOURNALED_CHANGES + " recovered changes were written to " + projectFile));
        assertThat(recoveredBackupsIn(songB))
                .as("fixture: the replay rewrote project.daw, backing up the prior file").hasSize(1);
        assertSongAIsStillOpen(songAModel);
    }

    @Test
    void aReplayWhoseProjectFileCouldNotBeWrittenIsRefusedWithOneWarningThatCarriesTheFailure()
            throws Exception {
        Path songB = aProjectWithACheckpointAndAJournal("Song B", aCheckpointOf("Song B"));
        // A directory where the replay writes its temporary project file.
        Path occupied = Files.createDirectories(songB.resolve("project.daw.recovering.tmp"));
        Files.writeString(occupied.resolve("occupied"), "not a project file");
        DawProject songAModel = project.get();
        recoveryAnswer.set(RECOVER_EVERYTHING);

        runOnFx(() -> lifecycle.beginRecovery(songB));

        NotificationEntry warning = awaitOneEntry();
        assertThat(warning.level()).isEqualTo(NotificationLevel.WARNING);
        assertThat(warning.message())
                .as("the failure, then the late refusal's own words, in one WARNING")
                .startsWith("Could not write recovered project: ")
                .endsWith(ProjectLifecycleController.lateLoadRefusalMessage(""));
        assertThat(ProjectContext.forProject(songB).journalDirectory())
                .as("fixture: the write failed before the journal was cleared").isDirectory();
        assertSongAIsStillOpen(songAModel);
    }

    @Test
    void aFailedReplayRefusedLateCarriesTheFailureAndClaimsNoOpen() throws Exception {
        Path songB = aProjectWithACheckpointAndAJournal("Song B", "<<<not a project>>>");
        DawProject songAModel = project.get();
        recoveryAnswer.set(RECOVER_EVERYTHING);

        runOnFx(() -> lifecycle.beginRecovery(songB));

        NotificationEntry warning = awaitOneEntry();
        assertThat(warning.level()).isEqualTo(NotificationLevel.WARNING);
        assertThat(warning.message())
                .startsWith("Recovery failed (")
                .endsWith(ProjectLifecycleController.lateLoadRefusalMessage(""))
                .doesNotContain("opened the last clean save");
        assertSongAIsStillOpen(songAModel);
    }

    // ── the recovery flow, refused late while recording ──────────────────────

    @Test
    void aDiscardRefusedWhileRecordingAfterTheJournalIsGoneSaysTheJournalWasDiscarded() throws Exception {
        Path songB = aProjectWithACheckpointAndAJournal("Song B", aCheckpointOf("Song B"));
        DawProject songAModel = project.get();
        recoveryAnswer.set(RESTORE_CHECKPOINT_ONLY);
        aRecordingStartsWhileTheDialogIsOpen();

        runOnFx(() -> lifecycle.beginRecovery(songB));

        assertOneWarningWhileRecording(ProjectLifecycleController.lateLoadRefusalWhileRecordingMessage(
                "The journal of " + songB + " was discarded"));
        assertThat(onFx(shown::getEntries).getFirst().message())
                .as("the recording form's own words, not only as lateLoadRefusalWhileRecordingMessage builds them")
                .contains("the project was not opened", "stop the recording, then open it");
        assertThat(ProjectContext.forProject(songB).journalDirectory())
                .as("fixture: the discard deleted the journal before the load was refused").doesNotExist();
        assertSongAIsStillOpen(songAModel);
    }

    @Test
    void aReplayRefusedWhileRecordingAfterItWroteTheProjectFileSaysTheRecoveredChangesAreThere()
            throws Exception {
        Path songB = aProjectWithACheckpointAndAJournal("Song B", aCheckpointOf("Song B"));
        Path projectFile = ProjectContext.forProject(songB).projectFile();
        DawProject songAModel = project.get();
        recoveryAnswer.set(RECOVER_EVERYTHING);
        aRecordingStartsWhileTheDialogIsOpen();

        runOnFx(() -> lifecycle.beginRecovery(songB));

        assertOneWarningWhileRecording(ProjectLifecycleController.lateLoadRefusalWhileRecordingMessage(
                JOURNALED_CHANGES + " recovered changes were written to " + projectFile));
        assertThat(recoveredBackupsIn(songB))
                .as("fixture: the replay rewrote project.daw, backing up the prior file").hasSize(1);
        assertSongAIsStillOpen(songAModel);
    }

    @Test
    void aReplayWhoseProjectFileCouldNotBeWrittenIsRefusedWhileRecordingWithOneWarningThatCarriesTheFailure()
            throws Exception {
        Path songB = aProjectWithACheckpointAndAJournal("Song B", aCheckpointOf("Song B"));
        // A directory where the replay writes its temporary project file.
        Path occupied = Files.createDirectories(songB.resolve("project.daw.recovering.tmp"));
        Files.writeString(occupied.resolve("occupied"), "not a project file");
        DawProject songAModel = project.get();
        recoveryAnswer.set(RECOVER_EVERYTHING);
        aRecordingStartsWhileTheDialogIsOpen();

        runOnFx(() -> lifecycle.beginRecovery(songB));

        NotificationEntry warning = awaitOneEntryWhileRecording();
        assertThat(warning.level()).isEqualTo(NotificationLevel.WARNING);
        assertThat(warning.message())
                .as("the failure, then the recording refusal's own words, in one WARNING")
                .startsWith("Could not write recovered project: ")
                .endsWith(ProjectLifecycleController.lateLoadRefusalWhileRecordingMessage(""));
        assertThat(ProjectContext.forProject(songB).journalDirectory())
                .as("fixture: the write failed before the journal was cleared").isDirectory();
        assertSongAIsStillOpen(songAModel);
    }

    @Test
    void aFailedReplayRefusedLateWhileRecordingCarriesTheFailureAndClaimsNoOpen() throws Exception {
        Path songB = aProjectWithACheckpointAndAJournal("Song B", "<<<not a project>>>");
        DawProject songAModel = project.get();
        recoveryAnswer.set(RECOVER_EVERYTHING);
        aRecordingStartsWhileTheDialogIsOpen();

        runOnFx(() -> lifecycle.beginRecovery(songB));

        NotificationEntry warning = awaitOneEntryWhileRecording();
        assertThat(warning.level()).isEqualTo(NotificationLevel.WARNING);
        assertThat(warning.message())
                .startsWith("Recovery failed (")
                .endsWith(ProjectLifecycleController.lateLoadRefusalWhileRecordingMessage(""))
                .doesNotContain("opened the last clean save");
        assertSongAIsStillOpen(songAModel);
    }

    // ── the FX halves, not refused ───────────────────────────────────────────

    @Test
    void aFailedReplaysFallbackThatCouldNotOpenTheLastCleanSaveDoesNotClaimItDid() throws Exception {
        Path notAProject = Files.createDirectories(workspace.resolve("Not a project"));

        runOnFx(() -> lifecycle.openLastCleanSaveAfterFailedReplay(notAProject, "injected replay failure"));

        assertThat(onFx(shown::getEntries).getLast()).as("the failure is the last word").satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
            assertThat(entry.message()).isEqualTo(
                    "Recovery failed (injected replay failure); the last clean save could not be opened either");
        });
    }

    @Test
    void aReplayWhoseProjectCouldNotBeOpenedClaimsNoRecovery() throws Exception {
        Path notAProject = Files.createDirectories(workspace.resolve("Not a project"));

        runOnFx(() -> lifecycle.openRecoveredProject(notAProject, notAProject.resolve("project.daw"), 2));

        assertThat(onFx(shown::getEntries))
                .as("the failed load says so, and no SUCCESS claims the recovered changes were opened")
                .isNotEmpty()
                .noneSatisfy(entry -> assertThat(entry.level()).isEqualTo(NotificationLevel.SUCCESS));
        assertThat(onFx(shown::getEntries).getLast().level()).isEqualTo(NotificationLevel.ERROR);
    }

    @Test
    void anArchiveRestoredWithMissingAssetsOpensWithAWarningThatCountsThem() throws Exception {
        Path songB = anotherProjectOnDisk("Song B"); // stands for the extracted archive

        runOnFx(() -> lifecycle.openRestoredArchive(songB, "Song B", 2));

        assertThat(projectManager.getCurrentProject().projectPath()).isEqualTo(songB);
        assertThat(onFx(shown::getEntries).getLast()).satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING);
            assertThat(entry.message()).isEqualTo("Restored archive: Song B (2 missing assets)");
        });
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Answers the recovery dialog shown in {@code stage} with the button labelled {@code label}. */
    private void answer(Stage stage, String label) {
        try {
            Node root = stage.getScene().getRoot();
            DialogPane pane = root instanceof DialogPane direct ? direct : (DialogPane) root.lookup(".dialog-pane");
            ButtonType choice = pane.getButtonTypes().stream()
                    .filter(type -> type.getText().equals(label))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the recovery dialog offers '" + label + "'"));
            ((Button) pane.lookupButton(choice)).fire();
        } catch (Throwable t) {
            answerFailure.set(t);
            stage.hide();
        }
    }

    /** Polls, bounded by {@link #OUTCOME_BUDGET}, for the first notification; then asserts it is the only one. */
    private NotificationEntry awaitOneEntry() throws Exception {
        long deadline = System.nanoTime() + OUTCOME_BUDGET.toNanos();
        while (onFx(shown::size) == 0) {
            assertThat(answerFailure.get()).as("the recovery dialog was answered").isNull();
            assertThat(System.nanoTime() - deadline < 0)
                    .as("the recovery flow reported within %s", OUTCOME_BUDGET).isTrue();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        runOnFx(() -> { }); // one more FX turn: anything posted with the first entry has run
        assertThat(recoveryDialogs).as("fixture: the real recovery dialog was shown once").hasValue(1);
        assertThat(onFx(shown::getEntries)).as("one notification, no modal dialog").hasSize(1);
        return onFx(shown::getEntries).getFirst();
    }

    private void assertOneWarning(String message) throws Exception {
        assertThat(awaitOneEntry()).satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING);
            assertThat(entry.message()).isEqualTo(message);
        });
    }

    /**
     * Records for real ({@link RecordingInFlightFixture}) instead of a take
     * starting to be written: the lifecycle controller asks the recording
     * transport controller both checks, as {@code MainController} asks its
     * current one, and the recording starts while the user reads the
     * recovery dialog — after the recovery's own up-front refusal has let it
     * through — on an FX turn posted before the one that answers the dialog.
     */
    private void aRecordingStartsWhileTheDialogIsOpen() throws Exception {
        recording = RecordingInFlightFixture.audio(project.get());
        TransportController controller = recording.controller();
        runOnFx(() -> {
            lifecycle.setTakeBeingWrittenCheck(controller::isTakeBeingWritten);
            lifecycle.setRecordingInFlightCheck(controller::isRecordingInFlight);
        });
        whileTheDialogIsOpen.set(() -> Platform.runLater(() -> {
            try {
                recording.startOnFx();
            } catch (Throwable t) {
                recordingStartFailure.set(t);
            }
        }));
    }

    /** {@link #awaitOneEntry()}, once the recording started while the dialog was open; it is still recording. */
    private NotificationEntry awaitOneEntryWhileRecording() throws Exception {
        NotificationEntry entry = awaitOneEntry();
        assertThat(recordingStartFailure.get()).as("fixture: the recording started while the dialog was open")
                .isNull();
        recording.assertStillRecording();
        return entry;
    }

    private void assertOneWarningWhileRecording(String message) throws Exception {
        assertThat(awaitOneEntryWhileRecording()).satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING);
            assertThat(entry.message()).isEqualTo(message);
        });
    }

    private void assertSongAIsStillOpen(DawProject songAModel) {
        assertThat(project.get()).as("the open project model is not replaced").isSameAs(songAModel);
        assertThat(projectManager.getCurrentProject()).as("the open project is not abandoned").isNotNull();
        assertThat(projectManager.getCurrentProject().projectPath()).isEqualTo(songA);
        assertThat(rebuilds).as("no UI rebuild for another project").hasValue(0);
    }

    /**
     * A saved project on disk with a checkpoint holding {@code checkpointXml}
     * (the newest, so recovery replays onto it) and a journal of
     * {@link #JOURNALED_CHANGES} changes after it.
     */
    private Path aProjectWithACheckpointAndAJournal(String name, String checkpointXml) throws IOException {
        Path directory = anotherProjectOnDisk(name);
        ProjectContext context = ProjectContext.forProject(directory);
        Files.createDirectories(context.checkpointDirectory());
        Files.writeString(context.checkpointDirectory().resolve("checkpoint-999-20260101T000000.daw"),
                checkpointXml, StandardCharsets.UTF_8);
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        for (int sequence = 0; sequence < JOURNALED_CHANGES; sequence++) {
            ByteBuffer frame = JournalRecord.ofMutation(sequence, Instant.ofEpochMilli(1_000L + sequence),
                    "MixerEvent.MuteChanged", UUID.randomUUID(), null).toBuffer();
            byte[] bytes = new byte[frame.remaining()];
            frame.get(bytes);
            frames.writeBytes(bytes);
        }
        Files.createDirectories(context.journalDirectory());
        Files.write(context.journalDirectory().resolve("segment-000.bin"), frames.toByteArray());
        return directory;
    }

    private static String aCheckpointOf(String name) throws IOException {
        DawProject checkpointed = new DawProject(name, AudioFormat.CD_QUALITY);
        checkpointed.createAudioTrack("Vocals");
        return new ProjectSerializer().serialize(checkpointed);
    }

    private static List<Path> recoveredBackupsIn(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(p -> p.getFileName().toString().startsWith("project.daw.recovered."))
                    .toList();
        }
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
