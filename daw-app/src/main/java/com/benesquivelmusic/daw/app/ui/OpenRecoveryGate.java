package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.dialogs.RecoveryChoice;
import com.benesquivelmusic.daw.core.persistence.LockStolenException;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.persistence.RecoveryTemps;
import com.benesquivelmusic.daw.core.persistence.UnreadProjectItem;
import com.benesquivelmusic.daw.core.persistence.journal.*;
import com.benesquivelmusic.daw.core.persistence.snapshot.SnapshotStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * One worker-only LOCK → RECOVER gate shared by every project-open door. Each gated, writable open
 * reads its recovery evidence once into a {@link RecoveryPlan}: the chooser is shown that plan's
 * summary, and the choice acts on that same plan, so what the dialog describes is what happens.
 * An interrupted save is never deleted without a copy: before anything is cleared, a choice that
 * does not promote it keeps it byte for byte as an "Interrupted save" snapshot, and a copy that
 * fails aborts the decision with nothing deleted.
 *
 * <p>The target lease is verified before the evidence is read; before clearing, when nothing is
 * owed; after the choice; after a journal replay, before its copies are made; by the commit,
 * immediately before it replaces the head (after its backup); after the commit, before the
 * clears; and, when recovery fails in a way the controller shows as the read-only fallback (a
 * {@link ReplayFailed}), before that failure is reported, since a takeover can be what made it
 * fail. Recovery I/O can be long, and a session that has lost the folder writes nothing more into
 * it: the new holder's own gate owns the evidence. So a failed check fails the open as itself
 * (Persistence Integrity book §5.5, "lock wins"), with the outgoing project kept and nothing
 * deleted, never as the read-only fallback, whose "evidence was preserved" this session could no
 * longer keep. A check is not atomic with the step after it: a takeover between the last check
 * and the head's rename is not seen before the replace, though the check after the commit still
 * fails the open before the clears; likewise, a takeover just after a failed recovery's last check
 * is not seen before the read-only fallback. Every save's ownership check leaves a gap of the same
 * kind, and a wider one (story 393).
 */
final class OpenRecoveryGate {
    private static final Logger LOG = Logger.getLogger(OpenRecoveryGate.class.getName());
    /** The snapshot name an interrupted save is kept under: SnapshotStore lists it as "interrupted save", and its file name contains "interrupted-save". */
    static final String INTERRUPTED_SAVE_SNAPSHOT = "Interrupted save";
    static final String NO_HEAD_FOR_CHECKPOINT_ONLY =
            "project.daw is missing, so Restore checkpoint only cannot open this project; the journal was kept";
    /** The open's lease check, which a {@link Commit} runs as its last step before it replaces the head. */
    @FunctionalInterface interface LeaseCheck { void verify() throws IOException; }
    /**
     * Replaces the head with {@code xml}: anything it does first (a backup), then
     * {@code beforeReplace}, then the replace. A failed check must propagate, with nothing replaced.
     */
    @FunctionalInterface interface Commit { void write(Path head, String xml, LeaseCheck beforeReplace) throws IOException; }
    /** Replays a journal plan; production uses {@link JournalReplayer#recover(RecoveryPlan)}. */
    @FunctionalInterface interface Replay { RecoveryResult recover(RecoveryPlan plan); }

    /**
     * What the gate did. {@code applied} is -1 when nothing was recovered, else the journal records
     * replay dispatched, rebuilt or not (0 for a promoted interrupted save); {@code notRebuilt} is
     * how many of them replay could not rebuild into the recovered project, read from the same
     * plan the dialog described (0 unless a journal was replayed); {@code promotedInterruptedSave}
     * tells a promoted interrupted save apart from a journal replay that applied nothing.
     */
    record Result(int applied, int notRebuilt, List<UnreadProjectItem> unread, boolean promotedInterruptedSave) {
        static final Result PLAIN = new Result(-1, 0, List.of(), false);
        static final Result INTERRUPTED_SAVE = new Result(0, 0, List.of(), true);

        Result {
            unread = List.copyOf(Objects.requireNonNull(unread, "unread must not be null"));
            if (notRebuilt < 0) throw new IllegalArgumentException("notRebuilt must be >= 0: " + notRebuilt);
        }

