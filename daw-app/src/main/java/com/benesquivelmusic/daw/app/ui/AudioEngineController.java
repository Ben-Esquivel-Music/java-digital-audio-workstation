package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.BufferSizeRange;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MixPrecision;
import com.benesquivelmusic.daw.sdk.audio.RoundTripLatency;
import com.benesquivelmusic.daw.sdk.audio.SampleRate;
import com.benesquivelmusic.daw.sdk.audio.XrunEvent;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Flow;

/**
 * Application-layer abstraction that the {@link AudioSettingsDialog} uses
 * to query and mutate audio engine state without depending on the full
 * {@link com.benesquivelmusic.daw.core.audio.AudioEngine} wiring.
 *
 * <p>Implementations coordinate stopping the audio stream, swapping or
 * re-initializing the backend, creating a new engine with the requested
 * {@link com.benesquivelmusic.daw.core.audio.AudioFormat}, and restarting
 * playback. The dialog treats this interface as opaque so it can be
 * stubbed in tests that do not have a real audio engine available.</p>
 */
public interface AudioEngineController {

    /** Constant backend name used when no native backend is available. */
    String BACKEND_NONE = "None";

    /**
     * Immutable request passed to {@link #applyConfiguration(Request)}.
     *
     * @param backendName        name of the backend to activate (e.g., "PortAudio")
     * @param inputDeviceName    name of the input device, or empty for default
     * @param outputDeviceName   name of the output device, or empty for default
     * @param sampleRate         desired sample rate
     * @param bufferFrames       desired buffer size in sample frames (must be positive);
     *                           may be any driver-reported value, not limited to the
     *                           power-of-two {@link BufferSize} enum
     * @param bitDepth           desired bit depth (for new-project defaults)
     */
    record Request(
            String backendName,
            String inputDeviceName,
            String outputDeviceName,
            SampleRate sampleRate,
            int bufferFrames,
            int bitDepth) {

        public Request {
            if (backendName == null) {
                throw new IllegalArgumentException("backendName must not be null");
            }
            if (inputDeviceName == null) {
                throw new IllegalArgumentException("inputDeviceName must not be null");
            }
            if (outputDeviceName == null) {
                throw new IllegalArgumentException("outputDeviceName must not be null");
            }
            if (sampleRate == null) {
                throw new IllegalArgumentException("sampleRate must not be null");
            }
            if (bufferFrames <= 0) {
                throw new IllegalArgumentException("bufferFrames must be positive: " + bufferFrames);
            }
            if (bitDepth <= 0) {
                throw new IllegalArgumentException("bitDepth must be positive: " + bitDepth);
            }
        }
    }

    /** Returns the name of the currently active backend, or {@link #BACKEND_NONE}. */
    String getActiveBackendName();

    /**
     * Returns the list of backend names the user can switch between.
     * A backend appears only if its native library / runtime is usable on
     * this system.
     */
    List<String> getAvailableBackendNames();

    /**
     * Returns all audio devices reported by the currently active backend.
     * Returns an empty list when no backend is active.
     */
    List<AudioDeviceInfo> listDevices();

    /**
     * Returns the audio devices reported by the given backend without
     * changing the active backend. Returns an empty list when the backend
     * name is unknown or the backend fails to initialize.
     */
    List<AudioDeviceInfo> listDevices(String backendName);

    /**
     * Returns the most recent audio-thread CPU load as a percentage in
     * {@code [0.0, 100.0]}, or {@code -1.0} if the engine has no active
     * performance monitor.
     */
    double getCpuLoadPercent();

    /**
     * Applies the given configuration. Typically stops the current stream,
     * swaps the backend if requested, creates a new engine with the new
     * format, re-wires it to the project, and restarts playback.
     *
     * @throws RuntimeException if the configuration cannot be applied
     */
    void applyConfiguration(Request request);

    /**
     * Applies the given {@link MixPrecision} to the live mixer. The change
     * takes effect on the next {@code Mixer.mixDown()} invocation; it does
     * not require stopping or restarting the audio stream.
     *
     * <p>The default implementation is a no-op, which is safe for test
     * doubles that do not have a real engine.</p>
     *
     * @param precision the new mix precision (must not be {@code null})
     */
    default void applyMixPrecision(MixPrecision precision) {
        // no-op for test stubs
    }

