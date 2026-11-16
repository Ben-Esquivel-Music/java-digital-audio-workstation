package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.diagnostics.FailureFunnel;
import com.benesquivelmusic.daw.app.diagnostics.FingerprintRateLimiter;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;

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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 337, Goals — Tests "Pulse isolation test", its escalation half
 * (rulings E33–E36; Failure Surfacing book §3.3 "Log once per offender; toast
 * on persistence", §5.1 row 12): a continuous-channel consumer that throws on
 * every frame is one WARNING log record with its stack and the offender at
 * its first frame and nothing at its second; at its third it is one WARNING
 * toast with Open Log Folder, and its history entry; inside the next 30 s it
 * is only counted; after them it re-shows with the running total. Its sibling
 * channel drains in every frame. A FATAL pulse failure surfaces at once.
 *
 * <p>Real wiring: the dispatcher is the one {@link FailureSpineFixture#armOnFx()}
 * builds as {@code DawApplication.start} builds it — on the funnel's pulse
 * failure sink ({@code FailureSpineWiringSourceTest} pins the production call
 * site) — over the test's clocks; the test drives its pulse, the body of the
 * timer the application's dispatcher runs every frame, then lets the FX
 * thread run the toast the funnel posted. Records and history entries are
 * picked out by this test's own offender and reason, so a failure an earlier
 * test leaked in the shared fork can neither break nor satisfy them.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class PulseFailureEscalationTest {

    private static final long SECOND = Duration.ofSeconds(1).toNanos();
    private static final String REASON = "the level meter consumer broke";
    private static final String BASE = "A display in the window keeps failing to update: " + REASON
            + ". The rest of the window still updates; if something looks frozen, check the log.";
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private static final String FATAL_REASON = "the pulse ran out of an internal resource";

    private final AtomicLong nanos = new AtomicLong(SECOND);
    /** Noon today, so every time the copy shows is today's, whatever time the test runs. */
    private final AtomicReference<Instant> wall =
            new AtomicReference<>(LocalDate.now(ZONE).atTime(LocalTime.NOON).atZone(ZONE).toInstant());

    @Test
    void aConsumerThatKeepsFailingIsLoggedOnceShownAtItsThirdFrameAndReShownAfterTheWindow(@TempDir Path settings)
            throws Exception {
        assertThat(FingerprintRateLimiter.ISOLATED_CONSUMER_THRESHOLD).as("the frames below count to the third")
                .isEqualTo(3);
        try (FailureSpineFixture spine = FailureSpineFixture.withClock(settings, nanos::get, wall::get,
                folder -> { })) {
            spine.armOnFx();
            Instant firstSeen = wall.get();
            AtomicInteger siblingDrains = new AtomicInteger();
            FxDispatcher dispatcher = spine.dispatcher();
            FxDispatcher.ContinuousChannel<String> failing =
                    onFx(() -> dispatcher.openContinuous(new ThrowingMeterConsumer()));
            FxDispatcher.ContinuousDoubleChannel sibling =
                    onFx(() -> dispatcher.openContinuousDouble(value -> siblingDrains.incrementAndGet()));

            frame(spine, failing, sibling);
            List<LogRecord> afterFirst = ourRecords(spine);
            assertThat(afterFirst).as("the first frame: one WARNING record with the stack and the offender")
                    .singleElement().satisfies(record -> {
                        assertThat(record.getLevel()).isEqualTo(Level.WARNING);
                        assertThat(record.getMessage())
                                .startsWith("Failure " + FailureFunnel.UI_PULSE + " (WARNING) on thread \"")
                                .contains("occurrence 1",
                                        "offender CHANNEL_DRAIN " + ThrowingMeterConsumer.class.getName());
                        assertThat(record.getThrown()).isInstanceOf(IllegalStateException.class).hasMessage(REASON);
                    });
            assertThat(spine.logText()).as("the record reached the log file, stack included")
                    .contains("offender CHANNEL_DRAIN " + ThrowingMeterConsumer.class.getName())
                    .contains("java.lang.IllegalStateException: " + REASON);
            assertThat(ourEntries(spine)).as("a one-off pulse failure is log-only").isEmpty();

            frame(spine, failing, sibling);
            assertThat(ourRecords(spine)).as("the second frame: counted, nothing new").hasSize(1);
            assertThat(ourEntries(spine)).isEmpty();

            frame(spine, failing, sibling);
            assertThat(ourEntries(spine)).as("the third frame: one WARNING entry with Open Log Folder")
                    .singleElement().satisfies(entry -> {
                        assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING);
                        assertThat(entry.message()).isEqualTo(BASE);
                        assertThat(entry.actionLabel()).hasValue("Open Log Folder");
                        assertThat(entry.action()).isPresent();
                    });
            assertThat(ourRecords(spine)).as("and its record").hasSize(2)
                    .last().satisfies(record -> {
                        assertThat(record.getLevel()).isEqualTo(Level.WARNING);
                        assertThat(record.getMessage()).contains("occurrence 3",
                                "1 occurrences since the previous notice were not logged individually");
                    });

            for (int frame = 4; frame <= 20; frame++) {
                frame(spine, failing, sibling);
            }
            assertThat(ourEntries(spine)).as("inside the window: counted only").hasSize(1);
            assertThat(ourRecords(spine)).hasSize(2);

            // The consumer keeps failing frame after frame, so it is never quiet for a window: no tail is owed.
            advance(Duration.ofSeconds(29));
            frame(spine, failing, sibling);
            assertThat(ourEntries(spine)).as("29 s after the notice: still inside the window").hasSize(1);
            advance(Duration.ofSeconds(1));
            frame(spine, failing, sibling);
            assertThat(ourEntries(spine)).as("30 s after the notice: re-shown with the running total").hasSize(2)
                    .last().satisfies(entry -> {
                        assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING);
                        assertThat(entry.message()).isEqualTo(BASE + " It has happened 22 times since "
                                + CLOCK.format(firstSeen.atZone(ZONE)) + ".");
                    });
            assertThat(ourRecords(spine)).hasSize(3).last().satisfies(record -> assertThat(record.getMessage())
                    .contains("occurrence 22", "18 occurrences since the previous notice were not logged individually"));
            assertThat(siblingDrains).as("the sibling channel drained in every frame").hasValue(22);
            assertThat(spine.lastResortText()).as("nothing reached the last resort").isEmpty();
        }
    }

    @Test
    void aFatalPulseFailureSurfacesAtOnce(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = FailureSpineFixture.withClock(settings, nanos::get, wall::get,
                folder -> { })) {
            spine.armOnFx();
            FxDispatcher dispatcher = spine.dispatcher();
            runOnFx(() -> dispatcher.onFx("fatal keyed work", () -> {
                throw new InternalError(FATAL_REASON);
            }));

            spine.pulse();
            runOnFx(() -> { });

            assertThat(spine.history().getEntries())
                    .filteredOn(entry -> entry.message().contains(FATAL_REASON))
                    .as("a VirtualMachineError is FATAL and surfaces at its first occurrence (story 337, ruling E35)")
                    .singleElement().satisfies(entry -> {
                        assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
                        assertThat(entry.message()).startsWith("Serious internal error:");
                        assertThat(entry.actionLabel()).hasValue("Open Log Folder");
                    });
            assertThat(spine.funnelRecords()).filteredOn(record -> record.getThrown() != null
                            && FATAL_REASON.equals(record.getThrown().getMessage()))
                    .singleElement().satisfies(record -> {
                        assertThat(record.getLevel()).isEqualTo(Level.SEVERE);
                        assertThat(record.getMessage()).contains("(FATAL)", "offender KEYED_WORK key fatal keyed work");
                    });
        }
    }

    /** One frame: both channels published, one pulse, then one FX turn so a toast the funnel posted has shown. */
    private static void frame(FailureSpineFixture spine, FxDispatcher.ContinuousChannel<String> failing,
                              FxDispatcher.ContinuousDoubleChannel sibling) throws Exception {
        failing.publish("-12 dBFS");
        sibling.publish(0.25);
        spine.pulse();
        runOnFx(() -> { });
    }

    private void advance(Duration by) {
        nanos.addAndGet(by.toNanos());
        wall.updateAndGet(now -> now.plus(by));
    }

    private static List<LogRecord> ourRecords(FailureSpineFixture spine) {
        return spine.funnelRecords().stream()
                .filter(record -> record.getMessage().contains(ThrowingMeterConsumer.class.getName()))
                .toList();
    }

    private static List<NotificationEntry> ourEntries(FailureSpineFixture spine) {
        return spine.history().getEntries().stream().filter(entry -> entry.message().contains(REASON)).toList();
    }

    /** A meter consumer that throws on every value, from one line, so every failure shares one fingerprint. */
    private static final class ThrowingMeterConsumer implements Consumer<String> {
        @Override
        public void accept(String level) {
            throw new IllegalStateException(REASON);
        }
    }
}
