package com.benesquivelmusic.daw.core.clip;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.CrossTrackMoveAction;
import com.benesquivelmusic.daw.core.audio.GroupMoveClipsAction;
import com.benesquivelmusic.daw.core.audio.MoveClipAction;
import com.benesquivelmusic.daw.core.audio.NudgeClipsAction;
import com.benesquivelmusic.daw.core.audio.SlipClipAction;
import com.benesquivelmusic.daw.core.midi.MidiClip;
import com.benesquivelmusic.daw.core.midi.MidiNoteData;
import com.benesquivelmusic.daw.core.midi.SlipMidiClipAction;
import com.benesquivelmusic.daw.core.project.edit.NudgeService;
import com.benesquivelmusic.daw.core.project.edit.RippleEditService;
import com.benesquivelmusic.daw.core.project.edit.SlipEditService;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.sdk.edit.RippleMode;

import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests that every position-changing operation refuses to mutate a
 * locked clip. Verifies the goals from the user story
 * "Clip-Level Time Lock Preventing Accidental Movement".
 */
class ClipTimeLockTest {

    @Test
    void audioClipIsUnlockedByDefault() {
        AudioClip clip = new AudioClip("a", 0.0, 4.0, null);
        assertThat(clip.isLocked()).isFalse();
    }

    @Test
    void midiClipIsUnlockedByDefault() {
        assertThat(new MidiClip().isLocked()).isFalse();
    }

    @Test
    void moveClipActionRefusesLockedClip() {
        AudioClip clip = new AudioClip("a", 0.0, 4.0, null);
        clip.setLocked(true);
        MoveClipAction action = new MoveClipAction(clip, 8.0);

        assertThatThrownBy(action::execute)
                .isInstanceOf(LockedClipException.class)
                .hasMessageContaining("Move");
        assertThat(clip.getStartBeat()).isEqualTo(0.0);
    }

    @Test
    void crossTrackMoveActionRefusesLockedClip() {
        Track src = new Track("s", TrackType.AUDIO);
        Track dst = new Track("d", TrackType.AUDIO);
        AudioClip clip = new AudioClip("a", 0.0, 4.0, null);
        src.addClip(clip);
        clip.setLocked(true);

        CrossTrackMoveAction action = new CrossTrackMoveAction(src, dst, clip, 8.0);
        assertThatThrownBy(action::execute).isInstanceOf(LockedClipException.class);

        // Original state preserved
        assertThat(src.getClips()).containsExactly(clip);
        assertThat(dst.getClips()).isEmpty();
        assertThat(clip.getStartBeat()).isEqualTo(0.0);
    }

    @Test
    void groupMoveActionIsAtomicallyRefusedWhenAnyClipIsLocked() {
        Track t = new Track("t", TrackType.AUDIO);
        AudioClip a = new AudioClip("a", 0.0, 4.0, null);
        AudioClip b = new AudioClip("b", 8.0, 4.0, null);
        b.setLocked(true);
        t.addClip(a);
        t.addClip(b);

        GroupMoveClipsAction action = new GroupMoveClipsAction(
                List.of(Map.entry(t, a), Map.entry(t, b)),
                2.0, 0, List.of(t));

        assertThatThrownBy(action::execute)
                .isInstanceOf(LockedClipException.class);

        // Atomic: no clip moved, including the unlocked one.
        assertThat(a.getStartBeat()).isEqualTo(0.0);
        assertThat(b.getStartBeat()).isEqualTo(8.0);
    }

    @Test
    void nudgeClipsActionRefusesWhenAnyClipLocked() {
        AudioClip a = new AudioClip("a", 4.0, 4.0, null);
        AudioClip b = new AudioClip("b", 8.0, 4.0, null);
        a.setLocked(true);

        NudgeClipsAction action = new NudgeClipsAction(List.of(a, b), 1.0);
        assertThatThrownBy(action::execute).isInstanceOf(LockedClipException.class);
        assertThat(a.getStartBeat()).isEqualTo(4.0);
        assertThat(b.getStartBeat()).isEqualTo(8.0);
    }

    @Test
    void slipClipActionRefusesLockedClip() {
        AudioClip clip = new AudioClip("a", 0.0, 4.0, null);
        clip.setLocked(true);
        SlipClipAction action = new SlipClipAction(clip, 1.0);

        assertThatThrownBy(action::execute).isInstanceOf(LockedClipException.class);
        assertThat(clip.getSourceOffsetBeats()).isEqualTo(0.0);
    }

    @Test
    void slipMidiClipActionRefusesLockedClip() {
        MidiClip clip = new MidiClip();
        clip.addNote(new MidiNoteData(60, 4, 4, 100, 0));
        clip.setLocked(true);
        SlipMidiClipAction action = new SlipMidiClipAction(clip, 2);

        assertThatThrownBy(action::execute).isInstanceOf(LockedClipException.class);
        assertThat(clip.getNote(0).startColumn()).isEqualTo(4);
    }

    @Test
    void nudgeServiceBuildActionRefusesLockedClip() {
        AudioClip clip = new AudioClip("a", 4.0, 4.0, null);
        clip.setLocked(true);
        assertThatThrownBy(() -> NudgeService.buildAction(List.of(clip), 1.0))
                .isInstanceOf(LockedClipException.class);
    }

