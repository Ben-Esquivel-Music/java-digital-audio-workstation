package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.persistence.AssetReference;
import com.benesquivelmusic.daw.core.persistence.ClipRehydrator;
import com.benesquivelmusic.daw.core.persistence.MissingAsset;
import com.benesquivelmusic.daw.core.persistence.MissingAssetsReport;
import com.benesquivelmusic.daw.core.persistence.RehydrationListener;
import com.benesquivelmusic.daw.core.persistence.RehydrationOutcome;
import com.benesquivelmusic.daw.core.persistence.RehydrationSession;
import com.benesquivelmusic.daw.core.persistence.UnreadProjectItem;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The owner of the open project's audio (story 329; Persistence Integrity
 * book §4.3, §5.3 steps 4 REHYDRATE, 5 ACCOUNT and 8 ANNOUNCE, §6.1, §6.2).
 * Confined to the FX thread: every method but the rehydration listener it
 * hands the core runs there.
 *
 * <p><strong>One run per open.</strong> {@link #begin} retires the previous
 * run, starts the core {@link ClipRehydrator} for the project just installed
 * and returns the open's {@link Generation}. The run owns the core session,
 * the live missing-assets report — the open's rows, then the rows the
 * rehydration adds, then the rows a relink or a skip removes
 * ({@link AssetRelinkFlow}, through {@link #editReport}) — the
 * {@code Loading audio — N of M} strip operation and the report's one
 * window.</p>
 *
 * <p><strong>One completion queue, one keyed drain (decision D12).</strong>
 * The decode threads only queue: each outcome, then — last, once — the
 * run's end, followed by {@code onFx(drainKey, drain)} under the run's one
 * key. The drain empties the queue on the FX thread, applies every outcome
 * to the live project ({@link RehydrationSession#apply}), adds the rows they
 * produce, updates the strip operation and repaints the arrangement once. No
 * completion is lost to the per-frame coalescing (the queue holds them, the
 * key only says "this run has completions"), the end never overtakes an
 * outcome, and a pulse repaints at most once however many clips landed. A
 * run always ends (decisions D33, D39, D40): a failure applying one outcome
 * is contained to it, the progress, the end and the repaint run even when
 * the report update failed, and once the end is taken every source the
 * session still owes — its outcome lost, or its apply failed — becomes a
 * row instead of loading for the rest of the session.</p>
 *
 * <p>{@code Review…} actions left in the notification history hold the
 * open's number, never its project ({@link ReviewAction}).</p>
 *
 * <p><strong>The fence (decision D11).</strong> Before any effect — an
 * attach, a row, the strip, the announcement, the report, a repaint — the
 * run must be the current one and its project the live project. The core's
 * cancel is best-effort; this check is the one that decides.</p>
 */
final class OpenProjectAudio {

    private static final Logger LOG = Logger.getLogger(OpenProjectAudio.class.getName());

    /**
     * One open of one project: what the fence compares and what a deferred
     * action of the open (a relink result) carries; a {@code Review…} of the
     * notification history carries only its {@link #id()}.
     *
     * @param id      the open's number, increasing for the life of the owner
     * @param project the project the open installed
     */
    record Generation(long id, DawProject project) {
        Generation {
            Objects.requireNonNull(project, "project must not be null");
        }
    }

    /**
     * Whether the open project's audio is ready — the one query the export,
     * bounce and freeze guards ({@link RenderReadinessGuard}) consult
     * (decisions D19, D28).
     *
     * @param loading          a clip of the query's tracks is still
     *                         {@link ClipAudioState#LOADING} — the current
     *                         run still owes its source
     * @param loadedSources    the sources the run has applied (decoded or
     *                         failed), over the whole project
     * @param totalSources     the sources the run decodes, over the whole project
     * @param unresolvedAssets the distinct assets of the live report — the
     *                         rows a relink can still act on (a skipped row
     *                         is not one)
     * @param silentClips      the clips of the query's tracks that read from a
     *                         file, have no audio, and that nothing is
     *                         loading — missing, unreadable, too large,
     *                         skipped: the clips a render would leave silent
     */
    record Readiness(boolean loading, int loadedSources, int totalSources, int unresolvedAssets,
                     int silentClips) {
    }

    /**
     * Shows a generation's missing-assets report. The default shows the
     * non-modal {@code MissingAssetsDialog}; tests count presentations.
     */
    @FunctionalInterface
    interface ReportPresenter {

        /**
         * Shows the report of {@code generation} for the first time and
         * returns its surface, which the owner keeps for the generation:
         * later {@code Review…} actions {@linkplain ReportView#present()
         * re-present} it, report changes {@linkplain ReportView#update
         * update} it, and retiring the run {@linkplain ReportView#dismiss()
         * dismisses} it. Called at most once per generation.
         *
         * @param generation the open the report belongs to
         * @param report     the report as it is now
         * @return the surface showing it
         */
        ReportView present(Generation generation, MissingAssetsReport report);
    }

    /**
     * Applies one source's outcome to the open's core session — the step the
     * drain contains per completion (decision D33). The default is the
     * session's own {@link RehydrationSession#apply}; a test makes it fail
     * for one source.
     */
    @FunctionalInterface
    interface OutcomeApplier {

        /**
         * Applies {@code outcome} to {@code session}. FX thread.
         *
         * @param session the current run's session
         * @param outcome one source's outcome
         * @return the rows the outcome produced
         */
        List<MissingAsset> apply(RehydrationSession session, RehydrationOutcome outcome);
    }

    /** A generation's one report surface (decision D15: one instance per generation). */
    interface ReportView {

        /** Shows the surface again, or brings it to the front when it is showing. */
        void present();

        /**
         * Shows {@code report} instead of what it shows.
         *
         * @param report the live report
         */
        void update(MissingAssetsReport report);

        /** Closes the surface for good: its generation was retired. */
        void dismiss();
    }

    private final FxDispatcher dispatcher;
    private final ProjectOperationProgress progress;
    private final NotificationBar notificationBar;
    private final Supplier<DawProject> liveProject;
    private final Runnable repaint;
    private final List<Runnable> changeListeners = new ArrayList<>();
    private ClipRehydrator rehydrator = new ClipRehydrator();
    private OutcomeApplier outcomeApplier = (session, outcome) -> session.apply(outcome).rows();
    private ReportPresenter presenter;
    private Run current;
    private long lastGeneration;

    /**
     * Creates the owner.
     *
     * @param dispatcher      the FX marshalling seam whose keyed work runs the drain
     * @param progress        the status strip's operation model
     * @param notificationBar where the announcement and the follow-up go
     * @param liveProject     the live project, read for the fence
     * @param repaint         repaints the arrangement, once per drain
     * @param presenter       shows a generation's report
     */
    OpenProjectAudio(FxDispatcher dispatcher, ProjectOperationProgress progress,
                     NotificationBar notificationBar, Supplier<DawProject> liveProject,
                     Runnable repaint, ReportPresenter presenter) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        this.progress = Objects.requireNonNull(progress, "progress must not be null");
        this.notificationBar = Objects.requireNonNull(notificationBar, "notificationBar must not be null");
        this.liveProject = Objects.requireNonNull(liveProject, "liveProject must not be null");
        this.repaint = Objects.requireNonNull(repaint, "repaint must not be null");
        this.presenter = Objects.requireNonNull(presenter, "presenter must not be null");
    }

    // ── the run ──────────────────────────────────────────────────────────────

    /**
     * Begins the audio of a project just installed as the live project
     * (§5.3 step 4 REHYDRATE): retires the previous run, logs the
     * reference-track files the open found missing (decision D9: logged,
     * never counted), and starts decoding every clip source the report does
     * not already name, those under {@code playheadBeat} first. Returns at
     * once — it queues work and does no I/O.
     *
     * @param project          the live project
     * @param projectDirectory the folder it was opened from, or {@code null}
     * @param atOpen           the open's missing-assets report
     * @param playheadBeat     the transport position the user hears first
     * @return the open's generation
     */
    Generation begin(DawProject project, Path projectDirectory, MissingAssetsReport atOpen, double playheadBeat) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(atOpen, "atOpen must not be null");
        retire();
        Generation generation = new Generation(++lastGeneration, project);
        Run run = new Run(generation, projectDirectory, atOpen);
        current = run;
        if (!atOpen.missingReferenceTrackPaths().isEmpty()) {
            LOG.warning(() -> "Reference-track audio is not loaded with the project (future story 359); "
                    + "the open also found these reference-track files missing: "
                    + atOpen.missingReferenceTrackPaths());
        }
        try {
            run.session = rehydrator.start(project, atOpen,
                    Double.isFinite(playheadBeat) ? playheadBeat : 0.0, run.listener());
        } catch (RuntimeException failed) {
            LOG.log(Level.WARNING, "The clip audio of " + project.getName()
                    + " could not be scheduled for loading; its clips stay offline", failed);
        }
        if (run.session != null && run.session.sourceCount() > 0) {
            run.operation = loadingOperation(0, run.session.sourceCount());
            progress.addOperation(run.operation);
        }
        fireChange();
        return generation;
    }

    /**
     * Retires the current run, if any: its decodes are cancelled, its
     * queued completions dropped, its strip operation removed and its
     * report closed; from now on nothing of it has an effect. Idempotent.
     */
    void retire() {
        Run run = current;
        if (run == null) {
            return;
        }
        current = null;
        if (run.session != null) {
            run.session.cancel();
        }
        dispatcher.cancelKey(run.drainKey);
        run.completions.clear();
        if (run.operation != null) {
            progress.removeOperation(run.operation);
            run.operation = null;
        }
        if (run.view != null) {
            ReportView view = run.view;
            run.view = null;
            view.dismiss();
        }
        fireChange();
    }

    /**
     * Whether {@code generation} is the current run's, and its project is
     * still the live project — the fence every deferred effect passes.
     *
     * @param generation the generation to check
     * @return {@code true} if it is current
     */
    boolean isCurrent(Generation generation) {
        Run run = current;
        return run != null && run.generation.equals(generation) && liveProject.get() == generation.project();
    }

    /**
     * Returns the current generation, when its project is the live one.
     *
     * @return the current generation, or empty
     */
    Optional<Generation> currentGeneration() {
        Run run = fencedCurrent();
        return run == null ? Optional.empty() : Optional.of(run.generation);
    }

    /**
     * Returns the folder the current generation was opened from.
     *
     * @return the project folder, or empty when there is none or no current generation
     */
    Optional<Path> projectDirectory() {
        Run run = fencedCurrent();
        return run == null ? Optional.empty() : Optional.ofNullable(run.projectDirectory);
    }

    // ── the announcement and the report ──────────────────────────────────────

    /**
     * Shows the open's one announcement (§5.3 step 8 ANNOUNCE; decisions
     * D8, D14) for {@code door}, qualified by the run's report and by
     * {@code unreadItems} (story 330), and — when the live report has rows —
     * presents the report, once (D15). Does nothing unless
     * {@code generation} is current, so a load whose migration dialog was
     * rolled back, which re-entered the load, announces nothing.
     *
     * @param generation       the open
     * @param door             how it was opened
     * @param projectDirectory the opened folder (the archive's destination)
     * @param unreadItems      the saved items the open's read could not honour
     *                         as written ({@link OpenAnnouncement#of})
     */
    void announce(Generation generation, OpenDoor door, Path projectDirectory,
                  List<UnreadProjectItem> unreadItems) {
        if (!isCurrent(generation)) {
            return;
        }
        Run run = current;
        OpenAnnouncement announcement = OpenAnnouncement.of(door, projectDirectory, run.report, unreadItems);
        run.announced = assetsOf(run.report);
        if (announcement.offersReview()) {
            notificationBar.show(announcement.level(), announcement.message(),
                    ProjectAudioText.text("projectOpen.review"), reviewAction(generation));
        } else {
            notificationBar.show(announcement.level(), announcement.message());
        }
        if (!run.report.isEmpty()) {
            presentReport(generation);
        }
    }

    /**
     * Presents the report of {@code generation}: shows it the first time,
     * re-presents the same surface after. Does nothing for a generation that
     * is not current.
     *
     * @param generation the open whose report to show
     */
    void presentReport(Generation generation) {
        if (!isCurrent(generation)) {
            return;
        }
        Run run = current;
        if (run.view == null) {
            run.view = presenter.present(generation, run.report);
        } else {
            run.view.present();
        }
    }

    /**
     * Returns the live missing-assets report of the current generation.
     *
     * @return the report; {@link MissingAssetsReport#EMPTY} when nothing is owned
     */
    MissingAssetsReport report() {
        Run run = fencedCurrent();
        return run == null ? MissingAssetsReport.EMPTY : run.report;
    }

    /**
     * Replaces the live report of {@code generation} with {@code edit}'s
     * result — the seam a relink or a skip removes its row through — and
     * updates the report's surface. Does nothing for a generation that is
     * not current.
     *
     * @param generation the open the edit belongs to
     * @param edit       computes the new report from the live one
     * @return {@code true} if the edit was applied
     */
    boolean editReport(Generation generation, UnaryOperator<MissingAssetsReport> edit) {
        Objects.requireNonNull(edit, "edit must not be null");
        if (!isCurrent(generation)) {
            return false;
        }
        Run run = current;
        MissingAssetsReport edited = Objects.requireNonNull(edit.apply(run.report), "edit returned null");
        if (edited != run.report) {
            run.report = edited;
            reportChanged(run);
        }
        return true;
    }

    /**
     * Shows the live report of {@code generation} again on its surface, when
     * it has one — the rows' relink state changed (a check began, a located
     * file was refused), not the report. Does nothing for a generation that
     * is not current.
     *
     * @param generation the open whose report surface to refresh
     */
    void refreshReport(Generation generation) {
        if (!isCurrent(generation)) {
            return;
        }
        Run run = current;
        if (run.view != null) {
            run.view.update(run.report);
        }
    }

    // ── queries ──────────────────────────────────────────────────────────────

    /**
     * What {@code clip}'s body shows (decision D18): its waveform once it
     * has audio, {@code Loading…} while the current run still owes its
     * source, {@code Offline} for any other clip that reads from a file.
     *
     * @param clip a clip of the live project
     * @return the clip's state
     */
    ClipAudioState stateOf(AudioClip clip) {
        ClipAudioState unmanaged = ClipAudioState.unmanaged(clip);
        if (unmanaged != ClipAudioState.OFFLINE) {
            return unmanaged;
        }
        Run run = fencedCurrent();
        if (run != null && run.session != null) {
            Optional<AssetReference> source = AssetReference.of(clip);
            if (source.isPresent() && run.session.isPending(source.get())) {
                return ClipAudioState.LOADING;
            }
        }
        return ClipAudioState.OFFLINE;
    }

    /**
     * Whether the open project's audio is ready, its clips counted over the
     * whole live project.
     *
     * @return the readiness
     * @see #readinessOf(Collection)
     */
    Readiness readiness() {
        DawProject project = liveProject.get();
        return readinessOf(project == null ? List.of() : project.getTracks());
    }

    /**
     * Whether the open project's audio is ready for a render of
     * {@code tracks} (decisions D19, D28, D31; the one readiness query):
     * whether a clip of {@code tracks} is still loading, how far the run got
     * over the whole project, how many assets the report still names, and
     * how many clips of {@code tracks} would render silent
     * ({@link ClipAudioState#OFFLINE}, counted live — a skipped row's clips
     * included). With no current run — none began, or the live project is
     * not the run's (a DAWproject import, say) — nothing is loading and
     * every clip that reads from a file and has no audio is silent.
     *
     * @param tracks the tracks the render reads
     * @return the readiness
     */
    Readiness readinessOf(Collection<Track> tracks) {
        Objects.requireNonNull(tracks, "tracks must not be null");
        Run run = fencedCurrent();
        boolean loading = false;
        int silent = 0;
        for (Track track : tracks) {
            for (AudioClip clip : track.getClips()) {
                ClipAudioState state = run == null ? ClipAudioState.unmanaged(clip) : stateOf(clip);
                if (state == ClipAudioState.LOADING) {
                    loading = true;
                } else if (state == ClipAudioState.OFFLINE) {
                    silent++;
                }
            }
        }
        if (run == null) {
            return new Readiness(false, 0, 0, 0, silent);
        }
        int total = run.session == null ? 0 : run.session.sourceCount();
        int pending = run.session == null ? 0 : run.session.pendingCount();
        return new Readiness(loading, total - pending, total, run.report.assetCount(), silent);
    }

    /**
     * Registers {@code listener} to run, on the FX thread, after anything a
     * menu's enablement may depend on changed: a run begun or retired, the
     * live report changed, a run finished. Returns the removal.
     *
     * @param listener the listener
     * @return removes the listener; idempotent
     */
    Runnable addChangeListener(Runnable listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        changeListeners.add(listener);
        return () -> changeListeners.remove(listener);
    }

    // ── seams ────────────────────────────────────────────────────────────────

    /**
     * Replaces the rehydrator later runs start (tests hold its decode threads).
     *
     * @param rehydrator the rehydrator
     */
    void setRehydrator(ClipRehydrator rehydrator) {
        this.rehydrator = Objects.requireNonNull(rehydrator, "rehydrator must not be null");
    }

    /**
     * Replaces how the drain applies one outcome (tests make it fail for one
     * source).
     *
     * @param applier the applier
     */
    void setOutcomeApplier(OutcomeApplier applier) {
        this.outcomeApplier = Objects.requireNonNull(applier, "applier must not be null");
    }

    /**
     * Replaces the report presenter (tests count presentations).
     *
     * @param presenter the presenter
     */
    void setPresenter(ReportPresenter presenter) {
        this.presenter = Objects.requireNonNull(presenter, "presenter must not be null");
    }

    // ── internals ────────────────────────────────────────────────────────────

    private Run fencedCurrent() {
        Run run = current;
        return run != null && liveProject.get() == run.generation.project() ? run : null;
    }

    private Runnable reviewAction(Generation generation) {
        return new ReviewAction(this, generation.id());
    }

    /**
     * What a {@code Review…} of the notification history does: presents the
     * report of the open numbered {@code generation} while it is the
     * current one, and nothing once another open replaced it.
     */
    private void review(long generation) {
        Run run = fencedCurrent();
        if (run != null && run.generation.id() == generation) {
            presentReport(run.generation);
        }
    }

    /**
     * The one drain of {@code run} (decision D12), on the FX thread: every
     * queued outcome applied in order, then the end, then one repaint.
     */
    private void drain(Run run) {
        if (current != run) {
            run.completions.clear();
            return;
        }
        if (liveProject.get() != run.generation.project()) {
            // A door replaced the project without retiring the run: nothing
            // of it may reach the new project.
            LOG.warning(() -> "The audio loading of " + run.generation.project().getName()
                    + " outlived its project; it is retired");
            retire();
            return;
        }
        try {
            drainCurrent(run);
        } catch (RuntimeException failure) {
            // Contained: a failure here must not starve the pulse's other keyed work.
            LOG.log(Level.WARNING, "Applying loaded clip audio failed", failure);
        }
    }

    /**
     * Applies every queued completion (decisions D33, D39). A failure
     * applying one completion is contained to it — the rest still land, and
     * its source stays pending. Once the run's end was taken, every source
     * the session still counts as pending (an outcome lost on the way, or
     * one whose apply failed) is settled as a row over its clips still
     * without audio, so nothing stays loading. The progress, the end and
     * the one repaint run even when adding the rows to the report failed; a
     * drain that only settled repaints too (decision D40), so a settled
     * clip stops showing {@code Loading…}.
     */
    private void drainCurrent(Run run) {
        int applied = 0;
        boolean ended = false;
        List<MissingAsset> settled = List.of();
        List<MissingAsset> rows = new ArrayList<>();
        try {
            Completion completion;
            while ((completion = run.completions.poll()) != null) {
                switch (completion) {
                    case Completion.Arrived arrived -> {
                        applied++;
                        rows.addAll(applyContained(run, arrived.outcome()));
                    }
                    case Completion.Ended _ -> ended = true;
                }
            }
            if (ended) {
                settled = run.session.settlePending();
                rows.addAll(settled);
            }
            addRows(run, rows);
        } finally {
            if (applied > 0) {
                updateProgress(run);
            }
            if (ended) {
                finish(run);
            }
            if (applied > 0 || !settled.isEmpty()) {
                repaint.run();
            }
        }
    }

    /**
     * Applies one outcome through the {@link OutcomeApplier}; a failure is
     * logged and applies nothing more of it. The session still owes the
     * source, so the run's end settles it as a row.
     */
    private List<MissingAsset> applyContained(Run run, RehydrationOutcome outcome) {
        try {
            return outcomeApplier.apply(run.session, outcome);
        } catch (RuntimeException failure) {
            LOG.log(Level.WARNING, "Applying the loaded audio of " + outcome.source() + " failed", failure);
            return List.of();
        }
    }

    /** Adds the rows a drain produced to the live report, noting those that came after the announcement. */
    private void addRows(Run run, List<MissingAsset> rows) {
        if (rows.isEmpty()) {
            return;
        }
        run.report = run.report.withAll(rows);
        if (run.announced != null) {
            for (MissingAsset row : rows) {
                run.addedAfterAnnouncement.add(row.reference());
            }
        }
        reportChanged(run);
    }

    private void updateProgress(Run run) {
        if (run.operation == null) {
            return;
        }
        int total = run.session.sourceCount();
        ProjectOperationProgress.NamedOperation next = loadingOperation(total - run.session.pendingCount(), total);
        progress.replaceOperation(run.operation, next);
        run.operation = next;
    }

    /**
     * The run's end: the strip operation goes, and — when the run added
     * rows after the announcement that are still unresolved — exactly one
     * follow-up WARNING (decisions D14, D24), its clauses those of the rows
     * it adds: the missing assets, then the shorter ones.
     */
    private void finish(Run run) {
        if (run.finished) {
            return;
        }
        run.finished = true;
        if (run.operation != null) {
            progress.removeOperation(run.operation);
            run.operation = null;
        }
        if (run.announced != null) {
            OpenAnnouncement.followUp(run.report.restrictedTo(run.addedAfterAnnouncement))
                    .ifPresent(message -> notificationBar.show(NotificationLevel.WARNING, message,
                            ProjectAudioText.text("projectOpen.review"), reviewAction(run.generation)));
        }
        fireChange();
    }

    private void reportChanged(Run run) {
        try {
            if (run.view != null) {
                run.view.update(run.report);
            }
        } finally {
            fireChange();
        }
    }

    private void fireChange() {
        for (Runnable listener : List.copyOf(changeListeners)) {
            try {
                listener.run();
            } catch (RuntimeException failure) {
                LOG.log(Level.WARNING, "A project-audio change listener failed", failure);
            }
        }
    }

    private static Set<AssetReference> assetsOf(MissingAssetsReport report) {
        Set<AssetReference> assets = new HashSet<>();
        for (MissingAsset row : report.assets()) {
            assets.add(row.reference());
        }
        return assets;
    }

    private static ProjectOperationProgress.NamedOperation loadingOperation(int loaded, int total) {
        return new ProjectOperationProgress.NamedOperation(
                ProjectAudioText.text("projectOpen.loadingAudio", Integer.toString(loaded), Integer.toString(total)),
                total == 0 ? 1.0 : (double) loaded / total);
    }

    /**
     * A {@code Review…} action as the notification history keeps it — up to
     * a hundred entries, for the life of the window (r1 M2). It holds the
     * owner and the open's number, never the open's {@link Generation}:
     * through that, the replaced project — every track, clip and decoded
     * array — would stay reachable from the history after another open
     * replaced it. Numbers are never reused, so a click once the open was
     * replaced finds no current run with its number and does nothing.
     *
     * @param owner      the owner of the open project's audio
     * @param generation the number of the open whose report it presents
     */
    record ReviewAction(OpenProjectAudio owner, long generation) implements Runnable {

        ReviewAction {
            Objects.requireNonNull(owner, "owner must not be null");
        }

        @Override
        public void run() {
            owner.review(generation);
        }
    }

    /** What a decode thread queues for the drain. */
    private sealed interface Completion permits Completion.Arrived, Completion.Ended {

        /** One source's outcome. */
        record Arrived(RehydrationOutcome outcome) implements Completion {
            public Arrived {
                Objects.requireNonNull(outcome, "outcome must not be null");
            }
        }

        /** The run's end; always the last completion. */
        record Ended(RehydrationListener.Summary summary) implements Completion {
            public Ended {
                Objects.requireNonNull(summary, "summary must not be null");
            }
        }
    }

    /** The key of one run's drain: one key per fact, "this run has completions". */
    private record DrainKey(long generation) {
    }

    /** One open's audio. FX thread, except {@link #completions} and the listener. */
    private final class Run {

        final Generation generation;
        final Path projectDirectory;
        final DrainKey drainKey;
        /** Written by decode threads, emptied by the drain. */
        final Queue<Completion> completions = new ConcurrentLinkedQueue<>();
        final Runnable drainTask;
        RehydrationSession session;
        MissingAssetsReport report;
        ProjectOperationProgress.NamedOperation operation;
        ReportView view;
        /** The assets the announcement counted; {@code null} until it is made. */
        Set<AssetReference> announced;
        final Set<AssetReference> addedAfterAnnouncement = new LinkedHashSet<>();
        boolean finished;

        Run(Generation generation, Path projectDirectory, MissingAssetsReport report) {
            this.generation = generation;
            this.projectDirectory = projectDirectory;
            this.drainKey = new DrainKey(generation.id());
            this.report = report;
            this.drainTask = () -> drain(this);
        }

        /** The listener the core session reports to, on its decode threads: queue, then post. */
        RehydrationListener listener() {
            return new RehydrationListener() {
                @Override
                public void onOutcome(RehydrationOutcome outcome) {
                    completions.add(new Completion.Arrived(outcome));
                    dispatcher.onFx(drainKey, drainTask);
                }

                @Override
                public void onFinished(Summary summary) {
                    completions.add(new Completion.Ended(summary));
                    dispatcher.onFx(drainKey, drainTask);
                }
            };
        }
    }
}
