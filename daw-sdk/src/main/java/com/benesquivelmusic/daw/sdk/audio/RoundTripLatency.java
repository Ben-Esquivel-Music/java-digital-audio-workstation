package com.benesquivelmusic.daw.sdk.audio;

/**
 * Driver-reported round-trip latency for recorded-take time alignment.
 *
 * <p>Pro Tools and Logic both call {@code ASIOGetLatencies(int* inputLatency,
 * int* outputLatency)} at stream open and shift the recorded buffer by
 * {@code inputLatency + outputLatency} so the wave aligns with the bar the
 * user heard. CoreAudio exposes the same numbers via
 * {@code kAudioDevicePropertyLatency} +
 * {@code kAudioStreamPropertyLatency} +
 * {@code kAudioDevicePropertySafetyOffset}; WASAPI exposes them via
 * {@code IAudioClient::GetStreamLatency} plus the device period; JACK via
 * {@code jack_port_get_total_latency}.</p>
 *
 * <p>This record is the shape every {@link AudioBackend} returns from
 * {@link AudioBackend#reportedLatency()} after a successful
 * {@link AudioBackend#open(DeviceId, AudioFormat, int) open} call. The total
 * compensation a recording pipeline should apply is the sum of all three
 * fields — see {@link #totalFrames()}.</p>
 *
 * <p>This is the round-trip caused by the driver's own input + output buffer
 * pipelines (story this record was added for). It is independent from PDC
 * (plugin-delay compensation, story 124), which handles latency introduced
 * by plugins inside the audio graph.</p>
 *
 * @param inputFrames        latency in frames on the capture path
 *                           (mic → driver → DAW); must be {@code >= 0}
 * @param outputFrames       latency in frames on the playback path
 *                           (DAW → driver → speakers); must be {@code >= 0}
 * @param safetyOffsetFrames CoreAudio's {@code kAudioDevicePropertySafetyOffset}
 *                           or equivalent guard padding; must be {@code >= 0}.
 *                           Backends with no notion of a safety offset report
 *                           {@code 0}.
 */
public record RoundTripLatency(int inputFrames, int outputFrames, int safetyOffsetFrames) {

    /**
     * Sentinel value used when a backend has no opened stream yet (or has
     * no way to report driver latency, e.g. {@link JavaxSoundBackend}).
     * All three components are zero, so {@link #totalFrames()} returns
     * {@code 0} and recording compensation is a no-op.
     */
    public static final RoundTripLatency UNKNOWN = new RoundTripLatency(0, 0, 0);

    public RoundTripLatency {
        if (inputFrames < 0) {
            throw new IllegalArgumentException(
                    "inputFrames must be >= 0: " + inputFrames);
        }
        if (outputFrames < 0) {
            throw new IllegalArgumentException(
                    "outputFrames must be >= 0: " + outputFrames);
        }
        if (safetyOffsetFrames < 0) {
            throw new IllegalArgumentException(
                    "safetyOffsetFrames must be >= 0: " + safetyOffsetFrames);
        }
    }

    /**
     * Returns the total round-trip latency in frames — the sum of input,
     * output, and safety-offset frames. This is the value
     * {@code RecordingPipeline} subtracts from each captured-block sample
     * position so the resulting clip aligns with the cue the user heard.
     *
     * <p>Uses {@link Math#addExact(int, int)} so corrupt or extremely
     * large values surface as an {@link ArithmeticException} instead of
     * silently overflowing into a negative/incorrect compensation
     * amount.</p>
     *
     * @return total round-trip frames (never negative)
     * @throws ArithmeticException if the sum overflows {@code int}
     */
    public int totalFrames() {
        return Math.addExact(Math.addExact(inputFrames, outputFrames), safetyOffsetFrames);
    }

    /**
     * Returns the total round-trip latency in milliseconds at the given
     * sample rate. Used by the transport-bar indicator
     * ({@code "I/O 5.3 ms"}) and the
     * {@code IoLatencyDetailsPopup}.
     *
     * @param sampleRateHz sample rate in Hz (must be positive)
     * @return total latency in milliseconds (never negative)
     * @throws IllegalArgumentException if {@code sampleRateHz <= 0}
     */
    public double totalMillis(double sampleRateHz) {
        if (!(sampleRateHz > 0)) {
            throw new IllegalArgumentException(
                    "sampleRateHz must be positive: " + sampleRateHz);
        }
        return (totalFrames() * 1000.0) / sampleRateHz;
    }

    /**
     * Convenience factory for backends that do not report a safety offset
     * (WASAPI / JACK / the JDK mixer). Equivalent to
     * {@code new RoundTripLatency(inputFrames, outputFrames, 0)}.
     *
     * @param inputFrames  capture-path latency in frames
     * @param outputFrames playback-path latency in frames
     * @return a new round-trip latency with zero safety offset
     */
    public static RoundTripLatency of(int inputFrames, int outputFrames) {
        return new RoundTripLatency(inputFrames, outputFrames, 0);
    }
}
