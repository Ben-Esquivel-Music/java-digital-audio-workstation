package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.midi.MidiClip;
import com.benesquivelmusic.daw.core.midi.MidiNoteData;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackColor;
import com.benesquivelmusic.daw.core.track.TrackType;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
class ArrangementCanvasTest {

    // ── Construction ────────────────────────────────────────────────────────

    @Test
    void shouldCreateCanvasWithToolkit() throws Exception {

        AtomicReference<ArrangementCanvas> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(new ArrangementCanvas());
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(ref.get()).isNotNull();
    }

    // ── Property setters ────────────────────────────────────────────────────

    @Test
    void shouldAcceptTracksAndRedraw() throws Exception {

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();

                Track audio = new Track("Audio 1", TrackType.AUDIO);
                audio.setColor(TrackColor.BLUE);
                AudioClip clip = new AudioClip("Vocal", 0.0, 4.0, null);
                audio.addClip(clip);

                Track midi = new Track("MIDI 1", TrackType.MIDI);
                midi.setColor(TrackColor.GREEN);
                midi.getMidiClip().addNote(MidiNoteData.of(60, 0, 2, 100));
                midi.getMidiClip().addNote(MidiNoteData.of(64, 2, 2, 90));

                canvas.setTracks(List.of(audio, midi));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void shouldSetPixelsPerBeat() throws Exception {

        AtomicReference<ArrangementCanvas> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setPixelsPerBeat(80.0);
                ref.set(canvas);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(ref.get().getPixelsPerBeat()).isEqualTo(80.0);
    }

    @Test
    void shouldIgnoreNonPositivePixelsPerBeat() throws Exception {

        AtomicReference<ArrangementCanvas> ref = new AtomicReference<>();
        AtomicReference<Double> beforeRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                beforeRef.set(canvas.getPixelsPerBeat());
                canvas.setPixelsPerBeat(0.0);
                ref.set(canvas);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(ref.get().getPixelsPerBeat()).isEqualTo(beforeRef.get());
    }

    @Test
    void shouldSetScrollOffsets() throws Exception {

        AtomicReference<ArrangementCanvas> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setScrollXBeats(10.5);
                canvas.setScrollYPixels(42.0);
                ref.set(canvas);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(ref.get().getScrollXBeats()).isEqualTo(10.5);
        assertThat(ref.get().getScrollYPixels()).isEqualTo(42.0);
    }

    @Test
    void shouldClampNegativeScrollToZero() throws Exception {

        AtomicReference<ArrangementCanvas> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setScrollXBeats(-5.0);
                canvas.setScrollYPixels(-10.0);
                ref.set(canvas);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(ref.get().getScrollXBeats()).isEqualTo(0.0);
        assertThat(ref.get().getScrollYPixels()).isEqualTo(0.0);
    }

    @Test
    void shouldSetTrackHeight() throws Exception {

        AtomicReference<ArrangementCanvas> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTrackHeight(120.0);
                ref.set(canvas);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(ref.get().getTrackHeight()).isEqualTo(120.0);
    }

    @Test
    void shouldClampTrackHeightToMinimum() throws Exception {

        AtomicReference<ArrangementCanvas> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTrackHeight(5.0);
                ref.set(canvas);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(ref.get().getTrackHeight()).isEqualTo(TrackHeightZoom.MIN_TRACK_HEIGHT);
    }

    @Test
    void shouldHandleNullTrackList() throws Exception {

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(null);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void shouldHandleEmptyTrackList() throws Exception {

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(List.of());
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void shouldRenderAudioClipWithWaveformData() throws Exception {

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();

                Track track = new Track("Audio 1", TrackType.AUDIO);
                AudioClip clip = new AudioClip("Test Clip", 2.0, 8.0, null);
                float[][] audioData = new float[1][1024];
                for (int i = 0; i < 1024; i++) {
                    audioData[0][i] = (float) Math.sin(2 * Math.PI * i / 1024);
                }
                clip.setAudioData(audioData);
                clip.setFadeInBeats(1.0);
                clip.setFadeOutBeats(0.5);
                track.addClip(clip);

                canvas.setTracks(List.of(track));
                canvas.setPixelsPerBeat(40.0);
                canvas.refresh();
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void shouldRenderMidiClipWithNotes() throws Exception {

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();

                Track track = new Track("MIDI 1", TrackType.MIDI);
                track.setColor(TrackColor.PURPLE);
                MidiClip midiClip = track.getMidiClip();
                midiClip.addNote(MidiNoteData.of(60, 0, 4, 100));
                midiClip.addNote(MidiNoteData.of(64, 4, 4, 90));
                midiClip.addNote(MidiNoteData.of(67, 8, 4, 80));

                canvas.setTracks(List.of(track));
                canvas.setPixelsPerBeat(40.0);
                canvas.refresh();
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void shouldSetPlayheadBeat() throws Exception {

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setPlayheadBeat(16.0);
                canvas.refresh();
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void shouldHandleMultipleTracksWithMixedTypes() throws Exception {

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();

                Track audio1 = new Track("Audio 1", TrackType.AUDIO);
                audio1.setColor(TrackColor.RED);
                audio1.addClip(new AudioClip("Vocals", 0.0, 16.0, null));
                audio1.addClip(new AudioClip("Chorus", 20.0, 8.0, null));

                Track midi1 = new Track("MIDI 1", TrackType.MIDI);
                midi1.setColor(TrackColor.BLUE);
                midi1.getMidiClip().addNote(MidiNoteData.of(48, 0, 8, 100));

                Track audio2 = new Track("Audio 2", TrackType.AUDIO);
                audio2.setColor(TrackColor.GREEN);
                audio2.addClip(new AudioClip("Bass", 4.0, 12.0, null));

                canvas.setTracks(List.of(audio1, midi1, audio2));
                canvas.setPixelsPerBeat(60.0);
                canvas.setTrackHeight(100.0);
                canvas.refresh();
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    }

    // ── Playhead getter ─────────────────────────────────────────────────────

    @Test
    void shouldReturnPlayheadBeat() throws Exception {

        AtomicReference<ArrangementCanvas> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setPlayheadBeat(8.5);
                ref.set(canvas);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(ref.get().getPlayheadBeat()).isEqualTo(8.5);
    }

    // ── Auto-scroll toggle ──────────────────────────────────────────────────

    @Test
    void shouldToggleAutoScroll() throws Exception {

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setAutoScroll(false);
                canvas.setPlayheadBeat(100.0);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    }

    // ── Loop region overlay ─────────────────────────────────────────────────

    @Test
    void shouldSetLoopRegion() throws Exception {

        AtomicReference<ArrangementCanvas> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setLoopRegion(true, 4.0, 12.0);
                ref.set(canvas);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(ref.get().isLoopEnabled()).isTrue();
        assertThat(ref.get().getLoopStartBeat()).isEqualTo(4.0);
        assertThat(ref.get().getLoopEndBeat()).isEqualTo(12.0);
    }

    @Test
    void shouldDefaultLoopRegionDisabled() throws Exception {

        AtomicReference<ArrangementCanvas> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(new ArrangementCanvas());
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(ref.get().isLoopEnabled()).isFalse();
    }

    @Test
    void shouldRenderLoopRegionWithTracks() throws Exception {

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                Track audio = new Track("Audio 1", TrackType.AUDIO);
                audio.addClip(new AudioClip("Clip", 0.0, 16.0, null));
                canvas.setTracks(List.of(audio));
                canvas.setLoopRegion(true, 4.0, 12.0);
                canvas.refresh();
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void shouldDisableLoopRegion() throws Exception {

        AtomicReference<ArrangementCanvas> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setLoopRegion(true, 4.0, 12.0);
                canvas.setLoopRegion(false, 4.0, 12.0);
                ref.set(canvas);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(ref.get().isLoopEnabled()).isFalse();
    }

    @Test
    void loopHighlightColorShouldBeDefined() {
        assertThat(ArrangementCanvas.LOOP_HIGHLIGHT_COLOR).isNotNull();
    }
}
