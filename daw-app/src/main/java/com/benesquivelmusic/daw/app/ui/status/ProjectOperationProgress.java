package com.benesquivelmusic.daw.app.ui.status;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;

import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyLongProperty;
import javafx.beans.property.ReadOnlyLongWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The single, named source of project-status truth for the Session Status
 * Strip (Project Manager Design Book §5.5, story 295 — Stage 1 of the §7
 * migration path). A JavaFX {@code Property}-heavy model
 * ({@code javafx-application-design} §4): every visible status the strip
 * renders is one observable property here, and the strip's cells
 * {@code bind(...)} to them. No controller writes status text anywhere else
 * — this model is the §8 rejection of "status text in {@code Label.setText}".
 *
 * <h2>The seven invisible signals, promoted to chrome (§2.2)</h2>
 *
 * <p>The properties cover the §2.2 facts the user previously could not see:
 * the {@link #lastSaveTimeProperty() last save} (time + {@link SaveScope
 * scope} + {@link #lastSaveSucceededProperty() outcome}), the
 * {@link #nextCheckpointInstantProperty() next checkpoint}, the
 * {@link #journalEventsQueuedProperty() journal queue}, the
 * {@link #lockHolderLabelProperty() lock holder} + {@link #lockFreshProperty()
 * freshness}, {@link #freeDiskBytesProperty() free disk} +
 * {@link #estimatedRecordMinutesProperty() capture estimate}, the
 * {@link #sessionNameProperty() session name} + {@link #sessionElapsedProperty()
 * age}, and the {@link #currentSchemaVersionProperty() schema} +
 * {@link #backupSchemaVersionProperty() backup schema}. In-flight long
 * operations register on the {@link #getOperations() operations list} (§5.5);
 * {@link #savingProperty() saving} is simply that list being non-empty.</p>
 *
 * <h2>Single writer; read-only views (§2.4)</h2>
 *
 * <p>Every property is a private {@code ReadOnly*Wrapper}; only the read-only
 * view is handed to controls, so a bound cell can never write back. The model
 * is the sole writer, through the keyed mutators below. {@link #dirtyProperty()
 * dirty} is the one exception: it is <em>bound</em> to story 292's
 * {@code ProjectVM.dirtyProperty()} via {@link #bindDirtyTo(ObservableValue)}
 * rather than re-derived (the §295 goal "bind {@code dirty} to it"). ProjectVM
 * exposes neither a last-save time nor a saving flag, so {@code lastSaveTime}
 * is written by the save path and {@code saving} is derived from the
 * operations list.</p>
 *
 * <h2>Threading — the FX-confinement seam (§2.1, story 289)</h2>
 *
 * <p>Status is produced off the FX thread (the 30&nbsp;s disk scan on a virtual
 * thread, checkpoint callbacks on the checkpoint scheduler, save on the FX
 * thread). Every mutator routes its write through the story-289
 * {@link FxDispatcher#onFx(Object, Runnable) keyed} dispatcher, so the property
 * is only ever set on the FX thread and N rapid mutations under the same key
 * within one frame coalesce to a single strip repaint
 * ({@code javafx-application-design} §6 "coalesce per frame", §11 "the FX thread
 * is sacred"). Discrete operation register/unregister uses the un-keyed
 * {@link FxDispatcher#onFx(Runnable)} so every add/remove survives (never
 * coalesced away).</p>
 */
public final class ProjectOperationProgress {

    /**
     * Scope of a save: a full re-serialisation ({@link #FULL}) or a journal
     * delta ({@link #DELTA}). Stage 1 only ever produces {@link #FULL} (the
     * journal is story 298); the enum exists now so the Saved cell never needs
     * an API break when delta saves land.
     */
    public enum SaveScope {
        /** A complete re-serialisation of {@code project.daw}. */
        FULL,
        /** An incremental journal delta (story 298 — not produced in Stage 1). */
        DELTA
    }

    /**
     * The §6.3 write-ahead-journal back-pressure level surfaced on the Journal
     * cell (story 298). It mirrors the daw-core
     * {@code com.benesquivelmusic.daw.core.persistence.journal.Backpressure}
     * enum without depending on it (the SDK/UI seam keeps the JavaFX status model
     * free of a direct core enum import):
     *
     * <ul>
     *   <li>{@link #NORMAL} — the hand-off queue is keeping up; the Journal cell
     *       renders neutrally.</li>
     *   <li>{@link #SLOW} — the bounded queue filled and records are buffering in
     *       memory ("disk is slow — events buffering"); the cell goes amber
     *       ({@link StatusStripCell.Severity#WARN}).</li>
     *   <li>{@link #UNRESPONSIVE} — the overflow byte budget was exceeded ("disk
     *       unresponsive"); the cell goes red
     *       ({@link StatusStripCell.Severity#CRITICAL}) and the controller also
     *       raises an error notification. Records are still buffered, never
     *       dropped.</li>
     * </ul>
     */
    public enum JournalBackpressure {
        /** Queue keeping up; Journal cell neutral. */
        NORMAL,
        /** Disk slow — events buffering in memory; Journal cell amber. */
        SLOW,
        /** Disk unresponsive — overflow budget exceeded; Journal cell red. */
        UNRESPONSIVE
    }

    /**
     * Coalescing keys — one per logical fact group. N rapid mutations under one
     * key within a single {@link FxDispatcher#pulse() frame} collapse to the
     * latest (story 295 binding-test contract). Distinct facts use distinct keys
     * so they never overwrite each other.
     */
    private enum Key { SAVE, CHECKPOINT, JOURNAL, LOCK, DISK, SESSION, SCHEMA, WORKSPACE }

    /** Sentinel for an unknown numeric value (no project open / scan not yet run). */
    public static final long UNKNOWN_BYTES = -1L;
    /** Sentinel for an unknown record-minutes estimate. */
    public static final double UNKNOWN_MINUTES = -1.0;
    /** Sentinel for an unknown / inapplicable schema version (no project, or no backup). */
    public static final int UNKNOWN_VERSION = -1;

    private final FxDispatcher dispatcher;

    private final ReadOnlyObjectWrapper<Instant> lastSaveTime =
            new ReadOnlyObjectWrapper<>(this, "lastSaveTime", null);
    private final ReadOnlyObjectWrapper<SaveScope> lastSaveScope =
            new ReadOnlyObjectWrapper<>(this, "lastSaveScope", SaveScope.FULL);
    private final ReadOnlyBooleanWrapper lastSaveSucceeded =
            new ReadOnlyBooleanWrapper(this, "lastSaveSucceeded", true);

    private final ReadOnlyObjectWrapper<Instant> nextCheckpointInstant =
            new ReadOnlyObjectWrapper<>(this, "nextCheckpointInstant", null);
    private final ReadOnlyObjectWrapper<Duration> checkpointInterval =
            new ReadOnlyObjectWrapper<>(this, "checkpointInterval", Duration.ofMinutes(5));
    private final ReadOnlyIntegerWrapper journalEventsQueued =
            new ReadOnlyIntegerWrapper(this, "journalEventsQueued", 0);
    private final ReadOnlyObjectWrapper<JournalBackpressure> journalBackpressure =
            new ReadOnlyObjectWrapper<>(this, "journalBackpressure", JournalBackpressure.NORMAL);

    private final ReadOnlyStringWrapper lockHolderLabel =
            new ReadOnlyStringWrapper(this, "lockHolderLabel", "");
    private final ReadOnlyBooleanWrapper lockFresh =
            new ReadOnlyBooleanWrapper(this, "lockFresh", true);

    private final ReadOnlyLongWrapper freeDiskBytes =
            new ReadOnlyLongWrapper(this, "freeDiskBytes", UNKNOWN_BYTES);
    private final ReadOnlyDoubleWrapper estimatedRecordMinutes =
            new ReadOnlyDoubleWrapper(this, "estimatedRecordMinutes", UNKNOWN_MINUTES);

    private final ReadOnlyStringWrapper sessionName =
            new ReadOnlyStringWrapper(this, "sessionName", "");
    private final ReadOnlyObjectWrapper<Duration> sessionElapsed =
            new ReadOnlyObjectWrapper<>(this, "sessionElapsed", Duration.ZERO);

    private final ReadOnlyIntegerWrapper currentSchemaVersion =
            new ReadOnlyIntegerWrapper(this, "currentSchemaVersion", UNKNOWN_VERSION);
    private final ReadOnlyIntegerWrapper backupSchemaVersion =
            new ReadOnlyIntegerWrapper(this, "backupSchemaVersion", UNKNOWN_VERSION);

    private final ReadOnlyStringWrapper workspaceName =
            new ReadOnlyStringWrapper(this, "workspaceName", "");

    /**
     * The single dirty bit (§1.2), bound to {@code ProjectVM.dirty} (§295 goal
     * "bind {@code dirty} to it"). <strong>Forward-declared</strong>: its visible
     * form is the §8 Journal cell ("events queued since last checkpoint" — the
     * dirty-dot replacement), whose producer is the write-ahead journal of story
     * 298. Until then this bound bit has no rendered cell; it is exposed now so a
     * later stage (or an external consumer) can read the one dirty source without
     * an API break, mirroring the forward-declared {@code ProjectVM.checkpoint}.
     */
    private final ReadOnlyBooleanWrapper dirty =
            new ReadOnlyBooleanWrapper(this, "dirty", false);

    /** In-flight named operations (§5.5). Mutated only on the FX thread. */
    private final ObservableList<NamedOperation> operationsBacking =
            FXCollections.observableArrayList();
    private final ObservableList<NamedOperation> operations =
            FXCollections.unmodifiableObservableList(operationsBacking);

    /** {@code true} while any operation is in flight (§4.5 slow-save HUD). */
    private final ReadOnlyBooleanWrapper saving =
            new ReadOnlyBooleanWrapper(this, "saving");

    /**
     * Creates a model whose every mutation is marshalled onto the FX thread
     * through {@code dispatcher} (story 289).
     *
     * @param dispatcher the FX-confinement seam; must not be {@code null}
     * @throws NullPointerException if {@code dispatcher} is {@code null}
     */
    public ProjectOperationProgress(FxDispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        // saving := operations non-empty — the §4.5 "an operation is running"
        // signal, derived rather than poked so it can never drift from the list.
        saving.bind(Bindings.isNotEmpty(operationsBacking));
    }

    // ── Mutators (callable from any thread; keyed-coalesced onto the FX thread) ──

    /**
     * Records a successful save: stamps {@link #lastSaveTimeProperty() time},
     * {@link #lastSaveScopeProperty() scope}, and flips
     * {@link #lastSaveSucceededProperty()} to {@code true}. The strip's Saved
     * cell flashes — that flash <em>is</em> the success signal (§4.5), replacing
     * the retired "Project saved" notification.
     *
     * @param time  the moment the save completed; must not be {@code null}
     * @param scope full vs. delta; must not be {@code null}
     */
    public void recordSaveSucceeded(Instant time, SaveScope scope) {
        Objects.requireNonNull(time, "time must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        publish(Key.SAVE, () -> {
            lastSaveTime.set(time);
            lastSaveScope.set(scope);
            lastSaveSucceeded.set(true);
        });
    }

    /**
     * Records a failed save: flips {@link #lastSaveSucceededProperty()} to
     * {@code false} so the Saved cell turns to its error state (§4.5 — the cell
     * persists the failure; it does not auto-dismiss). The last successful
     * {@code lastSaveTime} is left untouched.
     */
    public void recordSaveFailed() {
        publish(Key.SAVE, () -> lastSaveSucceeded.set(false));
    }

    /**
     * Publishes the instant the next checkpoint will fire (or {@code null} when
     * no project is open / autosave is off) <em>and</em> the autosave interval
     * that is the §6 countdown bar's denominator. Both travel under one
     * coalescing key and are set together so the strip's countdown is exact for
     * both {@code AutoSaveConfig.DEFAULT} (5&nbsp;min) and {@code LONG_SESSION}
     * (30&nbsp;s) (story 295 Technical Notes).
     *
     * @param instant  the next-fire instant, or {@code null} for "none scheduled"
     * @param interval the autosave interval; must not be {@code null}
     */
    public void setNextCheckpoint(Instant instant, Duration interval) {
        Objects.requireNonNull(interval, "interval must not be null");
        publish(Key.CHECKPOINT, () -> {
            nextCheckpointInstant.set(instant);
            checkpointInterval.set(interval);
        });
    }

    /**
     * Publishes the number of journal events queued since the last checkpoint
     * (the §8 replacement for the single dirty dot). Stage 1 has no journal
     * (story 298), so production leaves this at {@code 0}; the cell exists now.
     *
     * @param count the queued-event count
     */
    public void setJournalEventsQueued(int count) {
        publish(Key.JOURNAL, () -> journalEventsQueued.set(count));
    }

    /**
     * Publishes the §6.3 write-ahead-journal back-pressure level (story 298).
     * Sourced off the FX thread from the journal writer's virtual-thread
     * {@code JournalListener.onBackpressure(...)} callback; the strip's Journal
     * cell goes amber on {@link JournalBackpressure#SLOW} and red on
     * {@link JournalBackpressure#UNRESPONSIVE}. Travels under the same
     * {@link Key#JOURNAL} coalescing key as the queued count (both are facets of
     * the one Journal cell), so a {@code null} is coerced to
     * {@link JournalBackpressure#NORMAL} to keep the cell well-formed.
     *
     * @param level the back-pressure level; a {@code null} becomes
     *              {@link JournalBackpressure#NORMAL}
     */
    public void setJournalBackpressure(JournalBackpressure level) {
        JournalBackpressure resolved = level == null ? JournalBackpressure.NORMAL : level;
        publish(Key.JOURNAL, () -> journalBackpressure.set(resolved));
    }

    /**
     * Publishes the lock-holder label (e.g. {@code "you @ Studio-PC"}) and
     * whether the heartbeat is fresh (§1.3).
     *
     * @param holderLabel the human-readable holder; must not be {@code null}
     * @param fresh       whether the heartbeat is live (not stale)
     */
    public void setLock(String holderLabel, boolean fresh) {
        Objects.requireNonNull(holderLabel, "holderLabel must not be null");
        publish(Key.LOCK, () -> {
            lockHolderLabel.set(holderLabel);
            lockFresh.set(fresh);
        });
    }

    /**
     * Publishes the §1.8 disk-space contract: free bytes and the derived
     * capture-minutes estimate. Sourced off the FX thread by the 30&nbsp;s disk
     * scan; the disk cell goes amber under 30 record-minutes and red under 5
     * (§6.2).
     *
     * @param freeBytes      usable bytes on the project's file store, or
     *                       {@link #UNKNOWN_BYTES}
     * @param recordMinutes  estimated minutes of capture room, or
     *                       {@link #UNKNOWN_MINUTES}
     */
    public void setDisk(long freeBytes, double recordMinutes) {
        publish(Key.DISK, () -> {
            freeDiskBytes.set(freeBytes);
            estimatedRecordMinutes.set(recordMinutes);
        });
    }

    /**
     * Publishes the §3.3 working-session name and elapsed time.
     *
     * @param name    the session name; must not be {@code null}
     * @param elapsed the elapsed session time; must not be {@code null}
     */
    public void setSession(String name, Duration elapsed) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(elapsed, "elapsed must not be null");
        publish(Key.SESSION, () -> {
            sessionName.set(name);
            sessionElapsed.set(elapsed);
        });
    }

    /**
     * Publishes the schema versions (§1.6): the current on-disk schema and the
     * pre-migration backup schema, or {@link #UNKNOWN_VERSION} for "no backup".
     *
     * @param current the current schema version
     * @param backup  the backup's schema version, or {@link #UNKNOWN_VERSION}
     */
    public void setSchema(int current, int backup) {
        publish(Key.SCHEMA, () -> {
            currentSchemaVersion.set(current);
            backupSchemaVersion.set(backup);
        });
    }

    /**
     * Publishes the §3.1 workspace name shown in the Workspace cell.
     *
     * @param name the workspace name; must not be {@code null}
     */
    public void setWorkspaceName(String name) {
        Objects.requireNonNull(name, "name must not be null");
        publish(Key.WORKSPACE, () -> workspaceName.set(name));
    }

    /**
     * Registers an in-flight operation (§5.5). Any operation running &gt;500&nbsp;ms
     * shows progress in the strip (§8 "no background work without progress").
     * Discrete — routed un-keyed so it is never coalesced away.
     *
     * @param operation the operation to add; must not be {@code null}
     */
    public void addOperation(NamedOperation operation) {
        Objects.requireNonNull(operation, "operation must not be null");
        dispatcher.onFx(() -> {
            if (!operationsBacking.contains(operation)) {
                operationsBacking.add(operation);
            }
        });
    }

    /**
     * Unregisters a finished operation (§5.5). Discrete — routed un-keyed so it
     * is never coalesced away.
     *
     * @param operation the operation to remove; must not be {@code null}
     */
    public void removeOperation(NamedOperation operation) {
        Objects.requireNonNull(operation, "operation must not be null");
        dispatcher.onFx(() -> operationsBacking.remove(operation));
    }

    /**
     * Binds {@link #dirtyProperty()} to story 292's {@code ProjectVM.dirty}
     * (the §1.2 "one dirty bit"), so the model mirrors the live core dirty bit
     * rather than re-deriving it (§295 goal). Idempotent re-binding is the
     * caller's responsibility; the composition root calls this once per
     * {@code ProjectVM} generation.
     *
     * @param source the observable dirty bit to mirror; must not be {@code null}
     */
    public void bindDirtyTo(ObservableValue<? extends Boolean> source) {
        Objects.requireNonNull(source, "source must not be null");
        dirty.bind(source);
    }

    /**
     * Routes a property write onto the FX thread with per-key coalescing
     * (story 289). The write runs on the next {@link FxDispatcher#pulse()},
     * and only the latest write per key in that frame survives.
     */
    private void publish(Key key, Runnable write) {
        dispatcher.onFx(key, write);
    }

    // ── Read-only property views (the model is the sole writer, §2.4) ─────────

    /** @return the last successful save time, or {@code null} if never saved. */
    public ReadOnlyObjectProperty<Instant> lastSaveTimeProperty() { return lastSaveTime.getReadOnlyProperty(); }
    /** @return the last save time, or {@code null}. */
    public Instant getLastSaveTime() { return lastSaveTime.get(); }

    /** @return the last save's {@link SaveScope} (full vs. delta). */
    public ReadOnlyObjectProperty<SaveScope> lastSaveScopeProperty() { return lastSaveScope.getReadOnlyProperty(); }
    /** @return the last save scope. */
    public SaveScope getLastSaveScope() { return lastSaveScope.get(); }

    /** @return whether the last save succeeded ({@code false} → Saved cell error state). */
    public ReadOnlyBooleanProperty lastSaveSucceededProperty() { return lastSaveSucceeded.getReadOnlyProperty(); }
    /** @return whether the last save succeeded. */
    public boolean isLastSaveSucceeded() { return lastSaveSucceeded.get(); }

    /** @return the instant the next checkpoint fires, or {@code null}. */
    public ReadOnlyObjectProperty<Instant> nextCheckpointInstantProperty() { return nextCheckpointInstant.getReadOnlyProperty(); }
    /** @return the next checkpoint instant, or {@code null}. */
    public Instant getNextCheckpointInstant() { return nextCheckpointInstant.get(); }

    /** @return the autosave interval (the §6 countdown bar's denominator). */
    public ReadOnlyObjectProperty<Duration> checkpointIntervalProperty() { return checkpointInterval.getReadOnlyProperty(); }
    /** @return the autosave interval. */
    public Duration getCheckpointInterval() { return checkpointInterval.get(); }

    /** @return the journal-events-queued count (the §8 dirty-dot replacement). */
    public ReadOnlyIntegerProperty journalEventsQueuedProperty() { return journalEventsQueued.getReadOnlyProperty(); }
    /** @return the queued journal-event count. */
    public int getJournalEventsQueued() { return journalEventsQueued.get(); }

    /** @return the §6.3 journal back-pressure level (story 298; default {@link JournalBackpressure#NORMAL}). */
    public ReadOnlyObjectProperty<JournalBackpressure> journalBackpressureProperty() { return journalBackpressure.getReadOnlyProperty(); }
    /** @return the current journal back-pressure level (never {@code null}). */
    public JournalBackpressure getJournalBackpressure() { return journalBackpressure.get(); }

    /** @return the lock-holder label (§1.3). */
    public ReadOnlyStringProperty lockHolderLabelProperty() { return lockHolderLabel.getReadOnlyProperty(); }
    /** @return the lock-holder label. */
    public String getLockHolderLabel() { return lockHolderLabel.get(); }

    /** @return whether the lock heartbeat is fresh (not stale). */
    public ReadOnlyBooleanProperty lockFreshProperty() { return lockFresh.getReadOnlyProperty(); }
    /** @return whether the lock is fresh. */
    public boolean isLockFresh() { return lockFresh.get(); }

    /** @return the free disk bytes, or {@link #UNKNOWN_BYTES} (§1.8). */
    public ReadOnlyLongProperty freeDiskBytesProperty() { return freeDiskBytes.getReadOnlyProperty(); }
    /** @return the free disk bytes, or {@link #UNKNOWN_BYTES}. */
    public long getFreeDiskBytes() { return freeDiskBytes.get(); }

    /** @return the estimated record-minutes, or {@link #UNKNOWN_MINUTES} (§6.2 amber/red). */
    public ReadOnlyDoubleProperty estimatedRecordMinutesProperty() { return estimatedRecordMinutes.getReadOnlyProperty(); }
    /** @return the estimated record-minutes, or {@link #UNKNOWN_MINUTES}. */
    public double getEstimatedRecordMinutes() { return estimatedRecordMinutes.get(); }

    /** @return the §3.3 session name. */
    public ReadOnlyStringProperty sessionNameProperty() { return sessionName.getReadOnlyProperty(); }
    /** @return the session name. */
    public String getSessionName() { return sessionName.get(); }

    /** @return the elapsed session time. */
    public ReadOnlyObjectProperty<Duration> sessionElapsedProperty() { return sessionElapsed.getReadOnlyProperty(); }
    /** @return the elapsed session time. */
    public Duration getSessionElapsed() { return sessionElapsed.get(); }

    /** @return the current on-disk schema version, or {@link #UNKNOWN_VERSION}. */
    public ReadOnlyIntegerProperty currentSchemaVersionProperty() { return currentSchemaVersion.getReadOnlyProperty(); }
    /** @return the current schema version. */
    public int getCurrentSchemaVersion() { return currentSchemaVersion.get(); }

    /** @return the pre-migration backup schema version, or {@link #UNKNOWN_VERSION} (no backup). */
    public ReadOnlyIntegerProperty backupSchemaVersionProperty() { return backupSchemaVersion.getReadOnlyProperty(); }
    /** @return the backup schema version, or {@link #UNKNOWN_VERSION}. */
    public int getBackupSchemaVersion() { return backupSchemaVersion.get(); }

    /** @return the §3.1 workspace name. */
    public ReadOnlyStringProperty workspaceNameProperty() { return workspaceName.getReadOnlyProperty(); }
    /** @return the workspace name. */
    public String getWorkspaceName() { return workspaceName.get(); }

    /** @return the single dirty bit (§1.2), bound to {@code ProjectVM.dirty}. */
    public ReadOnlyBooleanProperty dirtyProperty() { return dirty.getReadOnlyProperty(); }
    /** @return whether the project has unsaved changes. */
    public boolean isDirty() { return dirty.get(); }

    /** @return {@code true} while any operation is in flight (§4.5). */
    public ReadOnlyBooleanProperty savingProperty() { return saving.getReadOnlyProperty(); }
    /** @return whether an operation is in flight. */
    public boolean isSaving() { return saving.get(); }

    /**
     * The in-flight named operations (§5.5). Unmodifiable observable view —
     * mutate only through {@link #addOperation(NamedOperation)} /
     * {@link #removeOperation(NamedOperation)}.
     *
     * @return the live, read-only operations list
     */
    public ObservableList<NamedOperation> getOperations() {
        return operations;
    }

    /**
     * A named, in-flight project operation (save, autosave, archive, …) with an
     * observable progress in {@code [0,1]} or {@code -1} for indeterminate
     * (§4.5 — a slow save overlays a progress bar on the Saved cell). The slow
     * worker updates {@link #progressProperty()} as it runs.
     */
    public static final class NamedOperation {

        /** Indeterminate-progress sentinel (an indeterminate progress bar). */
        public static final double INDETERMINATE = -1.0;

        private final String name;
        private final javafx.beans.property.DoubleProperty progress;

        /**
         * Creates an indeterminate operation.
         *
         * @param name the human-readable operation name; must not be {@code null}
         */
        public NamedOperation(String name) {
            this(name, INDETERMINATE);
        }

        /**
         * Creates an operation with an initial progress.
         *
         * @param name     the human-readable operation name; must not be {@code null}
         * @param progress {@code [0,1]} or {@link #INDETERMINATE}
         */
        public NamedOperation(String name, double progress) {
            this.name = Objects.requireNonNull(name, "name must not be null");
            this.progress = new javafx.beans.property.SimpleDoubleProperty(this, "progress", progress);
        }

        /** @return the operation name. */
        public String name() { return name; }

        /** @return the observable progress property ({@code [0,1]} or {@link #INDETERMINATE}). */
        public javafx.beans.property.DoubleProperty progressProperty() { return progress; }

        /** @return the current progress. */
        public double getProgress() { return progress.get(); }

        /** @param value the new progress ({@code [0,1]} or {@link #INDETERMINATE}). */
        public void setProgress(double value) { progress.set(value); }
    }
}
