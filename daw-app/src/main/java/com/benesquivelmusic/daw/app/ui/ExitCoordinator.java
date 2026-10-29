package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.ClosePromptAnswers.StopTakeAnswer;
import com.benesquivelmusic.daw.app.ui.JournalEnding.JournalEnd;
import com.benesquivelmusic.daw.app.ui.ProjectClosePrompts.TakenOverAnswer;
import com.benesquivelmusic.daw.app.ui.ProjectFolderCloser.ClosingCheckpoint;
import com.benesquivelmusic.daw.app.ui.ProjectFolderCloser.Plan;
import com.benesquivelmusic.daw.app.ui.ProjectFolderCloser.WriterScope;
import com.benesquivelmusic.daw.app.ui.ProjectLifecycleController.CloseReadiness;
import com.benesquivelmusic.daw.app.ui.ProjectLifecycleController.FlushResult;
import com.benesquivelmusic.daw.app.ui.ProjectLifecycleController.PendingSwitch;
import com.benesquivelmusic.daw.app.ui.ProjectLifecycleController.SavesReport;
import com.benesquivelmusic.daw.app.ui.ProjectLifecycleController.UnsavedChangesAnswer;
import com.benesquivelmusic.daw.app.ui.SaveAsFlow.SaveAsResult;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;
import com.benesquivelmusic.daw.core.persistence.LockStolenException;
import com.benesquivelmusic.daw.core.persistence.ProjectLock;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.SaveOutcome;

