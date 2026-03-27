package com.benesquivelmusic.daw.core.transport;

import java.util.Objects;

/**
 * Non-UI model that backs the timeline ruler.
 *
 * <p>Responsible for converting between beat positions and wall-clock seconds,
 * formatting position labels in the current {@link TimeDisplayMode}, and
 * selecting the appropriate ruler subdivision granularity for a given zoom
 * level.</p>
 *
 * <p>All methods delegate to the associated {@link Transport} for tempo and
 * time-signature information.</p>
 */
public final class TimelineRulerModel {

    private static final int TICKS_PER_BEAT = 960;
    private static final double MIN_PIXELS_BETWEEN_MARKS = 40.0;

    private final Transport transport;
    private TimeDisplayMode displayMode = TimeDisplayMode.BARS_BEATS_TICKS;

    /**
     * Creates a ruler model backed by the given transport.
     *
     * @param transport the transport providing tempo and time-signature data
     */
    public TimelineRulerModel(Transport transport) {
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
    }

    /** Returns the current display mode. */
    public TimeDisplayMode getDisplayMode() {
        return displayMode;
    }

    /** Sets the display mode. */
    public void setDisplayMode(TimeDisplayMode displayMode) {
        this.displayMode = Objects.requireNonNull(displayMode, "displayMode must not be null");
    }

    /** Toggles between {@link TimeDisplayMode#BARS_BEATS_TICKS} and {@link TimeDisplayMode#TIME}. */
    public void toggleDisplayMode() {
        displayMode = (displayMode == TimeDisplayMode.BARS_BEATS_TICKS)
                ? TimeDisplayMode.TIME
                : TimeDisplayMode.BARS_BEATS_TICKS;
    }

    /**
     * Converts a beat position to wall-clock seconds using the current tempo.
     *
     * @param beats the position in beats (&ge; 0)
     * @return the position in seconds
     */
    public double beatsToSeconds(double beats) {
        return beats * 60.0 / transport.getTempo();
    }

    /**
     * Converts wall-clock seconds to a beat position using the current tempo.
     *
     * @param seconds the position in seconds (&ge; 0)
     * @return the position in beats
     */
    public double secondsToBeats(double seconds) {
        return seconds * transport.getTempo() / 60.0;
    }

    /**
     * Returns the current playback position in seconds.
     *
     * @return the position in seconds
     */
    public double getPositionInSeconds() {
        return beatsToSeconds(transport.getPositionInBeats());
    }

    /**
     * Converts a pixel x-coordinate on the ruler to a beat position.
     *
     * @param pixelX        the x-coordinate in pixels
     * @param pixelsPerBeat the current scale (pixels per beat)
     * @return the beat position (&ge; 0)
     */
    public double pixelToBeats(double pixelX, double pixelsPerBeat) {
        if (pixelsPerBeat <= 0) {
            throw new IllegalArgumentException("pixelsPerBeat must be positive: " + pixelsPerBeat);
        }
        return Math.max(0.0, pixelX / pixelsPerBeat);
    }

    /**
     * Converts a beat position to a pixel x-coordinate on the ruler.
     *
     * @param beats         the beat position
     * @param pixelsPerBeat the current scale (pixels per beat)
     * @return the pixel x-coordinate
     */
    public double beatsToPixel(double beats, double pixelsPerBeat) {
        return beats * pixelsPerBeat;
    }

    /**
     * Formats a beat position as a human-readable label according to the
     * current {@link #getDisplayMode()}.
     *
     * <p>In {@link TimeDisplayMode#BARS_BEATS_TICKS} mode the format is
     * {@code bar:beat:tick} (1-based bars and beats).  In
     * {@link TimeDisplayMode#TIME} mode the format is {@code mm:ss:ms}.</p>
     *
     * @param positionInBeats the position in beats
     * @return the formatted label
     */
    public String formatPosition(double positionInBeats) {
        if (displayMode == TimeDisplayMode.BARS_BEATS_TICKS) {
            return formatBarsBeatsTicks(positionInBeats);
        } else {
            return formatTime(positionInBeats);
        }
    }

    /**
     * Determines the subdivision interval (in beats) that keeps ruler marks at
     * least {@link #MIN_PIXELS_BETWEEN_MARKS} pixels apart at the given zoom.
     *
     * <p>The algorithm walks a sequence of musically meaningful subdivisions
     * (bars, halves, quarters, eighths, sixteenths, thirty-seconds) and returns
     * the finest subdivision whose pixel spacing exceeds the threshold.</p>
     *
     * @param pixelsPerBeat the current horizontal scale
     * @return the subdivision interval in beats
     */
    public double subdivisionForZoom(double pixelsPerBeat) {
        int beatsPerBar = transport.getTimeSignatureNumerator();
        double[] subdivisions = {
                beatsPerBar * 4,    // 4 bars
                beatsPerBar * 2,    // 2 bars
                beatsPerBar,        // 1 bar
                beatsPerBar / 2.0,  // half bar
                1.0,                // quarter note
                0.5,                // eighth note
                0.25,               // sixteenth note
                0.125               // thirty-second note
        };
        for (int i = subdivisions.length - 1; i >= 0; i--) {
            double sub = subdivisions[i];
            if (sub > 0 && sub * pixelsPerBeat >= MIN_PIXELS_BETWEEN_MARKS) {
                return sub;
            }
        }
        return subdivisions[0];
    }

    /** Returns the backing transport. */
    public Transport getTransport() {
        return transport;
    }

    // ── private helpers ─────────────────────────────────────────────────────

    private String formatBarsBeatsTicks(double positionInBeats) {
        int beatsPerBar = transport.getTimeSignatureNumerator();
        int totalTicks = (int) Math.round(positionInBeats * TICKS_PER_BEAT);
        int ticksPerBar = beatsPerBar * TICKS_PER_BEAT;
        int bar = totalTicks / ticksPerBar + 1;
        int remaining = totalTicks % ticksPerBar;
        int beat = remaining / TICKS_PER_BEAT + 1;
        int ticks = remaining % TICKS_PER_BEAT;
        return String.format("%d:%d:%03d", bar, beat, ticks);
    }

    private String formatTime(double positionInBeats) {
        double totalSeconds = beatsToSeconds(positionInBeats);
        int minutes = (int) (totalSeconds / 60);
        double remainder = totalSeconds - minutes * 60.0;
        int secs = (int) remainder;
        int ms = (int) Math.round((remainder - secs) * 1000);
        if (ms >= 1000) {
            ms -= 1000;
            secs += 1;
        }
        return String.format("%02d:%02d:%03d", minutes, secs, ms);
    }
}
