package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.diagnostics.FailureFunnel;
import com.benesquivelmusic.daw.app.diagnostics.FingerprintRateLimiter;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ChangeListener;
import javafx.scene.control.Label;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.GUARD;
import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.awaitOnFx;
import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 336, Goals — Tests bullet 5 (Goals bullet 5): a failure thrown from
 * inside the funnel neither recurses nor
 * deadlocks. It is written to the last-resort file, which names both the
 * failure being handled and what the funnel hit, it never becomes a failure
 * the funnel counts, and the application carries on: the FX thread still
 * answers, and the next, different failure is still shown.
 *
 * <p>Five ways in: a history listener that throws while the toast is recorded
 * (the asynchronous half), a log handler that hands the funnel another
 * failure on the same thread (the synchronous half), a log handler that
 * throws an {@link Error} on the FX thread — whose failure still gets its
 * toast, though its record could not be written — a JavaFX listener on the
 * toast's label that throws, which JavaFX does not throw but hands straight
 * to the FX thread's handler, and a JavaFX listener that throws while a
 * due tail is recorded, the pulse driven past due on the test's clock
 * ({@link FailureSpineFixture#withClock}). Every listener and handler a test
 * adds is removed in a {@code finally}.</p>
 *
 * <p>The funnel is the production default wiring,
 * {@link FailureSpineFixture#launch} ({@code DawLauncher.prepareLaunch}), with
 * the {@code MainController} substitute of {@link FailureSpineFixture#armOnFx()}:
 * the {@code FxDispatcher}, {@code NotificationBar} and
 * {@code NotificationHistoryService} built as
 * {@code MainController.initializeNotificationBar} and
 * {@code DawApplication.start} build them, and the production
 * {@code installOnCurrentFxThread} and {@code armToasts} called. FX failures
 * are thrown from a raw {@code Platform.runLater}, so they reach the FX
 * thread's handler as an event handler's do.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class FailureFunnelReentrancyTest {

    private static final String TAPE_GONE = "Internal error in the background task “take storage”: the tape folder"
            + " went away. Its work may be incomplete.";
    private static final String STILL_SHOWN = "Internal error in the background task “autosave”: the autosave"
            + " folder went away. Its work may be incomplete.";

    @Test
    void aHistoryListenerThatThrowsGoesToTheLastResortAndNeverLoops(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = FailureSpineFixture.launch(settings)) {
            spine.armOnFx();
            Consumer<NotificationEntry> throwing = entry -> {
                if (entry != null && TAPE_GONE.equals(entry.message())) {
                    throw new IllegalStateException("the history listener broke");
                }
            };
            spine.history().addListener(throwing);
            try {
                failTakeStorage();
                awaitLastResortNaming(spine, "java.lang.IllegalStateException: the history listener broke");
                settleAFewTurns();

                assertLastResortNamesBoth(spine, "java.lang.UnsupportedOperationException: the tape folder went away",
                        "java.lang.IllegalStateException: the history listener broke");
                assertThat(occurrences(spine.lastResortText(), "the history listener broke"))
                        .as("written once: the listener's failure never re-entered the funnel to loop").isEqualTo(1);
                assertThat(spine.funnel().occurrenceTotals()).as("the failure being handled is counted once")
                        .containsEntry(takeStorageKey(spine), 1L);
                assertThat(spine.funnel().occurrenceTotals().keySet())
                        .as("and the listener's failure is not a failure the funnel counts")
                        .noneMatch(key -> key.contains("java.lang.IllegalStateException|" + getClass().getName()));
                assertThat(onFx(() -> Boolean.TRUE)).as("the FX thread still answers").isTrue();
            } finally {
                spine.history().removeListener(throwing);
            }

            failAutosave();
            awaitOnFx("the next, different failure's toast", () -> STILL_SHOWN.equals(spine.bar().getMessage()));
        }
    }

    @Test
    void aLogHandlerThatHandsTheFunnelAnotherFailureOnTheSameThreadGoesToTheLastResort(@TempDir Path settings)
            throws Exception {
        Logger funnelLog = Logger.getLogger(FailureFunnel.class.getName());
        Handler reentering = new LogHandler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getMessage() != null && record.getMessage().contains("on thread \"daw-take-storage\"")) {
                    Thread.currentThread().getUncaughtExceptionHandler()
                            .uncaughtException(Thread.currentThread(), new IllegalArgumentException("re-entered"));
                }
            }
        };
        try (FailureSpineFixture spine = FailureSpineFixture.launch(settings)) {
            spine.armOnFx();
            funnelLog.addHandler(reentering);

            failTakeStorage();
            awaitOnFx("the failure's toast, shown all the same", () -> TAPE_GONE.equals(spine.bar().getMessage()));

            assertLastResortNamesBoth(spine, "java.lang.UnsupportedOperationException: the tape folder went away",
                    "java.lang.IllegalArgumentException: re-entered");
            assertThat(spine.funnel().occurrenceTotals().keySet())
                    .as("the failure that re-entered is not counted: it went to the last resort")
                    .noneMatch(key -> key.contains("java.lang.IllegalArgumentException|" + getClass().getName()));
            assertThat(spine.history().getEntries()).filteredOn(entry -> entry.message().equals(TAPE_GONE))
                    .as("one toast").hasSize(1);
        } finally {
            funnelLog.removeHandler(reentering);
        }
    }

    @Test
    void aLogHandlerThatThrowsAnErrorOnTheFxThreadGoesToTheLastResortAndTheFxThreadCarriesOn(
            @TempDir Path settings) throws Exception {
        IllegalStateException fxFailure = new IllegalStateException("the clip drag failed");
        Logger funnelLog = Logger.getLogger(FailureFunnel.class.getName());
        Handler throwing = new LogHandler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getThrown() == fxFailure) {
                    throw new NoClassDefFoundError("com/example/sneaky/LicenceText");
                }
            }
        };
        try (FailureSpineFixture spine = FailureSpineFixture.launch(settings)) {
            spine.armOnFx();
            funnelLog.addHandler(throwing);

            Platform.runLater(() -> {
                throw fxFailure;
            });
            awaitLastResortNaming(spine, "java.lang.NoClassDefFoundError: com/example/sneaky/LicenceText");

            assertLastResortNamesBoth(spine, "java.lang.IllegalStateException: the clip drag failed",
                    "java.lang.NoClassDefFoundError: com/example/sneaky/LicenceText");
            assertThat(onFx(() -> Boolean.TRUE)).as("nothing left the handler: the FX thread still answers")
                    .isTrue();
            String clipDragFailed = "Internal error in the user interface: the clip drag failed. Check that your"
                    + " last change took effect.";
            FailureSpineFixture.await("the failure's own toast, though its record could not be written",
                    () -> spine.history().getEntries().stream()
                            .anyMatch(entry -> entry.message().equals(clipDragFailed)));
            assertThat(spine.history().getEntries()).filteredOn(entry -> entry.message().equals(clipDragFailed))
                    .singleElement().satisfies(entry -> assertThat(entry.action()).as("with the action").isPresent());

            funnelLog.removeHandler(throwing);
            Platform.runLater(() -> {
                throw new IllegalArgumentException("the fader drag failed");
            });
            awaitOnFx("the next, different FX failure's toast", () -> ("Internal error in the user interface: the"
                    + " fader drag failed. Check that your last change took effect.").equals(spine.bar().getMessage()));
        } finally {
            funnelLog.removeHandler(throwing);
        }
    }

    @Test
    void aListenerOnTheToastsLabelThatThrowsGoesToTheLastResortAndTheToastShowsOnce(@TempDir Path settings)
            throws Exception {
        try (FailureSpineFixture spine = FailureSpineFixture.launch(settings)) {
            spine.armOnFx();
            Label message = onFx(() -> (Label) spine.bar().getPill().lookup(".notification-message"));
            ChangeListener<String> throwing = (observable, before, now) -> {
                if (TAPE_GONE.equals(now)) {
                    throw new IllegalStateException("the label listener broke");
                }
            };
            runOnFx(() -> message.textProperty().addListener(throwing));
            try {
                failTakeStorage();
                awaitOnFx("the toast", () -> TAPE_GONE.equals(spine.bar().getMessage()));
                awaitLastResortNaming(spine, "java.lang.IllegalStateException: the label listener broke");
                settleAFewTurns();

                assertLastResortNamesBoth(spine, "java.lang.UnsupportedOperationException: the tape folder went away",
                        "java.lang.IllegalStateException: the label listener broke");
                assertThat(spine.funnel().occurrenceTotals().keySet())
                        .as("JavaFX handed the listener's failure to the FX thread's handler, which did not count it")
                        .noneMatch(key -> key.contains("java.lang.IllegalStateException|" + getClass().getName()));
                assertThat(spine.history().getEntries()).filteredOn(entry -> entry.message().equals(TAPE_GONE))
                        .as("exactly one toast").hasSize(1);
            } finally {
                runOnFx(() -> message.textProperty().removeListener(throwing));
            }

            failAutosave();
            awaitOnFx("the next, different failure's toast", () -> STILL_SHOWN.equals(spine.bar().getMessage()));
        }
    }

    @Test
    void aListenerThatThrowsWhileATailIsRecordedGoesToTheLastResortAndIsNotCounted(@TempDir Path settings)
            throws Exception {
        AtomicLong nanos = new AtomicLong(Duration.ofSeconds(1).toNanos());
        AtomicReference<Instant> wall = new AtomicReference<>(Instant.now());
        String tailPrefix = TAPE_GONE + " It happened once more after that notice";
        try (FailureSpineFixture spine = FailureSpineFixture.withClock(settings, nanos::get, wall::get,
                folder -> { })) {
            spine.armOnFx();
            // A view that shows the newest history entry in a JavaFX property, as the inspector's history does,
            // and a listener on that property that breaks on the tail: JavaFX hands its exception to the FX
            // thread's handler while the funnel is recording the tail.
            StringProperty newest = new SimpleStringProperty();
            ChangeListener<String> throwing = (observable, before, now) -> {
                if (now != null && now.startsWith(tailPrefix)) {
                    throw new IllegalArgumentException("the tail listener broke");
                }
            };
            Consumer<NotificationEntry> showNewest = entry -> {
                if (entry != null) {
                    newest.set(entry.message());
                }
            };
            runOnFx(() -> newest.addListener(throwing));
            spine.history().addListener(showNewest);
            try {
                failTakeStorage();
                nanos.addAndGet(Duration.ofSeconds(1).toNanos());
                failTakeStorage();
                awaitOnFx("the first occurrence's toast", () -> TAPE_GONE.equals(spine.bar().getMessage()));
                String key = takeStorageKey(spine);
                assertThat(spine.funnel().occurrenceTotals()).as("the second one counted: a tail is owed")
                        .containsEntry(key, 2L);

                nanos.addAndGet(FingerprintRateLimiter.WINDOW_NANOS);
                spine.pulse();
                awaitLastResortNaming(spine, "java.lang.IllegalArgumentException: the tail listener broke");
                settleAFewTurns();

                assertThat(spine.lastResortText())
                        .as("the last resort names the tail's failure, its count and what the listener threw")
                        .contains("\tWhile recording the tail of " + key + " (1 occurrences counted since its last"
                                + " notice)" + System.lineSeparator())
                        .contains("\tWhat the failure funnel hit: java.lang.IllegalArgumentException: the tail"
                                + " listener broke");
                assertThat(key).as("whose fingerprint names the tail's own class")
                        .contains("java.lang.UnsupportedOperationException");
                assertThat(spine.funnel().occurrenceTotals().keySet())
                        .as("JavaFX handed the listener's failure to the FX thread's handler, which did not count it")
                        .noneMatch(each -> each.contains("java.lang.IllegalArgumentException|"));
                assertThat(spine.history().getEntries()).filteredOn(entry -> entry.message().startsWith(tailPrefix))
                        .as("the tail is still recorded, once").hasSize(1);
                assertThat(onFx(() -> Boolean.TRUE)).as("the FX thread still answers").isTrue();
            } finally {
                spine.history().removeListener(showNewest);
                runOnFx(() -> newest.removeListener(throwing));
            }
        }
    }

    /** {@code daw-take-storage} ends with an uncaught failure; returns when it has ended. */
    private static void failTakeStorage() throws InterruptedException {
        Thread worker = Thread.ofVirtual().name("daw-take-storage").start(() -> {
            throw new UnsupportedOperationException("the tape folder went away");
        });
        assertThat(worker.join(GUARD)).isTrue();
    }

    /** {@code daw-autosave} ends with a different uncaught failure; returns when it has ended. */
    private static void failAutosave() throws InterruptedException {
        Thread worker = Thread.ofVirtual().name("daw-autosave").start(() -> {
            throw new IllegalStateException("the autosave folder went away");
        });
        assertThat(worker.join(GUARD)).isTrue();
    }

    private static String takeStorageKey(FailureSpineFixture spine) {
        return spine.funnel().occurrenceTotals().keySet().stream()
                .filter(key -> key.startsWith("thread.uncaught|java.lang.UnsupportedOperationException|"
                        + FailureFunnelReentrancyTest.class.getName() + ".lambda$failTakeStorage$"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the failure being handled was counted"));
    }

    private static void awaitLastResortNaming(FailureSpineFixture spine, String text) throws Exception {
        FailureSpineFixture.await("the last-resort record naming " + text, () -> spine.lastResortText().contains(text));
    }

    private static void assertLastResortNamesBoth(FailureSpineFixture spine, String handled, String hit)
            throws Exception {
        assertThat(spine.lastResortText())
                .as("the last resort names the failure being handled and what the funnel hit")
                .contains("\tFailure being handled: " + handled)
                .contains("\tWhat the failure funnel hit while handling it: " + hit);
    }

    /** A few FX turns, long enough for a loop, had there been one, to show. */
    private static void settleAFewTurns() throws Exception {
        for (int turn = 0; turn < 5; turn++) {
            runOnFx(() -> { });
        }
    }

    private static int occurrences(String text, String fragment) {
        int count = 0;
        for (int at = text.indexOf(fragment); at >= 0; at = text.indexOf(fragment, at + 1)) {
            count++;
        }
        return count;
    }

    /** A handler whose {@code flush} and {@code close} do nothing. */
    private abstract static class LogHandler extends Handler {
        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