import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.value.ObservableBooleanValue;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The App-Exit Protocol (story 333; Persistence Integrity book §2.7, §4.8,
 * §5.4; the studio engineer's rulings R1-R12, the closing checkpoint, the
 * answers Q1-Q8, ruling 2 OQ-3/OQ-4 and the stage-4 S8 ruling): the one
 * coordinator every close of the open project goes through — the window's
 * close box, File ▸ Exit, File ▸ Close Project, the close-then-continue
 * primitive and the application stop — whose steps run in the
 * book's order, each publishing its outcome before the next starts:
 * INTERCEPT, GUARD, FLUSH, CLOSING_CHECKPOINT, SEAL, RELEASE, then, for an
 * exit, DISPOSE and HIDE, or, for Close Project, the fresh Untitled project.
 *
 * <h2>The steps</h2>
 * <ol>
 *   <li><b>INTERCEPT</b>: the request is taken — the primary stage's
 *       {@link WindowEvent#WINDOW_CLOSE_REQUEST} is consumed by an event
 *       filter ({@link #installOn}), so the window stays until the protocol
 *       decides. A request while a close runs is merged, upgrades a Close
 *       Project to an exit (ruling Q8), or is refused (design §1.4).</li>
 *   <li><b>GUARD</b> (ruling R1's order): Close Project is refused while
 *       another switch is pending, any door while a file dialog is open;
 *       then a recording in flight asks "Stop the take and close" first —
 *       {@code Keep recording} ends the close before any step; then the
 *       window goes input-inert ({@link WindowInputFence}) and the close is
 *       the switch pending, and it waits — never blocking the FX thread —
 *       for every save queued before it to report, for the take to be
 *       written and published, and, for an exit, for another pending switch
 *       to end (a door waiting for a save is replaced silently, ruling R12
 *       #8). A queued save that failed ends the close, its ERROR shown
 *       (ruling R9); a lost lock among them — or one already known — shows
 *       the exit's takeover dialog in place of the unsaved-changes prompt
 *       (rulings 2 OQ-4, stage-4 S8). Otherwise unsaved changes ask the R2
 *       prompt: Save and close, Discard and close, or Cancel — the veto.</li>
 *   <li><b>FLUSH</b>, only for Save and close: the transport stopped right
 *       before the capture, then the one canonical save (story 331) — the
 *       first save's chooser and create for a project never saved, the
 *       exit's Save As for a read-only open or after a takeover (ruling R5:
 *       published, remembered, not opened). Discard and nothing-to-save stop
 *       the transport and read the lock off the FX thread, so a folder
 *       another session took gets no closing checkpoint and no seal.</li>
 *   <li><b>CLOSING_CHECKPOINT</b>, <b>SEAL</b>, <b>RELEASE</b>: the shared
 *       tail ({@link ProjectFolderCloser}) with the plan of the decision
 *       (design §1.2's table); the journal's detach at its start is the
 *       close's point of no return (ruling Q4).</li>
 *   <li><b>DISPOSE</b> and <b>HIDE</b> (exits): the window's lifetime is
 *       disposed — the engine's stop handed to its own thread — and the
 *       window hides once that hand-off ended or after
 *       {@link #ENGINE_STOP_BOUND}, whichever is first, while the progress
 *       surface keeps painting "Stopping audio…" (ruling 2 OQ-3); then the
 *       terminal exit runs. Close Project installs a fresh Untitled project
 *       with the Welcome window instead (ruling R10).</li>
 * </ol>
 *
 * <h2>Waiting, and the way out</h2>
 * <p>Every wait is a completion stage or a property listened to, and every
 * continuation is fenced by the run's identity — a continuation of a run
 * that ended does nothing — and posted unkeyed through the
 * {@link FxDispatcher}, so a prompt or a nested event loop may follow it
 * (javafx skill §11, "Threading"; Control Synchronization book §2.6). Once a
 * wait has lasted half a second the modal progress surface
 * ({@link ClosingProgress}) shows its step and a keep button; at ten seconds
 * a note follows the current wait, and an exit offers {@code Quit anyway}
 * (ruling R8) — never during the take wait (R1). The clock starts over
 * after every prompt (ruling S3). The keep button ends the close; it is
 * disabled for ten seconds after the journal's detach and gone once the
 * release began, with the exceptions {@link #refreshSurface} states (ruling
 * Q4, as ruling 4 Q1 and Q2 amend it). Quit anyway skips the steps not yet begun and
 * leaves the lock, as after a crash. Every tail runs under that clock, so a
 * hung writer always meets a way out (the stage-6 reviews' B1/B2). And every
 * entry into a run is guarded: a step that throws ends the run by how far it
 * had got, with one ERROR, so the window never stays inert
 * ({@link #stepFailed}, ruling 4 Q3).</p>
 *
 * <h2>The close-then-continue primitive</h2>
 * <p>{@link #closeOpenProjectThenContinue} closes the open project as Close
 * Project does — the same GUARD, prompts and tail, no HIDE — installs the
 * Untitled project without the Welcome window and hands the caller one
 * outcome, posted (design §1.8; ruling 4 Q6.4). It has no production door yet
 * (known limit 11): a project workspace switch will be its caller.</p>
 *
 * <h2>The application-stop fallback</h2>
 * <p>{@link #applicationStopping()}, from {@code Application.stop()}: when the
 * protocol ran it only waits, bounded, for the audio engine's stop; when it
 * did not — the toolkit ended by another caller — it closes the open project
 * without asking and never writes {@code project.daw}: the take stopped and
 * waited for, the writers idle, the closing checkpoint of unsaved changes, the
 * journal sealed — or left unsealed with unsaved changes — the lock released,
 * then DISPOSE; it takes an interactive close over, joining what it had begun
 * (ruling R4; ruling 4 Q6). Each of its waits is a nested event loop with a
 * deadline ({@link NestedWait}).</p>
 *
 * <p>FX-confined: every method runs on the JavaFX application thread — but
 * {@link #applicationStopping()}, which may be called off it.</p>
 */
final class ExitCoordinator {

    private static final Logger LOG = Logger.getLogger(ExitCoordinator.class.getName());

    /** How long a close waits on disk work before its progress surface shows (ruling R8). */
    static final Duration SURFACE_DELAY = Duration.ofMillis(500);

    /** How long a close waits before its note — and an exit's {@code Quit anyway} — shows (ruling R8). */
    static final Duration NOTE_DELAY = Duration.ofSeconds(10);

    /** How long the keep button stays disabled after the journal's detach (ruling Q4). */
    static final Duration KEEP_AFTER_DETACH_DELAY = Duration.ofSeconds(10);

    /** How long HIDE waits for the engine's stop, handed to its own thread, at most (ruling 2 OQ-3). */
    static final Duration ENGINE_STOP_BOUND = Duration.ofSeconds(5);

    /**
     * How long, counted from a DISPOSE's hand-off, {@code Application.stop()}
     * waits for the engine's stop to end before the process ends — after the
     * protocol ran and after the application-stop fallback's own DISPOSE
     * alike (ruling 2 OQ-3, constraint 1; the studio engineer's stage-8 S1):
     * 10 s more than {@link #ENGINE_STOP_BOUND}.
     */
    static final Duration ENGINE_STOP_AFTER_HIDE_BOUND = Duration.ofSeconds(15);

    /** The application-stop fallback's bound in its log lines: ruling R4's 30 s, the M1 hook's (ruling 4 Q6.3). */
    private static final long EXIT_WRITE_WAIT_SECONDS = ProjectManager.EXIT_WRITE_WAIT.toSeconds();

    /** The window in which a second exit during the take wait says so once (ruling R1, the G2 shape). */
    static final Duration SECOND_REQUEST_WINDOW = ProjectLifecycleController.DOOR_REFUSAL_WINDOW;

    /** The name an exit is known by in the log and as the switch pending. */
    static final String EXIT_DOOR = "Exit";

    /** The name the application-stop fallback is known by in the log and as the switch pending. */
    static final String APPLICATION_STOP_DOOR = ProjectLifecycleController.APPLICATION_STOP_DOOR;

    /** The thread DISPOSE hands the audio engine's stop to ({@link #onTheEngineShutdownThread}). */
    static final String ENGINE_SHUTDOWN_THREAD = "daw-engine-shutdown";

    /**
     * The non-daemon platform thread of the application-stop fallback's
     * defensive branch, where no nested event loop can run (design §1.7 step
     * 9): the JVM waits for it, bounded, before it ends.
     */
    static final String EXIT_FALLBACK_THREAD = "daw-exit-fallback";

    /**
     * Runs {@code work} — DISPOSE's hand-off: the render queue's
     * persistence, the audio engine's stop, the end of the render queue's
     * workers, the plug-in supervisor, the backup retention and the disk
     * scanner — on one platform thread, {@value #ENGINE_SHUTDOWN_THREAD},
     * so a driver's slow stop never freezes the visible progress surface
     * (the studio engineer's S9). The thread is a daemon (ruling 2 OQ-3):
     * nothing calls {@code System.exit} once the launcher returns, so a
     * non-daemon thread stuck in a driver call would keep a windowless
     * process holding the device; a daemon one ends with the JVM, leaving the
     * device as after a crash. HIDE waits for it at most
     * {@link #ENGINE_STOP_BOUND}, and {@code Application.stop()} at most
     * {@link #ENGINE_STOP_AFTER_HIDE_BOUND} from its start, after which the
     * process ends and cuts it ({@link #applicationStopping()}). Any thread;
     * never waits.
     *
     * @param work the hand-off
     * @return completes when the thread ended — exceptionally when {@code work} threw or the thread could not start
     */
    static CompletionStage<Void> onTheEngineShutdownThread(Runnable work) {
        Objects.requireNonNull(work, "work must not be null");
        CompletableFuture<Void> ended = new CompletableFuture<>();
        try {
            Thread.ofPlatform().name(ENGINE_SHUTDOWN_THREAD).daemon(true).start(() -> {
                try {
                    work.run();
                    ended.complete(null);
                } catch (RuntimeException | Error failed) {
                    LOG.log(Level.SEVERE, failed, () -> "The audio engine's shutdown failed");
                    ended.completeExceptionally(failed);
                }
            });
        } catch (RuntimeException | Error notStarted) {
            LOG.log(Level.SEVERE, notStarted, () -> "The audio engine's shutdown thread could not start");
            ended.completeExceptionally(notStarted);
        }
        return ended;
    }

    // ── the intent ──────────────────────────────────────────────────────────

    /** A door that closes the open project (CS book §2.8: the window's box, the menu and its shortcut are one intent). */
    sealed interface CloseDoor permits CloseDoor.WindowClose, CloseDoor.FileExit, CloseDoor.CloseProject,
            CloseDoor.CloseThenContinue, CloseDoor.ApplicationStop {

        /** The door's name, as the log and the switch pending name it. */
        String name();

        /** Whether the close ends the application. */
        boolean exitsApplication();

        /**
         * Whether the close may ask the user anything: every door but the application stop (ruling R1).
         * Declarative: the application stop never reaches GUARD — {@link ExitCoordinator#request} sends it to
         * the fallback before any run is made, which asks nothing — so GUARD's check states R1's rule for the
         * doors that do reach it (the stage-8 review N4; {@code ExitCoordinatorSourceTest} pins the diversion).
         */
        boolean mayAsk();

        /** The window's close box, or Alt+F4 (and the system Quit on macOS). */
        record WindowClose() implements CloseDoor {
            @Override
            public String name() {
                return EXIT_DOOR;
            }

            @Override
            public boolean exitsApplication() {
                return true;
            }

            @Override
            public boolean mayAsk() {
                return true;
            }
        }

        /** File ▸ Exit, Shortcut+Q, the palette's Exit. */
        record FileExit() implements CloseDoor {
            @Override
            public String name() {
                return EXIT_DOOR;
            }

            @Override
            public boolean exitsApplication() {
                return true;
            }

            @Override
            public boolean mayAsk() {
                return true;
            }
        }

        /** File ▸ Close Project, Shortcut+Shift+W, the palette's Close Project. */
        record CloseProject() implements CloseDoor {
            @Override
            public String name() {
                return ProjectLifecycleController.CLOSE_PROJECT_DOOR;
            }

            @Override
            public boolean exitsApplication() {
                return false;
            }

            @Override
            public boolean mayAsk() {
                return true;
            }
        }

        /**
         * The close-then-continue primitive ({@link #closeOpenProjectThenContinue}): closes as Close Project
         * does, then hands {@code then} its one outcome (design §1.8; ruling 4 Q6.4).
         *
         * @param switchName the switch that follows, as refusals and the primitive's texts name it
         * @param then       receives the close's one outcome, posted to the FX thread
         */
        record CloseThenContinue(String switchName, Consumer<CloseOutcome> then) implements CloseDoor {
            public CloseThenContinue {
                Objects.requireNonNull(switchName, "switchName must not be null");
                Objects.requireNonNull(then, "then must not be null");
            }

            @Override
            public String name() {
                return switchName;
            }

            @Override
            public boolean exitsApplication() {
                return false;
            }

            @Override
            public boolean mayAsk() {
                return true;
            }
        }

        /**
         * The application stops without the protocol: {@code Application.stop()}'s fallback
         * ({@link #applicationStopping()}, ruling R4), which asks nothing.
         */
        record ApplicationStop() implements CloseDoor {
            @Override
            public String name() {
                return APPLICATION_STOP_DOOR;
            }

            @Override
            public boolean exitsApplication() {
                return true;
            }

            @Override
            public boolean mayAsk() {
                return false;
            }
        }
    }

    /** How a close ended. */
    sealed interface CloseOutcome permits CloseOutcome.Closed, CloseOutcome.Cancelled, CloseOutcome.Failed {

        /** The project was closed: the window hid, or the Untitled project replaced it. */
        record Closed() implements CloseOutcome { }

        /** The close was refused or the user kept the project open: nothing more runs. */
        record Cancelled() implements CloseOutcome { }

        /**
         * A save the close waited for, or its own, failed, and the close stopped (ruling R9); or a step of the
         * close threw, and the close ended as far as it had got (ruling 4 Q3).
         *
         * @param cause why
         */
        record Failed(Throwable cause) implements CloseOutcome {
            public Failed {
                Objects.requireNonNull(cause, "cause must not be null");
            }
        }
    }

    /** The protocol's steps, in Persistence Integrity book §5.4's order with the closing checkpoint before SEAL. */
    enum ExitStep { INTERCEPT, GUARD, FLUSH, CLOSING_CHECKPOINT, SEAL, RELEASE, DISPOSE, HIDE }

    /** How a step ended. */
    enum StepResult { DONE, SKIPPED, FAILED, CANCELLED }

    /**
     * A step's outcome, published before the next step starts (book §2.7, §5.4).
     *
     * @param step   the step
     * @param result how it ended
     * @param detail what it did, for the log
     */
    record StepOutcome(ExitStep step, StepResult result, String detail) {
        StepOutcome {
            Objects.requireNonNull(step, "step must not be null");
            Objects.requireNonNull(result, "result must not be null");
            Objects.requireNonNull(detail, "detail must not be null");
        }
    }

    /**
     * The take facts of the current transport controller (ruling R1): the
     * controller's own take-being-written answer and property, which the
     * close waits on, and its recording in flight — a deliberate superset of
     * the lifecycle controller's check, which leaves the count-in to the
     * pipeline and the MIDI recorders (ruling 4 Q5 (ii)). FX thread.
     */
    interface TakeFacts {

        /** No transport: nothing records, nothing is being written. */
        TakeFacts NONE = of(() -> null);

        /**
         * Whether a recording is in flight: RECORDING, PREPARING, COUNT_IN or a live MIDI recorder (ruling R1).
         * The count-in is named on its own on purpose: today a count-in also has an active pipeline or MIDI
         * recorders, so the disjunct is redundant in fact, and it keeps R1 true if a take is ever allocated only
         * at the downbeat.
         */
        boolean recordingInFlight();

        /** Whether a take is still being written or read back for its publication. */
        boolean takeBeingWritten();

        /** {@link #takeBeingWritten()} as a property. */
        ReadOnlyBooleanProperty takeBeingWrittenProperty();

        /** How many takes the controller published with at least one clip or note so far. */
        long takePublicationsWithClips();

        /**
         * The facts of {@code controller}'s answer, read live each time.
         *
         * @param controller the current transport controller, or {@code null} when there is none
         * @return the facts
         */
        static TakeFacts of(Supplier<TransportController> controller) {
            Objects.requireNonNull(controller, "controller must not be null");
            ReadOnlyBooleanProperty nothing = new ReadOnlyBooleanWrapper(false).getReadOnlyProperty();
            return new TakeFacts() {
                @Override
                public boolean recordingInFlight() {
                    TransportController current = controller.get();
                    return current != null && (current.isRecordingInFlight() || current.isCountingIn());
                }

                @Override
                public boolean takeBeingWritten() {
                    TransportController current = controller.get();
                    return current != null && current.isTakeBeingWritten();
                }

                @Override
                public ReadOnlyBooleanProperty takeBeingWrittenProperty() {
                    TransportController current = controller.get();
                    return current != null ? current.takeBeingWrittenProperty() : nothing;
                }

                @Override
                public long takePublicationsWithClips() {
                    TransportController current = controller.get();
                    return current != null ? current.takePublicationsWithClips() : 0L;
                }
            };
        }
    }

    /**
     * The toolkit's nested event loops: the application-stop fallback waits
     * in them inside {@code Application.stop()} ({@link NestedWait}, design
     * §1.7), and a test that says no loop can start reaches its defensive
     * branch. The interactive protocol never nests a loop itself — its
     * prompts are the toolkit's own {@code showAndWait}.
     */
    interface NestedLoops {

        /** The toolkit's own: {@link Platform#canStartNestedEventLoop()} and its enter and exit. */
        NestedLoops PLATFORM = new NestedLoops() {
            @Override
            public boolean canNest() {
                return Platform.canStartNestedEventLoop();
            }

            @Override
            public Object enter(Object key) {
                return Platform.enterNestedEventLoop(key);
            }

            @Override
            public void exit(Object key, Object value) {
                Platform.exitNestedEventLoop(key, value);
            }
        };

        /** Whether a nested loop may start now. */
        boolean canNest();

        /** Enters the loop of {@code key}; returns what its exit hands back. */
        Object enter(Object key);

        /** Exits the loop of {@code key} with {@code value}. */
        void exit(Object key, Object value);
    }

    /**
     * The coordinator's collaborators.
     *
     * @param lifecycle       the lifecycle controller: the close's facts, the switch pending, the save
     * @param projectManager  the project manager: the lock's read, the writers' idle
     * @param folderCloser    the shared tail of every close
     * @param saveAs          the exit's Save As (rulings R2 read-only, R7)
     * @param takes           the current transport controller's take facts
     * @param stopForClose    the close's transport stop ({@code TransportController.stopForClose()}),
     *                        answering whether the transport stopped
     * @param disposeWindow   DISPOSE: the window's lifetime's FX part, returning the engine's hand-off; it runs
     *                        once — a second call returns the first call's hand-off, also after the first call
     *                        threw
     * @param terminalExit    what ends the application after HIDE ({@code Platform::exit}; a test's recorder)
     * @param prompts         builds the prompts' words (and is the production {@code answers})
     * @param answers         shows the prompts and returns their answers
     * @param progressSurface makes the progress surface of a close
     * @param strip           the session strip, which shows "Finishing take…" during the take wait
     * @param notifications   the notification bar
     * @param fence           the input-inert window
     * @param timers          the clock seam of the surface, the note, the detach, the engine's bound and the
     *                        application stop's deadlines
     * @param nanoClock       the monotonic clock of the second request's rate limit, of DISPOSE's hand-off start
     *                        and of the application stop's deadlines
     * @param dispatcher      the FX marshalling seam; {@code null} for the app-scoped default
     * @param nestedLoops     the toolkit's nested loops, the application stop's waits
     * @param stepObserver    told each step's outcome before the next step starts
     */
    record Deps(ProjectLifecycleController lifecycle, ProjectManager projectManager, ProjectFolderCloser folderCloser,
                SaveAsFlow saveAs, TakeFacts takes, BooleanSupplier stopForClose,
                Supplier<CompletionStage<Void>> disposeWindow, Runnable terminalExit, ProjectClosePrompts prompts,
                ClosePromptAnswers answers, Supplier<ClosingProgress> progressSurface,
                ProjectOperationProgress strip, NotificationBar notifications, WindowInputFence fence,
                CloseTimers timers, LongSupplier nanoClock, FxDispatcher dispatcher, NestedLoops nestedLoops,
                Consumer<StepOutcome> stepObserver) {
        Deps {
            Objects.requireNonNull(lifecycle, "lifecycle must not be null");
            Objects.requireNonNull(projectManager, "projectManager must not be null");
            Objects.requireNonNull(folderCloser, "folderCloser must not be null");
            Objects.requireNonNull(saveAs, "saveAs must not be null");
            Objects.requireNonNull(takes, "takes must not be null");
            Objects.requireNonNull(stopForClose, "stopForClose must not be null");
            Objects.requireNonNull(disposeWindow, "disposeWindow must not be null");
            Objects.requireNonNull(terminalExit, "terminalExit must not be null");
            Objects.requireNonNull(prompts, "prompts must not be null");
            Objects.requireNonNull(answers, "answers must not be null");
            Objects.requireNonNull(progressSurface, "progressSurface must not be null");
            Objects.requireNonNull(strip, "strip must not be null");
            Objects.requireNonNull(notifications, "notifications must not be null");
            Objects.requireNonNull(fence, "fence must not be null");
            Objects.requireNonNull(timers, "timers must not be null");
            Objects.requireNonNull(nanoClock, "nanoClock must not be null");
            Objects.requireNonNull(nestedLoops, "nestedLoops must not be null");
            Objects.requireNonNull(stepObserver, "stepObserver must not be null");
        }
    }

    /** What the guard decided (design §1.2). */
    private enum Decision { NOTHING_TO_SAVE, DISCARD, SAVE, SAVE_AS_TAKEOVER }

    /** Where a run stands in its GUARD. */
    private enum Phase { STARTING, WAITING, DECIDED }

    /** What a run waits on now: its step line and its ten-second note follow it (ruling S2). */
    private enum WaitKind { NONE, TAKE, SAVES, SWITCH, FLUSH_SAVE, LOCK_CHECK, CHECKPOINT, JOURNAL, RELEASE, AUDIO }

    /** A listener the run added, with the subject it was added to (the remembered-subject rule). */
    private record Subscription(Observable subject, InvalidationListener listener) {
        void remove() {
            subject.removeListener(listener);
        }
    }

    /** One close: the run's facts, FX-confined; a continuation holding a run that is no longer current does nothing. */
    private final class CloseRun {
        final CloseDoor door;
        final CompletableFuture<CloseOutcome> outcome = new CompletableFuture<>();
        final List<Subscription> subscriptions = new ArrayList<>();
        final List<CloseTimers.Cancellable> clockTimers = new ArrayList<>();
        final Set<ExitStep> published = EnumSet.noneOf(ExitStep.class);
        final Set<ExitStep> reached = EnumSet.noneOf(ExitStep.class);
        Phase phase = Phase.STARTING;
        ExitStep currentStep = ExitStep.INTERCEPT;
        boolean exitRequested;
        CloseDoor exitAfterRelease;
        CompletableFuture<CloseOutcome> exitAfterReleaseOutcome;
        boolean ended;
        Decision decision;
        boolean lockLost;
        /**
         * The close's own Save As was asked for (FLUSH's takeover or read-only Save As): the application-stop
         * fallback's log then says the copy may still keep the changes (L8c; the studio engineer's final ruling F2).
         */
        boolean saveAsAsked;
        boolean takeHandled;
        long takePublicationsAtStart;
        String projectName = "";
        WaitKind currentWait = WaitKind.NONE;
        String switchName = "";
        boolean flushSaved;
        boolean detached;
        boolean keepAfterDetachAllowed;
        boolean releaseBegan;
        boolean quitting;
        /** An exit upgraded from Close Project waits, after Close Project's release, for every writer (D11). */
        boolean awaitingExitRelease;
        boolean disposeBegan;
        /** A step threw and the failure path runs: a second failure is logged only (ruling 4 Q3). */
        boolean failing;
        boolean hidden;
        /** The application-stop fallback took the run over (ruling 4 Q6.2): its own continuations do nothing. */
        boolean takenOver;
        CompletionStage<Void> handOff;
        /** The nano time DISPOSE handed the engine's stop off: the start of ruling 2 OQ-3's 15 s. */
        long disposeStartedNanos;
        Throwable sealFailure;
        SavesReport savesReport;
        ObservableBooleanValue takeSubject;
        Plan plan;
        ProjectFolderCloser.Closing closing;
        ClosingProgress surface;
        ProjectOperationProgress.NamedOperation takeOperation;
        long clockEpoch;
        boolean clockArmed;
        boolean surfaceDue;
        boolean noteDue;
        CloseTimers.Cancellable detachTimer;
        CloseTimers.Cancellable hideTimer;

        CloseRun(CloseDoor door) {
            this.door = door;
        }
    }

    private final Deps deps;
    private CloseRun running;
    /** HIDE ran: the protocol completed. Written on the FX thread; read by an application stop off it too. */
    private volatile boolean protocolCompleted;
    /** {@code Application.stop()} came ({@link #applicationStopping()}): no close starts any more. */
    private final AtomicBoolean applicationStopped = new AtomicBoolean();
    /** The hand-off of the DISPOSE the protocol ran, kept once it completed, for ruling 2 OQ-3's wait. */
    private CompletionStage<Void> completedHandOff;
    private long completedHandOffStartNanos;
    private Stage primaryStage;
    private long lastSecondRequestNanos;
    private boolean secondRequestShown;

    /**
     * Creates the coordinator.
     *
     * @param deps its collaborators
     */
    ExitCoordinator(Deps deps) {
        this.deps = Objects.requireNonNull(deps, "deps must not be null");
    }

    // ── the doors ───────────────────────────────────────────────────────────

    /**
     * Makes the primary stage's close box — and Alt+F4, and the system's
     * Quit on macOS, which reach the stage as a close request — the window
     * close door: an event filter consumes every
     * {@link WindowEvent#WINDOW_CLOSE_REQUEST} and asks the protocol, so the
     * window stays until the protocol decides (Persistence Integrity book
     * §5.4), and no later {@code setOnCloseRequest} can take the request
     * from it (javafx skill §12, "Event handling and public events": the
     * typed window event). HIDE hides this stage; a hide fires no close
     * request. It is installed once: the same stage again adds no second
     * filter — a second filter would ask the protocol twice for every close
     * box (the stage-7 review N3) — and another stage is refused. FX thread.
     *
     * @param stage the primary stage
     * @throws IllegalStateException when the coordinator was installed on another stage
     */
    void installOn(Stage stage) {
        Objects.requireNonNull(stage, "stage must not be null");
        if (primaryStage == stage) {
            return;
        }
        if (primaryStage != null) {
            throw new IllegalStateException("The close protocol is installed on one primary stage");
        }
        primaryStage = stage;
        stage.addEventFilter(WindowEvent.WINDOW_CLOSE_REQUEST, event -> {
            event.consume();
            request(new CloseDoor.WindowClose());
        });
    }

    /**
     * Asks to close the open project through {@code door}: a new run, or —
     * while one runs — merged with it, an upgrade of a Close Project to an
     * exit (ruling Q8), or refused (design §1.4). A Close Project with
     * nothing to close ({@link #closeProjectAvailable()}, ruling R10) does
     * nothing, as its disabled menu item and palette entry do, whichever
     * accelerator the scene holds for its shortcut (the studio engineer's
     * stage-7 N1). Once the application stops, no close starts: the
     * application-stop fallback closes the project ({@link #applicationStopping()},
     * which {@link CloseDoor.ApplicationStop} runs). Returns at once; the
     * protocol continues on later FX turns. FX thread.
     *
     * @param door the door
     * @return how the close ended — the running close's own when merged — completed on the FX thread
     */
    CompletionStage<CloseOutcome> request(CloseDoor door) {
        Objects.requireNonNull(door, "door must not be null");
        if (door instanceof CloseDoor.CloseThenContinue primitive && !(primitive.then() instanceof OnceOnALaterTurn)) {
            // The primitive's continuation receives one outcome, posted, whoever built its door (the stage-8
            // review N5): the door's own then is made once-only and posted, and handed the run's outcome too.
            return closeThenContinue(primitive.switchName(), primitive.then());
        }
        if (door instanceof CloseDoor.ApplicationStop) {
            applicationStopping();
            return CompletableFuture.completedFuture(new CloseOutcome.Closed());
        }
        if (protocolCompleted) {
            LOG.fine(() -> door.name() + " after the protocol ran: nothing more to close");
            return CompletableFuture.completedFuture(new CloseOutcome.Closed());
        }
        if (applicationStopped.get()) {
            LOG.fine(() -> door.name() + " refused: the application is stopping");
            return CompletableFuture.completedFuture(new CloseOutcome.Cancelled());
        }
        CloseRun current = running;
        if (current != null) {
            CompletionStage<CloseOutcome> merged;
            try {
                merged = merge(current, door);
            } catch (RuntimeException | Error failed) {
                stepFailed(current, failed);
                merged = current.outcome;
            }
            return merged;
        }
        if (door instanceof CloseDoor.CloseProject && !closeProjectAvailable()) {
            // Ruling R10: nothing to close. The menu item and the palette entry are disabled, and the shortcut does
            // nothing either (the studio engineer's stage-7 N1). Close Project only: the close-then-continue
            // primitive runs on a pristine Untitled project too (ruling 4 Q6.4).
            LOG.fine(() -> door.name() + ": nothing to close");
            return CompletableFuture.completedFuture(new CloseOutcome.Cancelled());
        }
        CloseRun run = new CloseRun(door);
        running = run;
        guarded(run, () -> {
            publish(run, ExitStep.INTERCEPT, StepResult.DONE, door.name());
            post(run, () -> guard(run));
        });
        return run.outcome;
    }

    /**
     * The close-then-continue primitive (design §1.8; rulings R1, R10; ruling
     * 4 Q6.4): closes the open project as Close Project does — the same
     * GUARD, the R1 confirmation with Close Project's header and R2's prompt,
     * {@code Keep project open}, its tail with no release wait and no HIDE —
     * then installs the Untitled project without the Welcome window, and only
     * then posts {@code then.accept(new Closed())}. Unlike Close Project it is
     * never refused for "nothing to close": on a pristine Untitled project it
     * runs every step, since a take can be recording and R1 guards the switch
     * that follows. {@code then} receives exactly one outcome, posted: the
     * keep, the Veto and every refusal give {@code Cancelled} — the refusals
     * name {@code switchName}; R9 and a step that throws give
     * {@code Failed(cause)}; an exit that upgraded it gives {@code Cancelled}
     * once the exit is certain, at DISPOSE, and {@code Closed} when Don't
     * quit returned it to the primitive and it completed. It has no
     * production caller yet (known limit 11): the project workspace switcher
     * will be one. FX thread.
     *
     * @param switchName the switch that follows the close, as the refusals and the primitive's texts name it
     * @param then       receives the close's one outcome, on a later FX turn
     */
    void closeOpenProjectThenContinue(String switchName, Consumer<CloseOutcome> then) {
        closeThenContinue(Objects.requireNonNull(switchName, "switchName must not be null"),
                Objects.requireNonNull(then, "then must not be null"));
    }

    /**
     * The primitive's run: {@code then} made once-only and posted
     * ({@link OnceOnALaterTurn}), refused once the application is closing,
     * else the run's door, and handed the run's outcome. FX thread.
     */
    private CompletionStage<CloseOutcome> closeThenContinue(String switchName, Consumer<CloseOutcome> then) {
        OnceOnALaterTurn once = new OnceOnALaterTurn(switchName, then);
        if (protocolCompleted || applicationStopped.get()) {
            LOG.fine(() -> switchName + " refused: the application is closing");
            once.accept(new CloseOutcome.Cancelled());
            return CompletableFuture.completedFuture(new CloseOutcome.Cancelled());
        }
        CompletionStage<CloseOutcome> outcome = request(new CloseDoor.CloseThenContinue(switchName, once));
        outcome.whenComplete((ended, failure) -> once.accept(ended != null ? ended : new CloseOutcome.Failed(failure)));
        return outcome;
    }

    /**
     * A continuation run once — the first outcome handed to it wins — and on
     * a later FX turn, never inside the close's own; a continuation that
     * throws is logged. Any thread.
     */
    private final class OnceOnALaterTurn implements Consumer<CloseOutcome> {
        private final String switchName;
        private final Consumer<CloseOutcome> then;
        private final AtomicBoolean delivered = new AtomicBoolean();

        OnceOnALaterTurn(String switchName, Consumer<CloseOutcome> then) {
            this.switchName = switchName;
            this.then = then;
        }

        @Override
        public void accept(CloseOutcome outcome) {
            if (!delivered.compareAndSet(false, true)) {
                return;
            }
            FxDispatcher.runOnFx(deps.dispatcher(), () -> {
                try {
                    then.accept(outcome);
                } catch (RuntimeException | Error failed) {
                    LOG.log(Level.SEVERE, failed, () -> "The continuation of " + switchName + " failed: "
                            + ProjectLifecycleController.failureReason(failed));
                }
            });
        }
    }

    /** Whether the window is input-inert: a close waits on disk work (design §1.5). FX thread. */
    boolean isWindowInert() {
        return deps.fence().isRaised();
    }

    /**
     * Whether the window is input-inert, as a read-only property whose one
     * writer is the {@link WindowInputFence}. FX thread.
     *
     * @return the property
     */
    ReadOnlyBooleanProperty windowInertProperty() {
        return deps.fence().raisedProperty();
    }

    /** Whether an exit ran the protocol to its end: HIDE ran. FX thread. */
    boolean protocolCompleted() {
        return protocolCompleted;
    }

    /**
     * Whether Close Project has anything to close (ruling R10): a project
     * folder is open, writable or read-only, or the in-memory project has
     * unsaved changes. FX thread.
     *
     * @return whether File ▸ Close Project is enabled
     */
    boolean closeProjectAvailable() {
        CloseReadiness readiness = deps.lifecycle().closeReadiness();
        return readiness.hasFolder() || readiness.dirty();
    }

    /**
     * Whether {@code window} is the running close's own progress surface,
     * which the window's disposal keeps until HIDE (ruling R12 #14). FX
     * thread.
     *
     * @param window a window
     * @return whether the close owns it
     */
    boolean ownsWindow(Window window) {
        CloseRun run = running;
        return run != null && run.surface != null && run.surface.owns(window);
    }

    // ── INTERCEPT: requests while a close runs (design §1.4) ─────────────────

    private CompletionStage<CloseOutcome> merge(CloseRun current, CloseDoor door) {
        if (!door.exitsApplication()) {
            LOG.fine(() -> door.name() + " refused: " + current.door.name() + " is closing the open project");
            return CompletableFuture.completedFuture(new CloseOutcome.Cancelled());
        }
        if (exits(current)) {
            if (current.currentWait == WaitKind.TAKE) {
                secondRequestWhileTheTakeIsWritten();
            } else {
                LOG.fine(() -> "A second exit request was merged into the running exit");
            }
            return current.outcome;
        }
        if (!current.releaseBegan) {
            current.exitRequested = true;
            LOG.fine(() -> "Close Project became an exit (ruling Q8)");
            refreshSurface(current);
            return current.outcome;
        }
        if (current.exitAfterRelease == null) {
            current.exitAfterRelease = door;
            current.exitAfterReleaseOutcome = new CompletableFuture<>();
            LOG.fine(() -> "An exit requested after Close Project released the folder runs once it ended");
        }
        return current.exitAfterReleaseOutcome;
    }

    /** A second exit while the take is written: one INFO per {@link #SECOND_REQUEST_WINDOW} (ruling R1). */
    private void secondRequestWhileTheTakeIsWritten() {
        long now = deps.nanoClock().getAsLong();
        String message = ProjectCloseText.text("close.take.secondRequest");
        if (secondRequestShown && now - lastSecondRequestNanos < SECOND_REQUEST_WINDOW.toNanos()) {
            LOG.fine(() -> "Said again: " + message);
            return;
        }
        secondRequestShown = true;
        lastSecondRequestNanos = now;
        LOG.info(message);
        deps.notifications().show(NotificationLevel.INFO, message);
    }

    // ── GUARD ───────────────────────────────────────────────────────────────

    /** GUARD's refusals and the take rule's confirmation (ruling R1's order, the studio engineer's S1). */
    private void guard(CloseRun run) {
        if (!isCurrent(run)) {
            return;
        }
        run.currentStep = ExitStep.GUARD;
        CloseReadiness readiness = deps.lifecycle().closeReadiness();
        run.projectName = readiness.projectName();
        PendingSwitch pending = readiness.pending();
        if (pending instanceof PendingSwitch.DoorDialogOpen || pending instanceof PendingSwitch.SaveAsChoosing) {
            deps.lifecycle().refuseWhileASwitchIsPending();
            refuse(run, pending.name() + "'s file dialog is open");
            return;
        }
        if (!run.door.exitsApplication() && !(pending instanceof PendingSwitch.None)) {
            if (pending instanceof PendingSwitch.Installing) {
                LOG.fine(() -> run.door.name() + " refused while a project is being installed");
            } else {
                deps.lifecycle().refuseWhileASwitchIsPending();
            }
            refuse(run, pending.name() + " is pending");
            return;
        }
        run.takePublicationsAtStart = deps.takes().takePublicationsWithClips();
        if (deps.takes().recordingInFlight()) {
            if (run.door.mayAsk()) {
                StopTakeAnswer answer = deps.answers().confirmStopTake(
                        ProjectClosePrompts.stopTakeHeader(exits(run), run.projectName));
                if (!isCurrent(run)) {
                    return;
                }
                if (answer != StopTakeAnswer.STOP_THE_TAKE) {
                    publish(run, ExitStep.GUARD, StepResult.CANCELLED, "Keep recording");
                    end(run, new CloseOutcome.Cancelled());
                    return;
                }
            }
            stopTransport();
            run.takeHandled = true;
        }
        if (exits(run)) {
            deps.lifecycle().cancelWaitingDoorForExit();
        }
        deps.fence().raise();
        deps.lifecycle().beginClosing(run.door.name());
        run.phase = Phase.WAITING;
        armClock(run);
        run.takeSubject = deps.takes().takeBeingWrittenProperty();
        subscribe(run, run.takeSubject);
        subscribe(run, deps.lifecycle().switchPendingProperty());
        // While the run is current its queued saves' lost lock is the run's, Quit anyway included: a save that
        // meets a steal during DISPOSE shows no dialog of its own (ruling 2 OQ-4).
        deps.lifecycle().whenItsSavesHaveReported(() -> isCurrent(run),
                report -> guarded(run, () -> {
                    // Quit anyway ended GUARD's waits: a report arriving during DISPOSE decides nothing (the
                    // stage-6 review B1).
                    if (isCurrent(run) && !run.quitting) {
                        run.savesReport = report;
                        rejudge(run);
                    }
                }));
        rejudge(run);
    }

    /** A refusal at GUARD's start: nothing ran. */
    private void refuse(CloseRun run, String why) {
        publish(run, ExitStep.GUARD, StepResult.CANCELLED, "refused: " + why);
        end(run, new CloseOutcome.Cancelled());
    }

    /**
     * GUARD's waits, judged again from the live facts each time one of them
     * may have changed: the take written, the queued saves reported, and —
     * for an exit — no other switch pending. A door waiting for a save is
     * replaced each time, so a door's own first save, which reads as its
     * folder being created first and as the door waiting after, is replaced
     * once it waits (stage-4 review N13).
     */
    private void rejudge(CloseRun run) {
        if (!isCurrent(run) || run.quitting || run.phase != Phase.WAITING) {
            return;
        }
        if (exits(run)) {
            deps.lifecycle().cancelWaitingDoorForExit();
        }
        boolean writing = run.takeSubject.get() || deps.takes().takeBeingWritten();
        if (writing) {
            run.takeHandled = true;
        }
        boolean switchPending = exits(run) && deps.lifecycle().switchPendingProperty().get();
        WaitKind wait = writing ? WaitKind.TAKE
                : run.savesReport == null ? WaitKind.SAVES
                : switchPending ? WaitKind.SWITCH
                : WaitKind.NONE;
        String switchName = wait == WaitKind.SWITCH ? deps.lifecycle().closeReadiness().pending().name() : "";
        setWait(run, wait, switchName);
        if (wait != WaitKind.NONE) {
            return;
        }
        run.phase = Phase.DECIDED;
        removeSubscriptions(run);
        decideAfterTheWaits(run);
    }

    /**
     * g3 and g4: a failed queued save, a lost lock, or the unsaved-changes
     * prompt (rulings R2, R9, OQ-4, S8). A queued save that failed stops the
     * close only while a head failure still stands once they all reported —
     * a later save of the project that landed made {@code project.daw}
     * current, so the close goes on, logged (ruling 4 Q5 (vi)). A loss
     * already known reads no lock: its holder is the known loss's, and a loss
     * known only from the lease's status has its own header (the studio
     * engineer's stage-6 S1).
     */
    private void decideAfterTheWaits(CloseRun run) {
        suspendClock(run);
        closeSurface(run);
        SavesReport report = run.savesReport;
        CloseReadiness readiness = deps.lifecycle().closeReadiness();
        run.projectName = readiness.projectName();
        if (report.lockLost().isPresent()) {
            if (readiness.dirty()) {
                takenOver(run, ExitStep.GUARD, report.lockLost().get().currentHolder(), false);
            } else {
                run.lockLost = true;
                decide(run, Decision.NOTHING_TO_SAVE, "the lock was lost; nothing to save");
            }
            return;
        }
        if (report.anyFailed()) {
            Optional<Throwable> standing = readiness.headFailure();
            if (standing.isPresent()) {
                Throwable cause = standing.get();
                publish(run, ExitStep.GUARD, StepResult.FAILED,
                        "a save queued before the close failed: " + ProjectLifecycleController.failureReason(cause));
                end(run, new CloseOutcome.Failed(cause));
                return;
            }
            LOG.info("A save of “" + run.projectName + "” queued before the close failed, and a later one"
                    + " succeeded; the close goes on.");
        }
        if (readiness.lockLost()) {
            if (!readiness.dirty()) {
                run.lockLost = true;
                decide(run, Decision.NOTHING_TO_SAVE, "the lock was already lost; nothing to save");
                return;
            }
            // Known facts only, no lock I/O at GUARD (the studio engineer's stage-4 S8 and stage-6 S1).
            Optional<LockStolenException> known = readiness.knownLockLoss();
            takenOver(run, ExitStep.GUARD, known.map(LockStolenException::currentHolder).orElse(null),
                    known.isEmpty());
            return;
        }
        if (!readiness.dirty()) {
            decide(run, Decision.NOTHING_TO_SAVE, "no unsaved changes");
            return;
        }
        UnsavedChangesAnswer answer = deps.answers().askBeforeClosing(
                deps.prompts().unsavedChanges(readiness, includesTheTake(run)));
        if (!isCurrent(run)) {
            return;
        }
        switch (answer) {
            case CANCEL -> {
                publish(run, ExitStep.GUARD, StepResult.CANCELLED, "Cancel: the project stays open");
                end(run, new CloseOutcome.Cancelled());
            }
            case DISCARD -> decide(run, Decision.DISCARD, "Discard and close");
            case SAVE -> decide(run, Decision.SAVE, "Save and close");
        }
    }

    /**
     * The exit's takeover dialog (ruling R7's exit variant): Save As… keeps
     * the work in a copy published beside the folder, Quit — or Close —
     * without saving leaves it, and Cancel keeps the project open. Shown at
     * GUARD for a loss already known or met by a queued save (rulings 2
     * OQ-4, stage-4 S8), and at FLUSH for one its own save met.
     *
     * @param holder                the lock found on disk, or {@code null}
     * @param knownOnlyFromTheLease whether the loss is known only from the lease's status (stage-6 S1)
     */
    private void takenOver(CloseRun run, ExitStep step, ProjectLock holder, boolean knownOnlyFromTheLease) {
        suspendClock(run);
        closeSurface(run);
        run.lockLost = true;
        TakenOverAnswer answer = deps.answers().takenOver(
                deps.prompts().takenOverAtClose(run.projectName, holder, knownOnlyFromTheLease, exits(run)));
        if (!isCurrent(run)) {
            return;
        }
        switch (answer) {
            case CANCEL -> {
                publish(run, step, StepResult.CANCELLED, "Cancel after the takeover: the project stays open");
                end(run, new CloseOutcome.Cancelled());
            }
            case WITHOUT_SAVING -> {
                if (step == ExitStep.GUARD) {
                    decide(run, Decision.DISCARD, "closed without saving: the lock was lost");
                } else {
                    run.decision = Decision.DISCARD;
                    publish(run, ExitStep.FLUSH, StepResult.SKIPPED, "closed without saving: the lock was lost");
                    closeFolder(run);
                }
            }
            case SAVE_AS -> {
                if (step == ExitStep.GUARD) {
                    decide(run, Decision.SAVE_AS_TAKEOVER, "Save As: the lock was lost");
                } else {
                    run.decision = Decision.SAVE_AS_TAKEOVER;
                    saveAsAtFlush(run, SaveAsFlow.Prefill.TAKEOVER);
                }
            }
        }
    }

    /** GUARD's decision: published, then FLUSH. */
    private void decide(CloseRun run, Decision decision, String detail) {
        run.decision = decision;
        publish(run, ExitStep.GUARD, StepResult.DONE, detail);
        flush(run);
    }

    // ── FLUSH ───────────────────────────────────────────────────────────────

    private void flush(CloseRun run) {
        run.currentStep = ExitStep.FLUSH;
        CloseReadiness readiness = deps.lifecycle().closeReadiness();
        switch (run.decision) {
            case SAVE -> {
                if (readiness.hasFolder() && readiness.readOnly()) {
                    saveAsAtFlush(run, SaveAsFlow.Prefill.COPY);
                    return;
                }
                CompletionStage<FlushResult> flushed = deps.lifecycle().saveBeforeClosing(() -> isCurrent(run),
                        () -> guarded(run, this::stopTransport));
                if (!isCurrent(run)) {
                    return;
                }
                armClock(run);
                setWait(run, WaitKind.FLUSH_SAVE, "");
                flushed.whenComplete((result, failure) -> post(run, () -> flushed(run, result, failure)));
            }
            case SAVE_AS_TAKEOVER -> saveAsAtFlush(run, SaveAsFlow.Prefill.TAKEOVER);
            case DISCARD, NOTHING_TO_SAVE -> {
                stopTransport();
                if (run.lockLost || !readiness.hasFolder() || readiness.readOnly()) {
                    publish(run, ExitStep.FLUSH, StepResult.SKIPPED, "nothing is saved");
                    closeFolder(run);
                    return;
                }
                armClock(run);
                setWait(run, WaitKind.LOCK_CHECK, "");
                deps.projectManager().whenLockChecked().whenComplete((steal, never) ->
                        post(run, () -> lockChecked(run, steal == null ? Optional.empty() : steal)));
            }
        }
    }

    /** FLUSH's save ended (rulings R7, R9). */
    private void flushed(CloseRun run, FlushResult result, Throwable failure) {
        if (!isCurrent(run) || run.quitting) {
            return;
        }
        FlushResult ended = result != null ? result
                : new FlushResult.SaveFailed(new SaveOutcome.Failed(failure, Optional.empty()));
        switch (ended) {
            case FlushResult.Saved _ -> {
                run.flushSaved = true;
                publish(run, ExitStep.FLUSH, StepResult.DONE, "saved");
                closeFolder(run);
            }
            case FlushResult.LockLost lost ->
                    takenOver(run, ExitStep.FLUSH, lost.stolen().currentHolder(), false);
            case FlushResult.SaveFailed notSaved -> {
                suspendClock(run);
                closeSurface(run);
                Throwable cause = notSaved.outcome().cause();
                publish(run, ExitStep.FLUSH, StepResult.FAILED, ProjectLifecycleController.failureReason(cause));
                end(run, new CloseOutcome.Failed(cause));
            }
            case FlushResult.NotRequested _ -> {
                publish(run, ExitStep.FLUSH, StepResult.CANCELLED, "no save was made: the chooser was cancelled");
                end(run, new CloseOutcome.Cancelled());
            }
        }
    }

    /**
     * The exit's Save As (rulings R2 read-only, R7): the copy is published
     * and remembered, not opened; the transport stops once the target is
     * chosen. Its chooser and its modal progress are the user's, so the
     * close's clock and surface are stopped meanwhile (ruling S3).
     */
    private void saveAsAtFlush(CloseRun run, SaveAsFlow.Prefill prefill) {
        run.saveAsAsked = true;
        run.currentStep = ExitStep.FLUSH;
        suspendClock(run);
        closeSurface(run);
        deps.saveAs().publishCopyBeforeClosing(prefill, () -> guarded(run, this::stopTransport))
                .whenComplete((result, failure) -> post(run, () -> savedAs(run, result, failure)));
    }

    private void savedAs(CloseRun run, SaveAsResult result, Throwable failure) {
        if (!isCurrent(run) || run.quitting) {
            return;
        }
        if (result instanceof SaveAsFlow.Published published) {
            publish(run, ExitStep.FLUSH, StepResult.DONE, "a copy was published: " + published.folder());
            closeFolder(run);
            return;
        }
        if (result instanceof SaveAsFlow.Failed failed) {
            publish(run, ExitStep.FLUSH, StepResult.FAILED, ProjectLifecycleController.failureReason(failed.cause()));
            end(run, new CloseOutcome.Failed(failed.cause()));
            return;
        }
        if (failure != null) {
            publish(run, ExitStep.FLUSH, StepResult.FAILED, ProjectLifecycleController.failureReason(failure));
            end(run, new CloseOutcome.Failed(failure));
            return;
        }
        // The chooser cancelled (no toast, ruling R7), or the copy cancelled (its INFO is the flow's).
        publish(run, ExitStep.FLUSH, StepResult.CANCELLED, "the Save As was cancelled");
        end(run, new CloseOutcome.Cancelled());
    }

    /** Discard or nothing to save: a folder another session took gets nothing written (R7, the studio engineer's N7). */
    private void lockChecked(CloseRun run, Optional<LockStolenException> steal) {
        if (!isCurrent(run) || run.quitting) {
            return;
        }
        if (steal.isPresent()) {
            run.lockLost = true;
            if (run.decision == Decision.DISCARD) {
                LOG.info("Discarded changes of “" + run.projectName + "” were not kept: another session holds its"
                        + " lock.");
            }
        }
        publish(run, ExitStep.FLUSH, StepResult.SKIPPED, run.decision == Decision.DISCARD ? "Discard and close"
                : "nothing to save");
        closeFolder(run);
    }

    // ── CLOSING_CHECKPOINT, SEAL, RELEASE ───────────────────────────────────

    /**
     * The tail: the closer runs with the decision's plan, under the wait
     * clock — every tail runs under the surface's clock (rulings R8, B3; the
     * stage-6 reviews' B1/B2): one that follows a prompt, a takeover dialog
     * or the exit's Save As starts it over (S3); one that waited on FLUSH
     * keeps FLUSH's clock, which is never restarted at RELEASE (ruling 4 Q1).
     */
    private void closeFolder(CloseRun run) {
        armClock(run);
        run.plan = plan(run);
        // Wanted while the run is not ended — also once the application-stop fallback took it over, which
        // joins the steps it began rather than start them again (ruling 4 Q6.2; the studio engineer's S8).
        run.closing = deps.folderCloser().close(run.plan, () -> run == running && !run.ended, stepsOf(run));
        run.closing.result().whenComplete((result, failure) -> post(run, () -> closeEnded(run, result, failure)));
    }

    /**
     * The plan of design §1.2's table, from the decision and the folder as it
     * is after FLUSH, by the door: Close Project's run always carries Close
     * Project's plan — no release wait — even when an exit upgraded it; the
     * exit it added waits for every writer after the release instead (D11),
     * so a Don't quit at any point leaves a plain Close Project (ruling 4 Q2).
     * A read-only folder gets nothing at all — no closing checkpoint, no
     * journal end — so nothing is written in it by construction (the studio
     * engineer's stage-6 N1).
     */
    private Plan plan(CloseRun run) {
        CloseReadiness readiness = deps.lifecycle().closeReadiness();
        boolean exits = run.door.exitsApplication();
        WriterScope writers = exits ? WriterScope.EVERY_WRITER : WriterScope.OPEN_GENERATION;
        if (run.lockLost) {
            return new Plan(ClosingCheckpoint.NONE, Optional.of(JournalEnd.DRAIN_WITHOUT_MANIFEST), writers, exits,
                    true);
        }
        if (readiness.hasFolder() && readiness.readOnly()) {
            return new Plan(ClosingCheckpoint.NONE, Optional.empty(), writers, exits, true);
        }
        if (!readiness.hasFolder()) {
            return new Plan(ClosingCheckpoint.NONE, Optional.empty(), writers, exits, true);
        }
        return switch (run.decision) {
            case DISCARD -> new Plan(ClosingCheckpoint.IF_RECORDS_OR_NOT_CHECKPOINTED, Optional.of(JournalEnd.SEAL),
                    writers, exits, true);
            case SAVE, SAVE_AS_TAKEOVER, NOTHING_TO_SAVE -> new Plan(ClosingCheckpoint.IF_RECORDS_OUTSIDE_A_CAPTURE,
                    Optional.of(JournalEnd.SEAL), writers, exits, false);
        };
    }

    /** What the closer tells the run, mapped onto the protocol's steps; each callback an entry of the run, guarded. */
    private ProjectFolderCloser.Steps stepsOf(CloseRun run) {
        return new ProjectFolderCloser.Steps() {
            @Override
            public void reached(ProjectFolderCloser.Step step) {
                guarded(run, () -> {
                    if (run.ended) {
                        return;
                    }
                    ExitStep exitStep = ExitStep.valueOf(step.name());
                    run.reached.add(exitStep);
                    run.currentStep = exitStep;
                    setWait(run, switch (step) {
                        case CLOSING_CHECKPOINT -> WaitKind.CHECKPOINT;
                        case SEAL -> WaitKind.JOURNAL;
                        case RELEASE -> WaitKind.RELEASE;
                    }, "");
                });
            }

            @Override
            public void outcome(ProjectFolderCloser.StepOutcome outcome) {
                guarded(run, () -> {
                    ExitStep step = ExitStep.valueOf(outcome.step().name());
                    // A step a failure already published FAILED is not told again as the skip's SKIPPED.
                    if (run.ended || (run.failing && run.published.contains(step))) {
                        return;
                    }
                    publish(run, step, StepResult.valueOf(outcome.result().name()), outcome.detail());
                });
            }

            @Override
            public void detached() {
                guarded(run, () -> {
                    if (run.ended) {
                        return;
                    }
                    run.detached = true;
                    run.detachTimer = deps.timers().after(KEEP_AFTER_DETACH_DELAY, () -> guarded(run, () -> {
                        if (isCurrent(run)) {
                            run.keepAfterDetachAllowed = true;
                            refreshSurface(run);
                        }
                    }));
                    refreshSurface(run);
                });
            }

            @Override
            public void releaseBegan() {
                guarded(run, () -> {
                    if (run.ended) {
                        return;
                    }
                    run.releaseBegan = true;
                    refreshSurface(run);
                });
            }
        };
    }

    /**
     * The closer ended: the tail, the seal failure's dialog, or nothing (a
     * skip, or a run that ended). A result that never came — the closer's
     * stage completed exceptionally — is a failure of the run (ruling 4 Q3).
     */
    private void closeEnded(CloseRun run, ProjectFolderCloser.Result result, Throwable failure) {
        if (!isCurrent(run) || run.quitting) {
            return;
        }
        if (result == null) {
            if (failure != null) {
                stepFailed(run, failure);
            }
            return;
        }
        switch (result) {
            case ProjectFolderCloser.Released released -> closed(run, released);
            case ProjectFolderCloser.SealFailed failed -> sealFailed(run, failed.cause());
            case ProjectFolderCloser.Stopped _ -> { }
        }
    }

    /**
     * The folder was released: the R3 log line after a Discard, then the
     * exit's DISPOSE or Close Project's Untitled. An exit that upgraded a
     * Close Project waits first for what Close Project's plan does not —
     * every writer idle, then the retiring leases released (D11) — under the
     * surface, with Don't quit and, at ten seconds, Quit anyway (ruling 4
     * Q2).
     */
    private void closed(CloseRun run, ProjectFolderCloser.Released released) {
        if (run.decision == Decision.DISCARD && released.closingCheckpoint().isPresent()) {
            LOG.info("Discarded changes of “" + run.projectName + "” were kept in checkpoint "
                    + released.closingCheckpoint().get().getFileName() + ".");
        }
        if (run.door.exitsApplication()) {
            dispose(run);
            return;
        }
        if (exits(run)) {
            run.awaitingExitRelease = true;
            setWait(run, WaitKind.RELEASE, "");
            refreshSurface(run);
            deps.projectManager().whenWritersIdle()
                    .thenCompose(_ -> deps.projectManager().whenRetiredLeasesReleased())
                    .whenComplete((_, _) -> post(run, () -> {
                        // A Don't quit meanwhile ended the run as Close Project, on its own turn.
                        if (isCurrent(run) && !run.quitting && exits(run)) {
                            dispose(run);
                        }
                    }));
            return;
        }
        closeProjectEnded(run);
    }

    /**
     * Close Project's end (ruling R10): the Untitled project installed, then
     * the run ended — the window live, the close no longer pending — and only
     * then, unless an exit runs next, the Welcome window, posted outside the
     * run and shown only if no close or project switch began before its turn
     * (the app-fix-2 review N5): a first-run wizard it opens is never fenced,
     * and a failure of it is logged only — the project was closed (ruling 4
     * Q3, the stage-7 review N9). The application stop is such a close: a
     * Welcome window opened inside one of its waits would hold that wait open
     * (the stage-8 review B1). The close-then-continue primitive's end is the
     * same with no Welcome window: its continuation, posted once the run
     * ended, is what follows (design §1.8).
     */
    private void closeProjectEnded(CloseRun run) {
        boolean exitNext = run.exitAfterRelease != null;
        deps.lifecycle().installUntitledAfterClose();
        end(run, new CloseOutcome.Closed());
        if (!exitNext && !(run.door instanceof CloseDoor.CloseThenContinue)) {
            FxDispatcher.runOnFx(deps.dispatcher(), () -> {
                try {
                    // The Welcome is the idle window's, as at launch: a close or a project switch begun since
                    // end() already decided what comes next (the app-fix-2 review N5).
                    if (running != null || protocolCompleted || applicationStopped.get()
                            || deps.lifecycle().switchPendingProperty().get()) {
                        LOG.fine("The Welcome window after Close Project was not shown: another close or a project"
                                + " switch began first");
                        return;
                    }
                    deps.lifecycle().showWelcomeAfterClose();
                } catch (RuntimeException | Error failed) {
                    LOG.log(Level.SEVERE, failed, () -> "The Welcome window after Close Project failed; the project"
                            + " was closed");
                }
            });
        }
    }

    /**
     * The journal could not be sealed (ruling R12 #6, Q4.6): nothing is still
     * writing, so the surface says so at once with Quit anyway — Close
     * anyway for Close Project — which releases the folder, its journal left
     * unsealed; the keep button keeps the project open, its journaling
     * paused.
     */
    private void sealFailed(CloseRun run, Throwable cause) {
        suspendClock(run);
        run.sealFailure = cause;
        run.surfaceDue = true;
        refreshSurface(run);
    }

    // ── DISPOSE, HIDE ───────────────────────────────────────────────────────

    private void dispose(CloseRun run) {
        run.disposeBegan = true;
        run.currentStep = ExitStep.DISPOSE;
        run.disposeStartedNanos = deps.nanoClock().getAsLong();
        run.sealFailure = null;
        if (run.door instanceof CloseDoor.CloseThenContinue primitive) {
            // An exit upgraded the primitive and is now certain: its switch will not follow (ruling 4 Q6.4, item 2).
            primitive.then().accept(new CloseOutcome.Cancelled());
        }
        boolean shown = run.surface != null && run.surface.isOpen();
        suspendClock(run);
        setWait(run, WaitKind.AUDIO, "");
        if (shown) {
            run.surfaceDue = true;
            refreshSurface(run);
        } else {
            armClock(run);
        }
        CompletionStage<Void> handOff;
        Throwable notDisposed = null;
        try {
            handOff = Objects.requireNonNull(deps.disposeWindow().get(), "the hand-off must not be null");
        } catch (RuntimeException | Error failed) {
            LOG.log(Level.SEVERE, failed, () -> "The window's disposal failed; the exit goes on");
            // The disposal started the engine's stop before its failure left it: HIDE still waits for that stop
            // (ruling 2 OQ-3; the studio engineer's stage-7 S1, the stage-7 review S3).
            notDisposed = failed;
            handOff = handOffStartedDespiteTheFailure();
        }
        run.handOff = handOff;
        // Published once the disposal's outcome is known: an observer that throws is a step that failed, never
        // the disposal's failure, and asks for no second disposal (the app-fix-2 review N3).
        if (notDisposed == null) {
            publish(run, ExitStep.DISPOSE, StepResult.DONE, "the window's lifetime was disposed; the audio stops");
        } else {
            publish(run, ExitStep.DISPOSE, StepResult.FAILED, ProjectLifecycleController.failureReason(notDisposed));
        }
        hideOnceTheEngineStopped(run, handOff);
    }

    /**
     * The engine's hand-off a failed disposal still started: the window's
     * disposal starts it in a {@code finally} and hands it back on a second
     * call (MainController.disposeWindowLifetime), so HIDE still waits for
     * the audio device's stop, at most {@link #ENGINE_STOP_BOUND} (ruling 2
     * OQ-3). None when the second call fails too. FX thread.
     */
    private CompletionStage<Void> handOffStartedDespiteTheFailure() {
        try {
            CompletionStage<Void> started = deps.disposeWindow().get();
            return started != null ? started : CompletableFuture.completedFuture(null);
        } catch (RuntimeException | Error again) {
            LOG.log(Level.FINE, again, () -> "The failed disposal started no hand-off");
            return CompletableFuture.completedFuture(null);
        }
    }

    /** HIDE once {@code handOff} ended or after {@link #ENGINE_STOP_BOUND}, whichever is first; armed once. */
    private void hideOnceTheEngineStopped(CloseRun run, CompletionStage<Void> handOff) {
        if (run.hideTimer != null) {
            return;
        }
        run.hideTimer = deps.timers().after(ENGINE_STOP_BOUND, () -> guarded(run, () -> hide(run,
                "the audio engine was still stopping after " + ENGINE_STOP_BOUND.toSeconds() + " s")));
        handOff.whenComplete((_, _) -> post(run, () -> hide(run, "the audio engine stopped")));
    }

    /**
     * HIDE, once: after the engine's hand-off or its bound, whichever is
     * first; then the terminal exit. Each cleanup is guarded, so a failing
     * one never keeps the window or the run (ruling 4 Q3); HIDE is published
     * before the protocol counts as completed and the terminal exit runs.
     */
    private void hide(CloseRun run, String why) {
        if (run != running || run.ended || run.hidden || run.takenOver) {
            return;
        }
        run.hidden = true;
        // Kept for Application.stop(), which waits for the engine's stop at most until its start plus
        // ENGINE_STOP_AFTER_HIDE_BOUND (ruling 2 OQ-3, constraint 1).
        completedHandOff = run.handOff != null ? run.handOff : CompletableFuture.completedFuture(null);
        completedHandOffStartNanos = run.disposeStartedNanos;
        quietly(() -> suspendClock(run));
        quietly(() -> cancel(run.hideTimer));
        quietly(() -> cancel(run.detachTimer));
        quietly(() -> closeSurface(run));
        quietly(() -> removeSubscriptions(run));
        quietly(() -> removeTakeOperation(run));
        quietly(deps.fence()::lower);
        if (primaryStage != null) {
            quietly(primaryStage::hide);
        }
        quietly(() -> publish(run, ExitStep.HIDE, StepResult.DONE, why));
        protocolCompleted = true;
        running = null;
        run.ended = true;
        run.outcome.complete(new CloseOutcome.Closed());
        deps.terminalExit().run();
    }

    // ── keep, Quit anyway, Close anyway ─────────────────────────────────────

    /**
     * The keep button, Escape or the close box (rulings R1, R8, Q4, Q8; ruling
     * 4 Q1 and Q2). An upgraded Close Project's {@code Don't quit} drops only
     * the exit — during D11's wait it ends the run as the Close Project it
     * was, at once. Before the detach the close ends, nothing written; after
     * it the tail is skipped, its journal drained unsealed, once the ten
     * seconds passed — at once when the plan has no journal end, whose
     * keeping costs nothing.
     */
    private void keep(CloseRun run) {
        if (!isCurrent(run) || run.quitting) {
            return;
        }
        if (upgraded(run)) {
            // Ruling Q8: Don't quit cancels only the exit; the close goes on as the Close Project it was.
            run.exitRequested = false;
            if (run.awaitingExitRelease) {
                // Ruling 4 Q2: D11's wait was the exit's alone; Close Project's own close has ended.
                run.awaitingExitRelease = false;
                LOG.fine(() -> "Don't quit: Close Project ends without the exit's wait for every writer");
                closeProjectEnded(run);
                return;
            }
            LOG.fine(() -> "Don't quit: the close goes on as Close Project");
            refreshSurface(run);
            return;
        }
        if (run.sealFailure != null) {
            warnKeptOpen(run, "close.cancelled.sealed.");
            end(run, new CloseOutcome.Cancelled());
            return;
        }
        if (run.releaseBegan) {
            return;
        }
        if (!run.detached) {
            publish(run, run.currentStep, StepResult.CANCELLED, keepLabel(run) + ": the project stays open");
            end(run, new CloseOutcome.Cancelled());
            return;
        }
        boolean anyJournal = run.plan.journalEnd().isPresent();
        if (anyJournal && !run.keepAfterDetachAllowed) {
            return;
        }
        boolean sealHadBegun = run.closing.journalEndBegan();
        run.closing.skipRemaining(drainMode(run));
        skipTheStepsLeft(run, "kept open after the journal's detach");
        if (anyJournal) {
            warnKeptOpen(run, keptOpenPrefix(run, sealHadBegun));
        }
        end(run, new CloseOutcome.Cancelled());
    }

    /**
     * Which WARNING a close kept open after the detach shows (ruling Q4):
     * drained — recovery evidence preserved — unless its seal had begun, or
     * the lock was lost, whose drain wrote no manifest into a folder another
     * session holds, so nothing more than paused journaling is true (ruling 4
     * Q1, refinement 2).
     */
    private static String keptOpenPrefix(CloseRun run, boolean sealHadBegun) {
        return sealHadBegun || run.lockLost ? "close.cancelled.sealed." : "close.cancelled.drained.";
    }

    /** The WARNING of a close kept open after the detach (ruling Q4): its journaling is paused. */
    private void warnKeptOpen(CloseRun run, String keyPrefix) {
        String message = keptOpenText(run, keyPrefix);
        LOG.warning(message);
        deps.notifications().show(NotificationLevel.WARNING, message);
    }

    /**
     * The text of a close kept open after the detach: the exit's, Close
     * Project's, or the close-then-continue primitive's, which names its
     * switch — "Close Project cancelled…" would be false for it (ruling 4
     * Q6.4, item 3).
     */
    private static String keptOpenText(CloseRun run, String keyPrefix) {
        if (exits(run)) {
            return ProjectCloseText.text(keyPrefix + "exit");
        }
        if (run.door instanceof CloseDoor.CloseThenContinue primitive) {
            return ProjectCloseText.text(keyPrefix + "switch", primitive.switchName());
        }
        return ProjectCloseText.text(keyPrefix + "project");
    }

    /** The note's button: Quit anyway (R8), or — after a seal failed — Quit anyway or Close anyway (Q4.6). */
    private void offer(CloseRun run) {
        if (!isCurrent(run) || run.quitting) {
            return;
        }
        if (run.sealFailure != null) {
            Throwable cause = run.sealFailure;
            run.sealFailure = null;
            LOG.log(Level.WARNING, cause, () -> "The close goes on past a journal it could not seal");
            refreshSurface(run);
            armClock(run);
            run.closing.releaseAnyway().whenComplete((result, failure) ->
                    post(run, () -> closeEnded(run, result, failure)));
            return;
        }
        if (exits(run)) {
            quitAnyway(run);
        }
    }

    /**
     * Quit anyway (ruling R8): GUARD's waits end first — a save report, a
     * take's publication or a switch's end arriving during DISPOSE decides
     * nothing (the stage-6 review B1) — then the steps not begun are skipped
     * — a journal not yet ending is drained unsealed, best effort, on a
     * virtual thread nobody waits for; a seal already running is never
     * interrupted — and the lock is left, as after a crash; then DISPOSE and
     * HIDE.
     */
    private void quitAnyway(CloseRun run) {
        run.quitting = true;
        run.phase = Phase.DECIDED;
        removeSubscriptions(run);
        LOG.warning("Quit anyway: the exit skips what it has not done; the next open may offer to recover this"
                + " session");
        if (run.closing == null) {
            if (!run.published.contains(ExitStep.GUARD)) {
                publish(run, ExitStep.GUARD, StepResult.SKIPPED, "Quit anyway");
            }
            if (!run.published.contains(ExitStep.FLUSH)) {
                publish(run, ExitStep.FLUSH, StepResult.SKIPPED, "Quit anyway");
            }
            // The tail's own skip: every step SKIPPED, and its best-effort drain of an open journal.
            run.plan = new Plan(ClosingCheckpoint.NONE, Optional.of(JournalEnd.SEAL), WriterScope.EVERY_WRITER,
                    false, true);
            run.closing = deps.folderCloser().close(run.plan, () -> isCurrent(run), stepsOf(run));
        }
        run.closing.skipRemaining(drainMode(run));
        skipTheStepsLeft(run, "Quit anyway");
        dispose(run);
    }

    /** A step the closer had begun without telling its end — a running seal, the release's wait — is SKIPPED. */
    private void skipTheStepsLeft(CloseRun run, String why) {
        for (ExitStep step : List.of(ExitStep.CLOSING_CHECKPOINT, ExitStep.SEAL, ExitStep.RELEASE)) {
            if (!run.published.contains(step)) {
                publish(run, step, StepResult.SKIPPED, why + (run.reached.contains(step) ? ": not waited for" : ""));
            }
        }
    }

    /**
     * How a journal is drained when the close stops before its end: never a
     * manifest in a folder another session holds — a loss the run decided,
     * one already known before the decision, or one a queued save reported
     * (the studio engineer's stage-6 N2).
     */
    private JournalEnd drainMode(CloseRun run) {
        boolean lost = run.lockLost || deps.lifecycle().closeReadiness().lockLost()
                || (run.savesReport != null && run.savesReport.lockLost().isPresent());
        return lost ? JournalEnd.DRAIN_WITHOUT_MANIFEST : JournalEnd.DRAIN_UNSEALED;
    }

    // ── the clock and the surface (rulings R8, S2, S3, Q4, OQ-3) ────────────

    /** Starts the wait clock, unless it runs: the surface at 500 ms, the note at 10 s. */
    private void armClock(CloseRun run) {
        if (run.clockArmed) {
            return;
        }
        run.clockArmed = true;
        long token = ++run.clockEpoch;
        // A surface already showing stays: only its note waits for the new clock's ten seconds.
        run.surfaceDue = run.surface != null && run.surface.isOpen();
        run.noteDue = false;
        run.clockTimers.add(deps.timers().after(SURFACE_DELAY, () -> guarded(run, () -> {
            if (isCurrent(run) && token == run.clockEpoch) {
                run.surfaceDue = true;
                refreshSurface(run);
            }
        })));
        run.clockTimers.add(deps.timers().after(NOTE_DELAY, () -> guarded(run, () -> {
            if (isCurrent(run) && token == run.clockEpoch) {
                run.noteDue = true;
                refreshSurface(run);
            }
        })));
    }

    /** Stops the wait clock: a prompt or a decision follows; the next wait starts it over (ruling S3). */
    private void suspendClock(CloseRun run) {
        run.clockArmed = false;
        run.clockEpoch++;
        run.clockTimers.forEach(CloseTimers.Cancellable::cancel);
        run.clockTimers.clear();
        run.surfaceDue = false;
        run.noteDue = false;
    }

    private void setWait(CloseRun run, WaitKind wait, String switchName) {
        if (run.currentWait == wait && run.switchName.equals(switchName)) {
            return;
        }
        run.currentWait = wait;
        run.switchName = switchName;
        refreshSurface(run);
    }

    /**
     * The surface as the run stands: shown once due, its step, keep button
     * and note following the current wait. The keep button is gone once the
     * release began (Q4.5) and disabled in the ten seconds after the detach
     * (Q4.2) — but for a plan with no journal end, whose detach costs nothing
     * (ruling 4 Q1, refinement 1), and for an upgraded Close Project, whose
     * {@code Don't quit} only drops the exit (ruling 4 Q2). A Close Project
     * note offers the keep button, so it shows only while that button can be
     * pressed (the studio engineer's stage-6 S3).
     */
    private void refreshSurface(CloseRun run) {
        if (run.ended) {
            return;
        }
        updateTakeOperation(run);
        if (!run.surfaceDue) {
            return;
        }
        ClosingProgress surface = surfaceOf(run);
        String step = stepLine(run);
        surface.open(ProjectCloseText.text("close.progress.title", run.projectName), step, keepLabel(run));
        surface.step(step);
        surface.keepLabel(keepLabel(run));
        boolean audio = run.currentWait == WaitKind.AUDIO;
        boolean upgraded = upgraded(run);
        surface.keepShown(run.sealFailure != null || (!audio && (!run.releaseBegan || upgraded)));
        boolean keepUsable = !run.detached || run.keepAfterDetachAllowed || run.plan == null
                || run.plan.journalEnd().isEmpty();
        surface.keepEnabled(run.sealFailure != null || upgraded || keepUsable);
        if (run.sealFailure != null) {
            surface.note(ProjectClosePrompts.sealFailedNote(run.sealFailure, exits(run)),
                    exits(run) ? ClosingProgress.Offer.QUIT_ANYWAY : ClosingProgress.Offer.CLOSE_ANYWAY);
        } else if (run.noteDue && !audio && (exits(run) || keepUsable)) {
            surface.note(noteText(run), offerOf(run));
        } else {
            surface.note("", ClosingProgress.Offer.NONE);
        }
    }

    private ClosingProgress surfaceOf(CloseRun run) {
        if (run.surface == null) {
            run.surface = Objects.requireNonNull(deps.progressSurface().get(), "the progress surface must not be null");
            run.surface.onKeep(() -> guarded(run, () -> keep(run)));
            run.surface.onOffer(() -> guarded(run, () -> offer(run)));
        }
        return run.surface;
    }

    private void closeSurface(CloseRun run) {
        if (run.surface != null) {
            run.surface.closeByProtocol();
            run.surface = null;
        }
    }

    private String stepLine(CloseRun run) {
        return switch (run.currentWait) {
            case TAKE -> ProjectCloseText.text("close.step.take");
            case SAVES, FLUSH_SAVE -> ProjectCloseText.text("close.step.saving");
            case SWITCH -> ProjectCloseText.text("close.step.switch", run.switchName);
            case LOCK_CHECK, CHECKPOINT -> ProjectCloseText.text("close.step.checkpoint");
            case NONE, JOURNAL -> ProjectCloseText.text("close.step.journal");
            // Every writer and the retiring leases: the journal is sealed or there is none (stage-6 S6).
            case RELEASE -> ProjectCloseText.text("close.step.release");
            case AUDIO -> ProjectCloseText.text("close.step.audio");
        };
    }

    private String keepLabel(CloseRun run) {
        return ProjectCloseText.text(exits(run) ? "close.keep.exit" : "close.keep.project");
    }

    /**
     * The ten-second note of the current wait and the decision (ruling S2,
     * the stage-4 S2 amendment, ruling 4 Q1): the note follows what is still
     * running. At RELEASE nothing of the closing folder is written — its
     * checkpoint and journal have ended — so an exit's note is
     * {@code close.note.release}, and Close Project, which never waits there,
     * has none; under a lost lock the folder's last writes are another
     * session's folder's, {@code close.note.takenOver} (or its
     * {@code .project} variant); the lock's own read keeps its note, the loss
     * not being known yet.
     */
    private String noteText(CloseRun run) {
        boolean exits = exits(run);
        String suffix = exits ? "" : ".project";
        return switch (run.currentWait) {
            case TAKE -> ProjectCloseText.text(exits ? "close.note.take.exit" : "close.note.take.project");
            case SAVES, FLUSH_SAVE -> ProjectCloseText.text("close.note.saveNotFinished" + suffix);
            case SWITCH -> ProjectClosePrompts.switchNote(run.switchName);
            case LOCK_CHECK -> ProjectCloseText.text(
                    (run.flushSaved ? "close.note.saveLanded" : "close.note.notSaved") + suffix);
            case NONE, CHECKPOINT, JOURNAL -> run.lockLost
                    ? ProjectCloseText.text("close.note.takenOver" + suffix, run.projectName)
                    : ProjectCloseText.text((run.flushSaved ? "close.note.saveLanded" : "close.note.notSaved") + suffix);
            case RELEASE -> exits ? ProjectCloseText.text("close.note.release", run.projectName) : "";
            case AUDIO -> "";
        };
    }

    /** Quit anyway for an exit, after ten seconds, but never during the take wait (rulings R1, R12 #9). */
    private ClosingProgress.Offer offerOf(CloseRun run) {
        return exits(run) && run.currentWait != WaitKind.TAKE && run.currentWait != WaitKind.AUDIO
                ? ClosingProgress.Offer.QUIT_ANYWAY : ClosingProgress.Offer.NONE;
    }

    /** "Finishing take…" on the strip once the take wait has lasted half a second (ruling R1). */
    private void updateTakeOperation(CloseRun run) {
        if (run.currentWait == WaitKind.TAKE && run.surfaceDue) {
            if (run.takeOperation == null) {
                run.takeOperation = new ProjectOperationProgress.NamedOperation(ProjectCloseText.text("close.step.take"));
                deps.strip().addOperation(run.takeOperation);
            }
        } else {
            removeTakeOperation(run);
        }
    }

    private void removeTakeOperation(CloseRun run) {
        if (run.takeOperation != null) {
            deps.strip().removeOperation(run.takeOperation);
            run.takeOperation = null;
        }
    }

    // ── the application-stop fallback (ruling R4; ruling 2 OQ-3; ruling 4 Q6) ─

    /**
     * {@code Application.stop()}'s part of the protocol (design §1.7; ruling
     * R4; ruling 2 OQ-3; ruling 4 Q6), run once. The toolkit calls it on the
     * FX thread and its launcher waits there until it returns ({@code
     * runAndWait}), so the JVM does not end under it — and every wait here is
     * a {@link NestedWait}, a nested event loop with a deadline, in which the
     * toolkit's posts keep running:
     * <ul>
     *   <li><b>After the protocol ran</b> (HIDE), on the FX thread: one wait
     *       for DISPOSE's hand-off, the audio engine's stop, at most until
     *       its start plus {@link #ENGINE_STOP_AFTER_HIDE_BOUND}, logged when
     *       that passes; no step, nothing published (ruling 2 OQ-3,
     *       constraint 1). Off the FX thread it returns at once: no nested
     *       loop can wait there.</li>
     *   <li><b>Otherwise, the fallback</b>: it asks nothing, shows nothing
     *       and never writes {@code project.daw}. An interactive close is
     *       fenced — its own continuations do nothing more — and taken over:
     *       past DISPOSE it only waits for that DISPOSE's hand-off, even when
     *       the fallback throws; decided, its decision stands and the steps
     *       it began are joined, never begun again (the studio engineer's S8;
     *       ruling 4 Q6.2, item 1); undecided, its state is dropped. First
     *       the transport is stopped — a recording, which it cannot ask
     *       about, and playback alike (ruling R4 step 1, the studio
     *       engineer's stage-8 S2) — and a lost lock that a save meets shows
     *       no takeover dialog while a close is pending (the studio
     *       engineer's stage-8 S3 and final ruling F1). While a Save As
     *       copies or a project is installed, the switch owns the open
     *       folder: no closer runs, the tail is skipped, as after a crash
     *       (the studio engineer's stage-8 S4). The fallback's own close: the
     *       take waited for, every writer idle, then the closer with design
     *       §1.2's last row — the closing checkpoint when a record lies
     *       outside every capture or the project has unsaved changes, the
     *       journal sealed when it has none and drained unsealed when it has,
     *       so the next open offers to recover them; nothing written into a
     *       read-only folder, one never saved, or one whose lock another
     *       session holds — and the release, every writer and lease awaited.
     *       All of it within {@link ProjectManager#EXIT_WRITE_WAIT} of the
     *       stop: a take or a writer still at it then skips the rest, as
     *       after a crash, with the journal drained, best effort. A step of
     *       its own that throws is logged and skips the rest of the tail the
     *       same way (the stage-8 review S1) — with no closer and no drain
     *       while a switch owns the open folder (the studio engineer's final
     *       ruling F11). Then DISPOSE, its hand-off waited for at most
     *       {@link #ENGINE_STOP_AFTER_HIDE_BOUND} from its start. It
     *       publishes GUARD, CLOSING_CHECKPOINT, SEAL, RELEASE and DISPOSE,
     *       never HIDE: the stage has hidden. Its log is the record
     *       (ruling 4 Q6.3).</li>
     *   <li><b>Off the FX thread, or where no nested loop can start now</b>:
     *       the defensive branch, unreachable in production
     *       ({@link #stopOnTheFallbacksThread}).</li>
     * </ul>
     * Never throws. Any thread.
     */
    void applicationStopping() {
        if (!applicationStopped.compareAndSet(false, true)) {
            LOG.fine("The application stop was asked again: it runs once");
            return;
        }
        boolean onFx = Platform.isFxApplicationThread();
        try {
            if (protocolCompleted) {
                if (onFx) {
                    awaitTheHandOff(completedHandOff,
                            completedHandOffStartNanos + ENGINE_STOP_AFTER_HIDE_BOUND.toNanos(),
                            ENGINE_STOP_AFTER_HIDE_BOUND);
                }
                return;
            }
            if (!onFx || !deps.nestedLoops().canNest()) {
                stopOnTheFallbacksThread(onFx);
                return;
            }
            stopInNestedLoops();
        } catch (RuntimeException | Error failed) {
            LOG.log(Level.SEVERE, failed, () -> "The application-stop fallback failed: "
                    + ProjectLifecycleController.failureReason(failed));
        }
    }

    /**
     * The fallback's close, its waits nested loops on the FX thread (design
     * §1.7, steps 2-8). A run past DISPOSE gets no step and no DISPOSE again
     * — nothing captures a project once DISPOSE began, whoever began it
     * (ruling 4 Q6.2 item 1's last row, as the studio engineer's ruling C3 on
     * app fix pass 2's Java items amends it) — and that DISPOSE's hand-off is
     * waited for even when the fallback throws (the studio engineer's final
     * ruling F11). Otherwise DISPOSE follows the close whatever it threw: a
     * throw is logged {@code SEVERE} and skips the rest of the tail, as after
     * a crash — with no closer and no drain while a switch owns the open
     * folder (final ruling F11) — so the audio engine is still stopped
     * (ruling R4 step 6; the stage-8 review S1). FX thread.
     */
    private void stopInNestedLoops() {
        CloseRun interactive = running;
        CloseRun stop = new CloseRun(new CloseDoor.ApplicationStop());
        long deadline = deps.nanoClock().getAsLong() + ProjectManager.EXIT_WRITE_WAIT.toNanos();
        boolean pastDispose = interactive != null && interactive.disposeBegan;
        try {
            if (interactive != null) {
                takeOver(interactive);
            }
            try {
                stop.projectName = deps.lifecycle().closeReadiness().projectName();
                if (interactive != null) {
                    LOG.warning(doorName(interactive) + " was still closing “" + stop.projectName + "” when the"
                            + " application stopped; the application-stop fallback takes it over.");
                }
                if (pastDispose) {
                    // Past RELEASE: no step; DISPOSE ran once, its own hand-off waited for (ruling 4 Q6.2, item 1).
                    awaitTheHandOff(interactive.handOff,
                            interactive.disposeStartedNanos + ENGINE_STOP_AFTER_HIDE_BOUND.toNanos(),
                            ENGINE_STOP_AFTER_HIDE_BOUND);
                    return;
                }
                closeAtTheStop(interactive, stop, deadline);
            } catch (RuntimeException | Error failed) {
                LOG.log(Level.SEVERE, failed, () -> "The application-stop fallback failed: "
                        + ProjectLifecycleController.failureReason(failed));
                if (pastDispose) {
                    // The engine's stop began: its hand-off is still waited for (ruling 2 OQ-3; the studio engineer's
                    // stage-8 S1 and final ruling F11).
                    quietly(() -> awaitTheHandOff(interactive.handOff,
                            interactive.disposeStartedNanos + ENGINE_STOP_AFTER_HIDE_BOUND.toNanos(),
                            ENGINE_STOP_AFTER_HIDE_BOUND));
                } else {
                    // While a switch owns the open folder no closer runs and nothing is drained (the studio
                    // engineer's stage-8 S4 and final ruling F11).
                    quietly(() -> {
                        if (aSwitchOwnsTheOpenFolder()) {
                            skipTheStepsLeft(stop, "the application-stop fallback failed");
                        } else {
                            skipTheTail(interactive != null && interactive.closing != null ? interactive : stop,
                                    "the application-stop fallback failed");
                        }
                    });
                }
            }
            if (!pastDispose) {
                disposeAtTheStop(stop);
            }
        } finally {
            if (interactive != null) {
                retireTheTakenOver(interactive);
            }
        }
    }

    /**
     * The close the fallback runs before its DISPOSE: L1, the window inert
     * and the close pending, the transport stopped, then — unless a switch is
     * replacing the open project — the decided close joined or the
     * fallback's own. FX thread.
     */
    private void closeAtTheStop(CloseRun interactive, CloseRun stop, long deadline) {
        LOG.warning("The application is stopping without the exit protocol; the application-stop fallback asks"
                + " nothing more about “" + stop.projectName + "” and starts no save of its own.");
        deps.fence().raise();
        deps.lifecycle().beginClosing(stop.door.name());
        deps.lifecycle().cancelWaitingDoorForExit();
        // Ruling R4 step 1 (the studio engineer's stage-8 S2): the transport stopped at once — a recording,
        // which it cannot ask about (R1), and playback alike — so nothing sounds, and nothing is captured
        // playing, while the fallback waits.
        boolean recording = deps.takes().recordingInFlight();
        quietly(this::stopTransport);
        if (recording) {
            LOG.warning("A recording was in progress when the application stopped; it was stopped.");
        }
        PendingSwitch replacing = deps.lifecycle().closeReadiness().pending();
        if (ownsTheOpenFolder(replacing)) {
            // The switch owns the open folder's journal and lock: a second closer could seal or checkpoint
            // into the wrong folder (the studio engineer's S8; stage-8 S4).
            LOG.warning(replacing.name() + " was still replacing “" + stop.projectName + "” when the"
                    + " application stopped; the closing checkpoint, the seal and the release are skipped, as"
                    + " after a crash.");
            skipTheStepsLeft(stop, replacing.name() + " was still replacing the open project");
        } else if (interactive != null && interactive.decision != null) {
            joinTheDecidedClose(interactive, stop, deadline);
        } else {
            closeAsTheFallback(stop, recording, deadline);
        }
    }

    /**
     * Whether a switch owns the open folder's journal and lock now — a Save
     * As copying or adopting its copy, or a project being installed — so the
     * fallback runs no closer and drains nothing, its catch included (the
     * studio engineer's stage-8 S4 and final ruling F11). FX thread.
     */
    private boolean aSwitchOwnsTheOpenFolder() {
        return ownsTheOpenFolder(deps.lifecycle().closeReadiness().pending());
    }

    /** {@link #aSwitchOwnsTheOpenFolder()}'s rule, for a pending switch already read. */
    private static boolean ownsTheOpenFolder(PendingSwitch pending) {
        return pending instanceof PendingSwitch.Installing || pending instanceof PendingSwitch.SaveAsCopying;
    }

    /**
     * The fallback's own close, on the project as it is (design §1.7 steps
     * 3-7; ruling R4): the take — a recording was stopped at once, it cannot
     * ask (R1) — every writer, the plan and the closer, each waited for
     * within {@code deadline}.
     *
     * @param recording whether a recording was in flight when the fallback stopped the transport
     */
    private void closeAsTheFallback(CloseRun stop, boolean recording, long deadline) {
        stop.currentStep = ExitStep.GUARD;
        boolean writing = recording || deps.takes().takeBeingWritten();
        Waited take = awaitTheTake(deadline);
        if (take != Waited.ENDED) {
            if (take == Waited.PAST_THE_DEADLINE) {
                LOG.warning("The take was still being written " + EXIT_WRITE_WAIT_SECONDS + " seconds after the"
                        + " application stopped; the closing checkpoint, the seal and the release are skipped, as"
                        + " after a crash.");
            }
            publish(stop, ExitStep.GUARD, StepResult.FAILED, "the take was still being written");
            skipTheTail(stop, "the take was still being written");
            return;
        }
        publish(stop, ExitStep.GUARD, StepResult.DONE, recording ? "the recording was stopped and its take written"
                : writing ? "the take was written" : "nothing was recording");
        if (!everyWriterIdle(stop, deadline)) {
            return;
        }
        Optional<StopPlan> plan = planAtTheStop(stop, null, false, deadline);
        if (plan.isPresent()) {
            closeAsPlanned(stop, plan.get(), deadline);
        }
    }

    /**
     * A close decided before the stop (ruling 4 Q6.2, item 1): its decision
     * stands. With its closer running, the fallback joins it — waits for the
     * step it began, a SEAL or a RELEASE included, and never begins one
     * again (the studio engineer's S8, FEAS-S10); on its seal-failure
     * surface it goes on as Quit or Close anyway would, the lock released
     * and the journal left unsealed; a close whose plan did not wait for the
     * release (Close Project, the primitive) then waits for every writer and
     * the retired leases, as an exit does. With no closer yet — its save,
     * its lock's read or its takeover dialog under way — the writers are
     * waited for, then its decision's row of design §1.2's table runs — the
     * fallback's own row once its save left the project with unsaved
     * changes, since nothing can stop as R9 would.
     */
    private void joinTheDecidedClose(CloseRun interactive, CloseRun stop, long deadline) {
        ProjectFolderCloser.Closing closing = interactive.closing;
        if (closing == null) {
            if (!everyWriterIdle(stop, deadline)) {
                return;
            }
            stop.saveAsAsked = interactive.saveAsAsked;
            Optional<StopPlan> plan = planAtTheStop(stop, interactive.decision, interactive.lockLost, deadline);
            if (plan.isPresent()) {
                closeAsPlanned(stop, plan.get(), deadline);
            }
            return;
        }
        CompletableFuture<ProjectFolderCloser.Result> result = closing.result().toCompletableFuture();
        while (true) {
            Waited ended = new NestedWait("the close it took over").until(result, deadline);
            if (ended != Waited.ENDED) {
                pastTheDeadline(ended, stop);
                closing.skipRemaining(drainMode(interactive));
                skipTheStepsLeft(interactive, "the application stopped");
                return;
            }
            if (result.isCompletedExceptionally()) {
                // Its closer threw (it logged it SEVERE): what it had not begun is skipped, its journal drained.
                closing.skipRemaining(drainMode(interactive));
                skipTheStepsLeft(interactive, "the close failed");
                return;
            }
            if (!(result.resultNow() instanceof ProjectFolderCloser.SealFailed)) {
                break;
            }
            // Its seal failed (ruling R12 #6): on as Quit or Close anyway, the journal left unsealed.
            interactive.sealFailure = null;
            result = closing.releaseAnyway().toCompletableFuture();
        }
        // R3's line, unless the run's own closed() logged it: an upgraded Close Project in D11's wait (the stage-8
        // review N1).
        if (result.resultNow() instanceof ProjectFolderCloser.Released released && !interactive.awaitingExitRelease
                && interactive.decision == Decision.DISCARD && released.closingCheckpoint().isPresent()) {
            LOG.info("Discarded changes of “" + interactive.projectName + "” were kept in checkpoint "
                    + released.closingCheckpoint().get().getFileName() + ".");
        }
        if (interactive.plan != null && !interactive.plan.awaitRelease()) {
            CompletableFuture<Void> released = deps.projectManager().whenWritersIdle()
                    .thenCompose(_ -> deps.projectManager().whenRetiredLeasesReleased());
            Waited waited = new NestedWait("the closed folder's release").until(released, deadline);
            if (waited == Waited.PAST_THE_DEADLINE) {
                // The close had ended: nothing is skipped, only these waits are cut (the stage-8 review N2).
                LOG.warning("A save, a checkpoint or a lock's release was still running " + EXIT_WRITE_WAIT_SECONDS
                        + " seconds after the application stopped; the application stop goes on without waiting"
                        + " for it.");
            }
        }
    }

    /** Waits for every writer — the saves queued, every checkpoint job — within the deadline (R4 step 2). */
    private boolean everyWriterIdle(CloseRun stop, long deadline) {
        Waited writers = new NestedWait("every writer").until(deps.projectManager().whenWritersIdle(), deadline);
        if (writers == Waited.ENDED) {
            return true;
        }
        if (writers == Waited.PAST_THE_DEADLINE) {
            LOG.warning("A save or a checkpoint was still being written " + EXIT_WRITE_WAIT_SECONDS + " seconds after"
                    + " the application stopped; the closing checkpoint, the seal and the release are skipped, as"
                    + " after a crash.");
        }
        skipTheTail(stop, "a save or a checkpoint was still being written");
        return false;
    }

    /** The stop's closer, where the fallback may close at all. */
    private enum Row { NO_FOLDER, READ_ONLY, LOCK_LOST, WRITABLE }

    /**
     * What the stop's closer does: its plan, the folder's row, and whether it
     * is the fallback's own row (design §1.2's last row) — whose unsaved
     * changes the log accounts for (ruling 4 Q6.3, L7/L7b/L8).
     */
    private record StopPlan(Plan plan, Row row, boolean fallbacksOwn) { }

    /**
     * The stop's plan (design §1.2's table; ruling 4 Q6.2): the fallback's own
     * row unless a decision stands — a Discard keeps its row, so its
     * discarded work still gets its closing checkpoint and its seal; a save
     * that left unsaved changes takes the fallback's row. Every row waits for
     * every writer and the release. Nothing written into a read-only folder,
     * a project never saved, or a folder whose lock another session holds —
     * the lock read off the FX thread, waited for — but this session's own
     * journal drained there without a manifest (ruling R7's interim). Empty
     * when the lock's read did not answer by the deadline: the rest is
     * skipped.
     */
    private Optional<StopPlan> planAtTheStop(CloseRun stop, Decision decision, boolean decidedLockLost,
                                             long deadline) {
        CloseReadiness readiness = deps.lifecycle().closeReadiness();
        boolean fallbacksOwn = decision == null || (decision != Decision.DISCARD && readiness.dirty());
        if (!readiness.hasFolder()) {
            return Optional.of(new StopPlan(new Plan(ClosingCheckpoint.NONE, Optional.empty(),
                    WriterScope.EVERY_WRITER, true, true), Row.NO_FOLDER, fallbacksOwn));
        }
        if (readiness.readOnly()) {
            return Optional.of(new StopPlan(new Plan(ClosingCheckpoint.NONE, Optional.empty(),
                    WriterScope.EVERY_WRITER, true, true), Row.READ_ONLY, fallbacksOwn));
        }
        boolean lost = decidedLockLost || readiness.lockLost();
        if (!lost) {
            stop.currentStep = ExitStep.CLOSING_CHECKPOINT;
            CompletableFuture<Optional<LockStolenException>> checked = deps.projectManager().whenLockChecked();
            Waited read = new NestedWait("the lock's check").until(checked, deadline);
            if (read != Waited.ENDED) {
                pastTheDeadline(read, stop);
                skipTheTail(stop, "the lock's check did not answer");
                return Optional.empty();
            }
            lost = checked.resultNow().isPresent();
        }
        if (lost) {
            stop.lockLost = true;
            return Optional.of(new StopPlan(new Plan(ClosingCheckpoint.NONE,
                    Optional.of(JournalEnd.DRAIN_WITHOUT_MANIFEST), WriterScope.EVERY_WRITER, true, true),
                    Row.LOCK_LOST, fallbacksOwn));
        }
        ClosingCheckpoint checkpoint = fallbacksOwn ? ClosingCheckpoint.IF_RECORDS_OR_UNSAVED
                : decision == Decision.DISCARD ? ClosingCheckpoint.IF_RECORDS_OR_NOT_CHECKPOINTED
                : ClosingCheckpoint.IF_RECORDS_OUTSIDE_A_CAPTURE;
        // Nothing can ask after a seal failure: the close goes on to the release, the journal left unsealed.
        return Optional.of(new StopPlan(new Plan(checkpoint, Optional.of(JournalEnd.SEAL), WriterScope.EVERY_WRITER,
                true, true), Row.WRITABLE, fallbacksOwn));
    }

    /** The stop's closer with {@code plan}, waited for within the deadline; then what its log owes. */
    private void closeAsPlanned(CloseRun stop, StopPlan plan, long deadline) {
        stop.plan = plan.plan();
        stop.closing = deps.folderCloser().close(stop.plan, () -> !stop.ended, stepsOf(stop));
        CompletableFuture<ProjectFolderCloser.Result> result = stop.closing.result().toCompletableFuture();
        Waited ended = new NestedWait("the close").until(result, deadline);
        if (ended != Waited.ENDED) {
            pastTheDeadline(ended, stop);
            stop.closing.skipRemaining(drainMode(stop));
            skipTheStepsLeft(stop, "the application stopped");
            return;
        }
        if (result.isCompletedExceptionally()) {
            stop.closing.skipRemaining(drainMode(stop));
            skipTheStepsLeft(stop, "the close failed");
            return;
        }
        if (plan.fallbacksOwn() && result.resultNow() instanceof ProjectFolderCloser.Released released) {
            accountForTheUnsavedChanges(stop, plan.row(), released);
        }
    }

    /**
     * Ruling 4 Q6.3's L7, L7b and L8, with L8c: what became of unsaved
     * changes — {@code project.isDirty()} in the detach's turn (ruling 4
     * Q6.2, item 2). L7b whenever no journal was open at the detach, whatever
     * the setting (the studio engineer's final ruling F4). L8c on a lost
     * lock's or a read-only open's row when the close's own Save As was asked
     * for: its copy, which the fallback does not wait for, may still keep
     * them (final ruling F2). The closing checkpoint failure's line follows
     * the closer's own WARNING.
     */
    private void accountForTheUnsavedChanges(CloseRun stop, Row row, ProjectFolderCloser.Released released) {
        if (!stop.closing.unsavedAtTheDetach()) {
            return;
        }
        String changes = "Unsaved changes of “" + stop.projectName + "” were";
        if (released.closingCheckpoint().isPresent()) {
            String file = released.closingCheckpoint().get().getFileName().toString();
            LOG.info(stop.closing.journalOpenAtTheDetach()
                    ? changes + " kept in checkpoint " + file + "; the journal is left unsealed, so the next open"
                    + " offers to recover them."
                    : changes + " kept in checkpoint " + file + "; no journal was open for the project, so the next"
                    + " open does not offer to recover them.");
            return;
        }
        String why = switch (row) {
            case LOCK_LOST -> "another session holds its lock";
            case READ_ONLY -> "the project is open read-only";
            case NO_FOLDER -> "the project has never been saved";
            case WRITABLE -> stop.closing.closingCheckpointFailure()
                    .map(cause -> "their closing checkpoint could not be written ("
                            + ProjectLifecycleController.failureReason(cause) + ")")
                    .orElse(null);
        };
        if (why != null) {
            LOG.warning(stop.saveAsAsked && (row == Row.LOCK_LOST || row == Row.READ_ONLY)
                    ? changes + " not written into its folder: " + why + ". The Save As asked for before the"
                      + " application stopped keeps them only if its copy finished before the application ended;"
                      + " the application-stop fallback does not wait for it."
                    : changes + " not kept: " + why + ".");
        }
    }

    /** Ruling 4 Q6.3's L6, when the deadline passed (a wait that could not start is logged by itself). */
    private void pastTheDeadline(Waited waited, CloseRun stop) {
        if (waited == Waited.PAST_THE_DEADLINE) {
            LOG.warning("The close of “" + stop.projectName + "” had not finished " + EXIT_WRITE_WAIT_SECONDS
                    + " seconds after the application stopped; its remaining steps are skipped, as after a crash.");
        }
    }

    /**
     * The take written and published (ruling R4, step 1): the take property
     * listened to — revalidated first, and read again right after the
     * registration — and the wait a {@link NestedWait}, in which the take's
     * publication turn runs. The listener goes from the subject it was added
     * to.
     */
    private Waited awaitTheTake(long deadline) {
        ReadOnlyBooleanProperty subject = deps.takes().takeBeingWrittenProperty();
        CompletableFuture<Void> written = new CompletableFuture<>();
        InvalidationListener listener = observable -> {
            ((ObservableBooleanValue) observable).get();
            if (!subject.get() && !deps.takes().takeBeingWritten()) {
                written.complete(null);
            }
        };
        Subscription subscription = new Subscription(subject, listener);
        subject.addListener(listener);
        try {
            if (!subject.get() && !deps.takes().takeBeingWritten()) {
                written.complete(null);
            }
            return new NestedWait("the take").until(written, deadline);
        } finally {
            subscription.remove();
        }
    }

    /**
     * The tail skipped, as after a crash (ruling R4's bounds; the studio
     * engineer's N10): the closing checkpoint, the seal and the release
     * SKIPPED, the lock left, and an open journal drained unsealed, best
     * effort, on a thread nobody waits for.
     */
    private void skipTheTail(CloseRun stop, String why) {
        if (stop.closing == null) {
            stop.plan = new Plan(ClosingCheckpoint.NONE, Optional.of(JournalEnd.SEAL), WriterScope.EVERY_WRITER,
                    false, true);
            stop.closing = deps.folderCloser().close(stop.plan, () -> !stop.ended, stepsOf(stop));
        }
        stop.closing.skipRemaining(drainMode(stop));
        skipTheStepsLeft(stop, why);
    }

    /**
     * The stop's DISPOSE: the window's lifetime disposed — a failure of it
     * asks again for the engine's stop it started — and its hand-off waited
     * for at most {@link #ENGINE_STOP_AFTER_HIDE_BOUND} from its start (ruling
     * 2 OQ-3: the device gets the same 15 s on every path; the studio
     * engineer's stage-8 S1).
     */
    private void disposeAtTheStop(CloseRun stop) {
        stop.disposeBegan = true;
        stop.currentStep = ExitStep.DISPOSE;
        long started = deps.nanoClock().getAsLong();
        CompletionStage<Void> handOff;
        Throwable notDisposed = null;
        try {
            handOff = Objects.requireNonNull(deps.disposeWindow().get(), "the hand-off must not be null");
        } catch (RuntimeException | Error failed) {
            LOG.log(Level.SEVERE, failed, () -> "The window's disposal failed; the application stop goes on");
            notDisposed = failed;
            handOff = handOffStartedDespiteTheFailure();
        }
        // Published once the disposal's outcome is known, quietly: an observer's throw neither asks for a second
        // disposal nor skips the wait for the engine's stop (the app-fix-2 review N3, as for the protocol's DISPOSE).
        Throwable failed = notDisposed;
        quietly(() -> publish(stop, ExitStep.DISPOSE, failed == null ? StepResult.DONE : StepResult.FAILED,
                failed == null ? "the window's lifetime was disposed; the audio stops"
                        : ProjectLifecycleController.failureReason(failed)));
        awaitTheHandOff(handOff, started + ENGINE_STOP_AFTER_HIDE_BOUND.toNanos(), ENGINE_STOP_AFTER_HIDE_BOUND);
    }

    /** Waits for DISPOSE's hand-off, the audio engine's stop, until {@code boundNanos}; logs when it passed. */
    private void awaitTheHandOff(CompletionStage<Void> handOff, long boundNanos, Duration bound) {
        if (handOff == null) {
            return;
        }
        if (new NestedWait("the audio engine's stop").until(handOff, boundNanos) == Waited.PAST_THE_DEADLINE) {
            LOG.warning("Audio engine shutdown did not finish within " + bound.toSeconds() + " seconds; the"
                    + " application ends with the audio device still stopping.");
        }
    }

    /**
     * Fences an interactive close for the fallback: its own continuations —
     * posts, timers, its surface, its listeners — do nothing any more, and
     * its surface closes; its closer, which the fallback may join, goes on.
     */
    private void takeOver(CloseRun run) {
        run.takenOver = true;
        quietly(() -> suspendClock(run));
        quietly(() -> cancel(run.detachTimer));
        quietly(() -> cancel(run.hideTimer));
        quietly(() -> closeSurface(run));
        quietly(() -> removeSubscriptions(run));
        quietly(() -> removeTakeOperation(run));
    }

    /**
     * The taken-over close ends with the fallback, {@code Cancelled}: the
     * application ends, so nothing it asked for follows — the primitive's
     * switch included, and an exit asked after Close Project's release, whose
     * outcome completes too (the stage-8 review N11). The
     * window stays inert and the close pending for the rest of the process:
     * a stale continuation is refused.
     */
    private void retireTheTakenOver(CloseRun run) {
        if (running == run) {
            running = null;
        }
        run.ended = true;
        run.outcome.complete(new CloseOutcome.Cancelled());
        if (run.exitAfterReleaseOutcome != null) {
            run.exitAfterReleaseOutcome.complete(new CloseOutcome.Cancelled());
        }
    }

    /**
     * The defensive branch (design §1.7 step 9; ruling 4 Q6.2, item 3),
     * unreachable in production — the launcher calls {@code stop()} on the FX
     * thread with the toolkit alive: the stop came off the FX thread, or no
     * nested loop can start now. It runs neither DISPOSE nor the engine's
     * stop — DISPOSE's FX part cannot run off the FX thread — and leaves the
     * audio device to the end of the process. With the take unknown (off the
     * FX thread) or in flight, it skips the closing checkpoint, the seal and
     * the release, as after a crash: the next open's gate offers what the
     * journal can recover, and the take's own recovery at the next open is
     * future story 402's — no production path runs {@code TakeRecovery.scan}
     * yet. Otherwise, on the non-daemon {@value #EXIT_FALLBACK_THREAD}
     * thread the JVM waits for, it drains the open journal unsealed with no
     * manifest and no closing checkpoint — the drain on a virtual thread of
     * its own, so a disk that stopped responding blocks nothing the JVM waits
     * for — then waits for every writer, closes the project and waits for its
     * lock's release, each wait bounded by
     * {@link ProjectManager#EXIT_WRITE_WAIT} ({@link BoundedWait}); a drain
     * not done by then leaves the lock, as after a crash.
     */
    private void stopOnTheFallbacksThread(boolean onFx) {
        CloseRun stop = new CloseRun(new CloseDoor.ApplicationStop());
        boolean takeKnownIdle = onFx && !deps.takes().recordingInFlight() && !deps.takes().takeBeingWritten();
        CloseRun interactive = onFx ? running : null;
        if (interactive != null) {
            // Nothing is joined here: the run ends, its closer stops at its next turn, a running seal goes on.
            takeOver(interactive);
            retireTheTakenOver(interactive);
        }
        if (!takeKnownIdle) {
            LOG.warning("The application is stopping where no nested event loop can run, while a take may still be"
                    + " recording or being written: the closing checkpoint, the seal and the release are skipped, as"
                    + " after a crash, and the audio engine is left to the end of the process.");
            skipTheStepsLeft(stop, "the take may still be recording or being written");
            return;
        }
        LOG.warning("The application is stopping where no nested event loop can run: the open project's journal is"
                + " drained unsealed with no closing checkpoint, and the audio engine is left to the end of the"
                + " process.");
        publish(stop, ExitStep.CLOSING_CHECKPOINT, StepResult.SKIPPED, "no capture runs without a nested loop");
        long deadline = deps.nanoClock().getAsLong() + ProjectManager.EXIT_WRITE_WAIT.toNanos();
        ProjectFolderCloser closer = deps.folderCloser();
        ProjectManager projectManager = deps.projectManager();
        LongSupplier clock = deps.nanoClock();
        Thread.ofPlatform().name(EXIT_FALLBACK_THREAD).daemon(false).start(() -> {
            // The drain blocks while it writes, so it runs on a virtual thread of its own, waited for within the
            // deadline: a disk that stopped responding never keeps the JVM from ending (the stage-8 review S8).
            CompletableFuture<Void> drained = new CompletableFuture<>();
            try {
                Thread.ofVirtual().name(ProjectFolderCloser.DRAIN_WORKER).start(() -> {
                    try {
                        closer.drainTheOpenFolderUnsealed();
                        drained.complete(null);
                    } catch (RuntimeException | Error notDrained) {
                        drained.completeExceptionally(notDrained);
                    }
                });
            } catch (RuntimeException | Error notStarted) {
                drained.completeExceptionally(notStarted);
            }
            if (!BoundedWait.until(drained, deadline, clock)) {
                // A release made now would not owe the drain that may still write (ruling 2 OQ-1, constraint 3).
                publish(stop, ExitStep.SEAL, StepResult.SKIPPED, "not drained by the deadline");
                publish(stop, ExitStep.RELEASE, StepResult.SKIPPED, "the journal was still being drained");
                return;
            }
            if (drained.isCompletedExceptionally()) {
                Throwable notDrained = drained.exceptionNow();
                LOG.log(Level.WARNING, notDrained, () -> "The open project's journal could not be drained");
                publish(stop, ExitStep.SEAL, StepResult.FAILED, ProjectLifecycleController.failureReason(notDrained));
            } else {
                publish(stop, ExitStep.SEAL, StepResult.DONE,
                        "an open journal of the project drained unsealed, with no manifest");
            }
            if (!BoundedWait.until(projectManager.whenWritersIdle(), deadline, clock)) {
                LOG.warning("A save or a checkpoint was still being written " + EXIT_WRITE_WAIT_SECONDS + " seconds"
                        + " after the application stopped; the closing checkpoint, the seal and the release are"
                        + " skipped, as after a crash.");
                publish(stop, ExitStep.RELEASE, StepResult.SKIPPED, "a writer was still writing at the deadline");
                return;
            }
            boolean released = BoundedWait.until(projectManager.closeProject(), deadline, clock);
            publish(stop, ExitStep.RELEASE, released ? StepResult.DONE : StepResult.SKIPPED, released
                    ? "the folder's lock was released" : "the folder's lock was not released by the deadline");
        });
    }

    /** How a {@link NestedWait} ended. */
    private enum Waited {
        /** The awaited stage completed, normally or not. */
        ENDED,
        /** The deadline passed first. */
        PAST_THE_DEADLINE,
        /** No nested loop could start, so nothing was waited for (logged). */
        NOT_WAITED
    }

    /**
     * One wait of {@code Application.stop()} (design §1.7; the feasibility
     * critique's S8): a nested event loop on the FX thread — the launcher's
     * thread waits in {@code runAndWait(stop)} meanwhile, so the JVM does not
     * end under it, and the toolkit's posts, the awaited work's among them,
     * keep running — with a deadline on the nano clock, through
     * {@link CloseTimers} (javafx skill §11, "Threading": the toolkit's own
     * mechanism, never a blocked FX thread). One-shot:
     * <ul>
     *   <li>the condition is checked first, and no loop is entered when it
     *       already holds;</li>
     *   <li>the wait itself is its loop's key, so each wait has its own;</li>
     *   <li>the completion is attached asynchronously, posted to the FX
     *       thread, never run where it was attached;</li>
     *   <li>the completion and the deadline each leave the loop only through
     *       one compare-and-set, so whichever comes first leaves it and the
     *       other does nothing — as does a timer that fires after the wait
     *       ended.</li>
     * </ul>
     * A modal loop opened inside a wait — a dialog's {@code showAndWait} —
     * holds that wait past its deadline: the toolkit leaves nested loops
     * innermost first. So the fallback opens none, and neither the takeover
     * dialog nor the Welcome window opens once the application stops (the
     * stage-8 review B1; the studio engineer's final ruling F1). A Save As
     * chosen before the stop whose target is refused meanwhile still asks
     * again — its warning, then its chooser — and holds the wait until it is
     * answered (Resolution category 4).
     */
    private final class NestedWait {
        private final String what;
        private final AtomicBoolean done = new AtomicBoolean();

        NestedWait(String what) {
            this.what = what;
        }

        /**
         * Waits, in a nested loop, until {@code completion} completed —
         * normally or not — or {@code deadlineNanos} passed. FX thread.
         *
         * @return how the wait ended
         */
        Waited until(CompletionStage<?> completion, long deadlineNanos) {
            if (completion.toCompletableFuture().isDone()) {
                return Waited.ENDED;
            }
            long remaining = deadlineNanos - deps.nanoClock().getAsLong();
            if (remaining <= 0) {
                return Waited.PAST_THE_DEADLINE;
            }
            if (!deps.nestedLoops().canNest()) {
                LOG.warning(() -> "The application stop could not wait for " + what + ": no nested event loop can"
                        + " start now");
                return Waited.NOT_WAITED;
            }
            completion.whenCompleteAsync((_, _) -> leave(true), task -> FxDispatcher.runOnFx(deps.dispatcher(), task));
            CloseTimers.Cancellable deadline = deps.timers().after(Duration.ofNanos(remaining), () -> leave(false));
            try {
                return Boolean.TRUE.equals(deps.nestedLoops().enter(this)) ? Waited.ENDED : Waited.PAST_THE_DEADLINE;
            } finally {
                done.set(true);
                deadline.cancel();
            }
        }

        /** Leaves the loop, once: the completion's turn or the deadline's, whichever comes first. FX thread. */
        private void leave(boolean ended) {
            if (done.compareAndSet(false, true)) {
                deps.nestedLoops().exit(this, ended);
            }
        }

        @Override
        public String toString() {
            return "the application stop's wait for " + what;
        }
    }

    // ── the run's plumbing ──────────────────────────────────────────────────

    /** Whether the run closes the application: an exit door, or a Close Project an exit upgraded. */
    private static boolean exits(CloseRun run) {
        return run.door.exitsApplication() || run.exitRequested;
    }

    /** Whether the run is a Close Project an exit upgraded (ruling Q8), its keep {@code Don't quit}. */
    private static boolean upgraded(CloseRun run) {
        return !run.door.exitsApplication() && run.exitRequested;
    }

    /** The door's name as the failure texts name it: {@code Exit} for an exit, an upgraded Close Project's too. */
    private static String doorName(CloseRun run) {
        return exits(run) ? EXIT_DOOR : run.door.name();
    }

    /** Whether {@code run}'s own continuations may still act: it is the running close, not ended, not taken over. */
    private boolean isCurrent(CloseRun run) {
        return run == running && !run.ended && !run.takenOver;
    }

    /** R2's take line: the close stopped or waited out a take whose publication put a clip or a note in. */
    private boolean includesTheTake(CloseRun run) {
        return run.takeHandled && deps.takes().takePublicationsWithClips() > run.takePublicationsAtStart;
    }

    /** The close's transport stop, checked: a transport that did not stop is stopped once more (design §3.8). */
    private void stopTransport() {
        if (deps.stopForClose().getAsBoolean()) {
            return;
        }
        LOG.warning("The transport did not stop for the close; it is stopped once more");
        if (!deps.stopForClose().getAsBoolean()) {
            LOG.warning("The transport still did not stop for the close");
        }
    }

    /**
     * Listens to {@code subject} for the run: the listener revalidates the
     * property first — an invalidation listener whose property is never read
     * again hears nothing more — then posts the re-judgement, which reads it
     * live; the property is read once more after the registration, for a
     * change between the first look and the listener.
     */
    private void subscribe(CloseRun run, ObservableBooleanValue subject) {
        InvalidationListener listener = observable -> {
            ((ObservableBooleanValue) observable).get();
            post(run, () -> rejudge(run));
        };
        subject.addListener(listener);
        run.subscriptions.add(new Subscription(subject, listener));
        subject.get();
    }

    private static void removeSubscriptions(CloseRun run) {
        run.subscriptions.forEach(Subscription::remove);
        run.subscriptions.clear();
    }

    private void publish(CloseRun run, ExitStep step, StepResult result, String detail) {
        run.published.add(step);
        StepOutcome outcome = new StepOutcome(step, result, detail);
        LOG.info(() -> "Exit protocol: " + step + " " + result + " " + detail);
        deps.stepObserver().accept(outcome);
    }

    /**
     * Ends the run, once: the window becomes live, the close is no longer the
     * switch pending, its surface closes, its timers and listeners go — from
     * the subjects they were added to — and its outcome completes; an exit
     * asked after Close Project's release runs next, once. Each cleanup is
     * guarded, and the fence's lowering and the close's end come after them
     * whatever they threw, so the window becomes live whatever else fails
     * (ruling 4 Q3, constraint 2).
     */
    private void end(CloseRun run, CloseOutcome outcome) {
        if (running != run) {
            return;
        }
        running = null;
        run.ended = true;
        quietly(() -> suspendClock(run));
        quietly(() -> cancel(run.detachTimer));
        quietly(() -> cancel(run.hideTimer));
        quietly(() -> closeSurface(run));
        quietly(() -> removeSubscriptions(run));
        quietly(() -> removeTakeOperation(run));
        quietly(deps.fence()::lower);
        quietly(deps.lifecycle()::endClosing);
        run.outcome.complete(outcome);
        CloseDoor exitNext = run.exitAfterRelease;
        if (exitNext != null) {
            CompletableFuture<CloseOutcome> next = run.exitAfterReleaseOutcome;
            FxDispatcher.runOnFx(deps.dispatcher(), () -> request(exitNext).whenComplete((ended, failure) -> {
                if (failure != null) {
                    next.completeExceptionally(failure);
                } else {
                    next.complete(ended);
                }
            }));
        }
    }

    private static void cancel(CloseTimers.Cancellable timer) {
        if (timer != null) {
            timer.cancel();
        }
    }

    /** An unkeyed post of a continuation of {@code run}, guarded: it may show a prompt or a nested loop. */
    private void post(CloseRun run, Runnable work) {
        FxDispatcher.runOnFx(deps.dispatcher(), () -> guarded(run, work));
    }

    // ── a step that throws (ruling 4 Q3) ───────────────────────────────────

    /**
     * Runs {@code work}, an entry into {@code run} — a post, a timer, a
     * closer callback, a surface action, the synchronous part of a request —
     * so that a step that throws ends the run instead of stranding it
     * ({@link #stepFailed}). FX thread.
     */
    private void guarded(CloseRun run, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException | Error failed) {
            stepFailed(run, failed);
        }
    }

    /**
     * A step of {@code run} threw (ruling 4 Q3): logged SEVERE as
     * {@code Exit protocol: <step> failed: <reason>} and published FAILED,
     * then the run ends by how far it had got — on its own progress, not on
     * the step that threw — so the window never stays inert and a later close
     * starts afresh:
     * <ul>
     *   <li>before the journal's detach: the close stops, the project open —
     *       one ERROR, {@code close.failed};</li>
     *   <li>detached, the release not begun: the tail skipped, as a keep
     *       after the detach skips it, its journal drained unsealed — one
     *       ERROR, {@code close.failed.paused} when the plan has a journal
     *       end ({@code close.failed} otherwise), the keep's WARNING logged
     *       only;</li>
     *   <li>the release begun: an exit goes on as Quit anyway, logged only;
     *       Close Project ends with the window live — one ERROR,
     *       {@code close.failed.closed};</li>
     *   <li>DISPOSE begun: HIDE, at DISPOSE's hand-off or its bound, logged
     *       only.</li>
     * </ul>
     * A failure outside the run, or a second one inside this path, is logged
     * only, so a run shows exactly one ERROR; so is one of a run the
     * application-stop fallback took over, which shows nothing (ruling 4
     * Q6.2, item 4). Never throws. FX thread.
     */
    private void stepFailed(CloseRun run, Throwable failed) {
        ExitStep step = run.currentStep;
        String reason = ProjectLifecycleController.failureReason(failed);
        LOG.log(Level.SEVERE, failed, () -> "Exit protocol: " + step + " failed: " + reason);
        if (run != running || run.ended || run.failing || run.takenOver) {
            return;
        }
        run.failing = true;
        quietly(() -> publish(run, step, StepResult.FAILED, reason));
        if (run.disposeBegan) {
            hideAfterAFailure(run);
            return;
        }
        boolean releaseBegan = run.awaitingExitRelease || (run.closing != null && run.closing.releaseBegan());
        if (releaseBegan && exits(run)) {
            try {
                goOnAsQuitAnyway(run, "the close protocol failed");
            } catch (RuntimeException | Error again) {
                LOG.log(Level.SEVERE, again, () -> "Exit protocol: going on as Quit anyway failed: "
                        + ProjectLifecycleController.failureReason(again));
                hideAfterAFailure(run);
            }
            return;
        }
        String key;
        if (releaseBegan) {
            key = "close.failed.closed";
        } else if (run.detached && run.closing != null) {
            boolean anyJournal = run.plan != null && run.plan.journalEnd().isPresent();
            boolean sealHadBegun = run.closing.journalEndBegan();
            quietly(() -> run.closing.skipRemaining(drainMode(run)));
            quietly(() -> skipTheStepsLeft(run, "the close protocol failed"));
            if (anyJournal) {
                // One fact, one notification: the ERROR below says the journaling is paused.
                quietly(() -> LOG.warning(keptOpenText(run, keptOpenPrefix(run, sealHadBegun))));
            }
            key = anyJournal ? "close.failed.paused" : "close.failed";
        } else {
            key = "close.failed";
        }
        String errorKey = key;
        // The text is built inside the cleanup that shows it, so the run ends whatever it throws (the app-fix-2
        // review N2): the window becomes live (ruling 4 Q3, constraint 2).
        quietly(() -> deps.notifications().show(NotificationLevel.ERROR,
                ProjectCloseText.text(errorKey, doorName(run), run.projectName, reason)));
        end(run, new CloseOutcome.Failed(failed));
    }

    /**
     * An exit whose release had begun when a step threw: on as Quit anyway
     * from there — GUARD's waits ended, what is left skipped — then DISPOSE
     * and HIDE (ruling 4 Q3, P3).
     */
    private void goOnAsQuitAnyway(CloseRun run, String why) {
        run.quitting = true;
        run.phase = Phase.DECIDED;
        run.awaitingExitRelease = false;
        removeSubscriptions(run);
        if (run.closing != null) {
            run.closing.skipRemaining(drainMode(run));
        }
        skipTheStepsLeft(run, why);
        dispose(run);
    }

    /**
     * DISPOSE had begun when a step threw, or DISPOSE itself could not go on:
     * HIDE follows at DISPOSE's hand-off — the one asked, or asked now, the
     * window's disposal running once — or after its bound (ruling 4 Q3, P4).
     * Never throws.
     */
    private void hideAfterAFailure(CloseRun run) {
        quietly(() -> {
            CompletionStage<Void> handOff = run.handOff;
            if (handOff == null && run.hideTimer == null) {
                try {
                    handOff = Objects.requireNonNull(deps.disposeWindow().get(), "the hand-off must not be null");
                    run.handOff = handOff;
                } catch (RuntimeException | Error notDisposed) {
                    LOG.log(Level.SEVERE, notDisposed, () -> "The window's disposal failed; the exit goes on");
                }
            }
            hideOnceTheEngineStopped(run, handOff != null ? handOff : CompletableFuture.completedFuture(null));
        });
        if (run.hideTimer == null) {
            hide(run, "the close protocol failed");
        }
    }

    /** Runs a cleanup of the failure path or of a run's end: a failure of it is logged only (ruling 4 Q3). */
    private static void quietly(Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException | Error failed) {
            LOG.log(Level.SEVERE, failed, () -> "Exit protocol: a cleanup failed: "
                    + ProjectLifecycleController.failureReason(failed));
        }
    }
}
