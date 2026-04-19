package com.benesquivelmusic.daw.core.audio.harness;

import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.AudioStreamCallback;
import com.benesquivelmusic.daw.sdk.audio.AudioStreamConfig;
import com.benesquivelmusic.daw.sdk.audio.LatencyInfo;
import com.benesquivelmusic.daw.sdk.audio.NativeAudioBackend;
import com.benesquivelmusic.daw.sdk.audio.SampleRate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Headless {@link NativeAudioBackend} for deterministic, offline tests.
 *
 * <p>This backend produces no device output. Instead, it records every
 * sample frame passed to the configured {@link AudioStreamCallback} into
 * an in-memory {@code double[][]} buffer, allowing tests to assert on
 * the rendered audio without a sound card.</p>
 *
 * <p>The backend does not spawn any background threads: tests drive the
 * callback synchronously via {@link #drive(int)}.</p>
 *
 * <p>Not thread-safe — intended for single-threaded test use only.</p>
 */
public final class HeadlessAudioBackend implements NativeAudioBackend {

    /** Default headless device — no physical device is required. */
    public static final AudioDeviceInfo HEADLESS_DEVICE = new AudioDeviceInfo(
            0,
            "Headless",
            "Headless",
            2,
            2,
            44_100.0,
            List.of(SampleRate.HZ_44100, SampleRate.HZ_48000, SampleRate.HZ_96000),
            0.0,
            0.0
    );

    private boolean initialized;
    private boolean streamOpen;
    private boolean streamActive;

    private AudioStreamConfig config;
    private AudioStreamCallback callback;

    // Captured output: one float[channels][frames] buffer per process() call.
    // Kept as a list so a test can drive an arbitrary number of blocks.
    private final List<float[][]> capturedBlocks = new ArrayList<>();
    private int capturedFrames;

    // Optional input generator invoked before each callback to fill the
    // input buffer (e.g., a sine generator for self-tests).
    private InputGenerator inputGenerator = (in, numFrames, framesRendered) -> {
        for (int ch = 0; ch < in.length; ch++) {
            Arrays.fill(in[ch], 0, numFrames, 0f);
        }
    };

    @Override
    public void initialize() {
        initialized = true;
    }

    @Override
    public List<AudioDeviceInfo> getAvailableDevices() {
        return List.of(HEADLESS_DEVICE);
    }

    @Override
    public AudioDeviceInfo getDefaultInputDevice() {
        return HEADLESS_DEVICE;
    }

    @Override
    public AudioDeviceInfo getDefaultOutputDevice() {
        return HEADLESS_DEVICE;
    }

    @Override
    public void openStream(AudioStreamConfig config, AudioStreamCallback callback) {
        this.config = Objects.requireNonNull(config, "config");
        this.callback = Objects.requireNonNull(callback, "callback");
        this.streamOpen = true;
    }

    @Override
    public void startStream() {
        if (!streamOpen) {
            throw new IllegalStateException("Stream is not open");
        }
        streamActive = true;
    }

    @Override
    public void stopStream() {
        streamActive = false;
    }

    @Override
    public void closeStream() {
        streamActive = false;
        streamOpen = false;
        callback = null;
    }

    @Override
    public LatencyInfo getLatencyInfo() {
        if (!streamOpen) {
            throw new IllegalStateException("Stream is not open");
        }
        int frames = config.bufferSize().getFrames();
        return LatencyInfo.of(0, 0, frames, config.sampleRate().getHz());
    }

    @Override
    public boolean isStreamActive() {
        return streamActive;
    }

    @Override
    public String getBackendName() {
        return "Headless";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void close() {
        closeStream();
        initialized = false;
    }

    // ── Test-driven interaction ──────────────────────────────────────────────

    /**
     * Replaces the input generator invoked before each callback. By default,
     * inputs are silent zeros. Tests can install a sine-wave or noise
     * generator to exercise passthrough and DSP code paths.
     *
     * @param generator the input generator (must not be null)
     */
    public void setInputGenerator(InputGenerator generator) {
        this.inputGenerator = Objects.requireNonNull(generator, "generator");
    }

    /**
     * Drives the registered audio callback repeatedly until the requested
     * number of sample frames have been processed. Each invocation uses the
     * buffer size from the configured {@link AudioStreamConfig}; the final
     * block may be shorter if {@code totalFrames} is not an exact multiple.
     *
     * @param totalFrames the total number of frames to render
     * @throws IllegalStateException if the stream is not active
     */
    public void drive(int totalFrames) {
        if (!streamActive || callback == null) {
            throw new IllegalStateException("Stream is not active");
        }
        if (totalFrames < 0) {
            throw new IllegalArgumentException("totalFrames must be >= 0: " + totalFrames);
        }

        int inChannels = Math.max(config.inputChannels(), 0);
        int outChannels = Math.max(config.outputChannels(), 1);
        int blockSize = config.bufferSize().getFrames();

        float[][] input = new float[inChannels][blockSize];
        float[][] output = new float[outChannels][blockSize];

        int rendered = 0;
        while (rendered < totalFrames) {
            int thisBlock = Math.min(blockSize, totalFrames - rendered);

            for (int ch = 0; ch < outChannels; ch++) {
                Arrays.fill(output[ch], 0, thisBlock, 0f);
            }
            // Pass the cumulative frame offset (across all drive() calls)
            // so input generators can produce continuous signals even when
            // a caller splits a long render into multiple drive() invocations.
            inputGenerator.generate(input, thisBlock, capturedFrames);

            callback.process(input, output, thisBlock);

            // Snapshot the rendered block so the next iteration does not
            // overwrite captured data.
            float[][] snapshot = new float[outChannels][thisBlock];
            for (int ch = 0; ch < outChannels; ch++) {
                System.arraycopy(output[ch], 0, snapshot[ch], 0, thisBlock);
            }
            capturedBlocks.add(snapshot);

            rendered += thisBlock;
            capturedFrames += thisBlock;
        }
    }

    /**
     * Returns all captured output frames as a contiguous
     * {@code double[channels][frames]} buffer. The returned array is a
     * fresh copy; subsequent calls to {@link #drive(int)} do not mutate it.
     *
     * @return the captured output, or an empty array if nothing was driven
     */
    public double[][] getCapturedOutput() {
        if (capturedBlocks.isEmpty()) {
            return new double[0][0];
        }
        int channels = capturedBlocks.get(0).length;
        double[][] out = new double[channels][capturedFrames];
        int offset = 0;
        for (float[][] block : capturedBlocks) {
            int n = block[0].length;
            for (int ch = 0; ch < channels; ch++) {
                for (int i = 0; i < n; i++) {
                    out[ch][offset + i] = block[ch][i];
                }
            }
            offset += n;
        }
        return out;
    }

    /** Clears the captured output buffer without touching stream state. */
    public void clearCapturedOutput() {
        capturedBlocks.clear();
        capturedFrames = 0;
    }

    /** Returns the number of captured sample frames across all blocks. */
    public int getCapturedFrameCount() {
        return capturedFrames;
    }

    /** Returns the registered audio callback, or {@code null} if none. */
    public AudioStreamCallback getRegisteredCallback() {
        return callback;
    }

    /** Returns the stream configuration, or {@code null} if not opened. */
    public AudioStreamConfig getStreamConfig() {
        return config;
    }

    /** Returns {@code true} once {@link #initialize()} has been called. */
    public boolean isInitialized() {
        return initialized;
    }

    /** Returns {@code true} while a stream is open (between open/close). */
    public boolean isStreamOpen() {
        return streamOpen;
    }

    /**
     * Generates input samples for the next callback. Used by the harness
     * to feed deterministic test signals (sine waves, noise, silence)
     * into the engine.
     */
    @FunctionalInterface
    public interface InputGenerator {
        /**
         * Fills {@code inputBuffer[channel][0..numFrames-1]} with samples for
         * the next callback invocation.
         *
         * @param inputBuffer    the buffer to fill
         * @param numFrames      the number of frames to produce in this block
         * @param framesRendered the total number of frames already rendered
         *                       prior to this block (for phase/time tracking)
         */
        void generate(float[][] inputBuffer, int numFrames, int framesRendered);
    }
}
