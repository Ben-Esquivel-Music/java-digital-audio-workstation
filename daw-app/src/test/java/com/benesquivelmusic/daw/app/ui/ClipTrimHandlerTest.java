package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import javafx.application.Platform;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class ClipTrimHandlerTest {

    private static boolean toolkitAvailable;

    @BeforeAll
    static void initToolkit() throws Exception {
        toolkitAvailable = false;
        CountDownLatch startupLatch = new CountDownLatch(1);
        try {
            Platform.startup(startupLatch::countDown);
            if (!startupLatch.await(5, TimeUnit.SECONDS)) {
                return;
            }
        } catch (IllegalStateException ignored) {
        } catch (UnsupportedOperationException ignored) {
            return;
        }
        CountDownLatch verifyLatch = new CountDownLatch(1);
        Thread verifier = new Thread(() -> {
            try {
                Platform.runLater(verifyLatch::countDown);
            } catch (Exception ignored) {
            }
        });
        verifier.setDaemon(true);
        verifier.start();
        verifier.join(3000);
        toolkitAvailable = verifyLatch.await(3, TimeUnit.SECONDS);
    }

    private UndoManager undoManager;
    private List<Track> tracks;
    private double pixelsPerBeat;
    private double scrollXBeats;
    private double scrollYPixels;
    private double trackHeight;
    private boolean snapEnabled;
    private GridResolution gridResolution;
    private int beatsPerBar;
    private int refreshCount;

    @BeforeEach
    void setUp() {
        undoManager = new UndoManager();
        tracks = new ArrayList<>();
        pixelsPerBeat = 40.0;
        scrollXBeats = 0.0;
        scrollYPixels = 0.0;
        trackHeight = 80.0;
        snapEnabled = false;
        gridResolution = GridResolution.QUARTER;
        beatsPerBar = 4;
        refreshCount = 0;
    }

    private ClipTrimHandler createHandler() {
        return new ClipTrimHandler(new ClipTrimHandler.Host() {
            @Override public double pixelsPerBeat() { return pixelsPerBeat; }
            @Override public double scrollXBeats() { return scrollXBeats; }
            @Override public double scrollYPixels() { return scrollYPixels; }
            @Override public double trackHeight() { return trackHeight; }
            @Override public List<Track> tracks() { return tracks; }
            @Override public UndoManager undoManager() { return undoManager; }
            @Override public boolean snapEnabled() { return snapEnabled; }
            @Override public GridResolution gridResolution() { return gridResolution; }
            @Override public int beatsPerBar() { return beatsPerBar; }
            @Override public void refreshCanvas() { refreshCount++; }
        });
    }

    // ── Edge detection ───────────────────────────────────────────────────────

    @Test
    void shouldDetectLeftEdge() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 4.0, 8.0, null);
        track.addClip(clip);
        tracks.add(track);

        ClipTrimHandler handler = createHandler();

        // Left edge at beat 4.0 = pixel 160 at 40px/beat
        // y=40 is in track 0
        ClipTrimHandler.TrimEdge edge = handler.detectEdge(160.0, 40.0);
        assertThat(edge).isEqualTo(ClipTrimHandler.TrimEdge.LEFT);
    }

    @Test
    void shouldDetectRightEdge() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 4.0, 8.0, null);
        track.addClip(clip);
        tracks.add(track);

        ClipTrimHandler handler = createHandler();

        // Right edge at beat 12.0 = pixel 480 at 40px/beat
        ClipTrimHandler.TrimEdge edge = handler.detectEdge(480.0, 40.0);
        assertThat(edge).isEqualTo(ClipTrimHandler.TrimEdge.RIGHT);
    }

    @Test
    void shouldReturnNullWhenNotNearEdge() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 4.0, 8.0, null);
        track.addClip(clip);
        tracks.add(track);

        ClipTrimHandler handler = createHandler();

        // Middle of clip at beat 8.0 = pixel 320
        ClipTrimHandler.TrimEdge edge = handler.detectEdge(320.0, 40.0);
        assertThat(edge).isNull();
    }

    @Test
    void shouldReturnNullWhenOutsideAllTracks() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        track.addClip(new AudioClip("Vocal", 4.0, 8.0, null));
        tracks.add(track);

        ClipTrimHandler handler = createHandler();

        // y=200 is outside track bounds (1 track × 80px)
        assertThat(handler.detectEdge(160.0, 200.0)).isNull();
    }

    @Test
    void shouldFindClipAtLeftEdge() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 4.0, 8.0, null);
        track.addClip(clip);
        tracks.add(track);

        ClipTrimHandler handler = createHandler();

        AudioClip found = handler.clipAtEdge(160.0, 40.0);
        assertThat(found).isSameAs(clip);
    }

    @Test
    void shouldFindClipAtRightEdge() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 4.0, 8.0, null);
        track.addClip(clip);
        tracks.add(track);

        ClipTrimHandler handler = createHandler();

        AudioClip found = handler.clipAtEdge(480.0, 40.0);
        assertThat(found).isSameAs(clip);
    }

    // ── Left edge trim ───────────────────────────────────────────────────────

    @Test
    void shouldTrimLeftEdgeForward() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 4.0, 8.0, null);
        clip.setSourceOffsetBeats(0.0);
        track.addClip(clip);
        tracks.add(track);

        ClipTrimHandler handler = createHandler();

        handler.beginTrim(clip, ClipTrimHandler.TrimEdge.LEFT);
        assertThat(handler.isTrimming()).isTrue();
        assertThat(handler.getActiveEdge()).isEqualTo(ClipTrimHandler.TrimEdge.LEFT);

        // Drag left edge to beat 6.0 (pixel 240)
        handler.completeTrim(240.0);

        assertThat(clip.getStartBeat()).isEqualTo(6.0);
        assertThat(clip.getDurationBeats()).isEqualTo(6.0);
        assertThat(clip.getSourceOffsetBeats()).isEqualTo(2.0);
        assertThat(handler.isTrimming()).isFalse();
    }

    @Test
    void shouldTrimRightEdgeInward() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 4.0, 8.0, null);
        track.addClip(clip);
        tracks.add(track);

        ClipTrimHandler handler = createHandler();

        handler.beginTrim(clip, ClipTrimHandler.TrimEdge.RIGHT);

        // Drag right edge to beat 10.0 (pixel 400)
        handler.completeTrim(400.0);

        assertThat(clip.getStartBeat()).isEqualTo(4.0);
        assertThat(clip.getDurationBeats()).isEqualTo(6.0);
        assertThat(clip.getEndBeat()).isEqualTo(10.0);
    }

    // ── Boundary clamping ────────────────────────────────────────────────────

    @Test
    void shouldClampLeftEdgeToPreventNegativeSourceOffset() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 4.0, 8.0, null);
        clip.setSourceOffsetBeats(0.0); // No room to extend left
        track.addClip(clip);
        tracks.add(track);

        ClipTrimHandler handler = createHandler();

        handler.beginTrim(clip, ClipTrimHandler.TrimEdge.LEFT);
        // Try to drag left edge to beat 2.0 (pixel 80) — should be clamped to 4.0
        handler.completeTrim(80.0);

        // Source offset can't go below 0, so start stays at 4.0
        assertThat(clip.getStartBeat()).isEqualTo(4.0);
        assertThat(clip.getSourceOffsetBeats()).isEqualTo(0.0);
    }

    @Test
    void shouldClampLeftEdgeToPreventNegativeStart() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 2.0, 8.0, null);
        clip.setSourceOffsetBeats(4.0); // Room to extend left
        track.addClip(clip);
        tracks.add(track);

        ClipTrimHandler handler = createHandler();

        handler.beginTrim(clip, ClipTrimHandler.TrimEdge.LEFT);
        // Try to drag far left to beat -5.0 (pixel -200) — should clamp to 0.0
        handler.completeTrim(-200.0);

        assertThat(clip.getStartBeat()).isEqualTo(0.0);
        assertThat(clip.getDurationBeats()).isEqualTo(10.0);
        assertThat(clip.getSourceOffsetBeats()).isEqualTo(2.0);
    }

    @Test
    void shouldClampRightEdgeToOriginalEnd() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 4.0, 8.0, null);
        track.addClip(clip);
        tracks.add(track);

        ClipTrimHandler handler = createHandler();

        handler.beginTrim(clip, ClipTrimHandler.TrimEdge.RIGHT);
        // Try to extend right edge beyond the original end (beat 12.0)
        handler.completeTrim(600.0); // beat 15.0

        // Should be clamped to the original end
        assertThat(clip.getEndBeat()).isEqualTo(12.0);
        assertThat(clip.getDurationBeats()).isEqualTo(8.0);
    }

    @Test
    void shouldEnforceMinimumClipDuration() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 4.0, 8.0, null);
        track.addClip(clip);
        tracks.add(track);

        ClipTrimHandler handler = createHandler();

        handler.beginTrim(clip, ClipTrimHandler.TrimEdge.RIGHT);
        // Try to drag right edge all the way to the start
        handler.completeTrim(160.0); // beat 4.0

        // Should maintain at least MIN_CLIP_DURATION_BEATS
        assertThat(clip.getDurationBeats()).isGreaterThanOrEqualTo(
                ClipTrimHandler.MIN_CLIP_DURATION_BEATS);
    }

    // ── Snap to grid ─────────────────────────────────────────────────────────

    @Test
    void shouldSnapTrimToGrid() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 4.0, 8.0, null);
        track.addClip(clip);
        tracks.add(track);
        snapEnabled = true;
        gridResolution = GridResolution.QUARTER;

        ClipTrimHandler handler = createHandler();

        handler.beginTrim(clip, ClipTrimHandler.TrimEdge.RIGHT);
        // Drag right edge to beat 9.3 (pixel 372) — should snap to 9.0
        handler.completeTrim(372.0);

        assertThat(clip.getEndBeat()).isCloseTo(9.0, offset(0.01));
    }

    // ── Undo integration ─────────────────────────────────────────────────────

    @Test
    void shouldRegisterUndoableAction() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 4.0, 8.0, null);
        track.addClip(clip);
        tracks.add(track);

        ClipTrimHandler handler = createHandler();

        handler.beginTrim(clip, ClipTrimHandler.TrimEdge.LEFT);
        handler.completeTrim(240.0); // beat 6.0

        assertThat(clip.getStartBeat()).isEqualTo(6.0);

        undoManager.undo();

        assertThat(clip.getStartBeat()).isEqualTo(4.0);
        assertThat(clip.getDurationBeats()).isEqualTo(8.0);
        assertThat(clip.getSourceOffsetBeats()).isEqualTo(0.0);
    }

    @Test
    void shouldNotRegisterActionWhenNoChange() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 4.0, 8.0, null);
        track.addClip(clip);
        tracks.add(track);

        ClipTrimHandler handler = createHandler();

        handler.beginTrim(clip, ClipTrimHandler.TrimEdge.LEFT);
        // Release at the same position (beat 4.0 = pixel 160)
        handler.completeTrim(160.0);

        assertThat(undoManager.canUndo()).isFalse();
    }

    // ── Cancel ───────────────────────────────────────────────────────────────

    @Test
    void shouldCancelTrimAndRestoreOriginalState() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 4.0, 8.0, null);
        track.addClip(clip);
        tracks.add(track);

        ClipTrimHandler handler = createHandler();

        handler.beginTrim(clip, ClipTrimHandler.TrimEdge.LEFT);
        handler.updateTrim(240.0, 0); // Preview at beat 6.0
        handler.cancelTrim();

        assertThat(clip.getStartBeat()).isEqualTo(4.0);
        assertThat(clip.getDurationBeats()).isEqualTo(8.0);
        assertThat(handler.isTrimming()).isFalse();
    }

    // ── Preview state ────────────────────────────────────────────────────────

    @Test
    void shouldUpdatePreviewDuringDrag() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 4.0, 8.0, null);
        track.addClip(clip);
        tracks.add(track);

        ClipTrimHandler handler = createHandler();

        handler.beginTrim(clip, ClipTrimHandler.TrimEdge.RIGHT);
        handler.updateTrim(400.0, 0); // beat 10.0

        assertThat(handler.getPreviewBeat()).isCloseTo(10.0, offset(0.01));
        assertThat(handler.getPreviewTrackIndex()).isEqualTo(0);
        assertThat(refreshCount).isGreaterThan(0);
    }

    @Test
    void shouldClearPreviewAfterCompletion() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 4.0, 8.0, null);
        track.addClip(clip);
        tracks.add(track);

        ClipTrimHandler handler = createHandler();

        handler.beginTrim(clip, ClipTrimHandler.TrimEdge.RIGHT);
        handler.completeTrim(400.0);

        assertThat(handler.getPreviewBeat()).isLessThan(0);
        assertThat(handler.getPreviewTrackIndex()).isEqualTo(-1);
    }

    // ── Edge threshold constant ──────────────────────────────────────────────

    @Test
    void edgeThresholdShouldBeSixPixels() {
        assertThat(ClipTrimHandler.EDGE_THRESHOLD_PIXELS).isEqualTo(6.0);
    }

    // ── Left edge extend backward (previously trimmed clip) ──────────────────

    @Test
    void shouldExtendLeftEdgeBackwardWhenSourceOffsetAllows() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 6.0, 6.0, null);
        clip.setSourceOffsetBeats(2.0); // Was trimmed 2 beats from original start
        track.addClip(clip);
        tracks.add(track);

        ClipTrimHandler handler = createHandler();

        handler.beginTrim(clip, ClipTrimHandler.TrimEdge.LEFT);
        // Drag left to beat 4.0 (pixel 160) — extending the clip back
        handler.completeTrim(160.0);

        assertThat(clip.getStartBeat()).isEqualTo(4.0);
        assertThat(clip.getDurationBeats()).isEqualTo(8.0);
        assertThat(clip.getSourceOffsetBeats()).isEqualTo(0.0);
    }
}
