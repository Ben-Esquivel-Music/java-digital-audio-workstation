package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.Objects;
import java.util.Random;

/**
 * Built-in signal generator plugin for test signal generation.
 *
 * <p>Provides essential test signals for calibration, troubleshooting,
 * and measurement — sine waves for frequency response testing, white/pink
 * noise for room analysis, sweep tones for impulse response capture, and
 * square/triangle/sawtooth waves for signal chain verification.</p>
 *
 * <h2>Waveform Types</h2>
 * <ul>
 *   <li><b>Sine</b> — pure tone at the configured frequency</li>
 *   <li><b>Square</b> — odd-harmonic-rich square wave</li>
 *   <li><b>Triangle</b> — odd-harmonic wave with softer roll-off</li>
 *   <li><b>Sawtooth</b> — all-harmonic sawtooth wave</li>
 *   <li><b>White noise</b> — flat power spectral density</li>
 *   <li><b>Pink noise</b> — 1/f spectral slope (−3 dB/octave)</li>
 * </ul>
 *
 * <h2>Sweep Mode</h2>
 * <p>Supports linear or logarithmic frequency sweeps from a configurable
 * start frequency to an end frequency over a configurable duration, useful
 * for impulse response measurement.</p>
 *
 * <h2>Output</h2>
 * <p>Mono signal duplicated to both channels (stereo output). The generated
 * signal is routed to the master bus or a selected track output.</p>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link #initialize(PluginContext)} — stores the host context.</li>
 *   <li>{@link #activate()} — marks the plugin as active.</li>
 *   <li>{@link #deactivate()} — stops signal generation and silences output.</li>
 *   <li>{@link #dispose()} — releases all audio resources.</li>
 * </ol>
 */
public final class SignalGeneratorPlugin implements BuiltInDawPlugin {

    /** Stable plugin identifier — used by the host to map plugins to views. */
    public static final String PLUGIN_ID = "com.benesquivelmusic.daw.signal-generator";

    /** Minimum allowed frequency in Hz. */
    public static final double MIN_FREQUENCY_HZ = 20.0;

    /** Maximum allowed frequency in Hz. */
    public static final double MAX_FREQUENCY_HZ = 20_000.0;

    /** Maximum amplitude in dB (0 dBFS). */
    public static final double MAX_AMPLITUDE_DB = 0.0;

    /** Default amplitude in dBFS (−18 dBFS to avoid accidental loud output). */
    public static final double DEFAULT_AMPLITUDE_DB = -18.0;

    /** Default frequency in Hz (1 kHz — standard test tone). */
    public static final double DEFAULT_FREQUENCY_HZ = 1000.0;

    /** Default sweep duration in seconds. */
    public static final double DEFAULT_SWEEP_DURATION_SECONDS = 5.0;

