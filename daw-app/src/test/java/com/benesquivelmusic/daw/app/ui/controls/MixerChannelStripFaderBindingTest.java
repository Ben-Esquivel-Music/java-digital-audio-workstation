package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.controls.skin.MixerChannelStripSkin;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 271 — {@link MixerChannelStrip#faderDbProperty()} is two-way
 * synced to the embedded {@link Fader}'s {@code valueProperty()}, and
 * {@link MixerChannelStrip#panProperty()} to the embedded {@link Knob}.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class MixerChannelStripFaderBindingTest {

    private record Skin(MixerChannelStrip strip, MixerChannelStripSkin skin) { }

    private static Skin attach() {
        return runOnFxThread(() -> {
            MixerChannelStrip strip = new MixerChannelStrip();
            StackPane root = new StackPane(strip);
            new Scene(root, 200, 600);
            root.applyCss();
            root.layout();
            return new Skin(strip, (MixerChannelStripSkin) strip.getSkin());
        });
    }

    @Test
    void setFaderDbPropagatesToEmbeddedFader() {
        Skin s = attach();
        runOnFxThread(() -> {
            s.strip().setFaderDb(-6.0);
            return null;
        });
        assertThat(s.skin().fader().getValue())
                .as("setFaderDb(-6) sets the embedded Fader value")
                .isEqualTo(-6.0);
    }

    @Test
    void settingEmbeddedFaderValuePropagatesBackToFaderDbProperty() {
        Skin s = attach();
        runOnFxThread(() -> {
            s.skin().fader().setValue(-12.0);
            return null;
        });
        assertThat(s.strip().getFaderDb())
                .as("moving the Fader updates faderDbProperty (two-way)")
                .isEqualTo(-12.0);
    }

    @Test
    void setPanPropagatesToEmbeddedKnobAndBack() {
        Skin s = attach();
        runOnFxThread(() -> {
            s.strip().setPan(-0.5);
            return null;
        });
        assertThat(s.skin().knob().getValue())
                .as("setPan(-0.5) sets the embedded Knob value")
                .isEqualTo(-0.5);

        runOnFxThread(() -> {
            s.skin().knob().setValue(0.75);
            return null;
        });
        assertThat(s.strip().getPan())
                .as("turning the Knob updates panProperty (two-way)")
                .isEqualTo(0.75);
    }
}
