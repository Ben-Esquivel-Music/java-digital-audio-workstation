package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioEngineSettings;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.CallbackBackendAdapter;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.audio.performance.XrunDetector;
import com.benesquivelmusic.daw.core.audio.portaudio.PortAudioBackend;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.performance.PerformanceMonitor;
import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceEvent;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.BufferSizeRange;
import com.benesquivelmusic.daw.sdk.audio.ClockSource;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.FormatChangeReason;
import com.benesquivelmusic.daw.sdk.audio.JavaxSoundBackend;
import com.benesquivelmusic.daw.sdk.audio.MixPrecision;
import com.benesquivelmusic.daw.sdk.audio.WasapiBackend;
import com.benesquivelmusic.daw.sdk.audio.XrunEvent;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default {@link AudioEngineController} that drives a real {@link AudioEngine}.
 *
 * <p>Owns the engine's {@link StreamingProvision} — the requested SDK
 * {@link AudioBackend} plus its explicit fallback ladder (story 316) — and
 * coordinates reconfiguration: stop the current stream, mutate the engine's
 * format, rebuild the provision when the requested backend or device
 * changed, then restart the stream through the engine's one open seam.
 * Each engine re-configuration reuses the same {@code AudioEngine} instance
 * so that all sub-controllers (transport, mixer, editors) keep their
 * existing engine references intact. The controller owns every provision
 * backend instance's lifecycle; the engine only opens and closes streams on
 * them.</p>
 */
final class DefaultAudioEngineController implements AudioEngineController {

    private static final Logger LOG = Logger.getLogger(DefaultAudioEngineController.class.getName());

    private final AudioEngine audioEngine;
    private final TestTonePlayer tonePlayer;
    private final Runnable postReconfigureCallback;
    private final NotificationManager notifications;
    private final IncompleteTakeStore incompleteTakeStore;
    /**
     * Maps a UI-facing backend name (e.g. {@code "ASIO"}, {@code "WASAPI"},
     * {@code "CoreAudio"}, {@code "JACK"}, {@code "Mock"}) to a fresh SDK
     * {@link AudioBackend} instance. Story 130: the selector is the single
     * place that knows the {@link AudioBackend} sealed permits, so this
     * controller no longer hand-rolls a {@code switch} that fell through
     * to {@code null} for every platform backend.
     */
    private final AudioBackendSelector backendSelector;
    private final SubmissionPublisher<EngineState> engineStatePublisher = new SubmissionPublisher<>();
    private final SubmissionPublisher<AudioDeviceEvent> deviceEventPublisher =
            new SubmissionPublisher<>();
    private volatile XrunDetector xrunDetector;
    private volatile EngineState engineState = EngineState.STOPPED;
    /** Backend whose deviceEvents() we are currently subscribed to. */
    private final AtomicReference<AudioBackend> boundBackend = new AtomicReference<>();
    /** Currently-opened device — if it disappears we transition to DEVICE_LOST. */
    private final AtomicReference<DeviceId> activeDevice = new AtomicReference<>();
    /** Lifecycle-owned subscriber for the currently bound backend generation. */
    private final AtomicReference<DeviceEventSubscriber> deviceEventSubscriber =
            new AtomicReference<>();
    private final AtomicBoolean controllerClosed = new AtomicBoolean();

    /**
     * The requested endpoint (backend + device names) the currently
     * installed {@link StreamingProvision} was built from, or {@code null}
     * when no provision has been applied through
     * {@link #applyConfiguration(Request)} yet. Repeated reconfigures that
     * only change buffer size / sample rate leave the provision (and its
     * backend instances) untouched so they neither re-create backends nor
     * re-emit fallback notifications; any endpoint change rebuilds it so
     * the requested rung's {@link DeviceId} always names the configured
     * device (story 316 — the device is honoured on every open).
     */
    private ProvisionEndpoint appliedProvisionEndpoint;

    /** The (backend, input device, output device) triple a provision serves. */
    private record ProvisionEndpoint(
            String backendName, String inputDeviceName, String outputDeviceName) {
    }

