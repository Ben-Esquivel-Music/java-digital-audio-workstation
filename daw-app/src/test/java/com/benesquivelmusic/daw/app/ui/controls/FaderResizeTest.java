package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.controls.skin.FaderSkin;

import javafx.scene.Scene;
import javafx.scene.layout.Pane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The fader's cap snaps proportionally to the container height — every
 * internal dimension is derived from the assigned width / height in
 * {@link FaderSkin#layoutChildren}.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class FaderResizeTest {

    private static double capYAt(double height) {
        return runOnFxThread(() -> {
            Fader f = Fader.create()
                    .min(-96).max(12).defaultValue(0)
                    .curve(Fader.TravelCurve.LOG_DB)
                    .showMeter(false)
                    .build();
            Pane root = new Pane(f);
            new Scene(root, 40, height + 20);
            root.applyCss();
            f.resizeRelocate(0, 0, 20, height);
            f.layout();
            FaderSkin skin = (FaderSkin) f.getSkin();
            return skin.capCentreY();
        });
    }

    @Test
    void capPositionScalesProportionallyWithHeight() {
        // For value=0 (default) with LOG_DB, the cap should be at 75%
        // travel from the bottom regardless of container height. The
        // expected proportion is identical even though the literal pixel
        // offset differs.
        double y100 = capYAt(100);
        double y200 = capYAt(200);

        // Compute expected proportions: at value=0 with LOG_DB,
        // travelTop = 6 px, travelRange = h - 12, capCentre = 6 + (1 - 0.75) * (h - 12).
        double expected100 = 6.0 + 0.25 * (100 - 12);
        double expected200 = 6.0 + 0.25 * (200 - 12);
        assertThat(y100).isCloseTo(expected100, within(1.0));
        assertThat(y200).isCloseTo(expected200, within(1.0));

        // And the cap-Y distance from the top scales proportionally.
        assertThat(y200).isGreaterThan(y100);
    }

    @Test
    void columnHeightTracksAssignedHeight() {
        double[] heights = new double[2];
        runOnFxThread(() -> {
            Fader f = Fader.create().showMeter(false).build();
            Pane root = new Pane(f);
            new Scene(root, 40, 400);
            root.applyCss();
            f.resizeRelocate(0, 0, 20, 120);
            f.layout();
            heights[0] = ((FaderSkin) f.getSkin()).columnHeight();
            f.resizeRelocate(0, 0, 20, 240);
            f.layout();
            heights[1] = ((FaderSkin) f.getSkin()).columnHeight();
            return null;
        });
        assertThat(heights[0]).isEqualTo(120.0);
        assertThat(heights[1]).isEqualTo(240.0);
    }
}
