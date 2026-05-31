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
 * step. Long-running offline renders are pushed to a background
 * virtual thread and surfaced through a modeless
 * {@link TaskProgressIndicator}; cancellation rolls back any
 * partially-rendered tracks via {@code undo()}.</p>
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

    private final DawProject project;
    private final UndoManager undoManager;
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

    /** Per-track-id provenance map for the snowflake tooltip. Thread-safe
     *  because recordFreeze() is called from the virtual worker thread while
     *  tooltipFor()/cacheState() are read from the JavaFX application thread. */
    private final Map<String, FreezeRecord> records = new java.util.concurrent.ConcurrentHashMap<>();

    /** Captured state of the most recent freeze for tooltip rendering. */
    private record FreezeRecord(CacheState state, Instant when) { }

    /**
     * Creates a new controller.
     *
     * @param project                  project supplying tracks, mixer,
     *                                 sample rate and tempo (non-null)
     * @param undoManager              undo manager to register actions
     *                                 with so freeze/unfreeze are
     *                                 reversible (non-null)
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
    public TrackFreezeController(DawProject project,
                                 UndoManager undoManager,
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
     * @param project                  project supplying tracks, mixer,
     *                                 sample rate and tempo (non-null)
     * @param undoManager              undo manager to register actions with
     *                                 (non-null)
     * @param owner                    owning JavaFX window for the progress
     *                                 indicator; may be {@code null}
     * @param onTrackFreezeChanged     single-track freeze-state callback
     * @param onSelectionFreezeChanged batch freeze/unfreeze callback
     * @param statusReporter           status-bar reporter (message + icon)
     * @param fxDispatcher             the FX-thread marshalling seam, or
     *                                 {@code null} to use the
     *                                 {@link FxDispatcher#getDefault() default}
     */
    public TrackFreezeController(DawProject project,
                                 UndoManager undoManager,
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
        if (track == null) {
            statusReporter.accept("No track selected to freeze", DawIcon.INFO_CIRCLE);
            return;
        }
        if (track.isFrozen()) {
            statusReporter.accept("Track is already frozen: " + track.getName(), DawIcon.INFO_CIRCLE);
            return;
        }
        MixerChannel channel = project.getMixerChannelForTrack(track);
        if (channel == null) {
            statusReporter.accept("No mixer channel for: " + track.getName(), DawIcon.INFO_CIRCLE);
            return;
        }
        runFreezeAsync(List.of(track), false);
    }

    /**
     * Unfreezes the given track through the undo manager. If the
     * track is null, not frozen, or has no associated mixer channel,
     * the request is silently ignored with a status-bar message.
     */
    public void unfreezeTrack(Track track) {
        if (track == null) {
            statusReporter.accept("No track selected to unfreeze", DawIcon.INFO_CIRCLE);
            return;
        }
        if (!track.isFrozen()) {
            statusReporter.accept("Track is not frozen: " + track.getName(), DawIcon.INFO_CIRCLE);
            return;
        }
        MixerChannel channel = project.getMixerChannelForTrack(track);
        if (channel == null) {
            statusReporter.accept("No mixer channel for: " + track.getName(), DawIcon.INFO_CIRCLE);
            return;
        }
        UnfreezeTrackAction action = new UnfreezeTrackAction(
                track, channel, sampleRate(), tempo(), channels());
        undoManager.execute(action);
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
        List<Track> targets = selection == null ? List.of() : selection.stream()
                .filter(t -> t != null && !t.isFrozen() && project.getMixerChannelForTrack(t) != null)
                .toList();
        if (targets.isEmpty()) {
            statusReporter.accept("Nothing to freeze in selection", DawIcon.INFO_CIRCLE);
            return;
        }
        runFreezeAsync(targets, true);
    }

    /**
     * Unfreezes every frozen track in {@code selection} as a single
     * undo step (via {@link BatchUnfreezeTracksAction}). Unfreezing
     * is fast (it just discards the rendered audio buffer), so no
     * progress indicator is shown.
     */
    public void unfreezeTracks(List<Track> selection) {
        List<Track> targets = selection == null ? List.of() : selection.stream()
                .filter(t -> t != null && t.isFrozen() && project.getMixerChannelForTrack(t) != null)
                .toList();
        if (targets.isEmpty()) {
            statusReporter.accept("Nothing to unfreeze in selection", DawIcon.INFO_CIRCLE);
            return;
        }
        BatchUnfreezeTracksAction action = new BatchUnfreezeTracksAction(
                new ArrayList<>(targets), project::getMixerChannelForTrack,
                sampleRate(), tempo(), channels());
        undoManager.execute(action);
        for (Track t : targets) records.remove(t.getId());
        statusReporter.accept("Unfrozen " + targets.size() + " track(s)", DawIcon.INFO_CIRCLE);
        onSelectionFreezeChanged.run();
    }

    // ── Internal: async render with progress + cancel ───────────────────────

    /**
     * Runs the freeze for one or more tracks on a background virtual
     * thread. For {@code batch == true} (multi-track), the progress
     * indicator shows a Cancel button with cooperative cancellation;
     * for single-track requests the Cancel button is hidden because
     * the render is atomic and cannot be interrupted mid-way.
     * Cancellation is cooperative — the worker checks the cancel flag
     * between tracks and undoes the partial batch.
     *
     * <p>All {@link UndoManager} mutations and status-bar updates are
     * serialized onto the JavaFX application thread so the undo
     * history and UI are only ever modified from a single thread,
     * even though the render itself runs on a virtual thread.</p>
     */
    private void runFreezeAsync(List<Track> targets, boolean batch) {
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
                    List<Track> succeeded = new ArrayList<>();
                    try {
                        if (!batch) {
                            Track t = targets.get(0);
                            FreezeTrackAction action = new FreezeTrackAction(
                                    t, project.getMixerChannelForTrack(t),
                                    sampleRate(), tempo(), channels());
                            runOnFxThreadAndWait(() -> undoManager.execute(action));
                            recordFreeze(t, CacheState.FRESH, start);
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
                                            project::getMixerChannelForTrack,
                                            sampleRate(), tempo(), channels(),
                                            cancelled,
                                            (i, t) -> progress.update((double) i / total,
                                                    "Freezing (" + (i + 1) + "/" + total + "): " + t.getName()),
                                            (i, t) -> {
                                                recordFreeze(t, CacheState.FRESH, Instant.now());
                                                progress.update((double) (i + 1) / total,
                                                        "Frozen (" + (i + 1) + "/" + total + "): " + t.getName());
                                            });
                            runOnFxThreadAndWait(() -> undoManager.execute(batchAction));
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
                                undoManager.undo();
                                undoManager.clearRedoStack();
                            });
                            for (Track t : succeeded) records.remove(t.getId());
                            reportStatus(
                                    "Freeze cancelled — rolled back " + rolledBack + " track(s)",
                                    DawIcon.INFO_CIRCLE);
                        } else {
                            reportStatus(
                                    batch ? "Frozen " + succeeded.size() + " track(s)"
                                          : "Frozen: " + succeeded.get(0).getName(),
                                    DawIcon.INFO_CIRCLE);
                        }
                    } catch (RuntimeException ex) {
                        LOG.log(Level.WARNING, "Track freeze failed", ex);
                        reportStatus("Freeze failed: " + ex.getMessage(), DawIcon.INFO_CIRCLE);
                    } finally {
                        progress.close();
                        Runnable refresh = batch ? onSelectionFreezeChanged
                                : () -> {
                                    if (!succeeded.isEmpty()) {
                                        onTrackFreezeChanged.accept(succeeded.get(0));
                                    }
                                };
                        if (Platform.isFxApplicationThread()) {
                            refresh.run();
                        } else {
                            postFx(refresh);
                        }
                    }
                });
    }

    /**
     * Reports a status message on the FX thread. Safe to call from any
     * thread — marshals through the {@link FxDispatcher} seam (story 289)
     * when off the FX thread.
     */
    private void reportStatus(String message, DawIcon icon) {
        if (Platform.isFxApplicationThread()) {
            statusReporter.accept(message, icon);
        } else {
            postFx(() -> statusReporter.accept(message, icon));
        }
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

    private void recordFreeze(Track track, CacheState state, Instant when) {
        records.put(track.getId(), new FreezeRecord(state, when));
    }

    private int sampleRate() { return (int) project.getFormat().sampleRate(); }
    private double tempo()   { return project.getTransport().getTempo(); }
    private int channels()   { return project.getFormat().channels(); }

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

