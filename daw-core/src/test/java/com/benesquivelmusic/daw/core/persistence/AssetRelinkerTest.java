package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.GlueClipsAction;
import com.benesquivelmusic.daw.core.audioimport.AudioFileImporter;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.SegmentFile;
import com.benesquivelmusic.daw.core.recording.TakeDirectories;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.TempoChangeEvent;
import com.benesquivelmusic.daw.sdk.audio.SourceRateMetadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.saved;
import static com.benesquivelmusic.daw.core.persistence.AudioFileFixtures.admScene;
import static com.benesquivelmusic.daw.core.persistence.AudioFileFixtures.segment;
import static com.benesquivelmusic.daw.core.persistence.AudioFileFixtures.wav;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Relink (Persistence Integrity book §4.3; decision D17): preparing a located
 * file on a worker, applying it on the owner thread, and what the next save
 * writes.
 */
class AssetRelinkerTest {

    private static final int RATE = 48_000;
    private static final AudioFormat FORMAT = new AudioFormat(RATE, 2, 16, 512);

    @TempDir
    Path tempDir;

    @Test
    void aFileRelinkedToACopyUnderTheProjectDirectoryGetsItsAudioMarksTheProjectDirtyAndIsSavedRelative()
            throws Exception {
        Reopened reopened = reopenWithMovedImport("Inside");
        Path copy = Files.createDirectories(reopened.projectDir().resolve("audio").resolve("found"))
                .resolve("tone.wav");
        Files.copy(reopened.parked(), copy);
        AudioClip clip = onlyClip(reopened.project());
        AssetRelinker.Request request = AssetRelinker.request(reopened.project(), reopened.row().reference());

        RelinkPreparation prepared = new AssetRelinker().prepare(request, copy);

        assertThat(prepared).isInstanceOf(RelinkPreparation.Candidate.class);
        RelinkPreparation.Candidate candidate = (RelinkPreparation.Candidate) prepared;
        assertThat(candidate.asset()).isEqualTo(reopened.row().reference());
        assertThat(candidate.replacement()).isEqualTo(new AssetReference.SingleFile(copy.toString()));
        assertThat(candidate.shorterThanUsed()).isFalse();
        assertThat(candidate.needsConfirmation()).as("nothing to ask: it is applied at once").isFalse();
        assertThat(candidate.locatedName()).isEqualTo("tone.wav");
        assertThat(clip.getSourceFilePath()).as("preparing touches nothing")
                .isEqualTo(reopened.originalLocation().toString());
        assertThat(clip.getAudioData()).isNull();
        assertThat(reopened.project().isDirty()).isFalse();

        List<String> invalidated = new ArrayList<>();
        AssetRelinker.Applied applied = AssetRelinker.apply(candidate, invalidated::add);

        assertThat(applied.relinked()).containsExactly(clip);
        assertThat(applied.rows()).isEmpty();
        assertThat(invalidated).containsExactly(clip.getId());
        assertThat(clip.getSourceFilePath()).isEqualTo(copy.toString());
        assertThat(Arrays.deepEquals(clip.getAudioData(), reopened.importedAudio()))
                .as("the copy at the project rate, as its import held it")
                .isTrue();
        assertThat(clip.getSourceRateMetadata()).isNull();
        assertThat(reopened.project().isDirty()).as("a relink is an edit").isTrue();

        saved(reopened.manager(), reopened.manager().getCurrentDawProject());
        assertThat(Files.readString(reopened.projectDir().resolve("project.daw")))
                .contains("source-file=\"audio/found/tone.wav\"")
                .doesNotContain(copy.toString());
        reopened.manager().closeProject().join();
    }

    @Test
    void aFileRelinkedToACopyOutsideTheProjectDirectoryIsSavedWithItsAbsolutePathVerbatim() throws Exception {
        Reopened reopened = reopenWithMovedImport("Outside");
        Path copy = Files.createDirectories(tempDir.resolve("elsewhere")).resolve("tone.wav");
        Files.copy(reopened.parked(), copy);
        AssetRelinker.Request request = AssetRelinker.request(reopened.project(), reopened.row().reference());

        RelinkPreparation.Candidate candidate =
                (RelinkPreparation.Candidate) new AssetRelinker().prepare(request, copy);
        AssetRelinker.apply(candidate, id -> { });
        saved(reopened.manager(), reopened.manager().getCurrentDawProject());

        assertThat(onlyClip(reopened.project()).getAudioData()).isNotNull();
        assertThat(Files.readString(reopened.projectDir().resolve("project.daw")))
                .contains("source-file=\"" + copy + "\"");
        reopened.manager().closeProject().join();
    }

