package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AtomicFileWriter;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.persistence.journal.JournalRecord;
import com.benesquivelmusic.daw.core.persistence.journal.JournalState;
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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Story 331, ruling M3, the application's own writers of {@code project.daw}
 * — the migration roll-back and the journal replay's recovered file: a
 * {@code project.daw} that is a symbolic link to a {@code .daw} file stays a
 * link, and the file it leads to is replaced, as the project manager's save
 * does; one that leads to anything else is refused with the writer's reason,
 * and nothing is written. Skipped where this account cannot create symbolic
 * links (on Windows that takes Developer Mode or an elevated shell).
 */
@ExtendWith(JavaFxToolkitExtension.class)
class AppWritersFollowAProjectFileLinkTest {

    private static final String NOT_A_DAW_FILE_LINK =
            "a symbolic link that does not lead to a .daw file; it was not replaced";

    @TempDir
    Path workspace;

    private SaveFlowFixture fixture;
    private Path shared;

    @BeforeEach
    void openSongA() throws Exception {
        assumeSymbolicLinks(workspace);
        fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT);
        // The controller's own writes go through the fixture's writer, whose mover records each publish.
        runOnFx(() -> fixture.lifecycle().setFileWriter(fixture.writer));
        shared = Files.createDirectories(workspace.resolve("shared"));
    }

    @AfterEach
    void close() throws Exception {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void aRollBackThroughAProjectFileLinkReplacesTheDawFileItLeadsToAndKeepsTheLink() throws Exception {
        Path projectFile = fixture.songA.resolve("project.daw");
        Path song = shared.resolve("Song.daw");
        Files.move(projectFile, song);
        Files.createSymbolicLink(projectFile, Path.of("..", "shared", "Song.daw"));
        Path backup = aMigrationBackupOf(fixture.songA);

        runOnFx(() -> fixture.lifecycle().rollbackMigration(fixture.songA, aMigrationReport(), backup));

        assertThat(Files.isSymbolicLink(projectFile)).as("project.daw is still the link").isTrue();
        assertThat(song).as("the .daw file it leads to holds the backup").hasSameBinaryContentAs(backup);
        assertThat(publishedOutsideCheckpoints()).as("the one publish replaced the .daw file the link leads to")
                .containsExactly(song.toRealPath());
        assertThat(writerTempsIn(fixture.songA)).isEmpty();
        assertThat(writerTempsIn(shared)).isEmpty();
        assertThat(fixture.notifications()).extracting(NotificationEntry::level)
                .doesNotContain(NotificationLevel.ERROR);
    }

    @Test
    void aRollBackThroughALinkThatLeadsToNoDawFileIsRefusedAndWritesNothing() throws Exception {
        Path projectFile = fixture.songA.resolve("project.daw");
        Path notes = Files.writeString(shared.resolve("notes.txt"), "not a project");
        Files.delete(projectFile);
        Files.createSymbolicLink(projectFile, Path.of("..", "shared", "notes.txt"));
        Path backup = aMigrationBackupOf(fixture.songA);
        DawProject songAModel = onFx(fixture.project::get);

        runOnFx(() -> fixture.lifecycle().rollbackMigration(fixture.songA, aMigrationReport(), backup));

        assertThat(fixture.notifications()).singleElement().satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
            assertThat(entry.message()).isEqualTo("Roll back failed: "
                    + new FileSystemException(projectFile.toString(), null, NOT_A_DAW_FILE_LINK).getMessage());
        });
        assertThat(Files.isSymbolicLink(projectFile)).as("the link is as it was").isTrue();
        assertThat(Files.readString(notes)).as("the file it leads to is untouched").isEqualTo("not a project");
        assertThat(fixture.publishes.done()).as("nothing was published").isEmpty();
        assertThat(writerTempsIn(fixture.songA)).isEmpty();
        assertThat(writerTempsIn(shared)).isEmpty();
        assertThat(onFx(fixture.project::get)).as("Song A is not reloaded").isSameAs(songAModel);
    }

    @Test
    void aJournalReplayThroughAProjectFileLinkWritesTheDawFileItLeadsToAndKeepsTheLink() throws Exception {
        Path songB = aProjectWithACheckpointAndAJournal("Song B");
        Path projectFile = songB.resolve("project.daw");
        Path song = shared.resolve("Song B.daw");
        Files.move(projectFile, song);
        Files.createSymbolicLink(projectFile, Path.of("..", "shared", "Song B.daw"));
        String before = Files.readString(song);

        // The replay's worker half, off the FX thread; its FX half then opens Song B.
        fixture.lifecycle().replayJournal(songB);

        assertThat(Files.isSymbolicLink(projectFile)).as("project.daw is still the link").isTrue();
        assertThat(Files.readString(song)).as("the .daw file it leads to holds the recovered project")
                .contains("Vocals");
        try (Stream<Path> entries = Files.list(songB)) {
            assertThat(entries.filter(entry -> entry.getFileName().toString().startsWith("project.daw.recovered."))
                    .toList()).as("the pre-recovery file was backed up first").singleElement()
                    .satisfies(recoveredBackup -> assertThat(Files.readString(recoveredBackup)).isEqualTo(before));
        }
        assertThat(writerTempsIn(songB)).isEmpty();
        assertThat(writerTempsIn(shared)).isEmpty();
        fixture.awaitOnFx(() -> songB.equals(fixture.projectManager.getCurrentProject().projectPath()),
                "the recovered Song B opened through its link");
        assertThat(publishedOutsideCheckpoints()).as("the backup, then the one publish of the recovered project")
                .hasSize(2)
                .satisfies(published -> assertThat(published.getFirst().getFileName().toString())
                        .startsWith("project.daw.recovered."))
                .endsWith(song.toRealPath());
    }

    @Test
    void aJournalReplayThroughALinkThatLeadsToNoDawFileBacksNothingUpAndWritesNothing() throws Exception {
        Path songB = aProjectWithACheckpointAndAJournal("Song B");
        Path projectFile = songB.resolve("project.daw");
        Path notes = Files.writeString(shared.resolve("notes.txt"), "not a project");
        Files.delete(projectFile);
        Path leadsTo = Path.of("..", "shared", "notes.txt");
        Files.createSymbolicLink(projectFile, leadsTo);

        // The replay's worker half, off the FX thread; its FX half then reports the refused write.
        fixture.lifecycle().replayJournal(songB);
        fixture.awaitOnFx(() -> !fixture.shown.getEntries().isEmpty(), "the recovery's outcome was shown");

        assertThat(fixture.notifications()).singleElement().satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
            assertThat(entry.message()).isEqualTo("Could not write recovered project: "
                    + new FileSystemException(projectFile.toString(), null, NOT_A_DAW_FILE_LINK).getMessage()
                    + "; opened the last clean save");
        });
        try (Stream<Path> entries = Files.list(songB)) {
            assertThat(entries.map(entry -> entry.getFileName().toString())
                    .filter(name -> name.startsWith("project.daw.recovered.")).toList())
                    .as("nothing was read through the link into a backup").isEmpty();
        }
        assertThat(fixture.publishes.done()).as("nothing was published").isEmpty();
        assertThat(writerTempsIn(songB)).isEmpty();
        assertThat(writerTempsIn(shared)).isEmpty();
        assertThat(Files.isSymbolicLink(projectFile)).as("the link is as it was").isTrue();
        assertThat(Files.readSymbolicLink(projectFile)).isEqualTo(leadsTo);
        assertThat(Files.readString(notes)).as("the file it leads to is untouched").isEqualTo("not a project");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Skips the test where this account cannot create symbolic links in {@code folder}. */
    private static void assumeSymbolicLinks(Path folder) throws IOException {
        Path probe = folder.resolve("symbolic-link-check");
        String refusal = "";
        try {
            Files.createSymbolicLink(probe, Path.of("symbolic-link-check-target"));
            Files.delete(probe);
        } catch (UnsupportedOperationException | FileSystemException refused) {
            refusal = String.valueOf(refused);
        }
        assumeTrue(refusal.isEmpty(), "this account cannot create symbolic links here: " + refusal);
    }

    /** A pre-migration backup beside {@code projectDirectory}'s {@code project.daw}, as a first save writes it. */
    private static Path aMigrationBackupOf(Path projectDirectory) throws IOException {
        DawProject original = new DawProject("Song A", AudioFormat.CD_QUALITY);
        original.createAudioTrack("Before the migration");
        return Files.writeString(projectDirectory.resolve("project.daw.v1.20260101-000000.bak"),
                new ProjectSerializer().serialize(original));
    }

    private static MigrationReport aMigrationReport() {
        int current = MigrationRegistry.CURRENT_VERSION;
        return new MigrationReport(1, current,
                List.of(new MigrationReport.AppliedMigration(1, current, "test migration")), Instant.EPOCH);
    }

    /**
     * The files published so far through the fixture's writer that are not
     * checkpoints — which the project the writes lead to opening may write —
     * oldest first.
     */
    private List<Path> publishedOutsideCheckpoints() {
        return fixture.publishes.done(target -> !SaveFlowFixture.isCheckpoint(target)).stream()
                .map(SaveFlowFixture.Publish::target).toList();
    }

    /** The names in {@code folder} that are the atomic writer's temps. */
    private static List<String> writerTempsIn(Path folder) throws IOException {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.map(entry -> entry.getFileName().toString())
                    .filter(name -> AtomicFileWriter.publishedNameOf(name).isPresent()).toList();
        }
    }

    /**
     * A saved project with a hash-verified checkpoint capture of "Vocals" and
     * three journaled changes after that capture, independent of the current head.
     */
    private Path aProjectWithACheckpointAndAJournal(String name) throws IOException {
        Path directory = fixture.anotherProjectOnDisk(name);
        DawProject checkpointed = new DawProject(name, AudioFormat.CD_QUALITY);
        checkpointed.createAudioTrack("Vocals");
        ProjectContext context = ProjectContext.forProject(directory);
        Files.createDirectories(context.checkpointDirectory());
        for (Path written : SaveFlowFixture.checkpointFiles(directory)) {
            Files.delete(written);
        }
        Path checkpoint = Files.writeString(context.checkpointDirectory().resolve("checkpoint-999-20260101T000000.daw"),
                new ProjectSerializer().serialize(checkpointed), StandardCharsets.UTF_8);
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        new JournalRecord(0L, Instant.EPOCH, JournalRecord.CHECKPOINT_TYPE, null,
                JournalState.capture(-1L, checkpoint)).writeTo(java.nio.channels.Channels.newChannel(frames));
        for (int sequence = 0; sequence < 3; sequence++) {
            ByteBuffer frame = JournalRecord.ofMutation(sequence + 1L, Instant.ofEpochMilli(1_000L + sequence),
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
