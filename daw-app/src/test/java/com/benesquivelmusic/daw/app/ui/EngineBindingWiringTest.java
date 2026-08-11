package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.vm.TransportVM;
import com.benesquivelmusic.daw.app.ui.vm.command.CoreTransportIntentHandler;
import com.benesquivelmusic.daw.app.ui.vm.command.StartTransportCommand;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.EngineBinder;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.TransportState;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 314 — the in-process rebind cascade: dispose the epoch-N consumers,
 * bind the next project through the production {@link EngineBinder}, and
 * re-create the VMs against the same live references — the exact order
 * {@code MainController.rebuildViewModels()} runs ({@code MainController} is
 * never FXML-loaded in tests, so this substitutes the wiring in-process,
 * mirroring {@code ProjectSurfaceWiringTest}).
 */
@ExtendWith(JavaFxToolkitExtension.class)
class EngineBindingWiringTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final AudioFormat FORMAT = new AudioFormat(44_100.0, 2, 16, 8);

    private final List<Runnable> vmDisposers = new ArrayList<>();

    @Test
    void projectSwitchRebindsTheEngineDisposesTheOldEpochAndRoutesGesturesToTheNewProject()
            throws InterruptedException {
        DawProject projectA = new DawProject("A", FORMAT);
        DawProject projectB = new DawProject("B", FORMAT);
        AudioEngine engine = new AudioEngine(FORMAT);
        EngineBinder binder = new EngineBinder(engine);
        FxDispatcher dispatcher = new FxDispatcher();
        try {
            // Simulated open of A.
            EpochSurface epochSurfaceA = rebuild(binder, dispatcher, projectA);
            TransportVM vmA = epochSurfaceA.vm();
            long epochA = binder.epoch();
            assertThat(engine.getTransport()).isSameAs(projectA.getTransport());
            assertThat(engine.getMixer()).isSameAs(projectA.getMixer());
            assertThat(engine.getTracks()).isEqualTo(projectA.getTracks());
            assertThat(dispatcher.openChannelCount())
                    .as("epoch A opens exactly one playhead channel").isEqualTo(1);

            // Switch to B — dispose epoch-A consumers, bind, rebuild.
            EpochSurface epochSurfaceB = rebuild(binder, dispatcher, projectB);
            TransportVM vmB = epochSurfaceB.vm();
            assertThat(binder.epoch())
                    .as("the switch increments the binding epoch").isGreaterThan(epochA);
            assertThat(engine.getTransport()).isSameAs(projectB.getTransport());
            assertThat(engine.getMixer()).isSameAs(projectB.getMixer());
            assertThat(dispatcher.openChannelCount())
                    .as("the old TransportVM is disposed back to baseline before epoch B's "
                            + "channel opens — one channel total, not two")
                    .isEqualTo(1);
            assertThat(vmA).isNotSameAs(vmB);

            // A post-switch gesture goes through epoch B's PRODUCTION command
            // carrier (story 290 seam) — a stale epoch-A surface still routing
            // to the old project would leave B STOPPED and fail below.
            new StartTransportCommand().execute(epochSurfaceB.handler());
            flushFx();
            assertThat(projectB.getTransport().getState())
                    .as("the gesture flips B's transport").isEqualTo(TransportState.PLAYING);
            assertThat(projectA.getTransport().getState())
                    .as("A's transport stays STOPPED").isEqualTo(TransportState.STOPPED);
            assertThat(engine.getTransport().getState())
                    .as("the engine sees the playing transport").isEqualTo(TransportState.PLAYING);
            assertThat(vmB.getState()).isEqualTo(TransportState.PLAYING);

            // Structural change on the live project refreshes the engine snapshot.
            List<Track> before = engine.getTracks();
            Track added = projectB.createAudioTrack("New Track");
            assertThat(engine.getTracks())
                    .as("a post-switch track add refreshes the engine's snapshot")
                    .isNotSameAs(before)
                    .containsExactly(added);
        } finally {
            disposeAll();
            assertThat(dispatcher.openChannelCount())
                    .as("full disposal returns the dispatcher to baseline").isZero();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * One binding epoch's production surfaces: the {@link TransportVM} plus
     * the story-290 command carrier gestures are executed through.
     */
    private record EpochSurface(TransportVM vm, CoreTransportIntentHandler handler) { }

    /**
     * Mirrors {@code MainController.rebuildViewModels()} order: dispose all
     * epoch-N consumers, bind the project, then build epoch N+1's VMs and
     * per-epoch command carrier.
     */
    private EpochSurface rebuild(EngineBinder binder, FxDispatcher dispatcher, DawProject project) {
        disposeAll();
        binder.bind(project);
        TransportVM vm = new TransportVM(project.getTransport(), dispatcher);
        vmDisposers.add(vm::dispose);
        return new EpochSurface(vm,
                new CoreTransportIntentHandler(project.getTransport(), FORMAT.sampleRate()));
    }

    private void disposeAll() {
        for (Runnable disposer : vmDisposers) {
            disposer.run();
        }
        vmDisposers.clear();
    }

    private static void flushFx() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("FX queue must flush within %ds", TIMEOUT_SECONDS).isTrue();
    }
}
