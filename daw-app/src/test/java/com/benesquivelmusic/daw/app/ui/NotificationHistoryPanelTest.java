package com.benesquivelmusic.daw.app.ui;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
class NotificationHistoryPanelTest {

    private NotificationHistoryPanel createOnFxThread(
            NotificationHistoryService service) throws Exception {
        AtomicReference<NotificationHistoryPanel> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(new NotificationHistoryPanel(service));
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
        return ref.get();
    }

    private void runOnFxThread(Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
    }

    @Test
    void shouldStartWithEmptyList() throws Exception {
        NotificationHistoryService service = new NotificationHistoryService();
        NotificationHistoryPanel panel = createOnFxThread(service);

        assertThat(panel).isNotNull();
        assertThat(panel.getHistoryListView().getItems()).isEmpty();
    }

    @Test
    void shouldDisplayRecordedEntries() throws Exception {
        NotificationHistoryService service = new NotificationHistoryService();
        NotificationHistoryPanel panel = createOnFxThread(service);

        runOnFxThread(() -> {
            service.record(NotificationLevel.ERROR, "Error one");
            service.record(NotificationLevel.WARNING, "Warning one");
        });

        assertThat(panel.getHistoryListView().getItems()).hasSize(2);
        assertThat(panel.getHistoryListView().getItems().get(0).message())
                .isEqualTo("Error one");
        assertThat(panel.getHistoryListView().getItems().get(1).message())
                .isEqualTo("Warning one");
    }

    @Test
    void clearShouldEmptyTheList() throws Exception {
        NotificationHistoryService service = new NotificationHistoryService();
        NotificationHistoryPanel panel = createOnFxThread(service);

        runOnFxThread(() -> {
            service.record(NotificationLevel.ERROR, "Error");
            service.record(NotificationLevel.WARNING, "Warning");
        });
        assertThat(panel.getHistoryListView().getItems()).hasSize(2);

        runOnFxThread(service::clear);
        assertThat(panel.getHistoryListView().getItems()).isEmpty();
    }

    @Test
    void shouldHaveBrowserPanelStyleClass() throws Exception {
        NotificationHistoryService service = new NotificationHistoryService();
        NotificationHistoryPanel panel = createOnFxThread(service);

        assertThat(panel.getStyleClass()).contains("browser-panel");
    }

    @Test
    void disposeShouldStopUpdates() throws Exception {
        NotificationHistoryService service = new NotificationHistoryService();
        NotificationHistoryPanel panel = createOnFxThread(service);

        runOnFxThread(panel::dispose);

        // Record after dispose — panel should not update
        runOnFxThread(() -> service.record(NotificationLevel.ERROR, "After dispose"));

        // The panel items list should remain empty (it was never updated after dispose)
        assertThat(panel.getHistoryListView().getItems()).isEmpty();
    }
}
