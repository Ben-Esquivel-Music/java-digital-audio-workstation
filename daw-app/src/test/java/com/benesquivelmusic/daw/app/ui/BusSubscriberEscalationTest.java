package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.diagnostics.FailureFunnel;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.sdk.event.TransportEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 337, Goals — Tests "Bus escalation test" (rulings E35–E38; Failure
 * Surfacing book §3.3, §5.1 row 12): a subscriber that throws on every event
 * of the application's bus is one WARNING log record at its first failure, no
 * toast; at its third it becomes a surfaced fact — one WARNING toast and
 * history entry naming the event type, with Open Log Folder — while a sibling
 * subscriber keeps receiving every event.
 *
 * <p>Real wiring: the bus is built as {@code DawApplication.start} builds it,
 * {@code DefaultEventBus.builder(failureFunnel.busFailureSink())}
 * ({@code FailureSpineWiringSourceTest} pins that call site), on a funnel
 * over the test's clocks armed on a dispatcher and notification bar
 * ({@link FailureSpineFixture#armOnFx()}). Handlers are delivered on the bus's
 * own workers, so each burst ends with a marker event the test awaits: a
 * subscription delivers in order and reports a failure before it takes its
 * next event, so once both subscribers saw the marker every report for the
 * events before it was made. Records and history entries are picked out by
 * this test's own reason.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class BusSubscriberEscalationTest {

    private static final long GUARD_SECONDS = 20;
    private static final String REASON = "the take list could not follow the transport";
    private static final String NOTICE = "Part of the application keeps failing to handle “Started” updates: "
            + REASON + ". Everything else still receives them; check the log for details.";
    private static final long MARKER_POSITION = Long.MAX_VALUE;
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final AtomicLong nanos = new AtomicLong(Duration.ofSeconds(1).toNanos());
    private final AtomicReference<Instant> wall =
            new AtomicReference<>(LocalDate.now(ZONE).atTime(LocalTime.NOON).atZone(ZONE).toInstant());

    @Test
    void aSubscriberThatKeepsFailingIsLoggedOnceThenSurfacedAtItsThirdFailureWhileItsSiblingKeepsReceiving(
            @TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = FailureSpineFixture.withClock(settings, nanos::get, wall::get,
                folder -> { })) {
            spine.armOnFx();
            DefaultEventBus bus = DefaultEventBus.builder(spine.funnel().busFailureSink())
                    .uiExecutor(spine.dispatcher()::onFx)
                    .build();
            try {
                AtomicInteger siblingReceived = new AtomicInteger();
                AtomicReference<CountDownLatch> failingSawMarker = new AtomicReference<>(new CountDownLatch(1));
                AtomicReference<CountDownLatch> siblingSawMarker = new AtomicReference<>(new CountDownLatch(1));
                bus.on(TransportEvent.Started.class, event -> {
                    if (event.positionFrames() == MARKER_POSITION) {
                        failingSawMarker.get().countDown();
                        return;
                    }
                    throw new IllegalStateException(REASON);
                });
                bus.on(TransportEvent.Started.class, event -> {
                    if (event.positionFrames() == MARKER_POSITION) {
                        siblingSawMarker.get().countDown();
                    } else {
                        siblingReceived.incrementAndGet();
                    }
                });

                publishThenAwaitTheMarker(bus, 1, failingSawMarker, siblingSawMarker);
                runOnFx(() -> { });
                assertThat(ourRecords(spine)).as("the first failure: one WARNING record, stack and offender")
                        .singleElement().satisfies(record -> {
                            assertThat(record.getLevel()).isEqualTo(Level.WARNING);
                            assertThat(record.getMessage())
                                    .startsWith("Failure " + FailureFunnel.BUS_SUBSCRIBER + " (WARNING) on thread \"")
                                    .contains("occurrence 1", "offender Started@", "(HANDLER)");
                            assertThat(record.getThrown()).hasMessage(REASON);
                        });
                assertThat(ourEntries(spine)).as("and no toast").isEmpty();

                publishThenAwaitTheMarker(bus, 2, failingSawMarker, siblingSawMarker);
                FailureSpineFixture.awaitOnFx("the third failure's notice reaches the history",
                        () -> !ourEntries(spine).isEmpty());
                runOnFx(() -> { });

                assertThat(ourEntries(spine)).as("one WARNING entry naming the event type, with Open Log Folder")
                        .singleElement().satisfies(entry -> {
                            assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING);
                            assertThat(entry.message()).isEqualTo(NOTICE);
                            assertThat(entry.actionLabel()).hasValue("Open Log Folder");
                        });
                assertThat(ourRecords(spine)).as("the first record and the notice's").hasSize(2);
                assertThat(siblingReceived).as("the sibling received every event").hasValue(3);
            } finally {
                bus.close();
            }
        }
    }

    private static void publishThenAwaitTheMarker(DefaultEventBus bus, int events,
                                                  AtomicReference<CountDownLatch> failingSawMarker,
                                                  AtomicReference<CountDownLatch> siblingSawMarker)
            throws InterruptedException {
        CountDownLatch failing = new CountDownLatch(1);
        CountDownLatch sibling = new CountDownLatch(1);
        failingSawMarker.set(failing);
        siblingSawMarker.set(sibling);
        for (int i = 0; i < events; i++) {
            bus.publish(new TransportEvent.Started(i, Instant.EPOCH));
        }
        bus.publish(new TransportEvent.Started(MARKER_POSITION, Instant.EPOCH));
        assertThat(failing.await(GUARD_SECONDS, TimeUnit.SECONDS))
                .as("the failing subscription keeps receiving after every failure").isTrue();
        assertThat(sibling.await(GUARD_SECONDS, TimeUnit.SECONDS)).as("the sibling saw the marker").isTrue();
    }

    private static List<LogRecord> ourRecords(FailureSpineFixture spine) {
        return spine.funnelRecords().stream()
                .filter(record -> record.getThrown() != null && REASON.equals(record.getThrown().getMessage()))
                .toList();
    }

    private static List<NotificationEntry> ourEntries(FailureSpineFixture spine) {
        return spine.history().getEntries().stream().filter(entry -> entry.message().contains(REASON)).toList();
    }
}
