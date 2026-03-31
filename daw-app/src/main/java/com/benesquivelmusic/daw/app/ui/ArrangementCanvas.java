package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.automation.AutomationData;
import com.benesquivelmusic.daw.core.automation.AutomationLane;
import com.benesquivelmusic.daw.core.automation.AutomationParameter;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    static final Color TRIM_PREVIEW_COLOR = Color.web("#00E5FF", 0.8);

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
    private static final double TRIM_PREVIEW_LINE_WIDTH = 2.0;

    private final Canvas canvas;

    private List<Track> tracks = List.of();
    private double pixelsPerBeat = ArrangementNavigator.BASE_PIXELS_PER_BEAT;
    private double scrollXBeats;
    private double scrollYPixels;
    private double trackHeight = TrackHeightZoom.DEFAULT_TRACK_HEIGHT;
    private double playheadBeat = -1.0;
    private boolean autoScroll = true;
    private double trimPreviewBeat = -1.0;
    private int trimPreviewTrackIndex = -1;

    /**
     * Tracks which track IDs have their automation lane expanded.
     * Key: track ID, Value: the selected {@link AutomationParameter}.
     */
    private final Map<String, AutomationParameter> automationLaneVisibility = new HashMap<>();

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
        if (Double.compare(this.playheadBeat, beat) == 0) {
            return;
        }
        this.playheadBeat = beat;
        if (autoScroll && beat >= 0) {
            ensurePlayheadVisible();
        }
        redraw();
    }

    /**
     * Enables or disables automatic horizontal scrolling to keep the
     * playhead visible during playback.
     *
     * @param autoScroll {@code true} to enable auto-scroll
     */
    public void setAutoScroll(boolean autoScroll) {
        this.autoScroll = autoScroll;
    }

    /**
     * Forces a full redraw of the arrangement canvas.
     */
    public void refresh() {
        redraw();
    }

    /**
     * Sets the trim preview position for rendering a ghost line during
     * clip edge trimming. Pass a negative beat to hide the preview.
     *
     * @param beat       the preview beat position, or negative to hide
     * @param trackIndex the track lane index to render the preview in
     */
    void setTrimPreview(double beat, int trackIndex) {
        this.trimPreviewBeat = beat;
        this.trimPreviewTrackIndex = trackIndex;
    }

    // ── Automation lane visibility ─────────────────────────────────────────

    /**
     * Toggles the visibility of the automation lane for the given track.
     * When shown, the lane defaults to {@link AutomationParameter#VOLUME}.
     *
     * @param track the track whose automation lane to toggle
     */
    void toggleAutomationLane(Track track) {
        String id = track.getId();
        if (automationLaneVisibility.containsKey(id)) {
            automationLaneVisibility.remove(id);
        } else {
            automationLaneVisibility.put(id, AutomationParameter.VOLUME);
        }
        redraw();
    }

    /**
     * Returns whether the automation lane is visible for the given track.
     *
     * @param track the track to check
     * @return {@code true} if the automation lane is expanded
     */
    boolean isAutomationLaneVisible(Track track) {
        return automationLaneVisibility.containsKey(track.getId());
    }

    /**
     * Sets the automation parameter displayed in the given track's lane.
     *
     * @param track     the track
     * @param parameter the parameter to display
     */
    void setAutomationParameter(Track track, AutomationParameter parameter) {
        if (automationLaneVisibility.containsKey(track.getId())) {
            automationLaneVisibility.put(track.getId(), parameter);
            redraw();
        }
    }

    /**
     * Returns the currently selected automation parameter for the given
     * track's lane, or {@code null} if the lane is not visible.
     *
     * @param track the track to query
     * @return the selected parameter, or {@code null}
     */
    AutomationParameter getAutomationParameter(Track track) {
        return automationLaneVisibility.get(track.getId());
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

    double getPlayheadBeat() {
        return playheadBeat;
    }

    double getTrimPreviewBeat() {
        return trimPreviewBeat;
    }

    int getTrimPreviewTrackIndex() {
        return trimPreviewTrackIndex;
    }

    /**
     * Resolves the track index at the given Y pixel coordinate, accounting
     * for expanded automation lanes.
     *
     * @param y the Y pixel coordinate (in canvas space)
     * @return the track index, or {@code -1} if outside all track lanes
     */
    int trackIndexAtY(double y) {
        double adjustedY = y + scrollYPixels;
        double cumulative = 0;
        for (int i = 0; i < tracks.size(); i++) {
            double slotHeight = trackHeight;
            if (automationLaneVisibility.containsKey(tracks.get(i).getId())) {
                slotHeight += AutomationLaneRenderer.AUTOMATION_LANE_HEIGHT;
            }
            if (adjustedY < cumulative + slotHeight) {
                return i;
            }
            cumulative += slotHeight;
        }
        return -1;
    }

    /**
     * Returns {@code true} if the given Y coordinate falls within an
     * automation sub-lane (below the track lane proper) rather than in
     * the clip area of a track.
     *
     * @param y the Y pixel coordinate (in canvas space)
     * @return {@code true} if the coordinate is inside an automation lane
     */
    boolean isYInAutomationLane(double y) {
        double adjustedY = y + scrollYPixels;
        double cumulative = 0;
        for (int i = 0; i < tracks.size(); i++) {
            double autoHeight = automationLaneVisibility.containsKey(tracks.get(i).getId())
                    ? AutomationLaneRenderer.AUTOMATION_LANE_HEIGHT : 0;
            double slotHeight = trackHeight + autoHeight;
            if (adjustedY < cumulative + slotHeight) {
                // Within this track's slot — check if in the automation sub-lane
                return autoHeight > 0 && adjustedY >= cumulative + trackHeight;
            }
            cumulative += slotHeight;
        }
        return false;
    }

    /**
     * Returns the Y pixel coordinate of the automation sub-lane top for
     * the given track index (in canvas space, accounting for scroll).
     *
     * @param trackIndex the track index
     * @return the Y coordinate, or {@code -1} if no automation lane is visible
     */
    double automationLaneY(int trackIndex) {
        if (trackIndex < 0 || trackIndex >= tracks.size()) {
            return -1;
        }
        if (!automationLaneVisibility.containsKey(tracks.get(trackIndex).getId())) {
            return -1;
        }
        return computeLaneY(trackIndex) + trackHeight;
    }

    // ── Rendering ──────────────────────────────────────────────────────────

    /**
     * Computes the Y pixel offset for the given track index, accounting for
     * any expanded automation lanes above it.
     */
    private double computeLaneY(int trackIndex) {
        double y = 0;
        for (int i = 0; i < trackIndex; i++) {
            y += trackHeight;
            if (i < tracks.size() && automationLaneVisibility.containsKey(tracks.get(i).getId())) {
                y += AutomationLaneRenderer.AUTOMATION_LANE_HEIGHT;
            }
        }
        return y - scrollYPixels;
    }

    /**
     * Computes the total content height including automation lanes.
     */
    private double computeTotalContentHeight() {
        double total = 0;
        for (int i = 0; i < tracks.size(); i++) {
            total += trackHeight;
            if (automationLaneVisibility.containsKey(tracks.get(i).getId())) {
                total += AutomationLaneRenderer.AUTOMATION_LANE_HEIGHT;
            }
        }
        return total;
    }

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
        drawAutomationLanes(gc, w, h);
        drawTrimPreview(gc, w, h);
        drawPlayhead(gc, w, h);
    }

    private void drawTrackLanes(GraphicsContext gc, double canvasWidth, double canvasHeight) {
        for (int i = 0; i < tracks.size(); i++) {
            double y = computeLaneY(i);
            double effectiveHeight = trackHeight;
            if (automationLaneVisibility.containsKey(tracks.get(i).getId())) {
                effectiveHeight += AutomationLaneRenderer.AUTOMATION_LANE_HEIGHT;
            }
            if (y + effectiveHeight < 0) {
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
        double totalContentHeight = computeTotalContentHeight() - scrollYPixels;
        if (totalContentHeight < canvasHeight) {
            gc.setFill(LANE_COLOR_EVEN);
            gc.fillRect(0, totalContentHeight, canvasWidth, canvasHeight - totalContentHeight);
        }
    }

    private void drawClips(GraphicsContext gc, double canvasWidth, double canvasHeight) {
        for (int i = 0; i < tracks.size(); i++) {
            Track track = tracks.get(i);
            double laneY = computeLaneY(i);
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

    private void drawAutomationLanes(GraphicsContext gc, double canvasWidth, double canvasHeight) {
        for (int i = 0; i < tracks.size(); i++) {
            Track track = tracks.get(i);
            AutomationParameter param = automationLaneVisibility.get(track.getId());
            if (param == null) {
                continue;
            }

            double autoLaneY = computeLaneY(i) + trackHeight;
            double autoLaneHeight = AutomationLaneRenderer.AUTOMATION_LANE_HEIGHT;
            if (autoLaneY + autoLaneHeight < 0 || autoLaneY > canvasHeight) {
                continue;
            }

            AutomationData automationData = track.getAutomationData();
            AutomationLane lane = automationData.getOrCreateLane(param);
            AutomationLaneRenderer.draw(gc, lane, 0, autoLaneY, canvasWidth,
                    autoLaneHeight, pixelsPerBeat, scrollXBeats);
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

    private void drawTrimPreview(GraphicsContext gc, double canvasWidth, double canvasHeight) {
        if (trimPreviewBeat < 0 || trimPreviewTrackIndex < 0) {
            return;
        }
        double x = (trimPreviewBeat - scrollXBeats) * pixelsPerBeat;
        if (x < 0 || x > canvasWidth) {
            return;
        }
        double laneY = computeLaneY(trimPreviewTrackIndex);
        double laneBottom = laneY + trackHeight;
        if (laneBottom < 0 || laneY > canvasHeight) {
            return;
        }
        double drawY = Math.max(0, laneY + CLIP_INSET);
        double drawBottom = Math.min(canvasHeight, laneBottom - CLIP_INSET);
        if (drawBottom <= drawY) {
            return;
        }
        gc.setStroke(TRIM_PREVIEW_COLOR);
        gc.setLineWidth(TRIM_PREVIEW_LINE_WIDTH);
        gc.strokeLine(x, drawY, x, drawBottom);
    }

    private Color parseTrackColor(Track track) {
        String hex = track.getColor().getHexColor();
        try {
            return Color.web(hex);
        } catch (IllegalArgumentException e) {
            return Color.web("#3498DB");
        }
    }

    /**
     * Auto-scrolls the horizontal offset to keep the playhead in view.
     * Uses the same approach as {@link TimelineRuler#ensurePlayheadVisible()}.
     */
    private void ensurePlayheadVisible() {
        double viewWidthBeats = canvas.getWidth() / pixelsPerBeat;
        if (viewWidthBeats <= 0) {
            return;
        }
        if (playheadBeat < scrollXBeats) {
            scrollXBeats = playheadBeat;
        } else if (playheadBeat > scrollXBeats + viewWidthBeats * 0.9) {
            scrollXBeats = playheadBeat - viewWidthBeats * 0.1;
        }
    }
}
