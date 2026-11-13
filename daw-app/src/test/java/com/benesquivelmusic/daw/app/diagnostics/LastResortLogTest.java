package com.benesquivelmusic.daw.app.diagnostics;

import com.benesquivelmusic.daw.app.diagnostics.GuardedThrowablesTest.LineBreak;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The record of last resort when the funnel itself throws (story 336,
 * Goals—Tests bullet 5): standard error and a separate, append-only file that stops
 * growing at 1 MiB within a session and is moved aside at launch; no
 * {@code toString()}, so a throwable whose every part throws is still written
 * by its class name; and it never throws.
 */
class LastResortLogTest {

    /** Where a record begins: a timestamp at the start of a line, as {@code grep -E '^[0-9]{4}-'} finds it. */
    private static final Pattern RECORD_START = Pattern.compile("^\\d{4}-", Pattern.MULTILINE);

    /** Every line break a reader may split on. */
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");

    @TempDir
    Path logs;

    private final ByteArrayOutputStream standardError = new ByteArrayOutputStream();
    private final PrintStream capturedError = new PrintStream(standardError, true, StandardCharsets.UTF_8);

    @Test
    void itWritesBothThrowablesByClassMessageAndFramesToStandardErrorAndItsOwnFile() throws IOException {
        Path file = logs.resolve(LastResortLog.FILE_NAME);
        LastResortLog lastResort = new LastResortLog(Optional.of(file), capturedError);
        Thread failing = Thread.ofPlatform().name("daw-take-storage").unstarted(() -> { });

        lastResort.write(failing, new IllegalStateException("disk gone"), new IllegalArgumentException("listener"));

        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(written).contains("[daw-take-storage]")
                .contains("\tFailure being handled: java.lang.IllegalStateException: disk gone")
                .contains("\tWhat the failure funnel hit while handling it: java.lang.IllegalArgumentException:"
                        + " listener");
        assertThat(written.lines()).as("the frames")
                .anySatisfy(line -> assertThat(line).startsWith("\t\tat ").contains(LastResortLogTest.class.getName()));
        assertThat(standardError.toString(StandardCharsets.UTF_8)).isEqualTo(written);
        assertThat(LastResortLog.FILE_NAME).isEqualTo("daw-last-resort.log");
    }

    @Test
    void itAppendsAndNeverTouchesTheRotatingLog() throws IOException {
        Path file = logs.resolve(LastResortLog.FILE_NAME);
        Files.writeString(logs.resolve("daw-0.log"), "the rotating log");
        LastResortLog lastResort = new LastResortLog(Optional.of(file), capturedError);

        lastResort.write(Thread.currentThread(), new IllegalStateException("first"), new IllegalStateException("a"));
        lastResort.write(Thread.currentThread(), "recording a tail", new IllegalStateException("second"));

        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(written).contains("first").contains("\tWhile recording a tail")
                .contains("What the failure funnel hit: java.lang.IllegalStateException: second");
        assertThat(written.indexOf("first")).isLessThan(written.indexOf("second"));
        assertThat(Files.readString(logs.resolve("daw-0.log"))).isEqualTo("the rotating log");
    }

    @Test
    void aThrowableWhoseMessageAndToStringThrowIsWrittenByItsClassName() throws IOException {
        Path file = logs.resolve(LastResortLog.FILE_NAME);
        LastResortLog lastResort = new LastResortLog(Optional.of(file), capturedError);

        assertThatCode(() -> lastResort.write(Thread.currentThread(), new Hostile(), new Hostile()))
                .doesNotThrowAnyException();

        assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .contains(Hostile.class.getName() + ": <the message could not be read: java.lang.NoClassDefFoundError>");
    }

    @Test
    void itStopsAppendingOnceTheFileReachesOneMebibyte() throws IOException {
        Path file = logs.resolve(LastResortLog.FILE_NAME);
        byte[] full = new byte[(int) LastResortLog.MAX_BYTES];
        Files.write(file, full);
        LastResortLog lastResort = new LastResortLog(Optional.of(file), capturedError);

        lastResort.write(Thread.currentThread(), new IllegalStateException("x"), new IllegalStateException("y"));

        assertThat(Files.size(file)).isEqualTo(LastResortLog.MAX_BYTES);
        assertThat(standardError.toString(StandardCharsets.UTF_8)).as("standard error still gets it")
                .contains("IllegalStateException: x");
    }

