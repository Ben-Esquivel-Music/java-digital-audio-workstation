package com.benesquivelmusic.daw.sdk.audio;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;
import java.util.Optional;

/**
 * FFM (JEP 454) shim that translates the ASIO host-callback's
 * {@code asioMessage(long selector, long value, void* message, double* opt) -> long}
 * upcall into {@link AsioBackend#publishFormatChangeRequested(DeviceId,
 * Optional, FormatChangeReason)} invocations (story 218).
 *
 * <p>The shim is constructed by {@link AsioBackend#open(DeviceId,
 * AudioFormat, int)}. Construction always succeeds on every platform:
 * the upcall stub itself is built via {@link Linker#nativeLinker()},
 * which is available on every supported JVM. Only the optional
 * {@code asioshim} library lookup and the
 * {@code installAsioMessageCallback} downcall are platform-conditional;
 * if either is missing the shim degrades to a no-op and
 * {@link AsioBackend#publishFormatChangeRequested(DeviceId, Optional,
 * FormatChangeReason)} simply never fires.</p>
 *
 * <p>The {@link #dispatch(long, long)} entrypoint is package-private so
 * that unit tests can exercise the selector-to-reason mapping without
 * requiring an actual native ASIO driver to be installed.</p>
 */
final class AsioFormatChangeShim implements AutoCloseable {

    /** ASIO selector: a generic reset request from the driver. */
    static final int kAsioResetRequest = 3;
    /** ASIO selector: the driver wants the host to resync the device clock. */
    static final int kAsioResyncRequest = 4;
    /** ASIO selector: the driver renegotiated the buffer size to {@code value} frames. */
    static final int kAsioBufferSizeChange = 7;

    /** ASE_OK — selector handled successfully. */
    private static final long ASE_OK = 1L;
    /** ASE_NotPresent — selector unknown / unhandled. */
    private static final long ASE_NOT_PRESENT = 0L;

