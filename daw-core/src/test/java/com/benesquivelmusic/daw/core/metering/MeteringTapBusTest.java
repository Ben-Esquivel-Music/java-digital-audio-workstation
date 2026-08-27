package com.benesquivelmusic.daw.core.metering;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 318 "registry lifecycle" acceptance: attach returns a token; dispose
 * detaches; a rebind to a newer epoch disposes the older tokens and fires
 * {@code onDisposed} after the registry lock is released; refresh keeps the
 * slot of every unchanged channel; an attach whose point is missing refreshes;
 * unbind publishes an empty snapshot.
 */
class MeteringTapBusTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 24, 64);

    private MeteringTapBus bus;
    private Mixer mixer;
    private MixerChannel channelA;
    private MixerChannel channelB;
    private MixerChannel defaultReturn;

    @BeforeEach
    void setUp() {
        bus = new MeteringTapBus();
        mixer = new Mixer();
        channelA = new MixerChannel("A");
        channelB = new MixerChannel("B");
        mixer.addChannel(channelA);
        mixer.addChannel(channelB);
        defaultReturn = mixer.getReturnBuses().get(0);
    }

    @AfterEach
    void tearDown() {
        bus.close();
    }

    @Test
    void attachReturnsATokenForTheCurrentEpoch() {
        bus.rebind(mixer, FORMAT, 1L);
        LevelSubscription token = bus.attachLevel(new MeterTapPoint.ChannelPost(channelA.getId()));

        assertThat(token.epoch()).isEqualTo(1L);
        assertThat(token.point()).isEqualTo(new MeterTapPoint.ChannelPost(channelA.getId()));
        assertThat(token.isDisposed()).isFalse();
        assertThat(bus.levelSubscriptionCount()).isEqualTo(1);
        assertThat(bus.epoch()).isEqualTo(1L);
        assertThat(bus.isBound()).isTrue();
    }

    @Test
    void tokenReadsTheFramePublishedIntoItsSlot() {
        bus.rebind(mixer, FORMAT, 1L);
        LevelSubscription token = bus.attachLevel(new MeterTapPoint.ChannelPost(channelB.getId()));
        MeterFrame frame = new MeterFrame();
        assertThat(token.readInto(frame)).as("nothing published yet").isFalse();

        TapSnapshot taps = bus.snapshot();
        publish(taps.channelSlot(1, channelB), taps, 0.5f);

        assertThat(token.readInto(frame)).isTrue();
        assertThat(frame.epoch()).isEqualTo(1L);
        assertThat(frame.peak(0)).isCloseTo(0.5f, within(1e-6f));
        assertThat(frame.channelCount()).isEqualTo(2);
    }

    @Test
    void disposeDetachesAndFiresOnDisposedExactlyOnce() {
        bus.rebind(mixer, FORMAT, 1L);
        LevelSubscription token = bus.attachLevel(MeterTapPoint.MASTER_OUT);
        AtomicInteger fired = new AtomicInteger();
        token.onDisposed(fired::incrementAndGet);

        token.dispose();
        token.dispose();

        assertThat(token.isDisposed()).isTrue();
        assertThat(fired.get()).isEqualTo(1);
        assertThat(bus.levelSubscriptionCount()).isZero();
        assertThat(token.readInto(new MeterFrame())).isFalse();

        AtomicBoolean late = new AtomicBoolean();
        token.onDisposed(() -> late.set(true));
        assertThat(late.get()).as("a callback registered after disposal runs immediately").isTrue();
    }

    @Test
    void rebindToANewerEpochDisposesOlderTokensAndFiresAfterUnlock() throws Exception {
        bus.rebind(mixer, FORMAT, 1L);
        LevelSubscription old = bus.attachLevel(new MeterTapPoint.ChannelPost(channelA.getId()));
        AnalysisSubscription oldAnalysis = bus.attachAnalysis(
                MeterTapPoint.MASTER_CHAIN, 4, (samples, channels, frames, rate) -> { });
        AtomicReference<String> lockObservation = new AtomicReference<>();
        AtomicInteger fired = new AtomicInteger();
        old.onDisposed(() -> {
            fired.incrementAndGet();
            // Prove the registry lock is NOT held while callbacks run: another
            // thread must be able to enter a locked bus method promptly.
            Thread probe = Thread.ofPlatform().name("lock-probe").unstarted(
                    () -> lockObservation.set("count=" + bus.levelSubscriptionCount()));
            probe.start();
            try {
                probe.join(2_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (probe.isAlive()) {
                lockObservation.set("BLOCKED: registry lock held during onDisposed");
            }
            // And the callback may re-enter the bus.
            bus.attachLevel(MeterTapPoint.MASTER_OUT);
        });

        bus.rebind(mixer, FORMAT, 2L);

        assertThat(fired.get()).isEqualTo(1);
        assertThat(old.isDisposed()).isTrue();
        assertThat(oldAnalysis.isDisposed()).isTrue();
        assertThat(lockObservation.get()).isEqualTo("count=0");
        assertThat(bus.epoch()).isEqualTo(2L);
        assertThat(bus.levelSubscriptionCount())
                .as("the token re-attached from the callback lives at the new epoch")
                .isEqualTo(1);
        assertThat(bus.analysisSubscriptionCount()).isZero();
        assertThat(bus.snapshot().hasAnalysisRings()).isFalse();
    }

    @Test
    void rebindAtTheSameEpochKeepsTokensAndRejectsGoingBackwards() {
        bus.rebind(mixer, FORMAT, 3L);
        LevelSubscription token = bus.attachLevel(MeterTapPoint.MASTER_OUT);

        bus.rebind(mixer, FORMAT, 3L);
        assertThat(token.isDisposed()).isFalse();

        assertThatIllegalArgumentException().isThrownBy(() -> bus.rebind(mixer, FORMAT, 2L));
        assertThat(token.isDisposed()).isFalse();
    }

    @Test
    void refreshReusesSlotsForUnchangedChannelsAndDropsRemovedOnes() {
        bus.rebind(mixer, FORMAT, 1L);
        TapSnapshot before = bus.snapshot();
        LevelTapSlot slotA = before.channelSlot(0, channelA);
        LevelTapSlot slotReturn = before.returnSlot(0, defaultReturn);
        LevelSubscription tokenA = bus.attachLevel(new MeterTapPoint.ChannelPost(channelA.getId()));
        LevelSubscription tokenB = bus.attachLevel(new MeterTapPoint.ChannelPost(channelB.getId()));
        publish(slotA, before, 0.25f);

        MixerChannel channelC = new MixerChannel("C");
        mixer.addChannel(channelC);
        mixer.removeChannel(channelB);
        bus.refreshSlots();

        TapSnapshot after = bus.snapshot();
        assertThat(after).isNotSameAs(before);
        assertThat(after.channelSlotCount()).isEqualTo(2);
        assertThat(after.channelSlot(0, channelA)).isSameAs(slotA);
        assertThat(after.returnSlot(0, defaultReturn)).isSameAs(slotReturn);
        assertThat(after.channelSlot(1, channelC)).isNotNull().isNotSameAs(slotA);
        assertThat(after.masterChain()).isSameAs(before.masterChain());
        assertThat(after.masterOut()).isSameAs(before.masterOut());

        MeterFrame frame = new MeterFrame();
        assertThat(tokenA.readInto(frame)).as("the reused slot keeps its published frame").isTrue();
        assertThat(frame.peak(0)).isCloseTo(0.25f, within(1e-6f));
        assertThat(tokenA.isDisposed()).isFalse();
        assertThat(tokenB.isDisposed()).as("refresh never disposes tokens").isFalse();
        assertThat(tokenB.readInto(frame)).as("a removed channel's token reads unresolved").isFalse();
    }

    @Test
    void attachOnAMissRefreshesTheSlots() {
        bus.rebind(mixer, FORMAT, 1L);
        assertThat(bus.snapshot().returnSlotCount()).isEqualTo(1);

        MixerChannel added = mixer.addReturnBus("Delay Return");
        LevelSubscription token = bus.attachLevel(new MeterTapPoint.ReturnPost(added.getId()));

        TapSnapshot taps = bus.snapshot();
        assertThat(taps.returnSlotCount()).isEqualTo(2);
        LevelTapSlot slot = taps.returnSlot(1, added);
        assertThat(slot).isNotNull();
        publish(slot, taps, 0.75f);
        MeterFrame frame = new MeterFrame();
        assertThat(token.readInto(frame)).isTrue();
        assertThat(frame.peak(1)).isCloseTo(0.75f, within(1e-6f));
    }

    @Test
    void attachForAnUnknownPointStillReturnsATokenThatReadsFalse() {
        bus.rebind(mixer, FORMAT, 1L);
        TapSnapshot before = bus.snapshot();
        LevelSubscription token = bus.attachLevel(new MeterTapPoint.ChannelPost(UUID.randomUUID()));
        assertThat(token.isDisposed()).isFalse();
        assertThat(token.readInto(new MeterFrame())).isFalse();
        assertThat(bus.snapshot().channelSlotCount()).isEqualTo(before.channelSlotCount());
    }

    @Test
    void unbindDisposesEverythingAndPublishesAnEmptySnapshot() {
        bus.rebind(mixer, FORMAT, 1L);
        LevelSubscription level = bus.attachLevel(MeterTapPoint.MASTER_OUT);
        AnalysisSubscription analysis = bus.attachAnalysis(
                MeterTapPoint.MASTER_OUT, 4, (samples, channels, frames, rate) -> { });
        List<String> order = new ArrayList<>();
        level.onDisposed(() -> order.add("level"));
        analysis.onDisposed(() -> order.add("analysis"));

        bus.unbind();

        assertThat(order).containsExactly("level", "analysis");
        assertThat(bus.isBound()).isFalse();
        assertThat(bus.levelSubscriptionCount()).isZero();
        assertThat(bus.analysisSubscriptionCount()).isZero();
        TapSnapshot taps = bus.snapshot();
        assertThat(taps.isEmpty()).isTrue();
        assertThat(taps.hasAnalysisRings()).isFalse();
        assertThat(taps.masterChain()).isNull();
        assertThat(taps.masterOut()).isNull();
        assertThat(taps.channelSlot(0, channelA)).isNull();
        assertThat(taps.epoch()).as("the empty snapshot still carries the bus epoch").isEqualTo(1L);
        bus.blockCompleted(taps);
        assertThat(bus.blockIndex()).isEqualTo(1L);
        bus.unbind();
    }

    @Test
    void closeDisposesTokensAndRejectsFurtherAttachments() {
        bus.rebind(mixer, FORMAT, 1L);
        LevelSubscription token = bus.attachLevel(MeterTapPoint.MASTER_CHAIN);

        bus.close();

        assertThat(token.isDisposed()).isTrue();
        assertThat(bus.isClosed()).isTrue();
        assertThat(bus.isBound()).isFalse();
        assertThat(bus.snapshot().isEmpty()).isTrue();
        assertThatIllegalStateException().isThrownBy(() -> bus.attachLevel(MeterTapPoint.MASTER_OUT));
        assertThatIllegalStateException().isThrownBy(() -> bus.rebind(mixer, FORMAT, 2L));
        bus.close();
    }

    @Test
    void snapshotResolvesSlotsByIndexAndIdentity() {
        bus.rebind(mixer, FORMAT, 1L);
        TapSnapshot taps = bus.snapshot();

        assertThat(taps.isEmpty()).isFalse();
        assertThat(taps.epoch()).isEqualTo(1L);
        assertThat(taps.sampleRate()).isEqualTo(48_000.0);
        assertThat(taps.channelSlotCount()).isEqualTo(2);
        assertThat(taps.returnSlotCount()).isEqualTo(1);
        assertThat(taps.channelSlot(0, channelA)).isNotNull();
        assertThat(taps.channelSlot(0, channelA).point())
                .isEqualTo(new MeterTapPoint.ChannelPost(channelA.getId()));
        assertThat(taps.channelSlot(0, channelB)).as("identity mismatch reads untapped").isNull();
        assertThat(taps.channelSlot(2, channelA)).as("out of range reads untapped").isNull();
        assertThat(taps.channelSlot(-1, channelA)).isNull();
        assertThat(taps.returnSlot(0, defaultReturn).point())
                .isEqualTo(new MeterTapPoint.ReturnPost(defaultReturn.getId()));
        assertThat(taps.returnSlot(0, channelA)).isNull();
        assertThat(taps.masterChain().point()).isEqualTo(MeterTapPoint.MASTER_CHAIN);
        assertThat(taps.masterOut().point()).isEqualTo(MeterTapPoint.MASTER_OUT);
        assertThat(taps.masterChain()).isNotSameAs(taps.masterOut());
    }

    @Test
    void masterTapsAreReadableThroughTheirSingletons() {
        bus.rebind(mixer, FORMAT, 1L);
        LevelSubscription chain = bus.attachLevel(MeterTapPoint.MASTER_CHAIN);
        LevelSubscription out = bus.attachLevel(MeterTapPoint.MASTER_OUT);
        TapSnapshot taps = bus.snapshot();
        publish(taps.masterChain(), taps, 1.0f);
        publish(taps.masterOut(), taps, 0.5f);

        MeterFrame frame = new MeterFrame();
        assertThat(chain.readInto(frame)).isTrue();
        assertThat(frame.peak(0)).isEqualTo(1.0f);
        assertThat(frame.clipped()).isTrue();
        assertThat(out.readInto(frame)).isTrue();
        assertThat(frame.peak(0)).isCloseTo(0.5f, within(1e-6f));
        assertThat(frame.clipped()).isFalse();
    }

    @Test
    void insertPairsAreKeyedByInsertSlotIdentity() {
        InsertSlot eq = new InsertSlot("EQ", new PassThrough());
        InsertSlot comp = new InsertSlot("Comp", new PassThrough());
        InsertSlot verb = new InsertSlot("Verb", new PassThrough());
        InsertSlot masterLimiter = new InsertSlot("Limiter", new PassThrough());
        channelA.addInsert(eq);
        channelA.addInsert(comp);
        defaultReturn.addInsert(verb);
        mixer.getMasterChannel().addInsert(masterLimiter);
        bus.rebind(mixer, FORMAT, 1L);

        Map<InsertSlot, InsertTapPair> pairs = bus.insertTapPairs();
        assertThat(pairs).hasSize(4).containsKeys(eq, comp, verb, masterLimiter);
        TapSnapshot taps = bus.snapshot();
        assertThat(taps.insertTapCount()).isEqualTo(4);
        assertThat(taps.insertTapFor(eq)).isSameAs(pairs.get(eq));
        assertThat(taps.insertTapFor(eq).slot()).isSameAs(eq);
        assertThat(taps.insertTapFor(new InsertSlot("EQ", new PassThrough()))).isNull();
        assertThat(taps.hasInsertTaps(channelA)).isTrue();
        assertThat(taps.hasInsertTaps(defaultReturn)).isTrue();
        assertThat(taps.hasInsertTaps(mixer.getMasterChannel())).isTrue();
        assertThat(taps.hasInsertTaps(channelB)).isFalse();

        InsertTapPair pair = taps.insertTapFor(comp);
        assertThat(pair.input().point()).isEqualTo(new MeterTapPoint.InsertIo(comp.getPluginInstanceId()));
        assertThat(pair.output().point()).isEqualTo(pair.input().point());
        assertThat(pair.input()).isNotSameAs(pair.output());

        channelA.addInsert(new InsertSlot("Gate", new PassThrough()));
        bus.refreshSlots();
        assertThat(bus.snapshot().insertTapFor(comp)).as("refresh reuses the pair").isSameAs(pair);
        assertThat(bus.snapshot().insertTapCount()).isEqualTo(5);
    }

    @Test
    void insertIoTokensReadBothHalves() {
        InsertSlot comp = new InsertSlot("Comp", new PassThrough());
        channelA.addInsert(comp);
        bus.rebind(mixer, FORMAT, 1L);
        MeterTapPoint.InsertIo point = new MeterTapPoint.InsertIo(comp.getPluginInstanceId());
        InsertIoSubscription io = bus.attachInsertIo(point);
        LevelSubscription outputOnly = bus.attachLevel(point);
        assertThat(bus.levelSubscriptionCount()).isEqualTo(2);

        TapSnapshot taps = bus.snapshot();
        InsertTapPair pair = taps.insertTapFor(comp);
        publish(pair.input(), taps, 0.9f);
        publish(pair.output(), taps, 0.3f);

        MeterFrame frame = new MeterFrame();
        assertThat(io.readInputInto(frame)).isTrue();
        assertThat(frame.peak(0)).isCloseTo(0.9f, within(1e-6f));
        assertThat(io.readOutputInto(frame)).isTrue();
        assertThat(frame.peak(0)).isCloseTo(0.3f, within(1e-6f));
        assertThat(outputOnly.readInto(frame)).isTrue();
        assertThat(frame.peak(0)).as("attachLevel on an InsertIo reads the output half")
                .isCloseTo(0.3f, within(1e-6f));

        io.dispose();
        assertThat(io.readInputInto(frame)).isFalse();
        assertThat(io.readOutputInto(frame)).isFalse();
    }

    @Test
    void blockCompletedAdvancesTheStampEveryFrameOfABlockShares() {
        bus.rebind(mixer, FORMAT, 1L);
        TapSnapshot taps = bus.snapshot();
        assertThat(taps.blockIndex()).isEqualTo(bus.blockIndex()).isZero();

        long stamp = taps.blockIndex();
        taps.channelSlot(0, channelA).publishSilence(taps.epoch(), stamp, 2);
        taps.masterOut().publishSilence(taps.epoch(), stamp, 2);
        bus.blockCompleted(taps);
        bus.blockCompleted(null);

        assertThat(taps.blockIndex()).isEqualTo(2L);
        MeterFrame frame = new MeterFrame();
        assertThat(bus.attachLevel(MeterTapPoint.MASTER_OUT).readInto(frame)).isTrue();
        assertThat(frame.blockIndex()).isZero();
        assertThat(frame.epoch()).isEqualTo(1L);
    }

    @Test
    void tokenAttachedWhileUnboundReadsFalseAndIsDisposedByTheFirstBind() {
        LevelSubscription early = bus.attachLevel(MeterTapPoint.MASTER_OUT);
        assertThat(early.epoch()).isZero();
        assertThat(early.readInto(new MeterFrame())).isFalse();
        assertThat(bus.snapshot().isEmpty()).isTrue();
        bus.refreshSlots();
        assertThat(bus.snapshot().isEmpty()).as("refresh while unbound is a no-op").isTrue();

        bus.rebind(mixer, FORMAT, 1L);

        assertThat(early.isDisposed()).isTrue();
        assertThat(bus.levelSubscriptionCount()).isZero();
    }

    @Test
    void failingOnDisposedCallbackDoesNotStopTheOthers() {
        bus.rebind(mixer, FORMAT, 1L);
        LevelSubscription first = bus.attachLevel(MeterTapPoint.MASTER_OUT);
        LevelSubscription second = bus.attachLevel(MeterTapPoint.MASTER_CHAIN);
        AtomicBoolean secondFired = new AtomicBoolean();
        first.onDisposed(() -> {
            throw new IllegalStateException("boom");
        });
        second.onDisposed(() -> secondFired.set(true));

        bus.unbind();

        assertThat(first.isDisposed()).isTrue();
        assertThat(secondFired.get()).isTrue();
    }

    /** Simulates one render-thread publish into {@code slot}: both lanes at {@code value}. */
    private static void publish(LevelTapSlot slot, TapSnapshot taps, float value) {
        assertThat(slot).isNotNull();
        slot.beginBlock(taps.epoch(), taps.blockIndex(), 2);
        slot.accumulate(0, value);
        slot.accumulate(1, value);
        slot.publish(1);
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
