package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.FadeCurveType;
import com.benesquivelmusic.daw.core.midi.MidiClip;
import com.benesquivelmusic.daw.core.track.Track;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * Renders clip rectangles and all clip-level overlays on the arrangement
 * canvas: the clip body and border, the selection highlight, fade-in /
 * fade-out curve overlays and handles, and the clip name label.
 *
 * <p>Orchestrates {@link ClipWaveformRenderer} for audio clips and
 * {@link ClipMidiPreviewRenderer} for MIDI clips so callers only need to
 * invoke one entry point per clip.</p>
 *
 * <p>Stateless utility: positioning, transform, and selection state are
 * passed in explicitly so each call is independently testable.</p>
 */
final class ClipOverlayRenderer {

    static final Color CLIP_BORDER_COLOR = Color.web("#ffffff", 0.3);
    static final Color CLIP_LABEL_COLOR = Color.web("#ffffff", 0.9);
    static final Color FADE_HANDLE_COLOR = Color.web("#ffffff", 0.85);
    static final Color FADE_HANDLE_FILL_COLOR = Color.web("#ffffff", 0.3);
    static final Color CLIP_SELECTED_BORDER_COLOR = Color.web("#42A5F5", 0.9);
    static final Color CLIP_SELECTED_OVERLAY_COLOR = Color.web("#42A5F5", 0.25);

    static final double CLIP_CORNER_RADIUS = 4.0;
    static final double CLIP_INSET = 2.0;

    private static final Font CLIP_LABEL_FONT = Font.font("SansSerif", 10);
    private static final double CLIP_LABEL_PADDING = 4.0;
    private static final double CLIP_OPACITY = 0.75;
    private static final double FADE_OVERLAY_OPACITY = 0.3;
    private static final double FADE_HANDLE_SIZE = ClipFadeHandler.HANDLE_SIZE_PIXELS;
    private static final int FADE_CURVE_SEGMENTS = 20;

    private ClipOverlayRenderer() {
    }

    /**
     * Draws the full audio clip — body, border, selection highlight,
     * fade overlays, waveform, fade handles and name label — at the
     * clip's position in the arrangement lane.
     */
    static void drawAudioClip(GraphicsContext gc, AudioClip clip, Color trackColor,
                              double laneY, double trackHeight,
                              double pixelsPerBeat, double scrollXBeats,
                              double canvasWidth, double canvasHeight,
                              SelectionModel selectionModel) {
        drawAudioClip(gc, clip, trackColor, laneY, trackHeight,
                pixelsPerBeat, scrollXBeats, canvasWidth, canvasHeight,
                selectionModel, 0.0);
    }

    /**
     * Extended entry point that additionally renders the clip's per-clip
     * gain envelope (if present) on top of the waveform. Pass
     * {@code samplesPerBeat <= 0} to skip the envelope overlay.
     *
     * @param samplesPerBeat session sample rate divided by beats-per-second
     *                       (sample rate &times; 60 / tempo); used to
     *                       convert breakpoint frame offsets to pixels
     */
    static void drawAudioClip(GraphicsContext gc, AudioClip clip, Color trackColor,
                              double laneY, double trackHeight,
                              double pixelsPerBeat, double scrollXBeats,
                              double canvasWidth, double canvasHeight,
                              SelectionModel selectionModel,
                              double samplesPerBeat) {
        double clipX = (clip.getStartBeat() - scrollXBeats) * pixelsPerBeat;
        double clipWidth = clip.getDurationBeats() * pixelsPerBeat;

        if (clipX + clipWidth < 0 || clipX > canvasWidth) {
            return;
        }

        double clipY = laneY + CLIP_INSET;
        double clipHeight = trackHeight - 2 * CLIP_INSET;

        fillClipBody(gc, trackColor, clipX, clipY, clipWidth, clipHeight);

        if (selectionModel != null && selectionModel.isClipSelected(clip)) {
            drawSelectionOverlay(gc, clipX, clipY, clipWidth, clipHeight);
        }

        if (clip.getFadeInBeats() > 0) {
            double fadeWidth = Math.max(0.0,
                    Math.min(clip.getFadeInBeats() * pixelsPerBeat, clipWidth));
            if (fadeWidth > 0.0) {
                drawFadeInOverlay(gc, clip.getFadeInCurveType(),
                        clipX, clipY, fadeWidth, clipHeight);
            }
        }
        if (clip.getFadeOutBeats() > 0) {
            double fadeWidth = Math.max(0.0,
                    Math.min(clip.getFadeOutBeats() * pixelsPerBeat, clipWidth));
            if (fadeWidth > 0.0) {
                drawFadeOutOverlay(gc, clip.getFadeOutCurveType(),
                        clipX + clipWidth - fadeWidth, clipY, fadeWidth, clipHeight);
            }
        }

        ClipWaveformRenderer.draw(gc, clip.getAudioData(),
                clipX, clipY, clipWidth, clipHeight, canvasWidth);

        if (samplesPerBeat > 0.0) {
            clip.gainEnvelope().ifPresent(env ->
                    ClipGainEnvelopeRenderer.draw(gc, env,
                            clipX, clipY, clipWidth, clipHeight,
                            pixelsPerBeat, scrollXBeats, samplesPerBeat,
                            clip.getStartBeat(), clip.getSourceOffsetBeats()));
        }

        drawFadeHandles(gc, clip, clipX, clipY, clipWidth, pixelsPerBeat);

        drawClipLabel(gc, clip.getName(), clipX, clipY, clipWidth, clipHeight);
    }

