package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.metering.MeterFeed;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.metering.InsertTapPair;
import com.benesquivelmusic.daw.core.metering.LevelTapSlot;
import com.benesquivelmusic.daw.core.metering.MeteringTapBus;
import com.benesquivelmusic.daw.core.metering.TapSnapshot;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.editor.PluginParameterStore;
import com.benesquivelmusic.daw.sdk.plugin.PluginMeterSnapshot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 318 "editor plumbing" acceptance: a slot-bound store receives
 * {@code INSERT_IO} frames via {@code publishMeters} while the bridge is
 * attached, none after the detach token runs, and a replaced store (Reload)
 * is picked up through the supplier.
 */
class InsertIoMeterBridgeTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 24, 64);
    private static final int BLOCK = 64;

    private MeteringTapBus bus;
    private InsertSlot slot;
    private FxDispatcher dispatcher;
    private MeterFeed feed;

    @BeforeEach
    void setUp() {
        bus = new MeteringTapBus();
        Mixer mixer = new Mixer();
        MixerChannel channel = new MixerChannel("A");
        slot = new InsertSlot("Comp", new PassThrough());
        channel.addInsert(slot);
        mixer.addChannel(channel);
        bus.rebind(mixer, FORMAT, 1L);
        dispatcher = new FxDispatcher();
        feed = new MeterFeed(bus, dispatcher);
    }

    @AfterEach
    void tearDown() {
        feed.dispose();
        bus.close();
    }

    private void renderInsertBlock(float inLevel, float outLevel) {
        TapSnapshot taps = bus.snapshot();
        InsertTapPair pair = taps.insertTapFor(slot);
        assertThat(pair).as("the insert is tapped").isNotNull();
        float[] in = new float[BLOCK];
        float[] out = new float[BLOCK];
        Arrays.fill(in, inLevel);
        Arrays.fill(out, outLevel);
        publish(pair.input(), taps, in);
        publish(pair.output(), taps, out);
        bus.blockCompleted(taps);
    }

    private static void publish(LevelTapSlot tap, TapSnapshot taps, float[] lane) {
        tap.beginBlock(taps.epoch(), taps.blockIndex(), 2);
        tap.accumulate(0, lane, BLOCK);
        tap.accumulate(1, lane, BLOCK);
        tap.publish(BLOCK);
    }

    private static double db(double linear) {
        return 20.0 * Math.log10(linear);
    }

    @Test
    void framesArriveViaPublishMetersWhileAttachedAndStopAfterDetach() {
        PluginParameterStore store = new PluginParameterStore(List.of());
        store.publishMeters(PluginMeterSnapshot.ofGainReduction(-3.5));
        AtomicReference<PluginParameterStore> current = new AtomicReference<>(store);

        Runnable detach = InsertIoMeterBridge.attach(feed, slot.getPluginInstanceId(), this, current::get, () -> true);
        assertThat(feed.subscriptionCount()).isEqualTo(1);

        renderInsertBlock(0.8f, 0.4f);
        dispatcher.pulse();

        PluginMeterSnapshot meters = store.meters();
        assertThat(meters.inputLevelDb()).isCloseTo(db(0.8), within(1e-4));
        assertThat(meters.outputLevelDb()).isCloseTo(db(0.4), within(1e-4));
        assertThat(meters.gainReductionDb()).as("the plugin's own GR reading is preserved").isEqualTo(-3.5);

        detach.run();
        detach.run();
        assertThat(feed.subscriptionCount()).isZero();
        assertThat(bus.levelSubscriptionCount()).isZero();

        renderInsertBlock(0.2f, 0.1f);
        dispatcher.pulse();
        assertThat(store.meters()).as("nothing after detach").isSameAs(meters);
    }

    @Test
    void silenceIsPublishedAsNegativeInfinity() {
        PluginParameterStore store = new PluginParameterStore(List.of());
        InsertIoMeterBridge.attach(feed, slot.getPluginInstanceId(), this, () -> store, () -> true);

        renderInsertBlock(0f, 0f);
        dispatcher.pulse();

        assertThat(store.meters().inputLevelDb()).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(store.meters().outputLevelDb()).isEqualTo(Double.NEGATIVE_INFINITY);
    }

    @Test
    void aReplacedStoreIsPickedUpThroughTheSupplier() {
        PluginParameterStore before = new PluginParameterStore(List.of());
        PluginParameterStore after = new PluginParameterStore(List.of());
        AtomicReference<PluginParameterStore> current = new AtomicReference<>(before);
        InsertIoMeterBridge.attach(feed, slot.getPluginInstanceId(), this, current::get, () -> true);

        renderInsertBlock(0.5f, 0.5f);
        dispatcher.pulse();
        assertThat(before.meters().inputLevelDb()).isCloseTo(db(0.5), within(1e-4));

        current.set(after);
        renderInsertBlock(0.25f, 0.25f);
        dispatcher.pulse();

        assertThat(after.meters().inputLevelDb()).as("the reloaded store receives the next block")
                .isCloseTo(db(0.25), within(1e-4));
        assertThat(before.meters().inputLevelDb()).as("the old store is untouched")
                .isCloseTo(db(0.5), within(1e-4));
    }

    /**
     * Book §6.3 — one key per tap point per surface. The surface is an
     * explicit argument (a stable object), so a second attach for the same
     * editor REPLACES the first even without an intervening detach; when the
     * supplier was the surface this silently accumulated two subscriptions,
     * because {@code this::store} is a new object on every evaluation.
     */
    @Test
    void attachingTwiceForTheSameSurfaceReplacesTheFirstSubscription() {
        PluginParameterStore first = new PluginParameterStore(List.of());
        PluginParameterStore second = new PluginParameterStore(List.of());
        InsertIoMeterBridge.attach(feed, slot.getPluginInstanceId(), this, () -> first, () -> true);
        InsertIoMeterBridge.attach(feed, slot.getPluginInstanceId(), this, () -> second, () -> true);

        assertThat(feed.subscriptionCount()).as("the second attach replaced the first").isEqualTo(1);
        assertThat(bus.levelSubscriptionCount()).as("the replaced engine token was disposed").isEqualTo(1);

        renderInsertBlock(0.5f, 0.5f);
        dispatcher.pulse();

        assertThat(second.meters().inputLevelDb()).isCloseTo(db(0.5), within(1e-4));
        assertThat(first.meters()).as("the replaced subscription publishes nothing")
                .isSameAs(PluginMeterSnapshot.SILENT);
    }

    @Test
    void aHiddenEditorPublishesNothingAndANullStoreIsSkipped() {
        PluginParameterStore store = new PluginParameterStore(List.of());
        AtomicBoolean visible = new AtomicBoolean(false);
        AtomicReference<PluginParameterStore> current = new AtomicReference<>(store);
        InsertIoMeterBridge.attach(feed, slot.getPluginInstanceId(), this, current::get, visible::get);

        renderInsertBlock(0.5f, 0.5f);
        dispatcher.pulse();
        assertThat(store.meters()).isSameAs(PluginMeterSnapshot.SILENT);

        current.set(null);
        visible.set(true);
        dispatcher.pulse();
        assertThat(store.meters()).isSameAs(PluginMeterSnapshot.SILENT);
    }

    private static final class PassThrough implements AudioProcessor {
        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            for (int ch = 0; ch < Math.min(inputBuffer.length, outputBuffer.length); ch++) {
                System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
            }
        }

        @Override
        public void reset() {
        }

        @Override
        public int getInputChannelCount() {
            return 2;
        }

        @Override
        public int getOutputChannelCount() {
            return 2;
        }
    }
}
