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
     * Do not advertise control-panel support until the native ASIO
     * control-panel bridge is actually wired. Returning an empty
     * {@link Optional} allows the UI to disable the action instead of
     * exposing a button that can only fail at runtime.
     *
     * <p>When the FFM downcall to {@code ASIOControlPanel()} is wired
     * by the implementation layer that ships the Steinberg ASIO SDK
     * shim (see {@code daw-core/native/asio/}), this method should
     * return {@code Optional.of(this::invokeAsioControlPanel)}.</p>
     */
    @Override
    public Optional<Runnable> openControlPanel() {
        return Optional.empty();
    }

    /**
     * Placeholder for a future bridge to the FFM-bound
     * {@code ASIOControlPanel()} symbol from the native shim under
     * {@code daw-core/native/asio/}. This backend does not currently
     * expose the control panel.
     */
    private void invokeAsioControlPanel() {
        throw new AudioBackendException(
                "ASIO control panel is not implemented in this build.");
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
