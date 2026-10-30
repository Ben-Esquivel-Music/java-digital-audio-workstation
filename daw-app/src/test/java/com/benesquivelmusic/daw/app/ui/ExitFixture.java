package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.status.ProjectJournalCoordinator;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointEvent;
import com.benesquivelmusic.daw.core.session.SessionManager;
import com.benesquivelmusic.daw.core.session.WorkingSession;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;

/**
 * The close protocol as the application will wire it (story 333, design
 * §4): the {@link SaveFlowFixture}'s save flow over real persistence, with
 * journaled persistence on — a real {@link ProjectJournalCoordinator} whose
 * journal of Song A is open on an event bus, and Song A's working session —
 * the production {@link SessionJournalEnding}, {@link ProjectFolderCloser}
 * and {@link SaveAsFlow}, a {@link WindowInputFence}, and an
 * {@link ExitCoordinator} installed on a real main window with a scene,
 * whose clocks are manual ({@link ManualCloseTimers} and a nano clock), whose
 * steps are recorded, and whose DISPOSE and terminal exit are recorders: the
 * DISPOSE hand-off is a stage the test may hold; the terminal exit never
 * calls {@code Platform.exit}. The prompts are the real Alerts, owned by the
 * main window: the unsaved-changes prompt and the takeover dialog are
 * answered by title by the save flow's fixture; the take rule's confirmation
 * by {@link #stopTakeAnswer}. Every wait is bounded by
 * {@link SaveFlowFixture#GUARD}.
 */
final class ExitFixture implements AutoCloseable {

    static final Duration GUARD = SaveFlowFixture.GUARD;
    static final String RECORDING_TITLE = "Recording in Progress";
    static final String UNSAVED_TITLE = "Unsaved Changes";
    /** The answer that presses Enter on the focused button. */
    static final String ENTER = "<Enter>";
    /** The answer that presses Escape. */
    static final String ESCAPE = "<Escape>";
    /** The answer that is the window's close box. */
    static final String CLOSE_BOX = "<close box>";

    /** The shape of a prompt shown: its texts, its buttons in order with their data, the default and the focus. */
    record Shown(String header, String content, List<String> buttons, List<ButtonBar.ButtonData> data,
                 String defaultButton, String focused, Window owner) { }

