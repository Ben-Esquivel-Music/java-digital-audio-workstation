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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AsioBackend#dropoutCounters()} as the backend wires it: the live
 * buffer-switch bridge's starved periods and capture-ring overflows, folded
 * in when the bridge is torn down so a reopen never makes a count go back,
 * plus the input publisher's drops. Stub driver and streaming shims stand in
 * for the native library; the test drives the bridge's callback directly.
 */
class AsioBackendDropoutCountersTest {

    private static final DeviceId DEVICE = new DeviceId(AsioBackend.NAME, "Dropout Driver");
    private static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 32);
    private static final int FRAMES = 64;
    private static final long AWAIT_SECONDS = 10;

    private Arena arena;
    private AsioBackend backend;

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
     * Blocks the input publisher dropped for a subscriber that never requests
     * are counted beside the ring's own overflows, and only those: the
     * drain's publish failures stay in {@code threadCounters()}.
     */
    @Test
    void theInputPublishersDropsJoinTheDroppedInputBlocks() throws Exception {
        backend.open(DEVICE, FORMAT, FRAMES);
        backend.inputBlocks().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { /* never requests */ }
            @Override public void onNext(AudioBlock block) { }
            @Override public void onError(Throwable t) { }
            @Override public void onComplete() { }
        });
        AsioBufferSwitchShim bridge = backend.activeBufferSwitchShim();
        for (int i = 0; i < Flow.defaultBufferSize() * 4; i++) {
            bridge.bufferSwitch(i & 1, 1);
            if ((i & 31) == 31) {
                Thread.sleep(2);
            }
        }

        backend.close();

        long publisherDrops = support().inputBlocksDropped();
        assertThat(publisherDrops).as("the saturated subscriber dropped blocks").isPositive();
        assertThat(backend.dropoutCounters().inputBlocksDropped())
                .isEqualTo(bridge.inputRingOverflows() + publisherDrops);
        assertThat(backend.threadCounters().inputBlocksLost()).isZero();
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
        @Override boolean start() { return true; }
        @Override boolean stop() { return true; }
        @Override boolean disposeBuffers() { return true; }
        @Override boolean installBufferSwitchCallback(MemorySegment stub) { return true; }
        @Override boolean uninstallBufferSwitchCallback() { return true; }
        @Override public void close() { super.close(); }
    }
}
