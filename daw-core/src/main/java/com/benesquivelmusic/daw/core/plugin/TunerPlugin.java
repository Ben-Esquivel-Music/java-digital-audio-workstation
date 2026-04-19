package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.analysis.PitchDetector;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.Objects;

/**
 * Built-in chromatic tuner plugin.
 *
 * <p>Wraps the DAW's {@link PitchDetector} as a first-class plugin
 * so it appears in the Plugins menu alongside external plugins.  The tuner
 * analyzes incoming monophonic audio and reports the nearest musical note,
 * octave, frequency, and cents offset from the target pitch.</p>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link #initialize(PluginContext)} — creates a {@link PitchDetector}
 *       configured with the context's sample rate.</li>
 *   <li>{@link #activate()} — marks the plugin as active.</li>
 *   <li>{@link #deactivate()} — marks inactive and clears the last tuning result.</li>
 *   <li>{@link #dispose()} — releases the pitch detector instance.</li>
 * </ol>
 *
 * <h2>Reference pitch</h2>
 * <p>The default reference pitch for A4 is 440 Hz.  It can be adjusted from
 * {@value #MIN_REFERENCE_PITCH_HZ} Hz to {@value #MAX_REFERENCE_PITCH_HZ} Hz
 * to support alternate tuning standards (e.g., A4 = 432 Hz baroque pitch
 * or A4 = 443 Hz orchestral pitch).</p>
 */
@BuiltInPlugin(label = "Chromatic Tuner", icon = "spectrum", category = BuiltInPluginCategory.UTILITY)
public final class TunerPlugin implements BuiltInDawPlugin {

    /** Stable plugin identifier — used by the host to map plugins to views. */
    public static final String PLUGIN_ID = "com.benesquivelmusic.daw.tuner";

    /** Default reference pitch for A4 in Hz. */
    public static final double DEFAULT_REFERENCE_PITCH_HZ = 440.0;

    /** Minimum allowed reference pitch for A4 in Hz. */
    public static final double MIN_REFERENCE_PITCH_HZ = 415.0;

    /** Maximum allowed reference pitch for A4 in Hz. */
    public static final double MAX_REFERENCE_PITCH_HZ = 466.0;

    /** Default pitch detection buffer size in samples. */
    static final int DEFAULT_BUFFER_SIZE = 4096;

    /** Cents threshold within which the pitch is considered "in tune". */
    static final double IN_TUNE_CENTS = 3.0;