    @Test
    void aTakeIsRelinkedWholeFromAnyOneOfItsSegmentsInAMovedFolderAndIsSavedRelative() throws Exception {
        ProjectManager manager = newManager();
        Path projectDir = manager.createProject("Take", tempDir).projectPath();
        DawProject project = new DawProject("Take", FORMAT);
        Track track = project.createAudioTrack("Vocals");
        Path takeFolder = TakeDirectories.allocate(ProjectManager.audioDirectory(projectDir),
                Instant.parse("2026-10-20T10:00:00Z")).resolve(track.getId());
        List<String> recorded = List.of(segment(takeFolder, 0, 44_100.0, 512).toString(),
                segment(takeFolder, 1, 44_100.0, 512).toString());
        AudioClip take = new AudioClip("Take 1", 0.0, 0.04, null);
        take.setSourceSegmentPaths(recorded);
        track.addClip(take);
        saved(manager, project);
        manager.closeProject().join();
        Path moved = ProjectManager.audioDirectory(projectDir).resolve("moved-take");
        Files.move(takeFolder, moved);
        List<Path> movedSegments = List.of(moved.resolve("segment-000.wav"), moved.resolve("segment-001.wav"));
        float[][] onDisk = SegmentFile.readFrames(movedSegments);

        ProjectManager reopened = newManager();
        reopened.openProject(projectDir);
        DawProject loaded = reopened.getCurrentDawProject();
        AudioClip clip = onlyClip(loaded);
        MissingAsset row = reopened.getLastMissingAssets().assets().getFirst();
        assertThat(row.reference()).isInstanceOf(AssetReference.Take.class);
        AssetRelinker.Request request = AssetRelinker.request(loaded, row.reference());

        // The user picks the take's second segment: its folder is used for all of them.
        RelinkPreparation prepared = new AssetRelinker().prepare(request, movedSegments.get(1));

        assertThat(prepared).isInstanceOf(RelinkPreparation.Candidate.class);
        AssetRelinker.Applied applied = AssetRelinker.apply((RelinkPreparation.Candidate) prepared, id -> { });
        assertThat(applied.relinked()).containsExactly(clip);
        assertThat(clip.getSourceSegmentPaths())
                .containsExactly(movedSegments.get(0).toString(), movedSegments.get(1).toString());
        assertThat(clip.getSourceFilePath()).as("the head invariant").isEqualTo(movedSegments.getFirst().toString());
        assertThat(Arrays.deepEquals(clip.getAudioData(), onDisk)).as("both segments at the native rate").isTrue();
        assertThat(clip.getSourceRateMetadata()).isEqualTo(new SourceRateMetadata(44_100, 2, onDisk[0].length));
        assertThat(loaded.isDirty()).isTrue();

        saved(reopened, reopened.getCurrentDawProject());
        assertThat(Files.readString(projectDir.resolve("project.daw")))
                .contains("source-file=\"audio/moved-take/segment-000.wav\"")
                .contains("<source-segment path=\"audio/moved-take/segment-000.wav\"/>")
                .contains("<source-segment path=\"audio/moved-take/segment-001.wav\"/>")
                .doesNotContain(projectDir.toString());
        reopened.closeProject().join();
    }

    @Test
    void aTakeWhoseFolderLacksOneSegmentIsRefusedAndNothingIsRepointed() throws Exception {
        Path recordedIn = tempDir.resolve("recorded");
        List<String> recorded = List.of(recordedIn.resolve("segment-000.wav").toString(),
                recordedIn.resolve("segment-001.wav").toString());
        Path moved = tempDir.resolve("moved");
        Path picked = segment(moved, 0, 44_100.0, 256); // segment-001.wav did not come along
        DawProject project = new DawProject("Partial", FORMAT);
        AudioClip clip = new AudioClip("Take 1", 0.0, 0.01, null);
        clip.setSourceSegmentPaths(recorded);
        project.createAudioTrack("Vocals").addClip(clip);
        project.markClean();
        AssetReference.Take asset = new AssetReference.Take(recorded);

        RelinkPreparation prepared = new AssetRelinker().prepare(AssetRelinker.request(project, asset), picked);

        assertThat(prepared).isInstanceOfSatisfying(RelinkPreparation.Refusal.class, refusal -> {
            assertThat(refusal.asset()).isEqualTo(asset);
            assertThat(refusal.reason()).isEqualTo(MissingAssetReason.NOT_FOUND);
            assertThat(refusal.detail()).as("its own sentence: \"Not relinked —\" would repeat the at-open one")
                    .isEqualTo("1 of 2 segments is not in " + moved + ": segment-001.wav");
        });
        assertThat(clip.getSourceSegmentPaths()).containsExactlyElementsOf(recorded);
        assertThat(clip.getAudioData()).isNull();
        assertThat(project.isDirty()).isFalse();
    }