    final SaveFlowFixture save;
    final Path songA;
    final DefaultEventBus bus = new DefaultEventBus();
    final SessionManager sessions = new SessionManager();
    final ProjectJournalCoordinator journal;
    final AtomicReference<WorkingSession> activeSession = new AtomicReference<>();
    final AtomicReference<Path> activeSessionDirectory = new AtomicReference<>();
    final ManualCloseTimers timers = new ManualCloseTimers();
    final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    /** Every step outcome, in order. */
    final List<ExitCoordinator.StepOutcome> steps = new CopyOnWriteArrayList<>();
    /** The step outcomes and the checkpoint events, in the order they happened. */
    final List<String> events = new CopyOnWriteArrayList<>();
    final AtomicInteger disposals = new AtomicInteger();
    final AtomicInteger terminalExits = new AtomicInteger();
    final AtomicInteger transportStops = new AtomicInteger();
    /** The DISPOSE hand-off the next disposal returns; completed unless a test holds it. */
    volatile CompletableFuture<Void> handOff = CompletableFuture.completedFuture(null);
    /**
     * The window's disposal DISPOSE runs instead of returning {@link #handOff}, when a test wires the
     * application's own ({@code MainController::disposeWindowLifetime}, story 333's stage 7); counted and
     * recorded as the recorder is.
     */
    volatile java.util.function.Supplier<CompletionStage<Void>> windowDisposal;
    /** The current transport controller the take facts read, or {@code null}. */
    volatile TransportController transport;
    /** Take facts a test supplies instead of the transport's, or {@code null}. */
    volatile ExitCoordinator.TakeFacts scriptedTakes;
    /** The take rule's confirmation's answer: a button's text, {@link #ENTER}, {@link #ESCAPE} or {@link #CLOSE_BOX}. */
    volatile String stopTakeAnswer = "Keep recording";
    /** Each take rule's confirmation shown. */
    final List<Shown> stopTakePrompts = new CopyOnWriteArrayList<>();
    /**
     * For each confirmation answered by a key or its close box: whether it closed by itself right after the
     * event — read before the hang guard hides it (the stage-6 review N3).
     */
    final List<Boolean> stopTakeClosedByItself = new CopyOnWriteArrayList<>();
    /** Each unsaved-changes prompt shown. */
    final List<Shown> unsavedPrompts = new CopyOnWriteArrayList<>();
    /** Run on the FX thread right before each unsaved-changes prompt is shown, while the window is inert. */
    volatile Runnable beforeTheUnsavedPrompt = () -> { };
    /** Run on the FX thread at each of the close's transport stops, before it (story 333: a test makes it throw). */
    volatile Runnable beforeTheTransportStop = () -> { };
    /**
     * The project's transport right after each of the close's stops of a real transport controller, recorded on
     * the acting thread: its state, and " post-roll" while it is in one.
     */
    final List<String> transportAfterStops = new CopyOnWriteArrayList<>();
    /**
     * The project's transport right before each of the close's stops of a real transport controller, recorded on
     * the acting thread as {@link #transportAfterStops} is (the stage-7 review N5: a post-roll the stop met).
     */
    final List<String> transportBeforeStops = new CopyOnWriteArrayList<>();
    /** Every progress surface the coordinator made. */
    final List<ClosingProgressDialog> surfaces = new CopyOnWriteArrayList<>();
    /** Told each step outcome on the FX thread right after it was recorded: a test acts at a step. */
    volatile java.util.function.Consumer<ExitCoordinator.StepOutcome> onStep = _ -> { };
    /** How many times Close Project showed the Welcome window. */
    final AtomicInteger welcomes = new AtomicInteger();
    /**
     * Whether the toolkit's nested loops may start: {@code false} is the application stop's seam into its
     * defensive branch (story 333, design §1.7 step 9).
     */
    volatile boolean nestedLoopsAllowed = true;
    /** The key of every nested loop the coordinator entered, in order (the application stop's waits). */
    final List<Object> nestedLoopsEntered = new CopyOnWriteArrayList<>();

    private final Runnable checkpointRecording;
    private Stage stage;
    private WindowInputFence fence;
    private ProjectClosePrompts prompts;
    private ExitCoordinator coordinator;
    private final ListChangeListener<Window> answerer = change -> {
        while (change.next()) {
            for (Window window : change.getAddedSubList()) {
                if (window instanceof Stage shown && RECORDING_TITLE.equals(shown.getTitle())) {
                    Platform.runLater(() -> answerStopTake(shown, 0));
                } else if (window instanceof Stage shown && UNSAVED_TITLE.equals(shown.getTitle())) {
                    unsavedPrompts.add(shapeOf(shown));
                }
            }
        }
    };

    /** Journaled persistence on, Song A's journal open, the ending as production builds it. */
    ExitFixture(Path workspace, AutoSaveConfig config) throws Exception {
        this(workspace, config, true, UnaryOperator.identity());
    }

    /**
     * @param journaling whether journaled persistence is on (Song A's journal open and its session opened)
     * @param ending     wraps the production journal ending (a test holds or fails a seal)
     */
    ExitFixture(Path workspace, AutoSaveConfig config, boolean journaling, UnaryOperator<JournalEnding> ending)
            throws Exception {
        this(workspace, config, journaling, ending, true);
    }

