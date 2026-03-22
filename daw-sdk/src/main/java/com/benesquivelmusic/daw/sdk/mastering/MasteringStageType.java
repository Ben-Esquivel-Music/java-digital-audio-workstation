package com.benesquivelmusic.daw.sdk.mastering;

/**
 * Standard stages in a mastering signal chain.
 *
 * <p>The typical mastering chain order is:
 * Gain staging → EQ (corrective) → Compression → EQ (tonal)
 * → Stereo imaging → Limiting → Dithering.</p>
 */
public enum MasteringStageType {

    /** Gain staging — sets the initial level before processing. */
    GAIN_STAGING,

    /** Corrective EQ — surgical fixes (resonances, rumble). */
    EQ_CORRECTIVE,

    /** Dynamic range compression. */
    COMPRESSION,

    /** Tonal EQ — broad tonal shaping after compression. */
    EQ_TONAL,

    /** Stereo imaging — width and mid/side balance. */
    STEREO_IMAGING,

    /** Brick-wall limiting — final loudness ceiling. */
    LIMITING,

    /** Dithering — noise shaping for bit-depth reduction. */
    DITHERING
}
