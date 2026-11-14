package com.benesquivelmusic.daw.app.diagnostics;

import com.benesquivelmusic.daw.app.diagnostics.GuardedThrowablesTest.LineBreak;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The record of last resort when the funnel itself throws (story 336,
 * Goals—Tests bullet 5): standard error and a separate, append-only file that stops
 * growing at 1 MiB within a session and is moved aside at launch; no
 * {@code toString()}, so a throwable whose every part throws is still written
 * by its class name; it never throws; and a write made while the same thread
 * is already writing one reads neither throwable, so reading a throwable never
 * recurses through the last resort (story 336, studio-engineer ruling
 * R992-14).
 */
class LastResortLogTest {

    /** Where a record begins: a timestamp at the start of a line, as {@code grep -E '^[0-9]{4}-'} finds it. */
    private static final Pattern RECORD_START = Pattern.compile("^\\d{4}-", Pattern.MULTILINE);

    /** Every line break a reader may split on. */
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");

    /**
     * What a nested write writes where a message would be (story 336,
     * studio-engineer ruling R992-14): spelled here, never read from the class.
     */
    private static final String NOT_READ =
            "<the message and frames were not read: this thread was already writing a last-resort record>";

    /** How many times a {@link Reentering} re-enters at most, so the broken code's recursion ends in milliseconds. */
    private static final int FUSE = 16;

    /** Where the outer write holds the re-entering throwable. */
    enum Outer {
        /** As the failure being handled, in {@code write(Thread, Throwable, Throwable)}. */
        FAILURE_BEING_HANDLED("Failure being handled"),
        /** As what the funnel hit, in {@code write(Thread, Throwable, Throwable)}. */
        FAILURE_HIT("What the failure funnel hit while handling it"),
        /** As what the funnel hit, in {@code write(Thread, String, Throwable)}. */
        ACTIVITY("What the failure funnel hit");

        private final String label;

        Outer(String label) {
            this.label = label;
        }

        /** The label of the line that holds the re-entering throwable. */
        String label() {
            return label;
        }
    }

    /** Which write overload a write is. */
    enum Overload {
        /** {@code write(Thread, Throwable, Throwable)}. */
        FAILURE,
        /** {@code write(Thread, String, Throwable)}. */
        ACTIVITY
    }

    /** Which instance a nested write is made on. */
    enum Instance {
        /** The outer write's own. */
        SAME,
        /** A second one on the same file and standard error. */
        ANOTHER
    }

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

