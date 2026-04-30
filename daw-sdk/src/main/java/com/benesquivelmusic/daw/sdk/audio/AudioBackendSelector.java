package com.benesquivelmusic.daw.sdk.audio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Selects the right {@link AudioBackend} for the current host, with
 * automatic fallback to {@link JavaxSoundBackend} when the preferred
 * backend cannot open a stream.
 *
 * <p>This is the backend-wiring counterpart to the UI selection story (098):
 * the Audio Settings dialog asks {@link AudioEngineController} for the list
 * of available backend names, and {@code AudioEngineController} drives
 * {@code AudioBackendSelector} to pick the initial default and to honour
 * the user's persisted choice from {@link AudioSettingsStore}.</p>
 *
 * <h2>OS-default priority order</h2>
 * <ul>
 *   <li><b>Windows</b>: ASIO &rarr; WASAPI &rarr; Java Sound</li>
 *   <li><b>macOS</b>: CoreAudio &rarr; Java Sound</li>
 *   <li><b>Linux</b>: JACK &rarr; Java Sound</li>
 *   <li><b>Other</b>: Java Sound</li>
 * </ul>
 */
public final class AudioBackendSelector {

    private static final Logger LOGGER = Logger.getLogger(AudioBackendSelector.class.getName());

    /** Names of every backend the selector can produce. */
    public static final List<String> ALL_BACKEND_NAMES = List.of(
            AsioBackend.NAME,
            CoreAudioBackend.NAME,
            WasapiBackend.NAME,
            JackBackend.NAME,
            JavaxSoundBackend.NAME,
            MockAudioBackend.NAME);

    private final Map<String, Supplier<AudioBackend>> factories;

    /** Creates a selector wired to the default, real backend factories. */
    public AudioBackendSelector() {
        this(defaultFactories());
    }

    /**
     * Creates a selector with caller-supplied factories. Used by tests to
     * inject {@link MockAudioBackend} as any permitted backend name for
     * deterministic selection tests.
     *
     * @param factories map of backend-name to backend factory
     */
    public AudioBackendSelector(Map<String, Supplier<AudioBackend>> factories) {
        this.factories = new LinkedHashMap<>(
                Objects.requireNonNull(factories, "factories must not be null"));
    }

    private static Map<String, Supplier<AudioBackend>> defaultFactories() {
        Map<String, Supplier<AudioBackend>> map = new LinkedHashMap<>();
        map.put(AsioBackend.NAME, AsioBackend::new);
        map.put(CoreAudioBackend.NAME, CoreAudioBackend::new);
        map.put(WasapiBackend.NAME, WasapiBackend::new);
        map.put(JackBackend.NAME, JackBackend::new);
        map.put(JavaxSoundBackend.NAME, JavaxSoundBackend::new);
        map.put(MockAudioBackend.NAME, MockAudioBackend::new);
        return map;
    }

