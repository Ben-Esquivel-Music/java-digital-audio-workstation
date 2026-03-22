package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.audio.javasound.JavaSoundBackend;
import com.benesquivelmusic.daw.core.audio.portaudio.PortAudioBackend;
import com.benesquivelmusic.daw.sdk.audio.NativeAudioBackend;

import java.util.Objects;

/**
 * Factory for creating audio backends with automatic fallback.
 *
 * <p>Attempts to use the PortAudio FFM backend first. If the PortAudio native
 * library is not available on the system, falls back to the Java Sound API
 * backend.</p>
 *
 * <h2>Selection Order</h2>
 * <ol>
 *   <li><strong>PortAudio</strong> — Low-latency native I/O via FFM bindings.
 *       Requires the PortAudio shared library on the system library path.</li>
 *   <li><strong>Java Sound</strong> — Always-available fallback using
 *       {@code javax.sound.sampled}. Higher latency but no native dependencies.</li>
 * </ol>
 *
 * <h2>FFM as a Replacement for PortAudio</h2>
 * <p>The Java FFM API (JEP 454, final in JDK 22) can also be used to call
 * OS audio APIs directly (ALSA on Linux, CoreAudio on macOS, WASAPI on Windows)
 * without going through PortAudio. This approach eliminates the PortAudio
 * dependency at the cost of maintaining separate bindings per platform.
 * Future backends could implement {@link NativeAudioBackend} to target specific
 * OS APIs directly via FFM — see the project documentation for analysis.</p>
 */
public final class AudioBackendFactory {

    private AudioBackendFactory() {}

    /**
     * Creates the best available audio backend for the current platform.
     *
     * <p>Tries PortAudio first, then falls back to Java Sound.</p>
     *
     * @return a new audio backend instance (not yet initialized)
     */
    public static NativeAudioBackend createDefault() {
        PortAudioBackend portAudio = new PortAudioBackend();
        if (portAudio.isAvailable()) {
            return portAudio;
        }
        return new JavaSoundBackend();
    }

    /**
     * Creates a PortAudio backend.
     *
     * @return a new PortAudio backend (not yet initialized)
     * @throws IllegalStateException if PortAudio is not available
     */
    public static NativeAudioBackend createPortAudio() {
        PortAudioBackend backend = new PortAudioBackend();
        if (!backend.isAvailable()) {
            throw new IllegalStateException(
                    "PortAudio native library is not available on this system");
        }
        return backend;
    }

    /**
     * Creates a Java Sound API backend.
     *
     * @return a new Java Sound backend (not yet initialized)
     */
    public static NativeAudioBackend createJavaSound() {
        return new JavaSoundBackend();
    }

    /**
     * Returns the name of the backend that would be selected by {@link #createDefault()}.
     *
     * @return the backend name ("PortAudio" or "Java Sound")
     */
    public static String detectBackendName() {
        PortAudioBackend portAudio = new PortAudioBackend();
        return portAudio.isAvailable() ? "PortAudio" : "Java Sound";
    }
}
