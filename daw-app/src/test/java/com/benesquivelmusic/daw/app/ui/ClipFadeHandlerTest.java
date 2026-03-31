package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.FadeCurveType;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class ClipFadeHandlerTest {
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

    private ClipFadeHandler createHandler() {
        return new ClipFadeHandler(new ClipFadeHandler.Host() {
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

    // ── Handle detection ─────────────────────────────────────────────────────

    @Test
    void shouldDetectFadeInHandle() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 4.0, 8.0, null);
        clip.setFadeInBeats(2.0);
        track.addClip(clip);
        tracks.add(track);

        ClipFadeHandler handler = createHandler();

        // Fade-in handle at beat 6.0 = clipX + fadeInWidth
        // clipX = (4.0 - 0) * 40 = 160, fadeInWidth = 2.0 * 40 = 80
        // handleX = 240, y near top of clip (clipY = 0 + 2 = 2)
        ClipFadeHandler.HandleHit hit = handler.hitTestHandle(240.0, 5.0);
        assertThat(hit).isNotNull();
        assertThat(hit.handle()).isEqualTo(ClipFadeHandler.FadeHandle.FADE_IN);
        assertThat(hit.clip()).isSameAs(clip);
    }

    @Test
    void shouldDetectFadeOutHandle() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 4.0, 8.0, null);
        clip.setFadeOutBeats(2.0);
        track.addClip(clip);
        tracks.add(track);

        ClipFadeHandler handler = createHandler();

        // Fade-out handle: clipX + clipWidth - fadeOutWidth
        // clipX = 160, clipWidth = 320, fadeOutWidth = 80
        // handleOutX = 160 + 320 - 80 = 400, y near top
        ClipFadeHandler.HandleHit hit = handler.hitTestHandle(400.0, 5.0);
        assertThat(hit).isNotNull();
        assertThat(hit.handle()).isEqualTo(ClipFadeHandler.FadeHandle.FADE_OUT);
        assertThat(hit.clip()).isSameAs(clip);
    }

    @Test
    void shouldDetectFadeInHandleAtZeroFade() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 4.0, 8.0, null);
        // fadeInBeats = 0.0 (default), handle is at clip start
        track.addClip(clip);
        tracks.add(track);

        ClipFadeHandler handler = createHandler();

        // handleX = clipX + 0 = 160
        ClipFadeHandler.HandleHit hit = handler.hitTestHandle(160.0, 5.0);
        assertThat(hit).isNotNull();
        assertThat(hit.handle()).isEqualTo(ClipFadeHandler.FadeHandle.FADE_IN);
    }

    @Test
    void shouldReturnNullWhenNotNearHandle() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 4.0, 8.0, null);
        clip.setFadeInBeats(2.0);
        track.addClip(clip);
        tracks.add(track);

        ClipFadeHandler handler = createHandler();

        // Middle of clip, far from handles
        assertThat(handler.hitTestHandle(320.0, 40.0)).isNull();
    }

    @Test
    void shouldReturnNullWhenOutsideAllTracks() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        track.addClip(new AudioClip("Vocal", 4.0, 8.0, null));
        tracks.add(track);

        ClipFadeHandler handler = createHandler();

        // y=200 is outside track bounds
        assertThat(handler.hitTestHandle(160.0, 200.0)).isNull();
    }

    // ── Fade-in drag ─────────────────────────────────────────────────────────

    @Test
    void shouldDragFadeInHandle() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 4.0, 8.0, null);
        track.addClip(clip);
        tracks.add(track);

        ClipFadeHandler handler = createHandler();

        handler.beginFade(clip, ClipFadeHandler.FadeHandle.FADE_IN);
        assertThat(handler.isFading()).isTrue();
        assertThat(handler.getActiveHandle()).isEqualTo(ClipFadeHandler.FadeHandle.FADE_IN);

        // Drag to beat 6.0 (pixel 240), setting fadeIn to 2.0 beats
        handler.completeFade(240.0);

        assertThat(clip.getFadeInBeats()).isCloseTo(2.0, offset(0.01));
        assertThat(handler.isFading()).isFalse();
    }

    @Test
    void shouldDragFadeOutHandle() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 4.0, 8.0, null);
        track.addClip(clip);
        tracks.add(track);

        ClipFadeHandler handler = createHandler();

        handler.beginFade(clip, ClipFadeHandler.FadeHandle.FADE_OUT);

        // Drag to beat 10.0 (pixel 400), setting fadeOut to 2.0 beats
        // endBeat = 12.0, so fadeOut = 12.0 - 10.0 = 2.0
        handler.completeFade(400.0);

        assertThat(clip.getFadeOutBeats()).isCloseTo(2.0, offset(0.01));
    }

    // ── Crossing prevention ──────────────────────────────────────────────────

    @Test
    void shouldPreventFadeInFromCrossingFadeOut() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 0.0, 8.0, null);
        clip.setFadeOutBeats(3.0);
        track.addClip(clip);
        tracks.add(track);

        ClipFadeHandler handler = createHandler();

        handler.beginFade(clip, ClipFadeHandler.FadeHandle.FADE_IN);
        // Try to set fadeIn to 7.0 beats (beyond maxFadeIn = 8.0 - 3.0 = 5.0)
        handler.completeFade(280.0); // beat 7.0

        assertThat(clip.getFadeInBeats()).isCloseTo(5.0, offset(0.01));
        assertThat(clip.getFadeOutBeats()).isCloseTo(3.0, offset(0.01));
    }

    @Test
    void shouldPreventFadeOutFromCrossingFadeIn() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 0.0, 8.0, null);
        clip.setFadeInBeats(3.0);
        track.addClip(clip);
        tracks.add(track);

        ClipFadeHandler handler = createHandler();

        handler.beginFade(clip, ClipFadeHandler.FadeHandle.FADE_OUT);
        // Try to set fadeOut to 7.0 beats (beyond maxFadeOut = 8.0 - 3.0 = 5.0)
        // endBeat = 8.0, beat = 8.0 - 7.0 = 1.0 → pixel 40
        handler.completeFade(40.0);

        assertThat(clip.getFadeOutBeats()).isCloseTo(5.0, offset(0.01));
        assertThat(clip.getFadeInBeats()).isCloseTo(3.0, offset(0.01));
    }

    @Test
    void shouldClampFadeInToZero() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 4.0, 8.0, null);
        clip.setFadeInBeats(2.0);
        track.addClip(clip);
        tracks.add(track);

        ClipFadeHandler handler = createHandler();

        handler.beginFade(clip, ClipFadeHandler.FadeHandle.FADE_IN);
        // Drag before clip start (negative fade) → should clamp to 0
        handler.completeFade(80.0); // beat 2.0, before clip start at 4.0

        assertThat(clip.getFadeInBeats()).isCloseTo(0.0, offset(0.01));
    }

    @Test
    void shouldClampFadeOutToZero() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 4.0, 8.0, null);
        clip.setFadeOutBeats(2.0);
        track.addClip(clip);
        tracks.add(track);

        ClipFadeHandler handler = createHandler();

        handler.beginFade(clip, ClipFadeHandler.FadeHandle.FADE_OUT);
        // Drag past clip end (negative fade) → should clamp to 0
        handler.completeFade(600.0); // beat 15.0, past clip end at 12.0

        assertThat(clip.getFadeOutBeats()).isCloseTo(0.0, offset(0.01));
    }

    // ── Snap to grid ─────────────────────────────────────────────────────────

    @Test
    void shouldSnapFadeToGrid() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 0.0, 8.0, null);
        track.addClip(clip);
        tracks.add(track);
        snapEnabled = true;
        gridResolution = GridResolution.QUARTER;

        ClipFadeHandler handler = createHandler();

        handler.beginFade(clip, ClipFadeHandler.FadeHandle.FADE_IN);
        // Drag to beat 2.3 (pixel 92) — should snap to 2.0
        handler.completeFade(92.0);

        assertThat(clip.getFadeInBeats()).isCloseTo(2.0, offset(0.01));
    }

    // ── Undo integration ─────────────────────────────────────────────────────

    @Test
    void shouldRegisterUndoableAction() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 0.0, 8.0, null);
        track.addClip(clip);
        tracks.add(track);

        ClipFadeHandler handler = createHandler();

        handler.beginFade(clip, ClipFadeHandler.FadeHandle.FADE_IN);
        handler.completeFade(80.0); // beat 2.0

        assertThat(clip.getFadeInBeats()).isCloseTo(2.0, offset(0.01));

        undoManager.undo();

        assertThat(clip.getFadeInBeats()).isCloseTo(0.0, offset(0.01));
    }

    @Test
    void shouldNotRegisterActionWhenNoChange() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 4.0, 8.0, null);
        track.addClip(clip);
        tracks.add(track);

        ClipFadeHandler handler = createHandler();

        handler.beginFade(clip, ClipFadeHandler.FadeHandle.FADE_IN);
        // Release at clip start position (beat 4.0 = pixel 160) → fadeIn stays 0
        handler.completeFade(160.0);

        assertThat(undoManager.canUndo()).isFalse();
    }

    // ── Cancel ───────────────────────────────────────────────────────────────

    @Test
    void shouldCancelFadeAndRestoreOriginalState() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 0.0, 8.0, null);
        clip.setFadeInBeats(1.0);
        track.addClip(clip);
        tracks.add(track);

        ClipFadeHandler handler = createHandler();

        handler.beginFade(clip, ClipFadeHandler.FadeHandle.FADE_IN);
        handler.updateFade(120.0); // Preview at beat 3.0
        handler.cancelFade();

        assertThat(clip.getFadeInBeats()).isCloseTo(1.0, offset(0.01));
        assertThat(handler.isFading()).isFalse();
    }

    // ── Real-time update ─────────────────────────────────────────────────────

    @Test
    void shouldUpdateFadeInRealTime() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 0.0, 8.0, null);
        track.addClip(clip);
        tracks.add(track);

        ClipFadeHandler handler = createHandler();

        handler.beginFade(clip, ClipFadeHandler.FadeHandle.FADE_IN);
        handler.updateFade(120.0); // beat 3.0

        assertThat(clip.getFadeInBeats()).isCloseTo(3.0, offset(0.01));
    }

    // ── Tooltip ──────────────────────────────────────────────────────────────

    @Test
    void shouldGenerateTooltipForFadeIn() {
        AudioClip clip = new AudioClip("Vocal", 0.0, 8.0, null);
        clip.setFadeInBeats(2.5);
        clip.setFadeInCurveType(FadeCurveType.EQUAL_POWER);

        ClipFadeHandler.HandleHit hit =
                new ClipFadeHandler.HandleHit(clip, ClipFadeHandler.FadeHandle.FADE_IN);

        String tooltip = ClipFadeHandler.tooltipFor(hit);
        assertThat(tooltip).contains("Fade In");
        assertThat(tooltip).contains("2.50");
        assertThat(tooltip).contains("Equal Power");
    }

    @Test
    void shouldGenerateTooltipForFadeOut() {
        AudioClip clip = new AudioClip("Vocal", 0.0, 8.0, null);
        clip.setFadeOutBeats(1.0);
        clip.setFadeOutCurveType(FadeCurveType.S_CURVE);

        ClipFadeHandler.HandleHit hit =
                new ClipFadeHandler.HandleHit(clip, ClipFadeHandler.FadeHandle.FADE_OUT);

        String tooltip = ClipFadeHandler.tooltipFor(hit);
        assertThat(tooltip).contains("Fade Out");
        assertThat(tooltip).contains("1.00");
        assertThat(tooltip).contains("S-Curve");
    }

    // ── Handle size constant ─────────────────────────────────────────────────

    @Test
    void handleSizeShouldBeTenPixels() {
        assertThat(ClipFadeHandler.HANDLE_SIZE_PIXELS).isEqualTo(10.0);
    }

    // ── Redo after undo ──────────────────────────────────────────────────────

    @Test
    void shouldRedoFadeAfterUndo() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 0.0, 8.0, null);
        track.addClip(clip);
        tracks.add(track);

        ClipFadeHandler handler = createHandler();

        handler.beginFade(clip, ClipFadeHandler.FadeHandle.FADE_OUT);
        // endBeat = 8.0, drag to beat 5.0 → fadeOut = 3.0
        handler.completeFade(200.0);

        assertThat(clip.getFadeOutBeats()).isCloseTo(3.0, offset(0.01));

        undoManager.undo();
        assertThat(clip.getFadeOutBeats()).isCloseTo(0.0, offset(0.01));

        undoManager.redo();
        assertThat(clip.getFadeOutBeats()).isCloseTo(3.0, offset(0.01));
    }

    // ── Description ──────────────────────────────────────────────────────────

    @Test
    void undoDescriptionShouldBeAdjustClipFade() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 0.0, 8.0, null);
        track.addClip(clip);
        tracks.add(track);

        ClipFadeHandler handler = createHandler();

        handler.beginFade(clip, ClipFadeHandler.FadeHandle.FADE_IN);
        handler.completeFade(80.0); // beat 2.0

        assertThat(undoManager.undoDescription()).isEqualTo("Adjust Clip Fade");
    }
}
