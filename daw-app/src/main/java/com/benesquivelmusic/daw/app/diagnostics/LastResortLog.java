package com.benesquivelmusic.daw.app.diagnostics;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
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
 * {@code <logs>/daw-last-resort.log}, opening and closing it for every
 * append; in a packaged windowed application standard error goes nowhere.
 * It never appends to the rotating {@code daw-*.log} files: after its first
 * rotation {@code FileHandler} reopens generation 0 truncated and writes at
 * its own offset, and foreign bytes would escape its size meter. Within a
 * session the file stops growing once it reaches {@link #MAX_BYTES} and is
 * never rotated on the failure path; at launch, a file that size or larger is
 * moved aside to {@code daw-last-resort-previous.log}
 * ({@link #moveOversizedAtLaunch}), so one bad session cannot disable the
 * last resort for every later one. The size check and the append are made
 * together under one lock, {@link #APPEND_LOCK}, for every writer in the
 * process, so this process's writers leave the file at most one record past
 * {@link #MAX_BYTES} in a session; the record is written to standard error
 * before the lock is taken (story 336, studio-engineer ruling R992-4). The
 * size is read with a stat and the record is appended through
 * {@code Files.newOutputStream}, whose channel an interrupt does not close,
 * so a writer whose interrupt flag is set still appends its record and keeps
 * the flag.</p>
 *
 * <p>It uses no {@code java.util.logging}, no formatter, no resource bundle,
 * no {@code toString()} and no {@code printStackTrace()} of the throwables:
 * class names, guarded messages and guarded frames only. Every write is
 * inside {@code catch (Throwable)} and gives up silently; it is the end of
 * the line. A line break inside any value it writes — the thread's name, the
 * activity, a class name, a message, a frame — is written as its escape,
 * <code>&#92;u000A</code> for LF, so each record is its header line and lines
 * that begin with a tab ({@link GuardedThrowables#endLine}; story 336,
 * studio-engineer ruling R992-2).</p>
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

    /**
     * Every size check and append of every instance in this process (story
     * 336, studio-engineer ruling R992-4). A leaf: nothing is logged, printed
     * or posted while it is held.
     */
    private static final Object APPEND_LOCK = new Object();

    private final Optional<Path> file;
    private final PrintStream standardError;
    private final Runnable afterTheSizeCheck;

    /**
     * @param file the file to append to, normally {@code <logs>/daw-last-resort.log}; empty when the log
     *             folder is unusable, and then only standard error is written
     */
    public LastResortLog(Optional<Path> file) {
        this(file, System.err, () -> { });
    }

    LastResortLog(Optional<Path> file, PrintStream standardError) {
        this(file, standardError, () -> { });
    }

    /**
     * A test-only seam: {@code afterTheSizeCheck} runs after the size check
     * lets an append through and before the append, under
     * {@link #APPEND_LOCK}, so a test can hold a writer there.
     *
     * @param file              as {@link #LastResortLog(Optional)}
     * @param standardError     where every record is printed
     * @param afterTheSizeCheck what runs between the size check and the append
     */
    LastResortLog(Optional<Path> file, PrintStream standardError, Runnable afterTheSizeCheck) {
        this.file = Objects.requireNonNull(file, "file must not be null");
        this.standardError = Objects.requireNonNull(standardError, "standardError must not be null");
        this.afterTheSizeCheck = Objects.requireNonNull(afterTheSizeCheck, "afterTheSizeCheck must not be null");
    }

    /**
     * At launch, while still single-threaded and before any handler is
     * installed: moves a {@code daw-last-resort.log} of
     * {@link #MAX_BYTES} or more to {@code daw-last-resort-previous.log},
     * replacing any older copy. Never throws; on failure the cap still stops
     * appends. It decides under {@link #APPEND_LOCK}, uncontended this early,
     * so every size decision is made under that one lock (story 336,
     * studio-engineer ruling R992-4).
     *
     * @param logFolder the log folder
     */
    public static void moveOversizedAtLaunch(Path logFolder) {
        synchronized (APPEND_LOCK) {
            try {
                Path current = logFolder.resolve(FILE_NAME);
                if (Files.isRegularFile(current) && Files.size(current) >= MAX_BYTES) {
                    Files.move(current, logFolder.resolve(PREVIOUS_FILE_NAME), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Throwable unmovable) {
                // The cap still stops appends; nothing else can be done this early.
            }
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
            int start = record.length();
            record.append("\tWhile ").append(activity);
            GuardedThrowables.endLine(record, start, System.lineSeparator());
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
        int start = record.length();
        record.append(timestamp).append(" LAST-RESORT [").append(threadName(thread))
                .append("] the failure funnel could not handle a failure");
        GuardedThrowables.endLine(record, start, System.lineSeparator());
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
        int start = record.length();
        record.append('\t').append(label).append(": ").append(GuardedThrowables.className(failure));
        String message;
        try {
            message = failure.getMessage();
        } catch (Throwable unreadable) {
            message = "<the message could not be read: " + GuardedThrowables.className(unreadable) + ">";
        }
        if (message != null) {
            record.append(": ").append(message);
        }
        GuardedThrowables.endLine(record, start, nl);
        for (StackTraceElement frame : GuardedThrowables.frames(failure)) {
            int frameStart = record.length();
            record.append("\t\tat ").append(frame);
            GuardedThrowables.endLine(record, frameStart, nl);
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
            byte[] bytes = record.getBytes(StandardCharsets.UTF_8);
            synchronized (APPEND_LOCK) {
                if (sizeOf(target) >= MAX_BYTES) {
                    return;
                }
                afterTheSizeCheck.run();
                // Files.newOutputStream's channel, unlike FileChannel.open's, is not closed by an interrupt,
                // so a writer whose interrupt flag is set still appends its record (story 336, studio-engineer
                // ruling R992-4).
                try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {
                    out.write(bytes);
                }
            }
        } catch (Throwable unwritable) {
            // Gives up silently: the end of the line.
        }
    }

    /** {@code target}'s size, 0 when it does not exist yet: a stat, which an interrupt cannot cut short. */
    private static long sizeOf(Path target) throws IOException {
        try {
            return Files.size(target);
        } catch (NoSuchFileException absent) {
            return 0L;
        }
    }
}