        /**
         * The door the open announces after this result: a promoted interrupted save as itself
         * (never as a journal replay of 0 changes), a journal replay with its applied count, how
         * many of those changes it could not rebuild and its own read's unread items, or
         * {@code selected} when nothing was recovered.
         */
        OpenDoor door(OpenDoor selected) {
            if (promotedInterruptedSave) return new OpenDoor.InterruptedSavePromotion();
            return applied >= 0 ? new OpenDoor.JournalReplay(applied, notRebuilt, unread) : selected;
        }
    }
    static final class Cancelled extends IOException {
        Cancelled() { super("Recovery cancelled"); }
    }
    static final class ReplayFailed extends IOException {
        private final boolean writing;
        ReplayFailed(Throwable failure, boolean writing) { super(failure.getMessage(), failure); this.writing = writing; }
        boolean writing() { return writing; }
    }
    private final ProjectManager manager;
    private final Supplier<Boolean> enabled;
    private final Function<RecoverySummary, RecoveryChoice> choose;
    private final Commit commit;
    private final Replay replay;

    OpenRecoveryGate(ProjectManager manager, Supplier<Boolean> enabled,
                     Function<RecoverySummary, RecoveryChoice> choose, Commit commit) {
        this(manager, enabled, choose, commit, plan -> new JournalReplayer().recover(plan));
    }

    /** As the production constructor, with the journal replay replaced: a test acts while it runs. */
    OpenRecoveryGate(ProjectManager manager, Supplier<Boolean> enabled,
                     Function<RecoverySummary, RecoveryChoice> choose, Commit commit, Replay replay) {
        this.manager = manager;
        this.enabled = enabled;
        this.choose = choose;
        this.commit = commit;
        this.replay = replay;
    }

    Result run(Path folder, boolean readOnly) throws IOException {
        return run(folder, readOnly, manager.getLockManager());
    }

    Result run(Path folder, boolean readOnly, ProjectLockManager lock) throws IOException {
        if (!Thread.currentThread().isVirtual()) throw new IllegalStateException("Recovery must run on a virtual thread");
        if (readOnly) return Result.PLAIN;
        lock.verifyHeld();
        ProjectContext context = ProjectContext.forProject(folder);
        RecoveryTemps temps = RecoveryTemps.inspect(folder);
        boolean journalEnabled = Boolean.TRUE.equals(enabled.get());
        boolean hasJournal = !Files.notExists(context.journalDirectory());
        if (!journalEnabled || !hasJournal) LOG.info("Recovery gate: degraded lock-heuristic mode for " + folder);
        Optional<RecoveryPlan> owed = new RecoveryScanner().plan(context, temps, journalEnabled);
        if (owed.isEmpty()) {
            lock.verifyHeld();
            if (journalEnabled) JournalState.clear(context);
            temps.clean();
            return Result.PLAIN;
        }
        RecoveryPlan plan = owed.get();
        RecoveryChoice decision = choose.apply(plan.summary());
        if (decision == RecoveryChoice.CANCEL || decision == RecoveryChoice.INSPECT_EVENTS) throw new Cancelled();
        lock.verifyHeld();
        Result result;
        try { result = decision == RecoveryChoice.REPLAY_ALL ? recoverEverything(plan, lock) : restoreCheckpointOnly(plan); }
        catch (ReplayFailed failed) {
            // Here, not in the controller: the target lease is released as soon as the gate fails.
            try { lock.verifyHeld(); }
            catch (IOException lost) { lost.addSuppressed(failed); throw lost; }
            throw failed;
        }
        lock.verifyHeld();
        if (journalEnabled) JournalState.clear(context);
        temps.clean();
        return result;
    }