    @Test
    void aTakeWhoseSegmentsDisagreeOnSampleRateIsRefusedAsUnreadable() throws Exception {
        Path recordedIn = tempDir.resolve("recorded");
        List<String> recorded = List.of(recordedIn.resolve("segment-000.wav").toString(),
                recordedIn.resolve("segment-001.wav").toString());
        Path moved = tempDir.resolve("moved");
        Path picked = segment(moved, 0, 44_100.0, 256);
        segment(moved, 1, 48_000.0, 256);
        DawProject project = new DawProject("Mixed", FORMAT);
        AudioClip clip = new AudioClip("Take 1", 0.0, 0.01, null);
        clip.setSourceSegmentPaths(recorded);
        project.createAudioTrack("Vocals").addClip(clip);

        RelinkPreparation prepared = new AssetRelinker()
                .prepare(AssetRelinker.request(project, new AssetReference.Take(recorded)), picked);

        assertThat(prepared).isInstanceOfSatisfying(RelinkPreparation.Refusal.class, refusal -> {
            assertThat(refusal.reason()).isEqualTo(MissingAssetReason.UNREADABLE);
            assertThat(refusal.detail()).contains("sample rate");
        });
        assertThat(clip.getSourceSegmentPaths()).containsExactlyElementsOf(recorded);
    }

    @Test
    void everyClipHoldingTheAssetIsRepointedOntoOneArrayReplacingWhatItHeldAndInvalidatedOnceEach()
            throws Exception {
        Path gone = tempDir.resolve("old").resolve("loop.wav");
        Path found = wav(tempDir.resolve("new").resolve("loop.wav"), RATE, RATE, 2); // 1 s = 2 beats at 120 BPM
        DawProject project = new DawProject("Holders", FORMAT);
        Track drums = project.createAudioTrack("Drums");
        AudioClip first = new AudioClip("loop", 0.0, 2.0, gone.toString());
        drums.addClip(first);
        AudioClip second = first.splitAt(1.0);
        drums.addClip(second);
        // Both halves hold what a changed file gave them; a relink may replace it.
        float[][] stale = new float[2][10];
        first.setAudioData(stale);
        second.setAudioData(stale);
        AudioClip unrelated = new AudioClip("other", 4.0, 1.0, tempDir.resolve("other.wav").toString());
        project.createAudioTrack("Bass").addClip(unrelated);
        project.markClean();
        AssetReference asset = new AssetReference.SingleFile(gone.toString());
        AssetRelinker.Request request = AssetRelinker.request(project, asset);
        assertThat(request.clipCount()).isEqualTo(2);

        RelinkPreparation.Candidate candidate =
                (RelinkPreparation.Candidate) new AssetRelinker().prepare(request, found);
        List<String> invalidated = new ArrayList<>();
        AssetRelinker.Applied applied = AssetRelinker.apply(candidate, invalidated::add);

        assertThat(applied.relinked()).containsExactly(first, second);
        assertThat(invalidated).containsExactly(first.getId(), second.getId());
        float[][] relinkedAudio = first.getAudioData();
        assertThat(relinkedAudio).isNotNull().isNotSameAs(stale);
        assertThat(second.getAudioData()).as("one array for the source").isSameAs(relinkedAudio);
        assertThat(first.getSourceFilePath()).isEqualTo(found.toString());
        assertThat(second.getSourceFilePath()).isEqualTo(found.toString());
        assertThat(unrelated.getSourceFilePath()).isEqualTo(tempDir.resolve("other.wav").toString());
        assertThat(unrelated.getAudioData()).isNull();
        assertThat(project.isDirty()).isTrue();
        new GlueClipsAction(drums, first, second).execute();
        assertThat(drums.getClips()).singleElement().extracting(AudioClip::getAudioData).isSameAs(relinkedAudio);
    }

    @Test
    void aLocatedFileThatDoesNotDecodeOrIsNotThereIsRefusedWithItsReason() throws Exception {
        Path bad = Files.writeString(tempDir.resolve("bad.wav"), "not really a wav");
        Path nowhere = tempDir.resolve("nowhere.wav");
        DawProject project = new DawProject("Refusals", FORMAT);
        AudioClip clip = new AudioClip("gone", 0.0, 1.0, tempDir.resolve("gone.wav").toString());
        project.createAudioTrack("T").addClip(clip);
        project.markClean();
        AssetReference asset = new AssetReference.SingleFile(clip.getSourceFilePath());
        AssetRelinker.Request request = AssetRelinker.request(project, asset);
        AssetRelinker relinker = new AssetRelinker();

        assertThat(relinker.prepare(request, bad)).isInstanceOfSatisfying(RelinkPreparation.Refusal.class,
                refusal -> {
                    assertThat(refusal.asset()).isEqualTo(asset);
                    assertThat(refusal.reason()).isEqualTo(MissingAssetReason.UNREADABLE);
                    assertThat(refusal.detail()).startsWith(bad.toString() + ": ").hasSizeGreaterThan(
                            bad.toString().length() + 2);
                });
        assertThat(relinker.prepare(request, nowhere)).isInstanceOfSatisfying(RelinkPreparation.Refusal.class,
                refusal -> {
                    assertThat(refusal.reason()).isEqualTo(MissingAssetReason.NOT_FOUND);
                    assertThat(refusal.detail()).isEqualTo(nowhere + ": not found");
                });
        assertThat(clip.getSourceFilePath()).isEqualTo(tempDir.resolve("gone.wav").toString());
        assertThat(clip.getAudioData()).isNull();
        assertThat(project.isDirty()).isFalse();
    }

