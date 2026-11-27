package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AsioBackend#dropoutCounters()} as the backend wires it: the live
 * buffer-switch bridge's starved periods and capture-ring overflows, folded
 * in when the bridge is torn down so a reopen never makes a count go back.
 * A block the input publisher drops for a subscriber is told to it, never
 * counted here, and the blocks failed callbacks lost are reported apart.
 * Stub driver and streaming shims stand in for the native library; the test
 * drives the bridge's callback directly.
 */
class AsioBackendDropoutCountersTest {

    private static final DeviceId DEVICE = new DeviceId(AsioBackend.NAME, "Dropout Driver");
    private static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 32);
    private static final int FRAMES = 64;
    private static final long AWAIT_SECONDS = 10;

    private Arena arena;
    private AsioBackend backend;

    /** What the streaming shim's {@code ASIOStart} runs and answers; {@code null} answers success. */
    private volatile BooleanSupplier startHook;

    @BeforeEach
    void setUp() {
        arena = Arena.ofShared();
        AsioBackend.setDriverShimFactory(StubDriverShim::new);
        AsioBackend.setStreamingShimFactory(StubStreamingShim::new);
        backend = new AsioBackend();
    }

    @AfterEach
    void tearDown() {
        backend.close();
        AsioBackend.resetDriverShimFactory();
        AsioBackend.resetStreamingShimFactory();
        arena.close();
    }

    @Test
    void starvedPeriodsAreFoldedInAtCloseAndKeepGrowingAfterAReopen() {
        backend.open(DEVICE, FORMAT, FRAMES);
        starveOnce(backend.activeBufferSwitchShim());
        assertThat(backend.dropoutCounters().outputStarvedPeriods()).isEqualTo(1L);

        backend.close();
        assertThat(backend.dropoutCounters().outputStarvedPeriods())
                .as("a closed bridge's count is kept")
                .isEqualTo(1L);

        backend.open(DEVICE, FORMAT, FRAMES);
        AsioBufferSwitchShim reopened = backend.activeBufferSwitchShim();
        starveOnce(reopened);
        reopened.bufferSwitch(0, 1);

        BackendDropoutCounters counters = backend.dropoutCounters();
        assertThat(counters.outputStarvedPeriods()).isEqualTo(3L);
        assertThat(counters.reportsOutputStarvation()).isTrue();
    }

    /**
     * Rendered blocks sunk through the backend and discarded unplayed, skipped
     * by a late callback or refused by a full ring, are folded in at close.
     */
    @Test
    void discardedBlocksAreFoldedInAtCloseAndKeepGrowingAfterAReopen() {
        backend.open(DEVICE, FORMAT, FRAMES);
        AsioBufferSwitchShim bridge = backend.activeBufferSwitchShim();
        sinkBlocks(1);
        bridge.bufferSwitch(0, 1);
        sinkBlocks(3);
        bridge.bufferSwitch(1, 1);
        assertThat(backend.dropoutCounters().outputDiscardedBlocks())
                .as("the late callback skipped two of three queued blocks")
                .isEqualTo(2L);
        sinkBlocks(5);
        assertThat(backend.dropoutCounters().outputDiscardedBlocks())
                .as("the four-slot ring refused the fifth")
                .isEqualTo(3L);

        backend.close();
        assertThat(backend.dropoutCounters().outputDiscardedBlocks())
                .as("a closed bridge's count is kept")
                .isEqualTo(3L);

        backend.open(DEVICE, FORMAT, FRAMES);
        AsioBufferSwitchShim reopened = backend.activeBufferSwitchShim();
        sinkBlocks(1);
        reopened.bufferSwitch(0, 1);
        sinkBlocks(2);
        reopened.bufferSwitch(1, 1);

        assertThat(backend.dropoutCounters().outputDiscardedBlocks()).isEqualTo(4L);
    }

    private void sinkBlocks(int count) {
        for (int i = 0; i < count; i++) {
            backend.sink(AudioBlock.silence(FORMAT.sampleRate(), FORMAT.channels(), FRAMES));
        }
    }

    /** A capture ring the drain cannot empty overflows, and each lost block counts. */
    @Test
    void captureRingOverflowsCountAsDroppedInputBlocks() throws Exception {
        CountDownLatch drainHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        replaceInputPublisher(new SubmissionPublisher<>() {
            @Override
            public int offer(AudioBlock item,
                             BiPredicate<Flow.Subscriber<? super AudioBlock>, ? super AudioBlock> onDrop) {
                drainHeld.countDown();
                try {
                    release.await(AWAIT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return super.offer(item, onDrop);
            }
        });
        try {
            backend.open(DEVICE, FORMAT, FRAMES);
            AsioBufferSwitchShim bridge = backend.activeBufferSwitchShim();
            bridge.bufferSwitch(0, 1);
            assertThat(drainHeld.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 32; i++) {
                bridge.bufferSwitch(i & 1, 1);
            }
            assertThat(backend.dropoutCounters().inputBlocksDropped()).isZero();

            for (int i = 0; i < 4; i++) {
                bridge.bufferSwitch(i & 1, 1);
            }

            assertThat(backend.dropoutCounters().inputBlocksDropped()).isEqualTo(4L);
        } finally {
            release.countDown();
        }
    }

    /**
     * Blocks the input publisher dropped for a subscriber that could not keep
     * up are told to that subscriber, on the {@code asio-input-drain} thread,
     * and never counted: the dropped input blocks are the ring's own overflows
     * only, and the drain lost nothing (story 338, ruling E208). The stream's
     * publisher never runs a delivery and holds one block per subscriber, so
     * the second and third blocks are dropped for it.
     */
    @Test
    void theInputPublishersDropsAreToldToTheSubscriberAndOnlyRingOverflowsAreCounted() throws Exception {
        replaceInputPublisher(new SubmissionPublisher<>(task -> { }, 1));
        backend.open(DEVICE, FORMAT, FRAMES);
        CountDownLatch toldTwice = new CountDownLatch(2);
        AtomicLong told = new AtomicLong();
        backend.inputBlocks().subscribe(new CountingListener(told, toldTwice));
        AsioBufferSwitchShim bridge = backend.activeBufferSwitchShim();
        for (int i = 0; i < 3; i++) {
            bridge.bufferSwitch(i & 1, 1);
        }
        assertThat(toldTwice.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        backend.close();

        assertThat(told.get()).as("the full buffer's drops were told to the subscriber").isEqualTo(2L);
        assertThat(backend.dropoutCounters().inputBlocksDropped()).isEqualTo(bridge.inputRingOverflows());
        assertThat(backend.threadCounters().inputBlocksLost()).isZero();
    }

    /**
     * A buffer-switch fault while the period's input is copied loses that
     * period's block: counted by {@link AsioBackend#failedCallbackInputBlocks()},
     * never as a dropped input block, beside its callback fault. The torn-down
     * bridge's count is kept at close and grows on after a reopen (story 338,
     * ruling E211).
     */
    @Test
    void blocksFailedCallbacksLostAreFoldedInAtCloseAndKeepGrowingAfterAReopen() throws Exception {
        backend.open(DEVICE, FORMAT, FRAMES);
        failInputHalfOnce(backend.activeBufferSwitchShim());

        assertThat(backend.failedCallbackInputBlocks()).isEqualTo(1L);
        assertThat(backend.dropoutCounters().inputBlocksDropped()).isZero();
        assertThat(backend.threadCounters().callbackFaults()).isEqualTo(1L);

        backend.close();
        assertThat(backend.failedCallbackInputBlocks()).as("a closed bridge's count is kept").isEqualTo(1L);

        backend.open(DEVICE, FORMAT, FRAMES);
        failInputHalfOnce(backend.activeBufferSwitchShim());

        assertThat(backend.failedCallbackInputBlocks()).isEqualTo(2L);
    }

    /**
     * A failed open whose buffer-switch bridge had started its drain completes
     * the stream's input publisher, so a drain that outlived its bounded join
     * holding a block publishes nothing into the next stream (story 338,
     * ruling E215). The drain is held inside its publish until the reopen;
     * the next stream's subscriber is the only one, and it receives nothing.
     */
    @Test
    void aDrainThatOutlivesAFailedOpenCannotPublishIntoTheNextStream() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        replaceInputPublisher(new SubmissionPublisher<>(Runnable::run, Flow.defaultBufferSize()) {
            @Override
            public int offer(AudioBlock item,
                             BiPredicate<Flow.Subscriber<? super AudioBlock>, ? super AudioBlock> onDrop) {
                entered.countDown();
                awaitUninterruptibly(release);
                return super.offer(item, onDrop);
            }
        });
        AtomicReference<AsioBufferSwitchShim> failedBridge = new AtomicReference<>();
        AtomicBoolean drainHeld = new AtomicBoolean();
        startHook = () -> {
            AsioBufferSwitchShim live = backend.activeBufferSwitchShim();
            failedBridge.set(live);
            live.bufferSwitch(0, 1);
            try {
                drainHeld.set(entered.await(AWAIT_SECONDS, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return false;
        };
        try {
            assertThatThrownBy(() -> backend.open(DEVICE, FORMAT, FRAMES))
                    .hasMessageContaining("ASIOStart failed");
            assertThat(drainHeld).as("the drain took the block into its publish").isTrue();
            Thread oldDrain = failedBridge.get().drainThread();
            assertThat(oldDrain.isAlive()).as("the drain outlived the failed open's bounded join").isTrue();

            startHook = null;
            backend.open(DEVICE, FORMAT, FRAMES);
            List<AudioBlock> received = new CopyOnWriteArrayList<>();
            backend.inputBlocks().subscribe(new CollectingSubscriber(received));
            release.countDown();
            oldDrain.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));

            assertThat(oldDrain.isAlive()).isFalse();
            assertThat(received).as("the failed stream's block never reached the next stream").isEmpty();
            assertThat(failedBridge.get().publishDrops()).as("the closed publisher's refusal is teardown").isZero();
        } finally {
            startHook = null;
            release.countDown();
        }
    }

    private void failInputHalfOnce(AsioBufferSwitchShim bridge) throws ReflectiveOperationException {
        Field inputBuffers = AsioBufferSwitchShim.class.getDeclaredField("inputBuffers");
        inputBuffers.setAccessible(true);
        ((MemorySegment[][]) inputBuffers.get(bridge))[0][0] = arena.allocate(1);
        bridge.bufferSwitchUpcall(0, 1);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Requests nothing and counts the blocks dropped for it. */
    private static final class CountingListener
            implements Flow.Subscriber<AudioBlock>, InputBlockLossListener {

        private final AtomicLong told;
        private final CountDownLatch notices;

        CountingListener(AtomicLong told, CountDownLatch notices) {
            this.told = told;
            this.notices = notices;
        }

        @Override
        public void inputBlockDropped(AudioBlock block) {
            told.incrementAndGet();
            notices.countDown();
        }

        @Override public void onSubscribe(Flow.Subscription subscription) { }
        @Override public void onNext(AudioBlock block) { }
        @Override public void onError(Throwable throwable) { }
        @Override public void onComplete() { }
    }

    /** Requests everything and keeps each block it receives. */
    private static final class CollectingSubscriber implements Flow.Subscriber<AudioBlock> {

        private final List<AudioBlock> received;

        CollectingSubscriber(List<AudioBlock> received) {
            this.received = received;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override public void onNext(AudioBlock block) {
            received.add(block);
        }

        @Override public void onError(Throwable throwable) { }
        @Override public void onComplete() { }
    }

    private void starveOnce(AsioBufferSwitchShim bridge) {
        backend.sink(AudioBlock.silence(FORMAT.sampleRate(), FORMAT.channels(), FRAMES));
        bridge.bufferSwitch(0, 1);
        bridge.bufferSwitch(1, 1);
    }

    private AudioBackendSupport support() throws ReflectiveOperationException {
        Field supportField = AsioBackend.class.getDeclaredField("support");
        supportField.setAccessible(true);
        return (AudioBackendSupport) supportField.get(backend);
    }

    private void replaceInputPublisher(SubmissionPublisher<AudioBlock> publisher)
            throws ReflectiveOperationException {
        Field publisherField = AudioBackendSupport.class.getDeclaredField("publisher");
        publisherField.setAccessible(true);
        publisherField.set(support(), publisher);
    }

    private static final class StubDriverShim extends AsioDriverShim {
        @Override boolean isEnumerationAvailable() { return true; }
        @Override boolean isLifecycleAvailable() { return true; }
        @Override List<DriverDescriptor> listDrivers() {
            return List.of(new DriverDescriptor(DEVICE.name(),
                    "{00000000-0000-0000-0000-000000000000}"));
        }
        @Override boolean loadDriver(String driverName) { return true; }
        @Override public void close() { super.close(); }
    }

    /** Streaming shim backed by test-owned memory instead of a real driver. */
    private final class StubStreamingShim extends AsioStreamingShim {
        private final List<BufferInfo> bufferInfos;

        StubStreamingShim() {
            List<BufferInfo> infos = new ArrayList<>();
            for (int direction = 0; direction < 2; direction++) {
                for (int channel = 0; channel < FORMAT.channels(); channel++) {
                    MemorySegment half0 = arena.allocate(ValueLayout.JAVA_FLOAT, FRAMES);
                    MemorySegment half1 = arena.allocate(ValueLayout.JAVA_FLOAT, FRAMES);
                    infos.add(new BufferInfo(channel, direction == 0, 19,
                            half0.address(), half1.address()));
                }
            }
            bufferInfos = List.copyOf(infos);
        }

        @Override boolean isStreamingAvailable() { return true; }
        @Override boolean createBuffers(int[] in, int[] out, int frames) { return true; }
        @Override List<BufferInfo> getBufferInfos() { return bufferInfos; }
        @Override boolean start() {
            BooleanSupplier hook = startHook;
            return hook == null || hook.getAsBoolean();
        }
        @Override boolean stop() { return true; }
        @Override boolean disposeBuffers() { return true; }
        @Override boolean installBufferSwitchCallback(MemorySegment stub) { return true; }
        @Override boolean uninstallBufferSwitchCallback() { return true; }
        @Override public void close() { super.close(); }
    }
}
