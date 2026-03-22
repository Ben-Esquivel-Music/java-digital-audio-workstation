package com.benesquivelmusic.daw.sdk.spatial;

/**
 * Positioning modes for the 3D spatial panner.
 *
 * <p>{@link #SNAP_TO_SPEAKER} constrains the source to the nearest
 * speaker position in the configured layout, useful for discrete
 * channel-based workflows. {@link #FREE_FORM} allows continuous
 * 3D placement at any coordinate.</p>
 */
public enum PositioningMode {

    /** Snap the source position to the nearest speaker in the layout. */
    SNAP_TO_SPEAKER,

    /** Allow free-form continuous 3D placement. */
    FREE_FORM
}