    /** Function descriptor for ASIO's {@code asioMessage(long, long, void*, double*) -> long}. */
    private static final FunctionDescriptor ASIO_MESSAGE =
            FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS);

    private final AsioBackend backend;
    private final AudioBackendSupport support;
    private final DeviceId device;
    private final Arena arena;
    private final MemorySegment upcallStub;
    private final boolean registered;
    private boolean closed;

    /**
     * Builds the upcall stub and (on Windows hosts where the
     * {@code asioshim} library is present) installs it via the
     * shim's {@code installAsioMessageCallback} entrypoint.
     *
     * @param backend the owning backend; must not be null
     * @param support the support holding the currently-opened
     *                {@link AudioFormat}; must not be null. The shim
     *                reads {@link AudioBackendSupport#format()} on
     *                {@code kAsioBufferSizeChange} so that the proposed
     *                format carries the previously opened sample rate /
     *                channels / bit depth
     * @param device  the device id this shim is bound to; must not be null
     */
    AsioFormatChangeShim(AsioBackend backend, AudioBackendSupport support, DeviceId device) {
        this.backend = Objects.requireNonNull(backend, "backend must not be null");
        this.support = Objects.requireNonNull(support, "support must not be null");
        this.device = Objects.requireNonNull(device, "device must not be null");
        this.arena = Arena.ofConfined();
        this.upcallStub = buildUpcallStub();
        this.registered = tryRegister();
    }

    private MemorySegment buildUpcallStub() {
        try {
            MethodHandle handle = MethodHandles.lookup().findVirtual(
                    AsioFormatChangeShim.class,
                    "asioMessageUpcall",
                    MethodType.methodType(long.class,
                            long.class, long.class,
                            MemorySegment.class, MemorySegment.class))
                    .bindTo(this);
            return Linker.nativeLinker().upcallStub(handle, ASIO_MESSAGE, arena);
        } catch (Throwable t) {
            // Linker/method handle wiring should never fail on a supported
            // JVM, but if it does we degrade to a no-op rather than break
            // open() — story 218 explicitly requires graceful degradation.
            return MemorySegment.NULL;
        }
    }

    private boolean tryRegister() {
        if (upcallStub.equals(MemorySegment.NULL)) {
            return false;
        }
        try {
            SymbolLookup lookup = SymbolLookup.libraryLookup("asioshim", arena);
            MemorySegment install = lookup.find("installAsioMessageCallback")
                    .orElseThrow(() -> new UnsatisfiedLinkError(
                            "installAsioMessageCallback not found"));
            MethodHandle handle = Linker.nativeLinker().downcallHandle(
                    install, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            handle.invoke(upcallStub);
            return true;
        } catch (IllegalArgumentException | UnsatisfiedLinkError ignored) {
            // Library not present on this host — no-op.
            return false;
        } catch (Throwable ignored) {
            // Any other failure (missing symbol, ABI mismatch) — no-op.
            return false;
        }
    }

    /**
     * Method handle target for the FFM upcall stub. Bound to {@code this}
     * via {@link MethodHandles#bindTo(Object)}; the JVM calls it from the
     * ASIO host-callback thread when the driver fires
     * {@code asioMessage}.
     */
    @SuppressWarnings("unused") // invoked reflectively via the upcall stub
    private long asioMessageUpcall(long selector, long value,
                                   MemorySegment message, MemorySegment opt) {
        try {
            return dispatch(selector, value);
        } catch (Throwable t) {
            // Never let an exception propagate back into native code.
            return ASE_NOT_PRESENT;
        }
    }

    /**
     * Translates a single ASIO host-callback into a
     * {@link AudioDeviceEvent.FormatChangeRequested} event on the
     * backend's {@link AudioBackend#deviceEvents()} publisher.
     *
     * <p>Selector mapping (story 218):</p>
     * <ul>
     *   <li>{@link #kAsioBufferSizeChange} &rarr;
     *       {@link FormatChangeReason.BufferSizeChange}. The proposed
     *       format carries the previously opened sample rate / channels
     *       / bit depth via {@link AudioBackendSupport#format()};
     *       {@code value} is the new frame count but
     *       {@link AudioFormat} does not include buffer size, so the
     *       new frame count is implicit in the event semantics.</li>
     *   <li>{@link #kAsioResyncRequest} &rarr;
     *       {@link FormatChangeReason.ClockSourceChange}; proposed
     *       format is empty.</li>
     *   <li>{@link #kAsioResetRequest} &rarr;
     *       {@link FormatChangeReason.DriverReset}; proposed format
     *       is empty. Sample-rate-driven resets cannot be distinguished
     *       here without an additional {@code ASIOGetSampleRate()}
     *       downcall — they are reported as {@code DriverReset} and the
     *       controller re-queries device capabilities on reopen.</li>
     * </ul>
     *
     * <p>Package-private so that unit tests can drive each selector
     * without needing a real ASIO driver loaded.</p>
     *
     * @param selector the ASIO selector code
     * @param value    selector-specific payload (e.g. new buffer size in frames)
     * @return {@link #ASE_OK} for known selectors,
     *         {@link #ASE_NOT_PRESENT} otherwise
     */
    long dispatch(long selector, long value) {
        switch ((int) selector) {
            case kAsioBufferSizeChange: {
                AudioFormat current = support.format();
                Optional<AudioFormat> proposed = current == null
                        ? Optional.empty()
                        : Optional.of(new AudioFormat(
                                current.sampleRate(),
                                current.channels(),
                                current.bitDepth()));
                backend.publishFormatChangeRequested(
                        device, proposed, new FormatChangeReason.BufferSizeChange());
                return ASE_OK;
            }
            case kAsioResyncRequest:
                backend.publishFormatChangeRequested(
                        device, Optional.empty(),
                        new FormatChangeReason.ClockSourceChange());
                return ASE_OK;
            case kAsioResetRequest:
                backend.publishFormatChangeRequested(
                        device, Optional.empty(),
                        new FormatChangeReason.DriverReset());
                return ASE_OK;
            default:
                return ASE_NOT_PRESENT;
        }
    }

    /**
     * Returns {@code true} if the shim successfully registered itself
     * with the {@code asioshim} native library. Tests use this to verify
     * registration is correctly skipped on hosts that lack the library.
     *
     * @return true iff {@code installAsioMessageCallback} was invoked
     */
    boolean isRegistered() {
        return registered;
    }

    /**
     * Returns the address of the upcall stub bound to ASIO's
     * {@code asioMessage} entrypoint. Exposed for testing only.
     *
     * @return the upcall stub's address (never null;
     *         {@link MemorySegment#NULL} if construction failed)
     */
    MemorySegment upcallStub() {
        return upcallStub;
    }

    /**
     * Unregisters the upcall (when registration succeeded) and frees the
     * upcall stub. Idempotent — safe to call multiple times.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (registered) {
            try {
                SymbolLookup lookup = SymbolLookup.libraryLookup("asioshim", arena);
                lookup.find("uninstallAsioMessageCallback").ifPresent(symbol -> {
                    try {
                        Linker.nativeLinker().downcallHandle(
                                symbol, FunctionDescriptor.ofVoid()).invoke();
                    } catch (Throwable ignored) {
                        // Best-effort: never throw from close().
                    }
                });
            } catch (Throwable ignored) {
                // Library disappeared between open() and close() — ignore.
            }
        }
        try {
            arena.close();
        } catch (Throwable ignored) {
            // Already closed by something else — idempotent.
        }
    }
}
