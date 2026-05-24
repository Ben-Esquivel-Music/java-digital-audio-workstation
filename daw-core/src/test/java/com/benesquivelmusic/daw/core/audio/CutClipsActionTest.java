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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CutClipsActionTest {

    @Test
    void shouldHaveCorrectDescription() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        track.addClip(clip);

        CutClipsAction action = new CutClipsAction(
                List.of(Map.entry(track, clip)));
        assertThat(action.description()).isEqualTo("Cut Clips");
    }

    @Test
    void shouldRemoveSingleClipOnExecute() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        track.addClip(clip);

        CutClipsAction action = new CutClipsAction(
                List.of(Map.entry(track, clip)));
        action.execute();

        assertThat(track.getClips()).isEmpty();
    }

    @Test
    void shouldReAddSingleClipOnUndo() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        track.addClip(clip);

        CutClipsAction action = new CutClipsAction(
                List.of(Map.entry(track, clip)));
        action.execute();
        action.undo();

        assertThat(track.getClips()).containsExactly(clip);
    }

    @Test
    void shouldRemoveMultipleClipsFromDifferentTracks() {
        Track drums = new Track("Drums", TrackType.AUDIO);
        Track bass = new Track("Bass", TrackType.AUDIO);
        AudioClip kick = new AudioClip("kick", 0.0, 4.0, null);
        AudioClip bassLine = new AudioClip("bass-line", 0.0, 8.0, null);
        drums.addClip(kick);
        bass.addClip(bassLine);

        CutClipsAction action = new CutClipsAction(
                List.of(Map.entry(drums, kick), Map.entry(bass, bassLine)));
        action.execute();

        assertThat(drums.getClips()).isEmpty();
        assertThat(bass.getClips()).isEmpty();
    }

    @Test
    void shouldReAddMultipleClipsOnUndo() {
        Track drums = new Track("Drums", TrackType.AUDIO);
        Track bass = new Track("Bass", TrackType.AUDIO);
        AudioClip kick = new AudioClip("kick", 0.0, 4.0, null);
        AudioClip bassLine = new AudioClip("bass-line", 0.0, 8.0, null);
        drums.addClip(kick);
        bass.addClip(bassLine);

        CutClipsAction action = new CutClipsAction(
                List.of(Map.entry(drums, kick), Map.entry(bass, bassLine)));
        action.execute();
        action.undo();

        assertThat(drums.getClips()).containsExactly(kick);
        assertThat(bass.getClips()).containsExactly(bassLine);
    }

    @Test
    void shouldWorkWithUndoManager() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        track.addClip(clip);
        UndoManager undoManager = new UndoManager();

        undoManager.execute(new CutClipsAction(
                List.of(Map.entry(track, clip))));
        assertThat(track.getClips()).isEmpty();

        undoManager.undo();
        assertThat(track.getClips()).containsExactly(clip);

        undoManager.redo();
        assertThat(track.getClips()).isEmpty();
    }

    @Test
    void shouldRejectNullEntries() {
        assertThatThrownBy(() -> new CutClipsAction(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectEmptyEntries() {
        assertThatThrownBy(() -> new CutClipsAction(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldPublishPerLeafRemovedOnExecuteAndAddedOnUndo() throws Exception {
        Track drums = new Track("Drums", TrackType.AUDIO);
        Track bass = new Track("Bass", TrackType.AUDIO);
        AudioClip kick = new AudioClip("kick", 0.0, 4.0, null);
        AudioClip bassLine = new AudioClip("bass-line", 0.0, 8.0, null);
        drums.addClip(kick);
        bass.addClip(bassLine);

        EventBus bus = DefaultEventBus.builder().build();
        EventBusPublisher.setDefault(bus);
        try {
            List<ClipEvent> events = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(4);
            bus.on(ClipEvent.class, e -> {
                events.add(e);
                latch.countDown();
            });

            CutClipsAction action = new CutClipsAction(
                    List.of(Map.entry(drums, kick), Map.entry(bass, bassLine)));
            action.execute();
            action.undo();

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(events).hasSize(4);
            // Execute publishes Removed for each leaf in order
            assertThat(events.get(0)).isInstanceOf(ClipEvent.Removed.class);
            assertThat(events.get(0).trackId()).isEqualTo(UUID.fromString(drums.getId()));
            assertThat(events.get(0).clipId()).isEqualTo(UUID.fromString(kick.getId()));
            assertThat(events.get(1)).isInstanceOf(ClipEvent.Removed.class);
            assertThat(events.get(1).trackId()).isEqualTo(UUID.fromString(bass.getId()));
            assertThat(events.get(1).clipId()).isEqualTo(UUID.fromString(bassLine.getId()));
            // Undo publishes Added in reverse order
            assertThat(events.get(2)).isInstanceOf(ClipEvent.Added.class);
            assertThat(events.get(2).trackId()).isEqualTo(UUID.fromString(bass.getId()));
            assertThat(events.get(2).clipId()).isEqualTo(UUID.fromString(bassLine.getId()));
            assertThat(events.get(3)).isInstanceOf(ClipEvent.Added.class);
            assertThat(events.get(3).trackId()).isEqualTo(UUID.fromString(drums.getId()));
            assertThat(events.get(3).clipId()).isEqualTo(UUID.fromString(kick.getId()));
        } finally {
            EventBusPublisher.setDefault(null);
            bus.close();
        }
    }
}
