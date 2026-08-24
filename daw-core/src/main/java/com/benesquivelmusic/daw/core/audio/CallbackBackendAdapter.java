package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioBackendException;
import com.benesquivelmusic.daw.sdk.audio.AudioBlock;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.AudioStreamConfig;
import com.benesquivelmusic.daw.sdk.audio.BufferSize;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.NativeAudioBackend;
import com.benesquivelmusic.daw.sdk.audio.RoundTripLatency;
import com.benesquivelmusic.daw.sdk.audio.SampleRate;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Adapts a legacy callback-driven {@link NativeAudioBackend} (production
 * wrappee: {@code PortAudioBackend}) behind the SDK {@link AudioBackend}
 * interface so the engine streams through ONE seam for every backend
 * (story 316, design book &sect;4.2).
 *
 * <p>The legacy backend pulls audio by invoking a callback on <em>its</em>
 * audio thread — PortAudio's is a real RT thread. The SDK interface pushes:
 * the render pump calls {@link #sink(AudioBlock)}. The adapter bridges the two
 * with a pair of lock-free, allocation-free {@link InterleavedBlockRing}s:</p>
 * <ul>
 *   <li><strong>Output</strong> — {@code sink} (non-RT pump thread) copies
 *       interleaved samples into the output ring; the device callback
 *       de-interleaves one slot per invocation, in order, and plays silence
 *       when the ring runs dry. {@link #awaitSinkCapacity(long)} polls the
 *       ring's occupancy on the pump side, which is what paces the pump by
 *       the device clock.</li>
 *   <li><strong>Input</strong> — the device callback interleaves captured
 *       samples into the input ring (drop-on-full, counted); a daemon
 *       {@value #DRAIN_THREAD_NAME} thread drains it in order and publishes
 *       {@link AudioBlock}s through this adapter's own
 *       {@link SubmissionPublisher}. The RT callback never touches the
 *       publisher — its only cross-thread call is
 *       {@link LockSupport#unpark(Thread)} (the {@code asio-input-drain}
 *       precedent).</li>
 * </ul>
 *
 * <h2>Device identity (&sect;3.2)</h2>
 * <p>{@link #open(DeviceId, com.benesquivelmusic.daw.sdk.audio.AudioFormat, int)}
 * resolves the device NAME against a fresh enumeration snapshot on every
 * open. {@link DeviceId#isDefault()} resolves through the driver's real
 * default-device query ({@link NativeAudioBackend#getDefaultOutputDevice()}
 * — PortAudio's {@code Pa_GetDefaultOutputDevice}), never index&nbsp;0. A
 * name that no longer enumerates is a visible {@link AudioBackendException}.
 * The resolved index is used only within that one snapshot.</p>
 *
 * <h2>Input never kills playback</h2>
 * <p>Capture problems degrade, they never fail the open (capture truth is
 * story 326): an input device that fails to resolve disables input with a
 * warning; the requested input channel count is clamped to the resolved
 * input device's {@code maxInputChannels} (a mono mic never fails a stereo
 * open); and when the driver still refuses the duplex open, the open is
 * retried once output-only before the failure is allowed to propagate.</p>
 *
 * <h2>Side-output channel writes are counted, not routed</h2>
 * <p>{@link #writeToChannel(int, float[])} cannot yet address individual
 * physical output channels — the rings carry whole interleaved mix blocks —
 * so the metronome click's side output and the cue-bus contributions are
 * dropped and counted in {@link #droppedChannelWrites()} rather than
 * silently discarded by the interface's inherited no-op default (story 316
 * review); the real hardware routing belongs to existing stories 136 and
 * 135.</p>
 *
 * <h2>Open and close are UNBOUNDED, and they run under the engine's
 * lifecycle lock (story 316 re-review)</h2>
 * <p>{@link #open(DeviceId, com.benesquivelmusic.daw.sdk.audio.AudioFormat, int)}
 * calls {@code PortAudioBackend.openStream} and {@code startStream}, which
 * issue {@code Pa_OpenStream} and {@code Pa_StartStream} as UNTIMED FFM
 * downcalls on the CALLING thread. {@link #close()} does the same with
 * {@code Pa_StopStream} / {@code Pa_CloseStream}. All of them are invoked
 * from inside {@code AudioEngine}'s {@code lifecycleLock}, and the
 * application's Play handler calls {@code AudioEngine.startAudioOutput()}
 * from the JavaFX application thread. A device whose driver wedges in any of
 * those calls therefore stalls EVERY lifecycle transition in the application
 * — Play, Stop, the device-event reopen worker, shutdown — and freezes the
 * UI with them. There is no timeout anywhere on this path.</p>
 *
 * <p>ASIO's equivalent hazard was closed in the same review by giving
 * {@code AsioControlThread} a per-downcall budget. That was affordable
 * because ASIO ALREADY had a dedicated control thread (COM apartment
 * affinity forced one), so bounding it meant adding a timeout to an existing
 * {@code Future.get()}. PortAudio has no such thread, and giving it one is
 * not a wrapper — it is a subsystem:</p>
 * <ol>
 *   <li>{@code PortAudioBackend.openStream} allocates the stream's
 *       {@link java.lang.foreign.Arena#ofConfined() confined} arena and
 *       {@code closeStream} closes it. A confined arena may only be closed by
 *       the thread that opened it, so moving the OPEN onto a control thread
 *       forces the close, and every other access to that arena, onto the same
 *       thread. The callback upcall stub lives in that arena too.</li>
 *   <li>The same {@code PortAudioBackend} instance answers
 *       {@code Pa_Initialize}, device enumeration and the default-device
 *       queries, which the application deliberately runs on a BACKGROUND
 *       worker so the settings dialog does not block. Funnelling only the
 *       stream calls onto a control thread would split one native library
 *       across two threads; funnelling all of them would mean a wedged open
 *       also wedges device enumeration — a behaviour change well outside the
 *       lock this was meant to protect.</li>
 *   <li>PortAudio's Windows host APIs initialize COM on the calling thread,
 *       so "which thread" is a correctness question for the whole backend,
 *       not a detail of the open call.</li>
 * </ol>
 * <p>So the exposure is DOCUMENTED rather than half-fixed. A real fix is a
 * story of its own: a {@code PortAudioControlThread} that owns initialize,
 * enumerate, open, start, stop, close and terminate, with a bounded wait on
 * each and an enumeration path that tolerates the queue; plus the honest
 * failure message and the fallback-ladder behaviour that
 * {@code AsioControlThread} already models. Until then, treat any PortAudio
 * downcall as capable of blocking a lifecycle transition indefinitely.</p>
 */
public final class CallbackBackendAdapter implements AudioBackend {

    private static final Logger LOG =
            Logger.getLogger(CallbackBackendAdapter.class.getName());

    /** Name of the daemon thread that marshals captured blocks off the RT thread. */
    static final String DRAIN_THREAD_NAME = "native-input-drain";

    /** Slots in the pump &rarr; callback output hand-off ring. */
    private static final int OUTPUT_RING_SLOTS = 4;

    /** Slots in the callback &rarr; drain input hand-off ring. */
    private static final int INPUT_RING_SLOTS = 32;

    /** Backstop park interval for the drain thread when the ring runs dry. */
    private static final long DRAIN_PARK_NANOS = 20_000_000L; // 20 ms

    /** How long {@link #close()} waits for the drain thread to finish. */
    private static final long DRAIN_SHUTDOWN_TIMEOUT_MILLIS = 2_000L;

    private final NativeAudioBackend delegate;
    private final String inputDeviceName;

    private boolean initialized;
    private volatile boolean open;

    // Stream shape — written on open() (lifecycle thread), read by the
    // device callback and drain threads; published via the volatile rings.
    private int outChannels;
    private int inChannels;
    private int bufferFrames;
    private double openedSampleRate;

    private volatile InterleavedBlockRing outputRing;
    private volatile InterleavedBlockRing inputRing;

    // Callback-thread-only scratch (pre-allocated at open).
    private float[] outScratch;
    private float[] inScratch;
    // Drain-thread-only scratch.
    private float[] drainScratch;

    private Thread drainThread;
    private volatile boolean draining;

    private volatile SubmissionPublisher<AudioBlock> inputPublisher =
            new SubmissionPublisher<>();

    // Written by the ENGINE render thread (sink / writeToChannel), never by
    // the device callback — an atomic read-modify-write is fine off the
    // driver's real-time thread.
    private final AtomicLong droppedOutputBlocks = new AtomicLong();
    private final AtomicLong droppedChannelWrites = new AtomicLong();

    /**
     * Captured input blocks dropped because the input ring was full.
     *
     * <p>A plain {@code volatile long} with {@code ++} rather than an
     * {@link AtomicLong} (story 316 review), because
     * {@link #deviceCallback(float[][], float[][], int)} — the ONLY writer —
     * runs on the device's real-time thread. {@code
     * AtomicLong.incrementAndGet} is a CAS retry loop: its worst-case
     * iteration count is unbounded under contention, so it is not wait-free
     * and does not belong on a driver callback thread. The plain increment
     * compiles to a volatile load, an add and a volatile store — bounded
     * instruction count, no lock, no allocation — and with a single writer
     * the non-atomic read-modify-write can lose nothing, while the volatile
     * store still publishes each value to the control / test threads that
     * read it through {@link #droppedInputBlocks()}.</p>
     *
     * <p>Same idiom, same reasoning, as
     * {@code AsioBufferSwitchShim.renderedBlocksConsumed}. The two sibling
     * counters above stay {@code AtomicLong}: they are written by the engine
     * render thread, which is not the driver's callback thread.</p>
     */
    private volatile long droppedInputBlocks;

    /**
     * Creates an adapter over the given legacy backend that opens its input
     * side on the backend's default input device.
     *
     * @param delegate the legacy backend to adapt; must not be null. The
     *                 adapter owns its lifecycle from here on
     */
    public CallbackBackendAdapter(NativeAudioBackend delegate) {
        this(delegate, "");
    }

    /**
     * Creates an adapter over the given legacy backend.
     *
     * @param delegate        the legacy backend to adapt; must not be null
     * @param inputDeviceName the configured input device name; blank means
     *                        "the backend's default input device". A name
     *                        that fails to resolve disables input with a
     *                        warning rather than failing the open
     */
    public CallbackBackendAdapter(NativeAudioBackend delegate, String inputDeviceName) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.inputDeviceName = inputDeviceName == null ? "" : inputDeviceName;
    }

    @Override
    public String name() {
        return delegate.getBackendName();
    }

    @Override
    public boolean isAvailable() {
        return delegate.isAvailable();
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public List<AudioDeviceInfo> listDevices() {
        if (!delegate.isAvailable()) {
            return List.of();
        }
        try {
            ensureInitialized();
            return List.copyOf(delegate.getAvailableDevices());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Device enumeration failed on " + name(), e);
            return List.of();
        }
    }

    /**
     * Opens a stream on the named device, resolving it against a fresh
     * enumeration snapshot and starting the capture drain thread when the
     * resolved input device can supply channels.
     *
     * <p>ORDERING (story 316 re-review): every check that can still refuse
     * this open — the argument guards, the device resolution and the whole
     * {@link AudioStreamConfig} construction, whose {@link SampleRate} and
     * {@link BufferSize} lookups reject values outside their enums — runs
     * BEFORE {@code startDrainThread()}. A refusal thrown after that start
     * escapes the {@code try} that would have stopped the thread, and
     * {@link #close()} skips the teardown as well because the adapter never
     * became open: the daemon thread would then outlive the failed open
     * while the engine's fallback ladder streams through another backend.
     * Keep new validation above the drain-thread start.</p>
     *
     * @param device       the device to open; {@link DeviceId#isDefault()}
     *                     resolves through the driver's real default query
     * @param format       the negotiated format to open with
     * @param bufferFrames the buffer size in sample frames
     * @throws IllegalArgumentException if {@code bufferFrames} is not
     *                                  positive, or the format's sample rate
     *                                  or buffer size is not one this backend
     *                                  supports
     * @throws IllegalStateException    if a stream is already open
     * @throws AudioBackendException    if the device cannot be resolved or
     *                                  the driver refuses the stream
     */
    @Override
    public void open(DeviceId device,
                     com.benesquivelmusic.daw.sdk.audio.AudioFormat format,
                     int bufferFrames) {
        Objects.requireNonNull(device, "device must not be null");
        Objects.requireNonNull(format, "format must not be null");
        if (bufferFrames <= 0) {
            throw new IllegalArgumentException(
                    "bufferFrames must be positive: " + bufferFrames);
        }
        if (open) {
            throw new IllegalStateException(
                    "A stream is already open on " + name() + "; close it first");
        }
        ensureInitialized();

        // One FRESH enumeration snapshot per open — indices are only valid
        // within it (design book §3.2).
        List<AudioDeviceInfo> snapshot = delegate.getAvailableDevices();
        int outputIndex = resolveOutputDevice(device, snapshot);
        AudioDeviceInfo inputDevice = resolveInputDevice(snapshot);
        int inputIndex = inputDevice != null ? inputDevice.index() : -1;

        this.outChannels = format.channels();
        // Clamp to what the resolved input device can actually supply — a
        // mono mic must not fail (or over-declare) a stereo-format open.
        // Via clampInputChannels, not a bare Math.min: a device whose count
        // is AudioDeviceInfo.CHANNEL_COUNT_UNKNOWN ("offered, but not
        // knowable until the driver is loaded" — an enumerated ASIO driver)
        // would otherwise clamp to -1, which AudioStreamConfig's own
        // validation then refuses outright ("inputChannels must be >= 0"),
        // failing a PLAYBACK open over an input-side unknown (story 316
        // review). PortAudio always reports real counts, so this is a
        // contract guard rather than a live path today.
        this.inChannels = inputDevice != null
                ? inputDevice.clampInputChannels(format.channels())
                : 0;
        this.bufferFrames = bufferFrames;
        this.openedSampleRate = format.sampleRate();
        this.outScratch = new float[outChannels * bufferFrames];
        this.outputRing = new InterleavedBlockRing(OUTPUT_RING_SLOTS,
                outChannels * bufferFrames);
        // ORDERING CONSTRAINT (story 316 re-review) — do not move this below
        // startDrainThread(). Everything here can still REFUSE the open:
        // SampleRate.fromHz and BufferSize.fromFrames reject any value
        // outside their enums, and the record's canonical constructor
        // validates the indices and channel counts. Built after the drain
        // thread had started, such a refusal escaped past the try whose
        // catch calls stopDrainThread() — and close() skips that teardown
        // too, because it is gated on `open`, which is still false — so the
        // daemon thread outlived the failed open for the life of the JVM
        // while the engine's ladder moved on to another backend.
        AudioStreamConfig config = new AudioStreamConfig(
                inputIndex,
                outputIndex,
                inChannels,
                outChannels,
                SampleRate.fromHz((int) format.sampleRate()),
                BufferSize.fromFrames(bufferFrames));

        if (inChannels > 0) {
            this.inScratch = new float[inChannels * bufferFrames];
            this.drainScratch = new float[inChannels * bufferFrames];
            this.inputRing = new InterleavedBlockRing(INPUT_RING_SLOTS,
                    inChannels * bufferFrames);
            startDrainThread();
        } else {
            this.inScratch = null;
            this.drainScratch = null;
            this.inputRing = null;
        }

        try {
            openStreamWithInputRetry(config);
            try {
                delegate.startStream();
            } catch (RuntimeException | Error startFailure) {
                try {
                    delegate.closeStream();
                } catch (RuntimeException closeFailure) {
                    startFailure.addSuppressed(closeFailure);
                }
                throw startFailure;
            }
        } catch (RuntimeException | Error openFailure) {
            stopDrainThread();
            this.outputRing = null;
            this.inputRing = null;
            throw openFailure;
        }
        open = true;
    }

    /**
     * Opens the delegate stream, retrying once output-only when a duplex
     * open is refused — input must never kill playback (class javadoc). The
     * retry tears the input side down first so the adapter's capture state
     * matches the stream that actually opened; an output-only refusal (or a
     * refusal of the retry itself) propagates to {@code open()}'s unwind.
     */
    private void openStreamWithInputRetry(AudioStreamConfig config) {
        try {
            delegate.openStream(config, this::deviceCallback);
        } catch (RuntimeException duplexFailure) {
            if (config.inputChannels() <= 0) {
                throw duplexFailure;
            }
            LOG.log(Level.WARNING,
                    "Duplex open refused on " + name() + "; retrying output-only"
                            + " (capture disabled for this stream, playback unaffected)",
                    duplexFailure);
            disableInputSide();
            delegate.openStream(new AudioStreamConfig(
                            -1,
                            config.outputDeviceIndex(),
                            0,
                            config.outputChannels(),
                            config.sampleRate(),
                            config.bufferSize()),
                    this::deviceCallback);
        }
    }

    /** Undoes the input-side setup of an open that fell back to output-only. */
    private void disableInputSide() {
        stopDrainThread();
        this.inChannels = 0;
        this.inScratch = null;
        this.drainScratch = null;
        this.inputRing = null;
    }

    @Override
    public Flow.Publisher<AudioBlock> inputBlocks() {
        return inputPublisher;
    }

    @Override
    public void sink(AudioBlock block) {
        Objects.requireNonNull(block, "block must not be null");
        if (!open) {
            return; // no stream open — silently dropped per the interface contract
        }
        if (block.channels() != outChannels) {
            throw new IllegalArgumentException(
                    "block channel count " + block.channels()
                            + " does not match the opened stream's " + outChannels);
        }
        if (block.frames() != bufferFrames) {
            throw new IllegalArgumentException(
                    "block frame count " + block.frames()
                            + " does not match the opened buffer size " + bufferFrames);
        }
        InterleavedBlockRing ring = this.outputRing;
        if (ring == null || !ring.write(block.samples(), block.totalSamples())) {
            droppedOutputBlocks.incrementAndGet();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Validates its arguments and then DROPS the samples, counting the
     * drop in {@link #droppedChannelWrites()}. The output ring carries whole
     * interleaved mix blocks written by {@link #sink(AudioBlock)}; mixing a
     * side-output channel into them is the hardware cue/click routing owned
     * by existing stories 136 (metronome side output) and 135 (headphone
     * cue), which story 316's non-goals leave with those stories — the
     * wiring design book &sect;4.2 lists them as what story 316
     * <em>unblocks</em>, not as what it delivers.</p>
     *
     * <p>The override exists so the gap is a COUNTED fact rather than the
     * interface's silently inherited no-op default (story 316 review): this
     * adapter is the default provision head on Windows without ASIO, so
     * every routed click and every cue-bus contribution
     * {@code RenderPipeline} emits lands here while the call site looks fully
     * wired. Deliberately does NOT log — it runs on the render path.</p>
     *
     * @throws IllegalArgumentException if {@code channelIndex} is negative
     *                                  or {@code monoSamples} is null
     */
    @Override
    public void writeToChannel(int channelIndex, float[] monoSamples) {
        if (channelIndex < 0) {
            throw new IllegalArgumentException(
                    "channelIndex must not be negative: " + channelIndex);
        }
        if (monoSamples == null) {
            throw new IllegalArgumentException("monoSamples must not be null");
        }
        droppedChannelWrites.incrementAndGet();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Polls the output ring's occupancy through the shared
     * {@link AudioBackend#pollForSinkCapacity(long, java.util.function.BooleanSupplier)}
     * helper (story 316 review — {@code AsioBackend} carried a verbatim copy
     * of this loop), returning as soon as the ring can accept another block,
     * i.e. as soon as the device callback has consumed one. The helper honours
     * the contract's non-positive-timeout and bounded-wait clauses itself.
     * Falls back to a full-timeout park (wall-clock pacing) when no stream is
     * open.</p>
     */
    @Override
    public void awaitSinkCapacity(long timeoutNanos) {
        // Capture the swappable reference exactly once: close() may null the
        // field concurrently with a pump-thread wait (house rule).
        InterleavedBlockRing ring = this.outputRing;
        if (ring == null) {
            LockSupport.parkNanos(timeoutNanos);
            return;
        }
        AudioBackend.pollForSinkCapacity(timeoutNanos, ring::hasSpace);
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public RoundTripLatency reportedLatency() {
        return delegate.reportedLatency();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Stops and closes the delegate stream (when one is open), shuts the
     * drain thread down, completes the input publisher (subscribers of the
     * closed stream see {@code onComplete}) and releases the delegate. The
     * delegate release runs even when no stream was ever opened — an
     * adapter used only for enumeration (for example the Settings dialog's
     * {@code listDevices()} probes) still initialized the delegate
     * ({@code Pa_Initialize}) and must give that back; {@code delegate.close()}
     * is idempotent. Idempotent once fully closed. A {@code closeStream}
     * refusal propagates with the stream still tracked as open, so a later
     * {@code close()} retry reaches the same handle — exactly what the
     * engine's {@code RELEASE_PENDING} retry needs.</p>
     */
    @Override
    public void close() {
        if (open) {
            try {
                delegate.stopStream();
            } catch (RuntimeException stopFailure) {
                LOG.log(Level.FINE, "stopStream failed before close on " + name(),
                        stopFailure);
            }
            // May throw: the handle is then still held, `open` stays true,
            // and a later close() retries — never swallow this one.
            delegate.closeStream();
            open = false;
            stopDrainThread();
            this.outputRing = null;
            this.inputRing = null;
        }
        completeAndReplaceInputPublisher();
        try {
            delegate.close();
        } catch (RuntimeException closeFailure) {
            LOG.log(Level.WARNING, "Releasing " + name() + " failed", closeFailure);
        }
        initialized = false;
    }

    /** Captured input blocks dropped because the input ring was full. */
    long droppedInputBlocks() {
        return droppedInputBlocks;
    }

    /** Rendered output blocks dropped because the output ring was full. */
    long droppedOutputBlocks() {
        return droppedOutputBlocks.get();
    }

    /**
     * Side-output buffers dropped by
     * {@link #writeToChannel(int, float[])} because this adapter cannot yet
     * address individual physical output channels (stories 136 / 135).
     */
    long droppedChannelWrites() {
        return droppedChannelWrites.get();
    }

    // ── Device resolution (§3.2 — names against a fresh snapshot) ────────

    private int resolveOutputDevice(DeviceId device, List<AudioDeviceInfo> snapshot) {
        if (device.isDefault()) {
            AudioDeviceInfo defaultOut = delegate.getDefaultOutputDevice();
            if (defaultOut == null || !defaultOut.supportsOutput()) {
                throw new AudioBackendException(
                        "No default output device available on " + name());
            }
            return defaultOut.index();
        }
        for (AudioDeviceInfo info : snapshot) {
            if (info.supportsOutput() && info.name().equals(device.name())) {
                return info.index();
            }
        }
        throw new AudioBackendException(
                "Output device '" + device.name() + "' not found on " + name());
    }

    /**
     * Resolves the configured input device against the snapshot, or
     * {@code null} when input is disabled for this stream. The full
     * {@link AudioDeviceInfo} is returned (not just the index) so the caller
     * can clamp the input channel count to the device's real capability.
     */
    private AudioDeviceInfo resolveInputDevice(List<AudioDeviceInfo> snapshot) {
        if (inputDeviceName.isBlank()) {
            AudioDeviceInfo defaultIn = delegate.getDefaultInputDevice();
            return defaultIn != null && defaultIn.supportsInput()
                    ? defaultIn
                    : null; // no default input — input disabled
        }
        for (AudioDeviceInfo info : snapshot) {
            if (info.supportsInput() && info.name().equals(inputDeviceName)) {
                return info;
            }
        }
        LOG.warning("Input device '" + inputDeviceName + "' not found on " + name()
                + "; capture disabled for this stream (playback unaffected)");
        return null;
    }

    private void ensureInitialized() {
        if (!initialized) {
            delegate.initialize();
            initialized = true;
        }
    }

    // ── The RT bridge ────────────────────────────────────────────────────

    /**
     * Invoked by the delegate on ITS audio thread once per hardware buffer.
     * Allocation-free and lock-free: one in-order ring read for playback, one
     * ring write for capture, and a single {@link LockSupport#unpark(Thread)}
     * to wake the drain thread — never a {@code SubmissionPublisher} call.
     */
    @RealTimeSafe
    private void deviceCallback(float[][] inputBuffer, float[][] outputBuffer,
                                int numFrames) {
        InterleavedBlockRing out = this.outputRing;
        float[] outBlock = this.outScratch;
        if (out != null && outBlock != null && out.readInto(outBlock)) {
            deinterleave(outBlock, outputBuffer, numFrames, outChannels);
        } else {
            fillSilence(outputBuffer, numFrames);
        }

        InterleavedBlockRing in = this.inputRing;
        float[] inBlock = this.inScratch;
        if (in != null && inBlock != null
                && inputBuffer != null && inputBuffer.length > 0) {
            interleave(inputBuffer, inBlock, numFrames, inChannels);
            if (!in.write(inBlock, inBlock.length)) {
                droppedInputBlocks++;
            }
            Thread drain = this.drainThread;
            if (drain != null) {
                LockSupport.unpark(drain);
            }
        }
    }

    /**
     * De-interleaves one scratch block into the driver's output planes.
     *
     * <p>The driver's {@code numFrames} is ITS truth, not ours (story 316
     * review): {@code source} is the fixed {@code channels * bufferFrames}
     * scratch sized at {@link #open(DeviceId,
     * com.benesquivelmusic.daw.sdk.audio.AudioFormat, int)}, so a block
     * larger than the requested buffer — PortAudio under a host-API-imposed
     * period, or a post-reset size change — must be CLAMPED against the
     * scratch here, never allowed to index past it. Throwing an
     * {@link ArrayIndexOutOfBoundsException} on the device's real-time thread
     * would break {@link #deviceCallback}'s documented promise that a
     * momentarily differently-shaped block never throws. The frames the
     * scratch cannot cover are left as SILENCE, never as stale samples.</p>
     */
    @RealTimeSafe
    private static void deinterleave(float[] source, float[][] planes,
                                     int numFrames, int channels) {
        if (channels <= 0) {
            fillSilence(planes, numFrames);
            return;
        }
        int maxFrames = source.length / channels; // the scratch is the bound
        int usableChannels = Math.min(channels, planes.length);
        for (int ch = 0; ch < usableChannels; ch++) {
            float[] plane = planes[ch];
            int frames = Math.min(Math.min(numFrames, plane.length), maxFrames);
            for (int frame = 0; frame < frames; frame++) {
                plane[frame] = source[frame * channels + ch];
            }
            // Short scratch block: the rest of the driver's plane is silence.
            java.util.Arrays.fill(plane, frames, Math.min(numFrames, plane.length), 0f);
        }
        for (int ch = usableChannels; ch < planes.length; ch++) {
            java.util.Arrays.fill(planes[ch], 0, Math.min(numFrames, planes[ch].length), 0f);
        }
    }

    /**
     * Interleaves the driver's capture planes into one scratch block.
     *
     * <p>Same clamp as {@link #deinterleave(float[], float[][], int, int)}
     * and for the same reason: {@code destination} is the fixed
     * {@code channels * bufferFrames} input scratch, and the driver's
     * {@code numFrames} is ITS truth, not ours (story 316 review). Frames
     * beyond the scratch are dropped rather than thrown on the RT thread.</p>
     *
     * <p>A SHORT driver block clears the tail it did not supply — the
     * capture-side half of {@code deinterleave}'s "the frames the scratch
     * cannot cover are left as SILENCE, never as stale samples" rule (story
     * 316 review). {@link #deviceCallback} queues the WHOLE scratch and the
     * drain thread publishes a full {@code bufferFrames} block out of it, so
     * every sample this callback did not write would otherwise be republished
     * as FRESH capture audio: the previous callback's tail, recorded a second
     * time. The fill runs through {@code destination.length} rather than
     * {@code maxFrames * channels} so a scratch length that is not an exact
     * multiple of {@code channels} is covered too, and {@code blockFrames} is
     * floored at zero so a non-positive driver block silences the whole
     * scratch instead of indexing the fill negatively on the RT thread.</p>
     */
    @RealTimeSafe
    private static void interleave(float[][] planes, float[] destination,
                                   int numFrames, int channels) {
        if (channels <= 0) {
            return;
        }
        int maxFrames = destination.length / channels; // the scratch is the bound
        int blockFrames = Math.max(0, Math.min(numFrames, maxFrames));
        int usableChannels = Math.min(channels, planes.length);
        for (int ch = 0; ch < usableChannels; ch++) {
            float[] plane = planes[ch];
            int frames = Math.min(blockFrames, plane.length);
            for (int frame = 0; frame < frames; frame++) {
                destination[frame * channels + ch] = plane[frame];
            }
            // Short capture plane: pad the rest of the block with silence.
            for (int frame = frames; frame < blockFrames; frame++) {
                destination[frame * channels + ch] = 0f;
            }
        }
        for (int ch = usableChannels; ch < channels; ch++) {
            for (int frame = 0; frame < blockFrames; frame++) {
                destination[frame * channels + ch] = 0f;
            }
        }
        // Short driver BLOCK: the frames it did not supply are silence, never
        // the previous callback's tail republished as fresh capture (javadoc).
        java.util.Arrays.fill(destination, blockFrames * channels, destination.length, 0f);
    }

    @RealTimeSafe
    private static void fillSilence(float[][] planes, int numFrames) {
        for (float[] plane : planes) {
            java.util.Arrays.fill(plane, 0, Math.min(numFrames, plane.length), 0f);
        }
    }

    // ── The input drain thread (non-RT) ──────────────────────────────────

    private void startDrainThread() {
        draining = true;
        drainThread = Thread.ofPlatform()
                .name(DRAIN_THREAD_NAME)
                .daemon(true)
                .unstarted(this::drainLoop);
        drainThread.start();
    }

    private void stopDrainThread() {
        Thread drain = this.drainThread;
        if (drain == null) {
            return;
        }
        draining = false;
        this.drainThread = null;
        LockSupport.unpark(drain);
        try {
            drain.join(DRAIN_SHUTDOWN_TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Drains the input ring strictly in order, allocates one
     * {@link AudioBlock} per slot (each published block owns a private
     * sample array, honouring {@code AudioBlock}'s documented immutability)
     * and publishes it. Non-RT thread: touching the
     * {@link SubmissionPublisher} is legal only here.
     */
    private void drainLoop() {
        while (draining) {
            InterleavedBlockRing ring = this.inputRing;
            float[] scratch = this.drainScratch;
            if (ring != null && scratch != null) {
                while (draining && ring.readInto(scratch)) {
                    AudioBlock block = new AudioBlock(
                            openedSampleRate, inChannels, bufferFrames, scratch.clone());
                    inputPublisher.offer(block, (subscriber, dropped) -> false);
                }
            }
            if (draining) {
                LockSupport.parkNanos(DRAIN_PARK_NANOS);
            }
        }
    }

    private void completeAndReplaceInputPublisher() {
        SubmissionPublisher<AudioBlock> outgoing = this.inputPublisher;
        this.inputPublisher = new SubmissionPublisher<>();
        outgoing.close();
    }
}