    /**
     * @param journaling   whether journaled persistence is on (Song A's journal open and its session opened)
     * @param ending       wraps the production journal ending (a test holds or fails a seal)
     * @param recoveryGate whether the opens run the recovery gate, as the application installs it; without it
     *                     Recover Session scans, asks and works on its own workers (story 331's ungated path)
     */
    ExitFixture(Path workspace, AutoSaveConfig config, boolean journaling, UnaryOperator<JournalEnding> ending,
                boolean recoveryGate) throws Exception {
        save = new SaveFlowFixture(workspace, config);
        songA = save.songA;
        journal = new ProjectJournalCoordinator(save.progress, onFx(NotificationBar::new), save.dispatcher,
                () -> journaling, () -> bus, save.checkpoints, sessions, InstantSource.system());
        checkpointRecording = save.checkpoints.addEventListener(event -> events.add("checkpoint "
                + event.getClass().getSimpleName() + (event instanceof CheckpointEvent.Written written
                ? " " + written.origin() : "")));
        if (recoveryGate) {
            runOnFx(() -> save.lifecycle().enableRecoveryGate(() -> journaling, () -> {
                Path journalDirectory = journal.currentProjectDirectory();
                journal.detachFromBus();
                return () -> journal.closeCurrentFor(journalDirectory, null);
            }, journal::openForRequired));
        }
        if (journaling) {
            journal.openFor(songA);
            activeSession.set(sessions.openSession(songA, Instant.now()).current());
            activeSessionDirectory.set(songA);
        }
        JournalEnding production = new SessionJournalEnding(journal, sessions, activeSession::get,
                activeSessionDirectory::get, () -> null, save::openFolder, InstantSource.system());
        JournalEnding used = ending.apply(production);
        runOnFx(() -> {
            save.installSaveAs(journal, used);
            save.lifecycle().setTakeBeingWrittenCheck(() -> takes().takeBeingWritten());
            save.lifecycle().setRecordingInFlightCheck(() -> takes().recordingInFlight());
            save.lifecycle().setWelcomeAfterClose(welcomes::incrementAndGet);
            stage = new Stage();
            stage.setTitle("Main window");
            stage.setScene(new Scene(new StackPane(new Button("Play")), 360, 240));
            stage.show();
            fence = new WindowInputFence();
            prompts = new ProjectClosePrompts(save.lifecycle(), () -> Optional.of(stage));
            Window.getWindows().addListener(answerer);
            coordinator = new ExitCoordinator(new ExitCoordinator.Deps(save.lifecycle(), save.projectManager,
                    save.folderCloser(), save.saveAsFlow(), new ExitCoordinator.TakeFacts() {
                        @Override
                        public boolean recordingInFlight() {
                            return takes().recordingInFlight();
                        }

                        @Override
                        public boolean takeBeingWritten() {
                            return takes().takeBeingWritten();
                        }

                        @Override
                        public javafx.beans.property.ReadOnlyBooleanProperty takeBeingWrittenProperty() {
                            return takes().takeBeingWrittenProperty();
                        }

                        @Override
                        public long takePublicationsWithClips() {
                            return takes().takePublicationsWithClips();
                        }
                    }, this::stopTheTransport, this::dispose, terminalExits::incrementAndGet, prompts,
                    new ClosePromptAnswers() {
                        @Override
                        public ClosePromptAnswers.StopTakeAnswer confirmStopTake(String header) {
                            return prompts.confirmStopTake(header);
                        }

                        @Override
                        public ProjectLifecycleController.UnsavedChangesAnswer askBeforeClosing(
                                ProjectLifecycleController.UnsavedChangesPrompt prompt) {
                            beforeTheUnsavedPrompt.run();
                            return prompts.askBeforeClosing(prompt);
                        }

                        @Override
                        public ProjectClosePrompts.TakenOverAnswer takenOver(ProjectClosePrompts.TakenOverPrompt prompt) {
                            return prompts.takenOver(prompt);
                        }

                        @Override
                        public void refuseSaveAsTarget(String message) {
                            prompts.refuseSaveAsTarget(message);
                        }
                    }, () -> {
                        ClosingProgressDialog dialog = new ClosingProgressDialog(Optional.of(stage));
                        surfaces.add(dialog);
                        return dialog;
                    }, save.progress, save.notificationBar(), fence, timers, nanos::get, save.dispatcher,
                    new ExitCoordinator.NestedLoops() {
                        @Override
                        public boolean canNest() {
                            return nestedLoopsAllowed && ExitCoordinator.NestedLoops.PLATFORM.canNest();
                        }

                        @Override
                        public Object enter(Object key) {
                            nestedLoopsEntered.add(key);
                            return ExitCoordinator.NestedLoops.PLATFORM.enter(key);
                        }

                        @Override
                        public void exit(Object key, Object value) {
                            ExitCoordinator.NestedLoops.PLATFORM.exit(key, value);
                        }
                    }, outcome -> {
                        steps.add(outcome);
                        events.add("step " + outcome.step() + " " + outcome.result());
                        onStep.accept(outcome);
                    }));
            coordinator.installOn(stage);
        });
    }

