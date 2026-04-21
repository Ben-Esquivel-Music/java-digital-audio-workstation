package com.benesquivelmusic.daw.core.project.edit;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.CompoundUndoableAction;
import com.benesquivelmusic.daw.sdk.edit.RippleMode;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link RippleEditService}. Validates the three ripple-scope
 * behaviours (OFF / PER_TRACK / ALL_TRACKS), selection-aware behaviour, and
 * that invalid moves surface as {@link RippleValidationException}s.
 *
 * <p>Story 138 — {@code docs/user-stories/138-ripple-edit-mode.md}.</p>
 */
class RippleEditServiceTest {

    // ── Ripple Delete ────────────────────────────────────────────────────────

    @Test
    void rippleDeletePerTrackClosesGapOnTrackOnly() {
        Track drums = new Track("Drums", TrackType.AUDIO);
        Track bass = new Track("Bass", TrackType.AUDIO);
        AudioClip kick = new AudioClip("kick", 0.0, 4.0, null);
        AudioClip snare = new AudioClip("snare", 4.0, 4.0, null);
        AudioClip hat = new AudioClip("hat", 8.0, 4.0, null);
        AudioClip bassLine = new AudioClip("bass-line", 4.0, 8.0, null);
        drums.addClip(kick);
        drums.addClip(snare);
        drums.addClip(hat);
        bass.addClip(bassLine);

        CompoundUndoableAction action = RippleEditService.buildRippleDelete(
                List.of(Map.entry(drums, snare)),
                RippleMode.PER_TRACK,
                List.of(drums, bass),
                OptionalDouble.empty(), OptionalDouble.empty());
        action.execute();

        // snare deleted; hat shifted left by 4 beats; bass line untouched
        assertThat(drums.getClips()).doesNotContain(snare);
        assertThat(hat.getStartBeat()).isEqualTo(4.0);
        assertThat(bassLine.getStartBeat()).isEqualTo(4.0);
    }

    @Test
    void rippleDeleteAllTracksClosesGapOnEveryTrack() {
        Track drums = new Track("Drums", TrackType.AUDIO);
        Track bass = new Track("Bass", TrackType.AUDIO);
        AudioClip kick = new AudioClip("kick", 0.0, 4.0, null);
        AudioClip snare = new AudioClip("snare", 4.0, 4.0, null);
        AudioClip hat = new AudioClip("hat", 8.0, 4.0, null);
        AudioClip bassIntro = new AudioClip("bass-intro", 0.0, 4.0, null);
        AudioClip bassMain = new AudioClip("bass-main", 8.0, 4.0, null);
        drums.addClip(kick);
        drums.addClip(snare);
        drums.addClip(hat);
        bass.addClip(bassIntro);
        bass.addClip(bassMain);

        CompoundUndoableAction action = RippleEditService.buildRippleDelete(
                List.of(Map.entry(drums, snare)),
                RippleMode.ALL_TRACKS,
                List.of(drums, bass),
                OptionalDouble.empty(), OptionalDouble.empty());
        action.execute();

        // Drums: snare deleted, hat shifts left by 4.
        assertThat(drums.getClips()).doesNotContain(snare);
        assertThat(hat.getStartBeat()).isEqualTo(4.0);

        // Bass: bassMain started at 8 which is >= cutoff (4) → shifts to 4.
        // bassIntro (start 0) is before cutoff → unchanged.
        assertThat(bassIntro.getStartBeat()).isEqualTo(0.0);
        assertThat(bassMain.getStartBeat()).isEqualTo(4.0);
    }

    @Test
    void rippleDeleteUndoRestoresOriginalPositions() {
        Track drums = new Track("Drums", TrackType.AUDIO);
        AudioClip kick = new AudioClip("kick", 0.0, 4.0, null);
        AudioClip snare = new AudioClip("snare", 4.0, 4.0, null);
        AudioClip hat = new AudioClip("hat", 8.0, 4.0, null);
        drums.addClip(kick);
        drums.addClip(snare);
        drums.addClip(hat);

        CompoundUndoableAction action = RippleEditService.buildRippleDelete(
                List.of(Map.entry(drums, snare)),
                RippleMode.PER_TRACK,
                List.of(drums),
                OptionalDouble.empty(), OptionalDouble.empty());
        action.execute();
        action.undo();

        assertThat(drums.getClips()).contains(kick, snare, hat);
        assertThat(kick.getStartBeat()).isEqualTo(0.0);
        assertThat(snare.getStartBeat()).isEqualTo(4.0);
        assertThat(hat.getStartBeat()).isEqualTo(8.0);
    }

