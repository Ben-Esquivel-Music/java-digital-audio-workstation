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
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MixPrecision;
import com.benesquivelmusic.daw.sdk.audio.NativeAudioBackend;
import com.benesquivelmusic.daw.sdk.audio.XrunEvent;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
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
            // Switch on sealed AudioDeviceEvent — exhaustive over the three
            // permitted records. JEP 441 (final, JDK 21).
            switch (event) {
                case AudioDeviceEvent.DeviceRemoved removed -> onDeviceRemoved(removed.device());
                case AudioDeviceEvent.DeviceArrived arrived -> onDeviceArrived(arrived.device());
                case AudioDeviceEvent.DeviceFormatChanged changed ->
                        LOG.info("Device format changed for " + changed.device() + ": " + changed.newFormat());
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
