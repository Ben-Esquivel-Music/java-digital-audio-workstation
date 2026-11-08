package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.midi.SoundFontAssignment;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.TakeManifest;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SealStatus;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SealedBy;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SegmentEntry;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SegmentState;
import com.benesquivelmusic.daw.core.recording.TakeManifest.TrackEntry;
import com.benesquivelmusic.daw.core.track.Track;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.TIMEOUT_SECONDS;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.saved;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Save As engine's copy (story 333, ruling R5; Persistence Integrity book
 * §2.2, §3.3, §4.1): what goes into the copy and what does not, the sizes and
 * the free-space check, the staging-then-rename publish (the named folder
 * never holds half a project), {@code project.daw} written last into an
 * existing empty folder, the refusals, and a cancel or a failure that
 * deletes exactly what the copy created. Through {@link ProjectManager}'s
 * own Save As methods; the plan's free-space refusal through
 * {@link ProjectFolderCopy}'s volume seam.
 */
class SaveAsCopiesTheProjectTest {

    @TempDir
    Path tmp;

    private SaveTestSupport.RecordingMover mover;
    private ProjectManager manager;
    private DawProject project;
    private Path songA;
    private Path songB;
    private Path library;

    @BeforeEach
    void songAOnDisk() throws IOException {
        mover = new SaveTestSupport.RecordingMover();
        manager = new ProjectManager(new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true)),
                null, new ProjectLockManager(), new ProjectDeserializer(),
                new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, mover));
        ProjectMetadata created = manager.createProject("Song A", tmp);
        songA = created.projectPath();
        songB = tmp.resolve("Song B");
        library = Files.createDirectories(tmp.resolve("Library"));

        write(songA.resolve("audio/imported.wav"), 1_000);
        write(songA.resolve("audio/unreferenced.wav"), 500);
        sealedTake(songA.resolve("audio/takes/t1"));
        write(songA.resolve("audio/takes/t1/vox/segment-000.wav"), 2_000);
        write(songA.resolve("audio/takes/t1/take.manifest.tmp"), 10);
        streamingTake(songA.resolve("audio/takes/t2"));
        write(songA.resolve("audio/takes/t2/vox/segment-000.wav"), 3_000);
        write(songA.resolve("audio/vox.wav.part"), 40);
        write(songA.resolve("audio/.imported.wav.0123456789abcdef.tmp"), 30);
        write(songA.resolve("samples/kick.wav"), 700);
        write(songA.resolve("samples/unused.wav"), 70);
        write(songA.resolve("sf/piano.sf2"), 900);
        write(songA.resolve("snapshots/Mix A.daw"), 300);
        write(songA.resolve("snapshots/.Mix A.daw.0123456789abcdef.tmp"), 20);
        write(songA.resolve("journal/segment-00001.jsonl"), 50);
        write(songA.resolve("sessions/session.json"), 60);
        write(songA.resolve("project.daw.v1.20260101-000000.bak"), 80);
        write(songA.resolve("project.daw.degraded.20260101-000000.bak"), 80);
        write(songA.resolve("project.daw.recovered.20260101-000000.bak"), 80);
        write(songA.resolve(".project.daw.0123456789abcdef.tmp"), 80);
        write(songA.resolve("notes.txt"), 15);
        write(songA.resolve("archives/old.dawz"), 25);
        write(library.resolve("external.wav"), 400);

        project = new DawProject("Song A", AudioFormat.CD_QUALITY);
        project.setMetadata(created);
        Track vox = project.createAudioTrack("Vox");
        vox.addClip(new AudioClip("imported", 0, 4, songA.resolve("audio/imported.wav").toString()));
        AudioClip take = new AudioClip("take", 4, 4, songA.resolve("audio/takes/t1/vox/segment-000.wav").toString());
        take.setSourceSegmentPaths(List.of(songA.resolve("audio/takes/t1/vox/segment-000.wav").toString()));
        vox.addClip(take);
        vox.addClip(new AudioClip("kick", 8, 1, songA.resolve("samples/kick.wav").toString()));
        vox.addClip(new AudioClip("gone", 9, 1, songA.resolve("audio/gone.wav").toString()));
        vox.addClip(new AudioClip("external", 10, 1, library.resolve("external.wav").toString()));
        project.createMidiTrack("Keys").setSoundFontAssignment(
                new SoundFontAssignment(songA.resolve("sf/piano.sf2"), 0, 0, "Piano"));
        saved(manager, project);
        project.markDirty();
        mover.clear();
    }

    @Test
    void theCopyHoldsAudioReferencedFilesAndSnapshotsAndNothingOfTheOriginalsTimeline() throws IOException {
        long revision = project.getEditRevision();
        Map<String, FileTime> originalBefore = listingWithTimes(songA);

        SaveAsPlan.Ready plan = ready(planInto(songB));
        SaveAsOutcome outcome = manager.publishCopy(plan, SaveAsProgress.NONE, () -> false);

        assertThat(plan.files()).extracting(file -> slashed(file.relativePath())).containsExactly(
                "audio/imported.wav", "audio/takes/t1/take.manifest", "audio/takes/t1/vox/segment-000.wav",
                "audio/unreferenced.wav", "samples/kick.wav", "sf/piano.sf2", "snapshots/Mix A.daw");
        assertThat(plan.missingFiles()).as("audio/gone.wav: named, under the folder, absent").isEqualTo(1);
        assertThat(plan.unknownEntries()).as("notes.txt; archives/ is known and left out (ruling 3 Q2)").isEqualTo(1);
        assertThat(plan.intoExistingEmptyFolder()).isFalse();
        assertThat(outcome).isInstanceOf(SaveAsOutcome.Published.class);
        SaveAsOutcome.Published published = (SaveAsOutcome.Published) outcome;
        assertThat(published.folder()).isEqualTo(songB);
        assertThat(published.metadata().projectPath()).isEqualTo(songB);
        assertThat(published.metadata().name()).isEqualTo("Song B");
        assertThat(published.sourceFolder()).contains(songA);
        assertThat(published.formerRoots().roots()).extracting(FormerRoots.Root::folder).containsExactly(songA);
        assertThat(published.formerRoots().heldFrom(songA)).as("the copy holds exactly the files it copied")
                .containsExactlyInAnyOrderElementsOf(plan.files().stream().map(SaveAsPlan.CopyItem::relativePath).toList());
        assertThat(published.missingFiles()).isEqualTo(1);
        assertThat(published.unknownEntries()).isEqualTo(1);
        assertThat(plan.files()).as("the plan's bytes exclude the archives").noneMatch(file -> slashed(file.relativePath()).startsWith("archives/"));
        assertThat(songB.resolve("archives")).as("the original's packaged archives stay with it").doesNotExist();

        assertThat(files(songB)).containsExactly(
                "audio/imported.wav", "audio/takes/t1/take.manifest", "audio/takes/t1/vox/segment-000.wav",
                "audio/unreferenced.wav", "project.daw", "samples/kick.wav", "sf/piano.sf2", "snapshots/Mix A.daw");
        assertThat(songB.resolve("audio/takes/t1/vox/segment-000.wav"))
                .hasSameBinaryContentAs(songA.resolve("audio/takes/t1/vox/segment-000.wav"));
        assertThat(songB.resolve("samples/kick.wav")).hasSameBinaryContentAs(songA.resolve("samples/kick.wav"));

        assertThat(plan.bytes()).as("the files' sizes plus the rendered head")
                .isEqualTo(1_000 + 2_000 + Files.size(songA.resolve("audio/takes/t1/take.manifest")) + 500 + 700 + 900
                        + 300 + Files.size(songB.resolve("project.daw")));
        assertThat(plan.fileBytes()).isEqualTo(plan.bytes() - Files.size(songB.resolve("project.daw")));

        assertThat(listingWithTimes(songA)).as("the original folder is only read").isEqualTo(originalBefore);
        assertThat(project.getName()).as("the live project is not renamed").isEqualTo("Song A");
        assertThat(project.getMetadata().projectPath()).isEqualTo(songA);
        assertThat(project.isDirty()).isTrue();
        assertThat(project.getEditRevision()).isEqualTo(revision);
    }

    /**
     * COV-S-12 as the studio engineer's ruling S1 on stage 3 amends it: no
     * reference to a file the copy holds names the original folder; a
     * reference the copy could not take — {@code audio/gone.wav}, absent, and
     * a SoundFont {@code sf/missing.sf2}, absent — keeps its original
     * absolute path, so it still names the original's file (and plays once
     * that file is back), never a file the copy does not have.
     */
    @Test
    void theCopysProjectFileIsSelfContainedAndNamedAfterItsFolder() throws IOException {
        project.createMidiTrack("Pad").setSoundFontAssignment(
                new SoundFontAssignment(songA.resolve("sf/missing.sf2"), 0, 0, "Pad"));
        SaveAsCapture capture = manager.captureForSaveAs(project, "Song B");
        manager.publishCopy(ready(manager.planSaveAs(capture, songB, () -> false)), SaveAsProgress.NONE, () -> false);

        String xml = Files.readString(songB.resolve("project.daw"), StandardCharsets.UTF_8);
        String gone = songA.resolve("audio/gone.wav").toString();
        String missingFont = songA.resolve("sf/missing.sf2").toString().replace('\\', '/');
        assertThat(xml).contains("source-file=\"" + gone + "\"").contains("path=\"" + missingFont + "\"");
        assertThat(xml.replace("source-file=\"" + gone + "\"", "").replace("path=\"" + missingFont + "\"", ""))
                .as("no reference to a file the copy holds names the original folder (COV-S-12)")
                .doesNotContain(songA.toString())
                .doesNotContain(songA.toString().replace('\\', '/'));
        assertThat(xml).contains("source-file=\"audio/imported.wav\"").contains("source-file=\"samples/kick.wav\"")
                .contains("path=\"audio/takes/t1/vox/segment-000.wav\"");
        DawProject copy = new ProjectDeserializer().deserialize(xml, songB);
        assertThat(copy.getName()).isEqualTo("Song B");
        assertThat(copy.getMetadata().lastModified()).isEqualTo(capture.metadata().lastModified());
        assertThat(copy.getMetadata().createdAt()).isEqualTo(project.getMetadata().createdAt());
        List<String> clips = copy.getTracks().getFirst().getClips().stream().map(AudioClip::getSourceFilePath).toList();
        assertThat(clips).containsExactly(songB.resolve("audio/imported.wav").toString(),
                songB.resolve("audio/takes/t1/vox/segment-000.wav").toString(),
                songB.resolve("samples/kick.wav").toString(), gone, library.resolve("external.wav").toString());
        assertThat(copy.getTracks().get(1).getSoundFontAssignment().soundFontPath())
                .as("a SoundFont the copy holds is the copy's own")
                .isEqualTo(songB.resolve("sf/piano.sf2"));
        assertThat(copy.getTracks().get(2).getSoundFontAssignment().soundFontPath())
                .as("a SoundFont the copy does not hold keeps naming the original's file")
                .isEqualTo(songA.resolve("sf/missing.sf2"));
        assertThat(capture.revision()).isEqualTo(project.getEditRevision());
    }

    @Test
    void theNamedFolderNeverHoldsHalfAProjectItIsPublishedByOneRename() throws Exception {
        SaveAsPlan.Ready plan = ready(planInto(songB));
        Path staging = AtomicFileWriter.stagingSiblingOf(songB, "saving-as");
        SaveTestSupport.Gate publish = mover.holdNext(songB::equals);

        CompletableFuture<SaveAsOutcome> copying = CompletableFuture.supplyAsync(
                () -> manager.publishCopy(plan, SaveAsProgress.NONE, () -> false));
        publish.awaitReached();
        try {
            assertThat(songB).as("while the publish is held, the named folder does not exist").doesNotExist();
            assertThat(files(staging)).as("the staging sibling already holds the whole copy, its head included")
                    .contains("project.daw", "audio/imported.wav", "snapshots/Mix A.daw");
        } finally {
            publish.release();
        }
        assertThat(copying.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isInstanceOf(SaveAsOutcome.Published.class);

        assertThat(staging).doesNotExist();
        assertThat(mover.publishes(songB::equals))
                .as("one rename publishes the folder, with no option that could replace what is there")
                .singleElement()
                .satisfies(move -> assertThat(move.options()).isEmpty());
        List<Path> order = mover.publishes().stream().map(SaveTestSupport.RecordingMover.Publish::target).toList();
        assertThat(order.get(order.size() - 2)).as("project.daw is written last, before the folder's publish")
                .isEqualTo(staging.resolve("project.daw"));
    }

    @Test
    void anExistingEmptyFolderIsWrittenIntoWithProjectDawLast() throws IOException {
        Files.createDirectory(songB);

        SaveAsPlan.Ready plan = ready(planInto(songB));
        SaveAsOutcome outcome = manager.publishCopy(plan, SaveAsProgress.NONE, () -> false);

        assertThat(plan.intoExistingEmptyFolder()).isTrue();
        assertThat(outcome).isInstanceOf(SaveAsOutcome.Published.class);
        List<Path> order = mover.publishes().stream().map(SaveTestSupport.RecordingMover.Publish::target).toList();
        assertThat(order.getLast()).isEqualTo(songB.resolve("project.daw"));
        assertThat(order).as("no staging sibling and no folder publish").allMatch(target -> target.startsWith(songB)
                && !target.equals(songB));
        assertThat(AtomicFileWriter.stagingSiblingOf(songB, "saving-as")).doesNotExist();
        assertThat(files(songB)).contains("project.daw", "audio/imported.wav", "sf/piano.sf2");
    }

    @Test
    void anEmptyFolderThatFilledUpAfterThePlanIsNeverMergedInto() throws IOException {
        Files.createDirectory(songB);
        SaveAsPlan.Ready plan = ready(planInto(songB));
        Files.writeString(songB.resolve("project.daw"), "somebody else's");

        SaveAsOutcome outcome = manager.publishCopy(plan, SaveAsProgress.NONE, () -> false);

        assertThat(outcome).isInstanceOfSatisfying(SaveAsOutcome.Failed.class,
                failed -> assertThat(failed.cause()).isInstanceOf(java.nio.file.FileAlreadyExistsException.class));
        assertThat(files(songB)).containsExactly("project.daw");
        assertThat(songB.resolve("project.daw")).hasContent("somebody else's");
    }

    @Test
    void aCancelBeforeTheHeadDeletesTheStagingCopyAndEveryParentItCreated() throws IOException {
        Path deep = tmp.resolve("new").resolve("parent").resolve("Song B");
        List<String> before = entries(tmp);
        SaveAsPlan.Ready plan = ready(planInto(deep));
        AtomicInteger copied = new AtomicInteger();
        SaveAsProgress progress = recording(new ArrayList<>(), copied);

        SaveAsOutcome outcome = manager.publishCopy(plan, progress, () -> copied.get() > 2);

        assertThat(outcome).isInstanceOf(SaveAsOutcome.Cancelled.class);
        assertThat(mover.publishes()).as("the cancel came after two files were copied").hasSize(2);
        assertThat(tmp.resolve("new")).as("the parents it created are gone").doesNotExist();
        assertThat(entries(tmp)).as("nothing it created is left").isEqualTo(before);
        assertThat(files(songA)).as("the original keeps everything").contains("audio/imported.wav", "project.daw");
    }

    @Test
    void aCancelIntoAnExistingFolderDeletesWhatItWroteAndKeepsTheFolder() throws IOException {
        Files.createDirectory(songB);
        SaveAsPlan.Ready plan = ready(planInto(songB));
        AtomicInteger copied = new AtomicInteger();

        SaveAsOutcome outcome = manager.publishCopy(plan, recording(new ArrayList<>(), copied),
                () -> copied.get() > 3);

        assertThat(outcome).isInstanceOf(SaveAsOutcome.Cancelled.class);
        assertThat(songB).as("the folder the user chose stays, empty").isEmptyDirectory();
    }

    @Test
    void aFailedFileCopyDeletesOnlyWhatTheCopyCreated() throws IOException {
        Files.writeString(tmp.resolve("neighbour.txt"), "mine");
        List<String> before = entries(tmp);
        IOException refused = new IOException("the disk refused the segment");
        mover.failWith(target -> target.getFileName().toString().equals("segment-000.wav"), refused);
        SaveAsPlan.Ready plan = ready(planInto(songB));

        SaveAsOutcome outcome = manager.publishCopy(plan, SaveAsProgress.NONE, () -> false);

        assertThat(outcome).isInstanceOfSatisfying(SaveAsOutcome.Failed.class,
                failed -> assertThat(failed.cause()).isSameAs(refused));
        assertThat(songB).doesNotExist();
        assertThat(entries(tmp)).as("the staging sibling is gone; nothing else changed").isEqualTo(before);
        assertThat(tmp.resolve("neighbour.txt")).hasContent("mine");
    }

    @Test
    void aFailedHeadWriteIntoAnExistingFolderLeavesItEmpty() throws IOException {
        Files.createDirectory(songB);
        IOException refused = new IOException("the head could not be written");
        mover.failWith(target -> target.equals(songB.resolve("project.daw")), refused);
        SaveAsPlan.Ready plan = ready(planInto(songB));

        SaveAsOutcome outcome = manager.publishCopy(plan, SaveAsProgress.NONE, () -> false);

        assertThat(outcome).isInstanceOfSatisfying(SaveAsOutcome.Failed.class,
                failed -> assertThat(failed.cause()).isSameAs(refused));
        assertThat(songB).isEmptyDirectory();
    }

    @Test
    void aFolderAnotherWriterMadeBeforeThePublishFailsTheCopyAndIsLeftAlone() throws IOException {
        AtomicFileWriter.Mover intruding = (source, target, options) -> {
            if (target.equals(songB) && !Files.exists(target)) {
                Files.createDirectory(target);
                Files.writeString(target.resolve("theirs.txt"), "theirs");
            }
            AtomicFileWriter.FILE_SYSTEM_MOVER.move(source, target, options);
        };
        ProjectFolderCopy copy = new ProjectFolderCopy(new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
                intruding));
        SaveAsPlan.Ready plan = ready(copy.plan(manager.captureForSaveAs(project, "Song B"), songB, () -> false));

        SaveAsOutcome outcome = copy.copy(plan, SaveAsProgress.NONE, () -> false);

        assertThat(outcome).isInstanceOfSatisfying(SaveAsOutcome.Failed.class,
                failed -> assertThat(failed.cause()).isInstanceOf(FileSystemException.class));
        assertThat(files(songB)).containsExactly("theirs.txt");
        assertThat(AtomicFileWriter.stagingSiblingOf(songB, "saving-as")).doesNotExist();
    }

    @Test
    void aCopyWhoseSizeDoesNotMatchItsSourceFailsTheSaveAs() throws IOException {
        AtomicFileWriter.Mover truncating = (temp, target, options) -> {
            if (target.getFileName().toString().equals("kick.wav")) {
                Files.write(temp, new byte[10]);
            }
            AtomicFileWriter.FILE_SYSTEM_MOVER.move(temp, target, options);
        };
        ProjectFolderCopy copy = new ProjectFolderCopy(new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
                truncating));
        SaveAsPlan.Ready plan = ready(copy.plan(manager.captureForSaveAs(project, "Song B"), songB, () -> false));

        SaveAsOutcome outcome = copy.copy(plan, SaveAsProgress.NONE, () -> false);

        assertThat(outcome).isInstanceOfSatisfying(SaveAsOutcome.Failed.class,
                failed -> assertThat(failed.cause()).hasMessageContaining("kick.wav").hasMessageContaining("10 bytes"));
        assertThat(songB).doesNotExist();
        assertThat(AtomicFileWriter.stagingSiblingOf(songB, "saving-as")).doesNotExist();
    }

    @Test
    void aStagingFolderACrashLeftIsDeletedBeforeTheCopyStages() throws IOException {
        Path staging = AtomicFileWriter.stagingSiblingOf(songB, "saving-as");
        write(staging.resolve("left-by-a-crash.wav"), 10);

        SaveAsOutcome outcome = manager.publishCopy(
                ready(planInto(songB)),
                SaveAsProgress.NONE, () -> false);

        assertThat(outcome).isInstanceOf(SaveAsOutcome.Published.class);
        assertThat(songB.resolve("left-by-a-crash.wav")).doesNotExist();
        assertThat(staging).doesNotExist();
    }

    @Test
    void aCancelTakenBeforeTheHeadCallbackReturnedIsSeenAndLeavesNothing() throws IOException {
        List<String> before = entries(tmp);
        AtomicBoolean cancel = new AtomicBoolean();
        SaveAsProgress progress = new SaveAsProgress() {
            @Override
            public void copied(long bytesCopied, long bytesToCopy) {
            }

            @Override
            public void writingHead() {
                // The caller's cancel won its compare-and-set just before this callback: it is the copy's decision.
                cancel.set(true);
            }
        };

        SaveAsOutcome outcome = manager.publishCopy(ready(planInto(songB)), progress, cancel::get);

        assertThat(outcome).as("stage-5 review S2: asked once more right after writingHead()")
                .isInstanceOf(SaveAsOutcome.Cancelled.class);
        assertThat(songB).doesNotExist();
        assertThat(AtomicFileWriter.stagingSiblingOf(songB, "saving-as")).as("no staging left").doesNotExist();
        assertThat(entries(tmp)).as("nothing it created is left").isEqualTo(before);
    }

    @Test
    void aCancelAskedWhileTheHeadIsWrittenFinishesThePublish() throws IOException {
        AtomicBoolean cancel = new AtomicBoolean();
        AtomicFileWriter.Mover cancellingAtTheHead = (temp, target, options) -> {
            if (target.getFileName().toString().equals("project.daw")) {
                cancel.set(true);
            }
            AtomicFileWriter.FILE_SYSTEM_MOVER.move(temp, target, options);
        };
        ProjectFolderCopy copy = new ProjectFolderCopy(new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
                cancellingAtTheHead));
        SaveAsPlan.Ready plan = ready(copy.plan(manager.captureForSaveAs(project, "Song B"), songB, () -> false));

        SaveAsOutcome outcome = copy.copy(plan, SaveAsProgress.NONE, cancel::get);

        assertThat(cancel).as("non-vacuity: the cancel was asked while project.daw was written").isTrue();
        assertThat(outcome).as("from the head write on, the copy cannot be cancelled (SKL-S8)")
                .isInstanceOf(SaveAsOutcome.Published.class);
        assertThat(songB.resolve("project.daw")).isRegularFile();
    }

    @Test
    void progressCountsTheFilesBytesThenTheHeadOnce() throws IOException {
        SaveAsPlan.Ready plan = ready(planInto(songB));
        List<String> events = new ArrayList<>();

        manager.publishCopy(plan, recording(events, new AtomicInteger()), () -> false);

        long total = plan.fileBytes();
        assertThat(events.getFirst()).isEqualTo("copied 0/" + total);
        assertThat(events.get(events.size() - 2)).isEqualTo("copied " + total + "/" + total);
        assertThat(events.getLast()).isEqualTo("head");
        assertThat(events).hasSize(plan.files().size() + 2);
    }

    @Test
    void aProgressReportThatThrowsDoesNotStopTheCopy() throws IOException {
        SaveAsProgress broken = new SaveAsProgress() {
            @Override
            public void copied(long bytesCopied, long bytesToCopy) {
                throw new IllegalStateException("the view is gone");
            }

            @Override
            public void writingHead() {
                throw new IllegalStateException("the view is gone");
            }
        };

        SaveAsOutcome outcome = manager.publishCopy(
                ready(planInto(songB)), broken, () -> false);

        assertThat(outcome).isInstanceOf(SaveAsOutcome.Published.class);
    }

    @Test
    void aFolderThatIsTakenIsRefusedWithItsParentAndName() throws IOException {
        write(songB.resolve("somebody.txt"), 1);
        Path file = Files.writeString(tmp.resolve("A File"), "x");
        SaveAsCapture capture = manager.captureForSaveAs(project, "Song B");

        assertThat(manager.planSaveAs(capture, songB, () -> false)).isEqualTo(new SaveAsPlan.TargetTaken(tmp, "Song B"));
        assertThat(manager.planSaveAs(capture, file, () -> false)).isEqualTo(new SaveAsPlan.TargetTaken(tmp, "A File"));
    }

    @Test
    void theProjectsOwnFolderAFolderInsideItAndAnotherNameOfItAreRefused() throws IOException {
        SaveAsCapture capture = manager.captureForSaveAs(project, "Song B");

        assertThat(manager.planSaveAs(capture, songA, () -> false)).isInstanceOf(SaveAsPlan.TargetInsideProject.class);
        assertThat(manager.planSaveAs(capture, songA.resolve("audio").resolve("Song B"), () -> false))
                .isInstanceOf(SaveAsPlan.TargetInsideProject.class);
        assertThat(manager.planSaveAs(capture, songA.resolve("not-yet").resolve("deeper"), () -> false))
                .isInstanceOf(SaveAsPlan.TargetInsideProject.class);
    }

    @Test
    void theProjectsFolderByASymbolicLinkIsRefused() throws IOException {
        SaveAsCapture capture = manager.captureForSaveAs(project, "Song B");
        SaveTestSupport.assumeSymbolicLinks(tmp);
        Path alias = Files.createSymbolicLink(tmp.resolve("Alias of A"), songA);
        assertThat(manager.planSaveAs(capture, alias, () -> false)).as("the folder by another name")
                .isInstanceOf(SaveAsPlan.TargetInsideProject.class);
        assertThat(manager.planSaveAs(capture, alias.resolve("Song B"), () -> false))
                .as("a new folder inside it, by another name")
                .isInstanceOf(SaveAsPlan.TargetInsideProject.class);
    }

    @Test
    void theProjectsFolderByItsShareNameIsRefusedOnWindows() throws IOException {
        Path real = songA.toRealPath();
        String drive = real.getRoot().toString();
        assumeTrue(drive.length() == 3 && drive.charAt(1) == ':', "a drive-letter path (Windows) names the folder");
        Path share = Path.of("\\\\localhost\\" + drive.charAt(0) + "$\\" + real.getRoot().relativize(real));
        assumeTrue(Files.isDirectory(share), "the administrative share " + share + " is reachable");
        SaveAsCapture capture = manager.captureForSaveAs(project, "Song B");

        assertThat(manager.planSaveAs(capture, share.resolve("Song B"), () -> false))
                .as("a new folder inside the project, named through \\\\localhost\\<drive>$: the folder's identity"
                        + " (Files.isSameFile: volume serial and file index) matches where its paths do not")
                .isInstanceOf(SaveAsPlan.TargetInsideProject.class);
    }

    @Test
    void aVolumeWithLessUsableSpaceThanTheCopyIsRefused() throws IOException {
        ProjectFolderCopy copy = new ProjectFolderCopy(new AtomicFileWriter(), volume(1_000L, 4_096L));
        SaveAsCapture capture = manager.captureForSaveAs(project, "Song B");
        SaveAsPlan.Ready ready = ready(manager.planSaveAs(capture, songB, () -> false));
        long needed = clustered(ready, 4_096L);
        assertThat(ready.files()).as("fixture: every file fits one 4 KiB cluster")
                .allMatch(file -> file.bytes() > 0 && file.bytes() <= 4_096L);

        SaveAsPlan plan = copy.plan(capture, songB, () -> false);

        assertThat(plan).as("each file and the head take whole clusters, the head twice more and the reserve")
                .isEqualTo(new SaveAsPlan.NotEnoughSpace(needed, 1_000L, songB.getRoot()));
        assertThat(new ProjectFolderCopy(new AtomicFileWriter(), volume(needed, 4_096L))
                .plan(capture, songB, () -> false)).as("exactly enough is enough").isInstanceOf(SaveAsPlan.Ready.class);
        assertThat(new ProjectFolderCopy(new AtomicFileWriter(), volume(needed - 1, 4_096L))
                .plan(capture, songB, () -> false)).isInstanceOf(SaveAsPlan.NotEnoughSpace.class);
    }

    /**
     * The studio engineer's ruling N1 on stage 3: on a volume with large
     * allocation units (exFAT on a big card: 128 KiB) every small file takes
     * a whole unit, and a copy planned to the byte would fail late.
     */
    @Test
    void theFreeSpaceCheckRoundsEveryFileUpToTheVolumesAllocationUnit() throws IOException {
        long unit = 128L * 1024;
        SaveAsCapture capture = manager.captureForSaveAs(project, "Song B");
        SaveAsPlan.Ready ready = ready(manager.planSaveAs(capture, songB, () -> false));
        long needed = clustered(ready, unit);
        assertThat(needed).as("fixture: every file and the head take one 128 KiB unit")
                .isEqualTo(ready.files().size() * unit + 3 * unit + ProjectFolderCopy.RESERVE_BYTES);

        assertThat(new ProjectFolderCopy(new AtomicFileWriter(), volume(needed - 1, unit))
                .plan(capture, songB, () -> false))
                .isEqualTo(new SaveAsPlan.NotEnoughSpace(needed, needed - 1, songB.getRoot()));
        assertThat(ProjectFolderCopy.allocated(List.of(), 0L, unit)).as("nothing to copy needs the reserve alone")
                .isEqualTo(ProjectFolderCopy.RESERVE_BYTES);
    }

    /** What {@code ready} needs where a unit holds {@code unit} bytes: whole units per file, the head thrice, the reserve. */
    private static long clustered(SaveAsPlan.Ready ready, long unit) {
        long needed = 0L;
        for (SaveAsPlan.CopyItem file : ready.files()) {
            needed += (file.bytes() + unit - 1) / unit * unit;
        }
        long head = ready.bytes() - ready.fileBytes();
        return needed + 3 * ((head + unit - 1) / unit * unit) + ProjectFolderCopy.RESERVE_BYTES;
    }

    /** A volume with {@code usable} bytes free and allocation units of {@code unit} bytes. */
    private static ProjectFolderCopy.UsableSpace volume(long usable, long unit) {
        return new ProjectFolderCopy.UsableSpace() {
            @Override
            public long of(Path existingFolder) {
                return usable;
            }

            @Override
            public long allocationUnit(Path existingFolder) {
                return unit;
            }
        };
    }

    @Test
    void aProjectNeverSavedCopiesNothingButItsProjectFile() throws IOException {
        ProjectManager fresh = new ProjectManager(new CheckpointManager(
                new AutoSaveConfig(Duration.ofHours(1), 10, true)));
        DawProject untitled = new DawProject("Untitled Project", AudioFormat.CD_QUALITY);
        untitled.createAudioTrack("Vox").addClip(new AudioClip("ext", 0, 1,
                library.resolve("external.wav").toString()));

        SaveAsCapture capture = fresh.captureForSaveAs(untitled, "Song C");
        SaveAsPlan.Ready plan = ready(fresh.planSaveAs(capture, tmp.resolve("Song C"), () -> false));
        SaveAsOutcome outcome = fresh.publishCopy(plan, SaveAsProgress.NONE, () -> false);

        assertThat(capture.sourceFolder()).isEmpty();
        assertThat(plan.files()).isEmpty();
        assertThat(outcome).isInstanceOfSatisfying(SaveAsOutcome.Published.class,
                published -> assertThat(published.formerRoots()).isEqualTo(FormerRoots.NONE));
        assertThat(files(tmp.resolve("Song C"))).containsExactly("project.daw");
        assertThat(tmp.resolve("Song C").resolve("audio")).isEmptyDirectory();
        DawProject copy = new ProjectDeserializer().deserialize(
                Files.readString(tmp.resolve("Song C").resolve("project.daw")), tmp.resolve("Song C"));
        assertThat(copy.getTracks().getFirst().getClips().getFirst().getSourceFilePath())
                .as("an external reference stays absolute").isEqualTo(library.resolve("external.wav").toString());
    }

    /**
     * The studio engineer's ruling B1 on stage 3: the project's disk is gone
     * (a drive unplugged, a share offline) — Ctrl+S, the checkpoints and the
     * journal all fail, and Save As to another disk is the only way to keep
     * the session's work. The plan cannot list the source folder: it logs a
     * WARNING, takes no file, counts every clip file under the folder as
     * missing, and the copy publishes {@code project.daw} alone, whose
     * references keep naming the original's files — so they heal when the
     * drive is back.
     */
    @Test
    void aSourceFolderThatCannotBeListedStillPublishesItsProjectFile() throws IOException {
        SaveAsCapture capture = manager.captureForSaveAs(project, "Song B");
        Path away = Files.move(songA, tmp.resolve("Song A, unplugged"));
        List<LogRecord> warnings = new ArrayList<>();
        SaveAsPlan plan;
        try (AutoCloseable recording = recordWarnings(warnings)) {
            plan = manager.planSaveAs(capture, songB, () -> false);
        } catch (Exception notClosed) {
            throw new AssertionError(notClosed);
        }

        SaveAsPlan.Ready ready = ready(plan);
        assertThat(ready.files()).as("nothing can be read from the folder").isEmpty();
        assertThat(ready.missingFiles()).as("imported, the take, kick and gone").isEqualTo(4);
        assertThat(ready.unknownEntries()).isZero();
        assertThat(warnings).extracting(LogRecord::getMessage)
                .anySatisfy(message -> assertThat(message).startsWith("Save As cannot list " + songA));
        SaveAsOutcome outcome = manager.publishCopy(ready, SaveAsProgress.NONE, () -> false);

        assertThat(outcome).isInstanceOfSatisfying(SaveAsOutcome.Published.class,
                published -> assertThat(published.missingFiles()).isEqualTo(4));
        assertThat(files(songB)).containsExactly("project.daw");
        String xml = Files.readString(songB.resolve("project.daw"), StandardCharsets.UTF_8);
        assertThat(xml).as("the references keep the original's absolute paths")
                .contains("source-file=\"" + songA.resolve("audio/imported.wav") + "\"")
                .contains("source-file=\"" + songA.resolve("samples/kick.wav") + "\"")
                .contains("path=\"" + songA.resolve("sf/piano.sf2").toString().replace('\\', '/') + "\"");
        Files.move(away, songA);
    }

    /**
     * The studio engineer's ruling S2 on stage 3: a project whose
     * {@code audio/} is a link to a bigger disk — a junction on Windows, a
     * symbolic link on POSIX — and one whose take folder is such a link are
     * copied whole, at the link's path, the copy's folders real ones; a link
     * that leads back into the project, or above it, is walked never or once.
     */
    @Test
    void aLinkedAudioFolderAndALinkedFolderInsideItAreCopiedWholeAndALoopOnce() throws Exception {
        Path bigDisk = Files.createDirectories(tmp.resolve("Big Disk"));
        Path audioThere = bigDisk.resolve("Song A audio");
        Files.move(songA.resolve("audio"), audioThere);
        assumeTrue(link(songA.resolve("audio"), audioThere), "this file system cannot link a folder here");
        Path drumsThere = Files.createDirectories(bigDisk.resolve("Drums"));
        write(drumsThere.resolve("kit.wav"), 600);
        assumeTrue(link(audioThere.resolve("drums"), drumsThere), "this file system cannot link a folder here");
        assumeTrue(link(audioThere.resolve("loop"), audioThere), "this file system cannot link a folder here");
        assumeTrue(link(audioThere.resolve("up"), tmp), "this file system cannot link a folder here");
        assumeTrue(link(audioThere.resolve("into"), songA.resolve("samples")),
                "this file system cannot link a folder here");

        SaveAsPlan.Ready plan = ready(planInto(songB));
        SaveAsOutcome outcome = manager.publishCopy(plan, SaveAsProgress.NONE, () -> false);

        assertThat(plan.files()).extracting(file -> slashed(file.relativePath())).contains(
                "audio/imported.wav", "audio/takes/t1/vox/segment-000.wav", "audio/unreferenced.wav",
                "audio/drums/kit.wav");
        assertThat(plan.files()).extracting(file -> slashed(file.relativePath()))
                .as("a loop back into the walked folder, a link above the project and one into it are left out")
                .noneMatch(path -> path.startsWith("audio/loop/") || path.startsWith("audio/up/")
                        || path.startsWith("audio/into/"))
                .doesNotContain("audio/takes/t2/vox/segment-000.wav");
        assertThat(plan.missingFiles()).as("audio/gone.wav alone").isEqualTo(1);
        assertThat(outcome).isInstanceOf(SaveAsOutcome.Published.class);
        assertThat(songB.resolve("audio/drums/kit.wav")).hasSameBinaryContentAs(drumsThere.resolve("kit.wav"));
        assertThat(songB.resolve("audio/imported.wav")).hasSameBinaryContentAs(audioThere.resolve("imported.wav"));
        assertThat(Files.isDirectory(songB.resolve("audio"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                .as("the copy's audio/ is a folder of its own, not a link").isTrue();
        assertThat(Files.isSymbolicLink(songB.resolve("audio"))).isFalse();
    }

    /**
     * Corefix review N9 (POSIX; the walk's partial-read branch): one folder
     * under {@code audio/} that cannot be read is logged and left out, its
     * referenced clip counts as missing, and the rest of {@code audio/} is
     * still copied — the walk is not dropped whole by its outer catch.
     */
    @Test
    void anUnreadableFolderUnderAudioIsLeftOutAndTheRestIsCopied() throws Exception {
        assumeTrue(java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "a folder's read permission is a POSIX permission");
        Path locked = songA.resolve("audio/locked");
        write(locked.resolve("kept.wav"), 100);
        project.getTracks().getFirst().addClip(new AudioClip("kept", 14, 1, locked.resolve("kept.wav").toString()));
        Files.setPosixFilePermissions(locked, java.util.EnumSet.noneOf(java.nio.file.attribute.PosixFilePermission.class));
        List<LogRecord> warnings = new ArrayList<>();
        try {
            assumeTrue(!Files.isReadable(locked), "this user reads a folder whatever its permissions (root)");
            SaveAsPlan plan;
            try (AutoCloseable recording = recordWarnings(warnings)) {
                plan = manager.planSaveAs(manager.captureForSaveAs(project, "Song B"), songB, () -> false);
            }

            SaveAsPlan.Ready ready = ready(plan);
            assertThat(ready.files()).extracting(file -> slashed(file.relativePath()))
                    .as("the rest of audio/").contains("audio/imported.wav", "audio/unreferenced.wav",
                            "audio/takes/t1/vox/segment-000.wav")
                    .noneMatch(path -> path.startsWith("audio/locked/"));
            assertThat(ready.missingFiles()).as("audio/gone.wav and the unreadable clip").isEqualTo(2);
            assertThat(warnings).extracting(LogRecord::getMessage).as("the unreadable folder is said")
                    .anySatisfy(message -> assertThat(message).startsWith("Save As cannot read " + locked));
        } finally {
            Files.setPosixFilePermissions(locked, java.util.EnumSet.allOf(
                    java.nio.file.attribute.PosixFilePermission.class));
        }
    }

    /**
     * Corefix review N8: a plain {@code audio/} whose only link leads above
     * the project — to its parent — is left out by the "above the project"
     * guard alone: nothing was walked through a link before it, so no other
     * rule would keep the parent's tree out of the copy.
     */
    @Test
    void aLinkAboveTheProjectIsLeftOutWhenNoLinkWasWalkedBeforeIt() throws Exception {
        assumeTrue(link(songA.resolve("audio").resolve("up"), tmp), "this file system cannot link a folder here");

        SaveAsPlan.Ready plan = ready(planInto(songB));

        assertThat(plan.files()).extracting(file -> slashed(file.relativePath()))
                .as("non-vacuity: the plain audio/ is copied").contains("audio/imported.wav")
                .as("the parent's tree is left out").noneMatch(path -> path.startsWith("audio/up/"));
    }

    /**
     * The studio engineer's ruling S3 on stage 3: an archive-restored project
     * keeps its audio under {@code assets/}, which the copy takes whole — its
     * unreferenced files too, held by the undo history — and a restored
     * archive's header {@code archive.properties} is left out and not
     * counted as unknown (ruling N3).
     */
    @Test
    void anArchiveRestoredProjectsAssetsAreCopiedWholeAndItsArchiveHeaderIsLeftOut() throws IOException {
        write(songA.resolve("assets/0a1b_kick.wav"), 800);
        write(songA.resolve("assets/0c2d_undone.wav"), 900);
        write(songA.resolve("archive.properties"), 120);
        project.getTracks().getFirst().addClip(new AudioClip("restored", 12, 1,
                songA.resolve("assets/0a1b_kick.wav").toString()));

        SaveAsPlan.Ready plan = ready(planInto(songB));
        manager.publishCopy(plan, SaveAsProgress.NONE, () -> false);

        assertThat(plan.files()).extracting(file -> slashed(file.relativePath()))
                .contains("assets/0a1b_kick.wav", "assets/0c2d_undone.wav")
                .doesNotContain("archive.properties");
        assertThat(plan.unknownEntries()).as("notes.txt, as before").isEqualTo(1);
        assertThat(Files.readString(songB.resolve("project.daw"))).contains("source-file=\"assets/0a1b_kick.wav\"");
        assertThat(songB.resolve("assets/0c2d_undone.wav")).exists();
        assertThat(songB.resolve("archive.properties")).doesNotExist();
    }

    /**
     * The studio engineer's ruling S6 on stage 3: the dialog's Cancel works
     * while the plan still reads the source folder — the plan stops at the
     * next folder or file it would read, throws, and has written nothing.
     */
    @Test
    void aCancelDuringThePlanStopsItBeforeItHasWrittenAnything() throws IOException {
        SaveAsCapture capture = manager.captureForSaveAs(project, "Song B");
        AtomicInteger asked = new AtomicInteger();
        List<String> before = entries(tmp);

        assertThatThrownBy(() -> manager.planSaveAs(capture, songB, () -> asked.incrementAndGet() > 3))
                .isInstanceOf(CancellationException.class);

        assertThat(asked.get()).as("asked while it read the source folder, and stopped at once").isEqualTo(4);
        assertThat(entries(tmp)).as("nothing was written").isEqualTo(before);
        assertThat(manager.planSaveAs(capture, songB, () -> false)).as("the capture can be planned again")
                .isInstanceOf(SaveAsPlan.Ready.class);
    }

    /**
     * Stage 3's review N1: {@code project.daw} is written into an existing
     * empty folder without replacing anything — a head another writer put
     * there after the plan's check fails the copy and is left as it was.
     */
    @Test
    void aProjectFileAnotherWriterPutIntoTheChosenFolderIsNeverReplaced() throws IOException {
        Files.createDirectory(songB);
        AtomicFileWriter.Mover intruding = (temp, target, options) -> {
            if (target.equals(songB.resolve("project.daw")) && !Files.exists(target)) {
                Files.writeString(target, "theirs");
            }
            AtomicFileWriter.FILE_SYSTEM_MOVER.move(temp, target, options);
        };
        ProjectFolderCopy copy = new ProjectFolderCopy(new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
                intruding));
        SaveAsPlan.Ready plan = ready(copy.plan(manager.captureForSaveAs(project, "Song B"), songB, () -> false));

        SaveAsOutcome outcome = copy.copy(plan, SaveAsProgress.NONE, () -> false);

        assertThat(outcome).isInstanceOfSatisfying(SaveAsOutcome.Failed.class,
                failed -> assertThat(failed.cause()).isInstanceOf(java.nio.file.FileAlreadyExistsException.class));
        assertThat(files(songB)).containsExactly("project.daw");
        assertThat(songB.resolve("project.daw")).hasContent("theirs");
    }

    @Test
    void anEmptyFolderDeletedAfterThePlanFailsTheCopyNamingThat() throws IOException {
        Files.createDirectory(songB);
        SaveAsPlan.Ready plan = ready(planInto(songB));
        Files.delete(songB);

        SaveAsOutcome outcome = manager.publishCopy(plan, SaveAsProgress.NONE, () -> false);

        assertThat(outcome).isInstanceOfSatisfying(SaveAsOutcome.Failed.class,
                failed -> assertThat(failed.cause()).isInstanceOf(java.nio.file.NoSuchFileException.class)
                        .hasMessageContaining("no longer exists"));
        assertThat(songB).doesNotExist();
    }

    /** Ruling R6: the copy's name is its folder's; a capture taken under another name plans no copy. */
    @Test
    void aCaptureTakenUnderAnotherNamePlansNoCopy() throws IOException {
        SaveAsCapture capture = manager.captureForSaveAs(project, "Song B");

        assertThatThrownBy(() -> manager.planSaveAs(capture, tmp.resolve("Song C"), () -> false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Song B");
        assertThat(tmp.resolve("Song C")).doesNotExist();
    }

    /**
     * The studio engineer's ruling N2 on stage 3: on Windows a folder's
     * identity in {@link FolderFingerprint} is its volume, creation instant
     * and name, which a copy that kept its timestamps shares. Save As asks the
     * file system instead, so a target inside such a twin is not refused as
     * inside the project.
     */
    @Test
    void aTargetInsideATwinOfTheProjectFolderIsNotRefused() throws IOException {
        Path twin = Files.createDirectories(tmp.resolve("Backup").resolve(songA.getFileName().toString()));
        Files.setAttribute(twin, "basic:creationTime", Files.readAttributes(songA,
                java.nio.file.attribute.BasicFileAttributes.class).creationTime());
        SaveAsCapture capture = manager.captureForSaveAs(project, "Song B");

        assertThat(manager.planSaveAs(capture, twin.resolve("Song B"), () -> false))
                .as("a folder that only shares the project's volume, creation time and name")
                .isInstanceOf(SaveAsPlan.Ready.class);
    }

    @Test
    void aCopyItemNeverLeavesTheCopysFolder() {
        Path source = songA.resolve("audio/imported.wav");

        assertThatThrownBy(() -> new SaveAsPlan.CopyItem(source, Path.of("..", "beside.wav"), 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SaveAsPlan.CopyItem(source, Path.of("audio", "..", "..", "x.wav"), 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SaveAsPlan.CopyItem(source, Path.of(""), 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SaveAsPlan.CopyItem(source, source, 1L))
                .as("an absolute path").isInstanceOf(IllegalArgumentException.class);
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            // Rooted but not absolute on Windows: they resolve to a drive's root or its working directory.
            assertThatThrownBy(() -> new SaveAsPlan.CopyItem(source, Path.of("\\x.wav"), 1L))
                    .as("corefix review N7: \\x.wav").isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new SaveAsPlan.CopyItem(source, Path.of("C:x.wav"), 1L))
                    .as("corefix review N7: C:x.wav").isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> FormerRoots.NONE.thenCopiedFrom(songA, java.util.Set.of(Path.of("\\x.wav"))))
                    .as("the held set refuses it too").isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> FormerRoots.NONE.thenCopiedFrom(songA, java.util.Set.of(Path.of("C:x.wav"))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(new SaveAsPlan.CopyItem(source, Path.of("audio", "imported.wav"), 1L).relativePath())
                .isEqualTo(Path.of("audio", "imported.wav"));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Makes {@code link} lead to the folder {@code target}: a junction on
     * Windows, a symbolic link elsewhere. Whether it could be made.
     */
    private static boolean link(Path link, Path target) throws Exception {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            Process command = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return command.waitFor(10, TimeUnit.SECONDS) && command.exitValue() == 0;
        }
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException unsupported) {
            return false;
        }
    }

    /** Records the WARNINGs {@link ProjectFolderCopy} logs until the returned handle is closed. */
    private static AutoCloseable recordWarnings(List<LogRecord> warnings) {
        Logger log = Logger.getLogger(ProjectFolderCopy.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                if (logRecord.getLevel() == Level.WARNING) {
                    synchronized (warnings) {
                        warnings.add(logRecord);
                    }
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        log.addHandler(handler);
        return () -> log.removeHandler(handler);
    }

    /** The plan of a Save As of the open project into {@code target}, captured under the target's name. */
    private SaveAsPlan planInto(Path target) throws IOException {
        return manager.planSaveAs(manager.captureForSaveAs(project, target.getFileName().toString()), target,
                () -> false);
    }

    private static SaveAsPlan.Ready ready(SaveAsPlan plan) {
        assertThat(plan).isInstanceOf(SaveAsPlan.Ready.class);
        return (SaveAsPlan.Ready) plan;
    }

    private static SaveAsProgress recording(List<String> events, AtomicInteger copied) {
        return new SaveAsProgress() {
            @Override
            public void copied(long bytesCopied, long bytesToCopy) {
                events.add("copied " + bytesCopied + "/" + bytesToCopy);
                copied.incrementAndGet();
            }

            @Override
            public void writingHead() {
                events.add("head");
            }
        };
    }

    private static void write(Path file, int bytes) throws IOException {
        Files.createDirectories(file.getParent());
        byte[] content = new byte[bytes];
        for (int i = 0; i < bytes; i++) {
            content[i] = (byte) (i * 31 + file.getFileName().toString().length());
        }
        Files.write(file, content);
    }

    private static void sealedTake(Path take) throws IOException {
        Files.createDirectories(take);
        manifest(take).sealStatus(SealStatus.SEALED).sealedBy(SealedBy.STOP).segments(List.of(
                new SegmentEntry("vox", 0, 0, "vox/segment-000.wav", 1_000L, SegmentState.SEALED)))
                .build().write(take);
    }

    private static void streamingTake(Path take) throws IOException {
        Files.createDirectories(take);
        manifest(take).segments(List.of(new SegmentEntry("vox", 0, 0, "vox/segment-000.wav",
                TakeManifest.FRAMES_STREAMING, SegmentState.STREAMING))).build().write(take);
    }

    private static TakeManifest.Builder manifest(Path take) {
        return TakeManifest.builder()
                .take(take.getFileName().toString())
                .startedAt(Instant.parse("2026-10-28T10:00:00Z"))
                .sampleRate(48_000.0)
                .bitDepth(24)
                .streamChannels(1)
                .startBeat(0.0)
                .startFrame(0L)
                .forceCadenceMillis(5_000L)
                .ringSlots(8)
                .ringFrames(512)
                .addTrack(new TrackEntry("vox", 1, 0L));
    }

    /** Every regular file under {@code folder}, relative, forward-slashed, sorted. */
    private static List<String> files(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(folder)) {
            return walk.filter(Files::isRegularFile).map(file -> slashed(folder.relativize(file)))
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new)).stream().toList();
        }
    }

    /** The names of {@code folder}'s own entries, sorted. */
    private static List<String> entries(Path folder) throws IOException {
        try (Stream<Path> list = Files.list(folder)) {
            return list.map(entry -> entry.getFileName().toString()).sorted().toList();
        }
    }

    /** Every entry under {@code folder} but the lock and the checkpoints, with its last-modified time. */
    private static Map<String, FileTime> listingWithTimes(Path folder) throws IOException {
        Map<String, FileTime> listing = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path entry : walk.toList()) {
                String name = slashed(folder.relativize(entry));
                if (name.equals(ProjectLockManager.LOCK_FILE_NAME) || name.startsWith("checkpoints")) {
                    continue;
                }
                listing.put(name, Files.getLastModifiedTime(entry));
            }
        }
        return Collections.unmodifiableMap(listing);
    }

    private static String slashed(Path relative) {
        return relative.toString().replace('\\', '/');
    }
}
