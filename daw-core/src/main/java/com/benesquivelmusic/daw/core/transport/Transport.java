package com.benesquivelmusic.daw.core.transport;

import com.benesquivelmusic.daw.sdk.transport.PunchRegion;

/**
 * Controls the playback transport of the DAW (play, stop, record, pause).
 *
 * <p>The transport maintains the current playback position and state,
 * coordinating with the audio engine to start and stop audio processing.</p>
 *
 * <p>Tempo and time signature data are managed by an associated
 * {@link TempoMap}, which supports multiple tempo and time signature
 * changes along the timeline.</p>
 */
public final class Transport {

    private static final double DEFAULT_LOOP_START = 0.0;
    private static final double DEFAULT_LOOP_END = 16.0;

    private TransportState state = TransportState.STOPPED;
    private double positionInBeats = 0.0;
    private final TempoMap tempoMap = new TempoMap();
    private boolean loopEnabled = false;
    private double loopStartInBeats = DEFAULT_LOOP_START;
    private double loopEndInBeats = DEFAULT_LOOP_END;
    private PunchRegion punchRegion;

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

    /**
     * Returns the tempo map that manages tempo and time signature changes.
     *
     * @return the tempo map
     */
    public TempoMap getTempoMap() {
        return tempoMap;
    }

    /**
     * Returns the initial (default) tempo in beats per minute (BPM).
     *
     * <p>This is a convenience method equivalent to reading the first tempo
     * change event from the {@link TempoMap}.</p>
     */
    public double getTempo() {
        return tempoMap.getTempoChanges().get(0).bpm();
    }

    /**
     * Sets the initial tempo in BPM by replacing the tempo change event at beat 0.
     *
     * @param tempo BPM value (must be between 20 and 999)
     */
    public void setTempo(double tempo) {
        if (tempo < 20.0 || tempo > 999.0) {
            throw new IllegalArgumentException("tempo must be between 20 and 999 BPM: " + tempo);
        }
        tempoMap.addTempoChange(TempoChangeEvent.instant(0.0, tempo));
    }

    /**
     * Returns the initial time signature numerator.
     *
     * <p>This is a convenience method equivalent to reading the first time
     * signature change event from the {@link TempoMap}.</p>
     */
    public int getTimeSignatureNumerator() {
        return tempoMap.getTimeSignatureChanges().get(0).numerator();
    }

    /**
     * Returns the initial time signature denominator.
     *
     * <p>This is a convenience method equivalent to reading the first time
     * signature change event from the {@link TempoMap}.</p>
     */
    public int getTimeSignatureDenominator() {
        return tempoMap.getTimeSignatureChanges().get(0).denominator();
    }

    /**
     * Sets the initial time signature by replacing the time signature change at beat 0.
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
        tempoMap.addTimeSignatureChange(new TimeSignatureChangeEvent(0.0, numerator, denominator));
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

    /**
     * Installs a frame-based punch-in/out region on the transport.
     *
     * <p>When the region is {@linkplain PunchRegion#enabled() enabled}, the
     * recording pipeline captures input only within
     * {@code [startFrames, endFrames)} while the transport continues to play
     * back normally outside that range. This enables auto-punch: the record
     * arm can remain pressed across multiple passes and only the punch range
     * is captured each time.</p>
     *
     * @param punchRegion the punch region to install (must not be {@code null};
     *                    use {@link #clearPunchRegion()} to remove)
     * @throws NullPointerException if {@code punchRegion} is {@code null}
     */
    public void setPunchRegion(PunchRegion punchRegion) {
        if (punchRegion == null) {
            throw new NullPointerException("punchRegion must not be null; use clearPunchRegion() to remove");
        }
        this.punchRegion = punchRegion;
    }

    /**
     * Removes any installed punch region. After this call
     * {@link #isPunchEnabled()} returns {@code false} and
     * {@link #getPunchRegion()} returns {@code null}.
     */
    public void clearPunchRegion() {
        this.punchRegion = null;
    }

    /**
     * Returns the currently installed punch region, or {@code null} if none
     * has been set. The region may be present but disabled; use
     * {@link #isPunchEnabled()} to test whether punch recording is active.
     *
     * @return the punch region, or {@code null}
     */
    public PunchRegion getPunchRegion() {
        return punchRegion;
    }

    /**
     * Returns whether punch recording is currently active — i.e. a punch
     * region has been installed <em>and</em> its {@code enabled} flag is
     * {@code true}.
     *
     * @return {@code true} if punch recording should gate input capture
     */
    public boolean isPunchEnabled() {
        return punchRegion != null && punchRegion.enabled();
    }
}
