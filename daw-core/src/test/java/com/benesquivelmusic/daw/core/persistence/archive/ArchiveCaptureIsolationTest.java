package com.benesquivelmusic.daw.core.persistence.archive;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.midi.SoundFontAssignment;
import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An archive packages its capture, never the live project (story 331,
 * "Archive isolation test"; Persistence Integrity book §2.3, §4.2): the
 * capture is taken on the thread that owns the project and only it is
 * handed to the worker, so an edit made after it — while the write runs,
 * held at its first asset by the {@link ProjectArchiver.AssetWriteHook} seam
 * — changes nothing in the archived document, and no live clip is ever
 * given an archive's {@code assets/…} reference. The options apply when the
 * capture is taken: a SoundFont they leave out is neither packaged nor
 * substituted, though a clip naming the same file is.
 */
class ArchiveCaptureIsolationTest {

    private static final long HOLD_TIMEOUT_SECONDS = 30;

    @TempDir
    Path tmp;

    @Test
    void anEditMadeAfterTheCaptureWhileTheWriteRunsNeverReachesTheArchive() throws Exception {
        Path media = Files.createDirectories(tmp.resolve("media"));
        Path loop = Files.write(media.resolve("loop.wav"), new byte[]{7, 7, 7});
        Path other = Files.write(media.resolve("other.wav"), new byte[]{8, 8});
        DawProject project = new DawProject("Isolated", AudioFormat.CD_QUALITY);
        AudioClip clip = new AudioClip("Loop", 0, 4, loop.toString());
        project.createAudioTrack("Loops").addClip(clip);
        project.getTransport().setTempo(120.0);

        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> writingThread = new AtomicReference<>();
        ProjectArchiver archiver = new ProjectArchiver(new ProjectSerializer(), new ProjectDeserializer(), "test",
                entryName -> {
                    if (writingThread.compareAndSet(null, Thread.currentThread())) {
                        reached.countDown();
                        awaitRelease(release);
                    }
                });
        ArchiveCapture capture = archiver.capture(project, ArchiveOptions.defaults(), List.of());
        ArchiveCapture unedited = archiver.capture(project, ArchiveOptions.defaults(), List.of());
        Path archive = tmp.resolve("out").resolve("isolated.dawz");

        ProjectArchiveSummary summary;
        try (ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ProjectArchiveSummary> writing = worker.submit(() -> archiver.write(capture, archive));
            try {
                assertThat(reached.await(HOLD_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .as("the write reached its first asset").isTrue();
                // Edits on the owning thread while the archive is written.
                clip.setSourceFilePath(other.toString());
                project.createAudioTrack("Added after the capture");
                project.getTransport().setTempo(90.0);
                assertNoLiveClipHoldsAnArchiveReference(project);
            } finally {
                release.countDown();
            }
            summary = writing.get(HOLD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(writingThread.get()).as("the write ran on the worker").isNotSameAs(Thread.currentThread());
        assertThat(summary.uniqueAssetCount()).as("the captured file was packed").isEqualTo(1);
        assertThat(entriesOf(archive)).anyMatch(name -> name.endsWith("_loop.wav"))
                .noneMatch(name -> name.endsWith("_other.wav"));
        String document = projectDocumentOf(archive);
        assertThat(document)
                .doesNotContain("Added after the capture")
                .doesNotContain(other.toString())
                .doesNotContain("other.wav")
                .contains("tempo=\"120.0\"");
        Path reference = tmp.resolve("out").resolve("unedited.dawz");
        archiver.write(unedited, reference);
        assertThat(document).as("byte for byte the document of a capture taken before the edits, written after them")
                .isEqualTo(projectDocumentOf(reference));
        assertNoLiveClipHoldsAnArchiveReference(project);
        assertThat(clip.getSourceFilePath()).as("the edit stands in the live project").isEqualTo(other.toString());
    }

    @Test
    void aSoundFontTheOptionsLeaveOutIsNeitherPackagedNorSubstitutedThoughAClipNamingTheFileIs() throws Exception {
        Path media = Files.createDirectories(tmp.resolve("media"));
        Path shared = Files.write(media.resolve("shared.sf2"), new byte[]{5, 5, 5});
        DawProject project = new DawProject("Options", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Audio").addClip(new AudioClip("Clip", 0, 4, shared.toString()));
        Track keys = project.createMidiTrack("Keys");
        keys.setSoundFontAssignment(new SoundFontAssignment(shared, 0, 0, "Piano"));
        ProjectArchiver archiver = new ProjectArchiver();

        ArchiveCapture capture = archiver.capture(project, new ArchiveOptions(true, false, false), List.of());
        Path archive = tmp.resolve("no-soundfonts.dawz");
        ProjectArchiveSummary summary = archiver.write(capture, archive);

        assertThat(summary.uniqueAssetCount()).as("the clip's file").isEqualTo(1);
        String document = projectDocumentOf(archive);
        assertThat(document)
                .as("the clip points at the packaged asset")
                .contains("source-file=\"" + ArchiveHeader.ASSETS_DIR + "/");
        assertThat(document)
                .as("the SoundFont keeps its own path")
                .contains("path=\"" + shared.toString().replace('\\', '/') + "\"");
    }

    private static void awaitRelease(CountDownLatch release) throws IOException {
        try {
            if (!release.await(HOLD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("the test never released the held archive");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while the archive was held");
        }
    }

    private static void assertNoLiveClipHoldsAnArchiveReference(DawProject project) {
        for (Track track : project.getTracks()) {
            for (AudioClip clip : track.getClips()) {
                assertThat(clip.getSourceFilePath()).as("live clip %s", clip.getName())
                        .doesNotStartWith(ArchiveHeader.ASSETS_DIR + "/");
                assertThat(clip.getSourceSegmentPaths())
                        .noneMatch(path -> path.startsWith(ArchiveHeader.ASSETS_DIR + "/"));
            }
        }
    }

    private static String projectDocumentOf(Path archive) throws IOException {
        try (InputStream in = Files.newInputStream(archive);
             ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals(ArchiveHeader.PROJECT_DOC_NAME)) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("no " + ArchiveHeader.PROJECT_DOC_NAME + " in " + archive);
    }

    private static List<String> entriesOf(Path archive) throws IOException {
        List<String> names = new ArrayList<>();
        try (InputStream in = Files.newInputStream(archive);
             ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                names.add(entry.getName());
            }
        }
        return names;
    }
}