    /**
     * Two writers in a failure storm, the first held between the size check
     * and the append: the check and the append are made together under one
     * lock, so a file one byte short of the cap takes the first writer's
     * record whole and refuses the second's, and standard error still gets
     * both (story 336, studio-engineer ruling R992-4). The writers are platform
     * threads, so the second one's state reads {@code BLOCKED} while it waits.
     */
    @Test
    void theSizeCheckAndTheAppendAreMadeTogetherSoASecondWriterCannotAppendPastTheCap() throws Exception {
        Path file = logs.resolve(LastResortLog.FILE_NAME);
        int prefill = (int) LastResortLog.MAX_BYTES - 1;
        Files.write(file, new byte[prefill]);
        CountDownLatch firstPastTheCheck = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondPastTheCheck = new CountDownLatch(1);
        AtomicBoolean firstCaller = new AtomicBoolean(true);
        LastResortLog lastResort = new LastResortLog(Optional.of(file), capturedError, () -> {
            if (firstCaller.getAndSet(false)) {
                firstPastTheCheck.countDown();
                try {
                    releaseFirst.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            } else {
                secondPastTheCheck.countDown();
            }
        });
        Thread first = Thread.ofPlatform().name("last-resort-writer-1").unstarted(() -> lastResort.write(
                Thread.currentThread(), new IllegalStateException("first"), new IllegalStateException("a")));
        Thread second = Thread.ofPlatform().name("last-resort-writer-2").unstarted(() -> lastResort.write(
                Thread.currentThread(), new IllegalStateException("second"), new IllegalStateException("b")));

        try {
            first.start();
            assertThat(firstPastTheCheck.await(10, TimeUnit.SECONDS)).as("the first writer is past the size check")
                    .isTrue();
            second.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!secondPastTheCheck.await(10, TimeUnit.MILLISECONDS)
                    && second.getState() != Thread.State.BLOCKED) {
                assertThat(System.nanoTime() - deadline)
                        .as("within 10 s the second writer is past the size check, or waits for the lock").isNegative();
            }
        } finally {
            releaseFirst.countDown();
        }
        assertThat(first.join(Duration.ofSeconds(10))).isTrue();
        assertThat(second.join(Duration.ofSeconds(10))).isTrue();

        byte[] written = Files.readAllBytes(file);
        String appended = new String(written, prefill, written.length - prefill, StandardCharsets.UTF_8);
        String errors = standardError.toString(StandardCharsets.UTF_8);
        String firstRecord = recordNaming(errors, "last-resort-writer-1");
        assertThat(Pattern.compile(" LAST-RESORT \\[").matcher(appended).results().count())
                .as("one record past the cap").isEqualTo(1);
        assertThat(appended).as("the first writer's, whole").isEqualTo(firstRecord);
        assertThat((long) written.length).as("the prefill and the first writer's record")
                .isEqualTo(prefill + (long) firstRecord.getBytes(StandardCharsets.UTF_8).length);
        assertThat(recordNaming(errors, "last-resort-writer-2")).as("standard error gets the second one too")
                .contains("IllegalStateException: second");
    }