    /**
     * Reading a throwable's message or frames — the re-entering throwable
     * tried as the failure being handled and as what the funnel hit in
     * {@code write(Thread, Throwable, Throwable)}, and as what it hit in
     * {@code write(Thread, String, Throwable)}, since
     * {@code FailureFunnel.reentered} hands the re-entering failure as what the
     * funnel hit — can re-enter the last resort on the same thread, as the
     * funnel sends it a failure that re-entered with the same original: such a
     * write, on this instance or another, reads neither throwable, is written
     * as the header, the activity when there is one and each throwable's class
     * name with the marker and no frames, and comes before the record whose
     * reading caused it (story 336, studio-engineer rulings R992-13 and
     * R992-14). The re-entering throwable re-enters at most {@link #FUSE}
     * times, so a last resort without the guard ends its recursion in
     * milliseconds.
     */
    @ParameterizedTest(name = "outer {0}, nested {1} on the {2} instance")
    @MethodSource("nestings")
    void aWriteMadeWhileThisThreadIsWritingOneReadsNeitherThrowableAndComesFirst(Outer outerWrite,
                                                                                 Overload nestedOverload,
                                                                                 Instance instance)
            throws IOException {
        Path file = logs.resolve(LastResortLog.FILE_NAME);
        LastResortLog outer = new LastResortLog(Optional.of(file), capturedError);
        LastResortLog nested = instance == Instance.SAME ? outer : new LastResortLog(Optional.of(file), capturedError);
        Thread failing = Thread.ofPlatform().name("daw-take-storage").unstarted(() -> { });
        List<Counting> handedToNestedWrites = new ArrayList<>();
        Reentering reentering = new Reentering("the take folder went away");
        reentering.reenterWith(() -> {
            Counting hit = new Counting("re-entered");
            handedToNestedWrites.add(hit);
            switch (nestedOverload) {
                case FAILURE -> nested.write(failing, reentering, hit);
                case ACTIVITY -> nested.write(failing, "re-entered while a message was read", hit);
            }
        });

        switch (outerWrite) {
            case FAILURE_BEING_HANDLED ->
                    outer.write(failing, reentering, new IllegalArgumentException("what the funnel hit"));
            case FAILURE_HIT ->
                    outer.write(failing, new IllegalArgumentException("what was being handled"), reentering);
            case ACTIVITY -> outer.write(failing, "recording the tail of x", reentering);
        }

        assertThat(reentering.messageReads()).as("its message is read once, by the outer write").isEqualTo(1);
        assertThat(reentering.frameReads()).as("its frames are read once, by the outer write").isEqualTo(1);
        assertThat(handedToNestedWrites.stream().map(Counting::calls).toList())
                .as("a nested write per read, which calls nothing on what it was handed").containsExactly(0, 0);
        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(standardError.toString(StandardCharsets.UTF_8)).isEqualTo(written);
        List<String> records = records(written);
        assertThat(records).as("two nested records, then the outer one").hasSize(3);
        for (String record : records.subList(0, 2)) {
            assertIsNested(record, nestedOverload);
        }
        List<String> outerLines = List.of(LINE_BREAK.split(records.get(2)));
        int reenteringLine = outerLines.indexOf(
                "\t" + outerWrite.label() + ": " + Reentering.class.getName() + ": the take folder went away");
        assertThat(reenteringLine).as("the outer record holds its message").isPositive();
        assertThat(outerLines.get(reenteringLine + 1)).as("and its frames").startsWith("\t\tat ")
                .contains(LastResortLogTest.class.getName());
    }

