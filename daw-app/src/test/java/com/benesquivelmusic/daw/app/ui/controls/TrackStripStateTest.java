package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.controls.skin.TrackStripSkin;

import javafx.css.PseudoClass;
import javafx.scene.Scene;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * State-driven styling contract for {@link TrackStrip}.
 *
 * <p>Setting {@link TrackStrip#mutedProperty()} / {@code soloedProperty} /
 * {@code armedProperty} must flip both the corresponding M/S/R toggle's
 * {@code :selected} pseudo-class and the strip's own state pseudo-class.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class TrackStripStateTest {

    private record Snapshot(boolean toggleSelected, boolean stripPseudoSet, Color nameFill) { }

    private static Snapshot attachAndApply(java.util.function.BiConsumer<TrackStrip, ToggleButton> mutate,
                                           ToggleSelector which,
                                           PseudoClass stripPc) {
        return runOnFxThread(() -> {
            TrackStrip strip = new TrackStrip();
            strip.setTrackName("Drums");
            StackPane root = new StackPane(strip);
            root.getStyleClass().add("root-pane");
            Scene scene = new Scene(root, 320, 60);
            // Apply the app stylesheet so -ts-* token forwards resolve.
            com.benesquivelmusic.daw.app.ui.DarkThemeHelper.applyTo(scene);
            root.applyCss();
            root.layout();
            TrackStripSkin skin = (TrackStripSkin) strip.getSkin();
            ToggleButton btn = which.pick(skin);
            mutate.accept(strip, btn);
            // re-apply CSS so the new pseudo-class state cascades.
            root.applyCss();
            root.layout();
            boolean toggleSelected = btn.isSelected()
                    && btn.getPseudoClassStates().contains(PseudoClass.getPseudoClass("selected"));
            boolean pcSet = strip.getPseudoClassStates().contains(stripPc);
            Color nameFill = (Color) skin.nameLabel().getTextFill();
            return new Snapshot(toggleSelected, pcSet, nameFill);
        });
    }

    private interface ToggleSelector {
        ToggleButton pick(TrackStripSkin s);
    }

    @Test
    void mutedSetsMuteTogglePseudoClassAndFadesName() {
        Snapshot s = attachAndApply(
                (strip, btn) -> strip.setMuted(true),
                TrackStripSkin::muteButton,
                PseudoClass.getPseudoClass("muted"));
        assertThat(s.toggleSelected())
                .as("setMuted(true) selects the M toggle")
                .isTrue();
        assertThat(s.stripPseudoSet())
                .as("setMuted(true) flips the strip's :muted pseudo-class")
                .isTrue();
        // The CSS rule .track-strip:muted .track-strip-name { -fx-text-fill: -ts-text-mute }
        // resolves to the Palette A muted text colour.
        assertThat(s.nameFill())
                .as("muted: name text-fill resolves to -text-mute (#7A808C)")
                .isEqualTo(Color.web("#7A808C"));
    }

    @Test
    void soloedSetsSoloTogglePseudoClass() {
        Snapshot s = attachAndApply(
                (strip, btn) -> strip.setSoloed(true),
                TrackStripSkin::soloButton,
                PseudoClass.getPseudoClass("soloed"));
        assertThat(s.toggleSelected()).isTrue();
        assertThat(s.stripPseudoSet()).isTrue();
    }

    @Test
    void armedSetsArmTogglePseudoClass() {
        Snapshot s = attachAndApply(
                (strip, btn) -> strip.setArmed(true),
                TrackStripSkin::armButton,
                PseudoClass.getPseudoClass("armed"));
        assertThat(s.toggleSelected()).isTrue();
        assertThat(s.stripPseudoSet()).isTrue();
    }
}
