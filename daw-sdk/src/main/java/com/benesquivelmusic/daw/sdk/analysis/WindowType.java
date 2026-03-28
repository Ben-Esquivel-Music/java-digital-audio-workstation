package com.benesquivelmusic.daw.sdk.analysis;

/**
 * Windowing functions for FFT-based spectrum analysis.
 *
 * <p>Each window type trades off frequency resolution against spectral
 * leakage. Use {@link #HANN} for general-purpose analysis,
 * {@link #HAMMING} for narrowband signals, and {@link #BLACKMAN_HARRIS}
 * for high-dynamic-range measurements requiring minimal side-lobe
 * leakage.</p>
 */
public enum WindowType {

    /** Hann (Hanning) window — good general-purpose choice. */
    HANN("Hann"),

    /** Hamming window — slightly less side-lobe leakage than Hann. */
    HAMMING("Hamming"),

    /** Blackman-Harris window — excellent side-lobe suppression for high-DR analysis. */
    BLACKMAN_HARRIS("Blackman-Harris");

    private final String displayName;

    WindowType(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns a human-readable display name for this window type.
     *
     * @return display name (e.g. "Hann", "Blackman-Harris")
     */
    public String displayName() {
        return displayName;
    }
}
