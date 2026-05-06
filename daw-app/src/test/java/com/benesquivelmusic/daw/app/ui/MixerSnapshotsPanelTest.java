package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.snapshot.MixerSnapshot;
import com.benesquivelmusic.daw.core.mixer.snapshot.MixerSnapshotManager;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Headless JavaFX test for the Story 103 Mixer Scene Snapshots panel and
 * A/B toggle integration in {@link MixerView}.
 *
 * <p>Validates:</p>
 * <ul>
 *   <li>Saving a named snapshot through the panel adds a row visible in the
 *       panel's {@link javafx.scene.control.ListView}.</li>
 *   <li>Recalling a snapshot restores previously captured volumes.</li>
 *   <li>{@link MixerView#toggleAB()} alternates between the A and B slots,
 *       applying each slot's mixer state in turn.</li>
 *   <li>Reaching {@link MixerSnapshotManager#MAX_SNAPSHOTS} disables the
 *       Save button and surfaces a clear tooltip.</li>
 * </ul>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class MixerSnapshotsPanelTest {

    private <T> T runFx(java.util.concurrent.Callable<T> action) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(action.call());
            } catch (Throwable t) {
                err.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX runnable timed out");
        }
        if (err.get() != null) {
            throw new RuntimeException(err.get());
        }
        return ref.get();
    }

    private void runFx(Runnable action) throws Exception {
        runFx(() -> { action.run(); return null; });
    }

    @Test
    void saveAddsNamedSnapshotToPanel() throws Exception {
        DawProject project = new DawProject("T", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Vox");

        MixerView view = runFx(() -> new MixerView(project, new UndoManager()));
        runFx(() -> view.getSnapshotsPanel().saveSnapshot("Loud Vocal"));

        assertThat(project.getMixerSnapshotManager().getSnapshots())
                .extracting(MixerSnapshot::name)
                .containsExactly("Loud Vocal");
        assertThat(view.getSnapshotsPanel().getSnapshotList().getItems())
                .hasSize(1)
                .first()
                .extracting(MixerSnapshot::name).isEqualTo("Loud Vocal");
    }

    @Test
    void recallRestoresPreviousVolumes() throws Exception {
        DawProject project = new DawProject("T", AudioFormat.CD_QUALITY);
        Track t = project.createAudioTrack("Bass");
        MixerChannel ch = project.getMixerChannelForTrack(t);

        MixerView view = runFx(() -> new MixerView(project, new UndoManager()));

        // Capture state with vol=0.4
        runFx(() -> ch.setVolume(0.4));
        MixerSnapshot snap = runFx(() -> view.getSnapshotsPanel().saveSnapshot("Quiet"));
        // Mutate
        runFx(() -> ch.setVolume(0.9));
        // Recall
        runFx(() -> view.getSnapshotsPanel().recallSnapshot(snap));

        assertThat(ch.getVolume()).isEqualTo(0.4);
    }

    @Test
    void toggleABAlternatesBetweenSavedStates() throws Exception {
        DawProject project = new DawProject("T", AudioFormat.CD_QUALITY);
        Track t = project.createAudioTrack("Drums");
        MixerChannel ch = project.getMixerChannelForTrack(t);
        Mixer mixer = project.getMixer();

        MixerView view = runFx(() -> new MixerView(project, new UndoManager()));

        // Capture A at vol 0.3
        runFx(() -> ch.setVolume(0.3));
        MixerSnapshot a = MixerSnapshot.capture(mixer, "A");
        // Capture B at vol 0.8
        runFx(() -> ch.setVolume(0.8));
        MixerSnapshot b = MixerSnapshot.capture(mixer, "B");

        MixerSnapshotManager manager = project.getMixerSnapshotManager();
        manager.setSlot(MixerSnapshotManager.Slot.A, a);
        manager.setSlot(MixerSnapshotManager.Slot.B, b);
        manager.setActiveSlot(MixerSnapshotManager.Slot.A);

        // Toggle: A -> B applies vol 0.8
        runFx(view::toggleAB);
        assertThat(manager.getActiveSlot()).isEqualTo(MixerSnapshotManager.Slot.B);
        assertThat(ch.getVolume()).isEqualTo(0.8);

        // Toggle: B -> A applies vol 0.3
        runFx(view::toggleAB);
        assertThat(manager.getActiveSlot()).isEqualTo(MixerSnapshotManager.Slot.A);
        assertThat(ch.getVolume()).isEqualTo(0.3);
    }

    @Test
    void saveButtonDisablesAtMaxSnapshots() throws Exception {
        DawProject project = new DawProject("T", AudioFormat.CD_QUALITY);
        project.createAudioTrack("X");

        MixerView view = runFx(() -> new MixerView(project, null));
        MixerSnapshotsPanel panel = view.getSnapshotsPanel();

        // Fill to MAX_SNAPSHOTS via the panel
        runFx(() -> {
            for (int i = 0; i < MixerSnapshotManager.MAX_SNAPSHOTS; i++) {
                panel.saveSnapshot("snap-" + i);
            }
        });

        assertThat(project.getMixerSnapshotManager().getSnapshotCount())
                .isEqualTo(MixerSnapshotManager.MAX_SNAPSHOTS);
        assertThat(panel.getSaveButton().isDisable()).isTrue();
        assertThat(panel.getSaveButton().getTooltip().getText())
                .contains(String.valueOf(MixerSnapshotManager.MAX_SNAPSHOTS));
    }

    @Test
    void snapshotsToggleButtonShowsAndHidesPanel() throws Exception {
        DawProject project = new DawProject("T", AudioFormat.CD_QUALITY);
        MixerView view = runFx(() -> new MixerView(project));

        // Initially hidden — panel is not in scene graph
        assertThat(view.getSnapshotsPanel().getParent()).isNull();

        runFx(() -> view.getSnapshotsToggleButton().setSelected(true));
        assertThat(view.getSnapshotsPanel().getParent()).isNotNull();

        runFx(() -> view.getSnapshotsToggleButton().setSelected(false));
        assertThat(view.getSnapshotsPanel().getParent()).isNull();
    }
}
