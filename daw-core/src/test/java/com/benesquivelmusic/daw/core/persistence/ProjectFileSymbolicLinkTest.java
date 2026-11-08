package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.SaveTestSupport.RecordingMover;
import com.benesquivelmusic.daw.core.persistence.SaveTestSupport.RecordingMover.Publish;
import com.benesquivelmusic.daw.core.project.DawProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.failed;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.saved;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.tempsIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A {@code project.daw} that is a symbolic link (story 331, ruling M3): its
 * writers — the save, and the metadata file a legacy project's close writes —
 * keep the link and replace the {@code .daw} file it leads to, with that
 * file's temp beside it and its POSIX permission bits kept. A read-only
 * {@code .daw} file is refused naming it, and a link that leads to no
 * {@code .daw} file — nowhere, or to a text file — is refused naming the
 * link: both are ordinary head failures, so nothing changes, the project
 * stays dirty and the rendered document is in a safety checkpoint (ruling
 * G9). A backup the open owes is copied through the link only when the
 * replace would follow it; otherwise nothing is copied and it stays owed.
 * Every test is skipped where the account may not create symbolic links.
 */
class ProjectFileSymbolicLinkTest {

    private static final String NOT_A_DAW_FILE =
            "a symbolic link that does not lead to a .daw file; it was not replaced";

    /** The link's target, relative to the project folder, for the {@code .daw} file kept in a shared folder. */
    private static final Path SHARED_SONG = Path.of("..", "shared", "Song.daw");

    /** A schema 1 project document: its open migrates it, so its first save owes a backup of it. */
    private static final String SCHEMA_ONE = """
            <daw-project version="1">
              <metadata>
                <name>Song</name>
              </metadata>
              <transport loop-enabled="false" position="0.0" tempo="120.0"/>
              <tracks>
                <track id="00000000-0000-0000-0000-0000000000b1" name="Drums" type="AUDIO"/>
              </tracks>
            </daw-project>
            """;

    @TempDir
    Path tempDir;

    private final RecordingMover mover = new RecordingMover();
    private ProjectManager manager;
    private Path projectFile;
    private Path shared;
    private Path song;

