package com.benesquivelmusic.daw.sdk.audio;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * <p>The SDK itself only declares the public surface defined by
 * {@link AudioBackend}; wiring the ASIO buffer-switch callback into
 * {@link #inputBlocks()} / {@link #sink(AudioBlock)} lives in the
 * implementation layer that ships the native shim.</p>
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

    /** Backend instance that currently owns the single process-wide driver. */
    private static AsioBackend activeBackend;

    /**
     * Whether the "ASIO capability shim is unavailable; using fallback"
     * INFO has already been logged in this JVM. Story 213 explicitly
     * requires logging the absence "exactly once per process".
     */
    private static final AtomicBoolean FALLBACK_LOGGED = new AtomicBoolean(false);

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
                devices.add(new AudioDeviceInfo(
                        index, driver.name(), NAME,
                        0, 0, 0.0, List.of(), 0.0, 0.0));
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
            driverResetRequested = false;

            AsioDriverShim candidate = driverShimFactory.get();
            if (!candidate.isLifecycleAvailable()) {
                candidate.close();
                throw unavailableException();
            }
            String driverName;
            try {
                driverName = resolveDriverName(candidate, device);
            } catch (RuntimeException | Error failure) {
                candidate.close();
                throw failure;
            }
            if (!candidate.loadDriver(driverName)) {
                candidate.close();
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
     * driver does not have simply get no buffer;
     * {@link AsioBufferSwitchShim} captures silence for them and leaves their
     * (non-existent) output buffers alone.</p>
     *
     * <p>When no count is available — no capability shim, or
     * {@code ASIOGetChannels} failed — the request keeps the previous
     * behaviour.</p>
     *
     * @return {@code {inputs, outputs}}
     * @throws AudioBackendException when the combined request exceeds the
     *                               native shim's {@code MAX_STREAM_CHANNELS}
     *                               cap, rather than letting
     *                               {@code ASIOCreateBuffers} fail opaquely
     */
    private static int[] negotiateChannelCounts(int formatChannels, String driverName) {
        int inputs = formatChannels;
        int outputs = formatChannels;
        Optional<int[]> reported = driverChannelCounts();
        if (reported.isPresent()) {
            inputs = Math.clamp(reported.get()[0], 0, formatChannels);
            outputs = Math.clamp(reported.get()[1], 0, formatChannels);
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
        if (candidate.isStreamingAvailable()) {
            return candidate;
        }
        candidate.close();
        return null;
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
     * {@code ASIOCreateBuffers}).
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
        if (streaming != null) {
            tearDownStreaming(streaming);
        }
        if (bridge != null) {
            bridge.close();
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
        candidate.close();
    }

    /**
     * Runs the driver-side half of the story-311 teardown — {@code ASIOStop},
     * {@code ASIODisposeBuffers}, then uninstalling the buffer-switch upcall —
     * and reports a driver that refused either of the first two.
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
     * The caller frees the upcall stub's arena immediately after this returns, so
     * the question a refusal raises is whether a still-running driver can reach
     * that stub. It cannot, because the native shim closes both windows:</p>
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
     */
    private void tearDownStreaming(AsioStreamingShim streaming) {
        boolean stopped = streaming.stop();
        boolean disposed = streaming.disposeBuffers();
        warnIfDriverRefusedTeardown(stopped, disposed);
        streaming.uninstallBufferSwitchCallback();
    }

    /**
     * Logs a driver that refused {@code ASIOStop} and/or
     * {@code ASIODisposeBuffers}. Shared by {@link #close()} /
     * {@code rollbackOpen(...)} — via {@link #tearDownStreaming} — and by the
     * asynchronous {@link #stopStreamingForDriverReset()} teardown, which runs
     * the same two calls and would otherwise discard the same information.
     */
    private void warnIfDriverRefusedTeardown(boolean stopped, boolean disposed) {
        if (stopped && disposed) {
            return;
        }
        String refused;
        if (!stopped && !disposed) {
            refused = "ASIOStop and ASIODisposeBuffers";
        } else if (!stopped) {
            refused = "ASIOStop";
        } else {
            refused = "ASIODisposeBuffers";
        }
        String driver = activeDriverName;
        LOG.log(Level.WARNING,
                "ASIO driver refused " + refused + " during teardown: "
                        + (driver == null ? "<no driver loaded>" : driver)
                        + ". The driver may still be firing bufferSwitch. The "
                        + "teardown continues: the native shim closes its buffer "
                        + "gate and waits on a bounded in-flight callback barrier "
                        + "before anything a callback touches is released.");
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
     * <p>A driver that refuses {@code ASIOStop} or {@code ASIODisposeBuffers} is
     * logged and the teardown carries on; see
     * {@link #tearDownStreaming(AsioStreamingShim)} for why continuing is the
     * safe response and aborting is not.</p>
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
            if (streaming != null) {
                tearDownStreaming(streaming);
            }
            if (bridge != null) {
                bridge.close();
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
                lifecycle.close();
            }
            if (activeBackend == this) {
                activeBackend = null;
            }
            support.close();
        }
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
        RESET_TEARDOWN_EXECUTOR.execute(() -> {
            boolean stopped = streaming.stop();
            boolean disposed = streaming.disposeBuffers();
            warnIfDriverRefusedTeardown(stopped, disposed);
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
