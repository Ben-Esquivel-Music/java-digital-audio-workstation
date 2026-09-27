package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.controls.MixerChannelStrip;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.recording.SessionInputSelection;
import com.benesquivelmusic.daw.app.ui.vm.TrackControlWiring;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;

import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 322 contract rig (probe-derived, permanent) — the PRODUCTION wiring shape, not a recording sink:
 * {@link TrackControlWiring#standalone} (a live {@code TrackChannelRegistry} +
 * {@code CoreTrackIntentHandler} wrapped by {@code LinkedTrackCommandDispatcher},
 * exactly what {@code MainController.rebuildTrackControlWiring()} builds) shared
 * by a {@link MixerView} (hosted supplier, never the standalone fallback) and a
 * {@link TrackStripController}. Built and used on the FX thread only.
 */
final class Story322ContractRig implements AutoCloseable {

    static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 24, 128);
    static final int FRAMES = 32;
    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");

    final DawProject project;
    final UndoManager undoManager = new UndoManager();
    final FxDispatcher dispatcher = new FxDispatcher();
    final TrackControlWiring wiring;
    final MixerView mixerView;
    final VBox trackListPanel = new VBox(new Label("TRACKS"));
    final Label statusBarLabel = new Label();
    final NotificationBar notificationBar = new NotificationBar();
    final AudioEngine audioEngine;
    final TrackStripController controller;
    private final Map<Track, HBox> lanes = new LinkedHashMap<>();

    Story322ContractRig(DawProject project) {
        this.project = project;
        this.wiring = TrackControlWiring.standalone(project, dispatcher, null);
        this.mixerView = new MixerView(project, undoManager, dispatcher);
        this.mixerView.setTrackControlWiring(() -> wiring);
        this.audioEngine = new AudioEngine(project.getFormat());
        this.notificationBar.setAnimated(false);
        this.controller = new TrackStripController(
                project, undoManager, audioEngine, mixerView,
                notificationBar, statusBarLabel, trackListPanel, new BorderPane(),
                new ClipboardManager(), new SelectionModel(),
                () -> { }, () -> { }, () -> { }, () -> { }, () -> { }, () -> { }, () -> { },
                () -> false,
                ZoomLevel::new,
                () -> null,
                () -> wiring,
                new NoSessionInput());
        assertThat(mixerView.getTrackControlWiring())
                .as("the mixer view binds through the HOSTED wiring, not its standalone fallback")
                .isSameAs(wiring);
    }

    /** Builds (once) and returns the arrangement lane of {@code track}. */
    HBox lane(Track track) {
        return lanes.computeIfAbsent(track, controller::addTrackToUI);
    }

    TrackStripController.StripControls laneControls(Track track) {
        TrackStripController.StripControls controls = TrackStripController.controlsOf(lane(track));
        assertThat(controls).isNotNull();
        return controls;
    }

    /** The mixer's bound track strip at project index {@code index}. */
    MixerChannelStrip strip(int index) {
        return mixerView.getTrackStrips().get(index).strip();
    }

    static boolean isActive(Node node) {
        return node.getPseudoClassStates().contains(ACTIVE);
    }

    static double db(double linear) {
        return 20.0 * Math.log10(linear);
    }

    /** Renders one block of {@code amplitude} on mixer channel {@code channelIndex} only. */
    float[][] render(int channelIndex, float amplitude) {
        Mixer mixer = project.getMixer();
        int channels = mixer.getChannels().size();
        int returns = mixer.getReturnBuses().size();
        float[][][] chans = new float[channels][2][FRAMES];
        Arrays.fill(chans[channelIndex][0], amplitude);
        Arrays.fill(chans[channelIndex][1], amplitude);
        float[][] out = new float[2][FRAMES];
        mixer.mixDown(chans, out, new float[returns][2][FRAMES], FRAMES);
        return out;
    }

    static double peak(float[] lane) {
        double p = 0.0;
        for (float v : lane) {
            p = Math.max(p, Math.abs(v));
        }
        return p;
    }

    @Override
    public void close() {
        controller.dispose();
        mixerView.dispose();
        wiring.dispose();
        dispatcher.dispose();
    }

    /** Runs {@code work} on the FX thread and rethrows whatever it threw. */
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

    /** Waits until every runLater posted before this call has run. */
    static void flushFx() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    }

    /** A session input selection with no device and no side effects. */
    private static final class NoSessionInput implements SessionInputSelection {
        @Override
        public String currentDeviceName() {
            return "";
        }

        @Override
        public void select(AudioDeviceInfo device) {
            // no session device in a probe
        }
    }
}
