package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioBackendFactory;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.javasound.JavaSoundBackend;
import com.benesquivelmusic.daw.core.audio.performance.XrunDetector;
import com.benesquivelmusic.daw.core.audio.portaudio.PortAudioBackend;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.performance.PerformanceMonitor;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.MixPrecision;
import com.benesquivelmusic.daw.sdk.audio.NativeAudioBackend;
import com.benesquivelmusic.daw.sdk.audio.XrunEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Flow;
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
    private volatile XrunDetector xrunDetector;

    DefaultAudioEngineController(AudioEngine audioEngine, Runnable postReconfigureCallback) {
        this.audioEngine = Objects.requireNonNull(audioEngine, "audioEngine must not be null");
        this.tonePlayer = new TestTonePlayer();
        this.postReconfigureCallback = postReconfigureCallback;
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
                request.bufferSize().getFrames());
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
        } else {
            audioEngine.start();
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
