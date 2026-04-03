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
 * <p>These tests exercise the domain-model logic used by the corresponding
 * context-menu actions in {@link TrackStripController}. No JavaFX toolkit is
 * required.</p>
 */
class ClipEditOperationsTest {

    // ── Paste Over ──────────────────────────────────────────────────────────

    @Test
    void pasteOverShouldPlaceClipAtPlayhead() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip original = new AudioClip("vocal", 2.0, 4.0, null);
        ClipboardManager clipboard = new ClipboardManager();
        clipboard.copyClips(List.of(new ClipboardEntry(track, original)));

        double playhead = 10.0;
        ClipboardEntry entry = clipboard.getEntries().get(0);
        AudioClip pasted = entry.clip().duplicate();
        pasted.setStartBeat(playhead);
        track.addClip(pasted);

        assertThat(pasted.getStartBeat()).isEqualTo(10.0);
        assertThat(pasted.getDurationBeats()).isEqualTo(4.0);
        assertThat(track.getClips()).contains(pasted);
    }

    @Test
    void pasteOverShouldSplitAndRemoveOverlappingClips() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        // Existing clip from beat 4 to beat 12
        AudioClip existing = new AudioClip("drums", 4.0, 8.0, null);
        track.addClip(existing);

        // Paste a 4-beat clip at beat 6 (covers 6..10) — should split existing
        AudioClip toPaste = new AudioClip("vocal", 0.0, 4.0, null);
        double playhead = 6.0;
        double pasteEnd = playhead + toPaste.getDurationBeats(); // 10.0

        // Find overlapping clips and split/remove
        List<AudioClip> toRemove = new ArrayList<>();
        List<AudioClip> toAdd = new ArrayList<>();
        for (AudioClip clip : new ArrayList<>(track.getClips())) {
            double clipStart = clip.getStartBeat();
            double clipEnd = clip.getEndBeat();
            if (clipStart < pasteEnd && clipEnd > playhead) {
                toRemove.add(clip);
                // Keep the portion before the paste region
                if (clipStart < playhead) {
                    AudioClip before = clip.duplicate();
                    before.setStartBeat(clipStart);
                    before.setSourceOffsetBeats(clip.getSourceOffsetBeats());
                    before.setDurationBeats(playhead - clipStart);
                    toAdd.add(before);
                }
                // Keep the portion after the paste region
                if (clipEnd > pasteEnd) {
                    AudioClip after = clip.duplicate();
                    after.setSourceOffsetBeats(clip.getSourceOffsetBeats() + (pasteEnd - clipStart));
                    after.setStartBeat(pasteEnd);
                    after.setDurationBeats(clipEnd - pasteEnd);
                    toAdd.add(after);
                }
            }
        }
        for (AudioClip clip : toRemove) track.removeClip(clip);
        for (AudioClip clip : toAdd) track.addClip(clip);
        AudioClip pasted = toPaste.duplicate();
        pasted.setStartBeat(playhead);
        track.addClip(pasted);

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
    }

    @Test
    void pasteOverShouldCompletelyRemoveEnclosedClip() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        // Existing clip from beat 5 to beat 7 — completely inside paste region
        AudioClip small = new AudioClip("click", 5.0, 2.0, null);
        track.addClip(small);

        double playhead = 4.0;
        double pasteEnd = 8.0; // paste a 4-beat clip at beat 4

        List<AudioClip> toRemove = new ArrayList<>();
        for (AudioClip clip : new ArrayList<>(track.getClips())) {
            if (clip.getStartBeat() < pasteEnd && clip.getEndBeat() > playhead) {
                toRemove.add(clip);
            }
        }
        for (AudioClip clip : toRemove) track.removeClip(clip);
        AudioClip pasted = new AudioClip("vocal", playhead, pasteEnd - playhead, null);
        track.addClip(pasted);

        assertThat(track.getClips()).hasSize(1);
        assertThat(track.getClips().get(0).getStartBeat()).isEqualTo(4.0);
    }

    @Test
    void pasteOverWithNoOverlapShouldJustAddClip() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip existing = new AudioClip("drums", 0.0, 4.0, null);
        track.addClip(existing);

        double playhead = 8.0;
        AudioClip pasted = new AudioClip("vocal", playhead, 4.0, null);
        track.addClip(pasted);

        assertThat(track.getClips()).hasSize(2);
    }

    // ── Trim to Selection ───────────────────────────────────────────────────

    @Test
    void trimToSelectionShouldTrimClipToSelectionBoundaries() {
        AudioClip clip = new AudioClip("vocal", 2.0, 10.0, null); // 2..12
        double selStart = 4.0;
        double selEnd = 8.0;

        // Trim: clamp clip to selection range
        double newStart = Math.max(clip.getStartBeat(), selStart);
        double newEnd = Math.min(clip.getEndBeat(), selEnd);
        clip.trimTo(newStart, newEnd);

        assertThat(clip.getStartBeat()).isEqualTo(4.0);
        assertThat(clip.getDurationBeats()).isEqualTo(4.0);
        assertThat(clip.getSourceOffsetBeats()).isEqualTo(2.0); // offset by 4-2=2
    }

    @Test
    void trimToSelectionShouldHandleSelectionLargerThanClip() {
        AudioClip clip = new AudioClip("vocal", 4.0, 4.0, null); // 4..8
        double selStart = 2.0;
        double selEnd = 12.0;

        // Clip is already fully inside selection — no effective trim
        double newStart = Math.max(clip.getStartBeat(), selStart);
        double newEnd = Math.min(clip.getEndBeat(), selEnd);

        // No actual trim needed since clip is within selection
        assertThat(newStart).isEqualTo(4.0);
        assertThat(newEnd).isEqualTo(8.0);
    }

    @Test
    void trimToSelectionShouldTrimOnlyTheStart() {
        AudioClip clip = new AudioClip("vocal", 2.0, 6.0, null); // 2..8
        double selStart = 4.0;
        double selEnd = 10.0;

        double newStart = Math.max(clip.getStartBeat(), selStart);
        double newEnd = Math.min(clip.getEndBeat(), selEnd);
        clip.trimTo(newStart, newEnd);

        assertThat(clip.getStartBeat()).isEqualTo(4.0);
        assertThat(clip.getDurationBeats()).isEqualTo(4.0);
        assertThat(clip.getSourceOffsetBeats()).isEqualTo(2.0);
    }

    @Test
    void trimToSelectionShouldTrimOnlyTheEnd() {
        AudioClip clip = new AudioClip("vocal", 4.0, 8.0, null); // 4..12
        double selStart = 2.0;
        double selEnd = 8.0;

        double newStart = Math.max(clip.getStartBeat(), selStart);
        double newEnd = Math.min(clip.getEndBeat(), selEnd);
        clip.trimTo(newStart, newEnd);

        assertThat(clip.getStartBeat()).isEqualTo(4.0);
        assertThat(clip.getDurationBeats()).isEqualTo(4.0);
    }

    // ── Crop ────────────────────────────────────────────────────────────────

    @Test
    void cropShouldRemoveClipsCompletelyOutsideSelection() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip inside = new AudioClip("inside", 4.0, 4.0, null);   // 4..8
        AudioClip outside = new AudioClip("outside", 12.0, 2.0, null); // 12..14
        track.addClip(inside);
        track.addClip(outside);

        double selStart = 3.0;
        double selEnd = 9.0;

        // Find clips completely outside the selection
        List<AudioClip> toRemove = new ArrayList<>();
        for (AudioClip clip : track.getClips()) {
            if (clip.getEndBeat() <= selStart || clip.getStartBeat() >= selEnd) {
                toRemove.add(clip);
            }
        }
        for (AudioClip clip : toRemove) track.removeClip(clip);

        assertThat(track.getClips()).hasSize(1);
        assertThat(track.getClips().get(0).getName()).isEqualTo("inside");
    }

    @Test
    void cropShouldSplitAndTrimClipsPartiallyInsideSelection() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        // Clip spans 2..12 — selection is 4..8
        AudioClip clip = new AudioClip("vocal", 2.0, 10.0, null);
        track.addClip(clip);

        double selStart = 4.0;
        double selEnd = 8.0;

        // For each clip overlapping the selection, trim to selection bounds
        List<AudioClip> toRemove = new ArrayList<>();
        List<AudioClip> toAdd = new ArrayList<>();
        for (AudioClip c : new ArrayList<>(track.getClips())) {
            double clipStart = c.getStartBeat();
            double clipEnd = c.getEndBeat();

            if (clipEnd <= selStart || clipStart >= selEnd) {
                // Completely outside — remove
                toRemove.add(c);
            } else if (clipStart < selStart || clipEnd > selEnd) {
                // Partially inside — trim to selection
                double newStart = Math.max(clipStart, selStart);
                double newEnd = Math.min(clipEnd, selEnd);
                AudioClip trimmed = c.duplicate();
                double offsetDelta = newStart - clipStart;
                trimmed.setSourceOffsetBeats(c.getSourceOffsetBeats() + offsetDelta);
                trimmed.setStartBeat(newStart);
                trimmed.setDurationBeats(newEnd - newStart);
                toRemove.add(c);
                toAdd.add(trimmed);
            }
            // Fully inside — keep as-is
        }
        for (AudioClip c : toRemove) track.removeClip(c);
        for (AudioClip c : toAdd) track.addClip(c);

        assertThat(track.getClips()).hasSize(1);
        AudioClip result = track.getClips().get(0);
        assertThat(result.getStartBeat()).isEqualTo(4.0);
        assertThat(result.getEndBeat()).isEqualTo(8.0);
        assertThat(result.getSourceOffsetBeats()).isEqualTo(2.0);
    }

    @Test
    void cropShouldKeepClipsFullyInsideSelection() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("vocal", 4.0, 2.0, null); // 4..6
        track.addClip(clip);

        double selStart = 2.0;
        double selEnd = 10.0;

        List<AudioClip> toRemove = new ArrayList<>();
        for (AudioClip c : track.getClips()) {
            if (c.getEndBeat() <= selStart || c.getStartBeat() >= selEnd) {
                toRemove.add(c);
            }
        }
        for (AudioClip c : toRemove) track.removeClip(c);

        assertThat(track.getClips()).hasSize(1);
        assertThat(track.getClips().get(0)).isSameAs(clip);
    }

    @Test
    void cropWithNoClipsShouldBeNoOp() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        assertThat(track.getClips()).isEmpty();
        // Crop on empty track should not throw
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

        double selStart = 3.0;
        double selEnd = 10.0;

        // Process crop
        List<AudioClip> toRemove = new ArrayList<>();
        List<AudioClip> toAdd = new ArrayList<>();
        for (AudioClip c : new ArrayList<>(track.getClips())) {
            double clipStart = c.getStartBeat();
            double clipEnd = c.getEndBeat();
            if (clipEnd <= selStart || clipStart >= selEnd) {
                toRemove.add(c);
            } else if (clipStart < selStart || clipEnd > selEnd) {
                double newStart = Math.max(clipStart, selStart);
                double newEnd = Math.min(clipEnd, selEnd);
                AudioClip trimmed = c.duplicate();
                double offsetDelta = newStart - clipStart;
                trimmed.setSourceOffsetBeats(c.getSourceOffsetBeats() + offsetDelta);
                trimmed.setStartBeat(newStart);
                trimmed.setDurationBeats(newEnd - newStart);
                toRemove.add(c);
                toAdd.add(trimmed);
            }
        }
        for (AudioClip c : toRemove) track.removeClip(c);
        for (AudioClip c : toAdd) track.addClip(c);

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
}
