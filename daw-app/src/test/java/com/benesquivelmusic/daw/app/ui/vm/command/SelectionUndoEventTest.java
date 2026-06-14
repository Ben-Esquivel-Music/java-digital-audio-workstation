package com.benesquivelmusic.daw.app.ui.vm.command;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.vm.HistoryControlBinder;
import com.benesquivelmusic.daw.app.ui.vm.HistoryVM;
import com.benesquivelmusic.daw.app.ui.vm.SelectionVM;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.event.DispatchMode;
import com.benesquivelmusic.daw.sdk.event.EventBus;
import com.benesquivelmusic.daw.sdk.event.UiEvent;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.MenuItem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 292 — verifies the command&rarr;handler&rarr;event path for the selection
 * and history surfaces: a {@link SelectTrackCommand} run through
 * {@link CoreSelectionIntentHandler} announces {@link UiEvent.SelectionChanged};
 * an {@link UndoCommand} run through {@link CoreHistoryIntentHandler} announces
 * {@link UiEvent.UndoStateChanged} <em>and</em> actually undoes (Control
 * Synchronization Design Book §5.1, §5.5, §5.6, §6.9). Events are asserted via
 * their bus payload — never {@code Event.getSource()} identity, never rendered
 * output — mirroring {@code ChannelCommandEventTest}.
 *
 * <p>The handler's VALIDATE gate is proven discriminating: a re-issued identical
 * selection announces nothing, and the undo/redo controls bound through
 * {@link HistoryControlBinder} track {@link HistoryVM} after a flush.</p>
 */
class SelectionUndoEventTest {

    private static final long TIMEOUT_SECONDS = 5;

    private DefaultEventBus bus;

    @BeforeAll
    static void initToolkit() throws InterruptedException {
        startToolkit();
    }

    @BeforeEach
    void installBus() {
        bus = new DefaultEventBus();
        EventBusPublisher.setDefault(bus);
    }

    @AfterEach
    void clearBus() {
        EventBusPublisher.setDefault(null);
        bus.close();
    }

    @Test
    void selectTrackCommandAnnouncesSelectionChanged() throws InterruptedException {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track trackA = project.createAudioTrack("A");
        SelectionVM selection = new SelectionVM();
        CoreSelectionIntentHandler handler = new CoreSelectionIntentHandler(selection);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<UiEvent.SelectionChanged> ref = new AtomicReference<>();
        try (EventBus.Subscription s = bus.on(UiEvent.SelectionChanged.class,
                DispatchMode.ON_CALLER_THREAD, e -> { ref.set(e); latch.countDown(); })) {

            runOnFx(() -> new SelectTrackCommand(trackA).execute(handler));

            assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("selecting a track announces SelectionChanged").isTrue();
            assertThat(ref.get())
                    .as("the announced event is a SelectionChanged payload")
                    .isInstanceOf(UiEvent.SelectionChanged.class);
            assertThat(ref.get().timestamp())
                    .as("SelectionChanged carries a wall-clock instant").isNotNull();
        } finally {
            selection.dispose();
        }
    }

    @Test
    void undoCommandAnnouncesUndoStateChangedAndUndoes() throws InterruptedException {
        UndoManager manager = new UndoManager();
        manager.execute(new FakeAction("Add Track")); // make an undo available
        CoreHistoryIntentHandler handler = new CoreHistoryIntentHandler(manager);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<UiEvent.UndoStateChanged> ref = new AtomicReference<>();
        try (EventBus.Subscription s = bus.on(UiEvent.UndoStateChanged.class,
                DispatchMode.ON_CALLER_THREAD, e -> { ref.set(e); latch.countDown(); })) {

            runOnFx(() -> new UndoCommand().execute(handler));

            assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("an undo announces UndoStateChanged").isTrue();
            assertThat(ref.get()).isInstanceOf(UiEvent.UndoStateChanged.class);
            assertThat(manager.canUndo())
                    .as("the undo actually ran — nothing left to undo")
                    .isFalse();
        }
    }

