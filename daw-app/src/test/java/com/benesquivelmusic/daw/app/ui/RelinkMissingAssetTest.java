package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.persistence.AssetReference;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.MissingAssetReason;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.SegmentFile;
import com.benesquivelmusic.daw.core.recording.SegmentWriter;
import com.benesquivelmusic.daw.core.recording.TakeDirectories;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.audio.SourceRateMetadata;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 329, "Goals — Tests", relink (decisions D16, D17): a missing file of
 * a reopened project, relinked from the missing-assets report's
 * {@code Locate file…}, plays and draws again in the open session, dirties
 * the project, leaves the report, and the next save writes the new
 * reference forward-slash project-relative under the project folder and
 * absolute outside it. With the rules around it: a take is relinked whole
 * from any one of its segments; a file that does not decode is refused on
 * its row and changes nothing; a file the user must agree to — shorter than
 * its clips, a take found in another track's folder of the take, a plain file
 * for an ADM import's clips — is applied only after one confirmation that
 * names it (decisions D25, D27, D30), and a take's chooser opens in its own
 * folder or the nearest one left; {@code Skip} hides the row without dirtying
 * the project or touching the reference; and a result that arrives after
 * another open does nothing.
 *
 * <p>The report is the real {@link MissingAssetsDialog}, built (not shown)
 * by the controller's own factory; its buttons are fired. The chooser and
 * the confirmation are seams; the relink workers are held until the test
 * releases them, and their results run only when the test pulses the
 * dispatcher.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class RelinkMissingAssetTest {

    private static final String SHORTER = "tone.wav holds 0.25 s; its clips use up to 1.00 s. Use it anyway?";

    @TempDir
    Path workspace;

    private OpenProjectFixture fixture;
    private HeldRelinkWorkers workers = HeldRelinkWorkers.held();
    private final List<MissingAssetsDialog> dialogs = new CopyOnWriteArrayList<>();
    private final List<Optional<Path>> chooserDirectories = new CopyOnWriteArrayList<>();
    private final AtomicReference<Path> pick = new AtomicReference<>();
    private final List<String> confirmations = new CopyOnWriteArrayList<>();
    private final AtomicBoolean confirmAnswer = new AtomicBoolean();
    private final List<String> invalidated = new CopyOnWriteArrayList<>();

    @BeforeEach
    void createTheController() throws Exception {
        fixture = new OpenProjectFixture(workspace);
        runOnFx(() -> {
            ProjectLifecycleController lifecycle = fixture.lifecycle();
            lifecycle.setMissingAssetsPresenter((generation, report) -> {
                MissingAssetsDialog dialog = lifecycle.missingAssetsDialog(generation, report);
                dialogs.add(dialog);
                return dialog;
            });
            AssetRelinkFlow relink = lifecycle.assetRelink();
            relink.setWorkers(workers);
            relink.setFileLocator((row, initialDirectory) -> {
                chooserDirectories.add(initialDirectory);
                return Optional.ofNullable(pick.get());
            });
            relink.setFolderLocator(_ -> Optional.empty());
            relink.setConfirmation(message -> {
                confirmations.add(message);
                return confirmAnswer.get();
            });
            lifecycle.setClipConversionInvalidator(invalidated::add);
        });
    }

    @AfterEach
    void closeTheController() throws Exception {
        workers.release();
        workers.awaitEveryJobEnded();
        fixture.close();
    }

    @Test
    void aMissingFileRelinkedToACopyUnderTheProjectFolderPlaysDrawsDirtiesAndIsSavedProjectRelative()
            throws Exception {
        Path source = OpenProjectFixture.wav(workspace.resolve("library").resolve("tone.wav"), 48_000, 1.0, 220.0);
        OpenProjectFixture.SavedProject saved = fixture.projectOnDisk("Relink Inside", source);
        float[][] imported = saved.clips().getFirst().getAudioData();
        Track savedTrack = saved.saved().getTracks().getFirst();
        int[] importedPixels = onFx(() -> OpenProjectFixture.arrangementPixels(savedTrack, ClipAudioState::unmanaged));
        Path copy = saved.directory().resolve("audio").resolve("found").resolve("tone.wav");
        Files.createDirectories(copy.getParent());
        Files.move(source, copy);
        pick.set(copy);

        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(saved.directory()))).isTrue();
        MissingAssetsDialog dialog = onlyDialog();
        AudioClip clip = fixture.liveClips().getFirst();
        Track liveTrack = onFx(() -> fixture.project.get().getTracks().getFirst());
        assertThat(clip.getAudioData()).as("fixture: the source is missing at open").isNull();
        int[] offlinePixels = onFx(() -> OpenProjectFixture.arrangementPixels(liveTrack, fixture.audio()::stateOf));
        assertThat(Arrays.equals(offlinePixels, importedPixels))
                .as("non-vacuity: the snapshot tells the Offline placeholder from the waveform").isFalse();
        int repaintsAtOpen = fixture.repaints.get();

        runOnFx(() -> button(dialog.rowNodes().getFirst(), "missing-assets-locate-file").fire());

        assertThat(chooserDirectories).as("the chooser opens in the project folder")
                .containsExactly(Optional.of(saved.directory()));
        runOnFx(() -> {
            Node row = dialog.rowNodes().getFirst();
            assertThat(button(row, "missing-assets-locate-file").isDisabled()).as("in flight").isTrue();
            assertThat(button(row, "missing-assets-skip").isDisabled()).as("in flight").isTrue();
            assertThat(texts(row)).contains("Checking…");
        });
        assertThat(clip.getAudioData()).as("the decode runs on a worker, not on the click").isNull();

        runWorkersThenPulse();

        assertThat(Arrays.deepEquals(clip.getAudioData(), imported))
                .as("the clip plays the located file's audio, decoded as an import decodes it").isTrue();
        assertThat(clip.getSourceFilePath()).isEqualTo(copy.toString());
        int[] relinkedPixels = onFx(() -> OpenProjectFixture.arrangementPixels(liveTrack, fixture.audio()::stateOf));
        assertThat(Arrays.equals(relinkedPixels, importedPixels))
                .as("the arrangement draws the waveform the import drew, pixel for pixel").isTrue();
        assertThat(onFx(() -> fixture.project.get().isDirty())).as("a relink is an edit").isTrue();
        assertThat(onFx(dialog::rowNodes)).as("the row is gone").isEmpty();
        assertThat(onFx(() -> fixture.audio().report().isEmpty())).isTrue();
        assertThat(invalidated).as("the clip's cached sample-rate conversions are dropped")
                .containsExactly(clip.getId());
        assertThat(fixture.repaints.get()).as("one repaint").isEqualTo(repaintsAtOpen + 1);
        assertThat(onFx(() -> fixture.undoManager.get().canUndo())).as("not an undo-stack entry").isFalse();

        runOnFx(() -> fixture.lifecycle().onSaveProject());
        SaveOutcomes.awaitIdle(fixture.projectManager); // the save is written off the FX thread (story 331)
        assertThat(Files.readString(saved.directory().resolve("project.daw")))
                .contains("source-file=\"audio/found/tone.wav\"")
                .doesNotContain(copy.toString())
                .doesNotContain(source.toString());
    }

    @Test
    void aMissingFileRelinkedToACopyOutsideTheProjectFolderIsSavedAbsolute() throws Exception {
        Path source = OpenProjectFixture.wav(workspace.resolve("library").resolve("tone.wav"), 48_000, 1.0, 220.0);
        OpenProjectFixture.SavedProject saved = fixture.projectOnDisk("Relink Outside", source);
        Path copy = workspace.resolve("shared-library").resolve("tone.wav");
        Files.createDirectories(copy.getParent());
        Files.move(source, copy);
        assertThat(copy.startsWith(saved.directory())).as("fixture: outside the project").isFalse();
        pick.set(copy);
        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(saved.directory()))).isTrue();
        MissingAssetsDialog dialog = onlyDialog();

        runOnFx(() -> button(dialog.rowNodes().getFirst(), "missing-assets-locate-file").fire());
        runWorkersThenPulse();

        AudioClip clip = fixture.liveClips().getFirst();
        assertThat(clip.getAudioData()).isNotNull();
        runOnFx(() -> fixture.lifecycle().onSaveProject());
        SaveOutcomes.awaitIdle(fixture.projectManager); // the save is written off the FX thread (story 331)
        assertThat(Files.readString(saved.directory().resolve("project.daw")))
                .contains("source-file=\"" + copy + "\"");
    }

    @Test
    void aTakeIsRelinkedWholeFromOnePickedSegmentAndIsSavedProjectRelative() throws Exception {
        Path directory = takeProjectOnDisk();
        // The whole take directory moved: its segments still lie in <stamp>_take-NNNN/<trackId>.
        Path takeDirectory = takeFolder.getParent();
        String take = takeDirectory.getFileName().toString();
        String trackId = takeFolder.getFileName().toString();
        Path movedTake = ProjectManager.audioDirectory(directory).resolve("moved").resolve(take);
        Files.createDirectories(movedTake.getParent());
        Files.move(takeDirectory, movedTake);
        Path moved = movedTake.resolve(trackId);
        List<Path> segments = List.of(moved.resolve("segment-000.wav"), moved.resolve("segment-001.wav"));
        float[][] onDisk = SegmentFile.readFrames(segments);
        pick.set(segments.get(1));
        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(directory))).isTrue();
        MissingAssetsDialog dialog = onlyDialog();
        assertThat(onFx(() -> fixture.audio().report().assets().getFirst().reference()))
                .isInstanceOf(AssetReference.Take.class);

        runOnFx(() -> button(dialog.rowNodes().getFirst(), "missing-assets-locate-file").fire());
        runWorkersThenPulse();

        assertThat(chooserDirectories)
                .as("the take's folders are gone: the chooser opens in their nearest ancestor inside the project")
                .containsExactly(Optional.of(TakeDirectories.takesDirectory(ProjectManager.audioDirectory(directory))));
        assertThat(confirmations).as("found in its own take folder: nothing to ask").isEmpty();
        AudioClip clip = fixture.liveClips().getFirst();
        assertThat(clip.getSourceSegmentPaths()).as("every segment, found by name in the picked file's folder")
                .containsExactly(segments.get(0).toString(), segments.get(1).toString());
        assertThat(Arrays.deepEquals(clip.getAudioData(), onDisk)).isTrue();
        assertThat(clip.getSourceRateMetadata()).isEqualTo(new SourceRateMetadata(44_100, 2, onDisk[0].length));
        assertThat(onFx(dialog::rowNodes)).isEmpty();
        runOnFx(() -> fixture.lifecycle().onSaveProject());
        SaveOutcomes.awaitIdle(fixture.projectManager); // the save is written off the FX thread (story 331)
        String relative = "audio/moved/" + take + "/" + trackId + "/";
        assertThat(Files.readString(directory.resolve("project.daw")))
                .contains("<source-segment path=\"" + relative + "segment-000.wav\"/>")
                .contains("<source-segment path=\"" + relative + "segment-001.wav\"/>");
    }

    @Test
    void aTakePickedInAnotherTracksFolderOfTheTakeIsAppliedOnlyAfterTheUserConfirms() throws Exception {
        Path directory = takeProjectOnDisk();
        // Segment names repeat in every track folder of a take: the user picks the neighbour's.
        Path takeDirectory = takeFolder.getParent();
        Path otherTrack = takeDirectory.resolve("other-track");
        Files.createDirectories(otherTrack);
        for (String name : List.of("segment-000.wav", "segment-001.wav")) {
            Files.move(takeFolder.resolve(name), otherTrack.resolve(name));
        }
        Files.delete(takeFolder);
        List<String> original = List.of(takeFolder.resolve("segment-000.wav").toString(),
                takeFolder.resolve("segment-001.wav").toString());
        pick.set(otherTrack.resolve("segment-001.wav"));
        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(directory))).isTrue();
        MissingAssetsDialog dialog = onlyDialog();
        AudioClip clip = fixture.liveClips().getFirst();
        String question = takeDirectory.getFileName() + "/other-track is not this take's folder "
                + takeDirectory.getFileName() + "/" + takeFolder.getFileName() + ". Use it anyway?";

        confirmAnswer.set(false);
        runOnFx(() -> button(dialog.rowNodes().getFirst(), "missing-assets-locate-file").fire());
        runWorkersThenPulse();

        assertThat(chooserDirectories).as("the chooser opens in the take's directory, the nearest one left")
                .containsExactly(Optional.of(takeDirectory));
        assertThat(confirmations).as("asked, naming both folders").containsExactly(question);
        assertThat(clip.getSourceSegmentPaths()).as("declined: nothing changed").containsExactlyElementsOf(original);
        assertThat(clip.getAudioData()).isNull();
        assertThat(onFx(() -> fixture.project.get().isDirty())).isFalse();
        assertThat(invalidated).isEmpty();
        runOnFx(() -> {
            assertThat(dialog.rowNodes()).as("the row stays").hasSize(1);
            assertThat(button(dialog.rowNodes().getFirst(), "missing-assets-locate-file").isDisabled())
                    .as("idle again").isFalse();
        });

        confirmAnswer.set(true);
        runOnFx(() -> button(dialog.rowNodes().getFirst(), "missing-assets-locate-file").fire());
        runWorkersThenPulse();

        assertThat(confirmations).containsExactly(question, question);
        assertThat(clip.getSourceSegmentPaths()).as("confirmed: relinked to the picked folder")
                .containsExactly(otherTrack.resolve("segment-000.wav").toString(),
                        otherTrack.resolve("segment-001.wav").toString());
        assertThat(clip.getAudioData()).isNotNull();
        assertThat(onFx(dialog::rowNodes)).isEmpty();
    }

    @Test
    void aTakePickedInAnotherTakeOfTheSameTrackIsNamedByBothTakeFolders() throws Exception {
        Path directory = takeProjectOnDisk();
        // Every take of a track has a segment-000.wav: the user picks the same track's folder of a later take.
        String trackId = takeFolder.getFileName().toString();
        Path laterTake = TakeDirectories.allocate(ProjectManager.audioDirectory(directory),
                Instant.parse("2026-10-20T11:00:00Z")).resolve(trackId);
        Files.createDirectories(laterTake);
        for (String name : List.of("segment-000.wav", "segment-001.wav")) {
            Files.move(takeFolder.resolve(name), laterTake.resolve(name));
        }
        Files.delete(takeFolder);
        Files.delete(takeFolder.getParent());
        String thisTake = takeFolder.getParent().getFileName().toString();
        String pickedTake = laterTake.getParent().getFileName().toString();
        assertThat(List.of(thisTake, pickedTake)).as("fixture: two takes of one track")
                .satisfiesExactly(name -> assertThat(name).endsWith("_take-0001"),
                        name -> assertThat(name).endsWith("_take-0002"));
        pick.set(laterTake.resolve("segment-000.wav"));
        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(directory))).isTrue();
        MissingAssetsDialog dialog = onlyDialog();
        AudioClip clip = fixture.liveClips().getFirst();
        List<String> original = List.copyOf(clip.getSourceSegmentPaths());

        confirmAnswer.set(false);
        runOnFx(() -> button(dialog.rowNodes().getFirst(), "missing-assets-locate-file").fire());
        runWorkersThenPulse();

        assertThat(confirmations).as("decision D38: each folder named by its take and its track")
                .containsExactly(pickedTake + "/" + trackId + " is not this take's folder "
                        + thisTake + "/" + trackId + ". Use it anyway?");
        assertThat(clip.getSourceSegmentPaths()).as("declined: nothing changed").containsExactlyElementsOf(original);
        assertThat(clip.getAudioData()).isNull();
    }

    @Test
    void anAdmImportsAssetRelinkedToAFileWithoutAdmMetadataIsAppliedOnlyAfterTheUserConfirms() throws Exception {
        Path master = OpenProjectFixture.wav(workspace.resolve("library").resolve("master.wav"), 48_000, 1.0, 220.0);
        Path directory = admImportOnDisk(master);
        Files.delete(master);
        Path plain = OpenProjectFixture.wav(workspace.resolve("found").resolve("master.wav"), 48_000, 1.0, 220.0);
        pick.set(plain);
        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(directory))).isTrue();
        MissingAssetsDialog dialog = onlyDialog();
        List<AudioClip> clips = fixture.liveClips();
        String question = "master.wav has no ADM metadata; every clip would play the same channels of it"
                + " instead of its own bed or object. Use it anyway?";

        confirmAnswer.set(false);
        runOnFx(() -> button(dialog.rowNodes().getFirst(), "missing-assets-locate-file").fire());
        runWorkersThenPulse();

        assertThat(confirmations).as("asked, naming the file").containsExactly(question);
        for (AudioClip clip : clips) {
            assertThat(clip.getSourceFilePath()).as("declined: nothing changed").isEqualTo(master.toString());
            assertThat(clip.getAudioData()).isNull();
        }
        assertThat(onFx(() -> fixture.project.get().isDirty())).isFalse();

        confirmAnswer.set(true);
        runOnFx(() -> button(dialog.rowNodes().getFirst(), "missing-assets-locate-file").fire());
        runWorkersThenPulse();

        assertThat(confirmations).containsExactly(question, question);
        for (AudioClip clip : clips) {
            assertThat(clip.getSourceFilePath()).as("confirmed: relinked as a plain file").isEqualTo(plain.toString());
            assertThat(clip.getAudioData()).as("every clip plays the whole file").isSameAs(clips.getFirst().getAudioData())
                    .isNotNull();
        }
        assertThat(onFx(dialog::rowNodes)).isEmpty();
    }

    @Test
    void aPickThatDoesNotDecodeIsRefusedOnItsRowAndChangesNothing() throws Exception {
        Path source = OpenProjectFixture.wav(workspace.resolve("library").resolve("tone.wav"), 48_000, 1.0, 220.0);
        OpenProjectFixture.SavedProject saved = fixture.projectOnDisk("Relink Refused", source);
        Files.delete(source);
        Path broken = workspace.resolve("found").resolve("tone.wav");
        Files.createDirectories(broken.getParent());
        Files.write(broken, new byte[]{'n', 'o', 't', ' ', 'a', ' ', 'w', 'a', 'v', 'e'});
        pick.set(broken);
        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(saved.directory()))).isTrue();
        MissingAssetsDialog dialog = onlyDialog();
        AssetReference asset = new AssetReference.SingleFile(source.toString());

        runOnFx(() -> button(dialog.rowNodes().getFirst(), "missing-assets-locate-file").fire());
        runWorkersThenPulse();

        AudioClip clip = fixture.liveClips().getFirst();
        assertThat(clip.getSourceFilePath()).as("not re-pointed").isEqualTo(source.toString());
        assertThat(clip.getAudioData()).isNull();
        assertThat(onFx(() -> fixture.project.get().isDirty())).isFalse();
        assertThat(invalidated).isEmpty();
        assertThat(onFx(() -> fixture.lifecycle().assetRelink()
                .statusOf(fixture.audio().currentGeneration().orElseThrow(), asset)))
                .isInstanceOfSatisfying(AssetRelinkFlow.RowStatus.Refused.class,
                        refused -> assertThat(refused.reason()).isEqualTo(MissingAssetReason.UNREADABLE));
        runOnFx(() -> {
            assertThat(dialog.rowNodes()).as("the row stays").hasSize(1);
            Node row = dialog.rowNodes().getFirst();
            assertThat(texts(row)).anySatisfy(text -> assertThat(text)
                    .startsWith("Not relinked — can't read: " + broken));
            assertThat(button(row, "missing-assets-locate-file").isDisabled()).as("free to try again").isFalse();
        });
    }

    @Test
    void aShorterPickIsAppliedOnlyAfterTheUserConfirms() throws Exception {
        Path source = OpenProjectFixture.wav(workspace.resolve("library").resolve("tone.wav"), 48_000, 1.0, 220.0);
        OpenProjectFixture.SavedProject saved = fixture.projectOnDisk("Relink Shorter", source);
        Files.delete(source);
        Path shorter = OpenProjectFixture.wav(workspace.resolve("found").resolve("tone.wav"), 48_000, 0.25, 220.0);
        pick.set(shorter);
        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(saved.directory()))).isTrue();
        MissingAssetsDialog dialog = onlyDialog();
        AudioClip clip = fixture.liveClips().getFirst();

        confirmAnswer.set(false);
        runOnFx(() -> button(dialog.rowNodes().getFirst(), "missing-assets-locate-file").fire());
        runWorkersThenPulse();

        assertThat(confirmations).as("asked once, naming the file and both seconds").containsExactly(SHORTER);
        assertThat(clip.getSourceFilePath()).as("declined: nothing changed").isEqualTo(source.toString());
        assertThat(clip.getAudioData()).isNull();
        assertThat(onFx(() -> fixture.project.get().isDirty())).isFalse();
        assertThat(invalidated).isEmpty();
        runOnFx(() -> {
            assertThat(dialog.rowNodes()).hasSize(1);
            assertThat(button(dialog.rowNodes().getFirst(), "missing-assets-locate-file").isDisabled()).isFalse();
        });

        confirmAnswer.set(true);
        runOnFx(() -> button(dialog.rowNodes().getFirst(), "missing-assets-locate-file").fire());
        runWorkersThenPulse();

        assertThat(confirmations).hasSize(2);
        assertThat(clip.getSourceFilePath()).as("confirmed: relinked").isEqualTo(shorter.toString());
        assertThat(clip.getAudioData()).isNotNull();
        assertThat(onFx(dialog::rowNodes)).isEmpty();
    }

    @Test
    void skipHidesTheRowWithoutDirtyingTheProjectAndTheNextSaveKeepsTheReference() throws Exception {
        Path source = OpenProjectFixture.wav(workspace.resolve("library").resolve("tone.wav"), 48_000, 1.0, 220.0);
        OpenProjectFixture.SavedProject saved = fixture.projectOnDisk("Skip", source);
        Files.delete(source);
        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(saved.directory()))).isTrue();
        MissingAssetsDialog dialog = onlyDialog();
        assertThat(onFx(() -> fixture.lifecycle().hasUnresolvedAssets())).isTrue();

        runOnFx(() -> button(dialog.rowNodes().getFirst(), "missing-assets-skip").fire());

        assertThat(onFx(dialog::rowNodes)).as("hidden for this session").isEmpty();
        assertThat(onFx(() -> fixture.audio().report().isEmpty())).isTrue();
        assertThat(onFx(() -> fixture.project.get().isDirty())).as("a skip is not an edit").isFalse();
        AudioClip clip = fixture.liveClips().getFirst();
        assertThat(clip.getSourceFilePath()).isEqualTo(source.toString());
        assertThat(onFx(() -> fixture.audio().stateOf(clip))).as("still silent").isEqualTo(ClipAudioState.OFFLINE);
        assertThat(onFx(() -> fixture.lifecycle().hasUnresolvedAssets())).as("nothing left to relink").isFalse();
        assertThat(onFx(() -> fixture.audio().readiness().silentClips()))
                .as("but a render still knows the clip is silent").isEqualTo(1);
        assertThat(workers.started()).as("nothing ran").isZero();

        runOnFx(() -> fixture.lifecycle().onSaveProject());
        SaveOutcomes.awaitIdle(fixture.projectManager); // the save is written off the FX thread (story 331)
        assertThat(Files.readString(saved.directory().resolve("project.daw")))
                .as("the reference is kept: a returning drive or a later relink still works")
                .contains("source-file=\"" + source + "\"");
    }

    @Test
    void aRelinkResultThatArrivesAfterAnotherOpenDoesNothing() throws Exception {
        workers = HeldRelinkWorkers.ignoringInterrupts();
        runOnFx(() -> fixture.lifecycle().assetRelink().setWorkers(workers));
        Path source = OpenProjectFixture.wav(workspace.resolve("library").resolve("tone.wav"), 48_000, 1.0, 220.0);
        OpenProjectFixture.SavedProject first = fixture.projectOnDisk("First", source);
        Path copy = workspace.resolve("found").resolve("tone.wav");
        Files.createDirectories(copy.getParent());
        Files.move(source, copy);
        pick.set(copy);
        Path other = OpenProjectFixture.wav(workspace.resolve("library").resolve("other.wav"), 48_000, 1.0, 330.0);
        OpenProjectFixture.SavedProject second = fixture.projectOnDisk("Second", other);
        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(first.directory()))).isTrue();
        MissingAssetsDialog dialog = onlyDialog();
        AudioClip firstClip = fixture.liveClips().getFirst();
        runOnFx(() -> button(dialog.rowNodes().getFirst(), "missing-assets-locate-file").fire());
        assertThat(workers.started()).isEqualTo(1);

        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(second.directory()))).isTrue();
        DawProject secondProject = onFx(fixture.project::get);
        int repaints = fixture.repaints.get();
        // The held check now runs to the end and posts its result for the first open.
        runWorkersThenPulse();

        assertThat(firstClip.getSourceFilePath()).as("the first open's clip is not re-pointed")
                .isEqualTo(source.toString());
        assertThat(firstClip.getAudioData()).isNull();
        assertThat(onFx(() -> fixture.project.get())).isSameAs(secondProject);
        assertThat(onFx(secondProject::isDirty)).isFalse();
        assertThat(invalidated).isEmpty();
        assertThat(fixture.repaints.get()).as("nothing repainted").isEqualTo(repaints);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /** The take folder of {@link #takeProjectOnDisk()}. */
    private Path takeFolder;

    /**
     * Saves a project holding one recorded take of two 512-frame 44.1 kHz
     * segments, under the project's audio folder, and returns its folder.
     */
    private Path takeProjectOnDisk() throws Exception {
        ProjectManager other = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT));
        Path directory = other.createProject("Take", workspace).projectPath();
        DawProject project = new DawProject("Take", OpenProjectFixture.PROJECT_FORMAT);
        Track track = project.createAudioTrack("Vocals");
        takeFolder = TakeDirectories.allocate(ProjectManager.audioDirectory(directory),
                Instant.parse("2026-10-20T10:00:00Z")).resolve(track.getId());
        List<String> recorded = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            recorded.add(segment(takeFolder, index).toString());
        }
        AudioClip take = new AudioClip("Take 1", 0.0, 0.04, null);
        take.setSourceSegmentPaths(recorded);
        track.addClip(take);
        SaveOutcomes.saved(other, project);
        other.closeProject().join();
        return directory;
    }

    /**
     * Saves a project whose two clips are an ADM BWF import's objects of
     * {@code master} — named {@code Object 1} and {@code Object 2}, as the
     * import names them, each on its own track — and returns its folder.
     */
    private Path admImportOnDisk(Path master) throws Exception {
        ProjectManager other = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT));
        Path directory = other.createProject("Atmos", workspace).projectPath();
        DawProject project = new DawProject("Atmos", OpenProjectFixture.PROJECT_FORMAT);
        for (int object = 1; object <= 2; object++) {
            Track track = project.createAudioTrack("Object " + object);
            track.addClip(new AudioClip("Object " + object, 0.0, 1.0, master.toString()));
        }
        SaveOutcomes.saved(other, project);
        other.closeProject().join();
        return directory;
    }

    /** Writes and seals segment {@code index} of a stereo 44.1 kHz take, as the capture path does. */
    private static Path segment(Path folder, int index) throws Exception {
        int frames = 512;
        float[][] block = new float[2][frames];
        for (int i = 0; i < frames; i++) {
            block[0][i] = (float) (0.1 * (index + 1) * Math.sin(i / 7.0));
            block[1][i] = -block[0][i];
        }
        Files.createDirectories(folder);
        try (SegmentWriter writer = SegmentWriter.open(folder.resolve(String.format("segment-%03d.wav.part", index)),
                44_100.0, 2, 16, Duration.ZERO, System::nanoTime)) {
            writer.append(block, 2, frames);
            return writer.seal();
        }
    }

    private MissingAssetsDialog onlyDialog() {
        assertThat(dialogs).as("the report was presented once").hasSize(1);
        return dialogs.getFirst();
    }

    /** Lets the held relink jobs run, waits for them to end, pulses the dispatcher, then one more FX turn. */
    private void runWorkersThenPulse() throws Exception {
        workers.release();
        workers.awaitEveryJobEnded();
        fixture.pulse();
        // A confirmation is asked on a later turn of the FX event loop.
        runOnFx(() -> { });
    }

    private static Button button(Node row, String styleClass) {
        Node found = row.lookup("." + styleClass);
        assertThat(found).as("the row has a .%s", styleClass).isInstanceOf(Button.class);
        return (Button) found;
    }

    /** Every label text under {@code node}. */
    private static List<String> texts(Node node) {
        List<String> texts = new ArrayList<>();
        for (Node label : node.lookupAll(".label")) {
            if (label instanceof Label text) {
                texts.add(text.getText());
            }
        }
        return texts;
    }
}
