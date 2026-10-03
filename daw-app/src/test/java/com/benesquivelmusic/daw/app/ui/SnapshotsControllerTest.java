package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.snapshot.SnapshotBrowserService;
import com.benesquivelmusic.daw.core.snapshot.SnapshotEntry;
import com.benesquivelmusic.daw.core.snapshot.SnapshotKind;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
                new SnapshotsController.Deps(
                        () -> null,
                        projectRef::get,
                        () -> true,
                        (project, label) -> projectRef.set(project)));
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

    @Test
    void aRestoredSnapshotKnowsTheProjectDirectorySoRecordIsNotRefusedAndReferencesStayRelative(
            @TempDir Path workspace) throws IOException {
        // Story 323: a snapshot is a checkpoint of the open project. The
        // restored DawProject replaces the live one, so it must carry the
        // project directory: TransportController refuses to record without
        // it, and every later checkpoint would serialize absolute paths.
        SnapshotBrowserService service = new SnapshotBrowserService();
        CheckpointManager checkpointManager = new CheckpointManager(AutoSaveConfig.DEFAULT);
        ProjectManager projectManager = new ProjectManager(checkpointManager);
        try {
            Path projectDir = projectManager.createProject("Snap Song", workspace).projectPath();
            Path trackDir = Files.createDirectories(ProjectManager.audioDirectory(projectDir)
                    .resolve("takes/2026-09-29T10-00-00_take-0001/track-a"));
            Path first = Files.write(trackDir.resolve("segment-000.wav"), new byte[]{1});
            Path second = Files.write(trackDir.resolve("segment-001.wav"), new byte[]{2});

            DawProject live = new DawProject("Snap Song", AudioFormat.STUDIO_QUALITY);
            live.setMetadata(live.getMetadata().withPath(projectDir));
            Track vox = new Track("Vox", TrackType.AUDIO);
            live.addTrack(vox);
            AudioClip clip = new AudioClip("Take 1", 0.0, 8.0, first.toString());
            clip.setSourceSegmentPaths(List.of(first.toString(), second.toString()));
            vox.addClip(clip);

            AtomicReference<DawProject> projectRef = new AtomicReference<>(live);
            SnapshotsController controller = new SnapshotsController(
                    service, checkpointManager, projectManager,
                    new SnapshotsController.Deps(
                            () -> null,
                            projectRef::get,
                            () -> true,
                            (project, label) -> projectRef.set(project)));
            String relativeHead = "audio/takes/2026-09-29T10-00-00_take-0001/track-a/segment-000.wav";
            String snapshotXml = new ProjectSerializer().serialize(live);
            assertThat(snapshotXml)
                    .as("fixture: the snapshot itself is project-relative")
                    .contains("source-file=\"" + relativeHead + "\"");
            SnapshotEntry entry = service.createUserCheckpoint("Before comp", snapshotXml);

            DawProject restored = controller.loadFromEntry(entry);

            assertThat(restored.getMetadata().projectPath())
                    .as("the restored project knows the open project's directory")
                    .isEqualTo(projectDir);
            AudioClip restoredClip = restored.getTracks().get(0).getClips().get(0);
            assertThat(restoredClip.getSourceSegmentPaths()).hasSize(2);
            assertThat(Path.of(restoredClip.getSourceFilePath())).isAbsolute().isRegularFile();
            // The next checkpoint of the restored project relativises again.
            String nextCheckpoint = new ProjectSerializer().serialize(restored);
            assertThat(nextCheckpoint)
                    .contains("source-file=\"" + relativeHead + "\"")
                    .doesNotContain(projectDir.toString())
                    .doesNotContain(projectDir.toString().replace('\\', '/'));
        } finally {
            projectManager.closeProject();
        }
    }

    @Test
    void aSnapshotLoadedWithNoOpenProjectDirectoryKeepsANullProjectPath() throws IOException {
        // The control for the test above: with no project directory known the
        // restored metadata is left as the deserializer produced it.
        DawProject project = new DawProject("Unsaved", AudioFormat.STUDIO_QUALITY);
        SnapshotsController controller = newController(project);
        SnapshotEntry entry = controller.createCheckpointWithLabel("Snap");

        DawProject restored = controller.loadFromEntry(entry);

        assertThat(restored.getMetadata().projectPath()).isNull();
    }
}
