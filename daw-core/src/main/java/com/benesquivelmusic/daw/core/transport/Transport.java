package com.benesquivelmusic.daw.core.transport;

/**
 * Controls the playback transport of the DAW (play, stop, record, pause).
 *
 * <p>The transport maintains the current playback position and state,
 * coordinating with the audio engine to start and stop audio processing.</p>
 */
public final class Transport {

    private static final double DEFAULT_LOOP_START = 0.0;
    private static final double DEFAULT_LOOP_END = 16.0;

    private TransportState state = TransportState.STOPPED;
    private double positionInBeats = 0.0;
    private double tempo = 120.0;
    private int timeSignatureNumerator = 4;
    private int timeSignatureDenominator = 4;
    private boolean loopEnabled = false;
    private double loopStartInBeats = DEFAULT_LOOP_START;
    private double loopEndInBeats = DEFAULT_LOOP_END;

    /** Starts playback from the current position. */
    public void play() {
        state = TransportState.PLAYING;
    }

    /** Stops playback and resets the position to zero. */
    public void stop() {
        state = TransportState.STOPPED;
        positionInBeats = 0.0;
    }

    /** Pauses playback at the current position. */
    public void pause() {
        if (state == TransportState.PLAYING || state == TransportState.RECORDING) {
            state = TransportState.PAUSED;
        }
    }

    /** Starts recording from the current position. */
    public void record() {
        state = TransportState.RECORDING;
    }

    /** Returns the current transport state. */
    public TransportState getState() {
        return state;
    }

    /** Returns the current playback position in beats. */
    public double getPositionInBeats() {
        return positionInBeats;
    }

    /** Sets the playback position in beats. */
    public void setPositionInBeats(double positionInBeats) {
        if (positionInBeats < 0) {
            throw new IllegalArgumentException("position must not be negative: " + positionInBeats);
        }
        this.positionInBeats = positionInBeats;
    }

    /** Returns the tempo in beats per minute (BPM). */
    public double getTempo() {
        return tempo;
    }

    /**
     * Sets the tempo in BPM.
     *
     * @param tempo BPM value (must be between 20 and 999)
     */
    public void setTempo(double tempo) {
        if (tempo < 20.0 || tempo > 999.0) {
            throw new IllegalArgumentException("tempo must be between 20 and 999 BPM: " + tempo);
        }
        this.tempo = tempo;
    }

    /** Returns the time signature numerator. */
    public int getTimeSignatureNumerator() {
        return timeSignatureNumerator;
    }

    /** Returns the time signature denominator. */
    public int getTimeSignatureDenominator() {
        return timeSignatureDenominator;
    }

    /**
     * Sets the time signature.
     *
     * @param numerator   beats per bar (e.g., 4)
     * @param denominator note value of each beat (e.g., 4 for quarter note)
     */
    public void setTimeSignature(int numerator, int denominator) {
        if (numerator <= 0) {
            throw new IllegalArgumentException("numerator must be positive: " + numerator);
        }
        if (denominator <= 0) {
            throw new IllegalArgumentException("denominator must be positive: " + denominator);
        }
        this.timeSignatureNumerator = numerator;
        this.timeSignatureDenominator = denominator;
    }

    /** Returns {@code true} if loop mode is enabled. */
    public boolean isLoopEnabled() {
        return loopEnabled;
    }

    /** Enables or disables loop mode. */
    public void setLoopEnabled(boolean loopEnabled) {
        this.loopEnabled = loopEnabled;
    }

    /** Returns the loop start position in beats. */
    public double getLoopStartInBeats() {
        return loopStartInBeats;
    }

    /** Returns the loop end position in beats. */
    public double getLoopEndInBeats() {
        return loopEndInBeats;
    }

    /**
     * Sets the loop region boundaries in beats.
     *
     * @param startInBeats loop start position (must be &ge; 0)
     * @param endInBeats   loop end position (must be greater than {@code startInBeats})
     */
    public void setLoopRegion(double startInBeats, double endInBeats) {
        if (startInBeats < 0) {
            throw new IllegalArgumentException("loop start must not be negative: " + startInBeats);
        }
        if (endInBeats <= startInBeats) {
            throw new IllegalArgumentException(
                    "loop end must be greater than loop start: start=" + startInBeats + ", end=" + endInBeats);
        }
        this.loopStartInBeats = startInBeats;
        this.loopEndInBeats = endInBeats;
    }

    /**
     * Advances the playback position by the given number of beats.
     *
     * <p>When loop mode is enabled, the position wraps back to the loop start
     * if it reaches or exceeds the loop end.</p>
     *
     * @param deltaBeats number of beats to advance (must be &ge; 0)
     */
    public void advancePosition(double deltaBeats) {
        if (deltaBeats < 0) {
            throw new IllegalArgumentException("deltaBeats must not be negative: " + deltaBeats);
        }
        positionInBeats += deltaBeats;
        if (loopEnabled && loopEndInBeats > loopStartInBeats) {
            double loopLength = loopEndInBeats - loopStartInBeats;
            while (positionInBeats >= loopEndInBeats) {
                positionInBeats -= loopLength;
            }
        }
    }
}
