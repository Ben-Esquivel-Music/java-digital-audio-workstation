package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.sdk.event.DispatchMode;
import com.benesquivelmusic.daw.sdk.event.EventBus;
import com.benesquivelmusic.daw.sdk.event.TransportEvent;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 289 — proves the production wiring change: building the
 * {@link DefaultEventBus} with {@code uiExecutor(fxDispatcher::onFx)} routes
 * every {@link DispatchMode#ON_UI_THREAD} subscriber through the one
 * {@link FxDispatcher} marshalling seam, so the handler runs on the JavaFX
 * Application Thread (Control Synchronization Design Book §4.2, §4.5; mirrors
 * {@code DawApplication}'s bus build).
 *
 * <p>The assertion is made through the bus API plus an FX-thread latch — never
 * via {@code Event.getSource()} identity (the bus rewrites source on bubble, and
 * a {@link com.benesquivelmusic.daw.sdk.event.DawEvent DawEvent} is not a JavaFX
 * {@code Event} anyway). Because the bus's {@code runAndAwait} blocks a worker
 * until the FX task runs, the dispatcher is started and the FX toolkit is live so
 * {@code onFx}'s {@code Platform.runLater} actually executes; otherwise that
 * worker would deadlock.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class BusUiDispatchRoutesThroughDispatcherTest {

    @Test
    void onUiThreadSubscriberRunsOnTheFxThreadThroughTheDispatcherSeam()
            throws Exception {
        FxDispatcher dispatcher = new FxDispatcher();
        startOnFxAndWait(dispatcher);

        EventBus bus = DefaultEventBus.builder()
                .uiExecutor(dispatcher::onFx)
                .build();
        try {
            AtomicBoolean ranOnFxThread = new AtomicBoolean(false);
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            CountDownLatch handled = new CountDownLatch(1);

            bus.on(TransportEvent.Started.class, DispatchMode.ON_UI_THREAD, _ -> {
                try {
                    ranOnFxThread.set(Platform.isFxApplicationThread());
                } catch (Throwable t) {
                    thrown.set(t);
                } finally {
                    handled.countDown();
                }
            });

            // Publish from the test (non-FX) thread; ON_UI_THREAD delivery must
            // marshal the handler onto the FX thread via dispatcher::onFx.
            bus.publish(new TransportEvent.Started(0L, Instant.now()));

            assertThat(handled.await(5, TimeUnit.SECONDS))
                    .as("the ON_UI_THREAD subscriber must run within 5s — a "
                            + "deadlock here would mean onFx failed to post to a "
                            + "live FX thread")
                    .isTrue();
            if (thrown.get() != null) {
                throw new AssertionError(thrown.get());
            }
            assertThat(ranOnFxThread.get())
                    .as("the ON_UI_THREAD subscriber must run on the FX thread "
                            + "through the FxDispatcher seam")
                    .isTrue();
        } finally {
            bus.close();
            disposeOnFxAndWait(dispatcher);
        }
    }

    private static void startOnFxAndWait(FxDispatcher dispatcher) throws InterruptedException {
        runOnFxAndWait(dispatcher::start);
    }

    private static void disposeOnFxAndWait(FxDispatcher dispatcher) throws InterruptedException {
        runOnFxAndWait(dispatcher::dispose);
    }

    private static void runOnFxAndWait(Runnable action) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS))
                .as("FX lifecycle action must complete within 5s")
                .isTrue();
    }
}
