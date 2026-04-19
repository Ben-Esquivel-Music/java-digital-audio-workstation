package com.benesquivelmusic.daw.core.audio.harness;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.sdk.audio.AudioStreamCallback;
import com.benesquivelmusic.daw.sdk.audio.AudioStreamConfig;
import com.benesquivelmusic.daw.sdk.audio.BufferSize;
import com.benesquivelmusic.daw.sdk.audio.SampleRate;

import java.time.Duration;
import java.util.Objects;
import java.util.Random;

/**
 * Deterministic, offline test harness for the DAW audio engine.
 *
 * <p>The harness lets integration tests render a {@link DawProject} (or a
 * synthetic signal) into an in-memory buffer without touching any audio
 * device, JavaFX toolkit, or background threads. Every render is
 * byte-reproducible given the same seed and inputs.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * var harness = new HeadlessAudioHarness(AudioFormat.CD_QUALITY);
 * harness.load(project);
 * double[][] audio = harness.renderRange(0, (long) (2.0 * 44_100));
 * HeadlessAudioHarness.assertRenderMatches(goldenPath, audio, -90.0);
 * }</pre>
 *
 * <h2>Determinism</h2>
 * <ul>
 *   <li>A fixed random seed (default {@value #DEFAULT_SEED}) is exposed via
 *       {@link #getRandom()} for tests that need pseudo-random input signals
 *       or parameter jitter.</li>
 *   <li>All processing happens synchronously on the calling thread — no
 *       wall-clock jitter from scheduling affects the rendered audio.</li>
 * </ul>
 *
 * <h2>Timeouts</h2>
 * <p>{@link #renderRange(long, long)} enforces a wall-clock budget
 * (default {@link #DEFAULT_TIMEOUT}) so a runaway render cannot hang CI.
 * See {@link #setTimeout(Duration)} and {@link #playAtSpeed(double)}.</p>
 *
 * <p>Not thread-safe.</p>
 */
public final class HeadlessAudioHarness implements AutoCloseable {

    /** Default seed used by {@link #getRandom()} unless overridden. */
    public static final long DEFAULT_SEED = 0xC0FFEE_D15CL;

    /** Default wall-clock timeout enforced by {@link #renderRange(long, long)}. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final AudioFormat format;
    private final HeadlessAudioBackend backend = new HeadlessAudioBackend();
    private final AudioEngine engine;

    private DawProject project;
    private double speedFactor = 1.0;
    private Duration timeout = DEFAULT_TIMEOUT;
    private long seed = DEFAULT_SEED;
    private Random random = new Random(seed);

    /**
     * Creates a harness using the given audio format.
     *
     * @param format the audio format (sample rate, channels, block size)
     */
    public HeadlessAudioHarness(AudioFormat format) {
        this.format = Objects.requireNonNull(format, "format");
        this.engine = new AudioEngine(format);
        this.engine.setAudioBackend(backend);
    }

    // ── Configuration ────────────────────────────────────────────────────────

    /**
     * Loads a project into the harness, wiring its transport, mixer, and
     * tracks into the underlying {@link AudioEngine} for rendering.
     *
     * @param project the project to render (may be {@code null} to unload)
     * @return this harness
     */
    public HeadlessAudioHarness load(DawProject project) {
        this.project = project;
        if (project != null) {
            engine.setTransport(project.getTransport());
            engine.setMixer(project.getMixer());
            engine.setTracks(java.util.List.copyOf(project.getTracks()));
        } else {
            engine.setTransport(null);
            engine.setMixer(null);
            engine.setTracks(null);
        }
        return this;
    }

    /**
     * Sets the "playback speed" used to compute the wall-clock budget for
     * {@link #renderRange(long, long)}. Values {@code > 1.0} allow longer
     * sessions to be rendered faster than real time in stress tests.
     *
     * @param speedFactor the speed factor ({@code > 0}, defaults to 1.0)
     * @return this harness
     */
    public HeadlessAudioHarness playAtSpeed(double speedFactor) {
        if (!(speedFactor > 0.0) || Double.isInfinite(speedFactor) || Double.isNaN(speedFactor)) {
            throw new IllegalArgumentException("speedFactor must be a positive finite number: " + speedFactor);
        }
        this.speedFactor = speedFactor;
        return this;
    }

    /**
     * Overrides the wall-clock timeout for subsequent {@link #renderRange}
     * calls. Pass {@link Duration#ZERO} or a negative duration to disable.
     *
     * @param timeout the new timeout
     * @return this harness
     */
    public HeadlessAudioHarness setTimeout(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        return this;
    }

    /**
     * Reseeds the deterministic {@link Random} exposed to tests via
     * {@link #getRandom()}.
     *
     * @param seed the new seed
     * @return this harness
     */
    public HeadlessAudioHarness withSeed(long seed) {
        this.seed = seed;
        this.random = new Random(seed);
        return this;
    }