    @Test
    void rippleDeleteOffLeavesLaterClipsUntouched() {
        Track drums = new Track("Drums", TrackType.AUDIO);
        AudioClip kick = new AudioClip("kick", 0.0, 4.0, null);
        AudioClip snare = new AudioClip("snare", 4.0, 4.0, null);
        AudioClip hat = new AudioClip("hat", 8.0, 4.0, null);
        drums.addClip(kick);
        drums.addClip(snare);
        drums.addClip(hat);

        CompoundUndoableAction action = RippleEditService.buildRippleDelete(
                List.of(Map.entry(drums, snare)),
                RippleMode.OFF,
                List.of(drums),
                OptionalDouble.empty(), OptionalDouble.empty());
        action.execute();

        assertThat(drums.getClips()).doesNotContain(snare);
        assertThat(hat.getStartBeat()).isEqualTo(8.0); // unchanged
    }

    @Test
    void rippleDeleteWithTimeSelectionRipplesBySelectionLength() {
        Track drums = new Track("Drums", TrackType.AUDIO);
        AudioClip kick = new AudioClip("kick", 0.0, 4.0, null);
        AudioClip snare = new AudioClip("snare", 4.0, 2.0, null); // only 2 beats
        AudioClip hat = new AudioClip("hat", 16.0, 4.0, null);
        drums.addClip(kick);
        drums.addClip(snare);
        drums.addClip(hat);

        // Selection covers 4..12 (8 beats) but only the snare lies inside.
        CompoundUndoableAction action = RippleEditService.buildRippleDelete(
                List.of(Map.entry(drums, snare)),
                RippleMode.PER_TRACK,
                List.of(drums),
                OptionalDouble.of(4.0), OptionalDouble.of(12.0));
        action.execute();

        // hat started at 16 → shifts left by 8 (selection length) → 8.
        assertThat(hat.getStartBeat()).isEqualTo(8.0);
    }