    @Test
    void aLocatedFileShorterThanAClipUsesIsACandidateThatAsksForConfirmation() throws Exception {
        Path half = wav(tempDir.resolve("half.wav"), RATE, RATE / 2, 2); // 0.5 s = 1 beat at 120 BPM
        DawProject project = new DawProject("Short", FORMAT);
        Track track = project.createAudioTrack("Pad");
        AudioClip tooLong = new AudioClip("too long", 0.0, 3.0, tempDir.resolve("gone-long.wav").toString());
        AudioClip fits = new AudioClip("fits", 4.0, 1.0, tempDir.resolve("gone-fits.wav").toString());
        track.addClip(tooLong);
        track.addClip(fits);
        AssetRelinker relinker = new AssetRelinker();

        RelinkPreparation longer = relinker.prepare(AssetRelinker.request(project,
                new AssetReference.SingleFile(tooLong.getSourceFilePath())), half);
        RelinkPreparation fitting = relinker.prepare(AssetRelinker.request(project,
                new AssetReference.SingleFile(fits.getSourceFilePath())), half);

        assertThat(longer).isInstanceOfSatisfying(RelinkPreparation.Candidate.class, candidate -> {
            assertThat(candidate.shorterThanUsed()).isTrue();
            assertThat(candidate.needsConfirmation()).isTrue();
            assertThat(candidate.locatedName()).as("the confirmation names the file").isEqualTo("half.wav");
            assertThat(candidate.heldSeconds()).isEqualTo(0.5);
            assertThat(candidate.usedSeconds()).as("3 beats at 120 BPM").isEqualTo(1.5);
            assertThat(candidate.takeFolderMismatch()).isEmpty();
            assertThat(candidate.missingAdmMetadata()).isEmpty();
        });
        assertThat(fitting).isInstanceOfSatisfying(RelinkPreparation.Candidate.class, candidate -> {
            assertThat(candidate.shorterThanUsed()).isFalse();
            assertThat(candidate.needsConfirmation()).isFalse();
            assertThat(candidate.usedSeconds()).isEqualTo(0.5);
        });
        // Confirmed: it applies as it is.
        AssetRelinker.apply((RelinkPreparation.Candidate) longer, id -> { });
        assertThat(tooLong.getAudioData()).isNotNull();
        assertThat(tooLong.getAudioData()[0]).hasSize(RATE / 2);
    }

    @Test
    void aLocatedFileAboveHalfTheHeapIsRefusedAsTooLargeWithoutBeingDecoded() throws Exception {
        Path big = wav(tempDir.resolve("big.wav"), RATE, 1_000, 1);
        DawProject project = new DawProject("Budget", FORMAT);
        AudioClip clip = new AudioClip("big", 0.0, 0.01, tempDir.resolve("gone.wav").toString());
        project.createAudioTrack("T").addClip(clip);
        RecordingSourceDecoder decoder = new RecordingSourceDecoder()
                .cost(new AssetReference.SingleFile(big.toString()), 501);

        RelinkPreparation prepared = new AssetRelinker(decoder, 1_000).prepare(
                AssetRelinker.request(project, new AssetReference.SingleFile(clip.getSourceFilePath())), big);

        assertThat(prepared).isInstanceOfSatisfying(RelinkPreparation.Refusal.class, refusal -> {
            assertThat(refusal.reason()).isEqualTo(MissingAssetReason.TOO_LARGE);
            assertThat(refusal.detail()).isEqualTo(big + ": needs about 1 MiB of memory to load;"
                    + " at most 1 MiB is allowed (half the application's memory limit)");
        });
        assertThat(decoder.decodes()).isEmpty();
    }

