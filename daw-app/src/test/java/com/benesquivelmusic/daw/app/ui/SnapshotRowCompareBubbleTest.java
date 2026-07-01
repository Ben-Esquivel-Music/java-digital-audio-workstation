package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.snapshot.SnapshotEntry;
import com.benesquivelmusic.daw.core.snapshot.SnapshotKind;
import javafx.application.Platform;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
class SnapshotRowCompareBubbleTest {

    @Test
    void compareRequestBubblesWithSnapshotPayloadToParentFilter() throws Exception {
        SnapshotEntry entry = new SnapshotEntry(
                "snap-1",
                Instant.parse("2026-06-21T12:00:00Z"),
                SnapshotKind.NAMED_SNAPSHOT,
                "Client review",
                "client-review.daw",
                () -> "<project/>");

        AtomicReference<SessionManagerDock.SnapshotDockEvent> captured =
                new AtomicReference<>();

        runOnFxThread(() -> {
            SessionManagerDock dock = new SessionManagerDock();
            VBox parent = new VBox(dock);
            parent.addEventFilter(
                    SessionManagerDock.SnapshotDockEvent.COMPARE_REQUESTED,
                    captured::set);
            dock.setNamedSnapshots(List.of(entry));

            dock.fireEvent(new SessionManagerDock.SnapshotDockEvent(
                    dock,
                    dock,
                    SessionManagerDock.SnapshotDockEvent.COMPARE_REQUESTED,
                    entry));
        });

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().getEntry()).isEqualTo(entry);
    }

    private static void runOnFxThread(Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }
}
