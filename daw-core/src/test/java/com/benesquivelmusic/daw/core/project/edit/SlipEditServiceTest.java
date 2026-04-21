package com.benesquivelmusic.daw.core.project.edit;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.midi.MidiClip;
import com.benesquivelmusic.daw.core.midi.MidiNoteData;
import com.benesquivelmusic.daw.core.project.edit.SlipEditService.SlipResult;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for {@link SlipEditService}. Validates clamping at both source edges
 * for audio clips, clamping against negative start columns for MIDI clips,
 * the {@code hitEdge} flag, and no-op behaviour.
 *
 * <p>Story 139 — {@code docs/user-stories/139-slip-edit-within-clip.md}.</p>
 */
class SlipEditServiceTest {

    private static final double EPSILON = 1e-6;

    // ── Audio slip ─────────────────────────────────────────────────────────

    @Test
    void audioSlipWithinBoundsAppliesUnchangedDelta() {
        AudioClip clip = new AudioClip("vox", 0.0, 4.0, null);
        clip.setSourceOffsetBeats(2.0);

        SlipResult result = SlipEditService.buildAudioSlip(clip, 1.5, 10.0);

        assertThat(result.hasAction()).isTrue();
        assertThat(result.appliedBeatDelta()).isEqualTo(1.5, within(EPSILON));
        assertThat(result.hitEdge()).isFalse();

        result.action().execute();
        assertThat(clip.getSourceOffsetBeats()).isEqualTo(3.5, within(EPSILON));
    }

    @Test
    void audioSlipClampsAtZeroAndFlagsHitEdge() {
        AudioClip clip = new AudioClip("vox", 0.0, 4.0, null);
        clip.setSourceOffsetBeats(1.0);

        // Requested -3.0 would push to -2.0; clamp to 0.0.
        SlipResult result = SlipEditService.buildAudioSlip(clip, -3.0, 10.0);

        assertThat(result.hasAction()).isTrue();
        assertThat(result.appliedBeatDelta()).isEqualTo(-1.0, within(EPSILON));
        assertThat(result.hitEdge()).isTrue();

        result.action().execute();
        assertThat(clip.getSourceOffsetBeats()).isEqualTo(0.0, within(EPSILON));
    }

    @Test
    void audioSlipClampsAtSourceEndAndFlagsHitEdge() {
        // sourceLength = 10, duration = 4 → maxOffset = 6
        AudioClip clip = new AudioClip("vox", 0.0, 4.0, null);
        clip.setSourceOffsetBeats(5.0);

        SlipResult result = SlipEditService.buildAudioSlip(clip, 3.0, 10.0);

        assertThat(result.hasAction()).isTrue();
        assertThat(result.appliedBeatDelta()).isEqualTo(1.0, within(EPSILON));
        assertThat(result.hitEdge()).isTrue();

        result.action().execute();
        assertThat(clip.getSourceOffsetBeats()).isEqualTo(6.0, within(EPSILON));
    }

    @Test
    void audioSlipWithUnknownSourceLengthTreatsUpperBoundAsUnbounded() {
        AudioClip clip = new AudioClip("vox", 0.0, 4.0, null);
        clip.setSourceOffsetBeats(2.0);

        // sourceLengthBeats = 0 → upper bound unbounded; only lower bound enforced.
        SlipResult result = SlipEditService.buildAudioSlip(clip, 100.0, 0.0);

        assertThat(result.hasAction()).isTrue();
        assertThat(result.appliedBeatDelta()).isEqualTo(100.0, within(EPSILON));
        assertThat(result.hitEdge()).isFalse();
    }

    @Test
    void audioSlipWithSourceShorterThanDurationStillClampsAtZero() {
        // Pathological case: source shorter than clip duration → upper bound unbounded.
        AudioClip clip = new AudioClip("vox", 0.0, 4.0, null);
        clip.setSourceOffsetBeats(0.5);

        SlipResult result = SlipEditService.buildAudioSlip(clip, -2.0, 2.0);

        assertThat(result.hasAction()).isTrue();
        assertThat(result.appliedBeatDelta()).isEqualTo(-0.5, within(EPSILON));
        assertThat(result.hitEdge()).isTrue();
    }

