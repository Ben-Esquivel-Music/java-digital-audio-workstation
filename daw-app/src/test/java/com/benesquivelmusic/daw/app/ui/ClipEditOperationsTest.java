package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the clip editing operations: Paste Over, Trim to Selection, and Crop.
 *
 * <p>These tests exercise the production {@link ClipEditOperations} utility
 * class that {@link TrackStripController} delegates to. No JavaFX toolkit is
 * required.</p>
 */
class ClipEditOperationsTest {

    // ── Paste Over ──────────────────────────────────────────────────────────

    @Test
    void pasteOverShouldPlaceClipAtPlayhead() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip original = new AudioClip("vocal", 2.0, 4.0, null);

        ClipEditOperations.Result result =
                ClipEditOperations.pasteOver(track, List.of(original), 10.0);

        assertThat(track.getClips()).hasSize(1);
        AudioClip pasted = track.getClips().get(0);
        assertThat(pasted.getStartBeat()).isEqualTo(10.0);
        assertThat(pasted.getDurationBeats()).isEqualTo(4.0);
        assertThat(result.addedClips()).containsExactly(pasted);
        assertThat(result.removedClips()).isEmpty();
    }

    @Test
    void pasteOverShouldSplitAndRemoveOverlappingClips() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        // Existing clip from beat 4 to beat 12
        AudioClip existing = new AudioClip("drums", 4.0, 8.0, null);
        track.addClip(existing);

        // Paste a 4-beat clip at beat 6 (covers 6..10) — should split existing
        AudioClip toPaste = new AudioClip("vocal", 0.0, 4.0, null);

        ClipEditOperations.Result result =
                ClipEditOperations.pasteOver(track, List.of(toPaste), 6.0);

        // Should have 3 clips: before (4..6), pasted (6..10), after (10..12)
        assertThat(track.getClips()).hasSize(3);
        List<AudioClip> sorted = new ArrayList<>(track.getClips());
        sorted.sort((a, b) -> Double.compare(a.getStartBeat(), b.getStartBeat()));

        assertThat(sorted.get(0).getStartBeat()).isEqualTo(4.0);
        assertThat(sorted.get(0).getEndBeat()).isEqualTo(6.0);
        assertThat(sorted.get(1).getStartBeat()).isEqualTo(6.0);
        assertThat(sorted.get(1).getEndBeat()).isEqualTo(10.0);
        assertThat(sorted.get(2).getStartBeat()).isEqualTo(10.0);
        assertThat(sorted.get(2).getEndBeat()).isEqualTo(12.0);
        assertThat(result.removedClips()).containsExactly(existing);
    }

    @Test
    void pasteOverShouldCompletelyRemoveEnclosedClip() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        // Existing clip from beat 5 to beat 7 — completely inside paste region
        AudioClip small = new AudioClip("click", 5.0, 2.0, null);
        track.addClip(small);

        AudioClip toPaste = new AudioClip("vocal", 0.0, 4.0, null);

        ClipEditOperations.Result result =
                ClipEditOperations.pasteOver(track, List.of(toPaste), 4.0);

        assertThat(track.getClips()).hasSize(1);
        assertThat(track.getClips().get(0).getStartBeat()).isEqualTo(4.0);
        assertThat(result.removedClips()).containsExactly(small);
    }

    @Test
    void pasteOverWithNoOverlapShouldJustAddClip() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip existing = new AudioClip("drums", 0.0, 4.0, null);
        track.addClip(existing);

        AudioClip toPaste = new AudioClip("vocal", 0.0, 4.0, null);

        ClipEditOperations.pasteOver(track, List.of(toPaste), 8.0);

        assertThat(track.getClips()).hasSize(2);
    }

    @Test
    void pasteOverMultipleEntriesShouldNotInterfereWithEachOther() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip existing = new AudioClip("drums", 2.0, 8.0, null); // 2..10
        track.addClip(existing);

        AudioClip clip1 = new AudioClip("vocal", 0.0, 3.0, null);
        AudioClip clip2 = new AudioClip("guitar", 0.0, 3.0, null);

        ClipEditOperations.Result result =
                ClipEditOperations.pasteOver(track, List.of(clip1, clip2), 4.0);

        // Combined paste span is 4..7 (both clips are 3 beats starting at playhead 4)
        // The existing clip (2..10) should be split into before (2..4) and after (7..10)
        // Plus the two pasted clips (both at 4..7)
        assertThat(result.removedClips()).containsExactly(existing);

        List<AudioClip> sorted = new ArrayList<>(track.getClips());
        sorted.sort((a, b) -> Double.compare(a.getStartBeat(), b.getStartBeat()));

        // before portion, then 2 pasted clips, then after portion
        assertThat(sorted.get(0).getStartBeat()).isEqualTo(2.0);
        assertThat(sorted.get(0).getEndBeat()).isEqualTo(4.0);
        // last clip should be the after-split portion
        assertThat(sorted.get(sorted.size() - 1).getStartBeat()).isEqualTo(7.0);
        assertThat(sorted.get(sorted.size() - 1).getEndBeat()).isEqualTo(10.0);
    }

    @Test
    void pasteOverUndoShouldRestoreOriginalState() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip existing = new AudioClip("drums", 4.0, 8.0, null);
        track.addClip(existing);

        AudioClip toPaste = new AudioClip("vocal", 0.0, 4.0, null);

        ClipEditOperations.Result result =
                ClipEditOperations.pasteOver(track, List.of(toPaste), 6.0);

        assertThat(track.getClips()).hasSize(3);

        result.undo(track);

        assertThat(track.getClips()).hasSize(1);
        assertThat(track.getClips().get(0)).isSameAs(existing);
    }

    // ── Trim to Selection ───────────────────────────────────────────────────

    @Test
    void trimToSelectionShouldTrimClipToSelectionBoundaries() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("vocal", 2.0, 10.0, null); // 2..12
        track.addClip(clip);

        ClipEditOperations.TrimResult result =
                ClipEditOperations.trimToSelection(track, 4.0, 8.0);

        assertThat(result).isNotNull();
        assertThat(clip.getStartBeat()).isEqualTo(4.0);
        assertThat(clip.getDurationBeats()).isEqualTo(4.0);
        assertThat(clip.getSourceOffsetBeats()).isEqualTo(2.0); // offset by 4-2=2
    }

    @Test
    void trimToSelectionShouldHandleSelectionLargerThanClip() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("vocal", 4.0, 4.0, null); // 4..8
        track.addClip(clip);

        // Clip is already fully inside selection — trimming should leave it unchanged
        ClipEditOperations.TrimResult result =
                ClipEditOperations.trimToSelection(track, 2.0, 12.0);

        assertThat(result).isNotNull();
        assertThat(clip.getStartBeat()).isEqualTo(4.0);
        assertThat(clip.getDurationBeats()).isEqualTo(4.0);
        assertThat(clip.getSourceOffsetBeats()).isEqualTo(0.0);
    }

    @Test
    void trimToSelectionShouldTrimOnlyTheStart() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("vocal", 2.0, 6.0, null); // 2..8
        track.addClip(clip);

        ClipEditOperations.trimToSelection(track, 4.0, 10.0);

        assertThat(clip.getStartBeat()).isEqualTo(4.0);
        assertThat(clip.getDurationBeats()).isEqualTo(4.0);
        assertThat(clip.getSourceOffsetBeats()).isEqualTo(2.0);
    }

    @Test
    void trimToSelectionShouldTrimOnlyTheEnd() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("vocal", 4.0, 8.0, null); // 4..12
        track.addClip(clip);

        ClipEditOperations.trimToSelection(track, 2.0, 8.0);

        assertThat(clip.getStartBeat()).isEqualTo(4.0);
        assertThat(clip.getDurationBeats()).isEqualTo(4.0);
    }

    @Test
    void trimToSelectionShouldReturnNullWhenNoClipsOverlap() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("vocal", 10.0, 4.0, null); // 10..14
        track.addClip(clip);

        ClipEditOperations.TrimResult result =
                ClipEditOperations.trimToSelection(track, 2.0, 6.0);

        assertThat(result).isNull();
        assertThat(clip.getStartBeat()).isEqualTo(10.0); // unchanged
    }

    @Test
    void trimToSelectionUndoShouldRestoreOriginalClipState() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("vocal", 2.0, 10.0, null);
        track.addClip(clip);

        ClipEditOperations.TrimResult result =
                ClipEditOperations.trimToSelection(track, 4.0, 8.0);

        assertThat(clip.getStartBeat()).isEqualTo(4.0);

        result.undo();

        assertThat(clip.getStartBeat()).isEqualTo(2.0);
        assertThat(clip.getDurationBeats()).isEqualTo(10.0);
        assertThat(clip.getSourceOffsetBeats()).isEqualTo(0.0);
    }

    // ── Crop ────────────────────────────────────────────────────────────────

    @Test
    void cropShouldRemoveClipsCompletelyOutsideSelection() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip inside = new AudioClip("inside", 4.0, 4.0, null);   // 4..8
        AudioClip outside = new AudioClip("outside", 12.0, 2.0, null); // 12..14
        track.addClip(inside);
        track.addClip(outside);

        ClipEditOperations.Result result =
                ClipEditOperations.crop(track, 3.0, 9.0);

        assertThat(track.getClips()).hasSize(1);
        assertThat(track.getClips().get(0).getName()).isEqualTo("inside");
        assertThat(result.removedClips()).containsExactly(outside);
    }

    @Test
    void cropShouldSplitAndTrimClipsPartiallyInsideSelection() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        // Clip spans 2..12 — selection is 4..8
        AudioClip clip = new AudioClip("vocal", 2.0, 10.0, null);
        track.addClip(clip);

        ClipEditOperations.Result result =
                ClipEditOperations.crop(track, 4.0, 8.0);

        assertThat(track.getClips()).hasSize(1);
        AudioClip trimmed = track.getClips().get(0);
        assertThat(trimmed.getStartBeat()).isEqualTo(4.0);
        assertThat(trimmed.getEndBeat()).isEqualTo(8.0);
        assertThat(trimmed.getSourceOffsetBeats()).isEqualTo(2.0);
        assertThat(result.removedClips()).containsExactly(clip);
    }

    @Test
    void cropShouldKeepClipsFullyInsideSelection() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("vocal", 4.0, 2.0, null); // 4..6
        track.addClip(clip);

        ClipEditOperations.Result result =
                ClipEditOperations.crop(track, 2.0, 10.0);

        assertThat(track.getClips()).hasSize(1);
        assertThat(track.getClips().get(0)).isSameAs(clip);
        assertThat(result.removedClips()).isEmpty();
        assertThat(result.addedClips()).isEmpty();
    }

    @Test
    void cropWithNoClipsShouldBeNoOp() {
        Track track = new Track("Audio 1", TrackType.AUDIO);

        ClipEditOperations.Result result =
                ClipEditOperations.crop(track, 2.0, 10.0);

        assertThat(track.getClips()).isEmpty();
        assertThat(result.removedClips()).isEmpty();
        assertThat(result.addedClips()).isEmpty();
    }

    @Test
    void cropShouldHandleMultipleClips() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip c1 = new AudioClip("a", 0.0, 4.0, null);    // 0..4
        AudioClip c2 = new AudioClip("b", 4.0, 4.0, null);    // 4..8
        AudioClip c3 = new AudioClip("c", 8.0, 4.0, null);    // 8..12
        AudioClip c4 = new AudioClip("d", 12.0, 4.0, null);   // 12..16
        track.addClip(c1);
        track.addClip(c2);
        track.addClip(c3);
        track.addClip(c4);

        ClipEditOperations.crop(track, 3.0, 10.0);

        // c1 (0..4) partially overlaps → trimmed to 3..4
        // c2 (4..8) fully inside → kept
        // c3 (8..12) partially overlaps → trimmed to 8..10
        // c4 (12..16) fully outside → removed
        assertThat(track.getClips()).hasSize(3);

        List<AudioClip> sorted = new ArrayList<>(track.getClips());
        sorted.sort((a, b) -> Double.compare(a.getStartBeat(), b.getStartBeat()));

        assertThat(sorted.get(0).getStartBeat()).isEqualTo(3.0);
        assertThat(sorted.get(0).getEndBeat()).isEqualTo(4.0);
        assertThat(sorted.get(1).getStartBeat()).isEqualTo(4.0);
        assertThat(sorted.get(1).getEndBeat()).isEqualTo(8.0);
        assertThat(sorted.get(2).getStartBeat()).isEqualTo(8.0);
        assertThat(sorted.get(2).getEndBeat()).isEqualTo(10.0);
    }

    @Test
    void cropUndoShouldRestoreOriginalState() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("vocal", 2.0, 10.0, null);
        track.addClip(clip);

        ClipEditOperations.Result result =
                ClipEditOperations.crop(track, 4.0, 8.0);

        assertThat(track.getClips()).hasSize(1);
        assertThat(track.getClips().get(0)).isNotSameAs(clip);

        result.undo(track);

        assertThat(track.getClips()).hasSize(1);
        assertThat(track.getClips().get(0)).isSameAs(clip);
    }
}