    /**
     * Instantiates the backend registered under {@code name}, regardless of
     * whether it reports {@link AudioBackend#isAvailable()}. Callers are
     * responsible for the returned backend's lifecycle (must be
     * {@link AudioBackend#close() closed}).
     *
     * <p>Returns {@code null} when {@code name} is null/blank or does not
     * match any registered factory — this lets the caller's own legacy /
     * non-SDK switch fall through. Story 130 (this method) is the single
     * place that maps a UI-facing backend name to an SDK
     * {@link AudioBackend} instance, so {@link DefaultAudioEngineController}
     * does not need to maintain a parallel hand-rolled {@code switch}.</p>
     *
     * @param name backend name as listed by {@link #availableBackendNames()}
     * @return a fresh {@link AudioBackend} instance, or {@code null} if
     *         no factory is registered for {@code name}
     */
    public AudioBackend selectByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Supplier<AudioBackend> factory = factories.get(name);
        return factory == null ? null : factory.get();
    }

    /**
     * Alias for {@link #availableBackends()} — the name used by the
     * {@code AudioSettingsDialog} combo and the round-trip wiring test
     * in story 130. Kept as a separate method so the UI vocabulary
     * ("availableBackendNames") and the legacy SDK vocabulary
     * ("availableBackends") can both be used without confusion.
     *
     * @return the list of backend names available on this host
     */
    public List<String> availableBackendNames() {
        return availableBackends();
    }

    /**
     * Returns the names of every backend whose native library / driver is
     * available on this host, in OS-default priority order.
     *
     * @return list of available backend names (never empty — Java Sound is
     *         always present)
     */
    public List<String> availableBackends() {
        List<String> available = new ArrayList<>();
        for (String name : preferenceOrderForCurrentOs()) {
            Supplier<AudioBackend> factory = factories.get(name);
            if (factory == null) {
                continue;
            }
            try (AudioBackend probe = factory.get()) {
                if (probe.isAvailable()) {
                    available.add(name);
                }
            }
        }
        if (!available.contains(JavaxSoundBackend.NAME)
                && factories.containsKey(JavaxSoundBackend.NAME)) {
            available.add(JavaxSoundBackend.NAME);
        }
        return List.copyOf(available);
    }

    /**
     * Returns the OS-default preferred backend name for this host.
     *
     * @return preferred backend name
     */
    public String defaultBackendName() {
        for (String candidate : preferenceOrderForCurrentOs()) {
            Supplier<AudioBackend> factory = factories.get(candidate);
            if (factory == null) continue;
            try (AudioBackend probe = factory.get()) {
                if (probe.isAvailable()) {
                    return candidate;
                }
            }
        }
        return JavaxSoundBackend.NAME;
    }

    /**
     * Returns the OS-specific backend-preference order. Package-private for
     * testability so tests can verify the Windows / macOS / Linux ordering
     * without having to run on those OSes.
     *
     * @return ordered list of backend names
     */
    List<String> preferenceOrderForCurrentOs() {
        return preferenceOrder(System.getProperty("os.name", ""));
    }

    static List<String> preferenceOrder(String osName) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return List.of(AsioBackend.NAME, WasapiBackend.NAME, JavaxSoundBackend.NAME);
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return List.of(CoreAudioBackend.NAME, JavaxSoundBackend.NAME);
        }
        if (os.contains("nux") || os.contains("nix")) {
            return List.of(JackBackend.NAME, JavaxSoundBackend.NAME);
        }
        return List.of(JavaxSoundBackend.NAME);
    }

    /**
     * Opens {@code preferredName}'s backend with the given stream parameters.
     * If the preferred backend is unavailable, or if
     * {@link AudioBackend#open(DeviceId, AudioFormat, int)} throws, the
     * failure is logged and the method falls back to
     * {@link JavaxSoundBackend}.
     *
     * <p>Returns the opened backend; the caller owns its lifecycle and must
     * call {@link AudioBackend#close()} when done.</p>
     *
     * @param preferredName backend the user asked for
     * @param device         target device id
     * @param format         desired format
     * @param bufferFrames   desired buffer size in frames
     * @return a successfully-opened backend (never null)
     */
    public AudioBackend openWithFallback(
            String preferredName, DeviceId device, AudioFormat format, int bufferFrames) {
        Objects.requireNonNull(preferredName, "preferredName must not be null");
        Supplier<AudioBackend> factory = factories.get(preferredName);
        if (factory != null) {
            AudioBackend backend = factory.get();
            if (backend.isAvailable()) {
                try {
                    backend.open(device, format, bufferFrames);
                    return backend;
                } catch (RuntimeException openFailure) {
                    LOGGER.log(Level.WARNING,
                            openFailure,
                            () -> "Backend " + preferredName
                                    + " failed to open — falling back to "
                                    + JavaxSoundBackend.NAME);
                    safeClose(backend);
                }
            } else {
                LOGGER.log(Level.INFO,
                        () -> "Backend " + preferredName
                                + " not available — falling back to " + JavaxSoundBackend.NAME);
                safeClose(backend);
            }
        }
        AudioBackend fallback = factories
                .getOrDefault(JavaxSoundBackend.NAME, JavaxSoundBackend::new)
                .get();
        fallback.open(fallbackDevice(device), format, bufferFrames);
        return fallback;
    }

    private static DeviceId fallbackDevice(DeviceId original) {
        if (original == null || original.isDefault()) {
            return DeviceId.defaultFor(JavaxSoundBackend.NAME);
        }
        return new DeviceId(JavaxSoundBackend.NAME, original.name());
    }

    private static void safeClose(AudioBackend backend) {
        try {
            backend.close();
        } catch (RuntimeException ignored) {
            // swallow — best-effort cleanup
        }
    }
}
