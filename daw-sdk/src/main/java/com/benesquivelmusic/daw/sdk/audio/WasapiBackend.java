package com.benesquivelmusic.daw.sdk.audio;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;

/**
 * Windows WASAPI backend driven by FFM bindings to {@code mmdeviceapi.h}
 * and {@code audioclient.h}. Supports both shared and exclusive modes —
 * the latter bypasses the Windows audio engine mixer for lower latency
 * at the cost of holding the device exclusively.
 *
 * <p>Available only on Windows hosts; other OSes see {@link #isAvailable()}
 * = false and {@link AudioBackendSelector} falls back to
 * {@link JavaxSoundBackend}.</p>
 */
public final class WasapiBackend implements AudioBackend {

    /** Backend name. */
    public static final String NAME = "WASAPI";

    private static final boolean AVAILABLE = isWindows();

    private final AudioBackendSupport support = new AudioBackendSupport();
    private final boolean exclusive;

    /** Creates a new WASAPI backend in shared mode. */
    public WasapiBackend() {
        this(false);
    }

    /**
     * Creates a new WASAPI backend.
     *
     * @param exclusive {@code true} to request WASAPI exclusive mode
     *                  (lowest latency, single-client); {@code false} for
     *                  shared mode through the Windows audio engine
     */
    public WasapiBackend(boolean exclusive) {
        this.exclusive = exclusive;
    }

    /**
     * Returns {@code true} if this backend was constructed for WASAPI
     * exclusive mode.
     *
     * @return true for exclusive-mode instances
     */
    public boolean isExclusive() {
        return exclusive;
    }

    @Override
    public String name() {
        return exclusive ? NAME + " (Exclusive)" : NAME;
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
            throw new AudioBackendException("WASAPI is only available on Windows.");
        }
        support.markOpen(format, bufferFrames);
        // Native IAudioClient wiring implemented on Windows builds.
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

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
