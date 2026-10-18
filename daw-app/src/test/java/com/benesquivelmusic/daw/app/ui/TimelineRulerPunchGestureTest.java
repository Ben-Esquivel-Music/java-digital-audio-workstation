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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 328 (book §5.6) — the ruler's punch gestures and draw truth.
 *
 * <p>Geometry: 40 px per beat, no scroll, 120 BPM, ruler height 32 px. The
 * ruler's sample rate is the PROJECT rate (96 kHz), so beat 2 = 1 s =
 * 96 000 frames and beat 6 = 288 000 frames. Loop gestures own the upper
 * half ({@code y = 8}), punch gestures the lower half ({@code y = 28}).</p>
 *
 * <p>The record-across-punch proof (audio only inside the region, cosine
 * crossfaded edges) lives in {@code RecordingPipelineTest} (daw-core:
 * {@code shouldApplySampleAccuratePunchBoundariesWithinOneBlock},
 * {@code shouldApplyCosineCrossfadeAtPunchBoundaries},
 * {@code shouldAutoPunchAcrossMultiplePassesWhileArmed}) and is not
 * duplicated here.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class TimelineRulerPunchGestureTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final double PROJECT_RATE = 96_000.0;
    private static final double UPPER_HALF_Y = 8.0;
    private static final double LOWER_HALF_Y = 28.0;
    private static final long BEAT_2 = 96_000L;
    private static final long BEAT_6 = 288_000L;

    @Test
    void drawnPunchHandlesLandOnTheRegionsBeatBoundsAtTheProjectRate() throws Exception {
        Transport transport = transportAt120Bpm();
        transport.setPunchRegion(PunchRegion.enabled(BEAT_2, BEAT_6));
        TimelineRuler ruler = buildRuler(transport);

        computeOnFx(() -> { ruler.redraw(); return null; });

        // (beats − scroll) × pixelsPerBeat — no 44.1 kHz offset (which would
        // draw the in-handle at 96000/44100 × 2 × 40 ≈ 174 px).
        assertThat(ruler.lastDrawnPunchInX()).isCloseTo(2.0 * 40.0, within(1e-6));
        assertThat(ruler.lastDrawnPunchOutX()).isCloseTo(6.0 * 40.0, within(1e-6));
    }

    @Test
    void drawnPunchHandlesFollowScrollAndZoom() throws Exception {
        Transport transport = transportAt120Bpm();
        transport.setPunchRegion(PunchRegion.enabled(BEAT_2, BEAT_6));
        TimelineRuler ruler = buildRuler(transport);

        computeOnFx(() -> {
            ruler.setScrollOffsetBeats(1.0);
            ruler.setPixelsPerBeat(20.0);
            return null;
        });

        assertThat(ruler.lastDrawnPunchInX()).isCloseTo((2.0 - 1.0) * 20.0, within(1e-6));
        assertThat(ruler.lastDrawnPunchOutX()).isCloseTo((6.0 - 1.0) * 20.0, within(1e-6));
    }

    @Test
    void shiftDragInTheLowerHalfDefinesAnEnabledPunchRegionOfTheDraggedBeatsAtTheRulersRate() throws Exception {
        Transport transport = transportAt120Bpm();
        TimelineRuler ruler = buildRuler(transport);
        Canvas canvas = canvasOf(ruler);

        computeOnFx(() -> {
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_PRESSED, 80.0, LOWER_HALF_Y, true));
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_DRAGGED, 240.0, LOWER_HALF_Y, true));
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_RELEASED, 240.0, LOWER_HALF_Y, true));
            return null;
        });

        assertThat(transport.getPunchRegion()).isEqualTo(PunchRegion.enabled(BEAT_2, BEAT_6));
        assertThat(transport.isLoopEnabled()).as("the lower half never touches the loop").isFalse();
    }

    @Test
    void shiftDragInTheLowerHalfDraggedLeftwardsStillDefinesAnOrderedRegion() throws Exception {
        Transport transport = transportAt120Bpm();
        TimelineRuler ruler = buildRuler(transport);
        Canvas canvas = canvasOf(ruler);

        computeOnFx(() -> {
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_PRESSED, 240.0, LOWER_HALF_Y, true));
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_DRAGGED, 80.0, LOWER_HALF_Y, true));
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_RELEASED, 80.0, LOWER_HALF_Y, true));
            return null;
        });

        assertThat(transport.getPunchRegion()).isEqualTo(PunchRegion.enabled(BEAT_2, BEAT_6));
    }

    @Test
    void shiftDragInTheLowerHalfSnapsToTheGridWhenSnapIsOn() throws Exception {
        Transport transport = transportAt120Bpm();
        TimelineRuler ruler = buildRuler(transport);
        Canvas canvas = canvasOf(ruler);

        computeOnFx(() -> {
            ruler.setSnapEnabled(true);
            ruler.setGridResolution(GridResolution.QUARTER);
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_PRESSED, 87.0, LOWER_HALF_Y, true));   // beat 2.175 → 2
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_DRAGGED, 250.0, LOWER_HALF_Y, true));  // beat 6.25  → 6
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_RELEASED, 250.0, LOWER_HALF_Y, true));
            return null;
        });

        assertThat(transport.getPunchRegion()).isEqualTo(PunchRegion.enabled(BEAT_2, BEAT_6));
    }

    @Test
    void shiftDragShorterThanTheMinimumPunchLengthLeavesTheTransportUnchanged() throws Exception {
        Transport transport = transportAt120Bpm();
        TimelineRuler ruler = buildRuler(transport);
        Canvas canvas = canvasOf(ruler);

        computeOnFx(() -> {
            // 0.5 px = 0.0125 beat = 6.25 ms = 600 frames < 960 (2 × 5 ms @ 96 kHz)
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_PRESSED, 80.0, LOWER_HALF_Y, true));
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_DRAGGED, 80.5, LOWER_HALF_Y, true));
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_RELEASED, 80.5, LOWER_HALF_Y, true));
            return null;
        });

        assertThat(transport.getPunchRegion()).as("no zero/too-short region is ever installed").isNull();
        assertThat(transport.isLoopEnabled()).isFalse();
    }

    @Test
    void shiftDragInTheUpperHalfStillDefinesALoopAndLeavesPunchAlone() throws Exception {
        Transport transport = transportAt120Bpm();
        TimelineRuler ruler = buildRuler(transport);
        Canvas canvas = canvasOf(ruler);

        computeOnFx(() -> {
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_PRESSED, 80.0, UPPER_HALF_Y, true));
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_DRAGGED, 240.0, UPPER_HALF_Y, true));
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_RELEASED, 240.0, UPPER_HALF_Y, true));
            return null;
        });

        assertThat(transport.isLoopEnabled()).isTrue();
        assertThat(transport.getLoopStartInBeats()).isEqualTo(2.0);
        assertThat(transport.getLoopEndInBeats()).isEqualTo(6.0);
        assertThat(transport.getPunchRegion()).isNull();
    }

    @Test
    void draggingThePunchOutHandleMovesTheOutPoint() throws Exception {
        Transport transport = transportAt120Bpm();
        transport.setPunchRegion(new PunchRegion(BEAT_2, BEAT_6, false));
        TimelineRuler ruler = buildRuler(transport);
        Canvas canvas = canvasOf(ruler);
        List<Double> seeks = new CopyOnWriteArrayList<>();
        ruler.addSeekListener(seeks::add);

        computeOnFx(() -> {
            ruler.redraw();
            double outX = ruler.lastDrawnPunchOutX();
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_PRESSED, outX + 3.0, LOWER_HALF_Y, false)); // inside the hit zone
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_DRAGGED, 320.0, LOWER_HALF_Y, false));       // beat 8
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_RELEASED, 320.0, LOWER_HALF_Y, false));
            return null;
        });

        assertThat(transport.getPunchRegion())
                .as("the out-point follows the drag; the in-point and the enabled flag are kept")
                .isEqualTo(new PunchRegion(BEAT_2, 384_000L, false));
        assertThat(seeks).as("a handle press is not a click-to-seek").isEmpty();
    }

    @Test
    void draggingThePunchInHandleMovesTheInPoint() throws Exception {
        Transport transport = transportAt120Bpm();
        transport.setPunchRegion(PunchRegion.enabled(BEAT_2, BEAT_6));
        TimelineRuler ruler = buildRuler(transport);
        Canvas canvas = canvasOf(ruler);
        List<Double> seeks = new CopyOnWriteArrayList<>();
        ruler.addSeekListener(seeks::add);

        computeOnFx(() -> {
            ruler.redraw();
            double inX = ruler.lastDrawnPunchInX();
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_PRESSED, inX - 3.0, LOWER_HALF_Y, false));
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_DRAGGED, 40.0, LOWER_HALF_Y, false));        // beat 1
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_RELEASED, 40.0, LOWER_HALF_Y, false));
            return null;
        });

        assertThat(transport.getPunchRegion()).isEqualTo(PunchRegion.enabled(48_000L, BEAT_6));
        assertThat(seeks).isEmpty();
    }

    @Test
    void draggingAPunchHandleAcrossTheOtherIsRefusedAndTheRegionStays() throws Exception {
        Transport transport = transportAt120Bpm();
        transport.setPunchRegion(PunchRegion.enabled(BEAT_2, BEAT_6));
        TimelineRuler ruler = buildRuler(transport);
        Canvas canvas = canvasOf(ruler);

        computeOnFx(() -> {
            ruler.redraw();
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_PRESSED, 80.0, LOWER_HALF_Y, false));   // in-handle
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_DRAGGED, 300.0, LOWER_HALF_Y, false));  // past the out-point
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_RELEASED, 300.0, LOWER_HALF_Y, false));
            return null;
        });

        assertThat(transport.getPunchRegion()).isEqualTo(PunchRegion.enabled(BEAT_2, BEAT_6));
    }

    @Test
    void aPlainClickAwayFromTheHandlesInTheLowerHalfStillSeeks() throws Exception {
        Transport transport = transportAt120Bpm();
        transport.setPunchRegion(PunchRegion.enabled(BEAT_2, BEAT_6));
        TimelineRuler ruler = buildRuler(transport);
        Canvas canvas = canvasOf(ruler);
        List<Double> seeks = new CopyOnWriteArrayList<>();
        ruler.addSeekListener(seeks::add);

        computeOnFx(() -> {
            ruler.redraw();
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_PRESSED, 160.0, LOWER_HALF_Y, false)); // beat 4, between the handles
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_RELEASED, 160.0, LOWER_HALF_Y, false));
            return null;
        });

        assertThat(seeks).containsExactly(4.0);
        assertThat(transport.getPunchRegion()).isEqualTo(PunchRegion.enabled(BEAT_2, BEAT_6));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Transport transportAt120Bpm() {
        Transport transport = new Transport();
        transport.setTempo(120.0);
        return transport;
    }

    private static TimelineRuler buildRuler(Transport transport) throws Exception {
        return computeOnFx(() -> {
            TimelineRuler r = new TimelineRuler(transport);
            r.setSampleRate(PROJECT_RATE);
            r.setAutoScroll(false);
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
