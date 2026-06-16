package com.benesquivelmusic.daw.app.ui.status;

import com.benesquivelmusic.daw.app.ui.NotificationBar;
import com.benesquivelmusic.daw.app.ui.NotificationLevel;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.journal.Backpressure;
import com.benesquivelmusic.daw.core.persistence.journal.JournalConfig;
import com.benesquivelmusic.daw.core.persistence.journal.JournalEventRecorder;
import com.benesquivelmusic.daw.core.persistence.journal.JournalListener;
import com.benesquivelmusic.daw.core.persistence.journal.JournalWriter;
import com.benesquivelmusic.daw.core.persistence.journal.ProjectContext;
import com.benesquivelmusic.daw.core.session.SessionManager;
import com.benesquivelmusic.daw.core.session.WorkingSession;
import com.benesquivelmusic.daw.sdk.event.AutoSaveListener;
import com.benesquivelmusic.daw.sdk.event.EventBus;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
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
 * from the application {@link EventBus}, and registers an {@link AutoSaveListener}
 * that rotates the journal on every successful checkpoint. Journal telemetry —
 * the queued-since-checkpoint count and the back-pressure level — is published
 * onto the {@link ProjectOperationProgress} model, which the Session Status
 * Strip's Journal cell binds to.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>{@link JournalWriter#offer(String, java.util.UUID, byte[], Instant) offer}
 * is non-blocking and never drops, so the {@link JournalEventRecorder} can feed
 * it straight from the bus callback (the recorder runs
 * {@code ON_CALLER_THREAD}). The {@link JournalListener} callbacks fire on the
 * writer's virtual thread; the {@link ProjectOperationProgress} mutators
 * self-marshal onto the FX thread (story 289), so they are called directly, and
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
    private AutoSaveListener rotationListener;
    private Path openProjectDir;

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
     * Synchronously detaches the journal's {@link JournalEventRecorder} from the
     * event bus, so no further events are journaled. Fast and FX-thread-safe (it
     * only cancels the bus subscription). Call this on the FX thread at the start
     * of shutdown — <strong>before the EventBus closes</strong> — then finish the
     * drain/seal with {@link #closeCurrent()} off the FX thread. Idempotent.
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
     * or when a journal is already open (the caller must {@link #closeCurrent()}
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
            if (writer != null) {
                // A journal is already open; the caller is responsible for
                // closing the prior project's journal before opening the next
                // (or using reopenFor(...), which does both under the lock).
                return;
            }
            EventBus bus = busSupplier.get();
            if (bus == null) {
                LOG.warning("No EventBus available; journaled persistence not started for " + projectDir);
                return;
            }
            ProjectContext ctx = ProjectContext.forProject(projectDir, clock);
            JournalWriter newWriter = new JournalWriter(ctx, JournalConfig.DEFAULT, true, newListener());
            try {
                newWriter.start();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to start journal writer for " + projectDir, e);
                return;
            }
            JournalEventRecorder newRecorder =
                    new JournalEventRecorder(bus, newWriter, clock).start();
            AutoSaveListener listener = new RotationListener(newWriter);
            checkpointManager.addListener(listener);

            this.writer = newWriter;
            this.recorder = newRecorder;
            this.rotationListener = listener;
            this.openProjectDir = projectDir;
            // Seed the Journal cell to a clean state for the freshly-opened project.
            progress.setJournalEventsQueued(0);
            progress.setJournalBackpressure(ProjectOperationProgress.JournalBackpressure.NORMAL);
            LOG.info(() -> "Journaled persistence started for " + projectDir);
        } finally {
            lifecycleLock.unlock();
        }
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
        lifecycleLock.lock();
        try {
            JournalWriter w = this.writer;
            JournalEventRecorder r = this.recorder;
            AutoSaveListener listener = this.rotationListener;
            Path dir = this.openProjectDir;

            // Clear live state first so a concurrent isOpen() sees "closed".
            this.writer = null;
            this.recorder = null;
            this.rotationListener = null;
            this.openProjectDir = null;

            if (listener != null) {
                checkpointManager.removeListener(listener);
            }
            if (r != null) {
                r.close();
            }
            List<String> segments = List.of();
            if (w != null) {
                segments = w.segmentFileNames();
                w.close();
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

    /** Rotates the journal on every successful checkpoint (the new checkpoint subsumes the segment). */
    private static final class RotationListener implements AutoSaveListener {
        private final JournalWriter writer;

        RotationListener(JournalWriter writer) {
            this.writer = writer;
        }

        @Override
        public void onBeforeCheckpoint(String checkpointId) {
            // no-op
        }

        @Override
        public void onAfterCheckpoint(String checkpointId) {
            writer.onCheckpointSucceeded();
        }

        @Override
        public void onCheckpointFailed(String checkpointId, Throwable cause) {
            // no-op — a failed checkpoint must not rotate the journal away.
        }
    }
}
