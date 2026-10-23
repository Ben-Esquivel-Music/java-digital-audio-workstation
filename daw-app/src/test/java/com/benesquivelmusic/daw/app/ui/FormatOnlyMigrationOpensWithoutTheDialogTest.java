package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationReport;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Button;
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
import java.util.concurrent.atomic.AtomicInteger;

import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 330 (studio-engineer rulings F5, R3) — a real schema 1 file opened
 * with the production migration registry, through the lifecycle controller's
 * {@code loadProjectFromPath}: the 1 → 2 step only re-expresses the file
 * format, so the open is migrated (backed up on its first save, listed in the
 * history, badged on the hub) yet shows no "Project Migrated" dialog, and
 * announces itself exactly once, plainly.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class FormatOnlyMigrationOpensWithoutTheDialogTest {

    private static final String MIGRATION_DIALOG_TITLE = "Project Migrated";

    /** A schema 1 file as the schema 1 serializer wrote it: arpeggiator keyed by class, pre/post-roll, no MIDI clip. */
    private static final String SCHEMA_1_FILE = """
            <daw-project version="1">
              <metadata>
                <name>Old Song</name>
              </metadata>
              <transport loop-enabled="false" loop-end="16.0" loop-start="0.0" position="0.0" post-roll-bars="1" pre-roll-bars="2" pre-roll-enabled="true" tempo="96.0" time-sig-denominator="4" time-sig-numerator="4"/>
              <tracks>
                <track id="00000000-0000-0000-0000-0000000000a1" name="Keys" type="MIDI" volume="1.0">
                  <midi-effect-chain>
                    <midi-effect class="com.benesquivelmusic.daw.core.plugin.builtin.midi.ArpeggiatorPlugin" gate="80.0" latch="true" octave-range="2" pattern="UP_DOWN" rate="EIGHTH" swing="12.5"/>
                  </midi-effect-chain>
                </track>
              </tracks>
              <mixer>
                <master name="Master" volume="1.0"/>
                <channels>
                  <channel name="Keys" volume="0.75"/>
                </channels>
                <return-buses/>
              </mixer>
            </daw-project>
            """;

    @TempDir
    Path workspace;

    private OpenProjectFixture fixture;
    /** The migration dialogs shown; each is closed at once so the load can return and the test fail. */
    private final AtomicInteger migrationDialogs = new AtomicInteger();
    private final ListChangeListener<Window> closeMigrationDialogs = change -> {
        while (change.next()) {
            for (Window window : change.getAddedSubList()) {
                if (window instanceof Stage stage && MIGRATION_DIALOG_TITLE.equals(stage.getTitle())) {
                    migrationDialogs.incrementAndGet();
                    Platform.runLater(() -> pressOk(stage));
                }
            }
        }
    };

    @BeforeEach
    void createTheController() throws Exception {
        fixture = new OpenProjectFixture(workspace);
        runOnFx(() -> Window.getWindows().addListener(closeMigrationDialogs));
    }

    @AfterEach
    void closeTheController() throws Exception {
        try {
            runOnFx(() -> Window.getWindows().removeListener(closeMigrationDialogs));
        } finally {
            fixture.close();
        }
    }

    @Test
    void aSchemaOneFileOpensMigratedWithoutTheDialogAndAnnouncesOncePlainly() throws Exception {
        ProjectManager other = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT));
        Path directory = other.createProject("Old Song", workspace).projectPath();
        other.abandonProject();
        Files.writeString(directory.resolve("project.daw"), SCHEMA_1_FILE);

        // Bounded at the fixture's guard: a dialog that showed is closed by the listener.
        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(directory))).isTrue();

        MigrationReport report = fixture.projectManager.getLastMigrationReport();
        assertThat(report.wasMigrated()).as("fixture: the production registry migrated the file").isTrue();
        assertThat(report.changesAuthoredContent()).as("fixture: by a format-only step").isFalse();
        assertThat(migrationDialogs).as("no Project Migrated dialog").hasValue(0);
        assertThat(fixture.notifications()).as("exactly one announcement, the plain one").singleElement()
                .satisfies(entry -> {
                    assertThat(entry.level()).isEqualTo(NotificationLevel.SUCCESS);
                    assertThat(entry.message()).isEqualTo("Opened project: " + directory.getFileName());
                });
    }

    /** Presses the dialog's OK, which is also its cancel, so the dialog closes. */
    private static void pressOk(Stage stage) {
        Node root = stage.getScene().getRoot();
        DialogPane pane = root instanceof DialogPane direct ? direct : (DialogPane) root.lookup(".dialog-pane");
        Button ok = (Button) pane.lookupButton(MigrationReportDialog.OK_BUTTON_TYPE);
        if (ok != null) {
            ok.fire();
        } else {
            stage.hide();
        }
    }
}
