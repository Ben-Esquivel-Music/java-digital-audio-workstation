package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.audio.ClipGainEnvelope;
import com.benesquivelmusic.daw.sdk.audio.CurveShape;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

import java.util.List;

/**
 * Renders a clip's per-clip gain envelope as a second polyline on top of
 * the clip's waveform, with circular breakpoint handles that can be
 * hit-tested for drag-to-add / click-to-delete interaction &mdash; the
 * same interaction model as {@link AutomationLaneRenderer}.
 *
 * <p>The renderer is stateless and intentionally consists of pure static
 * utility methods for coordinate conversion and hit-testing plus a single
 * {@link #draw} entry point that touches the {@link GraphicsContext}.
 * This split lets coordinate and hit-test logic be covered by unit tests
 * that do not require the JavaFX toolkit, which matters for headless CI.</p>
 *
 * <p>Breakpoints live in the clip's native frame domain
 * ({@code frameOffsetInClip}); conversion to canvas pixels requires the
 * session's {@code samplesPerBeat} (= sample rate &times; 60 / tempo) and
 * the clip's timeline start / source offset.</p>
 */
final class ClipGainEnvelopeRenderer {

    /** Radius of breakpoint handle circles in pixels. */
    static final double BREAKPOINT_RADIUS = 4.0;

    /** Hit-test tolerance for breakpoint selection (pixels). */
    static final double HIT_TOLERANCE = 8.0;

    /** Minimum dB displayed (mapped to the bottom of the clip). */
    static final double MIN_DB = -24.0;

    /** Maximum dB displayed (mapped to the top of the clip). */
    static final double MAX_DB = 12.0;

    /** Number of interpolation steps per segment for curved shapes. */
    private static final int CURVE_STEPS = 24;

    /** Vertical inset in pixels so breakpoints don't touch the clip edges. */
    private static final double INSET = 3.0;

    static final Color ENVELOPE_COLOR = Color.web("#FFD54F", 0.95);
    static final Color ZERO_DB_LINE_COLOR = Color.web("#FFD54F", 0.25);
    static final Color BREAKPOINT_FILL = Color.web("#FFD54F");
    static final Color BREAKPOINT_STROKE = Color.web("#ffffff", 0.95);

    private ClipGainEnvelopeRenderer() {
        // utility class
    }

    /**
     * Draws the gain envelope polyline and breakpoint handles on top of
     * the clip's body.
     *
     * @param gc                  the graphics context to draw on
     * @param envelope            the envelope to render
     * @param clipX               the clip's left edge in canvas pixels
     * @param clipY               the clip's top edge in canvas pixels
     * @param clipWidth           the clip's width in pixels
     * @param clipHeight          the clip's height in pixels
     * @param pixelsPerBeat       horizontal scale
     * @param scrollXBeats        horizontal scroll offset in beats
     * @param samplesPerBeat      session sample rate / tempo conversion
     * @param clipStartBeat       the clip's start position in beats
     * @param sourceOffsetBeats   the clip's source offset in beats
     */
    static void draw(GraphicsContext gc, ClipGainEnvelope envelope,
                     double clipX, double clipY,
                     double clipWidth, double clipHeight,
                     double pixelsPerBeat, double scrollXBeats,
                     double samplesPerBeat,
                     double clipStartBeat, double sourceOffsetBeats) {
        if (envelope == null || samplesPerBeat <= 0.0 || clipWidth <= 0.0) {
            return;
        }

        // Faint reference line at 0 dB.
        double zeroDbY = dbToY(0.0, clipY, clipHeight);
        gc.setStroke(ZERO_DB_LINE_COLOR);
        gc.setLineWidth(1.0);
        gc.strokeLine(clipX, zeroDbY, clipX + clipWidth, zeroDbY);

        List<ClipGainEnvelope.BreakpointDb> bps = envelope.breakpoints();
        double clipRightX = clipX + clipWidth;

        gc.setStroke(ENVELOPE_COLOR);
        gc.setLineWidth(1.75);

        // Horizontal extension from clip's left edge to the first breakpoint.
        var first = bps.getFirst();
        double firstX = clampToClip(frameToX(first.frameOffsetInClip(),
                        samplesPerBeat, pixelsPerBeat, scrollXBeats,
                        clipStartBeat, sourceOffsetBeats),
                clipX, clipRightX);
        double firstY = dbToY(first.dbGain(), clipY, clipHeight);
        if (firstX > clipX) {
            gc.strokeLine(clipX, firstY, firstX, firstY);
        }

        // Segments between breakpoints using the left-point's curve shape.
        for (int i = 0; i < bps.size() - 1; i++) {
            var left = bps.get(i);
            var right = bps.get(i + 1);
            double x1 = frameToX(left.frameOffsetInClip(), samplesPerBeat,
                    pixelsPerBeat, scrollXBeats, clipStartBeat, sourceOffsetBeats);
            double x2 = frameToX(right.frameOffsetInClip(), samplesPerBeat,
                    pixelsPerBeat, scrollXBeats, clipStartBeat, sourceOffsetBeats);
            double y1 = dbToY(left.dbGain(), clipY, clipHeight);
            double y2 = dbToY(right.dbGain(), clipY, clipHeight);

            if (left.curve() == CurveShape.LINEAR) {
                drawClippedLine(gc, x1, y1, x2, y2, clipX, clipRightX);
            } else {
                drawCurvedSegment(gc, x1, y1, x2, y2, left.curve(),
                        clipX, clipRightX);
            }
        }

        // Horizontal extension from the last breakpoint to the clip's right edge.
        var last = bps.getLast();
        double lastX = clampToClip(frameToX(last.frameOffsetInClip(),
                        samplesPerBeat, pixelsPerBeat, scrollXBeats,
                        clipStartBeat, sourceOffsetBeats),
                clipX, clipRightX);
        double lastY = dbToY(last.dbGain(), clipY, clipHeight);
        if (lastX < clipRightX) {
            gc.strokeLine(lastX, lastY, clipRightX, lastY);
        }

        // Breakpoint handles.
        for (var bp : bps) {
            double px = frameToX(bp.frameOffsetInClip(), samplesPerBeat,
                    pixelsPerBeat, scrollXBeats, clipStartBeat, sourceOffsetBeats);
            double py = dbToY(bp.dbGain(), clipY, clipHeight);
            if (px < clipX - BREAKPOINT_RADIUS || px > clipRightX + BREAKPOINT_RADIUS) {
                continue;
            }
            gc.setFill(BREAKPOINT_FILL);
            gc.fillOval(px - BREAKPOINT_RADIUS, py - BREAKPOINT_RADIUS,
                    BREAKPOINT_RADIUS * 2, BREAKPOINT_RADIUS * 2);
            gc.setStroke(BREAKPOINT_STROKE);
            gc.setLineWidth(1.0);
            gc.strokeOval(px - BREAKPOINT_RADIUS, py - BREAKPOINT_RADIUS,
                    BREAKPOINT_RADIUS * 2, BREAKPOINT_RADIUS * 2);
        }
    }

