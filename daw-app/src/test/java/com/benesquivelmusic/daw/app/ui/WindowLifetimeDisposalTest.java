package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.metering.MeterFeed;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.EngineBinder;
import com.benesquivelmusic.daw.core.export.RenderQueue;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Story 333, design §1.7 and the studio engineer's ruling 2 OQ-3 — DISPOSE as
 * the application wires it, {@code MainController.disposeWindowLifetime()}:
 * its FX part returns at once — the meter consumers detached before the
 * engine is unbound, every other window hidden but the primary stage — while
 * the audio engine's stop, handed to the daemon {@code daw-engine-shutdown}
 * thread, is still held; the hand-off's stage completes only once the stop
 * ended. It runs once, and a failing FX part still hands the stop off. Then
 * the same disposal under the real coordinator: the progress surface shows
 * "Stopping audio…" with no note and no button while the engine's stop is
 * held, HIDE comes at the five-second bound, or at once when the stop ends
 * first, and DISPOSE keeps the close's own surface while it hides a floating
 * panel whose own close handler would have vetoed its close (ruling R12 #14).
 *
 * <p>{@code MainController} is never FXML-loaded in tests: each test builds
 * {@code new MainController()} and sets the collaborators the disposal
 * stops on its non-final fields, a test fixture rather than a reflection
 * consumer (dawg-annotations-reflection §4, "Adding a new reflection
 * consumer"; JEP 500: no final field is ever set). The engine's stop is held
 * by its mock, never by a sleep.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class WindowLifetimeDisposalTest {

    @TempDir
    Path workspace;

    @Test
    void theFxPartReturnsWhileTheEngineStopHandedToItsDaemonThreadIsHeld() throws Exception {
        HeldEngine engine = new HeldEngine();
        MeterFeed meters = mock(MeterFeed.class);
        EngineBinder binder = mock(EngineBinder.class);
        RenderQueue queue = mock(RenderQueue.class);
        AtomicInteger viewModelsReleased = new AtomicInteger();
        AtomicReference<Stage> primary = new AtomicReference<>();
        AtomicReference<Stage> floating = new AtomicReference<>();
        AtomicReference<Stage> unowned = new AtomicReference<>();
        AtomicReference<Stage> broken = new AtomicReference<>();
        MainController main = onFx(() -> {
            MainController controller = new MainController();
            setField(controller, "audioEngine", engine.mock);
            setField(controller, "meterFeed", meters);
            setField(controller, "engineBinder", binder);
            setField(controller, "renderQueue", queue);
            viewModelDisposers(controller).add(viewModelsReleased::incrementAndGet);
            primary.set(shown(new Stage(), "Main window"));
            controller.installPrimaryStage(primary.get());
            // An editor whose own hidden handler fails, shown first: the windows after it are hidden still.
            Stage failing = new Stage();
            failing.initOwner(primary.get());
            failing.setOnHidden(_ -> {
                throw new IllegalStateException("an editor's hidden handler failed");
            });
            broken.set(shown(failing, "Broken editor"));
            Stage panel = new Stage();
            panel.initOwner(primary.get());
            floating.set(shown(panel, "Floating panel"));
            unowned.set(shown(new Stage(), "Plug-in fault log"));
            return controller;
        });
        try {
            long started = System.nanoTime();
            CompletionStage<Void> handOff = onFx(main::disposeWindowLifetime);
            assertThat(System.nanoTime() - started).as("the FX part returned while the engine's stop is held")
                    .isLessThan(TimeUnit.SECONDS.toNanos(5));

            Thread stopping = engine.awaitStopping();
            assertThat(stopping.getName()).isEqualTo("daw-engine-shutdown");
            assertThat(stopping.isDaemon()).as("ruling 2 OQ-3: a daemon").isTrue();
            assertThat(handOff.toCompletableFuture().isDone()).as("the hand-off waits for the engine's stop").isFalse();
            assertThat(viewModelsReleased).hasValue(1);
            runOnFx(() -> {
                assertThat(primary.get().isShowing()).as("HIDE hides the primary stage, not DISPOSE").isTrue();
                assertThat(floating.get().isShowing()).as("an owned floating panel is hidden").isFalse();
                assertThat(unowned.get().isShowing()).as("an unowned tool window is hidden").isFalse();
                assertThat(broken.get().isShowing()).as("so is the editor whose hidden handler failed").isFalse();
            });
            InOrder order = inOrder(meters, binder, queue, engine.mock);
            order.verify(meters).dispose();
            order.verify(binder).unbind();
            // The studio engineer's stage-7 N2: the render queue is persisted before the driver's stop, which is
            // still held — so a stop that never returns cuts nothing of the queue's state.
            order.verify(queue).persist();
            order.verify(engine.mock).shutdown();
            verify(queue, never()).shutdown();

            assertThat(onFx(main::disposeWindowLifetime)).as("it runs once: a second call has the first hand-off")
                    .isSameAs(handOff);
            assertThat(viewModelsReleased).hasValue(1);

            engine.release();
            handOff.toCompletableFuture().get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS);
            verify(engine.mock, times(1)).shutdown();
            verify(meters, times(1)).dispose();
            InOrder queueEnd = inOrder(engine.mock, queue);
            queueEnd.verify(engine.mock).shutdown();
            queueEnd.verify(queue).shutdown();
            verify(queue, times(1)).persist();
        } finally {
            engine.release();
            runOnFx(() -> primary.get().hide());
        }
    }

    /**
     * The app-fix-2 review S1 (the studio engineer's ruling on app fix pass
     * 2's Java items, §1): the capture gate the application installs says
     * yes while the window lives — no project being installed — and no from
     * DISPOSE on, for good: already when DISPOSE's view-model step runs,
     * before the plug-in step empties the open project's insert lists, and
     * after a second call.
     */
    @Test
    void theAutosaveCapturesNothingOnceTheWindowsDisposalBegan() throws Exception {
        HeldEngine engine = new HeldEngine();
        engine.release();
        com.benesquivelmusic.daw.core.persistence.CheckpointSource source = wiredCaptureSource(engine, null);
        List<Boolean> atTheViewModelStep = new java.util.concurrent.CopyOnWriteArrayList<>();
        MainController main = lastWired.get();
        runOnFx(() -> viewModelDisposers(main).add(() -> atTheViewModelStep.add(source.isAvailable())));
        assertThat(onFx(source::isAvailable)).as("non-vacuity: no project is being installed").isTrue();

        runOnFx(() -> main.disposeWindowLifetime());

        assertThat(atTheViewModelStep).as("shut before DISPOSE's steps ran").containsExactly(false);
        assertThat(onFx(source::isAvailable)).isFalse();
        runOnFx(() -> main.disposeWindowLifetime());
        assertThat(onFx(source::isAvailable)).as("for good").isFalse();
    }

    /** The same gate shuts when DISPOSE's FX part fails: the flag is set before any step, outside them. */
    @Test
    void aFailingFxPartShutsTheAutosaveToo() throws Exception {
        HeldEngine engine = new HeldEngine();
        engine.release();
        IllegalStateException broken = new IllegalStateException("a view model would not dispose");
        com.benesquivelmusic.daw.core.persistence.CheckpointSource source = wiredCaptureSource(engine, () -> {
            throw broken;
        });
        MainController main = lastWired.get();

        assertThatThrownBy(() -> runOnFx(() -> main.disposeWindowLifetime())).hasRootCause(broken);

        assertThat(onFx(source::isAvailable)).isFalse();
    }

    /** The partial controller {@link #wiredCaptureSource} built last. */
    private final AtomicReference<MainController> lastWired = new AtomicReference<>();

    /**
     * A partial controller over a mock project manager, its autosave wired
     * through {@code wireTheWindowsAutosave()} as the application wires it;
     * returns the capture source the controller handed the checkpoints.
     */
    private com.benesquivelmusic.daw.core.persistence.CheckpointSource wiredCaptureSource(HeldEngine engine,
                                                                                         Runnable viewModelStep)
            throws Exception {
        com.benesquivelmusic.daw.core.persistence.ProjectManager autosaved =
                mock(com.benesquivelmusic.daw.core.persistence.ProjectManager.class);
        com.benesquivelmusic.daw.core.persistence.CheckpointManager checkpoints =
                mock(com.benesquivelmusic.daw.core.persistence.CheckpointManager.class);
        org.mockito.Mockito.when(autosaved.getCheckpointManager()).thenReturn(checkpoints);
        MainController main = onFx(() -> {
            MainController controller = new MainController();
            setField(controller, "audioEngine", engine.mock);
            setField(controller, "projectManager", autosaved);
            if (viewModelStep != null) {
                viewModelDisposers(controller).add(viewModelStep);
            }
            controller.wireTheWindowsAutosave();
            return controller;
        });
        lastWired.set(main);
        org.mockito.ArgumentCaptor<com.benesquivelmusic.daw.core.persistence.CheckpointSource> installed =
                org.mockito.ArgumentCaptor.forClass(com.benesquivelmusic.daw.core.persistence.CheckpointSource.class);
        verify(checkpoints).setCaptureSource(org.mockito.ArgumentMatchers.any(), installed.capture());
        return installed.getValue();
    }

    @Test
    void aFailingFxPartStillHandsTheEngineStopOffAndRunsEveryOtherCleanup() throws Exception {
        HeldEngine engine = new HeldEngine();
        engine.release();
        EngineBinder binder = mock(EngineBinder.class);
        IllegalStateException broken = new IllegalStateException("a view model would not dispose");
        MainController main = onFx(() -> {
            MainController controller = new MainController();
            setField(controller, "audioEngine", engine.mock);
            setField(controller, "engineBinder", binder);
            viewModelDisposers(controller).add(() -> {
                throw broken;
            });
            return controller;
        });

        assertThatThrownBy(() -> runOnFx(() -> main.disposeWindowLifetime())).hasRootCause(broken);

        engine.awaitStopping();
        verify(binder).unbind();
        CompletionStage<Void> handOff = onFx(main::disposeWindowLifetime);
        handOff.toCompletableFuture().get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS);
        verify(engine.mock, times(1)).shutdown();
    }

    @Test
    void underTheCoordinatorTheSurfaceSaysStoppingAudioAndHideWaitsForTheHeldStopAtMostFiveSeconds()
            throws Exception {
        HeldEngine engine = new HeldEngine();
        try (ExitFixture exit = new ExitFixture(workspace, AutoSaveConfig.DEFAULT)) {
            AtomicReference<Stage> floating = new AtomicReference<>();
            AtomicInteger vetoed = new AtomicInteger();
            MainController main = partialApplication(exit, engine);
            runOnFx(() -> {
                Stage panel = new Stage();
                panel.initOwner(exit.stage());
                // A secondary window whose own handler vetoes its close (ruling R12 #14): DISPOSE hides it anyway.
                panel.setOnCloseRequest(event -> {
                    vetoed.incrementAndGet();
                    event.consume();
                });
                floating.set(shown(panel, "Floating mixer"));
            });
            exit.windowDisposal = main::disposeWindowLifetime;
            // The close waits on its closing checkpoint long enough for its surface to show before DISPOSE.
            SaveFlowFixture.Publishes.Hold closing = exit.save.publishes.holdNext(SaveFlowFixture::isCheckpoint);
            exit.edit("Bass");
            exit.save.promptAnswer = "Discard and close";
            ClosingProgressDialog surface;
            try {
                exit.closeBox();
                closing.awaitHeld();
                exit.fire(ExitCoordinator.SURFACE_DELAY);
                surface = exit.surface();
                assertThat(onFx(surface::isOpen)).as("fixture: the surface shows before DISPOSE").isTrue();
            } finally {
                closing.release();
            }

            exit.awaitStep(ExitCoordinator.ExitStep.DISPOSE);
            engine.awaitStopping();
            assertThat(onFx(surface::stepText)).isEqualTo("Stopping audio…");
            assertThat(onFx(surface::noteText)).as("no recover text after a clean seal").isEmpty();
            assertThat(onFx(() -> surface.keepButton().isVisible() || surface.offerButton().isVisible()))
                    .as("no buttons while the audio stops").isFalse();
            runOnFx(() -> {
                assertThat(floating.get().isShowing()).as("DISPOSE hid the floating panel").isFalse();
                assertThat(surface.isOpen()).as("and kept the close's own surface").isTrue();
                assertThat(exit.stage().isShowing()).isTrue();
            });
            assertThat(vetoed).as("a hide asks no close request").hasValue(0);
            assertThat(exit.reached(ExitCoordinator.ExitStep.HIDE)).as("the engine still stops").isFalse();

            exit.fire(ExitCoordinator.ENGINE_STOP_BOUND);

            assertThat(exit.stepNames()).endsWith("RELEASE DONE", "DISPOSE DONE", "HIDE DONE");
            assertThat(onFx(() -> exit.stage().isShowing())).isFalse();
            assertThat(onFx(surface::isOpen)).as("HIDE closed the surface").isFalse();
            assertThat(exit.terminalExits).hasValue(1);
            assertThat(exit.disposals).hasValue(1);
            assertThat(engine.stopEnded()).as("HIDE came at the bound, the engine's stop still held").isFalse();
        } finally {
            engine.release();
        }
    }

    @Test
    void underTheCoordinatorAnEngineStopThatEndsFirstHidesAtOnce() throws Exception {
        HeldEngine engine = new HeldEngine();
        try (ExitFixture exit = new ExitFixture(workspace, AutoSaveConfig.DEFAULT)) {
            MainController main = partialApplication(exit, engine);
            exit.windowDisposal = main::disposeWindowLifetime;

            exit.closeBox();
            exit.awaitStep(ExitCoordinator.ExitStep.DISPOSE);
            engine.awaitStopping();
            assertThat(exit.reached(ExitCoordinator.ExitStep.HIDE)).isFalse();
            engine.release();

            exit.awaitStep(ExitCoordinator.ExitStep.HIDE);
            assertThat(engine.stopEnded()).isTrue();
            assertThat(exit.stepNames()).filteredOn(name -> name.startsWith("HIDE")).containsExactly("HIDE DONE");
            assertThat(exit.terminalExits).hasValue(1);
            // The window's close box after HIDE: nothing runs again.
            runOnFx(() -> exit.stage().fireEvent(new WindowEvent(exit.stage(), WindowEvent.WINDOW_CLOSE_REQUEST)));
            exit.fire(ExitCoordinator.ENGINE_STOP_BOUND);
            assertThat(exit.disposals).hasValue(1);
            assertThat(exit.terminalExits).hasValue(1);
        } finally {
            engine.release();
        }
    }

    /**
     * The studio engineer's stage-7 S1 (the stage-7 review S3; ruling 4 Q5
     * (x)): DISPOSE's FX part fails — a view model would not dispose — after
     * the disposal already started the engine's stop. DISPOSE is published
     * FAILED, and HIDE still waits for that stop, held here, at most
     * {@link ExitCoordinator#ENGINE_STOP_BOUND}: the coordinator asks the
     * disposal again, which hands back the stop it started. The disposal ran
     * once — the engine stopped once — although DISPOSE asked twice.
     */
    @Test
    void aFailedFxPartStillLetsHideWaitForTheEngineStopItStartedAtMostFiveSeconds() throws Exception {
        HeldEngine engine = new HeldEngine();
        try (ExitFixture exit = new ExitFixture(workspace, AutoSaveConfig.DEFAULT)) {
            MainController main = partialApplicationWhoseFxPartFails(exit, engine);
            exit.windowDisposal = main::disposeWindowLifetime;

            exit.closeBox();
            exit.awaitStep(ExitCoordinator.ExitStep.DISPOSE);
            engine.awaitStopping();
            exit.save.settle();

            assertThat(exit.stepNames()).as("the FX part's failure is published").contains("DISPOSE FAILED")
                    .doesNotContain("DISPOSE DONE");
            assertThat(exit.reached(ExitCoordinator.ExitStep.HIDE)).as("HIDE waits for the engine's stop it started")
                    .isFalse();
            assertThat(exit.terminalExits).hasValue(0);

            exit.fire(ExitCoordinator.ENGINE_STOP_BOUND);

            assertThat(exit.stepNames()).endsWith("DISPOSE FAILED", "HIDE DONE");
            assertThat(exit.terminalExits).hasValue(1);
            assertThat(engine.stopEnded()).as("HIDE came at the bound, the engine's stop still held").isFalse();
            assertThat(exit.disposals).as("DISPOSE asked the disposal again for its hand-off").hasValue(2);
            verify(engine.mock, times(1)).shutdown();
        } finally {
            engine.release();
        }
    }

    /** As above, the engine's stop ending first: HIDE comes then, before the bound. */
    @Test
    void aFailedFxPartHidesOnceTheEngineStopItStartedEnded() throws Exception {
        HeldEngine engine = new HeldEngine();
        try (ExitFixture exit = new ExitFixture(workspace, AutoSaveConfig.DEFAULT)) {
            MainController main = partialApplicationWhoseFxPartFails(exit, engine);
            exit.windowDisposal = main::disposeWindowLifetime;

            exit.closeBox();
            exit.awaitStep(ExitCoordinator.ExitStep.DISPOSE);
            engine.awaitStopping();
            exit.save.settle();
            assertThat(exit.reached(ExitCoordinator.ExitStep.HIDE)).as("not before the stop ended").isFalse();

            engine.release();

            exit.awaitStep(ExitCoordinator.ExitStep.HIDE);
            assertThat(engine.stopEnded()).isTrue();
            assertThat(exit.stepNames()).endsWith("DISPOSE FAILED", "HIDE DONE");
            assertThat(exit.terminalExits).hasValue(1);
            verify(engine.mock, times(1)).shutdown();
        } finally {
            engine.release();
        }
    }

    /**
     * {@link #partialApplication}, its FX part failing: a view model that will not dispose. Package-private:
     * the application-stop tests wait for this disposal's re-asked hand-off ({@code ApplicationStopFallbackTest}).
     */
    static MainController partialApplicationWhoseFxPartFails(ExitFixture exit, HeldEngine engine)
            throws Exception {
        MainController main = partialApplication(exit, engine);
        runOnFx(() -> viewModelDisposers(main).add(() -> {
            throw new IllegalStateException("a view model would not dispose");
        }));
        return main;
    }

    /**
     * A partial application over the exit fixture: the primary stage and the
     * coordinator are the fixture's, the audio engine is {@code engine}.
     * Package-private, as {@link HeldEngine} is: the application-stop tests
     * hold the same engine stop.
     */
    static MainController partialApplication(ExitFixture exit, HeldEngine engine) throws Exception {
        return onFx(() -> {
            MainController controller = new MainController();
            setField(controller, "audioEngine", engine.mock);
            setField(controller, "exitCoordinator", exit.coordinator());
            controller.installPrimaryStage(exit.stage());
            return controller;
        });
    }

    /** An audio engine whose stop is held until {@link #release()}: a driver slow to stop (the blocking-engine seam). */
    static final class HeldEngine {
        final AudioEngine mock = mock(AudioEngine.class);
        private final CountDownLatch stopping = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final CountDownLatch ended = new CountDownLatch(1);
        private final AtomicReference<Thread> stoppedOn = new AtomicReference<>();

        HeldEngine() {
            doAnswer(_ -> {
                stoppedOn.set(Thread.currentThread());
                stopping.countDown();
                assertThat(released.await(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS))
                        .as("the test released the engine's stop").isTrue();
                ended.countDown();
                return null;
            }).when(mock).shutdown();
        }

        Thread awaitStopping() throws InterruptedException {
            assertThat(stopping.await(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS))
                    .as("the engine's stop began").isTrue();
            return stoppedOn.get();
        }

        void release() {
            released.countDown();
        }

        boolean stopEnded() {
            return ended.getCount() == 0;
        }
    }

    private static Stage shown(Stage stage, String title) {
        stage.setTitle(title);
        stage.setScene(new Scene(new StackPane(), 120, 80));
        stage.show();
        return stage;
    }

    @SuppressWarnings("unchecked")
    private static List<Runnable> viewModelDisposers(MainController controller) {
        try {
            Field field = MainController.class.getDeclaredField("vmDisposers");
            field.setAccessible(true);
            return (List<Runnable>) field.get(controller);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    /** Sets a non-final field of the partial controller (never a final one: JEP 500). */
    private static void setField(MainController target, String name, Object value) {
        try {
            Field field = MainController.class.getDeclaredField(name);
            assertThat(java.lang.reflect.Modifier.isFinal(field.getModifiers())).as("%s is not final", name).isFalse();
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
