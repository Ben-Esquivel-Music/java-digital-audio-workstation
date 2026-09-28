package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.controls.MixerChannelStrip;
import com.benesquivelmusic.daw.app.ui.display.LevelMeterDisplay;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.metering.MeterFeed;
import com.benesquivelmusic.daw.app.ui.metering.VisibleMeterBinding;
import com.benesquivelmusic.daw.app.ui.vm.ChannelVM;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mastering.MasteringChain;
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
 * <p>Since story 322 (the story-271 skin swap) a track strip is a
 * {@link MixerChannelStrip} whose integrated meter is fed by its
 * {@link ChannelVM}: the VM owns the {@code CHANNEL_POST} subscription (the
 * strip is the visibility-owning surface) and relays the peak into
 * {@link MixerChannelStrip#meterPeakDbProperty()}, floored at
 * {@link ChannelVM#METER_FLOOR_DB}. The return strip and the master still use
 * a view-registered {@link LevelMeterDisplay}. Every meter of both kinds is
 * probed through one {@link MeterProbe} seam; subscription counts cover both
 * (two track VMs + one return display + the master).</p>
 *
 * <p>Signal design is the {@code MeteringTapCorrectnessTest} one: 750 Hz at
 * 48 kHz in 512-frame blocks is eight whole cycles per block, so every block
 * carries the exact peak sample regardless of grid alignment — and therefore
 * the <em>same</em> peak every block. A VM republishes only a changed value,
 * so a test that floors a strip and then expects a fresh delivery renders at
 * a distinct channel gain ({@link #renderBlocksAtDistinctGain}).</p>
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

    /** Two track strips (ChannelVM-owned meters). */
    private static final int EXPECTED_TRACK_STRIPS = 2;
    /** One return strip (a view-registered LevelMeterDisplay); the master's meter is separate. */
    private static final int EXPECTED_RETURN_METERS = 1;
    /** Track VMs + return display + the master's MASTER_OUT subscription. */
    private static final int EXPECTED_SUBSCRIPTIONS = EXPECTED_TRACK_STRIPS + EXPECTED_RETURN_METERS + 1;
    /** Every meter kind reads at or below this once floored (−120 dBFS strip floor; −∞ display). */
    private static final double FLOOR_DB = ChannelVM.METER_FLOOR_DB;

    private DawProject project;
    private AudioEngine engine;
    private EngineBinder binder;
    private MeteringTapBus bus;
    private RenderPipeline pipeline;
    private MasteringChain masterChain;
    private float[][] output;
    private final float[] interleaved = new float[CHANNELS * BLOCK];
    private FxDispatcher dispatcher;
    private MeterFeed feed;
    private MixerView view;
    private Stage stage;
    /** Channel gain of the next {@link #renderBlocksAtDistinctGain} pass (see the class Javadoc). */
    private double distinctGain = 1.0;

    /** One meter of either kind: what it currently shows and how to floor it by hand. */
    private interface MeterProbe {
        String name();
        double pendingPeakDb();
        void floor();
    }

    private static MeterProbe probe(String name, LevelMeterDisplay display) {
        return new MeterProbe() {
            @Override public String name() { return name; }
            @Override public double pendingPeakDb() { return display.getPendingPeakDb(); }
            @Override public void floor() { display.update(LevelData.SILENCE); }
        };
    }

    private static MeterProbe probe(String name, MixerChannelStrip strip) {
        return new MeterProbe() {
            @Override public String name() { return name; }
            @Override public double pendingPeakDb() { return strip.getMeterPeakDb(); }
            @Override public void floor() { strip.setMeterPeakDb(FLOOR_DB); }
        };
    }

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
        pipeline = new RenderPipeline(FORMAT, 8, BLOCK);
        output = new float[CHANNELS][BLOCK];

        engine = new AudioEngine(FORMAT);
        masterChain = engine.getMasteringChain();
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

    /**
     * Renders {@code count} blocks at a channel gain no earlier pass used, so
     * the post-fader peak every tap reports differs from the last delivered
     * one and a {@link ChannelVM} — which republishes only a changed value —
     * relays it into a strip that was floored by hand. Stays above −6 dB.
     */
    private void renderBlocksAtDistinctGain(int count) {
        distinctGain -= 0.05;
        for (MixerChannel channel : project.getMixer().getChannels()) {
            channel.setVolume(distinctGain);
        }
        renderBlocks(count);
    }

    private List<MixerChannelStrip> trackStrips() {
        return view.getTrackStrips().stream().map(MixerView.TrackStripHandles::strip).toList();
    }

    private ChannelVM channelVmOf(MixerChannelStrip strip) {
        return view.getTrackControlWiring().registry().channelVm(strip.getChannelId());
    }

    /** The view-registered displays: the return strip's plus the master's. */
    private List<LevelMeterDisplay> displays() {
        List<LevelMeterDisplay> meters = new ArrayList<>(view.getStripMeterDisplays());
        meters.add(view.getMasterMeterDisplay());
        return meters;
    }

    /** Every meter this view owns: the track strips, the return strip and the master. */
    private List<MeterProbe> allMeters() {
        List<MeterProbe> probes = new ArrayList<>();
        for (MixerChannelStrip strip : trackStrips()) {
            probes.add(probe("track strip " + strip.getChannelName(), strip));
        }
        for (LevelMeterDisplay display : view.getStripMeterDisplays()) {
            probes.add(probe("return strip display", display));
        }
        probes.add(probe("master display", view.getMasterMeterDisplay()));
        return probes;
    }

    private static List<Double> peaksOf(List<MeterProbe> probes) {
        return probes.stream().map(MeterProbe::pendingPeakDb).toList();
    }

    private static void assertAllAboveFloor(List<MeterProbe> probes, String why) {
        for (MeterProbe probe : probes) {
            assertThat(probe.pendingPeakDb()).as(why + ": " + probe.name()).isGreaterThan(-60.0);
        }
    }

    private static void assertAllAtFloor(List<MeterProbe> probes, String why) {
        for (MeterProbe probe : probes) {
            assertThat(probe.pendingPeakDb()).as(why + ": " + probe.name()).isLessThanOrEqualTo(FLOOR_DB);
        }
    }

    @Test
    void everyStripMeterSubscribesItsOwnTapPoint() {
        assertThat(view.getMeterFeed()).as("the feed is retained").isSameAs(feed);
        assertThat(view.getStripMeterDisplays())
                .as("the default return strip is the one view-registered strip display")
                .hasSize(EXPECTED_RETURN_METERS);
        assertThat(trackStrips()).as("two track strips").hasSize(EXPECTED_TRACK_STRIPS);
        for (MixerChannelStrip strip : trackStrips()) {
            assertThat(channelVmOf(strip).isMeterBound())
                    .as("track strip %s meters through its ChannelVM", strip.getChannelName())
                    .isTrue();
        }
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
            allMeters().forEach(MeterProbe::floor);
        });
        renderBlock();
        onFxRun(() -> {
            dispatcher.pulse();
            assertMeterDemand(0);
            assertAllAtFloor(allMeters(), "nothing is delivered while the window is hidden");
            stage.show();
            assertMeterDemand(EXPECTED_SUBSCRIPTIONS);
            assertThat(view.getMasterMeterBinding()).isSameAs(masterBefore);
        });

        // A distinct gain: the track VMs still hold the pre-hide peak, and an
        // identical re-delivery would (correctly) not republish into the
        // hand-floored strips.
        renderBlocksAtDistinctGain(1);
        onFxRun(dispatcher::pulse);
        assertAllAboveFloor(allMeters(), "delivery resumes once shown again");
    }

    @Test
    void hidingAnAncestorOrTheMixerReleasesEveryMeterImmediately() throws Exception {
        onFxRun(() -> {
            var parent = (StackPane) stage.getScene().getRoot();
            var pendingBeforeHide = peaksOf(allMeters());
            parent.setVisible(false);
            assertMeterDemand(0);
            dispatcher.pulse();
            assertThat(peaksOf(allMeters()))
                    .as("a hidden surface receives no frame and retains its last pending value")
                    .containsExactlyElementsOf(pendingBeforeHide);
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
        List<MeterProbe> discardedMeters = allMeters();
        var discardedPeaks = peaksOf(discardedMeters);
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
        // The master probe is shared by both lists (its display is never
        // rebuilt), so compare the discarded STRIP probes only.
        List<MeterProbe> discardedStrips = discardedMeters.subList(0, discardedMeters.size() - 1);
        assertThat(peaksOf(discardedStrips))
                .as("a discarded strip receives no frames after the hidden refresh")
                .containsExactlyElementsOf(discardedPeaks.subList(0, discardedPeaks.size() - 1));
        assertAllAboveFloor(allMeters(), "the rebuilt strips are fed once shown");
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
        // The fixture visibility pulse may already have delivered a silent frame.
        // Both untouched defaults and raw silence are below the visible meter floor.
        assertAllAtFloor(allMeters(), "meter is dark before the first audio block");

        renderBlocks(BLOCKS);
        onFxRun(dispatcher::pulse);

        assertAllAboveFloor(allMeters(), "post-fader peak reached the strip meter");
        for (LevelMeterDisplay meter : displays()) {
            assertThat(meter.getPendingRmsDb())
                    .as("post-fader RMS reached the strip display")
                    .isGreaterThan(-60.0);
        }
    }

    @Test
    void whenRenderingStopsTheStaleWindowReturnsEveryMeterToTheFloor() throws Exception {
        renderBlocks(BLOCKS);
        onFxRun(dispatcher::pulse);
        assertAllAboveFloor(allMeters(), "rendered blocks reached every meter");

        // No further blocks: after STALE_NANOS the feed delivers exactly one
        // silent frame per subscription and the meters fall to the floor.
        Thread.sleep(MeterFeed.STALE_NANOS / 1_000_000L + 80L);
        onFxRun(dispatcher::pulse);

        assertAllAtFloor(allMeters(), "silent frame drove the meter to its floor");
        for (LevelMeterDisplay meter : displays()) {
            assertThat(meter.getPendingPeakDb())
                    .as("silent frame drove the display to digital silence")
                    .isEqualTo(Double.NEGATIVE_INFINITY);
            assertThat(meter.getPendingRmsDb())
                    .as("silent frame drove the display to digital silence")
                    .isEqualTo(Double.NEGATIVE_INFINITY);
        }
    }

    @Test
    void refreshDisposesTheDiscardedStripSubscriptionsAndSubscribesTheRebuiltOnes()
            throws Exception {
        List<VisibleMeterBinding> before = view.getStripMeterBindings();
        List<MixerChannelStrip> discardedStrips = trackStrips();
        List<MeterProbe> discardedMeters = allMeters();
        var discardedPeaks = peaksOf(discardedMeters);
        VisibleMeterBinding masterBefore = view.getMasterMeterBinding();
        assertThat(before).hasSize(EXPECTED_RETURN_METERS);
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
                .as("the rebuilt return strip is subscribed")
                .hasSize(EXPECTED_RETURN_METERS)
                .doesNotContainAnyElementsOf(before);
        assertThat(trackStrips())
                .as("the track strips were rebuilt")
                .hasSize(EXPECTED_TRACK_STRIPS)
                .doesNotContainAnyElementsOf(discardedStrips);
        assertThat(feed.subscriptionCount())
                .as("refresh() must not leak subscriptions into the app-scoped feed")
                .isEqualTo(EXPECTED_SUBSCRIPTIONS);
        assertThat(bus.levelSubscriptionCount()).isEqualTo(EXPECTED_SUBSCRIPTIONS);

        // The rebuilt strips are live: they meter the next rendered blocks.
        renderBlocks(BLOCKS);
        onFxRun(dispatcher::pulse);
        List<MeterProbe> discardedStripProbes = discardedMeters.subList(0, discardedMeters.size() - 1);
        assertThat(peaksOf(discardedStripProbes))
                .as("discarded strip receives no frames after refresh")
                .containsExactlyElementsOf(discardedPeaks.subList(0, discardedPeaks.size() - 1));
        assertAllAboveFloor(allMeters(), "rebuilt strip meter is fed");
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
        for (MixerChannelStrip strip : trackStrips()) {
            assertThat(channelVmOf(strip).isMeterBound())
                    .as("a detached view releases the VM-owned meter of %s", strip.getChannelName())
                    .isFalse();
        }
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
        assertThat(view.getStripMeterBindings()).hasSize(EXPECTED_RETURN_METERS);
        for (MixerChannelStrip strip : trackStrips()) {
            assertThat(channelVmOf(strip).isMeterBound())
                    .as("re-attach re-binds the VM-owned meter of %s", strip.getChannelName())
                    .isTrue();
        }
        assertThat(feed.subscriptionCount()).isEqualTo(EXPECTED_SUBSCRIPTIONS);
        assertThat(bus.levelSubscriptionCount()).isEqualTo(EXPECTED_SUBSCRIPTIONS);

        // And they are live, not merely counted: floor every meter first, so
        // a stale reading left over from before the detach cannot pass.
        List<MeterProbe> meters = allMeters();
        onFxRun(() -> meters.forEach(MeterProbe::floor));
        renderBlocksAtDistinctGain(BLOCKS);
        onFxRun(dispatcher::pulse);
        assertAllAboveFloor(meters, "a re-mounted strip meter is fed again");
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
                .as("the return strip was still rebuilt").hasSize(EXPECTED_RETURN_METERS);
        assertThat(trackStrips())
                .as("the track strips were still rebuilt").hasSize(EXPECTED_TRACK_STRIPS);
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
        List<MixerChannelStrip> stripsBefore = trackStrips();
        List<MixerChannel> channels = project.getMixer().getChannels();
        ChannelLink link = new ChannelLink(channels.get(0).getId(), channels.get(1).getId(),
                LinkMode.ABSOLUTE, true, true, true, false);

        onFxRun(() -> project.getChannelLinkManager().link(link));

        assertThat(view.getStripMeterDisplays())
                .as("the restored channel-link listener re-rendered the return strip")
                .isNotEqualTo(before);
        assertThat(trackStrips())
                .as("the restored channel-link listener re-rendered the track strips")
                .doesNotContainAnyElementsOf(stripsBefore);
        assertThat(feed.subscriptionCount())
                .as("and the rebuilt strips are subscribed exactly once each")
                .isEqualTo(EXPECTED_SUBSCRIPTIONS);
    }
}