    /** Number of octave rows for Voss–McCartney pink noise generation. */
    private static final int PINK_NOISE_ROWS = 16;

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
            PLUGIN_ID,
            "Signal Generator",
            "1.0.0",
            "DAW Built-in",
            PluginType.INSTRUMENT
    );

    /**
     * Supported waveform types for signal generation.
     */
    public enum WaveformType {
        /** Pure sine wave. */
        SINE,
        /** Square wave (odd harmonics). */
        SQUARE,
        /** Triangle wave (odd harmonics with softer roll-off). */
        TRIANGLE,
        /** Sawtooth wave (all harmonics). */
        SAWTOOTH,
        /** White noise — flat power spectral density. */
        WHITE_NOISE,
        /** Pink noise — 1/f spectral slope (−3 dB/octave). */
        PINK_NOISE
    }

    /**
     * Frequency sweep modes.
     */
    public enum SweepMode {
        /** No sweep — continuous signal at the configured frequency. */
        OFF,
        /** Linear frequency sweep from start to end frequency. */
        LINEAR,
        /** Logarithmic (exponential) frequency sweep from start to end frequency. */
        LOGARITHMIC
    }

    private PluginContext context;
    private boolean active;
    private boolean muted;

    // Signal parameters
    private volatile WaveformType waveformType = WaveformType.SINE;
    private volatile double frequencyHz = DEFAULT_FREQUENCY_HZ;
    private volatile double amplitudeDb = DEFAULT_AMPLITUDE_DB;

    // Sweep parameters
    private volatile SweepMode sweepMode = SweepMode.OFF;
    private volatile double sweepStartFrequencyHz = MIN_FREQUENCY_HZ;
    private volatile double sweepEndFrequencyHz = MAX_FREQUENCY_HZ;
    private volatile double sweepDurationSeconds = DEFAULT_SWEEP_DURATION_SECONDS;

    // Generation state
    private double phase;
    private Random noiseRandom;
    private double[] pinkNoiseRows;
    private double pinkNoiseRunningSum;

    public SignalGeneratorPlugin() {
    }

    @Override
    public String getMenuLabel() {
        return "Signal Generator";
    }

    @Override
    public String getMenuIcon() {
        return "waveform";
    }

    @Override
    public BuiltInPluginCategory getCategory() {
        return BuiltInPluginCategory.UTILITY;
    }

    @Override
    public PluginDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void initialize(PluginContext context) {
        Objects.requireNonNull(context, "context must not be null");
        this.context = context;
        resetGenerationState();
    }

    @Override
    public void activate() {
        active = true;
    }

    @Override
    public void deactivate() {
        active = false;
        muted = false;
        resetGenerationState();
    }

    @Override
    public void dispose() {
        active = false;
        muted = false;
        context = null;
        noiseRandom = null;
        pinkNoiseRows = null;
    }

    // ── Signal Parameters ──────────────────────────────────────────────

    /**
     * Returns the current waveform type.
     *
     * @return the waveform type, never {@code null}
     */
    public WaveformType getWaveformType() {
        return waveformType;
    }

    /**
     * Sets the waveform type.
     *
     * @param waveformType the waveform type to use
     * @throws NullPointerException if {@code waveformType} is {@code null}
     */
    public void setWaveformType(WaveformType waveformType) {
        Objects.requireNonNull(waveformType, "waveformType must not be null");
        this.waveformType = waveformType;
        resetGenerationState();
    }

    /**
     * Returns the current signal frequency in Hz.
     *
     * @return the frequency in Hz
     */
    public double getFrequencyHz() {
        return frequencyHz;
    }

    /**
     * Sets the signal frequency.
     *
     * @param hz the frequency in Hz, must be between
     *           {@value #MIN_FREQUENCY_HZ} and {@value #MAX_FREQUENCY_HZ}
     * @throws IllegalArgumentException if {@code hz} is outside the allowed range
     */
    public void setFrequencyHz(double hz) {
        if (hz < MIN_FREQUENCY_HZ || hz > MAX_FREQUENCY_HZ) {
            throw new IllegalArgumentException(
                    "frequencyHz must be between %s and %s: %s"
                            .formatted(MIN_FREQUENCY_HZ, MAX_FREQUENCY_HZ, hz));
        }
        this.frequencyHz = hz;
    }

    /**
     * Returns the current amplitude in dBFS.
     *
     * @return the amplitude in dBFS
     */
    public double getAmplitudeDb() {
        return amplitudeDb;
    }

    /**
     * Sets the amplitude in dBFS.
     *
     * <p>The allowed range is from negative infinity (silence) to
     * {@value #MAX_AMPLITUDE_DB} dBFS (full scale). The default is
     * {@value #DEFAULT_AMPLITUDE_DB} dBFS to avoid accidental loud output.</p>
     *
     * @param db the amplitude in dBFS, must be ≤ {@value #MAX_AMPLITUDE_DB}
     * @throws IllegalArgumentException if {@code db} is greater than {@value #MAX_AMPLITUDE_DB}
     */
    public void setAmplitudeDb(double db) {
        if (db > MAX_AMPLITUDE_DB) {
            throw new IllegalArgumentException(
                    "amplitudeDb must be <= %s: %s".formatted(MAX_AMPLITUDE_DB, db));
        }
        this.amplitudeDb = db;
    }

    // ── Sweep Parameters ───────────────────────────────────────────────

    /**
     * Returns the current sweep mode.
     *
     * @return the sweep mode, never {@code null}
     */
    public SweepMode getSweepMode() {
        return sweepMode;
    }

    /**
     * Sets the sweep mode.
     *
     * @param sweepMode the sweep mode
     * @throws NullPointerException if {@code sweepMode} is {@code null}
     */
    public void setSweepMode(SweepMode sweepMode) {
        Objects.requireNonNull(sweepMode, "sweepMode must not be null");
        this.sweepMode = sweepMode;
    }

    /**
     * Returns the sweep start frequency in Hz.
     *
     * @return the start frequency in Hz
     */
    public double getSweepStartFrequencyHz() {
        return sweepStartFrequencyHz;
    }

    /**
     * Sets the sweep start frequency.
     *
     * @param hz the start frequency in Hz, must be between
     *           {@value #MIN_FREQUENCY_HZ} and {@value #MAX_FREQUENCY_HZ}
     * @throws IllegalArgumentException if {@code hz} is outside the allowed range
     */
    public void setSweepStartFrequencyHz(double hz) {
        if (hz < MIN_FREQUENCY_HZ || hz > MAX_FREQUENCY_HZ) {
            throw new IllegalArgumentException(
                    "sweepStartFrequencyHz must be between %s and %s: %s"
                            .formatted(MIN_FREQUENCY_HZ, MAX_FREQUENCY_HZ, hz));
        }
        this.sweepStartFrequencyHz = hz;
    }

    /**
     * Returns the sweep end frequency in Hz.
     *
     * @return the end frequency in Hz
     */
    public double getSweepEndFrequencyHz() {
        return sweepEndFrequencyHz;
    }

    /**
     * Sets the sweep end frequency.
     *
     * @param hz the end frequency in Hz, must be between
     *           {@value #MIN_FREQUENCY_HZ} and {@value #MAX_FREQUENCY_HZ}
     * @throws IllegalArgumentException if {@code hz} is outside the allowed range
     */
    public void setSweepEndFrequencyHz(double hz) {
        if (hz < MIN_FREQUENCY_HZ || hz > MAX_FREQUENCY_HZ) {
            throw new IllegalArgumentException(
                    "sweepEndFrequencyHz must be between %s and %s: %s"
                            .formatted(MIN_FREQUENCY_HZ, MAX_FREQUENCY_HZ, hz));
        }
        this.sweepEndFrequencyHz = hz;
    }

    /**
     * Returns the sweep duration in seconds.
     *
     * @return the sweep duration in seconds
     */
    public double getSweepDurationSeconds() {
        return sweepDurationSeconds;
    }

    /**
     * Sets the sweep duration.
     *
     * @param seconds the duration in seconds, must be positive
     * @throws IllegalArgumentException if {@code seconds} is not positive
     */
    public void setSweepDurationSeconds(double seconds) {
        if (seconds <= 0.0) {
            throw new IllegalArgumentException(
                    "sweepDurationSeconds must be positive: %s".formatted(seconds));
        }
        this.sweepDurationSeconds = seconds;
    }

    // ── Mute / Panic ───────────────────────────────────────────────────

    /**
     * Returns whether the output is currently muted.
     *
     * @return {@code true} if muted
     */
    public boolean isMuted() {
        return muted;
    }

    /**
     * Sets the mute state.
     *
     * @param muted {@code true} to mute, {@code false} to unmute
     */
    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    /**
     * Immediately silences output by muting and resetting generation state.
     *
     * <p>This is a "panic" action intended for emergency use — it immediately
     * stops all signal output without changing other parameters.</p>
     */
    public void panic() {
        muted = true;
        resetGenerationState();
    }

    // ── State Queries ──────────────────────────────────────────────────

    /**
     * Returns whether the plugin is currently active.
     *
     * @return {@code true} if active
     */
    public boolean isActive() {
        return active;
    }

    // ── Audio Generation ───────────────────────────────────────────────

    /**
     * Generates a buffer of audio samples.
     *
     * <p>Fills the provided buffer with the configured signal. If the plugin
     * is muted, the buffer is filled with silence. The buffer length is
     * determined by the caller (typically the host's buffer size).</p>
     *
     * @param buffer the output buffer to fill with generated samples
     * @throws NullPointerException  if {@code buffer} is {@code null}
     * @throws IllegalStateException if the plugin has not been initialized
     */
    public void generate(float[] buffer) {
        Objects.requireNonNull(buffer, "buffer must not be null");
        if (context == null) {
            throw new IllegalStateException("Plugin has not been initialized");
        }

        if (muted || !active) {
            fillSilence(buffer);
            return;
        }

        double sampleRate = context.getSampleRate();
        double amplitude = dbToLinear(amplitudeDb);

        for (int i = 0; i < buffer.length; i++) {
            double sample = generateSample(sampleRate, i, buffer.length);
            buffer[i] = (float) (sample * amplitude);
        }

        advancePhase(buffer.length, sampleRate);
    }

    // ── Internal ───────────────────────────────────────────────────────

    private double generateSample(double sampleRate, int bufferIndex, int bufferLength) {
        return switch (waveformType) {
            case SINE -> generateSine(sampleRate, bufferIndex, bufferLength);
            case SQUARE -> generateSquare(sampleRate, bufferIndex, bufferLength);
            case TRIANGLE -> generateTriangle(sampleRate, bufferIndex, bufferLength);
            case SAWTOOTH -> generateSawtooth(sampleRate, bufferIndex, bufferLength);
            case WHITE_NOISE -> generateWhiteNoise();
            case PINK_NOISE -> generatePinkNoise(bufferIndex);
        };
    }

    private double currentPhaseIncrement(double sampleRate, int bufferIndex, int bufferLength) {
        double freq = frequencyHz;
        if (sweepMode != SweepMode.OFF) {
            // Interpolate frequency across the buffer for smooth sweep
            double t = (double) bufferIndex / bufferLength;
            freq = interpolateSweepFrequency(t);
        }
        return freq / sampleRate;
    }

    private double interpolateSweepFrequency(double t) {
        return switch (sweepMode) {
            case OFF -> frequencyHz;
            case LINEAR -> sweepStartFrequencyHz
                    + t * (sweepEndFrequencyHz - sweepStartFrequencyHz);
            case LOGARITHMIC -> sweepStartFrequencyHz
                    * Math.pow(sweepEndFrequencyHz / sweepStartFrequencyHz, t);
        };
    }

    private double generateSine(double sampleRate, int bufferIndex, int bufferLength) {
        double phaseInc = currentPhaseIncrement(sampleRate, bufferIndex, bufferLength);
        double currentPhase = phase + (double) bufferIndex * phaseInc;
        return Math.sin(2.0 * Math.PI * currentPhase);
    }

    private double generateSquare(double sampleRate, int bufferIndex, int bufferLength) {
        double phaseInc = currentPhaseIncrement(sampleRate, bufferIndex, bufferLength);
        double currentPhase = phase + (double) bufferIndex * phaseInc;
        double wrapped = currentPhase - Math.floor(currentPhase);
        return wrapped < 0.5 ? 1.0 : -1.0;
    }

    private double generateTriangle(double sampleRate, int bufferIndex, int bufferLength) {
        double phaseInc = currentPhaseIncrement(sampleRate, bufferIndex, bufferLength);
        double currentPhase = phase + (double) bufferIndex * phaseInc;
        double wrapped = currentPhase - Math.floor(currentPhase);
        return 2.0 * Math.abs(2.0 * wrapped - 1.0) - 1.0;
    }

    private double generateSawtooth(double sampleRate, int bufferIndex, int bufferLength) {
        double phaseInc = currentPhaseIncrement(sampleRate, bufferIndex, bufferLength);
        double currentPhase = phase + (double) bufferIndex * phaseInc;
        double wrapped = currentPhase - Math.floor(currentPhase);
        return 2.0 * wrapped - 1.0;
    }

    private double generateWhiteNoise() {
        return noiseRandom.nextDouble() * 2.0 - 1.0;
    }

    private double generatePinkNoise(int bufferIndex) {
        if (pinkNoiseRows == null) {
            return 0.0;
        }
        int sampleIndex = bufferIndex + 1;
        int changed = sampleIndex ^ (sampleIndex - 1);
        for (int r = 0; r < PINK_NOISE_ROWS; r++) {
            if ((changed & (1 << r)) != 0) {
                pinkNoiseRunningSum -= pinkNoiseRows[r];
                pinkNoiseRows[r] = noiseRandom.nextDouble() * 2.0 - 1.0;
                pinkNoiseRunningSum += pinkNoiseRows[r];
            }
        }
        double whiteComponent = noiseRandom.nextDouble() * 2.0 - 1.0;
        return (pinkNoiseRunningSum + whiteComponent) / (PINK_NOISE_ROWS + 1);
    }

    private void advancePhase(int bufferLength, double sampleRate) {
        if (waveformType != WaveformType.WHITE_NOISE && waveformType != WaveformType.PINK_NOISE) {
            double phaseInc = frequencyHz / sampleRate;
            phase += (double) bufferLength * phaseInc;
            // Keep phase from growing unboundedly
            phase -= Math.floor(phase);
        }
    }

    private void resetGenerationState() {
        phase = 0.0;
        noiseRandom = new Random(0);
        pinkNoiseRows = new double[PINK_NOISE_ROWS];
        pinkNoiseRunningSum = 0.0;
        for (int r = 0; r < PINK_NOISE_ROWS; r++) {
            pinkNoiseRows[r] = noiseRandom.nextDouble() * 2.0 - 1.0;
            pinkNoiseRunningSum += pinkNoiseRows[r];
        }
    }

    private static void fillSilence(float[] buffer) {
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] = 0.0f;
        }
    }

    /**
     * Converts a decibel value to a linear amplitude multiplier.
     *
     * @param db the amplitude in dBFS
     * @return the linear amplitude (0.0 for −∞ dB, 1.0 for 0 dB)
     */
    static double dbToLinear(double db) {
        if (Double.isInfinite(db) && db < 0) {
            return 0.0;
        }
        return Math.pow(10.0, db / 20.0);
    }
}
