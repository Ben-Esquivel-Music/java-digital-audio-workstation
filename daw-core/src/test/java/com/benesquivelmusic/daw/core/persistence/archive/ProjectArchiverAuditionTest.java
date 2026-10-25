package com.benesquivelmusic.daw.core.persistence.archive;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.comping.CompRegion;
import com.benesquivelmusic.daw.core.comping.CompileCompAction;
import com.benesquivelmusic.daw.core.comping.TakeComping;
import com.benesquivelmusic.daw.core.comping.TakeLane;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.benesquivelmusic.daw.core.persistence.archive.ArchiveTestSupport.archive;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An archive taken while a take lane is auditioned packages the take files
 * the SAVED comp references — the selection, which may be drawn from a lane
 * other than the soloed one — and points the archived document at them
 * (PR #984 review), without ever rewriting a live clip: the archive is
 * captured on the thread that owns the project and written off it while the
 * session stays editable (story 331), so a recompile (the main-lane click
 * that ends the audition, an Alt-click) can run while it is being written,
 * and must never copy an archive-relative {@code assets/…} path into the
 * live comp or compare against one.
 */
class ProjectArchiverAuditionTest {

    private static final long HOLD_TIMEOUT_SECONDS = 30;

    @TempDir
    Path tmp;

    @Test
    void anArchiveDuringAnAuditionPackagesTheSelectionsTakeFilesNotTheSoloedLanes() throws IOException {
        Fixture f = new Fixture(tmp);
        f.audition();
        List<AudioClip> liveClips = List.copyOf(f.vox.getClips());

        ProjectArchiver archiver = new ProjectArchiver();
        Path archive = tmp.resolve("audition.dawz");
        ProjectArchiveSummary summary = archive(archiver, f.project, archive);

        // The selection's two segment files are packaged; the soloed lane's is not.
        assertThat(summary.uniqueAssetCount()).isEqualTo(2);
        assertSelectionPackagedOnly(archive);

        // The live session's references and clips are untouched.
        assertThat(f.selectedTake.getSourceSegmentPaths())
                .containsExactly(f.selected0.toString(), f.selected1.toString());
        assertThat(f.vox.getClips()).containsExactlyElementsOf(liveClips);
        assertThat(f.comping.getTakeLane(1).isSoloed()).isTrue();

        assertArchivedCompIsTheSelection(f, archiver, archive);
    }

    @Test
    void endingTheAuditionWhileAnArchiveIsWrittenRecompilesFromTheTakesOwnPaths() throws Exception {
        Fixture f = new Fixture(tmp);
        f.audition();
        f.project.markClean();
        HoldAtFirstAsset hold = new HoldAtFirstAsset();
        ProjectArchiver archiver = new ProjectArchiver(
                new ProjectSerializer(), new ProjectDeserializer(), "test", hold);
        Path archive = tmp.resolve("held.dawz");

        ProjectArchiveSummary summary;
        // The capture on the thread that owns the project; only the capture reaches the worker.
        ArchiveCapture capture = archiver.capture(f.project, ArchiveOptions.defaults(), List.of());
        try (ExecutorService archiveWorker = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ProjectArchiveSummary> archiving =
                    archiveWorker.submit(() -> archiver.write(capture, archive));
            try {
                assertThat(hold.reached.await(HOLD_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .as("the archive reached its asset write").isTrue();
                assertNoLiveClipHoldsAnArchivePath(f);

                // The main-lane click while the archive is held (CompToolHandler.clickMainLane).
                assertThat(f.comping.endAuditions()).isTrue();
                new CompileCompAction(f.vox).execute();

                List<AudioClip> comp = f.comping.compOutputOn(f.vox);
                assertThat(comp).as("the selection compile is back on the main lane").hasSize(1);
                assertThat(comp.getFirst().getSourceSegmentPaths())
                        .as("sliced from the take's own segment files, not the archive's")
                        .containsExactly(f.selected0.toString(), f.selected1.toString());
                assertThat(comp.getFirst().getSourceFilePath()).isEqualTo(f.selected0.toString());
                assertNoLiveClipHoldsAnArchivePath(f);
            } finally {
                hold.release.countDown();
            }
            summary = archiving.get(HOLD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        assertNoLiveClipHoldsAnArchivePath(f);
        List<AudioClip> comp = f.comping.compOutputOn(f.vox);
        assertThat(comp).hasSize(1);
        assertThat(comp.getFirst().getSourceSegmentPaths())
                .containsExactly(f.selected0.toString(), f.selected1.toString());
        assertThat(f.project.isDirty()).as("neither the archive nor the audition's end is an edit").isFalse();

        // The archive still holds the selection, with archive-relative references.
        assertThat(summary.uniqueAssetCount()).isEqualTo(2);
        assertSelectionPackagedOnly(archive);
        String document = projectDocumentOf(archive);
        assertThat(document)
                .contains("assets/")
                .doesNotContain(f.selected0.toString())
                .doesNotContain(f.selected1.toString());
        assertArchivedCompIsTheSelection(f, archiver, archive);
    }

    @Test
    void anAltClickWhileAnArchiveIsWrittenSeesAnUneditedCompAsTheSelection() throws Exception {
        Fixture f = new Fixture(tmp);
        new CompileCompAction(f.vox).execute();          // the selection plays; no audition
        f.project.markClean();
        HoldAtFirstAsset hold = new HoldAtFirstAsset();
        ProjectArchiver archiver = new ProjectArchiver(
                new ProjectSerializer(), new ProjectDeserializer(), "test", hold);
        Path archive = tmp.resolve("held.dawz");

        ArchiveCapture capture = archiver.capture(f.project, ArchiveOptions.defaults(), List.of());
        try (ExecutorService archiveWorker = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ProjectArchiveSummary> archiving =
                    archiveWorker.submit(() -> archiver.write(capture, archive));
            try {
                assertThat(hold.reached.await(HOLD_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .as("the archive reached its asset write").isTrue();
                assertNoLiveClipHoldsAnArchivePath(f);

                // CompToolHandler.altClickLane's rule: the project is marked
                // dirty and the "Audition discarded edits" notice shown only
                // when an audition starts over a comp that no longer saves as
                // the selection. The comp is unedited, so neither may happen.
                boolean discardsCompEdits = !f.comping.isAuditioning()
                        && !f.comping.compOutputSavesAsSelection(f.vox);
                assertThat(discardsCompEdits)
                        .as("an unedited comp saves as the selection mid-archive too")
                        .isFalse();
                f.comping.getTakeLane(0).setSoloed(false);
                f.comping.getTakeLane(1).setSoloed(true);
                new CompileCompAction(f.vox).execute();
                assertNoLiveClipHoldsAnArchivePath(f);
            } finally {
                hold.release.countDown();
            }
            archiving.get(HOLD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        assertNoLiveClipHoldsAnArchivePath(f);
        assertThat(f.project.isDirty()).isFalse();
        assertSelectionPackagedOnly(archive);
    }

    private static void assertNoLiveClipHoldsAnArchivePath(Fixture f) {
        List<AudioClip> live = new ArrayList<>(f.vox.getClips());
        for (TakeLane lane : f.comping.getTakeLanes()) {
            live.addAll(lane.getClips());
        }
        assertThat(live).isNotEmpty();
        for (AudioClip clip : live) {
            assertThat(clip.getSourceSegmentPaths())
                    .as("segments of live clip %s", clip.getName())
                    .isNotEmpty()
                    .noneMatch(path -> path.startsWith(ArchiveHeader.ASSETS_DIR + "/"));
            assertThat(clip.getSourceFilePath())
                    .as("source file of live clip %s", clip.getName())
                    .doesNotStartWith(ArchiveHeader.ASSETS_DIR + "/");
        }
    }

    private static void assertSelectionPackagedOnly(Path archive) throws IOException {
        List<String> assetEntries = assetEntryNames(archive);
        assertThat(assetEntries).hasSize(2)
                .anyMatch(name -> name.endsWith("_take-1-segment-000.wav"))
                .anyMatch(name -> name.endsWith("_take-1-segment-001.wav"))
                .noneMatch(name -> name.endsWith("_take-2-segment-000.wav"));
    }

    /** The archived document's comp clip is the selection and points at the packaged files. */
    private void assertArchivedCompIsTheSelection(Fixture f, ProjectArchiver archiver, Path archive)
            throws IOException {
        Path extractDir = tmp.resolve("restored-" + archive.getFileName());
        ArchivedProject opened = archiver.openArchive(archive, extractDir, null);
        assertThat(opened.missingAssets()).isEmpty();
        List<AudioClip> restored = opened.project().getTracks().getFirst().getClips();
        assertThat(restored).hasSize(1);
        AudioClip slice = restored.getFirst();
        assertThat(slice.getStartBeat()).isEqualTo(2.0);
        assertThat(slice.getDurationBeats()).isEqualTo(3.0);
        List<String> segments = slice.getSourceSegmentPaths();
        assertThat(segments).hasSize(2);
        Path extractAbs = extractDir.toAbsolutePath().normalize();
        for (String segment : segments) {
            assertThat(Path.of(segment).normalize()).startsWith(extractAbs);
        }
        assertThat(Files.readAllBytes(Path.of(segments.get(0)))).containsExactly(Fixture.SELECTED_BYTES_0);
        assertThat(Files.readAllBytes(Path.of(segments.get(1)))).containsExactly(Fixture.SELECTED_BYTES_1);
    }

    private static List<String> assetEntryNames(Path archive) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            zip.stream()
                    .map(ZipEntry::getName)
                    .filter(name -> name.startsWith(ArchiveHeader.ASSETS_DIR + "/"))
                    .forEach(names::add);
        }
        return names;
    }

    private static String projectDocumentOf(Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile());
             InputStream in = zip.getInputStream(zip.getEntry(ArchiveHeader.PROJECT_DOC_NAME))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Holds the first asset write until the test releases it, so the test
     * can edit the live project while the archive is being written.
     */
    private static final class HoldAtFirstAsset implements ProjectArchiver.AssetWriteHook {
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean first = new AtomicBoolean(true);

        @Override
        public void beforeAssetWrite(String entryName) throws IOException {
            if (!first.compareAndSet(true, false)) {
                return;
            }
            reached.countDown();
            try {
                if (!release.await(HOLD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IOException("the test never released the held archive");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted while the archive was held");
            }
        }
    }

    /**
     * A track with two take lanes: lane 0 holds a two-segment take whose
     * beats 2-5 are the comp selection; lane 1 holds a one-segment take.
     */
    private static final class Fixture {
        static final byte[] SELECTED_BYTES_0 = {1, 1, 1};
        static final byte[] SELECTED_BYTES_1 = {2, 2, 2, 2};
        static final byte[] SOLOED_BYTES = {9, 9, 9, 9, 9};

        final Path selected0;
        final Path selected1;
        final Path soloed;
        final DawProject project;
        final Track vox;
        final TakeComping comping;
        final AudioClip selectedTake;
        final AudioClip soloedTake;

        Fixture(Path root) throws IOException {
            Path takes = Files.createDirectories(root.resolve("Song/audio/takes"));
            selected0 = Files.write(takes.resolve("take-1-segment-000.wav"), SELECTED_BYTES_0);
            selected1 = Files.write(takes.resolve("take-1-segment-001.wav"), SELECTED_BYTES_1);
            soloed = Files.write(takes.resolve("take-2-segment-000.wav"), SOLOED_BYTES);

            project = new DawProject("Audition", AudioFormat.CD_QUALITY);
            vox = project.createAudioTrack("Vox");
            comping = vox.getTakeComping();
            selectedTake = take("Take 1", selected0, selected1);
            soloedTake = take("Take 2", soloed);
            comping.addTakeLane(lane(selectedTake));
            comping.addTakeLane(lane(soloedTake));
            comping.addCompRegion(new CompRegion(0, 2.0, 3.0));
        }

        /** Compiles the selection, then Alt-clicks lane 1 (CompToolHandler.altClickLane). */
        void audition() {
            new CompileCompAction(vox).execute();
            comping.getTakeLane(1).setSoloed(true);
            new CompileCompAction(vox).execute();
        }

        private static AudioClip take(String name, Path... segments) {
            AudioClip clip = new AudioClip(name, 0.0, 8.0, null);
            List<String> paths = new ArrayList<>();
            for (Path segment : segments) {
                paths.add(segment.toString());
            }
            clip.setSourceSegmentPaths(paths);
            clip.setAudioData(new float[][] { new float[16] });
            return clip;
        }

        private static TakeLane lane(AudioClip take) {
            TakeLane lane = new TakeLane(take.getName());
            lane.addClip(take);
            return lane;
        }
    }
}
