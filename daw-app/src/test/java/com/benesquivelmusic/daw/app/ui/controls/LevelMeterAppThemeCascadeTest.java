package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.DarkThemeHelper;
import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;

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
 *   <li><b>{@code -meter-background} follows the {@code -surface-2}
 *       token.</b> {@code styles.css} forwards {@code -surface-2} into
 *       the control's {@code -meter-background} styleable property.
 *       Because the source and target names differ, JavaFX resolves it
 *       through the {@code .root-pane} ancestor — so a theme that
 *       re-tints {@code -surface-2} re-tints the unlit segments.</li>
 *   <li><b>Lit segments are re-tinted via the {@code .level-meter}
 *       selector directly.</b> The documented {@code -meter-*} CSS
 *       styleable property names match {@code .root-pane}'s role-token
 *       names, so a same-name forward in {@code styles.css}
 *       ({@code .level-meter { -meter-low: -meter-low; }}) is a circular
 *       looked-up colour that JavaFX drops. Themes must therefore declare
 *       their lit-segment palette on {@code .level-meter} (the same
 *       structural entry point used by stories 268–271).</li>
 * </ol>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class LevelMeterAppThemeCascadeTest {

    private final List<LevelMeter> created = new ArrayList<>();

    @AfterEach
    void cleanup() {
        runOnFxThread(() -> {
            for (LevelMeter m : created) {
                if (m.getSkin() != null) {
                    m.setSkin(null);
                }
            }
            created.clear();
            return null;
        });
    }

    private LevelMeter newMeter() {
        LevelMeter m = runOnFxThread(LevelMeter::new);
        created.add(m);
        return m;
    }

    @Test
    void defaultAppThemeResolvesToPaletteAValues() {
        LevelMeter m = newMeter();
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
        LevelMeter m = newMeter();
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
                .as("a theme re-tinting -surface-2 must reach -meter-background "
                        + "via the distinctly-named styles.css forward")
                .isEqualTo(Color.web("#654321"));
    }

    @Test
    void litSegmentsAreReTintedViaTheLevelMeterSelector() {
        // Themes re-tint the lit-segment palette on the .level-meter
        // selector — this is the supported entry point for the controls
        // package. A .root-pane `-meter-low` token override does NOT
        // cascade (a same-name forward would be a circular looked-up
        // colour); the doc-comment in styles.css explains why.
        LevelMeter m = newMeter();
        Color[] c = runOnFxThread(() -> {
            StackPane root = new StackPane(m);
            root.getStyleClass().add("root-pane");
            Scene scene = new Scene(root, 80, 240);
            DarkThemeHelper.applyTo(scene);
            scene.getStylesheets().add("data:text/css;base64,"
                    + java.util.Base64.getEncoder().encodeToString(
                            (".level-meter { -meter-low: #0011FF; "
                                    + "-meter-clip: #FFAA00; }")
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            root.applyCss();
            root.layout();
            return new Color[] {m.getMeterLow(), m.getMeterClip()};
        });
        assertThat(c[0]).isEqualTo(Color.web("#0011FF"));
        assertThat(c[1]).isEqualTo(Color.web("#FFAA00"));
    }
}
