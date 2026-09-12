package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.metering.MeterFeed;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.metering.LevelTapSlot;
import com.benesquivelmusic.daw.core.metering.MeteringTapBus;
import com.benesquivelmusic.daw.core.metering.TapSnapshot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.within;

/** Real meter-bus frames consumed only while an actual VM surface is showing. */
class ChannelVMMeterTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 24, 64);
    private static final int BLOCK = 64;
    private static final double HALF_SCALE_DB = -6.0206;

    @BeforeAll
    static void initToolkit() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    @Test
    void configuredFeedAloneCreatesNoMeterDemand() throws Exception {
        withVm(rig -> {
            assertThat(rig.vm.getMeterLevel()).isEqualTo(ChannelVM.METER_FLOOR_DB);
            assertThat(rig.vm.isMeterBound()).isFalse();
            rig.assertDemand(0);
            rig.stage.show();
            rig.dispatcher.pulse();
            rig.assertDemand(0);
        });
    }

    @Test
    void aVisibleBoundMeterPublishesPeakDbfsAndFloorsSilence() throws Exception {
        withVm(rig -> {
            rig.vm.bindMeter(rig.surface);
            rig.stage.show();
            rig.assertDemand(1);
            renderInto(rig.bus, rig.channel, 0.5f);
            rig.dispatcher.pulse();
            assertThat(rig.vm.getMeterLevel()).isCloseTo(HALF_SCALE_DB, within(0.01));
            renderInto(rig.bus, rig.channel, 0f);
            rig.dispatcher.pulse();
            assertThat(rig.vm.getMeterLevel()).isEqualTo(ChannelVM.METER_FLOOR_DB).isFinite();
        });
    }

    @Test
    void hiddenDetachedAndHiddenAncestorSurfacesReleaseDemandUntilShownAgain() throws Exception {
        withVm(rig -> {
            rig.vm.bindMeter(rig.surface);
            rig.assertDemand(0);
            rig.stage.show();
            rig.assertDemand(1);
            renderInto(rig.bus, rig.channel, 0.5f);
            rig.dispatcher.pulse();
            double lastLevel = rig.vm.getMeterLevel();

            rig.root.setVisible(false);
            rig.assertDemand(0);
            rig.dispatcher.pulse();
            assertThat(rig.vm.getMeterLevel()).isEqualTo(lastLevel);
            rig.root.setVisible(true);
            rig.assertDemand(1);
            rig.root.getChildren().remove(rig.surface);
            rig.assertDemand(0);
            rig.root.getChildren().add(rig.surface);
            rig.assertDemand(1);
            rig.surface.setVisible(false);
            rig.assertDemand(0);
            rig.surface.setVisible(true);
            rig.assertDemand(1);
            rig.stage.hide();
            rig.assertDemand(0);
            rig.stage.show();
            rig.assertDemand(1);
            renderInto(rig.bus, rig.channel, 0.25f);
            rig.dispatcher.pulse();
            assertThat(rig.vm.getMeterLevel()).isCloseTo(-12.0412, within(0.01));
        });
    }

    @Test
    void surfaceRemovalTokenStopsUpdatesAndDoesNotDetachAnotherSurface() throws Exception {
        withVm(rig -> {
            var otherSurface = new Pane();
            rig.root.getChildren().add(otherSurface);
            Runnable first = rig.vm.bindMeter(rig.surface);
            Runnable second = rig.vm.bindMeter(otherSurface);
            rig.stage.show();
            rig.assertDemand(2);
            first.run();
            first.run();
            rig.assertDemand(1);
            renderInto(rig.bus, rig.channel, 0.5f);
            rig.dispatcher.pulse();
            assertThat(rig.vm.getMeterLevel()).isCloseTo(HALF_SCALE_DB, within(0.01));
            second.run();
            rig.assertDemand(0);
            assertThat(rig.vm.isMeterBound()).isFalse();
            rig.dispatcher.pulse();
            assertThat(rig.vm.getMeterLevel()).isCloseTo(HALF_SCALE_DB, within(0.01));
        });
    }

    @Test
    void aSupersededSurfaceTokenCannotDetachItsReplacement() throws Exception {
        withVm(rig -> {
            rig.stage.show();
            Runnable oldBinding = rig.vm.bindMeter(rig.surface);
            Runnable replacement = rig.vm.bindMeter(rig.surface);
            oldBinding.run();
            rig.assertDemand(1);
            assertThat(rig.vm.isMeterBound()).isTrue();
            renderInto(rig.bus, rig.channel, 0.5f);
            rig.dispatcher.pulse();
            assertThat(rig.vm.getMeterLevel()).isCloseTo(HALF_SCALE_DB, within(0.01));
            replacement.run();
            rig.assertDemand(0);
        });
    }

    @Test
    void unbindAndDisposeRemoveVisibilityListenersAsWellAsSubscriptions() throws Exception {
        withVm(rig -> {
            rig.vm.bindMeter(rig.surface);
            rig.stage.show();
            rig.vm.unbindMeter();
            rig.assertDemand(0);
            rig.stage.hide();
            rig.stage.show();
            rig.assertDemand(0);
            rig.vm.bindMeter(rig.surface);
            int channelsBefore = rig.dispatcher.openChannelCount();
            rig.vm.dispose();
            rig.assertDemand(0);
            assertThat(rig.dispatcher.openChannelCount()).isEqualTo(channelsBefore - 1);
            rig.stage.hide();
            rig.stage.show();
            rig.dispatcher.pulse();
            rig.assertDemand(0);
            assertThatIllegalStateException().isThrownBy(() -> rig.vm.bindMeter(rig.surface));
        });
    }

    @Test
    void visibleSurfaceReattachesAcrossBusRebind() throws Exception {
        withVm(rig -> {
            rig.vm.bindMeter(rig.surface);
            rig.stage.show();
            renderInto(rig.bus, rig.channel, 0.5f);
            rig.dispatcher.pulse();
            rig.bus.rebind(rig.mixer, FORMAT, 2L);
            rig.dispatcher.pulse();
            rig.assertDemand(1);
            assertThat(rig.vm.getMeterLevel()).isEqualTo(ChannelVM.METER_FLOOR_DB);
            renderInto(rig.bus, rig.channel, 0.25f);
            rig.dispatcher.pulse();
            assertThat(rig.vm.getMeterLevel()).isCloseTo(-12.0412, within(0.01));
        });
    }

    @Test
    void registrySuppliesFeedsWithoutSubscribingUnshownChannels() throws Exception {
        onFx(() -> {
            var project = new DawProject("Meters", FORMAT);
            var drums = project.createAudioTrack("Drums");
            project.createAudioTrack("Bass");
            var bus = new MeteringTapBus();
            bus.rebind(project.getMixer(), FORMAT, 1L);
            var dispatcher = new FxDispatcher();
            var feed = new MeterFeed(bus, dispatcher);
            var registry = new TrackChannelRegistry(project, dispatcher, feed);
            var surface = new Pane();
            var stage = new Stage();
            stage.setScene(new Scene(surface, 100, 100));
            try {
                assertThat(registry.channelVms()).hasSize(2).allSatisfy(vm ->
                        assertThat(vm.isMeterBound()).isFalse());
                assertThat(feed.subscriptionCount()).isZero();
                assertThat(bus.snapshot().isEmpty()).isTrue();
                var drumsVm = registry.channelVm(UUID.fromString(drums.getId()));
                drumsVm.bindMeter(surface);
                assertThat(feed.subscriptionCount()).isZero();
                stage.show();
                assertThat(feed.subscriptionCount()).isEqualTo(1);
                renderInto(bus, project.getMixerChannelForTrack(drums), 0.5f);
                dispatcher.pulse();
                assertThat(drumsVm.getMeterLevel()).isCloseTo(HALF_SCALE_DB, within(0.01));
                registry.dispose();
                assertThat(feed.subscriptionCount()).isZero();
                assertThat(bus.snapshot().isEmpty()).isTrue();
                stage.hide();
                stage.show();
                assertThat(feed.subscriptionCount()).isZero();
            } finally {
                registry.dispose();
                stage.close();
                feed.dispose();
                bus.close();
            }
        });
    }

    @Test
    void registryWithoutFeedLeavesMetersUnbound() throws Exception {
        onFx(() -> {
            var project = new DawProject("Meters", FORMAT);
            project.createAudioTrack("Drums");
            var registry = new TrackChannelRegistry(project, new FxDispatcher());
            try {
                assertThat(registry.channelVms()).isNotEmpty().allSatisfy(vm -> {
                    assertThat(vm.isMeterBound()).isFalse();
                    assertThat(vm.getMeterLevel()).isEqualTo(ChannelVM.METER_FLOOR_DB);
                    assertThatIllegalStateException().isThrownBy(() -> vm.bindMeter(new Pane()));
                });
            } finally {
                registry.dispose();
            }
        });
    }

    private static void renderInto(MeteringTapBus bus, MixerChannel channel, float level) {
        TapSnapshot taps = bus.snapshot();
        LevelTapSlot slot = null;
        for (int i = 0; slot == null && i < 8; i++) {
            slot = taps.channelSlot(i, channel);
        }
        assertThat(slot).isNotNull();
        slot.beginBlock(taps.epoch(), taps.blockIndex(), 2);
        for (int frame = 0; frame < BLOCK; frame++) {
            slot.accumulate(0, level);
            slot.accumulate(1, level);
        }
        slot.publish(BLOCK);
        bus.blockCompleted(taps);
    }

    private static void withVm(Consumer<Rig> action) throws Exception {
        onFx(() -> {
            try (var rig = new Rig()) {
                action.accept(rig);
            }
        });
    }

    private static void onFx(Runnable action) throws Exception {
        var task = new FutureTask<Void>(() -> {
            action.run();
            return null;
        });
        Platform.runLater(task);
        task.get(15, TimeUnit.SECONDS);
    }

    private static final class Rig implements AutoCloseable {
        final MeteringTapBus bus = new MeteringTapBus();
        final Mixer mixer = new Mixer();
        final MixerChannel channel = new MixerChannel("Drums");
        final FxDispatcher dispatcher = new FxDispatcher();
        final MeterFeed feed = new MeterFeed(bus, dispatcher);
        final ChannelVM vm = new ChannelVM(channel, dispatcher, feed);
        final Pane surface = new Pane();
        final Pane root = new Pane(surface);
        final Stage stage = new Stage();

        Rig() {
            mixer.addChannel(channel);
            bus.rebind(mixer, FORMAT, 1L);
            stage.setScene(new Scene(root, 100, 100));
        }

        void assertDemand(int expected) {
            assertThat(feed.subscriptionCount()).isEqualTo(expected);
            assertThat(bus.levelSubscriptionCount()).isEqualTo(expected);
            if (expected == 0) {
                assertThat(bus.snapshot().isEmpty()).isTrue();
            }
        }

        @Override
        public void close() {
            vm.dispose();
            stage.close();
            feed.dispose();
            bus.close();
        }
    }
}