    @Test
    void anAdmFileRelinkGivesEachBedAndObjectClipItsOwnAudioAndReportsAClipWhoseNameSelectsNothing()
            throws Exception {
        Path original = admScene(tempDir.resolve("old").resolve("scene.wav"), 44_100);
        DawProject project = new DawProject("Atmos", FORMAT);
        new AudioFileImporter(project).importFile(original, 0.0);
        AudioClip bed = onlyClipOn(project, "Bed (Stereo)");
        AudioClip objectOne = onlyClipOn(project, "Object 1");
        AudioClip objectTwo = onlyClipOn(project, "Object 2");
        float[][] bedImported = bed.getAudioData();
        float[][] objectTwoImported = objectTwo.getAudioData();
        objectOne.setName("Lead vocal");
        for (Track track : project.getTracks()) {
            for (AudioClip clip : track.getClips()) {
                clip.setAudioData(null); // the file went missing before the reopen
            }
        }
        Path moved = Files.createDirectories(tempDir.resolve("new")).resolve("scene.wav");
        Files.move(original, moved);
        AssetRelinker.Request request = AssetRelinker.request(project,
                new AssetReference.SingleFile(original.toString()));

        RelinkPreparation prepared = new AssetRelinker().prepare(request, moved);
        assertThat(prepared).isInstanceOf(RelinkPreparation.Candidate.class);
        AssetRelinker.Applied applied = AssetRelinker.apply((RelinkPreparation.Candidate) prepared, id -> { });

        assertThat(applied.relinked()).containsExactlyInAnyOrder(bed, objectOne, objectTwo);
        assertThat(Arrays.deepEquals(bed.getAudioData(), bedImported)).isTrue();
        assertThat(Arrays.deepEquals(objectTwo.getAudioData(), objectTwoImported)).isTrue();
        assertThat(objectOne.getAudioData()).as("a renamed clip is never guessed").isNull();
        assertThat(objectOne.getSourceFilePath()).as("but it follows its asset").isEqualTo(moved.toString());
        assertThat(applied.rows()).singleElement().satisfies(row -> {
            assertThat(row.reason()).isEqualTo(MissingAssetReason.ADM_OBJECT_UNKNOWN);
            assertThat(row.reference()).isEqualTo(new AssetReference.SingleFile(moved.toString()));
            assertThat(row.holders()).as("every clip the relink re-pointed")
                    .extracting(MissingAsset.Holder::clipId)
                    .containsExactly(bed.getId(), objectOne.getId(), objectTwo.getId());
            assertThat(row.silentHolders()).extracting(MissingAsset.Holder::clipId)
                    .containsExactly(objectOne.getId());
            assertThat(row.detail()).isEqualTo("1 of them selects no bed or object of the file (it has:"
                    + " Bed (Stereo), Object 1, Object 2)");
        });
        assertThat(MissingAssetsReport.EMPTY.withAll(applied.rows()).silentClipCount()).isEqualTo(1);
    }

    @Test
    void aTakeLocatedInAnotherTracksFolderOfTheSameTakeIsACandidateThatNamesBothFolders() throws Exception {
        Path takes = TakeDirectories.allocate(tempDir.resolve("moved").resolve("audio"),
                Instant.parse("2026-10-20T10:00:00Z"));
        // An eight-track drum take: every track folder holds segment-000 and segment-001.
        List<Path> trackFolders = new ArrayList<>();
        for (int track = 0; track < 8; track++) {
            Path folder = takes.resolve("track-" + track);
            segment(folder, 0, 48_000.0, 256);
            segment(folder, 1, 48_000.0, 256);
            trackFolders.add(folder);
        }
        String takeName = takes.getFileName().toString();
        Path recordedIn = tempDir.resolve("old").resolve("audio").resolve("takes").resolve(takeName)
                .resolve("track-0");
        List<String> recorded = List.of(recordedIn.resolve("segment-000.wav").toString(),
                recordedIn.resolve("segment-001.wav").toString());
        DawProject project = new DawProject("Drums", FORMAT);
        AudioClip kick = new AudioClip("Kick take", 0.0, 0.01, null);
        kick.setSourceSegmentPaths(recorded);
        project.createAudioTrack("Kick").addClip(kick);
        AssetRelinker.Request request = AssetRelinker.request(project, new AssetReference.Take(recorded));
        AssetRelinker relinker = new AssetRelinker();

        RelinkPreparation snareFolder = relinker.prepare(request, trackFolders.get(1).resolve("segment-000.wav"));
        RelinkPreparation ownFolder = relinker.prepare(request, trackFolders.get(0).resolve("segment-001.wav"));

        assertThat(snareFolder).isInstanceOfSatisfying(RelinkPreparation.Candidate.class, candidate -> {
            assertThat(candidate.takeFolderMismatch()).as("same names, frames and rate: only the folder tells")
                    .contains(new RelinkPreparation.TakeFolderMismatch(takeName + "/track-1",
                            takeName + "/track-0"));
            assertThat(candidate.needsConfirmation()).isTrue();
            assertThat(candidate.shorterThanUsed()).isFalse();
            assertThat(candidate.locatedName()).as("named as the take's folder is").isEqualTo(takeName + "/track-1");
        });
        assertThat(ownFolder).isInstanceOfSatisfying(RelinkPreparation.Candidate.class, candidate -> {
            assertThat(candidate.takeFolderMismatch()).as("the take's own folder, moved with its take").isEmpty();
            assertThat(candidate.needsConfirmation()).isFalse();
            assertThat(candidate.replacement()).isEqualTo(new AssetReference.Take(List.of(
                    trackFolders.get(0).resolve("segment-000.wav").toString(),
                    trackFolders.get(0).resolve("segment-001.wav").toString())));
        });
        assertThat(kick.getSourceSegmentPaths()).as("preparing touches nothing").containsExactlyElementsOf(recorded);
    }

