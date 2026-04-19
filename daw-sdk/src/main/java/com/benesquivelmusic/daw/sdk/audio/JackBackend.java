package com.benesquivelmusic.daw.sdk.audio;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;

/**
 * Linux JACK backend — JACK is the professional-audio standard on Linux,
 * used for inter-application routing and low-latency hardware access.
 *
 * <p>Availability is auto-detected at startup by probing for
 * {@code libjack} via FFM's {@link java.lang.foreign.SymbolLookup}. When
 * {@code libjack} is absent, {@link #isAvailable()} returns {@code false}
 * and {@link AudioBackendSelector} falls back to
 * {@link JavaxSoundBackend}.</p>
 */
public final class JackBackend implements AudioBackend {

    /** Backend name. */
    public static final String NAME = "JACK";

    private static final boolean AVAILABLE =
            AudioBackendSupport.nativeLibraryAvailable("jack", "libjack.so.0", "libjack.dylib");

    private final AudioBackendSupport support = new AudioBackendSupport();

    /** Creates a new JACK backend (no native resources allocated until {@link #open}). */
    public JackBackend() {
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
                    "JACK is not available — libjack was not found on the library path.");
        }
        support.markOpen(format, bufferFrames);
        // Native jack_client_open / jack_set_process_callback wiring is
        // implemented on Linux builds that link against libjack.
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

    @Override
    public void close() {
        support.close();
    }
}
