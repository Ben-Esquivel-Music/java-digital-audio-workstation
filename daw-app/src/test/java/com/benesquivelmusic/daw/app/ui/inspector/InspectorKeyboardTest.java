package com.benesquivelmusic.daw.app.ui.inspector;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * §5.6 keyboard parity — every section body must be reachable by
 * {@code Tab} traversal, and traversal must not escape the inspector
 * to a sibling panel before every focusable node has been visited.
 *
 * <p>We don't drive AWT/Robot here (headless-flaky) — instead we
 * collect every focus-traversable descendant of the drawer and assert
 * that it covers all five section bodies' interactive controls.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class InspectorKeyboardTest {

    @Test
    void everyFocusableInspectorControlIsReachable() {
        int[] focusable = new int[1];
        boolean[] sectionsRepresented = new boolean[5];
        runOnFxThread(() -> {
            InspectorDrawer drawer = new InspectorDrawer();
            drawer.setAnimated(false);
            drawer.setExpanded(true);
            Pane root = new Pane(drawer);
            new Scene(root, 400, 600);
            root.applyCss();
            root.layout();

            List<Node> traversable = new ArrayList<>();
            collectFocusTraversable(drawer, traversable);
            focusable[0] = traversable.size();

            sectionsRepresented[0] = drawer.getTrackSection().getNameField().isFocusTraversable();
            sectionsRepresented[1] = drawer.getInsertsSection().getAddButton().isFocusTraversable();
            // Sends section has no rows by default — assert the section
            // node itself accepts focus traversal via its header.
            sectionsRepresented[2] = drawer.getSendsSection().getHeader().isFocusTraversable();
            sectionsRepresented[3] = drawer.getRoutingSection().getHeader().isFocusTraversable();
            sectionsRepresented[4] = drawer.getNotesSection().getTextArea().isFocusTraversable();
            return null;
        });
        assertThat(focusable[0])
                .as("inspector subtree exposes at least one focusable node per section")
                .isGreaterThanOrEqualTo(5);
        for (int i = 0; i < sectionsRepresented.length; i++) {
            assertThat(sectionsRepresented[i])
                    .as("section " + i + " contributes at least one focus-traversable node")
                    .isTrue();
        }
    }

    private static void collectFocusTraversable(Node n, List<Node> out) {
        if (n.isFocusTraversable()) out.add(n);
        if (n instanceof javafx.scene.Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) {
                collectFocusTraversable(c, out);
            }
        }
    }
}
