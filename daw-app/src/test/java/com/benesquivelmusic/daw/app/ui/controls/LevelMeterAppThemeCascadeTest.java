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
 * <p>Three distinct, JavaFX-correct cascade paths are asserted:
 *
 * <ol>
 *   <li><b>{@code -meter-background} follows the {@code -surface-2}
 *       token.</b> {@code styles.css} declares
 *       {@code .level-meter { -meter-background: -surface-2 }} (distinct
 *       names, non-circular). A theme that re-tints {@code -surface-2} on
 *       {@code .root-pane} re-tints the unlit segments.</li>
 *   <li><b>Lit-segment colours follow the {@code .root-pane} Palette A
 *       role tokens.</b> The control's CssMetaData uses internal
 *       {@code -lm-*} names; {@code level-meter.css} forwards
 *       {@code -lm-low: -meter-low; ...} (distinct names, non-circular).
 *       A theme that re-tints {@code -meter-low} on {@code .root-pane}
 *       reaches the meter through that forward — no per-control rule
 *       required, restoring the styles.css "structural selectors consume
 *       role tokens" convention.</li>
 *   <li><b>Lit segments can also be re-tinted on {@code .level-meter}.</b>
 *       Plugin GUIs and themes can override the documented {@code -meter-*}
 *       CSS API directly on {@code .level-meter}; the same forward picks
 *       it up.</li>
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
        assertThat(c[0]).isEqualTo(Color.web("#3FBF7F")); // -meter-low role token
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
    void litSegmentsFollowRootPaneRoleTokens() {
        // The lit-segment colours cascade from .root-pane's Palette A role
        // tokens — no per-control .level-meter rule needed. This restores
        // the styles.css convention that structural selectors consume role
        // tokens. Mechanism: control's CssMetaData uses internal -lm-*
        // names; level-meter.css forwards `-lm-low: -meter-low` (distinct
        // names, non-circular), so the .root-pane override reaches the
        // meter via the standard looked-up colour cascade.
        LevelMeter m = newMeter();
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
                .as("re-tinting -meter-low on .root-pane must reach the "
                        + "control via the level-meter.css forward")
                .isEqualTo(Color.web("#0011FF"));
        assertThat(c[1]).isEqualTo(Color.web("#FFAA00"));
    }

    @Test
    void litSegmentsCanAlsoBeReTintedOnTheLevelMeterSelector() {
        // Plugins/themes that prefer to scope the override to the control
        // can still set the documented -meter-* names on .level-meter; the
        // level-meter.css forward picks them up the same way.
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
