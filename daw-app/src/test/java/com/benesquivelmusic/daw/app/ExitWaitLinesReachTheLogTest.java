package com.benesquivelmusic.daw.app;

import com.benesquivelmusic.daw.app.logging.ExitHoldingLogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exit wait's lines reach the log file (story 336, Goals bullet 9 and
 * Goals—Tests bullet 8, binding; story 331, ruling N1). A child JVM sets
 * logging up exactly as the packaged launcher does —
 * the jpackage {@code --java-options} value of {@code daw-app/pom.xml},
 * {@code -m daw.app/…} with no surefire {@code --add-reads}
 * ({@link ChildJvm}), the production {@code DawLauncher.prepareLaunch} —
 * and exits on the JDK's real shutdown path ({@link ExitWaitLogLinesMain}):
 * {@code LogManager}'s own shutdown hook resets the handlers while the exit
 * wait still runs, and only the application's manager holds that reset. The
 * failure funnel's tails are part of what the hold keeps: a tail still owed
 * when the toasts are disarmed is written then, and one counted during the
 * exit wait is written by the default handler at the reset, on the JDK's
 * {@code Logging-Cleaner} thread, before the closing line.
 */
class ExitWaitLinesReachTheLogTest {

    private static final String PERSISTENCE_SEAM =
            "daw.core/com.benesquivelmusic.daw.core.persistence=daw.app";
    private static final String WAITING = "Waiting up to 2 s for the saves and checkpoints still being written before"
            + " the application exits";
    private static final String DEADLINE = "Saves or checkpoints were still being written 2 s after the application"
            + " began to exit; the application exits without them, and project.daw holds the last save that finished";
    private static final String CLOSED = "Log closed at application exit.";

    @Test
    void aSaveHeldPastTheShortenedBoundLeavesBothLinesThenTheClosingLine(@TempDir Path work) throws Exception {
        ChildJvm.Result child = ChildJvm.run(work, ExitWaitLogLinesMain.class,
                List.of(ChildJvm.logManagerOption(), "--add-opens", PERSISTENCE_SEAM),
                List.of("heldSave", work.resolve("projects").toString()));

        assertThat(child.exited()).as("the child exits; %s", child.printed()).isTrue();
        assertThat(child.exitValue()).as(child.printed()).isZero();
        assertThat(child.stdout()).contains("log manager: " + ExitHoldingLogManager.class.getName())
                .contains("the exit wait returned false");
        String log = child.log();
        assertInOrder(log, ": Exit hold: active", ": " + WAITING, ": " + DEADLINE, ": " + CLOSED);
        assertThat(log).contains(" INFO    [daw-exit-write-wait] com.benesquivelmusic.daw.core.persistence"
                        + ".ProjectManager: " + WAITING)
                .contains(" WARNING [daw-exit-write-wait] com.benesquivelmusic.daw.core.persistence"
                        + ".ProjectManager: " + DEADLINE);
    }

    @Test
    void withTheJdksOwnManagerTheDeadlineWarningIsLost(@TempDir Path work) throws Exception {
        ChildJvm.Result child = ChildJvm.run(work, ExitWaitLogLinesMain.class,
                List.of("-Djava.util.logging.manager=java.util.logging.LogManager", "--add-opens", PERSISTENCE_SEAM),
                List.of("heldSave", work.resolve("projects").toString()));

        assertThat(child.exitValue()).as(child.printed()).isZero();
        assertThat(child.stdout()).as("the wait ran to its deadline").contains("the exit wait returned false");
        assertThat(child.log()).as("non-vacuity: what this story fixes")
                .contains(": Exit hold: inactive (java.util.logging.manager does not name")
                .doesNotContain(DEADLINE)
                .doesNotContain(CLOSED);
    }

    @Test
    void anExitWaitThatOutlivesItsHoldGetsTheExpiryLineAndNothingAfterIt(@TempDir Path work) throws Exception {
        ChildJvm.Result child = ChildJvm.run(work, ExitWaitLogLinesMain.class,
                List.of(ChildJvm.logManagerOption()), List.of("holdExpires"));

        assertThat(child.exitValue()).as(child.printed()).isZero();
        String log = child.log();
        assertThat(log).contains(" WARNING [Logging-Cleaner] " + ExitHoldingLogManager.class.getName()
                        + ": The log is closing 6 s after the application began to exit, and the wait for saves and"
                        + " checkpoints has not returned; anything it logs after this line is lost.")
                .doesNotContain(ExitWaitLogLinesMain.LATE_LINE)
                .doesNotContain(CLOSED);
        assertThat(child.elapsed()).as("the JVM still waits for the exit wait itself")
                .isGreaterThanOrEqualTo(Duration.ofSeconds(8));
    }

    @Test
    void withNoExitWaitRegisteredTheLogClosesAtOnce(@TempDir Path work) throws Exception {
        ChildJvm.Result child = ChildJvm.run(work, ExitWaitLogLinesMain.class,
                List.of(ChildJvm.logManagerOption()), List.of("noExitWait"));

        assertThat(child.exitValue()).as(child.printed()).isZero();
        String log = child.log();
        assertThat(log).contains(" INFO    [Logging-Cleaner] " + ExitHoldingLogManager.class.getName() + ": "
                        + CLOSED)
                .doesNotContain(ExitWaitLogLinesMain.LATE_LINE);
        assertThat(child.elapsed()).as("well under the 35 s hold").isLessThan(Duration.ofSeconds(20));
    }

    @Test
    void occurrencesCountedAfterTheDisarmingAreWrittenBeforeTheClosingLine(@TempDir Path work) throws Exception {
        ChildJvm.Result child = ChildJvm.run(work, ExitWaitLogLinesMain.class,
                List.of(ChildJvm.logManagerOption()), List.of("failuresDuringTheHold"));

        assertThat(child.exited()).as("the child exits; %s", child.printed()).isTrue();
        assertThat(child.exitValue()).as(child.printed()).isZero();
        assertThat(child.stdout()).contains("the exit wait returned true");
        String log = child.log();
        String funnel = " SEVERE  [%s] com.benesquivelmusic.daw.app.diagnostics.FailureFunnel: ";
        assertInOrder(log,
                funnel.formatted("daw-take-storage") + "Failure thread.uncaught (ERROR) on thread \"daw-take-storage\","
                        + " occurrence 1, fingerprint ",
                ": Failure thread.uncaught repeated 1 more times between ",
                "; last message: " + ExitWaitLogLinesMain.BEFORE_DISARMING,
                funnel.formatted("daw-autosave") + "Failure thread.uncaught (ERROR) on thread \"daw-autosave\","
                        + " occurrence 1, fingerprint ",
                " SEVERE  [Logging-Cleaner] com.benesquivelmusic.daw.app.diagnostics.FailureFunnel: Failure"
                        + " thread.uncaught repeated 1 more times between ",
                "; last message: " + ExitWaitLogLinesMain.DURING_THE_HOLD,
                ": " + CLOSED);
        assertThat(lines(log, "Failure thread.uncaught repeated ")).as("the disarming's tail, then the close's")
                .hasSize(2);
    }

    private static List<String> lines(String log, String fragment) {
        return log.lines().filter(line -> line.contains(fragment)).toList();
    }

    private static void assertInOrder(String log, String... parts) {
        int from = 0;
        for (String part : parts) {
            int at = log.indexOf(part, from);
            assertThat(at).as("%s after position %d in:%n%s", part, from, log).isNotNegative();
            from = at + part.length();
        }
    }
}
