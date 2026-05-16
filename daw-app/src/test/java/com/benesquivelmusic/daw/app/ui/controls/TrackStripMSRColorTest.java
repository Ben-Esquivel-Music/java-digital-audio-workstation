package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.DarkThemeHelper;
import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.controls.skin.TrackStripSkin;

import javafx.scene.Scene;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.Background;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * UI Design Book §7.5 explicit veto: the M/S/R column is three states of
 * one thing (track gating), not three semantics. The toggled fill colour
 * encodes the state semantically:
 *
 * <ul>
 *   <li>M (mute) → {@code -text}   (#B7BCC7)</li>
 *   <li>S (solo) → {@code -warn}   (#E6B450)</li>
 *   <li>R (arm)  → {@code -danger} (#E5484D)</li>
 * </ul>
 *
 * <p><strong>Not</strong> the legacy {@code -orange} / {@code -green} /
 * {@code -red} multi-coloured semantic palette.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class TrackStripMSRColorTest {

    @Test
    void selectedMuteToggleResolvesToTextColor() {
        Color fill = selectedFillOf(TrackStripSkin::muteButton, TrackStrip::setMuted);
        assertThat(fill)
                .as("M:selected fill resolves to -text (#B7BCC7), not legacy -orange")
                .isEqualTo(Color.web("#B7BCC7"));
    }

    @Test
    void selectedSoloToggleResolvesToWarnColor() {
        Color fill = selectedFillOf(TrackStripSkin::soloButton, TrackStrip::setSoloed);
        assertThat(fill)
                .as("S:selected fill resolves to -warn (#E6B450), not legacy -green")
                .isEqualTo(Color.web("#E6B450"));
    }

    @Test
    void selectedArmToggleResolvesToDangerColor() {
        Color fill = selectedFillOf(TrackStripSkin::armButton, TrackStrip::setArmed);
        assertThat(fill)
                .as("R:selected fill resolves to -danger (#E5484D), not legacy -red")
                .isEqualTo(Color.web("#E5484D"));
    }

    private static Color selectedFillOf(
            java.util.function.Function<TrackStripSkin, ToggleButton> selector,
            java.util.function.BiConsumer<TrackStrip, Boolean> activate) {
        return runOnFxThread(() -> {
            TrackStrip strip = new TrackStrip();
            StackPane root = new StackPane(strip);
            root.getStyleClass().add("root-pane");
            Scene scene = new Scene(root, 320, 60);
            DarkThemeHelper.applyTo(scene);
            root.applyCss();
            root.layout();
            TrackStripSkin skin = (TrackStripSkin) strip.getSkin();
            activate.accept(strip, true);
            root.applyCss();
            root.layout();
            ToggleButton btn = selector.apply(skin);
            Background bg = btn.getBackground();
            if (bg == null || bg.getFills().isEmpty()) {
                return null;
            }
            Paint p = bg.getFills().get(0).getFill();
            return (p instanceof Color c) ? c : null;
        });
    }
}
