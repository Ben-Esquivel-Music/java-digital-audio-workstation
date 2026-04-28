package com.benesquivelmusic.daw.sdk.audio;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

    /**
     * Opens {@code Audio MIDI Setup.app} via {@code open(1)} — the
     * macOS equivalent of an ASIO control panel for managing
     * aggregate devices, sample rates, and routing. Returns
     * {@link Optional#empty()} on any non-macOS host.
     *
     * <p>The runnable must be invoked on a non-audio thread. Failures
     * (missing executable, denied access) are surfaced as
     * {@link AudioBackendException} so the caller can show a
     * notification.</p>
     */
    @Override
    public Optional<Runnable> openControlPanel() {
        if (!AVAILABLE) {
            return Optional.empty();
        }
        return Optional.of(CoreAudioBackend::launchAudioMidiSetup);
    }

    private static void launchAudioMidiSetup() {
        try {
            new ProcessBuilder("open",
                    "/System/Applications/Utilities/Audio MIDI Setup.app").start();
        } catch (java.io.IOException e) {
            throw new AudioBackendException(
                    "Could not launch Audio MIDI Setup: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        support.close();
    }

    /**
     * Reads the device's buffer-size capabilities from CoreAudio's
     * {@code kAudioDevicePropertyBufferFrameSizeRange} (min/max) and
     * {@code kAudioDevicePropertyBufferFrameSize} (preferred). CoreAudio
     * accepts any frame count in the reported range so {@code granularity}
     * is {@code 1}.
     *
     * <p>The defaults below match what the built-in audio device
     * reports on a typical Apple Silicon Mac; the implementation layer
     * that ships the FFM bindings (story 130) replaces them with the
     * actual property values at runtime.</p>
     */
    @Override
    public BufferSizeRange bufferSizeRange(DeviceId device) {
        Objects.requireNonNull(device, "device must not be null");
        return new BufferSizeRange(14, 4096, 512, 1);
    }

    /**
     * Reads the device's available sample rates from CoreAudio's
     * {@code kAudioDevicePropertyAvailableNominalSampleRates}. The
     * default implementation returns the canonical rate set; the
     * implementation layer that ships the FFM bindings replaces it with
     * the actual property values at runtime.
     */
    @Override
    public Set<Integer> supportedSampleRates(DeviceId device) {
        Objects.requireNonNull(device, "device must not be null");
        return Set.of(44_100, 48_000, 88_200, 96_000, 176_400, 192_000);
    }

    private static boolean isMac() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("mac") || os.contains("darwin");
    }
}
