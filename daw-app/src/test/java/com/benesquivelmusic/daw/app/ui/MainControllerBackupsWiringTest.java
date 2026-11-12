package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.settings.DiskUsageTask;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.CheckpointOrigin;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.backup.BackupRetentionPolicyStore;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.event.EventBus;
import com.benesquivelmusic.daw.sdk.persistence.BackupRetentionPolicy;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 335 (studio ruling R1.4, R2) — the production Backups wiring of
 * {@link MainController}: the Settings surface {@code createSettingsDialog()}
 * builds reaches the window's {@link BackupRetentionController} through
 * {@code buildBackupSettingsAccess()}. An Apply of the Backups rows delivers
 * the edited policy to the checkpoint manager; a failed save of it shows the
 * ruling's WARNING, verbatim, once; and the disk-usage readout reads the open
 * project's folder.
 *
 * <p>A partial controller — {@code new MainController()} with its fields set,
 * the {@code DefaultTempoApplyIsolationTest} idiom; it is never FXML-loaded,
 * since {@code initialize()} builds a live audio engine — over a real project
 * manager with a project created on disk and a policy store whose save fails
 * (its folder is a file). The Apply's {@code SettingsApplied} publish goes to
 * a private bus and the theme manager is an isolated one.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class MainControllerBackupsWiringTest {

    private static final String NOT_SAVED = "Checkpoint retention could not be saved; it applies until you quit.";
    private static final long WAIT_SECONDS = 10;
    /**
     * Guard on one FX turn, none of which waits on the FX thread. The test
     * method's guards together (10 + 10 + 10 + 10 + 10 s) and {@link #restore()}'s
     * stay under the suite's 60 s method timeout.
     */
    private static final long FX_SECONDS = 10;

    private final List<Preferences> nodes = new ArrayList<>();
    private EventBus previousBus;
    private DefaultEventBus bus;
    /** What the test opened; {@link #restore()} closes it, under its own timeout. */
    private CheckpointManager checkpoints;
    private ProjectManager projectManager;
    private SettingsDialog dialog;

    /** The window's notification bar and the Settings surface's disk-usage readout. */
    private record Built(NotificationBar bar, Label diskSummary) {}

    /** What the notification bar shows, read in one FX turn. */
    private record Shown(String message, NotificationLevel level) {}

    @BeforeEach
    void isolateTheBusAndTheThemeManager() {
        previousBus = EventBusPublisher.getDefault();
        bus = DefaultEventBus.builder().uiExecutor(Runnable::run).build();
        EventBusPublisher.setDefault(bus);
        ThemeManager.setDefaultForTest(new ThemeManager(node("mainControllerBackupsTheme")));
    }

    @AfterEach
    void restore() throws Exception {
        try {
            if (dialog != null) {
                Story307TestSupport.onFx(() -> {
                    dialog.setBackupSettingsAccess(null);
                    return null;
                });
            }
            if (projectManager != null) {
                projectManager.closeProject().get(WAIT_SECONDS, TimeUnit.SECONDS);
            }
            if (checkpoints != null) {
                checkpoints.stop().get(WAIT_SECONDS, TimeUnit.SECONDS);
            }
        } finally {
            ThemeManager.setDefaultForTest(null);
            EventBusPublisher.setDefault(previousBus);
            bus.close();
            for (Preferences node : nodes) {
                node.removeNode();
            }
        }
    }

    @Test
    void anApplyDeliversAFailedSaveWarnsOnceAndTheReadoutReadsTheOpenProjectsFolder(@TempDir Path tempDir)
            throws Exception {
        checkpoints = new CheckpointManager(AutoSaveConfig.DEFAULT);
        projectManager = new ProjectManager(checkpoints);
        Path song = projectManager.createProject("Song", tempDir.resolve("workspace")).projectPath();
        Path checkpoint = checkpoints.writeCheckpoint("<project revision=\"1\"/>", 1, CheckpointOrigin.USER)
                .get(WAIT_SECONDS, TimeUnit.SECONDS);
        Path notAFolder = Files.writeString(tempDir.resolve("blocker"), "x");
        BackupRetentionController retention = new BackupRetentionController(
                new BackupRetentionPolicyStore(notAFolder.resolve("backup-retention.json")), checkpoints);
        NotificationHistoryService history = new NotificationHistoryService();
        MainController controller = new MainController();
        setField(controller, "settingsModel", new SettingsModel(node("mainControllerBackups")));
        setField(controller, "project", new DawProject("Song", AudioFormat.CD_QUALITY));
        setField(controller, "undoManager", new UndoManager());
        setField(controller, "projectManager", projectManager);
        setField(controller, "pluginRegistry", new PluginRegistry());
        setField(controller, "backupRetentionController", retention);
        Built built = Story307TestSupport.onFx(() -> {
            NotificationBar bar = new NotificationBar();
            bar.setAnimated(false);
            bar.setHistoryService(history);
            setField(controller, "notificationBar", bar);
            setField(controller, "statusBarLabel", new Label());
            setField(controller, "rootPane", new BorderPane());
            dialog = controller.createSettingsDialog();
            dialog.selectCategory("backups");
            return new Built(bar, find(dialog, Label.class, "settings-disk-summary"));
        }, FX_SECONDS, TimeUnit.SECONDS);

        // The readout reads the open project's folder: its one checkpoint, not "not saved yet".
        String expected = "Checkpoints: " + DiskUsageTask.formatBytes(Files.size(checkpoint)) + " in 1 file.";
        assertThat(checkpoint.getParent().getParent()).isEqualTo(song);
        assertThat(Story307TestSupport.awaitFxValue(built.diskSummary()::getText, expected,
                WAIT_SECONDS, TimeUnit.SECONDS)).as("the readout shows %s", expected).isTrue();

        Story307TestSupport.onFx(() -> {
            Map<String, Object> edits = Map.of(
                    "backups.keepRecent", 2, "backups.keepHourly", 0, "backups.keepDaily", 0,
                    "backups.keepWeekly", 0, "backups.maxAgeDays", 0.0, "backups.maxDiskGiB", 0.0);
            edits.forEach((id, value) -> dialog.getShell().settingRow(id).orElseThrow().setValue(value));
            dialog.applySettings();
            return null;
        }, FX_SECONDS, TimeUnit.SECONDS);
        assertThat(checkpoints.getRetentionPolicy()).as("the Apply reached the checkpoint manager")
                .contains(new BackupRetentionPolicy(2, 0, 0, 0, Duration.ZERO, 0L));

        // The failed save and the WARNING it posts share one guard.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        assertThat(retention.awaitUntil(deadline)).as("the save ended").isTrue();
        NotificationBar bar = built.bar();
        assertThat(Story307TestSupport.awaitFxValue(() -> new Shown(bar.getMessage(), bar.getCurrentLevel()),
                new Shown(NOT_SAVED, NotificationLevel.WARNING),
                Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS))
                .as("the failed save is reported, verbatim, as a WARNING").isTrue();
        assertThat(history.getEntries()).as("once")
                .filteredOn(entry -> entry.message().equals(NOT_SAVED)).hasSize(1)
                .allSatisfy(entry -> assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING));
    }

    private static <T extends Node> T find(SettingsDialog dialog, Class<T> type, String styleClass) {
        List<Node> found = new ArrayList<>();
        collect(dialog.getShell(), found);
        return found.stream()
                .filter(type::isInstance)
                .filter(node -> node.getStyleClass().contains(styleClass))
                .map(type::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no ." + styleClass));
    }

    /** {@code ScrollPane} content is not a child until a skin attaches, so it is followed explicitly. */
    private static void collect(Node node, List<Node> into) {
        if (node == null) {
            return;
        }
        into.add(node);
        if (node instanceof ScrollPane scrollPane) {
            collect(scrollPane.getContent(), into);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, into);
            }
        }
    }

    private Preferences node(String prefix) {
        Preferences node = Preferences.userRoot().node(prefix + "_" + System.nanoTime());
        nodes.add(node);
        return node;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