    @BeforeEach
    void createAProjectWhoseFileLivesInASharedFolder() throws IOException {
        SaveTestSupport.assumeSymbolicLinks(tempDir);
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, mover);
        manager = SaveTestSupport.manager(
                new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true), writer),
                new ProjectLockManager(), writer, ProjectManager.CHECKPOINT_WAIT);
        Path directory = manager.createProject("Song", tempDir).projectPath();
        projectFile = directory.resolve("project.daw");
        shared = Files.createDirectories(tempDir.resolve("shared"));
        // The metadata file createProject wrote moves to the shared folder; each test links to it, or elsewhere.
        song = Files.move(projectFile, shared.resolve("Song.daw"));
        mover.clear();
    }

    @AfterEach
    void releaseTheManager() {
        if (manager != null) {
            manager.closeProject().join();
        }
    }

    @Test
    void aSaveThroughTheLinkWritesTheDawFileItLeadsToAndKeepsTheLink() throws IOException {
        Files.createSymbolicLink(projectFile, SHARED_SONG);
        boolean posix = Files.getFileStore(song).supportsFileAttributeView(PosixFileAttributeView.class);
        if (posix) {
            // Not a default any umask gives a new file, so a lost mode shows.
            Files.setPosixFilePermissions(song, PosixFilePermissions.fromString("rw----r--"));
        }

        SaveOutcome.Saved outcome = saved(manager, project());

        assertThat(Files.isSymbolicLink(projectFile)).as("the link stays").isTrue();
        assertThat(Files.readSymbolicLink(projectFile)).isEqualTo(SHARED_SONG);
        String written = Files.readString(song);
        assertThat(written).as("the save's capture").contains("<daw-project").contains("name=\"Vox\"");
        assertThat(mover.publishes()).extracting(Publish::target).as("the head was published onto Song.daw")
                .contains(song.toRealPath());
        assertThat(Files.readString(outcome.checkpoint().orElseThrow())).isEqualTo(written);
        assertThat(tempsIn(projectFile.getParent())).isEmpty();
        assertThat(tempsIn(shared)).isEmpty();
        if (posix) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(song)))
                    .as("Song.daw's mode, kept").isEqualTo("rw----r--");
        }
    }

    @Test
    void aReadOnlyDawFileBehindTheLinkIsRefusedNamingItAndTheWorkIsInASafetyCopy() throws IOException {
        Files.createSymbolicLink(projectFile, SHARED_SONG);
        byte[] before = Files.readAllBytes(song);
        String real = song.toRealPath().toString();
        DawProject project = project();

        makeReadOnly(song, true);
        try {
            assumeFalse(Files.isWritable(song), "a superuser may write a read-only file");

            SaveOutcome.Failed outcome = failed(manager, project);

            assertThat(outcome.cause()).isInstanceOfSatisfying(AccessDeniedException.class, denied -> {
                assertThat(denied.getFile()).as("names Song.daw, not the link").isEqualTo(real);
                assertThat(denied.getReason())
                        .isEqualTo("the file is read-only or not writable; it was not replaced");
            });
            assertThat(Files.readAllBytes(song)).isEqualTo(before);
            assertThat(outcome.safetyCheckpoint()).as("ruling G9: the work is in a checkpoint").isPresent();
            assertThat(project.isDirty()).isTrue();
            assertThat(Files.isSymbolicLink(projectFile)).isTrue();
            assertThat(tempsIn(shared)).isEmpty();
        } finally {
            makeReadOnly(song, false);
        }
    }

    @ParameterizedTest(name = "project.daw -> ../shared/{0}")
    @ValueSource(strings = {"Gone.daw", "notes.txt"})
    void aLinkThatDoesNotLeadToADawFileIsRefusedNamingItAndTheWorkIsInASafetyCopy(String linkedName)
            throws IOException {
        Path notes = Files.writeString(shared.resolve("notes.txt"), "a text file that must never be overwritten");
        Path relative = Path.of("..", "shared", linkedName);
        Files.createSymbolicLink(projectFile, relative);
        DawProject project = project();

        SaveOutcome.Failed outcome = failed(manager, project);

        assertThat(outcome.cause()).isExactlyInstanceOf(FileSystemException.class)
                .hasMessage(projectFile + ": " + NOT_A_DAW_FILE);
        FileSystemException refused = (FileSystemException) outcome.cause();
        assertThat(refused.getFile()).as("names the link").isEqualTo(projectFile.toString());
        assertThat(refused.getReason()).isEqualTo(NOT_A_DAW_FILE);
        Path safety = outcome.safetyCheckpoint().orElseThrow();
        assertThat(Files.readString(safety)).as("the rendered document").contains("name=\"Vox\"");
        assertThat(project.isDirty()).isTrue();
        assertThat(Files.readSymbolicLink(projectFile)).as("the link is as it was").isEqualTo(relative);
        assertThat(notes).as("never written").hasContent("a text file that must never be overwritten");
        assertThat(shared.resolve("Gone.daw")).as("a missing file is never created").doesNotExist();
        assertThat(tempsIn(projectFile.getParent())).isEmpty();
        assertThat(tempsIn(shared)).isEmpty();
    }

    /**
     * The backup a migrated open owes (ruling F10) is copied through the link
     * only when the save's replace would follow it (ruling M3): a link to a
     * file that is not a {@code .daw} file is refused before anything is read
     * through it, so no copy of that file lands in the project folder, and
     * the backup stays owed until a save through a link to a {@code .daw}
     * file takes it, beside the link, from the file it leads to.
     */
    @Test
    void aBackupOwedThroughALinkTheSaveRefusesCopiesNothingAndStaysOwedForTheNextSave() throws IOException {
        manager.closeProject().join();
        Path schemaOne = Files.writeString(shared.resolve("Song-v1.xml"), SCHEMA_ONE);
        byte[] original = Files.readAllBytes(schemaOne);
        Path toSchemaOne = Path.of("..", "shared", "Song-v1.xml");
        Files.createSymbolicLink(projectFile, toSchemaOne);
        manager.openProject(projectFile.getParent());
        assertThat(manager.getLastMigrationReport().wasMigrated()).as("fixture: the open owes a migration backup")
                .isTrue();
        DawProject project = manager.getCurrentDawProject();

        SaveOutcome.Failed outcome = failed(manager, project);

        assertThat(outcome.cause()).isExactlyInstanceOf(FileSystemException.class)
                .hasMessage(projectFile + ": " + NOT_A_DAW_FILE);
        assertThat(((FileSystemException) outcome.cause()).getFile()).as("names the link")
                .isEqualTo(projectFile.toString());
        assertThat(schemaOneBackupsIn(projectFile.getParent())).as("nothing was copied through the link").isEmpty();
        assertThat(tempsIn(projectFile.getParent())).isEmpty();
        assertThat(tempsIn(shared)).isEmpty();
        assertThat(Files.readAllBytes(schemaOne)).as("Song-v1.xml is as it was").isEqualTo(original);
        assertThat(Files.readSymbolicLink(projectFile)).as("the link is as it was").isEqualTo(toSchemaOne);

        Files.delete(projectFile);
        Files.writeString(song, SCHEMA_ONE);
        Files.createSymbolicLink(projectFile, SHARED_SONG);

        saved(manager, project);

        assertThat(schemaOneBackupsIn(projectFile.getParent())).as("the backup stayed owed: this save took it")
                .singleElement().satisfies(backup -> assertThat(Files.readAllBytes(backup))
                        .as("the schema 1 document").isEqualTo(original));
        assertThat(Files.readSymbolicLink(projectFile)).as("the link stays").isEqualTo(SHARED_SONG);
        assertThat(Files.readString(song)).as("the save's capture").contains("<daw-project version=\"2\"");
    }

    /**
     * A dangling link — its {@code .daw} file on a volume that is offline at
     * the save — is refused like any link the replace would not follow
     * (ruling M3), and the backup the migrated open owes (ruling F10) is not
     * cleared by the refused save: it stays owed, and the first save once
     * the file is back takes it, beside the link, before that file is
     * replaced.
     */
    @Test
    void aBackupOwedThroughADanglingLinkStaysOwedUntilItsDawFileIsBack() throws IOException {
        manager.closeProject().join();
        Files.writeString(song, SCHEMA_ONE);
        byte[] original = Files.readAllBytes(song);
        Files.createSymbolicLink(projectFile, SHARED_SONG);
        manager.openProject(projectFile.getParent());
        assertThat(manager.getLastMigrationReport().wasMigrated()).as("fixture: the open owes a migration backup")
                .isTrue();
        DawProject project = manager.getCurrentDawProject();
        Path offline = Files.move(song, Files.createDirectories(tempDir.resolve("offline")).resolve("Song.daw"));

        SaveOutcome.Failed outcome = failed(manager, project);

        assertThat(outcome.cause()).isExactlyInstanceOf(FileSystemException.class)
                .hasMessage(projectFile + ": " + NOT_A_DAW_FILE);
        assertThat(((FileSystemException) outcome.cause()).getFile()).as("names the link")
                .isEqualTo(projectFile.toString());
        assertThat(schemaOneBackupsIn(projectFile.getParent())).as("nothing was copied through the link").isEmpty();
        assertThat(tempsIn(projectFile.getParent())).isEmpty();
        assertThat(tempsIn(shared)).isEmpty();
        assertThat(song).as("a missing file is never created").doesNotExist();
        assertThat(Files.readSymbolicLink(projectFile)).as("the link is as it was").isEqualTo(SHARED_SONG);

        Files.move(offline, song);

        saved(manager, project);

        assertThat(schemaOneBackupsIn(projectFile.getParent())).as("the backup stayed owed: this save took it")
                .singleElement().satisfies(backup -> assertThat(Files.readAllBytes(backup))
                        .as("the schema 1 document").isEqualTo(original));
        assertThat(Files.readSymbolicLink(projectFile)).as("the link stays").isEqualTo(SHARED_SONG);
        assertThat(Files.readString(song)).as("the save's capture").contains("<daw-project version=\"2\"");
    }

    @Test
    void closingALegacyProjectWritesNothingThroughTheLink() throws IOException {
        manager.closeProject().join();
        Files.createSymbolicLink(projectFile, SHARED_SONG);
        manager.openProject(projectFile.getParent());
        assertThat(manager.getCurrentDawProject()).as("fixture: a metadata-only project").isNull();
        byte[] before = Files.readAllBytes(song);
        mover.clear();

        manager.closeProject().join();

        assertThat(Files.isSymbolicLink(projectFile)).as("the link stays").isTrue();
        assertThat(Files.readSymbolicLink(projectFile)).isEqualTo(SHARED_SONG);
        assertThat(mover.publishes()).as("a close is no save (story 333, ruling R9): nothing was published")
                .isEmpty();
        assertThat(Files.readAllBytes(song)).as("Song.daw is as it was").isEqualTo(before);
        assertThat(tempsIn(projectFile.getParent())).isEmpty();
        assertThat(tempsIn(shared)).isEmpty();
    }

    /** The schema 1 migration backups in {@code directory}: {@code project.daw.v1.<stamp>.bak}. */
    private static List<Path> schemaOneBackupsIn(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(entry -> {
                String name = entry.getFileName().toString();
                return name.startsWith("project.daw.v1.") && name.endsWith(".bak");
            }).toList();
        }
    }

    private static DawProject project() {
        DawProject project = new DawProject("Song", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Vox");
        project.markDirty();
        return project;
    }

    /**
     * Makes {@code file} read-only, or writable again: its POSIX permission
     * bits where it has them, else the DOS read-only flag (Windows, where
     * {@code @TempDir} cannot delete a read-only file).
     */
    private static void makeReadOnly(Path file, boolean readOnly) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString(readOnly ? "r--r--r--" : "rw-r--r--"));
            return;
        }
        DosFileAttributeView dos = Files.getFileAttributeView(file, DosFileAttributeView.class);
        assumeTrue(dos != null, "the file system can make a file read-only");
        dos.setReadOnly(readOnly);
    }
}
