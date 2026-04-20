package com.benesquivelmusic.daw.sdk.telemetry;

/**
 * Broadband directivity factor ({@code Q}) of a sound source.
 *
 * <p>The directivity factor is the ratio of the sound intensity radiated
 * on-axis to the intensity a non-directional (omnidirectional) source of
 * the same total power would produce at the same distance. A cardioid
 * source concentrates its energy forward and therefore has
 * {@code Q &gt; 1}; the higher {@code Q}, the narrower the polar
 * response.</p>
 *
 * <p>The {@code Q} values enumerated here are the classical textbook
 * approximations used in room-acoustic calculations — exact polar
 * patterns are frequency-dependent, but a single broadband {@code Q}
 * per source is sufficient for critical-distance estimation.</p>
 *
 * <p>Critical distance scales as {@code d_c ∝ √Q}: a cardioid source
 * ({@code Q ≈ 2.5}) has a critical distance roughly {@code √2.5 ≈ 1.58}
 * times that of an omnidirectional source in the same room.</p>
 *
 * <p>Enums in Java are implicitly sealed — no further subtypes are
 * permissible beyond the four patterns listed below, which mirrors the
 * &quot;sealed enum&quot; language of the originating issue.</p>
 */
public enum SourceDirectivity {

    /** Uniform radiation in every direction. {@code Q = 1.0}. */
    OMNIDIRECTIONAL(1.0, "OMNI"),

    /** Standard cardioid polar pattern. {@code Q ≈ 2.5}. */
    CARDIOID(2.5, "CARD"),

    /** Supercardioid polar pattern (narrower main lobe, small rear lobe). {@code Q ≈ 3.9}. */
    SUPERCARDIOID(3.9, "SUPER"),

    /** Hypercardioid polar pattern (even narrower main lobe, larger rear lobe). {@code Q ≈ 4.0}. */
    HYPERCARDIOID(4.0, "HYPER");

    private final double q;
    private final String shortLabel;

    SourceDirectivity(double q, String shortLabel) {
        this.q = q;
        this.shortLabel = shortLabel;
    }

    /**
     * Returns the directivity factor {@code Q} for this polar pattern
     * (dimensionless, always ≥ 1).
     */
    public double q() {
        return q;
    }

    /**
     * Returns a compact, user-friendly label suitable for on-canvas
     * rendering (e.g. {@code "OMNI"}, {@code "CARD"}, {@code "SUPER"},
     * {@code "HYPER"}). Decoupled from {@link #name()} so the enum
     * constants can be renamed without disturbing the UI.
     */
    public String shortLabel() {
        return shortLabel;
    }
}
