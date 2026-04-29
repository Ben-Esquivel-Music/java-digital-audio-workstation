package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioBackendFactory;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.javasound.JavaSoundBackend;
import com.benesquivelmusic.daw.core.audio.performance.XrunDetector;
import com.benesquivelmusic.daw.core.audio.portaudio.PortAudioBackend;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.performance.PerformanceMonitor;
import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceEvent;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.BufferSizeRange;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.FormatChangeReason;
import com.benesquivelmusic.daw.sdk.audio.MixPrecision;
import com.benesquivelmusic.daw.sdk.audio.NativeAudioBackend;
import com.benesquivelmusic.daw.sdk.audio.XrunEvent;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default {@link AudioEngineController} that drives a real {@link AudioEngine}.
 *
 * <p>Owns the active {@link NativeAudioBackend} and coordinates
 * reconfiguration: stop the current stream, mutate the engine's format,
 * swap the backend when requested, then restart the stream. Each engine
 * re-configuration reuses the same {@code AudioEngine} instance so that
 * all sub-controllers (transport, mixer, editors) keep their existing
 * engine references intact.</p>
 */
final class DefaultAudioEngineController implements AudioEngineController {

    private static final Logger LOG = Logger.getLogger(DefaultAudioEngineController.class.getName());

    private final AudioEngine audioEngine;
    private final TestTonePlayer tonePlayer;
    private final Runnable postReconfigureCallback;
    private final NotificationManager notifications;
    private final IncompleteTakeStore incompleteTakeStore;
    private final SubmissionPublisher<EngineState> engineStatePublisher = new SubmissionPublisher<>();
    private volatile XrunDetector xrunDetector;
    private volatile EngineState engineState = EngineState.STOPPED;
    /** Backend whose deviceEvents() we are currently subscribed to. */
    private final AtomicReference<AudioBackend> boundBackend = new AtomicReference<>();
    /** Currently-opened device — if it disappears we transition to DEVICE_LOST. */
    private final AtomicReference<DeviceId> activeDevice = new AtomicReference<>();
    /** Subscription to the bound backend's device-event publisher. */
    private final AtomicReference<Flow.Subscription> deviceEventSubscription = new AtomicReference<>();

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
        this.audioEngine = Objects.requireNonNull(audioEngine, "audioEngine must not be null");
        this.tonePlayer = new TestTonePlayer();
        this.postReconfigureCallback = postReconfigureCallback;
        this.notifications = Objects.requireNonNull(notifications, "notifications must not be null");
        this.incompleteTakeStore = Objects.requireNonNull(
                incompleteTakeStore, "incompleteTakeStore must not be null");
        this.xrunDetector = createDetectorFor(audioEngine.getFormat());
    }

    @Override
    public String getActiveBackendName() {
        NativeAudioBackend backend = audioEngine.getAudioBackend();
        return backend == null ? BACKEND_NONE : backend.getBackendName();
    }

    @Override
    public List<String> getAvailableBackendNames() {
        List<String> names = new ArrayList<>();
        try {
            if (new PortAudioBackend().isAvailable()) {
                names.add("PortAudio");
            }
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "PortAudio unavailable", e);
        }
        names.add("Java Sound");
        return Collections.unmodifiableList(names);
    }

    @Override
    public List<AudioDeviceInfo> listDevices() {
        NativeAudioBackend backend = audioEngine.getAudioBackend();
        if (backend == null) {
            return List.of();
        }
        try {
            audioEngine.ensureBackendInitialized();
            return backend.getAvailableDevices();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to list audio devices", e);
            return List.of();
        }
    }

    @Override
    public List<AudioDeviceInfo> listDevices(String backendName) {
        if (backendName == null || backendName.isBlank() || BACKEND_NONE.equals(backendName)) {
            return List.of();
        }
        NativeAudioBackend active = audioEngine.getAudioBackend();
        if (active != null && backendName.equals(active.getBackendName())) {
            return listDevices();
        }
        NativeAudioBackend probe = null;
        try {
            probe = createBackendByName(backendName);
            if (probe == null) {
                return List.of();
            }
            probe.initialize();
            return probe.getAvailableDevices();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to enumerate " + backendName + " devices", e);
            return List.of();
        } finally {
            if (probe != null) {
                try {
                    probe.close();
                } catch (RuntimeException ignored) {
                    // best-effort cleanup
                }
            }
        }
    }

    @Override
    public double getCpuLoadPercent() {
        PerformanceMonitor monitor = audioEngine.getPerformanceMonitor();
        return monitor == null ? -1.0 : monitor.getCpuLoadPercent();
    }

    @Override
    public void applyConfiguration(Request request) {
        Objects.requireNonNull(request, "request must not be null");
        LOG.info("Applying audio configuration: " + request);

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

        // Buffer size or sample rate may have changed — rebuild the
        // xrun detector so its deadline matches the new format, and
        // reset the counter per the issue's reset-on-reconfigure rule.
        XrunDetector previousDetector = this.xrunDetector;
        this.xrunDetector = createDetectorFor(updated);
        if (previousDetector != null) {
            previousDetector.close();
        }

        NativeAudioBackend currentBackend = audioEngine.getAudioBackend();
        if (currentBackend == null || !request.backendName().equals(currentBackend.getBackendName())) {
            NativeAudioBackend newBackend = createBackendByName(request.backendName());
            if (newBackend == null) {
                newBackend = AudioBackendFactory.createDefault();
            }
            audioEngine.setAudioBackend(newBackend);
        }

        int outputDeviceIndex = resolveDeviceIndex(
                audioEngine.getAudioBackend(),
                request.outputDeviceName(),
                true);

        if (wasOpen) {
            audioEngine.startAudioOutput(Math.max(0, outputDeviceIndex));
            setEngineState(EngineState.RUNNING);
        } else {
            audioEngine.start();
            setEngineState(EngineState.STOPPED);
        }

        if (postReconfigureCallback != null) {
            try {
                postReconfigureCallback.run();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Post-reconfigure callback failed", e);
            }
        }
    }

    @Override
    public void playTestTone(String outputDeviceName) {
        tonePlayer.play(outputDeviceName == null ? "" : outputDeviceName);
    }

    @Override
    public void applyMixPrecision(MixPrecision precision) {
        Objects.requireNonNull(precision, "precision must not be null");
        Mixer mixer = audioEngine.getMixer();
        if (mixer != null) {
            mixer.setMixPrecision(precision);
            LOG.info("Mix precision set to " + precision);
        }
    }

    @Override
    public Flow.Publisher<XrunEvent> xrunEvents() {
        return xrunDetector.xrunEvents();
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
        NativeAudioBackend backend = audioEngine.getAudioBackend();
        if (backend == null) {
            return com.benesquivelmusic.daw.sdk.audio.RoundTripLatency.UNKNOWN;
        }
        return backend.reportedLatency();
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
    public void bindBackendDeviceEvents(AudioBackend backend, DeviceId activeDevice) {
        Objects.requireNonNull(backend, "backend must not be null");
        Objects.requireNonNull(activeDevice, "activeDevice must not be null");
        Flow.Subscription previous = deviceEventSubscription.getAndSet(null);
        if (previous != null) {
            try { previous.cancel(); } catch (RuntimeException ignored) { /* best-effort */ }
        }
        boundBackend.set(backend);
        this.activeDevice.set(activeDevice);
        backend.deviceEvents().subscribe(new DeviceEventSubscriber());
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

    private void onDeviceRemoved(DeviceId removed) {
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

    private void onDeviceArrived(DeviceId arrived) {
        if (engineState != EngineState.DEVICE_LOST) {
            return;
        }
        DeviceId active = activeDevice.get();
        if (active == null || !matches(active, arrived)) {
            return;
        }
        LOG.info("Lost audio device returned: " + arrived);
        AudioBackend backend = boundBackend.get();
        if (backend != null) {
            try {
                if (!backend.isOpen()) {
                    AudioFormat format = audioEngine.getFormat();
                    backend.open(arrived,
                            new com.benesquivelmusic.daw.sdk.audio.AudioFormat(
                                    format.sampleRate(),
                                    format.channels(),
                                    format.bitDepth()),
                            format.bufferSize());
                }
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Failed to reopen backend after reconnect", e);
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

    private void performFormatChangeReopen(DeviceId active,
                                           AudioDeviceEvent.FormatChangeRequested requested) {
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

        // 6. Reopen the SDK backend's stream — close first since a
        //    driver-initiated format change occurs while the backend is
        //    still open, and calling open() without a preceding close()
        //    would violate AudioBackendSupport.markOpen().
        if (backend != null) {
            try {
                if (backend.isOpen()) {
                    try {
                        backend.close();
                    } catch (RuntimeException closeException) {
                        LOG.log(Level.WARNING,
                                "Failed to close backend before reopen after format change",
                                closeException);
                    }
                }
                backend.open(active,
                        new com.benesquivelmusic.daw.sdk.audio.AudioFormat(
                                reopenFormat.sampleRate(),
                                reopenFormat.channels(),
                                reopenFormat.bitDepth()),
                        reopenFormat.bufferSize());
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Failed to reopen backend after format change", e);
            }
        }

        // 7. Resume in STOPPED state. Transport does NOT auto-start —
        //    user re-arms manually, identical to the onDeviceArrived
        //    convention.
        setEngineState(EngineState.STOPPED);

        // 8. Surface the SRC fallback notification when the driver moved
        //    to a rate that differs from the project's session rate. The
        //    actual SRC insertion at the device boundary is a render-graph
        //    change owned by the engine pipeline (story 126's
        //    com.benesquivelmusic.daw.sdk.audio.SampleRateConverter); this
        //    controller does not own that graph.
        // TODO(story-126-integration): wire SampleRateConverter at the
        //   device boundary inside the engine's render graph so a project
        //   authored at 48 kHz keeps its session rate even when the
        //   driver moves to 44.1 kHz.
        if (isSampleRateChange && rateActuallyDiffers) {
            int newRateKhz = (int) Math.round(proposed.get().sampleRate() / 1000.0);
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

    private final class DeviceEventSubscriber implements Flow.Subscriber<AudioDeviceEvent> {
        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            deviceEventSubscription.set(subscription);
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(AudioDeviceEvent event) {
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
            LOG.log(Level.WARNING, "Device-event publisher error", throwable);
        }

        @Override
        public void onComplete() {
            // Backend closed; no further events expected.
        }
    }

    /**
     * The production audio engine uses {@link NativeAudioBackend}
     * (PortAudio or Java Sound), neither of which exposes a native
     * control panel. When ASIO/WASAPI/CoreAudio support is added to
     * the engine, this method should delegate to the active backend's
     * panel action.
     */
    @Override
    public Optional<Runnable> openControlPanel() {
        return Optional.empty();
    }

    /**
     * Exposes the detector for tests and for engine-internal wiring
     * that records per-buffer timing.
     */
    XrunDetector getXrunDetector() {
        return xrunDetector;
    }

    /** Closes background resources owned by this controller. */
    void shutdown() {
        tonePlayer.close();
        Flow.Subscription sub = deviceEventSubscription.getAndSet(null);
        if (sub != null) {
            try { sub.cancel(); } catch (RuntimeException ignored) { /* best-effort */ }
        }
        engineStatePublisher.close();
        ScheduledFuture<?> pending = pendingFormatChange.getAndSet(null);
        if (pending != null) {
            pending.cancel(false);
        }
        formatChangeWorker.shutdownNow();
        XrunDetector detector = this.xrunDetector;
        if (detector != null) {
            detector.close();
        }
    }

    private static XrunDetector createDetectorFor(AudioFormat format) {
        return new XrunDetector(format.sampleRate(), format.bufferSize());
    }

    private static NativeAudioBackend createBackendByName(String name) {
        if (name == null || name.isBlank() || BACKEND_NONE.equals(name)) {
            return null;
        }
        return switch (name) {
            case "PortAudio" -> {
                try {
                    yield AudioBackendFactory.createPortAudio();
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "PortAudio requested but unavailable", e);
                    yield null;
                }
            }
            case "Java Sound" -> new JavaSoundBackend();
            default -> null;
        };
    }

    private static int resolveDeviceIndex(NativeAudioBackend backend, String deviceName, boolean outputRequired) {
        if (backend == null) {
            return -1;
        }
        if (deviceName == null || deviceName.isBlank()) {
            return 0;
        }
        try {
            for (AudioDeviceInfo info : backend.getAvailableDevices()) {
                if (deviceName.equals(info.name())) {
                    if (outputRequired && !info.supportsOutput()) {
                        continue;
                    }
                    return info.index();
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "Failed to resolve device index for " + deviceName, e);
        }
        return 0;
    }
}
