package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.dialogs.RecoveryChoice;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.persistence.RecoveryTemps;
import com.benesquivelmusic.daw.core.persistence.UnreadProjectItem;
import com.benesquivelmusic.daw.core.persistence.journal.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

/** One worker-only LOCK → RECOVER gate shared by every project-open door. */
final class OpenRecoveryGate {
    private static final Logger LOG = Logger.getLogger(OpenRecoveryGate.class.getName());
    @FunctionalInterface interface Commit { void write(Path head, String xml) throws IOException; }
    record Result(int applied, List<UnreadProjectItem> unread) {
        static final Result PLAIN = new Result(-1, List.of());
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
        boolean recover = journalEnabled && JournalState.needsRecovery(context);
        if (!recover && temps.newerHead() == null) {
            lock.verifyHeld();
            if (journalEnabled) JournalState.clear(context);
            temps.clean();
            return Result.PLAIN;
        }
        RecoverySummary summary = new RecoveryScanner().scan(context).orElse(new RecoverySummary(-1L, null, "",
                "Atomic writer head", context.requestedAt(), 1L, Duration.ZERO, List.of(), 0L, List.of()));
        RecoveryChoice decision = choose.apply(summary);
        if (decision == RecoveryChoice.CANCEL || decision == RecoveryChoice.INSPECT_EVENTS) throw new Cancelled();
        lock.verifyHeld();
        Result result = Result.PLAIN;
        if (decision == RecoveryChoice.REPLAY_ALL) {
            try {
            if (temps.newerHead() != null && !recover) {
                commit.write(context.projectFile(), Files.readString(temps.newerHead()));
                result = new Result(0, List.of());
            } else {
                if (temps.newerHead() != null) {
                    Path retained = folder.resolve("snapshots").resolve("recovered-head-" + context.requestId() + ".daw");
                    Files.createDirectories(retained.getParent());
                    new com.benesquivelmusic.daw.core.persistence.AtomicFileWriter().copy(temps.newerHead(), retained,
                            com.benesquivelmusic.daw.core.persistence.AtomicFileWriter.Overwrite.NEVER);
                    LOG.info(() -> "Retained newer head temp as recovery snapshot " + retained);
                }
                RecoveryResult replay = new JournalReplayer().recover(context);
                commit.write(context.projectFile(), new ProjectSerializer().serialize(replay.recoveredProject()));
                result = new Result(replay.appliedRecordCount(), replay.unreadItems());
            }
            } catch (JournalRecoveryException failure) { throw new ReplayFailed(failure, false); }
            catch (IOException failure) { throw new ReplayFailed(failure, true); }
        }
        lock.verifyHeld();
        if (journalEnabled) JournalState.clear(context);
        temps.clean();
        return result;
    }
}
