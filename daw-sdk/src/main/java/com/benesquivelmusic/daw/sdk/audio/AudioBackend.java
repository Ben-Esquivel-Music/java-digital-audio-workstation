package com.benesquivelmusic.daw.sdk.audio;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Flow;

/**
 * Sealed abstraction over the five professional audio I/O backends the DAW
 * targets, plus a deterministic mock for offline tests.
 *
 * <p>An {@code AudioBackend} is a thin, uniform surface over very different
 * native drivers — ASIO on Windows, CoreAudio on macOS, WASAPI on Windows,
 * JACK on Linux, and the cross-platform {@code javax.sound.sampled} fallback.
 * The application layer (see {@code AudioEngineController}) chooses a
 * backend based on the current OS and the user's saved selection
 * (see {@link AudioSettingsStore}), and transparently falls back to
 * {@link JavaxSoundBackend} when the preferred backend cannot open a stream
 * on this machine (see {@link AudioBackendSelector}).</p>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link #isAvailable()} — cheap check that the native library / driver
 *       the backend needs is installed. Safe to call on any OS.</li>
 *   <li>{@link #listDevices()} — enumerate the backend's devices. Returns
 *       an empty list when the backend is not available.</li>
 *   <li>{@link #open(DeviceId, AudioFormat, int)} — allocate a stream.
 *       After this call, {@link #inputBlocks()} starts emitting blocks for
 *       devices that can record and {@link #sink(AudioBlock)} accepts blocks
 *       for devices that can play back.</li>
 *   <li>{@link #close()} — release all native resources. Idempotent.</li>
 * </ol>
 *
 * <h2>Threading</h2>
 * <p>{@link #inputBlocks()} delivers each {@link AudioBlock} on a backend-
 * owned thread (typically the native audio callback thread). Subscribers
 * must not block. {@link #sink(AudioBlock)} may be called from any thread;
 * implementations serialize internally.</p>
 *
 * <h2>Permitted implementations</h2>
 * <ul>
 *   <li>{@link JavaxSoundBackend} — always available; built on
 *       {@code javax.sound.sampled}.</li>
 *   <li>{@link AsioBackend} — Windows ASIO via FFM. Requires a
 *       user-installed ASIO driver (for example ASIO4ALL).</li>
 *   <li>{@link CoreAudioBackend} — macOS CoreAudio via FFM.</li>
 *   <li>{@link WasapiBackend} — Windows WASAPI via FFM (shared / exclusive).</li>
 *   <li>{@link JackBackend} — Linux JACK via FFM bindings to {@code libjack}.</li>
 *   <li>{@link MockAudioBackend} — deterministic test double that plays from
 *       and writes to {@code byte[]} buffers; never touches real hardware,
 *       so integration tests can run on a headless CI runner without an
 *       audio card.</li>
 * </ul>
 *
 * @see AudioBackendSelector
 * @see AudioSettingsStore
 */
