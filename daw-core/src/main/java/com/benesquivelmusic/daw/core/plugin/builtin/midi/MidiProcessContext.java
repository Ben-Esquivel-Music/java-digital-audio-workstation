package com.benesquivelmusic.daw.core.plugin.builtin.midi;

/**
 * Per-block context passed to {@link MidiEffectPlugin#process}.
 *
 * <p>Provides the timing information a MIDI effect needs to schedule
 * outgoing events: the audio sample rate, the size of the current block
 * in frames, the musical position at the start of the block (in beats),
 * and the conversion factor between beats and samples at the current
 * tempo. With these four scalars an effect can render any rate-based
 * pattern with sample-accurate timing.</p>
 *
 * @param sampleRate     the audio sample rate in Hz
 * @param blockSize      the current processing block size, in frames
 * @param startBeat      the musical position at frame 0 of the block, in beats
 * @param samplesPerBeat the number of samples that elapse during one beat
 */
public record MidiProcessContext(double sampleRate,
                                 int blockSize,
                                 double startBeat,
                                 double samplesPerBeat) {

    /** Compact constructor — validates the timing scalars. */
    public MidiProcessContext {
        if (sampleRate <= 0.0) {
            throw new IllegalArgumentException("sampleRate must be > 0: " + sampleRate);
        }
        if (blockSize <= 0) {
            throw new IllegalArgumentException("blockSize must be > 0: " + blockSize);
        }
        if (samplesPerBeat <= 0.0) {
            throw new IllegalArgumentException("samplesPerBeat must be > 0: " + samplesPerBeat);
        }
    }
}
