package com.benesquivelmusic.daw.core.audio.portaudio;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.Optional;

/**
 * Raw FFM (Foreign Function &amp; Memory API — JEP 454) bindings for the
 * PortAudio C library.
 *
 * <p>This class provides thin Java wrappers around PortAudio functions using
 * {@link Linker} and {@link SymbolLookup} to locate and invoke native symbols
 * at runtime — no JNI or generated code required.</p>
 *
 * <h2>Supported Functions</h2>
 * <ul>
 *   <li>{@code Pa_Initialize} / {@code Pa_Terminate}</li>
 *   <li>{@code Pa_GetDeviceCount} / {@code Pa_GetDeviceInfo}</li>
 *   <li>{@code Pa_GetDefaultInputDevice} / {@code Pa_GetDefaultOutputDevice}</li>
 *   <li>{@code Pa_OpenStream} / {@code Pa_StartStream} / {@code Pa_StopStream} / {@code Pa_CloseStream}</li>
 *   <li>{@code Pa_IsStreamActive}</li>
 *   <li>{@code Pa_GetStreamInfo}</li>
 *   <li>{@code Pa_GetErrorText}</li>
 * </ul>
 *
 * <h2>Native Library Loading</h2>
 * <p>The PortAudio shared library ({@code libportaudio.so}, {@code libportaudio.dylib},
 * or {@code portaudio.dll}) must be on the system library path. Use
 * {@link #isAvailable()} to check before calling any other method.</p>
 *
 * @see <a href="http://www.portaudio.com/docs/v19-doxydocs/portaudio_8h.html">PortAudio API Reference</a>
 */
public final class PortAudioBindings {

    // PortAudio constants
    /** PortAudio sample format: 32-bit float. */
    public static final long PA_FLOAT32 = 0x00000001L;

    /** PortAudio no-error code. */
    public static final int PA_NO_ERROR = 0;

    /** Sentinel for "no device". */
    public static final int PA_NO_DEVICE = -1;

    // PaStreamCallbackResult constants
    /** Continue processing audio. */
    public static final int PA_CONTINUE = 0;

    /** Stop the stream after this callback returns. */
    public static final int PA_COMPLETE = 1;

    /** Abort the stream immediately. */
    public static final int PA_ABORT = 2;

