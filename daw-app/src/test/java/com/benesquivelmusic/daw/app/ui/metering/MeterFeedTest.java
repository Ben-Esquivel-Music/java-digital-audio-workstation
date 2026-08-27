package com.benesquivelmusic.daw.app.ui.metering;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.metering.InsertTapPair;
import com.benesquivelmusic.daw.core.metering.LevelTapSlot;
import com.benesquivelmusic.daw.core.metering.MeterFrame;
import com.benesquivelmusic.daw.core.metering.MeterTapPoint;
import com.benesquivelmusic.daw.core.metering.MeteringTapBus;
import com.benesquivelmusic.daw.core.metering.TapSnapshot;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 318 — registry-lifecycle acceptance for the FX-pulse drain: a real
 * {@link MeteringTapBus} bound to a real {@link Mixer}, frames written
 * through the {@link LevelTapSlot} RT API exactly as the mixer does, the
 * pulse driven manually through {@link FxDispatcher#pulse()} with an
 * injected clock. No JavaFX toolkit is needed — the feed touches JavaFX only
 * through the sinks, which are plain callbacks here.
 */
class MeterFeedTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 24, 64);
    private static final int BLOCK = 64;

    /** A delivered frame, copied out because the feed's frame is reused. */
    private record Delivered(float peak, float rms, int channels, boolean silent, long epoch, long block) {
        static Delivered of(MeterFrame frame) {
            return new Delivered(frame.maxPeak(), frame.maxRms(), frame.channelCount(),
                    frame.isSilent(), frame.epoch(), frame.blockIndex());
        }
    }

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private MeteringTapBus bus;
    private Mixer mixer;
    private MixerChannel channelA;
    private FxDispatcher dispatcher;
    private MeterFeed feed;
    /** The token {@link #subscribeChannelA} handed back, for the lifecycle tests. */
    private MeterSubscription channelSubscription;

    @BeforeEach
    void setUp() {
        bus = new MeteringTapBus();
        mixer = new Mixer();
        channelA = new MixerChannel("A");
        mixer.addChannel(channelA);
        bus.rebind(mixer, FORMAT, 1L);
        dispatcher = new FxDispatcher();
        feed = new MeterFeed(bus, dispatcher, nanos::get);
    }

    @AfterEach
    void tearDown() {
        feed.dispose();
        bus.close();
    }

    /** Renders one block into CHANNEL_POST(A) and MASTER_OUT exactly as the mixer would. */
    private long renderBlock(float level) {
        TapSnapshot taps = bus.snapshot();
        long stamp = taps.blockIndex();
        float[] lane = new float[BLOCK];
        Arrays.fill(lane, level);
        publish(taps.channelSlot(0, channelA), taps, lane);
        publish(taps.masterOut(), taps, lane);
        bus.blockCompleted(taps);
        return stamp;
    }

    private static void publish(LevelTapSlot slot, TapSnapshot taps, float[] lane) {
        slot.beginBlock(taps.epoch(), taps.blockIndex(), 2);
        slot.accumulate(0, lane, BLOCK);
        slot.accumulate(1, lane, BLOCK);
        slot.publish(BLOCK);
    }

    /**
     * Subscribes the CHANNEL_POST(A) strip; the token is kept in
     * {@link #channelSubscription} (re-subscribing to read it back would
     * replace — and dispose — the very subscription under test).
     */
    private List<Delivered> subscribeChannelA(AtomicBoolean visible) {
        List<Delivered> delivered = new ArrayList<>();
        channelSubscription = feed.subscribe(new MeterTapPoint.ChannelPost(channelA.getId()), "strip-A",
                visible::get, frame -> delivered.add(Delivered.of(frame)));
        return delivered;
    }

    @Test
    void feedRegistersOnePulseParticipant() {
        assertThat(dispatcher.pulseParticipantCount()).isEqualTo(1);
        assertThat(feed.subscriptionCount()).isZero();
        assertThat(feed.isDisposed()).isFalse();
    }

    @Test
    void pulseDeliversEachNewBlockOnceAndNothingWhenNoBlockArrived() {
        List<Delivered> delivered = subscribeChannelA(new AtomicBoolean(true));
        assertThat(bus.levelSubscriptionCount()).isEqualTo(1);

        dispatcher.pulse();
        assertThat(delivered).as("nothing published yet").isEmpty();

        long first = renderBlock(0.5f);
        dispatcher.pulse();
        dispatcher.pulse();
        dispatcher.pulse();
        assertThat(delivered).as("one block, one delivery — repeated pulses do not re-deliver").hasSize(1);
        assertThat(delivered.get(0).peak()).isCloseTo(0.5f, within(1e-6f));
        assertThat(delivered.get(0).rms()).isCloseTo(0.5f, within(1e-6f));
        assertThat(delivered.get(0).channels()).isEqualTo(2);
        assertThat(delivered.get(0).epoch()).isEqualTo(1L);
        assertThat(delivered.get(0).block()).isEqualTo(first);

        long second = renderBlock(0.25f);
        renderBlock(0.75f);
        dispatcher.pulse();
        assertThat(delivered).as("a burst between pulses yields the newest block only").hasSize(2);
        assertThat(delivered.get(1).peak()).isCloseTo(0.75f, within(1e-6f));
        assertThat(delivered.get(1).block()).isEqualTo(second + 1);
    }

    @Test
    void hiddenSubscriptionConsultsOnlyTheVisibleSupplier() {
        AtomicInteger visibleCalls = new AtomicInteger();
        AtomicInteger sinkCalls = new AtomicInteger();
        feed.subscribe(new MeterTapPoint.ChannelPost(channelA.getId()), "hidden-strip",
                () -> {
                    visibleCalls.incrementAndGet();
                    return false;
                },
                frame -> sinkCalls.incrementAndGet());
        renderBlock(0.9f);

        dispatcher.pulse();
        dispatcher.pulse();
        dispatcher.pulse();

        assertThat(visibleCalls.get()).as("the visible supplier is consulted once per pulse").isEqualTo(3);
        assertThat(sinkCalls.get()).as("a hidden meter's sink is never called").isZero();
        assertThat(feed.readAttempts()).as("a hidden meter performs no slot read").isZero();
    }

    @Test
    void staleSubscriptionDeliversExactlyOneSilentFrameAfterTheWindow() {
        List<Delivered> delivered = subscribeChannelA(new AtomicBoolean(true));
        long stamp = renderBlock(0.6f);
        dispatcher.pulse();
        assertThat(delivered).hasSize(1);

        nanos.addAndGet(MeterFeed.STALE_NANOS - 1L);
        dispatcher.pulse();
        assertThat(delivered).as("inside the stale window nothing is delivered").hasSize(1);

        nanos.addAndGet(2L);
        dispatcher.pulse();
        assertThat(delivered).as("one silent frame at the stale boundary").hasSize(2);
        Delivered silent = delivered.get(1);
        assertThat(silent.silent()).isTrue();
        assertThat(silent.channels()).as("silence keeps the last seen lane count").isEqualTo(2);
        assertThat(silent.epoch()).isEqualTo(1L);
        assertThat(silent.block()).isEqualTo(stamp);

        nanos.addAndGet(10L * MeterFeed.STALE_NANOS);
        dispatcher.pulse();
        dispatcher.pulse();
        assertThat(delivered).as("then nothing until frames resume").hasSize(2);

        renderBlock(0.3f);
        dispatcher.pulse();
        assertThat(delivered).hasSize(3);
        assertThat(delivered.get(2).silent()).isFalse();
        assertThat(delivered.get(2).peak()).isCloseTo(0.3f, within(1e-6f));
    }

    @Test
    void aSubscriptionThatNeverReceivesAFrameGoesSilentOnceAfterTheWindow() {
        List<Delivered> delivered = new ArrayList<>();
        feed.subscribe(MeterTapPoint.MASTER_CHAIN, "untapped", () -> true,
                frame -> delivered.add(Delivered.of(frame)));

        nanos.addAndGet(MeterFeed.STALE_NANOS);
        dispatcher.pulse();
        dispatcher.pulse();

        assertThat(delivered).hasSize(1);
        assertThat(delivered.get(0).silent()).isTrue();
        assertThat(delivered.get(0).channels()).isEqualTo(MeterFeed.DEFAULT_SILENT_CHANNELS);
    }

    @Test
    void subscribeWithAnEqualKeyReplacesTheEarlierSubscription() {
        MeterTapPoint point = new MeterTapPoint.ChannelPost(channelA.getId());
        Object surface = new Object();
        AtomicInteger firstSink = new AtomicInteger();
        AtomicInteger secondSink = new AtomicInteger();

        MeterSubscription first = feed.subscribe(point, surface, () -> true, f -> firstSink.incrementAndGet());
        MeterSubscription second = feed.subscribe(point, surface, () -> true, f -> secondSink.incrementAndGet());

        assertThat(first.isDisposed()).as("the replaced subscription is disposed").isTrue();
        assertThat(second.isDisposed()).isFalse();
        assertThat(first.key()).isEqualTo(second.key()).isEqualTo(new MeterKey(point, surface));
        assertThat(feed.subscriptionCount()).isEqualTo(1);
        assertThat(bus.levelSubscriptionCount()).as("the replaced engine token is disposed too").isEqualTo(1);

        renderBlock(0.4f);
        dispatcher.pulse();
        assertThat(firstSink.get()).isZero();
        assertThat(secondSink.get()).isEqualTo(1);

        // A different surface for the same point is a different key.
        feed.subscribe(point, new Object(), () -> true, f -> { });
        assertThat(feed.subscriptionCount()).isEqualTo(2);
    }

    @Test
    void subscriptionDisposeRemovesItAndItsEngineToken() {
        List<Delivered> delivered = new ArrayList<>();
        MeterSubscription subscription = feed.subscribe(MeterTapPoint.MASTER_OUT, "master",
                () -> true, frame -> delivered.add(Delivered.of(frame)));
        renderBlock(0.5f);
        dispatcher.pulse();
        assertThat(delivered).hasSize(1);

        subscription.dispose();
        subscription.dispose();

        assertThat(subscription.isDisposed()).isTrue();
        assertThat(feed.subscriptionCount()).isZero();
        assertThat(bus.levelSubscriptionCount()).isZero();
        renderBlock(0.5f);
        dispatcher.pulse();
        assertThat(delivered).as("nothing after dispose").hasSize(1);
    }

    @Test
    void rebindDisposesTheEngineTokenDeliversOneSilentFrameThenReattachesUnderTheNewEpoch() {
        List<Delivered> delivered = subscribeChannelA(new AtomicBoolean(true));
        MeterSubscription subscription = channelSubscription;
        renderBlock(0.5f);
        dispatcher.pulse();
        assertThat(delivered).hasSize(1);
        assertThat(subscription.epoch()).isEqualTo(1L);

        // The binder rebinds the same mixer under epoch 2: the epoch-1 engine
        // token is disposed; the reused slot still holds the epoch-1 frame.
        bus.rebind(mixer, FORMAT, 2L);
        assertThat(bus.levelSubscriptionCount()).as("epoch-1 token disposed by the bus").isZero();
        assertThat(subscription.isDisposed()).as("the UI intent survives the rebind").isFalse();

        dispatcher.pulse();
        assertThat(delivered).as("exactly one silent frame for the lost token").hasSize(2);
        assertThat(delivered.get(1).silent()).isTrue();
        assertThat(delivered.get(1).epoch()).isEqualTo(1L);
        assertThat(subscription.epoch()).as("re-attached under the new epoch").isEqualTo(2L);
        assertThat(bus.levelSubscriptionCount()).isEqualTo(1);

        dispatcher.pulse();
        dispatcher.pulse();
        assertThat(delivered).as("the stale epoch-1 frame in the reused slot is never delivered").hasSize(2);

        renderBlock(0.25f);
        dispatcher.pulse();
        assertThat(delivered).hasSize(3);
        assertThat(delivered.get(2).epoch()).isEqualTo(2L);
        assertThat(delivered.get(2).peak()).isCloseTo(0.25f, within(1e-6f));
    }

    @Test
    void aHiddenSubscriptionCostsNothingAcrossARebindAndCatchesUpWhenShown() {
        AtomicBoolean visible = new AtomicBoolean(true);
        List<Delivered> delivered = subscribeChannelA(visible);
        renderBlock(0.5f);
        dispatcher.pulse();
        visible.set(false);

        bus.rebind(mixer, FORMAT, 2L);
        dispatcher.pulse();
        dispatcher.pulse();
        assertThat(delivered).as("hidden: no silent frame, no re-attach").hasSize(1);
        assertThat(bus.levelSubscriptionCount()).isZero();

        visible.set(true);
        dispatcher.pulse();
        assertThat(delivered).hasSize(2);
        assertThat(delivered.get(1).silent()).isTrue();
        assertThat(bus.levelSubscriptionCount()).isEqualTo(1);
    }

    @Test
    void unbindGoesSilentOnceAndFramesResumeAfterTheNextBind() {
        List<Delivered> delivered = subscribeChannelA(new AtomicBoolean(true));
        renderBlock(0.5f);
        dispatcher.pulse();

        bus.unbind();
        dispatcher.pulse();
        nanos.addAndGet(5L * MeterFeed.STALE_NANOS);
        dispatcher.pulse();
        dispatcher.pulse();
        assertThat(delivered).as("one silent frame on unbind, then nothing").hasSize(2);
        assertThat(delivered.get(1).silent()).isTrue();

        bus.rebind(mixer, FORMAT, 2L);
        dispatcher.pulse();
        assertThat(delivered).as("the unbound-epoch token is replaced silently (no frame was ever delivered under it)")
                .hasSize(3);
        renderBlock(0.5f);
        dispatcher.pulse();
        assertThat(delivered).hasSize(4);
        assertThat(delivered.get(3).silent()).isFalse();
        assertThat(delivered.get(3).epoch()).isEqualTo(2L);
    }

    @Test
    void aClosedBusEndsTheSubscriptionAfterItsSilentFrame() {
        List<Delivered> delivered = subscribeChannelA(new AtomicBoolean(true));
        MeterSubscription subscription = channelSubscription;
        renderBlock(0.5f);
        dispatcher.pulse();

        bus.close();
        dispatcher.pulse();

        assertThat(delivered).hasSize(2);
        assertThat(delivered.get(1).silent()).isTrue();
        assertThat(subscription.isDisposed()).as("no re-attach on a closed bus").isTrue();
        assertThat(feed.subscriptionCount()).isZero();
    }

    @Test
    void feedDisposeRemovesTheParticipantAndDisposesEverySubscription() {
        MeterSubscription a = feed.subscribe(new MeterTapPoint.ChannelPost(channelA.getId()), "a",
                () -> true, f -> { });
        MeterSubscription b = feed.subscribe(MeterTapPoint.MASTER_OUT, "b", () -> true, f -> { });
        assertThat(bus.levelSubscriptionCount()).isEqualTo(2);

        feed.dispose();
        feed.dispose();

        assertThat(feed.isDisposed()).isTrue();
        assertThat(dispatcher.pulseParticipantCount()).as("the participant is removed").isZero();
        assertThat(feed.subscriptionCount()).isZero();
        assertThat(a.isDisposed()).isTrue();
        assertThat(b.isDisposed()).isTrue();
        assertThat(bus.levelSubscriptionCount()).as("every engine token is disposed").isZero();
        assertThatIllegalStateException()
                .isThrownBy(() -> feed.subscribe(MeterTapPoint.MASTER_OUT, "late", () -> true, f -> { }));
    }

    @Test
    void insertIoSubscriptionDeliversBothHalvesOfTheSameBlock() {
        InsertSlot slot = new InsertSlot("Gain", new PassThrough());
        channelA.addInsert(slot);
        bus.refreshSlots();
        List<Delivered> inputs = new ArrayList<>();
        List<Delivered> outputs = new ArrayList<>();
        MeterSubscription subscription = feed.subscribeInsertIo(slot.getPluginInstanceId(), "editor",
                () -> true, (in, out) -> {
                    inputs.add(Delivered.of(in));
                    outputs.add(Delivered.of(out));
                });
        assertThat(subscription.key().point()).isEqualTo(new MeterTapPoint.InsertIo(slot.getPluginInstanceId()));

        TapSnapshot taps = bus.snapshot();
        InsertTapPair pair = taps.insertTapFor(slot);
        assertThat(pair).isNotNull();
        float[] in = new float[BLOCK];
        float[] out = new float[BLOCK];
        Arrays.fill(in, 0.8f);
        Arrays.fill(out, 0.4f);
        publish(pair.input(), taps, in);
        publish(pair.output(), taps, out);
        bus.blockCompleted(taps);

        dispatcher.pulse();
        dispatcher.pulse();

        assertThat(inputs).hasSize(1);
        assertThat(inputs.get(0).peak()).isCloseTo(0.8f, within(1e-6f));
        assertThat(outputs.get(0).peak()).isCloseTo(0.4f, within(1e-6f));
        assertThat(inputs.get(0).block()).isEqualTo(outputs.get(0).block());

        nanos.addAndGet(MeterFeed.STALE_NANOS);
        dispatcher.pulse();
        assertThat(inputs).hasSize(2);
        assertThat(inputs.get(1).silent()).isTrue();
        assertThat(outputs.get(1).silent()).isTrue();

        subscription.dispose();
        assertThat(bus.levelSubscriptionCount()).isZero();
    }

    @Test
    void aSinkMayDisposeItsOwnSubscriptionDuringThePulse() {
        AtomicInteger calls = new AtomicInteger();
        MeterSubscription[] holder = new MeterSubscription[1];
        holder[0] = feed.subscribe(MeterTapPoint.MASTER_OUT, "self-disposing", () -> true, frame -> {
            calls.incrementAndGet();
            holder[0].dispose();
        });
        feed.subscribe(new MeterTapPoint.ChannelPost(channelA.getId()), "other", () -> true,
                frame -> calls.incrementAndGet());

        renderBlock(0.5f);
        dispatcher.pulse();

        assertThat(calls.get()).isEqualTo(2);
        assertThat(feed.subscriptionCount()).isEqualTo(1);
        assertThat(holder[0].isDisposed()).isTrue();
    }

    /** Minimal processor for an insert slot; never runs in these tests. */
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
