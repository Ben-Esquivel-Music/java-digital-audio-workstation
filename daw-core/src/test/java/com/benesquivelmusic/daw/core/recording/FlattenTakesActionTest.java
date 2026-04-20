package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlattenTakesActionTest {

    private static AudioClip clip(String name) {
        return new AudioClip(name, 0.0, 1.0, null);
    }

    @Test
    void flattenCollapsesToActiveTakeAndDetachesGroup() {
        Track track = new Track("Vox", TrackType.AUDIO);
        AudioClip t0 = clip("t0");
        AudioClip t1 = clip("t1");
        AudioClip t2 = clip("t2");
        track.addClip(t0); // active clip is on the track lane.

        TakeGroup group = TakeGroup.empty()
                .withTakeAppended(Take.of(t0))
                .withTakeAppended(Take.of(t1))
                .withTakeAppended(Take.of(t2))
                .withActiveIndex(1);
        track.putTakeGroup(group);

        FlattenTakesAction action = new FlattenTakesAction(track, group);
        action.execute();

        // Only the active take's clip (t1) remains on the track.
        assertThat(track.getClips()).containsExactly(t1);
        // The take group is detached.
        assertThat(track.getTakeGroup(group.id())).isNull();
    }

    @Test
    void undoRestoresOriginalClipsAndGroup() {
        Track track = new Track("Vox", TrackType.AUDIO);
        AudioClip t0 = clip("t0");
        AudioClip t1 = clip("t1");
        track.addClip(t0);

        TakeGroup group = TakeGroup.empty()
                .withTakeAppended(Take.of(t0))
                .withTakeAppended(Take.of(t1));
        track.putTakeGroup(group);

        FlattenTakesAction action = new FlattenTakesAction(track, group);
        action.execute();
        assertThat(track.getClips()).containsExactly(t0);

        action.undo();

        assertThat(track.getClips()).containsExactly(t0);
        assertThat(track.getTakeGroup(group.id())).isSameAs(group);
    }

    @Test
    void rejectsEmptyGroup() {
        Track track = new Track("Vox", TrackType.AUDIO);
        assertThatThrownBy(() -> new FlattenTakesAction(track, TakeGroup.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hasDescription() {
        Track track = new Track("Vox", TrackType.AUDIO);
        AudioClip c = clip("c");
        track.addClip(c);
        TakeGroup g = TakeGroup.of(java.util.List.of(Take.of(c)));
        track.putTakeGroup(g);

        FlattenTakesAction action = new FlattenTakesAction(track, g);
        assertThat(action.description()).isEqualTo("Flatten Takes");
    }
}
