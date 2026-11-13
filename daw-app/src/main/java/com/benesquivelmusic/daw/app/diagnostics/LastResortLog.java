package com.benesquivelmusic.daw.app.diagnostics;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The record of last resort when the failure funnel itself throws (story
 * 336, Goals bullet 5).
 *
 * <p>It writes to standard error <em>and</em> appends to a file of its own,
 * {@code <logs>/daw-last-resort.log}, opening, appending and closing it on
 * every use; in a packaged windowed application standard error goes nowhere.
 * It never appends to the rotating {@code daw-*.log} files: after its first
 * rotation {@code FileHandler} reopens generation 0 truncated and writes at
 * its own offset, and foreign bytes would escape its size meter. Within a
 * session the file stops growing once it reaches {@link #MAX_BYTES} and is
 * never rotated on the failure path; at launch, a file that size or larger is
 * moved aside to {@code daw-last-resort-previous.log}
 * ({@link #moveOversizedAtLaunch}), so one bad session cannot disable the
 * last resort for every later one.</p>
 *
 * <p>It uses no {@code java.util.logging}, no formatter, no resource bundle,
 * no {@code toString()} and no {@code printStackTrace()} of the throwables:
 * class names, guarded messages and guarded frames only. Every write is
 * inside {@code catch (Throwable)} and gives up silently; it is the end of
 * the line.</p>
 */
public final class LastResortLog {

    /** The file's name in the log folder. */
    public static final String FILE_NAME = "daw-last-resort.log";

    /** Where an oversized file is moved at launch, replacing an older copy. */
    public static final String PREVIOUS_FILE_NAME = "daw-last-resort-previous.log";

    /** The size at which appends stop for the session, and at which the file is moved aside at launch. */
    static final long MAX_BYTES = 1_048_576L;

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT);

    private final Optional<Path> file;
    private final PrintStream standardError;

    /**
     * @param file the file to append to, normally {@code <logs>/daw-last-resort.log}; empty when the log
     *             folder is unusable, and then only standard error is written
     */
    public LastResortLog(Optional<Path> file) {
        this(file, System.err);
    }

    LastResortLog(Optional<Path> file, PrintStream standardError) {
        this.file = Objects.requireNonNull(file, "file must not be null");
        this.standardError = Objects.requireNonNull(standardError, "standardError must not be null");
    }

    /**
     * At launch, while still single-threaded and before any handler is
     * installed: moves a {@code daw-last-resort.log} of
     * {@link #MAX_BYTES} or more to {@code daw-last-resort-previous.log},
     * replacing any older copy. Never throws; on failure the cap still stops
     * appends.
     *
     * @param logFolder the log folder
     */
    public static void moveOversizedAtLaunch(Path logFolder) {
        try {
            Path current = logFolder.resolve(FILE_NAME);
            if (Files.isRegularFile(current) && Files.size(current) >= MAX_BYTES) {
                Files.move(current, logFolder.resolve(PREVIOUS_FILE_NAME), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable unmovable) {
            // The cap still stops appends; nothing else can be done this early.
        }
    }

    /**
     * Records that the funnel failed while it handled {@code original}.
     *
     * @param thread        the thread the failure happened on
     * @param original      the failure the funnel was handling
     * @param funnelFailure what the funnel threw, or the failure that re-entered it
     */
    public void write(Thread thread, Throwable original, Throwable funnelFailure) {
        try {
            StringBuilder record = header(thread);
            appendThrowable(record, "Failure being handled", original);
            appendThrowable(record, "What the failure funnel hit while handling it", funnelFailure);
            emit(record.toString());
        } catch (Throwable givingUp) {
            // The end of the line.
        }
    }

    /**
     * Records that the funnel failed in work of its own that handled no
     * particular failure — a tail, a notice, the log folder, disarming.
     *
     * @param thread        the thread the funnel ran on
     * @param activity      what the funnel was doing
     * @param funnelFailure what it threw
     */
    public void write(Thread thread, String activity, Throwable funnelFailure) {
        try {
            StringBuilder record = header(thread);
            record.append("\tWhile ").append(activity).append(System.lineSeparator());
            appendThrowable(record, "What the failure funnel hit", funnelFailure);
            emit(record.toString());
        } catch (Throwable givingUp) {
            // The end of the line.
        }
    }

    private static StringBuilder header(Thread thread) {
        StringBuilder record = new StringBuilder(512);
        String timestamp;
        try {
            timestamp = TIMESTAMP.format(ZonedDateTime.now(ZoneId.systemDefault()));
        } catch (Throwable unreadable) {
            timestamp = "-";
        }
        record.append(timestamp).append(" LAST-RESORT [").append(threadName(thread))
                .append("] the failure funnel could not handle a failure").append(System.lineSeparator());
        return record;
    }

    private static String threadName(Thread thread) {
        try {
            String name = thread.getName();
            return name == null || name.isEmpty() ? "#" + thread.threadId() : name;
        } catch (Throwable unreadable) {
            return "-";
        }
    }

    private static void appendThrowable(StringBuilder record, String label, Throwable failure) {
        String nl = System.lineSeparator();
        record.append('\t').append(label).append(": ").append(GuardedThrowables.className(failure));
        String message;
        try {
            message = failure.getMessage();
        } catch (Throwable unreadable) {
            message = "<the message could not be read: " + GuardedThrowables.className(unreadable) + ">";
        }
        if (message != null) {
            record.append(": ").append(message.replace("\r", " ").replace("\n", " "));
        }
        record.append(nl);
        for (StackTraceElement frame : GuardedThrowables.frames(failure)) {
            record.append("\t\tat ").append(frame).append(nl);
        }
    }

    private void emit(String record) {
        try {
            standardError.print(record);
            standardError.flush();
        } catch (Throwable unprintable) {
            // Standard error may be gone; the file is still tried.
        }
        if (file.isEmpty()) {
            return;
        }
        try {
            Path target = file.get();
            if (Files.exists(target) && Files.size(target) >= MAX_BYTES) {
                return;
            }
            try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {
                out.write(record.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable unwritable) {
            // Gives up silently: the end of the line.
        }
    }
}
