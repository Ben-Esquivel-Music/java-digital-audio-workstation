package com.benesquivelmusic.daw.core.metering;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Story 318 "analysis lane substrate": a consumer attached to a tap point
 * receives the blocks the render path writes through the RT API, in order, on
 * the one {@code daw-metering-analysis} thread; overruns are counted; close
 * joins the thread.
 */
class AnalysisLaneTest {

    private static final int BLOCK_FRAMES = 64;
    private static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 24, BLOCK_FRAMES);
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);

    private MeteringTapBus bus;
    private Mixer mixer;
    private MixerChannel channel;

    @BeforeEach
    void setUp() {
        bus = new MeteringTapBus();
        mixer = new Mixer();
        channel = new MixerChannel("A");
        mixer.addChannel(channel);
        bus.rebind(mixer, FORMAT, 1L);
    }

    @AfterEach
    void tearDown() {
        bus.close();
    }

    @Test
    void attachStartsTheNamedDaemonThreadAndDeliversBlocksInOrder() throws Exception {
        assertThat(bus.isAnalysisThreadAlive()).isFalse();
        RecordingConsumer consumer = new RecordingConsumer();
        AnalysisSubscription sub = bus.attachAnalysis(
                new MeterTapPoint.ChannelPost(channel.getId()), 8, consumer);

        assertThat(bus.isAnalysisThreadAlive()).isTrue();
        assertThat(bus.analysisSubscriptionCount()).isEqualTo(1);
        assertThat(sub.ringCapacity()).isEqualTo(8);
        assertThat(sub.ringBlockFrames()).isEqualTo(BLOCK_FRAMES);
        TapSnapshot taps = bus.snapshot();
        assertThat(taps.hasAnalysisRings()).isTrue();
        LevelTapSlot slot = taps.channelSlot(0, channel);
        assertThat(slot.rings()).hasSize(1);
        assertThat(taps.masterOut().rings()).isEmpty();

        for (int i = 1; i <= 5; i++) {
            renderBlock(taps, slot, i);
        }

        awaitUntil(() -> consumer.values().size() >= 5, "five blocks delivered");
        assertThat(consumer.values()).containsExactly(1f, 2f, 3f, 4f, 5f);
        assertThat(consumer.threadNames()).containsOnly(AnalysisThread.THREAD_NAME);
        assertThat(consumer.thread().isDaemon()).isTrue();
        assertThat(consumer.thread().isVirtual()).isFalse();
        assertThat(consumer.channelCounts()).containsOnly(2);
        assertThat(consumer.frameCounts()).containsOnly(BLOCK_FRAMES);
        assertThat(consumer.sampleRates()).containsOnly(48_000.0);
        assertThat(sub.droppedBlocks()).isZero();
        assertThat(consumer.overruns()).isEmpty();
    }

    @Test
    void blockCompletedWithoutRingsStartsNoThread() {
        TapSnapshot taps = bus.snapshot();
        assertThat(taps.hasAnalysisRings()).isFalse();
        bus.blockCompleted(taps);
        assertThat(bus.isAnalysisThreadAlive()).isFalse();
        assertThat(bus.blockIndex()).isEqualTo(1L);
    }

    @Test
    void overrunIsCountedAndReportedWhileTheNewestBlocksSurvive() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        RecordingConsumer consumer = new RecordingConsumer(gate);
        AnalysisSubscription sub = bus.attachAnalysis(MeterTapPoint.MASTER_OUT, 2, consumer);
        TapSnapshot taps = bus.snapshot();
        LevelTapSlot slot = taps.masterOut();
        assertThat(slot.rings()).hasSize(1);

        final int written = 10;
        for (int i = 1; i <= written; i++) {
            renderBlock(taps, slot, i);
        }
        gate.countDown();

        awaitUntil(() -> consumer.values().size() + sub.droppedBlocks() >= written,
                "every block read or counted dropped");
        awaitUntil(() -> !consumer.overruns().isEmpty(), "onOverrun");
        List<Float> values = consumer.values();
        assertThat(values).isNotEmpty();
        assertThat(values).isSorted();
        assertThat(values.getLast()).isEqualTo(10f);
        assertThat(sub.droppedBlocks()).isGreaterThanOrEqualTo(written - 3L);
        assertThat(values.size() + sub.droppedBlocks()).isEqualTo(written);
        assertThat(consumer.overruns().getLast()).isEqualTo(sub.droppedBlocks());
    }

    @Test
    void consumerExceptionDoesNotStopTheLane() throws Exception {
        RecordingConsumer consumer = new RecordingConsumer();
        consumer.throwOnFirstBlock = true;
        bus.attachAnalysis(MeterTapPoint.MASTER_CHAIN, 4, consumer);
        TapSnapshot taps = bus.snapshot();
        LevelTapSlot slot = taps.masterChain();

        renderBlock(taps, slot, 1);
        awaitUntil(() -> consumer.attempts() >= 1, "first (throwing) delivery");
        renderBlock(taps, slot, 2);
        renderBlock(taps, slot, 3);

        awaitUntil(() -> consumer.values().size() >= 2, "later blocks still delivered");
        assertThat(consumer.values()).containsExactly(2f, 3f);
        assertThat(bus.isAnalysisThreadAlive()).isTrue();
    }

    @Test
    void disposeSwapsTheRingOutOfTheSnapshot() throws Exception {
        RecordingConsumer consumer = new RecordingConsumer();
        AnalysisSubscription sub = bus.attachAnalysis(MeterTapPoint.MASTER_OUT, 4, consumer);
        TapSnapshot before = bus.snapshot();
        SampleBlockRing[] ringsBefore = before.masterOut().rings();
        assertThat(ringsBefore).hasSize(1);
        renderBlock(before, before.masterOut(), 1);
        awaitUntil(() -> consumer.values().size() == 1, "first block");

        AtomicBoolean disposed = new AtomicBoolean();
        sub.onDisposed(() -> disposed.set(true));
        sub.dispose();

        assertThat(disposed.get()).isTrue();
        TapSnapshot after = bus.snapshot();
        assertThat(after).isNotSameAs(before);
        assertThat(after.hasAnalysisRings()).isFalse();
        assertThat(after.masterOut().rings()).isEmpty();
        assertThat(ringsBefore).as("the previous array is never mutated in place").hasSize(1);
        assertThat(bus.analysisSubscriptionCount()).isZero();
        assertThat(bus.lanes()).isEmpty();

        renderBlock(after, after.masterOut(), 2);
        Thread.sleep(50L);
        assertThat(consumer.values()).containsExactly(1f);
        assertThat(bus.isAnalysisThreadAlive()).as("the thread outlives its last lane").isTrue();
    }

    @Test
    void refreshKeepsRingsOnReusedSlots() {
        bus.attachAnalysis(new MeterTapPoint.ChannelPost(channel.getId()), 4, new RecordingConsumer());
        SampleBlockRing ring = bus.snapshot().channelSlot(0, channel).rings()[0];

        mixer.addChannel(new MixerChannel("B"));
        bus.refreshSlots();

        TapSnapshot after = bus.snapshot();
        assertThat(after.channelSlotCount()).isEqualTo(2);
        assertThat(after.channelSlot(0, channel).rings()).containsExactly(ring);
        assertThat(after.hasAnalysisRings()).isTrue();
    }

    @Test
    void closeJoinsTheAnalysisThread() throws Exception {
        RecordingConsumer consumer = new RecordingConsumer();
        bus.attachAnalysis(MeterTapPoint.MASTER_OUT, 4, consumer);
        TapSnapshot taps = bus.snapshot();
        renderBlock(taps, taps.masterOut(), 1);
        awaitUntil(() -> consumer.thread() != null, "thread captured");
        Thread analysisThread = consumer.thread();
        assertThat(analysisThread.isAlive()).isTrue();

        bus.close();

        assertThat(analysisThread.isAlive()).as("close joins the analysis thread").isFalse();
        assertThat(bus.isAnalysisThreadAlive()).isFalse();
        assertThat(bus.isClosed()).isTrue();
        assertThatIllegalStateException().isThrownBy(
                () -> bus.attachAnalysis(MeterTapPoint.MASTER_OUT, 4, consumer));
    }

    @Test
    void ringIsSizedFromTheBoundFormatOrTheDefaultWhenUnbound() {
        AnalysisSubscription bound = bus.attachAnalysis(
                MeterTapPoint.MASTER_OUT, 3, new RecordingConsumer());
        assertThat(bound.ringBlockFrames()).isEqualTo(BLOCK_FRAMES);
        assertThat(bound.ringCapacity()).isEqualTo(4);

        MeteringTapBus unbound = new MeteringTapBus();
        try {
            AnalysisSubscription early = unbound.attachAnalysis(
                    MeterTapPoint.MASTER_OUT, 2, new RecordingConsumer());
            assertThat(early.ringBlockFrames()).isEqualTo(MeteringTapBus.DEFAULT_RING_BLOCK_FRAMES);
            assertThat(early.epoch()).isZero();
            assertThat(unbound.snapshot().hasAnalysisRings()).isFalse();
        } finally {
            unbound.close();
        }
    }

    @Test
    void attachAnalysisValidatesItsArguments() {
        RecordingConsumer consumer = new RecordingConsumer();
        assertThatIllegalArgumentException().isThrownBy(
                () -> bus.attachAnalysis(MeterTapPoint.MASTER_OUT, 0, consumer));
        assertThatNullPointerException().isThrownBy(
                () -> bus.attachAnalysis(MeterTapPoint.MASTER_OUT, 4, null));
        assertThatNullPointerException().isThrownBy(
                () -> bus.attachAnalysis(null, 4, consumer));
        assertThat(bus.isAnalysisThreadAlive()).isFalse();
    }

    /** Simulates the render path for one block: level publish, ring copies, block end. */
    private void renderBlock(TapSnapshot taps, LevelTapSlot slot, int value) {
        float[][] src = new float[2][BLOCK_FRAMES];
        Arrays.fill(src[0], (float) value);
        Arrays.fill(src[1], (float) -value);
        slot.beginBlock(taps.epoch(), taps.blockIndex(), 2);
        slot.accumulate(0, src[0], BLOCK_FRAMES);
        slot.accumulate(1, src[1], BLOCK_FRAMES);
        slot.publish(BLOCK_FRAMES);
        SampleBlockRing[] rings = slot.rings();
        for (SampleBlockRing ring : rings) {
            ring.write(src, 2, BLOCK_FRAMES);
        }
        bus.blockCompleted(taps);
    }

    private static void awaitUntil(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT_NANOS;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(2L);
        }
    }

    /** Records every delivery; optionally gates the first delivery and/or throws on it. */
    private static final class RecordingConsumer implements AnalysisConsumer {
        private final CountDownLatch gate;
        private final List<Float> values = new ArrayList<>();
        private final List<Integer> channelCounts = new ArrayList<>();
        private final List<Integer> frameCounts = new ArrayList<>();
        private final List<Double> sampleRates = new ArrayList<>();
        private final List<String> threadNames = new ArrayList<>();
        private final List<Long> overruns = new ArrayList<>();
        private final AtomicReference<Thread> thread = new AtomicReference<>();
        private int attempts;
        volatile boolean throwOnFirstBlock;

        RecordingConsumer() {
            this(null);
        }

        RecordingConsumer(CountDownLatch gate) {
            this.gate = gate;
        }

        @Override
        public void onBlock(float[][] samples, int channelCount, int numFrames, double sampleRate) {
            thread.compareAndSet(null, Thread.currentThread());
            if (gate != null) {
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            boolean first;
            synchronized (this) {
                first = attempts++ == 0;
            }
            if (first && throwOnFirstBlock) {
                throw new IllegalStateException("simulated consumer failure");
            }
            float value = samples[0][0];
            for (int f = 1; f < numFrames; f++) {
                if (samples[0][f] != value || samples[1][f] != -value) {
                    throw new AssertionError("torn block delivered: " + value);
                }
            }
            synchronized (this) {
                values.add(value);
                channelCounts.add(channelCount);
                frameCounts.add(numFrames);
                sampleRates.add(sampleRate);
                threadNames.add(Thread.currentThread().getName());
            }
        }

        @Override
        public void onOverrun(long droppedBlocks) {
            synchronized (this) {
                overruns.add(droppedBlocks);
            }
        }

        synchronized List<Float> values() {
            return List.copyOf(values);
        }

        synchronized List<Integer> channelCounts() {
            return List.copyOf(channelCounts);
        }

        synchronized List<Integer> frameCounts() {
            return List.copyOf(frameCounts);
        }

        synchronized List<Double> sampleRates() {
            return List.copyOf(sampleRates);
        }

        synchronized List<String> threadNames() {
            return List.copyOf(threadNames);
        }

        synchronized List<Long> overruns() {
            return List.copyOf(overruns);
        }

        synchronized int attempts() {
            return attempts;
        }

        Thread thread() {
            return thread.get();
        }
    }
}
