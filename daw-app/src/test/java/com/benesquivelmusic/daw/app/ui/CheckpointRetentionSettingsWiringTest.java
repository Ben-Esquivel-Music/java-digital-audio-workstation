package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.settings.DiskUsageTask;
import com.benesquivelmusic.daw.core.persistence.CheckpointFileName;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.CheckpointOrigin;
import com.benesquivelmusic.daw.core.persistence.backup.BackupRetentionPolicyStore;
import com.benesquivelmusic.daw.sdk.persistence.BackupRetentionPolicy;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.chart.PieChart;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 335 — "Retention delivery test" (studio ruling R1, R2): the Backups
 * rows visibly change what is kept on disk in the real {@code checkpoints/}
 * folder across two application runs, and the disk-usage readout reflects that
 * same folder.
 *
 * <p>Each run is the application's own wiring of the parts: a
 * {@link CheckpointManager} built from {@link MainController#startupAutoSaveConfig},
 * a {@link BackupRetentionController} over one policy store that delivers the
 * stored policy before the checkpoints start, and the production Settings
 * surface ({@link SettingsDialog} over the controller's
 * {@link BackupRetentionController#settingsAccess access seam}) whose Apply
 * delivers and saves. Run 1 edits the rows; run 2 is a restart over the same
 * store and folder (story 331's seeded index makes the count honest). The
 * source half pins {@code initialize()}'s delivery before any checkpoint
 * generation, the relabelled menu entry, and that no main source targets the
 * ghost {@code ~/.daw/autosaves}.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class CheckpointRetentionSettingsWiringTest {

    private static final ResourceBundle MESSAGES = ResourceBundle.getBundle(
            "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);
    private static final long WAIT_SECONDS = 10;

    @Test
    void theBackupsRowsChangeWhatTwoRunsKeepInTheRealCheckpointsFolderAndTheReadoutShowsIt(@TempDir Path tempDir)
            throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("Song"));
        Path policyFile = tempDir.resolve("backup-retention.json");
        SettingsModel model = Story307TestSupport.model("checkpointRetentionWiring");

        // ── Run 1 ──
        CheckpointManager run1 = new CheckpointManager(MainController.startupAutoSaveConfig(model));
        BackupRetentionPolicy edited = new BackupRetentionPolicy(2, 0, 0, 0, Duration.ZERO, 0L);
        String seventh;
        try (StoppedWhenTheRunEnds _ = new StoppedWhenTheRunEnds(run1)) {
            BackupRetentionController controller1 = new BackupRetentionController(
                    new BackupRetentionPolicyStore(policyFile), run1, Runnable::run);
            assertThat(controller1.deliverStored()).contains(BackupRetentionPolicy.DEFAULT);
            run1.start(project).get(WAIT_SECONDS, TimeUnit.SECONDS);
            for (int revision = 1; revision <= 6; revision++) {
                write(run1, revision);
            }
            assertThat(checkpointNames(project)).as("DEFAULT keeps 10 recent").hasSize(6);

            AtomicInteger saveFailures = new AtomicInteger();
            Story307TestSupport.onFx(() -> {
                SettingsDialog dialog = new SettingsDialog(model);
                dialog.setBackupSettingsAccess(controller1.settingsAccess(() -> Optional.of(project),
                        saveFailures::incrementAndGet));
                Map<String, Object> edits = Map.of(
                        "backups.keepRecent", 2, "backups.keepHourly", 0, "backups.keepDaily", 0,
                        "backups.keepWeekly", 0, "backups.maxAgeDays", 0.0, "backups.maxDiskGiB", 0.0);
                edits.forEach((id, value) -> dialog.getShell().settingRow(id).orElseThrow().setValue(value));
                assertThat(dialog.getShell().pendingEdits()).containsOnlyKeys(edits.keySet());
                dialog.applySettingsAsync();
                dialog.setBackupSettingsAccess(null);
                return null;
            }, 30, TimeUnit.SECONDS);
            List<String> beforeSeventh = checkpointNames(project);
            assertThat(beforeSeventh).as("Apply deletes nothing (R1.3)").hasSize(6);

            seventh = write(run1, 7).getFileName().toString();
            assertThat(checkpointNames(project)).as("the next checkpoint prunes by the delivered policy")
                    .containsExactly(beforeSeventh.get(5), seventh);
            assertThat(run1.getRetentionPolicy()).as("delivered on Apply").contains(edited);
            assertThat(BackupRetentionPolicyStore.load(policyFile)).as("and saved").contains(edited);
            assertThat(saveFailures).hasValue(0);
        }

        // ── Run 2: a restart over the same store and folder ──
        CheckpointManager run2 = new CheckpointManager(MainController.startupAutoSaveConfig(model));
        try (StoppedWhenTheRunEnds _ = new StoppedWhenTheRunEnds(run2)) {
            BackupRetentionController controller2 = new BackupRetentionController(
                    new BackupRetentionPolicyStore(policyFile), run2, Runnable::run);
            assertThat(controller2.deliverStored()).as("read back from disk").contains(edited);
            List<String> seeded = checkpointNames(project);
            run2.start(project).get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertThat(checkpointNames(project)).as("nothing is pruned at start").isEqualTo(seeded);
            String eighth = write(run2, 1).getFileName().toString();
            assertThat(checkpointNames(project)).as("the new checkpoint and the newest seeded one")
                    .containsExactlyInAnyOrder(seventh, eighth);

            // ── The disk-usage readout reads the same folder ──
            long checkpointBytes = 0;
            for (String name : checkpointNames(project)) {
                checkpointBytes += Files.size(project.resolve("checkpoints").resolve(name));
            }
            String expectedSummary = MessageFormat.format(MESSAGES.getString("settings.backups.diskUsage.summary"),
                    DiskUsageTask.formatBytes(checkpointBytes), 2);
            SettingsDialog readout = Story307TestSupport.onFx(() -> {
                SettingsDialog dialog = new SettingsDialog(model);
                dialog.setBackupSettingsAccess(controller2.settingsAccess(() -> Optional.of(project), () -> { }));
                dialog.selectCategory("backups");
                return dialog;
            }, 30, TimeUnit.SECONDS);
            try {
                Label summary = Story307TestSupport.onFx(() -> find(readout, Label.class, "settings-disk-summary"));
                assertThat(Story307TestSupport.awaitFxValue(summary::getText, expectedSummary,
                        WAIT_SECONDS, TimeUnit.SECONDS))
                        .as("the readout shows %s", expectedSummary).isTrue();
                long sliceBytes = checkpointBytes;
                Story307TestSupport.onFx(() -> {
                    PieChart pie = find(readout, PieChart.class, "settings-disk-pie");
                    assertThat(pie.getData()).hasSize(4);
                    assertThat(pie.getData().getFirst().getPieValue()).as("the Checkpoints slice")
                            .isEqualTo((double) sliceBytes);
                    assertThat(find(readout, Label.class, "settings-disk-note").getText())
                            .isEqualTo("Retention changes apply after the open project's next checkpoint;"
                                    + " applying them deletes nothing.");
                    assertThat(summary.getAccessibleText()).isEqualTo(expectedSummary);
                    return null;
                });
            } finally {
                Story307TestSupport.onFx(() -> {
                    readout.setBackupSettingsAccess(null);
                    return null;
                });
            }
        }
    }

    /**
     * Stops a run's checkpoint generation when the run ends, passed or failed
     * (build-of-record review F1): a failed run's writer must not outlive the
     * test into the {@code @TempDir} cleanup. The stop runs in the
     * try-with-resources' implicit {@code finally}; after a failed run its own
     * failure (a stop that also times out under the same stall) is attached to
     * the run's failure as suppressed, so it never hides which step failed. The
     * stop's budget is the same {@link #WAIT_SECONDS} guard as every write's.
     */
    private record StoppedWhenTheRunEnds(CheckpointManager manager) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            manager.stop().get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Test
    void initializeDeliversTheStoredPolicyBeforeAnyGenerationAndNothingTargetsTheGhostFolder() throws IOException {
        Path appMain = SourceScanSupport.locateDawAppModule().resolve("src/main/java");
        Path ui = appMain.resolve("com/benesquivelmusic/daw/app/ui");
        String main = flatCode(ui.resolve("MainController.java"));
        String initialize = methodBody(main, "private void initialize()");

        int construction = initialize.indexOf(
                "backupRetentionController = new BackupRetentionController(new BackupRetentionPolicyStore(),"
                        + " checkpointManager);");
        int delivery = initialize.indexOf("deliverStoredRetention(backupRetentionController,");
        int projectManager = initialize.indexOf("new ProjectManager(checkpointManager");
        assertThat(construction).as("built over the one checkpoint manager").isNotNegative();
        assertThat(delivery).as("delivered right after").isGreaterThan(construction);
        assertThat(projectManager).as("before any project (and so any generation) exists").isGreaterThan(delivery);
        assertThat(initialize).contains("projectManager.alsoAwaitBeforeExit(backupRetentionController);");
        assertThat(main).as("no background prune remains").doesNotContain("backupRetentionController.start()")
                .doesNotContain("backupRetentionController.shutdown()");

        String menu = Files.readString(ui.resolve("MenuConstructionService.java"), StandardCharsets.UTF_8);
        assertThat(menu).contains("menuItem(\"Checkpoint Retention\\u2026\"").doesNotContain("~/.daw/autosaves")
                .doesNotContain("Backup Retention");

        Pattern ghost = Pattern.compile("\"autosaves\"|\\.daw[/\\\\]autosaves|defaultAutosavesDirectory");
        List<Path> offenders = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(appMain)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String code = SourceScanSupport.stripComments(Files.readString(source, StandardCharsets.UTF_8));
                if (ghost.matcher(code).find()) {
                    offenders.add(appMain.relativize(source));
                }
            }
        }
        assertThat(offenders).as("no daw-app main source names autosaves as a path segment").isEmpty();
    }

    private static Path write(CheckpointManager manager, long revision) throws Exception {
        return manager.writeCheckpoint("<project revision=\"" + revision + "\"/>", revision, CheckpointOrigin.USER)
                .get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    /** The checkpoints in the project's real folder, oldest name first: only names the grammar accepts. */
    private static List<String> checkpointNames(Path project) throws IOException {
        Path folder = project.resolve("checkpoints");
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    .map(file -> file.getFileName().toString())
                    .filter(name -> CheckpointFileName.parse(name).isPresent())
                    .sorted()
                    .toList();
        }
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

    private static String flatCode(Path file) throws IOException {
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(raw.length()).as("non-vacuity: %s was read", file.getFileName()).isGreaterThan(10_000);
        return SourceScanSupport.stripComments(raw).replaceAll("\\s+", " ");
    }

    private static String methodBody(String code, String signature) {
        int declaration = code.indexOf(signature);
        assertThat(declaration).as("the source declares %s", signature).isNotNegative();
        int open = code.indexOf('{', declaration);
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return code.substring(open, i + 1);
            }
        }
        throw new AssertionError("unbalanced braces after " + signature);
    }
}
