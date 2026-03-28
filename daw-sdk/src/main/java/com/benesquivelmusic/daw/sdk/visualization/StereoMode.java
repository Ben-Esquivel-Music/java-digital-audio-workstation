package com.benesquivelmusic.daw.sdk.visualization;

/**
 * Display modes for stereo spectrum analysis.
 *
 * <p>Controls how left and right channel spectrum data is presented
 * in the spectrum display. Overlay mode draws both channels on the
 * same axes, while split mode divides the display area vertically.</p>
 */
public enum StereoMode {

    /** Left and right channel spectra overlaid on the same axes. */
    LEFT_RIGHT_OVERLAY("L/R Overlay"),

    /** Left and right channel spectra displayed in separate vertical halves. */
    LEFT_RIGHT_SPLIT("L/R Split");

    private final String displayName;

    StereoMode(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns a human-readable display name for this stereo mode.
     *
     * @return display name (e.g. "L/R Overlay")
     */
    public String displayName() {
        return displayName;
    }
}
