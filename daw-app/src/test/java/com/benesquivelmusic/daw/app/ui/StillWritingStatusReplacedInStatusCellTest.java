package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.recording.RecordingPipeline;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;
import com.benesquivelmusic.daw.sdk.audio.RoundTripLatency;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 323 review probe (verification round 3): the status bar the app
 * hands {@link TransportController} is a {@link StatusCellLabel}
 * ({@code main-view.fxml}), which prefixes every text with its cell
 * separator. When a stopped take has been written, the controller takes back
 * the status bar's {@link TransportController#TAKE_FINISHING_MESSAGE} or
 * {@link TransportController#TAKE_STILL_WRITING_MESSAGE} — with the no-clip
 * text, or, when the controller has been retired by a project replacement,
 * with {@link TransportController#TAKE_OF_A_REPLACED_PROJECT_STATUS} — only
 * if the bar still says one of them, and that comparison must see past the
 * separator. {@code TransportControllerTest} hands the controller a plain
 * {@link Label}, so a comparison that does not strip the separator passes
 * there and never matches in the app.
 *
 * <p>The take and its Stop are real; its capture thread is held mid-pass by
 * {@link CaptureThreadHold}, so the take is still being written until the
 * test releases it, and the delayed still-writing warning is fired by hand
 * ({@link ManualFxDelay}). The controller's completion of the take
 * ({@code setTakeCompletionForTest}) is the real {@code completeStop()},
 * whose clips are taken back off the track again, so the take holds no clip.
 * FX work runs through {@link Platform#runLater} with bounded latches (5 s
 * per handler, 10 s for the first recorded block and for the capture thread
 * to be held, 30 s for the take's start and its publication).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class StillWritingStatusReplacedInStatusCellTest {

    private static final long TAKE_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(30);

    @TempDir
    Path projectDirectory;

    private AudioEngine audioEngine;
    private Label statusBarLabel;
    private final CaptureThreadHold hold = new CaptureThreadHold();
    private final ManualFxDelay stillWritingDelay = new ManualFxDelay();
    private final List<RecordingPipeline> completions = new CopyOnWriteArrayList<>();

    @AfterEach
    void closeEngine() {
        hold.release();
        if (audioEngine != null) {
            audioEngine.stopAudioOutput();
            audioEngine.stop();
        }
    }

    @Test
    void aTakeWrittenWithoutAClipTakesBackTheStillWritingStatusInTheAppsStatusCell() throws Exception {
        TransportController controller = stoppedWhileTheTakeIsStillBeingWritten();
        runOnFx(stillWritingDelay::fireAll);
        assertThat(onFx(statusBarLabel::getText)).as("fixture: the app's status cell carries its separator")
                .isEqualTo(StatusCellLabel.CELL_SEPARATOR + TransportController.TAKE_STILL_WRITING_MESSAGE);

        hold.release(); // the capture thread seals the take and terminates
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "fixture: the take was published");

        assertThat(completions).as("fixture: the take was completed").hasSize(1);
        assertThat(onFx(statusBarLabel::getText))
                .isEqualTo(StatusCellLabel.CELL_SEPARATOR + TransportController.TAKE_WRITTEN_WITHOUT_CLIPS_MESSAGE);
    }

    @Test
    void aRetiredControllerTakesBackTheFinishingStatusInTheAppsStatusCell() throws Exception {
        TransportController controller = stoppedWhileTheTakeIsStillBeingWritten();

        runOnFx(controller::retire);
        hold.release(); // the capture thread seals the take and terminates
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "fixture: the publishing turn ran");

        assertThat(completions).as("fixture: a retired controller does not complete the take").isEmpty();
        assertThat(onFx(statusBarLabel::getText))
                .isEqualTo(StatusCellLabel.CELL_SEPARATOR + TransportController.TAKE_OF_A_REPLACED_PROJECT_STATUS);
    }

    /** Records a take with one block on disk, holds its capture thread, then Stops while it is still being written. */
    private TransportController stoppedWhileTheTakeIsStillBeingWritten() throws Exception {
        DawProject project = new DawProject("Song A", new AudioFormat(48000, 2, 16, 256));
        project.setMetadata(project.getMetadata().withPath(projectDirectory));
        Track armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        TransportController controller = newController(project);
        runOnFx(() -> {
            hold.installOn(controller);
            controller.setStillWritingDelayForTest(stillWritingDelay);
            controller.setTakeCompletionForTest(pipeline -> {
                completions.add(pipeline);
                pipeline.completeStop();
                pipeline.getRecordedClips().forEach(Track::removeClip);
                return List.<AudioClip>of();
            });
        });
        runOnFx(controller::toggleRecord);
        awaitOnFx(() -> !controller.isPreparingTake(), "fixture: the take's files were created and capture began");
        Path part = onFx(controller::activeTakeDirectory).orElseThrow()
                .resolve(armed.getId()).resolve("segment-000.wav.part");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(part) || Files.size(part) <= 44) {
            assertThat(System.nanoTime() - deadline < 0)
                    .as("fixture: a recorded block reached %s within 10 s", part).isTrue();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        hold.arm();
        hold.awaitHolding(Duration.ofSeconds(10));

        runOnFx(controller::stop);

        assertThat(onFx(controller::isTakeBeingWritten)).as("fixture: the take is still being written").isTrue();
        assertThat(onFx(statusBarLabel::getText)).as("fixture: the app's status cell carries its separator")
                .isEqualTo(StatusCellLabel.CELL_SEPARATOR + TransportController.TAKE_FINISHING_MESSAGE);
        return controller;
    }

    private TransportController newController(DawProject project) throws Exception {
        MockAudioBackend backend = new MockAudioBackend();
        StreamingProvision provision = new StreamingProvision(backend.name(),
                List.of(new BackendStreamRung(backend, DeviceId.defaultFor(backend.name()))));
        AtomicReference<TransportController> ref = new AtomicReference<>();
        runOnFx(() -> {
            AudioEngine engine = new AudioEngine(project.getFormat());
            audioEngine = engine;
            engine.setStreamingProvision(provision);
            NotificationBar notificationBar = new NotificationBar();
            notificationBar.setAnimated(false);
            statusBarLabel = new StatusCellLabel();
            Label recIndicator = new Label();
            recIndicator.setVisible(false);
            recIndicator.setManaged(false);
            ref.set(new TransportController(project, engine, new UndoManager(), notificationBar,
                    new Label(), statusBarLabel, recIndicator, new Button(), new Button(),
                    () -> false,
                    () -> GridResolution.QUARTER,
                    () -> CountInMode.OFF,
                    track -> { },
                    () -> true,
                    () -> RoundTripLatency.UNKNOWN,
                    new StubSessionInputSelection()));
        });
        return ref.get();
    }

    /** Runs {@code action} on the FX thread and rethrows whatever it threw; bounded at 5 s. */
    private static void runOnFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                done.countDown();
            }
        });
        assertThat(done.await(5, TimeUnit.SECONDS)).as("the FX handler returned within 5 s").isTrue();
        assertThat(thrown.get()).as("the FX handler threw nothing").isNull();
    }

    private static <T> T onFx(Supplier<T> read) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        runOnFx(() -> value.set(read.get()));
        return value.get();
    }

    /** Waits, at most 30 s, polling on the FX thread, until {@code condition} holds there. */
    private static void awaitOnFx(Supplier<Boolean> condition, String what) throws Exception {
        long deadline = System.nanoTime() + TAKE_BUDGET_NANOS;
        while (!onFx(condition)) {
            assertThat(System.nanoTime() - deadline < 0).as("%s, within 30 s", what).isTrue();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
    }
}
