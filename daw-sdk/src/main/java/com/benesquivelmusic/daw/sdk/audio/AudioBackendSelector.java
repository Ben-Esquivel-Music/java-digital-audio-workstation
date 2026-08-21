package com.benesquivelmusic.daw.sdk.audio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Selects the right {@link AudioBackend} for the current host. Open-time
 * fallback is not this class's job: the engine walks its explicit
 * {@code StreamingProvision} ladder (story 316), publishing a
 * {@code BackendFallbackEvent} per failed hop.
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
     * {@link AudioBackend} instance, so the app controller does not need to
     * maintain a parallel hand-rolled {@code switch}.</p>
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
     * available on this host <em>and</em> whose streaming path is actually
     * implemented ({@link AudioBackend#supportsStreaming()}), in OS-default
     * priority order.
     *
     * <p>The streaming gate (story 316) keeps backends whose
     * {@code sink(...)} discards by construction — WASAPI, CoreAudio and
     * JACK today — out of the offered list, so the application never opens a
     * stream that would be silent while looking healthy. They return here as
     * their streaming stories land.</p>
     *
     * @return list of available, streamable backend names (never empty —
     *         Java Sound is always present)
     */
    public List<String> availableBackends() {
        List<String> available = new ArrayList<>();
        for (String name : preferenceOrderForCurrentOs()) {
            Supplier<AudioBackend> factory = factories.get(name);
            if (factory == null) {
                continue;
            }
            try (AudioBackend probe = factory.get()) {
                if (probe.isAvailable() && probe.supportsStreaming()) {
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
     * Returns the OS-default preferred backend name for this host — the
     * first entry of the OS preference order that is both available and
     * streamable ({@link AudioBackend#supportsStreaming()}, story 316), so
     * the default is never a backend that would open a silent stream.
     *
     * @return preferred backend name
     */
    public String defaultBackendName() {
        for (String candidate : preferenceOrderForCurrentOs()) {
            Supplier<AudioBackend> factory = factories.get(candidate);
            if (factory == null) continue;
            try (AudioBackend probe = factory.get()) {
                if (probe.isAvailable() && probe.supportsStreaming()) {
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
}
