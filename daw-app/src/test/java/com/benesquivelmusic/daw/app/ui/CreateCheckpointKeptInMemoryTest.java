package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.LockConflictResolution;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;
import com.benesquivelmusic.daw.core.snapshot.SnapshotEntry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.InstantSource;
import java.util.List;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 331, Create Checkpoint where no checkpoint can be written to disk
 * (ruling K2): on a project that was never saved, and on one open read-only,
 * every press still adds the labelled entry to the snapshot browser — kept in
 * memory until the application closes — and shows a WARNING saying so; nothing
 * is asked of the checkpoints, nothing failed, so no checkpoint failure
 * state is set and nothing counts toward the autosave streak.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class CreateCheckpointKeptInMemoryTest {

    @TempDir
    Path workspace;

    private SaveFlowFixture fixture;

    @BeforeEach
    void openSongA() throws Exception {
        fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT);
    }

    @AfterEach
    void close() throws Exception {
        fixture.close();
    }

    @Test
    void theWarningsAreTheRulingsOwnWords() {
        assertThat(SnapshotsController.KEPT_IN_MEMORY_UNSAVED_MESSAGE).isEqualTo("Checkpoint kept in memory only,"
                + " until the application closes: save the project to write checkpoints to disk.");
        assertThat(SnapshotsController.KEPT_IN_MEMORY_READ_ONLY_MESSAGE).isEqualTo("Checkpoint kept in memory only,"
                + " until the application closes: this project is open read-only, so no checkpoint is written to"
                + " its folder.");
    }

    @Test
    void onAProjectNeverSavedEachPressKeepsTheEntryInMemoryAndWarnsWithoutAFailure() throws Exception {
        // As at a fresh start: the Untitled project is not a project on disk.
        runOnFx(() -> fixture.projectManager.closeProject());

        SnapshotEntry first = onFx(() -> fixture.snapshots().createCheckpointWithLabel("Before the comp"));
        SnapshotEntry second = onFx(() -> fixture.snapshots().createCheckpointWithLabel("After the comp"));
        awaitNotifications(2);

        assertThat(List.of(first, second)).as("each press adds the labelled entry").doesNotContainNull()
                .extracting(SnapshotEntry::label).containsExactly("Before the comp", "After the comp");
        assertThat(fixture.notifications()).extracting(NotificationEntry::level, NotificationEntry::message)
                .as("a WARNING every press, nothing else")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(NotificationLevel.WARNING,
                                SnapshotsController.KEPT_IN_MEMORY_UNSAVED_MESSAGE),
                        org.assertj.core.groups.Tuple.tuple(NotificationLevel.WARNING,
                                SnapshotsController.KEPT_IN_MEMORY_UNSAVED_MESSAGE));
        assertNothingFailedAndNothingWasWritten();
        assertThat(fixture.prompts).as("no location prompt either").hasValue(0);
    }

    @Test
    void onAReadOnlyOpenEachPressKeepsTheEntryInMemoryAndWarnsWithoutAFailure() throws Exception {
        Path songB = fixture.anotherProjectOnDisk("Song B");
        ProjectLockManager anotherSession = new ProjectLockManager(InstantSource.system(), "bob", "other-host", 2L);
        assertThat(anotherSession.tryAcquire(songB).wasAcquired()).as("fixture: another session holds Song B")
                .isTrue();
        fixture.projectManager.setLockConflictHandler((holder, stale) -> LockConflictResolution.OPEN_READ_ONLY);
        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(songB))).isTrue();
        assertThat(fixture.projectManager.isReadOnly()).as("fixture: open read-only").isTrue();
        List<Path> songBCheckpoints = SaveFlowFixture.checkpointFiles(songB);
        runOnFx(() -> fixture.shown.clear());
        fixture.publishes.clear();

        SnapshotEntry entry = onFx(() -> fixture.snapshots().createCheckpointWithLabel("Listening copy"));
        awaitNotifications(1);

        assertThat(entry).as("the labelled entry").isNotNull();
        assertThat(fixture.notifications()).extracting(NotificationEntry::level, NotificationEntry::message)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(NotificationLevel.WARNING,
                        SnapshotsController.KEPT_IN_MEMORY_READ_ONLY_MESSAGE));
        assertNothingFailedAndNothingWasWritten();
        assertThat(SaveFlowFixture.checkpointFiles(songB)).as("nothing written to the folder another session owns")
                .isEqualTo(songBCheckpoints);
    }

    private void assertNothingFailedAndNothingWasWritten() throws Exception {
        fixture.pulse();
        assertThat(onFx(fixture.progress::isLastCheckpointSucceeded)).as("no checkpoint failure state").isTrue();
        assertThat(onFx(fixture.progress::getCheckpointFailureCause)).isNull();
        assertThat(fixture.publishes.done(SaveFlowFixture::isCheckpoint)).as("no checkpoint written").isEmpty();
    }

    private void awaitNotifications(int count) throws Exception {
        fixture.awaitOnFx(() -> fixture.shown.getEntries().size() >= count, count + " notifications");
        runOnFx(() -> { });
    }
}
