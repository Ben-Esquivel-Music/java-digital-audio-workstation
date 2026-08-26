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
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;

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
 * {@code float[]} writes, {@link java.util.concurrent.atomic.AtomicLong}
 * {@code lazySet} release stores, and one increment of the {@code volatile
 * long} {@code renderedBlocksConsumed} counter — a plain load-add-store by
 * its single writer (the driver thread is the only mutator), so it is
 * wait-free and allocates nothing even though the store is volatile. No
 * allocation, no lock, no blocking call, no logging.</p>
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
 * <p>Story 312 converts the full {@code ASIOSampleType} matrix here:
 * {@code Int16}, packed 3-byte {@code Int24}, {@code Int32} — including the
 * right-justified {@code Int32×16/18/20/24} variants — and IEEE 754
 * {@code Float32} / {@code Float64}, in both byte orders. {@link AsioSampleType}
 * owns every layout fact; this class only decides <em>which</em> converter each
 * channel gets.</p>
 *
 * <p>The conversion locus is Java rather than the shim's native
 * {@code bufferSwitch} trampoline. That trampoline hands the JVM the driver's
 * raw buffer addresses and moves no samples at all, as {@code asioshim.cpp},
 * {@code asioshim.h} and {@code native/asio/README.md} all record; the shim is
 * also skipped by CMake in any checkout without the non-redistributable
 * Steinberg SDK, so conversion code placed there would compile — and be
 * tested — nowhere.</p>
 *
 * <p>Each channel's converter is resolved <em>once</em>, here at construction
 * time, from the {@code ASIOSampleType} the driver reported for that channel;
 * the callback thread never inspects a sample type. A channel whose reported
 * type this DAW cannot convert (a DSD layout, an unassigned code, or the
 * shim's {@code -1} "the driver refused to say") now fails
 * {@link AsioBackend#open(DeviceId, AudioFormat, int)} with an
 * {@link AudioBackendException} naming every offending channel, rather than
 * being silently mis-decoded or silenced.</p>
 */
final class AsioBufferSwitchShim implements AutoCloseable {

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

    /**
     * How long {@link #close()} and {@link #closeRetainingUpcallStub()} wait
     * for the drain thread to finish.
     */
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
    /**
     * Per-channel input converter, or {@code null} when the channel has no
     * usable driver buffer — the driver exposed none, or its address could not
     * be reinterpreted. Such a channel captures silence.
     */
    private final AsioSampleType[] inputTypes;
    /**
     * Per-channel output converter, or {@code null} when the channel has no
     * usable driver buffer. Such a channel is left completely untouched: there
     * is no buffer to write to.
     */
    private final AsioSampleType[] outputTypes;

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

    /**
     * How many input channels the driver actually handed usable buffers for
     * (story 316 review) — counted once, from the descriptors
     * {@code ASIOCreateBuffers} really produced, rather than from the count the
     * host asked for.
     *
     * <p>A channel counts only when {@link #bindDriverBuffers} bound a
     * converter to it, which is precisely the condition
     * {@link #copyInputChannel} uses to decide between decoding the driver's
     * buffer and writing silence. Anything else — a channel outside the opened
     * format, a descriptor whose {@code buffers[0]}/{@code buffers[1]} came
     * back {@link MemorySegment#NULL}, an address the FFM boundary refused to
     * reinterpret — captures silence, and counting it would be exactly the
     * false promise {@link AsioBackend#openedInputChannels()} exists to
     * prevent.</p>
     */
    private final int boundInputChannels;

    private final Arena arena;
    private final MemorySegment upcallStub;

    private volatile boolean streaming = true;
    private volatile boolean draining = true;
    private volatile boolean closed;

    /**
     * Rendered blocks the driver callback actually consumed (story 316
     * review): incremented by {@link #bufferSwitch(int, int)} exactly when
     * {@code outputRing.drainLatestInto} handed it an engine-rendered block,
     * never on the silence path. It is the observable proof that the output
     * bridge is alive — that audio written through {@link #write(AudioBlock)}
     * reached the driver's buffers — as opposed to the transport merely
     * advancing while the callback starved.
     *
     * <p>A plain {@code volatile long} with {@code ++} is real-time safe
     * here because the driver's callback thread is the <em>only</em>
     * writer. The increment compiles to a volatile load, an add and a
     * volatile store — no CAS retry loop, so it is wait-free with a bounded
     * instruction count; no lock, so it cannot block on a control thread;
     * and no object is created, so it cannot trigger an allocation or a GC
     * pause on the RT thread. The non-atomicity of the read-modify-write
     * costs nothing with one writer (no update can be lost), and the
     * volatile store still publishes each new value to the control / test
     * threads that read it — which is exactly why an {@code AtomicLong} or
     * {@code LongAdder} would buy nothing and a {@code LongAdder} would
     * additionally allocate cells.</p>
     *
     * <p>{@code RealTimeSafeContractTest} enforces only part of that.
     * Its bytecode sentinels scan {@link #bufferSwitch(int, int)}, and
     * every method it reaches inside this class, for <em>inline
     * publication</em> — invocations of {@code SubmissionPublisher},
     * {@code publishInput}, {@code submit} or {@code offer} — and for
     * <em>atomic read-modify-writes</em>, which is what would fail if this
     * counter were turned back into an {@code AtomicLong}; its other goals
     * check for {@code synchronized}, varargs and boxed types on
     * {@code @RealTimeSafe} methods. A counter that allocated or took a
     * lock through some other API would still pass all of them, so the
     * remainder of the field's RT-safety is a design property maintained
     * here, not one the sentinels enforce.</p>
     */
    private volatile long renderedBlocksConsumed;

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
     * @throws AudioBackendException when the driver reports an
     *                               {@code ASIOSampleType} this DAW cannot
     *                               convert for a channel it actually exposed
     *                               a buffer for
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
        this.inputTypes = new AsioSampleType[channels];
        this.outputTypes = new AsioSampleType[channels];
        for (int half = 0; half < HALVES; half++) {
            Arrays.fill(inputBuffers[half], MemorySegment.NULL);
            Arrays.fill(outputBuffers[half], MemorySegment.NULL);
        }
        // Load-bearing ordering: binding rejects an unconvertible channel by
        // throwing, and it runs BEFORE Arena.ofShared() and before
        // drainThread.start(). A rejected open therefore leaks neither the
        // upcall arena nor the drain thread, and AsioBackend#open's
        // catch (RuntimeException | Error) rolls back with bridge == null.
        bindDriverBuffers(bufferInfos);
        this.boundInputChannels = countBoundChannels(inputTypes);

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
     * Resolves each descriptor's {@code ASIOSampleType} and reinterprets its
     * raw {@code buffers[0]} / {@code buffers[1]} addresses into bounded
     * {@link MemorySegment} views sized for <em>that channel's</em> format.
     *
     * <p>Descriptors for channels the opened format does not cover are ignored,
     * and channels the driver never reported keep their
     * {@link MemorySegment#NULL} placeholder and a {@code null} converter — a
     * playback-only device simply captures silence, and an interface with fewer
     * outputs than the format asks for leaves the extra channels unwritten
     * (story 311, S1).</p>
     *
     * <p>The view's byte size is per channel, not global: a
     * {@code frames * 4} view over an {@code ASIOSTFloat64LSB} buffer would
     * cover only the first half of it and every access past
     * {@code frames / 2} would throw {@link IndexOutOfBoundsException} on the
     * driver's real-time thread.</p>
     *
     * @throws AudioBackendException when any channel the driver actually bound
     *                               buffers for reports a type this DAW cannot
     *                               convert
     */
    private void bindDriverBuffers(List<AsioStreamingShim.BufferInfo> bufferInfos) {
        StringBuilder unsupported = new StringBuilder();
        for (AsioStreamingShim.BufferInfo info : bufferInfos) {
            int channel = info.channel();
            if (channel < 0 || channel >= channels) {
                continue;
            }
            Optional<AsioSampleType> resolved = AsioSampleType.forCode(info.sampleType());
            // An unresolved type has no byte size, so the view is sized from
            // the widest container the SDK defines. It is never read: the
            // rejection below fails the open before any callback can run.
            int bytesPerSample = resolved.isPresent()
                    ? resolved.get().bytesPerSample()
                    : Long.BYTES;
            long byteSize = (long) bufferFrames * bytesPerSample;
            MemorySegment half0 = viewOf(info.buffer0(), byteSize);
            MemorySegment half1 = viewOf(info.buffer1(), byteSize);
            boolean bound = !half0.equals(MemorySegment.NULL)
                    && !half1.equals(MemorySegment.NULL);
            if (bound && resolved.isEmpty()) {
                // Only a channel we would actually touch can fail the open. A
                // descriptor with no usable buffer is never decoded or
                // encoded, so its type is irrelevant.
                if (!unsupported.isEmpty()) {
                    unsupported.append(", ");
                }
                unsupported.append(info.input() ? "input " : "output ")
                        .append(channel).append('=').append(info.sampleType());
            }
            if (info.input()) {
                inputBuffers[0][channel] = half0;
                inputBuffers[1][channel] = half1;
                inputTypes[channel] = bound ? resolved.orElse(null) : null;
            } else {
                outputBuffers[0][channel] = half0;
                outputBuffers[1][channel] = half1;
                outputTypes[channel] = bound ? resolved.orElse(null) : null;
            }
        }
        rejectUnsupportedSampleTypes(unsupported.toString());
    }

    /**
     * Counts the channels {@link #bindDriverBuffers} bound a converter to.
     *
     * <p>A {@code null} entry is a channel the driver exposed no usable buffer
     * for; {@link #copyInputChannel} writes silence for it and
     * {@link #copyOutputChannel} skips it entirely, so it is not a channel by
     * any measure the engine cares about.</p>
     *
     * @param types the per-channel converter table; must not be null
     * @return how many entries are non-null
     */
    private static int countBoundChannels(AsioSampleType[] types) {
        int bound = 0;
        for (AsioSampleType type : types) {
            if (type != null) {
                bound++;
            }
        }
        return bound;
    }

    /**
     * Builds a bounded view over a raw driver buffer address. The resulting
     * segment carries <em>no</em> alignment guarantee, which is why every
     * access uses a {@code JAVA_*_UNALIGNED} layout.
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
     * Fails the open for every {@code ASIOSampleType} this DAW cannot convert,
     * naming all of them in one message rather than reporting the first and
     * hiding the rest.
     *
     * <p>Mis-decoding is the alternative, and at a hardware boundary it is
     * loud: the driver would read the engine's float bytes as integers. The
     * story's Non-Goals require a clear {@link AudioBackendException}
     * instead.</p>
     */
    private static void rejectUnsupportedSampleTypes(String unsupportedTypes) {
        if (unsupportedTypes.isEmpty()) {
            return;
        }
        throw new AudioBackendException(
                "ASIO driver reported unsupported ASIOSampleType(s) [" + unsupportedTypes
                        + "]; this DAW converts Int16, Int24 (packed 3-byte) and Int32 "
                        + "— including the right-justified Int32x16/18/20/24 variants — "
                        + "plus Float32 and Float64, in both byte orders. DSD types and "
                        + "a driver that reports no type at all (-1) are not supported.");
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
            copyInputChannel(inputs[channel], inputTypes[channel],
                    incoming, channel, channelCount, frames);
        }
        // Drop-on-full rather than block: a stalled drain thread must never
        // hold up the driver. The unpark is non-blocking and allocation-free.
        inputRing.write(incoming, incoming.length);
        LockSupport.unpark(drainThread);

        if (outputRing.drainLatestInto(outputScratch)) {
            // Single-writer counter; the driver thread is the only mutator.
            renderedBlocksConsumed++;
        } else {
            // Nothing rendered yet this cycle — emit silence rather than
            // repeating the previous block.
            Arrays.fill(outputScratch, 0f);
        }
        MemorySegment[] outputs = outputBuffers[bufferIndex];
        for (int channel = 0; channel < channelCount; channel++) {
            copyOutputChannel(outputs[channel], outputTypes[channel],
                    outputScratch, channel, channelCount, frames);
        }
    }

    /**
     * Driver &rarr; engine copy for one channel: decodes the driver buffer
     * into a per-channel scratch array of normalised float32, then
     * de-interleaves it into the interleaved {@link AudioBlock} layout.
     *
     * <p>The decode is story 312's driver-side conversion seam.
     * {@link AsioSampleType#decode} branches on the channel's format once and
     * then runs a flat loop; for {@code ASIOSTFloat32LSB} on x64 it is still
     * the single bulk {@link MemorySegment#copy} story 311 established, which
     * replaces {@code frames} individually bounds-checked {@code get} calls
     * (8192 of them at 32&nbsp;channels &times; 128&nbsp;frames).</p>
     *
     * @param type the channel's converter, or {@code null} when the driver
     *             exposed no usable buffer — that channel captures silence
     */
    @RealTimeSafe
    private void copyInputChannel(MemorySegment source, AsioSampleType type,
                                  float[] destination, int channel,
                                  int channelCount, int frames) {
        if (type == null) {
            for (int frame = 0; frame < frames; frame++) {
                destination[frame * channelCount + channel] = 0f;
            }
            return;
        }
        float[] scratch = inputChannelScratch;
        type.decode(source, scratch, frames);
        for (int frame = 0; frame < frames; frame++) {
            destination[frame * channelCount + channel] = scratch[frame];
        }
    }

    /**
     * Engine &rarr; driver copy for one channel: interleaved scratch into a
     * per-channel scratch array, then one encode pass into the driver's
     * buffer. The encode counterpart of {@link #copyInputChannel}.
     *
     * @param type the channel's converter, or {@code null} when the driver
     *             exposed no usable buffer — there is nothing to write to, so
     *             that channel is skipped entirely
     */
    @RealTimeSafe
    private void copyOutputChannel(MemorySegment destination, AsioSampleType type,
                                   float[] source, int channel,
                                   int channelCount, int frames) {
        if (type == null) {
            // The driver exposes no output buffer for this channel (fewer
            // hardware outputs than the opened format asks for).
            return;
        }
        float[] scratch = outputChannelScratch;
        for (int frame = 0; frame < frames; frame++) {
            scratch[frame] = source[frame * channelCount + channel];
        }
        type.encode(scratch, destination, frames);
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
     * subscribers; it is stopped by {@link #close()} or by
     * {@link #closeRetainingUpcallStub()}, whichever ends the bridge.</p>
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
     * Returns {@code true} when the {@code sink(...)} &rarr; callback output
     * ring holds no block the driver has still to consume.
     *
     * <p>Emptiness rather than spare capacity is the question, because
     * {@link #bufferSwitch(int, int)} consumes the ring through
     * {@link AudioBlockRing#drainLatestInto(float[])}, which takes the
     * <em>whole</em> queue and plays only its newest block. A free slot
     * therefore does not mean the driver wants another block: a single
     * callback empties all {@value #OUTPUT_RING_SLOTS} slots at once, so a
     * spare-capacity gate would release the render pump four times per
     * callback — advancing playback and the transport at roughly four times
     * device time while three of every four rendered blocks were discarded
     * unheard. Only an <em>empty</em> ring proves that a {@code bufferSwitch}
     * actually consumed the block {@link #write(AudioBlock)} last queued.</p>
     *
     * <p>The device-clock pacing seam (story 316): {@link
     * AsioBackend#awaitSinkCapacity(long)} polls this from the engine's
     * render pump — a non-real-time thread — between blocks, so the pump
     * produces at exactly the rate {@link #bufferSwitch(int, int)} consumes.
     * Purely a read of the ring's occupancy counters; it adds no work to the
     * real-time {@code bufferSwitch} / {@code write} paths.</p>
     *
     * <p>Two consequences are worth naming. In steady state at most one
     * rendered block is ever in flight, so {@code drainLatestInto}'s discard
     * stops being the norm and becomes the overload valve it was meant to be:
     * it bites only when the pump ran ahead of a late callback. And the
     * pacing is still bounded from the other side — {@code awaitSinkCapacity}
     * parks for at most one block period whether or not this ever answers
     * {@code true}, which is what keeps the transport advancing at wall-clock
     * rate when no callback arrives at all.</p>
     */
    boolean outputRingDrained() {
        return outputRing.isEmpty();
    }

    /**
     * Returns how many engine-rendered blocks {@link #bufferSwitch(int, int)}
     * has drained into the driver's output buffers (story 316 review). Zero
     * until the first callback finds a {@link #write(AudioBlock)}-ed block
     * waiting; callbacks that emitted silence do not count. Surfaced through
     * {@link AsioBackend#renderedBlocksConsumedByDriver()} for the Windows
     * streaming proof in daw-core. A volatile read; adds nothing to the
     * real-time path.
     */
    long renderedBlocksConsumed() {
        return renderedBlocksConsumed;
    }

    /**
     * How many input channels this bridge will really publish audio for
     * (story 316 review) — the honest answer behind
     * {@link AsioBackend#openedInputChannels()}.
     *
     * <p>Zero once {@link #stopStreaming()} has quiesced the bridge, because a
     * quiesced bridge publishes nothing whatever its buffers say: that is the
     * state a {@code kAsioResetRequest} teardown leaves behind, and the
     * story-218 contract is that the consumer must {@code close()} and reopen.
     * Reporting the old count there would promise capture from a bridge that
     * has stopped reading the driver's buffers.</p>
     *
     * @return bound input channels while streaming, {@code 0} once quiesced
     */
    int boundInputChannels() {
        return streaming ? boundInputChannels : 0;
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
     * the upcall stub's arena. The caller must have CONFIRMED the uninstall
     * first — {@link AsioStreamingShim#uninstallBufferSwitchCallback()}
     * answering {@code true} — or a late driver callback would jump into
     * released memory; a teardown that could not confirm it must end the bridge
     * with {@link #closeRetainingUpcallStub()} instead. Idempotent.
     */
    @Override
    public void close() {
        shutDown(true);
    }

    /**
     * Ends the bridge exactly as {@link #close()} does — {@code closed} and
     * {@code streaming} latched, the {@code asio-input-drain} thread stopped
     * and joined after its final flush — but deliberately LEAKS the upcall
     * stub's arena for the life of the process. Idempotent, and mutually
     * exclusive with {@link #close()}: whichever runs first decides the
     * arena's fate.
     *
     * <p>This is the only safe ending for a teardown whose uninstall could not
     * be CONFIRMED, which {@code AsioBackend.tearDownStreaming(...)} reports by
     * returning what
     * {@link AsioStreamingShim#uninstallBufferSwitchCallback()} answered. A
     * {@code false} there means the call was not made at all, was refused on
     * arrival because an earlier downcall its caller stopped waiting for — by
     * budget or by interrupt — was still executing (so
     * {@link AsioControlThread#isQuiesced()} was false and every bounded
     * operation is rejected), did not complete within its own budget, was
     * interrupted, or failed at the FFM boundary. That method enumerates all
     * five; out here they cannot be told apart, and the vendor driver is a
     * party to none of them — it is never invoked by the uninstall (story 316
     * review, round 4). What they share is the only thing this method needs:
     * the shim's registered buffer-switch callback may still be this stub's
     * address, so the driver's next {@code bufferSwitch} — which enters the
     * shim's own trampoline and reads that pointer — may still reach it.
     * {@link #stopStreaming()} makes such a callback harmless, but only while
     * the stub is still mapped: freeing the arena unmaps it, and the next
     * callback jumps into released memory — a JVM crash whose native stack
     * names neither this class nor the driver.</p>
     *
     * <p>The leak is one upcall stub and its arena, bounded and static for the
     * life of the process, which is the same trade
     * {@code AsioBackend.tearDownStreaming(...)} already documents when it
     * calls the uninstall "the actual protection": a leaked stub costs memory,
     * a freed-but-installed stub costs the process.</p>
     */
    void closeRetainingUpcallStub() {
        shutDown(false);
    }

    /**
     * Shared body of {@link #close()} and {@link #closeRetainingUpcallStub()}.
     * Everything but the arena is identical, so one implementation keeps the
     * two endings from drifting apart, and latching {@code closed} first is
     * what makes both idempotent and mutually exclusive.
     *
     * @param releaseUpcallArena whether the upcall stub's arena may be freed;
     *                           {@code false} when the shim's registered
     *                           buffer-switch callback may still be the stub's
     *                           address, so a driver callback could still
     *                           reach it through the shim's trampoline
     */
    private void shutDown(boolean releaseUpcallArena) {
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
        if (!releaseUpcallArena) {
            return;
        }
        try {
            arena.close();
        } catch (Throwable ignored) {
            // Arena already closed, or closed from another thread — close()
            // stays idempotent and must never throw from a teardown path.
        }
    }
}
