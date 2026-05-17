package com.benesquivelmusic.daw.app.ui;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Rectangle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * UI Design Book §5.10 / §7.3 — the notification toast is a pill
 * ({@code -surface-1} bg, NOT a coloured banner) with a 4 px left accent
 * {@link Rectangle} in the semantic colour drawn as a child, not a CSS
 * border.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class NotificationBarPillStyleTest {

    private static final String STYLES_CSS =
            "/com/benesquivelmusic/daw/app/ui/styles.css";

    @Test
    void warningPillHasSurfaceBackgroundAndWarnAccentBar() {
        Color[] result = new Color[3]; // [0]=bar bg, [1]=accent fill, [2]=msg text
        Color[] expected = new Color[3]; // [0]=-surface-1, [1]=-warn, [2]=-text-hi
        Rectangle[] accent = new Rectangle[1];

        runOnFxThread(() -> {
            // Reproduce the production .root-pane anchor exactly (the
            // same lookup-resolution path TokenResolutionSmokeTest uses).
            // The whole notification subtree is added under the .root-pane
            // so the forwarded -ntf-* tokens resolve. Sibling probes give
            // the expected token values (palette-swap safe — no hex here).
            BorderPane rootPane = new BorderPane();
            rootPane.getStyleClass().add("root-pane");

            Region surfaceProbe = new Region();
            surfaceProbe.setStyle("-fx-background-color: -surface-1;");
            Region warnProbe = new Region();
            warnProbe.setStyle("-fx-background-color: -warn;");
            Label textHiProbe = new Label("x");
            textHiProbe.setStyle("-fx-text-fill: -text-hi;");
            rootPane.getChildren().addAll(surfaceProbe, warnProbe, textHiProbe);

            NotificationBar bar = new NotificationBar();
            rootPane.getChildren().add(bar);
            bar.show(NotificationLevel.WARNING, "Track 03 is armed without an input");

            Scene scene = new Scene(rootPane, 600, 200);
            scene.getStylesheets().add(
                    NotificationBarPillStyleTest.class
                            .getResource(STYLES_CSS).toExternalForm());
            rootPane.applyCss();
            rootPane.layout();
            // Force a full CSS + raster pass so scene-stylesheet selector
            // rules that reference .root-pane looked-up colours resolve
            // (a plain applyCss() leaves them unresolved in a freshly
            // built headless scene — see memory: JavaFX headless pitfalls,
            // "Prism pipeline stays UNKNOWN until rasterisation forces it").
            rootPane.snapshot(null, null);
            rootPane.applyCss();
            rootPane.layout();

            expected[0] = (Color) surfaceProbe.getBackground().getFills().getFirst().getFill();
            expected[1] = (Color) warnProbe.getBackground().getFills().getFirst().getFill();
            expected[2] = (Color) textHiProbe.getTextFill();
            Paint barBg = bar.getBackground() == null ? null
                    : bar.getBackground().getFills().getFirst().getFill();
            result[0] = (Color) barBg;
            accent[0] = findAccentBar(bar);
            result[1] = (Color) accent[0].getFill();
            Labeled msg = (Labeled) bar.lookup(".notification-message");
            result[2] = (Color) msg.getTextFill();
            return null;
        });

        // §5.10 — the pill background is -surface-1, NOT -warn.
        assertThat(result[0]).as("pill background must resolve (not null)").isNotNull();
        assertColorEquals(result[0], expected[0], "pill background must be -surface-1");
        assertThat(colorDistance(result[0], expected[1]))
                .as("pill background must NOT be the level colour (-warn)")
                .isGreaterThan(0.05);

        // §7.3 — a 4 px child Rectangle filled -warn at the left edge.
        assertThat(accent[0]).as("accent bar Rectangle must exist").isNotNull();
        assertThat(accent[0].getWidth()).isEqualTo(4.0);
        assertColorEquals(result[1], expected[1], "accent bar fill must be -warn");
        assertThat(accent[0].isManaged())
                .as("accent bar is unmanaged so it does not perturb the row")
                .isFalse();
        assertThat(accent[0].getLayoutX())
                .as("accent bar sits at the left edge")
                .isLessThan(1.0);

        // §5.10 — foreground text is -text-hi, NOT the level colour.
        assertThat(result[2]).as("message text fill must resolve (not null)").isNotNull();
        assertColorEquals(result[2], expected[2], "pill message text must be -text-hi");
        assertThat(colorDistance(result[2], expected[1]))
                .as("message text must NOT be the level colour (-warn)")
                .isGreaterThan(0.05);
    }

    private static void assertColorEquals(Color got, Color want, String desc) {
        assertThat(got.getRed()).as(desc + " (red)").isCloseTo(want.getRed(), offset(0.01));
        assertThat(got.getGreen()).as(desc + " (green)").isCloseTo(want.getGreen(), offset(0.01));
        assertThat(got.getBlue()).as(desc + " (blue)").isCloseTo(want.getBlue(), offset(0.01));
    }

    private static double colorDistance(Color a, Color b) {
        double dr = a.getRed() - b.getRed();
        double dg = a.getGreen() - b.getGreen();
        double db = a.getBlue() - b.getBlue();
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    private static Rectangle findAccentBar(Parent parent) {
        for (var node : parent.getChildrenUnmodifiable()) {
            if (node instanceof Rectangle r
                    && r.getStyleClass().contains("notification-accent-bar")) {
                return r;
            }
            if (node instanceof Parent p) {
                Rectangle found = findAccentBar(p);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
