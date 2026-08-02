package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 311 headline acceptance test for {@link AsioBufferSwitchShim}.
 *
 * <p>Fake "driver" buffers are allocated from a test-owned
 * {@link Arena#ofShared()} and their raw addresses are handed to the shim as
 * {@link AsioStreamingShim.BufferInfo} records with
 * {@code sampleType = 19 (ASIOSTFloat32LSB)}, exactly as
 * {@code asioshim_getBufferInfos} would. Synthetic
 * {@link AsioBufferSwitchShim#bufferSwitch(int, int)} calls then stand in for
 * the driver's real-time callback — the same "drive the package-private
 * entrypoint directly" idiom {@code AsioFormatChangeShimTest} uses for
 * {@code dispatch}.</p>
 *
 * <p>Every channel carries distinct, non-zero values so a stride error or a
 * channel swap fails loudly instead of silently passing.</p>
 *
 * <p>Assertions about <em>how many</em> blocks a callback published are made
 * deterministic without a sleep-based quiet period: the test closes the shim
 * (which joins the {@code asio-input-drain} thread after a final flush) and
 * only then publishes a recognisable <em>sentinel</em> straight through
 * {@link AudioBackendSupport#publishInput(AudioBlock)}. A
 * {@code SubmissionPublisher} preserves per-subscriber order, so the sentinel's
 * index in the received list is an exact count of what the callbacks
 * published.</p>
 */
class AsioBufferSwitchShimTest {

    /** {@code ASIOSTFloat32LSB} — the only type story 311 converts. */
    private static final int FLOAT32_LSB = 19;
    /** {@code ASIOSTInt32LSB} — deferred to story 312. */
    private static final int INT32_LSB = 17;

    private static final int HALVES = 2;
    private static final long AWAIT_SECONDS = 10;
    private static final float SENTINEL = 12_345f;

    private Arena arena;
    private AudioBackendSupport support;
    private AsioBufferSwitchShim shim;

    private int channels;
    private int frames;
    private AudioFormat format;
    private MemorySegment[][] driverInputs;
    private MemorySegment[][] driverOutputs;

    @BeforeEach
    void setUp() {
        arena = Arena.ofShared();
        fixture(2, 4);
    }

    @AfterEach
    void tearDown() {
        if (shim != null) {
            shim.close();
            shim = null;
        }
        support.close();
        arena.close();
    }

    // ------------------------------------------------------------------
    // Capture (driver -> engine)
    // ------------------------------------------------------------------

    @Test
    void eachCallbackPublishesExactlyOneDeinterleavedInputBlock() throws Exception {
        buildShim(FLOAT32_LSB);
        writeDriverBuffer(driverInputs[0][0], 0.1f, 0.2f, 0.3f, 0.4f);
        writeDriverBuffer(driverInputs[0][1], -0.5f, -0.6f, -0.7f, -0.8f);
        CapturingSubscriber subscriber = subscribe();

        shim.bufferSwitch(0, 1);
        flushAndPublishSentinel();

        assertThat(subscriber.await(2)).isTrue();
        assertThat(subscriber.blocks()).hasSize(2);
        AudioBlock first = subscriber.blocks().get(0);
        assertThat(first.channels()).isEqualTo(channels);
        assertThat(first.frames()).isEqualTo(frames);
        assertThat(first.samples()).containsExactly(
                0.1f, -0.5f,
                0.2f, -0.6f,
                0.3f, -0.7f,
                0.4f, -0.8f);
    }

    @Test
    void secondHalfIsReadFromTheSecondDriverBufferAndPublishesOneMoreBlock()
            throws Exception {
        buildShim(FLOAT32_LSB);
        writeDriverBuffer(driverInputs[0][0], 0.1f, 0.2f, 0.3f, 0.4f);
        writeDriverBuffer(driverInputs[0][1], -0.5f, -0.6f, -0.7f, -0.8f);
        writeDriverBuffer(driverInputs[1][0], 1.1f, 1.2f, 1.3f, 1.4f);
        writeDriverBuffer(driverInputs[1][1], -1.5f, -1.6f, -1.7f, -1.8f);
        CapturingSubscriber subscriber = subscribe();

        shim.bufferSwitch(0, 1);
        shim.bufferSwitch(1, 1);

        assertThat(subscriber.await(2)).isTrue();
        assertThat(subscriber.blocks()).hasSize(2);
        assertThat(subscriber.blocks().get(1).samples()).containsExactly(
                1.1f, -1.5f,
                1.2f, -1.6f,
                1.3f, -1.7f,
                1.4f, -1.8f);
    }

    /**
     * A 3&nbsp;&times;&nbsp;5 case with a distinct value per (channel, frame):
     * the 2&nbsp;&times;&nbsp;4 fixture above cannot catch an off-by-one on the
     * frame count or a channel-count / frame-count transposition.
     */
    @Test
    void deinterleaveAndInterleaveAreCorrectForThreeChannelsAndFiveFrames()
            throws Exception {
        fixture(3, 5);
        buildShim(FLOAT32_LSB);
        for (int channel = 0; channel < 3; channel++) {
            for (int frame = 0; frame < 5; frame++) {
                driverInputs[0][channel].set(ValueLayout.JAVA_FLOAT_UNALIGNED,
                        (long) frame * Float.BYTES, cell(channel, frame));
            }
        }
        float[] outgoing = new float[3 * 5];
        for (int frame = 0; frame < 5; frame++) {
            for (int channel = 0; channel < 3; channel++) {
                outgoing[frame * 3 + channel] = -cell(channel, frame);
            }
        }
        CapturingSubscriber subscriber = subscribe();
        shim.write(new AudioBlock(format.sampleRate(), 3, 5, outgoing));

        shim.bufferSwitch(0, 1);
        flushAndPublishSentinel();

        assertThat(subscriber.await(2)).isTrue();
        float[] captured = subscriber.blocks().get(0).samples();
        assertThat(captured).hasSize(15);
        for (int frame = 0; frame < 5; frame++) {
            for (int channel = 0; channel < 3; channel++) {
                assertThat(captured[frame * 3 + channel])
                        .as("captured channel %d frame %d", channel, frame)
                        .isEqualTo(cell(channel, frame));
            }
        }
        for (int channel = 0; channel < 3; channel++) {
            float[] played = readDriverBuffer(driverOutputs[0][channel], 5);
            for (int frame = 0; frame < 5; frame++) {
                assertThat(played[frame])
                        .as("played channel %d frame %d", channel, frame)
                        .isEqualTo(-cell(channel, frame));
            }
        }
    }

    /**
     * B1's restructure: captured blocks are freshly allocated on the
     * {@code asio-input-drain} thread, so two consecutive blocks must be
     * independent objects — there is no recycled pool and no aliasing window.
     */
    @Test
    void publishedBlocksAreIndependentInstancesRatherThanRecycledSlots()
            throws Exception {
        buildShim(FLOAT32_LSB);
        CapturingSubscriber subscriber = subscribe();

        writeDriverBuffer(driverInputs[0][0], 1f, 1f, 1f, 1f);
        writeDriverBuffer(driverInputs[0][1], 1f, 1f, 1f, 1f);
        shim.bufferSwitch(0, 1);
        writeDriverBuffer(driverInputs[0][0], 2f, 2f, 2f, 2f);
        writeDriverBuffer(driverInputs[0][1], 2f, 2f, 2f, 2f);
        shim.bufferSwitch(0, 1);

        assertThat(subscriber.await(2)).isTrue();
        AudioBlock first = subscriber.blocks().get(0);
        AudioBlock second = subscriber.blocks().get(1);
        assertThat(first.samples())
                .as("the first block must not be overwritten by the second")
                .containsOnly(1f);
        assertThat(second.samples()).containsOnly(2f);
        assertThat(first.samples())
                .as("blocks must not share a sample array")
                .isNotSameAs(second.samples());
    }

    @Test
    void drainThreadDeliversEveryCapturedBlockInOrder() throws Exception {
        buildShim(FLOAT32_LSB);
        CapturingSubscriber subscriber = subscribe();
        int callbacks = 16; // well inside the 32-slot capture ring: no drops

        for (int i = 1; i <= callbacks; i++) {
            int half = i & 1;
            for (int channel = 0; channel < channels; channel++) {
                writeDriverBuffer(driverInputs[half][channel], i, i, i, i);
            }
            shim.bufferSwitch(half, 1);
        }

        assertThat(subscriber.await(callbacks)).isTrue();
        List<AudioBlock> blocks = subscriber.blocks();
        assertThat(blocks).hasSize(callbacks);
        for (int i = 0; i < callbacks; i++) {
            assertThat(blocks.get(i).samples())
                    .as("captured block %d must arrive in order and intact", i)
                    .containsOnly((float) (i + 1));
        }
    }

    /**
     * The property the whole off-thread restructure buys: a subscriber that
     * never calls {@link Flow.Subscription#request} saturates the publisher's
     * per-subscriber buffer, and the driver's callback must still return
     * promptly. It cannot touch the publisher at all, so nothing on the
     * callback thread can allocate, lock or stall.
     */
    @Test
    void bufferSwitchNeverBlocksEvenWhenTheInputSubscriberNeverRequests()
            throws Exception {
        buildShim(FLOAT32_LSB);
        NeverRequestingSubscriber saturated = new NeverRequestingSubscriber();
        support.inputBlocks().subscribe(saturated);
        assertThat(saturated.awaitSubscription()).isTrue();

        int callbacks = Flow.defaultBufferSize() * 4; // 1024 — far past the buffer
        CountDownLatch done = new CountDownLatch(1);
        AtomicLong slowestNanos = new AtomicLong();
        Thread driverThread = new Thread(() -> {
            for (int i = 0; i < callbacks; i++) {
                long start = System.nanoTime();
                shim.bufferSwitch(i & 1, 1);
                slowestNanos.accumulateAndGet(System.nanoTime() - start, Math::max);
            }
            done.countDown();
        }, "fake-asio-callback");
        driverThread.setDaemon(true);
        driverThread.start();

        assertThat(done.await(AWAIT_SECONDS, TimeUnit.SECONDS))
                .as("%d bufferSwitch callbacks must complete against a saturated "
                        + "subscriber — the callback must never publish", callbacks)
                .isTrue();
        driverThread.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
        assertThat(driverThread.isAlive()).isFalse();
        assertThat(slowestNanos.get())
                .as("no single bufferSwitch may take a full second")
                .isLessThan(TimeUnit.SECONDS.toNanos(1));
    }

    @Test
    void inputBlocksAreMarshalledByANamedDaemonDrainThreadThatCloseStops() {
        buildShim(FLOAT32_LSB);
        Thread drain = shim.drainThread();

        assertThat(drain.getName()).isEqualTo(AsioBufferSwitchShim.DRAIN_THREAD_NAME);
        assertThat(drain.isDaemon())
                .as("the drain thread must never keep the JVM alive")
                .isTrue();
        assertThat(drain.isAlive()).isTrue();

        shim.close();

        assertThat(drain.isAlive())
                .as("close() must join the drain thread before freeing the arena")
                .isFalse();
    }

    // ------------------------------------------------------------------
    // Playback (engine -> driver)
    // ------------------------------------------------------------------

    @Test
    void blockWrittenToSinkIsInterleavedIntoTheDriverBuffersOnTheNextCallback() {
        buildShim(FLOAT32_LSB);
        shim.write(new AudioBlock(format.sampleRate(), channels, frames, new float[] {
                0.25f, -0.75f,
                0.26f, -0.76f,
                0.27f, -0.77f,
                0.28f, -0.78f}));

        shim.bufferSwitch(1, 1);

        assertThat(readDriverBuffer(driverOutputs[1][0], frames))
                .containsExactly(0.25f, 0.26f, 0.27f, 0.28f);
        assertThat(readDriverBuffer(driverOutputs[1][1], frames))
                .containsExactly(-0.75f, -0.76f, -0.77f, -0.78f);
    }

    /**
     * Two blocks sunk before a single callback: the driver must receive the
     * <em>second</em> one. A FIFO-oldest implementation passes every other
     * playback test in this class.
     */
    @Test
    void theMostRecentSunkBlockWinsWhenTwoArriveBetweenCallbacks() {
        buildShim(FLOAT32_LSB);
        shim.write(new AudioBlock(format.sampleRate(), channels, frames, new float[] {
                1f, -1f, 1f, -1f, 1f, -1f, 1f, -1f}));
        shim.write(new AudioBlock(format.sampleRate(), channels, frames, new float[] {
                2f, -2f, 2f, -2f, 2f, -2f, 2f, -2f}));

        shim.bufferSwitch(0, 1);

        assertThat(readDriverBuffer(driverOutputs[0][0], frames))
                .as("the freshest rendered block must reach the driver")
                .containsOnly(2f);
        assertThat(readDriverBuffer(driverOutputs[0][1], frames)).containsOnly(-2f);
    }

    @Test
    void outputIsZeroFilledWhenNothingWasRenderedRatherThanRepeatingStaleAudio() {
        buildShim(FLOAT32_LSB);
        writeDriverBuffer(driverOutputs[0][0], 9f, 9f, 9f, 9f);
        writeDriverBuffer(driverOutputs[0][1], 9f, 9f, 9f, 9f);

        shim.bufferSwitch(0, 1);

        assertThat(readDriverBuffer(driverOutputs[0][0], frames)).containsOnly(0f);
        assertThat(readDriverBuffer(driverOutputs[0][1], frames)).containsOnly(0f);
    }

    @Test
    void previouslyWrittenBlockIsNotReplayedOnTheFollowingCallback() {
        buildShim(FLOAT32_LSB);
        shim.write(new AudioBlock(format.sampleRate(), channels, frames, new float[] {
                1f, 2f, 1f, 2f, 1f, 2f, 1f, 2f}));

        shim.bufferSwitch(0, 1);
        shim.bufferSwitch(1, 1);

        assertThat(readDriverBuffer(driverOutputs[0][0], frames)).containsOnly(1f);
        assertThat(readDriverBuffer(driverOutputs[1][0], frames))
                .as("a consumed block must not be replayed into the next half")
                .containsOnly(0f);
    }

    // ------------------------------------------------------------------
    // Degradation
    // ------------------------------------------------------------------

    /**
     * Story 312 owns the general {@code ASIOSampleType} conversion. Until then
     * an unsupported type must play <em>silence</em>: leaving the driver's own
     * bytes in place would replay them every block as a fixed-pitch buzz, and
     * most real drivers report {@code ASIOSTInt32LSB} rather than float32.
     */
    @Test
    void unsupportedSampleTypeSilencesBothTheInputBlockAndTheDriverOutput()
            throws Exception {
        buildShim(INT32_LSB);
        writeDriverBuffer(driverInputs[0][0], 0.1f, 0.2f, 0.3f, 0.4f);
        writeDriverBuffer(driverOutputs[0][0], 7f, 7f, 7f, 7f);
        writeDriverBuffer(driverOutputs[0][1], 7f, 7f, 7f, 7f);
        CapturingSubscriber subscriber = subscribe();
        shim.write(new AudioBlock(format.sampleRate(), channels, frames,
                new float[channels * frames]));

        shim.bufferSwitch(0, 1);
        flushAndPublishSentinel();

        assertThat(subscriber.await(2)).isTrue();
        assertThat(subscriber.blocks().get(0).samples()).containsOnly(0f);
        assertThat(readDriverBuffer(driverOutputs[0][0], frames))
                .as("driver garbage must be overwritten with silence, not replayed")
                .containsOnly(0f);
        assertThat(readDriverBuffer(driverOutputs[0][1], frames)).containsOnly(0f);
    }

    /**
     * Story 311 (S1): a driver may expose fewer channels than the opened format
     * asks for — a playback-only interface reports no inputs at all. Unbound
     * channels capture silence and are skipped on output; they must not throw.
     */
    @Test
    void channelsTheDriverDoesNotExposeCaptureSilenceAndAreSkippedOnOutput()
            throws Exception {
        List<AsioStreamingShim.BufferInfo> infos = List.of(
                new AsioStreamingShim.BufferInfo(0, true, FLOAT32_LSB,
                        driverInputs[0][0].address(), driverInputs[1][0].address()),
                new AsioStreamingShim.BufferInfo(0, false, FLOAT32_LSB,
                        driverOutputs[0][0].address(), driverOutputs[1][0].address()));
        shim = new AsioBufferSwitchShim(support, format, frames, infos);
        writeDriverBuffer(driverInputs[0][0], 0.1f, 0.2f, 0.3f, 0.4f);
        writeDriverBuffer(driverOutputs[0][1], 5f, 5f, 5f, 5f);
        CapturingSubscriber subscriber = subscribe();
        shim.write(new AudioBlock(format.sampleRate(), channels, frames, new float[] {
                0.9f, 0.8f, 0.9f, 0.8f, 0.9f, 0.8f, 0.9f, 0.8f}));

        shim.bufferSwitch(0, 1);
        flushAndPublishSentinel();

        assertThat(subscriber.await(2)).isTrue();
        assertThat(subscriber.blocks().get(0).samples()).containsExactly(
                0.1f, 0f,
                0.2f, 0f,
                0.3f, 0f,
                0.4f, 0f);
        assertThat(readDriverBuffer(driverOutputs[0][0], frames))
                .containsOnly(0.9f);
        assertThat(readDriverBuffer(driverOutputs[0][1], frames))
                .as("a channel the driver never reported has no buffer to write")
                .containsOnly(5f);
    }

    /**
     * Out-of-range halves publish nothing. Deterministic without a quiet
     * period: the capture ring is FIFO, so a block produced by the ignored
     * callbacks would necessarily arrive <em>before</em> the valid one.
     */
    @Test
    void outOfRangeBufferIndexIsIgnored() throws Exception {
        buildShim(FLOAT32_LSB);
        writeDriverBuffer(driverInputs[0][0], 4f, 4f, 4f, 4f);
        writeDriverBuffer(driverInputs[0][1], 4f, 4f, 4f, 4f);
        CapturingSubscriber subscriber = subscribe();

        shim.bufferSwitch(-1, 1);
        shim.bufferSwitch(2, 1);
        shim.bufferSwitch(0, 1);
        flushAndPublishSentinel();

        assertThat(subscriber.await(2)).isTrue();
        assertThat(subscriber.blocks()).hasSize(2);
        assertThat(subscriber.blocks().get(0).samples()).containsOnly(4f);
    }

    @Test
    void quiescedBridgeStopsTouchingDriverBuffersAndPublishesNothing()
            throws Exception {
        buildShim(FLOAT32_LSB);
        writeDriverBuffer(driverOutputs[0][0], 3f, 3f, 3f, 3f);
        writeDriverBuffer(driverInputs[0][0], 6f, 6f, 6f, 6f);
        CapturingSubscriber subscriber = subscribe();

        shim.stopStreaming();
        assertThat(shim.isStreaming()).isFalse();
        shim.bufferSwitch(0, 1);
        flushAndPublishSentinel();

        assertThat(subscriber.await(1)).isTrue();
        assertThat(subscriber.blocks()).hasSize(1);
        assertThat(subscriber.blocks().get(0).samples())
                .as("the sentinel must be the first block: the quiesced callback "
                        + "published nothing before it")
                .containsOnly(SENTINEL);
        assertThat(readDriverBuffer(driverOutputs[0][0], frames)).containsOnly(3f);
    }

    @Test
    void upcallStubIsBuiltOnEveryPlatformAndCloseIsIdempotent() {
        buildShim(FLOAT32_LSB);
        assertThat(shim.upcallStub()).isNotNull();
        assertThat(shim.upcallStub().equals(MemorySegment.NULL)).isFalse();
        assertThat(shim.bufferFrames()).isEqualTo(frames);
        shim.close();
        shim.close();
        assertThat(shim.isStreaming()).isFalse();
    }

    // ------------------------------------------------------------------
    // Fixture helpers
    // ------------------------------------------------------------------

    /** A distinct, non-zero value per (channel, frame) pair. */
    private static float cell(int channel, int frame) {
        return (channel + 1) * 100f + (frame + 1);
    }

    /**
     * (Re)builds the driver-buffer fixture and the backend support for a given
     * geometry. Called from {@code setUp} with the default 2 &times; 4 shape.
     */
    private void fixture(int channelCount, int frameCount) {
        if (support != null) {
            support.close();
        }
        this.channels = channelCount;
        this.frames = frameCount;
        this.format = new AudioFormat(48_000.0, channelCount, 32);
        this.driverInputs = allocateHalves();
        this.driverOutputs = allocateHalves();
        this.support = new AudioBackendSupport();
        support.markOpen(format, frameCount);
    }

    private void buildShim(int sampleType) {
        List<AsioStreamingShim.BufferInfo> infos = new ArrayList<>();
        for (int channel = 0; channel < channels; channel++) {
            infos.add(new AsioStreamingShim.BufferInfo(channel, true, sampleType,
                    driverInputs[0][channel].address(),
                    driverInputs[1][channel].address()));
        }
        for (int channel = 0; channel < channels; channel++) {
            infos.add(new AsioStreamingShim.BufferInfo(channel, false, sampleType,
                    driverOutputs[0][channel].address(),
                    driverOutputs[1][channel].address()));
        }
        shim = new AsioBufferSwitchShim(support, format, frames, List.copyOf(infos));
    }

    private MemorySegment[][] allocateHalves() {
        MemorySegment[][] halves = new MemorySegment[HALVES][channels];
        for (int half = 0; half < HALVES; half++) {
            for (int channel = 0; channel < channels; channel++) {
                halves[half][channel] = arena.allocate(ValueLayout.JAVA_FLOAT, frames);
            }
        }
        return halves;
    }

    private static void writeDriverBuffer(MemorySegment buffer, float... values) {
        for (int frame = 0; frame < values.length; frame++) {
            buffer.set(ValueLayout.JAVA_FLOAT_UNALIGNED,
                    (long) frame * Float.BYTES, values[frame]);
        }
    }

    private static float[] readDriverBuffer(MemorySegment buffer, int frameCount) {
        float[] values = new float[frameCount];
        for (int frame = 0; frame < frameCount; frame++) {
            values[frame] = buffer.get(ValueLayout.JAVA_FLOAT_UNALIGNED,
                    (long) frame * Float.BYTES);
        }
        return values;
    }

    /**
     * Terminates the drain thread — {@link AsioBufferSwitchShim#close()} flushes
     * the capture ring and joins it — and only then publishes a recognisable
     * block straight through the support. Because the drain thread is provably
     * finished before the sentinel is offered, and a
     * {@code SubmissionPublisher} preserves per-subscriber order, the
     * sentinel's index is an exact count of what the callbacks published.
     */
    private void flushAndPublishSentinel() {
        shim.close();
        float[] samples = new float[channels * frames];
        java.util.Arrays.fill(samples, SENTINEL);
        support.publishInput(
                new AudioBlock(format.sampleRate(), channels, frames, samples));
    }

    private CapturingSubscriber subscribe() throws InterruptedException {
        CapturingSubscriber subscriber = new CapturingSubscriber();
        support.inputBlocks().subscribe(subscriber);
        assertThat(subscriber.awaitSubscription())
                .as("the subscription must be established before blocks are driven")
                .isTrue();
        return subscriber;
    }

    /**
     * Collects published blocks. Story 311's drain thread allocates a fresh
     * {@link AudioBlock} per callback, so the received instances are safe to
     * retain — no defensive copy is needed here, and
     * {@code publishedBlocksAreIndependentInstancesRatherThanRecycledSlots}
     * asserts exactly that.
     */
    private static final class CapturingSubscriber implements Flow.Subscriber<AudioBlock> {

        private final List<AudioBlock> received =
                Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch subscribed = new CountDownLatch(1);

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
            subscribed.countDown();
        }

        @Override
        public void onNext(AudioBlock item) {
            received.add(item);
        }

        @Override public void onError(Throwable throwable) { }

        @Override public void onComplete() { }

        boolean awaitSubscription() throws InterruptedException {
            return subscribed.await(AWAIT_SECONDS, TimeUnit.SECONDS);
        }

        List<AudioBlock> blocks() {
            return List.copyOf(received);
        }

        boolean await(int expected) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
            while (System.nanoTime() < deadline) {
                if (received.size() >= expected) {
                    return true;
                }
                Thread.sleep(5);
            }
            return received.size() >= expected;
        }
    }

    /** Never requests, so the publisher's per-subscriber buffer saturates. */
    private static final class NeverRequestingSubscriber
            implements Flow.Subscriber<AudioBlock> {

        private final CountDownLatch subscribed = new CountDownLatch(1);

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            // Deliberately no request(...) — this is the back-pressure the
            // callback thread must never wait on.
            subscribed.countDown();
        }

        @Override public void onNext(AudioBlock item) { }

        @Override public void onError(Throwable throwable) { }

        @Override public void onComplete() { }

        boolean awaitSubscription() throws InterruptedException {
            return subscribed.await(AWAIT_SECONDS, TimeUnit.SECONDS);
        }
    }
}
