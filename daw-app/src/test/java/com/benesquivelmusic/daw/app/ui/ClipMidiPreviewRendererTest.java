package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.midi.MidiNoteData;

import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ClipMidiPreviewRenderer}: pure-logic bounds computation
 * and smoke-test note rendering with a real JavaFX canvas.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ClipMidiPreviewRendererTest {

    @Test
    void shouldReturnNullBoundsForEmptyNoteList() {
        assertThat(ClipMidiPreviewRenderer.computeBounds(List.of())).isNull();
    }

    @Test
    void shouldComputeBoundsFromSingleNote() {
        var notes = List.of(MidiNoteData.of(60, 4, 8, 100));
        var bounds = ClipMidiPreviewRenderer.computeBounds(notes);
        assertThat(bounds).isNotNull();
        assertThat(bounds.minColumn()).isEqualTo(4);
        assertThat(bounds.minNote()).isEqualTo(60);
        assertThat(bounds.maxNote()).isEqualTo(60);
        assertThat(bounds.startBeat()).isEqualTo(4 * EditorView.BEATS_PER_COLUMN);
        assertThat(bounds.durationBeats()).isEqualTo(8 * EditorView.BEATS_PER_COLUMN);
    }

    @Test
    void shouldComputeBoundsSpanningMultipleNotes() {
        var notes = List.of(
                MidiNoteData.of(60, 0, 4, 100),
                MidiNoteData.of(72, 4, 4, 100),
                MidiNoteData.of(48, 2, 2, 100));
        var bounds = ClipMidiPreviewRenderer.computeBounds(notes);
        assertThat(bounds.minColumn()).isEqualTo(0);
        assertThat(bounds.minNote()).isEqualTo(48);
        assertThat(bounds.maxNote()).isEqualTo(72);
        assertThat(bounds.durationBeats()).isEqualTo(8 * EditorView.BEATS_PER_COLUMN);
    }

    @Test
    void shouldDrawNotesWithoutError() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Canvas canvas = new Canvas(400, 200);
                GraphicsContext gc = canvas.getGraphicsContext2D();
                var notes = List.of(
                        MidiNoteData.of(60, 0, 4, 100),
                        MidiNoteData.of(64, 4, 4, 100),
                        MidiNoteData.of(67, 8, 4, 100));
                var bounds = ClipMidiPreviewRenderer.computeBounds(notes);
                ClipMidiPreviewRenderer.drawNotes(gc, notes, bounds,
                        10, 10, 200, 60, 40.0);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    }
}
