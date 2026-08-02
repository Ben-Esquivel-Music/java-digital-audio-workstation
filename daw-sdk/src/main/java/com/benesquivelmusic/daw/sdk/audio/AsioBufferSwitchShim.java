package com.benesquivelmusic.daw.sdk.audio;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * FFM (JEP 454) upcall that bridges the ASIO driver's
 * {@code bufferSwitch(long bufferIndex, long directProcess)} host callback
 * into the engine (story 311): driver input buffers are de-interleaved into a
 * lock-free ring and republished on {@link AsioBackend#inputBlocks()} by a
 * dedicated drain thread, and the most recent block handed to
 * {@link AsioBackend#sink(AudioBlock)} is interleaved back out into the
 * driver's output buffers.
 *
 * <p>{@link AsioFormatChangeShim} is the canonical reference for the upcall
 * stub construction, the {@link Arena#ofShared()} rationale (the driver calls
 * back from its own thread, so a confined arena would be illegal), and the
 * "never let an exception propagate back into native code" wrapper.</p>
 *
 * <h2>Real-time discipline</h2>
 * <p>Everything {@link #bufferSwitch(int, int)} touches — the reinterpreted
 * {@link MemorySegment} views of the driver's double buffers, the per-channel
 * and interleaved scratch arrays, and both {@link AudioBlockRing}s — is
 * allocated in the constructor, on the calling (control) thread. The callback
 * body therefore performs only {@code MemorySegment} bulk copies,
 * {@code float[]} writes and {@link java.util.concurrent.atomic.AtomicLong}
 * {@code lazySet} release stores: no allocation, no lock, no blocking call, no
 * logging.</p>
 *
 * <h2>Off-thread input marshalling</h2>
 * <p>{@code SubmissionPublisher.offer(...)} acquires a
 * {@link java.util.concurrent.locks.ReentrantLock} and may allocate, which the
 * {@link RealTimeSafe} contract forbids outright. The callback therefore never
 * publishes: it de-interleaves into {@code inputRing} and hands off to the
 * dedicated {@code asio-input-drain} daemon platform thread, which allocates a
 * fresh {@link AudioBlock} per drained slot and calls
 * {@link AudioBackendSupport#publishInput(AudioBlock)}. Because each published
 * block owns a private sample array, {@link AudioBlock}'s documented
 * immutability holds and subscribers need not copy.</p>
 *
 * <p>The hand-off signal is {@link LockSupport#unpark(Thread)} — the only
 * cross-thread call the callback makes. It allocates nothing and returns
 * whether or not the target is parked; a permit issued before the drain thread
 * parks simply makes the next park return at once, so a wake-up can never be
 * lost. Precisely stated, {@code unpark} is a <em>bounded</em> OS signalling
 * primitive rather than a strictly lock-free one: on HotSpot/Windows — this
 * project's target — {@code Parker::unpark} is a {@code SetEvent} on an event
 * handle, while on some other platforms it briefly takes an internal OS mutex
 * shared only with the target thread's own park. It is nevertheless the right
 * primitive here, because unlike {@code SubmissionPublisher}'s
 * {@link java.util.concurrent.locks.ReentrantLock} it holds nothing across user
 * code and so can never be blocked by a slow subscriber. The drain thread
 * parks with a bounded timeout so it neither busy-spins at 100&nbsp;% CPU nor
 * can stall if an unpark is ever missed. It consumes the ring strictly
 * <em>in order</em> ({@link AudioBlockRing#readInto(float[])}), because
 * recorded audio must stay contiguous.</p>
 *
 * <h2>Sample format</h2>
 * <p>Story 311 implements {@code ASIOSTFloat32LSB} ({@value #ASIOST_FLOAT32_LSB})
 * only; generalising the conversion is story 312's job. Channels whose driver
 * reported any other {@code ASIOSampleType} are zero-filled on input
 * <em>and</em> on output — leaving the driver's own output bytes in place would
 * replay them every block as a fixed-pitch buzz. The unsupported types are
 * logged once at construction time, never from the callback thread.</p>
 */
final class AsioBufferSwitchShim implements AutoCloseable {

    private static final Logger LOG =
            Logger.getLogger(AsioBufferSwitchShim.class.getName());

    /** {@code ASIOSTFloat32LSB} — the only sample type story 311 converts. */
    static final int ASIOST_FLOAT32_LSB = 19;

    /** Bytes per {@code ASIOSTFloat32LSB} sample. */
    private static final int BYTES_PER_SAMPLE = 4;

    /** Name of the daemon thread that marshals captured blocks off the RT thread. */
    static final String DRAIN_THREAD_NAME = "asio-input-drain";

    /**
     * Slots in the callback &rarr; {@code asio-input-drain} hand-off ring.
     * Sized generously: the drain thread only has to keep up on average, and
     * a deeper ring absorbs a scheduling hiccup without losing captured audio.
     */
    private static final int INPUT_RING_SLOTS = 32;

    /** Slots in the {@code sink(...)} &rarr; callback hand-off ring. */
    private static final int OUTPUT_RING_SLOTS = 4;

    /** Backstop park interval for the drain thread when the ring runs dry. */
    private static final long DRAIN_PARK_NANOS = 20_000_000L; // 20 ms

    /** How long {@link #close()} waits for the drain thread to finish. */
    private static final long DRAIN_SHUTDOWN_TIMEOUT_MILLIS = 2_000L;

    /** ASIO's double-buffer index is always 0 or 1. */
    private static final int HALVES = 2;

    /**
     * Windows ASIO callback descriptor. The SDK uses C {@code long}, which is
     * fixed at 32 bits on Win64 and therefore maps to {@code JAVA_INT}, not
     * Java's 64-bit {@code long}.
     */
    private static final FunctionDescriptor ASIO_BUFFER_SWITCH =
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);

    private final AudioBackendSupport support;
    private final double sampleRate;
    private final int channels;
    private final int bufferFrames;

    /** Driver input buffers indexed {@code [half][channel]}. */
    private final MemorySegment[][] inputBuffers;
    /** Driver output buffers indexed {@code [half][channel]}. */
    private final MemorySegment[][] outputBuffers;
    /** Whether channel {@code c} has a usable float32 input buffer. */
    private final boolean[] inputReady;
    /** Whether the driver exposed any output buffer at all for channel {@code c}. */
    private final boolean[] outputBound;
    /** Whether channel {@code c} has a usable float32 output buffer. */
    private final boolean[] outputReady;

    /** Interleaved capture staging buffer; callback thread only. */
    private final float[] inputScratch;
    /** Per-channel capture staging buffer; callback thread only. */
    private final float[] inputChannelScratch;
    /** Interleaved playback staging buffer; callback thread only. */
    private final float[] outputScratch;
    /** Per-channel playback staging buffer; callback thread only. */
    private final float[] outputChannelScratch;
    /** Interleaved staging buffer; {@code asio-input-drain} thread only. */
    private final float[] drainScratch;

    private final AudioBlockRing inputRing;
    private final AudioBlockRing outputRing;
    private final Thread drainThread;

    private final Arena arena;
    private final MemorySegment upcallStub;

    private volatile boolean streaming = true;
    private volatile boolean draining = true;
    private volatile boolean closed;

    /**
     * Pre-allocates every buffer, view and staging array the callback needs,
     * builds the FFM upcall stub, and starts the {@code asio-input-drain}
     * thread. Runs on the calling (control) thread — never on the driver's
     * real-time thread.
     *
     * @param support      the backend support that owns the input publisher
     *                     and the open flag; must not be null
     * @param format       the opened format; its channel count defines the
     *                     active channel set; must not be null
     * @param bufferFrames the negotiated buffer size in sample frames; must be positive
     * @param bufferInfos  the descriptors returned by
     *                     {@link AsioStreamingShim#getBufferInfos()}; must not be null
     */
    AsioBufferSwitchShim(AudioBackendSupport support, AudioFormat format,
                         int bufferFrames, List<AsioStreamingShim.BufferInfo> bufferInfos) {
        this.support = Objects.requireNonNull(support, "support must not be null");
        Objects.requireNonNull(format, "format must not be null");
        Objects.requireNonNull(bufferInfos, "bufferInfos must not be null");
        if (bufferFrames <= 0) {
            throw new IllegalArgumentException(
                    "bufferFrames must be positive: " + bufferFrames);
        }
        this.sampleRate = format.sampleRate();
        this.channels = format.channels();
        this.bufferFrames = bufferFrames;
        this.inputBuffers = new MemorySegment[HALVES][channels];
        this.outputBuffers = new MemorySegment[HALVES][channels];
        this.inputReady = new boolean[channels];
        this.outputBound = new boolean[channels];
        this.outputReady = new boolean[channels];
        for (int half = 0; half < HALVES; half++) {
            Arrays.fill(inputBuffers[half], MemorySegment.NULL);
            Arrays.fill(outputBuffers[half], MemorySegment.NULL);
        }
        long byteSize = (long) bufferFrames * BYTES_PER_SAMPLE;
        String unsupportedTypes = bindDriverBuffers(bufferInfos, byteSize);
        warnAboutUnsupportedSampleTypes(unsupportedTypes);

        int samplesPerBlock = channels * bufferFrames;
        this.inputScratch = new float[samplesPerBlock];
        this.inputChannelScratch = new float[bufferFrames];
        this.outputScratch = new float[samplesPerBlock];
        this.outputChannelScratch = new float[bufferFrames];
        this.drainScratch = new float[samplesPerBlock];
        this.inputRing = new AudioBlockRing(INPUT_RING_SLOTS, samplesPerBlock);
        this.outputRing = new AudioBlockRing(OUTPUT_RING_SLOTS, samplesPerBlock);

        // The driver invokes this upcall from its own callback thread, so the
        // stub's arena must be shared rather than confined.
        this.arena = Arena.ofShared();
        this.upcallStub = buildUpcallStub();

        this.drainThread = Thread.ofPlatform()
                .name(DRAIN_THREAD_NAME)
                .daemon(true)
                .unstarted(this::drainLoop);
        // Started last: Thread.start() publishes every field written above.
        drainThread.start();
    }

    /**
     * Reinterprets each descriptor's raw {@code buffers[0]} / {@code buffers[1]}
     * addresses into bounded {@link MemorySegment} views.
     *
     * <p>Descriptors for channels the opened format does not cover are ignored,
     * and channels the driver never reported keep their
     * {@link MemorySegment#NULL} placeholder — a playback-only device simply
     * captures silence, and an interface with fewer outputs than the format
     * asks for leaves the extra channels unwritten (story 311, S1).</p>
     *
     * @return a comma-separated list of the {@code ASIOSampleType}s story 311
     *         cannot convert, or an empty string when every channel is float32
     */
    private String bindDriverBuffers(List<AsioStreamingShim.BufferInfo> bufferInfos,
                                     long byteSize) {
        StringBuilder unsupported = new StringBuilder();
        for (AsioStreamingShim.BufferInfo info : bufferInfos) {
            int channel = info.channel();
            if (channel < 0 || channel >= channels) {
                continue;
            }
            boolean supported = info.sampleType() == ASIOST_FLOAT32_LSB;
            if (!supported) {
                if (!unsupported.isEmpty()) {
                    unsupported.append(", ");
                }
                unsupported.append(info.input() ? "input " : "output ")
                        .append(channel).append('=').append(info.sampleType());
            }
            MemorySegment half0 = viewOf(info.buffer0(), byteSize);
            MemorySegment half1 = viewOf(info.buffer1(), byteSize);
            boolean bound = !half0.equals(MemorySegment.NULL)
                    && !half1.equals(MemorySegment.NULL);
            if (info.input()) {
                inputBuffers[0][channel] = half0;
                inputBuffers[1][channel] = half1;
                inputReady[channel] = bound && supported;
            } else {
                outputBuffers[0][channel] = half0;
                outputBuffers[1][channel] = half1;
                outputBound[channel] = bound;
                outputReady[channel] = bound && supported;
            }
        }
        return unsupported.toString();
    }

    /**
     * Builds a bounded view over a raw driver buffer address. The resulting
     * segment carries <em>no</em> alignment guarantee, which is why every
     * access uses {@code JAVA_FLOAT_UNALIGNED}.
     */
    private static MemorySegment viewOf(long address, long byteSize) {
        if (address == 0L) {
            return MemorySegment.NULL;
        }
        try {
            return MemorySegment.ofAddress(address).reinterpret(byteSize);
        } catch (Throwable ignored) {
            // Restricted-method access denied (no --enable-native-access) or a
            // bogus address: treat the channel as absent rather than fail open.
            return MemorySegment.NULL;
        }
    }

    /**
     * Logs the unsupported {@code ASIOSampleType}s exactly once, here at
     * construction time. The callback thread must never log.
     */
    private static void warnAboutUnsupportedSampleTypes(String unsupportedTypes) {
        if (unsupportedTypes.isEmpty()) {
            return;
        }
        LOG.log(Level.WARNING,
                "ASIO driver reported unsupported ASIOSampleType(s) [" + unsupportedTypes
                        + "]; story 311 converts ASIOSTFloat32LSB (" + ASIOST_FLOAT32_LSB
                        + ") only, so those channels are silenced until story 312 "
                        + "(ASIO sample-format conversion) generalises the copy boundary.");
    }

    private MemorySegment buildUpcallStub() {
        try {
            MethodHandle handle = MethodHandles.lookup().findVirtual(
                    AsioBufferSwitchShim.class,
                    "bufferSwitchUpcall",
                    MethodType.methodType(void.class, int.class, int.class))
                    .bindTo(this);
            return Linker.nativeLinker().upcallStub(handle, ASIO_BUFFER_SWITCH, arena);
        } catch (Throwable t) {
            // Linker/method-handle wiring should never fail on a supported
            // JVM, but if it does we degrade to a null stub rather than break
            // open() — story 311 requires graceful degradation everywhere.
            return MemorySegment.NULL;
        }
    }

    /**
     * Method-handle target bound into the FFM upcall stub. The JVM calls it
     * from the driver's real-time callback thread.
     */
    @SuppressWarnings("unused") // invoked reflectively via the upcall stub
    private void bufferSwitchUpcall(int bufferIndex, int directProcess) {
        try {
            bufferSwitch(bufferIndex, directProcess);
        } catch (Throwable ignored) {
            // Never let an exception propagate back into native code — the
            // ASIO driver has no way to recover from a Java stack unwind.
        }
    }

    /**
     * The story's real-time bridge: de-interleave the driver's input half into
     * the capture ring and wake the drain thread, then interleave the most
     * recent {@code sink(...)} block into the driver's output half.
     *
     * <p>Package-private so unit tests can drive synthetic callbacks directly,
     * exactly the way {@link AsioFormatChangeShim#dispatch(long, long)} is
     * tested. Allocation-free, lock-free, blocking-free and log-free — see the
     * "Real-time discipline" and "Off-thread input marshalling" notes on the
     * class.</p>
     *
     * <p>{@code ASIOOutputReady()} is deliberately <em>not</em> called from
     * here: the native trampoline calls it after this upcall returns, which
     * keeps the downcall out of the JVM upcall.</p>
     *
     * @param bufferIndex  ASIO's double-buffer half, 0 or 1
     * @param directProcess ASIO's {@code directProcess} hint; unused — the
     *                      bridge always processes inline
     */
    @RealTimeSafe
    void bufferSwitch(int bufferIndex, int directProcess) {
        if (!streaming) {
            return;
        }
        if (bufferIndex < 0 || bufferIndex >= HALVES) {
            return;
        }
        int channelCount = channels;
        int frames = bufferFrames;

        float[] incoming = inputScratch;
        MemorySegment[] inputs = inputBuffers[bufferIndex];
        for (int channel = 0; channel < channelCount; channel++) {
            copyInputChannel(inputs[channel], inputReady[channel],
                    incoming, channel, channelCount, frames);
        }
        // Drop-on-full rather than block: a stalled drain thread must never
        // hold up the driver. The unpark is non-blocking and allocation-free.
        inputRing.write(incoming, incoming.length);
        LockSupport.unpark(drainThread);

        if (!outputRing.drainLatestInto(outputScratch)) {
            // Nothing rendered yet this cycle — emit silence rather than
            // repeating the previous block.
            Arrays.fill(outputScratch, 0f);
        }
        MemorySegment[] outputs = outputBuffers[bufferIndex];
        for (int channel = 0; channel < channelCount; channel++) {
            copyOutputChannel(outputs[channel], outputBound[channel],
                    outputReady[channel], outputScratch, channel, channelCount, frames);
        }
    }

    /**
     * Driver &rarr; engine copy for one channel: bulk-reads the per-channel
     * driver buffer into a scratch array, then de-interleaves it into the
     * interleaved {@link AudioBlock} layout.
     *
     * <p>One {@link MemorySegment#copy} per channel replaces {@code frames}
     * individually bounds-checked {@code get} calls (8192 of them at
     * 32&nbsp;channels &times; 128&nbsp;frames) and is still allocation-free.</p>
     *
     * <p>story 312: this is one of the two sample-format conversion seams.
     * Story 311 only decodes {@code ASIOSTFloat32LSB}; every other
     * {@code ASIOSampleType} — and every channel the driver did not expose —
     * arrives here with {@code ready == false} and is silenced. Story 312
     * generalises the decode based on the per-channel {@code ASIOSampleType}
     * captured at construction.</p>
     */
    @RealTimeSafe
    private void copyInputChannel(MemorySegment source, boolean ready,
                                  float[] destination, int channel,
                                  int channelCount, int frames) {
        if (!ready) {
            for (int frame = 0; frame < frames; frame++) {
                destination[frame * channelCount + channel] = 0f;
            }
            return;
        }
        float[] scratch = inputChannelScratch;
        // JAVA_FLOAT_UNALIGNED: a reinterpreted native segment carries no
        // alignment guarantee, and the aligned layout would throw
        // IllegalArgumentException on a misaligned driver buffer.
        MemorySegment.copy(source, ValueLayout.JAVA_FLOAT_UNALIGNED, 0L,
                scratch, 0, frames);
        for (int frame = 0; frame < frames; frame++) {
            destination[frame * channelCount + channel] = scratch[frame];
        }
    }

    /**
     * Engine &rarr; driver copy for one channel: interleaved scratch into a
     * per-channel scratch array, then one bulk {@link MemorySegment#copy} into
     * the driver's buffer.
     *
     * <p>story 312: the encode counterpart of {@link #copyInputChannel}. Story
     * 311 only encodes {@code ASIOSTFloat32LSB}; a channel of any other
     * {@code ASIOSampleType} has its driver buffer <strong>zero-filled</strong>
     * rather than skipped. A zero byte pattern is silence for every integer PCM
     * and float {@code ASIOSampleType}, whereas leaving the driver's bytes
     * alone would replay them every block as a fixed-pitch buzz. Story 312
     * generalises the encode.</p>
     */
    @RealTimeSafe
    private void copyOutputChannel(MemorySegment destination, boolean bound,
                                   boolean ready, float[] source, int channel,
                                   int channelCount, int frames) {
        if (!bound) {
            // The driver exposes no output buffer for this channel (fewer
            // hardware outputs than the opened format asks for).
            return;
        }
        if (!ready) {
            destination.fill((byte) 0);
            return;
        }
        float[] scratch = outputChannelScratch;
        for (int frame = 0; frame < frames; frame++) {
            scratch[frame] = source[frame * channelCount + channel];
        }
        MemorySegment.copy(scratch, 0, destination,
                ValueLayout.JAVA_FLOAT_UNALIGNED, 0L, frames);
    }

    /**
     * Body of the {@code asio-input-drain} daemon thread: consumes the capture
     * ring in order, allocates one {@link AudioBlock} per drained slot and
     * publishes it. Allocation here is deliberate and safe — this is not the
     * driver's real-time thread.
     */
    private void drainLoop() {
        float[] scratch = drainScratch;
        while (draining) {
            boolean progressed = false;
            while (inputRing.readInto(scratch)) {
                progressed = true;
                publishDrained(scratch);
            }
            if (!progressed) {
                // Bounded park: the callback's unpark normally wakes us
                // immediately, and the timeout is a backstop that also keeps
                // close() responsive without a busy-spin.
                LockSupport.parkNanos(this, DRAIN_PARK_NANOS);
            }
        }
        // Final sweep so blocks captured just before close() still reach
        // subscribers that are still attached.
        while (inputRing.readInto(scratch)) {
            publishDrained(scratch);
        }
    }

    private void publishDrained(float[] scratch) {
        try {
            support.publishInput(new AudioBlock(
                    sampleRate, channels, bufferFrames, scratch.clone()));
        } catch (Throwable ignored) {
            // A publisher closed concurrently with this drain, or a
            // misbehaving drop handler, must never kill the drain thread —
            // the next callback still has to be delivered.
        }
    }

    /**
     * Hands the engine's rendered block to the callback thread. Called from
     * {@link AsioBackend#sink(AudioBlock)} on the render thread; drops the
     * block (rather than blocking) when the ring is full.
     *
     * @param block the rendered output block; must not be null
     */
    @RealTimeSafe
    void write(AudioBlock block) {
        Objects.requireNonNull(block, "block must not be null");
        if (!streaming) {
            return;
        }
        float[] samples = block.samples();
        // A frame-count mismatch is rejected by AsioBackend#sink before it
        // reaches here; the ring's own clamp is the last-resort guard so the
        // render thread never takes an exception.
        outputRing.write(samples, samples.length);
    }

    /**
     * Quiesces the bridge: no further callback touches the driver's buffers.
     * A plain volatile write, so it is safe to call from the driver's own
     * callback thread during a reset request (story 218 &times; 311).
     * Idempotent.
     *
     * <p>The drain thread keeps running so already-captured blocks still reach
     * subscribers; it is stopped by {@link #close()}.</p>
     */
    void stopStreaming() {
        streaming = false;
    }

    /** Returns {@code true} while callbacks are still bridged into the engine. */
    boolean isStreaming() {
        return streaming;
    }

    /**
     * Returns the address of the upcall stub bound to ASIO's
     * {@code bufferSwitch} entrypoint. Exposed for
     * {@link AsioStreamingShim#installBufferSwitchCallback(MemorySegment)}
     * and for testing.
     *
     * @return the stub's address (never null; {@link MemorySegment#NULL} if
     *         construction failed)
     */
    MemorySegment upcallStub() {
        return upcallStub;
    }

    /** Returns the negotiated buffer size in sample frames. */
    int bufferFrames() {
        return bufferFrames;
    }

    /**
     * Test seam: the {@code asio-input-drain} daemon thread that marshals
     * captured blocks off the driver's real-time thread. Never null.
     */
    Thread drainThread() {
        return drainThread;
    }

    /**
     * Quiesces the bridge, stops the {@code asio-input-drain} thread and frees
     * the upcall stub's arena. The caller must have uninstalled the callback
     * first, or a late driver callback would jump into released memory.
     * Idempotent.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        streaming = false;
        draining = false;
        LockSupport.unpark(drainThread);
        try {
            drainThread.join(DRAIN_SHUTDOWN_TIMEOUT_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        try {
            arena.close();
        } catch (Throwable ignored) {
            // Arena already closed, or closed from another thread — close()
            // stays idempotent and must never throw from a teardown path.
        }
    }
}
