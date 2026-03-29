package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.midi.MidiClip;
import com.benesquivelmusic.daw.core.midi.MidiNoteData;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.List;

/**
 * Renders horizontal track lanes with audio and MIDI clip rectangles
 * for the arrangement view.
 *
 * <p>Each track occupies a horizontal lane whose height is determined by
 * the current {@link TrackHeightZoom}. Audio clips are rendered as
 * colored rectangles positioned at their {@code startBeat} with width
 * proportional to {@code durationBeats}. A miniature waveform overview
 * is drawn inside each audio clip using the clip's {@code audioData}
 * buffer. MIDI clips are rendered with a simplified piano-roll note
 * preview. Clip names appear as overlay labels.</p>
 *
 * <p>Horizontal and vertical scrolling are controlled via the canvas's
 * scroll and zoom parameters and can be coordinated with higher-level
 * navigation components by the caller as needed.</p>
 */
public final class ArrangementCanvas extends Pane {

    static final Color LANE_COLOR_EVEN = Color.web("#1c1c2e");
    static final Color LANE_COLOR_ODD = Color.web("#22223a");
    static final Color LANE_SEPARATOR_COLOR = Color.web("#333355", 0.5);
    static final Color CLIP_BORDER_COLOR = Color.web("#ffffff", 0.3);
    static final Color CLIP_LABEL_COLOR = Color.web("#ffffff", 0.9);
    static final Color WAVEFORM_COLOR = Color.web("#ffffff", 0.5);
    static final Color MIDI_NOTE_COLOR = Color.web("#ffffff", 0.7);
    static final Color PLAYHEAD_COLOR = Color.web("#ff5555");

    private static final Font CLIP_LABEL_FONT = Font.font("SansSerif", 10);
    private static final double CLIP_CORNER_RADIUS = 4.0;
    private static final double CLIP_INSET = 2.0;
    private static final double CLIP_LABEL_PADDING = 4.0;
    private static final double CLIP_OPACITY = 0.75;
    private static final double FADE_OVERLAY_OPACITY = 0.3;
    private static final int WAVEFORM_MIN_WIDTH = 4;
    private static final double MIDI_NOTE_HEIGHT_FRACTION = 0.08;

    /**
     * Beats per grid column — MIDI note columns are 1/16 notes (0.25 beats)
     * at 4/4. Shared with {@link EditorView#BEATS_PER_COLUMN}.
     */
    static final double BEATS_PER_COLUMN = EditorView.BEATS_PER_COLUMN;
    private static final double PLAYHEAD_WIDTH = 2.0;

    private final Canvas canvas;

    private List<Track> tracks = List.of();
    private double pixelsPerBeat = ArrangementNavigator.BASE_PIXELS_PER_BEAT;
    private double scrollXBeats;
    private double scrollYPixels;
    private double trackHeight = TrackHeightZoom.DEFAULT_TRACK_HEIGHT;
    private double playheadBeat = -1.0;

    /**
     * Creates an empty arrangement canvas.
     */
    public ArrangementCanvas() {
        this.canvas = new Canvas();
        getChildren().add(canvas);

        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());

        widthProperty().addListener((obs, oldV, newV) -> redraw());
        heightProperty().addListener((obs, oldV, newV) -> redraw());
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Updates the track list and redraws. The list is not copied — the
     * caller must ensure the reference remains valid.
     *
     * @param tracks the current project tracks
     */
    public void setTracks(List<Track> tracks) {
        this.tracks = tracks == null ? List.of() : tracks;
        redraw();
    }

    /**
     * Sets the horizontal scale (pixels per beat) and redraws.
     *
     * @param pixelsPerBeat pixels per beat at the current zoom level
     */
    public void setPixelsPerBeat(double pixelsPerBeat) {
        if (pixelsPerBeat <= 0) {
            return;
        }
        this.pixelsPerBeat = pixelsPerBeat;
        redraw();
    }

    /**
     * Sets the horizontal scroll offset in beats and redraws.
     *
     * @param scrollXBeats the horizontal scroll offset
     */
    public void setScrollXBeats(double scrollXBeats) {
        this.scrollXBeats = Math.max(0.0, scrollXBeats);
        redraw();
    }

    /**
     * Sets the vertical scroll offset in pixels and redraws.
     *
     * @param scrollYPixels the vertical scroll offset
     */
    public void setScrollYPixels(double scrollYPixels) {
        this.scrollYPixels = Math.max(0.0, scrollYPixels);
        redraw();
    }

