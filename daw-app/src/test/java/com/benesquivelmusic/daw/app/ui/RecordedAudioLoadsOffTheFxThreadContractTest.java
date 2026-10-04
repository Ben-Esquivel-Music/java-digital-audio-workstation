package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.archive.ProjectArchiver;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.Take;
import com.benesquivelmusic.daw.core.recording.TakeGroup;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.audio.RoundTripLatency;
import javafx.application.Platform;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stopped take's audio is read back from its sealed segments on the
 * storage executor <em>before</em> the take is published (Recording
 * Reliability book §4.4), through the real {@link TransportController}:
 * while the read is held no clip of the take is on any track and the take is
 * still being written — Record and the doors that replace the open project
 * are refused; the FX thread reads no file; the clip of a multi-segment take
 * and every lap of a loop-record take arrive on their tracks with their
 * audio; a read that fails for one clip, or as a whole, still publishes the
 * take, reports once and deletes nothing; and a controller retired during
 * the read publishes and attaches nothing.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class RecordedAudioLoadsOffTheFxThreadContractTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 16, 1024);
    /** The data bytes of one block of {@link #FORMAT}. */
    private static final long BLOCK_BYTES = 1024L * 2 * 2;
    /** How the controller's storage task logs a clip it could not read. */
    private static final String UNREADABLE_LOG_PREFIX = "Could not read the recorded audio in ";

    @TempDir
    Path projectDirectory;

    private SteppedTakeFixture fixture;
    private final ControllerLog log = new ControllerLog();

    /**
     * The controller's logger, held here for as long as this class is: the
     * log manager keeps a named logger only weakly, and naming
     * {@code TransportController.class} does not initialise that class, so
     * until the first controller is built nothing else holds the logger. A
     * handler added to one that is collected before then is gone, and the
     * controller logs to a new logger without it.
     */
    private static final Logger CONTROLLER_LOGGER = Logger.getLogger(TransportController.class.getName());

    @BeforeEach
    void listenToTheControllersLog() {
        CONTROLLER_LOGGER.addHandler(log);
    }

    @AfterEach
    void endTheTake() throws Exception {
        log.thrownWhenAClipIsUnreadable = null;
        CONTROLLER_LOGGER.removeHandler(log);
        if (fixture != null) {
            fixture.close();
        }
    }

    /**
     * What the controller logs, from whichever thread. It can be told to
     * throw from inside the storage task: the task logs each clip it could
     * not read, so an {@link Error} thrown from that log call leaves the
     * task as an {@code OutOfMemoryError} of its own would.
     */
    private static final class ControllerLog extends Handler {
        final List<LogRecord> records = new CopyOnWriteArrayList<>();
        volatile Error thrownWhenAClipIsUnreadable;

        @Override
        public void publish(LogRecord record) {
            records.add(record);
            Error injected = thrownWhenAClipIsUnreadable;
            if (injected != null && record.getMessage() != null
                    && record.getMessage().startsWith(UNREADABLE_LOG_PREFIX)) {
                throw injected;
            }
        }

        long unreadableClips() {
            return records.stream().filter(record -> record.getMessage() != null
                    && record.getMessage().startsWith(UNREADABLE_LOG_PREFIX)).count();
        }

        @Override public void flush() { }
        @Override public void close() { }
    }

    private DawProject projectWithADirectory() {
        DawProject project = new DawProject("saved", FORMAT);
        SteppedTakeFixture.giveADirectory(project, projectDirectory);
        return project;
    }

    private static List<Path> segmentsOf(AudioClip clip) {
        return clip.getSourceSegmentPaths().stream().map(Path::of).toList();
    }

    private long shownAt(NotificationLevel level) {
        return fixture.shown.getEntries().stream().filter(entry -> entry.level() == level).count();
    }

    /** The segment-path lists the held read was handed, in publication order. */
    private List<List<String>> listsBeingRead() throws Exception {
        return SteppedTakeFixture.get(() -> fixture.pipeline().recordedSegmentPaths());
    }

    private void assertNothingOfTheTakeIsPublished(DawProject project) throws Exception {
        assertThat(SteppedTakeFixture.get(() -> project.getTracks().stream()
                .allMatch(track -> track.getClips().isEmpty() && track.getTakeGroups().isEmpty())))
                .as("no clip and no take stack of the take is on any track").isTrue();
        assertThat(SteppedTakeFixture.get(() -> fixture.undoManager().canUndo())).as("no undo entry").isFalse();
        assertThat(SteppedTakeFixture.get(project::isDirty)).as("the project is not marked dirty").isFalse();
    }

    @Test
    void aStoppedTakeIsReadOffTheFxThreadBeforeItIsPublishedAndItsClipArrivesWithItsAudio() throws Exception {
        int blocks = 5;
        DawProject project = projectWithADirectory();
        Track armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        project.markClean();
        // Two blocks to a segment: the take rotates twice.
        fixture = new SteppedTakeFixture(project, FORMAT, RoundTripLatency.UNKNOWN,
                pipeline -> pipeline.setSegmentLimits(Duration.ofHours(1), 2 * BLOCK_BYTES));
        // What the clip looked like, and on which thread, the moment the take was published.
        List<Boolean> publishedOnFx = new CopyOnWriteArrayList<>();
        List<Boolean> publishedWithAudio = new CopyOnWriteArrayList<>();
        SteppedTakeFixture.onFx(() -> fixture.undoManager().addHistoryListener(_ -> {
            publishedOnFx.add(Platform.isFxApplicationThread());
            publishedWithAudio.add(!armed.getClips().isEmpty()
                    && armed.getClips().stream().allMatch(clip -> clip.getAudioData() != null));
        }));
        fixture.startRecording();
        fixture.feedRamp(blocks);
        int storageTasksBeforeTheStop = fixture.storage.handedOverOnFx.size();

        fixture.stopAndHoldTheRead();

        assertThat(listsBeingRead()).as("fixture: one clip to read, spanning three segments")
                .hasSize(1).first().satisfies(list -> assertThat(list).hasSize(3));
        assertThat(fixture.storage.handedOverOnFx).as("the FX thread handed one read to the storage executor")
                .hasSize(storageTasksBeforeTheStop + 1);
        assertThat(fixture.storage.handedOverOnFx.getLast()).isTrue();
        assertThat(fixture.storage.queuedCount()).as("which has not run: the FX thread read nothing itself")
                .isEqualTo(1);
        assertThat(SteppedTakeFixture.get(fixture.controller::isTakeBeingWritten))
                .as("the take is being written until it is published, the read included").isTrue();
        assertNothingOfTheTakeIsPublished(project);
        assertThat(fixture.statusText()).isEqualTo(TransportController.TAKE_FINISHING_MESSAGE);
        assertThat(shownAt(NotificationLevel.SUCCESS)).as("nothing says the clips were created yet").isZero();
        assertThat(publishedOnFx).isEmpty();

        fixture.runTheHeldRead();

        assertThat(fixture.storage.ranOnFx.getLast()).as("the segments were read off the FX thread").isFalse();
        assertThat(publishedOnFx).as("the take was published once, on the FX thread").containsExactly(true);
        assertThat(publishedWithAudio).as("its clip already had its audio when the take was published")
                .containsExactly(true);
        AudioClip clip = SteppedTakeFixture.get(() -> armed.getClips()).getFirst();
        float[][] audio = clip.getAudioData();
        assertThat(audio).isNotNull().hasNumberOfRows(2);
        assertThat(audio[0]).hasSize(blocks * 1024);
        TakeCapturedAtTheEngineFormatContractTest.assertHoldsTheRamp(audio, 0);
        assertThat(segmentsOf(clip)).as("the clip names its three segments").hasSize(3).allMatch(Files::isRegularFile);
        assertThat(fixture.undoManager().undoDescription()).isEqualTo("Record Audio");
        assertThat(SteppedTakeFixture.get(project::isDirty)).isTrue();
        assertThat(fixture.statusText()).isEqualTo("Recording stopped — 1 clip created");
        assertThat(shownAt(NotificationLevel.SUCCESS)).isEqualTo(1);
        assertThat(shownAt(NotificationLevel.ERROR)).isZero();
        assertThat(fixture.storage.queuedCount()).as("nothing is read after the publication").isZero();
    }

    @Test
    void recordAndTheProjectDoorsAreRefusedAndTheStillWritingWarningFiresWhileTheTakeIsReadBack() throws Exception {
        DawProject project = projectWithADirectory();
        Track armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        project.markClean();
        fixture = new SteppedTakeFixture(project, FORMAT, RoundTripLatency.UNKNOWN, _ -> { });
        // A project-replacing door, wired as MainController wires it: it
        // asks the current transport controller whether a take is being written.
        NotificationHistoryService doorShown = new NotificationHistoryService();
        AtomicReference<ProjectLifecycleController> door = new AtomicReference<>();
        SteppedTakeFixture.onFx(() -> {
            NotificationBar doorBar = new NotificationBar();
            doorBar.setAnimated(false);
            doorBar.setHistoryService(doorShown);
            ProjectLifecycleController lifecycle = new ProjectLifecycleController(
                    new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT)),
                    new SessionInterchangeController(), doorBar,
                    new ProjectOperationProgress(new FxDispatcher()), new BorderPane(), new VBox(),
                    new ProjectLifecycleController.Deps(() -> project, _ -> { }, fixture::undoManager, _ -> { },
                            () -> { }, () -> { }, () -> { }, () -> null, _ -> { }),
                    new ProjectArchiver());
            lifecycle.setTakeBeingWrittenCheck(fixture.controller::isTakeBeingWritten);
            door.set(lifecycle);
        });
        fixture.startRecording();
        fixture.feedRamp(2);

        fixture.stopAndHoldTheRead();

        SteppedTakeFixture.onFx(fixture.stillWritingDelay::fireAll);
        assertThat(fixture.shown.getEntries().getLast().level()).isEqualTo(NotificationLevel.WARNING);
        assertThat(fixture.shown.getEntries().getLast().message())
                .as("the delayed warning covers a take that is being read back")
                .isEqualTo(TransportController.TAKE_STILL_WRITING_MESSAGE)
                .contains("read back");
        assertThat(fixture.statusText()).isEqualTo(TransportController.TAKE_STILL_WRITING_MESSAGE);

        assertThat(SteppedTakeFixture.get(() -> door.get().confirmProjectMayClose()))
                .as("a door that replaces the open project is refused during the read").isFalse();
        assertThat(doorShown.getEntries()).hasSize(1);
        assertThat(doorShown.getEntries().getFirst().message())
                .isEqualTo(ProjectLifecycleController.PROJECT_CHANGE_WHILE_WRITING_MESSAGE);

        int pipelinesBefore = fixture.pipelines.size();
        SteppedTakeFixture.onFx(fixture.controller::toggleRecord);
        assertThat(fixture.shown.getEntries().getLast().level()).isEqualTo(NotificationLevel.WARNING);
        assertThat(fixture.shown.getEntries().getLast().message())
                .as("Record is refused during the read").isEqualTo(TransportController.RECORD_WHILE_WRITING_MESSAGE);
        assertThat(SteppedTakeFixture.get(fixture.controller::isRecordingInFlight)).isFalse();
        assertThat(fixture.storage.queuedCount()).as("no take directory was asked for: only the read is held")
                .isEqualTo(1);
        assertNothingOfTheTakeIsPublished(project);

        fixture.runTheHeldRead();

        assertThat(fixture.pipelines).as("the refused Record built no pipeline").hasSize(pipelinesBefore);
        AudioClip clip = SteppedTakeFixture.get(() -> armed.getClips()).getFirst();
        assertThat(clip.getAudioData()).as("the take is then published with its audio").isNotNull();
        assertThat(SteppedTakeFixture.get(fixture.controller::isTakeBeingWritten)).isFalse();
    }

    @Test
    void everyLapOfALoopRecordedTakeIsPublishedWithItsAudio() throws Exception {
        int blocksPerLap = 4;
        int blocks = 10;
        DawProject project = projectWithADirectory();
        Track armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        project.markClean();
        double beatsPerBlock = 1024 / 48_000.0 * (project.getTransport().getTempo() / 60.0);
        project.getTransport().setLoopRegion(0.0, blocksPerLap * beatsPerBlock);
        project.getTransport().setLoopEnabled(true);
        fixture = new SteppedTakeFixture(project, FORMAT, RoundTripLatency.UNKNOWN,
                pipeline -> pipeline.setLoopRecord(true));
        fixture.startRecording();
        fixture.feedRamp(blocks);

        fixture.stopAndHoldTheRead();

        assertThat(listsBeingRead()).as("fixture: the transport wrapped, so there is a list to read per lap")
                .hasSizeGreaterThan(1);
        assertNothingOfTheTakeIsPublished(project);

        fixture.runTheHeldRead();

        List<Take> takes = new ArrayList<>();
        for (TakeGroup group : SteppedTakeFixture.get(() -> List.copyOf(armed.getTakeGroups().values()))) {
            takes.addAll(group.takes());
        }
        assertThat(takes).hasSizeGreaterThan(1);
        long frame = 0;
        for (Take take : takes) {
            float[][] audio = take.clip().getAudioData();
            assertThat(audio).as("lap %s has its audio, active or not", take.clip().getName()).isNotNull();
            TakeCapturedAtTheEngineFormatContractTest.assertHoldsTheRamp(audio, frame);
            frame += audio[0].length;
        }
        assertThat(frame).as("the laps hold every frame that was fed, end to end").isEqualTo(blocks * 1024L);
        assertThat(SteppedTakeFixture.get(() -> armed.getClips())).as("the active lap's clip is on the track")
                .isNotEmpty().allMatch(clip -> clip.getAudioData() != null);
        assertThat(shownAt(NotificationLevel.ERROR)).isZero();
    }

    @Test
    void aClipWhoseSegmentCannotBeReadIsPublishedWithoutAudioAndReportedOnce() throws Exception {
        DawProject project = projectWithADirectory();
        Track vox = project.createAudioTrack("Vox");
        vox.setArmed(true);
        Track guitar = project.createAudioTrack("Gtr");
        guitar.setArmed(true);
        fixture = new SteppedTakeFixture(project, FORMAT, RoundTripLatency.UNKNOWN,
                pipeline -> pipeline.setSegmentLimits(Duration.ofHours(1), 2 * BLOCK_BYTES));
        fixture.startRecording();
        fixture.feedRamp(3);
        Path takeDirectory = fixture.pipeline().getTakeDirectory();
        fixture.stopAndHoldTheRead();
        List<String> voxSegments = listsBeingRead().getFirst();
        assertThat(voxSegments).as("fixture: Vox's take spans two segments").hasSize(2);
        Files.delete(Path.of(voxSegments.getLast()));

        fixture.runTheHeldRead();

        AudioClip voxClip = SteppedTakeFixture.get(() -> vox.getClips()).getFirst();
        AudioClip guitarClip = SteppedTakeFixture.get(() -> guitar.getClips()).getFirst();
        assertThat(shownAt(NotificationLevel.ERROR)).as("one ERROR for the take").isEqualTo(1);
        assertThat(shownAt(NotificationLevel.SUCCESS)).as("and no SUCCESS: a clip is silent").isZero();
        NotificationEntry error = fixture.shown.getEntries().getLast();
        assertThat(error.level()).isEqualTo(NotificationLevel.ERROR);
        assertThat(error.message()).as("it names the clip left without audio and the take's folder")
                .startsWith("Recording stopped — 2 clips created; the recorded audio of '" + voxClip.getName() + "' (")
                .endsWith(") could not be loaded for playback; the take's files stay under audio/takes/"
                        + takeDirectory.getFileName())
                .doesNotContain("'" + guitarClip.getName() + "'");
        assertThat(fixture.statusText()).as("the status bar says the same").isEqualTo(error.message());
        assertThat(voxClip.getAudioData()).as("the clip that could not be read has no audio").isNull();
        assertThat(voxClip.getSourceSegmentPaths()).as("and keeps every segment reference").isEqualTo(voxSegments);
        assertThat(Path.of(voxSegments.getFirst())).as("nothing else of the take is deleted").isRegularFile();
        assertThat(guitarClip.getAudioData()).as("the other clip of the take has its audio").isNotNull();
        TakeCapturedAtTheEngineFormatContractTest.assertHoldsTheRamp(guitarClip.getAudioData(), 0);
        assertThat(fixture.undoManager().undoDescription()).as("the take is published all the same")
                .isEqualTo("Record Audio");
        assertThat(SteppedTakeFixture.get(project::isDirty)).isTrue();
    }

    @Test
    void aReadThatFailsAsAWholeStillPublishesTheTakeWithoutAudioAndIsReportedOnce() throws Exception {
        DawProject project = projectWithADirectory();
        Track vox = project.createAudioTrack("Vox");
        vox.setArmed(true);
        Track guitar = project.createAudioTrack("Gtr");
        guitar.setArmed(true);
        fixture = new SteppedTakeFixture(project, FORMAT, RoundTripLatency.UNKNOWN, _ -> { });
        fixture.startRecording();
        fixture.feedRamp(2);
        Path takeDirectory = fixture.pipeline().getTakeDirectory();
        fixture.stopAndHoldTheRead();
        List<List<String>> lists = listsBeingRead();
        assertThat(lists).hasSize(2);
        // Vox's segment is gone, and the storage task's log call for it
        // throws an Error: the task ends with it, as with an OutOfMemoryError.
        Files.delete(Path.of(lists.getFirst().getFirst()));
        log.thrownWhenAClipIsUnreadable = new OutOfMemoryError("injected by the test");

        fixture.runTheHeldRead();

        assertThat(fixture.storage.ranOnFx.getLast()).as("fixture: the read ran, off the FX thread").isFalse();
        AudioClip voxClip = SteppedTakeFixture.get(() -> vox.getClips()).getFirst();
        AudioClip guitarClip = SteppedTakeFixture.get(() -> guitar.getClips()).getFirst();
        assertThat(voxClip.getAudioData()).as("no clip of the take has audio").isNull();
        assertThat(guitarClip.getAudioData()).isNull();
        assertThat(voxClip.getSourceSegmentPaths()).as("each keeps its segment references")
                .isEqualTo(lists.get(0));
        assertThat(guitarClip.getSourceSegmentPaths()).isEqualTo(lists.get(1));
        assertThat(Path.of(lists.get(1).getFirst())).as("nothing is deleted").isRegularFile();
        assertThat(shownAt(NotificationLevel.ERROR)).as("one ERROR for the take").isEqualTo(1);
        assertThat(shownAt(NotificationLevel.SUCCESS)).isZero();
        String reason = "(OutOfMemoryError: injected by the test)";
        assertThat(fixture.shown.getEntries().getLast().message())
                .isEqualTo("Recording stopped — 2 clips created; the recorded audio of '" + voxClip.getName() + "' "
                        + reason + ", '" + guitarClip.getName() + "' " + reason
                        + " could not be loaded for playback; the take's files stay under audio/takes/"
                        + takeDirectory.getFileName());
        assertThat(fixture.statusText()).isEqualTo(fixture.shown.getEntries().getLast().message());
        assertThat(fixture.undoManager().undoDescription()).isEqualTo("Record Audio");
        assertThat(SteppedTakeFixture.get(fixture.controller::isTakeBeingWritten))
                .as("Record is available again").isFalse();
    }

    @Test
    void aControllerRetiredWhileTheTakeIsReadBackReadsNoFurtherAndPublishesAndAttachesNothing() throws Exception {
        DawProject project = projectWithADirectory();
        Track armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        project.markClean();
        fixture = new SteppedTakeFixture(project, FORMAT, RoundTripLatency.UNKNOWN, _ -> { });
        fixture.startRecording();
        fixture.feedRamp(2);
        Path takeDirectory = fixture.pipeline().getTakeDirectory();
        fixture.stopAndHoldTheRead();
        List<String> segments = listsBeingRead().getFirst();
        int shownBefore = fixture.shown.getEntries().size();

        SteppedTakeFixture.onFx(fixture.controller::retire);
        // Were the clip still read, this would be logged as a clip that could not be read.
        Files.delete(Path.of(segments.getFirst()));
        fixture.runTheHeldRead();

        assertThat(fixture.storage.ranOnFx.getLast()).as("fixture: the storage task ran").isFalse();
        assertThat(log.unreadableClips()).as("a retired controller's read stops before its next clip").isZero();
        assertNothingOfTheTakeIsPublished(project);
        assertThat(fixture.shown.getEntries()).as("it shows only where the take's files are")
                .hasSize(shownBefore + 1);
        assertThat(fixture.shown.getEntries().getLast().level()).isEqualTo(NotificationLevel.WARNING);
        assertThat(fixture.shown.getEntries().getLast().message())
                .isEqualTo(TransportController.takeOfAReplacedProjectMessage("saved", takeDirectory));
        assertThat(fixture.statusText()).isEqualTo(TransportController.TAKE_OF_A_REPLACED_PROJECT_STATUS);
    }
}
