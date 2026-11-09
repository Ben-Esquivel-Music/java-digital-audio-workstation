package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.comping.CompRegion;
import com.benesquivelmusic.daw.core.comping.CompileCompAction;
import com.benesquivelmusic.daw.core.comping.SetCompRegionAction;
import com.benesquivelmusic.daw.core.comping.TakeComping;
import com.benesquivelmusic.daw.core.comping.TakeLane;
import com.benesquivelmusic.daw.core.persistence.archive.ArchiveOptions;
import com.benesquivelmusic.daw.core.persistence.archive.ArchivedProject;
import com.benesquivelmusic.daw.core.persistence.archive.ProjectArchiveSummary;
import com.benesquivelmusic.daw.core.persistence.archive.ProjectArchiver;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.SegmentFile;
import com.benesquivelmusic.daw.core.track.Track;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static com.benesquivelmusic.daw.core.persistence.AudioFileFixtures.segment;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.saved;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every take lane's files are project content once the project saves the
 * lanes (story 334, studio ruling E16): an archive packages every lane's
 * lap; a lap file only an unselected lane holds that is missing at open is
 * one missing asset and silences no clip; the clip rehydrator gives lane
 * clips their audio — the audible comp first (studio ruling G10) — so the
 * first swipe after a reopen compiles audible slices, sample-equal to the
 * lap over the region; a swipe made before the lap decoded gets the audio
 * when it attaches; and a relink reaches lane clips, so a swipe after it
 * compiles from the relinked file (studio ruling G15).
 *
 * <p>Laps are real take segments ({@link AudioFileFixtures#segment}), one
 * per lane with lane-specific content and a distinct signal on each of
 * their two channels, decoded at their native rate as a recorded take is.</p>
 */
class TakeLaneAssetsTest {

    private static final int RATE = 48_000;
    private static final AudioFormat FORMAT = new AudioFormat(RATE, 2, 16, 512);
    /** One second of audio per lap: two beats at the default 120 BPM. */
    private static final int LAP_FRAMES = RATE;
    private static final double LAP_BEATS = 2.0;

    @TempDir
    Path tempDir;

    private ProjectManager manager;

    @BeforeEach
    void createManager() {
        manager = new ProjectManager(new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true)));
    }

    @AfterEach
    void closeTheProject() {
        manager.closeProject().join();
    }

    @Test
    void anArchiveOfAThreeLaneTrackPackagesThreeLapFiles() throws IOException {
        Stack stack = stack(tempDir.resolve("takes"), 3);
        ProjectArchiver archiver = new ProjectArchiver();
        Path archive = tempDir.resolve("song.dawz");

        ProjectArchiveSummary summary = archiver.write(
                archiver.capture(stack.project(), ArchiveOptions.defaults(), List.of()), archive);

        assertThat(summary.uniqueAssetCount()).as("one lap file per lane, the selected one included once")
                .isEqualTo(3);
        ArchivedProject opened = archiver.openArchive(archive, tempDir.resolve("restored"), null);
        assertThat(opened.missingAssets()).isEmpty();
        List<TakeLane> lanes = opened.project().getTracks().getFirst().getTakeComping().getTakeLanes();
        assertThat(lanes).hasSize(3);
        Path extracted = opened.extractedDir().toAbsolutePath().normalize();
        for (int i = 0; i < 3; i++) {
            String packaged = lanes.get(i).getClips().getFirst().getSourceSegmentPaths().getFirst();
            assertThat(Path.of(packaged).normalize()).as("lane %d points into the archive", i).startsWith(extracted);
            assertThat(Files.readAllBytes(Path.of(packaged))).as("lane %d holds its own lap", i)
                    .isEqualTo(Files.readAllBytes(stack.lapFiles().get(i)));
        }
    }

    @Test
    void aMissingUnselectedLapIsOneMissingAssetAndNoSilentClip() throws IOException {
        Path directory = manager.createProject("Song", tempDir).projectPath();
        Stack stack = stack(ProjectManager.audioDirectory(directory).resolve("takes"), 2);
        saved(manager, stack.project());
        manager.closeProject().join();
        Files.delete(stack.lapFiles().get(1));

        manager.openProject(directory);

        MissingAssetsReport report = manager.getLastMissingAssets();
        assertThat(report.missingAssetCount()).as("the unselected lane's lap is missing").isEqualTo(1);
        assertThat(report.silentClipCount()).as("a take lane does not play: no clip is silent").isZero();
        assertThat(report.assets()).singleElement().satisfies(row -> {
            assertThat(row.reference()).isEqualTo(new AssetReference.Take(
                    List.of(stack.lapFiles().get(1).toString())));
            assertThat(row.holders()).extracting(MissingAsset.Holder::clipName).containsExactly("Take — Vox");
        });
    }

    @Test
    void laneClipsHoldAudioAfterReopenSoTheFirstSwipeIsNotSilent() throws Exception {
        Path directory = manager.createProject("Song", tempDir).projectPath();
        Stack stack = stack(ProjectManager.audioDirectory(directory).resolve("takes"), 2);
        saved(manager, stack.project());
        manager.closeProject().join();
        manager.openProject(directory);
        DawProject project = manager.getCurrentDawProject();
        Track vox = project.getTracks().getFirst();
        TakeComping comping = vox.getTakeComping();

        rehydrate(project, new ClipRehydrator());

        for (int i = 0; i < 2; i++) {
            AudioClip lap = comping.getTakeLane(i).getClips().getFirst();
            assertThat(lap.getAudioData()).as("lane %d's lap holds the lap file's audio", i)
                    .isDeepEqualTo(SegmentFile.readFrames(List.of(stack.lapFiles().get(i))));
        }
        AudioClip unselectedLap = comping.getTakeLane(1).getClips().getFirst();
        List<AudioClip> adopted = comping.compOutputOn(vox);
        assertThat(adopted).as("the saved comp was adopted").hasSize(1);

        new SetCompRegionAction(vox, new CompRegion(1, 0.5, 1.0)).execute();

        List<AudioClip> comp = comping.compOutputOn(vox);
        assertThat(vox.getClips()).as("the swipe replaced exactly the adopted output").doesNotContainAnyElementsOf(adopted);
        assertNoBeatPlaysTwice(vox);
        AudioClip swiped = comp.stream().filter(clip -> clip.getStartBeat() == 0.5).findFirst().orElseThrow();
        assertSlicePlaysTheLap(swiped, unselectedLap, 0.5);
    }

    @Test
    void aSwipeBeforeTheLanesSourceDecodedGetsItsAudioWhenTheSourceAttaches() throws Exception {
        Path directory = manager.createProject("Song", tempDir).projectPath();
        Stack stack = stack(ProjectManager.audioDirectory(directory).resolve("takes"), 2);
        saved(manager, stack.project());
        manager.closeProject().join();
        manager.openProject(directory);
        DawProject project = manager.getCurrentDawProject();
        Track vox = project.getTracks().getFirst();
        TakeComping comping = vox.getTakeComping();
        CountDownLatch release = new CountDownLatch(1);
        RecordingSourceDecoder held = new RecordingSourceDecoder().holdUntil(release);
        CollectingRehydrationListener listener = new CollectingRehydrationListener();
        RehydrationSession session = new ClipRehydrator(held, 1, Long.MAX_VALUE)
                .start(project, manager.getLastMissingAssets(), 0.0, listener);
        held.awaitFirstDecode();

        new SetCompRegionAction(vox, new CompRegion(1, 0.5, 1.0)).execute();
        AudioClip swiped = comping.compOutputOn(vox).stream()
                .filter(clip -> clip.getStartBeat() == 0.5).findFirst().orElseThrow();
        assertThat(swiped.getAudioData()).as("the lap has not decoded yet").isNull();

        release.countDown();
        listener.awaitFinished();
        listener.applyAll(session);

        AudioClip unselectedLap = comping.getTakeLane(1).getClips().getFirst();
        assertThat(unselectedLap.getAudioData())
                .isDeepEqualTo(SegmentFile.readFrames(List.of(stack.lapFiles().get(1))));
        assertSlicePlaysTheLap(swiped, unselectedLap, 0.5);
    }

    @Test
    void aRelinkReachesLaneClips() throws Exception {
        Path directory = manager.createProject("Song", tempDir).projectPath();
        Stack stack = stack(ProjectManager.audioDirectory(directory).resolve("takes"), 2);
        saved(manager, stack.project());
        manager.closeProject().join();
        Path moved = Files.createDirectories(tempDir.resolve("found"));
        Path relocated = Files.move(stack.lapFiles().get(1), moved.resolve(stack.lapFiles().get(1).getFileName()));
        manager.openProject(directory);
        DawProject project = manager.getCurrentDawProject();
        Track vox = project.getTracks().getFirst();
        TakeComping comping = vox.getTakeComping();
        rehydrate(project, new ClipRehydrator());
        AudioClip unselectedLap = comping.getTakeLane(1).getClips().getFirst();
        assertThat(unselectedLap.getAudioData()).as("fixture: the lap is missing").isNull();
        MissingAsset row = manager.getLastMissingAssets().assets().getFirst();

        RelinkPreparation prepared = new AssetRelinker().prepare(AssetRelinker.request(project, row.reference()),
                relocated);
        assertThat(prepared).isInstanceOf(RelinkPreparation.Candidate.class);
        AssetRelinker.Applied applied = AssetRelinker.apply((RelinkPreparation.Candidate) prepared, id -> { });

        assertThat(applied.relinked()).as("the relink reaches the lane clip").containsExactly(unselectedLap);
        assertThat(unselectedLap.getSourceSegmentPaths()).containsExactly(relocated.toString());
        float[][] relinkedAudio = SegmentFile.readFrames(List.of(relocated));
        assertThat(unselectedLap.getAudioData()).isDeepEqualTo(relinkedAudio);

        new SetCompRegionAction(vox, new CompRegion(1, 0.5, 1.0)).execute();
        AudioClip swiped = comping.compOutputOn(vox).stream()
                .filter(clip -> clip.getStartBeat() == 0.5).findFirst().orElseThrow();
        assertThat(swiped.getSourceSegmentPaths()).containsExactly(relocated.toString());
        assertSlicePlaysTheLap(swiped, unselectedLap, 0.5);
        assertThat(swiped.getAudioData()).isDeepEqualTo(relinkedAudio);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /** A one-track project and the lap file of each of its lanes. */
    private record Stack(DawProject project, List<Path> lapFiles) {
    }

    /**
     * A project whose track "Vox" holds {@code lanes} take lanes, lane
     * {@code i} holding one lap over beats 0–2 recorded as one segment with
     * lane-specific content under {@code takesRoot/lap-<i+1>/}, and lane 0
     * selected whole and compiled onto the main lane.
     */
    private static Stack stack(Path takesRoot, int lanes) throws IOException {
        DawProject project = new DawProject("Song", FORMAT);
        Track vox = project.createAudioTrack("Vox");
        TakeComping comping = vox.getTakeComping();
        List<Path> lapFiles = new ArrayList<>();
        for (int i = 0; i < lanes; i++) {
            Path lapFile = segment(takesRoot.resolve("lap-" + (i + 1)), i, RATE, LAP_FRAMES);
            lapFiles.add(lapFile);
            AudioClip lap = new AudioClip("Take — Vox", 0.0, LAP_BEATS, null);
            lap.setSourceSegmentPaths(List.of(lapFile.toString()));
            TakeLane lane = new TakeLane("Take " + (i + 1));
            lane.addClip(lap);
            comping.addTakeLane(lane);
        }
        comping.addCompRegion(new CompRegion(0, 0.0, LAP_BEATS));
        new CompileCompAction(vox).execute();
        return new Stack(project, List.copyOf(lapFiles));
    }

    /** Runs a rehydration of {@code project} to its end and applies every outcome, as the owner thread does. */
    private void rehydrate(DawProject project, ClipRehydrator rehydrator) throws InterruptedException {
        CollectingRehydrationListener listener = new CollectingRehydrationListener();
        RehydrationSession session = rehydrator.start(project, manager.getLastMissingAssets(), 0.0, listener);
        listener.awaitFinished();
        listener.applyAll(session);
    }

    /**
     * {@code slice} plays {@code lap} from {@code startBeat}: it holds the
     * lap's own audio, not a copy (one decode per source), and starts that
     * many beats into it — so its samples over the region are the lap's at
     * the region's offset, and it is not silent.
     */
    private static void assertSlicePlaysTheLap(AudioClip slice, AudioClip lap, double startBeat) {
        assertThat(slice.getAudioData()).as("the slice shares the lap's decode").isSameAs(lap.getAudioData());
        assertThat(slice.getSourceSegmentPaths()).isEqualTo(lap.getSourceSegmentPaths());
        assertThat(slice.getSourceOffsetBeats()).as("the slice starts at the region's offset into the lap")
                .isEqualTo(lap.getSourceOffsetBeats() + (startBeat - lap.getStartBeat()));
        float peak = 0f;
        for (float[] channel : slice.getAudioData()) {
            for (float sample : channel) {
                peak = Math.max(peak, Math.abs(sample));
            }
        }
        assertThat(peak).as("the slice is not silent").isGreaterThan(0f);
    }

    /** No two clips of the track's main lane overlap: no beat range plays twice. */
    private static void assertNoBeatPlaysTwice(Track track) {
        List<AudioClip> clips = track.getClips();
        for (int a = 0; a < clips.size(); a++) {
            for (int b = a + 1; b < clips.size(); b++) {
                AudioClip first = clips.get(a);
                AudioClip second = clips.get(b);
                assertThat(first.getStartBeat() < second.getEndBeat() && second.getStartBeat() < first.getEndBeat())
                        .as("%s [%s, %s) and %s [%s, %s) overlap", first.getName(), first.getStartBeat(),
                                first.getEndBeat(), second.getName(), second.getStartBeat(), second.getEndBeat())
                        .isFalse();
            }
        }
    }
}