    /**
     * Draws the full MIDI clip — body, border, selection highlight, mini
     * piano-roll notes and name label — at the clip's position.
     */
    static void drawMidiClip(GraphicsContext gc, Track track, MidiClip midiClip,
                             Color trackColor,
                             double laneY, double trackHeight,
                             double pixelsPerBeat, double scrollXBeats,
                             double canvasWidth, double canvasHeight,
                             SelectionModel selectionModel) {
        var notes = midiClip.getNotes();
        ClipMidiPreviewRenderer.PreviewBounds bounds =
                ClipMidiPreviewRenderer.computeBounds(notes);
        if (bounds == null) {
            return;
        }

        double clipX = (bounds.startBeat() - scrollXBeats) * pixelsPerBeat;
        double clipWidth = bounds.durationBeats() * pixelsPerBeat;

        if (clipX + clipWidth < 0 || clipX > canvasWidth) {
            return;
        }

        double clipY = laneY + CLIP_INSET;
        double clipHeight = trackHeight - 2 * CLIP_INSET;

        fillClipBody(gc, trackColor, clipX, clipY, clipWidth, clipHeight);

        if (selectionModel != null && selectionModel.isMidiClipSelected(midiClip)) {
            drawSelectionOverlay(gc, clipX, clipY, clipWidth, clipHeight);
        }

        ClipMidiPreviewRenderer.drawNotes(gc, notes, bounds,
                clipX, clipY, clipWidth, clipHeight, pixelsPerBeat);

        drawClipLabel(gc, track.getName(), clipX, clipY, clipWidth, clipHeight);
    }

    /**
     * Draws a trim-preview ghost line at the given beat, clamped inside
     * the track lane identified by {@code laneY}.
     */
    static void drawTrimPreview(GraphicsContext gc,
                                double trimPreviewBeat, double scrollXBeats,
                                double pixelsPerBeat,
                                double laneY, double trackHeight,
                                double canvasWidth, double canvasHeight) {
        double x = (trimPreviewBeat - scrollXBeats) * pixelsPerBeat;
        if (x < 0 || x > canvasWidth) {
            return;
        }
        double laneBottom = laneY + trackHeight;
        if (laneBottom < 0 || laneY > canvasHeight) {
            return;
        }
        double drawY = Math.max(0, laneY + CLIP_INSET);
        double drawBottom = Math.min(canvasHeight, laneBottom - CLIP_INSET);
        if (drawBottom <= drawY) {
            return;
        }
        gc.setStroke(TransportOverlayRenderer.TRIM_PREVIEW_COLOR);
        gc.setLineWidth(TransportOverlayRenderer.TRIM_PREVIEW_LINE_WIDTH);
        gc.strokeLine(x, drawY, x, drawBottom);
    }