    // ── Coordinate conversions (pure, no JavaFX state) ─────────────────────

    /**
     * Converts a clip-local frame offset to an X pixel coordinate on the
     * arrangement canvas.
     */
    static double frameToX(long frameOffsetInClip, double samplesPerBeat,
                           double pixelsPerBeat, double scrollXBeats,
                           double clipStartBeat, double sourceOffsetBeats) {
        double beatInSource = frameOffsetInClip / samplesPerBeat;
        double timelineBeat = clipStartBeat + (beatInSource - sourceOffsetBeats);
        return (timelineBeat - scrollXBeats) * pixelsPerBeat;
    }

    /**
     * Converts an X pixel coordinate to the nearest non-negative clip-local
     * frame offset.
     */
    static long xToFrame(double x, double samplesPerBeat,
                         double pixelsPerBeat, double scrollXBeats,
                         double clipStartBeat, double sourceOffsetBeats) {
        double timelineBeat = x / pixelsPerBeat + scrollXBeats;
        double beatInSource = timelineBeat - clipStartBeat + sourceOffsetBeats;
        long frame = Math.round(beatInSource * samplesPerBeat);
        return Math.max(0L, frame);
    }

    /**
     * Converts a dB value to a Y pixel coordinate within the clip rect.
     * Values are clamped to {@code [MIN_DB, MAX_DB]}; {@code MAX_DB} sits
     * at the top inset and {@code MIN_DB} at the bottom inset.
     */
    static double dbToY(double db, double clipY, double clipHeight) {
        double usable = clipHeight - 2 * INSET;
        if (usable <= 0) {
            return clipY + clipHeight / 2.0;
        }
        double clamped = Math.clamp(db, MIN_DB, MAX_DB);
        double normalized = (clamped - MIN_DB) / (MAX_DB - MIN_DB); // 0 at MIN, 1 at MAX
        // Invert so higher dB appears higher on screen.
        return clipY + INSET + (1.0 - normalized) * usable;
    }

    /** Converts a Y pixel coordinate within the clip rect to a dB value. */
    static double yToDb(double y, double clipY, double clipHeight) {
        double usable = clipHeight - 2 * INSET;
        if (usable <= 0) {
            return 0.0;
        }
        double normalized = 1.0 - (y - clipY - INSET) / usable;
        double db = MIN_DB + normalized * (MAX_DB - MIN_DB);
        return Math.clamp(db, MIN_DB, MAX_DB);
    }

