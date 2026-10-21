package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AssetReference;
import com.benesquivelmusic.daw.core.persistence.MissingAsset;
import com.benesquivelmusic.daw.core.persistence.MissingAssetReason;
import com.benesquivelmusic.daw.core.persistence.MissingAssetsReport;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 329, decisions D15–D17 — the report's relink controls: every row
 * has {@code Locate file…} and {@code Skip}, and the report a
 * {@code Locate folder…}, each with accessible text and each asking the
 * owner — Skip's saying whether the asset's clips stay silent, keep playing
 * as loaded, or how many of them stay silent, counted over every row of the
 * asset Skip hides (decision D43); a row whose work is in
 * flight has both buttons disabled; a row's status line says what the last
 * attempt found; and a running search names its folder and offers
 * {@code Cancel search}.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class MissingAssetsDialogRelinkControlsTest {

    private static final Path LIBRARY = Path.of("library").toAbsolutePath();
    private static final AssetReference KICK = new AssetReference.SingleFile(LIBRARY.resolve("kick.wav").toString());
    private static final AssetReference PAD = new AssetReference.SingleFile(LIBRARY.resolve("pad.wav").toString());
    private static final AssetReference SNARE = new AssetReference.SingleFile(LIBRARY.resolve("snare.wav").toString());

    @Test
    void everyRowAndTheReportHaveAccessibleRelinkControlsThatAskTheOwner() throws Exception {
        RecordingReportActions actions = new RecordingReportActions();
        MissingAssetsReport report = report(KICK, PAD);

        runOnFx(() -> {
            MissingAssetsDialog dialog = new MissingAssetsDialog(null, report, actions);
            List<Node> rows = dialog.rowNodes();
            Button locate = button(rows.get(0), "missing-assets-locate-file");
            Button skip = button(rows.get(1), "missing-assets-skip");
            assertThat(locate.getText()).isEqualTo("Locate file…");
            assertThat(locate.getAccessibleText()).isEqualTo("Locate the file of " + KICK.references().getFirst());
            assertThat(skip.getText()).isEqualTo("Skip");
            assertThat(skip.getAccessibleText())
                    .isEqualTo("Skip " + PAD.references().getFirst() + " for this session; its clips stay silent");
            Button folder = (Button) dialog.getDialogPane().lookup(".missing-assets-locate-folder");
            Button cancel = (Button) dialog.getDialogPane().lookup(".missing-assets-cancel-search");
            assertThat(folder.getText()).isEqualTo("Locate folder…");
            assertThat(folder.getAccessibleText()).isNotBlank();
            assertThat(cancel.getAccessibleText()).isNotBlank();
            assertThat(cancel.isVisible()).as("no search runs").isFalse();

            locate.fire();
            skip.fire();
            folder.fire();

            assertThat(actions.located).extracting(MissingAsset::reference).containsExactly(KICK);
            assertThat(actions.skipped).extracting(MissingAsset::reference).containsExactly(PAD);
            assertThat(actions.folderSearches).hasValue(1);
        });
    }

    @Test
    void skipOnARowThatSilencesNoClipSaysItsClipsKeepPlayingAsLoaded() throws Exception {
        // A file shorter than its clip: the clip kept its audio, so skipping the row silences nothing.
        MissingAsset shorter = new MissingAsset(PAD, MissingAssetReason.CHANGED_ON_DISK,
                "holds 0.50 s; the clip spans 1.00 s at the current tempo; loaded as it is",
                List.of(new MissingAsset.Holder("1", "Track", "Clip 1")));
        assertThat(shorter.silentClipCount()).as("fixture: no clip of the row is silent").isZero();

        runOnFx(() -> {
            MissingAssetsDialog dialog = new MissingAssetsDialog(null,
                    new MissingAssetsReport(List.of(shorter), List.of()), new RecordingReportActions());
            assertThat(button(dialog.rowNodes().getFirst(), "missing-assets-skip").getAccessibleText())
                    .isEqualTo("Skip " + PAD.references().getFirst()
                            + " for this session; its clips keep playing as loaded");
        });
    }

    @Test
    void skipOnAnAdmRowThatSilencesSomeOfItsClipsSaysHowManyStaySilent() throws Exception {
        // An ADM master re-rendered without objects 5 and 6: the bed and objects 1-4 keep playing.
        List<MissingAsset.Holder> seven = holders(7);
        MissingAsset adm = new MissingAsset(PAD, MissingAssetReason.ADM_OBJECT_UNKNOWN,
                "2 of them select no bed or object of the file (it has: …)", seven, seven.subList(5, 7));
        assertThat(adm.silentClipCount()).as("fixture: 2 of the 7 clips are silent").isEqualTo(2);

        runOnFx(() -> {
            MissingAssetsDialog dialog = new MissingAssetsDialog(null,
                    new MissingAssetsReport(List.of(adm), List.of()), new RecordingReportActions());
            assertThat(button(dialog.rowNodes().getFirst(), "missing-assets-skip").getAccessibleText())
                    .isEqualTo("Skip " + PAD.references().getFirst()
                            + " for this session; 2 of its clips stay silent, the others keep playing as loaded");
        });
    }

    @Test
    void skipOnEitherRowOfAnAssetWithTwoRowsCountsTheSilentClipsOfBoth() throws Exception {
        // Skip hides both rows: the shorter row's Skip also leaves the ADM row's silent clip silent.
        List<MissingAsset.Holder> seven = holders(7);
        MissingAsset shorter = new MissingAsset(PAD, MissingAssetReason.CHANGED_ON_DISK,
                "3 of them run past its end; holds 0.50 s; the clip spans 1.00 s at the current tempo;"
                        + " loaded as it is", seven, List.of());
        MissingAsset adm = new MissingAsset(PAD, MissingAssetReason.ADM_OBJECT_UNKNOWN,
                "1 of them selects no bed or object of the file (it has: …)", seven, List.of(seven.getLast()));
        String some = "Skip " + PAD.references().getFirst()
                + " for this session; 1 of its clips stays silent, the others keep playing as loaded";

        runOnFx(() -> {
            MissingAssetsDialog dialog = new MissingAssetsDialog(null,
                    new MissingAssetsReport(List.of(shorter, adm), List.of()), new RecordingReportActions());
            List<Node> rows = dialog.rowNodes();
            assertThat(rows).as("fixture: one asset, two rows").hasSize(2);
            assertThat(button(rows.get(0), "missing-assets-skip").getAccessibleText())
                    .as("the shorter row's Skip").isEqualTo(some);
            assertThat(button(rows.get(1), "missing-assets-skip").getAccessibleText())
                    .as("the ADM row's Skip").isEqualTo(some);
        });
    }

    @Test
    void aRowsStatusDisablesItsButtonsWhileInFlightAndSaysWhatTheLastAttemptFound() throws Exception {
        RecordingReportActions actions = new RecordingReportActions();
        actions.statuses.put(KICK, new AssetRelinkFlow.RowStatus.Working(7));
        actions.statuses.put(PAD, new AssetRelinkFlow.RowStatus.Refused(MissingAssetReason.UNREADABLE,
                "/found/pad.wav: not a RIFF file"));
        actions.statuses.put(SNARE, new AssetRelinkFlow.RowStatus.Ambiguous(3));

        runOnFx(() -> {
            MissingAssetsDialog dialog = new MissingAssetsDialog(null, report(KICK, PAD, SNARE), actions);
            List<Node> rows = dialog.rowNodes();

            assertThat(button(rows.get(0), "missing-assets-locate-file").isDisabled()).isTrue();
            assertThat(button(rows.get(0), "missing-assets-skip").isDisabled()).isTrue();
            assertThat(statusLine(rows.get(0), "missing-assets-status")).isEqualTo("Checking…");

            assertThat(button(rows.get(1), "missing-assets-locate-file").isDisabled()).isFalse();
            assertThat(statusLine(rows.get(1), "missing-assets-status-attention"))
                    .isEqualTo("Not relinked — can't read: /found/pad.wav: not a RIFF file");

            assertThat(button(rows.get(2), "missing-assets-skip").isDisabled()).isFalse();
            assertThat(statusLine(rows.get(2), "missing-assets-status-attention"))
                    .isEqualTo("3 candidates — use Locate file…");
        });
    }

    @Test
    void aRunningSearchNamesItsFolderAndCanBeCancelled() throws Exception {
        RecordingReportActions actions = new RecordingReportActions();
        Path moved = LIBRARY.resolveSibling("moved");
        actions.searched = moved;

        runOnFx(() -> {
            MissingAssetsDialog dialog = new MissingAssetsDialog(null, report(KICK), actions);
            Button folder = (Button) dialog.getDialogPane().lookup(".missing-assets-locate-folder");
            Button cancel = (Button) dialog.getDialogPane().lookup(".missing-assets-cancel-search");
            Label searching = (Label) dialog.getDialogPane().lookup(".missing-assets-searching");
            assertThat(folder.isDisabled()).as("one search at a time").isTrue();
            assertThat(searching.getText()).isEqualTo("Searching " + moved + "…");
            assertThat(searching.getAccessibleText()).isEqualTo(searching.getText());
            assertThat(cancel.isVisible()).isTrue();

            cancel.fire();
            assertThat(actions.cancelledSearches).hasValue(1);

            actions.searched = null;
            dialog.update(dialog.report());
            assertThat(cancel.isVisible()).as("the search is over").isFalse();
            assertThat(folder.isDisabled()).isFalse();
        });
    }

    private static MissingAssetsReport report(AssetReference... assets) {
        List<MissingAsset> rows = new ArrayList<>();
        int clip = 0;
        for (AssetReference asset : assets) {
            clip++;
            rows.add(new MissingAsset(asset, MissingAssetReason.NOT_FOUND, "not found",
                    List.of(new MissingAsset.Holder(Integer.toString(clip), "Track", "Clip " + clip))));
        }
        return new MissingAssetsReport(rows, List.of());
    }

    private static List<MissingAsset.Holder> holders(int count) {
        List<MissingAsset.Holder> holders = new ArrayList<>(count);
        for (int clip = 1; clip <= count; clip++) {
            holders.add(new MissingAsset.Holder(Integer.toString(clip), "Track", "Clip " + clip));
        }
        return holders;
    }

    private static Button button(Node row, String styleClass) {
        Node found = row.lookup("." + styleClass);
        assertThat(found).as("the row has a .%s", styleClass).isInstanceOf(Button.class);
        return (Button) found;
    }

    private static String statusLine(Node row, String styleClass) {
        Node found = row.lookup("." + styleClass);
        assertThat(found).as("the row has a .%s status line", styleClass).isInstanceOf(Label.class);
        Label line = (Label) found;
        assertThat(line.getAccessibleText()).isEqualTo(line.getText());
        return line.getText();
    }
}
