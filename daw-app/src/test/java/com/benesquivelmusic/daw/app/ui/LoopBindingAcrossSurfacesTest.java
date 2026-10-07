package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.vm.TransportControlBinder;
import com.benesquivelmusic.daw.app.ui.vm.TransportVM;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleLoopCommand;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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
 * Story 315 — the Loop button's lit state is a pure function of
 * {@link TransportVM} through {@link TransportControlBinder#bindLoop}: a loop
 * defined from ANY surface lights it, with no imperative sync call.
 *
 * <p>The ruler half fires a <em>real</em> Shift-press {@link MouseEvent} on the
 * {@link TimelineRuler} canvas (the ruler lives in a Scene with a completed
 * layout pass — headless-FX convention of this repo), so the full production
 * gesture path runs: Shift-press → {@code Transport.setLoopEnabled(true)} +
 * {@code setLoopRegion} → LOOP signal → VM republish → binder lights the
 * button. The toolbar half runs a {@link ToggleLoopCommand} through the
 * production handler ({@link TransportController}) and asserts the reverse:
 * loop off → button unlit → the ruler's next redraw reads the same (it paints
 * straight from the transport).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class LoopBindingAcrossSurfacesTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");
    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 16, 256);

    @Test
    void rulerShiftClickLightsTheLoopButtonThroughTheVmBindingAndToolbarToggleUnlightsIt()
            throws Exception {
        DawProject project = new DawProject("loop-binding", FORMAT);
        Transport transport = project.getTransport();
        FxDispatcher dispatcher = new FxDispatcher();
        TransportVM vm = new TransportVM(transport, dispatcher);

        TransportController handler = newController(project);
        Button loopButton = computeOnFx(Button::new);
        TransportControlBinder binder =
                new TransportControlBinder(vm, command -> command.execute(handler));
        TimelineRuler ruler = computeOnFx(() -> {
            TimelineRuler r = new TimelineRuler(transport);
            // Headless-FX convention: sizing/layout only takes effect once the
            // node is in a Scene and a layout pass has run.
            new Scene(r, 800, TimelineRuler.DEFAULT_HEIGHT);
            r.applyCss();
            r.layout();
            return r;
        });
        try {
            computeOnFx(() -> { binder.bindLoop(loopButton); return null; });
            assertThat(computeOnFx(() -> loopButton.getPseudoClassStates().contains(ACTIVE)))
                    .as("loop button starts unlit").isFalse();

            // ── Ruler surface: a REAL Shift-press + drag + release gesture ──
            Canvas canvas = computeOnFx(() ->
                    (Canvas) ruler.getChildrenUnmodifiable().get(0));
            computeOnFx(() -> {
                Event.fireEvent(canvas, mouseEvent(MouseEvent.MOUSE_PRESSED, 160.0, true));
                Event.fireEvent(canvas, mouseEvent(MouseEvent.MOUSE_DRAGGED, 320.0, true));
                Event.fireEvent(canvas, mouseEvent(MouseEvent.MOUSE_RELEASED, 320.0, true));
                return null;
            });

            // The gesture wrote the transport (at 40 px/beat: 160 px → beat 4,
            // 320 px → beat 8)…
            assertThat(transport.isLoopEnabled())
                    .as("Shift-press enables loop mode on the transport").isTrue();
            assertThat(transport.getLoopStartInBeats()).isEqualTo(4.0);
            assertThat(transport.getLoopEndInBeats()).isEqualTo(8.0);

            // …and the button lights through the VM binding alone — no
            // syncLoopButtonState() exists to call (source-scan-enforced).
            flushFx();
            assertThat(computeOnFx(() -> loopButton.getPseudoClassStates().contains(ACTIVE)))
                    .as("a ruler-defined loop lights the Loop button through the VM binding")
                    .isTrue();

            // ── Toolbar surface: ToggleLoopCommand through the production handler ──
            computeOnFx(() -> { new ToggleLoopCommand().execute(handler); return null; });
            assertThat(transport.isLoopEnabled())
                    .as("the toolbar toggle disables loop mode").isFalse();
            flushFx();
            assertThat(computeOnFx(() -> loopButton.getPseudoClassStates().contains(ACTIVE)))
                    .as("the button unlights through the same binding").isFalse();
            // The ruler reflects it on its next redraw because it paints
            // straight from the transport it is bound to.
            computeOnFx(() -> { ruler.redraw(); return null; });
            assertThat(ruler.getModel().getTransport().isLoopEnabled()).isFalse();
        } finally {
            computeOnFx(() -> { binder.dispose(); return null; });
            vm.dispose();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static MouseEvent mouseEvent(
            javafx.event.EventType<MouseEvent> type, double x, boolean shiftDown) {
        boolean primaryDown = type != MouseEvent.MOUSE_RELEASED;
        return new MouseEvent(type, x, 16.0, x, 16.0, MouseButton.PRIMARY, 1,
                shiftDown, false, false, false,
                primaryDown, false, false,
                false, false, false, null);
    }

    private TransportController newController(DawProject project) throws Exception {
        return computeOnFx(() -> new TransportController(
                project, new AudioEngine(project.getFormat()), new UndoManager(),
                new NotificationBar(), new Label(), new Label(), new Label(),
                new Button(), new Button(),
                () -> false,
                () -> GridResolution.QUARTER,
                () -> CountInMode.OFF,
                track -> { },
                () -> true,
                () -> com.benesquivelmusic.daw.sdk.audio.RoundTripLatency.UNKNOWN));
    }

    /** Posts a barrier onto the FX thread and blocks until it (and every earlier {@code onFx}) has run. */
    private static void flushFx() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
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
