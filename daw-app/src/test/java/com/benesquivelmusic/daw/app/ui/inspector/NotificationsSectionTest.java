package com.benesquivelmusic.daw.app.ui.inspector;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.NotificationHistoryService;
import com.benesquivelmusic.daw.app.ui.NotificationLevel;
import com.benesquivelmusic.daw.app.ui.NotificationPill;

import javafx.scene.Scene;
import javafx.scene.layout.Pane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Optional;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * UI Design Book §5.6 / §7.8 — the notification history is folded into
 * the inspector drawer as a Notifications section (no standalone
 * coloured surface), rendering one shared {@link NotificationPill} per
 * entry, newest-first.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class NotificationsSectionTest {

    /**
     * Test-local convenience for the action-less record path. Production
     * always calls the canonical four-argument {@code record} (story 273);
     * only tests want the short form, so the convenience lives here.
     */
    private static void record(NotificationHistoryService svc,
                               NotificationLevel level,
                               String message) {
        svc.record(level, message, Optional.empty(), Optional.empty());
    }

    @Test
    void postedNotificationsRenderNewestFirstAsPills() {
        List<NotificationPill> pills = runOnFxThread(() -> {
            InspectorDrawer drawer = new InspectorDrawer();
            drawer.setAnimated(false);

            NotificationHistoryService svc = new NotificationHistoryService();
            drawer.setNotificationHistoryService(svc);

            Pane root = new Pane(drawer);
            new Scene(root, 400, 600);
            root.applyCss();
            root.layout();

            record(svc, NotificationLevel.INFO, "first");
            record(svc, NotificationLevel.SUCCESS, "second");
            record(svc, NotificationLevel.WARNING, "third");
            record(svc, NotificationLevel.ERROR, "fourth");
            record(svc, NotificationLevel.INFO, "fifth");

            drawer.setExpanded(true);
            drawer.getNotificationsSection().setExpanded(true);
            root.applyCss();
            root.layout();

            return drawer.getNotificationsSection().getPills();
        });

        assertThat(pills).hasSize(5);
        // Newest-first (reverse chronological).
        assertThat(pills.get(0).getMessage()).isEqualTo("fifth");
        assertThat(pills.get(1).getMessage()).isEqualTo("fourth");
        assertThat(pills.get(2).getMessage()).isEqualTo("third");
        assertThat(pills.get(3).getMessage()).isEqualTo("second");
        assertThat(pills.get(4).getMessage()).isEqualTo("first");
        assertThat(pills.get(0).getCurrentLevel()).isEqualTo(NotificationLevel.INFO);
        assertThat(pills.get(2).getCurrentLevel()).isEqualTo(NotificationLevel.WARNING);
    }

    @Test
    void sectionIsTheSixthInspectorSection() {
        boolean[] present = new boolean[1];
        runOnFxThread(() -> {
            InspectorDrawer drawer = new InspectorDrawer();
            present[0] = drawer.getSections().size() == 6
                    && drawer.getSections().get(5) == drawer.getNotificationsSection();
            return null;
        });
        assertThat(present[0])
                .as("Notifications is appended after Notes (§5.6 order)")
                .isTrue();
    }

    @Test
    void clearedHistoryEmptiesThePills() {
        int[] sizes = new int[2];
        runOnFxThread(() -> {
            InspectorDrawer drawer = new InspectorDrawer();
            drawer.setAnimated(false);
            NotificationHistoryService svc = new NotificationHistoryService();
            drawer.setNotificationHistoryService(svc);

            Pane root = new Pane(drawer);
            new Scene(root, 400, 600);
            root.applyCss();
            root.layout();

            record(svc, NotificationLevel.ERROR, "boom");
            record(svc, NotificationLevel.WARNING, "warn");
            sizes[0] = drawer.getNotificationsSection().getPills().size();

            svc.clear();
            sizes[1] = drawer.getNotificationsSection().getPills().size();
            return null;
        });

        assertThat(sizes[0]).isEqualTo(2);
        assertThat(sizes[1]).isZero();
    }
}
