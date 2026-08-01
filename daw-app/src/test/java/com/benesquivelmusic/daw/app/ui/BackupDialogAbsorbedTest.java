package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.settings.SettingsCatalogue;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.chart.PieChart;
import javafx.scene.control.ScrollPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 308 — the Backups category absorbs {@code BackupSettingsDialog},
 * pie included (§3.3, §5.8, §7 Stage 4): the six real retention-policy
 * fields render as generic rows, the disk-usage pie renders INLINE as a
 * group visual (not a modal), and the former Edit-menu entry deep-links
 * into the category.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class BackupDialogAbsorbedTest {

    private static final List<String> BACKUP_IDS = List.of(
            "backups.keepRecent", "backups.keepHourly", "backups.keepDaily",
            "backups.keepWeekly", "backups.maxAgeDays", "backups.maxDiskGiB");

    @Test
    void retentionRowsExistForEveryRealPolicyField() throws Exception {
        SettingsCatalogue.Category backups = SettingsCatalogue.create().categories().stream()
                .filter(category -> category.id().equals("backups"))
                .findFirst().orElseThrow();
        assertThat(backups.groups()).extracting(SettingsCatalogue.Group::id)
                .containsExactly("retention", "diskUsage");
        assertThat(backups.groups().get(0).settings().stream()
                .map(setting -> setting.id()).toList())
                .containsExactlyElementsOf(BACKUP_IDS);

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(
                    Story307TestSupport.model("backupAbsorbedRows"));
            assertThat(BACKUP_IDS).allSatisfy(id ->
                    assertThat(dialog.getShell().settingRow(id)).isPresent());
            return null;
        });
    }

    @Test
    void diskUsagePieRendersInlineAsAGroupVisual() throws Exception {
        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(
                    Story307TestSupport.model("backupAbsorbedPie"));
            dialog.selectCategory("backups");

            // The shell pane is a plain VBox graph (ScrollPane content
            // followed explicitly — no skins realized headlessly).
            Set<Node> nodes = new LinkedHashSet<>();
            collectDeep(dialog.getShell(), nodes);
            List<PieChart> pies = nodes.stream()
                    .filter(PieChart.class::isInstance)
                    .map(PieChart.class::cast)
                    .toList();
            assertThat(pies)
                    .as("the Backups category renders the disk-usage pie inline")
                    .hasSize(1);
            assertThat(pies.get(0).getStyleClass()).contains("settings-disk-pie");
            assertThat(pies.get(0).isLegendVisible()).isTrue();
            assertThat(pies.get(0).getLabelsVisible()).isFalse();
            return null;
        });
    }

    /** The §2.1 guard: no standalone backup dialog, deep link instead. */
    @Test
    void standaloneDialogIsDeletedAndTheMenuEntryDeepLinks() throws IOException {
        Path module = SourceScanSupport.locateDawAppModule();
        Path ui = module.resolve("src/main/java/com/benesquivelmusic/daw/app/ui");

        assertThat(ui.resolve("BackupSettingsDialog.java")).doesNotExist();
        String mainController = Files.readString(
                ui.resolve("MainController.java"), StandardCharsets.UTF_8);
        assertThat(mainController)
                .contains("dialog.selectCategory(\"backups\")")
                .doesNotContain("backupRetentionController.openDialog")
                .doesNotContain("new BackupSettingsDialog");
        String retentionController = Files.readString(
                ui.resolve("BackupRetentionController.java"), StandardCharsets.UTF_8);
        assertThat(retentionController)
                .doesNotContain("openDialog")
                .doesNotContain("new BackupSettingsDialog");
    }

    /**
     * Deep scene-graph walk; {@code ScrollPane} content is not a child
     * until a skin attaches, so it is followed explicitly.
     */
    private static void collectDeep(Node node, Set<Node> into) {
        if (node == null || !into.add(node)) {
            return;
        }
        if (node instanceof ScrollPane scrollPane) {
            collectDeep(scrollPane.getContent(), into);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectDeep(child, into);
            }
        }
    }
}
