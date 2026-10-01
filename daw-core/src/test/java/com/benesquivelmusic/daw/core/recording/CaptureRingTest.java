package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.MonitorInstruction;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CaptureRing} — the lock-free callback → flush-thread hand-off of
 * story 323 (book §4.2).
 */
class CaptureRingTest {

    private static final int SLOT_FRAMES = 4;
    private static final int CHANNELS = 2;
    private static final int SOURCES = 2;

    private static CaptureRing ring(int requestedSlots) {
        return new CaptureRing(SLOT_FRAMES, CHANNELS, SOURCES, requestedSlots);
    }

    private static float sampleFor(int block, int source, int channel, int frame) {
        return block * 1000f + source * 100f + channel * 10f + frame;
    }

    private static int framesFor(int block) {
        return SLOT_FRAMES - (block % 2); // delivered block length varies, never assume a constant
    }

    private static void fill(CaptureRing.Slot slot, int block, float[][] scratch) {
        int frames = framesFor(block);
        slot.setStartFrame((long) block * SLOT_FRAMES);
        slot.setBeatPosition(block * 0.25);
        slot.setNumFrames(frames);
        slot.setPunchEnabled(block % 2 == 0);
        slot.setPunchStartFrames(block * 100L);
        slot.setPunchEndFrames(block * 100L + 50);
        slot.setLoopEnabled(block % 3 == 0);
        for (int ch = 0; ch < CHANNELS; ch++) {
            for (int f = 0; f < frames; f++) {
                scratch[ch][f] = sampleFor(block, 0, ch, f);
            }
        }
        slot.copySource(0, scratch, CHANNELS, frames);
        if (block % 2 == 0) {
            for (int ch = 0; ch < CHANNELS; ch++) {
                for (int f = 0; f < frames; f++) {
                    scratch[ch][f] = sampleFor(block, 1, ch, f);
                }
            }
            slot.copySource(1, scratch, CHANNELS, frames);
        } else {
            slot.clearSource(1);
        }
    }

    /** Returns a description of the first mismatch, or {@code null} when the slot is exactly block {@code expected}. */
    private static String verify(CaptureRing.Slot slot, int expected) {
        int frames = framesFor(expected);
        if (slot.sequence() != expected) {
            return "sequence " + slot.sequence() + " != " + expected;
        }
        if (slot.startFrame() != (long) expected * SLOT_FRAMES) {
            return "startFrame of block " + expected + ": " + slot.startFrame();
        }
        if (slot.beatPosition() != expected * 0.25) {
            return "beat of block " + expected + ": " + slot.beatPosition();
        }
        if (slot.numFrames() != frames) {
            return "numFrames of block " + expected + ": " + slot.numFrames();
        }
        if (slot.punchEnabled() != (expected % 2 == 0)
                || slot.punchStartFrames() != expected * 100L
                || slot.punchEndFrames() != expected * 100L + 50
                || slot.loopEnabled() != (expected % 3 == 0)) {
            return "gate snapshot of block " + expected;
        }
        if (slot.sourceChannels(0) != CHANNELS) {
            return "source 0 channels of block " + expected + ": " + slot.sourceChannels(0);
        }
        int expectedSource1 = expected % 2 == 0 ? CHANNELS : 0;
        if (slot.sourceChannels(1) != expectedSource1) {
            return "source 1 channels of block " + expected + ": " + slot.sourceChannels(1);
        }
        for (int s = 0; s < SOURCES; s++) {
            for (int ch = 0; ch < slot.sourceChannels(s); ch++) {
                for (int f = 0; f < frames; f++) {
                    float actual = slot.channel(s, ch)[f];
                    if (actual != sampleFor(expected, s, ch, f)) {
                        return "payload of block " + expected + " source " + s + " ch " + ch
                                + " frame " + f + ": " + actual;
                    }
                }
            }
        }
        return null;
    }

