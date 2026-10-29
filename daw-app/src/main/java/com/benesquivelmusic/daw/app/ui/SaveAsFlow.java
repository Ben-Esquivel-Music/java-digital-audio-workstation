package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.JournalEnding.JournalEnd;
import com.benesquivelmusic.daw.app.ui.ProjectFolderCloser.ClosingCheckpoint;
import com.benesquivelmusic.daw.app.ui.ProjectFolderCloser.Plan;
import com.benesquivelmusic.daw.app.ui.ProjectFolderCloser.WriterScope;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.persistence.LockStatus;
import com.benesquivelmusic.daw.core.persistence.LockStolenException;
import com.benesquivelmusic.daw.core.persistence.ProjectAssetReferences;
import com.benesquivelmusic.daw.core.persistence.ProjectLock;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.ProjectMetadata;
import com.benesquivelmusic.daw.core.persistence.SaveAsCapture;
import com.benesquivelmusic.daw.core.persistence.SaveAsOutcome;
import com.benesquivelmusic.daw.core.persistence.SaveAsPlan;
import com.benesquivelmusic.daw.core.persistence.SaveAsProgress;
import com.benesquivelmusic.daw.core.project.DawProject;

import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * File ▸ Save As… (story 333; the studio engineer's rulings R5, R6, R7, Q1,
 * Q2, Q6, S5, S6, ruling 2 OQ-1 and ruling 3 Q1): the open project, as it is
 * now, copied into a new folder — every file of it the copy needs through
 * the atomic writer, {@code project.daw} last, a staged copy published by
 * one rename — and then, for a user's Save As, made the open project in the
 * same window: the same live model, so the session and its undo history go
 * on and nothing is reloaded (research "Session management: Robust project
 * save/load with undo/redo history"; the copy's head is the one serializer's
 * render at the current schema, research "4. Project File Format").
 *
 * <h2>The flow</h2>
 * <ol>
 *   <li>Refused while recording, while a take is being written, or while
 *       another switch is pending; a user's Save As is then the one switch
 *       pending — "Save As" — while its chooser and its copy are open.</li>
 *   <li>The target: a save dialog ({@link SaveAsTargetPrompt}) pre-filled,
 *       off the FX thread and within {@link #PREFILL_BOUND}, with the open
 *       folder's parent and {@code <folder name> copy} — or
 *       {@code <folder name>-takeover-<yyyyMMdd-HHmmss>} after a takeover —
 *       the first free name with the {@code " <n>"} suffix; the workspace
 *       root and the project's name for a project never saved, and the
 *       workspace root when the parent is no writable folder or does not
 *       answer in time (ruling R6). The typed name is sanitised.</li>
 *   <li>The capture, on the FX thread, then the modal progress dialog
 *       ({@link SaveAsProgressDialog}), then the plan and the copy on one
 *       {@code daw-save-as} worker. A refused target — taken, inside the
 *       project, not enough space — is said in a modal warning and the
 *       chooser opens again (ruling Q6). Cancel while the plan runs ends the
 *       Save As at once and drops the plan's late result; Cancel while the
 *       files are copied stops the copy, which deletes what it created; from
 *       {@code project.daw}'s write on there is no Cancel.</li>
 *   <li>A user's Save As then takes the copy's lock on the worker and checks
 *       it before the open project is touched: a copy that cannot be locked
 *       is remembered in Recent Projects and reported, and the open project
 *       stays as it was (ruling S5). The open project's folder is then
 *       closed as Discard and close closes it ({@link ProjectFolderCloser}),
 *       nothing written to or deleted from it when it is read-only or its
 *       lock was lost; ten seconds after the copy was locked — the
 *       original's lock still being read, or its close still running — the
 *       dialog offers {@code Continue} (ruling S6), which stops it — a journal end that
 *       had not begun is not run, the original's journal drained unsealed
 *       instead, and one already running is not interrupted; the original's
 *       lock is released only once that journal end, too, has ended — and
 *       opens the copy with no journal this session (ruling 2 OQ-1). The
 *       copy is adopted, its journal opened unless
 *       Continue was chosen or its lock was found lost, and, on the FX thread,
 *       the live project is re-pointed at the files the copy holds, renamed
 *       after the copy's folder, marked clean at the capture's revision and
 *       installed as the strip's new project. A lock lost between the check
 *       and the adoption opens the copy as a project whose lock another
 *       session took (ruling 3 Q1).</li>
 *   <li>At exit ({@link #publishCopyBeforeClosing}) the copy is published
 *       and remembered in Recent Projects, nothing else: no lock, journal or
 *       session (ruling R5).</li>
 * </ol>
 *
 * <h2>Threading</h2>
 * <p>Every method but the workers' runs on the JavaFX thread and never
 * waits: the pre-fill's checks, the plan, the copy, the lock, Recent
 * Projects and the journal's open run on workers, whose results come back
 * through unkeyed {@link FxDispatcher} posts — the chooser, the refusal and
 * the dialog are modal — while the copy's progress reaches the dialog
 * through a keyed post, cancelled before the dialog closes or moves past the
 * copy (javafx skill §11 "Threading"; Control Synchronization book §2.6). A
 * cancel and the worker decide the copy's phase by compare-and-set, and the
 * copy asks once more right after the worker's side of it
 * ({@link SaveAsProgress#writingHead()}), so a cancel never races a
 * published copy into two outcomes.</p>
 */
final class SaveAsFlow {

    private static final Logger LOG = Logger.getLogger(SaveAsFlow.class.getName());

    /** The worker the plan, the copy, the lock and the adoption run on. */
    static final String WORKER = "daw-save-as";

    /** The worker the pre-fill's checks run on. */
    static final String PREFILL_WORKER = "daw-save-as-prefill";

    /** How long the pre-fill's checks may take before the workspace root is pre-filled (ruling R6). */
    static final Duration PREFILL_BOUND = Duration.ofSeconds(2);

    /** How long the original folder's close may take before {@code Continue} is offered (ruling S6). */
    static final Duration ORIGINAL_STILL_CLOSING_DELAY = Duration.ofSeconds(10);

    /** The takeover pre-fill's stamp: {@code BACKUP_STAMP}, local time, {@link Locale#ROOT} digits (ruling R6). */
    private static final DateTimeFormatter TAKEOVER_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    /** What the copy is for. */
    enum Purpose {
        /** A user's Save As: the copy becomes the open project. */
        REPLACE_OPEN,
        /** At exit (a read-only open, a lost lock): the copy is published and remembered, nothing else. */
        PUBLISH_BEFORE_CLOSING
    }

    /** How the chooser's name is pre-filled (ruling R6). */
    enum Prefill {
        /** {@code <folder name> copy}. */
        COPY,
        /** {@code <folder name>-takeover-<yyyyMMdd-HHmmss>}: after another session took the lock over. */
        TAKEOVER
    }

    /** How a Save As ended. */
    sealed interface SaveAsResult permits Published, ChooserCancelled, Cancelled, Failed, Refused { }

    /**
     * The copy was published — and, for a user's Save As, made the open project.
     *
     * @param folder the copy's folder
     */
    record Published(Path folder) implements SaveAsResult {
        Published {
            Objects.requireNonNull(folder, "folder must not be null");
        }
    }

    /** The chooser was cancelled: nothing was copied, and nothing is said. */
    record ChooserCancelled() implements SaveAsResult { }

    /** The progress dialog's Cancel: nothing was left of the copy (R5's INFO says so). */
    record Cancelled() implements SaveAsResult { }

    /**
     * The Save As failed (R5's ERROR, or S5's when the copy was published but could not be opened).
     *
     * @param cause why
     */
    record Failed(Throwable cause) implements SaveAsResult {
        Failed {
            Objects.requireNonNull(cause, "cause must not be null");
        }
    }

    /** Refused before anything was asked: recording, a take being written, another switch pending. */
    record Refused() implements SaveAsResult { }

    /** What the flow needs of the lifecycle controller, on the FX thread unless said. */
    interface Lifecycle {

        /**
         * Makes a user's Save As the one switch pending, choosing its target;
         * refuses — with the pending switch's INFO — while another switch is
         * pending, and silently while a project is being installed.
         *
         * @return whether the Save As may begin
         */
        boolean beginSaveAs();

        /** The pending Save As copies ({@code true}) or chooses its target again ({@code false}). */
        void saveAsCopying(boolean copying);

        /** The Save As is no longer the switch pending. */
        void endSaveAs();

        /** Whether a recording is in flight. */
        boolean recordingInFlight();

        /** Whether a take is still being written. */
        boolean takeBeingWritten();

        /**
         * The copy became the open project: the strip starts over with the
         * copy's head time, and what depends on the open folder follows it —
         * the snapshot browser, the session manifest, the lock indicator and
         * the labels.
         *
         * @param adopted  the copy's metadata
         * @param lockLost the lost lock the adoption met (ruling 3 Q1), the same instance — the loss a close of
         *                 the copy then knows, with its holder, without reading the lock (the studio engineer's
         *                 stage-6 S1); empty when the copy's lock was held or could not be checked
         */
        void copyAdopted(ProjectMetadata adopted, Optional<LockStolenException> lockLost);

        /**
         * Opens the copy's journal, on the calling worker: a fresh session
         * whose base is the copy's head.
         *
         * @param folder the copy's folder
         * @throws RuntimeException if journaled persistence could not start
         */
        void openJournalOf(Path folder);
    }

    /**
     * The flow's collaborators.
     *
     * @param projectManager    the project manager
     * @param lifecycle         the lifecycle controller's part
     * @param folderCloser      closes the original folder
     * @param prompts           the refusal of a target
     * @param targetPrompt      asks for the target
     * @param notifications     the notification bar
     * @param dispatcher        the FX marshalling seam
     * @param project           the live project
     * @param captureLayoutJson the live layout, captured into the project before the copy's capture
     * @param timers            the clock of Continue's ten seconds
     * @param clock             the takeover pre-fill's time
     * @param zone              the takeover pre-fill's zone
     * @param owner             the main window
     * @param workspaceRoot     the workspace ({@code ~/DAW Projects}), or {@code null}
     */
    record Deps(ProjectManager projectManager, Lifecycle lifecycle, ProjectFolderCloser folderCloser,
                ProjectClosePrompts prompts, SaveAsTargetPrompt targetPrompt, NotificationBar notifications,
                FxDispatcher dispatcher, Supplier<DawProject> project, Supplier<String> captureLayoutJson,
                CloseTimers timers, InstantSource clock, ZoneId zone, Supplier<Optional<Window>> owner,
                Supplier<Path> workspaceRoot) {
        Deps {
            Objects.requireNonNull(projectManager, "projectManager must not be null");
            Objects.requireNonNull(lifecycle, "lifecycle must not be null");
            Objects.requireNonNull(folderCloser, "folderCloser must not be null");
            Objects.requireNonNull(prompts, "prompts must not be null");
            Objects.requireNonNull(targetPrompt, "targetPrompt must not be null");
            Objects.requireNonNull(notifications, "notifications must not be null");
            Objects.requireNonNull(dispatcher, "dispatcher must not be null");
            Objects.requireNonNull(project, "project must not be null");
            Objects.requireNonNull(captureLayoutJson, "captureLayoutJson must not be null");
            Objects.requireNonNull(timers, "timers must not be null");
            Objects.requireNonNull(clock, "clock must not be null");
            Objects.requireNonNull(zone, "zone must not be null");
            Objects.requireNonNull(owner, "owner must not be null");
            Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null");
        }
    }

    /** Where a Save As stands; the cancel and the worker decide the copy's phase by compare-and-set. */
    private enum Phase { CHOOSING, PLANNING, COPYING, HEAD, ORIGINAL_CLOSING, ADOPTING, CANCELLED, ENDED }

    /** What the chooser opens with: a folder, or {@code null} for the platform's default, and a name. */
    private record Prefilled(Path directory, String name) { }

    private final Deps deps;
    private Executor worker = task -> Thread.ofVirtual().name(WORKER).start(task);
    private Executor prefillWorker = task -> Thread.ofVirtual().name(PREFILL_WORKER).start(task);
    private Predicate<Path> writableDirectory = SaveAsFlow::isAWritableDirectory;
    private Predicate<Path> nameTaken = SaveAsFlow::exists;
    private Duration prefillBound = PREFILL_BOUND;
    private Function<Optional<Window>, SaveAsProgressDialog> dialogs = SaveAsProgressDialog::new;
    private Planner planner;
    /** Reads the open project's own lock off the FX thread: {@link ProjectManager#whenLockChecked()} unless a test says. */
    private Supplier<CompletableFuture<Optional<LockStolenException>>> originalLockCheck;
    /** The progress dialog of the Save As running now, or {@code null}. FX thread. */
    private SaveAsProgressDialog shownDialog;

    SaveAsFlow(Deps deps) {
        this.deps = Objects.requireNonNull(deps, "deps must not be null");
        this.planner = deps.projectManager()::planSaveAs;
        this.originalLockCheck = deps.projectManager()::whenLockChecked;
    }

    /**
     * A user's Save As — File ▸ Save As…, the takeover dialog's
     * {@code Save As…}, a save failure's {@code Save As…}: the copy becomes
     * the open project. FX thread; returns at once.
     *
     * @param prefill how the chooser's name is pre-filled
     * @return how the Save As ended, completed on the FX thread
     */
    CompletionStage<SaveAsResult> start(Prefill prefill) {
        Objects.requireNonNull(prefill, "prefill must not be null");
        if (deps.lifecycle().recordingInFlight()) {
            return refused(ProjectCloseText.text("saveAs.refused.recording"));
        }
        if (deps.lifecycle().takeBeingWritten()) {
            return refused(ProjectCloseText.text("saveAs.refused.writing"));
        }
        if (!deps.lifecycle().beginSaveAs()) {
            return CompletableFuture.completedFuture(new Refused());
        }
        Run run = new Run(Purpose.REPLACE_OPEN, prefill, () -> { });
        askForTheTarget(run, firstPrefill(run));
        return run.result;
    }

    /**
     * The exit's Save As (rulings R2, R7): the copy is published and
     * remembered in Recent Projects — no lock, journal or session — and the
     * close goes on. It runs under the close's own pending switch and
     * registers none. The chooser's cancel is {@link ChooserCancelled}; a
     * cancelled or failed copy says so itself (R5's INFO and ERROR). FX
     * thread; returns at once.
     *
     * @param prefill       how the chooser's name is pre-filled
     * @param beforeCapture what runs each time a target is chosen, right before the capture — again after a
     *                      refused target reopened the chooser (the close stops playback there, which is
     *                      idempotent)
     * @return how the Save As ended, completed on the FX thread
     */
    CompletionStage<SaveAsResult> publishCopyBeforeClosing(Prefill prefill, Runnable beforeCapture) {
        Run run = new Run(Purpose.PUBLISH_BEFORE_CLOSING, Objects.requireNonNull(prefill, "prefill must not be null"),
                Objects.requireNonNull(beforeCapture, "beforeCapture must not be null"));
        askForTheTarget(run, firstPrefill(run));
        return run.result;
    }

    // ── the target ────────────────────────────────────────────────────────

    private CompletionStage<SaveAsResult> refused(String warning) {
        LOG.warning(warning);
        deps.notifications().show(NotificationLevel.WARNING, warning);
        return CompletableFuture.completedFuture(new Refused());
    }

    /** The first pre-fill of a Save As (ruling R6), checked off the FX thread. FX thread. */
    private CompletableFuture<Prefilled> firstPrefill(Run run) {
        String name;
        Path preferredParent;
        if (run.sourceFolder.isEmpty()) {
            name = sanitizedOrAsIs(run.oldName);
            preferredParent = null;
        } else {
            Path folder = run.sourceFolder.get();
            String folderName = folder.getFileName() == null ? run.oldName : folder.getFileName().toString();
            name = run.prefill == Prefill.TAKEOVER
                    ? folderName + "-takeover-" + TAKEOVER_STAMP.format(LocalDateTime.ofInstant(
                            deps.clock().instant(), deps.zone()))
                    : folderName + " copy";
            preferredParent = folder.getParent();
        }
        return prefillOffTheFxThread(preferredParent, name);
    }

    /**
     * What the chooser opens with: {@code preferredParent} when it is a
     * writable folder that answers within the bound, else the workspace root
     * when it is a writable folder that answers within it, else the
     * platform's default; and the first free name from {@code name} with the
     * {@code " <n>"} suffix in that folder ({@code name} itself when the
     * check does not answer in time). Every check shares one deadline, the
     * bound from now (ruling R6's "within about 2 s"): the two folders are
     * checked at once; the workspace root's answer is waited for only when
     * the parent's is not a writable folder, so a slow workspace root never
     * delays a parent that answered (the app fix pass 1 review, S1); and the
     * free name gets what is left.
     */
    private CompletableFuture<Prefilled> prefillOffTheFxThread(Path preferredParent, String name) {
        Path workspace = deps.workspaceRoot().get();
        long deadline = System.nanoTime() + prefillBound.toNanos();
        CompletableFuture<Boolean> parentWritable = writableWithin(preferredParent, deadline);
        CompletableFuture<Boolean> workspaceWritable = writableWithin(workspace, deadline);
        return parentWritable.thenCompose(parentOk -> parentOk
                        ? CompletableFuture.completedFuture(preferredParent)
                        : workspaceWritable.thenApply(workspaceOk -> workspaceOk ? workspace : null))
                .thenCompose(directory -> directory == null
                        ? CompletableFuture.completedFuture(new Prefilled(null, name))
                        : freeNameWithin(directory, name, deadline));
    }

    /** Whether {@code folder} is a writable folder, checked on the pre-fill worker until {@code deadline}. */
    private CompletableFuture<Boolean> writableWithin(Path folder, long deadline) {
        long left = deadline - System.nanoTime();
        if (folder == null || left <= 0L) {
            return CompletableFuture.completedFuture(false);
        }
        return CompletableFuture.supplyAsync(() -> writableOnTheWorker(folder), prefillWorker)
                .completeOnTimeout(false, left, TimeUnit.NANOSECONDS).exceptionally(_ -> false);
    }

    /** The first free name in {@code parent}, checked on the pre-fill worker until {@code deadline}; else {@code name}. */
    private CompletableFuture<Prefilled> freeNameWithin(Path parent, String name, long deadline) {
        long left = deadline - System.nanoTime();
        if (left <= 0L) {
            return CompletableFuture.completedFuture(new Prefilled(parent, name));
        }
        return CompletableFuture.supplyAsync(() -> freeNameOnTheWorker(parent, name), prefillWorker)
                .completeOnTimeout(new Prefilled(parent, name), left, TimeUnit.NANOSECONDS)
                .exceptionally(_ -> new Prefilled(parent, name));
    }

    /** Whether {@code folder} is a writable folder. The pre-fill worker. */
    private boolean writableOnTheWorker(Path folder) {
        return writableDirectory.test(folder);
    }

    /** The first free name from {@code name} in {@code parent}. The pre-fill worker. */
    private Prefilled freeNameOnTheWorker(Path parent, String name) {
        return new Prefilled(parent, firstFreeName(parent, name));
    }

    /** {@code name}, or the first of {@code "<name> 2"}, {@code "<name> 3"}, … not taken in {@code directory}. Worker. */
    private String firstFreeName(Path directory, String name) {
        String free = name;
        for (int suffix = 2; nameTaken.test(directory.resolve(free)); suffix++) {
            free = name + " " + suffix;
        }
        return free;
    }

    /** The first free name in {@code parent}, checked off the FX thread within the bound; {@code name} otherwise. */
    private CompletableFuture<Prefilled> freeNameOffTheFxThread(Path parent, String name) {
        return freeNameWithin(parent, name, System.nanoTime() + prefillBound.toNanos());
    }

    /** Opens the chooser once {@code prefill} is known, in an unkeyed FX turn (it is modal). */
    private void askForTheTarget(Run run, CompletableFuture<Prefilled> prefill) {
        prefill.whenComplete((prefilled, _) -> post(() -> choose(run, prefilled != null ? prefilled
                : new Prefilled(null, sanitizedOrAsIs(run.oldName)))));
    }

    /** The chooser, then the capture and the copy. FX thread, outside a pulse. */
    private void choose(Run run, Prefilled prefilled) {
        if (run.ended) {
            return;
        }
        Optional<Path> chosen;
        try {
            chosen = deps.targetPrompt().choose(prefilled.directory(), prefilled.name());
        } catch (RuntimeException notShown) {
            fail(run, notShown, prefilled.name());
            return;
        }
        if (chosen.isEmpty()) {
            end(run, new ChooserCancelled());
            return;
        }
        Path picked = chosen.get();
        Path parent = picked.toAbsolutePath().getParent();
        String typed = picked.getFileName() == null ? "" : picked.getFileName().toString();
        String name;
        try {
            name = ProjectManager.sanitizeDirectoryName(typed);
        } catch (IllegalArgumentException invalid) {
            fail(run, invalid, typed);
            return;
        }
        if (parent == null) {
            fail(run, new IOException("A Save As copy needs a folder to be created in: " + picked), name);
            return;
        }
        run.newName = name;
        run.target = parent.resolve(name);
        run.beforeCapture.run();
        DawProject project = run.project;
        project.setLayoutJson(deps.captureLayoutJson().get());
        try {
            run.capture = deps.projectManager().captureForSaveAs(project, name);
        } catch (IOException | RuntimeException notCaptured) {
            fail(run, notCaptured, name);
            return;
        }
        if (run.purpose == Purpose.REPLACE_OPEN) {
            deps.lifecycle().saveAsCopying(true);
        }
        showTheDialog(run, dialog -> dialog.showPlanning(ProjectCloseText.text("saveAs.progress.planning", name)));
        run.phase.set(Phase.PLANNING);
        SaveAsCapture capture = run.capture;
        Path target = run.target;
        try {
            worker.execute(() -> planOnTheWorker(run, capture, target));
        } catch (RuntimeException notStarted) {
            fail(run, notStarted, name);
        }
    }

    // ── the copy ──────────────────────────────────────────────────────────

    /** The plan, then — when it is ready and the Save As was not cancelled — the copy, on the worker. */
    private void planOnTheWorker(Run run, SaveAsCapture capture, Path target) {
        SaveAsPlan plan;
        try {
            plan = planner.plan(capture, target, () -> run.phase.get() == Phase.CANCELLED);
        } catch (CancellationException cancelled) {
            return;
        } catch (IOException | RuntimeException failed) {
            post(() -> {
                if (run.phase.get() == Phase.PLANNING) {
                    fail(run, failed, run.newName);
                }
            });
            return;
        }
        if (plan instanceof SaveAsPlan.Ready ready) {
            if (run.phase.compareAndSet(Phase.PLANNING, Phase.COPYING)) {
                copyOnTheWorker(run, ready);
            }
            return;
        }
        post(() -> targetRefused(run, plan));
    }

    /** The copy, on the worker: progress to the dialog through a keyed post, then the outcome. */
    private void copyOnTheWorker(Run run, SaveAsPlan.Ready ready) {
        SaveAsOutcome outcome = deps.projectManager().publishCopy(ready, new SaveAsProgress() {
            @Override
            public void copied(long bytesCopied, long bytesToCopy) {
                if (bytesToCopy <= 0L) {
                    // Nothing to copy (a project never saved, or one with no audio): the planning line stays
                    // until the head's, never "0 KB of 0 KB".
                    return;
                }
                String line = ProjectCloseText.text("saveAs.progress.copying", run.newName,
                        SaveAsSizeFormat.format(bytesCopied), SaveAsSizeFormat.format(bytesToCopy));
                double fraction = (double) bytesCopied / bytesToCopy;
                deps.dispatcher().onFx(run.progressKey, () -> {
                    SaveAsProgressDialog dialog = run.dialog;
                    if (dialog != null && dialog.isShowing()) {
                        dialog.showCopying(line, fraction);
                    }
                });
            }

            @Override
            public void writingHead() {
                run.phase.compareAndSet(Phase.COPYING, Phase.HEAD);
                String line = ProjectCloseText.text("saveAs.progress.head");
                deps.dispatcher().onFx(run.progressKey, () -> {
                    SaveAsProgressDialog dialog = run.dialog;
                    if (dialog != null && dialog.isShowing()) {
                        dialog.showWritingHead(line);
                    }
                });
            }
        }, () -> run.phase.get() == Phase.CANCELLED);
        switch (outcome) {
            case SaveAsOutcome.Published published -> publishedOnTheWorker(run, published);
            case SaveAsOutcome.Cancelled _ -> post(() -> {
                String cancelled = ProjectCloseText.text("saveAs.cancelled", run.newName);
                LOG.info(cancelled);
                deps.notifications().show(NotificationLevel.INFO, cancelled);
                end(run, new Cancelled());
            });
            case SaveAsOutcome.Failed failed -> post(() -> fail(run, failed.cause(), run.newName));
        }
    }

    /**
     * The copy is published, on the worker. At exit it is remembered in
     * Recent Projects and nothing else; for a user's Save As its lock is
     * taken and checked here — before the open project is touched. The open
     * project's own lock is read next, off this worker, while the dialog can
     * already offer {@code Continue} ({@link #beginClosingTheOriginal}).
     */
    private void publishedOnTheWorker(Run run, SaveAsOutcome.Published published) {
        Path folder = published.folder();
        if (run.purpose == Purpose.PUBLISH_BEFORE_CLOSING) {
            remember(folder);
            post(() -> publishedAtExit(run, published));
            return;
        }
        ProjectManager.PreparedOpen lease = null;
        try {
            lease = deps.projectManager().prepareLockedAccess(folder);
            if (lease.readOnly()) {
                throw new IOException("another session holds its lock");
            }
            lease.lock().verifyHeld();
        } catch (IOException | RuntimeException notLocked) {
            if (lease != null) {
                try {
                    lease.close();
                } catch (IOException notReleased) {
                    notLocked.addSuppressed(notReleased);
                }
            }
            remember(folder);
            post(() -> notOpened(run, published, notLocked, false, false));
            return;
        }
        ProjectManager.PreparedOpen locked = lease;
        post(() -> beginClosingTheOriginal(run, published, locked));
    }

    /** The exit's copy is published and remembered (ruling R5). FX thread. */
    private void publishedAtExit(Run run, SaveAsOutcome.Published published) {
        reportDone(run, published, Optional.empty());
        end(run, new Published(published.folder()));
    }

    /**
     * The copy is published and locked: the open project's folder begins to
     * close (ruling R5). FX thread. The dialog says the original is being
     * closed, and its ten seconds start now (ruling S6) — before the
     * original's own lock is read, which a failing disk can hold for as long
     * as the read takes — so {@code Continue} is offered whichever step
     * hangs; the close itself starts once that read answers
     * ({@link #closeTheOriginal}), unless {@code Continue} started it first.
     */
    private void beginClosingTheOriginal(Run run, SaveAsOutcome.Published published,
                                         ProjectManager.PreparedOpen lease) {
        if (run.ended) {
            releaseOffTheFxThread(lease);
            return;
        }
        run.phase.set(Phase.ORIGINAL_CLOSING);
        // A late keyed "Writing project.daw…" post must not overwrite the step line that follows it.
        deps.dispatcher().cancelKey(run.progressKey);
        if (run.sourceFolder.isPresent()) {
            String closing = ProjectCloseText.text("saveAs.progress.closing", run.oldName);
            showTheDialog(run, dialog -> dialog.showPastTheCopy(closing));
        } else {
            // No folder to close: the head's line, whose keyed post the cancel above may have dropped, stays the
            // step until the opening's — past the copy, Cancel disabled (the app fix pass 1 review, N5).
            String head = ProjectCloseText.text("saveAs.progress.head");
            showTheDialog(run, dialog -> dialog.showPastTheCopy(head));
        }
        run.originalStillClosing = deps.timers().after(ORIGINAL_STILL_CLOSING_DELAY,
                () -> offerToContinue(run, published, lease));
        originalLockCheck.get().whenComplete((stolen, _) -> post(() -> {
            if (run.ended || run.continued || run.closing != null) {
                // Continue closed the original meanwhile, or the Save As ended: the late answer is dropped.
                return;
            }
            closeTheOriginal(run, published, lease, stolen != null && stolen.isPresent());
        }));
    }

    /**
     * Closes the open project's folder as Discard and close closes it (ruling
     * R5), once its lock was read: its closing checkpoint per (i)/(ii), its
     * journal sealed, then its release, which returns at once; nothing
     * written to or deleted from a read-only folder, and nothing into one
     * whose lock another session took but the journal's unsealed drain into
     * this session's own segment (ruling R7's interim). A failed closing
     * checkpoint or seal is logged and the switch goes on (ruling S5). FX
     * thread.
     */
    private void closeTheOriginal(Run run, SaveAsOutcome.Published published, ProjectManager.PreparedOpen lease,
                                  boolean originalLockLost) {
        boolean lockLost = originalLockLost
                || deps.projectManager().getLockManager().status() == LockStatus.STOLEN;
        startClosing(run, published, lease, planForTheOriginal(lockLost));
    }

    /**
     * How the original folder closes, by what is known of it now: no folder
     * or a read-only one, nothing at all — no closing checkpoint and no
     * journal end, so nothing is written to it by construction; a lock lost,
     * no closing checkpoint and the journal drained unsealed with no
     * manifest; otherwise Discard and close's row. FX thread.
     */
    private Plan planForTheOriginal(boolean lockLost) {
        ProjectManager projectManager = deps.projectManager();
        ProjectMetadata open = projectManager.getCurrentProject();
        if (open == null || open.projectPath() == null || projectManager.isReadOnly()) {
            return new Plan(ClosingCheckpoint.NONE, Optional.empty(), WriterScope.OPEN_GENERATION, false, true);
        }
        if (lockLost) {
            return new Plan(ClosingCheckpoint.NONE, Optional.of(JournalEnd.DRAIN_WITHOUT_MANIFEST),
                    WriterScope.OPEN_GENERATION, false, true);
        }
        return new Plan(ClosingCheckpoint.IF_RECORDS_OR_NOT_CHECKPOINTED, Optional.of(JournalEnd.SEAL),
                WriterScope.OPEN_GENERATION, false, true);
    }

    /**
     * Starts the original folder's close as {@code plan} says; its end adopts
     * the copy — unless one of the close's own steps threw before its release
     * began ({@link #originalNotClosed}). FX thread.
     */
    private ProjectFolderCloser.Closing startClosing(Run run, SaveAsOutcome.Published published,
                                                     ProjectManager.PreparedOpen lease, Plan plan) {
        ProjectFolderCloser.Closing closing = deps.folderCloser().close(plan, () -> !run.ended,
                ProjectFolderCloser.Steps.NONE);
        run.closing = closing;
        closing.result().whenComplete((_, failure) -> post(() -> {
            if (failure != null && !closing.releaseBegan()) {
                originalNotClosed(run, published, lease, closing, plan, failure);
            } else {
                originalClosed(run, published, lease);
            }
        }));
        return closing;
    }

    /**
     * The original's close failed before its release began — one of its own
     * steps threw (the studio engineer's ruling on app fix pass 2a, item 5):
     * the original is still the open project, so the copy is never adopted
     * over it. Before
     * its journal's detach the journal is kept as it is (ruling 4 Q3's P1);
     * after it, a journal end that had not begun is drained unsealed (with no
     * manifest when its lock was lost), as a keep after the detach drains it
     * (P2; the studio engineer's stage-8 S6). The copy stays published, is
     * remembered in Recent Projects and its lease is released, off the FX
     * thread; and {@code saveAs.notOpened} says the copy was written but not
     * opened and the original stays open — {@code saveAs.notOpened.paused},
     * with its journaling paused, after the detach of an open journal.
     * Once the release began the original was closed, and the copy is
     * adopted as usual ({@link #originalClosed}). FX thread.
     */
    private void originalNotClosed(Run run, SaveAsOutcome.Published published, ProjectManager.PreparedOpen lease,
                                   ProjectFolderCloser.Closing closing, Plan plan, Throwable failure) {
        if (run.originalStillClosing != null) {
            run.originalStillClosing.cancel();
        }
        // Ruling 4 Q3's P1/P2 for Save As's original (the studio engineer's stage-8 S6): before the detach its
        // journal is kept; after it the journal is drained, and the text says that journaling is paused.
        boolean paused = closing.detached() && closing.journalOpenAtTheDetach();
        closing.skipRemaining(!closing.detached() ? null
                : plan.journalEnd().equals(Optional.of(JournalEnd.DRAIN_WITHOUT_MANIFEST))
                ? JournalEnd.DRAIN_WITHOUT_MANIFEST : JournalEnd.DRAIN_UNSEALED);
        Path folder = published.folder();
        releaseOffTheFxThread(lease);
        offTheFxThread(() -> remember(folder));
        if (run.ended) {
            return;
        }
        Throwable cause = failure instanceof CompletionException wrapped && wrapped.getCause() != null
                ? wrapped.getCause() : failure;
        notOpened(run, published, cause, false, paused);
    }

    /**
     * Ten seconds into the original's close — its lock still being read, or a
     * step of its close still running: the note and {@code Continue} (ruling
     * S6). FX thread.
     */
    private void offerToContinue(Run run, SaveAsOutcome.Published published, ProjectManager.PreparedOpen lease) {
        ProjectFolderCloser.Closing closing = run.closing;
        SaveAsProgressDialog dialog = run.dialog;
        if (run.ended || run.phase.get() != Phase.ORIGINAL_CLOSING || dialog == null
                || closing != null && closing.result().toCompletableFuture().isDone()) {
            return;
        }
        dialog.onContinue(() -> continuePressed(run, published, lease));
        dialog.showOldStillClosing(ProjectCloseText.text("saveAs.oldStillClosing", run.newName, run.oldName));
    }

    /**
     * {@code Continue} (ruling S6, ruling 2 OQ-1): the original's close stops
     * where it is — a journal end not begun becomes a best-effort drain that
     * writes no manifest, a running one is not interrupted — and its folder
     * is released once that journal end, too, has ended; the copy then opens
     * with no journal this session. While the original's lock is still being
     * read, its close starts here as for a lock another session took —
     * nothing written into its folder but that drain — and stops at once;
     * the read's late answer is dropped. FX thread.
     */
    private void continuePressed(Run run, SaveAsOutcome.Published published, ProjectManager.PreparedOpen lease) {
        ProjectFolderCloser.Closing closing = run.closing;
        if (run.ended || run.continued || closing != null && closing.result().toCompletableFuture().isDone()) {
            return;
        }
        run.continued = true;
        if (closing == null) {
            closing = startClosing(run, published, lease, planForTheOriginal(true));
        }
        CompletionStage<Void> journalEnd = closing.skipRemaining(JournalEnd.DRAIN_WITHOUT_MANIFEST);
        if (!closing.releaseBegan()) {
            deps.projectManager().closeProject(journalEnd);
        }
        LOG.warning(() -> "Save As went on while " + run.oldName + " was still being closed: its journal end, if it"
                + " had not begun, is not run, and " + run.newName + " opens with no journal this session");
    }

    /** The original folder is closed: the copy is adopted on the worker. FX thread. */
    private void originalClosed(Run run, SaveAsOutcome.Published published, ProjectManager.PreparedOpen lease) {
        if (run.originalStillClosing != null) {
            run.originalStillClosing.cancel();
        }
        if (run.ended) {
            releaseOffTheFxThread(lease);
            return;
        }
        run.phase.set(Phase.ADOPTING);
        deps.dispatcher().cancelKey(run.progressKey);
        String opening = ProjectCloseText.text("saveAs.progress.opening", run.newName);
        showTheDialog(run, dialog -> dialog.showPastTheCopy(opening));
        boolean continued = run.continued;
        try {
            worker.execute(() -> adoptOnTheWorker(run, published, lease, continued));
        } catch (RuntimeException notStarted) {
            offTheFxThread(() -> notAdoptedOnTheWorker(run, published, lease, notStarted));
        }
    }

    /**
     * The copy could not be adopted after the original was closed — a
     * contract breach, unreachable: its lease released, the copy remembered
     * in Recent Projects, then said. A worker — the FX thread only when no
     * thread can be started ({@link #offTheFxThread}).
     */
    private void notAdoptedOnTheWorker(Run run, SaveAsOutcome.Published published, ProjectManager.PreparedOpen lease,
                                       Throwable cause) {
        closeQuietly(lease);
        remember(published.folder());
        post(() -> notOpened(run, published, cause, true, false));
    }

    /**
     * The adoption and the copy's journal, on the worker: the journal opens
     * only when Continue was not chosen and the copy's lock is still ours —
     * the coordinator makes no lock check of its own (ruling 3 Q1,
     * constraint 6).
     */
    private void adoptOnTheWorker(Run run, SaveAsOutcome.Published published, ProjectManager.PreparedOpen lease,
                                  boolean continued) {
        ProjectManager.AdoptedCopy adopted;
        try {
            adopted = deps.projectManager().adoptPublishedCopy(published, lease, run.project);
        } catch (IOException | RuntimeException breach) {
            LOG.log(Level.SEVERE, breach, () -> "The published copy in " + published.folder() + " could not be"
                    + " adopted");
            notAdoptedOnTheWorker(run, published, lease, breach);
            return;
        }
        Throwable journalFailure = null;
        if (!continued && adopted.lockCheckFailure().isEmpty()) {
            try {
                deps.lifecycle().openJournalOf(published.folder());
            } catch (RuntimeException | Error notOpened) {
                LOG.log(Level.WARNING, notOpened, () -> "The journal of " + published.folder() + " could not open");
                journalFailure = notOpened;
            }
        }
        Throwable journalNotOpened = journalFailure;
        post(() -> adopted(run, published, adopted, journalNotOpened));
    }

    /**
     * The copy is the open project; its FX half: the live references re-pointed
     * at the files the copy holds, the project named after the copy's folder,
     * its metadata the copy's, clean at the capture's revision (setting the
     * name marks nothing dirty), the strip's new project; then the outcome —
     * the Done INFO or the missing-files WARNING first, and the one ERROR
     * that replaces the Done INFO last: the notification bar shows one toast
     * at a time, so the more severe fact, the one that carries the action, is
     * the one left showing, and both reach the history. FX thread.
     */
    private void adopted(Run run, SaveAsOutcome.Published published, ProjectManager.AdoptedCopy adopted,
                         Throwable journalFailure) {
        DawProject project = run.project;
        run.capture.sourceFolder().ifPresent(from -> ProjectAssetReferences.rebase(project, from, published.folder(),
                published.formerRoots().heldFiles()));
        project.setName(run.newName);
        project.setMetadata(adopted.metadata());
        project.markCleanIfUnchanged(run.capture.revision());
        deps.lifecycle().copyAdopted(adopted.metadata(), adopted.lockCheckFailure()
                .filter(LockStolenException.class::isInstance).map(LockStolenException.class::cast));
        Optional<String> error = adoptionError(run, published, adopted, journalFailure);
        reportDone(run, published, error);
        error.ifPresent(text -> {
            LOG.warning(text);
            if (adopted.lockCheckFailure().orElse(null) instanceof LockStolenException) {
                Path copy = published.folder();
                deps.notifications().show(NotificationLevel.ERROR, text, ProjectCloseText.text("saveFailure.saveAs"),
                        () -> {
                            // The action belongs to the copy it describes: once another project is open, the
                            // Notifications history's entry does nothing.
                            if (isTheOpenFolder(copy)) {
                                start(Prefill.TAKEOVER);
                            }
                        });
            } else {
                deps.notifications().show(NotificationLevel.ERROR, text);
            }
        });
        end(run, new Published(published.folder()));
    }

    /** Whether {@code folder} is the open project's folder now. FX thread. */
    private boolean isTheOpenFolder(Path folder) {
        ProjectMetadata open = deps.projectManager().getCurrentProject();
        return open != null && folder.equals(open.projectPath());
    }

    /**
     * The one ERROR that replaces the Done INFO, if any: the copy's lock
     * lost before its adoption (ruling 3 Q1) — or not checkable — then
     * Continue's paused journal (ruling 2 OQ-1), then a journal that could
     * not open.
     */
    private Optional<String> adoptionError(Run run, SaveAsOutcome.Published published,
                                           ProjectManager.AdoptedCopy adopted, Throwable journalFailure) {
        String parent = parentOf(published.folder());
        Optional<IOException> lockFailure = adopted.lockCheckFailure();
        if (lockFailure.isPresent()) {
            if (lockFailure.get() instanceof LockStolenException stolen) {
                ProjectLock holder = stolen.currentHolder();
                return Optional.of(holder == null
                        ? ProjectCloseText.text("saveAs.lockLost.noHolder", run.newName, parent)
                        : ProjectCloseText.text("saveAs.lockLost.holder", run.newName, parent, holder.user(),
                                holder.hostname(), Long.toString(holder.pid())));
            }
            return Optional.of(ProjectCloseText.text("saveAs.journalPaused.openFailed", run.newName, parent,
                    ProjectLifecycleController.failureReason(lockFailure.get())));
        }
        if (run.continued) {
            return Optional.of(ProjectCloseText.text("saveAs.journalPaused.continued", run.newName, parent,
                    run.oldName));
        }
        if (journalFailure != null) {
            return Optional.of(ProjectCloseText.text("saveAs.journalPaused.openFailed", run.newName, parent,
                    ProjectLifecycleController.failureReason(journalFailure)));
        }
        return Optional.empty();
    }

    /**
     * R5's Done INFO — unless an ERROR replaced it — or its missing-files
     * WARNING, which says where the copy still looks for those files: the
     * folder it was copied from, whose absolute paths its references keep
     * (the studio engineer's stage-5 S4). FX thread.
     */
    private void reportDone(Run run, SaveAsOutcome.Published published, Optional<String> error) {
        int missing = published.missingFiles();
        if (missing > 0) {
            String lookedFor = published.sourceFolder().map(Path::toString).orElse("");
            String warning = missing == 1
                    ? ProjectCloseText.text("saveAs.done.missing.one", run.newName, lookedFor)
                    : ProjectCloseText.text("saveAs.done.missing.many", run.newName, Integer.toString(missing),
                            lookedFor);
            LOG.warning(warning);
            deps.notifications().show(NotificationLevel.WARNING, warning);
        } else if (error.isEmpty()) {
            String done = ProjectCloseText.text("saveAs.done", run.newName, parentOf(published.folder()));
            LOG.info(done);
            deps.notifications().show(NotificationLevel.INFO, done);
        }
    }

    /**
     * The copy was published but could not be opened (ruling S5): before the
     * original was touched, it stays open as it is; on the two contract-breach
     * paths after it was closed — unreachable, logged {@code SEVERE} — the
     * text says it was already closed (the studio engineer's stage-5 N6);
     * when the original's close failed after its journal's detach, it stays
     * open with its journaling paused, and the text says so (the studio
     * engineer's stage-8 S6). Either way the copy is in Recent Projects. FX
     * thread.
     */
    private void notOpened(Run run, SaveAsOutcome.Published published, Throwable cause, boolean originalClosed,
                           boolean paused) {
        String text = ProjectCloseText.text(originalClosed ? "saveAs.notOpened.closed"
                        : paused ? "saveAs.notOpened.paused" : "saveAs.notOpened",
                run.newName, parentOf(published.folder()), notOpenedReason(cause), run.oldName);
        LOG.log(originalClosed ? Level.SEVERE : Level.WARNING, cause, () -> text);
        deps.notifications().show(NotificationLevel.ERROR, text);
        end(run, new Failed(cause));
    }

    /**
     * Why the copy could not be opened, as {@code saveAs.notOpened} says it:
     * a lock another session took between the publish and this session's
     * check names its holder (the studio engineer's stage-5 N5); any other
     * failure its own reason.
     *
     * @param cause the failure
     * @return the text
     */
    static String notOpenedReason(Throwable cause) {
        if (cause instanceof LockStolenException stolen) {
            ProjectLock holder = stolen.currentHolder();
            return holder == null ? ProjectCloseText.text("saveAs.notOpened.lockRemoved")
                    : ProjectCloseText.text("saveAs.notOpened.lockTaken", holder.user(), holder.hostname(),
                            Long.toString(holder.pid()));
        }
        return ProjectLifecycleController.failureReason(cause);
    }

    // ── refusals, cancel, failure, the end ──────────────────────────────────

    /**
     * A refused target (ruling Q6): the modal warning, then the chooser again
     * — a taken name with the first free name in the same folder, a folder
     * inside the project with the typed name in the original's parent, not
     * enough space with the same folder and name. FX thread, outside a pulse.
     */
    private void targetRefused(Run run, SaveAsPlan plan) {
        if (!run.phase.compareAndSet(Phase.PLANNING, Phase.CHOOSING)) {
            return;
        }
        hideTheDialog(run);
        if (run.purpose == Purpose.REPLACE_OPEN) {
            deps.lifecycle().saveAsCopying(false);
        }
        deps.prompts().refuseSaveAsTarget(refusalText(plan, run.newName, run.oldName));
        if (run.ended) {
            return;
        }
        askForTheTarget(run, reopened(plan, run));
    }

    /** What the chooser reopens with after {@code plan} refused the target (ruling Q6). FX thread. */
    private CompletableFuture<Prefilled> reopened(SaveAsPlan plan, Run run) {
        Path parent = run.target.getParent();
        return switch (plan) {
            case SaveAsPlan.TargetTaken _ -> freeNameOffTheFxThread(parent, run.newName);
            case SaveAsPlan.TargetInsideProject _ -> CompletableFuture.completedFuture(new Prefilled(
                    run.sourceFolder.map(Path::getParent).orElse(parent), run.newName));
            case SaveAsPlan.NotEnoughSpace _, SaveAsPlan.Ready _ ->
                    CompletableFuture.completedFuture(new Prefilled(parent, run.newName));
        };
    }

    /**
     * The refusal of a target (ruling R5's three texts; Q2's sizes, N6's
     * volume root).
     *
     * @param plan    the refusal
     * @param name    the copy's name
     * @param project the open project's name
     * @return the text
     */
    static String refusalText(SaveAsPlan plan, String name, String project) {
        return switch (plan) {
            case SaveAsPlan.TargetTaken taken -> ProjectCloseText.text("saveAs.refused.taken", taken.name(),
                    taken.parent().toString());
            case SaveAsPlan.TargetInsideProject _ -> ProjectCloseText.text("saveAs.refused.inside", project);
            case SaveAsPlan.NotEnoughSpace space -> {
                List<String> sizes = SaveAsSizeFormat.neededAndUsable(space.needed(), space.usable());
                yield ProjectCloseText.text("saveAs.refused.space", sizes.get(0), space.volumeRoot().toString(),
                        sizes.get(1));
            }
            case SaveAsPlan.Ready _ -> throw new IllegalArgumentException("a ready plan refuses nothing: " + name);
        };
    }

    /**
     * The dialog's Cancel, Escape or close box: taken while the plan or the
     * files run, too late from {@code project.daw}'s write on. FX thread.
     *
     * @return whether the cancel was taken
     */
    private boolean cancelPressed(Run run) {
        if (run.phase.compareAndSet(Phase.PLANNING, Phase.CANCELLED)) {
            // The plan writes nothing: the Save As ends now, and the plan's late result is dropped.
            String cancelled = ProjectCloseText.text("saveAs.cancelled", run.newName);
            LOG.info(cancelled);
            deps.notifications().show(NotificationLevel.INFO, cancelled);
            end(run, new Cancelled());
            return true;
        }
        // The copy deletes what it created when it sees the cancel; its outcome ends the Save As.
        return run.phase.compareAndSet(Phase.COPYING, Phase.CANCELLED);
    }

    private void fail(Run run, Throwable cause, String name) {
        String text = ProjectCloseText.text("saveAs.failed", ProjectLifecycleController.failureReason(cause),
                name == null ? "" : name);
        LOG.log(Level.WARNING, cause, () -> text);
        deps.notifications().show(NotificationLevel.ERROR, text);
        end(run, new Failed(cause));
    }

    /** Ends {@code run} once: the dialog closed, the pending switch ended, the outcome completed. FX thread. */
    private void end(Run run, SaveAsResult result) {
        if (run.ended) {
            return;
        }
        run.ended = true;
        run.phase.set(Phase.ENDED);
        if (run.originalStillClosing != null) {
            run.originalStillClosing.cancel();
        }
        hideTheDialog(run);
        if (run.purpose == Purpose.REPLACE_OPEN) {
            deps.lifecycle().endSaveAs();
        }
        run.result.complete(result);
    }

    /**
     * Shows {@code step} on the progress dialog of {@code run}: on the dialog
     * showing, or on a new one — the step applied before it shows, so it opens
     * with its line. FX thread.
     */
    private void showTheDialog(Run run, Consumer<SaveAsProgressDialog> step) {
        SaveAsProgressDialog dialog = run.dialog;
        if (dialog != null && dialog.isShowing()) {
            step.accept(dialog);
            return;
        }
        dialog = dialogs.apply(deps.owner().get());
        dialog.onCancel(() -> cancelPressed(run));
        step.accept(dialog);
        run.dialog = dialog;
        shownDialog = dialog;
        dialog.show();
    }

    /** Closes the progress dialog of {@code run}, its pending progress dropped first. */
    private void hideTheDialog(Run run) {
        deps.dispatcher().cancelKey(run.progressKey);
        SaveAsProgressDialog dialog = run.dialog;
        run.dialog = null;
        if (dialog != null) {
            dialog.closeByFlow();
        }
        if (shownDialog == dialog) {
            shownDialog = null;
        }
    }

    private void remember(Path folder) {
        try {
            deps.projectManager().rememberRecentProject(folder);
        } catch (RuntimeException notRecorded) {
            LOG.log(Level.WARNING, notRecorded, () -> folder + " could not be recorded in Recent Projects");
        }
    }

    /**
     * Releases an unadopted lease off the FX thread: its release reads and
     * deletes the copy's lock file under a lock wait (stage-5 review N13).
     */
    private void releaseOffTheFxThread(ProjectManager.PreparedOpen lease) {
        offTheFxThread(() -> closeQuietly(lease));
    }

    /**
     * Runs {@code work} — disk work — off the FX thread: on the worker, or on
     * a virtual thread of its own when the worker refuses it; only when no
     * thread can be started, on the caller, with a {@code WARNING}: work that
     * never runs would leave the copy's lock held for the session.
     */
    private void offTheFxThread(Runnable work) {
        try {
            worker.execute(work);
        } catch (RuntimeException refused) {
            try {
                Thread.ofVirtual().name(WORKER).start(work);
            } catch (RuntimeException | Error notStarted) {
                LOG.log(Level.WARNING, notStarted, () -> "No thread could be started for a Save As's disk work; it"
                        + " runs on the calling thread");
                work.run();
            }
        }
    }

    /**
     * Releases an unadopted lease, logging a failure. A worker — the FX
     * thread only when no thread can be started ({@link #offTheFxThread}).
     */
    private static void closeQuietly(ProjectManager.PreparedOpen lease) {
        try {
            lease.close();
        } catch (IOException notReleased) {
            LOG.log(Level.WARNING, notReleased, () -> "The lock of an unadopted Save As copy could not be released");
        }
    }

    private void post(Runnable work) {
        deps.dispatcher().onFx(work);
    }

    private static String parentOf(Path folder) {
        Path parent = folder.getParent();
        return parent == null ? folder.toString() : parent.toString();
    }

    private static String sanitizedOrAsIs(String name) {
        try {
            return ProjectManager.sanitizeDirectoryName(name);
        } catch (IllegalArgumentException invalid) {
            return name;
        }
    }

    private static boolean isAWritableDirectory(Path folder) {
        return Files.isDirectory(folder) && Files.isWritable(folder);
    }

    private static boolean exists(Path path) {
        return Files.exists(path);
    }

    // ── test seams ────────────────────────────────────────────────────────

    /** The progress dialog of the Save As running now, or {@code null}. FX thread. */
    SaveAsProgressDialog progressDialog() {
        return shownDialog;
    }

    /** Replaces the worker the plan, the copy, the lock and the adoption run on. */
    void setWorker(Executor worker) {
        this.worker = Objects.requireNonNull(worker, "worker must not be null");
    }

    /** Replaces the pre-fill's check of a writable folder and its bound. */
    void setPrefillChecks(Predicate<Path> writableDirectory, Duration bound) {
        this.writableDirectory = Objects.requireNonNull(writableDirectory, "writableDirectory must not be null");
        this.prefillBound = Objects.requireNonNull(bound, "bound must not be null");
    }

    /**
     * Replaces the plan of a target ({@link ProjectManager#planSaveAs}), on
     * the worker: a test hands back a refusal no real volume gives on demand
     * — not enough space.
     */
    void setPlanner(Planner planner) {
        this.planner = Objects.requireNonNull(planner, "planner must not be null");
    }

    /**
     * Replaces the read of the open project's own lock before its folder is
     * closed ({@link ProjectManager#whenLockChecked()}): a test holds it, as
     * a failing disk would.
     */
    void setOriginalLockCheck(Supplier<CompletableFuture<Optional<LockStolenException>>> check) {
        this.originalLockCheck = Objects.requireNonNull(check, "check must not be null");
    }

    /** How a Save As target is planned, on the worker: {@link ProjectManager#planSaveAs} unless a test says. */
    @FunctionalInterface
    interface Planner {
        SaveAsPlan plan(SaveAsCapture capture, Path target, java.util.function.BooleanSupplier cancelled)
                throws IOException;
    }

    /** One Save As. FX-confined, but for {@link #phase}, which its workers read. */
    private final class Run {
        final Purpose purpose;
        final Prefill prefill;
        final Runnable beforeCapture;
        final CompletableFuture<SaveAsResult> result = new CompletableFuture<>();
        final AtomicReference<Phase> phase = new AtomicReference<>(Phase.CHOOSING);
        /** The key of the copy's progress posts. */
        final Object progressKey = new Object();
        final DawProject project;
        final String oldName;
        final Optional<Path> sourceFolder;
        String newName;
        Path target;
        SaveAsCapture capture;
        SaveAsProgressDialog dialog;
        ProjectFolderCloser.Closing closing;
        CloseTimers.Cancellable originalStillClosing;
        volatile boolean continued;
        boolean ended;

        Run(Purpose purpose, Prefill prefill, Runnable beforeCapture) {
            this.purpose = purpose;
            this.prefill = prefill;
            this.beforeCapture = beforeCapture;
            this.project = deps.project().get();
            this.oldName = project.getName();
            ProjectMetadata open = deps.projectManager().getCurrentProject();
            this.sourceFolder = Optional.ofNullable(open == null ? null : open.projectPath());
        }
    }
}
