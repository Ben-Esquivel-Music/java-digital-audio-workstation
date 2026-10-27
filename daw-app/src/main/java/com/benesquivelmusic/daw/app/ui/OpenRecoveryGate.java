package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.dialogs.RecoveryChoice;
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
 */
final class OpenRecoveryGate {
    private static final Logger LOG = Logger.getLogger(OpenRecoveryGate.class.getName());
    /** The snapshot name an interrupted save is kept under: SnapshotStore lists it as "interrupted save", and its file name contains "interrupted-save". */
    static final String INTERRUPTED_SAVE_SNAPSHOT = "Interrupted save";
    static final String NO_HEAD_FOR_CHECKPOINT_ONLY =
            "project.daw is missing, so Restore checkpoint only cannot open this project; the journal was kept";
    @FunctionalInterface interface Commit { void write(Path head, String xml) throws IOException; }

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

    OpenRecoveryGate(ProjectManager manager, Supplier<Boolean> enabled,
                     Function<RecoverySummary, RecoveryChoice> choose, Commit commit) {
        this.manager = manager;
        this.enabled = enabled;
        this.choose = choose;
        this.commit = commit;
    }

    Result run(Path folder, boolean readOnly) throws IOException {
        return run(folder, readOnly, manager.getLockManager());
    }

    Result run(Path folder, boolean readOnly, com.benesquivelmusic.daw.core.persistence.ProjectLockManager lock) throws IOException {
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
        Result result = decision == RecoveryChoice.REPLAY_ALL ? recoverEverything(plan) : restoreCheckpointOnly(plan);
        lock.verifyHeld();
        if (journalEnabled) JournalState.clear(context);
        temps.clean();
        return result;
    }

    /**
     * Recover everything with {@code plan}: promotes the interrupted save if that is the plan
     * (refusing one that changed since the plan described it), otherwise replays the journal from
     * the plan's verified recovery point, keeps any interrupted save as a snapshot, and only then
     * writes {@code project.daw}.
     */
    private Result recoverEverything(RecoveryPlan plan) throws IOException {
        Path head = plan.context().projectFile();
        return switch (plan.recovery()) {
            case RecoveryPlan.PromoteNewerHead promote -> {
                String xml;
                try { xml = promote.temp().readUnchanged(); }
                catch (IOException changed) { throw new ReplayFailed(changed, false); }
                try { commit.write(head, xml); }
                catch (IOException failure) { throw new ReplayFailed(failure, true); }
                yield Result.INTERRUPTED_SAVE;
            }
            case RecoveryPlan.ReplayJournal _ -> {
                RecoveryResult replay;
                try { replay = new JournalReplayer().recover(plan); }
                catch (JournalRecoveryException failure) { throw new ReplayFailed(failure, false); }
                keepInterruptedSave(plan);
                try { commit.write(head, new ProjectSerializer().serialize(replay.recoveredProject())); }
                catch (IOException failure) { throw new ReplayFailed(failure, true); }
                RecoverySummary.Changes changes = ((RecoverySummary.Replay) plan.summary().recovery()).changes();
                yield new Result(replay.appliedRecordCount(), Math.toIntExact(changes.count() - changes.rebuilt()),
                        replay.unreadItems(), false);
            }
        };
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
