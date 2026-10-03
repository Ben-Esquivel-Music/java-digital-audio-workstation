package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;
import com.benesquivelmusic.daw.app.ui.vm.ProjectVM;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.archive.ProjectArchiver;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;
import com.benesquivelmusic.daw.sdk.audio.RoundTripLatency;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A take recorded into a saved, clean project is an unsaved change (PR #978
 * review, the finding that publishing a take never marked the project
 * dirty): once the user's Stop has published the take's clip, the one dirty
 * bit is set — on the project, on its {@link ProjectVM} and on the session
 * status strip's model that mirrors it — so a door that would replace the
 * project asks the unsaved-changes prompt, and the Save it offers writes the
 * take's segment into {@code project.daw}. A take still being written when
 * its Stop returned, and that turned out to hold no clip, is no change: its
 * publication leaves the project clean, and the door goes ahead unasked.
 *
 * <p>The recording is real — a {@link TransportController} over a real
 * {@link AudioEngine} on a {@link MockAudioBackend}, streaming a mono 24-bit
 * 96 kHz take into the project's {@code audio/takes} — and the door is a real
 * {@link ProjectLifecycleController} over a real {@link ProjectManager}.
 * Neither Record nor Stop waits for the take's capture thread (PR #978 review
 * 5391920205): the take records, and a stopped take is published, on a later
 * FX turn, which the test waits for. The unsaved-changes prompt is answered
 * on the FX thread, inside its {@code showAndWait}, by pressing the button
 * the test chose, or by hiding it (a cancel). Every FX action and every wait
 * is bounded.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class RecordedTakeUnsavedChangesTest {

    private static final String UNSAVED_CHANGES_TITLE = "Unsaved Changes";
    private static final Duration FX_TURN_BUDGET = Duration.ofSeconds(5);
    private static final long WAIT_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(10);
    /** The hang guard of a wait for a real take's capture thread: its files created, or the take published. */
    private static final long TAKE_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(30);

    @TempDir
    Path workspace;

    private ProjectManager projectManager;
    private DawProject project;
    private Track lead;
    private AudioEngine engine;
    private TransportController controller;
    private ProjectLifecycleController lifecycle;
    private ProjectVM projectVM;
    private ProjectOperationProgress progress;
    /** Holds the take's capture thread for the test that needs its take still being written. */
    private final CaptureThreadHold hold = new CaptureThreadHold();

    /** The text of the prompt button to press; {@code null} hides the prompt, which cancels it. */
    private volatile String promptAnswer;
    private final AtomicInteger prompts = new AtomicInteger();
    private final ListChangeListener<Window> answerUnsavedChangesPrompts = change -> {
        while (change.next()) {
            for (Window window : change.getAddedSubList()) {
                if (window instanceof Stage stage && UNSAVED_CHANGES_TITLE.equals(stage.getTitle())) {
                    prompts.incrementAndGet();
                    Platform.runLater(() -> answer(stage));
                }
            }
        }
    };

    @BeforeEach
    void openASavedCleanProject() throws Exception {
        projectManager = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT));
        Path projectDirectory = projectManager.createProject("Ballad", workspace).projectPath();
        project = new DawProject("Ballad", new AudioFormat(96_000.0, 1, 24, 128));
        project.setMetadata(project.getMetadata().withPath(projectDirectory));
        lead = project.createAudioTrack("Lead");
        lead.setArmed(true);
        project.markClean();

        MockAudioBackend backend = new MockAudioBackend();
        StreamingProvision provision = new StreamingProvision(backend.name(),
                List.of(new BackendStreamRung(backend, DeviceId.defaultFor(backend.name()))));
        AtomicReference<UndoManager> undoManager = new AtomicReference<>(new UndoManager());
        runOnFx(() -> {
            Window.getWindows().addListener(answerUnsavedChangesPrompts);
            engine = new AudioEngine(project.getFormat());
            engine.setStreamingProvision(provision);
            NotificationBar transportToasts = new NotificationBar();
            transportToasts.setAnimated(false);
            Label recIndicator = new Label();
            controller = new TransportController(project, engine, undoManager.get(), transportToasts,
                    new Label(), new Label(), recIndicator, new Button(), new Button(),
                    () -> false,
                    () -> GridResolution.QUARTER,
                    () -> CountInMode.OFF,
                    track -> { },
                    () -> true,
                    () -> RoundTripLatency.UNKNOWN,
                    new StubSessionInputSelection());
            NotificationBar lifecycleToasts = new NotificationBar();
            lifecycleToasts.setAnimated(false);
            FxDispatcher dispatcher = new FxDispatcher();
            progress = new ProjectOperationProgress(dispatcher);
            projectVM = new ProjectVM(project, dispatcher);
            progress.bindDirtyTo(projectVM.dirtyProperty());
            lifecycle = new ProjectLifecycleController(projectManager, new SessionInterchangeController(),
                    lifecycleToasts, progress, new BorderPane(), new VBox(),
                    new ProjectLifecycleController.Deps(() -> project, _ -> { }, undoManager::get,
                            undoManager::set, () -> { }, () -> { }, () -> { }, () -> null, _ -> { }),
                    new ProjectArchiver());
            lifecycle.setTakeBeingWrittenCheck(controller::isTakeBeingWritten);
            lifecycle.setRecordingInFlightCheck(controller::isRecordingInFlight);
        });
    }

    @AfterEach
    void stopEverything() throws Exception {
        try {
            hold.release();
            runOnFx(() -> {
                if (controller.isRecordingInFlight()) {
                    controller.stop();
                }
            });
            awaitOnFx(() -> !controller.isTakeBeingWritten(), "the take's files are closed", TAKE_BUDGET_NANOS);
        } finally {
            runOnFx(() -> {
                Window.getWindows().removeListener(answerUnsavedChangesPrompts);
                if (projectVM != null) {
                    projectVM.dispose(); // its change listener on the project
                }
            });
            engine.stopAudioOutput();
            engine.stop();
            projectManager.abandonProject();
        }
    }

    @Test
    void aStoppedTakeIsAnUnsavedChangeThatThePromptAsksAboutAndItsSaveWritesIntoTheProjectFile()
            throws Exception {
        assertThat(onFx(projectVM::isDirty)).as("fixture: the saved project starts clean").isFalse();
        Path takeDirectory = recordUntilABlockIsOnDisk();

        runOnFx(controller::stop);
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "the stopped take was published", TAKE_BUDGET_NANOS);

        assertThat(onFx(() -> List.copyOf(lead.getClips()))).as("fixture: the Stop published the take")
                .hasSize(1);
        assertThat(onFx(() -> lead.getClips().getFirst().getSourceFilePath()))
                .as("fixture: the clip is the sealed segment")
                .isEqualTo(takeDirectory.resolve(lead.getId()).resolve("segment-000.wav").toString());
        assertThat(onFx(project::isDirty)).as("the published take is an unsaved change").isTrue();
        assertThat(onFx(projectVM::isDirty)).as("the one dirty bit the window title and Save read").isTrue();
        assertThat(onFx(() -> progress.dirtyProperty().get())).as("and the status strip's mirror of it").isTrue();

        promptAnswer = null;
        assertThat(onFx(lifecycle::confirmProjectMayClose)).as("the prompt, dismissed, keeps the project open")
                .isFalse();
        assertThat(prompts).as("a door that replaces the project asks about the take").hasValue(1);

        promptAnswer = "Save";
        assertThat(onFx(lifecycle::confirmProjectMayClose)).as("saved, the door may go ahead").isTrue();
        assertThat(prompts).hasValue(2);
        assertThat(onFx(project::isDirty)).isFalse();
        assertThat(onFx(projectVM::isDirty)).isFalse();
        String saved = Files.readString(project.getMetadata().projectPath().resolve("project.daw"),
                StandardCharsets.UTF_8);
        assertThat(saved).as("the Save the prompt offered wrote the take's segment into project.daw")
                .contains(takeDirectory.getFileName().toString())
                .contains("segment-000.wav");
    }

    @Test
    void aTakeThatHeldNoClipWhenItsWritingFinishedLeavesTheProjectCleanAndTheDoorUnasked() throws Exception {
        AtomicInteger completions = new AtomicInteger();
        // The take's writing finishes after its Stop has returned, and holds
        // no clip: the clips the real completion built are taken off again.
        runOnFx(() -> {
            hold.installOn(controller);
            controller.setTakeCompletionForTest(pipeline -> {
                completions.incrementAndGet();
                pipeline.completeStop();
                pipeline.getRecordedClips().forEach(Track::removeClip);
                return List.<AudioClip>of();
            });
        });
        recordUntilABlockIsOnDisk();
        hold.arm();
        hold.awaitHolding(Duration.ofSeconds(10));

        try {
            runOnFx(controller::stop);
            assertThat(onFx(controller::isTakeBeingWritten)).as("fixture: the take is being written").isTrue();
            assertThat(onFx(project::isDirty)).isFalse();
        } finally {
            hold.release(); // the capture thread seals the take and terminates
        }
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "the take was published", TAKE_BUDGET_NANOS);

        assertThat(completions).as("fixture: the turn that published the take completed it once").hasValue(1);
        assertThat(onFx(() -> List.copyOf(lead.getClips()))).as("fixture: no clip was published").isEmpty();
        assertThat(onFx(project::isDirty)).as("a take that published no clip is no change").isFalse();
        assertThat(onFx(projectVM::isDirty)).isFalse();
        promptAnswer = null;
        assertThat(onFx(lifecycle::confirmProjectMayClose)).as("the door goes ahead").isTrue();
        assertThat(prompts).as("without the unsaved-changes prompt").hasValue(0);
    }

    /**
     * Presses Record and waits, bounded, until the take records and its first
     * segment holds a block; returns the take directory.
     */
    private Path recordUntilABlockIsOnDisk() throws Exception {
        runOnFx(controller::toggleRecord);
        awaitOnFx(() -> !controller.isPreparingTake(), "the take's files were created and capture began",
                TAKE_BUDGET_NANOS);
        Path takeDirectory = onFx(() -> controller.activeTakeDirectory().orElseThrow(
                () -> new AssertionError("fixture: the take streams into a take directory")));
        Path part = takeDirectory.resolve(lead.getId()).resolve("segment-000.wav.part");
        long deadline = System.nanoTime() + WAIT_BUDGET_NANOS;
        while (sizeOf(part) <= 44) {
            assertThat(System.nanoTime() - deadline < 0).as("fixture: a block reached %s within 10 s", part).isTrue();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        return takeDirectory;
    }

    /** Presses the button the test chose on the prompt, or hides it. FX thread, inside its showAndWait. */
    private void answer(Stage prompt) {
        String text = promptAnswer;
        Node found = prompt.getScene().getRoot().lookup(".dialog-pane");
        if (text != null && found instanceof DialogPane pane) {
            for (ButtonType type : pane.getButtonTypes()) {
                if (text.equals(type.getText()) && pane.lookupButton(type) instanceof Button button) {
                    button.fire();
                    return;
                }
            }
            throw new AssertionError("the prompt has no " + text + " button");
        }
        prompt.hide();
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (NoSuchFileException notYet) {
            return -1;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void awaitOnFx(Supplier<Boolean> condition, String what, long budgetNanos) throws Exception {
        long deadline = System.nanoTime() + budgetNanos;
        while (!onFx(condition)) {
            assertThat(System.nanoTime() - deadline < 0)
                    .as("%s, within %d s", what, TimeUnit.NANOSECONDS.toSeconds(budgetNanos)).isTrue();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
    }

    private static <T> T onFx(Supplier<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        runOnFx(() -> result.set(action.get()));
        return result.get();
    }

    private static void runOnFx(Runnable action) throws Exception {
        runOnFx(action, FX_TURN_BUDGET);
    }

    private static void runOnFx(Runnable action, Duration budget) throws Exception {
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
        assertThat(done.await(budget.toMillis(), TimeUnit.MILLISECONDS))
                .as("the FX action returned within %d s", budget.toSeconds()).isTrue();
        if (thrown.get() instanceof Error error) {
            throw error;
        }
        if (thrown.get() != null) {
            throw new AssertionError("the FX action threw", thrown.get());
        }
    }
}
