package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.hub.ProjectCard;
import com.benesquivelmusic.daw.app.ui.hub.ProjectHubView;
import com.benesquivelmusic.daw.app.ui.hub.WelcomeView;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.RecentProjectsStore;
import com.benesquivelmusic.daw.core.persistence.archive.ProjectArchiver;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
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
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 323 review (the user's decision "Refuse while recording"): the
 * Project Hub's Open and the Welcome screen's Continue replace the open
 * project, so each passes {@code ProjectLifecycleController.confirmProjectMayClose()},
 * which refuses while a recording is in flight — first, with one WARNING
 * ({@link ProjectLifecycleController#PROJECT_CHANGE_WHILE_RECORDING_MESSAGE})
 * and no unsaved-changes prompt, the recording going on untouched — and,
 * once the user has stopped the recording, asks about a dirty project before
 * it is replaced. The recording counterpart of
 * {@code HubAndWelcomeOpenWhileTakeIsWrittenTest}, whose fixture and steps
 * this follows.
 *
 * <p>The recording is real ({@link RecordingInFlightFixture}): a
 * {@link TransportController} over Song A streams an armed track's take into
 * Song A's {@code audio/takes}, and the lifecycle controller asks that
 * controller's {@code isRecordingInFlight()} and {@code isTakeBeingWritten()},
 * as {@code MainController} asks its current one. A {@link RecentProjectsStore}
 * on a throwaway preferences node, removed after each test, makes the Hub and
 * the Welcome screen list Song B. Each door is opened as a user does from the
 * keyboard: Enter on Song B's card. Every FX action runs through
 * {@link Platform#runLater} and is bounded at 5 s — the fixture's Record and
 * Stop too: {@link RecordingInFlightFixture} bounds each of its FX actions at
 * 5 s, and waits on the test thread, bounded at 30 s, for what the take's
 * capture thread does (the take recording once that thread has created its
 * files; nothing being written any more after a Stop); an unsaved-changes prompt
 * is counted and hidden on the FX thread, which ends its {@code showAndWait}
 * as a cancel, so no test waits on a dialog. The cards' background scans are
 * joined, bounded, before the temporary directory is removed.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class HubAndWelcomeOpenWhileRecordingTest {

    private static final String UNSAVED_CHANGES_TITLE = "Unsaved Changes";

    @TempDir
    Path workspace;

    private final AtomicReference<DawProject> project =
            new AtomicReference<>(new DawProject("Song A", AudioFormat.CD_QUALITY));
    private final AtomicInteger rebuilds = new AtomicInteger();
    private final AtomicInteger unsavedChangesPrompts = new AtomicInteger();
    private final NotificationHistoryService shown = new NotificationHistoryService();
    private final List<Thread> cardScans = new CopyOnWriteArrayList<>();
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

    private Preferences recentProjectsNode;
    private ProjectManager projectManager;
    private Path songA;
    private Path songB;
    private ProjectLifecycleController lifecycle;
    private RecordingInFlightFixture recording;

    @BeforeEach
    void openSongAWithSongBInTheRecentProjectsAndRecord() throws Exception {
        recentProjectsNode = Preferences.userRoot()
                .node("daw-test-hub-welcome-open-while-recording-" + System.nanoTime());
        projectManager = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT),
                new RecentProjectsStore(recentProjectsNode));
        songA = projectManager.createProject("Song A", workspace).projectPath();
        project.get().setMetadata(project.get().getMetadata().withPath(songA));
        songB = anotherProjectOnDisk("Song B");
        projectManager.getRecentProjectsStore().addRecentProject(songB);
        recording = RecordingInFlightFixture.audio(project.get());
        TransportController controller = recording.controller();
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
            lifecycle.setTakeBeingWrittenCheck(controller::isTakeBeingWritten);
            lifecycle.setRecordingInFlightCheck(controller::isRecordingInFlight);
        });
        recording.start();
    }

    @AfterEach
    void stopTheRecordingAndCloseTheOpenProject() throws Exception {
        try {
            if (recording != null) {
                recording.close();
            }
        } finally {
            // Each step runs even if the one before it failed (an FX turn
            // past its bound, a scan still alive): nested finally blocks.
            try {
                runOnFx(() -> Window.getWindows().removeListener(dismissUnsavedChangesPrompts));
            } finally {
                try {
                    joinTheCardScans();
                } finally {
                    try {
                        projectManager.abandonProject();
                    } finally {
                        recentProjectsNode.removeNode();
                    }
                }
            }
        }
    }

    /** Joins every card scan the test started, bounded; one still alive fails the test. */
    private void joinTheCardScans() throws InterruptedException {
        for (Thread scan : cardScans) {
            scan.join(TimeUnit.SECONDS.toMillis(5));
        }
        assertThat(cardScans.stream().filter(Thread::isAlive).toList())
                .as("the Project Hub's card scans ended within 5 s").isEmpty();
    }

    @Test
    void theProjectHubsOpenIsRefusedWhileRecordingAndAsksOnceTheRecordingIsStopped() throws Exception {
        AtomicReference<ProjectHubView> presented = new AtomicReference<>();
        runOnFx(() -> {
            lifecycle.setProjectHubPresenter(presented::set);
            lifecycle.onRecentProjects();
        });
        ProjectHubView hub = presented.get();
        assertThat(hub).as("fixture: the Project Hub was presented").isNotNull();
        cardScans.addAll(hub.pendingScans());
        ProjectCard songBCard = onFx(() -> hub.recentCards().stream()
                .filter(card -> songB.equals(hub.pathFor(card)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("fixture: the Hub lists Song B")));

        assertTheDoorIsRefusedFirstThenAsksThenReplaces(() -> pressEnter(songBCard));
    }

    @Test
    void theWelcomeScreensContinueIsRefusedWhileRecordingAndAsksOnceTheRecordingIsStopped() throws Exception {
        AtomicReference<WelcomeView> presented = new AtomicReference<>();
        runOnFx(() -> {
            lifecycle.setWelcomePresenter(presented::set);
            lifecycle.showWelcome();
        });
        WelcomeView welcome = presented.get();
        assertThat(welcome).as("fixture: the Welcome screen was presented").isNotNull();
        cardScans.addAll(welcome.pendingScans());
        int songBIndex = welcome.continuePaths().indexOf(songB);
        assertThat(songBIndex).as("fixture: Song B is a Continue card").isNotNegative();
        ProjectCard songBCard = welcome.continueCards().get(songBIndex);

        assertTheDoorIsRefusedFirstThenAsksThenReplaces(() -> pressEnter(songBCard));
    }

    private void assertTheDoorIsRefusedFirstThenAsksThenReplaces(Runnable openSongB) throws Exception {
        project.get().markDirty();
        DawProject songAModel = project.get();

        runOnFx(openSongB);

        assertThat(unsavedChangesPrompts).as("refused before the unsaved-changes prompt").hasValue(0);
        assertThat(shown.getEntries()).as("one WARNING toast, no modal dialog").singleElement()
                .satisfies(entry -> {
                    assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING);
                    assertThat(entry.message())
                            .isEqualTo(ProjectLifecycleController.PROJECT_CHANGE_WHILE_RECORDING_MESSAGE);
                });
        assertSongAIsStillOpen(songAModel);
        recording.assertStillRecording();

        recording.stop();
        runOnFx(openSongB);
        assertThat(unsavedChangesPrompts)
                .as("once the recording is stopped, the door asks about the unsaved changes before replacing them")
                .hasValue(1);
        assertSongAIsStillOpen(songAModel);
        assertThat(shown.getEntries()).as("the dismissed prompt cancels, and nothing more is shown").hasSize(1);

        project.get().markClean();
        runOnFx(openSongB);
        assertThat(projectManager.getCurrentProject().projectPath())
                .as("non-vacuity: a clean project gives way to Song B through the same door")
                .isEqualTo(songB);
        assertThat(rebuilds).hasValue(1);
        assertThat(unsavedChangesPrompts).hasValue(1);
    }

    private static void pressEnter(ProjectCard card) {
        card.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, "",
                KeyCode.ENTER, false, false, false, false));
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
