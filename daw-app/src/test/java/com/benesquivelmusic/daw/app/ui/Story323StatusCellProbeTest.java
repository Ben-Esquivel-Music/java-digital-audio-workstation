package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.recording.RecordingPipeline;
import com.benesquivelmusic.daw.core.recording.TakeFinalizationPendingException;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 323 review probe (verification round 3): the status bar the app
 * hands {@link TransportController} is a {@link StatusCellLabel}
 * ({@code main-view.fxml}), which prefixes every text with its cell
 * separator. When a take still being written has finished, the controller
 * takes back the status bar's "still being written" — with the no-clip text,
 * or, when the controller has been retired by a project replacement, with
 * {@link TransportController#TAKE_OF_A_REPLACED_PROJECT_STATUS} — only if the
 * bar still says it, and that comparison must see past the separator.
 * {@code TransportControllerTest} hands the controller a plain {@link Label},
 * so a comparison that does not strip the separator passes there and never
 * matches in the app.
 *
 * <p>The pipeline stop is the controller's package-private seam, as in
 * {@code TransportControllerTest}: the first call really stops the pipeline
 * (its segments sealed and closed), takes its clips back off the track and
 * throws {@link TakeFinalizationPendingException} with a completion the test
 * completes as "the capture thread has terminated"; a second call hands back
 * no clip. FX work runs through {@link Platform#runLater} with bounded
 * latches (5 s per handler, 10 s for the first recorded block and for the
 * deferred stop, as in {@code TransportControllerTest}).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class Story323StatusCellProbeTest {

    @TempDir
    Path projectDirectory;

    private AudioEngine audioEngine;
    private Label statusBarLabel;

    @AfterEach
    void closeEngine() {
        if (audioEngine != null) {
            audioEngine.stopAudioOutput();
            audioEngine.stop();
        }
    }

    /** First call: the take is still being written; the second: the take produced no clip. */
    private static final class StillWritingStop implements TransportController.PipelineStop {
        final CompletableFuture<Void> written = new CompletableFuture<>();
        final List<RecordingPipeline> calls = new CopyOnWriteArrayList<>();
        final CountDownLatch deferredCall = new CountDownLatch(1);

        @Override
        public List<AudioClip> stop(RecordingPipeline pipeline) {
            calls.add(pipeline);
            if (calls.size() == 1) {
                pipeline.stop();
                pipeline.getRecordedClips().forEach(Track::removeClip);
                throw new TakeFinalizationPendingException(pipeline.getTakeDirectory(),
                        Duration.ofSeconds(30), written);
            }
            deferredCall.countDown();
            return List.of();
        }
    }

    @Test
    void aTakeWrittenWithoutAClipTakesBackTheStillWritingStatusInTheAppsStatusCell() throws Exception {
        StillWritingStop stops = new StillWritingStop();
        TransportController controller = stoppedWhileTheTakeIsStillBeingWritten(stops);

        stops.written.complete(null); // the capture thread has terminated
        assertThat(stops.deferredCall.await(10, TimeUnit.SECONDS)).isTrue();
        runOnFx(() -> { }); // one FX turn after the deferred half

        assertThat(controller.isTakeBeingWritten()).as("fixture: the deferred half ran").isFalse();
        assertThat(statusBarLabel.getText())
                .isEqualTo(StatusCellLabel.CELL_SEPARATOR + TransportController.TAKE_WRITTEN_WITHOUT_CLIPS_MESSAGE);
    }

    @Test
    void aRetiredControllerTakesBackTheStillWritingStatusInTheAppsStatusCell() throws Exception {
        StillWritingStop stops = new StillWritingStop();
        TransportController controller = stoppedWhileTheTakeIsStillBeingWritten(stops);

        runOnFx(controller::retire);
        stops.written.complete(null); // the capture thread has terminated
        runOnFx(() -> { }); // one FX turn after the deferred half

        assertThat(stops.calls).as("fixture: a retired controller does not stop the pipeline again").hasSize(1);
        assertThat(controller.isTakeBeingWritten()).as("fixture: the deferred half ran").isFalse();
        assertThat(statusBarLabel.getText())
                .isEqualTo(StatusCellLabel.CELL_SEPARATOR + TransportController.TAKE_OF_A_REPLACED_PROJECT_STATUS);
    }

    /** Records a take with one block on disk, then Stops while its take is still being written. */
    private TransportController stoppedWhileTheTakeIsStillBeingWritten(StillWritingStop stops) throws Exception {
        DawProject project = new DawProject("Song A", new AudioFormat(48000, 2, 16, 256));
        project.setMetadata(project.getMetadata().withPath(projectDirectory));
        Track armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        TransportController controller = newController(project);
        runOnFx(() -> controller.setPipelineStopForTest(stops));
        runOnFx(controller::toggleRecord);
        Path part = controller.activeTakeDirectory().orElseThrow()
                .resolve(armed.getId()).resolve("segment-000.wav.part");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(part) || Files.size(part) <= 44) {
            assertThat(System.nanoTime() - deadline < 0)
                    .as("fixture: a recorded block reached %s within 10 s", part).isTrue();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }

        runOnFx(controller::stop);

        assertThat(controller.isTakeBeingWritten()).as("fixture: the take is still being written").isTrue();
        assertThat(statusBarLabel.getText()).as("fixture: the app's status cell carries its separator")
                .isEqualTo(StatusCellLabel.CELL_SEPARATOR + TransportController.TAKE_STILL_WRITING_MESSAGE);
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
}