    /**
     * Recover everything with {@code plan}: promotes the interrupted save if that is the plan
     * (refusing one that changed since the plan described it), otherwise replays the journal from
     * the plan's verified recovery point, keeps any interrupted save as a snapshot, and only then
     * writes {@code project.daw}. A replay can take long, so {@code lock} is verified again after
     * it, before any copy is made, and the commit verifies it last before it replaces the head
     * ({@link #replaceHead(Path, String, ProjectLockManager)}). Those checks fail as themselves,
     * never as {@link ReplayFailed}: the class note says why. A {@link ReplayFailed} from here
     * leaves {@code run} only after the lease is verified once more.
     */
    private Result recoverEverything(RecoveryPlan plan, ProjectLockManager lock) throws IOException {
        Path head = plan.context().projectFile();
        return switch (plan.recovery()) {
            case RecoveryPlan.PromoteNewerHead promote -> {
                String xml;
                try { xml = promote.temp().readUnchanged(); }
                catch (IOException changed) { throw new ReplayFailed(changed, false); }
                replaceHead(head, xml, lock);
                yield Result.INTERRUPTED_SAVE;
            }
            case RecoveryPlan.ReplayJournal _ -> {
                RecoveryResult replayed;
                try { replayed = replay.recover(plan); }
                catch (JournalRecoveryException failure) { throw new ReplayFailed(failure, false); }
                lock.verifyHeld();
                keepInterruptedSave(plan);
                String xml;
                try { xml = new ProjectSerializer().serialize(replayed.recoveredProject()); }
                catch (IOException failure) { throw new ReplayFailed(failure, true); }
                replaceHead(head, xml, lock);
                RecoverySummary.Changes changes = ((RecoverySummary.Replay) plan.summary().recovery()).changes();
                yield new Result(replayed.appliedRecordCount(), Math.toIntExact(changes.count() - changes.rebuilt()),
                        replayed.unreadItems(), false);
            }
        };
    }

    /**
     * Hands {@code xml} to the commit with {@code lock}'s check to run immediately before the
     * replace. The step that failed decides how the failure leaves, not its type (a
     * {@link LockStolenException} is an {@link IOException} too): a failed check propagates as
     * itself, even if the commit wrapped it in, or replaced it with, another {@link IOException}
     * (kept as suppressed); any other {@link IOException} from the commit is the write's,
     * {@link ReplayFailed} with {@code writing}.
     */
    private void replaceHead(Path head, String xml, ProjectLockManager lock) throws IOException {
        AtomicReference<IOException> checkFailed = new AtomicReference<>();
        try {
            commit.write(head, xml, () -> {
                try { lock.verifyHeld(); }
                catch (IOException lost) { checkFailed.set(lost); throw lost; }
            });
        } catch (IOException failure) {
            IOException lost = checkFailed.get();
            if (lost != null) {
                if (failure != lost) lost.addSuppressed(failure);
                throw lost;
            }
            throw new ReplayFailed(failure, true);
        }
    }

    /**
     * Restore checkpoint only with {@code plan}: opens {@code project.daw} as last saved, so it
     * refuses, before deleting anything, when there is none; keeps any interrupted save as a
     * snapshot.
     */
    private static Result restoreCheckpointOnly(RecoveryPlan plan) throws IOException {
        if (!Files.isRegularFile(plan.context().projectFile())) throw new IOException(NO_HEAD_FOR_CHECKPOINT_ONLY);
        keepInterruptedSave(plan);
        return Result.PLAIN;
    }

    /**
     * Copies the plan's interrupted save, byte for byte, into {@code snapshots/} as an
     * "Interrupted save" snapshot. Its failure is the decision's: nothing has been cleared yet, so
     * the failure names the interrupted save, where it could not be copied and why (by
     * {@link JournalState#reason}: never a bare path), and says that nothing was deleted.
     */
    private static void keepInterruptedSave(RecoveryPlan plan) throws IOException {
        Optional<RecoveryPlan.NewerHead> save = plan.interruptedSave();
        if (save.isEmpty()) return;
        Path snapshotsDir = plan.context().projectDirectory().resolve(SnapshotStore.SNAPSHOTS_DIR);
        SnapshotStore.Snapshot kept;
        try {
            kept = new SnapshotStore().createSnapshotFromFile(save.get().temp(), snapshotsDir, INTERRUPTED_SAVE_SNAPSHOT);
        } catch (IOException failure) {
            throw new IOException("The interrupted save " + save.get().temp().getFileName() + " could not be copied to "
                    + snapshotsDir + " (" + JournalState.reason(failure) + "); nothing was deleted", failure);
        }
        LOG.info(() -> "Kept the interrupted save " + save.get().temp() + " as snapshot " + kept.path());
    }
}
