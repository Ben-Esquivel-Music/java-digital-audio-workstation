package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.dock.PanelGripHandle;
import com.benesquivelmusic.daw.app.ui.layout.PanelDetachRequestedEvent;

import javafx.application.Platform;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 288 — {@code PanelGripFiresDetachEventTest}. A {@link
 * PanelGripHandle} dropped on no accepting target (transfer mode {@code
 * null}) must fire a {@link PanelDetachRequestedEvent} carrying the panel
 * id and the drop-point bounds, so it bubbles to the {@code MainController}
 * detach bridge.
 *
 * <p>A real drag-and-drop {@code DragEvent} (with a {@code Dragboard})
 * cannot be synthesised headless, so the test invokes the package-private
 * {@link PanelGripHandle#completeGesture} seam directly — the same code
 * {@code setOnDragDone} runs. The assertion reads the event payload via a
 * parent {@code addEventFilter}, never {@code Event.getSource()} identity
 * (JavaFX rewrites the source per node on bubble —
 * {@code feedback_javafx_bubbling_event_test_pitfall.md}).</p>
 *
 * <p>Runs on the FX toolkit (and in the {@code …app.ui} package) because
 * constructing a {@code PanelGripHandle} builds a {@code DawgIcon} and
 * installs a {@code Tooltip} — both are FX scene nodes
 * ({@code project_extendwith_jpms_test_env.md}).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class PanelGripFiresDetachEventTest {

    private <T> T onFx(java.util.function.Supplier<T> body) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<RuntimeException> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(body.get());
            } catch (RuntimeException e) {
                err.set(e);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).as("FX task completed").isTrue();
        if (err.get() != null) {
            throw err.get();
        }
        return ref.get();
    }

    @Test
    void detachGestureFiresDetachEventWithDropBounds() throws Exception {
        AtomicReference<PanelDetachRequestedEvent> captured = new AtomicReference<>();

        PanelDetachRequestedEvent event = onFx(() -> {
            Region boundsSource = new Region();
            boundsSource.resize(420, 300); // give the panel a measured size
            PanelGripHandle grip = new PanelGripHandle("mixer", boundsSource);

            // Parent filters on the event PAYLOAD (not getSource()).
            StackPane parent = new StackPane(grip);
            parent.addEventFilter(
                    PanelDetachRequestedEvent.PANEL_DETACH_REQUESTED, captured::set);

            // Drop on no accepting target → transferMode == null.
            grip.completeGesture(null, 640, 480);
            return captured.get();
        });

        assertThat(event).as("detach event was fired and bubbled to the parent").isNotNull();
        assertThat(event.getPanelId()).isEqualTo("mixer");
        assertThat(event.getBounds()).as("drop-point bounds were carried").isNotNull();
        assertThat(event.getBounds().x()).isEqualTo(640.0);
        assertThat(event.getBounds().y()).isEqualTo(480.0);
        assertThat(event.getBounds().width()).isEqualTo(420.0);
        assertThat(event.getBounds().height()).isEqualTo(300.0);
    }

    @Test
    void acceptedDropDoesNotFireDetachEvent() throws Exception {
        // When a dock zone accepts the drop (non-null transfer mode), the
        // grip must NOT fire a detach event — the zone fires the dock event.
        AtomicReference<PanelDetachRequestedEvent> captured = new AtomicReference<>();

        onFx(() -> {
            Region boundsSource = new Region();
            boundsSource.resize(420, 300);
            PanelGripHandle grip = new PanelGripHandle("mixer", boundsSource);
            StackPane parent = new StackPane(grip);
            parent.addEventFilter(
                    PanelDetachRequestedEvent.PANEL_DETACH_REQUESTED, captured::set);

            grip.completeGesture(javafx.scene.input.TransferMode.MOVE, 640, 480);
            return null;
        });

        assertThat(captured.get())
                .as("an accepted drop fires no detach event")
                .isNull();
    }
}
