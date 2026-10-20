package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.transport.PunchRegion;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * PR #984 review — the loop window and the punch region are saved with the
 * project, so a ruler gesture that changed either runs the ruler's edit
 * callback ({@link TimelineRuler#setOnTransportEdited}; production: mark the
 * live project dirty) exactly once, at its release (or, when the release
 * never arrived, at the ruler's next button release or primary press). The
 * decision compares both values at the release with those the press found —
 * read before the press wrote anything, whichever half the press was in — so
 * a click, a drag back to the starting value and a punch Shift-drag that
 * never spans the minimum run nothing, while an upper-half Shift+click, which
 * defines a loop on the press itself, does whenever that loop differs from
 * the one before.
 *
 * <p>Geometry as in {@code TimelineRulerPunchGestureTest}: 40 px per beat, no
 * scroll, 120 BPM, ruler height 32 px, project rate 96 kHz (beat 2 = 96 000
 * frames, beat 6 = 288 000, beat 8 = 384 000); loop gestures in the upper half
 * ({@code y = 8}), punch gestures in the lower half ({@code y = 28}).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class TimelineRulerGesturesReportEditsOnReleaseTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final double PROJECT_RATE = 96_000.0;
    private static final double UPPER_HALF_Y = 8.0;
    private static final double LOWER_HALF_Y = 28.0;
    private static final long BEAT_2 = 96_000L;
    private static final long BEAT_6 = 288_000L;
    private static final long BEAT_8 = 384_000L;

    private final AtomicInteger edits = new AtomicInteger();

    @Test
    void aPunchShiftDragDefinitionRunsTheCallbackOnceAtItsReleaseAndNotBefore() throws Exception {
        Transport transport = transportAt120Bpm();
        TimelineRuler ruler = buildRuler(transport);
        Canvas canvas = canvasOf(ruler);

        computeOnFx(() -> {
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_PRESSED, 80.0, LOWER_HALF_Y, true));
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_DRAGGED, 240.0, LOWER_HALF_Y, true));
            return null;
        });
        assertThat(transport.getPunchRegion())
                .as("guard: the drag already installed the region")
                .isEqualTo(PunchRegion.enabled(BEAT_2, BEAT_6));
        assertThat(edits).as("nothing is decided before the release").hasValue(0);

        computeOnFx(() -> {
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_RELEASED, 240.0, LOWER_HALF_Y, true));
            return null;
        });
        assertThat(edits).as("once, at the release").hasValue(1);
    }

    @Test
    void aPunchShiftDragShorterThanTheMinimumDoesNotRunTheCallback() throws Exception {
        Transport transport = transportAt120Bpm();
        TimelineRuler ruler = buildRuler(transport);
        Canvas canvas = canvasOf(ruler);

        computeOnFx(() -> {
            // 0.5 px = 600 frames < 960 (2 × 5 ms @ 96 kHz): nothing is written.
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_PRESSED, 80.0, LOWER_HALF_Y, true));
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_DRAGGED, 80.5, LOWER_HALF_Y, true));
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_RELEASED, 80.5, LOWER_HALF_Y, true));
            return null;
        });

        assertThat(transport.getPunchRegion()).as("guard: no region was installed").isNull();
        assertThat(edits).hasValue(0);
    }

    @Test
    void aPunchHandleDragRunsTheCallbackOnce() throws Exception {
        Transport transport = transportAt120Bpm();
        transport.setPunchRegion(PunchRegion.enabled(BEAT_2, BEAT_6));
        TimelineRuler ruler = buildRuler(transport);
        Canvas canvas = canvasOf(ruler);

        computeOnFx(() -> {
            ruler.redraw();
            double outX = ruler.lastDrawnPunchOutX();
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_PRESSED, outX + 3.0, LOWER_HALF_Y, false));
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_DRAGGED, 280.0, LOWER_HALF_Y, false));
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_DRAGGED, 320.0, LOWER_HALF_Y, false));
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_RELEASED, 320.0, LOWER_HALF_Y, false));
            return null;
        });

        assertThat(transport.getPunchRegion()).isEqualTo(PunchRegion.enabled(BEAT_2, BEAT_8));
        assertThat(edits).as("one gesture, one decision — not one per drag event").hasValue(1);
    }

    @Test
    void aPunchHandlePressAndReleaseWithoutMovingDoesNotRunTheCallback() throws Exception {
        Transport transport = transportAt120Bpm();
        transport.setPunchRegion(PunchRegion.enabled(BEAT_2, BEAT_6));
        TimelineRuler ruler = buildRuler(transport);
        Canvas canvas = canvasOf(ruler);

        computeOnFx(() -> {
            ruler.redraw();
            double outX = ruler.lastDrawnPunchOutX();
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_PRESSED, outX + 3.0, LOWER_HALF_Y, false));
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_RELEASED, outX + 3.0, LOWER_HALF_Y, false));
            return null;
        });

        assertThat(transport.getPunchRegion()).isEqualTo(PunchRegion.enabled(BEAT_2, BEAT_6));
        assertThat(edits).hasValue(0);
    }

    @Test
    void aPlainClickToSeekDoesNotRunTheCallback() throws Exception {
        Transport transport = transportAt120Bpm();
        transport.setPunchRegion(PunchRegion.enabled(BEAT_2, BEAT_6));
        transport.setLoopWindow(true, 2.0, 6.0);
        TimelineRuler ruler = buildRuler(transport);
        Canvas canvas = canvasOf(ruler);
        List<Double> seeks = new CopyOnWriteArrayList<>();
        ruler.addSeekListener(seeks::add);

        computeOnFx(() -> {
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_PRESSED, 160.0, LOWER_HALF_Y, false));
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_RELEASED, 160.0, LOWER_HALF_Y, false));
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_PRESSED, 160.0, UPPER_HALF_Y, false));
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_RELEASED, 160.0, UPPER_HALF_Y, false));
            return null;
        });

        assertThat(seeks).as("guard: both clicks seeked").containsExactly(4.0, 4.0);
        assertThat(edits).as("a playhead move is not an edit of the project").hasValue(0);
    }

    @Test
    void anUpperHalfShiftClickWithoutDraggingRunsTheCallbackOnce() throws Exception {
        Transport transport = transportAt120Bpm();
        Transport.LoopWindow before = transport.getLoopWindow();
        TimelineRuler ruler = buildRuler(transport);
        Canvas canvas = canvasOf(ruler);

        computeOnFx(() -> {
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_PRESSED, 80.0, UPPER_HALF_Y, true));
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_RELEASED, 80.0, UPPER_HALF_Y, true));
            return null;
        });

        assertThat(transport.getLoopWindow())
                .as("guard: the press itself defined a loop at the pressed beat")
                .isEqualTo(new Transport.LoopWindow(true, 2.0, 2.001))
                .isNotEqualTo(before);
        assertThat(edits)
                .as("the press's own write is part of the gesture: the comparison is with the value before it")
                .hasValue(1);
    }

    @Test
    void anUpperHalfShiftClickThatOnlyReEnablesTheSameLoopIsAnEdit() throws Exception {
        Transport transport = transportAt120Bpm();
        // As a snapped Shift+click at beat 4 left it, then switched off with the Loop button.
        transport.setLoopWindow(false, 4.0, 4.0 + 0.001);
        TimelineRuler ruler = buildRuler(transport);
        Canvas canvas = canvasOf(ruler);

        computeOnFx(() -> {
            ruler.setSnapEnabled(true);
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_PRESSED, 4.3 * 40.0, UPPER_HALF_Y, true));
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_RELEASED, 4.3 * 40.0, UPPER_HALF_Y, true));
            return null;
        });

        assertThat(transport.getLoopWindow())
                .as("guard: the click snapped onto the same bounds and enabled them")
                .isEqualTo(new Transport.LoopWindow(true, 4.0, 4.0 + 0.001));
        assertThat(edits).as("only the saved enabled flag changed: still an edit").hasValue(1);
    }

    @Test
    void anUpperHalfShiftClickThatReDefinesTheIdenticalLoopDoesNotRunTheCallback() throws Exception {
        Transport transport = transportAt120Bpm();
        // As an earlier snapped Shift+click at beat 4 left it.
        transport.setLoopWindow(true, 4.0, 4.0 + 0.001);
        TimelineRuler ruler = buildRuler(transport);
        Canvas canvas = canvasOf(ruler);
        List<Transport.ChangeKind> signals = new CopyOnWriteArrayList<>();
        transport.addChangeListener(signals::add);

        computeOnFx(() -> {
            ruler.setSnapEnabled(true);
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_PRESSED, 3.8 * 40.0, UPPER_HALF_Y, true));
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_RELEASED, 3.8 * 40.0, UPPER_HALF_Y, true));
            return null;
        });

        assertThat(signals).as("guard: the press did write the loop window").containsExactly(Transport.ChangeKind.LOOP);
        assertThat(transport.getLoopWindow())
                .as("guard: it wrote the window it found")
                .isEqualTo(new Transport.LoopWindow(true, 4.0, 4.0 + 0.001));
        assertThat(edits).as("the press wrote, but the saved value did not change").hasValue(0);
    }

    @Test
    void aLoopHandleDragRunsTheCallbackOnce() throws Exception {
        Transport transport = transportAt120Bpm();
        transport.setLoopWindow(true, 2.0, 6.0);
        TimelineRuler ruler = buildRuler(transport);
        Canvas canvas = canvasOf(ruler);

        computeOnFx(() -> {
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_PRESSED, 240.0, UPPER_HALF_Y, false));  // end handle
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_DRAGGED, 280.0, UPPER_HALF_Y, false));
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_DRAGGED, 320.0, UPPER_HALF_Y, false));
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_RELEASED, 320.0, UPPER_HALF_Y, false));
            return null;
        });

        assertThat(transport.getLoopWindow()).isEqualTo(new Transport.LoopWindow(true, 2.0, 8.0));
        assertThat(edits).hasValue(1);
    }

    @Test
    void aLoopHandleDraggedFromTheLowerHalfRunsTheCallbackOnce() throws Exception {
        Transport transport = transportAt120Bpm();
        transport.setLoopWindow(true, 2.0, 6.0);
        TimelineRuler ruler = buildRuler(transport);
        Canvas canvas = canvasOf(ruler);

        computeOnFx(() -> {
            // No punch region: the lower half's hit-test falls back to the loop's end handle.
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_PRESSED, 240.0, LOWER_HALF_Y, false));
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_DRAGGED, 320.0, LOWER_HALF_Y, false));
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_RELEASED, 320.0, LOWER_HALF_Y, false));
            return null;
        });

        assertThat(transport.getPunchRegion()).as("guard: still no punch region").isNull();
        assertThat(transport.getLoopWindow())
                .as("guard: the lower-half press moved the loop")
                .isEqualTo(new Transport.LoopWindow(true, 2.0, 8.0));
        assertThat(edits).as("both settings are compared, whichever half the press was in").hasValue(1);
    }

    @Test
    void aLoopHandleDraggedAwayAndBackToItsExactStartingValueDoesNotRunTheCallback() throws Exception {
        Transport transport = transportAt120Bpm();
        transport.setLoopWindow(true, 2.0, 6.0);
        TimelineRuler ruler = buildRuler(transport);
        Canvas canvas = canvasOf(ruler);
        AtomicReference<Transport.LoopWindow> midDrag = new AtomicReference<>();

        computeOnFx(() -> {
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_PRESSED, 80.0, UPPER_HALF_Y, false));   // start handle
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_DRAGGED, 120.0, UPPER_HALF_Y, false));  // beat 3
            midDrag.set(transport.getLoopWindow());
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_DRAGGED, 80.0, UPPER_HALF_Y, false));   // beat 2 again
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_RELEASED, 80.0, UPPER_HALF_Y, false));
            return null;
        });

        assertThat(midDrag.get())
                .as("guard: the drag did move the start on its way")
                .isEqualTo(new Transport.LoopWindow(true, 3.0, 6.0));
        assertThat(transport.getLoopWindow()).isEqualTo(new Transport.LoopWindow(true, 2.0, 6.0));
        assertThat(edits).as("values are compared, not pointer travel").hasValue(0);
    }

    @Test
    void aGestureARebindInterruptsNeitherRunsTheCallbackNorWritesTheNewTransport() throws Exception {
        Transport transportA = transportAt120Bpm();
        transportA.setLoopWindow(true, 2.0, 6.0);
        Transport transportB = transportAt120Bpm();
        Transport.LoopWindow bBefore = transportB.getLoopWindow();
        TimelineRuler ruler = buildRuler(transportA);
        Canvas canvas = canvasOf(ruler);

        computeOnFx(() -> {
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_PRESSED, 80.0, UPPER_HALF_Y, false));   // A's start handle
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_DRAGGED, 120.0, UPPER_HALF_Y, false));
            ruler.rebind(transportB);                                                              // project switch
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_DRAGGED, 160.0, UPPER_HALF_Y, false));
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_RELEASED, 160.0, UPPER_HALF_Y, false));
            return null;
        });

        assertThat(transportA.getLoopWindow())
                .as("guard: the gesture edited the replaced project's transport before the switch")
                .isEqualTo(new Transport.LoopWindow(true, 3.0, 6.0));
        assertThat(transportB.getLoopWindow())
                .as("the rest of the drag does not write the new project's transport")
                .isEqualTo(bBefore);
        assertThat(edits).as("the new project is not marked dirty for the old one's gesture").hasValue(0);
    }

    @Test
    void aGestureWhoseReleaseNeverArrivedIsDecidedAtTheNextPress() throws Exception {
        Transport transport = transportAt120Bpm();
        transport.setLoopWindow(true, 2.0, 6.0);
        TimelineRuler ruler = buildRuler(transport);
        Canvas canvas = canvasOf(ruler);

        computeOnFx(() -> {
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_PRESSED, 80.0, UPPER_HALF_Y, false));   // start handle
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_DRAGGED, 120.0, UPPER_HALF_Y, false));
            // No release: the next press is a click-to-seek well away from the handles.
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_PRESSED, 400.0, UPPER_HALF_Y, false));
            return null;
        });
        assertThat(edits).as("the unreleased gesture's edit is not lost").hasValue(1);

        computeOnFx(() -> {
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_RELEASED, 400.0, UPPER_HALF_Y, false));
            return null;
        });
        assertThat(edits).as("the seek click adds nothing").hasValue(1);
    }

    @Test
    void theEditCallbackMustNotBeNull() throws Exception {
        TimelineRuler ruler = buildRuler(transportAt120Bpm());

        assertThatNullPointerException().isThrownBy(() -> ruler.setOnTransportEdited(null));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Transport transportAt120Bpm() {
        Transport transport = new Transport();
        transport.setTempo(120.0);
        return transport;
    }

    private TimelineRuler buildRuler(Transport transport) throws Exception {
        return computeOnFx(() -> {
            TimelineRuler r = new TimelineRuler(transport);
            r.setSampleRate(PROJECT_RATE);
            r.setAutoScroll(false);
            r.setOnTransportEdited(edits::incrementAndGet);
            new Scene(r, 800, TimelineRuler.DEFAULT_HEIGHT);
            r.applyCss();
            r.layout();
            return r;
        });
    }

    private static Canvas canvasOf(TimelineRuler ruler) throws Exception {
        return computeOnFx(() -> (Canvas) ruler.getChildrenUnmodifiable().get(0));
    }

    private static MouseEvent mouse(javafx.event.EventType<MouseEvent> type, double x, double y, boolean shift) {
        boolean primaryDown = type != MouseEvent.MOUSE_RELEASED;
        return new MouseEvent(type, x, y, x, y, MouseButton.PRIMARY, 1,
                shift, false, false, false,
                primaryDown, false, false,
                false, false, false, null);
    }

    /** Runs {@code work} on the FX thread with capture-and-rethrow of failures. */
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
        assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("FX work completes").isTrue();
        if (thrown.get() instanceof Exception e) {
            throw e;
        }
        if (thrown.get() instanceof Error e) {
            throw e;
        }
        return result.get();
    }
}