    @Test
    void rippleDeleteRejectsEmptyEntries() {
        assertThatThrownBy(() -> RippleEditService.buildRippleDelete(
                List.of(),
                RippleMode.PER_TRACK,
                List.of(),
                OptionalDouble.empty(), OptionalDouble.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Ripple Move ──────────────────────────────────────────────────────────

    @Test
    void rippleMovePerTrackShiftsLaterClipsByDelta() {
        Track drums = new Track("Drums", TrackType.AUDIO);
        AudioClip kick = new AudioClip("kick", 0.0, 4.0, null);
        AudioClip snare = new AudioClip("snare", 4.0, 4.0, null);
        AudioClip hat = new AudioClip("hat", 8.0, 4.0, null);
        drums.addClip(kick);
        drums.addClip(snare);
        drums.addClip(hat);

        // Move snare from 4 → 6 (delta = +2). hat should shift to 10.
        CompoundUndoableAction action = RippleEditService.buildRippleMove(
                snare, drums, 6.0,
                RippleMode.PER_TRACK,
                List.of(drums),
                OptionalDouble.empty(), OptionalDouble.empty());
        action.execute();

        assertThat(snare.getStartBeat()).isEqualTo(6.0);
        assertThat(hat.getStartBeat()).isEqualTo(10.0);
        assertThat(kick.getStartBeat()).isEqualTo(0.0); // before cutoff → unchanged
    }

    @Test
    void rippleMoveOutsideSelectionDoesNotRipple() {
        Track drums = new Track("Drums", TrackType.AUDIO);
        AudioClip kick = new AudioClip("kick", 0.0, 4.0, null);
        AudioClip snare = new AudioClip("snare", 4.0, 4.0, null);
        AudioClip hat = new AudioClip("hat", 20.0, 4.0, null);
        drums.addClip(kick);
        drums.addClip(snare);
        drums.addClip(hat);

        // Selection 10..18 — snare (start 4) is outside the selection, so the
        // move should not ripple hat.
        CompoundUndoableAction action = RippleEditService.buildRippleMove(
                snare, drums, 6.0,
                RippleMode.PER_TRACK,
                List.of(drums),
                OptionalDouble.of(10.0), OptionalDouble.of(18.0));
        action.execute();

        assertThat(snare.getStartBeat()).isEqualTo(6.0);
        assertThat(hat.getStartBeat()).isEqualTo(20.0); // unchanged
    }

    @Test
    void rippleMoveOverlapThrowsValidationException() {
        // Anchor (stationary, before cutoff) at 4..8.
        // Moved clip at 10 → 2 (delta = -8).
        // Later clip at 14 shifts by -8 → lands at 6..10, overlapping anchor.
        Track drums = new Track("Drums", TrackType.AUDIO);
        AudioClip anchor = new AudioClip("anchor", 4.0, 4.0, null);
        AudioClip moved = new AudioClip("moved", 10.0, 4.0, null);
        AudioClip later = new AudioClip("later", 14.0, 4.0, null);
        drums.addClip(anchor);
        drums.addClip(moved);
        drums.addClip(later);

        assertThatThrownBy(() -> RippleEditService.buildRippleMove(
                moved, drums, 2.0,
                RippleMode.PER_TRACK,
                List.of(drums),
                OptionalDouble.empty(), OptionalDouble.empty()))
                .isInstanceOf(RippleValidationException.class)
                .hasMessageContaining("overlap");
    }

    @Test
    void rippleMoveUndoRestoresAllPositions() {
        Track drums = new Track("Drums", TrackType.AUDIO);
        AudioClip kick = new AudioClip("kick", 0.0, 4.0, null);
        AudioClip snare = new AudioClip("snare", 4.0, 4.0, null);
        AudioClip hat = new AudioClip("hat", 8.0, 4.0, null);
        drums.addClip(kick);
        drums.addClip(snare);
        drums.addClip(hat);

        CompoundUndoableAction action = RippleEditService.buildRippleMove(
                snare, drums, 6.0,
                RippleMode.PER_TRACK,
                List.of(drums),
                OptionalDouble.empty(), OptionalDouble.empty());
        action.execute();
        action.undo();

        assertThat(kick.getStartBeat()).isEqualTo(0.0);
        assertThat(snare.getStartBeat()).isEqualTo(4.0);
        assertThat(hat.getStartBeat()).isEqualTo(8.0);
    }

    @Test
    void rippleMoveAllTracksShiftsEveryTrack() {
        Track drums = new Track("Drums", TrackType.AUDIO);
        Track bass = new Track("Bass", TrackType.AUDIO);
        AudioClip snare = new AudioClip("snare", 4.0, 4.0, null);
        AudioClip hat = new AudioClip("hat", 8.0, 4.0, null);
        AudioClip bassTail = new AudioClip("bass-tail", 10.0, 4.0, null);
        drums.addClip(snare);
        drums.addClip(hat);
        bass.addClip(bassTail);

        CompoundUndoableAction action = RippleEditService.buildRippleMove(
                snare, drums, 6.0,
                RippleMode.ALL_TRACKS,
                List.of(drums, bass),
                OptionalDouble.empty(), OptionalDouble.empty());
        action.execute();

        assertThat(snare.getStartBeat()).isEqualTo(6.0);
        assertThat(hat.getStartBeat()).isEqualTo(10.0);
        assertThat(bassTail.getStartBeat()).isEqualTo(12.0); // shifted by +2
    }

    @Test
    void rippleMoveOffOnlyMovesPrimaryClip() {
        Track drums = new Track("Drums", TrackType.AUDIO);
        AudioClip snare = new AudioClip("snare", 4.0, 4.0, null);
        AudioClip hat = new AudioClip("hat", 8.0, 4.0, null);
        drums.addClip(snare);
        drums.addClip(hat);

        CompoundUndoableAction action = RippleEditService.buildRippleMove(
                snare, drums, 6.0,
                RippleMode.OFF,
                List.of(drums),
                OptionalDouble.empty(), OptionalDouble.empty());
        action.execute();

        assertThat(snare.getStartBeat()).isEqualTo(6.0);
        assertThat(hat.getStartBeat()).isEqualTo(8.0); // unchanged
    }
}
