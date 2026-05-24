package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.event.ClipEvent;
import com.benesquivelmusic.daw.sdk.event.EventBus;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoveClipActionTest {

    @Test
    void shouldHaveCorrectDescription() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        track.addClip(clip);

        assertThat(new RemoveClipAction(track, clip).description()).isEqualTo("Remove Clip");
    }

    @Test
    void shouldRemoveClipOnExecute() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        track.addClip(clip);

        RemoveClipAction action = new RemoveClipAction(track, clip);
        action.execute();

        assertThat(track.getClips()).isEmpty();
    }

    @Test
    void shouldReAddClipOnUndo() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        track.addClip(clip);

        RemoveClipAction action = new RemoveClipAction(track, clip);
        action.execute();
        action.undo();

        assertThat(track.getClips()).containsExactly(clip);
    }

    @Test
    void shouldWorkWithUndoManager() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        track.addClip(clip);
        UndoManager undoManager = new UndoManager();

        undoManager.execute(new RemoveClipAction(track, clip));
        assertThat(track.getClips()).isEmpty();

        undoManager.undo();
        assertThat(track.getClips()).containsExactly(clip);

        undoManager.redo();
        assertThat(track.getClips()).isEmpty();
    }

    @Test
    void shouldRejectNullTrack() {
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        assertThatThrownBy(() -> new RemoveClipAction(null, clip))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullClip() {
        Track track = new Track("Drums", TrackType.AUDIO);
        assertThatThrownBy(() -> new RemoveClipAction(track, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldPublishClipEventRemovedOnExecuteAndAddedOnUndo() throws Exception {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        track.addClip(clip);

        EventBus bus = DefaultEventBus.builder().build();
        EventBusPublisher.setDefault(bus);
        try {
            List<ClipEvent> events = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(2);
            bus.on(ClipEvent.class, e -> {
                events.add(e);
                latch.countDown();
            });

            RemoveClipAction action = new RemoveClipAction(track, clip);
            action.execute();
            action.undo();

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(events).hasSize(2);
            assertThat(events.get(0)).isInstanceOf(ClipEvent.Removed.class);
            assertThat(events.get(1)).isInstanceOf(ClipEvent.Added.class);
            UUID expectedTrackId = UUID.fromString(track.getId());
            UUID expectedClipId = UUID.fromString(clip.getId());
            assertThat(events.get(0).trackId()).isEqualTo(expectedTrackId);
            assertThat(events.get(0).clipId()).isEqualTo(expectedClipId);
            assertThat(events.get(1).trackId()).isEqualTo(expectedTrackId);
            assertThat(events.get(1).clipId()).isEqualTo(expectedClipId);
        } finally {
            EventBusPublisher.setDefault(null);
            bus.close();
        }
    }
}
