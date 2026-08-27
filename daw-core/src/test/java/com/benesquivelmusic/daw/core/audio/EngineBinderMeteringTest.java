package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.metering.LevelSubscription;
import com.benesquivelmusic.daw.core.metering.MeterTapPoint;
import com.benesquivelmusic.daw.core.metering.MeteringTapBus;
import com.benesquivelmusic.daw.core.metering.TapSnapshot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Story 318 — the {@link EngineBinder} is the one binding point of the
 * engine's {@link MeteringTapBus}: {@code bind} rebinds it under the new
 * epoch, a {@code TRACKS} change refreshes its slots, {@code unbind}
 * empties it, and {@code AudioEngine.shutdown()} closes it.
 */
class EngineBinderMeteringTest {

    private static final AudioFormat FORMAT = new AudioFormat(44_100.0, 2, 16, 8);

    private AudioEngine engine;
    private EngineBinder binder;
    private DawProject project;
    private MeteringTapBus bus;

    @BeforeEach
    void setUp() {
        engine = new AudioEngine(FORMAT);
        binder = new EngineBinder(engine);
        project = new DawProject("Metered", FORMAT);
        bus = engine.meteringTapBus();
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
    }

    @Test
    void bindBindsTheBusUnderTheBindingEpochWithOneSlotPerChannelReturnAndMaster() {
        project.createAudioTrack("Drums");
        project.createAudioTrack("Bass");
        assertThat(bus.isBound()).as("unbound before the first bind").isFalse();
        assertThat(bus.snapshot().isEmpty()).isTrue();

        binder.bind(project);

        assertThat(bus.isBound()).isTrue();
        assertThat(bus.epoch()).as("the bus epoch is the binder's").isEqualTo(binder.epoch());
        TapSnapshot taps = bus.snapshot();
        assertThat(taps.epoch()).isEqualTo(binder.epoch());
        assertThat(taps.sampleRate()).isEqualTo(FORMAT.sampleRate());
        assertThat(taps.channelSlotCount()).isEqualTo(2);
        for (int i = 0; i < 2; i++) {
            MixerChannel channel = project.getMixer().getChannels().get(i);
            assertThat(taps.channelSlot(i, channel))
                    .as("slot %d resolves for the live mixer channel", i).isNotNull();
            assertThat(taps.channelSlot(i, channel).point())
                    .isEqualTo(new MeterTapPoint.ChannelPost(channel.getId()));
        }
        assertThat(taps.returnSlotCount()).isEqualTo(project.getMixer().getReturnBuses().size());
        assertThat(taps.masterChain()).isNotNull();
        assertThat(taps.masterOut()).isNotNull();
    }

    @Test
    void aTracksChangeRefreshesTheSlotsSoTheNewChannelResolvesAndExistingSlotsSurvive() {
        Track drums = project.createAudioTrack("Drums");
        binder.bind(project);
        MixerChannel drumsChannel = project.getMixer().getChannels().get(0);
        TapSnapshot before = bus.snapshot();
        LevelSubscription existing = bus.attachLevel(
                new MeterTapPoint.ChannelPost(UUID.fromString(drums.getId())));

        Track bass = project.createAudioTrack("Bass");

        TapSnapshot after = bus.snapshot();
        assertThat(after).isNotSameAs(before);
        assertThat(after.channelSlotCount()).isEqualTo(2);
        MixerChannel bassChannel = project.getMixer().getChannels().get(1);
        assertThat(bassChannel.getId()).isEqualTo(UUID.fromString(bass.getId()));
        assertThat(after.channelSlot(1, bassChannel))
                .as("the new channel's CHANNEL_POST slot resolves without a rebind").isNotNull();
        assertThat(after.channelSlot(0, drumsChannel))
                .as("an unchanged channel keeps its slot instance across the refresh")
                .isSameAs(before.channelSlot(0, drumsChannel));
        assertThat(existing.isDisposed()).as("a refresh disposes nothing").isFalse();
        assertThat(bus.epoch()).as("a refresh does not bump the epoch").isEqualTo(binder.epoch());

        project.removeTrack(bass);
        assertThat(bus.snapshot().channelSlotCount()).isEqualTo(1);
        assertThat(bus.snapshot().channelSlot(0, drumsChannel)).isNotNull();
    }

    @Test
    void rebindDisposesEpochNSubscriptionsAndUnbindEmptiesTheSnapshot() {
        project.createAudioTrack("Drums");
        binder.bind(project);
        long firstEpoch = binder.epoch();
        LevelSubscription epochN = bus.attachLevel(MeterTapPoint.MASTER_OUT);
        AtomicBoolean disposedCallback = new AtomicBoolean();
        epochN.onDisposed(() -> disposedCallback.set(true));
        assertThat(epochN.epoch()).isEqualTo(firstEpoch);

        binder.bind(project);

        assertThat(binder.epoch()).isEqualTo(firstEpoch + 1);
        assertThat(bus.epoch()).isEqualTo(binder.epoch());
        assertThat(epochN.isDisposed()).as("an epoch-N token is disposed by the rebind").isTrue();
        assertThat(disposedCallback).isTrue();
        assertThat(bus.levelSubscriptionCount()).isZero();
        assertThat(bus.snapshot().epoch()).isEqualTo(binder.epoch());
        assertThat(bus.snapshot().channelSlotCount()).isEqualTo(1);

        LevelSubscription epochN1 = bus.attachLevel(MeterTapPoint.MASTER_OUT);
        binder.unbind();

        assertThat(epochN1.isDisposed()).as("unbind disposes every token").isTrue();
        assertThat(bus.isBound()).isFalse();
        assertThat(bus.snapshot().isEmpty()).as("the render thread taps nothing while unbound").isTrue();
        assertThat(binder.epoch()).as("unbind does not advance the epoch").isEqualTo(firstEpoch + 1);
        assertThat(bus.epoch()).isEqualTo(firstEpoch + 1);
    }

    @Test
    void refreshPerformanceMonitorRefreshesTheSlotsWithTheEngineFormat() {
        project.createAudioTrack("Drums");
        binder.bind(project);
        TapSnapshot before = bus.snapshot();

        binder.refreshPerformanceMonitor();

        TapSnapshot after = bus.snapshot();
        assertThat(after).as("a refresh publishes a new snapshot").isNotSameAs(before);
        assertThat(after.channelSlotCount()).isEqualTo(1);
        assertThat(after.sampleRate()).isEqualTo(engine.getFormat().sampleRate());
        assertThat(after.epoch()).isEqualTo(binder.epoch());
    }

    @Test
    void engineShutdownClosesTheBusSoNothingCanAttachAfterwards() {
        project.createAudioTrack("Drums");
        binder.bind(project);
        LevelSubscription token = bus.attachLevel(MeterTapPoint.MASTER_CHAIN);

        engine.shutdown();

        assertThat(bus.isClosed()).isTrue();
        assertThat(token.isDisposed()).isTrue();
        assertThat(bus.snapshot().isEmpty()).isTrue();
        assertThatIllegalStateException().isThrownBy(() -> bus.attachLevel(MeterTapPoint.MASTER_OUT));
        engine.shutdown();
        assertThat(bus.isClosed()).as("shutdown is idempotent").isTrue();
    }
}
