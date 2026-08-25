package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioBackendException;
import com.benesquivelmusic.daw.sdk.audio.AudioBlock;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.AudioStreamConfig;
import com.benesquivelmusic.daw.sdk.audio.BufferSize;
import com.benesquivelmusic.daw.sdk.audio.CaptureRequirement;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.NativeAudioBackend;
import com.benesquivelmusic.daw.sdk.audio.RoundTripLatency;
import com.benesquivelmusic.daw.sdk.audio.SampleRate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;
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
 * <p>{@link #open(DeviceId, com.benesquivelmusic.daw.sdk.audio.AudioFormat, int,
 * com.benesquivelmusic.daw.sdk.audio.CaptureRequirement)}
 * resolves the configured device SELECTION against a fresh enumeration
 * snapshot on every open. {@link DeviceId#isDefault()} resolves through the
 * driver's real default-device query
 * ({@link NativeAudioBackend#getDefaultOutputDevice()}
 * — PortAudio's {@code Pa_GetDefaultOutputDevice}), never index&nbsp;0. An
 * OUTPUT selection that no longer enumerates is always a visible
 * {@link AudioBackendException}; an INPUT one only under
 * {@link com.benesquivelmusic.daw.sdk.audio.CaptureRequirement#REQUIRED} (see
 * &quot;Input never kills playback&quot; below). The resolved index is used
 * only within that one snapshot.</p>
 *
 * <p>A selection is matched in TWO passes, in both directions, through the
 * one shared {@code matchSelection} helper (story 316 review). First against
 * {@link AudioDeviceInfo#qualifiedName()} — {@code "Speakers [WASAPI]"} —
 * which discriminates host APIs exactly; then, only if that found nothing,
 * against the bare {@link AudioDeviceInfo#name()}, which is every selection
 * persisted before qualified labels existed. A bare name matching MORE than
 * one enumerated device is AMBIGUOUS and is refused, not resolved to the
 * first: PortAudio enumerates the same physical endpoint once per host API,
 * so first-match-wins silently opened an endpoint the user had not chosen,
 * with different indices and different latencies. The refusal message names
 * the competing qualified labels and points at Audio Settings.</p>
 *
 * <h2>Input never kills playback &mdash; unless capture is REQUIRED</h2>
 * <p>Under {@link com.benesquivelmusic.daw.sdk.audio.CaptureRequirement#OPTIONAL},
 * which is what {@code AudioEngine.startAudioOutput} asks for and what the
 * three-argument {@code open} delegates with, capture problems degrade and
 * never fail the open: an input device that fails to resolve — missing,
 * ambiguous, or a blank selection with no default input device — disables
 * input with a warning; the requested input channel count is clamped to the
 * resolved input device's {@code maxInputChannels} (a mono mic never fails a
 * stereo open); and when the driver still refuses the duplex open, the open
 * is retried once output-only before the failure is allowed to propagate.</p>
 *
 * <p>Under {@link com.benesquivelmusic.daw.sdk.audio.CaptureRequirement#REQUIRED},
 * which is what {@code AudioEngine.startAudioInputOutput} asks for, the first
 * and third of those become FAILURES (story 316 review). Every degradation
 * above produces a stream whose {@link #inputBlocks()} can never emit, and a
 * recording open that returns successfully into one of those is a SILENT
 * TAKE — the most expensive failure this application can produce. The channel
 * CLAMP is unaffected in both modes: clamping a stereo request to a mono mic
 * still leaves a live capture stream, so it is not a degradation to refuse.
 * The engine additionally verifies {@link #openedInputChannels()} after the
 * open returns, so a REQUIRED open that slipped through anyway is still
 * refused there; multi-device capture routing remains story 326.</p>
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
 *   <li>{@code PortAudioBackend.openStream} allocates the stream's arena —
 *       the {@code PaStreamParameters} structs, the handle pointer and the
 *       callback upcall stub live in it — and {@code closeStream} closes it.
 *       That arena is {@link java.lang.foreign.Arena#ofShared() shared}
 *       (story 316 review round): the lifecycle seam closes on whichever
 *       thread drives the transition — the JavaFX thread on Play and Stop,
 *       the {@code settings-audio-reconfigure} worker, the device-event
 *       reopen worker, shutdown — so the confined arena it was before threw
 *       {@code WrongThreadException} on every cross-thread close and leaked
 *       the upcall stub. Thread affinity is therefore no longer an arena
 *       constraint; the next two items are the remaining reasons a control
 *       thread is a subsystem.</li>
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
     * @param inputDeviceName the configured input device selection, either a
     *                        bare device name or an
     *                        {@link AudioDeviceInfo#qualifiedName()
     *                        host-API-qualified} one; blank means
     *                        "the backend's default input device". A
     *                        selection that fails to resolve — missing or
     *                        AMBIGUOUS — disables input with a warning
     *                        rather than failing the open, EXCEPT under
     *                        {@link CaptureRequirement#REQUIRED}, where it
     *                        fails the open (story 316 review)
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
     * Opens a stream on the named device with the historical PLAYBACK
     * contract: capture may degrade to nothing and the open still succeeds.
     *
     * <p>Delegates to
     * {@link #open(DeviceId, com.benesquivelmusic.daw.sdk.audio.AudioFormat,
     * int, CaptureRequirement)} with {@link CaptureRequirement#OPTIONAL}
     * (story 316 review) so there is exactly ONE open body to reason about —
     * the ordering constraint documented there is subtle enough that a second
     * copy of it would be a liability.</p>
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
        open(device, format, bufferFrames, CaptureRequirement.OPTIONAL);
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
     * <p>The {@link CaptureRequirement#REQUIRED} refusals honour that
     * constraint for free (story 316 review): the input-device resolution
     * already ran at the top of the method, and the refused duplex retry
     * lives inside {@code openStreamWithInputRetry}, which the {@code try}
     * below already covers with a {@code stopDrainThread()} in its catch.</p>
     *
     * @param device       the device to open; {@link DeviceId#isDefault()}
     *                     resolves through the driver's real default query
     * @param format       the negotiated format to open with
     * @param bufferFrames the buffer size in sample frames
     * @param capture      {@link CaptureRequirement#OPTIONAL} to keep the
     *                     playback contract (capture degrades silently), or
     *                     {@link CaptureRequirement#REQUIRED} to refuse any
     *                     open that would produce no capture
     * @throws IllegalArgumentException if {@code bufferFrames} is not
     *                                  positive, or the format's sample rate
     *                                  or buffer size is not one this backend
     *                                  supports
     * @throws IllegalStateException    if a stream is already open
     * @throws AudioBackendException    if the device cannot be resolved or
     *                                  the driver refuses the stream, and —
     *                                  under {@link CaptureRequirement#REQUIRED}
     *                                  — if the input device cannot be
     *                                  resolved or the duplex open is refused
     * @throws NullPointerException     if any argument is null
     */
    @Override
    public void open(DeviceId device,
                     com.benesquivelmusic.daw.sdk.audio.AudioFormat format,
                     int bufferFrames,
                     CaptureRequirement capture) {
        Objects.requireNonNull(device, "device must not be null");
        Objects.requireNonNull(format, "format must not be null");
        Objects.requireNonNull(capture, "capture must not be null");
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
        AudioDeviceInfo inputDevice = resolveInputDevice(snapshot, capture);
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
            openStreamWithInputRetry(config, capture);
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
     * {@inheritDoc}
     *
     * <p>The adapter's real {@code inChannels} while a stream is open, and
     * {@code 0} otherwise (story 316 review). It is the number the stream was
     * actually CONFIGURED with, after the input device's
     * {@link AudioDeviceInfo#clampInputChannels(int)} clamp and after any
     * output-only fallback: {@link #disableInputSide()} zeroes
     * {@code inChannels} before the output-only retry re-opens, so a stream
     * that degraded reports {@code 0} here even though the caller asked for
     * two channels. That is precisely the honesty
     * {@link AudioBackend#openedInputChannels()} asks for — a positive answer
     * is a promise about {@link #inputBlocks()}, and after a degradation the
     * input ring, the input scratch and the drain thread are all gone, so no
     * block can ever be published.</p>
     *
     * <p>{@code inChannels} is a plain field, not volatile, and that is
     * enough: it is written by {@code open} on the engine's lifecycle thread
     * and read by the engine's post-open verification on that same thread,
     * inside the same {@code lifecycleLock} critical section. The
     * {@code open} flag it is gated on IS volatile, which is what makes a
     * later cross-thread {@code isOpen()}-style read see a consistent
     * &quot;no stream&quot; rather than a stale count.</p>
     */
    @Override
    public int openedInputChannels() {
        return open ? inChannels : 0;
    }

    /**
     * Opens the delegate stream, retrying once output-only when a duplex
     * open is refused and {@code capture} allows it.
     *
     * <p>Under {@link CaptureRequirement#OPTIONAL} this is the historical
     * behaviour: input must never kill playback (class javadoc), so the retry
     * tears the input side down first — {@link #disableInputSide()}, which
     * also makes {@link #openedInputChannels()} report the truth — and
     * re-opens output-only. An output-only refusal, or a refusal of the retry
     * itself, propagates to {@code open()}'s unwind.</p>
     *
     * <p>Under {@link CaptureRequirement#REQUIRED} the duplex failure is
     * RETHROWN instead (story 316 review). The retry exists to protect
     * playback from an input problem, and on a recording open there is no
     * playback to protect: degrading would grab the device output-only,
     * return successfully, and hand the recording pipeline a stream whose
     * {@link #inputBlocks()} can never emit — the silent take this change
     * closes. Rethrowing also keeps the DRIVER's own exception as the
     * failure, which is the actionable one, and leaves the device untouched
     * so the engine's ladder can offer the next rung a clean device.</p>
     */
    private void openStreamWithInputRetry(AudioStreamConfig config,
                                          CaptureRequirement capture) {
        try {
            delegate.openStream(config, this::deviceCallback);
        } catch (RuntimeException duplexFailure) {
            if (config.inputChannels() <= 0) {
                throw duplexFailure;
            }
            if (capture == CaptureRequirement.REQUIRED) {
                LOG.warning("Duplex open refused on " + name()
                        + " and NOT retried output-only: this open requires capture"
                        + " (recording), and an output-only stream could never record");
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
     *
     * <p>A delegate RELEASE failure propagates too (story 316 review). A
     * later {@code close()} retries it because {@code delegate.close()} is
     * called unconditionally — nothing in this method reads
     * {@code initialized}. That flag is read only by
     * {@code ensureInitialized()}, so its staying {@code true} after a failed
     * release means just that a later {@link #open} or enumeration will not
     * re-issue {@code delegate.initialize()}; on {@code PortAudioBackend} a
     * repeat of that call re-issues no {@code Pa_Initialize} while the
     * delegate still counts itself initialized, because {@code initialize()}
     * is guarded by
     * {@code initialized.compareAndSet(false, true)}. Swallowing the failure
     * reported a release that never
     * happened: the engine's {@code closeFailedHop} saw a normal return and
     * walked the ladder to the next rung while the delegate could still hold
     * its stream. That is reachable, not theoretical — when
     * {@code startStream} fails inside {@link #open} and the rollback
     * {@code closeStream} fails as well, {@code open} never became
     * {@code true}, so the branch above never runs and the retained stream is
     * reachable ONLY through {@code delegate.close()} here. The retry is
     * meaningful because of how {@code PortAudioBackend.close()} is built: it
     * calls {@code closeStream()} first, and {@code closeStream()} throws on a
     * {@code Pa_CloseStream} error BEFORE it nulls {@code streamHandle}, so
     * the next call re-issues {@code Pa_CloseStream} on the handle it still
     * holds; {@code PortAudioBackend.close()} reaches its
     * {@code bindings.terminate()} ({@code Pa_Terminate}) only after that
     * {@code closeStream()} has returned normally. The input publisher is
     * completed and replaced immediately before {@code delegate.close()}, so
     * it is completed on every call that reaches the delegate release,
     * including a retry; a {@code closeStream} refusal in the branch above
     * exits before it.</p>
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
        // May throw (story 316 review). This call is unconditional, so a
        // later close() reaches the delegate again whatever `initialized`
        // holds; the flag staying true only spares ensureInitialized() a
        // repeat delegate.initialize(). See the javadoc for why a normal
        // return here must mean the delegate really let go.
        delegate.close();
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

    // ── Device resolution (§3.2 — selections against a fresh snapshot) ───

    /**
     * Resolves the requested output device to an index within {@code snapshot}
     * (story 316 review — host-API-qualified resolution).
     *
     * <p>{@link DeviceId#isDefault()} short-circuits to the driver's own
     * default-output query, never index&nbsp;0. Everything else goes through
     * {@link #matchSelection(List, String, Predicate)}'s two passes, and the
     * three outcomes are the whole contract: exactly one match opens it, zero
     * matches is a stale identity and throws, and MORE than one match is
     * refused rather than resolved to the first.</p>
     *
     * <p>Refusing an ambiguous name is the point (story 316 review).
     * PortAudio enumerates the same physical endpoint once per host API — one
     * interface is {@code "Speakers"} under MME, DirectSound, WASAPI and
     * WDM-KS, four entries with four indices and four latencies — so the old
     * {@code name().equals(...)} loop returned the FIRST match and the engine
     * opened an endpoint the user had not chosen, silently, with different
     * latency characteristics. There is no safe way to guess which one was
     * meant, so the open fails with a message that tells the user how to make
     * the selection unambiguous.</p>
     *
     * @param device   the requested device identity
     * @param snapshot the enumeration snapshot this open resolves within
     * @return the resolved output device index, valid only within
     *         {@code snapshot}
     * @throws AudioBackendException if no device matches, if more than one
     *                               does, or if there is no default output
     *                               device when one was asked for
     */
    private int resolveOutputDevice(DeviceId device, List<AudioDeviceInfo> snapshot) {
        if (device.isDefault()) {
            AudioDeviceInfo defaultOut = delegate.getDefaultOutputDevice();
            if (defaultOut == null || !defaultOut.supportsOutput()) {
                throw new AudioBackendException(
                        "No default output device available on " + name());
            }
            return defaultOut.index();
        }
        List<AudioDeviceInfo> matches =
                matchSelection(snapshot, device.name(), AudioDeviceInfo::supportsOutput);
        if (matches.size() == 1) {
            return matches.get(0).index();
        }
        if (matches.size() > 1) {
            throw new AudioBackendException(
                    ambiguousSelectionMessage("Output", device.name(), matches));
        }
        throw new AudioBackendException(
                "Output device '" + device.name() + "' not found on " + name());
    }

    /**
     * Resolves the configured input device against the snapshot, or
     * {@code null} when input is disabled for this stream. The full
     * {@link AudioDeviceInfo} is returned (not just the index) so the caller
     * can clamp the input channel count to the device's real capability.
     *
     * <p>Mirrors {@link #resolveOutputDevice(DeviceId, List)} exactly —
     * same {@link #matchSelection(List, String, Predicate)} helper, same
     * three outcomes — and differs only in what a failure MEANS, which is
     * what {@code capture} decides (story 316 review):</p>
     * <ul>
     *   <li>{@link CaptureRequirement#OPTIONAL}: a blank name with no default
     *       input, a name that no longer enumerates, and an ambiguous name
     *       all disable capture with a warning and let the open proceed.
     *       Input must never kill playback.</li>
     *   <li>{@link CaptureRequirement#REQUIRED}: each of those three throws.
     *       A record open whose configured input device does not enumerate is
     *       a FAILURE, not a degradation — carrying on would open an
     *       output-only stream, the recording pipeline would subscribe to a
     *       publisher that never emits, and the take would be silent with
     *       nothing in the log.</li>
     * </ul>
     *
     * @param snapshot the enumeration snapshot this open resolves within
     * @param capture  whether capture may be dropped
     * @return the resolved input device, or {@code null} when capture is
     *         disabled for this stream (only possible under
     *         {@link CaptureRequirement#OPTIONAL})
     * @throws AudioBackendException under {@link CaptureRequirement#REQUIRED},
     *                               when the configured input device cannot
     *                               be resolved to exactly one entry
     */
    private AudioDeviceInfo resolveInputDevice(List<AudioDeviceInfo> snapshot,
                                               CaptureRequirement capture) {
        if (inputDeviceName.isBlank()) {
            AudioDeviceInfo defaultIn = delegate.getDefaultInputDevice();
            if (defaultIn != null && defaultIn.supportsInput()) {
                return defaultIn;
            }
            return refuseInput(capture,
                    "No default input device available on " + name());
        }
        List<AudioDeviceInfo> matches =
                matchSelection(snapshot, inputDeviceName, AudioDeviceInfo::supportsInput);
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.size() > 1) {
            return refuseInput(capture,
                    ambiguousSelectionMessage("Input", inputDeviceName, matches));
        }
        return refuseInput(capture,
                "Input device '" + inputDeviceName + "' not found on " + name());
    }

    /**
     * The single place an input-side resolution failure decides what it
     * means (story 316 review): a thrown {@link AudioBackendException} under
     * {@link CaptureRequirement#REQUIRED}, a warning and {@code null} under
     * {@link CaptureRequirement#OPTIONAL}.
     *
     * <p>Returning {@code null} rather than being {@code void} lets every
     * call site read {@code return refuseInput(...)}, so no branch can
     * accidentally fall through into the resolved-device path.</p>
     *
     * @param capture what the caller asked for
     * @param reason  the resolution failure, already phrased for a user
     * @return always {@code null}, under {@link CaptureRequirement#OPTIONAL}
     * @throws AudioBackendException under {@link CaptureRequirement#REQUIRED}
     */
    private AudioDeviceInfo refuseInput(CaptureRequirement capture, String reason) {
        if (capture == CaptureRequirement.REQUIRED) {
            throw new AudioBackendException(reason
                    + ". This open requires capture (recording), so it is refused"
                    + " rather than degraded to output-only");
        }
        LOG.warning(reason + "; capture disabled for this stream (playback unaffected)");
        return null;
    }

    /**
     * The ONE device-selection matcher both directions use (story 316
     * review), so output and input resolution cannot drift apart.
     *
     * <p>Two passes, in this order:</p>
     * <ol>
     *   <li><strong>Exact qualified match.</strong> Entries whose
     *       {@link AudioDeviceInfo#qualifiedName()} equals the selection.
     *       That label carries the host API, so it discriminates two
     *       same-named endpoints exactly, and it is what the settings layer
     *       persists once a name has collided.</li>
     *   <li><strong>Legacy bare match</strong>, only when the first pass
     *       found nothing. Entries whose bare {@link AudioDeviceInfo#name()}
     *       equals the selection — every setting saved before qualified
     *       labels existed, which is the overwhelmingly common case and must
     *       keep working. A single hit resolves exactly as it always did; two
     *       or more are returned as-is so the CALLER can refuse them.</li>
     * </ol>
     *
     * <p>Deliberately NOT
     * {@link AudioDeviceInfo#isSelectionFor(String, String)} in the first
     * pass, and this is not a simplification anyone should make later: that
     * predicate answers "does this selection name that BARE device", so it
     * accepts {@code "Speakers [WASAPI]"} for the MME {@code "Speakers"} row
     * too. Using it here would collapse both passes into one and re-introduce
     * exactly the first-match-wins substitution this method exists to
     * prevent. It belongs to the settings layer, which asks the opposite
     * question — "is this persisted selection still about this device?" —
     * where accepting either form is right.</p>
     *
     * @param snapshot           the enumeration snapshot to search
     * @param selection          the persisted selection, bare or qualified
     * @param directionSupported {@link AudioDeviceInfo#supportsInput()} or
     *                           {@link AudioDeviceInfo#supportsOutput()}
     * @return the matching entries: empty (no match), one (resolved), or
     *         more than one (AMBIGUOUS — the caller must refuse, never pick)
     */
    private static List<AudioDeviceInfo> matchSelection(
            List<AudioDeviceInfo> snapshot,
            String selection,
            Predicate<AudioDeviceInfo> directionSupported) {
        List<AudioDeviceInfo> qualified = new ArrayList<>(1);
        for (AudioDeviceInfo info : snapshot) {
            if (directionSupported.test(info) && info.qualifiedName().equals(selection)) {
                qualified.add(info);
            }
        }
        if (!qualified.isEmpty()) {
            return qualified;
        }
        List<AudioDeviceInfo> bare = new ArrayList<>(1);
        for (AudioDeviceInfo info : snapshot) {
            if (directionSupported.test(info) && info.name().equals(selection)) {
                bare.add(info);
            }
        }
        return bare;
    }

    /**
     * Phrases an ambiguous selection for a user who has to fix it (story 316
     * review). Lists the competing entries by
     * {@link AudioDeviceInfo#qualifiedName()} — which is both the set of
     * competing host APIs and the exact string the settings layer will
     * persist once the user re-selects — and says where to do that.
     *
     * <p>Two entries can also collide with IDENTICAL qualified names (two of
     * the same interface under one host API). Nothing can tell those apart
     * from a persisted selection, so they are refused for the same reason and
     * the message simply repeats the label; the honest answer is that the
     * selection is not specific enough.</p>
     *
     * @param direction {@code "Input"} or {@code "Output"}, for the message
     * @param selection the ambiguous selection
     * @param matches   the competing entries; always at least two
     * @return the message for the {@link AudioBackendException} or warning
     */
    private String ambiguousSelectionMessage(String direction, String selection,
                                             List<AudioDeviceInfo> matches) {
        StringBuilder competing = new StringBuilder();
        for (AudioDeviceInfo info : matches) {
            if (!competing.isEmpty()) {
                competing.append(", ");
            }
            competing.append('\'').append(info.qualifiedName()).append('\'');
        }
        return direction + " device '" + selection + "' is AMBIGUOUS on " + name()
                + ": " + matches.size() + " enumerated devices answer to that name,"
                + " under different host APIs — " + competing
                + ". Opening the first of them would silently use an endpoint you did"
                + " not choose, so this selection is refused; re-select the device in"
                + " Audio Settings to store the host-API-qualified name";
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
