package com.benesquivelmusic.daw.sdk.telemetry;

import java.util.Objects;

/**
 * Immutable Speaker Boundary Interference Response (SBIR) prediction for
 * one speaker / boundary combination.
 *
 * <p>Captures a sampled magnitude-frequency response curve at the
 * listening position together with the worst comb-filter notch found in
 * the curve. Consumers — the &quot;Boundary Response&quot; panel in
 * {@code TelemetrySetupPanel}, the notch-risk overlay on
 * {@code RoomTelemetryDisplay}, and the suggestions engine — render or
 * react to these fields.</p>
 *
 * <p>The {@code frequenciesHz} and {@code magnitudeDb} arrays are
 * defensively copied at construction so the record stays immutable.
 * Callers that need to read the curve should treat the returned arrays
 * as read-only snapshots.</p>
 *
 * @param frequenciesHz      the frequency bins in Hz (must have the same
 *                           length as {@code magnitudeDb})
 * @param magnitudeDb        the magnitude response in dB at each bin
 *                           (0&nbsp;dB = direct sound only, no
 *                           interference)
 * @param worstNotchHz       the frequency at which the deepest notch
 *                           occurs, in Hz
 * @param worstNotchDepthDb  the depth of the deepest notch in dB
 *                           (negative number — e.g. {@code -8.0} for an
 *                           8&nbsp;dB null)
 * @param boundary           which boundary the prediction is attributed to
 */
public record SbirPrediction(
        double[] frequenciesHz,
        double[] magnitudeDb,
        double worstNotchHz,
        double worstNotchDepthDb,
        BoundaryKind boundary) {

    public SbirPrediction {
        Objects.requireNonNull(frequenciesHz, "frequenciesHz must not be null");
        Objects.requireNonNull(magnitudeDb, "magnitudeDb must not be null");
        Objects.requireNonNull(boundary, "boundary must not be null");
        if (frequenciesHz.length != magnitudeDb.length) {
            throw new IllegalArgumentException(
                    "frequenciesHz and magnitudeDb must have the same length: "
                            + frequenciesHz.length + " vs " + magnitudeDb.length);
        }
        // Defensive copy — record arrays would otherwise leak mutable state.
        frequenciesHz = frequenciesHz.clone();
        magnitudeDb = magnitudeDb.clone();
    }

    /** Returns a defensive copy of the frequency-bin array. */
    @Override
    public double[] frequenciesHz() {
        return frequenciesHz.clone();
    }

    /** Returns a defensive copy of the magnitude-response array. */
    @Override
    public double[] magnitudeDb() {
        return magnitudeDb.clone();
    }
}
