package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.vm.TrackChannelRegistry;
import com.benesquivelmusic.daw.app.ui.vm.TrackControlWiring;
import com.benesquivelmusic.daw.app.ui.vm.command.CoreTrackIntentHandler;
import com.benesquivelmusic.daw.app.ui.vm.command.TrackCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.TrackIntentHandler;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 322 test rig for the arrangement strips: a {@link TrackStripController}
 * over a real {@link TrackChannelRegistry} and a <em>recording</em> command
 * sink. Every {@link TrackCommand} a strip raises is captured in
 * {@link #raised}; when {@link #executeCommands} is set the sink also runs it
 * through the production {@link CoreTrackIntentHandler}, otherwise the model
 * is left untouched — which is how a test proves a control never writes the
 * model directly (Audio Engine Wiring Design Book §2.10).
 *
 * <p>Build on the FX thread via {@link #onFx(Callable)}; {@link #close()}
 * releases the controller, the wiring and the mixer view.</p>
 */
final class ArrangementStripFixture {

    final DawProject project;
    final UndoManager undoManager = new UndoManager();
    final AudioEngine audioEngine;
    final FxDispatcher dispatcher = new FxDispatcher();
    final TrackChannelRegistry registry;
    final List<TrackCommand> raised = new ArrayList<>();
    final TrackIntentHandler handler;
    volatile boolean executeCommands;
    final TrackControlWiring wiring;
    final MixerView mixerView;
    final NotificationBar notificationBar = new NotificationBar();
    final Label statusBarLabel = new Label();
    final VBox trackListPanel = new VBox();
    final StubSessionInputSelection sessionInputSelection;
    final TrackStripController controller;

    /** FX thread only. */
    ArrangementStripFixture(DawProject project, boolean executeCommands,
                            StubSessionInputSelection sessionInputSelection) {
        this.project = project;
        this.executeCommands = executeCommands;
        this.sessionInputSelection = sessionInputSelection;
        this.audioEngine = new AudioEngine(project.getFormat());
        this.registry = new TrackChannelRegistry(project, dispatcher);
        this.handler = new CoreTrackIntentHandler(project);
        Consumer<TrackCommand> sink = command -> {
            raised.add(command);
            if (this.executeCommands) {
                command.execute(handler);
            }
        };
        this.wiring = new TrackControlWiring(registry, sink);
        this.mixerView = new MixerView(project, undoManager);
        this.notificationBar.setAnimated(false);
        this.trackListPanel.getChildren().add(new Label("TRACKS"));
        this.controller = new TrackStripController(
                project, undoManager, audioEngine, mixerView,
                notificationBar, statusBarLabel, trackListPanel, new BorderPane(),
                new ClipboardManager(), new SelectionModel(),
                () -> { }, () -> { }, () -> { }, () -> { }, () -> { }, () -> { }, () -> { },
                () -> false,
                ZoomLevel::new,
                () -> null,   // the editor view is only reached from the context menu

                () -> wiring,
                sessionInputSelection);
    }

    ArrangementStripFixture(DawProject project, boolean executeCommands) {
        this(project, executeCommands, new StubSessionInputSelection());
    }

    /** Builds the strip for {@code track} (FX thread only) and returns it. */
    HBox addStrip(Track track) {
        return controller.addTrackToUI(track);
    }

    /** The control handles of a built strip. */
    static TrackStripController.StripControls controlsOf(HBox strip) {
        TrackStripController.StripControls controls = TrackStripController.controlsOf(strip);
        assertThat(controls).as("strip carries its controls").isNotNull();
        return controls;
    }

    /** FX thread only. */
    void close() {
        controller.dispose();
        mixerView.dispose();
        wiring.dispose();
        dispatcher.dispose();
    }

    /** Runs {@code work} on the FX thread, rethrowing anything it threw (assertion errors included). */
    static <T> T onFx(Callable<T> work) throws Exception {
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
        assertThat(latch.await(30, TimeUnit.SECONDS)).as("FX work completed").isTrue();
        Throwable t = thrown.get();
        if (t instanceof Error e) {
            throw e;
        }
        if (t instanceof Exception e) {
            throw e;
        }
        return result.get();
    }

    static void onFx(Runnable work) throws Exception {
        onFx(() -> {
            work.run();
            return null;
        });
    }
}
