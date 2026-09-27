package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.controls.skin.MixerChannelStripSkin;

import javafx.css.PseudoClass;
import javafx.event.ActionEvent;
import javafx.scene.Scene;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
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

    // ── Inline name editor (story 322 fix round 1, N13) ──────────────────

    private record NameEditOutcome(String channelName, String labelText,
                                   boolean editorVisible, boolean labelVisible) { }

    /** Opens the editor by double-click, types {@code typed}, then fires {@code finish} on the editor. */
    private static NameEditOutcome editName(String typed, javafx.event.Event finish) {
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
            skin.nameLabel().fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                    MouseButton.PRIMARY, 2, false, false, false, false,
                    true, false, false, false, false, false, null));
            assertThat(skin.nameEditor().isVisible()).as("double-click opens the editor").isTrue();
            skin.nameEditor().setText(typed);
            skin.nameEditor().fireEvent(finish);
            return new NameEditOutcome(strip.getChannelName(), skin.nameLabel().getText(),
                    skin.nameEditor().isVisible(), skin.nameLabel().isVisible());
        });
    }

    @Test
    void escapeCancelsTheInlineNameEditWithoutTouchingChannelName() {
        NameEditOutcome o = editName("Dru", new KeyEvent(KeyEvent.KEY_PRESSED, "", "",
                KeyCode.ESCAPE, false, false, false, false));
        assertThat(o.channelName()).as("Escape never calls setChannelName").isEqualTo("Drums");
        assertThat(o.labelText()).as("the label keeps the control's name").isEqualTo("Drums");
        assertThat(o.editorVisible()).as("editor hidden").isFalse();
        assertThat(o.labelVisible()).as("label back").isTrue();
    }

    @Test
    void enterCommitsTheInlineNameEditIntoChannelName() {
        NameEditOutcome o = editName("Drum Bus", new ActionEvent());
        assertThat(o.channelName()).as("Enter commits into channelName").isEqualTo("Drum Bus");
        assertThat(o.labelText()).isEqualTo("Drum Bus");
        assertThat(o.editorVisible()).isFalse();
        assertThat(o.labelVisible()).isTrue();
    }
}
