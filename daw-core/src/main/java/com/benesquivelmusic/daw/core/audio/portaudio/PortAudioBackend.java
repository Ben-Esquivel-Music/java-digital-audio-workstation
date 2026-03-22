package com.benesquivelmusic.daw.core.audio.portaudio;

import com.benesquivelmusic.daw.sdk.audio.AudioBackendException;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.AudioStreamCallback;
import com.benesquivelmusic.daw.sdk.audio.AudioStreamConfig;
import com.benesquivelmusic.daw.sdk.audio.LatencyInfo;
import com.benesquivelmusic.daw.sdk.audio.NativeAudioBackend;
import com.benesquivelmusic.daw.sdk.audio.SampleRate;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private final PortAudioBindings bindings;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean streamActive = new AtomicBoolean(false);

    private List<AudioDeviceInfo> cachedDevices;
    private AudioStreamConfig currentConfig;
    private AudioStreamCallback currentCallback;
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

        var devices = new ArrayList<AudioDeviceInfo>(deviceCount);
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
        if (streamActive.get()) {
            stopStream();
        }
        int result = bindings.closeStream(streamHandle);
        PortAudioException.checkError(result, "Pa_CloseStream");
        streamHandle = null;
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
        var devices = getAvailableDevices();
        for (AudioDeviceInfo device : devices) {
            if (device.index() == index) {
                return device;
            }
        }
        return null;
    }

    private AudioDeviceInfo parseDeviceInfo(int index, MemorySegment infoPtr) {
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
                maxInputChannels,
                maxOutputChannels,
                defaultSampleRate,
                List.of(SampleRate.values()), // PortAudio typically supports all standard rates
                defaultLowInputLatency * 1000.0,
                defaultLowOutputLatency * 1000.0
        );
    }

    private MemorySegment allocateStreamParameters(Arena arena, int deviceIndex, int channels) {
        // PaStreamParameters struct: { int device, int channelCount, unsigned long sampleFormat,
        //                              double suggestedLatency, void* hostApiSpecificStreamInfo }
        var layout = MemoryLayout.structLayout(
                ValueLayout.JAVA_INT.withName("device"),
                ValueLayout.JAVA_INT.withName("channelCount"),
                ValueLayout.JAVA_LONG.withName("sampleFormat"),
                ValueLayout.JAVA_DOUBLE.withName("suggestedLatency"),
                ValueLayout.ADDRESS.withName("hostApiSpecificStreamInfo")
        );

        MemorySegment params = arena.allocate(layout);
        params.set(ValueLayout.JAVA_INT,
                layout.byteOffset(MemoryLayout.PathElement.groupElement("device")), deviceIndex);
        params.set(ValueLayout.JAVA_INT,
                layout.byteOffset(MemoryLayout.PathElement.groupElement("channelCount")), channels);
        params.set(ValueLayout.JAVA_LONG,
                layout.byteOffset(MemoryLayout.PathElement.groupElement("sampleFormat")),
                PortAudioBindings.PA_FLOAT32);
        params.set(ValueLayout.JAVA_DOUBLE,
                layout.byteOffset(MemoryLayout.PathElement.groupElement("suggestedLatency")), 0.0);
        params.set(ValueLayout.ADDRESS,
                layout.byteOffset(MemoryLayout.PathElement.groupElement("hostApiSpecificStreamInfo")),
                MemorySegment.NULL);
        return params;
    }

    private MemorySegment createCallbackStub(Arena arena, AudioStreamCallback callback,
                                             int inputChannels, int outputChannels, int framesPerBuffer) {
        // The PortAudio callback signature:
        // int callback(const void* input, void* output, unsigned long frameCount,
        //              const PaStreamCallbackTimeInfo* timeInfo,
        //              PaStreamCallbackFlags statusFlags, void* userData)
        var callbackDescriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS
        );

        // Create an upcall stub that bridges the C callback to the Java callback
        return Linker.nativeLinker().upcallStub(
                CallbackBridge.createHandle(callback, inputChannels, outputChannels, framesPerBuffer),
                callbackDescriptor,
                arena
        );
    }

    /**
     * Bridge between PortAudio's C callback and the Java
     * {@link AudioStreamCallback}. The static method is exposed as an
     * upcall stub via the FFM API.
     */
    static final class CallbackBridge {

        private CallbackBridge() {}

        static java.lang.invoke.MethodHandle createHandle(
                AudioStreamCallback callback, int inputChannels, int outputChannels, int framesPerBuffer) {
            // Pre-allocate the Java-side buffers once
            float[][] inputBuffer = inputChannels > 0 ? new float[inputChannels][framesPerBuffer] : new float[0][];
            float[][] outputBuffer = outputChannels > 0 ? new float[outputChannels][framesPerBuffer] : new float[0][];

            try {
                var lookup = java.lang.invoke.MethodHandles.lookup();
                return lookup.bind(
                        new CallbackInvoker(callback, inputBuffer, outputBuffer,
                                inputChannels, outputChannels),
                        "invoke",
                        java.lang.invoke.MethodType.methodType(
                                int.class,
                                MemorySegment.class, MemorySegment.class, long.class,
                                MemorySegment.class, long.class, MemorySegment.class)
                );
            } catch (NoSuchMethodException | IllegalAccessException e) {
                throw new AudioBackendException("Failed to create callback bridge", e);
            }
        }
    }

    /**
     * Invokable object that processes PortAudio callbacks by converting between
     * native interleaved float buffers and the Java {@code float[][]} format.
     */
    static final class CallbackInvoker {

        private final AudioStreamCallback callback;
        private final float[][] inputBuffer;
        private final float[][] outputBuffer;
        private final int inputChannels;
        private final int outputChannels;

        CallbackInvoker(AudioStreamCallback callback, float[][] inputBuffer, float[][] outputBuffer,
                        int inputChannels, int outputChannels) {
            this.callback = callback;
            this.inputBuffer = inputBuffer;
            this.outputBuffer = outputBuffer;
            this.inputChannels = inputChannels;
            this.outputChannels = outputChannels;
        }

        /**
         * Called from native code via the upcall stub.
         */
        @SuppressWarnings("unused") // invoked reflectively via MethodHandle
        public int invoke(MemorySegment input, MemorySegment output, long frameCount,
                          MemorySegment timeInfo, long statusFlags, MemorySegment userData) {
            int frames = (int) frameCount;

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

            // Invoke the Java callback
            callback.process(inputBuffer, outputBuffer, frames);

            // Interleave output: float[][] → native interleaved float buffer
            if (outputChannels > 0 && !output.equals(MemorySegment.NULL)) {
                MemorySegment outputSeg = output.reinterpret((long) frames * outputChannels * Float.BYTES);
                for (int f = 0; f < frames; f++) {
                    for (int ch = 0; ch < outputChannels; ch++) {
                        outputSeg.set(ValueLayout.JAVA_FLOAT,
                                (long) (f * outputChannels + ch) * Float.BYTES,
                                outputBuffer[ch][f]);
                    }
                }
            }

            return PortAudioBindings.PA_CONTINUE;
        }
    }
}
