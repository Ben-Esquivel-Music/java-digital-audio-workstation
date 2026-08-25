package com.benesquivelmusic.daw.sdk.audio;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Windows ASIO backend — Steinberg's low-latency driver model, the de-facto
 * standard for professional audio work on Windows.
 *
 * <p>The backend calls a small, normalized C ABI exposed by the native shim
 * through the Foreign Function &amp; Memory API (JEP 454, final in Java 22).
 * The shim is compiled against the Steinberg ASIO SDK headers under
 * {@code daw-core/native/asio/} and must be built opt-in
 * (the Steinberg licence forbids redistributing the SDK headers) — when
 * it is absent the backend simply reports {@link #isAvailable()} = false
 * and {@link AudioBackendSelector} will fall back to
 * {@link JavaxSoundBackend}.</p>
 *
 * <p>Since story 311 the buffer-switch bridge lives here in the SDK:
 * {@link #open(DeviceId, AudioFormat, int)} installs an
 * {@code AsioBufferSwitchShim} that pumps the driver's callback into
 * {@link #inputBlocks()} and {@link #sink(AudioBlock)}, so this class —
 * not some downstream layer — is what really moves audio. The bridge
 * needs the native shim's story-311 streaming symbols; when they are
 * absent {@code open()} degrades to the story-310 lifecycle-only path,
 * whose {@code sink} discards. {@link #supportsStreaming()} probes for
 * exactly those symbols so a caller can refuse such a host before
 * opening a stream that would be silent by construction (story 316).</p>
 */
public final class AsioBackend implements AudioBackend {

    /** Backend name. */
    public static final String NAME = "ASIO";

    private static final Logger LOG = Logger.getLogger(AsioBackend.class.getName());

    /**
     * Canonical sample-rate menu probed against {@code ASIOCanSampleRate}
     * — the historical menu the dialog has always offered (story 213).
     */
    static final int[] CANONICAL_SAMPLE_RATES_HZ = {
            44_100, 48_000, 88_200, 96_000, 176_400, 192_000};

    /**
     * Factory for the FFM capability shim. Defaults to loading
     * {@code asioshim} via {@link AsioCapabilityShim}; tests inject a
     * stub via {@link #setCapabilityShimFactory(Supplier)} to exercise
     * the success and missing-shim paths without needing a Windows
     * host with the Steinberg ASIO SDK installed.
     */
    private static volatile Supplier<AsioCapabilityShim> capabilityShimFactory =
            AsioCapabilityShim::new;

    /** Factory for the optional ASIO enumeration/lifecycle FFM binding. */
    private static volatile Supplier<AsioDriverShim> driverShimFactory =
            AsioDriverShim::new;

    /**
     * Factory for the optional real-time streaming FFM binding (story 311).
     * Defaults to loading {@code asioshim} via {@link AsioStreamingShim};
     * tests inject a stub via {@link #setStreamingShimFactory(Supplier)} to
     * exercise the {@code createBuffers} / {@code start} / {@code stop} /
     * {@code disposeBuffers} ordering without a Windows host.
     */
    private static volatile Supplier<AsioStreamingShim> streamingShimFactory =
            AsioStreamingShim::new;

    /** Serializes the Steinberg SDK's process-wide single-driver lifecycle. */
    private static final Object DRIVER_LIFECYCLE_LOCK = new Object();

    /**
     * Runs {@code ASIOStop} / {@code ASIODisposeBuffers} after a
     * driver-initiated reset (story 218 &times; 311). A pre-created
     * single-thread daemon executor rather than a thread per reset: the caller
     * is the driver's own host-callback thread, which must not pay for an OS
     * thread creation, and serializing the teardowns keeps a reset storm from
     * spawning an unbounded number of threads. Mirrors the house
     * {@link AsioControlThread} idiom.
     */
    private static final ExecutorService RESET_TEARDOWN_EXECUTOR =
            Executors.newSingleThreadExecutor(task -> Thread.ofPlatform()
                    .name("asio-reset-teardown")
                    .daemon(true)
                    .unstarted(task));

    /**
     * How long the deferred driver-shim release waits for the control thread to
     * return from a call the host abandoned. It runs on the
     * {@code asio-reset-teardown} executor rather than on a lifecycle thread,
     * so it can afford to wait far longer than any
     * {@link AsioControlThread#DEFAULT_BUDGET}-sized figure: nothing queues
     * behind it except the next teardown, and giving up early on a driver that
     * would have let go leaks {@code ASIOExit} and the shim's FFM arena for the
     * life of the process.
     */
    private static final Duration DRIVER_RELEASE_BUDGET = Duration.ofMinutes(2);

    /** Backend instance that currently owns the single process-wide driver. */
    private static AsioBackend activeBackend;

    /**
     * Whether the "ASIO capability shim is unavailable; using fallback"
     * INFO has already been logged in this JVM. Story 213 explicitly
     * requires logging the absence "exactly once per process".
     */
    private static final AtomicBoolean FALLBACK_LOGGED = new AtomicBoolean(false);

    private final Executor resetTeardownExecutor;

    /**
     * How many driver-shim releases {@link #releaseDriverShim(AsioDriverShim)}
     * has DEFERRED and that have not run yet. It is what
     * {@link #isReleasePending()} answers from, and therefore the only way a
     * caller learns that a {@link #close()} which returned normally did not
     * actually hand the device back (story 316 re-review).
     *
     * <p>A count rather than a flag because a backend can owe more than one
     * release: {@code open()} releases the candidate shim on every early exit,
     * and a later {@code close()} releases the shim it did adopt. Both may
     * defer, and a flag cleared by the first task to finish would report a
     * released device while the second was still queued.</p>
     */
    private final AtomicInteger deferredDriverReleases = new AtomicInteger();

    private final AudioBackendSupport support = new AudioBackendSupport();
    private volatile AsioFormatChangeShim formatChangeShim;
    private volatile AsioDriverShim driverShim;
    private volatile AsioStreamingShim streamingShim;
    private volatile AsioBufferSwitchShim bufferSwitchShim;

    /**
     * Name of the driver {@code ASIOInit} succeeded for, or {@code null} while
     * no driver is loaded. Held so a teardown the driver refuses can name it in
     * the diagnostic without issuing a downcall from the teardown path.
     */
    private volatile String activeDriverName;

    /**
     * Latched by every {@link #stopStreamingForDriverReset()}, cleared on entry
     * to {@link #open(DeviceId, AudioFormat, int)} and re-checked once the
     * stream is running. A driver may issue {@code kAsioResetRequest} from
     * inside {@code ASIOCreateBuffers} — before either streaming shim is
     * published — in which case the reset would otherwise silently no-op while
     * {@code open()} went on to start a driver that just asked to be reset.
     */
    private volatile boolean driverResetRequested;

    /** Creates a new ASIO backend (no native resources allocated until {@link #open}). */
    public AsioBackend() {
        this(RESET_TEARDOWN_EXECUTOR);
    }

    /** Test seam for controlling when an asynchronous reset teardown runs. */
    AsioBackend(Executor resetTeardownExecutor) {
        this.resetTeardownExecutor = Objects.requireNonNull(
                resetTeardownExecutor, "resetTeardownExecutor");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isAvailable() {
        try (AsioDriverShim shim = driverShimFactory.get()) {
            return shim.isEnumerationAvailable() && shim.isLifecycleAvailable();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code true} only when the native {@code asioshim} exports every
     * story-311 streaming symbol — the same
     * {@link AsioStreamingShim#isStreamingAvailable()} probe
     * {@link #open(DeviceId, AudioFormat, int)} runs before it installs the
     * buffer-switch bridge (story 316 review).</p>
     *
     * <p>{@link #isAvailable()} only proves the enumeration and lifecycle
     * symbols resolve. A Windows host whose shim predates story 311 — or was
     * built without its streaming entrypoints — passes that probe, yet
     * {@code open()} then degrades to the story-310 no-streaming path:
     * the backend opens, {@link #sink(AudioBlock)} discards every block and
     * the stream is silent while looking healthy. That is exactly the false
     * success the selector's streaming gate exists to prevent, so this flag
     * must answer from the streaming shim rather than from
     * {@code isAvailable()}. It keeps such an ASIO out of
     * {@link AudioBackendSelector#availableBackends()} and out of the app
     * layer's provision ladder; {@code open()}'s own degrade path is
     * unchanged and remains the backstop for a caller that bypasses the
     * gate.</p>
     *
     * <p>The probe shim is opened and closed per call so no FFM arena
     * leaks; any failure to construct it counts as "not streamable",
     * mirroring {@link #isAvailable()}.</p>
     */
    @Override
    public boolean supportsStreaming() {
        try (AsioStreamingShim shim = streamingShimFactory.get()) {
            return streamingShimUsable(shim);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>One entry per installed x64 ASIO driver, straight from the registry
     * enumeration — no driver is loaded to produce this list.</p>
     *
     * <p>Each entry is therefore
     * {@linkplain AudioDeviceInfo#unprobed(int, String, String) unprobed}:
     * its channel counts are {@link AudioDeviceInfo#CHANNEL_COUNT_UNKNOWN}
     * rather than a number (story 316 review). {@code ASIOGetChannels} only
     * answers after {@code loadDriver} + {@code ASIOInit}, and loading every
     * installed driver to fill a settings menu is not acceptable: an ASIO
     * driver may take exclusive control of its hardware and may open a modal
     * control panel. So the count is genuinely unknown here, and inventing
     * a plausible one (2) would be a lie.</p>
     *
     * <p>Reporting {@code 0} — the previous behaviour — was equally wrong in
     * the other direction: {@code 0} means "this direction is not offered",
     * so {@link AudioDeviceInfo#supportsOutput()} answered {@code false} for
     * every ASIO driver and the Settings device menus filtered them all out.
     * The user could not select or persist a specific driver, which is the
     * whole point of enumerating them. The unknown sentinel offers both
     * directions without claiming a count; the real counts are read from the
     * driver in {@link #open(DeviceId, AudioFormat, int)}, which clamps the
     * requested channel set against them.</p>
     */
    @Override
    public List<AudioDeviceInfo> listDevices() {
        try (AsioDriverShim shim = driverShimFactory.get()) {
            List<AsioDriverShim.DriverDescriptor> drivers = shim.listDrivers();
            if (drivers.isEmpty()) {
                return List.of();
            }
            List<AudioDeviceInfo> devices = new java.util.ArrayList<>(drivers.size());
            for (int index = 0; index < drivers.size(); index++) {
                AsioDriverShim.DriverDescriptor driver = drivers.get(index);
                devices.add(AudioDeviceInfo.unprobed(index, driver.name(), NAME));
            }
            return List.copyOf(devices);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    /**
     * Opens the driver and, when the native streaming shim is present, starts
     * the ASIO buffer-switch loop (story 311).
     *
     * <p>Sequence, all under the process-wide driver lifecycle lock:</p>
     * <ol>
     *   <li>{@code asioshim_loadDriver} + {@code ASIOInit} (story 310).</li>
     *   <li>Buffer-size negotiation against the driver-reported
     *       {@code ASIOGetBufferSize} four-tuple (stories 213 / 220). A
     *       request outside the reported range or off its granularity ladder
     *       raises {@link AudioBackendException}; the size is never silently
     *       resized.</li>
     *   <li>Install the story-218 {@code asioMessage} shim — <em>before</em>
     *       {@code ASIOCreateBuffers}, because drivers issue the ASIO handshake
     *       selectors synchronously from inside that call.</li>
     *   <li>{@code ASIOCreateBuffers} for the opened channel set, clamped in
     *       each direction against the driver's reported input / output counts,
     *       then a read-back of the driver's buffer descriptors.</li>
     *   <li>{@link AudioBackendSupport#markOpen(AudioFormat, int)} —
     *       <em>before</em> the upcall is installed, because
     *       {@code publishInput} drops while the backend is not open and the
     *       first callback must not race the flag.</li>
     *   <li>Install the buffer-switch upcall, publish the streaming fields, then
     *       {@code ASIOStart} and take ownership of the process-wide driver.</li>
     * </ol>
     *
     * <p>When {@code asioshim} is absent — every non-Windows host, and every
     * Windows build made without the Steinberg SDK — steps 4 and 6 are
     * skipped and the method behaves exactly as it did in story 310: the
     * backend opens, publishes device events, and simply streams no audio.</p>
     *
     * <p><strong>A FAILED open does not always mean the device is free</strong>
     * (story 316 re-review). Once a candidate driver shim exists, every exit
     * below — each early one, and the {@code rollbackOpen(...)} behind the
     * main {@code try} — hands that shim to
     * {@link #releaseDriverShim(AsioDriverShim)}, which DEFERS the
     * close while the control thread is still executing a call the host
     * abandoned. (The entry gates that refuse ahead of the shim never take
     * one, so they have nothing to release.) The load-timeout path is the material one: a {@code loadDriver}
     * whose budget expired answers {@code false} while keeping its ownership
     * claim, so the {@link AudioBackendException} thrown here can be raised by a
     * backend that may still be acquiring the device. That is now REPORTED
     * rather than silent — {@link #isReleasePending()} answers {@code true}
     * until the queued {@code ASIOExit} runs — and a caller walking a fallback
     * ladder must read such a hop as a rung whose handle is RETAINED, not as an
     * ordinary refusal it may open past.</p>
     */
    @Override
    public void open(DeviceId device, AudioFormat format, int bufferFrames) {
        Objects.requireNonNull(device, "device must not be null");
        Objects.requireNonNull(format, "format must not be null");
        if (bufferFrames <= 0) {
            throw new IllegalArgumentException(
                    "bufferFrames must be positive: " + bufferFrames);
        }
        synchronized (DRIVER_LIFECYCLE_LOCK) {
            if (support.isOpen()) {
                throw new IllegalStateException("backend already has an open stream");
            }
            if (activeBackend != null && activeBackend != this) {
                throw new IllegalStateException(
                        "another ASIO backend already owns the process-wide driver");
            }
            if (!AsioControlThread.isQuiesced()) {
                // An earlier downcall its caller stopped waiting for (budget or
                // interrupt) is still inside the driver, so the device is
                // provably not free.
                // Loading a driver over one that is still executing is how a
                // wedged open becomes two hosts fighting over the same
                // hardware. Refuse rather than wait: this thread holds
                // DRIVER_LIFECYCLE_LOCK and the engine's lifecycle lock, and a
                // wait here would stall every transition behind both — which is
                // the failure the bounded budget exists to prevent.
                throw new AudioBackendException(
                        "ASIO device is not free: an earlier ASIO call its caller"
                                + " stopped waiting for (budget expired or interrupted)"
                                + " is still executing inside the driver."
                                + " The driver has not released the device, so it"
                                + " cannot be reconfigured yet.");
            }
            driverResetRequested = false;

            // Every early exit below releases the candidate through
            // releaseDriverShim rather than closing it inline: the entry gate
            // above proves the control thread was quiesced on ARRIVAL, not that
            // it still is. resolveDriverName and loadDriver are themselves
            // bounded downcalls, so either can leave a call abandoned inside the
            // driver — and an inline close would then spend the shim's single
            // close on downcalls that fail fast, latching it shut with ASIOExit
            // un-issued. The loadDriver path is the material one: a load that
            // times out has NOT proved the driver failed to initialize, and the
            // shim now keeps its ownership claim precisely so the deferred
            // release can still issue ASIOExit (see
            // AsioDriverShim#loadDriver(String)).
            AsioDriverShim candidate = driverShimFactory.get();
            if (!candidate.isLifecycleAvailable()) {
                releaseDriverShim(candidate);
                throw unavailableException();
            }
            String driverName;
            try {
                driverName = resolveDriverName(candidate, device);
            } catch (RuntimeException | Error failure) {
                releaseDriverShim(candidate);
                throw failure;
            }
            if (!candidate.loadDriver(driverName)) {
                releaseDriverShim(candidate);
                throw new AudioBackendException(
                        "Could not load and initialize ASIO driver: " + driverName);
            }
            activeDriverName = driverName;

            AsioStreamingShim streaming = null;
            AsioBufferSwitchShim bridge = null;
            AsioFormatChangeShim callback = null;
            boolean marked = false;
            try {
                // ASIOGetBufferSize only answers once the driver is
                // initialized, so negotiation happens after loadDriver and
                // before any buffer is created.
                requireAcceptedBufferSize(bufferFrames);

                // The asioMessage upcall must be live BEFORE ASIOCreateBuffers.
                // Story 311 is the first story that ever builds an ASIOCallbacks
                // table, and real drivers issue kAsioEngineVersion /
                // kAsioSelectorSupported / kAsioSupportsTimeInfo synchronously
                // from inside ASIOCreateBuffers. With the slot still empty the
                // trampoline answers 0 and the driver falls back to ASIO 1.0.
                // support.format() is deliberately still null here, so a
                // kAsioBufferSizeChange arriving during ASIOCreateBuffers yields
                // Optional.empty() for the proposed format — which
                // AsioFormatChangeShim#dispatch already handles correctly.
                callback = new AsioFormatChangeShim(
                        this, support, new DeviceId(NAME, driverName));
                formatChangeShim = callback;

                streaming = acquireStreamingShim();
                List<AsioStreamingShim.BufferInfo> bufferInfos = List.of();
                if (streaming != null) {
                    int[] counts = negotiateChannelCounts(format.channels(), driverName);
                    if (!streaming.createBuffers(channelIndices(counts[0]),
                            channelIndices(counts[1]), bufferFrames)) {
                        throw new AudioBackendException(
                                "ASIO driver rejected ASIOCreateBuffers for "
                                        + counts[0] + " input(s) / " + counts[1]
                                        + " output(s) at " + bufferFrames
                                        + " frames: " + driverName);
                    }
                    bufferInfos = streaming.getBufferInfos();
                    if (bufferInfos.isEmpty()) {
                        throw new AudioBackendException(
                                "ASIO driver reported no buffer descriptors after "
                                        + "ASIOCreateBuffers: " + driverName);
                    }
                }

                // ASIOInit succeeded before the backend is marked open. This
                // ordering prevents every capability wrapper from reaching an
                // uninitialized SDK global.
                support.markOpen(format, bufferFrames);
                marked = true;

                if (streaming != null) {
                    bridge = new AsioBufferSwitchShim(
                            support, format, bufferFrames, bufferInfos);
                    if (!streaming.installBufferSwitchCallback(bridge.upcallStub())) {
                        throw new AudioBackendException(
                                "Could not install the ASIO buffer-switch callback for "
                                        + driverName);
                    }
                    // Publish both shims BEFORE ASIOStart: a block sunk in the
                    // window between start and the field assignment would
                    // otherwise be silently dropped, and a reset arriving in
                    // that window would find nothing to tear down.
                    streamingShim = streaming;
                    bufferSwitchShim = bridge;
                    if (!streaming.start()) {
                        throw new AudioBackendException(
                                "ASIOStart failed for driver: " + driverName);
                    }
                }

                driverShim = candidate;
                activeBackend = this;
            } catch (RuntimeException | Error failure) {
                rollbackOpen(candidate, streaming, bridge, callback, marked);
                throw failure;
            }

            if (driverResetRequested) {
                // A reset arrived while open() was still wiring the stream up
                // (drivers legitimately send one from inside ASIOCreateBuffers).
                // The earlier call could only latch the request because neither
                // shim was published yet; both are live now, so run the real
                // teardown before handing a "running" stream back to the caller.
                stopStreamingForDriverReset();
            }
        }
    }

    /**
     * Resolves how many input and output channels {@code ASIOCreateBuffers}
     * should be asked for (story 311, S1).
     *
     * <p>Story 310's behaviour of requesting the opened format's channel set in
     * <em>both</em> directions breaks on a playback-only device — or on
     * ASIO4ALL with only speakers enabled — because {@code ASIOCreateBuffers}
     * fails for the phantom inputs and {@code open()} would throw where it used
     * to succeed. Asymmetric input / output counts are the norm on
     * multi-channel USB interfaces, this project's primary target, so each
     * direction is clamped against the driver's reported count. Channels the
     * driver does not have simply get no buffer — for a PARTIAL shortfall,
     * that is: a driver with fewer outputs than the format asks for still
     * carries audio on the outputs it does have, and
     * {@link AsioBufferSwitchShim} captures silence for absent inputs and
     * leaves their (non-existent) output buffers alone.</p>
     *
     * <p><strong>A TOTAL absence of outputs is a failure, not a clamp</strong>
     * (story 316 re-review). {@code open()} is a PLAYBACK open — the engine's
     * only caller sinks rendered blocks through it — so a driver reporting no
     * output channels at all (a capture-only interface, or ASIO4ALL with only
     * inputs enabled) must not be negotiated down to zero outputs and opened
     * anyway. It would succeed all the way through: {@code ASIOCreateBuffers}
     * accepts the inputs alone, {@code getBufferInfos()} is non-empty, the
     * bridge consumes rendered blocks and advances
     * {@link #renderedBlocksConsumedByDriver()} while every output buffer is
     * {@link java.lang.foreign.MemorySegment#NULL} — a stream that is silent by
     * construction, reporting progress, and passing the Windows integration
     * proof. So this rung FAILS instead, exactly as
     * {@link JavaxSoundBackend#open(DeviceId, AudioFormat, int)} fails when its
     * mandatory output line cannot be opened: the engine's fallback ladder must
     * see this rung fail, never "succeed" into a silent no-output stream
     * (story 316, honest promises).</p>
     *
     * <p>There is deliberately NO symmetric guard on zero inputs. Capture is
     * optional-degrade on this backend and on
     * {@link JavaxSoundBackend} alike — an input line that cannot be opened
     * only disables capture, playback proceeds — and a playback-only interface
     * is the common case this method was written to support in the first
     * place. Refusing it would break the very devices the clamp exists for.</p>
     *
     * <p><strong>Do not "fix" that asymmetry</strong> (story 316 review). The
     * RECORDING path's zero-input refusal is real, but it lives at the CALLER:
     * the engine opens with {@link CaptureRequirement#REQUIRED}, reads
     * {@link #openedInputChannels()} afterwards, and turns a zero into an
     * ordinary failed ladder hop. Adding a guard here instead would refuse a
     * playback-only interface for every open, recording or not — which is the
     * regression the paragraph above exists to prevent — and it would refuse it
     * on the wrong evidence anyway: {@code ASIOGetChannels} reports what the
     * driver HAS, while {@link #openedInputChannels()} reports what
     * {@code ASIOCreateBuffers} actually handed back.</p>
     *
     * <p>When no count is available — no capability shim, or
     * {@code ASIOGetChannels} failed — the request keeps the previous
     * behaviour, and the zero-output guard cannot fire: {@code outputs} is
     * still {@code formatChannels}. Non-Windows hosts and shim-less Windows
     * builds are therefore untouched by it.</p>
     *
     * @return {@code {inputs, outputs}}
     * @throws AudioBackendException when the driver reports no output channels
     *                               at all, so this playback open would
     *                               otherwise succeed into a stream nobody can
     *                               hear; or when the combined request exceeds
     *                               the native shim's
     *                               {@code MAX_STREAM_CHANNELS} cap, rather
     *                               than letting {@code ASIOCreateBuffers} fail
     *                               opaquely
     */
    private static int[] negotiateChannelCounts(int formatChannels, String driverName) {
        int inputs = formatChannels;
        int outputs = formatChannels;
        Optional<int[]> reported = driverChannelCounts();
        if (reported.isPresent()) {
            int reportedInputs = reported.get()[0];
            int reportedOutputs = reported.get()[1];
            inputs = Math.clamp(reportedInputs, 0, formatChannels);
            outputs = Math.clamp(reportedOutputs, 0, formatChannels);
            if (outputs == 0 && formatChannels > 0) {
                // Only the DRIVER's own reported count can clamp the request
                // away like this; a request of zero channels never reaches
                // here with formatChannels > 0.
                throw new AudioBackendException(
                        "ASIO driver reports no output channels: " + reportedInputs
                                + " input(s) / " + reportedOutputs + " output(s) clamps"
                                + " this " + formatChannels + "-channel playback request"
                                + " to 0 output(s). This rung is FAILING so the engine's"
                                + " fallback ladder can fall through to another backend,"
                                + " rather than succeeding into a silent no-output stream"
                                + " that would report progress nobody can hear: "
                                + driverName);
            }
        }
        if (inputs + outputs > AsioStreamingShim.MAX_STREAM_CHANNELS) {
            throw new AudioBackendException(
                    "ASIO driver rejected channel request " + inputs + " input(s) + "
                            + outputs + " output(s): the native shim activates at most "
                            + AsioStreamingShim.MAX_STREAM_CHANNELS
                            + " channels in total (inputs + outputs), so a symmetric "
                            + "request tops out at "
                            + (AsioStreamingShim.MAX_STREAM_CHANNELS / 2)
                            + " channels: " + driverName);
        }
        return new int[] {inputs, outputs};
    }

    /**
     * Reads {@code ASIOGetChannels(numInputChannels, numOutputChannels)} via
     * the capability shim, or {@link Optional#empty()} when the shim, the
     * symbol, or the driver cannot answer.
     */
    private static Optional<int[]> driverChannelCounts() {
        try (AsioCapabilityShim shim = capabilityShimFactory.get()) {
            return shim.getChannelCount();
        }
    }

    /**
     * Returns a streaming shim when the native library exports the story-311
     * symbols, or {@code null} when it does not — in which case the caller
     * keeps the story-310 no-streaming path. An unusable shim is closed
     * immediately so its FFM arena is not leaked.
     */
    private static AsioStreamingShim acquireStreamingShim() {
        AsioStreamingShim candidate = streamingShimFactory.get();
        if (streamingShimUsable(candidate)) {
            return candidate;
        }
        candidate.close();
        return null;
    }

    /**
     * The single definition of "this streaming shim can drive the story-311
     * buffer-switch path" (story 316 review). Both the pre-open probe in
     * {@link #supportsStreaming()} and the real acquisition in
     * {@link #acquireStreamingShim()} answer from here, so the selector can
     * never offer an ASIO that {@code open()} would then degrade to the
     * story-310 silent path.
     */
    private static boolean streamingShimUsable(AsioStreamingShim shim) {
        return shim.isStreamingAvailable();
    }

    /** The opened format's channel set: {@code {0, 1, …, channels - 1}}. */
    private static int[] channelIndices(int channels) {
        int[] indices = new int[channels];
        for (int i = 0; i < channels; i++) {
            indices[i] = i;
        }
        return indices;
    }

    /**
     * Rejects a buffer size the driver does not accept (stories 213 / 220
     * &times; 311). When no driver-reported range is available — the {@code asioshim}
     * library is missing, or {@code ASIOGetBufferSize} failed — the requested
     * size is accepted unchanged, exactly as before story 311.
     */
    private static void requireAcceptedBufferSize(int bufferFrames) {
        Optional<BufferSizeRange> reported;
        try (AsioCapabilityShim shim = capabilityShimFactory.get()) {
            reported = shim.isAvailable() ? shim.getBufferSize() : Optional.empty();
        }
        if (reported.isEmpty()) {
            return;
        }
        BufferSizeRange range = reported.get();
        if (range.accepts(bufferFrames)) {
            return;
        }
        throw new AudioBackendException(
                "ASIO driver rejected buffer size " + bufferFrames
                        + " frames: driver reports min=" + range.min()
                        + " max=" + range.max()
                        + " preferred=" + range.preferred()
                        + " granularity=" + range.granularity());
    }

    /**
     * Undoes a partially completed {@link #open(DeviceId, AudioFormat, int)}.
     * The teardown order matches {@link #close()}: stop and dispose the
     * driver's buffers, uninstall the upcall, and only then free the stub's
     * arena — a late callback must never jump into released memory. The
     * {@code asioMessage} shim is closed too, so a failed open never leaves a
     * registered upcall behind (it is now installed before
     * {@code ASIOCreateBuffers}). The driver shim itself is handed to
     * {@link #releaseDriverShim(AsioDriverShim)}, which may defer its close for
     * the reason documented there; every Java-side field is cleared here
     * regardless, so a failed open never leaves a half-live backend behind.
     * Clearing the fields is not the same as giving the DEVICE back, though: a
     * deferred release leaves this backend reporting {@link #isReleasePending()}
     * {@code true} after the rollback, and the caller must read the failed open
     * as a rung whose handle is RETAINED rather than as an ordinary refusal
     * (story 316 re-review).
     *
     * <p>When the uninstall could not be CONFIRMED — the call could not be
     * made, was refused on arrival, did not complete within its budget, was
     * interrupted, or failed at the FFM boundary; see
     * {@link AsioStreamingShim#uninstallBufferSwitchCallback()} for the full
     * list, and note that the driver is not involved in any of them — the
     * stub's arena is retained rather than freed, exactly as in
     * {@link #close()}. See {@link #tearDownStreaming(AsioStreamingShim)}.</p>
     */
    private void rollbackOpen(AsioDriverShim candidate, AsioStreamingShim streaming,
                              AsioBufferSwitchShim bridge, AsioFormatChangeShim callback,
                              boolean marked) {
        bufferSwitchShim = null;
        streamingShim = null;
        formatChangeShim = null;
        if (bridge != null) {
            bridge.stopStreaming();
        }
        // No streaming shim means no upcall was ever installed, so nothing can
        // be holding the stub's address and the full release is safe.
        boolean upcallUninstalled = true;
        if (streaming != null) {
            upcallUninstalled = tearDownStreaming(streaming);
        }
        if (bridge != null) {
            if (upcallUninstalled) {
                bridge.close();
            } else {
                logRetainedUpcallStub();
                bridge.closeRetainingUpcallStub();
            }
        }
        if (streaming != null) {
            streaming.close();
        }
        if (callback != null) {
            callback.close();
        }
        if (marked) {
            support.markClosed();
        }
        activeDriverName = null;
        releaseDriverShim(candidate);
    }

    /**
     * Runs the driver-side half of the story-311 teardown — {@code ASIOStop},
     * {@code ASIODisposeBuffers}, then uninstalling the buffer-switch upcall —
     * logs a driver that refused either of the first two, and reports whether
     * the upcall was provably uninstalled.
     *
     * <p>The native shim propagates the driver's own status rather than always
     * answering "OK": {@code asioshim_stop} and {@code asioshim_disposeBuffers}
     * return success only for the genuine "nothing to do" cases (not started, no
     * buffers, no driver loaded) and failure when the SDK call was actually made
     * and the driver refused it. A driver that ignores {@code ASIOStop} and
     * keeps firing {@code bufferSwitch} is therefore detectable here, and is
     * logged at {@link Level#WARNING} naming the refused call and the driver.</p>
     *
     * <p><strong>Why the teardown then continues instead of bailing out.</strong>
     * The caller frees the upcall stub's arena immediately after this returns
     * {@code true}, so the question a refusal raises is whether a still-running
     * driver can reach that stub. It cannot, because the native shim closes
     * both windows:</p>
     * <ul>
     *   <li>{@code asioshim_disposeBuffers} publishes "no buffers created"
     *       before it re-enters the SDK and then waits on a bounded in-flight
     *       barrier, so no callback is inside the native {@code bufferSwitch}
     *       body when {@code ASIODisposeBuffers} frees the buffers that body
     *       writes into; and</li>
     *   <li>{@code uninstallAsioBufferSwitchCallback} stores a null callback
     *       pointer and drains that same barrier again. Every callback that
     *       arrives afterwards loads null and returns without ever touching the
     *       upcall stub.</li>
     * </ul>
     *
     * <p>Aborting on a refused {@code ASIOStop} would therefore make nothing
     * safer — it would skip the uninstall that is the actual protection, leak
     * the upcall arena and the {@code asio-input-drain} thread for the life of
     * the process, and leave the driver streaming into buffers nobody disposes.
     * The native {@code asioshim_unloadDriver} repeats this same teardown before
     * {@code ASIOExit}, which is the last chance to quiesce such a driver.</p>
     *
     * <p><strong>Both of those windows stay OPEN when the control thread is not
     * quiesced.</strong> All three calls here are bounded, so while an earlier
     * downcall its caller stopped waiting for (by budget or by interrupt) is
     * still executing they are refused on arrival by
     * {@link AsioControlThread#isQuiesced()} and never reach the
     * driver at all — no buffer gate is closed and no callback pointer is
     * nulled. That state is also self-inflicted mid-teardown: a
     * {@code streaming.stop()} the control thread has already STARTED and that
     * then outlives its budget is abandoned in flight, which is precisely what
     * makes the {@code disposeBuffers()} and the uninstall behind it
     * refusable. Starting is the condition, not submission — a budget that
     * expires while the operation is still QUEUED withdraws it instead
     * ({@code AsioControlThread.settleAbandonedWait} wins {@code QUEUED ->
     * WITHDRAWN} before the task's own {@code QUEUED -> RUNNING}), which never
     * touches {@code ABANDONED_IN_FLIGHT}, so {@link AsioControlThread#isQuiesced()}
     * stays true and the calls behind it are NOT refused. The
     * warnings below say "not attempted" rather than blaming the driver for
     * that, and the driver shim's own release is deferred by
     * {@link #releaseDriverShim(AsioDriverShim)} so that
     * {@code asioshim_unloadDriver}'s backstop teardown still runs once the
     * driver returns.</p>
     *
     * <p><strong>The fate of the upcall stub is decided by the uninstall's own
     * outcome, never by a quiescence sample.</strong>
     * {@link AsioStreamingShim#uninstallBufferSwitchCallback()} answers
     * {@code true} only when the shim's registered buffer-switch callback is
     * provably no longer the stub's address, and that answer is what this
     * method returns. No sample can stand in for it: taken before the call it
     * cannot see the call be skipped, refused, time out, be interrupted or
     * fail at the FFM boundary, and taken after it reads {@code true} again
     * the moment an abandoned call returns — reporting a clean teardown for an
     * uninstall that never ran, and freeing an arena the shim's callback
     * pointer still holds. {@link #close()} and
     * {@code rollbackOpen(...)} therefore end the bridge with
     * {@link AsioBufferSwitchShim#closeRetainingUpcallStub()} whenever this
     * answers {@code false}, leaking one stub for the life of the process: the
     * driver may still fire {@code bufferSwitch} into the shim's trampoline,
     * which forwards through that pointer, and releasing its arena would
     * turn the next callback into a jump into released memory — a crash, not a
     * leak. It is the same trade this method already makes by continuing past a
     * refused {@code ASIOStop} instead of skipping "the actual protection".</p>
     *
     * <p>The two {@link AsioControlThread#isQuiesced()} samples below are
     * DIAGNOSTIC ONLY, and they PREDICT rather than decide: the gate is
     * re-read inside {@code AsioControlThread.call(...)}, which is what
     * actually refuses a bounded operation. They exist so that a refused
     * {@code ASIOStop} or {@code ASIODisposeBuffers} can be attributed to the
     * host's own fail-fast gate rather than to the driver, and each is taken
     * immediately before the call it describes because the preceding call can
     * change the answer. A benign race remains between each sample and its
     * submission — {@code AsioControlThread.call(...)} itself is the only
     * authority on what became of a call — but nothing load-bearing rests on
     * them: the one decision that can crash the process reads the uninstall's
     * return value instead.</p>
     *
     * @return whether the uninstall provably completed, so the caller may free
     *         the upcall stub's arena. {@code false} means the shim's
     *         registered buffer-switch callback may still be the stub's
     *         address and the caller must retain it.
     */
    private boolean tearDownStreaming(AsioStreamingShim streaming) {
        boolean stopAttempted = AsioControlThread.isQuiesced();
        boolean stopped = streaming.stop();
        // Re-sampled: a stop() the control thread had already STARTED and that
        // then outlived its caller's wait (budget or interrupt) is abandoned in
        // flight, so the dispose behind it is refused on arrival by the host
        // rather than by the driver. (A budget that expires while the stop is
        // still queued withdraws it instead and leaves the gate open.)
        boolean disposeAttempted = AsioControlThread.isQuiesced();
        boolean disposed = streaming.disposeBuffers();
        warnIfDriverRefusedTeardown(stopped, stopAttempted, disposed, disposeAttempted);
        // The uninstall's OWN outcome, not a sample: it is what tells the
        // caller whether freeing the upcall stub's arena is a release or a
        // crash.
        return streaming.uninstallBufferSwitchCallback();
    }

    /**
     * Records the one case in which the buffer-switch upcall stub is
     * deliberately leaked: {@link #tearDownStreaming(AsioStreamingShim)} could
     * not CONFIRM the uninstall, so the shim's registered buffer-switch
     * callback may still be the stub's address.
     * {@link Level#SEVERE} because the alternative
     * the process just avoided is a crash, and because the leak is permanent —
     * nothing later in this process can reclaim that arena.
     *
     * <p>The message describes the SHAPE of the failure rather than picking
     * one cause, because {@link AsioStreamingShim#uninstallBufferSwitchCallback()}
     * cannot distinguish its five and a maintainer reading this line must not
     * be sent after the wrong one. It must also not name the driver: the
     * native {@code uninstallAsioBufferSwitchCallback} nulls a pointer and
     * drains in-flight callbacks without entering the ASIO SDK at all, so the
     * driver has no way to refuse the uninstall or to throw out of it (story
     * 316 review, round 4 — this line previously said it did).</p>
     */
    private void logRetainedUpcallStub() {
        String driver = activeDriverName;
        String named = driver == null ? "<no driver loaded>" : driver;
        LOG.log(Level.SEVERE,
                "ASIO upcall stub RETAINED (deliberately leaked) for " + named
                        + ": uninstalling the buffer-switch callback could not be"
                        + " CONFIRMED — the call could not be made, was refused on"
                        + " arrival while an earlier ASIO call executed past its"
                        + " budget, did not complete within its own budget, was"
                        + " interrupted, or failed at the FFM boundary. The driver is"
                        + " NOT involved either way: the uninstall nulls a callback"
                        + " pointer and drains in-flight callbacks without entering"
                        + " the ASIO SDK. What is unknown is whether that pointer is"
                        + " still the stub's address, so a bufferSwitch may still"
                        + " reach it through the shim's trampoline."
                        + " Freeing its arena now would turn the next"
                        + " bufferSwitch into a jump into released memory, so the stub"
                        + " and its arena are leaked for the life of the process"
                        + " instead. The bridge is quiesced and its asio-input-drain"
                        + " thread is stopped by this same teardown, so no audio"
                        + " crosses it in either direction.");
    }

    /**
     * Logs a teardown that did not fully succeed, splitting the calls the HOST
     * most likely never submitted from the calls that most likely reached the
     * DRIVER and were refused. Shared by
     * {@link #close()} / {@code rollbackOpen(...)} — via
     * {@link #tearDownStreaming} — and by the asynchronous
     * {@link #stopStreamingForDriverReset()} teardown, which runs the same two
     * calls and would otherwise discard the same information.
     *
     * <p>The two {@code Attempted} flags are what keep the attribution honest.
     * {@link AsioStreamingShim#stop()} and
     * {@link AsioStreamingShim#disposeBuffers()} answer {@code false} both for a
     * driver that ran the call and refused it and for a call the HOST never
     * submitted: while an earlier downcall its caller stopped waiting for (by
     * budget or by interrupt) is still executing, {@link AsioControlThread}
     * refuses every bounded operation on arrival (see
     * {@link AsioControlThread#isQuiesced()}). Reporting the
     * host's own gate as "the ASIO driver refused ASIOStop" would send a
     * maintainer after the wrong component and hide the one fact that matters —
     * that a named call is still wedged inside the driver — so the two get
     * different messages.</p>
     *
     * <p><strong>Both attributions are best guesses, not proofs</strong>
     * (story 316 review, round 4 — this javadoc previously claimed the
     * driver-refused wording was "reserved for a call that provably reached
     * the driver", which the gate cannot establish). Each flag records only
     * that {@link AsioControlThread#isQuiesced()} was true immediately before
     * the call, i.e. that the host believed it could submit. The gate is
     * re-read inside {@code AsioControlThread.call(...)}, so the sample
     * predicts rather than decides; and even a call that IS submitted through
     * an open gate can exhaust its own budget while still queued, in which
     * case {@code settleAbandonedWait} moves it {@code QUEUED -> WITHDRAWN} and
     * the driver never sees it. That is reachable whenever the control thread
     * is busy with a call the host has NOT given up on — the unbounded modal
     * control panel being the obvious one — because such a call leaves
     * {@code isQuiesced()} true. The driver-refused message therefore carries
     * its own hedge.</p>
     *
     * <p>Each flag is sampled immediately before the call it describes rather
     * than once for the pair, because a {@code stop()} the control thread has
     * already STARTED and that then outlives its budget is abandoned in
     * flight: the {@code disposeBuffers()} behind it is then refused on
     * arrival by the host, and one earlier sample would blame the driver for
     * that refusal. (Starting is the condition:
     * {@code abandonWhileRunning} only counts an operation abandoned on a
     * winning {@code RUNNING -> ABANDONED} compare-and-set, so a budget that
     * expires while the call is still queued withdraws it and leaves the gate
     * open.) For the same reason this emits up to TWO warnings — one naming
     * the calls the host most likely never submitted, one naming the calls
     * that most likely reached the driver and were refused — so a mixed
     * teardown reports both instead of picking one. Nothing that can crash the
     * process depends on either: the fate of the upcall stub is decided by the
     * uninstall's own return value, see
     * {@link #tearDownStreaming(AsioStreamingShim)}.</p>
     *
     * @param stopped          whether {@code ASIOStop} reported success
     * @param stopAttempted    whether the host's fail-fast gate
     *                         ({@link AsioControlThread#isQuiesced()}) was open
     *                         immediately before {@code ASIOStop} — i.e.
     *                         whether the host believed it could submit that
     *                         call, not whether the driver received it
     * @param disposed         whether {@code ASIODisposeBuffers} reported success
     * @param disposeAttempted the same reading taken immediately before
     *                         {@code ASIODisposeBuffers}
     */
    private void warnIfDriverRefusedTeardown(boolean stopped, boolean stopAttempted,
                                             boolean disposed, boolean disposeAttempted) {
        if (stopped && disposed) {
            return;
        }
        String driver = activeDriverName;
        String named = driver == null ? "<no driver loaded>" : driver;
        String neverSubmitted = nameCalls(!stopped && !stopAttempted,
                !disposed && !disposeAttempted);
        if (!neverSubmitted.isEmpty()) {
            LOG.log(Level.WARNING,
                    "ASIO teardown NOT ATTEMPTED (" + neverSubmitted + ") for " + named
                            + ": the asio-control thread is still executing an earlier"
                            + " ASIO call its caller stopped waiting for (budget expired"
                            + " or interrupted), so the host refused"
                            + " to submit these calls. This is the host's own fail-fast"
                            + " gate, NOT a driver refusal — the driver almost certainly"
                            + " never saw them. The driver's buffers stay created and it"
                            + " may still be firing bufferSwitch until that earlier call"
                            + " returns. Attribution caveat: the gate is sampled just"
                            + " before each call, so an earlier call that returned in"
                            + " between could have let one through after all.");
        }
        String refusedByDriver = nameCalls(!stopped && stopAttempted,
                !disposed && disposeAttempted);
        if (!refusedByDriver.isEmpty()) {
            LOG.log(Level.WARNING,
                    "ASIO driver refused " + refusedByDriver + " during teardown: " + named
                            + ". The driver may still be firing bufferSwitch. The "
                            + "teardown continues: the native shim closes its buffer "
                            + "gate and waits on a bounded in-flight callback barrier "
                            + "before anything a callback touches is released. "
                            + "Attribution caveat: this is inferred from the host's "
                            + "fail-fast gate being OPEN just before the call, which "
                            + "means the host believed it could submit — not that the "
                            + "driver received it. A call that queued behind a healthy "
                            + "long-running downcall (the driver's modal control panel, "
                            + "typically) and then exhausted its own budget is withdrawn "
                            + "unseen and reported here too.");
        }
    }

    /**
     * Names the selected subset of the teardown's two calls exactly as the
     * single pre-split message worded them, so a maintainer's existing log
     * grep keeps matching. The empty string means "neither", which is how
     * {@link #warnIfDriverRefusedTeardown(boolean, boolean, boolean, boolean)}
     * decides that one of its two attributions has nothing to report.
     *
     * @param stop    include {@code ASIOStop}
     * @param dispose include {@code ASIODisposeBuffers}
     * @return the joined call names, or the empty string when neither is selected
     */
    private static String nameCalls(boolean stop, boolean dispose) {
        if (stop && dispose) {
            return "ASIOStop and ASIODisposeBuffers";
        }
        if (stop) {
            return "ASIOStop";
        }
        if (dispose) {
            return "ASIODisposeBuffers";
        }
        return "";
    }

    private static String resolveDriverName(AsioDriverShim shim, DeviceId device) {
        if (!device.isDefault()) {
            return device.name();
        }
        return shim.listDrivers().stream()
                .findFirst()
                .map(AsioDriverShim.DriverDescriptor::name)
                .orElseThrow(() -> new AudioBackendException(
                        "No installed ASIO driver is available for the default device"));
    }

    private static AudioBackendException unavailableException() {
        return new AudioBackendException(
                "ASIO is not available on this host. Install an ASIO driver "
                        + "(e.g. ASIO4ALL) and bundle daw-core/native/asio/asioshim.dll.");
    }

    /**
     * Live capture stream fed by the ASIO buffer-switch callback (story 311).
     *
     * <p>Each callback de-interleaves the driver's input half into a lock-free
     * ring; the dedicated {@code asio-input-drain} daemon thread then allocates
     * one {@link AudioBlock} per captured block, in order, and publishes it
     * here. Marshalling the publish off the driver's real-time thread is what
     * keeps the callback allocation- and lock-free, and it means every
     * published block owns a private sample array — {@link AudioBlock}'s
     * documented immutability holds and subscribers need not copy.</p>
     *
     * <p>Delivery is non-blocking in both stages: a subscriber that cannot keep
     * up loses blocks rather than stalling the audio device, and a drain thread
     * that cannot keep up loses blocks rather than stalling the driver.</p>
     */
    @Override
    public Flow.Publisher<AudioBlock> inputBlocks() {
        return support.inputBlocks();
    }

    /**
     * Exposes {@link AudioDeviceEvent}s derived from ASIO's
     * {@code kAsioResetRequest} (driver-initiated drop),
     * {@code kAsioBufferSizeChange}, and {@code kAsioResyncRequest}
     * callbacks installed on driver open.
     *
     * <p>The native shim translates those driver notifications into
     * {@link AudioDeviceEvent}s; delivery semantics are defined by the
     * {@link AudioBackend#deviceEvents()} contract. Format-change
     * requests in particular (story 218) are surfaced as
     * {@link AudioDeviceEvent.FormatChangeRequested} via
     * {@link #publishFormatChangeRequested(DeviceId, java.util.Optional,
     * FormatChangeReason)} from the native callback running on the
     * ASIO host-callback thread; see that method's Javadoc for the
     * exact mapping from each ASIO callback to a
     * {@link FormatChangeReason}.</p>
     *
     * <p><strong>Reopen contract (stories 218 &times; 311).</strong> A
     * {@code kAsioResetRequest} / {@code kAsioBufferSizeChange} quiesces the
     * streaming bridge and disposes the driver's buffers, but it deliberately
     * does <em>not</em> mark the backend closed — the driver is still loaded.
     * {@link #isOpen()} therefore keeps reporting {@code true} and a second
     * {@link #open(DeviceId, AudioFormat, int)} throws
     * {@link IllegalStateException}. The consumer of a
     * {@link AudioDeviceEvent.FormatChangeRequested} must
     * {@link #close()} this backend and then {@code open(...)} it again with
     * the new format / buffer size.</p>
     */
    @Override
    public Flow.Publisher<AudioDeviceEvent> deviceEvents() {
        return support.deviceEvents();
    }

    /**
     * Hook called by the native ASIO host-callback shim under
     * {@code daw-core/native/asio/} to surface a driver-initiated
     * reset request as a {@link AudioDeviceEvent.FormatChangeRequested}
     * event on this backend's {@link #deviceEvents()} publisher.
     *
     * <p>Mapping conventions used by the shim:</p>
     * <ul>
     *   <li>{@code kAsioBufferSizeChange(newFrames)} &rarr;
     *       {@code reason = }{@link FormatChangeReason.BufferSizeChange};
     *       {@code proposedFormat} can only carry the previously opened
     *       sample rate / channel count / bit depth, because
     *       {@code bufferFrames} is negotiated separately via
     *       {@link AudioBackend#open(DeviceId, AudioFormat, int)} and is
     *       not part of {@link AudioFormat}. The new frame count is
     *       carried as
     *       {@link FormatChangeReason.BufferSizeChange#newBufferFrames()}.</li>
     *   <li>{@code kAsioResetRequest} after a successful
     *       {@code ASIOSetSampleRate(newRate)} &rarr;
     *       {@code reason = }{@link FormatChangeReason.SampleRateChange};
     *       the new format carries the new sample rate.</li>
     *   <li>{@code kAsioResyncRequest} &rarr;
     *       {@code reason = }{@link FormatChangeReason.ClockSourceChange};
     *       the proposed format is typically empty since the rate /
     *       buffer size do not necessarily change.</li>
     *   <li>any other {@code kAsioResetRequest} (USB streaming-mode
     *       change, USB hub cycle, vendor utility "reset") &rarr;
     *       {@code reason = }{@link FormatChangeReason.DriverReset}.</li>
     * </ul>
     *
     * <p>Package-private: only the SDK's native shim is meant to call
     * this directly. The publisher accepts the event without blocking
     * (the underlying {@code SubmissionPublisher.offer(...)} drops
     * under back-pressure rather than stalling), so it is safe to call
     * from the ASIO host-callback thread.</p>
     *
     * @param device         the affected device id; must not be null
     * @param proposedFormat the format the driver is moving to, when known;
     *                       must not be null (use
     *                       {@link java.util.Optional#empty()} for unknown)
     * @param reason         why the driver is asking for a reset; must not be null
     */
    void publishFormatChangeRequested(DeviceId device,
                                      java.util.Optional<AudioFormat> proposedFormat,
                                      FormatChangeReason reason) {
        Objects.requireNonNull(device, "device must not be null");
        Objects.requireNonNull(proposedFormat, "proposedFormat must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        support.publishDeviceEvent(
                new AudioDeviceEvent.FormatChangeRequested(device, proposedFormat, reason));
    }

    /**
     * Hands a rendered block to the ASIO buffer-switch bridge (story 311).
     * The block is copied into a lock-free single-producer / single-consumer
     * ring; the next {@code bufferSwitch} pulls the most recent one and
     * interleaves it into the driver's output buffers. The call never blocks:
     * a full ring drops the block.
     *
     * <p>Before {@link #open(DeviceId, AudioFormat, int)} — or on a build
     * without the native streaming shim — the block is validated and
     * discarded, exactly as in story 310.</p>
     *
     * @throws AudioBackendException when the block's frame count does not match
     *                               the size the stream was opened at. An
     *                               engine rendering 64-frame blocks into a
     *                               128-frame ASIO stream would otherwise
     *                               produce half-audio / half-silence buffers
     *                               with no diagnostic; the story requires a
     *                               buffer-size mismatch to surface "rather
     *                               than silently resizing"
     */
    @Override
    public void sink(AudioBlock block) {
        support.validateOutgoing(block);
        // Capture the swappable reference exactly once: close() may null the
        // field concurrently with a render-thread sink(...).
        AsioBufferSwitchShim bridge = bufferSwitchShim;
        if (bridge == null) {
            return;
        }
        // The bridge is the authority on the negotiated size — it is what
        // actually indexes the driver's buffers.
        int streamFrames = bridge.bufferFrames();
        if (block.frames() != streamFrames) {
            throw new AudioBackendException(
                    "ASIO driver rejected block size " + block.frames()
                            + " frames: the open stream runs at " + streamFrames
                            + " frames per buffer. Re-open the backend for the "
                            + "new size rather than resizing silently.");
        }
        bridge.write(block);
    }

    /**
     * {@inheritDoc}
     *
     * <p>ASIO's real backpressure signal (story 316): polls the buffer-switch
     * bridge's output-ring occupancy in {@code timeoutNanos / 8} park slices,
     * returning as soon as the driver's own {@code bufferSwitch} has
     * <em>consumed</em> the previously written {@link #sink(AudioBlock)}
     * block — that is, as soon as the ring is empty, not as soon as it merely
     * has a free slot. The distinction is the point: the callback drains the
     * whole queue and plays only its newest block, so spare capacity is not a
     * request for more audio, and gating on it would let the pump render (and
     * the transport advance) several blocks per callback with all but one of
     * them discarded unheard. This is how the render pump ends up paced by the
     * device clock: the ring only empties at the rate the hardware calls back.
     * The poll loop itself is the shared
     * {@link AudioBackend#pollForSinkCapacity(long, java.util.function.BooleanSupplier)}
     * helper, so this backend and {@code daw-core}'s callback-backend adapter
     * cannot drift apart (story 316 review) — the LOOP, that is. Their
     * PREDICATES differ deliberately and must not be reconciled: this one
     * passes {@code outputRingDrained()} because its consumer swallows the
     * whole queue, while {@code CallbackBackendAdapter.awaitSinkCapacity}
     * passes {@code InterleavedBlockRing::hasSpace} because ITS consumer,
     * {@code InterleavedBlockRing.readInto}, is strict FIFO — one slot per
     * callback, oldest first, never skipping — so a free slot there really
     * does mean the device took a block and discarded nothing.</p>
     *
     * <p>Implemented entirely on the calling (non-real-time) side — a
     * read-only poll of the ring's occupancy counters. It adds no work to the
     * sentinel-tested real-time {@code bufferSwitch} / {@code write} paths.
     * When no stream is open (or the native streaming shim is absent) there
     * is no ring to poll, so this falls back to the default full-timeout
     * park, which degrades gracefully to wall-clock pacing.</p>
     */
    @Override
    public void awaitSinkCapacity(long timeoutNanos) {
        // Capture the swappable reference exactly once: close() may null the
        // field concurrently with a pump-thread wait (house rule).
        AsioBufferSwitchShim bridge = bufferSwitchShim;
        if (bridge == null) {
            LockSupport.parkNanos(timeoutNanos);
            return;
        }
        AudioBackend.pollForSinkCapacity(timeoutNanos, bridge::outputRingDrained);
    }

    /**
     * Diagnostic / observability query (story 316 review): how many
     * engine-rendered blocks the driver's real {@code bufferSwitch} callback
     * has actually drained into its output buffers since the stream opened.
     *
     * <p>Transport advancing, or {@link #sink(AudioBlock)} accepting blocks,
     * proves only that the engine's render pump ran — the pump advances the
     * transport before it sinks, and its capacity wait times out after one
     * block period whether or not a device callback ever arrives. This count
     * is the signal that distinguishes "audio reached the hardware" from
     * "the output bridge is dead": it moves only when
     * {@link AsioBufferSwitchShim#bufferSwitch(int, int)} consumed a rendered
     * block rather than emitting silence. It is deliberately <em>not</em>
     * part of the {@link AudioBackend} contract.</p>
     *
     * <p>What exercises it, honestly (story 316 review): today, only
     * STUBBED tests — {@code AsioBackendStreamingTest} pins the closed /
     * no-bridge / live-bridge answers and {@code AsioBufferSwitchShimTest}
     * pins the counter's own advance-and-hold behaviour, both driving a fake
     * shim with no driver anywhere. daw-core's engine-level Windows proof,
     * {@code AsioEngineStreamingIntegrationTest}, asserts this count
     * strictly increases, but it runs in no environment this project
     * currently has: it is gated on Windows, then on {@code asioshim.dll}
     * being on the FFM library path, then on an ASIO driver actually being
     * installed — and the hosted {@code windows-latest} lane in
     * {@code .github/workflows/windows-asioshim.yml} has no driver, so it
     * stops at that assumption. Real proof needs a Windows host with BOTH
     * an ASIO driver and the shim: a developer machine with an interface,
     * or a self-hosted runner with one. Treat green CI as evidence about the
     * counter's plumbing, never as evidence that audio reached hardware.</p>
     *
     * @return rendered blocks consumed by the driver callback, or {@code 0}
     *         when the backend is closed or the native streaming shim was
     *         unavailable (no bridge installed)
     */
    public long renderedBlocksConsumedByDriver() {
        // Read the swappable reference exactly once: close() may null the
        // field concurrently with this query (house rule).
        AsioBufferSwitchShim bridge = bufferSwitchShim;
        return bridge == null ? 0L : bridge.renderedBlocksConsumed();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The number of input channels {@code ASIOCreateBuffers} really handed
     * back — <em>not</em> the number
     * {@link #negotiateChannelCounts(int, String)} asked for. The two differ
     * whenever a driver accepts the call but exposes no usable buffer for a
     * channel, and it is the buffers, not the request, that decide whether
     * {@link #inputBlocks()} carries audio or silence: the bridge binds a
     * converter only to a channel with both halves mapped, and a channel
     * without one is memset to zero on every callback. Counting the request
     * would promise capture that the very next {@code bufferSwitch} would
     * contradict.</p>
     *
     * <p>{@code 0} when the backend is closed, when the native streaming shim
     * was unavailable so no bridge was ever installed (every non-Windows host,
     * and every Windows build made without the Steinberg SDK — those opens
     * stream no audio at all), and once a {@code kAsioResetRequest} teardown
     * has quiesced the bridge. Nothing resets it explicitly on close: the count
     * lives on the bridge, and {@link #close()} and
     * {@link #rollbackOpen(AsioDriverShim, AsioStreamingShim,
     * AsioBufferSwitchShim, AsioFormatChangeShim, boolean)} both null
     * {@code bufferSwitchShim}, which is the same single place every other
     * stream-scoped fact here is dropped.</p>
     *
     * <p>This is what the engine verifies after a
     * {@link CaptureRequirement#REQUIRED} open, and it is where the recording
     * path's zero-input refusal comes from — deliberately here rather than as a
     * guard inside {@link #negotiateChannelCounts(int, String)}, which must
     * keep opening playback-only interfaces. See that method for why.</p>
     */
    @Override
    public int openedInputChannels() {
        // Read the swappable reference exactly once, for the same reason as
        // renderedBlocksConsumedByDriver above (house rule).
        AsioBufferSwitchShim bridge = bufferSwitchShim;
        return bridge == null ? 0 : bridge.boundInputChannels();
    }

    @Override
    public boolean isOpen() {
        return support.isOpen();
    }

    /**
     * Returns {@code Optional.of(this::invokeAsioControlPanel)} when
     * the {@code asioshim} library is present and exports
     * {@code asioshim_openControlPanel} (story 212), and
     * {@link Optional#empty()} otherwise.
     *
     * <p>The dialog ({@code AudioSettingsDialog}) uses the empty
     * result to disable the "Open Driver Control Panel" button with
     * the existing tooltip. The non-empty result triggers the FFM
     * downcall to Steinberg's {@code ASIOControlPanel()} via the
     * native shim under {@code daw-core/native/asio/}.</p>
     */
    @Override
    public Optional<Runnable> openControlPanel() {
        try (AsioCapabilityShim shim = capabilityShimFactory.get()) {
            if (!shim.isControlPanelAvailable()) {
                return Optional.empty();
            }
        }
        return Optional.of(this::invokeAsioControlPanel);
    }

    /**
     * Performs the FFM downcall to Steinberg's {@code ASIOControlPanel()}
     * on a dedicated daemon platform thread and propagates any failure
     * back to the calling thread as an {@link AudioBackendException}
     * (story 212).
     *
     * <p>The native call may block until the user closes the modal
     * panel, so it must not run on the JavaFX thread (which would
     * freeze the UI) nor directly on a virtual thread (the carrier
     * would be pinned by the modal Win32 dialog's message pump). A
     * daemon platform thread satisfies both constraints while
     * {@link CompletableFuture#join()} communicates the result back
     * to the caller without busy-waiting or timing fragility.</p>
     *
     * <p>Per the {@link AudioBackend#openControlPanel()} contract,
     * failures are surfaced as {@link AudioBackendException} so the
     * caller (e.g. {@code AudioSettingsDialog#onOpenControlPanel})
     * can present a user-visible notification. Success means the
     * native panel was shown (and possibly already closed).</p>
     */
    private void invokeAsioControlPanel() {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Thread.ofPlatform()
                .name("asio-control-panel")
                .daemon(true)
                .start(() -> {
                    try (AsioCapabilityShim shim = capabilityShimFactory.get()) {
                        if (!shim.isControlPanelAvailable()) {
                            result.completeExceptionally(new AudioBackendException(
                                    "ASIO control panel is not available: the asioshim "
                                            + "library or asioshim_openControlPanel symbol is missing."));
                            return;
                        }
                        int rc = shim.openControlPanel();
                        if (rc == AsioCapabilityShim.CONTROL_PANEL_NOT_PRESENT) {
                            result.completeExceptionally(new AudioBackendException(
                                    "Driver does not provide a control panel"));
                        } else if (rc != 1) {
                            result.completeExceptionally(new AudioBackendException(
                                    "Could not launch ASIO control panel: " + rc));
                        } else {
                            result.complete(null);
                        }
                    } catch (AudioBackendException e) {
                        result.completeExceptionally(e);
                    } catch (RuntimeException e) {
                        result.completeExceptionally(new AudioBackendException(
                                "ASIO control panel launch failed: " + e.getMessage(), e));
                    }
                });
        try {
            result.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof AudioBackendException abe) {
                throw abe;
            }
            throw new AudioBackendException(
                    "ASIO control panel launch failed", cause);
        }
    }

    /**
     * Tears the stream down in the order story 311 mandates:
     * {@code ASIOStop} &rarr; {@code ASIODisposeBuffers} &rarr; uninstall the
     * buffer-switch upcall &rarr; free the upcall arena &rarr; the existing
     * story-310 {@code asioMessage} uninstall and {@code ASIOExit}.
     *
     * <p>Freeing the upcall arena strictly after the uninstall is the load-
     * bearing part: a callback that arrives between the two would otherwise
     * jump into released memory. Closing the buffer-switch shim also flushes
     * and joins the {@code asio-input-drain} thread, so no captured block is
     * published after this method returns. Idempotent.</p>
     *
     * <p>The concluding {@code ASIOExit} goes through
     * {@link #releaseDriverShim(AsioDriverShim)}, which defers it while the
     * control thread is still executing a call the host abandoned: the shim can
     * only ever be closed once, and closing it then would spend that one chance
     * on downcalls that cannot reach the driver. A close that deferred still
     * returns NORMALLY — there is nothing here to fail — so it reports the
     * deferral through {@link #isReleasePending()} instead, and a caller on a
     * lifecycle path must read that before treating the device as given back
     * (story 316 re-review).</p>
     *
     * <p>A driver that refuses {@code ASIOStop} or {@code ASIODisposeBuffers} is
     * logged and the teardown carries on; see
     * {@link #tearDownStreaming(AsioStreamingShim)} for why continuing is the
     * safe response and aborting is not.</p>
     *
     * <p>A teardown that could not CONFIRM the uninstall — the call could not
     * be made, was refused on arrival while an earlier call executed past its
     * budget, did not complete within its own budget, was interrupted, or
     * failed at the FFM boundary; the driver is a party to none of these, see
     * {@link AsioStreamingShim#uninstallBufferSwitchCallback()} — ends the
     * bridge with
     * {@link AsioBufferSwitchShim#closeRetainingUpcallStub()} instead. The
     * shim's registered buffer-switch callback may still be the stub's
     * address, so freeing its arena would be a
     * crash rather than a leak; the stub is retained for the life of the
     * process and the choice is logged at {@link Level#SEVERE}.</p>
     */
    @Override
    public void close() {
        synchronized (DRIVER_LIFECYCLE_LOCK) {
            AsioStreamingShim streaming = streamingShim;
            streamingShim = null;
            AsioBufferSwitchShim bridge = bufferSwitchShim;
            bufferSwitchShim = null;
            if (bridge != null) {
                bridge.stopStreaming();
            }
            // No streaming shim means no upcall was ever installed, so nothing
            // can be holding the stub's address and the full release is safe.
            boolean upcallUninstalled = true;
            if (streaming != null) {
                upcallUninstalled = tearDownStreaming(streaming);
            }
            if (bridge != null) {
                if (upcallUninstalled) {
                    bridge.close();
                } else {
                    logRetainedUpcallStub();
                    bridge.closeRetainingUpcallStub();
                }
            }
            if (streaming != null) {
                streaming.close();
            }
            AsioFormatChangeShim callback = formatChangeShim;
            formatChangeShim = null;
            if (callback != null) {
                callback.close();
            }
            AsioDriverShim lifecycle = driverShim;
            driverShim = null;
            activeDriverName = null;
            if (lifecycle != null) {
                releaseDriverShim(lifecycle);
            }
            if (activeBackend == this) {
                activeBackend = null;
            }
            support.close();
        }
    }

    /**
     * Releases the driver shim — {@code ASIOExit} plus its FFM arena — but only
     * once the control thread is provably not inside a call the host abandoned.
     *
     * <p>{@link AsioDriverShim#close()} latches itself closed and its
     * {@code unloadDriver()} clears the ownership flag on the first attempt, so
     * a close whose downcalls all failed can never be retried. While an
     * abandoned call is executing every bounded downcall DOES fail immediately
     * (see {@link AsioControlThread#isQuiesced()}), so closing inline would
     * spend the single chance to run {@code ASIOExit} on a call that cannot
     * reach the driver, and leak both the driver and the arena for the life of
     * the process. The close is therefore handed to the shared
     * {@code asio-reset-teardown} executor, which waits for quiescence FIRST
     * and takes {@link #DRIVER_LIFECYCLE_LOCK} only afterwards — never the
     * other way round, or the wait would hold the very lock it exists to keep
     * free.</p>
     *
     * <p>Deferring is not free, and the {@link Level#SEVERE} log says so: until
     * the driver returns it may still hold — or take — the device. Two things
     * keep that from becoming two hosts on one device. The process-wide
     * invariant survives because {@code AsioDriverShim.loadDriver} refuses
     * while another wrapper still owns the driver: a re-open attempted in that
     * window fails cleanly instead of loading a second driver over the first.
     * And the fallback ladder no longer walks past this backend either
     * (story 316 re-review): the deferral is COUNTED and reported through
     * {@link #isReleasePending()}, so a caller that reads it sees the same
     * "handle RETAINED" disposition it would get from a {@code close()} that
     * threw, instead of an ordinary successful close it is entitled to
     * believe. It used to be silent, and the ladder opened PortAudio or Java
     * Sound over a device this driver might still be acquiring.</p>
     *
     * <p>The deferral is owned rather than fire-and-forget. A shut-down
     * executor rejects the task, and the close then happens inline — a close
     * that cannot reach the driver is still better than a shim nobody ever
     * closes.</p>
     */
    private void releaseDriverShim(AsioDriverShim shim) {
        if (AsioControlThread.isQuiesced()) {
            shim.close();
            return;
        }
        LOG.log(Level.SEVERE,
                "ASIO driver release DEFERRED: an ASIO call its caller stopped waiting"
                        + " for (budget expired or interrupted) is"
                        + " still executing, so ASIOExit would fail fast and"
                        + " AsioDriverShim.close() can only ever be attempted once. The"
                        + " close is queued until the driver returns (up to "
                        + DRIVER_RELEASE_BUDGET.toSeconds() + " s). Until then the driver"
                        + " may still hold — or take — the device even though the"
                        + " fallback ladder has moved on to another host, so the backend"
                        + " that replaces this one may find the device unavailable.");
        // Counted BEFORE the task is published, never after. This mirrors
        // AsioControlThread.abandonWhileRunning, which takes its count before
        // it publishes the RUNNING -> ABANDONED transition, and for the same
        // reason: incrementing afterwards would leave a window in which the
        // hazard is already live — the release is owed, the device may still
        // be held — while isReleasePending() answers "clear". A caller that
        // sampled the query in that window would walk its fallback ladder past
        // this backend, which is the one direction in which being wrong opens
        // a second host over a device this driver has not let go of.
        deferredDriverReleases.incrementAndGet();
        try {
            resetTeardownExecutor.execute(() -> {
                try {
                    if (!AsioControlThread.awaitQuiescence(DRIVER_RELEASE_BUDGET)) {
                        LOG.log(Level.SEVERE,
                                "ASIO driver did not return from its abandoned call within "
                                        + DRIVER_RELEASE_BUDGET.toSeconds() + " s. Closing the"
                                        + " driver shim anyway: its downcalls will fail fast,"
                                        + " so ASIOExit and the shim's arena are leaked for"
                                        + " the life of the process and the device may stay"
                                        + " held by the driver.");
                    }
                    synchronized (DRIVER_LIFECYCLE_LOCK) {
                        shim.close();
                    }
                } finally {
                    // In a finally, and strictly after the close: the query
                    // must keep answering "pending" for as long as this
                    // backend really owes the release, however the task ends.
                    deferredDriverReleases.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException rejected) {
            LOG.log(Level.SEVERE,
                    "ASIO deferred driver release was rejected (the teardown executor is"
                            + " shut down); closing the driver shim inline instead. Its"
                            + " downcalls fail fast while the driver is still executing,"
                            + " so ASIOExit may not reach the driver.", rejected);
            try {
                // Nothing was ever deferred, so the provisional count is handed
                // straight back. The close happens INLINE — before the caller's
                // own close()/open() returns — so isReleasePending() is already
                // false by the time anyone is entitled to read it, which is
                // exactly right: this backend owes nothing afterwards.
                shim.close();
            } finally {
                deferredDriverReleases.decrementAndGet();
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code true} while {@link #releaseDriverShim(AsioDriverShim)} has
     * queued an {@code ASIOExit} it could not issue yet — i.e. while this
     * backend's {@link #close()} has returned without the driver having been
     * given back (story 316 re-review).</p>
     *
     * <p>The motivating case is a {@code loadDriver} whose bounded budget
     * expired. {@link AsioDriverShim#loadDriver(String)} then answers
     * {@code false} while deliberately KEEPING its ownership claim, because a
     * timeout is not proof the driver failed to initialize — it is most likely
     * a driver still powering up an interface, which may yet hand this process
     * an initialized driver. {@link #open(DeviceId, AudioFormat, int)} releases
     * that candidate — which DEFERS — and throws an ordinary
     * {@link AudioBackendException}. A caller walking a fallback ladder used to
     * have no way to tell that hop from any other failed one: this backend's
     * {@code close()} is then a no-op (no shim was ever adopted) so it
     * succeeds, and the next rung opens PortAudio or Java Sound over a device
     * the ASIO driver may still be acquiring. This query is what makes the two
     * distinguishable — the handle is RETAINED, and the walk must stop rather
     * than open beside it.</p>
     *
     * <p>Self-clearing, as the contract requires: the deferred task decrements
     * the count in a {@code finally} after {@code AsioDriverShim.close()}, so
     * once the driver returns and the queued {@code ASIOExit} has run this
     * answers {@code false} and the device may be attempted again. A read of a
     * plain {@link AtomicInteger} — no lock, no downcall, no arena — so it is
     * safe on the lifecycle path that asks it immediately after
     * {@code close()}.</p>
     */
    @Override
    public boolean isReleasePending() {
        return deferredDriverReleases.get() > 0;
    }

    /**
     * Quiesces the streaming bridge and tears the driver's buffers down after
     * a driver-initiated reset (story 218 &times; 311).
     *
     * <p>Called from {@link AsioFormatChangeShim#dispatch(long, long)} — i.e.
     * from the driver's own host-callback thread — for
     * {@code kAsioResetRequest} and {@code kAsioBufferSizeChange}. The bridge
     * is quiesced inline with a plain volatile write so no further callback
     * touches the driver's buffers, but {@code ASIOStop} /
     * {@code ASIODisposeBuffers} are dispatched to the shared
     * {@code asio-reset-teardown} daemon executor: the ASIO contract forbids
     * calling driver functions from inside a host callback, and doing so would
     * deadlock against the shim's driver mutex. A driver that refuses either
     * call is logged at {@link Level#WARNING} exactly as in
     * {@link #tearDownStreaming(AsioStreamingShim)}; unlike that path this one
     * frees nothing — the upcall stays installed and the bridge alive — so the
     * refusal is a diagnostic rather than a hazard.</p>
     *
     * <p>The queued task rechecks that its captured streaming shim is still
     * current while holding the lifecycle lock. If {@link #close()} and a
     * subsequent {@link #open(DeviceId, AudioFormat, int)} have already moved
     * the backend to another stream, the stale task makes no native calls and
     * logs no refusal. The driver callback itself only quiesces the bridge and
     * enqueues this work; it never acquires that lock or waits.</p>
     *
     * <p><strong>The backend stays open.</strong> This does not call
     * {@code markClosed()} — the driver is still loaded, and short-circuiting
     * {@link #close()} would leak the driver shim. {@link #isOpen()} therefore
     * still reports {@code true} afterwards and a second
     * {@link #open(DeviceId, AudioFormat, int)} throws
     * {@link IllegalStateException}{@code ("backend already has an open
     * stream")}. That is the intended story-218 contract: the consumer of the
     * {@link AudioDeviceEvent.FormatChangeRequested} event must
     * {@link #close()} and then {@code open(...)} again, which re-creates the
     * driver's buffers at the new size.</p>
     *
     * <p>Idempotent, and a no-op when nothing is streaming. The request is
     * latched unconditionally so a reset arriving from inside
     * {@code ASIOCreateBuffers} — before either shim is published — is re-run
     * by {@code open(...)} once the fields are live.</p>
     */
    void stopStreamingForDriverReset() {
        driverResetRequested = true;
        AsioBufferSwitchShim bridge = bufferSwitchShim;
        if (bridge != null) {
            bridge.stopStreaming();
        }
        AsioStreamingShim streaming = streamingShim;
        if (streaming == null) {
            return;
        }
        resetTeardownExecutor.execute(() -> {
            synchronized (DRIVER_LIFECYCLE_LOCK) {
                // close() may have already disposed this shim and open() may
                // have published its replacement while this task was queued.
                // Keep the identity check and downcalls in the same lifecycle
                // critical section so the stream cannot move on between them.
                if (streamingShim != streaming) {
                    return;
                }
                // Sampled immediately before each downcall for the same reason
                // as in tearDownStreaming: a refusal by the host must not be
                // logged as one by the driver, and a stop() the control thread
                // has already STARTED and that then outlives its budget is
                // abandoned in flight — which is what makes the dispose behind
                // it refused on arrival rather than by the driver. The sample
                // only PREDICTS: AsioControlThread.call re-reads the gate
                // itself, and that read is what actually refuses a bounded
                // operation. Diagnostic only; nothing here frees anything.
                boolean stopAttempted = AsioControlThread.isQuiesced();
                boolean stopped = streaming.stop();
                boolean disposeAttempted = AsioControlThread.isQuiesced();
                boolean disposed = streaming.disposeBuffers();
                warnIfDriverRefusedTeardown(
                        stopped, stopAttempted, disposed, disposeAttempted);
            }
        });
    }

    /**
     * Test seam: the buffer-switch bridge currently installed by
     * {@link #open(DeviceId, AudioFormat, int)}, or {@code null} when the
     * backend is closed or the native streaming shim was unavailable.
     */
    AsioBufferSwitchShim activeBufferSwitchShim() {
        return bufferSwitchShim;
    }

    /**
     * Test seam: the story-218 {@code asioMessage} shim currently installed by
     * {@link #open(DeviceId, AudioFormat, int)}, or {@code null} when the
     * backend is closed. Non-null from before {@code ASIOCreateBuffers} runs,
     * which is the ordering story 311 depends on.
     */
    AsioFormatChangeShim activeFormatChangeShim() {
        return formatChangeShim;
    }

    /**
     * Reports the buffer sizes the ASIO driver accepts via
     * {@code ASIOGetBufferSize(min, max, preferred, granularity)} —
     * the canonical four-tuple that motivated the API in
     * {@link BufferSizeRange}. Multi-channel USB drivers commonly
     * report non-power-of-two granularity (96, 192, 288, …) which the
     * dropdown must honour exactly.
     *
     * <p>Implementation: an FFM downcall to the {@code asioshim}
     * library's {@code asioshim_getBufferSize} entrypoint via
     * {@link AsioCapabilityShim} (story 130). When the shim is absent
     * (e.g. the JVM runs on Linux/macOS, or the Windows DLL was not
     * bundled) the method falls back to
     * {@link BufferSizeRange#DEFAULT_RANGE} and logs the absence at
     * {@code INFO} exactly once per process.</p>
     *
     * <p>The downcall is serialized on the dedicated
     * {@link AsioControlThread}, never on the JavaFX or audio render thread.</p>
     */
    @Override
    public BufferSizeRange bufferSizeRange(DeviceId device) {
        Objects.requireNonNull(device, "device must not be null");
        try (AsioCapabilityShim shim = capabilityShimFactory.get()) {
            if (!shim.isAvailable()) {
                logFallbackOnce();
                return BufferSizeRange.DEFAULT_RANGE;
            }
            Optional<BufferSizeRange> probed = shim.getBufferSize();
            if (probed.isPresent()) {
                return probed.get();
            }
            LOG.log(Level.FINE,
                    "ASIO buffer-size probe failed or returned invalid values; "
                            + "using default range");
            return BufferSizeRange.DEFAULT_RANGE;
        }
    }

    /**
     * Reports the sample rates the ASIO driver accepts. Probes each
     * entry of {@link #CANONICAL_SAMPLE_RATES_HZ} against
     * {@code asioshim_canSampleRate(double)} and returns the rates
     * the driver answered {@code ASE_OK} for.
     *
     * <p>When the {@code asioshim} library is absent the method
     * returns the canonical rate set unchanged so the dialog still
     * shows the historical menu, and logs the absence at {@code INFO}
     * exactly once per process.</p>
     */
    @Override
    public Set<Integer> supportedSampleRates(DeviceId device) {
        Objects.requireNonNull(device, "device must not be null");
        try (AsioCapabilityShim shim = capabilityShimFactory.get()) {
            if (!shim.isAvailable()) {
                logFallbackOnce();
                return canonicalSampleRateSet();
            }
            Set<Integer> accepted = new LinkedHashSet<>();
            for (int rate : CANONICAL_SAMPLE_RATES_HZ) {
                if (shim.canSampleRate(rate)) {
                    accepted.add(rate);
                }
            }
            // If the driver rejected every canonical rate (e.g. an unusual
            // hardware-locked rate), return the driver's current rate when
            // available instead of marking the full canonical set as
            // supported. Otherwise return an empty set so the UI can
            // detect "unsupported" rather than offering rates that fail.
            if (accepted.isEmpty()) {
                return shim.getSampleRate()
                        .map(rate -> (int) Math.round(rate))
                        .map(Set::of)
                        .orElseGet(Set::of);
            }
            return Set.copyOf(accepted);
        }
    }

    private static Set<Integer> canonicalSampleRateSet() {
        Set<Integer> all = new LinkedHashSet<>();
        for (int rate : CANONICAL_SAMPLE_RATES_HZ) {
            all.add(rate);
        }
        return Set.copyOf(all);
    }

    private static void logFallbackOnce() {
        if (FALLBACK_LOGGED.compareAndSet(false, true)) {
            LOG.log(Level.INFO,
                    "ASIO capability shim (asioshim) not available — "
                            + "Audio Settings dialog will use the canonical "
                            + "buffer-size and sample-rate fallbacks. "
                            + "Bundle daw-core/native/asio/asioshim.dll "
                            + "for driver-reported values.");
        }
    }

    /**
     * Test seam: replace the factory the backend uses to obtain its
     * {@link AsioCapabilityShim}. Used by unit tests to inject a stub
     * shim that returns a known {@link BufferSizeRange} and answers
     * {@code canSampleRate} deterministically, without requiring the
     * native library to be loaded. Restores via {@link #resetCapabilityShimFactory()}.
     *
     * @param factory non-null supplier of a shim instance per call
     */
    static void setCapabilityShimFactory(Supplier<AsioCapabilityShim> factory) {
        capabilityShimFactory = Objects.requireNonNull(factory, "factory");
    }

    /** Test seam: restore the production factory and reset log-once state. */
    static void resetCapabilityShimFactory() {
        capabilityShimFactory = AsioCapabilityShim::new;
        FALLBACK_LOGGED.set(false);
    }

    /** Test seam for enumeration and lifecycle paths without a native SDK. */
    static void setDriverShimFactory(Supplier<AsioDriverShim> factory) {
        driverShimFactory = Objects.requireNonNull(factory, "factory");
    }

    /** Restores the production ASIO driver shim factory. */
    static void resetDriverShimFactory() {
        driverShimFactory = AsioDriverShim::new;
    }

    /**
     * Test seam for the story-311 streaming path: replaces the factory the
     * backend uses to obtain its {@link AsioStreamingShim}, so the
     * {@code createBuffers} / {@code start} / {@code stop} /
     * {@code disposeBuffers} ordering can be asserted without a Windows host
     * or the Steinberg SDK.
     *
     * @param factory non-null supplier of a shim instance per open
     */
    static void setStreamingShimFactory(Supplier<AsioStreamingShim> factory) {
        streamingShimFactory = Objects.requireNonNull(factory, "factory");
    }

    /** Restores the production ASIO streaming shim factory. */
    static void resetStreamingShimFactory() {
        streamingShimFactory = AsioStreamingShim::new;
    }

    /**
     * Returns display metadata captured from {@code ASIOInit} for the active
     * driver, or empty while this backend has no initialized driver.
     */
    public Optional<AsioDriverInfo> activeDriverInfo() {
        AsioDriverShim lifecycle = driverShim;
        if (lifecycle == null) {
            return Optional.empty();
        }
        return lifecycle.getDriverInfo().flatMap(info -> {
            String name = info.name().isBlank()
                    ? lifecycle.getDriverName().orElse("") : info.name();
            if (name.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new AsioDriverInfo(
                    name, info.asioVersion(), info.driverVersion(),
                    info.errorMessage()));
        });
    }

    /**
     * Reports the hardware clock sources the ASIO driver exposes.
     * Calls {@code ASIOGetClockSources(ASIOClockSource[], int* numSources)}
     * via the {@link AsioCapabilityShim} (story 216), classifies each
     * driver-reported name into a {@link ClockKind} via
     * {@link #classifyClockKind(String)}, and returns an unmodifiable
     * list with {@link ClockSource#current()} reflecting the entry the
     * driver flagged as currently active.
     *
     * <p>When the {@code asioshim} library or the
     * {@code asioshim_getClockSources} symbol is absent (older shim
     * builds, Linux/macOS hosts, Windows hosts where the user did not
     * install the Steinberg ASIO SDK at build time), this returns an
     * empty list — which the Audio Settings dialog renders as a
     * disabled combo with a tooltip explaining that the native shim is
     * required.</p>
     *
     * <p>The downcall is serialized on the dedicated
     * {@link AsioControlThread}, never on the JavaFX or audio render thread.</p>
     */
    @Override
    public List<ClockSource> clockSources(DeviceId device) {
        Objects.requireNonNull(device, "device must not be null");
        try (AsioCapabilityShim shim = capabilityShimFactory.get()) {
            if (!shim.isClockSourceAvailable()) {
                return List.of();
            }
            List<AsioCapabilityShim.RawClockSource> rows = shim.getClockSources();
            if (rows.isEmpty()) {
                return List.of();
            }
            List<ClockSource> out = new java.util.ArrayList<>(rows.size());
            for (AsioCapabilityShim.RawClockSource row : rows) {
                String name = row.name();
                if (name == null || name.isBlank()) {
                    // ClockSource enforces non-blank names; substitute
                    // a stable label so a driver-side bug does not
                    // crash the dialog.
                    name = "Source " + row.id();
                }
                int id = row.id();
                if (id < 0) {
                    // Negative ids are invalid per ClockSource; skip
                    // the row rather than crash.
                    continue;
                }
                out.add(new ClockSource(id, name, row.current(),
                        classifyClockKind(name)));
            }
            return List.copyOf(out);
        }
    }

    /**
     * Routes a clock-source selection to
     * {@code ASIOSetClockSource(int)} via the {@link AsioCapabilityShim}
     * on a dedicated daemon platform thread (story 216), consistent
     * with stories 218 / 221 — the native driver call must not run on
     * the audio render thread. The method blocks on
     * {@link CompletableFuture#join()}, so callers must invoke it off
     * the JavaFX application thread (the Audio Settings dialog already
     * does this — its clock-source combo listener runs on a background
     * thread, mirroring {@link #invokeAsioControlPanel()}).
     *
     * <p>A non-zero return from the shim is translated into a
     * {@link AudioBackendException} with the ASE error code mapped to
     * a human-readable message:</p>
     * <ul>
     *   <li>{@code ASE_NotPresent} (-1000) →
     *       "unknown clock source id" (also covers a missing shim).</li>
     *   <li>{@code ASE_HWMalfunction} (-999) → "hardware malfunction".</li>
     *   <li>{@code ASE_InvalidParameter} (-998) → "invalid parameter".</li>
     *   <li>{@code ASE_InvalidMode} (-997) →
     *       "driver rejects clock change while streaming".</li>
     *   <li>{@code ASE_SPNotAdvancing} (-996) → "sample position not advancing".</li>
     *   <li>{@code ASE_NoClock} (-995) → "no clock available".</li>
     *   <li>{@code ASE_NoMemory} (-994) → "out of memory".</li>
     * </ul>
     */
    @Override
    public void selectClockSource(DeviceId device, int sourceId) {
        Objects.requireNonNull(device, "device must not be null");
        if (sourceId < 0) {
            throw new IllegalArgumentException(
                    "sourceId must not be negative: " + sourceId);
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        Thread.ofPlatform()
                .name("asio-set-clock-source")
                .daemon(true)
                .start(() -> {
                    try (AsioCapabilityShim shim = capabilityShimFactory.get()) {
                        if (!shim.isClockSourceAvailable()) {
                            result.completeExceptionally(new UnsupportedOperationException(
                                    "ASIO clock-source selection requires the native shim "
                                            + "under daw-core/native/asio/ which is not present "
                                            + "in this build."));
                            return;
                        }
                        int rc = shim.setClockSource(sourceId);
                        if (rc == 0) {
                            result.complete(null);
                            return;
                        }
                        result.completeExceptionally(new AudioBackendException(
                                "Could not set ASIO clock source " + sourceId
                                        + ": " + asioErrorMessage(rc)));
                    } catch (Throwable e) {
                        result.completeExceptionally(new AudioBackendException(
                                "ASIO clock-source selection failed: "
                                        + (e.getMessage() == null
                                                ? e.getClass().getSimpleName()
                                                : e.getMessage()),
                                e));
                    }
                });
        try {
            result.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof UnsupportedOperationException uoe) {
                throw uoe;
            }
            if (cause instanceof AudioBackendException abe) {
                throw abe;
            }
            throw new AudioBackendException(
                    "ASIO clock-source selection failed", cause);
        }
    }

    /**
     * Routes a sample-rate change to {@code ASIOSetSampleRate(double)}
     * via the {@link AsioCapabilityShim} on a dedicated daemon platform
     * thread (story 220), consistent with stories 216 / 218 — the
     * native driver call must not run on the audio render thread. The
     * method blocks on {@link CompletableFuture#join()}, so callers
     * should invoke it off the JavaFX application thread to avoid
     * freezing the UI. The Audio Settings dialog dispatches its Apply
     * flow to a background virtual thread for this reason.
     *
     * <p>A {@code false} return from the shim ({@code ASE_OK} not
     * received) is translated into an {@link AudioBackendException}
     * carrying the {@code ASE_InvalidMode} marker the issue mandates —
     * that is the failure case the dialog surfaces as a
     * {@link com.benesquivelmusic.daw.sdk.audio.AudioBackendException}
     * notification when the driver refuses to switch rates while the
     * stream is open or the requested rate is outside the driver's
     * supported set.</p>
     *
     * @param device target device id; must not be null
     * @param rate   the requested sample rate in Hz; must be positive
     *               and finite
     * @throws IllegalArgumentException      if {@code rate} is not
     *                                       positive / finite
     * @throws AudioBackendException if the native shim is not available
     *                               in this build or the driver rejects
     *                               the requested rate
     */
    @Override
    public void setSampleRate(DeviceId device, double rate) {
        Objects.requireNonNull(device, "device must not be null");
        if (!Double.isFinite(rate) || rate <= 0.0) {
            throw new IllegalArgumentException(
                    "rate must be a positive finite Hz value: " + rate);
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        Thread.ofPlatform()
                .name("asio-set-sample-rate")
                .daemon(true)
                .start(() -> {
                    try (AsioCapabilityShim shim = capabilityShimFactory.get()) {
                        if (!shim.isAvailable()) {
                            result.completeExceptionally(new AudioBackendException(
                                    "ASIO sample-rate change requires the native shim "
                                            + "under daw-core/native/asio/ which is not present "
                                            + "in this build."));
                            return;
                        }
                        if (shim.setSampleRate(rate)) {
                            result.complete(null);
                            return;
                        }
                        result.completeExceptionally(new AudioBackendException(
                                "Driver rejected sample rate " + formatRate(rate)
                                        + ": ASE_InvalidMode"));
                    } catch (Throwable e) {
                        result.completeExceptionally(new AudioBackendException(
                                "ASIO sample-rate change failed: "
                                        + (e.getMessage() == null
                                                ? e.getClass().getSimpleName()
                                                : e.getMessage()),
                                e));
                    }
                });
        try {
            result.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof AudioBackendException abe) {
                throw abe;
            }
            throw new AudioBackendException(
                    "ASIO sample-rate change failed", cause);
        }
    }

    /**
     * Formats a sample rate as a compact integer string when it is a
     * whole number (the canonical 44 100 / 48 000 / 96 000 / ... menu),
     * falling back to the default {@code Double#toString} otherwise.
     */
    private static String formatRate(double rate) {
        if (rate == Math.floor(rate) && !Double.isInfinite(rate)) {
            return Long.toString((long) rate);
        }
        return Double.toString(rate);
    }

    /**
     * Maps a driver-reported clock-source display name to a
     * {@link ClockKind}. The heuristic is the standard table the issue
     * specifies — case-insensitive substring matches against the well
     * known sync-source short names. A name that does not match any
     * digital-input bucket falls into {@link ClockKind.External}.
     */
    static ClockKind classifyClockKind(String name) {
        if (name == null) {
            return new ClockKind.External();
        }
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.contains("internal") || n.contains("int ") || n.equals("int")
                || n.contains("xtal") || n.contains("crystal")) {
            return new ClockKind.Internal();
        }
        if (n.contains("word") || n.contains("w/c") || n.contains("wclk")
                || n.contains("wordclock")) {
            return new ClockKind.WordClock();
        }
        if (n.contains("spdif") || n.contains("s/pdif") || n.contains("s pdif")) {
            return new ClockKind.Spdif();
        }
        if (n.contains("adat")) {
            return new ClockKind.Adat();
        }
        if (n.contains("aes")) {
            return new ClockKind.Aes();
        }
        return new ClockKind.External();
    }

    /**
     * Reports driver-supplied input-channel metadata via
     * {@code asioshim_getChannelCount} + {@code asioshim_getChannelInfo}
     * (story 215). Each entry's display name comes verbatim from the
     * driver's 32-byte ASCII {@code name} field; the {@link ChannelKind}
     * is inferred from the name via {@link ChannelKindHeuristics}.
     *
     * <p>Inactive channels (driver-reported {@code isActive == 0}) are
     * still included so {@link com.benesquivelmusic.daw.sdk.audio.AudioChannelInfo#active()}
     * is {@code false} and the routing dropdown can grey them out with
     * the existing "Disabled in driver" tooltip.</p>
     *
     * <p>When the {@code asioshim} library is absent or does not export
     * the channel-info symbols, this returns {@link List#of()} — the
     * inherited default — so the UI falls back to the legacy
     * "Input N" labels exactly as before.</p>
     */
    @Override
    public List<AudioChannelInfo> inputChannels(DeviceId device) {
        Objects.requireNonNull(device, "device must not be null");
        return enumerateChannels(true);
    }

    /**
     * Output-side counterpart of {@link #inputChannels(DeviceId)}; see
     * that method for the full contract (story 215).
     */
    @Override
    public List<AudioChannelInfo> outputChannels(DeviceId device) {
        Objects.requireNonNull(device, "device must not be null");
        return enumerateChannels(false);
    }

    private static List<AudioChannelInfo> enumerateChannels(boolean isInput) {
        try (AsioCapabilityShim shim = capabilityShimFactory.get()) {
            if (!shim.isChannelInfoAvailable()) {
                return List.of();
            }
            Optional<int[]> counts = shim.getChannelCount();
            if (counts.isEmpty()) {
                return List.of();
            }
            int total = isInput ? counts.get()[0] : counts.get()[1];
            if (total <= 0) {
                return List.of();
            }
            List<AudioChannelInfo> out = new java.util.ArrayList<>(total);
            for (int i = 0; i < total; i++) {
                Optional<AsioCapabilityShim.RawChannelInfo> info =
                        shim.getChannelInfo(i, isInput);
                if (info.isEmpty()) {
                    // The shim either ran out of valid channels or the
                    // driver returned an error; stop enumerating rather
                    // than emit a synthetic placeholder.
                    break;
                }
                String name = info.get().name();
                if (name == null || name.isBlank()) {
                    name = (isInput ? "Input " : "Output ") + (i + 1);
                }
                out.add(new AudioChannelInfo(
                        i, name,
                        ChannelKindHeuristics.infer(name),
                        info.get().active()));
            }
            return List.copyOf(out);
        }
    }

    private static String asioErrorMessage(int rc) {
        return switch (rc) {
            case -1000 -> "ASE_NotPresent — unknown clock source id";
            case -999  -> "ASE_HWMalfunction — hardware malfunction";
            case -998  -> "ASE_InvalidParameter — invalid parameter";
            case -997  -> "ASE_InvalidMode — driver rejects clock change while streaming";
            case -996  -> "ASE_SPNotAdvancing — sample position not advancing";
            case -995  -> "ASE_NoClock — no clock available";
            case -994  -> "ASE_NoMemory — out of memory";
            default    -> "ASIOError " + rc;
        };
    }

}