    /**
     * Draws the slip-edit ghost overlay — a translucent rectangle offset
     * horizontally by {@code beatDelta} from the clip's actual on-timeline
     * position, plus a red tint when the drag has hit the source-window
     * edge.
     *
     * <p>The ghost is clipped to the clip's original timeline bounds so it
     * reads as "content sliding inside the clip window" rather than the
     * clip itself moving.</p>
     *
     * <p>Story 139 — {@code docs/user-stories/139-slip-edit-within-clip.md}.</p>
     *
     * @param gc            the graphics context
     * @param clipStartBeat the clip's on-timeline start beat
     * @param durationBeats the clip's on-timeline duration in beats
     * @param beatDelta     the in-progress slip delta (positive = content
     *                      dragged right on the timeline)
     * @param hitEdge       {@code true} to flash the ghost red
     */
    static void drawSlipGhost(GraphicsContext gc,
                              double clipStartBeat, double durationBeats,
                              double beatDelta, boolean hitEdge,
                              double scrollXBeats, double pixelsPerBeat,
                              double laneY, double trackHeight,
                              double canvasWidth, double canvasHeight) {
        double clipX = (clipStartBeat - scrollXBeats) * pixelsPerBeat;
        double clipWidth = durationBeats * pixelsPerBeat;
        if (clipX + clipWidth < 0 || clipX > canvasWidth) {
            return;
        }
        double laneBottom = laneY + trackHeight;
        if (laneBottom < 0 || laneY > canvasHeight) {
            return;
        }

        double clipY = laneY + CLIP_INSET;
        double clipHeight = trackHeight - 2 * CLIP_INSET;
        double ghostX = clipX + beatDelta * pixelsPerBeat;

        // Clip the ghost to the clip's original bounds so the overlay reads
        // as content shifting inside a fixed window.
        gc.save();
        gc.beginPath();
        gc.rect(clipX, clipY, clipWidth, clipHeight);
        gc.clip();

        Color ghostFill = hitEdge
                ? Color.web("#ff5252", 0.45)
                : Color.web("#ffffff", 0.25);
        gc.setFill(ghostFill);
        gc.fillRoundRect(ghostX, clipY, clipWidth, clipHeight,
                CLIP_CORNER_RADIUS, CLIP_CORNER_RADIUS);

        Color ghostBorder = hitEdge
                ? Color.web("#ff1744", 0.9)
                : Color.web("#ffffff", 0.7);
        gc.setStroke(ghostBorder);
        gc.setLineWidth(hitEdge ? 2.0 : 1.5);
        gc.strokeRoundRect(ghostX, clipY, clipWidth, clipHeight,
                CLIP_CORNER_RADIUS, CLIP_CORNER_RADIUS);

        gc.restore();
    }

    private static void fillClipBody(GraphicsContext gc, Color trackColor,
                                     double clipX, double clipY,
                                     double clipWidth, double clipHeight) {
        Color fillColor = trackColor.deriveColor(0, 1.0, 1.0, CLIP_OPACITY);
        gc.setFill(fillColor);
        gc.fillRoundRect(clipX, clipY, clipWidth, clipHeight,
                CLIP_CORNER_RADIUS, CLIP_CORNER_RADIUS);
        gc.setStroke(CLIP_BORDER_COLOR);
        gc.setLineWidth(1.0);
        gc.strokeRoundRect(clipX, clipY, clipWidth, clipHeight,
                CLIP_CORNER_RADIUS, CLIP_CORNER_RADIUS);
    }

    private static void drawSelectionOverlay(GraphicsContext gc,
                                             double clipX, double clipY,
                                             double clipWidth, double clipHeight) {
        gc.setFill(CLIP_SELECTED_OVERLAY_COLOR);
        gc.fillRoundRect(clipX, clipY, clipWidth, clipHeight,
                CLIP_CORNER_RADIUS, CLIP_CORNER_RADIUS);
        gc.setStroke(CLIP_SELECTED_BORDER_COLOR);
        gc.setLineWidth(2.0);
        gc.strokeRoundRect(clipX, clipY, clipWidth, clipHeight,
                CLIP_CORNER_RADIUS, CLIP_CORNER_RADIUS);
    }

    private static void drawFadeInOverlay(GraphicsContext gc, FadeCurveType curveType,
                                          double fadeX, double clipY,
                                          double fadeWidth, double clipHeight) {
        gc.setFill(Color.web("#000000", FADE_OVERLAY_OPACITY));
        int n = FADE_CURVE_SEGMENTS;
        double[] xs = new double[n + 3];
        double[] ys = new double[n + 3];
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            double gain = fadeCurveGain(curveType, t);
            xs[i] = fadeX + t * fadeWidth;
            ys[i] = clipY + clipHeight * (1.0 - gain);
        }
        xs[n + 1] = fadeX;
        ys[n + 1] = clipY + clipHeight;
        xs[n + 2] = fadeX;
        ys[n + 2] = clipY + clipHeight;
        gc.fillPolygon(xs, ys, n + 3);

