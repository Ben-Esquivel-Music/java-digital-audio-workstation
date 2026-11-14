package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.diagnostics.DeclaredSubject;
import com.benesquivelmusic.daw.app.diagnostics.FailureFunnel;
import com.benesquivelmusic.daw.app.diagnostics.LogLineFormatter;
import com.benesquivelmusic.daw.app.diagnostics.LogSink;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.JarOnlyExceptions;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.ConsoleHandler;
import java.util.logging.ErrorManager;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.GUARD;
import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 336, Goals — Tests bullet 1 (the Failure Surfacing book §6.3 gate-4
 * wiring test) and the startup order of Goals bullet 8 (book §6.4): both
 * handlers are
 * installed at startup, a failure thrown on the FX thread is a SEVERE record
 * with its whole stack in the log file and an ERROR toast, failures from
 * before the toasts are armed show once when they are — one fact's own toast,
 * a summary counting every one of them, or the first FATAL fact's toast
 * instead of the summary, even a FATAL one that came after the sixteen the
 * funnel keeps — and after the toasts are disarmed a failure is logged only.
 * The log sink's own failures are shown when the toasts are armed: a log
 * file that could not be opened as a WARNING without the action, before the
 * failure toasts, which keep it; a failed write once, with it, whether it
 * failed before the arming or after. And with the log file unavailable, a
 * failure whose exception only a closed plugin JAR could format still gets
 * its toast, and the console handler still writes its record.
 *
 * <p>In process and headless, with the repo's {@code MainController}
 * substitute: {@link FailureSpineFixture#launch} is the production
 * {@code DawLauncher.prepareLaunch} (its clocks, log sink and default
 * handler), and {@link FailureSpineFixture#armOnFx()} builds the
 * {@code FxDispatcher}, {@code NotificationBar} and
 * {@code NotificationHistoryService} as {@code MainController.initializeNotificationBar}
 * and {@code DawApplication.start} do and calls the same production units —
 * {@code FailureFunnel.installOnCurrentFxThread} and {@code armToasts}.
 * {@code FailureSpineWiringSourceTest} pins that {@code DawLauncher.main},
 * {@code DawApplication.start} and {@code MainController.attachFailureFunnel}
 * call them. The FX failure is thrown from a raw {@code Platform.runLater}, so
 * the toolkit hands it to the FX thread's handler as it hands an event
 * handler's.</p>
 *
 * <p>The pre-arm cases use a funnel the default handler does not reach,
 * handed its failures through each thread's own handler or reported by a
 * declaring site: the count in the summary toast must not include a failure a
 * thread leaked by an earlier test could throw in the shared fork. Every
 * assertion on the bar or the history concerns the test's own toasts, read
 * from the history entries filtered by their messages.</p>
 *
 * <p>Two cases run the window's own {@code attachFailureFunnel} and
 * {@code onOpenLogFolder} on a {@code new MainController()} that is never
 * FXML-loaded nor initialized: given the notification bar
 * {@code initializeNotificationBar} would have built, it arms the toasts on
 * it; without one, the toasts stay off and one WARNING says so, and Help ▸
 * Open Log Folder still opens the folder.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class FailureSpineStartupWiringTest {

    private static final String OPEN_LOG_FOLDER = "Open Log Folder";
    private static final String SUMMARY_TAIL = " internal errors occurred while the application was starting, so"
            + " some features may not work.";
    /** How many pre-arm toasts the funnel keeps ({@code FailureFunnel.PRE_ARM_CAPACITY}). */
    private static final int KEPT_BEFORE_ARMING = 16;

    @Test
    void bothHandlersAreInstalledAndAnFxFailureIsLoggedWithItsStackAndShownAsAnErrorToast(@TempDir Path settings)
            throws Exception {
        try (FailureSpineFixture spine = FailureSpineFixture.launch(settings)) {
            FailureFunnel funnel = spine.funnel();
            assertThat(Thread.getDefaultUncaughtExceptionHandler())
                    .as("DawLauncher.prepareLaunch installs the funnel's default handler")
                    .isSameAs(funnel.defaultHandler());
            assertThat(FailureFunnel.installed()).contains(funnel);

            spine.armOnFx();
            assertThat(onFx(() -> Thread.currentThread().getUncaughtExceptionHandler()))
                    .as("the FX thread's own handler, by identity: its thread group would only delegate to the"
                            + " default handler (story 336's Known limits, the clarification of Goals bullet 2)")
                    .isSameAs(funnel.fxThreadHandler());

            Platform.runLater(() -> {
                throw new IllegalStateException("gate four");
            });

            String expected = "Internal error in the user interface: gate four. Check that your last change took"
                    + " effect.";
            NotificationEntry toast = awaitOwnEntry(spine, expected);
            assertThat(toast.level()).as("an ERROR toast").isEqualTo(NotificationLevel.ERROR);
            assertThat(toast.actionLabel()).as("the toast carries the action").contains(OPEN_LOG_FOLDER);
            assertThat(toast.action()).isPresent();

            String log = spine.logText();
            Matcher header = Pattern.compile("(?m)^\\d{4}-\\S+ SEVERE  \\[JavaFX Application Thread\\]"
                    + " com\\.benesquivelmusic\\.daw\\.app\\.diagnostics\\.FailureFunnel: Failure ui\\.handler"
                    + " \\(ERROR\\) on thread \"JavaFX Application Thread\", occurrence 1, fingerprint ui\\.handler"
                    + "\\|java\\.lang\\.IllegalStateException\\|com\\.benesquivelmusic\\.daw\\.app\\.ui"
                    + "\\.FailureSpineStartupWiringTest\\.lambda\\$\\S+$").matcher(log);
            assertThat(header.find()).as("the SEVERE record in %s:%n%s", spine.logFile(), log).isTrue();
            List<String> record = recordAt(log, header.start());
            assertThat(record).as("the exception's line, tab-indented").contains("\tjava.lang.IllegalStateException:"
                    + " gate four");
            assertThat(record).as("its whole stack, down to the lambda that threw")
                    .anyMatch(line -> line.startsWith("\t\tat ")
                            && line.contains("FailureSpineStartupWiringTest.lambda$"));
            int sessionStarted = log.indexOf("Session started: Digital Audio Workstation ");
            int exitHold = log.indexOf("Exit hold: ");
            assertThat(sessionStarted).as("the banner's first line").isNotNegative().isLessThan(exitHold);
            assertThat(exitHold).as("the banner precedes the record").isLessThan(header.start());
        }
    }

    @Test
    void oneFailureBeforeTheToastsAreArmedShowsItsOwnToastOnceArmed(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = detached(settings)) {
            FailureFunnel funnel = spine.funnel();
            failOn(funnel, "daw-device-scan", () -> {
                throw new IllegalStateException("no audio device answered");
            });
            assertThat(severeRecordsOn(spine, "daw-device-scan")).as("logged at once, before any toast")
                    .hasSize(1);

            spine.armOnFx();

            String expected = "Internal error in the background task “device scan”: no audio device answered. Its"
                    + " work may be incomplete.";
            NotificationEntry toast = awaitOwnEntry(spine, expected);
            assertThat(toast.actionLabel()).contains(OPEN_LOG_FOLDER);
            assertThat(toast.action()).as("the history keeps the action").isPresent();
            settleAFewTurns();
            assertThat(entries(spine, message -> message.equals(expected))).as("shown once").hasSize(1);
        }
    }

    @Test
    void twoFailuresBeforeTheToastsAreArmedShowOneSummaryToastWithTheAction(@TempDir Path settings)
            throws Exception {
        try (FailureSpineFixture spine = detached(settings)) {
            FailureFunnel funnel = spine.funnel();
            failOn(funnel, "daw-device-scan", () -> {
                throw new IllegalStateException("no audio device answered");
            });
            failOn(funnel, "daw-plugin-scan", () -> {
                throw new IllegalArgumentException("a plugin manifest could not be read");
            });
            assertThat(funnel.occurrenceTotals()).as("two distinct failures, each surfaced").hasSize(2);
            assertThat(severeRecordsOn(spine, "daw-device-scan")).hasSize(1);
            assertThat(severeRecordsOn(spine, "daw-plugin-scan")).hasSize(1);

            spine.armOnFx();

            String summary = "2" + SUMMARY_TAIL;
            NotificationEntry toast = awaitOwnEntry(spine, summary);
            settleAFewTurns();
            assertThat(toast.level()).isEqualTo(NotificationLevel.ERROR);
            assertThat(toast.actionLabel()).contains(OPEN_LOG_FOLDER);
            assertThat(toast.action()).isPresent();
            assertThat(entries(spine, message -> message.equals(summary))).as("one toast for both, posted once")
                    .hasSize(1);
            assertThat(entries(spine, message -> message.contains("no audio device answered")
                    || message.contains("a plugin manifest could not be read")))
                    .as("and neither failure's own toast").isEmpty();
        }
    }

    @Test
    void moreFailuresBeforeTheToastsAreArmedThanTheFunnelKeepsAreAllCountedInTheSummary(@TempDir Path settings)
            throws Exception {
        try (FailureSpineFixture spine = detached(settings)) {
            FailureFunnel funnel = spine.funnel();
            int failures = KEPT_BEFORE_ARMING + 4;
            reportScannerFailures(funnel, failures);
            assertThat(funnel.occurrenceTotals()).as("every one surfaced").hasSize(failures);

            spine.armOnFx();

            String summary = failures + SUMMARY_TAIL;
            NotificationEntry toast = awaitOwnEntry(spine, summary);
            settleAFewTurns();
            assertThat(toast.level()).isEqualTo(NotificationLevel.ERROR);
            assertThat(toast.action()).isPresent();
            assertThat(entries(spine, message -> message.endsWith(SUMMARY_TAIL)))
                    .as("one summary, counting the ones beyond the sixteen kept").containsExactly(toast);
            assertThat(entries(spine, message -> message.contains("found nothing"))).as("no failure's own toast")
                    .isEmpty();
        }
    }

    @Test
    void aFatalFailureBeforeTheToastsAreArmedShowsTheFirstFatalToastInsteadOfTheSummary(@TempDir Path settings)
            throws Exception {
        try (FailureSpineFixture spine = detached(settings)) {
            FailureFunnel funnel = spine.funnel();
            failOn(funnel, "daw-device-scan", () -> {
                throw new IllegalStateException("no audio device answered");
            });
            failOn(funnel, "daw-meter-feed", () -> {
                throw new InternalError("the meter ring is corrupt");
            });
            failOn(funnel, "daw-take-storage", () -> {
                throw new StackOverflowError("the take index recursed");
            });

            spine.armOnFx();

            String firstFatal = "Serious internal error: the application may not work correctly from now on. When"
                    + " you can, save a copy with File ▸ Save As… and restart. Reason: the meter ring is corrupt.";
            NotificationEntry toast = awaitOwnEntry(spine, firstFatal);
            settleAFewTurns();
            assertThat(toast.level()).isEqualTo(NotificationLevel.ERROR);
            assertThat(toast.actionLabel()).contains(OPEN_LOG_FOLDER);
            assertThat(entries(spine, message -> message.equals(firstFatal)))
                    .as("the first FATAL fact's toast, once").hasSize(1);
            assertThat(entries(spine, message -> message.endsWith(SUMMARY_TAIL)
                    || message.contains("the take index recursed") || message.contains("no audio device answered")))
                    .as("instead of the summary, and of the other facts' toasts").isEmpty();
            assertThat(severeRecordsOn(spine, "daw-meter-feed")).singleElement().satisfies(record ->
                    assertThat(record.getMessage()).startsWith("Failure thread.uncaught (FATAL) on thread"));
        }
    }

    @Test
    void aFatalFailureAfterTheSixteenTheFunnelKeepsStillReplacesTheSummary(@TempDir Path settings)
            throws Exception {
        try (FailureSpineFixture spine = detached(settings)) {
            FailureFunnel funnel = spine.funnel();
            reportScannerFailures(funnel, KEPT_BEFORE_ARMING);
            funnel.reportDeclared(new DeclaredSubject("startup.scan", "the sample cache"),
                    new InternalError("the sample cache is corrupt"));
            assertThat(funnel.occurrenceTotals()).as("seventeen distinct failures, each surfaced")
                    .hasSize(KEPT_BEFORE_ARMING + 1);

            spine.armOnFx();

            String fatal = "Serious internal error: the application may not work correctly from now on. When you"
                    + " can, save a copy with File ▸ Save As… and restart. Reason: the sample cache is corrupt.";
            NotificationEntry toast = awaitOwnEntry(spine, fatal);
            settleAFewTurns();
            assertThat(toast.level()).isEqualTo(NotificationLevel.ERROR);
            assertThat(toast.action()).isPresent();
            assertThat(entries(spine, message -> message.equals(fatal))).as("the FATAL fact's toast, once")
                    .hasSize(1);
            assertThat(entries(spine, message -> message.endsWith(SUMMARY_TAIL) || message.contains("found nothing")))
                    .as("instead of the summary").isEmpty();
        }
    }

    @Test
    void afterTheToastsAreDisarmedAFailureIsLoggedAndShowsNothing(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = detached(settings)) {
            FailureFunnel funnel = spine.funnel();
            spine.armOnFx();
            failOn(funnel, "daw-take-storage", () -> {
                throw new IllegalStateException("the take folder went away");
            });
            String shown = "Internal error in the background task “take storage”: the take folder went away. Its"
                    + " work may be incomplete.";
            awaitOwnEntry(spine, shown);
            assertThat(spine.dispatcher().pulseParticipantCount()).as("the tail check, while armed").isEqualTo(1);

            funnel.disarmToasts();
            failOn(funnel, "daw-autosave", () -> {
                throw new IllegalArgumentException("the autosave folder went away");
            });
            settleAFewTurns();

            assertThat(severeRecordsOn(spine, "daw-autosave")).as("still logged after teardown").hasSize(1);
            assertThat(entries(spine, message -> message.contains("the autosave folder went away")))
                    .as("and not shown").isEmpty();
            assertThat(onFx(() -> spine.bar().getMessage())).doesNotContain("the autosave folder went away");
            assertThat(entries(spine, message -> message.equals(shown))).as("the toast before teardown, once")
                    .hasSize(1);
            assertThat(spine.dispatcher().pulseParticipantCount()).as("the tail check is removed")
                    .isZero();
        }
    }

    @Test
    void withTheLogFileUnavailableArmingWarnsWithoutTheActionBeforeTheFailureToastWhichKeepsIt(
            @TempDir Path settings) throws Exception {
        Path folder = blockTheLogFolder(settings);
        try (FailureSpineFixture spine = detached(settings)) {
            assertThat(spine.sink().orElseThrow().unavailableReason()).as("precondition: no log file").isPresent();
            failOn(spine.funnel(), "daw-device-scan", () -> {
                throw new IllegalStateException("no audio device answered");
            });

            spine.armOnFx();

            String unavailable = "The log file could not be opened in " + folder.toAbsolutePath() + ": ";
            String fact = "Internal error in the background task “device scan”: no audio device answered. Its"
                    + " work may be incomplete.";
            awaitOwnEntry(spine, fact);
            settleAFewTurns();
            assertThat(entries(spine, message -> message.startsWith(unavailable) || message.equals(fact)))
                    .as("the sink's WARNING first, then the failure")
                    .satisfiesExactly(
                            warning -> {
                                assertThat(warning.message()).endsWith(". This session is not logged to a file.");
                                assertThat(warning.level()).isEqualTo(NotificationLevel.WARNING);
                                assertThat(warning.action()).as("no action: there is no file to show").isEmpty();
                                assertThat(warning.actionLabel()).isEmpty();
                            },
                            failure -> {
                                assertThat(failure.message()).isEqualTo(fact);
                                assertThat(failure.level()).isEqualTo(NotificationLevel.ERROR);
                                assertThat(failure.actionLabel()).as("the failure keeps the action")
                                        .contains(OPEN_LOG_FOLDER);
                                assertThat(failure.action()).isPresent();
                            });
        }
    }

    @Test
    void withTheLogFileUnavailableAFailureOnlyItsClosedJarCanFormatIsStillShownAndWrittenToTheConsole(
            @TempDir Path directory) throws Exception {
        Path settings = directory.resolve("settings");
        blockTheLogFolder(settings);
        RuntimeException jarOnly = JarOnlyExceptions.loadThenClose(directory.resolve("exceptions"));
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        ConsoleHandler console = consoleHandlerWritingTo(written);
        Logger root = Logger.getLogger("");
        root.addHandler(console);
        try (FailureSpineFixture spine = detached(settings)) {
            assertThat(spine.sink().orElseThrow().unavailableReason()).as("precondition: no log file").isPresent();
            assertThat(console.getFormatter()).as("every root handler formats with the hardened formatter, the log"
                    + " file or not").isInstanceOf(LogLineFormatter.class);
            spine.armOnFx();

            spine.funnel().reportDeclared(DeclaredSubject.pluginDisposal("Licensed Reverb"), jarOnly);

            String expected = "The plugin “Licensed Reverb” failed while it was being closed: LicenceException. It is"
                    + " no longer in use, but it may hold memory or processor time until you restart.";
            NotificationEntry toast = awaitOwnEntry(spine, expected);
            assertThat(toast.level()).isEqualTo(NotificationLevel.ERROR);
            assertThat(toast.action()).as("with the action").isPresent();
            console.flush();
            assertThat(written.toString(StandardCharsets.UTF_8)).as("the console handler wrote the record")
                    .contains("Failure plugin.dispose (ERROR) on thread \"" + Thread.currentThread().getName() + "\"")
                    .contains("\t" + JarOnlyExceptions.EXCEPTION_CLASS_NAME
                            + ": <the message could not be read: java.lang.NoClassDefFoundError>");
        } finally {
            root.removeHandler(console);
            console.close();
        }
    }

    @Test
    void aWriteFailureBeforeTheToastsAreArmedIsShownOnceWithTheActionWhenTheyAre(@TempDir Path settings)
            throws Exception {
        try (FailureSpineFixture spine = detached(settings)) {
            ErrorManager errors = spine.sink().orElseThrow().handler().orElseThrow().getErrorManager();
            errors.error("write failed", new IOException("There is not enough space on the disk"),
                    ErrorManager.WRITE_FAILURE);
            errors.error("write failed again", new IOException("still full"), ErrorManager.WRITE_FAILURE);

            spine.armOnFx();

            assertTheWriteFailureIsShownOnce(spine);
        }
    }

    @Test
    void aWriteFailureWhileTheToastsAreArmedIsShownOnceWithTheAction(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = detached(settings)) {
            spine.armOnFx();
            ErrorManager errors = spine.sink().orElseThrow().handler().orElseThrow().getErrorManager();

            errors.error("write failed", new IOException("There is not enough space on the disk"),
                    ErrorManager.WRITE_FAILURE);
            errors.error("write failed again", new IOException("still full"), ErrorManager.WRITE_FAILURE);

            assertTheWriteFailureIsShownOnce(spine);
        }
    }

    @Test
    void theWindowArmsTheToastsOnItsNotificationBarAndOpensTheFunnelsLogFolder(@TempDir Path settings)
            throws Exception {
        BlockingQueue<Path> opened = new LinkedBlockingQueue<>();
        try (FailureSpineFixture spine = FailureSpineFixture.detached(settings, System::nanoTime,
                InstantSource.system(), opened::add)) {
            FxDispatcher dispatcher = new FxDispatcher();
            NotificationHistoryService history = new NotificationHistoryService();
            NotificationBar bar = onFx(() -> {
                NotificationBar made = new NotificationBar();
                made.setAnimated(false);
                made.setAutoDismissMillis(600_000);
                made.setHistoryService(history);
                return made;
            });
            MainController window = onFx(() -> {
                MainController made = new MainController();
                made.setFxDispatcher(dispatcher);
                // What initializeNotificationBar builds when the window is FXML-loaded.
                setField(made, "notificationBar", bar);
                made.attachFailureFunnel(spine.funnel());
                return made;
            });
            try {
                assertThat(dispatcher.pulseParticipantCount()).as("armed on the window's dispatcher").isEqualTo(1);
                failOn(spine.funnel(), "daw-take-storage", () -> {
                    throw new IllegalStateException("disk gone");
                });
                String expected = "Internal error in the background task “take storage”: disk gone. Its work may be"
                        + " incomplete.";
                FailureSpineFixture.await("the toast on the window's bar", () -> history.getEntries().stream()
                        .anyMatch(entry -> entry.message().equals(expected)));
                assertThat(history.getEntries()).extracting(NotificationEntry::message)
                        .filteredOn(message -> message.equals(expected)).as("shown on the window's bar, once")
                        .hasSize(1);

                runOnFx(window::onOpenLogFolder);
                assertThat(opened.poll(GUARD.toMillis(), TimeUnit.MILLISECONDS))
                        .as("Help ▸ Open Log Folder opens the funnel's log folder").isEqualTo(spine.funnel().logFolder());
            } finally {
                spine.funnel().disarmToasts();
                runOnFx(() -> {
                    bar.dismiss();
                    dispatcher.dispose();
                });
            }
        }
    }

    @Test
    void withoutANotificationBarTheToastsStayOffAndOneWarningSaysSo(@TempDir Path settings) throws Exception {
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.WARNING && record.getMessage().contains("failure toasts stay off")) {
                    warnings.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger windowLog = Logger.getLogger(MainController.class.getName());
        windowLog.addHandler(capture);
        BlockingQueue<Path> opened = new LinkedBlockingQueue<>();
        try (FailureSpineFixture spine = FailureSpineFixture.detached(settings, System::nanoTime,
                InstantSource.system(), opened::add)) {
            MainController window = onFx(() -> {
                MainController made = new MainController();
                made.attachFailureFunnel(spine.funnel());
                return made;
            });

            assertThat(warnings).as("one WARNING instead of a silent log-only window")
                    .singleElement().extracting(LogRecord::getMessage)
                    .isEqualTo("The window has no notification bar or FX dispatcher yet, so failure toasts stay off;"
                            + " failures are logged only");
            runOnFx(window::onOpenLogFolder);
            assertThat(opened.poll(GUARD.toMillis(), TimeUnit.MILLISECONDS))
                    .as("Help ▸ Open Log Folder still opens the folder").isEqualTo(spine.funnel().logFolder());
        } finally {
            windowLog.removeHandler(capture);
        }
    }

    private static FailureSpineFixture detached(Path settings) throws Exception {
        return FailureSpineFixture.detached(settings, System::nanoTime, InstantSource.system(), folder -> { });
    }

    /** A file where the log folder should be: the log sink cannot open its file. */
    private static Path blockTheLogFolder(Path settings) throws IOException {
        Path folder = LogSink.folderUnder(settings);
        Files.createDirectories(settings);
        Files.writeString(folder, "a file where the log folder should be");
        return folder;
    }

    /** {@code count} distinct failures before the arming, each reported by its declaring site. */
    private static void reportScannerFailures(FailureFunnel funnel, int count) {
        for (int i = 1; i <= count; i++) {
            funnel.reportDeclared(new DeclaredSubject("startup.scan", "scanner " + i),
                    new IllegalStateException("scanner " + i + " found nothing"));
        }
    }

    /** The one history entry of the sink's write failure: a WARNING with the action, shown once. */
    private static void assertTheWriteFailureIsShownOnce(FailureSpineFixture spine) throws Exception {
        String notice = "The log file could not be written: There is not enough space on the disk. Lines from this"
                + " session may be missing from it.";
        NotificationEntry shown = awaitOwnEntry(spine, notice);
        settleAFewTurns();
        assertThat(shown.level()).isEqualTo(NotificationLevel.WARNING);
        assertThat(shown.actionLabel()).as("with the action: the file is there to look at")
                .contains(OPEN_LOG_FOLDER);
        assertThat(shown.action()).isPresent();
        assertThat(entries(spine, message -> message.startsWith("The log file could not be written: ")))
                .as("once, though the file handler failed twice").hasSize(1);
    }

    /**
     * A console handler writing to {@code written}: {@link ConsoleHandler} takes
     * {@code System.err} as it is constructed, so standard error is swapped for
     * the construction only.
     */
    private static ConsoleHandler consoleHandlerWritingTo(ByteArrayOutputStream written) {
        PrintStream standardError = System.err;
        System.setErr(new PrintStream(written, true, StandardCharsets.UTF_8));
        try {
            return new ConsoleHandler();
        } finally {
            System.setErr(standardError);
        }
    }

    /** Waits for the history entry whose message is {@code message}, this test's own, and returns it. */
    private static NotificationEntry awaitOwnEntry(FailureSpineFixture spine, String message) throws Exception {
        FailureSpineFixture.await("the history entry " + message,
                () -> !entries(spine, each -> each.equals(message)).isEmpty());
        return entries(spine, each -> each.equals(message)).getFirst();
    }

    /** The history entries whose messages {@code which} accepts, oldest first. */
    private static List<NotificationEntry> entries(FailureSpineFixture spine, Predicate<String> which) {
        return spine.history().getEntries().stream().filter(entry -> which.test(entry.message())).toList();
    }

    /** A few FX turns: anything posted twice would have shown by then. */
    private static void settleAFewTurns() throws Exception {
        for (int turn = 0; turn < 5; turn++) {
            runOnFx(() -> { });
        }
    }

    /** Sets a field of a window that was never FXML-loaded, as {@code ProjectInstallationInputFenceTest} does. */
    private static void setField(MainController window, String name, Object value) {
        try {
            Field field = MainController.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(window, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    /** Runs {@code failing} on a virtual thread whose own handler is the funnel's default handler, to its end. */
    private static void failOn(FailureFunnel funnel, String threadName, Runnable failing) throws InterruptedException {
        Thread worker = Thread.ofVirtual().name(threadName).uncaughtExceptionHandler(funnel.defaultHandler())
                .start(failing);
        assertThat(worker.join(GUARD)).as("%s ended", threadName).isTrue();
    }

    /** The funnel's SEVERE records about a failure on {@code threadName}. */
    private static List<LogRecord> severeRecordsOn(FailureSpineFixture spine, String threadName) {
        return spine.funnelRecords().stream()
                .filter(record -> record.getLevel() == Level.SEVERE)
                .filter(record -> record.getMessage().contains("on thread \"" + threadName + "\""))
                .toList();
    }

    /** The lines of the log record that starts at {@code start}, up to the next record. */
    private static List<String> recordAt(String log, int start) {
        Matcher next = Pattern.compile("(?m)^\\d{4}-").matcher(log);
        int end = next.find(start + 1) ? next.start() : log.length();
        return log.substring(start, end).lines().toList();
    }
}
