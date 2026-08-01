package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.settings.SettingRow;
import com.benesquivelmusic.daw.app.ui.settings.SettingsShell;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.sdk.audio.SampleRateConverter.QualityTier;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ScrollPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 308 — §5.7 tier 3: "Reset Recording to defaults" (driven through
 * the pane header's ⋯ menu item) restores every Recording row without
 * touching Backups or Audio; "Reset Backups to defaults" likewise; an
 * unknown category id throws.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class RecordingAndBackupsCategoryResetTest {

    private static final List<String> RECORDING_IDS = List.of(
            "metronome.enabled", "metronome.countIn", "metronome.clickChannel",
            "metronome.clickGain", "metronome.clickSideOutput",
            "metronome.clickMainMix", "metronome.clickCueBus");
    private static final List<String> BACKUP_IDS = List.of(
            "backups.keepRecent", "backups.keepHourly", "backups.keepDaily",
            "backups.keepWeekly", "backups.maxAgeDays", "backups.maxDiskGiB");

    @Test
    void categoryResetRestoresOnlyThatCategory() throws Exception {
        Story307TestSupport.onFx(() -> {
            SettingsModel model = Story307TestSupport.model("categoryReset");
            SettingsDialog dialog = new SettingsDialog(model);
            SettingsShell shell = dialog.getShell();

            // Edit every Recording row, one Backups row, one Audio row.
            row(shell, "metronome.enabled").setValue(false);
            row(shell, "metronome.countIn").setValue(CountInMode.TWO_BARS);
            row(shell, "metronome.clickChannel").setValue(5);
            row(shell, "metronome.clickGain").setValue(0.25);
            row(shell, "metronome.clickSideOutput").setValue(true);
            row(shell, "metronome.clickMainMix").setValue(false);
            String editedBus = UUID.randomUUID().toString();
            row(shell, "metronome.clickCueBus").setValue(editedBus);
            row(shell, "backups.keepRecent").setValue(42);
            QualityTier editedQuality = model.getSrcQuality() == QualityTier.LOW
                    ? QualityTier.HIGH : QualityTier.LOW;
            row(shell, "audio.srcQuality").setValue(editedQuality);

            // Tier 3 via the actual UI affordance: the recording pane's
            // ⋯ menu carries exactly one wired "Reset … to defaults" item.
            dialog.selectCategory("recording");
            MenuButton menu = categoryMenu(shell, "settings-category-menu-recording");
            assertThat(menu.getItems())
                    .as("the ⋯ menu carries the single reset item")
                    .hasSize(1);
            assertThat(menu.getItems().get(0).getText())
                    .isEqualTo("Reset Recording to defaults");
            menu.getItems().get(0).fire();

            for (String id : RECORDING_IDS) {
                SettingRow reset = row(shell, id);
                assertThat(reset.getValue())
                        .as("recording row %s is back at its default", id)
                        .isEqualTo(reset.descriptor().defaultValue());
            }
            assertThat(row(shell, "backups.keepRecent").getValue())
                    .as("backups edit untouched by the Recording reset")
                    .isEqualTo(42);
            assertThat(row(shell, "audio.srcQuality").getValue())
                    .as("audio edit untouched by the Recording reset")
                    .isEqualTo(editedQuality);

            // Backups tier 3 through the public API.
            shell.resetCategory("backups");
            for (String id : BACKUP_IDS) {
                SettingRow reset = row(shell, id);
                assertThat(reset.getValue())
                        .as("backups row %s is back at its default", id)
                        .isEqualTo(reset.descriptor().defaultValue());
            }
            assertThat(row(shell, "audio.srcQuality").getValue())
                    .as("audio edit still untouched")
                    .isEqualTo(editedQuality);

            assertThatThrownBy(() -> shell.resetCategory("nope"))
                    .isInstanceOf(IllegalArgumentException.class);
            return null;
        });
    }

    private static SettingRow row(SettingsShell shell, String id) {
        return shell.settingRow(id).orElseThrow();
    }

    private static MenuButton categoryMenu(SettingsShell shell, String nodeId) {
        Set<Node> nodes = new LinkedHashSet<>();
        collectDeep(shell, nodes);
        return nodes.stream()
                .filter(MenuButton.class::isInstance)
                .map(MenuButton.class::cast)
                .filter(button -> nodeId.equals(button.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no ⋯ menu with id " + nodeId));
    }

    /** ScrollPane content is followed explicitly (headless — no skins). */
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
