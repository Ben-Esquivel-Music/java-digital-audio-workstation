package com.benesquivelmusic.daw.sdk.store;

import com.benesquivelmusic.daw.sdk.model.Project;
import com.benesquivelmusic.daw.sdk.model.Track;
import com.benesquivelmusic.daw.sdk.model.TrackType;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UndoManagerTest {

    @Test
    void undoRedo_roundTripsThroughSnapshots() {
        try (ProjectStore store = new ProjectStore(Project.empty("s"))) {
            UndoManager um = new UndoManager(store);
            Track t = Track.of("V", TrackType.AUDIO);

            um.applyAndRecord(p -> p.putTrack(t));
            um.applyAndRecord(p -> p.putTrack(t.withVolume(0.5)));

            assertThat(store.project().tracks().get(t.id()).volume()).isEqualTo(0.5);
            assertThat(um.canUndo()).isTrue();
            assertThat(um.canRedo()).isFalse();

            // Undo the volume change
            Optional<Project> after1 = um.undo();
            assertThat(after1).isPresent();
            assertThat(store.project().tracks().get(t.id()).volume()).isEqualTo(1.0);

            // Undo the add
            Optional<Project> after2 = um.undo();
            assertThat(after2).isPresent();
            assertThat(store.project().tracks()).isEmpty();

            assertThat(um.canUndo()).isFalse();
            assertThat(um.canRedo()).isTrue();

            // Redo both
            um.redo();
            assertThat(store.project().tracks()).hasSize(1);
            um.redo();
            assertThat(store.project().tracks().get(t.id()).volume()).isEqualTo(0.5);

            // Time travel correctness: after a full undo/redo cycle the
            // structural value of the snapshot equals the original.
            um.undo(); um.undo(); um.redo(); um.redo();
            assertThat(store.project().tracks().get(t.id()).volume()).isEqualTo(0.5);
        }
    }

    @Test
    void recordingNewActionInvalidatesRedoStack() {
        try (ProjectStore store = new ProjectStore(Project.empty("s"))) {
            UndoManager um = new UndoManager(store);
            Track t = Track.of("V", TrackType.AUDIO);

            um.applyAndRecord(p -> p.putTrack(t));
            um.undo();
            assertThat(um.canRedo()).isTrue();

            um.applyAndRecord(p -> p.putTrack(Track.of("Other", TrackType.AUDIO)));
            assertThat(um.canRedo()).isFalse();
        }
    }

    @Test
    void undoOnEmptyStack_returnsEmpty() {
        try (ProjectStore store = new ProjectStore(Project.empty("s"))) {
            UndoManager um = new UndoManager(store);
            assertThat(um.undo()).isEmpty();
            assertThat(um.redo()).isEmpty();
        }
    }

    @Test
    void noOpAction_isNotRecorded() {
        try (ProjectStore store = new ProjectStore(Project.empty("s"))) {
            UndoManager um = new UndoManager(store);
            um.applyAndRecord(CompoundAction.identity());
            assertThat(um.canUndo()).isFalse();
        }
    }

    @Test
    void capacityCapsHistory() {
        try (ProjectStore store = new ProjectStore(Project.empty("s"))) {
            UndoManager um = new UndoManager(store, 2);
            for (int i = 0; i < 5; i++) {
                um.applyAndRecord(p -> p.putTrack(Track.of("T", TrackType.AUDIO)));
            }
            // Only the most recent 2 entries are kept.
            assertThat(um.undo()).isPresent();
            assertThat(um.undo()).isPresent();
            assertThat(um.undo()).isEmpty();
        }
    }

    @Test
    void invalidCapacity_throws() {
        try (ProjectStore store = new ProjectStore(Project.empty("s"))) {
            assertThatThrownBy(() -> new UndoManager(store, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