    @Test
    void audioSlipWithZeroNetDeltaProducesNoAction() {
        AudioClip clip = new AudioClip("vox", 0.0, 4.0, null);
        clip.setSourceOffsetBeats(0.0);

        // Requesting -1.0 at offset 0.0 clamps to 0.0 → net delta 0.
        SlipResult result = SlipEditService.buildAudioSlip(clip, -1.0, 10.0);

        assertThat(result.hasAction()).isFalse();
        assertThat(result.action()).isNull();
        assertThat(result.appliedBeatDelta()).isEqualTo(0.0);
        assertThat(result.hitEdge()).isTrue();
    }

    @Test
    void audioSlipRejectsNullClip() {
        assertThatNullPointerException()
                .isThrownBy(() -> SlipEditService.buildAudioSlip(null, 1.0, 10.0));
    }

    // ── MIDI slip ──────────────────────────────────────────────────────────

    @Test
    void midiSlipWithinBoundsShiftsAllNotes() {
        MidiClip clip = new MidiClip();
        clip.addNote(MidiNoteData.of(60, 4, 2, 100));
        clip.addNote(MidiNoteData.of(62, 8, 2, 100));

        SlipResult result = SlipEditService.buildMidiSlip(clip, 3);

        assertThat(result.hasAction()).isTrue();
        assertThat(result.appliedBeatDelta()).isEqualTo(0.75, within(EPSILON));
        assertThat(result.hitEdge()).isFalse();

        result.action().execute();
        assertThat(clip.getNote(0).startColumn()).isEqualTo(7);
        assertThat(clip.getNote(1).startColumn()).isEqualTo(11);
    }

    @Test
    void midiSlipClampsNegativeDeltaAtEarliestNote() {
        MidiClip clip = new MidiClip();
        clip.addNote(MidiNoteData.of(60, 2, 1, 100));
        clip.addNote(MidiNoteData.of(62, 6, 1, 100));

        // Requested -5 but earliest note is at 2 → clamp to -2.
        SlipResult result = SlipEditService.buildMidiSlip(clip, -5);

        assertThat(result.hasAction()).isTrue();
        assertThat(result.appliedBeatDelta()).isEqualTo(-0.5, within(EPSILON));
        assertThat(result.hitEdge()).isTrue();

        result.action().execute();
        assertThat(clip.getNote(0).startColumn()).isEqualTo(0);
        assertThat(clip.getNote(1).startColumn()).isEqualTo(4);
    }

    @Test
    void midiSlipPositiveDeltaIsUnbounded() {
        MidiClip clip = new MidiClip();
        clip.addNote(MidiNoteData.of(60, 0, 1, 100));

        SlipResult result = SlipEditService.buildMidiSlip(clip, 1_000);

        assertThat(result.hasAction()).isTrue();
        assertThat(result.appliedBeatDelta()).isEqualTo(250.0, within(EPSILON));
        assertThat(result.hitEdge()).isFalse();
    }

    @Test
    void midiSlipWithZeroDeltaIsNoOp() {
        MidiClip clip = new MidiClip();
        clip.addNote(MidiNoteData.of(60, 4, 1, 100));

        SlipResult result = SlipEditService.buildMidiSlip(clip, 0);

        assertThat(result.hasAction()).isFalse();
        assertThat(result.appliedBeatDelta()).isEqualTo(0.0);
        assertThat(result.hitEdge()).isFalse();
    }

    @Test
    void midiSlipOnEmptyClipIsNoOp() {
        MidiClip clip = new MidiClip();

        SlipResult result = SlipEditService.buildMidiSlip(clip, 5);

        assertThat(result.hasAction()).isFalse();
        assertThat(result.appliedBeatDelta()).isEqualTo(0.0);
        assertThat(result.hitEdge()).isFalse();
    }

    @Test
    void midiSlipWhereClampingCollapsesToZeroReportsHitEdgeNoAction() {
        MidiClip clip = new MidiClip();
        clip.addNote(MidiNoteData.of(60, 0, 1, 100));

        // Note already at column 0; cannot slip negative further.
        SlipResult result = SlipEditService.buildMidiSlip(clip, -3);

        assertThat(result.hasAction()).isFalse();
        assertThat(result.appliedBeatDelta()).isEqualTo(0.0);
        assertThat(result.hitEdge()).isTrue();
    }

    @Test
    void midiSlipRejectsNullClip() {
        assertThatNullPointerException()
                .isThrownBy(() -> SlipEditService.buildMidiSlip(null, 1));
    }
}
