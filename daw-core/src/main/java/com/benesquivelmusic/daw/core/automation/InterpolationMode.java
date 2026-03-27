package com.benesquivelmusic.daw.core.automation;

/**
 * Defines how values are interpolated between two adjacent automation points.
 */
public enum InterpolationMode {

    /** Linear interpolation — straight line between points. */
    LINEAR,

    /** Curved (ease-in/ease-out) interpolation using a smoothstep function. */
    CURVED
}
