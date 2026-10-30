package com.benesquivelmusic.daw.app.ui.status;

import com.benesquivelmusic.daw.app.ui.NotificationBar;
import com.benesquivelmusic.daw.app.ui.NotificationLevel;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.journal.Backpressure;
import com.benesquivelmusic.daw.core.persistence.journal.JournalConfig;
import com.benesquivelmusic.daw.core.persistence.journal.JournalEventRecorder;
import com.benesquivelmusic.daw.core.persistence.journal.JournalListener;
import com.benesquivelmusic.daw.core.persistence.journal.JournalState;
import com.benesquivelmusic.daw.core.persistence.journal.JournalWriter;
import com.benesquivelmusic.daw.core.persistence.journal.ProjectContext;
import com.benesquivelmusic.daw.core.session.SessionManager;
import com.benesquivelmusic.daw.core.session.WorkingSession;
import com.benesquivelmusic.daw.sdk.event.EventBus;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Story 298 — owns the per-project write-ahead-journal lifecycle for daw-app,
 * keeping the wiring out of {@code MainController}'s bulk and unit-testable.
 *
 * <p>When journaled persistence is enabled (the "Use journaled persistence"
 * preference, §7 Stage&nbsp;4 "ships behind a preference for one release"), an
 * {@link #openFor(Path) open} starts a {@link JournalWriter} for the project's
 * {@code journal/} directory and a {@link JournalEventRecorder} that feeds it
 * from the application {@link EventBus}, and registers capture and checkpoint listeners
 * that, once the journal accepts a written checkpoint's marker, retire the
 * records its capture covers and request a segment rotation. Journal
 * telemetry — the queued-since-checkpoint count and the back-pressure level —
 * is published onto the {@link ProjectOperationProgress} model, which the
 * Session Status Strip's Journal cell binds to.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>{@link JournalWriter#offer(String, java.util.UUID, byte[], Instant) offer}
 * is non-blocking and never drops, so the {@link JournalEventRecorder} can feed
 * it from the synchronous publication observer. The {@link JournalListener} callbacks fire on the
 * thread whose journal work caused them (see {@link JournalListener}); the
 * {@link ProjectOperationProgress} mutators self-marshal onto the FX thread
 * (story 289), so they are called directly, and
 * the one user-facing {@link NotificationBar} alert (on
 * {@link Backpressure#UNRESPONSIVE}) is wrapped in
 * {@link FxDispatcher#runOnFx(FxDispatcher, Runnable)} because the bar does not
 * self-marshal. Sealing the session writes a manifest, which is I/O; the caller
 * is expected to invoke {@link #closeCurrent(WorkingSession)} (or
 * {@link #reopenFor(Path, WorkingSession)}) from a background virtual thread.</p>
 *
 * <p>{@link #openFor(Path)}, {@link #closeCurrent(WorkingSession)} and
 * {@link #reopenFor(Path, WorkingSession)} serialise on an internal lock so a
 * rapid project switch can never start an open while a close/seal is still
 * draining; {@link #isOpen()} and {@link #detachFromBus()} read volatile state
 * lock-free so they stay safe to call on the FX thread. The session to seal is
 * <em>captured by the caller on the FX thread</em> and passed in, so the
 * coordinator never reads cross-thread mutable composition-root state.</p>
 */
public final class ProjectJournalCoordinator {

    private static final Logger LOG = Logger.getLogger(ProjectJournalCoordinator.class.getName());

    private final ProjectOperationProgress progress;
    private final NotificationBar notifications;
    private final FxDispatcher dispatcher;
    private final Supplier<Boolean> journaledPersistenceEnabled;
    private final Supplier<EventBus> busSupplier;
    private final CheckpointManager checkpointManager;
    private final SessionManager sessionManager;
    private final InstantSource clock;

    // ── live state (the currently open journal, if any) ────────────────────────
    // writer/recorder are volatile so isOpen() and detachFromBus() can read them
    // lock-free from the FX thread while openFor/closeCurrent mutate them on a
    // background virtual thread. The lifecycleLock below serialises the
    // open/close/reopen *sequences* so they can never interleave.
    private volatile JournalWriter writer;
    private volatile JournalEventRecorder recorder;
    private Runnable rotationListener;
    private Runnable captureListener;
    private volatile Path openProjectDir;

    /** The folders whose journal was last closed unsealed ({@link #closedUnsealed(Path)}). */
    private final Set<Path> closedUnsealed = ConcurrentHashMap.newKeySet();

    /**
     * Serialises {@link #openFor(Path)}, {@link #closeCurrent(WorkingSession)}
     * and {@link #reopenFor(Path, WorkingSession)} so a rapid project switch can
     * never start an open while a close/seal is still draining and sealing. The
     * seal is blocking I/O run on a background virtual thread, so the lock is
     * never acquired on the FX thread.
     */
    private final ReentrantLock lifecycleLock = new ReentrantLock();

    /**
     * Receives the re-sealed session (with the journal segments folded in) so
     * the composition root can keep its {@code activeSession} reference current.
     * Default is a no-op.
     */
    private BiConsumer<Path, WorkingSession> sessionSealedSink = (dir, session) -> { };

    /**
     * Creates a coordinator.
     *
     * @param progress                    the §5.5 status model that the Journal
     *                                    cell binds to; must not be {@code null}
     * @param notifications               the notification bar for the
     *                                    UNRESPONSIVE alert; must not be {@code null}
     * @param dispatcher                  the FX marshalling seam (the bar does
     *                                    not self-marshal); may be {@code null}
     *                                    (falls back to the app-scoped default)
     * @param journaledPersistenceEnabled reads the "Use journaled persistence"
     *                                    preference at open time; must not be {@code null}
     * @param busSupplier                 supplies the application event bus (the
     *                                    journal's change source); must not be {@code null}
     * @param checkpointManager           the checkpoint manager the rotation
     *                                    listener registers on; must not be {@code null}
     * @param sessionManager              persists the sealed session manifest;
     *                                    must not be {@code null}
     * @param clock                       stamps journal records / session seal
     *                                    times (test seam); must not be {@code null}
     */
    public ProjectJournalCoordinator(ProjectOperationProgress progress,
                                     NotificationBar notifications,
                                     FxDispatcher dispatcher,
                                     Supplier<Boolean> journaledPersistenceEnabled,
                                     Supplier<EventBus> busSupplier,
                                     CheckpointManager checkpointManager,
                                     SessionManager sessionManager,
                                     InstantSource clock) {
        this.progress = Objects.requireNonNull(progress, "progress must not be null");
        this.notifications = Objects.requireNonNull(notifications, "notifications must not be null");
        this.dispatcher = dispatcher;
        this.journaledPersistenceEnabled =
                Objects.requireNonNull(journaledPersistenceEnabled, "journaledPersistenceEnabled must not be null");
        this.busSupplier = Objects.requireNonNull(busSupplier, "busSupplier must not be null");
        this.checkpointManager = Objects.requireNonNull(checkpointManager, "checkpointManager must not be null");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Sets the sink that receives the re-sealed session after
     * {@link #closeCurrent(WorkingSession)} folds the journal segments in, so
     * the composition root can update its session reference.
     *
     * @param sink the sealed-session sink; must not be {@code null}
     */
    public void setSessionSealedSink(BiConsumer<Path, WorkingSession> sink) {
        this.sessionSealedSink = Objects.requireNonNull(sink, "sink must not be null");
    }

    /** {@return {@code true} while a journal is open for some project} */
    public boolean isOpen() {
        return writer != null;
    }

    /**
     * The project folder whose journal is open, or {@code null} when none is
     * — also while a close of it is under way, which clears it first.
     * Lock-free.
     *
     * @return the folder, or {@code null}
     */
    public Path currentProjectDirectory() { return openProjectDir; }

    /**
     * Whether a non-control record was offered to the open journal after the
     * highest capture boundary a checkpoint marker recorded (story 333, the
     * studio engineer's closing-checkpoint trigger (i);
     * {@link JournalWriter#offeredMutationsAfterCheckpointBoundary()}).
     * {@code false} when no journal is open. Lock-free, so the close reads it
     * on the FX thread right after the journal was detached from the bus,
     * when no record can be offered any more.
     *
     * @return whether a record lies outside every capture
     */
    public boolean hasRecordsOutsideACapture() {
        JournalWriter w = writer;
        return w != null && w.offeredMutationsAfterCheckpointBoundary();
    }

    /**
     * Seals the open journal into {@code session} if it is still the
     * journal of {@code expectedDirectory} ({@link #closeCurrent(WorkingSession)}),
     * checked under the lifecycle lock. With {@code expectedDirectory}
     * {@code null} — no journal was open when the caller looked — it returns
     * at once and never waits behind another folder's close (story 333).
     *
     * @param expectedDirectory the folder whose journal the caller closes, or {@code null}
     * @param session           the working session to fold the segments into; may be {@code null}
     * @throws java.io.UncheckedIOException if the seal failed (recovery evidence preserved)
     */
    public void closeCurrentFor(Path expectedDirectory, WorkingSession session) {
        if (expectedDirectory == null) {
            return;
        }
        lifecycleLock.lock();
        try {
            if (Objects.equals(expectedDirectory, openProjectDir)) closeCurrent(session);
        } finally { lifecycleLock.unlock(); }
    }

    /**
     * Drains the open journal without sealing it ({@link #drainWithoutSeal})
     * if it is still the journal of {@code expectedDirectory}, checked under
     * the lifecycle lock; returns at once with {@code expectedDirectory}
     * {@code null} (story 333; the studio engineer's ruling 2 OQ-1, constraint
     * 2: a journal end only ever touches the journal of the folder it closes).
     *
     * @param expectedDirectory the folder whose journal the caller closes, or {@code null}
     * @param session           the working session to fold the segments into, or {@code null}
     *                          for no manifest at all
     */
    public void drainWithoutSealFor(Path expectedDirectory, WorkingSession session) {
        if (expectedDirectory == null) {
            return;
        }
        lifecycleLock.lock();
        try {
            if (Objects.equals(expectedDirectory, openProjectDir)) closeCurrent(session, false);
        } finally { lifecycleLock.unlock(); }
    }

    /**
     * Whether the last close of {@code projectDir}'s journal left it unsealed
     * — a drain, or a seal that failed — so a later journal end of the same
     * folder must certify nothing (story 333: a session manifest written then
     * would claim a clean close the journal does not have). A new journal
     * opened for the folder clears it. Lock-free.
     *
     * @param projectDir the project folder
     * @return whether its journal was last closed unsealed
     */
    public boolean closedUnsealed(Path projectDir) {
        return projectDir != null && closedUnsealed.contains(projectDir);
    }

    /**
     * Synchronously detaches the journal's {@link JournalEventRecorder} from the
     * event bus, so no further events are journaled. Fast and FX-thread-safe (it
     * only cancels the bus subscription). Call this on the FX thread when a
     * close begins — <strong>before the EventBus closes</strong> — then finish
     * the drain or seal off the FX thread ({@link #closeCurrentFor},
     * {@link #drainWithoutSealFor}). Idempotent.
     */
    public void detachFromBus() {
        JournalEventRecorder r = this.recorder;
        if (r != null) {
            r.close();
        }
    }

    /**
     * Opens the write-ahead journal for {@code projectDir}. A no-op when
     * journaled persistence is disabled, when {@code projectDir} is {@code null},
     * or when a journal is already open (the caller must {@link #closeCurrent(WorkingSession)}
     * first). Any prior open is left untouched.
     *
     * @param projectDir the project root (must contain — or be able to create —
     *                   {@code journal/})
     */
    public void openFor(Path projectDir) {
        if (projectDir == null) {
            return;
        }
        if (!Boolean.TRUE.equals(journaledPersistenceEnabled.get())) {
            return;
        }
        lifecycleLock.lock();
        try {
            open(projectDir, this::showRecorderFailure);
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Opens the journal and requires readiness before a guarded project installation completes.
     * A no-op when journaled persistence is disabled.
     *
     * @param projectDir the project root; must not be {@code null}
     * @throws IllegalStateException if the enabled journal could not start, with the message
     *                               {@code "Journaled persistence could not start for <dir>: <reason>"}
     *                               and the failure as its cause; the caller's notification is
     *                               the one surface, so the coordinator posts none of its own
     */
    public void openForRequired(Path projectDir) {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        lifecycleLock.lock();
        try {
            if (!Boolean.TRUE.equals(journaledPersistenceEnabled.get())) {
                return;
            }
            Optional<Exception> failure = open(projectDir, _ -> { });
            if (failure.isPresent()) {
                throw new IllegalStateException("Journaled persistence could not start for " + projectDir + ": "
                        + reason(failure.get()), failure.get());
            }
        } finally { lifecycleLock.unlock(); }
    }

    /**
     * Opens the journal for {@code projectDir}; the caller holds the lifecycle lock.
     *
     * @param projectDir             the project root
     * @param recorderFailureSurface shows a recorder that could not attach to the bus
     * @return why no journal is open for {@code projectDir} afterwards; empty when one is
     */
    private Optional<Exception> open(Path projectDir, Consumer<RuntimeException> recorderFailureSurface) {
        if (writer != null) {
            // A journal is already open; the caller is responsible for
            // closing the prior project's journal before opening the next
            // (or using reopenFor(...), which does both under the lock).
            return projectDir.equals(openProjectDir) ? Optional.empty()
                    : Optional.of(new IllegalStateException("a journal is already open for " + openProjectDir));
        }
        EventBus bus = busSupplier.get();
        if (bus == null) {
            LOG.warning("No EventBus available; journaled persistence not started for " + projectDir);
            return Optional.of(new IllegalStateException("no event bus is available"));
        }
        ProjectContext ctx = ProjectContext.forProject(projectDir, clock);
        JournalWriter newWriter = new JournalWriter(ctx, JournalConfig.DEFAULT, true, newListener());
        try {
            newWriter.start();
        } catch (IOException | RuntimeException failure) {
            // A failed start() released what it acquired and removed the
            // journal evidence it could (JournalWriter.start); close() is the
            // safety net for a writer whose start is not failure-atomic. Never
            // sealAndClose(): a session that did not start must not be sealed.
            try { newWriter.close(); }
            catch (RuntimeException notClosed) { failure.addSuppressed(notClosed); }
            LOG.log(Level.WARNING, "Failed to start journal writer for " + projectDir, failure);
            return Optional.of(failure);
        }
        JournalEventRecorder newRecorder;
        try { newRecorder = new JournalEventRecorder(bus, newWriter, clock).start(); }
        catch (RuntimeException failure) {
            endUnrecordedSession(newWriter, failure);
            LOG.log(Level.WARNING, "Journal publication observation is unavailable", failure);
            recorderFailureSurface.accept(failure);
            return Optional.of(failure);
        }
        catch (Error failure) {
            endUnrecordedSession(newWriter, failure);
            throw failure;
        }
        java.util.OptionalLong journalGeneration = checkpointManager.runningGeneration();
        Runnable captureRemoval = checkpointManager.observeJournalSequence(newWriter::captureSequence);
        Runnable listener = checkpointManager.addEventListener(event -> {
            if (event instanceof com.benesquivelmusic.daw.core.persistence.CheckpointEvent.Written written
                    && journalGeneration.isPresent() && written.generation() == journalGeneration.getAsLong()
                    && journalGeneration.equals(checkpointManager.runningGeneration())
                    && written.file().getParent().equals(projectDir.resolve("checkpoints"))) {
                if (written.journalSequence().isPresent()) {
                    long boundary = written.journalSequence().getAsLong();
                    try { newWriter.onCheckpointSucceeded(boundary, written.file(), clock.instant()); }
                    catch (IOException failure) {
                        LOG.log(Level.WARNING, "Could not record checkpoint capture boundary for " + written.file().getFileName()
                                + "; the journal's boundary is unchanged", failure);
                    }
                }
            }
        });

        this.writer = newWriter;
        this.recorder = newRecorder;
        this.rotationListener = listener;
        this.captureListener = captureRemoval;
        this.openProjectDir = projectDir;
        closedUnsealed.remove(projectDir);
        // Seed the Journal cell to a clean state for the freshly-opened project.
        progress.setJournalEventsQueued(0);
        progress.setJournalBackpressure(ProjectOperationProgress.JournalBackpressure.NORMAL);
        LOG.info(() -> "Journaled persistence started for " + projectDir);
        return Optional.empty();
    }

    /**
     * Ends a session whose recorder never attached to the bus. The session
     * started durably and recorded nothing (no recorder, no checkpoint
     * listener yet), so a seal is truthful, and it keeps the next open from
     * offering to recover a session with no work in it. If the seal fails the
     * writer is closed unsealed and the seal failure is attached to
     * {@code failure}.
     */
    private void endUnrecordedSession(JournalWriter started, Throwable failure) {
        try {
            started.sealAndClose(clock.instant());
        } catch (IOException | RuntimeException notSealed) {
            LOG.log(Level.WARNING, "Journal session with no recorder could not be sealed; closing it unsealed", notSealed);
            failure.addSuppressed(notSealed);
            // sealAndClose's finally has already closed the segment and marked
            // the writer closed, so this close() is a no-op guard that keeps the
            // fallback if that ever changes.
            started.close();
        }
    }

    private void showRecorderFailure(RuntimeException failure) {
        FxDispatcher.runOnFx(dispatcher, () -> notifications.show(NotificationLevel.ERROR,
                "Journaled persistence could not start: " + failure.getMessage()));
    }

    /**
     * Why the journal could not start, for a notification that names no file
     * otherwise: the failure's message; for a {@link FileSystemException} that
     * gives no reason (its message is only the file), that message with its
     * type added; its type alone when there is no message. Unlike
     * {@code RecordCoordinator.ioReason} and {@link JournalState#reason}, it
     * keeps the path.
     */
    private static String reason(Exception failure) {
        String message = failure.getMessage();
        String simpleName = failure.getClass().getSimpleName();
        String type = simpleName.isEmpty() ? failure.getClass().getName() : simpleName;
        if (message == null || message.isBlank()) return type;
        if (failure instanceof FileSystemException fileSystemFailure
                && (fileSystemFailure.getReason() == null || fileSystemFailure.getReason().isBlank())) {
            return message + ": " + type;
        }
        return message;
    }

    /**
     * Atomically closes the journal currently open (if any), sealing its
     * segments into {@code outgoingSession}, then opens the journal for
     * {@code newProjectDir} — all under the lifecycle lock so a rapid project
     * switch cannot interleave the close/seal of the old journal with the open
     * of the new one. Call this off the FX thread (it does seal I/O).
     *
     * @param newProjectDir   the project to open a journal for
     * @param outgoingSession the working session of the project being closed, to
     *                        fold the outgoing journal's segments into; may be
     *                        {@code null} when there is nothing to seal
     */
    public void reopenFor(Path newProjectDir, WorkingSession outgoingSession) {
        lifecycleLock.lock();
        try {
            if (writer != null) {
                closeCurrent(outgoingSession);
            }
            openFor(newProjectDir);
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Closes the currently-open journal (recorder + writer) and seals
     * {@code sessionToSeal} by folding in the journal's segment file names and
     * persisting the manifest. A no-op when no journal is open. The session
     * manifest write is I/O — call this off the FX thread.
     *
     * @param sessionToSeal the working session to fold the journal segments
     *                      into; captured on the FX thread by the caller so it
     *                      is the session of the project being closed (not a
     *                      later project that may already have replaced it). May
     *                      be {@code null} when there is nothing to seal.
     */
    public void closeCurrent(WorkingSession sessionToSeal) {
        closeCurrent(sessionToSeal, true);
    }

    /**
     * Closes the open journal without sealing it, folding its segments into
     * {@code sessionToSeal} — or, with {@code null}, writing no manifest at
     * all. The exits that cannot certify a save — Quit anyway, the
     * application-stop fallback with unsaved changes, a lost lock
     * ({@code null} session: no manifest), a keep after the detach, Save As's
     * Continue — never certify the session clean (story 333); the folder is
     * then {@link #closedUnsealed(Path) closed unsealed}. Off the FX thread.
     *
     * @param sessionToSeal the working session to fold the segments into, or {@code null}
     */
    public void drainWithoutSeal(WorkingSession sessionToSeal) {
        closeCurrent(sessionToSeal, false);
    }

    private void closeCurrent(WorkingSession sessionToSeal, boolean seal) {
        lifecycleLock.lock();
        try {
            JournalWriter w = this.writer;
            JournalEventRecorder r = this.recorder;
            Runnable listener = this.rotationListener;
            Runnable captured = this.captureListener;
            Path dir = this.openProjectDir;

            // Clear live state first so a concurrent isOpen() sees "closed".
            this.writer = null;
            this.recorder = null;
            this.rotationListener = null;
            this.captureListener = null;
            this.openProjectDir = null;

            if (listener != null) {
                listener.run();
            }
            if (captured != null) captured.run();
            if (r != null) {
                r.close();
            }
            List<String> segments = List.of();
            if (w != null) {
                boolean sealed = false;
                try {
                    if (seal) {
                        w.sealAndClose(clock.instant());
                        sealed = true;
                    }
                    else w.close();
                }
                catch (IOException failure) {
                    throw new java.io.UncheckedIOException("Project journal could not be sealed; recovery evidence was preserved", failure);
                }
                finally {
                    // Read after the seal (story 333): a checkpoint marker asks the final drain to rotate, so the
                    // seal lands in a segment that did not exist before it; the manifest names that one too.
                    segments = w.segmentFileNames();
                    if (dir != null) {
                        if (sealed) closedUnsealed.remove(dir);
                        else closedUnsealed.add(dir);
                    }
                }
            }
            sealSession(dir, segments, sessionToSeal);
            // Reset the strip's Journal cell now that no journal is feeding it.
            progress.setJournalEventsQueued(0);
            progress.setJournalBackpressure(ProjectOperationProgress.JournalBackpressure.NORMAL);
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Folds {@code segments} into {@code session} and persists the sealed
     * manifest. The session is sealed at {@code now} (mirroring the shutdown
     * close), so the manifest records both the journal links and the close time.
     * A {@code null} session (e.g. an unsaved project) skips sealing.
     */
    private void sealSession(Path dir, List<String> segments, WorkingSession session) {
        if (dir == null || segments.isEmpty() || session == null) {
            return;
        }
        WorkingSession withSegments = session;
        for (String segment : segments) {
            withSegments = withSegments.withJournalSegment(segment);
        }
        try {
            WorkingSession sealed = sessionManager.closeSession(dir, withSegments, clock.instant());
            sessionSealedSink.accept(dir, sealed);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to seal session with journal segments for " + dir, e);
        }
    }

    // ── journal telemetry → status model + notification ─────────────────────────

    private JournalListener newListener() {
        return new JournalListener() {
            @Override
            public void onQueuedCountChanged(int queuedSinceCheckpoint) {
                // ProjectOperationProgress mutators self-marshal (story 289).
                progress.setJournalEventsQueued(queuedSinceCheckpoint);
            }

            @Override
            public void onBackpressure(Backpressure level) {
                ProjectOperationProgress.JournalBackpressure mapped = map(level);
                progress.setJournalBackpressure(mapped);
                if (mapped == ProjectOperationProgress.JournalBackpressure.UNRESPONSIVE) {
                    // The bar does NOT self-marshal — wrap the show().
                    FxDispatcher.runOnFx(dispatcher, () -> notifications.show(
                            NotificationLevel.ERROR,
                            "Disk is unresponsive — journal events are buffering in "
                                    + "memory and will be flushed when the disk recovers."));
                }
            }
        };
    }

    /** Maps the daw-core {@link Backpressure} to the UI {@link ProjectOperationProgress.JournalBackpressure}. */
    static ProjectOperationProgress.JournalBackpressure map(Backpressure level) {
        return switch (level) {
            case NORMAL -> ProjectOperationProgress.JournalBackpressure.NORMAL;
            case BUFFERING -> ProjectOperationProgress.JournalBackpressure.SLOW;
            case UNRESPONSIVE -> ProjectOperationProgress.JournalBackpressure.UNRESPONSIVE;
        };
    }

}