    private ExitCoordinator.TakeFacts takes() {
        ExitCoordinator.TakeFacts scripted = scriptedTakes;
        return scripted != null ? scripted : ExitCoordinator.TakeFacts.of(() -> transport);
    }

    private boolean stopTheTransport() {
        beforeTheTransportStop.run();
        transportStops.incrementAndGet();
        if (scriptedTakes instanceof ScriptedTakes scripted) {
            scripted.stop();
        }
        TransportController current = transport;
        if (current == null) {
            return true;
        }
        com.benesquivelmusic.daw.core.transport.Transport model = save.project.get().getTransport();
        transportBeforeStops.add(model.getState() + (model.isInPostRoll() ? " post-roll" : ""));
        boolean stopped = current.stopForClose();
        transportAfterStops.add(model.getState() + (model.isInPostRoll() ? " post-roll" : ""));
        return stopped;
    }

    /**
     * Take facts a test drives on the FX thread: a recording in flight, which
     * the close's Stop turns into a take being written; the take's
     * publication, which ends it and counts a take with clips.
     */
    static final class ScriptedTakes implements ExitCoordinator.TakeFacts {
        private final javafx.beans.property.ReadOnlyBooleanWrapper writing =
                new javafx.beans.property.ReadOnlyBooleanWrapper(this, "takeBeingWritten");
        private volatile boolean recording;
        // The fact itself, apart from its property, as the record coordinator keeps it (its fields): a close that
        // read only this would miss a property it never revalidated.
        private volatile boolean beingWritten;
        private volatile long publications;

        /** A recording is in flight. FX thread. */
        void record() {
            recording = true;
        }

        /** The Stop: the take is being written. FX thread. */
        void stop() {
            if (recording) {
                recording = false;
                beingWritten = true;
                writing.set(true);
            }
        }

        /** A take is being written (a Stop that ran before the close). FX thread. */
        void beingWritten(boolean written) {
            beingWritten = written;
            writing.set(written);
        }

        /** The take was published with clips: written no more. FX thread. */
        void published() {
            publications++;
            beingWritten = false;
            writing.set(false);
        }

        @Override
        public boolean recordingInFlight() {
            return recording;
        }

        @Override
        public boolean takeBeingWritten() {
            return beingWritten;
        }

        @Override
        public javafx.beans.property.ReadOnlyBooleanProperty takeBeingWrittenProperty() {
            return writing.getReadOnlyProperty();
        }

        @Override
        public long takePublicationsWithClips() {
            return publications;
        }
    }

    private CompletionStage<Void> dispose() {
        // As MainController.disposeWindowLifetime: the autosave's capture gate shuts first (the app-fix-2 review S1).
        save.windowLifetimeEnding = true;
        disposals.incrementAndGet();
        events.add("dispose");
        java.util.function.Supplier<CompletionStage<Void>> application = windowDisposal;
        return application != null ? application.get() : handOff;
    }

    ExitCoordinator coordinator() {
        return coordinator;
    }

    Stage stage() {
        return stage;
    }

    WindowInputFence fence() {
        return fence;
    }

