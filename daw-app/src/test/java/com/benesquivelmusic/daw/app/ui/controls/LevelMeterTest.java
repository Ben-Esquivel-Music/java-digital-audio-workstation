package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.controls.skin.LevelMeterSkin;

import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Core behaviour of {@link LevelMeter}: defaults, property accessors, the
 * audio→FX relay, the CssMetaData contract, and the draw-model seams
 * exercised under a live {@link Scene}.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class LevelMeterTest {

    @Test
    void defaultsAreSane() {
        LevelMeter m = runOnFxThread(LevelMeter::new);
        assertThat(m.getOrientation()).isEqualTo(Orientation.VERTICAL);
        assertThat(m.getChannelCount()).isEqualTo(2);
        assertThat(m.isAnimated()).isTrue();
        assertThat(m.getStyleClass()).contains("level-meter");
        assertThat(m.getMeterLow()).isEqualTo(Color.web("#3FBF7F"));
        assertThat(m.getMeterClip()).isEqualTo(Color.web("#E5484D"));
        assertThat(m.getMeterBackground()).isEqualTo(Color.web("#1D1F26"));
        assertThat(m.getUserAgentStylesheet()).endsWith("level-meter.css");
    }

    @Test
    void channelCountIsClampedToOneThroughEight() {
        LevelMeter m = runOnFxThread(LevelMeter::new);
        m.setChannelCount(0);
        assertThat(m.getChannelCount()).isEqualTo(1);
        m.setChannelCount(99);
        assertThat(m.getChannelCount()).isEqualTo(8);
        m.setChannelCount(4);
        assertThat(m.getChannelCount()).isEqualTo(4);
    }

    @Test
    void cssMetaDataExposesAllEightStyleableProperties() {
        var names = LevelMeter.getClassCssMetaData().stream()
                .map(javafx.css.CssMetaData::getProperty)
                .toList();
        assertThat(names).contains(
                "-meter-low", "-meter-mid", "-meter-hi", "-meter-clip",
                "-meter-background", "-meter-segment-gap",
                "-meter-segment-height", "-meter-tick-marks");
    }

    @Test
    void submitLevelsIsRelayedToPropertiesByTheSkinTimer() {
        LevelMeter m = runOnFxThread(LevelMeter::new);
        LevelMeterSkin skin = attach(m);

        m.submitLevels(-3.0, -9.0);
        // Pump a few timer pulses on the FX thread.
        runOnFxThread(() -> {
            for (int i = 0; i < 5; i++) {
                pulse(skin);
            }
            return null;
        });
        assertThat(m.getPeakDb()).isEqualTo(-3.0);
        assertThat(m.getRmsDb()).isEqualTo(-9.0);
    }

    @Test
    void drawModelLightsSegmentsAccordingToPeak() {
        LevelMeter m = runOnFxThread(LevelMeter::new);
        LevelMeterSkin skin = attach(m);

        runOnFxThread(() -> {
            m.setPeakDb(-60.0);
            return null;
        });
        layout(m);
        int floorLit = runOnFxThread(() -> skin.topLitSegmentIndex(0, 4, 200));

        runOnFxThread(() -> {
            m.setPeakDb(0.0);
            return null;
        });
        layout(m);
        int fullLit = runOnFxThread(() -> skin.topLitSegmentIndex(0, 4, 200));

        assertThat(fullLit).isGreaterThan(floorLit);
    }

    @Test
    void colorAtUsesClipColourNearFullScaleAndLowColourNearFloor() {
        LevelMeter m = runOnFxThread(LevelMeter::new);
        LevelMeterSkin skin = attach(m);
        runOnFxThread(() -> {
            m.setPeakDb(0.0);
            return null;
        });
        layout(m);

        Color near0 = runOnFxThread(() -> {
            int n = skin.segmentCount(4, 200);
            return skin.colorAt(0, n - 1, 4, 200);
        });
        Color nearFloor = runOnFxThread(() -> skin.colorAt(0, 0, 4, 200));

        assertThat(near0).isEqualTo(m.getMeterClip());
        assertThat(nearFloor).isEqualTo(m.getMeterLow());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    static LevelMeterSkin attach(LevelMeter m) {
        return runOnFxThread(() -> {
            StackPane root = new StackPane(m);
            Scene scene = new Scene(root, 80, 240);
            root.applyCss();
            root.layout();
            return (LevelMeterSkin) m.getSkin();
        });
    }

    static void layout(LevelMeter m) {
        runOnFxThread(() -> {
            m.getScene().getRoot().applyCss();
            m.getScene().getRoot().layout();
            return null;
        });
    }

    static void pulse(LevelMeterSkin skin) {
        // Equivalent to one AnimationTimer.handle() without depending on
        // pulse scheduling: relay + tick + paint via the seam path.
        skin.pumpOnce(System.nanoTime());
    }
}
