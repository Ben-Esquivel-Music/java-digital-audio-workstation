package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.diagnostics.FailureFact;
import com.benesquivelmusic.daw.app.diagnostics.FailureFunnel;
import com.benesquivelmusic.daw.app.diagnostics.Fingerprint;
import com.benesquivelmusic.daw.app.diagnostics.NoticeAction;
import com.benesquivelmusic.daw.app.diagnostics.RealTimeReport;

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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 337 — the failure funnel's real-time vocabulary the audio health
 * drain reports through (rulings E39–E44; Failure Surfacing book §6.2):
 * {@link FailureFunnel#reportRealTime} surfaces a fingerprint's first
 * occurrence at once as a WARNING with the report's own action, counts the
 * repeats inside the 30 s window by their weight and re-shows after it with
 * the weighted total; {@link FailureFunnel#escalateRealTime} always surfaces,
 * at ERROR, and latches the fingerprint so its WARNING re-shows stop until
 * {@link FailureFunnel#clearEscalation}; {@link FailureFunnel#foldRealTime}
 * counts and, when it would surface, writes its own record without a toast
 * (ruling E65). Each record names the guard site in place of a thread and the
 * anchors sampled since the last notice; a report with no throwable is
 * logged without a stack; a WARNING notice's tail is a WARNING entry with the
 * notice's action. {@link FailureFunnel#openLogFolderNotice()} is the funnel's
 * Open Log Folder.
 *
 * <p>The funnel runs over the test's clocks ({@link FailureSpineFixture#withClock})
 * armed on a dispatcher and notification bar; each report is made on the FX
 * thread, as the drain's pulse makes it, and the test lets one more FX turn
 * run the toast the funnel posted. Records and entries are picked out by
 * this test's own fingerprints and sentences.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class FailureFunnelRealTimeReportTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private static final String SITE = "device-callback";
    private static final Fingerprint CALLBACK = Fingerprint.ofSite("audio.callback", SITE);
    private static final Fingerprint WORKER = Fingerprint.ofSite("audio.worker", "worker-pool");
    private static final Fingerprint CONFIG = Fingerprint.ofSite("audio.config", "returnBusCap");
    private static final String FIRST = "The audio callback for “Test Interface” failed, so a block of output was"
            + " played as silence: the driver returned a short buffer. Audio continues.";
    private static final String FAULT = "Audio engine fault — 12 callback errors since 12:00:00: the driver returned"
            + " a short buffer. Audio may stay silent or broken until you restart the audio engine.";

    private final AtomicLong nanos = new AtomicLong(Duration.ofSeconds(1).toNanos());
    private final AtomicReference<Instant> wall =
            new AtomicReference<>(LocalDate.now(ZONE).atTime(LocalTime.NOON).atZone(ZONE).toInstant());
    private final AtomicInteger restarts = new AtomicInteger();
    private final NoticeAction restart = new NoticeAction("Restart Audio Engine", restarts::incrementAndGet);
    private final IllegalStateException shortBuffer = new IllegalStateException("the driver returned a short buffer");

    @Test
    void aFirstOccurrenceIsAWarningWithTheReportsActionAndItsRecordNamesTheSiteAndTheAnchors(@TempDir Path settings)
            throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            report(spine, callback(FailureFact.Severity.WARNING, 3, FIRST));

            assertThat(entries(spine, FIRST)).singleElement().satisfies(entry -> {
                assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING);
                assertThat(entry.actionLabel()).hasValue("Restart Audio Engine");
                entry.action().orElseThrow().run();
            });
            assertThat(restarts).as("the entry carries the report's own action").hasValue(1);
            assertThat(records(spine, CALLBACK)).singleElement().satisfies(record -> {
                assertThat(record.getLevel()).as("a WARNING fact is logged at WARNING").isEqualTo(Level.WARNING);
                assertThat(record.getMessage()).isEqualTo("Failure audio.callback (WARNING) at \"" + SITE
                        + "\", occurrence 3, fingerprint audio.callback|" + SITE
                        + ", sampled anchors com.example.Driver.render:12; com.example.Driver.copy:40");
                assertThat(record.getThrown()).isSameAs(shortBuffer);
            });
            assertThat(spine.funnel().occurrenceTotals()).as("the weight counts").containsEntry(CALLBACK.key(), 3L);
            assertThat(spine.funnel().hasSeen(CALLBACK)).isTrue();
        }
    }

    @Test
    void repeatsInsideTheWindowAreCountedByWeightAndAReShowCarriesTheWeightedTotal(@TempDir Path settings)
            throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            Instant firstSeen = wall.get();
            report(spine, callback(FailureFact.Severity.WARNING, 2, FIRST));
            advance(Duration.ofSeconds(10));
            report(spine, callback(FailureFact.Severity.WARNING, 5, FIRST));
            advance(Duration.ofSeconds(10));
            report(spine, callback(FailureFact.Severity.WARNING, 1, FIRST));

            assertThat(entries(spine, FIRST)).as("inside the window: counted silently").hasSize(1);
            assertThat(spine.funnel().occurrenceTotals()).containsEntry(CALLBACK.key(), 8L);

            advance(Duration.ofSeconds(11));
            report(spine, callback(FailureFact.Severity.WARNING, 4, FIRST));

            assertThat(entries(spine, FIRST)).hasSize(2).last().satisfies(entry -> assertThat(entry.message())
                    .isEqualTo(FIRST + " It has happened 12 times since " + CLOCK.format(firstSeen.atZone(ZONE))
                            + "."));
            assertThat(records(spine, CALLBACK)).hasSize(2).last().satisfies(record -> assertThat(record.getMessage())
                    .contains("occurrence 12",
                            "6 occurrences since the previous notice were not logged individually"));
        }
    }

    @Test
    void anEscalationAlwaysSurfacesAndLatchesUntilItIsCleared(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            report(spine, callback(FailureFact.Severity.WARNING, 1, FIRST));
            advance(Duration.ofSeconds(2));

            escalate(spine, callback(FailureFact.Severity.ERROR, 11, FAULT));

            assertThat(entries(spine, FAULT)).as("inside the window, the escalation surfaces anyway")
                    .singleElement().satisfies(entry -> {
                        assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
                        assertThat(entry.actionLabel()).hasValue("Restart Audio Engine");
                    });
            assertThat(records(spine, CALLBACK)).hasSize(2).last()
                    .satisfies(record -> assertThat(record.getLevel()).isEqualTo(Level.SEVERE));
            assertThat(spine.funnel().isEscalated(CALLBACK)).isTrue();

            advance(Duration.ofSeconds(31));
            report(spine, callback(FailureFact.Severity.WARNING, 1, FIRST));
            assertThat(entries(spine, FIRST)).as("latched: no WARNING re-show after the window").hasSize(1);
            assertThat(spine.funnel().occurrenceTotals()).as("but counted").containsEntry(CALLBACK.key(), 13L);

            runOnFx(() -> spine.funnel().clearEscalation(CALLBACK));
            assertThat(spine.funnel().isEscalated(CALLBACK)).isFalse();
            advance(Duration.ofSeconds(31));
            report(spine, callback(FailureFact.Severity.WARNING, 1, FIRST));
            assertThat(entries(spine, FIRST)).as("cleared: the window rule again").hasSize(2);
        }
    }

    @Test
    void aFoldIsCountedWithoutANoticeOfItsOwn(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            String workerText = "The inserts of a track failed during multi-core processing and were run again for"
                    + " that block: the driver returned a short buffer. If it keeps happening, check the inserts"
                    + " on your tracks.";
            assertThat(spine.funnel().hasSeen(WORKER)).isFalse();

            runOnFx(() -> spine.funnel().foldRealTime(new RealTimeReport(FailureFact.Origin.WORKER_POOL,
                    FailureFact.Severity.WARNING, WORKER, 2, Optional.of(shortBuffer), List.of(), "worker-pool",
                    workerText, spine.funnel().openLogFolderNotice())));
            runOnFx(() -> { });

            assertThat(spine.funnel().hasSeen(WORKER)).isTrue();
            assertThat(spine.funnel().occurrenceTotals()).containsEntry(WORKER.key(), 2L);
            assertThat(entries(spine, workerText)).as("no toast, no history entry").isEmpty();
            assertThat(records(spine, WORKER)).as("but a record of its own (ruling E65)").hasSize(1);
        }
    }

    @Test
    void aFoldedSurfaceWritesOneRecordCarryingItsWeightAndNoToast(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            String workerText = "The inserts of a track failed during multi-core processing and were run again for"
                    + " that block: the driver returned a short buffer. If it keeps happening, check the inserts"
                    + " on your tracks.";

            runOnFx(() -> spine.funnel().foldRealTime(new RealTimeReport(FailureFact.Origin.WORKER_POOL,
                    FailureFact.Severity.WARNING, WORKER, 5, Optional.of(shortBuffer),
                    List.of("com.example.Insert.process:7"), "worker-pool", workerText,
                    spine.funnel().openLogFolderNotice())));
            runOnFx(() -> { });

            assertThat(records(spine, WORKER)).as("one record accounts for the folded occurrences (story 336's"
                    + " accounting)").singleElement().satisfies(record -> {
                        assertThat(record.getLevel()).as("at the report's severity").isEqualTo(Level.WARNING);
                        assertThat(record.getMessage()).isEqualTo("Failure audio.worker (WARNING) at \"worker-pool\","
                                + " occurrence 5, fingerprint audio.worker|worker-pool, sampled anchors"
                                + " com.example.Insert.process:7, folded into the notice of the same frame; no toast"
                                + " of its own (story 337, ruling E65)");
                        assertThat(record.getThrown()).isSameAs(shortBuffer);
                    });
            assertThat(spine.funnel().occurrenceTotals()).as("its occurrence count is the weight")
                    .containsEntry(WORKER.key(), 5L);
            assertThat(entries(spine, workerText)).as("no toast, no bar entry").isEmpty();
            assertThat(spine.history().getEntries()).as("nothing reached the bar at all").isEmpty();
        }
    }

    @Test
    void aReportWithoutAThrowableIsLoggedWithoutAStack(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            String configText = "This mix has 18 return buses but only 16 can receive sends, so sends to the others"
                    + " are silent. Remove return buses you do not need.";

            report(spine, new RealTimeReport(FailureFact.Origin.RT_GUARD, FailureFact.Severity.WARNING, CONFIG, 1,
                    Optional.empty(), List.of(), "render-pipeline", configText, spine.funnel().openLogFolderNotice()));

            assertThat(entries(spine, configText)).singleElement().satisfies(entry -> {
                assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING);
                assertThat(entry.actionLabel()).hasValue("Open Log Folder");
            });
            assertThat(records(spine, CONFIG)).singleElement().satisfies(record -> {
                assertThat(record.getThrown()).isNull();
                assertThat(record.getMessage()).isEqualTo("Failure audio.config (WARNING) at \"render-pipeline\","
                        + " occurrence 1, fingerprint audio.config|returnBusCap");
            });
        }
    }

    @Test
    void aWarningNoticesTailIsAWarningEntryWithTheNoticesAction(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = armed(settings)) {
            report(spine, callback(FailureFact.Severity.WARNING, 1, FIRST));
            advance(Duration.ofSeconds(5));
            report(spine, callback(FailureFact.Severity.WARNING, 6, FIRST));
            advance(Duration.ofSeconds(31));

            spine.pulse();
            runOnFx(() -> { });

            assertThat(entries(spine, FIRST)).as("the notice and its tail").hasSize(2).last().satisfies(tail -> {
                assertThat(tail.message()).startsWith(FIRST + " It happened 6 more times after that notice");
                assertThat(tail.level()).isEqualTo(NotificationLevel.WARNING);
                assertThat(tail.actionLabel()).hasValue("Restart Audio Engine");
            });
            assertThat(spine.funnelRecords()).filteredOn(record -> record.getMessage().startsWith(
                            "Failure audio.callback repeated 6 more times"))
                    .singleElement().satisfies(record -> assertThat(record.getLevel()).isEqualTo(Level.WARNING));
        }
    }

    @Test
    void theOpenLogFolderNoticeIsTheFunnelsOwnAction(@TempDir Path settings) throws Exception {
        AtomicInteger opened = new AtomicInteger();
        try (FailureSpineFixture spine = FailureSpineFixture.withClock(settings, nanos::get, wall::get,
                folder -> opened.incrementAndGet())) {
            NoticeAction notice = spine.funnel().openLogFolderNotice();

            assertThat(notice.label()).isEqualTo("Open Log Folder");
            assertThat(spine.funnel().openLogFolderNotice()).as("the same instance every time").isSameAs(notice);
            notice.action().run();
            FailureSpineFixture.await("the log folder opens", () -> opened.get() == 1);
        }
    }

    private FailureSpineFixture armed(Path settings) throws Exception {
        FailureSpineFixture spine = FailureSpineFixture.withClock(settings, nanos::get, wall::get, folder -> { });
        try {
            spine.armOnFx();
            return spine;
        } catch (Throwable failure) {
            spine.close();
            throw failure;
        }
    }

    private RealTimeReport callback(FailureFact.Severity severity, long weight, String text) {
        return new RealTimeReport(FailureFact.Origin.NATIVE_UPCALL, severity, CALLBACK, weight,
                Optional.of(shortBuffer), List.of("com.example.Driver.render:12", "com.example.Driver.copy:40"), SITE,
                text, restart);
    }

    /** One report on the FX thread, as the drain's pulse makes it, then one FX turn for the toast it posted. */
    private static void report(FailureSpineFixture spine, RealTimeReport report) throws Exception {
        runOnFx(() -> spine.funnel().reportRealTime(report));
        runOnFx(() -> { });
    }

    private static void escalate(FailureSpineFixture spine, RealTimeReport report) throws Exception {
        runOnFx(() -> spine.funnel().escalateRealTime(report));
        runOnFx(() -> { });
    }

    private void advance(Duration by) {
        nanos.addAndGet(by.toNanos());
        wall.updateAndGet(now -> now.plus(by));
    }

    private static List<NotificationEntry> entries(FailureSpineFixture spine, String text) {
        return spine.history().getEntries().stream().filter(entry -> entry.message().startsWith(text)).toList();
    }

    private static List<LogRecord> records(FailureSpineFixture spine, Fingerprint fingerprint) {
        return spine.funnelRecords().stream()
                .filter(record -> record.getMessage() != null
                        && record.getMessage().contains("fingerprint " + fingerprint.key())
                        && record.getMessage().startsWith("Failure " + fingerprint.category() + " ("))
                .toList();
    }
}
