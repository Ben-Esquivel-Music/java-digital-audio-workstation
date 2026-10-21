package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.persistence.AssetReference;
import com.benesquivelmusic.daw.core.persistence.AssetRelinker;
import com.benesquivelmusic.daw.core.persistence.MissingAsset;
import com.benesquivelmusic.daw.core.persistence.MissingAssetReason;
import com.benesquivelmusic.daw.core.persistence.MissingAssetSearch;
import com.benesquivelmusic.daw.core.persistence.RelinkPreparation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Relink in the open session (story 329; Persistence Integrity book §4.3;
 * decisions D16, D17): what the missing-assets report's {@code Locate file…},
 * {@code Skip} and {@code Locate folder…} do. Confined to the FX thread,
 * except the worker bodies it starts.
 *
 * <p><strong>Locate file.</strong> The user picks a file (the chooser opens
 * in the project folder, for a take row in the take's own folder — any one
 * file of the take will do). The request is snapshotted on the FX thread
 * ({@link AssetRelinker#request}); a virtual-thread worker decodes and
 * checks the pick
 * ({@link AssetRelinker#prepare}) and posts the result back through the
 * {@link FxDispatcher} under a key of its own, carrying the open's
 * generation. On the FX thread, if the open is still the current one and the
 * row still waits for that result: a refusal is shown on the row; a file the
 * user must agree to — a take found outside its own folder, an ADM import's
 * file without ADM metadata, a file shorter than its clips — is applied
 * only after one confirmation that names it (decisions D25, D27, D30);
 * otherwise it is applied ({@link AssetRelinker#apply}, which re-points every
 * clip holding the asset, attaches the decoded audio, invalidates each clip's
 * sample-rate conversions through the engine's cache, and marks the project
 * dirty), the arrangement is repainted once and the row leaves the report.
 * Relink is not an undo-stack entry.</p>
 *
 * <p><strong>Locate folder.</strong> One walk of the chosen folder looks for
 * every row at once ({@link MissingAssetSearch}), then each resolved asset is
 * prepared as Locate file would; a row with several equally good candidates
 * stays, labelled {@code K candidates — use Locate file…}. A search that
 * relinked something says how many assets, from which folder, and logs each
 * mapping (decision D35). The search can be cancelled, and is cancelled when
 * the open is retired.</p>
 *
 * <p><strong>Skip</strong> hides every row of a row's asset
 * ({@link com.benesquivelmusic.daw.core.persistence.MissingAssetsReport#without})
 * for this session: the asset's clips keep their references and stay as
 * they are — a clip without audio stays silent, a clip that loaded (of a
 * "Shorter than the clip" row, say) keeps playing as loaded — and the
 * project is not dirtied.</p>
 *
 * <p>A row's buttons are disabled while its work is in flight (its status is
 * {@link RowStatus.Working}). Every effect is fenced: a result of an open
 * that is no longer the current one — another project was opened, a new one
 * made, the window closed — does nothing.</p>
 */
final class AssetRelinkFlow {

    private static final Logger LOG = Logger.getLogger(AssetRelinkFlow.class.getName());

    /** Asks the user for the file of one row; tests answer without a chooser. */
    @FunctionalInterface
    interface FileLocator {

        /**
         * Asks for the file of {@code row}.
         *
         * @param row              the row the user wants to relink
         * @param initialDirectory where the chooser opens
         *                         ({@link AssetRelinker#locateFileDirectory}):
         *                         the project folder, or — for a take whose
         *                         folder lies inside the project folder —
         *                         that folder when it exists, else its
         *                         nearest existing ancestor inside the
         *                         project folder
         * @return the picked file, or empty when the user cancelled
         */
        Optional<Path> locate(MissingAsset row, Optional<Path> initialDirectory);
    }

    /** Asks the user for a folder to search; tests answer without a chooser. */
    @FunctionalInterface
    interface FolderLocator {

        /**
         * Asks for the folder to search.
         *
         * @param initialDirectory where the chooser opens: the project folder
         * @return the picked folder, or empty when the user cancelled
         */
        Optional<Path> locate(Optional<Path> initialDirectory);
    }

    /** Asks the user a yes/no question; tests answer without a dialog. */
    @FunctionalInterface
    interface Confirmation {

        /**
         * Asks {@code message}.
         *
         * @param message the question
         * @return {@code true} if the user agreed
         */
        boolean confirm(String message);
    }

    /** Where a row's relink stands; {@link #IDLE} when nothing happened to it. */
    sealed interface RowStatus permits RowStatus.Idle, RowStatus.Working, RowStatus.Refused,
            RowStatus.Ambiguous {

        /** Nothing in flight, nothing to say. */
        RowStatus IDLE = new Idle();

        /** Nothing in flight, nothing to say. */
        record Idle() implements RowStatus {
        }

        /**
         * A located file is being checked, or a folder searched: the row's
         * buttons are disabled.
         *
         * @param job the work the row waits for
         */
        record Working(long job) implements RowStatus {
        }

        /**
         * The located file cannot be used; nothing was changed.
         *
         * @param reason why, as a report row would say it
         * @param detail the detail, naming the located file
         */
        record Refused(MissingAssetReason reason, String detail) implements RowStatus {
            public Refused {
                Objects.requireNonNull(reason, "reason must not be null");
                Objects.requireNonNull(detail, "detail must not be null");
            }
        }

        /**
         * A folder search found several equally good candidates; nothing
         * was guessed.
         *
         * @param candidates how many tied, at least two
         */
        record Ambiguous(int candidates) implements RowStatus {
        }
    }

    private final OpenProjectAudio audio;
    private final FxDispatcher dispatcher;
    private final NotificationBar notificationBar;
    private final Runnable repaint;
    private FileLocator fileLocator;
    private FolderLocator folderLocator;
    private Confirmation confirmation;
    private ThreadFactory workers = Thread.ofVirtual().name("daw-relink-", 0).factory();
    private final AssetRelinker relinker = new AssetRelinker();
    private Consumer<String> invalidator = _ -> { };
    private Session session;
    private long lastJob;

    /**
     * Creates the flow over {@code audio}'s report; it follows the open's
     * runs, ending its work when a run is retired.
     *
     * @param audio           the owner of the open project's audio
     * @param dispatcher      the FX marshalling seam; results run as its keyed work
     * @param notificationBar where a folder search's outcome goes
     * @param repaint         repaints the arrangement after a relink
     * @param fileLocator     asks for a row's file
     * @param folderLocator   asks for a folder to search
     * @param confirmation    asks whether to use a located file that needs
     *                        the user's consent ({@link #confirmationText})
     */
    AssetRelinkFlow(OpenProjectAudio audio, FxDispatcher dispatcher, NotificationBar notificationBar,
                    Runnable repaint, FileLocator fileLocator, FolderLocator folderLocator,
                    Confirmation confirmation) {
        this.audio = Objects.requireNonNull(audio, "audio must not be null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        this.notificationBar = Objects.requireNonNull(notificationBar, "notificationBar must not be null");
        this.repaint = Objects.requireNonNull(repaint, "repaint must not be null");
        this.fileLocator = Objects.requireNonNull(fileLocator, "fileLocator must not be null");
        this.folderLocator = Objects.requireNonNull(folderLocator, "folderLocator must not be null");
        this.confirmation = Objects.requireNonNull(confirmation, "confirmation must not be null");
        // The flow lives as long as the owner: the listener is never removed.
        audio.addChangeListener(this::followTheOpen);
    }

    // ── actions ──────────────────────────────────────────────────────────────

    /**
     * Locate file: asks for the file of {@code row} and relinks the row's
     * asset to it once a worker has decoded and checked it. Does nothing for
     * an open that is not current, a row that left the report, or a row
     * whose work is in flight.
     *
     * @param generation the open the row belongs to
     * @param row        the row
     */
    void locateFile(OpenProjectAudio.Generation generation, MissingAsset row) {
        Objects.requireNonNull(row, "row must not be null");
        Session owner = sessionFor(generation);
        AssetReference asset = row.reference();
        if (owner == null || !actionable(owner, asset)) {
            return;
        }
        Optional<Path> picked = fileLocator.locate(row,
                AssetRelinker.locateFileDirectory(asset, audio.projectDirectory().orElse(null)));
        // The chooser ran a nested event loop: the open, or the row, may have changed meanwhile.
        if (picked.isEmpty() || session != owner || !audio.isCurrent(generation) || !actionable(owner, asset)) {
            return;
        }
        AssetRelinker.Request request = AssetRelinker.request(generation.project(), asset);
        long job = ++lastJob;
        owner.statuses.put(asset, new RowStatus.Working(job));
        refresh(owner);
        Path file = picked.get();
        start(owner, job, () -> {
            RelinkPreparation prepared = prepare(asset, file.toString(), () -> relinker.prepare(request, file));
            if (prepared != null) {
                post(owner, job, () -> located(owner, job, prepared));
            }
        });
    }

    /**
     * Skip: hides every row of {@code row}'s asset
     * ({@link com.benesquivelmusic.daw.core.persistence.MissingAssetsReport#without})
     * for this session (decision D16). The asset's clips keep their
     * references and stay as they are: a clip without audio stays silent, a
     * clip that loaded keeps playing as loaded. The project is not dirtied.
     *
     * @param generation the open the row belongs to
     * @param row        the row
     */
    void skip(OpenProjectAudio.Generation generation, MissingAsset row) {
        Objects.requireNonNull(row, "row must not be null");
        Session owner = sessionFor(generation);
        AssetReference asset = row.reference();
        if (owner == null || isWorking(owner, asset)) {
            return;
        }
        owner.statuses.remove(asset);
        audio.editReport(generation, report -> report.without(asset));
    }

    /**
     * Locate folder: asks for a folder, then searches it once for every row
     * whose work is not in flight and relinks every asset found exactly
     * once. Does nothing for an open that is not current or while a search
     * runs.
     *
     * @param generation the open the report belongs to
     */
    void locateFolder(OpenProjectAudio.Generation generation) {
        Session owner = sessionFor(generation);
        if (owner == null || owner.search != null) {
            return;
        }
        Optional<Path> picked = folderLocator.locate(audio.projectDirectory());
        if (picked.isEmpty() || session != owner || !audio.isCurrent(generation) || owner.search != null) {
            return;
        }
        Map<AssetReference, AssetRelinker.Request> requests = new LinkedHashMap<>();
        for (MissingAsset row : audio.report().assets()) {
            AssetReference asset = row.reference();
            if (!requests.containsKey(asset) && !isWorking(owner, asset)) {
                requests.put(asset, AssetRelinker.request(generation.project(), asset));
            }
        }
        if (requests.isEmpty()) {
            return;
        }
        long job = ++lastJob;
        Path folder = picked.get();
        AtomicBoolean cancelled = new AtomicBoolean();
        for (AssetReference asset : requests.keySet()) {
            owner.statuses.put(asset, new RowStatus.Working(job));
        }
        owner.search = new FolderSearch(job, folder, cancelled);
        refresh(owner);
        start(owner, job, () -> {
            FolderOutcome outcome = search(folder, requests, cancelled, relinker);
            if (outcome != null) {
                post(owner, job, () -> searched(owner, job, outcome));
            }
        });
    }

    /**
     * Cancels the running folder search of {@code generation}: its rows are
     * idle again at once, and its result, if it still comes, is ignored.
     *
     * @param generation the open the search belongs to
     */
    void cancelFolderSearch(OpenProjectAudio.Generation generation) {
        Session owner = session;
        if (owner == null || !owner.generation.equals(generation) || owner.search == null) {
            return;
        }
        FolderSearch search = owner.search;
        owner.search = null;
        search.cancelled().set(true);
        stop(owner, search.job());
        owner.statuses.values().removeIf(status -> status instanceof RowStatus.Working working
                && working.job() == search.job());
        refresh(owner);
    }

    // ── queries ──────────────────────────────────────────────────────────────

    /**
     * Returns where the relink of {@code asset} stands in {@code generation}.
     *
     * @param generation the open
     * @param asset      the row's asset
     * @return the row's status; {@link RowStatus#IDLE} when nothing happened to it
     */
    RowStatus statusOf(OpenProjectAudio.Generation generation, AssetReference asset) {
        Session owner = session;
        if (owner == null || !owner.generation.equals(generation)) {
            return RowStatus.IDLE;
        }
        return owner.statuses.getOrDefault(asset, RowStatus.IDLE);
    }

    /**
     * Returns the folder {@code generation}'s running search walks.
     *
     * @param generation the open
     * @return the folder, or empty when no search runs
     */
    Optional<Path> searchedFolder(OpenProjectAudio.Generation generation) {
        Session owner = session;
        if (owner == null || !owner.generation.equals(generation) || owner.search == null) {
            return Optional.empty();
        }
        return Optional.of(owner.search.folder());
    }

    /**
     * Returns the report's actions for {@code generation}: the relink
     * actions and statuses of this flow, and {@code archiveProject}.
     *
     * @param generation     the open the report belongs to
     * @param archiveProject runs the archive consolidation flow
     * @return the actions
     */
    MissingAssetsDialog.Actions actionsFor(OpenProjectAudio.Generation generation, Runnable archiveProject) {
        Objects.requireNonNull(generation, "generation must not be null");
        Objects.requireNonNull(archiveProject, "archiveProject must not be null");
        return new MissingAssetsDialog.Actions() {
            @Override
            public void archiveProject() {
                archiveProject.run();
            }

            @Override
            public void locateFile(MissingAsset row) {
                AssetRelinkFlow.this.locateFile(generation, row);
            }

            @Override
            public void skip(MissingAsset row) {
                AssetRelinkFlow.this.skip(generation, row);
            }

            @Override
            public void locateFolder() {
                AssetRelinkFlow.this.locateFolder(generation);
            }

            @Override
            public void cancelFolderSearch() {
                AssetRelinkFlow.this.cancelFolderSearch(generation);
            }

            @Override
            public RowStatus statusOf(AssetReference asset) {
                return AssetRelinkFlow.this.statusOf(generation, asset);
            }

            @Override
            public Optional<Path> searchedFolder() {
                return AssetRelinkFlow.this.searchedFolder(generation);
            }
        };
    }

    // ── seams ────────────────────────────────────────────────────────────────

    /**
     * Replaces the file chooser (tests answer without one).
     *
     * @param fileLocator the file locator
     */
    void setFileLocator(FileLocator fileLocator) {
        this.fileLocator = Objects.requireNonNull(fileLocator, "fileLocator must not be null");
    }

    /**
     * Replaces the folder chooser (tests answer without one).
     *
     * @param folderLocator the folder locator
     */
    void setFolderLocator(FolderLocator folderLocator) {
        this.folderLocator = Objects.requireNonNull(folderLocator, "folderLocator must not be null");
    }

    /**
     * Replaces the confirmation a located file that needs the user's consent
     * asks (tests answer without a dialog).
     *
     * @param confirmation the confirmation
     */
    void setConfirmation(Confirmation confirmation) {
        this.confirmation = Objects.requireNonNull(confirmation, "confirmation must not be null");
    }

    /**
     * Replaces the threads later checks and searches run on (tests hold them).
     *
     * @param workers one new thread per job
     */
    void setWorkers(ThreadFactory workers) {
        this.workers = Objects.requireNonNull(workers, "workers must not be null");
    }

    /**
     * Sets what drops a re-pointed clip's cached sample-rate conversions:
     * the engine's {@code SampleRateConversionCache::invalidateForClip}.
     *
     * @param invalidator called with the id of each re-pointed clip
     */
    void setInvalidator(Consumer<String> invalidator) {
        this.invalidator = Objects.requireNonNull(invalidator, "invalidator must not be null");
    }

    // ── the FX side of a job ─────────────────────────────────────────────────

    /** A Locate file check ended. */
    private void located(Session owner, long job, RelinkPreparation prepared) {
        if (!waitsFor(owner, prepared.asset(), job)) {
            return;
        }
        switch (prepared) {
            case RelinkPreparation.Refusal refusal -> {
                owner.statuses.put(refusal.asset(), new RowStatus.Refused(refusal.reason(), refusal.detail()));
                refresh(owner);
            }
            case RelinkPreparation.Candidate candidate -> {
                if (candidate.needsConfirmation()) {
                    confirmThenApply(owner, job, candidate);
                } else {
                    apply(owner, candidate);
                    repaint.run();
                }
            }
        }
    }

    /** A folder search ended. */
    private void searched(Session owner, long job, FolderOutcome outcome) {
        if (owner.search == null || owner.search.job() != job) {
            return;
        }
        Path folder = owner.search.folder();
        owner.search = null;
        switch (outcome) {
            case FolderOutcome.Failed failed -> {
                owner.statuses.values().removeIf(status -> status instanceof RowStatus.Working working
                        && working.job() == job);
                refresh(owner);
                notificationBar.show(NotificationLevel.WARNING,
                        ProjectAudioText.text("missingAssets.folder.failed", folder, failed.detail()));
            }
            case FolderOutcome.Searched found -> searched(owner, job, folder, found);
        }
    }

    private void searched(Session owner, long job, Path folder, FolderOutcome.Searched found) {
        int applied = 0;
        boolean reported = false;
        List<RelinkPreparation.Candidate> toConfirm = new ArrayList<>();
        for (RelinkPreparation prepared : found.preparations()) {
            if (!waitsFor(owner, prepared.asset(), job)) {
                continue;
            }
            switch (prepared) {
                case RelinkPreparation.Refusal refusal -> {
                    owner.statuses.put(refusal.asset(),
                            new RowStatus.Refused(refusal.reason(), refusal.detail()));
                    reported = true;
                }
                case RelinkPreparation.Candidate candidate -> {
                    if (candidate.needsConfirmation()) {
                        toConfirm.add(candidate);
                    } else {
                        apply(owner, candidate);
                        applied++;
                    }
                }
            }
        }
        for (Map.Entry<AssetReference, Integer> tie : found.ambiguous().entrySet()) {
            if (waitsFor(owner, tie.getKey(), job)) {
                owner.statuses.put(tie.getKey(), new RowStatus.Ambiguous(tie.getValue()));
                reported = true;
            }
        }
        for (AssetReference asset : found.notFound()) {
            if (waitsFor(owner, asset, job)) {
                owner.statuses.remove(asset);
            }
        }
        refresh(owner);
        if (applied > 0) {
            repaint.run();
            notificationBar.show(NotificationLevel.INFO,
                    ProjectAudioText.count("missingAssets.folder.relinked", applied, folder.toString()));
        }
        if (applied == 0 && !reported && toConfirm.isEmpty()) {
            notificationBar.show(NotificationLevel.INFO,
                    ProjectAudioText.text("missingAssets.folder.nothingFound", folder));
        }
        for (RelinkPreparation.Candidate candidate : toConfirm) {
            confirmThenApply(owner, job, candidate);
        }
    }

    /**
     * Asks before applying a located file the user must agree to — the one
     * relink confirmation ({@link #confirmationText}). The question is
     * modal, and the result handlers run as the dispatcher's per-frame work,
     * during which JavaFX forbids a nested event loop: the question is asked
     * on a later turn of the FX event loop, and the open and the row are
     * checked again on each side of it.
     */
    private void confirmThenApply(Session owner, long job, RelinkPreparation.Candidate candidate) {
        AssetReference asset = candidate.asset();
        dispatcher.onFx(() -> {
            if (!waitsFor(owner, asset, job)) {
                return;
            }
            boolean useIt = confirmation.confirm(confirmationText(candidate));
            if (!waitsFor(owner, asset, job)) {
                return;
            }
            if (useIt) {
                apply(owner, candidate);
                repaint.run();
            } else {
                owner.statuses.remove(asset);
                refresh(owner);
            }
        });
    }

    /**
     * The question of the one relink confirmation (decision D27): the
     * sentences that apply to {@code candidate}, in this order — the take
     * was found outside its own folder (D25), the file has no ADM metadata
     * its clips need (D30, D43), the file is shorter than its clips — each
     * naming the located file (for a take, the take and track folders its
     * segments were found in, {@code <stamp>_take-NNNN/<trackId>}: D38),
     * then {@code Use it anyway?}.
     *
     * @param candidate a candidate that {@linkplain RelinkPreparation.Candidate#needsConfirmation() needs confirmation}
     * @return the question
     */
    static String confirmationText(RelinkPreparation.Candidate candidate) {
        List<String> sentences = new ArrayList<>(3);
        candidate.takeFolderMismatch().ifPresent(mismatch -> sentences.add(ProjectAudioText.text(
                "missingAssets.confirm.takeFolder", mismatch.pickedFolder(), mismatch.takeFolder())));
        candidate.missingAdmMetadata().ifPresent(_ -> sentences.add(ProjectAudioText.text(
                "missingAssets.confirm.noAdmMetadata", candidate.locatedName())));
        if (candidate.shorterThanUsed()) {
            sentences.add(ProjectAudioText.text("missingAssets.confirm.shorter", candidate.locatedName(),
                    ProjectAudioText.seconds(candidate.heldSeconds()),
                    ProjectAudioText.seconds(candidate.usedSeconds())));
        }
        return ProjectAudioText.text("missingAssets.confirm", String.join(" ", sentences));
    }

    /** Applies a candidate: every holder re-pointed, the audio attached, the row gone. */
    private void apply(Session owner, RelinkPreparation.Candidate candidate) {
        AssetRelinker.Applied applied = AssetRelinker.apply(candidate, invalidator);
        owner.statuses.remove(candidate.asset());
        audio.editReport(owner.generation,
                report -> report.without(candidate.asset()).withAll(applied.rows()));
        LOG.info(() -> "Relinked " + candidate.asset().references() + " to "
                + candidate.replacement().references() + " (" + applied.relinked().size() + " clip(s))");
    }

    // ── the worker side of a job ─────────────────────────────────────────────

    /** A check the worker runs. */
    @FunctionalInterface
    private interface Check {
        RelinkPreparation run() throws InterruptedException;
    }

    /**
     * Runs one check on the worker: a refusal for anything it throws, nothing
     * when the job was interrupted (its open was retired, or its search cancelled).
     */
    private static RelinkPreparation prepare(AssetReference asset, String located, Check check) {
        try {
            return check.run();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException failure) {
            LOG.log(Level.WARNING, "Checking " + located + " for a relink failed", failure);
            return new RelinkPreparation.Refusal(asset, MissingAssetReason.UNREADABLE,
                    located + ": " + failure.getMessage());
        }
    }

    /** One folder search and the checks of what it found; on the worker. */
    private static FolderOutcome search(Path folder, Map<AssetReference, AssetRelinker.Request> requests,
                                        AtomicBoolean cancelled, AssetRelinker checks) {
        Map<AssetReference, MissingAssetSearch.Match> matches;
        try {
            matches = MissingAssetSearch.search(folder, List.copyOf(requests.keySet()), cancelled::get);
        } catch (CancellationException stopped) {
            return null;
        } catch (IOException | RuntimeException failure) {
            LOG.log(Level.WARNING, "Searching " + folder + " for missing assets failed", failure);
            return new FolderOutcome.Failed(String.valueOf(failure.getMessage()));
        }
        List<RelinkPreparation> preparations = new ArrayList<>();
        Map<AssetReference, Integer> ambiguous = new LinkedHashMap<>();
        List<AssetReference> notFound = new ArrayList<>();
        for (Map.Entry<AssetReference, MissingAssetSearch.Match> match : matches.entrySet()) {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                return null;
            }
            AssetReference asset = match.getKey();
            switch (match.getValue()) {
                case MissingAssetSearch.Resolved resolved -> {
                    AssetRelinker.Request request = requests.get(asset);
                    RelinkPreparation prepared = prepare(asset,
                            String.join(", ", resolved.replacement().references()),
                            () -> checks.prepare(request, resolved.replacement()));
                    if (prepared == null) {
                        return null;
                    }
                    preparations.add(prepared);
                }
                case MissingAssetSearch.Ambiguous tie -> ambiguous.put(asset, tie.candidateCount());
                case MissingAssetSearch.NotFound _ -> notFound.add(asset);
            }
        }
        return new FolderOutcome.Searched(preparations, ambiguous, notFound);
    }

    /** What a folder search's worker posts back. */
    private sealed interface FolderOutcome permits FolderOutcome.Searched, FolderOutcome.Failed {

        /** The walk ran; each asset was prepared, tied or not found. */
        record Searched(List<RelinkPreparation> preparations, Map<AssetReference, Integer> ambiguous,
                        List<AssetReference> notFound) implements FolderOutcome {
            public Searched {
                preparations = List.copyOf(preparations);
                ambiguous = Map.copyOf(ambiguous);
                notFound = List.copyOf(notFound);
            }
        }

        /** The folder could not be walked. */
        record Failed(String detail) implements FolderOutcome {
            public Failed {
                Objects.requireNonNull(detail, "detail must not be null");
            }
        }
    }

    // ── jobs and sessions ────────────────────────────────────────────────────

    /** Starts {@code body} on a worker of its own, remembered so a retire can interrupt it. */
    private void start(Session owner, long job, Runnable body) {
        Thread worker = workers.newThread(() -> {
            try {
                body.run();
            } catch (RuntimeException | Error failure) {
                // Contained (design book §5.2): the job's rows are released, idle again.
                LOG.log(Level.WARNING, "A relink job failed", failure);
                post(owner, job, () -> released(owner, job));
            }
        });
        owner.jobs.put(job, worker);
        worker.start();
    }

    /** A job ended without a result: its rows are idle again. */
    private void released(Session owner, long job) {
        if (owner.search != null && owner.search.job() == job) {
            owner.search = null;
        }
        owner.statuses.values().removeIf(status -> status instanceof RowStatus.Working working
                && working.job() == job);
        refresh(owner);
    }

    /**
     * Posts a job's result to the FX thread under a key of its own (one key
     * per fact, never coalesced away), carrying the open's generation; it
     * runs only if its session is still the flow's and its open the current one.
     */
    private void post(Session owner, long job, Runnable result) {
        dispatcher.onFx(new ResultKey(owner.generation.id(), job), () -> {
            owner.jobs.remove(job);
            if (session == owner && audio.isCurrent(owner.generation)) {
                result.run();
            }
        });
    }

    /** Stops one job: its pending result is dropped and its thread interrupted. */
    private void stop(Session owner, long job) {
        dispatcher.cancelKey(new ResultKey(owner.generation.id(), job));
        Thread worker = owner.jobs.remove(job);
        if (worker != null) {
            worker.interrupt();
        }
    }

    /** The open's runs changed: a session whose open is no longer current ends. */
    private void followTheOpen() {
        Session owner = session;
        if (owner != null && !audio.isCurrent(owner.generation)) {
            end(owner);
        }
    }

    /** Ends a session: its search is cancelled, its jobs are stopped, nothing of it has an effect. */
    private void end(Session owner) {
        if (session == owner) {
            session = null;
        }
        if (owner.search != null) {
            owner.search.cancelled().set(true);
            owner.search = null;
        }
        for (long job : List.copyOf(owner.jobs.keySet())) {
            stop(owner, job);
        }
        owner.statuses.clear();
    }

    /** The session of {@code generation}, begun on its first action; {@code null} if it is not current. */
    private Session sessionFor(OpenProjectAudio.Generation generation) {
        Objects.requireNonNull(generation, "generation must not be null");
        if (!audio.isCurrent(generation)) {
            return null;
        }
        if (session == null || !session.generation.equals(generation)) {
            if (session != null) {
                end(session);
            }
            session = new Session(generation);
        }
        return session;
    }

    /** Whether {@code asset} is a row of the live report whose work is not in flight. */
    private boolean actionable(Session owner, AssetReference asset) {
        return audio.report().contains(asset) && !isWorking(owner, asset);
    }

    private static boolean isWorking(Session owner, AssetReference asset) {
        return owner.statuses.get(asset) instanceof RowStatus.Working;
    }

    /** Whether the session is current and {@code asset}'s row still waits for {@code job}. */
    private boolean waitsFor(Session owner, AssetReference asset, long job) {
        return session == owner && audio.isCurrent(owner.generation)
                && owner.statuses.get(asset) instanceof RowStatus.Working working && working.job() == job;
    }

    private void refresh(Session owner) {
        audio.refreshReport(owner.generation);
    }

    /** One open's relink state. FX thread. */
    private static final class Session {

        final OpenProjectAudio.Generation generation;
        final Map<AssetReference, RowStatus> statuses = new HashMap<>();
        /** The workers of the jobs whose results have not run yet. */
        final Map<Long, Thread> jobs = new HashMap<>();
        FolderSearch search;

        Session(OpenProjectAudio.Generation generation) {
            this.generation = generation;
        }
    }

    /** The running folder search: its job, its folder, its cancellation token. */
    private record FolderSearch(long job, Path folder, AtomicBoolean cancelled) {
        FolderSearch {
            Objects.requireNonNull(folder, "folder must not be null");
            Objects.requireNonNull(cancelled, "cancelled must not be null");
        }
    }

    /** The key one job's result is posted under. */
    private record ResultKey(long generation, long job) {
    }
}
