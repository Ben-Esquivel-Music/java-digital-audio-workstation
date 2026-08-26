package com.benesquivelmusic.daw.core.audio.portaudio;

import com.benesquivelmusic.daw.core.audio.NativeAbi;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
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
 *   <li>{@code Pa_GetHostApiInfo} &mdash; <em>optional</em>; see
 *       {@link #getHostApiName(int)}</li>
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

    /**
     * {@code PaHostApiInfo} &mdash; what {@code Pa_GetHostApiInfo} hands back
     * (story 316 review).
     *
     * <pre>{@code
     * typedef struct PaHostApiInfo {
     *     int             structVersion;
     *     PaHostApiTypeId type;                 // C enum -> int
     *     const char     *name;
     *     int             deviceCount;
     *     PaDeviceIndex   defaultInputDevice;   // typedef int
     *     PaDeviceIndex   defaultOutputDevice;  // typedef int
     * } PaHostApiInfo;
     * }</pre>
     *
     * <p>Only the {@code name} member is read, and only to label a device with
     * the host API it enumerates under &mdash; {@code "Windows WASAPI"} rather
     * than {@code "PortAudio Host API 2"}. That label is what
     * {@code AudioDeviceInfo.qualifiedName()} disambiguates same-named devices
     * with, so a user on Windows, where one endpoint routinely appears under
     * MME, DirectSound, WASAPI and WDM-KS at once, is choosing between names
     * that mean something.</p>
     *
     * <p><strong>There is no C {@code long} in this struct.</strong> Every
     * member is an {@code int} or a pointer, so plain {@link ValueLayout#JAVA_INT}
     * and {@link ValueLayout#ADDRESS} are correct here and the
     * {@link NativeAbi#C_LONG} rule written up on
     * {@link #PA_STREAM_PARAMETERS_LAYOUT} &mdash; a C {@code long} is four
     * bytes under LLP64 and must never be declared {@code JAVA_LONG} &mdash;
     * has nothing to bite on. Said explicitly so the next reader does not have
     * to re-derive it from the header. {@code PaHostApiTypeId} is a plain C
     * enum whose largest declared constant is {@code paAudioScienceHPI = 14},
     * which every ABI this project targets passes as {@code int}.</p>
     *
     * <p>Padding is spelled out the way {@link #PA_DEVICE_INFO_LAYOUT} spells
     * out its own, because {@link MemoryLayout#structLayout(MemoryLayout...)}
     * inserts none of its own. Two {@code int}s precede {@code name}, so it
     * lands on offset 8 with no padding before it; the four bytes at the end
     * are the ones a C compiler adds to round the struct up to a multiple of
     * the pointer alignment. Offsets on a 64-bit data model:
     * {@code structVersion} 0, {@code type} 4, {@code name} 8,
     * {@code deviceCount} 16, {@code defaultInputDevice} 20,
     * {@code defaultOutputDevice} 24, total 32.</p>
     */
    static final MemoryLayout PA_HOST_API_INFO_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("structVersion"),
            ValueLayout.JAVA_INT.withName("type"),
            ValueLayout.ADDRESS.withName("name"),
            ValueLayout.JAVA_INT.withName("deviceCount"),
            ValueLayout.JAVA_INT.withName("defaultInputDevice"),
            ValueLayout.JAVA_INT.withName("defaultOutputDevice"),
            MemoryLayout.paddingLayout(4)
    );

    // PaStreamInfo struct layout
    static final MemoryLayout PA_STREAM_INFO_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("structVersion"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.JAVA_DOUBLE.withName("inputLatency"),
            ValueLayout.JAVA_DOUBLE.withName("outputLatency"),
            ValueLayout.JAVA_DOUBLE.withName("sampleRate")
    );

    /**
     * {@code PaStreamParameters} — the struct {@code Pa_OpenStream} takes once
     * per direction.
     *
     * <pre>{@code
     * typedef struct PaStreamParameters {
     *     PaDeviceIndex   device;                     // int
     *     int             channelCount;
     *     PaSampleFormat  sampleFormat;               // unsigned long
     *     PaTime          suggestedLatency;           // double
     *     void           *hostApiSpecificStreamInfo;
     * } PaStreamParameters;
     * }</pre>
     *
     * <p>{@code sampleFormat} is a C {@code unsigned long}, so it is
     * {@link NativeAbi#C_LONG} and not {@link ValueLayout#JAVA_LONG}. Under
     * LLP64 that member is four bytes wide, and a {@code JAVA_LONG}
     * declaration writes eight — spilling over the padding that follows it.
     * Today that spill is harmless, and only by coincidence: the four bytes
     * behind {@code sampleFormat} happen to be the padding that re-aligns
     * {@code suggestedLatency}, so the following members keep their offsets,
     * and {@link #PA_FLOAT32} fits in 32 bits, so the member's own value
     * survives the little-endian store. Neither of those is a property of the
     * declaration: reorder the struct, or name a format that does not fit,
     * and the same declaration corrupts a live field. The width is stated
     * correctly here so that nothing has to rely on the coincidence.</p>
     *
     * <p>FFM's {@link MemoryLayout#structLayout(MemoryLayout...)} inserts no
     * padding of its own; it rejects a member that lands on an offset its own
     * alignment forbids. The four bytes a C compiler adds after a 32-bit
     * {@code sampleFormat} to re-align the {@code double} are therefore
     * spelled out here, the same way {@link #PA_DEVICE_INFO_LAYOUT} spells out
     * its own. The result is 32 bytes on BOTH data models, with
     * {@code device} at 0, {@code channelCount} at 4, {@code sampleFormat} at
     * 8, {@code suggestedLatency} at 16 and
     * {@code hostApiSpecificStreamInfo} at 24 — one struct, described
     * honestly twice rather than described once and hoped over.</p>
     */
    static final MemoryLayout PA_STREAM_PARAMETERS_LAYOUT = paStreamParametersLayout();

    private static MemoryLayout paStreamParametersLayout() {
        List<MemoryLayout> members = new ArrayList<>(6);
        members.add(ValueLayout.JAVA_INT.withName("device"));
        members.add(ValueLayout.JAVA_INT.withName("channelCount"));
        members.add(NativeAbi.C_LONG.withName("sampleFormat"));
        if (NativeAbi.C_LONG_IS_32_BIT) {
            members.add(MemoryLayout.paddingLayout(Long.BYTES - Integer.BYTES));
        }
        members.add(ValueLayout.JAVA_DOUBLE.withName("suggestedLatency"));
        members.add(ValueLayout.ADDRESS.withName("hostApiSpecificStreamInfo"));
        return MemoryLayout.structLayout(members.toArray(new MemoryLayout[0]));
    }

    /**
     * {@code Pa_OpenStream}'s C signature.
     *
     * <pre>{@code
     * PaError Pa_OpenStream(PaStream** stream,
     *                       const PaStreamParameters* inputParameters,
     *                       const PaStreamParameters* outputParameters,
     *                       double sampleRate,
     *                       unsigned long framesPerBuffer,
     *                       PaStreamFlags streamFlags,        // unsigned long
     *                       PaStreamCallback* streamCallback,
     *                       void* userData);
     * }</pre>
     *
     * <p>{@code framesPerBuffer} and {@code streamFlags} are both C
     * {@code unsigned long}, hence {@link NativeAbi#C_LONG} rather than
     * {@link ValueLayout#JAVA_LONG}. Package-private, and separated from
     * {@link #bindFunctions()}, so the ABI can be asserted on a host with no
     * PortAudio installed: no CI workflow in this repository installs it, so
     * a descriptor only reachable through a successful symbol lookup would be
     * a descriptor nothing ever checks.</p>
     */
    static final FunctionDescriptor PA_OPEN_STREAM_DESCRIPTOR = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.JAVA_DOUBLE, NativeAbi.C_LONG, NativeAbi.C_LONG,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS);

    /**
     * The single Java signature {@link #openStream} calls
     * {@code Pa_OpenStream} through, on every platform.
     *
     * <p>{@link MethodHandle#invokeExact} matches the handle's type
     * <em>exactly</em> — no widening, no narrowing, no boxing. On an LLP64
     * host {@link #PA_OPEN_STREAM_DESCRIPTOR} marshals those two parameters as
     * {@code int}, so the raw downcall handle's type ends
     * {@code (..., int, int, ...)} and the {@code long} arguments
     * {@link #openStream} passes would raise
     * {@link java.lang.invoke.WrongMethodTypeException} instead of calling
     * anything. Forking the public signature per platform was the alternative
     * and it is the wrong one: callers are talking about frames and flags, not
     * about the width of the host's C {@code long}. The handle is adapted to
     * this fixed type by {@link #adaptOpenStreamHandle(MethodHandle)}
     * instead.</p>
     */
    static final MethodType PA_OPEN_STREAM_JAVA_TYPE = MethodType.methodType(
            int.class,
            MemorySegment.class, MemorySegment.class, MemorySegment.class,
            double.class, long.class, long.class,
            MemorySegment.class, MemorySegment.class);

    private static final Linker LINKER = Linker.nativeLinker();

    private final SymbolLookup lookup;
    private final Arena arena;
    private final boolean available;

    // Cached method handles for hot-path operations
    private MethodHandle paInitialize;
    private MethodHandle paTerminate;
    private MethodHandle paGetDeviceCount;
    private MethodHandle paGetDeviceInfo;
    /** Null when the loaded library does not export it &mdash; see {@link #getHostApiName(int)}. */
    private MethodHandle paGetHostApiInfo;
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
     * Calls {@code Pa_GetHostApiInfo(hostApiIndex)} (story 316 review).
     *
     * <p>The returned struct is owned by PortAudio and is only valid between
     * {@code Pa_Initialize} and {@code Pa_Terminate}, so callers must not
     * retain it across a terminate/initialize cycle.</p>
     *
     * <p>Answers {@link MemorySegment#NULL} when the symbol was not bound,
     * which is the same answer the C function itself gives for an
     * out-of-range index &mdash; one degraded value for callers to test rather
     * than two. {@link #getHostApiName(int)} is the reason this may be
     * unbound; see its javadoc.</p>
     *
     * @param hostApiIndex the host API index
     * @return the memory segment pointing to the PaHostApiInfo struct, or
     *         {@code MemorySegment.NULL}
     */
    public MemorySegment getHostApiInfo(int hostApiIndex) {
        MethodHandle handle = paGetHostApiInfo;
        if (handle == null) {
            return MemorySegment.NULL;
        }
        try {
            return (MemorySegment) handle.invokeExact(hostApiIndex);
        } catch (Throwable e) {
            throw new PortAudioException("Pa_GetHostApiInfo invocation failed", -1, e);
        }
    }

    /**
     * The driver's own name for a host API &mdash; {@code "Windows WASAPI"},
     * {@code "MME"}, {@code "ASIO"}, {@code "ALSA"}, {@code "Core Audio"}
     * (story 316 review).
     *
     * <p>This exists to make a device label mean something. Without it the
     * only thing a {@code PaDeviceInfo} carries about its host API is an
     * index, so the disambiguating label on
     * {@code AudioDeviceInfo.qualifiedName()} read
     * {@code "Speakers (Realtek) [PortAudio Host API 2]"}, and
     * {@code CallbackBackendAdapter}'s ambiguous-selection message, which
     * lists the competing entries by that same qualified name and asks the
     * user to re-select one of them, offered a choice between
     * {@code 'Speakers (Realtek) [PortAudio Host API 1]'} and
     * {@code 'Speakers (Realtek) [PortAudio Host API 2]'}. On Windows the same
     * endpoint routinely enumerates under MME, DirectSound, WASAPI and WDM-KS,
     * so that is exactly the choice the user is asked to make &mdash; and an
     * index is not something they can make it on.</p>
     *
     * <p><strong>Every failure degrades to {@code null}; none of them
     * throws.</strong> The caller is building a cosmetic label during device
     * enumeration, and enumeration failing is a far worse outcome than a label
     * falling back to {@code "PortAudio Host API 2"}. {@code null} is returned
     * when: the symbol was not bound, {@code Pa_GetHostApiInfo} answered
     * {@code NULL} (out-of-range index, or PortAudio not initialized), the
     * struct's {@code name} pointer is {@code NULL}, or reading it threw. The
     * unbound case funnels through {@link #getHostApiInfo(int)}'s
     * {@code MemorySegment.NULL} rather than being tested twice, so there is
     * one degraded path here and not two.</p>
     *
     * <p>{@link Optional} was the alternative and it buys nothing here: the
     * only production caller feeds the result straight into a "use it if it is
     * neither null nor blank" test, which an empty {@code Optional} would only
     * re-spell.</p>
     *
     * <p>The name is read with the same bounded {@code reinterpret(256)} the
     * device name is read with in {@code PortAudioBackend.parseDeviceInfo}: the
     * C string's length is not knowable from the struct, so a bound has to be
     * chosen, and one bound used consistently is the only way that choice
     * stays reviewable.</p>
     *
     * @param hostApiIndex the host API index, as carried by a
     *                     {@code PaDeviceInfo}'s {@code hostApi} member
     * @return the host API's display name, or {@code null} if it cannot be
     *         determined
     */
    public String getHostApiName(int hostApiIndex) {
        try {
            MemorySegment infoPtr = getHostApiInfo(hostApiIndex);
            if (infoPtr.equals(MemorySegment.NULL)) {
                return null;
            }
            MemorySegment info = infoPtr.reinterpret(PA_HOST_API_INFO_LAYOUT.byteSize());
            MemorySegment namePtr = info.get(ValueLayout.ADDRESS,
                    PA_HOST_API_INFO_LAYOUT.byteOffset(
                            MemoryLayout.PathElement.groupElement("name")));
            if (namePtr.equals(MemorySegment.NULL)) {
                return null;
            }
            return namePtr.reinterpret(256).getString(0);
        } catch (RuntimeException e) {
            // Deliberately broad: PortAudioException from the downcall, plus
            // whatever reinterpreting and decoding a pointer a third-party
            // host-API plug-in supplied can raise. Nothing this method can
            // fail at is worth failing an enumeration over.
            return null;
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
     * <p>{@code framesPerBuffer} and {@code streamFlags} are declared
     * {@code long} here on every platform, even though the C parameters are
     * {@code unsigned long} and therefore 32 bits wide under LLP64. The
     * width difference is absorbed inside the bound handle — see
     * {@link #PA_OPEN_STREAM_JAVA_TYPE} and
     * {@link #adaptOpenStreamHandle(MethodHandle)} — so that callers never
     * have to know which data model they are on.</p>
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
        // The one OPTIONAL binding — see optionalDowncallHandle.
        paGetHostApiInfo = optionalDowncallHandle("Pa_GetHostApiInfo",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        paGetDefaultInputDevice = downcallHandle("Pa_GetDefaultInputDevice",
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
        paGetDefaultOutputDevice = downcallHandle("Pa_GetDefaultOutputDevice",
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
        paOpenStream = adaptOpenStreamHandle(
                downcallHandle("Pa_OpenStream", PA_OPEN_STREAM_DESCRIPTOR));
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

    /**
     * Adapts a raw {@code Pa_OpenStream} downcall handle to the fixed
     * {@link #PA_OPEN_STREAM_JAVA_TYPE}.
     *
     * <p>{@link MethodHandles#explicitCastArguments} and NOT
     * {@link MethodHandle#asType}: {@code asType} performs only the
     * conversions the language applies implicitly, and {@code long} to
     * {@code int} is a narrowing primitive conversion, which it refuses with
     * {@link java.lang.invoke.WrongMethodTypeException}. LLP64 needs precisely
     * that narrowing — the C parameter genuinely is 32 bits wide there — and
     * {@code explicitCastArguments} applies cast semantics, so it performs
     * it. On LP64 the raw type already equals the target type and the
     * adaptation is an identity no-op.</p>
     *
     * <p>Static, and taking the handle as a parameter rather than reading the
     * field, so a test can adapt a stand-in built with
     * {@link MethodHandles#empty(MethodType)} and prove the resulting call
     * shape on a host where PortAudio is not installed.</p>
     *
     * @param raw the handle the linker produced for
     *            {@link #PA_OPEN_STREAM_DESCRIPTOR}
     * @return a handle of type {@link #PA_OPEN_STREAM_JAVA_TYPE}
     */
    static MethodHandle adaptOpenStreamHandle(MethodHandle raw) {
        return MethodHandles.explicitCastArguments(raw, PA_OPEN_STREAM_JAVA_TYPE);
    }

    private MethodHandle downcallHandle(String name, FunctionDescriptor descriptor) {
        Optional<MemorySegment> symbol = lookup.find(name);
        if (symbol.isEmpty()) {
            throw new PortAudioException("Symbol not found: " + name, -1);
        }
        return LINKER.downcallHandle(symbol.get(), descriptor);
    }

    /**
     * Binds a symbol the backend can live without, answering {@code null}
     * instead of throwing when the loaded library does not export it (story
     * 316 review).
     *
     * <p>{@link #downcallHandle(String, FunctionDescriptor)} throws
     * {@code "Symbol not found: …"}, and {@link #bindFunctions()} runs from the
     * constructor, so every symbol bound through it is a HARD requirement: one
     * missing export and the whole PortAudio backend fails to construct, taking
     * playback with it. That is the right trade for every OTHER symbol this
     * class binds &mdash; a library that cannot open a stream is of no use
     * &mdash; and the wrong one for {@code Pa_GetHostApiInfo}, whose only
     * consumer builds a display label. Trading "audio works at all" for "the label reads
     * {@code [Windows WASAPI]} instead of {@code [PortAudio Host API 2]}" is
     * not a trade worth making against a library build we do not control, so
     * this one symbol is bound tolerantly and its consumer degrades.</p>
     *
     * @param name       the native symbol
     * @param descriptor the symbol's signature
     * @return the bound handle, or {@code null} if the library does not export
     *         the symbol
     */
    private MethodHandle optionalDowncallHandle(String name, FunctionDescriptor descriptor) {
        return lookup.find(name)
                .map(symbol -> LINKER.downcallHandle(symbol, descriptor))
                .orElse(null);
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
