package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 322 fix round (B2) — {@link FxDispatcher#isFxThread()} is the FX-thread
 * test the view-model layer makes on the audio-thread-reachable core-signal
 * path: a compare against the thread {@code start()} recorded, never
 * {@code Platform.isFxApplicationThread()} (whose {@code Toolkit.getToolkit()}
 * is {@code static synchronized} — a monitor the RT thread must not take;
 * Audio Engine Wiring Design Book §6.1). Pins the contract: a started
 * dispatcher answers {@code true} on the FX thread and {@code false} on a
 * worker, the record survives {@code dispose()}, and a never-started
 * (pure-unit) dispatcher falls back to the toolkit's own answer on both
 * threads.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class FxDispatcherFxThreadTest {

    private static final long TIMEOUT_SECONDS = 5;

    @Test
    void aStartedDispatcherAnswersTrueOnTheFxThreadAndFalseOnAWorker() throws Exception {
        FxDispatcher dispatcher = new FxDispatcher();
        try {
            assertThat(onFx(() -> {
                dispatcher.start();
                return dispatcher.isFxThread();
            })).as("on the FX thread, right after start()").isTrue();

            assertThat(dispatcher.isFxThread()).as("the test thread is not the FX thread").isFalse();
            assertThat(onWorker(dispatcher::isFxThread)).as("a virtual-thread worker").isFalse();
            assertThat(onFx(dispatcher::isFxThread)).as("on the FX thread again").isTrue();
        } finally {
            onFx(() -> {
                dispatcher.dispose();
                return null;
            });
        }
    }

    @Test
    void theRecordedFxThreadSurvivesDispose() throws Exception {
        FxDispatcher dispatcher = new FxDispatcher();
        onFx(() -> {
            dispatcher.start();
            dispatcher.dispose();
            return null;
        });

        assertThat(onWorker(dispatcher::isFxThread))
                .as("a disposed dispatcher's VMs may still receive a last core signal off-thread").isFalse();
        assertThat(onFx(dispatcher::isFxThread)).isTrue();
    }

    @Test
    void aNeverStartedDispatcherFallsBackToTheToolkitsAnswer() throws Exception {
        FxDispatcher dispatcher = new FxDispatcher();

        assertThat(dispatcher.isFxThread())
                .as("pure-unit dispatcher, test thread")
                .isEqualTo(Platform.isFxApplicationThread())
                .isFalse();
        assertThat(onWorker(dispatcher::isFxThread)).isFalse();
        assertThat(onFx(() -> dispatcher.isFxThread() == Platform.isFxApplicationThread()))
                .as("pure-unit dispatcher, FX thread: the toolkit's answer").isTrue();
        assertThat(onFx(dispatcher::isFxThread)).isTrue();
    }

    private static <T> T onFx(Callable<T> work) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                result.set(work.call());
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("FX work completed").isTrue();
        Throwable t = thrown.get();
        if (t instanceof Error e) {
            throw e;
        }
        if (t instanceof Exception e) {
            throw e;
        }
        return result.get();
    }

    private static <T> T onWorker(Callable<T> work) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            return executor.submit(work).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }
}
