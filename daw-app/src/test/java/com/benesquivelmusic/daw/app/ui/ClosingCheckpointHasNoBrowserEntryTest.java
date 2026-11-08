package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointEvent;
import com.benesquivelmusic.daw.core.persistence.CheckpointOrigin;
import com.benesquivelmusic.daw.core.snapshot.SnapshotEntry;
import com.benesquivelmusic.daw.core.snapshot.SnapshotKind;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, design §4.3 and the studio engineer's CLOSING CHECKPOINT
 * ruling: the closing checkpoint — Discard and close's misclick copy — is
 * its own origin, {@code CLOSING}, not {@code USER}: it makes no in-memory
 * entry in the snapshot browser and carries no label; on disk it is an
 * ordinary checkpoint, listed as one when its folder is browsed.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ClosingCheckpointHasNoBrowserEntryTest {

    @TempDir
    Path workspace;

    @Test
    void theClosingCheckpointMakesNoUserEntryAndNoLabel() throws Exception {
        try (ExitFixture fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT)) {
            runOnFx(() -> fixture.save.snapshots().registerCurrentProjectDirectory());
            fixture.save.save();
            fixture.save.settle();
            fixture.edit("Bass");
            fixture.save.promptAnswer = "Discard and close";

            fixture.closeProject();
            fixture.await(() -> fixture.save.project.get().getName().equals("Untitled Project"), "Untitled");

            CheckpointEvent.Written closing = fixture.save.checkpointEvents.stream()
                    .filter(event -> event instanceof CheckpointEvent.Written written
                            && written.origin() == CheckpointOrigin.CLOSING)
                    .map(CheckpointEvent.Written.class::cast).findFirst().orElseThrow();
            List<SnapshotEntry> entries = onFx(() -> fixture.save.snapshots().service().getEntries());
            assertThat(entries).as("no in-memory browser entry").noneMatch(entry -> entry.kind()
                    == SnapshotKind.USER_CHECKPOINT);
            assertThat(entries).filteredOn(entry -> entry.id().contains(closing.file().getFileName().toString())
                    || entry.label().contains(closing.file().getFileName().toString()))
                    .allSatisfy(entry -> assertThat(entry.kind()).as("listed only as a checkpoint on disk")
                            .isEqualTo(SnapshotKind.AUTOSAVE));
        }
    }
}
