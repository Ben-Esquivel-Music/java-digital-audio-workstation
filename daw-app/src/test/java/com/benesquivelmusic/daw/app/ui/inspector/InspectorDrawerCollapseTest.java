package com.benesquivelmusic.daw.app.ui.inspector;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;

import javafx.scene.Scene;
import javafx.scene.layout.Pane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * §5.6 collapse/expand width contract for {@link InspectorDrawer}.
 *
 * <ul>
 *   <li>Expanded → width is 240 ± 1 px.</li>
 *   <li>Collapsed → width is 24 ± 1 px (after the transition; with
 *       {@code animatedProperty=false} the width changes within one
 *       pulse).</li>
 * </ul>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class InspectorDrawerCollapseTest {

    @Test
    void expandedDrawerIsTwoFortyPxWide() {
        double width = runOnFxThread(() -> {
            InspectorDrawer drawer = new InspectorDrawer();
            drawer.setAnimated(false); // snap, don't animate
            drawer.setExpanded(true);
            Pane root = new Pane(drawer);
            new Scene(root, 600, 400);
            root.applyCss();
            root.layout();
            return drawer.getWidth();
        });
        assertThat(width).isCloseTo(240.0, within(1.0));
    }

    @Test
    void collapsedDrawerIsTwentyFourPxWide() {
        double width = runOnFxThread(() -> {
            InspectorDrawer drawer = new InspectorDrawer();
            drawer.setAnimated(false);
            drawer.setExpanded(true);
            Pane root = new Pane(drawer);
            new Scene(root, 600, 400);
            root.applyCss();
            root.layout();
            drawer.setExpanded(false);
            root.applyCss();
            root.layout();
            return drawer.getWidth();
        });
        assertThat(width).isCloseTo(24.0, within(1.0));
    }

    @Test
    void widthChangesWithinOnePulseWhenAnimationIsOff() {
        // The whole point of animatedProperty=false: the width is set
        // synchronously in the same pulse, no Timeline interpolation.
        double[] widths = new double[2];
        runOnFxThread(() -> {
            InspectorDrawer drawer = new InspectorDrawer();
            drawer.setAnimated(false);
            drawer.setExpanded(true);
            Pane root = new Pane(drawer);
            new Scene(root, 600, 400);
            root.applyCss();
            root.layout();
            widths[0] = drawer.getWidth();
            drawer.setExpanded(false);
            root.applyCss();
            root.layout();
            widths[1] = drawer.getWidth();
            return null;
        });
        assertThat(widths[0]).isCloseTo(240.0, within(1.0));
        assertThat(widths[1]).isCloseTo(24.0, within(1.0));
    }
}
