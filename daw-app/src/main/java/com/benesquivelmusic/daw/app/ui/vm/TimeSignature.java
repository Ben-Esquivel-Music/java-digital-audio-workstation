package com.benesquivelmusic.daw.app.ui.vm;

/**
 * Immutable view-model projection of the transport's initial time signature.
 *
 * <p>JavaFX has no {@code EnumProperty<T>} and no built-in time-signature type,
 * so {@link TransportVM} exposes the time signature as an
 * {@link javafx.beans.property.ObjectProperty ObjectProperty&lt;TimeSignature&gt;}.
 * Modelling it as a value carrier (rather than two loose {@code int} properties)
 * lets a control bind a single property and observe an atomic numerator+denominator
 * pair (Control Synchronization Design Book §3.3).</p>
 *
 * @param numerator   beats per bar (must be positive)
 * @param denominator note value of each beat (must be positive)
 */
public record TimeSignature(int numerator, int denominator) {

    /** Creates a time signature, validating that both fields are positive. */
    public TimeSignature {
        if (numerator <= 0) {
            throw new IllegalArgumentException("numerator must be positive: " + numerator);
        }
        if (denominator <= 0) {
            throw new IllegalArgumentException("denominator must be positive: " + denominator);
        }
    }

    /** Returns the conventional {@code numerator/denominator} display string (e.g. {@code "4/4"}). */
    @Override
    public String toString() {
        return numerator + "/" + denominator;
    }
}