        gc.setStroke(FADE_HANDLE_COLOR);
        gc.setLineWidth(1.5);
        gc.beginPath();
        gc.moveTo(fadeX, clipY + clipHeight);
        for (int i = 1; i <= n; i++) {
            double t = (double) i / n;
            double gain = fadeCurveGain(curveType, t);
            gc.lineTo(fadeX + t * fadeWidth, clipY + clipHeight * (1.0 - gain));
        }
        gc.stroke();
    }

    private static void drawFadeOutOverlay(GraphicsContext gc, FadeCurveType curveType,
                                           double fadeX, double clipY,
                                           double fadeWidth, double clipHeight) {
        gc.setFill(Color.web("#000000", FADE_OVERLAY_OPACITY));
        int n = FADE_CURVE_SEGMENTS;
        double[] xs = new double[n + 3];
        double[] ys = new double[n + 3];
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            double gain = fadeCurveGain(curveType, 1.0 - t);
            xs[i] = fadeX + t * fadeWidth;
            ys[i] = clipY + clipHeight * (1.0 - gain);
        }
        xs[n + 1] = fadeX + fadeWidth;
        ys[n + 1] = clipY;
        xs[n + 2] = fadeX + fadeWidth;
        ys[n + 2] = clipY;
        gc.fillPolygon(xs, ys, n + 3);

        gc.setStroke(FADE_HANDLE_COLOR);
        gc.setLineWidth(1.5);
        gc.beginPath();
        gc.moveTo(fadeX, clipY);
        for (int i = 1; i <= n; i++) {
            double t = (double) i / n;
            double gain = fadeCurveGain(curveType, 1.0 - t);
            gc.lineTo(fadeX + t * fadeWidth, clipY + clipHeight * (1.0 - gain));
        }
        gc.stroke();
    }

    private static double fadeCurveGain(FadeCurveType curveType, double t) {
        return switch (curveType) {
            case LINEAR -> t;
            case EQUAL_POWER -> Math.sin(t * Math.PI / 2.0);
            case S_CURVE -> t * t * (3.0 - 2.0 * t);
        };
    }

    private static void drawFadeHandles(GraphicsContext gc, AudioClip clip,
                                        double clipX, double clipY,
                                        double clipWidth, double pixelsPerBeat) {
        double handleH = FADE_HANDLE_SIZE;
        double handleW = FADE_HANDLE_SIZE;

        double fadeInWidth = Math.min(clip.getFadeInBeats() * pixelsPerBeat, clipWidth);
        double fadeInHandleX = clipX + fadeInWidth;
        drawFadeHandle(gc, fadeInHandleX, clipY, handleW, handleH);

        double fadeOutWidth = Math.min(clip.getFadeOutBeats() * pixelsPerBeat, clipWidth);
        double fadeOutHandleX = clipX + clipWidth - fadeOutWidth;
        drawFadeHandle(gc, fadeOutHandleX, clipY, handleW, handleH);
    }

    private static void drawFadeHandle(GraphicsContext gc,
                                       double handleX, double clipY,
                                       double handleW, double handleH) {
        double[] xs = {handleX, handleX - handleW / 2.0, handleX + handleW / 2.0};
        double[] ys = {clipY + handleH, clipY, clipY};
        gc.setFill(FADE_HANDLE_FILL_COLOR);
        gc.fillPolygon(xs, ys, 3);
        gc.setStroke(FADE_HANDLE_COLOR);
        gc.setLineWidth(1.0);
        gc.strokePolygon(xs, ys, 3);
    }

    private static void drawClipLabel(GraphicsContext gc, String name,
                                      double clipX, double clipY,
                                      double clipWidth, double clipHeight) {
        if (clipWidth < 20) {
            return;
        }
        gc.setFont(CLIP_LABEL_FONT);
        gc.setFill(CLIP_LABEL_COLOR);
        gc.setTextAlign(TextAlignment.LEFT);

        gc.save();
        gc.beginPath();
        gc.rect(clipX, clipY, clipWidth, clipHeight);
        gc.clip();
        gc.fillText(name, clipX + CLIP_LABEL_PADDING, clipY + 12);
        gc.restore();
    }
}
