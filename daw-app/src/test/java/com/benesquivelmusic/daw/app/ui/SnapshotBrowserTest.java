package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.snapshot.SnapshotBrowserService;
import com.benesquivelmusic.daw.core.snapshot.SnapshotEntry;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
class SnapshotBrowserTest {

    private SnapshotBrowser createOnFxThread(SnapshotBrowserService service) throws Exception {
        AtomicReference<SnapshotBrowser> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(new SnapshotBrowser(service));
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
        return ref.get();
    }

    private void runOnFxThread(Runnable r) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                r.run();
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
    }

    @Test
    void startsEmptyWhenServiceHasNoEntries() throws Exception {
        SnapshotBrowserService svc = new SnapshotBrowserService();
        SnapshotBrowser panel = createOnFxThread(svc);

        assertThat(panel).isNotNull();
        assertThat(panel.getListView().getItems()).isEmpty();
        assertThat(panel.getRestoreButton().isDisabled()).isTrue();
        assertThat(panel.getCompareButton().isDisabled()).isTrue();
    }

    @Test
    void displaysEntriesAndPreviewsContentOnSelection() throws Exception {
        SnapshotBrowserService svc = new SnapshotBrowserService();
        SnapshotEntry entry = svc.createUserCheckpoint("Before mastering",
                "<project>data</project>");

        SnapshotBrowser panel = createOnFxThread(svc);
        // Constructor auto-selected the most recent entry; verify listing.
        runOnFxThread(() -> assertThat(panel.getListView().getItems())
                .containsExactly(entry));
        runOnFxThread(() -> {
            panel.getListView().getSelectionModel().select(entry);
            assertThat(panel.getPreviewArea().getText())
                    .isEqualTo("<project>data</project>");
            assertThat(panel.getRestoreButton().isDisabled()).isFalse();
            assertThat(panel.getCompareButton().isDisabled()).isFalse();
        });
    }

    @Test
    void invokesRestoreAndCompareCallbacks() throws Exception {
        SnapshotBrowserService svc = new SnapshotBrowserService();
        SnapshotEntry entry = svc.createUserCheckpoint("X", "data");

        SnapshotBrowser panel = createOnFxThread(svc);
        AtomicReference<SnapshotEntry> restored = new AtomicReference<>();
        AtomicReference<SnapshotEntry> compared = new AtomicReference<>();
        AtomicBoolean checkpointFired = new AtomicBoolean(false);

        runOnFxThread(() -> {
            panel.setOnRestore(restored::set);
            panel.setOnCompare(compared::set);
            panel.setOnCreateCheckpoint(() -> checkpointFired.set(true));
            panel.getListView().getSelectionModel().select(entry);
            panel.getRestoreButton().fire();
            panel.getCompareButton().fire();
            panel.getCheckpointButton().fire();
        });

        assertThat(restored.get()).isEqualTo(entry);
        assertThat(compared.get()).isEqualTo(entry);
        assertThat(checkpointFired.get()).isTrue();
    }
}
