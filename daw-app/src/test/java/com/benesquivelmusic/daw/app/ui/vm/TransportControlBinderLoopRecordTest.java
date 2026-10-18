package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleLoopRecordCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.TransportCommand;
import com.benesquivelmusic.daw.core.transport.Transport;

import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.scene.control.Button;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 328 (book §5.6) — {@link TransportControlBinder#bindLoopRecord}:
 * the Loop Rec button's {@code :active} pseudo-class is a pure function of
 * {@code TransportVM.loopRecord} (seeded at bind, following the transport's
 * loop signal afterwards), a click raises {@link ToggleLoopRecordCommand}
 * into the command sink, and dispose removes the listener.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class TransportControlBinderLoopRecordTest {

    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");

    @Test
    void activePseudoClassFollowsTheLoopRecordFactAndAClickRaisesTheCommand() throws Exception {
        Transport transport = new Transport();
        List<TransportCommand> sink = new CopyOnWriteArrayList<>();
        AtomicReference<TransportVM> vm = new AtomicReference<>();
        AtomicReference<TransportControlBinder> binder = new AtomicReference<>();
        AtomicReference<Button> button = new AtomicReference<>();
        try {
            onFx(() -> {
                vm.set(new TransportVM(transport, new FxDispatcher()));
                binder.set(new TransportControlBinder(vm.get(), sink::add));
                Button loopRecord = new Button("Loop Rec");
                binder.get().bindLoopRecord(loopRecord);
                button.set(loopRecord);
            });
            assertThat(button.get().getPseudoClassStates()).as("seeded off").doesNotContain(ACTIVE);

            transport.setLoopRecordEnabled(true);
            flushFx();
            assertThat(button.get().getPseudoClassStates()).as("lit from the VM fact").contains(ACTIVE);

            transport.setLoopRecordEnabled(false);
            flushFx();
            assertThat(button.get().getPseudoClassStates()).doesNotContain(ACTIVE);

            onFx(() -> button.get().fire());
            assertThat(sink).singleElement().isInstanceOf(ToggleLoopRecordCommand.class);
            assertThat(transport.isLoopRecordEnabled())
                    .as("the binder raises the intent; it does not poke the transport").isFalse();

            onFx(() -> binder.get().dispose());
            transport.setLoopRecordEnabled(true);
            flushFx();
            assertThat(button.get().getPseudoClassStates()).as("disposed: no longer follows").doesNotContain(ACTIVE);
        } finally {
            if (vm.get() != null) {
                onFx(() -> vm.get().dispose());
            }
        }
    }

    private static void onFx(Runnable work) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                work.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertThat(done.await(5, TimeUnit.SECONDS)).as("the FX turn ran").isTrue();
        if (failure.get() instanceof RuntimeException re) {
            throw re;
        }
        if (failure.get() instanceof Error e) {
            throw e;
        }
    }

    /** Lets the dispatcher's posted turns run: two empty FX turns after a core signal. */
    private static void flushFx() throws Exception {
        onFx(() -> { });
        onFx(() -> { });
    }
}