    /**
     * Per-device calibration overrides (in sample frames) accepted by
     * the user via {@code LatencyCalibrationDialog} — story 217. Keyed
     * by the device-key encoding used by {@link AudioSettingsStore.Settings#deviceKey(DeviceId)}.
     * The override for the currently active device is folded into
     * {@link #reportedLatency()} so the recording pipeline shifts
     * captured clips by the calibrated value rather than by the
     * driver's (mistrusted) report. Persisted to
     * {@code ~/.daw/audio-settings.json} through
     * {@link com.benesquivelmusic.daw.sdk.audio.AudioSettingsStore}'s
     * {@code latencyOverrideFramesByDeviceKey} map.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, Integer>
            latencyOverridesByDeviceKey = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Single-thread worker that runs the
     * {@link AudioDeviceEvent.FormatChangeRequested} reopen flow off the
     * device-event thread (story 218). Coalescing of rapid-fire reset
     * requests (the user spinning a buffer-size dropdown produces many
     * events) is implemented by scheduling onto this executor with a
     * 250&nbsp;ms debounce delay; if a fresh request arrives before the
     * scheduled task runs, the previous task is cancelled and replaced.
     *
     * <p>This is <b>not</b> the audio callback thread — it is a
     * dedicated daemon worker named {@code "daw-format-change-worker"}.
     * Format-change reopens involve closing and reopening the audio
     * backend, which is far too heavy for the RT thread.</p>
     */
    private final ScheduledExecutorService formatChangeWorker =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "daw-format-change-worker");
                t.setDaemon(true);
                return t;
            });

    /**
     * The production drain seam for the {@link PerformanceMonitor}'s
     * deferred RT events (story 314 review follow-up). The RT audio
     * callback records underruns and warning-threshold transitions as
     * lock-free pending state; logging and warning-listener delivery are
     * forbidden on the audio callback, so this dedicated single-thread
     * daemon worker ({@code "daw-monitor-event-drain"}) calls
     * {@link PerformanceMonitor#publishPendingEvents()} every
     * {@value #MONITOR_EVENT_DRAIN_MILLIS}&nbsp;ms for the controller's
     * whole lifetime. That keeps underrun log lines and warning listeners
     * delivering even when every dialog is closed — the drain must never
     * depend on a UI poll such as {@link #getCpuLoadPercent()}.
     *
     * <p>Each tick looks the monitor up via
     * {@link AudioEngine#getPerformanceMonitor()} — never caches it —
     * because {@code EngineBinder.refreshPerformanceMonitor()} replaces
     * the instance after format changes. Shut down in {@link #shutdown()}.</p>
     */
    private final ScheduledExecutorService monitorEventDrain =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "daw-monitor-event-drain");
                t.setDaemon(true);
                return t;
            });

    /** Fixed delay between {@link #monitorEventDrain} ticks. */
    private static final long MONITOR_EVENT_DRAIN_MILLIS = 250L;

    /**
     * Coalescing window: when another
     * {@link AudioDeviceEvent.FormatChangeRequested} arrives within this
     * delay, the previous pending reopen is cancelled and rescheduled
     * (story 218: "the user spinning a buffer-size dropdown produces
     * multiple events").
     */
    private static final long FORMAT_CHANGE_COALESCE_MILLIS = 250L;

    /**
     * Drain deadline applied between transport stop and stream close
     * during a {@link AudioDeviceEvent.FormatChangeRequested} reopen
     * (story 218 — Pro Tools' ~200&nbsp;ms transport freeze).
     */
    private static final long FORMAT_CHANGE_DRAIN_MILLIS = 200L;

    /** Holds the currently-pending coalesced reopen task, if any. */
    private final AtomicReference<ScheduledFuture<?>> pendingFormatChange =
            new AtomicReference<>();

    /**
     * Latest pending format-change request — replaced when a new one
     * arrives during the coalescing window so the eventual reopen uses
     * the freshest proposed format / reason.
     */
    private final AtomicReference<AudioDeviceEvent.FormatChangeRequested> latestFormatChange =
            new AtomicReference<>();

    DefaultAudioEngineController(AudioEngine audioEngine, Runnable postReconfigureCallback) {
        this(audioEngine, postReconfigureCallback,
                NotificationManager.noop(),
                new IncompleteTakeStore(Paths.get(System.getProperty("java.io.tmpdir"))));
    }

    DefaultAudioEngineController(AudioEngine audioEngine,
                                 Runnable postReconfigureCallback,
                                 NotificationManager notifications,
                                 IncompleteTakeStore incompleteTakeStore) {
        this(audioEngine, postReconfigureCallback, notifications, incompleteTakeStore,
                new AudioBackendSelector());
    }

    /**
     * Test-friendly constructor that injects an {@link AudioBackendSelector}
     * — typically one whose factory map maps {@code "Mock"} (or any other
     * permitted backend name) to {@link com.benesquivelmusic.daw.sdk.audio.MockAudioBackend}.
     */
    DefaultAudioEngineController(AudioEngine audioEngine,
                                 Runnable postReconfigureCallback,
                                 NotificationManager notifications,
                                 IncompleteTakeStore incompleteTakeStore,
                                 AudioBackendSelector backendSelector) {
        this.audioEngine = Objects.requireNonNull(audioEngine, "audioEngine must not be null");
        this.tonePlayer = new TestTonePlayer();
        this.postReconfigureCallback = postReconfigureCallback;
        this.notifications = Objects.requireNonNull(notifications, "notifications must not be null");
        this.incompleteTakeStore = Objects.requireNonNull(
                incompleteTakeStore, "incompleteTakeStore must not be null");
        this.backendSelector = Objects.requireNonNull(
                backendSelector, "backendSelector must not be null");
        this.xrunDetector = createDetectorFor(audioEngine.getFormat());
        // Story 316 review: follow the OPEN stream, not the requested rung.
        // A plain Play calls AudioEngine.startAudioOutput() directly from
        // TransportController — this controller is not in that loop at all —
        // so binding device events only where WE drive the open left the
        // subscription (and activeDevice) pointed at a backend/device the
        // engine had already fallen back from: hot-unplug handling, channel
        // queries and latency overrides then all targeted the wrong endpoint.
        // The engine's stream-open seam fires on EVERY completed open with
        // the winning rung, which is the only place the winner is known.
        // An explicit lambda, not a method reference: bindBackendDeviceEvents
        // is overloaded (2-arg and 3-arg) and a method ref would silently
        // re-target if either overload's shape changed.
        audioEngine.setStreamOpenListener(
                (backend, device) -> bindBackendDeviceEvents(backend, device));
        monitorEventDrain.scheduleWithFixedDelay(this::drainMonitorEvents,
                MONITOR_EVENT_DRAIN_MILLIS, MONITOR_EVENT_DRAIN_MILLIS,
                TimeUnit.MILLISECONDS);
    }

    /**
     * One {@link #monitorEventDrain} tick — see the field javadoc. The
     * monitor is looked up fresh per tick because
     * {@code EngineBinder.refreshPerformanceMonitor()} swaps the instance
     * after format changes; caching would leave this drain pumping a
     * dead monitor.
     */
    private void drainMonitorEvents() {
        try {
            PerformanceMonitor monitor = audioEngine.getPerformanceMonitor();
            if (monitor != null) {
                monitor.publishPendingEvents();
            }
        } catch (RuntimeException e) {
            // An escaping exception would silently cancel the fixed-delay
            // schedule and kill the drain for the rest of the session —
            // log and keep ticking.
            LOG.log(Level.WARNING, "Performance-monitor event drain tick failed", e);
        }
    }

    @Override
    public synchronized String getActiveBackendName() {
        // Story 316 — honest reporting (book §2.4): the OPEN stream's
        // backend when one is open; otherwise the installed provision's
        // FIRST RUNG — the backend the next open will actually try, which
        // can differ from requestedBackendName() when the requested backend
        // failed the availability/streaming gate; otherwise "None". This can
        // never name a backend other than the one (that will be) streaming.
        Optional<String> open = audioEngine.openStreamBackendName();
        if (open.isPresent()) {
            return open.get();
        }
        StreamingProvision provision = audioEngine.getStreamingProvision();
        return provision != null ? provision.firstRung().backend().name() : BACKEND_NONE;
    }

    @Override
    public synchronized List<String> getAvailableBackendNames() {
        // Union of the adapted legacy PortAudio backend (story 316: it now
        // streams behind the SDK interface via CallbackBackendAdapter), the
        // selector's available-and-streamable SDK names, and the Java Sound
        // floor. The selector's supportsStreaming() gate keeps backends
        // whose sink() discards (WASAPI / CoreAudio / JACK today) out of
        // the offered list. Story 130: the dialog must be able to *select*
        // every backend the controller can *provision*.
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        // Story 316 review: the adapter here is an enumeration-only probe and
        // must not outlive this query. AudioBackend is AutoCloseable, and this
        // call site used to drop the instance unclosed, leaking a native
        // PortAudio handle every time the backend list was refreshed (the
        // Settings dialog, SettingsCatalogue and FirstRunWizard all read it).
        // try-with-resources releases it on BOTH branches, matching
        // tryCreatePortAudioAdapter()'s closeQuietly and withSdkBackend()'s
        // try (probe).
        try (AudioBackend probe = new CallbackBackendAdapter(new PortAudioBackend())) {
            if (probe.isAvailable()) {
                names.add("PortAudio");
            }
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "PortAudio unavailable", e);
        }
        names.addAll(backendSelector.availableBackendNames());
        names.add("Java Sound");
        return List.copyOf(names);
    }

    @Override
    public synchronized List<AudioDeviceInfo> listDevices() {
        // Open-aware: AudioEngine.getBackend() answers with the OPEN
        // stream's backend when one is open, else the provision's requested
        // rung — so the device list always matches the reported backend.
        AudioBackend backend = audioEngine.getBackend();
        if (backend == null) {
            return List.of();
        }
        try {
            return backend.listDevices();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to list audio devices", e);
            return List.of();
        }
    }

    @Override
    public synchronized List<AudioDeviceInfo> listDevices(String backendName) {
        if (backendName == null || backendName.isBlank() || BACKEND_NONE.equals(backendName)) {
            return List.of();
        }
        AudioBackend active = audioEngine.getBackend();
        if (active != null && backendName.equals(active.name())) {
            return listDevices();
        }
        // Fresh throwaway probe, enumerated and closed. Story 316: every
        // name — including the adapted "PortAudio" and the SDK "Java
        // Sound" — resolves to an SDK AudioBackend instance now.
        try (AudioBackend probe = createStreamingBackendByName(backendName, "")) {
            if (probe == null) {
                return List.of();
            }
            return probe.listDevices();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to enumerate " + backendName + " devices", e);
            return List.of();
        }
    }

    @Override
    public double getCpuLoadPercent() {
        PerformanceMonitor monitor = audioEngine.getPerformanceMonitor();
        if (monitor == null) {
            return -1.0;
        }
        // Deliberately side-effect-free: the monitor's deferred RT events
        // (underrun log lines, warning transitions, warning listeners) are
        // drained by the always-active monitorEventDrain worker, not by
        // this read. A dialog poll must never be load-bearing for event
        // delivery.
        return monitor.getCpuLoadPercent();
    }

    @Override
    public synchronized BufferSizeRange bufferSizeRange(
            String backendName, String outputDeviceName) {
        return withSdkBackend(backendName, backend -> backend.bufferSizeRange(
                deviceId(backendName, outputDeviceName)), BufferSizeRange.DEFAULT_RANGE);
    }

    @Override
    public synchronized Set<Integer> supportedSampleRates(
            String backendName, String outputDeviceName) {
        return withSdkBackend(backendName, backend -> backend.supportedSampleRates(
                deviceId(backendName, outputDeviceName)),
                Set.of(44_100, 48_000, 88_200, 96_000, 176_400, 192_000));
    }

    @Override
    public synchronized List<ClockSource> clockSources(
            String backendName, String outputDeviceName) {
        return withSdkBackend(backendName, backend -> backend.clockSources(
                deviceId(backendName, outputDeviceName)), List.of());
    }

    @Override
    public synchronized void selectClockSource(
            String backendName, String outputDeviceName, int sourceId) {
        withSdkBackend(backendName, backend -> {
            backend.selectClockSource(deviceId(backendName, outputDeviceName), sourceId);
            return null;
        }, null);
    }

    @Override
    public int getActiveThreadCount() {
        return audioEngine.getActiveThreadCount();
    }

    @Override
    public int getWorkerPoolSize() {
        return audioEngine.getWorkerPoolSize();
    }

    @Override
    public synchronized void applyConfiguration(Request request) {
        Objects.requireNonNull(request, "request must not be null");
        LOG.info("Applying audio configuration: " + request);

        try {
            boolean wasOpen = audioEngine.isStreamOpen();
            audioEngine.stopAudioOutput();
            audioEngine.stop();

            AudioFormat previous = audioEngine.getFormat();
            AudioFormat updated = new AudioFormat(
                    request.sampleRate().getHz(),
                    previous.channels(),
                    request.bitDepth(),
                    request.bufferFrames());
            audioEngine.setFormat(updated);

            // Story 125: apply the new worker-pool size to the engine. The
            // pool is locked at start() so the swap must happen between
            // stop() above and the start{,AudioOutput}() calls below.
            AudioEngineSettings previousSettings = audioEngine.getEngineSettings();
            if (previousSettings.workerPoolSize() != request.workerPoolSize()) {
                audioEngine.setEngineSettings(
                        previousSettings.withWorkerPoolSize(request.workerPoolSize()));
            }

            // Buffer size or sample rate may have changed — rebuild the
            // xrun detector so its deadline matches the new format, and
            // reset the counter per the issue's reset-on-reconfigure rule.
            XrunDetector previousDetector = this.xrunDetector;
            this.xrunDetector = createDetectorFor(updated);
            if (previousDetector != null) {
                previousDetector.close();
            }

            // Story 316: the engine streams through ONE provision slot. Rebuild
            // the provision only when the requested endpoint (backend + device
            // names) actually changed, so repeated buffer-size / sample-rate
            // reconfigures neither re-create backend instances nor re-emit
            // fallback notifications. Any endpoint change rebuilds it — the
            // requested rung's DeviceId is how the configured device is
            // honoured on EVERY subsequent open (book §3.2, never index 0).
            ProvisionEndpoint endpoint = new ProvisionEndpoint(
                    request.backendName(), request.inputDeviceName(), request.outputDeviceName());
            StreamingProvision provision = audioEngine.getStreamingProvision();
            if (provision == null || !endpoint.equals(appliedProvisionEndpoint)) {
                provision = buildStreamingProvision(request);
                installProvision(provision);
                appliedProvisionEndpoint = endpoint;
            }

            // Device hot-plug watch follows the requested (first) rung —
            // the backend the user chose is the one whose device events
            // matter. Correct HERE because the engine is stopped: there is
            // no open stream to disagree with, so the rung the next open
            // will try first is the honest thing to watch. Any open that
            // follows re-points this itself via the engine's stream-open
            // seam (story 316 review), including a bare Play straight from
            // TransportController.
            bindBackendDeviceEvents(
                    provision.firstRung().backend(), provision.firstRung().device());

            if (wasOpen) {
                // Story 316 review — the restart is guarded because it can now
                // genuinely fail: Java Sound stopped degrading to a silent
                // no-output stream, so an exhausted fallback ladder propagates
                // (openLadder rethrows the FIRST rung's failure), and
                // requireQuiescedPump() throws when a previous render pump has
                // not exited. Deliberately NOT rethrown: the configuration
                // itself WAS applied (format, worker pool, provision, device
                // watch) — only the restart failed, so the user keeps the new
                // settings and simply re-arms transport. Mirrors
                // runFormatChangeReopen(): notify, then always end up in
                // STOPPED so the user can re-arm transport even after a
                // failed reopen.
                try {
                    audioEngine.startAudioOutput();
                    setEngineState(EngineState.RUNNING);
                    // No re-point needed here any more (story 316 review):
                    // startAudioOutput() fired the engine's stream-open seam
                    // synchronously, and this controller's listener already
                    // rebound the whole device-event subscription — not just
                    // activeDevice, which is all the line that used to sit
                    // here ever did — to the rung that actually WON. That
                    // rebind now happens for EVERY open, including a bare
                    // Play issued straight from TransportController, which
                    // never reaches this method at all.
                } catch (RuntimeException openFailure) {
                    LOG.log(Level.SEVERE,
                            "Audio output could not be restarted after reconfigure",
                            openFailure);
                    setEngineState(EngineState.STOPPED);
                    try {
                        notifications.notify("Audio output could not be started: "
                                + openFailure.getMessage());
                    } catch (RuntimeException e) {
                        LOG.log(Level.WARNING, "NotificationManager rejected message", e);
                    }
                }
            } else {
                audioEngine.start();
                setEngineState(EngineState.STOPPED);
            }
        } catch (RuntimeException e) {
            // Story 316 review: anything else escaping this method used to
            // strand the flow — neither the terminal state nor the
            // post-reconfigure callback ran, so the settings dialog stayed
            // disabled forever. Land in STOPPED, tell the user, then rethrow
            // so callers still see the failure.
            setEngineState(EngineState.STOPPED);
            LOG.log(Level.SEVERE, "Audio configuration could not be applied", e);
            try {
                notifications.notify("Audio configuration could not be applied: "
                        + e.getMessage());
            } catch (RuntimeException notifyFailure) {
                LOG.log(Level.WARNING, "NotificationManager rejected message", notifyFailure);
            }
            throw e;
        } finally {
            // In the finally block so the dialog re-enables on EVERY path,
            // including the rethrown failure above.
            if (postReconfigureCallback != null) {
                try {
                    postReconfigureCallback.run();
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "Post-reconfigure callback failed", e);
                }
            }
        }
    }

    @Override
    public synchronized void playTestTone(String outputDeviceName) {
        tonePlayer.play(outputDeviceName == null ? "" : outputDeviceName);
    }

    @Override
    public synchronized void applyMixPrecision(MixPrecision precision) {
        Objects.requireNonNull(precision, "precision must not be null");
        Mixer mixer = audioEngine.getMixer();
        if (mixer != null) {
            mixer.setMixPrecision(precision);
            LOG.info("Mix precision set to " + precision);
        }
    }

    @Override
    public synchronized void applySrcQuality(
            com.benesquivelmusic.daw.sdk.audio.SampleRateConverter.QualityTier tier) {
        Objects.requireNonNull(tier, "tier must not be null");
        var previous = audioEngine.getSrcQualityTier();
        audioEngine.setSrcQualityTier(tier);
        // Invalidate cached conversions when the tier changes — they
        // were rendered with the previous filter kernel and would
        // otherwise leak across a quality change.
        if (previous != tier) {
            audioEngine.getSampleRateConversionCache().invalidateAll();
        }
        LOG.info("SRC quality set to " + tier);
    }

    @Override
    public Flow.Publisher<XrunEvent> xrunEvents() {
        return xrunDetector.xrunEvents();
    }

    @Override
    public Flow.Publisher<AudioDeviceEvent> deviceEvents() {
        return deviceEventPublisher;
    }

    @Override
    public EngineState engineState() {
        return engineState;
    }

    @Override
    public Flow.Publisher<EngineState> engineStateEvents() {
        return engineStatePublisher;
    }

    @Override
    public com.benesquivelmusic.daw.sdk.audio.RoundTripLatency reportedLatency() {
        com.benesquivelmusic.daw.sdk.audio.RoundTripLatency driver = driverReportedLatency();
        Integer override = activeDeviceOverride();
        if (override == null) {
            return driver;
        }
        // Fold override into a single-component RoundTripLatency so the
        // total equals the calibrated value while keeping the record
        // shape recording pipelines already understand.
        return new com.benesquivelmusic.daw.sdk.audio.RoundTripLatency(override, 0, 0);
    }

    @Override
    public com.benesquivelmusic.daw.sdk.audio.RoundTripLatency driverReportedLatency() {
        // Open-aware (story 316): the OPEN stream's backend when one is
        // open, else the provision's requested rung. Backends without a
        // native latency query — ASIO until its query story lands — report
        // RoundTripLatency.UNKNOWN honestly instead of a number from a
        // stream that is not actually playing.
        AudioBackend backend = audioEngine.getBackend();
        if (backend == null) {
            return com.benesquivelmusic.daw.sdk.audio.RoundTripLatency.UNKNOWN;
        }
        return backend.reportedLatency();
    }

    @Override
    public Optional<Integer> latencyOverrideFrames() {
        return Optional.ofNullable(activeDeviceOverride());
    }

    @Override
    public void setLatencyOverrideFrames(Optional<Integer> frames) {
        Objects.requireNonNull(frames, "frames must not be null");
        if (frames.isPresent() && frames.get() < 0) {
            throw new IllegalArgumentException(
                    "override frames must be >= 0: " + frames.get());
        }
        DeviceId device = activeDevice.get();
        if (device == null) {
            // No active device — nothing to key by; ignore silently.
            return;
        }
        String key = com.benesquivelmusic.daw.sdk.audio.AudioSettingsStore
                .Settings.deviceKey(device);
        if (frames.isPresent()) {
            latencyOverridesByDeviceKey.put(key, frames.get());
        } else {
            latencyOverridesByDeviceKey.remove(key);
        }
        // The recording pipeline is owned by the transport controller and
        // queries reportedLatency() at recording-start, so the next
        // recording will pick up the new value automatically. No live
        // mutation is required from this controller.
    }

    /**
     * Returns the override for the currently active device, or null if
     * no override is set for this device.
     */
    private Integer activeDeviceOverride() {
        DeviceId device = activeDevice.get();
        if (device == null) {
            return null;
        }
        String key = com.benesquivelmusic.daw.sdk.audio.AudioSettingsStore
                .Settings.deviceKey(device);
        return latencyOverridesByDeviceKey.get(key);
    }

    /**
     * Subscribes to {@code backend.deviceEvents()} so the controller
     * can transition to {@link EngineState#DEVICE_LOST} when
     * {@code activeDevice} disappears, persist the in-flight recording
     * take, notify the user, and automatically reopen the stream when
     * the matching device returns.
     *
     * <p>Calling this method again replaces any previously bound
     * backend; the previous subscription is cancelled.</p>
     */
    @Override
    public synchronized void bindBackendDeviceEvents(
            AudioBackend backend, DeviceId activeDevice) {
        Objects.requireNonNull(backend, "backend must not be null");
        Objects.requireNonNull(activeDevice, "activeDevice must not be null");
        bindBackendDeviceEvents(backend, activeDevice, backend.deviceEvents());
    }

    synchronized void bindBackendDeviceEvents(
            AudioBackend backend,
            DeviceId activeDevice,
            Flow.Publisher<AudioDeviceEvent> events) {
        Objects.requireNonNull(backend, "backend must not be null");
        Objects.requireNonNull(activeDevice, "activeDevice must not be null");
        Objects.requireNonNull(events, "events must not be null");
        DeviceEventSubscriber next = new DeviceEventSubscriber();
        DeviceEventSubscriber previous = deviceEventSubscriber.getAndSet(next);
        if (previous != null) {
            previous.close();
        }
        boundBackend.set(backend);
        this.activeDevice.set(activeDevice);
        if (controllerClosed.get()) {
            clearBoundBackendDeviceEvents();
            return;
        }
        events.subscribe(next);
    }

    private void clearBoundBackendDeviceEvents() {
        DeviceEventSubscriber previous = deviceEventSubscriber.getAndSet(null);
        if (previous != null) {
            previous.close();
        }
        boundBackend.set(null);
        activeDevice.set(null);
    }

    /**
     * Routes a sample-rate change to the SDK backend identified by
     * {@code backendName} (story 220). Resolves the backend through
     * the {@link AudioBackendSelector} — the same lookup used by
     * {@link #listDevices(String)} — so the call reaches the
     * {@link com.benesquivelmusic.daw.sdk.audio.AsioBackend}'s
     * {@code ASIOSetSampleRate} bridge. Backends that do not support
     * sample-rate selection (legacy {@code "PortAudio"} /
     * {@code "Java Sound"}, or any backend whose default
     * {@link AudioBackend#setSampleRate(DeviceId, double)} throws
     * {@link UnsupportedOperationException}) are treated as a no-op so
     * the dialog flow still falls through to the model-only update.
     *
     * <p>{@link com.benesquivelmusic.daw.sdk.audio.AudioBackendException}
     * is rethrown so the dialog can suppress the model update and
     * surface a notification, matching the issue's contract.</p>
     */
    @Override
    public synchronized void setSampleRate(
            String backendName, String outputDeviceName, double rate) {
        if (backendName == null || backendName.isBlank() || BACKEND_NONE.equals(backendName)) {
            return;
        }
        // Skip the legacy backend names — those backends negotiate the
        // rate at stream open and have no separate setter; the dialog's
        // reconfigure step will reopen them.
        if ("PortAudio".equals(backendName) || "Java Sound".equals(backendName)) {
            return;
        }
        try (AudioBackend sdk = createSdkBackendByName(backendName)) {
            if (sdk == null) {
                return;
            }
            // Story 316 review: route through deviceId() like every sibling
            // method. A blank outputDeviceName means "the backend's default
            // device" — not an error — and hand-rolling
            // new DeviceId(backendName, "") tripped DeviceId's compact
            // constructor ("name must not be blank"), whose
            // IllegalArgumentException is not caught below and aborted the
            // whole sample-rate flow. deviceId() also normalizes the
            // "WASAPI (Exclusive)" display name to the backend's real name.
            DeviceId deviceId = deviceId(backendName, outputDeviceName);
            sdk.setSampleRate(deviceId, rate);
        } catch (UnsupportedOperationException ignored) {
            // Backend does not support live sample-rate selection;
            // fall through and let the dialog persist the new rate.
        }
    }

    /** Returns the device this controller is currently watching, or empty when none is bound. */
    Optional<DeviceId> getActiveDevice() {
        return Optional.ofNullable(activeDevice.get());
    }

    /**
     * Captures one block of recorded input into the
     * {@link IncompleteTakeStore} so it can be flushed to disk if the
     * device disappears mid-take. Production wiring (the recording
     * subsystem) calls this from the audio callback.
     */
    void captureRecordingFrames(float[][] inputBuffer, int numFrames) {
        incompleteTakeStore.appendCapturedFrames(inputBuffer, numFrames);
    }

    /** Visible for tests. */
    IncompleteTakeStore getIncompleteTakeStore() {
        return incompleteTakeStore;
    }

    private void setEngineState(EngineState newState) {
        EngineState previous = this.engineState;
        if (previous == newState) {
            return;
        }
        this.engineState = newState;
        // Use offer() instead of submit() to avoid blocking under
        // backpressure — a slow UI subscriber must not stall the
        // device-event thread.
        if (!engineStatePublisher.isClosed()) {
            engineStatePublisher.offer(newState, (subscriber, dropped) -> false);
        }
        LOG.info("Engine state " + previous + " -> " + newState);
    }

    private synchronized void onDeviceRemoved(DeviceId removed) {
        DeviceId active = activeDevice.get();
        if (active == null || !matches(active, removed)) {
            // Some other device went away — nothing to do.
            return;
        }
        LOG.warning("Active audio device removed: " + removed);
        // Halt the render thread cleanly. Best-effort; never let an
        // exception from the device-event thread escape and crash the
        // engine — the issue requires "no exceptions on the audio thread".
        try {
            audioEngine.stopAudioOutput();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to stop audio output during disconnect", e);
        }
        try {
            audioEngine.stop();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to stop engine during disconnect", e);
        }
        // Persist any in-progress recording take so the user can review
        // it after the device returns.
        try {
            incompleteTakeStore.flushIfNotEmpty(removed, audioEngine.getFormat());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to persist incomplete take", e);
        }
        setEngineState(EngineState.DEVICE_LOST);
        try {
            notifications.notify("Audio device disconnected — playback paused");
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "NotificationManager rejected message", e);
        }
    }

    private synchronized void onDeviceArrived(DeviceId arrived) {
        if (engineState != EngineState.DEVICE_LOST) {
            return;
        }
        DeviceId active = activeDevice.get();
        if (active == null || !matches(active, arrived)) {
            return;
        }
        LOG.info("Lost audio device returned: " + arrived);
        // Story 316 — reopen through the ENGINE's one open/close seam, never
        // by calling backend.open(...) directly: startAudioOutput() walks
        // the provision ladder (honouring the configured DeviceId) and then
        // pauseAudioOutput() parks the stream open-but-not-rendering, which
        // is the historical post-arrival state — device proven usable, user
        // re-arms transport manually. A later Play resumes on the same open
        // stream. With no provision installed there is nothing to reopen.
        if (audioEngine.getStreamingProvision() != null && !audioEngine.isStreamOpen()) {
            try {
                audioEngine.startAudioOutput();
                audioEngine.pauseAudioOutput();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Failed to reopen audio stream after reconnect", e);
                try {
                    notifications.notify("Audio device reconnected but reopen failed: " + e.getMessage());
                } catch (RuntimeException ignored) { /* best-effort */ }
                return;
            }
        }
        // Resume in STOPPED state — the user re-arms transport manually,
        // so the recovered take can be reviewed first.
        setEngineState(EngineState.STOPPED);
        try {
            notifications.notify("Audio device reconnected — review recovered take and re-arm transport");
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "NotificationManager rejected message", e);
        }
    }

    /**
     * Handles a driver-initiated {@link AudioDeviceEvent.FormatChangeRequested}
     * by scheduling a coalesced reopen on the
     * {@link #formatChangeWorker} (story 218). Runs on the device-event
     * thread; off-loads the heavy work — stop transport, drain, close,
     * re-query capabilities, reopen — to the worker so the publisher
     * thread is never blocked.
     *
     * <p>If another {@code FormatChangeRequested} arrives within
     * {@value #FORMAT_CHANGE_COALESCE_MILLIS}&nbsp;ms, the pending task
     * is cancelled and rescheduled — the typical case where the user
     * is spinning a buffer-size dropdown in the driver panel. The
     * eventual reopen always uses the freshest proposed format /
     * reason.</p>
     *
     * @param requested the request to handle; must not be null
     */
    private void onFormatChangeRequested(AudioDeviceEvent.FormatChangeRequested requested) {
        DeviceId active = activeDevice.get();
        if (active == null || !matches(active, requested.device())) {
            // Some other device — nothing to do.
            return;
        }
        LOG.info("FormatChangeRequested received for " + requested.device()
                + " reason=" + requested.reason()
                + " proposedFormat=" + requested.proposedFormat());
        // Replace the latest pending request so the worker uses the
        // freshest payload when it eventually runs.
        latestFormatChange.set(requested);
        // Coalesce: cancel any in-flight scheduled reopen, then schedule
        // a fresh one 250 ms out. Deliberately not interruptIfRunning —
        // a reopen that has already begun should be allowed to finish.
        ScheduledFuture<?> previous = pendingFormatChange.getAndSet(null);
        if (previous != null) {
            previous.cancel(false);
        }
        ScheduledFuture<?> task;
        try {
            task = formatChangeWorker.schedule(this::runFormatChangeReopen,
                    FORMAT_CHANGE_COALESCE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            // Executor was shut down (controller closed) — nothing to do.
            LOG.log(Level.FINE, "Format-change worker rejected task", e);
            return;
        }
        pendingFormatChange.set(task);
    }

    /**
     * Runs the coalesced reopen flow on
     * {@link #formatChangeWorker}. Never invoked on the audio callback
     * thread or the device-event thread.
     */
    private void runFormatChangeReopen() {
        AudioDeviceEvent.FormatChangeRequested requested = latestFormatChange.getAndSet(null);
        if (requested == null) {
            // Coalesced into a later task that already ran — nothing to do.
            return;
        }
        DeviceId active = activeDevice.get();
        if (active == null || !matches(active, requested.device())) {
            return;
        }
        try {
            performFormatChangeReopen(active, requested);
        } catch (RuntimeException e) {
            // Never let the worker thread die because of one bad reopen —
            // the next event must still be handleable.
            LOG.log(Level.WARNING, "Format-change reopen failed", e);
            try {
                notifications.notify("Audio engine reconfiguration failed: " + e.getMessage());
            } catch (RuntimeException ignored) { /* best-effort */ }
            // Always end up in STOPPED so the user can re-arm transport
            // even after a failed reopen.
            setEngineState(EngineState.STOPPED);
        }
    }

    private synchronized void performFormatChangeReopen(
            DeviceId active, AudioDeviceEvent.FormatChangeRequested requested) {
        AudioBackend backend = boundBackend.get();
        AudioFormat currentFormat = audioEngine.getFormat();

        // Sample-rate change is treated specially — story 126's SRC is
        // inserted at the device boundary so the project session rate
        // does not change.
        Optional<com.benesquivelmusic.daw.sdk.audio.AudioFormat> proposed = requested.proposedFormat();
        boolean isSampleRateChange = requested.reason() instanceof FormatChangeReason.SampleRateChange;
        boolean rateActuallyDiffers = proposed.isPresent()
                && Double.compare(proposed.get().sampleRate(), currentFormat.sampleRate()) != 0;

        try {
            notifications.notify("Reconfiguring audio engine…");
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "NotificationManager rejected message", e);
        }
        setEngineState(EngineState.RECONFIGURING);

        // Whether a stream was open before this reopen — mirrors
        // applyConfiguration: only a previously-open stream is restored
        // after the format change (story 316, engine-seam routing).
        boolean wasOpen = audioEngine.isStreamOpen();

        // 1. Pause transport. Best-effort; we are NOT on the RT thread.
        try {
            audioEngine.stopAudioOutput();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to stop audio output during format-change reopen", e);
        }
        try {
            audioEngine.stop();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to stop engine during format-change reopen", e);
        }

        // 2. Drain — short, fixed-deadline pause to give any in-flight
        //    callback time to return. We are not on the RT thread, so
        //    Thread.sleep is acceptable here. Pro Tools' ~200ms freeze
        //    is the reference behaviour.
        try {
            Thread.sleep(FORMAT_CHANGE_DRAIN_MILLIS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        // 3. If recording was active, behave like DeviceRemoved for the
        //    in-flight take — flush partially captured frames so the
        //    user can review them after the reopen.
        try {
            incompleteTakeStore.flushIfNotEmpty(active, audioEngine.getFormat());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to persist incomplete take during reopen", e);
        }

        // 4. Re-query capabilities so the new open call uses values the
        //    backend actually allows. The SDK contract for these methods
        //    permits returning defaults when the native shim is absent;
        //    we deliberately call them anyway so the production wiring
        //    exercises the same path the FFM-bound version will.
        if (backend != null) {
            try {
                BufferSizeRange range = backend.bufferSizeRange(active);
                Set<Integer> rates = backend.supportedSampleRates(active);
                LOG.fine("Re-queried capabilities for " + active
                        + ": bufferSizeRange=" + range + " sampleRates=" + rates);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Failed to re-query backend capabilities", e);
            }
        }

        // 5. Compute the reopen format. If proposedFormat is present we
        //    apply it (clamped where we know better), otherwise reopen
        //    with the existing settings.
        AudioFormat reopenFormat;
        if (proposed.isPresent() && !isSampleRateChange) {
            com.benesquivelmusic.daw.sdk.audio.AudioFormat sdkFmt = proposed.get();
            reopenFormat = new AudioFormat(
                    sdkFmt.sampleRate(),
                    currentFormat.channels(),
                    sdkFmt.bitDepth() > 0 ? sdkFmt.bitDepth() : currentFormat.bitDepth(),
                    deriveBufferFrames(requested.reason(), currentFormat, backend, active));
        } else if (proposed.isPresent() /* sample-rate change — keep project rate */) {
            reopenFormat = currentFormat;
        } else {
            reopenFormat = currentFormat;
        }

        try {
            audioEngine.setFormat(reopenFormat);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to set new format on audio engine", e);
        }

        // Rebuild the xrun detector so its deadline matches the new
        // format — mirrors applyConfiguration() behaviour.
        XrunDetector previousDetector = this.xrunDetector;
        this.xrunDetector = createDetectorFor(reopenFormat);
        if (previousDetector != null) {
            previousDetector.close();
        }

        // 6. Reopen through the ENGINE's one open/close seam (story 316) —
        //    never by calling backend.open(...) directly. Step 1 already
        //    closed the stream via stopAudioOutput(); startAudioOutput()
        //    re-walks the provision ladder at the new format, honouring the
        //    configured DeviceId. Only a stream that was open before the
        //    change is restored, mirroring applyConfiguration.
        boolean streamRestored = false;
        if (wasOpen) {
            try {
                audioEngine.startAudioOutput();
                streamRestored = true;
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Failed to reopen audio stream after format change", e);
            }
        }

        // 7. Surface the SRC fallback notification when the driver moved
        //    to a rate that differs from the project's session rate.
        //    Story 126 — the engine-owned RenderPipeline consults the
        //    SampleRateConversionCache at each clip read AND at the
        //    device boundary so the project keeps its authored rate even
        //    when the driver moves to a different rate. Invalidate the
        //    cache here because a session-rate change makes every cached
        //    conversion stale (they all targeted the previous rate).
        //
        //    This must happen BEFORE the STOPPED transition (step 8):
        //    observers waiting on STOPPED rely on a happens-before
        //    guarantee that the user-facing notification AND the cache
        //    invalidation are already in effect once STOPPED is seen.
        if (isSampleRateChange && rateActuallyDiffers) {
            int newRateKhz = (int) Math.round(proposed.get().sampleRate() / 1000.0);
            audioEngine.getSampleRateConversionCache().invalidateAll();
            try {
                notifications.notify(
                        "Driver moved to " + newRateKhz
                                + "kHz — SRC inserted at device boundary. "
                                + "Pick the matching project rate from the driver "
                                + "panel to avoid SRC.");
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "NotificationManager rejected message", e);
            }
        } else {
            try {
                notifications.notify("Audio engine reconfigured");
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "NotificationManager rejected message", e);
            }
        }

        // 8. Run the post-reconfigure callback — the same hook
        //    applyConfiguration() fires. MainController wires it to
        //    reinstall the per-track CPU budget enforcer AND to
        //    EngineBinder.refreshPerformanceMonitor(); the
        //    PerformanceMonitor's per-block budget is fixed at
        //    construction, so a driver-originated buffer/format change
        //    that skipped this hook would leave the monitor reporting
        //    stale CPU load and false underruns. Runs on this
        //    best-effort path regardless of whether the backend reopen
        //    in step 6 logged a failure — setFormat already happened
        //    either way. Must precede the STOPPED transition (step 9)
        //    so observers gating on STOPPED see the refreshed monitor.
        if (postReconfigureCallback != null) {
            try {
                postReconfigureCallback.run();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Post-reconfigure callback failed", e);
            }
        }

        // 9. Terminal state. A stream that was open before the change and
        //    was successfully restored in step 6 reports RUNNING (mirroring
        //    applyConfiguration's wasOpen branch); otherwise the engine
        //    resumes in STOPPED — transport does NOT auto-start, the user
        //    re-arms manually, identical to the onDeviceArrived convention.
        //    This is the final step so that any observer waiting on the
        //    terminal state has a reliable happens-before guarantee that
        //    steps 7-8 (notification + cache invalidation + post-
        //    reconfigure callback) completed.
        setEngineState(streamRestored ? EngineState.RUNNING : EngineState.STOPPED);
    }

    /**
     * Picks the buffer-frame count to use when reopening after a
     * {@link AudioDeviceEvent.FormatChangeRequested}.
     *
     * <p>The SDK {@code AudioFormat} payload does not carry buffer frames,
     * so a request whose reason is {@link FormatChangeReason.BufferSizeChange}
     * reads the new frame count from
     * {@link FormatChangeReason.BufferSizeChange#newBufferFrames()} when
     * known, or falls back to the backend's
     * {@link BufferSizeRange#preferred()} value. For all other reasons the
     * current engine buffer size is retained to avoid changing multiple
     * variables at once.</p>
     */
    private static int deriveBufferFrames(
            FormatChangeReason reason,
            AudioFormat current,
            AudioBackend backend,
            DeviceId device) {
        if (reason instanceof FormatChangeReason.BufferSizeChange bsc) {
            if (bsc.newBufferFrames() > 0) {
                return bsc.newBufferFrames();
            }
            // Driver signal didn't carry a concrete frame count — fall
            // back to the backend's preferred buffer size.
            if (backend != null) {
                try {
                    return backend.bufferSizeRange(device).preferred();
                } catch (RuntimeException e) {
                    LOG.log(Level.FINE, "Failed to query bufferSizeRange for fallback", e);
                }
            }
        }
        return current.bufferSize();
    }

    private static boolean matches(DeviceId active, DeviceId other) {
        if (active.equals(other)) {
            return true;
        }
        // Fall back to friendly-name match across backends — vendor +
        // product + serial cross-checks would happen in a more advanced
        // identity-matching layer, but the issue allows friendly-name
        // fallback when serial information is unavailable.
        return active.name().equals(other.name());
    }

    private final class DeviceEventSubscriber
            implements Flow.Subscriber<AudioDeviceEvent>, AutoCloseable {
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public void onSubscribe(Flow.Subscription newSubscription) {
            Objects.requireNonNull(newSubscription, "subscription must not be null");
            if (closed.get() || controllerClosed.get()
                    || deviceEventSubscriber.get() != this
                    || !subscription.compareAndSet(null, newSubscription)) {
                newSubscription.cancel();
                return;
            }
            if ((closed.get() || controllerClosed.get()
                    || deviceEventSubscriber.get() != this)
                    && subscription.compareAndSet(newSubscription, null)) {
                newSubscription.cancel();
                return;
            }
            newSubscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(AudioDeviceEvent event) {
            if (closed.get() || controllerClosed.get()
                    || deviceEventSubscriber.get() != this) {
                return;
            }
            if (!deviceEventPublisher.isClosed()) {
                deviceEventPublisher.offer(event, (subscriber, dropped) -> false);
            }
            // Switch on sealed AudioDeviceEvent — exhaustive over the four
            // permitted records. JEP 441 (final, JDK 21).
            switch (event) {
                case AudioDeviceEvent.DeviceRemoved removed -> onDeviceRemoved(removed.device());
                case AudioDeviceEvent.DeviceArrived arrived -> onDeviceArrived(arrived.device());
                case AudioDeviceEvent.DeviceFormatChanged changed ->
                        LOG.info("Device format changed for " + changed.device() + ": " + changed.newFormat());
                case AudioDeviceEvent.FormatChangeRequested requested ->
                        onFormatChangeRequested(requested);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            if (!closed.get()) {
                LOG.log(Level.WARNING, "Device-event publisher error", throwable);
            }
        }

        @Override
        public void onComplete() {
            close();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            Flow.Subscription current = subscription.getAndSet(null);
            if (current != null) {
                try {
                    current.cancel();
                } catch (RuntimeException ignored) {
                    // best-effort cancellation
                }
            }
        }
    }

    /** Returns the active SDK backend's serialized native control-panel action. */
    @Override
    public synchronized Optional<Runnable> openControlPanel() {
        AudioBackend backend = audioEngine.getBackend();
        if (backend == null) {
            return Optional.empty();
        }
        return backend.openControlPanel().map(action -> () -> {
            synchronized (DefaultAudioEngineController.this) {
                action.run();
            }
        });
    }

    /**
     * Exposes the detector for tests and for engine-internal wiring
     * that records per-buffer timing.
     */
    XrunDetector getXrunDetector() {
        return xrunDetector;
    }

    /**
     * Closes background resources owned by this controller.
     *
     * <p>The provision's backend instances are closed here — the controller
     * owns every backend's lifecycle (story 316) — but only after the engine
     * CONFIRMS the render pump quiesced (story 316 review). A
     * {@link AudioEngine#stopAudioOutput()} whose bounded join timed out
     * deliberately leaves the backend open precisely because the pump may
     * still be inside {@code sink} / {@code awaitSinkCapacity}; closing every
     * provision backend anyway would release native state under that live
     * thread. The stop is retried once — it joins for up to a second and is
     * documented as retryable — and the close is otherwise skipped: see
     * {@link #closeProvisionBackendsOnceQuiesced()}.</p>
     */
    synchronized void shutdown() {
        controllerClosed.set(true);
        tonePlayer.close();
        clearBoundBackendDeviceEvents();
        // Nothing may call back into a shut-down controller, so the engine's
        // stream-open seam is cleared alongside the device-event
        // subscription it drives (story 316 review). bindBackendDeviceEvents
        // already no-ops on controllerClosed, but leaving a dead listener
        // installed on a live engine is not the honest lifecycle.
        audioEngine.setStreamOpenListener(null);
        engineStatePublisher.close();
        deviceEventPublisher.close();
        ScheduledFuture<?> pending = pendingFormatChange.getAndSet(null);
        if (pending != null) {
            pending.cancel(false);
        }
        formatChangeWorker.shutdownNow();
        monitorEventDrain.shutdownNow();
        XrunDetector detector = this.xrunDetector;
        if (detector != null) {
            detector.close();
        }
        closeProvisionBackendsOnceQuiesced();
    }

    /**
     * Stops any open stream and then closes every provision backend instance
     * to release native resources on exit — but only once the engine reports
     * the render pump CONFIRMED quiesced (story 316 review).
     *
     * <p>{@link AudioEngine#stopAudioOutput()} returns {@code false} for
     * exactly one condition: its bounded join timed out, so a thread may
     * still be inside {@code processBlock} and therefore inside the
     * backend's {@code sink} / {@code awaitSinkCapacity}. That stop
     * intentionally leaves the backend open; closing the provision's
     * instances anyway would free native state under the live
     * {@code engine-render-pump} thread — {@code AsioBackend}'s
     * {@code AsioBufferSwitchShim}, whose shared {@link java.lang.foreign.Arena}
     * backs the {@code bufferSwitch} upcall stub the driver calls into; a
     * Java Sound {@code SourceDataLine}; a PortAudio {@code Pa_} stream
     * handle. The stop is retried ONCE (it joins for up to a second and
     * documents itself as retryable, so this is a real second chance and not
     * an unbounded shutdown hang); if quiescence still cannot be confirmed
     * the instances are deliberately left open and the OS reclaims them at
     * process exit, which is strictly safer than a native use-after-free.</p>
     *
     * <p>A thrown stop counts as NOT quiesced: an exception says nothing
     * about whether the pump exited, and the safe reading of "unknown" here
     * is the one that does not free anything.</p>
     */
    private void closeProvisionBackendsOnceQuiesced() {
        boolean quiesced = stopAudioOutputForShutdown();
        if (!quiesced) {
            quiesced = stopAudioOutputForShutdown();
        }
        if (!quiesced) {
            LOG.severe("Audio shutdown: the render pump did not join in time, so the"
                    + " provision's backend instances are deliberately NOT closed — a"
                    + " close would release native state (an ASIO bufferSwitch upcall"
                    + " arena, a Java Sound SourceDataLine, a PortAudio stream handle)"
                    + " underneath a still-live engine-render-pump thread. The OS"
                    + " reclaims them at process exit, which is strictly safer than a"
                    + " native use-after-free.");
            return;
        }
        // No incoming provision on the shutdown path: every instance goes.
        closeProvisionBackends(audioEngine.getStreamingProvision(), null);
    }

    /**
     * One bounded stop attempt for {@link #closeProvisionBackendsOnceQuiesced()}.
     *
     * @return the engine's own quiescence verdict, or {@code false} when the
     *         stop threw — a failure that leaves pump quiescence unknown
     */
    private boolean stopAudioOutputForShutdown() {
        try {
            return audioEngine.stopAudioOutput();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to stop audio output during shutdown", e);
            return false;
        }
    }

    private static XrunDetector createDetectorFor(AudioFormat format) {
        return new XrunDetector(format.sampleRate(), format.bufferSize());
    }

    /**
     * Installs the blank-name default {@link StreamingProvision} —
     * PortAudio (adapted behind the SDK interface) when available, else
     * Java Sound — exactly the default the retired legacy backend factory
     * produced, now expressed as an explicit ladder on the engine's one
     * streaming slot (story 316).
     * Called by {@code MainController} at startup, before the persisted
     * audio settings refine the provision via
     * {@link #applyConfiguration(Request)}.
     */
    synchronized void installDefaultProvision() {
        installProvision(buildDefaultProvision("", ""));
    }

    /**
     * Builds the {@link StreamingProvision} for the given request (story
     * 316) — the requested backend as the first rung plus the explicit
     * emergency ladder the engine walks when that backend cannot open:
     *
     * <ul>
     *   <li>Requested rung: resolved by name — {@code "PortAudio"} becomes
     *       a {@link CallbackBackendAdapter} over {@link PortAudioBackend};
     *       {@code "Java Sound"} is the SDK {@link JavaxSoundBackend};
     *       every other name routes through the
     *       {@link AudioBackendSelector}. Its {@link DeviceId} is the
     *       configured output device (blank &rarr; the backend's default),
     *       honoured on every open.</li>
     *   <li>Fallback rungs: the PortAudio adapter when available (and not
     *       itself the requested backend), then Java Sound — each on its
     *       own DEFAULT device: falling back is an emergency, the default
     *       device is the honest choice, and the engine's
     *       {@code BackendFallbackEvent} names it.</li>
     * </ul>
     *
     * <p>If the requested backend is unavailable or its streaming path is
     * not implemented ({@link AudioBackend#supportsStreaming()} {@code ==
     * false} — WASAPI / CoreAudio / JACK today), the probe is closed, a
     * single {@link NotificationManager} warning of the form
     * {@code "ASIO not available — falling back to <head rung>"} — naming
     * the fallback ladder's ACTUAL head (PortAudio when available, else
     * Java Sound) — is emitted so the user is never silently routed
     * elsewhere, and the ladder starts at that head. The provision's
     * {@code requestedBackendName} <em>and</em> {@code requestedDevice} both
     * stay the user's request — passed explicitly on that path rather than
     * defaulted from the (fallback) first rung — so the engine's
     * {@code BackendFallbackEvent}s name the true requested endpoint and
     * never pair the requested backend with a device the user never chose.
     * The rejection is additionally carried as a single
     * {@link StreamingProvision#pendingFailedHopCauses() pending failed hop}
     * (story 316 review) naming WHY the gate rejected it — unavailable on
     * this host versus available with an unimplemented streaming path — so
     * the engine publishes a {@code BackendFallbackEvent} for it too once
     * the winning rung is known; without that, a fallback head opening on
     * the first try published nothing at all and the substitution was
     * visible only as a transient notification.
     * A blank / {@link #BACKEND_NONE} / unknown name yields the default
     * ladder (PortAudio when available, else Java Sound).</p>
     *
     * <p>Pure builder: does not install the provision and does not touch
     * the engine — {@link #applyConfiguration(Request)} pairs it with
     * {@link #installProvision(StreamingProvision)}.</p>
     */
    synchronized StreamingProvision buildStreamingProvision(Request request) {
        Objects.requireNonNull(request, "request must not be null");
        String name = request.backendName();
        if (name == null || name.isBlank() || BACKEND_NONE.equals(name)) {
            return buildDefaultProvision(request.inputDeviceName(), request.outputDeviceName());
        }
        AudioBackend requested = createStreamingBackendByName(name, request.inputDeviceName());
        if (requested == null) {
            // Unknown name — preserve historical behaviour: quietly
            // provision the best available default.
            return buildDefaultProvision(request.inputDeviceName(), request.outputDeviceName());
        }
        boolean requestedIsAvailable = requested.isAvailable();
        if (!requestedIsAvailable || !requested.supportsStreaming()) {
            closeQuietly(requested);
            List<BackendStreamRung> ladder = new ArrayList<>();
            appendFallbackRungs(ladder, name, request.inputDeviceName());
            if (ladder.isEmpty()) {
                ladder.add(new BackendStreamRung(
                        new JavaxSoundBackend(), DeviceId.defaultFor("Java Sound")));
            }
            // Fallback notification: the user explicitly asked for a
            // backend the host can't stream through — surface the switch,
            // naming the ladder's ACTUAL head (PortAudio when available,
            // else Java Sound), instead of silently routing them elsewhere.
            try {
                notifications.notify(name + " not available — falling back to "
                        + ladder.get(0).backend().name());
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "NotificationManager rejected fallback message", e);
            }
            // requestedBackendName stays the USER'S request even though it
            // cannot stream: the engine stamps it into every published
            // BackendFallbackEvent, and those must name the true request —
            // getActiveBackendName() reports the first rung (the backend
            // the user will actually hear) separately. The requested DEVICE
            // is passed explicitly for exactly the same reason (story 316
            // review): this ladder starts on a FALLBACK rung, so the two-arg
            // convenience constructor would default requestedDevice to that
            // fallback's device and the engine would stamp a
            // BackendFallbackEvent pairing the user's requested backend name
            // with a device they never chose.
            //
            // The rejection is ALSO carried forward as a pending failed hop
            // (story 316 review). This gate removes the requested backend
            // from the ladder entirely, so the engine's ladder walk records
            // no failed hop for it and — when the fallback head opens first
            // try — published NOTHING on the EventBus seam, making requested
            // != active a silent substitution there. The NotificationManager
            // message above is a different surface, not a substitute. The
            // cause distinguishes the two genuinely different user-facing
            // facts: a backend the host does not have at all, versus one it
            // has whose streaming path this build has not implemented
            // (supportsStreaming() == false — WASAPI / CoreAudio / JACK
            // today).
            String gateCause = requestedIsAvailable
                    ? name + " is available on this host but its streaming path is not"
                            + " implemented, so it was never offered to the open ladder"
                    : name + " is not available on this host, so it was never offered to"
                            + " the open ladder";
            return new StreamingProvision(
                    name, deviceId(name, request.outputDeviceName()), ladder,
                    List.of(gateCause));
        }
        List<BackendStreamRung> ladder = new ArrayList<>();
        ladder.add(new BackendStreamRung(requested, deviceId(name, request.outputDeviceName())));
        appendFallbackRungs(ladder, name, request.inputDeviceName());
        return new StreamingProvision(name, ladder);
    }

    /**
     * The blank-name default ladder: PortAudio (adapted) when available —
     * with the configured output device on its rung — else Java Sound.
     */
    private StreamingProvision buildDefaultProvision(
            String inputDeviceName, String outputDeviceName) {
        AudioBackend portAudio = tryCreatePortAudioAdapter(inputDeviceName);
        if (portAudio != null) {
            return new StreamingProvision("PortAudio", List.of(
                    new BackendStreamRung(portAudio, deviceId("PortAudio", outputDeviceName)),
                    new BackendStreamRung(
                            new JavaxSoundBackend(), DeviceId.defaultFor("Java Sound"))));
        }
        return new StreamingProvision("Java Sound", List.of(
                new BackendStreamRung(
                        new JavaxSoundBackend(), deviceId("Java Sound", outputDeviceName))));
    }

    /**
     * Appends the emergency fallback rungs — PortAudio when available, then
     * Java Sound — skipping whichever of them is the requested backend
     * itself. Fallback rungs open their backend's DEFAULT device.
     */
    private void appendFallbackRungs(
            List<BackendStreamRung> ladder, String requestedName, String inputDeviceName) {
        if (!"PortAudio".equals(requestedName)) {
            AudioBackend portAudio = tryCreatePortAudioAdapter(inputDeviceName);
            if (portAudio != null) {
                ladder.add(new BackendStreamRung(
                        portAudio, DeviceId.defaultFor("PortAudio")));
            }
        }
        if (!"Java Sound".equals(requestedName)) {
            ladder.add(new BackendStreamRung(
                    new JavaxSoundBackend(), DeviceId.defaultFor("Java Sound")));
        }
    }

    /**
     * Returns an available {@link CallbackBackendAdapter} over a fresh
     * {@link PortAudioBackend}, or {@code null} when PortAudio is not
     * usable on this host.
     */
    private static AudioBackend tryCreatePortAudioAdapter(String inputDeviceName) {
        try {
            CallbackBackendAdapter adapter =
                    new CallbackBackendAdapter(new PortAudioBackend(), inputDeviceName);
            if (adapter.isAvailable()) {
                return adapter;
            }
            closeQuietly(adapter);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "PortAudio adapter unavailable", e);
        }
        return null;
    }

    /**
     * Swaps the engine's streaming provision: <b>installs first</b>, then
     * closes the outgoing backend instances (story 316 review — the order was
     * previously the other way round).
     *
     * <p>{@link AudioEngine#setStreamingProvision(StreamingProvision)} is the
     * only call that quiesces the render pump: it abandons a stream the
     * engine still tracks on an outgoing backend the incoming ladder no
     * longer carries, and that abandonment is what calls {@code stopPump()}.
     * Closing the outgoing instances first therefore freed native state —
     * {@code AsioBackend}'s nulled {@code bufferSwitchShim}, a closed
     * {@code SourceDataLine}, a released {@code Pa_} handle — underneath a
     * live {@code engine-render-pump} thread that was still calling
     * {@code sink()} / {@code awaitSinkCapacity()} on it, whenever the engine
     * still tracked a stream (a {@code stopAudioOutput()} whose pump join
     * timed out, or a retained {@code RELEASE_PENDING} handle).</p>
     *
     * <p>The controller still owns every backend instance's lifecycle, so the
     * outgoing instances are closed here — but only the ones the incoming
     * ladder does not reuse: a shared instance is still live on the new
     * provision and must not be closed.</p>
     *
     * <p>That same ownership is why a REFUSED swap closes the INCOMING
     * instances (story 316 review). {@code setStreamingProvision} now throws
     * {@link com.benesquivelmusic.daw.sdk.audio.AudioBackendException} when
     * the outgoing stream's pump cannot be confirmed quiesced, aborting the
     * swap whole; by then {@code buildStreamingProvision} has already
     * CONSTRUCTED the incoming backend instances, and an exception that
     * simply propagated left them neither installed nor closed — a leak of
     * exactly the native handles this ordering exists to protect (a
     * PortAudio {@code Pa_} handle per {@code CallbackBackendAdapter}, a
     * Java Sound {@code SourceDataLine}). They are closed best-effort here,
     * skipping any instance the OUTGOING provision also carries: the engine
     * still points at that provision, so a shared instance is live.</p>
     */
    private void installProvision(StreamingProvision provision) {
        StreamingProvision outgoing = audioEngine.getStreamingProvision();
        try {
            audioEngine.setStreamingProvision(provision);
        } catch (RuntimeException swapRefused) {
            // Roles reversed on purpose: the provision that never got
            // installed is the one whose instances are now orphaned.
            closeProvisionBackends(provision, outgoing);
            throw swapRefused;
        }
        closeProvisionBackends(outgoing, provision);
    }

    /**
     * Best-effort close of every backend instance in {@code outgoing} that
     * {@code incoming} does not also carry. Reuse is decided by instance
     * identity, mirroring {@code AudioEngine.ladderContains} — an instance
     * the incoming ladder still holds is live and closing it would break the
     * next open.
     *
     * @param outgoing the provision being replaced, or {@code null}
     * @param incoming the provision taking its place, or {@code null} when
     *                 the engine is being left with nothing to stream
     */
    private static void closeProvisionBackends(
            StreamingProvision outgoing, StreamingProvision incoming) {
        if (outgoing == null) {
            return;
        }
        for (BackendStreamRung rung : outgoing.ladder()) {
            if (carries(incoming, rung.backend())) {
                continue;
            }
            closeQuietly(rung.backend());
        }
    }

    /**
     * Returns whether {@code provision}'s ladder carries this exact backend
     * <em>instance</em> — identity, not equality, matching
     * {@code AudioEngine.ladderContains}.
     */
    private static boolean carries(StreamingProvision provision, AudioBackend backend) {
        if (provision == null) {
            return false;
        }
        for (BackendStreamRung rung : provision.ladder()) {
            if (rung.backend() == backend) {
                return true;
            }
        }
        return false;
    }

    private static void closeQuietly(AudioBackend backend) {
        try {
            backend.close();
        } catch (RuntimeException ignored) {
            // best-effort cleanup
        }
    }

    /**
     * Resolves a UI-facing backend name to a fresh SDK {@link AudioBackend}
     * instance (story 316: every backend — including the adapted
     * {@code "PortAudio"} — lives behind the SDK interface now). Returns
     * {@code null} for unknown names; the caller owns the instance's
     * lifecycle.
     */
    private AudioBackend createStreamingBackendByName(String name, String inputDeviceName) {
        return switch (name) {
            case "PortAudio" -> {
                try {
                    yield new CallbackBackendAdapter(new PortAudioBackend(), inputDeviceName);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "PortAudio requested but unavailable", e);
                    yield null;
                }
            }
            case "Java Sound" -> new JavaxSoundBackend();
            default -> createSdkBackendByName(name);
        };
    }

    private AudioBackend createSdkBackendByName(String name) {
        if (WasapiBackend.NAME.concat(" (Exclusive)").equals(name)) {
            return new WasapiBackend(true);
        }
        return backendSelector.selectByName(name);
    }

    private <T> T withSdkBackend(
            String backendName, Function<AudioBackend, T> operation, T fallback) {
        if (backendName == null || backendName.isBlank()
                || "PortAudio".equals(backendName) || "Java Sound".equals(backendName)) {
            return fallback;
        }
        AudioBackend active = audioEngine.getBackend();
        if (active != null && backendName.equals(active.name())) {
            return operation.apply(active);
        }
        AudioBackend probe = createSdkBackendByName(backendName);
        if (probe == null) {
            return fallback;
        }
        try (probe) {
            return operation.apply(probe);
        }
    }

    private static DeviceId deviceId(String backendName, String deviceName) {
        String normalizedBackend = WasapiBackend.NAME.concat(" (Exclusive)").equals(backendName)
                ? WasapiBackend.NAME : backendName;
        if (deviceName == null || deviceName.isBlank()) {
            return DeviceId.defaultFor(normalizedBackend);
        }
        return new DeviceId(normalizedBackend, deviceName);
    }
}
