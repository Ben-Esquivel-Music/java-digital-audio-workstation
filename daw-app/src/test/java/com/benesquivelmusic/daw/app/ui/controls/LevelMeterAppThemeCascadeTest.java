package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.DarkThemeHelper;
import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the story-267 theming contract end-to-end through the REAL
 * application {@code styles.css} cascade — the path the unit-level
 * {@code LevelMeterSkinThemeTest} does not exercise (it never attaches
 * {@code styles.css}, and the control's user-agent fallback hex is
 * identical to the Palette&nbsp;A token value, so a regression there would
 * be invisible).
 *
 * <p>Two distinct, JavaFX-correct mechanisms are asserted:
 *
 * <ol>
 *   <li><b>{@code -lm-background} follows the {@code -surface-2}
 *       token.</b> {@code styles.css} forwards {@code -surface-2} into the
 *       control's {@code -lm-background} styleable property. Because the
 *       source and target names differ, JavaFX resolves it through the
 *       {@code .root-pane} ancestor — so a theme that re-tints
 *       {@code -surface-2} re-tints the unlit segments.</li>
 *   <li><b>Lit segments follow the {@code .root-pane} role tokens.</b>
 *       {@code styles.css} forwards {@code -meter-low} into
 *       {@code -lm-low}, {@code -meter-mid} into {@code -lm-mid}, etc.
 *       Because the source ({@code -meter-low}) and target
 *       ({@code -lm-low}) names differ, the forward is not a circular
 *       looked-up colour — a theme that re-tints the role token
 *       automatically re-tints the meter.</li>
 * </ol>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class LevelMeterAppThemeCascadeTest {

    private static String inlineCss(String css) {
        return "data:text/css;base64,"
                + Base64.getEncoder().encodeToString(css.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void defaultAppThemeResolvesToPaletteAValues() {
        LevelMeter m = runOnFxThread(LevelMeter::new);
        Color[] c = runOnFxThread(() -> {
            StackPane root = new StackPane(m);
            root.getStyleClass().add("root-pane");
            Scene scene = new Scene(root, 80, 240);
            DarkThemeHelper.applyTo(scene);
            root.applyCss();
            root.layout();
            return new Color[] {m.getMeterLow(), m.getMeterClip(), m.getMeterBackground()};
        });
        assertThat(c[0]).isEqualTo(Color.web("#3FBF7F")); // UA css (== Palette A)
        assertThat(c[1]).isEqualTo(Color.web("#E5484D"));
        assertThat(c[2]).isEqualTo(Color.web("#1D1F26")); // forwarded from -surface-2
    }

    @Test
    void meterBackgroundFollowsTheSurfaceTwoToken() {
        LevelMeter m = runOnFxThread(LevelMeter::new);
        Color bg = runOnFxThread(() -> {
            StackPane root = new StackPane(m);
            root.getStyleClass().add("root-pane");
            Scene scene = new Scene(root, 80, 240);
            DarkThemeHelper.applyTo(scene);
            // Simulate a story-277 theme re-tinting the -surface-2 token.
            root.setStyle("-surface-2: #654321;");
            root.applyCss();
            root.layout();
            return m.getMeterBackground();
        });
        assertThat(bg)
                .as("a theme re-tinting -surface-2 must reach -lm-background "
                        + "via the distinctly-named styles.css forward")
                .isEqualTo(Color.web("#654321"));
    }

    @Test
    void litSegmentsFollowRootPaneRoleTokens() {
        LevelMeter m = runOnFxThread(LevelMeter::new);
        Color[] c = runOnFxThread(() -> {
            StackPane root = new StackPane(m);
            root.getStyleClass().add("root-pane");
            Scene scene = new Scene(root, 80, 240);
            DarkThemeHelper.applyTo(scene);
            // Simulate a theme re-tinting the role tokens on .root-pane.
            root.setStyle("-meter-low: #0011FF; -meter-clip: #FFAA00;");
            root.applyCss();
            root.layout();
            return new Color[] {m.getMeterLow(), m.getMeterClip()};
        });
        assertThat(c[0])
                .as("re-tinting -meter-low on .root-pane must cascade through "
                        + "the -lm-low forward into the control")
                .isEqualTo(Color.web("#0011FF"));
        assertThat(c[1]).isEqualTo(Color.web("#FFAA00"));
    }
}
