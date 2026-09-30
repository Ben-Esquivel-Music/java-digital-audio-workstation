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
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
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
 * Story 323 review probe (Copilot 5365941737, HIGH — the app half): a take
 * still being written to disk does not count as a recording in flight, so a
 * Stop over the stopped transport meanwhile is the ordinary double-stop
 * gesture — it returns the playhead to zero (the return-to-start
 * preference) and never calls the pipeline — and the take's clips, published
 * later on the FX thread, stay anchored where the take started.
 *
 * <p>The pipeline stop is the controller's package-private seam: the first
 * call throws {@link TakeFinalizationPendingException} with a completion the
 * test completes as "the capture thread has terminated"; the later call is
 * the real {@code RecordingPipeline.stop()} (the core's flush-thread holds
 * are out of this module's reach). That differs from production: the first
 * call never reaches {@code RecordingPipeline.stop()}, so through the
 * pending window the pipeline is still active — its callback installed, the
 * track still flagged recording, its capture thread still running — and the
 * transport is stopped by the controller's own {@code requestStop()}; the
 * later call is then the pipeline's first, complete stop, one-shot side
 * effects included, whose {@code transport.stop()} finds the transport
 * already stopped and leaves the playhead where the rewind put it. A real
 * completing stop repeats none of the one-shot stop (the core's
 * {@code Story323StopFinalizationPendingContractTest} pins that). What this
 * probe pins is the controller's part: the second Stop is the double-stop
 * rewind and never calls the pipeline, and the published clip keeps the
 * take's anchor. FX work runs through
 * {@link Platform#runLater} with bounded latches; every wait on the test
 * thread is bounded (5 s per handler, 10 s for the first recorded block and
 * for the deferred stop, as in {@code TransportControllerTest}).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class Story323PendingTakeDoubleStopProbeTest {

    private static final double TAKE_ANCHOR_BEATS = 8.0;

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

    /** First call: the take is still being written; later calls: the real stop. */
    private static final class StillWritingStop implements TransportController.PipelineStop {
        final CompletableFuture<Void> written = new CompletableFuture<>();
        final List<RecordingPipeline> calls = new CopyOnWriteArrayList<>();
        final CountDownLatch deferredCall = new CountDownLatch(1);

        @Override
        public List<AudioClip> stop(RecordingPipeline pipeline) {
            calls.add(pipeline);
            if (calls.size() == 1) {
                throw new TakeFinalizationPendingException(pipeline.getTakeDirectory(),
                        Duration.ofSeconds(30), written);
            }
            try {
                return pipeline.stop();
            } finally {
                deferredCall.countDown();
            }
        }
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
            statusBarLabel = new Label();
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

    @Test
    void aStopWhileTheTakeIsStillBeingWrittenIsTheDoubleStopRewindAndTheClipKeepsItsAnchor() throws Exception {
        DawProject project = new DawProject("saved", new AudioFormat(48000, 2, 16, 256));
        project.setMetadata(project.getMetadata().withPath(projectDirectory));
        Track armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        Transport transport = project.getTransport();
        transport.setPositionInBeats(TAKE_ANCHOR_BEATS);
        assertThat(transport.isReturnToStartOnStop()).as("fixture: the default preference").isTrue();
        TransportController controller = newController(project);
        StillWritingStop stops = new StillWritingStop();
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
        assertThat(statusBarLabel.getText()).as("fixture: the take is still being written")
                .isEqualTo(TransportController.TAKE_STILL_WRITING_MESSAGE);
        assertThat(transport.getState()).isEqualTo(TransportState.STOPPED);
        assertThat(transport.getPositionInBeats()).as("fixture: the Stop returned to the take's start")
                .isEqualTo(TAKE_ANCHOR_BEATS);

        runOnFx(controller::stop);

        assertThat(stops.calls).as("the second Stop never calls the pipeline").hasSize(1);
        assertThat(transport.getPositionInBeats())
                .as("a Stop over the stopped transport while the take is written is the double-stop rewind")
                .isZero();
        assertThat(statusBarLabel.getText()).isEqualTo("Returned to start");

        stops.written.complete(null); // the capture thread has terminated
        assertThat(stops.deferredCall.await(10, TimeUnit.SECONDS)).isTrue();
        runOnFx(() -> { }); // one FX turn after the deferred publication

        assertThat(stops.calls).hasSize(2);
        assertThat(armed.getClips()).singleElement()
                .satisfies(clip -> assertThat(clip.getStartBeat())
                        .as("the rewind moved only the playhead: the clip is anchored where the take started")
                        .isEqualTo(TAKE_ANCHOR_BEATS));
        assertThat(transport.getPositionInBeats()).as("the deferred stop does not move the playhead").isZero();
        assertThat(statusBarLabel.getText()).isEqualTo("Recording stopped — 1 clip created");
    }
}
