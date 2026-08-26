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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * FFM (JEP 454) shim that translates the ASIO host-callback's
 * {@code asioMessage(int32 selector, int32 value, void* message, double* opt) -> int32}
 * upcall into {@link AsioBackend#publishFormatChangeRequested(DeviceId,
 * Optional, FormatChangeReason)} invocations (story 218).
 *
 * <p>The shim is constructed by {@link AsioBackend#open(DeviceId,
 * AudioFormat, int)}. Construction always succeeds on every platform:
 * the upcall stub itself is built via {@link Linker#nativeLinker()},
 * which is available on every supported JVM. Only the optional
 * {@code asioshim} library lookup and the
 * {@code installAsioMessageCallback} /
 * {@code uninstallAsioMessageCallback} downcall handles — both resolved
 * once, at construction — are platform-conditional; if either is
 * missing the shim degrades to a no-op, never installs anything, and
 * {@link AsioBackend#publishFormatChangeRequested(DeviceId, Optional,
 * FormatChangeReason)} simply never fires. Requiring the uninstall
 * handle <em>before</em> installing mirrors
 * {@link AsioStreamingShim#isStreamingAvailable()}: a shim that
 * registered a stub it could never unregister could only ever end with
 * the stub's arena retained.</p>
 *
 * <p>{@link #close()} frees the upcall stub's arena only when it can
 * prove no installed native pointer can still be — or later become —
 * the stub's address; every unconfirmed teardown retains the arena for
 * the life of the process instead. See {@link #close()} for the full
 * contract.</p>
 *
 * <p>The {@link #dispatch(long, long)} entrypoint is package-private so
 * that unit tests can exercise the selector-to-reason mapping without
 * requiring an actual native ASIO driver to be installed.</p>
 */
final class AsioFormatChangeShim implements AutoCloseable {

    private static final Logger LOG =
            Logger.getLogger(AsioFormatChangeShim.class.getName());

    /**
     * ASIO selector: "do you handle selector {@code value}?" — the driver's
     * capability handshake, answered from {@link #handlesSelector(int)}.
     */
    static final int kAsioSelectorSupported = 1;
    /** ASIO selector: which ASIO engine version the host implements. */
    static final int kAsioEngineVersion = 2;
    /** ASIO selector: a generic reset request from the driver. */
    static final int kAsioResetRequest = 3;
    /** ASIO selector: the driver wants the host to resync the device clock. */
    static final int kAsioResyncRequest = 4;
    /** ASIO selector: does the host implement {@code bufferSwitchTimeInfo}? */
    static final int kAsioSupportsTimeInfo = 5;
    /** ASIO selector: the driver's reported latencies changed. */
    static final int kAsioLatenciesChanged = 6;
    /** ASIO selector: the driver renegotiated the buffer size to {@code value} frames. */
    static final int kAsioBufferSizeChange = 7;

    /** ASE_OK — selector handled successfully. */
    private static final int ASE_OK = 1;
    /** ASE_NotPresent — selector unknown / unhandled. */
    private static final int ASE_NOT_PRESENT = 0;

    /**
     * ASIO 2.0. Answering {@code kAsioEngineVersion} with 0 makes drivers fall
     * back to ASIO 1.0 behaviour (no {@code bufferSwitchTimeInfo}, no sample
     * position), so the host must report 2.
     */
    private static final int ASIO_ENGINE_VERSION = 2;

    /**
     * Windows ASIO callback descriptor. The SDK uses C {@code long}, which is
     * fixed at 32 bits on Win64 and therefore maps to {@code JAVA_INT}, not
     * Java's 64-bit {@code long}.
     */
    private static final FunctionDescriptor ASIO_MESSAGE =
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS);

    /**
     * The two mutually exclusive endings {@link #close()} can take. The
     * first close latches {@link #closed} and then decides; a second close
     * is a no-op, so whichever ending ran first decides the arena's fate.
     */
    enum CloseEnding {
        /**
         * The arena was proven safe to free — no install downcall was ever
         * started, or the uninstall downcall was CONFIRMED — and its
         * release was attempted. {@link #releaseArena()} is best-effort,
         * so the arena may still be mapped afterwards; that residue is a
         * bounded leak and never a jump target, because this ending is
         * only reached when the stub is provably not (and can never again
         * become) the shim's registered callback pointer.
         */
        RELEASED,
        /**
         * The uninstall could not be CONFIRMED while an install downcall
         * had been started; the arena is retained for the life of the
         * process.
         */
        RETAINED
    }

    private final AsioBackend backend;
    private final AudioBackendSupport support;
    private final DeviceId device;
    private final Arena arena;
    private final MemorySegment upcallStub;
    private final MethodHandle installHandle;
    private final MethodHandle uninstallHandle;
    private final boolean registered;

    /**
     * Whether the {@code installAsioMessageCallback} downcall was ever
     * handed to {@link AsioControlThread}. Set immediately BEFORE the call
     * is submitted, because {@code AsioControlThread.call} throwing is not
     * proof the install never ran: a budget expiry abandons a call IN
     * FLIGHT, and the install may still complete natively afterwards.
     * {@link #close()} reads this — not {@link #registered} — to decide
     * whether the confirmed-uninstall protocol is required;
     * {@code registered == false} with this {@code true} is exactly the
     * "may still be installed" doubt that must retain the arena.
     */
    private boolean installDowncallStarted;

    /**
     * Latched by the first {@link #close()} before anything else happens.
     * Volatile because the driver's callback thread reads it at
     * {@link #asioMessageUpcall(int, int, MemorySegment, MemorySegment)}
     * entry while a lifecycle thread writes it.
     */
    private volatile boolean closed;

    /** Which ending the first {@link #close()} took; null until closed. */
    private CloseEnding closeEnding;

    /**
     * Test seam: forces {@link #confirmUninstall()} to answer unconfirmed
     * without invoking anything. Production-inert — nothing in main code
     * ever sets it.
     */
    private boolean forceUnconfirmedUninstall;

    /**
     * Builds the upcall stub, resolves the install / uninstall downcall
     * handles once, and (on Windows hosts where the {@code asioshim}
     * library is present) installs the stub via the shim's
     * {@code installAsioMessageCallback} entrypoint. Resolving the
     * uninstall handle here — rather than re-looking it up inside
     * {@link #close()} — removes the "library disappeared between open()
     * and close()" hole: a handle held from construction stays invocable
     * for the arena's lifetime.
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
        // The driver invokes this upcall from its own callback thread.
        this.arena = Arena.ofShared();
        this.upcallStub = buildUpcallStub();
        MethodHandle install = null;
        MethodHandle uninstall = null;
        try {
            SymbolLookup lookup = SymbolLookup.libraryLookup("asioshim", arena);
            Linker linker = Linker.nativeLinker();
            install = lookup.find("installAsioMessageCallback")
                    .map(symbol -> linker.downcallHandle(
                            symbol, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)))
                    .orElse(null);
            uninstall = lookup.find("uninstallAsioMessageCallback")
                    .map(symbol -> linker.downcallHandle(
                            symbol, FunctionDescriptor.ofVoid()))
                    .orElse(null);
        } catch (IllegalArgumentException | UnsatisfiedLinkError ignored) {
            // Library not present on this host — no-op shim.
        } catch (Throwable ignored) {
            // Native access disabled or ABI mismatch — no-op shim.
        }
        this.installHandle = install;
        this.uninstallHandle = uninstall;
        this.registered = tryRegister();
    }

    private MemorySegment buildUpcallStub() {
        try {
            MethodHandle handle = MethodHandles.lookup().findVirtual(
                    AsioFormatChangeShim.class,
                    "asioMessageUpcall",
                    MethodType.methodType(int.class,
                            int.class, int.class,
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

    /**
     * Installs the upcall stub, but only when this shim can also prove it
     * will be able to uninstall it later: {@link #uninstallHandle} must have
     * resolved at construction, mirroring
     * {@link AsioStreamingShim#isStreamingAvailable()}'s "no uninstall
     * symbol — nothing is ever installed" rule. A stub registered by a shim
     * that cannot unregister it could only ever end with the arena retained,
     * so that hole is closed before anything is registered.
     *
     * @return {@code true} when the install downcall returned normally
     *         reporting success — NOT merely "the install was attempted";
     *         see {@link #installDowncallStarted} for the distinction
     *         {@link #close()} depends on
     */
    private boolean tryRegister() {
        if (upcallStub.equals(MemorySegment.NULL)
                || installHandle == null || uninstallHandle == null) {
            return false;
        }
        try {
            // Set BEFORE the submit: a throw below is not proof the install
            // never ran (see the field javadoc), so from this point on
            // close() must run the confirmed-uninstall protocol.
            installDowncallStarted = true;
            return AsioControlThread.call(() -> {
                installHandle.invokeExact(upcallStub);
                return true;
            });
        } catch (Throwable ignored) {
            // The downcall may already have started and may still complete
            // natively; installDowncallStarted stays true so close() runs
            // the confirmed-uninstall protocol anyway.
            return false;
        }
    }

    /**
     * Method handle target for the FFM upcall stub. Bound to {@code this}
     * via {@link MethodHandles#bindTo(Object)}; the JVM calls it from the
     * ASIO host-callback thread when the driver fires
     * {@code asioMessage}.
     *
     * <p>Entry is gated on the {@link #closed} latch. On the
     * {@link CloseEnding#RETAINED} ending the shim's installed native
     * pointer may still be this stub's address, so a driver reset can
     * legitimately land here after {@link #close()}. That is harmless, but
     * only while the stub is still mapped — which is exactly what retaining
     * the arena guarantees — and this gate is what makes it harmless in the
     * other direction too: the late callback answers
     * {@code ASE_NotPresent} and touches neither the backend nor the
     * publisher a teardown may already have closed. The gate lives here, at
     * the stub's entry, rather than inside {@link #dispatch(long, long)},
     * so tests keep driving {@code dispatch} directly; package-private so
     * they can drive this entry too.</p>
     */
    int asioMessageUpcall(int selector, int value,
                          MemorySegment message, MemorySegment opt) {
        if (closed) {
            return ASE_NOT_PRESENT;
        }
        try {
            return (int) dispatch(selector, value);
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
     *       {@code value} is the new frame count and is carried as
     *       {@link FormatChangeReason.BufferSizeChange#newBufferFrames()}
     *       so consumers can apply it during reopen.</li>
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
     * <p>Story 311 makes this shim reachable for the first time: the
     * {@code ASIOCallbacks} struct is only built by
     * {@code asioshim_createBuffers}, so drivers now also send the ASIO
     * handshake selectors this method answers:</p>
     * <ul>
     *   <li>{@link #kAsioSelectorSupported} &rarr; 1 when {@code value} names
     *       a selector this shim handles, 0 otherwise.</li>
     *   <li>{@link #kAsioEngineVersion} &rarr; {@value #ASIO_ENGINE_VERSION}.</li>
     *   <li>{@link #kAsioSupportsTimeInfo} &rarr; 1; the native shim
     *       implements {@code bufferSwitchTimeInfo}.</li>
     *   <li>{@link #kAsioLatenciesChanged} &rarr; 1 (accepted). Latency
     *       <em>reporting</em> belongs to story 217, so nothing is published.</li>
     * </ul>
     *
     * <p>{@link #kAsioBufferSizeChange} and {@link #kAsioResetRequest}
     * invalidate the driver's buffers, so they quiesce the streaming bridge
     * via {@link AsioBackend#stopStreamingForDriverReset()} <em>before</em>
     * announcing the event (story 311). {@link #kAsioResyncRequest} does not
     * invalidate the buffers and is left alone.</p>
     *
     * <p>Because story 311 installs this shim <em>before</em>
     * {@code ASIOCreateBuffers} — drivers issue the handshake selectors
     * synchronously from inside that call — a
     * {@link #kAsioBufferSizeChange} can legitimately arrive while
     * {@link AudioBackendSupport#format()} is still {@code null}. The proposed
     * format is then {@link Optional#empty()}, which is exactly the
     * "format unknown" case the {@link AudioDeviceEvent.FormatChangeRequested}
     * contract already defines.</p>
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
            case kAsioSelectorSupported:
                return handlesSelector((int) value) ? ASE_OK : ASE_NOT_PRESENT;
            case kAsioEngineVersion:
                return ASIO_ENGINE_VERSION;
            case kAsioSupportsTimeInfo:
                return ASE_OK;
            case kAsioLatenciesChanged:
                return ASE_OK;
            case kAsioBufferSizeChange: {
                backend.stopStreamingForDriverReset();
                AudioFormat current = support.format();
                Optional<AudioFormat> proposed = current == null
                        ? Optional.empty()
                        : Optional.of(new AudioFormat(
                                current.sampleRate(),
                                current.channels(),
                                current.bitDepth()));
                backend.publishFormatChangeRequested(
                        device, proposed,
                        new FormatChangeReason.BufferSizeChange((int) value));
                return ASE_OK;
            }
            case kAsioResyncRequest:
                backend.publishFormatChangeRequested(
                        device, Optional.empty(),
                        new FormatChangeReason.ClockSourceChange());
                return ASE_OK;
            case kAsioResetRequest:
                backend.stopStreamingForDriverReset();
                backend.publishFormatChangeRequested(
                        device, Optional.empty(),
                        new FormatChangeReason.DriverReset());
                return ASE_OK;
            default:
                return ASE_NOT_PRESENT;
        }
    }

    /**
     * Answers ASIO's {@code kAsioSelectorSupported} handshake: the set of
     * selectors {@link #dispatch(long, long)} implements.
     */
    private static boolean handlesSelector(int selector) {
        return switch (selector) {
            case kAsioSelectorSupported, kAsioEngineVersion, kAsioResetRequest,
                 kAsioResyncRequest, kAsioSupportsTimeInfo,
                 kAsioLatenciesChanged, kAsioBufferSizeChange -> true;
            default -> false;
        };
    }

    /**
     * Returns {@code true} if the shim successfully registered itself
     * with the {@code asioshim} native library. Tests use this to verify
     * registration is correctly skipped on hosts that lack the library.
     *
     * @return true iff the {@code installAsioMessageCallback} downcall
     *         returned normally reporting success. {@code false} does NOT
     *         mean the install never ran — a budget expiry abandons a call
     *         IN FLIGHT — which is why {@link #close()} keys its teardown
     *         decision on {@link #installDowncallStarted}, never on this
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
     * Ends the shim, uninstalling the upcall when one may be installed and
     * freeing the stub's arena only when that is provably safe. Idempotent,
     * and the two endings are mutually exclusive: the first call latches
     * {@link #closed} before doing anything else and then decides, so a
     * second call — from either ending — is a no-op and can never flip the
     * arena's fate. Never throws.
     *
     * <p>The arena may be freed in exactly two cases. First, the install
     * downcall was never STARTED — the stub is {@link MemorySegment#NULL},
     * or the library / symbol lookups failed at construction so
     * {@link #tryRegister()} returned without submitting anything to
     * {@link AsioControlThread}: no stub address was ever registered,
     * nothing can later register it, and the full release is safe (the
     * mirror of {@link AsioStreamingShim#uninstallBufferSwitchCallback()}'s
     * no-symbol case). Second, the {@code uninstallAsioMessageCallback}
     * downcall was CONFIRMED — {@link AsioControlThread#call} returned
     * normally — having stored null into the shim's message-callback
     * pointer and then waited on the native shim's bounded in-flight
     * barrier for any callback that had already read it.</p>
     *
     * <p>EVERY other outcome retains the arena for the life of the process:
     * {@link AsioControlThread} refused the uninstall on arrival because an
     * earlier downcall its caller stopped waiting for is still executing;
     * the call's own budget expired, leaving it withdrawn before it ran or
     * abandoned after it started; the calling thread was interrupted; or
     * the FFM downcall itself failed. <strong>The driver is not a party to
     * this call</strong>: the native uninstall nulls a pointer and drains
     * in-flight callbacks without entering the ASIO SDK, so it has no
     * channel through which to refuse the call or to throw out of it. What
     * the unconfirmed outcomes share is the only thing that matters here —
     * the shim's registered message callback may still be this stub's
     * address, so the driver's next {@code asioMessage} (a concurrent
     * reset, say) may still reach it through
     * {@code asioshim_messageTrampoline}. Freeing the arena would turn that
     * callback into a jump into released stub memory; retaining it costs
     * one stub and one arena, bounded and static, and the {@link #closed}
     * latch makes the late callback answer {@code ASE_NotPresent} instead
     * of dispatching. The choice is logged at {@link Level#SEVERE}.</p>
     *
     * <p>{@link #registered} deliberately plays no part in the decision:
     * {@link #tryRegister()} answers {@code false} both for "never
     * submitted" and for "the control-thread call threw after the install
     * was already submitted" — and a budget expiry abandons a call IN
     * FLIGHT, so the install may still complete natively after the
     * {@code false}. {@link #installDowncallStarted} is what tells the two
     * apart; retain-on-doubt is the correct direction, because a leaked
     * stub costs memory while a freed-but-installed stub costs the
     * process.</p>
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (!installDowncallStarted) {
            closeEnding = CloseEnding.RELEASED;
            releaseArena();
            return;
        }
        if (confirmUninstall()) {
            closeEnding = CloseEnding.RELEASED;
            releaseArena();
            return;
        }
        closeEnding = CloseEnding.RETAINED;
        logRetainedUpcallStub();
    }

    /**
     * Runs the uninstall downcall and reports whether it was CONFIRMED —
     * {@link AsioControlThread#call} returned normally. Only that outcome
     * proves the shim's message-callback pointer is no longer (and can no
     * longer become) the stub's address; every throw — refusal on arrival,
     * budget expiry, interrupt, FFM failure — leaves the question open and
     * the caller must retain the arena. Never throws.
     */
    private boolean confirmUninstall() {
        if (forceUnconfirmedUninstall || uninstallHandle == null) {
            // No invocable uninstall while an install downcall was started
            // means the stub may be registered and cannot be unregistered.
            // The production constructor makes the null branch unreachable —
            // tryRegister() refuses to install without the uninstall handle
            // — but a defensive false here fails toward retention, never
            // toward release.
            return false;
        }
        try {
            AsioControlThread.call(() -> {
                uninstallHandle.invokeExact();
                return null;
            });
            return true;
        } catch (Throwable ignored) {
            // Best-effort: never throw from the teardown path. The failure
            // is reported through the return value and close() retains the
            // arena the shim's callback pointer may still hold.
            return false;
        }
    }

    /**
     * Frees the shared arena that owns the upcall stub and this shim's
     * downcall handles. Routed through {@link AsioControlThread} so it
     * cannot race a downcall bound to the same arena; best-effort so
     * {@link #close()} never throws — a failure leaves the arena mapped
     * (see the catch below and {@link CloseEnding#RELEASED}).
     */
    private void releaseArena() {
        try {
            AsioControlThread.call(() -> {
                arena.close();
                return null;
            });
        } catch (Throwable ignored) {
            // Best-effort, and NOT only "already closed": this is a BOUNDED
            // control-thread call, so it can be refused on arrival, expire
            // its budget, or be interrupted before arena.close() ever runs —
            // and the close itself can fail. In every case the arena may
            // stay mapped while closeEnding already reads RELEASED. That is
            // a bounded leak, never a hazard: close() only reaches here when
            // the stub is provably unregistered, so nothing can jump into
            // the arena, and close() must not throw from teardown.
        }
    }

    /**
     * Logs the {@link CloseEnding#RETAINED} ending. The message describes
     * the SHAPE of the failure rather than picking one cause, because
     * {@link #confirmUninstall()} cannot distinguish its outcomes and a
     * maintainer reading this line must not be sent after the wrong one; it
     * also must not blame the driver, which the uninstall never invokes.
     */
    private void logRetainedUpcallStub() {
        LOG.log(Level.SEVERE,
                "ASIO asioMessage upcall stub RETAINED (deliberately leaked) for "
                        + device.name()
                        + ": uninstalling the asioMessage callback could not be"
                        + " CONFIRMED — the call could not be made, was refused on"
                        + " arrival because an earlier ASIO call its caller stopped"
                        + " waiting for (by budget or by interrupt) was still"
                        + " executing, did not complete within its own budget, was"
                        + " interrupted, or failed at the FFM boundary. The driver is"
                        + " NOT involved either way: the uninstall nulls a callback"
                        + " pointer and drains in-flight callbacks without entering"
                        + " the ASIO SDK. What is unknown is whether that pointer is"
                        + " still the stub's address, so a driver reset may still"
                        + " reach it through asioshim_messageTrampoline. Freeing its"
                        + " arena now would turn the next asioMessage into a jump"
                        + " into released memory, so the stub and its arena are"
                        + " leaked for the life of the process instead. The closed"
                        + " latch makes such a late callback answer ASE_NotPresent"
                        + " while the stub stays mapped.");
    }

    /** Test seam: which ending {@link #close()} took; null until closed. */
    CloseEnding closeEnding() {
        return closeEnding;
    }

    /**
     * Test seam: whether the arena that owns the upcall stub is still
     * mapped — {@code true} after a {@link CloseEnding#RETAINED} close,
     * and also after a {@link CloseEnding#RELEASED} close whose
     * best-effort {@link #releaseArena()} failed.
     */
    boolean upcallArenaAlive() {
        return arena.scope().isAlive();
    }

    /**
     * Test seam: whether the install downcall was ever handed to
     * {@link AsioControlThread} (see {@link #installDowncallStarted}).
     */
    boolean installDowncallStarted() {
        return installDowncallStarted;
    }

    /**
     * Test seam: marks the install downcall as having been handed to the
     * control thread, putting {@link #close()} into the confirmed-uninstall
     * protocol on hosts where no real registration could happen.
     * Production-inert — nothing in main code calls it.
     */
    void markInstallDowncallStartedForTest() {
        installDowncallStarted = true;
    }

    /**
     * Test seam: makes {@link #close()} treat the uninstall as unconfirmed
     * without invoking anything, deterministically exercising the
     * {@link CloseEnding#RETAINED} ending on every host — including one
     * where the real library would confirm. Production-inert — nothing in
     * main code sets the flag.
     */
    void forceUnconfirmedUninstallForTest() {
        forceUnconfirmedUninstall = true;
    }
}
