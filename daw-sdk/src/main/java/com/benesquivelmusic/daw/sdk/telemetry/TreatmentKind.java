package com.benesquivelmusic.daw.sdk.telemetry;

/**
 * Kind of acoustic treatment that can be installed on a room surface.
 *
 * <p>Broadband absorbers tame mid/high reflections (first-reflection points,
 * rear-wall flutter). Low-frequency traps are placed in corners to reduce
 * pressure zones that drive standing modes. Diffusers scatter late energy
 * without removing it, preserving liveliness while breaking up flutter.</p>
 */
public enum TreatmentKind {

    /** Broadband porous absorber (e.g. 100 mm rockwool panel, 125 Hz–4 kHz). */
    ABSORBER_BROADBAND(0.85, false),

    /**
     * Low-frequency pressure trap for corner mounting
     * (e.g. 600 mm triangular bass trap, &lt; 200 Hz).
     */
    ABSORBER_LF_TRAP(0.55, false),

    /** Skyline / primitive-root diffuser (broadband scattering). */
    DIFFUSER_SKYLINE(0.30, true),

    /** Quadratic-residue (QRD) diffuser — narrow-band, precise scattering. */
    DIFFUSER_QUADRATIC(0.20, true);

    private final double effectiveAbsorption;
    private final boolean diffusing;

    TreatmentKind(double effectiveAbsorption, boolean diffusing) {
        this.effectiveAbsorption = effectiveAbsorption;
        this.diffusing = diffusing;
    }

    /**
     * Returns the effective mid-band absorption coefficient of this
     * treatment in [0, 1]. Diffusers primarily scatter rather than absorb,
     * but still contribute a small effective-absorption value because their
     * surface roughness dissipates some energy.
     */
    public double effectiveAbsorption() {
        return effectiveAbsorption;
    }

    /** Returns {@code true} when the treatment primarily scatters (diffuses) sound. */
    public boolean isDiffusing() {
        return diffusing;
    }
}
