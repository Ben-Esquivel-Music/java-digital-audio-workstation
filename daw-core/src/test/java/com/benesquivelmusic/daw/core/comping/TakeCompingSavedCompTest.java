package com.benesquivelmusic.daw.core.comping;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The side-effect-free "comp as saved" seam (PR #984 review): what a project
 * writer saves for a comped track mid-audition, and which live clips carry
 * the saved references — without advancing the pass or touching any live
 * state.
 */
class TakeCompingSavedCompTest {

    private Track track;
    private TakeComping comping;
    private AudioClip lane0Take;
    private AudioClip lane1Take;

    @BeforeEach
    void setUp() {
        track = new Track("Vox", TrackType.AUDIO);
        comping = track.getTakeComping();
        lane0Take = take("Take 1", "/takes/take-1/segment-000.wav");
        lane1Take = take("Take 2", "/takes/take-2/segment-000.wav");
        TakeLane lane0 = new TakeLane("Take 1");
        lane0.addClip(lane0Take);
        TakeLane lane1 = new TakeLane("Take 2");
        lane1.addClip(lane1Take);
        comping.addTakeLane(lane0);
        comping.addTakeLane(lane1);
        comping.addCompRegion(new CompRegion(0, 2.0, 3.0));
        new CompileCompAction(track).execute();
    }

    @Test
    void compileSelectionIgnoresTheSoloAndLeavesTheLivePassCurrent() {
        audition(1);
        List<AudioClip> auditionClips = List.copyOf(track.getClips());

        List<AudioClip> selection = comping.compileSelection();

        assertThat(selection).hasSize(1);
        AudioClip slice = selection.getFirst();
        assertThat(slice.getStartBeat()).isEqualTo(2.0);
        assertThat(slice.getDurationBeats()).isEqualTo(3.0);
        assertThat(slice.getSourceOffsetBeats()).isEqualTo(2.0);
        assertThat(slice.getSourceSegmentPaths()).isEqualTo(lane0Take.getSourceSegmentPaths());
        assertThat(slice.compOutputOwner()).as("detached: no pass tag").isEmpty();
        assertThat(comping.isCompOutput(slice)).isFalse();
        // The live audition clips are still current output: nothing superseded them.
        assertThat(track.getClips()).containsExactlyElementsOf(auditionClips);
        for (AudioClip live : auditionClips) {
            assertThat(comping.isCompOutput(live)).isTrue();
            assertThat(comping.isSupersededOutput(live)).isFalse();
        }
        assertThat(comping.getTakeLane(1).isSoloed()).isTrue();
    }

    @Test
    void isAuditioningFollowsTheSolo() {
        assertThat(comping.isAuditioning()).isFalse();
        audition(1);
        assertThat(comping.isAuditioning()).isTrue();
        comping.endAuditions();
        assertThat(comping.isAuditioning()).isFalse();
    }

    @Test
    void notAuditioningTheSavedClipsAreTheTracksClips() {
        AudioClip user = new AudioClip("User", 3.0, 1.0, "/media/user.wav");
        track.addClip(user);

        assertThat(comping.clipsAsSaved(track)).containsExactlyElementsOf(track.getClips());
        assertThat(comping.referenceHoldersAsSaved(track)).containsExactlyElementsOf(track.getClips());
    }

    @Test
    void auditioningTheSavedClipsAreTheUserClipsAndTheSelection() {
        AudioClip userInsideCompRange = new AudioClip("User", 3.0, 1.0, "/media/user.wav");
        track.addClip(userInsideCompRange);
        audition(1);

        List<AudioClip> saved = comping.clipsAsSaved(track);

        assertThat(saved).hasSize(2);
        assertThat(saved.getFirst()).isSameAs(userInsideCompRange);
        assertThat(saved.get(1).getSourceSegmentPaths()).isEqualTo(lane0Take.getSourceSegmentPaths());
        assertThat(saved).noneMatch(clip -> clip.getSourceSegmentPaths().equals(lane1Take.getSourceSegmentPaths()));
    }

    @Test
    void auditioningTheReferenceHoldersAreTheUserClipsAndTheSelectedTakeClipsOnce() {
        AudioClip user = new AudioClip("User", 9.0, 1.0, "/media/user.wav");
        track.addClip(user);
        comping.addCompRegion(new CompRegion(0, 6.0, 1.0));   // a second slice of the same take clip
        new CompileCompAction(track).execute();
        audition(1);

        assertThat(comping.referenceHoldersAsSaved(track)).containsExactly(user, lane0Take);
    }

    @Test
    void anEmptySelectionSavesNoCompClips() {
        AudioClip user = new AudioClip("User", 9.0, 1.0, "/media/user.wav");
        track.addClip(user);
        audition(1);
        comping.clearCompRegions();

        assertThat(comping.clipsAsSaved(track)).containsExactly(user);
        assertThat(comping.referenceHoldersAsSaved(track)).containsExactly(user);
    }

    @Test
    void unEditedCompOutputSavesAsTheSelectionAndAnEditedOneDoesNot() {
        assertThat(comping.compOutputSavesAsSelection(track)).isTrue();

        track.getClips().getFirst().setFadeInBeats(0.5);

        assertThat(comping.compOutputSavesAsSelection(track)).isFalse();
    }

    @Test
    void theSeamRefusesAnotherTracksClips() {
        Track other = new Track("Bass", TrackType.AUDIO);

        assertThatThrownBy(() -> comping.clipsAsSaved(other)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> comping.referenceHoldersAsSaved(other)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> comping.compOutputSavesAsSelection(other)).isInstanceOf(IllegalArgumentException.class);
    }

    /** What the COMP tool's Alt-click does (CompToolHandler.altClickLane, daw-app). */
    private void audition(int laneIndex) {
        for (int i = 0; i < comping.getTakeLaneCount(); i++) {
            comping.getTakeLane(i).setSoloed(i == laneIndex);
        }
        new CompileCompAction(track).execute();
    }

    private static AudioClip take(String name, String segment) {
        AudioClip clip = new AudioClip(name, 0.0, 8.0, null);
        clip.setSourceSegmentPaths(List.of(segment));
        clip.setAudioData(new float[][] { new float[16] });
        return clip;
    }
}
