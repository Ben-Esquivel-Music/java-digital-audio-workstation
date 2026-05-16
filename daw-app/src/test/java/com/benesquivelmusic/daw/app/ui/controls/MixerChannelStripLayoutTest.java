package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.DarkThemeHelper;
import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 271 — the strip width is density-driven and enforced from the
 * Skin (Java), not CSS: 72&nbsp;px Compact (also the default), 88&nbsp;px
 * Comfortable (UI Design Book §5.4, story 278).
 */
@ExtendWith(JavaFxToolkitExtension.class)
class MixerChannelStripLayoutTest {

    private static double widthOf(String densityClass) {
        return runOnFxThread(() -> {
            MixerChannelStrip strip = new MixerChannelStrip();
            strip.setChannelName("Drums");
            if (densityClass != null) {
                strip.getStyleClass().add(densityClass);
            }
            StackPane root = new StackPane(strip);
            root.getStyleClass().add("root-pane");
            Scene scene = new Scene(root, 200, 600);
            DarkThemeHelper.applyTo(scene);
            root.applyCss();
            root.layout();
            return strip.prefWidth(-1);
        });
    }

    @Test
    void compactDensityIs72Px() {
        assertThat(widthOf("density-compact")).isCloseTo(72.0, within(1.0));
    }

    @Test
    void comfortableDensityIs88Px() {
        assertThat(widthOf("density-comfortable")).isCloseTo(88.0, within(1.0));
    }

    @Test
    void defaultDensityKeepsTheCompactWidth() {
        // Story §5.4: "keep the width" — the default (no density class) is
        // the compact 72 px width.
        assertThat(widthOf(null)).isCloseTo(72.0, within(1.0));
    }
}
