package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.midi.MidiClip;
import com.benesquivelmusic.daw.core.midi.MidiNoteData;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

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

    // ── Additive rubber-band selection tests ────────────────────────────────

    @Test
    void addClipsInRegionShouldAddToExistingSelection() {
        SelectionModel model = new SelectionModel();
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip1 = new AudioClip("kick", 0.0, 4.0, null);
        AudioClip clip2 = new AudioClip("snare", 4.0, 4.0, null);
        AudioClip clip3 = new AudioClip("hat", 10.0, 2.0, null);
        track.addClip(clip1);
        track.addClip(clip2);
        track.addClip(clip3);

        // First select clip1
        model.selectClip(track, clip1);
        // Then additively select clips in region 9..13 (should include clip3)
        model.addClipsInRegion(List.of(track), 9.0, 13.0);

        assertThat(model.getSelectedClips()).hasSize(2);
        assertThat(model.isClipSelected(clip1)).isTrue();
        assertThat(model.isClipSelected(clip3)).isTrue();
        assertThat(model.isClipSelected(clip2)).isFalse();
    }

    @Test
    void addClipsInRegionShouldNotClearExisting() {
        SelectionModel model = new SelectionModel();
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip1 = new AudioClip("kick", 0.0, 4.0, null);
        AudioClip clip2 = new AudioClip("snare", 10.0, 4.0, null);
        track.addClip(clip1);
        track.addClip(clip2);

        model.selectClip(track, clip1);
        model.addClipsInRegion(List.of(track), 9.0, 15.0);

        assertThat(model.isClipSelected(clip1)).isTrue();
        assertThat(model.isClipSelected(clip2)).isTrue();
    }

    @Test
    void addClipsInRegionShouldRejectNullTracks() {
        SelectionModel model = new SelectionModel();
        assertThatThrownBy(() -> model.addClipsInRegion(null, 0.0, 5.0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void addClipsInRegionShouldRejectInvalidRegion() {
        SelectionModel model = new SelectionModel();
        assertThatThrownBy(() -> model.addClipsInRegion(List.of(), 5.0, 3.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addClipsInRegionWithNoOverlapShouldNotChangeSelection() {
        SelectionModel model = new SelectionModel();
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip1 = new AudioClip("kick", 0.0, 4.0, null);
        track.addClip(clip1);

        model.selectClip(track, clip1);
        model.addClipsInRegion(List.of(track), 10.0, 15.0);

        assertThat(model.getSelectedClips()).hasSize(1);
        assertThat(model.isClipSelected(clip1)).isTrue();
    }

    // ── MIDI clip selection tests ────────────────────────────────────────────

    @Test
    void selectMidiClipShouldSelectSingleMidiClip() {
        SelectionModel model = new SelectionModel();
        Track track = new Track("MIDI 1", TrackType.MIDI);
        MidiClip midiClip = track.getMidiClip();
        midiClip.addNote(MidiNoteData.of(60, 0, 4, 100));

        model.selectMidiClip(track, midiClip);

        assertThat(model.hasClipSelection()).isTrue();
        assertThat(model.isMidiClipSelected(midiClip)).isTrue();
    }

    @Test
    void selectMidiClipShouldClearPreviousAudioSelection() {
        SelectionModel model = new SelectionModel();
        Track audio = new Track("Audio 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        audio.addClip(clip);
        model.selectClip(audio, clip);

        Track midi = new Track("MIDI 1", TrackType.MIDI);
        MidiClip midiClip = midi.getMidiClip();
        midiClip.addNote(MidiNoteData.of(60, 0, 4, 100));

        model.selectMidiClip(midi, midiClip);

        assertThat(model.isClipSelected(clip)).isFalse();
        assertThat(model.isMidiClipSelected(midiClip)).isTrue();
    }

    @Test
    void toggleMidiClipSelectionShouldAddAndRemove() {
        SelectionModel model = new SelectionModel();
        Track track = new Track("MIDI 1", TrackType.MIDI);
        MidiClip midiClip = track.getMidiClip();
        midiClip.addNote(MidiNoteData.of(60, 0, 4, 100));

        model.toggleMidiClipSelection(track, midiClip);
        assertThat(model.isMidiClipSelected(midiClip)).isTrue();

        model.toggleMidiClipSelection(track, midiClip);
        assertThat(model.isMidiClipSelected(midiClip)).isFalse();
    }

    @Test
    void clearClipSelectionShouldClearMidiClips() {
        SelectionModel model = new SelectionModel();
        Track track = new Track("MIDI 1", TrackType.MIDI);
        MidiClip midiClip = track.getMidiClip();
        midiClip.addNote(MidiNoteData.of(60, 0, 4, 100));

        model.selectMidiClip(track, midiClip);
        model.clearClipSelection();

        assertThat(model.hasClipSelection()).isFalse();
        assertThat(model.isMidiClipSelected(midiClip)).isFalse();
    }

    @Test
    void selectClipsInRegionShouldIncludeMidiClips() {
        SelectionModel model = new SelectionModel();
        Track midi = new Track("MIDI 1", TrackType.MIDI);
        // Notes at columns 4–8 = beats 1.0–2.0
        midi.getMidiClip().addNote(MidiNoteData.of(60, 4, 4, 100));

        model.selectClipsInRegion(List.of(midi), 0.5, 2.5);

        assertThat(model.isMidiClipSelected(midi.getMidiClip())).isTrue();
    }

    @Test
    void selectClipsInRegionShouldExcludeNonOverlappingMidiClips() {
        SelectionModel model = new SelectionModel();
        Track midi = new Track("MIDI 1", TrackType.MIDI);
        // Notes at columns 0–4 = beats 0.0–1.0
        midi.getMidiClip().addNote(MidiNoteData.of(60, 0, 4, 100));

        model.selectClipsInRegion(List.of(midi), 5.0, 10.0);

        assertThat(model.isMidiClipSelected(midi.getMidiClip())).isFalse();
    }

    @Test
    void selectClipsInRegionShouldSelectBothAudioAndMidiClips() {
        SelectionModel model = new SelectionModel();
        Track audio = new Track("Audio 1", TrackType.AUDIO);
        AudioClip audioClip = new AudioClip("vocal", 0.0, 4.0, null);
        audio.addClip(audioClip);

        Track midi = new Track("MIDI 1", TrackType.MIDI);
        midi.getMidiClip().addNote(MidiNoteData.of(60, 4, 8, 100));

        model.selectClipsInRegion(List.of(audio, midi), 0.5, 3.0);

        assertThat(model.isClipSelected(audioClip)).isTrue();
        assertThat(model.isMidiClipSelected(midi.getMidiClip())).isTrue();
    }

    @Test
    void selectClipsInRegionShouldSkipEmptyMidiClips() {
        SelectionModel model = new SelectionModel();
        Track midi = new Track("MIDI 1", TrackType.MIDI);
        // No notes added — clip is empty

        model.selectClipsInRegion(List.of(midi), 0.0, 10.0);

        assertThat(model.isMidiClipSelected(midi.getMidiClip())).isFalse();
    }

    @Test
    void selectClipsInRegionShouldSkipNonMidiTracks() {
        SelectionModel model = new SelectionModel();
        Track audio = new Track("Audio 1", TrackType.AUDIO);
        // Audio tracks have a getMidiClip() too but it should not be selected
        audio.getMidiClip().addNote(MidiNoteData.of(60, 0, 4, 100));

        model.selectClipsInRegion(List.of(audio), 0.0, 10.0);

        assertThat(model.isMidiClipSelected(audio.getMidiClip())).isFalse();
    }

    @Test
    void addClipsInRegionShouldIncludeMidiClips() {
        SelectionModel model = new SelectionModel();
        Track audio = new Track("Audio 1", TrackType.AUDIO);
        AudioClip audioClip = new AudioClip("vocal", 0.0, 4.0, null);
        audio.addClip(audioClip);
        model.selectClip(audio, audioClip);

        Track midi = new Track("MIDI 1", TrackType.MIDI);
        midi.getMidiClip().addNote(MidiNoteData.of(60, 20, 8, 100));

        model.addClipsInRegion(List.of(midi), 4.0, 8.0);

        assertThat(model.isClipSelected(audioClip)).isTrue();
        assertThat(model.isMidiClipSelected(midi.getMidiClip())).isTrue();
    }

    @Test
    void selectClipShouldClearPreviousMidiSelection() {
        SelectionModel model = new SelectionModel();
        Track midi = new Track("MIDI 1", TrackType.MIDI);
        MidiClip midiClip = midi.getMidiClip();
        midiClip.addNote(MidiNoteData.of(60, 0, 4, 100));
        model.selectMidiClip(midi, midiClip);

        Track audio = new Track("Audio 1", TrackType.AUDIO);
        AudioClip audioClip = new AudioClip("kick", 0.0, 4.0, null);
        model.selectClip(audio, audioClip);

        assertThat(model.isMidiClipSelected(midiClip)).isFalse();
        assertThat(model.isClipSelected(audioClip)).isTrue();
    }

    @Test
    void midiClipStartBeatShouldComputeFromMinColumn() {
        MidiClip midiClip = new MidiClip();
        midiClip.addNote(MidiNoteData.of(60, 8, 4, 100));
        midiClip.addNote(MidiNoteData.of(64, 4, 4, 100));

        // Min start column is 4, so start beat is 4 * 0.25 = 1.0
        assertThat(SelectionModel.midiClipStartBeat(midiClip)).isCloseTo(1.0, offset(0.001));
    }

    @Test
    void midiClipEndBeatShouldComputeFromMaxEndColumn() {
        MidiClip midiClip = new MidiClip();
        midiClip.addNote(MidiNoteData.of(60, 0, 4, 100));
        midiClip.addNote(MidiNoteData.of(64, 4, 8, 100));

        // Max end column is 4 + 8 = 12, so end beat is 12 * 0.25 = 3.0
        assertThat(SelectionModel.midiClipEndBeat(midiClip)).isCloseTo(3.0, offset(0.001));
    }

    @Test
    void midiClipStartBeatShouldRejectEmptyClip() {
        MidiClip midiClip = new MidiClip();
        assertThatThrownBy(() -> SelectionModel.midiClipStartBeat(midiClip))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void midiClipEndBeatShouldRejectEmptyClip() {
        MidiClip midiClip = new MidiClip();
        assertThatThrownBy(() -> SelectionModel.midiClipEndBeat(midiClip))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Selection change listener ───────────────────────────────────────────

    @Test
    void selectClipShouldFireSelectionChangeListener() {
        SelectionModel model = new SelectionModel();
        int[] callCount = {0};
        model.setSelectionChangeListener(() -> callCount[0]++);
        Track track = new Track("T1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("c.wav", 0.0, 1.0, null);
        track.addClip(clip);
        model.selectClip(track, clip);
        assertThat(callCount[0]).isEqualTo(1);
    }

    @Test
    void toggleClipSelectionShouldFireSelectionChangeListener() {
        SelectionModel model = new SelectionModel();
        int[] callCount = {0};
        model.setSelectionChangeListener(() -> callCount[0]++);
        Track track = new Track("T1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("c.wav", 0.0, 1.0, null);
        track.addClip(clip);
        model.toggleClipSelection(track, clip);
        assertThat(callCount[0]).isEqualTo(1);
    }

    @Test
    void clearClipSelectionShouldFireSelectionChangeListener() {
        SelectionModel model = new SelectionModel();
        Track track = new Track("T1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("c.wav", 0.0, 1.0, null);
        track.addClip(clip);
        model.selectClip(track, clip);
        int[] callCount = {0};
        model.setSelectionChangeListener(() -> callCount[0]++);
        model.clearClipSelection();
        assertThat(callCount[0]).isEqualTo(1);
    }

    @Test
    void selectClipsInRegionShouldFireSelectionChangeListener() {
        SelectionModel model = new SelectionModel();
        int[] callCount = {0};
        model.setSelectionChangeListener(() -> callCount[0]++);
        Track track = new Track("T1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("c.wav", 1.0, 3.0, null);
        track.addClip(clip);
        model.selectClipsInRegion(List.of(track), 0.0, 4.0);
        assertThat(callCount[0]).isEqualTo(1);
    }

    @Test
    void addClipsInRegionShouldFireSelectionChangeListener() {
        SelectionModel model = new SelectionModel();
        int[] callCount = {0};
        model.setSelectionChangeListener(() -> callCount[0]++);
        Track track = new Track("T1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("c.wav", 1.0, 3.0, null);
        track.addClip(clip);
        model.addClipsInRegion(List.of(track), 0.0, 4.0);
        assertThat(callCount[0]).isEqualTo(1);
    }

    @Test
    void selectMidiClipShouldFireSelectionChangeListener() {
        SelectionModel model = new SelectionModel();
        int[] callCount = {0};
        model.setSelectionChangeListener(() -> callCount[0]++);
        Track track = new Track("M1", TrackType.MIDI);
        MidiClip midiClip = track.getMidiClip();
        midiClip.addNote(MidiNoteData.of(60, 0, 4, 100));
        model.selectMidiClip(track, midiClip);
        assertThat(callCount[0]).isEqualTo(1);
    }

    @Test
    void toggleMidiClipSelectionShouldFireSelectionChangeListener() {
        SelectionModel model = new SelectionModel();
        int[] callCount = {0};
        model.setSelectionChangeListener(() -> callCount[0]++);
        Track track = new Track("M1", TrackType.MIDI);
        MidiClip midiClip = track.getMidiClip();
        midiClip.addNote(MidiNoteData.of(60, 0, 4, 100));
        model.toggleMidiClipSelection(track, midiClip);
        assertThat(callCount[0]).isEqualTo(1);
    }

    @Test
    void nullListenerShouldNotThrow() {
        SelectionModel model = new SelectionModel();
        model.setSelectionChangeListener(null);
        Track track = new Track("T1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("c.wav", 0.0, 1.0, null);
        track.addClip(clip);
        model.selectClip(track, clip); // should not throw
    }

    // ── Track-level helpers (Issue 568) ────────────────────────────────────

    @Test
    void getFocusedTrackReturnsNullWhenNothingSelected() {
        assertThat(new SelectionModel().getFocusedTrack()).isNull();
    }

    @Test
    void getFocusedTrackReturnsTrackOfMostRecentlySelectedAudioClip() {
        SelectionModel model = new SelectionModel();
        Track t1 = new Track("T1", TrackType.AUDIO);
        Track t2 = new Track("T2", TrackType.AUDIO);
        AudioClip c1 = new AudioClip("c1.wav", 0.0, 1.0, null);
        AudioClip c2 = new AudioClip("c2.wav", 1.0, 1.0, null);
        t1.addClip(c1);
        t2.addClip(c2);
        model.selectClip(t1, c1);
        model.toggleClipSelection(t2, c2);

        assertThat(model.getFocusedTrack()).isSameAs(t2);
    }

    @Test
    void getFocusedTrackFallsBackToMidiClipTrack() {
        SelectionModel model = new SelectionModel();
        Track t = new Track("M", TrackType.MIDI);
        MidiClip clip = t.getMidiClip();
        clip.addNote(MidiNoteData.of(60, 0, 4, 100));
        model.selectMidiClip(t, clip);

        assertThat(model.getFocusedTrack()).isSameAs(t);
    }

    @Test
    void getTracksInClipSelectionReturnsDistinctTracksInOrder() {
        SelectionModel model = new SelectionModel();
        Track t1 = new Track("T1", TrackType.AUDIO);
        Track t2 = new Track("T2", TrackType.AUDIO);
        AudioClip a = new AudioClip("a.wav", 0, 1, null);
        AudioClip b = new AudioClip("b.wav", 0, 1, null);
        AudioClip c = new AudioClip("c.wav", 0, 1, null);
        t1.addClip(a); t1.addClip(c); t2.addClip(b);
        model.selectClip(t1, a);
        model.toggleClipSelection(t2, b);
        model.toggleClipSelection(t1, c); // second clip on t1 — track must not duplicate

        List<Track> tracks = model.getTracksInClipSelection();
        assertThat(tracks).containsExactly(t1, t2);
    }

    @Test
    void getTracksInClipSelectionIsEmptyWhenNothingSelected() {
        assertThat(new SelectionModel().getTracksInClipSelection()).isEmpty();
    }

    @Test
    void getFocusedTrackPrefersMostRecentRegardlessOfClipType() {
        // Mixed audio + MIDI selection: the last-toggled clip's track wins
        // even when an older audio clip would otherwise be returned.
        SelectionModel model = new SelectionModel();
        Track audioTrack = new Track("A", TrackType.AUDIO);
        Track midiTrack = new Track("M", TrackType.MIDI);
        AudioClip ac = new AudioClip("a.wav", 0, 1, null);
        audioTrack.addClip(ac);
        MidiClip mc = midiTrack.getMidiClip();
        mc.addNote(MidiNoteData.of(60, 0, 4, 100));

        model.selectClip(audioTrack, ac);
        model.toggleMidiClipSelection(midiTrack, mc);

        // MIDI was added last → focus must follow it, not the older audio.
        assertThat(model.getFocusedTrack()).isSameAs(midiTrack);

        // Toggling another audio clip on top brings focus back to audio.
        Track audioTrack2 = new Track("A2", TrackType.AUDIO);
        AudioClip ac2 = new AudioClip("a2.wav", 0, 1, null);
        audioTrack2.addClip(ac2);
        model.toggleClipSelection(audioTrack2, ac2);
        assertThat(model.getFocusedTrack()).isSameAs(audioTrack2);
    }

    @Test
    void getFocusedTrackFallsBackWhenFocusedClipDeselected() {
        SelectionModel model = new SelectionModel();
        Track t1 = new Track("T1", TrackType.AUDIO);
        Track t2 = new Track("T2", TrackType.AUDIO);
        AudioClip c1 = new AudioClip("c1.wav", 0, 1, null);
        AudioClip c2 = new AudioClip("c2.wav", 1, 1, null);
        t1.addClip(c1);
        t2.addClip(c2);
        model.toggleClipSelection(t1, c1);
        model.toggleClipSelection(t2, c2);
        // Now untoggle t2 — focus should fall back to t1's surviving entry.
        model.toggleClipSelection(t2, c2);
        assertThat(model.getFocusedTrack()).isSameAs(t1);
    }

    @Test
    void clearClipSelectionResetsFocus() {
        SelectionModel model = new SelectionModel();
        Track t = new Track("T", TrackType.AUDIO);
        AudioClip c = new AudioClip("c.wav", 0, 1, null);
        t.addClip(c);
        model.selectClip(t, c);
        assertThat(model.getFocusedTrack()).isSameAs(t);

        model.clearClipSelection();
        assertThat(model.getFocusedTrack()).isNull();
    }

    @Test
    void getFocusedTrackFallbackPrefersMostRecentSurvivingAudioOverOlderMidi() {
        // Older MIDI selection followed by a more recent audio selection;
        // when the audio clip is deselected, the surviving MIDI must take
        // focus. This guards against a fallback that always prefers MIDI
        // (or always prefers audio) regardless of true recency.
        SelectionModel model = new SelectionModel();
        Track midiTrack = new Track("M", TrackType.MIDI);
        Track audioTrack = new Track("A", TrackType.AUDIO);
        MidiClip mc = midiTrack.getMidiClip();
        mc.addNote(MidiNoteData.of(60, 0, 4, 100));
        AudioClip ac = new AudioClip("a.wav", 0, 1, null);
        audioTrack.addClip(ac);

        // MIDI first, then audio (audio is most recent).
        model.toggleMidiClipSelection(midiTrack, mc);
        model.toggleClipSelection(audioTrack, ac);
        assertThat(model.getFocusedTrack()).isSameAs(audioTrack);

        // Deselect the audio clip; the MIDI selection survives and must
        // become the new focus — the fallback is true cross-type recency,
        // not a hard-coded MIDI-first rule.
        model.toggleClipSelection(audioTrack, ac);
        assertThat(model.getFocusedTrack()).isSameAs(midiTrack);
    }

    @Test
    void getFocusedTrackFallbackPrefersMostRecentSurvivingMidiOverOlderAudio() {
        // The mirror case: older audio + newer MIDI; deselecting the MIDI
        // must surface the surviving audio. Guards the "audio first" bug.
        SelectionModel model = new SelectionModel();
        Track audioTrack = new Track("A", TrackType.AUDIO);
        Track midiTrack = new Track("M", TrackType.MIDI);
        AudioClip ac = new AudioClip("a.wav", 0, 1, null);
        audioTrack.addClip(ac);
        MidiClip mc = midiTrack.getMidiClip();
        mc.addNote(MidiNoteData.of(60, 0, 4, 100));

        model.toggleClipSelection(audioTrack, ac);
        model.toggleMidiClipSelection(midiTrack, mc);
        assertThat(model.getFocusedTrack()).isSameAs(midiTrack);

        model.toggleMidiClipSelection(midiTrack, mc);
        assertThat(model.getFocusedTrack()).isSameAs(audioTrack);
    }
}
