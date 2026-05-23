package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;

import javafx.css.PseudoClass;
import javafx.scene.Scene;
import javafx.scene.layout.Background;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * UI Design Book §7.1: hover swaps the row background to {@code -surface-3}
 * — no shadow, no border swap.
 *
 * <p>The {@code :hover} pseudo-class is driven directly via
 * {@link javafx.css.Styleable#pseudoClassStateChanged} because synthetic
 * {@code MOUSE_ENTERED} events do not reliably flip {@code :hover} in
 * JavaFX's headless harness ({@code :hover} tracks real cursor location).
 */
@ExtendWith(JavaFxToolkitExtension.class)
class TrackStripHoverTest {

    @Test
    void hoverSwapsBackgroundToSurface3AndRevertsOnUnhover() {
        Object[] data = runOnFxThread(() -> {
            TrackStrip strip = new TrackStrip();
            StackPane root = new StackPane(strip);
            root.getStyleClass().add("root-pane");
            Scene scene = new Scene(root, 320, 60);
            ThemeManager.getDefault().applyTo(scene);
            root.applyCss();
            root.layout();

            // baseline (no hover) — expect -surface-1.
            Color baseline = backgroundOf(strip);
            Object baselineEffect = strip.getEffect();

            // simulate hover
            strip.pseudoClassStateChanged(PseudoClass.getPseudoClass("hover"), true);
            strip.applyCss();
            Color hovered = backgroundOf(strip);
            Object hoverEffect = strip.getEffect();

            // back to baseline
            strip.pseudoClassStateChanged(PseudoClass.getPseudoClass("hover"), false);
            strip.applyCss();
            Color restored = backgroundOf(strip);
            Object restoredEffect = strip.getEffect();

            return new Object[] {
                    baseline, hovered, restored,
                    baselineEffect, hoverEffect, restoredEffect
            };
        });

        Color baseline = (Color) data[0];
        Color hovered = (Color) data[1];
        Color restored = (Color) data[2];

        // -surface-1 = #15161B, -surface-3 = #272A33 per Palette A.
        assertThat(baseline)
                .as("default row background resolves to -surface-1")
                .isEqualTo(Color.web("#15161B"));
        assertThat(hovered)
                .as(":hover swaps background to -surface-3 (§7.1)")
                .isEqualTo(Color.web("#272A33"));
        assertThat(restored)
                .as("background returns to -surface-1 when hover lifts")
                .isEqualTo(Color.web("#15161B"));

        // §7.1 veto: no Effect on hover (nor at rest).
        assertThat(data[3]).as("no Effect at rest").isNull();
        assertThat(data[4]).as("no Effect on hover (§7.1 explicit veto)").isNull();
        assertThat(data[5]).as("no Effect after hover lifts").isNull();
    }

    private static Color backgroundOf(TrackStrip strip) {
        Background bg = strip.getBackground();
        if (bg == null || bg.getFills().isEmpty()) return null;
        Paint p = bg.getFills().get(0).getFill();
        return (p instanceof Color c) ? c : null;
    }
}
