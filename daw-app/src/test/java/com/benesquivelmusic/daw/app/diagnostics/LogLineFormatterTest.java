package com.benesquivelmusic.daw.app.diagnostics;

import com.benesquivelmusic.daw.app.ui.plugin.fixtures.JarOnlyExceptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The log file's line format (story 336, Goals bullet 6) and its
 * story-334 obligation: a record is written whatever formatting its exception
 * throws, naming at least the exception's class — also for an exception only
 * its plugin's closed class loader could format ({@link JarOnlyExceptions}).
 */
class LogLineFormatterTest {

    private static final String NL = System.lineSeparator();

    /** A record header: timestamp with offset, level padded to 7, thread, logger. */
    private static final Pattern HEADER = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}(Z|[+-]\\d{2}:\\d{2}) [A-Z ]{7} \\[[^]]+] \\S+: .*");

    private final LogLineFormatter formatter = new LogLineFormatter();

    @Test
    void aRecordIsOneHeaderLineWithTheLevelPaddedTheThreadAndTheLogger() {
        LogRecord record = record(Level.INFO, "Session started");

        String formatted = formatter.format(record);

        assertThat(formatted).endsWith(NL);
        List<String> lines = formatted.lines().toList();
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst()).matches(HEADER)
                .contains(" INFO    [" + Thread.currentThread().getName() + "] com.example.test: Session started");
    }

    @Test
    void severeAndWarningFillTheLevelColumn() {
        assertThat(formatter.format(record(Level.SEVERE, "x"))).contains(" SEVERE  [");
        assertThat(formatter.format(record(Level.WARNING, "x"))).contains(" WARNING [");
    }

    @Test
    void anotherThreadsRecordNamesItsIdAndAMissingLoggerIsADash() {
        LogRecord record = record(Level.INFO, "elsewhere");
        record.setLongThreadID(Thread.currentThread().threadId() + 1_000_000L);
        record.setLoggerName(null);

        String header = formatter.format(record).lines().findFirst().orElseThrow();

        assertThat(header).contains(" [#" + (Thread.currentThread().threadId() + 1_000_000L) + "] -: elsewhere");
    }

    @Test
    void anUnnamedVirtualThreadIsNamedByItsId() throws InterruptedException {
        String[] formatted = new String[1];
        Thread thread = Thread.ofVirtual().unstarted(() -> formatted[0] = formatter.format(record(Level.INFO, "v")));
        thread.start();
        thread.join();

        assertThat(formatted[0]).contains(" [#" + thread.threadId() + "] ");
    }

    @Test
    void everyFollowingLineOfARecordBeginsWithATab() {
        IllegalStateException cause = new IllegalStateException("the cause");
        IllegalArgumentException failure = new IllegalArgumentException("first line\nsecond line", cause);
        failure.addSuppressed(new UnsupportedOperationException("suppressed one"));
        LogRecord record = record(Level.SEVERE, "message line one\nmessage line two");
        record.setThrown(failure);

        List<String> lines = formatter.format(record).lines().toList();

        assertThat(lines.getFirst()).matches(HEADER).endsWith(": message line one");
        assertThat(lines.subList(1, lines.size())).allSatisfy(line -> assertThat(line).startsWith("\t"));
        assertThat(lines).contains("\tmessage line two",
                "\tjava.lang.IllegalArgumentException: first line",
                "\tsecond line",
                "\t\tSuppressed: java.lang.UnsupportedOperationException: suppressed one",
                "\tCaused by: java.lang.IllegalStateException: the cause");
        assertThat(lines).anySatisfy(line -> assertThat(line).startsWith("\t\tat ")
                .contains("LogLineFormatterTest.everyFollowingLineOfARecordBeginsWithATab"));
        assertThat(lines).anySatisfy(line -> assertThat(line).startsWith("\t\t\tat "));
        assertThat(lines.stream().filter(line -> line.matches("^\\d{4}-.*"))).as("one record").hasSize(1);
    }

    @Test
    void anExceptionOnlyItsClosedClassLoadersJarCanFormatIsStillWrittenByItsClass(@TempDir Path dir)
            throws Exception {
        RuntimeException jarOnly = JarOnlyExceptions.loadThenClose(dir);
        assertThat(catchThrowable(jarOnly::getMessage)).as("non-vacuity: its message cannot be read")
                .isInstanceOf(NoClassDefFoundError.class);
        LogRecord record = record(Level.WARNING, "A removed plugin failed while it was disposed");
        record.setThrown(jarOnly);

        String formatted = formatter.format(record);

        assertThat(formatted).contains("\t" + JarOnlyExceptions.EXCEPTION_CLASS_NAME
                + ": <the message could not be read: java.lang.NoClassDefFoundError>" + NL);
        assertThat(formatted.lines().filter(line -> line.startsWith("\t\tat "))).as("its frames").isNotEmpty();
    }

    @Test
    void aThrowableWhoseEveryPartThrowsIsStillWritten() {
        Throwable hostile = new HostileThrowable();
        LogRecord record = record(Level.SEVERE, "hostile");
        record.setThrown(hostile);

        String formatted = formatter.format(record);

        assertThat(formatted).contains("\t" + HostileThrowable.class.getName()
                        + ": <the message could not be read: java.lang.NoClassDefFoundError>")
                .contains("<the stack trace could not be read: java.lang.NoClassDefFoundError>")
                .contains("<the cause could not be read: java.lang.NoClassDefFoundError>");
    }

    @Test
    void aCauseCycleIsWrittenOnceAndADeepChainStops() {
        IllegalStateException first = new IllegalStateException("first");
        IllegalStateException second = new IllegalStateException("second", first);
        first.initCause(second);
        LogRecord cyclic = record(Level.SEVERE, "cycle");
        cyclic.setThrown(first);

        assertThat(formatter.format(cyclic)).contains("\tCaused by: java.lang.IllegalStateException: second")
                .contains("<circular reference: java.lang.IllegalStateException>");

        Throwable deep = new IllegalStateException("level 0");
        for (int level = 1; level <= 40; level++) {
            deep = new IllegalStateException("level " + level, deep);
        }
        LogRecord deepRecord = record(Level.SEVERE, "deep");
        deepRecord.setThrown(deep);

        String formatted = formatter.format(deepRecord);
        assertThat(formatted).contains("level 40").contains("<the chain goes deeper than 16 exceptions")
                .doesNotContain("level 0" + NL);
    }

    @Test
    void aMessageWhoseParameterThrowsIsWrittenWithoutIt() {
        LogRecord record = record(Level.INFO, "value {0}");
        record.setParameters(new Object[] {new Object() {
            @Override
            public String toString() {
                throw new NoClassDefFoundError("com/example/Gone");
            }
        }});

        String formatted = formatter.format(record);

        assertThat(formatted).matches(HEADER.pattern() + "\\R?")
                .contains(": <the message could not be formatted: java.lang.NoClassDefFoundError>");
    }

    @Test
    void aRecordThatCannotBeFormattedAtAllStillNamesItsExceptionsClass() {
        LogRecord record = new LogRecord(Level.SEVERE, "x") {
            @Override
            public java.time.Instant getInstant() {
                throw new NoClassDefFoundError("com/example/Clock");
            }
        };
        record.setLoggerName("com.example.test");
        record.setThrown(new IllegalStateException("kept"));

        String formatted = formatter.format(record);

        assertThat(formatted).contains("com.example.test: <the record could not be formatted:"
                        + " java.lang.NoClassDefFoundError>")
                .contains("\tjava.lang.IllegalStateException" + NL);
    }

    private static LogRecord record(Level level, String message) {
        LogRecord record = new LogRecord(level, message);
        record.setLoggerName("com.example.test");
        return record;
    }

    /** A throwable each of whose parts throws, as a closed plugin class loader's can. */
    private static final class HostileThrowable extends RuntimeException {
        @Override
        public String getMessage() {
            throw new NoClassDefFoundError("com/example/Message");
        }

        @Override
        public StackTraceElement[] getStackTrace() {
            throw new NoClassDefFoundError("com/example/Frames");
        }

        @Override
        public synchronized Throwable getCause() {
            throw new NoClassDefFoundError("com/example/Cause");
        }

        @Override
        public String toString() {
            throw new NoClassDefFoundError("com/example/ToString");
        }

        @Override
        public String getLocalizedMessage() {
            throw new NoClassDefFoundError("com/example/Localized");
        }
    }
}
