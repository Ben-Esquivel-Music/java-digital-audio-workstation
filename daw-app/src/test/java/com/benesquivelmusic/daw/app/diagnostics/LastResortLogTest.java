package com.benesquivelmusic.daw.app.diagnostics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

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

    @Test
    void aSmallerFileStaysWhereItIsAtLaunch() throws IOException {
        Path file = logs.resolve(LastResortLog.FILE_NAME);
        Files.writeString(file, "a small record");

        LastResortLog.moveOversizedAtLaunch(logs);
        LastResortLog.moveOversizedAtLaunch(logs.resolve("missing"));

        assertThat(Files.readString(file)).isEqualTo("a small record");
        assertThat(logs.resolve(LastResortLog.PREVIOUS_FILE_NAME)).doesNotExist();
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