    /**
     * Hit-tests the breakpoints of an envelope against a pixel position.
     *
     * @return the index of the closest breakpoint within {@link #HIT_TOLERANCE}
     *         pixels, or {@code -1} if none
     */
    static int hitTestBreakpoint(ClipGainEnvelope envelope,
                                 double x, double y,
                                 double clipY, double clipHeight,
                                 double pixelsPerBeat, double scrollXBeats,
                                 double samplesPerBeat,
                                 double clipStartBeat, double sourceOffsetBeats) {
        if (envelope == null) {
            return -1;
        }
        int closest = -1;
        double bestDistSq = HIT_TOLERANCE * HIT_TOLERANCE;
        List<ClipGainEnvelope.BreakpointDb> bps = envelope.breakpoints();
        for (int i = 0; i < bps.size(); i++) {
            var bp = bps.get(i);
            double px = frameToX(bp.frameOffsetInClip(), samplesPerBeat,
                    pixelsPerBeat, scrollXBeats, clipStartBeat, sourceOffsetBeats);
            double py = dbToY(bp.dbGain(), clipY, clipHeight);
            double dx = x - px;
            double dy = y - py;
            double distSq = dx * dx + dy * dy;
            if (distSq <= bestDistSq) {
                bestDistSq = distSq;
                closest = i;
            }
        }
        return closest;
    }

    /**
     * Inserts a new breakpoint into the envelope at the given canvas pixel
     * position &mdash; the "drag-to-add" gesture in
     * {@link AutomationLaneRenderer}'s interaction model.
     *
     * @return a new envelope with the inserted breakpoint
     */
    static ClipGainEnvelope addBreakpointAt(ClipGainEnvelope envelope,
                                            double x, double y,
                                            double clipY, double clipHeight,
                                            double pixelsPerBeat, double scrollXBeats,
                                            double samplesPerBeat,
                                            double clipStartBeat, double sourceOffsetBeats,
                                            CurveShape curve) {
        long frame = xToFrame(x, samplesPerBeat, pixelsPerBeat, scrollXBeats,
                clipStartBeat, sourceOffsetBeats);
        double db = yToDb(y, clipY, clipHeight);
        CurveShape shape = (curve == null) ? CurveShape.LINEAR : curve;
        ClipGainEnvelope.BreakpointDb bp =
                new ClipGainEnvelope.BreakpointDb(frame, db, shape);
        // When seeding a brand-new envelope, derive the baseline from the
        // click's own dB rather than hard-coding 0 dB — otherwise a user who
        // adds the first breakpoint at, say, -12 dB would still see a
        // phantom 0 dB anchor at frame 0 and an unexpected gain jump.
        if (envelope == null) {
            return new ClipGainEnvelope(List.of(bp));
        }
        return envelope.withBreakpoint(bp);
    }

    // ── Internal drawing helpers ───────────────────────────────────────────

    private static void drawCurvedSegment(GraphicsContext gc,
                                          double x1, double y1,
                                          double x2, double y2,
                                          CurveShape curve,
                                          double clipLeftX, double clipRightX) {
        double prevX = x1;
        double prevY = y1;
        for (int step = 1; step <= CURVE_STEPS; step++) {
            double t = (double) step / CURVE_STEPS;
            double w = curve.weight(t);
            double cx = x1 + t * (x2 - x1);
            double cy = y1 + w * (y2 - y1);
            drawClippedLine(gc, prevX, prevY, cx, cy, clipLeftX, clipRightX);
            prevX = cx;
            prevY = cy;
        }
    }

    private static void drawClippedLine(GraphicsContext gc,
                                        double x1, double y1,
                                        double x2, double y2,
                                        double clipLeftX, double clipRightX) {
        // Trivial clip: drop segments entirely outside the clip rectangle.
        if ((x1 < clipLeftX && x2 < clipLeftX)
                || (x1 > clipRightX && x2 > clipRightX)) {
            return;
        }
        double a = Math.max(clipLeftX, Math.min(x1, x2));
        double b = Math.min(clipRightX, Math.max(x1, x2));
        if (a == b) {
            return;
        }
        // Interpolate Y at the clipped endpoints.
        double yAtA = interpY(x1, y1, x2, y2, a);
        double yAtB = interpY(x1, y1, x2, y2, b);
        gc.strokeLine(a, yAtA, b, yAtB);
    }

    private static double interpY(double x1, double y1, double x2, double y2, double x) {
        if (x1 == x2) {
            return y1;
        }
        double t = (x - x1) / (x2 - x1);
        return y1 + t * (y2 - y1);
    }

    private static double clampToClip(double x, double clipLeftX, double clipRightX) {
        if (x < clipLeftX) return clipLeftX;
        if (x > clipRightX) return clipRightX;
        return x;
    }
}
