package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.archive.ArchiveHeader;
import com.benesquivelmusic.daw.core.persistence.journal.ProjectContext;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry;
import com.benesquivelmusic.daw.core.snapshot.SnapshotBrowserService;
import com.benesquivelmusic.daw.core.snapshot.SnapshotEntry;
import com.benesquivelmusic.daw.core.snapshot.SnapshotKind;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 330 (studio-engineer rulings R4 and Q1) — a hand-edited note the
 * open cannot read ({@code velocity="128"}) qualifies the door's one
 * announcement, end to end, through each door that reads a project
 * document: a plain open of {@code project.daw}; an archive restore and a
 * journal replay; and a snapshot restore. The archive restore and the
 * journal replay read their own document (the archive's, the checkpoint)
 * and re-serialize it into {@code project.daw} before the reopen, so the
 * announcement counts their read's items. The reopen finds none of the
 * skipped note, which is gone from the rewritten file; a placeholder effect
 * would be found again, which is why the door's list replaces the load's. No
 * degraded backup is owed (the archive and the checkpoint keep the
 * original). Exactly one notification, a WARNING, counts only, no
 * {@code Review…} (there are no missing assets).
 */
@ExtendWith(JavaFxToolkitExtension.class)
class UnreadItemsQualifyTheOpenAnnouncementTest {

    /** One readable note and one the open cannot read, on the only track. */
    private static final String ONE_BAD_NOTE = """
            <daw-project version="%d">
              <metadata><name>Song</name></metadata>
              <tracks>
                <track id="00000000-0000-0000-0000-0000000000a1" name="Keys" type="MIDI">
                  <midi-clip locked="false">
                    <notes>
                      <note note-number="60" start-column="0" duration-columns="4" velocity="100" channel="0"/>
                      <note note-number="62" start-column="4" duration-columns="4" velocity="128" channel="0"/>
                    </notes>
                  </midi-clip>
                </track>
              </tracks>
            </daw-project>
            """.formatted(MigrationRegistry.CURRENT_VERSION);

    @TempDir
    Path workspace;

    private OpenProjectFixture fixture;

    @BeforeEach
    void createTheController() throws Exception {
        fixture = new OpenProjectFixture(workspace);
    }

    @AfterEach
    void closeTheController() throws Exception {
        fixture.close();
    }

    @Test
    void aPlainOpenOfAFileWithAnUnreadableNoteSaysSo() throws Exception {
        Path directory = projectFolderHolding("Song", ONE_BAD_NOTE);

        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(directory))).isTrue();

        assertAnnouncedOnce("Opened project: " + directory.getFileName() + " — 1 MIDI note skipped");
        assertThat(fixture.presenter.presentations).as("no asset is missing: no report").isEmpty();
    }

    @Test
    void anArchiveRestoreCountsItsOwnReadsItemsAndOwesNoBackup() throws Exception {
        Path archive = archiveHolding("Song", ONE_BAD_NOTE);
        // As the chooser hands it: a new folder in an existing one.
        Path destination = Files.createDirectories(workspace.resolve("restored")).resolve("Song-20261021-120000");

        // The worker half runs off the FX thread, as the restore's virtual thread runs it,
        // and posts the FX half; the barrier lets it run.
        fixture.lifecycle().restoreArchive(archive, destination, () -> { });
        runOnFx(() -> { });

        assertAnnouncedOnce("Restored archive: Song (1 MIDI note skipped)");
        assertThat(Files.readString(destination.resolve("project.daw")))
                .as("fixture: the restore rewrote project.daw without the unreadable note")
                .doesNotContain("velocity=\"128\"").contains("velocity=\"100\"");
        assertThat(fixture.projectManager.getLastUnreadItems()).as("the reopen found nothing to count").isEmpty();

        runOnFx(this::save);
        assertThat(backupsIn(destination)).as("the archive keeps the original: no backup in the destination")
                .isEmpty();
    }

    @Test
    void aJournalReplayCountsItsCheckpointsItemsAndOwesNoDegradedBackup() throws Exception {
        Path directory = projectFolderHolding("Song", ONE_BAD_NOTE.replace("velocity=\"128\"", "velocity=\"90\""));
        Path checkpoints = Files.createDirectories(ProjectContext.forProject(directory).checkpointDirectory());
        Files.writeString(checkpoints.resolve("checkpoint-999-20261021T120000.daw"), ONE_BAD_NOTE);

        // The worker half runs off the FX thread, as the replay's virtual thread runs it,
        // and posts the FX half; the barrier lets it run.
        fixture.lifecycle().replayJournal(directory);
        runOnFx(() -> { });

        assertAnnouncedOnce("Recovered 0 changes from the journal — 1 MIDI note skipped");
        assertThat(fixture.projectManager.getLastUnreadItems()).as("the reopen found nothing to count").isEmpty();

        runOnFx(this::save);
        assertThat(backupsIn(directory)).as("only the pre-recovery backup the replay itself wrote")
                .singleElement().satisfies(backup ->
                        assertThat(backup.getFileName().toString()).startsWith("project.daw.recovered."));
    }

    @Test
    void aSnapshotRestoreCountsItsOwnReadsItems() throws Exception {
        Path directory = projectFolderHolding("Song", ONE_BAD_NOTE.replace("velocity=\"128\"", "velocity=\"90\""));
        fixture.projectManager.openProject(directory);
        SnapshotsController snapshots = new SnapshotsController(new SnapshotBrowserService(),
                new CheckpointManager(AutoSaveConfig.DEFAULT), fixture.projectManager,
                new SnapshotsController.Deps(() -> null, fixture.projectManager::getCurrentDawProject,
                        () -> true, (restored, label, missingAssets, unreadItems) ->
                        fixture.lifecycle().installRestoredSnapshot(restored, label, missingAssets, unreadItems)));
        SnapshotEntry entry = new SnapshotEntry("edited", Instant.parse("2026-10-21T12:00:00Z"),
                SnapshotKind.NAMED_SNAPSHOT, "Before the edit", null, () -> ONE_BAD_NOTE);

        runOnFx(() -> snapshots.restoreEntry(entry));

        assertAnnouncedOnce("Restored snapshot: Before the edit — 1 MIDI note skipped");
    }

    private void assertAnnouncedOnce(String message) throws Exception {
        assertThat(fixture.notifications()).as("exactly one announcement for the open").singleElement()
                .satisfies(entry -> {
                    assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING);
                    assertThat(entry.message()).isEqualTo(message);
                    assertThat(entry.actionLabel()).as("no Review…: no asset is missing").isEmpty();
                });
    }

    /** Saves the open project; FX thread. */
    private void save() {
        try {
            fixture.projectManager.saveProject();
        } catch (IOException e) {
            throw new AssertionError("the save failed", e);
        }
    }

    /** A project folder whose {@code project.daw} holds {@code xml}, made by a manager of its own. */
    private Path projectFolderHolding(String name, String xml) throws IOException {
        ProjectManager other = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT));
        Path directory = other.createProject(name, workspace).projectPath();
        other.abandonProject();
        Files.writeString(directory.resolve("project.daw"), xml);
        return directory;
    }

    /** A {@code .dawz} whose project document is {@code xml}, its header naming {@code projectName}. */
    private Path archiveHolding(String projectName, String xml) throws IOException {
        Path archive = workspace.resolve(projectName + ".dawz");
        try (OutputStream out = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(ArchiveHeader.FILE_NAME));
            zip.write(("projectName=" + projectName + "\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(ArchiveHeader.PROJECT_DOC_NAME));
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return archive;
    }

    private static List<Path> backupsIn(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(p -> p.getFileName().toString().endsWith(".bak")).toList();
        }
    }
}
