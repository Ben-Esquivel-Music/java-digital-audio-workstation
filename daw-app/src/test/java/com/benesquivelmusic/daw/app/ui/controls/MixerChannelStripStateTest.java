package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.controls.skin.MixerChannelStripSkin;

import javafx.css.PseudoClass;
import javafx.scene.Scene;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * State-driven styling contract for {@link MixerChannelStrip} — mirrors
 * {@code TrackStripStateTest} at the channel-strip level.
 *
 * <p>Setting {@link MixerChannelStrip#mutedProperty()} /
 * {@code soloedProperty} / {@code armedProperty} must flip both the
 * corresponding M/S/R toggle's {@code :selected} pseudo-class and the
 * strip's own state pseudo-class.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class MixerChannelStripStateTest {

    private record Snapshot(boolean toggleSelected, boolean stripPseudoSet) { }

    private interface ToggleSelector {
        ToggleButton pick(MixerChannelStripSkin s);
    }

    private static Snapshot attachAndApply(
            java.util.function.Consumer<MixerChannelStrip> mutate,
            ToggleSelector which,
            PseudoClass stripPc) {
        return runOnFxThread(() -> {
            MixerChannelStrip strip = new MixerChannelStrip();
            strip.setChannelName("Drums");
            StackPane root = new StackPane(strip);
            root.getStyleClass().add("root-pane");
            Scene scene = new Scene(root, 200, 600);
            ThemeManager.getDefault().applyTo(scene);
            root.applyCss();
            root.layout();
            MixerChannelStripSkin skin = (MixerChannelStripSkin) strip.getSkin();
            ToggleButton btn = which.pick(skin);
            mutate.accept(strip);
            root.applyCss();
            root.layout();
            boolean toggleSelected = btn.isSelected()
                    && btn.getPseudoClassStates()
                            .contains(PseudoClass.getPseudoClass("selected"));
            boolean pcSet = strip.getPseudoClassStates().contains(stripPc);
            return new Snapshot(toggleSelected, pcSet);
        });
    }

    @Test
    void mutedSetsMuteTogglePseudoClassAndStripPseudoClass() {
        Snapshot s = attachAndApply(
                strip -> strip.setMuted(true),
                MixerChannelStripSkin::muteButton,
                PseudoClass.getPseudoClass("muted"));
        assertThat(s.toggleSelected())
                .as("setMuted(true) selects the M toggle").isTrue();
        assertThat(s.stripPseudoSet())
                .as("setMuted(true) flips the strip's :muted pseudo-class").isTrue();
    }

    @Test
    void soloedSetsSoloTogglePseudoClass() {
        Snapshot s = attachAndApply(
                strip -> strip.setSoloed(true),
                MixerChannelStripSkin::soloButton,
                PseudoClass.getPseudoClass("soloed"));
        assertThat(s.toggleSelected()).isTrue();
        assertThat(s.stripPseudoSet()).isTrue();
    }

    @Test
    void armedSetsArmTogglePseudoClass() {
        Snapshot s = attachAndApply(
                strip -> strip.setArmed(true),
                MixerChannelStripSkin::armButton,
                PseudoClass.getPseudoClass("armed"));
        assertThat(s.toggleSelected()).isTrue();
        assertThat(s.stripPseudoSet()).isTrue();
    }
}