    @Test
    void idempotentSelectTrackDoesNotReAnnounce() throws InterruptedException {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track trackA = project.createAudioTrack("A");
        SelectionVM selection = new SelectionVM();
        CoreSelectionIntentHandler handler = new CoreSelectionIntentHandler(selection);

        AtomicInteger count = new AtomicInteger();
        CountDownLatch firstAnnounce = new CountDownLatch(1);
        try (EventBus.Subscription s = bus.on(UiEvent.SelectionChanged.class,
                DispatchMode.ON_CALLER_THREAD, e -> { count.incrementAndGet(); firstAnnounce.countDown(); })) {

            // Two identical selections: the second is gated out by VALIDATE.
            runOnFx(() -> {
                new SelectTrackCommand(trackA).execute(handler);
                new SelectTrackCommand(trackA).execute(handler);
            });

            assertThat(firstAnnounce.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("the first selection announces").isTrue();
            // Give any erroneous second announce a chance to arrive before counting.
            flushFx();
            assertThat(count.get())
                    .as("the idempotent re-selection announces no second SelectionChanged")
                    .isEqualTo(1);
        } finally {
            selection.dispose();
        }
    }

    @Test
    void undoRedoControlsFollowHistoryVM() throws InterruptedException {
        FxDispatcher dispatcher = new FxDispatcher();
        UndoManager manager = new UndoManager(); // empty
        HistoryVM vm = new HistoryVM(manager, dispatcher);
        CoreHistoryIntentHandler handler = new CoreHistoryIntentHandler(manager);
        HistoryControlBinder binder = new HistoryControlBinder(vm, cmd -> cmd.execute(handler));

        AtomicReference<Button> undoBtnRef = new AtomicReference<>();
        AtomicReference<Button> redoBtnRef = new AtomicReference<>();
        AtomicReference<MenuItem> undoItemRef = new AtomicReference<>();
        try {
            // Build + bind the controls on FX; capture the empty-manager state.
            ControlSnapshot initial = callOnFx(() -> {
                Button undoBtn = new Button();
                Button redoBtn = new Button();
                MenuItem undoItem = new MenuItem();
                undoBtnRef.set(undoBtn);
                redoBtnRef.set(redoBtn);
                undoItemRef.set(undoItem);

                binder.bindUndoButton(undoBtn);
                binder.bindRedoButton(redoBtn);
                binder.bindUndoMenuItem(undoItem);
                return snapshot(undoBtn, redoBtn, undoItem);
            });
            assertThat(initial.undoDisabled())
                    .as("an empty manager disables the undo button").isTrue();

            // Execute an action; HistoryVM republishes via dispatcher.onFx, so flush.
            runOnFx(() -> manager.execute(new FakeAction("Add Track")));
            flushFx();

            ControlSnapshot afterExecute =
                    callOnFx(() -> snapshot(undoBtnRef.get(), redoBtnRef.get(), undoItemRef.get()));
            assertThat(afterExecute.undoDisabled())
                    .as("an available undo enables the undo button").isFalse();
            assertThat(afterExecute.undoItemText())
                    .as("the undo menu item text follows the next-undo label")
                    .isEqualTo("Undo Add Track");
            assertThat(afterExecute.redoDisabled())
                    .as("with nothing undone yet, redo stays disabled").isTrue();
        } finally {
            callOnFx(() -> {
                binder.dispose();
                return null;
            });
            vm.dispose();
        }
    }

    private static ControlSnapshot snapshot(Button undo, Button redo, MenuItem undoItem) {
        return new ControlSnapshot(
                undo.isDisable(), redo.isDisable(), undoItem.getText());
    }

    private record ControlSnapshot(boolean undoDisabled, boolean redoDisabled, String undoItemText) {
    }

    /** A minimal undoable action whose {@link #description()} drives the control labels. */
    private static final class FakeAction implements UndoableAction {
        private final String description;

        FakeAction(String description) {
            this.description = description;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public void execute() {
            // no model mutation needed
        }

        @Override
        public void undo() {
            // no model mutation needed
        }
    }

    // ── toolkit + FX-thread helpers (this package is opened only to itself under
    //    surefire, so a same-package static starter is used rather than a
    //    cross-package @ExtendWith — see ChannelCommandEventTest) ───────────────

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private static void startToolkit() throws InterruptedException {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        try {
            CountDownLatch startup = new CountDownLatch(1);
            Platform.startup(startup::countDown);
            if (!startup.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("JavaFX toolkit startup timed out");
            }
            Platform.setImplicitExit(false);
        } catch (IllegalStateException e) {
            String message = e.getMessage();
            if (message == null || !message.contains("Toolkit already initialized")) {
                throw e;
            }
        }
    }

    /** Posts a barrier onto the FX thread and blocks until it (and every earlier {@code onFx}) has run. */
    private static void flushFx() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("FX queue must flush within %ds", TIMEOUT_SECONDS).isTrue();
    }

    private static void runOnFx(Runnable work) throws InterruptedException {
        callOnFx(() -> {
            work.run();
            return null;
        });
    }

    private static <T> T callOnFx(FxCallable<T> work) throws InterruptedException {
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
        Throwable t = thrown.get();
        if (t instanceof RuntimeException re) {
            throw re;
        }
        if (t instanceof Error e) {
            throw e;
        }
        if (t != null) {
            throw new AssertionError("FX work threw a checked exception", t);
        }
        return result.get();
    }

    @FunctionalInterface
    private interface FxCallable<T> {
        T call();
    }
}
