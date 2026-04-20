package com.benesquivelmusic.daw.sdk.telemetry;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable description of a single rectangular-room normal mode.
 *
 * <p>A room mode is a standing wave defined by the triple of integer
 * indices {@code (nx, ny, nz)}. Its resonant frequency is
 * <i>f</i><sub>n</sub> = (<i>c</i>/2)·&radic;((nx/Lx)² + (ny/Ly)² +
 * (nz/Lz)²), where <i>c</i>&nbsp;≈&nbsp;343&nbsp;m/s and <i>Lx, Ly,
 * Lz</i> are the room dimensions.</p>
 *
 * <p>The {@code magnitudeDb} field carries the relative pressure at a
 * specific listening position (the &quot;listening-position modal
 * magnitude&quot;). It is 0&nbsp;dB at a pressure antinode and
 * approaches −&infin; at a node, so negative values mean the listener
 * sits in a partial null for that mode — a good thing for axial modes
 * and a useful hint for seat placement.</p>
 *
 * @param frequencyHz the mode's resonant frequency in Hz
 * @param kind        axial / tangential / oblique classification
 * @param indices     the three mode indices {@code (nx, ny, nz)}
 *                    (defensively copied; always length&nbsp;3)
 * @param magnitudeDb the modal pressure at the listening position in dB
 *                    (0&nbsp;dB = pressure antinode; negative =
 *                    partial null)
 */
public record RoomMode(
        double frequencyHz,
        ModeKind kind,
        int[] indices,
        double magnitudeDb) {

    public RoomMode {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(indices, "indices must not be null");
        if (indices.length != 3) {
            throw new IllegalArgumentException(
                    "indices must have length 3: got " + indices.length);
        }
        if (!(frequencyHz > 0) || Double.isNaN(frequencyHz) || Double.isInfinite(frequencyHz)) {
            throw new IllegalArgumentException(
                    "frequencyHz must be a finite positive number: " + frequencyHz);
        }
        if (Double.isNaN(magnitudeDb) || Double.isInfinite(magnitudeDb)) {
            throw new IllegalArgumentException(
                    "magnitudeDb must be finite: " + magnitudeDb);
        }
        // Defensive copy — records of int[] would otherwise leak mutable state.
        indices = indices.clone();
    }

    /** Returns a defensive copy of the {@code (nx, ny, nz)} index triple. */
    @Override
    public int[] indices() {
        return indices.clone();
    }

    /** Convenience accessor for the X (width) mode index. */
    public int nx() {
        return indices[0];
    }

    /** Convenience accessor for the Y (length) mode index. */
    public int ny() {
        return indices[1];
    }

    /** Convenience accessor for the Z (height) mode index. */
    public int nz() {
        return indices[2];
    }

    @Override
    public String toString() {
        return "RoomMode[%s (%d,%d,%d) %.1f Hz, %.1f dB]".formatted(
                kind, indices[0], indices[1], indices[2], frequencyHz, magnitudeDb);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RoomMode other)) return false;
        return Double.compare(frequencyHz, other.frequencyHz) == 0
                && Double.compare(magnitudeDb, other.magnitudeDb) == 0
                && kind == other.kind
                && Arrays.equals(indices, other.indices);
    }

    @Override
    public int hashCode() {
        return Objects.hash(frequencyHz, kind, Arrays.hashCode(indices), magnitudeDb);
    }
}
