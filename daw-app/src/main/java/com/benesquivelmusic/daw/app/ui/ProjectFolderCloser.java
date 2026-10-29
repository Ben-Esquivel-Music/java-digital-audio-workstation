package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.JournalEnding.JournalEnd;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.status.ProjectJournalCoordinator;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.ProjectMetadata;
import com.benesquivelmusic.daw.core.project.DawProject;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The shared tail of every close of the open project's folder (story 333;
 * Persistence Integrity book §5.4; the studio engineer's rulings R3, R5, R8
 * and ruling 2 OQ-1): the open generation's checkpoint writer idle, the
 * journal detached from the bus — the close's point of no return — the
 * closing checkpoint when the plan's trigger holds, the journal's end, and
 * the release of the folder, in that order. Each step's outcome is told to
 * the caller before the next step starts; every hop back to the JavaFX
 * thread is an unkeyed {@link FxDispatcher} post. The exit protocol closes
 * through it, and Save As closes the folder it copied through it.
 *
 * <h2>The steps</h2>
 * <ol>
 *   <li><b>CLOSING_CHECKPOINT</b>: waits until the open generation's
 *       checkpoint writer holds no job ({@link ProjectManager#whenOpenGenerationIdle()}:
 *       the closing folder's own generation, so a close never waits on
 *       another folder's writer); then, in one FX turn, detaches the journal
 *       ({@link JournalEnding#detach()}) and decides the trigger — read right
 *       after the detach, when no record can be offered any more — and asks
 *       for the closing checkpoint ({@link ProjectManager#writeClosingCheckpoint}).
 *       A closing checkpoint that fails is logged at {@code WARNING} and the
 *       close goes on (ruling R3).</li>
 *   <li><b>SEAL</b>: the plan's journal end, prepared on the FX thread and
 *       run on the {@code daw-journal-seal} worker — always before the
 *       release, so the checkpoint marker reaches the journal before the
 *       checkpoints stop (ruling R3).</li>
 *   <li><b>RELEASE</b>: for the exits, every writer idle first
 *       ({@link WriterScope#EVERY_WRITER}); then
 *       {@link ProjectManager#closeProject()}, which returns at once — the
 *       folder's lock is released in the background once its claimed work
 *       ended — and, when the plan says so, the wait for that release.</li>
 * </ol>
 *
 * <p>{@link Closing#skipRemaining} stops a close part-way — Quit anyway,
 * Save As's Continue, a deadline: every step not yet ended is reported
 * {@link StepResult#SKIPPED} — but a journal end already running, which is
 * never interrupted, and a release already made — and a journal end not
 * yet begun becomes a best-effort drain on a virtual thread the close does
 * not wait for. The returned
 * stage is the journal end that may still write — the running one, or that
 * drain — which a release made afterwards must owe
 * ({@link ProjectManager#closeProject(CompletionStage)}; ruling 2 OQ-1,
 * constraint 3). A journal end begins exactly once: the worker and the skip
 * decide it with one compare-and-set.</p>
 *
 * <p>The close always answers (the studio engineer's ruling on app fix pass 2a, item 5):
 * every FX turn of a close is guarded, so a step that throws completes its
 * {@link Closing#result()} exceptionally, once, logged {@code SEVERE} — the
 * caller ends its run by how far the close had got — and a skip after such a
 * failure still drains a journal end that had not begun.</p>
 *
 * <p>FX-confined, but for the journal end's phase and
 * {@link #drainTheOpenFolderUnsealed()}.</p>
 */
final class ProjectFolderCloser {

    private static final Logger LOG = Logger.getLogger(ProjectFolderCloser.class.getName());

    /** The worker a journal end runs on. */
    static final String SEAL_WORKER = "daw-journal-seal";

    /** The worker a best-effort drain of a stopped close runs on. */
    static final String DRAIN_WORKER = "daw-journal-drain";

    /** When a closing checkpoint is written (ruling R3's triggers). */
    enum ClosingCheckpoint {
        /** Never: read-only, a lost lock, no folder. */
        NONE,
        /** (i) a record was journaled outside every capture. */
        IF_RECORDS_OUTSIDE_A_CAPTURE,
        /** (i), or (ii) the live revision is not checkpointed: Discard and close, Save As's original. */
        IF_RECORDS_OR_NOT_CHECKPOINTED,
        /**
         * (i), or (iii) the project has unsaved changes: the application-stop fallback, whose journal end is
         * then left unsealed — its {@link JournalEnd#SEAL} becomes {@link JournalEnd#DRAIN_UNSEALED} when the
         * project has unsaved changes in the detach's turn (ruling R4, step 4; ruling 4 Q6.2, item 2), so the
         * next open offers to recover them from this closing checkpoint.
         */
        IF_RECORDS_OR_UNSAVED
    }

    /** Which writers the release waits for first. */
    enum WriterScope {
        /** Only the closing folder's checkpoint generation (step 1). */
        OPEN_GENERATION,
        /** The save writer and every checkpoint writer too, before the release: the exits. */
        EVERY_WRITER
    }

    /** A step of the close. */
    enum Step { CLOSING_CHECKPOINT, SEAL, RELEASE }

    /** How a step ended. */
    enum StepResult { DONE, SKIPPED, FAILED }

    /**
     * A step's outcome, told before the next step starts.
     *
     * @param step   the step
     * @param result how it ended
     * @param detail what it did, for the log
     */
    record StepOutcome(Step step, StepResult result, String detail) {
        StepOutcome {
            Objects.requireNonNull(step, "step must not be null");
            Objects.requireNonNull(result, "result must not be null");
            Objects.requireNonNull(detail, "detail must not be null");
        }
    }

    /**
     * What a close does.
     *
     * @param checkpoint           when the closing checkpoint is written
     * @param journalEnd           how the journal ends, or empty for no journal end
     * @param writers              which writers the release waits for first
     * @param awaitRelease         whether the close ends only once the folder's lock was released (the exits)
     * @param goOnAfterSealFailure whether a journal end that fails goes on to the release (a Discard, Save
     *                             As's original) rather than ending the close with {@link SealFailed}
     */
    record Plan(ClosingCheckpoint checkpoint, Optional<JournalEnd> journalEnd, WriterScope writers,
                boolean awaitRelease, boolean goOnAfterSealFailure) {
        Plan {
            Objects.requireNonNull(checkpoint, "checkpoint must not be null");
            Objects.requireNonNull(journalEnd, "journalEnd must not be null");
            Objects.requireNonNull(writers, "writers must not be null");
        }
    }

    /** How a close ended. */
    sealed interface Result permits Released, SealFailed, Stopped { }

    /**
     * The folder was released.
     *
     * @param closingCheckpoint the closing checkpoint written, if one was
     * @param sealFailure       the journal end's failure, when the plan went on after it
     */
    record Released(Optional<Path> closingCheckpoint, Optional<Throwable> sealFailure) implements Result {
        Released {
            Objects.requireNonNull(closingCheckpoint, "closingCheckpoint must not be null");
            Objects.requireNonNull(sealFailure, "sealFailure must not be null");
        }
    }

    /**
     * The journal end failed and the close stopped before the release
     * ({@link Closing#releaseAnyway()} goes on).
     *
     * @param cause             why the journal could not be ended
     * @param closingCheckpoint the closing checkpoint written, if one was
     */
    record SealFailed(Throwable cause, Optional<Path> closingCheckpoint) implements Result {
        SealFailed {
            Objects.requireNonNull(cause, "cause must not be null");
            Objects.requireNonNull(closingCheckpoint, "closingCheckpoint must not be null");
        }
    }

    /** The close was stopped part-way ({@link Closing#skipRemaining}) or is no longer wanted. */
    record Stopped() implements Result { }

    /** What a close tells its caller, on the FX thread. Every method defaults to doing nothing. */
    interface Steps {

        /** Nothing. */
        Steps NONE = new Steps() { };

        /** {@code step} begins. */
        default void reached(Step step) { }

        /** A step ended. */
        default void outcome(StepOutcome outcome) { }

        /** The journal was detached: the close's point of no return. */
        default void detached() { }

        /** The journal end began on its worker. */
        default void sealBegan() { }

        /** The release began: {@link ProjectManager#closeProject()} returned. */
        default void releaseBegan() { }
    }

    private final ProjectManager projectManager;
    private final ProjectJournalCoordinator journal;
    private final JournalEnding ending;
    private final Supplier<DawProject> project;
    private final FxDispatcher dispatcher;
    private final Executor sealWorker;

    /**
     * Creates the closer.
     *
     * @param projectManager the project manager of the open folder
     * @param journal        the journal coordinator, asked for the closing checkpoint's trigger (i)
     * @param ending         how the journal ends
     * @param project        the live project, captured for the closing checkpoint on the FX thread
     * @param dispatcher     the FX marshalling seam; {@code null} for the app-scoped default
     */
    ProjectFolderCloser(ProjectManager projectManager, ProjectJournalCoordinator journal, JournalEnding ending,
                        Supplier<DawProject> project, FxDispatcher dispatcher) {
        this(projectManager, journal, ending, project, dispatcher,
                task -> Thread.ofVirtual().name(SEAL_WORKER).start(task));
    }

    /** As the public constructor, with the journal end's worker {@code sealWorker} (a test seam). */
    ProjectFolderCloser(ProjectManager projectManager, ProjectJournalCoordinator journal, JournalEnding ending,
                        Supplier<DawProject> project, FxDispatcher dispatcher, Executor sealWorker) {
        this.projectManager = Objects.requireNonNull(projectManager, "projectManager must not be null");
        this.journal = Objects.requireNonNull(journal, "journal must not be null");
        this.ending = Objects.requireNonNull(ending, "ending must not be null");
        this.project = Objects.requireNonNull(project, "project must not be null");
        this.dispatcher = dispatcher;
        this.sealWorker = Objects.requireNonNull(sealWorker, "sealWorker must not be null");
    }

    /**
     * Closes the open project's folder as {@code plan} says. FX thread; never
     * waits: each step runs off the FX thread or is a completion stage, and
     * comes back through an unkeyed post.
     *
     * @param plan        what the close does
     * @param stillWanted asked at every hop back to the FX thread; {@code false} stops the close, which ends
     *                    {@link Stopped} without starting another step
     * @param steps       told of each step
     * @return the close, whose {@link Closing#result()} completes on the FX thread
     */
    Closing close(Plan plan, BooleanSupplier stillWanted, Steps steps) {
        Closing closing = new Closing(Objects.requireNonNull(plan, "plan must not be null"),
                Objects.requireNonNull(stillWanted, "stillWanted must not be null"),
                Objects.requireNonNull(steps, "steps must not be null"));
        closing.start();
        return closing;
    }

    /**
     * Drains the open folder's journal, unsealed and with no session
     * manifest, on the calling thread: the application-stop fallback's
     * defensive branch, which runs where no nested event loop can (story 333,
     * design §1.7 step 9) — no closing checkpoint, the FX-confined working
     * session not read, and a journal of another folder left alone (ruling 2
     * OQ-1, constraint 2). A no-op when no folder or no journal is open. Any
     * thread; it blocks while the drain writes, so never on the FX thread.
     */
    void drainTheOpenFolderUnsealed() {
        ProjectMetadata open = projectManager.getCurrentProject();
        journal.drainWithoutSealFor(open == null ? null : open.projectPath(), null);
    }

    private void post(Runnable work) {
        FxDispatcher.runOnFx(dispatcher, work);
    }

    /** Where a journal end stands; decided by one compare-and-set between its worker and a skip. */
    private enum JournalPhase { NOT_STARTED, STARTED, SKIPPED }

    /** One close of the open folder. FX-confined, but for {@link #journalPhase} and {@link #journalEnded}. */
    final class Closing {

        private final Plan plan;
        private final BooleanSupplier stillWanted;
        private final Steps steps;
        private CompletableFuture<Result> result = new CompletableFuture<>();
        private final AtomicReference<JournalPhase> journalPhase = new AtomicReference<>(JournalPhase.NOT_STARTED);
        /** Completes when the journal end — the seal, or a skip's drain — has run, normally or not. */
        private final CompletableFuture<Void> journalEnded = new CompletableFuture<>();
        private Optional<Path> closingCheckpoint = Optional.empty();
        private Optional<Throwable> closingCheckpointFailure = Optional.empty();
        private Optional<Throwable> sealFailure = Optional.empty();
        /**
         * The throw that ended the close (the studio engineer's ruling on app fix pass 2a, item 5), or
         * {@code null}.
         */
        private Throwable failure;
        private boolean stopped;
        private boolean skipped;
        private boolean detachedHere;
        private boolean unsavedAtTheDetach;
        private boolean journalOpenAtTheDetach;
        private boolean checkpointStepEnded;
        private boolean sealStepBegan;
        private boolean sealStepEnded;
        private boolean releaseStepBegan;
        private boolean releaseBegan;
        private boolean awaitingReleaseAnyway;

        private Closing(Plan plan, BooleanSupplier stillWanted, Steps steps) {
            this.plan = plan;
            this.stillWanted = stillWanted;
            this.steps = steps;
        }

        /**
         * How the close ended; completes on the FX thread — exceptionally, once, when one of its FX turns threw
         * (the studio engineer's ruling on app fix pass 2a, item 5).
         */
        CompletionStage<Result> result() {
            return result;
        }

        /** Whether the release began ({@link ProjectManager#closeProject()} returned). FX thread. */
        boolean releaseBegan() {
            return releaseBegan;
        }

        /** Whether the journal end began on its worker. Any thread. */
        boolean journalEndBegan() {
            return journalPhase.get() == JournalPhase.STARTED;
        }

        /** Whether the close reached its detach: the journal was detached and the trigger decided. FX thread. */
        boolean detached() {
            return detachedHere;
        }

        /**
         * Whether the project had unsaved changes in the detach's turn — ruling R4's "unsaved changes"
         * (ruling 4 Q6.2, item 2: {@code project.isDirty()} read right after the detach, as trigger (iii)).
         * {@code false} before the detach. FX thread.
         */
        boolean unsavedAtTheDetach() {
            return unsavedAtTheDetach;
        }

        /** Whether a journal was open in the detach's turn: journaled persistence was on. FX thread. */
        boolean journalOpenAtTheDetach() {
            return journalOpenAtTheDetach;
        }

        /** Why the closing checkpoint could not be written, when it was asked for and failed (ruling R3). FX thread. */
        Optional<Throwable> closingCheckpointFailure() {
            return closingCheckpointFailure;
        }

        /**
         * Stops the close where it is (Quit anyway, Save As's Continue, a
         * deadline): every step not yet ended is reported
         * {@link StepResult#SKIPPED} — the closing checkpoint still awaited,
         * a journal end submitted but not begun, the steps not reached, a
         * release still waiting for every writer — but a journal end already
         * running, which is never interrupted and whose outcome is no longer
         * told, and a release already made, whose wait is no longer told;
         * a journal end of the plan not yet
         * begun is not run — {@code drainIfNotBegun}, when given, drains the
         * journal instead, best effort, on a virtual thread the close does
         * not wait for, which the returned stage is; the close ends
         * {@link Stopped}. No release is made here. After a close whose FX
         * turn threw — its result completed exceptionally — the first skip
         * still does all this, so a journal end not begun is drained and the
         * steps not ended are told (the studio engineer's ruling on app fix
         * pass 2a, item 5, point 3); a later skip does nothing more. FX
         * thread.
         *
         * @param drainIfNotBegun how to drain a journal whose end had not begun, or {@code null} for none
         * @return the journal end that may still write into the folder — the running seal or the drain —
         *         completed when there is none; never exceptional
         */
        CompletionStage<Void> skipRemaining(JournalEnd drainIfNotBegun) {
            if (skipped || (result.isDone() && !awaitingReleaseAnyway && failure == null)) {
                return journalEnded.isDone() || journalPhase.get() == JournalPhase.STARTED ? journalEnded
                        : CompletableFuture.completedFuture(null);
            }
            skipped = true;
            stopped = true;
            awaitingReleaseAnyway = false;
            if (!checkpointStepEnded) {
                checkpointStepEnded = true;
                steps.outcome(new StepOutcome(Step.CLOSING_CHECKPOINT, StepResult.SKIPPED, "the close was stopped"));
            }
            CompletionStage<Void> owed;
            boolean sealNeverRan = !sealStepBegan;
            if (plan.journalEnd().isEmpty()) {
                owed = CompletableFuture.completedFuture(null);
            } else if (journalPhase.compareAndSet(JournalPhase.NOT_STARTED, JournalPhase.SKIPPED)) {
                // Submitted to its worker, or not even that: it never runs.
                sealNeverRan = true;
                owed = drainIfNotBegun == null ? CompletableFuture.completedFuture(null) : drain(drainIfNotBegun);
            } else {
                owed = journalEnded;
            }
            if (sealNeverRan && !sealStepEnded) {
                sealStepEnded = true;
                steps.outcome(new StepOutcome(Step.SEAL, StepResult.SKIPPED, "the close was stopped"));
            }
            if (!releaseBegan) {
                // Not reached, or waiting for every writer before it: no release was made (the app fix pass 1
                // review, N4). Once closeProject() returned the release was made, and only its wait is not told.
                steps.outcome(new StepOutcome(Step.RELEASE, StepResult.SKIPPED, "the close was stopped"));
            }
            complete(new Stopped());
            return owed;
        }

        /**
         * Goes on to the release after the close ended {@link SealFailed}
         * (Quit anyway or Close anyway after a seal failure, ruling R12 #6).
         * FX thread.
         *
         * @return how the close then ended
         * @throws IllegalStateException if the close did not end {@link SealFailed}
         */
        CompletionStage<Result> releaseAnyway() {
            if (!awaitingReleaseAnyway) {
                throw new IllegalStateException("The close did not stop at a failed journal end");
            }
            awaitingReleaseAnyway = false;
            result = new CompletableFuture<>();
            release();
            return result;
        }

        private void start() {
            steps.reached(Step.CLOSING_CHECKPOINT);
            projectManager.whenOpenGenerationIdle().whenComplete((_, _) -> post(() -> guarded(this::generationIdle)));
        }

        /**
         * Runs one FX turn of the close, guarded: a throw ends the close —
         * its result completed exceptionally, once, logged {@code SEVERE} —
         * so the caller's run always gets an answer and ends by how far the
         * close had got (the studio engineer's ruling on app fix pass 2a,
         * item 5; ruling 4 Q3, constraint 1). FX thread.
         */
        private void guarded(Runnable turn) {
            try {
                turn.run();
            } catch (RuntimeException | Error failed) {
                failed(failed);
            }
        }

        private void failed(Throwable thrown) {
            if (failure != null || result.isDone()) {
                LOG.log(Level.WARNING, thrown, () -> "A step of a close that had already ended failed: "
                        + ProjectLifecycleController.failureReason(thrown));
                return;
            }
            failure = thrown;
            LOG.log(Level.SEVERE, thrown, () -> "The close of the project's folder failed: "
                    + ProjectLifecycleController.failureReason(thrown));
            result.completeExceptionally(thrown);
        }

        /** Step 1, in one FX turn: the detach, the trigger, the closing checkpoint. */
        private void generationIdle() {
            if (!wanted()) {
                return;
            }
            journalOpenAtTheDetach = journal.isOpen();
            ending.detach();
            detachedHere = true;
            // Ruling R4's "unsaved changes" (ruling 4 Q6.2, item 2): read in this turn, right after the detach.
            unsavedAtTheDetach = project.get().isDirty();
            steps.detached();
            if (stopped) {
                // The caller stopped the close inside its detach callback — a step of its own that threw ends its
                // run as a keep after the detach does: no closing checkpoint of a project that stays open (the
                // app-fix-2 review N4).
                return;
            }
            if (!triggered()) {
                checkpointStepEnded = true;
                steps.outcome(new StepOutcome(Step.CLOSING_CHECKPOINT, StepResult.SKIPPED,
                        "no closing checkpoint is owed"));
                seal();
                return;
            }
            projectManager.writeClosingCheckpoint(project.get())
                    .whenComplete((file, notWritten) -> post(() -> guarded(() -> closingCheckpointEnded(file,
                            notWritten))));
        }

        /** Whether the plan's trigger holds now, right after the detach. FX thread. */
        private boolean triggered() {
            return switch (plan.checkpoint()) {
                case NONE -> false;
                case IF_RECORDS_OUTSIDE_A_CAPTURE -> journal.hasRecordsOutsideACapture();
                case IF_RECORDS_OR_NOT_CHECKPOINTED -> journal.hasRecordsOutsideACapture()
                        || !projectManager.isCheckpointed(project.get());
                case IF_RECORDS_OR_UNSAVED -> journal.hasRecordsOutsideACapture() || unsavedAtTheDetach;
            };
        }

        /**
         * The plan's journal end as decided in the detach's turn: the
         * application-stop fallback's seal leaves a project with unsaved
         * changes unsealed — a drain whose segments the session folds in — so
         * the next open offers to recover them from the closing checkpoint
         * (ruling R4, step 4; {@link ClosingCheckpoint#IF_RECORDS_OR_UNSAVED}).
         */
        private Optional<JournalEnd> journalEnd() {
            if (plan.checkpoint() == ClosingCheckpoint.IF_RECORDS_OR_UNSAVED && unsavedAtTheDetach
                    && plan.journalEnd().equals(Optional.of(JournalEnd.SEAL))) {
                return Optional.of(JournalEnd.DRAIN_UNSEALED);
            }
            return plan.journalEnd();
        }

        private void closingCheckpointEnded(Path file, Throwable notWritten) {
            if (!wanted()) {
                return;
            }
            checkpointStepEnded = true;
            if (notWritten != null) {
                Throwable cause = unwrap(notWritten);
                closingCheckpointFailure = Optional.of(cause);
                LOG.log(Level.WARNING, cause, () -> "The closing checkpoint could not be written; the close goes on");
                steps.outcome(new StepOutcome(Step.CLOSING_CHECKPOINT, StepResult.FAILED,
                        ProjectLifecycleController.failureReason(cause)));
            } else {
                closingCheckpoint = Optional.of(file);
                steps.outcome(new StepOutcome(Step.CLOSING_CHECKPOINT, StepResult.DONE, file.getFileName().toString()));
            }
            seal();
        }

        /** Step 2: the journal end, on its worker. FX thread. */
        private void seal() {
            sealStepBegan = true;
            steps.reached(Step.SEAL);
            Optional<JournalEnd> mode = journalEnd();
            if (mode.isEmpty()) {
                journalPhase.compareAndSet(JournalPhase.NOT_STARTED, JournalPhase.SKIPPED);
                journalEnded.complete(null);
                sealStepEnded = true;
                steps.outcome(new StepOutcome(Step.SEAL, StepResult.SKIPPED, "no journal end"));
                release();
                return;
            }
            Runnable end = ending.prepare(mode.get());
            try {
                sealWorker.execute(() -> runTheJournalEnd(end));
            } catch (RuntimeException notStarted) {
                if (journalPhase.compareAndSet(JournalPhase.NOT_STARTED, JournalPhase.STARTED)) {
                    journalEnded.complete(null);
                    journalEndFinished(notStarted);
                }
            }
        }

        /** On the journal end's worker. */
        private void runTheJournalEnd(Runnable end) {
            if (!journalPhase.compareAndSet(JournalPhase.NOT_STARTED, JournalPhase.STARTED)) {
                return;
            }
            post(() -> guarded(steps::sealBegan));
            Throwable notEnded = null;
            try {
                end.run();
            } catch (RuntimeException | Error failed) {
                notEnded = failed;
            } finally {
                journalEnded.complete(null);
            }
            Throwable outcome = notEnded;
            post(() -> guarded(() -> journalEndFinished(outcome)));
        }

        private void journalEndFinished(Throwable notEnded) {
            if (!wanted()) {
                return;
            }
            sealStepEnded = true;
            JournalEnd mode = journalEnd().orElseThrow();
            if (notEnded == null) {
                steps.outcome(new StepOutcome(Step.SEAL, StepResult.DONE, mode.name()));
                release();
                return;
            }
            Throwable cause = notEnded instanceof UncheckedIOException unchecked && unchecked.getCause() != null
                    ? unchecked.getCause() : notEnded;
            LOG.log(Level.WARNING, cause, () -> "The journal could not be ended (" + mode
                    + "); recovery evidence was preserved");
            steps.outcome(new StepOutcome(Step.SEAL, StepResult.FAILED, ProjectLifecycleController.failureReason(cause)));
            if (plan.goOnAfterSealFailure()) {
                sealFailure = Optional.of(cause);
                release();
            } else {
                awaitingReleaseAnyway = true;
                result.complete(new SealFailed(cause, closingCheckpoint));
            }
        }

        /** Step 3: every writer idle for the exits, then the release. FX thread. */
        private void release() {
            releaseStepBegan = true;
            steps.reached(Step.RELEASE);
            if (plan.writers() == WriterScope.EVERY_WRITER) {
                projectManager.whenWritersIdle().whenComplete((_, _) -> post(() -> guarded(this::releaseNow)));
            } else {
                releaseNow();
            }
        }

        private void releaseNow() {
            if (!wanted()) {
                return;
            }
            CompletableFuture<Void> released = projectManager.closeProject();
            releaseBegan = true;
            steps.releaseBegan();
            if (!plan.awaitRelease()) {
                releaseEnded();
                return;
            }
            CompletableFuture.allOf(released, projectManager.whenRetiredLeasesReleased())
                    .whenComplete((_, _) -> post(() -> guarded(this::releaseEnded)));
        }

        private void releaseEnded() {
            if (stopped || result.isDone()) {
                return;
            }
            steps.outcome(new StepOutcome(Step.RELEASE, StepResult.DONE, plan.awaitRelease()
                    ? "the folder's lock was released" : "the folder's lock is released once its work ended"));
            result.complete(new Released(closingCheckpoint, sealFailure));
        }

        /** Starts the best-effort drain of a stopped close on a virtual thread the close does not wait for. FX thread. */
        private CompletionStage<Void> drain(JournalEnd mode) {
            ending.detach();
            Runnable drain = ending.prepare(mode);
            try {
                Thread.ofVirtual().name(DRAIN_WORKER).start(() -> {
                    try {
                        drain.run();
                    } catch (RuntimeException | Error failed) {
                        LOG.log(Level.WARNING, failed, () -> "The best-effort drain of the journal failed");
                    } finally {
                        journalEnded.complete(null);
                    }
                });
            } catch (RuntimeException notStarted) {
                LOG.log(Level.WARNING, notStarted, () -> "The best-effort drain of the journal could not start");
                journalEnded.complete(null);
            }
            return journalEnded;
        }

        /** Whether a step may still run: the close was not stopped, has not ended, and is still wanted. */
        private boolean wanted() {
            if (stopped || result.isDone()) {
                return false;
            }
            if (!stillWanted.getAsBoolean()) {
                stopped = true;
                complete(new Stopped());
                return false;
            }
            return true;
        }

        private void complete(Result ended) {
            result.complete(ended);
        }
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException wrapped && wrapped.getCause() != null
                ? wrapped.getCause() : failure;
    }
}
