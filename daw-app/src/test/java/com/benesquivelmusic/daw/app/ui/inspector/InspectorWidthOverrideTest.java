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
 * §5.6 — applying a stylesheet that sets
 * {@code -inspector-expanded-width: 320;} on {@code .inspector-drawer}
 * lifts the expanded width without code changes (proves the
 * {@link javafx.css.StyleableProperty} token works end-to-end).
 */
@ExtendWith(JavaFxToolkitExtension.class)
class InspectorWidthOverrideTest {

    @Test
    void cssOverrideOfExpandedWidthIsRespected() throws Exception {
        // Inline data: URL stylesheet — no resource lookup required.
        String css = ".inspector-drawer { -inspector-expanded-width: 320; }";
        String dataUrl = "data:text/css;base64,"
                + java.util.Base64.getEncoder()
                        .encodeToString(css.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        double width = runOnFxThread(() -> {
            InspectorDrawer drawer = new InspectorDrawer();
            drawer.setAnimated(false);
            drawer.setExpanded(true);
            Pane root = new Pane(drawer);
            Scene scene = new Scene(root, 800, 400);
            scene.getStylesheets().add(dataUrl);
            root.applyCss();
            root.layout();
            // Trigger a re-apply now that the override has loaded.
            drawer.setExpanded(false);
            drawer.setExpanded(true);
            root.applyCss();
            root.layout();
            return drawer.getWidth();
        });
        assertThat(width)
                .as("CSS override of -inspector-expanded-width must take effect")
                .isCloseTo(320.0, within(1.0));
    }
}