    /**
     * The guard is keyed by the thread that writes, not by the thread a record
     * is about, and lasts only as long as a write: a write made meanwhile on
     * another thread about this thread, as the funnel's posted work writes on
     * the FX thread about the failing thread, and a later write on this
     * thread, are each written whole (story 336, studio-engineer ruling
     * R992-13). Its only wait is a bounded join.
     */
    @Test
    void aWriteOnAnotherThreadMeanwhileAndALaterWriteOnThisThreadAreWrittenWhole() throws IOException {
        Path file = logs.resolve(LastResortLog.FILE_NAME);
        LastResortLog lastResort = new LastResortLog(Optional.of(file), capturedError);
        Thread writer = Thread.currentThread();
        AtomicBoolean otherEnded = new AtomicBoolean();
        OnFirstMessageRead throwing = new OnFirstMessageRead("written on this thread");
        throwing.onFirstRead(() -> {
            Thread other = Thread.ofPlatform().name("last-resort-other-thread").start(() -> lastResort.write(
                    writer, new IllegalStateException("written on another thread"),
                    new IllegalStateException("b")));
            try {
                otherEnded.set(other.join(Duration.ofSeconds(10)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });

        lastResort.write(writer, throwing, new IllegalStateException("a"));
        lastResort.write(writer, new IllegalStateException("written later"), new IllegalStateException("c"));

        assertThat(otherEnded).as("the other thread's write ended within the join: nothing made it wait").isTrue();
        String errors = standardError.toString(StandardCharsets.UTF_8);
        assertThat(records(errors)).as("the other thread's record, the outer one and the later one").hasSize(3);
        assertThat(errors)
                .as("none of them nested: the guard is keyed by the thread that writes, not by the thread a record"
                        + " is about")
                .doesNotContain("were not read");
        assertThat(recordHolding(errors, "IllegalStateException: written on another thread"))
                .as("the other thread's record, whole").contains("\t\tat ");
        assertThat(recordHolding(errors, "IllegalStateException: written later")).as("the later record, whole")
                .contains("\t\tat ");
    }

    /**
     * A nested record is printed and appended through the same path as any
     * record: one byte short of the cap, the first nested record is the one
     * that crosses it, written whole, and the two nested records after it, one
     * of each overload, and the record being written when they were made are
     * refused by the file and still printed (story 336, studio-engineer
     * rulings R992-4 and R992-14).
     */
    @Test
    void aNestedRecordIsWrittenWholeUnderTheCapAheadOfTheRecordBeingWritten() throws IOException {
        Path file = logs.resolve(LastResortLog.FILE_NAME);
        int prefill = (int) LastResortLog.MAX_BYTES - 1;
        Files.write(file, new byte[prefill]);
        LastResortLog lastResort = new LastResortLog(Optional.of(file), capturedError);
        Thread failing = Thread.ofPlatform().name("daw-take-storage").unstarted(() -> { });
        OnFirstMessageRead once = new OnFirstMessageRead("the take folder went away");
        once.onFirstRead(() -> {
            lastResort.write(failing, once, new IllegalArgumentException("re-entered"));
            lastResort.write(failing, once, new IllegalArgumentException("re-entered again"));
            lastResort.write(failing, "re-entered while a message was read",
                    new IllegalArgumentException("re-entered a third time"));
        });

        lastResort.write(failing, once, new IllegalArgumentException("what the funnel hit"));

        List<String> records = records(standardError.toString(StandardCharsets.UTF_8));
        assertThat(records).as("three nested records, then the outer one").hasSize(4);
        for (String nestedRecord : records.subList(0, 2)) {
            assertThat(nestedRecord).as("a nested record reads neither throwable")
                    .contains("\tFailure being handled: " + OnFirstMessageRead.class.getName() + ": " + NOT_READ)
                    .contains("\tWhat the failure funnel hit while handling it: java.lang.IllegalArgumentException: "
                            + NOT_READ)
                    .doesNotContain("\t\tat ");
        }
        assertThat(records.get(2)).as("the nested record of the other overload reads neither throwable")
                .contains("\tWhile re-entered while a message was read")
                .contains("\tWhat the failure funnel hit: java.lang.IllegalArgumentException: " + NOT_READ)
                .doesNotContain("\t\tat ");
        assertThat(records.get(3)).as("the outer record reads both")
                .contains("\tFailure being handled: " + OnFirstMessageRead.class.getName()
                        + ": the take folder went away")
                .contains("\tWhat the failure funnel hit while handling it: java.lang.IllegalArgumentException:"
                        + " what the funnel hit");
        byte[] written = Files.readAllBytes(file);
        String appended = new String(written, prefill, written.length - prefill, StandardCharsets.UTF_8);
        assertThat(appended)
                .as("the first nested record crossed the cap, whole; the two after it and the outer one were refused")
                .isEqualTo(records.getFirst());
    }

    /**
     * Each place the outer write holds the re-entering throwable with each
     * nested overload, the nested write on the same instance or another.
     */
    static Stream<Arguments> nestings() {
        return Stream.of(Outer.values()).flatMap(outer -> Stream.of(Overload.values())
                .flatMap(nested -> Stream.of(Instance.values()).map(instance -> Arguments.of(outer, nested, instance))));
    }

    /** {@code record} is a nested record of {@code overload}: exactly its header and lines, no frame. */
    private static void assertIsNested(String record, Overload overload) {
        String nl = System.lineSeparator();
        List<String> lines = List.of(LINE_BREAK.split(record));
        String header = lines.getFirst();
        assertThat(header).as("the nested record's header")
                .endsWith(" LAST-RESORT [daw-take-storage] the failure funnel could not handle a failure");
        String expected = switch (overload) {
            case FAILURE -> header + nl
                    + "\tFailure being handled: " + Reentering.class.getName() + ": " + NOT_READ + nl
                    + "\tWhat the failure funnel hit while handling it: " + Counting.class.getName() + ": " + NOT_READ
                    + nl;
            case ACTIVITY -> header + nl
                    + "\tWhile re-entered while a message was read" + nl
                    + "\tWhat the failure funnel hit: " + Counting.class.getName() + ": " + NOT_READ + nl;
        };
        assertThat(record).as("a nested record, which reads neither throwable").isEqualTo(expected);
    }

    /** The records in {@code text}, each from its header to the next record's. */
    private static List<String> records(String text) {
        List<Integer> starts = RECORD_START.matcher(text).results().map(MatchResult::start).toList();
        List<String> records = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            records.add(text.substring(starts.get(i), i + 1 < starts.size() ? starts.get(i + 1) : text.length()));
        }
        return records;
    }

    /** The record in {@code text} that holds {@code fragment}. */
    private static String recordHolding(String text, String fragment) {
        return records(text).stream().filter(record -> record.contains(fragment)).findFirst()
                .orElseThrow(() -> new AssertionError("no record holds " + fragment + " in: " + text));
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

    /**
     * A throwable whose message and frames, on each of the first
     * {@link #FUSE} reads of either, first re-enter, as a plugin's
     * {@code getMessage()} that logs a record a handler hands the funnel can;
     * it counts the reads of each.
     */
    private static final class Reentering extends IllegalStateException {
        private final AtomicInteger fuse = new AtomicInteger();
        private final AtomicInteger messageReads = new AtomicInteger();
        private final AtomicInteger frameReads = new AtomicInteger();
        private Runnable reenter = () -> { };

        Reentering(String message) {
            super(message);
        }

        void reenterWith(Runnable reenter) {
            this.reenter = reenter;
        }

        int messageReads() {
            return messageReads.get();
        }

        int frameReads() {
            return frameReads.get();
        }

        @Override
        public String getMessage() {
            messageReads.incrementAndGet();
            if (fuse.getAndIncrement() < FUSE) {
                reenter.run();
            }
            return super.getMessage();
        }

        @Override
        public StackTraceElement[] getStackTrace() {
            frameReads.incrementAndGet();
            if (fuse.getAndIncrement() < FUSE) {
                reenter.run();
            }
            return super.getStackTrace();
        }
    }

    /** A throwable that counts every call of its message, frames, cause and string form. */
    private static final class Counting extends IllegalArgumentException {
        private final AtomicInteger calls = new AtomicInteger();

        Counting(String message) {
            super(message);
        }

        int calls() {
            return calls.get();
        }

        @Override
        public String getMessage() {
            calls.incrementAndGet();
            return super.getMessage();
        }

        @Override
        public String getLocalizedMessage() {
            calls.incrementAndGet();
            return super.getLocalizedMessage();
        }

        @Override
        public StackTraceElement[] getStackTrace() {
            calls.incrementAndGet();
            return super.getStackTrace();
        }

        @Override
        public synchronized Throwable getCause() {
            calls.incrementAndGet();
            return super.getCause();
        }

        @Override
        public String toString() {
            calls.incrementAndGet();
            return super.toString();
        }
    }

    /** A throwable whose message, read the first time, first runs what it was given. */
    private static final class OnFirstMessageRead extends IllegalStateException {
        private final AtomicBoolean firstRead = new AtomicBoolean(true);
        private Runnable onFirstRead = () -> { };

        OnFirstMessageRead(String message) {
            super(message);
        }

        void onFirstRead(Runnable work) {
            this.onFirstRead = work;
        }

        @Override
        public String getMessage() {
            if (firstRead.getAndSet(false)) {
                onFirstRead.run();
            }
            return super.getMessage();
        }
    }
}
