package com.benesquivelmusic.daw.core.audioimport;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportAudioClipActionTest {

    private DawProject project;

    @BeforeEach
    void setUp() {
        project = new DawProject("Test Project", AudioFormat.CD_QUALITY);
    }

    @Test
    void shouldHaveCorrectDescription() {
        Track track = project.createAudioTrack("Vocals");
        AudioClip clip = new AudioClip("vocals", 0.0, 4.0, "/audio/vocals.wav");

        ImportAudioClipAction action = new ImportAudioClipAction(project, track, clip, true);

        assertThat(action.description()).isEqualTo("Import Audio File");
    }

    @Test
    void shouldBeNoOpOnFirstExecute() {
        Track track = project.createAudioTrack("Drums");
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, "/audio/kick.wav");
        track.addClip(clip);

        // First execute should not duplicate the clip or track
        ImportAudioClipAction action = new ImportAudioClipAction(project, track, clip, true);
        action.execute();

        assertThat(track.getClips()).containsExactly(clip);
        assertThat(project.getTracks()).hasSize(1);
    }

    @Test
    void shouldRemoveClipOnUndo() {
        Track track = project.createAudioTrack("Bass");
        AudioClip clip = new AudioClip("bass", 0.0, 8.0, "/audio/bass.wav");
        track.addClip(clip);

        ImportAudioClipAction action = new ImportAudioClipAction(project, track, clip, false);
        action.execute(); // first execute — no-op
        action.undo();

        assertThat(track.getClips()).isEmpty();
    }

    @Test
    void shouldRemoveTrackOnUndoWhenTrackCreatedByImport() {
        Track track = project.createAudioTrack("Imported");
        AudioClip clip = new AudioClip("imported", 0.0, 4.0, "/audio/imported.wav");
        track.addClip(clip);

        ImportAudioClipAction action = new ImportAudioClipAction(project, track, clip, true);
        action.execute(); // first execute — no-op
        action.undo();

        assertThat(track.getClips()).isEmpty();
        assertThat(project.getTracks()).doesNotContain(track);
    }

    @Test
    void shouldNotRemoveTrackOnUndoWhenTrackNotCreatedByImport() {
        Track existingTrack = project.createAudioTrack("Existing");
        AudioClip clip = new AudioClip("sample", 4.0, 2.0, "/audio/sample.wav");
        existingTrack.addClip(clip);

        ImportAudioClipAction action = new ImportAudioClipAction(project, existingTrack, clip, false);
        action.execute(); // first execute — no-op
        action.undo();

        assertThat(existingTrack.getClips()).isEmpty();
        assertThat(project.getTracks()).contains(existingTrack);
    }

    @Test
    void shouldReAddClipAndTrackOnRedo() {
        Track track = project.createAudioTrack("Guitar");
        AudioClip clip = new AudioClip("guitar", 0.0, 4.0, "/audio/guitar.wav");
        track.addClip(clip);

        ImportAudioClipAction action = new ImportAudioClipAction(project, track, clip, true);
        action.execute(); // first execute — no-op
        action.undo();

        assertThat(track.getClips()).isEmpty();
        assertThat(project.getTracks()).doesNotContain(track);

        action.execute(); // redo

        assertThat(track.getClips()).containsExactly(clip);
        assertThat(project.getTracks()).contains(track);
    }

    @Test
    void shouldReAddClipOnRedoWithoutTrackCreation() {
        Track existingTrack = project.createAudioTrack("Existing");
        AudioClip clip = new AudioClip("sample", 0.0, 2.0, "/audio/sample.wav");
        existingTrack.addClip(clip);

        ImportAudioClipAction action = new ImportAudioClipAction(project, existingTrack, clip, false);
        action.execute(); // first execute — no-op
        action.undo();
        action.execute(); // redo

        assertThat(existingTrack.getClips()).containsExactly(clip);
        assertThat(project.getTracks()).contains(existingTrack);
    }

    @Test
    void shouldWorkWithUndoManager() {
        Track track = project.createAudioTrack("Keys");
        AudioClip clip = new AudioClip("keys", 0.0, 4.0, "/audio/keys.wav");
        track.addClip(clip);
        UndoManager undoManager = new UndoManager();

        undoManager.execute(new ImportAudioClipAction(project, track, clip, true));
        assertThat(track.getClips()).containsExactly(clip);
        assertThat(project.getTracks()).contains(track);

        undoManager.undo();
        assertThat(track.getClips()).isEmpty();
        assertThat(project.getTracks()).doesNotContain(track);

        undoManager.redo();
        assertThat(track.getClips()).containsExactly(clip);
        assertThat(project.getTracks()).contains(track);
    }

    @Test
    void shouldExposeTrackAndClip() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);

        ImportAudioClipAction action = new ImportAudioClipAction(project, track, clip, true);

        assertThat(action.getTrack()).isSameAs(track);
        assertThat(action.getClip()).isSameAs(clip);
        assertThat(action.isTrackCreatedByImport()).isTrue();
    }

    @Test
    void shouldRejectNullProject() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);

        assertThatThrownBy(() -> new ImportAudioClipAction(null, track, clip, true))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullTrack() {
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);

        assertThatThrownBy(() -> new ImportAudioClipAction(project, null, clip, true))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullClip() {
        Track track = new Track("Drums", TrackType.AUDIO);

        assertThatThrownBy(() -> new ImportAudioClipAction(project, track, null, true))
                .isInstanceOf(NullPointerException.class);
    }
}
