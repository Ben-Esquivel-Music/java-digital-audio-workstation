package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.FadeCurveType;
import com.benesquivelmusic.daw.core.midi.MidiNoteData;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackColor;
import com.benesquivelmusic.daw.core.track.TrackType;

import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for {@link ClipOverlayRenderer} — exercises the audio
 * clip, MIDI clip and trim preview entry points over a range of input
 * combinations (fade curves, selection, off-screen clips) to catch
 * regressions in the extracted code path.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ClipOverlayRendererTest {

    private static final double PIXELS_PER_BEAT = 40.0;

    @Test
    void shouldDrawAudioClipWithoutSelection() throws Exception {
        runOnFxThread(gc -> {
            AudioClip clip = new AudioClip("Clip", 0.0, 4.0, null);
            clip.setAudioData(new float[][]{new float[1024]});
            ClipOverlayRenderer.drawAudioClip(gc, clip, Color.BLUE,
                    10, 80, PIXELS_PER_BEAT, 0.0, 400, 200, null);
        });
    }

    @Test
    void shouldDrawAudioClipWithFades() throws Exception {
        runOnFxThread(gc -> {
            AudioClip clip = new AudioClip("FadeClip", 1.0, 6.0, null);
            clip.setAudioData(new float[][]{new float[2048]});
            clip.setFadeInBeats(1.0);
            clip.setFadeInCurveType(FadeCurveType.EQUAL_POWER);
            clip.setFadeOutBeats(2.0);
            clip.setFadeOutCurveType(FadeCurveType.S_CURVE);
            ClipOverlayRenderer.drawAudioClip(gc, clip, Color.RED,
                    10, 80, PIXELS_PER_BEAT, 0.0, 400, 200, null);
        });
    }

    @Test
    void shouldDrawAudioClipWithSelection() throws Exception {
        runOnFxThread(gc -> {
            Track track = new Track("T", TrackType.AUDIO);
            AudioClip clip = new AudioClip("Sel", 2.0, 4.0, null);
            track.addClip(clip);
            SelectionModel sel = new SelectionModel();
            sel.selectClip(track, clip);
            ClipOverlayRenderer.drawAudioClip(gc, clip, Color.GREEN,
                    10, 80, PIXELS_PER_BEAT, 0.0, 400, 200, sel);
            assertThat(sel.isClipSelected(clip)).isTrue();
        });
    }

    @Test
    void shouldCullAudioClipFullyOutsideViewport() throws Exception {
        runOnFxThread(gc -> {
            AudioClip clip = new AudioClip("Off", 100.0, 4.0, null);
            ClipOverlayRenderer.drawAudioClip(gc, clip, Color.BLUE,
                    10, 80, PIXELS_PER_BEAT, 0.0, 400, 200, null);
        });
    }

    @Test
    void shouldDrawMidiClipWithNotes() throws Exception {
        runOnFxThread(gc -> {
            Track track = new Track("Midi", TrackType.MIDI);
            track.setColor(TrackColor.GREEN);
            track.getMidiClip().addNote(MidiNoteData.of(60, 0, 4, 100));
            track.getMidiClip().addNote(MidiNoteData.of(64, 4, 4, 100));
            ClipOverlayRenderer.drawMidiClip(gc, track, track.getMidiClip(), Color.GREEN,
                    10, 80, PIXELS_PER_BEAT, 0.0, 400, 200, null);
        });
    }

    @Test
    void shouldNoopOnEmptyMidiClip() throws Exception {
        runOnFxThread(gc -> {
            Track track = new Track("Empty", TrackType.MIDI);
            ClipOverlayRenderer.drawMidiClip(gc, track, track.getMidiClip(), Color.GREEN,
                    10, 80, PIXELS_PER_BEAT, 0.0, 400, 200, null);
        });
    }

    @Test
    void shouldDrawTrimPreviewInsideLane() throws Exception {
        runOnFxThread(gc -> ClipOverlayRenderer.drawTrimPreview(gc,
                2.5, 0.0, PIXELS_PER_BEAT, 10, 80, 400, 200));
    }

    @Test
    void shouldSkipTrimPreviewOutsideCanvas() throws Exception {
        runOnFxThread(gc -> {
            ClipOverlayRenderer.drawTrimPreview(gc, -1.0, 0.0, PIXELS_PER_BEAT, 10, 80, 400, 200);
            ClipOverlayRenderer.drawTrimPreview(gc, 2.5, 0.0, PIXELS_PER_BEAT, -200, 80, 400, 200);
        });
    }

    private static void runOnFxThread(java.util.function.Consumer<GraphicsContext> action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Canvas canvas = new Canvas(400, 200);
                action.accept(canvas.getGraphicsContext2D());
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    }
}
