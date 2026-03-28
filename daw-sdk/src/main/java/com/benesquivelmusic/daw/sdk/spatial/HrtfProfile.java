package com.benesquivelmusic.daw.sdk.spatial;

/**
 * Built-in HRTF profiles for binaural monitoring with different head sizes.
 *
 * <p>Each profile represents a Head-Related Transfer Function dataset
 * optimized for a particular head circumference range. Selecting the
 * correct profile improves spatial accuracy when monitoring through
 * headphones.</p>
 *
 * <p>Users who require a more precise match can import a custom HRTF
 * from a SOFA file instead of using one of these built-in profiles.</p>
 *
 * @see BinauralRenderer
 * @see HrtfData
 */
public enum HrtfProfile {

    /** Small head size (circumference ≈ 52–54 cm). */
    SMALL("Small", 53.0),

    /** Medium head size — the default (circumference ≈ 56–58 cm). */
    MEDIUM("Medium", 57.0),

    /** Large head size (circumference ≈ 60–62 cm). */
    LARGE("Large", 61.0);

    private final String displayName;
    private final double headCircumferenceCm;

    HrtfProfile(String displayName, double headCircumferenceCm) {
        this.displayName = displayName;
        this.headCircumferenceCm = headCircumferenceCm;
    }

    /**
     * Returns the human-readable display name for this profile.
     *
     * @return the display name
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Returns the nominal head circumference in centimeters for this profile.
     *
     * @return the head circumference in cm
     */
    public double headCircumferenceCm() {
        return headCircumferenceCm;
    }
}
