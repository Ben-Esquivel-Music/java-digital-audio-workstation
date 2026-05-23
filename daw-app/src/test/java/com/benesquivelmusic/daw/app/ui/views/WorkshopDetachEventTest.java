package com.benesquivelmusic.daw.app.ui.views;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;

import javafx.application.Platform;
import javafx.event.EventType;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 281 — pressing the {@code ◯ Detach} button in the Workshop right
 * pane's breadcrumb header fires a typed {@link DetachPluginRequestedEvent}
 * that bubbles up the scene graph. Story 282 will install an
 * {@code addEventHandler(DETACH_PLUGIN_REQUESTED, …)} at the application
 * root and float the focused plugin into a stand-alone window; this story
 * stops at the event-fire stub, and this test pins that contract so the
 * 282 consumer has something to bind against.
 *
 * <p>Follows the bubbling-event-test pitfall guidance (memory
 * {@code feedback_javafx_bubbling_event_test_pitfall.md}): the filter is
 * attached at a {@link StackPane} <em>parent</em> of the WorkshopView and
 * the assertion is on the bubbled event's
 * {@link EventType} — never on {@code getSource()} identity, which JavaFX
 * rewrites per node during dispatch.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class WorkshopDetachEventTest {

    @Test
    void detachButtonFiresBubblingDetachPluginRequestedEvent() throws Exception {
        onFxThread(() -> {
            WorkshopView view = newWorkshopView();
            // Wrap in a real Parent so the bubble has somewhere to land.
            // The filter sits on this parent — strictly an ancestor of the
            // Detach button — so the test verifies the event leaves the
            // button and propagates upward.
            StackPane parent = new StackPane(view);
            new Scene(parent, 1280, 800);

            List<DetachPluginRequestedEvent> received = new ArrayList<>();
            parent.addEventFilter(
                    DetachPluginRequestedEvent.DETACH_PLUGIN_REQUESTED,
                    received::add);

            Button detach = view.detachButton();
            assertThat(detach)
                    .as("the Workshop view exposes the Detach button as a test seam")
                    .isNotNull();

            // Use Button.fire() (not click coordinates) — invokes the
            // button's onAction handler synchronously on the FX thread,
            // which fires DetachPluginRequestedEvent via Node#fireEvent.
            detach.fire();

            assertThat(received)
                    .as("the Detach button must fire exactly one "
                            + "DetachPluginRequestedEvent that bubbles to the "
                            + "WorkshopView's parent")
                    .hasSize(1);
            assertThat(received.get(0).getEventType())
                    .as("the bubbled event carries the typed "
                            + "DETACH_PLUGIN_REQUESTED event-type so story "
                            + "282 can bind addEventHandler(DETACH_PLUGIN_REQUESTED, …)")
                    .isEqualTo(DetachPluginRequestedEvent.DETACH_PLUGIN_REQUESTED);
            return null;
        });
    }

    // ── Harness ───────────────────────────────────────────────────────────

    private static WorkshopView newWorkshopView() {
        ResourceBundle messages = ResourceBundle.getBundle(
                "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);
        return new WorkshopView(messages);
    }

    private static <T> T onFxThread(Supplier<T> supplier) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(supplier.get());
            } catch (Throwable t) {
                err.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(15, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX thread did not complete within 15 seconds");
        }
        if (err.get() != null) {
            throw new AssertionError("FX thread action failed", err.get());
        }
        return ref.get();
    }
}