    @Test
    void anAdmImportsClipsGivenAPlainFileAreACandidateThatSaysTheFileHasNoAdmMetadata() throws Exception {
        Path plain = wav(tempDir.resolve("stripped").resolve("scene.wav"), RATE, RATE, 2);
        String gone = tempDir.resolve("old").resolve("scene.wav").toString();
        DawProject project = new DawProject("Atmos", FORMAT);
        AudioClip bed = new AudioClip("Bed (Stereo)", 0.0, 2.0, gone);
        AudioClip object = new AudioClip("Object 1", 0.0, 2.0, gone);
        project.createAudioTrack("Bed (Stereo)").addClip(bed);
        project.createAudioTrack("Object 1").addClip(object);
        AssetRelinker.Request request = AssetRelinker.request(project, new AssetReference.SingleFile(gone));

        RelinkPreparation prepared = new AssetRelinker().prepare(request, plain);

        assertThat(prepared).isInstanceOfSatisfying(RelinkPreparation.Candidate.class, candidate -> {
            assertThat(candidate.missingAdmMetadata()).contains(new RelinkPreparation.MissingAdmMetadata(2));
            assertThat(candidate.needsConfirmation()).isTrue();
            assertThat(candidate.locatedName()).isEqualTo("scene.wav");
        });
        // Confirmed: the file is attached as a plain file to every clip.
        AssetRelinker.Applied applied = AssetRelinker.apply((RelinkPreparation.Candidate) prepared, id -> { });
        assertThat(applied.rows()).isEmpty();
        assertThat(bed.getAudioData()).hasNumberOfRows(2);
        assertThat(object.getAudioData()).isSameAs(bed.getAudioData());
    }

    @Test
    void anAdmImportsClipsGivenAPlainFileAskFirstWhenAnotherProgramRenamedOne() throws Exception {
        Path plain = wav(tempDir.resolve("stripped").resolve("master.wav"), RATE, RATE, 2);
        String gone = tempDir.resolve("old").resolve("master.wav").toString();
        DawProject project = new DawProject("Interchanged", FORMAT);
        AudioClip bed = new AudioClip("Bed (Stereo)", 0.0, 2.0, gone);
        AudioClip object = new AudioClip("Object 1", 0.0, 2.0, gone);
        AudioClip vocal = new AudioClip("Vocal", 0.0, 2.0, gone);
        project.createAudioTrack("Bed (Stereo)").addClip(bed);
        project.createAudioTrack("Object 1").addClip(object);
        project.createAudioTrack("Vocal").addClip(vocal);
        AssetRelinker.Request request = AssetRelinker.request(project, new AssetReference.SingleFile(gone));

        RelinkPreparation prepared = new AssetRelinker().prepare(request, plain);

        assertThat(prepared).isInstanceOfSatisfying(RelinkPreparation.Candidate.class, candidate -> {
            assertThat(candidate.missingAdmMetadata()).as("a clip of another name does not make the file plain")
                    .contains(new RelinkPreparation.MissingAdmMetadata(2));
            assertThat(candidate.needsConfirmation()).isTrue();
        });
        // Confirmed: the file is attached as a plain file to every clip, whatever its name.
        AssetRelinker.Applied applied = AssetRelinker.apply((RelinkPreparation.Candidate) prepared, id -> { });
        assertThat(applied.rows()).isEmpty();
        assertThat(bed.getAudioData()).hasNumberOfRows(2);
        assertThat(object.getAudioData()).isSameAs(bed.getAudioData());
        assertThat(vocal.getAudioData()).isSameAs(bed.getAudioData());
    }

    @Test
    void aSingleObjectNamedClipGivenAPlainFileNeedsNoConfirmation() throws Exception {
        Path plain = wav(tempDir.resolve("mine").resolve("Object 1.wav"), RATE, RATE, 2);
        String gone = tempDir.resolve("old").resolve("Object 1.wav").toString();
        DawProject project = new DawProject("Plain", FORMAT);
        Track track = project.createAudioTrack("Object 1");
        AudioClip object = new AudioClip("Object 1", 0.0, 2.0, gone);
        track.addClip(object);
        track.addClip(object.splitAt(1.0));

        RelinkPreparation prepared = new AssetRelinker()
                .prepare(AssetRelinker.request(project, new AssetReference.SingleFile(gone)), plain);

        assertThat(prepared).isInstanceOfSatisfying(RelinkPreparation.Candidate.class, candidate -> {
            assertThat(candidate.missingAdmMetadata()).as("one distinct ADM-like name is a plain file").isEmpty();
            assertThat(candidate.needsConfirmation()).isFalse();
        });
    }