    /**
     * Installs a test input generator (e.g., a sine wave) fed to the engine's
     * input on every block. Useful for passthrough/self-tests when no
     * project is loaded.
     *
     * @param generator the generator (must not be {@code null})
     * @return this harness
     */
    public HeadlessAudioHarness setInputGenerator(HeadlessAudioBackend.InputGenerator generator) {
        backend.setInputGenerator(generator);
        return this;
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    /**
     * Renders the frames in the half-open range {@code [startFrame, endFrame)}
     * and returns them as a {@code double[channels][frames]} buffer.
     *
     * <p>The harness drives the audio engine synchronously and captures
     * every output block. If the session exposes a {@link com.benesquivelmusic.daw.core.transport.Transport},
     * it is repositioned to {@code startFrame} before rendering begins.</p>
     *
     * @param startFrame the first frame to render (inclusive, {@code >= 0})
     * @param endFrame   the end frame (exclusive, {@code >= startFrame})
     * @return the rendered audio
     * @throws IllegalArgumentException if the range is invalid
     * @throws HeadlessTimeoutException if rendering exceeds the configured timeout
     */
    public double[][] renderRange(long startFrame, long endFrame) {
        if (startFrame < 0 || endFrame < startFrame) {
            throw new IllegalArgumentException(
                    "Invalid range: start=" + startFrame + " end=" + endFrame);
        }
        long frameCount = endFrame - startFrame;
        if (frameCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Range too large: " + frameCount);
        }

        ensureStarted();
        backend.clearCapturedOutput();

        // Re-position the transport if a project is loaded. Our transport
        // works in beats; translate frames → beats using the initial tempo.
        if (project != null) {
            double tempo = project.getTransport().getTempo();
            double seconds = startFrame / format.sampleRate();
            double beats = seconds * tempo / 60.0;
            project.getTransport().setPositionInBeats(beats);
            project.getTransport().play();
        }

        long start = System.nanoTime();
        long budgetNanos = computeBudgetNanos(frameCount);

        // Drive the backend block-by-block so we can check the wall-clock
        // budget between blocks instead of only at the very end.
        int blockSize = format.bufferSize();
        int remaining = (int) frameCount;
        while (remaining > 0) {
            int thisBlock = Math.min(blockSize, remaining);
            backend.drive(thisBlock);
            remaining -= thisBlock;

            long elapsed = System.nanoTime() - start;
            if (budgetNanos > 0 && elapsed > budgetNanos) {
                throw new HeadlessTimeoutException(
                        "Render exceeded " + Duration.ofNanos(budgetNanos) + " (elapsed: "
                                + Duration.ofNanos(elapsed) + ")");
            }
        }

        if (project != null) {
            project.getTransport().pause();
        }

        return backend.getCapturedOutput();
    }

    private long computeBudgetNanos(long frameCount) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return 0L;
        }
        // Upper bound: the configured wall-clock timeout (default 10s), and
        // also the session duration divided by the speed factor so that a
        // 1-second render at 100x speed completes within ~10 ms of budget
        // rather than wasting 10 seconds before failing.
        double sessionSeconds = frameCount / format.sampleRate() / speedFactor;
        long sessionNanos = (long) Math.ceil(sessionSeconds * 1_000_000_000.0);
        long hardCapNanos = timeout.toNanos();
        // Always allow at least the hard cap — renderers that are faster
        // than real time should never fail because sessionNanos < wall time.
        return Math.max(sessionNanos, hardCapNanos);
    }

    private void ensureStarted() {
        if (!engine.isRunning()) {
            AudioStreamConfig cfg = new AudioStreamConfig(
                    0,
                    0,
                    format.channels(),
                    format.channels(),
                    sampleRateFromHz((int) Math.round(format.sampleRate())),
                    BufferSize.fromFrames(format.bufferSize())
            );
            engine.start();
            backend.initialize();
            backend.openStream(cfg, (AudioStreamCallback) engine::processBlock);
            backend.startStream();
        }
    }

    private static SampleRate sampleRateFromHz(int hz) {
        try {
            return SampleRate.fromHz(hz);
        } catch (IllegalArgumentException ex) {
            return SampleRate.HZ_44100;
        }
    }

    // ── Golden-file comparison ───────────────────────────────────────────────

    /**
     * Asserts that {@code actual} matches the golden audio at {@code goldenFile}
     * within the given tolerance (in dBFS).
     *
     * <p>Use {@link GoldenAudioFile#write(java.nio.file.Path, double[][])} to
     * generate a golden file once, then this method in subsequent test runs.</p>
     *
     * @param goldenFile    the golden audio file on disk
     * @param actual        the rendered audio
     * @param toleranceDbfs the maximum permitted per-sample difference, in dBFS
     *                      (e.g., {@code -90.0} for near-perfect equality)
     * @throws AssertionError if the buffers differ beyond the tolerance
     */
    public static void assertRenderMatches(java.nio.file.Path goldenFile,
                                           double[][] actual,
                                           double toleranceDbfs) {
        GoldenAudioFile.assertMatches(goldenFile, actual, toleranceDbfs);
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    /** Returns the audio format the harness is rendering at. */
    public AudioFormat getFormat() {
        return format;
    }

    /** Returns the underlying {@link HeadlessAudioBackend}. */
    public HeadlessAudioBackend getBackend() {
        return backend;
    }

    /** Returns the underlying {@link AudioEngine}. */
    public AudioEngine getEngine() {
        return engine;
    }

    /** Returns the deterministic {@link Random} seeded with {@link #getSeed()}. */
    public Random getRandom() {
        return random;
    }

    /** Returns the current deterministic seed. */
    public long getSeed() {
        return seed;
    }

    /** Returns the current playback speed factor. */
    public double getSpeedFactor() {
        return speedFactor;
    }

    /** Returns the current wall-clock render timeout. */
    public Duration getTimeout() {
        return timeout;
    }

    @Override
    public void close() {
        try {
            if (engine.isRunning()) {
                engine.stop();
            }
        } finally {
            backend.close();
        }
    }

    /** Thrown by {@link #renderRange(long, long)} when the timeout is exceeded. */
    public static final class HeadlessTimeoutException extends RuntimeException {
        public HeadlessTimeoutException(String message) {
            super(message);
        }
    }
}