    @Test
    void deliversEveryBlockInOrderAcrossWrapWithProducerAndConsumerThreads() throws Exception {
        CaptureRing ring = ring(8);
        int total = ring.capacity() * 3 + 5; // > 3× capacity: the index wraps repeatedly
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch consumerDone = new CountDownLatch(1);
        AtomicInteger consumed = new AtomicInteger();
        AtomicInteger refusals = new AtomicInteger();

        Thread producer = Thread.ofPlatform().name("capture-ring-test-producer").unstarted(() -> {
            float[][] scratch = new float[CHANNELS][SLOT_FRAMES];
            for (int block = 0; block < total; block++) {
                CaptureRing.Slot slot;
                while ((slot = ring.claim()) == null) {
                    // Ring full: every refusal is a counted drop (D3). This test
                    // wants every block delivered, so re-offer the same block
                    // after waiting for the consumer instead of moving on.
                    refusals.incrementAndGet();
                    LockSupport.parkNanos(10_000);
                }
                fill(slot, block, scratch);
                ring.publish();
            }
        });
        Thread consumer = Thread.ofPlatform().name("capture-ring-test-consumer").unstarted(() -> {
            int expected = 0;
            while (expected < total) {
                CaptureRing.Slot slot = ring.peek();
                if (slot == null) {
                    LockSupport.parkNanos(10_000);
                    continue;
                }
                String problem = verify(slot, expected);
                if (problem != null) {
                    errors.add(problem);
                }
                ring.release();
                expected++;
                consumed.set(expected);
            }
            consumerDone.countDown();
        });

        consumer.start();
        producer.start();
        // Guard (20 s) is far larger than any inner wait (10 µs parks).
        boolean finished = consumerDone.await(20, TimeUnit.SECONDS);
        producer.join(5_000);
        consumer.join(5_000);

        assertThat(finished).as("consumer drained every block").isTrue();
        assertThat(errors).as("every block's header and payload arrived in order").isEmpty();
        assertThat(consumed.get()).isEqualTo(total);
        assertThat(ring.overflowCount())
                .as("every refused claim was counted as a drop, and nothing else was")
                .isEqualTo(refusals.get());
        assertThat(ring.isEmpty()).isTrue();
        assertThat(ring.publishedBlocks()).isEqualTo(total);
        assertThat(ring.releasedBlocks()).isEqualTo(total);
    }

    @Test
    void fullRingDropsTheIncomingBlockAndCountsIt() {
        CaptureRing ring = ring(8);
        float[][] scratch = new float[CHANNELS][SLOT_FRAMES];
        for (int block = 0; block < ring.capacity(); block++) {
            CaptureRing.Slot slot = ring.claim();
            assertThat(slot).as("slot " + block).isNotNull();
            fill(slot, block, scratch);
            ring.publish();
        }
        assertThat(ring.size()).isEqualTo(ring.capacity());

        assertThat(ring.claim()).as("a full ring refuses the incoming block").isNull();
        assertThat(ring.overflowCount()).isEqualTo(1);
        assertThat(ring.claim()).isNull();
        assertThat(ring.overflowCount()).isEqualTo(2);

        // The queued blocks are untouched: the oldest is still block 0.
        assertThat(ring.size()).isEqualTo(ring.capacity());
        CaptureRing.Slot oldest = ring.peek();
        assertThat(oldest).isNotNull();
        assertThat(verify(oldest, 0)).isNull();
    }

    @Test
    void claimWorksAgainAfterADroppedBlockOnceTheConsumerFreesASlot() {
        CaptureRing ring = ring(8);
        float[][] scratch = new float[CHANNELS][SLOT_FRAMES];
        for (int block = 0; block < ring.capacity(); block++) {
            fill(ring.claim(), block, scratch);
            ring.publish();
        }
        assertThat(ring.claim()).isNull();
        assertThat(ring.overflowCount()).isEqualTo(1);

        ring.release(); // consumer frees block 0's slot

        CaptureRing.Slot slot = ring.claim();
        assertThat(slot).isNotNull();
        assertThat(slot.sequence()).isEqualTo(ring.capacity());
        fill(slot, ring.capacity(), scratch);
        ring.publish();
        assertThat(ring.size()).isEqualTo(ring.capacity());
        assertThat(ring.overflowCount()).as("a successful claim does not touch the counter").isEqualTo(1);

        // In-order delivery resumes at block 1 (block 0 was released, the dropped block never existed).
        assertThat(verify(ring.peek(), 1)).isNull();
    }

