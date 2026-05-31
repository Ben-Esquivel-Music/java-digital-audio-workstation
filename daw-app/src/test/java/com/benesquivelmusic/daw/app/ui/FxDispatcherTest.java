package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 289 — verifies the {@link FxDispatcher} discrete marshalling jobs
 * (Control Synchronization Design Book §4.5): {@code onFx(Runnable)} runs work
 * on the JavaFX Application Thread, and {@code onFx(key, work)} coalesces N
 * same-key posts within one pulse into a single execution (latest-wins).
 *
 * <p>Assertions that must hold <em>on</em> the FX thread are captured into an
 * {@link AtomicReference}, the latch is counted down in a {@code finally}, and
 * the throwable is rethrown on the test thread — a JavaFX headless pitfall is
 * that an assertion thrown inside a {@code Platform.runLater} body is swallowed,
 * so a failing assert would otherwise pass green. The coalescing assertions
 * check an invocation <em>counter</em>, never pixels (per the story).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class FxDispatcherTest {

    private static final long TIMEOUT_SECONDS = 5;

    /** Runs {@code action} on the FX thread and blocks until it completes. */
    private static void runOnFxAndWait(Runnable action) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("FX action must complete within %ds", TIMEOUT_SECONDS)
                .isTrue();
        rethrow(thrown.get());
    }

    private static void rethrow(Throwable t) {
        if (t instanceof RuntimeException re) {
            throw re;
        }
        if (t instanceof Error e) {
            throw e;
        }
        if (t != null) {
            throw new AssertionError(t);
        }
    }

    @Test
    void onFxRunnableRunsWorkOnTheFxThread() throws InterruptedException {
        FxDispatcher dispatcher = new FxDispatcher();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean ran = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        // Post from the test (non-FX) thread; the work must hop to the FX thread.
        dispatcher.onFx(() -> {
            try {
                // Captured + rethrown on the test thread — an assert thrown here
                // would otherwise be swallowed and the test would pass green.
                assertThat(Platform.isFxApplicationThread())
                        .as("onFx(Runnable) must run work on the FX thread")
                        .isTrue();
                ran.set(true);
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                latch.countDown();
            }
        });

        assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("onFx work must run within %ds", TIMEOUT_SECONDS)
                .isTrue();
        rethrow(thrown.get());
        assertThat(ran).as("onFx work must have executed").isTrue();
    }

    @Test
    void runOnFxWithPreferredDispatcherRunsWorkOnTheFxThread() throws InterruptedException {
        // The injection-aware postFx helpers (MixerView, BrowserPanel,
        // MainController, …) all delegate here; a non-null preferred dispatcher
        // must be used to hop the work onto the FX thread (and never NPE, the
        // null-tolerance the per-class helpers relied on).
        FxDispatcher preferred = new FxDispatcher();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean ran = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        FxDispatcher.runOnFx(preferred, () -> {
            try {
                assertThat(Platform.isFxApplicationThread())
                        .as("runOnFx(preferred, work) must run work on the FX thread")
                        .isTrue();
                ran.set(true);
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                latch.countDown();
            }
        });

        assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("runOnFx work must run within %ds", TIMEOUT_SECONDS)
                .isTrue();
        rethrow(thrown.get());
        assertThat(ran).as("runOnFx work must have executed").isTrue();
    }

    @Test
    void keyedPostsWithinOnePulseCoalesceToASingleLatestRun() throws InterruptedException {
        FxDispatcher dispatcher = new FxDispatcher();
        AtomicInteger invocations = new AtomicInteger(0);
        AtomicInteger lastValueSeen = new AtomicInteger(-1);
        Object key = new Object();
        int posts = 50;

        // All puts and the single drain happen on the FX thread in one runnable,
        // so no pulse can interleave between them: the N same-key posts must
        // collapse to exactly one execution of the latest runnable.
        runOnFxAndWait(() -> {
            for (int i = 0; i < posts; i++) {
                int value = i;
                dispatcher.onFx(key, () -> {
                    invocations.incrementAndGet();
                    lastValueSeen.set(value);
                });
            }
            dispatcher.pulse();
        });

        assertThat(invocations.get())
                .as("%d same-key posts within one pulse must coalesce to one run", posts)
                .isEqualTo(1);
        assertThat(lastValueSeen.get())
                .as("the coalesced run must be the latest-posted runnable")
                .isEqualTo(posts - 1);
    }

    @Test
    void distinctKeysEachRunOncePerPulse() throws InterruptedException {
        FxDispatcher dispatcher = new FxDispatcher();
        AtomicInteger runsA = new AtomicInteger(0);
        AtomicInteger runsB = new AtomicInteger(0);
        AtomicInteger runsC = new AtomicInteger(0);

        runOnFxAndWait(() -> {
            // Two posts each for A and B (coalesce to one apiece), one for C.
            dispatcher.onFx("A", runsA::incrementAndGet);
            dispatcher.onFx("A", runsA::incrementAndGet);
            dispatcher.onFx("B", runsB::incrementAndGet);
            dispatcher.onFx("B", runsB::incrementAndGet);
            dispatcher.onFx("C", runsC::incrementAndGet);
            dispatcher.pulse();
        });

        assertThat(runsA.get()).as("key A runs once").isEqualTo(1);
        assertThat(runsB.get()).as("key B runs once").isEqualTo(1);
        assertThat(runsC.get()).as("key C runs once").isEqualTo(1);
    }

    @Test
    void keyedWorkDoesNotRunUntilTheNextPulse() throws InterruptedException {
        FxDispatcher dispatcher = new FxDispatcher();
        AtomicInteger runs = new AtomicInteger(0);

        runOnFxAndWait(() -> {
            dispatcher.onFx("k", runs::incrementAndGet);
            // No pulse yet — the work must be pending, not executed.
            assertThat(runs.get())
                    .as("keyed work must not run before a pulse")
                    .isZero();
            dispatcher.pulse();
            assertThat(runs.get())
                    .as("keyed work runs on the pulse")
                    .isEqualTo(1);
            // A second pulse with nothing pending must not re-run it.
            dispatcher.pulse();
            assertThat(runs.get())
                    .as("keyed work runs exactly once, not on every later pulse")
                    .isEqualTo(1);
        });
    }
}
