package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.vm.command.CoreTransportIntentHandler;
import com.benesquivelmusic.daw.app.ui.vm.command.StartTransportCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleLoopCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleRecordCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.TransportCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.TransportIntentHandler;
import com.benesquivelmusic.daw.core.transport.Transport;

import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 290 — verifies the §6.1 transport-bar wiring through
 * {@link TransportControlBinder}: each control's visible state follows
 * {@link TransportVM} property changes (asserted via the {@code :active}
 * pseudo-class and the label {@code text}, never pixels), and a control click
 * issues the matching {@link TransportCommand} through the command seam — not by
 * writing a control field (Control Synchronization Design Book §4.4, §6.1).
 */
class TransportBindingTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");

    @BeforeAll
    static void initToolkit() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    private static void flushFx() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    }

    private static <T> T computeOnFx(java.util.concurrent.Callable<T> work) throws InterruptedException {
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

    private static boolean isActive(Button b) {
        return b.getPseudoClassStates().contains(ACTIVE);
    }

    @Test
    void controlVisibleStateFollowsViewModelProperties() throws Exception {
        Transport transport = new Transport();
        FxDispatcher dispatcher = new FxDispatcher();
        TransportVM vm = new TransportVM(transport, dispatcher);

        Button play = computeOnFx(Button::new);
        Button record = computeOnFx(Button::new);
        Label tempo = computeOnFx(Label::new);
        ToggleButton loop = computeOnFx(ToggleButton::new);

        TransportControlBinder binder = new TransportControlBinder(vm, c -> { });
        try {
            computeOnFx(() -> {
                binder.bindPlay(play);
                binder.bindRecord(record);
                binder.bindTempoLabel(tempo);
                binder.bindLoop(loop);
                return null;
            });

            assertThat(computeOnFx(() -> isActive(play))).isFalse();
            assertThat(computeOnFx(() -> isActive(record))).isFalse();

            transport.play();
            flushFx();
            assertThat(computeOnFx(() -> isActive(play)))
                    .as("play button :active follows state == PLAYING").isTrue();
            assertThat(computeOnFx(() -> isActive(record))).isFalse();

            transport.record();
            flushFx();
            assertThat(computeOnFx(() -> isActive(record)))
                    .as("record button :active follows state == RECORDING").isTrue();
            assertThat(computeOnFx(() -> isActive(play))).isFalse();

            transport.setTempo(142.0);
            flushFx();
            assertThat(computeOnFx(tempo::getText))
                    .as("tempo label text is bound to TransportVM.tempo").isEqualTo("142.0 BPM");

            transport.setLoopEnabled(true);
            flushFx();
            assertThat(computeOnFx(() -> loop.getPseudoClassStates().contains(ACTIVE)))
                    .as("loop toggle :active follows loopRegion.enabled").isTrue();
        } finally {
            computeOnFx(() -> { binder.dispose(); return null; });
            vm.dispose();
        }
    }

    @Test
    void controlClickIssuesCommandThroughTheCommandSeam() throws Exception {
        Transport transport = new Transport();
        TransportVM vm = new TransportVM(transport, new FxDispatcher());
        List<TransportCommand> issued = new CopyOnWriteArrayList<>();

        Button play = computeOnFx(Button::new);
        Button record = computeOnFx(Button::new);
        ToggleButton loop = computeOnFx(ToggleButton::new);

        TransportControlBinder binder = new TransportControlBinder(vm, issued::add);
        try {
            computeOnFx(() -> {
                binder.bindPlay(play);
                binder.bindRecord(record);
                binder.bindLoop(loop);
                return null;
            });

            computeOnFx(() -> { play.fire(); return null; });
            computeOnFx(() -> { record.fire(); return null; });
            computeOnFx(() -> { loop.fire(); return null; });

            assertThat(issued)
                    .as("each control click raises its intent through the command sink")
                    .containsExactly(
                            new StartTransportCommand(),
                            new ToggleRecordCommand(),
                            new ToggleLoopCommand());
        } finally {
            computeOnFx(() -> { binder.dispose(); return null; });
            vm.dispose();
        }
    }

    @Test
    void tempoFieldRejectedEditSnapsBackToTheCommittedTempoWithoutThrowing() throws Exception {
        Transport transport = new Transport();
        transport.setTempo(120.0);
        FxDispatcher dispatcher = new FxDispatcher();
        TransportVM vm = new TransportVM(transport, dispatcher);

        // The production wiring: a control gesture raises a command that runs
        // against the validating handler, which throws IllegalArgumentException
        // for an out-of-range tempo (the VALIDATE phase). The binder must catch
        // that and snap back — not let it escape onto the FX thread.
        TransportIntentHandler handler = new CoreTransportIntentHandler(transport, 48_000.0);
        Consumer<TransportCommand> sink = cmd -> cmd.execute(handler);

        TextField field = computeOnFx(TextField::new);
        TransportControlBinder binder = new TransportControlBinder(vm, sink);
        try {
            computeOnFx(() -> { binder.bindTempoField(field); return null; });
            assertThat(computeOnFx(() -> field.getText())).isEqualTo("120.0");

            // 1) Out-of-range numeric entry. parseDouble succeeds, the handler
            //    rejects it with IllegalArgumentException. If the binder failed to
            //    catch that, the IAE would propagate out of the action handler and
            //    computeOnFx would capture + rethrow it, failing this test.
            computeOnFx(() -> {
                field.setText("5000");
                field.getOnAction().handle(new ActionEvent());
                return null;
            });
            assertThat(computeOnFx(() -> field.getText()))
                    .as("an out-of-range tempo snaps back to the committed value")
                    .isEqualTo("120.0");
            assertThat(transport.getTempo())
                    .as("a rejected edit never mutates the transport").isEqualTo(120.0);

            // 2) Unparseable entry: parseDouble throws NumberFormatException (an
            //    IllegalArgumentException subtype) — same snap-back, sink never run.
            computeOnFx(() -> {
                field.setText("not-a-number");
                field.getOnAction().handle(new ActionEvent());
                return null;
            });
            assertThat(computeOnFx(() -> field.getText()))
                    .as("unparseable text snaps back to the committed value")
                    .isEqualTo("120.0");
        } finally {
            computeOnFx(() -> { binder.dispose(); return null; });
            vm.dispose();
        }
    }
}