    /**
     * Plays a short 440 Hz sine tone to verify audio output. Non-blocking —
     * returns as soon as the tone is queued. Uses the {@code javax.sound.sampled}
     * fallback path so it does not interfere with the main engine stream.
     *
     * @param outputDeviceName preferred device name for the tone, or empty
     *                         for the JVM default mixer
     * @throws RuntimeException if the tone cannot be played
     */
    void playTestTone(String outputDeviceName);

    /**
     * Returns an action that launches the active backend's native
     * driver control panel, or {@link Optional#empty()} when the
     * active backend has no native panel (for example {@code JACK} or
     * the test {@code Mock} backend).
     *
     * <p>{@link AudioSettingsDialog} uses this to enable or disable
     * the "Open Driver Control Panel" button. The returned runnable
     * may throw {@link RuntimeException} (typically
     * {@link com.benesquivelmusic.daw.sdk.audio.AudioBackendException})
     * when the panel cannot be launched; callers should surface the
     * failure as a notification rather than letting the stack trace
     * escape.</p>
     *
     * <p>The default implementation returns
     * {@link Optional#empty()}, which is safe for test stubs that do
     * not have a real backend.</p>
     *
     * @return an optional action that opens the native panel; never
     *         {@code null}
     */
    default Optional<Runnable> openControlPanel() {
        return Optional.empty();
    }

