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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AddClipActionTest {

    @Test
    void shouldHaveCorrectDescription() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);

        assertThat(new AddClipAction(track, clip).description()).isEqualTo("Add Clip");
    }

    @Test
    void shouldAddClipOnExecute() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);

        AddClipAction action = new AddClipAction(track, clip);
        action.execute();

        assertThat(track.getClips()).containsExactly(clip);
    }

    @Test
    void shouldRemoveClipOnUndo() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);

        AddClipAction action = new AddClipAction(track, clip);
        action.execute();
        action.undo();

        assertThat(track.getClips()).isEmpty();
    }

    @Test
    void shouldWorkWithUndoManager() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        UndoManager undoManager = new UndoManager();

        undoManager.execute(new AddClipAction(track, clip));
        assertThat(track.getClips()).containsExactly(clip);

        undoManager.undo();
        assertThat(track.getClips()).isEmpty();

        undoManager.redo();
        assertThat(track.getClips()).containsExactly(clip);
    }

    @Test
    void shouldRejectNullTrack() {
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        assertThatThrownBy(() -> new AddClipAction(null, clip))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullClip() {
        Track track = new Track("Drums", TrackType.AUDIO);
        assertThatThrownBy(() -> new AddClipAction(track, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldPublishClipEventAddedOnExecuteAndRemovedOnUndo() throws Exception {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);

        EventBus bus = DefaultEventBus.builder().build();
        EventBusPublisher.setDefault(bus);
        try {
            List<ClipEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(2);
            bus.on(ClipEvent.class, e -> {
                events.add(e);
                latch.countDown();
            });

            AddClipAction action = new AddClipAction(track, clip);
            action.execute();
            action.undo();

            assertThat(latch.await(5, TimeUnit.SECONDS))
                    .as("both Added and Removed events should arrive")
                    .isTrue();
            assertThat(events).hasSize(2);
            assertThat(events.get(0)).isInstanceOf(ClipEvent.Added.class);
            assertThat(events.get(1)).isInstanceOf(ClipEvent.Removed.class);
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
