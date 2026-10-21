package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.dialogs.DawgDialog;
import com.benesquivelmusic.daw.core.persistence.AssetReference;
import com.benesquivelmusic.daw.core.persistence.MissingAsset;
import com.benesquivelmusic.daw.core.persistence.MissingAssetReason;
import com.benesquivelmusic.daw.core.persistence.MissingAssetsReport;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.stage.Modality;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 329, decision D15 — the missing-assets report: a non-modal
 * {@link DawgDialog} with one row per asset (its D6 label, its path, the
 * clips that use it, accessible text), a footer Close that is the dialog's
 * cancel ({@link ButtonBar.ButtonData#CANCEL_CLOSE}), and the link to the
 * archive flow; one instance presented again and again, updated in place,
 * closed when its open is retired. A row names every clip that holds its
 * asset while the summary counts only the silent ones (decisions D24, D26),
 * an unreadable row's reason is on its detail line (D35), and keyboard
 * focus on a row's control survives a rebuild of the rows.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class MissingAssetsDialogTest {

    private static final Path LIBRARY = Path.of("library").toAbsolutePath();

    @Test
    void eachAssetIsOneLabelledAccessibleRowAndTheFooterCloseIsTheCancel() throws Exception {
        MissingAssetsReport report = new MissingAssetsReport(List.of(
                row(new AssetReference.SingleFile(LIBRARY.resolve("kick.wav").toString()),
                        MissingAssetReason.NOT_FOUND, "not found", "1", "2"),
                row(new AssetReference.SingleFile(LIBRARY.resolve("pad.wav").toString()),
                        MissingAssetReason.UNREADABLE, "not a RIFF file", "3")), List.of());
        RecordingReportActions actions = new RecordingReportActions();
        AtomicInteger archives = actions.archives;

        runOnFx(() -> {
            MissingAssetsDialog dialog = new MissingAssetsDialog(null, report, actions);

            assertThat(dialog).isInstanceOf(DawgDialog.class);
            assertThat(dialog.getModality()).isEqualTo(Modality.NONE);
            assertThat(dialog.isShowing()).as("shown only when presented").isFalse();
            List<Node> rows = dialog.rowNodes();
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).getAccessibleText())
                    .isEqualTo("Missing: " + LIBRARY.resolve("kick.wav") + ", used by 2 clips");
            assertThat(rows.get(1).getAccessibleText())
                    .isEqualTo("Can't read: " + LIBRARY.resolve("pad.wav") + ", used by 1 clip");
            assertThat(texts(rows.get(1))).as("the reason is on the wrapped detail line, not in the label")
                    .contains("Can't read", "not a RIFF file");
            assertThat(((Label) rows.get(1).lookup(".missing-assets-detail")).isWrapText()).isTrue();
            assertThat(texts(rows.get(0))).contains("Missing", LIBRARY.resolve("kick.wav").toString(),
                    "Used by 2 clips: Track — Clip 1, Track — Clip 2");
            assertThat(((Label) rows.get(0).lookup(".missing-assets-path")).getTooltip().getText())
                    .as("the full path in the tooltip").isEqualTo(LIBRARY.resolve("kick.wav").toString());

            ButtonType close = dialog.getDialogPane().getButtonTypes().stream()
                    .filter(type -> type.getButtonData() == ButtonBar.ButtonData.CANCEL_CLOSE)
                    .findFirst().orElseThrow();
            Button closeButton = (Button) dialog.getDialogPane().lookupButton(close);
            assertThat(closeButton.getText()).isEqualTo("Close");
            assertThat(closeButton.isVisible()).as("a visible footer Close").isTrue();
            assertThat(closeButton.getAccessibleText()).isNotBlank();

            Hyperlink archive = (Hyperlink) dialog.getDialogPane().lookup(".missing-assets-archive");
            assertThat(archive.getAccessibleText()).isNotBlank();
            archive.fire();
            assertThat(archives).as("the link runs the archive flow").hasValue(1);
        });
    }

    @Test
    void oneInstanceIsPresentedAgainUpdatedInPlaceAndDismissed() throws Exception {
        MissingAssetsReport one = new MissingAssetsReport(List.of(
                row(new AssetReference.SingleFile(LIBRARY.resolve("kick.wav").toString()),
                        MissingAssetReason.NOT_FOUND, "not found", "1")), List.of());
        MissingAssetsDialog dialog = onFx(() -> new MissingAssetsDialog(null, one, new RecordingReportActions()));

        runOnFx(dialog::present);
        assertThat(onFx(dialog::isShowing)).isTrue();
        runOnFx(dialog::present);
        assertThat(onFx(dialog::isShowing)).as("presented again, still the one window").isTrue();

        runOnFx(() -> dialog.update(MissingAssetsReport.EMPTY));
        assertThat(onFx(dialog::rowNodes)).isEmpty();
        assertThat(onFx(() -> texts(dialog.getDialogPane()))).contains("No missing assets left to review.");

        runOnFx(dialog::dismiss);
        assertThat(onFx(dialog::isShowing)).isFalse();
    }

    @Test
    void aTakeRowNamesItsFolderAndSegmentCountAndShowsWhichSegmentsAreMissing() throws Exception {
        Path take = LIBRARY.resolve("2026-10-20T10-00-00_take-0001").resolve("track-a");
        MissingAsset row = row(new AssetReference.Take(List.of(take.resolve("segment-000.wav").toString(),
                        take.resolve("segment-001.wav").toString())), MissingAssetReason.NOT_FOUND,
                "1 of 2 segments cannot be read: segment-001.wav (not found)", "1");

        assertThat(MissingAssetsDialog.locationText(row.reference())).isEqualTo(take + " (2 segments)");
        runOnFx(() -> {
            MissingAssetsDialog dialog = new MissingAssetsDialog(null,
                    new MissingAssetsReport(List.of(row), List.of()), new RecordingReportActions());
            assertThat(texts(dialog.rowNodes().getFirst()))
                    .contains("1 of 2 segments cannot be read: segment-001.wav (not found)");
        });
    }

    @Test
    void aRowNamesEveryClipThatHoldsItsAssetAndTheSummaryCountsOnlyTheSilentOnes() throws Exception {
        List<MissingAsset.Holder> thirty = holders(1, 30);
        MissingAsset shorter = new MissingAsset(new AssetReference.SingleFile(LIBRARY.resolve("vox.wav").toString()),
                MissingAssetReason.CHANGED_ON_DISK, "4 of them run past its end; holds 0.50 s; the clip spans"
                + " 1.50 s at the current tempo; loaded as it is", thirty, List.of());
        List<MissingAsset.Holder> nine = holders(31, 9);
        MissingAsset adm = new MissingAsset(new AssetReference.SingleFile(LIBRARY.resolve("atmos.wav").toString()),
                MissingAssetReason.ADM_OBJECT_UNKNOWN, "1 of them selects no bed or object", nine,
                List.of(nine.getLast()));
        MissingAssetsReport report = new MissingAssetsReport(List.of(shorter, adm), List.of());

        runOnFx(() -> {
            MissingAssetsDialog dialog = new MissingAssetsDialog(null, report, new RecordingReportActions());
            List<Node> rows = dialog.rowNodes();

            assertThat(texts(rows.get(0))).as("every holder counted; the subset is the detail's").contains(
                    "Shorter than the clip", shorter.detail(),
                    "Used by 30 clips: Track — Clip 1, Track — Clip 2, Track — Clip 3, Track — Clip 4,"
                            + " Track — Clip 5, Track — Clip 6 and 24 more");
            assertThat(texts(rows.get(1))).contains("Needs re-import (ADM: object unknown)", adm.detail(),
                    "Used by 9 clips: Track — Clip 31, Track — Clip 32, Track — Clip 33, Track — Clip 34,"
                            + " Track — Clip 35, Track — Clip 36 and 3 more");
            assertThat(texts(dialog.getDialogPane()))
                    .as("one clip is silent: the ADM clip whose name selects nothing")
                    .contains("1 missing asset (1 clip silent), 1 asset shorter than its clips");
        });
    }

    @Test
    void keyboardFocusOnARowsControlStaysAmongTheRowsWhenTheyAreRebuilt() throws Exception {
        MissingAsset kick = row(new AssetReference.SingleFile(LIBRARY.resolve("kick.wav").toString()),
                MissingAssetReason.NOT_FOUND, "not found", "1");
        MissingAsset snare = row(new AssetReference.SingleFile(LIBRARY.resolve("snare.wav").toString()),
                MissingAssetReason.NOT_FOUND, "not found", "2");
        MissingAsset pad = row(new AssetReference.SingleFile(LIBRARY.resolve("pad.wav").toString()),
                MissingAssetReason.NOT_FOUND, "not found", "3");
        RecordingReportActions actions = new RecordingReportActions();
        MissingAssetsDialog dialog = onFx(() -> new MissingAssetsDialog(null,
                new MissingAssetsReport(List.of(kick, snare, pad), List.of()), actions));
        try {
            runOnFx(dialog::present);
            runOnFx(() -> {
                Button skipKick = (Button) dialog.rowNodes().getFirst().lookup(".missing-assets-skip");
                skipKick.requestFocus();
                assertThat(dialog.getDialogPane().getScene().getFocusOwner())
                        .as("fixture: the first row's Skip has focus").isSameAs(skipKick);

                // Skip removed the row: the rebuild puts focus on the same control of the next row.
                dialog.update(new MissingAssetsReport(List.of(snare, pad), List.of()));
                Button skipSnare = (Button) dialog.rowNodes().getFirst().lookup(".missing-assets-skip");
                assertThat(dialog.getDialogPane().getScene().getFocusOwner()).isSameAs(skipSnare);

                // A row whose work is in flight disables its buttons: focus moves on to a later row's.
                actions.statuses.put(snare.reference(), new AssetRelinkFlow.RowStatus.Working(1));
                dialog.update(dialog.report());
                Button locatePad = (Button) dialog.rowNodes().get(1).lookup(".missing-assets-locate-file");
                assertThat(dialog.getDialogPane().getScene().getFocusOwner())
                        .as("the next enabled control, in a later row").isSameAs(locatePad);

                // No row left: Locate folder is disabled with nothing to search, so Close.
                dialog.update(MissingAssetsReport.EMPTY);
                Node close = dialog.getDialogPane().lookupButton(dialog.getDialogPane().getButtonTypes().stream()
                        .filter(type -> type.getButtonData() == ButtonBar.ButtonData.CANCEL_CLOSE)
                        .findFirst().orElseThrow());
                assertThat(dialog.getDialogPane().getScene().getFocusOwner()).isSameAs(close);
            });
        } finally {
            runOnFx(dialog::dismiss);
        }
    }

    private static List<MissingAsset.Holder> holders(int first, int count) {
        List<MissingAsset.Holder> holders = new java.util.ArrayList<>();
        for (int clip = first; clip < first + count; clip++) {
            holders.add(new MissingAsset.Holder(Integer.toString(clip), "Track", "Clip " + clip));
        }
        return holders;
    }

    private static MissingAsset row(AssetReference reference, MissingAssetReason reason, String detail,
                                    String... clipIds) {
        List<MissingAsset.Holder> holders = new java.util.ArrayList<>();
        for (String clipId : clipIds) {
            holders.add(new MissingAsset.Holder(clipId, "Track", "Clip " + clipId));
        }
        return new MissingAsset(reference, reason, detail, holders);
    }

    /** Every label text under {@code node}. */
    private static List<String> texts(Node node) {
        List<String> texts = new java.util.ArrayList<>();
        for (Node label : node.lookupAll(".label")) {
            if (label instanceof Label text) {
                texts.add(text.getText());
            }
        }
        return texts;
    }
}
