package com.benesquivelmusic.daw.sdk.audio;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

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

    private static final Logger LOG = Logger.getLogger(AsioBackend.class.getName());

    /**
     * Canonical sample-rate menu probed against {@code ASIOCanSampleRate}
     * — the historical menu the dialog has always offered (story 213).
     */
    static final int[] CANONICAL_SAMPLE_RATES_HZ = {
            44_100, 48_000, 88_200, 96_000, 176_400, 192_000};

    /**
     * Factory for the FFM capability shim. Defaults to loading
     * {@code asioshim} via {@link AsioCapabilityShim}; tests inject a
     * stub via {@link #setCapabilityShimFactory(Supplier)} to exercise
     * the success and missing-shim paths without needing a Windows
     * host with the Steinberg ASIO SDK installed.
     */
    private static volatile Supplier<AsioCapabilityShim> capabilityShimFactory =
            AsioCapabilityShim::new;

    /**
     * Whether the "ASIO capability shim is unavailable; using fallback"
     * INFO has already been logged in this JVM. Story 213 explicitly
     * requires logging the absence "exactly once per process".
     */
    private static final AtomicBoolean FALLBACK_LOGGED = new AtomicBoolean(false);

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
     * <p>Implementation: an FFM downcall to the {@code asioshim}
     * library's {@code asioshim_getBufferSize} entrypoint via
     * {@link AsioCapabilityShim} (story 130). When the shim is absent
     * (e.g. the JVM runs on Linux/macOS, or the Windows DLL was not
     * bundled) the method falls back to
     * {@link BufferSizeRange#DEFAULT_RANGE} and logs the absence at
     * {@code INFO} exactly once per process.</p>
     *
     * <p>The downcall runs on the calling thread (typically the
     * JavaFX thread when the Audio Settings dialog opens), never on
     * the audio render thread.</p>
     */
    @Override
    public BufferSizeRange bufferSizeRange(DeviceId device) {
        Objects.requireNonNull(device, "device must not be null");
        try (AsioCapabilityShim shim = capabilityShimFactory.get()) {
            if (!shim.isAvailable()) {
                logFallbackOnce();
                return BufferSizeRange.DEFAULT_RANGE;
            }
            Optional<BufferSizeRange> probed = shim.getBufferSize();
            if (probed.isPresent()) {
                return probed.get();
            }
            LOG.log(Level.FINE,
                    "ASIO buffer-size probe failed or returned invalid values; "
                            + "using default range");
            return BufferSizeRange.DEFAULT_RANGE;
        }
    }

    /**
     * Reports the sample rates the ASIO driver accepts. Probes each
     * entry of {@link #CANONICAL_SAMPLE_RATES_HZ} against
     * {@code asioshim_canSampleRate(double)} and returns the rates
     * the driver answered {@code ASE_OK} for.
     *
     * <p>When the {@code asioshim} library is absent the method
     * returns the canonical rate set unchanged so the dialog still
     * shows the historical menu, and logs the absence at {@code INFO}
     * exactly once per process.</p>
     */
    @Override
    public Set<Integer> supportedSampleRates(DeviceId device) {
        Objects.requireNonNull(device, "device must not be null");
        try (AsioCapabilityShim shim = capabilityShimFactory.get()) {
            if (!shim.isAvailable()) {
                logFallbackOnce();
                return canonicalSampleRateSet();
            }
            Set<Integer> accepted = new LinkedHashSet<>();
            for (int rate : CANONICAL_SAMPLE_RATES_HZ) {
                if (shim.canSampleRate(rate)) {
                    accepted.add(rate);
                }
            }
            // If the driver rejected every canonical rate (e.g. an unusual
            // hardware-locked rate), return the driver's current rate when
            // available instead of marking the full canonical set as
            // supported. Otherwise return an empty set so the UI can
            // detect "unsupported" rather than offering rates that fail.
            if (accepted.isEmpty()) {
                return shim.getSampleRate()
                        .map(rate -> (int) Math.round(rate))
                        .map(Set::of)
                        .orElseGet(Set::of);
            }
            return Set.copyOf(accepted);
        }
    }

    private static Set<Integer> canonicalSampleRateSet() {
        Set<Integer> all = new LinkedHashSet<>();
        for (int rate : CANONICAL_SAMPLE_RATES_HZ) {
            all.add(rate);
        }
        return Set.copyOf(all);
    }

    private static void logFallbackOnce() {
        if (FALLBACK_LOGGED.compareAndSet(false, true)) {
            LOG.log(Level.INFO,
                    "ASIO capability shim (asioshim) not available — "
                            + "Audio Settings dialog will use the canonical "
                            + "buffer-size and sample-rate fallbacks. "
                            + "Bundle daw-core/native/asio/asioshim.dll "
                            + "for driver-reported values.");
        }
    }

    /**
     * Test seam: replace the factory the backend uses to obtain its
     * {@link AsioCapabilityShim}. Used by unit tests to inject a stub
     * shim that returns a known {@link BufferSizeRange} and answers
     * {@code canSampleRate} deterministically, without requiring the
     * native library to be loaded. Restores via {@link #resetCapabilityShimFactory()}.
     *
     * @param factory non-null supplier of a shim instance per call
     */
    static void setCapabilityShimFactory(Supplier<AsioCapabilityShim> factory) {
        capabilityShimFactory = Objects.requireNonNull(factory, "factory");
    }

    /** Test seam: restore the production factory and reset log-once state. */
    static void resetCapabilityShimFactory() {
        capabilityShimFactory = AsioCapabilityShim::new;
        FALLBACK_LOGGED.set(false);
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