    @Test
    void oneObjectNamedClipAndAClipOfAnotherNameGivenAPlainFileNeedNoConfirmation() throws Exception {
        Path plain = wav(tempDir.resolve("mine").resolve("take.wav"), RATE, RATE, 2);
        String gone = tempDir.resolve("old").resolve("take.wav").toString();
        DawProject project = new DawProject("Plain", FORMAT);
        project.createAudioTrack("Object 1").addClip(new AudioClip("Object 1", 0.0, 2.0, gone));
        project.createAudioTrack("Vocal").addClip(new AudioClip("Vocal", 0.0, 2.0, gone));

        RelinkPreparation prepared = new AssetRelinker()
                .prepare(AssetRelinker.request(project, new AssetReference.SingleFile(gone)), plain);

        assertThat(prepared).isInstanceOfSatisfying(RelinkPreparation.Candidate.class, candidate -> {
            assertThat(candidate.missingAdmMetadata())
                    .as("one distinct ADM-like name is a plain file, whatever the other clips are named").isEmpty();
            assertThat(candidate.needsConfirmation()).isFalse();
        });
    }

    @Test
    void locateFileOpensInATakesOwnFolderOrItsNearestExistingAncestorInsideTheProject() throws Exception {
        Path projectDir = Files.createDirectories(tempDir.resolve("Song"));
        Path takes = Files.createDirectories(projectDir.resolve("audio").resolve("takes"));
        Path ownFolder = Files.createDirectories(takes.resolve("2026-10-20T10-00-00_take-0001").resolve("kick"));
        AssetReference present = new AssetReference.Take(List.of(ownFolder.resolve("segment-000.wav").toString()));
        AssetReference folderGone = new AssetReference.Take(List.of(
                takes.resolve("2026-10-20T10-00-00_take-0002").resolve("kick").resolve("segment-000.wav").toString()));
        AssetReference outside = new AssetReference.Take(List.of(
                tempDir.resolve("elsewhere").resolve("kick").resolve("segment-000.wav").toString()));
        AssetReference file = new AssetReference.SingleFile(ownFolder.resolve("segment-000.wav").toString());

        assertThat(AssetRelinker.locateFileDirectory(present, projectDir)).contains(ownFolder);
        assertThat(AssetRelinker.locateFileDirectory(folderGone, projectDir)).contains(takes);
        assertThat(AssetRelinker.locateFileDirectory(outside, projectDir)).contains(projectDir);
        assertThat(AssetRelinker.locateFileDirectory(outside, null)).isEmpty();
        assertThat(AssetRelinker.locateFileDirectory(file, projectDir)).as("a file's chooser opens in the project")
                .contains(projectDir);
    }

    @Test
    void locateFileNeverLooksAtATakeFolderOutsideTheProjectFolder() throws Exception {
        Path projectDir = Files.createDirectories(tempDir.resolve("Song"));
        // Where an offline share would be: the folder is there, but asking
        // could stall the chooser for the share's timeout.
        Path elsewhere = Files.createDirectories(tempDir.resolve("share")
                .resolve("2026-10-20T10-00-00_take-0001").resolve("kick"));
        AssetReference outside = new AssetReference.Take(List.of(elsewhere.resolve("segment-000.wav").toString()));

        assertThat(AssetRelinker.locateFileDirectory(outside, projectDir))
                .as("only folders inside the project are looked at, so the project folder")
                .contains(projectDir);
        assertThat(AssetRelinker.locateFileDirectory(outside, null)).as("no project folder, nothing to propose")
                .isEmpty();
    }

