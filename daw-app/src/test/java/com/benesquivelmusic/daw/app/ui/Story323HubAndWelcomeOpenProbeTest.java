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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 323 review probe (verification round 3): the Project Hub's Open and
 * the Welcome screen's Continue replace the open project, so each passes
 * {@code ProjectLifecycleController.confirmProjectMayClose()} — the take
 * being written is refused first, with one WARNING and no unsaved-changes
 * prompt, and once the take is written a dirty project is asked about before
 * it is replaced. {@code Story323ProjectChangeWhileTakeIsWrittenTest} drives
 * New, Open, Import, Restore from Archive and the snapshot restore; no test
 * drove these two doors, so a door wired to the prompt alone, or to no gate,
 * went unnoticed.
 *
 * <p>Same fixture as {@code Story323ProjectChangeWhileTakeIsWrittenTest}
 * (a real {@link ProjectLifecycleController}; the check it asks is the test's
 * flag), plus a {@link RecentProjectsStore} on a throwaway preferences node,
 * removed after each test, so the Hub and the Welcome screen list Song B.
 * Each door is opened as a user does from the keyboard: Enter on Song B's
 * card. Every FX action runs through {@link Platform#runLater} and is bounded
 * at 5 s; an unsaved-changes prompt is counted and hidden on the FX thread,
 * which ends its {@code showAndWait} as a cancel, so no test waits on a
 * dialog. The cards' background scans are joined, bounded, before the
 * temporary directory is removed.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class Story323HubAndWelcomeOpenProbeTest {

    private static final String UNSAVED_CHANGES_TITLE = "Unsaved Changes";

    @TempDir
    Path workspace;

    private final AtomicBoolean takeBeingWritten = new AtomicBoolean(true);
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

    @BeforeEach
    void openSongAWithSongBInTheRecentProjects() throws Exception {
        recentProjectsNode = Preferences.userRoot()
                .node("daw-test-story323-hub-welcome-probe-" + System.nanoTime());
        projectManager = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT),
                new RecentProjectsStore(recentProjectsNode));
        songA = projectManager.createProject("Song A", workspace).projectPath();
        project.get().setMetadata(project.get().getMetadata().withPath(songA));
        songB = anotherProjectOnDisk("Song B");
        projectManager.getRecentProjectsStore().addRecentProject(songB);
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
        runOnFx(() -> Window.getWindows().removeListener(dismissUnsavedChangesPrompts));
        for (Thread scan : cardScans) {
            scan.join(TimeUnit.SECONDS.toMillis(5));
        }
        projectManager.abandonProject();
        recentProjectsNode.removeNode();
    }

    @Test
    void theProjectHubsOpenIsRefusedBeforeTheUnsavedChangesPromptAndAsksOnceTheTakeIsWritten()
            throws Exception {
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
    void theWelcomeScreensContinueIsRefusedBeforeTheUnsavedChangesPromptAndAsksOnceTheTakeIsWritten()
            throws Exception {
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

        assertThat(unsavedChangesPrompts)
                .as("refused before the unsaved-changes prompt, which a take being written does not trip")
                .hasValue(0);
        assertThat(shown.getEntries()).as("one WARNING toast, no modal dialog").singleElement()
                .satisfies(entry -> {
                    assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING);
                    assertThat(entry.message())
                            .isEqualTo(ProjectLifecycleController.PROJECT_CHANGE_WHILE_WRITING_MESSAGE);
                });
        assertSongAIsStillOpen(songAModel);

        takeBeingWritten.set(false);
        runOnFx(openSongB);
        assertThat(unsavedChangesPrompts)
                .as("once the take is written, the door asks about the unsaved changes before replacing them")
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
