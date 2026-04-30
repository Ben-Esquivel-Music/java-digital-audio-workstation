package com.benesquivelmusic.daw.sdk.audio;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Optional;

/**
 * FFM (JEP 454) shim that bridges the four Steinberg ASIO SDK symbols
 * needed to populate the Audio Settings dialog with driver-allowed
 * buffer sizes and sample rates (story 130 / story 213). The native
 * counterpart lives under {@code daw-core/native/asio/} and is built
 * into {@code asioshim.dll} on Windows hosts that have the Steinberg
 * ASIO SDK available.
 *
 * <p>Exported native symbols (see {@code asioshim.cpp}):</p>
 * <ul>
 *   <li>{@code int asioshim_getBufferSize(int* min, int* max, int* preferred, int* granularity)}
 *       &rarr; wraps {@code ASIOGetBufferSize}; returns 1 on success (ASE_OK), 0 otherwise.</li>
 *   <li>{@code int asioshim_canSampleRate(double rate)}
 *       &rarr; wraps {@code ASIOCanSampleRate}; returns 1 if accepted.</li>
 *   <li>{@code int asioshim_getSampleRate(double* outRate)}
 *       &rarr; wraps {@code ASIOGetSampleRate}; returns 1 on success.</li>
 *   <li>{@code int asioshim_setSampleRate(double rate)}
 *       &rarr; wraps {@code ASIOSetSampleRate}; returns 1 on success.</li>
 *   <li>{@code int asioshim_openControlPanel()} (story 212)
 *       &rarr; wraps {@code ASIOControlPanel}; blocks until the modal
 *       panel is closed. Returns 1 on success, 0 if the driver does
 *       not provide a control panel ({@code ASE_NotPresent}), or a
 *       negative value for any other {@code ASIOError}. Optional —
 *       older shim builds without this symbol degrade gracefully.</li>
 * </ul>
 *
 * <p>Construction never throws: when the {@code asioshim} library is
 * absent (Linux/macOS hosts, or Windows hosts where the user did not
 * install the Steinberg ASIO SDK at build time), every accessor
 * returns {@link Optional#empty()} or {@code false}, and the calling
 * {@link AsioBackend} keeps its existing fallback behaviour. Only the
 * library lookup itself is platform-conditional; the rest of the class
 * is portable Java.</p>
 *
 * <p>FFM downcalls run on the calling thread (typically the JavaFX
 * thread when the Audio Settings dialog opens), never on the audio
 * render thread. Mid-stream rate changes route through
 * {@link AsioFormatChangeShim}'s reset path, not these capability
 * queries.</p>
 */
class AsioCapabilityShim implements AutoCloseable {

    /** {@code ASE_OK} status returned by the wrapped ASIO calls. */
    private static final int ASE_OK = 1;

    /**
     * {@code ASE_NotPresent} mapped at the native shim boundary —
     * the driver does not provide a control panel (story 212).
     */
    static final int CONTROL_PANEL_NOT_PRESENT = 0;

    private final Arena arena;
    private final boolean available;
    private final MethodHandle getBufferSize;
    private final MethodHandle canSampleRate;
    private final MethodHandle getSampleRate;
    private final MethodHandle setSampleRate;
    /**
     * Optional handle for {@code asioshim_openControlPanel} (story 212).
     * Older shim builds may not export this symbol; in that case the
     * field is {@code null} and {@link #isControlPanelAvailable()}
     * returns {@code false}, which keeps {@code AsioBackend#openControlPanel}
     * returning {@link Optional#empty()}.
     */
    private final MethodHandle openControlPanel;
    private boolean closed;

