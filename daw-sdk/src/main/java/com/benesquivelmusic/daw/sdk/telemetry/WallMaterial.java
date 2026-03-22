package com.benesquivelmusic.daw.sdk.telemetry;

/**
 * Common wall/surface materials and their approximate absorption coefficients.
 *
 * <p>The absorption coefficient ranges from 0.0 (fully reflective) to
 * 1.0 (fully absorptive). These mid-frequency averages are useful for
 * room telemetry estimations.</p>
 */
public enum WallMaterial {

    /** Bare concrete — highly reflective. */
    CONCRETE(0.02),

    /** Standard drywall/plasterboard. */
    DRYWALL(0.05),

    /** Glass windows/panels. */
    GLASS(0.03),

    /** Hardwood flooring/paneling. */
    WOOD(0.10),

    /** Carpet or heavy rug. */
    CARPET(0.30),

    /** Acoustic foam panels. */
    ACOUSTIC_FOAM(0.70),

    /** Heavy curtains or drapes. */
    CURTAINS(0.50),

    /** Acoustic tiles (suspended ceiling). */
    ACOUSTIC_TILE(0.65);

    private final double absorptionCoefficient;

    WallMaterial(double absorptionCoefficient) {
        this.absorptionCoefficient = absorptionCoefficient;
    }

    /**
     * Returns the mid-frequency absorption coefficient.
     *
     * @return the absorption coefficient in [0.0, 1.0]
     */
    public double absorptionCoefficient() {
        return absorptionCoefficient;
    }
}
