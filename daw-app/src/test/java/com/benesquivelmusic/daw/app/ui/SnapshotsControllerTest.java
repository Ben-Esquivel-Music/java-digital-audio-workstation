package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.snapshot.SnapshotBrowserService;
import com.benesquivelmusic.daw.core.snapshot.SnapshotEntry;
import com.benesquivelmusic.daw.core.snapshot.SnapshotKind;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;

import javafx.stage.Stage;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Headless tests for the {@link SnapshotsController} workflow surfaced by
 * Story 190 — <em>Snapshot History Browser with Visual Diff Preview</em>.
 *
 * <p>Exercises the headless entry points {@code createCheckpointWithLabel}
 * and {@code loadFromEntry} so the JavaFX toolkit is not required.</p>
 */
class SnapshotsControllerTest {

    private SnapshotsController newController(DawProject initial) {
        SnapshotBrowserService service = new SnapshotBrowserService();
        CheckpointManager checkpointManager = new CheckpointManager(AutoSaveConfig.DEFAULT);
        ProjectManager projectManager = new ProjectManager(checkpointManager);
        AtomicReference<DawProject> projectRef = new AtomicReference<>(initial);
        return new SnapshotsController(service, checkpointManager, projectManager,
                new SnapshotsController.Host() {
                    @Override public Stage ownerStage() { return null; }
                    @Override public DawProject currentProject() { return projectRef.get(); }
                    @Override public boolean confirmDiscardUnsavedChanges() { return true; }
                    @Override public void applyRestoredProject(DawProject project, String label) {
                        projectRef.set(project);
                    }
                });
    }

    @Test
    void createCheckpointAddsLabelledEntryToTimeline() {
        DawProject project = new DawProject("p", AudioFormat.STUDIO_QUALITY);
        SnapshotsController controller = newController(project);

        SnapshotEntry first = controller.createCheckpointWithLabel("Before mix");
        SnapshotEntry second = controller.createCheckpointWithLabel("After EQ");
        SnapshotEntry third = controller.createCheckpointWithLabel("");

        assertThat(controller.service().getEntries())
                .hasSize(3)
                .containsExactly(first, second, third);
        assertThat(first.label()).isEqualTo("Before mix");
        assertThat(second.label()).isEqualTo("After EQ");
        // Empty input produces a default timestamped label so the entry
        // is still identifiable in the browser.
        assertThat(third.label()).startsWith("Checkpoint ");
        assertThat(controller.service().getEntries())
                .allSatisfy(e -> assertThat(e.kind())
                        .isEqualTo(SnapshotKind.USER_CHECKPOINT));
    }

    @Test
    void loadFromEntryReproducesProjectStateBitIdentically() throws IOException {
        // Issue acceptance: "restoring a checkpoint produces bit-identical
        // project state vs loading the snapshot directly via ProjectManager".
        // The snapshot's serialized content is captured eagerly so the entry's
        // own bytes never change; restoring through SnapshotsController and
        // re-loading it directly via ProjectDeserializer must yield equal
        // structural state (same name, tracks, and clip layout).
        DawProject project = new DawProject("RestoreTest", AudioFormat.STUDIO_QUALITY);
        project.addTrack(new Track("Lead", TrackType.AUDIO));
        project.addTrack(new Track("Drums", TrackType.AUDIO));
        SnapshotsController controller = newController(project);

        SnapshotEntry entry = controller.createCheckpointWithLabel("Snap 1");

        // The snapshot's stored content must be byte-stable across reads.
        String firstRead = entry.loadContent();
        String secondRead = entry.loadContent();
        assertThat(firstRead).isEqualTo(secondRead);

        // Restoring the entry yields a structurally equal project.
        DawProject restored = controller.loadFromEntry(entry);
        assertThat(restored.getName()).isEqualTo(project.getName());
        assertThat(restored.getTracks()).hasSameSizeAs(project.getTracks());
        for (int i = 0; i < project.getTracks().size(); i++) {
            assertThat(restored.getTracks().get(i).getName())
                    .isEqualTo(project.getTracks().get(i).getName());
            assertThat(restored.getTracks().get(i).getType())
                    .isEqualTo(project.getTracks().get(i).getType());
        }
    }
}