    /**
     * Sets the track lane height in pixels and redraws.
     *
     * @param trackHeight the track lane height
     */
    public void setTrackHeight(double trackHeight) {
        this.trackHeight = Math.max(TrackHeightZoom.MIN_TRACK_HEIGHT, trackHeight);
        redraw();
    }

    /**
     * Sets the playhead position in beats and redraws.
     *
     * @param beat the playhead position, or a negative value to hide it
     */
    public void setPlayheadBeat(double beat) {
        this.playheadBeat = beat;
        redraw();
    }

    /**
     * Forces a full redraw of the arrangement canvas.
     */
    public void refresh() {
        redraw();
    }

    // ── Getters (for testing) ──────────────────────────────────────────────

    double getPixelsPerBeat() {
        return pixelsPerBeat;
    }

    double getScrollXBeats() {
        return scrollXBeats;
    }

    double getScrollYPixels() {
        return scrollYPixels;
    }

    double getTrackHeight() {
        return trackHeight;
    }

    // ── Rendering ──────────────────────────────────────────────────────────

    private void redraw() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, w, h);

        drawTrackLanes(gc, w, h);
        drawClips(gc, w, h);
        drawPlayhead(gc, w, h);
    }

    private void drawTrackLanes(GraphicsContext gc, double canvasWidth, double canvasHeight) {
        for (int i = 0; i < tracks.size(); i++) {
            double y = i * trackHeight - scrollYPixels;
            if (y + trackHeight < 0) {
                continue;
            }
            if (y > canvasHeight) {
                break;
            }

            gc.setFill(i % 2 == 0 ? LANE_COLOR_EVEN : LANE_COLOR_ODD);
            gc.fillRect(0, y, canvasWidth, trackHeight);

            gc.setStroke(LANE_SEPARATOR_COLOR);
            gc.setLineWidth(1.0);
            gc.strokeLine(0, y + trackHeight, canvasWidth, y + trackHeight);
        }

        // Fill remaining area below tracks
        double totalTrackHeight = tracks.size() * trackHeight - scrollYPixels;
        if (totalTrackHeight < canvasHeight) {
            gc.setFill(LANE_COLOR_EVEN);
            gc.fillRect(0, totalTrackHeight, canvasWidth, canvasHeight - totalTrackHeight);
        }
    }

    private void drawClips(GraphicsContext gc, double canvasWidth, double canvasHeight) {
        for (int i = 0; i < tracks.size(); i++) {
            Track track = tracks.get(i);
            double laneY = i * trackHeight - scrollYPixels;
            if (laneY + trackHeight < 0 || laneY > canvasHeight) {
                continue;
            }

            Color trackColor = parseTrackColor(track);

            // Draw audio clips
            for (AudioClip clip : track.getClips()) {
                drawAudioClip(gc, clip, trackColor, laneY, canvasWidth, canvasHeight);
            }

            // Draw MIDI clip if the track has MIDI notes
            MidiClip midiClip = track.getMidiClip();
            if (!midiClip.isEmpty() && (track.getType() == TrackType.MIDI)) {
                drawMidiClip(gc, track, midiClip, trackColor, laneY, canvasWidth, canvasHeight);
            }
        }
    }

    private void drawAudioClip(GraphicsContext gc, AudioClip clip, Color trackColor,
                                double laneY, double canvasWidth, double canvasHeight) {
        double clipX = (clip.getStartBeat() - scrollXBeats) * pixelsPerBeat;
        double clipWidth = clip.getDurationBeats() * pixelsPerBeat;

        // Cull clips entirely outside the viewport
        if (clipX + clipWidth < 0 || clipX > canvasWidth) {
            return;
        }

        double clipY = laneY + CLIP_INSET;
        double clipHeight = trackHeight - 2 * CLIP_INSET;

        // Clip body
        Color fillColor = trackColor.deriveColor(0, 1.0, 1.0, CLIP_OPACITY);
        gc.setFill(fillColor);
        gc.fillRoundRect(clipX, clipY, clipWidth, clipHeight,
                CLIP_CORNER_RADIUS, CLIP_CORNER_RADIUS);

        // Clip border
        gc.setStroke(CLIP_BORDER_COLOR);
        gc.setLineWidth(1.0);
        gc.strokeRoundRect(clipX, clipY, clipWidth, clipHeight,
                CLIP_CORNER_RADIUS, CLIP_CORNER_RADIUS);

        // Fade-in overlay (triangle)
        if (clip.getFadeInBeats() > 0) {
            double fadeWidth = clip.getFadeInBeats() * pixelsPerBeat;
            // Clamp fade width so the overlay stays within the clip bounds
            fadeWidth = Math.max(0.0, Math.min(fadeWidth, clipWidth));
            if (fadeWidth > 0.0) {
                gc.setFill(Color.web("#000000", FADE_OVERLAY_OPACITY));
                gc.fillPolygon(
                        new double[]{clipX, clipX + fadeWidth, clipX},
                        new double[]{clipY, clipY, clipY + clipHeight},
                        3);
            }
        }

        // Fade-out overlay (triangle)
        if (clip.getFadeOutBeats() > 0) {
            double fadeWidth = clip.getFadeOutBeats() * pixelsPerBeat;
            // Clamp fade width so the overlay stays within the clip bounds
            fadeWidth = Math.max(0.0, Math.min(fadeWidth, clipWidth));
            if (fadeWidth > 0.0) {
                double fadeX = clipX + clipWidth - fadeWidth;
                gc.setFill(Color.web("#000000", FADE_OVERLAY_OPACITY));
                gc.fillPolygon(
                        new double[]{fadeX, clipX + clipWidth, clipX + clipWidth},
                        new double[]{clipY, clipY, clipY + clipHeight},
                        3);
            }
        }

        // Waveform overview
        drawWaveform(gc, clip, clipX, clipY, clipWidth, clipHeight);

        // Clip name label
        drawClipLabel(gc, clip.getName(), clipX, clipY, clipWidth, clipHeight);
    }

    private void drawWaveform(GraphicsContext gc, AudioClip clip,
                               double clipX, double clipY,
                               double clipWidth, double clipHeight) {
        float[][] audioData = clip.getAudioData();
        if (audioData == null || audioData.length == 0 || audioData[0].length == 0) {
            return;
        }
        // Compute total pixel width using long/double math to avoid overflow,
        // then clamp to the int range for iteration.
        long totalPixelWidthLong = (long) Math.floor(clipWidth);
        if (totalPixelWidthLong < WAVEFORM_MIN_WIDTH) {
            return;
        }
        if (totalPixelWidthLong > Integer.MAX_VALUE) {
            totalPixelWidthLong = Integer.MAX_VALUE;
        }
        int totalPixelWidth = (int) totalPixelWidthLong;

        // Clamp rendering to the visible portion of the clip
        double canvasWidth = canvas.getWidth();
        double rawVisibleStart = -clipX;
        double rawVisibleEnd = canvasWidth - clipX;

        long visibleStartLong = (long) Math.floor(rawVisibleStart);
        long visibleEndLong = (long) Math.floor(rawVisibleEnd);

        if (visibleEndLong <= 0) {
            return;
        }

        // Clamp visible range to [0, totalPixelWidthLong]
        if (visibleStartLong < 0) {
            visibleStartLong = 0;
        }
        if (visibleEndLong > totalPixelWidthLong) {
            visibleEndLong = totalPixelWidthLong;
        }
        if (visibleStartLong >= visibleEndLong) {
            return;
        }

        // Finally, clamp to int range
        int visibleStart = (int) Math.min(visibleStartLong, (long) Integer.MAX_VALUE);
        int visibleEnd = (int) Math.min(visibleEndLong, (long) Integer.MAX_VALUE);
        float[] channel = audioData[0];
        int totalSamples = channel.length;
        double centerY = clipY + clipHeight / 2.0;
        double halfHeight = (clipHeight - 8.0) / 2.0;

        gc.setStroke(WAVEFORM_COLOR);
        gc.setLineWidth(1.0);

        for (int px = visibleStart; px < visibleEnd; px++) {
            int sampleStart = (int) ((long) px * totalSamples / totalPixelWidth);
            int sampleEnd = (int) ((long) (px + 1) * totalSamples / totalPixelWidth);
            sampleEnd = Math.min(sampleEnd, totalSamples);

            if (sampleStart >= sampleEnd) {
                continue;
            }
            float min = channel[sampleStart];
            float max = channel[sampleStart];
            for (int s = sampleStart + 1; s < sampleEnd; s++) {
                float val = channel[s];
                if (val < min) {
                    min = val;
                }
                if (val > max) {
                    max = val;
                }
            }

            double x = clipX + px;
            double y1 = centerY - max * halfHeight;
            double y2 = centerY - min * halfHeight;
            gc.strokeLine(x, y1, x, y2);
        }
    }

    private void drawMidiClip(GraphicsContext gc, Track track, MidiClip midiClip,
                               Color trackColor, double laneY,
                               double canvasWidth, double canvasHeight) {
        List<MidiNoteData> notes = midiClip.getNotes();
        if (notes.isEmpty()) {
            return;
        }

        // Determine the clip bounds from notes
        int minColumn = Integer.MAX_VALUE;
        int maxEndColumn = 0;
        int minNote = MidiNoteData.MAX_NOTE_NUMBER;
        int maxNote = 0;
        for (MidiNoteData note : notes) {
            if (note.startColumn() < minColumn) {
                minColumn = note.startColumn();
            }
            if (note.endColumn() > maxEndColumn) {
                maxEndColumn = note.endColumn();
            }
            if (note.noteNumber() < minNote) {
                minNote = note.noteNumber();
            }
            if (note.noteNumber() > maxNote) {
                maxNote = note.noteNumber();
            }
        }

        double clipStartBeat = minColumn * BEATS_PER_COLUMN;
        double clipDurationBeats = (maxEndColumn - minColumn) * BEATS_PER_COLUMN;
        if (clipDurationBeats <= 0) {
            clipDurationBeats = BEATS_PER_COLUMN;
        }

        double clipX = (clipStartBeat - scrollXBeats) * pixelsPerBeat;
        double clipWidth = clipDurationBeats * pixelsPerBeat;

        if (clipX + clipWidth < 0 || clipX > canvasWidth) {
            return;
        }

        double clipY = laneY + CLIP_INSET;
        double clipHeight = trackHeight - 2 * CLIP_INSET;

        // Clip body
        Color fillColor = trackColor.deriveColor(0, 1.0, 1.0, CLIP_OPACITY);
        gc.setFill(fillColor);
        gc.fillRoundRect(clipX, clipY, clipWidth, clipHeight,
                CLIP_CORNER_RADIUS, CLIP_CORNER_RADIUS);

        // Clip border
        gc.setStroke(CLIP_BORDER_COLOR);
        gc.setLineWidth(1.0);
        gc.strokeRoundRect(clipX, clipY, clipWidth, clipHeight,
                CLIP_CORNER_RADIUS, CLIP_CORNER_RADIUS);

        // Draw mini piano-roll notes
        int noteRange = maxNote - minNote + 1;
        if (noteRange < 1) {
            noteRange = 1;
        }
        double noteAreaY = clipY + 14;
        double noteAreaHeight = clipHeight - 18;
        if (noteAreaHeight < 4) {
            noteAreaHeight = 4;
        }
        double noteHeight = Math.max(2.0, Math.min(noteAreaHeight / noteRange,
                clipHeight * MIDI_NOTE_HEIGHT_FRACTION));

        gc.setFill(MIDI_NOTE_COLOR);
        for (MidiNoteData note : notes) {
            double nx = clipX + (note.startColumn() - minColumn) * BEATS_PER_COLUMN * pixelsPerBeat;
            double nw = note.durationColumns() * BEATS_PER_COLUMN * pixelsPerBeat;
            double pitchFraction = noteRange > 1
                    ? (double) (maxNote - note.noteNumber()) / (noteRange - 1)
                    : 0.5;
            double ny = noteAreaY + pitchFraction * (noteAreaHeight - noteHeight);
            gc.fillRect(nx, ny, Math.max(1, nw), noteHeight);
        }

        // Clip name label
        drawClipLabel(gc, track.getName(), clipX, clipY, clipWidth, clipHeight);
    }

    private void drawClipLabel(GraphicsContext gc, String name,
                                double clipX, double clipY,
                                double clipWidth, double clipHeight) {
        if (clipWidth < 20) {
            return;
        }
        gc.setFont(CLIP_LABEL_FONT);
        gc.setFill(CLIP_LABEL_COLOR);
        gc.setTextAlign(TextAlignment.LEFT);

        // Clip the text rendering to the clip bounds
        gc.save();
        gc.beginPath();
        gc.rect(clipX, clipY, clipWidth, clipHeight);
        gc.clip();
        gc.fillText(name, clipX + CLIP_LABEL_PADDING, clipY + 12);
        gc.restore();
    }

    private void drawPlayhead(GraphicsContext gc, double canvasWidth, double canvasHeight) {
        if (playheadBeat < 0) {
            return;
        }
        double x = (playheadBeat - scrollXBeats) * pixelsPerBeat;
        if (x < 0 || x > canvasWidth) {
            return;
        }
        gc.setFill(PLAYHEAD_COLOR);
        gc.fillRect(x - PLAYHEAD_WIDTH / 2.0, 0, PLAYHEAD_WIDTH, canvasHeight);
    }

    private Color parseTrackColor(Track track) {
        String hex = track.getColor().getHexColor();
        try {
            return Color.web(hex);
        } catch (IllegalArgumentException e) {
            return Color.web("#3498DB");
        }
    }
}