    // PaDeviceInfo struct field offsets (portable C struct)
    // These are approximations; real offsets depend on platform ABI.
    // The struct layout is resolved at initialization time.
    static final MemoryLayout PA_DEVICE_INFO_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("structVersion"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("name"),
            ValueLayout.JAVA_INT.withName("hostApi"),
            ValueLayout.JAVA_INT.withName("maxInputChannels"),
            ValueLayout.JAVA_INT.withName("maxOutputChannels"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.JAVA_DOUBLE.withName("defaultLowInputLatency"),
            ValueLayout.JAVA_DOUBLE.withName("defaultLowOutputLatency"),
            ValueLayout.JAVA_DOUBLE.withName("defaultHighInputLatency"),
            ValueLayout.JAVA_DOUBLE.withName("defaultHighOutputLatency"),
            ValueLayout.JAVA_DOUBLE.withName("defaultSampleRate")
    );

    // PaStreamInfo struct layout
    static final MemoryLayout PA_STREAM_INFO_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("structVersion"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.JAVA_DOUBLE.withName("inputLatency"),
            ValueLayout.JAVA_DOUBLE.withName("outputLatency"),
            ValueLayout.JAVA_DOUBLE.withName("sampleRate")
    );

    private static final Linker LINKER = Linker.nativeLinker();

    private final SymbolLookup lookup;
    private final Arena arena;
    private final boolean available;

    // Cached method handles for hot-path operations
    private MethodHandle paInitialize;
    private MethodHandle paTerminate;
    private MethodHandle paGetDeviceCount;
    private MethodHandle paGetDeviceInfo;
    private MethodHandle paGetDefaultInputDevice;
    private MethodHandle paGetDefaultOutputDevice;
    private MethodHandle paOpenStream;
    private MethodHandle paStartStream;
    private MethodHandle paStopStream;
    private MethodHandle paCloseStream;
    private MethodHandle paIsStreamActive;
    private MethodHandle paGetStreamInfo;
    private MethodHandle paGetErrorText;

    /**
     * Creates bindings for the PortAudio native library.
     *
     * <p>If the native library cannot be loaded, {@link #isAvailable()} returns
     * {@code false} and no other methods should be called.</p>
     */
    public PortAudioBindings() {
        SymbolLookup tempLookup = null;
        boolean tempAvailable = false;
        Arena tempArena = Arena.ofAuto();

        try {
            tempLookup = SymbolLookup.libraryLookup(resolveLibraryName(), tempArena);
            tempAvailable = true;
        } catch (IllegalArgumentException | UnsatisfiedLinkError _) {
            // Native library not found — this is expected on systems without PortAudio
        }

        this.lookup = tempLookup;
        this.arena = tempArena;
        this.available = tempAvailable;

        if (available) {
            bindFunctions();
        }
    }

    /**
     * Returns whether the PortAudio native library is available.
     *
     * @return true if the library was loaded successfully
     */
    public boolean isAvailable() {
        return available;
    }

    /**
     * Calls {@code Pa_Initialize()}.
     *
     * @return the PortAudio error code
     */
    public int initialize() {
        try {
            return (int) paInitialize.invokeExact();
        } catch (Throwable e) {
            throw new PortAudioException("Pa_Initialize invocation failed", -1, e);
        }
    }

    /**
     * Calls {@code Pa_Terminate()}.
     *
     * @return the PortAudio error code
     */
    public int terminate() {
        try {
            return (int) paTerminate.invokeExact();
        } catch (Throwable e) {
            throw new PortAudioException("Pa_Terminate invocation failed", -1, e);
        }
    }

    /**
     * Calls {@code Pa_GetDeviceCount()}.
     *
     * @return the number of devices, or a negative error code
     */
    public int getDeviceCount() {
        try {
            return (int) paGetDeviceCount.invokeExact();
        } catch (Throwable e) {
            throw new PortAudioException("Pa_GetDeviceCount invocation failed", -1, e);
        }
    }

    /**
     * Calls {@code Pa_GetDeviceInfo(deviceIndex)}.
     *
     * @param deviceIndex the device index
     * @return the memory segment pointing to the PaDeviceInfo struct, or {@code MemorySegment.NULL}
     */
    public MemorySegment getDeviceInfo(int deviceIndex) {
        try {
            return (MemorySegment) paGetDeviceInfo.invokeExact(deviceIndex);
        } catch (Throwable e) {
            throw new PortAudioException("Pa_GetDeviceInfo invocation failed", -1, e);
        }
    }

    /**
     * Calls {@code Pa_GetDefaultInputDevice()}.
     *
     * @return the default input device index, or {@link #PA_NO_DEVICE}
     */
    public int getDefaultInputDevice() {
        try {
            return (int) paGetDefaultInputDevice.invokeExact();
        } catch (Throwable e) {
            throw new PortAudioException("Pa_GetDefaultInputDevice invocation failed", -1, e);
        }
    }

    /**
     * Calls {@code Pa_GetDefaultOutputDevice()}.
     *
     * @return the default output device index, or {@link #PA_NO_DEVICE}
     */
    public int getDefaultOutputDevice() {
        try {
            return (int) paGetDefaultOutputDevice.invokeExact();
        } catch (Throwable e) {
            throw new PortAudioException("Pa_GetDefaultOutputDevice invocation failed", -1, e);
        }
    }

    /**
     * Calls {@code Pa_OpenStream(...)}.
     *
     * @param streamPtr            pointer to receive the stream handle
     * @param inputParameters      input stream parameters (or {@code MemorySegment.NULL})
     * @param outputParameters     output stream parameters (or {@code MemorySegment.NULL})
     * @param sampleRate           the desired sample rate
     * @param framesPerBuffer      the desired buffer size
     * @param streamFlags          stream flags
     * @param streamCallback       the callback function pointer (or {@code MemorySegment.NULL})
     * @param userData             user data pointer (or {@code MemorySegment.NULL})
     * @return the PortAudio error code
     */
    public int openStream(MemorySegment streamPtr,
                          MemorySegment inputParameters,
                          MemorySegment outputParameters,
                          double sampleRate,
                          long framesPerBuffer,
                          long streamFlags,
                          MemorySegment streamCallback,
                          MemorySegment userData) {
        try {
            return (int) paOpenStream.invokeExact(
                    streamPtr, inputParameters, outputParameters,
                    sampleRate, framesPerBuffer, streamFlags,
                    streamCallback, userData);
        } catch (Throwable e) {
            throw new PortAudioException("Pa_OpenStream invocation failed", -1, e);
        }
    }

    /**
     * Calls {@code Pa_StartStream(stream)}.
     *
     * @param stream the stream handle
     * @return the PortAudio error code
     */
    public int startStream(MemorySegment stream) {
        try {
            return (int) paStartStream.invokeExact(stream);
        } catch (Throwable e) {
            throw new PortAudioException("Pa_StartStream invocation failed", -1, e);
        }
    }

    /**
     * Calls {@code Pa_StopStream(stream)}.
     *
     * @param stream the stream handle
     * @return the PortAudio error code
     */
    public int stopStream(MemorySegment stream) {
        try {
            return (int) paStopStream.invokeExact(stream);
        } catch (Throwable e) {
            throw new PortAudioException("Pa_StopStream invocation failed", -1, e);
        }
    }

    /**
     * Calls {@code Pa_CloseStream(stream)}.
     *
     * @param stream the stream handle
     * @return the PortAudio error code
     */
    public int closeStream(MemorySegment stream) {
        try {
            return (int) paCloseStream.invokeExact(stream);
        } catch (Throwable e) {
            throw new PortAudioException("Pa_CloseStream invocation failed", -1, e);
        }
    }

    /**
     * Calls {@code Pa_IsStreamActive(stream)}.
     *
     * @param stream the stream handle
     * @return 1 if active, 0 if not, or a negative error code
     */
    public int isStreamActive(MemorySegment stream) {
        try {
            return (int) paIsStreamActive.invokeExact(stream);
        } catch (Throwable e) {
            throw new PortAudioException("Pa_IsStreamActive invocation failed", -1, e);
        }
    }

    /**
     * Calls {@code Pa_GetStreamInfo(stream)}.
     *
     * @param stream the stream handle
     * @return the memory segment pointing to the PaStreamInfo struct
     */
    public MemorySegment getStreamInfo(MemorySegment stream) {
        try {
            return (MemorySegment) paGetStreamInfo.invokeExact(stream);
        } catch (Throwable e) {
            throw new PortAudioException("Pa_GetStreamInfo invocation failed", -1, e);
        }
    }

    /**
     * Calls {@code Pa_GetErrorText(errorCode)}.
     *
     * @param errorCode the PortAudio error code
     * @return the error text, or a generic message if lookup fails
     */
    public String getErrorText(int errorCode) {
        try {
            MemorySegment textPtr = (MemorySegment) paGetErrorText.invokeExact(errorCode);
            if (textPtr.equals(MemorySegment.NULL)) {
                return "Unknown error: " + errorCode;
            }
            return textPtr.reinterpret(256).getString(0);
        } catch (Throwable e) {
            return "Error code: " + errorCode;
        }
    }

    // --- Internal helpers ---

    private void bindFunctions() {
        paInitialize = downcallHandle("Pa_Initialize",
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
        paTerminate = downcallHandle("Pa_Terminate",
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
        paGetDeviceCount = downcallHandle("Pa_GetDeviceCount",
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
        paGetDeviceInfo = downcallHandle("Pa_GetDeviceInfo",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        paGetDefaultInputDevice = downcallHandle("Pa_GetDefaultInputDevice",
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
        paGetDefaultOutputDevice = downcallHandle("Pa_GetDefaultOutputDevice",
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
        paOpenStream = downcallHandle("Pa_OpenStream",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        paStartStream = downcallHandle("Pa_StartStream",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        paStopStream = downcallHandle("Pa_StopStream",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        paCloseStream = downcallHandle("Pa_CloseStream",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        paIsStreamActive = downcallHandle("Pa_IsStreamActive",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        paGetStreamInfo = downcallHandle("Pa_GetStreamInfo",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        paGetErrorText = downcallHandle("Pa_GetErrorText",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    }

    private MethodHandle downcallHandle(String name, FunctionDescriptor descriptor) {
        Optional<MemorySegment> symbol = lookup.find(name);
        if (symbol.isEmpty()) {
            throw new PortAudioException("Symbol not found: " + name, -1);
        }
        return LINKER.downcallHandle(symbol.get(), descriptor);
    }

    private static String resolveLibraryName() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "portaudio";
        } else if (os.contains("mac")) {
            return "libportaudio.dylib";
        } else {
            return "libportaudio.so";
        }
    }
}
