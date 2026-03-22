package com.benesquivelmusic.daw.sdk.audio;

/**
 * Standard audio buffer sizes in sample frames.
 *
 * <p>Smaller buffer sizes yield lower latency but require more CPU.
 * Typical professional DAW settings range from 64 to 512 samples.</p>
 *
 * <table>
 *   <caption>Buffer size vs. approximate latency at 44.1 kHz</caption>
 *   <tr><th>Samples</th><th>Latency</th></tr>
 *   <tr><td>32</td><td>~0.7 ms</td></tr>
 *   <tr><td>64</td><td>~1.5 ms</td></tr>
 *   <tr><td>128</td><td>~2.9 ms</td></tr>
 *   <tr><td>256</td><td>~5.8 ms</td></tr>
 *   <tr><td>512</td><td>~11.6 ms</td></tr>
 *   <tr><td>1024</td><td>~23.2 ms</td></tr>
 *   <tr><td>2048</td><td>~46.4 ms</td></tr>
 * </table>
 */
public enum BufferSize {

    SAMPLES_32(32),
    SAMPLES_64(64),
    SAMPLES_128(128),
    SAMPLES_256(256),
    SAMPLES_512(512),
    SAMPLES_1024(1024),
    SAMPLES_2048(2048);

    private final int frames;

    BufferSize(int frames) {
        this.frames = frames;
    }

    /**
     * Returns the number of sample frames for this buffer size.
     *
     * @return the frame count
     */
    public int getFrames() {
        return frames;
    }

    /**
     * Calculates the buffer latency in milliseconds for a given sample rate.
     *
     * @param sampleRate the sample rate in Hz
     * @return the latency in milliseconds
     */
    public double latencyMs(double sampleRate) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        return (frames / sampleRate) * 1000.0;
    }

    /**
     * Returns the {@code BufferSize} whose frame count matches the given value.
     *
     * @param frames the frame count
     * @return the matching buffer size
     * @throws IllegalArgumentException if no matching buffer size exists
     */
    public static BufferSize fromFrames(int frames) {
        for (BufferSize size : values()) {
            if (size.frames == frames) {
                return size;
            }
        }
        throw new IllegalArgumentException("Unsupported buffer size: " + frames);
    }
}
