package com.benesquivelmusic.daw.core.persistence.archive;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AtomicFileWriter;
import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.persistence.SaveTestSupport;
import com.benesquivelmusic.daw.core.project.DawProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ProjectArchiver#write} publishes the {@code .dawz} through the
 * {@link AtomicFileWriter} (story 331, "Crash-shaped atomicity test", the
 * archive): a write that fails in the crash window — the temp written and
 * forced, the move not yet made — or whose atomic move and
 * {@code REPLACE_EXISTING} fallback both fail leaves no archive and no temp,
 * and an archive already at that path byte for byte; the exception that ended
 * the write reaches the caller as thrown. The two-move failure over an
 * existing archive is {@code ProjectArchiverAssetWriteFailureTest}'s. Both
 * failures are injected at the writer's mover seam. The next archive to the
 * same file deletes the temps archives of it that were cut off left, and no
 * other file (ruling M2); a {@code .dawz} path that is a symbolic link
 * becomes the archive itself (ruling M3; skipped where the account may not
 * create links).
 */
class ArchiveWriteAtomicityTest {

    @TempDir
    Path tmp;

    @Test
    void aWriteThatFailsBetweenTheForcedTempAndTheMoveLeavesNoArchiveAndNoTemp() throws IOException {
        IOException crash = new IOException("injected failure between the forced temp and the move");
        ProjectArchiver archiver = archiver((temp, target, options) -> {
            throw crash;
        });
        Path archiveDir = Files.createDirectories(tmp.resolve("out"));
        Path archive = archiveDir.resolve("song.dawz");
        ArchiveCapture capture = archiver.capture(project(), ArchiveOptions.defaults(), List.of());

        assertThatThrownBy(() -> archiver.write(capture, archive)).isSameAs(crash);

        assertThat(archive).doesNotExist();
        assertThat(entriesOf(archiveDir)).as("no temp beside it").isEmpty();
    }

    @Test
    void aWriteWhoseAtomicMoveAndFallbackBothFailLeavesNoArchiveAndNoTemp() throws IOException {
        IOException fallbackFailure = new IOException("injected failure of the plain-replace fallback");
        ProjectArchiver archiver = archiver((temp, target, options) -> {
            if (List.of(options).contains(StandardCopyOption.ATOMIC_MOVE)) {
                throw new AtomicMoveNotSupportedException(temp.toString(), target.toString(), "no atomic move");
            }
            throw fallbackFailure;
        });
        Path archiveDir = Files.createDirectories(tmp.resolve("out"));
        Path archive = archiveDir.resolve("song.dawz");
        ArchiveCapture capture = archiver.capture(project(), ArchiveOptions.defaults(), List.of());

        assertThatThrownBy(() -> archiver.write(capture, archive)).isSameAs(fallbackFailure);

        assertThat(fallbackFailure.getSuppressed()).singleElement()
                .isInstanceOf(AtomicMoveNotSupportedException.class);
        assertThat(archive).doesNotExist();
        assertThat(entriesOf(archiveDir)).as("no temp beside it").isEmpty();
    }

    @Test
    void aWriteThatFailsInTheCrashWindowLeavesAnExistingArchiveByteForByte() throws IOException {
        Path archiveDir = Files.createDirectories(tmp.resolve("out"));
        Path archive = archiveDir.resolve("song.dawz");
        DawProject project = project();
        new ProjectArchiver().write(new ProjectArchiver().capture(project, ArchiveOptions.defaults(), List.of()),
                archive);
        byte[] previous = Files.readAllBytes(archive);
        IOException crash = new IOException("injected failure between the forced temp and the move");
        ProjectArchiver archiver = archiver((temp, target, options) -> {
            throw crash;
        });
        project.createAudioTrack("Added after the first archive");
        ArchiveCapture capture = archiver.capture(project, ArchiveOptions.defaults(), List.of());

        assertThatThrownBy(() -> archiver.write(capture, archive)).isSameAs(crash);

        assertThat(Files.readAllBytes(archive)).as("the previous archive, byte for byte").isEqualTo(previous);
        assertThat(entriesOf(archiveDir)).as("no temp beside it").containsExactly(archive);
    }

