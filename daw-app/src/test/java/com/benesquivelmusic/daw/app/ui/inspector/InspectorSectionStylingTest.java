package com.benesquivelmusic.daw.app.ui.inspector;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;

import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.text.Font;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * §3.2 / §7.6 — inspector section headers use "Label small" (10 px, 600,
 * muted text) — explicitly <em>not</em> purple.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class InspectorSectionStylingTest {

    @Test
    void sectionHeadersAreMutedTenPxAndNotPurple() {
        Paint[] fill = new Paint[1];
        double[] fontSize = new double[1];
        runOnFxThread(() -> {
            InspectorDrawer drawer = new InspectorDrawer();
            drawer.setAnimated(false);
            Pane root = new Pane(drawer);
            new Scene(root, 400, 400);
            root.applyCss();
            root.layout();

            var titleLabel = drawer.getTrackSection().getTitleLabel();
            fill[0] = titleLabel.getTextFill();
            Font f = titleLabel.getFont();
            fontSize[0] = f.getSize();
            return null;
        });

        assertThat(fontSize[0]).isEqualTo(10.0);

        // §7.6 — section headers must never resolve to the saturated
        // accent (purple). The Palette-A accent is #7C8CFF — assert the
        // resolved fill is not that hue and instead is the muted text
        // colour family (#7A808C in Palette A).
        assertThat(fill[0]).isInstanceOf(Color.class);
        Color c = (Color) fill[0];
        // muted text (#7A808C) — RGB roughly equal channels, ~ 0.48.
        // The accent (#7C8CFF) has a strongly elevated blue channel.
        // The simplest non-purple invariant: blue channel is NOT
        // dominant by a wide margin.
        double bDominance = c.getBlue() - Math.max(c.getRed(), c.getGreen());
        assertThat(bDominance)
                .as("section header must not resolve to the saturated -accent (purple) per §7.6")
                .isLessThan(0.10);
    }
}
