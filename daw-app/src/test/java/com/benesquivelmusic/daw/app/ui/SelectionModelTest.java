package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

class SelectionModelTest {

    // ── Time selection (existing tests) ─────────────────────────────────────

    @Test
    void shouldStartWithNoSelection() {
        SelectionModel model = new SelectionModel();
        assertThat(model.hasSelection()).isFalse();
    }

    @Test
    void setSelectionShouldActivateSelection() {
        SelectionModel model = new SelectionModel();
        model.setSelection(1.0, 5.0);
        assertThat(model.hasSelection()).isTrue();
        assertThat(model.getStartBeat()).isCloseTo(1.0, offset(0.001));
        assertThat(model.getEndBeat()).isCloseTo(5.0, offset(0.001));
    }

    @Test
    void clearSelectionShouldDeactivateSelection() {
        SelectionModel model = new SelectionModel();
        model.setSelection(1.0, 5.0);
        model.clearSelection();
        assertThat(model.hasSelection()).isFalse();
    }

    @Test
    void setSelectionShouldRejectStartEqualToEnd() {
        SelectionModel model = new SelectionModel();
        assertThatThrownBy(() -> model.setSelection(3.0, 3.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setSelectionShouldRejectStartGreaterThanEnd() {
        SelectionModel model = new SelectionModel();
        assertThatThrownBy(() -> model.setSelection(5.0, 3.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clearOnNoSelectionShouldBeNoOp() {
        SelectionModel model = new SelectionModel();
        model.clearSelection();
        assertThat(model.hasSelection()).isFalse();
    }

    @Test
    void setSelectionMultipleTimesShouldUpdateRange() {
        SelectionModel model = new SelectionModel();
        model.setSelection(1.0, 5.0);
        model.setSelection(2.0, 8.0);
        assertThat(model.hasSelection()).isTrue();
        assertThat(model.getStartBeat()).isCloseTo(2.0, offset(0.001));
        assertThat(model.getEndBeat()).isCloseTo(8.0, offset(0.001));
    }

    @Test
    void defaultBeatsShouldBeZero() {
        SelectionModel model = new SelectionModel();
        assertThat(model.getStartBeat()).isCloseTo(0.0, offset(0.001));
        assertThat(model.getEndBeat()).isCloseTo(0.0, offset(0.001));
    }

    // ── Clip selection tests ────────────────────────────────────────────────

    @Test
    void shouldStartWithNoClipSelection() {
        SelectionModel model = new SelectionModel();
        assertThat(model.hasClipSelection()).isFalse();
        assertThat(model.getSelectedClips()).isEmpty();
    }

    @Test
    void selectClipShouldSelectSingleClip() {
        SelectionModel model = new SelectionModel();
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);

        model.selectClip(track, clip);

        assertThat(model.hasClipSelection()).isTrue();
        assertThat(model.getSelectedClips()).hasSize(1);
        assertThat(model.getSelectedClips().get(0).clip()).isSameAs(clip);
        assertThat(model.getSelectedClips().get(0).sourceTrack()).isSameAs(track);
    }

    @Test
    void selectClipShouldClearPreviousSelection() {
        SelectionModel model = new SelectionModel();
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip1 = new AudioClip("kick", 0.0, 4.0, null);
        AudioClip clip2 = new AudioClip("snare", 4.0, 4.0, null);

        model.selectClip(track, clip1);
        model.selectClip(track, clip2);

        assertThat(model.getSelectedClips()).hasSize(1);
        assertThat(model.getSelectedClips().get(0).clip()).isSameAs(clip2);
    }

    @Test
    void toggleClipSelectionShouldAddToSelection() {
        SelectionModel model = new SelectionModel();
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip1 = new AudioClip("kick", 0.0, 4.0, null);
        AudioClip clip2 = new AudioClip("snare", 4.0, 4.0, null);

        model.selectClip(track, clip1);
        model.toggleClipSelection(track, clip2);

        assertThat(model.getSelectedClips()).hasSize(2);
    }

    @Test
    void toggleClipSelectionShouldDeselectIfAlreadySelected() {
        SelectionModel model = new SelectionModel();
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);

        model.selectClip(track, clip);
        model.toggleClipSelection(track, clip);

        assertThat(model.hasClipSelection()).isFalse();
    }

    @Test
    void isClipSelectedShouldReturnTrueForSelectedClip() {
        SelectionModel model = new SelectionModel();
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);

        model.selectClip(track, clip);

        assertThat(model.isClipSelected(clip)).isTrue();
    }

    @Test
    void isClipSelectedShouldReturnFalseForUnselectedClip() {
        SelectionModel model = new SelectionModel();
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);

        assertThat(model.isClipSelected(clip)).isFalse();
    }

    @Test
    void clearClipSelectionShouldDeselectAll() {
        SelectionModel model = new SelectionModel();
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);

        model.selectClip(track, clip);
        model.clearClipSelection();

        assertThat(model.hasClipSelection()).isFalse();
        assertThat(model.getSelectedClips()).isEmpty();
    }

    @Test
    void selectClipShouldRejectNullTrack() {
        SelectionModel model = new SelectionModel();
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        assertThatThrownBy(() -> model.selectClip(null, clip))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void selectClipShouldRejectNullClip() {
        SelectionModel model = new SelectionModel();
        Track track = new Track("Drums", TrackType.AUDIO);
        assertThatThrownBy(() -> model.selectClip(track, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void toggleClipSelectionShouldRejectNullTrack() {
        SelectionModel model = new SelectionModel();
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        assertThatThrownBy(() -> model.toggleClipSelection(null, clip))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void toggleClipSelectionShouldRejectNullClip() {
        SelectionModel model = new SelectionModel();
        Track track = new Track("Drums", TrackType.AUDIO);
        assertThatThrownBy(() -> model.toggleClipSelection(track, null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── Rubber-band selection tests ─────────────────────────────────────────

    @Test
    void selectClipsInRegionShouldSelectOverlappingClips() {
        SelectionModel model = new SelectionModel();
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip1 = new AudioClip("kick", 0.0, 4.0, null);
        AudioClip clip2 = new AudioClip("snare", 4.0, 4.0, null);
        AudioClip clip3 = new AudioClip("hat", 10.0, 2.0, null);
        track.addClip(clip1);
        track.addClip(clip2);
        track.addClip(clip3);

        model.selectClipsInRegion(List.of(track), 3.0, 5.0);

        assertThat(model.hasClipSelection()).isTrue();
        assertThat(model.getSelectedClips()).hasSize(2);
        assertThat(model.isClipSelected(clip1)).isTrue();
        assertThat(model.isClipSelected(clip2)).isTrue();
        assertThat(model.isClipSelected(clip3)).isFalse();
    }

    @Test
    void selectClipsInRegionShouldClearPreviousSelection() {
        SelectionModel model = new SelectionModel();
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip1 = new AudioClip("kick", 0.0, 4.0, null);
        AudioClip clip2 = new AudioClip("snare", 10.0, 4.0, null);
        track.addClip(clip1);
        track.addClip(clip2);

        model.selectClip(track, clip2);
        model.selectClipsInRegion(List.of(track), 0.0, 5.0);

        assertThat(model.getSelectedClips()).hasSize(1);
        assertThat(model.isClipSelected(clip1)).isTrue();
        assertThat(model.isClipSelected(clip2)).isFalse();
    }

    @Test
    void selectClipsInRegionShouldWorkAcrossMultipleTracks() {
        SelectionModel model = new SelectionModel();
        Track drums = new Track("Drums", TrackType.AUDIO);
        Track bass = new Track("Bass", TrackType.AUDIO);
        AudioClip kick = new AudioClip("kick", 0.0, 4.0, null);
        AudioClip bassLine = new AudioClip("bass", 2.0, 6.0, null);
        drums.addClip(kick);
        bass.addClip(bassLine);

        model.selectClipsInRegion(List.of(drums, bass), 1.0, 3.0);

        assertThat(model.getSelectedClips()).hasSize(2);
        assertThat(model.isClipSelected(kick)).isTrue();
        assertThat(model.isClipSelected(bassLine)).isTrue();
    }

    @Test
    void selectClipsInRegionShouldSelectNothingWhenNoOverlap() {
        SelectionModel model = new SelectionModel();
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        track.addClip(clip);

        model.selectClipsInRegion(List.of(track), 5.0, 10.0);

        assertThat(model.hasClipSelection()).isFalse();
    }

    @Test
    void selectClipsInRegionShouldRejectNullTracks() {
        SelectionModel model = new SelectionModel();
        assertThatThrownBy(() -> model.selectClipsInRegion(null, 0.0, 5.0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void selectClipsInRegionShouldRejectInvalidRegion() {
        SelectionModel model = new SelectionModel();
        assertThatThrownBy(() -> model.selectClipsInRegion(List.of(), 5.0, 3.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getSelectedClipsShouldReturnUnmodifiableList() {
        SelectionModel model = new SelectionModel();
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        model.selectClip(track, clip);

        assertThatThrownBy(() -> model.getSelectedClips().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
