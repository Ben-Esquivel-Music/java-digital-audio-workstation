package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Preallocated, allocation-free, single-producer / single-consumer ring of raw
 * capture blocks — the only hand-off between the audio callback and the
 * {@code capture-flush} thread (Recording Reliability book §4.2; story 323).
 *
 * <p><strong>Threads.</strong> Exactly one producer and one consumer:</p>
 * <ul>
 *   <li>The audio callback ({@code CaptureCallback.onAudioCaptured}) enters
 *       the producer gate ({@link #enterProducer()}), calls {@link #claim()},
 *       fills the returned {@link Slot} (header + payload copies), calls
 *       {@link #publish()} if the gate is still open
 *       ({@link #producerOpen()}), and leaves the gate
 *       ({@link #exitProducer()}); it counts what it had to cut off with
 *       {@link #noteTruncatedFrames(int)}. Those, plus the read-only
 *       {@link #overflowCount()} / {@link #droppedAfterSequence()} /
 *       {@link #truncatedFrames()} / {@link #size()} / {@link #isEmpty()},
 *       are the {@link RealTimeSafe} surface: bounded arithmetic, volatile
 *       and release stores, no allocation, no locks, no throwing paths
 *       reachable from a well-formed call.</li>
 *   <li>The flush thread calls {@link #peek()} to read the oldest published
 *       slot in place and {@link #release()} once it has finished with it. It
 *       is the only writer of the read index. Before the final sweep of a
 *       stop it waits, bounded, for a callback in flight to leave the gate
 *       ({@link #awaitProducerQuiescent(long)}).</li>
 *   <li>The thread that ends the take closes the gate
 *       ({@link #closeProducer()}): one volatile store, no waiting.</li>
 * </ul>
 *
 * <p><strong>Producer gate — the stop fence</strong> (book §5.2 FINALIZING:
 * deregister → drain → seal). Removing the engine's recording callback does
 * not stop a callback the audio thread has already loaded, so the ring
 * carries a per-take gate of two volatile words: {@code producerClosed},
 * written by the thread that ends the take, and {@code producerPhase},
 * written only by the callback and odd while it is inside. The callback
 * stores an odd phase and then loads the closed flag; the stopping side
 * stores the closed flag and then — on the flush thread — loads the phase.
 * Volatile accesses are totally ordered, so one of two things holds for
 * every callback. Either its phase store comes before the flush thread's
 * phase load: the flush thread then reads that odd phase and waits for the
 * exit store, which the callback makes after its publish, or it reads the
 * even phase of that exit already — and the publish is visible to it either
 * way. Or the phase store comes after that load, and so after the close:
 * every load of the closed flag the callback then makes — at entry, and
 * again right before {@link #publish()} — reads closed, and it publishes
 * nothing. So once {@link #awaitProducerQuiescent(long)} has returned
 * {@code true}, no block is published to this ring again, and the drain that
 * follows is complete. A callback that finds the gate closed after it
 * claimed a slot leaves the slot claimed and unpublished: the block that
 * straddles the stop is dropped whole, never torn. The gate is never
 * reopened — a ring serves one take, a later take has its own ring, and a
 * callback left over from this take can only ever find this ring's gate
 * closed. Both words are plain volatile loads and stores; the phase is
 * advanced with a load and a store, not an atomic read-modify-write,
 * because the callback is its only writer.</p>
 *
 * <p><strong>Precondition: one callback thread at a time.</strong> The
 * phase word, and with it the whole argument above, is correct only while
 * at most one thread is between {@link #enterProducer()} and
 * {@link #exitProducer()} at any moment — the engine's single render
 * thread, which loads the recording callback once per block. Two callers
 * that overlap can lose one of the unsynchronised increments and leave the
 * parity wrong for the rest of the take: stuck odd, every stop waits out the
 * whole bound and warns; stuck even while a callback is inside, the wait
 * reports quiescence that does not hold and the final sweep can miss a
 * block. Nothing here detects that; the gate does not guard against it.</p>
 *
 * <p><strong>Slot layout</strong> (book §4.2 / context D2). Each slot carries a
 * primitives-only header stamped on the callback — start frame, transport
 * beat position, kept frame count, truncated-frame count, punch-gate
 * snapshot, loop flag — and a payload of {@code sourceCount × channelsPerSource} channel rows of
 * {@code slotFrames} samples. Source 0 is the device block; each further
 * source is the graph-instrument recording buffer of one armed track whose
 * input routing is {@code NONE} (that engine buffer is only valid inside the
 * callback, so it must be copied here). {@link Slot#sourceChannels(int)}
 * records how many channels were actually present per source in this block
 * ({@code 0} = absent).</p>
 *
 * <p><strong>Sizing.</strong> {@code slotFrames} is the block size of the
 * format the pipeline was constructed with (a delivered block may be
 * longer — see "Over-long blocks"); the slot count is {@link #slotCountFor(double, int, Duration)}: enough
 * blocks to cover {@link #DEFAULT_HANDOFF_TOLERANCE} (250 ms) of audio, never
 * fewer than {@link #MIN_SLOTS}, rounded up to a power of two so the index
 * wrap is a mask — the {@code AudioBlockRing} discipline the ASIO shim
 * already proves.</p>
 *
 * <p><strong>Overflow policy</strong> (book §4.2). This ring drops the
 * <em>incoming</em> block, not the oldest queued one: {@link #claim()} returns
 * {@code null}, {@link #overflowCount()} advances, and the callback returns
 * at once. Overwriting the oldest slot is not single-producer-safe — the
 * consumer may be copying out of exactly that slot at that moment (the same
 * reason {@code AudioBlockRing.write} gives). Either way the loss is bounded
 * to one block per overflow and honestly recorded: the flush thread notices
 * the counter advancing and writes a {@code gap=} line to the take manifest,
 * positioned by {@link #droppedAfterSequence()}. The callback never blocks
 * (book §9.14).</p>
 *
 * <p><strong>Over-long blocks.</strong> A slot holds {@code slotFrames}
 * frames. A delivered block longer than that keeps its first
 * {@code slotFrames} frames; the callback stamps the excess on the slot
 * ({@link Slot#setTruncatedFrames(int)}) and counts it here
 * ({@link #noteTruncatedFrames(int)}), and the flush thread records the
 * episode in the manifest. Nothing is cut off silently.</p>
 *
 * <p>The counters and the drop marker are each a plain load followed by a
 * release store ({@code lazySet}) rather than an atomic read-modify-write:
 * the callback is their only writer, and the story-316 sentinels forbid the
 * CAS family on callback bridges.</p>
 */
public final class CaptureRing {

    /** Smallest slot count ever allocated (book §4.2: "never fewer than 8 slots"). */
    public static final int MIN_SLOTS = 8;

    /**
     * Largest slot count that can be requested: 2<sup>30</sup>, the largest
     * power of two an {@code int} holds. A larger request has no power of
     * two to be rounded up to and is refused by the constructor, and a
     * tolerance that covers more blocks than this is refused by
     * {@link #slotCountFor(double, int, Duration)}.
     */
    public static final int MAX_SLOTS = 1 << 30;

    /** Hand-off tolerance the default sizing covers (book §4.2: "≥ 250 ms of audio"). */
    public static final Duration DEFAULT_HANDOFF_TOLERANCE = Duration.ofMillis(250);

    /** Value of {@link #droppedAfterSequence()} while no block has ever been dropped. */
    public static final long NO_DROP = -1L;

    /** Park step of {@link #awaitProducerQuiescent(long)} while a callback is inside the producer gate. */
    static final long QUIESCENCE_POLL_NANOS = 100_000L;

    private final Slot[] slots;
    private final int mask;
    private final int capacity;
    private final int slotFrames;
    private final int channelsPerSource;
    private final int sourceCount;
    private final AtomicLong head = new AtomicLong(0);          // consumer: next slot to read
    private final AtomicLong tail = new AtomicLong(0);          // producer: next slot to publish
    private final AtomicLong overflowCount = new AtomicLong(0); // producer-only writer
    private final AtomicLong droppedAfterSequence = new AtomicLong(NO_DROP); // producer-only writer
    private final AtomicLong truncatedFrames = new AtomicLong(0);            // producer-only writer
    private boolean claimed;                                    // producer-only
    /** The take is ending: no block is to be published any more. Written by the thread that ends the take. */
    private volatile boolean producerClosed;
    /** Odd while the callback is between {@link #enterProducer()} and {@link #exitProducer()}; the callback is the only writer. */
    private volatile long producerPhase;

    /**
     * Creates a ring of at least {@code requestedSlots} slots (rounded up to a
     * power of two, never fewer than {@link #MIN_SLOTS}), each holding
     * {@code sourceCount} sources of {@code channelsPerSource} channels of
     * {@code slotFrames} samples. All memory is allocated here; nothing is
     * allocated afterwards.
     *
     * @param slotFrames        frames per slot (the block size of the format the
     *                          pipeline was constructed with); positive
     * @param channelsPerSource channel rows per source; positive
     * @param sourceCount       number of sources (1 + graph-instrument tracks); positive
     * @param requestedSlots    minimum slot count; positive and at most {@link #MAX_SLOTS}
     * @throws IllegalArgumentException if an argument is out of range
     */
    public CaptureRing(int slotFrames, int channelsPerSource, int sourceCount, int requestedSlots) {
        if (slotFrames <= 0) {
            throw new IllegalArgumentException("slotFrames must be positive: " + slotFrames);
        }
        if (channelsPerSource <= 0) {
            throw new IllegalArgumentException(
                    "channelsPerSource must be positive: " + channelsPerSource);
        }
        if (sourceCount <= 0) {
            throw new IllegalArgumentException("sourceCount must be positive: " + sourceCount);
        }
        if (requestedSlots <= 0) {
            throw new IllegalArgumentException("requestedSlots must be positive: " + requestedSlots);
        }
        if (requestedSlots > MAX_SLOTS) {
            // Rounding up would shift past the int range and never reach it.
            throw new IllegalArgumentException("requestedSlots must not exceed " + MAX_SLOTS
                    + ": " + requestedSlots);
        }
        int cap = MIN_SLOTS;
        while (cap < requestedSlots) {
            cap <<= 1;
        }
        this.capacity = cap;
        this.mask = cap - 1;
        this.slotFrames = slotFrames;
        this.channelsPerSource = channelsPerSource;
        this.sourceCount = sourceCount;
        this.slots = new Slot[cap];
        for (int i = 0; i < cap; i++) {
            slots[i] = new Slot(slotFrames, channelsPerSource, sourceCount);
        }
    }

    /**
     * Creates a ring sized from the engine's live format: slot frames =
     * {@link AudioFormat#bufferSize()}, channels per source =
     * {@link AudioFormat#channels()}, slot count =
     * {@link #slotCountFor(double, int, Duration)} with the default tolerance.
     *
     * @param format      the engine's live stream format
     * @param sourceCount number of sources (1 + graph-instrument tracks)
     * @return a new ring
     */
    public static CaptureRing forFormat(AudioFormat format, int sourceCount) {
        Objects.requireNonNull(format, "format must not be null");
        return new CaptureRing(format.bufferSize(), format.channels(), sourceCount,
                slotCountFor(format.sampleRate(), format.bufferSize(), DEFAULT_HANDOFF_TOLERANCE));
    }

    /**
     * Returns the slot count that covers {@code tolerance} of audio at
     * {@code sampleRate} in blocks of {@code blockFrames}:
     * {@code max(MIN_SLOTS, ceil(tolerance / blockDuration))} rounded up to a
     * power of two.
     *
     * @param sampleRate  frames per second; positive
     * @param blockFrames frames per block; positive
     * @param tolerance   hand-off tolerance; non-negative, and covering at
     *                    most {@link #MAX_SLOTS} blocks
     * @return the slot count (a power of two ≥ {@link #MIN_SLOTS})
     * @throws IllegalArgumentException if an argument is out of range
     * @throws ArithmeticException if {@code tolerance} exceeds {@code Long.MAX_VALUE} nanoseconds (from {@link Duration#toNanos()})
     */
    public static int slotCountFor(double sampleRate, int blockFrames, Duration tolerance) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (blockFrames <= 0) {
            throw new IllegalArgumentException("blockFrames must be positive: " + blockFrames);
        }
        Objects.requireNonNull(tolerance, "tolerance must not be null");
        if (tolerance.isNegative()) {
            throw new IllegalArgumentException("tolerance must not be negative: " + tolerance);
        }
        double blocksInTolerance = tolerance.toNanos() / 1_000_000_000.0 * sampleRate / blockFrames;
        long needed = Math.max(MIN_SLOTS, (long) Math.ceil(blocksInTolerance));
        if (needed > MAX_SLOTS) {
            // Rounding up would shift past the int range and never reach it.
            throw new IllegalArgumentException("tolerance covers more than " + MAX_SLOTS
                    + " blocks: " + needed);
        }
        int cap = MIN_SLOTS;
        while (cap < needed) {
            cap <<= 1;
        }
        return cap;
    }

    /**
     * Enters the producer gate: marks the callback as inside and reports
     * whether the gate is open. Audio-callback side, first call of every
     * callback; every call is paired with one {@link #exitProducer()},
     * whatever this returns. The phase store is a full volatile store and
     * precedes the load of the closed flag — the order the class note's
     * argument rests on.
     *
     * @return {@code true} if the gate is open; {@code false} once
     *         {@link #closeProducer()} has been called — the callback then
     *         claims nothing and only leaves
     */
    @RealTimeSafe
    public boolean enterProducer() {
        producerPhase = producerPhase + 1;
        return !producerClosed;
    }

    /**
     * Returns whether the gate is still open: one volatile load.
     * Audio-callback side, between {@link #enterProducer()} and
     * {@link #exitProducer()}, right before {@link #publish()} — a block
     * whose callback reads {@code false} here is not published.
     */
    @RealTimeSafe
    public boolean producerOpen() {
        return !producerClosed;
    }

    /**
     * Leaves the producer gate: marks the callback as outside again.
     * Audio-callback side, last call of every callback that called
     * {@link #enterProducer()}.
     */
    @RealTimeSafe
    public void exitProducer() {
        producerPhase = producerPhase + 1;
    }

    /**
     * Closes the producer gate for good: one volatile store, no waiting. The
     * thread that ends the take calls it before it removes the recording
     * callback and before it moves the transport, so a block is either
     * published by a callback that read the gate open before this store —
     * and stamped its header before that — or not published at all.
     * Idempotent. Any thread but the audio callback.
     */
    public void closeProducer() {
        producerClosed = true;
    }

    /** Returns whether {@link #closeProducer()} has been called. Any thread. */
    public boolean isProducerClosed() {
        return producerClosed;
    }

    /**
     * Waits until no callback is inside the producer gate, for at most
     * {@code boundNanos}. Flush-thread side, after {@link #closeProducer()}
     * and before the final drain: once it has returned {@code true}, no
     * block is published to this ring again (class note). It returns at
     * once when no callback is inside; otherwise it parks in
     * {@link #QUIESCENCE_POLL_NANOS} steps — a callback is inside for the
     * length of one bounded copy — until the callback has left or the bound
     * has elapsed. Only the flush thread calls it; the thread that ends the
     * take never waits.
     *
     * @param boundNanos the longest wait, in nanoseconds; non-positive means
     *                   no waiting — the phase is read without parking
     * @return {@code true} if no callback is inside; {@code false} if one
     *         was still inside when the bound elapsed — it may publish one
     *         more block if it read the gate open before it was closed
     */
    public boolean awaitProducerQuiescent(long boundNanos) {
        if ((producerPhase & 1L) == 0L) {
            return true;
        }
        long deadline = System.nanoTime() + Math.max(0L, boundNanos);
        while (System.nanoTime() - deadline < 0) {
            LockSupport.parkNanos(this, QUIESCENCE_POLL_NANOS);
            if ((producerPhase & 1L) == 0L) {
                return true;
            }
        }
        return (producerPhase & 1L) == 0L;
    }

    /**
     * Claims the next free slot for the producer to fill. Audio-callback
     * side.
     *
     * <p>The returned slot's header and per-source channel counts are reset;
     * its payload is stale until written. The slot is not visible to the
     * consumer until {@link #publish()}. Claiming again without publishing
     * simply returns the same slot again, reset — which is what a callback
     * that found the producer gate closed before its publish leaves behind.</p>
     *
     * @return the slot to fill, or {@code null} when the ring is full — in
     *         which case the incoming block is dropped,
     *         {@link #droppedAfterSequence()} names the last block published
     *         before it and {@link #overflowCount()} has advanced (see the
     *         class note on the overflow policy)
     */
    @RealTimeSafe
    public Slot claim() {
        long t = tail.get();
        long h = head.get();
        if (t - h >= capacity) {
            // Single writer of both: plain load + release store, no RMW. The
            // marker goes first so a reader that sees the advanced count
            // also sees where that drop happened.
            droppedAfterSequence.lazySet(t - 1);
            overflowCount.lazySet(overflowCount.get() + 1);
            return null;
        }
        Slot slot = slots[(int) (t & mask)];
        slot.reset(t);
        claimed = true;
        return slot;
    }

    /**
     * Publishes the slot returned by the last {@link #claim()}. Audio-callback
     * side.
     *
     * <p>A release store on the tail: every header/payload write made to the
     * slot before this call happens-before the consumer's acquire load of the
     * tail, exactly as in {@code AudioBlockRing.write}.</p>
     *
     * @throws IllegalStateException if no slot is currently claimed (a
     *         programming error, never reachable from a well-formed
     *         claim → fill → publish sequence)
     */
    @RealTimeSafe
    public void publish() {
        if (!claimed) {
            throw new IllegalStateException("publish() without a preceding claim()");
        }
        claimed = false;
        tail.lazySet(tail.get() + 1);
    }

    /**
     * Returns the oldest published slot without consuming it, or {@code null}
     * when the ring is empty. Flush-thread side. The slot's contents stay
     * valid until {@link #release()}.
     */
    public Slot peek() {
        long h = head.get();
        long t = tail.get();
        if (h >= t) {
            return null;
        }
        return slots[(int) (h & mask)];
    }

    /**
     * Consumes the slot last returned by {@link #peek()}. Flush-thread side.
     *
     * <p>A release store on the head: the producer may only reuse the slot
     * once it observes the advanced index, so the consumer must be finished
     * reading before calling this.</p>
     *
     * @throws IllegalStateException if nothing is pending
     */
    public void release() {
        long h = head.get();
        long t = tail.get();
        if (h >= t) {
            throw new IllegalStateException("release() with no published slot pending");
        }
        head.lazySet(h + 1);
    }

    /** Returns how many incoming blocks have been dropped because the ring was full. Readable from any thread. */
    @RealTimeSafe
    public long overflowCount() {
        return overflowCount.get();
    }

    /**
     * Returns the sequence number of the last block that was published
     * before the most recent drop — the block the dropped audio would have
     * followed — or {@link #NO_DROP} while nothing has been dropped.
     * Readable from any thread.
     *
     * <p>Written by the producer in {@link #claim()}'s drop path, before
     * {@link #overflowCount()} advances, so a reader that observes the
     * advanced count also observes the marker of that drop (or of a later
     * one). A drop needs a full ring, so the marked block was
     * {@code capacity − 1} blocks ahead of the read index the producer
     * loaded, and is normally still queued when the marker is stored (the
     * flush service's fallback covers the case where it is not). Several
     * drops between two publishes share one marker; a drop after a later
     * publish moves it forward.</p>
     */
    @RealTimeSafe
    public long droppedAfterSequence() {
        return droppedAfterSequence.get();
    }

    /**
     * Counts {@code excess} frames of a delivered block that did not fit the
     * slot and were cut off. Audio-callback side; the callback is the only
     * writer, so this is a plain load followed by a release store like the
     * overflow counter. Non-positive values are ignored.
     *
     * @param excess frames beyond {@link #slotFrames()} in the delivered block
     */
    @RealTimeSafe
    public void noteTruncatedFrames(int excess) {
        if (excess > 0) {
            truncatedFrames.lazySet(truncatedFrames.get() + excess);
        }
    }

    /**
     * Returns how many delivered frames have been cut off because their
     * block was longer than {@link #slotFrames()}. Readable from any thread.
     */
    @RealTimeSafe
    public long truncatedFrames() {
        return truncatedFrames.get();
    }

    /** Returns the number of published-but-unreleased slots. Racy across threads by nature; fine for pacing and tests. */
    @RealTimeSafe
    public int size() {
        long h = head.get();
        long t = tail.get();
        return (int) Math.max(0, t - h);
    }

    /** Returns {@code true} when every published slot has been released. */
    @RealTimeSafe
    public boolean isEmpty() {
        return head.get() >= tail.get();
    }

    /** Returns the slot count (a power of two ≥ {@link #MIN_SLOTS}). */
    public int capacity() {
        return capacity;
    }

    /** Returns the frames each slot can hold. */
    public int slotFrames() {
        return slotFrames;
    }

    /** Returns the channel rows per source. */
    public int channelsPerSource() {
        return channelsPerSource;
    }

    /** Returns the number of sources per slot. */
    public int sourceCount() {
        return sourceCount;
    }

    /**
     * Returns the total number of blocks published so far (the producer
     * index). The flush service's {@code awaitFlushed} compares its applied
     * count against this value.
     */
    public long publishedBlocks() {
        return tail.get();
    }

    /** Returns the total number of blocks released so far (the consumer index). */
    public long releasedBlocks() {
        return head.get();
    }

    @Override
    public String toString() {
        return "CaptureRing[capacity=" + capacity + ", slotFrames=" + slotFrames
                + ", channelsPerSource=" + channelsPerSource + ", sources=" + sourceCount + "]";
    }

    /**
     * One preallocated ring slot: a primitives-only header plus the payload
     * rows. Written only by the producer between {@link CaptureRing#claim()}
     * and {@link CaptureRing#publish()}; read only by the consumer between
     * {@link CaptureRing#peek()} and {@link CaptureRing#release()}.
     *
     * <p>Every public method is a bounded read, a scalar store, or a bounded
     * copy — hence the type-level {@link RealTimeSafe}. Setters that take
     * counts <em>clamp</em> to the slot's shape rather than throw, so no
     * exception path is reachable from the callback for a source index
     * within {@link #sourceCount()} and a block whose rows are non-null;
     * source indices are not clamped, and an index outside that range or a
     * {@code null} row throws. The reflection sweeps of
     * {@code RealTimeSafeContractTest} do not reach this nested class (their
     * scanner skips {@code $} names); the same reflection-visible rules, and
     * the bytecode rule that no public method holds a monitor instruction,
     * are pinned by {@code CaptureRingTest}. The methods the recording
     * callback calls are walked by that suite's capture-path sentinel.</p>
     */
    @RealTimeSafe
    public static final class Slot {

        private final int slotFrames;
        private final int channelsPerSource;
        private final int sourceCount;
        /** {@code [source * channelsPerSource + channel][frame]}. */
        private final float[][] payload;
        /** {@code [source][channel]} views aliasing {@link #payload} rows — preallocated, never rebuilt. */
        private final float[][][] sourceViews;
        private final int[] sourceChannels;

        private long sequence = -1;
        private long startFrame;
        private double beatPosition;
        private int numFrames;
        private int truncatedFrames;
        private boolean punchEnabled;
        private long punchStartFrames;
        private long punchEndFrames;
        private boolean loopEnabled;

        private Slot(int slotFrames, int channelsPerSource, int sourceCount) {
            this.slotFrames = slotFrames;
            this.channelsPerSource = channelsPerSource;
            this.sourceCount = sourceCount;
            this.payload = new float[sourceCount * channelsPerSource][slotFrames];
            this.sourceViews = new float[sourceCount][channelsPerSource][];
            for (int s = 0; s < sourceCount; s++) {
                for (int ch = 0; ch < channelsPerSource; ch++) {
                    sourceViews[s][ch] = payload[s * channelsPerSource + ch];
                }
            }
            this.sourceChannels = new int[sourceCount];
        }

        /** Producer-side reset at claim time: header cleared, payload left stale. */
        private void reset(long sequence) {
            this.sequence = sequence;
            this.startFrame = 0;
            this.beatPosition = 0.0;
            this.numFrames = 0;
            this.truncatedFrames = 0;
            this.punchEnabled = false;
            this.punchStartFrames = 0;
            this.punchEndFrames = 0;
            this.loopEnabled = false;
            Arrays.fill(sourceChannels, 0);
        }

        /** Returns the ring sequence number assigned at claim time (0-based, monotonic). */
        public long sequence() {
            return sequence;
        }

        /** Returns the transport frame position of the first frame in this block. */
        public long startFrame() {
            return startFrame;
        }

        /** Sets the transport frame position of the first frame in this block. */
        public void setStartFrame(long startFrame) {
            this.startFrame = startFrame;
        }

        /** Returns the transport beat position stamped on the callback for this block. */
        public double beatPosition() {
            return beatPosition;
        }

        /** Sets the transport beat position for this block. */
        public void setBeatPosition(double beatPosition) {
            this.beatPosition = beatPosition;
        }

        /**
         * Returns the number of frames of the delivered block this slot
         * holds: the delivered count (which may be less than
         * {@link #slotFrames()}), or {@code slotFrames} for a block that was
         * longer — see {@link #truncatedFrames()}.
         */
        public int numFrames() {
            return numFrames;
        }

        /** Sets the frame count from the delivered one, clamped to {@code [0, slotFrames]}. */
        public void setNumFrames(int numFrames) {
            this.numFrames = Math.max(0, Math.min(slotFrames, numFrames));
        }

        /**
         * Returns how many frames of the delivered block were cut off
         * because it was longer than {@link #slotFrames()} ({@code 0} for a
         * block that fit). The kept frames are {@link #numFrames()}.
         */
        public int truncatedFrames() {
            return truncatedFrames;
        }

        /** Records how many delivered frames did not fit this slot; negative values are stored as {@code 0}. */
        public void setTruncatedFrames(int truncatedFrames) {
            this.truncatedFrames = Math.max(0, truncatedFrames);
        }

        /** Returns whether the transport punch region was enabled when this block was captured. */
        public boolean punchEnabled() {
            return punchEnabled;
        }

        /** Sets the punch-enabled snapshot. */
        public void setPunchEnabled(boolean punchEnabled) {
            this.punchEnabled = punchEnabled;
        }

        /** Returns the punch-in frame snapshot (meaningful when {@link #punchEnabled()}). */
        public long punchStartFrames() {
            return punchStartFrames;
        }

        /** Sets the punch-in frame snapshot. */
        public void setPunchStartFrames(long punchStartFrames) {
            this.punchStartFrames = punchStartFrames;
        }

        /** Returns the punch-out frame snapshot (meaningful when {@link #punchEnabled()}). */
        public long punchEndFrames() {
            return punchEndFrames;
        }

        /** Sets the punch-out frame snapshot. */
        public void setPunchEndFrames(long punchEndFrames) {
            this.punchEndFrames = punchEndFrames;
        }

        /** Returns whether transport looping was enabled when this block was captured. */
        public boolean loopEnabled() {
            return loopEnabled;
        }

        /** Sets the loop-enabled snapshot. */
        public void setLoopEnabled(boolean loopEnabled) {
            this.loopEnabled = loopEnabled;
        }

        /** Returns the number of sources this slot carries. */
        public int sourceCount() {
            return sourceCount;
        }

        /** Returns the channel rows per source. */
        public int channelsPerSource() {
            return channelsPerSource;
        }

        /** Returns the frames each channel row can hold. */
        public int slotFrames() {
            return slotFrames;
        }

        /** Returns how many channels of {@code source} are present in this block ({@code 0} = absent). */
        public int sourceChannels(int source) {
            return sourceChannels[source];
        }

        /** Records how many channels of {@code source} are present, clamped to {@code [0, channelsPerSource]}. */
        public void setSourceChannels(int source, int channels) {
            sourceChannels[source] = Math.max(0, Math.min(channelsPerSource, channels));
        }

        /** Marks {@code source} absent in this block. */
        public void clearSource(int source) {
            sourceChannels[source] = 0;
        }

        /**
         * Returns the channel rows of {@code source} — a preallocated view of
         * the slot's payload, {@code [channelsPerSource][slotFrames]}. Only
         * the first {@link #sourceChannels(int)} rows and the first
         * {@link #numFrames()} frames are meaningful.
         */
        public float[][] source(int source) {
            return sourceViews[source];
        }

        /** Returns one channel row of {@code source}. */
        public float[] channel(int source, int channel) {
            return sourceViews[source][channel];
        }

        /**
         * Copies {@code numFrames} frames of the first {@code channels} rows
         * of {@code block} into {@code source} and records the copied channel
         * count. Counts are clamped to the slot's shape and to the block's
         * actual row lengths; frames the block did not provide (up to the
         * clamped {@code numFrames}) are zeroed so the valid range never
         * carries stale audio. Does not touch the header's
         * {@link #numFrames()} — set that once for the whole block.
         *
         * @param source    source index
         * @param block     {@code [channel][frame]} samples; must not be null
         * @param channels  rows of {@code block} to copy
         * @param numFrames frames to copy
         */
        public void copySource(int source, float[][] block, int channels, int numFrames) {
            int rows = Math.max(0, Math.min(Math.min(channels, channelsPerSource), block.length));
            int frames = Math.max(0, Math.min(numFrames, slotFrames));
            float[][] view = sourceViews[source];
            for (int ch = 0; ch < rows; ch++) {
                float[] from = block[ch];
                float[] to = view[ch];
                int copied = Math.min(frames, from.length);
                System.arraycopy(from, 0, to, 0, copied);
                if (copied < frames) {
                    Arrays.fill(to, copied, frames, 0f);
                }
            }
            sourceChannels[source] = rows;
        }
    }
}