    /**
     * Loads the {@code asioshim} library and resolves the four entry
     * points. Any failure is captured silently — the resulting shim is
     * simply {@link #isAvailable() unavailable}.
     */
    AsioCapabilityShim() {
        this.arena = Arena.ofConfined();
        boolean ok = false;
        MethodHandle gbs = null;
        MethodHandle csr = null;
        MethodHandle gsr = null;
        MethodHandle ssr = null;
        MethodHandle ocp = null;
        SymbolLookup lookup = null;
        Linker linker = null;
        try {
            lookup = SymbolLookup.libraryLookup("asioshim", arena);
            linker = Linker.nativeLinker();
            gbs = linker.downcallHandle(
                    lookup.find("asioshim_getBufferSize").orElseThrow(
                            () -> new UnsatisfiedLinkError("asioshim_getBufferSize")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            csr = linker.downcallHandle(
                    lookup.find("asioshim_canSampleRate").orElseThrow(
                            () -> new UnsatisfiedLinkError("asioshim_canSampleRate")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_DOUBLE));
            gsr = linker.downcallHandle(
                    lookup.find("asioshim_getSampleRate").orElseThrow(
                            () -> new UnsatisfiedLinkError("asioshim_getSampleRate")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            ssr = linker.downcallHandle(
                    lookup.find("asioshim_setSampleRate").orElseThrow(
                            () -> new UnsatisfiedLinkError("asioshim_setSampleRate")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_DOUBLE));
            ok = true;
        } catch (IllegalArgumentException | UnsatisfiedLinkError ignored) {
            // Library or symbol absent: shim degrades to a no-op.
        } catch (Throwable ignored) {
            // ABI mismatch or any other failure: degrade to a no-op.
        }
        // Story 212: resolve asioshim_openControlPanel optionally — its
        // absence does not invalidate the four required capability
        // symbols, but its presence is what unlocks the dialog button.
        if (ok && lookup != null && linker != null) {
            try {
                ocp = linker.downcallHandle(
                        lookup.find("asioshim_openControlPanel").orElseThrow(
                                () -> new UnsatisfiedLinkError("asioshim_openControlPanel")),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT));
            } catch (IllegalArgumentException | UnsatisfiedLinkError ignored) {
                // Older shim build without the control-panel export.
            } catch (Throwable ignored) {
                // ABI mismatch or any other failure: leave handle null.
            }
        }
        this.available = ok;
        this.getBufferSize = gbs;
        this.canSampleRate = csr;
        this.getSampleRate = gsr;
        this.setSampleRate = ssr;
        this.openControlPanel = ocp;
    }

    /** Returns {@code true} when all four entry points were resolved. */
    boolean isAvailable() {
        return available && !closed;
    }

    /**
     * Calls {@code ASIOGetBufferSize(min, max, preferred, granularity)}
     * via the native shim. On success returns the four-tuple as a
     * {@link BufferSizeRange}; on failure (shim absent, native call
     * returned non-{@code ASE_OK}, validation rejected the values, or
     * any FFM error) returns {@link Optional#empty()}.
     */
    Optional<BufferSizeRange> getBufferSize() {
        if (!isAvailable()) {
            return Optional.empty();
        }
        try (Arena call = Arena.ofConfined()) {
            MemorySegment min = call.allocate(ValueLayout.JAVA_INT);
            MemorySegment max = call.allocate(ValueLayout.JAVA_INT);
            MemorySegment preferred = call.allocate(ValueLayout.JAVA_INT);
            MemorySegment granularity = call.allocate(ValueLayout.JAVA_INT);
            int rc = (int) getBufferSize.invokeExact(min, max, preferred, granularity);
            if (rc != ASE_OK) {
                return Optional.empty();
            }
            int gran = granularity.get(ValueLayout.JAVA_INT, 0);
            // ASIO uses any negative granularity as a sentinel meaning the
            // driver accepts power-of-two buffer sizes between min and max.
            // Normalize to BufferSizeRange.POWER_OF_TWO_GRANULARITY (-1)
            // so expandedSizes() / accepts() expand the correct ladder.
            int safeGran = gran < 0 ? BufferSizeRange.POWER_OF_TWO_GRANULARITY : gran;
            return Optional.of(new BufferSizeRange(
                    min.get(ValueLayout.JAVA_INT, 0),
                    max.get(ValueLayout.JAVA_INT, 0),
                    preferred.get(ValueLayout.JAVA_INT, 0),
                    safeGran));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    /**
     * Returns {@code true} iff the driver answered {@code ASE_OK} (1)
     * to {@code ASIOCanSampleRate(rate)}. Returns {@code false} when
     * the shim is unavailable or any error occurred.
     */
    boolean canSampleRate(double rate) {
        if (!isAvailable()) {
            return false;
        }
        try {
            int rc = (int) canSampleRate.invokeExact(rate);
            return rc == ASE_OK;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Returns the driver's currently configured sample rate via
     * {@code ASIOGetSampleRate} when available — convenience for the
     * controller after a driver-initiated reset (story 218).
     */
    Optional<Double> getSampleRate() {
        if (!isAvailable()) {
            return Optional.empty();
        }
        try (Arena call = Arena.ofConfined()) {
            MemorySegment out = call.allocate(ValueLayout.JAVA_DOUBLE);
            int rc = (int) getSampleRate.invokeExact(out);
            if (rc != ASE_OK) {
                return Optional.empty();
            }
            return Optional.of(out.get(ValueLayout.JAVA_DOUBLE, 0));
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    /**
     * Calls {@code ASIOSetSampleRate(rate)} via the shim. Returns
     * {@code true} on {@code ASE_OK}, {@code false} otherwise.
     */
    boolean setSampleRate(double rate) {
        if (!isAvailable()) {
            return false;
        }
        try {
            int rc = (int) setSampleRate.invokeExact(rate);
            return rc == ASE_OK;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Returns {@code true} when the {@code asioshim_openControlPanel}
     * symbol resolved at construction (story 212). The four capability
     * accessors do not depend on this symbol, so an older shim build
     * still reports {@link #isAvailable()} = true while
     * {@code isControlPanelAvailable()} returns {@code false}.
     */
    boolean isControlPanelAvailable() {
        return isAvailable() && openControlPanel != null;
    }

    /**
     * Calls {@code ASIOControlPanel()} via the shim and returns the
     * raw shim status code (story 212):
     * <ul>
     *   <li>{@code 1} — {@code ASE_OK}, panel was shown and closed normally.</li>
     *   <li>{@link #CONTROL_PANEL_NOT_PRESENT} ({@code 0}) — driver does
     *       not provide a control panel ({@code ASE_NotPresent}).</li>
     *   <li>negative — any other {@code ASIOError} (driver-side failure).</li>
     * </ul>
     *
     * <p>The native call blocks the calling thread until the user
     * closes the modal panel; callers must therefore invoke this on a
     * dedicated platform thread, never on the JavaFX thread or the
     * audio render thread.</p>
     *
     * <p>If the shim or the {@code openControlPanel} symbol is
     * unavailable, returns a generic failure (negative). FFM-level
     * exceptions are also normalised to a generic failure rather than
     * propagated, so the supervising {@link AsioBackend#openControlPanel()}
     * runnable can translate every non-OK code into a clear
     * {@link AudioBackendException}.</p>
     */
    int openControlPanel() {
        if (!isControlPanelAvailable()) {
            return -1;
        }
        try {
            return (int) openControlPanel.invokeExact();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** Releases the FFM arena. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            arena.close();
        } catch (Throwable ignored) {
            // Already closed elsewhere — idempotent.
        }
    }
}
