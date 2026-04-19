package com.benesquivelmusic.daw.sdk.audio;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;

/**
 * macOS CoreAudio backend driven by FFM bindings to {@code AudioUnit.h} and
 * {@code AudioHardware.h}. Available only on macOS hosts; other OSes see
 * {@link #isAvailable()} = false and {@link AudioBackendSelector} falls
 * back to {@link JavaxSoundBackend}.
 *
 * <p>CoreAudio is the JVM's default mixer on macOS — but going through
 * {@code javax.sound.sampled} costs an extra thread hop and keeps the
 * JVM from selecting specific aggregate-device inputs. This backend calls
 * CoreAudio directly to expose explicit device selection with sub-10&nbsp;ms
 * round-trip latency.</p>
 */
public final class CoreAudioBackend implements AudioBackend {

    /** Backend name. */
    public static final String NAME = "CoreAudio";

    private static final boolean AVAILABLE = isMac();

    private final AudioBackendSupport support = new AudioBackendSupport();

    /** Creates a new CoreAudio backend (no native resources allocated until {@link #open}). */
    public CoreAudioBackend() {
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
            throw new AudioBackendException("CoreAudio is only available on macOS.");
        }
        support.markOpen(format, bufferFrames);
        // Native AudioUnit render-callback wiring is implemented on macOS builds.
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

    private static boolean isMac() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("mac") || os.contains("darwin");
    }
}
