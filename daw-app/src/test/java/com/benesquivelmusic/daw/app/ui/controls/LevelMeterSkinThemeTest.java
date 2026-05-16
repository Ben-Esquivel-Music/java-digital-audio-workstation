package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.controls.skin.LevelMeterSkin;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * CSS cascade: the control's own user-agent stylesheet supplies Palette A
 * fallbacks, and a scene/inline stylesheet can re-tint the meter colour
 * properties — proving the styleable contract resolves end-to-end.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class LevelMeterSkinThemeTest {

    @Test
    void userAgentStylesheetSuppliesPaletteAFallbackColours() {
        LevelMeter m = runOnFxThread(LevelMeter::new);
        runOnFxThread(() -> {
            StackPane root = new StackPane(m);
            new Scene(root, 80, 240);
            root.applyCss();
            root.layout();
            return null;
        });
        // No app stylesheet attached — values come from level-meter.css.
        assertThat(m.getMeterLow()).isEqualTo(Color.web("#3FBF7F"));
        assertThat(m.getMeterClip()).isEqualTo(Color.web("#E5484D"));
        assertThat(m.getMeterBackground()).isEqualTo(Color.web("#1D1F26"));
    }

    @Test
    void sceneStylesheetOverridesMeterLowColour() {
        LevelMeter m = runOnFxThread(LevelMeter::new);
        Color resolved = runOnFxThread(() -> {
            StackPane root = new StackPane(m);
            Scene scene = new Scene(root, 80, 240);
            scene.getStylesheets().add("data:text/css;base64,"
                    + java.util.Base64.getEncoder().encodeToString(
                            ".level-meter { -lm-low: red; }"
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            root.applyCss();
            root.layout();
            return m.getMeterLow();
        });
        assertThat(resolved).isEqualTo(Color.RED);
    }

    @Test
    void overriddenColourReachesTheSkinDrawModel() {
        LevelMeter m = runOnFxThread(LevelMeter::new);
        LevelMeterSkin skin = runOnFxThread(() -> {
            StackPane root = new StackPane(m);
            Scene scene = new Scene(root, 80, 240);
            scene.getStylesheets().add("data:text/css;base64,"
                    + java.util.Base64.getEncoder().encodeToString(
                            ".level-meter { -lm-clip: lime; }"
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            root.applyCss();
            root.layout();
            LevelMeterSkin s = (LevelMeterSkin) m.getSkin();
            m.setPeakDb(0.0);
            return s;
        });
        Color topColour = runOnFxThread(() -> {
            int n = skin.segmentCount(4, 200);
            return skin.colorAt(0, n - 1, 4, 200);
        });
        assertThat(topColour).isEqualTo(Color.LIME);
    }
}
