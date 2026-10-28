package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.hub.ProjectCard;
import com.benesquivelmusic.daw.app.ui.hub.WelcomeView;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;
import com.benesquivelmusic.daw.core.persistence.RecentProjectsStore;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.prefs.Preferences;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Story 331, the Welcome window's gated actions — New project, a Continue
 * card, Open project, Restore archive, Import DAWproject and Recover session
 * (finding: Welcome actions dismiss inside the gate's proceed, as the Project
 * Hub's Open does): an action dismisses the window only once its flow may go
 * on, so one that is refused — here while Open Project's file dialog is the
 * one switch pending (ruling L4) — leaves the window, and its recover row,
 * up. An action that may go on dismisses it, once.
 *
 * <p>The Welcome window is shown in a real stage, whose hides are counted
 * through a {@code WINDOW_HIDDEN} handler. Song C is in the recent projects
 * with the lock of a session that did not exit cleanly, so it is a recover
 * row; Song B is there with no lock, so it is a Continue card. Song C's lock
 * is released before an action that may go on, so a recovery's load opens
 * Song C. Every file dialog an action may go on to is answered with a
 * cancel.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class WelcomeWindowStaysUpWhenItsActionIsRefusedTest {

    /** A gated action of the Welcome window. */
    enum WelcomeAction {
        NEW_PROJECT, CONTINUE, OPEN_PROJECT, RESTORE_ARCHIVE, IMPORT_DAWPROJECT, RECOVER_SESSION
    }

    @TempDir
    Path workspace;

    private Preferences recentProjectsNode;
    private SaveFlowFixture fixture;
    private ProjectLockManager crashedSession;
    private Path songB;
    private Path songC;
    private Stage welcomeStage;
    private WelcomeView welcome;
    private final AtomicInteger hides = new AtomicInteger();
    private final List<String> recoveryWorkersStarted = new CopyOnWriteArrayList<>();
    /** The file dialogs an action went on to, by the door's name. */
    private final List<String> dialogsAsked = new CopyOnWriteArrayList<>();

    @BeforeEach
    void showTheWelcomeWindowWithARecoverRowAndAContinueCard() throws Exception {
        recentProjectsNode = Preferences.userRoot().node("daw-test-welcome-stays-up-" + System.nanoTime());
        fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT, new RecentProjectsStore(recentProjectsNode));
        songB = fixture.anotherProjectOnDisk("Song B");
        songC = fixture.anotherProjectOnDisk("Song C");
        // A session that did not exit cleanly: its lock's last heartbeat is 20 minutes old.
        crashedSession = new ProjectLockManager(InstantSource.fixed(Instant.now().minus(Duration.ofMinutes(20))),
                "ben", "studio-pc", 123L);
        crashedSession.forceAcquire(songC);
        fixture.projectManager.getRecentProjectsStore().addRecentProject(songB);
        fixture.projectManager.getRecentProjectsStore().addRecentProject(songC);
        runOnFx(() -> {
            fixture.lifecycle().setBeforeRecoveryWork(recoveryWorkersStarted::add);
            fixture.lifecycle().setNewProjectLocationPrompt(
                    () -> cancelled(ProjectLifecycleController.NEW_PROJECT_DOOR));
            fixture.lifecycle().setOpenProjectPrompt(() -> cancelled(ProjectLifecycleController.OPEN_PROJECT_DOOR));
            fixture.lifecycle().setArchiveToRestorePrompt(
                    () -> cancelled(ProjectLifecycleController.RESTORE_FROM_ARCHIVE_DOOR));
            fixture.lifecycle().setImportSessionPrompt(
                    () -> cancelled(ProjectLifecycleController.IMPORT_SESSION_DOOR));
            fixture.lifecycle().setWelcomePresenter(view -> {
                welcome = view;
                welcomeStage = new Stage();
                welcomeStage.setTitle("Welcome");
                welcomeStage.setScene(new Scene(view, 900, 600));
                welcomeStage.addEventHandler(WindowEvent.WINDOW_HIDDEN, _ -> hides.incrementAndGet());
                welcomeStage.show();
            });
            fixture.lifecycle().showWelcome();
        });
        WelcomeTestSupport.awaitClassification(welcome);
        assertThat(welcome.recoverPaths()).as("fixture: Song C is a recover row").containsExactly(songC);
        assertThat(welcome.continuePaths()).as("fixture: Song B is a Continue card").contains(songB);
        assertThat(onFx(welcomeStage::isShowing)).as("fixture: the Welcome window shows").isTrue();
    }

    @AfterEach
    void close() throws Exception {
        try {
            for (Thread scan : welcome.pendingScans()) {
                scan.join(TimeUnit.SECONDS.toMillis(5));
            }
            runOnFx(() -> welcomeStage.hide());
            crashedSession.close();
        } finally {
            try {
                fixture.close();
            } finally {
                recentProjectsNode.removeNode();
            }
        }
    }

    @ParameterizedTest
    @EnumSource(WelcomeAction.class)
    void anActionRefusedWhileADoorsFileDialogIsOpenLeavesTheWelcomeWindowShowing(WelcomeAction action)
            throws Exception {
        AtomicBoolean showingAfterTheRefusal = new AtomicBoolean();
        AtomicBoolean taken = new AtomicBoolean();

        runOnFx(() -> {
            fixture.lifecycle().setOpenProjectPrompt(() -> {
                // The action on the Welcome window while Open Project's folder chooser is open.
                if (taken.compareAndSet(false, true)) {
                    take(action);
                    showingAfterTheRefusal.set(welcomeStage.isShowing());
                }
                return null;
            });
            fixture.lifecycle().onOpenProject();
        });

        assertThat(fixture.notifications()).extracting(NotificationEntry::level, NotificationEntry::message)
                .as("the action was refused").containsExactly(tuple(NotificationLevel.INFO,
                        ProjectLifecycleController.doorDialogStillOpenMessage(
                                ProjectLifecycleController.OPEN_PROJECT_DOOR)));
        assertThat(taken).as("fixture: the action was taken while the dialog was open").isTrue();
        assertThat(recoveryWorkersStarted).as("no recovery began").isEmpty();
        assertThat(showingAfterTheRefusal).as("the refused action left the Welcome window up").isTrue();
        assertThat(onFx(welcomeStage::isShowing)).as("and it stays up, its recover row with it").isTrue();
        assertThat(hides).as("never hidden").hasValue(0);
        assertThat(fixture.projectManager.getCurrentProject().projectPath()).as("Song A is still open")
                .isEqualTo(fixture.songA);
    }

    @ParameterizedTest
    @EnumSource(WelcomeAction.class)
    void anActionThatMayGoOnDismissesTheWelcomeWindowOnce(WelcomeAction action) throws Exception {
        crashedSession.release();

        runOnFx(() -> take(action));

        assertThat(onFx(welcomeStage::isShowing)).as("dismissed once the action went on").isFalse();
        switch (action) {
            case NEW_PROJECT -> assertThat(dialogsAsked).containsExactly(ProjectLifecycleController.NEW_PROJECT_DOOR);
            case OPEN_PROJECT -> assertThat(dialogsAsked).containsExactly(ProjectLifecycleController.OPEN_PROJECT_DOOR);
            case RESTORE_ARCHIVE -> assertThat(dialogsAsked)
                    .containsExactly(ProjectLifecycleController.RESTORE_FROM_ARCHIVE_DOOR);
            case IMPORT_DAWPROJECT -> assertThat(dialogsAsked)
                    .containsExactly(ProjectLifecycleController.IMPORT_SESSION_DOOR);
            case CONTINUE -> assertThat(fixture.projectManager.getCurrentProject().projectPath())
                    .as("the Continue card opened Song B").isEqualTo(songB);
            case RECOVER_SESSION -> {
                fixture.awaitOnFx(() -> songC.equals(fixture.projectManager.getCurrentProject().projectPath()),
                        "the recovery opened Song C");
                assertThat(recoveryWorkersStarted).contains(ProjectLifecycleController.RECOVERY_SCAN_WORKER);
            }
        }
        assertThat(hides).as("hidden exactly once").hasValue(1);
    }

    /** Takes {@code action} on the Welcome window, as a click or a key press does. FX thread. */
    private void take(WelcomeAction action) {
        switch (action) {
            case NEW_PROJECT -> startTile("New project").fire();
            case CONTINUE -> pressEnter(continueCardOf(songB));
            case OPEN_PROJECT -> startTile("Open project…").fire();
            case RESTORE_ARCHIVE -> startTile("Restore archive…").fire();
            case IMPORT_DAWPROJECT -> startTile("Import DAWproject").fire();
            case RECOVER_SESSION -> recoverButton().fire();
        }
    }

    /** A file dialog an action went on to, answered with a cancel. */
    private Path cancelled(String door) {
        dialogsAsked.add(door);
        return null;
    }

    /** The Start tile titled {@code title}. FX thread. */
    private Button startTile(String title) {
        return welcome.lookupAll(".tile").stream()
                .filter(node -> node instanceof Button button && button.getAccessibleText().startsWith(title + " — "))
                .map(Button.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the Start tile " + title));
    }

    /** The Continue card of {@code project}. FX thread. */
    private ProjectCard continueCardOf(Path project) {
        int index = welcome.continuePaths().indexOf(project);
        assertThat(index).as("the Continue card of %s", project).isNotNegative();
        return welcome.continueCards().get(index);
    }

    private static void pressEnter(ProjectCard card) {
        card.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, "",
                KeyCode.ENTER, false, false, false, false));
    }

    /** Song C's {@code [Recover session ▸]} button on the Welcome window. FX thread. */
    private Button recoverButton() {
        Node found = welcome.recoverSection().lookup(".welcome-recover-action");
        assertThat(found).as("the recover row's button").isInstanceOf(Button.class);
        return (Button) found;
    }
}
