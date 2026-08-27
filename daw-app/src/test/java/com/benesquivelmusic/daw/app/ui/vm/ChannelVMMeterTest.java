package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.metering.MeterFeed;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.metering.LevelTapSlot;
import com.benesquivelmusic.daw.core.metering.MeterTapPoint;
import com.benesquivelmusic.daw.core.metering.MeteringTapBus;
import com.benesquivelmusic.daw.core.metering.TapSnapshot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 318 — {@code ChannelVM.meterLevel} finally has a producer. A real
 * {@link MeteringTapBus} bound to a real {@link Mixer}, frames written through
 * the {@link LevelTapSlot} RT API exactly as {@code Mixer.mixDown} writes
 * them, and the FX pulse driven by hand: the VM's continuous property must
 * carry the rendered post-fader <em>peak in dBFS</em>, floored at
 * {@link ChannelVM#METER_FLOOR_DB} and never {@code NaN} or
 * {@code -Infinity} (the continuous channel rejects the former and no binding
 * should ever see the latter).
 */
class ChannelVMMeterTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 24, 64);
    private static final int BLOCK = 64;
    /** 20·log10(0.5) — the dB the tap must report for a half-scale block. */
    private static final double HALF_SCALE_DB = -6.0206;

    private MeteringTapBus bus;
    private Mixer mixer;
    private MixerChannel channel;
    private FxDispatcher dispatcher;
    private MeterFeed feed;
    private ChannelVM vm;

    @BeforeAll
    static void initToolkit() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    @BeforeEach
    void setUp() {
        bus = new MeteringTapBus();
        mixer = new Mixer();
        channel = new MixerChannel("Drums");
        mixer.addChannel(channel);
        bus.rebind(mixer, FORMAT, 1L);
        dispatcher = new FxDispatcher();
        feed = new MeterFeed(bus, dispatcher);
        vm = new ChannelVM(channel, dispatcher);
    }

    @AfterEach
    void tearDown() {
        vm.dispose();
        feed.dispose();
        bus.close();
    }

    /** Publishes one block of constant {@code level} into this channel's slot. */
    private void renderBlock(float level) {
        TapSnapshot taps = bus.snapshot();
        LevelTapSlot slot = taps.channelSlot(0, channel);
        float[] lane = new float[BLOCK];
        Arrays.fill(lane, level);
        slot.beginBlock(taps.epoch(), taps.blockIndex(), 2);
        slot.accumulate(0, lane, BLOCK);
        slot.accumulate(1, lane, BLOCK);
        slot.publish(BLOCK);
        bus.blockCompleted(taps);
    }

    @Test
    void meterLevelStartsAtTheFloorNotAtFullScale() {
        assertThat(ChannelVM.METER_FLOOR_DB).isEqualTo(-120.0);
        assertThat(vm.getMeterLevel())
                .as("an unmetered channel reads as silence, not 0 dBFS")
                .isEqualTo(ChannelVM.METER_FLOOR_DB);
        assertThat(vm.isMeterBound()).isFalse();
    }

    @Test
    void aBoundMeterPublishesTheRenderedPeakInDbfs() {
        vm.bindMeter(feed);
        assertThat(vm.isMeterBound()).isTrue();
        assertThat(feed.subscriptionCount()).isEqualTo(1);
        assertThat(bus.levelSubscriptionCount()).isEqualTo(1);

        renderBlock(0.5f);
        dispatcher.pulse();

        assertThat(vm.getMeterLevel())
                .as("half scale is −6.02 dBFS")
                .isCloseTo(HALF_SCALE_DB, within(0.01));
    }

    @Test
    void aSilentBlockClampsToTheFloorInsteadOfNegativeInfinity() {
        vm.bindMeter(feed);
        renderBlock(0.5f);
        dispatcher.pulse();
        assertThat(vm.getMeterLevel()).isGreaterThan(ChannelVM.METER_FLOOR_DB);

        renderBlock(0.0f);
        dispatcher.pulse();

        assertThat(vm.getMeterLevel())
                .as("digital silence is floored, never −Infinity")
                .isEqualTo(ChannelVM.METER_FLOOR_DB);
        assertThat(Double.isFinite(vm.getMeterLevel())).isTrue();
    }

    @Test
    void unbindStopsFurtherUpdatesAndLeavesTheLastValue() {
        vm.bindMeter(feed);
        renderBlock(0.5f);
        dispatcher.pulse();
        double afterFirst = vm.getMeterLevel();

        vm.unbindMeter();
        assertThat(vm.isMeterBound()).isFalse();
        assertThat(feed.subscriptionCount()).isZero();
        assertThat(bus.levelSubscriptionCount()).isZero();

        renderBlock(1.0f);
        dispatcher.pulse();

        assertThat(vm.getMeterLevel())
                .as("an unbound VM receives nothing further")
                .isEqualTo(afterFirst);
    }

    @Test
    void bindingTwiceReplacesTheSubscriptionRatherThanAccumulating() {
        vm.bindMeter(feed);
        vm.bindMeter(feed);

        assertThat(feed.subscriptionCount()).isEqualTo(1);
        assertThat(bus.levelSubscriptionCount()).isEqualTo(1);
    }

    @Test
    void disposeUnbindsTheMeterBeforeClosingTheContinuousChannel() {
        vm.bindMeter(feed);
        assertThat(feed.subscriptionCount()).isEqualTo(1);
        int channelsBefore = dispatcher.openChannelCount();

        vm.dispose();

        assertThat(feed.subscriptionCount())
                .as("dispose() releases the tap-bus subscription")
                .isZero();
        assertThat(dispatcher.openChannelCount()).isEqualTo(channelsBefore - 1);

        // A pulse after disposal must not publish into the closed channel.
        renderBlock(1.0f);
        dispatcher.pulse();
        assertThat(vm.isMeterBound()).isFalse();
    }

    @Test
    void bindingADisposedVmIsRejected() {
        vm.dispose();
        assertThatIllegalStateException().isThrownBy(() -> vm.bindMeter(feed));
    }

    @Test
    void theRegistryBindsEveryChannelVmAndReleasesThemOnDispose() {
        DawProject project = new DawProject("Meters", FORMAT);
        Track drums = project.createAudioTrack("Drums");
        Track bass = project.createAudioTrack("Bass");
        MeteringTapBus projectBus = new MeteringTapBus();
        projectBus.rebind(project.getMixer(), FORMAT, 1L);
        FxDispatcher projectDispatcher = new FxDispatcher();
        MeterFeed projectFeed = new MeterFeed(projectBus, projectDispatcher);
        TrackChannelRegistry registry =
                new TrackChannelRegistry(project, projectDispatcher, projectFeed);
        try {
            assertThat(registry.channelVms()).hasSize(2);
            assertThat(registry.channelVms()).allSatisfy(channelVm ->
                    assertThat(channelVm.isMeterBound()).isTrue());
            assertThat(projectFeed.subscriptionCount()).isEqualTo(2);

            ChannelVM drumsVm = registry.channelVm(UUID.fromString(drums.getId()));
            ChannelVM bassVm = registry.channelVm(UUID.fromString(bass.getId()));
            assertThat(drumsVm).isNotNull();
            assertThat(bassVm).isNotNull();

            renderInto(projectBus, project.getMixerChannelForTrack(drums), 0.5f);
            projectDispatcher.pulse();

            assertThat(drumsVm.getMeterLevel()).isCloseTo(HALF_SCALE_DB, within(0.01));
            assertThat(bassVm.getMeterLevel())
                    .as("a channel with no rendered signal stays at the floor")
                    .isEqualTo(ChannelVM.METER_FLOOR_DB);
        } finally {
            registry.dispose();
            assertThat(projectFeed.subscriptionCount())
                    .as("registry dispose() releases every meter subscription")
                    .isZero();
            projectFeed.dispose();
            projectBus.close();
        }
    }

    @Test
    void theRegistryWithoutAFeedLeavesEveryMeterUnbound() {
        DawProject project = new DawProject("Meters", FORMAT);
        project.createAudioTrack("Drums");
        TrackChannelRegistry registry = new TrackChannelRegistry(project, new FxDispatcher());
        try {
            assertThat(registry.channelVms()).isNotEmpty();
            assertThat(registry.channelVms()).allSatisfy(channelVm -> {
                assertThat(channelVm.isMeterBound()).isFalse();
                assertThat(channelVm.getMeterLevel()).isEqualTo(ChannelVM.METER_FLOOR_DB);
            });
        } finally {
            registry.dispose();
        }
    }

    /** Publishes one block into {@code target}'s slot on {@code targetBus}. */
    private static void renderInto(MeteringTapBus targetBus, MixerChannel target, float level) {
        TapSnapshot taps = targetBus.snapshot();
        LevelTapSlot slot = null;
        for (int i = 0; slot == null && i < 8; i++) {
            slot = taps.channelSlot(i, target);
        }
        assertThat(slot).as("a slot exists for %s", target.getName()).isNotNull();
        float[] lane = new float[BLOCK];
        Arrays.fill(lane, level);
        slot.beginBlock(taps.epoch(), taps.blockIndex(), 2);
        slot.accumulate(0, lane, BLOCK);
        slot.accumulate(1, lane, BLOCK);
        slot.publish(BLOCK);
        targetBus.blockCompleted(taps);
    }
}