    /**
     * A writer whose interrupt flag is set still appends its record and keeps
     * the flag: an uncaught failure can arrive on a thread that restored its
     * interrupt before it threw, and in the packaged application the file is
     * the only record (story 336, studio-engineer ruling R992-4).
     */
    @Test
    void aWriterWhoseInterruptIsSetStillAppendsItsRecordAndKeepsTheInterrupt() throws IOException {
        Path file = logs.resolve(LastResortLog.FILE_NAME);
        LastResortLog lastResort = new LastResortLog(Optional.of(file), capturedError);

        boolean stillInterrupted;
        try {
            Thread.currentThread().interrupt();
            lastResort.write(Thread.currentThread(), new IllegalStateException("x"), new IllegalStateException("y"));
            stillInterrupted = Thread.currentThread().isInterrupted();
        } finally {
            Thread.interrupted();
        }

        assertThat(stillInterrupted).as("the writer keeps its interrupt").isTrue();
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).as("the record is in the file")
                .contains("IllegalStateException: x")
                .isEqualTo(standardError.toString(StandardCharsets.UTF_8));
    }

    @Test
    void itNeverThrowsEvenWhenTheFileIsADirectoryOrThereIsNoFile() throws IOException {
        Path directory = Files.createDirectories(logs.resolve(LastResortLog.FILE_NAME));

        assertThatCode(() -> new LastResortLog(Optional.of(directory), capturedError)
                .write(Thread.currentThread(), new IllegalStateException("x"), new IllegalStateException("y")))
                .doesNotThrowAnyException();
        assertThatCode(() -> new LastResortLog(Optional.empty(), capturedError)
                .write(Thread.currentThread(), "disarming", new IllegalStateException("z")))
                .doesNotThrowAnyException();
        assertThat(standardError.toString(StandardCharsets.UTF_8)).contains("IllegalStateException: z");
    }

    @Test
    void anOversizedFileIsMovedAsideAtLaunchAndTheNextWriteStartsAFreshOne() throws IOException {
        Path file = logs.resolve(LastResortLog.FILE_NAME);
        Path previous = logs.resolve(LastResortLog.PREVIOUS_FILE_NAME);
        Files.writeString(previous, "an older copy");
        Files.write(file, new byte[(int) LastResortLog.MAX_BYTES]);

        LastResortLog.moveOversizedAtLaunch(logs);
        new LastResortLog(Optional.of(file), capturedError)
                .write(Thread.currentThread(), new IllegalStateException("fresh"), new IllegalStateException("y"));

        assertThat(Files.size(previous)).as("the old file replaced the older copy").isEqualTo(LastResortLog.MAX_BYTES);
        assertThat(Files.size(file)).isLessThan(LastResortLog.MAX_BYTES);
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("IllegalStateException: fresh");
        assertThat(LastResortLog.PREVIOUS_FILE_NAME).isEqualTo("daw-last-resort-previous.log");
    }

    /**
     * A line break in any value a record holds — the thread's name, the
     * activity, a message, a frame — is written as its escape, so each record
     * is its header and lines that begin with a tab (story 336,
     * studio-engineer ruling R992-2).
     */
    @ParameterizedTest
    @EnumSource(LineBreak.class)
    void aLineBreakInAnyValueIsWrittenAsItsEscapeSoEachRecordKeepsItsLines(LineBreak lineBreak) throws IOException {
        String t = lineBreak.text();
        String escape = lineBreak.escape();
        Path file = logs.resolve(LastResortLog.FILE_NAME);
        LastResortLog lastResort = new LastResortLog(Optional.of(file), capturedError);
        Thread failing = Thread.ofPlatform().name("plugin" + t + "worker").unstarted(() -> { });
        String forging = "disk" + t + "2026-01-01T00:00:00.000Z LAST-RESORT [forged] x";

        lastResort.write(failing, "writing the tail of plugin.dispose|Evil" + t + "Plugin|x",
                withPoisonedFrames(new IllegalStateException(forging), t));
        lastResort.write(failing, withPoisonedFrames(new IllegalStateException(forging), t),
                withPoisonedFrames(new IllegalArgumentException(forging), t));

        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(RECORD_START.matcher(written).results().count()).as("one record start per record").isEqualTo(2);
        List<String> lines = List.of(LINE_BREAK.split(written));
        List<String> headers = lines.stream().filter(line -> !line.startsWith("\t")).toList();
        assertThat(headers).as("every line after each header begins with a tab").hasSize(2)
                .allSatisfy(header -> assertThat(header)
                        .endsWith(" LAST-RESORT [plugin" + escape + "worker] the failure funnel could not handle a"
                                + " failure"));
        assertThat(lines.getFirst()).isEqualTo(headers.getFirst());
        assertThat(lines).as("each value keeps its line")
                .contains("\tWhile writing the tail of plugin.dispose|Evil" + escape + "Plugin|x",
                        "\tFailure being handled: java.lang.IllegalStateException: disk" + escape
                                + "2026-01-01T00:00:00.000Z LAST-RESORT [forged] x")
                .anySatisfy(line -> assertThat(line).startsWith("\t\tat loader" + escape + "name/")
                        .endsWith("/com.example.Evil" + escape + "Name.run" + escape + "X(Evil" + escape + ".java:7)"));
        assertThat(standardError.toString(StandardCharsets.UTF_8)).isEqualTo(written);
    }

    @Test
    void aSmallerFileStaysWhereItIsAtLaunch() throws IOException {
        Path file = logs.resolve(LastResortLog.FILE_NAME);
        Files.writeString(file, "a small record");

        LastResortLog.moveOversizedAtLaunch(logs);
        LastResortLog.moveOversizedAtLaunch(logs.resolve("missing"));

        assertThat(Files.readString(file)).isEqualTo("a small record");
        assertThat(logs.resolve(LastResortLog.PREVIOUS_FILE_NAME)).doesNotExist();
    }

    /** The record in {@code text} whose header names {@code thread}: from its header to the next record's. */
    private static String recordNaming(String text, String thread) {
        List<Integer> starts = RECORD_START.matcher(text).results().map(MatchResult::start).toList();
        for (int i = 0; i < starts.size(); i++) {
            String record = text.substring(starts.get(i), i + 1 < starts.size() ? starts.get(i + 1) : text.length());
            if (record.contains(" LAST-RESORT [" + thread + "]")) {
                return record;
            }
        }
        throw new AssertionError("no record names " + thread + " in: " + text);
    }

    /** {@code failure}, its one frame carrying {@code t} in every field a plugin can set. */
    private static <T extends Throwable> T withPoisonedFrames(T failure, String t) {
        failure.setStackTrace(new StackTraceElement[] {new StackTraceElement("loader" + t + "name", "module" + t,
                "1" + t, "com.example.Evil" + t + "Name", "run" + t + "X", "Evil" + t + ".java", 7)});
        return failure;
    }

    /** A throwable whose message and string form throw, as a closed plugin class loader's can. */
    private static final class Hostile extends RuntimeException {
        @Override
        public String getMessage() {
            throw new NoClassDefFoundError("com/example/Message");
        }

        @Override
        public String toString() {
            throw new NoClassDefFoundError("com/example/ToString");
        }
    }
}
