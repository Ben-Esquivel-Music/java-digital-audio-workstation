package com.benesquivelmusic.daw.app.logging;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exit hold's wait (story 336, Goals bullet 9), driven without an
 * {@link ExitHoldingLogManager} instance —
 * constructing one would register a second JDK shutdown hook in this JVM.
 * The shutdown hooks start concurrently in no fixed order, so the hold waits
 * while the exit-wait hook has not started, then joins it; a hook still
 * unstarted when the hold runs out counts as expired and nothing throws. The
 * child-JVM test {@code ExitWaitLinesReachTheLogTest} proves the hold on the
 * JDK's real shutdown path.
 */
class ExitHoldAwaitsTheExitWaitTest {

    private static final Duration SHORT_HOLD = Duration.ofMillis(300);

    @Test
    void theHoldIsTheExitWaitsLimitPlusFiveSeconds() {
        assertThat(ExitHoldingLogManager.HOLD_MARGIN).isEqualTo(Duration.ofSeconds(5));
        assertThat(ExitHoldingLogManager.CLEANER_CLASS_NAME).isEqualTo("java.util.logging.LogManager$Cleaner");
        assertThat(ExitHoldingLogManager.expiredLine(Duration.ofSeconds(35))).isEqualTo("The log is closing 35 s"
                + " after the application began to exit, and the wait for saves and checkpoints has not returned;"
                + " anything it logs after this line is lost.");
        assertThat(ExitHoldingLogManager.expiredLine(Duration.ofMillis(1_500))).startsWith("The log is closing 1500 ms");
        assertThat(ExitHoldingLogManager.CLOSED_LINE).isEqualTo("Log closed at application exit.");
    }

    @Test
    void withNoExitWaitRegisteredTheResetIsNotHeld() {
        long start = System.nanoTime();

        ExitHoldingLogManager.ExitWait outcome = ExitHoldingLogManager.awaitExitWait(Optional.empty(), System::nanoTime);

        assertThat(outcome).isEqualTo(new ExitHoldingLogManager.ExitWait.NotRegistered());
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(200));
    }

    @Test
    void aHookThatEndsReleasesTheHold() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        Thread hook = Thread.ofPlatform().name("daw-exit-write-wait").unstarted(() -> awaitQuietly(release));
        Thread starter = Thread.ofVirtual().start(() -> {
            sleepQuietly(50);
            hook.start();
            sleepQuietly(100);
            release.countDown();
        });

        ExitHoldingLogManager.ExitWait outcome = ExitHoldingLogManager.awaitExitWait(
                Optional.of(new ExitHoldingLogManager.Hold(hook, Duration.ofSeconds(10))), System::nanoTime);

        assertThat(outcome).isEqualTo(new ExitHoldingLogManager.ExitWait.Released());
        assertThat(hook.getState()).isEqualTo(Thread.State.TERMINATED);
        starter.join();
    }

    @Test
    void aHookThatOutlivesTheHoldExpiresIt() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        Thread hook = Thread.ofPlatform().name("daw-exit-write-wait").start(() -> awaitQuietly(release));
        try {
            long start = System.nanoTime();

            ExitHoldingLogManager.ExitWait outcome = ExitHoldingLogManager.awaitExitWait(
                    Optional.of(new ExitHoldingLogManager.Hold(hook, SHORT_HOLD)), System::nanoTime);

            assertThat(outcome).isEqualTo(new ExitHoldingLogManager.ExitWait.Expired(SHORT_HOLD));
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(SHORT_HOLD)
                    .isLessThan(Duration.ofSeconds(10));
        } finally {
            release.countDown();
            hook.join();
        }
    }

    @Test
    void aHookThatNeverStartsExpiresTheHoldWithoutThrowing() {
        Thread hook = Thread.ofPlatform().name("daw-exit-write-wait").unstarted(() -> { });

        ExitHoldingLogManager.ExitWait outcome = ExitHoldingLogManager.awaitExitWait(
                Optional.of(new ExitHoldingLogManager.Hold(hook, SHORT_HOLD)), System::nanoTime);

        assertThat(outcome).isEqualTo(new ExitHoldingLogManager.ExitWait.Expired(SHORT_HOLD));
        assertThat(hook.getState()).isEqualTo(Thread.State.NEW);
    }

    @Test
    void anInterruptedHoldReturnsWithTheInterruptRestored() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        Thread hook = Thread.ofPlatform().name("daw-exit-write-wait").start(() -> awaitQuietly(release));
        try {
            Thread.currentThread().interrupt();

            ExitHoldingLogManager.ExitWait outcome = ExitHoldingLogManager.awaitExitWait(
                    Optional.of(new ExitHoldingLogManager.Hold(hook, Duration.ofSeconds(10))), System::nanoTime);

            assertThat(outcome).isEqualTo(new ExitHoldingLogManager.ExitWait.Interrupted());
            assertThat(Thread.interrupted()).as("the interrupt status was restored").isTrue();
        } finally {
            release.countDown();
            hook.join();
        }
    }

    @Test
    void aRegistrationHoldsTheHookForItsLimitPlusTheMargin() {
        Optional<ExitHoldingLogManager.Hold> before = ExitHoldingLogManager.registeredHold();
        Thread hook = Thread.ofPlatform().name("daw-exit-write-wait").unstarted(() -> { });
        try {
            ExitHoldingLogManager.holdShutdownResetFor(hook, Duration.ofSeconds(2));

            assertThat(ExitHoldingLogManager.registeredHold())
                    .hasValue(new ExitHoldingLogManager.Hold(hook, Duration.ofSeconds(7)));
        } finally {
            ExitHoldingLogManager.restoreRegisteredHold(before);
        }
        assertThat(ExitHoldingLogManager.registeredHold()).as("put back as the test found it, none included")
                .isEqualTo(before);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