public sealed interface AudioBackend extends AutoCloseable
        permits JavaxSoundBackend,
                AsioBackend,
                CoreAudioBackend,
                WasapiBackend,
                JackBackend,
                MockAudioBackend {

    /**
     * Returns the human-readable name of the backend, used as the
     * {@code backend} component of {@link DeviceId}.
     *
     * @return the backend's display name (never null or blank)
     */
    String name();

    /**
     * Returns {@code true} if the backend's native library / driver is usable
     * on this host. Cheap and side-effect-free: callers rely on this when
     * building the list shown in the Audio Settings dialog (story 098).
     *
     * @return true when {@link #open(DeviceId, AudioFormat, int)} has a
     *         realistic chance of succeeding
     */
    boolean isAvailable();

    /**
     * Enumerates every device the backend exposes on this host. Returns an
     * empty, unmodifiable list when the backend is not available.
     *
     * @return an unmodifiable list of devices
     */
    List<AudioDeviceInfo> listDevices();

    /**
     * Opens a stream on the given device with the given format and
     * buffer size (in sample frames). The backend is left in the "open"
     * state until {@link #close()} is called; calling {@code open} twice
     * without an intervening {@code close} must throw
     * {@link IllegalStateException}.
     *
     * @param device      target device id; {@link DeviceId#isDefault() default}
     *                    asks the backend to pick its own default device
     * @param format      desired PCM format
     * @param bufferFrames desired buffer size in sample frames (must be positive)
     * @throws AudioBackendException     if the native driver refuses the
     *                                   requested configuration
     * @throws IllegalStateException     if a stream is already open on this backend
     * @throws IllegalArgumentException  if {@code bufferFrames <= 0}
     */
    void open(DeviceId device, AudioFormat format, int bufferFrames);

    /**
     * Returns a {@link Flow.Publisher} that emits one {@link AudioBlock} per
     * hardware callback while the stream is open. The publisher completes
     * when {@link #close()} is called. Returns an empty publisher (completes
     * immediately) for output-only devices.
     *
     * @return a publisher of captured input blocks; never {@code null}
     */
    Flow.Publisher<AudioBlock> inputBlocks();

    /**
     * Writes a block of audio to the backend's output device. Blocks delivered
     * while no stream is open are silently dropped. The {@code block}'s
     * channel count must match the channel count passed to
     * {@link #open(DeviceId, AudioFormat, int)}.
     *
     * @param block the audio to play; must not be null
     * @throws IllegalArgumentException if {@code block} is incompatible with
     *                                  the opened format
     */
    void sink(AudioBlock block);

    /**
     * Writes a mono buffer directly to a single physical output channel,
     * bypassing the main mix bus and any track or return-bus processing.
     *
     * <p>Used by the metronome's side output (story 136) to feed the click
     * to the drummer's headphone channel without the sample appearing in
     * overhead or room microphones. The default implementation is a no-op;
     * implementations that cannot address individual output channels may
     * leave it as-is, in which case the side output is silently dropped.
     * Buffers delivered while no stream is open are silently ignored.</p>
     *
     * @param channelIndex 0-based index of the physical output channel
     *                     (must be &ge; 0)
     * @param monoSamples  mono audio samples in {@code [-1.0, 1.0]};
     *                     must not be null (may be empty)
     * @throws IllegalArgumentException if {@code channelIndex} is negative
     *                                  or {@code monoSamples} is null
     */
    default void writeToChannel(int channelIndex, float[] monoSamples) {
        if (channelIndex < 0) {
            throw new IllegalArgumentException(
                    "channelIndex must not be negative: " + channelIndex);
        }
        if (monoSamples == null) {
            throw new IllegalArgumentException("monoSamples must not be null");
        }
        // Default: drop the samples. Backends that can address individual
        // output channels override this method.
    }

    /**
     * Returns {@code true} while a stream is open (between {@code open} and
     * {@code close}).
     *
     * @return true when the stream is open
     */
    boolean isOpen();

    /**
     * Returns an action that launches the driver's native control panel
     * (the vendor's own out-of-process UI), or {@link Optional#empty()}
     * when this backend has no native panel.
     *
     * <p>Multi-channel USB audio interfaces ship vendor utilities — USB
     * streaming mode, safe-mode buffers, routing matrices, mixer pages,
     * and the driver's own buffer-size table all live there. This hook
     * lets the DAW surface that UI from the Audio Settings dialog
     * exactly the way Pro Tools, Cubase, Reaper, and Studio One do on
     * Windows. The DAW is responsible for invoking the returned
     * {@link Runnable} on a non-audio thread; implementations must
     * never block the render callback. The DAW is also responsible for
     * re-querying {@link #listDevices()} after the returned
     * {@link Runnable} finishes so the UI can reflect any change the
     * user made in the driver UI. Some implementations launch an
     * external process and may therefore return before the user closes
     * the native panel.</p>
     *
     * <p>Per-backend conventions:</p>
     * <ul>
     *   <li>{@link AsioBackend} — invokes the driver-provided
     *       {@code ASIOControlPanel()} via the FFM binding.</li>
     *   <li>{@link WasapiBackend} — launches {@code mmsys.cpl ,1}
     *       (Recording tab) on Windows.</li>
     *   <li>{@link CoreAudioBackend} — opens
     *       {@code /System/Applications/Utilities/Audio MIDI Setup.app}
     *       via {@code open(1)}.</li>
     *   <li>{@link JackBackend} — returns empty;
     *       {@code qjackctl} is third-party and out of scope.</li>
     *   <li>{@link JavaxSoundBackend} — returns empty; the JDK mixer
     *       has no vendor UI.</li>
     *   <li>{@link MockAudioBackend} — returns a runnable that records
     *       the invocation for tests.</li>
     * </ul>
     *
     * <p>Failures from the launched action (for example the ASIO
     * driver returning {@code ASE_NotPresent}, a missing executable,
     * or denied access) must be surfaced as a {@link RuntimeException}
     * — typically {@link AudioBackendException} — so the caller can
     * report it to the user instead of letting a stack trace escape.</p>
     *
     * <p>The default implementation returns {@link Optional#empty()},
     * which is the correct behaviour for any backend that has no
     * vendor control panel.</p>
     *
     * @return an optional action that opens the native panel, or
     *         empty when the backend has no native panel; never null
     */
    default Optional<Runnable> openControlPanel() {
        return Optional.empty();
    }

    /**
     * Returns the discrete set of buffer sizes the given device will
     * accept, expressed as a {@link BufferSizeRange} four-tuple
     * {@code (min, max, preferred, granularity)} — the same shape
     * Steinberg's {@code ASIOGetBufferSize} reports. The Audio Settings
     * dialog (story 098) consults this method instead of inventing its
     * own buffer-size menu, so users only ever see frame counts the
     * driver will actually accept.
     *
     * <p>Per-backend conventions:</p>
     * <ul>
     *   <li>{@link AsioBackend} — calls {@code ASIOGetBufferSize}.</li>
     *   <li>{@link WasapiBackend} — exclusive mode reads
     *       {@code IAudioClient::GetDevicePeriod} for the (min,max)
     *       range; shared mode reports
     *       {@code BufferSizeRange.singleton(mixerPeriodFrames)} since
     *       the OS mixer period is fixed.</li>
     *   <li>{@link CoreAudioBackend} — reads
     *       {@code kAudioDevicePropertyBufferFrameSizeRange} for
     *       (min,max) and
     *       {@code kAudioDevicePropertyBufferFrameSize} for preferred;
     *       granularity is {@code 1}.</li>
     *   <li>{@link JackBackend} — returns
     *       {@code BufferSizeRange.singleton(jackBufferSize)} since the
     *       JACK server picks one server-wide buffer size.</li>
     *   <li>{@link JavaxSoundBackend} — returns the historical
     *       power-of-two ladder so persisted settings keep working;
     *       the JDK mixer does not expose a query API.</li>
     *   <li>{@link MockAudioBackend} — returns whatever the test
     *       fixture has configured; defaults to a generic ladder.</li>
     * </ul>
     *
     * <p>The default implementation returns
     * {@link BufferSizeRange#DEFAULT_RANGE}, which preserves the
     * historical menu for backends that have not yet overridden it.</p>
     *
     * @param device target device id; {@link DeviceId#isDefault() default}
     *               asks the backend to query its own default device
     * @return the range of buffer sizes the device accepts; never null
     */
    default BufferSizeRange bufferSizeRange(DeviceId device) {
        return BufferSizeRange.DEFAULT_RANGE;
    }

    /**
     * Returns the set of sample rates (in Hz) the given device will
     * accept — i.e. the drivers that today reject
     * {@code ASIOSetSampleRate()} for any rate not in their
     * {@code ASIOCanSampleRate()} whitelist.
     *
     * <p>The Audio Settings dialog (story 098) shows the union of the
     * canonical rate list ({@link SampleRate}) and this set, with rates
     * the device does not support visually disabled and tooltipped
     * "not supported by current device" — exactly the way Pro Tools,
     * Cubase and Reaper present unsupported rates.</p>
     *
     * <p>Per-backend conventions:</p>
     * <ul>
     *   <li>{@link AsioBackend} — probes
     *       {@code ASIOCanSampleRate} across the canonical rate list.</li>
     *   <li>{@link WasapiBackend} — shared mode returns the singleton
     *       OS-mixer rate (the only rate the WASAPI mixer accepts);
     *       exclusive mode probes
     *       {@code IAudioClient::IsFormatSupported} across the
     *       canonical rate list.</li>
     *   <li>{@link CoreAudioBackend} — reads
     *       {@code kAudioDevicePropertyAvailableNominalSampleRates}.</li>
     *   <li>{@link JackBackend} — returns the singleton
     *       {@code jack_get_sample_rate(client)} since the JACK
     *       server picks one server-wide rate.</li>
     *   <li>{@link JavaxSoundBackend} — returns the historical
     *       canonical list; the JDK mixer accepts whatever the
     *       underlying OS driver accepts.</li>
     *   <li>{@link MockAudioBackend} — returns whatever the test
     *       fixture has configured.</li>
     * </ul>
     *
     * <p>The default implementation returns the canonical rate set
     * (44.1 / 48 / 88.2 / 96 / 176.4 / 192 kHz) so backends that have
     * not yet overridden it preserve historical behaviour.</p>
     *
     * @param device target device id; {@link DeviceId#isDefault() default}
     *               asks the backend to query its own default device
     * @return an immutable set of supported sample rates in Hz; never null
     */
    default Set<Integer> supportedSampleRates(DeviceId device) {
        return Set.of(44_100, 48_000, 88_200, 96_000, 176_400, 192_000);
    }

    /**
     * Returns a {@link Flow.Publisher} that emits an
     * {@link AudioDeviceEvent} every time the host OS or vendor driver
     * reports that a device has arrived, gone away, or changed its
     * native format.
     *
     * <p>USB audio interfaces enumerate and unenumerate freely: a yanked
     * cable, a sleeping laptop, a powered USB hub cycling, or a driver
     * crash all surface as the device "going away" mid-session. Each OS
     * gives us a structured signal for that, and this publisher unifies
     * all of them so the application layer can transition the engine to
     * {@code DEVICE_LOST}, halt the render thread, persist any in-flight
     * recording take, and automatically reopen the stream when the
     * device returns.</p>
     *
     * <p>Per-backend conventions:</p>
     * <ul>
     *   <li>{@link AsioBackend} — translates {@code kAsioResetRequest},
     *       {@code kAsioBufferSizeChange}, and
     *       {@code kAsioResyncRequest} from the ASIO callback set
     *       installed on driver open.</li>
     *   <li>{@link WasapiBackend} — subscribes to
     *       {@code IMMNotificationClient::OnDeviceStateChanged} and
     *       {@code OnDefaultDeviceChanged}.</li>
     *   <li>{@link CoreAudioBackend} — installs a property listener
     *       on {@code kAudioHardwarePropertyDevices}.</li>
     *   <li>{@link JackBackend} — watches for JACK server shutdown
     *       (registered shutdown callback) and port-registration
     *       changes.</li>
     *   <li>{@link JavaxSoundBackend} — emits no events; the JDK mixer
     *       does not expose a hot-plug notification API.</li>
     *   <li>{@link MockAudioBackend} — exposes
     *       {@code simulateDeviceArrived/Removed/FormatChanged} so
     *       tests can drive the device-event flow deterministically.</li>
     * </ul>
     *
     * <p>Events are delivered on a backend-owned thread (the OS
     * notification thread, never the audio callback thread).
     * Subscribers must not block.</p>
     *
     * <p>The default implementation returns an empty publisher that
     * never emits, which is safe for backends that have no hot-plug
     * notification source.</p>
     *
     * @return a publisher of device events; never {@code null}
     */
    default Flow.Publisher<AudioDeviceEvent> deviceEvents() {
        return subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) { /* no-op */ }
                @Override public void cancel() { /* no-op */ }
            });
        };
    }

    /**
     * Returns driver-reported metadata for every input channel of the
     * given device — story 199 ("Driver-Reported Channel Names in I/O
     * Routing Dropdowns").
     *
     * <p>Each {@link AudioChannelInfo} carries the channel's index, a
     * driver-reported display name (e.g. {@code "Mic/Line 1"},
     * {@code "Hi-Z Inst 3"}, {@code "S/PDIF L"}), a {@link ChannelKind}
     * classification, and an active flag (ASIO {@code isActive}: the
     * UI greys disabled channels).</p>
     *
     * <p>Per-backend conventions:</p>
     * <ul>
     *   <li>{@link AsioBackend} — {@code ASIOGetChannelInfo(channel,
     *       isInput=true)} per channel; kind via
     *       {@link ChannelKindHeuristics}.</li>
     *   <li>{@link CoreAudioBackend} —
     *       {@code kAudioObjectPropertyElementName} per channel.</li>
     *   <li>{@link WasapiBackend} — {@code IPart::GetName} on the
     *       capture endpoint's channel parts.</li>
     *   <li>{@link JackBackend} — {@code jack_port_get_aliases}.</li>
     *   <li>{@link JavaxSoundBackend} — empty list; the JDK mixer does
     *       not expose per-channel names.</li>
     *   <li>{@link MockAudioBackend} — returns whatever the test fixture
     *       has configured via {@code setInputChannels}.</li>
     * </ul>
     *
     * <p>The default implementation returns an empty list, which the
     * UI interprets as "fall back to generic 'Input N' labels".</p>
     *
     * @param device target device id; must not be null
     * @return an unmodifiable list of input-channel metadata; never null
     */
    default List<AudioChannelInfo> inputChannels(DeviceId device) {
        return List.of();
    }

    /**
     * Returns driver-reported metadata for every output channel of the
     * given device — the output-side counterpart of
     * {@link #inputChannels(DeviceId)}. See that method for per-backend
     * conventions.
     *
     * @param device target device id; must not be null
     * @return an unmodifiable list of output-channel metadata; never null
     */
    default List<AudioChannelInfo> outputChannels(DeviceId device) {
        return List.of();
    }

    /**
     * Returns the hardware clock sources the given device exposes
     * (internal crystal, word-clock BNC, S/PDIF, ADAT, AES, …) — story
     * "Hardware Clock Source Selection".
     *
     * <p>One element of the returned list will have
     * {@link ClockSource#current()} {@code true}; the dialog uses that
     * to default the Clock Source combo. An empty list disables the
     * combo and tooltips it "this backend does not expose clock-source
     * selection" — that is the correct behaviour for WASAPI / JACK /
     * the JDK mixer, which all run at the OS / server clock and have
     * no per-device choice.</p>
     *
     * <p>Per-backend conventions:</p>
     * <ul>
     *   <li>{@link AsioBackend} — calls
     *       {@code ASIOGetClockSources(ASIOClockSource[], int*
     *       numSources)} and maps every entry's {@code associatedGroup}
     *       to a {@link ClockKind}.</li>
     *   <li>{@link CoreAudioBackend} — reads
     *       {@code kAudioDevicePropertyClockSource} (current) and
     *       {@code kAudioDevicePropertyClockSources} (available).</li>
     *   <li>{@link WasapiBackend} — empty list; selection happens in
     *       the device's own control panel.</li>
     *   <li>{@link JackBackend} — empty list; the JACK server runs at
     *       its own clock.</li>
     *   <li>{@link JavaxSoundBackend} — empty list; the JDK mixer has
     *       no clock-source API.</li>
     *   <li>{@link MockAudioBackend} — returns whatever the test
     *       fixture has configured via {@code setClockSources}.</li>
     * </ul>
     *
     * <p>The default implementation returns an empty list, which the
     * UI interprets as "no choice available — disable the combo and
     * tooltip it".</p>
     *
     * @param device target device id; must not be null
     * @return an unmodifiable list of clock sources; never null
     */
    default List<ClockSource> clockSources(DeviceId device) {
        return List.of();
    }

    /**
     * Asks the driver to lock the device to the clock source whose
     * {@link ClockSource#id()} equals {@code sourceId}. Maps to
     * {@code ASIOSetClockSource(int)} on ASIO and to
     * {@code kAudioDevicePropertyClockSource} on CoreAudio.
     *
     * <p>After this call the host should re-query
     * {@link #bufferSizeRange(DeviceId)} and
     * {@link #supportedSampleRates(DeviceId)}, since some interfaces
     * only allow specific rates and buffer sizes per clock source.</p>
     *
     * <p>The default implementation throws
     * {@link UnsupportedOperationException}, which is appropriate for
     * backends that report an empty {@link #clockSources(DeviceId)}
     * list (the UI keeps the combo disabled in that case so the call
     * never reaches them through the normal flow).</p>
     *
     * @param device   target device id; must not be null
     * @param sourceId the driver-defined id of the source to lock to,
     *                 as reported by {@link #clockSources(DeviceId)}
     * @throws UnsupportedOperationException if this backend has no
     *                                       clock-source selection API
     * @throws AudioBackendException         if the driver rejects the
     *                                       requested source
     */
    default void selectClockSource(DeviceId device, int sourceId) {
        throw new UnsupportedOperationException(
                "Backend " + name() + " does not support clock-source selection");
    }

    /**
     * Returns a {@link Flow.Publisher} that emits a
     * {@link ClockLockEvent} every time the driver reports a change in
     * external-clock lock state.
     *
     * <p>ASIO drivers surface this through
     * {@code ASIOFuture(kAsioGetExternalClockLocked)} (polled at 1 Hz
     * from a non-audio thread) plus the asynchronous
     * {@code kAsioResyncRequest} callback. CoreAudio surfaces it via
     * {@code kAudioDevicePropertyClockSourceLocked}.</p>
     *
     * <p>The default implementation returns an empty publisher that
     * never emits, which is correct for backends that do not expose
     * clock-lock state (WASAPI / JACK / the JDK mixer).</p>
     *
     * @return a publisher of clock-lock events; never {@code null}
     */
    default Flow.Publisher<ClockLockEvent> clockLockEvents() {
        return subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) { /* no-op */ }
                @Override public void cancel() { /* no-op */ }
            });
        };
    }

    /**
     * Returns the driver-reported round-trip latency for the currently
     * opened stream — the sum of capture-path and playback-path frames
     * the recording pipeline must subtract from each captured-block
     * sample position so a recorded take aligns with the cue the user
     * heard.
     *
     * <p>Per-backend conventions (planned — none of these backends
     * override this method yet; they all return the default
     * {@link RoundTripLatency#UNKNOWN} until the native query is
     * implemented in a follow-up):</p>
     * <ul>
     *   <li><b>ASIO</b> — will call
     *       {@code ASIOGetLatencies(int* in, int* out)}; safety-offset
     *       frames will be zero (ASIO has no equivalent).</li>
     *   <li><b>CoreAudio</b> — will sum
     *       {@code kAudioDevicePropertyLatency} +
     *       {@code kAudioStreamPropertyLatency} into the input/output
     *       components and report
     *       {@code kAudioDevicePropertySafetyOffset} separately.</li>
     *   <li><b>WASAPI</b> — will translate
     *       {@code IAudioClient::GetStreamLatency} plus the device
     *       period into the input/output components.</li>
     *   <li><b>JACK</b> — will use
     *       {@code jack_port_get_total_latency} on the capture and
     *       playback ports.</li>
     *   <li>{@link JavaxSoundBackend} — returns
     *       {@link RoundTripLatency#UNKNOWN}; the JDK mixer does not
     *       expose a latency query API.</li>
     *   <li>{@link MockAudioBackend} — returns whatever the test
     *       fixture has configured via
     *       {@code setReportedLatency(RoundTripLatency)}.</li>
     * </ul>
     *
     * <p>Backends that have not yet opened a stream — or that have no
     * way to query the driver — return
     * {@link RoundTripLatency#UNKNOWN}, which yields zero compensation
     * frames and makes the recording pipeline a no-op for that
     * stream.</p>
     *
     * <p>The default implementation returns
     * {@link RoundTripLatency#UNKNOWN}, which preserves the historical
     * "no compensation" behaviour for backends that have not yet
     * overridden it.</p>
     *
     * @return the driver-reported round-trip latency; never {@code null}
     */
    default RoundTripLatency reportedLatency() {
        return RoundTripLatency.UNKNOWN;
    }

    /**
     * Closes any open stream and releases native resources. Idempotent.
     */
    @Override
    void close();
}
