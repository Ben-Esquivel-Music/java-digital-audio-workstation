package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.persistence.journal.JournalRecord;
import com.benesquivelmusic.daw.core.persistence.journal.ProjectContext;
import com.benesquivelmusic.daw.core.project.DawProject;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.GUARD;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 331, ruling L3: Recover Session is the one switch pending from the FX
 * step that begins it until its last load passes the switch point — through
 * the scan, the recovery dialog, Restore checkpoint only's journal delete and
 * Recover everything's replay. A switch asked meanwhile is refused with one
 * INFO, "Still recovering the session — Recover Session continues when the
 * recovery finishes.", rate-limited like a door's; a Ctrl+S is not refused.
 * Each worker hands over to its load's switch point in one FX step, so the
 * recovery's own switch points never refuse it. Recover asked while another
 * switch is pending is refused with that switch's INFO, before the recording
 * and take refusal. A worker that throws — anything — takes its failure path
 * and leaves nothing pending. Cancel and Inspect events… at the recovery dialog
 * end the recovery with nothing pending and the open project still open; a
 * recovery dialog that cannot be built is logged at SEVERE, shown as the ERROR
 * "Recovery failed: &lt;reason&gt;", and the Welcome window comes back. A late
 * load that already did something, refused because another switch is pending,
 * says so in a WARNING every time (ruling L3 (b)).
 *
 * <p>Each recovery worker is held, when a test chooses, at the controller's
 * seam before its work ({@code setBeforeRecoveryWork}); the real recovery
 * dialog opens over a project with a real checkpoint and journal and is
 * answered on a later FX turn. The Welcome screen a cancelled recovery
 * re-shows is handed to a presenter that shows nothing.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class RecoverSessionIsTheOnePendingSwitchTest {

    private static final String RECOVERY_DIALOG_TITLE = "Recover unsaved work";
    private static final String CAPTURED_EVENTS_TITLE = "Captured events";
    private static final String RECOVER_EVERYTHING = "Recover everything";
    private static final String RESTORE_CHECKPOINT_ONLY = "Restore checkpoint only";
    private static final String INSPECT_EVENTS = "Inspect events…";
    private static final String CANCEL = "Cancel";
    private static final int JOURNALED_CHANGES = 3;

    @TempDir
    Path workspace;

    private SaveFlowFixture fixture;
    /** The recovery worker held at its start, by name; {@code null} holds none. */
    private volatile String heldWorker;
    private final CountDownLatch workerHeld = new CountDownLatch(1);
    private final CountDownLatch workerReleased = new CountDownLatch(1);
    /** What a recovery worker throws at its start, by name; {@code null} throws nothing. */
    private volatile String throwingWorker;
    private final List<String> workersStarted = new CopyOnWriteArrayList<>();
    /** The recovery dialog's answer, and what the test does while the dialog asks (on the FX thread). */
    private volatile String recoveryAnswer = RESTORE_CHECKPOINT_ONLY;
    private volatile Runnable whileTheDialogAsks = () -> { };
    private final AtomicReference<Throwable> answerFailure = new AtomicReference<>();
    private final AtomicBoolean takeBeingWritten = new AtomicBoolean();
    /** The Inspect events… windows shown, and the Welcome windows the recovery showed again. */
    private final AtomicInteger eventWindowsShown = new AtomicInteger();
    private final AtomicInteger welcomesShown = new AtomicInteger();

    private final ListChangeListener<Window> answerRecoveryDialogs = change -> {
        while (change.next()) {
            for (Window window : change.getAddedSubList()) {
                if (window instanceof Stage stage && RECOVERY_DIALOG_TITLE.equals(stage.getTitle())) {
                    Platform.runLater(() -> {
                        whileTheDialogAsks.run();
                        answer(stage, recoveryAnswer);
                    });
                } else if (window instanceof Stage stage && CAPTURED_EVENTS_TITLE.equals(stage.getTitle())) {
                    // Inspect events…'s window: read, then closed with its one button.
                    Platform.runLater(() -> {
                        eventWindowsShown.incrementAndGet();
                        answer(stage, null);
                    });
                }
            }
        }
    };

    @BeforeEach
    void openSongA() throws Exception {
        fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT);
        runOnFx(() -> {
            Window.getWindows().addListener(answerRecoveryDialogs);
            fixture.lifecycle().setTakeBeingWrittenCheck(takeBeingWritten::get);
            fixture.lifecycle().setBeforeRecoveryWork(this::atTheStartOfARecoveryWorker);
            fixture.lifecycle().setWelcomePresenter(_ -> welcomesShown.incrementAndGet());
        });
    }

    @AfterEach
    void close() throws Exception {
        workerReleased.countDown();
        try {
            runOnFx(() -> {
                Window.getWindows().removeListener(answerRecoveryDialogs);
                for (Window window : List.copyOf(Window.getWindows())) {
                    if (window instanceof Stage stage && RECOVERY_DIALOG_TITLE.equals(stage.getTitle())) {
                        stage.hide();
                    }
                }
            });
        } finally {
            fixture.close();
        }
    }

    @Test
    void theStringsAreTheRulingsOwn() {
        assertThat(ProjectLifecycleController.STILL_RECOVERING_MESSAGE)
                .isEqualTo("Still recovering the session — Recover Session continues when the recovery finishes.");
        assertThat(ProjectLifecycleController.lateSwitchRefusedMessage(
                "3 recovered changes were written to C:\\Projects\\Song B\\project.daw",
                ProjectLifecycleController.OPEN_PROJECT_DOOR))
                .isEqualTo("3 recovered changes were written to C:\\Projects\\Song B\\project.daw; the project was not"
                        + " opened, because Open Project is replacing the open project — open it later with File ▸"
                        + " Open Project…");
    }

    @Test
    void aSwitchAskedWhileTheScanRunsIsRefusedWithOneInfoAndACtrlSIsNot() throws Exception {
        Path songC = fixture.anotherProjectOnDisk("Song C");
        heldWorker = ProjectLifecycleController.RECOVERY_SCAN_WORKER;
        AtomicInteger switchesRun = new AtomicInteger();
        runOnFx(() -> fixture.project.get().markDirty());

        runOnFx(() -> fixture.lifecycle().beginRecovery(songC));
        awaitTheHeldWorker();
        runOnFx(() -> {
            for (int click = 0; click < 3; click++) {
                fixture.lifecycle().whenProjectMayClose(ProjectLifecycleController.OPEN_PROJECT_DOOR,
                        switchesRun::incrementAndGet);
            }
            fixture.lifecycle().whenProjectMayClose(ProjectLifecycleController.RESTORE_SNAPSHOT_DOOR,
                    switchesRun::incrementAndGet);
            fixture.lifecycle().onSaveProject();
        });
        SaveOutcomes.awaitIdle(fixture.projectManager);

        assertThat(fixture.notifications()).as("one INFO for every switch asked during the scan").singleElement()
                .satisfies(entry -> {
                    assertThat(entry.level()).isEqualTo(NotificationLevel.INFO);
                    assertThat(entry.message()).isEqualTo(ProjectLifecycleController.STILL_RECOVERING_MESSAGE);
                });
        assertThat(fixture.publishes.done(SaveFlowFixture::isHead)).as("the Ctrl+S was written into Song A")
                .singleElement().satisfies(publish -> assertThat(publish.target().getParent()).isEqualTo(fixture.songA));

        workerReleased.countDown();
        awaitTheOpenProject(songC);
        assertThat(switchesRun).as("no refused switch ran").hasValue(0);
    }

    @Test
    void aSwitchAskedWhileTheRecoveryDialogAsksIsRefusedAndTheRecoveryGoesOn() throws Exception {
        Path songB = aProjectWithACheckpointAndAJournal("Song B");
        AtomicBoolean doorRan = new AtomicBoolean();
        whileTheDialogAsks = () -> fixture.lifecycle().whenProjectMayClose(
                ProjectLifecycleController.OPEN_PROJECT_DOOR, () -> doorRan.set(true));
        recoveryAnswer = RESTORE_CHECKPOINT_ONLY;

        runOnFx(() -> fixture.lifecycle().beginRecovery(songB));

        awaitTheOpenProject(songB);
        assertThat(answerFailure.get()).as("the recovery dialog was answered").isNull();
        assertThat(doorRan).isFalse();
        assertThat(fixture.notifications()).extracting(NotificationEntry::message)
                .contains(ProjectLifecycleController.STILL_RECOVERING_MESSAGE);
    }

    @Test
    void aSwitchAskedWhileTheJournalIsDeletedIsRefusedAndTheLastCleanSaveOpens() throws Exception {
        Path songB = aProjectWithACheckpointAndAJournal("Song B");
        heldWorker = ProjectLifecycleController.RECOVERY_DISCARD_WORKER;
        recoveryAnswer = RESTORE_CHECKPOINT_ONLY;

        runOnFx(() -> fixture.lifecycle().beginRecovery(songB));
        awaitTheHeldWorker();
        assertThat(onFx(() -> DoorGate.proceedsAtOnce(fixture.lifecycle())))
                .as("a door during the journal delete is refused").isFalse();

        assertThat(fixture.notifications()).singleElement().satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(NotificationLevel.INFO);
            assertThat(entry.message()).isEqualTo(ProjectLifecycleController.STILL_RECOVERING_MESSAGE);
        });
        workerReleased.countDown();
        awaitTheOpenProject(songB);
        assertThat(ProjectContext.forProject(songB).journalDirectory()).as("the journal was discarded")
                .doesNotExist();
    }

    @Test
    void aSwitchAskedWhileTheReplayRunsIsRefusedAndTheRecoveredProjectOpens() throws Exception {
        Path songB = aProjectWithACheckpointAndAJournal("Song B");
        heldWorker = ProjectLifecycleController.RECOVERY_REPLAY_WORKER;
        recoveryAnswer = RECOVER_EVERYTHING;

        runOnFx(() -> fixture.lifecycle().beginRecovery(songB));
        awaitTheHeldWorker();
        assertThat(onFx(() -> DoorGate.proceedsAtOnce(fixture.lifecycle())))
                .as("a door during the replay is refused").isFalse();

        assertThat(fixture.notifications()).singleElement().satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(NotificationLevel.INFO);
            assertThat(entry.message()).isEqualTo(ProjectLifecycleController.STILL_RECOVERING_MESSAGE);
        });
        workerReleased.countDown();
        awaitTheOpenProject(songB);
        assertThat(onFx(() -> fixture.project.get().getTracks().stream().map(track -> track.getName()).toList()))
                .as("the recovered project — its checkpoint's track — opened").contains("Vocals");
    }

    @Test
    void recoverAskedWhileAnotherSwitchIsPendingIsRefusedWithThatSwitchsInfoBeforeTheTakeRefusal()
            throws Exception {
        Path songC = fixture.anotherProjectOnDisk("Song C");
        SaveFlowFixture.Publishes.Hold head = fixture.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.save();
        head.awaitHeld();
        CountDownLatch door = onFx(() -> DoorGate.whenItProceeds(fixture.lifecycle()));
        takeBeingWritten.set(true);

        runOnFx(() -> fixture.lifecycle().beginRecovery(songC));

        assertThat(fixture.notifications()).as("the pending switch's INFO, not the take's WARNING").singleElement()
                .satisfies(entry -> {
                    assertThat(entry.level()).isEqualTo(NotificationLevel.INFO);
                    assertThat(entry.message()).isEqualTo("Still saving — " + DoorGate.TEST_DOOR
                            + " continues when the save finishes.");
                });
        assertThat(workersStarted).as("no scan was started").isEmpty();
        takeBeingWritten.set(false);
        head.release();
        assertThat(door.await(GUARD.toMillis(), TimeUnit.MILLISECONDS)).as("the waiting door goes on").isTrue();
    }

    @Test
    void aScanThatThrowsOpensTheLastCleanSaveAndLeavesNothingPending() throws Exception {
        Path songC = fixture.anotherProjectOnDisk("Song C");
        throwingWorker = ProjectLifecycleController.RECOVERY_SCAN_WORKER;

        runOnFx(() -> fixture.lifecycle().beginRecovery(songC));

        awaitTheOpenProject(songC);
        assertThat(onFx(() -> DoorGate.proceedsAtOnce(fixture.lifecycle()))).as("nothing is left pending").isTrue();
    }

    @Test
    void aJournalDeleteThatThrowsTakesTheNotDeletedPathAndLeavesNothingPending() throws Exception {
        Path songB = aProjectWithACheckpointAndAJournal("Song B");
        throwingWorker = ProjectLifecycleController.RECOVERY_DISCARD_WORKER;
        recoveryAnswer = RESTORE_CHECKPOINT_ONLY;

        runOnFx(() -> fixture.lifecycle().beginRecovery(songB));

        awaitTheOpenProject(songB);
        assertThat(ProjectContext.forProject(songB).journalDirectory()).as("the journal was not deleted")
                .isDirectory();
        assertThat(onFx(() -> DoorGate.proceedsAtOnce(fixture.lifecycle()))).as("nothing is left pending").isTrue();
    }

    @Test
    void aReplayThatThrowsTakesTheFailedReplaysPathWithItsReasonAndLeavesNothingPending() throws Exception {
        Path songB = aProjectWithACheckpointAndAJournal("Song B");
        throwingWorker = ProjectLifecycleController.RECOVERY_REPLAY_WORKER;
        recoveryAnswer = RECOVER_EVERYTHING;

        runOnFx(() -> fixture.lifecycle().beginRecovery(songB));

        awaitTheOpenProject(songB);
        fixture.awaitOnFx(() -> fixture.shown.getEntries().stream()
                .anyMatch(entry -> entry.level() == NotificationLevel.ERROR), "the failed replay's ERROR");
        assertThat(fixture.notifications()).extracting(NotificationEntry::message)
                .anyMatch(message -> message.startsWith("Recovery failed (injected failure of "
                        + ProjectLifecycleController.RECOVERY_REPLAY_WORKER + ")"));
        assertThat(onFx(() -> DoorGate.proceedsAtOnce(fixture.lifecycle()))).as("nothing is left pending").isTrue();
    }

    @Test
    void aLateLoadThatAlreadyDidSomethingIsRefusedWithAWarningEveryTimeWhileAnotherSwitchIsPending()
            throws Exception {
        Path songB = fixture.anotherProjectOnDisk("Song B");
        Path projectFile = songB.resolve("project.daw");
        SaveFlowFixture.Publishes.Hold head = fixture.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.save();
        head.awaitHeld();
        CountDownLatch door = onFx(() -> DoorGate.whenItProceeds(fixture.lifecycle()));

        runOnFx(() -> {
            fixture.lifecycle().openRecoveredProject(songB, projectFile, 3, List.of());
            fixture.lifecycle().openRecoveredProject(songB, projectFile, 3, List.of());
            fixture.lifecycle().openLastCleanSaveAfterRecoveredWriteFailed(songB, "disk full");
            fixture.lifecycle().openLastCleanSaveAfterFailedReplay(songB, "bad record");
        });

        String replacing = "; the project was not opened, because " + DoorGate.TEST_DOOR
                + " is replacing the open project — open it later with File ▸ Open Project…";
        assertThat(fixture.notifications()).as("a WARNING each time, never rate-limited")
                .allSatisfy(entry -> assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING))
                .extracting(NotificationEntry::message).containsExactly(
                        "3 recovered changes were written to " + projectFile + replacing,
                        "3 recovered changes were written to " + projectFile + replacing,
                        "Could not write recovered project: disk full" + replacing,
                        "Recovery failed (bad record)" + replacing);
        head.release();
        assertThat(door.await(GUARD.toMillis(), TimeUnit.MILLISECONDS)).as("the waiting door goes on").isTrue();
        fixture.settle();
        assertThat(onFx(() -> fixture.projectManager.getCurrentProject().projectPath()))
                .as("no refused late load ran").isEqualTo(fixture.songA);
    }

    @Test
    void cancelAtTheRecoveryDialogEndsTheRecoveryWithNothingPendingAndSongAStillOpen() throws Exception {
        Path songB = aProjectWithACheckpointAndAJournal("Song B");
        DawProject songAModel = onFx(fixture.project::get);
        recoveryAnswer = CANCEL;

        runOnFx(() -> fixture.lifecycle().beginRecovery(songB));
        fixture.awaitOnFx(() -> welcomesShown.get() == 1, "the Welcome window shown again");

        assertTheRecoveryEndedWithoutALoad(songAModel);
        assertThat(ProjectContext.forProject(songB).journalDirectory()).as("nothing was discarded").isDirectory();
    }

    @Test
    void inspectEventsAtTheRecoveryDialogEndsTheRecoveryWithNothingPendingAndSongAStillOpen() throws Exception {
        Path songB = aProjectWithACheckpointAndAJournal("Song B");
        DawProject songAModel = onFx(fixture.project::get);
        recoveryAnswer = INSPECT_EVENTS;

        runOnFx(() -> fixture.lifecycle().beginRecovery(songB));
        fixture.awaitOnFx(() -> welcomesShown.get() == 1, "the Welcome window shown again");

        assertThat(eventWindowsShown).as("the captured events were shown").hasValue(1);
        assertTheRecoveryEndedWithoutALoad(songAModel);
    }

    @Test
    void aRecoveryDialogThatCannotBeBuiltIsLoggedAndShownAndTheWelcomeWindowComesBack() throws Exception {
        Path songB = aProjectWithACheckpointAndAJournal("Song B");
        DawProject songAModel = onFx(fixture.project::get);
        IllegalStateException broken = new IllegalStateException("the recovery dialog could not be built");
        List<LogRecord> severe = new CopyOnWriteArrayList<>();
        Handler recorder = new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                if (logRecord.getLevel() == Level.SEVERE) {
                    severe.add(logRecord);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger controllerLog = Logger.getLogger(ProjectLifecycleController.class.getName());
        controllerLog.addHandler(recorder);
        try {
            runOnFx(() -> fixture.lifecycle().setRecoveryDialogs(_ -> {
                throw broken;
            }));

            runOnFx(() -> fixture.lifecycle().beginRecovery(songB));
            fixture.awaitOnFx(() -> welcomesShown.get() == 1, "the Welcome window shown again");
        } finally {
            controllerLog.removeHandler(recorder);
        }

        assertThat(severe).as("logged at SEVERE with its stack").singleElement()
                .satisfies(entry -> assertThat(entry.getThrown()).isSameAs(broken));
        assertThat(fixture.notifications()).singleElement().satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
            assertThat(entry.message()).isEqualTo("Recovery failed: the recovery dialog could not be built");
        });
        assertThat(onFx(fixture.project::get)).as("Song A is still open").isSameAs(songAModel);
        assertThat(onFx(() -> DoorGate.proceedsAtOnce(fixture.lifecycle()))).as("nothing is left pending").isTrue();
    }

    /** Recover Session ended at its dialog: nothing pending, no notification, Song A still the open project. */
    private void assertTheRecoveryEndedWithoutALoad(DawProject songAModel) throws Exception {
        assertThat(answerFailure.get()).as("the recovery dialog was answered").isNull();
        assertThat(fixture.notifications()).as("nothing to say").isEmpty();
        assertThat(onFx(fixture.project::get)).as("Song A is still open").isSameAs(songAModel);
        assertThat(onFx(() -> fixture.projectManager.getCurrentProject().projectPath())).isEqualTo(fixture.songA);
        assertThat(onFx(() -> DoorGate.proceedsAtOnce(fixture.lifecycle())))
                .as("nothing is left pending: the next door proceeds at once").isTrue();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** The seam at the start of each recovery worker: records it, then holds or throws as the test chose. */
    private void atTheStartOfARecoveryWorker(String worker) {
        workersStarted.add(worker);
        if (worker.equals(throwingWorker)) {
            throw new IllegalStateException("injected failure of " + worker);
        }
        if (worker.equals(heldWorker)) {
            workerHeld.countDown();
            try {
                if (!workerReleased.await(GUARD.toMillis() * 3, TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("the held " + worker + " was never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while held", e);
            }
        }
    }

    private void awaitTheHeldWorker() throws InterruptedException {
        assertThat(workerHeld.await(GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .as("the %s reached its seam within %s", heldWorker, GUARD).isTrue();
    }

    private void awaitTheOpenProject(Path expected) throws Exception {
        fixture.awaitOnFx(() -> fixture.currentProjectIs(expected),
                "the recovery opened " + expected.getFileName());
    }

    /**
     * Answers the dialog shown in {@code stage} with the button labelled
     * {@code label}, or with its first button when {@code label} is
     * {@code null}. FX thread.
     */
    private void answer(Stage stage, String label) {
        try {
            Node root = stage.getScene().getRoot();
            DialogPane pane = root instanceof DialogPane direct ? direct : (DialogPane) root.lookup(".dialog-pane");
            ButtonType choice = pane.getButtonTypes().stream()
                    .filter(type -> label == null || type.getText().equals(label))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the dialog offers '" + label + "'"));
            ((Button) pane.lookupButton(choice)).fire();
        } catch (Throwable t) {
            answerFailure.set(t);
            stage.hide();
        }
    }

    /**
     * A saved project on disk with a checkpoint of one track, "Vocals" (the
     * verified session-start base holds the same document), and a journal of
     * {@link #JOURNALED_CHANGES} changes after it.
     */
    private Path aProjectWithACheckpointAndAJournal(String name) throws IOException {
        Path directory = fixture.anotherProjectOnDisk(name);
        DawProject checkpointed = new DawProject(name, AudioFormat.CD_QUALITY);
        checkpointed.createAudioTrack("Vocals");
        ProjectContext context = ProjectContext.forProject(directory);
        Files.createDirectories(context.checkpointDirectory());
        // Only this checkpoint: the save that made the project wrote one of its own.
        for (Path written : SaveFlowFixture.checkpointFiles(directory)) {
            Files.delete(written);
        }
        String checkpointXml = new ProjectSerializer().serialize(checkpointed);
        Files.writeString(context.checkpointDirectory().resolve("checkpoint-999-20260101T000000.daw"),
                checkpointXml, StandardCharsets.UTF_8);
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        Files.createDirectories(context.journalDirectory());
        Path base = Files.writeString(context.journalDirectory().resolve(
                com.benesquivelmusic.daw.core.persistence.journal.JournalState.BASE_FILE), checkpointXml);
        byte[] baseHash = com.benesquivelmusic.daw.core.persistence.journal.JournalState.hash(base)
                .getBytes(StandardCharsets.UTF_8);
        new JournalRecord(0L, Instant.EPOCH, JournalRecord.SESSION_START_TYPE, null, baseHash)
                .writeTo(java.nio.channels.Channels.newChannel(frames));
        for (int sequence = 0; sequence < JOURNALED_CHANGES; sequence++) {
            ByteBuffer frame = JournalRecord.ofMutation(sequence + 1L, Instant.ofEpochMilli(1_000L + sequence),
                    "MixerEvent.MuteChanged", UUID.randomUUID(), null).toBuffer();
            byte[] bytes = new byte[frame.remaining()];
            frame.get(bytes);
            frames.writeBytes(bytes);
        }
        Files.createDirectories(context.journalDirectory());
        Files.write(context.journalDirectory().resolve("segment-000.bin"), frames.toByteArray());
        return directory;
    }
}