    @Test
    void capacityRoundsUpToAPowerOfTwoAndNeverBelowEight() {
        assertThat(ring(1).capacity()).isEqualTo(8);
        assertThat(ring(3).capacity()).isEqualTo(8);
        assertThat(ring(8).capacity()).isEqualTo(8);
        assertThat(ring(9).capacity()).isEqualTo(16);
        assertThat(ring(100).capacity()).isEqualTo(128);
        assertThat(CaptureRing.MIN_SLOTS).isEqualTo(8);
    }

    @Test
    void slotCountCoversTheHandoffToleranceRoundedUpToAPowerOfTwo() {
        Duration tolerance = Duration.ofMillis(250);
        // 512 frames @ 48 kHz = 10.67 ms → 23.4 blocks → 24 → 32
        assertThat(CaptureRing.slotCountFor(48_000, 512, tolerance)).isEqualTo(32);
        // 4096 frames @ 48 kHz = 85.3 ms → 2.9 blocks → 3 → floor of 8
        assertThat(CaptureRing.slotCountFor(48_000, 4096, tolerance)).isEqualTo(8);
        // 64 frames @ 44.1 kHz = 1.45 ms → 172.3 blocks → 173 → 256
        assertThat(CaptureRing.slotCountFor(44_100, 64, tolerance)).isEqualTo(256);
        // Exactly a power of two stays put: 256 frames @ 48 kHz → 46.9 → 47 → 64
        assertThat(CaptureRing.slotCountFor(48_000, 256, tolerance)).isEqualTo(64);
        assertThat(CaptureRing.slotCountFor(48_000, 512, Duration.ZERO)).isEqualTo(8);
    }

    @Test
    void forFormatSizesSlotsFromTheLiveFormat() {
        CaptureRing ring = CaptureRing.forFormat(new AudioFormat(48_000.0, 4, 24, 512), 3);

        assertThat(ring.slotFrames()).isEqualTo(512);
        assertThat(ring.channelsPerSource()).isEqualTo(4);
        assertThat(ring.sourceCount()).isEqualTo(3);
        assertThat(ring.capacity()).isEqualTo(32);
        CaptureRing.Slot slot = ring.claim();
        assertThat(slot.source(2)).hasDimensions(4, 512);
        assertThat(slot.slotFrames()).isEqualTo(512);
    }

    @Test
    void headerFieldsSurviveARoundTrip() {
        CaptureRing ring = ring(8);
        CaptureRing.Slot written = ring.claim();
        written.setStartFrame(1_234_567_890_123L);
        written.setBeatPosition(97.125);
        written.setNumFrames(3);
        written.setPunchEnabled(true);
        written.setPunchStartFrames(1_000_000L);
        written.setPunchEndFrames(2_000_000L);
        written.setLoopEnabled(true);
        written.setSourceChannels(0, 2);
        written.setSourceChannels(1, 1);
        ring.publish();

        CaptureRing.Slot read = ring.peek();
        assertThat(read).isSameAs(written);
        assertThat(read.startFrame()).isEqualTo(1_234_567_890_123L);
        assertThat(read.beatPosition()).isEqualTo(97.125);
        assertThat(read.numFrames()).isEqualTo(3);
        assertThat(read.punchEnabled()).isTrue();
        assertThat(read.punchStartFrames()).isEqualTo(1_000_000L);
        assertThat(read.punchEndFrames()).isEqualTo(2_000_000L);
        assertThat(read.loopEnabled()).isTrue();
        assertThat(read.sourceChannels(0)).isEqualTo(2);
        assertThat(read.sourceChannels(1)).isEqualTo(1);
        assertThat(read.sequence()).isZero();
    }

