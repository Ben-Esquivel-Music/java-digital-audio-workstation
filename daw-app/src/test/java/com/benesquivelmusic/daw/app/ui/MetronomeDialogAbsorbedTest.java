package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.settings.RecordingSettingsAccess;
import com.benesquivelmusic.daw.app.ui.settings.SettingRow;
import com.benesquivelmusic.daw.app.ui.settings.SettingsCatalogue;
import com.benesquivelmusic.daw.core.mixer.CueBus;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.sdk.transport.ClickOutput;

import javafx.scene.control.ComboBox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 308 — the Recording category absorbs {@code MetronomeSettingsDialog}
 * (§3.3, §7 Stage 4): every control the dialog exposed has a generic
 * catalogue-driven row, the cue-bus selector semantics survive the
 * migration, and the standalone dialog class is gone (the §2.1 guard).
 */
@ExtendWith(JavaFxToolkitExtension.class)
class MetronomeDialogAbsorbedTest {

    private static final List<String> RECORDING_IDS = List.of(
            "metronome.enabled", "metronome.countIn", "metronome.clickChannel",
            "metronome.clickGain", "metronome.clickSideOutput",
            "metronome.clickMainMix", "metronome.clickCueBus");

    @Test
    void recordingGroupsCarryTheDesignBookOrderAndEveryAbsorbedControlHasAnId() {
        SettingsCatalogue.Category recording = SettingsCatalogue.create().categories().stream()
                .filter(category -> category.id().equals("recording"))
                .findFirst().orElseThrow();

        assertThat(recording.groups()).extracting(SettingsCatalogue.Group::id)
                .containsExactly("metronome", "clickRouting", "cueMix");
        assertThat(ids(recording.groups().get(0))).containsExactly(
                "metronome.enabled", "metronome.countIn");
        assertThat(ids(recording.groups().get(1))).containsExactly(
                "metronome.clickChannel", "metronome.clickGain",
                "metronome.clickSideOutput");
        assertThat(ids(recording.groups().get(2))).containsExactly(
                "metronome.clickMainMix", "metronome.clickCueBus");
    }

    @Test
    void dialogExposesALiveRowForEveryAbsorbedRecordingId() throws Exception {
        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(
                    Story307TestSupport.model("metronomeAbsorbedRows"));
            assertThat(RECORDING_IDS).allSatisfy(id ->
                    assertThat(dialog.getShell().settingRow(id)).isPresent());

            // Migrated cue-selector semantics: with no cue buses (no
            // Recording seam attached) the options collapse to just ""
            // — the "Main mix only" sentinel, exactly the old dialog's
            // suppressed-selector behaviour re-expressed as one option.
            SettingRow cueRow = dialog.getShell()
                    .settingRow("metronome.clickCueBus").orElseThrow();
            assertThat(cueRow.choiceOptions()).containsExactly("");
            assertThat(cueRow.getValue()).isEqualTo("");
            return null;
        });
    }

    @Test
    void cueBusOptionsMirrorTheLiveBusesWithTheOldDialogDisplayFormat() throws Exception {
        CueBus singer = CueBus.create("Singer", 1);
        CueBus drummer = CueBus.create("Drummer", 2);
        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(
                    Story307TestSupport.model("metronomeAbsorbedCueBuses"));
            dialog.setRecordingSettingsAccess(
                    stubAccess(List.of(singer, drummer), drummer.id()));

            SettingRow row = dialog.getShell()
                    .settingRow("metronome.clickCueBus").orElseThrow();
            // "Main mix only" + Singer + Drummer = 3 options; the
            // currently routed bus is preselected (value = its UUID).
            assertThat(row.choiceOptions()).containsExactly("",
                    singer.id().toString(), drummer.id().toString());
            assertThat(row.getValue()).isEqualTo(drummer.id().toString());

            // The realized ComboBox converter renders the old dialog's
            // exact display strings ("label  (Out A/B)" from the
            // stereo-pair index; "" as "Main mix only").
            dialog.selectCategory("recording");
            dialog.show();
            row.applyCss();
            @SuppressWarnings("unchecked")
            ComboBox<Object> combo = (ComboBox<Object>) row.lookup(".combo-box");
            assertThat(combo).isNotNull();
            assertThat(combo.getConverter().toString("")).isEqualTo("Main mix only");
            assertThat(combo.getConverter().toString(singer.id().toString()))
                    .isEqualTo("Singer  (Out 3/4)");
            assertThat(combo.getConverter().toString(drummer.id().toString()))
                    .isEqualTo("Drummer  (Out 5/6)");
            dialog.hide();
            return null;
        });
    }

    /** The §2.1 guard: no standalone metronome dialog, deep link instead. */
    @Test
    void standaloneDialogIsDeletedAndTheLiveEntryDeepLinks() throws IOException {
        Path module = SourceScanSupport.locateDawAppModule();
        Path ui = module.resolve("src/main/java/com/benesquivelmusic/daw/app/ui");

        assertThat(ui.resolve("MetronomeSettingsDialog.java")).doesNotExist();
        String metronomeController = Files.readString(
                ui.resolve("MetronomeController.java"), StandardCharsets.UTF_8);
        assertThat(metronomeController)
                .doesNotContain("new MetronomeSettingsDialog")
                .contains("clickRoutingSettingsOpener");
        // The deep-link literal lives in MainController's injected opener.
        String mainController = Files.readString(
                ui.resolve("MainController.java"), StandardCharsets.UTF_8);
        assertThat(mainController)
                .contains("dialog.selectCategory(\"recording\")");
    }

    private static RecordingSettingsAccess stubAccess(List<CueBus> buses, UUID currentBusId) {
        return new RecordingSettingsAccess() {
            private Snapshot snapshot = new Snapshot(true, CountInMode.OFF,
                    ClickOutput.MAIN_MIX_ONLY, Optional.ofNullable(currentBusId));

            @Override
            public Snapshot current() {
                return snapshot;
            }

            @Override
            public List<CueBus> cueBuses() {
                return buses;
            }

            @Override
            public void apply(Snapshot edited) {
                snapshot = edited;
            }
        };
    }

    private static List<String> ids(SettingsCatalogue.Group group) {
        return group.settings().stream().map(setting -> setting.id()).toList();
    }
}