    @Test
    void slipEditServiceRefusesLockedAudioClip() {
        AudioClip clip = new AudioClip("a", 0.0, 4.0, null);
        clip.setLocked(true);
        assertThatThrownBy(() -> SlipEditService.buildAudioSlip(clip, 1.0, 16.0))
                .isInstanceOf(LockedClipException.class);
    }

    @Test
    void slipEditServiceRefusesLockedMidiClip() {
        MidiClip clip = new MidiClip();
        clip.addNote(new MidiNoteData(60, 4, 4, 100, 0));
        clip.setLocked(true);
        assertThatThrownBy(() -> SlipEditService.buildMidiSlip(clip, 1))
                .isInstanceOf(LockedClipException.class);
    }

    @Test
    void rippleMoveRefusesLockedMovingClip() {
        Track t = new Track("t", TrackType.AUDIO);
        AudioClip a = new AudioClip("a", 0.0, 4.0, null);
        a.setLocked(true);
        t.addClip(a);

        assertThatThrownBy(() -> RippleEditService.buildRippleMove(
                a, t, 8.0, RippleMode.PER_TRACK, List.of(t),
                OptionalDouble.empty(), OptionalDouble.empty()))
                .isInstanceOf(LockedClipException.class);
    }

    @Test
    void rippleMoveRefusesWhenADownstreamLockedClipWouldShift() {
        Track t = new Track("t", TrackType.AUDIO);
        AudioClip moving = new AudioClip("a", 0.0, 4.0, null);
        AudioClip downstream = new AudioClip("b", 8.0, 4.0, null);
        downstream.setLocked(true);
        t.addClip(moving);
        t.addClip(downstream);

        assertThatThrownBy(() -> RippleEditService.buildRippleMove(
                moving, t, 2.0, RippleMode.PER_TRACK, List.of(t),
                OptionalDouble.empty(), OptionalDouble.empty()))
                .isInstanceOf(LockedClipException.class);
    }

    @Test
    void rippleDeleteRefusesWhenADownstreamLockedClipWouldShift() {
        Track t = new Track("t", TrackType.AUDIO);
        AudioClip toDelete = new AudioClip("a", 0.0, 4.0, null);
        AudioClip downstream = new AudioClip("b", 8.0, 4.0, null);
        downstream.setLocked(true);
        t.addClip(toDelete);
        t.addClip(downstream);

        Map.Entry<Track, AudioClip> entry = new AbstractMap.SimpleEntry<>(t, toDelete);
        assertThatThrownBy(() -> RippleEditService.buildRippleDelete(
                List.of(entry), RippleMode.PER_TRACK, List.of(t),
                OptionalDouble.empty(), OptionalDouble.empty()))
                .isInstanceOf(LockedClipException.class);
    }

    @Test
    void unlockingClipRestoresMobility() {
        AudioClip clip = new AudioClip("a", 0.0, 4.0, null);
        clip.setLocked(true);
        new SetClipLockedAction(clip, false).execute();

        // Unlocked: move now succeeds.
        new MoveClipAction(clip, 8.0).execute();
        assertThat(clip.getStartBeat()).isEqualTo(8.0);
    }

    @Test
    void setClipLockedActionTogglesAndUndoes() {
        AudioClip clip = new AudioClip("a", 0.0, 4.0, null);
        SetClipLockedAction lock = new SetClipLockedAction(clip, true);

        assertThat(lock.description()).isEqualTo("Lock Clip");
        lock.execute();
        assertThat(clip.isLocked()).isTrue();
        lock.undo();
        assertThat(clip.isLocked()).isFalse();
    }

    @Test
    void setClipLockedActionWorksOnMidiClips() {
        MidiClip clip = new MidiClip();
        SetClipLockedAction action = new SetClipLockedAction(clip, true);
        action.execute();
        assertThat(clip.isLocked()).isTrue();
        action.undo();
        assertThat(clip.isLocked()).isFalse();
    }

    @Test
    void duplicatePreservesLockedFlag() {
        AudioClip clip = new AudioClip("a", 0.0, 4.0, null);
        clip.setLocked(true);
        AudioClip copy = clip.duplicate();
        assertThat(copy.isLocked()).isTrue();
    }

    @Test
    void splitInheritsLockedFlag() {
        AudioClip clip = new AudioClip("a", 0.0, 8.0, null);
        clip.setLocked(true);
        AudioClip second = clip.splitAt(4.0);
        assertThat(clip.isLocked()).isTrue();
        assertThat(second.isLocked()).isTrue();
    }

    @Test
    void lockedClipMessageIsSingularForOneClip() {
        AudioClip clip = new AudioClip("a", 0.0, 4.0, null);
        clip.setLocked(true);
        assertThatThrownBy(() -> new MoveClipAction(clip, 8.0).execute())
                .isInstanceOf(LockedClipException.class)
                .hasMessageContaining("1 clip is time-locked");
    }

    @Test
    void lockedClipMessageIsPluralForMultiple() {
        AudioClip a = new AudioClip("a", 0.0, 4.0, null);
        AudioClip b = new AudioClip("b", 8.0, 4.0, null);
        a.setLocked(true);
        b.setLocked(true);
        assertThatThrownBy(() -> new NudgeClipsAction(List.of(a, b), 1.0).execute())
                .isInstanceOf(LockedClipException.class)
                .hasMessageContaining("2 clips are time-locked");
    }
}
