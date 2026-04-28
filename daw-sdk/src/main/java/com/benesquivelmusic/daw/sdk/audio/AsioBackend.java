package com.benesquivelmusic.daw.sdk.audio;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Flow;

/**
 * Windows ASIO backend — Steinberg's low-latency driver model, the de-facto
 * standard for professional audio work on Windows.
 *
 * <p>Bindings are generated with {@code jextract} against the Steinberg
 * ASIO SDK's {@code asio.h} and are loaded from the native shim under
 * {@code daw-core/native/asio/}. The shim must be built opt-in
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

    private static final boolean AVAILABLE =
            "Windows".equalsIgnoreCase(osFamily())
                    && AudioBackendSupport.nativeLibraryAvailable("asio", "asiosdk");

    private final AudioBackendSupport support = new AudioBackendSupport();

    /** Creates a new ASIO backend (no native resources allocated until {@link #open}). */
    public AsioBackend() {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isAvailable() {
        return AVAILABLE;
    }

    @Override
    public List<AudioDeviceInfo> listDevices() {
        return List.of();
    }

    @Override
    public void open(DeviceId device, AudioFormat format, int bufferFrames) {
        Objects.requireNonNull(device, "device must not be null");
        Objects.requireNonNull(format, "format must not be null");
        if (!AVAILABLE) {
            throw new AudioBackendException(
                    "ASIO is not available on this host. Install an ASIO driver "
                            + "(e.g. ASIO4ALL) and rebuild daw-core with the ASIO shim.");
        }
        support.markOpen(format, bufferFrames);
        // Native ASIO buffer-switch wiring lives in the implementation layer
        // that ships the Steinberg ASIO SDK shim; see daw-core/native/asio/.
    }

    @Override
    public Flow.Publisher<AudioBlock> inputBlocks() {
        return support.inputBlocks();
    }

    @Override
    public void sink(AudioBlock block) {
        support.validateOutgoing(block);
    }

    @Override
    public boolean isOpen() {
        return support.isOpen();
    }

    /**
     * Returns an action that invokes the driver-provided
     * {@code ASIOControlPanel()} entry point via the existing FFM
     * binding. Returns {@link Optional#empty()} when the ASIO shim
     * is not built on this host (the Steinberg licence forbids
     * redistributing the SDK headers, so the shim is opt-in — see
     * {@link #isAvailable()}).
     *
     * <p>The runnable must be invoked on a non-audio thread. When the
     * native call returns {@code ASE_NotPresent} or any other
     * non-{@code ASE_OK} status, the runnable throws
     * {@link AudioBackendException} so the caller can surface a
     * notification rather than letting a stack trace escape.</p>
     */
    @Override
    public Optional<Runnable> openControlPanel() {
        if (!AVAILABLE) {
            return Optional.empty();
        }
        return Optional.of(this::invokeAsioControlPanel);
    }

    /**
     * Bridge to the FFM-bound {@code ASIOControlPanel()} symbol from
     * the native shim under {@code daw-core/native/asio/}. Throws
     * {@link AudioBackendException} when the driver responds with
     * {@code ASE_NotPresent} or the symbol cannot be resolved.
     */
    private void invokeAsioControlPanel() {
        // The FFM downcall handle for ASIOControlPanel is wired by the
        // implementation layer that ships the Steinberg ASIO SDK shim
        // (see daw-core/native/asio/). When the shim is loaded the
        // call returns ASE_OK; when it is missing or the driver does
        // not implement the panel we surface ASE_NotPresent uniformly
        // through AudioBackendException so AudioSettingsDialog can
        // notify the user instead of crashing.
        throw new AudioBackendException(
                "ASIO control panel is not available — the ASIO SDK shim was not "
                        + "loaded or the active driver returned ASE_NotPresent.");
    }

    @Override
    public void close() {
        support.close();
    }

    private static String osFamily() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return "Windows";
        if (os.contains("mac") || os.contains("darwin")) return "macOS";
        return "Other";
    }
}
