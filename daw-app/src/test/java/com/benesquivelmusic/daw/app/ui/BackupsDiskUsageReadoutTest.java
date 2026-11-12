package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.settings.BackupSettingsAccess;
import com.benesquivelmusic.daw.app.ui.settings.DiskUsageTask;
import com.benesquivelmusic.daw.sdk.persistence.BackupRetentionPolicy;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.chart.PieChart;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 335 (studio ruling R2) — the Settings ▸ Backups disk-usage readout
 * never reports a size it could not read: a project not saved yet shows an
 * empty pie and says it has no checkpoints; a scan that fails — the open
 * project's folder gone, as on a drive that went offline, or no folder at all —
 * shows an empty pie and {@code Disk usage could not be read.}, never zero
 * slices or "0 B in no files". The texts are the ruling's, verbatim.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class BackupsDiskUsageReadoutTest {

    private static final String UNSAVED = "This project has not been saved yet, so it has no checkpoints.";
    private static final String FAILED = "Disk usage could not be read.";
    private static final long WAIT_SECONDS = 10;

    private final List<Preferences> nodes = new ArrayList<>();

    @AfterEach
    void removeNodes() throws Exception {
        for (Preferences node : nodes) {
            node.removeNode();
        }
    }

    @Test
    void aProjectNotSavedYetShowsAnEmptyPieAndSaysItHasNoCheckpoints() throws Exception {
        SettingsDialog dialog = readout(Optional.empty());
        try {
            Story307TestSupport.onFx(() -> {
                assertThat(find(dialog, Label.class, "settings-disk-summary").getText()).isEqualTo(UNSAVED);
                assertThat(find(dialog, PieChart.class, "settings-disk-pie").getData()).isEmpty();
                return null;
            });
        } finally {
            detach(dialog);
        }
    }

    @Test
    void anOpenProjectWhoseFolderIsGoneFailsTheScanAndShowsNoZeros(@TempDir Path tempDir) throws Exception {
        assertFailedReadout(tempDir.resolve("Song on a drive that went offline"));
    }

    @Test
    void anOpenProjectWhosePathIsNoFolderFailsTheScanAndShowsNoZeros(@TempDir Path tempDir) throws Exception {
        assertFailedReadout(Files.writeString(tempDir.resolve("Song"), "not a folder"));
    }

    /** The contrast: a folder that is there is read, and its checkpoints counted. */
    @Test
    void anOpenProjectsFolderIsReadIntoFourSlicesAndTheSummary(@TempDir Path tempDir) throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("Song"));
        Path checkpoints = Files.createDirectories(project.resolve("checkpoints"));
        Files.writeString(checkpoints.resolve("checkpoint-001-20260101T120000.daw"), "ABCDE");
        SettingsDialog dialog = readout(Optional.of(project));
        try {
            Label summary = Story307TestSupport.onFx(() -> find(dialog, Label.class, "settings-disk-summary"));
            assertThat(Story307TestSupport.awaitFxValue(summary::getText, "Checkpoints: "
                    + DiskUsageTask.formatBytes(5) + " in 1 file.", WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(Story307TestSupport.onFx(() -> find(dialog, PieChart.class, "settings-disk-pie").getData()))
                    .hasSize(4);
        } finally {
            detach(dialog);
        }
    }

    private void assertFailedReadout(Path folder) throws Exception {
        SettingsDialog dialog = readout(Optional.of(folder));
        try {
            Label summary = Story307TestSupport.onFx(() -> find(dialog, Label.class, "settings-disk-summary"));
            assertThat(Story307TestSupport.awaitFxValue(summary::getText, FAILED, WAIT_SECONDS, TimeUnit.SECONDS))
                    .as("the readout says the scan failed").isTrue();
            Story307TestSupport.onFx(() -> {
                assertThat(find(dialog, PieChart.class, "settings-disk-pie").getData()).as("no slice, zero or not")
                        .isEmpty();
                assertThat(summary.getAccessibleText()).isEqualTo(FAILED);
                return null;
            });
        } finally {
            detach(dialog);
        }
    }

    /** A Settings dialog whose Backups access names {@code projectDirectory} as the open project's folder. */
    private SettingsDialog readout(Optional<Path> projectDirectory) throws Exception {
        Preferences node = Preferences.userRoot().node("backupsDiskUsageReadout_" + System.nanoTime());
        nodes.add(node);
        BackupSettingsAccess access = new BackupSettingsAccess() {
            @Override
            public BackupRetentionPolicy currentPolicy() {
                return BackupRetentionPolicy.DEFAULT;
            }

            @Override
            public boolean storedPolicyUnreadable() {
                return false;
            }

            @Override
            public Optional<Path> projectDirectory() {
                return projectDirectory;
            }

            @Override
            public void applyPolicy(BackupRetentionPolicy policy) {
                throw new AssertionError("nothing is applied here");
            }
        };
        return Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(new SettingsModel(node));
            dialog.setBackupSettingsAccess(access);
            dialog.selectCategory("backups");
            return dialog;
        }, 30, TimeUnit.SECONDS);
    }

    private static void detach(SettingsDialog dialog) throws Exception {
        Story307TestSupport.onFx(() -> {
            dialog.setBackupSettingsAccess(null);
            return null;
        });
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
}
