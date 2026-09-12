package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.LevelMeterDisplay;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.metering.MeterFeed;
import com.benesquivelmusic.daw.app.ui.metering.VisibleMeterBinding;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.EffectsChain;
import com.benesquivelmusic.daw.core.audio.EngineBinder;
import com.benesquivelmusic.daw.core.audio.RenderPipeline;
import com.benesquivelmusic.daw.core.metering.MeteringTapBus;
import com.benesquivelmusic.daw.core.metering.TapSnapshot;
import com.benesquivelmusic.daw.core.mixer.ChannelLink;
import com.benesquivelmusic.daw.core.mixer.LinkMode;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.Send;
import com.benesquivelmusic.daw.core.mixer.SendMode;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.visualization.LevelData;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 318 — the "live strip test": a real {@link DawProject} (two audio
 * tracks plus the mixer's default return bus), a real {@link RenderPipeline}
 * and {@link com.benesquivelmusic.daw.core.mixer.Mixer} rendering a full-scale
 * sine, a real {@link MeteringTapBus} owned by a real {@link AudioEngine} and
 * bound through a real {@link EngineBinder}, and a real {@link MixerView}
 * whose strip meters subscribe through a {@link MeterFeed}.
 *
 * <p>What it pins:</p>
 * <ul>
 *   <li>after rendered blocks and one FX pulse, <em>every</em> track, return
 *       and master strip meter has been handed a level above its floor —
 *       the strips are no longer "permanently dark";</li>
 *   <li>when rendering stops, one pulse after the stale window puts every one
 *       of them back at the floor ("honest idle"), rather than freezing on the
 *       last value;</li>
 *   <li>{@link MixerView#refresh()} disposes the subscriptions of the strips
 *       it discards and subscribes the rebuilt ones — the live count is
 *       unchanged, so the app-scoped feed cannot accumulate dead meters.</li>
 * </ul>
 *
 * <p>Signal design is the {@code MeteringTapCorrectnessTest} one: 750 Hz at
 * 48 kHz in 512-frame blocks is eight whole cycles per block, so every block
 * carries the exact peak sample regardless of grid alignment.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class MixerViewMeterFeedTest {

    private static final double SAMPLE_RATE = 48_000.0;
    private static final int CHANNELS = 2;
    private static final int BLOCK = 512;
    private static final int CYCLES_PER_BLOCK = 8;
    private static final double TEMPO = 120.0;
    private static final double SAMPLES_PER_BEAT = SAMPLE_RATE * 60.0 / TEMPO;
    private static final int BLOCKS = 6;
    private static final int TOTAL_FRAMES = BLOCK * (BLOCKS + 4);
    private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, CHANNELS, 24, BLOCK);

    /** Two track strips + one return strip; the master strip's meter is separate. */
    private static final int EXPECTED_STRIP_METERS = 3;
    /** Strip meters plus the master's MASTER_OUT subscription. */
    private static final int EXPECTED_SUBSCRIPTIONS = EXPECTED_STRIP_METERS + 1;

    private DawProject project;
    private AudioEngine engine;
    private EngineBinder binder;
    private MeteringTapBus bus;
    private RenderPipeline pipeline;
    private EffectsChain masterChain;
    private float[][] output;
    private final float[] interleaved = new float[CHANNELS * BLOCK];
    private FxDispatcher dispatcher;
    private MeterFeed feed;
    private MixerView view;
    private Stage stage;

    // ── FX helpers (capture + rethrow — the swallowed-assertion pitfall) ──

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
        assertThat(latch.await(10, TimeUnit.SECONDS)).as("FX action completes").isTrue();
        if (err.get() != null) {
            throw new AssertionError("FX action threw", err.get());
        }
        return ref.get();
    }

    private static void onFxRun(Runnable action) throws Exception {
        onFx(() -> {
            action.run();
            return null;
        });
    }

    @BeforeEach
    void setUp() throws Exception {
        project = new DawProject("Meters", FORMAT);
        Track trackA = project.createAudioTrack("A");
        Track trackB = project.createAudioTrack("B");
        trackA.addClip(sineClip("A-clip"));
        trackB.addClip(sineClip("B-clip"));

        MixerChannel channelA = project.getMixerChannelForTrack(trackA);
        MixerChannel returnBus = project.getMixer().getReturnBuses().get(0);
        // A post-fader send so RETURN_POST carries signal — without one the
        // return strip would legitimately meter silence and the "above floor"
        // assertion would be untestable rather than false.
        channelA.addSend(new Send(returnBus, 0.5, SendMode.POST_FADER));

        project.getTransport().setTempo(TEMPO);
        project.getMixer().prepareForPlayback(CHANNELS, BLOCK);
        masterChain = new EffectsChain();
        masterChain.allocateIntermediateBuffers(CHANNELS, BLOCK);
        pipeline = new RenderPipeline(FORMAT, 8, BLOCK);
        output = new float[CHANNELS][BLOCK];

        engine = new AudioEngine(FORMAT);
        binder = new EngineBinder(engine);
        binder.bind(project);
        bus = engine.meteringTapBus();

        project.getTransport().play();

        dispatcher = new FxDispatcher();
        DawProject boundProject = project;
        FxDispatcher boundDispatcher = dispatcher;
        MeteringTapBus boundBus = bus;
        view = onFx(() -> {
            MeterFeed created = new MeterFeed(boundBus, boundDispatcher);
            MixerView mixerView = new MixerView(boundProject, null, boundDispatcher);
            mixerView.setMeterFeed(created);
            // A showing window is required for demand. Applying CSS creates
            // the ScrollPane skin that attaches the strips to the scene graph.
            stage = new Stage();
            stage.setScene(new Scene(new StackPane(mixerView), 900, 600));
            stage.show();
            mixerView.applyCss();
            mixerView.layout();
            boundDispatcher.pulse(); // Observe visibility and attach before rendering the next block.
            feed = created;
            return mixerView;
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        if (stage != null) {
            onFxRun(stage::close);
        }
        if (view != null) {
            onFxRun(() -> view.setMeterFeed(null));
        }
        if (feed != null) {
            onFxRun(feed::dispose);
        }
        if (binder != null) {
            binder.unbind();
        }
        if (engine != null) {
            engine.meteringTapBus().close();
        }
        view = null;
    }

    private static AudioClip sineClip(String name) {
        AudioClip clip = new AudioClip(name, 0.0, TOTAL_FRAMES / SAMPLES_PER_BEAT, null);
        float[][] data = new float[CHANNELS][TOTAL_FRAMES];
        for (int i = 0; i < TOTAL_FRAMES; i++) {
            float v = (float) Math.sin(2.0 * Math.PI * CYCLES_PER_BLOCK * i / BLOCK);
            data[0][i] = v;
            data[1][i] = v;
        }
        clip.setAudioData(data);
        return clip;
    }

    /** Renders one block exactly as {@code AudioEngine.processBlock} does. */
    private void renderBlock() {
        TapSnapshot taps = bus.snapshot();
        for (float[] lane : output) {
            Arrays.fill(lane, 0f);
        }
        pipeline.renderBlock(null, output, BLOCK, project.getTransport(), project.getMixer(),
                project.getTracks(), null, masterChain, null, null, null, null, null, null,
                null, taps, interleaved);
        bus.blockCompleted(taps);
    }

    private void renderBlocks(int count) {
        for (int i = 0; i < count; i++) {
            renderBlock();
        }
    }

    /** Every meter this view owns: the track / return strips plus the master. */
    private List<LevelMeterDisplay> allMeters() {
        List<LevelMeterDisplay> meters = new ArrayList<>(view.getStripMeterDisplays());
        meters.add(view.getMasterMeterDisplay());
        return meters;
    }

    @Test
    void everyStripMeterSubscribesItsOwnTapPoint() {
        assertThat(view.getMeterFeed()).as("the feed is retained").isSameAs(feed);
        assertThat(view.getStripMeterDisplays())
                .as("two track strips and the default return strip")
                .hasSize(EXPECTED_STRIP_METERS);
        assertThat(view.getMasterMeterDisplay()).as("the master strip meter").isNotNull();
        assertThat(view.getMasterMeterBinding()).as("MASTER_OUT binding").isNotNull();
        assertThat(feed.subscriptionCount()).isEqualTo(EXPECTED_SUBSCRIPTIONS);
        assertThat(bus.levelSubscriptionCount())
                .as("one engine token per strip meter")
                .isEqualTo(EXPECTED_SUBSCRIPTIONS);
    }

    @Test
    void replacingAnUnvisitedMixerLeavesNoAbandonedEntriesInTheFeed() throws Exception {
        onFxRun(() -> {
            stage.getScene().setRoot(new StackPane());
            assertMeterDemand(0);

            var previousProject = new DawProject("Unvisited project", FORMAT);
            previousProject.createAudioTrack("Unvisited track");
            var unvisited = new MixerView(previousProject, null, dispatcher);
            unvisited.setMeterFeed(feed);
            unvisited.refresh();
            assertThat(unvisited.getScene()).isNull();
            assertThat(unvisited.getStripMeterBindings()).isEmpty();
            assertThat(unvisited.getMasterMeterBinding()).isNull();
            assertMeterDemand(0);

            view = new MixerView(project, null, dispatcher);
            view.setMeterFeed(feed);
            assertMeterDemand(0);
            stage.getScene().setRoot(view);
            view.applyCss();
            view.layout();
            assertMeterDemand(EXPECTED_SUBSCRIPTIONS);
            stage.getScene().setRoot(new StackPane());
            dispatcher.pulse();
            assertMeterDemand(0);
        });
    }

    @Test
    void hidingTheWindowReleasesDemandAndStopsDeliveryUntilShownAgain() throws Exception {
        renderBlock();
        onFxRun(dispatcher::pulse);
        assertThat(view.getMasterMeterDisplay().getPendingPeakDb()).isGreaterThan(-60.0);
        VisibleMeterBinding masterBefore = view.getMasterMeterBinding();

        onFxRun(() -> {
            stage.hide();
            assertThat(view.getScene()).as("floating docks retain their Scene when hidden").isNotNull();
            assertMeterDemand(0);
            allMeters().forEach(meter -> meter.update(LevelData.SILENCE));
        });
        renderBlock();
        onFxRun(() -> {
            dispatcher.pulse();
            assertMeterDemand(0);
            assertThat(allMeters()).allSatisfy(meter ->
                    assertThat(meter.getPendingPeakDb()).isEqualTo(Double.NEGATIVE_INFINITY));
            stage.show();
            assertMeterDemand(EXPECTED_SUBSCRIPTIONS);
            assertThat(view.getMasterMeterBinding()).isSameAs(masterBefore);
        });

        renderBlock();
        onFxRun(dispatcher::pulse);
        assertThat(allMeters()).allSatisfy(meter ->
                assertThat(meter.getPendingPeakDb()).isGreaterThan(-60.0));
    }

    @Test
    void hidingAnAncestorOrTheMixerReleasesEveryMeterImmediately() throws Exception {
        onFxRun(() -> {
            var parent = (StackPane) stage.getScene().getRoot();
            parent.setVisible(false);
            assertMeterDemand(0);
            dispatcher.pulse();
            assertThat(allMeters()).allSatisfy(meter ->
                    assertThat(meter.getPendingPeakDb()).isEqualTo(-120.0));
            parent.setVisible(true);
            assertMeterDemand(EXPECTED_SUBSCRIPTIONS);
            view.setVisible(false);
            assertMeterDemand(0);
            view.setVisible(true);
            assertMeterDemand(EXPECTED_SUBSCRIPTIONS);
        });
    }

    @Test
    void refreshingWhileTheWindowIsHiddenReactivatesOnlyTheCurrentStrips() throws Exception {
        List<LevelMeterDisplay> discardedMeters = view.getStripMeterDisplays();
        VisibleMeterBinding masterBefore = view.getMasterMeterBinding();
        onFxRun(() -> {
            stage.hide();
            view.refresh();
            view.applyCss();
            view.layout();
            dispatcher.pulse();
            assertMeterDemand(0);
            assertThat(view.getMasterMeterBinding()).isSameAs(masterBefore);
            stage.show();
            assertMeterDemand(EXPECTED_SUBSCRIPTIONS);
        });

        renderBlock();
        onFxRun(dispatcher::pulse);
        assertThat(discardedMeters).allSatisfy(meter ->
                assertThat(meter.getPendingPeakDb()).isEqualTo(-120.0));
        assertThat(allMeters()).allSatisfy(meter ->
                assertThat(meter.getPendingPeakDb()).isGreaterThan(-60.0));
    }

    @Test
    void clearingTheFeedPreventsVisibilityChangesFromResurrectingSubscriptions() throws Exception {
        onFxRun(() -> {
            view.setMeterFeed(null);
            assertMeterDemand(0);
            stage.hide();
            stage.show();
            view.refresh();
            view.applyCss();
            view.layout();
            assertMeterDemand(0);
            stage.hide();
            view.setMeterFeed(feed);
            assertMeterDemand(0);
            stage.show();
            assertMeterDemand(EXPECTED_SUBSCRIPTIONS);
        });
    }

    private void assertMeterDemand(int expected) {
        assertThat(feed.subscriptionCount()).as("entries visited by every FX pulse").isEqualTo(expected);
        assertThat(bus.levelSubscriptionCount()).as("engine level demand").isEqualTo(expected);
    }

    @Test
    void renderedPlaybackPutsEveryStripAndMasterMeterAboveTheFloor() throws Exception {
        // Guard: nothing has been fed yet, so every meter sits at its floor.
        for (LevelMeterDisplay meter : allMeters()) {
            assertThat(meter.getPendingPeakDb())
                    .as("meter is dark before the first pulse")
                    .isEqualTo(-120.0);
        }

        renderBlocks(BLOCKS);
        onFxRun(dispatcher::pulse);

        for (LevelMeterDisplay meter : allMeters()) {
            assertThat(meter.getPendingPeakDb())
                    .as("post-fader peak reached the strip meter")
                    .isGreaterThan(-60.0);
            assertThat(meter.getPendingRmsDb())
                    .as("post-fader RMS reached the strip meter")
                    .isGreaterThan(-60.0);
        }
    }

    @Test
    void whenRenderingStopsTheStaleWindowReturnsEveryMeterToTheFloor() throws Exception {
        renderBlocks(BLOCKS);
        onFxRun(dispatcher::pulse);
        assertThat(view.getMasterMeterDisplay().getPendingPeakDb()).isGreaterThan(-60.0);

        // No further blocks: after STALE_NANOS the feed delivers exactly one
        // silent frame per subscription and the meters fall to the floor.
        Thread.sleep(MeterFeed.STALE_NANOS / 1_000_000L + 80L);
        onFxRun(dispatcher::pulse);

        for (LevelMeterDisplay meter : allMeters()) {
            assertThat(meter.getPendingPeakDb())
                    .as("silent frame drove the meter to its floor")
                    .isEqualTo(Double.NEGATIVE_INFINITY);
            assertThat(meter.getPendingRmsDb())
                    .as("silent frame drove the meter to its floor")
                    .isEqualTo(Double.NEGATIVE_INFINITY);
        }
    }

    @Test
    void refreshDisposesTheDiscardedStripSubscriptionsAndSubscribesTheRebuiltOnes()
            throws Exception {
        List<VisibleMeterBinding> before = view.getStripMeterBindings();
        List<LevelMeterDisplay> discardedMeters = view.getStripMeterDisplays();
        VisibleMeterBinding masterBefore = view.getMasterMeterBinding();
        assertThat(before).hasSize(EXPECTED_STRIP_METERS);
        assertThat(feed.subscriptionCount()).isEqualTo(EXPECTED_SUBSCRIPTIONS);

        onFxRun(() -> {
            view.refresh();
            view.applyCss();
            view.layout();
            dispatcher.pulse();
        });

        assertThat(view.getMasterMeterBinding())
                .as("the master strip is not rebuilt, so its binding survives")
                .isSameAs(masterBefore);
        assertThat(view.getStripMeterBindings())
                .as("the rebuilt strips are subscribed")
                .hasSize(EXPECTED_STRIP_METERS)
                .doesNotContainAnyElementsOf(before);
        assertThat(feed.subscriptionCount())
                .as("refresh() must not leak subscriptions into the app-scoped feed")
                .isEqualTo(EXPECTED_SUBSCRIPTIONS);
        assertThat(bus.levelSubscriptionCount()).isEqualTo(EXPECTED_SUBSCRIPTIONS);

        // The rebuilt strips are live: they meter the next rendered blocks.
        renderBlocks(BLOCKS);
        onFxRun(dispatcher::pulse);
        assertThat(discardedMeters).allSatisfy(meter ->
                assertThat(meter.getPendingPeakDb())
                        .as("discarded strip receives no frames after refresh")
                        .isEqualTo(-120.0));
        for (LevelMeterDisplay meter : allMeters()) {
            assertThat(meter.getPendingPeakDb())
                    .as("rebuilt strip meter is fed")
                    .isGreaterThan(-60.0);
        }
    }

    @Test
    void detachingTheViewFromItsSceneReleasesEveryMeterSubscription() throws Exception {
        assertThat(feed.subscriptionCount()).isEqualTo(EXPECTED_SUBSCRIPTIONS);

        // Navigation removes the cached view from its host's children.
        onFxRun(() -> ((StackPane) view.getParent()).getChildren().remove(view));

        assertThat(view.getScene()).as("the view left the scene graph").isNull();
        assertThat(feed.subscriptionCount())
                .as("a detached MixerView holds no subscription in the app-scoped feed")
                .isZero();
        assertThat(view.getMasterMeterBinding()).isNull();
    }

    /**
     * The view switch that actually happens in the app: the
     * {@code ViewNavigationController} caches this MixerView, drops it out of
     * the {@code BorderPane}'s centre when another view is shown, and puts
     * the SAME instance back on return — never calling {@code setMeterFeed}
     * again. Without a re-attach branch every mixer meter would be dark
     * forever after the first view switch.
     */
    @Test
    void reAttachingTheViewSubscribesEveryMeterAgain() throws Exception {
        StackPane host = onFx(() -> (StackPane) view.getParent());
        onFxRun(() -> host.getChildren().remove(view));
        assertThat(feed.subscriptionCount()).isZero();

        onFxRun(() -> {
            host.getChildren().add(view);
            view.applyCss();
            view.layout();
            dispatcher.pulse();
        });

        assertThat(view.getScene()).as("the view is back in the scene graph").isNotNull();
        assertThat(view.getMasterMeterBinding())
                .as("the master strip re-subscribes MASTER_OUT").isNotNull();
        assertThat(view.getStripMeterBindings()).hasSize(EXPECTED_STRIP_METERS);
        assertThat(feed.subscriptionCount()).isEqualTo(EXPECTED_SUBSCRIPTIONS);
        assertThat(bus.levelSubscriptionCount()).isEqualTo(EXPECTED_SUBSCRIPTIONS);

        // And they are live, not merely counted: floor every meter first, so
        // a stale reading left over from before the detach cannot pass.
        List<LevelMeterDisplay> meters = allMeters();
        onFxRun(() -> meters.forEach(meter -> meter.update(LevelData.SILENCE)));
        renderBlocks(BLOCKS);
        onFxRun(dispatcher::pulse);
        for (LevelMeterDisplay meter : meters) {
            assertThat(meter.getPendingPeakDb())
                    .as("a re-mounted strip meter is fed again")
                    .isGreaterThan(-60.0);
        }
    }

    /**
     * A {@code refresh()} driven from outside the Mixer — a track created
     * while another view is on screen ({@code TrackCreationController} calls
     * it unconditionally) — must not re-acquire live tokens on a detached
     * view. That view may be replaced without ever being shown again, in
     * which case the app-scoped feed would keep it, its displays and the old
     * project's channels alive for the life of the process.
     */
    @Test
    void refreshingWhileDetachedAcquiresNoSubscriptionsAndReAttachRestoresThem() throws Exception {
        StackPane host = onFx(() -> (StackPane) view.getParent());
        onFxRun(() -> host.getChildren().remove(view));
        assertThat(feed.subscriptionCount()).isZero();

        onFxRun(view::refresh);

        assertThat(view.getStripMeterDisplays())
                .as("the strips were still rebuilt").hasSize(EXPECTED_STRIP_METERS);
        assertThat(view.getStripMeterBindings())
                .as("a detached view acquires no strip subscription on refresh").isEmpty();
        assertThat(feed.subscriptionCount())
                .as("nothing was handed to the app-scoped feed").isZero();

        onFxRun(() -> {
            host.getChildren().add(view);
            view.applyCss();
            view.layout();
        });
        assertThat(feed.subscriptionCount())
                .as("re-attach subscribes the strips the detached refresh rebuilt")
                .isEqualTo(EXPECTED_SUBSCRIPTIONS);
    }

    /**
     * The detach branch releases the channel-link and undo-history listeners
     * too; the re-attach branch has to put them back, or a channel-link edit
     * stops re-rendering the strips for the rest of the session (the
     * ViewNavigationController re-mounts the same instance).
     */
    @Test
    void reAttachingTheViewRestoresTheChannelLinkListener() throws Exception {
        StackPane host = onFx(() -> (StackPane) view.getParent());
        onFxRun(() -> host.getChildren().remove(view));
        onFxRun(() -> {
            host.getChildren().add(view);
            view.applyCss();
            view.layout();
        });

        List<LevelMeterDisplay> before = view.getStripMeterDisplays();
        List<MixerChannel> channels = project.getMixer().getChannels();
        ChannelLink link = new ChannelLink(channels.get(0).getId(), channels.get(1).getId(),
                LinkMode.ABSOLUTE, true, true, true, false, false);

        onFxRun(() -> project.getChannelLinkManager().link(link));

        assertThat(view.getStripMeterDisplays())
                .as("the restored channel-link listener re-rendered the strips")
                .isNotEqualTo(before);
        assertThat(feed.subscriptionCount())
                .as("and the rebuilt strips are subscribed exactly once each")
                .isEqualTo(EXPECTED_SUBSCRIPTIONS);
    }
}
