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

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.function.BooleanSupplier;

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
 *
 * <h2>Head save and checkpoint: two states (story 331)</h2>
 *
 * <p>The outcome of a save of {@code project.daw} — the <em>head</em> — and
 * the outcome of a checkpoint are separate facts (rulings F6, G4): each has
 * its own properties and its own coalescing key, distinct from the
 * countdown's (ruling G13 #3), so neither erases the other in one frame. A
 * head failure is cleared only by a head save success of the same project,
 * a checkpoint failure only by a checkpoint success of the same project, and
 * both by a project replacement ({@link #resetForProject}); never by the
 * other kind, a skip, a dismissed notification or time (ruling G4 (d)).</p>
 *
 * <p>Both states belong to the open project's <em>generation</em>
 * ({@link #projectGeneration()}), which every project replacement advances.
 * Each head and checkpoint mutator names the generation its outcome was taken
 * under; one of a generation that is no longer the open one changes nothing
 * — neither when it is posted nor when its frame applies it — so an outcome
 * that arrives after a project switch never touches the project that replaced
 * it (ruling G13 #4, #7).</p>
 *
 * <p>Every fact a head or checkpoint mutator sets has a coalescing key of its
 * own (memory "one key per fact"): the head's failure state, the head's last
 * save time, the head's success count, the checkpoint failure state, the last
 * good checkpoint and the countdown. A success and a failure posted in one
 * frame therefore both leave what each set: the failure stands, and the
 * success's time — and, for a checkpoint, its file — is kept.</p>
 *
 * <p>The last good checkpoint is not the strip generation's but the
 * <em>checkpoint generation's</em> (ruling K1): the newest checkpoint on disk
 * as the project's checkpoints were seeded, then the last one they wrote. It
 * is shown only while the checkpoint generation that seeded or wrote it still
 * runs, as the predicate it was recorded with answers on the FX thread — when
 * its frame applies it, when the project is replaced, and when
 * {@link #checkpointsEnded()} asks, as it does when the lifecycle controller
 * abandons the open project, which every end of a checkpoint generation
 * follows — so a snapshot restore, which keeps the project's checkpoints
 * running, keeps it, and nothing of a replaced project's checkpoints is
 * shown for the project that replaced it.</p>
 *
 * <h2>The countdown (story 331, rulings G13 #2, M4, N2)</h2>
 *
 * <p>Only the checkpoints' re-arms write the countdown
 * ({@link #rearmCheckpointCountdown}); nothing guesses it. Only the end of
 * the checkpoint generation clears it ({@link #checkpointsEnded}): a
 * replacement that ends it ({@link #resetForProject}), and the lifecycle
 * controller's abandon of the open project, which a failed open leaves with
 * no replacement (ruling N2). The strip then shows no countdown until a
 * running generation arms its first tick. A snapshot restore keeps the
 * generation running, so it keeps the countdown
 * ({@link #resetForSnapshotRestore}).</p>
 *
 * <h2>Today (story 331, ruling L1)</h2>
 *
 * <p>Which day is today is a fact of this model too, so the strip renders a
 * time with its date when it is of another day without reading a clock:
 * {@link #todayProperty()}, in the JVM's default time zone as fixed at
 * launch, starts as the constructor's clock's date and moves on through
 * {@link #setToday}, which the strip's 5&nbsp;s poll calls with the system
 * clock's date — so a session that runs past midnight, or wakes from sleep,
 * shows the date within a poll.</p>
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
     * Coalescing keys — one per fact. N rapid mutations under one key within a
     * single {@link FxDispatcher#pulse() frame} collapse to the latest (story
     * 295 binding-test contract), so every mutator under a key sets every
     * field of that key, and no field of another key. Distinct facts use
     * distinct keys so they never overwrite each other (story 331, ruling G13
     * #3, memory "one key per fact"): the head's failure state
     * ({@link #SAVE_STATE}), its last save time ({@link #SAVE_TIME}) and its
     * success count ({@link #SAVE_SUCCESS}); the checkpoint failure state
     * ({@link #CHECKPOINT_STATE}), the last good checkpoint
     * ({@link #CHECKPOINT_LAST_GOOD}) and the countdown ({@link #CHECKPOINT});
     * the journal's queued count ({@link #JOURNAL_QUEUED}) and its
     * back-pressure ({@link #JOURNAL_BACKPRESSURE}); and today's date
     * ({@link #TODAY}, ruling L1).
     */
    private enum Key {
        SAVE_STATE, SAVE_TIME, SAVE_SUCCESS, CHECKPOINT, CHECKPOINT_STATE, CHECKPOINT_LAST_GOOD, JOURNAL_QUEUED,
        JOURNAL_BACKPRESSURE, LOCK, DISK, SESSION, SCHEMA, WORKSPACE, TODAY
    }

    /**
     * The coalescing key of a fact that belongs to one project generation: a
     * stale generation's post never takes the place of the open one's in a
     * frame, and runs as nothing.
     */
    private record GenerationKey(Key key, long generation) { }

    /** Sentinel for an unknown numeric value (no project open / scan not yet run). */
    public static final long UNKNOWN_BYTES = -1L;
    /** Sentinel for an unknown record-minutes estimate. */
    public static final double UNKNOWN_MINUTES = -1.0;
    /** Sentinel for an unknown / inapplicable schema version (no project, or no backup). */
    public static final int UNKNOWN_VERSION = -1;

    /**
     * The name of the {@link NamedOperation operation} registered while a
     * head save — a save of {@code project.daw} — has run past 500&nbsp;ms
     * (PM book §4.5, story 331 ruling G4): the one operation the Saved cell
     * shows ahead of a standing failure.
     */
    public static final String HEAD_SAVE_OPERATION_NAME = "Saving project…";

    private final FxDispatcher dispatcher;

    private final ReadOnlyObjectWrapper<Instant> lastSaveTime =
            new ReadOnlyObjectWrapper<>(this, "lastSaveTime", null);
    private final ReadOnlyObjectWrapper<SaveScope> lastSaveScope =
            new ReadOnlyObjectWrapper<>(this, "lastSaveScope", SaveScope.FULL);
    private final ReadOnlyBooleanWrapper lastSaveSucceeded =
            new ReadOnlyBooleanWrapper(this, "lastSaveSucceeded", true);
    private final ReadOnlyObjectWrapper<Throwable> saveFailureCause =
            new ReadOnlyObjectWrapper<>(this, "saveFailureCause", null);
    private final ReadOnlyObjectWrapper<Instant> saveFailureTime =
            new ReadOnlyObjectWrapper<>(this, "saveFailureTime", null);
    private final ReadOnlyLongWrapper savesSucceeded =
            new ReadOnlyLongWrapper(this, "savesSucceeded", 0L);

    private final ReadOnlyBooleanWrapper lastCheckpointSucceeded =
            new ReadOnlyBooleanWrapper(this, "lastCheckpointSucceeded", true);
    private final ReadOnlyObjectWrapper<Throwable> checkpointFailureCause =
            new ReadOnlyObjectWrapper<>(this, "checkpointFailureCause", null);
    private final ReadOnlyObjectWrapper<Instant> checkpointFailureTime =
            new ReadOnlyObjectWrapper<>(this, "checkpointFailureTime", null);
    private final ReadOnlyObjectWrapper<Instant> lastCheckpointTime =
            new ReadOnlyObjectWrapper<>(this, "lastCheckpointTime", null);
    private final ReadOnlyObjectWrapper<Path> lastCheckpointFile =
            new ReadOnlyObjectWrapper<>(this, "lastCheckpointFile", null);

    /**
     * Whether the checkpoint generation that seeded or wrote the last good
     * checkpoint still runs (ruling K1); asked on the FX thread only. FX
     * thread.
     */
    private BooleanSupplier lastCheckpointRuns = () -> false;

    /**
     * The open project's generation: advanced, on the FX thread, by every
     * {@link #resetForProject} and {@link #resetForSnapshotRestore}; read
     * from any thread by the producers that tag their outcomes with it.
     */
    private volatile long projectGeneration;

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
     * Today's date in the JVM's default time zone as fixed at launch (ruling
     * L1): the day the strip's times are of when they carry no date. Starts
     * as the constructor's clock's date; {@link #setToday} moves it on.
     */
    private final ReadOnlyObjectWrapper<LocalDate> today;

    /** The date {@link #setToday} last posted, so an equal date is not posted again. */
    private volatile LocalDate todayPosted;

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
     * through {@code dispatcher} (story 289), its {@link #todayProperty() today}
     * the system clock's date in the JVM's default time zone.
     *
     * @param dispatcher the FX-confinement seam; must not be {@code null}
     * @throws NullPointerException if {@code dispatcher} is {@code null}
     */
    public ProjectOperationProgress(FxDispatcher dispatcher) {
        this(dispatcher, Clock.systemDefaultZone());
    }

    /**
     * Creates a model whose every mutation is marshalled onto the FX thread
     * through {@code dispatcher} (story 289), its {@link #todayProperty() today}
     * starting as {@code clock}'s date (ruling L1). The application passes the
     * system clock (through {@link #ProjectOperationProgress(FxDispatcher)});
     * a test passes a fixed one, so the strip's dates do not depend on when it
     * runs.
     *
     * @param dispatcher the FX-confinement seam; must not be {@code null}
     * @param clock      the clock today's date starts from; must not be {@code null}
     * @throws NullPointerException if either is {@code null}
     */
    public ProjectOperationProgress(FxDispatcher dispatcher, Clock clock) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        LocalDate start = LocalDate.now(Objects.requireNonNull(clock, "clock must not be null"));
        this.today = new ReadOnlyObjectWrapper<>(this, "today", start);
        this.todayPosted = start;
        // saving := operations non-empty — the §4.5 "an operation is running"
        // signal, derived rather than poked so it can never drift from the list.
        saving.bind(Bindings.isNotEmpty(operationsBacking));
    }

    // ── Mutators (callable from any thread; keyed-coalesced onto the FX thread) ──

    /**
     * The open project's generation (see the class note): the token a
     * producer reads when it takes an outcome of the open project and passes
     * to the head and checkpoint mutators. Callable from any thread.
     *
     * @return the open project's generation
     */
    public long projectGeneration() {
        return projectGeneration;
    }

    /**
     * A project replaced the open one and ended its checkpoints — New, Open,
     * Import, a restore from an archive, a recovery's load, a roll-back
     * (story 331, rulings G13 #4, M4): begins a new
     * {@link #projectGeneration() generation}, so nothing reported for the
     * replaced project changes the strip any more, and resets the head and
     * checkpoint states — no failure stands, and no countdown runs until the
     * new project's checkpoints re-arm, which they do only once they are
     * seeded — and the last save time becomes {@code lastSaved}, the
     * replacing project's {@code ProjectMetadata.lastModified} (or
     * {@code null} when it has never been saved). The last good checkpoint is
     * kept only while the checkpoint generation that seeded or wrote it still
     * runs, and is otherwise cleared until the new project's checkpoints
     * report theirs (ruling K1). The autosave failure streak, counted against
     * the generation, starts over. Applied at once: <strong>FX thread
     * only</strong>.
     *
     * @param lastSaved when the replacing project was last saved, or {@code null}
     */
    public void resetForProject(Instant lastSaved) {
        startANewGeneration(lastSaved);
        checkpointsEnded();
    }

    /**
     * A snapshot of the open project replaced it, and its checkpoints run on
     * (story 331, rulings G13 #4, K1, M4): as {@link #resetForProject}, except
     * that the countdown and its interval are left as they are — the
     * checkpoints' armed tick is unchanged, so the countdown never passes
     * through "No checkpoint scheduled". A re-arm of the running checkpoints
     * still pending when the generation changed is posted again by its
     * listener, under the new generation. Applied at once: <strong>FX thread
     * only</strong>.
     *
     * @param lastSaved when {@code project.daw} was last written, or {@code null} when it never was
     */
    public void resetForSnapshotRestore(Instant lastSaved) {
        startANewGeneration(lastSaved);
    }

    /** The common part of a replacement: a new generation and the head and checkpoint states reset. FX thread. */
    private void startANewGeneration(Instant lastSaved) {
        projectGeneration++;
        lastSaveTime.set(lastSaved);
        lastSaveScope.set(SaveScope.FULL);
        clearSaveFailure();
        clearCheckpointFailure();
        recheckLastGoodCheckpoint();
    }

    /**
     * The open project's checkpoint generation ended (story 331, rulings K1,
     * N2): the countdown is cleared — the strip shows "No checkpoint
     * scheduled" until a running generation arms its first tick — and the
     * last good checkpoint is rechecked: cleared unless the checkpoint
     * generation that seeded or wrote it still runs (ruling K1). A replacement
     * that ends the generation calls it ({@link #resetForProject}), and so
     * does the lifecycle controller when it abandons the open project, which
     * a failed open leaves with no replacement. The head state, the last save
     * time and a standing checkpoint failure are left as they are (ruling
     * G4 (d)). Applied at once: <strong>FX thread only</strong>.
     */
    public void checkpointsEnded() {
        nextCheckpointInstant.set(null);
        recheckLastGoodCheckpoint();
    }

    /**
     * Clears the last good checkpoint once the checkpoint generation that
     * seeded or wrote it no longer runs (ruling K1: it is shown only while
     * that generation runs). It is asked when the generation ends
     * ({@link #checkpointsEnded()}), which the lifecycle controller's abandon
     * of the open project and every replacement that ends it call, and by a
     * snapshot restore, which keeps it running. Applied at once:
     * <strong>FX thread only</strong>.
     */
    private void recheckLastGoodCheckpoint() {
        if (!lastCheckpointRuns.getAsBoolean()) {
            setLastGoodCheckpoint(null, null, () -> false);
        }
    }

    /**
     * Records a head save of generation {@code generation} that wrote
     * {@code project.daw}: stamps {@link #lastSaveTimeProperty() time} and
     * {@link #lastSaveScopeProperty() scope}, clears a standing head failure
     * ({@link #lastSaveSucceededProperty()} back to {@code true}) and counts
     * the success ({@link #savesSucceededProperty()}, which the Saved cell's
     * one-shot flash follows). Each of the three is a fact of its own key, so
     * a failure posted in the same frame keeps this time and count. A
     * checkpoint failure is left standing (ruling G4 (d)).
     *
     * @param generation the generation the save was requested under
     * @param time       when {@code project.daw} was captured; must not be {@code null}
     * @param scope      full vs. delta; must not be {@code null}
     */
    public void recordSaveSucceeded(long generation, Instant time, SaveScope scope) {
        Objects.requireNonNull(time, "time must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        publish(Key.SAVE_TIME, generation, () -> {
            lastSaveTime.set(time);
            lastSaveScope.set(scope);
        });
        publish(Key.SAVE_STATE, generation, this::clearSaveFailure);
        publish(Key.SAVE_SUCCESS, generation, () -> savesSucceeded.set(savesSucceeded.get() + 1));
    }

    /**
     * Records a head save of generation {@code generation} that left
     * {@code project.daw} as it was: {@link #lastSaveSucceededProperty()}
     * turns {@code false} with the {@link #saveFailureCauseProperty() cause}
     * and {@link #saveFailureTimeProperty() time}, and stays so until a head
     * save of the same project succeeds or the project is replaced (§4.5:
     * failures persist). The last successful {@code lastSaveTime} is kept.
     * A checkpoint success never clears it.
     *
     * @param generation the generation the save was requested under
     * @param time       when the failure reached the application; must not be {@code null}
     * @param cause      why the save failed; must not be {@code null}
     */
    public void recordSaveFailed(long generation, Instant time, Throwable cause) {
        Objects.requireNonNull(time, "time must not be null");
        Objects.requireNonNull(cause, "cause must not be null");
        publish(Key.SAVE_STATE, generation, () -> {
            lastSaveSucceeded.set(false);
            saveFailureCause.set(cause);
            saveFailureTime.set(time);
        });
    }

    /**
     * Records a checkpoint of generation {@code generation} that was written
     * (any origin: a tick, after a save, Create Checkpoint, a safety copy):
     * clears a standing checkpoint failure and makes it the last good
     * checkpoint — its {@link #lastCheckpointFileProperty() file} and
     * {@link #lastCheckpointTimeProperty() time} — while {@code stillRunning},
     * asked on the FX thread when its frame applies it, answers {@code true}
     * (ruling K1). The two are facts of their own keys, so a failure posted in
     * the same frame keeps this file and time. The head state, the last save
     * time included, is left alone: a checkpoint is not a save of
     * {@code project.daw} (ruling G4 (c)).
     *
     * @param generation   the generation the checkpoint was reported under
     * @param time         the time its name carries (ruling K1), or {@code null} when it carries none
     * @param file         the checkpoint file; must not be {@code null}
     * @param stillRunning whether the checkpoint generation that wrote it still runs; must not be {@code null}
     */
    public void recordCheckpointWritten(long generation, Instant time, Path file, BooleanSupplier stillRunning) {
        Objects.requireNonNull(file, "file must not be null");
        Objects.requireNonNull(stillRunning, "stillRunning must not be null");
        publish(Key.CHECKPOINT_STATE, generation, this::clearCheckpointFailure);
        publishLastGoodCheckpoint(time, file, stillRunning);
    }

    /**
     * Records what the project's checkpoints found on disk as they were
     * seeded (ruling K1): until they write one, the newest checkpoint there is
     * the last good checkpoint, while {@code stillRunning}, asked on the FX
     * thread when its frame applies it, answers {@code true}. With none on
     * disk — or a folder that could not be read — there is no last good
     * checkpoint. It belongs to the checkpoints, not to a project generation:
     * a project replacement that keeps them running keeps it.
     *
     * @param time         the time the newest checkpoint's name carries, or {@code null} for none
     * @param newest       the newest checkpoint, or {@code null} when there was none
     * @param stillRunning whether the checkpoint generation that seeded it still runs; must not be {@code null}
     */
    public void recordCheckpointsSeeded(Instant time, Path newest, BooleanSupplier stillRunning) {
        Objects.requireNonNull(stillRunning, "stillRunning must not be null");
        publishLastGoodCheckpoint(newest == null ? null : time, newest, stillRunning);
    }

    /**
     * Records a checkpoint of generation {@code generation} that wrote
     * nothing: {@link #lastCheckpointSucceededProperty()} turns {@code false}
     * with the {@link #checkpointFailureCauseProperty() cause} and
     * {@link #checkpointFailureTimeProperty() time}, until a checkpoint of the
     * same project is written or the project is replaced. A head save success
     * never clears it (ruling G4 (d)). It sets only those three facts of its
     * key: the last good checkpoint, a fact of its own key, is left as it is
     * — it is cleared once its checkpoint generation has ended, by the
     * {@link #checkpointsEnded()} of the abandon or replacement that ends it,
     * not by a failure.
     *
     * @param generation the generation the checkpoint was reported under
     * @param time       when it was reported; must not be {@code null}
     * @param cause      why it failed; must not be {@code null}
     */
    public void recordCheckpointFailed(long generation, Instant time, Throwable cause) {
        Objects.requireNonNull(time, "time must not be null");
        Objects.requireNonNull(cause, "cause must not be null");
        publish(Key.CHECKPOINT_STATE, generation, () -> {
            lastCheckpointSucceeded.set(false);
            checkpointFailureCause.set(cause);
            checkpointFailureTime.set(time);
        });
    }

    /** The head's failure state cleared. FX thread. */
    private void clearSaveFailure() {
        lastSaveSucceeded.set(true);
        saveFailureCause.set(null);
        saveFailureTime.set(null);
    }

    /** The checkpoint failure state cleared. FX thread. */
    private void clearCheckpointFailure() {
        lastCheckpointSucceeded.set(true);
        checkpointFailureCause.set(null);
        checkpointFailureTime.set(null);
    }

    /**
     * Routes the last good checkpoint onto the FX thread under its own key:
     * posted only while its checkpoint generation runs, applied only if it
     * still does when its frame runs. Not a project generation's fact (ruling
     * K1), so a project replacement between the post and the frame does not
     * drop it; a newer post — always of the checkpoints running then —
     * replaces it.
     */
    private void publishLastGoodCheckpoint(Instant time, Path file, BooleanSupplier stillRunning) {
        if (!stillRunning.getAsBoolean()) {
            return;
        }
        dispatcher.onFx(Key.CHECKPOINT_LAST_GOOD, () -> {
            if (stillRunning.getAsBoolean()) {
                setLastGoodCheckpoint(time, file, stillRunning);
            }
        });
    }

    /** Sets the last good checkpoint and the predicate of its checkpoint generation. FX thread. */
    private void setLastGoodCheckpoint(Instant time, Path file, BooleanSupplier runs) {
        lastCheckpointTime.set(time);
        lastCheckpointFile.set(file);
        lastCheckpointRuns = runs;
    }

    /**
     * Re-arms the countdown of generation {@code generation} after the
     * checkpoints' cadence armed its next tick (ruling G13 #2): on the FX
     * thread, the countdown runs to {@code nextDue} while {@code stillRunning}
     * — asked there, when the frame applies it — answers {@code true}, and is
     * cleared otherwise: a re-arm can trail the disabling of autosave or the
     * end of the project's checkpoints (ruling H3).
     *
     * @param generation   the generation the re-arm was reported under
     * @param nextDue      when the next tick is due; must not be {@code null}
     * @param interval     the autosave interval; must not be {@code null}
     * @param stillRunning whether the automatic checkpoints still run, asked on the FX thread
     */
    public void rearmCheckpointCountdown(long generation, Instant nextDue, Duration interval,
                                         BooleanSupplier stillRunning) {
        Objects.requireNonNull(nextDue, "nextDue must not be null");
        Objects.requireNonNull(interval, "interval must not be null");
        Objects.requireNonNull(stillRunning, "stillRunning must not be null");
        publish(Key.CHECKPOINT, generation, () -> {
            nextCheckpointInstant.set(stillRunning.getAsBoolean() ? nextDue : null);
            checkpointInterval.set(interval);
        });
    }

    /**
     * Publishes the number of journal events queued since the last checkpoint
     * (the §8 replacement for the single dirty dot), under its own key: a
     * {@linkplain #setJournalBackpressure back-pressure} change posted in the
     * same frame keeps it (story 331, one key per fact).
     *
     * @param count the queued-event count
     */
    public void setJournalEventsQueued(int count) {
        publish(Key.JOURNAL_QUEUED, () -> journalEventsQueued.set(count));
    }

    /**
     * Publishes the §6.3 write-ahead-journal back-pressure level (story 298).
     * Sourced off the FX thread from the journal writer's virtual-thread
     * {@code JournalListener.onBackpressure(...)} callback; the strip's Journal
     * cell goes amber on {@link JournalBackpressure#SLOW} and red on
     * {@link JournalBackpressure#UNRESPONSIVE}. Travels under its own
     * {@link Key#JOURNAL_BACKPRESSURE} coalescing key, apart from the queued
     * count's, so a count posted in the same frame keeps it (story 331, one
     * key per fact). A {@code null} is coerced to
     * {@link JournalBackpressure#NORMAL} to keep the cell well-formed.
     *
     * @param level the back-pressure level; a {@code null} becomes
     *              {@link JournalBackpressure#NORMAL}
     */
    public void setJournalBackpressure(JournalBackpressure level) {
        JournalBackpressure resolved = level == null ? JournalBackpressure.NORMAL : level;
        publish(Key.JOURNAL_BACKPRESSURE, () -> journalBackpressure.set(resolved));
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
     * Publishes today's date (story 331, ruling L1): the strip's 5&nbsp;s poll
     * sends the system clock's date in the JVM's default time zone on every
     * tick, so within a poll of local midnight — or of a wake from sleep or a
     * clock step — a time of the day before carries its date. Posted under
     * its own key, and only when it is not {@code equals} to the date posted
     * last; applied only when it is not {@code equals} to the date shown.
     *
     * @param date today's date; must not be {@code null}
     */
    public void setToday(LocalDate date) {
        Objects.requireNonNull(date, "date must not be null");
        if (date.equals(todayPosted)) {
            return;
        }
        todayPosted = date;
        publish(Key.TODAY, () -> {
            if (!date.equals(today.get())) {
                today.set(date);
            }
        });
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
     * Replaces a registered operation with {@code replacement} in place, so
     * an operation whose name carries its progress ("Loading audio — 3 of
     * 40", story 329) keeps its slot in the strip. Nothing happens when
     * {@code current} is no longer registered — it finished or was removed
     * meanwhile. Discrete — routed un-keyed, after every earlier add and
     * remove.
     *
     * @param current     the operation shown now; must not be {@code null}
     * @param replacement the operation to show instead; must not be {@code null}
     */
    public void replaceOperation(NamedOperation current, NamedOperation replacement) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(replacement, "replacement must not be null");
        dispatcher.onFx(() -> {
            int index = operationsBacking.indexOf(current);
            if (index >= 0) {
                operationsBacking.set(index, replacement);
            }
        });
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

    /**
     * Routes a write of generation {@code generation} onto the FX thread under
     * that generation's own key: posted only while it is the open project's,
     * and applied only if it still is when its frame runs. A stale write is
     * never posted, never replaces the open generation's pending write, and
     * runs as nothing.
     */
    private void publish(Key key, long generation, Runnable write) {
        if (generation != projectGeneration) {
            return;
        }
        dispatcher.onFx(new GenerationKey(key, generation), () -> {
            if (generation == projectGeneration) {
                write.run();
            }
        });
    }

    // ── Read-only property views (the model is the sole writer, §2.4) ─────────

    /**
     * @return when {@code project.daw} last received a save — the last head save
     *         that succeeded, or the open project's {@code lastModified} as it
     *         was opened — or {@code null} if it was never saved; a checkpoint
     *         never moves it
     */
    public ReadOnlyObjectProperty<Instant> lastSaveTimeProperty() { return lastSaveTime.getReadOnlyProperty(); }
    /** @return the last save time, or {@code null}. */
    public Instant getLastSaveTime() { return lastSaveTime.get(); }

    /** @return the last save's {@link SaveScope} (full vs. delta). */
    public ReadOnlyObjectProperty<SaveScope> lastSaveScopeProperty() { return lastSaveScope.getReadOnlyProperty(); }
    /** @return the last save scope. */
    public SaveScope getLastSaveScope() { return lastSaveScope.get(); }

    /** @return whether no head save failure stands ({@code false} → Saved cell error state). */
    public ReadOnlyBooleanProperty lastSaveSucceededProperty() { return lastSaveSucceeded.getReadOnlyProperty(); }
    /** @return whether no head save failure stands. */
    public boolean isLastSaveSucceeded() { return lastSaveSucceeded.get(); }

    /** @return the cause of the standing head save failure, or {@code null} when none stands. */
    public ReadOnlyObjectProperty<Throwable> saveFailureCauseProperty() { return saveFailureCause.getReadOnlyProperty(); }
    /** @return the cause of the standing head save failure, or {@code null}. */
    public Throwable getSaveFailureCause() { return saveFailureCause.get(); }

    /** @return when the standing head save failure was reported, or {@code null} when none stands. */
    public ReadOnlyObjectProperty<Instant> saveFailureTimeProperty() { return saveFailureTime.getReadOnlyProperty(); }
    /** @return when the standing head save failure was reported, or {@code null}. */
    public Instant getSaveFailureTime() { return saveFailureTime.get(); }

    /**
     * The head saves' success signal the Saved cell flashes on (ruling G4
     * (c)). Its contract: it advances by one in each frame — each
     * {@link FxDispatcher#pulse() pulse} — in which at least one head save of
     * the open project succeeded, and changes at no other time: not on a
     * failed save, a checkpoint or a project replacement. Two successes in
     * one frame advance it once, so it is not an exact count of saves; a
     * success and a failure in one frame still advance it. The Saved cell
     * flashes once, for 600&nbsp;ms, on each change while its window shows
     * and Reduce Motion is off.
     *
     * @return the number of frames in which a head save of the open project succeeded
     */
    public ReadOnlyLongProperty savesSucceededProperty() { return savesSucceeded.getReadOnlyProperty(); }
    /** @return the number of frames in which a head save of the open project succeeded. */
    public long getSavesSucceeded() { return savesSucceeded.get(); }

    /** @return whether no checkpoint failure stands ({@code false} → the Saved cell's warning state). */
    public ReadOnlyBooleanProperty lastCheckpointSucceededProperty() { return lastCheckpointSucceeded.getReadOnlyProperty(); }
    /** @return whether no checkpoint failure stands. */
    public boolean isLastCheckpointSucceeded() { return lastCheckpointSucceeded.get(); }

    /** @return the cause of the standing checkpoint failure, or {@code null} when none stands. */
    public ReadOnlyObjectProperty<Throwable> checkpointFailureCauseProperty() { return checkpointFailureCause.getReadOnlyProperty(); }
    /** @return the cause of the standing checkpoint failure, or {@code null}. */
    public Throwable getCheckpointFailureCause() { return checkpointFailureCause.get(); }

    /** @return when the standing checkpoint failure was reported, or {@code null} when none stands. */
    public ReadOnlyObjectProperty<Instant> checkpointFailureTimeProperty() { return checkpointFailureTime.getReadOnlyProperty(); }
    /** @return when the standing checkpoint failure was reported, or {@code null}. */
    public Instant getCheckpointFailureTime() { return checkpointFailureTime.get(); }

    /**
     * @return the time the last good checkpoint's name carries — the newest
     *         checkpoint on disk as the open project's checkpoints were
     *         seeded, then the last one they wrote (ruling K1) — or
     *         {@code null} when there is none: never the file's time, nor
     *         when the fact arrived
     */
    public ReadOnlyObjectProperty<Instant> lastCheckpointTimeProperty() { return lastCheckpointTime.getReadOnlyProperty(); }
    /** @return the time the last good checkpoint's name carries, or {@code null}. */
    public Instant getLastCheckpointTime() { return lastCheckpointTime.get(); }

    /**
     * @return the last good checkpoint file of the open project's checkpoints
     *         (ruling K1, see {@link #lastCheckpointTimeProperty()}), or
     *         {@code null} when there is none
     */
    public ReadOnlyObjectProperty<Path> lastCheckpointFileProperty() { return lastCheckpointFile.getReadOnlyProperty(); }
    /** @return the last good checkpoint file, or {@code null}. */
    public Path getLastCheckpointFile() { return lastCheckpointFile.get(); }

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

    /**
     * @return today's date in the JVM's default time zone (ruling L1): a time
     *         the strip shows carries its date when it is of another day
     */
    public ReadOnlyObjectProperty<LocalDate> todayProperty() { return today.getReadOnlyProperty(); }
    /** @return today's date. */
    public LocalDate getToday() { return today.get(); }

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
     * A named, in-flight project operation (save, archive, restore, …) with an
     * observable progress in {@code [0,1]} or {@code -1} for indeterminate.
     * The Saved cell shows the operation's name ({@code "◐ <name>"}); it draws
     * no progress bar (§4.5's slim bar is not built yet). The slow worker
     * updates {@link #progressProperty()} as it runs.
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