    @Test
    void claimResetsTheHeaderOfAReusedSlot() {
        CaptureRing ring = ring(8);
        float[][] scratch = new float[CHANNELS][SLOT_FRAMES];
        fill(ring.claim(), 0, scratch);
        ring.publish();
        ring.release();
        // Fill the ring so the next claim wraps onto slot 0 again.
        for (int block = 1; block <= ring.capacity(); block++) {
            CaptureRing.Slot slot = ring.claim();
            if (block == ring.capacity()) {
                assertThat(slot.sequence()).isEqualTo(ring.capacity());
                assertThat(slot.startFrame()).isZero();
                assertThat(slot.beatPosition()).isZero();
                assertThat(slot.numFrames()).isZero();
                assertThat(slot.punchEnabled()).isFalse();
                assertThat(slot.punchStartFrames()).isZero();
                assertThat(slot.punchEndFrames()).isZero();
                assertThat(slot.loopEnabled()).isFalse();
                assertThat(slot.sourceChannels(0)).isZero();
                assertThat(slot.sourceChannels(1)).isZero();
            }
            ring.publish();
        }
    }

    @Test
    void claimedSlotIsNotVisibleToPeekBeforePublish() {
        CaptureRing ring = ring(8);
        CaptureRing.Slot slot = ring.claim();
        slot.setStartFrame(42);

        assertThat(ring.peek()).isNull();
        assertThat(ring.isEmpty()).isTrue();
        assertThat(ring.size()).isZero();
        assertThat(ring.publishedBlocks()).isZero();

        ring.publish();

        assertThat(ring.peek()).isSameAs(slot);
        assertThat(ring.isEmpty()).isFalse();
        assertThat(ring.size()).isEqualTo(1);
        assertThat(ring.publishedBlocks()).isEqualTo(1);
    }

    @Test
    void reclaimingWithoutPublishingReturnsTheSameSlot() {
        CaptureRing ring = ring(8);
        CaptureRing.Slot first = ring.claim();
        first.setStartFrame(7);
        CaptureRing.Slot again = ring.claim();

        assertThat(again).isSameAs(first);
        assertThat(again.startFrame()).as("re-claim resets the header").isZero();
        assertThat(ring.overflowCount()).isZero();
    }

    @Test
    void copySourceClampsToTheSlotShapeAndZeroFillsMissingFrames() {
        CaptureRing ring = ring(8);
        CaptureRing.Slot slot = ring.claim();
        float[][] wide = new float[CHANNELS + 2][SLOT_FRAMES + 3];
        for (float[] row : wide) {
            java.util.Arrays.fill(row, 1f);
        }
        slot.copySource(0, wide, CHANNELS + 2, SLOT_FRAMES + 3);
        assertThat(slot.sourceChannels(0)).isEqualTo(CHANNELS);
        for (int ch = 0; ch < CHANNELS; ch++) {
            assertThat(slot.channel(0, ch)).containsOnly(1f);
        }

        float[][] shortRows = new float[CHANNELS][2];
        shortRows[0][0] = 5f;
        shortRows[0][1] = 6f;
        slot.copySource(0, shortRows, CHANNELS, SLOT_FRAMES);
        assertThat(slot.channel(0, 0)).containsExactly(5f, 6f, 0f, 0f);
        assertThat(slot.channel(0, 1)).containsExactly(0f, 0f, 0f, 0f);

        slot.setNumFrames(SLOT_FRAMES + 100);
        assertThat(slot.numFrames()).isEqualTo(SLOT_FRAMES);
        slot.setNumFrames(-1);
        assertThat(slot.numFrames()).isZero();
        slot.setSourceChannels(1, 99);
        assertThat(slot.sourceChannels(1)).isEqualTo(CHANNELS);
        slot.clearSource(1);
        assertThat(slot.sourceChannels(1)).isZero();
    }

