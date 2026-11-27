package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 311 headline acceptance test for {@link AsioBufferSwitchShim},
 * extended by story 312 with the non-float {@code ASIOSampleType} paths.
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

    /** {@code ASIOSTInt24MSB} — packed 3-byte, big-endian. */
    private static final int INT24_MSB = 1;
    /** {@code ASIOSTInt32MSB24} — 24 significant bits right-justified in a big-endian word. */
    private static final int INT32_MSB24 = 11;
    /** {@code ASIOSTInt16LSB}. */
    private static final int INT16_LSB = 16;
    /** {@code ASIOSTInt24LSB} — packed 3-byte, the common USB-interface format. */
    private static final int INT24_LSB = 17;
    /** {@code ASIOSTInt32LSB}. */
    private static final int INT32_LSB = 18;
    /** {@code ASIOSTFloat32LSB} — story 311's original bulk-copy fast path. */
    private static final int FLOAT32_LSB = 19;
    /** {@code ASIOSTFloat64LSB} — the widest container the SDK defines. */
    private static final int FLOAT64_LSB = 20;
    /** {@code ASIOSTInt32LSB16} — 16 significant bits right-justified in a little-endian word. */
    private static final int INT32_LSB16 = 24;
    /** {@code ASIOSTDSDInt8LSB1} — out of scope by the story's Non-Goals. */
    private static final int DSD_INT8_LSB1 = 32;
    /** The native shim's "the driver refused to report a sample type". */
    private static final int SAMPLE_TYPE_UNKNOWN = -1;

    /** Full scale of a packed 24-bit sample: {@code 2^23}. */
    private static final int INT24_FULL_SCALE = 8_388_608;
    /** Bytes in a packed 24-bit sample. */
    private static final int INT24_BYTES = 3;

    private static final int HALVES = 2;
    private static final long AWAIT_SECONDS = 10;
    private static final float SENTINEL = 12_345f;
    /**
     * Ceiling on the emulated render-pump loop. A gate that never closes must
     * fail an assertion, not hang the suite.
     */
    private static final int PUMP_LOOP_LIMIT = 16;

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

    /**
     * A wake the callback never delivers loses no block: a block written into
     * the capture ring while the drain is parked on it empty, with no
     * {@code bufferSwitch} and so no unpark, is published by the drain's own
     * timed park (story 338, ruling E272). The bound is the class's
     * {@code AWAIT_SECONDS}, far above that park's {@code DRAIN_PARK_NANOS}.
     */
    @Test
    void aBlockThatReachesTheCaptureRingWithNoWakeIsStillPublished() throws Exception {
        buildShim(FLOAT32_LSB);
        CapturingSubscriber subscriber = subscribe();
        AudioBlockRing captureRing = shimField("inputRing", AudioBlockRing.class);
        float[] block = new float[channels * frames];
        Arrays.fill(block, 0.5f);

        assertThat(awaitParkedInItsDrainLoop(shim.drainThread()))
                .as("fixture: the drain thread is parked on the empty capture ring")
                .isTrue();
        assertThat(captureRing.write(block, block.length)).as("fixture: the ring took the block").isTrue();

        assertThat(subscriber.await(1))
                .as("the drain published the block it was never woken for, within %d s", AWAIT_SECONDS)
                .isTrue();
        assertThat(subscriber.blocks().getFirst().samples()).containsOnly(0.5f);
    }

    /**
     * Waits, bounded, until {@code drain} is parked in its loop's park, the
     * only park whose blocker is the shim.
     */
    private boolean awaitParkedInItsDrainLoop(Thread drain) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (System.nanoTime() - deadline < 0L) {
            Thread.State state = drain.getState();
            boolean parked = state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
            if (parked && LockSupport.getBlocker(drain) == shim) {
                return true;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        return false;
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
    // The upcall guard (story 337, rulings E5, E15, E16)
    // ------------------------------------------------------------------

    /**
     * Ruling E15: a fault before the outputs were written must not leave the
     * half holding the previous cycle's samples — the driver would replay
     * them as a buzz. The guard silences the whole half, then counts. The
     * fault is injected by swapping one input view for a one-byte segment,
     * so the decode at the top of {@code bufferSwitch} throws.
     */
    @Test
    void aThrowingBufferSwitchSilencesTheOutputHalfAndCounts() throws Exception {
        buildShim(FLOAT32_LSB);
        writeDriverBuffer(driverOutputs[0][0], 9f, 9f, 9f, 9f);
        writeDriverBuffer(driverOutputs[0][1], 9f, 9f, 9f, 9f);
        writeDriverBuffer(driverOutputs[1][0], 7f, 7f, 7f, 7f);
        shimArray("inputBuffers")[0][0] = arena.allocate(1);

        shim.bufferSwitchUpcall(0, 1);

        assertThat(readDriverBuffer(driverOutputs[0][0], frames))
                .as("the half the faulted cycle owned is silence, not the previous cycle")
                .containsOnly(0f);
        assertThat(readDriverBuffer(driverOutputs[0][1], frames)).containsOnly(0f);
        assertThat(readDriverBuffer(driverOutputs[1][0], frames))
                .as("the other half is not touched")
                .containsOnly(7f);
        assertThat(shim.upcallFaults()).isEqualTo(1L);
        assertThat(shim.lastUpcallFault()).get().isInstanceOf(IndexOutOfBoundsException.class);
        assertThat(shim.deviceCallbackPeriods()).isEqualTo(1L);
    }

    /** Ruling E5: every upcall is one device-callback period; a healthy one counts no fault. */
    @Test
    void aHealthyUpcallBeatsTheDeviceHeartbeatAndCountsNoFault() {
        buildShim(FLOAT32_LSB);

        shim.bufferSwitchUpcall(0, 1);
        shim.bufferSwitchUpcall(1, 1);
        shim.bufferSwitchUpcall(7, 1);

        assertThat(shim.deviceCallbackPeriods())
                .as("counted at entry, whatever the half index")
                .isEqualTo(3L);
        assertThat(shim.upcallFaults()).isZero();
        assertThat(shim.lastUpcallFault()).isEmpty();
    }

    /**
     * Ruling E16: a block the {@code asio-input-drain} thread cannot publish
     * is counted on that thread, and the drain keeps draining.
     */
    @Test
    void aPublishFailureOnTheDrainIsCounted() throws Exception {
        IllegalStateException publishFailure = new IllegalStateException("subscriber pipeline failed");
        support.close();
        support = new AudioBackendSupport(new SubmissionPublisher<>() {
            @Override
            public int offer(AudioBlock item,
                             java.util.function.BiPredicate<Flow.Subscriber<? super AudioBlock>,
                                     ? super AudioBlock> onDrop) {
                throw publishFailure;
            }
        }, new SubmissionPublisher<>());
        support.markOpen(format, frames);
        buildShim(FLOAT32_LSB);

        shim.bufferSwitch(0, 1);
        shim.bufferSwitch(1, 1);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (shim.publishDrops() < 2 && System.nanoTime() - deadline < 0L) {
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
        assertThat(shim.publishDrops()).as("each lost block counted, the drain still running").isEqualTo(2L);
        assertThat(shim.lastPublishFault()).containsSame(publishFailure);
        assertThat(shim.upcallFaults()).as("the driver thread never saw the drain's fault").isZero();
    }

    /**
     * Story 337, ruling E90: the report a backend's ledger reads from a live
     * shim names each count by its category — upcall faults are
     * {@code audio.callback}, publish drops are lost input blocks — with its
     * last fault, and the heartbeat always present. Two lost blocks and one
     * upcall fault, so a swap of the two counts cannot pass.
     */
    @Test
    void countersReportTheShimsFaultsDropsAndHeartbeat() throws Exception {
        IllegalStateException publishFailure = new IllegalStateException("subscriber pipeline failed");
        support.close();
        support = new AudioBackendSupport(new SubmissionPublisher<>() {
            @Override
            public int offer(AudioBlock item,
                             java.util.function.BiPredicate<Flow.Subscriber<? super AudioBlock>,
                                     ? super AudioBlock> onDrop) {
                throw publishFailure;
            }
        }, new SubmissionPublisher<>());
        support.markOpen(format, frames);
        buildShim(FLOAT32_LSB);

        shim.bufferSwitchUpcall(0, 1);
        shim.bufferSwitchUpcall(1, 1);
        shimArray("inputBuffers")[0][0] = arena.allocate(1);
        shim.bufferSwitchUpcall(0, 1);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (shim.publishDrops() < 2 && System.nanoTime() - deadline < 0L) {
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }

        BackendThreadCounters counters = shim.counters();

        assertThat(counters.callbackFaults()).as("upcall faults are audio.callback").isEqualTo(1L);
        assertThat(counters.inputBlocksLost()).as("publish drops are lost input blocks").isEqualTo(2L);
        assertThat(counters.inputHalts()).isZero();
        assertThat(counters.deviceNotificationFaults()).isZero();
        assertThat(counters.deviceCallbackPeriods()).as("every upcall is one period").hasValue(3L);
        assertThat(counters.lastCallbackFault()).get().isInstanceOf(IndexOutOfBoundsException.class);
        assertThat(counters.lastInputFault()).containsSame(publishFailure);
        assertThat(counters.lastDeviceNotificationFault()).isEmpty();
    }

    /** Rulings E4 and E5: a backend with no stream has nothing counted and a heartbeat at zero. */
    @Test
    void anAsioBackendWithNoStreamReportsZeroCountsAndAHeartbeatOfZero() {
        BackendThreadCounters counters = new AsioBackend().threadCounters();

        assertThat(counters.callbackFaults()).isZero();
        assertThat(counters.inputBlocksLost()).isZero();
        assertThat(counters.deviceNotificationFaults()).isZero();
        assertThat(counters.deviceCallbackPeriods()).hasValue(0L);
    }

    private MemorySegment[][] shimArray(String field) throws ReflectiveOperationException {
        return shimField(field, MemorySegment[][].class);
    }

    private <T> T shimField(String field, Class<T> type) throws ReflectiveOperationException {
        java.lang.reflect.Field declared = AsioBufferSwitchShim.class.getDeclaredField(field);
        declared.setAccessible(true);
        return type.cast(declared.get(shim));
    }

    // ------------------------------------------------------------------
    // Callback-consumption signal (story 316 review)
    // ------------------------------------------------------------------

    @Test
    void renderedBlocksConsumedStartsAtZero() {
        buildShim(FLOAT32_LSB);

        assertThat(shim.renderedBlocksConsumed()).isZero();
    }

    @Test
    void aCallbackThatEmitsSilenceDoesNotCountAsConsumingARenderedBlock() {
        buildShim(FLOAT32_LSB);

        shim.bufferSwitch(0, 1);
        shim.bufferSwitch(1, 1);

        assertThat(shim.renderedBlocksConsumed())
                .as("the silence path must not masquerade as drained output")
                .isZero();
    }

    @Test
    void aCallbackThatDrainsAWrittenBlockIncrementsRenderedBlocksConsumed() {
        buildShim(FLOAT32_LSB);
        shim.write(new AudioBlock(format.sampleRate(), channels, frames, new float[] {
                1f, 2f, 1f, 2f, 1f, 2f, 1f, 2f}));

        shim.bufferSwitch(0, 1);

        assertThat(shim.renderedBlocksConsumed()).isEqualTo(1L);
    }

    @Test
    void renderedBlocksConsumedHoldsWhenTheNextCallbackFindsNothingNew() {
        buildShim(FLOAT32_LSB);
        shim.write(new AudioBlock(format.sampleRate(), channels, frames, new float[] {
                1f, 2f, 1f, 2f, 1f, 2f, 1f, 2f}));

        shim.bufferSwitch(0, 1);
        shim.bufferSwitch(1, 1);

        assertThat(shim.renderedBlocksConsumed())
                .as("a second callback with no new write emitted silence, not output")
                .isEqualTo(1L);
    }

    // ------------------------------------------------------------------
    // Observed dropouts: output starvation and capture-ring overflow
    // ------------------------------------------------------------------

    @Test
    void anEmptyRingAfterTheFirstPlayedBlockCountsOneStarvedPeriod() {
        buildShim(FLOAT32_LSB);
        writeRenderedBlock();

        shim.bufferSwitch(0, 1);
        shim.bufferSwitch(1, 1);

        assertThat(shim.outputStarvedPeriods())
                .as("the second callback found nothing rendered and played silence")
                .isEqualTo(1L);
    }

    @Test
    void aCallbackThatPlaysARenderedBlockIsNotStarved() {
        buildShim(FLOAT32_LSB);
        writeRenderedBlock();
        shim.bufferSwitch(0, 1);
        writeRenderedBlock();

        shim.bufferSwitch(1, 1);

        assertThat(shim.outputStarvedPeriods()).isZero();
    }

    @Test
    void emptyCallbacksBeforeTheFirstRenderedBlockAreStartUpNotStarvation() {
        buildShim(FLOAT32_LSB);

        shim.bufferSwitch(0, 1);
        shim.bufferSwitch(1, 1);

        assertThat(shim.outputStarvedPeriods())
                .as("ASIOStart precedes the pump's first block, so these periods are start-up")
                .isZero();
    }

    /**
     * The early returns (a bad half, a quiesced bridge) never count as
     * starvation although the device heartbeat still advances, which is why
     * the starved count cannot be derived from the heartbeat.
     */
    @Test
    void earlyReturnsCountNoStarvationWhileTheHeartbeatAdvances() {
        buildShim(FLOAT32_LSB);
        writeRenderedBlock();
        shim.bufferSwitchUpcall(0, 1);

        shim.bufferSwitchUpcall(-1, 1);
        shim.bufferSwitchUpcall(2, 1);
        shim.stopStreaming();
        shim.bufferSwitchUpcall(1, 1);

        assertThat(shim.deviceCallbackPeriods()).isEqualTo(4L);
        assertThat(shim.outputStarvedPeriods()).isZero();
    }

    /**
     * A drain thread held inside its publish leaves the capture ring to fill;
     * each callback past its capacity loses its block and counts it.
     */
    @Test
    void aFullCaptureRingCountsEachBlockItLoses() throws Exception {
        CountDownLatch drainBlocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        support.close();
        support = new AudioBackendSupport(holdingPublisher(drainBlocked, release), new SubmissionPublisher<>());
        support.markOpen(format, frames);
        buildShim(FLOAT32_LSB);
        try {
            shim.bufferSwitch(0, 1);
            assertThat(drainBlocked.await(AWAIT_SECONDS, TimeUnit.SECONDS))
                    .as("the drain thread took the first block and is held in its publish")
                    .isTrue();
            for (int i = 0; i < 32; i++) {
                shim.bufferSwitch(i & 1, 1);
            }
            assertThat(shim.inputRingOverflows())
                    .as("32 blocks fit the empty 32-slot ring")
                    .isZero();

            shim.bufferSwitch(0, 1);
            shim.bufferSwitch(1, 1);
            shim.bufferSwitch(0, 1);

            assertThat(shim.inputRingOverflows()).isEqualTo(3L);
        } finally {
            release.countDown();
        }
    }

    /**
     * A period whose captured block the full capture ring refused, and whose
     * output half then faults, lost that block once: as a ring overflow,
     * never also as a failed callback's (story 338, ruling E211).
     */
    @Test
    void aBlockTheFullRingRefusedIsNotCountedAgainWhenItsOutputHalfFaults() throws Exception {
        CountDownLatch drainBlocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        support.close();
        support = new AudioBackendSupport(holdingPublisher(drainBlocked, release), new SubmissionPublisher<>());
        support.markOpen(format, frames);
        buildShim(FLOAT32_LSB);
        try {
            shim.bufferSwitch(0, 1);
            assertThat(drainBlocked.await(AWAIT_SECONDS, TimeUnit.SECONDS))
                    .as("the drain thread took the first block and is held in its publish")
                    .isTrue();
            for (int i = 0; i < 32; i++) {
                shim.bufferSwitch(i & 1, 1);
            }
            assertThat(shim.inputRingOverflows()).as("fixture: the 32-slot ring is full").isZero();
            shimArray("outputBuffers")[0][0] = arena.allocate(1);

            shim.bufferSwitchUpcall(0, 1);

            assertThat(shim.inputRingOverflows()).isEqualTo(1L);
            assertThat(shim.upcallFaults()).isEqualTo(1L);
            assertThat(shim.failedPeriodInputBlocks()).as("the refused block is the ring's overflow alone").isZero();
        } finally {
            release.countDown();
        }
    }

    /**
     * Every period owes its own captured block: a fault while copying the
     * input of a period after a healthy one, which took its block, loses the
     * faulting period's (story 338, ruling E211).
     */
    @Test
    void aFaultInTheInputHalfAfterAHealthyPeriodCountsThatPeriodsBlock() throws Exception {
        buildShim(FLOAT32_LSB);
        shimArray("inputBuffers")[1][0] = arena.allocate(1);

        shim.bufferSwitchUpcall(0, 1);
        assertThat(shim.upcallFaults()).as("fixture: the first period was healthy").isZero();
        shim.bufferSwitchUpcall(1, 1);

        assertThat(shim.upcallFaults()).isEqualTo(1L);
        assertThat(shim.failedPeriodInputBlocks()).isEqualTo(1L);
    }

    /** A publisher whose every offer counts {@code drainBlocked} down and then waits for {@code release}. */
    private static SubmissionPublisher<AudioBlock> holdingPublisher(CountDownLatch drainBlocked,
                                                                    CountDownLatch release) {
        return new SubmissionPublisher<>() {
            @Override
            public int offer(AudioBlock item,
                             java.util.function.BiPredicate<Flow.Subscriber<? super AudioBlock>,
                                     ? super AudioBlock> onDrop) {
                drainBlocked.countDown();
                try {
                    release.await(AWAIT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return super.offer(item, onDrop);
            }
        };
    }

    /**
     * A drain whose close's join gave up (here: an interrupted closer) still
     * holds its stream's blocks: the one it was publishing and one left in the
     * ring. Neither may reach the next stream (story 338, ruling E215), and an
     * offer its closed publisher refuses is teardown, not a lost block. The
     * next stream's publisher delivers on the offering thread, so a block
     * offered into it has been received by the time the drain has ended.
     */
    @Test
    void aDrainThatOutlivesItsCloseCannotPublishIntoTheNextStream() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        support.close();
        support = new AudioBackendSupport(new GatedPublisher(entered, release), new SubmissionPublisher<>());
        support.markOpen(format, frames);
        buildShim(FLOAT32_LSB);
        Thread drain = shim.drainThread();
        try {
            shim.bufferSwitch(0, 1);
            shim.bufferSwitch(1, 1);
            assertThat(entered.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

            Thread.currentThread().interrupt();
            shim.close();
            assertThat(Thread.interrupted()).as("the close's join gave up on the interrupt").isTrue();
            assertThat(drain.isAlive()).as("the drain outlived its close").isTrue();

            support.close();
            support.markOpen(format, frames);
            replaceSupportPublisher(new SubmissionPublisher<>(Runnable::run, Flow.defaultBufferSize()));
            CapturingSubscriber nextStream = subscribe();
            release.countDown();
            drain.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));

            assertThat(drain.isAlive()).isFalse();
            assertThat(nextStream.blocks()).as("no block of the closed stream reached the next one").isEmpty();
            assertThat(shim.publishDrops()).as("the closed publisher's refusal is teardown").isZero();
        } finally {
            release.countDown();
        }
    }

    /**
     * A fault while the period's input is copied loses that period's captured
     * block: one block lost, beside the one callback fault (story 338, ruling
     * E211). A healthy period after it counts nothing more.
     */
    @Test
    void aFaultWhileCopyingThePeriodsInputCountsItsBlockLostAndOneCallbackFault() throws Exception {
        buildShim(FLOAT32_LSB);
        shimArray("inputBuffers")[0][0] = arena.allocate(1);

        shim.bufferSwitchUpcall(0, 1);

        assertThat(shim.failedPeriodInputBlocks()).isEqualTo(1L);
        assertThat(shim.upcallFaults()).isEqualTo(1L);
        assertThat(shim.counters().callbackFaults()).isEqualTo(1L);
        assertThat(shim.counters().inputBlocksLost())
                .as("a failed callback's block is not one the drain lost")
                .isZero();

        shim.bufferSwitchUpcall(1, 1);

        assertThat(shim.failedPeriodInputBlocks()).isEqualTo(1L);
    }

    /**
     * A fault in the output half comes after the period's input reached the
     * capture ring, so the take lost nothing: no block lost, one callback
     * fault, and the period's block is published (story 338, ruling E211).
     */
    @Test
    void aFaultInTheOutputHalfCountsNoBlockLost() throws Exception {
        buildShim(FLOAT32_LSB);
        CapturingSubscriber subscriber = subscribe();
        shimArray("outputBuffers")[0][0] = arena.allocate(1);

        shim.bufferSwitchUpcall(0, 1);

        assertThat(shim.failedPeriodInputBlocks()).isZero();
        assertThat(shim.upcallFaults()).isEqualTo(1L);
        flushAndPublishSentinel();
        assertThat(subscriber.await(2)).isTrue();
        assertThat(subscriber.blocks()).as("the period's block, then the sentinel").hasSize(2);
    }

    /**
     * A stream with no bound input channel owes no captured block, so its
     * failed callback loses none (story 338, ruling E211). The fault is
     * forced in the input half by planting a converter and a one-byte view
     * into the shim's arrays.
     */
    @Test
    void anOutputOnlyStreamsFaultCountsNoBlockLost() throws Exception {
        List<AsioStreamingShim.BufferInfo> outputsOnly = new ArrayList<>();
        for (int channel = 0; channel < channels; channel++) {
            outputsOnly.add(new AsioStreamingShim.BufferInfo(channel, false, FLOAT32_LSB,
                    driverOutputs[0][channel].address(), driverOutputs[1][channel].address()));
        }
        shim = new AsioBufferSwitchShim(support, format, frames, outputsOnly);
        assertThat(shim.boundInputChannels()).isZero();
        java.lang.reflect.Field typesField = AsioBufferSwitchShim.class.getDeclaredField("inputTypes");
        typesField.setAccessible(true);
        ((AsioSampleType[]) typesField.get(shim))[0] = AsioSampleType.FLOAT32_LSB;
        shimArray("inputBuffers")[0][0] = arena.allocate(1);

        shim.bufferSwitchUpcall(0, 1);

        assertThat(shim.upcallFaults()).isEqualTo(1L);
        assertThat(shim.failedPeriodInputBlocks()).isZero();
    }

    private void replaceSupportPublisher(SubmissionPublisher<AudioBlock> publisher)
            throws ReflectiveOperationException {
        java.lang.reflect.Field declared = AudioBackendSupport.class.getDeclaredField("publisher");
        declared.setAccessible(true);
        declared.set(support, publisher);
    }

    /**
     * Holds the first offer until released, uninterruptibly, then offers;
     * every later offer passes straight through.
     */
    private static final class GatedPublisher extends SubmissionPublisher<AudioBlock> {

        private final CountDownLatch entered;
        private final CountDownLatch release;

        GatedPublisher(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        public int offer(AudioBlock item,
                         java.util.function.BiPredicate<Flow.Subscriber<? super AudioBlock>,
                                 ? super AudioBlock> onDrop) {
            entered.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return super.offer(item, onDrop);
        }
    }

    // ------------------------------------------------------------------
    // Observed dropouts: rendered blocks discarded unplayed
    // ------------------------------------------------------------------

    /** Once playing, every block a full four-slot output ring refuses counts. */
    @Test
    void aFullOutputRingCountsEachRefusedBlockOnceTheBridgeHasPlayed() {
        buildShim(FLOAT32_LSB);
        writeRenderedBlock();
        shim.bufferSwitch(0, 1);
        for (int i = 0; i < 4; i++) {
            writeRenderedBlock();
        }
        assertThat(shim.outputDiscardedBlocks()).as("four blocks fit the empty ring").isZero();

        writeRenderedBlock();
        writeRenderedBlock();

        assertThat(shim.outputDiscardedBlocks()).isEqualTo(2L);
    }

    /** A late callback plays the newest queued block; the older ones it consumed count. */
    @Test
    void olderBlocksACallbackSkipsToPlayTheNewestAreCounted() {
        buildShim(FLOAT32_LSB);
        writeRenderedBlock();
        shim.bufferSwitch(0, 1);
        writeRenderedBlock();
        writeRenderedBlock();
        writeRenderedBlock();

        shim.bufferSwitch(1, 1);

        assertThat(shim.outputDiscardedBlocks())
                .as("three queued, the newest played, two discarded unheard")
                .isEqualTo(2L);
        assertThat(shim.renderedBlocksConsumed()).isEqualTo(2L);
    }

    /**
     * The pump runs ahead of {@code ASIOStart}: before the first played block
     * neither the refused blocks nor the ones the first callback skips are a
     * gap.
     */
    @Test
    void blocksQueuedBeforeTheFirstPlayedBlockAreStartUpNotDiscards() {
        buildShim(FLOAT32_LSB);
        for (int i = 0; i < 6; i++) {
            writeRenderedBlock();
        }
        assertThat(shim.outputDiscardedBlocks()).as("two refused before priming").isZero();

        shim.bufferSwitch(0, 1);

        assertThat(shim.outputDiscardedBlocks()).as("three skipped by the priming callback").isZero();
    }

    @Test
    void aQuiescedBridgeCountsNoDiscardedBlocks() {
        buildShim(FLOAT32_LSB);
        writeRenderedBlock();
        shim.bufferSwitch(0, 1);
        shim.stopStreaming();

        for (int i = 0; i < 6; i++) {
            writeRenderedBlock();
        }
        shim.bufferSwitch(1, 1);

        assertThat(shim.outputDiscardedBlocks()).isZero();
    }

    /** Negative control: one block per callback, the paced steady state, discards nothing. */
    @Test
    void aStreamThatPlaysEveryBlockItRendersDiscardsNothing() {
        buildShim(FLOAT32_LSB);
        for (int i = 0; i < 16; i++) {
            writeRenderedBlock();
            shim.bufferSwitch(i & 1, 1);
        }

        assertThat(shim.outputDiscardedBlocks()).isZero();
        assertThat(shim.renderedBlocksConsumed()).isEqualTo(16L);
    }

    private void writeRenderedBlock() {
        shim.write(new AudioBlock(format.sampleRate(), channels, frames, new float[] {
                1f, 2f, 1f, 2f, 1f, 2f, 1f, 2f}));
    }

    // ------------------------------------------------------------------
    // Render-pump pacing gate (story 316 review)
    // ------------------------------------------------------------------

    @Test
    void theGateIsOpenOnAFreshlyOpenedBridgeSoTheFirstBlockIsRenderedAtOnce() {
        buildShim(FLOAT32_LSB);

        assertThat(shim.outputRingDrained())
                .as("nothing has been written, so the driver owes no consumption")
                .isTrue();
    }

    @Test
    void oneWrittenBlockClosesTheGateUntilACallbackConsumesIt() {
        buildShim(FLOAT32_LSB);

        shim.write(renderedBlock(1f));

        assertThat(shim.outputRingDrained())
                .as("a queued block the driver has not taken must hold the pump")
                .isFalse();

        shim.bufferSwitch(0, 1);

        assertThat(shim.outputRingDrained())
                .as("the callback consumed it, so the next block may be rendered")
                .isTrue();
    }

    /**
     * The headline of the story 316 gate fix: the pump is paced by
     * <em>consumption</em>, not by free slots.
     *
     * <p>Against the previous {@code outputRing.hasSpace()} gate this loop
     * admitted four blocks per callback — the ring's full slot count — and the
     * next {@code bufferSwitch} played the newest and discarded the other
     * three, so playback and the transport advanced at roughly four times
     * device time. One block per callback, with the consumption counter
     * matching the number of callbacks, is the fix.</p>
     */
    @Test
    void thePumpLoopAdmitsExactlyOneBlockPerCallbackInsteadOfRefillingTheRing() {
        buildShim(FLOAT32_LSB);

        int beforeAnyCallback = renderWhileTheGateIsOpen();
        shim.bufferSwitch(0, 1);
        int betweenCallbacks = renderWhileTheGateIsOpen();
        shim.bufferSwitch(1, 1);

        assertThat(beforeAnyCallback)
                .as("the pump may run one block ahead of the driver, not four")
                .isEqualTo(1);
        assertThat(betweenCallbacks)
                .as("one consumed block releases the pump for exactly one more")
                .isEqualTo(1);
        assertThat(shim.renderedBlocksConsumed())
                .as("both rendered blocks reached the driver; none was discarded")
                .isEqualTo(2L);
    }

    // ------------------------------------------------------------------
    // Degradation
    // ------------------------------------------------------------------

    /**
     * Story 312: a type this DAW cannot convert now fails the open. Silencing
     * the channel (story 311's behaviour) hid a driver the DAW could not
     * actually drive, and guessing at the layout would hand the driver the
     * engine's float bytes reinterpreted as integers — loud enough to damage
     * speakers.
     */
    @Test
    void unsupportedSampleTypeRejectsTheOpenInsteadOfSilencingTheChannel() {
        List<AsioStreamingShim.BufferInfo> dsd =
                bufferInfos(DSD_INT8_LSB1, FLOAT32_LSB);

        assertThatThrownBy(() ->
                new AsioBufferSwitchShim(support, format, frames, dsd))
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("input 0=" + DSD_INT8_LSB1)
                .hasMessageContaining("input 1=" + DSD_INT8_LSB1)
                .hasMessageContaining("Int24");

        List<AsioStreamingShim.BufferInfo> unknown =
                bufferInfos(FLOAT32_LSB, SAMPLE_TYPE_UNKNOWN);

        assertThatThrownBy(() ->
                new AsioBufferSwitchShim(support, format, frames, unknown))
                .as("a driver that reports no type at all must not be guessed at")
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("output 0=" + SAMPLE_TYPE_UNKNOWN)
                .hasMessageContaining("output 1=" + SAMPLE_TYPE_UNKNOWN);
    }

    /**
     * A descriptor with no usable driver buffer is never decoded or encoded,
     * so its reported type is irrelevant and must not fail an otherwise
     * workable open.
     */
    @Test
    void anUnsupportedTypeOnAChannelWithNoDriverBufferDoesNotFailTheOpen() {
        List<AsioStreamingShim.BufferInfo> infos = List.of(
                new AsioStreamingShim.BufferInfo(0, true, FLOAT32_LSB,
                        driverInputs[0][0].address(), driverInputs[1][0].address()),
                new AsioStreamingShim.BufferInfo(1, true, DSD_INT8_LSB1, 0L, 0L),
                new AsioStreamingShim.BufferInfo(0, false, FLOAT32_LSB,
                        driverOutputs[0][0].address(), driverOutputs[1][0].address()));

        shim = new AsioBufferSwitchShim(support, format, frames, infos);

        assertThat(shim.isStreaming()).isTrue();
    }

    // ------------------------------------------------------------------
    // Sample-format conversion (story 312)
    // ------------------------------------------------------------------

    /**
     * The story's integration case: a driver reporting packed 24-bit — the
     * format a multi-channel USB interface most often exposes — must reach the
     * engine as correctly normalised float32, and the engine's float block must
     * reach the driver as correctly packed 24-bit.
     */
    @Test
    void int24DriverBuffersAreDecodedToFloatAndTheRenderedBlockIsPackedBack()
            throws Exception {
        fixture(2, 4, INT24_BYTES, INT24_BYTES);
        buildShim(INT24_LSB);
        // Exact powers of two, so the decode is exact and a tolerance would
        // only hide an error.
        writePacked24(driverInputs[0][0],
                INT24_FULL_SCALE / 2, INT24_FULL_SCALE / 4,
                -INT24_FULL_SCALE / 2, -INT24_FULL_SCALE);
        writePacked24(driverInputs[0][1],
                -INT24_FULL_SCALE / 4, 0, INT24_FULL_SCALE / 8, -INT24_FULL_SCALE / 8);
        CapturingSubscriber subscriber = subscribe();
        shim.write(new AudioBlock(format.sampleRate(), channels, frames, new float[] {
                0.5f, -0.5f,
                0.25f, -0.25f,
                0f, 1f,
                -1f, 0.125f}));

        shim.bufferSwitch(0, 1);
        flushAndPublishSentinel();

        assertThat(subscriber.await(2)).isTrue();
        assertThat(subscriber.blocks().get(0).samples())
                .as("packed 24-bit must normalise to [-1, 1], not arrive as garbage")
                .containsExactly(
                        0.5f, -0.25f,
                        0.25f, 0f,
                        -0.5f, 0.125f,
                        -1f, -0.125f);
        // round(x * (2^23 - 1)): +0.5 -> 4194304, -0.5 -> -4194303,
        // +1 -> 8388607, -1 -> -8388607.
        assertThat(readPacked24(driverOutputs[0][0], frames))
                .containsExactly(4_194_304, 2_097_152, 0, -8_388_607);
        assertThat(readPacked24(driverOutputs[0][1], frames))
                .containsExactly(-4_194_303, -2_097_152, 8_388_607, 1_048_576);
    }

    /**
     * The converter is resolved per channel <em>and</em> per direction, so a
     * driver that captures 24-bit and plays 32-bit — or any other asymmetric
     * pairing — must be handled without a single global format assumption.
     */
    @Test
    void inputAndOutputConvertersAreIndependentPerDirection() throws Exception {
        fixture(2, 4, INT24_BYTES, Integer.BYTES);
        buildShim(INT24_LSB, INT32_LSB);
        writePacked24(driverInputs[0][0],
                INT24_FULL_SCALE / 2, 0, -INT24_FULL_SCALE / 2, -INT24_FULL_SCALE);
        writePacked24(driverInputs[0][1], 0, 0, 0, 0);
        CapturingSubscriber subscriber = subscribe();
        shim.write(new AudioBlock(format.sampleRate(), channels, frames, new float[] {
                0.5f, -0.5f,
                0.5f, -0.5f,
                0.5f, -0.5f,
                0.5f, -0.5f}));

        shim.bufferSwitch(0, 1);
        flushAndPublishSentinel();

        assertThat(subscriber.await(2)).isTrue();
        assertThat(subscriber.blocks().get(0).samples()).containsExactly(
                0.5f, 0f,
                0f, 0f,
                -0.5f, 0f,
                -1f, 0f);
        // round(0.5 * (2^31 - 1)) = 1073741824; round(-0.5 * (2^31 - 1)) = -1073741823.
        assertThat(readRawInts(driverOutputs[0][0], frames))
                .containsOnly(1_073_741_824);
        assertThat(readRawInts(driverOutputs[0][1], frames))
                .containsOnly(-1_073_741_823);
    }

    /**
     * The converter and the driver-buffer stride are resolved per
     * <em>channel</em>, not once per direction. The ASIO SDK carries
     * {@code ASIOSampleType} in each channel's own {@code ASIOChannelInfo},
     * and a host that samples one descriptor per direction and assumes the
     * rest match (as PortAudio's ASIO host API does) mis-decodes every channel
     * that differs. Six formats and three container sizes per direction in one
     * shim, so a per-direction shortcut cannot pass.
     *
     * <p>This is also the only place an MSB variant, a 2-byte container and a
     * right-justified variant are driven through {@code bufferSwitch} rather
     * than exercised directly in {@code AsioSampleTypeTest}.</p>
     */
    @Test
    void everyChannelGetsItsOwnConverterAndItsOwnDriverBufferStride() throws Exception {
        fixture(3, 4, new int[] {2, 4, 8}, new int[] {3, 4, 4});
        buildShim(new int[] {INT16_LSB, INT32_MSB24, FLOAT64_LSB},
                new int[] {INT24_MSB, FLOAT32_LSB, INT32_LSB16});
        // Every raw value is a power of two, so no decode needs a tolerance.
        writeShorts(driverInputs[0][0], 16_384, -8_192, 4_096, -32_768);
        writeRawIntsBigEndian(driverInputs[0][1], 4_194_304, -8_388_608, 2_097_152, 0);
        writeDoubles(driverInputs[0][2], 0.75, -0.375, 0.0, 0.5);
        CapturingSubscriber subscriber = subscribe();
        shim.write(new AudioBlock(format.sampleRate(), 3, 4, new float[] {
                0.5f, 0.1f, 1f,
                -0.5f, -0.2f, -1f,
                1f, 0.3f, 0.5f,
                -1f, -0.4f, 0f}));

        shim.bufferSwitch(0, 1);
        flushAndPublishSentinel();

        assertThat(subscriber.await(2)).isTrue();
        assertThat(subscriber.blocks().get(0).samples())
                .as("each capture channel must be decoded with its own ASIOSampleType "
                        + "and read at its own stride")
                .containsExactly(
                        0.5f, 0.5f, 0.75f,
                        -0.25f, -1f, -0.375f,
                        0.125f, 0.25f, 0f,
                        -1f, 0f, 0.5f);

        // round(x * (2^23 - 1)) packed three bytes, most significant first.
        assertThat(readPacked24BigEndian(driverOutputs[0][0], frames))
                .as("Int24MSB output must be packed big-endian at a three-byte stride")
                .containsExactly(4_194_304, -4_194_303, 8_388_607, -8_388_607);
        assertThat(readDriverBuffer(driverOutputs[0][1], frames))
                .as("Float32LSB output must stay bit-exact alongside two integer channels")
                .containsExactly(0.1f, -0.2f, 0.3f, -0.4f);
        // round(x * (2^15 - 1)), sign-extended, in the low 16 bits of the word.
        assertThat(readRawInts(driverOutputs[0][2], frames))
                .as("Int32LSB16 output must be right-justified, not left-justified")
                .containsExactly(32_767, -32_767, 16_384, 0);
    }

    /**
     * The discriminating regression guard for the per-channel view size.
     * Story 311 reinterpreted <em>every</em> driver buffer to
     * {@code bufferFrames * 4} bytes; an {@code ASIOSTFloat64LSB} channel needs
     * eight bytes per frame, so that view covered only the first half of the
     * buffer and every access from frame {@code frames / 2} onwards threw
     * {@link IndexOutOfBoundsException} — on the driver's real-time thread.
     */
    @Test
    void float64DriverBuffersAreConvertedAcrossTheWholeBufferNotJustItsFirstHalf()
            throws Exception {
        fixture(2, 4, Double.BYTES, Double.BYTES);
        buildShim(FLOAT64_LSB);
        writeDoubles(driverInputs[0][0], 0.1, 0.2, 0.3, 0.4);
        writeDoubles(driverInputs[0][1], -0.5, -0.6, -0.7, -0.8);
        CapturingSubscriber subscriber = subscribe();
        shim.write(new AudioBlock(format.sampleRate(), channels, frames, new float[] {
                0.25f, -0.75f,
                0.26f, -0.76f,
                0.27f, -0.77f,
                0.28f, -0.78f}));

        shim.bufferSwitch(0, 1);
        flushAndPublishSentinel();

        assertThat(subscriber.await(2)).isTrue();
        assertThat(subscriber.blocks().get(0).samples())
                .as("the second half of a Float64 buffer must be reachable")
                .containsExactly(
                        0.1f, -0.5f,
                        0.2f, -0.6f,
                        0.3f, -0.7f,
                        0.4f, -0.8f);
        // Widened from the float bus, so the stored doubles are exactly the
        // float values promoted — not the decimal literals' own doubles.
        assertThat(readDoubles(driverOutputs[0][0], frames)).containsExactly(
                (double) 0.25f, (double) 0.26f, (double) 0.27f, (double) 0.28f);
        assertThat(readDoubles(driverOutputs[0][1], frames)).containsExactly(
                (double) -0.75f, (double) -0.76f, (double) -0.77f, (double) -0.78f);
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
    // Teardown: freeing the upcall stub vs. retaining it (story 316 re-review)
    // ------------------------------------------------------------------

    /**
     * The arena {@link AsioBufferSwitchShim#close()} frees is the one that owns
     * the upcall stub, and the stub's own {@link MemorySegment#scope()} is the
     * observable proof of it: the scope is alive exactly while the stub is
     * still mapped, i.e. while a late driver callback could still land in it.
     */
    @Test
    void closeReleasesTheUpcallStubsArenaAndStopsTheDrainThread() {
        buildShim(FLOAT32_LSB);
        MemorySegment stub = shim.upcallStub();
        assertThat(stub.equals(MemorySegment.NULL))
                .as("a NULL stub would make the scope assertions meaningless")
                .isFalse();
        assertThat(stub.scope().isAlive()).isTrue();
        Thread drain = shim.drainThread();

        shim.close();

        assertThat(stub.scope().isAlive())
                .as("close() frees the arena: its caller uninstalled the callback first")
                .isFalse();
        assertThat(drain.isAlive()).isFalse();
    }

    /**
     * {@link AsioBufferSwitchShim#closeRetainingUpcallStub()} is
     * {@link AsioBufferSwitchShim#close()} minus exactly one step. It exists for
     * the teardown that could not CONFIRM
     * {@link AsioStreamingShim#uninstallBufferSwitchCallback()} — it answered
     * {@code false}, so the shim's registered buffer-switch callback may still
     * be the stub's address and unmapping it would turn the next
     * {@code bufferSwitch}, which reaches the stub through the shim's own
     * trampoline, into a jump into released memory.
     *
     * <p>The stub this test leaves mapped is never reclaimed — that permanent,
     * bounded leak <em>is</em> the behaviour under test, and the
     * {@code tearDown} {@code close()} is a no-op on an already-closed
     * bridge.</p>
     */
    @Test
    void retainingCloseQuiescesTheBridgeAndDrainThreadYetKeepsTheStubMapped() {
        buildShim(FLOAT32_LSB);
        MemorySegment stub = shim.upcallStub();
        assertThat(stub.equals(MemorySegment.NULL)).isFalse();
        Thread drain = shim.drainThread();
        writeDriverBuffer(driverOutputs[0][0], 7f, 7f, 7f, 7f);

        shim.closeRetainingUpcallStub();

        assertThat(shim.isStreaming())
                .as("no further callback may touch the driver's buffers")
                .isFalse();
        assertThat(drain.isAlive())
                .as("the retain variant still stops and joins the drain thread")
                .isFalse();
        assertThat(stub.scope().isAlive())
                .as("the driver may still hold this address, so it must stay mapped")
                .isTrue();
        shim.bufferSwitch(0, 1);
        assertThat(readDriverBuffer(driverOutputs[0][0], frames))
                .as("a callback arriving after the retaining close writes nothing")
                .containsOnly(7f);
    }

    /**
     * Idempotent, and the first ending wins. A {@code close()} that arrives
     * after the retaining one must not free the arena the retaining one
     * deliberately kept, or the crash it just prevented would simply happen
     * later.
     */
    @Test
    void retainingCloseIsIdempotentAndALaterCloseStillDoesNotFreeTheStub() {
        buildShim(FLOAT32_LSB);
        MemorySegment stub = shim.upcallStub();
        Thread drain = shim.drainThread();

        shim.closeRetainingUpcallStub();
        shim.closeRetainingUpcallStub();
        shim.close();

        assertThat(stub.scope().isAlive())
                .as("the retained stub outlives every later close")
                .isTrue();
        assertThat(drain.isAlive()).isFalse();
        assertThat(shim.isStreaming()).isFalse();
    }

    // ------------------------------------------------------------------
    // Fixture helpers
    // ------------------------------------------------------------------

    /** A distinct, non-zero value per (channel, frame) pair. */
    private static float cell(int channel, int frame) {
        return (channel + 1) * 100f + (frame + 1);
    }

    /** A full-geometry rendered block whose every sample is {@code value}. */
    private AudioBlock renderedBlock(float value) {
        float[] samples = new float[channels * frames];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = value;
        }
        return new AudioBlock(format.sampleRate(), channels, frames, samples);
    }

    /**
     * Emulates the engine render pump's inner loop: render and sink a block
     * for as long as {@link AsioBufferSwitchShim#outputRingDrained()} says the
     * driver has taken what it was already given. Bounded by
     * {@link #PUMP_LOOP_LIMIT} so a gate that never closes fails an assertion
     * instead of spinning forever.
     *
     * @return how many blocks the pump was allowed to render
     */
    private int renderWhileTheGateIsOpen() {
        int rendered = 0;
        while (shim.outputRingDrained() && rendered < PUMP_LOOP_LIMIT) {
            shim.write(renderedBlock(rendered + 1f));
            rendered++;
        }
        return rendered;
    }

    /**
     * (Re)builds the driver-buffer fixture and the backend support for a given
     * geometry, with float32-sized driver buffers. Called from {@code setUp}
     * with the default 2 &times; 4 shape.
     */
    private void fixture(int channelCount, int frameCount) {
        fixture(channelCount, frameCount, Float.BYTES, Float.BYTES);
    }

    /** One container size for every channel in each direction. */
    private void fixture(int channelCount, int frameCount,
                         int inputBytesPerSample, int outputBytesPerSample) {
        fixture(channelCount, frameCount,
                repeated(inputBytesPerSample, channelCount),
                repeated(outputBytesPerSample, channelCount));
    }

    /**
     * Story 312: the driver-side container is per <em>channel</em>, not per
     * direction — the ASIO SDK carries {@code ASIOSampleType} in each
     * channel's own {@code ASIOChannelInfo}. The halves are allocated with
     * byte alignment rather than {@code ValueLayout.JAVA_FLOAT}, which is also
     * what a reinterpreted driver address gives the shim: an aligned value
     * layout would throw on one.
     */
    private void fixture(int channelCount, int frameCount,
                         int[] inputBytesPerSample, int[] outputBytesPerSample) {
        if (support != null) {
            support.close();
        }
        this.channels = channelCount;
        this.frames = frameCount;
        this.format = new AudioFormat(48_000.0, channelCount, 32);
        this.driverInputs = allocateHalves(inputBytesPerSample);
        this.driverOutputs = allocateHalves(outputBytesPerSample);
        this.support = new AudioBackendSupport();
        support.markOpen(format, frameCount);
    }

    private void buildShim(int sampleType) {
        buildShim(sampleType, sampleType);
    }

    /** Story 312: separate input and output types, so a mixed-format driver can be stood in for. */
    private void buildShim(int inputSampleType, int outputSampleType) {
        buildShim(repeated(inputSampleType, channels),
                repeated(outputSampleType, channels));
    }

    /** Story 312: a distinct {@code ASIOSampleType} per channel per direction. */
    private void buildShim(int[] inputSampleTypes, int[] outputSampleTypes) {
        shim = new AsioBufferSwitchShim(support, format, frames,
                bufferInfos(inputSampleTypes, outputSampleTypes));
    }

    private List<AsioStreamingShim.BufferInfo> bufferInfos(int inputSampleType,
                                                           int outputSampleType) {
        return bufferInfos(repeated(inputSampleType, channels),
                repeated(outputSampleType, channels));
    }

    private List<AsioStreamingShim.BufferInfo> bufferInfos(int[] inputSampleTypes,
                                                           int[] outputSampleTypes) {
        List<AsioStreamingShim.BufferInfo> infos = new ArrayList<>();
        for (int channel = 0; channel < channels; channel++) {
            infos.add(new AsioStreamingShim.BufferInfo(channel, true,
                    inputSampleTypes[channel],
                    driverInputs[0][channel].address(),
                    driverInputs[1][channel].address()));
        }
        for (int channel = 0; channel < channels; channel++) {
            infos.add(new AsioStreamingShim.BufferInfo(channel, false,
                    outputSampleTypes[channel],
                    driverOutputs[0][channel].address(),
                    driverOutputs[1][channel].address()));
        }
        return List.copyOf(infos);
    }

    private MemorySegment[][] allocateHalves(int[] bytesPerSample) {
        MemorySegment[][] halves = new MemorySegment[HALVES][channels];
        for (int half = 0; half < HALVES; half++) {
            for (int channel = 0; channel < channels; channel++) {
                halves[half][channel] =
                        arena.allocate((long) frames * bytesPerSample[channel]);
            }
        }
        return halves;
    }

    private static int[] repeated(int value, int count) {
        int[] values = new int[count];
        java.util.Arrays.fill(values, value);
        return values;
    }

    private static void writePacked24(MemorySegment buffer, int... values) {
        for (int frame = 0; frame < values.length; frame++) {
            long offset = (long) frame * INT24_BYTES;
            buffer.set(ValueLayout.JAVA_BYTE, offset, (byte) values[frame]);
            buffer.set(ValueLayout.JAVA_BYTE, offset + 1, (byte) (values[frame] >> 8));
            buffer.set(ValueLayout.JAVA_BYTE, offset + 2, (byte) (values[frame] >> 16));
        }
    }

    private static int[] readPacked24(MemorySegment buffer, int frameCount) {
        int[] values = new int[frameCount];
        for (int frame = 0; frame < frameCount; frame++) {
            long offset = (long) frame * INT24_BYTES;
            int b0 = buffer.get(ValueLayout.JAVA_BYTE, offset) & 0xFF;
            int b1 = buffer.get(ValueLayout.JAVA_BYTE, offset + 1) & 0xFF;
            int b2 = buffer.get(ValueLayout.JAVA_BYTE, offset + 2) & 0xFF;
            values[frame] = ((b2 << 24) | (b1 << 16) | (b0 << 8)) >> 8;
        }
        return values;
    }

    private static int[] readPacked24BigEndian(MemorySegment buffer, int frameCount) {
        int[] values = new int[frameCount];
        for (int frame = 0; frame < frameCount; frame++) {
            long offset = (long) frame * INT24_BYTES;
            int b0 = buffer.get(ValueLayout.JAVA_BYTE, offset) & 0xFF;
            int b1 = buffer.get(ValueLayout.JAVA_BYTE, offset + 1) & 0xFF;
            int b2 = buffer.get(ValueLayout.JAVA_BYTE, offset + 2) & 0xFF;
            values[frame] = ((b0 << 24) | (b1 << 16) | (b2 << 8)) >> 8;
        }
        return values;
    }

    private static void writeShorts(MemorySegment buffer, int... values) {
        for (int frame = 0; frame < values.length; frame++) {
            buffer.set(ValueLayout.JAVA_SHORT_UNALIGNED,
                    (long) frame * Short.BYTES, (short) values[frame]);
        }
    }

    /**
     * Lays a right-justified 32-bit sample out big-endian, as an MSB driver
     * would. {@code JAVA_INT_UNALIGNED} is native (little-endian) order on the
     * only platform ASIO runs on, so the value is byte-reversed on the way in.
     */
    private static void writeRawIntsBigEndian(MemorySegment buffer, int... values) {
        for (int frame = 0; frame < values.length; frame++) {
            buffer.set(ValueLayout.JAVA_INT_UNALIGNED, (long) frame * Integer.BYTES,
                    Integer.reverseBytes(values[frame]));
        }
    }

    private static int[] readRawInts(MemorySegment buffer, int frameCount) {
        int[] values = new int[frameCount];
        for (int frame = 0; frame < frameCount; frame++) {
            values[frame] = buffer.get(ValueLayout.JAVA_INT_UNALIGNED,
                    (long) frame * Integer.BYTES);
        }
        return values;
    }

    private static void writeDoubles(MemorySegment buffer, double... values) {
        for (int frame = 0; frame < values.length; frame++) {
            buffer.set(ValueLayout.JAVA_DOUBLE_UNALIGNED,
                    (long) frame * Double.BYTES, values[frame]);
        }
    }

    private static double[] readDoubles(MemorySegment buffer, int frameCount) {
        double[] values = new double[frameCount];
        for (int frame = 0; frame < frameCount; frame++) {
            values[frame] = buffer.get(ValueLayout.JAVA_DOUBLE_UNALIGNED,
                    (long) frame * Double.BYTES);
        }
        return values;
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
