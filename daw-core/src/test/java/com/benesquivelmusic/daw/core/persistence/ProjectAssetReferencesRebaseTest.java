package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.comping.TakeLane;
import com.benesquivelmusic.daw.core.midi.SoundFontAssignment;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ProjectAssetReferences#rebase} (story 333, ruling R5; SKL-O3):
 * after Save As made the copy the open project, every live reference under
 * the original folder whose file the copy holds names the same relative path
 * under the copy — the
 * clips of every track and of every take lane, not only the saved comp, each
 * segment of a recorded take and each SoundFont path — and nothing else is
 * touched. No disk is read: the folders need not exist.
 */
class ProjectAssetReferencesRebaseTest {

    @TempDir
    Path tmp;

    @Test
    void everyLiveReferenceUnderTheOriginalNamesTheCopy() {
        Path songA = tmp.resolve("Song A");
        Path songB = tmp.resolve("Song B");
        DawProject project = new DawProject("Song A", AudioFormat.CD_QUALITY);
        Track vox = project.createAudioTrack("Vox");
        AudioClip imported = new AudioClip("imported", 0, 4, songA.resolve("audio/imported.wav").toString());
        AudioClip take = new AudioClip("take", 4, 4, songA.resolve("audio/takes/t1/vox/segment-000.wav").toString());
        take.setSourceSegmentPaths(List.of(songA.resolve("audio/takes/t1/vox/segment-000.wav").toString(),
                songA.resolve("audio/takes/t1/vox/segment-001.wav").toString()));
        vox.addClip(imported);
        vox.addClip(take);
        TakeLane lane = new TakeLane("Take 2");
        AudioClip laneClip = new AudioClip("lane", 0, 4, songA.resolve("audio/takes/t2/vox/segment-000.wav").toString());
        lane.addClip(laneClip);
        vox.getTakeComping().addTakeLane(lane);
        Track keys = project.createMidiTrack("Keys");
        keys.setSoundFontAssignment(new SoundFontAssignment(songA.resolve("sf/piano.sf2"), 1, 2, "Piano"));

        int rebased = ProjectAssetReferences.rebase(project, songA, songB, Set.of(Path.of("audio/imported.wav"),
                Path.of("audio/takes/t1/vox/segment-000.wav"), Path.of("audio/takes/t1/vox/segment-001.wav"),
                Path.of("audio/takes/t2/vox/segment-000.wav"), Path.of("sf/piano.sf2")));

        assertThat(rebased).as("the imported clip, two segments, the lane clip and the SoundFont").isEqualTo(5);
        assertThat(imported.getSourceFilePath()).isEqualTo(songB.resolve("audio/imported.wav").toString());
        assertThat(take.getSourceSegmentPaths()).containsExactly(
                songB.resolve("audio/takes/t1/vox/segment-000.wav").toString(),
                songB.resolve("audio/takes/t1/vox/segment-001.wav").toString());
        assertThat(take.getSourceFilePath()).as("the head follows the first segment")
                .isEqualTo(songB.resolve("audio/takes/t1/vox/segment-000.wav").toString());
        assertThat(laneClip.getSourceFilePath())
                .as("a take-lane clip outside the saved comp is re-pointed too")
                .isEqualTo(songB.resolve("audio/takes/t2/vox/segment-000.wav").toString());
        assertThat(keys.getSoundFontAssignment()).isEqualTo(
                new SoundFontAssignment(songB.resolve("sf/piano.sf2"), 1, 2, "Piano"));
    }

