package com.benesquivelmusic.daw.core.snapshot;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotDiffTest {

    /** The id of the "Vocals" track in both snapshots of a comparison. */
    private static final UUID VOCALS = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID DRUMS = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID BASS = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    /**
     * Builds a new {@link Track} with a deterministic ID. The diff
     * compares snapshots by stable IDs (preserved across save/load), so
     * tests construct paired tracks that share an ID without going
     * through the full serializer round-trip.
     */
    private static Track track(UUID id, String name, TrackType type) {
        return new Track(id, name, type);
    }

    private static AudioClip clip(String id, String name,
                                  double startBeat, double durationBeats,
                                  String src) {
        AudioClip c = new AudioClip(name, startBeat, durationBeats, src);
        try {
            Field idField = AudioClip.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(c, id);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        return c;
    }

    @Test
    void emptyDiffWhenProjectsAreIdentical() {
        DawProject a = new DawProject("Song", AudioFormat.CD_QUALITY);
        a.addTrack(track(VOCALS, "Vocals", TrackType.AUDIO));
        a.addTrack(track(DRUMS, "Drums", TrackType.AUDIO));

        DawProject b = new DawProject("Song", AudioFormat.CD_QUALITY);
        b.addTrack(track(VOCALS, "Vocals", TrackType.AUDIO));
        b.addTrack(track(DRUMS, "Drums", TrackType.AUDIO));

        SnapshotDiff diff = SnapshotDiff.between(a, b);

        assertThat(diff.isEmpty()).isTrue();
        assertThat(diff.shortSummary()).isEqualTo("No changes");
    }

    @Test
    void detectsAddedAndRemovedTracks() {
        DawProject from = new DawProject("Song", AudioFormat.CD_QUALITY);
        from.addTrack(track(VOCALS, "Vocals", TrackType.AUDIO));

        DawProject to = new DawProject("Song", AudioFormat.CD_QUALITY);
        to.addTrack(track(DRUMS, "Drums", TrackType.AUDIO));
        to.addTrack(track(BASS, "Bass", TrackType.AUDIO));

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
        Track tFrom = track(VOCALS, "Vocals", TrackType.AUDIO);
        tFrom.addClip(clip("verse", "Verse", 0, 4, "v.wav"));
        tFrom.addClip(clip("chorus", "Chorus", 4, 4, "c.wav"));
        from.addTrack(tFrom);

        DawProject to = new DawProject("Song", AudioFormat.CD_QUALITY);
        Track tTo = track(VOCALS, "Vocals", TrackType.AUDIO);
        tTo.addClip(clip("verse", "Verse", 0, 4, "v.wav"));
        tTo.addClip(clip("bridge", "Bridge", 8, 2, "b.wav"));
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
        Track tFrom = track(VOCALS, "Vocals", TrackType.AUDIO);
        tFrom.addClip(clip("verse", "Verse", 0, 4, "v.wav"));
        from.addTrack(tFrom);

        DawProject to = new DawProject("Song", AudioFormat.CD_QUALITY);
        Track tTo = track(VOCALS, "Vocals", TrackType.AUDIO);
        tTo.addClip(clip("verse", "Verse", 8, 4, "v.wav"));
        to.addTrack(tTo);

        SnapshotDiff diff = SnapshotDiff.between(from, to);

        assertThat(diff.entries())
                .anyMatch(e -> e.category().equals("clip")
                        && e.changeType() == SnapshotDiff.ChangeType.MODIFIED);
    }

    @Test
    void detectsMixerParameterChanges() {
        DawProject from = new DawProject("Song", AudioFormat.CD_QUALITY);
        Track tFrom = track(VOCALS, "Vocals", TrackType.AUDIO);
        tFrom.setVolume(0.5);
        from.addTrack(tFrom);

        DawProject to = new DawProject("Song", AudioFormat.CD_QUALITY);
        Track tTo = track(VOCALS, "Vocals", TrackType.AUDIO);
        tTo.setVolume(0.8);
        tTo.setMuted(true);
        tTo.setSolo(true);
        to.addTrack(tTo);

        SnapshotDiff diff = SnapshotDiff.between(from, to);

        assertThat(diff.entries())
                .anyMatch(e -> e.category().equals("mixer")
                        && e.description().startsWith("Volume"));
        assertThat(diff.entries())
                .anyMatch(e -> e.category().equals("mixer")
                        && e.description().startsWith("Mute"));
        assertThat(diff.entries())
                .anyMatch(e -> e.category().equals("mixer")
                        && e.description().startsWith("Solo"));
    }

    @Test
    void detectsTrackRenameAsModification() {
        DawProject from = new DawProject("Song", AudioFormat.CD_QUALITY);
        from.addTrack(track(VOCALS, "Vocals", TrackType.AUDIO));

        DawProject to = new DawProject("Song", AudioFormat.CD_QUALITY);
        to.addTrack(track(VOCALS, "Lead Vocals", TrackType.AUDIO));

        SnapshotDiff diff = SnapshotDiff.between(from, to);

        assertThat(diff.entries())
                .anyMatch(e -> e.category().equals("track")
                        && e.changeType() == SnapshotDiff.ChangeType.MODIFIED
                        && e.description().contains("renamed"));
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
        DawProject p = new DawProject("Song", AudioFormat.CD_QUALITY);
        p.addTrack(track(VOCALS, "Vocals", TrackType.AUDIO));

        SnapshotDiff added = SnapshotDiff.between(null, p);
        SnapshotDiff removed = SnapshotDiff.between(p, null);

        assertThat(added.entries())
                .anyMatch(e -> e.category().equals("project")
                        && e.changeType() == SnapshotDiff.ChangeType.ADDED);
        assertThat(removed.entries())
                .anyMatch(e -> e.category().equals("project")
                        && e.changeType() == SnapshotDiff.ChangeType.REMOVED);
    }
}
