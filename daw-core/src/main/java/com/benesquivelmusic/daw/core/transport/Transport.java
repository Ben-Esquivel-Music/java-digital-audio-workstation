package com.benesquivelmusic.daw.core.transport;

import com.benesquivelmusic.daw.sdk.transport.PreRollPostRoll;
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
    private PreRollPostRoll preRollPostRoll = PreRollPostRoll.DISABLED;
    private boolean inPreRoll = false;
    private boolean inPostRoll = false;

    /** Starts playback from the current position. */
    public void play() {
        state = TransportState.PLAYING;
        inPreRoll = false;
        inPostRoll = false;
    }

    /** Stops playback and resets the position to zero. */
    public void stop() {
        state = TransportState.STOPPED;
        positionInBeats = 0.0;
        inPreRoll = false;
        inPostRoll = false;
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

    // ── Pre-roll / Post-roll ───────────────────────────────────────────────

    /**
     * Installs a bar-based pre-roll/post-roll configuration on the transport.
     *
     * <p>When the configuration is {@linkplain PreRollPostRoll#enabled()
     * enabled}, {@link #playWithPreRoll()} seeks the playhead back by
     * {@code preBars} before beginning playback, and {@link #requestStop()}
     * extends playback by {@code postBars} before fully stopping. During
     * pre-roll and post-roll windows the click track keeps sounding but input
     * must not be captured — callers can check {@link #isInputCaptureGated()}
     * to implement that gating.</p>
     *
     * @param preRollPostRoll the configuration (must not be {@code null}; use
     *                        {@link #clearPreRollPostRoll()} to reset)
     * @throws NullPointerException if {@code preRollPostRoll} is {@code null}
     */
    public void setPreRollPostRoll(PreRollPostRoll preRollPostRoll) {
        if (preRollPostRoll == null) {
            throw new NullPointerException(
                    "preRollPostRoll must not be null; use clearPreRollPostRoll() to reset");
        }
        this.preRollPostRoll = preRollPostRoll;
    }

    /**
     * Resets the pre-roll/post-roll configuration to
     * {@link PreRollPostRoll#DISABLED}.
     */
    public void clearPreRollPostRoll() {
        this.preRollPostRoll = PreRollPostRoll.DISABLED;
        this.inPreRoll = false;
        this.inPostRoll = false;
    }

    /**
     * Returns the currently installed pre-roll/post-roll configuration.
     * Never {@code null}; defaults to {@link PreRollPostRoll#DISABLED}.
     *
     * @return the current configuration
     */
    public PreRollPostRoll getPreRollPostRoll() {
        return preRollPostRoll;
    }

    /**
     * Returns {@code true} if a pre-roll/post-roll configuration is installed
     * and its {@code enabled} flag is {@code true}.
     */
    public boolean isPreRollPostRollEnabled() {
        return preRollPostRoll.enabled();
    }

    /**
     * Starts playback with pre-roll applied: before entering the
     * {@link TransportState#PLAYING} state, the position is seeked backward by
     * {@code preBars × beatsPerBar} (clamped to zero). When no pre-roll is
     * configured (or the configuration is disabled or {@code preBars == 0}),
     * this method is equivalent to {@link #play()}.
     *
     * <p>The returned value is the number of beats that playback was rewound
     * by, which is useful for tests asserting sample-accurate pre-roll.</p>
     *
     * @return the number of beats by which the playhead was shifted back
     */
    public double playWithPreRoll() {
        double shift = 0.0;
        if (preRollPostRoll.enabled() && preRollPostRoll.preBars() > 0) {
            shift = preRollPostRoll.preRollBeats(getTimeSignatureNumerator());
            double target = positionInBeats - shift;
            if (target < 0) {
                shift = positionInBeats; // clamp
                target = 0.0;
            }
            positionInBeats = target;
            inPreRoll = shift > 0;
        } else {
            inPreRoll = false;
        }
        inPostRoll = false;
        state = TransportState.PLAYING;
        return shift;
    }

    /**
     * Requests that the transport stop. If a post-roll is configured, the
     * transport enters the post-roll window instead of stopping immediately;
     * the caller is expected to invoke {@link #advancePosition(double)} as
     * normal and call {@link #finishPostRoll()} once the post-roll duration
     * has elapsed. If no post-roll is configured, this method is equivalent
     * to {@link #stop()}.
     *
     * @return {@code true} if the transport entered a post-roll window
     *         (still running), {@code false} if it stopped immediately
     */
    public boolean requestStop() {
        if (preRollPostRoll.enabled() && preRollPostRoll.postBars() > 0
                && (state == TransportState.PLAYING
                        || state == TransportState.RECORDING)) {
            inPostRoll = true;
            inPreRoll = false;
            // Post-roll plays back, not records — drop out of RECORDING.
            state = TransportState.PLAYING;
            return true;
        }
        stop();
        return false;
    }

    /**
     * Completes a post-roll window started by {@link #requestStop()}, moving
     * the transport to the {@link TransportState#STOPPED} state. Safe to call
     * when not in post-roll (no-op).
     */
    public void finishPostRoll() {
        inPostRoll = false;
        state = TransportState.STOPPED;
        positionInBeats = 0.0;
    }

    /**
     * Marks the transport as having crossed the pre-roll boundary — i.e. the
     * playhead has reached the original starting position and real recording
     * may begin. Safe to call when not in pre-roll (no-op).
     */
    public void finishPreRoll() {
        inPreRoll = false;
    }

    /** Returns {@code true} if the transport is currently inside a pre-roll window. */
    public boolean isInPreRoll() {
        return inPreRoll;
    }

    /** Returns {@code true} if the transport is currently inside a post-roll window. */
    public boolean isInPostRoll() {
        return inPostRoll;
    }

    /**
     * Returns {@code true} if input capture must be suppressed at the current
     * moment — i.e. the transport is inside a pre-roll or post-roll window.
     * The click/metronome is <em>not</em> affected by this flag.
     */
    public boolean isInputCaptureGated() {
        return inPreRoll || inPostRoll;
    }
}