    /**
     * The studio engineer's ruling S1 on stage 3: the session is re-pointed
     * at the copy only for the files the copy holds, so the live model and
     * the copy's {@code project.daw} agree — a file the copy could not take
     * (missing, left out, unreadable) keeps naming the original's file, and
     * plays again once that file is back.
     */
    @Test
    void aReferenceWhoseFileTheCopyDoesNotHoldKeepsTheOriginalsPath() {
        Path songA = tmp.resolve("Song A");
        Path songB = tmp.resolve("Song B");
        DawProject project = new DawProject("Song A", AudioFormat.CD_QUALITY);
        Track vox = project.createAudioTrack("Vox");
        AudioClip copied = new AudioClip("copied", 0, 1, songA.resolve("audio/copied.wav").toString());
        AudioClip gone = new AudioClip("gone", 1, 1, songA.resolve("audio/gone.wav").toString());
        AudioClip take = new AudioClip("take", 2, 1, songA.resolve("audio/takes/t1/vox/segment-000.wav").toString());
        take.setSourceSegmentPaths(List.of(songA.resolve("audio/takes/t1/vox/segment-000.wav").toString(),
                songA.resolve("audio/takes/t1/vox/segment-001.wav").toString()));
        vox.addClip(copied);
        vox.addClip(gone);
        vox.addClip(take);
        Track keys = project.createMidiTrack("Keys");
        SoundFontAssignment missingFont = new SoundFontAssignment(songA.resolve("sf/missing.sf2"), 0, 0, "Piano");
        keys.setSoundFontAssignment(missingFont);

        int rebased = ProjectAssetReferences.rebase(project, songA, songB, Set.of(Path.of("audio/copied.wav"),
                Path.of("audio/takes/t1/vox/segment-001.wav")));

        assertThat(rebased).as("the copied clip and the one segment the copy holds").isEqualTo(2);
        assertThat(copied.getSourceFilePath()).isEqualTo(songB.resolve("audio/copied.wav").toString());
        assertThat(gone.getSourceFilePath()).as("not copied: still the original's file")
                .isEqualTo(songA.resolve("audio/gone.wav").toString());
        assertThat(take.getSourceSegmentPaths()).containsExactly(
                songA.resolve("audio/takes/t1/vox/segment-000.wav").toString(),
                songB.resolve("audio/takes/t1/vox/segment-001.wav").toString());
        assertThat(keys.getSoundFontAssignment()).as("a SoundFont the copy does not hold is kept").isSameAs(missingFont);
    }

    @Test
    void referencesOutsideTheOriginalAndOnesThatNameNoFileThereAreLeftAsTheyAre() {
        Path songA = tmp.resolve("Song A");
        Path library = tmp.resolve("Library");
        String external = library.resolve("kick.wav").toString();
        String sibling = tmp.resolve("Song A2").resolve("audio/x.wav").toString();
        String relative = "audio/relative.wav";
        String theFolderItself = songA.toString();
        DawProject project = new DawProject("Song A", AudioFormat.CD_QUALITY);
        Track drums = project.createAudioTrack("Drums");
        AudioClip externalClip = new AudioClip("external", 0, 1, external);
        AudioClip siblingClip = new AudioClip("sibling", 1, 1, sibling);
        AudioClip relativeClip = new AudioClip("relative", 2, 1, relative);
        AudioClip folderClip = new AudioClip("folder", 3, 1, theFolderItself);
        drums.addClip(externalClip);
        drums.addClip(siblingClip);
        drums.addClip(relativeClip);
        drums.addClip(folderClip);
        Track keys = project.createMidiTrack("Keys");
        SoundFontAssignment shared = new SoundFontAssignment(library.resolve("gm.sf2"), 0, 0, "GM");
        keys.setSoundFontAssignment(shared);

        int rebased = ProjectAssetReferences.rebase(project, songA, tmp.resolve("Song B"),
                Set.of(Path.of("audio/x.wav"), Path.of("audio/relative.wav")));

        assertThat(rebased).isZero();
        assertThat(externalClip.getSourceFilePath()).isEqualTo(external);
        assertThat(siblingClip.getSourceFilePath())
                .as("a folder whose name only starts with the original's is not under it").isEqualTo(sibling);
        assertThat(relativeClip.getSourceFilePath()).isEqualTo(relative);
        assertThat(folderClip.getSourceFilePath()).as("the folder itself is not an asset").isEqualTo(theFolderItself);
        assertThat(keys.getSoundFontAssignment()).isSameAs(shared);
    }

    @Test
    void theWalkCollectsEveryLaneOnceButTheArchiveWalkKeepsItsSavedHolders() {
        DawProject project = new DawProject("Song A", AudioFormat.CD_QUALITY);
        Track vox = project.createAudioTrack("Vox");
        AudioClip onTrack = new AudioClip("track", 0, 4, tmp.resolve("a.wav").toString());
        vox.addClip(onTrack);
        TakeLane lane = new TakeLane("Take 1");
        AudioClip laneClip = new AudioClip("lane", 0, 4, tmp.resolve("b.wav").toString());
        lane.addClip(laneClip);
        lane.addClip(laneClip);
        vox.getTakeComping().addTakeLane(lane);

        List<String> every = ProjectAssetReferences.collect(project).stream()
                .map(ProjectAssetReferences.Ref::currentPath).toList();
        List<String> saved = ProjectAssetReferences.collect(project,
                        track -> track.getTakeComping().referenceHoldersAsSaved(track), true).stream()
                .map(ProjectAssetReferences.Ref::currentPath).toList();

        assertThat(every).as("the track's clips, then the lane's, a clip held twice once")
                .containsExactly(onTrack.getSourceFilePath(), laneClip.getSourceFilePath());
        assertThat(saved).as("not auditioning, the saved holders are the track's clips alone")
                .containsExactly(onTrack.getSourceFilePath());
    }
}