    /**
     * Returns a {@link Flow.Publisher} that emits {@link XrunEvent}s
     * whenever the audio engine detects a late buffer, a dropped
     * buffer, or a graph-wide CPU overload.
     *
     * <p>UI components (for example an xrun counter in the transport
     * bar) subscribe to this publisher so they never have to poll the
     * audio thread. The default implementation returns an empty
     * publisher that never emits, which is safe for test doubles that
     * do not need xrun reporting.</p>
     *
     * @return a publisher of {@link XrunEvent}s; never {@code null}
     */
    default Flow.Publisher<XrunEvent> xrunEvents() {
        return subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) { /* no-op */ }
                @Override public void cancel() { /* no-op */ }
            });
        };
    }

    /**
     * Returns the buffer-size range the active backend allows for the
     * named output device. The Audio Settings dialog (story 098 +
     * story 213) uses this to populate the buffer-size dropdown with
     * driver-allowed values only.
     *
     * <p>The default implementation returns
     * {@link BufferSizeRange#DEFAULT_RANGE} so test stubs that have no
     * real backend continue to work.</p>
     *
     * @param backendName       backend name (e.g. "ASIO", "WASAPI",
     *                          "PortAudio", "Java Sound")
     * @param outputDeviceName  output device name; empty for the
     *                          default device
     * @return the driver-allowed buffer-size range; never {@code null}
     */
    default BufferSizeRange bufferSizeRange(String backendName, String outputDeviceName) {
        return BufferSizeRange.DEFAULT_RANGE;
    }

    /**
     * Returns the set of sample rates (in Hz) the active backend's
     * named output device can operate at. The Audio Settings dialog
     * uses this to grey out unsupported rates in the canonical menu
     * and to fall back to the device's preferred rate when a
     * persisted setting is no longer supported.
     *
     * <p>The default implementation returns the canonical rate set so
     * test stubs that have no real backend continue to work.</p>
     *
     * @param backendName       backend name
     * @param outputDeviceName  output device name; empty for the
     *                          default device
     * @return an immutable set of supported sample rates in Hz; never
     *         {@code null}
     */
    default Set<Integer> supportedSampleRates(String backendName, String outputDeviceName) {
        return Set.of(44_100, 48_000, 88_200, 96_000, 176_400, 192_000);
    }

    /**
     * Returns the current high-level engine lifecycle state. Distinct from
     * transport state (play/record/stop) and from the audio stream's
     * open/closed flag — captures whether the engine is rendering normally,
     * intentionally stopped, or recovering from a device disconnect.
     *
     * <p>The default implementation returns {@link EngineState#STOPPED}
     * which is safe for test stubs that do not track engine lifecycle.</p>
     *
     * @return the current engine state; never {@code null}
     */
    default EngineState engineState() {
        return EngineState.STOPPED;
    }

    /**
     * Returns a {@link Flow.Publisher} that emits the new
     * {@link EngineState} every time the engine transitions between
     * states. UI components (for example the transport bar's
     * "Reconnecting…" indicator) subscribe to this publisher so they
     * never have to poll.
     *
     * <p>The default implementation returns an empty publisher that
     * never emits, which is safe for test doubles.</p>
     *
     * @return a publisher of engine-state transitions; never {@code null}
     */
    default Flow.Publisher<EngineState> engineStateEvents() {
        return subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) { /* no-op */ }
                @Override public void cancel() { /* no-op */ }
            });
        };
    }

    /**
     * Returns the driver-reported round-trip latency for the currently
     * opened audio stream. The {@link TransportController} queries this
     * once at recording start and passes the result to
     * {@link com.benesquivelmusic.daw.core.recording.RecordingPipeline#setReportedLatency(RoundTripLatency)}
     * so captured clips are shifted by the driver's reported
     * input + output + safety-offset frames.
     *
     * <p>The default implementation returns
     * {@link RoundTripLatency#UNKNOWN} (zero compensation), which is
     * safe for test stubs that have no real backend.</p>
     *
     * @return the driver-reported round-trip latency; never {@code null}
     */
    default RoundTripLatency reportedLatency() {
        return RoundTripLatency.UNKNOWN;
    }

    /**
     * Returns the hardware clock sources the active backend's named
     * output device exposes. The Audio Settings dialog renders this as
     * a combo box; an empty list disables the combo and tooltips it
     * "this backend does not expose clock-source selection".
     *
     * <p>The default implementation returns an empty list so test
     * stubs that have no real backend continue to work.</p>
     *
     * @param backendName       backend name (e.g. "ASIO", "WASAPI")
     * @param outputDeviceName  output device name; empty for the
     *                          default device
     * @return the device's clock sources; never {@code null}
     */
    default List<com.benesquivelmusic.daw.sdk.audio.ClockSource> clockSources(
            String backendName, String outputDeviceName) {
        return List.of();
    }

    /**
     * Asks the active backend to lock the named output device to the
     * clock source whose id is {@code sourceId}. The dialog calls this
     * when the user picks a new entry in the Clock Source combo. After
     * a successful selection the dialog re-queries
     * {@link #bufferSizeRange(String, String)} and
     * {@link #supportedSampleRates(String, String)}, since some
     * interfaces only allow specific rates per clock source.
     *
     * <p>The default implementation is a no-op which is safe for test
     * stubs.</p>
     *
     * @param backendName       backend name
     * @param outputDeviceName  output device name; empty for the
     *                          default device
     * @param sourceId          driver-defined clock-source id
     */
    default void selectClockSource(String backendName, String outputDeviceName, int sourceId) {
        // no-op for test stubs
    }

    /**
     * Returns a {@link Flow.Publisher} that emits a
     * {@link com.benesquivelmusic.daw.sdk.audio.ClockLockEvent} every
     * time the active backend reports a change in external-clock lock
     * state. The transport-bar clock-status indicator subscribes to
     * this so it can flash red on lock failure; the engine
     * additionally pauses recording (not playback) on a lock-loss
     * event.
     *
     * <p>The default implementation returns an empty publisher that
     * never emits, which is safe for test doubles.</p>
     *
     * @return a publisher of clock-lock events; never {@code null}
     */
    default Flow.Publisher<com.benesquivelmusic.daw.sdk.audio.ClockLockEvent> clockLockEvents() {
        return subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) { /* no-op */ }
                @Override public void cancel() { /* no-op */ }
            });
        };
    }

    /**
     * Binds the given {@link AudioBackend} so the controller subscribes
     * to its {@link AudioBackend#deviceEvents()} publisher and reacts
     * to {@code DeviceArrived}, {@code DeviceRemoved}, and
     * {@code DeviceFormatChanged}.
     *
     * <p>On {@code DeviceRemoved} for the active device, the controller
     * transitions to {@link EngineState#DEVICE_LOST}, halts the render
     * thread, persists the in-flight recording take to
     * {@code .daw/incomplete-takes/}, and notifies the user. On a
     * matching {@code DeviceArrived}, the stream is reopened with the
     * previously configured format and the engine returns to
     * {@link EngineState#STOPPED}.</p>
     *
     * <p>The default implementation is a no-op which is safe for test
     * stubs.</p>
     *
     * @param backend       the backend whose hot-plug events to consume;
     *                      must not be null
     * @param activeDevice  the currently opened device (the one whose
     *                      removal should trigger {@code DEVICE_LOST});
     *                      must not be null
     */
    default void bindBackendDeviceEvents(AudioBackend backend, DeviceId activeDevice) {
        // no-op for test stubs
    }
}
