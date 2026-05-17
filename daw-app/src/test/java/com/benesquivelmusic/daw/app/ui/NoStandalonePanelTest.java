package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.inspector.InspectorDrawer;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UI Design Book §7.8 — the two-notification-systems veto. The
 * standalone {@code NotificationHistoryPanel} is removed entirely
 * (story 273); the history lives only in the inspector drawer.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class NoStandalonePanelTest {

    @Test
    void standalonePanelClassNoLongerExists() {
        assertThatThrownBy(() -> Class.forName(
                "com.benesquivelmusic.daw.app.ui.NotificationHistoryPanel"))
                .as("the standalone notification-history panel must be deleted")
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void noNotificationHistoryPanelNodeInSceneGraph() {
        boolean[] found = new boolean[1];
        runOnFxThread(() -> {
            InspectorDrawer drawer = new InspectorDrawer();
            drawer.setAnimated(false);
            drawer.setExpanded(true);
            drawer.getNotificationsSection().setExpanded(true);

            Pane root = new Pane(drawer);
            new Scene(root, 400, 600);
            root.applyCss();
            root.layout();

            found[0] = containsTypeNamed(root, "NotificationHistoryPanel");
            return null;
        });

        assertThat(found[0])
                .as("no NotificationHistoryPanel-typed node may remain in the scene graph")
                .isFalse();
    }

    private static boolean containsTypeNamed(Node node, String simpleName) {
        if (simpleName.equals(node.getClass().getSimpleName())) {
            return true;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                if (containsTypeNamed(child, simpleName)) {
                    return true;
                }
            }
        }
        return false;
    }
}