    @Test
    void theNextArchiveToTheSameFileDeletesTheTempsCutOffArchivesOfItLeftAndNoOtherFile() throws IOException {
        Path archiveDir = Files.createDirectories(tmp.resolve("out"));
        Path archive = archiveDir.resolve("song.dawz");
        List<Path> cutOff = List.of(
                Files.write(archiveDir.resolve(".song.dawz.1141de43e9050945.tmp"), new byte[4096]),
                Files.write(archiveDir.resolve(".song.dawz.f0ea7b9968197265.tmp"), new byte[4096]));
        List<Path> kept = List.of(
                Files.write(archiveDir.resolve(".other.dawz.1141de43e9050945.tmp"), new byte[4096]),
                Files.write(archiveDir.resolve(".song.dawz.1141de43e905094.tmp"), new byte[4096]),
                Files.createDirectory(archiveDir.resolve(".song.dawz.0000000000000000.tmp")));
        ProjectArchiver archiver = new ProjectArchiver();

        archiver.write(archiver.capture(project(), ArchiveOptions.defaults(), List.of()), archive);

        assertThat(cutOff).as("the temps of song.dawz").allSatisfy(temp -> assertThat(temp).doesNotExist());
        List<Path> left = new ArrayList<>(kept);
        left.add(archive);
        assertThat(entriesOf(archiveDir))
                .as("another archive's temp, a 15-digit near miss and a directory with a temp's name stay")
                .containsExactlyInAnyOrderElementsOf(left);
        assertThat(entryNamesOf(archive)).as("a whole archive")
                .contains(ArchiveHeader.FILE_NAME, ArchiveHeader.PROJECT_DOC_NAME);
    }

    @Test
    void aDawzThatIsASymbolicLinkIsReplacedByTheArchiveItself() throws IOException {
        SaveTestSupport.assumeSymbolicLinks(tmp);
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));
        Path old = Files.writeString(elsewhere.resolve("old.dawz"), "an archive somewhere else");
        Path archiveDir = Files.createDirectories(tmp.resolve("out"));
        Path archive = Files.createSymbolicLink(archiveDir.resolve("song.dawz"), Path.of("..", "elsewhere",
                "old.dawz"));
        ProjectArchiver archiver = new ProjectArchiver();

        archiver.write(archiver.capture(project(), ArchiveOptions.defaults(), List.of()), archive);

        assertThat(Files.isSymbolicLink(archive)).as("the archive lands at the path the user chose").isFalse();
        assertThat(Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(entryNamesOf(archive)).as("a whole archive")
                .contains(ArchiveHeader.FILE_NAME, ArchiveHeader.PROJECT_DOC_NAME);
        assertThat(old).as("the file the link led to is not written").hasContent("an archive somewhere else");
        assertThat(entriesOf(archiveDir)).as("no temp beside it").containsExactly(archive);
    }

    private DawProject project() throws IOException {
        Path media = Files.createDirectories(tmp.resolve("media"));
        Path loop = media.resolve("loop.wav");
        if (!Files.exists(loop)) {
            Files.write(loop, new byte[]{7, 7, 7});
        }
        DawProject project = new DawProject("Song", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Loops").addClip(new AudioClip("Loop", 0, 4, loop.toString()));
        return project;
    }

    private static ProjectArchiver archiver(AtomicFileWriter.Mover mover) {
        return new ProjectArchiver(new ProjectSerializer(), new ProjectDeserializer(), "test",
                ProjectArchiver.AssetWriteHook.NONE, ProjectArchiver.AssetPlanHook.NONE,
                new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, mover));
    }

    private static List<Path> entriesOf(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.toList();
        }
    }

    /** The names of the ZIP entries {@code archive} holds, in order. */
    private static List<String> entryNamesOf(Path archive) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                names.add(entry.getName());
            }
        }
        return names;
    }
}
