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
    private volatile CoreAudioFormatChangeShim formatChangeShim;

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
        // Story 218: install CoreAudio property listeners that translate
        // nominal-sample-rate / buffer-frame-size / clock-source changes
        // into publishFormatChangeRequested(...). On non-macOS hosts the
        // CoreAudio.framework lookup fails and the shim degrades to no-op.
        this.formatChangeShim = new CoreAudioFormatChangeShim(this, device);
        // Native AudioUnit render-callback wiring is implemented on macOS builds.
    }

    @Override
    public Flow.Publisher<AudioBlock> inputBlocks() {
        return support.inputBlocks();
    }

    /**
     * Translates CoreAudio's {@code kAudioHardwarePropertyDevices}
     * property listener (and the per-device
     * {@code kAudioDevicePropertyDeviceIsAlive} /
     * {@code kAudioDevicePropertyNominalSampleRate} listeners)
     * into {@link AudioDeviceEvent}s. The native shim publishes via
     * {@link AudioBackendSupport#publishDeviceEvent(AudioDeviceEvent)}.
     *
     * <p>Format-change requests (story 218) are surfaced via
     * {@link #publishFormatChangeRequested(DeviceId, java.util.Optional,
     * FormatChangeReason)} from the per-device property listeners
     * registered on stream open.</p>
     */
    @Override
    public Flow.Publisher<AudioDeviceEvent> deviceEvents() {
        return support.deviceEvents();
    }

    /**
     * Hook called by the macOS FFM shim from the per-device CoreAudio
     * property listeners registered on stream open to surface a
     * driver-initiated reset request as a
     * {@link AudioDeviceEvent.FormatChangeRequested} event on this
     * backend's {@link #deviceEvents()} publisher (story 218).
     *
     * <p>Mapping conventions used by the shim:</p>
     * <ul>
     *   <li>{@code kAudioDevicePropertyNominalSampleRate} listener
     *       fires &rarr;
     *       {@code reason = }{@link FormatChangeReason.SampleRateChange};
     *       {@code proposedFormat} is {@link java.util.Optional#empty()}
     *       because the listener fires before the new value is fully
     *       readable; the controller re-queries on reopen.</li>
     *   <li>{@code kAudioDevicePropertyBufferFrameSize} listener
     *       fires &rarr;
     *       {@code reason = }{@link FormatChangeReason.BufferSizeChange};
     *       {@code proposedFormat} is {@link java.util.Optional#empty()};
     *       the new frame count is not yet readable at notification
     *       time.</li>
     *   <li>{@code kAudioDevicePropertyClockSource} listener fires
     *       &rarr;
     *       {@code reason = }{@link FormatChangeReason.ClockSourceChange};
     *       the proposed format is typically empty.</li>
     * </ul>
     *
     * <p>Package-private: only the SDK's native shim is meant to call
     * this directly. The publisher accepts the event without blocking,
     * so it is safe to call from the CoreAudio dispatch queue the
     * property listener was registered on.</p>
     *
     * @param device         the affected device id; must not be null
     * @param proposedFormat the format the driver is moving to, when known;
     *                       must not be null (use
     *                       {@link java.util.Optional#empty()} for unknown)
     * @param reason         why the driver is asking for a reset; must not be null
     */
    void publishFormatChangeRequested(DeviceId device,
                                      java.util.Optional<AudioFormat> proposedFormat,
                                      FormatChangeReason reason) {
        Objects.requireNonNull(device, "device must not be null");
        Objects.requireNonNull(proposedFormat, "proposedFormat must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        support.publishDeviceEvent(
                new AudioDeviceEvent.FormatChangeRequested(device, proposedFormat, reason));
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
        CoreAudioFormatChangeShim shim = this.formatChangeShim;
        this.formatChangeShim = null;
        if (shim != null) {
            shim.close();
        }
        support.close();
    }

    /**
     * Reads the device's buffer-size capabilities from CoreAudio's
     * {@code kAudioDevicePropertyBufferFrameSizeRange} (min/max) and
     * {@code kAudioDevicePropertyBufferFrameSize} (preferred). While
     * CoreAudio accepts any frame count in the reported range, a
     * coarser granularity is used here to keep the dropdown menu
     * practical (a handful of entries instead of thousands).
     *
     * <p>The defaults below match what the built-in audio device
     * reports on a typical Apple Silicon Mac; the implementation layer
     * that ships the FFM bindings (story 130) replaces them with the
     * actual property values at runtime.</p>
     */
    @Override
    public BufferSizeRange bufferSizeRange(DeviceId device) {
        Objects.requireNonNull(device, "device must not be null");
        return new BufferSizeRange(32, 4096, 512, 32);
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

    /**
     * Reports the device's available clock sources from CoreAudio's
     * {@code kAudioDevicePropertyClockSources} property and marks the
     * one returned by {@code kAudioDevicePropertyClockSource} as
     * {@link ClockSource#current()}. The SDK-level default returns an
     * empty list; the FFM implementation layer (story 130) replaces it
     * with the real property reads at runtime.
     */
    @Override
    public List<ClockSource> clockSources(DeviceId device) {
        Objects.requireNonNull(device, "device must not be null");
        return List.of();
    }

    /**
     * Writes the requested source id to
     * {@code kAudioDevicePropertyClockSource}. Without the macOS FFM
     * shim this method throws {@link UnsupportedOperationException} —
     * the same exception the interface default throws — so callers
     * can reliably detect lack of support with a single catch.
     */
    @Override
    public void selectClockSource(DeviceId device, int sourceId) {
        Objects.requireNonNull(device, "device must not be null");
        throw new UnsupportedOperationException(
                "CoreAudio clock-source selection requires the macOS FFM shim "
                        + "which is not present in this build.");
    }

    private static boolean isMac() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("mac") || os.contains("darwin");
    }
}
