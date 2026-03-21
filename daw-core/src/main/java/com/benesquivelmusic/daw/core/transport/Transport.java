package com.benesquivelmusic.daw.core.transport;

/**
 * Controls the playback transport of the DAW (play, stop, record, pause).
 *
 * <p>The transport maintains the current playback position and state,
 * coordinating with the audio engine to start and stop audio processing.</p>
 */
public final class Transport {

    private TransportState state = TransportState.STOPPED;
    private double positionInBeats = 0.0;
    private double tempo = 120.0;
    private int timeSignatureNumerator = 4;
    private int timeSignatureDenominator = 4;

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
}
