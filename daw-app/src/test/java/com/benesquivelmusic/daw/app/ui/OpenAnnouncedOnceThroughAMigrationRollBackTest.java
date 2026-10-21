package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry;
import com.benesquivelmusic.daw.core.persistence.migration.ProjectMigration;

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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 329, decision D14 — the re-entrancy fence of the one announcement.
 * {@code loadProjectFromPath} shows the migration report dialog before it
 * announces; that dialog waits in a nested event loop, and its Roll back
 * re-enters the lifecycle controller, which replaces the open. When the
 * dialog returns, the outer load must announce nothing and present nothing:
 * the open it would announce is no longer the current one.
 *
 * <p>Here the real {@link MigrationReportDialog} opens for a project the
 * deserializer migrates (a registry one schema version ahead, with identity
 * steps), and the test answers it as a user does: Roll back…, then OK on
 * the confirmation. With no backup yet, the roll-back abandons the migrated
 * project and says so; the project has a missing source, so an unfenced
 * outer load would add a WARNING and present the report. The confirmed
 * roll-back closes the dialog (decision D32) — nothing here hides it — and
 * only then does the outer load resume. Resumed, it opens no journal for the
 * folder the roll-back abandoned (decision D48).</p>
 *
 * <p>With a backup, the roll-back copies it over the project file and
 * reloads — the reload re-enters the load (decision D41). A reload that
 * fails, the backup unreadable, leaves its one ERROR the only notification:
 * the outer load, superseded, announces nothing and opens no journal. A
 * reload that succeeds migrates again and shows its own dialog only once
 * the first closed (decision D42), announces once, and opens the journal
 * once.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class OpenAnnouncedOnceThroughAMigrationRollBackTest {

    private static final String MIGRATION_DIALOG_TITLE = "Project Migrated";
    private static final String CONFIRMATION_TITLE = "Roll back migration?";

    @TempDir
    Path workspace;

    private OpenProjectFixture fixture;
    private final AtomicReference<Throwable> answerFailure = new AtomicReference<>();
    /** The migration dialogs shown so far: the first is answered Roll back…, any later one OK. */
    private final AtomicInteger migrationDialogs = new AtomicInteger();
    /** Whether a migration dialog appeared while another one was still showing. */
    private final AtomicBoolean stackedDialogs = new AtomicBoolean();
    private final AtomicInteger journalOpens = new AtomicInteger();
    private final ListChangeListener<Window> answerDialogs = change -> {
        while (change.next()) {
            for (Window window : change.getAddedSubList()) {
                if (window instanceof Stage stage && MIGRATION_DIALOG_TITLE.equals(stage.getTitle())) {
                    if (anotherStageTitledIsShowing(MIGRATION_DIALOG_TITLE, stage)) {
                        stackedDialogs.set(true);
                    }
                    // The confirmed roll-back closes the dialog itself, so the
                    // load that showed it resumes — what this test is about. A
                    // reload that migrates again shows its own dialog: OK.
                    ButtonType answer = migrationDialogs.getAndIncrement() == 0
                            ? MigrationReportDialog.ROLLBACK_BUTTON_TYPE : MigrationReportDialog.OK_BUTTON_TYPE;
                    Platform.runLater(() -> press(stage, answer));
                } else if (window instanceof Stage stage && CONFIRMATION_TITLE.equals(stage.getTitle())) {
                    Platform.runLater(() -> press(stage, ButtonType.OK));
                }
            }
        }
    };

    @BeforeEach
    void createTheController() throws Exception {
        MigrationRegistry oneVersionAhead = forcedMigrationRegistry();
        fixture = new OpenProjectFixture(workspace, new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT),
                null, new ProjectLockManager(), new ProjectDeserializer(oneVersionAhead)));
        runOnFx(() -> {
            Window.getWindows().addListener(answerDialogs);
            fixture.lifecycle().setJournalOpenHook(_ -> journalOpens.incrementAndGet());
        });
    }

    @AfterEach
    void closeTheController() throws Exception {
        try {
            runOnFx(() -> {
                Window.getWindows().removeListener(answerDialogs);
                for (Window window : List.copyOf(Window.getWindows())) {
                    if (window instanceof Stage stage && (MIGRATION_DIALOG_TITLE.equals(stage.getTitle())
                            || CONFIRMATION_TITLE.equals(stage.getTitle()))) {
                        stage.hide();
                    }
                }
            });
        } finally {
            try {
                fixture.close();
            } finally {
                // A reload that failed took the folder's lock and kept it (the
                // project manager releases a lock only for a project it opened);
                // let it go, and its heartbeat with it, before the folder is deleted.
                fixture.projectManager.getLockManager().release();
            }
        }
    }

    @Test
    void aRollBackFromTheMigrationDialogReplacesTheOpenSoTheOuterLoadAnnouncesNothing() throws Exception {
        Path deleted = OpenProjectFixture.wav(workspace.resolve("library").resolve("deleted.wav"), 48_000, 0.5, 220.0);
        OpenProjectFixture.SavedProject saved = fixture.projectOnDisk("Legacy", deleted);
        Files.delete(deleted);

        // Bounded at the fixture's guard: a migration dialog that did not close never lets the load return.
        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(saved.directory()))).isTrue();

        assertThat(answerFailure.get()).as("the dialogs were answered").isNull();
        assertThat(onFx(() -> openStageTitled(MIGRATION_DIALOG_TITLE))).as("the roll-back closed the dialog")
                .isFalse();
        assertThat(fixture.notifications()).as("only the roll-back speaks").singleElement().satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(NotificationLevel.SUCCESS);
            assertThat(entry.message()).startsWith("Discarded in-memory migration");
        });
        assertThat(fixture.presenter.presentations).as("the replaced open presents no report").isEmpty();
        assertThat(onFx(() -> fixture.audio().currentGeneration())).as("the abandoned open was retired").isEmpty();
        assertThat(onFx(() -> fixture.project.get().getName())).isEqualTo("Untitled Project");

        fixture.releaseDecodesAndPulse();
        assertThat(fixture.notifications()).hasSize(1);
    }

    @Test
    void aRollBackWithNoBackupOpensNoJournalForTheProjectItAbandoned() throws Exception {
        OpenProjectFixture.SavedProject saved = fixture.projectOnDisk("Legacy",
                OpenProjectFixture.wav(workspace.resolve("library").resolve("tone.wav"), 48_000, 0.5, 220.0));

        // Bounded at the fixture's guard: a migration dialog that did not close never lets the load return.
        onFx(() -> fixture.lifecycle().loadProjectFromPath(saved.directory()));
        fixture.releaseDecodesAndPulse();

        assertThat(answerFailure.get()).as("the dialogs were answered").isNull();
        assertThat(onFx(() -> fixture.project.get().getName()))
                .as("fixture: with no backup, the roll-back abandoned the project").isEqualTo("Untitled Project");
        assertThat(journalOpens).as("no journal opens for the folder the roll-back abandoned").hasValue(0);
        assertThat(fixture.notifications()).as("only the roll-back speaks").singleElement().satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(NotificationLevel.SUCCESS);
            assertThat(entry.message()).isEqualTo("Discarded in-memory migration; the on-disk file is still the "
                    + "original (v" + MigrationRegistry.CURRENT_VERSION + ")");
        });
    }

    @Test
    void aRollBackWhoseReloadFailsSaysOnlyThatTheOpenFailed() throws Exception {
        OpenProjectFixture.SavedProject saved = fixture.projectOnDisk("Legacy",
                OpenProjectFixture.wav(workspace.resolve("library").resolve("tone.wav"), 48_000, 0.5, 220.0));
        String corrupt = "<daw-project><tracks>";
        Files.writeString(backupOf(saved.directory()), corrupt);

        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(saved.directory())))
                .as("the outer load answers with the roll-back reload's result: it failed").isFalse();

        assertThat(answerFailure.get()).as("the dialogs were answered").isNull();
        assertThat(Files.readString(saved.directory().resolve("project.daw")))
                .as("fixture: the roll-back restored the corrupt backup").isEqualTo(corrupt);
        assertThat(fixture.notifications()).as("one notification, the reload's error; never Opened project")
                .singleElement().satisfies(entry -> {
                    assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
                    assertThat(entry.message()).startsWith("Open failed: ");
                });
        assertThat(journalOpens).as("no journal opens: the reload failed and the outer load was superseded")
                .hasValue(0);
    }

    @Test
    void aRollBackWhoseReloadSucceedsAnnouncesOnceAndOpensTheJournalOnce() throws Exception {
        Path deleted = OpenProjectFixture.wav(workspace.resolve("library").resolve("deleted.wav"), 48_000, 0.5, 220.0);
        OpenProjectFixture.SavedProject saved = fixture.projectOnDisk("Legacy", deleted);
        Files.delete(deleted);
        Files.copy(saved.directory().resolve("project.daw"), backupOf(saved.directory()));

        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(saved.directory())))
                .as("the outer load answers with the roll-back reload's result").isTrue();

        assertThat(answerFailure.get()).as("the dialogs were answered").isNull();
        assertThat(migrationDialogs).as("fixture: the reload migrated again and showed its own dialog, answered OK")
                .hasValue(2);
        assertThat(stackedDialogs).as("decision D42: the reload's dialog showed once the first had closed")
                .isFalse();
        assertThat(fixture.notifications()).as("the reload's one announcement, qualified by what it could not find")
                .singleElement().satisfies(entry -> {
                    assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING);
                    assertThat(entry.message()).startsWith("Restored on-disk project file to v"
                            + MigrationRegistry.CURRENT_VERSION);
                });
        assertThat(journalOpens).as("the journal opened once, by the reload, not again by the outer load")
                .hasValue(1);
        assertThat(fixture.presenter.presentations).as("the reload presented its report once").hasSize(1);

        fixture.releaseDecodesAndPulse();
        assertThat(fixture.notifications()).hasSize(1);
    }

    /** A pre-migration backup of {@code directory}'s project file, named as the roll-back looks for it. */
    private static Path backupOf(Path directory) {
        return directory.resolve("project.daw.v" + MigrationRegistry.CURRENT_VERSION + ".20261020-120000.bak");
    }

    /** Whether a stage titled {@code title} is showing. FX thread. */
    private static boolean openStageTitled(String title) {
        for (Window window : Window.getWindows()) {
            if (window instanceof Stage stage && title.equals(stage.getTitle()) && stage.isShowing()) {
                return true;
            }
        }
        return false;
    }

    /** Whether a stage titled {@code title} other than {@code stage} is showing. FX thread. */
    private static boolean anotherStageTitledIsShowing(String title, Stage stage) {
        for (Window window : Window.getWindows()) {
            if (window != stage && window instanceof Stage other && title.equals(other.getTitle())
                    && other.isShowing()) {
                return true;
            }
        }
        return false;
    }

    /** Presses the button of {@code type} in the dialog shown in {@code stage}. */
    private void press(Stage stage, ButtonType type) {
        try {
            Node root = stage.getScene().getRoot();
            DialogPane pane = root instanceof DialogPane direct ? direct : (DialogPane) root.lookup(".dialog-pane");
            ((Button) pane.lookupButton(type)).fire();
        } catch (Throwable t) {
            answerFailure.set(t);
            stage.hide();
        }
    }

    /** A registry one version ahead of the current schema, every step the identity. */
    private static MigrationRegistry forcedMigrationRegistry() {
        int target = MigrationRegistry.CURRENT_VERSION + 1;
        MigrationRegistry.Builder builder = MigrationRegistry.builder(target);
        for (int version = 1; version < target; version++) {
            int from = version;
            builder.add(ProjectMigration.step(from, "v" + from + "->v" + (from + 1), document -> document));
        }
        return builder.build();
    }
}