    ProjectLifecycleController lifecycle() {
        return save.lifecycle();
    }

    /** The window's close box: a close request fired at the main window. FX thread inside. */
    void closeBox() throws Exception {
        runOnFx(() -> stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST)));
    }

    /** File ▸ Exit. */
    CompletionStage<ExitCoordinator.CloseOutcome> fileExit() throws Exception {
        return onFx(() -> coordinator.request(new ExitCoordinator.CloseDoor.FileExit()));
    }

    /** File ▸ Close Project. */
    CompletionStage<ExitCoordinator.CloseOutcome> closeProject() throws Exception {
        return onFx(() -> coordinator.request(new ExitCoordinator.CloseDoor.CloseProject()));
    }

    /**
     * The close-then-continue primitive (story 333, design §1.8), asked on the
     * FX thread for the switch {@code switchName}; the returned stage
     * completes with the outcome its continuation received, and each outcome
     * received is recorded in {@link #continuations}.
     */
    CompletableFuture<ExitCoordinator.CloseOutcome> closeThenContinue(String switchName) throws Exception {
        CompletableFuture<ExitCoordinator.CloseOutcome> received = new CompletableFuture<>();
        runOnFx(() -> coordinator.closeOpenProjectThenContinue(switchName, outcome -> {
            continuations.add(outcome);
            events.add("continuation " + outcome.getClass().getSimpleName());
            received.complete(outcome);
        }));
        return received;
    }

    /** Every outcome a close-then-continue continuation received, in order. */
    final List<ExitCoordinator.CloseOutcome> continuations = new CopyOnWriteArrayList<>();

    /**
     * {@code Application.stop()}'s call (story 333, design §1.7), posted to
     * the FX thread and never waited for here: its waits are nested loops
     * that need the test to drive the take, the writers and the timers with
     * FX posts that run inside them (the feasibility critique's S9). The
     * returned stage completes once it returned — exceptionally if it threw.
     */
    CompletableFuture<Void> stopTheApplication() {
        CompletableFuture<Void> stopped = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                coordinator.applicationStopping();
                events.add("stopped");
                stopped.complete(null);
            } catch (Throwable failed) {
                stopped.completeExceptionally(failed);
            }
        });
        return stopped;
    }

    /** Waits, bounded by {@link #GUARD} — longer than any wait inside — until {@code stopped} completed. */
    static void awaitStopped(CompletableFuture<Void> stopped) throws Exception {
        stopped.get(GUARD.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** An edit of the open project: a track added, the project dirty. */
    void edit(String track) throws Exception {
        runOnFx(() -> {
            save.project.get().createAudioTrack(track);
            save.project.get().markDirty();
        });
    }

    /** The steps published so far, as {@code "<step> <result>"}. */
    List<String> stepNames() {
        return steps.stream().map(outcome -> outcome.step() + " " + outcome.result()).toList();
    }

    /** Whether {@code step} was published. */
    boolean reached(ExitCoordinator.ExitStep step) {
        return steps.stream().anyMatch(outcome -> outcome.step() == step);
    }

    /** Waits, bounded, until {@code step} was published. */
    void awaitStep(ExitCoordinator.ExitStep step) throws Exception {
        save.awaitOnFx(() -> reached(step), "the step " + step);
    }

    /** Waits, bounded, until {@code condition} holds on the FX thread. */
    void await(BooleanSupplier condition, String what) throws Exception {
        save.awaitOnFx(condition, what);
    }

    /**
     * Waits, bounded, until a journal end of Song A — a drain or a seal on
     * its own thread — has ended entirely, its session manifest written too:
     * the journal is no longer open, and a barrier through the coordinator's
     * lifecycle lock, which that end holds to its last write, has passed.
     */
    void awaitTheJournalEnded() throws Exception {
        await(() -> !journal.isOpen(), "the journal end began");
        journal.drainWithoutSealFor(songA, null);
    }

    /** The progress surface made last. */
    ClosingProgressDialog surface() {
        return surfaces.isEmpty() ? null : surfaces.getLast();
    }

    /** Fires the manual timers of {@code delay}, on the FX thread. */
    void fire(Duration delay) throws Exception {
        runOnFx(() -> timers.fire(delay));
    }

    @Override
    public void close() throws Exception {
        try {
            handOff.complete(null);
            save.publishes.releaseAll();
            runOnFx(() -> {
                Window.getWindows().removeListener(answerer);
                for (ClosingProgressDialog dialog : surfaces) {
                    dialog.closeByProtocol();
                }
                if (fence != null) {
                    fence.lower();
                }
                if (stage != null && stage.isShowing()) {
                    stage.hide();
                }
            });
            if (journal.isOpen()) {
                journal.drainWithoutSeal(null);
            } else {
                // A barrier: a drain still running on its own thread (Quit anyway's, a keep's) holds the
                // coordinator's lifecycle lock to its last write; nothing is left writing when the folder is deleted.
                journal.drainWithoutSealFor(songA, null);
            }
        } finally {
            checkpointRecording.run();
            bus.close();
            save.close();
        }
    }

    // ── the take rule's confirmation ─────────────────────────────────────────

    private void answerStopTake(Stage prompt, int turns) {
        Node focused = prompt.getScene().getFocusOwner();
        if (focused == null && turns < 50) {
            Platform.runLater(() -> answerStopTake(prompt, turns + 1));
            return;
        }
        stopTakePrompts.add(shapeOf(prompt));
        Node found = prompt.getScene().getRoot().lookup(".dialog-pane");
        String text = stopTakeAnswer;
        if (ENTER.equals(text) || ESCAPE.equals(text)) {
            KeyCode key = ENTER.equals(text) ? KeyCode.ENTER : KeyCode.ESCAPE;
            Node target = focused != null ? focused : prompt.getScene().getRoot();
            target.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", key, false, false, false, false));
            target.fireEvent(new KeyEvent(KeyEvent.KEY_RELEASED, "", "", key, false, false, false, false));
            stopTakeClosedByItself.add(!prompt.isShowing());
            hideIfStillShowing(prompt);
            return;
        }
        if (CLOSE_BOX.equals(text)) {
            prompt.fireEvent(new WindowEvent(prompt, WindowEvent.WINDOW_CLOSE_REQUEST));
            stopTakeClosedByItself.add(!prompt.isShowing());
            hideIfStillShowing(prompt);
            return;
        }
        if (found instanceof DialogPane pane) {
            for (ButtonType type : pane.getButtonTypes()) {
                if (type.getText().equals(text) && pane.lookupButton(type) instanceof Button button) {
                    button.fire();
                    return;
                }
            }
        }
        hideIfStillShowing(prompt);
    }

    /** A hang guard only: a prompt that refused to close must not leave its nested loop running. */
    private static void hideIfStillShowing(Stage prompt) {
        if (prompt.isShowing()) {
            prompt.hide();
        }
    }

    private static Shown shapeOf(Stage prompt) {
        Node found = prompt.getScene().getRoot().lookup(".dialog-pane");
        if (!(found instanceof DialogPane pane)) {
            return new Shown(null, null, List.of(), List.of(), null, null, prompt.getOwner());
        }
        List<String> buttons = new ArrayList<>();
        List<ButtonBar.ButtonData> data = new ArrayList<>();
        String defaultButton = null;
        for (ButtonType type : pane.getButtonTypes()) {
            buttons.add(type.getText());
            data.add(type.getButtonData());
            if (pane.lookupButton(type) instanceof Button button && button.isDefaultButton()) {
                defaultButton = type.getText();
            }
        }
        Node focused = prompt.getScene().getFocusOwner();
        return new Shown(pane.getHeaderText(), pane.getContentText(), List.copyOf(buttons), List.copyOf(data),
                defaultButton, focused instanceof Button button ? button.getText() : null, prompt.getOwner());
    }
}
