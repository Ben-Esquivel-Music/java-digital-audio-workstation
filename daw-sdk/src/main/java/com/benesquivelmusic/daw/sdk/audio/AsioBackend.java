package com.benesquivelmusic.daw.sdk.audio;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
    private volatile AsioFormatChangeShim formatChangeShim;

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
        // Story 218: install the FFM upcall that translates ASIO's
        // asioMessage host-callback into publishFormatChangeRequested(...).
        // Construction always succeeds; the actual native registration is
        // a no-op if the asioshim library is not present on this host.
        this.formatChangeShim = new AsioFormatChangeShim(this, support, device);
        // Native ASIO buffer-switch wiring lives in the implementation layer
        // that ships the Steinberg ASIO SDK shim; see daw-core/native/asio/.
    }

    @Override
    public Flow.Publisher<AudioBlock> inputBlocks() {
        return support.inputBlocks();
    }

    /**
     * Exposes {@link AudioDeviceEvent}s derived from ASIO's
     * {@code kAsioResetRequest} (driver-initiated drop),
     * {@code kAsioBufferSizeChange}, and {@code kAsioResyncRequest}
     * callbacks installed on driver open.
     *
     * <p>The native shim translates those driver notifications into
     * {@link AudioDeviceEvent}s; delivery semantics are defined by the
     * {@link AudioBackend#deviceEvents()} contract. Format-change
     * requests in particular (story 218) are surfaced as
     * {@link AudioDeviceEvent.FormatChangeRequested} via
     * {@link #publishFormatChangeRequested(DeviceId, java.util.Optional,
     * FormatChangeReason)} from the native callback running on the
     * ASIO host-callback thread; see that method's Javadoc for the
     * exact mapping from each ASIO callback to a
     * {@link FormatChangeReason}.</p>
     */
    @Override
    public Flow.Publisher<AudioDeviceEvent> deviceEvents() {
        return support.deviceEvents();
    }

    /**
     * Hook called by the native ASIO host-callback shim under
     * {@code daw-core/native/asio/} to surface a driver-initiated
     * reset request as a {@link AudioDeviceEvent.FormatChangeRequested}
     * event on this backend's {@link #deviceEvents()} publisher.
     *
     * <p>Mapping conventions used by the shim:</p>
     * <ul>
     *   <li>{@code kAsioBufferSizeChange(newFrames)} &rarr;
     *       {@code reason = }{@link FormatChangeReason.BufferSizeChange};
     *       {@code proposedFormat} can only carry the previously opened
     *       sample rate / channel count / bit depth, because
     *       {@code bufferFrames} is negotiated separately via
     *       {@link AudioBackend#open(DeviceId, AudioFormat, int)} and is
     *       not part of {@link AudioFormat}. The new frame count is
     *       carried as
     *       {@link FormatChangeReason.BufferSizeChange#newBufferFrames()}.</li>
     *   <li>{@code kAsioResetRequest} after a successful
     *       {@code ASIOSetSampleRate(newRate)} &rarr;
     *       {@code reason = }{@link FormatChangeReason.SampleRateChange};
     *       the new format carries the new sample rate.</li>
     *   <li>{@code kAsioResyncRequest} &rarr;
     *       {@code reason = }{@link FormatChangeReason.ClockSourceChange};
     *       the proposed format is typically empty since the rate /
     *       buffer size do not necessarily change.</li>
     *   <li>any other {@code kAsioResetRequest} (USB streaming-mode
     *       change, USB hub cycle, vendor utility "reset") &rarr;
     *       {@code reason = }{@link FormatChangeReason.DriverReset}.</li>
     * </ul>
     *
     * <p>Package-private: only the SDK's native shim is meant to call
     * this directly. The publisher accepts the event without blocking
     * (the underlying {@code SubmissionPublisher.offer(...)} drops
     * under back-pressure rather than stalling), so it is safe to call
     * from the ASIO host-callback thread.</p>
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
        AsioFormatChangeShim shim = this.formatChangeShim;
        this.formatChangeShim = null;
        if (shim != null) {
            shim.close();
        }
        support.close();
    }

    /**
     * Reports the buffer sizes the ASIO driver accepts via
     * {@code ASIOGetBufferSize(min, max, preferred, granularity)} —
     * the canonical four-tuple that motivated the API in
     * {@link BufferSizeRange}. Multi-channel USB drivers commonly
     * report non-power-of-two granularity (96, 192, 288, …) which the
     * dropdown must honour exactly.
     *
     * <p>The defaults below mirror the values RME and Focusrite USB
     * drivers report for a typical 96-frame minimum; the FFM
     * implementation layer (story 130) replaces them with the actual
     * driver-reported values at runtime.</p>
     */
    @Override
    public BufferSizeRange bufferSizeRange(DeviceId device) {
        Objects.requireNonNull(device, "device must not be null");
        return new BufferSizeRange(64, 2048, 256, 64);
    }

    /**
     * Reports the sample rates the ASIO driver accepts. The FFM
     * implementation layer (story 130) probes
     * {@code ASIOCanSampleRate} across the canonical rate list and
     * keeps only the rates the driver returns {@code ASE_OK} for; the
     * default returns the canonical set so the dialog still shows the
     * historical menu when the native shim is absent.
     */
    @Override
    public Set<Integer> supportedSampleRates(DeviceId device) {
        Objects.requireNonNull(device, "device must not be null");
        return Set.of(44_100, 48_000, 88_200, 96_000, 176_400, 192_000);
    }

    /**
     * Reports the hardware clock sources the ASIO driver exposes.
     * The FFM implementation layer calls
     * {@code ASIOGetClockSources(ASIOClockSource[], int* numSources)}
     * and maps each entry's {@code associatedGroup} / name into a
     * {@link ClockKind}; the SDK-level default returns an empty list,
     * which the Audio Settings dialog renders as a disabled combo with
     * a tooltip explaining that the native shim is required.
     */
    @Override
    public List<ClockSource> clockSources(DeviceId device) {
        Objects.requireNonNull(device, "device must not be null");
        return List.of();
    }

    /**
     * Routes a clock-source selection to the FFM-bound
     * {@code ASIOSetClockSource(int)} symbol. Without the native ASIO
     * shim under {@code daw-core/native/asio/} this method throws
     * {@link UnsupportedOperationException} — the same exception the
     * interface default throws — so callers can reliably detect lack
     * of support with a single catch.
     */
    @Override
    public void selectClockSource(DeviceId device, int sourceId) {
        Objects.requireNonNull(device, "device must not be null");
        throw new UnsupportedOperationException(
                "ASIO clock-source selection requires the native shim under "
                        + "daw-core/native/asio/ which is not present in this build.");
    }

    private static String osFamily() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return "Windows";
        if (os.contains("mac") || os.contains("darwin")) return "macOS";
        return "Other";
    }
}
