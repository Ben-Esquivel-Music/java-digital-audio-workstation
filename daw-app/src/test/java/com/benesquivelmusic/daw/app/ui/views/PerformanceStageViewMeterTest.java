package com.benesquivelmusic.daw.app.ui.views;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.controls.LevelMeter;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.metering.MeterFeed;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.metering.LevelTapSlot;
import com.benesquivelmusic.daw.core.metering.MeteringTapBus;
import com.benesquivelmusic.daw.core.metering.TapSnapshot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 318 — the Performance Stage's meters stop being decoration: its
 * stereo bus {@link LevelMeter} is fed by the engine's {@code MASTER_OUT}
 * tap and each track tile's inline meter by that channel's post-fader
 * {@code CHANNEL_POST} tap, through the same FX-pulse {@link MeterFeed} every
 * other surface uses.
 *
 * <p>Frames are written through the {@link LevelTapSlot} RT API exactly as
 * {@code Mixer.mixDown} / {@code RenderPipeline.renderBlock} write them, so
 * the test exercises the real publication path without a render thread.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class PerformanceStageViewMeterTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 24, 64);
    private static final int BLOCK = 64;
    /** 20·log10(0.5) — the dB a half-scale block must report. */
    private static final double HALF_SCALE_DB = -6.0206;

    private DawProject project;
    private List<Track> tracks;
    private MeteringTapBus bus;
    private FxDispatcher dispatcher;
    private MeterFeed feed;
    private PerformanceStageView view;

    @BeforeEach
    void setUp() throws Exception {
        project = new DawProject("Stage Meters", FORMAT);
        project.createAudioTrack("Drums");
        project.createAudioTrack("Bass");
        tracks = List.copyOf(project.getTracks());

        bus = new MeteringTapBus();
        bus.rebind(project.getMixer(), FORMAT, 1L);
        dispatcher = new FxDispatcher();

        view = onFx(() -> {
            feed = new MeterFeed(bus, dispatcher);
            ResourceBundle messages = ResourceBundle.getBundle(
                    "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);
            return new PerformanceStageView(project, messages, new InertHost());
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        onFxRun(() -> {
            view.unbindMeters();
            feed.dispose();
        });
        bus.close();
    }

    /** Puts the stage into a scene — the "visible" predicate every subscription reads. */
    private void attachScene() throws Exception {
        onFxRun(() -> new Scene(new StackPane(view), 1280, 800));
    }

    /** Publishes one block of constant {@code level} into MASTER_OUT and every channel slot. */
    private void renderBlock(float level) {
        TapSnapshot taps = bus.snapshot();
        float[] lane = new float[BLOCK];
        Arrays.fill(lane, level);
        publish(taps.masterOut(), taps, lane);
        List<MixerChannel> channels = project.getMixer().getChannels();
        for (int i = 0; i < channels.size(); i++) {
            publish(taps.channelSlot(i, channels.get(i)), taps, lane);
        }
        bus.blockCompleted(taps);
    }

    private static void publish(LevelTapSlot slot, TapSnapshot taps, float[] lane) {
        assertThat(slot).as("the bus must expose a slot for every bound tap point").isNotNull();
        slot.beginBlock(taps.epoch(), taps.blockIndex(), 2);
        slot.accumulate(0, lane, BLOCK);
        slot.accumulate(1, lane, BLOCK);
        slot.publish(BLOCK);
    }

    private LevelMeter tileMeter(int index) {
        return view.trackTiles().get(index).getMeter();
    }

    @Test
    void bindMetersSubscribesTheBusMeterAndEveryTile() throws Exception {
        assertThat(view.meterSubscriptionCount()).as("nothing before bindMeters").isZero();

        onFxRun(() -> view.bindMeters(feed));

        assertThat(view.meterSubscriptionCount())
                .as("MASTER_OUT plus one CHANNEL_POST per tile")
                .isEqualTo(1 + tracks.size());
        assertThat(feed.subscriptionCount()).isEqualTo(1 + tracks.size());
        assertThat(bus.levelSubscriptionCount()).isEqualTo(1 + tracks.size());
    }

    @Test
    void aStageOutsideTheSceneGraphCostsThePulseNothing() throws Exception {
        onFxRun(() -> view.bindMeters(feed));
        renderBlock(0.5f);
        onFxRun(dispatcher::pulse);

        assertThat(view.busMeter().hasSubmission())
                .as("a stage with no scene is skipped entirely by the pulse")
                .isFalse();
        assertThat(tileMeter(0).hasSubmission()).isFalse();

        attachScene();
        renderBlock(0.5f);
        onFxRun(dispatcher::pulse);

        assertThat(view.busMeter().hasSubmission())
                .as("once on screen the same subscription delivers")
                .isTrue();
    }

    @Test
    void renderedBlocksReachTheBusMeterPerLaneAndEveryTileMeter() throws Exception {
        attachScene();
        onFxRun(() -> view.bindMeters(feed));

        renderBlock(0.5f);
        onFxRun(dispatcher::pulse);

        assertThat(view.busMeter().consumeSubmittedPeakDb(0))
                .as("MASTER_OUT lane 0 peak")
                .isCloseTo(HALF_SCALE_DB, within(0.01));
        assertThat(view.busMeter().consumeSubmittedPeakDb(1))
                .as("MASTER_OUT lane 1 peak")
                .isCloseTo(HALF_SCALE_DB, within(0.01));
        assertThat(view.busMeter().consumeSubmittedRmsDb(0))
                .as("MASTER_OUT lane 0 RMS")
                .isCloseTo(HALF_SCALE_DB, within(0.01));

        for (int i = 0; i < tracks.size(); i++) {
            assertThat(tileMeter(i).consumeSubmittedPeakDb())
                    .as("tile %d aggregate peak", i)
                    .isCloseTo(HALF_SCALE_DB, within(0.01));
            assertThat(tileMeter(i).consumeSubmittedRmsDb())
                    .as("tile %d aggregate RMS", i)
                    .isCloseTo(HALF_SCALE_DB, within(0.01));
        }
    }

    @Test
    void unbindMetersReleasesEveryTokenAndStopsDelivery() throws Exception {
        attachScene();
        onFxRun(() -> view.bindMeters(feed));
        renderBlock(0.5f);
        onFxRun(dispatcher::pulse);
        double afterFirst = view.busMeter().consumeSubmittedPeakDb(0);

        onFxRun(view::unbindMeters);

        assertThat(view.meterSubscriptionCount()).isZero();
        assertThat(feed.subscriptionCount()).isZero();
        assertThat(bus.levelSubscriptionCount()).isZero();

        renderBlock(1.0f);
        onFxRun(dispatcher::pulse);

        assertThat(view.busMeter().consumeSubmittedPeakDb(0))
                .as("an unbound stage receives nothing further")
                .isEqualTo(afterFirst);
    }

    @Test
    void bindingTwiceReplacesRatherThanAccumulates() throws Exception {
        onFxRun(() -> view.bindMeters(feed));
        onFxRun(() -> view.bindMeters(feed));

        assertThat(view.meterSubscriptionCount()).isEqualTo(1 + tracks.size());
        assertThat(feed.subscriptionCount()).isEqualTo(1 + tracks.size());
    }

    @Test
    void unbindMetersIsSafeWithoutABind() {
        assertThat(view.meterSubscriptionCount()).isZero();
        view.unbindMeters();
        assertThat(view.meterSubscriptionCount()).isZero();
    }

    // ── Harness ───────────────────────────────────────────────────────────

    private static final class InertHost implements PerformanceStageView.Host {
        @Override public void onPlay() { }
        @Override public void onStop() { }
        @Override public void onRecord() { }
        @Override public void onToggleLoop() { }
        @Override public void onExitPerformanceStage() { }
        @Override public void onOpenAudioSettings() { }
        @Override public void onNewProject() { }
        @Override public void onOpenProject() { }
        @Override public void onSaveProject() { }
        @Override public void onRecentProjects() { }
    }

    private static <T> T onFx(Supplier<T> supplier) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(supplier.get());
            } catch (Throwable t) {
                err.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(15, TimeUnit.SECONDS)).as("FX action completes").isTrue();
        if (err.get() != null) {
            throw new AssertionError("FX action failed", err.get());
        }
        return ref.get();
    }

    private static void onFxRun(Runnable action) throws Exception {
        onFx(() -> {
            action.run();
            return null;
        });
    }
}
