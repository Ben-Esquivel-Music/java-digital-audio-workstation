package com.benesquivelmusic.daw.core.snapshot;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotDiffTest {

    @Test
    void emptyDiffWhenProjectsAreIdentical() {
        DawProject a = projectWithVocalsAndDrums();
        DawProject b = projectWithVocalsAndDrums();

        SnapshotDiff diff = SnapshotDiff.between(a, b);

        assertThat(diff.isEmpty()).isTrue();
        assertThat(diff.shortSummary()).isEqualTo("No changes");
    }

    @Test
    void detectsAddedAndRemovedTracks() {
        DawProject from = new DawProject("Song", AudioFormat.CD_QUALITY);
        from.addTrack(new Track("Vocals", TrackType.AUDIO));

        DawProject to = new DawProject("Song", AudioFormat.CD_QUALITY);
        to.addTrack(new Track("Drums", TrackType.AUDIO));
        to.addTrack(new Track("Bass", TrackType.AUDIO));

        SnapshotDiff diff = SnapshotDiff.between(from, to);

        assertThat(diff.entries())
                .extracting(SnapshotDiff.Entry::category, SnapshotDiff.Entry::changeType)
                .contains(
                        org.assertj.core.groups.Tuple.tuple("track", SnapshotDiff.ChangeType.REMOVED),
                        org.assertj.core.groups.Tuple.tuple("track", SnapshotDiff.ChangeType.ADDED)
                );
        assertThat(diff.shortSummary()).contains("+2 tracks").contains("-1 track");
    }

    @Test
    void detectsAddedAndRemovedClips() {
        DawProject from = new DawProject("Song", AudioFormat.CD_QUALITY);
        Track tFrom = new Track("Vocals", TrackType.AUDIO);
        tFrom.addClip(new AudioClip("Verse", 0, 4, "v.wav"));
        tFrom.addClip(new AudioClip("Chorus", 4, 4, "c.wav"));
        from.addTrack(tFrom);

        DawProject to = new DawProject("Song", AudioFormat.CD_QUALITY);
        Track tTo = new Track("Vocals", TrackType.AUDIO);
        tTo.addClip(new AudioClip("Verse", 0, 4, "v.wav"));
        tTo.addClip(new AudioClip("Bridge", 8, 2, "b.wav"));
        to.addTrack(tTo);

        SnapshotDiff diff = SnapshotDiff.between(from, to);

        assertThat(diff.entries())
                .anyMatch(e -> e.category().equals("clip")
                        && e.changeType() == SnapshotDiff.ChangeType.ADDED
                        && e.identifier().endsWith("Bridge"));
        assertThat(diff.entries())
                .anyMatch(e -> e.category().equals("clip")
                        && e.changeType() == SnapshotDiff.ChangeType.REMOVED
                        && e.identifier().endsWith("Chorus"));
    }

    @Test
    void detectsModifiedClipParameters() {
        DawProject from = new DawProject("Song", AudioFormat.CD_QUALITY);
        Track tFrom = new Track("Vocals", TrackType.AUDIO);
        tFrom.addClip(new AudioClip("Verse", 0, 4, "v.wav"));
        from.addTrack(tFrom);

        DawProject to = new DawProject("Song", AudioFormat.CD_QUALITY);
        Track tTo = new Track("Vocals", TrackType.AUDIO);
        AudioClip moved = new AudioClip("Verse", 8, 4, "v.wav");
        tTo.addClip(moved);
        to.addTrack(tTo);

        SnapshotDiff diff = SnapshotDiff.between(from, to);

        assertThat(diff.entries())
                .anyMatch(e -> e.category().equals("clip")
                        && e.changeType() == SnapshotDiff.ChangeType.MODIFIED);
    }

    @Test
    void detectsMixerParameterChanges() {
        DawProject from = new DawProject("Song", AudioFormat.CD_QUALITY);
        Track tFrom = new Track("Vocals", TrackType.AUDIO);
        tFrom.setVolume(0.5);
        from.addTrack(tFrom);

        DawProject to = new DawProject("Song", AudioFormat.CD_QUALITY);
        Track tTo = new Track("Vocals", TrackType.AUDIO);
        tTo.setVolume(0.8);
        tTo.setMuted(true);
        to.addTrack(tTo);

        SnapshotDiff diff = SnapshotDiff.between(from, to);

        assertThat(diff.entries())
                .anyMatch(e -> e.category().equals("mixer")
                        && e.description().startsWith("Volume"));
        assertThat(diff.entries())
                .anyMatch(e -> e.category().equals("mixer")
                        && e.description().startsWith("Mute"));
    }

    @Test
    void renamedProjectIsReportedAsModification() {
        DawProject from = new DawProject("Old Name", AudioFormat.CD_QUALITY);
        DawProject to = new DawProject("New Name", AudioFormat.CD_QUALITY);

        SnapshotDiff diff = SnapshotDiff.between(from, to);

        assertThat(diff.entries())
                .anyMatch(e -> e.category().equals("project")
                        && e.changeType() == SnapshotDiff.ChangeType.MODIFIED);
    }

    @Test
    void nullSidesAreReportedAsWholeAddOrRemove() {
        DawProject p = projectWithVocalsAndDrums();

        SnapshotDiff added = SnapshotDiff.between(null, p);
        SnapshotDiff removed = SnapshotDiff.between(p, null);

        assertThat(added.entries())
                .anyMatch(e -> e.category().equals("project")
                        && e.changeType() == SnapshotDiff.ChangeType.ADDED);
        assertThat(removed.entries())
                .anyMatch(e -> e.category().equals("project")
                        && e.changeType() == SnapshotDiff.ChangeType.REMOVED);
    }

    private static DawProject projectWithVocalsAndDrums() {
        DawProject p = new DawProject("Song", AudioFormat.CD_QUALITY);
        p.addTrack(new Track("Vocals", TrackType.AUDIO));
        p.addTrack(new Track("Drums", TrackType.AUDIO));
        return p;
    }
}
