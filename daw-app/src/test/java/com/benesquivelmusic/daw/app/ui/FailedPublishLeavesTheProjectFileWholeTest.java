package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AtomicFileWriter;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.persistence.archive.ArchiveHeader;
import com.benesquivelmusic.daw.core.persistence.journal.JournalRecord;
import com.benesquivelmusic.daw.core.persistence.journal.ProjectContext;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationReport;
import com.benesquivelmusic.daw.core.project.DawProject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 331 — the application's own writes of a project file go through the
 * {@link AtomicFileWriter} (Persistence Integrity book §4.1, §2.2): the
 * recovered project file a journal replay writes, the {@code project.daw} a
 * restored archive is rewritten to, and the migration roll-back's copy of a
 * backup. Each is driven into the crash window the writer guards: through
 * the controller's writer seam ({@link ProjectLifecycleController#setFileWriter}),
 * the atomic move is refused and its fallback fails too. The project file is
 * then byte-identical to what it was, no temp of the writer is left beside
 * it, and the flow reports the writer's original exception — the fallback's,
 * whose message it carries.
 *
 * <p>Each flow's worker half is driven on the test thread
 * ({@code replayJournal}, {@code restoreArchive}), as the recovery and
 * archive tests do; the roll-back runs on the FX thread, where it runs in
 * the application, and reports its failure in the same FX call.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class FailedPublishLeavesTheProjectFileWholeTest {

    /** The message of the fallback move's failure: the writer's original exception. */
    private static final String PUBLISH_FAILURE = "injected failure of the fallback move";
    private static final int JOURNALED_CHANGES = 3;

    @TempDir
    Path workspace;

    private SaveFlowFixture fixture;

    @BeforeEach
    void openSongA() throws Exception {
        fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT);
        runOnFx(() -> fixture.lifecycle().setFileWriter(fixture.writer));
    }

    @AfterEach
    void close() throws Exception {
        fixture.close();
    }

    @Test
    void aRecoveredProjectWhosePublishFailsLeavesTheProjectFileAsItWasAndReportsTheWritersFailure()
            throws Exception {
        Path songB = aProjectWithACheckpointAndAJournal("Song B");
        Path projectFile = ProjectContext.forProject(songB).projectFile();
        byte[] before = Files.readAllBytes(projectFile);
        bothMovesFail(projectFile);
        ProjectLifecycleController lifecycle = onFx(fixture::lifecycle);

        lifecycle.replayJournal(songB);

        assertThat(projectFile).as("project.daw is the file it was before the replay").hasBinaryContent(before);
        assertThat(writerTempsIn(songB)).as("no temp of the writer is left beside it").isEmpty();
        assertThat(fixture.publishes.done(isFile(projectFile))).as("nothing was published over it").isEmpty();
        assertThat(recoveredBackupsIn(songB)).as("the prior file was backed up first, for real").hasSize(1);
        assertThat(ProjectContext.forProject(songB).journalDirectory())
                .as("the journal is kept: the replay's changes were never written").isDirectory();
        fixture.awaitOnFx(() -> fixture.shown.getEntries().stream()
                        .anyMatch(entry -> entry.message().startsWith("Could not write recovered project: ")),
                "the recovery reported the failed write");
        assertThat(fixture.notifications()).extracting(NotificationEntry::message)
                .as("the recovery's outcome carries the writer's original exception")
                .contains("Could not write recovered project: " + PUBLISH_FAILURE + "; opened the last clean save");
    }

    @Test
    void aRestoredArchiveWhoseHeadPublishFailsLeavesTheExtractedProjectFileAsItWasAndReportsTheWritersFailure()
            throws Exception {
        Path archive = fixture.archiveOnDisk("Song R");
        Path destination = fixture.restoreDestination("Song R");
        Path projectFile = destination.resolve("project.daw");
        byte[] extracted = projectDocumentOf(archive);
        bothMovesFail(projectFile);
        ProjectLifecycleController lifecycle = onFx(fixture::lifecycle);
        AtomicBoolean finished = new AtomicBoolean();

        lifecycle.restoreArchive(archive, destination, () -> finished.set(true));

        fixture.awaitOnFx(finished::get, "the restore's FX half ran");
        assertThat(projectFile).as("project.daw is the file the extraction wrote").hasBinaryContent(extracted);
        assertThat(writerTempsIn(destination)).as("no temp of the writer is left beside it").isEmpty();
        assertThat(fixture.publishes.done(isFile(projectFile))).as("nothing was published over it").isEmpty();
        assertThat(fixture.notifications()).singleElement().satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
            assertThat(entry.message()).as("the restore's outcome carries the writer's original exception")
                    .isEqualTo("Restore failed: " + PUBLISH_FAILURE);
        });
        assertThat(onFx(() -> fixture.projectManager.getCurrentProject().projectPath()))
                .as("Song A stays open").isEqualTo(fixture.songA);
    }

    @Test
    void aRollBackWhoseCopyFailsLeavesTheProjectFileAsItWasAndReportsTheWritersFailureOnTheFxThread()
            throws Exception {
        Path projectFile = fixture.songA.resolve("project.daw");
        DawProject original = new DawProject("Song A", AudioFormat.CD_QUALITY);
        original.createAudioTrack("Before the migration");
        Path backup = Files.writeString(fixture.songA.resolve("project.daw.v1.20260101-000000.bak"),
                new ProjectSerializer().serialize(original));
        byte[] before = Files.readAllBytes(projectFile);
        int current = MigrationRegistry.CURRENT_VERSION;
        MigrationReport report = new MigrationReport(1, current,
                List.of(new MigrationReport.AppliedMigration(1, current, "test migration")), Instant.EPOCH);
        DawProject songAModel = onFx(fixture.project::get);
        bothMovesFail(projectFile);

        runOnFx(() -> fixture.lifecycle().rollbackMigration(fixture.songA, report, backup));

        assertThat(fixture.notifications())
                .as("the roll-back ran on the FX thread and reported its failure in that same call")
                .singleElement().satisfies(entry -> {
                    assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
                    assertThat(entry.message()).isEqualTo("Roll back failed: " + PUBLISH_FAILURE);
                });
        assertThat(projectFile).as("project.daw is the file it was before the roll-back").hasBinaryContent(before);
        assertThat(writerTempsIn(fixture.songA)).as("no temp of the writer is left beside it").isEmpty();
        assertThat(onFx(fixture.project::get)).as("Song A is not reloaded").isSameAs(songAModel);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Fails the next publish of {@code file} in the crash window: the atomic
     * move is refused ({@link AtomicMoveNotSupportedException}), so the
     * writer falls back to a plain replace, which fails with
     * {@link #PUBLISH_FAILURE}.
     */
    private void bothMovesFail(Path file) {
        fixture.publishes.failNext(isFile(file),
                new AtomicMoveNotSupportedException(null, file.toString(), "injected: no atomic move"));
        fixture.publishes.failNext(isFile(file), new IOException(PUBLISH_FAILURE));
    }

    private static Predicate<Path> isFile(Path file) {
        Path expected = file.toAbsolutePath().normalize();
        return target -> target.toAbsolutePath().normalize().equals(expected);
    }

    /** The files in {@code directory} that are a temp of the writer, by the writer's own grammar. */
    private static List<String> writerTempsIn(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(entry -> entry.getFileName().toString())
                    .filter(name -> AtomicFileWriter.publishedNameOf(name).isPresent())
                    .toList();
        }
    }

    private static List<Path> recoveredBackupsIn(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(entry -> entry.getFileName().toString().startsWith("project.daw.recovered."))
                    .toList();
        }
    }

    /** The project document an archive holds: what its extraction writes as {@code project.daw}. */
    private static byte[] projectDocumentOf(Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry document = zip.getEntry(ArchiveHeader.PROJECT_DOC_NAME);
            assertThat(document).as("fixture: the archive holds a project document").isNotNull();
            try (InputStream in = zip.getInputStream(document)) {
                return in.readAllBytes();
            }
        }
    }

    /**
     * A saved project on disk with a checkpoint of itself (the newest, so
     * the replay replays onto it) and a journal of {@link #JOURNALED_CHANGES}
     * changes after it.
     */
    private Path aProjectWithACheckpointAndAJournal(String name) throws IOException {
        Path directory = fixture.anotherProjectOnDisk(name);
        ProjectContext context = ProjectContext.forProject(directory);
        DawProject checkpointed = new DawProject(name, AudioFormat.CD_QUALITY);
        checkpointed.createAudioTrack("Vocals");
        Files.createDirectories(context.checkpointDirectory());
        Files.writeString(context.checkpointDirectory().resolve("checkpoint-999-20260101T000000.daw"),
                new ProjectSerializer().serialize(checkpointed));
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        for (int sequence = 0; sequence < JOURNALED_CHANGES; sequence++) {
            ByteBuffer frame = JournalRecord.ofMutation(sequence, Instant.ofEpochMilli(1_000L + sequence),
                    "MixerEvent.MuteChanged", UUID.randomUUID(), null).toBuffer();
            byte[] bytes = new byte[frame.remaining()];
            frame.get(bytes);
            frames.writeBytes(bytes);
        }
        Files.createDirectories(context.journalDirectory());
        Files.write(context.journalDirectory().resolve("segment-000.bin"), frames.toByteArray());
        return directory;
    }
}
