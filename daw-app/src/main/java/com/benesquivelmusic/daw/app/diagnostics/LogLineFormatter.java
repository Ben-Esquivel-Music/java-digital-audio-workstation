package com.benesquivelmusic.daw.app.diagnostics;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * The log file's line format (story 336, Goals bullet 6; its "Note from
 * Story 334" on a record whose plugin exception cannot be formatted).
 *
 * <p>One header line per record —
 * {@code <yyyy-MM-dd'T'HH:mm:ss.SSSXXX> <LEVEL padded to 7> [<thread>] <logger>: <message>}
 * — and every following line of the same record begins with a tab: the
 * message's continuation lines, the exception line, its frames, its causes
 * and its suppressed exceptions ({@link GuardedThrowables#appendChain}). So
 * {@code grep -E '^[0-9]{4}-'} lists the records. The timestamp is local
 * with its offset; the level is {@link java.util.logging.Level#getName()},
 * not localized; the thread is the current thread's name when it is the
 * record's thread and has one, else {@code #<id>} (a {@link LogRecord}
 * carries only the id); the logger is the full name, or {@code -}. Lines end
 * with {@link System#lineSeparator()}. A line break inside a value — the
 * thread's name, the level, the logger, a class name, a frame, or a message's
 * line break other than CR, LF and CR LF, which begin its tab-indented
 * continuation lines — is written as its escape, <code>&#92;u000A</code> for
 * LF, so no value can split a record or forge one
 * ({@link GuardedThrowables#endLine}; story 336, studio-engineer ruling
 * R992-2).</p>
 *
 * <p>It never throws. {@code StreamHandler} catches only an
 * {@link Exception} a formatter throws, so an {@link Error} — a closed
 * plugin class loader's {@link NoClassDefFoundError} — would otherwise leave
 * {@code Logger.log} with no record written. Every read of the record's
 * exception is guarded, and if anything else fails the record is still
 * written, naming at least the exception's class.</p>
 *
 * <p>The log file's formatter also has a {@linkplain #getHead head}: one
 * line, formatted like a record, that names the session (studio-engineer
 * ruling, story 336). {@code StreamHandler} writes a formatter's head before the
 * first record of every output stream it opens — the file the sink opens at
 * launch, and each new generation 0 a rotation opens in the middle of a
 * session — so every log file names the session its records belong to.</p>
 */
public final class LogLineFormatter extends Formatter {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT);

    /** The width the level name is padded to. */
    private static final int LEVEL_WIDTH = 7;

    /** The logger the head line names. */
    private final String headLogger;

    /** The head line's message, or none: then the formatter has no head. */
    private final Optional<String> headMessage;

    /** Creates the formatter, without a head. */
    public LogLineFormatter() {
        this.headLogger = "-";
        this.headMessage = Optional.empty();
    }

    /**
     * Creates the log file's formatter, whose head is one INFO line of
     * {@code headLogger} saying {@code headMessage}.
     *
     * @param headLogger  the logger the head line names
     * @param headMessage the head line's message
     */
    LogLineFormatter(String headLogger, String headMessage) {
        this.headLogger = Objects.requireNonNull(headLogger, "headLogger must not be null");
        this.headMessage = Optional.of(Objects.requireNonNull(headMessage, "headMessage must not be null"));
    }

    /**
     * The head of a new output stream: the head line formatted like an INFO
     * record of now, on the writing thread, or nothing. {@code StreamHandler}
     * calls it while it holds its own lock, so it only formats — it never
     * logs, takes no other lock, and never throws.
     *
     * <p>The head is stamped when it is written, which can be later than the
     * first record written after it: a record carries the time it was logged
     * and is formatted before {@code StreamHandler} takes its lock, so a record
     * that waited for the lock follows a head stamped after it. File order,
     * not timestamp order, is authoritative: the lines after the head, up to
     * the next session's head, are this session's.</p>
     *
     * @param handler the handler opening the stream
     * @return the head line, or {@code ""}
     */
    @Override
    public String getHead(Handler handler) {
        if (headMessage.isEmpty()) {
            return "";
        }
        try {
            LogRecord head = new LogRecord(Level.INFO, headMessage.get());
            head.setLoggerName(headLogger);
            return format(head);
        } catch (Throwable unformattable) {
            return "";
        }
    }

    /**
     * {@code at} as the timestamp every line begins with: local, with its offset.
     *
     * @param at the instant
     * @return {@code yyyy-MM-dd'T'HH:mm:ss.SSSXXX} in the system zone
     */
    static String timestamp(Instant at) {
        return TIMESTAMP.format(at.atZone(ZoneId.systemDefault()));
    }

    @Override
    public String format(LogRecord record) {
        try {
            return formatRecord(record);
        } catch (Throwable unformattable) {
            return fallback(record, unformattable);
        }
    }

    private String formatRecord(LogRecord record) {
        String lineSeparator = System.lineSeparator();
        StringBuilder out = new StringBuilder(256);
        int start = out.length();
        appendHeader(out, record);
        List<String> lines = messageOf(record).lines().toList();
        out.append(lines.isEmpty() ? "" : lines.getFirst());
        GuardedThrowables.endLine(out, start, lineSeparator);
        for (int i = 1; i < lines.size(); i++) {
            int lineStart = out.length();
            out.append('\t').append(lines.get(i));
            GuardedThrowables.endLine(out, lineStart, lineSeparator);
        }
        Throwable thrown = record.getThrown();
        if (thrown != null) {
            GuardedThrowables.appendChain(out, thrown, lineSeparator);
        }
        return out.toString();
    }

    private static void appendHeader(StringBuilder out, LogRecord record) {
        out.append(TIMESTAMP.format(record.getInstant().atZone(ZoneId.systemDefault()))).append(' ');
        String level = record.getLevel() == null ? "-" : record.getLevel().getName();
        out.append(level);
        for (int i = level.length(); i < LEVEL_WIDTH; i++) {
            out.append(' ');
        }
        out.append(" [").append(threadOf(record)).append("] ");
        String logger = record.getLoggerName();
        out.append(logger == null ? "-" : logger).append(": ");
    }

    /** The current thread's name when it is the record's thread and has one, else {@code #<id>}. */
    private static String threadOf(LogRecord record) {
        Thread current = Thread.currentThread();
        long recordThread = record.getLongThreadID();
        String name = current.getName();
        if (current.threadId() == recordThread && name != null && !name.isEmpty()) {
            return name;
        }
        return "#" + recordThread;
    }

    private String messageOf(LogRecord record) {
        try {
            String message = formatMessage(record);
            return message == null ? "" : message;
        } catch (Throwable unformattable) {
            return "<the message could not be formatted: " + GuardedThrowables.className(unformattable) + ">";
        }
    }

    /** At least a header naming the failure, and the record's exception class when it has one. */
    private static String fallback(LogRecord record, Throwable unformattable) {
        String lineSeparator = System.lineSeparator();
        StringBuilder out = new StringBuilder(128);
        int start = out.length();
        try {
            out.append(TIMESTAMP.format(record.getInstant().atZone(ZoneId.systemDefault())));
        } catch (Throwable unreadable) {
            out.append('-');
        }
        try {
            out.append(' ').append(record.getLevel().getName());
        } catch (Throwable unreadable) {
            out.append(" -");
        }
        try {
            out.append(" [#").append(record.getLongThreadID()).append("] ");
        } catch (Throwable unreadable) {
            out.append(" [-] ");
        }
        try {
            String logger = record.getLoggerName();
            out.append(logger == null ? "-" : logger);
        } catch (Throwable unreadable) {
            out.append('-');
        }
        out.append(": <the record could not be formatted: ").append(unformattable.getClass().getName())
                .append('>');
        GuardedThrowables.endLine(out, start, lineSeparator);
        try {
            Throwable thrown = record.getThrown();
            if (thrown != null) {
                int lineStart = out.length();
                out.append('\t').append(thrown.getClass().getName());
                GuardedThrowables.endLine(out, lineStart, lineSeparator);
            }
        } catch (Throwable unreadable) {
            // The header above is the record.
        }
        return out.toString();
    }
}
