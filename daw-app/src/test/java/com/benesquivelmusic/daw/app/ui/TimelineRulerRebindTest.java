package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.transport.TimeDisplayMode;
import com.benesquivelmusic.daw.core.transport.Transport;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 315 — regression for the stale-collaborator bug: the ruler is built
 * once but the project (and its {@link Transport}) is swapped on every load;
 * {@link TimelineRuler#rebind(Transport)} must retarget every gesture and
 * paint path at the live transport, carry the user's display mode across, and
 * leave the dead project's transport untouched.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class TimelineRulerRebindTest {

    private static final long TIMEOUT_SECONDS = 5;

    @Test
    void rebindRetargetsLoopGesturesAtTheNewTransportAndPreservesDisplayMode() throws Exception {
        Transport transportA = new Transport();
        Transport transportB = new Transport();

        TimelineRuler ruler = computeOnFx(() -> {
            TimelineRuler r = new TimelineRuler(transportA);
            new Scene(r, 800, TimelineRuler.DEFAULT_HEIGHT);
            r.applyCss();
            r.layout();
            // The user's mode choice must survive the project switch.
            r.toggleDisplayMode();
            return r;
        });
        assertThat(ruler.getDisplayMode()).isEqualTo(TimeDisplayMode.TIME);
        assertThat(ruler.getModel().getTransport()).isSameAs(transportA);

        // Project switch — the epoch rule applied to the ruler.
        computeOnFx(() -> { ruler.rebind(transportB); return null; });
        assertThat(ruler.getModel().getTransport())
                .as("the ruler reads the LIVE project's transport after rebind")
                .isSameAs(transportB);
        assertThat(ruler.getDisplayMode())
                .as("the display mode survives the rebind")
                .isEqualTo(TimeDisplayMode.TIME);

        // A real Shift-press+drag loop gesture now mutates B…
        Canvas canvas = computeOnFx(() -> (Canvas) ruler.getChildrenUnmodifiable().get(0));
        computeOnFx(() -> {
            Event.fireEvent(canvas, mouseEvent(MouseEvent.MOUSE_PRESSED, 80.0));
            Event.fireEvent(canvas, mouseEvent(MouseEvent.MOUSE_DRAGGED, 240.0));
            Event.fireEvent(canvas, mouseEvent(MouseEvent.MOUSE_RELEASED, 240.0));
            return null;
        });

        assertThat(transportB.isLoopEnabled())
                .as("the gesture mutates the NEW project's transport").isTrue();
        assertThat(transportB.getLoopStartInBeats()).isEqualTo(2.0);
        assertThat(transportB.getLoopEndInBeats()).isEqualTo(6.0);

        // …and the dead project's transport is untouched (the regression).
        assertThat(transportA.isLoopEnabled())
                .as("the dead project's transport is never written").isFalse();
        assertThat(transportA.getLoopStartInBeats()).isEqualTo(0.0);
        assertThat(transportA.getLoopEndInBeats()).isEqualTo(16.0);
    }

    @Test
    void rebindingToTheSameTransportKeepsTheSameModelInstance() throws Exception {
        // Story 315 review — rebind() is idempotent (story 314's epoch-guard
        // style). rebuildViewModels() runs at startup too, right after the ruler
        // was built against this very transport; reallocating the model and
        // forcing a redraw there is pure waste.
        Transport transport = new Transport();

        TimelineRuler ruler = computeOnFx(() -> {
            TimelineRuler r = new TimelineRuler(transport);
            new Scene(r, 800, TimelineRuler.DEFAULT_HEIGHT);
            r.applyCss();
            r.layout();
            return r;
        });
        Object modelBefore = ruler.getModel();

        computeOnFx(() -> { ruler.rebind(transport); return null; });

        assertThat(ruler.getModel())
                .as("rebinding to the transport the ruler already targets is a no-op")
                .isSameAs(modelBefore);

        // A genuine switch still reallocates.
        Transport other = new Transport();
        computeOnFx(() -> { ruler.rebind(other); return null; });
        assertThat(ruler.getModel())
                .as("a real project switch still installs a fresh model")
                .isNotSameAs(modelBefore);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static MouseEvent mouseEvent(javafx.event.EventType<MouseEvent> type, double x) {
        boolean primaryDown = type != MouseEvent.MOUSE_RELEASED;
        return new MouseEvent(type, x, 16.0, x, 16.0, MouseButton.PRIMARY, 1,
                true, false, false, false,
                primaryDown, false, false,
                false, false, false, null);
    }

    /** Runs {@code work} on the FX thread with capture-and-rethrow of assertion errors. */
    private static <T> T computeOnFx(Callable<T> work) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                result.set(work.call());
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        switch (thrown.get()) {
            case null -> { }
            case RuntimeException re -> throw re;
            case Error e -> throw e;
            case Throwable t -> throw new AssertionError("FX work threw", t);
        }
        return result.get();
    }
}