    private static final String[] NOTE_NAMES = {
            "C", "C#", "D", "D#", "E", "F",
            "F#", "G", "G#", "A", "A#", "B"
    };

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
            PLUGIN_ID,
            "Chromatic Tuner",
            "1.0.0",
            "DAW Built-in",
            PluginType.ANALYZER
    );

    /** Stored for potential future reconfiguration (e.g., changing buffer size). */
    private PluginContext context;
    private PitchDetector pitchDetector;
    private boolean active;
    private volatile double referencePitchHz = DEFAULT_REFERENCE_PITCH_HZ;
    private volatile TuningResult lastResult;

    /**
     * Result of a tuning analysis.
     *
     * @param noteName    the nearest note name (e.g., "A", "C#")
     * @param octave      the octave number (e.g., 4 for A4)
     * @param frequencyHz the detected fundamental frequency in Hz
     * @param centsOffset cents sharp (+) or flat (−) from the nearest semitone,
     *                    in the range [−50, +50]
     * @param inTune      {@code true} if {@code |centsOffset|} ≤ {@value #IN_TUNE_CENTS}
     */
    public record TuningResult(
            String noteName,
            int octave,
            double frequencyHz,
            double centsOffset,
            boolean inTune
    ) {
        public TuningResult {
            Objects.requireNonNull(noteName, "noteName must not be null");
        }
    }

    public TunerPlugin() {
    }

    @Override
    public PluginDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void initialize(PluginContext context) {
        Objects.requireNonNull(context, "context must not be null");
        this.context = context;
        pitchDetector = new PitchDetector(DEFAULT_BUFFER_SIZE, context.getSampleRate());
    }

    @Override
    public void activate() {
        active = true;
    }

    @Override
    public void deactivate() {
        active = false;
        lastResult = null;
    }

    @Override
    public void dispose() {
        active = false;
        pitchDetector = null;
        context = null;
        lastResult = null;
    }

    /**
     * Returns the current reference pitch for A4.
     *
     * @return the reference pitch in Hz
     */
    public double getReferencePitchHz() {
        return referencePitchHz;
    }

    /**
     * Sets the reference pitch for A4.
     *
     * @param hz the new reference pitch in Hz, must be between
     *           {@value #MIN_REFERENCE_PITCH_HZ} and {@value #MAX_REFERENCE_PITCH_HZ}
     * @throws IllegalArgumentException if {@code hz} is outside the allowed range
     */
    public void setReferencePitchHz(double hz) {
        if (hz < MIN_REFERENCE_PITCH_HZ || hz > MAX_REFERENCE_PITCH_HZ) {
            throw new IllegalArgumentException(
                    "referencePitchHz must be between %s and %s: %s"
                            .formatted(MIN_REFERENCE_PITCH_HZ, MAX_REFERENCE_PITCH_HZ, hz));
        }
        this.referencePitchHz = hz;
    }

    /**
     * Analyzes a buffer of mono audio samples and returns the tuning result.
     *
     * <p>Returns {@code null} when the pitch detector does not detect a
     * pitched signal (i.e., the input is silent or too noisy).</p>
     *
     * @param samples mono audio samples; length must be ≥ the detector's buffer size
     *                ({@value #DEFAULT_BUFFER_SIZE})
     * @return the tuning result, or {@code null} if no pitched signal is detected
     * @throws IllegalStateException    if the plugin has not been initialized
     * @throws NullPointerException     if {@code samples} is {@code null}
     * @throws IllegalArgumentException if {@code samples.length} is less than
     *                                  the detector's buffer size
     */
    public TuningResult process(float[] samples) {
        Objects.requireNonNull(samples, "samples must not be null");
        if (pitchDetector == null) {
            throw new IllegalStateException("Plugin has not been initialized");
        }
        if (samples.length < DEFAULT_BUFFER_SIZE) {
            throw new IllegalArgumentException(
                    "samples length (%d) must be >= buffer size (%d)"
                            .formatted(samples.length, DEFAULT_BUFFER_SIZE));
        }

        PitchDetector.PitchResult pitchResult = pitchDetector.detect(samples);
        if (!pitchResult.pitched()) {
            lastResult = null;
            return null;
        }

        lastResult = toTuningResult(pitchResult.frequencyHz());
        return lastResult;
    }

    /**
     * Returns the result of the most recent {@link #process(float[])} call,
     * or {@code null} if no pitched signal has been detected yet (or after
     * {@link #deactivate()}).
     *
     * @return the last tuning result, or {@code null}
     */
    public TuningResult getLastResult() {
        return lastResult;
    }

    /**
     * Returns the underlying {@link PitchDetector} created during
     * {@link #initialize(PluginContext)}, or {@code null} if the plugin
     * has not been initialized or has been disposed.
     *
     * @return the pitch detector, or {@code null}
     */
    public PitchDetector getPitchDetector() {
        return pitchDetector;
    }

    /**
     * Returns whether the plugin is currently active.
     *
     * @return {@code true} if active
     */
    public boolean isActive() {
        return active;
    }

    // ── Pitch-to-note conversion ────────────────────────────────────────

    /**
     * Converts a detected frequency to a {@link TuningResult} using the
     * current reference pitch.
     */
    private TuningResult toTuningResult(double frequencyHz) {
        // Number of semitones from A4 (using equal temperament)
        double semitones = 12.0 * Math.log(frequencyHz / referencePitchHz) / Math.log(2.0);
        int nearestSemitone = (int) Math.round(semitones);
        double centsOffset = (semitones - nearestSemitone) * 100.0;

        // A4 is MIDI note 69 → note index 9, octave 4
        int midiNote = 69 + nearestSemitone;
        int noteIndex = Math.floorMod(midiNote, 12);
        int octave = Math.floorDiv(midiNote, 12) - 1;

        String noteName = NOTE_NAMES[noteIndex];
        boolean inTune = Math.abs(centsOffset) <= IN_TUNE_CENTS;

        return new TuningResult(noteName, octave, frequencyHz, centsOffset, inTune);
    }
}
