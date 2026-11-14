package com.benesquivelmusic.daw.app.logging;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * The application's {@link LogManager}: it holds the JDK's shutdown reset of
 * the logging handlers until the exit wait has returned (story 336, Goals
 * bullet 9; story 331, ruling N1).
 *
 * <p>{@code LogManager}'s own shutdown hook — its private {@code Cleaner}
 * thread ({@code LogManager.java:251}) — sets the state to shutdown and calls
 * {@link #reset()}, which removes and closes every handler, while the other
 * shutdown hooks run alongside it. So a line another hook logs reaches no
 * handler: {@code ProjectManager.awaitWritesBeforeExit}'s INFO, its deadline
 * WARNING and its interrupted WARNING, the records of writers that fail
 * during the wait, and the other pending writers' lines. This manager holds
 * <em>only</em> that shutdown-path reset — an explicit {@link #reset()} or
 * {@code readConfiguration()} resets at once — while the {@code daw-exit-write-wait}
 * hook {@linkplain #holdShutdownResetFor registered with it} runs, at most the
 * exit wait's limit plus {@link #HOLD_MARGIN}; then the default
 * uncaught-exception handler writes the lines it still owes the log, if it
 * {@linkplain OwedLines owes any}, this manager writes its closing line, and
 * it resets. With no exit wait registered it skips the hold, then writes the
 * owed lines and the closing line, and resets. The hold adds no exit latency:
 * the JVM already waits for every shutdown hook.</p>
 *
 * <p><strong>How it is selected.</strong> {@code LogManager}'s static
 * initializer reads the system property {@code java.util.logging.manager},
 * loads the class it names with the system class loader and instantiates it
 * reflectively from the {@code java.logging} module. That module can
 * instantiate a class of {@code daw.app} only if the class is public, has a
 * public no-argument constructor, and sits in a package {@code daw.app}
 * exports to it — here a qualified export, to {@code java.logging} alone.
 * That rule is the module system's own. How it is tested follows
 * dawg-annotations-reflection "### Adding a new reflection consumer": since
 * surefire's {@code --add-reads} hides a missing read from every test in its
 * JVM, "test it in a JVM of its own". Surefire's added module options would
 * hide a missing export the same way, so child-JVM tests launch the
 * application as the packaged launcher does and see this class selected.
 * Every launch path names it, and {@code DawLauncher.main} names it first
 * thing when the launch path did not.</p>
 *
 * <p><strong>Class initialization.</strong> It is instantiated inside
 * {@code LogManager}'s static initializer, where {@code LogManager.getLogManager()}
 * is not yet usable, so neither this class nor its constructor touches
 * {@code java.util.logging}, and it declares no static logger: the closing
 * lines' logger is looked up only at the shutdown reset, long after
 * {@code LogManager} was initialized.</p>
 */
public final class ExitHoldingLogManager extends LogManager {

    /** How much longer than the exit wait's own limit the log is held. */
    public static final Duration HOLD_MARGIN = Duration.ofSeconds(5);

    /** The binary name of the JDK's private shutdown hook thread class ({@code LogManager.java:251}). */
    static final String CLEANER_CLASS_NAME = "java.util.logging.LogManager$Cleaner";

    /** The INFO that ends every session the hold released, or that had no exit wait. */
    static final String CLOSED_LINE = "Log closed at application exit.";

    /** The registered exit wait, if any. */
    private static volatile Hold registered;

    /**
     * Instantiated reflectively by {@code java.util.logging.LogManager} when
     * {@code java.util.logging.manager} names this class. Touches nothing:
     * {@code LogManager}'s own constructor registers its shutdown hook.
     */
    public ExitHoldingLogManager() {
    }

    /**
     * Registers the exit-wait hook: the shutdown reset of the logging
     * handlers is held until {@code exitWait} returns, at most
     * {@code exitWaitLimit} plus {@link #HOLD_MARGIN}. A later registration
     * replaces an earlier one.
     *
     * @param exitWait      the shutdown hook thread that waits for the writes
     * @param exitWaitLimit how long that hook waits at most
     */
    public static void holdShutdownResetFor(Thread exitWait, Duration exitWaitLimit) {
        Objects.requireNonNull(exitWait, "exitWait must not be null");
        Objects.requireNonNull(exitWaitLimit, "exitWaitLimit must not be null");
        registered = new Hold(exitWait, exitWaitLimit.plus(HOLD_MARGIN));
    }

    /**
     * Test seam only, never called by the application: puts the registration
     * back as a test found it, none included, so that a test which registers
     * an exit wait in the shared surefire JVM leaves no hold behind.
     *
     * @param hold the registration to restore, or none
     */
    static void restoreRegisteredHold(Optional<Hold> hold) {
        registered = Objects.requireNonNull(hold, "hold must not be null").orElse(null);
    }

    /**
     * The registered exit wait, if any.
     *
     * @return the exit-wait hook and how long the reset is held for it at most
     */
    public static Optional<Hold> registeredHold() {
        return Optional.ofNullable(registered);
    }

    /**
     * Whether the process's {@code LogManager} is this class — whether the
     * launch path named it before anything initialized logging.
     *
     * @return whether the exit hold is active
     */
    public static boolean isActive() {
        return LogManager.getLogManager() instanceof ExitHoldingLogManager;
    }

    /**
     * Resets at once, unless this is the JDK's shutdown reset: then it waits
     * for the registered exit wait, has the default handler write the lines
     * it still owes, writes the closing line, and always resets in the end.
     */
    @Override
    public void reset() {
        if (!isTheShutdownCleaner(Thread.currentThread())) {
            super.reset();
            return;
        }
        try {
            ExitWait outcome = awaitExitWait(registeredHold(), System::nanoTime);
            writeOwedLines();
            writeClosingLine(outcome);
        } catch (Throwable unexpected) {
            // The reset below must still run: nothing here may keep the handlers open.
        } finally {
            super.reset();
        }
    }

    /**
     * The hold itself, factored out so it can be tested without an instance
     * (constructing one registers a second JDK shutdown hook). Waits while the
     * hook has not started yet — the shutdown hooks start concurrently in no
     * fixed order, and {@code Thread.join(Duration)} on a thread that has not
     * started throws {@link IllegalThreadStateException}, so it must not be
     * called before the hook has left {@link Thread.State#NEW} — then joins
     * it, all within the hold from now.
     *
     * @param hold  the registered exit wait, if any
     * @param nanos the monotonic clock the hold is measured on
     * @return {@link ExitWait.NotRegistered} with none; {@link ExitWait.Released} once the hook has
     *         returned; {@link ExitWait.Expired} when the hold ran out first, the hook still unstarted
     *         or still running; {@link ExitWait.Interrupted} when this thread was interrupted, with its
     *         interrupt status restored
     */
    static ExitWait awaitExitWait(Optional<Hold> hold, LongSupplier nanos) {
        if (hold.isEmpty()) {
            return new ExitWait.NotRegistered();
        }
        Thread hook = hold.get().exitWait();
        Duration bound = hold.get().hold();
        long deadline = nanos.getAsLong() + bound.toNanos();
        try {
            while (hook.getState() == Thread.State.NEW) {
                if (nanos.getAsLong() - deadline >= 0) {
                    return new ExitWait.Expired(bound);
                }
                Thread.sleep(1);
            }
            long remaining = deadline - nanos.getAsLong();
            if (remaining > 0 && hook.join(Duration.ofNanos(remaining))) {
                return new ExitWait.Released();
            }
            return hook.getState() == Thread.State.TERMINATED
                    ? new ExitWait.Released()
                    : new ExitWait.Expired(bound);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new ExitWait.Interrupted();
        }
    }

    private static boolean isTheShutdownCleaner(Thread thread) {
        return CLEANER_CLASS_NAME.equals(thread.getClass().getName());
    }

    /**
     * Asks the process's default uncaught-exception handler, when it
     * {@linkplain OwedLines owes lines}, to write them now — after the hold,
     * so that what failed during the exit wait is counted, and before the
     * closing line. The default handler is the registry: nothing is
     * registered here. Never throws.
     */
    private static void writeOwedLines() {
        try {
            if (Thread.getDefaultUncaughtExceptionHandler() instanceof OwedLines owed) {
                owed.writeOwedLines();
            }
        } catch (Throwable unwritable) {
            // The closing line and the reset still run.
        }
    }

    /**
     * The closing line of each outcome — none when the wait was
     * interrupted — on a logger looked up now, at the shutdown reset. Never
     * throws.
     */
    private static void writeClosingLine(ExitWait outcome) {
        try {
            switch (outcome) {
                case ExitWait.Released _, ExitWait.NotRegistered _ -> closingLog().info(CLOSED_LINE);
                case ExitWait.Expired expired -> closingLog().log(Level.WARNING, expiredLine(expired.hold()));
                case ExitWait.Interrupted _ -> {
                    // The interrupt status is restored; the reset follows without a line.
                }
            }
        } catch (Throwable unloggable) {
            // The reset still runs.
        }
    }

    private static Logger closingLog() {
        return Logger.getLogger(ExitHoldingLogManager.class.getName());
    }

    /** The WARNING when the hold ran out, {@code hold} in {@code ProjectManager}'s bound style. */
    static String expiredLine(Duration hold) {
        String shown = hold.toMillis() % 1_000L == 0L ? hold.toSeconds() + " s" : hold.toMillis() + " ms";
        return "The log is closing " + shown + " after the application began to exit, and the wait for saves and"
                + " checkpoints has not returned; anything it logs after this line is lost.";
    }

    /**
     * The registered exit wait.
     *
     * @param exitWait the shutdown hook thread that waits for the writes
     * @param hold     how long the reset is held for it at most: its limit plus {@link #HOLD_MARGIN}
     */
    public record Hold(Thread exitWait, Duration hold) {
        public Hold {
            Objects.requireNonNull(exitWait, "exitWait must not be null");
            Objects.requireNonNull(hold, "hold must not be null");
        }
    }

    /**
     * What the process's default uncaught-exception handler may also be:
     * the writer of lines it still owes the log, which only it can write
     * once nothing more can be added to them — the failure funnel's tails of
     * occurrences counted during the exit wait (story 336). The shutdown
     * reset calls it after the hold and before the closing line, on the JDK's
     * {@code Logging-Cleaner} thread, with every handler still open.
     */
    public interface OwedLines {

        /**
         * Writes every line still owed to the log, and must never throw. It
         * runs after the hold, so it extends the reset past the hold by the
         * time its writes take; it must not wait for another thread to finish
         * its work, so that time is bounded by the lines it writes and the
         * locks they take, not by any other thread's progress.
         *
         * <p>The failure funnel's default handler, the one implementation,
         * writes at most one line per live fingerprint
         * ({@code FingerprintRateLimiter.MAX_FINGERPRINTS}, 256). The lock
         * order that matters, not a list of every lock it may take: the rate
         * limiter's monitor, to take the owed tails, is released before
         * anything is written; each root handler's own lock is taken as each
         * line is published to it; and only when a write to the log file
         * fails, the sink's error-reporting monitors and then the funnel's
         * {@code armLock} — after the file handler's lock when a flush or
         * rotation failed under it, never before it. Under the sink's
         * error-manager monitor that failure is also printed to
         * {@code System.err}, which takes that stream's lock; and when the
         * funnel's toasts are still armed, because
         * {@code DawApplication.stop()} never ran, its notice is posted as a
         * toast through the application's {@code FxDispatcher}, whose
         * {@code Platform.runLater} takes the toolkit's own locks. So it
         * extends the reset past the hold by at most one line write per live
         * fingerprint.</p>
         */
        void writeOwedLines();
    }

    /** How the hold ended. */
    sealed interface ExitWait permits ExitWait.Released, ExitWait.Expired, ExitWait.NotRegistered,
            ExitWait.Interrupted {

        /** The exit wait returned within the hold. */
        record Released() implements ExitWait {
        }

        /**
         * The hold ran out first.
         *
         * @param hold the hold that ran out
         */
        record Expired(Duration hold) implements ExitWait {
            public Expired {
                Objects.requireNonNull(hold, "hold must not be null");
            }
        }

        /** No exit wait was registered. */
        record NotRegistered() implements ExitWait {
        }

        /** The waiting thread was interrupted. */
        record Interrupted() implements ExitWait {
        }
    }
}
