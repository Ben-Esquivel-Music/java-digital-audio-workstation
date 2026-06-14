package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.vm.command.CoreTransportIntentHandler;
import com.benesquivelmusic.daw.app.ui.vm.command.TransportCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.TransportIntentHandler;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;

import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.scene.control.Button;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 293 — proves §4.4 "single-writer binding: the control never races itself",
 * the property that retires the §1.4 re-entrancy guards. A control bound to its
 * {@link TransportVM} raises an intent on a gesture; the command handler mutates the
 * core; the core's neutral signal drives the VM republish; the binder updates the
 * control <em>as a subscriber</em>. This test runs that full production round-trip
 * with an invocation counter on the command sink and asserts the round-trip
 * re-enters the gesture handler <strong>exactly once</strong> — no feedback loop, so
 * no {@code suppressEvents}/{@code updating} flag is needed by construction.
 */
class SingleWriterNoLoopTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");

    @BeforeAll
    static void initToolkit() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    @Test
    void togglingAControlBoundToItsViewModelRunsTheHandlerExactlyOnce() throws Exception {
        Transport transport = new Transport();
        FxDispatcher dispatcher = new FxDispatcher();
        TransportVM vm = new TransportVM(transport, dispatcher);
        TransportIntentHandler handler = new CoreTransportIntentHandler(transport, 48_000.0);

        AtomicInteger handlerInvocations = new AtomicInteger();
        // The full production write path: a gesture raises a command, which we COUNT
        // and then execute against the real handler — handler.start() mutates the
        // Transport, whose STATE signal drives the VM republish, which the binder
        // observes to flip the button ':active'. The counter measures re-entry.
        Consumer<TransportCommand> countingSink = command -> {
            handlerInvocations.incrementAndGet();
            command.execute(handler);
        };

        Button play = computeOnFx(Button::new);
        TransportControlBinder binder = new TransportControlBinder(vm, countingSink);
        try {
            computeOnFx(() -> {
                binder.bindPlay(play);
                return null;
            });
            assertThat(computeOnFx(() -> isActive(play)))
                    .as("play is inactive before any gesture").isFalse();

            // Exactly ONE user gesture.
            computeOnFx(() -> {
                play.fire();
                return null;
            });
            // Drain the STATE signal (dispatcher.onFx -> runLater) so the VM republishes
            // and the binder updates ':active'. Flush twice: a feedback re-fire, if one
            // existed, would have run the handler again by the second pulse.
            flushFx();
            flushFx();

            assertThat(handlerInvocations.get())
                    .as("§4.4 — the command -> Transport.play() -> STATE signal -> VM republish "
                            + "-> ':active' binding round-trip re-enters the play handler ZERO extra "
                            + "times (the control never races itself; the §1.4 guards are unnecessary)")
                    .isEqualTo(1);
            assertThat(computeOnFx(() -> isActive(play)))
                    .as("the round-trip still updated the control as a read-only subscriber")
                    .isTrue();
            assertThat(transport.getState())
                    .as("the single gesture produced exactly one mutation")
                    .isEqualTo(TransportState.PLAYING);
        } finally {
            computeOnFx(() -> {
                binder.dispose();
                return null;
            });
            vm.dispose();
        }
    }

    // ── FX helpers (mirror TransportBindingTest) ──────────────────────────────

    private static boolean isActive(Button b) {
        return b.getPseudoClassStates().contains(ACTIVE);
    }

    private static void flushFx() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    }

    private static <T> T computeOnFx(Callable<T> work) throws InterruptedException {
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
        assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        if (thrown.get() instanceof RuntimeException re) {
            throw re;
        }
        if (thrown.get() instanceof Error e) {
            throw e;
        }
        return result.get();
    }
}
