package com.benesquivelmusic.daw.core.audio.health;

import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The rate-bounded log record of an audio failure that a site could not
 * record into a lane (story 337, ruling E77): the site called
 * {@link RenderThread#recordOnCurrentThread} and got {@code false}, so it runs
 * on a thread with no lane — an offline export, bounce or freeze. Never
 * called on a render thread, where the failure is recorded instead.
 *
 * <p>One instance per processor or plugin host, built off the render thread.
 * The first failure is always written. After that at most one record is
 * written per {@link #WINDOW_NANOS} (the failure funnel's 30-second window,
 * story 337, rulings E35 and E44), and each later record states how many
 * failures occurred since the previous record, so a block-rate failure during
 * a long render never floods the log.</p>
 */
public final class OffLaneFailureLog {

    /** The rate bound's window: at most one record per 30 seconds after the first. */
    static final long WINDOW_NANOS = 30_000_000_000L;

    /** Written when the site's own description could not be built. */
    private static final String UNREADABLE_MESSAGE = "An audio failure occurred off a render thread";

    private final Logger logger;
    private final Level level;
    private final LongSupplier clockNanos;

    private boolean written;
    private long lastWrittenNanos;
    private long failuresSinceLastRecord;

    /**
     * Creates a rate-bounded log on the given logger and level.
     *
     * @param logger where each record is written; never {@code null}
     * @param level  the records' level; never {@code null}
     */
    public OffLaneFailureLog(Logger logger, Level level) {
        this(logger, level, System::nanoTime);
    }

    OffLaneFailureLog(Logger logger, Level level, LongSupplier clockNanos) {
        this.logger = Objects.requireNonNull(logger, "logger must not be null");
        this.level = Objects.requireNonNull(level, "level must not be null");
        this.clockNanos = Objects.requireNonNull(clockNanos, "clockNanos must not be null");
    }

    /**
     * Counts one failure and writes it when the rate bound allows. The
     * message is built only for a record that is written; a description that
     * throws is replaced by a generic sentence, so this method throws nothing
     * into the failing site (story 337, ruling E77: no new throw).
     *
     * @param message the failure's description: what failed and what the audio did
     * @param failure the thrown object, or {@code null}
     */
    public synchronized void failed(Supplier<String> message, Throwable failure) {
        long now = clockNanos.getAsLong();
        failuresSinceLastRecord++;
        if (written && now - lastWrittenNanos < WINDOW_NANOS) {
            return;
        }
        long count = written ? failuresSinceLastRecord : 0L;
        written = true;
        lastWrittenNanos = now;
        failuresSinceLastRecord = 0L;
        logger.log(level, failure, () -> describe(message, count));
    }

    private static String describe(Supplier<String> message, long failuresSincePrevious) {
        String text;
        try {
            text = message.get();
        } catch (RuntimeException unreadable) {
            text = UNREADABLE_MESSAGE;
        }
        if (failuresSincePrevious == 0L) {
            return text;
        }
        return text + " (" + failuresSincePrevious
                + (failuresSincePrevious == 1L ? " failure" : " failures")
                + " since the previous record)";
    }
}
