package com.benesquivelmusic.daw.core.export.aaf;

import com.benesquivelmusic.daw.core.audio.FadeCurveType;

/**
 * Fade-curve shapes preserved in AAF output.
 *
 * <p>AAF distinguishes the curve type per fade boundary; the writer
 * captures one of these values for each clip's fade-in and fade-out so
 * that downstream tools can render the same shape rather than collapsing
 * everything to a linear ramp.</p>
 */
public enum AafFadeCurve {

    /** Straight-line amplitude ramp. */
    LINEAR,

    /** Cosine/sine equal-power crossfade shape. */
    EQUAL_POWER,

    /** Smooth S-shaped curve (slow–fast–slow). */
    S_CURVE;

    /**
     * Maps a {@link FadeCurveType} from the audio engine onto its AAF
     * equivalent.
     */
    public static AafFadeCurve from(FadeCurveType curveType) {
        return switch (curveType) {
            case LINEAR      -> LINEAR;
            case EQUAL_POWER -> EQUAL_POWER;
            case S_CURVE     -> S_CURVE;
        };
    }
}
