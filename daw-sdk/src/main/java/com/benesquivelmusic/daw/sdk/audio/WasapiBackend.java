package com.benesquivelmusic.daw.sdk.audio;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
    private volatile WasapiFormatChangeShim formatChangeShim;

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
        // Story 218: install the IMMNotificationClient FFM shim that
        // translates OnPropertyValueChanged(PKEY_AudioEngine_DeviceFormat)
        // into publishFormatChangeRequested(...). On non-Windows hosts
        // the Ole32 lookup fails and the shim degrades to no-op.
        this.formatChangeShim = new WasapiFormatChangeShim(this, device);
        // Native IAudioClient wiring implemented on Windows builds.
    }

    @Override
    public Flow.Publisher<AudioBlock> inputBlocks() {
        return support.inputBlocks();
    }

    /**
     * Translates WASAPI's
     * {@code IMMNotificationClient::OnDeviceStateChanged} and
     * {@code OnDefaultDeviceChanged} notifications into
     * {@link AudioDeviceEvent}s. The native shim registers an
     * {@code IMMNotificationClient} on driver open and publishes via
     * {@link AudioBackendSupport#publishDeviceEvent(AudioDeviceEvent)}.
     *
     * <p>Format-change requests (story 218) are surfaced via
     * {@link #publishFormatChangeRequested(DeviceId, java.util.Optional,
     * FormatChangeReason)} from
     * {@code IMMNotificationClient::OnPropertyValueChanged} (mix-format
     * key on the active endpoint) and from full {@code IAudioClient}
     * invalidation by the audio engine.</p>
     */
    @Override
    public Flow.Publisher<AudioDeviceEvent> deviceEvents() {
        return support.deviceEvents();
    }

    /**
     * Hook called by the Windows FFM shim from
     * {@code IMMNotificationClient::OnPropertyValueChanged} (and from
     * the {@code AUDCLNT_E_DEVICE_INVALIDATED} path inside the audio
     * engine) to surface a driver-initiated reset request as a
     * {@link AudioDeviceEvent.FormatChangeRequested} event on this
     * backend's {@link #deviceEvents()} publisher (story 218).
     *
     * <p>Mapping conventions used by the shim:</p>
     * <ul>
     *   <li>{@code OnPropertyValueChanged} for the device's mix-format
     *       property key &rarr;
     *       {@code reason = }{@link FormatChangeReason.SampleRateChange};
     *       {@code proposedFormat} is {@link java.util.Optional#empty()}
     *       because WASAPI does not surface the new format until the
     *       client is re-initialised; the controller re-queries on
     *       reopen.</li>
     *   <li>{@code IAudioClient} returning
     *       {@code AUDCLNT_E_DEVICE_INVALIDATED} on the next
     *       {@code GetBuffer} call &rarr;
     *       {@code reason = }{@link FormatChangeReason.DriverReset};
     *       the proposed format is empty since WASAPI does not surface
     *       the new format until the client is re-initialised.</li>
     * </ul>
     *
     * <p>Package-private: only the SDK's native shim is meant to call
     * this directly. The publisher accepts the event without blocking,
     * so it is safe to call from the WASAPI notification thread.</p>
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
     * Launches the Windows Sound control panel ({@code mmsys.cpl})
     * on the Recording tab — the closest WASAPI equivalent to ASIO's
     * driver control panel. Returns {@link Optional#empty()} on any
     * non-Windows host.
     *
     * <p>The runnable must be invoked on a non-audio thread. Failures
     * (missing executable, denied access) are surfaced
     * as {@link AudioBackendException} so the caller can show a
     * notification. The launched process runs asynchronously; the
     * runnable returns as soon as the process is spawned, not when
     * the user closes the control panel.</p>
     */
    @Override
    public Optional<Runnable> openControlPanel() {
        if (!AVAILABLE) {
            return Optional.empty();
        }
        return Optional.of(WasapiBackend::launchSoundControlPanel);
    }

    private static void launchSoundControlPanel() {
        try {
            // Equivalent to running "control mmsys.cpl ,1" which opens the
            // Sound control panel on the Recording tab; ",0" would be Playback.
            new ProcessBuilder("control.exe", "mmsys.cpl", ",1").start();
        } catch (java.io.IOException e) {
            throw new AudioBackendException(
                    "Could not launch Windows Sound control panel: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        WasapiFormatChangeShim shim = this.formatChangeShim;
        this.formatChangeShim = null;
        if (shim != null) {
            shim.close();
        }
        support.close();
    }

    /**
     * Reports the buffer-size range allowed by the WASAPI client mode.
     *
     * <p>WASAPI <b>shared</b> mode is fixed at the OS mixer's period
     * (typically 10&nbsp;ms ≈ 480 frames at 48 kHz) — the mixer cannot
     * be reconfigured per-application — so the singleton range is
     * returned. <b>Exclusive</b> mode lets the application pick buffer
     * sizes in granularity steps within the device's period range, so
     * a granular range is reported.</p>
     *
     * <p>The values below are conservative defaults that match what
     * {@code IAudioClient::GetDevicePeriod} returns for typical USB
     * Audio Class 2 devices; the implementation layer that ships the
     * native FFM bindings (story 130) replaces them with the actual
     * device-reported values at runtime.</p>
     */
    @Override
    public BufferSizeRange bufferSizeRange(DeviceId device) {
        Objects.requireNonNull(device, "device must not be null");
        if (exclusive) {
            // Exclusive mode: power-of-two ladder from min device period
            // (~3 ms / 144 frames at 48 kHz) up to a generous max.
            return new BufferSizeRange(64, 2048, 256, 64);
        }
        // Shared mode: fixed at the OS mixer period; ~10 ms at 48 kHz.
        return BufferSizeRange.singleton(480);
    }

    /**
     * Reports supported sample rates per WASAPI mode.
     *
     * <p><b>Shared</b> mode is fixed at the OS mixer's nominal rate
     * (typically 48 kHz) — the mixer rejects any other rate — so the
     * singleton set is returned. <b>Exclusive</b> mode probes
     * {@code IAudioClient::IsFormatSupported}; the default
     * implementation here returns the canonical set since the FFM
     * binding is wired in story 130.</p>
     */
    @Override
    public Set<Integer> supportedSampleRates(DeviceId device) {
        Objects.requireNonNull(device, "device must not be null");
        if (exclusive) {
            return Set.of(44_100, 48_000, 88_200, 96_000, 176_400, 192_000);
        }
        return Set.of(48_000);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
