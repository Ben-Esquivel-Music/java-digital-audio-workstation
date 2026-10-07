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
import org.junit.jupiter.api.BeforeEach;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 323 review probe (Copilot 5365941737, HIGH — the app half): a take
 * still being written to disk does not count as a recording in flight, so a
 * Stop over the stopped transport meanwhile is the ordinary double-stop
 * gesture — it returns the playhead to zero (the return-to-start
 * preference) and neither stops nor completes the take again — and the
 * take's clips, published later on the FX thread, stay anchored where the
 * take started.
 *
 * <p>The take is real and so is its Stop: the pipeline's
 * {@code requestStop()} removes the callback, clears the flags, stops the
 * transport (back to the take's anchor) and asks the capture thread to seal
 * the take, and never waits for it (PR #978 review 5391920205). The capture
 * thread is held mid-pass by {@link CaptureThreadHold}, so the take is still
 * being written for as long as the test says; the controller's completion of
 * the take ({@code setTakeCompletionForTest}) is the real
 * {@code completeStop()}, counted. FX work runs through
 * {@link Platform#runLater} with bounded latches; every wait on the test
 * thread is bounded (5 s per handler, 10 s for the first recorded block and
 * for the capture thread to be held, 30 s for the take's start and its
 * publication on a real disk).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class DoubleStopWhileTakeIsWrittenTest {

    private static final double TAKE_ANCHOR_BEATS = 8.0;
    private static final long TAKE_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(30);

    @TempDir
    Path projectDirectory;

    private AudioEngine audioEngine;
    private Label statusBarLabel;
    private final CaptureThreadHold hold = new CaptureThreadHold();

    /** The controller {@link #newController} made for the running test, or {@code null}. */
    private TransportController controller;

    /** The capture-flush threads alive when the running test began. */
    private CaptureFlushThreadWatch flushThreads;

    /**
     * On every path: releases the held capture thread, then stops a take the
     * test left recording or preparing and waits (bounded, 30 s) until nothing
     * is in flight or being written — so neither the capture thread nor an open
     * segment outlives the test or the temporary directory — and only then
     * closes the engine.
     */
    @BeforeEach
    void rememberTheLiveCaptureFlushThreads() {
        flushThreads = CaptureFlushThreadWatch.snapshot();
    }

    @AfterEach
    void stopTheTakeAndCloseEngine() throws Exception {
        AssertionError failure = null;
        TransportController made = controller;
        try {
            hold.release();
            if (made != null) {
                runOnFx(() -> {
                    if (made.isRecordingInFlight()) {
                        made.stop();
                    }
                });
                awaitOnFx(() -> !made.isRecordingInFlight() && !made.isTakeBeingWritten(),
                        "a take the test left running is stopped and published");
            }
            failure = flushThreads.joinNewThreads();
        } catch (AssertionError | Exception e) {
            failure = new AssertionError("a take the test left running could not be ended", e);
        } finally {
            try {
                if (made != null) {
                    runOnFx(made::retire);
                }
            } catch (AssertionError | Exception e) {
                if (failure == null) {
                    failure = new AssertionError("retiring the controller failed", e);
                } else {
                    failure.addSuppressed(e);
                }
            } finally {
                if (audioEngine != null) {
                    audioEngine.stopAudioOutput();
                    audioEngine.stop();
                }
            }
        }
        if (failure != null) {
            throw failure;
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
                    () -> RoundTripLatency.UNKNOWN));
        });
        controller = ref.get();
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
        List<RecordingPipeline> completions = new CopyOnWriteArrayList<>();
        runOnFx(() -> {
            hold.installOn(controller);
            controller.setStillWritingDelayForTest(new ManualFxDelay());
            controller.setTakeCompletionForTest((pipeline, loadedAudio) -> {
                completions.add(pipeline);
                return pipeline.completeStop(loadedAudio);
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

        try {
            runOnFx(controller::stop);
            assertThat(onFx(statusBarLabel::getText)).as("fixture: the take is still being written")
                    .isEqualTo(TransportController.TAKE_FINISHING_MESSAGE);
            assertThat(onFx(controller::isTakeBeingWritten)).as("fixture: the take is FINALIZING").isTrue();
            assertThat(onFx(transport::getState)).isEqualTo(TransportState.STOPPED);
            assertThat(onFx(transport::getPositionInBeats)).as("fixture: the Stop returned to the take's start")
                    .isEqualTo(TAKE_ANCHOR_BEATS);

            runOnFx(controller::stop);

            assertThat(completions).as("the second Stop does not complete the take").isEmpty();
            AtomicBoolean stillPending = new AtomicBoolean();
            runOnFx(() -> stillPending.set(hold.pipeline().isFinalizationPending()));
            assertThat(stillPending).as("nor did anything else: the take is still being finished").isTrue();
            assertThat(onFx(transport::getPositionInBeats))
                    .as("a Stop over the stopped transport while the take is written is the double-stop rewind")
                    .isZero();
            assertThat(onFx(statusBarLabel::getText)).isEqualTo("Returned to start");
        } finally {
            hold.release(); // the capture thread seals the take and terminates
        }
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "the take was published");

        assertThat(completions).as("the take was completed once, by the turn that published it").hasSize(1);
        List<AudioClip> clips = onFx(() -> List.copyOf(armed.getClips()));
        assertThat(clips).singleElement()
                .satisfies(clip -> assertThat(clip.getStartBeat())
                        .as("the rewind moved only the playhead: the clip is anchored where the take started")
                        .isEqualTo(TAKE_ANCHOR_BEATS));
        assertThat(onFx(transport::getPositionInBeats)).as("publishing the take does not move the playhead")
                .isZero();
        assertThat(onFx(statusBarLabel::getText)).isEqualTo("Recording stopped — 1 clip created");
    }
}
