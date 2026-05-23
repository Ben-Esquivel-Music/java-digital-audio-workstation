package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.controls.MixerChannelStrip.ChannelType;
import com.benesquivelmusic.daw.app.ui.controls.skin.MixerChannelStripSkin;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 271 — with {@code channelType = MASTER} the M/S/R toggles are
 * <strong>not present in the rendered scene graph</strong> (asserted as
 * not findable, not merely {@code !visible}); only the fader, meter and
 * name remain. Switching back to {@code AUDIO} re-adds them.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class MixerChannelStripChannelTypeTest {

    private static void collectToggles(Node node, List<Node> acc) {
        if (node instanceof ToggleButton) {
            acc.add(node);
        }
        if (node instanceof javafx.scene.Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                collectToggles(child, acc);
            }
        }
    }

    private static boolean msrTogglesPresent(MixerChannelStrip strip) {
        MixerChannelStripSkin skin = (MixerChannelStripSkin) strip.getSkin();
        ToggleButton m = skin.muteButton();
        ToggleButton s = skin.soloButton();
        ToggleButton r = skin.armButton();
        List<Node> toggles = new ArrayList<>();
        collectToggles(strip, toggles);
        return toggles.contains(m) || toggles.contains(s) || toggles.contains(r);
    }

    @Test
    void masterChannelHasNoMsrTogglesInSceneGraph() {
        boolean present = runOnFxThread(() -> {
            MixerChannelStrip strip = MixerChannelStrip.create()
                    .name("Master")
                    .channelType(ChannelType.MASTER)
                    .build();
            StackPane root = new StackPane(strip);
            root.getStyleClass().add("root-pane");
            Scene scene = new Scene(root, 200, 600);
            ThemeManager.getDefault().applyTo(scene);
            root.applyCss();
            root.layout();
            return msrTogglesPresent(strip);
        });
        assertThat(present)
                .as("MASTER channel must not have M/S/R toggles in the "
                        + "rendered scene graph")
                .isFalse();
    }

    @Test
    void audioChannelHasMsrTogglesInSceneGraph() {
        boolean present = runOnFxThread(() -> {
            MixerChannelStrip strip = MixerChannelStrip.create()
                    .name("Drums")
                    .channelType(ChannelType.AUDIO)
                    .build();
            StackPane root = new StackPane(strip);
            root.getStyleClass().add("root-pane");
            Scene scene = new Scene(root, 200, 600);
            ThemeManager.getDefault().applyTo(scene);
            root.applyCss();
            root.layout();
            return msrTogglesPresent(strip);
        });
        assertThat(present)
                .as("AUDIO channel must show M/S/R toggles").isTrue();
    }

    @Test
    void switchingMasterToAudioReAddsMsrToggles() {
        boolean present = runOnFxThread(() -> {
            MixerChannelStrip strip = MixerChannelStrip.create()
                    .name("Bus")
                    .channelType(ChannelType.MASTER)
                    .build();
            StackPane root = new StackPane(strip);
            root.getStyleClass().add("root-pane");
            Scene scene = new Scene(root, 200, 600);
            ThemeManager.getDefault().applyTo(scene);
            root.applyCss();
            root.layout();
            strip.setChannelType(ChannelType.AUDIO);
            root.applyCss();
            root.layout();
            return msrTogglesPresent(strip);
        });
        assertThat(present)
                .as("switching MASTER → AUDIO re-adds M/S/R to the scene graph")
                .isTrue();
    }
}
