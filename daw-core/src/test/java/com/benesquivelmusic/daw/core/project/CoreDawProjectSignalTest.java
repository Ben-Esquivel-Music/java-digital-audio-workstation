package com.benesquivelmusic.daw.core.project;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject.ChangeKind;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 292 — verifies the toolkit-neutral change-notification seam added to
 * {@link DawProject} (mirrors {@code CoreTrackSignalTest} for {@code Track}).
 * The seam is what {@code daw-app}'s {@code ProjectVM} subscribes to; these
 * tests pin its contract: which mutation fires which {@link ChangeKind}, that
 * the dirty flag is fire-on-change (an idempotent {@code markDirty} announces
 * nothing), that removal tokens work, and — the discriminating case — that a
 * listener removed mid-notification cannot corrupt the iteration.
 */
class CoreDawProjectSignalTest {

    private static DawProject newProject() {
        return new DawProject("Test", AudioFormat.CD_QUALITY);
    }

    @Test
    void setNameFiresNameSignal() {
        DawProject project = newProject();
        List<ChangeKind> kinds = new ArrayList<>();
        project.addChangeListener(kinds::add);

        project.setName("Renamed");

        assertThat(kinds).containsExactly(ChangeKind.NAME);
        assertThat(project.getName()).isEqualTo("Renamed");
    }

    @Test
    void markDirtyFiresDirtyOnceOnTransitionThenIsIdempotent() {
        DawProject project = newProject(); // a new project starts clean
        List<ChangeKind> kinds = new ArrayList<>();
        project.addChangeListener(kinds::add);

        project.markDirty();
        project.markDirty(); // already dirty — must NOT re-announce

        assertThat(kinds).containsExactly(ChangeKind.DIRTY);
        assertThat(project.isDirty()).isTrue();
    }

    @Test
    void markCleanFiresDirtyOnlyOnTransition() {
        DawProject project = newProject();
        project.markDirty(); // before the listener — not recorded
        List<ChangeKind> kinds = new ArrayList<>();
        project.addChangeListener(kinds::add);

        project.markClean();
        project.markClean(); // already clean — must NOT re-announce

        assertThat(kinds).containsExactly(ChangeKind.DIRTY);
        assertThat(project.isDirty()).isFalse();
    }

    @Test
    void addTrackFiresTracksSignal() {
        DawProject project = newProject();
        List<ChangeKind> kinds = new ArrayList<>();
        project.addChangeListener(kinds::add);

        Track track = project.createAudioTrack("Drums");

        assertThat(kinds).containsExactly(ChangeKind.TRACKS);
        assertThat(project.getTracks()).containsExactly(track);
    }

    @Test
    void removeTrackFiresTracksOnlyWhenItActuallyRemoves() {
        DawProject project = newProject();
        Track track = project.createAudioTrack("Drums"); // before the listener
        List<ChangeKind> kinds = new ArrayList<>();
        project.addChangeListener(kinds::add);

        assertThat(project.removeTrack(track)).isTrue();
        Track stranger = new Track("Stranger", TrackType.AUDIO);
        assertThat(project.removeTrack(stranger)).isFalse(); // not in project — no signal

        assertThat(kinds).containsExactly(ChangeKind.TRACKS);
    }

    @Test
    void moveTrackFiresTracksButNotForANoOpMove() {
        DawProject project = newProject();
        Track a = project.createAudioTrack("A");
        Track b = project.createAudioTrack("B");
        List<ChangeKind> kinds = new ArrayList<>();
        project.addChangeListener(kinds::add);

        project.moveTrack(0, 1);
        project.moveTrack(1, 1); // from == to — early return, fires nothing

        assertThat(kinds).containsExactly(ChangeKind.TRACKS);
        assertThat(project.getTracks()).containsExactly(b, a);
    }

    @Test
    void duplicateTrackFiresTracksSignal() {
        DawProject project = newProject();
        Track a = project.createAudioTrack("A");
        List<ChangeKind> kinds = new ArrayList<>();
        project.addChangeListener(kinds::add);

        Track copy = project.duplicateTrack(a);

        assertThat(kinds).containsExactly(ChangeKind.TRACKS);
        assertThat(project.getTracks()).containsExactly(a, copy);
    }

    @Test
    void moveTrackToFolderFiresTracksSignal() {
        // story 293 review #4: a folder re-nesting is a structural change, so it
        // must announce TRACKS like add/remove/move — the reactive arrangement
        // canvas (ProjectVM.tracks) repaints off this signal.
        DawProject project = newProject();
        Track folder = project.createFolderTrack("Drums");
        Track kick = project.createAudioTrack("Kick");
        List<ChangeKind> kinds = new ArrayList<>();
        project.addChangeListener(kinds::add);

        project.moveTrackToFolder(kick, folder);

        assertThat(kinds).containsExactly(ChangeKind.TRACKS);
        assertThat(kick.getParentTrack()).isEqualTo(folder);
    }

    @Test
    void removeTrackFromFolderFiresTracksSignal() {
        DawProject project = newProject();
        Track folder = project.createFolderTrack("Drums");
        Track kick = project.createAudioTrack("Kick");
        project.moveTrackToFolder(kick, folder);
        List<ChangeKind> kinds = new ArrayList<>();
        project.addChangeListener(kinds::add);

        project.removeTrackFromFolder(kick);

        assertThat(kinds).containsExactly(ChangeKind.TRACKS);
        assertThat(kick.getParentTrack()).isNull();
    }

    @Test
    void removalTokenUnregistersTheListener() {
        DawProject project = newProject();
        List<ChangeKind> kinds = new ArrayList<>();
        Runnable token = project.addChangeListener(kinds::add);

        project.setName("One");
        token.run();
        project.setName("Two"); // after unregister — not recorded

        assertThat(kinds).containsExactly(ChangeKind.NAME);
    }

    @Test
    void removeChangeListenerUnregistersTheListener() {
        DawProject project = newProject();
        List<ChangeKind> kinds = new ArrayList<>();
        Consumer<ChangeKind> listener = kinds::add;
        project.addChangeListener(listener);

        project.markDirty();
        project.removeChangeListener(listener);
        project.markClean(); // after unregister — not recorded

        assertThat(kinds).containsExactly(ChangeKind.DIRTY);
    }

    @Test
    void aListenerRemovingAnotherDuringNotificationDoesNotThrowAndKeepsSnapshotSemantics() {
        DawProject project = newProject();
        List<ChangeKind> received = new ArrayList<>();

        AtomicReference<Consumer<ChangeKind>> bRef = new AtomicReference<>();
        // Listener A, during its own notification, unregisters listener B.
        Consumer<ChangeKind> listenerA = kind -> project.removeChangeListener(bRef.get());
        Consumer<ChangeKind> listenerB = received::add;
        bRef.set(listenerB);
        project.addChangeListener(listenerA);
        project.addChangeListener(listenerB);

        // The snapshot is read once into a stable local before iterating, so B
        // still receives THIS notification even though A removed it mid-loop —
        // and the iteration does not throw (a naive size()+get(i) loop would).
        project.setName("X");
        assertThat(received).containsExactly(ChangeKind.NAME);

        // B was removed for good; the NEXT notification skips it.
        received.clear();
        project.setName("Y");
        assertThat(received).isEmpty();
    }
}
