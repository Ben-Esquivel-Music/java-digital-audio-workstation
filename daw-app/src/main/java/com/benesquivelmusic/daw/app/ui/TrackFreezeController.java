package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.BatchFreezeTracksAction;
import com.benesquivelmusic.daw.core.track.BatchUnfreezeTracksAction;
import com.benesquivelmusic.daw.core.track.FreezeTrackAction;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.UnfreezeTrackAction;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import javafx.application.Platform;
import javafx.stage.Window;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Story 035 — Track Freeze and Unfreeze for CPU Management.
 *
 * <p>Coordinates the user-facing freeze workflow: drives
 * {@link FreezeTrackAction} / {@link UnfreezeTrackAction} (and the
 * batch variants {@link BatchFreezeTracksAction} /
 * {@link BatchUnfreezeTracksAction}) through the project's
 * {@link UndoManager} so every freeze/unfreeze is one reversible
 * step. A background virtual thread sequences each freeze gesture, but
 * the offline render itself runs inside the action's {@code execute()},
 * which that worker posts to the FX thread, so the render blocks the FX
 * thread while it runs (pre-existing, story 035). A freeze is surfaced
 * through a modeless {@link TaskProgressIndicator}. A batch
 * cancellation that is seen rolls back any partially-rendered tracks via
 * {@code undo()}; see {@code runFreezeAsync} for when a Cancel click is
 * seen.</p>
 *
 * <p>For the on-track snowflake (❄) tooltip described in the story,
 * {@link #cacheState(Track)} reports whether the most recent freeze of
 * a given track was a story-206 cache hit, a fresh render, or
 * unknown.</p>
 *
 * <p>This controller owns no JavaFX scene of its own — it is wired to
 * a host application via constructor injection and notifies the host
 * after every successful freeze/unfreeze so the UI can refresh
 * (rebuild track strips, mixer channels, etc.).</p>
 *
 * <p>One controller serves every project generation: it reads the project
 * and its {@link UndoManager} from live suppliers. Each gesture reads that
 * pair once, on the FX thread, at its entry point, and runs entirely
 * against it — the mixer-channel lookups, the render settings, the
 * {@code execute} and a cancelled batch's rollback. A freeze still
 * rendering when another project is opened finishes against the project it
 * started on, and is then discarded without refreshing, recording or
 * reporting anything for the new project. The project is never marked
 * dirty here: every freeze and unfreeze goes through
 * {@link UndoManager#execute}, and the generation's
 * {@code UndoHistoryDirtyBinder} marks that generation's project
 * (Control Synchronization Design Book §5.1, §7).</p>
 */
public final class TrackFreezeController {

    private static final Logger LOG = Logger.getLogger(TrackFreezeController.class.getName());

    /**
     * Last-known cache provenance of a frozen track, displayed as the
     * tooltip on the on-track ❄ snowflake glyph.
     */
    public enum CacheState {
        /** Track was loaded from the persistent rendered-track cache (story 206). */
        HIT,
        /** Track was rendered fresh and (if a cache is wired) written back to disk. */
        FRESH,
        /** Cache integration is not wired or the freeze pre-dates this controller. */
        UNKNOWN
    }

    private final Supplier<DawProject> project;
    private final Supplier<UndoManager> undoManager;
    private final Consumer<Track> onTrackFreezeChanged;
    private final Runnable onSelectionFreezeChanged;
    private final BiConsumer<String, DawIcon> statusReporter;
    private final Window owner;
    /**
     * The FX-thread marshalling seam (story 289), injected on the production
     * path and threaded into the modeless {@link TaskProgressIndicator}. May be
     * {@code null} in a pure-unit context (the compatibility constructor
     * defaults it to {@link FxDispatcher#getDefault()}); {@link #postFx}
     * tolerates the null.
     */
    private final FxDispatcher fxDispatcher;

    /** Per-track-id provenance map for the snowflake tooltip. Written on the
     *  FX thread, when a freeze gesture ends (only if its project is still
     *  the current one) and by the unfreeze entry points; concurrent so a
     *  tooltipFor()/cacheState() read is safe from any thread. */
    private final Map<String, FreezeRecord> records = new java.util.concurrent.ConcurrentHashMap<>();

    /** Captured state of the most recent freeze for tooltip rendering. */
    private record FreezeRecord(CacheState state, Instant when) { }

    /**
     * The project and undo manager one gesture runs against, read once from
     * the suppliers at the gesture's entry point.
     */
    private record Generation(DawProject project, UndoManager undoManager) {

        MixerChannel channelFor(Track track) { return project.getMixerChannelForTrack(track); }
        int sampleRate() { return (int) project.getFormat().sampleRate(); }
        double tempo()   { return project.getTransport().getTempo(); }
        int channels()   { return project.getFormat().channels(); }
    }

    /**
     * Creates a new controller.
     *
     * @param project                  the current project, supplying tracks,
     *                                 mixer, sample rate and tempo; read
     *                                 once per gesture (non-null)
     * @param undoManager              the current project's undo manager, to
     *                                 register actions with so
     *                                 freeze/unfreeze are reversible; read
     *                                 once per gesture (non-null)
     * @param owner                    owning JavaFX window for the
     *                                 progress indicator; may be
     *                                 {@code null}
     * @param onTrackFreezeChanged     callback invoked after a single
     *                                 track's freeze state flips, used
     *                                 to refresh that track's UI
     *                                 (snowflake, mixer strip)
     * @param onSelectionFreezeChanged callback invoked after a batch
     *                                 freeze/unfreeze; the host should
     *                                 refresh every affected strip
     * @param statusReporter           status-bar reporter (message + icon)
     */
    public TrackFreezeController(Supplier<DawProject> project,
                                 Supplier<UndoManager> undoManager,
                                 Window owner,
                                 Consumer<Track> onTrackFreezeChanged,
                                 Runnable onSelectionFreezeChanged,
                                 BiConsumer<String, DawIcon> statusReporter) {
        this(project, undoManager, owner, onTrackFreezeChanged,
                onSelectionFreezeChanged, statusReporter, FxDispatcher.getDefault());
    }

    /**
     * Creates a new controller with an explicit FX-thread marshalling seam
     * (story 289).
     *
     * @param project                  the current project; read once per
     *                                 gesture (non-null)
     * @param undoManager              the current project's undo manager;
     *                                 read once per gesture (non-null)
     * @param owner                    owning JavaFX window for the progress
     *                                 indicator; may be {@code null}
     * @param onTrackFreezeChanged     single-track freeze-state callback
     * @param onSelectionFreezeChanged batch freeze/unfreeze callback
     * @param statusReporter           status-bar reporter (message + icon)
     * @param fxDispatcher             the FX-thread marshalling seam, or
     *                                 {@code null} to use the
     *                                 {@link FxDispatcher#getDefault() default}
     */
    public TrackFreezeController(Supplier<DawProject> project,
                                 Supplier<UndoManager> undoManager,
                                 Window owner,
                                 Consumer<Track> onTrackFreezeChanged,
                                 Runnable onSelectionFreezeChanged,
                                 BiConsumer<String, DawIcon> statusReporter,
                                 FxDispatcher fxDispatcher) {
        this.project = Objects.requireNonNull(project, "project must not be null");
        this.undoManager = Objects.requireNonNull(undoManager, "undoManager must not be null");
        this.onTrackFreezeChanged = onTrackFreezeChanged != null ? onTrackFreezeChanged : t -> {};
        this.onSelectionFreezeChanged = onSelectionFreezeChanged != null ? onSelectionFreezeChanged : () -> {};
        this.statusReporter = statusReporter != null ? statusReporter : (m, i) -> {};
        this.owner = owner;
        // May be null in a pure-unit context; postFx() / TaskProgressIndicator
        // fall back to the static seam, preserving today's behaviour exactly.
        this.fxDispatcher = fxDispatcher;
    }

    /**
     * Posts {@code work} to the FX thread through the injected
     * {@link FxDispatcher} when present, else the static app-scoped seam — the
     * null branch reproduces today's behaviour exactly (story 289). Callers
     * already guard with {@code Platform.isFxApplicationThread()}; this is only
     * the off-thread hop.
     */
    private void postFx(Runnable work) {
        FxDispatcher.runOnFx(fxDispatcher, work);
    }

    /** The current project and undo manager, read once at a gesture's entry point (FX thread). */
    private Generation currentGeneration() {
        return new Generation(
                Objects.requireNonNull(project.get(), "project supplier returned null"),
                Objects.requireNonNull(undoManager.get(), "undoManager supplier returned null"));
    }

    /** Whether {@code generation}'s project and undo manager are both still the current pair (FX thread). */
    private boolean isCurrent(Generation generation) {
        return project.get() == generation.project()
                && undoManager.get() == generation.undoManager();
    }

    /**
     * Returns the most recent cache provenance for the given track, or
     * {@link Optional#empty()} if the track has never been frozen
     * during this session.
     */
    public Optional<CacheState> cacheState(Track track) {
        if (track == null) return Optional.empty();
        FreezeRecord rec = records.get(track.getId());
        return rec == null ? Optional.empty() : Optional.of(rec.state());
    }

    /**
     * Returns a tooltip string for the on-track snowflake glyph.
     * Format depends on the recorded {@link CacheState}: cache-hit
     * tracks read "Loaded from cache (Story 206)", freshly-rendered
     * tracks read "Rendered fresh, cached at <ISO-8601 timestamp>",
     * and unknown-state tracks fall back to a generic message.
     */
    public String tooltipFor(Track track) {
        if (track == null || !track.isFrozen()) {
            return "";
        }
        FreezeRecord rec = records.get(track.getId());
        if (rec == null) {
            return "Frozen — pre-rendered audio is in use";
        }
        return switch (rec.state()) {
            case HIT -> "Loaded from cache (Story 206)";
            case FRESH -> "Rendered fresh, cached at " + rec.when();
            case UNKNOWN -> "Frozen — pre-rendered audio is in use";
        };
    }

    // ── Single-track operations ─────────────────────────────────────────────

    /**
     * Freezes the given track through the undo manager. If the track
     * is null, already frozen, or has no associated mixer channel,
     * the request is silently ignored with a status-bar message.
     */
    public void freezeTrack(Track track) {
        Generation generation = currentGeneration();
        if (track == null) {
            statusReporter.accept("No track selected to freeze", DawIcon.INFO_CIRCLE);
            return;
        }
        if (track.isFrozen()) {
            statusReporter.accept("Track is already frozen: " + track.getName(), DawIcon.INFO_CIRCLE);
            return;
        }
        MixerChannel channel = generation.channelFor(track);
        if (channel == null) {
            statusReporter.accept("No mixer channel for: " + track.getName(), DawIcon.INFO_CIRCLE);
            return;
        }
        runFreezeAsync(new FreezeRun(generation, List.of(track), false));
    }

    /**
     * Unfreezes the given track through the undo manager. If the
     * track is null, not frozen, or has no associated mixer channel,
     * the request is silently ignored with a status-bar message.
     */
    public void unfreezeTrack(Track track) {
        Generation generation = currentGeneration();
        if (track == null) {
            statusReporter.accept("No track selected to unfreeze", DawIcon.INFO_CIRCLE);
            return;
        }
        if (!track.isFrozen()) {
            statusReporter.accept("Track is not frozen: " + track.getName(), DawIcon.INFO_CIRCLE);
            return;
        }
        MixerChannel channel = generation.channelFor(track);
        if (channel == null) {
            statusReporter.accept("No mixer channel for: " + track.getName(), DawIcon.INFO_CIRCLE);
            return;
        }
        UnfreezeTrackAction action = new UnfreezeTrackAction(
                track, channel, generation.sampleRate(), generation.tempo(), generation.channels());
        generation.undoManager().execute(action);
        records.remove(track.getId());
        statusReporter.accept("Unfrozen: " + track.getName(), DawIcon.INFO_CIRCLE);
        onTrackFreezeChanged.accept(track);
    }

    // ── Batch operations ────────────────────────────────────────────────────

    /**
     * Freezes every non-frozen track in {@code selection} as a single
     * undo step (via {@link BatchFreezeTracksAction}). The offline
     * render runs on a background virtual thread; per-track progress
     * is reported through a modeless {@link TaskProgressIndicator}
     * with a Cancel button that rolls the partial batch back.
     */
    public void freezeTracks(List<Track> selection) {
        Generation generation = currentGeneration();
        List<Track> targets = selection == null ? List.of() : selection.stream()
                .filter(t -> t != null && !t.isFrozen() && generation.channelFor(t) != null)
                .toList();
        if (targets.isEmpty()) {
            statusReporter.accept("Nothing to freeze in selection", DawIcon.INFO_CIRCLE);
            return;
        }
        runFreezeAsync(new FreezeRun(generation, targets, true));
    }

    /**
     * Unfreezes every frozen track in {@code selection} as a single
     * undo step (via {@link BatchUnfreezeTracksAction}). Unfreezing
     * is fast (it just discards the rendered audio buffer), so no
     * progress indicator is shown.
     */
    public void unfreezeTracks(List<Track> selection) {
        Generation generation = currentGeneration();
        List<Track> targets = selection == null ? List.of() : selection.stream()
                .filter(t -> t != null && t.isFrozen() && generation.channelFor(t) != null)
                .toList();
        if (targets.isEmpty()) {
            statusReporter.accept("Nothing to unfreeze in selection", DawIcon.INFO_CIRCLE);
            return;
        }
        BatchUnfreezeTracksAction action = new BatchUnfreezeTracksAction(
                new ArrayList<>(targets), generation::channelFor,
                generation.sampleRate(), generation.tempo(), generation.channels());
        generation.undoManager().execute(action);
        for (Track t : targets) records.remove(t.getId());
        statusReporter.accept("Unfrozen " + targets.size() + " track(s)", DawIcon.INFO_CIRCLE);
        onSelectionFreezeChanged.run();
    }

    // ── Internal: async render with progress + cancel ───────────────────────

    /**
     * One freeze gesture: the generation it runs against, its targets, and
     * the outcome its worker hands to the FX thread when it ends. The
     * worker fills the outcome; {@link #finishFreeze} reads it on the FX
     * thread after the worker's last write, through the {@link #postFx} hop.
     */
    private static final class FreezeRun {

        private final Generation generation;
        private final List<Track> targets;
        private final boolean batch;
        /** Tracks this gesture froze, in order; worker-written. */
        private final List<Track> succeeded = new ArrayList<>();
        /** Provenance of each track this gesture froze, committed to the controller's {@code records} only if still current. */
        private final Map<String, FreezeRecord> rendered = new java.util.concurrent.ConcurrentHashMap<>();
        /** The status to report, or {@code null} when the worker ended on an {@link Error}. */
        private volatile String status;
        /** Whether a cancelled batch was rolled back on {@link #generation}'s undo manager. */
        private volatile boolean rolledBack;

        FreezeRun(Generation generation, List<Track> targets, boolean batch) {
            this.generation = generation;
            this.targets = targets;
            this.batch = batch;
        }
    }

    /**
     * Sequences the freeze for one or more tracks from a background
     * virtual thread. For a batch (multi-track) run, the progress
     * indicator shows a Cancel button with cooperative cancellation;
     * for single-track requests the Cancel button is hidden because
     * the render is atomic and cannot be interrupted mid-way.
     * The batch action checks the cancel flag between tracks, inside its
     * {@code execute()} on the FX thread. Because the render blocks the FX
     * thread for the whole batch, a Cancel click is effectively seen only
     * if it arrives before the batch's {@code execute()} starts, and the
     * per-track progress updates do not render while the batch runs
     * (pre-existing, story 035). When the cancellation is seen, the worker
     * undoes the partial batch on the run's captured undo manager.
     *
     * <p>All {@link UndoManager} mutations and status-bar updates are
     * serialized onto the JavaFX application thread so the undo
     * history and UI are only ever modified from a single thread. The
     * virtual worker thread only sequences the gesture: the render
     * itself runs inside {@code execute}, which the worker hands to the
     * FX thread through {@code runOnFxThreadAndWait}
     * ({@code FreezeTrackAction.execute()} and the batch action's
     * {@code execute()} call {@code TrackFreezeService.freeze}), so it
     * blocks the FX thread
     * while it renders (pre-existing, story 035). Every
     * mutation — the {@code execute} and a cancelled batch's
     * {@code undo()} + {@code clearRedoStack()} — goes to the run's
     * captured undo manager, never the live one: after an Open, a live
     * lookup would roll back the new project's last edit.</p>
     */
    private void runFreezeAsync(FreezeRun run) {
        Generation generation = run.generation;
        List<Track> targets = run.targets;
        boolean batch = run.batch;
        TaskProgressIndicator progress = new TaskProgressIndicator(
                owner, batch ? "Freezing " + targets.size() + " tracks…" : "Freezing track…",
                fxDispatcher);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        progress.setOnCancel(() -> cancelled.set(true));
        if (!batch) {
            // Single-track freeze is atomic — cancel is not meaningful.
            progress.hideCancelButton();
        }
        progress.show();
        progress.update(0.0, batch ? "Preparing…" : ("Freezing: " + targets.get(0).getName()));

        Thread.ofVirtual()
                .name("daw-freeze-worker")
                .start(() -> {
                    Instant start = Instant.now();
                    boolean partial = false;
                    List<Track> succeeded = run.succeeded;
                    try {
                        if (!batch) {
                            Track t = targets.get(0);
                            FreezeTrackAction action = new FreezeTrackAction(
                                    t, generation.channelFor(t),
                                    generation.sampleRate(), generation.tempo(), generation.channels());
                            runOnFxThreadAndWait(() -> generation.undoManager().execute(action));
                            run.rendered.put(t.getId(), new FreezeRecord(CacheState.FRESH, start));
                            succeeded.add(t);
                            progress.update(1.0, "Frozen: " + t.getName());
                        } else {
                            // Batch path: a single ProgressAwareBatchAction
                            // is registered with the undo manager so the
                            // entire multi-track operation collapses into
                            // ONE undo step (per story 035: "Internally
                            // invokes BatchFreezeTracksAction so the entire
                            // batch is one undo step"). The synthetic
                            // action also reports per-track progress and
                            // honours cancellation cooperatively.
                            int total = targets.size();
                            ProgressAwareBatchFreezeAction batchAction =
                                    new ProgressAwareBatchFreezeAction(
                                            new ArrayList<>(targets),
                                            generation::channelFor,
                                            generation.sampleRate(), generation.tempo(), generation.channels(),
                                            cancelled,
                                            (i, t) -> progress.update((double) i / total,
                                                    "Freezing (" + (i + 1) + "/" + total + "): " + t.getName()),
                                            (i, t) -> {
                                                run.rendered.put(t.getId(),
                                                        new FreezeRecord(CacheState.FRESH, Instant.now()));
                                                progress.update((double) (i + 1) / total,
                                                        "Frozen (" + (i + 1) + "/" + total + "): " + t.getName());
                                            });
                            runOnFxThreadAndWait(() -> generation.undoManager().execute(batchAction));
                            succeeded.addAll(batchAction.frozenByThisAction());
                            partial = batchAction.wasCancelled();
                        }

                        if (partial) {
                            // User cancelled mid-batch. The synthetic batch
                            // action stopped at the last fully-rendered
                            // track. Roll the entire (partial) batch back
                            // as a single undo step so the project is left
                            // in a consistent state — matches the story's
                            // requirement that "Cancellation is supported
                            // and rolls back any partially-rendered tracks".
                            // After undoing, clear the redo stack so the
                            // cancelled partial-freeze does not linger as a
                            // confusing "Redo" option.
                            int rolledBack = succeeded.size();
                            runOnFxThreadAndWait(() -> {
                                generation.undoManager().undo();
                                generation.undoManager().clearRedoStack();
                            });
                            run.rolledBack = true;
                            run.status = "Freeze cancelled — rolled back " + rolledBack + " track(s)";
                        } else {
                            run.status = batch ? "Frozen " + succeeded.size() + " track(s)"
                                               : "Frozen: " + succeeded.get(0).getName();
                        }
                    } catch (RuntimeException ex) {
                        LOG.log(Level.WARNING, "Track freeze failed", ex);
                        run.status = "Freeze failed: " + failureDetail(ex);
                    } finally {
                        progress.close();
                        if (Platform.isFxApplicationThread()) {
                            finishFreeze(run);
                        } else {
                            postFx(() -> finishFreeze(run));
                        }
                    }
                });
    }

    /**
     * Ends a freeze gesture on the FX thread. If the project the gesture
     * started on is no longer the current one (the user opened, created or
     * restored another project meanwhile), the finished freeze belongs to a
     * closed project: nothing is refreshed or recorded and the status reads
     * "Freeze discarded — project closed". Otherwise the gesture's cache
     * provenance is recorded (or, for a rolled-back batch, cleared), its
     * status reported and the host's refresh callback run.
     */
    private void finishFreeze(FreezeRun run) {
        if (!isCurrent(run.generation)) {
            for (Track t : run.targets) records.remove(t.getId());
            statusReporter.accept("Freeze discarded — project closed", DawIcon.INFO_CIRCLE);
            return;
        }
        if (run.rolledBack) {
            for (Track t : run.succeeded) records.remove(t.getId());
        } else {
            records.putAll(run.rendered);
        }
        if (run.status != null) {
            statusReporter.accept(run.status, DawIcon.INFO_CIRCLE);
        }
        if (run.batch) {
            onSelectionFreezeChanged.run();
        } else if (!run.succeeded.isEmpty()) {
            onTrackFreezeChanged.accept(run.succeeded.get(0));
        }
    }

    /** The failure's message, or its simple class name when it has none, so the user never reads "null". */
    private static String failureDetail(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    /**
     * Executes a {@link Runnable} on the JavaFX application thread and
     * blocks until it completes. Used to serialize {@link UndoManager}
     * mutations from the virtual worker thread onto the single-threaded
     * FX thread so the undo history and project state are only ever
     * modified from one thread. If called from the FX thread already,
     * the runnable is executed inline.
     */
    private void runOnFxThreadAndWait(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
            return;
        }
        Object lock = new Object();
        var done = new AtomicBoolean(false);
        var failure = new java.util.concurrent.atomic.AtomicReference<RuntimeException>();
        postFx(() -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                failure.set(e);
            } finally {
                synchronized (lock) {
                    done.set(true);
                    lock.notifyAll();
                }
            }
        });
        synchronized (lock) {
            while (!done.get()) {
                try { lock.wait(); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(
                            "Thread interrupted while waiting for FX thread to complete UndoManager operation", ie);
                }
            }
        }
        if (failure.get() != null) throw failure.get();
    }

    /**
     * Synthetic single-step undo action used by the batch-freeze path
     * so that a multi-track freeze collapses into ONE entry in the
     * undo history — matching story 035's "the entire batch is one
     * undo step". The action also drives progress callbacks and
     * supports cooperative cancellation.
     *
     * <p>Behaves like {@link BatchFreezeTracksAction} but with two
     * additions: (1) progress callbacks fire before/after each
     * per-track render, and (2) an externally-supplied
     * {@link AtomicBoolean} cancel flag is checked between tracks so
     * the user can stop mid-batch. On undo, every track that was
     * actually frozen by this action is unfrozen.</p>
     */
    private static final class ProgressAwareBatchFreezeAction
            implements com.benesquivelmusic.daw.core.undo.UndoableAction {

        private final List<Track> tracks;
        private final java.util.function.Function<Track, MixerChannel> channelLookup;
        private final int sampleRate;
        private final double tempo;
        private final int channels;
        private final AtomicBoolean cancelFlag;
        private final java.util.function.BiConsumer<Integer, Track> beforeEach;
        private final java.util.function.BiConsumer<Integer, Track> afterEach;
        private final List<Track> frozen = new ArrayList<>();
        private boolean cancelled;

        ProgressAwareBatchFreezeAction(List<Track> tracks,
                                       java.util.function.Function<Track, MixerChannel> channelLookup,
                                       int sampleRate, double tempo, int channels,
                                       AtomicBoolean cancelFlag,
                                       java.util.function.BiConsumer<Integer, Track> beforeEach,
                                       java.util.function.BiConsumer<Integer, Track> afterEach) {
            this.tracks = tracks;
            this.channelLookup = channelLookup;
            this.sampleRate = sampleRate;
            this.tempo = tempo;
            this.channels = channels;
            this.cancelFlag = cancelFlag;
            this.beforeEach = beforeEach;
            this.afterEach = afterEach;
        }

        @Override public String description() { return "Batch Freeze Tracks"; }

        @Override
        public void execute() {
            frozen.clear();
            cancelled = false;
            for (int i = 0; i < tracks.size(); i++) {
                if (cancelFlag.get()) {
                    cancelled = true;
                    return;
                }
                Track t = tracks.get(i);
                if (t.isFrozen()) continue;
                MixerChannel ch = channelLookup.apply(t);
                if (ch == null) continue;
                beforeEach.accept(i, t);
                com.benesquivelmusic.daw.core.track.TrackFreezeService
                        .freeze(t, ch, sampleRate, tempo, channels);
                frozen.add(t);
                afterEach.accept(i, t);
            }
        }

        @Override
        public void undo() {
            for (Track t : frozen) {
                if (t.isFrozen()) {
                    com.benesquivelmusic.daw.core.track.TrackFreezeService.unfreeze(t);
                }
            }
            frozen.clear();
        }

        boolean wasCancelled() { return cancelled; }
        List<Track> frozenByThisAction() { return List.copyOf(frozen); }
    }
}