    @Test
    void publishWithoutClaimAndReleaseOnEmptyRingAreProgrammingErrors() {
        CaptureRing ring = ring(8);
        assertThatThrownBy(ring::publish).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(ring::release).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNonPositiveShape() {
        assertThatThrownBy(() -> new CaptureRing(0, 2, 1, 8)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CaptureRing(4, 0, 1, 8)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CaptureRing(4, 2, 0, 8)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CaptureRing(4, 2, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CaptureRing.slotCountFor(0, 512, Duration.ofMillis(250)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CaptureRing.slotCountFor(48_000, 512, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsASlotRequestNoPowerOfTwoCanCover() throws InterruptedException {
        // The largest power of two an int holds is 2^30; rounding a larger
        // request up can never terminate. The constructor runs on its own
        // daemon thread so that a regression fails this test instead of
        // hanging the suite (guard 10 s; the refusal itself takes no time).
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread constructing = Thread.ofPlatform().name("capture-ring-test-oversized").daemon(true).start(() -> {
            try {
                new CaptureRing(SLOT_FRAMES, CHANNELS, SOURCES, CaptureRing.MAX_SLOTS + 1);
            } catch (Throwable thrown) {
                outcome.set(thrown);
            }
        });
        constructing.join(10_000);

        assertThat(constructing.isAlive()).as("the constructor returned").isFalse();
        assertThat(outcome.get())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestedSlots")
                .hasMessageContaining(Integer.toString(CaptureRing.MAX_SLOTS + 1));
        assertThat(CaptureRing.MAX_SLOTS).isEqualTo(1 << 30);
    }

    @Test
    void slotCountForRejectsAToleranceNoPowerOfTwoCanCover() throws InterruptedException {
        // The sizing helper rounds up with the loop the constructor has, so
        // it needs the same refusal: a tolerance of more than 2^30 blocks
        // has no power of two to be rounded up to. Every call that can
        // reach the rounding loop runs on a daemon thread under the guard,
        // so a regression fails this test instead of hanging the suite.
        double largestCoverableRate = CaptureRing.MAX_SLOTS; // one-frame blocks, 1 s: exactly 2^30 blocks
        AtomicInteger largest = new AtomicInteger();
        Throwable atTheLimit = RampCaptureTestSupport.outcomeWithinTheGuard("capture-ring-test-slot-count-limit",
                () -> largest.set(CaptureRing.slotCountFor(largestCoverableRate, 1, Duration.ofSeconds(1))));
        assertThat(atTheLimit).as("exactly MAX_SLOTS blocks is still coverable").isNull();
        assertThat(largest.get()).isEqualTo(CaptureRing.MAX_SLOTS);

        Throwable oneBlockMore = RampCaptureTestSupport.outcomeWithinTheGuard("capture-ring-test-slot-count-over",
                () -> CaptureRing.slotCountFor(largestCoverableRate + 1, 1, Duration.ofSeconds(1)));
        assertThat(oneBlockMore)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tolerance covers more than " + CaptureRing.MAX_SLOTS + " blocks")
                .hasMessageContaining(Long.toString(CaptureRing.MAX_SLOTS + 1L));

        Throwable aDayOfOneFrameBlocks = RampCaptureTestSupport.outcomeWithinTheGuard("capture-ring-test-slot-count-day",
                () -> CaptureRing.slotCountFor(48_000, 1, Duration.ofDays(1)));
        assertThat(aDayOfOneFrameBlocks)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(Long.toString(86_400L * 48_000L));

        // Not under the guard: this call never reaches the rounding loop.
        // Duration.toNanos() overflows before the slot-count guard is reached.
        assertThatThrownBy(() -> CaptureRing.slotCountFor(48_000, 512, Duration.ofSeconds(Long.MAX_VALUE)))
                .as("the tolerance exceeds Long.MAX_VALUE nanoseconds:"
                        + " Duration.toNanos() overflows before the slot-count guard")
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void aDropMarksTheLastBlockPublishedBeforeIt() {
        CaptureRing ring = ring(8);
        float[][] scratch = new float[CHANNELS][SLOT_FRAMES];
        assertThat(ring.droppedAfterSequence()).as("nothing dropped yet").isEqualTo(CaptureRing.NO_DROP).isEqualTo(-1L);
        for (int block = 0; block < ring.capacity(); block++) {
            fill(ring.claim(), block, scratch);
            ring.publish();
            assertThat(ring.droppedAfterSequence()).as("a successful claim leaves the marker alone").isEqualTo(-1L);
        }

        assertThat(ring.claim()).isNull();
        assertThat(ring.droppedAfterSequence()).as("block 7 was the last one published before the drop").isEqualTo(7);
        assertThat(ring.claim()).isNull();
        assertThat(ring.droppedAfterSequence()).as("a second drop in the same episode shares the marker").isEqualTo(7);
        assertThat(ring.overflowCount()).isEqualTo(2);

        ring.release(); // the consumer frees block 0's slot
        CaptureRing.Slot next = ring.claim();
        assertThat(next.sequence()).isEqualTo(8);
        fill(next, 8, scratch);
        ring.publish();
        assertThat(ring.droppedAfterSequence()).as("publishing does not move the marker").isEqualTo(7);

        assertThat(ring.claim()).as("full again").isNull();
        assertThat(ring.droppedAfterSequence()).as("the next drop follows block 8").isEqualTo(8);
        assertThat(ring.overflowCount()).isEqualTo(3);
    }

    @Test
    void truncatedFramesAreCountedOnTheRingAndStampedOnTheSlot() {
        CaptureRing ring = ring(8);
        assertThat(ring.truncatedFrames()).isZero();

        CaptureRing.Slot slot = ring.claim();
        assertThat(slot.truncatedFrames()).isZero();
        slot.setNumFrames(SLOT_FRAMES + 3);
        slot.setTruncatedFrames(3);
        ring.noteTruncatedFrames(3);
        ring.publish();

        assertThat(ring.peek().numFrames()).isEqualTo(SLOT_FRAMES);
        assertThat(ring.peek().truncatedFrames()).isEqualTo(3);
        assertThat(ring.truncatedFrames()).isEqualTo(3);
        ring.noteTruncatedFrames(5);
        ring.noteTruncatedFrames(0);
        ring.noteTruncatedFrames(-7);
        assertThat(ring.truncatedFrames()).as("only positive excess counts").isEqualTo(8);

        ring.release();
        for (int block = 1; block < ring.capacity(); block++) {
            ring.claim();
            ring.publish();
        }
        CaptureRing.Slot reused = ring.claim();
        assertThat(reused).isSameAs(slot);
        assertThat(reused.truncatedFrames()).as("claim resets the stamp of a reused slot").isZero();
        reused.setTruncatedFrames(-1);
        assertThat(reused.truncatedFrames()).isZero();
    }

    @Test
    void slotsRealTimeSafeSurfaceObeysTheRulesTheModuleScannerCannotReach() {
        // RealTimeSafeContractTest discovers classes through ModuleClassScanner,
        // which skips every nested ('$') class — so the type-level annotation on
        // CaptureRing.Slot is pinned here instead.
        assertThat(CaptureRing.Slot.class.isAnnotationPresent(RealTimeSafe.class)).isTrue();
        List<Method> surface = publicMethodsOf(CaptureRing.Slot.class);

        assertThat(surface).extracting(Method::getName)
                .as("the pin sees the callback's copy and stamp methods")
                .contains("copySource", "clearSource", "setNumFrames", "setTruncatedFrames", "setStartFrame",
                        "setBeatPosition", "setPunchEnabled", "setPunchStartFrames", "setPunchEndFrames",
                        "setLoopEnabled", "slotFrames");
        assertThat(realTimeSafetyViolations(surface)).isEmpty();
    }

    @Test
    void theSlotPinWouldRejectEachForbiddenShape() {
        // Self-check: the predicate that passes Slot fails a fixture that
        // breaks each rule once, and passes the fixture's one clean method.
        List<Method> fixture = publicMethodsOf(RealTimeUnsafeFixture.class);

        assertThat(fixture).extracting(Method::getName).containsExactlyInAnyOrder(
                "boxedParameter", "boxedReturn", "variableArity", "locked", "lockedInside", "clean");
        assertThat(realTimeSafetyViolations(fixture))
                .as("reflection sees the synchronized modifier, not a synchronized block: 'lockedInside' passes here")
                .containsExactlyInAnyOrder(
                        "boxedParameter: boxed parameter java.lang.Integer",
                        "boxedReturn: boxed return java.lang.Long",
                        "variableArity: varargs",
                        "locked: synchronized");
    }

    @Test
    void slotsPublicMethodsHoldNoMonitorInstruction() throws IOException {
        // The bytecode half of the synchronized rule (skill
        // dawg-annotations-reflection §4): a synchronized BLOCK leaves no
        // modifier for reflection to see, only MONITORENTER/MONITOREXIT in
        // the method's code.
        List<String> scanned = new ArrayList<>();
        List<String> offenders = publicMethodsHoldingAMonitor(CaptureRing.Slot.class, scanned);

        assertThat(scanned)
                .as("the scan parsed CaptureRing$Slot and saw the callback's copy and stamp methods")
                .contains("copySource", "clearSource", "setNumFrames", "setTruncatedFrames", "setStartFrame",
                        "setBeatPosition", "setPunchEnabled", "setPunchStartFrames", "setPunchEndFrames",
                        "setLoopEnabled", "slotFrames");
        assertThat(scanned).hasSameSizeAs(publicMethodsOf(CaptureRing.Slot.class));
        assertThat(offenders).isEmpty();
    }

    @Test
    void theMonitorScanWouldRejectASynchronizedBlock() throws IOException {
        // Self-check: the scan that passes Slot names the one fixture method
        // that locks inside its body — and only that one (a synchronized
        // METHOD is a flag, not an instruction; the reflection pin has it).
        List<String> scanned = new ArrayList<>();
        List<String> offenders = publicMethodsHoldingAMonitor(RealTimeUnsafeFixture.class, scanned);

        assertThat(scanned).containsExactlyInAnyOrder(
                "boxedParameter", "boxedReturn", "variableArity", "locked", "lockedInside", "clean");
        assertThat(offenders).containsExactly("lockedInside");
    }

    /**
     * Parses {@code type}'s class file and returns the names of its public
     * methods whose code holds a monitor instruction; every public method
     * the scan looked at is added to {@code scanned}.
     */
    private static List<String> publicMethodsHoldingAMonitor(Class<?> type, List<String> scanned)
            throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        byte[] bytes;
        try (InputStream in = type.getResourceAsStream(resource)) {
            assertThat(in).as("class file %s", resource).isNotNull();
            bytes = in.readAllBytes();
        }
        ClassModel model = ClassFile.of().parse(bytes);
        assertThat(model.thisClass().asInternalName()).isEqualTo(type.getName().replace('.', '/'));
        List<String> offenders = new ArrayList<>();
        for (MethodModel method : model.methods()) {
            String name = method.methodName().stringValue();
            if (!method.flags().has(AccessFlag.PUBLIC) || method.flags().has(AccessFlag.SYNTHETIC)
                    || name.startsWith("<")) {
                continue; // not public, compiler-made, or a constructor / initializer
            }
            scanned.add(name);
            method.code().ifPresent(code -> {
                for (CodeElement element : code) {
                    if (element instanceof MonitorInstruction) {
                        offenders.add(name);
                        return;
                    }
                }
            });
        }
        return offenders;
    }

    private static List<Method> publicMethodsOf(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> !m.isSynthetic())
                .toList();
    }

    /** The reflection-visible rules of {@code RealTimeSafeContractTest}: no synchronized, no varargs, no boxed types. */
    private static List<String> realTimeSafetyViolations(List<Method> methods) {
        List<String> violations = new ArrayList<>();
        for (Method method : methods) {
            if (Modifier.isSynchronized(method.getModifiers())) {
                violations.add(method.getName() + ": synchronized");
            }
            if (method.isVarArgs()) {
                violations.add(method.getName() + ": varargs");
            }
            if (BOXED_TYPES.contains(method.getReturnType())) {
                violations.add(method.getName() + ": boxed return " + method.getReturnType().getName());
            }
            for (Class<?> parameter : method.getParameterTypes()) {
                if (BOXED_TYPES.contains(parameter)) {
                    violations.add(method.getName() + ": boxed parameter " + parameter.getName());
                }
            }
        }
        return violations;
    }

    private static final Set<Class<?>> BOXED_TYPES = Set.of(
            Boolean.class, Byte.class, Character.class, Short.class,
            Integer.class, Long.class, Float.class, Double.class);

    /** Breaks each rule exactly once — four that reflection sees and one ({@code lockedInside}) that only the bytecode shows — and has one clean method. */
    @SuppressWarnings("unused")
    static final class RealTimeUnsafeFixture {
        public void boxedParameter(Integer frames) {
        }

        public Long boxedReturn() {
            return 0L;
        }

        public void variableArity(int... frames) {
        }

        public synchronized void locked() {
        }

        public void lockedInside() {
            synchronized (this) {
            }
        }

        public void clean(int frames, float[][] block) {
        }
    }
}
