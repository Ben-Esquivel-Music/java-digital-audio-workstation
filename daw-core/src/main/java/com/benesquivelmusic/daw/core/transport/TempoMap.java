package com.benesquivelmusic.daw.core.transport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages tempo and time signature changes along the timeline.
 *
 * <p>The tempo map maintains an ordered list of {@link TempoChangeEvent}s and
 * {@link TimeSignatureChangeEvent}s. It always contains at least one initial
 * tempo event (at beat 0) and one initial time signature event (at beat 0).</p>
 *
 * <p>The map provides lookup methods to determine the effective tempo or time
 * signature at any beat position, and conversion methods to translate between
 * beat positions and absolute wall-clock seconds, correctly integrating over
 * variable tempo regions.</p>
 *
 * <p>Supported transition types include {@link TempoTransitionType#INSTANT}
 * (step change), {@link TempoTransitionType#LINEAR} (accelerando/ritardando),
 * and {@link TempoTransitionType#CURVED} (smooth S-curve).</p>
 */
public final class TempoMap {

    private static final double DEFAULT_TEMPO = 120.0;
    private static final int DEFAULT_NUMERATOR = 4;
    private static final int DEFAULT_DENOMINATOR = 4;

    private final List<TempoChangeEvent> tempoChanges = new ArrayList<>();
    private final List<TimeSignatureChangeEvent> timeSignatureChanges = new ArrayList<>();

    /**
     * Creates a new tempo map with default initial values (120 BPM, 4/4).
     */
    public TempoMap() {
        tempoChanges.add(TempoChangeEvent.instant(0.0, DEFAULT_TEMPO));
        timeSignatureChanges.add(new TimeSignatureChangeEvent(0.0, DEFAULT_NUMERATOR, DEFAULT_DENOMINATOR));
    }

    /**
     * Creates a new tempo map with the given initial tempo and time signature.
     *
     * @param initialTempo       the initial tempo in BPM (must be between 20 and 999)
     * @param initialNumerator   beats per bar (must be positive)
     * @param initialDenominator note value of each beat (must be positive)
     */
    public TempoMap(double initialTempo, int initialNumerator, int initialDenominator) {
        tempoChanges.add(TempoChangeEvent.instant(0.0, initialTempo));
        timeSignatureChanges.add(new TimeSignatureChangeEvent(0.0, initialNumerator, initialDenominator));
    }

    // ── Tempo change operations ─────────────────────────────────────────────

    /**
     * Adds a tempo change event to the map.
     *
     * <p>If a tempo change already exists at the same beat position, it is
     * replaced by the new event.</p>
     *
     * @param event the tempo change event to add
     * @throws NullPointerException if event is {@code null}
     */
    public void addTempoChange(TempoChangeEvent event) {
        if (event == null) {
            throw new NullPointerException("event must not be null");
        }
        tempoChanges.removeIf(e -> Double.compare(e.positionInBeats(), event.positionInBeats()) == 0);
        tempoChanges.add(event);
        Collections.sort(tempoChanges);
    }

    /**
     * Removes the tempo change event at the given beat position.
     *
     * <p>The initial tempo change at beat 0 cannot be removed. If an attempt
     * is made to remove it, this method returns {@code false}.</p>
     *
     * @param positionInBeats the beat position of the event to remove
     * @return {@code true} if an event was removed
     */
    public boolean removeTempoChange(double positionInBeats) {
        if (Double.compare(positionInBeats, 0.0) == 0) {
            return false;
        }
        return tempoChanges.removeIf(e -> Double.compare(e.positionInBeats(), positionInBeats) == 0);
    }

    /**
     * Returns an unmodifiable view of all tempo change events, sorted by position.
     *
     * @return the list of tempo change events
     */
    public List<TempoChangeEvent> getTempoChanges() {
        return Collections.unmodifiableList(tempoChanges);
    }

    /**
     * Returns the number of tempo change events.
     *
     * @return the count
     */
    public int getTempoChangeCount() {
        return tempoChanges.size();
    }

    /**
     * Returns the effective tempo at the given beat position, accounting for
     * all tempo changes and their transition types.
     *
     * <p>A tempo change event's transition type describes how the tempo
     * transitions from the previous event's value to this event's value
     * over the interval between the two events.</p>
     *
     * <p>For {@link TempoTransitionType#INSTANT}, the tempo steps to the new
     * value at the change point. For {@link TempoTransitionType#LINEAR}, the
     * tempo linearly ramps from the previous value to the new value over the
     * interval. For {@link TempoTransitionType#CURVED}, the tempo follows a
     * smooth S-curve (smoothstep) over that interval.</p>
     *
     * @param positionInBeats the beat position (&ge; 0)
     * @return the effective tempo in BPM
     */
    public double getTempoAtBeat(double positionInBeats) {
        if (tempoChanges.size() == 1) {
            return tempoChanges.get(0).bpm();
        }

        int floorIndex = findLastIndexAtOrBefore(tempoChanges, positionInBeats);
        TempoChangeEvent floorEvent = tempoChanges.get(floorIndex);

        // Check if there is a next event with a non-instant transition
        int nextIndex = floorIndex + 1;
        if (nextIndex < tempoChanges.size()) {
            TempoChangeEvent nextEvent = tempoChanges.get(nextIndex);
            if (nextEvent.transitionType() != TempoTransitionType.INSTANT) {
                // We are in the transition zone between floorEvent and nextEvent
                double segmentStart = floorEvent.positionInBeats();
                double segmentEnd = nextEvent.positionInBeats();
                double t = (positionInBeats - segmentStart) / (segmentEnd - segmentStart);
                t = Math.max(0.0, Math.min(1.0, t));

                if (nextEvent.transitionType() == TempoTransitionType.LINEAR) {
                    return floorEvent.bpm() + t * (nextEvent.bpm() - floorEvent.bpm());
                }

                // CURVED: smoothstep interpolation
                double smoothT = t * t * (3.0 - 2.0 * t);
                return floorEvent.bpm() + smoothT * (nextEvent.bpm() - floorEvent.bpm());
            }
        }

        return floorEvent.bpm();
    }

    // ── Time signature change operations ────────────────────────────────────

    /**
     * Adds a time signature change event to the map.
     *
     * <p>If a time signature change already exists at the same beat position,
     * it is replaced by the new event.</p>
     *
     * @param event the time signature change event to add
     * @throws NullPointerException if event is {@code null}
     */
    public void addTimeSignatureChange(TimeSignatureChangeEvent event) {
        if (event == null) {
            throw new NullPointerException("event must not be null");
        }
        timeSignatureChanges.removeIf(e -> Double.compare(e.positionInBeats(), event.positionInBeats()) == 0);
        timeSignatureChanges.add(event);
        Collections.sort(timeSignatureChanges);
    }

    /**
     * Removes the time signature change event at the given beat position.
     *
     * <p>The initial time signature change at beat 0 cannot be removed.</p>
     *
     * @param positionInBeats the beat position of the event to remove
     * @return {@code true} if an event was removed
     */
    public boolean removeTimeSignatureChange(double positionInBeats) {
        if (Double.compare(positionInBeats, 0.0) == 0) {
            return false;
        }
        return timeSignatureChanges.removeIf(
                e -> Double.compare(e.positionInBeats(), positionInBeats) == 0);
    }

    /**
     * Returns an unmodifiable view of all time signature change events, sorted by position.
     *
     * @return the list of time signature change events
     */
    public List<TimeSignatureChangeEvent> getTimeSignatureChanges() {
        return Collections.unmodifiableList(timeSignatureChanges);
    }

    /**
     * Returns the number of time signature change events.
     *
     * @return the count
     */
    public int getTimeSignatureChangeCount() {
        return timeSignatureChanges.size();
    }

    /**
     * Returns the effective time signature at the given beat position.
     *
     * @param positionInBeats the beat position (&ge; 0)
     * @return the effective time signature event
     */
    public TimeSignatureChangeEvent getTimeSignatureAtBeat(double positionInBeats) {
        int index = findLastTimeSigIndexAtOrBefore(positionInBeats);
        return timeSignatureChanges.get(index);
    }

    // ── Beat ↔ seconds conversion ───────────────────────────────────────────

    /**
     * Converts a beat position to wall-clock seconds, integrating over all
     * tempo changes between beat 0 and the given position.
     *
     * <p>This method correctly handles instant, linear, and curved tempo
     * transitions. A tempo change event's transition type describes how the
     * tempo transitions from the previous event to this event over the
     * interval between the two.</p>
     *
     * @param beats the position in beats (&ge; 0)
     * @return the position in seconds
     */
    public double beatsToSeconds(double beats) {
        if (beats <= 0.0) {
            return 0.0;
        }

        double seconds = 0.0;

        for (int i = 0; i < tempoChanges.size(); i++) {
            TempoChangeEvent current = tempoChanges.get(i);
            double segmentStart = current.positionInBeats();

            if (segmentStart >= beats) {
                break;
            }

            double segmentEnd;
            TempoTransitionType nextTransition;
            double nextBpm;

            if (i + 1 < tempoChanges.size()) {
                TempoChangeEvent next = tempoChanges.get(i + 1);
                segmentEnd = Math.min(beats, next.positionInBeats());
                nextTransition = next.transitionType();
                nextBpm = next.bpm();
            } else {
                segmentEnd = beats;
                nextTransition = TempoTransitionType.INSTANT;
                nextBpm = current.bpm();
            }

            double deltaBeats = segmentEnd - segmentStart;
            if (deltaBeats <= 0.0) {
                continue;
            }

            if (nextTransition == TempoTransitionType.INSTANT) {
                // Constant tempo throughout this segment
                seconds += deltaBeats * 60.0 / current.bpm();
            } else {
                // Tempo transitions from current.bpm to nextBpm over the full interval
                double fullSegmentEnd = (i + 1 < tempoChanges.size())
                        ? tempoChanges.get(i + 1).positionInBeats()
                        : beats;
                double fullLength = fullSegmentEnd - segmentStart;
                seconds += integrateTransition(
                        current.bpm(), nextBpm, nextTransition,
                        segmentStart, fullLength,
                        segmentStart, segmentEnd);
            }
        }

        return seconds;
    }

    /**
     * Converts wall-clock seconds to a beat position by numerically inverting
     * {@link #beatsToSeconds(double)}.
     *
     * <p>Uses a bisection method for robustness across all transition types.</p>
     *
     * @param seconds the position in seconds (&ge; 0)
     * @return the position in beats
     */
    public double secondsToBeats(double seconds) {
        if (seconds <= 0.0) {
            return 0.0;
        }

        // Use Newton-like bisection: find beats such that beatsToSeconds(beats) == seconds
        double lo = 0.0;
        // Upper bound estimate: use the minimum tempo to get a safe upper bound
        double minTempo = Double.MAX_VALUE;
        for (TempoChangeEvent event : tempoChanges) {
            if (event.bpm() < minTempo) {
                minTempo = event.bpm();
            }
        }
        double hi = seconds * minTempo / 60.0 + seconds * 999.0 / 60.0;

        for (int iteration = 0; iteration < 100; iteration++) {
            double mid = (lo + hi) / 2.0;
            double midSeconds = beatsToSeconds(mid);
            if (Math.abs(midSeconds - seconds) < 1e-12) {
                return mid;
            }
            if (midSeconds < seconds) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2.0;
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    /**
     * Finds the index of the last tempo change event at or before the given position.
     */
    private int findLastIndexAtOrBefore(List<TempoChangeEvent> events, double position) {
        int result = 0;
        for (int i = 1; i < events.size(); i++) {
            if (events.get(i).positionInBeats() <= position) {
                result = i;
            } else {
                break;
            }
        }
        return result;
    }

    /**
     * Finds the index of the last time signature change event at or before the given position.
     */
    private int findLastTimeSigIndexAtOrBefore(double position) {
        int result = 0;
        for (int i = 1; i < timeSignatureChanges.size(); i++) {
            if (timeSignatureChanges.get(i).positionInBeats() <= position) {
                result = i;
            } else {
                break;
            }
        }
        return result;
    }

    /**
     * Integrates 60/tempo(b) over [from, to] for a transition segment using
     * numerical Simpson's rule.
     */
    private double integrateTransition(double startBpm, double endBpm,
                                       TempoTransitionType type,
                                       double transitionStart, double transitionLength,
                                       double from, double to) {
        int steps = 64;
        double h = (to - from) / steps;
        double sum = 0.0;

        for (int i = 0; i <= steps; i++) {
            double b = from + i * h;
            double t = (b - transitionStart) / transitionLength;
            t = Math.max(0.0, Math.min(1.0, t));

            double tempo;
            if (type == TempoTransitionType.LINEAR) {
                tempo = startBpm + t * (endBpm - startBpm);
            } else {
                // Smoothstep for CURVED
                double smoothT = t * t * (3.0 - 2.0 * t);
                tempo = startBpm + smoothT * (endBpm - startBpm);
            }

            double weight;
            if (i == 0 || i == steps) {
                weight = 1.0;
            } else if (i % 2 == 1) {
                weight = 4.0;
            } else {
                weight = 2.0;
            }
            sum += weight * (60.0 / tempo);
        }

        return sum * h / 3.0;
    }
}
