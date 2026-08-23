package com.benesquivelmusic.daw.sdk.audio;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * FFM (JEP 454, final in Java 22) binding for the real-time streaming
 * entrypoints exported by {@code asioshim.dll} (story 311):
 * {@code asioshim_createBuffers} / {@code asioshim_getBufferInfos} /
 * {@code asioshim_start} / {@code asioshim_stop} /
 * {@code asioshim_disposeBuffers}, plus the
 * {@code installAsioBufferSwitchCallback} /
 * {@code uninstallAsioBufferSwitchCallback} pair that registers the JVM
 * upcall built by {@link AsioBufferSwitchShim}.
 *
 * <p>Shape and degradation semantics are identical to {@link AsioDriverShim}
 * and {@link AsioCapabilityShim}: construction never throws, a missing
 * library or symbol leaves the corresponding {@link MethodHandle} null, and
 * every operation then reports "unavailable" instead of failing. On a normal
 * checkout the Steinberg SDK is absent, {@code asioshim.dll} is not built, and
 * {@link #isStreamingAvailable()} is {@code false} — in which case
 * {@link AsioBackend#open(DeviceId, AudioFormat, int)} keeps exactly the
 * story-310 behaviour and no audio streams.</p>
 *
 * <p>Every call here is a <em>downcall</em> and is therefore serialized on the
 * dedicated {@link AsioControlThread}, preserving the COM apartment affinity
 * the Steinberg host glue requires. The buffer-switch <em>upcall</em> arrives
 * on the driver's own real-time thread and must never touch this class.</p>
 *
 * <p>Non-final, with package-private methods, so unit tests can subclass it —
 * the same seam {@link AsioDriverShim} and {@link AsioCapabilityShim} provide.</p>
 */
class AsioStreamingShim implements AutoCloseable {

    /** Shim success convention shared by every {@code asioshim_*} export. */
    private static final int SHIM_OK = 1;

    /**
     * Bytes per {@code asioshim_getBufferInfos} record:
     * {@code int32 channel}, {@code int32 isInput}, {@code int32 sampleType},
     * {@code int32 reserved}, {@code int64 buffer0}, {@code int64 buffer1}.
     */
    static final int BUFFER_INFO_STRIDE = 32;

    /**
     * Byte alignment {@code asioshim_getBufferInfos} records require. The two
     * pointer fields are read as {@code int64} at {@code base + 16} /
     * {@code base + 24}, so the array's base address must be 8-byte aligned.
     */
    static final int BUFFER_INFO_ALIGNMENT = 8;

    /**
     * Maximum number of active channels the native side accepts:
     * {@code asioshim_createBuffers} rejects
     * {@code numInputs + numOutputs > 64} ({@code MAX_STREAM_CHANNELS} in
     * {@code asioshim.cpp}). The cap is on the <em>combined</em> count, so a
     * symmetric request tops out at 32 in + 32 out.
     */
    static final int MAX_STREAM_CHANNELS = 64;

    /**
     * Capacity this shim hands to {@code asioshim_getBufferInfos} — one record
     * per activatable channel.
     */
    static final int BUFFER_INFO_CAPACITY = MAX_STREAM_CHANNELS;

    // ── ABI contract ──────────────────────────────────────────────────
    // One constant per exported symbol. The production bindings below and
    // AsioStreamingShimTest's pure-Java upcall/downcall round trip both read
    // these, so an argument-count or carrier-type drift fails in CI without
    // any native library present (story 311).

    /**
     * {@code int asioshim_createBuffers(const int* inputChannels, int numInputs,
     * const int* outputChannels, int numOutputs, int bufferFrames)}.
     */
    static final FunctionDescriptor CREATE_BUFFERS =
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT);

    /** {@code int asioshim_getBufferInfos(void* outArray, int* outCount)}. */
    static final FunctionDescriptor GET_BUFFER_INFOS =
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS);

    /** {@code int asioshim_start(void)}. */
    static final FunctionDescriptor START =
            FunctionDescriptor.of(ValueLayout.JAVA_INT);

    /** {@code int asioshim_stop(void)}. */
    static final FunctionDescriptor STOP =
            FunctionDescriptor.of(ValueLayout.JAVA_INT);

    /** {@code int asioshim_disposeBuffers(void)}. */
    static final FunctionDescriptor DISPOSE_BUFFERS =
            FunctionDescriptor.of(ValueLayout.JAVA_INT);

    /** {@code void installAsioBufferSwitchCallback(void* callback)}. */
    static final FunctionDescriptor INSTALL_BUFFER_SWITCH_CALLBACK =
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);

    /** {@code void uninstallAsioBufferSwitchCallback(void)}. */
    static final FunctionDescriptor UNINSTALL_BUFFER_SWITCH_CALLBACK =
            FunctionDescriptor.ofVoid();

    private final Arena arena;
    private final MethodHandle createBuffers;
    private final MethodHandle getBufferInfos;
    private final MethodHandle start;
    private final MethodHandle stop;
    private final MethodHandle disposeBuffers;
    private final MethodHandle installCallback;
    private final MethodHandle uninstallCallback;
    private volatile boolean closed;

    /**
     * Loads {@code asioshim} and resolves the seven streaming symbols. Any
     * failure is captured silently; the resulting shim simply reports
     * {@link #isStreamingAvailable()} = {@code false}.
     */
    AsioStreamingShim() {
        arena = Arena.ofShared();
        MethodHandle create = null;
        MethodHandle infos = null;
        MethodHandle begin = null;
        MethodHandle end = null;
        MethodHandle dispose = null;
        MethodHandle install = null;
        MethodHandle uninstall = null;
        try {
            SymbolLookup lookup = SymbolLookup.libraryLookup("asioshim", arena);
            Linker linker = Linker.nativeLinker();
            create = resolve(linker, lookup, "asioshim_createBuffers", CREATE_BUFFERS);
            infos = resolve(linker, lookup, "asioshim_getBufferInfos", GET_BUFFER_INFOS);
            begin = resolve(linker, lookup, "asioshim_start", START);
            end = resolve(linker, lookup, "asioshim_stop", STOP);
            dispose = resolve(linker, lookup, "asioshim_disposeBuffers", DISPOSE_BUFFERS);
            install = resolve(linker, lookup, "installAsioBufferSwitchCallback",
                    INSTALL_BUFFER_SWITCH_CALLBACK);
            uninstall = resolve(linker, lookup, "uninstallAsioBufferSwitchCallback",
                    UNINSTALL_BUFFER_SWITCH_CALLBACK);
        } catch (IllegalArgumentException | UnsatisfiedLinkError ignored) {
            // Optional DLL absent (every non-Windows host, and every Windows
            // build made without -DASIO_SDK_DIR): degrade to a no-op shim.
        } catch (Throwable ignored) {
            // Native access disabled or ABI mismatch: keep all handles null so
            // isStreamingAvailable() reports false rather than exploding later.
        }
        createBuffers = create;
        getBufferInfos = infos;
        start = begin;
        stop = end;
        disposeBuffers = dispose;
        installCallback = install;
        uninstallCallback = uninstall;
    }

    private static MethodHandle resolve(Linker linker, SymbolLookup lookup,
                                        String symbol, FunctionDescriptor descriptor) {
        return lookup.find(symbol)
                .map(address -> linker.downcallHandle(address, descriptor))
                .orElse(null);
    }

    /**
     * Returns {@code true} only when this shim is open and every streaming
     * symbol resolved. A partially-resolved shim (an older {@code asioshim}
     * build) is treated as unavailable so {@code open(...)} keeps the
     * story-310 no-streaming path rather than half-starting a driver.
     */
    boolean isStreamingAvailable() {
        return !closed
                && createBuffers != null
                && getBufferInfos != null
                && start != null
                && stop != null
                && disposeBuffers != null
                && installCallback != null
                && uninstallCallback != null;
    }

    /**
     * Calls {@code ASIOCreateBuffers} for the requested active channels with
     * the shim's host-callback set installed.
     *
     * @param inputChannels  driver input channel indices to activate; must not be null
     * @param outputChannels driver output channel indices to activate; must not be null
     * @param bufferFrames   negotiated buffer size in sample frames; must be positive
     * @return {@code true} on {@code ASE_OK}
     */
    boolean createBuffers(int[] inputChannels, int[] outputChannels, int bufferFrames) {
        Objects.requireNonNull(inputChannels, "inputChannels must not be null");
        Objects.requireNonNull(outputChannels, "outputChannels must not be null");
        if (!isStreamingAvailable() || bufferFrames <= 0) {
            return false;
        }
        try {
            return AsioControlThread.call(() -> {
                try (Arena call = Arena.ofConfined()) {
                    MemorySegment ins = allocateChannels(call, inputChannels);
                    MemorySegment outs = allocateChannels(call, outputChannels);
                    int status = (int) createBuffers.invokeExact(
                            ins, inputChannels.length,
                            outs, outputChannels.length,
                            bufferFrames);
                    return status == SHIM_OK;
                }
            });
        } catch (Throwable ignored) {
            // Native call failed or the ABI mismatched — report rejection so
            // AsioBackend#open rolls back and surfaces AudioBackendException.
            return false;
        }
    }

    private static MemorySegment allocateChannels(Arena arena, int[] channels) {
        if (channels.length == 0) {
            return MemorySegment.NULL;
        }
        return arena.allocateFrom(ValueLayout.JAVA_INT, channels);
    }

    /**
     * Reads back the driver buffer descriptors created by
     * {@link #createBuffers(int[], int[], int)}. Records appear in exactly the
     * order the channels were passed: all inputs first, then all outputs.
     *
     * @return one {@link BufferInfo} per active channel, or an empty list when
     *         the shim is unavailable or the native call failed
     */
    List<BufferInfo> getBufferInfos() {
        if (!isStreamingAvailable()) {
            return List.of();
        }
        try {
            return AsioControlThread.call(() -> {
                try (Arena call = Arena.ofConfined()) {
                    // Explicit 8-byte alignment: the single-argument
                    // allocate(byteSize) is specified as byte-alignment 1, and
                    // the record's two pointer fields are 64-bit.
                    MemorySegment records = call.allocate(
                            (long) BUFFER_INFO_STRIDE * BUFFER_INFO_CAPACITY,
                            BUFFER_INFO_ALIGNMENT);
                    MemorySegment count = call.allocate(ValueLayout.JAVA_INT);
                    count.set(ValueLayout.JAVA_INT, 0, BUFFER_INFO_CAPACITY);
                    int status = (int) getBufferInfos.invokeExact(records, count);
                    if (status != SHIM_OK) {
                        return List.of();
                    }
                    int actual = Math.clamp(
                            count.get(ValueLayout.JAVA_INT, 0), 0, BUFFER_INFO_CAPACITY);
                    return decodeBufferInfos(records, actual);
                }
            });
        } catch (Throwable ignored) {
            // Missing driver state or FFM failure — an empty list makes
            // AsioBackend#open roll back cleanly.
            return List.of();
        }
    }

    /**
     * Decodes {@code count} 32-byte buffer-info records from {@code records}.
     * Package-private so the layout can be unit-tested without a driver.
     *
     * <p>The {@code _UNALIGNED} layout variants are deliberate. The aligned
     * {@code JAVA_LONG} enforces 8-byte alignment of the <em>absolute</em>
     * address, which would make this method untestable against a heap segment
     * ({@link MemorySegment#ofArray(byte[])} has maximum alignment 1) and would
     * throw {@link IllegalArgumentException} rather than decode. The caller
     * still allocates with {@link #BUFFER_INFO_ALIGNMENT} so the native writes
     * are properly aligned; this is purely about not letting the read path
     * impose an alignment the segment cannot advertise.</p>
     */
    static List<BufferInfo> decodeBufferInfos(MemorySegment records, int count) {
        Objects.requireNonNull(records, "records must not be null");
        int safeCount = Math.max(0, count);
        List<BufferInfo> decoded = new ArrayList<>(safeCount);
        for (int index = 0; index < safeCount; index++) {
            long base = (long) index * BUFFER_INFO_STRIDE;
            decoded.add(new BufferInfo(
                    records.get(ValueLayout.JAVA_INT_UNALIGNED, base),
                    records.get(ValueLayout.JAVA_INT_UNALIGNED, base + 4) != 0,
                    records.get(ValueLayout.JAVA_INT_UNALIGNED, base + 8),
                    records.get(ValueLayout.JAVA_LONG_UNALIGNED, base + 16),
                    records.get(ValueLayout.JAVA_LONG_UNALIGNED, base + 24)));
        }
        return List.copyOf(decoded);
    }

    /** Calls {@code ASIOStart}. Returns {@code true} on {@code ASE_OK}. */
    boolean start() {
        return invokeStatus(start);
    }

    /** Calls {@code ASIOStop}. Idempotent; {@code true} when not started. */
    boolean stop() {
        return invokeStatus(stop);
    }

    /** Calls {@code ASIODisposeBuffers} (stopping first if needed). Idempotent. */
    boolean disposeBuffers() {
        return invokeStatus(disposeBuffers);
    }

    private boolean invokeStatus(MethodHandle handle) {
        if (handle == null || closed) {
            return false;
        }
        try {
            int status = AsioControlThread.call(() -> (int) handle.invokeExact());
            return status == SHIM_OK;
        } catch (Throwable ignored) {
            // Best-effort: a failed teardown must never break close().
            return false;
        }
    }

    /**
     * Registers the FFM upcall stub the shim invokes from every
     * {@code bufferSwitch}.
     *
     * @param upcallStub the stub produced by {@link AsioBufferSwitchShim#upcallStub()};
     *                   must not be null
     * @return {@code true} when the callback was installed
     */
    boolean installBufferSwitchCallback(MemorySegment upcallStub) {
        Objects.requireNonNull(upcallStub, "upcallStub must not be null");
        if (!isStreamingAvailable() || upcallStub.equals(MemorySegment.NULL)) {
            return false;
        }
        try {
            return AsioControlThread.call(() -> {
                installCallback.invokeExact(upcallStub);
                return true;
            });
        } catch (Throwable ignored) {
            // Install failed — the caller rolls the open back.
            return false;
        }
    }

    /**
     * Clears the registered buffer-switch upcall and reports whether that
     * provably happened. Must be called before the
     * {@link AsioBufferSwitchShim}'s arena is freed, or a late callback would
     * jump into a released stub. Best-effort and idempotent; never throws.
     *
     * <p><strong>The caller may free the upcall stub's arena only when this
     * returns {@code true}</strong>, and must read THIS value rather than an
     * {@link AsioControlThread#isQuiesced()} sample taken around the call. A
     * sample answers a different question — whether the control thread was free
     * of abandoned calls at the instant it was read — and it is wrong in both
     * directions here. Taken BEFORE, it cannot see this call be skipped,
     * refused, time out, be interrupted, or fail at the FFM boundary. Taken
     * AFTER, it reads {@code true} again the moment an abandoned call that
     * caused a refusal finally returns, reporting a clean teardown for an
     * uninstall that never ran. Only this call's own outcome distinguishes
     * "the shim no longer holds the stub's address" from "it may still".</p>
     *
     * <p><strong>The vendor driver is not a party to this call</strong>
     * (story 316 review, round 4 — earlier wording here blamed it, wrongly).
     * The native {@code uninstallAsioBufferSwitchCallback} stores
     * {@code nullptr} into the shim's own buffer-switch callback pointer and
     * then drains in-flight callbacks; it takes no driver lock, enters no ASIO
     * SDK entry point, and returns {@code void}. The driver's
     * {@code ASIOCallbacks} table is wired to the shim's exported
     * {@code asioshim_bufferSwitchTrampoline}, never to the JVM stub — it is
     * that trampoline's read of the pointer this call nulls that reaches the
     * stub. The driver therefore has no channel through which to refuse this
     * call or to throw out of it, and nothing written here or in the callers
     * may suggest that it has.</p>
     *
     * @return {@code true} when the shim's registered buffer-switch callback
     *         is provably no longer the stub's address, which covers two
     *         cases. First, the
     *         {@code uninstallAsioBufferSwitchCallback} symbol never resolved:
     *         {@link #isStreamingAvailable()} requires
     *         {@code uninstallCallback != null}, and
     *         {@link #installBufferSwitchCallback(MemorySegment)} answers
     *         {@code false} without invoking anything when streaming is
     *         unavailable, so such a shim can never have installed a stub in
     *         the first place. Second, the downcall ran and returned normally,
     *         having nulled the pointer and then waited on the native shim's
     *         bounded in-flight callback barrier for any callback that had
     *         already read it.
     *         <p>{@code false} in five cases, none of which this method can
     *         tell apart and none of which involve the driver: this shim is
     *         already closed, so the call is not made at all — the same answer
     *         {@code invokeStatus} gives for a closed shim;
     *         {@link AsioControlThread} refuses it on arrival because an
     *         earlier downcall outlived its budget and is still executing;
     *         this call's own budget expires, leaving it withdrawn before it
     *         ran or abandoned after it started; the calling thread is
     *         interrupted, which {@code AsioControlThread.call} re-interrupts
     *         and rethrows straight into the {@code catch} below; or the FFM
     *         downcall itself fails — {@link #close()} racing it and closing
     *         the arena that backs the handle, for instance. The caller must
     *         then retain the stub's arena.</p>
     */
    boolean uninstallBufferSwitchCallback() {
        if (uninstallCallback == null) {
            // No symbol means isStreamingAvailable() is false, which means
            // installBufferSwitchCallback() answered false without ever
            // installing anything: no stub address was ever registered, so
            // the caller's full release is safe.
            return true;
        }
        if (closed) {
            // The handles are no longer usable, so the call is not made at
            // all. A stub installed before the close is therefore still the
            // shim's registered callback: report failure.
            return false;
        }
        try {
            AsioControlThread.call(() -> {
                uninstallCallback.invokeExact();
                return null;
            });
            return true;
        } catch (Throwable ignored) {
            // Best-effort: never throw from the teardown path. The failure is
            // reported through the return value instead, and the caller keeps
            // the upcall stub mapped rather than freeing an arena the shim's
            // callback pointer may still hold.
            return false;
        }
    }

    /**
     * Releases the FFM arena that owns this shim's method handles. Idempotent.
     *
     * <p>This deliberately performs <em>no</em> driver teardown. The caller
     * owns the story-mandated ordering — {@code stop()} &rarr;
     * {@code disposeBuffers()} &rarr;
     * {@link #uninstallBufferSwitchCallback()} &rarr; close the
     * {@link AsioBufferSwitchShim} (freeing its upcall arena strictly after the
     * uninstall) &rarr; {@code close()} — and
     * {@link AsioBackend#close()} and {@code rollbackOpen(...)} both do exactly
     * that. Repeating the teardown here would make the headline ordering test
     * assert a call sequence production does not have.</p>
     *
     * <p>For a caller that skips the explicit teardown, the native
     * {@code asioshim_unloadDriver} is the backstop: it stops and disposes the
     * driver's buffers before {@code ASIOExit}, so the driver is never left
     * streaming.</p>
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            AsioControlThread.call(() -> {
                arena.close();
                return null;
            });
        } catch (Throwable ignored) {
            // Already closed elsewhere — close() stays idempotent.
        }
    }

    /**
     * One active channel's driver buffer descriptor, decoded from the
     * 32-byte {@code asioshim_getBufferInfos} record.
     *
     * @param channel    driver channel index
     * @param input      {@code true} for an input channel, {@code false} for output
     * @param sampleType the driver's {@code ASIOSampleType} for this channel
     * @param buffer0    address of {@code ASIOBufferInfo.buffers[0]}
     * @param buffer1    address of {@code ASIOBufferInfo.buffers[1]}
     */
    record BufferInfo(int channel, boolean input, int sampleType,
                      long buffer0, long buffer1) {
        BufferInfo {
            if (channel < 0) {
                throw new IllegalArgumentException(
                        "channel must not be negative: " + channel);
            }
        }
    }
}
