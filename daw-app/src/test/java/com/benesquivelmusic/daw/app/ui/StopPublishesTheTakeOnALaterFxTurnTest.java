package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.track.Track;
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
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * PR #978 review 5391920205 (F1): Stop never waits on the FX thread for the
 * take's capture thread. It asks the take to be sealed and returns; the take
 * is published on a later FX turn, once that thread has terminated
 * ({@code publishWhenWritten}, then {@code finishWrittenTake}). Meanwhile
 * the status bar says {@link TransportController#TAKE_FINISHING_MESSAGE},
 * and only a take still not published
 * {@link TransportController#TAKE_STILL_WRITING_DELAY} after its Stop is
 * reported as {@link TransportController#TAKE_STILL_WRITING_MESSAGE}.
 *
 * <p>The take is real: a {@link TransportController} over a real
 * {@link AudioEngine} on a {@link MockAudioBackend}, recording into the
 * project's {@code audio/takes}. Its capture thread is held mid-pass by
 * {@link CaptureThreadHold}, released by every test that arms it, in a
 * {@code finally}.
 * The still-writing warning's delay is fired by hand ({@link ManualFxDelay})
 * except in the one test that runs the production delay, a
 * {@code PauseTransition}, against a take held past it. Every FX action is
 * bounded at 5 s; every wait for a real disk at 30 s.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class StopPublishesTheTakeOnALaterFxTurnTest {

    private static final long TAKE_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final String ONE_CLIP_CREATED = "Recording stopped — 1 clip created";

    @TempDir
    Path projectDirectory;

    private DawProject project;
    private Track armed;
    private AudioEngine engine;
    private TransportController controller;
    private Label statusBar;
    private Label recIndicator;
    private final NotificationHistoryService shown = new NotificationHistoryService();
    private final CaptureThreadHold hold = new CaptureThreadHold();

    @BeforeEach
    void armATrack() {
        project = new DawProject("Song A", new AudioFormat(48000, 2, 16, 256));
        project.setMetadata(project.getMetadata().withPath(projectDirectory));
        armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
    }

    @AfterEach
    void releaseAndStopEverything() throws Exception {
        if (controller == null) {
            return;
        }
        try {
            hold.release();
            runOnFx(() -> {
                if (controller.isRecordingInFlight()) {
                    controller.stop();
                }
            });
            awaitOnFx(() -> !controller.isTakeBeingWritten(), "the take's files are closed");
        } finally {
            engine.stopAudioOutput();
            engine.stop();
        }
    }

    @Test
    void stopReturnsWhileTheSealIsHeldAndTheTakeIsPublishedOnceTheCaptureThreadIsReleased() throws Exception {
        ManualFxDelay delay = new ManualFxDelay();
        recordingWithABlockOnDisk(delay);
        hold.arm();
        hold.awaitHolding(Duration.ofSeconds(10));
        int before = shown.size();

        try {
            runOnFx(controller::stop); // returns within the FX bound although the capture thread is held

            assertThat(onFx(statusBar::getText)).isEqualTo(TransportController.TAKE_FINISHING_MESSAGE);
            assertThat(onFx(controller::isTakeBeingWritten)).as("the take is being finished").isTrue();
            assertThat(onFx(controller::isRecordingInFlight)).isFalse();
            assertThat(onFx(() -> project.getTransport().getState())).isEqualTo(TransportState.STOPPED);
            assertThat(onFx(engine::getRecordingCallback)).as("the recording callback is removed").isNull();
            assertThat(onFx(recIndicator::isVisible)).isFalse();
            assertThat(onFx(() -> List.copyOf(armed.getClips()))).as("nothing is published yet").isEmpty();
            assertThat(entriesSince(before)).as("no toast at Stop").isEmpty();
        } finally {
            hold.release(); // the capture thread seals the take and terminates
        }
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "the take was published");

        assertThat(onFx(() -> List.copyOf(armed.getClips()))).as("the take is published").hasSize(1);
        assertThat(entriesSince(before)).extracting(NotificationEntry::level, NotificationEntry::message)
                .containsExactly(tuple(NotificationLevel.SUCCESS, ONE_CLIP_CREATED));
        assertThat(onFx(statusBar::getText)).isEqualTo(ONE_CLIP_CREATED);
    }

    @Test
    void theStillWritingWarningAppearsOnlyOnceTheDelayHasPassedWithTheTakeUnpublished() throws Exception {
        ManualFxDelay delay = new ManualFxDelay();
        recordingWithABlockOnDisk(delay);
        hold.arm();
        hold.awaitHolding(Duration.ofSeconds(10));
        int before = shown.size();

        try {
            runOnFx(controller::stop);
            assertThat(delay.delays()).as("the Stop scheduled the warning after the named delay")
                    .containsExactly(TransportController.TAKE_STILL_WRITING_DELAY);
            assertThat(TransportController.TAKE_STILL_WRITING_DELAY).isEqualTo(Duration.ofSeconds(2));
            assertThat(entriesSince(before)).as("no WARNING before the delay has passed").isEmpty();
            assertThat(onFx(statusBar::getText)).isEqualTo(TransportController.TAKE_FINISHING_MESSAGE);

            runOnFx(delay::fireAll); // the delay passes; the take is still held

            assertThat(entriesSince(before)).extracting(NotificationEntry::level, NotificationEntry::message)
                    .containsExactly(tuple(NotificationLevel.WARNING, TransportController.TAKE_STILL_WRITING_MESSAGE));
            assertThat(onFx(statusBar::getText)).isEqualTo(TransportController.TAKE_STILL_WRITING_MESSAGE);
        } finally {
            hold.release();
        }
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "the take was published");

        assertThat(entriesSince(before)).extracting(NotificationEntry::level, NotificationEntry::message)
                .containsExactly(tuple(NotificationLevel.WARNING, TransportController.TAKE_STILL_WRITING_MESSAGE),
                        tuple(NotificationLevel.SUCCESS, ONE_CLIP_CREATED));
        assertThat(onFx(statusBar::getText)).isEqualTo(ONE_CLIP_CREATED);
    }

    @Test
    void aTakePublishedBeforeTheDelayNeverShowsTheStillWritingWarning() throws Exception {
        ManualFxDelay delay = new ManualFxDelay();
        recordingWithABlockOnDisk(delay);
        int before = shown.size();

        runOnFx(controller::stop);
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "the take was published");
        assertThat(delay.delays()).as("fixture: the Stop scheduled the warning").hasSize(1);

        runOnFx(delay::fireAll); // the delay passes after the take was published

        assertThat(entriesSince(before)).extracting(NotificationEntry::level, NotificationEntry::message)
                .as("the SUCCESS only: a published take is never reported as still being written")
                .containsExactly(tuple(NotificationLevel.SUCCESS, ONE_CLIP_CREATED));
        assertThat(onFx(statusBar::getText)).isEqualTo(ONE_CLIP_CREATED);
    }

    @Test
    void theProductionDelayReportsATakeStillHeldOnlyAfterTheDelay() throws Exception {
        recordingWithABlockOnDisk(null); // the production delay: a PauseTransition on the FX thread
        hold.arm();
        hold.awaitHolding(Duration.ofSeconds(10));
        int before = shown.size();

        try {
            long stopPressed = System.nanoTime();
            runOnFx(controller::stop);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (entriesSince(before).isEmpty()) {
                assertThat(System.nanoTime() - deadline < 0)
                        .as("the production delay reported the held take within 10 s").isTrue();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            }
            long reportedAfter = System.nanoTime() - stopPressed;

            assertThat(entriesSince(before)).extracting(NotificationEntry::level, NotificationEntry::message)
                    .containsExactly(tuple(NotificationLevel.WARNING, TransportController.TAKE_STILL_WRITING_MESSAGE));
            assertThat(reportedAfter).as("not before the delay had passed since the Stop was pressed")
                    .isGreaterThanOrEqualTo(TransportController.TAKE_STILL_WRITING_DELAY.toNanos());
            assertThat(onFx(statusBar::getText)).isEqualTo(TransportController.TAKE_STILL_WRITING_MESSAGE);
            assertThat(onFx(controller::isTakeBeingWritten)).isTrue();
        } finally {
            hold.release();
        }
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "the take was published");
        assertThat(onFx(statusBar::getText)).isEqualTo(ONE_CLIP_CREATED);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Builds the controller — with {@code delay} as its still-writing delay,
     * or the production one when {@code null} — records a take, and waits,
     * bounded, until a recorded block has reached its first segment.
     */
    private void recordingWithABlockOnDisk(ManualFxDelay delay) throws Exception {
        MockAudioBackend backend = new MockAudioBackend();
        StreamingProvision provision = new StreamingProvision(backend.name(),
                List.of(new BackendStreamRung(backend, DeviceId.defaultFor(backend.name()))));
        runOnFx(() -> {
            engine = new AudioEngine(project.getFormat());
            engine.setStreamingProvision(provision);
            NotificationBar notificationBar = new NotificationBar();
            notificationBar.setAnimated(false);
            notificationBar.setHistoryService(shown);
            statusBar = new Label();
            recIndicator = new Label();
            recIndicator.setVisible(false);
            recIndicator.setManaged(false);
            controller = new TransportController(project, engine, new UndoManager(), notificationBar,
                    new Label(), statusBar, recIndicator, new Button(), new Button(),
                    () -> false,
                    () -> GridResolution.QUARTER,
                    () -> CountInMode.OFF,
                    track -> { },
                    () -> true,
                    () -> RoundTripLatency.UNKNOWN);
            if (delay != null) {
                controller.setStillWritingDelayForTest(delay);
            }
            hold.installOn(controller);
        });
        runOnFx(controller::toggleRecord);
        awaitOnFx(() -> !controller.isPreparingTake(), "the take's files were created and capture began");
        Path part = onFx(controller::activeTakeDirectory).orElseThrow()
                .resolve(armed.getId()).resolve("segment-000.wav.part");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (sizeOf(part) <= 44) {
            assertThat(System.nanoTime() - deadline < 0).as("fixture: a block reached %s within 10 s", part)
                    .isTrue();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        Thread inputCheck = onFx(controller::pendingSessionInputCheck).orElseThrow();
        inputCheck.join(TimeUnit.SECONDS.toMillis(5));
        assertThat(inputCheck.isAlive()).as("fixture: the record start's input check finished").isFalse();
        runOnFx(() -> { }); // whatever the check posted has run
    }

    private List<NotificationEntry> entriesSince(int before) {
        List<NotificationEntry> entries = shown.getEntries();
        return List.copyOf(entries.subList(before, entries.size()));
    }

    private static long sizeOf(Path file) throws Exception {
        try {
            return Files.size(file);
        } catch (NoSuchFileException notYet) {
            return -1;
        }
    }

    private static <T> T onFx(Supplier<T> read) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        runOnFx(() -> value.set(read.get()));
        return value.get();
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
        assertThat(done.await(5, TimeUnit.SECONDS)).as("the FX action returned within 5 s").isTrue();
        if (thrown.get() instanceof Error error) {
            throw error;
        }
        if (thrown.get() != null) {
            throw new AssertionError("the FX action threw", thrown.get());
        }
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
