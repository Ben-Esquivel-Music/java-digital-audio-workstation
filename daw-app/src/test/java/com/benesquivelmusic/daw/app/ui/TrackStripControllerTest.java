package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link TrackStripController} helper logic that can be exercised
 * without a live JavaFX scene or toolkit.
 *
 * <p>Story 322 deleted {@code midiInstrumentIcon} together with the MIDI
 * instrument-hint icons it fed (the insert row now renders the channel's real
 * rack — see {@code InsertIndicatorTruthTest}); its keyword tests went with it.</p>
 */
class TrackStripControllerTest {

    // ── computeDropTargetIndex ───────────────────────────────────────────────

    @Test
    void shouldReturnNegativeOneWhenDropOnSamePosition() {
        // Dragging track 1 and dropping above track 2 results in no-op (same position)
        assertThat(TrackStripController.computeDropTargetIndex(1, 1, true)).isEqualTo(-1);
    }

    @Test
    void shouldMoveTrackDownWhenDropBelow() {
        // 3 tracks [0,1,2]: drag 0, drop below track 2 → target = 2
        assertThat(TrackStripController.computeDropTargetIndex(0, 2, false)).isEqualTo(2);
    }

    @Test
    void shouldMoveTrackUpWhenDropAbove() {
        // 3 tracks [0,1,2]: drag 2, drop above track 0 → target = 0
        assertThat(TrackStripController.computeDropTargetIndex(2, 0, true)).isEqualTo(0);
    }

    @Test
    void shouldHandleAdjacentMoveDown() {
        // 3 tracks [0,1,2]: drag 0, drop below track 1 → target = 1
        assertThat(TrackStripController.computeDropTargetIndex(0, 1, false)).isEqualTo(1);
    }

    @Test
    void shouldHandleAdjacentMoveUp() {
        // 3 tracks [0,1,2]: drag 1, drop above track 0 → target = 0
        assertThat(TrackStripController.computeDropTargetIndex(1, 0, true)).isEqualTo(0);
    }

    @Test
    void shouldReturnNegativeOneWhenDropBelowSelf() {
        // Dragging track 1 and dropping below track 0 → stays at index 1 → no-op
        assertThat(TrackStripController.computeDropTargetIndex(1, 0, false)).isEqualTo(-1);
    }

    @Test
    void shouldHandleDropAboveTopHalfAdjacentBelow() {
        // 3 tracks [0,1,2]: drag 0, drop above track 1 → stays at 0 → no-op
        assertThat(TrackStripController.computeDropTargetIndex(0, 1, true)).isEqualTo(-1);
    }

    @Test
    void shouldHandleMoveToEnd() {
        // 5 tracks [0..4]: drag 0, drop below track 4 → target = 4
        assertThat(TrackStripController.computeDropTargetIndex(0, 4, false)).isEqualTo(4);
    }

    @Test
    void shouldHandleMoveToStart() {
        // 5 tracks [0..4]: drag 4, drop above track 0 → target = 0
        assertThat(TrackStripController.computeDropTargetIndex(4, 0, true)).isEqualTo(0);
    }

    @Test
    void shouldHandleMoveFromMiddleDown() {
        // 5 tracks [0..4]: drag 2, drop below track 3 → target = 3
        assertThat(TrackStripController.computeDropTargetIndex(2, 3, false)).isEqualTo(3);
    }

    @Test
    void shouldHandleMoveFromMiddleUp() {
        // 5 tracks [0..4]: drag 2, drop above track 1 → target = 1
        assertThat(TrackStripController.computeDropTargetIndex(2, 1, true)).isEqualTo(1);
    }

    // ── abbreviateInsertName (story 322 insert indicators) ───────────────────

    @Test
    void abbreviatesInsertNamesToThreeUpperCaseCharacters() {
        assertThat(TrackStripController.abbreviateInsertName("Compressor")).isEqualTo("COM");
        assertThat(TrackStripController.abbreviateInsertName("EQ")).isEqualTo("EQ");
        assertThat(TrackStripController.abbreviateInsertName("  reverb ")).isEqualTo("REV");
        assertThat(TrackStripController.abbreviateInsertName("   ")).isEqualTo("?");
    }
}
