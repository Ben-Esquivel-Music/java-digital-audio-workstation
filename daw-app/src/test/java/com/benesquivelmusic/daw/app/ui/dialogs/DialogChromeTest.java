package com.benesquivelmusic.daw.app.ui.dialogs;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.icons.DawgIcon;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.text.Font;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * §5.9 / §3.2 / §3.4 — the shared {@link DawgDialog} chrome:
 * <ul>
 *   <li>a flat (non-gradient, non-image) header background;</li>
 *   <li>an accent-filled primary footer button;</li>
 *   <li>a muted, 10 px, non-purple section header (§7.6 veto).</li>
 * </ul>
 *
 * <p>Follows {@code InspectorSectionStylingTest}: resolved values are
 * extracted out of the FX runnable and asserted on the test thread
 * (assertions inside an FX runnable are swallowed → false green —
 * a known project pitfall).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class DialogChromeTest {

    /** Palette-A {@code -accent} (#7C8CFF) is blue-dominant. */
    private static final double ACCENT_BLUE_DOMINANCE_MIN = 0.20;

    @Test
    void headerIsFlatPrimaryIsAccentSectionHeaderIsMuted() {
        Paint[] headerFill = new Paint[1];
        boolean[] headerHasImage = new boolean[1];
        Paint[] primaryFill = new Paint[1];
        Paint[] sectionFill = new Paint[1];
        double[] sectionFontSize = new double[1];

        runOnFxThread(() -> {
            DawgDialog<Void> dialog = new DawgDialog<>();
            dialog.setTitle("Chrome Test");
            dialog.setHeaderText("Chrome Test");
            Label sectionBody = new Label("body");
            dialog.addSection("Scan", sectionBody)
                  .primary("Save", () -> { })
                  .secondary("Cancel", () -> { })
                  .sized(DawgDialog.Size.MEDIUM);

            DialogPane pane = dialog.getDialogPane();
            Pane root = new Pane(pane);
            new Scene(root, 720, 480);
            root.applyCss();
            root.layout();

            // (a) header panel — must be a flat fill, not a gradient
            //     or image. The header-panel sub-node is created lazily
            //     once the dialog has a header.
            Region header = (Region) pane.lookup(".header-panel");
            if (header != null) {
                headerHasImage[0] = !header.getBackground().getImages().isEmpty();
                headerFill[0] = firstFill(header.getBackground());
            }

            // (b) primary button — accent-filled region background.
            Button primary = (Button) pane.lookupButton(
                    primaryButtonType(pane));
            primary.applyCss();
            primaryFill[0] = firstFill(primary.getBackground());

            // (c) section header label — muted, 10 px, not purple.
            Label sectionHeader = findSectionHeader(pane);
            sectionFill[0] = sectionHeader.getTextFill();
            Font f = sectionHeader.getFont();
            sectionFontSize[0] = f.getSize();
            return null;
        });

        // (a) Flat header — no background image, fill is a plain Color
        //     (not a LinearGradient).
        assertThat(headerHasImage[0])
                .as("§3.4 — dialog header must not use a background image")
                .isFalse();
        assertThat(headerFill[0])
                .as("§3.4 — dialog header fill must be a flat Color, not a gradient")
                .isInstanceOf(Color.class);

        // (b) Primary button resolves to the accent family. Derive the
        //     judgement from the resolved CSS, never a hard-coded hex:
        //     the Palette-A accent #7C8CFF is strongly blue-dominant.
        assertThat(primaryFill[0])
                .as("primary button background must resolve from CSS")
                .isInstanceOf(Color.class);
        Color primary = (Color) primaryFill[0];
        double primaryBlueDominance =
                primary.getBlue() - Math.max(primary.getRed(), primary.getGreen());
        assertThat(primaryBlueDominance)
                .as("§5.9 / §3.1 — primary button must resolve to the "
                        + "accent (blue-dominant indigo), not a neutral surface")
                .isGreaterThan(ACCENT_BLUE_DOMINANCE_MIN);

        // (c) Section header — muted family, 10 px, NOT the saturated
        //     accent. Reuses InspectorSectionStylingTest's §7.6
        //     blue-dominance invariant verbatim.
        assertThat(sectionFontSize[0]).isEqualTo(10.0);
        assertThat(sectionFill[0]).isInstanceOf(Color.class);
        Color section = (Color) sectionFill[0];
        double sectionBlueDominance =
                section.getBlue() - Math.max(section.getRed(), section.getGreen());
        assertThat(sectionBlueDominance)
                .as("§7.6 — section header must not resolve to the "
                        + "saturated -accent (purple)")
                .isLessThan(0.10);
    }

    /**
     * §5.9 — an informational dialog with no footer dismiss button keeps
     * the secondary header close glyph, and it is <em>functional</em>
     * (wired to dismiss; not decorative) and styled
     * {@code .dawg-dialog-close} so the CSS-resolved tint applies.
     */
    @Test
    void informationalDialogKeepsFunctionalCloseGlyph() {
        Node[] graphic = new Node[1];
        boolean[] clickWired = new boolean[1];
        runOnFxThread(() -> {
            DawgDialog<ButtonType> dialog = DawgDialog.info("Title", "Message");
            DialogPane pane = dialog.getDialogPane();
            new Scene(new Pane(pane), 480, 320);
            pane.applyCss();
            graphic[0] = dialog.getGraphic();
            if (graphic[0] != null) {
                clickWired[0] = graphic[0].getOnMouseClicked() != null;
            }
            return null;
        });

        assertThat(graphic[0])
                .as("§5.9 — informational (no footer Cancel) dialog keeps "
                        + "the header close glyph")
                .isInstanceOf(DawgIcon.class);
        assertThat(((Node) graphic[0]).getStyleClass())
                .as("close glyph must carry .dawg-dialog-close so its tint "
                        + "resolves from CSS -fx-icon-color")
                .contains("dawg-dialog-close");
        assertThat(clickWired[0])
                .as("§5.9 — the close glyph is a dismiss path, not "
                        + "decorative: it must have a click handler")
                .isTrue();
    }

    /**
     * §5.9 — when the footer carries a Cancel/Close button it is the
     * dismiss path; the now-redundant header glyph is retracted. Locks
     * the extends-only migration case (e.g. ChannelCpuBudgetDialog /
     * AtmosSessionConfigDialog add APPLY+CANCEL after super()).
     */
    @Test
    void footerCancelRetractsCloseGlyph() {
        Node[] graphic = new Node[1];
        runOnFxThread(() -> {
            DawgDialog<Void> dialog = new DawgDialog<>();
            dialog.setHeaderText("Has Cancel");
            dialog.getDialogPane().getButtonTypes()
                    .setAll(ButtonType.APPLY, ButtonType.CANCEL);
            new Scene(new Pane(dialog.getDialogPane()), 480, 320);
            dialog.getDialogPane().applyCss();
            graphic[0] = dialog.getGraphic();
            return null;
        });

        assertThat(graphic[0])
                .as("§5.9 — a footer Cancel is the dismiss path; the "
                        + "header close glyph must be retracted")
                .isNull();
    }

    /**
     * Retraction must never stomp a domain header graphic a subclass set
     * itself via {@code setGraphic(...)} (the AudioSettings / Backup /
     * Settings pattern: own header icon, then APPLY+CANCEL added).
     */
    @Test
    void subclassHeaderGraphicSurvivesRetraction() {
        Label domain = new Label("domain-icon");
        Node[] graphic = new Node[1];
        runOnFxThread(() -> {
            DawgDialog<Void> dialog = new DawgDialog<>();
            dialog.setHeaderText("Own Graphic");
            dialog.setGraphic(domain);
            dialog.getDialogPane().getButtonTypes()
                    .setAll(ButtonType.APPLY, ButtonType.CANCEL);
            new Scene(new Pane(dialog.getDialogPane()), 480, 320);
            dialog.getDialogPane().applyCss();
            graphic[0] = dialog.getGraphic();
            return null;
        });

        assertThat(graphic[0])
                .as("a subclass-set header graphic must survive close-glyph "
                        + "retraction unstomped")
                .isSameAs(domain);
    }

    private static Paint firstFill(Background bg) {
        if (bg == null) {
            return null;
        }
        for (BackgroundFill fill : bg.getFills()) {
            if (fill.getFill() != null) {
                return fill.getFill();
            }
        }
        return null;
    }

    private static ButtonType primaryButtonType(DialogPane pane) {
        return pane.getButtonTypes().stream()
                .filter(bt -> bt.getButtonData()
                        == javafx.scene.control.ButtonBar.ButtonData.OK_DONE)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no primary button type"));
    }

    private static Label findSectionHeader(DialogPane pane) {
        for (var node : pane.lookupAll(".dawg-dialog-section-header")) {
            if (node instanceof Label label) {
                return label;
            }
        }
        // Fallback: walk the body VBox directly (lookupAll can miss
        // un-applied subtrees on some headless pulses).
        if (pane.getContent() instanceof VBox body) {
            for (var child : body.getChildren()) {
                if (child instanceof Label label
                        && label.getStyleClass().contains("dawg-dialog-section-header")) {
                    return label;
                }
            }
        }
        throw new AssertionError("no .dawg-dialog-section-header label found");
    }
}
