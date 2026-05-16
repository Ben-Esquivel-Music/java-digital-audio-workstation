package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.controls.skin.FaderSkin;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Travel-curve geometry for {@link Fader}: the LOG_DB curve must place
 * {@code 0 dB} at 75% travel, {@code -∞} at the bottom, and {@code +max}
 * at the top.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class FaderCurveTest {

    private static Fader laidOutFader(double min, double max, double colHeight) {
        return runOnFxThread(() -> {
            Fader f = Fader.create()
                    .min(min).max(max).defaultValue(0)
                    .curve(Fader.TravelCurve.LOG_DB)
                    .showMeter(false)
                    .build();
            StackPane root = new StackPane(f);
            new Scene(root, 40, colHeight + 4);
            root.applyCss();
            f.resizeRelocate(0, 0, 20, colHeight);
            f.layout();
            return f;
        });
    }

    @Test
    void logDbZeroSitsAtSeventyFivePercentTravel() {
        // Standard mixer range -96..+12; column height 200 px.
        // The cap-centre travel range is (height - capHeight=12) = 188 px.
        // At value=0, the cap centre should be at travelTop + 0.25 * travelRange
        // (1.0 - 0.75 = 0.25 from the top, since position 1.0 = top).
        Fader f = laidOutFader(-96, 12, 200);
        runOnFxThread(() -> { f.setValue(0.0); return null; });
        double pos = runOnFxThread(() -> f.positionForValue(0.0));
        assertThat(pos).isCloseTo(0.75, within(1e-6));

        FaderSkin skin = (FaderSkin) f.getSkin();
        double capY = runOnFxThread(() -> skin.capCentreY());
        // travelRangeHeight = 200 - 12 = 188; travelTop = 6
        // Expected cap centre at value=0 (pos=0.75): 6 + (1 - 0.75) * 188 = 6 + 47 = 53
        double expected = 6.0 + (1.0 - 0.75) * 188.0;
        assertThat(capY)
                .as("cap centre Y for 0 dB at 75%% travel")
                .isCloseTo(expected, within(1.0));
    }

    @Test
    void negativeInfinityMapsToBottom() {
        Fader f = laidOutFader(-96, 12, 200);
        runOnFxThread(() -> { f.setValue(Double.NEGATIVE_INFINITY); return null; });
        double pos = runOnFxThread(() -> f.positionForValue(f.getValue()));
        assertThat(pos).as("-∞ clamps to min → 0% travel").isEqualTo(0.0);

        FaderSkin skin = (FaderSkin) f.getSkin();
        double capY = runOnFxThread(() -> skin.capCentreY());
        // pos=0 → cap centre at bottom = travelTop + travelRange = 6 + 188 = 194
        assertThat(capY).isCloseTo(194.0, within(1.0));
    }

    @Test
    void maxMapsToTop() {
        Fader f = laidOutFader(-96, 12, 200);
        runOnFxThread(() -> { f.setValue(12.0); return null; });
        double pos = runOnFxThread(() -> f.positionForValue(12.0));
        assertThat(pos).isCloseTo(1.0, within(1e-6));

        FaderSkin skin = (FaderSkin) f.getSkin();
        double capY = runOnFxThread(() -> skin.capCentreY());
        // pos=1 → cap centre at top = travelTop = 6
        assertThat(capY).isCloseTo(6.0, within(1.0));
    }

    @Test
    void linearCurveIsLinear() {
        Fader f = laidOutFader(-1, 1, 200);
        runOnFxThread(() -> { f.setCurve(Fader.TravelCurve.LINEAR); return null; });
        assertThat(runOnFxThread(() -> f.positionForValue(0.0)))
                .isCloseTo(0.5, within(1e-9));
        assertThat(runOnFxThread(() -> f.positionForValue(-1.0)))
                .isCloseTo(0.0, within(1e-9));
        assertThat(runOnFxThread(() -> f.positionForValue(1.0)))
                .isCloseTo(1.0, within(1e-9));
    }

    @Test
    void inverseMappingRoundTrips() {
        Fader f = laidOutFader(-96, 12, 200);
        for (double v : new double[]{-96, -60, -24, -6, 0, 3, 6, 12}) {
            double pos = runOnFxThread(() -> f.positionForValue(v));
            double back = runOnFxThread(() -> f.valueForPosition(pos));
            assertThat(back)
                    .as("LOG_DB round-trip for v=%s", v)
                    .isCloseTo(v, within(1e-6));
        }
    }
}
