package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.diagnostics.DeclaredSubject;
import com.benesquivelmusic.daw.app.diagnostics.FailureFunnel;
import com.benesquivelmusic.daw.app.diagnostics.FingerprintRateLimiter;
import com.benesquivelmusic.daw.app.diagnostics.FunnelTailNaming;
import com.benesquivelmusic.daw.app.logging.ExitHoldingLogManager;

import javafx.beans.value.ChangeListener;
import javafx.scene.control.Label;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.GUARD;
import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 336, Goals — Tests bullet 4 (Failure Surfacing book §6.2; story
 * 336, Goals bullet 4): the same fingerprint thrown
 * again and again shows at most one toast per 30 s window, anchored at the
 * last notice; a repeat after the window shows again with the running total;
 * the occurrences after the last notice are one history-only tail entry and
 * one log line; and the history accounts for every occurrence — the newest
 * notice's running total plus the later tail counts equals the occurrences
 * thrown. The log accounts for every occurrence too: a tail
 * still owed is written when the toasts are disarmed and, for occurrences
 * counted after that, when the log closes; and a fingerprint evicted from the
 * limiter writes its tail line as it goes. A tail line that a root handler
 * cannot write — at an eviction or at the disarming — costs the failure being
 * handled neither its record nor its toast, and goes to the last-resort file
 * naming the tail's fingerprint and count: a record that cannot be written
 * costs no failure its toast. When even the tail's name cannot be built, as
 * when memory runs out, the last resort still gets a record that needs
 * nothing more, the evicting failure still gets its record and its toast,
 * and the tails taken after it — at the disarming or when due — are still
 * written.
 *
 * <p>The funnel runs over the test's clocks ({@link FailureSpineFixture#withClock}),
 * installed as the default handler, and each occurrence is a real background
 * thread's uncaught failure thrown from one line, so all of them share one
 * fingerprint. {@link FailureSpineFixture#armOnFx()} is the
 * {@code MainController} substitute: the {@code FxDispatcher},
 * {@code NotificationBar} and {@code NotificationHistoryService} built as
 * {@code MainController.initializeNotificationBar} and
 * {@code DawApplication.start} build them, and the production
 * {@code installOnCurrentFxThread} and {@code armToasts} called. The tail is
 * found by the funnel's own pulse participant: the test drives the
 * dispatcher's pulse with the clock past due, the body of the
 * timer the application's dispatcher runs every frame. The disarming is the
 * production {@code disarmToasts}, the first statement of
 * {@code DawApplication.stop()}, and the log's close is the default handler's
 * own {@link ExitHoldingLogManager.OwedLines} answer, which the exit hold
 * calls at the JDK's shutdown reset ({@code ExitWaitLinesReachTheLogTest}
 * runs that reset itself, in a JVM of its own).</p>
 *
 * <p>Building a tail's last-resort name throws only when the JVM runs out of
 * memory, so the tests of a tail that cannot be named build the funnel
 * through its test-only seam ({@link FunnelTailNaming}), whose naming throws
 * an {@link OutOfMemoryError} for one fingerprint and names every other tail
 * as the application does.</p>
 *
 * <p>Every assertion on the bar or the history concerns this test's own
 * failure: the history is filtered by its message, and the toasts the bar
 * showed are recorded from its label and filtered the same way, so a failure
 * a thread leaked by an earlier test throws in the shared fork can neither
 * break nor satisfy them.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class FailureRateLimitTest {

    private static final String THREAD = "daw-meter-feed";
    private static final String MESSAGE = "the meter feed stalled";
    private static final String BASE = "Internal error in the background task “meter feed”: " + MESSAGE
            + ". Its work may be incomplete.";
    private static final long WINDOW = FingerprintRateLimiter.WINDOW_NANOS;
    private static final long SECOND = Duration.ofSeconds(1).toNanos();
    /** How many fingerprints the limiter keeps ({@code FingerprintRateLimiter.MAX_FINGERPRINTS}). */
    private static final int KEPT_FINGERPRINTS = 256;
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter LOG_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT);
    private static final Pattern RUNNING_TOTAL = Pattern.compile("It has happened (\\d+) times since ");
    private static final Pattern TAIL_COUNT = Pattern.compile("It happened (?:once more|(\\d+) more times) after");
    /** The class a root handler's formatter can no longer load when it throws on a tail line. */
    private static final String TAIL_FORMATTER = "com/example/broken/TailFormatter";

    private final AtomicLong nanos = new AtomicLong(SECOND);
    /** Noon today, so every time the copy shows is today's, whatever time the test runs. */
    private final AtomicReference<Instant> wall =
            new AtomicReference<>(LocalDate.now(ZONE).atTime(LocalTime.NOON).atZone(ZONE).toInstant());

    @Test
    void oneToastPerWindowAndTheHistoryAccountsForEveryOccurrence(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = FailureSpineFixture.withClock(settings, nanos::get, wall::get,
                folder -> { })) {
            spine.armOnFx();
            List<String> toasts = new CopyOnWriteArrayList<>();
            ChangeListener<String> toastShown = (observable, before, now) -> toasts.add(now);
            Label toastLabel = onFx(() -> (Label) spine.bar().getPill().lookup(".notification-message"));
            runOnFx(() -> toastLabel.textProperty().addListener(toastShown));
            try {
                assertOneToastPerWindowAndTheAccounting(spine, toasts);
            } finally {
                runOnFx(() -> toastLabel.textProperty().removeListener(toastShown));
            }
        }
    }

    private void assertOneToastPerWindowAndTheAccounting(FailureSpineFixture spine, List<String> toasts)
            throws Exception {
        int thrown = 0;

        Instant firstSeen = wall.get();
        failOnce();
        thrown++;
        settle();
        assertThat(shownOrRecorded(spine)).as("the first occurrence: a toast and its history entry")
                .containsExactly(BASE);
        assertThat(ownToasts(toasts)).as("shown as a toast").containsExactly(BASE);
        String key = theOneFingerprint(spine);

        for (int i = 0; i < 9; i++) {
            advance(SECOND);
            failOnce();
            thrown++;
        }
        settle();
        assertThat(shownOrRecorded(spine)).as("inside the window: counted, no toast, no history entry")
                .containsExactly(BASE);
        assertThat(recordsOf(spine, key)).as("and no individual log record").hasSize(1);
        assertThat(spine.funnel().occurrenceTotals()).containsEntry(key, 10L);

        advance(WINDOW - 9 * SECOND);
        failOnce();
        thrown++;
        settle();
        String reshow = BASE + " It has happened 11 times since " + CLOCK.format(firstSeen.atZone(ZONE)) + ".";
        assertThat(shownOrRecorded(spine)).as("a repeat after the window shows again, with the running total")
                .containsExactly(BASE, reshow);
        assertThat(ownToasts(toasts)).as("as a toast").containsExactly(BASE, reshow);
        assertThat(recordsOf(spine, key)).as("one more record").hasSize(2).last()
                .satisfies(record -> assertThat(record.getMessage()).contains(", occurrence 11, fingerprint ")
                        .endsWith(", 9 occurrences since the previous notice were not logged individually"));

        Instant firstSuppressed = null;
        Instant lastSuppressed = null;
        for (int i = 0; i < 4; i++) {
            advance(SECOND);
            firstSuppressed = firstSuppressed == null ? wall.get() : firstSuppressed;
            lastSuppressed = wall.get();
            failOnce();
            thrown++;
        }
        settle();
        assertThat(shownOrRecorded(spine)).containsExactly(BASE, reshow);

        advance(WINDOW - 1);
        spine.pulse();
        assertThat(shownOrRecorded(spine)).as("one nanosecond before the tail is due, the pulse records none")
                .containsExactly(BASE, reshow);

        advance(1);
        spine.pulse();
        String tail = BASE + " It happened 4 more times after that notice, the last time at "
                + CLOCK.format(lastSuppressed.atZone(ZONE)) + ".";
        assertThat(shownOrRecorded(spine)).as("the pulse past due records the tail in the history")
                .containsExactly(BASE, reshow, tail);
        assertThat(ownToasts(toasts)).as("history only: the tail was never a toast").containsExactly(BASE, reshow);
        assertThat(spine.history().getEntries()).filteredOn(entry -> entry.message().equals(tail))
                .singleElement().satisfies(entry -> {
                    assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
                    assertThat(entry.actionLabel()).contains("Open Log Folder");
                    assertThat(entry.action()).isPresent();
                });
        String tailLine = "Failure thread.uncaught repeated 4 more times between "
                + LOG_TIME.format(firstSuppressed.atZone(ZONE)) + " and "
                + LOG_TIME.format(lastSuppressed.atZone(ZONE)) + ", not logged individually (fingerprint " + key
                + "); last message: " + MESSAGE;
        assertThat(recordsOf(spine, key)).as("and one tail line, without a stack").hasSize(3).last()
                .satisfies(record -> {
                    assertThat(record.getMessage()).isEqualTo(tailLine);
                    assertThat(record.getThrown()).isNull();
                });
        assertThat(spine.logText()).contains(tailLine);

        assertThat(thrown).isEqualTo(15);
        assertThat(spine.funnel().occurrenceTotals()).containsEntry(key, 15L);
        List<String> history = shownOrRecorded(spine);
        assertThat(history).as("two toasts, one tail entry").hasSize(3);
        assertThat(1 + (runningTotal(reshow) - 1) + tailCount(tail))
                .as("the history's increments: the notice, the re-show's ten, the tail's four")
                .isEqualTo(thrown);
        assertThat(runningTotal(history.get(1)) + tailCount(history.get(2)))
                .as("the newest notice's running total plus the later tail counts equals the occurrences thrown")
                .isEqualTo(thrown);
    }

    @Test
    void disarmingWritesTheOwedTailToTheLogAndTheLogsCloseWritesTheOnesCountedAfter(@TempDir Path settings)
            throws Exception {
        try (FailureSpineFixture spine = FailureSpineFixture.withClock(settings, nanos::get, wall::get,
                folder -> { })) {
            spine.armOnFx();
            FailureFunnel funnel = spine.funnel();
            assertThat(spine.dispatcher().pulseParticipantCount()).as("armed: the one tail check").isEqualTo(1);

            failOnce();
            advance(SECOND);
            Instant counted = wall.get();
            failOnce();
            String key = theOneFingerprint(spine);
            assertThat(tailLinesOf(spine, key)).as("a tail is owed, not yet due").isEmpty();

            funnel.disarmToasts();

            assertThat(spine.dispatcher().pulseParticipantCount()).as("disarmed: removed").isZero();
            String disarmedTail = tailLine(key, 1, counted, counted);
            assertThat(tailLinesOf(spine, key)).as("disarming writes the owed tail at once, due or not")
                    .containsExactly(disarmedTail);
            assertThat(spine.logText()).contains(disarmedTail);

            advance(2 * WINDOW);
            spine.pulse();
            settle();
            assertThat(shownOrRecorded(spine)).as("after teardown nothing more reaches the history")
                    .containsExactly(BASE);
            assertThat(tailLinesOf(spine, key)).as("and no tail is written twice").hasSize(1);

            failOnce();
            advance(SECOND);
            Instant countedDuringTheExitHold = wall.get();
            failOnce();
            assertThat(tailLinesOf(spine, key)).as("counted after the disarming, while the exit hold keeps the"
                    + " log open: owed").hasSize(1);
            assertThat(Thread.getDefaultUncaughtExceptionHandler()).as("the default handler the exit hold asks")
                    .isSameAs(funnel.defaultHandler()).isInstanceOf(ExitHoldingLogManager.OwedLines.class);

            ((ExitHoldingLogManager.OwedLines) Thread.getDefaultUncaughtExceptionHandler()).writeOwedLines();

            String closingTail = tailLine(key, 1, countedDuringTheExitHold, countedDuringTheExitHold);
            assertThat(tailLinesOf(spine, key)).as("the log's close writes the tail counted after the disarming")
                    .containsExactly(disarmedTail, closingTail);
            assertThat(spine.logText()).contains(closingTail);
            assertThat(shownOrRecorded(spine)).as("and still nothing reaches the history").containsExactly(BASE);
            assertThat(funnel.occurrenceTotals()).containsEntry(key, 4L);
            assertThat(recordsOf(spine, key)).as("the log accounts for all four: two notices and two tails")
                    .extracting(LogRecord::getMessage)
                    .satisfiesExactly(
                            notice -> assertThat(notice).contains(", occurrence 1, fingerprint "),
                            tail -> assertThat(tail).isEqualTo(disarmedTail),
                            notice -> assertThat(notice).contains(", occurrence 3, fingerprint ")
                                    .doesNotContain("not logged individually"),
                            tail -> assertThat(tail).isEqualTo(closingTail));
        }
    }

    @Test
    void evictingAFingerprintThatOwesATailWritesItsTailLineToTheLog(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = FailureSpineFixture.detached(settings, nanos::get, wall::get,
                folder -> { })) {
            FailureFunnel funnel = spine.funnel();
            DeclaredSubject first = DeclaredSubject.pluginDisposal("the first plugin");
            String key = "plugin.dispose|the first plugin|java.lang.IllegalStateException";
            funnel.reportDeclared(first, new IllegalStateException("the licence check failed"));
            advance(SECOND);
            Instant counted = wall.get();
            funnel.reportDeclared(first, new IllegalStateException("the licence check failed again"));
            assertThat(funnel.occurrenceTotals()).as("counted inside its window: a tail is owed")
                    .containsEntry(key, 2L);

            for (int i = 0; i < KEPT_FINGERPRINTS; i++) {
                advance(1);
                funnel.reportDeclared(DeclaredSubject.pluginDisposal("plugin " + i),
                        new IllegalStateException("its dispose failed"));
            }

            assertThat(funnel.occurrenceTotals()).as("the 257th fingerprint evicted the least recently seen")
                    .hasSize(KEPT_FINGERPRINTS).doesNotContainKey(key);
            String evictedTail = "Failure plugin.dispose repeated 1 more times between "
                    + LOG_TIME.format(counted.atZone(ZONE)) + " and " + LOG_TIME.format(counted.atZone(ZONE))
                    + ", not logged individually (fingerprint " + key + "); last message: the licence check failed"
                    + " again";
            assertThat(recordsOf(spine, key)).as("its notice, then the tail line its eviction wrote").hasSize(2)
                    .last().satisfies(record -> {
                        assertThat(record.getLevel()).isEqualTo(Level.SEVERE);
                        assertThat(record.getMessage()).isEqualTo(evictedTail);
                        assertThat(record.getThrown()).isNull();
                    });
            assertThat(spine.logText()).as("in the log file").contains(evictedTail);
        }
    }

    @Test
    void aTailLineThatCannotBeWrittenGoesToTheLastResortNamingItsCountAndTheEvictingFailureIsStillShown(
            @TempDir Path settings) throws Exception {
        String evictedKey = "plugin.dispose|the first plugin|java.lang.IllegalStateException";
        String evictingKey = "plugin.dispose|the evicting plugin|java.lang.IllegalStateException";
        Handler throwingOnTheTails = throwingOnTheTailLinesOf(List.of(evictedKey, evictingKey));
        Logger root = Logger.getLogger("");
        // A root handler ahead of the log file's, which the spine adds after it: these tail lines never reach the file.
        root.addHandler(throwingOnTheTails);
        try (FailureSpineFixture spine = FailureSpineFixture.detached(settings, nanos::get, wall::get,
                folder -> { })) {
            FailureFunnel funnel = spine.funnel();
            DeclaredSubject first = DeclaredSubject.pluginDisposal("the first plugin");
            funnel.reportDeclared(first, new IllegalStateException("the licence check failed"));
            advance(SECOND);
            funnel.reportDeclared(first, new IllegalStateException("the licence check failed again"));
            for (int i = 0; i < KEPT_FINGERPRINTS - 1; i++) {
                advance(1);
                funnel.reportDeclared(DeclaredSubject.pluginDisposal("plugin " + i),
                        new IllegalStateException("its dispose failed"));
            }
            assertThat(funnel.occurrenceTotals()).as("every fingerprint kept, the first one owing a tail")
                    .hasSize(KEPT_FINGERPRINTS).containsEntry(evictedKey, 2L);
            spine.armOnFx();

            advance(1);
            DeclaredSubject evicting = DeclaredSubject.pluginDisposal("the evicting plugin");
            IllegalStateException evictingFailure = new IllegalStateException("its dispose failed too");
            funnel.reportDeclared(evicting, evictingFailure);

            assertThat(funnel.occurrenceTotals()).as("the 257th fingerprint evicted the first")
                    .containsKey(evictingKey).doesNotContainKey(evictedKey);
            assertThat(recordsOf(spine, evictingKey)).as("the evicting failure's own notice record, all the same")
                    .singleElement().satisfies(record -> {
                        assertThat(record.getLevel()).isEqualTo(Level.SEVERE);
                        assertThat(record.getMessage()).contains(", occurrence 1, fingerprint " + evictingKey);
                        assertThat(record.getThrown()).isSameAs(evictingFailure);
                    });
            assertThat(spine.logText()).as("written to the log file")
                    .contains(", occurrence 1, fingerprint " + evictingKey);
            String evictingToast = "The plugin “the evicting plugin” failed while it was being closed: its dispose"
                    + " failed too. It is no longer in use, but it may hold memory or processor time until you"
                    + " restart.";
            FailureSpineFixture.await("the evicting failure's own toast", () -> spine.history().getEntries().stream()
                    .anyMatch(entry -> entry.message().equals(evictingToast)));
            assertThat(spine.history().getEntries()).filteredOn(entry -> entry.message().equals(evictingToast))
                    .singleElement().satisfies(entry -> assertThat(entry.action()).as("with the action").isPresent());
            assertThat(spine.lastResortText())
                    .as("the last resort holds the tail's Error, naming the evicted tail's fingerprint and count")
                    .contains("\tWhile writing the tail of " + evictedKey + " (1 occurrences counted since its last"
                            + " notice), whose fingerprint was evicted" + System.lineSeparator())
                    .contains("\tWhat the failure funnel hit: java.lang.NoClassDefFoundError: " + TAIL_FORMATTER);
            assertThat(spine.logText()).as("whose line the log file never received")
                    .doesNotContain("not logged individually (fingerprint " + evictedKey + ")");

            advance(SECOND);
            funnel.reportDeclared(evicting, new IllegalStateException("its dispose failed again"));
            assertThat(funnel.occurrenceTotals()).as("counted inside its window: a tail is owed")
                    .containsEntry(evictingKey, 2L);
            funnel.disarmToasts();

            assertThat(spine.lastResortText())
                    .as("the drain's tail line that cannot be written names its fingerprint and count too")
                    .contains("\tWhile writing the tail of " + evictingKey + " (1 occurrences counted since its"
                            + " last notice)" + System.lineSeparator());
            assertThat(spine.logText()).as("whose line the log file never received either")
                    .doesNotContain("not logged individually (fingerprint " + evictingKey + ")");
        } finally {
            root.removeHandler(throwingOnTheTails);
        }
    }

    @Test
    void anEvictedTailThatCanBeNeitherWrittenNorNamedCostsTheEvictingFailureNeitherItsRecordNorItsToast(
            @TempDir Path settings) throws Exception {
        String evictedKey = pluginDisposeKey("the first plugin");
        String evictingKey = pluginDisposeKey("the evicting plugin");
        Handler throwingOnTheTail = throwingOnTheTailLinesOf(List.of(evictedKey));
        Logger root = Logger.getLogger("");
        root.addHandler(throwingOnTheTail);
        try (FailureSpineFixture spine = FailureSpineFixture.detached(settings, nanos::get, wall::get,
                folder -> { }, unableToName(evictedKey))) {
            FailureFunnel funnel = spine.funnel();
            owesATail(funnel, "the first plugin");
            for (int i = 0; i < KEPT_FINGERPRINTS - 1; i++) {
                advance(1);
                funnel.reportDeclared(DeclaredSubject.pluginDisposal("plugin " + i),
                        new IllegalStateException("its dispose failed"));
            }
            spine.armOnFx();

            advance(1);
            IllegalStateException evictingFailure = new IllegalStateException("its dispose failed too");
            funnel.reportDeclared(DeclaredSubject.pluginDisposal("the evicting plugin"), evictingFailure);

            assertThat(funnel.occurrenceTotals()).as("the 257th fingerprint evicted the first")
                    .containsKey(evictingKey).doesNotContainKey(evictedKey);
            assertThat(recordsOf(spine, evictingKey)).as("the evicting failure's own notice record, all the same")
                    .singleElement().satisfies(record -> {
                        assertThat(record.getLevel()).isEqualTo(Level.SEVERE);
                        assertThat(record.getMessage()).contains(", occurrence 1, fingerprint " + evictingKey);
                        assertThat(record.getThrown()).isSameAs(evictingFailure);
                    });
            assertThat(spine.logText()).as("written to the log file")
                    .contains(", occurrence 1, fingerprint " + evictingKey);
            String evictingToast = pluginDisposeBase("the evicting plugin", "its dispose failed too");
            FailureSpineFixture.await("the evicting failure's own toast", () -> spine.history().getEntries().stream()
                    .anyMatch(entry -> entry.message().equals(evictingToast)));
            assertThat(spine.history().getEntries()).filteredOn(entry -> entry.message().equals(evictingToast))
                    .singleElement().satisfies(entry -> assertThat(entry.action()).as("with the action").isPresent());
            String nl = System.lineSeparator();
            assertThat(spine.lastResortText())
                    .as("the last resort records the failure being handled and what writing the tail line threw")
                    .contains("\tFailure being handled: java.lang.IllegalStateException: its dispose failed too" + nl)
                    .contains("\tWhat the failure funnel hit while handling it: java.lang.NoClassDefFoundError: "
                            + TAIL_FORMATTER + nl)
                    .doesNotContain(OutOfMemoryError.class.getName());
            assertThat(spine.logText()).as("whose line the log file never received")
                    .doesNotContain("not logged individually (fingerprint " + evictedKey + ")");
        } finally {
            root.removeHandler(throwingOnTheTail);
        }
    }

    @Test
    void aDrainedTailThatCanBeNeitherWrittenNorNamedCostsTheTailsTakenAfterItNothing(@TempDir Path settings)
            throws Exception {
        String unnameableKey = pluginDisposeKey("the first plugin");
        Handler throwingOnTheTail = throwingOnTheTailLinesOf(List.of(unnameableKey));
        Logger root = Logger.getLogger("");
        root.addHandler(throwingOnTheTail);
        try (FailureSpineFixture spine = FailureSpineFixture.detached(settings, nanos::get, wall::get,
                folder -> { }, unableToName(unnameableKey))) {
            FailureFunnel funnel = spine.funnel();
            // The limiter takes its tails least recently seen first: the first plugin's comes first.
            owesATail(funnel, "the first plugin");
            Instant secondCounted = owesATail(funnel, "the second plugin");
            Instant thirdCounted = owesATail(funnel, "the third plugin");

            funnel.disarmToasts();

            String secondTail = pluginDisposeTailLine("the second plugin", secondCounted);
            String thirdTail = pluginDisposeTailLine("the third plugin", thirdCounted);
            assertThat(recordsOf(spine, pluginDisposeKey("the second plugin"))).extracting(LogRecord::getMessage)
                    .as("the tails taken after it are still written").contains(secondTail);
            assertThat(recordsOf(spine, pluginDisposeKey("the third plugin"))).extracting(LogRecord::getMessage)
                    .contains(thirdTail);
            assertThat(spine.logText()).as("to the log file").contains(secondTail, thirdTail)
                    .doesNotContain("not logged individually (fingerprint " + unnameableKey + ")");
            String nl = System.lineSeparator();
            assertThat(spine.lastResortText())
                    .as("the last resort names what the funnel was doing, and what writing the line threw")
                    .contains("\tWhile writing the tails still owed when the toasts were disarmed" + nl
                            + "\tWhat the failure funnel hit: java.lang.NoClassDefFoundError: " + TAIL_FORMATTER + nl)
                    .doesNotContain(OutOfMemoryError.class.getName());
        } finally {
            root.removeHandler(throwingOnTheTail);
        }
    }

    @Test
    void aDueTailThatCannotBeNamedIsStillRecordedAndSoAreTheTailsTakenAfterIt(@TempDir Path settings)
            throws Exception {
        try (FailureSpineFixture spine = FailureSpineFixture.detached(settings, nanos::get, wall::get,
                folder -> { }, unableToName(pluginDisposeKey("the first plugin")))) {
            spine.armOnFx();
            FailureFunnel funnel = spine.funnel();
            // The limiter takes its tails least recently seen first: the first plugin's comes first.
            Instant firstCounted = owesATail(funnel, "the first plugin");
            Instant secondCounted = owesATail(funnel, "the second plugin");

            advance(WINDOW);
            spine.pulse();

            assertTailRecorded(spine, "the first plugin", firstCounted);
            assertTailRecorded(spine, "the second plugin", secondCounted);
        }
    }

    /** {@code plugin}'s due tail, counted at {@code counted}, has its log line and its history-only entry. */
    private static void assertTailRecorded(FailureSpineFixture spine, String plugin, Instant counted)
            throws Exception {
        String tailLine = pluginDisposeTailLine(plugin, counted);
        assertThat(recordsOf(spine, pluginDisposeKey(plugin))).extracting(LogRecord::getMessage)
                .as("%s's tail line", plugin).contains(tailLine);
        assertThat(spine.logText()).as("%s's tail line in the log file", plugin).contains(tailLine);
        String tailEntry = pluginDisposeBase(plugin, "its dispose failed")
                + " It happened once more after that notice, the last time at " + CLOCK.format(counted.atZone(ZONE))
                + ".";
        assertThat(spine.history().getEntries()).as("%s's history-only tail entry", plugin)
                .filteredOn(entry -> entry.message().equals(tailEntry)).hasSize(1);
    }

    /**
     * Reports {@code plugin}'s dispose failure twice inside one window: a
     * notice, then a counted occurrence, for which a tail is owed.
     *
     * @return when the counted occurrence happened
     */
    private Instant owesATail(FailureFunnel funnel, String plugin) {
        DeclaredSubject subject = DeclaredSubject.pluginDisposal(plugin);
        funnel.reportDeclared(subject, new IllegalStateException("its dispose failed"));
        advance(SECOND);
        Instant counted = wall.get();
        funnel.reportDeclared(subject, new IllegalStateException("its dispose failed again"));
        assertThat(funnel.occurrenceTotals()).as("%s owes a tail", plugin).containsEntry(pluginDisposeKey(plugin), 2L);
        return counted;
    }

    private static String pluginDisposeKey(String plugin) {
        return "plugin.dispose|" + plugin + "|java.lang.IllegalStateException";
    }

    private static String pluginDisposeBase(String plugin, String reason) {
        return "The plugin “" + plugin + "” failed while it was being closed: " + reason + ". It is no longer in"
                + " use, but it may hold memory or processor time until you restart.";
    }

    /** The tail line of {@link #owesATail}'s one counted occurrence of {@code plugin}. */
    private static String pluginDisposeTailLine(String plugin, Instant counted) {
        return "Failure plugin.dispose repeated 1 more times between " + LOG_TIME.format(counted.atZone(ZONE))
                + " and " + LOG_TIME.format(counted.atZone(ZONE)) + ", not logged individually (fingerprint "
                + pluginDisposeKey(plugin) + "); last message: its dispose failed again";
    }

    /**
     * Builds the funnel through its test-only seam, so that building the
     * last-resort name of {@code key}'s tail throws, as it does only when the
     * JVM runs out of memory.
     */
    private static Function<FailureFunnel.Collaborators, FailureFunnel> unableToName(String key) {
        return collaborators -> FunnelTailNaming.unableToName(collaborators,
                tail -> tail.fingerprint().key().equals(key), new OutOfMemoryError("Metaspace"));
    }

    /**
     * A root handler that throws on the tail line of each of {@code keys} —
     * as a handler's formatter does on a plugin's class that can no longer be
     * loaded — and takes every other record. Added before the spine, it comes
     * ahead of the log file's handler, so those lines never reach the file.
     */
    private static Handler throwingOnTheTailLinesOf(List<String> keys) {
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                String message = record.getMessage();
                if (message != null && message.startsWith("Failure plugin.dispose repeated ")
                        && keys.stream().anyMatch(key -> message.contains("(fingerprint " + key + ")"))) {
                    throw new NoClassDefFoundError(TAIL_FORMATTER);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setFormatter(new SimpleFormatter());
        return handler;
    }

    /** A real background thread's uncaught failure, thrown from this one line every time: one fingerprint. */
    private static void failOnce() throws InterruptedException {
        Thread worker = Thread.ofVirtual().name(THREAD).start(() -> {
            throw new IllegalStateException(MESSAGE);
        });
        assertThat(worker.join(GUARD)).isTrue();
    }

    private void advance(long byNanos) {
        nanos.addAndGet(byNanos);
        wall.updateAndGet(now -> now.plusNanos(byNanos));
    }

    /** One FX turn: every toast the funnel posted before it has run. */
    private static void settle() throws Exception {
        runOnFx(() -> { });
    }

    /** Every history entry of this test's failure, oldest first: each toast the bar showed, and each tail. */
    private static List<String> shownOrRecorded(FailureSpineFixture spine) {
        return spine.history().getEntries().stream()
                .map(NotificationEntry::message)
                .filter(message -> message.startsWith(BASE))
                .toList();
    }

    /** The toasts the bar showed of this test's failure, oldest first. */
    private static List<String> ownToasts(List<String> toasts) {
        return toasts.stream().filter(message -> message != null && message.startsWith(BASE)).toList();
    }

    /** This test's one fingerprint: the funnel's only key on {@link #THREAD}'s failure. */
    private static String theOneFingerprint(FailureSpineFixture spine) {
        List<String> keys = spine.funnel().occurrenceTotals().keySet().stream()
                .filter(key -> key.startsWith("thread.uncaught|java.lang.IllegalStateException|"
                        + FailureRateLimitTest.class.getName() + ".lambda$failOnce$"))
                .toList();
        assertThat(keys).as("one fingerprint for every occurrence").hasSize(1);
        return keys.getFirst();
    }

    /** The funnel's records of {@code key}, in order. */
    private static List<LogRecord> recordsOf(FailureSpineFixture spine, String key) {
        return spine.funnelRecords().stream()
                .filter(record -> record.getMessage().contains("fingerprint " + key))
                .toList();
    }

    /** The tail lines the funnel wrote for {@code key}, in order. */
    private static List<String> tailLinesOf(FailureSpineFixture spine, String key) {
        return recordsOf(spine, key).stream()
                .map(LogRecord::getMessage)
                .filter(message -> message.startsWith("Failure thread.uncaught repeated "))
                .toList();
    }

    private static String tailLine(String key, long suppressed, Instant first, Instant last) {
        return "Failure thread.uncaught repeated " + suppressed + " more times between "
                + LOG_TIME.format(first.atZone(ZONE)) + " and " + LOG_TIME.format(last.atZone(ZONE))
                + ", not logged individually (fingerprint " + key + "); last message: " + MESSAGE;
    }

    private static long runningTotal(String reshow) {
        Matcher matcher = RUNNING_TOTAL.matcher(reshow);
        assertThat(matcher.find()).as("a running total in %s", reshow).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    private static long tailCount(String tail) {
        Matcher matcher = TAIL_COUNT.matcher(tail);
        assertThat(matcher.find()).as("a tail count in %s", tail).isTrue();
        return matcher.group(1) == null ? 1 : Long.parseLong(matcher.group(1));
    }
}
