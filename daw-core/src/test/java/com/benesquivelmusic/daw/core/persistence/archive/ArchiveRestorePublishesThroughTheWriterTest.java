package com.benesquivelmusic.daw.core.persistence.archive;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AtomicFileWriter;
import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.project.DawProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.benesquivelmusic.daw.core.persistence.archive.ArchiveTestSupport.archive;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An archive restore into a new folder publishes its staging directory
 * through the writer's one directory publish
 * ({@link AtomicFileWriter#publishDirectory}, story 333, ENG-S11): the move
 * that publishes the folder is the writer's mover's, from
 * {@code .<name>.restoring.tmp}, one rename with no copy option — and while that
 * move is held, the folder the user named does not exist, so it never holds
 * half a project.
 */
class ArchiveRestorePublishesThroughTheWriterTest {

    private static final long TIMEOUT_SECONDS = 10;

    @TempDir
    Path tmp;

    @Test
    void theRestoredFolderIsPublishedByTheWritersMoveAndNeverSeenHalfExtracted() throws Exception {
        Path wav = Files.write(Files.createDirectories(tmp.resolve("media")).resolve("clip.wav"),
                new byte[] {1, 2, 3, 4});
        DawProject project = new DawProject("Restore Me", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Audio").addClip(new AudioClip("c", 0, 1.0, wav.toString()));
        Path archiveFile = tmp.resolve("session.dawz");
        archive(new ProjectArchiver(), project, archiveFile);

        Path target = tmp.resolve("Restored");
        Path staging = AtomicFileWriter.stagingSiblingOf(target, "restoring");
        List<List<Object>> moves = new CopyOnWriteArrayList<>();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicFileWriter.Mover mover = (source, destination, options) -> {
            moves.add(List.of(source, destination, List.of(options)));
            if (destination.equals(target)) {
                held.countDown();
                try {
                    if (!release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IOException("the test never released the publish");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while held", interrupted);
                }
            }
            AtomicFileWriter.FILE_SYSTEM_MOVER.move(source, destination, options);
        };
        ProjectArchiver archiver = new ProjectArchiver(new ProjectSerializer(), new ProjectDeserializer(), "test",
                ProjectArchiver.AssetWriteHook.NONE, ProjectArchiver.AssetPlanHook.NONE,
                new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, mover));

        CompletableFuture<ArchivedProject> restore = CompletableFuture.supplyAsync(() -> {
            try {
                return archiver.openArchive(archiveFile, target, MissingAssetResolver.none());
            } catch (IOException failed) {
                throw new java.io.UncheckedIOException(failed);
            }
        });
        assertThat(held.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("the publish was reached").isTrue();
        try {
            assertThat(target).as("while the publish is held, the named folder does not exist").doesNotExist();
            assertThat(staging.resolve(ArchiveHeader.PROJECT_DOC_NAME))
                    .as("everything is extracted into the staging sibling first").isRegularFile();
        } finally {
            release.countDown();
        }
        ArchivedProject restored = restore.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(restored.extractedDir()).isEqualTo(target);
        assertThat(target.resolve(ArchiveHeader.PROJECT_DOC_NAME)).isRegularFile();
        assertThat(staging).doesNotExist();
        assertThat(moves).as("one move publishes the folder: the writer's, one rename from the staging sibling")
                .containsExactly(List.<Object>of(staging, target, List.<CopyOption>of()));
    }
}
