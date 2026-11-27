package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.recording.RecordState;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.recording.FinalManifestFailure;
import com.benesquivelmusic.daw.core.recording.RecordingPipeline;
import com.benesquivelmusic.daw.core.recording.TakeManifest;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.RoundTripLatency;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The capture thread's warnings about a take after the person's Stop (story 338, rulings E284 and E299): held
 * while the take is written, then shown once after the take's report and kept in the Notifications history; kept
 * in the history only after a device loss that ended the take (a rescue, or a count-in it cut short) or an error
 * report, which stay on the bar; none for a take of a project that was replaced, and none from a start that
 * failed. The test speaks as the take's capture thread through the warning sink the coordinator installed.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class CaptureWarningsAfterTheStopTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 16, 1024);
    private static final String LATE = "1 capture block(s) were published after the final sweep at frame 2048 and"
            + " are not part of the take; the manifest records the gap";
    private static final String STALE = "a warning of the start that failed";

    @TempDir
    Path projectDirectory;

    private final List<Consumer<String>> sinks = new CopyOnWriteArrayList<>();
    private SteppedTakeFixture fixture;
    private Track armed;
    /** Run on each take's pipeline before its preparation; nothing unless a test sets it. */
    private volatile Consumer<RecordingPipeline> pipelineSetup = _ -> { };

    @BeforeEach
    void buildTheController() throws Exception {
        DawProject project = new DawProject("saved", FORMAT);
        SteppedTakeFixture.giveADirectory(project, projectDirectory);
        armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        project.markClean();
        fixture = new SteppedTakeFixture(project, FORMAT, RoundTripLatency.UNKNOWN,
                pipeline -> pipelineSetup.accept(pipeline));
        SteppedTakeFixture.onFx(() -> fixture.controller.recordCoordinator()
                .setCaptureWarningSinkTapForTest(sinks::add));
    }

    @AfterEach
    void endTheTake() throws Exception {
        if (fixture != null) {
            fixture.close();
        }
    }

    private void recordATake() throws Exception {
        fixture.startRecording();
        fixture.feedRamp(2);
    }

    /** Hands {@code message} to the take's warning sink, as its capture thread does, and lets the FX turn run. */
    private void warnAsTheCaptureThread(String message) throws Exception {
        assertThat(sinks).as("fixture: the take's warning sink was installed").hasSize(1);
        sinks.getFirst().accept(message);
        SteppedTakeFixture.onFx(() -> { });
    }

    private List<NotificationEntry> history() {
        return fixture.shown.getEntries();
    }

    @Test
    void aWarningAfterTheStopIsShownAfterTheTakesReportAndKeptInTheHistoryOnce() throws Exception {
        recordATake();
        fixture.stopAndHoldTheRead();
        warnAsTheCaptureThread(LATE);
        assertThat(history()).as("held while the take is written").noneMatch(entry -> entry.message().equals(LATE));

        fixture.runTheHeldRead();

        List<NotificationEntry> entries = history();
        assertThat(entries.get(entries.size() - 2).message()).isEqualTo("Recording stopped — 1 clip created");
        assertThat(entries.getLast().level()).isEqualTo(NotificationLevel.WARNING);
        assertThat(entries.getLast().message()).isEqualTo(LATE);
        assertThat(entries).as("once").filteredOn(entry -> entry.message().equals(LATE)).hasSize(1);
        assertThat(SteppedTakeFixture.get(() -> fixture.notificationBar().getMessage())).isEqualTo(LATE);
    }

    @Test
    void afterADeviceLossTheWarningIsKeptInTheHistoryOnlyAndTheLossNoticeStaysOnTheBar() throws Exception {
        recordATake();
        SteppedTakeFixture.onFx(() -> fixture.controller.recordCoordinator()
                .setRescuedTakeRegistrationForTest(_ -> { }));
        SteppedTakeFixture.onFx(() -> fixture.controller.recordCoordinator()
                .onDeviceLost(DeviceId.defaultFor(fixture.backend.name())));
        fixture.storage.awaitQueued(1);
        warnAsTheCaptureThread(LATE);

        fixture.runTheHeldRead();

        List<NotificationEntry> entries = history();
        NotificationEntry notice = entries.get(entries.size() - 2);
        assertThat(notice.level()).isEqualTo(NotificationLevel.WARNING);
        assertThat(notice.actionLabel()).contains("Review");
        assertThat(entries.getLast().message()).isEqualTo(LATE);
        assertThat(entries.getLast().actionLabel()).as("kept in the history, with no action").isEmpty();
        assertThat(SteppedTakeFixture.get(() -> fixture.notificationBar().getMessage()))
                .as("the loss's notice stays on the bar").isEqualTo(notice.message());
    }

    /**
     * Ruling E299: a device loss while a take the person stopped is still being written ends nothing; the take
     * gives its report and then the seal's warnings on the bar, as any stopped take.
     */
    @Test
    void aDeviceLossWhileAStoppedTakeIsWrittenLeavesTheWarningOnTheBarAfterTheReport() throws Exception {
        recordATake();
        fixture.stopAndHoldTheRead();
        warnAsTheCaptureThread(LATE);
        assertThat(SteppedTakeFixture.get(() -> fixture.controller.recordCoordinator().getState()))
                .as("premise: the take is being written, not recorded").isEqualTo(RecordState.FINALIZING);
        SteppedTakeFixture.onFx(() -> fixture.controller.recordCoordinator()
                .onDeviceLost(DeviceId.defaultFor(fixture.backend.name())));

        fixture.runTheHeldRead();

        List<NotificationEntry> entries = history();
        assertThat(entries.get(entries.size() - 2).message()).isEqualTo("Recording stopped — 1 clip created");
        assertThat(entries.getLast().message()).isEqualTo(LATE);
        assertThat(entries).as("once").filteredOn(entry -> entry.message().equals(LATE)).hasSize(1);
        assertThat(SteppedTakeFixture.get(() -> fixture.notificationBar().getMessage())).isEqualTo(LATE);
    }

    @Test
    void aTakeOfAProjectThatWasReplacedShowsNone() throws Exception {
        recordATake();
        fixture.stopAndHoldTheRead();
        warnAsTheCaptureThread(LATE);
        SteppedTakeFixture.onFx(fixture.controller::retire);
        warnAsTheCaptureThread("a warning that comes after the replacement");

        fixture.runTheHeldRead();

        assertThat(history()).noneMatch(entry -> entry.message().equals(LATE)
                || entry.message().equals("a warning that comes after the replacement"));
        assertThat(history().getLast().message())
                .startsWith("The take recorded in 'saved' was not added to any project");
    }

    @Test
    void anErrorReportStaysOnTheBarAndTheWarningIsKeptInTheHistoryOnly() throws Exception {
        SteppedTakeFixture.onFx(() -> fixture.controller.setStopSealOutcomeForTest(_ ->
                new TransportController.StopSealOutcome(Optional.empty(), Optional.of(new FinalManifestFailure(
                        new IOException("injected manifest write failure"), TakeManifest.SealedBy.STOP)))));
        recordATake();
        fixture.stopAndHoldTheRead();
        warnAsTheCaptureThread(LATE);

        fixture.runTheHeldRead();

        List<NotificationEntry> entries = history();
        assertThat(entries.get(entries.size() - 2).level()).isEqualTo(NotificationLevel.ERROR);
        assertThat(entries.getLast().message()).isEqualTo(LATE);
        assertThat(SteppedTakeFixture.get(() -> fixture.notificationBar().getCurrentLevel()))
                .as("the error report stays on the bar").isEqualTo(NotificationLevel.ERROR);
    }

    /** Ruling E284: the ERROR of audio that could not be read back stays on the bar too. */
    @Test
    void anUnreadableTakesErrorStaysOnTheBarAndTheWarningIsKeptInTheHistoryOnly() throws Exception {
        recordATake();
        fixture.stopAndHoldTheRead();
        warnAsTheCaptureThread(LATE);
        for (List<String> segments : SteppedTakeFixture.get(() -> fixture.pipeline().recordedSegmentPaths())) {
            for (String segment : segments) {
                Files.delete(Path.of(segment));
            }
        }

        fixture.runTheHeldRead();

        List<NotificationEntry> entries = history();
        NotificationEntry report = entries.get(entries.size() - 2);
        assertThat(report.level()).isEqualTo(NotificationLevel.ERROR);
        assertThat(report.message()).contains("could not be loaded for playback");
        assertThat(entries.getLast().message()).isEqualTo(LATE);
        assertThat(entries.getLast().actionLabel()).as("kept in the history, with no action").isEmpty();
        assertThat(SteppedTakeFixture.get(() -> fixture.notificationBar().getMessage()))
                .as("the error report stays on the bar").isEqualTo(report.message());
    }

    /** Ruling E284: the ERROR of a take whose completion failed stays on the bar, and the warning is given once. */
    @Test
    void aFailedCompletionsErrorStaysOnTheBarAndTheWarningIsKeptInTheHistoryOnce() throws Exception {
        SteppedTakeFixture.onFx(() -> fixture.controller.setTakeCompletionForTest((_, _) -> {
            throw new IllegalStateException("injected completion failure");
        }));
        recordATake();
        fixture.stopAndHoldTheRead();
        warnAsTheCaptureThread(LATE);

        fixture.runTheHeldRead();

        List<NotificationEntry> entries = history();
        NotificationEntry report = entries.get(entries.size() - 2);
        assertThat(report.level()).isEqualTo(NotificationLevel.ERROR);
        assertThat(report.message()).startsWith("Recording could not be finished — injected completion failure");
        assertThat(entries.getLast().message()).isEqualTo(LATE);
        assertThat(entries).as("once").filteredOn(entry -> entry.message().equals(LATE)).hasSize(1);
        assertThat(SteppedTakeFixture.get(() -> fixture.notificationBar().getMessage()))
                .as("the error report stays on the bar").isEqualTo(report.message());
    }

    /**
     * Rulings E284 and E299: a take a device loss cut short during its count-in keeps the start's ERROR on the bar,
     * and a warning its seal gives is kept in the Notifications history only. The warning is handed to the sink in
     * the loss's FX turn, before the loss: its turn is queued before the turn the take's termination posts, which
     * publishes the take, so it runs after the loss and while the take is written, as a seal's warning does.
     */
    @Test
    void aCountInTheDeviceLossCutShortKeepsItsErrorOnTheBarAndTheWarningInTheHistoryOnly() throws Exception {
        fixture.countIn = CountInMode.TWO_BARS;
        fixture.startRecording();
        assertThat(SteppedTakeFixture.get(() -> fixture.controller.recordCoordinator().getState()))
                .as("premise: the take counts in").isEqualTo(RecordState.COUNT_IN);

        SteppedTakeFixture.onFx(() -> {
            sinks.getFirst().accept(LATE);
            fixture.controller.recordCoordinator().onDeviceLost(DeviceId.defaultFor(fixture.backend.name()));
        });
        SteppedTakeFixture.awaitOnFx(() -> !fixture.controller.isTakeBeingWritten(), "the take was published");

        assertThat(history()).filteredOn(entry -> entry.message().equals(LATE)).singleElement().satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING);
            assertThat(entry.actionLabel()).as("kept in the history, with no action").isEmpty();
        });
        assertThat(SteppedTakeFixture.get(() -> fixture.notificationBar().getCurrentLevel()))
                .as("the start's ERROR stays on the bar").isEqualTo(NotificationLevel.ERROR);
        assertThat(SteppedTakeFixture.get(() -> fixture.notificationBar().getMessage()))
                .startsWith("Recording aborted — no take was started: ").contains("lost during the count-in");
    }

    /** Ruling E284: a warning while the take of a project that was replaced still records shows nothing. */
    @Test
    void aWarningWhileTheTakeOfAReplacedProjectStillRecordsShowsNothing() throws Exception {
        recordATake();
        SteppedTakeFixture.onFx(fixture.controller::retire);
        assertThat(SteppedTakeFixture.get(() -> fixture.controller.recordCoordinator().getState()))
                .as("premise: the take still records").isEqualTo(RecordState.RECORDING);

        warnAsTheCaptureThread(LATE);

        assertThat(history()).noneMatch(entry -> entry.message().equals(LATE));
    }

    /**
     * Ruling E284: a warning of a start that failed is held for no take, since that start's take is not the one
     * being written, so the next take's report is not followed by it.
     */
    @Test
    void aWarningOfAFailedStartIsNotGivenAfterTheNextTakesReport() throws Exception {
        pipelineSetup = pipeline -> {
            pipelineSetup = _ -> { };
            try {
                // A file where the track's directory belongs: the take's files cannot be created.
                Files.createFile(pipeline.getTakeDirectory().resolve(armed.getId()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
        fixture.pressRecord();
        fixture.storage.runNext();
        SteppedTakeFixture.awaitOnFx(() -> !fixture.controller.isPreparingTake(), "the start failed");
        assertThat(SteppedTakeFixture.get(() -> fixture.controller.activeTakeDirectory()))
                .as("premise: no take records").isEmpty();
        sinks.getFirst().accept(STALE);
        SteppedTakeFixture.onFx(() -> { });
        fixture.storage.runNext();
        SteppedTakeFixture.awaitOnFx(() -> !fixture.controller.isTakeBeingWritten(),
                "the failed start's files and stream were cleaned up");

        recordATake();
        fixture.stopAndAwaitThePublication();

        assertThat(history()).noneMatch(entry -> entry.message().equals(STALE));
        assertThat(SteppedTakeFixture.get(() -> fixture.notificationBar().getMessage()))
                .isEqualTo("Recording stopped — 1 clip created");
    }
}
