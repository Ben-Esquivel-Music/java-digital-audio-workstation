package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.DarkThemeHelper;
import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.controls.skin.TrackStripSkin;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Rectangle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 270 §5.3: armed state draws a 2 px {@code -danger} vertical bar
 * on the left edge of the row.
 *
 * <p>The bar is drawn as a {@link Rectangle} child of the skin (not as a
 * {@code -fx-border-color} on the row) per UI Design Book §7.3 so that
 * arming/disarming does not perturb the row's intrinsic height.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class TrackStripArmEdgeBarTest {

    @Test
    void armedStripHasTwoPxDangerRectangleOnTheLeftEdge() {
        Object[] data = runOnFxThread(() -> {
            TrackStrip strip = new TrackStrip();
            strip.setTrackName("Lead");
            StackPane root = new StackPane(strip);
            root.getStyleClass().add("root-pane");
            Scene scene = new Scene(root, 320, 60);
            DarkThemeHelper.applyTo(scene);
            root.applyCss();
            root.layout();

            strip.setArmed(true);
            root.applyCss();
            root.layout();

            TrackStripSkin skin = (TrackStripSkin) strip.getSkin();
            Rectangle bar = skin.armBar();
            return new Object[] {
                    bar.getWidth(),
                    bar.getX(),
                    bar.getFill(),
                    bar.isVisible(),
                    bar.isManaged()
            };
        });

        double width = (double) data[0];
        double x = (double) data[1];
        Paint fill = (Paint) data[2];
        boolean visible = (boolean) data[3];
        boolean managed = (boolean) data[4];

        assertThat(width)
                .as("armed left-edge bar is exactly 2 px wide (§5.3)")
                .isEqualTo(2.0);
        assertThat(x)
                .as("armed left-edge bar is pinned to the row's left edge")
                .isEqualTo(0.0);
        assertThat(fill)
                .as("armed left-edge bar is filled with the -danger token")
                .isEqualTo(Color.web("#E5484D"));
        assertThat(visible).as("bar visible when armed").isTrue();
        // managed=false would be wrong because we bind managed to armed
        // (so the bar collapses when disarmed) — when armed it IS managed.
        assertThat(managed).as("bar managed (visible) when armed").isTrue();
    }

    @Test
    void disarmedStripHidesTheBarSoTheRowDoesNotReflow() {
        boolean[] visibility = new boolean[2];
        runOnFxThread(() -> {
            TrackStrip strip = new TrackStrip();
            StackPane root = new StackPane(strip);
            root.getStyleClass().add("root-pane");
            new Scene(root, 320, 60);
            root.applyCss();
            root.layout();
            TrackStripSkin skin = (TrackStripSkin) strip.getSkin();

            strip.setArmed(true);
            root.applyCss();
            root.layout();
            visibility[0] = skin.armBar().isVisible();

            strip.setArmed(false);
            root.applyCss();
            root.layout();
            visibility[1] = skin.armBar().isVisible();
            return null;
        });
        assertThat(visibility[0]).as("armed: bar visible").isTrue();
        assertThat(visibility[1]).as("disarmed: bar hidden").isFalse();
    }
}
