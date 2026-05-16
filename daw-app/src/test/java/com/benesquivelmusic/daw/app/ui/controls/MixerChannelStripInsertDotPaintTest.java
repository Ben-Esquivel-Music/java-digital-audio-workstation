package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.DarkThemeHelper;
import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.controls.skin.MixerChannelStripSkin;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 271 / phase-2 template trap #1 — "tests assert the draw-model seam
 * while users see {@code paint()}".
 *
 * <p>The story's {@code insertDotActive(int)} skin seam returns a boolean.
 * This test instead reads the <strong>rendered {@link Circle}'s resolved
 * fill</strong> — the actual pixel the user sees — under a theme re-tinted
 * to values distinct from the UA fallback, and asserts:
 *
 * <ul>
 *   <li>an engaged, non-bypassed insert renders the (re-tinted)
 *       {@code -mcs-accent} fill;</li>
 *   <li>a bypassed insert renders the (re-tinted) {@code -mcs-text-mute}
 *       fill;</li>
 *   <li>the two differ.</li>
 * </ul>
 *
 * <p>This proves the single {@code insertDotActive} helper actually drives
 * the painted result (via the {@code .active} style class consumed by
 * {@code mixer-channel-strip.css}) and that the seam and the paint path
 * cannot disagree — the exact correctness trap the story-267 expert review
 * flagged as propagating through the 268–271 template.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class MixerChannelStripInsertDotPaintTest {

    private static Circle dotIn(Node row) {
        if (row instanceof Circle c) {
            return c;
        }
        if (row instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                Circle c = dotIn(child);
                if (c != null) {
                    return c;
                }
            }
        }
        return null;
    }

    @Test
    void renderedStatusDotFillFollowsTheSingleActiveHelperUnderAReTintedTheme() {
        Color[] fills = runOnFxThread(() -> {
            MixerChannelStrip strip = new MixerChannelStrip();
            // index 0: active + not bypassed  → dot must be -mcs-accent
            strip.insertsProperty().add(new InsertSlotModel("EQ", true, false));
            // index 1: bypassed               → dot must be -mcs-text-mute
            strip.insertsProperty().add(new InsertSlotModel("Comp", true, true));

            StackPane root = new StackPane(strip);
            root.getStyleClass().add("root-pane");
            Scene scene = new Scene(root, 88, 600);
            DarkThemeHelper.applyTo(scene);
            // Re-tint to values distinct from the UA fallback so a broken
            // forward / wrong style class cannot hide behind equal hex.
            root.setStyle("-accent: #0011FF; -text-mute: #FFAA00;");
            root.applyCss();
            root.layout();

            MixerChannelStripSkin skin = (MixerChannelStripSkin) strip.getSkin();
            Circle activeDot = dotIn(skin.insertRowNode(0));
            Circle bypassedDot = dotIn(skin.insertRowNode(1));
            assertThat(activeDot).as("active row must render a status dot").isNotNull();
            assertThat(bypassedDot).as("bypassed row must render a status dot").isNotNull();
            // Cross-check the seam agrees with what we are about to read off
            // the painted node (single source of truth).
            assertThat(skin.insertDotActive(0)).isTrue();
            assertThat(skin.insertDotActive(1)).isFalse();
            return new Color[] {(Color) activeDot.getFill(), (Color) bypassedDot.getFill()};
        });

        assertThat(fills[0])
                .as("engaged, non-bypassed insert dot renders the re-tinted "
                        + "-mcs-accent (proving .active drives the painted fill)")
                .isEqualTo(Color.web("#0011FF"));
        assertThat(fills[1])
                .as("bypassed insert dot renders the re-tinted -mcs-text-mute")
                .isEqualTo(Color.web("#FFAA00"));
        assertThat(fills[0])
                .as("active and bypassed dots must be visually distinct")
                .isNotEqualTo(fills[1]);
    }
}
