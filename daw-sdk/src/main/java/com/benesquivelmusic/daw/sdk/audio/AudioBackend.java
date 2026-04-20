package com.benesquivelmusic.daw.sdk.audio;

import java.util.List;
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
     * Closes any open stream and releases native resources. Idempotent.
     */
    @Override
    void close();
}
