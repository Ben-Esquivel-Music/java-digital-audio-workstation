package com.benesquivelmusic.daw.core.audio.portaudio;

import com.benesquivelmusic.daw.core.audio.NativeAbi;
import com.benesquivelmusic.daw.sdk.audio.*;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PortAudio-based audio backend using Java's FFM API (JEP 454).
 *
 * <p>Provides low-latency, cross-platform audio I/O by calling into the
 * PortAudio C library via {@link PortAudioBindings}. Supports buffer sizes
 * as low as 32 samples (~0.7 ms at 44.1 kHz) for real-time monitoring.</p>
 *
 * <h2>Supported Host APIs</h2>
 * <ul>
 *   <li>Windows: WASAPI, ASIO (via PortAudio)</li>
 *   <li>macOS: CoreAudio</li>
 *   <li>Linux: ALSA, JACK</li>
 * </ul>
 *
 * <h2>Prerequisites</h2>
 * <p>The PortAudio shared library must be installed on the system and accessible
 * via the standard library path. Use {@link #isAvailable()} to check.</p>
 */
public final class PortAudioBackend implements NativeAudioBackend {

    private static final Logger LOG = Logger.getLogger(PortAudioBackend.class.getName());

    private final PortAudioBindings bindings;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean streamActive = new AtomicBoolean(false);

    private List<AudioDeviceInfo> cachedDevices;
    private AudioStreamConfig currentConfig;
    private AudioStreamCallback currentCallback;
    private CallbackInvoker currentInvoker;
    private MemorySegment streamHandle;
    private Arena streamArena;

    /**
     * Creates a new PortAudio backend.
     */
    public PortAudioBackend() {
        this.bindings = new PortAudioBindings();
    }

    /**
     * Creates a new PortAudio backend with the given bindings (for testing).
     *
     * @param bindings the PortAudio bindings to use
     */
    PortAudioBackend(PortAudioBindings bindings) {
        this.bindings = Objects.requireNonNull(bindings, "bindings must not be null");
    }

    @Override
    public void initialize() {
        if (!bindings.isAvailable()) {
            throw new AudioBackendException("PortAudio native library is not available");
        }
        if (!initialized.compareAndSet(false, true)) {
            return; // already initialized
        }
        int result = bindings.initialize();
        PortAudioException.checkError(result, "Pa_Initialize");
    }

    @Override
    public List<AudioDeviceInfo> getAvailableDevices() {
        ensureInitialized();
        if (cachedDevices != null) {
            return cachedDevices;
        }

        int deviceCount = bindings.getDeviceCount();
        PortAudioException.checkError(deviceCount, "Pa_GetDeviceCount");

        ArrayList<AudioDeviceInfo> devices = new ArrayList<AudioDeviceInfo>(deviceCount);
        for (int i = 0; i < deviceCount; i++) {
            MemorySegment infoPtr = bindings.getDeviceInfo(i);
            if (!infoPtr.equals(MemorySegment.NULL)) {
                devices.add(parseDeviceInfo(i, infoPtr));
            }
        }

        cachedDevices = Collections.unmodifiableList(devices);
        return cachedDevices;
    }

    @Override
    public AudioDeviceInfo getDefaultInputDevice() {
        ensureInitialized();
        int index = bindings.getDefaultInputDevice();
        if (index == PortAudioBindings.PA_NO_DEVICE) {
            return null;
        }
        return findDevice(index);
    }

    @Override
    public AudioDeviceInfo getDefaultOutputDevice() {
        ensureInitialized();
        int index = bindings.getDefaultOutputDevice();
        if (index == PortAudioBindings.PA_NO_DEVICE) {
            return null;
        }
        return findDevice(index);
    }

    @Override
    public void openStream(AudioStreamConfig config, AudioStreamCallback callback) {
        ensureInitialized();
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        if (streamHandle != null) {
            throw new IllegalStateException("A stream is already open; close it first");
        }

        streamArena = Arena.ofConfined();

        try {
            // Allocate PaStreamParameters structs
            MemorySegment inputParams = config.hasInput()
                    ? allocateStreamParameters(streamArena, config.inputDeviceIndex(), config.inputChannels())
                    : MemorySegment.NULL;
            MemorySegment outputParams = config.hasOutput()
                    ? allocateStreamParameters(streamArena, config.outputDeviceIndex(), config.outputChannels())
                    : MemorySegment.NULL;

            // Allocate pointer to receive the stream handle
            MemorySegment streamPtr = streamArena.allocate(ValueLayout.ADDRESS);

            // Create the callback upcall stub
            MemorySegment callbackStub = createCallbackStub(streamArena, callback,
                    config.inputChannels(), config.outputChannels(), config.bufferSize().getFrames());

            int result = bindings.openStream(
                    streamPtr,
                    inputParams,
                    outputParams,
                    config.sampleRate().getHz(),
                    config.bufferSize().getFrames(),
                    0L, // no special flags
                    callbackStub,
                    MemorySegment.NULL
            );
            PortAudioException.checkError(result, "Pa_OpenStream");

            streamHandle = streamPtr.get(ValueLayout.ADDRESS, 0);
            currentConfig = config;
            currentCallback = callback;
        } catch (RuntimeException | Error e) {
            streamArena.close();
            streamArena = null;
            // The invoker was published by createCallbackStub before the open
            // failed; it belongs to a stream that never existed, so it must not
            // survive into the next open's clamp report.
            currentInvoker = null;
            throw e;
        }
    }

    @Override
    public void startStream() {
        ensureStreamOpen();
        int result = bindings.startStream(streamHandle);
        PortAudioException.checkError(result, "Pa_StartStream");
        streamActive.set(true);
    }

    @Override
    public void stopStream() {
        if (streamHandle == null || !streamActive.get()) {
            return;
        }
        int result = bindings.stopStream(streamHandle);
        PortAudioException.checkError(result, "Pa_StopStream");
        streamActive.set(false);
    }

    @Override
    public void closeStream() {
        if (streamHandle == null) {
            return;
        }
        stopStreamBeforeClose();
        // Story 315 review — Pa_CloseStream on a still-active stream discards
        // pending buffers as if Pa_AbortStream had been called, so close
        // proceeds even when the graceful stop above failed.
        int result = bindings.closeStream(streamHandle);
        PortAudioException.checkError(result, "Pa_CloseStream");
        streamActive.set(false);
        streamHandle = null;
        reportClampedOversizedPeriods();
        currentConfig = null;
        currentCallback = null;
        if (streamArena != null) {
            streamArena.close();
            streamArena = null;
        }
    }

    @Override
    public LatencyInfo getLatencyInfo() {
        ensureStreamOpen();

        MemorySegment infoPtr = bindings.getStreamInfo(streamHandle);
        if (infoPtr.equals(MemorySegment.NULL)) {
            // Fall back to calculated latency from config
            double bufferLatencyMs = currentConfig.bufferSize().latencyMs(
                    currentConfig.sampleRate().getHz());
            return LatencyInfo.of(bufferLatencyMs, bufferLatencyMs,
                    currentConfig.bufferSize().getFrames(),
                    currentConfig.sampleRate().getHz());
        }

        MemorySegment info = infoPtr.reinterpret(
                PortAudioBindings.PA_STREAM_INFO_LAYOUT.byteSize());
        double inputLatency = info.get(ValueLayout.JAVA_DOUBLE,
                PortAudioBindings.PA_STREAM_INFO_LAYOUT.byteOffset(
                        MemoryLayout.PathElement.groupElement("inputLatency")));
        double outputLatency = info.get(ValueLayout.JAVA_DOUBLE,
                PortAudioBindings.PA_STREAM_INFO_LAYOUT.byteOffset(
                        MemoryLayout.PathElement.groupElement("outputLatency")));
        double sampleRate = info.get(ValueLayout.JAVA_DOUBLE,
                PortAudioBindings.PA_STREAM_INFO_LAYOUT.byteOffset(
                        MemoryLayout.PathElement.groupElement("sampleRate")));

        return LatencyInfo.of(
                inputLatency * 1000.0,
                outputLatency * 1000.0,
                currentConfig.bufferSize().getFrames(),
                sampleRate
        );
    }

    @Override
    public boolean isStreamActive() {
        if (streamHandle == null) {
            return false;
        }
        return streamActive.get() && bindings.isStreamActive(streamHandle) == 1;
    }

    @Override
    public String getBackendName() {
        return "PortAudio";
    }

    @Override
    public boolean isAvailable() {
        return bindings.isAvailable();
    }

    @Override
    public void close() {
        closeStream();
        if (initialized.compareAndSet(true, false)) {
            bindings.terminate();
            cachedDevices = null;
        }
    }

    // --- Internal helpers ---

    /**
     * Story 315 review — a failed graceful {@code Pa_StopStream} must not
     * block {@code Pa_CloseStream}, which is what actually releases the
     * stream and its callback. The stop failure is logged and close proceeds.
     */
    private void stopStreamBeforeClose() {
        try {
            stopStream();
        } catch (AudioBackendException e) {
            LOG.log(Level.WARNING, "Pa_StopStream failed before close; closing the stream anyway", e);
        }
    }

    /**
     * Reports, once per stream, how many host periods the callback bridge had
     * to clamp (story 316 review).
     *
     * <p>{@link CallbackInvoker#invoke} cannot log: it runs on PortAudio's
     * real-time thread, where a {@link Logger} call would take a lock and
     * allocate. It therefore only counts, and the count is drained here — on
     * the closing thread, by construction, because {@link #closeStream()} is
     * called by the owner and never by the driver.</p>
     *
     * <p>The requested frame count is read back off the invoker rather than
     * off {@link #currentConfig}, because the invoker's value is the one the
     * clamp actually measured against.</p>
     */
    private void reportClampedOversizedPeriods() {
        CallbackInvoker invoker = this.currentInvoker;
        if (invoker != null && invoker.clampedOversizedPeriods() > 0) {
            LOG.warning("PortAudio delivered " + invoker.clampedOversizedPeriods()
                    + " callback period(s) larger than the " + invoker.framesPerBuffer()
                    + " frames this stream was opened with; each was clamped to "
                    + invoker.framesPerBuffer() + " frames and the excess frames were"
                    + " played as silence, because indexing past the pre-allocated"
                    + " channel planes would have thrown out of an upcall on the"
                    + " driver's real-time thread");
        }
        this.currentInvoker = null;
    }

    private void ensureInitialized() {
        if (!initialized.get()) {
            throw new IllegalStateException("PortAudio backend is not initialized; call initialize() first");
        }
    }

    private void ensureStreamOpen() {
        if (streamHandle == null) {
            throw new IllegalStateException("No stream is open");
        }
    }

    private AudioDeviceInfo findDevice(int index) {
        List<AudioDeviceInfo> devices = getAvailableDevices();
        for (AudioDeviceInfo device : devices) {
            if (device.index() == index) {
                return device;
            }
        }
        return null;
    }

    /**
     * Parses one native {@code PaDeviceInfo} struct into an
     * {@link AudioDeviceInfo}.
     *
     * <p>Package-private and static (story 316 re-review) so the
     * native-boundary normalisation it performs can be exercised against a
     * hand-built struct without a PortAudio installation on the host.</p>
     *
     * @param index   the PortAudio device index this struct describes
     * @param infoPtr pointer to the {@code PaDeviceInfo} struct
     * @return the parsed device descriptor
     */
    static AudioDeviceInfo parseDeviceInfo(int index, MemorySegment infoPtr) {
        MemorySegment info = infoPtr.reinterpret(
                PortAudioBindings.PA_DEVICE_INFO_LAYOUT.byteSize());

        MemorySegment namePtr = info.get(ValueLayout.ADDRESS,
                PortAudioBindings.PA_DEVICE_INFO_LAYOUT.byteOffset(
                        MemoryLayout.PathElement.groupElement("name")));
        String name = namePtr.equals(MemorySegment.NULL)
                ? "Unknown Device"
                : namePtr.reinterpret(256).getString(0);

        int hostApiIndex = info.get(ValueLayout.JAVA_INT,
                PortAudioBindings.PA_DEVICE_INFO_LAYOUT.byteOffset(
                        MemoryLayout.PathElement.groupElement("hostApi")));
        int maxInputChannels = info.get(ValueLayout.JAVA_INT,
                PortAudioBindings.PA_DEVICE_INFO_LAYOUT.byteOffset(
                        MemoryLayout.PathElement.groupElement("maxInputChannels")));
        int maxOutputChannels = info.get(ValueLayout.JAVA_INT,
                PortAudioBindings.PA_DEVICE_INFO_LAYOUT.byteOffset(
                        MemoryLayout.PathElement.groupElement("maxOutputChannels")));
        double defaultLowInputLatency = info.get(ValueLayout.JAVA_DOUBLE,
                PortAudioBindings.PA_DEVICE_INFO_LAYOUT.byteOffset(
                        MemoryLayout.PathElement.groupElement("defaultLowInputLatency")));
        double defaultLowOutputLatency = info.get(ValueLayout.JAVA_DOUBLE,
                PortAudioBindings.PA_DEVICE_INFO_LAYOUT.byteOffset(
                        MemoryLayout.PathElement.groupElement("defaultLowOutputLatency")));
        double defaultSampleRate = info.get(ValueLayout.JAVA_DOUBLE,
                PortAudioBindings.PA_DEVICE_INFO_LAYOUT.byteOffset(
                        MemoryLayout.PathElement.groupElement("defaultSampleRate")));

        return new AudioDeviceInfo(
                index,
                name,
                "PortAudio Host API " + hostApiIndex,
                sanitizeChannelCount(maxInputChannels, "maxInputChannels", name),
                sanitizeChannelCount(maxOutputChannels, "maxOutputChannels", name),
                defaultSampleRate,
                List.of(SampleRate.values()), // PortAudio typically supports all standard rates
                defaultLowInputLatency * 1000.0,
                defaultLowOutputLatency * 1000.0
        );
    }

    /**
     * Normalises a channel count read straight out of native memory (story
     * 316 re-review).
     *
     * <p>{@link AudioDeviceInfo}'s canonical constructor now REJECTS any
     * count below {@link AudioDeviceInfo#CHANNEL_COUNT_UNKNOWN}, which makes
     * this struct read the boundary where a driver reporting garbage either
     * degrades or crashes. It is a real boundary and not a formality: the
     * int arrives from a third-party host-API plug-in, and an
     * {@code IllegalArgumentException} thrown out of the record constructor
     * here would surface either as a FAILED OPEN — {@code
     * CallbackBackendAdapter.open} enumerates inside the ladder walk — or as
     * an empty device list, because {@code
     * AudioDeviceManager.getAvailableDevices} catches {@code Exception} and
     * answers {@code List.of()}. Either way the device would vanish and the
     * user would be told nothing.</p>
     *
     * <p>A nonsensical count becomes
     * {@link AudioDeviceInfo#CHANNEL_COUNT_UNKNOWN} rather than {@code 0}
     * because those two mean different things. {@code 0} is the record's
     * statement that the direction is NOT OFFERED, so it would drop the
     * device out of the settings menu silently — precisely the failure this
     * story removed for ASIO. "Offered, but the count is not knowable" is
     * the honest reading of garbage: the device stays selectable, every
     * consumer already handles the sentinel ({@code clampInputChannels}
     * defers to the driver), and an open the hardware cannot honour then
     * fails visibly against the driver instead of disappearing from a menu.
     * The bogus value is logged so the offending driver is named.</p>
     *
     * @param nativeCount the raw value read from the struct
     * @param field       the struct field name, for the log line
     * @param deviceName  the device the value came from, for the log line
     * @return {@code nativeCount} when it is a legal count, else
     *         {@link AudioDeviceInfo#CHANNEL_COUNT_UNKNOWN}
     */
    private static int sanitizeChannelCount(int nativeCount, String field, String deviceName) {
        if (nativeCount >= 0) {
            return nativeCount;
        }
        LOG.warning("PortAudio device '" + deviceName + "' reported a nonsensical " + field
                + " of " + nativeCount + "; treating it as CHANNEL_COUNT_UNKNOWN so the device"
                + " stays selectable and an open fails visibly against the driver, rather than"
                + " the enumeration throwing and the device vanishing");
        return AudioDeviceInfo.CHANNEL_COUNT_UNKNOWN;
    }

    /**
     * Fills one {@code PaStreamParameters} struct for {@code Pa_OpenStream}.
     *
     * <p>The layout is {@link PortAudioBindings#PA_STREAM_PARAMETERS_LAYOUT}
     * rather than one built here, so that the ABI decision — {@code
     * sampleFormat} is a C {@code unsigned long}, four bytes wide under LLP64
     * — is made once, next to the other PortAudio struct layouts, and can be
     * asserted by a test.</p>
     *
     * <p>{@code sampleFormat} is consequently written through the accessor
     * that MATCHES that layout's width. A {@link ValueLayout#JAVA_LONG}
     * store into a four-byte member writes four bytes past it, and the only
     * reasons that has never broken anything are that those four bytes are
     * the padding in front of {@code suggestedLatency} and that
     * {@link PortAudioBindings#PA_FLOAT32} fits in 32 bits. Both are facts
     * about this struct and this constant, not about the store — so the
     * store is made the right width instead.</p>
     */
    private MemorySegment allocateStreamParameters(Arena arena, int deviceIndex, int channels) {
        MemoryLayout layout = PortAudioBindings.PA_STREAM_PARAMETERS_LAYOUT;

        MemorySegment params = arena.allocate(layout);
        params.set(ValueLayout.JAVA_INT,
                layout.byteOffset(MemoryLayout.PathElement.groupElement("device")), deviceIndex);
        params.set(ValueLayout.JAVA_INT,
                layout.byteOffset(MemoryLayout.PathElement.groupElement("channelCount")), channels);
        long sampleFormatOffset =
                layout.byteOffset(MemoryLayout.PathElement.groupElement("sampleFormat"));
        if (NativeAbi.C_LONG_IS_32_BIT) {
            params.set(ValueLayout.JAVA_INT, sampleFormatOffset,
                    (int) PortAudioBindings.PA_FLOAT32);
        } else {
            params.set(ValueLayout.JAVA_LONG, sampleFormatOffset,
                    PortAudioBindings.PA_FLOAT32);
        }
        params.set(ValueLayout.JAVA_DOUBLE,
                layout.byteOffset(MemoryLayout.PathElement.groupElement("suggestedLatency")), 0.0);
        params.set(ValueLayout.ADDRESS,
                layout.byteOffset(MemoryLayout.PathElement.groupElement("hostApiSpecificStreamInfo")),
                MemorySegment.NULL);
        return params;
    }

    /**
     * The C signature of PortAudio's stream callback, as this backend
     * declares it to the linker:
     *
     * <pre>{@code
     * int callback(const void* input, void* output, unsigned long frameCount,
     *              const PaStreamCallbackTimeInfo* timeInfo,
     *              PaStreamCallbackFlags statusFlags, void* userData);
     * }</pre>
     *
     * <p>{@code frameCount} and {@code statusFlags} — argument layouts 2 and
     * 4, counting from zero — are {@link NativeAbi#C_LONG}, not
     * {@link ValueLayout#JAVA_LONG}: {@code frameCount} is a C
     * {@code unsigned long} and {@code PaStreamCallbackFlags} is a typedef of
     * one, so both are 32 bits wide under LLP64. Declaring 64 bits there does
     * not merely mis-name a type — it makes the upcall read a full 64-bit
     * argument slot where the host wrote only the lower 32 bits, and nothing
     * in the ABI obliges the caller to have zeroed the rest. The frame count
     * that arrives can therefore carry bits no host supplied, and
     * {@link CallbackInvoker#invoke} multiplies it by the frame stride to
     * size a {@link MemorySegment#reinterpret(long)}: a fictitious size there
     * is a zero-fill running past the driver's real buffer. The width has to
     * be the platform's, not a convenient constant.</p>
     */
    private static final FunctionDescriptor CALLBACK_DESCRIPTOR = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, NativeAbi.C_LONG,
            ValueLayout.ADDRESS, NativeAbi.C_LONG, ValueLayout.ADDRESS
    );

    /**
     * The upcall descriptor PortAudio's stream callback is bound with.
     *
     * <p>Package-private so a test can assert the ABI directly — descriptor
     * against {@link Linker#canonicalLayouts()}, and descriptor against the
     * Java method {@link CallbackBridge#createHandle(CallbackInvoker)} binds
     * — without PortAudio being installed on the host.</p>
     *
     * @return the callback's {@link FunctionDescriptor}
     */
    static FunctionDescriptor callbackDescriptor() {
        return CALLBACK_DESCRIPTOR;
    }

    private MemorySegment createCallbackStub(Arena arena, AudioStreamCallback callback,
                                             int inputChannels, int outputChannels, int framesPerBuffer) {
        CallbackInvoker invoker = CallbackInvoker.forStream(
                callback, inputChannels, outputChannels, framesPerBuffer);
        this.currentInvoker = invoker;

        // Create an upcall stub that bridges the C callback to the Java callback
        return Linker.nativeLinker().upcallStub(
                CallbackBridge.createHandle(invoker),
                CALLBACK_DESCRIPTOR,
                arena
        );
    }

    /**
     * Bridge between PortAudio's C callback and the Java
     * {@link AudioStreamCallback}. The bound method is exposed as an
     * upcall stub via the FFM API.
     */
    static final class CallbackBridge {

        private CallbackBridge() {}

        /**
         * Binds the {@link CallbackInvoker} entry point that MATCHES
         * {@link #callbackDescriptor()}.
         *
         * <p>{@link Linker#upcallStub} rejects a handle whose type differs
         * from the descriptor's, so the carrier used here is taken from
         * {@link NativeAbi#C_LONG} itself rather than written out: the
         * descriptor and this {@link MethodType} are then derived from one
         * source and cannot drift apart. Under LP64 that carrier is
         * {@code long} and the canonical {@link CallbackInvoker#invoke} is
         * bound; under LLP64 it is {@code int} and the zero-extending
         * {@link CallbackInvoker#invokeNarrowLong} is bound instead.</p>
         *
         * @param invoker the per-stream invoker to bind the upcall to
         * @return the {@link MethodHandle} to hand {@link Linker#upcallStub}
         */
        static MethodHandle createHandle(CallbackInvoker invoker) {
            Class<?> cLongCarrier = NativeAbi.C_LONG.carrier();
            String entryPoint = NativeAbi.C_LONG_IS_32_BIT ? "invokeNarrowLong" : "invoke";
            try {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                return lookup.bind(
                        invoker,
                        entryPoint,
                        MethodType.methodType(
                                int.class,
                                MemorySegment.class, MemorySegment.class, cLongCarrier,
                                MemorySegment.class, cLongCarrier, MemorySegment.class)
                );
            } catch (NoSuchMethodException | IllegalAccessException e) {
                throw new AudioBackendException("Failed to create callback bridge", e);
            }
        }
    }

    /**
     * Invokable object that processes PortAudio callbacks by converting between
     * native interleaved float buffers and the Java {@code float[][]} format.
     *
     * <p>{@link #invoke} runs on PortAudio's real-time thread. The Java-side
     * channel planes are allocated ONCE, at
     * {@link #forStream(AudioStreamCallback, int, int, int)}, and sized to the
     * {@code framesPerBuffer} the stream was opened with — so the callback
     * itself allocates no array.</p>
     *
     * <h2>Why the frame count is clamped</h2>
     * <p>PortAudio's contract is that when {@code Pa_OpenStream} is given a
     * NON-ZERO {@code framesPerBuffer}, every callback receives exactly that
     * many frames. This codebase always requests one — the request comes from
     * {@link com.benesquivelmusic.daw.sdk.audio.BufferSize}, whose constants
     * run 32..2048 and never include {@code paFramesPerBufferUnspecified} — so
     * a host that hands us a LARGER period is a host API breaking that
     * contract, not a configuration we chose.</p>
     *
     * <p>{@link #invoke} clamps rather than trusting {@code frameCount}
     * because it indexes the pre-allocated planes BEFORE any downstream
     * consumer sees the block: {@code CallbackBackendAdapter}'s own oversized
     * -block clamp sits behind {@code AudioStreamCallback.process} and can
     * protect nothing here. An {@link ArrayIndexOutOfBoundsException} thrown
     * back through an FFM upcall into a native driver is not a recoverable
     * outcome — it unwinds into C code that has no handler — so the bridge
     * absorbs the oversized period, processes the frames it can, silences the
     * remainder, and counts the event for
     * {@link PortAudioBackend#closeStream()} to report.</p>
     *
     * <h2>Two entry points, one implementation</h2>
     * <p>{@link #invoke} is the callback. It is the method the upcall stub
     * binds on an LP64 host, where PortAudio's C {@code unsigned long} is 64
     * bits wide and marshals to a Java {@code long}. On an LLP64 host that C
     * type is 32 bits, so the stub binds {@link #invokeNarrowLong} instead,
     * which zero-extends the two unsigned parameters and calls
     * {@link #invoke}. Exactly one of the two is bound per stream — see
     * {@link CallbackBridge#createHandle(CallbackInvoker)} — and the driver's
     * real-time thread is the caller either way.</p>
     *
     * <p>Neither entry point is annotated {@code @RealTimeSafe}, and that is
     * deliberate: {@link #invoke} calls
     * {@link MemorySegment#reinterpret(long)}, which creates a segment
     * object, so the annotation would be a promise these methods do not keep.
     * The driver hands a different buffer pointer to every callback, so there
     * is nothing to cache and the exemption is inherent rather than a to-do.
     * {@code RealTimeSafeContractTest} lists both entry points regardless,
     * with that exemption recorded against them, so they still get the
     * STRUCTURAL sentinels — no inline publish, no atomic read-modify-write —
     * which is the part of the contract they can and do keep.</p>
     */
    static final class CallbackInvoker {

        private final AudioStreamCallback callback;
        private final float[][] inputBuffer;
        private final float[][] outputBuffer;
        private final int inputChannels;
        private final int outputChannels;
        private final int framesPerBuffer;

        /**
         * Host periods this stream had to clamp down to
         * {@link #framesPerBuffer} — that is, callbacks where PortAudio
         * delivered MORE frames than the stream was opened with.
         *
         * <p>A plain {@code volatile long} with {@code ++} is the right
         * counter here because PortAudio's callback thread is the
         * <em>only</em> writer. The increment is a volatile load, an add and
         * a volatile store: no CAS retry loop, so its cost is a bounded
         * instruction count rather than something that can spin under
         * contention; no lock, so it cannot block behind a control thread;
         * and no object is created, so it cannot provoke an allocation or a
         * GC pause inside the callback. With a single writer the
         * non-atomicity of the read-modify-write loses nothing — there is no
         * concurrent update to lose to — while the volatile store still
         * publishes each value to the closing thread that reads it in
         * {@link PortAudioBackend#reportClampedOversizedPeriods()} and to
         * tests. An {@code AtomicLong} would buy no correctness and pay a
         * CAS for it; a {@code LongAdder} would additionally allocate
         * cells.</p>
         *
         * <p>This is the same idiom, for the same reason, as
         * {@code AsioBufferSwitchShim.renderedBlocksConsumed}.</p>
         */
        private volatile long clampedOversizedPeriods;

        /**
         * Creates an invoker with its channel planes pre-allocated at
         * {@code framesPerBuffer}.
         *
         * @param callback       the Java callback to drive
         * @param inputChannels  capture channel count; {@code 0} for none
         * @param outputChannels playback channel count; {@code 0} for none
         * @param framesPerBuffer the frame count the stream is being opened
         *                        with, and therefore the plane length every
         *                        callback is clamped to
         * @return the invoker to bind into the upcall stub
         */
        static CallbackInvoker forStream(AudioStreamCallback callback, int inputChannels,
                                         int outputChannels, int framesPerBuffer) {
            float[][] inputBuffer = inputChannels > 0
                    ? new float[inputChannels][framesPerBuffer] : new float[0][];
            float[][] outputBuffer = outputChannels > 0
                    ? new float[outputChannels][framesPerBuffer] : new float[0][];
            return new CallbackInvoker(callback, inputBuffer, outputBuffer,
                    inputChannels, outputChannels, framesPerBuffer);
        }

        CallbackInvoker(AudioStreamCallback callback, float[][] inputBuffer, float[][] outputBuffer,
                        int inputChannels, int outputChannels, int framesPerBuffer) {
            this.callback = callback;
            this.inputBuffer = inputBuffer;
            this.outputBuffer = outputBuffer;
            this.inputChannels = inputChannels;
            this.outputChannels = outputChannels;
            this.framesPerBuffer = framesPerBuffer;
        }

        /**
         * The callback, on PortAudio's real-time thread: entered directly
         * from the upcall stub on an LP64 host, and from
         * {@link #invokeNarrowLong} on an LLP64 one.
         *
         * <p>See the class javadoc for why {@code frameCount} is clamped to
         * {@link #framesPerBuffer} instead of trusted.</p>
         *
         * <h2>Why the output size is computed the long way</h2>
         * <p>{@code frameCount} is exactly the {@code unsigned long} the host
         * supplied, and nothing else. The upcall descriptor declares
         * {@link NativeAbi#C_LONG} — the platform's canonical C {@code long}
         * — for PortAudio's {@code unsigned long frameCount}, so on an LP64
         * host all 64 bits of it are the host's, and on an LLP64 host, where
         * that C type is 32 bits, the host's 32 bits reach this method
         * zero-extended by {@link #invokeNarrowLong}. No half of an argument
         * slot the host never wrote is read as data.</p>
         *
         * <p>That still leaves a claim this method cannot act on, because
         * under LP64 {@code unsigned long} genuinely is 64 bits wide. A host
         * value at or above {@code 2^63} arrives as a NEGATIVE Java
         * {@code long} and is read as zero frames by {@code Math.max(0L,
         * frameCount)}; a merely enormous one is positive, yet multiplying it
         * by the frame stride to size the output segment still overflows to a
         * NEGATIVE byte count, and {@link MemorySegment#reinterpret(long)}
         * answers a negative size with an {@link IllegalArgumentException} —
         * thrown straight back out of this upcall into the driver, which is
         * the exact failure class the clamp exists to remove. The product is
         * therefore guarded against overflow ({@code hostFrames <=
         * Long.MAX_VALUE / perFrameBytes}) rather than taken on trust, and
         * that guard stays load-bearing on every LP64 host.</p>
         *
         * <p>Everything else about the host's claim IS taken on trust, and
         * deliberately so. Zeroing the frames beyond the ones we rendered
         * writes into memory only the host says it owns — the same trust the
         * sample-write loop has always placed in {@code frameCount}. The only
         * claim rejected here is one that cannot be represented at all: when
         * the byte count would overflow, the segment is sized to what was
         * actually rendered and the silencing fill is skipped, so nothing is
         * written past a buffer whose extent the host never described. There
         * is no plausibility ceiling and no magic multiple of
         * {@link #framesPerBuffer} — representability is the whole test.</p>
         *
         * @param frameCount the host's frame count for this period; any
         *                   {@code long} value is accepted, including a
         *                   negative one, which is read as zero frames
         * @return {@link PortAudioBindings#PA_CONTINUE}
         */
        @SuppressWarnings("unused") // invoked reflectively via MethodHandle
        public int invoke(MemorySegment input, MemorySegment output, long frameCount,
                          MemorySegment timeInfo, long statusFlags, MemorySegment userData) {
            long hostFrames = Math.max(0L, frameCount);
            int frames = (int) Math.min(hostFrames, framesPerBuffer);
            if (hostFrames > framesPerBuffer) {
                clampedOversizedPeriods++; // single writer: the driver's callback thread
            }

            // De-interleave input: native interleaved float buffer → float[][]
            if (inputChannels > 0 && !input.equals(MemorySegment.NULL)) {
                MemorySegment inputSeg = input.reinterpret((long) frames * inputChannels * Float.BYTES);
                for (int f = 0; f < frames; f++) {
                    for (int ch = 0; ch < inputChannels; ch++) {
                        inputBuffer[ch][f] = inputSeg.get(ValueLayout.JAVA_FLOAT,
                                (long) (f * inputChannels + ch) * Float.BYTES);
                    }
                }
            }

            // Invoke the Java callback with the CLAMPED count — the planes are
            // only framesPerBuffer long.
            callback.process(inputBuffer, outputBuffer, frames);

            // Interleave output: float[][] → native interleaved float buffer
            if (outputChannels > 0 && !output.equals(MemorySegment.NULL)) {
                // outputChannels > 0 is guaranteed by the enclosing test, so
                // perFrameBytes is positive and the division cannot trap.
                long renderedBytes = (long) frames * outputChannels * Float.BYTES;
                long perFrameBytes = (long) outputChannels * Float.BYTES;
                long hostBytes = hostFrames <= Long.MAX_VALUE / perFrameBytes
                        ? hostFrames * perFrameBytes
                        : renderedBytes; // not a period we can believe - write only what we rendered
                MemorySegment outputSeg = output.reinterpret(Math.max(hostBytes, renderedBytes));
                for (int f = 0; f < frames; f++) {
                    for (int ch = 0; ch < outputChannels; ch++) {
                        outputSeg.set(ValueLayout.JAVA_FLOAT,
                                (long) (f * outputChannels + ch) * Float.BYTES,
                                outputBuffer[ch][f]);
                    }
                }
                if (hostBytes > renderedBytes) {
                    // The frames we could not render are SILENCE, not whatever
                    // the driver left in its buffer.
                    outputSeg.asSlice(renderedBytes).fill((byte) 0);
                }
            }

            return PortAudioBindings.PA_CONTINUE;
        }

        /**
         * The same callback, for hosts whose C {@code long} is 32 bits wide.
         *
         * <p>This is the entry point the upcall stub binds on an LLP64
         * platform — Windows — where PortAudio's {@code unsigned long
         * frameCount} and its {@code PaStreamCallbackFlags} typedef are both
         * 32-bit C types, so {@link NativeAbi#C_LONG} marshals them into Java
         * {@code int} parameters. On an LP64 platform (Linux, macOS) this
         * method is never bound and {@link #invoke} is entered directly; see
         * {@link CallbackBridge#createHandle(CallbackInvoker)}.</p>
         *
         * <p>The parameters are {@code int} because that is what the ABI
         * delivers, and they are widened with
         * {@link Integer#toUnsignedLong(int)} rather than by a plain cast
         * because the C types are UNSIGNED. A widening cast is sign
         * extension: a host period of {@code 0xFFFFFFFF} would become
         * {@code -1}, which {@code Math.max(0L, frameCount)} reads as zero
         * frames — a silent dropout instead of the oversized period it
         * actually is, and one the clamp counter would never report.
         * {@code Integer.toUnsignedLong} reads it as {@code 4294967295}, so
         * the value {@link #invoke} sees is the number the host wrote.</p>
         *
         * <p>It delegates rather than duplicating: this method is an ABI
         * adapter and nothing else, so there is exactly one implementation of
         * the callback to reason about, and the ABI it adapts is the only
         * thing that can be wrong in it.</p>
         *
         * @param frameCount  the host's frame count, as an unsigned 32-bit
         *                    value
         * @param statusFlags PortAudio's {@code PaStreamCallbackFlags}, as an
         *                    unsigned 32-bit value
         * @return {@link PortAudioBindings#PA_CONTINUE}
         */
        @SuppressWarnings("unused") // invoked reflectively via MethodHandle
        public int invokeNarrowLong(MemorySegment input, MemorySegment output, int frameCount,
                                    MemorySegment timeInfo, int statusFlags,
                                    MemorySegment userData) {
            return invoke(input, output, Integer.toUnsignedLong(frameCount),
                    timeInfo, Integer.toUnsignedLong(statusFlags), userData);
        }

        /**
         * The frame count this stream was opened with, and therefore the
         * length of every channel plane.
         *
         * @return the requested frames per buffer
         */
        int framesPerBuffer() {
            return framesPerBuffer;
        }

        /**
         * How many host periods this stream clamped.
         *
         * @return the clamp count, {@code 0} when the host honoured the
         *         requested period on every callback
         */
        long clampedOversizedPeriods() {
            return clampedOversizedPeriods;
        }
    }
}