    @Test
    void aTakeLocatedInAnotherTakeOfTheSameTrackNamesThePickedTakeAndTrackFolder() throws Exception {
        Path audio = tempDir.resolve("Song").resolve("audio");
        String trackId = "3f2a9c1e-5b7d-4e2a-9f10-6c8d2b4a7e31";
        Instant stamp = Instant.parse("2026-10-20T15:30:12Z");
        Path ownTake = TakeDirectories.allocate(audio, stamp);
        Path nextTake = TakeDirectories.allocate(audio, stamp);
        // The track's own folder of its take is gone; the next take of the
        // same track has a folder with the same track id and segment names.
        Path nextTakeTrack = nextTake.resolve(trackId);
        segment(nextTakeTrack, 0, 48_000.0, 256);
        segment(nextTakeTrack, 1, 48_000.0, 256);
        Path recordedIn = ownTake.resolve(trackId);
        List<String> recorded = List.of(recordedIn.resolve("segment-000.wav").toString(),
                recordedIn.resolve("segment-001.wav").toString());
        DawProject project = new DawProject("Vocal", FORMAT);
        AudioClip vocal = new AudioClip("Vocal take", 0.0, 0.01, null);
        vocal.setSourceSegmentPaths(recorded);
        project.createAudioTrack("Vocal").addClip(vocal);
        String ownName = ownTake.getFileName() + "/" + trackId;
        String nextName = nextTake.getFileName() + "/" + trackId;

        RelinkPreparation prepared = new AssetRelinker().prepare(
                AssetRelinker.request(project, new AssetReference.Take(recorded)),
                nextTakeTrack.resolve("segment-000.wav"));

        assertThat(prepared).isInstanceOfSatisfying(RelinkPreparation.Candidate.class, candidate -> {
            assertThat(candidate.takeFolderMismatch())
                    .as("the picked folder names its take, not just the track id both folders share")
                    .contains(new RelinkPreparation.TakeFolderMismatch(nextName, ownName));
            assertThat(candidate.locatedName()).as("the confirmation tells this take from the track's others")
                    .isEqualTo(nextName);
        });
    }

    @Test
    void aLocatedFileIsJudgedAtTheTempoTheEnginePlaysItsClipsAtNotAFasterTempoLaterInTheMap() throws Exception {
        Path half = wav(tempDir.resolve("half.wav"), RATE, RATE / 2, 2); // 0.5 s
        DawProject project = new DawProject("Tempo", FORMAT);
        project.getTransport().setTempo(60.0);
        project.getTransport().getTempoMap().addTempoChange(TempoChangeEvent.instant(100.0, 120.0));
        AudioClip click = new AudioClip("click", 0.0, 1.0, tempDir.resolve("gone.wav").toString());
        project.createAudioTrack("Click").addClip(click);

        RelinkPreparation prepared = new AssetRelinker().prepare(
                AssetRelinker.request(project, new AssetReference.SingleFile(click.getSourceFilePath())), half);

        assertThat(prepared).isInstanceOfSatisfying(RelinkPreparation.Candidate.class, candidate -> {
            assertThat(candidate.usedSeconds()).as("one beat at the transport's 60 BPM").isEqualTo(1.0);
            assertThat(candidate.shorterThanUsed()).isTrue();
            assertThat(candidate.needsConfirmation()).isTrue();
        });
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    /**
     * A project whose one clip was imported from {@code library/tone.wav}
     * (44.1 kHz into 48 kHz), saved, and reopened after the file was moved
     * away: the clip is silent and the open's report has its row.
     */
    private Reopened reopenWithMovedImport(String name) throws IOException {
        Path library = wav(tempDir.resolve("library").resolve("tone.wav"), 44_100, 22_050, 2);
        ProjectManager manager = newManager();
        Path projectDir = manager.createProject(name, tempDir).projectPath();
        DawProject project = new DawProject(name, FORMAT);
        float[][] imported = new AudioFileImporter(project).importFile(library, 0.0).clip().getAudioData();
        saved(manager, project);
        manager.closeProject().join();
        Path parked = Files.createDirectories(tempDir.resolve("parked-" + name)).resolve("tone.wav");
        Files.move(library, parked);

        ProjectManager reopened = newManager();
        reopened.openProject(projectDir);
        MissingAsset row = reopened.getLastMissingAssets().assets().getFirst();
        assertThat(row.reason()).isEqualTo(MissingAssetReason.NOT_FOUND);
        return new Reopened(reopened, projectDir, reopened.getCurrentDawProject(), row, library, parked, imported);
    }

    /**
     * @param originalLocation where the clip's reference still points; empty
     * @param parked           the moved file; tests copy it to where the user "finds" it
     */
    private record Reopened(ProjectManager manager, Path projectDir, DawProject project, MissingAsset row,
                            Path originalLocation, Path parked, float[][] importedAudio) {
    }

    private static ProjectManager newManager() {
        return new ProjectManager(new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true)));
    }

    private static AudioClip onlyClip(DawProject project) {
        assertThat(project.getTracks()).hasSize(1);
        assertThat(project.getTracks().getFirst().getClips()).hasSize(1);
        return project.getTracks().getFirst().getClips().getFirst();
    }

    private static AudioClip onlyClipOn(DawProject project, String trackName) {
        Track track = project.getTracks().stream().filter(t -> t.getName().equals(trackName)).findFirst()
                .orElseThrow();
        assertThat(track.getClips()).hasSize(1);
        return track.getClips().getFirst();
    }
}
