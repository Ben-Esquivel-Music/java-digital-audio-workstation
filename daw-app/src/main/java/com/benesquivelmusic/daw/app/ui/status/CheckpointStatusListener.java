package com.benesquivelmusic.daw.app.ui.status;

import com.benesquivelmusic.daw.app.ui.NotificationBar;
import com.benesquivelmusic.daw.app.ui.NotificationLevel;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.persistence.CheckpointEvent;
import com.benesquivelmusic.daw.core.persistence.CheckpointFileName;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.CheckpointOrigin;

import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Turns the checkpoint manager's {@link CheckpointEvent}s into the session
 * status strip's checkpoint state and the autosave failure warning (story
 * 331; Persistence Integrity book §4.2, §6.2; rulings F6, G4, G13 #2, #5, #9,
 * H3, K1). Registered with {@link CheckpointManager#addEventListener}; the
 * removal token that call returns detaches it.
 *
 * <ul>
 *   <li>What the checkpoints' {@link CheckpointEvent.Seeded seeding} found is
 *       the last good checkpoint until they write one: the newest checkpoint
 *       already on disk, or none (ruling K1).</li>
 *   <li>A checkpoint {@link CheckpointEvent.Written written}, of any origin,
 *       clears the checkpoint failure state and becomes the last good
 *       checkpoint; it ends an autosave failure streak. It never touches the
 *       head state: a checkpoint is not a save of {@code project.daw}.</li>
 *   <li>A checkpoint that {@link CheckpointEvent.Failed failed}, of any
 *       origin, sets the checkpoint failure state. One of the automatic
 *       checkpoints — a scheduled tick, or the checkpoint after a save
 *       (ruling G13 #5) — also counts toward the streak: the second in a row
 *       raises one WARNING naming the cause and the last good checkpoint's
 *       time, and later ones count quietly (ruling F6). A user's Create
 *       Checkpoint, a failed save's safety copy or the exit's closing
 *       checkpoint (story 333) does not count; Create
 *       Checkpoint reports its own failure every time
 *       ({@link #userCheckpointRequested()}).</li>
 *   <li>Every {@link CheckpointEvent.Rearmed re-arm} re-arms the strip's
 *       countdown, which runs only while {@link CheckpointManager#isRunning()},
 *       asked on the FX thread when the frame applies it, answers
 *       {@code true} (ruling H3: a re-arm can trail the disabling of autosave
 *       or the end of the checkpoints). A re-arm is the countdown's only
 *       writer; a snapshot restore, which keeps the checkpoints running,
 *       posts the last one again ({@link #snapshotRestored()}, ruling M4).</li>
 * </ul>
 *
 * <p>The time of a checkpoint, seeded or written, is the stamp in its name,
 * read in the listener's zone, the one it was written in
 * ({@link CheckpointFileName#instantIn}): never the file's time, nor when the
 * fact arrived (ruling K1).</p>
 *
 * <p>Threading: facts arrive on the checkpoint writer (seeding and outcomes)
 * or the checkpoint scheduler (re-arms); a checkpoint's name is read there.
 * Each fact then goes to the FX thread through the {@link FxDispatcher}'s
 * un-keyed post, in the order it arrived, and is decided there, in one
 * runnable: it applies only if the checkpoint generation that reported it
 * ({@link CheckpointEvent#generation()}) is the one running at that moment
 * ({@link CheckpointManager#runningGeneration()}), and then to the strip
 * model's {@link ProjectOperationProgress#projectGeneration() project
 * generation} read in that same runnable, through its keyed,
 * generation-fenced mutators. A project switch ends the checkpoint generation
 * of the project it replaces on the FX thread, so nothing a replaced
 * project's checkpoints report — however late it is delivered — changes the
 * project that replaced it, and a fact of the running checkpoints is never
 * lost for arriving before the strip learned of the switch (ruling G13 #4).
 * The streak is counted per project generation, so a replacement — a
 * snapshot restore included — starts it over; the last good checkpoint
 * belongs to the checkpoint generation, which a snapshot restore keeps
 * running. Every state of this listener is the FX thread's.</p>
 */
public final class CheckpointStatusListener implements Consumer<CheckpointEvent> {

    private static final Logger LOG = Logger.getLogger(CheckpointStatusListener.class.getName());

    /** The consecutive automatic checkpoint failure that raises the one warning (ruling F6). */
    static final int FAILURES_THAT_WARN = 2;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter DAY_AND_CLOCK =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private final ProjectOperationProgress progress;
    private final CheckpointManager checkpoints;
    private final NotificationBar notifications;
    private final FxDispatcher dispatcher;
    private final InstantSource clock;
    private final ZoneId zone;

    /** The project generation the streak belongs to. FX thread. */
    private long streakGeneration = -1L;
    /** Consecutive failed automatic checkpoints of {@link #streakGeneration}. FX thread. */
    private int failedInARow;
    /** The last good checkpoint the running checkpoints reported, or {@code null} before any. FX thread. */
    private LastGood lastGood;
    /**
     * The last re-arm this listener applied to the countdown, or {@code null}
     * before any (ruling M4): a snapshot restore posts it again. FX thread.
     */
    private CheckpointEvent.Rearmed lastRearmed;

    /**
     * The last good checkpoint of a checkpoint generation (ruling K1).
     *
     * @param generation the checkpoint generation that seeded or wrote it
     * @param time       the time its name carries, or {@code null} when there is none
     */
    private record LastGood(long generation, Instant time) { }

    /**
     * Creates the listener.
     *
     * @param progress      the strip's model; must not be {@code null}
     * @param checkpoints   the checkpoint manager whose running generation and {@code isRunning()} gate every
     *                      fact; must not be {@code null}
     * @param notifications the notification bar the warnings go to (FX thread); must not be {@code null}
     * @param dispatcher    the FX-thread seam every fact and warning is posted through, or {@code null} for the
     *                      app-scoped default
     * @param clock         the time of each failure; must not be {@code null}
     * @param zone          the zone checkpoint names are read in and the warning's times written in (the
     *                      application's default zone); must not be {@code null}
     */
    public CheckpointStatusListener(ProjectOperationProgress progress, CheckpointManager checkpoints,
                                    NotificationBar notifications, FxDispatcher dispatcher, InstantSource clock,
                                    ZoneId zone) {
        this.progress = Objects.requireNonNull(progress, "progress must not be null");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints must not be null");
        this.notifications = Objects.requireNonNull(notifications, "notifications must not be null");
        this.dispatcher = dispatcher;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.zone = Objects.requireNonNull(zone, "zone must not be null");
    }

    /**
     * Takes one checkpoint fact, on the thread the manager reports it on, and
     * posts it to the FX thread, where it is decided (see the class note).
     * Nothing here blocks.
     *
     * @param event the fact
     */
    @Override
    public void accept(CheckpointEvent event) {
        switch (event) {
            case CheckpointEvent.Seeded seeded -> {
                Instant newest = seeded.newest().flatMap(this::stampOf).orElse(null);
                onFx(() -> seeded(seeded, newest));
            }
            case CheckpointEvent.Written written -> {
                Instant stamp = stampOf(written.file()).orElse(null);
                onFx(() -> written(written, stamp));
            }
            case CheckpointEvent.Failed failed -> {
                Instant at = clock.instant();
                onFx(() -> failed(failed, at));
            }
            case CheckpointEvent.Rearmed rearmed -> onFx(() -> rearmed(rearmed));
        }
    }

    /**
     * A user asked for a checkpoint (Create Checkpoint): returns what reports
     * it if it writes nothing (rulings G8, G13 #9), bound to the project open
     * now. Call it on the FX thread, when the user asks; the returned
     * reporter may be called from any thread. It sets that project's
     * checkpoint failure state and shows an ERROR notification naming the
     * cause, every time; it does not count toward the autosave streak. When
     * the project has been replaced since — its checkpoints stopped before
     * this one was written — the notification still says so, but the
     * replacing project's strip is not touched. A failure the manager also
     * reported through its {@link CheckpointEvent.Failed} event sets the same
     * state twice, which changes nothing.
     *
     * @return the failure reporter of this request
     */
    public Consumer<Throwable> userCheckpointRequested() {
        long generation = progress.projectGeneration();
        return cause -> {
            Objects.requireNonNull(cause, "cause must not be null");
            progress.recordCheckpointFailed(generation, clock.instant(), cause);
            String message = "Checkpoint failed: " + reasonOf(cause);
            FxDispatcher.runOnFx(dispatcher, () -> notifications.show(NotificationLevel.ERROR, message));
        };
    }

    /**
     * A user's Create Checkpoint was kept in memory only, and nothing was
     * asked of the checkpoints (ruling K2): {@code message}, which says why,
     * is shown as a WARNING, every time. Nothing failed: the checkpoint
     * failure state and the streak are left alone. Callable from any thread.
     *
     * @param message what was kept and why; must not be {@code null}
     */
    public void userCheckpointKeptInMemory(String message) {
        Objects.requireNonNull(message, "message must not be null");
        FxDispatcher.runOnFx(dispatcher, () -> notifications.show(NotificationLevel.WARNING, message));
    }

    private void onFx(Runnable decision) {
        FxDispatcher.runOnFx(dispatcher, decision);
    }

    /** The FX half of a seeding, with its newest checkpoint's time: that checkpoint is the last good one (ruling K1). */
    private void seeded(CheckpointEvent.Seeded seeded, Instant newest) {
        long reporting = seeded.generation();
        if (!isRunning(reporting)) {
            ignore(seeded);
            return;
        }
        lastGood = new LastGood(reporting, newest);
        progress.recordCheckpointsSeeded(newest, seeded.newest().orElse(null), () -> isRunning(reporting));
    }

    /** The FX half of a written checkpoint, with the time its name carries. */
    private void written(CheckpointEvent.Written written, Instant stamp) {
        long reporting = written.generation();
        if (!isRunning(reporting)) {
            ignore(written);
            return;
        }
        long generation = progress.projectGeneration();
        adoptStreak(generation);
        failedInARow = 0;
        lastGood = new LastGood(reporting, stamp);
        progress.recordCheckpointWritten(generation, stamp, written.file(), () -> isRunning(reporting));
    }

    /** The FX half of a failed checkpoint, with the time it arrived. */
    private void failed(CheckpointEvent.Failed failed, Instant at) {
        long reporting = failed.generation();
        if (!isRunning(reporting)) {
            ignore(failed);
            return;
        }
        long generation = progress.projectGeneration();
        adoptStreak(generation);
        boolean warn = countsTowardTheStreak(failed.origin()) && ++failedInARow == FAILURES_THAT_WARN;
        progress.recordCheckpointFailed(generation, at, failed.cause());
        if (warn) {
            Instant lastGoodTime = lastGood != null && lastGood.generation() == reporting ? lastGood.time() : null;
            String message = streakWarning(failed.cause(), lastGoodTime, at);
            LOG.log(Level.WARNING, failed.cause(), () -> message);
            notifications.show(NotificationLevel.WARNING, message);
        }
    }

    /** The FX half of a re-arm. */
    private void rearmed(CheckpointEvent.Rearmed rearmed) {
        if (!isRunning(rearmed.generation())) {
            ignore(rearmed);
            return;
        }
        lastRearmed = rearmed;
        progress.rearmCheckpointCountdown(progress.projectGeneration(), rearmed.nextDue(), rearmed.interval(),
                checkpoints::isRunning);
    }

    /**
     * A snapshot of the open project replaced it, and its checkpoints run on
     * (story 331, ruling M4): in the restore's FX step, after the strip model
     * began the restore's project generation, the last re-arm this listener
     * applied for the running checkpoints is posted again under that
     * generation — so a re-arm whose keyed write was still pending when the
     * generation changed is not lost, and the countdown keeps the armed
     * tick's due time. Nothing is posted when no re-arm of the running
     * checkpoints was applied. <strong>FX thread only.</strong>
     */
    public void snapshotRestored() {
        CheckpointEvent.Rearmed last = lastRearmed;
        if (last == null || !isRunning(last.generation())) {
            return;
        }
        progress.rearmCheckpointCountdown(progress.projectGeneration(), last.nextDue(), last.interval(),
                checkpoints::isRunning);
    }

    /** Whether {@code generation} is the checkpoint generation running now. */
    private boolean isRunning(long generation) {
        return checkpoints.runningGeneration().equals(OptionalLong.of(generation));
    }

    private static void ignore(CheckpointEvent stale) {
        LOG.fine(() -> "A fact of checkpoints that no longer run changes nothing: " + stale);
    }

    /** Starts the streak over when {@code generation} is a new project's. */
    private void adoptStreak(long generation) {
        if (generation != streakGeneration) {
            streakGeneration = generation;
            failedInARow = 0;
        }
    }

    /**
     * The time a checkpoint's name carries, read in this listener's
     * {@code zone} — the zone the application writes checkpoint names and
     * the warning's times in (ruling K1).
     */
    private Optional<Instant> stampOf(Path checkpoint) {
        Path name = checkpoint.getFileName();
        return name == null ? Optional.empty()
                : CheckpointFileName.parse(name.toString()).map(parsed -> parsed.instantIn(zone));
    }

    /**
     * Whether a failed checkpoint of {@code origin} is an autosave failure (rulings F6, G13 #5, #9): the exit's
     * closing checkpoint (story 333, ruling R3) is not one.
     */
    static boolean countsTowardTheStreak(CheckpointOrigin origin) {
        return switch (origin) {
            case SCHEDULED, AFTER_SAVE -> true;
            case USER, SAFETY_COPY, CLOSING -> false;
        };
    }

    /**
     * The one warning of a streak (rulings F6, K1): the cause and the time of
     * the last good checkpoint — the date first when it is not today — or
     * that no earlier checkpoint of this project was found.
     */
    String streakWarning(Throwable cause, Instant lastGoodCheckpoint, Instant now) {
        String base = "Checkpoints are failing: " + reasonOf(cause) + ".";
        if (lastGoodCheckpoint == null) {
            return base + " No earlier checkpoint of this project was found.";
        }
        ZonedDateTime good = lastGoodCheckpoint.atZone(zone);
        LocalDate today = now.atZone(zone).toLocalDate();
        String when = good.toLocalDate().equals(today) ? CLOCK.format(good) : DAY_AND_CLOCK.format(good);
        return base + " The last good checkpoint is from " + when + ".";
    }

    /** A failure's message, or its simple class name when it has none, so the user never reads "null". */
    static String reasonOf(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
